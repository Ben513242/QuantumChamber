package dev.quantumchamber.persistence;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Stream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;

/** 使用原生 manager 的生命週期，但讀寫均經過可觀測的 journal 健康閘門。 */
public final class SessionRecoveryState extends PersistentState {
    public static final String STATE_ID = "quantumchamber_sessions";
    @FunctionalInterface interface JournalReadback { NbtCompound read(Path path) throws IOException; }
    private final JournalReadback readback;
    public SessionRecoveryState() { this(SessionJournalStore::read); }
    SessionRecoveryState(JournalReadback readback) { this.readback = java.util.Objects.requireNonNull(readback); }
    private final Map<UUID, SessionRecoveryRecord> records = new LinkedHashMap<>();
    // 僅存活於本 process；remove 與空 journal flush 都不能抹去同 UUID 的最後 authority。
    private final Map<UUID, SessionRecoveryRecord> authorityHistory = new LinkedHashMap<>();
    // 最後一份經 strict load／readback 承認的 authority（一般 remove 後仍保留）；只供 restoreCheckedAuthority 還原 history
    // （abortUnchecked、convergeQuarantined、commitRestore 與 terminate 的失敗還原）。checked 終局讀回確認後才修剪該 session。
    private final Map<UUID, SessionRecoveryRecord> checkedAuthority = new LinkedHashMap<>();
    // CAS 還原失敗或 owner 未能還原的 session：未 checked current 永不寫出，寫入時只用上一份 flushed authority；由 convergeQuarantined 收斂。
    private final java.util.Set<UUID> quarantined = new HashSet<>();
    private Map<UUID, SessionRecoveryRecord> flushed = Map.of();
    // Schema 3 envelope 本身是不可撤銷的初始化證據；不新增 NBT 欄位。
    private boolean candidateInitialized;
    // spec §5 的兩個旗標，不可混用。m5Evidence 只決定寫出的 schema：讀到 schema 4 或本 instance 初始化成功時為真，之後不變回假。
    // m5ReadyThisInstance 只決定能不能提交新的 COLLAPSING：只有本 instance 的 M5 authority 初始化成功才為真，讀到 schema 4 不算。
    private boolean m5Evidence;
    private boolean m5ReadyThisInstance;
    // 已 checked 終局（讀回確認不存在）的 session：本 process 內 put 一律拒絕同一個 UUID。
    private final java.util.Set<UUID> terminated = new HashSet<>();
    // roll-forward 類寫入（spec §6）尚未經 checked flush 讀回確認的 session；collapse view 只在這時採用 current。
    private final java.util.Set<UUID> rollForwardPending = new HashSet<>();
    // 「讀回已確認之後才出現的例外」最近一次 WARN 的去重鍵（FailureText.dedupeKey），以 session 為單位；終局成功時移除。
    private final Map<UUID, String> confirmedFailureWarnings = new HashMap<>();
    private String loadError;
    private MinecraftServer owner;

    public static SessionRecoveryState get(MinecraftServer server) {
        requireServerThread(server);
        Path path = path(server);
        var manager = server.getOverworld().getPersistentStateManager();
        // 原生 manager 只負責快取；首次 callback 仍從正式檔案 strict read，避免遷移或預設 compound 掩蓋損壞。
        var type = new PersistentState.Type<>(SessionRecoveryState::new,
                (NbtCompound ignored, RegistryWrapper.WrapperLookup lookup) -> load(path), DataFixTypes.LEVEL);
        SessionRecoveryState state = manager.get(type, STATE_ID);
        if (state == null) {
            // 原生讀檔失敗可能回 null；只有 checked read 證明檔案不存在才允許初建。
            state = load(path);
            manager.set(STATE_ID, state);
        }
        state.owner = server;
        return state;
    }

