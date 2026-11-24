package dev.quantumchamber.corridor;

import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.transfer.SessionTransferService;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;

/** 只協調已發布玩家群體；所有空間、pin 就緒與票仍由 PageManager 擁有。 */
public final class CorridorRepositionService {
    private final MinecraftServer server;
    private final Predicate<SessionRecoveryRecord> managedActiveSession;
    /** M7 T5-3：HOME 列的換頁 predicate（正式 gateway 只在 HOME runtime 已入場、未選定時為真）；舊建構子一律不管 HOME。 */
    private final Predicate<HomeSessionRecord> managedHomeSession;
    private final Map<UUID,Tracking> tracking=new HashMap<>();
    /** HOME 換頁目前處於容量 pending 的 SID（INFO 每段只記一次；換頁成功或列消失時清除）。 */
    private final Set<UUID> homeCapacityPending=new HashSet<>();
    private final SessionTransferService transfers=new SessionTransferService();

    public CorridorRepositionService(MinecraftServer server,Predicate<SessionRecoveryRecord> managedActiveSession) {
        this(server,managedActiveSession,home -> false);
    }
    public CorridorRepositionService(MinecraftServer server,Predicate<SessionRecoveryRecord> managedActiveSession,Predicate<HomeSessionRecord> managedHomeSession) {
        this.server=Objects.requireNonNull(server); this.managedActiveSession=Objects.requireNonNull(managedActiveSession);
        this.managedHomeSession=Objects.requireNonNull(managedHomeSession);
    }

