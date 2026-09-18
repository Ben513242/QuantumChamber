package dev.quantumchamber.corridor;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.persistence.SessionSemantics;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Box;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import java.util.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.server.world.ChunkTicketType;

public final class CorridorPageManager {
    public record MappingRef(UUID sessionUuid, long instanceEpoch, int slotId) {}
    public record MappingView(MappingRef ref, long firstCorePage, long lastCorePage, long aliasStartBlock,
            long aliasEndBlock, long logicalAnchorBlock, BlockPos localBlockOrigin, Direction outwardFacing, BlockBox bounds) {
        public MappingView { localBlockOrigin = localBlockOrigin.toImmutable(); bounds = SlotAllocator.copy(bounds); }
        @Override public BlockBox bounds() { return SlotAllocator.copy(bounds); }
    }
    public record MappingSet(long epoch, List<MappingView> instances) {
        public MappingSet { instances = List.copyOf(instances); }
    }
    public record PreparedMappings(UUID token, UUID sessionUuid, long baseEpoch, MappingSet target) {}
    public record PhysicalPose(Vec3d position, Vec3d velocity, float yaw, float pitch) {}
    public record LogicalPose(double lateral, double height, double logicalZ, Vec3d localVelocity, float localYaw, float pitch) {}
    public record EntityMove(UUID entityUuid, MappingRef source, MappingRef target, LogicalPose logicalPose,
            PhysicalPose before, PhysicalPose after) {}
    public record RemapBatch(UUID token, PreparedMappings prepared, List<EntityMove> moves) {
        public RemapBatch { moves = List.copyOf(moves); }
    }
    public record RetiredMappings(UUID token, UUID sessionUuid, List<MappingRef> instances) {
        public RetiredMappings { instances = List.copyOf(instances); }
    }
    private static CorridorPageManager active;
    private static final ChunkTicketType<UUID> TICKET=ChunkTicketType.create("quantumchamber_corridor",Comparator.comparing(UUID::toString));
    private MinecraftServer server;
    private ServerWorld world;
    private SessionRecoveryState journal;
    private final SlotAllocator allocator = new SlotAllocator();
    private final Map<UUID,Space> spaces = new LinkedHashMap<>();
    private final Map<Integer,BlockBox> protectedLeases = new LinkedHashMap<>();
    private final Set<UUID> completedReleases = new HashSet<>();
    private int budgetTick = Integer.MIN_VALUE, writes, lastTick = Integer.MIN_VALUE;
    private int peakCenterTickets;

