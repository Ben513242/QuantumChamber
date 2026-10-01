package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 的 checked 權威（spec §6）：每個 server instance 一份，持有 allocation ledger（§6.1）與 projection ledger（§6.5）的已確認內容、
 * 收斂旗標與以權威為單位的退避。只在 server thread 使用。
 * <ul>
 * <li><b>初始化</b>（{@link #initialize(MinecraftServer)}）：開服時（{@code SERVER_STARTED}，註冊順序早於 lifecycle bootstrap 物化）執行一次。
 *     只看 journal 的 flushed envelope：還不是 schema 4 時，缺少的 ledger 以 checked 方式建立為空檔；已是 schema 4 時，缺少任何一個就是遺失
 *     （{@link SealedReason#AUTHORITY_CORRUPT}），不建檔、不補寫。成功才呼叫 {@link SessionRecoveryState#markCollapseAuthoritiesInitialized()}；
 *     任何原因的失敗都不呼叫，並記 ERROR。</li>
 * <li><b>讀取失敗的分類</b>：讀到原始 bytes 之後的失敗（壓縮格式、NBT、strict decode）與確定遺失為 {@code AUTHORITY_CORRUPT}；讀檔時的 IO 例外為
 *     {@code AUTHORITY_UNREADABLE}。</li>
 * <li><b>寫入</b>：同目錄暫存檔、force、以正式讀取規則驗證暫存檔、atomic move、strict 讀回並 exact 比對（沿用 M4 discovery 的寫法）。
 *     寫入或讀回失敗時 roll-forward（spec §6）：current 保留這次的新內容，權威標為未確認；未確認期間這個權威的查詢與寫入一律回「未確認」，
 *     不做新決策，由 {@link #convergeIfNeeded} 依 {@link CollapseBackoff} 退避重試 checked 重寫。</li>
 * </ul>
 * 兩個 ledger 都不是 PersistentState，原生 autosave 與停服不會寫它們；收斂完成之前 crash，重啟後讀到的舊內容或新內容都合法（spec §6 的表）。
 */
public final class CollapseAuthorities {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private static final Map<MinecraftServer, CollapseAuthorities> INSTANCES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static boolean registered;
    /** ledger 檔（壓縮後）讀入記憶體的上限；超過視為內容損壞。 */
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    /** 解壓後 NBT 的配置預算（與 M4 discovery 相同）。 */
    static final long MAX_NBT_BYTES = 16L * 1024 * 1024;

    /** 收斂與未確認旗標的單位。F2c 會加入 discovery 與 catalog。 */
    public enum Authority { ALLOCATION_LEDGER, PROJECTION_LEDGER }

    /**
     * 決策 9 的 package-private IO port：讀取 ledger 檔（正式檔或暫存檔）的原始 bytes。初始化的載入、寫入前的暫存檔驗證、atomic move 之後的讀回
     * 都經過它，pure JVM 測試以它注入 IO 例外與「atomic move 完成、讀回失敗」。檔案不存在必須丟 {@link NoSuchFileException}。
     */
    @FunctionalInterface interface LedgerIo { byte[] read(Path path) throws IOException; }

    /** 初始化的結果。 */
    public record InitResult(Outcome allocation, Outcome projection, boolean flushedSchemaFour, Optional<RuntimeException> failure) {
        public enum Outcome {
            /** 既有檔 strict 讀取成功。 */
            LOADED,
            /** journal 還不是 schema 4、檔案不存在：已 checked 建立空檔。 */
            CREATED,
            /** journal 還不是 schema 4、檔案不存在，但因為其他失敗而沒有建立。 */
            NOT_CREATED,
            /** journal 已是 schema 4、檔案不存在：遺失，不建檔、不補寫。 */
            LOST,
            /** 讀到原始 bytes 之後的解析或 strict decode 失敗。 */
            CORRUPT,
            /** 讀檔，或建立空檔時的 IO 例外（含讀回失敗）。 */
            UNREADABLE,
            /** journal 不健康，沒有讀取。 */
            NOT_EXAMINED
        }

        public InitResult {
            Objects.requireNonNull(allocation, "allocation");
            Objects.requireNonNull(projection, "projection");
            Objects.requireNonNull(failure, "failure");
            // 兩個 ledger 都可用但仍有 failure：標記 journal 失敗。任一 ledger 不可用時一定有 failure。
            if (!(usable(allocation) && usable(projection)) && failure.isEmpty()) throw new IllegalArgumentException("初始化失敗時必須帶 failure");
        }

        /** 兩個 ledger 都可用，而且已呼叫 {@link SessionRecoveryState#markCollapseAuthoritiesInitialized()}。 */
        public boolean succeeded() { return failure.isEmpty(); }

        /**
         * 初始化失敗時，UNIVERSE 目的地到 RESOLVE 的 SEALED 原因（spec §6）：任一 ledger 遺失或損壞為 {@code AUTHORITY_CORRUPT}（永久，
         * 下次開服仍會失敗）；否則（IO 例外、journal 不健康）為 {@code AUTHORITY_UNREADABLE}。成功時為空。
         */
        public Optional<SealedReason> sealedReason() {
            if (succeeded()) return Optional.empty();
            boolean corrupt = broken(allocation) || broken(projection);
            return Optional.of(corrupt ? SealedReason.AUTHORITY_CORRUPT : SealedReason.AUTHORITY_UNREADABLE);
        }

        private static boolean usable(Outcome outcome) { return outcome == Outcome.LOADED || outcome == Outcome.CREATED; }
        private static boolean broken(Outcome outcome) { return outcome == Outcome.LOST || outcome == Outcome.CORRUPT; }
    }

    /**
     * 查詢（與 allocation 的追加）結果。成功的三種狀態沒有 SEALED 原因與 failure；其餘都帶著可直接用於 SEALED 的原因（F4a／F4b）與 failure。
     * @param <T> entry 型別
     */
    public record Lookup<T>(Status status, Optional<T> value, Optional<SealedReason> sealedReason, Optional<RuntimeException> failure) {
        public enum Status {
            /** 找到 entry：allocation 為 exact 等於本 session 會寫出的內容；projection 為該 (chamber, UniverseId) 的已確認 entry。 */
            FOUND,
            /** allocation：沒有 entry，本次 checked 追加並讀回確認。 */
            APPENDED,
            /** projection：已確認的 ledger 沒有這個 (chamber, UniverseId)。 */
            ABSENT,
            /** 權威有尚未確認的 roll-forward 寫入：不做新決策，先 {@link CollapseAuthorities#convergeIfNeeded}。 */
            UNCONFIRMED,
            /** 本次追加的 checked 寫入或讀回失敗：current 保留它，權威標為未確認。 */
            WRITE_FAILED,
            /** 遺失、損壞、既有紀錄不是本 session 會寫出的內容、UniverseId 已屬於另一個 token、已達上限，或初始化因此失敗。 */
            CORRUPT,
            /** 初始化時的 IO 例外（或 journal 不健康）；下次開服重新初始化。 */
            UNREADABLE
        }

        public Lookup {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sealedReason, "sealedReason"); Objects.requireNonNull(failure, "failure");
            boolean ok = status == Status.FOUND || status == Status.APPENDED || status == Status.ABSENT;
            if (ok == sealedReason.isPresent() || ok == failure.isPresent()) throw new IllegalArgumentException("只有失敗的結果帶 SEALED 原因與 failure");
            if ((status == Status.FOUND || status == Status.APPENDED) != value.isPresent()) throw new IllegalArgumentException("只有 FOUND／APPENDED 帶 entry");
        }

        public boolean succeeded() { return sealedReason.isEmpty(); }

        static <T> Lookup<T> found(T value) { return new Lookup<>(Status.FOUND, Optional.of(value), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> appended(T value) { return new Lookup<>(Status.APPENDED, Optional.of(value), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> absent() { return new Lookup<>(Status.ABSENT, Optional.empty(), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> failed(Status status, SealedReason reason, RuntimeException failure) {
            return new Lookup<>(status, Optional.empty(), Optional.of(reason), Optional.of(failure));
        }
    }

    /** projection 的寫入結果。WRITTEN／UNCHANGED 帶 entry；INVALID_TRANSITION 是 caller 的錯誤（沒有 SEALED 原因）；其餘帶 SEALED 原因。 */
    public record WriteResult(Status status, Optional<ProjectionLedger.Entry> entry, Optional<SealedReason> sealedReason,
            Optional<RuntimeException> failure) {
        public enum Status {
            /** checked 寫入並讀回確認；entry 為寫入的內容。 */
            WRITTEN,
            /** 既有 entry 已是同一份內容（同 site、同 Status），沒有寫入；entry 為既有內容。 */
            UNCHANGED,
            /**
             * Status 只能 PENDING→COMPLETE（spec §6.5）：新 key 直接寫 COMPLETE，或把既有 COMPLETE 寫回 PENDING。沒有寫入；既有 entry
             * （若有）放在 entry，例如已 COMPLETE 時 caller 應改走修復路徑（§9.2）。
             */
            INVALID_TRANSITION,
            /** 權威有尚未確認的 roll-forward 寫入：不寫入，先 {@link CollapseAuthorities#convergeIfNeeded}。 */
            UNCONFIRMED,
            /** 本次的 checked 寫入或讀回失敗：current 保留它，權威標為未確認（投影方塊只在 PENDING 確認之後才寫）。 */
            WRITE_FAILED,
            /** 遺失、損壞、既有 entry 的 Role／ControllerPos／OutwardFacing 與本 session 的來源艙不符、已達上限，或初始化因此失敗。 */
            CORRUPT,
            /** 初始化時的 IO 例外（或 journal 不健康）。 */
            UNREADABLE
        }

        public WriteResult {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(entry, "entry");
            Objects.requireNonNull(sealedReason, "sealedReason"); Objects.requireNonNull(failure, "failure");
            boolean ok = status == Status.WRITTEN || status == Status.UNCHANGED;
            if (ok == failure.isPresent()) throw new IllegalArgumentException("只有失敗的結果帶 failure");
            if ((ok || status == Status.INVALID_TRANSITION) == sealedReason.isPresent()) {
                throw new IllegalArgumentException("WRITTEN／UNCHANGED／INVALID_TRANSITION 沒有 SEALED 原因，其餘必須有");
            }
            if (ok && entry.isEmpty()) throw new IllegalArgumentException("WRITTEN／UNCHANGED 必須帶 entry");
        }

        public boolean succeeded() { return failure.isEmpty(); }

        static WriteResult of(Status status, ProjectionLedger.Entry entry) {
            return new WriteResult(status, Optional.of(entry), Optional.empty(), Optional.empty());
        }
        static WriteResult invalid(Optional<ProjectionLedger.Entry> existing, String reason) {
            return new WriteResult(Status.INVALID_TRANSITION, existing, Optional.empty(), Optional.of(new IllegalArgumentException(reason)));
        }
        static WriteResult failed(Lookup<?> gate) {
            var status = switch (gate.status()) {
                case UNCONFIRMED -> Status.UNCONFIRMED;
                case WRITE_FAILED -> Status.WRITE_FAILED;
                case CORRUPT -> Status.CORRUPT;
                case UNREADABLE -> Status.UNREADABLE;
                case FOUND, APPENDED, ABSENT -> throw new IllegalArgumentException("成功的查詢不是寫入失敗");
            };
            return new WriteResult(status, Optional.empty(), gate.sealedReason(), gate.failure());
        }
    }

    private final MinecraftServer owner;
    private final LedgerIo io;
    private final IntSupplier ticks;
    private final Slot<UniverseAllocationLedger> allocation;
    private final Slot<ProjectionLedger> projection;
    private InitResult init;

    /** package-private：pure JVM 測試以自己的 port 與時鐘建立（沒有 server，不檢查執行緒）。 */
    CollapseAuthorities(Path saveRoot, LedgerIo io, IntSupplier ticks) { this(null, saveRoot, io, ticks); }

    private CollapseAuthorities(MinecraftServer owner, Path saveRoot, LedgerIo io, IntSupplier ticks) {
        this.owner = owner;
        this.io = Objects.requireNonNull(io, "io");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        var data = Objects.requireNonNull(saveRoot, "saveRoot").toAbsolutePath().normalize().resolve("data");
        allocation = new Slot<>(Authority.ALLOCATION_LEDGER, data.resolve(UniverseAllocationLedger.FILE_NAME),
                UniverseAllocationLedger::toNbt, UniverseAllocationLedger::fromNbt, UniverseAllocationLedger.empty(), SealedReason.ALLOCATION_IO_FAILED);
        projection = new Slot<>(Authority.PROJECTION_LEDGER, data.resolve(ProjectionLedger.FILE_NAME),
                ProjectionLedger::toNbt, ProjectionLedger::fromNbt, ProjectionLedger.empty(), SealedReason.PROJECTION_FAILED);
    }

    /**
     * 註冊開服初始化與停服釋放。{@code QuantumSuperpositionMod} 必須在 {@code SuperpositionSessionManager.initialize()} 之後、
     * {@code UniverseLifecycleService.initialize()} 之前呼叫（plan 決策 2：Fabric 依註冊順序執行，初始化因此早於 bootstrap 物化）。
     */
    public static synchronized void registerEvents() {
        if (registered) return;
        registered = true;
        ServerLifecycleEvents.SERVER_STARTED.register(CollapseAuthorities::onStarted);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
    }

    private static void onStarted(MinecraftServer server) {
        var authorities = new CollapseAuthorities(server, server.getSavePath(WorldSavePath.ROOT), CollapseAuthorities::readFile, server::getTicks);
        if (INSTANCES.putIfAbsent(server, authorities) != null) throw new IllegalStateException("同一 server 的 M5 authority 不得重複初始化");
        authorities.initialize(server);
    }

    /** 本 server instance 的權威；只在開服之後、停服之前存在（單人遊戲在同一個 JVM 重開世界是新的 instance）。 */
    public static CollapseAuthorities get(MinecraftServer server) {
        var authorities = INSTANCES.get(Objects.requireNonNull(server, "server"));
        if (authorities == null) throw new IllegalStateException("沒有此 server 的 M5 authority（尚未開服或已停服）");
        authorities.requireThread();
        return authorities;
    }

    /** 開服初始化（每個 server instance 一次）；失敗時記 ERROR 並回傳失敗的結果，不丟例外。 */
    public InitResult initialize(MinecraftServer server) {
        if (server != owner) throw new IllegalArgumentException("CollapseAuthorities 不屬於這個 server");
        return initialize(() -> SessionRecoveryState.get(server));
    }

    InitResult initialize(Supplier<SessionRecoveryState> journal) {
        requireThread();
        if (init != null) throw new IllegalStateException("M5 authority 每個 server instance 只初始化一次");
        InitResult result;
        try {
            result = examine(journal);
        } catch (RuntimeException unexpected) {
            result = new InitResult(InitResult.Outcome.NOT_EXAMINED, InitResult.Outcome.NOT_EXAMINED, false, Optional.of(unexpected));
        }
        init = result;
        if (result.succeeded()) {
            LOGGER.info("M5 authority 初始化完成：allocation={}（{} 筆），projection={}（{} 筆），flushed journal 已是 schema 4={}",
                    result.allocation(), allocation.confirmed.entries().size(), result.projection(), projection.confirmed.entries().size(),
                    result.flushedSchemaFour());
        } else {
            var cause = result.failure().orElseThrow();
            LOGGER.error("M5 authority 初始化失敗：allocation={}，projection={}，flushed journal 已是 schema 4={}；本 server instance 不提交新的 COLLAPSING，"
                    + "journal 不會因此升為 schema 4，UNIVERSE 目的地到 RESOLVE 時進 SEALED({})；下次開服重新初始化：{}",
                    result.allocation(), result.projection(), result.flushedSchemaFour(), result.sealedReason().orElseThrow(),
                    FailureText.describe(cause), cause);
        }
        return result;
    }

    private InitResult examine(Supplier<SessionRecoveryState> journalSource) {
        SessionRecoveryState journal;
        boolean schemaFour;
        try {
            journal = journalSource.get();
            journal.requireHealthy();
            // 必須在 markCollapseAuthoritiesInitialized 之前讀：此時 m5Evidence 等於「flushed envelope 已是 schema 4」（F2a）。
            schemaFour = journal.m5Evidence();
        } catch (RuntimeException unhealthy) {
            return new InitResult(InitResult.Outcome.NOT_EXAMINED, InitResult.Outcome.NOT_EXAMINED, false,
                    Optional.of(new IllegalStateException("journal 不健康，無法判定 M5 ledger 應補建或已遺失", unhealthy)));
        }
        var failures = new ArrayList<RuntimeException>();
        var allocationOutcome = load(allocation, schemaFour, failures);
        var projectionOutcome = load(projection, schemaFour, failures);
        // 全部讀取都沒有失敗才建立缺少的空檔（journal 還不是 schema 4 時，ledger 不可能有任何 M5 寫入，補建不會蓋掉遺失的證據）。
        if (failures.isEmpty()) allocationOutcome = createIfAbsent(allocation, allocationOutcome, failures);
        if (failures.isEmpty()) projectionOutcome = createIfAbsent(projection, projectionOutcome, failures);
        if (failures.isEmpty()) {
            try { journal.markCollapseAuthoritiesInitialized(); } catch (RuntimeException failure) { failures.add(failure); }
        }
        return new InitResult(allocationOutcome, projectionOutcome, schemaFour, combine(failures));
    }

    private <L> InitResult.Outcome load(Slot<L> slot, boolean schemaFour, List<RuntimeException> failures) {
        byte[] bytes;
        try {
            bytes = io.read(slot.path);
        } catch (NoSuchFileException absent) {
            if (!schemaFour) return InitResult.Outcome.NOT_CREATED;
            failures.add(new IllegalStateException(slot.authority + " 遺失：journal 已是 schema 4，兩個 ledger 檔都必須存在", absent));
            return InitResult.Outcome.LOST;
        } catch (IOException failure) {
            failures.add(new UncheckedIOException(slot.authority + " 讀取時 IO 例外", failure));
            return InitResult.Outcome.UNREADABLE;
        }
        try {
            var ledger = slot.decode.apply(parse(bytes));
            slot.confirmed = ledger; slot.current = ledger;
            return InitResult.Outcome.LOADED;
        } catch (IOException | RuntimeException failure) {
            failures.add(new IllegalStateException(slot.authority + " strict decode 失敗", failure));
            return InitResult.Outcome.CORRUPT;
        }
    }

    private <L> InitResult.Outcome createIfAbsent(Slot<L> slot, InitResult.Outcome outcome, List<RuntimeException> failures) {
        if (outcome != InitResult.Outcome.NOT_CREATED) return outcome;
        try {
            writeAtomic(slot, slot.empty);
        } catch (IOException | RuntimeException failure) {
            failures.add(new UncheckedIOException(slot.authority + " 建立空檔失敗",
                    failure instanceof IOException ioFailure ? ioFailure : new IOException(failure)));
            return InitResult.Outcome.UNREADABLE;
        }
        slot.confirmed = slot.empty; slot.current = slot.empty;
        return InitResult.Outcome.CREATED;
    }

    /** 初始化成功（兩個 ledger 都可用，journal 已標記）。 */
    public boolean initialized() {
        requireThread();
        return init != null && init.succeeded();
    }

    /**
     * NEW 的 allocation lookup-or-create（spec §6.1、§8.2 第 1 步）。已有同一個 token 的 entry 時，必須 exact 等於本 session 會寫出的內容
     * （{@link UniverseAllocationLedger.Entry#of}），否則 {@code CORRUPT}，不沿用、不覆寫；沒有時 checked 追加。
     * 權威未確認、遺失或初始化失敗時不做任何決策。
     */
    public Lookup<UniverseAllocationLedger.Entry> lookupOrAppendAllocation(NewUniverseIntent intent) {
        requireThread();
        Objects.requireNonNull(intent, "intent");
        Lookup<UniverseAllocationLedger.Entry> gate = gate(allocation);
        if (gate != null) return gate;
        var expected = UniverseAllocationLedger.Entry.of(intent);
        var ledger = allocation.current;
        var existing = ledger.byToken(expected.allocationToken());
        if (existing.isPresent()) {
            if (existing.get().equals(expected)) return Lookup.found(existing.get());
            return corrupt(new IllegalStateException("既有 allocation entry 不是本 session 會寫出的內容（UniverseId 推導值或 profile 不符）"));
        }
        if (ledger.byUniverseId(expected.universeId()).isPresent()) {
            return corrupt(new IllegalStateException("推導的 UniverseId 已屬於另一個 AllocationToken"));
        }
        if (ledger.entries().size() >= UniverseAllocationLedger.MAX_ENTRIES) {
            return corrupt(new IllegalStateException("allocation ledger 已達 " + UniverseAllocationLedger.MAX_ENTRIES + " 筆上限"));
        }
        Lookup<UniverseAllocationLedger.Entry> written = checkedWrite(allocation, ledger.append(expected));
        return written == null ? Lookup.appended(expected) : written;
    }

    /**
     * 已確認的 projection entry（spec §6.5）；沒有時為 {@code ABSENT}。只看 key，不驗 site：寫入時由 {@link #writeProjection} 驗證。
     * 權威未確認時回 {@code UNCONFIRMED}（未確認的 PENDING 不能拿來決定寫方塊）。
     */
    public Lookup<ProjectionLedger.Entry> projection(UUID chamber, UniverseId id) {
        requireThread();
        Objects.requireNonNull(chamber, "chamber"); Objects.requireNonNull(id, "id");
        Lookup<ProjectionLedger.Entry> gate = gate(projection);
        if (gate != null) return gate;
        return projection.current.find(chamber, id).map(Lookup::found).orElseGet(Lookup::absent);
    }

    /**
     * checked 寫入一筆 projection entry：新 key 只能寫 PENDING；既有 key 的 Role／ControllerPos／OutwardFacing 必須等於本 session 的來源艙
     * （不符為 {@code CORRUPT}，不改寫），Status 只能 PENDING→COMPLETE，同一份內容為 {@code UNCHANGED}。本切片的 role 只有 OVERWORLD。
     */
    public WriteResult writeProjection(ProjectionLedger.Entry entry) {
        requireThread();
        Objects.requireNonNull(entry, "entry");
        if (entry.role() != DimensionRole.OVERWORLD) throw new IllegalArgumentException("本切片的投影只有 OVERWORLD");
        Lookup<ProjectionLedger.Entry> gate = gate(projection);
        if (gate != null) return WriteResult.failed(gate);
        var ledger = projection.current;
        var existing = ledger.find(entry.chamberUuid(), entry.universeId());
        if (existing.isPresent()) {
            var previous = existing.get();
            if (!previous.sameSite(entry)) {
                return WriteResult.failed(corrupt(new IllegalStateException("既有 projection entry 的 Role／ControllerPos／OutwardFacing 與本 session 的來源艙不符")));
            }
            if (previous.status() == entry.status()) return WriteResult.of(WriteResult.Status.UNCHANGED, previous);
            if (previous.status() == ProjectionLedger.Entry.Status.COMPLETE) return WriteResult.invalid(existing, "既有投影已是 COMPLETE，Status 不能退回 PENDING");
        } else {
            if (entry.status() != ProjectionLedger.Entry.Status.PENDING) return WriteResult.invalid(Optional.empty(), "沒有 PENDING entry，不能直接寫 COMPLETE");
            if (ledger.entries().size() >= ProjectionLedger.MAX_ENTRIES) {
                return WriteResult.failed(corrupt(new IllegalStateException("projection ledger 已達 " + ProjectionLedger.MAX_ENTRIES + " 筆上限")));
            }
        }
        Lookup<ProjectionLedger.Entry> written = checkedWrite(projection, ledger.with(entry));
        return written == null ? WriteResult.of(WriteResult.Status.WRITTEN, entry) : WriteResult.failed(written);
    }

    /** spec §6 的收斂旗標：該權威有尚未經 checked 讀回確認的 roll-forward 寫入。 */
    public boolean unconfirmed(Authority authority) {
        requireThread();
        return slot(authority).unconfirmed;
    }

    /**
     * 下一次使用前的收斂：權威未確認、而且不在退避期內時，以 checked 方式重寫 current（含所有可能已落地的追加）。成功即確認並清除退避；
     * 失敗時維持未確認並加倍退避（{@link CollapseBackoff}，以權威為單位）。初始化失敗或已遺失時不做任何事。
     */
    public void convergeIfNeeded(Authority authority) {
        requireThread();
        var slot = slot(authority);
        if (init == null || !init.succeeded() || slot.lost != null || !slot.unconfirmed) return;
        if (!slot.backoff.ready(ticks.getAsInt())) return;
        rewrite(slot);
    }

    private <L> void rewrite(Slot<L> slot) { checkedWrite(slot, slot.current); }

    /** 查詢前的共通閘門：未初始化是 caller 錯誤；初始化失敗、遺失、未確認各回對應的失敗結果；可以使用時為 null。 */
    private <T> Lookup<T> gate(Slot<?> slot) {
        if (init == null) throw new IllegalStateException("M5 authority 尚未初始化");
        if (!init.succeeded()) {
            var reason = init.sealedReason().orElseThrow();
            var status = reason == SealedReason.AUTHORITY_CORRUPT ? Lookup.Status.CORRUPT : Lookup.Status.UNREADABLE;
            return Lookup.failed(status, reason, new IllegalStateException("M5 authority 初始化失敗（allocation=" + init.allocation()
                    + "，projection=" + init.projection() + "）", init.failure().orElseThrow()));
        }
        if (slot.lost != null) return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, slot.lost);
        if (slot.unconfirmed) {
            return Lookup.failed(Lookup.Status.UNCONFIRMED, slot.writeFailure,
                    new IllegalStateException(slot.authority + " 有尚未確認的寫入，收斂之前不做新決策"));
        }
        return null;
    }

    private static <T> Lookup<T> corrupt(RuntimeException failure) {
        return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, failure);
    }

    /**
     * 執行期間的 checked 寫入（追加或收斂）。成功回 null；失敗回對應的 Lookup：
     * 檔案在寫入前已消失 → 遺失（{@code CORRUPT}，不重建）；其他寫入或讀回失敗 → roll-forward（current 保留 next、標為未確認、退避）。
     */
    private <L, T> Lookup<T> checkedWrite(Slot<L> slot, L next) {
        try {
            Files.readAttributes(slot.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            // spec §6.1／§6.5：執行期間發現檔案消失，一律視為遺失；不重建，current 不變。
            slot.lost = new IllegalStateException(slot.authority + " 在執行期間消失，視為遺失", missing);
            LOGGER.error("M5 {} 的檔案在執行期間消失，視為遺失（AUTHORITY_CORRUPT）；不重建，需要管理員處理：{}", slot.authority, slot.path);
            return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, slot.lost);
        } catch (IOException failure) {
            return rollForward(slot, next, failure);
        }
        try {
            writeAtomic(slot, next);
        } catch (IOException | RuntimeException failure) {
            return rollForward(slot, next, failure);
        }
        boolean converged = slot.unconfirmed;
        slot.confirmed = next; slot.current = next; slot.unconfirmed = false;
        slot.backoff.reset(); slot.warned = null;
        if (converged) LOGGER.info("M5 {} 收斂完成：checked 重寫已讀回確認", slot.authority);
        return null;
    }

    /** spec §6 的 roll-forward：寫入或讀回失敗時 current 保留新內容（絕不移除可能已落地的追加），標為未確認並退避；同一原因只記一次 WARN。 */
    private <L, T> Lookup<T> rollForward(Slot<L> slot, L next, Exception failure) {
        slot.current = next; slot.unconfirmed = true;
        slot.backoff.failed(ticks.getAsInt());
        var key = FailureText.dedupeKey(failure);
        if (!key.equals(slot.warned)) {
            slot.warned = key;
            LOGGER.warn("M5 {} 的 checked 寫入未確認：保留新內容並標為未確認，{} ticks 後才重試收斂：{}",
                    slot.authority, slot.backoff.delayTicks(), FailureText.describe(failure), failure);
        }
        var cause = failure instanceof RuntimeException runtime ? runtime : new UncheckedIOException(slot.authority + " checked 寫入失敗",
                failure instanceof IOException ioFailure ? ioFailure : new IOException(failure));
        return Lookup.failed(Lookup.Status.WRITE_FAILED, slot.writeFailure, cause);
    }

    /** 同目錄暫存檔、force、以正式讀取規則驗證暫存檔、atomic move、strict 讀回並 exact 比對（沿用 M4 discovery 的寫法）。 */
    private <L> void writeAtomic(Slot<L> slot, L ledger) throws IOException {
        var expected = slot.encode.apply(ledger);
        var parent = slot.path.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, slot.path.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(expected, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // 先用正式讀取規則驗暫存檔，避免讀不回來的內容取代上一份有效權威。
            verify(slot, temporary, expected);
            Files.move(temporary, slot.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verify(slot, slot.path, expected);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <L> void verify(Slot<L> slot, Path path, NbtCompound expected) throws IOException {
        var actual = parse(io.read(path));
        slot.decode.apply(actual);
        if (!expected.equals(actual)) throw new IOException(slot.authority + " exact readback 不符：" + path);
    }

    /** 原始 bytes → NBT。這一步（含之後的 strict decode）的任何失敗都是內容損壞，不是 IO 例外。 */
    static NbtCompound parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("ledger 檔案超過 " + MAX_FILE_BYTES + " bytes");
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtSizeTracker.of(MAX_NBT_BYTES));
    }

    /** 正式的 {@link LedgerIo}：必須是一般檔案（不跟隨連結）；最多讀 {@link #MAX_FILE_BYTES}＋1 bytes，超過的部分由 {@link #parse} 判為損壞。 */
    static byte[] readFile(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("M5 ledger 必須是一般檔案：" + path);
        try (var input = Files.newInputStream(path)) { return input.readNBytes(MAX_FILE_BYTES + 1); }
    }

    private Slot<?> slot(Authority authority) {
        return switch (Objects.requireNonNull(authority, "authority")) {
            case ALLOCATION_LEDGER -> allocation;
            case PROJECTION_LEDGER -> projection;
        };
    }

    private void requireThread() {
        if (owner != null && !owner.isOnThread()) throw new IllegalStateException("M5 authority 必須在伺服器執行緒操作");
    }

    private static Optional<RuntimeException> combine(List<RuntimeException> failures) {
        if (failures.isEmpty()) return Optional.empty();
        var first = failures.getFirst();
        for (var other : failures.subList(1, failures.size())) first.addSuppressed(other);
        return Optional.of(first);
    }

    /** 一個 ledger 權威：已確認內容、current（roll-forward 尚未確認時領先）、收斂旗標、遺失狀態與以權威為單位的退避。 */
    private static final class Slot<L> {
        final Authority authority;
        final Path path;
        final Function<L, NbtCompound> encode;
        final Function<NbtCompound, L> decode;
        final L empty;
        final SealedReason writeFailure;
        final CollapseBackoff backoff = new CollapseBackoff();
        L confirmed;
        L current;
        boolean unconfirmed;
        RuntimeException lost;
        String warned;

        Slot(Authority authority, Path path, Function<L, NbtCompound> encode, Function<NbtCompound, L> decode, L empty, SealedReason writeFailure) {
            this.authority = authority; this.path = path; this.encode = encode; this.decode = decode;
            this.empty = empty; this.writeFailure = writeFailure; this.confirmed = empty; this.current = empty;
        }
    }

    /** 兩個 ledger 共用的 strict NBT helper（persistence 的 {@code JournalNbt} 是 package-private，不能跨 package 使用）。 */
    static final class LedgerNbt {
        private LedgerNbt() {}

        static void requireType(NbtCompound nbt, String key, int type) {
            if (!nbt.contains(key, type)) throw new IllegalArgumentException(key + " 缺失或 NBT 型別錯誤");
        }

        static UUID uuid(NbtCompound nbt, String key) {
            requireType(nbt, key, NbtElement.INT_ARRAY_TYPE);
            if (nbt.getIntArray(key).length != 4) throw new IllegalArgumentException(key + " 必須是四個 int 的 UUID");
            return nbt.getUuid(key);
        }

        static <E extends Enum<E>> E enumValue(Class<E> type, NbtCompound nbt, String key) {
            requireType(nbt, key, NbtElement.STRING_TYPE);
            var raw = nbt.getString(key);
            for (var constant : type.getEnumConstants()) if (constant.name().equals(raw)) return constant;
            throw new IllegalArgumentException(key + " 不是合法的 " + type.getSimpleName());
        }

        /** Entries list：空 list 必須是 END held type，非空必須是 COMPOUND；長度上限在逐筆解碼之前檢查（H1 的做法）。 */
        static NbtList entries(NbtCompound nbt, int maxEntries) {
            requireType(nbt, "Entries", NbtElement.LIST_TYPE);
            var list = (NbtList) nbt.get("Entries");
            int heldType = list.isEmpty() ? NbtElement.END_TYPE : NbtElement.COMPOUND_TYPE;
            if (list.getHeldType() != heldType) throw new IllegalArgumentException("Entries 必須包含 compound；空 list 必須使用 END held type");
            if (list.size() > maxEntries) throw new IllegalArgumentException("Entries 超過 " + maxEntries + " 筆上限");
            return list;
        }
    }
}
