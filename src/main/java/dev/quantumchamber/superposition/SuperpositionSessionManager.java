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
    sealed interface Owner permits SuperpositionSession, Provisional, HomeRuntime, HomeResume {}
    private static final class Provisional implements Owner {
        final UUID sid;
        final int claimedAt;
        boolean valid = true;
        boolean warned;
        /** M7 HOME（T5-2）：等待 A 的 START_CHECK 票 FULL 的入場；null 表示一般暫占或檢查已用掉。 */
        HomeStart home;
        Provisional(UUID sid,int claimedAt) { this.sid = sid; this.claimedAt=claimedAt; }
    }
    /** startHome 受理後、A 的 START_CHECK FULL 之前保留的入場請求（只在本 server instance、記憶體中）。 */
    private record HomeStart(ServerWorld world,ChamberControllerBlockEntity controller,HomeSessionRecord.EntryEndpoint endpoint,
            ChamberOriginAuthority root,List<UUID> requested,dev.quantumchamber.collapse.HomeRootTickets.Lease lease) {}
    /**
     * M7 T5-4：點 HOME 門的選門請求（spec §4.2）：原互動的玩家 UUID 與實體、走廊世界、門格、點門當下的門定位（DoorKey、owner MappingRef、current epoch）、受理的 tick 與
     * A 的 SELECT_CHECK 票。只用一次。
     */
    private record HomeSelectRequest(UUID player,ServerPlayerEntity instance,ServerWorld world,BlockPos pos,CorridorPageManager.HomeDoorHit hit,int requestedAt,
            dev.quantumchamber.collapse.HomeRootTickets.Lease lease) {}
    /** B 的資格與投影權威都已核對：Entry 是 exact B，Root 是同一個 Chamber 的 canonical 主世界 ORIGIN。 */
    private record HomeEntryCheck(HomeSessionRecord.EntryEndpoint endpoint,ChamberOriginAuthority root) {}
    /**
     * M7 HOME 的 runtime 入場交易（T5-2，比照 {@link SuperpositionSession}）：恢復權威仍在 checked journal。來源是 exact B，所以與 legacy runtime 分開型別，
     * legacy 的 {@link #runtime}／{@link #activeSessions} 看不到它。
     */
    static final class HomeRuntime implements Owner {
        final ServerWorld entryWorld;
        final ChamberControllerBlockEntity controller;
        final ChamberFrame frame;
        final QuantumEffectTransaction effects;
        final CorridorPageManager.PreparedMappings prepared;
        final HomeSessionRecord initial;
        SessionState state=SessionState.ARMING;
        /** 本 session 已嘗試原生移動的參與者；ARMING rollback 只拉回這些人或目前真的在走廊世界的人。 */
        final Set<UUID> moveAttempted=new HashSet<>();
        /** M7 T5-4：等待 A 的 SELECT_CHECK 票 FULL 的選門請求（同 SID 最多一份；只在本 server instance、記憶體中）。 */
        HomeSelectRequest select;
        HomeRuntime(ServerWorld entryWorld,ChamberControllerBlockEntity controller,ChamberFrame frame,QuantumEffectTransaction effects,
                CorridorPageManager.PreparedMappings prepared,HomeSessionRecord initial) {
            this.entryWorld=entryWorld; this.controller=controller; this.frame=frame; this.effects=effects; this.prepared=prepared; this.initial=initial;
        }
    }
    /**
     * M7 T5-5：開服接手（attach）時需要 B 的 checked HOME 列的唯一 owner（spec §6 :180，沿 {@link #sessions} 的 Chamber UUID key）。不建 {@link HomeRuntime}（它需要
     * {@code startHome} 才有的 B Controller、藥效交易與 PreparedMappings）；busy 仍來自 journal。第一個 tick 起由 {@link #resumeHomes} 把未選定的 ARMING／SUPERPOSITION
     * 轉 RETURNING（{@code convert}）、在 exact live B 出現之後取 Entry=B 來源票；返還、清理與 checkpoint 沿用既有引擎；{@link #finishResumedHomes} 收尾（ack、釋票、移除自己）。
     * <p>T6 契約（設計稿 5.1）：T6 以自己的 runtime 取代時，把 {@link #sessions} 的值原地換掉，{@link #homeTickets} 裡以 SID 為 key 的 B 來源票原地沿用，不先釋放再取
     * （spec §7 :203「不雙重取得」）；被取代的 HomeResume 已不在 sessions，收尾段不會碰到它。
     */
    static final class HomeResume implements Owner {
        final UUID sid, chamber;
        final HomeSessionRecord.EntryEndpoint entry;
        final boolean convert;
        /** exact live B 解析不到（托管等待）時，本 instance 只記一次 WARN。 */
        boolean pendingWarned;
        HomeResume(UUID sid,UUID chamber,HomeSessionRecord.EntryEndpoint entry,boolean convert) {
            this.sid=sid; this.chamber=chamber; this.entry=entry; this.convert=convert;
        }
    }
    private static UUID ownerSid(Owner owner) {
        return switch(owner) {
            case Provisional provisional -> provisional.sid;
            case SuperpositionSession runtime -> runtime.initial.sessionUuid();
            case HomeRuntime home -> home.initial.sessionUuid();
            case HomeResume resume -> resume.sid;
        };
    }
    private final Map<UUID,Owner> sessions=new LinkedHashMap<>();

    private SuperpositionSession runtime(UUID chamber) {
        return sessions.get(chamber) instanceof SuperpositionSession session ? session : null;
    }
    private List<SuperpositionSession> activeSessions() {
        return sessions.values().stream().filter(SuperpositionSession.class::isInstance).map(SuperpositionSession.class::cast).toList();
    }

    /** 已確認的 ARMING 取代暫占。未確認或保留中的寫入繼續占位。 */
    public boolean finishProvisional(MinecraftServer owner,UUID chamber,UUID sid) {
        if (!ownsProvisional(owner,chamber,sid)) return false;
        var checked=journal.allFlushedSessions().get(sid);
        if (!(checked instanceof HomeSessionRecord home) || home.state()!=SessionState.ARMING || !home.chamberUuid().equals(chamber)
                || !home.equals(journal.allSessions().get(sid)) || journal.pendingSessions().containsKey(sid)
                || journal.quarantinedSessions().contains(sid)) return false;
        var token=(Provisional)sessions.get(chamber);
        token.valid=false;
        dev.quantumchamber.collapse.HomeRootTickets.release(owner,sid);
        sessions.remove(chamber,token);
        return true;
    }

    private boolean journalBusy(UUID chamber) {
        var snapshots = List.of(journal.allSessions(),journal.allFlushedSessions(),journal.pendingSessions());
        var quarantine = journal.quarantinedSessions();
        // 未知的隔離列連艙身分也不能確認，先保守擋住所有入場。
        return snapshots.stream().flatMap(rows -> rows.values().stream()).anyMatch(row -> row.chamberUuid().equals(chamber))
                || quarantine.stream().anyMatch(sid -> snapshots.stream().noneMatch(rows -> rows.containsKey(sid)));
    }
    public boolean globalChamberBusy(MinecraftServer owner, UUID chamber) {
        if (owner==null || owner!=server || !owner.isOnThread()) return true;
        try { return sessions.containsKey(chamber) || journalBusy(chamber); }
        catch (RuntimeException unreadable) { return true; }
    }
    public boolean claimProvisional(MinecraftServer owner, UUID chamber, UUID sid) {
        Objects.requireNonNull(chamber); Objects.requireNonNull(sid);
        if (globalChamberBusy(owner,chamber)) return false;
        var pages=CorridorPageManager.forServer(owner);
        if (pages.operationInProgress(sid) || pages.hasReservation(sid) || sessions.values().stream().anyMatch(value -> ownerSid(value).equals(sid))) return false;
        if (journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)
                || journal.pendingSessions().containsKey(sid) || journal.quarantinedSessions().contains(sid)) return false;
        boolean claimed=pages.exclusiveOperation(sid,() -> sessions.putIfAbsent(chamber,new Provisional(sid,owner.getTicks()))==null);
        // M7 spec §2 [R3]：A 自 B 暫占起受保護；撤銷舊的斷電協調結果，之後的協調看到 BUSY 不會再判 OFF。
        if (claimed) ChamberProtectionService.get().revokeReconciliation(chamber);
        return claimed;
    }
    /** 同艙另一端的回程暫占（尚未 checked ARMING）。 */
    public boolean provisionalClaimed(MinecraftServer owner, UUID chamber) {
        return owner==server && owner.isOnThread() && sessions.get(chamber) instanceof Provisional p && p.valid;
    }
    public boolean ownsProvisional(MinecraftServer owner, UUID chamber, UUID sid) {
        return owner==server && owner.isOnThread() && sessions.get(chamber) instanceof Provisional p && p.valid && p.sid.equals(sid);
    }
    public void cancelProvisional(MinecraftServer owner, UUID chamber, UUID sid) {
        if (owner!=server || !owner.isOnThread()) throw new IllegalStateException("必須在原 server thread 取消暫占");
        if (!(sessions.get(chamber) instanceof Provisional p) || !p.sid.equals(sid)) return;
        p.valid=false;
        dev.quantumchamber.collapse.HomeRootTickets.release(owner,sid);
        if (!CorridorPageManager.forServer(owner).hasReservation(sid)) {
            try { if (!journalBusy(chamber)) sessions.remove(chamber,p); }
            catch (RuntimeException unreadable) { /* journal 恢復後重新確認。 */ }
        }
    }
    @Override public Presence localPresence(ServerWorld world, net.minecraft.util.math.BlockPos pos) {
        var owner=world.getServer();
        if (owner!=server || !owner.isOnThread() || owner.getWorld(world.getRegistryKey())!=world) return Presence.UNKNOWN;
        var chunk=world.getChunkManager().getWorldChunk(pos.getX()>>4,pos.getZ()>>4);
        if (chunk==null) return Presence.UNKNOWN;
        if (!(chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller)) return Presence.NONE;
        if (controller.instanceKind()==ChamberInstanceKind.PROJECTION) {
            var id=ChamberCollapsePort.projections().entryChamber(world,controller);
            if (id.isEmpty()) return Presence.NONE;
            try {
                for (var row:journal.allFlushedSessions().values()) if (row instanceof HomeSessionRecord h
                        && h.chamberUuid().equals(id.get()) && h.entryEndpoint().worldKey().equals(world.getRegistryKey().getValue())
                        && h.entryEndpoint().controllerPos().equals(pos)) {
                    if (!h.equals(journal.allSessions().get(h.sessionUuid())) || journal.quarantinedSessions().contains(h.sessionUuid())) return Presence.UNKNOWN;
                    return switch(h.state()) {
                        case ARMING -> Presence.ARMING;
                        case SUPERPOSITION, MEASURED -> Presence.ACTIVE;
                        case RETURNING -> Presence.RETURNING;
                        case COLLAPSING, SEALED -> Presence.COLLAPSE;
                        case PASSAGE_READY -> h.home().stopReason().isPresent()
                                || h.home().closeIntent() instanceof HomeSessionRecord.Close
                                || owner.getOverworld().getTime()>=h.home().passageExpiresAtGameTime()
                                ? Presence.COLLAPSE : Presence.PASSAGE;
                    };
                }
                return globalChamberBusy(owner,id.get()) ? Presence.BUSY : Presence.NONE;
            } catch (RuntimeException unreadable) { return Presence.UNKNOWN; }
        }
        return controller.chamberUuid()==null ? Presence.NONE : presence(owner,controller.chamberUuid());
    }
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
    // ---- M7 HOME（T5-2） ----
    /** HOME 的 Entry=B 來源票（SID → B 的世界與 Controller chunk）；與 legacy 的 {@link #tickets} 分開（legacy 的 SourceTicket 綁舊列）。 */
    private final Map<UUID,HomeTicket> homeTickets=new LinkedHashMap<>();
    private record HomeTicket(ServerWorld world,ChunkPos chunk) {}
    /** 初始 ARMING 沒有 checked 的 HOME 入場：暫占記號與 reservation 保留到 journal 確認沒有殘留（spec §6 :184）。 */
    private final Map<UUID,HomeAbandon> homeAbandons=new LinkedHashMap<>();
    private record HomeAbandon(UUID chamber,dev.quantumchamber.collapse.CollapseBackoff backoff) {}
    /** 要轉 RETURNING 但還沒 checked 的 HOME 列：per-SID 退避重試（B 的 Controller 沒有協調器會再呼叫 returnToOrigin）。 */
    private final Map<UUID,dev.quantumchamber.collapse.CollapseBackoff> homeReturnRetries=new HashMap<>();
    /** HOME 的 WARN 去重鍵（SID → 最近一次的原因）。 */
    private final Map<UUID,String> homeWarnings=new HashMap<>();
    /** HOME tick 段的意外例外去重（T5-3 Step 0，品質 M-10）：段名 → 最近一次的 {@link FailureText#dedupeKey}；該段之後成功一次就清除。 */
    private final Map<String,String> homeTickFailures=new HashMap<>();

    public static void initialize() {
        if(initialized) return;
        initialized=true;
        var manager=new SuperpositionSessionManager();
        ChamberSessions.install(manager);
        dev.quantumchamber.collapse.HomeRootTickets.initialize();
        // M7 spec v1.6 C：回程收尾的 B 收尾票（與 A 票共用份數、refs 與 coverage 上限）。
        dev.quantumchamber.collapse.HomeEntryTickets.initialize();
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((owner,resources) -> { manager.cancelAllProvisional(owner); manager.cancelHomeSelects(owner); });
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
            var runtime=runtime(record.chamberUuid());
            return server==owner && runtime!=null && runtime.initial.sessionUuid().equals(record.sessionUuid())
                    && runtime.state==SessionState.SUPERPOSITION && record.state()==SessionState.SUPERPOSITION;
        },home -> {
            // M7 T5-3：HOME 列只在本 server 的 HOME runtime 已入場（SUPERPOSITION）、checked 列未選定時換頁；選門之後的換頁不在 T5-3。
            var runtime=sessions.get(home.chamberUuid()) instanceof HomeRuntime value ? value : null;
            return server==owner && runtime!=null && runtime.initial.sessionUuid().equals(home.sessionUuid())
                    && runtime.state==SessionState.SUPERPOSITION && home.state()==SessionState.SUPERPOSITION
                    && home.home().selection() instanceof HomeSessionRecord.Unselected;
        });
        for(var record : journal.flushedRecords().values()) attachRecord(record);
        // M7 T5-5（設計稿第 2、3 節）：需要 B 的 checked HOME 列登記 HomeResume。attach 只建記憶體：不寫 journal、不取 B 票（attach 當下 lifecycle 的 context
        // 還不存在，exact B 一定解析不到）；轉 RETURNING 與取票在第一個 tick 起由 resumeHomes 做。
        for(var row : journal.allFlushedSessions().values()) if(row instanceof HomeSessionRecord home) {
            var action=CorridorPageManager.homeAttachAction(home);
            if(action.ticket()) sessions.put(home.chamberUuid(),new HomeResume(home.sessionUuid(),home.chamberUuid(),home.entryEndpoint(),action.convert()));
        }
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
        if (!globalChamberBusy(owner,chamber)) return Presence.NONE;
        if (owner!=server || !owner.isOnThread()) return Presence.UNKNOWN;
        SessionRecoveryRecord durable;
        try { durable=record(chamber); } catch (RuntimeException unreadable) { return Presence.UNKNOWN; }
        // spec §5、§10.7：三個塌縮狀態只依 collapse view 推導，排在 runtime 判斷之前（DORMANT 續行與重啟之後都沒有 runtime）。
        if(durable!=null && durable.state().collapseState()) {
            var view=journal.collapseView(durable.sessionUuid()).orElse(durable);
            return view.state()==SessionState.PASSAGE_READY ? Presence.PASSAGE : Presence.COLLAPSE;
        }
        var runtime=runtime(chamber);
        if(runtime!=null && runtime.state==SessionState.RETURNING) return Presence.RETURNING;
        if(durable==null) return globalChamberBusy(owner,chamber) ? Presence.BUSY : Presence.NONE;
        if(durable.state()==SessionState.RETURNING) return Presence.RETURNING;
        if(runtime==null) return Presence.UNKNOWN;
        return runtime.state==SessionState.SUPERPOSITION || runtime.state==SessionState.MEASURED
                || durable.state()==SessionState.MEASURED ? Presence.ACTIVE : Presence.ARMING;
    }
    /**
     * M7 spec v1.6 D（{@link ChamberSessionGateway#permanentlySealedOnly}）：讀本 server 的 journal、runtime／暫占與走廊 reservation，交給純函式
     * {@link #permanentlySealedOnly(UUID, Map, Map, Map, Set, java.util.function.Predicate, boolean, java.util.function.Predicate)}。不在原 server thread、journal
     * 不健康或讀取丟出時回 false（不依讀不到的權威放行）。只讀，不寫 journal 或任何 ledger。
     * <p>保守行為（T4A 審查 M-7，T4B Step 0 只評估、不改）：{@code runtimeOrProvisional} 取 {@code sessions.containsKey(chamber)}，已失效（{@code valid=false}）但因
     * {@link #cancelProvisional} 遇到 journal 仍有這座艙的列而保留的暫占記號也算在內，這時不放行，要等重啟（記號是 process 狀態）才解除。暫占只在這座艙沒有任何 journal 列時取得
     * （{@link #globalChamberBusy}），所以能留下失效記號的只有同一個 SID 的 HOME 列；它要變成永久封存才會走到這裡，而 HOME 永久封存的產生點屬 T6，T4A／T4B 只由測試 fixture
     * 造出。屬 fail-closed（只延後解除），T6 加入產生點時再評估。
     */
    @Override public boolean permanentlySealedOnly(MinecraftServer owner,UUID chamber) {
        if(owner==null || owner!=server || !owner.isOnThread() || chamber==null) return false;
        try {
            journal.requireHealthy();
            var pages=CorridorPageManager.forServer(owner);
            return permanentlySealedOnly(chamber,journal.allSessions(),journal.allFlushedSessions(),journal.pendingSessions(),journal.quarantinedSessions(),
                    journal::rollForwardPending,sessions.containsKey(chamber),sid -> pages.hasReservation(sid) || pages.operationInProgress(sid));
        } catch(RuntimeException unreadable) { return false; }
    }
    /**
     * M7 spec v1.6 D 的「只剩永久封存」判定（純函式）。只有下列全部成立時為真：
     * <ul>
     * <li>沒有 runtime 或暫占（{@code runtimeOrProvisional} 為假），也沒有送出中尚未確認的寫入（{@code pending} 為空）；</li>
     * <li>這座艙在 current 或 flushed 至少有一列，每一列在 current 與 flushed 都存在而且相同，沒有 roll-forward 尚未確認、沒有隔離、沒有走廊 reservation 或進行中的操作；
     *     連艙身分都無法確認的隔離列（不在 current 與 flushed）也不放行；</li>
     * <li>每一列都是永久原因的 SEALED：舊列 EXISTING_INELIGIBLE、AUTHORITY_CORRUPT、PROJECTION_CONFLICT、TRANSFER_RECOVERY_REQUIRED（{@link dev.quantumchamber.collapse.SealedReason#retryable()}
     *     為假）；HOME 列 ENTRY_AUTHORITY_INVALID、AUTHORITY_CORRUPT、TRANSFER_RECOVERY_REQUIRED。可重試與 restart-only 的原因、DORMANT、WAITING 一律不適用。</li>
     * </ul>
     */
    static boolean permanentlySealedOnly(UUID chamber,Map<UUID,JournalSession> current,Map<UUID,JournalSession> flushed,Map<UUID,JournalSession> pending,
            Set<UUID> quarantined,java.util.function.Predicate<UUID> rollForwardPending,boolean runtimeOrProvisional,java.util.function.Predicate<UUID> reserved) {
        if(runtimeOrProvisional || !pending.isEmpty()) return false;
        for(var sid : quarantined) if(!current.containsKey(sid) && !flushed.containsKey(sid)) return false;
        var rows=new LinkedHashSet<UUID>();
        for(var snapshot : List.of(current,flushed)) snapshot.forEach((sid,row) -> { if(row.chamberUuid().equals(chamber)) rows.add(sid); });
        if(rows.isEmpty()) return false;
        for(var sid : rows) {
            var row=current.get(sid);
            if(row==null || !row.equals(flushed.get(sid)) || rollForwardPending.test(sid) || quarantined.contains(sid) || reserved.test(sid)
                    || !permanentSeal(row)) return false;
        }
        return true;
    }
    private static final Set<HomeSessionRecord.Seal> PERMANENT_HOME_SEALS=Set.of(HomeSessionRecord.Seal.ENTRY_AUTHORITY_INVALID,
            HomeSessionRecord.Seal.AUTHORITY_CORRUPT,HomeSessionRecord.Seal.TRANSFER_RECOVERY_REQUIRED);
    private static boolean permanentSeal(JournalSession row) {
        if(row.state()!=SessionState.SEALED) return false;
        if(row instanceof SessionRecoveryRecord old) return old.collapse().sealedReason().filter(reason -> !reason.retryable()).isPresent();
        return row instanceof HomeSessionRecord home && home.home().sealedReason().filter(PERMANENT_HOME_SEALS::contains).isPresent();
    }
    /** 互動仍綁定入場時的runtime與來源Controller；重啟return-only record沒有選擇權。 */
    public boolean candidateSourceAuthorized(MinecraftServer owner,SessionRecoveryRecord record) {
        requireServer(owner);
        var runtime=runtime(record.chamberUuid());
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
        runtime(record.chamberUuid()).state=SessionState.MEASURED;
    }
    @Override public void onUnknownSourcePreset(MinecraftServer owner,SessionRecoveryRecord checked) {
        requireServer(owner);
        java.util.Objects.requireNonNull(checked,"checked");
        var runtime=runtime(checked.chamberUuid());
        if(checked.state()!=SessionState.SUPERPOSITION || !checked.candidateContext().map(dev.quantumchamber.candidate.CandidatePolicySnapshot::isM6).orElse(false)
                || !checked.equals(journal.flushedRecords().get(checked.sessionUuid())) || !checked.equals(journal.records().get(checked.sessionUuid()))
                || runtime==null || !runtime.initial.sessionUuid().equals(checked.sessionUuid()) || !sourceAuthority(runtime))
            throw new IllegalStateException("未知preset通知的exact session/controller owner不符");
        runtime.controller.requireCandidateRetryLow();
    }
    /**
     * M7 HOME 入場：從同艙已確認的 COMPLETE 投影 B 啟動回程走廊（plan Task 5 :308、:315、:317；spec §4.1、§6、§7）。T7 才接正式入口，T5～T6 只由 testmod 呼叫。
     * <ol>
     * <li>任何 claim、ticket、reservation 或 ARMING 之前依序：B 的 latch（v1.6 A）→ 平台的原生玩家 checkpoint 能力（{@link PlayerCheckpointStore#unsupportedReason}）→
     *     B 的資格與投影權威（exact live B、COMPLETE projection、結構、封艙、供電、cohort、跨 session 玩家）——controller C11。</li>
     * <li>同一個 server-thread 步驟暫占（{@link #claimProvisional}，從這一刻起 A 也 busy），再取得 A 的 START_CHECK 票（≤200 ticks，{@code HomeRootTickets}）。</li>
     * <li>回 {@link StartResult#STAGING}：受理、等待 A FULL（controller C5）。之後由 {@link #tick} 在票 FULL 的 tick 續行（{@link #continueHomeStarts}）；
     *     票期到、{@code /reload}、停服都由暫占與票的既有 owner 釋放，不寫 ARMING。</li>
     * </ol>
     * 回 {@link StartResult#REJECTED} 時沒有暫占、票、reservation、journal 或玩家移動。唯一的保守副作用（T5-3 Step 0，品質 M-9）：暫占成功之後才取 START_CHECK 票失敗時，
     * 暫占（{@link #claimProvisional}）已撤銷 A 的斷電協調結果，{@link #cancelProvisional} 不會還原；A 維持受保護到下一次協調（與 legacy {@code start} 相同，fail-closed）。
     * latch、平台與 B 資格三項拒絕都在暫占之前，沒有這個副作用。
     */
    public StartResult startHome(ServerWorld entry,ChamberControllerBlockEntity controller,List<UUID> requested) {
        var owner=entry.getServer();
        if(owner==null || owner!=server || !owner.isOnThread() || requested.isEmpty() || requested.size()>25
                || new HashSet<>(requested).size()!=requested.size()) return StartResult.REJECTED;
        // C11（plan :317、:547）：latch → 平台 checkpoint 能力 → 暫占；三者都在任何 claim、ticket、reservation 或 ARMING 之前。
        if(controller.rearmRequiresPlayerClose()) return StartResult.REJECTED;
        if(!checkpointCapable()) return StartResult.REJECTED;
        var check=homeEntryCheck(entry,controller,requested);
        if(check==null) return StartResult.REJECTED;
        var chamber=check.endpoint().chamberUuid();
        var sid=UUID.randomUUID();
        if(!claimProvisional(owner,chamber,sid)) return StartResult.REJECTED;
        try {
            var lease=dev.quantumchamber.collapse.HomeRootTickets.acquire(owner,sid,check.root(),dev.quantumchamber.collapse.HomeRootTickets.Purpose.START_CHECK);
            if(lease.isEmpty()) { cancelProvisional(owner,chamber,sid); return StartResult.REJECTED; }
            ((Provisional)sessions.get(chamber)).home=new HomeStart(entry,controller,check.endpoint(),check.root(),List.copyOf(requested),lease.get());
            return StartResult.STAGING;
        } catch(RuntimeException failure) {
            try { cancelProvisional(owner,chamber,sid); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("HOME 入場的 START_CHECK 未能開始，拒絕：chamber={} session={}",chamber,sid,failure);
            return StartResult.REJECTED;
        }
    }
    /**
     * B 的資格與投影權威（spec §4.1 第 1～3 步；不含 latch 與平台檢查，那兩項只在 {@link #startHome} 的固定順序裡）。不合格或讀不到時回 null，沒有副作用。
     * 資格預覽用 {@link ChamberActivationService#evaluateEntryReadiness}（controller C2：既有的 {@code evaluateReadiness} 不改、仍拒絕 PROJECTION）。
     */
    private HomeEntryCheck homeEntryCheck(ServerWorld entry,ChamberControllerBlockEntity controller,List<UUID> requested) {
        try {
            journal.requireHealthy();
            if(controller.activationBlocked()) return null;
            var endpoint=dev.quantumchamber.collapse.HomeEndpointAuthority.resolveEntry(entry,controller).orElse(null);
            if(endpoint==null || exactEntry(endpoint)!=entry) return null;
            var preview=new ChamberActivationService().evaluateEntryReadiness(entry,controller);
            if(!preview.accepted() || !new HashSet<>(preview.participantUuids()).equals(new HashSet<>(requested))
                    || !ChamberActivationService.entryPowered(entry,controller.getPos())) return null;
            if(!journal.participantsAvailable(requested)) return null;
            var root=new ChamberOriginAuthority(endpoint.chamberUuid(),net.minecraft.world.World.OVERWORLD.getValue(),
                    dev.quantumchamber.universe.DimensionRole.OVERWORLD,endpoint.controllerPos(),endpoint.facing(),ChamberInstanceKind.ORIGIN);
            return new HomeEntryCheck(endpoint,root);
        } catch(RuntimeException unreadable) { return null; }
    }
    /** exact live B：Entry 的 world key 存在，而且是該宇宙目前的 active world；否則 null。 */
    private ServerWorld exactEntry(HomeSessionRecord.EntryEndpoint endpoint) {
        var world=server.getWorld(RegistryKey.of(RegistryKeys.WORLD,endpoint.worldKey()));
        return world!=null && dev.quantumchamber.universe.UniverseLifecycleService.resolveActive(server,endpoint.universeId()).orElse(null)==world ? world : null;
    }
    /**
     * START_CHECK 的續行（controller C5）：在 {@link #tick} 裡、暫占的 200 tick（與票期同一個起點）之內，A 的票 FULL 的那個 tick 進該 SID 的 operation guard，
     * 核 A（{@code checkRoot}，含即時電位）並重驗 B、cohort、藥效、電位、latch 與平台，之後凍結快照、租走廊、checked ARMING（{@link #admitHomeStart}）；
     * 成功才取得 Entry=B 來源票、排入 HOME 幾何。檢查結果只用一次；不合格時只釋放暫占與票。
     */
    private void continueHomeStarts(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var entry : List.copyOf(sessions.entrySet())) {
            if(!(entry.getValue() instanceof Provisional p) || !p.valid || p.home==null || pages.operationInProgress(p.sid)) continue;
            var start=p.home;
            var chamber=entry.getKey();
            boolean full;
            try { full=start.lease().full(); }
            catch(RuntimeException failure) { p.home=null; rejectHomeStart(owner,chamber,p.sid,"A 的 START_CHECK 票讀取失敗",failure); continue; }
            if(!full || !p.valid) continue;
            p.home=null;
            HomeRuntime admitted;
            try { admitted=pages.exclusiveOperation(p.sid,() -> admitHomeStart(owner,chamber,p,start)); }
            catch(RuntimeException failure) { settleFailedHomeStart(owner,chamber,p.sid,failure); continue; }
            if(admitted==null) continue;
            try { homeSourceTicket(admitted); pages.prepareHome(admitted.prepared); }
            catch(RuntimeException failure) { failHome(admitted,failure); }
        }
    }
    /**
     * T5-3 Step 0（品質 M-1）：{@link #admitHomeStart} 丟出意外例外時依現況收斂，不在 journal 未確認時釋放鎖或 reservation。
     * <ul>
     * <li>這座艙已有本 SID 的 runtime（checked ARMING 之後才建立）：補取 Entry=B 來源票與走廊票（返還要它們），交 {@link #failHome}，只回 B。
     *     <b>防禦性分支</b>（T5-4 Step 0，T5-3 品質 M-6）：T5-3 Step 0 重排之後，{@link #admitHomeStart} 在 {@code sessions.put(chamber,runtime)} 之後只剩記 log 與回傳，
     *     目前不可達；保留它，讓日後在 put 之後加入會丟例外的步驟時，仍不會留下沒有收斂者的 runtime。</li>
     * <li>還有走廊 reservation 或 journal 殘留：釋放 START_CHECK 票，暫占記號與 reservation 保留（鎖），交 {@link #finishHomeAbandoned}——journal 確認沒有殘留之後才歸還。</li>
     * <li>兩者皆無（reservation 之前就丟出）：與原本相同，只釋放暫占與票。</li>
     * </ul>
     * checked ARMING 之後的清理例外由 {@link #admitHomeStart} 自己收斂（先建立 runtime），不會留下沒有 runtime 的 ARMING 列。
     */
    private void settleFailedHomeStart(MinecraftServer owner,UUID chamber,UUID sid,RuntimeException failure) {
        if(sessions.get(chamber) instanceof HomeRuntime runtime && runtime.initial.sessionUuid().equals(sid)) {
            try { homeSourceTicket(runtime); CorridorPageManager.forServer(owner).prepareHome(runtime.prepared); }
            catch(RuntimeException setup) { failure.addSuppressed(setup); }
            failHome(runtime,failure);
            return;
        }
        boolean held;
        try { held=CorridorPageManager.forServer(owner).hasReservation(sid) || homeResidue(sid); }
        catch(RuntimeException unreadable) { failure.addSuppressed(unreadable); held=true; }
        if(!held) { rejectHomeStart(owner,chamber,sid,"HOME 入場前的重驗丟出例外",failure); return; }
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        logger.warn("HOME 入場在走廊 reservation 之後丟出例外，保留暫占與 reservation，journal 確認沒有殘留之後才解除：chamber={} session={}",chamber,sid,failure);
        try { cancelProvisional(owner,chamber,sid); }
        catch(RuntimeException cleanup) { logger.warn("HOME 入場的暫占取消待重試：session={}",sid,cleanup); }
        if(!homeAbandons.containsKey(sid)) {
            var backoff=new dev.quantumchamber.collapse.CollapseBackoff();
            backoff.failed(owner.getTicks());
            homeAbandons.put(sid,new HomeAbandon(chamber,backoff));
        }
    }
    private void rejectHomeStart(MinecraftServer owner,UUID chamber,UUID sid,String reason,RuntimeException failure) {
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("{}，拒絕 HOME 入場：chamber={} session={}",reason,chamber,sid,failure);
        try { cancelProvisional(owner,chamber,sid); }
        catch(RuntimeException cleanup) { org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("HOME 入場的暫占取消待重試：session={}",sid,cleanup); }
    }
    /**
     * spec §4.1 第 5～7 步，在該 SID 的 operation guard 內：A 的檢查（enabled、身分、結構、即時電位）→ 重驗 → 凍結快照與走廊 reservation → checked ARMING
     * （{@code admitHome}）。成功時以 runtime 取代暫占；不合格時只釋放暫占與票（沒有 reservation）；ARMING 沒有 checked 時保留暫占記號與 reservation，
     * 交 {@link #finishHomeAbandoned} 等 journal 確認沒有殘留（spec §6 :184）。
     */
    private HomeRuntime admitHomeStart(MinecraftServer owner,UUID chamber,Provisional p,HomeStart start) {
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        var sid=p.sid;
        if(!ownsProvisional(owner,chamber,sid)) return null;
        var root=dev.quantumchamber.collapse.HomeEndpointAuthority.checkRoot(owner,start.root(),true);
        if(root!=dev.quantumchamber.collapse.HomeEndpointAuthority.RootStatus.READY) {
            logger.info("HOME 入場的原艙檢查未通過（{}），拒絕啟動：chamber={} session={}",root,chamber,sid);
            cancelProvisional(owner,chamber,sid); return null;
        }
        var check=start.controller().rearmRequiresPlayerClose() ? null : homeEntryCheck(start.world(),start.controller(),start.requested());
        if(check==null || !check.endpoint().equals(start.endpoint()) || !check.root().equals(start.root()) || !checkpointCapable()) {
            logger.info("HOME 入場在 A 檢查之後重驗 B／cohort／藥效／電位／平台未通過，拒絕啟動：chamber={} session={}",chamber,sid);
            cancelProvisional(owner,chamber,sid); return null;
        }
        var pages=CorridorPageManager.forServer(owner);
        QuantumEffectTransaction effects;
        CorridorPageManager.PreparedMappings prepared;
        try {
            effects=new QuantumEffectTransaction(start.requested().stream().map(id -> owner.getPlayerManager().getPlayer(id)).toList());
            prepared=pages.reserveHomeInitial(sid,start.root(),start.endpoint(),start.world(),Set.of(0L));
        } catch(IllegalArgumentException | IllegalStateException refused) {
            logger.info("HOME 入場的快照或走廊 reservation 被拒，拒絕啟動：chamber={} session={}：{}",chamber,sid,refused.getMessage());
            cancelProvisional(owner,chamber,sid); return null;
        }
        HomeSessionRecord arming;
        try {
            arming=new HomeSessionRecord(sid,chamber,start.root(),start.endpoint(),effects.snapshots(),prepared.target().instances().stream()
                    .map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(),view.bounds())).toList(),SessionState.ARMING,UNSELECTED);
        } catch(IllegalArgumentException invalid) {
            pages.abandonHomeReservation(sid); cancelProvisional(owner,chamber,sid);
            logger.warn("HOME 入場的 ARMING 列不合法，拒絕啟動：chamber={} session={}",chamber,sid,invalid);
            return null;
        }
        var admitted=journal.admitHome(owner,arming);
        if(!admitted.committed()) {
            logger.warn("HOME 入場的 ARMING 未能 checked 落盤（{}），保留暫占與 reservation，未確認的寫入收斂後才解除：chamber={} session={}",
                    admitted.status(),chamber,sid,admitted.failure().orElse(null));
            // 釋放 START_CHECK 票；reservation 還在，所以暫占記號保留（鎖）。寫出結果不明（pending 保留這次嘗試）時，第一次核對排在退避之後，
            // 不在同一個 tick 立刻重寫；沒有任何殘留（寫出前就被拒）時 finishHomeAbandoned 立即解除。
            cancelProvisional(owner,chamber,sid);
            var backoff=new dev.quantumchamber.collapse.CollapseBackoff();
            backoff.failed(owner.getTicks());
            homeAbandons.put(sid,new HomeAbandon(chamber,backoff));
            return null;
        }
        // T5-3 Step 0（品質 M-1）：checked ARMING 之後先建立 runtime，再釋放暫占與 START_CHECK 票；釋放丟出例外時入場照常（票由原艙票 owner 之後續清），
        // 不會留下沒有 runtime、也沒有收斂者的 ARMING 列。
        var runtime=new HomeRuntime(start.world(),start.controller(),new ChamberFrame(start.endpoint().controllerPos(),start.endpoint().facing()),
                effects,prepared,arming);
        try { if(!finishProvisional(owner,chamber,sid)) { p.valid=false; dev.quantumchamber.collapse.HomeRootTickets.release(owner,sid); } }
        catch(RuntimeException cleanup) {
            p.valid=false;
            logger.warn("HOME 入場已 checked ARMING，但暫占與 START_CHECK 票的釋放丟出例外；票由原艙票 owner 之後續清，入場照常：chamber={} session={}",chamber,sid,cleanup);
        }
        sessions.put(chamber,runtime);
        logger.info("HOME 入場已 checked ARMING（Entry=B）：chamber={} session={} participants={}",chamber,sid,arming.participants().size());
        return runtime;
    }
    private static final HomeSessionRecord.Home UNSELECTED=new HomeSessionRecord.Home(new HomeSessionRecord.Unselected(),-1,-1,-1,List.of(),List.of(),
            Optional.empty(),-1,new HomeSessionRecord.None(),Optional.empty());
    /**
     * 初始 ARMING 沒有 checked 的 HOME 入場：journal 的 current、flushed、pending 與隔離都已沒有這個 SID（未確認的嘗試已收斂）之後，才歸還走廊 reservation 並解除暫占。
     * 還有殘留而 current 不帶這列時（寫出結果不明，pending 保留這次嘗試），依 per-SID 退避（200→12000 ticks）做 checked 寫出：先核對磁碟上的權威，再覆寫可能已落地、
     * 未確認的 ARMING；隔離的列交 recovery 收斂。
     */
    private void finishHomeAbandoned(MinecraftServer owner) {
        if(homeAbandons.isEmpty()) return;
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        var pages=CorridorPageManager.forServer(owner);
        for(var entry : List.copyOf(homeAbandons.entrySet())) {
            var sid=entry.getKey(); var abandon=entry.getValue();
            if(homeResidue(sid) && !journal.allSessions().containsKey(sid) && !journal.quarantinedSessions().contains(sid)
                    && abandon.backoff().ready(owner.getTicks())) {
                try { journal.flush(owner); abandon.backoff().reset(); }
                catch(RuntimeException failure) {
                    abandon.backoff().failed(owner.getTicks());
                    warnHome(sid,"HOME 入場未確認的 ARMING 尚未收斂（checked 寫出失敗），保留暫占與 reservation，退避後重試",failure);
                }
            }
            if(homeResidue(sid) || recovery.convergencePending(sid)) continue;
            try { pages.abandonHomeReservation(sid); }
            catch(RuntimeException failure) { warnHome(sid,"HOME 入場的 reservation 尚未歸還，下一 tick 重試",failure); continue; }
            homeAbandons.remove(sid); homeWarnings.remove(sid);
            try { cancelProvisional(owner,abandon.chamber(),sid); }
            catch(RuntimeException failure) { logger.warn("HOME 入場的暫占取消待重試：session={}",sid,failure); }
            logger.info("HOME 入場未確認的 ARMING 已收斂、沒有殘留，解除 reservation 與暫占：chamber={} session={}",abandon.chamber(),sid);
        }
    }
    private boolean homeResidue(UUID sid) {
        return journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)
                || journal.pendingSessions().containsKey(sid) || journal.quarantinedSessions().contains(sid);
    }
    private List<HomeRuntime> homeRuntimes() {
        return sessions.values().stream().filter(HomeRuntime.class::isInstance).map(HomeRuntime.class::cast).toList();
    }
    /**
     * HOME runtime 的每 tick（比照 legacy 迴圈）：ARMING 在幾何就緒時入場，資格失效即整組返還；SUPERPOSITION 在 B 身分或凍結 cohort 藥效失效時整組返還
     * （牛奶、到期、離線，spec §2「取消與藥效」）。選定之後屬 T5-4／T6，這裡只同步狀態。
     */
    private void tickHomeRuntimes(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var runtime : homeRuntimes()) {
            var sid=runtime.initial.sessionUuid();
            if(pages.operationInProgress(sid)) continue;
            var durable=journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
            if(durable!=null && durable.home().selection() instanceof HomeSessionRecord.Selected) { runtime.state=durable.state(); continue; }
            if(durable!=null && durable.state()==SessionState.RETURNING) runtime.state=SessionState.RETURNING;
            if(runtime.state==SessionState.RETURNING) { retryHomeReturning(owner,runtime,durable); continue; }
            if(pages.homeFailed(sid)) { failHome(runtime,new IllegalStateException("HOME 走廊 space 已失敗")); continue; }
            if(runtime.state==SessionState.SUPERPOSITION) {
                try {
                    if(!entryAuthority(runtime) || !pages.homeCohortHasBuff(sid))
                        throw homeBuffLapsed(runtime) ? new CohortBuffLapsed("凍結 cohort 有成員的 QuantumState 已到期或被解除")
                                : new IllegalStateException("活動中的 B 身分或完整 cohort 效果失效");
                } catch(RuntimeException failure) { failHome(runtime,failure); }
                continue;
            }
            if(runtime.state!=SessionState.ARMING) continue;
            try {
                if(!homeEligible(runtime)) throw homeBuffLapsed(runtime) ? new CohortBuffLapsed("準備期間凍結 cohort 有成員的 QuantumState 已到期或被解除")
                        : new IllegalStateException("準備期間 B 身分／cohort／全員資格改變");
                if(!pages.homeReady(runtime.prepared)) continue;
                enterHome(runtime,pages);
            } catch(IOException | RuntimeException failure) { failHome(runtime,failure); }
        }
    }
    /** RETURNING 的列已 checked 移除、走廊已記完成收據：消費收據（X5，completedReleases 上限 64）、釋放 runtime 與 Entry=B 來源票。 */
    private void completeHomeReturns(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var runtime : homeRuntimes()) {
            var sid=runtime.initial.sessionUuid();
            if(runtime.state!=SessionState.RETURNING || !pages.releaseComplete(sid) || journal.allSessions().containsKey(sid)
                    || journal.allFlushedSessions().containsKey(sid)) continue;
            pages.acknowledgeRelease(sid);
            releaseHomeRuntime(runtime);
            homeReturnRetries.remove(sid); homeWarnings.remove(sid);
            org.slf4j.LoggerFactory.getLogger("quantumchamber").info("HOME 未選定的整組返回 B 完成，解除占用：chamber={} session={}",runtime.initial.chamberUuid(),sid);
        }
    }
    private void releaseHomeRuntime(HomeRuntime runtime) {
        sessions.remove(runtime.initial.chamberUuid(),runtime);
        releaseHomeTicket(runtime.initial.sessionUuid());
    }
    /** 釋放這個 SID 的 Entry=B 來源票（沒有時不做事）。 */
    private void releaseHomeTicket(UUID sid) {
        var ticket=homeTickets.remove(sid);
        if(ticket!=null) ticket.world().getChunkManager().removeTicket(SOURCE_TICKET,ticket.chunk(),2,sid);
    }
    /** Entry=B 的來源票（spec §7 :203：B 的來源票由原 owner 持有；比照 legacy 的 {@code sourceTicket}，票在 B 的 Controller chunk）。 */
    private void homeSourceTicket(HomeRuntime runtime) {
        homeSourceTicket(runtime.initial.sessionUuid(),runtime.entryWorld,runtime.frame.controllerPos());
    }
    /** 同上，給沒有 runtime 的開服接手（{@link HomeResume}）用；依 SID 冪等。 */
    private void homeSourceTicket(UUID sid,ServerWorld entry,BlockPos controllerPos) {
        if(homeTickets.containsKey(sid)) return;
        var chunk=new ChunkPos(controllerPos);
        entry.getChunkManager().addTicket(SOURCE_TICKET,chunk,2,sid);
        homeTickets.put(sid,new HomeTicket(entry,chunk));
    }
    private List<HomeResume> homeResumes() {
        return sessions.values().stream().filter(HomeResume.class::isInstance).map(HomeResume.class::cast).toList();
    }
    /**
     * M7 T5-5（設計稿第 3 節）：開服接手的 HOME 列，第一個 tick 起。
     * <ol>
     * <li>{@code convert} 而 checked 列仍是未選定的 ARMING／SUPERPOSITION：per-SID 退避允許時以既有 {@link #homeReturning} 轉 RETURNING（有 pending 時先做一次 checked 寫出，
     *     {@code commitHomeEdge} 在 pending 非空時拒絕）；失敗只記 WARN（去重），退避後重試，不丟出。</li>
     * <li>還沒有 B 票、exact live B 已出現：取 Entry=B 來源票。exact B 解析不到時不取（托管等待，spec §9.2 :271），本 instance 每個 SID 只記一則 WARN。</li>
     * </ol>
     * 取到票、B 載入、端點 VALID 之前，返還引擎不動任何人（{@code SessionRecoveryManager.recoverHome}）。
     */
    private void resumeHomes(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var resume : homeResumes()) {
            var sid=resume.sid;
            if(resume.convert && journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row && row.home().selection() instanceof HomeSessionRecord.Unselected
                    && (row.state()==SessionState.ARMING || row.state()==SessionState.SUPERPOSITION) && !pages.operationInProgress(sid)) {
                var backoff=homeReturnRetries.computeIfAbsent(sid,ignored -> new dev.quantumchamber.collapse.CollapseBackoff());
                if(backoff.ready(owner.getTicks())) {
                    try {
                        if(!journal.pendingSessions().isEmpty()) journal.flush(owner);
                        homeReturning(sid);
                        backoff.reset(); homeWarnings.remove(sid);
                    } catch(RuntimeException failure) {
                        backoff.failed(owner.getTicks());
                        warnHome(sid,"開服接手的 HOME 返還（RETURNING）尚未 checked，依退避重試",failure);
                    }
                }
            }
            if(homeTickets.containsKey(sid)) continue;
            var entry=exactEntry(resume.entry);
            if(entry!=null) homeSourceTicket(sid,entry,resume.entry.controllerPos());
            else if(!resume.pendingWarned) {
                resume.pendingWarned=true;
                org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("開服接手的 HOME 旅程：exact B 目前不是 live（宇宙未啟用或端點不在），保持托管，不取 B 來源票、不移動任何人：chamber={} session={} entry={}",
                        resume.chamber,sid,resume.entry.worldKey());
            }
        }
    }
    /**
     * M7 T5-5（設計稿 5.1）：開服接手的 HOME 列的收尾。每 tick 依 SID 重讀 journal，不快取列：
     * <ol>
     * <li>列已從 current、flushed、pending 消失，不在隔離，也沒有 reservation：有完成收據就 ack（以 {@code releaseComplete} 守門），釋放 B 票，移除自己；</li>
     * <li>選定的 MEASURED、租約空、current＝flushed、沒有 reservation（DORMANT，有沒有 stop 都一樣）：釋放 B 票，移除自己；有 stop 時之後由 T4 {@code HomeClosure} 獨立 CLOSE；</li>
     * <li>列變成 SEALED（任何原因）：釋放 B 票，移除自己（比照 {@link #releaseOnSealed}；T5 不會產生）。</li>
     * </ol>
     * COLLAPSING 只在第 1 或第 3 款時釋放（已清理的 COLLAPSING 持票，交 T6 的 PREPARE 原地接手）。移除時一併清 per-SID 的退避與 WARN 狀態。
     */
    private void finishResumedHomes(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var resume : homeResumes()) {
            var sid=resume.sid;
            var row=journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
            boolean gone=!homeResidue(sid) && !pages.hasReservation(sid);
            boolean dormant=row!=null && row.state()==SessionState.MEASURED && pages.homeMeasuredReleaseComplete(sid);
            boolean sealed=row!=null && row.state()==SessionState.SEALED;
            if(!gone && !dormant && !sealed) continue;
            if(gone && pages.releaseComplete(sid)) pages.acknowledgeRelease(sid);
            sessions.remove(resume.chamber,resume);
            releaseHomeTicket(sid);
            homeReturnRetries.remove(sid); homeWarnings.remove(sid);
            org.slf4j.LoggerFactory.getLogger("quantumchamber").info("開服接手的 HOME 旅程已收尾（{}），釋放 B 來源票：chamber={} session={}",
                    gone ? "列已移除" : dormant ? "MEASURED DORMANT 持鎖" : "SEALED",resume.chamber,sid);
        }
    }
    /** 只核對凍結的 Entry 權威（exact live B、Controller 實例、PROJECTION、投影端點 VALID），不把供電、cohort 或藥效當成返還目的的身分。 */
    private boolean entryAuthority(HomeRuntime runtime) {
        var endpoint=runtime.initial.entryEndpoint();
        var controller=runtime.controller;
        var pos=endpoint.controllerPos();
        if(exactEntry(endpoint)!=runtime.entryWorld || controller.isRemoved() || controller.getWorld()!=runtime.entryWorld
                || !controller.getPos().equals(pos) || controller.instanceKind()!=ChamberInstanceKind.PROJECTION) return false;
        var chunk=runtime.entryWorld.getChunkManager().getWorldChunk(pos.getX()>>4,pos.getZ()>>4);
        if(chunk==null || chunk.getBlockEntity(pos)!=controller) return false;
        var block=chunk.getBlockState(pos);
        if(!block.isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER) || !block.equals(controller.getCachedState())
                || block.get(ChamberControllerBlock.FACING)!=endpoint.facing()) return false;
        try { return dev.quantumchamber.collapse.HomeEndpointAuthority.inspectEntry(server,endpoint)==dev.quantumchamber.collapse.HomeEndpointAuthority.EntryStatus.VALID; }
        catch(RuntimeException unreadable) { return false; }
    }
    private boolean homeEligible(HomeRuntime runtime) {
        if(!entryAuthority(runtime) || runtime.controller.activationBlocked()
                || !ChamberActivationService.entryPowered(runtime.entryWorld,runtime.frame.controllerPos())) return false;
        var preview=new ChamberActivationService().evaluateEntryReadiness(runtime.entryWorld,runtime.controller);
        return preview.accepted() && new HashSet<>(preview.participantUuids()).equals(homeIds(runtime.initial))
                && runtime.initial.participants().stream().allMatch(person -> {
                    var player=server.getPlayerManager().getPlayer(person.playerUuid());
                    return player!=null && player.getServerWorld()==runtime.entryWorld && !player.isSpectator()
                            && player.hasStatusEffect(ModEffects.QUANTUM_STATE);
                });
    }
    /** B 權威仍有效，且至少一位在線、存活的凍結參與者已沒有 QuantumState（自然到期或被牛奶解除）。 */
    private boolean homeBuffLapsed(HomeRuntime runtime) {
        return entryAuthority(runtime) && runtime.initial.participants().stream().anyMatch(person -> {
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            return player!=null && !player.isRemoved() && player.isAlive() && !player.hasStatusEffect(ModEffects.QUANTUM_STATE);
        });
    }
    /**
     * HOME 入場（plan :317）：checked ARMING → 逐人原生移動 → 重驗位置與 cohort 效果 → 逐人 {@link PlayerCheckpointStore#saveAndVerify} → 重驗位置、exact B 與效果 →
     * checked SUPERPOSITION（restore CAS，operation guard 內）→ 發布 mapping（{@code commitHomeInitial}）。任一步失敗都交 {@link #failHome}：依 ARMING 的進度把已移動的人
     * 原生移回 exact B 的原位，再 checked RETURNING。
     */
    private void enterHome(HomeRuntime runtime,CorridorPageManager pages) throws IOException {
        var target=server.getWorld(SuperpositionWorld.KEY);
        var sid=runtime.initial.sessionUuid();
        var entrance=pages.entrance(sid);
        var view=runtime.prepared.target().instances().getFirst();
        var poses=new LinkedHashMap<UUID,CorridorPageManager.PhysicalPose>();
        var basis=new CorridorBasis(runtime.frame.outwardFacing(),SessionSemantics.LATERAL_BUFF_MAINTAINED);
        for(var person : runtime.initial.participants()) {
            var local=basis.toCorridorPosition(ChamberSpaceCoordinates.localPosition(runtime.frame,person.sourcePosition()));
            var logical=new CorridorPageManager.LogicalPose(local.x,local.y,local.z,
                    basis.toCorridorVector(ChamberSpaceCoordinates.localVector(runtime.frame,person.sourceVelocity())),
                    basis.toCorridorYaw(localYaw(runtime.frame,person.yaw())),person.pitch());
            var pose=pages.toPhysical(view.ref(),logical);
            var player=server.getPlayerManager().getPlayer(person.playerUuid());
            if(player==null || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(entrance),
                    player.getBoundingBox().offset(pose.position().subtract(player.getPos())))) throw new IllegalStateException("HOME 入場完整 bbox 超出入口");
            poses.put(person.playerUuid(),pose);
        }
        for(var move : poses.entrySet()) {
            if(!entryAuthority(runtime)) throw new IllegalStateException("HOME 入場移動前 B 身分失效");
            runtime.effects.verifyCurrent(server);
            var pose=move.getValue();
            runtime.moveAttempted.add(move.getKey());
            if(!transfers.move(server.getPlayerManager().getPlayer(move.getKey()),target,pose.position(),pose.velocity(),pose.yaw(),pose.pitch()))
                throw new IllegalStateException("HOME 部分移動失敗，尚未提交 cohort");
        }
        checkHomeTargets(runtime,target,entrance,poses);
        runtime.effects.verifyCurrent(server);
        for(var person : runtime.initial.participants()) PlayerCheckpointStore.saveAndVerify(server,
                server.getPlayerManager().getPlayer(person.playerUuid()),Optional.empty());
        checkHomeTargets(runtime,target,entrance,poses);
        if(!entryAuthority(runtime)) throw new IllegalStateException("HOME 發布前 B 身分失效");
        runtime.effects.verifyCurrent(server);
        var initial=runtime.initial;
        var active=new HomeSessionRecord(sid,initial.chamberUuid(),initial.rootAnchor(),initial.entryEndpoint(),initial.participants(),initial.spaceLeases(),
                SessionState.SUPERPOSITION,initial.home());
        pages.exclusiveOperation(sid,() -> {
            var result=journal.commitHomeRestore(server,initial,active);
            if(!result.committed()) throw new IllegalStateException("HOME checked SUPERPOSITION 尚未確認："+result.status(),result.failure().orElse(null));
            return null;
        });
        pages.commitHomeInitial(sid,homeIds(initial));
        runtime.state=SessionState.SUPERPOSITION;
    }
    private void checkHomeTargets(HomeRuntime runtime,ServerWorld target,ChamberFrame entrance,Map<UUID,CorridorPageManager.PhysicalPose> poses) {
        for(var person : runtime.initial.participants()) {
            var player=server.getPlayerManager().getPlayer(person.playerUuid()); var pose=poses.get(person.playerUuid());
            if(player==null || player.getServerWorld()!=target || target.getEntity(person.playerUuid())!=player || player.isSpectator()
                    || player.getPos().squaredDistanceTo(pose.position())>=1e-12 || player.getVelocity().squaredDistanceTo(pose.velocity())>=1e-12
                    || Math.abs(MathHelper.wrapDegrees(player.getYaw()-pose.yaw()))>=1e-4 || Math.abs(player.getPitch()-pose.pitch())>=1e-4
                    || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(entrance),player.getBoundingBox()))
                throw new IllegalStateException("HOME 全群 actual world／pose／bbox 尚未確認");
        }
    }
    /**
     * HOME 入場或走廊的失敗（比照 legacy {@code fail}）：ARMING（或從未 checked）時把本 session 移動過、或目前在走廊的參與者原生移回 exact B 的快照原位
     * （last_checkpoint_fails_after_native_moves）；之後 checked RETURNING，只回 B。HOME 的 RestoreEntryEffectOnReturn 固定 0，不還原入場藥效。
     */
    private void failHome(HomeRuntime runtime,Exception failure) {
        var sid=runtime.initial.sessionUuid();
        // T5-4：入場或走廊失敗時，進行中的選門請求一併作廢、只釋放 SELECT_CHECK 票。
        releaseHomeSelect(runtime);
        var durable=journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
        boolean rollback=durable==null || durable.state()==SessionState.ARMING;
        runtime.state=SessionState.RETURNING;
        if(rollback && entryAuthority(runtime)) {
            var corridor=server.getWorld(SuperpositionWorld.KEY);
            for(var person : runtime.initial.participants()) {
                var player=server.getPlayerManager().getPlayer(person.playerUuid());
                if(player!=null && !runtime.moveAttempted.contains(person.playerUuid()) && player.getServerWorld()!=corridor) continue;
                if(player!=null && (!transfers.move(player,runtime.entryWorld,person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch())
                        || !ChamberOccupantService.contains(ChamberGeometry.interiorBox(runtime.frame),player.getBoundingBox())))
                    failure.addSuppressed(new IllegalStateException("rollback pose 尚未確認："+person.playerUuid()));
            }
        } else if(rollback) {
            failure.addSuppressed(new IllegalStateException("B 權威已失效，禁止向保存 pose 移動；保留 pending"));
        }
        try { homeReturning(sid); } catch(RuntimeException persistence) { failure.addSuppressed(persistence); }
        var logger=org.slf4j.LoggerFactory.getLogger("quantumchamber");
        if(failure instanceof CohortBuffLapsed && failure.getSuppressed().length==0)
            logger.info("凍結 cohort 的 QuantumState 已失效，依規則整組返回 B（正常結束，非故障、不退款）：session={}，原因={}",sid,failure.getMessage());
        else logger.warn("HOME 入場交易保留 RETURNING（返回 B）：session={}",sid,failure);
    }
    /** 未選定的 ARMING／SUPERPOSITION 以 checked restore CAS 轉 RETURNING（operation guard 內）；已是 RETURNING、已選定或已移除時不寫。 */
    private void homeReturning(UUID sid) {
        var durable=journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord home ? home : null;
        if(durable==null || durable.state()==SessionState.RETURNING || durable.home().selection() instanceof HomeSessionRecord.Selected) return;
        if(!journal.recoveryWritesSafe()) throw new IllegalStateException("未 checked authority 尚未確認，不可經返還落盤");
        var next=new HomeSessionRecord(sid,durable.chamberUuid(),durable.rootAnchor(),durable.entryEndpoint(),durable.participants(),durable.spaceLeases(),
                SessionState.RETURNING,durable.home());
        CorridorPageManager.forServer(server).exclusiveOperation(sid,() -> {
            var result=journal.commitHomeRestore(server,durable,next);
            if(!result.committed()) throw new IllegalStateException("HOME RETURNING 尚未 checked："+result.status(),result.failure().orElse(null));
            return null;
        });
    }
    /**
     * runtime 已要求返還、checked 列卻仍是 ARMING／SUPERPOSITION（上一次寫不出）：依 per-SID 退避重試。B 的 Controller 沒有供電協調器會再呼叫 returnToOrigin，
     * 所以由這裡重試；有未確認的寫入（pending）時先做一次 checked 寫出讓它收斂。
     */
    private void retryHomeReturning(MinecraftServer owner,HomeRuntime runtime,HomeSessionRecord durable) {
        if(durable==null || durable.state()==SessionState.RETURNING || durable.home().selection() instanceof HomeSessionRecord.Selected) return;
        var sid=runtime.initial.sessionUuid();
        var backoff=homeReturnRetries.computeIfAbsent(sid,ignored -> new dev.quantumchamber.collapse.CollapseBackoff());
        if(!backoff.ready(owner.getTicks())) return;
        try {
            if(!journal.pendingSessions().isEmpty()) journal.flush(owner);
            homeReturning(sid);
            backoff.reset(); homeWarnings.remove(sid);
        } catch(RuntimeException failure) {
            backoff.failed(owner.getTicks());
            warnHome(sid,"HOME 返還的 RETURNING 尚未 checked，依退避重試",failure);
        }
    }
    /**
     * HOME 的一個 tick 段（T5-3 Step 0，品質 M-10）：段內漏接的 RuntimeException 只記 WARN（同段同原因只記一次），不丟出，本 tick 其餘的 legacy、reposition 與 recovery
     * 照常。段內的 HOME 狀態不因此改變；下一 tick 照常重做。
     */
    private void isolateHome(String segment,Runnable body) {
        try {
            body.run();
            homeTickFailures.remove(segment);
        } catch(RuntimeException failure) {
            var key=FailureText.dedupeKey(failure);
            if(key.equals(homeTickFailures.put(segment,key))) return;
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("HOME 的 tick 段（{}）丟出意外例外，已隔離；本 tick 其餘的 legacy、reposition 與 recovery 照常：{}",
                    segment,FailureText.describe(failure),failure);
        }
    }
    private void warnHome(UUID sid,String message,RuntimeException failure) {
        var key=message+"｜"+FailureText.dedupeKey(failure);
        if(key.equals(homeWarnings.put(sid,key))) return;
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("{}：session={}：{}",message,sid,FailureText.describe(failure),failure);
    }
    // ---- M7 HOME（T5-4）：唯一 HOME 選擇（spec §4.2、§7 :198／:200／:201；plan :321、:323） ----

    /**
     * 點 HOME 門（{@code HomeDoorInteraction} 經 {@code ChamberCollapsePort.HomeDoors} 呼叫）：在該 SID 的 session operation guard 內做受理前預檢（不寫 journal），通過才借 A 的
     * SELECT_CHECK 票、記下請求；之後由 {@link #continueHomeSelects} 在票 FULL 的 tick 重驗並提交。受理回 {@link net.minecraft.util.ActionResult#CONSUME}（尚未選定，不宣稱成功），
     * 不受理回 FAIL。互動一律消耗，不落到候選門或一般艙門。
     */
    public net.minecraft.util.ActionResult requestHomeSelect(ServerWorld world,BlockPos pos,ServerPlayerEntity player,CorridorPageManager.HomeDoorHit hit) {
        var owner=world.getServer();
        if(owner!=server || !owner.isOnThread() || !hit.current()) return net.minecraft.util.ActionResult.FAIL;
        var sid=hit.sessionUuid();
        var runtime=homeRuntimes().stream().filter(value -> value.initial.sessionUuid().equals(sid)).findFirst().orElse(null);
        var pages=CorridorPageManager.forServer(owner);
        if(runtime==null || runtime.select!=null || pages.operationInProgress(sid)) return net.minecraft.util.ActionResult.FAIL;
        try {
            return pages.exclusiveOperation(sid,() -> {
                if(!homeSelectValid(owner,runtime,player.getUuid(),player,world,pos,hit,false)) return net.minecraft.util.ActionResult.FAIL;
                var lease=dev.quantumchamber.collapse.HomeRootTickets.acquire(owner,sid,runtime.initial.rootAnchor(),dev.quantumchamber.collapse.HomeRootTickets.Purpose.SELECT_CHECK);
                if(lease.isEmpty()) return net.minecraft.util.ActionResult.FAIL;
                runtime.select=new HomeSelectRequest(player.getUuid(),player,world,pos.toImmutable(),hit,owner.getTicks(),lease.get());
                return net.minecraft.util.ActionResult.CONSUME;
            });
        } catch(RuntimeException refused) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber").debug("HOME 點門不受理：session={}：{}",sid,FailureText.describe(refused));
            return net.minecraft.util.ActionResult.FAIL;
        }
    }

    /**
     * 選門請求的續行（spec §4.2 :100–:103、§7 :198）：受理之後 200 tick 內，A 的 SELECT_CHECK 票 FULL 的那個 tick 進該 SID 的 operation guard，重抓並重驗全部條件
     * （{@link #homeSelectValid}，含 A 此刻的電位），全過才以 restore CAS checked MEASURED＋HOME（first-wins）。請求只用一次；不論結果都只釋放自己的 SELECT_CHECK 票。
     * <p>T5-4 fix1：runtime 已不是 SUPERPOSITION，或 checked 列已不是 SUPERPOSITION／未選定（取消、牛奶、離線、B 失效或另一個已確認狀態）時，請求已不可能提交：立即釋票、
     * 丟請求，不等滿 200 tick（品質 M-6，設計稿 §1.2「票已失效：釋票、丟請求」）。選門 checked 之後的釋票失敗只記 WARN，仍完成 runtime 的 MEASURED 與 INFO（品質 M-5；
     * 票由原艙票 owner 之後續清，比照 {@link #releaseHomeSelect}）。
     */
    private void continueHomeSelects(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var runtime : homeRuntimes()) {
            var request=runtime.select;
            if(request==null) continue;
            var sid=runtime.initial.sessionUuid();
            if(owner.getTicks()-request.requestedAt()>=200) { releaseHomeSelect(runtime); continue; }
            boolean stale;
            try {
                stale=runtime.state!=SessionState.SUPERPOSITION || !(journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row)
                        || row.state()!=SessionState.SUPERPOSITION || !(row.home().selection() instanceof HomeSessionRecord.Unselected);
            } catch(RuntimeException unreadable) { stale=true; }
            if(stale) { releaseHomeSelect(runtime); continue; }
            if(pages.operationInProgress(sid)) continue;
            boolean full;
            try { full=request.lease().full(); }
            catch(RuntimeException unreadable) { releaseHomeSelect(runtime); continue; }
            if(!full) continue;
            runtime.select=null;
            SessionRecoveryState.CommitResult result=null;
            boolean released=false;
            try { result=pages.exclusiveOperation(sid,() -> commitHomeSelect(owner,runtime,request)); }
            catch(RuntimeException failure) { warnHome(sid,"HOME 選門的重驗或提交丟出例外，保持未選定",failure); }
            finally {
                try { dev.quantumchamber.collapse.HomeRootTickets.release(owner,sid); released=true; }
                catch(RuntimeException failure) { warnHome(sid,"HOME 選門之後的 SELECT_CHECK 票釋放失敗（原艙票 owner 之後續清）",failure); }
            }
            if(result!=null && result.committed()) {
                runtime.state=SessionState.MEASURED;
                org.slf4j.LoggerFactory.getLogger("quantumchamber").info("HOME 選門已 checked（MEASURED＋HOME），SELECT_CHECK 票{}：chamber={} session={} player={}",
                        released ? "已釋放" : "釋放失敗、由原艙票 owner 續清",runtime.initial.chamberUuid(),sid,request.player());
            }
        }
    }

    /** guard 內：重驗通過才 checked SUPERPOSITION→MEASURED＋Selected；不通過回 null（不寫）。 */
    private SessionRecoveryState.CommitResult commitHomeSelect(MinecraftServer owner,HomeRuntime runtime,HomeSelectRequest request) {
        if(!homeSelectValid(owner,runtime,request.player(),request.instance(),request.world(),request.pos(),request.hit(),true)) return null;
        var row=(HomeSessionRecord)journal.allFlushedSessions().get(runtime.initial.sessionUuid());
        var h=row.home();
        var selected=new HomeSessionRecord(row.sessionUuid(),row.chamberUuid(),row.rootAnchor(),row.entryEndpoint(),row.participants(),row.spaceLeases(),SessionState.MEASURED,
                new HomeSessionRecord.Home(new HomeSessionRecord.Selected(HomeDoorLayout.key(row.sessionUuid()),request.player(),owner.getOverworld().getTime()),
                        h.committedAtGameTime(),h.passageOpenedAtGameTime(),h.passageExpiresAtGameTime(),h.crossed(),h.recoveryRequired(),h.stopReason(),h.stoppedAtGameTime(),
                        h.closeIntent(),h.sealedReason()));
        return journal.commitHomeRestore(owner,row,selected);
    }

    /**
     * 選門的條件（spec §4.2 :99–:101、plan :321）。受理（{@code full=false}）與 FULL 之後（{@code full=true}）共用；FULL 之後另核 A 此刻的 enabled／身分／結構／電位。
     * <ol>
     * <li>門：current mapping 上的 HOME 門與點門當下的定位完全相同（DoorKey、owner 的 instanceEpoch 與 slot、current epoch），點的格是它的 25 格之一；</li>
     * <li>走廊：沒有新實例在建造或搬移、租約都已實體就緒（使用者 2026-11-25「新走廊建造中不受理」）；</li>
     * <li>原玩家實體：同一個 instance、在走廊、存活、非旁觀、未 removed，bbox 完整在門所在的唯一 current 實例，可以互動這扇門；</li>
     * <li>cohort：凍結 cohort 全員仍有 QuantumState、在走廊（{@code homeCohortHasBuff}），沒有人已 Returned；</li>
     * <li>checked 列 current＝flushed＝SUPERPOSITION、未選定、沒有待確認的 roll-forward、不在隔離；runtime 仍是 SUPERPOSITION；</li>
     * <li>B：exact live、Controller 與投影端點 VALID、此刻供電、封艙；</li>
     * <li>A（只在 FULL 之後）：{@code checkRoot(...,true)} 為 READY。</li>
     * </ol>
     */
    private boolean homeSelectValid(MinecraftServer owner,HomeRuntime runtime,UUID playerUuid,ServerPlayerEntity player,ServerWorld world,BlockPos pos,
            CorridorPageManager.HomeDoorHit hit,boolean full) {
        var sid=runtime.initial.sessionUuid();
        var pages=CorridorPageManager.forServer(owner);
        var door=pages.currentHomeDoor(sid);
        boolean sameDoor=door.isPresent() && door.get().equals(hit) && pages.homeDoorCells(sid,hit.owner()).contains(pos);
        if(!sameDoor || !pages.homeSelectable(sid)) return false;
        var corridor=owner.getWorld(SuperpositionWorld.KEY);
        boolean sameEntity=owner.getPlayerManager().getPlayer(playerUuid)==player && world==corridor && player.getServerWorld()==corridor
                && corridor.getEntity(playerUuid)==player && player.isAlive() && !player.isSpectator() && !player.isRemoved();
        if(!sameEntity || !pages.homePlayerOwner(sid,player).equals(Optional.of(door.get().owner()))
                || pages.homeDoorCells(sid,door.get().owner()).stream().noneMatch(cell -> player.canInteractWithBlockAt(cell,1.0))) return false;
        if(!(journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row) || !row.equals(journal.allSessions().get(sid))
                || row.state()!=SessionState.SUPERPOSITION || !(row.home().selection() instanceof HomeSessionRecord.Unselected)
                || journal.rollForwardPending(sid) || journal.quarantinedSessions().contains(sid) || runtime.state!=SessionState.SUPERPOSITION
                || row.participants().stream().noneMatch(person -> person.playerUuid().equals(playerUuid))
                || row.participants().stream().anyMatch(SessionRecoveryRecord.Participant::returned) || !pages.homeCohortHasBuff(sid)) return false;
        if(!entryAuthority(runtime) || !ChamberActivationService.entryPowered(runtime.entryWorld,runtime.frame.controllerPos()) || !homeEntrySealed(runtime)) return false;
        return !full || dev.quantumchamber.collapse.HomeEndpointAuthority.checkRoot(owner,runtime.initial.rootAnchor(),true)
                ==dev.quantumchamber.collapse.HomeEndpointAuthority.RootStatus.READY;
    }

    /** B 封艙：exact B 的艙體結構完整、門關上（{@link ChamberDetector} 的 valid 與 sealed；不看 cohort，cohort 在走廊）。 */
    private boolean homeEntrySealed(HomeRuntime runtime) {
        var structure=new ChamberDetector().validate(new WorldChamberBlockView(runtime.entryWorld),runtime.frame);
        return structure.valid() && structure.sealed();
    }

    /** 作廢這個 runtime 的選門請求並只釋放它的 SELECT_CHECK 票（冪等）。 */
    private void releaseHomeSelect(HomeRuntime runtime) {
        if(runtime.select==null) return;
        runtime.select=null;
        try { dev.quantumchamber.collapse.HomeRootTickets.release(server,runtime.initial.sessionUuid()); }
        catch(RuntimeException failure) { warnHome(runtime.initial.sessionUuid(),"HOME 選門請求的 SELECT_CHECK 票釋放失敗（原艙票 owner 之後續清）",failure); }
    }

    /** spec §10 :311：{@code /reload} 時取消所有未選定的選門請求、釋放票（已 checked 的 HOME 不受影響）。 */
    private void cancelHomeSelects(MinecraftServer owner) {
        if(owner!=server || !owner.isOnThread()) return;
        for(var runtime : homeRuntimes()) releaseHomeSelect(runtime);
    }

    /** 選定之後的保留式清理已完成、列是 MEASURED DORMANT：釋放 HOME runtime 與 Entry=B 來源票（比照 legacy DORMANT）；艙仍由 journal 持鎖（spec §6）。 */
    private void releaseDormantHomeRuntimes(MinecraftServer owner) {
        var pages=CorridorPageManager.forServer(owner);
        for(var runtime : homeRuntimes()) {
            var sid=runtime.initial.sessionUuid();
            // 選定之後的列已由回程終局 owner（HomeClosure）checked 移除（例如 COLLAPSING 清理之後的原艙停止）：runtime 不再有列可跟隨，一併釋放。
            boolean removed=runtime.state!=SessionState.ARMING && runtime.state!=SessionState.SUPERPOSITION && runtime.state!=SessionState.RETURNING
                    && !homeResidue(sid) && !pages.hasReservation(sid);
            if(!removed && (!(journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row) || row.state()!=SessionState.MEASURED
                    || !pages.homeMeasuredReleaseComplete(sid))) continue;
            releaseHomeSelect(runtime);
            releaseHomeRuntime(runtime);
            homeReturnRetries.remove(sid); homeWarnings.remove(sid);
            org.slf4j.LoggerFactory.getLogger("quantumchamber").info("HOME 選定之後全員已返回 B、走廊已清理，MEASURED DORMANT 持鎖、釋放 runtime 與 B 來源票：chamber={} session={}",
                    runtime.initial.chamberUuid(),sid);
        }
    }

    /**
     * C4：未選定的走廊取消沿用 gateway 的 {@link #returnToOrigin}，B（PROJECTION）的 Controller 走這個 HOME 分支：以投影索引辨識 exact B 的 Chamber，
     * 未選定的 ARMING／SUPERPOSITION checked 轉 RETURNING（只回 B）；回傳是否已沒有這座艙的旅程。已選定的 HOME 列這裡不動（選定之後 B 端的操作屬 T6）。
     */
    private boolean returnToEntry(ServerWorld world,ChamberControllerBlockEntity controller) {
        var id=ChamberCollapsePort.projections().entryChamber(world,controller);
        if(id.isEmpty()) return false;
        var chamber=id.get();
        HomeSessionRecord row=null;
        for(var value : journal.allFlushedSessions().values()) if(value instanceof HomeSessionRecord home && home.chamberUuid().equals(chamber)
                && home.entryEndpoint().worldKey().equals(world.getRegistryKey().getValue()) && home.entryEndpoint().controllerPos().equals(controller.getPos())) row=home;
        var runtime=sessions.get(chamber) instanceof HomeRuntime home ? home : null;
        if(row==null && runtime==null) return !globalChamberBusy(server,chamber);
        if(row==null || row.home().selection() instanceof HomeSessionRecord.Selected
                || CorridorPageManager.forServer(server).operationInProgress(row.sessionUuid())) return false;
        if(row.state()==SessionState.ARMING || row.state()==SessionState.SUPERPOSITION) {
            try { homeReturning(row.sessionUuid()); }
            catch(RuntimeException failure) { warnHome(row.sessionUuid(),"HOME 走廊取消的 RETURNING 尚未 checked，稍後重試",failure); }
            if(runtime!=null) runtime.state=SessionState.RETURNING;
        }
        return false;
    }
    private static Set<UUID> homeIds(HomeSessionRecord record) {
        return record.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).collect(java.util.stream.Collectors.toSet());
    }
    @Override public StartResult start(ServerWorld source,ChamberControllerBlockEntity controller,List<UUID> requested) {
        var owner=source.getServer();
        var chamber=controller.chamberUuid();
        var id=UUID.randomUUID();
        if(chamber==null || !claimProvisional(owner,chamber,id)) return StartResult.REJECTED;
        try { return startClaimed(source,controller,requested,chamber,id); }
        finally { cancelProvisional(owner,chamber,id); }
    }
    private StartResult startClaimed(ServerWorld source,ChamberControllerBlockEntity controller,List<UUID> requested,UUID chamber,UUID id) {
        // M7 spec v1.6 A：latch 為真（取消或回家之後尚未有玩家親手關門）時，與供電協調同樣拒絕。
        if(requested.isEmpty() || requested.size()>25
                || new HashSet<>(requested).size()!=requested.size() || controller.activationBlocked() || controller.candidateRetryRequiresLow()
                || controller.rearmRequiresPlayerClose()) return StartResult.REJECTED;
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
        var effects=new QuantumEffectTransaction(requested.stream().map(playerId -> server.getPlayerManager().getPlayer(playerId)).toList());
        var pages=CorridorPageManager.forServer(server);
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
        if (!ownsProvisional(server,chamber,id)) throw new IllegalStateException("入場前的暫占已失效");
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
            var runtime=activeSessions().stream().filter(value -> value.initial.sessionUuid().equals(sid)).findFirst().orElse(null);
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
        for (var entry:List.copyOf(sessions.entrySet())) if(entry.getValue() instanceof Provisional p && (!p.valid || owner.getTicks()-p.claimedAt>=200))
            cancelForEvent(owner,entry.getKey(),p);
        // M7 HOME（T5-2）：START_CHECK 的續行與 HOME runtime；legacy 的迴圈只看 SuperpositionSession。
        // T5-3 Step 0（品質 M-10）：HOME 各段各自隔離意外例外（isolateHome）。不隔離時例外會從 END_SERVER_TICK 丟出：本 tick 其後的 legacy、reposition、recovery
        // 與之後註冊的 tick owner 都被跳過，原版的 server 迴圈還會把它當成未預期例外而停服。
        // M7 T5-5：開服接手的 HOME 列排在最前面（同一個 END tick 的 recovery 之前先轉 RETURNING，JOIN 的凍結期內就已是返還中）。
        isolateHome("resumeHomes",() -> resumeHomes(owner));
        isolateHome("continueHomeStarts",() -> continueHomeStarts(owner));
        // M7 T5-4：選門請求的 SELECT_CHECK 續行排在 tickHomeRuntimes 之前：FULL 當下的重驗看的是 gateway 尚未處理的狀態（例如藥效剛失效），不能依 gateway 已取消才拒絕。
        isolateHome("continueHomeSelects",() -> continueHomeSelects(owner));
        isolateHome("tickHomeRuntimes",() -> tickHomeRuntimes(owner));
        for(var runtime : activeSessions()) {
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
        isolateHome("finishHomeAbandoned",() -> finishHomeAbandoned(owner));
        isolateHome("completeHomeReturns",() -> completeHomeReturns(owner));
        isolateHome("finishResumedHomes",() -> finishResumedHomes(owner));
        isolateHome("releaseDormantHomeRuntimes",() -> releaseDormantHomeRuntimes(owner));
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
        var runtime=runtime(record.chamberUuid());
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
        // M7 HOME（T5-2，controller C4）：B 的 Controller（PROJECTION）走 HOME 分支；legacy 路徑不變。
        if(controller.instanceKind()==ChamberInstanceKind.PROJECTION) return returnToEntry(source,controller);
        var runtime=runtime(controller.chamberUuid()); var durable=record(controller.chamberUuid());
        var known=tickets.entrySet().stream().filter(entry -> entry.getValue().record().chamberUuid().equals(controller.chamberUuid()))
                .findFirst().orElse(null);
        if(runtime==null && durable==null && known==null) return !globalChamberBusy(source.getServer(),controller.chamberUuid());
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
        sessions.values().removeIf(value -> value instanceof SuperpositionSession runtime && runtime.initial.sessionUuid().equals(sessionUuid));
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
    private void cancelForEvent(MinecraftServer owner,UUID chamber,Provisional token) {
        try { cancelProvisional(owner,chamber,token.sid); }
        catch(RuntimeException failure) {
            // root ticket owner 保留原生 refs，該 SID 下次再試；不得中斷其他暫占。
            if (!token.warned) {
                token.warned=true;
                org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("暫占取消待重試：SID={}",token.sid,failure);
            }
        }
    }
    private void cancelAllProvisional(MinecraftServer owner) {
        if (owner!=server) return;
        for (var entry:List.copyOf(sessions.entrySet())) if(entry.getValue() instanceof Provisional p)
            cancelForEvent(owner,entry.getKey(),p);
    }
    private void stopping(MinecraftServer owner) {
        // 前置 authority attach 失敗時仍會收到 STOPPING；此時沒有本 manager 的資源。
        if(server==null) return;
        if(owner!=server || !owner.isOnThread()) throw new IllegalStateException("session cleanup 必須在同一 server thread");
        // 釋放已持有的來源票不讀寫 journal，不能由不健康或部分初始化的 journal 中斷關閉。
        for(var entry : tickets.entrySet()) entry.getValue().world().getChunkManager()
                .removeTicket(SOURCE_TICKET,entry.getValue().chunk(),2,entry.getKey());
        tickets.clear();
        for(var entry : homeTickets.entrySet()) entry.getValue().world().getChunkManager()
                .removeTicket(SOURCE_TICKET,entry.getValue().chunk(),2,entry.getKey());
        homeTickets.clear();
    }
    private void detach(MinecraftServer owner) {
        if(server!=owner) return;
        if(!owner.isOnThread()) throw new IllegalStateException("session detach 必須在同一 server thread");
        if(recovery!=null) recovery.detach(owner);
        recovery=null; reposition=null; sessions.clear(); tickets.clear(); journal=null; server=null; lastTick=Integer.MIN_VALUE; checkpointWarning=null;
        abandonWarnings.clear(); abandonPending.clear(); retiredInitial.clear();
        homeTickets.clear(); homeAbandons.clear(); homeReturnRetries.clear(); homeWarnings.clear(); homeTickFailures.clear();
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
