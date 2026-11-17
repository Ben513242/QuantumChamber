package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M7HomeClosurePhases.FRAME;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.JSON;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.LOGGER;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.doorSummary;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.gate;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.listing;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.require;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.sha256;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.text;
import static dev.quantumchamber.gametest.M7HomeClosurePhases.writeDurably;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberPowerCoordinator;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.HomeClosure;
import dev.quantumchamber.collapse.HomeEndpointAuthority;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.RestartClosed;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.HomeSessionRecord.RootStop;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.HomeSessionRecord.Unselected;
import dev.quantumchamber.persistence.JournalSession;
import dev.quantumchamber.persistence.SessionJournalStore;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import net.minecraft.world.World;

/**
 * 僅 testmod（M7 T4 Phase B2a）：Windows 原生 close 矩陣以外的跨 JVM 鏈（plan Task 4 :265、:270、:89–90；M7 spec §5.3、§9.1、§9.2、§10、V03、V10）。
 * 每列兩個 phase、各一個獨立 JVM，沿 {@link M7HomeClosurePhases} 的 fixture、receipt 與 halt 做法：
 * <ul>
 * <li>{@code restart-enable-before-entry}：crash 時 registry 的 disabled 已寫出、journal 的 stop 還沒寫出（probe 持有同 SID 的 session operation guard，
 *     正式 {@code setEnabled(false)} 把根事件排進記憶體佇列）；reopen 在開服掃描之前 direct enable（必須 pending），掃描後 stop 必須已 checked，續行被拒，
 *     第二次 enable（A 修復）成功但本趟仍以 ROOT_DISABLED 收尾。</li>
 * <li>{@code restart-ready-stop-crossed}：READY、部分 Crossed、stop 已持久、A 已修復時 crash；reopen 以 ROOT_DISABLED（不是 RESTART_CLOSED）收尾，Crossed 保留。</li>
 * <li>{@code restart-ready-low-no-root}：READY、部分 Crossed、A 只是普通斷電（LOW）時 crash；reopen 開服掃描不 latch，以 RESTART_CLOSED 收尾，A 的 chunk 一直未載入。
 *     世界出生點移到遠離 A 的位置（T4 B2b-2），讓參與者登入時 vanilla 的出生搜尋在幾何上碰不到 A。</li>
 * <li>{@code receipt-existing}：收據已發佈、熱列移除失敗（journal flush 故障，RESTORED）時 crash；reopen 從 CLOSE 續做，只驗既有收據 exact、不覆寫、移除前不解鎖。</li>
 * <li>{@code receipt-player-closed}：成功收尾後玩家以正式方塊互動關 B 門、checkpoint 之後 crash；reopen 舊 SID 不重開門。</li>
 * <li>{@code receipt-mismatch-stop}（T4 P1）：同 {@code receipt-existing} 走到熱列移除失敗（RESTORED，journal dirty）之後，把收據換成同 SID、內容不同的合法收據，
 *     在 A、B 兩側放標記方塊、改一個 gamerule（level.dat），然後<em>正常停服</em>；停服存檔必須完成（SERVER_STOPPED），停服後只讀檔案核對熱列與竄改的收據都保留、
 *     管理員 ERROR 恰一則，再模擬管理員還原收據，halt(77)。reopen 核對標記與 gamerule 都已落盤、開服時仍占用，之後從 CLOSE 續做（同 {@code receipt-existing}）。</li>
 * <li>{@code receipt-write-fail-stop}（T4 P2）：同 {@code receipt-existing} 走到熱列移除失敗（RESTORED，journal dirty）之後，武裝 journal 正式寫出的 atomic move 故障
 *     （{@link M7NativeSaveFault}，Windows 上是真正的 AccessDenied），以原版 autosave 的同一條路徑存一次（失敗、保留 pending），在 A、B 兩側放標記方塊、改 gamerule，
 *     然後<em>正常停服</em>：停服時的存檔先核對磁碟、重試寫出、再次失敗，但不得丟進原版存檔鏈；停服必須完成（SERVER_STOPPED），停服後只讀檔案核對 journal 檔不變、
 *     收據不變、原生存檔的 ERROR（TICK marker）恰一則，解除故障後 halt(77)。reopen 核對標記與 gamerule 都已落盤，之後從 CLOSE 續做（同 {@code receipt-existing}）。</li>
 * </ul>
 * 預期終局見 SDD {@code t4b2a-report.md}；Java（本類）與 runner（{@code m7-home-probe.ps1}）各自核對。
 */
final class M7HomeChainPhases {
    static final List<String> NAMES = List.of("restart-enable-before-entry", "restart-ready-stop-crossed", "restart-ready-low-no-root",
            "receipt-existing", "receipt-player-closed", "receipt-mismatch-stop", "receipt-write-fail-stop");
    /** 收尾的完整事件序列（正式 owner 從沒有 CLOSE 起推進到 checked 移除）。 */
    static final List<String> FULL = M7HomeClosurePhases.CUTS;
    /** 從已凍結 CLOSE 續做、收據已存在（沿 B1 群組 C）。 */
    static final List<String> FROM_EXISTING = List.of("before-door", "after-door", "before-checkpoint", "after-checkpoint", M7HomeClosurePhases.VERIFY_EXISTING,
            "after-recheck", "before-remove", "after-remove");
    static final int READY_TICKS = 200, CUT_TICKS = 200, CLOSE_TICKS = 400, QUIET_TICKS = 40;
    /**
     * restart-ready-low-no-root 的世界出生點 XZ（T4 B2b-2）。reopen 時參與者登入，vanilla 1.21 的 {@code ServerPlayerEntity} 建構子以 overworld 呼叫
     * {@code getWorldSpawnPos}：在出生點 ±i 內以不固定 seed 的亂數挑欄，交給 {@code SpawnLocating.findOverworldSpawn} 同步載入那一欄的 chunk（之後玩家資料才把人放回 B）。
     * 原本出生點 (0,y,0) 時，方形與 A 的 chunk (0,0) 重疊，約 121/441 的欄會讓 A 在開服後第 1 個 tick 被暫時載入。移到這裡之後，方形在幾何上碰不到 A，
     * 由 {@link Run#spawnSearchAvoidsA} 在 crash 與 reopen 兩端核對。
     */
    static final int LOW_SPAWN_X = -256, LOW_SPAWN_Z = -256;

    private M7HomeChainPhases() {}

    enum Chain {
        RESTART_ENABLE("restart-enable-before-entry", "restart", "ordinary", "registry-saved"),
        RESTART_READY_STOP("restart-ready-stop-crossed", "restart", "passable", "repaired-saved"),
        RESTART_READY_LOW("restart-ready-low-no-root", "restart", "passable", "low-saved"),
        RECEIPT_EXISTING("receipt-existing", "receipt", "ordinary", "remove-rejected"),
        RECEIPT_PLAYER_CLOSED("receipt-player-closed", "receipt", "ordinary", "player-closed"),
        /** halt 點 {@code stopped}：正常停服完成之後（SERVER_STOPPED），不是正式 owner 的事件。 */
        RECEIPT_MISMATCH_STOP("receipt-mismatch-stop", "receipt", "ordinary", "stopped"),
        /** halt 點 {@code stopped}（同上）：journal 正式寫出故障期間正常停服完成之後（T4 P2）。 */
        RECEIPT_WRITE_FAIL_STOP("receipt-write-fail-stop", "receipt", "ordinary", "stopped");

        final String row, family, door, crashPoint;

        Chain(String row, String family, String door, String crashPoint) {
            this.row = row; this.family = family; this.door = door; this.crashPoint = crashPoint;
        }

        static Chain of(String row) {
            for (var chain : values()) if (chain.row.equals(row)) return chain;
            throw new IllegalStateException("M7_HOME_ROW_UNKNOWN：" + row);
        }

        boolean ready() { return this == RESTART_READY_STOP || this == RESTART_READY_LOW; }

        /** crash 當下收據已發佈、熱列仍在（RESTORED）：reopen 從已凍結的 CLOSE 續做、只驗既有收據。 */
        boolean existingReceipt() { return this == RECEIPT_EXISTING || stopsNormally(); }

        /** crash phase 以正常停服結束（halt 點在 SERVER_STOPPED 之後），不是 crash 之前就 halt。 */
        boolean stopsNormally() { return this == RECEIPT_MISMATCH_STOP || this == RECEIPT_WRITE_FAIL_STOP; }

        /** crash phase 在 halt 之前的收尾事件。 */
        List<String> crashEvents() {
            return switch (this) {
                case RESTART_ENABLE, RESTART_READY_STOP, RESTART_READY_LOW -> List.of();
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> {
                    var events = new ArrayList<>(FULL.subList(0, FULL.indexOf("before-remove") + 1));
                    events.add("remove-rejected");
                    yield List.copyOf(events);
                }
                case RECEIPT_PLAYER_CLOSED -> FULL;
            };
        }

        List<String> reopenEvents() {
            return switch (this) {
                case RESTART_ENABLE, RESTART_READY_STOP, RESTART_READY_LOW -> FULL;
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> FROM_EXISTING;
                case RECEIPT_PLAYER_CLOSED -> List.of();
            };
        }

        /** reopen 第一次改門的 outcome；不改門時為空。 */
        List<String> reopenDoorOutcome() {
            return switch (this) {
                case RESTART_ENABLE, RESTART_READY_STOP, RESTART_READY_LOW -> List.of("APPLIED");
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> List.of("UNCHANGED");
                case RECEIPT_PLAYER_CLOSED -> List.of();
            };
        }

        ChamberDoorService.DoorState initialDoor() {
            return door.equals("passable") ? ChamberDoorService.DoorState.passable(FRAME.outwardFacing()) : ChamberDoorService.DoorState.CLOSED;
        }

        String initialDoorSummary() { return door.equals("passable") ? "OPEN/PASSABLE_X" : "CLOSED/NONE"; }

        /** reopen 開服時 B 門的磁碟狀態。 */
        String diskDoorAtReopen() {
            return switch (this) {
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> "OPEN/NONE";
                default -> initialDoorSummary();
            };
        }
    }

    /** {@code receipt-mismatch-stop} 的標記方塊：控制器正上方、艙體頂端再高 6 格（A 與 B 同一個座標；在測試票保持的 chunk 內、不在艙體與門口）。 */
    static BlockPos marker() {
        return new BlockPos(FRAME.controllerPos().getX(), ChamberGeometry.bounds(FRAME).getMaxY() + 6, FRAME.controllerPos().getZ());
    }

    private enum Step { SETUP, READY, AWAIT_CUT, STOP_MISMATCH, STOP_WRITE_FAIL, AWAIT_CRASH_CLOSE, AWAIT_CLOSE, QUIET, DONE }