    public static CorridorPageManager forServer(MinecraftServer server) {
        if (active == null || active.server != server || server.getWorld(SuperpositionWorld.KEY) != active.world) {
            throw new IllegalStateException("沒有此 server/world 的走廊 authority");
        }
        active.requireThread();
        return active;
    }
    public void attach(MinecraftServer server) {
        if (!server.isOnThread() || active != null || this.server != null) throw new IllegalStateException("authority 不可重複 attach");
        var journal = SessionRecoveryState.get(server);
        journal.requireHealthy();
        var world = server.getWorld(SuperpositionWorld.KEY);
        if (world == null) throw new IllegalStateException("固定 Superposition 世界不存在");
        this.server=server; this.world=world; this.journal=journal;
        org.slf4j.LoggerFactory.getLogger("quantumchamber").info("走廊 bootstrap：storage={}，durable sessions={}",
                server.getSavePath(net.minecraft.util.WorldSavePath.ROOT),journal.flushedRecords().size());
        for (var record : journal.flushedRecords().values()) {
            var space=new Space(record.sessionUuid(),record.origin(),record.participants(),record.semantics());
            for (var lease : record.spaceLeases()) {
                validateFacingBounds(lease.bounds(),record.semantics().corridorFacing(record.origin().facing()));
                validateWorldBounds(lease.bounds());
                allocator.restore(lease.slotId(),lease.bounds());
                space.leases.put(lease.slotId(),lease);
                protectedLeases.put(lease.slotId(),lease.bounds());
            }
            spaces.put(space.id,space);
        }
        try {
            for (var space : spaces.values()) acquireTickets(space);
            active=this;
        } catch (RuntimeException failure) {
            for (var space : spaces.values()) removeAllTickets(space);
            throw failure;
        }
    }
    static void stopping(MinecraftServer server) {
        if (active!=null && active.server==server) {
            for (var space : active.spaces.values()) active.removeAllTickets(space);
            org.slf4j.LoggerFactory.getLogger("quantumchamber").info("走廊 STOPPING 票已對稱移除；中心票峰值={}，expanded coverage 峰值={} chunks",
                    active.peakCenterTickets,active.allocator.peakCoverage());
        }
    }
    static void detach(MinecraftServer server) {
        if (active != null && active.server == server) {
            active.spaces.clear(); active.protectedLeases.clear(); active.completedReleases.clear();
            active.server=null; active.world=null; active.journal=null; active=null;
        }
    }
    static boolean protectedPosition(ServerWorld world, BlockPos pos) {
        return active != null && active.world==world && active.server==world.getServer()
                && active.protectedLeases.values().stream().anyMatch(bounds -> bounds.contains(pos));
    }
    public void prepare(UUID sessionUuid, UUID chamberUuid, Direction facing, Set<Long> occupied) {
        var space=space(sessionUuid);
        if (!space.origin.chamberUuid().equals(chamberUuid) || space.origin.facing()!=facing
                || !space.pendingPages.equals(occupied) || space.pending==null || space.pending.baseEpoch()!=0) {
            throw new IllegalArgumentException("舊介面只可解析完全匹配的既有 initial reservation");
        }
        prepare(space.pending);
    }
    public void tick() {
        requireThread();
        if (lastTick==server.getTicks()) return;
        lastTick=server.getTicks(); resetBudget();
        try { requirePinCapacity(); }
        catch (IllegalStateException capacity) {
            for (var space : List.copyOf(spaces.values())) if (!space.failed && !space.releasing) fail(space,capacity);
        }
        for (var space : List.copyOf(spaces.values())) {
            try {
                if (space.releasing) { tickRelease(space); continue; }
                if (space.failed) continue;
                if (space.builds.isEmpty() && space.overlay.isEmpty() && space.retiring.isEmpty()) continue;
                var record=durable(space);
                var staged=journal.records().get(space.id);
                if (record.state()==SessionState.RETURNING || staged==null || staged.state()==SessionState.RETURNING) continue;
                for (var job : List.copyOf(space.builds.values())) {
                    if (!leaseReady(job.view.bounds())) continue;
                    if (!pins(job.view.bounds()).isEmpty()) throw new IllegalStateException("prepared 空間出現 live pin，停止幾何寫入");
                    while (writes<4096) {
                        if (job.next==null) job.next=job.builder.next();
                        if (job.next==null) {
                            space.builds.remove(job.view.ref().slotId());
                            org.slf4j.LoggerFactory.getLogger("quantumchamber").info("走廊幾何完成：session={} slot={} bounds={} elapsedTicks={} elapsedMs={}",
                                    space.id,job.view.ref().slotId(),job.view.bounds(),server.getTicks()-space.prepareTick,
                                    (System.nanoTime()-space.prepareNanos)/1_000_000);
                            break;
                        }
                        write(job.next.position(),job.next.state());
                        if (job.next.state().isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER)) {
                            SessionEntranceAllocator.identify(world,CorridorGeometry.entrance(job.view,space.semantics),space.origin.chamberUuid());
                        }
                        job.next=null;
                    }
                }
                while (writes<4096 && !space.overlay.isEmpty()) {
                    var cell=space.overlay.removeFirst(); write(cell.position(),cell.state());
                }
                for (int slot : List.copyOf(space.retiring)) tickRetirement(space,slot);
            } catch (RuntimeException failure) {
                fail(space,failure);
            }
        }
    }
    public boolean ready(UUID sessionUuid) {
        var space=space(sessionUuid);
        return space.pending!=null && ready(space.pending);
    }
    public ChamberFrame entrance(UUID sessionUuid) {
        var space=space(sessionUuid);
        var mappings=space.current.instances().isEmpty() && space.pending!=null ? space.pending.target() : space.current;
        return mappings.instances().stream().filter(CorridorGeometry::hasEntrance).findFirst()
                .map(view -> CorridorGeometry.entrance(view,space.semantics)).orElseThrow(() -> new IllegalStateException("目前局部映射沒有入口 replica"));
    }
    public Vec3d toPhysical(UUID sessionUuid, double lateral, double y, double logicalZ) {
        var space=space(sessionUuid);
        var view=select(space.current,logicalZ);
        return toPhysical(view.ref(),new LogicalPose(lateral,y,logicalZ,Vec3d.ZERO,0,0)).position();
    }
    public void release(UUID sessionUuid) {
        var space=space(sessionUuid);
        if (space.releasing) return;
        int outstanding=(int)spaces.values().stream().filter(value -> value.releasing).count();
        if (!canTrackRelease(completedReleases.size(),outstanding)) throw new IllegalStateException("收尾追蹤與未消費收據達容量");
        var record=durable(space);
        if (record.state()!=SessionState.RETURNING) throw new IllegalStateException("退休前必須 checked RETURNING");
        // ARMING落盤後、prepare之前也可能取消；退休同樣必須持有可見實體的完整票。
        acquireTickets(space);
        space.releasing=true; space.builds.clear(); space.overlay.clear();
    }
    public boolean releaseComplete(UUID sessionUuid) { requireThread(); return completedReleases.contains(sessionUuid); }
    static boolean canTrackRelease(int completed,int outstanding) { return completed<64 && outstanding<64-completed; }
    public void acknowledgeRelease(UUID sessionUuid) {
        requireThread();
        if (!completedReleases.remove(sessionUuid)) throw new IllegalStateException("沒有可消費的完成收據");
    }
    public MappingSet currentMappings(UUID sessionUuid) { return space(sessionUuid).current; }

    /** 只有整份 durable lease 的實體皆可見，才可發布返還 pin 快照。 */
    public Optional<List<UUID>> returnEntityPins(UUID sessionUuid) {
        var space=space(sessionUuid);
        if(durable(space).state()!=SessionState.RETURNING) throw new IllegalStateException("非 RETURNING 不可取得返還 pins");
        if(space.leases.values().stream().anyMatch(lease -> !leaseReady(lease.bounds()))) return Optional.empty();
        requirePinCapacity(); var result=new LinkedHashSet<UUID>();
        for(var lease : space.leases.values()) for(var entity : pins(lease.bounds())) result.add(entity.getUuid());
        return Optional.of(List.copyOf(result));
    }

    public Optional<Map<UUID,MappingRef>> currentEntityOwners(UUID sessionUuid) {
        var space=space(sessionUuid); durable(space);
        if(space.current.instances().isEmpty() || space.current.instances().stream().anyMatch(view -> !leaseReady(view.bounds())))
            return Optional.empty();
        requirePinCapacity(); var owners=new LinkedHashMap<UUID,MappingRef>();
        for(var view : space.current.instances()) for(var entity : pins(view.bounds())) {
            if(!contains(box(view.bounds()),entity.getBoundingBox()) || owners.put(entity.getUuid(),view.ref())!=null)
                throw new IllegalStateException("entity 沒有唯一完整 current bbox owner");
        }
        return Optional.of(Map.copyOf(owners));
    }

    public Map<UUID,MappingRef> affectedEntityOwners(PreparedMappings prepared) {
        var space=pending(prepared); durable(space);
        if(!ready(prepared) || changedSources(space).stream().anyMatch(view -> !leaseReady(view.bounds())))
            throw new IllegalStateException("affected owners 尚未實體就緒");
        requirePinCapacity(); return affectedOwners(space);
    }
    Optional<SessionEntranceDoorService.ToggleResult> toggleEntrance(ServerWorld target,BlockPos pos) {
        requireThread();
        if (target!=world) return Optional.empty();
        for (var space : spaces.values()) {
            var known=java.util.stream.Stream.concat(space.current.instances().stream(),space.pending==null ? java.util.stream.Stream.empty()
                    : space.pending.target().instances().stream()).filter(CorridorGeometry::hasEntrance)
                    .filter(view -> CorridorGeometry.entrance(view,space.semantics).controllerPos().equals(pos)).findFirst();
            if (known.isEmpty()) continue;
            var denied=new SessionEntranceDoorService.ToggleResult(false,"入口狀態、身分或操作條件不符，拒絕操作並保持保護。");
            if (space.pending!=null || space.failed || space.releasing || space.operations!=0
                    || !space.current.instances().contains(known.get())) return Optional.of(denied);
            try {
                var record=durable(space);
                if (record.state()!=SessionState.SUPERPOSITION || record.restoreEntryEffectOnReturn()) return Optional.of(denied);
                if (!(world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller) || controller.getWorld()!=world
                        || controller.isRemoved() || controller.instanceKind()!=ChamberInstanceKind.PROJECTION
                        || !space.origin.chamberUuid().equals(controller.chamberUuid())
                        || world.getBlockState(pos).get(ChamberControllerBlock.FACING)!=space.origin.facing()) return Optional.of(denied);
                resetBudget();
                // 預留最多 25 次正向與 25 次 rollback；所有實際嘗試仍共用同一計數器。
                if (writes>4096-50) return Optional.of(new SessionEntranceDoorService.ToggleResult(false,"本 tick 幾何預算不足，請稍後再試。"));
                space.operations++;
                try {
                    boolean changed=new ChamberDoorService().toggle(new WorldChamberBlockView(world),
                            (position,open) -> write(position,world.getBlockState(position).with(QuantumBulkheadBlock.OPEN,open)),
                            ChamberMutationExecutor.DIRECT,CorridorGeometry.entrance(known.get(),space.semantics));
                    if (!changed) {
                        fail(space,new IllegalStateException("入口整面門交易被拒絕")); return Optional.of(denied);
                    }
                    space.frontOpen=!space.frontOpen;
                    return Optional.of(new SessionEntranceDoorService.ToggleResult(true,"入口艙門已切換。"));
                } finally { space.operations--; }
            } catch (RuntimeException failure) {
                fail(space,failure); return Optional.of(denied);
            }
        }
        return Optional.empty();
    }
    public PreparedMappings prepareRemap(UUID sessionUuid, long expectedCurrentEpoch, Set<Long> occupiedPages) {
        var space=space(sessionUuid);
        if (space.current.epoch()!=expectedCurrentEpoch || expectedCurrentEpoch==0 || space.pending!=null || space.releasing
                || space.failed || space.operations!=0) throw new IllegalStateException("remap epoch／操作狀態不符");
        var record=durable(space);
        if (record.state()!=SessionState.SUPERPOSITION) throw new IllegalStateException("只有已發布 SUPERPOSITION 可 remap");
        requireActiveBuff(space);
        var oldSlots=Set.copyOf(space.leases.keySet());
        boolean reserved=false;
        try {
            requirePageCapacity(space,occupiedPages); requirePinCapacity();
            var prepared=reserve(space,expectedCurrentEpoch,occupiedPages); reserved=true;
            journal.put(new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),record.participants(),
                    List.copyOf(space.leases.values()),record.state(),record.restoreEntryEffectOnReturn(),record.semantics()));
            journal.flush(server); durable(space); acquireTickets(space); enqueue(space); return prepared;
        } catch (RuntimeException failure) {
            if (!reserved) for (int slot : List.copyOf(space.leases.keySet())) if (!oldSlots.contains(slot)) {
                space.leases.remove(slot); allocator.release(slot); protectedLeases.remove(slot);
            }
            fail(space,failure); throw failure;
        }
    }
    public boolean ready(PreparedMappings prepared) {
        var space=pending(prepared);
        if (!space.queued || space.failed || space.releasing || !space.builds.isEmpty()) return false;
        var record=durable(space); var staged=journal.records().get(space.id);
        return record.state()!=SessionState.RETURNING && staged!=null && staged.state()!=SessionState.RETURNING
                && prepared.target().instances().stream().allMatch(view -> leaseReady(view.bounds()));
    }
    public LogicalPose toLogical(MappingRef source, PhysicalPose physicalPose) {
        var view=view(source); validate(physicalPose);
        if (!box(view.bounds()).contains(physicalPose.position())) throw new IllegalArgumentException("physical pose 不在 source bounds");
        var frame=CorridorGeometry.frame(view);
        var local=CorridorGeometry.localPosition(frame,physicalPose.position());
        var facing=CorridorGeometry.localVector(frame,yawVector(physicalPose.yaw()));
        return new LogicalPose(local.x,local.y,local.z+view.logicalAnchorBlock(),
                CorridorGeometry.localVector(frame,physicalPose.velocity()),yaw(facing),physicalPose.pitch());
    }
    public PhysicalPose toPhysical(MappingRef target, LogicalPose logicalPose) {
        var view=view(target); validate(logicalPose);
        if (logicalPose.logicalZ()<view.aliasStartBlock() || logicalPose.logicalZ()>=view.aliasEndBlock()) {
            throw new IllegalArgumentException("logical pose 不在 target alias");
        }
        var frame=CorridorGeometry.frame(view);
        var position=CorridorGeometry.position(frame,logicalPose.lateral(),logicalPose.height(),logicalPose.logicalZ()-view.logicalAnchorBlock());
        if (!box(view.bounds()).contains(position)) throw new IllegalArgumentException("logical pose 超出完整 bounds");
        return new PhysicalPose(position,CorridorGeometry.vector(frame,logicalPose.localVelocity()),
                yaw(CorridorGeometry.vector(frame,yawVector(logicalPose.localYaw()))),logicalPose.pitch());
    }
    public RemapBatch beginRemap(PreparedMappings prepared, Map<UUID, MappingRef> affectedEntityOwners) {
        var space=pending(prepared);
        if (prepared.baseEpoch()==0 || !ready(prepared) || space.batch!=null || space.operations!=0) throw new IllegalStateException("remap 尚未就緒");
        if (durable(space).state()!=SessionState.SUPERPOSITION) throw new IllegalStateException("session 正在安全返還");
        requireActiveBuff(space);
        requirePinCapacity();
        var owners=affectedOwners(space);
        if (!owners.equals(affectedEntityOwners)) throw new IllegalArgumentException("affected entity 清單不等於最新完整 live pins");
        for (var target : changedTargets(space)) if (!pins(target.bounds()).isEmpty()) throw new IllegalStateException("新 target 已出現額外 pin");
        if (changedSources(space).stream().anyMatch(view -> !leaseReady(view.bounds()))) throw new IllegalStateException("source 實體載入尚未確認");
        var moves=new ArrayList<EntityMove>();
        for (var owner : owners.entrySet()) {
            var entity=world.getEntity(owner.getKey());
            if (entity==null) throw new IllegalStateException("live entity 已消失");
            var before=pose(entity); var logical=toLogical(owner.getValue(),before);
            var target=select(prepared.target(),logical.logicalZ()); var after=toPhysical(target.ref(),logical);
            if (!contains(box(target.bounds()),entity.getBoundingBox().offset(after.position().subtract(before.position())))) {
                throw new IllegalStateException("entity 完整 bbox 無法放入 target");
            }
            moves.add(new EntityMove(entity.getUuid(),owner.getValue(),target.ref(),logical,before,after));
        }
        space.batchTick=server.getTicks();
        space.batch=new RemapBatch(UUID.randomUUID(),prepared,moves);
        return space.batch;
    }
    public RetiredMappings commitRemap(RemapBatch batch) {
        var space=pending(batch.prepared());
        if (space.batch!=batch || space.batchTick!=server.getTicks()) throw new IllegalArgumentException("過期或偽造 remap batch");
        if (space.failed || space.releasing || durable(space).state()!=SessionState.SUPERPOSITION) throw new IllegalStateException("session 不再允許發布");
        requireActiveBuff(space);
        requirePinCapacity();
        var expected=batch.moves().stream().map(EntityMove::entityUuid).collect(java.util.stream.Collectors.toSet());
        if (!movingPins(space).equals(expected)) throw new IllegalStateException("begin 後出現新的 pin 或遺失 entity");
        for (var move : batch.moves()) {
            var entity=world.getEntity(move.entityUuid());
            if (entity==null || entity.getWorld()!=world || !samePose(pose(entity),move.after())
                    || !contains(box(view(move.target()).bounds()),entity.getBoundingBox())) {
                throw new IllegalStateException("全群 actual target 尚未確認；保留唯一 batch 與兩邊租約");
            }
        }
        var previous=changedSources(space);
        previous.forEach(view -> space.retiredViews.put(view.ref(),view));
        var retired=retiredToken(space,previous.stream().map(MappingView::ref).toList());
        space.current=space.pending.target(); space.currentPages=space.pendingPages;
        space.pending=null; space.pendingPages=Set.of(); space.queued=false; space.batch=null;
        return retired;
    }
    public RetiredMappings cancelPrepared(PreparedMappings prepared) {
        var space=pending(prepared);
        if (prepared.baseEpoch()==0) {
            if (space.queued || journal.records().containsKey(space.id) || journal.flushedRecords().containsKey(space.id)) {
                throw new IllegalStateException("只有零 geometry 且無 journal 的 provisional reservation 可取消");
            }
            space.provisionalCancelled=true;
        } else {
            if (durable(space).state()!=SessionState.SUPERPOSITION) throw new IllegalStateException("RETURNING 不可當作普通 remap 取消");
            if (space.batch!=null) {
                for (var move : space.batch.moves()) {
                    var entity=world.getEntity(move.entityUuid());
                    if (entity==null || entity.getWorld()!=world || !samePose(pose(entity),move.before())) {
                        throw new IllegalStateException("部分移動尚未安全 rollback，保留兩邊 owner");
                    }
                }
            }
            for (var target : changedTargets(space)) if (!pins(target.bounds()).isEmpty()) throw new IllegalStateException("target 仍有 pin");
        }
        var changed=changedTargets(space);
        changed.forEach(view -> space.retiredViews.put(view.ref(),view));
        var token=retiredToken(space,changed.stream().map(MappingView::ref).toList());
        space.pending=null; space.pendingPages=Set.of(); space.queued=false; space.batch=null; space.builds.clear();
        return token;
    }
    public void retire(RetiredMappings retired) {
        var space=space(retired.sessionUuid());
        if (space.retireTokens.get(retired.token())!=retired) throw new IllegalArgumentException("偽造或過期退休 token");
        if (space.operations!=0) throw new IllegalStateException("入口操作租約尚未結束");
        if (space.provisionalCancelled) {
            if (journal.records().containsKey(space.id) || journal.flushedRecords().containsKey(space.id)
                    || space.leases.values().stream().anyMatch(lease -> !pins(lease.bounds()).isEmpty())) {
                throw new IllegalStateException("provisional 取消仍有權威或 pin");
            }
            for (int slot : space.leases.keySet()) { allocator.release(slot); protectedLeases.remove(slot); }
            spaces.remove(space.id); return;
        }
        for (var ref : retired.instances()) {
            if (!space.retiredViews.containsKey(ref)) throw new IllegalArgumentException("退休內容不匹配已保留實例");
            space.retiring.add(ref.slotId());
        }
        if (retired.instances().isEmpty()) space.retireTokens.remove(retired.token());
    }
    public PreparedMappings reserveInitial(UUID sessionUuid, UUID chamberUuid, Direction facing, Set<Long> occupiedPages) {
        return reserveInitial(sessionUuid,chamberUuid,facing,occupiedPages,SessionSemantics.LEGACY_FORWARD_CONSUMED);
    }
    public PreparedMappings reserveInitial(UUID sessionUuid, UUID chamberUuid, Direction facing, Set<Long> occupiedPages,SessionSemantics semantics) {
        requireThread();
        if (spaces.containsKey(sessionUuid) || completedReleases.contains(sessionUuid) || journal.records().containsKey(sessionUuid)
                || !occupiedPages.equals(Set.of(0L))) throw new IllegalArgumentException("initial 必須是新的 session 與 occupied page 0");
        var registry=ChamberRegistryState.get(server); registry.requireHealthy();
        var record=registry.registry().records().get(chamberUuid);
        if (record==null || record.instanceKind()!=ChamberInstanceKind.ORIGIN || record.destroyed() || record.facing()!=facing) {
            throw new IllegalArgumentException("來源艙權威不匹配");
        }
        var origin=new ChamberOriginAuthority(chamberUuid,record.originWorldKey(),record.originDimensionRole(),
                record.anchorPos(),facing,ChamberInstanceKind.ORIGIN);
        var source=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,origin.worldKey()));
        if (source==null) throw new IllegalStateException("來源世界不存在");
        var cohort=new ChamberOccupantService().findParticipants(source,new ChamberFrame(record.anchorPos(),facing));
        if (cohort.isEmpty()) throw new IllegalStateException("initial cohort 不得為空");
        var snapshots=new ArrayList<SessionRecoveryRecord.Participant>();
        for (var player : cohort) {
            var effect=player.getStatusEffect(ModEffects.QUANTUM_STATE);
            if (effect==null) throw new IllegalStateException("來源 cohort 必須全員持有 QuantumState");
            snapshots.add(new SessionRecoveryRecord.Participant(player.getUuid(),player.getPos(),player.getVelocity(),player.getYaw(),
                    player.getPitch(),(net.minecraft.nbt.NbtCompound)effect.writeNbt(),false));
        }
        var space=new Space(sessionUuid,origin,snapshots,semantics);
        requirePageCapacity(space,occupiedPages);
        spaces.put(sessionUuid,space);
        try { return reserve(space,0,occupiedPages); }
        catch (RuntimeException failure) {
            // 尚未回傳 reservation，也沒有 journal 或任何 geometry。
            for (int slot : space.leases.keySet()) { allocator.release(slot); protectedLeases.remove(slot); }
            spaces.remove(sessionUuid); throw failure;
        }
    }
    public void prepare(PreparedMappings initial) {
        var space=pending(initial);
        if (initial.baseEpoch()!=0 || space.queued || space.releasing) throw new IllegalStateException("initial 狀態不合法");
        var record=durable(space);
        if (record.state()!=SessionState.ARMING || record.restoreEntryEffectOnReturn()!=(space.semantics==SessionSemantics.LEGACY_FORWARD_CONSUMED)
                || !participantsEqual(space.source,record.participants())) throw new IllegalStateException("initial 需要完整 durable ARMING 快照");
        acquireTickets(space); enqueue(space);
    }
    public void commitInitial(UUID sessionUuid, Set<UUID> cohort) {
        var space=space(sessionUuid);
        if (space.pending==null || space.pending.baseEpoch()!=0) throw new IllegalStateException("initial reservation 不存在");
        var record=durable(space);
        if (record.state()!=SessionState.SUPERPOSITION || record.restoreEntryEffectOnReturn()) {
            throw new IllegalStateException("initial 需要精確全 cohort 與 durable SUPERPOSITION,false");
        }
        try {
            if (!ready(space.pending) || !cohort.equals(ids(space.source)) || !participantsEqual(space.source,record.participants())) {
                throw new IllegalStateException("已提交 false 決策，但 initial readiness／cohort 權威不符");
            }
            checkInitialCohort(space,cohort);
            for (UUID id : cohort) PlayerCheckpointStore.saveAndVerify(server,server.getPlayerManager().getPlayer(id),Optional.empty());
            checkInitialCohort(space,cohort);
        } catch (IOException | RuntimeException failure) {
            fail(space,failure);
            throw new IllegalStateException("已提交 false 決策後的發布前核對失敗；保留空間等待安全返還",failure);
        }
        space.current=space.pending.target(); space.currentPages=space.pendingPages;
        space.pending=null; space.queued=false; space.rearOpen=true;
        var frame=entrance(sessionUuid);
        for (int y=1;y<=5;y++) for(int n=1;n<=5;n++) {
            if(space.semantics==SessionSemantics.LATERAL_BUFF_MAINTAINED) {
                for(int x : new int[]{0,6}) space.overlay.add(new CorridorGeometry.Cell(
                        ChamberSpaceCoordinates.block(frame,x,y,n),Blocks.AIR.getDefaultState()));
            } else space.overlay.add(new CorridorGeometry.Cell(ChamberSpaceCoordinates.block(frame,n,y,6),Blocks.AIR.getDefaultState()));
        }
    }

    private void requireThread() {
        if (server==null || !server.isOnThread() || active!=this || server.getWorld(SuperpositionWorld.KEY)!=world) {
            throw new IllegalStateException("走廊操作必須在唯一 authority 的 server thread");
        }
    }
    private Space space(UUID id) {
        requireThread(); var value=spaces.get(id);
        if (value==null) throw new IllegalArgumentException("未知 session");
        return value;
    }
    private Space pending(PreparedMappings prepared) {
        Objects.requireNonNull(prepared);
        var space=space(prepared.sessionUuid());
        if (space.pending!=prepared || space.current.epoch()!=prepared.baseEpoch()) throw new IllegalArgumentException("偽造或過期 prepared token");
        return space;
    }
    private SessionRecoveryRecord durable(Space space) {
        journal.requireHealthy();
        var record=journal.flushedRecords().get(space.id);
        // 比較 Space 的不可變來源與語意；狀態、returned 與 lease 進度不構成來源身分。
        if (record==null || !SessionRecoveryRecord.sameAuthority(new SessionRecoveryRecord(space.id,space.origin.chamberUuid(),
                space.origin,space.source,record.spaceLeases(),SessionState.RETURNING,false,space.semantics),record)
                || !leasesEqual(record.spaceLeases(),space.leases.values())) throw new IllegalStateException("durable lease／來源權威不符");
        return record;
    }
    private PreparedMappings reserve(Space space, long epoch, Set<Long> occupied) {
        var components=CorridorLayout.plan(occupied,576);
        var corridorFacing=space.semantics.corridorFacing(space.origin.facing());
        var views=new ArrayList<MappingView>();
        for (var component : components) {
            var retained=space.current.instances().stream().filter(view -> view.firstCorePage()==component.firstCorePage()
                    && view.lastCorePage()==component.lastCorePage() && view.aliasStartBlock()==component.aliasStartBlock()
                    && view.aliasEndBlock()==component.aliasEndBlock()).findFirst();
            if (retained.isPresent()) { views.add(retained.get()); continue; }
            long anchor=Math.multiplyExact(component.firstCorePage()+1,96);
            var lease=allocator.reserve(CorridorGeometry.relativeBounds(component,anchor,corridorFacing))
                    .orElseThrow(() -> new IllegalStateException("局部實例／完整 AABB 容量不足"));
            try { validateWorldBounds(lease.bounds()); }
            catch (RuntimeException failure) { allocator.release(lease.slotId()); throw failure; }
            var frame=new ChamberFrame(lease.origin(),corridorFacing);
            space.instanceEpoch=Math.incrementExact(space.instanceEpoch);
            var view=new MappingView(new MappingRef(space.id,space.instanceEpoch,lease.slotId()),component.firstCorePage(),component.lastCorePage(),
                    component.aliasStartBlock(),component.aliasEndBlock(),anchor,CorridorGeometry.block(frame,0,0,0),corridorFacing,lease.bounds());
            space.leases.put(lease.slotId(),new SessionRecoveryRecord.SpaceLease(lease.slotId(),lease.bounds()));
            protectedLeases.put(lease.slotId(),lease.bounds()); views.add(view);
        }
        space.pendingPages=Set.copyOf(occupied);
        space.pending=new PreparedMappings(UUID.randomUUID(),space.id,epoch,new MappingSet(epoch+1,views));
        return space.pending;
    }
    private void enqueue(Space space) {
        space.prepareTick=server.getTicks(); space.prepareNanos=System.nanoTime();
        for (var view : space.pending.target().instances()) if (!space.current.instances().contains(view)) {
            space.builds.put(view.ref().slotId(),new Build(view,space.frontOpen,space.rearOpen,space.semantics));
        }
        space.queued=true;
    }
    private void requirePageCapacity(Space replacement, Set<Long> pages) {
        int occupied=pages.size();
        for (var space : spaces.values()) if (space!=replacement) occupied+=Math.max(space.currentPages.size(),space.pendingPages.size());
        if (occupied>128) throw new IllegalStateException("全域 occupied page 容量不足");
    }
    private void checkInitialCohort(Space space, Set<UUID> cohort) {
        var box=ChamberGeometry.interiorBox(entrance(space.id));
        for (UUID id : cohort) {
            var player=server.getPlayerManager().getPlayer(id);
            if (player==null || player.getServerWorld()!=world || world.getEntity(id)!=player || !player.isAlive() || player.isRemoved()
                    || player.isSpectator() || !contains(box,player.getBoundingBox())
                    || player.hasStatusEffect(ModEffects.QUANTUM_STATE)!=(space.semantics==SessionSemantics.LATERAL_BUFF_MAINTAINED))
                throw new IllegalStateException("全cohort尚未完成真實入場與對應模式效果核對");
        }
        var present=world.getPlayers(player -> player.getBoundingBox().intersects(box));
        if (!present.stream().map(Entity::getUuid).collect(java.util.stream.Collectors.toSet()).equals(cohort)) {
            throw new IllegalStateException("replica 內出現未凍結玩家");
        }
    }
    /** 同一份 Space／current／checked journal 與真玩家共同驗證；只供新模式維持資格。 */
    public boolean activeCohortHasBuff(UUID sessionUuid) {
        var space=space(sessionUuid);
        var record=durable(space); var current=journal.records().get(sessionUuid);
        if(space.semantics!=SessionSemantics.LATERAL_BUFF_MAINTAINED || space.failed || space.releasing
                || record.state()!=SessionState.SUPERPOSITION || current==null || !current.equals(record)) return false;
        for(var person : record.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || player.getServer()!=server || player.getServerWorld()!=world || world.getEntity(person.playerUuid())!=player
                    || !player.isAlive() || player.isRemoved() || player.isSpectator() || !player.hasStatusEffect(ModEffects.QUANTUM_STATE)) return false;
        }
        return true;
    }
    private void requireActiveBuff(Space space) {
        if(space.semantics==SessionSemantics.LATERAL_BUFF_MAINTAINED && !activeCohortHasBuff(space.id))
            throw new IllegalStateException("新模式完整cohort的目前Buff失效，禁止繼續頁面移動或發布");
    }
    private void resetBudget() {
        if (budgetTick!=server.getTicks()) { budgetTick=server.getTicks(); writes=0; }
    }
    boolean write(BlockPos pos, BlockState state) {
        resetBudget();
        if (world.getBlockState(pos).equals(state)) return true;
        if (writes>=4096) throw new IllegalStateException("本 tick 方塊寫入預算已耗盡");
        writes++;
        boolean accepted=ChamberProtectionService.get().authorizedMutation(world,pos,() -> world.setBlockState(pos,state,3));
        if (!accepted || !world.getBlockState(pos).equals(state)) throw new IllegalStateException("授權幾何寫入失敗");
        return true;
    }
    private void tickRelease(Space space) {
        // 即使前次 checked remove 失敗，重試也先重驗完整凍結來源與所有返還進度。
        var record=durable(space);
        if (record.state()!=SessionState.RETURNING || record.participants().stream().anyMatch(person -> !person.returned())) return;
        if (space.finalizing==null && !journal.records().containsKey(space.id)) return;
        if (space.operations>0 || space.leases.values().stream().anyMatch(lease -> !leaseReady(lease.bounds()) || !pins(lease.bounds()).isEmpty())) return;
        for (var lease : space.leases.values()) {
            if (!clear(space,lease.slotId(),lease.bounds())) return;
        }
        space.finalizing=record;
        // 失敗後保留自己的前一份 durable 證據；不能從 in-memory 缺失推測成功。
        journal.remove(space.id); journal.flush(server);
        if (journal.flushedRecords().containsKey(space.id)) throw new IllegalStateException("最後 journal 移除未確認");
        for (int slot : space.leases.keySet()) { releaseTickets(space,slot); allocator.release(slot); protectedLeases.remove(slot); }
        spaces.remove(space.id); completedReleases.add(space.id);
    }
    private boolean clear(Space space, int slot, BlockBox bounds) {
        if (space.cleared.contains(slot)) return true;
        var iterator=space.clearing.computeIfAbsent(slot,ignored -> CorridorGeometry.clearing(bounds));
        while (writes<4096) {
            var next=space.clearPending.get(slot);
            if (next==null) {
                if (!iterator.hasNext()) break;
                next=iterator.next().toImmutable(); space.clearPending.put(slot,next);
            }
            write(next,Blocks.AIR.getDefaultState());
            space.clearPending.remove(slot);
        }
        if (iterator.hasNext() || space.clearPending.containsKey(slot)) return false;
        space.cleared.add(slot); space.clearing.remove(slot); return true;
    }
    private void tickRetirement(Space space,int slot) {
        var lease=space.leases.get(slot);
        if (lease==null || space.operations>0 || !leaseReady(lease.bounds()) || !pins(lease.bounds()).isEmpty()) return;
        var record=durable(space);
        if (!clear(space,slot,lease.bounds())) return;
        var remaining=space.leases.values().stream().filter(other -> other.slotId()!=slot).toList();
        if (remaining.isEmpty()) throw new IllegalStateException("最後租約必須經 release 收尾");
        journal.put(new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),record.participants(),remaining,
                record.state(),record.restoreEntryEffectOnReturn(),record.semantics()));
        journal.flush(server);
        if (journal.flushedRecords().get(space.id).spaceLeases().stream().anyMatch(other -> other.slotId()==slot)) {
            throw new IllegalStateException("舊租約移除尚未 durable");
        }
        releaseTickets(space,slot); space.leases.remove(slot); allocator.release(slot); protectedLeases.remove(slot);
        space.retiring.remove(slot); space.cleared.remove(slot);
        space.retiredViews.entrySet().removeIf(entry -> entry.getKey().slotId()==slot);
        space.retireTokens.values().removeIf(token -> token.instances().stream().noneMatch(space.retiredViews::containsKey));
    }
    private void requirePinCapacity() {
        var ids=new HashSet<UUID>();
        for (var bounds : protectedLeases.values()) for (var entity : pins(bounds)) ids.add(entity.getUuid());
        if (ids.size()>256) throw new IllegalStateException("全域 managed entity pin 已超過 256");
    }
    private static void validateFacingBounds(BlockBox bounds,Direction facing) {
        SlotAllocator.validateShape(bounds);
        if (facing.getAxis().isVertical() || (facing.getAxis()==Direction.Axis.Z
                ? (long)bounds.getMaxX()-bounds.getMinX()!=6 : (long)bounds.getMaxZ()-bounds.getMinZ()!=6)) {
            throw new IllegalArgumentException("[LEASE_FACING_INVALID] lease 截面與來源 facing 不符");
        }
    }
    private void validateWorldBounds(BlockBox bounds) {
        var border=world.getWorldBorder();
        double west=border.getBoundWest(),east=border.getBoundEast(),north=border.getBoundNorth(),south=border.getBoundSouth();
        // contains(Box) 不作完整包含假設；直接核對整個方塊體積的外緣。
        if (!Double.isFinite(west) || !Double.isFinite(east) || !Double.isFinite(north) || !Double.isFinite(south)
                || bounds.getMinX()<west || (long)bounds.getMaxX()+1>east || bounds.getMinZ()<north || (long)bounds.getMaxZ()+1>south
                || bounds.getMinY()<world.getBottomY() || bounds.getMaxY()>=(long)world.getBottomY()+world.getHeight()) {
            throw new IllegalArgumentException("[LEASE_WORLD_BORDER] 完整 lease 超出真實世界高度或 world border");
        }
    }
    private void acquireTickets(Space space) {
        for (var lease : space.leases.values()) {
            validateFacingBounds(lease.bounds(),space.semantics.corridorFacing(space.origin.facing()));
            if (space.ticketed.containsKey(lease.slotId())) continue;
            var chunks=footprint(lease.bounds()); var acquired=new HashSet<Long>();
            try {
                for (long packed : chunks) {
                    int count=space.ticketRefs.getOrDefault(packed,0);
                    if (count==0) world.getChunkManager().addTicket(TICKET,new ChunkPos(packed),2,space.id);
                    space.ticketRefs.put(packed,count+1); acquired.add(packed);
                }
                space.ticketed.put(lease.slotId(),Set.copyOf(chunks));
            } catch (RuntimeException failure) {
                for (long packed : acquired) decrementTicket(space,packed);
                throw failure;
            }
        }
        peakCenterTickets=Math.max(peakCenterTickets,spaces.values().stream().mapToInt(value -> value.ticketRefs.size()).sum());
    }
    private static Set<Long> footprint(BlockBox bounds) {
        var result=new HashSet<Long>();
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++) for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) {
            result.add(ChunkPos.toLong(x,z));
        }
        return Set.copyOf(result);
    }
    private boolean leaseReady(BlockBox bounds) {
        for(long packed : footprint(bounds)) {
            var chunk=new ChunkPos(packed);
            if (world.getChunkManager().getWorldChunk(chunk.x,chunk.z)==null || !world.isChunkLoaded(packed) || !world.shouldTick(chunk)) return false;
        }
        return true;
    }
    private void decrementTicket(Space space,long packed) {
        int count=space.ticketRefs.getOrDefault(packed,0);
        if (count<=0) throw new IllegalStateException("ticket refcount 不一致");
        if (count==1) { world.getChunkManager().removeTicket(TICKET,new ChunkPos(packed),2,space.id); space.ticketRefs.remove(packed); }
        else space.ticketRefs.put(packed,count-1);
    }
    private void releaseTickets(Space space,int slot) {
        var chunks=space.ticketed.remove(slot);
        if (chunks!=null) for(long packed : chunks) decrementTicket(space,packed);
    }
    private void removeAllTickets(Space space) {
        for(long packed : List.copyOf(space.ticketRefs.keySet())) world.getChunkManager().removeTicket(TICKET,new ChunkPos(packed),2,space.id);
        space.ticketRefs.clear(); space.ticketed.clear();
    }
    private List<MappingView> changedSources(Space space) {
        return space.current.instances().stream().filter(view -> !space.pending.target().instances().contains(view)).toList();
    }
    private List<MappingView> changedTargets(Space space) {
        return space.pending.target().instances().stream().filter(view -> !space.current.instances().contains(view)).toList();
    }
    private Map<UUID,MappingRef> affectedOwners(Space space) {
        var owners=new LinkedHashMap<UUID,MappingRef>();
        for (var source : changedSources(space)) for (var entity : pins(source.bounds())) {
            if (!contains(box(source.bounds()),entity.getBoundingBox()) || owners.put(entity.getUuid(),source.ref())!=null) {
                throw new IllegalStateException("entity bbox 跨出唯一 source owner");
            }
        }
        return Map.copyOf(owners);
    }
    private Set<UUID> movingPins(Space space) {
        var ids=new HashSet<UUID>();
        for (var view : java.util.stream.Stream.concat(changedSources(space).stream(),changedTargets(space).stream()).toList()) {
            for (var entity : pins(view.bounds())) ids.add(entity.getUuid());
        }
        return Set.copyOf(ids);
    }
    private RetiredMappings retiredToken(Space space,List<MappingRef> refs) {
        var retired=new RetiredMappings(UUID.randomUUID(),space.id,refs); space.retireTokens.put(retired.token(),retired); return retired;
    }
    private static PhysicalPose pose(Entity entity) { return new PhysicalPose(entity.getPos(),entity.getVelocity(),entity.getYaw(),entity.getPitch()); }
    private static boolean samePose(PhysicalPose a,PhysicalPose b) {
        return a.position().squaredDistanceTo(b.position())<1e-12 && a.velocity().squaredDistanceTo(b.velocity())<1e-12
                && Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(a.yaw()-b.yaw()))<1e-4 && Math.abs(a.pitch()-b.pitch())<1e-4;
    }
    private List<Entity> pins(BlockBox bounds) {
        var result=new ArrayList<Entity>(); var volume=box(bounds);
        // native teleport 同 tick 已改 pose，但區段索引可能尚未更新；直接比對 live entity 真 bbox。
        for (var entity : world.iterateEntities()) if (!entity.isRemoved() && entity.getWorld()==world
                && (entity instanceof ServerPlayerEntity || entity instanceof ItemEntity || entity instanceof ProjectileEntity)
                && entity.getBoundingBox().intersects(volume)) {
            result.add(entity);
            // 257 已足以證明超量；退休只需知道非空，不建立無界的 pin 清單。
            if (result.size()>256) break;
        }
        return List.copyOf(result);
    }
    private void fail(Space space,Exception failure) {
        space.failed=true;
        var record=journal.flushedRecords().get(space.id);
        if (record!=null) {
            try {
                journal.put(new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),record.participants(),
                        List.copyOf(space.leases.values()),SessionState.RETURNING,record.restoreEntryEffectOnReturn(),record.semantics()));
                journal.flush(server);
            } catch (RuntimeException persistenceFailure) { failure.addSuppressed(persistenceFailure); }
        }
        org.slf4j.LoggerFactory.getLogger("quantumchamber").error("走廊保留租約等待安全返還：{}",space.id,failure);
    }
    private static Set<UUID> ids(List<SessionRecoveryRecord.Participant> people) {
        return people.stream().map(SessionRecoveryRecord.Participant::playerUuid).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
    private static boolean participantsEqual(List<SessionRecoveryRecord.Participant> a,List<SessionRecoveryRecord.Participant> b) {
        return new HashSet<>(a).equals(new HashSet<>(b));
    }
    private static boolean leasesEqual(Collection<SessionRecoveryRecord.SpaceLease> a,Collection<SessionRecoveryRecord.SpaceLease> b) {
        if (a.size()!=b.size()) return false;
        for (var lease : a) if (b.stream().noneMatch(other -> other.slotId()==lease.slotId() && sameBox(other.bounds(),lease.bounds()))) return false;
        return true;
    }
    private static boolean sameBox(BlockBox a,BlockBox b) {
        return a.getMinX()==b.getMinX() && a.getMinY()==b.getMinY() && a.getMinZ()==b.getMinZ()
                && a.getMaxX()==b.getMaxX() && a.getMaxY()==b.getMaxY() && a.getMaxZ()==b.getMaxZ();
    }
    private static Box box(BlockBox b) { return new Box(b.getMinX(),b.getMinY(),b.getMinZ(),b.getMaxX()+1,b.getMaxY()+1,b.getMaxZ()+1); }
    private static boolean contains(Box a,Box b) {
        return b.minX>=a.minX && b.minY>=a.minY && b.minZ>=a.minZ && b.maxX<=a.maxX && b.maxY<=a.maxY && b.maxZ<=a.maxZ;
    }
    private static MappingView select(MappingSet set,double logicalZ) {
        LogicalAddress.from(logicalZ);
        return set.instances().stream().filter(view -> logicalZ>=view.aliasStartBlock() && logicalZ<view.aliasEndBlock()).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("邏輯座標不在已準備的局部分量"));
    }
    private MappingView view(MappingRef ref) {
        var space=space(ref.sessionUuid());
        return java.util.stream.Stream.concat(space.current.instances().stream(),space.pending==null ? java.util.stream.Stream.empty()
                : space.pending.target().instances().stream()).filter(view -> view.ref().equals(ref)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未知／偽造／過期 mapping ref"));
    }
    private static Vec3d yawVector(float yaw) {
        double radians=Math.toRadians(yaw); return new Vec3d(-Math.sin(radians),0,Math.cos(radians));
    }
    private static float yaw(Vec3d vector) { return (float)Math.toDegrees(Math.atan2(-vector.x,vector.z)); }
    private static void finite(Vec3d vector) {
        if (vector==null || !Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException("pose 向量必須有限");
        }
    }
    private static void validate(PhysicalPose pose) {
        finite(pose.position()); finite(pose.velocity());
        if (!Float.isFinite(pose.yaw()) || !Float.isFinite(pose.pitch())) throw new IllegalArgumentException("pose 視角必須有限");
    }
    private static void validate(LogicalPose pose) {
        finite(new Vec3d(pose.lateral(),pose.height(),pose.logicalZ())); finite(pose.localVelocity()); LogicalAddress.from(pose.logicalZ());
        if (!Float.isFinite(pose.localYaw()) || !Float.isFinite(pose.pitch())) throw new IllegalArgumentException("pose 視角必須有限");
    }
    private static final class Build {
        final MappingView view; final CorridorGeometry.Builder builder; CorridorGeometry.Cell next;
        Build(MappingView view,boolean front,boolean rear,SessionSemantics semantics) {
            this.view=view; builder=new CorridorGeometry.Builder(view,front,rear,semantics);
        }
    }
    private static final class Space {
        final UUID id; final ChamberOriginAuthority origin; final List<SessionRecoveryRecord.Participant> source;
        final SessionSemantics semantics;
        final Map<Integer,SessionRecoveryRecord.SpaceLease> leases=new LinkedHashMap<>();
        final Map<Integer,Build> builds=new LinkedHashMap<>();
        final ArrayDeque<CorridorGeometry.Cell> overlay=new ArrayDeque<>();
        final Set<Integer> retiring=new LinkedHashSet<>(), cleared=new HashSet<>();
        final Map<Integer,Iterator<BlockPos>> clearing=new HashMap<>();
        final Map<Integer,BlockPos> clearPending=new HashMap<>();
        final Map<UUID,RetiredMappings> retireTokens=new HashMap<>();
        final Map<MappingRef,MappingView> retiredViews=new HashMap<>();
        final Map<Integer,Set<Long>> ticketed=new HashMap<>();
        final Map<Long,Integer> ticketRefs=new HashMap<>();
        MappingSet current=new MappingSet(0,List.of()); PreparedMappings pending; RemapBatch batch;
        Set<Long> currentPages=Set.of(),pendingPages=Set.of();
        boolean queued,frontOpen,rearOpen,failed,releasing,provisionalCancelled; int operations,batchTick,prepareTick; long instanceEpoch,prepareNanos;
        SessionRecoveryRecord finalizing;
        Space(UUID id,ChamberOriginAuthority origin,List<SessionRecoveryRecord.Participant> source,SessionSemantics semantics) {
            this.id=id; this.origin=origin; this.source=List.copyOf(source); this.semantics=Objects.requireNonNull(semantics);
        }
    }
}
