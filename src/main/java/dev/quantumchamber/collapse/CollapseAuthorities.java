package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistry;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
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
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 的 checked 權威（spec §6）：每個 server instance 一份，持有 allocation ledger（§6.1）與 projection ledger（§6.5）的已確認內容，
 * 也是 discovery（§6.4）與 catalog 配置（§6.2）的收斂 owner：四個權威各自的收斂旗標與以權威為單位的退避。只在 server thread 使用。
 * <ul>
 * <li><b>初始化</b>（{@link #initialize(MinecraftServer)}）：開服時（{@code SERVER_STARTED}，註冊順序早於 lifecycle bootstrap 物化）執行一次。
 *     只看 journal 的 flushed envelope：還不是 schema 4 時，缺少的 ledger 以 checked 方式建立為空檔；已是 schema 4 時，缺少任何一個就是遺失
 *     （{@link SealedReason#AUTHORITY_CORRUPT}），不建檔、不補寫。成功才呼叫 {@link SessionRecoveryState#markCollapseAuthoritiesInitialized()}；
 *     任何原因的失敗都不呼叫，並記 ERROR。</li>
 * <li><b>讀取失敗的分類</b>：讀到原始 bytes 之後的失敗（壓縮格式、NBT、strict decode）與確定遺失為 {@code AUTHORITY_CORRUPT}；讀檔時的 IO 例外為
 *     {@code AUTHORITY_UNREADABLE}。</li>
 * <li><b>寫入</b>：同目錄暫存檔、force、以正式讀取規則驗證暫存檔、atomic move、strict 讀回並 exact 比對（{@link CheckedNbtFile}，
 *     M4 discovery 的寫法）。寫入或讀回失敗時 roll-forward（spec §6）：current 保留這次的新內容，權威標為未確認；未確認期間這個權威的查詢與寫入一律回「未確認」
 *     （{@code UNCONFIRMED}；discovery 的唯讀查詢與 catalog 的配置依 spec 不得 SEALED，回 {@code WAIT}），不做新決策，由 {@link #convergeIfNeeded} 依 {@link CollapseBackoff} 退避重試 checked 重寫。</li>
 * <li><b>discovery</b>（M4 的檔，不是 PersistentState）：不在初始化載入，每次使用時從磁碟 strict 讀取（與 M4 的讀取點一致）；檔案不存在一律是遺失
 *     （只有 NEW／EXISTING 的 RESOLVE 會用到，那時 journal 已有 candidate evidence，M4 已建立 discovery）。寫入與 ledger 相同，失敗時 roll-forward：
 *     current 保留這次的 append，絕不移除可能已落地的 record，所以 M4 從磁碟讀到的候選池不會倒退。</li>
 * <li><b>catalog</b>（M3 的 PersistentState）：依原生 dirty 機制收斂（M3 的 save 失敗時保留 current 與 dirty，下一次寫出——重試或原生
 *     autosave——帶著它）。這裡不另存旗標，「未確認」直接由 catalog 推導：仍 dirty，或 current 不等於 flushed。本類別負責阻擋新決策
 *     （未確認時配置與唯讀查詢回 {@code WAIT}，依 §6.2 不 SEALED）、以權威為單位退避重試寫出，以及寫出失敗與收斂完成的 log。F4a 對 catalog 的
 *     讀寫都經這裡（F4a fix round 2，m-2）：配置或驗證（{@link #allocateOrVerifyCatalog}）、唯讀查詢（{@link #catalogRecord}）與 §8.3 的
 *     availability 變更（{@link #changeCatalogAvailability}，restore 方向在寫出失敗時把 current 改回變更前的內容）。</li>
 * </ul>
 * 兩個 ledger 與 discovery 都不是 PersistentState，原生 autosave 與停服不會寫它們；收斂完成之前 crash，重啟後讀到的舊內容或新內容都合法
 * （spec §6 的表）。
 * <p>這裡的 log 記的是權威本身（初始化、遺失、寫入未確認與收斂），不屬於任何 session，所以一律帶 {@link CollapseService#TICK_LOG_MARKER}
 * （F4a，F4a-0 裁示 1）：GameTest 的 {@code collapseFlowNeverWarned} 因此也看得到權威寫入未確認的 WARN。
 */
public final class CollapseAuthorities {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private static final Map<MinecraftServer, CollapseAuthorities> INSTANCES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static boolean registered;

    /** 收斂與未確認旗標的單位（spec §6 收斂共通第 2 點：以權威為單位）。 */
    public enum Authority { ALLOCATION_LEDGER, PROJECTION_LEDGER, DISCOVERY, CATALOG }

    /**
     * 既有 catalog record 可以接受的 availability（spec §6.2、§8.3）。只有 NEW 的 RESOLVE 查詢本 session 推導的 UniverseId 時可以接受
     * {@code DISABLED}（spec §6 收斂規則表：等同 §8.3 已完成）；其餘情況一律 {@link #EAGER_ONLY}。
     */
    public enum AvailabilityPolicy {
        /** EXISTING 重驗（§8.1）與其他一律：只接受 {@code EAGER_ENABLED}。 */
        EAGER_ONLY,
        /**
         * NEW 的 RESOLVE，UniverseId 是本 session 的推導值（F4a fix round 1，I-1）：也接受 {@code DISABLED}，與最近一次封存的原因無關；之後由 F4a 依本
         * instance 的物化額度改回 {@code EAGER_ENABLED} 再物化，或等下次重啟。
         */
        MATERIALIZE_RETRY;

        boolean accepts(DesiredAvailability availability) {
            return switch (Objects.requireNonNull(availability, "availability")) {
                case EAGER_ENABLED -> true;
                case DISABLED -> this == MATERIALIZE_RETRY;
            };
        }
    }

    /**
     * {@link #changeCatalogAvailability} 寫出或讀回失敗時的收斂方向（spec §6 收斂規則表的兩個方向；F4a fix round 2，m-2）。
     */
    public enum WriteDirection {
        /**
         * roll-forward（§8.3 改為 {@code DISABLED}：新內容是較保守的一方）：current 保留這次的新 availability 並維持 dirty（M3 save 失敗時的語意），
         * 之後的寫出（{@link #convergeIfNeeded} 的重試或原生 autosave）帶著它。
         */
        ROLL_FORWARD,
        /**
         * restore（§8.3 重試時改回 {@code EAGER_ENABLED}：之後的物化依賴它）：current 改回變更前的 availability（較保守的一方）並維持 dirty，下一次寫出
         * 以它覆寫磁碟（含 atomic move 已完成、讀回失敗而磁碟已是新內容的情況）。
         */
        RESTORE
    }

    /**
     * 決策 9 的 package-private IO port：讀取 ledger 與 discovery 檔（正式檔或暫存檔）的原始 bytes。初始化的載入、discovery 的讀取、
     * 寫入前的暫存檔驗證、atomic move 之後的讀回都經過它，pure JVM 測試以它注入 IO 例外與「atomic move 完成、讀回失敗」。
     * 檔案不存在必須丟 {@link NoSuchFileException}。正式實作是 {@link CheckedNbtFile#readFile}。
     */
    @FunctionalInterface interface LedgerIo extends CheckedNbtFile.ReadPort {}

    /**
     * 決策 9 的 package-private catalog port。production 是 {@link ServerCatalog}（{@link UniverseRegistryState#get} 與
     * {@link UniverseRegistryState#flush}）；pure JVM 測試以它提供 catalog 並注入寫出失敗，檢查本類別的未確認狀態。
     */
    interface CatalogPort {
        /** 本 server 的 catalog（PersistentState，同一個 instance）。 */
        UniverseRegistryState catalog();

        /** checked 寫出 catalog 的 current。失敗時丟 {@link RuntimeException}；M3 的 save 失敗時保留 current 與 dirty。 */
        void flush(UniverseRegistryState catalog);
    }

    private record ServerCatalog(MinecraftServer server) implements CatalogPort {
        @Override public UniverseRegistryState catalog() { return UniverseRegistryState.get(server); }
        @Override public void flush(UniverseRegistryState catalog) { catalog.flush(server); }
    }

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
     * 查詢（與 allocation、discovery、catalog 的追加或配置）結果。成功的三種狀態（FOUND、APPENDED、ABSENT）沒有 SEALED 原因與 failure；
     * {@code WAIT} 也都沒有（不是失敗，下一個 tick 再試）；其餘四種失敗狀態都帶著可直接用於 SEALED 的原因（F4a／F4b）與 failure。
     * @param <T> entry 型別
     */
    public record Lookup<T>(Status status, Optional<T> value, Optional<SealedReason> sealedReason, Optional<RuntimeException> failure) {
        public enum Status {
            /**
             * 找到既有紀錄，而且已驗證：allocation、discovery、catalog 為 exact 等於（或正是）本 session 會寫出的內容；projection 為該
             * (chamber, UniverseId) 的已確認 entry；discovery 與 catalog 的唯讀查詢為該 UniverseId 的 record（呼叫端自己判斷 gameplayEligible 或
             * availability）；catalog 的 availability 變更：已確認的這筆已是目標 availability，沒有寫出。
             */
            FOUND,
            /**
             * allocation、discovery：沒有紀錄，本次 checked 追加並讀回確認；catalog：本次配置並 checked 寫出、讀回確認；catalog 的 availability
             * 變更：本次變更並 checked 寫出、讀回確認。
             */
            APPENDED,
            /** projection、discovery 與 catalog 的唯讀查詢：已確認的內容沒有這個 key。 */
            ABSENT,
            /**
             * 權威有尚未確認的 roll-forward 寫入：不做新決策，先 {@link CollapseAuthorities#convergeIfNeeded}。ledger 的查詢與寫入、discovery 的
             * 追加（NEW，依 §6.4 進 SEALED）用它，帶原因。
             */
            UNCONFIRMED,
            /**
             * 權威有未確認的寫入，但這個呼叫依 spec <b>不得 SEALED</b>：下一個 tick 再試，或先 {@link CollapseAuthorities#convergeIfNeeded}。
             * 只有這幾處會回：catalog 的配置（{@link CollapseAuthorities#allocateOrVerifyCatalog}，§6.2：仍 dirty 或 current 不等於 flushed；
             * catalog 是共用權威，dirty 可能來自別的 session 的寫入失敗）、discovery 與 catalog 的唯讀查詢（{@link CollapseAuthorities#discovery}、
             * {@link CollapseAuthorities#catalogRecord}，§6 收斂共通第 2 點：EXISTING 重驗不做新決策，只等收斂），以及 restore 方向的 catalog
             * availability 變更（{@link CollapseAuthorities#changeCatalogAvailability}：之後的物化依賴它，是新決策）。不帶 SEALED 原因，也不帶
             * failure；{@link Lookup#succeeded()} 為 false。
             */
            WAIT,
            /** 本次的 checked 寫入或讀回失敗：current 保留它，權威標為未確認。 */
            WRITE_FAILED,
            /**
             * 遺失、損壞、既有紀錄不是本 session 會寫出的內容、UniverseId 已屬於另一個 token、已達上限、orphan storage，或初始化因此失敗。
             */
            CORRUPT,
            /** 初始化時的 IO 例外（或 journal 不健康），或 discovery 讀取、storage 檢查時的 IO 例外；重啟之後重試。 */
            UNREADABLE
        }

        public Lookup {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(value, "value");
            Objects.requireNonNull(sealedReason, "sealedReason"); Objects.requireNonNull(failure, "failure");
            boolean failed = status == Status.WRITE_FAILED || status == Status.CORRUPT || status == Status.UNREADABLE || status == Status.UNCONFIRMED;
            if (failed != sealedReason.isPresent() || failed != failure.isPresent()) {
                throw new IllegalArgumentException("只有 WRITE_FAILED、CORRUPT、UNREADABLE、UNCONFIRMED 帶 SEALED 原因與 failure");
            }
            if ((status == Status.FOUND || status == Status.APPENDED) != value.isPresent()) throw new IllegalArgumentException("只有 FOUND／APPENDED 帶 entry");
        }

        /** 成功：FOUND、APPENDED、ABSENT。{@code WAIT} 與四種失敗狀態都為 false（依 status 判斷，不看有沒有 SEALED 原因）。 */
        public boolean succeeded() { return status == Status.FOUND || status == Status.APPENDED || status == Status.ABSENT; }

        static <T> Lookup<T> found(T value) { return new Lookup<>(Status.FOUND, Optional.of(value), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> appended(T value) { return new Lookup<>(Status.APPENDED, Optional.of(value), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> absent() { return new Lookup<>(Status.ABSENT, Optional.empty(), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> waiting() { return new Lookup<>(Status.WAIT, Optional.empty(), Optional.empty(), Optional.empty()); }
        static <T> Lookup<T> failed(Status status, SealedReason reason, RuntimeException failure) {
            return new Lookup<>(status, Optional.empty(), Optional.of(reason), Optional.of(failure));
        }
    }

    /**
     * projection 的寫入結果。成功的三種狀態（WRITTEN、UNCHANGED、ALREADY_COMPLETE）帶 entry，沒有 SEALED 原因與 failure；其餘都沒有 entry，
     * 帶著可直接用於 SEALED 的原因（F4b）與 failure。caller 的前提錯誤（role 不是 OVERWORLD、沒有 PENDING 就寫 COMPLETE）直接丟例外，不是結果狀態。
     */
    public record WriteResult(Status status, Optional<ProjectionLedger.Entry> entry, Optional<SealedReason> sealedReason,
            Optional<RuntimeException> failure) {
        public enum Status {
            /** checked 寫入並讀回確認；entry 為寫入的內容。 */
            WRITTEN,
            /** 既有 entry 已是同一份內容（同 site、同 Status），沒有寫入；entry 為既有內容。 */
            UNCHANGED,
            /**
             * 寫入 PENDING，而既有 entry 已是 COMPLETE：site 已驗證相同，沒有寫入（Status 不倒退，spec §6.5）；entry 為既有的 COMPLETE。
             * 不是錯誤：這座艙之前已完成這個宇宙的投影，F4b 改走只修復不變量 cell 的路徑（§9.2），不清 interior。
             */
            ALREADY_COMPLETE,
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
            boolean ok = status == Status.WRITTEN || status == Status.UNCHANGED || status == Status.ALREADY_COMPLETE;
            if (ok == sealedReason.isPresent() || ok == failure.isPresent()) throw new IllegalArgumentException("只有失敗的結果帶 SEALED 原因與 failure");
            if (ok != entry.isPresent()) throw new IllegalArgumentException("只有 WRITTEN／UNCHANGED／ALREADY_COMPLETE 帶 entry");
        }

        public boolean succeeded() { return failure.isEmpty(); }

        static WriteResult of(Status status, ProjectionLedger.Entry entry) {
            return new WriteResult(status, Optional.of(entry), Optional.empty(), Optional.empty());
        }
        static WriteResult failed(Lookup<?> gate) {
            var status = switch (gate.status()) {
                case UNCONFIRMED -> Status.UNCONFIRMED;
                case WRITE_FAILED -> Status.WRITE_FAILED;
                case CORRUPT -> Status.CORRUPT;
                case UNREADABLE -> Status.UNREADABLE;
                case FOUND, APPENDED, ABSENT -> throw new IllegalArgumentException("成功的查詢不是寫入失敗");
                case WAIT -> throw new IllegalArgumentException("WAIT 只屬於 catalog 與 discovery 的查詢、catalog 的配置與變更，不是 projection 的寫入結果");
            };
            return new WriteResult(status, Optional.empty(), gate.sealedReason(), gate.failure());
        }
    }

    private final MinecraftServer owner;
    private final Path saveRoot;
    private final LedgerIo io;
    private final CatalogPort catalog;
    private final IntSupplier ticks;
    private final Slot<UniverseAllocationLedger> allocation;
    private final Slot<ProjectionLedger> projection;
    private final Slot<UniverseDiscoveryState> discovery;
    /** catalog 的收斂：以權威為單位的退避、WARN 去重鍵，以及本 instance 的寫出是否失敗過（確認時記 INFO）。未確認本身由 catalog 推導。 */
    private final CollapseBackoff catalogBackoff = new CollapseBackoff();
    private String catalogWarned;
    private boolean catalogWriteFailed;
    private InitResult init;

    /** package-private：pure JVM 測試以自己的 port 與時鐘建立（沒有 server，不檢查執行緒）。 */
    CollapseAuthorities(Path saveRoot, LedgerIo io, CatalogPort catalog, IntSupplier ticks) { this(null, saveRoot, io, catalog, ticks); }

    private CollapseAuthorities(MinecraftServer owner, Path saveRoot, LedgerIo io, CatalogPort catalog, IntSupplier ticks) {
        this.owner = owner;
        this.saveRoot = Objects.requireNonNull(saveRoot, "saveRoot").toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.ticks = Objects.requireNonNull(ticks, "ticks");
        var data = this.saveRoot.resolve("data");
        allocation = new Slot<>(Authority.ALLOCATION_LEDGER, data.resolve(UniverseAllocationLedger.FILE_NAME),
                UniverseAllocationLedger::toNbt, UniverseAllocationLedger::fromNbt, UniverseAllocationLedger.empty(), SealedReason.ALLOCATION_IO_FAILED);
        projection = new Slot<>(Authority.PROJECTION_LEDGER, data.resolve(ProjectionLedger.FILE_NAME),
                ProjectionLedger::toNbt, ProjectionLedger::fromNbt, ProjectionLedger.empty(), SealedReason.PROJECTION_FAILED);
        // discovery 不由 M5 初始化建立或載入（M4 擁有建立），所以沒有空檔內容；confirmed／current 只在寫入之後才有值。
        discovery = new Slot<>(Authority.DISCOVERY, data.resolve(UniverseDiscoveryState.FILE_NAME),
                UniverseDiscoveryState::toNbt, UniverseDiscoveryState::fromNbt, null, SealedReason.ALLOCATION_IO_FAILED);
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
        var authorities = new CollapseAuthorities(server, server.getSavePath(WorldSavePath.ROOT), CheckedNbtFile::readFile,
                new ServerCatalog(server), server::getTicks);
        if (INSTANCES.putIfAbsent(server, authorities) != null) throw new IllegalStateException("同一 server 的 M5 authority 不得重複初始化");
        authorities.initialize(server);
    }

    /**
     * 本 server instance 的權威（單人遊戲在同一個 JVM 重開世界是新的 instance）。instance 在 {@link #registerEvents()} 註冊的
     * {@code SERVER_STARTED} listener 裡才建立，在 {@code SERVER_STOPPED} 釋放：比它更早註冊的 {@code SERVER_STARTED} listener 不能呼叫
     * （Fabric 依註冊順序執行，那時還沒有 instance），停服之後也不能呼叫。初始化失敗時 instance 仍然存在，查詢會回對應的失敗結果。
     */
    public static CollapseAuthorities get(MinecraftServer server) {
        var authorities = INSTANCES.get(Objects.requireNonNull(server, "server"));
        if (authorities == null) {
            throw new IllegalStateException("沒有此 server 的 M5 authority：instance 在 CollapseAuthorities.registerEvents() 註冊的 SERVER_STARTED "
                    + "listener 裡才建立、在 SERVER_STOPPED 釋放；比它更早註冊的 SERVER_STARTED listener，以及停服之後，都不能呼叫");
        }
        authorities.requireThread();
        return authorities;
    }

    /**
     * 開服初始化：只供 {@link #registerEvents()} 註冊的 {@code SERVER_STARTED} listener 在建立 instance 之後呼叫一次（第二次丟例外）。
     * 失敗時記 ERROR 並回傳失敗的結果，不丟例外，所以之後的 lifecycle bootstrap 照常執行。
     */
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
            LOGGER.info(CollapseService.TICK_LOG_MARKER, "M5 authority 初始化完成：allocation={}（{} 筆），projection={}（{} 筆），flushed journal 已是 schema 4={}",
                    result.allocation(), allocation.confirmed.entries().size(), result.projection(), projection.confirmed.entries().size(),
                    result.flushedSchemaFour());
        } else {
            var cause = result.failure().orElseThrow();
            LOGGER.error(CollapseService.TICK_LOG_MARKER, "M5 authority 初始化失敗：allocation={}，projection={}，flushed journal 已是 schema 4={}；本 server instance 不提交新的 COLLAPSING，"
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
            var ledger = slot.decode.apply(CheckedNbtFile.parse(bytes));
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
     * <p>呼叫前提：本 session 的 COLLAPSING 已經 checked 落盤（journal 的 flushed envelope 已是 schema 4）。初始化的遺失判定依賴這個不變量
     * （spec §6）：journal 還不是 schema 4 時，缺少的 ledger 會被當成第一次建立而補成空檔；若 ledger 在那之前就有寫入，遺失就偵測不到。
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
     * 已確認的 projection entry（spec §6.5）；沒有時為 {@code ABSENT}。只看 key，不驗 site：決定第一次寫入或修復之前，先以
     * {@link #writeProjection} 寫 PENDING 取得 site 驗證（{@code UNCHANGED}＝既有 PENDING、{@code ALREADY_COMPLETE}＝既有 COMPLETE）。
     * {@code ABSENT} 時，先做 spec §6.5 的實體交叉檢查（F4b），才能寫 PENDING。PENDING 一旦存在就不再做交叉檢查。
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
     * checked 寫入一筆 projection entry（spec §6.5）。
     * <ul>
     * <li>既有 key 的 Role／ControllerPos／OutwardFacing 必須等於本 session 的來源艙，不符為 {@code CORRUPT}，不改寫。</li>
     * <li>site 相同時：同一個 Status 為 {@code UNCHANGED}；既有 PENDING 寫 COMPLETE 才實際寫入；既有 COMPLETE 寫 PENDING 為
     *     {@code ALREADY_COMPLETE}（Status 不倒退，帶回既有 entry，F4b 改走修復路徑）。</li>
     * <li>新 key 只能寫 PENDING；已達上限為 {@code CORRUPT}。</li>
     * </ul>
     * 呼叫前提（違反時丟 {@link IllegalArgumentException}，不是結果狀態）：
     * <ul>
     * <li>本切片的 role 只有 OVERWORLD。</li>
     * <li>COMPLETE 只能寫在已確認的 PENDING 之後：已確認的 PENDING 不會消失（不刪除 entry，遺失由閘門先回 {@code CORRUPT}），所以新 key
     *     直接寫 COMPLETE 一定是 caller 的錯誤。</li>
     * <li>與 {@link #lookupOrAppendAllocation} 相同：本 session 的 COLLAPSING 已經 checked 落盤（journal flushed 已是 schema 4），
     *     初始化的遺失判定依賴這個不變量。</li>
     * </ul>
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
            if (previous.status() == ProjectionLedger.Entry.Status.COMPLETE) return WriteResult.of(WriteResult.Status.ALREADY_COMPLETE, previous);
        } else {
            if (entry.status() != ProjectionLedger.Entry.Status.PENDING) throw new IllegalArgumentException("沒有已確認的 PENDING entry，不能直接寫 COMPLETE");
            if (ledger.entries().size() >= ProjectionLedger.MAX_ENTRIES) {
                return WriteResult.failed(corrupt(new IllegalStateException("projection ledger 已達 " + ProjectionLedger.MAX_ENTRIES + " 筆上限")));
            }
        }
        Lookup<ProjectionLedger.Entry> written = checkedWrite(projection, ledger.with(entry));
        return written == null ? WriteResult.of(WriteResult.Status.WRITTEN, entry) : WriteResult.failed(written);
    }

    /**
     * NEW 的 discovery 追加（spec §6.4、§8.2 第 4 步；RESOLVE 物化成功之後呼叫）。欄位與 idempotency 依
     * {@link UniverseDiscoveryState#append}：沒有這個 UniverseId 時 checked 追加（{@code APPENDED}）；已有時必須 {@code gameplayEligible} 而且
     * {@code firstObserver} 等於 {@code selectedBy}（{@code FOUND}，不改動），否則 {@code CORRUPT}，不沿用、不改寫。
     * <ul>
     * <li>寫入或讀回失敗：roll-forward，current 保留這筆 append（絕不移除可能已落地的 record，M4 讀到的候選池不倒退），回
     *     {@code WRITE_FAILED}／{@code ALLOCATION_IO_FAILED}；收斂之前一律 {@code UNCONFIRMED}，重試前先 {@link #convergeIfNeeded}。</li>
     * <li>讀取失敗的分類（spec §6）：檔案不存在是遺失（{@code CORRUPT}，本 instance 之後一律 {@code CORRUPT}，不重建）；讀檔時的 IO 例外為
     *     {@code UNREADABLE}；讀到 bytes 之後的解析或 strict decode 失敗為 {@code CORRUPT}。</li>
     * </ul>
     * 初始化失敗時與 ledger 相同，回初始化的失敗結果（spec §6：UNIVERSE 目的地到 RESOLVE 時依初始化的失敗分類進 SEALED），不讀、不寫。
     * @param discoveredAtGameTime 當下的 game time（負數是 caller 錯誤，丟 {@link IllegalArgumentException}）
     * @param selectedBy 本 session 的 {@code SelectedBy}
     */
    public Lookup<UniverseDiscoveryRecord> appendDiscovery(UniverseId universeId, long discoveredAtGameTime, UUID selectedBy) {
        requireThread();
        Objects.requireNonNull(universeId, "universeId"); Objects.requireNonNull(selectedBy, "selectedBy");
        if (discoveredAtGameTime < 0) throw new IllegalArgumentException("discovery 的 game time 必須非負");
        Lookup<UniverseDiscoveryRecord> gate = gate(discovery);
        if (gate != null) return gate;
        var read = readDiscovery();
        if (!read.succeeded()) return failedAs(read);
        UniverseDiscoveryState.Append append;
        try {
            append = read.value().orElseThrow().append(universeId, discoveredAtGameTime, selectedBy);
        } catch (ArithmeticException overflow) {
            return corrupt(new IllegalStateException("discovery ordinal 溢位", overflow));
        }
        return switch (append.outcome()) {
            case EXISTING -> Lookup.found(append.record());
            case MISMATCH -> corrupt(new IllegalStateException("既有 discovery record 不是本 session 會寫出的內容（gameplayEligible 或 firstObserver 不符）"));
            case APPENDED -> {
                Lookup<UniverseDiscoveryRecord> written = checkedWrite(discovery, append.next());
                yield written == null ? Lookup.appended(append.record()) : written;
            }
        };
    }

    /**
     * 唯讀的 discovery 查詢（spec §8.1 EXISTING 重驗用）：從磁碟 strict 讀取，回這個 UniverseId 的 record（{@code FOUND}，是否
     * {@code gameplayEligible} 由呼叫端判斷）或 {@code ABSENT}。
     * <ul>
     * <li>discovery 有未確認的寫入（別的 session 的 append 尚未確認）時回 {@code WAIT}（不帶原因與 failure）：依 spec §6 收斂共通第 2 點，
     *     未確認期間只重試收斂、不做新決策，所以呼叫端等下一個 tick 或先 {@link #convergeIfNeeded}，不判 {@code EXISTING_INELIGIBLE}，
     *     也不 SEALED。這與 {@link #appendDiscovery}（NEW）不同：NEW 的未確認依 §6.4 回帶 {@code ALLOCATION_IO_FAILED} 的
     *     {@code UNCONFIRMED}。</li>
     * <li>初始化失敗、遺失與讀取失敗的處理同 {@link #appendDiscovery}。</li>
     * </ul>
     */
    public Lookup<UniverseDiscoveryRecord> discovery(UniverseId universeId) {
        requireThread();
        Objects.requireNonNull(universeId, "universeId");
        Lookup<UniverseDiscoveryRecord> gate = gate(discovery);
        if (gate != null) return gate.status() == Lookup.Status.UNCONFIRMED ? Lookup.waiting() : gate;
        var read = readDiscovery();
        if (!read.succeeded()) return failedAs(read);
        return read.value().orElseThrow().find(universeId).map(Lookup::found).orElseGet(Lookup::absent);
    }

    /**
     * NEW 的 catalog 配置或既有紀錄驗證（spec §6.2、§8.2 第 2 步）。只看 flushed catalog：
     * <ol>
     * <li>M5 初始化失敗 → 初始化的失敗結果（與 ledger 相同，不配置）。</li>
     * <li>catalog 不健康（M3 strict decode 失敗；正常情況下 bootstrap 已讓 server 停止）→ {@code CORRUPT}。</li>
     * <li>catalog 有未確認的寫入（{@link #unconfirmed} 為真：仍 dirty，或 current 不等於 flushed）→ {@code WAIT}（沒有 SEALED 原因、沒有
     *     failure），不配置。依 §6.2 <b>不得 SEALED</b>，下一個 tick 再試（catalog 是共用權威，dirty 可能來自別的 session 的寫入失敗；持續
     *     600 ticks 記一次 WARN 由 F4a 負責）。呼叫端可以先查 {@link #unconfirmed}、需要時 {@link #convergeIfNeeded}。</li>
     * <li>已有這個 UniverseId：以 {@link #catalogRecordMatches} 依 {@code availability} 驗證，符合為 {@code FOUND}，否則 {@code CORRUPT}，
     *     不重新配置、不改寫。</li>
     * <li>沒有：該 UUID 的 storage 目錄已存在就是 orphan → {@code CORRUPT}，絕不採用或刪除那個目錄；檢查時的 IO 例外為 {@code UNREADABLE}。</li>
     * <li>否則 {@code allocateOverworld(uuid, flushed 的最大 ordinal＋1)}（catalog 為空時為 0），availability 為 {@code EAGER_ENABLED}，再
     *     checked 寫出：成功為 {@code APPENDED}；寫出或讀回失敗為 {@code WRITE_FAILED}／{@code ALLOCATION_IO_FAILED}——roll-forward，M3 的 save
     *     保留 current 與 dirty，下一次寫出（{@link #convergeIfNeeded} 的重試或原生 autosave）帶著它；確認之前不得物化。</li>
     * </ol>
     * @param availability 既有紀錄可以接受的 availability：NEW 的 RESOLVE（本 session 推導的 UniverseId）傳
     *     {@link AvailabilityPolicy#MATERIALIZE_RETRY}（接受 {@code DISABLED}，F4a 依物化額度改回 {@code EAGER_ENABLED} 或等下次重啟）；其餘一律
     *     {@link AvailabilityPolicy#EAGER_ONLY}
     */
    public Lookup<UniverseRecord> allocateOrVerifyCatalog(UniverseId universeId, AvailabilityPolicy availability) {
        requireThread();
        Objects.requireNonNull(universeId, "universeId"); Objects.requireNonNull(availability, "availability");
        Lookup<UniverseRecord> gate = catalogGate("不配置");
        if (gate != null) return gate;
        var state = catalog.catalog();
        Map<UniverseId, UniverseRecord> flushed = state.flushedRecords();
        var existing = flushed.get(universeId);
        if (existing != null) {
            if (catalogRecordMatches(existing, universeId, availability)) return Lookup.found(existing);
            return corrupt(new IllegalStateException("既有 catalog record 不是 M5 會配置的內容（descriptor、profile、seed policy 或 availability 不符）"));
        }
        var storage = universeStorage(universeId);
        try {
            Files.readAttributes(storage, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return corrupt(new IllegalStateException("orphan storage：catalog 沒有這筆紀錄，storage 目錄卻已存在；不採用、不刪除：" + storage));
        } catch (NoSuchFileException absent) {
            // 正常：這個 UUID 從未物化過。
        } catch (IOException failure) {
            return Lookup.failed(Lookup.Status.UNREADABLE, SealedReason.AUTHORITY_UNREADABLE,
                    new UncheckedIOException("無法確認 storage 目錄是否存在：" + storage, failure));
        }
        UniverseRecord allocated;
        try {
            long ordinal = Math.addExact(flushed.values().stream().mapToLong(record -> record.definition().allocationOrdinal()).max().orElse(-1), 1);
            allocated = state.allocateOverworld(universeId.value(), ordinal);
        } catch (RuntimeException failure) {
            return corrupt(new IllegalStateException("catalog 配置失敗", failure));
        }
        Lookup<UniverseRecord> written = flushCatalog(state);
        return written == null ? Lookup.appended(allocated) : written;
    }

    /**
     * 唯讀的 catalog 查詢（spec §8.1 EXISTING 重驗用；F4a fix round 2，m-2，與 {@link #discovery} 同一個風格）：回 flushed catalog 的這筆
     * （{@code FOUND}，是否符合 availability 與 descriptor 由呼叫端以 {@link #catalogRecordMatches} 判斷）或 {@code ABSENT}。不配置、不寫出、不讀磁碟。
     * <ul>
     * <li>M5 初始化失敗 → 初始化的失敗結果；catalog 不健康 → {@code CORRUPT}。</li>
     * <li>catalog 有未確認的寫入（{@link #unconfirmed}）→ {@code WAIT}：未確認期間不做新決策（§6 收斂共通第 2 點），呼叫端等下一個 tick 或先
     *     {@link #convergeIfNeeded}，不判 {@code EXISTING_INELIGIBLE}，也不 SEALED。</li>
     * <li>已確認時與 {@link #allocateOrVerifyCatalog} 相同，清除 catalog 的退避與 WARN 去重（本 instance 曾寫出失敗時記 INFO「收斂完成」）。</li>
     * </ul>
     */
    public Lookup<UniverseRecord> catalogRecord(UniverseId universeId) {
        requireThread();
        Objects.requireNonNull(universeId, "universeId");
        Lookup<UniverseRecord> gate = catalogGate("不查詢");
        if (gate != null) return gate;
        var record = catalog.catalog().flushedRecords().get(universeId);
        return record == null ? Lookup.absent() : Lookup.found(record);
    }

    /**
     * checked 變更一筆 catalog record 的 availability（spec §8.3；F4a fix round 2，m-2），沿用 catalog 的收斂簿記：寫出並讀回確認時清除退避與 WARN
     * 去重（本 instance 曾寫出失敗時記 INFO「收斂完成」），回 {@code APPENDED}；寫出或讀回失敗時以權威為單位退避（之後的 {@link #convergeIfNeeded}
     * 在退避期內不重寫）、同一原因只記一次 WARN，回 {@code WRITE_FAILED}／{@code ALLOCATION_IO_FAILED}（SEALED 的原因由呼叫端決定，§8.3 一律是
     * {@code MATERIALIZE_FAILED}）。失敗時 current 依 {@code direction}（spec §6 收斂規則表）：
     * <ul>
     * <li>{@link WriteDirection#ROLL_FORWARD}：保留新的 availability 並維持 dirty。不看 catalog 是否已確認：較保守的新內容必須立刻記下（§8.3「避免下次
     *     開服時 bootstrap 因這筆失敗而停服」），這次寫出本身也帶出 current 中其他尚未確認的寫入（與之後的任何寫出相同）。</li>
     * <li>{@link WriteDirection#RESTORE}：改回變更前的 availability 並維持 dirty，下一次寫出以它覆寫磁碟。之後的物化依賴這次變更，是新決策：catalog
     *     有未確認的寫入時回 {@code WAIT}、不變更（§6 收斂共通第 2 點）。</li>
     * </ul>
     * 其餘：M5 初始化失敗 → 初始化的失敗結果；catalog 不健康 → {@code CORRUPT}；catalog 已確認、這筆已是 {@code availability} → {@code FOUND}，不寫出。
     * 呼叫前提（違反時丟 {@link IllegalArgumentException}，不是結果狀態）：catalog 的 current 有這筆 record。
     */
    public Lookup<UniverseRecord> changeCatalogAvailability(UniverseId universeId, DesiredAvailability availability, WriteDirection direction) {
        requireThread();
        Objects.requireNonNull(universeId, "universeId"); Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(direction, "direction");
        Lookup<UniverseRecord> initialization = initializationGate();
        if (initialization != null) return initialization;
        var state = catalog.catalog();
        UniverseRecord previous;
        try {
            state.requireHealthy();
            previous = state.records().get(universeId);
        } catch (IllegalStateException unhealthy) {
            return corrupt(new IllegalStateException("catalog 不健康，不變更 availability", unhealthy));
        }
        if (previous == null) throw new IllegalArgumentException("catalog 沒有這筆 record，不能變更 availability：" + universeId.value());
        boolean unconfirmed = catalogUnconfirmed(state);
        if (direction == WriteDirection.RESTORE && unconfirmed) return Lookup.waiting();
        if (!unconfirmed) {
            catalogConfirmed();
            if (previous.desiredAvailability() == availability) return Lookup.found(previous);
        }
        UniverseRecord changed;
        try {
            changed = state.changeAvailability(universeId, availability);
        } catch (RuntimeException failure) {
            return corrupt(new IllegalStateException("catalog 的 availability 變更失敗", failure));
        }
        Runnable restore = direction == WriteDirection.RESTORE ? () -> {
            state.changeAvailability(universeId, previous.desiredAvailability());
            if (!state.isDirty()) state.markDirty();
        } : null;
        Lookup<UniverseRecord> written = flushCatalog(state, restore);
        return written == null ? Lookup.appended(changed) : written;
    }

    /**
     * spec §6.2 的既有 catalog record 驗證（§8.1 的 EXISTING 重驗也適用）：record 的 definition 必須 exact 等於 M3 以同一個 UUID 與 ordinal
     * 配置的 OVERWORLD 內容（恰一個 OVERWORLD descriptor、world key、{@code VANILLA_OVERWORLD_SHARED_SEED_V1}、{@code SHARED_SAVE_SEED_V1}、
     * storage policy 1），而且 availability 為 {@code availability} 接受的值。不比對 AllocationOrdinal（配置時才決定）。目前 M3 的
     * {@code UniverseDefinition} 建構子已經不允許其他 descriptor 內容（strict decode 就會讓 catalog 不健康），這裡仍 exact 比對，防守日後放寬。
     * @param availability 只有 NEW 的 RESOLVE（本 session 推導的 UniverseId）可以傳 {@link AvailabilityPolicy#MATERIALIZE_RETRY}（也接受
     *     {@code DISABLED}，F4a fix round 1）；其餘一律 {@link AvailabilityPolicy#EAGER_ONLY}
     */
    public static boolean catalogRecordMatches(UniverseRecord record, UniverseId universeId, AvailabilityPolicy availability) {
        Objects.requireNonNull(record, "record"); Objects.requireNonNull(universeId, "universeId"); Objects.requireNonNull(availability, "availability");
        var definition = record.definition();
        var expected = new UniverseRegistry().allocateOverworld(universeId.value(), definition.allocationOrdinal()).definition();
        return definition.equals(expected) && availability.accepts(record.desiredAvailability());
    }

    /**
     * spec §6 的收斂旗標：該權威有尚未經 checked 讀回確認的 roll-forward 寫入。catalog 由 catalog 本身推導（仍 dirty，或 current 不等於
     * flushed；catalog 不健康時為真）：原生 autosave 寫出成功之後就自動回到已確認。
     */
    public boolean unconfirmed(Authority authority) {
        requireThread();
        if (Objects.requireNonNull(authority, "authority") == Authority.CATALOG) return catalogUnconfirmed(catalog.catalog());
        return slot(authority).unconfirmed;
    }

    /**
     * 下一次使用前的收斂：權威未確認、而且不在退避期內時，以 checked 方式重寫 current（含所有可能已落地的追加；catalog 為 checked 寫出 current）。
     * 成功即確認並清除退避；失敗時維持未確認並加倍退避（{@link CollapseBackoff}，以權威為單位）。初始化失敗或已遺失時不做任何事
     * （初始化失敗的 instance 沒有任何 M5 寫入）；catalog 已由原生 autosave 確認時只清除退避。
     */
    public void convergeIfNeeded(Authority authority) {
        requireThread();
        Objects.requireNonNull(authority, "authority");
        if (init == null || !init.succeeded()) return;
        if (authority == Authority.CATALOG) {
            convergeCatalog();
            return;
        }
        var slot = slot(authority);
        if (slot.lost != null || !slot.unconfirmed) return;
        if (!slot.backoff.ready(ticks.getAsInt())) return;
        rewrite(slot);
    }

    private <L> void rewrite(Slot<L> slot) { checkedWrite(slot, slot.current); }

    /** 查詢前的共通閘門：未初始化是 caller 錯誤；初始化失敗、遺失、未確認各回對應的失敗結果；可以使用時為 null。 */
    private <T> Lookup<T> gate(Slot<?> slot) {
        Lookup<T> initialization = initializationGate();
        if (initialization != null) return initialization;
        if (slot.lost != null) return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, slot.lost);
        if (slot.unconfirmed) {
            return Lookup.failed(Lookup.Status.UNCONFIRMED, slot.writeFailure,
                    new IllegalStateException(slot.authority + " 有尚未確認的寫入，收斂之前不做新決策"));
        }
        return null;
    }

    /** 四個權威共用的初始化閘門（fail closed）：未初始化是 caller 錯誤；初始化失敗回對應的失敗結果；成功時為 null。 */
    private <T> Lookup<T> initializationGate() {
        if (init == null) throw new IllegalStateException("M5 authority 尚未初始化");
        if (!init.succeeded()) {
            var reason = init.sealedReason().orElseThrow();
            var status = reason == SealedReason.AUTHORITY_CORRUPT ? Lookup.Status.CORRUPT : Lookup.Status.UNREADABLE;
            return Lookup.failed(status, reason, new IllegalStateException("M5 authority 初始化失敗（allocation=" + init.allocation()
                    + "，projection=" + init.projection() + "）", init.failure().orElseThrow()));
        }
        return null;
    }

    private static <T> Lookup<T> corrupt(RuntimeException failure) {
        return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, failure);
    }

    /** 把失敗的 Lookup 轉成另一個 entry 型別（失敗結果沒有 entry）。 */
    private static <T> Lookup<T> failedAs(Lookup<?> failed) {
        return Lookup.failed(failed.status(), failed.sealedReason().orElseThrow(), failed.failure().orElseThrow());
    }

    /**
     * discovery 的 strict 讀取（每次都從磁碟讀，與 M4 的讀取點相同）。成功為 {@code FOUND}（值是整份 state）；檔案不存在為遺失（{@code CORRUPT}，
     * 標記遺失、記一次 ERROR、不重建）；讀檔時的 IO 例外為 {@code UNREADABLE}；讀到 bytes 之後的失敗為 {@code CORRUPT}。
     */
    private Lookup<UniverseDiscoveryState> readDiscovery() {
        byte[] bytes;
        try {
            bytes = io.read(discovery.path);
        } catch (NoSuchFileException missing) {
            return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, markLost(discovery, missing));
        } catch (IOException failure) {
            return Lookup.failed(Lookup.Status.UNREADABLE, SealedReason.AUTHORITY_UNREADABLE,
                    new UncheckedIOException(discovery.authority + " 讀取時 IO 例外", failure));
        }
        try {
            return Lookup.found(UniverseDiscoveryState.fromNbt(CheckedNbtFile.parse(bytes)));
        } catch (IOException | RuntimeException failure) {
            return corrupt(new IllegalStateException(discovery.authority + " strict decode 失敗", failure));
        }
    }

    /** spec §6.1／§6.5：執行期間發現檔案消失，一律視為遺失；本 instance 之後一律 {@code CORRUPT}，不重建，current 不變，只記一次 ERROR。 */
    private RuntimeException markLost(Slot<?> slot, NoSuchFileException missing) {
        if (slot.lost == null) {
            slot.lost = new IllegalStateException(slot.authority + " 在執行期間消失，視為遺失", missing);
            LOGGER.error(CollapseService.TICK_LOG_MARKER, "M5 {} 的檔案在執行期間消失，視為遺失（AUTHORITY_CORRUPT）；不重建，需要管理員處理：{}", slot.authority, slot.path);
        }
        return slot.lost;
    }

    /** catalog 的未確認狀態由 catalog 推導：仍 dirty（含 restore 之後 current 已等於 flushed、但還沒寫出），或 current 不等於 flushed。 */
    private static boolean catalogUnconfirmed(UniverseRegistryState state) {
        try {
            return state.isDirty() || !state.records().equals(state.flushedRecords());
        } catch (IllegalStateException unhealthy) {
            return true;
        }
    }

    private void convergeCatalog() {
        var state = catalog.catalog();
        if (!catalogUnconfirmed(state)) {
            catalogConfirmed();
            return;
        }
        try {
            state.requireHealthy();
        } catch (IllegalStateException unhealthy) {
            return;
        }
        if (!catalogBackoff.ready(ticks.getAsInt())) return;
        flushCatalog(state);
    }

    /**
     * catalog 的配置與唯讀查詢共用的閘門（fail closed）：M5 初始化失敗 → 初始化的失敗結果；catalog 不健康 → {@code CORRUPT}（訊息帶
     * {@code refusal}）；有未確認的寫入 → {@code WAIT}，不做新決策。可以使用時以 {@link #catalogConfirmed()} 清除收斂簿記，回 null。
     */
    private <T> Lookup<T> catalogGate(String refusal) {
        Lookup<T> initialization = initializationGate();
        if (initialization != null) return initialization;
        var state = catalog.catalog();
        try {
            state.requireHealthy();
        } catch (IllegalStateException unhealthy) {
            return corrupt(new IllegalStateException("catalog 不健康，" + refusal, unhealthy));
        }
        if (catalogUnconfirmed(state)) return Lookup.waiting();
        catalogConfirmed();
        return null;
    }

    /** {@link #flushCatalog(UniverseRegistryState, Runnable)}，roll-forward（失敗時 current 保留）。 */
    private <T> Lookup<T> flushCatalog(UniverseRegistryState state) {
        return flushCatalog(state, null);
    }

    /**
     * catalog 的 checked 寫出。成功（之後 catalog 不再未確認）回 null；失敗時回 {@code WRITE_FAILED}／{@code ALLOCATION_IO_FAILED}，並記以權威為單位的
     * 退避與 WARN（同一原因一次）。{@code restore} 為 null 時是 spec §6 的 roll-forward：M3 的 save 已保留 current 與 dirty
     * （{@code UniverseRegistryState.save}），這裡不改 current；不為 null 時是 restore：先執行它（把 current 改回變更前的內容並維持 dirty），WARN 的字句也說明。
     */
    private <T> Lookup<T> flushCatalog(UniverseRegistryState state, Runnable restore) {
        RuntimeException failure = null;
        try {
            catalog.flush(state);
        } catch (RuntimeException flushFailure) {
            failure = flushFailure;
        }
        if (failure == null && !catalogUnconfirmed(state)) {
            catalogConfirmed();
            return null;
        }
        if (failure == null) failure = new IllegalStateException("CATALOG 寫出之後仍有未確認的寫入");
        if (restore != null) restore.run();
        catalogWriteFailed = true;
        catalogBackoff.failed(ticks.getAsInt());
        var key = FailureText.dedupeKey(failure);
        if (!key.equals(catalogWarned)) {
            catalogWarned = key;
            if (restore == null) {
                LOGGER.warn(CollapseService.TICK_LOG_MARKER, "M5 CATALOG 的 checked 寫出未確認：current 保留並維持 dirty，{} ticks 後才重試收斂（或由原生 autosave 寫出）：{}",
                        catalogBackoff.delayTicks(), FailureText.describe(failure), failure);
            } else {
                LOGGER.warn(CollapseService.TICK_LOG_MARKER, "M5 CATALOG 的 checked 寫出未確認：current 已改回變更前的內容（restore）並維持 dirty，{} ticks 後才重試收斂"
                        + "（或由原生 autosave 寫出）：{}", catalogBackoff.delayTicks(), FailureText.describe(failure), failure);
            }
        }
        return Lookup.failed(Lookup.Status.WRITE_FAILED, SealedReason.ALLOCATION_IO_FAILED, failure);
    }

    /** catalog 已確認（本 instance 的寫出或原生 autosave）：清除退避與 WARN 去重鍵；本 instance 曾寫出失敗時記 INFO。 */
    private void catalogConfirmed() {
        if (catalogWriteFailed) LOGGER.info(CollapseService.TICK_LOG_MARKER, "M5 CATALOG 收斂完成：catalog 的寫出已讀回確認");
        catalogWriteFailed = false;
        catalogBackoff.reset();
        catalogWarned = null;
    }

    /** 這個 UUID 的 universe storage 目錄（與 M3 {@code UniverseRegistryState.inspectStorage} 相同的推導：{@code <save>/dimensions/<namespace>/<path>} 的上一層）。 */
    private Path universeStorage(UniverseId universeId) {
        var key = UniverseKeys.world(universeId, DimensionRole.OVERWORLD).getValue();
        return saveRoot.resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()).normalize().getParent();
    }

    /**
     * 執行期間的 checked 寫入（追加或收斂）。成功回 null；失敗回對應的 Lookup：
     * 檔案在寫入前已消失 → 遺失（{@code CORRUPT}，不重建）；其他寫入或讀回失敗 → roll-forward（current 保留 next、標為未確認、退避）。
     */
    private <L, T> Lookup<T> checkedWrite(Slot<L> slot, L next) {
        try {
            Files.readAttributes(slot.path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return Lookup.failed(Lookup.Status.CORRUPT, SealedReason.AUTHORITY_CORRUPT, markLost(slot, missing));
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
        if (converged) LOGGER.info(CollapseService.TICK_LOG_MARKER, "M5 {} 收斂完成：checked 重寫已讀回確認", slot.authority);
        return null;
    }

    /** spec §6 的 roll-forward：寫入或讀回失敗時 current 保留新內容（絕不移除可能已落地的追加），標為未確認並退避；同一原因只記一次 WARN。 */
    private <L, T> Lookup<T> rollForward(Slot<L> slot, L next, Exception failure) {
        slot.current = next; slot.unconfirmed = true;
        slot.backoff.failed(ticks.getAsInt());
        var key = FailureText.dedupeKey(failure);
        if (!key.equals(slot.warned)) {
            slot.warned = key;
            LOGGER.warn(CollapseService.TICK_LOG_MARKER, "M5 {} 的 checked 寫入未確認：保留新內容並標為未確認，{} ticks 後才重試收斂：{}",
                    slot.authority, slot.backoff.delayTicks(), FailureText.describe(failure), failure);
        }
        var cause = failure instanceof RuntimeException runtime ? runtime : new UncheckedIOException(slot.authority + " checked 寫入失敗",
                failure instanceof IOException ioFailure ? ioFailure : new IOException(failure));
        return Lookup.failed(Lookup.Status.WRITE_FAILED, slot.writeFailure, cause);
    }

    /** {@link CheckedNbtFile#write}：暫存檔與 atomic move 之後的讀回都經過本權威的 {@link LedgerIo}，並以該權威的 strict decode 驗證。 */
    private <L> void writeAtomic(Slot<L> slot, L content) throws IOException {
        CheckedNbtFile.write(slot.path, slot.encode.apply(content), io, nbt -> slot.decode.apply(nbt), slot.authority.toString());
    }

    /** 檔案權威的 slot（catalog 是 PersistentState，沒有 slot）。 */
    private Slot<?> slot(Authority authority) {
        return switch (Objects.requireNonNull(authority, "authority")) {
            case ALLOCATION_LEDGER -> allocation;
            case PROJECTION_LEDGER -> projection;
            case DISCOVERY -> discovery;
            case CATALOG -> throw new IllegalArgumentException("CATALOG 沒有檔案 slot");
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

    /**
     * 一個檔案權威（兩個 ledger 與 discovery）：已確認內容、current（roll-forward 尚未確認時領先）、收斂旗標、遺失狀態與以權威為單位的退避。
     * discovery 不在初始化載入，confirmed／current 只在寫入之後才有值，查詢一律重新從磁碟讀。
     */
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
}