    /** 一個 phase 的 runner；只在 server thread 執行（cut 事件由正式 owner 在 server thread 呼叫）。 */
    static final class Run implements M7HomeProbe.Phase {
        private static final Identifier EARLY = Identifier.of("quantumchamber:m7_chain_early");
        private static final Identifier AFTER = Identifier.of("quantumchamber:m7_chain_after");
        private static final Identifier BEFORE = Identifier.of("quantumchamber:m7_chain_before");
        final M7HomeClosurePhases.Configuration config;
        final Chain chain;
        private final Map<String, Object> proof = new LinkedHashMap<>();
        private final List<String> failures = new ArrayList<>();
        private final Map<String, Boolean> observed = new LinkedHashMap<>();
        private final List<Map<String, Object>> events = new ArrayList<>();
        private final List<Map<String, Object>> checkpoints = new ArrayList<>();
        private final Map<String, Object> early = new LinkedHashMap<>();
        private final Map<String, Object> startFiles = new LinkedHashMap<>();
        private MinecraftServer server;
        private ServerWorld overworld, b;
        private UUID chamber, sid, participantId, fakeId;
        private String participantName;
        private ConnectedGameTestPlayer participant;
        private ChamberOriginAuthority root;
        private EntryEndpoint entry;
        private Map<String, Object> pose;
        private List<String> homeChunks;
        private Step step = Step.SETUP;
        private int stepTicks, ticks, hits;
        private boolean armed, finished, passed;
        private volatile boolean stoppingSeen;
        private JsonObject crash;
        /** reopen：收尾前的那一列（收據的 Session 必須等於它只把 CloseIntent 正規化為 NONE）。 */
        private String closingRowText;
        /** reopen：預期的 RootStop 時間（RESTART_ENABLE 是開服掃描的 latch 時間，其他是 crash 的 StoppedAt）。 */
        private long expectedStoppedAt = -1;
        private UUID startupInstance;
        private int eventsBeforeQuiet, checkpointsBeforeQuiet;
        private List<Map<String, Object>> receiptsBeforeQuiet;
        /** 世界的存檔根（開服時記下；停服之後的檔案核對不再經 server）。 */
        private Path saveRoot;
        /** receipt-mismatch-stop：quantumchamber logger 的 ERROR 擷取、原本的收據 bytes、停服步驟的事實與停服前的快照、停服前的 journal（只讀 isDirty）。 */
        private LogCapture logs;
        private byte[] originalReceipt;
        private final Map<String, Object> stopFacts = new LinkedHashMap<>();
        private Map<String, Object> preStop;
        private SessionRecoveryState stopJournal;
        /** receipt-write-fail-stop：武裝中的 journal 寫出故障（停服之後才解除）。 */
        private M7NativeSaveFault.Handle stopFault;
        /**
         * receipt-mismatch-stop：probe 自己在暫存存檔根以正式 {@code HomeReceiptStore.publishOrVerify} 產生竄改用收據的期間。{@code M7HomeReceiptWindowMixin}
         * 無法區分存檔根，這段期間的事件不是正式 owner 的事件，不記入事件序列，只計數（T4 P1 試跑 1 的工具問題）。
         */
        private boolean scratchPublish;
        private int scratchEventsIgnored;

        Run(M7HomeClosurePhases.Configuration config) {
            this.config = config;
            this.chain = Chain.of(config.row());
        }

        private boolean crashPhase() { return config.phase().equals("crash"); }

        @Override public void start() {
            proof.put("producer", "M7HomeProbe");
            proof.put("m7ProbeSchema", 1);
            proof.put("row", chain.row);
            proof.put("family", chain.family);
            proof.put("door", chain.door);
            proof.put("cut", chain.crashPoint);
            proof.put("group", chain.family.toUpperCase(java.util.Locale.ROOT));
            proof.put("phase", config.phase());
            proof.put("rowTable", M7HomeClosurePhases.rows());
            proof.put("nonce", config.nonce());
            proof.put("startupNonce", config.startupNonce());
            proof.put("pid", ProcessHandle.current().pid());
            proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            proof.put("root", config.root().toString());
            proof.put("runtimeScope", "testmod-present");
            proof.put("loadedModIds", FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList());
            proof.put("failures", failures);
            if (!crashPhase()) {
                // 開服之前讀 crash 留下的原件：journal、registry 與收據目錄的 bytes 就是 crash 當下的狀態（還沒有任何程式碰過）。
                try {
                    crash = JsonParser.parseString(Files.readString(config.evidence().resolveSibling("crash").resolve("halt.json"))).getAsJsonObject();
                    var setup = crash.getAsJsonObject("setup");
                    chamber = UUID.fromString(text(setup, "chamber"));
                    sid = UUID.fromString(text(setup, "sid"));
                    participantId = UUID.fromString(text(setup.getAsJsonObject("participant"), "uuid"));
                    participantName = text(setup.getAsJsonObject("participant"), "name");
                    var data = config.root().resolve("world/data");
                    startFiles.put("journalSha256", sha256(data.resolve(SessionRecoveryState.STATE_ID + ".dat")));
                    startFiles.put("registrySha256", sha256(data.resolve(ChamberRegistryState.STATE_ID + ".dat")));
                    startFiles.put("receipts", listing(data.resolve("quantumchamber_home_receipts")));
                } catch (Exception | Error failure) {
                    failures.add("crashReceiptUnreadable：" + failure);
                    LOGGER.error("M7 home chain：讀不到 crash 的原件", failure);
                }
                proof.put("startFiles", startFiles);
                proof.put("early", early);
                ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(EARLY, Event.DEFAULT_PHASE);
                ServerLifecycleEvents.SERVER_STARTED.register(EARLY, this::early);
            }
            ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerLifecycleEvents.SERVER_STARTED.register(AFTER, this::started);
            ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerTickEvents.END_SERVER_TICK.register(AFTER, this::tick);
            ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(BEFORE, Event.DEFAULT_PHASE);
            ServerLifecycleEvents.SERVER_STOPPING.register(BEFORE, this::stopping);
            ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
            ServerLifecycleEvents.SERVER_STOPPED.register(AFTER, this::stopped);
            LOGGER.info("M7_HOME_BEGIN row={} phase={} startupNonce={}", chain.row, config.phase(), config.startupNonce());
        }

        // ---- 生命週期 ----

        /**
         * reopen：SERVER_STARTED 的預設 phase 之前（世界、journal 與 registry 已可讀，{@code HomeRootFence} 的開服掃描尚未執行）。記下磁碟載入的狀態；
         * {@code restart-enable-before-entry} 在這裡做首個 maintenance call（direct enable），必須 pending。
         */
        private void early(MinecraftServer owner) {
            try {
                require(failures.isEmpty(), "M7_HOME_PRESTART_FAILED " + failures);
                homeChunks = M7HomeClosurePhases.homeChunks();
                var ow = owner.getOverworld();
                var journal = SessionRecoveryState.get(owner);
                var registry = ChamberRegistryState.get(owner).registry();
                var flushed = journal.allFlushedSessions().get(sid);
                early.put("gameTime", ow.getTime());
                early.put("row", flushed == null ? null : String.valueOf(flushed));
                early.put("stopped", flushed instanceof HomeSessionRecord h && h.home().stopReason().isPresent());
                var record = registry.records().get(chamber);
                early.put("registryEnabled", record != null && record.enabled());
                early.put("registryPowerState", record == null ? "ABSENT" : record.powerState().name());
                early.put("aLoaded", aLoaded(ow));
                if (chain == Chain.RESTART_ENABLE) {
                    boolean enabled = new ChamberLifecycleService(registry).setEnabled(chamber, true);
                    var after = registry.records().get(chamber);
                    var rowAfter = journal.allSessions().get(sid);
                    early.put("enableBeforeScan", enabled);
                    early.put("registryEnabledAfterEnable", after != null && after.enabled());
                    early.put("rowAfterEnable", rowAfter == null ? null : String.valueOf(rowAfter));
                }
            } catch (Exception | Error failure) {
                failures.add("early：" + failure);
                LOGGER.error("M7 home chain：開服掃描前的觀察失敗", failure);
            }
        }

        private void started(MinecraftServer owner) {
            if (server != null) return;
            server = owner;
            overworld = owner.getOverworld();
            guarded(() -> {
                require(failures.isEmpty(), "M7_HOME_PRESTART_FAILED " + failures);
                require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world").toRealPath()), "M7_HOME_SAVE_ROOT");
                saveRoot = owner.getSavePath(WorldSavePath.ROOT);
                homeChunks = M7HomeClosurePhases.homeChunks();
                if (crashPhase()) setupCrash(); else setupReopen();
            });
        }

        private void tick(MinecraftServer owner) {
            if (owner != server || finished || step == Step.SETUP) return;
            guarded(() -> {
                ticks++;
                switch (step) {
                    case READY -> ready();
                    case AWAIT_CUT -> awaitCut();
                    case STOP_MISMATCH -> stopMismatch();
                    case STOP_WRITE_FAIL -> stopWriteFail();
                    case AWAIT_CRASH_CLOSE -> awaitCrashClose();
                    case AWAIT_CLOSE -> awaitClose();
                    case QUIET -> quiet();
                    default -> { }
                }
            });
        }

        private void stopping(MinecraftServer owner) {
            if (owner != server) return;
            stoppingSeen = true;
            proof.put("stoppingSeen", true);
            try { if (participant != null) participant.close(); }
            catch (RuntimeException failure) { failures.add("closeParticipant：" + failure); }
        }

        private void stopped(MinecraftServer owner) {
            if (owner != server) return;
            if (crashPhase() && chain == Chain.RECEIPT_MISMATCH_STOP) { stoppedWithMismatch(); return; }
            if (crashPhase() && chain == Chain.RECEIPT_WRITE_FAIL_STOP) { stoppedWithWriteFailure(); return; }
            proof.put("stoppedSeen", true);
            proof.put("ending", "STOP");
            proof.put("events", events);
            proof.put("checkpoints", checkpoints);
            proof.put("observed", observed);
            proof.put("ticks", ticks);
            proof.put("status", passed && failures.isEmpty() && stoppingSeen ? "PASS" : "FAIL");
            try { writeDurably(config.evidence().resolve("final.json"), JSON.toJson(proof)); }
            catch (IOException failure) { LOGGER.error("M7 home chain：final receipt 寫不出來", failure); }
            LOGGER.info("M7_HOME_END row={} phase={} status={}", chain.row, config.phase(), proof.get("status"));
        }

        private void stop() {
            if (finished) return;
            finished = true;
            proof.put("stopRequested", true);
            server.stop(false);
        }

        private void guarded(M7HomeClosurePhases.Checked action) {
            try { action.run(); }
            catch (Exception | Error failure) {
                failures.add(failure.toString());
                LOGGER.error("M7 home chain 失敗：row={} phase={}", chain.row, config.phase(), failure);
                if (server != null) stop();
            }
        }

        // ---- crash phase ----

