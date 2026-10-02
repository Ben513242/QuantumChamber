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
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
    private SessionRecoveryManager recovery;
    private CorridorRepositionService reposition;
    private int lastTick=Integer.MIN_VALUE;
    private String checkpointWarning;
    // 初始 ARMING 未能 checked 落盤而放棄入場時，各 chamber 已記 WARN 的原因；同一 chamber 同一原因（去重鍵）只記一次。
    private final Map<UUID,AbandonWarning> abandonWarnings=new HashMap<>();
    // 初始 authority 從未 checked、放棄入場失敗而改走 fail 的 session → 取消 reservation 失敗時已記 WARN 的去重鍵（尚未失敗為 null）。
    private final Map<UUID,String> abandonPending=new LinkedHashMap<>();
    // cancelPrepared 已成功、retire 尚未成功的初始 reservation；重試時只做 retire（cancelPrepared 不可重做）。
    private final Map<UUID,CorridorPageManager.RetiredMappings> retiredInitial=new HashMap<>();

    public static void initialize() {
        if(initialized) return;
        initialized=true;
        var manager=new SuperpositionSessionManager();
        ChamberSessions.install(manager);
        ServerLifecycleEvents.SERVER_STARTED.register(manager::attach);
        ServerTickEvents.END_SERVER_TICK.register(manager::tick);
        ServerPlayConnectionEvents.JOIN.register((handler,sender,owner) -> manager.recovery.onJoin(handler.player.getUuid(),owner));
        ServerPlayConnectionEvents.DISCONNECT.register((handler,owner) -> {
            var id=handler.player.getUuid();
            owner.execute(() -> {
                if(manager.server==owner && manager.recovery!=null) manager.recovery.onDisconnect(id,owner);
            });
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(manager::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.register(manager::detach);
    }
    private void attach(MinecraftServer owner) {
        if(server!=null || !owner.isOnThread()) throw new IllegalStateException("session authority 不可重複 attach");
        server=owner; journal=SessionRecoveryState.get(owner); journal.requireHealthy();
        CorridorPageManager.forServer(owner);
        recovery=new SessionRecoveryManager(owner,this::sourceAuthority);
        reposition=new CorridorRepositionService(owner,record -> {
            var runtime=sessions.get(record.chamberUuid());
            return server==owner && runtime!=null && runtime.initial.sessionUuid().equals(record.sessionUuid())
                    && runtime.state==SessionState.SUPERPOSITION && record.state()==SessionState.SUPERPOSITION;
        });
        for(var record : journal.flushedRecords().values()) attachRecord(record);
    }
    /**
     * 開服時依一筆 flushed record 接手。M4 狀態：非 DORMANT 一律取得來源 ticket 並轉 RETURNING（MEASURED 改為保留式 recovery）。
     * M5 的塌縮狀態不轉 RETURNING（spec §5、§12、§13.2），留給塌縮流程（F3d 的 CollapseService）：只有 COLLAPSING 需要來源 ticket
     * （返還需要）；PASSAGE_READY 與 SEALED 不建 ticket、不寫入，record 原樣保留——PASSAGE_READY 由塌縮流程在開服後結束，SEALED 保留。
     */
    private void attachRecord(SessionRecoveryRecord record) {
        switch(record.state()) {
            case COLLAPSING -> sourceTicket(record);
            case PASSAGE_READY, SEALED -> { }
            case MEASURED -> { if(MeasuredRecoveryPhase.from(record)!=MeasuredRecoveryPhase.DORMANT) { sourceTicket(record); returning(record); } }
            case ARMING, SUPERPOSITION, RETURNING -> { sourceTicket(record); returning(record); }
        }
    }
    @Override public Presence presence(MinecraftServer owner,UUID chamber) {
        requireServer(owner);
        var durable=record(chamber);
        // spec §5、§10.7：三個塌縮狀態只依 collapse view 推導，排在 runtime 判斷之前（DORMANT 續行與重啟之後都沒有 runtime）。
        if(durable!=null && durable.state().collapseState()) {
            var view=journal.collapseView(durable.sessionUuid()).orElse(durable);
            return view.state()==SessionState.PASSAGE_READY ? Presence.PASSAGE : Presence.COLLAPSE;
        }
        var runtime=sessions.get(chamber);
        if(runtime!=null && runtime.state==SessionState.RETURNING) return Presence.RETURNING;
        if(durable==null) return runtime==null ? Presence.NONE : Presence.UNKNOWN;
        if(durable.state()==SessionState.RETURNING) return Presence.RETURNING;
        if(runtime==null) return Presence.UNKNOWN;
        return runtime.state==SessionState.SUPERPOSITION || runtime.state==SessionState.MEASURED
                || durable.state()==SessionState.MEASURED ? Presence.ACTIVE : Presence.ARMING;
    }
    /** 互動仍綁定入場時的runtime與來源Controller；重啟return-only record沒有選擇權。 */
    public boolean candidateSourceAuthorized(MinecraftServer owner,SessionRecoveryRecord record) {
        requireServer(owner);
        var runtime=sessions.get(record.chamberUuid());
        return runtime!=null && (runtime.state==SessionState.SUPERPOSITION || runtime.state==SessionState.MEASURED)
                && SessionRecoveryRecord.sameAuthority(runtime.initial,record)
                && record.equals(journal.flushedRecords().get(record.sessionUuid()))
                && record.equals(journal.records().get(record.sessionUuid())) && sourceAuthority(runtime);
    }
    /** 只承認checked SELECTED讀回；呼叫者仍持有空間操作guard。 */
    public void candidateMeasured(MinecraftServer owner,UUID sid) {
        requireServer(owner);
        var record=journal.flushedRecords().get(sid);
        if(record==null || record.state()!=SessionState.MEASURED || !candidateSourceAuthorized(owner,record))
            throw new IllegalStateException("測量尚未完成exact readback");
        sessions.get(record.chamberUuid()).state=SessionState.MEASURED;
    }
    @Override public StartResult start(ServerWorld source,ChamberControllerBlockEntity controller,List<UUID> requested) {
        requireServer(source.getServer());
        var chamber=controller.chamberUuid();
        if(chamber==null || presence(server,chamber)!=Presence.NONE || requested.isEmpty() || requested.size()>25
                || new HashSet<>(requested).size()!=requested.size() || controller.activationBlocked()) return StartResult.REJECTED;
        var preview=new ChamberActivationService().evaluateReadiness(source,controller);
        if(!preview.accepted() || !new HashSet<>(preview.participantUuids()).equals(new HashSet<>(requested))
                || !source.isReceivingRedstonePower(controller.getPos())) return StartResult.REJECTED;
        // 任何 authority 建立或 reservation 前先做跨 session 玩家預檢；DORMANT receipt 只封鎖自己的 Chamber。
        if(!journal.participantsAvailable(requested)) return StartResult.REJECTED;
        // 原生玩家 checkpoint 是提交 SUPERPOSITION 與安全返還的必要條件；平台或存檔 volume 不支援時，在凍結 candidate context、
        // reservation、效果快照或任何玩家移動之前受控拒絕，不能讓玩家進走廊後卡在 RETURNING。
        if(!checkpointCapable()) return StartResult.REJECTED;
        var frame=new ChamberFrame(controller.getPos(),controller.getCachedState().get(ChamberControllerBlock.FACING));
        dev.quantumchamber.candidate.CandidatePolicySnapshot candidateContext;
        try { candidateContext=SuperpositionSession.freezeCandidateContext(server,source); }
        catch(IOException | RuntimeException unhealthy) { return StartResult.REJECTED; }
        var effects=new QuantumEffectTransaction(requested.stream().map(id -> server.getPlayerManager().getPlayer(id)).toList());
        var id=UUID.randomUUID(); var pages=CorridorPageManager.forServer(server);
        CorridorPageManager.PreparedMappings prepared;
        try { prepared=pages.reserveInitial(id,chamber,frame.outwardFacing(),Set.of(0L),SessionSemantics.LATERAL_BUFF_MAINTAINED); }
        catch(IllegalArgumentException | IllegalStateException capacity) { return StartResult.REJECTED; }
        var origin=new ChamberOriginAuthority(chamber,source.getRegistryKey().getValue(),
                dev.quantumchamber.universe.DimensionRole.fromVanillaKey(source.getRegistryKey()).orElseThrow(),
                frame.controllerPos(),frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
        var initial=SessionRecoveryRecord.candidateAware(id,chamber,origin,effects.snapshots(),prepared.target().instances().stream()
                .map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(),view.bounds())).toList(),SessionState.ARMING,false,
                SessionSemantics.LATERAL_BUFF_MAINTAINED,candidateContext);
        var runtime=new SuperpositionSession(source,controller,frame,effects,prepared,initial);
        sessions.put(chamber,runtime);
        try { journal.put(initial); journal.flush(server); }
        catch(RuntimeException failure) {
            // 初始 ARMING 未 checked：沒有上一份 flushed authority，只能 CAS 移除並取消零 geometry reservation。
            if(abandonInitial(runtime,failure)) return StartResult.REJECTED;
            fail(runtime,failure); settleUncheckedInitial(runtime); return StartResult.STAGING;
        }
        var previous=abandonWarnings.remove(chamber);
        if(previous!=null) org.slf4j.LoggerFactory.getLogger("quantumchamber").info("入場 ARMING 已恢復 checked 落盤：chamber={}（先前放棄原因：{}）",chamber,previous.reason());
        try {
            sourceTicket(initial); pages.prepare(prepared);
            return StartResult.STAGING;
        } catch(RuntimeException failure) {
            fail(runtime,failure); return StartResult.STAGING;
        }
    }
    /**
     * 每次入場前重驗 playerdata 的原生 checkpoint 能力（只開目錄 metadata HANDLE，成本小），不快取成功結果；
     * 同一原因只記一次 WARN，恢復通過時記一次 INFO。
     */
    private boolean checkpointCapable() {
        var reason=PlayerCheckpointStore.unsupportedReason(server);
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        if(reason.isEmpty()) {
            if(checkpointWarning!=null) logger.info("玩家原生 checkpoint 能力預檢已恢復通過，重新允許入場（先前原因：{}）",checkpointWarning);
            checkpointWarning=null;
            return true;
        }
        if(!reason.get().equals(checkpointWarning)) logger.warn("玩家原生 checkpoint 能力預檢未通過，拒絕入場（不預留走廊、不改效果、不移動玩家）：{}；playerdata={}",
                reason.get(),server.getSavePath(net.minecraft.util.WorldSavePath.PLAYERDATA));
        checkpointWarning=reason.get();
        return false;
    }
    /**
     * 初始 ARMING 未能 checked 落盤時放棄入場：先以 CAS 移除未 checked record，再補一次 checked 寫出，覆寫磁碟上可能已原子寫入但未確認的
     * ARMING（readback 才失敗的窗口）；最後取消零 geometry reservation 並移除 runtime。成功時玩家未移動，沒有 journal、runtime、source ticket、
     * slot 或 page 保留。補寫失敗不影響放棄：record 已不在 current，journal 保持 dirty，由下一次寫出覆寫。CAS 或清理失敗才改走既有 RETURNING。
     */
    private boolean abandonInitial(SuperpositionSession runtime,RuntimeException failure) {
        var initial=runtime.initial;
        RuntimeException unacknowledged=null;
        try {
            if(!journal.abortUnchecked(initial) || journal.records().containsKey(initial.sessionUuid())
                    || journal.flushedRecords().containsKey(initial.sessionUuid())) return false;
            try { journal.flush(server); }
            catch(RuntimeException overwrite) { unacknowledged=overwrite; }
            cancelInitial(runtime);
        } catch(RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
            if(unacknowledged!=null) failure.addSuppressed(unacknowledged);
            return false;
        }
        warnAbandoned(initial,failure,unacknowledged);
        return true;
    }
    /**
     * 取消從未 checked 的初始 reservation，並移除 runtime 與來源票。可安全重試：cancelPrepared 成功後先保留 RetiredMappings，
     * retire 失敗時下一次只重做 retire。
     */
    private void cancelInitial(SuperpositionSession runtime) {
        var pages=CorridorPageManager.forServer(server);
        var sid=runtime.initial.sessionUuid();
        var retired=retiredInitial.get(sid);
        if(retired==null) { retired=pages.cancelPrepared(runtime.prepared); retiredInitial.put(sid,retired); }
        pages.retire(retired);
        retiredInitial.remove(sid);
        sessions.remove(runtime.initial.chamberUuid());
        var ticket=tickets.remove(sid);
        if(ticket!=null) ticket.world().getChunkManager().removeTicket(SOURCE_TICKET,ticket.chunk(),2,sid);
    }
    /** 放棄入場時已記 WARN 的原因：key 只用來去重（不含檔案路徑等不穩定細節），reason 是顯示文字。 */
    private record AbandonWarning(String key,String reason) {}
    /**
     * 放棄入場的 WARN 帶 root cause，依 chamber＋原因去重（比照 checkpointCapable）；同一 chamber 之後 checked 落盤成功時記一次 INFO。
     * 去重鍵是 {@link FailureText#dedupeKey} 加上補寫是否成功，不用顯示文字：暫存檔路徑等細節每次都不同，持續故障時會讓同一原因每次都記 WARN。
     */
    private void warnAbandoned(SessionRecoveryRecord initial,RuntimeException failure,RuntimeException unacknowledged) {
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        var key=FailureText.dedupeKey(failure)+"｜補寫="+(unacknowledged==null ? "成功" : "失敗");
        var reason=FailureText.describe(failure)+"；"+(unacknowledged==null ? "磁碟上可能未確認的 ARMING 已由 checked 寫出覆寫"
                : "補寫失敗，journal 保持 dirty，由下一次寫出覆寫（"+FailureText.describe(unacknowledged)+"）");
        var previous=abandonWarnings.put(initial.chamberUuid(),new AbandonWarning(key,reason));
        if(previous!=null && previous.key().equals(key)) {
            logger.debug("入場 ARMING 以相同原因再次放棄（WARN 已去重）：chamber={} session={}",initial.chamberUuid(),initial.sessionUuid());
            return;
        }
        logger.warn("入場 ARMING 未能 checked 落盤，已移除未 checked record 並取消 reservation（同一 chamber 同一原因只記一次）：chamber={} session={} 原因={}",
                initial.chamberUuid(),initial.sessionUuid(),reason,failure);
    }
    /**
     * 放棄入場失敗、改走 fail 之後（H3 q4）：fallback RETURNING 已 checked 時由一般返還流程接手。初始 authority 從未 checked 時，立即隔離仍留在
     * current 的未 checked record（同一 tick 內其他寫出也不得承認它），並交給 finishAbandoned：recovery 以 checked 寫出確認移除之後，
     * 才取消 reservation 並移除 runtime。立即隔離分支由 GameTest native_start_cancel_fault_quarantines_unchecked_initial_at_once_windows 覆蓋。
     */
    private void settleUncheckedInitial(SuperpositionSession runtime) {
        var sid=runtime.initial.sessionUuid();
        if(journal.flushedRecords().containsKey(sid)) return;
        try { if(journal.uncheckedSessions().contains(sid)) journal.quarantine(sid); }
        catch(RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("初始 ARMING 殘留尚未隔離，交由 recovery tick 處理：session={}",sid,failure);
        }
        abandonPending.putIfAbsent(sid,null);
    }
    /**
     * 初始 authority 從未 checked 的 session：等 recovery 以 checked 寫出確認殘留已移除（current、flushed 都沒有，且 convergencePending 為假），
     * 才取消 reservation 並移除 runtime，chamber 可再使用。確認之前一律保留（fail-closed）。
     */
    private void finishAbandoned() {
        if(abandonPending.isEmpty()) return;
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        for(var sid : List.copyOf(abandonPending.keySet())) {
            var runtime=sessions.values().stream().filter(value -> value.initial.sessionUuid().equals(sid)).findFirst().orElse(null);
            // runtime 已不在，或 fallback RETURNING 後來已 checked：由一般返還流程接手。
            if(runtime==null || journal.flushedRecords().containsKey(sid)) { abandonPending.remove(sid); retiredInitial.remove(sid); continue; }
            if(journal.records().containsKey(sid) || recovery.convergencePending(sid)) continue;
            try {
                cancelInitial(runtime); abandonPending.remove(sid);
                logger.info("初始 ARMING 殘留已 checked 移除，取消 reservation 並釋放 chamber：chamber={} session={}",runtime.initial.chamberUuid(),sid);
            } catch(RuntimeException failure) {
                var cause=FailureText.dedupeKey(failure);
                if(!cause.equals(abandonPending.put(sid,cause))) logger.warn("初始 ARMING 殘留已移除，但 reservation 尚未取消，下一 tick 重試：session={}",sid,failure);
            }
        }
    }
    public void tick(MinecraftServer owner) {
        requireServer(owner);
        if(lastTick==owner.getTicks()) return;
        lastTick=owner.getTicks();
        for(var runtime : List.copyOf(sessions.values())) {
            if(CorridorPageManager.forServer(owner).operationInProgress(runtime.initial.sessionUuid())) continue;
            var durable=journal.flushedRecords().get(runtime.initial.sessionUuid());
            // spec §13.2：塌縮狀態由塌縮流程負責（F3d 的 CollapseService）；這裡不請求 measured recovery、不 fail，runtime 只跟隨 durable 狀態。
            if(durable!=null && durable.state().collapseState()) { runtime.state=durable.state(); continue; }
            if(durable!=null && durable.state()==SessionState.RETURNING) runtime.state=SessionState.RETURNING;
            if(durable!=null && durable.state()==SessionState.MEASURED) runtime.state=SessionState.MEASURED;
            if(runtime.state==SessionState.MEASURED) {
                try {
                    if(!sourceAuthority(runtime) || !CorridorPageManager.forServer(owner).activeCohortHasBuff(runtime.initial.sessionUuid()))
                        recovery.requestMeasuredRecovery(runtime.initial.sessionUuid());
                } catch(RuntimeException failure) { recovery.requestMeasuredRecovery(runtime.initial.sessionUuid()); }
                continue;
            }
            if(runtime.state==SessionState.SUPERPOSITION) {
                try {
                    if(!sourceAuthority(runtime) || !CorridorPageManager.forServer(owner).activeCohortHasBuff(runtime.initial.sessionUuid()))
                        throw buffLapsed(runtime) ? new CohortBuffLapsed("凍結 cohort 有成員的 QuantumState 已到期或被解除")
                                : new IllegalStateException("活動中的來源身分或完整cohort效果失效");
                } catch(RuntimeException failure) { fail(runtime,failure); continue; }
            }
            if(runtime.state!=SessionState.ARMING) continue;
            try {
                if(!eligible(runtime)) throw buffLapsed(runtime) ? new CohortBuffLapsed("準備期間凍結 cohort 有成員的 QuantumState 已到期或被解除")
                        : new IllegalStateException("準備期間來源身分／cohort／全員資格改變");
                var pages=CorridorPageManager.forServer(owner);
                if(!pages.ready(runtime.prepared)) continue;
                enter(runtime,pages);
            } catch(IOException | RuntimeException failure) { fail(runtime,failure); }
        }
        reposition.tick(owner); recovery.tick(owner); finishAbandoned();
        // 只有 DORMANT 的 MEASURED 在保留式清理完成後釋放 runtime 與來源 ticket。COLLAPSING 的清理收尾走 CorridorPageManager 延伸後的同一路徑，
        // runtime 與來源 ticket 保留到塌縮流程的終局或進入 SEALED 才釋放（spec §5：releaseAfterTerminal、releaseOnSealed）。
        for(var record : journal.flushedRecords().values()) if(record.state()==SessionState.MEASURED
                && recovery.measuredRecoveryComplete(record.sessionUuid())) {
            sessions.remove(record.chamberUuid());
            var ticket=tickets.remove(record.sessionUuid());
            if(ticket!=null) ticket.world().getChunkManager().removeTicket(SOURCE_TICKET,ticket.chunk(),2,record.sessionUuid());
        }
    }
    private boolean eligible(SuperpositionSession runtime) {
        if(!sourceAuthority(runtime) || runtime.controller.activationBlocked()
                || !runtime.source.isReceivingRedstonePower(runtime.sourceFrame.controllerPos())) return false;
        var preview=new ChamberActivationService().evaluateReadiness(runtime.source,runtime.controller);
        return preview.accepted() && new HashSet<>(preview.participantUuids()).equals(ids(runtime.initial))
                && runtime.initial.participants().stream().allMatch(person -> {
                    var player=server.getPlayerManager().getPlayer(person.playerUuid());
                    return player!=null && player.getServerWorld()==runtime.source && !player.isSpectator()
                            && player.hasStatusEffect(ModEffects.QUANTUM_STATE);
                });
    }
    /** 只核對凍結來源權威，不把供電、enabled、buff或cohort資格當成返還目的身分。 */
    private boolean sourceAuthority(SuperpositionSession runtime) {
        var origin=runtime.initial.origin();
        if(runtime.source.getServer()!=server || server.getWorld(runtime.source.getRegistryKey())!=runtime.source
                || !origin.worldKey().equals(runtime.source.getRegistryKey().getValue())
                || !origin.controllerPos().equals(runtime.sourceFrame.controllerPos()) || origin.facing()!=runtime.sourceFrame.outwardFacing()
                || runtime.controller.isRemoved() || runtime.controller.getWorld()!=runtime.source
                || !origin.controllerPos().equals(runtime.controller.getPos()) || !origin.chamberUuid().equals(runtime.controller.chamberUuid())
                || runtime.controller.instanceKind()!=ChamberInstanceKind.ORIGIN) return false;
        var chunk=runtime.source.getChunkManager().getWorldChunk(origin.controllerPos().getX()>>4,origin.controllerPos().getZ()>>4);
        if(chunk==null || chunk.getBlockEntity(origin.controllerPos())!=runtime.controller) return false;
        var block=chunk.getBlockState(origin.controllerPos());
        if(!block.isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER) || !block.equals(runtime.controller.getCachedState())
                || block.get(ChamberControllerBlock.FACING)!=origin.facing()) return false;
        try {
            var state=ChamberRegistryState.get(server); state.requireHealthy();
            return state.registry().findOrigin(origin.worldKey(),origin.role(),runtime.sourceFrame)
                    .filter(record -> record.chamberUuid().equals(origin.chamberUuid()) && !record.destroyed()
                            && record.instanceKind()==ChamberInstanceKind.ORIGIN && record.originWorldKey().equals(origin.worldKey())
                            && record.originDimensionRole()==origin.role() && record.anchorPos().equals(origin.controllerPos())
                            && record.facing()==origin.facing()).isPresent();
        } catch(RuntimeException unreadableAuthority) { return false; }
    }
    /** 重啟只用持久 origin 核對；同程序仍綁定入場時的 Controller 實例。 */
    private boolean sourceAuthority(SessionRecoveryRecord record) {
        var runtime=sessions.get(record.chamberUuid());
        if(runtime!=null) return runtime.initial.sessionUuid().equals(record.sessionUuid()) && sourceAuthority(runtime);
        var origin=record.origin();
        var source=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,origin.worldKey()));
        if(source==null) return false;
        var chunk=source.getChunkManager().getWorldChunk(origin.controllerPos().getX()>>4,origin.controllerPos().getZ()>>4);
        if(chunk==null || !(chunk.getBlockEntity(origin.controllerPos()) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld()!=source || controller.instanceKind()!=ChamberInstanceKind.ORIGIN
                || !origin.chamberUuid().equals(controller.chamberUuid())) return false;
        var block=chunk.getBlockState(origin.controllerPos());
        if(!block.isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER) || !block.equals(controller.getCachedState())
                || block.get(ChamberControllerBlock.FACING)!=origin.facing()) return false;
        var registry=ChamberRegistryState.get(server); registry.requireHealthy();
        return registry.registry().findOrigin(origin.worldKey(),origin.role(),new ChamberFrame(origin.controllerPos(),origin.facing()))
                .filter(value -> value.chamberUuid().equals(origin.chamberUuid()) && !value.destroyed()
                        && value.instanceKind()==ChamberInstanceKind.ORIGIN && value.originWorldKey().equals(origin.worldKey())
                        && value.originDimensionRole()==origin.role() && value.anchorPos().equals(origin.controllerPos())
                        && value.facing()==origin.facing()).isPresent();
    }
    private void enter(SuperpositionSession runtime,CorridorPageManager pages) throws IOException {
        var target=server.getWorld(SuperpositionWorld.KEY);
        var entrance=pages.entrance(runtime.initial.sessionUuid());
        var view=runtime.prepared.target().instances().getFirst();
        var poses=new LinkedHashMap<UUID,CorridorPageManager.PhysicalPose>();
        var basis=new CorridorBasis(runtime.sourceFrame.outwardFacing(),runtime.initial.semantics());
        for(var person : runtime.initial.participants()) {
            var local=basis.toCorridorPosition(ChamberSpaceCoordinates.localPosition(runtime.sourceFrame,person.sourcePosition()));
            var logical=new CorridorPageManager.LogicalPose(local.x,local.y,local.z,
                    basis.toCorridorVector(ChamberSpaceCoordinates.localVector(runtime.sourceFrame,person.sourceVelocity())),
                    basis.toCorridorYaw(localYaw(runtime.sourceFrame,person.yaw())),person.pitch());
            var pose=pages.toPhysical(view.ref(),logical);
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(entrance),
                    player.getBoundingBox().offset(pose.position().subtract(player.getPos())))) throw new IllegalStateException("入場完整 bbox 超出入口");
            poses.put(person.playerUuid(),pose);
        }
        for(var move : poses.entrySet()) {
            if(!sourceAuthority(runtime)) throw new IllegalStateException("入場移動前來源身分失效");
            runtime.effects.verifyCurrent(server);
            var pose=move.getValue();
            runtime.moveAttempted.add(move.getKey());
            if(!transfers.move(server.getPlayerManager().getPlayer(move.getKey()),target,pose.position(),pose.velocity(),pose.yaw(),pose.pitch()))
                throw new IllegalStateException("部分移動失敗，尚未提交 cohort");
        }
        checkTargets(runtime,target,entrance,poses);
        runtime.effects.verifyCurrent(server);
        for(var person : runtime.initial.participants()) PlayerCheckpointStore.saveAndVerify(server,
                server.getPlayerManager().getPlayer(person.playerUuid()),Optional.empty());
        checkTargets(runtime,target,entrance,poses);
        if(!sourceAuthority(runtime)) throw new IllegalStateException("發布前來源身分失效");
        runtime.effects.verifyCurrent(server);
        var initial=runtime.initial;
        journal.put(initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.SUPERPOSITION,false));
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
        var known=tickets.entrySet().stream().filter(entry -> entry.getValue().record().chamberUuid().equals(controller.chamberUuid()))
                .findFirst().orElse(null);
        if(runtime==null && durable==null && known==null) return true;
        var authority=durable!=null ? durable : runtime!=null ? runtime.initial : known.getValue().record();
        // spec §10.7、§13.2：塌縮狀態一律沒有副作用、回 false。斷電、停用與結構失效的後果由塌縮流程依即時訊號處理
        // （RESOLVE 不受影響、PREPARE 退回 WAITING、PASSAGE_READY 結束、SEALED 保留）。
        if(authority.state().collapseState()) return false;
        if(CorridorPageManager.forServer(server).operationInProgress(authority.sessionUuid())) return false;
        if(authority.state()==SessionState.MEASURED) {
            recovery.requestMeasuredRecovery(authority.sessionUuid());
            // 清完幾何仍未消耗 selection；LOW 不可解除 Chamber 的 durable 阻擋。
            return false;
        }
        if(!sourceAuthority(authority) || server.getWorld(source.getRegistryKey())!=source
                || source.getBlockEntity(authority.origin().controllerPos())!=controller) return false;
        if(recovery.returnComplete(authority.sessionUuid())) {
            sessions.remove(authority.chamberUuid());
            var ticket=tickets.remove(authority.sessionUuid());
            if(ticket!=null) ticket.world().getChunkManager().removeTicket(SOURCE_TICKET,ticket.chunk(),2,authority.sessionUuid());
            CorridorPageManager.forServer(server).acknowledgeRelease(authority.sessionUuid());
            return true;
        }
        if(durable!=null) {
            if(!durable.origin().worldKey().equals(source.getRegistryKey().getValue())
                    || !durable.origin().controllerPos().equals(controller.getPos()) || controller.instanceKind()!=ChamberInstanceKind.ORIGIN)
                return false;
            returning(durable);
        }
        if(runtime!=null) runtime.state=SessionState.RETURNING;
        return false;
    }
    private void fail(SuperpositionSession runtime,Exception failure) {
        var durable=journal.flushedRecords().get(runtime.initial.sessionUuid());
        var authority=durable==null ? runtime.initial : durable;
        boolean restore=authority.restoreEntryEffectOnReturn();
        // 效果政策不是提交旗標；新模式從 ARMING 起即 KEEP_CURRENT，仍須 rollback 未提交的 pose。
        boolean rollback=durable==null || durable.state()==SessionState.ARMING;
        runtime.state=SessionState.RETURNING;
        if(rollback && sourceAuthority(runtime)) {
            var corridor=server.getWorld(SuperpositionWorld.KEY);
            for(var person : runtime.initial.participants()) {
                var player=server.getPlayerManager().getPlayer(person.playerUuid());
                // 只拉回本 session 確實嘗試移動過、或目前真的在固定走廊世界的參與者；從未進走廊的在線成員保持原地。
                if(player!=null && !runtime.moveAttempted.contains(person.playerUuid()) && player.getServerWorld()!=corridor) continue;
                if(player!=null && (!transfers.move(player,runtime.source,person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch())
                        || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(runtime.sourceFrame),player.getBoundingBox())))
                    failure.addSuppressed(new IllegalStateException("rollback pose 尚未確認："+person.playerUuid()));
            }
            if(restore && !runtime.effects.restore(server)) failure.addSuppressed(new IllegalStateException("rollback effects 尚未確認"));
        } else if(rollback) {
            failure.addSuppressed(new IllegalStateException("來源權威已失效，禁止向保存pose移動或覆寫目前效果；保留pending"));
        }
        try { returning(durable==null ? runtime.initial : durable); sourceTicket(runtime.initial); }
        catch(RuntimeException persistence) { failure.addSuppressed(persistence); }
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        // Buff 自然到期或喝牛奶是規則內的整組返還，不附 stack；rollback／落盤等任何附帶失敗仍視為故障記 WARN＋stack。
        if(failure instanceof CohortBuffLapsed && failure.getSuppressed().length==0)
            logger.info("凍結 cohort 的 QuantumState 已失效，依規則整組安全返還（正常結束，非故障）：session={}，效果恢復決策={}，原因={}",
                    runtime.initial.sessionUuid(),restore,failure.getMessage());
        else logger.warn("入場交易保留 RETURNING，效果恢復決策={}，session={}",restore,runtime.initial.sessionUuid(),failure);
    }
    /** 來源權威仍有效，且至少一位在線、存活的凍結參與者已沒有 QuantumState（自然到期或被牛奶解除）。 */
    private boolean buffLapsed(SuperpositionSession runtime) {
        return sourceAuthority(runtime) && runtime.initial.participants().stream().anyMatch(person -> {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            return player!=null && !player.isRemoved() && player.isAlive() && !player.hasStatusEffect(ModEffects.QUANTUM_STATE);
        });
    }
    /** 規則內的整組返還原因（Buff 到期／喝牛奶）；只用來分流 log 等級，返還流程與其他失敗完全相同。 */
    private static final class CohortBuffLapsed extends IllegalStateException {
        CohortBuffLapsed(String message) { super(message); }
    }
    private void returning(SessionRecoveryRecord record) {
        if(record.state()==SessionState.MEASURED) { recovery.requestMeasuredRecovery(record.sessionUuid()); return; }
        // 塌縮狀態不轉 RETURNING（spec §13.2）；呼叫端（attach、returnToOrigin）已先排除，這裡再防守一次。
        if(record.state().collapseState()) return;
        if(!journal.recoveryWritesSafe()) throw new IllegalStateException("未 checked authority 尚未確認，不可經返還落盤");
        if(record.state()==SessionState.RETURNING && record.equals(journal.records().get(record.sessionUuid()))) return;
        CorridorPageManager.forServer(server).exclusiveOperation(record.sessionUuid(),() -> {
            journal.put(record.withProgress(record.participants(),record.spaceLeases(),SessionState.RETURNING,record.restoreEntryEffectOnReturn()));
            journal.flush(server); return null;
        });
    }
    /**
     * spec §5「終局」：checked 終局讀回確認之後，移除該 session 的 runtime（{@code sessions}）與來源 ticket（{@code tickets}）。不移除的話，
     * {@link #presence} 會因殘留的 runtime 回 UNKNOWN，{@link #returnToOrigin} 也會從殘留的 ticket 取得塌縮 authority，原艙一直停在 RETURNING、
     * 不能開新 session。前提：journal 的 current 與 flushed 都已沒有該 session；不符就丟例外，不釋放仍在進行的塌縮。冪等。
     */
    public void releaseAfterTerminal(MinecraftServer owner,UUID sessionUuid) {
        requireServer(owner);
        if(journal.records().containsKey(sessionUuid) || journal.flushedRecords().containsKey(sessionUuid))
            throw new IllegalStateException("runtime 與來源 ticket 只能在 checked 終局之後釋放：session="+sessionUuid);
        releaseRuntimeAndTicket(sessionUuid);
    }
    /**
     * spec §5：執行期間進入 SEALED 時立即釋放來源 ticket，避免永久原因的 SEALED 讓來源 chunk 一直被載入；runtime 一併移除（SEALED 的
     * presence 只依 record 推導，SEALED→COLLAPSING 重試也不需要 runtime）。重試提交之後以 {@link #acquireCollapseTicket} 再取得。
     * 前提：該 session 的 collapse view 是 SEALED（含 roll-forward 尚未確認的 SEALED）；不符就丟例外。冪等。
     */
    public void releaseOnSealed(MinecraftServer owner,UUID sessionUuid) {
        requireServer(owner);
        var view=journal.collapseView(sessionUuid).orElse(null);
        if(view==null || view.state()!=SessionState.SEALED)
            throw new IllegalStateException("只有 SEALED 的 session 可以在進入時釋放來源 ticket：session="+sessionUuid);
        releaseRuntimeAndTicket(sessionUuid);
    }
    /**
     * 提交 COLLAPSING 之後取得來源 ticket（返還需要，spec §5）。live 塌縮的 runtime 在入場時已取得，這裡不重複；DORMANT 續行與同一個
     * server instance 內的 SEALED→COLLAPSING 重試沒有 ticket，必須在這裡取得（attach 只為開服當下就是 COLLAPSING 的 record 取得）。
     * 前提：flushed 恰是這筆 COLLAPSING record；不符就丟例外。冪等。
     */
    public void acquireCollapseTicket(MinecraftServer owner,SessionRecoveryRecord record) {
        requireServer(owner);
        var durable=journal.flushedRecords().get(record.sessionUuid());
        if(durable==null || durable.state()!=SessionState.COLLAPSING || !durable.equals(record))
            throw new IllegalStateException("只有已 checked 的 COLLAPSING 可以取得來源 ticket：session="+record.sessionUuid());
        sourceTicket(record);
    }
    private void releaseRuntimeAndTicket(UUID sessionUuid) {
        sessions.values().removeIf(runtime -> runtime.initial.sessionUuid().equals(sessionUuid));
        var ticket=tickets.remove(sessionUuid);
        if(ticket!=null) ticket.world().getChunkManager().removeTicket(SOURCE_TICKET,ticket.chunk(),2,sessionUuid);
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
        tickets.put(record.sessionUuid(),new SourceTicket(source,chunk,record));
    }
    private void stopping(MinecraftServer owner) {
        // 前置 authority attach 失敗時仍會收到 STOPPING；此時沒有本 manager 的資源。
        if(server==null) return;
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("session cleanup 必須在同一 server thread");
        // 釋放已持有的來源票不讀寫 journal，不能由不健康或部分初始化的 journal 中斷關閉。
        for(var entry : tickets.entrySet()) entry.getValue().world().getChunkManager()
                .removeTicket(SOURCE_TICKET,entry.getValue().chunk(),2,entry.getKey());
        tickets.clear();
    }
    private void detach(MinecraftServer owner) {
        if(server!=owner) return;
        if(!owner.isOnThread()) throw new IllegalStateException("session detach 必須在同一 server thread");
        if(recovery!=null) recovery.detach(owner);
        recovery=null; reposition=null; sessions.clear(); tickets.clear(); journal=null; server=null; lastTick=Integer.MIN_VALUE; checkpointWarning=null;
        abandonWarnings.clear(); abandonPending.clear(); retiredInitial.clear();
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
        var direction=ChamberSpaceCoordinates.localVector(frame,new Vec3d(-Math.sin(angle),0,Math.cos(angle)));
        return (float)Math.toDegrees(Math.atan2(-direction.x,direction.z));
    }
    private record SourceTicket(ServerWorld world,ChunkPos chunk,SessionRecoveryRecord record) {}
}