    public void tick(MinecraftServer owner) {
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("reposition 必須在同一 server thread");
        var journal=SessionRecoveryState.get(server); journal.requireHealthy();
        // M7 T5-3：追蹤以 allFlushedSessions() 為準（含 HOME 列）；舊列照原路徑與原 predicate，HOME 列在下面以 typed 分派走自己的路徑。
        tracking.keySet().retainAll(journal.allFlushedSessions().keySet());
        homeCapacityPending.retainAll(journal.allFlushedSessions().keySet());
        for(var record : journal.flushedRecords().values()) if(record.state()==SessionState.SUPERPOSITION && managedActiveSession.test(record)) {
            if(CorridorPageManager.forServer(server).operationInProgress(record.sessionUuid())) continue;
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
        tickHome(journal);
    }

    /**
     * M7 T5-3：HOME 列（未選定的 SUPERPOSITION、正式 predicate 為真）的換頁。容量拒絕留 pending（{@link #advanceHome}）；其他失敗交 PageManager 的 HOME 故障路徑，
     * 只回 B。每列各自隔離，不中斷其他列。
     */
    private void tickHome(SessionRecoveryState journal) {
        for(var row : journal.allFlushedSessions().values()) {
            if(!(row instanceof HomeSessionRecord home) || home.state()!=SessionState.SUPERPOSITION
                    || !(home.home().selection() instanceof HomeSessionRecord.Unselected) || !managedHomeSession.test(home)) continue;
            var pages=CorridorPageManager.forServer(server);
            if(pages.operationInProgress(home.sessionUuid())) continue;
            try { advanceHome(home); }
            catch(RuntimeException failure) {
                try { pages.failHome(home.sessionUuid(),new IllegalStateException("HOME 頁面移動失敗，保留所有租約並要求安全返回 B",failure)); }
                catch(RuntimeException cleanup) {
                    failure.addSuppressed(cleanup);
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("HOME 頁面移動失敗，且故障路徑尚未接手（下一 tick 由 HOME runtime 重試）：session={}",home.sessionUuid(),failure);
                }
            }
        }
    }

    /** 比照 {@link #advance}：Buff 核對換成 HOME 的 {@code homeCohortHasBuff}，容量拒絕（{@link dev.quantumchamber.persistence.HomeJournalBudget.CapacityExceeded}）留 pending。 */
    private void advanceHome(HomeSessionRecord row) {
        var sid=row.sessionUuid();
        var pages=CorridorPageManager.forServer(server); var world=server.getWorld(SuperpositionWorld.KEY);
        requireHomeBuff(sid,pages);
        var state=tracking.computeIfAbsent(sid,ignored -> new Tracking());
        if(state.pending!=null) {
            if(!pages.ready(state.pending)) return;
            var batch=pages.beginRemap(state.pending,pages.affectedEntityOwners(state.pending));
            try {
                for(var move : batch.moves()) {
                    requireHomeBuff(sid,pages);
                    var entity=world.getEntity(move.entityUuid()); var pose=move.after();
                    if(entity==null || !transfers.move(entity,world,pose.position(),pose.velocity(),pose.yaw(),pose.pitch()))
                        throw new IllegalStateException("HOME native entity remap 尚未確認："+move.entityUuid());
                }
                requireHomeBuff(sid,pages);
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
        var visible=pages.currentEntityOwners(sid);
        if(visible.isEmpty()) return;
        var owners=visible.get();
        for(var person : row.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || player.getServerWorld()!=world || world.getEntity(person.playerUuid())!=player || !owners.containsKey(person.playerUuid()))
                throw new IllegalStateException("HOME 完整凍結 cohort 已離線或離開目前唯一映射");
        }
        var occupied=new HashSet<Long>();
        var tag="quantumchamber_session:"+sid;
        for(var entry : owners.entrySet()) {
            var entity=world.getEntity(entry.getKey());
            if(entity==null) throw new IllegalStateException("已發布 pin 的真 UUID 已消失");
            if(SuperpositionEntityPolicy.managed(entity) && !(entity instanceof PlayerEntity)) {
                if(entity.getCommandTags().stream().anyMatch(value -> value.startsWith("quantumchamber_session:") && !value.equals(tag))
                        || !entity.getCommandTags().contains(tag) && !entity.addCommandTag(tag))
                    throw new IllegalStateException("普通實體沒有唯一 session tag");
            }
            var logical=pages.toLogical(entry.getValue(),pose(entity));
            occupied.add((long)Math.floor(logical.logicalZ()/96.0));
        }
        if(occupied.isEmpty() || occupied.equals(state.occupied)) return;
        state.targetPages=Set.copyOf(occupied);
        try {
            state.pending=pages.prepareRemap(sid,pages.currentMappings(sid).epoch(),state.targetPages);
            homeCapacityPending.remove(sid);
        } catch(dev.quantumchamber.persistence.HomeJournalBudget.CapacityExceeded retry) {
            // spec §5.1：容量不足是可重試的 pending，不轉 RETURNING。PageManager 已撤回本次多保留的 slot，舊 mapping、原 SID、未變的 Selection 與 CLOSE 空間都保留；
            // 下一個 tick 再算一次 occupied 並重試。同一段 pending 只記一則 INFO。
            state.targetPages=Set.of();
            if(homeCapacityPending.add(sid))
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("HOME 換頁的 journal 預留不足，保留原映射、原 SID 與 CLOSE 空間，下一 tick 重試（不轉 RETURNING）：session={}：{}",
                        sid,retry.getMessage());
        }
    }

    private static void requireHomeBuff(UUID sid,CorridorPageManager pages) {
        if(!pages.homeCohortHasBuff(sid)) throw new IllegalStateException("HOME remap 前完整 cohort 的 Buff 失效");
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
            if(SuperpositionEntityPolicy.managed(entity) && !(entity instanceof PlayerEntity)) {
                if(entity.getCommandTags().stream().anyMatch(value -> value.startsWith("quantumchamber_session:") && !value.equals(tag))
                        || !entity.getCommandTags().contains(tag) && !entity.addCommandTag(tag))
                    throw new IllegalStateException("普通實體沒有唯一 session tag");
            }
            var logical=pages.toLogical(entry.getValue(),pose(entity));
            occupied.add((long)Math.floor(logical.logicalZ()/96.0));
        }
        if(occupied.isEmpty() || occupied.equals(state.occupied)) return;
        state.targetPages=Set.copyOf(occupied);
        try {
            state.pending=pages.prepareRemap(record.sessionUuid(),pages.currentMappings(record.sessionUuid()).epoch(),state.targetPages);
        } catch(dev.quantumchamber.persistence.HomeJournalBudget.CapacityExceeded retry) {
            state.targetPages=Set.of();
        }
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
