package dev.quantumchamber.persistence;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.transfer.ChamberReturnPlacement;
import dev.quantumchamber.transfer.SessionTransferService;
import java.io.IOException;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;

/** 以 durable cohort 驅動返還；玩家 checkpoint 與最後 lease 收據皆不可省略。 */
public final class SessionRecoveryManager {
    private static SessionRecoveryManager active;
    private final MinecraftServer server;
    private final SessionRecoveryState journal;
    private final Predicate<SessionRecoveryRecord> sourceAuthority;
    private final Map<UUID,Integer> joinTicks=new HashMap<>();
    private final Set<UUID> disconnected=new HashSet<>(),disconnectWarnings=new HashSet<>();
    private final Map<UUID,String> failures=new HashMap<>();
    private final Map<UUID,Integer> returnTicks=new HashMap<>();
    private final SessionTransferService transfers=new SessionTransferService();

    public SessionRecoveryManager(MinecraftServer server,Predicate<SessionRecoveryRecord> sourceAuthority) {
        if(!server.isOnThread() || active!=null) throw new IllegalStateException("recovery authority 不可重複 attach");
        this.server=server; this.journal=SessionRecoveryState.get(server);
        this.sourceAuthority=Objects.requireNonNull(sourceAuthority); active=this;
    }

    /** 只能在原生網路 handler 已序列化的執行緒查詢，不讀取 Netty 執行緒上的 journal。 */
    public static boolean blocks(UUID player,MinecraftServer owner) {
        if(active==null || active.server!=owner) return false;
        active.requireServer(owner);
        try {
            return active.joinTicks.containsKey(player) || active.disconnected.contains(player)
                    || java.util.stream.Stream.concat(active.journal.records().values().stream(),active.journal.flushedRecords().values().stream())
                    .anyMatch(record -> record.state()==SessionState.RETURNING && record.participants().stream()
                            .anyMatch(person -> person.playerUuid().equals(player)));
        } catch(RuntimeException unreadable) { return true; }
    }

    /** JOIN 只排下一 tick，不把 server.execute 視為登入完成。 */
    public void onJoin(UUID player,MinecraftServer owner) {
        requireServer(owner);
        if(recordFor(player)!=null) joinTicks.put(player,owner.getTicks()+1);
    }

    public void onDisconnect(UUID player,MinecraftServer owner) {
        if(owner!=server) return;
        owner.execute(() -> {
            if(active!=this) return;
            joinTicks.remove(player);
            disconnected.add(player);
        });
    }

    public void tick(MinecraftServer owner) {
        requireServer(owner); journal.requireHealthy();
        // 落盤失敗不能中斷原生 disconnect 清理；識別資訊留在本服務，下一 tick 重試。
        for(var id : Set.copyOf(disconnected)) {
            try {
                var record=recordFor(id); if(record!=null) markReturning(record);
                disconnected.remove(id); disconnectWarnings.remove(id);
            } catch(RuntimeException failure) {
                if(disconnectWarnings.add(id)) org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("斷線返還尚未落盤，保留重試：{}",id,failure);
            }
        }
        joinTicks.entrySet().removeIf(entry -> owner.getTicks()>=entry.getValue());
        returnTicks.keySet().retainAll(journal.flushedRecords().keySet());
        for(var record : journal.flushedRecords().values()) if(record.state()==SessionState.RETURNING) {
            // 先完成本 tick 的入場／rollback 交易，下一 tick 才進行獨立返還 checkpoint。
            if(owner.getTicks()<=returnTicks.computeIfAbsent(record.sessionUuid(),ignored -> owner.getTicks())) continue;
            try { recover(record); failures.remove(record.sessionUuid()); }
            catch(IOException | RuntimeException failure) {
                var message=failure.getClass().getName()+": "+failure.getMessage();
                if(!message.equals(failures.put(record.sessionUuid(),message)))
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("返還保留 pending，session={}: {}",record.sessionUuid(),message);
            }
        }
    }

    public boolean returnComplete(UUID session) {
        requireServer(server);
        return CorridorPageManager.forServer(server).releaseComplete(session);
    }

    public void detach(MinecraftServer owner) {
        requireServer(owner); joinTicks.clear(); disconnected.clear(); disconnectWarnings.clear(); failures.clear(); returnTicks.clear(); if(active==this) active=null;
    }

