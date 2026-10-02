package dev.quantumchamber.persistence;

import dev.quantumchamber.chamber.*;
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
    private final Map<UUID,Set<UUID>> moveWarnings=new HashMap<>(),landingWarnings=new HashMap<>();
    // 已以最後 checked authority 取代 current、尚待 checked 寫出確認的隔離 session（值為收斂目標，空值代表移除）。
    private final Map<UUID,Optional<SessionRecoveryRecord>> converging=new LinkedHashMap<>();
    private final Map<UUID,String> convergenceFailures=new HashMap<>();
    // 收斂寫出失敗的 WARN 去重鍵（FailureText.dedupeKey）與顯示文字（FailureText.describe）；恢復時的 INFO 印顯示文字。
    private String convergenceWarning,convergenceWarningText;
    // spec §6 收斂共通第 2 點：roll-forward 尚未確認時只重試同一份寫出（RETRY_FLUSH），以 journal 這個權威為單位退避（§11.2 的數列）。
    // 第一次失敗後 200 ticks 才重試，之後加倍到 12000；任何一次寫出讀回確認、或已沒有尚未確認的 roll-forward 時清除。新 instance 沒有退避。
    private final dev.quantumchamber.collapse.CollapseBackoff journalRetry=new dev.quantumchamber.collapse.CollapseBackoff();
    private int journalRetryFailedTick=Integer.MIN_VALUE;
    // 凍結中位置修正的節流：玩家 → 最近一次修正的 server tick。過期的項目與不存在等價，每個 recovery tick 清掉，
    // 所以只留最近 CORRECTION_INTERVAL_TICKS 內修正過的玩家；斷線或 session 結束後最多再留一個間隔。
    private final Map<UUID,Integer> corrections=new HashMap<>();
    private final SessionTransferService transfers=new SessionTransferService();

    /** 凍結中的位置修正：同一玩家在這麼多 server ticks 內最多送一次。 */
    public static final int CORRECTION_INTERVAL_TICKS=10;

    public SessionRecoveryManager(MinecraftServer server,Predicate<SessionRecoveryRecord> sourceAuthority) {
        if(!server.isOnThread() || active!=null) throw new IllegalStateException("recovery authority 不可重複 attach");
        this.server=server; this.journal=SessionRecoveryState.get(server);
        this.sourceAuthority=Objects.requireNonNull(sourceAuthority); active=this;
    }

    /**
     * 只能在原生網路 handler 已序列化的執行緒查詢，不讀取 Netty 執行緒上的 journal。
     * 除了 JOIN 排隊與斷線待處理，record 凍結該玩家（{@link #frozen}）就攔截。
     */
    public static boolean blocks(UUID player,MinecraftServer owner) {
        if(active==null || active.server!=owner) return false;
        active.requireServer(owner);
        try {
            return active.joinTicks.containsKey(player) || active.disconnected.contains(player) || frozen(active.journal,player);
        } catch(RuntimeException unreadable) { return true; }
    }

    /**
     * 是否有 record 凍結這位玩家。M4 的 session：current 或 flushed 任一份凍結就算。M5 的塌縮狀態（current 或 flushed 任一份是塌縮狀態的
     * session）只看 spec §6 的 collapse view：返還進度的 roll-forward 尚未確認時採用 current，已返還的人先於落盤解除凍結；隔離中則看 flushed。
     */
    static boolean frozen(SessionRecoveryState journal,UUID player) {
        var current=journal.records(); var durable=journal.flushedRecords();
        var sessions=new LinkedHashSet<UUID>(current.keySet()); sessions.addAll(durable.keySet());
        for(var sid : sessions) {
            var staged=current.get(sid); var checked=durable.get(sid);
            if(staged!=null && staged.state().collapseState() || checked!=null && checked.state().collapseState()) {
                if(journal.collapseView(sid).filter(view -> freezes(view,player)).isPresent()) return true;
            } else if(staged!=null && freezes(staged,player) || checked!=null && freezes(checked,player)) return true;
        }
        return false;
    }

    /**
     * 「record 是否凍結這位玩家」的唯一判斷。RETURNING、MEASURED 與 M5 的 COLLAPSING 只凍結尚未 checked 返還的參與者；已返還者即使
     * 同組仍有離線 pending，也恢復正常操作（M2 spec §6）。PASSAGE_READY 與 SEALED 不凍結（spec §5）。
     */
    private static boolean freezes(SessionRecoveryRecord record,UUID player) {
        return (record.state()==SessionState.RETURNING || record.state()==SessionState.MEASURED || record.state()==SessionState.COLLAPSING)
                && record.participants().stream().anyMatch(person -> person.playerUuid().equals(player) && !person.returned());
    }

    /**
     * {@link #blocks} 為真、丟棄移動封包時，是否該附送位置修正：同一玩家每 {@link #CORRECTION_INTERVAL_TICKS} 個 server ticks 最多一次，
     * 回 true 即記下本次。與 blocks 相同，只能在原生 handler 已序列化的 server thread 呼叫。
     */
    public static boolean correctionDue(UUID player,MinecraftServer owner) {
        if(active==null || active.server!=owner) return false;
        active.requireServer(owner);
        int now=owner.getTicks(); var last=active.corrections.get(player);
        if(last!=null && now-last<CORRECTION_INTERVAL_TICKS) return false;
        active.corrections.put(player,now); return true;
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
        requireServer(owner);
        // 先於 journal 健康檢查：journal 不健康時 blocks 仍會攔截並修正，節流狀態照樣過期。
        corrections.values().removeIf(last -> owner.getTicks()-last>=CORRECTION_INTERVAL_TICKS);
        journal.requireHealthy();
        // checked 提交（commitChecked、start 的初始 ARMING）失敗時，owner 會立即 CAS 還原或隔離未 checked authority；但雙重故障
        // （例如 abort 本身失敗、或初始 ARMING 的清理與 RETURNING 都寫不出）仍可能留下殘留。tick 在任何提交的同步 put→flush 之外執行，
        // 此時看得到的未 checked current 都是殘留：只隔離並收斂該 session，其他 session 的 recovery 照常，不全域暫停。
        convergeUnchecked(owner);
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
        moveWarnings.keySet().retainAll(journal.flushedRecords().keySet());
        landingWarnings.keySet().retainAll(journal.flushedRecords().keySet());
        var pages=CorridorPageManager.forServer(owner);
        int now=owner.getTicks();
        boolean rollForwardOutstanding=false;
        for(var record : journal.flushedRecords().values()) {
            var work=recoveryWork(journal,record,pages.measuredRecoveryRequested(record.sessionUuid()));
            if(work==RecoveryWork.NONE || pages.operationInProgress(record.sessionUuid())) continue;
            // dirty candidate/selection 不得隨返還進度一併被承認；僅重試同一份 receipt 的進度。
            var current=journal.records().get(record.sessionUuid());
            if(current==null || !record.candidateContext().equals(current.candidateContext())
                    || !record.candidateLedger().equals(current.candidateLedger()) || !record.candidateSelection().equals(current.candidateSelection())) continue;
            try {
                if(work==RecoveryWork.RETRY_FLUSH) {
                    // 只重試同一份 current 的寫出，以 journal 為單位退避（spec §6 收斂共通第 2 點）。同一個 tick 之前的寫出已確認時，
                    // 這個 session 的 recoveryWork 已經不是 RETRY_FLUSH，不會走到這裡。
                    rollForwardOutstanding=true;
                    if(!journalRetry.ready(now)) continue;
                    journal.flush(owner);
                } else {
                    // 先完成本 tick 的入場／rollback 交易，下一 tick 才進行獨立返還 checkpoint。
                    if(now<=returnTicks.computeIfAbsent(record.sessionUuid(),ignored -> now)) continue;
                    recover(record);
                }
                failures.remove(record.sessionUuid());
            } catch(IOException | RuntimeException failure) {
                // 返還寫入（roll-forward）或 RETRY_FLUSH 失敗、留下尚未確認的進度：journal 這個權威進入退避，同一個 tick 只算一次失敗。
                if(journal.rollForwardPending(record.sessionUuid())) {
                    rollForwardOutstanding=true;
                    if(journalRetryFailedTick!=now) { journalRetry.failed(now); journalRetryFailedTick=now; }
                }
                // 整合審查延後清單「新 WARN 缺 cause」：以 FailureText.dedupeKey 去重，WARN 帶 root cause 的顯示文字並附 stack。
                var key=FailureText.dedupeKey(failure);
                if(!key.equals(failures.put(record.sessionUuid(),key)))
                    org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("recovery 寫出保留 pending（返還，或 roll-forward 的重試寫出），session={}：{}",record.sessionUuid(),
                            FailureText.describe(failure),failure);
            }
        }
        // 已沒有尚未確認的 roll-forward（本 tick 的寫出已確認，或其他寫出、原生 autosave 已帶出）：清除 journal 的退避。
        if(!rollForwardOutstanding) { journalRetry.reset(); journalRetryFailedTick=Integer.MIN_VALUE; }
    }

    /** recovery tick 對一筆 flushed record 要做的事。 */
    enum RecoveryWork { NONE, RECOVER, RETRY_FLUSH }

    /**
     * RETURNING 一律返還；MEASURED 只在保留式 recovery 已請求時返還與清理。M5 的 COLLAPSING 依 receipt 推導（spec §7.3 的 RETURN_PLAYERS、
     * RELEASE_GEOMETRY），不需要請求；返還與清理都完成（SETTLED）之後沒有返還工作。
     * <p>三個塌縮狀態只要有尚未確認的 roll-forward（返還與清 lease 進度、進入 SEALED、Crossed 等），一律只重試寫出同一份 current
     * （RETRY_FLUSH，以 journal 為單位退避，見 {@link #tick}），不從 flushed 疊寫新進度（spec §6 收斂共通第 2 點、§7；疊寫會讓已返還者倒退，
     * 被 sameAuthority 拒絕）。這是所有 roll-forward 唯一的重試者：塌縮流程與走廊清理在 roll-forward 未確認時只暫停，不自行重寫。
     * PASSAGE_READY 與 SEALED 全員已返還、沒有 lease，沒有返還工作。
     */
    static RecoveryWork recoveryWork(SessionRecoveryState journal,SessionRecoveryRecord record,boolean measuredRecoveryRequested) {
        return switch(record.state()) {
            case RETURNING -> RecoveryWork.RECOVER;
            case MEASURED -> measuredRecoveryRequested ? RecoveryWork.RECOVER : RecoveryWork.NONE;
            case COLLAPSING -> journal.rollForwardPending(record.sessionUuid()) ? RecoveryWork.RETRY_FLUSH
                    : MeasuredRecoveryPhase.from(record).released() ? RecoveryWork.NONE : RecoveryWork.RECOVER;
            case PASSAGE_READY, SEALED -> journal.rollForwardPending(record.sessionUuid()) ? RecoveryWork.RETRY_FLUSH : RecoveryWork.NONE;
            case ARMING, SUPERPOSITION -> RecoveryWork.NONE;
        };
    }

    /**
     * H3 q3／q4：未 checked authority 的收斂（candidate 三欄位，以及 M5 restore 類狀態邊的殘留，見 {@link SessionRecoveryState#uncheckedSessions}）。
     * 殘留先隔離（之後的寫出只帶上一份 flushed authority）；隔離 session 再以最後 checked authority 取代 current——flushed 為 ARMING／SUPERPOSITION
     * 時推進為 checked RETURNING，玩家照常返還；RETURNING、MEASURED 與三個塌縮狀態原樣保留 flushed；沒有 flushed authority 時 checked 移除——
     * 並以 checked flush 確認。寫出失敗時保持 dirty，下一 tick 重試；WARN 同原因只記一次。
     */
    private void convergeUnchecked(MinecraftServer owner) {
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        for(var sid : journal.uncheckedSessions()) if(journal.quarantine(sid))
            logger.warn("recovery 發現未 checked authority 殘留（candidate 或 M5 restore 類寫入），隔離 session={}：之後的寫出只帶上一份 flushed authority，"
                    +"並收斂為該 flushed 狀態（ARMING／SUPERPOSITION 推進為 checked RETURNING，其餘原樣保留；沒有 flushed 時 checked 移除）",sid);
        var pages=CorridorPageManager.forServer(owner);
        var quarantined=journal.quarantinedSessions();
        convergenceFailures.keySet().retainAll(quarantined);
        for(var sid : quarantined) {
            if(pages.operationInProgress(sid)) continue;
            try { converging.put(sid,journal.convergeQuarantined(sid)); convergenceFailures.remove(sid); }
            catch(RuntimeException failure) {
                var cause=FailureText.dedupeKey(failure);
                if(!cause.equals(convergenceFailures.put(sid,cause))) logger.warn("隔離 session 尚無法收斂，維持隔離並於下一 tick 重試：session={}",sid,failure);
            }
        }
        if(converging.isEmpty()) return;
        try { journal.flush(owner); }
        catch(RuntimeException failure) {
            var cause=FailureText.dedupeKey(failure);
            if(!cause.equals(convergenceWarning)) logger.warn("未 checked authority 的收斂寫出失敗，保持 dirty 並於下一 tick 重試：sessions={}",converging.keySet(),failure);
            convergenceWarning=cause; convergenceWarningText=FailureText.describe(failure); return;
        }
        // checked 寫出成功：逐一以 flushed 比對收斂目標，只有 exact 相符才宣告收斂。
        var flushed=journal.flushedRecords();
        converging.forEach((sid,target) -> {
            var durable=flushed.get(sid);
            // 收斂對象不限 candidate authority，也包含 M5 restore 類寫入的殘留（uncheckedSessions）。
            if(target.map(record -> record.equals(durable)).orElse(durable==null))
                logger.info("未 checked authority 已收斂：session={} → {}",sid,target.map(record -> "checked "+record.state()).orElse("checked 移除"));
            else logger.warn("收斂寫出成功，但 flushed 與收斂目標不符（之後的寫入已改動該 session）：session={} 目標={} flushed={}",sid,
                    target.map(record -> "checked "+record.state()).orElse("移除"),durable==null ? "ABSENT" : durable.state());
        });
        // 恢復時印顯示文字（describe，含 root cause），不印去重鍵（H3 fix round 1 re-review 的 Nit 1）。
        if(convergenceWarning!=null) logger.info("收斂寫出已恢復（先前原因：{}）",convergenceWarningText);
        converging.clear(); convergenceWarning=null; convergenceWarningText=null;
    }

    /**
     * 該 session 的收斂是否仍未經 checked 寫出確認（隔離中，或已取代 current 但寫出尚未成功）。fail-closed：
     * 依賴「殘留已 checked 移除」的清理（例如釋放 chamber）必須等它回 false。
     */
    public boolean convergencePending(UUID session) {
        requireServer(server);
        return converging.containsKey(session) || journal.quarantinedSessions().contains(session);
    }

    public boolean returnComplete(UUID session) {
        requireServer(server);
        return CorridorPageManager.forServer(server).releaseComplete(session);
    }

    public void requestMeasuredRecovery(UUID session) {
        requireServer(server);
        CorridorPageManager.forServer(server).requestMeasuredRecovery(session);
    }

    /** 保留式清理是否真的完成（record 已 DORMANT／SETTLED，而且走廊 space 已釋放），見 {@link CorridorPageManager#measuredRecoveryComplete}。 */
    public boolean measuredRecoveryComplete(UUID session) {
        requireServer(server);
        return CorridorPageManager.forServer(server).measuredRecoveryComplete(session);
    }

    public void detach(MinecraftServer owner) {
        requireServer(owner); joinTicks.clear(); disconnected.clear(); disconnectWarnings.clear(); failures.clear(); returnTicks.clear(); moveWarnings.clear();
        landingWarnings.clear();
        corrections.clear(); converging.clear(); convergenceFailures.clear(); convergenceWarning=null; convergenceWarningText=null;
        journalRetry.reset(); journalRetryFailedTick=Integer.MIN_VALUE; if(active==this) active=null;
    }

    private void recover(SessionRecoveryRecord initial) throws IOException {
        if(!sourceAuthority.test(initial)) return;
        var source=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,initial.origin().worldKey()));
        if(source==null) return;
        var frame=new ChamberFrame(initial.origin().controllerPos(),initial.origin().facing());
        var positions=new LinkedHashMap<UUID,Vec3d>();
        initial.participants().forEach(person -> positions.put(person.playerUuid(),person.sourcePosition()));
        // R2 M6：落點避開原艙內裝飾的碰撞；所有候選都被擋時沿用原本的地板 slot（舊行為），並記一次 WARN。
        var slots=ChamberReturnPlacement.plan(frame,List.copyOf(positions.keySet()),positions,position -> landingClear(source,position));
        var record=initial;
        for(var person : initial.participants()) {
            if(person.returned() || joinTicks.containsKey(person.playerUuid())) continue;
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || player.isRemoved()) continue;
            if(!inside(player,source,frame) && !landingClear(source,slots.get(person.playerUuid()))) warnLandingBlocked(record.sessionUuid(),person.playerUuid(),source);
            var marker=new PlayerRecoveryCheckpoint(record.sessionUuid(),record.restoreEntryEffectOnReturn()
                    ? PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY : PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
            var access=(PlayerRecoveryCheckpointAccess)player;
            var previous=access.quantumchamber$getRecoveryCheckpoint();
            boolean applyMarker=shouldApplyMarker(marker,previous);
            if(!inside(player,source,frame) && !transfers.move(player,source,slots.get(person.playerUuid()),Vec3d.ZERO,person.yaw(),person.pitch())
                    || !inside(player,source,frame)) {
                // 跨維度 teleport 不重設速度，原生 move 可能回報 pose 未確認但人已在原艙內；這是下一 tick 自然接續的暫態，
                // 只有嘗試後仍不在原艙 interior 才是真的移動失敗而記 WARN。
                if(!inside(player,source,frame)) warnMovePending(record.sessionUuid(),person.playerUuid(),source);
                continue;
            }
            if(!sourceAuthority.test(record)) continue;
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
            record=returnedRecord(record,person.playerUuid());
            journal.put(record); journal.flush(server);
            var warned=moveWarnings.get(record.sessionUuid()); if(warned!=null) warned.remove(person.playerUuid());
        }
        var pages=CorridorPageManager.forServer(server);
        var pins=pages.returnEntityPins(record.sessionUuid());
        if(pins.isEmpty()) return;
        var corridor=server.getWorld(SuperpositionWorld.KEY);
        for(var id : pins.get()) {
            var entity=corridor.getEntity(id);
            if(entity==null || entity instanceof ServerPlayerEntity) return;
            var position=ChamberSpaceCoordinates.position(frame,3.5,1,3.5);
            if(!ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),entity.getBoundingBox().offset(position.subtract(entity.getPos())))
                    || !sourceAuthority.test(record) || !transfers.move(entity,source,position,Vec3d.ZERO,entity.getYaw(),entity.getPitch())) return;
            source.getEntity(id).removeCommandTag("quantumchamber_session:"+record.sessionUuid());
        }
        if(record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)) {
            // MEASURED 與 COLLAPSING 保留收據：走保留式清理（spec §13.2），清完仍保留 record；RETURNING 走一般 release（移除 record）。
            if(record.state()==SessionState.MEASURED || record.state()==SessionState.COLLAPSING) pages.releaseMeasured(record.sessionUuid());
            else pages.release(record.sessionUuid());
        }
    }

    /** 返還移動未確認時保留 pending 並於下一 tick 重試；同一 session 的同一玩家只記一次 WARN，成功返還或 record 消失後才重置。 */
    private void warnMovePending(UUID session,UUID player,ServerWorld source) {
        if(moveWarnings.computeIfAbsent(session,ignored -> new HashSet<>()).add(player))
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("返還移動尚未確認，保留 pending 並於下一 tick 重試：session={} player={} source={}",
                    session,player,source.getRegistryKey().getValue());
    }

    /** 站在 {@code position}（腳底中心）的標準玩家 bbox 是否不與方塊或實心 entity 碰撞（R2 M6 的返還落點檢查）。 */
    private static boolean landingClear(ServerWorld world,Vec3d position) {
        return world.isSpaceEmpty(net.minecraft.entity.EntityType.PLAYER.getDimensions().getBoxAt(position));
    }

    /** 原艙內沒有無碰撞的返還落點、沿用預設地板 slot 時記 WARN；同一 session 的同一玩家只記一次（與移動失敗的去重相同的重置時機）。 */
    private void warnLandingBlocked(UUID session,UUID player,ServerWorld source) {
        if(landingWarnings.computeIfAbsent(session,ignored -> new HashSet<>()).add(player))
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("原艙內沒有無碰撞的返還落點，沿用預設地板落點：session={} player={} source={}",
                    session,player,source.getRegistryKey().getValue());
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

    private SessionRecoveryRecord recordFor(UUID player) { return recordFor(journal,player); }

    /**
     * 依玩家找仍佔用他的 session（spec §5 的 {@link SessionRecoveryState#occupies}；塌縮狀態依 §6 的 collapse view）。DORMANT、PASSAGE_READY、
     * SEALED 與塌縮狀態的已返還參與者不算，所以玩家同時是 SEALED A 的已返還參與者與活動 B 的參與者時只會找到 B。由於唯一性規則，最多一筆。
     */
    static SessionRecoveryRecord recordFor(SessionRecoveryState journal,UUID player) {
        return journal.flushedRecords().values().stream()
                .map(record -> record.state().collapseState() ? journal.collapseView(record.sessionUuid()).orElse(record) : record)
                .filter(record -> SessionRecoveryState.occupies(record,player)).findFirst().orElse(null);
    }

    private void markReturning(SessionRecoveryRecord record) {
        if(record.state()==SessionState.MEASURED) { requestMeasuredRecovery(record.sessionUuid()); return; }
        // COLLAPSING 的返還由 receipt 推導（recoveryWork），斷線不寫入；PASSAGE_READY 與 SEALED 不寫入（spec §12、§13.2）。
        if(record.state()==SessionState.RETURNING || record.state().collapseState()) return;
        CorridorPageManager.forServer(server).exclusiveOperation(record.sessionUuid(),() -> {
            journal.put(record.withProgress(record.participants(),record.spaceLeases(),SessionState.RETURNING,record.restoreEntryEffectOnReturn()));
            journal.flush(server); return null;
        });
    }

    private void requireServer(MinecraftServer owner) {
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("recovery 必須使用同一 server thread");
    }

    static SessionRecoveryRecord returnedRecord(SessionRecoveryRecord record,UUID player) {
        // COLLAPSING 的返還進度是 roll-forward 類寫入（spec §6），經一般 put 寫入。
        if(record.state()!=SessionState.RETURNING && record.state()!=SessionState.MEASURED && record.state()!=SessionState.COLLAPSING)
            throw new IllegalArgumentException("只有返還中的 session 可更新玩家 checkpoint 進度");
        if(record.participants().stream().noneMatch(person -> person.playerUuid().equals(player)))
            throw new IllegalArgumentException("玩家不屬於凍結 cohort");
        var people=record.participants().stream().map(person -> person.playerUuid().equals(player)
                ? new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch(),
                    person.quantumStateSnapshot(),true) : person).toList();
        return record.withProgress(people,record.spaceLeases(),record.state(),record.restoreEntryEffectOnReturn());
    }
}
