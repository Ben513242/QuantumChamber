package dev.quantumchamber.persistence;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.universe.GenerationSettingsSnapshot;
import dev.quantumchamber.universe.Minecraft121SettingsLifecycle;
import dev.quantumchamber.universe.UniverseProfileRegistry;
import dev.quantumchamber.universe.ServerSettingsContext;
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
import java.util.List;
import java.util.ArrayList;
import java.lang.ref.WeakReference;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
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
    public enum JournalSlot { ABSENT, PRESENT_NULL, SAME, OTHER }
    /** 固定 journal key，只回傳關係；不交出原生 map、foreign state 或鎖。 */
    public interface JournalSlotView { JournalSlot quantumchamber$inspectJournalSlot(SessionRecoveryState expected); }
    @FunctionalInterface interface JournalReadback { NbtCompound read(Path path) throws IOException; }
    private final JournalReadback readback;
    @FunctionalInterface interface M6IntentValidator { void validate(List<NewUniverseIntent> intents); }
    private static final M6IntentValidator REJECT_M6 = intents -> { throw new IllegalStateException("M6 snapshot 缺少 server-bound validator"); };
    private final M6IntentValidator intentValidator;
    public SessionRecoveryState() { this(SessionJournalStore::read); }
    SessionRecoveryState(JournalReadback readback) { this(readback, REJECT_M6); }
    SessionRecoveryState(JournalReadback readback, M6IntentValidator validator) {
        this.readback = Objects.requireNonNull(readback); this.intentValidator = Objects.requireNonNull(validator);
    }
    /**
     * provider 綁定每次都檢查；需要 lease 的 resolve 每個 snapshot 在本 validator 只做一次，整批成功才記住（Task 22 整分支審查 fix1）。
     * validator 隨 state 建立，生命期是一個 server instance；同一 instance 內 resolve 依賴的 worldgen registry 不隨 {@code /reload} 改變，
     * 所以已 resolve 過的 snapshot 在 reload 期間或失敗的 reload 之後，journal 仍可讀寫，不必再取 lease。
     */
    static M6IntentValidator m6Validator(Supplier<UniverseProfileRegistry> registry, Supplier<ServerSettingsContext> acquire) {
        Objects.requireNonNull(registry); Objects.requireNonNull(acquire);
        var resolved = new HashSet<NbtCompound>();
        return intents -> {
            if (intents.isEmpty()) return;
            var profiles = registry.get();
            var pending = new LinkedHashMap<NbtCompound, GenerationSettingsSnapshot>();
            for (var intent : intents) {
                for (var snapshot : List.of(intent.sourceSettingsSnapshot(), intent.targetSettingsSnapshot())) {
                    profiles.requireBindable(snapshot);
                    pending.putIfAbsent(snapshot.toNbt(), snapshot);
                }
            }
            synchronized (resolved) { pending.keySet().removeAll(resolved); }
            if (pending.isEmpty()) return;
            var lease = acquire.get(); lease.requireValid();
            for (var snapshot : pending.values()) profiles.resolve(snapshot, lease);
            lease.requireValid();
            synchronized (resolved) { resolved.addAll(pending.keySet()); }
        };
    }
    private enum InstallationPhase { LOADING, INSTALLED, FAILED }
    private static final class InstalledState {
        private final WeakReference<Object> server;
        private final WeakReference<Object> manager;
        private final WeakReference<Thread> ownerThread = new WeakReference<>(Thread.currentThread());
        private WeakReference<SessionRecoveryState> state = new WeakReference<>(null);
        private InstallationPhase phase = InstallationPhase.LOADING;
        InstalledState(Object server, Object manager) {
            this.server = new WeakReference<>(server); this.manager = new WeakReference<>(manager);
        }
    }
    private static final List<InstalledState> INSTALLATIONS = new ArrayList<>();
    private static JournalSlot peek(Object manager, SessionRecoveryState expected) {
        if (!(manager instanceof JournalSlotView view)) throw new IllegalStateException("JOURNAL_SLOT_VIEW_UNAVAILABLE");
        return Objects.requireNonNull(view.quantumchamber$inspectJournalSlot(expected));
    }
    /** 呼叫端持有 INSTALLATIONS；不呼 native API、外部 callback 或 IO。 */
    private static InstalledState findBinding(Object server, Object manager) {
        INSTALLATIONS.removeIf(entry -> entry.server.get() == null);
        for (var entry : INSTALLATIONS) {
            if (entry.server.get() == server) {
                if (entry.manager.get() != manager) {
                    entry.phase = InstallationPhase.FAILED;
                    throw new IllegalStateException("JOURNAL_WRONG_MANAGER");
                }
                return entry;
            }
        }
        // 先處理該 server 的既有 binding，只有新 server 才檢查 manager 是否被占用。
        for (var entry : INSTALLATIONS) {
            if (entry.manager.get() == manager) throw new IllegalStateException("JOURNAL_SHARED_MANAGER");
        }
        return null;
    }
    private static void requirePhase(InstalledState entry, Object server, Object manager, InstallationPhase phase) {
        if (entry == null || findBinding(server, manager) != entry || entry.phase != phase
                || entry.ownerThread.get() != Thread.currentThread()) throw new IllegalStateException("JOURNAL_BINDING_CONFLICT");
    }
    /** 不初始化、不重新讀檔；只核既有 owner。peek 自己的短 fence 不與 INSTALLATIONS 巢狀。 */
    private static void requireOwned(Object server, Object manager, InstalledState slot, SessionRecoveryState state) {
        synchronized (INSTALLATIONS) {
            requirePhase(slot, server, manager, InstallationPhase.INSTALLED);
            if (slot.state.get() != state || state.installation != slot || state.installationServer != server)
                throw new IllegalStateException("JOURNAL_OWNER_IDENTITY");
        }
        var observed = peek(manager, state);
        synchronized (INSTALLATIONS) {
            requirePhase(slot, server, manager, InstallationPhase.INSTALLED);
            if (observed != JournalSlot.SAME || slot.state.get() != state || server instanceof MinecraftServer && state.owner != server) {
                slot.phase = InstallationPhase.FAILED;
                throw new IllegalStateException("JOURNAL_OWNER_SLOT_MISMATCH");
            }
        }
    }
    static SessionRecoveryState getOrInstall(Object server, Object manager,
            Supplier<SessionRecoveryState> loader, Consumer<SessionRecoveryState> installer) {
        Objects.requireNonNull(server); Objects.requireNonNull(manager); Objects.requireNonNull(loader); Objects.requireNonNull(installer);
        InstalledState known; SessionRecoveryState cached = null;
        synchronized (INSTALLATIONS) {
            known = findBinding(server, manager);
            if (known != null) {
                if (known.ownerThread.get() != Thread.currentThread()) throw new IllegalStateException("JOURNAL_WRONG_THREAD");
                cached = known.state.get();
                if (known.phase != InstallationPhase.INSTALLED || cached == null) {
                    known.phase = InstallationPhase.FAILED; throw new IllegalStateException("JOURNAL_BINDING_CONFLICT");
                }
            }
        }
        if (cached != null) { requireOwned(server, manager, known, cached); return cached; }
        if (peek(manager, null) != JournalSlot.ABSENT) throw new IllegalStateException("JOURNAL_INITIAL_SLOT_OCCUPIED");
        InstalledState slot;
        synchronized (INSTALLATIONS) {
            if (findBinding(server, manager) != null) throw new IllegalStateException("JOURNAL_RESERVATION_DRIFT");
            slot = new InstalledState(server, manager); INSTALLATIONS.add(slot);
        }
        try {
            var state = Objects.requireNonNull(loader.get(), "loaded state");
            if (peek(manager, null) != JournalSlot.ABSENT) throw new IllegalStateException("JOURNAL_INSTALL_PRECHECK");
            synchronized (INSTALLATIONS) {
                requirePhase(slot, server, manager, InstallationPhase.LOADING);
                if (state.installation != null || server instanceof MinecraftServer && state.owner != server)
                    throw new IllegalStateException("JOURNAL_INSTALL_OWNER");
                state.installation = slot; state.installationServer = server; slot.state = new WeakReference<>(state);
            }
            installer.accept(state);
            if (peek(manager, state) != JournalSlot.SAME) throw new IllegalStateException("JOURNAL_INSTALL_POSTCHECK");
            synchronized (INSTALLATIONS) {
                requirePhase(slot, server, manager, InstallationPhase.LOADING); slot.phase = InstallationPhase.INSTALLED;
            }
            return state;
        } catch (RuntimeException | Error failure) {
            synchronized (INSTALLATIONS) { slot.phase = InstallationPhase.FAILED; }
            throw failure;
        }
    }
    private final Map<UUID, JournalSession> records = new LinkedHashMap<>();
    // 僅存活於本 process；remove 與空 journal flush 都不能抹去同 UUID 的最後 authority。
    private final Map<UUID, JournalSession> authorityHistory = new LinkedHashMap<>();
    // 最後一份經 strict load／readback 承認的 authority（一般 remove 後仍保留）；只供 restoreCheckedAuthority 還原 history
    // （abortUnchecked、convergeQuarantined、commitRestore 與 terminate 的失敗還原）。checked 終局讀回確認後才修剪該 session。
    private final Map<UUID, JournalSession> checkedAuthority = new LinkedHashMap<>();
    // CAS 還原失敗或 owner 未能還原的 session：未 checked current 永不寫出，寫入時只用上一份 flushed authority；由 convergeQuarantined 收斂。
    private final java.util.Set<UUID> quarantined = new HashSet<>();
    private Map<UUID, JournalSession> flushed = Map.of();
    // Schema 3 envelope 本身是不可撤銷的初始化證據；不新增 NBT 欄位。
    private boolean candidateInitialized;
    // m5Evidence：讀到 schema 4／5 或本 instance 初始化成功時為真；schemaFiveEvidence 另守單向升級，不混用。
    // m5ReadyThisInstance 只決定能不能提交新的 COLLAPSING：只有本 instance 的 M5 authority 初始化成功才為真，讀到 schema 4 不算。
    private boolean m5Evidence;
    private boolean schemaFiveEvidence;
    private boolean schemaSixEvidence;
    // 不改 schema1–4 的既有重試流程；但讀回未確認時，不能直接跨進首次 schema5 提交。
    private boolean legacyWriteUnconfirmed;
    // 只有正式 load／正常 exact readback 才能建立 wrapped provenance；純 fromNbt 不猜 DataVersion 或磁碟不存在。
    private NbtCompound checkedWrapped;
    private Path checkedPath;
    private boolean checkedAbsent;
    private PendingAttempt pending;
    private boolean pendingRestartRequired;
    private record PendingAttempt(Path target, SessionJournalStore.PreparedWrite prepared,
            Map<UUID, JournalSession> expectedRecords, NbtCompound oldWrapped,
            Map<UUID, JournalSession> oldRecords, boolean oldAbsent) {
        private PendingAttempt {
            expectedRecords = Map.copyOf(expectedRecords); oldRecords = Map.copyOf(oldRecords);
            oldWrapped = oldWrapped == null ? null : oldWrapped.copy();
        }
    }
    private boolean m5ReadyThisInstance;
    // 已 checked 終局（讀回確認不存在）的 session：本 process 內 put 一律拒絕同一個 UUID。
    private final java.util.Set<UUID> terminated = new HashSet<>();
    // roll-forward 類寫入（spec §6）尚未經 checked flush 讀回確認的 session；collapse view 只在這時採用 current。
    private final java.util.Set<UUID> rollForwardPending = new HashSet<>();
    // 「讀回已確認之後才出現的例外」最近一次 WARN 的去重鍵（FailureText.dedupeKey），以 session 為單位；該 session 下一次乾淨的
    // 寫出（讀回確認、沒有例外）或終局成功時移除。
    private final Map<UUID, String> confirmedFailureWarnings = new HashMap<>();
    // T4 P1／P2（P1-M1）：寫出前核對到收據不符（或無法核對）而已記過管理員 ERROR 的類別；同一段不符期間每個 SID 的每一類只記一次。
    // T4 B2b-2（B2b1-M2）：這個 SID 的收據核對通過時清除它的鍵（見 clearReceiptReports），之後再發生仍會再記。
    private final java.util.Set<ReceiptReport> receiptMismatchReported = new HashSet<>();
    // T4 P1：目前這次 save 是否來自 checked flush（{@link #flush}）。不是時就是原版的原生存檔（autosave、/save-all、停服）。
    private boolean checkedWrite;
    // T4 P2：原生存檔已記過 ERROR 的 journal 寫出失敗類別；同一段失敗期間每一類只記一次（見 {@link #reportNativeSaveFailure}）。
    // T4 B2b-1（P2-M2）：讀回確認的成功寫出（checked 或原生）結束這段失敗期間時清除，比照 confirmedFailureWarnings；之後再發生同類失敗仍會再記。
    private final java.util.EnumSet<NativeSaveFailure> nativeSaveFailureReported = java.util.EnumSet.noneOf(NativeSaveFailure.class);
    private String loadError;
    private MinecraftServer owner;
    private InstalledState installation;
    private Object installationServer;

    public static SessionRecoveryState get(MinecraftServer server) {
        requireServerThread(server);
        Path path = path(server);
        var manager = server.getOverworld().getPersistentStateManager();
        return getOrInstall(server, manager, () -> {
            var state = load(path, m6Validator(Minecraft121SettingsLifecycle::registry,
                    () -> Minecraft121SettingsLifecycle.acquire(server)));
            state.owner = server;
            return state;
        }, state -> manager.set(STATE_ID, state));
    }

    static SessionRecoveryState load(Path path) {
        return load(path, REJECT_M6);
    }
    static SessionRecoveryState load(Path path, M6IntentValidator validator) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            var state = new SessionRecoveryState(SessionJournalStore::read, validator);
            state.checkedPath = path.toAbsolutePath().normalize(); state.checkedAbsent = true;
            return state;
        } catch (IOException | RuntimeException exception) {
            return failed("無法確認 journal 路徑：" + exception);
        }
        try {
            var wrapped = SessionJournalStore.read(path);
            var state = fromNbt(wrapped.getCompound("data"), validator);
            if (state.loadError == null) {
                if (state.schemaSixEvidence) {
                    HomeJournalBudget.requireReserved(wrapped);
                    verifyReceipts(path,state.flushed);
                }
                state.checkedWrapped = wrapped.copy(); state.checkedPath = path.toAbsolutePath().normalize();
            }
            return state;
        } catch (IOException | RuntimeException exception) {
            // 已見到檔案後消失也屬讀取失敗，不退回空 journal。
            return failed("journal 無法讀取：" + exception);
        }
    }

    static void verifyReceipts(Path file, Map<UUID,JournalSession> rows) throws IOException {
        if(rows.isEmpty()) return;
        var store=new HomeReceiptStore(file.toAbsolutePath().normalize().getParent().getParent());
        for(var row:rows.values()) store.verifyActive(row);
    }

    public static SessionRecoveryState fromNbt(NbtCompound nbt) {
        return fromNbt(nbt, REJECT_M6);
    }
    static SessionRecoveryState fromNbt(NbtCompound nbt, M6IntentValidator validator) {
        try {
            JournalNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
            int schema=nbt.getInt("SchemaVersion");
            if (schema < 1 || schema > 6) throw new IllegalArgumentException("不支援 journal schema");
            if (schema >= 3) JournalNbt.keys(nbt, "SchemaVersion", "Records");
            var state = new SessionRecoveryState(SessionJournalStore::read, validator);
            // schema 4／5 都是 candidate 與 M5 authority 已初始化的證據，但不代表本 instance 已就緒。
            state.candidateInitialized = schema >= 3;
            state.m5Evidence = schema >= 4;
            state.schemaFiveEvidence = schema >= 5;
            state.schemaSixEvidence = schema == 6;
            for (var raw : JournalNbt.compounds(nbt, "Records", schema)) {
                var row = (NbtCompound)raw;
                JournalSession record = schema == 6 && row.contains("RecordVersion") ? HomeJournalCodec.decode(row)
                        : SessionRecoveryRecord.fromNbt(row, Math.min(schema,5));
                if (state.records.putIfAbsent(record.sessionUuid(), record) != null) throw new IllegalArgumentException("session UUID 重複");
            }
            validateOwnership(state.records);
            var newIntents = state.records().values().stream().flatMap(record -> record.candidateLedger().stream())
                    .map(entry -> entry.candidate()).filter(candidate -> candidate instanceof QuantumCandidate.New fresh && fresh.intent().isM6())
                    .map(candidate -> ((QuantumCandidate.New) candidate).intent()).toList();
            if (!newIntents.isEmpty()) state.intentValidator.validate(newIntents);
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
    public Map<UUID,JournalSession> allSessions() { requireHealthy(); return Map.copyOf(records); }
    public Map<UUID,JournalSession> allFlushedSessions() { requireHealthy(); return flushed; }
    /** 本次已送出但尚未確認的候選值；維持與原始 attempt 共用的不可變快照。 */
    public Map<UUID,JournalSession> pendingSessions() {
        requireHealthy(); return pending==null ? Map.of() : pending.expectedRecords();
    }
    private static Map<UUID,SessionRecoveryRecord> legacyRows(Map<UUID,JournalSession> rows) {
        var result = new LinkedHashMap<UUID,SessionRecoveryRecord>();
        rows.forEach((id,row) -> { if(row instanceof SessionRecoveryRecord old) result.put(id,old); });
        return Map.copyOf(result);
    }
    public Map<UUID,SessionRecoveryRecord> records() { requireHealthy(); return legacyRows(records); }
    public Map<UUID,SessionRecoveryRecord> flushedRecords() { requireHealthy(); return legacyRows(flushed); }
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
     * 以及 M5 的 restore 類狀態邊（見 {@link #uncheckedSessions}）。
     * owner 失敗即 CAS 還原或隔離，recovery tick 也會隔離任何殘留，因此正常只剩 checked 提交本身同步的 put→flush 窗口會回 false。
     * M5 的 roll-forward 類內容（返還與清 lease 進度、Crossed、進入 SEALED）不算未 checked：它們本來就要由之後的任何寫出帶出去。
     */
    public boolean recoveryWritesSafe() { return uncheckedSessions().isEmpty(); }
    /**
     * 非隔離、current 含未 checked authority 的 session。除了 candidate 三欄位與 flushed 不同之外，也包含 current 相對 flushed 跨過
     * M5 restore 類狀態邊（MEASURED→COLLAPSING、COLLAPSING→PASSAGE_READY、SEALED→COLLAPSING）的 session：這類內容只能經
     * {@link #commitRestore} 的同步 put→flush 承認，失敗即還原；殘留時由 recovery tick 隔離並收斂回 flushed。
     */
    public java.util.Set<UUID> uncheckedSessions() {
        requireHealthy();
        var unchecked=new java.util.LinkedHashSet<UUID>();
        for(var current : records.values()) if(!quarantined.contains(current.sessionUuid()) && !authorityChecked(current)) unchecked.add(current.sessionUuid());
        return java.util.Set.copyOf(unchecked);
    }
    private boolean authorityChecked(JournalSession current) {
        if (current instanceof HomeSessionRecord) return current.equals(flushed.get(current.sessionUuid())) || rollForwardPending.contains(current.sessionUuid());
        return legacyAuthorityChecked((SessionRecoveryRecord)current);
    }
    private boolean legacyAuthorityChecked(SessionRecoveryRecord current) {
        if(!candidateChecked(current)) return false;
        if(!current.state().collapseState()) return true;
        var durable=flushed.get(current.sessionUuid());
        return durable!=null && !restoreEdge(durable.state(),current.state());
    }
    private boolean candidateChecked(SessionRecoveryRecord current) {
        if(current.candidateContext().isEmpty()) return true;
        var previous=(SessionRecoveryRecord)flushed.get(current.sessionUuid());
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
        var sid=expected.sessionUuid(); var current=(SessionRecoveryRecord)records.get(sid);
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
    java.util.Optional<JournalSession> convergeQuarantined(UUID sessionUuid) {
        requireMutationThread();
        requireHealthy();
        if(!quarantined.contains(sessionUuid)) throw new IllegalStateException("只有隔離中的 session 可以收斂");
        var durable=flushed.get(sessionUuid);
        JournalSession target=durable;
        if(durable instanceof SessionRecoveryRecord old) target=convergenceTarget(old);
        if(durable instanceof HomeSessionRecord h && (h.state()==SessionState.ARMING || h.state()==SessionState.SUPERPOSITION))
            target=new HomeSessionRecord(h.sessionUuid(),h.chamberUuid(),h.rootAnchor(),h.entryEndpoint(),h.participants(),
                    h.spaceLeases(),SessionState.RETURNING,h.home());
        var candidate=new LinkedHashMap<>(records);
        if(target==null) candidate.remove(sessionUuid); else candidate.put(sessionUuid,target);
        validateOwnership(candidate);
        requireRoom(candidate,sessionUuid);
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
        var row = rollForwardPending.contains(session) && !quarantined.contains(session) ? records.get(session) : flushed.get(session);
        return row instanceof SessionRecoveryRecord old ? Optional.of(old) : Optional.empty();
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
     * （見 {@link CommitResult}）；讀回已確認之後才出現的例外由 journal 記 WARN（{@link #afterConfirmedWrite}）。
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
            afterConfirmedWrite(next.sessionUuid(),"roll-forward "+expected.state()+"→"+next.state(),failure);
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
            afterConfirmedWrite(sid,"restore "+expected.state()+"→"+next.state(),failure);
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
        // 讀回已確認不存在（flush 之後才有的例外記 WARN）：才修剪 authority history，並留 tombstone；session 已結束，去重鍵一併移除。
        afterConfirmedWrite(sid,"terminate "+origin,failure);
        authorityHistory.remove(sid); checkedAuthority.remove(sid); terminated.add(sid); confirmedFailureWarnings.remove(sid);
        return new TerminalResult(TerminalResult.Status.REMOVED,Optional.empty());
    }

    /** 執行一次 checked flush；成功與否由呼叫端以 flushed（讀回確認的內容）判定，這裡只帶回例外。 */
    private static Optional<RuntimeException> checkedFlush(Runnable flush) {
        try { flush.run(); return Optional.empty(); } catch(RuntimeException failure) { return Optional.of(failure); }
    }

    /**
     * 讀回已確認的寫出之後的 log 處理：flush 有例外（例如 flush 之後的 hook）時，結果仍以 flushed 為準、不當成寫出失敗，但不得靜默丟棄，
     * 記 WARN 並附 stack trace；沒有例外（乾淨的寫出）時清除該 session 的去重鍵。
     * <p>去重與 {@code SessionRecoveryManager} 的慣例一致：以 session 為單位記住最近一次的 {@link FailureText#dedupeKey}，
     * 原因相同就不再記，原因改變或中間有一次乾淨寫出（{@code failures.remove} 的對應）之後再出現才再記。
     * 去重鍵不區分寫入種類（restore、roll-forward、終局）：這種例外來自三者共用的 flush 路徑，與寫的是哪一條邊無關；
     * 每則 WARN 仍帶上寫入種類。
     */
    private void afterConfirmedWrite(UUID sid, String write, Optional<RuntimeException> late) {
        if(late.isEmpty()) { confirmedFailureWarnings.remove(sid); return; }
        var key=FailureText.dedupeKey(late.orElseThrow());
        if(key.equals(confirmedFailureWarnings.put(sid,key))) return;
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("checked 寫出已讀回確認，之後才出現例外（結果以 flushed 為準，不重試）：session={} {}：{}",
                sid,write,FailureText.describe(late.orElseThrow()),late.orElseThrow());
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
    public CommitResult admitHome(MinecraftServer server, HomeSessionRecord next) {
        requireServerThread(server);
        HomeReceiptStore store;
        try { store=new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)); }
        catch(IOException failure) { return CommitResult.of(CommitResult.Status.REJECTED,new UncheckedIOException(failure)); }
        return admitHome(store, () -> flush(server),next);
    }
    CommitResult admitHome(HomeReceiptStore receipts, Runnable flush, HomeSessionRecord next) {
        requireMutationThread(); requireHealthy();
        var sid=next.sessionUuid();
        if(next.state()!=SessionState.ARMING || next.participants().stream().anyMatch(SessionRecoveryRecord.Participant::returned)
                || !m5ReadyThisInstance || legacyWriteUnconfirmed || pending!=null || !rollForwardPending.isEmpty()
                || !quarantined.isEmpty() || !records.equals(flushed) || authorityHistory.containsKey(sid) || terminated.contains(sid))
            return CommitResult.of(CommitResult.Status.REJECTED,new IllegalStateException("HOME 入場前置不符"));
        try {
            receipts.requireAdmissible(sid);
            stageHome(next,false);
        } catch(IOException ex) { return CommitResult.of(CommitResult.Status.REJECTED,new UncheckedIOException(ex)); }
        catch(RuntimeException ex) { return CommitResult.of(CommitResult.Status.REJECTED,ex); }
        return finishHome(next,true,flush);
    }
    public CommitResult commitHomeRestore(MinecraftServer server, HomeSessionRecord expected, HomeSessionRecord next) {
        requireServerThread(server); return commitHome(() -> flush(server),expected,next,true);
    }
    public CommitResult commitHomeRollForward(MinecraftServer server, HomeSessionRecord expected, HomeSessionRecord next) {
        requireServerThread(server); return commitHome(() -> flush(server),expected,next,false);
    }
    CommitResult commitHome(Runnable flush,HomeSessionRecord expected,HomeSessionRecord next,boolean restore) {
        if(closeLatch(expected,next))
            return CommitResult.of(CommitResult.Status.REJECTED,new IllegalStateException("HOME 結案只由回程終局 owner 提交"));
        return commitHomeEdge(flush,expected,next,restore);
    }
    private static boolean closeLatch(HomeSessionRecord expected,HomeSessionRecord next) {
        return expected.home().closeIntent() instanceof HomeSessionRecord.None && next.home().closeIntent() instanceof HomeSessionRecord.Close;
    }
    private CommitResult commitHomeEdge(Runnable flush,HomeSessionRecord expected,HomeSessionRecord next,boolean restore) {
        requireMutationThread(); requireHealthy(); var sid=expected.sessionUuid();
        if(!expected.equals(records.get(sid)) || !expected.equals(flushed.get(sid)) || quarantined.contains(sid)
                || pending!=null || terminated.contains(sid) || !HomeSessionRecord.edge(expected,next,restore)
                || next.state()==SessionState.COLLAPSING && expected.state()!=next.state() && !m5ReadyThisInstance)
            return CommitResult.of(CommitResult.Status.REJECTED,new IllegalStateException("HOME CAS 或提交邊不符"));
        try { stageHome(next,!restore); } catch(RuntimeException invalid) { return CommitResult.of(CommitResult.Status.REJECTED,invalid); }
        return finishHome(next,restore,flush);
    }
    /** M7 spec §9.2 :273：NONE→CLOSE 只接受回程終局 owner 在事件 guard 內發出的一次性提交（roll-forward）。 */
    public CommitResult latchHomeClose(MinecraftServer server, dev.quantumchamber.collapse.HomeClosure.CloseMint mint) {
        requireServerThread(server); return latchHomeClose(() -> flush(server),mint,server);
    }
    CommitResult latchHomeClose(Runnable flush, dev.quantumchamber.collapse.HomeClosure.CloseMint mint, MinecraftServer owner) {
        if(mint==null || !closeLatch(mint.expected(),mint.next()) || !mint.consume(owner))
            return CommitResult.of(CommitResult.Status.REJECTED,new IllegalStateException("沒有回程終局 owner 的結案提交"));
        return commitHomeEdge(flush,mint.expected(),mint.next(),false);
    }
    /** 結案列的移除只接受回程終局 owner 的一次性 proof（M7 spec §5.3）：B 門、原生 checkpoint 與 exact 收據都由 owner 核對過。 */
    public TerminalResult finishHomeClose(MinecraftServer server, HomeSessionRecord expected, dev.quantumchamber.collapse.HomeClosure.HomeClosureProof proof) {
        requireServerThread(server);
        HomeReceiptStore store;
        try { store=new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)); }
        catch(IOException failure) { return TerminalResult.of(TerminalResult.Status.REJECTED,new UncheckedIOException(failure)); }
        return finishHomeClose(store,() -> flush(server),expected,proof,server);
    }
    TerminalResult finishHomeClose(HomeReceiptStore store, Runnable flush, HomeSessionRecord expected,
            dev.quantumchamber.collapse.HomeClosure.HomeClosureProof proof, MinecraftServer owner) {
        if(!(expected.home().closeIntent() instanceof HomeSessionRecord.Close) || proof==null || !proof.consume(owner,expected))
            return TerminalResult.of(TerminalResult.Status.REJECTED,new IllegalStateException("沒有回程終局 owner 的移除 proof"));
        return terminateHome(store,flush,expected);
    }
    /**
     * 未選定 RETURNING 的完整返還收尾（plan Task 5 :308，T5-2）：只接受走廊 owner 的一次性 proof——全員已返回 B、走廊幾何已清除、
     * HOME 原生 checkpoint（{@link MeasuredWorldSaveCheckpoint#save(MinecraftServer,HomeSessionRecord)}）已在同一個 tick 成功之後，由
     * {@code CorridorPageManager} 建立。已選定或已結案的列一律拒絕（結案列只經 {@link #finishHomeClose}）。移除本身與 {@link #terminateHome} 共用同一個 checked 本體。
     */
    public TerminalResult finishHomeReturn(MinecraftServer server, HomeSessionRecord expected, dev.quantumchamber.corridor.CorridorPageManager.HomeReturnProof proof) {
        requireServerThread(server);
        if(expected.home().closeIntent() instanceof HomeSessionRecord.Close || !(expected.home().selection() instanceof HomeSessionRecord.Unselected)
                || proof==null || !proof.consume(server,expected))
            return TerminalResult.of(TerminalResult.Status.REJECTED,new IllegalStateException("沒有走廊 owner 的未選定返還 proof"));
        HomeReceiptStore store;
        try { store=new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)); }
        catch(IOException failure) { return TerminalResult.of(TerminalResult.Status.REJECTED,new UncheckedIOException(failure)); }
        return terminateHome(store,() -> flush(server),expected);
    }
    /**
     * HOME 列 checked 移除的共用本體（package-private）。外部 caller 不能自宣 HOME 列已完成：未選定的 RETURNING 只經 {@link #finishHomeReturn}（要求 HOME checkpoint 的 proof），
     * 已結案列只經 {@link #finishHomeClose}。T5-2 收窄成一律拒絕的 public overload 已在 T5-4 Step 0 移除（T5-2 規格 M-3、品質 M-6）。
     */
    TerminalResult terminateHome(HomeReceiptStore store,Runnable flush,HomeSessionRecord expected) {
        requireMutationThread(); requireHealthy(); var sid=expected.sessionUuid();
        if(!expected.equals(records.get(sid)) || !expected.equals(flushed.get(sid)) || quarantined.contains(sid)
                || pending!=null || rollForwardPending.contains(sid))
            return TerminalResult.of(TerminalResult.Status.REJECTED,new IllegalStateException("HOME 移除 CAS 不符"));
        try {
            if(expected.home().closeIntent() instanceof HomeSessionRecord.Close) {
                store.requireExact(expected);
            } else if(expected.state()!=SessionState.RETURNING || expected.participants().stream().anyMatch(p -> !p.returned()))
                throw new IOException("HOME 尚未完成返還或結案");
        } catch(IOException failure) { return TerminalResult.of(TerminalResult.Status.REJECTED,new UncheckedIOException(failure)); }
        records.remove(sid); markDirty(); var failure=checkedFlush(flush);
        if(!flushed.containsKey(sid)) {
            afterConfirmedWrite(sid,"HOME 終局",failure); authorityHistory.remove(sid); checkedAuthority.remove(sid);
            terminated.add(sid); confirmedFailureWarnings.remove(sid);
            return new TerminalResult(TerminalResult.Status.REMOVED,Optional.empty());
        }
        var cause=failure.orElseGet(() -> new IllegalStateException("HOME 終局讀回尚有該 SID"));
        if(!records.containsKey(sid) && !quarantined.contains(sid)) {
            restoreCheckedAuthority(sid); return TerminalResult.of(TerminalResult.Status.RESTORED,cause);
        }
        quarantine(sid); return TerminalResult.of(TerminalResult.Status.QUARANTINED,cause);
    }
    private void stageHome(HomeSessionRecord next,boolean forward) {
        var candidate=new LinkedHashMap<>(records); candidate.put(next.sessionUuid(),next);
        validateOwnership(candidate); requireRoom(candidate,next.sessionUuid());
        records.put(next.sessionUuid(),next); authorityHistory.put(next.sessionUuid(),next);
        if(forward) rollForwardPending.add(next.sessionUuid());
        markDirty();
    }
    private CommitResult finishHome(HomeSessionRecord next,boolean restore,Runnable flush) {
        var sid=next.sessionUuid(); var failure=checkedFlush(flush);
        if(next.equals(flushed.get(sid))) {
            afterConfirmedWrite(sid,"HOME "+next.state(),failure);
            return new CommitResult(CommitResult.Status.COMMITTED,Optional.empty());
        }
        var cause=failure.orElseGet(() -> new IllegalStateException("HOME 尚未 exact 讀回"));
        markDirty();
        if(!restore) return CommitResult.of(CommitResult.Status.ROLL_FORWARD_PENDING,cause);
        if(next.equals(records.get(sid)) && !quarantined.contains(sid)) {
            restoreCheckedAuthority(sid);
            return CommitResult.of(CommitResult.Status.RESTORED,cause);
        }
        quarantine(sid);
        return CommitResult.of(CommitResult.Status.QUARANTINED,cause);
    }
    public boolean participantsAvailable(java.util.Collection<UUID> players) {
        requireHealthy();
        return Stream.of(records,flushed,pendingSessions()).flatMap(rows -> rows.values().stream())
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
        if (Stream.of(records,flushed,authorityHistory).anyMatch(rows -> rows.get(record.sessionUuid()) instanceof HomeSessionRecord))
            throw new IllegalArgumentException("舊 session 不得取代 HOME");
        var current=(SessionRecoveryRecord)records.get(record.sessionUuid());
        var durable=(SessionRecoveryRecord)flushed.get(record.sessionUuid());
        var historical=(SessionRecoveryRecord)authorityHistory.get(record.sessionUuid());
        if (record.state().collapseState()) requireCollapseWrite(record,current,durable,historical,restoreCommit);
        // 同時保護 dirty、durable 與已移除紀錄的 authority。
        if ((current!=null && !SessionRecoveryRecord.sameAuthority(current,record))
                || (durable!=null && !SessionRecoveryRecord.sameAuthority(durable,record))
                || (historical!=null && !SessionRecoveryRecord.sameAuthority(historical,record))) {
            throw new IllegalArgumentException("同一 session 的凍結 authority 不可改動，ledger 與 selection 不可倒退");
        }
        var candidate = new LinkedHashMap<>(records); candidate.put(record.sessionUuid(), record);
        validateOwnership(candidate);
        requireRoom(candidate,record.sessionUuid());
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
        if(pending!=null && pending.prepared().schema()==6)
            throw new IllegalStateException("HOME journal 待收斂，不能移除現行占用");
        if(Stream.of(records.get(id),flushed.get(id),authorityHistory.get(id))
                .anyMatch(record -> record!=null && (record instanceof HomeSessionRecord || receiptRetained(record.state()))))
            throw new IllegalStateException("MEASURED 與塌縮狀態的 receipt 必須保留，不能經一般 remove 消耗（只有 checked 終局 API 可以移除）");
        if (records.remove(id) == null) return false;
        markDirty(); return true;
    }
    public void flush(MinecraftServer server) {
        requireServerThread(server);
        if (get(server) != this) throw new IllegalStateException("journal 不屬於此 server");
        checkedSave(path(server).toFile(), server.getRegistryManager());
    }
    /**
     * checked flush 的寫出：與原生存檔走同一個 {@link #save}，差別只在寫出失敗時——這裡照舊丟出，由 caller 以 flushed 判定失敗並退避；
     * 原生存檔則不丟出、保持未寫出的狀態（收據核對擋下時見 {@link #receiptHold}，T4 P1；其他 journal 寫入失敗見 {@link #reportNativeSaveFailure}，T4 P2）。
     * <p>覆蓋前提（T4 P2 審查 P2-M6 附註）：多數拒寫原因的 JUnit 只經這裡（checked）驗，原生路徑另有少數斷言；這成立是因為兩條路徑共用同一個 save 本體、
     * 只差失敗時丟不丟出。日後若把原生路徑分出另一個本體，要重新補上原生路徑的拒寫斷言。
     */
    void checkedSave(File file, RegistryWrapper.WrapperLookup lookup) {
        boolean outer = checkedWrite;
        checkedWrite = true;
        try { save(file, lookup); }
        finally { checkedWrite = outer; }
    }
    public NbtCompound writeNbt(NbtCompound nbt) {
        requireHealthy();
        var writable = writable();
        return encode(nbt,writable,writtenSchema(writable));
    }
    private NbtCompound encode(NbtCompound nbt, Map<UUID,JournalSession> writable, int schema) {
        nbt.putInt("SchemaVersion", schema);
        var entries = new NbtList(); writable.values().forEach(record -> entries.add(record instanceof HomeSessionRecord home ? HomeJournalCodec.encode(home) : ((SessionRecoveryRecord)record).toNbt(Math.min(5,schema))));
        nbt.put("Records", entries); return nbt;
    }
    /**
     * 已確認 schema5 永不降版；首次 M6 Context 寫出另守初始化與未知寫入。
     * 其餘維持舊式子：legacy → 2；candidateInitialized → (m5Evidence ? 4 : 3)；空且未初始化 → 2。
     */
    private int writtenSchema(Map<UUID, JournalSession> writable) {
        // envelopeSchema 先驗證不混裝 legacy 與 candidate（混裝時丟例外，寫出因此失敗）；它的回傳值（有 candidate → 3，否則 2）
        // 只在最後一行使用。
        int envelope = envelopeSchema(writable);
        if(schemaSixEvidence || writable.values().stream().anyMatch(HomeSessionRecord.class::isInstance)) return 6;
        var legacy=legacyRows(writable);
        if (schemaFiveEvidence) {
            if (legacy.values().stream().anyMatch(record -> record.candidateContext().isEmpty()))
                throw new IllegalStateException("schema5 不可降回無 Context 的 legacy session");
            return 5;
        }
        if (legacy.values().stream().anyMatch(record -> record.candidateContext().map(context -> context.isM6()).orElse(false))) {
            if (!m5ReadyThisInstance || legacyWriteUnconfirmed || !rollForwardPending.isEmpty())
                throw new IllegalStateException("首次 schema5 必須完成 M5 初始化且沒有其他未確認寫入");
            // 先從 durable 集合確認每筆舊 authority 仍原樣存在；只巡 writable 會漏掉未 flush 的 remove。
            for (var confirmed : flushed.entrySet()) {
                if (!Objects.equals(confirmed.getValue(), writable.get(confirmed.getKey())))
                    throw new IllegalStateException("首次 schema5 不可併入尚未 checked 的舊 session 移除或變更");
            }
            for (var record : legacy.values()) {
                if (!record.candidateContext().orElseThrow().isM6() && !flushed.containsKey(record.sessionUuid()))
                    throw new IllegalStateException("首次 schema5 不可夾帶其他未確認 candidate authority");
            }
            return 5;
        }
        if (legacy.values().stream().anyMatch(record -> record.candidateContext().isEmpty())) return 2;
        if (candidateInitialized) return m5Evidence ? 4 : 3;
        // 空 journal → 2。有 candidate record 時 candidateInitialized 必為真，走不到這裡；仍沿用 envelope 判斷，不寫出錯誤的 2。
        return envelope;
    }
    /** 所有寫出（checked flush、原生 autosave／stop）共用：隔離 session 只寫上一份 flushed authority，其他 session 照常。 */
    private Map<UUID, JournalSession> writable() {
        if (quarantined.isEmpty()) return records;
        var result = new LinkedHashMap<UUID, JournalSession>();
        records.forEach((id, record) -> {
            var checked = quarantined.contains(id) ? flushed.get(id) : record;
            if (checked != null) result.put(id, checked);
        });
        for(var id:quarantined) if(!result.containsKey(id) && flushed.containsKey(id)) result.put(id,flushed.get(id));
        return result;
    }
    @Override public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) { return writeNbt(nbt); }

    @Override public void save(File file, RegistryWrapper.WrapperLookup lookup) {
        requireMutationThread();
        if (installation != null) {
            Object manager = owner == null ? installation.manager.get() : owner.getOverworld().getPersistentStateManager();
            requireOwned(installationServer, manager, installation, this);
        } else if (owner != null) throw new IllegalStateException("JOURNAL_UNINSTALLED_OWNER");
        // 以上（執行緒與 journal owner 的核對）是程式錯誤的守門，原生存檔照舊丟出（T4 P2 controller 的決定）。以下任何 journal 寫入失敗：checked flush 照舊丟出；
        // 原生存檔（autosave、/save-all、停服）不丟進原版存檔鏈（T4 P2，使用者 2026-11-17 裁示），保持未寫出的狀態與 M5 的 pending／dirty 語意，見 reportNativeSaveFailure。
        var stage = NativeSaveFailure.UNHEALTHY;
        try {
            requireHealthy();
            // 即使 caller 已清 dirty，也不能藉 autosave／stop 或 cache hit 丟棄未確認的 attempt。
            if (pending != null) {
                stage = NativeSaveFailure.PENDING;
                try { reconcilePending(file.toPath().toAbsolutePath().normalize()); }
                catch (IOException | RuntimeException failure) {
                    throw new UncheckedIOException("journal pending 未確認，拒絕覆寫", failure instanceof IOException io
                            ? io : new IOException("journal pending 解析失敗", failure));
                }
            }
            if (!isDirty()) return;
            stage = NativeSaveFailure.PREPARE;
            var wrapped = new NbtCompound(); wrapped.put("data", writeNbt(new NbtCompound()));
            NbtHelper.putDataVersion(wrapped);
            var expected = Map.copyOf(writable());
            try {
                if (wrapped.getCompound("data").getInt("SchemaVersion") >= 5) {
                    var candidate = new SessionRecoveryState[1];
                    var prepared = SessionJournalStore.prepareSchemaFive(wrapped, data -> {
                        var decoded = fromNbt(data, intentValidator); decoded.requireHealthy();
                        if (!expected.equals(decoded.allSessions())) throw new IllegalStateException("schema"+wrapped.getCompound("data").getInt("SchemaVersion")+" preflight records 不符");
                        candidate[0] = decoded;
                    });
                    var target = file.toPath().toAbsolutePath().normalize();
                    if(prepared.schema()==6) {
                        var hold=receiptHold(target,expected);
                        if(hold!=null) {
                            // T4 P1：原生存檔（autosave、/save-all、停服）不把收據不符丟進原版存檔鏈：這次不寫出、保持 dirty，原版照常存完其他
                            // PersistentState 與各世界。checked flush 照舊丟出（caller 回報失敗並退避）。
                            if(!checkedWrite) return;
                            throw hold;
                        }
                    }
                    boolean sameCheckedPath = target.equals(checkedPath);
                    pending = new PendingAttempt(target, prepared, candidate[0].allFlushedSessions(),
                            sameCheckedPath ? checkedWrapped : null, flushed, sameCheckedPath && checkedAbsent);
                    stage = NativeSaveFailure.WRITE;
                    try { SessionJournalStore.writePrepared(file.toPath(), prepared); }
                    catch (SessionJournalStore.WriteFailure failure) {
                        if (!failure.possiblyMoved()) pending = null;
                        throw failure;
                    }
                } else {
                    stage = NativeSaveFailure.WRITE;
                    legacyWriteUnconfirmed = true;
                    SessionJournalStore.write(file.toPath(), wrapped);
                }
                stage = NativeSaveFailure.READBACK;
                var actual = readback.read(file.toPath());
                var decoded = fromNbt(actual.getCompound("data"), intentValidator);
                decoded.requireHealthy();
                if (!wrapped.equals(actual) || !expected.equals(decoded.allSessions())) {
                    throw new IOException("journal 正式檔 exact readback 不符");
                }
                var checkedCopy = actual.copy();
                flushed = decoded.allFlushedSessions();
                authorityHistory.putAll(flushed);
                checkedAuthority.putAll(flushed);
                candidateInitialized |= actual.getCompound("data").getInt("SchemaVersion") >= 3;
                schemaFiveEvidence |= actual.getCompound("data").getInt("SchemaVersion") >= 5;
                schemaSixEvidence |= actual.getCompound("data").getInt("SchemaVersion") == 6;
                legacyWriteUnconfirmed = false;
                checkedWrapped = checkedCopy; checkedPath = file.toPath().toAbsolutePath().normalize(); checkedAbsent = false;
                pending = null;
                // roll-forward 尚未確認的 session：讀回確認 flushed 已等於 current 時清除。
                rollForwardPending.removeIf(sid -> Objects.equals(flushed.get(sid), records.get(sid)));
                // T4 B2b-1（P2-M2）：讀回確認的成功寫出結束原生存檔的失敗期間；之後再發生同類失敗仍會再記一則 ERROR。
                nativeSaveFailureReported.clear();
            } catch (IOException | RuntimeException exception) {
                // T4 B2b-1（P2-M5）：checked 路徑的訊息不變（丟出同一個物件）；原生存檔的 ERROR 附這個 cause，不帶 checked 的用語。
                throw new UncheckedIOException(checkedWrite ? "journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot"
                        : "journal 原生存檔寫出失敗，保留 dirty 與上一個 durable snapshot",
                        exception instanceof IOException io ? io : new IOException("journal strict readback 失敗", exception));
            }
            setDirty(false);
        } catch (RuntimeException failure) {
            if (checkedWrite) throw failure;
            reportNativeSaveFailure(stage, file, failure);
        }
    }

    /**
     * T4 P2：原生存檔的 journal 寫入失敗類別（{@link #save} 失敗時所在的階段；收據不符另由 {@link #receiptHold} 處理）。
     * 不丟出之後各類保持的狀態與收斂者：
     * <ul>
     * <li>{@code UNHEALTHY}：開服時讀不到 journal；本 instance 永不寫出，管理員修檔後重啟收斂。</li>
     * <li>{@code PENDING}：上一次寫出的結果尚未確認、核對磁碟失敗；未確認的寫入不覆寫。核對失敗後本 instance 停寫（M5 既有），重啟後由開服 strict 載入收斂。</li>
     * <li>{@code PREPARE}：編碼或寫出前檢查（schema、strict decode、preflight、預算）失敗；尚未建立 pending，保持 dirty。</li>
     * <li>{@code WRITE}：寫入正式檔失敗（例如 Windows atomic move 的 AccessDenied）；possiblyMoved 時正式檔可能已被替換、保留 pending，下一次寫出先核對磁碟，
     *     否則保持 dirty。</li>
     * <li>{@code READBACK}：正式檔已替換但讀回失敗或不符；保留 pending，下一次寫出核對磁碟時依 M5 既有規則判定需要重啟（legacy schema ≤4 沒有 pending，下一次存檔重寫）。</li>
     * </ul>
     * 後三類由下一次原生存檔與 recovery 的 journal 退避重試（{@code SessionRecoveryManager}）收斂；不新增短暫重試。
     */
    private enum NativeSaveFailure {
        UNHEALTHY("journal 不健康（開服時無法讀取）"),
        PENDING("上一次寫出的結果尚未確認（pending），核對磁碟失敗"),
        PREPARE("編碼或寫出前檢查失敗"),
        WRITE("寫入正式檔失敗"),
        READBACK("正式檔讀回失敗或與寫出的內容不符");

        final String text;
        NativeSaveFailure(String text) { this.text = text; }
    }

    /**
     * T4 P2：原生存檔的 journal 寫入失敗不丟進原版存檔鏈（原版照常存完其他 PersistentState、level.dat 與各世界，停服照常完成），只記 ERROR。
     * 同一段失敗期間每一類只記一次，不刷屏；讀回確認的成功寫出結束這段期間（T4 B2b-1，P2-M2），之後再發生同類失敗仍再記一則。不屬於任何 session，帶
     * {@code CollapseService.TICK_LOG_MARKER}（父 marker 是 {@code LOG_MARKER}）並附 cause。
     * 訊息依失敗的階段說明這次的結果（T4 B2b-1，P2-M5：結果不明的寫出與讀回失敗時正式檔可能已被替換，不寫成「這次沒有寫出」），再依失敗後的狀態說明收斂方式。
     */
    private void reportNativeSaveFailure(NativeSaveFailure kind, File file, RuntimeException failure) {
        if (!nativeSaveFailureReported.add(kind)) return;
        String outcome;
        if (kind == NativeSaveFailure.READBACK) outcome = "journal 這次沒有確認寫出：正式檔已被替換，但讀回失敗或與寫出的內容不符（結果未確認）";
        else if (kind == NativeSaveFailure.WRITE && pending != null) outcome = "journal 這次沒有確認寫出：atomic move 可能已經完成，正式檔可能已被替換（結果未確認）";
        else outcome = "journal 這次沒有寫出";
        String convergence;
        if (loadError != null) convergence = "本 instance 不會寫出 journal；需要修復或移走損壞的 journal 檔，再重啟伺服器才能收斂";
        else if (pendingRestartRequired) convergence = "已無法證實磁碟上的權威，本 instance 不再寫出 journal（未確認的寫入不會被覆寫）；需要重啟伺服器才能收斂（開服 strict 載入）";
        else if (pending != null && kind == NativeSaveFailure.READBACK) convergence = "保留 pending：正式檔已是這份未確認的寫出，下一次寫出（原生存檔，或 recovery 的 journal 退避重試）"
                + "核對磁碟時會判定需要重啟伺服器才能收斂（開服 strict 載入），本 instance 之後不再寫出 journal，未確認的寫入不會被覆寫";
        else if (pending != null) convergence = "由保留的 pending 收斂：下一次寫出（原生存檔，或 recovery 的 journal 退避重試）會先核對磁碟，仍是上一份權威才重寫；"
                + "正式檔若已被這份未確認的寫出替換，本 instance 停寫、需要重啟伺服器，未確認的寫入不會被覆寫";
        else convergence = "保持 dirty：由下一次原生存檔或 recovery 的 journal 退避重試收斂";
        org.slf4j.LoggerFactory.getLogger("quantumchamber").error(dev.quantumchamber.collapse.CollapseService.TICK_LOG_MARKER,
                "原生存檔（autosave、/save-all 或停服）的 journal 寫出失敗：{}：不丟進原版存檔流程，原版照常存完其他資料與各世界；{}"
                        + "（dirty={}，pending={}）；{}。同一段失敗期間這一類只記一次（讀回確認的成功寫出之後再發生時會再記）：path={}：{}",
                kind.text, outcome, isDirty(), pending != null, convergence, file.toPath().toAbsolutePath().normalize(), FailureText.describe(failure), failure);
    }

    /**
     * 寫出前逐列核對同 SID 的收據（T4 P1；M7 spec §5.3 :175、§10 :307「錯配或未知維持鎖與原件」；t4b2a-review P1）。開服 strict 載入另以
     * {@link #verifyReceipts} 核對全部列，不在這裡。
     * <ul>
     * <li>本次有變動的列（不等於 {@link #flushed}，或 target 不是上一次 checked 讀回的同一個檔）與收據不符時，回傳擋下這次寫出的原因：
     *     寫出的 bytes 永不含「本次有變動、而且與收據不符」的列。</li>
     * <li>未變動、已在 target 上的列與收據不符時不擋：照原樣重寫不會擴大不一致，其他 SID 的進度照常落盤。</li>
     * <li>兩種情形都不改那一列與收據原件（熱列仍在，同艙仍占用），記管理員 ERROR（{@link #reportReceiptMismatch}；同一段不符期間每個 SID 的每一類只記一次，
     *     這個 SID 的核對通過時清除，見 {@link #clearReceiptReports}）。</li>
     * </ul>
     * 無法建立收據 store（存檔根無法解析）時，每一列都當成無法核對處理。
     * @return 擋下寫出的原因；可以寫出時為 null
     */
    private IOException receiptHold(Path target, Map<UUID, JournalSession> rows) {
        if (rows.isEmpty()) return null;
        boolean onDisk = target.equals(checkedPath) && !checkedAbsent;
        var saveRoot = target.getParent().getParent();
        HomeReceiptStore store = null;
        IOException unavailable = null;
        try { store = new HomeReceiptStore(saveRoot); }
        catch (IOException failure) { unavailable = failure; }
        IOException hold = null;
        for (var row : rows.values()) {
            Exception failure = unavailable;
            if (store != null) {
                try { store.verifyActive(row); clearReceiptReports(row.sessionUuid()); continue; }
                catch (IOException | RuntimeException mismatch) { failure = mismatch; }
            }
            var sid = row.sessionUuid();
            boolean unchanged = onDisk && row.equals(flushed.get(sid));
            reportReceiptMismatch(new ReceiptReport(sid, receiptReadable(store, row), unchanged), saveRoot, failure);
            if (unchanged) continue;
            var blocked = new IOException("回程收據與本次要寫出的熱列不符：session=" + sid + "：" + failure.getMessage(), failure);
            if (hold == null) hold = blocked; else hold.addSuppressed(blocked);
        }
        return hold;
    }

    /** 收據 ERROR 的去重鍵（T4 P2，P1-M1）：SID 加類別——收據讀得出而內容不符（{@code mismatch}）或無法核對、這一列未變動或本次有變動。 */
    private record ReceiptReport(UUID sid, boolean mismatch, boolean unchanged) {}

    /**
     * T4 B2b-2（B2b1-M2）：這個 SID 的收據核對通過（問題真的解除，例如收據已還原或移走）時，清除它全部類別的去重鍵；之後同一個 SID 再出問題會再記一則。
     * 不以「寫出成功」清除：未變動的不符列每次存檔都照原樣寫出成功，那樣會每次存檔都重記。不符期間核對一直失敗，不會清除，所以仍只記一則；只清通過核對的那個 SID。
     */
    private void clearReceiptReports(UUID sid) {
        if (!receiptMismatchReported.isEmpty()) receiptMismatchReported.removeIf(report -> report.sid().equals(sid));
    }

    /**
     * {@link #receiptHold} 核對失敗之後才分類（不在成功路徑多讀檔）：再以 {@link HomeReceiptStore#lookup} 讀一次。讀得出、而且不是這一列的結案快照
     * （或這一列尚未結案）才算「不符」；讀不出（IO 錯誤、權限、路徑不是一般檔案、內容無法解析）、檔案不見，或讀出的內容等於這一列（第一次的失敗來自讀取，
     * 或只差 DataVersion 等位元組層級的不同）都算「無法核對」。ERROR 如實寫「無法讀取或解析」，不寫成外部竄改，也不宣稱收據沒有被改過（T4 B2b-1，P2-M5）。
     */
    private static boolean receiptReadable(HomeReceiptStore store, JournalSession row) {
        if (store == null) return false;
        java.util.Optional<HomeSessionRecord> stored;
        try { stored = store.lookup(row.sessionUuid()); }
        catch (IOException | RuntimeException unreadable) { return false; }
        if (stored.isEmpty()) return false;
        if (!(row instanceof HomeSessionRecord h) || !(h.home().closeIntent() instanceof HomeSessionRecord.Close)) return true;
        return !stored.get().equals(h);
    }

    /**
     * {@link #receiptHold} 的管理員 ERROR：帶 {@code CollapseService.LOG_MARKER} 與 {@code session=<SID>}，附收據檔路徑（{@link HomeReceiptStore#receiptPath}）、
     * 處理方式與 cause；同一段不符期間每個 SID 的每一類（{@link ReceiptReport}）只記一次，這個 SID 的核對通過時清除（T4 B2b-2，B2b1-M2）。
     * 本次有變動的那一類會擋下整份 journal 的寫出，訊息註明全服 journal 寫入已暫停。
     */
    private void reportReceiptMismatch(ReceiptReport report, Path saveRoot, Exception failure) {
        if (!receiptMismatchReported.add(report)) return;
        var receipt = HomeReceiptStore.receiptPath(saveRoot, report.sid()).toAbsolutePath().normalize();
        String kind = report.mismatch() ? "與熱列不符（外部竄改、損壞或備份錯拼）"
                : "無法讀取或解析（例如檔案被其他程式鎖定、權限不足、路徑不是一般檔案、內容無法解析，或與這一列只差 DataVersion 等格式；"
                        + "可能只是讀取問題，也可能是損壞或其他版本寫出的收據）";
        String scope = report.unchanged() ? "這一列未變動，journal 照原樣寫出，其他旅程不受影響"
                : "這一列本次有變動，本次不寫出 journal、保持 dirty；處理之前全服 journal 寫入已暫停（原生存檔不寫出 journal，所有旅程的 checked 提交都會失敗並退避）";
        String handling = report.mismatch() ? "確認收據檔是否屬於這一列，刪除它或還原成原本發佈的那一份，下一次存檔或 owner 重試就會恢復，不必重啟"
                : "先排除讀取問題（解除鎖定、修正權限）；不是讀取問題時，確認收據檔是否屬於這一列，刪除它或還原成原本發佈的那一份。下一次存檔或 owner 重試就會恢復，不必重啟";
        org.slf4j.LoggerFactory.getLogger("quantumchamber").error(dev.quantumchamber.collapse.CollapseService.LOG_MARKER,
                "回程收據{}：保留熱列、收據原件與占用，需要管理員處理（{}）：session={}：收據檔={}：處理方式：{}：{}",
                kind, scope, report.sid(), receipt, handling, FailureText.describe(failure), failure);
    }

    /** 只讀收斂：原 checked exact 才解除；原 attempt exact 也不得在本 instance 復活已 abort 的 current。 */
    private void reconcilePending(Path target) throws IOException {
        if (pendingRestartRequired) throw new IOException("JOURNAL_PENDING_RESTART_REQUIRED");
        var attempt = pending;
        if (!attempt.target().equals(target)) throw new IOException("JOURNAL_PENDING_TARGET_MISMATCH");
        // 第一次核對若無法證實舊權威，此 instance 之後不再重讀或寫入。
        pendingRestartRequired = true;
        requireExactOldAuthority(target, attempt);
        pendingRestartRequired = false;
        pending = null;
    }

    private void requireExactOldAuthority(Path target, PendingAttempt attempt) throws IOException {
        NbtCompound actual;
        try { actual = readback.read(target); }
        catch (NoSuchFileException missing) {
            if (attempt.oldAbsent()) return;
            throw new IOException("JOURNAL_PENDING_MISSING_OLD", missing);
        } catch (IOException | RuntimeException unreadable) {
            throw new IOException("JOURNAL_PENDING_UNREADABLE", unreadable);
        }
        SessionRecoveryState decoded;
        try {
            decoded = fromNbt(actual.getCompound("data"), intentValidator); decoded.requireHealthy();
        } catch (RuntimeException unreadable) { throw new IOException("JOURNAL_PENDING_UNREADABLE", unreadable); }
        if (attempt.oldWrapped() != null && attempt.oldWrapped().equals(actual)
                && attempt.oldRecords().equals(decoded.allSessions())) return;
        if (actual.getCompound("data").getInt("SchemaVersion") == attempt.prepared().schema()
                && attempt.prepared().wrapped().equals(actual) && attempt.expectedRecords().equals(decoded.allSessions()))
            throw new IOException("JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED");
        throw new IOException("JOURNAL_PENDING_AUTHORITY_UNKNOWN");
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
    public static boolean occupies(JournalSession row, UUID player) {
        if(row instanceof SessionRecoveryRecord old) return occupies(old,player);
        return row.participants().stream().anyMatch(p -> p.playerUuid().equals(player) && switch(row.state()) {
            case ARMING, SUPERPOSITION, RETURNING -> true;
            case MEASURED -> !row.spaceLeases().isEmpty();
            case COLLAPSING,PASSAGE_READY,SEALED -> !p.returned();
        });
    }
    private void requireRoom(Map<UUID,JournalSession> candidate,UUID changedSid) {
        if(pending!=null && pending.prepared().schema()==6 && !candidate.equals(records))
            throw new IllegalStateException("HOME journal 有尚未確認的寫入，保留候選身分與租約");
        if(!schemaSixEvidence && candidate.values().stream().noneMatch(HomeSessionRecord.class::isInstance)) return;
        var proposed=candidate.get(changedSid);
        if(proposed!=null) {
            // 已確認與未確認各版本獨立驗證，同 SID 的兩份權威不互斥。
            for(var snapshot:List.of(records,flushed,pendingSessions())) for(var other:snapshot.values()) {
                if(other.sessionUuid().equals(changedSid)) continue;
                if(proposed.chamberUuid().equals(other.chamberUuid())
                        || proposed.participants().stream().anyMatch(p -> occupies(proposed,p.playerUuid()) && occupies(other,p.playerUuid())))
                    throw new IllegalStateException("其他旅程仍占用原艙或玩家");
                for(var a:proposed.spaceLeases()) for(var b:other.spaceLeases())
                    if(a.slotId()==b.slotId() || a.bounds().intersects(b.bounds()))
                        throw new IllegalStateException("其他旅程仍持有本次要求的租約");
            }
        }
        // 同 SID 的相同實體租約跨快照只計一次；尚未確認釋放的舊租約仍計入總量。
        var held=new LinkedHashMap<UUID,java.util.Set<SessionRecoveryRecord.SpaceLease>>();
        for(var snapshot:List.of(candidate,records,flushed,pendingSessions())) {
            snapshot.forEach((sid,row) -> held.computeIfAbsent(sid,id -> new HashSet<>()).addAll(row.spaceLeases()));
        }
        var physical=held.values().stream().flatMap(java.util.Set::stream).toList();
        dev.quantumchamber.corridor.SlotAllocator.validateSavedLeases(physical);
        if (owner!=null) dev.quantumchamber.corridor.CorridorPageManager.validateJournalCoverage(owner,physical);
        var wrapped=new NbtCompound(); NbtHelper.putDataVersion(wrapped);
        wrapped.put("data",encode(new NbtCompound(),candidate,6));
        HomeJournalBudget.requireReserved(wrapped);
    }
    private static boolean dormant(SessionRecoveryRecord record) {
        return record.state() == dev.quantumchamber.superposition.SessionState.MEASURED
                && MeasuredRecoveryPhase.from(record) == MeasuredRecoveryPhase.DORMANT;
    }
    private static void validateOwnership(Map<UUID, JournalSession> records) {
        envelopeSchema(records);
        var chambers = new HashSet<UUID>(); var players = new HashSet<UUID>(); var slots = new HashSet<Integer>();
        for (var record : records.values()) {
            if (!chambers.add(record.chamberUuid())) throw new IllegalArgumentException("來源艙已有 session");
            // 玩家唯一性只約束仍佔用玩家的 session（occupies）：DORMANT 與塌縮狀態的已返還參與者不佔用；Chamber 的唯一性不變。
            for (var person : record.participants())
                if (occupies(record, person.playerUuid()) && !players.add(person.playerUuid())) throw new IllegalArgumentException("玩家跨 session 重複");
            for (var lease : record.spaceLeases()) if (!slots.add(lease.slotId())) throw new IllegalArgumentException("slot 跨 session 重複");
        }
        if(records.values().stream().anyMatch(HomeSessionRecord.class::isInstance))
            dev.quantumchamber.corridor.SlotAllocator.validateSavedLeases(records.values().stream()
                    .flatMap(row -> row.spaceLeases().stream()).toList());
    }
    private static int envelopeSchema(Map<UUID, JournalSession> records) {
        boolean legacy = false; boolean candidate = false;
        for (var record : records.values()) {
            if (record instanceof HomeSessionRecord || ((SessionRecoveryRecord)record).candidateContext().isPresent()) candidate = true; else legacy = true;
        }
        if (legacy && candidate) throw new IllegalArgumentException("同一 journal envelope 不可混裝 legacy 與 candidate sessions");
        return candidate ? 3 : 2;
    }
}
