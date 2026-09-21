package dev.quantumchamber.corridor;

import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.transfer.SessionTransferService;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.server.MinecraftServer;

/** 只協調已發布玩家群體；所有空間、pin 就緒與票仍由 PageManager 擁有。 */
public final class CorridorRepositionService {
    private final MinecraftServer server;
    private final Predicate<SessionRecoveryRecord> managedActiveSession;
    private final Map<UUID,Tracking> tracking=new HashMap<>();
    private final SessionTransferService transfers=new SessionTransferService();

    public CorridorRepositionService(MinecraftServer server,Predicate<SessionRecoveryRecord> managedActiveSession) {
        this.server=Objects.requireNonNull(server); this.managedActiveSession=Objects.requireNonNull(managedActiveSession);
    }

    public void tick(MinecraftServer owner) {
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("reposition 必須在同一 server thread");
        var journal=SessionRecoveryState.get(server); journal.requireHealthy();
        tracking.keySet().retainAll(journal.flushedRecords().keySet());
        for(var record : journal.flushedRecords().values()) if(managedActiveSession.test(record)) {
            try { advance(record); }
            catch(RuntimeException failure) {
                var durable=journal.flushedRecords().get(record.sessionUuid());
                if(durable!=null) try {
                    journal.put(durable.withProgress(durable.participants(),durable.spaceLeases(),SessionState.RETURNING,
                            durable.restoreEntryEffectOnReturn())); journal.flush(server);
                } catch(RuntimeException persistence) { failure.addSuppressed(persistence); }
                org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("頁面移動失敗，保留所有租約並要求安全返還，session={}",record.sessionUuid(),failure);
            }
        }
    }

    private void advance(SessionRecoveryRecord record) {
        var pages=CorridorPageManager.forServer(server); var world=server.getWorld(SuperpositionWorld.KEY);
        requireActiveBuff(record,pages);
        var state=tracking.computeIfAbsent(record.sessionUuid(),ignored -> new Tracking());
        if(state.pending!=null) {
            if(!pages.ready(state.pending)) return;
            var batch=pages.beginRemap(state.pending,pages.affectedEntityOwners(state.pending));
            try {
                for(var move : batch.moves()) {
                    requireActiveBuff(record,pages);
                    var entity=world.getEntity(move.entityUuid()); var pose=move.after();
                    if(entity==null || !transfers.move(entity,world,pose.position(),pose.velocity(),pose.yaw(),pose.pitch()))
                        throw new IllegalStateException("native entity remap 尚未確認："+move.entityUuid());
                }
                requireActiveBuff(record,pages);
                var retired=pages.commitRemap(batch);
                state.occupied=state.targetPages; state.pending=null; state.targetPages=Set.of();
                pages.retire(retired);
            } catch(RuntimeException failure) {
                if(state.pending!=null) {
                    boolean restored=true;
                    for(var move : batch.moves().reversed()) {
                        var entity=world.getEntity(move.entityUuid()); var pose=move.before();
                        if(entity==null || !transfers.move(entity,world,pose.position(),pose.velocity(),pose.yaw(),pose.pitch())) restored=false;
                    }
                    if(restored) try { pages.retire(pages.cancelPrepared(state.pending)); state.pending=null; }
                    catch(RuntimeException unsafeCancellation) { failure.addSuppressed(unsafeCancellation); }
                }
                throw failure;
            }
            return;
        }
        var visible=pages.currentEntityOwners(record.sessionUuid());
        if(visible.isEmpty()) return;
        var owners=visible.get();
        for(var person : record.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || player.getServerWorld()!=world || world.getEntity(person.playerUuid())!=player || !owners.containsKey(person.playerUuid()))
                throw new IllegalStateException("完整凍結 cohort 已離線或離開目前唯一映射");
        }
        var occupied=new HashSet<Long>();
        var tag="quantumchamber_session:"+record.sessionUuid();
        for(var entry : owners.entrySet()) {
            var entity=world.getEntity(entry.getKey());
            if(entity==null) throw new IllegalStateException("已發布 pin 的真 UUID 已消失");
            if(entity instanceof ItemEntity || entity instanceof ProjectileEntity) {
                if(entity.getCommandTags().stream().anyMatch(value -> value.startsWith("quantumchamber_session:") && !value.equals(tag))
                        || !entity.getCommandTags().contains(tag) && !entity.addCommandTag(tag))
                    throw new IllegalStateException("普通實體沒有唯一 session tag");
            }
            var logical=pages.toLogical(entry.getValue(),pose(entity));
            occupied.add((long)Math.floor(logical.logicalZ()/96.0));
        }
        if(occupied.isEmpty() || occupied.equals(state.occupied)) return;
        state.targetPages=Set.copyOf(occupied);
        state.pending=pages.prepareRemap(record.sessionUuid(),pages.currentMappings(record.sessionUuid()).epoch(),state.targetPages);
    }

    private static void requireActiveBuff(SessionRecoveryRecord record,CorridorPageManager pages) {
        if(record.semantics()==dev.quantumchamber.persistence.SessionSemantics.LATERAL_BUFF_MAINTAINED
                && !pages.activeCohortHasBuff(record.sessionUuid())) throw new IllegalStateException("remap前完整cohort的Buff失效");
    }

    private static CorridorPageManager.PhysicalPose pose(Entity entity) {
        return new CorridorPageManager.PhysicalPose(entity.getPos(),entity.getVelocity(),entity.getYaw(),entity.getPitch());
    }
    private static final class Tracking {
        Set<Long> occupied=Set.of(0L),targetPages=Set.of();
        CorridorPageManager.PreparedMappings pending;
    }
}