    static SessionRecoveryState load(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return new SessionRecoveryState();
        } catch (IOException | RuntimeException exception) {
            return failed("無法確認 journal 路徑：" + exception);
        }
        try {
            return fromNbt(SessionJournalStore.read(path).getCompound("data"));
        } catch (IOException | RuntimeException exception) {
            // 已見到檔案後消失也屬讀取失敗，不退回空 journal。
            return failed("journal 無法讀取：" + exception);
        }
    }

    public static SessionRecoveryState fromNbt(NbtCompound nbt) {
        try {
            JournalNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
            int schema=nbt.getInt("SchemaVersion");
            if (schema < 1 || schema > 4) throw new IllegalArgumentException("不支援 journal schema");
            if (schema >= 3) JournalNbt.keys(nbt, "SchemaVersion", "Records");
            var state = new SessionRecoveryState();
            // schema 4 只會出現在 candidate 已初始化的 journal；它同時是 M5 authority 已初始化的證據，但不代表本 instance 已就緒。
            state.candidateInitialized = schema >= 3;
            state.m5Evidence = schema == 4;
            for (var raw : JournalNbt.compounds(nbt, "Records", schema)) {
                var record = SessionRecoveryRecord.fromNbt((NbtCompound) raw,schema);
                if (state.records.putIfAbsent(record.sessionUuid(), record) != null) throw new IllegalArgumentException("session UUID 重複");
            }
            validateOwnership(state.records);
            state.flushed = Map.copyOf(state.records);
            state.authorityHistory.putAll(state.flushed);
            state.checkedAuthority.putAll(state.flushed);
            return state;
        } catch (RuntimeException exception) {
            return failed("journal schema 無法解析：" + exception);
        }
    }

    public void requireHealthy() {
        if (loadError != null) throw new IllegalStateException("拒絕使用損壞的 " + STATE_ID + "：" + loadError);
    }
    public Map<UUID,SessionRecoveryRecord> records() { requireHealthy(); return Map.copyOf(records); }
    public Map<UUID,SessionRecoveryRecord> flushedRecords() { requireHealthy(); return flushed; }
    public boolean candidateInitialized() { requireHealthy(); return candidateInitialized; }
    /** M5 authority 已初始化的證據（決定寫出 schema 4）。在本 instance 呼叫 {@link #markCollapseAuthoritiesInitialized} 之前，它等於「flushed envelope 已是 schema 4」。 */
    public boolean m5Evidence() { requireHealthy(); return m5Evidence; }
    /** 提交新的 COLLAPSING（MEASURED→COLLAPSING、SEALED→COLLAPSING）的閘門；讀到 schema 4 不會讓它變真。 */
    public boolean m5ReadyThisInstance() { requireHealthy(); return m5ReadyThisInstance; }
    /**
     * 本 server instance 的 M5 authority 初始化成功（F2b）時呼叫：m5Evidence 與 m5ReadyThisInstance 都設為真。
     * <ul>
     * <li>不可撤銷：沒有對應的取消 API，兩個旗標在本 instance 內都不會再變回假。</li>
     * <li>只能在 allocation 與 projection 兩個 ledger 都已建立（或確認存在）並 strict 驗證成功之後呼叫；任何原因的初始化失敗都不得呼叫
     *     （spec §6：schema 4 envelope 本身就是兩個 ledger 已建立的證據）。</li>
     * <li>呼叫之後 {@link #m5Evidence()} 不再等於「flushed envelope 是否為 schema 4」；需要後者的判斷必須在呼叫之前完成。</li>
     * </ul>
     * 本身不標 dirty：candidate 已初始化的 journal 在之後第一次 checked 寫入時升為 schema 4（spec §12）。
     */
    public void markCollapseAuthoritiesInitialized() {
        requireMutationThread();
        requireHealthy();
        m5Evidence = true; m5ReadyThisInstance = true;
    }
    /**
     * recovery 寫入是否安全：非隔離 session 的 current 都不得含未 checked 的 authority——candidate context／ledger／selection，
     * 以及 M5 的 restore 類狀態邊（見 {@link #uncheckedCandidateSessions}）。
     * owner 失敗即 CAS 還原或隔離，recovery tick 也會隔離任何殘留，因此正常只剩 checked 提交本身同步的 put→flush 窗口會回 false。
     * M5 的 roll-forward 類內容（返還與清 lease 進度、Crossed、進入 SEALED）不算未 checked：它們本來就要由之後的任何寫出帶出去。
     */
    public boolean recoveryWritesSafe() { return uncheckedCandidateSessions().isEmpty(); }
    /**
     * 非隔離、current 含未 checked authority 的 session。除了 candidate 三欄位與 flushed 不同之外，也包含 current 相對 flushed 跨過
     * M5 restore 類狀態邊（MEASURED→COLLAPSING、COLLAPSING→PASSAGE_READY、SEALED→COLLAPSING）的 session：這類內容只能經
     * {@link #commitRestore} 的同步 put→flush 承認，失敗即還原；殘留時由 recovery tick 隔離並收斂回 flushed。
     */
    public java.util.Set<UUID> uncheckedCandidateSessions() {
        requireHealthy();
        var unchecked=new java.util.LinkedHashSet<UUID>();
        for(var current : records.values()) if(!quarantined.contains(current.sessionUuid()) && !authorityChecked(current)) unchecked.add(current.sessionUuid());
        return java.util.Set.copyOf(unchecked);
    }
    private boolean authorityChecked(SessionRecoveryRecord current) {
        if(!candidateChecked(current)) return false;
        if(!current.state().collapseState()) return true;
        var durable=flushed.get(current.sessionUuid());
        return durable!=null && !restoreEdge(durable.state(),current.state());
    }
    private boolean candidateChecked(SessionRecoveryRecord current) {
        if(current.candidateContext().isEmpty()) return true;
        var previous=flushed.get(current.sessionUuid());
        return previous!=null && previous.candidateContext().equals(current.candidateContext())
                && previous.candidateLedger().equals(current.candidateLedger())
                && previous.candidateSelection().equals(current.candidateSelection());
    }
    /**
     * checked 提交（put→flush→readback）失敗後由 candidate authority owner 呼叫。CAS：只有 current 仍 exact 等於本次提交的 next，
     * 而且 next 含尚未 checked 的 candidate authority 時，才把 current 還原為上一份 flushed record（沒有時移除），
     * authority history 還原為最後 checked authority；之後任何 flush、autosave 或 stop 都無法承認未 checked 的 candidate／selection。
     * next 的 candidate authority 已 checked（已落盤、legacy，或與 flushed 只差返還／lease 等進度）時不倒退。
     * CAS 不符且 current 仍含未 checked authority 時不猜測覆寫，改為隔離該 session（只影響它，其他 recovery 照常）。
     * @apiNote 只回退 candidate context／ledger／selection 尚未 checked 的寫入。candidate 三欄位不變的寫入（M5 的 COLLAPSING、
     *          READY、SEALED→COLLAPSING 與終局等 restore 類寫入）一律視為已 checked 而不回退；這類寫入只能經 {@link #commitRestore}
     *          與 {@link #terminate}，由它們自己的 CAS 還原。
     * @return 該 session 已無未 checked candidate authority 時為 true；隔離時為 false
     */
    public boolean abortUnchecked(SessionRecoveryRecord expected) {
        requireMutationThread();
        requireHealthy();
        var sid=expected.sessionUuid(); var current=records.get(sid);
        if(!expected.equals(current)) {
            if(current==null || candidateChecked(current)) return true;
            quarantine(sid);
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("candidate authority 還原 CAS 不符，隔離 session={}；之後只寫出上一份 flushed authority，由 recovery 收斂",sid);
            return false;
        }
        if(candidateChecked(expected)) return true;
        restoreCheckedAuthority(sid);
        return true;
    }
    /**
     * 隔離 session：它的 current 不再被寫出（所有寫出只帶上一份 flushed authority），put／remove 一律拒絕，
     * 直到 {@link #convergeQuarantined} 以最後 checked authority 取代 current。owner 無法還原時，以此 fail-closed 停住該 session。
     * <p>拒絕隔離：session 有尚未確認的 roll-forward（{@link #rollForwardPending}），而 current 的 authority 已 checked
     * （與 flushed 只差 roll-forward 進度，沒有未 checked 的 candidate 或 restore 殘留）。收斂會以 flushed 取代 current，
     * 等於把可能已落地的事實（例如 SEALED(TRANSFER_RECOVERY_REQUIRED)）寫回成舊狀態，違反 spec §6 與 §10.3。這時 caller 不隔離，
     * 只重試寫出（任何一次 checked flush 都會帶著 current）。roll-forward 旗標與未 checked 殘留同時存在時（只有雙重故障才會發生）照常隔離。
     * @return 這次才進入隔離時為 true；已隔離、沒有這個 session，或因上述 roll-forward 拒絕時為 false
     */
    public boolean quarantine(UUID sessionUuid) {
        requireMutationThread();
        requireHealthy();
        if(!records.containsKey(sessionUuid) && !flushed.containsKey(sessionUuid)) return false;
        if(quarantined.contains(sessionUuid)) return false;
        var current=records.get(sessionUuid);
        if(current!=null && rollForwardPending.contains(sessionUuid) && authorityChecked(current)) return false;
        return quarantined.add(sessionUuid);
    }
    public java.util.Set<UUID> quarantinedSessions() { requireHealthy(); return java.util.Set.copyOf(quarantined); }
    /**
     * 隔離 session 的收斂（H3 q3／q4）。隔離 session 的 current 從未被寫出或承認，所以不需 CAS：直接以最後 checked authority 取代，
     * authority history 也還原為最後 checked authority。flushed 為 ARMING／SUPERPOSITION 時推進為 RETURNING（candidate context、ledger、
     * selection 原樣保留，只改狀態），RETURNING／MEASURED 原樣保留；沒有 flushed authority 時移除。取代後已沒有未 checked authority，
     * 解除隔離並保持 dirty；caller 必須接著 checked flush，寫出成功才算收斂，同時覆寫磁碟上可能已落地但未確認的內容。
     * 只由同 package 的 {@link SessionRecoveryManager} 呼叫，由它負責 checked flush 與確認。
     * @return 收斂目標；空值代表移除
     */
    java.util.Optional<SessionRecoveryRecord> convergeQuarantined(UUID sessionUuid) {
        requireMutationThread();
        requireHealthy();
        if(!quarantined.contains(sessionUuid)) throw new IllegalStateException("只有隔離中的 session 可以收斂");
        var durable=flushed.get(sessionUuid);
        var target=durable==null ? null : convergenceTarget(durable);
        var candidate=new LinkedHashMap<>(records);
        if(target==null) candidate.remove(sessionUuid); else candidate.put(sessionUuid,target);
        validateOwnership(candidate);
        restoreCheckedAuthority(sessionUuid);
        if(target!=null && !target.equals(durable)) { records.put(sessionUuid,target); authorityHistory.put(sessionUuid,target); }
        quarantined.remove(sessionUuid);
        return java.util.Optional.ofNullable(target);
    }
    /**
     * 收斂只把尚未測量的 authority 推進到 RETURNING；已在返還或保留 receipt 的狀態原樣保留。新增狀態時必須在此明確決定。
     * M5 的三個塌縮狀態也保留收據，與 MEASURED 相同：收斂回上一份 flushed 的狀態，後續由塌縮流程依 §12 續做。
     */
    private static SessionRecoveryRecord convergenceTarget(SessionRecoveryRecord durable) {
        return switch(durable.state()) {
            case ARMING, SUPERPOSITION -> durable.withProgress(durable.participants(),durable.spaceLeases(),
                    dev.quantumchamber.superposition.SessionState.RETURNING,durable.restoreEntryEffectOnReturn());
            case RETURNING, MEASURED -> durable;
            case COLLAPSING, PASSAGE_READY, SEALED -> durable;
        };
    }
    /** 不檢查 CAS 的還原核心：current 還原為上一份 flushed record（沒有時移除），authority history 還原為最後 checked authority，並保持 dirty。 */
    private void restoreCheckedAuthority(UUID sid) {
        var durable=flushed.get(sid);
        if(durable==null) records.remove(sid); else records.put(sid,durable);
        var checked=checkedAuthority.get(sid);
        if(checked==null) authorityHistory.remove(sid); else authorityHistory.put(sid,checked);
        rollForwardPending.remove(sid);
        markDirty();
    }

    // ---- M5 塌縮（spec §5、§6） ----

    /** spec §5 的 receiptRetained：MEASURED、COLLAPSING、PASSAGE_READY、SEALED 都保留 M4 收據，不能經一般 remove 消耗。 */
    public static boolean receiptRetained(SessionState state) { return state.retainsReceipt(); }

    /**
     * spec §5 的單一佔用謂詞：record 是否仍佔用這位玩家（跨 session 的玩家唯一性只看它）。
     * ARMING、SUPERPOSITION、RETURNING 與非 DORMANT 的 MEASURED 佔用全部參與者；DORMANT 不佔用任何人；
     * 三個塌縮狀態只佔用尚未返還的參與者（PASSAGE_READY 與 SEALED 全員已返還，所以不佔用任何人）。Chamber 的唯一性不受影響。
     */
    public static boolean occupies(SessionRecoveryRecord record, UUID player) {
        var person=record.participants().stream().filter(participant -> participant.playerUuid().equals(player)).findFirst();
        if(person.isEmpty()) return false;
        return switch(record.state()) {
            case ARMING, SUPERPOSITION, RETURNING -> true;
            case MEASURED -> !dormant(record);
            case COLLAPSING, PASSAGE_READY, SEALED -> !person.orElseThrow().returned();
        };
    }

    /**
     * spec §6 的 collapse view：M5 對單一 session 的判斷以 flushed record 為準；唯一的例外是 roll-forward 尚未確認
     * （SEALED、Crossed、返還與清 lease 的進度），這時以 current 為準。隔離中的 session 的 current 不採用。
     */
    public Optional<SessionRecoveryRecord> collapseView(UUID session) {
        requireHealthy();
        return Optional.ofNullable(rollForwardPending.contains(session) && !quarantined.contains(session) ? records.get(session) : flushed.get(session));
    }

    /** 該 session 是否有尚未經 checked flush 讀回確認的 roll-forward 寫入；成立期間塌縮流程只重試寫出（spec §7）。 */
    public boolean rollForwardPending(UUID session) { requireHealthy(); return rollForwardPending.contains(session); }

    /**
     * checked 提交的結果。failure 只在不是 COMMITTED 時存在：REJECTED 時是拒絕原因，其餘是寫出失敗的 root cause。
     * journal 不記這些失敗；caller 依 (sid, 提交的狀態邊, {@link FailureText#dedupeKey}) 去重後記 WARN。
     */
    public record CommitResult(Status status, Optional<RuntimeException> failure) {
        public enum Status {
            /** put→flush→讀回確認 flushed exact 等於 next。 */
            COMMITTED,
            /** 寫入前就拒絕（CAS 不符、隔離、已終局、狀態邊不屬於這個提交種類、M5 尚未就緒）；journal 沒有改變。 */
            REJECTED,
            /**
             * current 帶著尚未確認的 roll-forward 內容並保持 dirty，collapse view 採用它；之後任何寫出都帶著它，caller 只重試寫出。
             * roll-forward 寫出失敗時是 next；restore 寫出失敗而 CAS 不符、current 卻是已 checked 的 roll-forward 內容時是那份 current
             * （不隔離，見 {@link #quarantine}）。
             */
            ROLL_FORWARD_PENDING,
            /** restore 寫出失敗、CAS 成立：current 已還原為 expected（上一份 flushed）並保持 dirty，下一次寫出覆寫磁碟。 */
            RESTORED,
            /** restore 寫出失敗、CAS 不符：已隔離該 session，之後只寫出它的 flushed authority，由 recovery 收斂。 */
            QUARANTINED
        }
        public CommitResult {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(failure, "failure");
            if((status==Status.COMMITTED)==failure.isPresent()) throw new IllegalArgumentException("只有 COMMITTED 沒有 failure");
        }
        static CommitResult of(Status status, RuntimeException failure) { return new CommitResult(status, Optional.of(failure)); }
        public boolean committed() { return status==Status.COMMITTED; }
    }

    /**
     * checked 終局的結果；failure 只在不是 REMOVED 時存在。journal 不記這些失敗（與 {@link CommitResult} 一致）；
     * caller 依 (sid, origin, {@link FailureText#dedupeKey}) 去重後記 WARN。
     */
    public record TerminalResult(Status status, Optional<RuntimeException> failure) {
        public enum Status {
            /** 移除、flush、讀回確認不存在；已修剪 authority history 並留下 tombstone。 */
            REMOVED,
            /** 起點不合法、CAS 不符、隔離或已終局；journal 沒有改變。 */
            REJECTED,
            /** flush 或讀回失敗、CAS 成立：expected 已放回並保持 dirty，下一次寫出覆寫磁碟。不改門。 */
            RESTORED,
            /** flush 或讀回失敗、CAS 不符：已隔離該 session，由 recovery 收斂。 */
            QUARANTINED,
            /**
             * flush 或讀回失敗、CAS 不符，而 current 是已 checked、尚未確認的 roll-forward 內容（例如 flush 期間的重入寫入）：
             * 不隔離、不丟棄它（見 {@link #quarantine}），沒有終局；caller 只重試寫出，確認之後再評估終局。
             */
            ROLL_FORWARD_PENDING
        }
        public TerminalResult {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(failure, "failure");
            if((status==Status.REMOVED)==failure.isPresent()) throw new IllegalArgumentException("只有 REMOVED 沒有 failure");
        }
        static TerminalResult of(Status status, RuntimeException failure) { return new TerminalResult(status, Optional.of(failure)); }
        public boolean removed() { return status==Status.REMOVED; }
    }

    /**
     * checked 終局唯一接受的四種起點（spec §5）。journal 只檢查能由 record 推導的條件（{@link #accepts}）；
     * 「現在處於哪個推導階段」「參與者是否關門」「通道是否結束」由 caller 判斷並負責。
     */
    public enum TerminalOrigin {
        /** PASSAGE_READY 的通道結束（§10.6）。 */
        PASSAGE_ENDED,
        /**
         * Destination=SOURCE 的 COLLAPSING 進入 PREPARE（§7.4）。journal 只能檢查 settled（全員已返還、lease 已清空）；
         * 只能在 PREPARE 階段呼叫（RELEASE_GEOMETRY 等前置階段都已完成），這個前提由 caller 負責。
         */
        SOURCE_FAST_PATH,
        /**
         * Destination=UNIVERSE 的 COLLAPSING，PREPARE 期間參與者關門（§7.3）。journal 只能檢查 settled；
         * 只能在 PREPARE 階段呼叫，也就是 RESOLVE 已完成（目的宇宙已配置、物化並 discovered），這個前提由 caller 負責。
         */
        PREPARE_DOOR_CLOSED,
        /** §11.3：flushed DORMANT MEASURED、來源 SourceFamilyRef.role() 不是 OVERWORLD、選中的是 NEW 或 EXISTING 候選。 */
        UNSUPPORTED_SOURCE_CANCEL;

        boolean accepts(SessionRecoveryRecord record) {
            return switch(this) {
                case PASSAGE_ENDED -> record.state()==SessionState.PASSAGE_READY;
                case SOURCE_FAST_PATH -> preparing(record) && record.collapse().destination() instanceof CollapseDestination.Source;
                case PREPARE_DOOR_CLOSED -> preparing(record) && record.collapse().destination() instanceof CollapseDestination.Universe;
                case UNSUPPORTED_SOURCE_CANCEL -> dormant(record)
                        && record.candidateContext().orElseThrow().sourceFamilyRef().role()!=DimensionRole.OVERWORLD
                        && record.selectedCandidate().filter(chosen -> chosen instanceof QuantumCandidate.New
                            || chosen instanceof QuantumCandidate.Existing).isPresent();
            };
        }
        private static boolean preparing(SessionRecoveryRecord record) {
            return record.state()==SessionState.COLLAPSING && record.spaceLeases().isEmpty()
                    && record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
        }
    }

    /**
     * spec §6 的 roll-forward 類 journal 寫入：COLLAPSING 的返還與清 lease 進度、PASSAGE_READY 加入 Crossed、進入 SEALED
     * （COLLAPSING→SEALED，或 PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)）。前置條件：current 與 flushed 都 exact 等於 expected。
     * 寫出失敗時 current 保留 next 並保持 dirty（{@link CommitResult.Status#ROLL_FORWARD_PENDING}），之後任何寫出（重試、其他 session 的 flush、
     * 原生 autosave、停服）都帶著它；未確認期間 {@link #collapseView} 採用 current。寫出失敗不記 log，由 caller 依原因去重
     * （見 {@link CommitResult}）；讀回已確認之後才出現的例外由 journal 記 WARN（{@link #warnAfterConfirmed}）。
     */
    public CommitResult commitRollForward(MinecraftServer server, SessionRecoveryRecord expected, SessionRecoveryRecord next) {
        requireServerThread(server);
        return commitRollForward(() -> flush(server), expected, next);
    }
    CommitResult commitRollForward(Runnable flush, SessionRecoveryRecord expected, SessionRecoveryRecord next) {
        requireMutationThread();
        requireHealthy();
        var refusal=refusal(expected,next);
        if(refusal==null && !rollForwardEdge(expected.state(),next.state()))
            refusal=new IllegalArgumentException("不是 roll-forward 類的塌縮寫入（返還與清 lease 進度、Crossed、進入 SEALED）："+expected.state()+"→"+next.state());
        if(refusal!=null) return CommitResult.of(CommitResult.Status.REJECTED,refusal);
        try { put(next,false); } catch(RuntimeException invalid) { return CommitResult.of(CommitResult.Status.REJECTED,invalid); }
        var failure=checkedFlush(flush);
        // 以 flushed 判定：讀回已確認 next 就是 COMMITTED（flush 之後才有的例外記 WARN）；否則 current 保留 next。
        if(next.equals(flushed.get(next.sessionUuid()))) {
            failure.ifPresent(late -> warnAfterConfirmed(next.sessionUuid(),"roll-forward "+expected.state()+"→"+next.state(),late));
            return new CommitResult(CommitResult.Status.COMMITTED,Optional.empty());
        }
        markDirty();
        return CommitResult.of(CommitResult.Status.ROLL_FORWARD_PENDING,failure.orElseGet(() -> new IllegalStateException("roll-forward 寫入的 exact readback 不符")));
    }

    /**
     * spec §6 的 restore 類 journal 寫入：COLLAPSING 提交（MEASURED→COLLAPSING）、READY 提交（COLLAPSING→PASSAGE_READY）、
     * SEALED→COLLAPSING 重試。這三條邊只能經這裡寫入（一般 {@link #put} 拒絕）。前置條件：current 與 flushed 都 exact 等於 expected；
     * 提交新的 COLLAPSING 另外要求 {@link #m5ReadyThisInstance}。
     * <p>SEALED→COLLAPSING：journal 層級只排除 TRANSFER_RECOVERY_REQUIRED（spec §5）；caller 必須先以
     * {@link dev.quantumchamber.collapse.SealedReason#retryable()}／{@link dev.quantumchamber.collapse.SealedReason#restartOnly()}
     * 與 §11.2 的觸發、退避把關之後才呼叫，永久原因不得自動重試。
     * <p>寫出失敗時依 H3 的 CAS：current 仍 exact 等於 next 才還原為 expected（上一份 flushed）並保持 dirty、authority history
     * 還原為最後 checked authority；CAS 不符時隔離該 session、不猜測覆寫（current 是已 checked 的 roll-forward 內容時不隔離，回
     * {@link CommitResult.Status#ROLL_FORWARD_PENDING}）。寫出失敗不記 log，由 caller 依原因去重；讀回已確認之後才出現的例外記 WARN。
     */
    public CommitResult commitRestore(MinecraftServer server, SessionRecoveryRecord expected, SessionRecoveryRecord next) {
        requireServerThread(server);
        return commitRestore(() -> flush(server), expected, next);
    }
    CommitResult commitRestore(Runnable flush, SessionRecoveryRecord expected, SessionRecoveryRecord next) {
        requireMutationThread();
        requireHealthy();
        var refusal=refusal(expected,next);
        if(refusal==null && !restoreEdge(expected.state(),next.state()))
            refusal=new IllegalArgumentException("不是 restore 類的塌縮寫入（COLLAPSING 提交、READY、SEALED→COLLAPSING）："+expected.state()+"→"+next.state());
        if(refusal==null && next.state()==SessionState.COLLAPSING && !m5ReadyThisInstance)
            refusal=new IllegalStateException("本 server instance 的 M5 authority 尚未初始化成功，不提交新的 COLLAPSING");
        if(refusal!=null) return CommitResult.of(CommitResult.Status.REJECTED,refusal);
        var sid=next.sessionUuid();
        try { put(next,true); } catch(RuntimeException invalid) { return CommitResult.of(CommitResult.Status.REJECTED,invalid); }
        var failure=checkedFlush(flush);
        if(next.equals(flushed.get(sid))) {
            failure.ifPresent(late -> warnAfterConfirmed(sid,"restore "+expected.state()+"→"+next.state(),late));
            return new CommitResult(CommitResult.Status.COMMITTED,Optional.empty());
        }
        var cause=failure.orElseGet(() -> new IllegalStateException("restore 類寫入的 exact readback 不符"));
        if(next.equals(records.get(sid)) && !quarantined.contains(sid)) {
            restoreCheckedAuthority(sid);
            return CommitResult.of(CommitResult.Status.RESTORED,cause);
        }
        quarantine(sid);
        return CommitResult.of(quarantined.contains(sid) ? CommitResult.Status.QUARANTINED : CommitResult.Status.ROLL_FORWARD_PENDING,cause);
    }

    /**
     * spec §5 唯一的 checked 終局：起點必須是 {@link TerminalOrigin} 接受的 record，而且 current 與 flushed 都 exact 等於 expected
     * → 移除 → flush → 讀回確認不存在。成功後才修剪該 session 的 authority history／checked authority，並留 process 內 tombstone
     * （之後 put 拒絕同一個 UUID）。flush 或讀回失敗時依 H3 的 CAS 放回 expected 並保持 dirty（CAS 不符時隔離；current 是已 checked 的
     * roll-forward 內容時不隔離），不改門。失敗不記 log，由 caller 依 (sid, origin, dedupeKey) 去重後記 WARN（見 {@link TerminalResult}）；
     * 讀回已確認之後才出現的例外記 WARN。runtime（{@code SuperpositionSessionManager} 的 sessions 與來源 ticket）的移除與正常結束的 INFO
     * 由呼叫端負責（F3d）。
     */
    public TerminalResult terminate(MinecraftServer server, SessionRecoveryRecord expected, TerminalOrigin origin) {
        requireServerThread(server);
        return terminate(() -> flush(server), expected, origin);
    }
    TerminalResult terminate(Runnable flush, SessionRecoveryRecord expected, TerminalOrigin origin) {
        requireMutationThread();
        requireHealthy();
        Objects.requireNonNull(origin,"origin");
        var sid=expected.sessionUuid();
        RuntimeException refusal=null;
        if(quarantined.contains(sid) || terminated.contains(sid)) refusal=new IllegalStateException("session 已隔離或已 checked 終局");
        else if(!expected.equals(records.get(sid)) || !expected.equals(flushed.get(sid)))
            refusal=new IllegalStateException("CAS：current 與 flushed 都必須 exact 等於 expected");
        else if(!origin.accepts(expected)) refusal=new IllegalArgumentException("終局起點不合法："+origin+" ← "+expected.state());
        if(refusal!=null) return TerminalResult.of(TerminalResult.Status.REJECTED,refusal);
        records.remove(sid); rollForwardPending.remove(sid); markDirty();
        var failure=checkedFlush(flush);
        if(flushed.containsKey(sid)) {
            var cause=failure.orElseGet(() -> new IllegalStateException("終局的 exact readback 仍含該 session"));
            if(!records.containsKey(sid) && !quarantined.contains(sid)) {
                restoreCheckedAuthority(sid);
                return TerminalResult.of(TerminalResult.Status.RESTORED,cause);
            }
            quarantine(sid);
            return TerminalResult.of(quarantined.contains(sid) ? TerminalResult.Status.QUARANTINED : TerminalResult.Status.ROLL_FORWARD_PENDING,cause);
        }
        // 讀回已確認不存在（flush 之後才有的例外記 WARN）：才修剪 authority history，並留 tombstone。
        failure.ifPresent(late -> warnAfterConfirmed(sid,"terminate "+origin,late));
        authorityHistory.remove(sid); checkedAuthority.remove(sid); terminated.add(sid); confirmedFailureWarnings.remove(sid);
        return new TerminalResult(TerminalResult.Status.REMOVED,Optional.empty());
    }

    /** 執行一次 checked flush；成功與否由呼叫端以 flushed（讀回確認的內容）判定，這裡只帶回例外。 */
    private static Optional<RuntimeException> checkedFlush(Runnable flush) {
        try { flush.run(); return Optional.empty(); } catch(RuntimeException failure) { return Optional.of(failure); }
    }

    /**
     * 讀回已確認之後才出現的例外（例如 flush 之後的 hook）：結果以 flushed 為準，不當成寫出失敗，但不得靜默丟棄。
     * 同一個 session 依 {@link FailureText#dedupeKey} 去重，原因改變才再記（沿用 recovery 的去重慣例）。
     */
    private void warnAfterConfirmed(UUID sid, String write, RuntimeException late) {
        var key=FailureText.dedupeKey(late);
        if(key.equals(confirmedFailureWarnings.put(sid,key))) return;
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("checked 寫出已讀回確認，之後才出現例外（結果以 flushed 為準，不重試）：session={} {}：{}",
                sid,write,FailureText.describe(late));
    }

    /** commit 共用的前置條件；回傳拒絕原因，可以寫入時為 null。 */
    private RuntimeException refusal(SessionRecoveryRecord expected, SessionRecoveryRecord next) {
        var sid=expected.sessionUuid();
        if(!sid.equals(next.sessionUuid())) return new IllegalArgumentException("expected 與 next 必須是同一個 session");
        if(quarantined.contains(sid) || terminated.contains(sid)) return new IllegalStateException("session 已隔離或已 checked 終局");
        if(!expected.equals(records.get(sid)) || !expected.equals(flushed.get(sid)))
            return new IllegalStateException("CAS：current 與 flushed 都必須 exact 等於 expected");
        if(next.equals(expected)) return new IllegalArgumentException("next 與 expected 相同，沒有可提交的變更");
        return null;
    }

    /** spec §6 的 restore 類狀態邊：COLLAPSING 提交、READY 提交、SEALED→COLLAPSING 重試（終局另由 {@link #terminate} 處理）。 */
    private static boolean restoreEdge(SessionState from, SessionState to) {
        return from==SessionState.MEASURED && to==SessionState.COLLAPSING
                || from==SessionState.COLLAPSING && to==SessionState.PASSAGE_READY
                || from==SessionState.SEALED && to==SessionState.COLLAPSING;
    }

    /** spec §6 的 roll-forward 類狀態邊：COLLAPSING／PASSAGE_READY 的 self-loop 進度，以及由它們進入 SEALED。SEALED 沒有 self-loop 進度。 */
    private static boolean rollForwardEdge(SessionState from, SessionState to) {
        return (from==SessionState.COLLAPSING || from==SessionState.PASSAGE_READY) && (to==from || to==SessionState.SEALED);
    }

    /** start 前 ownership 預檢：只看 {@link #occupies}；未確認移除的 flushed record 仍佔用。 */
    public boolean participantsAvailable(java.util.Collection<UUID> players) {
        requireHealthy();
        return Stream.concat(records.values().stream(),flushed.values().stream())
                .noneMatch(record -> players.stream().anyMatch(player -> occupies(record,player)));
    }
    /**
     * 一般寫入。塌縮狀態的 record 只接受 roll-forward 類（返還與清 lease 進度、Crossed、進入 SEALED），並把該 session 記為
     * roll-forward 尚未確認，直到 checked flush 讀回確認 flushed 已等於 current；restore 類狀態邊只能經 {@link #commitRestore}。
     */
    public void put(SessionRecoveryRecord record) { put(record,false); }
    private void put(SessionRecoveryRecord record, boolean restoreCommit) {
        requireMutationThread();
        requireHealthy();
        requireNotQuarantined(record.sessionUuid());
        if (terminated.contains(record.sessionUuid())) throw new IllegalStateException("session 已 checked 終局，同一 UUID 不得重新放回");
        if (candidateInitialized && record.candidateContext().isEmpty()) {
            throw new IllegalArgumentException("已初始化 candidate 的 journal 不得重新加入 legacy record");
        }
        var current=records.get(record.sessionUuid());
        var durable=flushed.get(record.sessionUuid());
        var historical=authorityHistory.get(record.sessionUuid());
        if (record.state().collapseState()) requireCollapseWrite(record,current,durable,historical,restoreCommit);
        // 同時保護 dirty、durable 與已移除紀錄的 authority。
        if ((current!=null && !SessionRecoveryRecord.sameAuthority(current,record))
                || (durable!=null && !SessionRecoveryRecord.sameAuthority(durable,record))
                || (historical!=null && !SessionRecoveryRecord.sameAuthority(historical,record))) {
            throw new IllegalArgumentException("同一 session 的凍結 authority 不可改動，ledger 與 selection 不可倒退");
        }
        var candidate = new LinkedHashMap<>(records); candidate.put(record.sessionUuid(), record);
        validateOwnership(candidate);
        records.put(record.sessionUuid(), record); authorityHistory.put(record.sessionUuid(), record);
        candidateInitialized |= record.candidateContext().isPresent();
        if (record.state().collapseState() && !restoreCommit) {
            if (record.equals(durable)) rollForwardPending.remove(record.sessionUuid()); else rollForwardPending.add(record.sessionUuid());
        }
        markDirty();
    }
    /**
     * 塌縮狀態的寫入前置：journal 必須已有 M5 證據（schema 4 之前不能存在塌縮狀態）；只能沿既有 authority 的狀態邊進入；
     * 一般 put 不得寫 restore 類狀態邊（current、flushed 或 history 任一份到 record 是 restore 邊都拒絕）。
     */
    private void requireCollapseWrite(SessionRecoveryRecord record, SessionRecoveryRecord current, SessionRecoveryRecord durable,
            SessionRecoveryRecord historical, boolean restoreCommit) {
        if (!m5Evidence) throw new IllegalStateException("M5 authority 尚未初始化，journal 不得寫入塌縮狀態（schema 4 之前）");
        var references = Stream.of(current, durable, historical).filter(Objects::nonNull).toList();
        if (references.isEmpty()) throw new IllegalArgumentException("塌縮狀態只能由保留的 MEASURED receipt 經狀態邊進入");
        if (!restoreCommit && references.stream().anyMatch(reference -> restoreEdge(reference.state(), record.state()))) {
            throw new IllegalStateException("COLLAPSING 提交、READY 與 SEALED→COLLAPSING 是 restore 類寫入，只能經 commitRestore");
        }
    }
    public boolean remove(UUID id) {
        requireMutationThread();
        requireHealthy();
        requireNotQuarantined(id);
        if(Stream.of(records.get(id),flushed.get(id),authorityHistory.get(id))
                .anyMatch(record -> record!=null && receiptRetained(record.state())))
            throw new IllegalStateException("MEASURED 與塌縮狀態的 receipt 必須保留，不能經一般 remove 消耗（只有 checked 終局 API 可以移除）");
        if (records.remove(id) == null) return false;
        markDirty(); return true;
    }
    public void flush(MinecraftServer server) {
        requireServerThread(server);
        if (get(server) != this) throw new IllegalStateException("journal 不屬於此 server");
        save(path(server).toFile(), server.getRegistryManager());
    }
    public NbtCompound writeNbt(NbtCompound nbt) {
        requireHealthy();
        var writable = writable();
        int schema = writtenSchema(writable);
        nbt.putInt("SchemaVersion", schema);
        var entries = new NbtList(); writable.values().forEach(record -> entries.add(record.toNbt(schema)));
        nbt.put("Records", entries); return nbt;
    }
    /**
     * spec §5 的寫出式子：有 legacy record → 2；否則 candidateInitialized → (m5Evidence ? 4 : 3)；否則 → 2（空 journal）。
     * 兩個旗標都不會變回假，所以一旦寫成 schema 4 就不再降回。
     */
    private int writtenSchema(Map<UUID, SessionRecoveryRecord> writable) {
        // envelopeSchema 先驗證不混裝 legacy 與 candidate（混裝時丟例外，寫出因此失敗）；它的回傳值（有 candidate → 3，否則 2）
        // 只在最後一行使用。
        int envelope = envelopeSchema(writable);
        if (writable.values().stream().anyMatch(record -> record.candidateContext().isEmpty())) return 2;
        if (candidateInitialized) return m5Evidence ? 4 : 3;
        // 空 journal → 2。有 candidate record 時 candidateInitialized 必為真，走不到這裡；仍沿用 envelope 判斷，不寫出錯誤的 2。
        return envelope;
    }
    /** 所有寫出（checked flush、原生 autosave／stop）共用：隔離 session 只寫上一份 flushed authority，其他 session 照常。 */
    private Map<UUID, SessionRecoveryRecord> writable() {
        if (quarantined.isEmpty()) return records;
        var result = new LinkedHashMap<UUID, SessionRecoveryRecord>();
        records.forEach((id, record) -> {
            var checked = quarantined.contains(id) ? flushed.get(id) : record;
            if (checked != null) result.put(id, checked);
        });
        return result;
    }
    @Override public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) { return writeNbt(nbt); }

    @Override public void save(File file, RegistryWrapper.WrapperLookup lookup) {
        requireMutationThread();
        requireHealthy();
        if (!isDirty()) return;
        var wrapped = new NbtCompound(); wrapped.put("data", writeNbt(new NbtCompound()));
        NbtHelper.putDataVersion(wrapped);
        var expected = Map.copyOf(writable());
        try {
            SessionJournalStore.write(file.toPath(), wrapped);
            var actual = readback.read(file.toPath());
            var decoded = fromNbt(actual.getCompound("data"));
            decoded.requireHealthy();
            if (!wrapped.equals(actual) || !expected.equals(decoded.records())) {
                throw new IOException("journal 正式檔 exact readback 不符");
            }
            flushed = decoded.flushedRecords();
            authorityHistory.putAll(flushed);
            checkedAuthority.putAll(flushed);
            // roll-forward 尚未確認的 session：讀回確認 flushed 已等於 current 時清除。
            rollForwardPending.removeIf(sid -> Objects.equals(flushed.get(sid), records.get(sid)));
        } catch (IOException | RuntimeException exception) {
            throw new UncheckedIOException("journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot",
                    exception instanceof IOException io ? io : new IOException("journal strict readback 失敗", exception));
        }
        setDirty(false);
    }

    private static SessionRecoveryState failed(String error) {
        var state = new SessionRecoveryState(); state.loadError = error; return state;
    }
    private static Path path(MinecraftServer server) { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(STATE_ID + ".dat"); }
    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("journal 必須在伺服器執行緒操作");
    }
    private void requireMutationThread() {
        if (owner != null) requireServerThread(owner);
    }
    private void requireNotQuarantined(UUID id) {
        if (quarantined.contains(id)) throw new IllegalStateException("session 的未 checked authority 已隔離，拒絕再寫入");
    }
    /** DORMANT＝MEASURED+SELECTED、全員 returned 且無 lease；它只封鎖自己的 Chamber（spec §11）。 */
    private static boolean dormant(SessionRecoveryRecord record) {
        return record.state() == dev.quantumchamber.superposition.SessionState.MEASURED
                && MeasuredRecoveryPhase.from(record) == MeasuredRecoveryPhase.DORMANT;
    }
    private static void validateOwnership(Map<UUID, SessionRecoveryRecord> records) {
        envelopeSchema(records);
        var chambers = new HashSet<UUID>(); var players = new HashSet<UUID>(); var slots = new HashSet<Integer>();
        for (var record : records.values()) {
            if (!chambers.add(record.chamberUuid())) throw new IllegalArgumentException("來源艙已有 session");
            // 玩家唯一性只約束仍佔用玩家的 session（occupies）：DORMANT 與塌縮狀態的已返還參與者不佔用；Chamber 的唯一性不變。
            for (var person : record.participants())
                if (occupies(record, person.playerUuid()) && !players.add(person.playerUuid())) throw new IllegalArgumentException("玩家跨 session 重複");
            for (var lease : record.spaceLeases()) if (!slots.add(lease.slotId())) throw new IllegalArgumentException("slot 跨 session 重複");
        }
    }
    private static int envelopeSchema(Map<UUID, SessionRecoveryRecord> records) {
        boolean legacy = false; boolean candidate = false;
        for (var record : records.values()) {
            if (record.candidateContext().isPresent()) candidate = true; else legacy = true;
        }
        if (legacy && candidate) throw new IllegalArgumentException("同一 journal envelope 不可混裝 legacy 與 candidate sessions");
        return candidate ? 3 : 2;
    }
}