    private void recover(SessionRecoveryRecord initial) throws IOException {
        if(!sourceAuthority.test(initial)) return;
        var source=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,initial.origin().worldKey()));
        if(source==null) return;
        var frame=new ChamberFrame(initial.origin().controllerPos(),initial.origin().facing());
        var positions=new LinkedHashMap<UUID,Vec3d>();
        initial.participants().forEach(person -> positions.put(person.playerUuid(),person.sourcePosition()));
        var slots=ChamberReturnPlacement.plan(frame,List.copyOf(positions.keySet()),positions);
        var record=initial;
        for(var person : initial.participants()) {
            if(person.returned() || joinTicks.containsKey(person.playerUuid())) continue;
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || player.isRemoved()) continue;
            var marker=new PlayerRecoveryCheckpoint(record.sessionUuid(),record.restoreEntryEffectOnReturn()
                    ? PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY : PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
            var access=(PlayerRecoveryCheckpointAccess)player;
            var previous=access.quantumchamber$getRecoveryCheckpoint();
            boolean applyMarker=shouldApplyMarker(marker,previous);
            if(!inside(player,source,frame) && !transfers.move(player,source,slots.get(person.playerUuid()),Vec3d.ZERO,person.yaw(),person.pitch())) continue;
            if(!inside(player,source,frame) || !sourceAuthority.test(record)) continue;
            if(applyMarker) {
                if(record.restoreEntryEffectOnReturn()) {
                    var effect=StatusEffectInstance.fromNbt(person.quantumStateSnapshot());
                    if(effect==null || !effect.getEffectType().equals(ModEffects.QUANTUM_STATE)) throw new IOException("來源 QuantumState snapshot 不合法");
                    player.removeStatusEffect(ModEffects.QUANTUM_STATE); player.addStatusEffect(effect);
                    if(!person.quantumStateSnapshot().equals(player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt()))
                        throw new IOException("完整來源效果尚未還原");
                }
                access.quantumchamber$setRecoveryCheckpoint(marker);
            }
            PlayerCheckpointStore.saveAndVerify(server,player,Optional.of(marker));
            if(!inside(player,source,frame) || !sourceAuthority.test(record)) continue;
            var completed=person.playerUuid();
            var participants=record.participants().stream().map(entry -> entry.playerUuid().equals(completed)
                    ? new SessionRecoveryRecord.Participant(entry.playerUuid(),entry.sourcePosition(),entry.sourceVelocity(),entry.yaw(),entry.pitch(),
                            entry.quantumStateSnapshot(),true) : entry).toList();
            record=new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),participants,record.spaceLeases(),
                    SessionState.RETURNING,record.restoreEntryEffectOnReturn(),record.semantics());
            journal.put(record); journal.flush(server);
        }
        var pages=CorridorPageManager.forServer(server);
        var pins=pages.returnEntityPins(record.sessionUuid());
        if(pins.isEmpty()) return;
        var corridor=server.getWorld(SuperpositionWorld.KEY);
        for(var id : pins.get()) {
            var entity=corridor.getEntity(id);
            if(entity==null || entity instanceof ServerPlayerEntity) return;
            var position=CorridorGeometry.position(frame,3.5,1,3.5);
            if(!ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),entity.getBoundingBox().offset(position.subtract(entity.getPos())))
                    || !sourceAuthority.test(record) || !transfers.move(entity,source,position,Vec3d.ZERO,entity.getYaw(),entity.getPitch())) return;
            source.getEntity(id).removeCommandTag("quantumchamber_session:"+record.sessionUuid());
        }
        if(record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)) pages.release(record.sessionUuid());
    }

    private boolean inside(ServerPlayerEntity player,ServerWorld source,ChamberFrame frame) {
        return player.getServerWorld()==source && source.getEntity(player.getUuid())==player
                && server.getPlayerManager().getPlayer(player.getUuid())==player
                && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),player.getBoundingBox());
    }

    /** 同一 session 的 marker 是已套用政策的收據；不同 policy 不可重解釋。 */
    static boolean shouldApplyMarker(PlayerRecoveryCheckpoint expected,Optional<PlayerRecoveryCheckpoint> previous) throws IOException {
        if(previous.isPresent() && previous.get().sessionUuid().equals(expected.sessionUuid()) && !previous.get().equals(expected))
            throw new IOException("同 session marker policy 不符");
        return !previous.equals(Optional.of(expected));
    }

    private SessionRecoveryRecord recordFor(UUID player) {
        return journal.flushedRecords().values().stream().filter(record -> record.participants().stream()
                .anyMatch(person -> person.playerUuid().equals(player))).findFirst().orElse(null);
    }

    private void markReturning(SessionRecoveryRecord record) {
        if(record.state()==SessionState.RETURNING) return;
        journal.put(new SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),record.participants(),record.spaceLeases(),
                SessionState.RETURNING,record.restoreEntryEffectOnReturn(),record.semantics())); journal.flush(server);
    }

    private void requireServer(MinecraftServer owner) {
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("recovery 必須使用同一 server thread");
    }
}