        private void setupCrash() throws Exception {
            var journal = SessionRecoveryState.get(server);
            expect("freshJournal", journal.allSessions().isEmpty() && journal.allFlushedSessions().isEmpty(), () -> "journal=" + journal.allSessions().keySet());
            expect("freshCatalog", UniverseRegistryState.get(server).flushedRecords().isEmpty(), () -> "catalog 不是空的");
            expect("freshReceipts", !Files.exists(receiptsDir()), () -> "收據目錄已存在");
            // A：沿 B1 的原艙寫法（不供電），參與者帶 QuantumState 在艙內，以正式 attemptArm 登記。測試票留到 JVM 結束。
            overworld.getChunk(FRAME.controllerPos());
            M7HomeClosurePhases.Run.placeChamber(overworld);
            M7HomeClosureGameTests.holdChunks(overworld, FRAME);
            participantId = UUID.randomUUID();
            participantName = "m7-" + participantId.toString().substring(0, 8);
            participant = connect();
            var player = participant.player();
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
            M5GameTestSupport.moveInside(player, FRAME);
            var controller = (ChamberControllerBlockEntity) overworld.getBlockEntity(FRAME.controllerPos());
            var arm = new ChamberActivationService().attemptArm(overworld, controller);
            expect("originRegistered", arm.accepted() && controller.chamberUuid() != null, () -> "arm=" + arm);
            chamber = controller.chamberUuid();
            M7HomeClosureGameTests.requireCandidateStores(server);
            // B：同 Chamber 已確認 COMPLETE 的投影（沿 B1；以測試票保持 B 的 chunks）。
            var universe = UniverseId.of(UUID.randomUUID());
            b = M6ProfileProbePhases.fixtureSource(server, universe, "normal");
            var authorities = CollapseAuthorities.get(server);
            var index = ProjectionProtectionIndex.get(server);
            var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, universe, DimensionRole.OVERWORLD, FRAME.controllerPos(),
                    FRAME.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
            index.confirmed(pending);
            M7HomeClosureGameTests.holdChunks(b, FRAME);
            var written = ProjectionWriter.write(b, FRAME, ProjectionWriter.Mode.FIRST_WRITE);
            expect("projectionWritten", written.status() == ProjectionWriter.Status.WRITTEN, () -> "write=" + written);
            ((ChamberControllerBlockEntity) b.getBlockEntity(FRAME.controllerPos())).setInstanceKind(ChamberInstanceKind.PROJECTION);
            index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
            entry = new EntryEndpoint(chamber, universe, b.getRegistryKey().getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), FRAME.outwardFacing(),
                    ChamberInstanceKind.PROJECTION);
            root = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), FRAME.outwardFacing(),
                    ChamberInstanceKind.ORIGIN);
            expect("entryValid", HomeEndpointAuthority.inspectEntry(server, entry) == HomeEndpointAuthority.EntryStatus.VALID,
                    () -> "inspectEntry=" + HomeEndpointAuthority.inspectEntry(server, entry));
            var applied = new ChamberDoorService().applyTarget(b, FRAME, chain.initialDoor());
            expect("bDoorInitial", applied.succeeded() && chain.initialDoorSummary().equals(doorSummary(b)), () -> applied + " door=" + doorSummary(b));
            sid = UUID.randomUUID();
            fakeId = chain.ready() ? UUID.randomUUID() : null;
            var row = chain.ready() ? readyCrossed(journal) : dormant(journal);
            // 參與者已返回 B：站在 B 艙內正中（不在門口），之後每個 tick 核對零移動。
            var spot = ChamberSpaceCoordinates.position(FRAME, 3.5, 1, 3.5);
            expect("participantInB", player.teleport(b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && player.getServerWorld() == b, () -> "world=" + player.getServerWorld());
            if (chain == Chain.RESTART_READY_LOW) {
                // A（chunk 0,0）在 spawn (0,72,0) 的 spawn chunks 內；設為 0（寫進 level.dat），reopen 時 A 不會因 spawn ticket 而載入。
                server.getGameRules().get(GameRules.SPAWN_CHUNK_RADIUS).set(0, server);
                expect("spawnChunkRadiusZero", server.getGameRules().getInt(GameRules.SPAWN_CHUNK_RADIUS) == 0, () -> "radius 未改");
                // T4 B2b-2：reopen 的登入出生搜尋也不得碰到 A（見 LOW_SPAWN_X）。出生點寫進 level.dat，由 ready() 的基線 checkpoint 落盤。
                overworld.setSpawnPos(new BlockPos(LOW_SPAWN_X, overworld.getSpawnPos().getY(), LOW_SPAWN_Z), overworld.getSpawnAngle());
                expect("spawnSearchAvoidsA", spawnSearchAvoidsA(), this::spawnSearchDetail);
                proof.put("spawnSearch", spawnSearchFacts());
            }
            var setup = new LinkedHashMap<String, Object>();
            setup.put("chain", chain.row);
            setup.put("chamber", chamber.toString());
            setup.put("sid", sid.toString());
            setup.put("universe", universe.toString());
            setup.put("entryWorld", b.getRegistryKey().getValue().toString());
            setup.put("participant", Map.of("uuid", participantId.toString(), "name", participantName));
            setup.put("crossedOffline", fakeId == null ? "NONE" : fakeId.toString());
            setup.put("homeChunks", homeChunks);
            setup.put("doorChunks", M7HomeClosurePhases.doorChunks());
            setup.put("homeRow", String.valueOf(row));
            proof.put("setup", setup);
            step = Step.READY;
        }

        /** 等 A、B 的相關 chunks 與 entity storage 就緒，之後依各列做 crash 前的操作。 */
        private void ready() throws Exception {
            var chunks = chunkSet();
            if (!ChunkSaveCheckpoint.ready(overworld, chunks) || !ChunkSaveCheckpoint.ready(b, chunks)) {
                require(++stepTicks <= READY_TICKS, "M7_HOME_CHUNKS_NOT_READY");
                return;
            }
            var journal = SessionRecoveryState.get(server);
            var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
            var aBefore = doorSummary(overworld);
            switch (chain) {
                case RESTART_ENABLE -> {
                    // 基線：A 仍啟用、journal 是沒有 stop 的 DORMANT，全部落盤。
                    checkpoint(chunks, true);
                    var dormantRow = (HomeSessionRecord) journal.allFlushedSessions().get(sid);
                    var baseline = baseline(dormantRow);
                    expect("baselineEnabled", Boolean.TRUE.equals(baseline.get("registryEnabled")) && dormantRow.home().stopReason().isEmpty(), () -> "baseline=" + baseline);
                    // 持有同 SID 的 session operation guard 時停用 A：根事件排進記憶體佇列、不寫 journal、不回報成功（M7 spec §9.1 :245、:249）。
                    // 同一個 guard 內做原生 checkpoint（registry 寫出 disabled；journal 不 dirty、不重寫），之後 crash。
                    CorridorPageManager.forServer(server).exclusiveOperation(sid, () -> {
                        guardedCrash(() -> {
                            boolean acked = lifecycle.setEnabled(chamber, false);
                            expect("disableNotAcked", !acked, () -> "guard 內停用回報成功");
                            expect("registryDisabledInMemory", !registryEnabled(), () -> "registry 記憶體沒有停用");
                            expect("stopNotWritten", dormantRow.equals(journal.allSessions().get(sid)) && dormantRow.equals(journal.allFlushedSessions().get(sid))
                                    && !journal.rollForwardPending(sid), () -> "row=" + journal.allSessions().get(sid));
                            expect("eventQueued", queued(), () -> "根事件沒有排隊");
                            checkpoint(chunks, true);
                            expect("journalFileUnchanged", sha256(journalFile()).equals(baseline.get("journalSha256")), () -> "journal 檔在第二次存檔時被重寫");
                            expect("registryFileRewritten", !sha256(registryFile()).equals(baseline.get("registrySha256")), () -> "registry 檔沒有寫出停用");
                            halt();
                        });
                        return null;
                    });
                }
                case RESTART_READY_STOP -> {
                    var readyRow = (HomeSessionRecord) journal.allFlushedSessions().get(sid);
                    expect("disableAcked", lifecycle.setEnabled(chamber, false), () -> "停用沒有回報成功");
                    var stopped = journal.allFlushedSessions().get(sid);
                    expect("readyStopLatched", stopped instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.state() == SessionState.PASSAGE_READY
                            && h.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && h.home().closeIntent() instanceof None
                            && h.home().crossed().equals(readyRow.home().crossed()) && !journal.rollForwardPending(sid), () -> "row=" + stopped);
                    // stop 已持久後 A 修復：復啟放行，stop 保留。
                    expect("repairAcked", lifecycle.setOriginEnabled(root, true) && registryEnabled(), () -> "A 修復被拒");
                    expect("stopKeptAfterRepair", stopped.equals(journal.allFlushedSessions().get(sid)) && stopped.equals(journal.allSessions().get(sid)),
                            () -> "row=" + journal.allSessions().get(sid));
                    checkpoint(chunks, true);
                    var baseline = baseline((HomeSessionRecord) stopped);
                    expect("baselineRepaired", Boolean.TRUE.equals(baseline.get("registryEnabled")), () -> "baseline=" + baseline);
                    expect("aDoorUnchanged", aBefore.equals(doorSummary(overworld)), () -> aBefore + "→" + doorSummary(overworld));
                    halt();
                }
                case RESTART_READY_LOW -> {
                    var readyRow = (HomeSessionRecord) journal.allFlushedSessions().get(sid);
                    var coordinator = new ChamberPowerCoordinator();
                    overworld.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
                    coordinator.refresh(overworld, FRAME.controllerPos());
                    expect("aPowered", overworld.isReceivingRedstonePower(FRAME.controllerPos()) && powerState().equals("POWERED"), () -> "powerState=" + powerState());
                    expect("poweredNoLatch", readyRow.equals(journal.allSessions().get(sid)) && readyRow.equals(journal.allFlushedSessions().get(sid)) && !queued(),
                            () -> "row=" + journal.allSessions().get(sid));
                    // 普通斷電（LOW）：不是根事件（M7 spec §9.1 :244）。
                    overworld.setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
                    coordinator.refresh(overworld, FRAME.controllerPos());
                    expect("aLow", !overworld.isReceivingRedstonePower(FRAME.controllerPos()) && powerState().equals("RETURNING"), () -> "powerState=" + powerState());
                    expect("lowNoLatch", readyRow.equals(journal.allSessions().get(sid)) && readyRow.equals(journal.allFlushedSessions().get(sid)) && !queued()
                            && registryEnabled(), () -> "row=" + journal.allSessions().get(sid));
                    checkpoint(chunks, true);
                    var baseline = baseline(readyRow);
                    expect("baselineLow", Boolean.TRUE.equals(baseline.get("registryEnabled")) && "RETURNING".equals(baseline.get("registryPowerState")),
                            () -> "baseline=" + baseline);
                    expect("aDoorUnchanged", aBefore.equals(doorSummary(overworld)), () -> aBefore + "→" + doorSummary(overworld));
                    halt();
                }
                case RECEIPT_EXISTING, RECEIPT_PLAYER_CLOSED, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> {
                    expect("disableAcked", lifecycle.setEnabled(chamber, false), () -> "停用沒有回報成功");
                    var stopped = journal.allFlushedSessions().get(sid);
                    expect("stopLatched", stopped instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid))
                            && h.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && !journal.rollForwardPending(sid), () -> "row=" + stopped);
                    checkpoint(chunks, true);
                    baseline((HomeSessionRecord) stopped);
                    // T4 P1：停服之後要核對這個 SID 的管理員 ERROR 個數，從 arm 之前開始擷取（JVM 以 halt 結束，不另外關閉）。
                    if (chain.stopsNormally()) logs = new LogCapture("quantumchamber");
                    if (chain.existingReceipt()) {
                        // 只讓「熱列移除」那次 journal flush 失敗（records 已沒有本 SID、flushed 仍有）：production 回 RESTORED，熱列與收據都保留。
                        var fault = new M5GameTestSupport.FlushFault(server, "M7 B2a probe 受控：回程熱列移除的 journal 寫出失敗", Integer.MAX_VALUE,
                                state -> !state.allSessions().containsKey(sid) && state.allFlushedSessions().containsKey(sid));
                        M5TestHooks.FLUSH_PROBE.install(fault, null);
                        proof.put("faultInstalled", true);
                    }
                    armed = true;
                    step = chain.existingReceipt() ? Step.AWAIT_CUT : Step.AWAIT_CRASH_CLOSE;
                    stepTicks = 0;
                    LOGGER.info("M7_HOME_ARMED row={} cut={} sid={}", chain.row, chain.crashPoint, sid);
                }
            }
        }

        /** guard 內的 crash 步驟：失敗時記下並停服（不 halt）。 */
        private void guardedCrash(M7HomeClosurePhases.Checked action) {
            try { action.run(); }
            catch (Exception | Error failure) {
                failures.add(failure.toString());
                LOGGER.error("M7 home chain 失敗：row={} phase={}", chain.row, config.phase(), failure);
                stop();
            }
        }

        private Map<String, Object> baseline(HomeSessionRecord row) throws IOException {
            pose = M7HomeClosurePhases.pose(participant.player());
            var baseline = new LinkedHashMap<String, Object>();
            baseline.put("aDoor", doorSummary(overworld));
            baseline.put("bDoor", doorSummary(b));
            baseline.put("row", String.valueOf(row));
            baseline.put("stoppedAt", row.home().stoppedAtGameTime());
            baseline.put("crossed", row.home().crossed().stream().map(UUID::toString).toList());
            baseline.put("passageOpenedAt", row.home().passageOpenedAtGameTime());
            baseline.put("pose", pose);
            baseline.put("journalSha256", sha256(journalFile()));
            baseline.put("registrySha256", sha256(registryFile()));
            baseline.put("receipts", listing(receiptsDir()));
            baseline.put("registryEnabled", registryEnabled());
            baseline.put("registryPowerState", powerState());
            baseline.put("busy", gate().globalChamberBusy(server, chamber));
            baseline.put("gameTime", overworld.getTime());
            expect("baselineShape", chain.initialDoorSummary().equals(baseline.get("bDoor")) && listing(receiptsDir()).isEmpty()
                    && Boolean.TRUE.equals(baseline.get("busy")), () -> "baseline=" + baseline);
            proof.put("baseline", baseline);
            return baseline;
        }

        private void awaitCut() {
            expect("participantStill", M7HomeClosurePhases.pose(participant.player()).equals(pose), () -> "pose=" + M7HomeClosurePhases.pose(participant.player()));
            require(++stepTicks <= CUT_TICKS, "cutReached：" + CUT_TICKS + " 個 tick 內沒有走到 " + chain.crashPoint + "；events=" + eventNames());
        }

        /** receipt-player-closed：等正式 owner 完整收尾，之後參與者以正式方塊互動關 B 門、checkpoint、crash。 */
        private void awaitCrashClose() throws Exception {
            expect("participantStill", M7HomeClosurePhases.pose(participant.player()).equals(pose), () -> "pose=" + M7HomeClosurePhases.pose(participant.player()));
            var journal = SessionRecoveryState.get(server);
            if (journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid)) {
                require(++stepTicks <= CLOSE_TICKS, "crashClosed：" + CLOSE_TICKS + " 個 tick 內沒有收尾；events=" + eventNames());
                return;
            }
            expect("crashEventsFull", eventNames().equals(Chain.RECEIPT_PLAYER_CLOSED.crashEvents()), () -> "events=" + eventNames());
            var receipt = new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)).lookup(sid).orElseThrow(() -> new AssertionError("crashReceipt：沒有收據"));
            expect("crashReceiptRoot", receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED && k.doorGoal() == DoorGoal.OPEN,
                    () -> "close=" + receipt.home().closeIntent());
            expect("crashClosedOutOpen", M7HomeClosurePhases.OPEN_NONE.equals(doorSummary(b)) && !gate().globalChamberBusy(server, chamber), () -> "door=" + doorSummary(b));
            playerClosesDoor("crashPlayerClosedDoor");
            checkpoint(chunkSet(), false);
            halt();
        }

        /**
         * receipt-mismatch-stop（T4 P1，t4b2a-review P1）：熱列移除失敗（RESTORED，journal 因此 dirty）的同一個 tick，把收據換成同 SID、內容不同的合法收據（外部竄改），
         * 在 A、B 兩側放標記方塊、把 gamerule {@code announceAdvancements} 設為 false（存在 level.dat），然後正常停服。journal 仍 dirty，停服存檔一定會核對收據；
         * 標記與 gamerule 只能由停服存檔寫出，reopen 核對它們都已落盤。
         */
        private void stopMismatch() throws Exception {
            step = Step.DONE;
            var journal = SessionRecoveryState.get(server);
            var row = journal.allFlushedSessions().get(sid);
            expect("removalRestored", row instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.home().closeIntent() instanceof Close
                    && journal.isDirty() && !journal.rollForwardPending(sid) && !journal.quarantinedSessions().contains(sid) && !journal.pendingSessions().containsKey(sid),
                    () -> "row=" + row + " dirty=" + journal.isDirty());
            var frozen = (HomeSessionRecord) row;
            var receipt = receiptsDir().resolve(sid + ".dat");
            expect("receiptPublishedExact", new HomeReceiptStore(saveRoot).lookup(sid).filter(frozen::equals).isPresent(), () -> "收據不是本列的 exact 收據");
            originalReceipt = Files.readAllBytes(receipt);
            byte[] variant;
            scratchPublish = true;
            try { variant = M7HomeClosureGameTests.variantReceipt(frozen); }
            finally { scratchPublish = false; }
            stopFacts.put("scratchEventsIgnored", scratchEventsIgnored);
            Files.write(receipt, variant);
            stopFacts.put("originalReceiptSha256", bytesSha256(originalReceipt));
            stopFacts.put("tamperedReceiptSha256", sha256(receipt));
            expect("receiptTampered", !stopFacts.get("originalReceiptSha256").equals(stopFacts.get("tamperedReceiptSha256")), () -> "竄改後的收據與原本相同");
            var marker = marker();
            for (var world : List.of(overworld, b)) {
                require(!world.getBlockState(marker).isOf(Blocks.SPONGE), "M7_HOME_MARKER_OCCUPIED：" + world.getRegistryKey().getValue());
                world.setBlockState(marker, Blocks.SPONGE.getDefaultState(), 3);
            }
            server.getGameRules().get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, server);
            expect("markersPlaced", overworld.getBlockState(marker).isOf(Blocks.SPONGE) && b.getBlockState(marker).isOf(Blocks.SPONGE)
                    && !server.getGameRules().getBoolean(GameRules.ANNOUNCE_ADVANCEMENTS) && journal.isDirty(), () -> "標記或 gamerule 沒有放好");
            stopFacts.put("marker", List.of(marker.getX(), marker.getY(), marker.getZ()));
            stopFacts.put("stopServerTick", server.getTicks());
            preStop = snapshot();
            stopJournal = journal;
            proof.put("stop", stopFacts);
            LOGGER.info("M7_HOME_MISMATCH_STOP row={} sid={} tampered={}", chain.row, sid, stopFacts.get("tamperedReceiptSha256"));
            stop();
        }

        /**
         * receipt-mismatch-stop：停服已完成（Fabric 的 SERVER_STOPPED 在 {@code MinecraftServer.shutdown} 的最後；停服存檔丟出例外時不會走到這裡，JVM 不以 77 結束）。
         * 世界已關閉，只讀檔案：journal 檔的這一列與停服前相同、journal 已寫出（不再 dirty）、收據仍是竄改後的那份、管理員 ERROR 恰一則而且帶 LOG_MARKER。
         * 之後模擬管理員處理（還原原本的收據；否則下一個 JVM 的開服 strict 載入會拒絕，R5），再 halt(77)。
         */
        private void stoppedWithMismatch() {
            proof.put("stoppedSeen", true);
            Map<String, Object> atHalt = preStop == null ? new LinkedHashMap<>() : new LinkedHashMap<>(preStop);
            try {
                expect("stopCompleted", preStop != null && stoppingSeen, () -> "停服前沒有走到不符的停服步驟：stoppingSeen=" + stoppingSeen);
                expect("journalCleanAfterStop", !stopJournal.isDirty(), () -> "停服存檔沒有寫出 journal");
                var disk = SessionRecoveryState.fromNbt(SessionJournalStore.read(journalFile()).getCompound("data")).allSessions();
                var before = ((Map<?, ?>) preStop.get("flushed")).get("text");
                expect("journalRowAfterStop", disk.size() == 1 && disk.containsKey(sid) && String.valueOf(disk.get(sid)).equals(before), () -> "disk=" + disk);
                var receipt = receiptsDir().resolve(sid + ".dat");
                stopFacts.put("receiptAfterStopSha256", sha256(receipt));
                expect("receiptKeptAfterStop", stopFacts.get("tamperedReceiptSha256").equals(stopFacts.get("receiptAfterStopSha256")), () -> "停服時收據被覆寫或刪除");
                var errors = logs.matching(org.apache.logging.log4j.Level.ERROR, "session=" + sid).stream()
                        .map(entry -> (entry.marker() == null ? "NO_MARKER" : entry.marker().getName()) + "|" + entry.message()).toList();
                stopFacts.put("adminErrors", errors.size());
                stopFacts.put("adminErrorEntries", errors);
                expect("adminErrorOnce", errors.size() == 1 && errors.getFirst().startsWith(CollapseService.LOG_MARKER.getName() + "|"), () -> "errors=" + errors);
                // 管理員處理：還原原本發佈的收據。
                Files.write(receipt, originalReceipt);
                expect("receiptRestored", sha256(receipt).equals(stopFacts.get("originalReceiptSha256")), () -> "還原後的收據不是原本那份");
                atHalt.put("journalSha256", sha256(journalFile()));
                atHalt.put("registrySha256", sha256(registryFile()));
                atHalt.put("receipts", listing(receiptsDir()));
                atHalt.put("receipt", receiptFacts());
            } catch (Exception | Error failure) {
                failures.add("stoppedWithMismatch：" + failure);
                LOGGER.error("M7 home chain：停服之後的核對失敗", failure);
            }
            proof.put("stop", stopFacts);
            var frozenAtHalt = atHalt;
            halt(() -> frozenAtHalt);
        }

        /**
         * receipt-write-fail-stop（T4 P2，使用者 2026-11-17 裁示）：熱列移除失敗（RESTORED，journal 因此 dirty）的同一個 tick，武裝 journal 正式寫出的 atomic move 故障，
         * 先以原版 autosave 的同一條路徑 {@code saveAll(true,false,false)} 存一次（寫出失敗、結果不明而保留 pending；不得丟出），再放標記方塊、改 gamerule，然後正常停服。
         * 停服時的存檔是「pending 時再次原生存檔」：先核對磁碟仍是上一份權威、重試寫出、再次失敗。標記與 gamerule 只能由停服存檔寫出，reopen 核對它們都已落盤。
         */
        private void stopWriteFail() throws Exception {
            step = Step.DONE;
            var journal = SessionRecoveryState.get(server);
            var row = journal.allFlushedSessions().get(sid);
            expect("removalRestored", row instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.home().closeIntent() instanceof Close
                    && journal.isDirty() && !journal.rollForwardPending(sid) && !journal.quarantinedSessions().contains(sid) && !journal.pendingSessions().containsKey(sid),
                    () -> "row=" + row + " dirty=" + journal.isDirty());
            var frozen = (HomeSessionRecord) row;
            var receipt = receiptsDir().resolve(sid + ".dat");
            expect("receiptPublishedExact", new HomeReceiptStore(saveRoot).lookup(sid).filter(frozen::equals).isPresent(), () -> "收據不是本列的 exact 收據");
            stopFacts.put("originalReceiptSha256", sha256(receipt));
            stopFacts.put("journalBeforeSha256", sha256(journalFile()));
            stopFault = M7NativeSaveFault.arm(journalFile());
            stopFacts.put("faultTarget", stopFault.blocked().toString());
            RuntimeException thrown = null;
            try { server.saveAll(true, false, false); } catch (RuntimeException failure) { thrown = failure; }
            stopFacts.put("autosaveThrown", thrown == null ? "NONE" : thrown.toString());
            if (thrown != null) LOGGER.error("M7_T4P2_NATIVE_AUTOSAVE_THREW row={}", chain.row, thrown);
            var autosaveThrown = thrown;
            expect("autosaveNoThrow", autosaveThrown == null, () -> "journal 寫出失敗時原生 autosave 的例外傳進原版存檔鏈：" + autosaveThrown);
            expect("autosaveKeptPending", journal.isDirty() && journal.pendingSessions().containsKey(sid) && stopFault.hits() == 1
                    && stopFacts.get("journalBeforeSha256").equals(sha256(journalFile())),
                    () -> "dirty=" + journal.isDirty() + " pending=" + journal.pendingSessions().keySet() + " hits=" + stopFault.hits());
            var marker = marker();
            for (var world : List.of(overworld, b)) {
                require(!world.getBlockState(marker).isOf(Blocks.SPONGE), "M7_HOME_MARKER_OCCUPIED：" + world.getRegistryKey().getValue());
                world.setBlockState(marker, Blocks.SPONGE.getDefaultState(), 3);
            }
            server.getGameRules().get(GameRules.ANNOUNCE_ADVANCEMENTS).set(false, server);
            expect("markersPlaced", overworld.getBlockState(marker).isOf(Blocks.SPONGE) && b.getBlockState(marker).isOf(Blocks.SPONGE)
                    && !server.getGameRules().getBoolean(GameRules.ANNOUNCE_ADVANCEMENTS) && journal.isDirty(), () -> "標記或 gamerule 沒有放好");
            stopFacts.put("marker", List.of(marker.getX(), marker.getY(), marker.getZ()));
            stopFacts.put("stopServerTick", server.getTicks());
            preStop = snapshot();
            stopJournal = journal;
            proof.put("stop", stopFacts);
            LOGGER.info("M7_HOME_WRITE_FAIL_STOP row={} sid={} hits={}", chain.row, sid, stopFault.hits());
            stop();
        }

        /**
         * receipt-write-fail-stop：停服已完成（SERVER_STOPPED；停服存檔把 journal 的例外丟進原版鏈時不會走到這裡）。世界已關閉，只讀檔案：journal 檔與停服前相同
         * （停服時的存檔重試寫出又失敗，未確認的寫入沒有覆寫任何東西）、記憶體仍 dirty 並保留 pending、收據不變、原生存檔的 ERROR（TICK marker）恰一則、
         * 這個 SID 沒有收據 ERROR。之後解除故障，halt(77)。
         */
        private void stoppedWithWriteFailure() {
            proof.put("stoppedSeen", true);
            Map<String, Object> atHalt = preStop == null ? new LinkedHashMap<>() : new LinkedHashMap<>(preStop);
            try {
                expect("stopCompleted", preStop != null && stoppingSeen, () -> "停服前沒有走到寫出故障的停服步驟：stoppingSeen=" + stoppingSeen);
                stopFacts.put("faultHits", stopFault.hits());
                expect("stopRetriedWrite", stopFault.hits() == 2, () -> "停服時的存檔應先核對磁碟、再重試寫出一次：hits=" + stopFault.hits());
                expect("journalHeldAfterStop", stopJournal.isDirty() && stopJournal.pendingSessions().containsKey(sid), () -> "停服存檔不得清掉 dirty 或 pending");
                stopFacts.put("journalAfterStopSha256", sha256(journalFile()));
                expect("journalFileUnchangedAfterStop", stopFacts.get("journalBeforeSha256").equals(stopFacts.get("journalAfterStopSha256")), () -> "停服時 journal 檔被改寫");
                var disk = SessionRecoveryState.fromNbt(SessionJournalStore.read(journalFile()).getCompound("data")).allSessions();
                var before = ((Map<?, ?>) preStop.get("flushed")).get("text");
                expect("journalRowAfterStop", disk.size() == 1 && disk.containsKey(sid) && String.valueOf(disk.get(sid)).equals(before), () -> "disk=" + disk);
                stopFacts.put("receiptAfterStopSha256", sha256(receiptsDir().resolve(sid + ".dat")));
                expect("receiptKeptAfterStop", stopFacts.get("originalReceiptSha256").equals(stopFacts.get("receiptAfterStopSha256")), () -> "停服時收據被改寫");
                var native_ = logs.matching(org.apache.logging.log4j.Level.ERROR, NATIVE_SAVE_ERROR).stream()
                        .map(entry -> (entry.marker() == null ? "NO_MARKER" : entry.marker().getName()) + "|" + entry.message()).toList();
                stopFacts.put("nativeErrors", native_.size());
                stopFacts.put("nativeErrorEntries", native_);
                expect("nativeErrorOnce", native_.size() == 1 && native_.getFirst().startsWith(CollapseService.TICK_LOG_MARKER.getName() + "|")
                        && native_.getFirst().contains("寫入正式檔失敗"), () -> "errors=" + native_);
                var session = logs.matching(org.apache.logging.log4j.Level.ERROR, "session=" + sid);
                stopFacts.put("sessionErrors", session.size());
                expect("noSessionError", session.isEmpty(), () -> "這個 SID 不得有收據 ERROR：" + session);
                stopFault.close();
                atHalt.put("journalSha256", sha256(journalFile()));
                atHalt.put("registrySha256", sha256(registryFile()));
                atHalt.put("receipts", listing(receiptsDir()));
                atHalt.put("receipt", receiptFacts());
            } catch (Exception | Error failure) {
                failures.add("stoppedWithWriteFailure：" + failure);
                LOGGER.error("M7 home chain：停服之後的核對失敗", failure);
            } finally {
                if (stopFault != null) stopFault.close();
            }
            proof.put("stop", stopFacts);
            var frozenAtHalt = atHalt;
            halt(() -> frozenAtHalt);
        }

        /** 原生存檔的 journal 寫出失敗 ERROR 的固定前綴（T4 P2，{@code SessionRecoveryState}）。 */
        private static final String NATIVE_SAVE_ERROR = "原生存檔（autosave、/save-all 或停服）的 journal 寫出失敗";

        private static String bytesSha256(byte[] bytes) {
            try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
            catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
        }

        /** crash：只有乾淨的 halt（fixture 點第一次 hit、沒有失敗、停服尚未開始、事件序列恰為預定的前綴）以 77 結束；否則 HALT_REFUSED、78。 */
        private void halt() { halt(this::snapshot); }

        /**
         * 同 {@link #halt()}，atHalt 由呼叫端給。receipt-mismatch-stop 的 halt 點在停服完成之後：停服必須已開始（不是 crash 之前的停服），atHalt 是停服前的記憶體快照
         * 加上停服、還原收據之後的檔案事實。
         */
        private void halt(Supplier<Map<String, Object>> atHaltSource) {
            hits++;
            var refused = new ArrayList<String>();
            if (hits != 1) refused.add("hit=" + hits);
            if (!failures.isEmpty()) refused.add("failures=" + failures);
            if (chain.stopsNormally()) { if (!stoppingSeen) refused.add("stoppingNotSeen：停服沒有開始"); }
            else if (stoppingSeen) refused.add("stoppingSeen：停服已開始，不是 crash");
            if (!eventNames().equals(chain.crashEvents())) refused.add("events=" + eventNames() + " expected=" + chain.crashEvents());
            int exit = refused.isEmpty() ? M7HomeClosurePhases.HALT_EXIT : M7HomeClosurePhases.HALT_FAILED_EXIT;
            try {
                var halt = new LinkedHashMap<String, Object>(proof);
                halt.put("status", refused.isEmpty() ? "HALT" : "HALT_REFUSED");
                halt.put("refused", refused);
                halt.put("hit", hits);
                halt.put("crashPointFailure", "none");
                halt.put("ending", "HALT");
                halt.put("events", events);
                halt.put("checkpoints", checkpoints);
                halt.put("observed", observed);
                halt.put("atHalt", atHaltSource.get());
                halt.put("haltServerTick", server.getTicks());
                halt.put("haltTimeUtc", Instant.now().toString());
                writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            } catch (Exception | Error failure) {
                LOGGER.error("M7 home chain：halt receipt 寫不出來", failure);
                exit = M7HomeClosurePhases.HALT_FAILED_EXIT;
            }
            if (exit != M7HomeClosurePhases.HALT_EXIT) LOGGER.error("M7 home chain：不是乾淨的 halt（{}），以 {} 結束", refused, exit);
            Runtime.getRuntime().halt(exit);
        }

        // ---- reopen phase ----

        private void setupReopen() throws Exception {
            require(crash != null, "M7_HOME_CRASH_RECEIPT_MISSING");
            expect("crashReceiptMatches", "HALT".equals(text(crash, "status")) && "crash".equals(text(crash, "phase")) && chain.row.equals(text(crash, "row"))
                    && config.nonce().equals(text(crash, "nonce")) && !config.startupNonce().equals(text(crash, "startupNonce"))
                    && crash.getAsJsonArray("refused").isEmpty() && crash.get("hit").getAsInt() == 1, () -> "crash=" + crash);
            var setup = crash.getAsJsonObject("setup");
            var baseline = crash.getAsJsonObject("baseline");
            var atHalt = crash.getAsJsonObject("atHalt");
            proof.put("setup", JSON.fromJson(setup, Map.class));
            fakeId = "NONE".equals(text(setup, "crossedOffline")) ? null : UUID.fromString(text(setup, "crossedOffline"));
            expect("homeChunksStable", JSON.toJsonTree(homeChunks).equals(setup.get("homeChunks")), () -> homeChunks + " vs " + setup.get("homeChunks"));
            expect("journalBytesFromCrash", Objects.equals(startFiles.get("journalSha256"), text(atHalt, "journalSha256")),
                    () -> startFiles.get("journalSha256") + " vs " + text(atHalt, "journalSha256"));
            expect("registryBytesFromCrash", Objects.equals(startFiles.get("registrySha256"), text(atHalt, "registrySha256")),
                    () -> startFiles.get("registrySha256") + " vs " + text(atHalt, "registrySha256"));
            expect("receiptsFromCrash", JSON.toJsonTree(startFiles.get("receipts")).equals(atHalt.get("receipts")),
                    () -> startFiles.get("receipts") + " vs " + atHalt.get("receipts"));
            b = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(text(setup, "entryWorld"))));
            expect("bMaterializedAtStart", b != null, () -> "B 世界不存在：" + text(setup, "entryWorld"));
            root = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), FRAME.outwardFacing(),
                    ChamberInstanceKind.ORIGIN);
            var journal = SessionRecoveryState.get(server);
            var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
            var current = journal.allSessions().get(sid);
            var flushed = journal.allFlushedSessions().get(sid);
            long startTime = ((Number) early.get("gameTime")).longValue();
            switch (chain) {
                case RESTART_ENABLE -> {
                    // 開服掃描之前：磁碟上的 journal 列沒有 stop、registry 是 disabled；首個 maintenance call（direct enable）必須 pending。
                    expect("earlyRowFromCrash", text(baseline, "row").equals(early.get("row")) && Boolean.FALSE.equals(early.get("stopped")),
                            () -> "early=" + early);
                    expect("earlyRegistryDisabled", Boolean.FALSE.equals(early.get("registryEnabled")), () -> "early=" + early);
                    expect("enableBeforeScanPending", Boolean.FALSE.equals(early.get("enableBeforeScan")) && Boolean.FALSE.equals(early.get("registryEnabledAfterEnable"))
                            && text(baseline, "row").equals(early.get("rowAfterEnable")), () -> "early=" + early);
                    // 開服掃描之後：stop 已 checked latch，時間是開服當下；registry 仍 disabled。
                    expect("scanLatchedStop", current instanceof HomeSessionRecord h && h.equals(flushed) && !journal.rollForwardPending(sid)
                            && h.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && h.home().stoppedAtGameTime() == startTime
                            && h.home().closeIntent() instanceof None && h.state() == SessionState.MEASURED, () -> "row=" + current + " startTime=" + startTime);
                    expect("registryStillDisabled", !registryEnabled(), () -> "registry 已啟用");
                    var stopped = (HomeSessionRecord) current;
                    closingRowText = String.valueOf(stopped);
                    expectedStoppedAt = startTime;
                    // 續行嘗試：續行 owner 會用的同一個正式提交邊（MEASURED→COLLAPSING）必須被拒，本趟不復活。
                    var h = stopped.home();
                    var resume = new HomeSessionRecord(sid, chamber, stopped.rootAnchor(), stopped.entryEndpoint(), stopped.participants(), List.of(),
                            SessionState.COLLAPSING, new Home(h.selection(), startTime, -1, -1, List.of(), List.of(), h.stopReason(), h.stoppedAtGameTime(),
                            new None(), Optional.empty()));
                    var attempt = journal.commitHomeRestore(server, stopped, resume);
                    expect("resumeRejected", attempt.status() == SessionRecoveryState.CommitResult.Status.REJECTED && stopped.equals(journal.allSessions().get(sid))
                            && stopped.equals(journal.allFlushedSessions().get(sid)), () -> "attempt=" + attempt.status());
                    // stop 已持久後 A 修復：第二次 enable 放行，stop 保留。
                    expect("repairAfterScan", lifecycle.setOriginEnabled(root, true) && registryEnabled() && stopped.equals(journal.allSessions().get(sid)),
                            () -> "registry=" + registryEnabled() + " row=" + journal.allSessions().get(sid));
                }
                case RESTART_READY_STOP -> {
                    expect("readyStopFromCrash", current instanceof HomeSessionRecord h && h.equals(flushed) && text(baseline, "row").equals(String.valueOf(h))
                            && h.state() == SessionState.PASSAGE_READY && h.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)), () -> "row=" + current);
                    expect("registryRepaired", registryEnabled() && Boolean.TRUE.equals(early.get("registryEnabled")), () -> "registry 沒有啟用");
                    expect("inStartupSet", startupContains(), () -> "開服收尾集合沒有本列");
                    closingRowText = String.valueOf(current);
                    expectedStoppedAt = baseline.get("stoppedAt").getAsLong();
                }
                case RESTART_READY_LOW -> {
                    expect("readyLowFromCrash", current instanceof HomeSessionRecord h && h.equals(flushed) && text(baseline, "row").equals(String.valueOf(h))
                            && h.state() == SessionState.PASSAGE_READY && h.home().stopReason().isEmpty(), () -> "row=" + current);
                    expect("scanNoLatchOnLow", Boolean.FALSE.equals(early.get("stopped")) && registryEnabled() && "RETURNING".equals(powerState()),
                            () -> "early=" + early + " power=" + powerState());
                    expect("aNotLoadedAtStart", Boolean.FALSE.equals(early.get("aLoaded")) && !aLoaded(overworld), () -> "early=" + early);
                    // T4 B2b-2：登入之前，以 level.dat 載入的出生點與 spawnRadius 核對登入的出生搜尋在幾何上碰不到 A。
                    expect("spawnSearchAvoidsA", spawnSearchAvoidsA(), this::spawnSearchDetail);
                    proof.put("spawnSearch", spawnSearchFacts());
                    expect("inStartupSet", startupContains(), () -> "開服收尾集合沒有本列");
                    closingRowText = String.valueOf(current);
                }
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> {
                    var atFlushed = atHalt.getAsJsonObject("flushed");
                    expect("closeFromCrash", current instanceof HomeSessionRecord h && h.equals(flushed) && text(atFlushed, "text").equals(String.valueOf(h))
                            && h.home().closeIntent() instanceof Close, () -> "row=" + current);
                    expect("busyAtStart", gate().globalChamberBusy(server, chamber), () -> "沒有占用");
                    // T4 P1：停服存檔寫出了 level.dat（停服前才改的 gamerule）。
                    if (chain.stopsNormally()) expect("stopSavedLevel", !server.getGameRules().getBoolean(GameRules.ANNOUNCE_ADVANCEMENTS),
                            () -> "停服前改的 gamerule 沒有落盤（level.dat 停在上一次存檔）");
                    closingRowText = text(baseline, "row");
                }
                case RECEIPT_PLAYER_CLOSED -> {
                    expect("journalEmptyAtStart", current == null && flushed == null && journal.allSessions().isEmpty(), () -> "journal=" + journal.allSessions().keySet());
                    expect("notBusyAtStart", !gate().globalChamberBusy(server, chamber), () -> "仍占用");
                    closingRowText = text(baseline, "row");
                }
            }
            startupInstance = startupInstance();
            proof.put("startupInstance", String.valueOf(startupInstance));
            // B 門：開服時由磁碟載入（正式 owner 還沒有推進）。A：LOW 列不載入（核對收尾不等 A），其他列載入核門格。
            M7HomeClosureGameTests.holdChunks(b, FRAME);
            var bAtStart = doorSummary(b);
            expect("bDoorDiskState", chain.diskDoorAtReopen().equals(bAtStart), () -> "B 門=" + bAtStart + " 預期=" + chain.diskDoorAtReopen());
            if (chain != Chain.RESTART_READY_LOW) {
                M7HomeClosureGameTests.holdChunks(overworld, FRAME);
                expect("aDoorAtStart", text(baseline, "aDoor").equals(doorSummary(overworld)), () -> "A 門=" + doorSummary(overworld));
            }
            // T4 P1：停服前才放的標記方塊在兩個世界都已落盤。
            if (chain.stopsNormally()) expect("stopSavedMarkers", overworld.getBlockState(marker()).isOf(Blocks.SPONGE) && b.getBlockState(marker()).isOf(Blocks.SPONGE),
                    () -> "A=" + overworld.getBlockState(marker()) + " B=" + b.getBlockState(marker()));
            // 零移動：參與者以 crash 前存下的 playerdata 登入。
            participant = connect();
            pose = M7HomeClosurePhases.pose(participant.player());
            expect("participantLoginPose", JSON.toJsonTree(pose).equals(baseline.get("pose")), () -> "login=" + pose + " baseline=" + baseline.get("pose"));
            // T4 B2b-2：登入本身（vanilla 的出生搜尋）不得載入 A；之後的收尾事件另由 aNeverLoadedDuringClose 核對。
            if (chain == Chain.RESTART_READY_LOW) expect("aNotLoadedAfterLogin", !aLoaded(overworld), this::spawnSearchDetail);
            proof.put("start", snapshot());
            step = Step.AWAIT_CLOSE;
            stepTicks = 0;
        }

        private void awaitClose() throws Exception {
            expect("participantStill", M7HomeClosurePhases.pose(participant.player()).equals(pose), () -> "pose=" + M7HomeClosurePhases.pose(participant.player()));
            var journal = SessionRecoveryState.get(server);
            boolean gone = !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid);
            if (chain == Chain.RECEIPT_PLAYER_CLOSED) {
                expect("closedDoorKeptAfterRestart", gone && events.isEmpty() && "CLOSED/NONE".equals(doorSummary(b)) && !gate().globalChamberBusy(server, chamber),
                        () -> "events=" + eventNames() + " door=" + doorSummary(b));
                if (++stepTicks < QUIET_TICKS) return;
            } else if (!gone) {
                require(++stepTicks <= CLOSE_TICKS, "closedAfterReopen：" + CLOSE_TICKS + " 個 tick 內沒有收尾；events=" + eventNames() + " row=" + journal.allSessions().get(sid));
                return;
            }
            verifyEnd();
            if (chain != Chain.RECEIPT_PLAYER_CLOSED) playerClosesDoor("playerClosedDoor");
            eventsBeforeQuiet = events.size();
            checkpointsBeforeQuiet = checkpoints.size();
            receiptsBeforeQuiet = listing(receiptsDir());
            step = Step.QUIET;
            stepTicks = 0;
        }

        private void verifyEnd() throws Exception {
            var baseline = crash.getAsJsonObject("baseline");
            var atHalt = crash.getAsJsonObject("atHalt");
            var journal = SessionRecoveryState.get(server);
            expect("activeRemoved", !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid) && !journal.pendingSessions().containsKey(sid)
                    && !journal.rollForwardPending(sid) && !journal.quarantinedSessions().contains(sid) && journal.allSessions().isEmpty(),
                    () -> "journal=" + journal.allSessions().keySet());
            expect("reopenEvents", eventNames().equals(chain.reopenEvents()), () -> "events=" + eventNames() + " expected=" + chain.reopenEvents());
            var doorOutcome = events.stream().filter(event -> event.get("event").equals("after-door")).map(event -> String.valueOf(event.get("detail"))).toList();
            expect("reopenDoorOutcome", doorOutcome.equals(chain.reopenDoorOutcome()), () -> "outcome=" + doorOutcome);
            int expectedCheckpoints = chain == Chain.RECEIPT_PLAYER_CLOSED ? 0 : 1;
            expect("reopenCheckpoint", checkpoints.size() == expectedCheckpoints && checkpoints.stream().allMatch(call -> checkpointOk(call, chain.existingReceipt())),
                    () -> "checkpoints=" + checkpoints);
            // 移除前不解鎖：before-remove 當下仍 busy、after-remove 當下才解除。
            expect("noEarlyUnlock", events.stream().filter(event -> event.get("event").equals("before-remove")).allMatch(event -> Boolean.TRUE.equals(event.get("busy")))
                    && events.stream().filter(event -> event.get("event").equals("after-remove")).allMatch(event -> Boolean.FALSE.equals(event.get("busy"))),
                    () -> "events=" + events);
            var store = new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT));
            var receipt = store.lookup(sid).orElseThrow(() -> new AssertionError("receiptPublished：沒有本 SID 的收據"));
            var close = receipt.home().closeIntent();
            var receiptClose = JSON.toJsonTree(M7HomeClosurePhases.Run.closeFacts(close));
            switch (chain) {
                case RESTART_ENABLE, RESTART_READY_STOP -> expect("receiptClose", close instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                        && k.doorGoal() == DoorGoal.OPEN && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, expectedStoppedAt))
                        && k.closedAtGameTime() >= expectedStoppedAt, () -> "close=" + close + " stoppedAt=" + expectedStoppedAt);
                case RESTART_READY_LOW -> expect("receiptClose", close instanceof Close k && k.reason() == CloseReason.RESTART_CLOSED && k.doorGoal() == DoorGoal.OPEN
                        && startupInstance != null && k.evidence().equals(new RestartClosed(startupInstance, k.closedAtGameTime()))
                        && receipt.home().stopReason().isEmpty(), () -> "close=" + close + " instance=" + startupInstance);
                case RECEIPT_EXISTING, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> expect("receiptClose", receiptClose.equals(atHalt.getAsJsonObject("flushed").get("close")),
                        () -> "close=" + receiptClose + " crash=" + atHalt.getAsJsonObject("flushed").get("close"));
                case RECEIPT_PLAYER_CLOSED -> expect("receiptClose", receiptClose.equals(atHalt.getAsJsonObject("receipt").get("close")),
                        () -> "close=" + receiptClose + " crash=" + atHalt.getAsJsonObject("receipt").get("close"));
            }
            expect("receiptSession", closingRowText.equals(String.valueOf(receipt.withClose(new None()))), () -> "receipt=" + receipt + " expected=" + closingRowText);
            if (chain.ready()) {
                var crossed = receipt.home().crossed().stream().map(UUID::toString).toList();
                expect("crossedKept", JSON.toJsonTree(crossed).equals(baseline.get("crossed")) && crossed.size() == 1 && crossed.getFirst().equals(fakeId.toString())
                        && receipt.home().passageOpenedAtGameTime() == baseline.get("passageOpenedAt").getAsLong(), () -> "crossed=" + crossed);
            }
            var end = listing(receiptsDir());
            var crashReceipts = atHalt.getAsJsonArray("receipts");
            boolean receiptsOk = switch (chain) {
                case RESTART_ENABLE, RESTART_READY_STOP, RESTART_READY_LOW -> M7HomeClosurePhases.names(end).equals(List.of(sid + ".dat")) && crashReceipts.isEmpty();
                case RECEIPT_EXISTING, RECEIPT_PLAYER_CLOSED, RECEIPT_MISMATCH_STOP, RECEIPT_WRITE_FAIL_STOP -> JSON.toJsonTree(end).equals(crashReceipts) && M7HomeClosurePhases.names(end).equals(List.of(sid + ".dat"));
            };
            expect("receiptFiles", receiptsOk, () -> "end=" + end + " crash=" + crashReceipts);
            String bEnd = chain == Chain.RECEIPT_PLAYER_CLOSED ? "CLOSED/NONE" : M7HomeClosurePhases.OPEN_NONE;
            expect("bDoorClosedOut", bEnd.equals(doorSummary(b)), () -> "B 門=" + doorSummary(b));
            if (chain == Chain.RESTART_READY_LOW) {
                // 收尾的每一個事件當下 A 都未載入（不等待 A FULL）；收尾之後才載入 A 核門格。
                expect("aNeverLoadedDuringClose", !aLoaded(overworld) && events.stream().allMatch(event -> Boolean.FALSE.equals(event.get("aLoaded"))),
                        () -> "events=" + events);
                M7HomeClosureGameTests.holdChunks(overworld, FRAME);
            }
            expect("aDoorUntouched", text(baseline, "aDoor").equals(doorSummary(overworld)), () -> "A 門=" + doorSummary(overworld));
            boolean repaired = chain == Chain.RESTART_ENABLE || chain.ready();
            expect("registryEnd", registryEnabled() == repaired, () -> "registryEnabled=" + registryEnabled());
            expect("resourcesReleased", !gate().globalChamberBusy(server, chamber) && !rootLeasePresent(), () -> "busy=" + gate().globalChamberBusy(server, chamber));
            proof.put("end", snapshot());
        }

        private void quiet() throws Exception {
            expect("playerClosedDoorKept", "CLOSED/NONE".equals(doorSummary(b)), () -> "B 門=" + doorSummary(b));
            expect("participantStill", M7HomeClosurePhases.pose(participant.player()).equals(pose), () -> "pose=" + M7HomeClosurePhases.pose(participant.player()));
            if (++stepTicks < QUIET_TICKS) return;
            var journal = SessionRecoveryState.get(server);
            expect("noRepairAfterRemoval", events.size() == eventsBeforeQuiet && checkpoints.size() == checkpointsBeforeQuiet
                    && listing(receiptsDir()).equals(receiptsBeforeQuiet) && journal.allSessions().isEmpty() && journal.allFlushedSessions().isEmpty(),
                    () -> "events=" + eventNames());
            proof.put("quiet", Map.of("ticks", QUIET_TICKS, "bDoor", doorSummary(b)));
            passed = true;
            step = Step.DONE;
            stop();
        }

        // ---- 正式 owner 的事件 ----

        @Override public void reached(String name, UUID session, Object detail) {
            if (server == null || !server.isOnThread()) { failures.add("eventOffThread：" + name); return; }
            if (scratchPublish) { scratchEventsIgnored++; return; }
            try {
                var record = new LinkedHashMap<String, Object>();
                record.put("event", name);
                record.put("serverTick", server.getTicks());
                record.put("gameTime", overworld.getTime());
                record.put("detail", M7HomeClosurePhases.describe(detail));
                record.put("busy", gate().globalChamberBusy(server, chamber));
                record.put("aLoaded", aLoaded(overworld));
                events.add(record);
                if (sid == null || !sid.equals(session)) failures.add("foreignSession：" + name + " session=" + session);
                if (crashPhase() && !armed) failures.add("eventBeforeArm：" + name);
                if (name.equals("before-checkpoint")) checkpoints.add(checkpointObservation(detail));
                if (crashPhase() && armed && name.equals(chain.crashPoint)) halt();
                // receipt-mismatch-stop：熱列移除失敗（RESTORED）之後，同一個 tick 的 probe tick 做竄改與停服（halt 點在停服完成之後）。
                if (crashPhase() && armed && chain.stopsNormally() && name.equals("remove-rejected") && step == Step.AWAIT_CUT) {
                    step = chain == Chain.RECEIPT_MISMATCH_STOP ? Step.STOP_MISMATCH : Step.STOP_WRITE_FAIL;
                    stepTicks = 0;
                }
            } catch (RuntimeException | Error failure) {
                failures.add("eventRecord：" + name + "：" + failure);
                LOGGER.error("M7 home chain：事件記錄失敗 {}", name, failure);
            }
        }

        // ---- fixture ----

        private ConnectedGameTestPlayer connect() {
            var connection = new ConnectedGameTestPlayer(overworld, new GameProfile(participantId, participantName));
            connection.confirmTeleport();
            connection.player().setNoGravity(true);
            return connection;
        }

        private List<SessionRecoveryRecord.Participant> people(int returned) {
            var ids = fakeId == null ? List.of(participantId) : List.of(participantId, fakeId);
            var people = new ArrayList<SessionRecoveryRecord.Participant>();
            for (int i = 0; i < ids.size(); i++) people.add(new SessionRecoveryRecord.Participant(ids.get(i), Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), i < returned));
            return List.copyOf(people);
        }

        /** 經正式 journal 入口走到已選定、全員返回、無租約的 MEASURED（HOME DORMANT）；返還一次一位。 */
        private HomeSessionRecord dormant(SessionRecoveryState journal) {
            int count = fakeId == null ? 1 : 2;
            var lease = new SessionRecoveryRecord.SpaceLease(0, new BlockBox(610000, 64, 610000, 610006, 70, 610006));
            var empty = new Home(new Unselected(), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var arming = new HomeSessionRecord(sid, chamber, root, entry, people(0), List.of(lease), SessionState.ARMING, empty);
            expect("provisionalClaimed", gate().claimProvisional(server, chamber, sid), () -> "先占失敗");
            expect("checkedArming", journal.admitHome(server, arming).committed() && gate().finishProvisional(server, chamber, sid), () -> "ARMING 未 checked");
            var active = new HomeSessionRecord(sid, chamber, root, entry, people(0), List.of(lease), SessionState.SUPERPOSITION, empty);
            expect("checkedSuperposition", journal.commitHomeRestore(server, arming, active).committed(), () -> "SUPERPOSITION 未 checked");
            var selected = new Home(new Selected(new DoorKey(sid, 1, DoorKey.DoorWallSide.POSITIVE_LATERAL), participantId, overworld.getTime()), -1, -1, -1,
                    List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var row = new HomeSessionRecord(sid, chamber, root, entry, people(0), List.of(lease), SessionState.MEASURED, selected);
            expect("checkedHomeSelection", journal.commitHomeRestore(server, active, row).committed(), () -> "HOME 選擇未 checked");
            for (int i = 1; i <= count; i++) {
                var next = new HomeSessionRecord(sid, chamber, root, entry, people(i), List.of(lease), SessionState.MEASURED, selected);
                int index = i;
                var previous = row;
                expect("checkedReturned" + i, journal.commitHomeRollForward(server, previous, next).committed(), () -> "第 " + index + " 位返回 B 未 checked");
                row = next;
            }
            var dormant = new HomeSessionRecord(sid, chamber, root, entry, people(count), List.of(), SessionState.MEASURED, selected);
            var settled = row;
            expect("checkedDormant", journal.commitHomeRollForward(server, settled, dormant).committed(), () -> "清除租約未 checked");
            return dormant;
        }

        /** DORMANT → COLLAPSING → PASSAGE_READY → 離線的第二位參與者跨過（Crossed=[p2]）；都經正式 journal 入口。 */
        private HomeSessionRecord readyCrossed(SessionRecoveryState journal) {
            var dormant = dormant(journal);
            var h = dormant.home();
            long time = overworld.getTime();
            var collapse = new HomeSessionRecord(sid, chamber, root, entry, dormant.participants(), List.of(), SessionState.COLLAPSING,
                    new Home(h.selection(), time, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
            expect("checkedCollapsing", journal.commitHomeRestore(server, dormant, collapse).committed(), () -> "COLLAPSING 未 checked");
            var ready = new HomeSessionRecord(sid, chamber, root, entry, dormant.participants(), List.of(), SessionState.PASSAGE_READY,
                    new Home(h.selection(), time, time, time + 1200, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
            expect("checkedReady", journal.commitHomeRestore(server, collapse, ready).committed(), () -> "READY 未 checked");
            var crossed = new HomeSessionRecord(sid, chamber, root, entry, dormant.participants(), List.of(), SessionState.PASSAGE_READY,
                    new Home(h.selection(), time, time, time + 1200, List.of(fakeId), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
            expect("checkedPartialCrossed", journal.commitHomeRollForward(server, ready, crossed).committed(), () -> "部分 Crossed 未 checked");
            return crossed;
        }

        /** 連線中的參與者以正式方塊互動（{@code BlockState.onUse} → {@code QuantumBulkheadBlock.onUse}）在 exact B 門上關門。 */
        private void playerClosesDoor(String name) {
            var pos = ChamberGeometry.localToWorld(FRAME, 3, 3, 0);
            var hit = new BlockHitResult(Vec3d.ofCenter(pos), FRAME.outwardFacing().getOpposite(), pos, false);
            var result = b.getBlockState(pos).onUse(b, participant.player(), hit);
            expect(name, result.isAccepted() && "CLOSED/NONE".equals(doorSummary(b)), () -> "use=" + result + " door=" + doorSummary(b));
        }

        private void checkpoint(Set<ChunkPos> chunks, boolean withA) {
            ChunkSaveCheckpoint.save(server, withA ? Map.of(overworld, chunks, b, chunks) : Map.of(b, chunks));
            server.getPlayerManager().saveAllPlayerData();
        }

        // ---- 觀察 ----

        private Map<String, Object> snapshot() {
            var s = new LinkedHashMap<String, Object>();
            var journal = SessionRecoveryState.get(server);
            s.put("current", rowFacts(journal.allSessions().get(sid)));
            s.put("flushed", rowFacts(journal.allFlushedSessions().get(sid)));
            s.put("pending", journal.pendingSessions().containsKey(sid));
            s.put("rollForwardPending", journal.rollForwardPending(sid));
            s.put("quarantined", journal.quarantinedSessions().contains(sid));
            s.put("journalSessions", journal.allSessions().size());
            s.put("queued", queued());
            try { s.put("journalSha256", sha256(journalFile())); } catch (IOException failure) { s.put("journalSha256", "ERROR:" + failure); }
            try { s.put("registrySha256", sha256(registryFile())); } catch (IOException failure) { s.put("registrySha256", "ERROR:" + failure); }
            try { s.put("receipts", listing(receiptsDir())); } catch (IOException failure) { s.put("receipts", "ERROR:" + failure); }
            s.put("receipt", receiptFacts());
            s.put("bDoor", doorSummary(b));
            s.put("aLoaded", aLoaded(overworld));
            s.put("aDoor", aLoaded(overworld) ? doorSummary(overworld) : "NOT_LOADED");
            s.put("pose", participant == null ? "NONE" : M7HomeClosurePhases.pose(participant.player()));
            s.put("busy", gate().globalChamberBusy(server, chamber));
            s.put("registryEnabled", registryEnabled());
            s.put("registryPowerState", powerState());
            s.put("rootTicketLease", rootLeasePresent());
            s.put("gameTime", overworld.getTime());
            return s;
        }

        private Map<String, Object> checkpointObservation(Object detail) {
            var call = new LinkedHashMap<String, Object>();
            var worlds = new ArrayList<Map<String, Object>>();
            if (detail instanceof Map<?, ?> relevant)
                for (var item : relevant.entrySet())
                    if (item.getKey() instanceof ServerWorld world && item.getValue() instanceof Set<?> chunks)
                        worlds.add(Map.of("world", world.getRegistryKey().getValue().toString(), "chunks", chunks.stream().map(chunk -> {
                            var pos = (ChunkPos) chunk;
                            return pos.x + "," + pos.z;
                        }).sorted().toList()));
            call.put("worlds", worlds);
            var flushed = SessionRecoveryState.get(server).allFlushedSessions().get(sid);
            call.put("activeClose", flushed instanceof HomeSessionRecord h && h.home().closeIntent() instanceof Close);
            call.put("bDoor", doorSummary(b));
            call.put("receiptPresent", Boolean.TRUE.equals(receiptFacts().get("present")));
            call.put("serverTick", server.getTicks());
            return call;
        }

        /** 正式 checkpoint 呼叫：relevant 集合只含 B、等於 homeChunks、涵蓋 B 門 25 格所在的 chunk（幾何獨立計算）；當下熱列仍在且 CLOSE 已凍結、門已是 OPEN/NONE。 */
        private boolean checkpointOk(Map<String, Object> call, boolean receiptPresent) {
            return JSON.toJsonTree(call.get("worlds")).equals(JSON.toJsonTree(List.of(Map.of("world", b.getRegistryKey().getValue().toString(), "chunks", homeChunks))))
                    && homeChunks.containsAll(M7HomeClosurePhases.doorChunks())
                    && Boolean.TRUE.equals(call.get("activeClose")) && M7HomeClosurePhases.OPEN_NONE.equals(call.get("bDoor"))
                    && Boolean.valueOf(receiptPresent).equals(call.get("receiptPresent"));
        }

        private Map<String, Object> rowFacts(JournalSession session) {
            var facts = new LinkedHashMap<String, Object>();
            facts.put("present", session != null);
            if (session instanceof HomeSessionRecord h) {
                facts.put("state", h.state().name());
                facts.put("stopReason", h.home().stopReason().map(Enum::name).orElse("NONE"));
                facts.put("stoppedAt", h.home().stoppedAtGameTime());
                facts.put("close", M7HomeClosurePhases.Run.closeFacts(h.home().closeIntent()));
                facts.put("text", String.valueOf(h));
            } else if (session != null) facts.put("text", String.valueOf(session));
            return facts;
        }

        private Map<String, Object> receiptFacts() {
            var facts = new LinkedHashMap<String, Object>();
            try {
                var stored = new HomeReceiptStore(saveRoot).lookup(sid);
                facts.put("present", stored.isPresent());
                stored.ifPresent(row -> {
                    facts.put("close", M7HomeClosurePhases.Run.closeFacts(row.home().closeIntent()));
                    facts.put("session", String.valueOf(row.withClose(new None())));
                });
            } catch (IOException | RuntimeException failure) { facts.put("error", failure.toString()); }
            return facts;
        }

        private boolean registryEnabled() {
            var record = ChamberRegistryState.get(server).registry().records().get(chamber);
            return record != null && record.enabled();
        }

        private String powerState() {
            var record = ChamberRegistryState.get(server).registry().records().get(chamber);
            return record == null ? "ABSENT" : record.powerState().name();
        }

        /**
         * T4 B2b-2：玩家登入時 vanilla 出生搜尋可能同步載入的 overworld chunk 的上界。{@code ServerPlayerEntity.getWorldSpawnPos} 的半徑
         * i＝max(0, {@code server.getSpawnRadius})，離世界邊界太近時縮小、≤1 時改成 1，所以 i ≤ max(1, spawnRadius)；候選欄都在出生點 ±i 的方形內，
         * 每一欄只由 {@code SpawnLocating.findOverworldSpawn} 載入那一欄所在的 chunk（之後的 {@code isSpaceEmpty} 只檢查同一欄、寬 0.6 的方框），
         * 全部失敗時退回出生點那一欄。回傳涵蓋這個方形的全部 chunk。
         */
        private Set<ChunkPos> spawnSearchChunks() {
            var spawn = overworld.getSpawnPos();
            int radius = Math.max(1, server.getSpawnRadius(overworld));
            var chunks = new LinkedHashSet<ChunkPos>();
            for (int x = Math.floorDiv(spawn.getX() - radius, 16); x <= Math.floorDiv(spawn.getX() + radius, 16); x++)
                for (int z = Math.floorDiv(spawn.getZ() - radius, 16); z <= Math.floorDiv(spawn.getZ() + radius, 16); z++) chunks.add(new ChunkPos(x, z));
            return chunks;
        }

        /** 登入的出生搜尋（上界）與 A 的 chunk 沒有交集：A 不可能因玩家登入而被載入。 */
        private boolean spawnSearchAvoidsA() {
            return spawnSearchChunks().stream().noneMatch(chunkSet()::contains);
        }

        private Map<String, Object> spawnSearchFacts() {
            var spawn = overworld.getSpawnPos();
            var facts = new LinkedHashMap<String, Object>();
            facts.put("spawn", spawn.getX() + "," + spawn.getY() + "," + spawn.getZ());
            facts.put("spawnRadius", server.getSpawnRadius(overworld));
            facts.put("searchChunks", spawnSearchChunks().stream().map(pos -> pos.x + "," + pos.z).toList());
            facts.put("aChunks", homeChunks);
            return facts;
        }

        private String spawnSearchDetail() { return "spawnSearch=" + spawnSearchFacts(); }

        /** A（同 FRAME 的 homeChunks）是否有任一 chunk 已以 FULL 載入；只查詢、不載入。 */
        private boolean aLoaded(ServerWorld world) {
            for (var pos : chunkSet()) if (world.getChunkManager().getWorldChunk(pos.x, pos.z) != null) return true;
            return false;
        }

        /** {@code HomeRootFence} 是否有本 SID 排隊中的根事件（package 可見的 static 查詢，反射呼叫、只讀）。 */
        private boolean queued() {
            try {
                var method = Class.forName("dev.quantumchamber.collapse.HomeRootFence").getDeclaredMethod("queued", MinecraftServer.class, UUID.class);
                method.setAccessible(true);
                return (Boolean) method.invoke(null, server, sid);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_HOME_FENCE_UNREADABLE", failure); }
        }

        /** 本 instance 回程終局 owner 的開服 UUID（{@code HomeClosure} 的 context，反射讀取、只讀）。 */
        private UUID startupInstance() {
            var context = ((Map<?, ?>) M4CandidateTestAccess.get(HomeClosure.class, "CONTEXTS")).get(server);
            return context == null ? null : (UUID) M4CandidateTestAccess.get(context, "instance");
        }

        private boolean startupContains() {
            var context = ((Map<?, ?>) M4CandidateTestAccess.get(HomeClosure.class, "CONTEXTS")).get(server);
            return context != null && ((Set<?>) M4CandidateTestAccess.get(context, "startup")).contains(sid);
        }

        /** 原艙票 owner（{@code HomeRootTickets}）是否仍有本 SID 的 lease（反射讀 private 欄位，只讀）。 */
        private boolean rootLeasePresent() {
            try {
                var type = Class.forName("dev.quantumchamber.collapse.HomeRootTickets");
                var servers = (Map<?, ?>) M7HomeClosurePhases.field(type, null, "servers");
                var owner = servers.get(server);
                return owner != null && ((Map<?, ?>) M7HomeClosurePhases.field(type, owner, "leases")).containsKey(sid);
            } catch (ReflectiveOperationException failure) { throw new IllegalStateException("M7_HOME_ROOT_TICKETS_UNREADABLE", failure); }
        }

        private List<String> eventNames() { return events.stream().map(event -> (String) event.get("event")).toList(); }

        private Set<ChunkPos> chunkSet() {
            var chunks = new LinkedHashSet<ChunkPos>();
            for (var item : homeChunks) {
                var parts = item.split(",");
                chunks.add(new ChunkPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])));
            }
            return Set.copyOf(chunks);
        }

        private Path journalFile() { return saveRoot.resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat"); }

        private Path registryFile() { return saveRoot.resolve("data").resolve(ChamberRegistryState.STATE_ID + ".dat"); }

        private Path receiptsDir() { return saveRoot.resolve("data").resolve("quantumchamber_home_receipts"); }

        private void expect(String name, boolean condition, Supplier<String> detail) {
            if (!condition) throw new AssertionError(name + "：" + detail.get());
            observed.put(name, true);
        }
    }

}
