package dev.quantumchamber.superposition;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.transfer.*;
import dev.quantumchamber.registry.ModEffects;
import java.util.*;
import java.io.IOException;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.*;

/** 真共享入場 gateway；來源 tickets 與交易協調，不擁有走廊 tick／allocator。 */
public final class SuperpositionSessionManager implements ChamberSessionGateway {
    private static boolean initialized;
    private static final ChunkTicketType<UUID> SOURCE_TICKET=ChunkTicketType.create("quantumchamber_source",Comparator.comparing(UUID::toString));
    private final Map<UUID,SuperpositionSession> sessions=new LinkedHashMap<>();
    private final Map<UUID,SourceTicket> tickets=new LinkedHashMap<>();
    private final SessionTransferService transfers=new SessionTransferService();
    private MinecraftServer server;
    private SessionRecoveryState journal;
    private int lastTick=Integer.MIN_VALUE;

    public static void initialize() {
        if(initialized) return;
        initialized=true;
        var manager=new SuperpositionSessionManager();
        ChamberSessions.install(manager);
        ServerLifecycleEvents.SERVER_STARTED.register(manager::attach);
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(manager::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(manager::detach);
    }
    private void attach(MinecraftServer owner) {
        if(server!=null || !owner.isOnThread()) throw new IllegalStateException("session authority 不可重複 attach");
        server=owner; journal=SessionRecoveryState.get(owner); journal.requireHealthy();
        CorridorPageManager.forServer(owner);
        for(var record : journal.flushedRecords().values()) sourceTicket(record);
    }
    @Override public Presence presence(MinecraftServer owner,UUID chamber) {
        requireServer(owner);
        var durable=record(chamber);
        var runtime=sessions.get(chamber);
        if(runtime!=null && runtime.state==SessionState.RETURNING) return Presence.RETURNING;
        if(durable==null) return runtime==null ? Presence.NONE : Presence.UNKNOWN;
        if(durable.state()==SessionState.RETURNING) return Presence.RETURNING;
        if(runtime==null) return Presence.UNKNOWN;
        return runtime.state==SessionState.SUPERPOSITION ? Presence.ACTIVE : Presence.ARMING;
    }
    @Override public StartResult start(ServerWorld source,ChamberControllerBlockEntity controller,List<UUID> requested) {
        requireServer(source.getServer());
        var chamber=controller.chamberUuid();
        if(chamber==null || presence(server,chamber)!=Presence.NONE || requested.isEmpty() || requested.size()>25
                || new HashSet<>(requested).size()!=requested.size() || controller.activationBlocked()) return StartResult.REJECTED;
        var preview=new ChamberActivationService().evaluateReadiness(source,controller);
        if(!preview.accepted() || !new HashSet<>(preview.participantUuids()).equals(new HashSet<>(requested))
                || !source.isReceivingRedstonePower(controller.getPos())) return StartResult.REJECTED;
        var frame=new ChamberFrame(controller.getPos(),controller.getCachedState().get(ChamberControllerBlock.FACING));
        var effects=new QuantumEffectTransaction(requested.stream().map(id -> server.getPlayerManager().getPlayer(id)).toList());
        var id=UUID.randomUUID(); var pages=CorridorPageManager.forServer(server);
        CorridorPageManager.PreparedMappings prepared;
        try { prepared=pages.reserveInitial(id,chamber,frame.outwardFacing(),Set.of(0L)); }
        catch(IllegalArgumentException | IllegalStateException capacity) { return StartResult.REJECTED; }
        var origin=new ChamberOriginAuthority(chamber,source.getRegistryKey().getValue(),
                dev.quantumchamber.universe.DimensionRole.fromVanillaKey(source.getRegistryKey()).orElseThrow(),
                frame.controllerPos(),frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
        var initial=new SessionRecoveryRecord(id,chamber,origin,effects.snapshots(),prepared.target().instances().stream()
                .map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(),view.bounds())).toList(),SessionState.ARMING,true);
        var runtime=new SuperpositionSession(source,controller,frame,effects,prepared,initial);
        sessions.put(chamber,runtime);
        try {
            journal.put(initial); journal.flush(server);
            sourceTicket(initial); pages.prepare(prepared);
            return StartResult.STAGING;
        } catch(RuntimeException failure) {
            fail(runtime,failure); return StartResult.STAGING;
        }
    }
    public void tick(MinecraftServer owner) {
        requireServer(owner);
        if(lastTick==owner.getTicks()) return;
        lastTick=owner.getTicks();
        for(var runtime : List.copyOf(sessions.values())) {
            var durable=journal.flushedRecords().get(runtime.initial.sessionUuid());
            if(durable!=null && durable.state()==SessionState.RETURNING) runtime.state=SessionState.RETURNING;
            if(runtime.state!=SessionState.ARMING) continue;
            try {
                if(!eligible(runtime)) throw new IllegalStateException("準備期間來源身分／cohort／全員資格改變");
                var pages=CorridorPageManager.forServer(owner);
                if(!pages.ready(runtime.prepared)) continue;
                enter(runtime,pages);
            } catch(IOException | RuntimeException failure) { fail(runtime,failure); }
        }
    }
    private boolean eligible(SuperpositionSession runtime) {
        if(server.getWorld(runtime.source.getRegistryKey())!=runtime.source || runtime.controller.isRemoved()
                || runtime.controller.getWorld()!=runtime.source || runtime.source.getBlockEntity(runtime.sourceFrame.controllerPos())!=runtime.controller
                || !runtime.initial.chamberUuid().equals(runtime.controller.chamberUuid())
                || runtime.controller.instanceKind()!=ChamberInstanceKind.ORIGIN || runtime.controller.activationBlocked()
                || !runtime.source.isReceivingRedstonePower(runtime.sourceFrame.controllerPos())) return false;
        var preview=new ChamberActivationService().evaluateReadiness(runtime.source,runtime.controller);
        return preview.accepted() && new HashSet<>(preview.participantUuids()).equals(ids(runtime.initial))
                && runtime.initial.participants().stream().allMatch(person -> {
                    var player=server.getPlayerManager().getPlayer(person.playerUuid());
                    return player!=null && player.getServerWorld()==runtime.source && !player.isSpectator()
                            && player.hasStatusEffect(ModEffects.QUANTUM_STATE);
                });
    }
    private void enter(SuperpositionSession runtime,CorridorPageManager pages) throws IOException {
        var target=server.getWorld(SuperpositionWorld.KEY);
        var entrance=pages.entrance(runtime.initial.sessionUuid());
        var view=runtime.prepared.target().instances().getFirst();
        var poses=new LinkedHashMap<UUID,CorridorPageManager.PhysicalPose>();
        for(var person : runtime.initial.participants()) {
            var local=CorridorGeometry.localPosition(runtime.sourceFrame,person.sourcePosition());
            var logical=new CorridorPageManager.LogicalPose(local.x,local.y,local.z,
                    CorridorGeometry.localVector(runtime.sourceFrame,person.sourceVelocity()),localYaw(runtime.sourceFrame,person.yaw()),person.pitch());
            var pose=pages.toPhysical(view.ref(),logical);
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(entrance),
                    player.getBoundingBox().offset(pose.position().subtract(player.getPos())))) throw new IllegalStateException("入場完整 bbox 超出入口");
            poses.put(person.playerUuid(),pose);
        }
        for(var move : poses.entrySet()) {
            var pose=move.getValue();
            if(!transfers.move(server.getPlayerManager().getPlayer(move.getKey()),target,pose.position(),pose.velocity(),pose.yaw(),pose.pitch()))
                throw new IllegalStateException("部分移動失敗，尚未提交 cohort");
        }
        checkTargets(runtime,target,entrance,poses);
        runtime.effects.commit(server);
        for(var person : runtime.initial.participants()) PlayerCheckpointStore.saveAndVerify(server,
                server.getPlayerManager().getPlayer(person.playerUuid()),Optional.empty());
        checkTargets(runtime,target,entrance,poses);
        var initial=runtime.initial;
        journal.put(new SessionRecoveryRecord(initial.sessionUuid(),initial.chamberUuid(),initial.origin(),initial.participants(),
                initial.spaceLeases(),SessionState.SUPERPOSITION,false));
        journal.flush(server);
        pages.commitInitial(initial.sessionUuid(),ids(initial));
        runtime.state=SessionState.SUPERPOSITION;
        new ChamberPowerCoordinator().refresh(runtime.source,runtime.sourceFrame.controllerPos());
    }
    private void checkTargets(SuperpositionSession runtime,ServerWorld target,ChamberFrame entrance,
            Map<UUID,CorridorPageManager.PhysicalPose> poses) {
        for(var person : runtime.initial.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid()); var pose=poses.get(person.playerUuid());
            if(player==null || player.getServerWorld()!=target || target.getEntity(person.playerUuid())!=player || player.isSpectator()
                    || player.getPos().squaredDistanceTo(pose.position())>=1e-12 || player.getVelocity().squaredDistanceTo(pose.velocity())>=1e-12
                    || Math.abs(MathHelper.wrapDegrees(player.getYaw()-pose.yaw()))>=1e-4 || Math.abs(player.getPitch()-pose.pitch())>=1e-4
                    || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(entrance),player.getBoundingBox()))
                throw new IllegalStateException("全群 actual world／pose／bbox 尚未確認");
        }
    }
    @Override public boolean returnToOrigin(ServerWorld source,ChamberControllerBlockEntity controller) {
        requireServer(source.getServer());
        var runtime=sessions.get(controller.chamberUuid()); var durable=record(controller.chamberUuid());
        if(runtime==null && durable==null) return true;
        if(durable!=null) {
            if(!durable.origin().worldKey().equals(source.getRegistryKey().getValue())
                    || !durable.origin().controllerPos().equals(controller.getPos()) || controller.instanceKind()!=ChamberInstanceKind.ORIGIN)
                return false;
            returning(durable);
        }
        if(runtime!=null) runtime.state=SessionState.RETURNING;
        // Task4 尚未提供玩家返還與已知收尾收據；保留 durable pending 與來源保護。
        return false;
    }
    private void fail(SuperpositionSession runtime,Exception failure) {
        var durable=journal.flushedRecords().get(runtime.initial.sessionUuid());
        boolean restore=durable==null || durable.restoreEntryEffectOnReturn();
        runtime.state=SessionState.RETURNING;
        if(restore) {
            for(var person : runtime.initial.participants()) {
                var player=server.getPlayerManager().getPlayer(person.playerUuid());
                if(player!=null && (!transfers.move(player,runtime.source,person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch())
                        || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(runtime.sourceFrame),player.getBoundingBox())))
                    failure.addSuppressed(new IllegalStateException("rollback pose 尚未確認："+person.playerUuid()));
            }
            if(!runtime.effects.restore(server)) failure.addSuppressed(new IllegalStateException("rollback effects 尚未確認"));
        }
        try { returning(durable==null ? runtime.initial : durable); sourceTicket(runtime.initial); }
        catch(RuntimeException persistence) { failure.addSuppressed(persistence); }
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("入場交易保留 RETURNING，效果恢復決策={}，session={}",restore,
                runtime.initial.sessionUuid(),failure);
    }
    private void returning(SessionRecoveryRecord record) {
        if(record.state()==SessionState.RETURNING && record.equals(journal.records().get(record.sessionUuid()))) return;
        journal.put(new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),record.participants(),
                record.spaceLeases(),SessionState.RETURNING,record.restoreEntryEffectOnReturn())); journal.flush(server);
    }
    private SessionRecoveryRecord record(UUID chamber) {
        return journal.flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(chamber)).findFirst().orElse(null);
    }
    private void sourceTicket(SessionRecoveryRecord record) {
        if(tickets.containsKey(record.sessionUuid())) return;
        var source=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,record.origin().worldKey()));
        if(source==null) throw new IllegalStateException("來源 world 不存在，不猜 spawn");
        var chunk=new ChunkPos(record.origin().controllerPos());
        source.getChunkManager().addTicket(SOURCE_TICKET,chunk,2,record.sessionUuid());
        tickets.put(record.sessionUuid(),new SourceTicket(source,chunk));
    }
    private void stopping(MinecraftServer owner) {
        requireServer(owner);
        for(var entry : tickets.entrySet()) entry.getValue().world().getChunkManager()
                .removeTicket(SOURCE_TICKET,entry.getValue().chunk(),2,entry.getKey());
        tickets.clear();
    }
    private void detach(MinecraftServer owner) {
        if(server==owner) { sessions.clear(); tickets.clear(); journal=null; server=null; lastTick=Integer.MIN_VALUE; }
    }
    private void requireServer(MinecraftServer owner) {
        if(owner!=server || server==null || !owner.isOnThread()) throw new IllegalStateException("session 必須在 attached server thread");
        journal.requireHealthy();
    }
    private static Set<UUID> ids(SessionRecoveryRecord record) {
        return record.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).collect(java.util.stream.Collectors.toSet());
    }
    private static float localYaw(ChamberFrame frame,float yaw) {
        double angle=Math.toRadians(yaw);
        var direction=CorridorGeometry.localVector(frame,new Vec3d(-Math.sin(angle),0,Math.cos(angle)));
        return (float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
    }
    private record SourceTicket(ServerWorld world,ChunkPos chunk) {}
}
