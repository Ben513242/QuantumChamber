package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberControllerLoadSyncQueue;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberLoadSyncTestAccess;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Allocation;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.DiskBlock;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Projection;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlockEntities;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.dimension.DimensionType;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM 的塌縮 crash／restart probe（spec §12 的 crash 窗口）。預設關閉：沒有 system property
 * {@code quantumchamber.m5collapse.phase} 時不註冊任何 listener、不記 log，default、legacy、M5 GameTest、main-only 與 M4 recovery run 都不受影響；
 * 屬性存在但設定不合法時，在 {@code onInitialize} 丟出（開服之前就失敗）。
 * <p>每個 phase 一個 JVM，由 {@code scripts/verification/m5-collapse-probe.ps1} 經 Gradle 的 {@code runM5Collapse} 啟動。root 是 nonce-owned 的
 * {@code run/m5-collapse-<nonce>}，必須等於 cwd；evidence 固定在 {@code <repo>/.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>}，
 * 已有 receipt 時拒絕（禁止在同一個 evidence 重跑）。
 * <p>phase 以 {@link #PHASES} 表示：名稱 → {@link PhaseFactory}。factory（在 {@code onInitialize} 呼叫）產生一個 {@link Phase}：開服前（{@code onInitialize}）的準備、
 * 開服時（{@code SERVER_STARTED}）的檢查、每個 tick 的不變量、依序執行的 {@link Step}（每個 step 有上限 tick，整個 phase 另以 {@link #PHASE_TICK_LIMIT} 為界），
 * 與停服之後（{@code SERVER_STOPPED}）的檢查。新增 phase 只要在 {@link #phases} 加 entry，並在
 * {@code m5-collapse-probe.ps1} 的 chain 表加上同名的 phase 與預期的結束（{@code scripts/verification/README.md}「M5 collapse probe」）。phase 的結束有兩種
 * （{@link Ending}）：
 * <ul>
 * <li>{@code STOP}：全部 step 完成之後正常停止，{@code SERVER_STOPPED} 時寫 {@code final.json}（status PASS 或 FAIL）。</li>
 * <li>{@code HALT}：在指定的 crash 窗口（{@link CrashWindow}，{@link M5TestHooks#CRASH_POINT}）寫出 {@code halt.json}（CREATE_NEW＋{@code force}）之後以
 *     {@code Runtime.halt(77)} 結束 JVM：不經正常停止、不存檔，等同 crash。窗口沒有到達、或任何斷言失敗時，照常停止並寫 FAIL 的 {@code final.json}。</li>
 * </ul>
 * 斷言失敗的訊息以斷言名稱開頭（例如 {@code catalogOnlyDestination}），記在 receipt 的 {@code failures}，之後正常停止；例外不會從 {@code END_SERVER_TICK}
 * 拋出。各 restart phase 的共同斷言是 spec §12 的四件事：同一個 UniverseId、catalog 最多只多 1 筆、沒有重抽、沒有人被困住。另有一條不是 crash 窗口的 chain：
 * 開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本），setup 正常停止，restart 開服前移走 ledger。
 */
public final class M5CollapseRecoveryProbe implements ModInitializer {
    static final String OWNER = ".superpowers/sdd/2026-10-01-m5-collapse-passage";
    /** 預期 crash 的 exit code：{@code m5-collapse-probe.ps1} 以它分辨 crash 窗口與其他非零結束。 */
    static final int HALT_EXIT = 77;
    /** halt receipt 寫不出來時的 exit code（不是預期的 crash；harness 判 FAIL）。 */
    static final int HALT_RECEIPT_FAILED_EXIT = 78;
    /** 整個 phase 的上限 tick 數。 */
    static final int PHASE_TICK_LIMIT = 3600;
    /** W1 的 restart：參與者 JOIN 之後最多在走廊幾個 tick（之後仍在走廊就是被困住）。 */
    static final int RETURN_TICK_LIMIT = 100;
    /** crash phase 寫出的 MEASURED 收據（單筆 journal 的 NBT），restart phase 以它比對「沒有重抽」。 */
    static final String RECEIPT_NBT = "receipt.nbt";
    private static final Gson JSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m5-collapse");
    private static final Identifier AFTER = Identifier.of("quantumchamber:m5_collapse_after");
    /** 原艙：Controller 在 (8,70,8)、朝北（與 M4 recovery probe 相同的位置）。 */
    private static final ChamberFrame FRAME = new ChamberFrame(new BlockPos(8, 70, 8), Direction.NORTH);

    /** phase 表：phase 名稱 → 建立這個 phase 的 factory（依 chain 的順序）。 */
    static final Map<String, PhaseFactory> PHASES = phases();

    private final Map<String, Object> proof = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    /** 已完成的 step：名稱 → 完成的 server tick 與花了幾個 tick。 */
    private final Map<String, Object> witness = new LinkedHashMap<>();
    /** 成立過的具名斷言（失敗的斷言直接丟出，不會留在這裡）。 */
    private final Map<String, Boolean> observed = new LinkedHashMap<>();
    private final List<ConnectedGameTestPlayer> players = new ArrayList<>();
    private Configuration config;
    private MinecraftServer server;
    private ServerWorld source;
    private SessionRecoveryState journal;
    private LogCapture capture;
    private Phase phase;
    private int ticks, stepIndex, stepTicks, readyTicks = -1;
    private boolean finished, passed;
    private M5TestHooks.CrashPoint crashPoint;
    // 這條 chain 的 fixture：crash phase 建立，restart phase 由 crash phase 的 receipt 讀回。
    private SessionRecoveryRecord receipt;
    private UUID participant, chamber, sid;
    private CandidateBytes token;
    private UniverseId universe;
    private int catalogBefore = -1;
    // restart phase：crash 的窗口；W4、W6 另在 JOIN 之前以 probe 的 ticket 載入投影 Controller，觀察它的 load-sync。
    private CrashWindow restartWindow;
    private ChamberControllerBlockEntity projectionController;
    private ChamberLoadSyncTestAccess.Observation loadSync;
    private LogCapture loadSyncLog;
    // W1 的 restart：JOIN 的 tick（phase 的第幾個 tick），與參與者是否已由走廊返還。
    private int joinedAt = -1;
    private boolean returned;
    // 退化的 restart：開服前就開始收的 log（初始化的 ERROR 在 SERVER_STARTED 的 default phase），與磁碟上投影 Controller 的 InstanceKind。
    private LogCapture startupLog;
    private String diskInstanceKind;

    // ---- phase 表 ----

    /**
     * 一個 phase：開服前的準備（{@code onInitialize}，例如移走權威檔）、開服時的檢查、每個 tick 的不變量（在 step 之前執行）、依序執行的 step，與停服之後的檢查
     * （{@code SERVER_STOPPED}，寫 {@code final.json} 之前）；{@code ending} 是預期的結束方式（與 m5-collapse-probe.ps1 的 chain 表一致）。開服前的準備或停服之後的
     * 檢查失敗時，記在 receipt 的 {@code failures}，phase 判 FAIL。
     */
    record Phase(Ending ending, Checked beforeStart, Checked started, Checked everyTick, List<Step> steps, Checked afterStop) {
        Phase {
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("phase 至少要有一個 step");
        }

        /** 沒有開服前的準備與停服之後的檢查。 */
        Phase(Ending ending, Checked started, Checked everyTick, List<Step> steps) {
            this(ending, NOTHING, started, everyTick, steps, NOTHING);
        }
    }

    private static final Checked NOTHING = () -> { };

    /** phase 預期的結束：正常停止（{@code final.json}），或在 crash 窗口 halt（{@code halt.json}、exit code {@link #HALT_EXIT}）。 */
    enum Ending { STOP, HALT }

    /** 一個 step：每個 tick 呼叫一次 {@code run}，回 true 時完成（記 witness，下一個 tick 進下一個 step）；{@code maxTicks} 個 tick 內沒有完成時 FAIL。 */
    record Step(String name, int maxTicks, Tick run) {
        Step {
            if (maxTicks < 1) throw new IllegalArgumentException("step 至少要有 1 個 tick");
        }
    }

    @FunctionalInterface interface Tick { boolean run() throws Exception; }

    @FunctionalInterface interface Checked { void run() throws Exception; }

    @FunctionalInterface interface PhaseFactory { Phase create(M5CollapseRecoveryProbe probe); }

    private static Map<String, PhaseFactory> phases() {
        var table = new LinkedHashMap<String, PhaseFactory>();
        // W1（spec §12）：live session 選門之後，MEASURED→COLLAPSING 剛提交、返還之前 crash；重啟之後走廊 space 已重建，不經請求就返還、清 lease，再推進到終局。
        table.put("w1-crash", M5CollapseRecoveryProbe::liveCrash);
        table.put("w1-restart", probe -> probe.liveRestart("w1-crash"));
        // W2、W3、W5（spec §12）：DORMANT 的 NEW 收據，參與者由艙外進入觸發塌縮，在 RESOLVE 的窗口 crash；重啟之後推進到 PASSAGE_READY、跨越與 (b) 終局。
        dormantNewChain(table, "w2", CrashWindow.ALLOCATION_APPENDED);
        dormantNewChain(table, "w3", CrashWindow.CATALOG_ALLOCATED);
        dormantNewChain(table, "w5", CrashWindow.DISCOVERY_APPENDED);
        // W4、W4b、W6（spec §12）：同樣的觸發，crash 在 PREPARE 的投影：寫到 Controller 那一格（W4，halt 前存檔）、目的 chunks 的 checkpoint 之前（W4b）、COMPLETE 之後（W6）。
        dormantNewChain(table, "w4", CrashWindow.PROJECTION_CELL_WRITTEN);
        dormantNewChain(table, "w4b", CrashWindow.DESTINATION_CHECKPOINT);
        dormantNewChain(table, "w6", CrashWindow.PROJECTION_COMPLETE);
        // W6 的 EXISTING 再造訪：先以 NEW 建好 U 的投影，之後 EXISTING 收據，在 REPAIR 寫了格、checkpoint 之前 crash。
        table.put("w6x-crash", M5CollapseRecoveryProbe::existingRevisitCrash);
        table.put("w6x-restart", probe -> probe.dormantNewRestart(CrashWindow.PROJECTION_REPAIRED, "w6x-crash"));
        // P-DEGRADED 的跨 JVM 版本（不是 crash 窗口）：EXISTING 再造訪提交 COLLAPSING 的同一個 tick 正常停服；下一次開服前移走 projection ledger。
        table.put("deg-setup", M5CollapseRecoveryProbe::degradedSetup);
        table.put("deg-restart", probe -> probe.degradedRestart("deg-setup"));
        return Collections.unmodifiableMap(table);
    }

    private static void dormantNewChain(Map<String, PhaseFactory> table, String chain, CrashWindow window) {
        table.put(chain + "-crash", probe -> probe.dormantNewCrash(window));
        table.put(chain + "-restart", probe -> probe.dormantNewRestart(window, chain + "-crash"));
    }

    // ---- 生命週期 ----

    @Override public void onInitialize() {
        config = Configuration.read();
        if (config == null) return;
        proof.put("producer", "M5CollapseRecoveryProbe");
        proof.put("phase", config.phase());
        proof.put("nonce", config.nonce());
        proof.put("startupNonce", config.startupNonce());
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("root", config.root().toString());
        proof.put("runtimeScope", "testmod-present");
        proof.put("loadedModIds", FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList());
        var properties = java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(argument -> argument.startsWith("-Dquantumchamber.") || argument.startsWith("-Dfabric")).toList();
        proof.put("jvmProperties", properties);
        proof.put("failures", failures);
        // 這個 probe 要讓塌縮流程真的推進；M4 recovery 用的全域暫停不得出現（build.gradle 的 m5Collapse run 刻意不帶）。
        if (properties.stream().anyMatch(argument -> argument.contains("collapseSuspended")) || System.getProperty("quantumchamber.test.collapseSuspended") != null) {
            failures.add("noCollapseSuspended：JVM 參數不得有 quantumchamber.test.collapseSuspended：" + properties);
        }
        phase = PHASES.get(config.phase()).create(this);
        try {
            phase.beforeStart().run();
        } catch (Exception | Error failure) {
            // 開服前的準備失敗：不丟出（JVM 照常開服），SERVER_STARTED 的檢查因 failures 不是空的而 FAIL、正常停止。
            failures.add(failure.toString());
            LOGGER.error("M5 collapse probe 開服前的準備失敗：phase={}", config.phase(), failure);
        }
        event("initialized");
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER, this::started);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerTickEvents.END_SERVER_TICK.register(AFTER, this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STOPPING.register(AFTER, this::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STOPPED.register(AFTER, this::stopped);
    }

    private void started(MinecraftServer owner) {
        if (server != null) return;
        server = owner;
        guarded(() -> {
            source = owner.getOverworld();
            journal = SessionRecoveryState.get(owner);
            capture = new LogCapture("quantumchamber");
            require(failures.isEmpty(), "啟動前的檢查失敗：" + failures);
            proof.put("ending", phase.ending().name());
            proof.put("steps", phase.steps().stream().map(Step::name).toList());
            phase.started().run();
            event("started");
        });
    }

    private void tick(MinecraftServer owner) {
        if (owner != server || finished || phase == null) return;
        guarded(() -> {
            require(++ticks <= PHASE_TICK_LIMIT, "phaseBounded：phase " + config.phase() + " 在 " + PHASE_TICK_LIMIT + " 個 tick 內沒有結束；" + diagnostic());
            phase.everyTick().run();
            var step = phase.steps().get(stepIndex);
            stepTicks++;
            if (!step.run().run()) {
                require(stepTicks < step.maxTicks(), "stepBounded：step " + step.name() + " 在 " + step.maxTicks() + " 個 tick 內沒有完成；" + diagnostic());
                return;
            }
            witness.put(step.name(), Map.of("serverTick", owner.getTicks(), "stepTicks", stepTicks));
            event("step " + step.name());
            stepIndex++;
            stepTicks = 0;
            if (stepIndex < phase.steps().size()) return;
            if (phase.ending() == Ending.HALT) throw new AssertionError("crashWindowReached：全部 step 都已完成，預期的 crash 窗口沒有到達；" + diagnostic());
            passed = true;
            stop();
        });
    }

    private void stopping(MinecraftServer owner) {
        if (owner != server) return;
        proof.put("stoppingSeen", true);
        closeLoadSync();
        for (var connection : players) {
            try { connection.close(); } catch (RuntimeException failure) { failures.add("closePlayer：" + failure); }
        }
        players.clear();
    }

    private void stopped(MinecraftServer owner) {
        if (owner != server) return;
        proof.put("stoppedSeen", true);
        try {
            phase.afterStop().run();
        } catch (Exception | Error failure) {
            failures.add(failure.toString());
            LOGGER.error("M5 collapse probe 停服之後的檢查失敗：phase={}", config.phase(), failure);
        }
        proof.put("witness", witness);
        proof.put("observed", observed);
        proof.put("ticks", ticks);
        proof.put("status", passed && failures.isEmpty() && Boolean.TRUE.equals(proof.get("stoppingSeen")) ? "PASS" : "FAIL");
        try {
            writeDurably(config.evidence().resolve("final.json"), JSON.toJson(proof));
        } catch (IOException failure) {
            LOGGER.error("M5 collapse probe：final receipt 寫不出來", failure);
        }
        event("stopped");
        if (capture != null) capture.close();
        if (loadSyncLog != null) loadSyncLog.close();
        if (startupLog != null) startupLog.close();
    }

    private void stop() {
        if (crashPoint != null) M5TestHooks.CRASH_POINT.remove(crashPoint);
        if (finished) return;
        finished = true;
        proof.put("stopRequested", true);
        server.stop(false);
    }

    private void guarded(Checked action) {
        try {
            action.run();
        } catch (Exception | Error failure) {
            failures.add(failure.toString());
            LOGGER.error("M5 collapse probe 失敗：phase={}", config.phase(), failure);
            event("failure");
            if (server != null) stop();
        }
    }

    // ---- crash 點與 halt receipt ----

    /** halt 之前的取證（或刻意的準備，例如 W4 的存檔）：寫進 halt receipt 的 {@code atHalt}。丟出時不 halt，crash 點記下失敗，phase 在下一個 tick FAIL。 */
    @FunctionalInterface interface HaltEvidence { void collect(Object detail, Map<String, Object> evidence) throws Exception; }

    private static final HaltEvidence NO_EVIDENCE = (detail, evidence) -> { };

    /** 依窗口裝上 crash 點：每個窗口的條件（detail 是否就是那一刻）與 halt 之前的取證。 */
    private void armWindow(CrashWindow window) {
        switch (window) {
            case COLLAPSING_COMMITTED -> armCrash(window, this::collapsingCommitted, this::committedBeforeTheReturn);
            case ALLOCATION_APPENDED, CATALOG_ALLOCATED, DISCOVERY_APPENDED -> armCrash(window, M5CollapseRecoveryProbe::appended, NO_EVIDENCE);
            case PROJECTION_CELL_WRITTEN -> armCrash(window, this::controllerCellWritten, this::savedHalfWrittenProjection);
            case DESTINATION_CHECKPOINT -> armCrash(window, this::destinationCheckpoint, this::projectionOnlyInMemory);
            case PROJECTION_COMPLETE -> armCrash(window, this::projectionCompleted, this::completeOnDisk);
            case PROJECTION_REPAIRED -> armCrash(window, this::repairWroteCells, this::repairOnlyInMemory);
            default -> throw new IllegalArgumentException("probe 沒有這個窗口的 crash 點：" + window);
        }
    }

    /** 裝上 crash 點：{@code window} 的 detail 符合 {@code condition} 的第一次，先 {@code evidence}，再寫 halt receipt、halt。 */
    private void armCrash(CrashWindow window, Predicate<Object> condition, HaltEvidence evidence) {
        var point = new M5TestHooks.CrashPoint(window, condition, (detail, hit) -> {
            var atHalt = new LinkedHashMap<String, Object>();
            try {
                evidence.collect(detail, atHalt);
            } catch (Exception failure) {
                throw new IllegalStateException("haltEvidence：halt 之前的取證失敗：" + failure, failure);
            }
            halt(window, detail, hit, atHalt);
        });
        M5TestHooks.CRASH_POINT.install(point, null);
        crashPoint = point;
        proof.put("crashWindow", window.name());
    }

    private static boolean appended(Object detail) {
        return detail instanceof CollapseAuthorities.Lookup<?> lookup && lookup.status() == CollapseAuthorities.Lookup.Status.APPENDED;
    }

    /** W1：這個 session 的 MEASURED→COLLAPSING 已提交（checked 讀回確認）。 */
    private boolean collapsingCommitted(Object detail) {
        return detail instanceof M5TestHooks.CollapsingCommit commit && commit.result().committed() && commit.expected().state() == SessionState.MEASURED
                && commit.next().state() == SessionState.COLLAPSING && commit.next().sessionUuid().equals(sid);
    }

    /** W4：第一次寫入剛寫完 U 的 Controller 那一格（targets 依 x→y→z：Controller 之前與目標不同的格已寫，之後的還沒有）。 */
    private boolean controllerCellWritten(Object detail) {
        return detail instanceof M5TestHooks.ProjectionCell cell && cell.mode() == ProjectionWriter.Mode.FIRST_WRITE
                && cell.pos().equals(FRAME.controllerPos()) && isUniverse(cell.world());
    }

    /** W4b：U 的原生存檔 checkpoint 開始之前（投影方塊已寫完並驗證）。 */
    private boolean destinationCheckpoint(Object detail) {
        return detail instanceof M5TestHooks.ChunkCheckpoint checkpoint && checkpoint.relevant().keySet().stream().anyMatch(this::isUniverse);
    }

    /** W6：projection ledger 已 checked 寫入這座艙在 U 的 COMPLETE。 */
    private boolean projectionCompleted(Object detail) {
        return detail instanceof CollapseAuthorities.WriteResult result && result.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                && result.entry().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE && entry.universeId().equals(universe)
                        && entry.chamberUuid().equals(chamber)).isPresent();
    }

    /** W6 的 EXISTING 再造訪：U 的 REPAIR 寫了格（{@code written > 0}），checkpoint 之前。 */
    private boolean repairWroteCells(Object detail) {
        return detail instanceof M5TestHooks.ProjectionWrite write && write.mode() == ProjectionWriter.Mode.REPAIR
                && write.result().status() == ProjectionWriter.Status.WRITTEN && write.result().written() > 0 && isUniverse(write.world());
    }

    /**
     * W1 的 halt 之前：磁碟上的 journal 已是 COLLAPSING（Destination 是推導出的 U），P 還沒有返還、lease 還在；P 的 playerdata 存在走廊（選門之前的原生存檔）；
     * allocation ledger 還是空的（RESOLVE 還沒有開始）。
     */
    private void committedBeforeTheReturn(Object detail, Map<String, Object> evidence) throws Exception {
        var onDisk = M5CollapseDiskEvidence.journalRecord(data(), sid).orElse(null);
        evidence.put("journalStateOnDisk", onDisk == null ? "ABSENT" : onDisk.state().name());
        expect("journalCollapsingAtHalt", onDisk != null && onDisk.state() == SessionState.COLLAPSING && destination(onDisk).equals(Optional.of(universe)),
                () -> "磁碟上的 journal 是 " + (onDisk == null ? "ABSENT" : onDisk.state() + " Destination=" + onDisk.collapse().destination()));
        expect("notReturnedAtHalt", onDisk.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !onDisk.spaceLeases().isEmpty(),
                () -> "磁碟上的參與者或 lease：" + onDisk.participants() + " leases=" + onDisk.spaceLeases());
        var dimension = M5CollapseDiskEvidence.playerDimension(saveRoot(), participant);
        evidence.put("playerDimensionOnDisk", dimension);
        expect("playerDataInTheCorridorAtHalt", SuperpositionWorld.KEY.getValue().toString().equals(dimension), () -> "磁碟上 P 的 playerdata 在 " + dimension);
        expect("noAllocationAtHalt", M5CollapseDiskEvidence.allocations(data()).isEmpty(), () -> "allocation ledger=" + allocationsOrError());
    }

    /**
     * W4 的 halt 之前：把 U 存到磁碟（{@code world.save(null, true, false)}：寫到一半的方塊落盤），再以 raw MCA 讀回。寫入迴圈在同一個 tick 內做完，正常情況下磁碟不會有
     * 寫到一半的方塊；這是刻意造出的最壞情況（「寫入途中 crash＋之前剛好存過檔」），比實際嚴格。實際會發生的「PENDING、磁碟上沒有方塊」由 W4b 涵蓋。
     */
    private void savedHalfWrittenProjection(Object detail, Map<String, Object> evidence) throws Exception {
        var cell = (M5TestHooks.ProjectionCell) detail;
        evidence.put("cellChanged", cell.changed());
        // 這一段第一次寫入到 Controller 為止（含）寫了幾格：寫到一半。
        evidence.put("cellsWrittenThroughTheController", crashPoint.reached());
        cell.world().save(null, true, false);
        var controller = diskBlock(cell.world(), FRAME.controllerPos());
        var first = diskBlock(cell.world(), local(0, 0, 0));
        var last = diskBlock(cell.world(), local(6, 6, 6));
        evidence.put("controllerOnDisk", controller.toString());
        evidence.put("firstCellOnDisk", first.toString());
        evidence.put("lastCellOnDisk", last.toString());
        expect("controllerOnDiskBeforeHalt", controllerBlock(controller), () -> "存檔之後磁碟上的 Controller 位置是 " + controller);
        expect("halfWrittenOnDisk", BEDROCK.equals(first.name()) && !BEDROCK.equals(last.name()), () -> "第一格 " + first + "，最後一格 " + last);
        expect("projectionPendingAtHalt", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection("PENDING"))), () -> "projection ledger=" + projectionsOrError());
    }

    /** W4b 的 halt 之前：投影方塊只在記憶體（Controller 已寫入 U），磁碟上沒有；projection ledger 是 PENDING。 */
    private void projectionOnlyInMemory(Object detail, Map<String, Object> evidence) throws Exception {
        var world = universeWorld().orElseThrow();
        var checkpoint = (M5TestHooks.ChunkCheckpoint) detail;
        evidence.put("checkpointChunks", checkpoint.relevant().get(world).stream().map(ChunkPos::toString).sorted().toList());
        var disk = diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        expect("projectionWrittenInMemory", world.getBlockState(FRAME.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                && world.getBlockState(local(6, 6, 6)).isOf(Blocks.BEDROCK), () -> "U 的投影方塊還沒有寫入");
        expect("projectionNotOnDiskAtHalt", !controllerBlock(disk), () -> "checkpoint 之前磁碟上已有 Controller：" + disk);
        expect("projectionPendingAtHalt", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection("PENDING"))), () -> "projection ledger=" + projectionsOrError());
    }

    /** W6 的 halt 之前：COMPLETE 已在磁碟上（checkpoint 已存下投影），journal 仍是 COLLAPSING。 */
    private void completeOnDisk(Object detail, Map<String, Object> evidence) throws Exception {
        var world = universeWorld().orElseThrow();
        var disk = diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        expect("controllerOnDiskBeforeHalt", controllerBlock(disk), () -> "checkpoint 之後磁碟上的 Controller 位置是 " + disk);
        expect("projectionCompleteAtHalt", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection())), () -> "projection ledger=" + projectionsOrError());
        var state = M5CollapseDiskEvidence.journalRecord(data(), sid).map(record -> record.state().name()).orElse("ABSENT");
        expect("journalCollapsingAtHalt", SessionState.COLLAPSING.name().equals(state), () -> "磁碟上的 journal 是 " + state);
    }

    /** W6x 的 halt 之前：REPAIR 已在記憶體修好被改壞的 shell，磁碟上仍是改壞的狀態；interior 的標記不受影響。 */
    private void repairOnlyInMemory(Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.ProjectionWrite) detail;
        var world = write.world();
        evidence.put("written", write.result().written());
        var disk = diskBlock(world, damagedShell());
        evidence.put("damagedShellOnDisk", disk.toString());
        expect("repairedInMemory", world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                () -> "shell=" + world.getBlockState(damagedShell()) + " marker=" + world.getBlockState(interiorMarker()));
        expect("repairNotOnDiskAtHalt", GLASS.equals(disk.name()), () -> "checkpoint 之前磁碟上的 shell 是 " + disk);
    }

    /** crash：寫出 {@code halt.json}（CREATE_NEW＋force）之後 {@code Runtime.halt}。receipt 寫不出來時以 {@link #HALT_RECEIPT_FAILED_EXIT} 結束。 */
    private void halt(CrashWindow window, Object detail, int hit, Map<String, Object> atHalt) {
        int exit = HALT_EXIT;
        try {
            var halt = new LinkedHashMap<String, Object>(proof);
            halt.put("status", "HALT");
            halt.put("window", window.name());
            halt.put("hit", hit);
            halt.put("reached", crashPoint.reached());
            halt.put("detail", describe(detail));
            halt.put("haltServerTick", server.getTicks());
            halt.put("universeActiveAtHalt", universe != null && universeWorld().isPresent());
            halt.put("stepAtHalt", stepIndex < phase.steps().size() ? phase.steps().get(stepIndex).name() : "-");
            halt.put("atHalt", atHalt);
            halt.put("witness", new LinkedHashMap<>(witness));
            halt.put("observed", new LinkedHashMap<>(observed));
            halt.put("disk", M5CollapseDiskEvidence.summary(saveRoot(), sid));
            halt.put("haltTimeUtc", Instant.now().toString());
            writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            event("halt " + window.name());
        } catch (Exception | Error failure) {
            LOGGER.error("M5 collapse probe：halt receipt 寫不出來", failure);
            exit = HALT_RECEIPT_FAILED_EXIT;
        }
        Runtime.getRuntime().halt(exit);
    }

    private static String describe(Object detail) {
        return switch (detail) {
            case M5TestHooks.CollapsingCommit commit -> "edge=" + commit.expected().state() + "→" + commit.next().state() + " session=" + commit.next().sessionUuid()
                    + " status=" + commit.result().status();
            case CollapseAuthorities.Lookup<?> lookup -> "status=" + lookup.status() + " value=" + lookup.value().map(String::valueOf).orElse("-");
            case CollapseAuthorities.WriteResult result -> "status=" + result.status() + " entry=" + result.entry().map(String::valueOf).orElse("-");
            case M5TestHooks.ProjectionCell cell -> "world=" + cell.world().getRegistryKey().getValue() + " mode=" + cell.mode() + " pos=" + cell.pos().toShortString()
                    + " changed=" + cell.changed();
            case M5TestHooks.ProjectionWrite write -> "world=" + write.world().getRegistryKey().getValue() + " mode=" + write.mode() + " status=" + write.result().status()
                    + " written=" + write.result().written();
            case M5TestHooks.ChunkCheckpoint checkpoint -> "worlds=" + checkpoint.relevant().entrySet().stream()
                    .map(entry -> entry.getKey().getRegistryKey().getValue() + "×" + entry.getValue().size()).sorted().toList();
            case null, default -> String.valueOf(detail);
        };
    }

    private void crashPointHealthy() {
        var point = crashPoint;
        if (point != null) expect("crashPointHealthy", point.failure() == null, () -> "crash 點失敗：" + point.failure());
    }

    // ---- fixture（原艙、參與者、DORMANT NEW 收據） ----

    private void requireFreshWorld() throws Exception {
        expect("freshJournal", journal.flushedRecords().isEmpty(), () -> "journal=" + journal.flushedRecords().keySet());
        expect("freshCatalog", UniverseRegistryState.get(server).flushedRecords().isEmpty(), () -> "catalog=" + UniverseRegistryState.get(server).flushedRecords().keySet());
        expect("freshAllocationLedger", M5CollapseDiskEvidence.allocations(data()).isEmpty(), () -> "allocation ledger 不是空的");
    }

    private void buildOrigin() {
        source.getChunk(0, 0);
        placeChamber();
        var id = UUID.randomUUID();
        var connection = connect(new GameProfile(id, "m5-" + id.toString().substring(0, 8)));
        participant = id;
        M5GameTestSupport.moveOutside(connection.player(), FRAME);
    }

    /** M4 recovery probe 的原艙寫法（基岩外殼、bulkhead 門、Controller、上方紅石塊），另把門外的通道清成空氣（參與者在門外等待，不能卡在地形裡）。 */
    private void placeChamber() {
        for (int x = -1; x <= 7; x++) for (int y = 0; y <= 9; y++) for (int z = -6; z < 0; z++) {
            source.setBlockState(ChamberSpaceCoordinates.block(FRAME, x, y, z), Blocks.AIR.getDefaultState(), 3);
        }
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
                boolean shell = x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6;
                var block = shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) block = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                source.setBlockState(ChamberGeometry.localToWorld(FRAME, x, y, z), block, 3);
            }
            source.setBlockState(FRAME.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, Direction.NORTH), 3);
        });
        source.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
    }

    /** DORMANT 的 NEW 收據（P 已返還、沒有 lease）。只建立，不寫入。 */
    private SessionRecoveryRecord dormantNewReceipt() {
        return M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), Map.of(participant, true), List.of(), true);
    }

    /** 收據 put＋flush、核對 flushed 與收據 exact 相同，再以它設定這條 chain 的 session、UniverseId 與 allocation token（{@link #adopt}）。 */
    private void putReceipt(SessionRecoveryRecord measured) {
        journal.put(measured);
        journal.flush(server);
        expect("receiptFlushed", measured.equals(journal.flushedRecords().get(measured.sessionUuid())), () -> "flushed 與收據不同");
        adopt(measured);
    }

    /** DORMANT 的 NEW 收據 put＋flush，寫出 {@link #RECEIPT_NBT}，再原生存檔。 */
    private void writeDormantNewReceipt() throws Exception {
        writeCrashReceipt(dormantNewReceipt());
    }

    /**
     * crash 之前的收據（DORMANT：P 已返還、沒有 lease）：put＋flush，記下 crash 前的 catalog 筆數，寫出 {@link #RECEIPT_NBT}（restart 以它比對「沒有重抽」），再原生存檔
     * （原艙、registry、journal、P 的 playerdata 與 U 的 chunks 都在磁碟上）。
     */
    private void writeCrashReceipt(SessionRecoveryRecord measured) throws Exception {
        putReceipt(measured);
        recordReceipt();
        expect("nativeSaveBeforeTrigger", server.saveAll(false, true, true), () -> "saveAll 回 false");
    }

    /** 這條 chain 的 MEASURED 收據（{@link #receipt}，已 flushed）：記下 crash 前的 catalog 筆數，寫出 {@link #RECEIPT_NBT}，摘要記在 receipt 的 {@code receipt}。 */
    private void recordReceipt() throws Exception {
        var measured = receipt;
        catalogBefore = UniverseRegistryState.get(server).flushedRecords().size();
        var file = config.evidence().resolve(RECEIPT_NBT);
        require(!Files.exists(file), "receipt NBT 已存在：" + file);
        var single = new SessionRecoveryState();
        single.put(measured);
        var wrapped = new NbtCompound();
        wrapped.put("data", single.writeNbt(new NbtCompound()));
        NbtIo.writeCompressed(wrapped, file);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("sessionUuid", sid.toString());
        evidence.put("chamberUuid", chamber.toString());
        evidence.put("participant", participant.toString());
        evidence.put("universeId", universe.value().toString());
        evidence.put("allocationToken", HexFormat.of().formatHex(token.bytes()));
        evidence.put("candidate", created() ? "NEW" : "EXISTING");
        evidence.put("catalogBefore", catalogBefore);
        evidence.put("receiptNbt", RECEIPT_NBT);
        evidence.put("receiptNbtSha256", M5CollapseDiskEvidence.sha256(file));
        evidence.put("candidateSha256", M5CollapseDiskEvidence.sha256(wrapped));
        proof.put("receipt", evidence);
    }

    /**
     * 以收據設定這條 chain 的 session 與 UniverseId：NEW 由 intent 的 allocation token 推導；EXISTING 是候選的 UniverseId，allocation token 沿用建立 U 的那一筆
     * （同一個 JVM 由 NEW 那一段設定，restart 由 halt receipt 讀回）。
     */
    private void adopt(SessionRecoveryRecord measured) {
        receipt = measured;
        sid = measured.sessionUuid();
        var candidate = measured.selectedCandidate().orElseThrow();
        if (candidate instanceof QuantumCandidate.New created) {
            token = created.intent().allocationToken();
            universe = UniverseAllocationLedger.deriveUniverseId(token);
        } else {
            universe = ((QuantumCandidate.Existing) candidate).universeId();
        }
    }

    /** 這條 chain 的收據選的是 NEW（catalog 恰多 1 筆）；EXISTING 時 catalog 不變。 */
    private boolean created() {
        return receipt.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New;
    }

    /** catalog 相對於 crash 前可以多幾筆：NEW 1、EXISTING 0（spec §12）。 */
    private int catalogGrowth() {
        return created() ? 1 : 0;
    }

    /**
     * restart phase 的開頭：讀回 crash phase（{@code crashPhase}）的 {@code halt.json} 與 {@link #RECEIPT_NBT}，核對 halt receipt 的 status、窗口、phase、nonce，
     * startupNonce 與這個 JVM 不同，receipt NBT 的 SHA-256 與 halt receipt 記的相同；再以收據設定這條 chain 的 fixture（session、原艙、參與者、UniverseId、
     * crash 前的 catalog 筆數）。回傳 halt receipt。{@code window} 為 null 時，前一個 phase 是正常停止的 setup（退化的 chain）：改讀它的 {@code final.json}，
     * 核對 status PASS、ending STOP、phase、nonce，其餘相同。
     */
    private JsonObject readCrashReceipt(String crashPhase, CrashWindow window) throws Exception {
        var crashEvidence = config.evidence().resolveSibling(crashPhase);
        var haltPath = crashEvidence.resolve(window == null ? "final.json" : "halt.json");
        JsonObject halt;
        if (window == null) {
            expect("setupReceiptPresent", Files.exists(haltPath) && !Files.exists(crashEvidence.resolve("halt.json")), () -> "setup phase 沒有正常停止的 receipt：" + crashEvidence);
            halt = JsonParser.parseString(Files.readString(haltPath)).getAsJsonObject();
            expect("setupReceiptMatches", "PASS".equals(text(halt, "status")) && Ending.STOP.name().equals(text(halt, "ending")) && crashPhase.equals(text(halt, "phase"))
                    && config.nonce().equals(text(halt, "nonce")), () -> "setup receipt 不符：status=" + text(halt, "status") + " ending=" + text(halt, "ending"));
        } else {
            expect("crashReceiptPresent", Files.exists(haltPath) && !Files.exists(crashEvidence.resolve("final.json")), () -> "crash phase 沒有 halt receipt：" + crashEvidence);
            halt = JsonParser.parseString(Files.readString(haltPath)).getAsJsonObject();
            expect("crashReceiptMatches", "HALT".equals(text(halt, "status")) && window.name().equals(text(halt, "window")) && crashPhase.equals(text(halt, "phase"))
                    && config.nonce().equals(text(halt, "nonce")), () -> "halt receipt 不符：status=" + text(halt, "status") + " window=" + text(halt, "window"));
        }
        expect("differentStartupNonce", !config.startupNonce().equals(text(halt, "startupNonce")), () -> "與 crash phase 相同的 startupNonce");
        var noted = halt.getAsJsonObject("receipt");
        var receiptFile = crashEvidence.resolve(RECEIPT_NBT);
        expect("receiptNbtIntact", M5CollapseDiskEvidence.sha256(receiptFile).equals(text(noted, "receiptNbtSha256")), () -> "receipt NBT 的 SHA-256 與 halt receipt 不同");
        var decoded = SessionRecoveryState.fromNbt(NbtIo.readCompressed(receiptFile, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data"));
        decoded.requireHealthy();
        var measured = decoded.flushedRecords().get(UUID.fromString(text(noted, "sessionUuid")));
        expect("receiptDecoded", measured != null && measured.state() == SessionState.MEASURED, () -> "receipt NBT 裡沒有這個 session 的 MEASURED 收據");
        adopt(measured);
        if (!created()) {
            // EXISTING：allocation token 是建立 U 的那一筆（halt receipt 記下的），它推導出的必須就是候選的 UniverseId。
            token = new CandidateBytes(HexFormat.of().parseHex(text(noted, "allocationToken")));
            expect("existingUniverseWasAllocated", UniverseAllocationLedger.deriveUniverseId(token).equals(universe),
                    () -> "halt receipt 的 allocation token 推導出的 UniverseId 不是候選的 " + universe.value());
        }
        chamber = measured.chamberUuid();
        participant = measured.participants().getFirst().playerUuid();
        catalogBefore = noted.get("catalogBefore").getAsInt();
        expect("receiptIdentity", universe.value().toString().equals(text(noted, "universeId")) && participant.toString().equals(text(noted, "participant")),
                () -> "receipt NBT 與 halt receipt 的 UniverseId 或參與者不同");
        String prefix = window == null ? "final" : "halt";
        proof.put(window == null ? "setupReceipt" : "crashReceipt", Map.of("phase", crashPhase, prefix + "Sha256", M5CollapseDiskEvidence.sha256(haltPath),
                prefix + "Pid", halt.get("pid").getAsLong(), prefix + "StartupNonce", text(halt, "startupNonce"), prefix + "StartTimeUtc", text(halt, "startTimeUtc")));
        proof.put("universeId", universe.value().toString());
        return halt;
    }

    // ---- DORMANT 收據的 crash／restart（W2、W3、W5、W4、W4b、W6；restart 也用於 W6x） ----

    /**
     * crash phase：fresh 世界蓋原艙、連線參與者 P 並移到艙外 → 原艙註冊之後寫 DORMANT 的 NEW 收據（P 已返還、沒有 lease）、原生存檔（原艙、registry、journal、
     * P 的 playerdata 都在磁碟上；P 存在艙外），再裝上 {@code window} 的 crash 點（{@link #armWindow}）→ P 由艙外進入 → 塌縮提交 COLLAPSING → RESOLVE 或 PREPARE
     * 走到窗口時 halt。
     */
    private Phase dormantNewCrash(CrashWindow window) {
        return new Phase(Ending.HALT, this::requireFreshWorld, this::crashPointHealthy, List.of(
                new Step("build", 1, () -> { buildOrigin(); return true; }),
                new Step("registered", 200, () -> {
                    if (!M5GameTestSupport.registeredIdle(source, FRAME)) return false;
                    chamber = M5GameTestSupport.controller(source, FRAME).chamberUuid();
                    return true;
                }),
                new Step("receipt", 1, () -> { writeDormantNewReceipt(); armWindow(window); return true; }),
                new Step("enter", 1, () -> { M5GameTestSupport.moveInside(participantPlayer(), FRAME); return true; }),
                new Step("committed", 40, this::committed),
                new Step("halt", 400, () -> false)));
    }

    /** 塌縮已提交 COLLAPSING（flushed），Destination 是這條 chain 的 UniverseId（NEW 是推導出的，EXISTING 是候選的）。 */
    private boolean committed() {
        var record = journal.flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.COLLAPSING) return false;
        expect(created() ? "committedToTheDerivedUniverse" : "committedToTheExistingUniverse", destination(record).equals(Optional.of(universe)),
                () -> "Destination=" + record.collapse().destination());
        return true;
    }

    /**
     * restart phase：讀回 crash phase 的 halt receipt 與 MEASURED 收據，開服時斷言窗口的磁碟狀態（W2 以外另斷言 bootstrap 已物化 {@code EAGER_ENABLED} 的 U；
     * W4、W6 在 JOIN 之前載入磁碟上的投影 Controller，等它經 load-sync 辨識為 inert 的 PROJECTION）；之後 P 真 JOIN（在艙外、來源世界）→ RESOLVE 完成 → P 由艙外進入 → PASSAGE_READY（門 passable、比較器 15、投影 COMPLETE）→ P 越過門板中線、傳送到 U →
     * (b) 全員都已跨過的終局；投影窗口另斷言 PREPARE 怎麼完成（{@link #preparedAsTheWindowRequires}）。每個 tick 斷言 catalog 只出現 Destination 的 UniverseId、record
     * 的 Destination 與候選不變。
     */
    private Phase dormantNewRestart(CrashWindow window, String crashPhase) {
        var steps = new ArrayList<Step>();
        // W4、W6：參與者 JOIN 之前，磁碟上的投影 Controller 經 load-sync 辨識為 inert 的 PROJECTION（不記 log）。
        if (window == CrashWindow.PROJECTION_CELL_WRITTEN || window == CrashWindow.PROJECTION_COMPLETE) steps.add(new Step("recognized", 60, this::projectionRecognized));
        steps.add(new Step("join", 1, () -> { joinParticipant(); return true; }));
        steps.addAll(passageSteps());
        return new Phase(Ending.STOP, () -> restartStarted(window, crashPhase), this::restartInvariants, steps);
    }

    /** restart 由 RESOLVE 推進到終局的 step（各 restart 共用）：RESOLVE 完成 → P 進入艙內 → PASSAGE_READY → P 越過門板中線、傳送到 U → (b) 全員都已跨過的終局。 */
    private List<Step> passageSteps() {
        return List.of(
                new Step("resolved", 400, this::resolvedThenEnter),
                new Step("ready", 600, this::passageReady),
                new Step("doorway", 1, () -> {
                    M5GameTestSupport.standInDoorway(participantPlayer(), FRAME, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                    return true;
                }),
                new Step("crossed", 10, this::crossed),
                new Step("ended", 40, this::endedByEveryoneCrossing));
    }

    /**
     * restart 的開服檢查：窗口在 RESOLVE 的哪一步（halt 當下是否已物化）、record 仍是 COLLAPSING 且 Destination 與候選不變、allocation 恰一筆（W1 還沒有），以及窗口
     * 各自的磁碟狀態；W1 另斷言參與者還沒有返還、走廊 space 已重建、沒有保留式 recovery 的請求。
     */
    private void restartStarted(CrashWindow window, String crashPhase) throws Exception {
        restartWindow = window;
        var halt = readCrashReceipt(crashPhase, window);
        // 窗口的位置：W1 在 RESOLVE 之前（返還之前），W2、W3 在物化之前 crash，W5 在物化之後（RESOLVE 的第 3 步），W4、W4b、W6、W6x 在 PREPARE。
        boolean live = window == CrashWindow.COLLAPSING_COMMITTED;
        boolean materializedWindow = !live && window != CrashWindow.ALLOCATION_APPENDED && window != CrashWindow.CATALOG_ALLOCATED;
        boolean activeAtHalt = halt.get("universeActiveAtHalt").getAsBoolean();
        expect(materializedWindow ? "materializedBeforeTheCrash" : "notMaterializedBeforeTheCrash",
                activeAtHalt == materializedWindow, () -> "halt 當下 U 的物化狀態=" + activeAtHalt);
        var current = journal.flushedRecords().get(sid);
        expect("restartedCollapsing", current != null && current.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(journal, sid));
        expect("sameDestination", destination(current).equals(Optional.of(universe)), () -> "Destination=" + current.collapse().destination());
        expect("noRedraw", sameCandidate(receipt, current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
        if (live) {
            // W1（spec §13.2）：未返還的 COLLAPSING 在 attach 時重建走廊 space，返還由 receipt 推導、不經 requestMeasuredRecovery。
            expect("notReturnedAtRestart", current.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty()
                    && MeasuredRecoveryPhase.from(current) == MeasuredRecoveryPhase.RETURN_PLAYERS, () -> "參與者 " + current.participants() + " leases=" + current.spaceLeases());
            expect("spaceRestored", spaceExists(), () -> "開服之後沒有這個 session 的走廊 space");
            expect("noMeasuredRecoveryRequest", !pages().measuredRecoveryRequested(sid), () -> "COLLAPSING 的 space 標記為已請求保留式 recovery");
            expect("noAllocationYet", M5CollapseDiskEvidence.allocations(data()).isEmpty(), () -> "allocation ledger=" + allocationsOrError());
        } else {
            expect("oneAllocationForTheToken", M5CollapseDiskEvidence.allocations(data()).equals(List.of(allocation())), () -> "allocation ledger=" + allocationsOrError());
        }
        var catalog = UniverseRegistryState.get(server).flushedRecords();
        boolean discovered = M5CollapseDiskEvidence.discovery(saveRoot()).stream().anyMatch(record -> record.universeId().equals(universe));
        boolean active = universeWorld().isPresent();
        if (live || window == CrashWindow.ALLOCATION_APPENDED) {
            expect("catalogNotYetAllocated", catalog.size() == catalogBefore && !catalog.containsKey(universe), () -> "catalog=" + catalog.keySet());
            expect("notYetMaterialized", !active, () -> "U 已物化");
            expect("notYetDiscovered", !discovered, () -> "discovery 已有 U");
        } else {
            if (created()) {
                expect("catalogAllocatedOnce", catalog.size() == catalogBefore + 1 && eagerEnabled(catalog.get(universe)), () -> "catalog=" + catalog);
            } else {
                expect("catalogUnchanged", catalog.size() == catalogBefore && eagerEnabled(catalog.get(universe)), () -> "catalog=" + catalog);
            }
            // spec §12 W3：重啟時由 EAGER_ENABLED 物化（UniverseLifecycleService 的開服 bootstrap，早於這個 SERVER_STARTED 的 AFTER phase）。
            expect("bootstrapMaterializedEagerEnabled", active, () -> "開服 bootstrap 沒有物化 U");
            if (window == CrashWindow.CATALOG_ALLOCATED) expect("notYetDiscovered", !discovered, () -> "discovery 已有 U");
            else expect("alreadyDiscovered", discovered, () -> "discovery 沒有 U");
        }
        var projections = M5CollapseDiskEvidence.projections(data());
        switch (window) {
            case COLLAPSING_COMMITTED, ALLOCATION_APPENDED, CATALOG_ALLOCATED, DISCOVERY_APPENDED ->
                    expect("noProjectionYet", projections.isEmpty(), () -> "projection ledger=" + projections);
            case PROJECTION_CELL_WRITTEN -> {
                expect("projectionStillPending", projections.equals(List.of(projection("PENDING"))), () -> "projection ledger=" + projections);
                var disk = diskBlock(universeWorld().orElseThrow(), FRAME.controllerPos());
                expect("controllerOnDiskAtRestart", controllerBlock(disk), () -> "磁碟上的 Controller 位置是 " + disk);
                loadProjection();
            }
            case DESTINATION_CHECKPOINT -> {
                expect("projectionStillPending", projections.equals(List.of(projection("PENDING"))), () -> "projection ledger=" + projections);
                var world = universeWorld().orElseThrow();
                var controller = diskBlock(world, FRAME.controllerPos());
                var last = diskBlock(world, local(6, 6, 6));
                expect("noProjectionOnDiskAtRestart", !controllerBlock(controller) && !BEDROCK.equals(last.name()),
                        () -> "磁碟上已有投影方塊：Controller 位置 " + controller + "，最後一格 " + last);
            }
            case PROJECTION_COMPLETE -> {
                expect("projectionCompleteOnDisk", projections.equals(List.of(projection())), () -> "projection ledger=" + projections);
                var disk = diskBlock(universeWorld().orElseThrow(), FRAME.controllerPos());
                expect("controllerOnDiskAtRestart", controllerBlock(disk), () -> "磁碟上的 Controller 位置是 " + disk);
                loadProjection();
            }
            case PROJECTION_REPAIRED -> {
                expect("projectionCompleteOnDisk", projections.equals(List.of(projection())), () -> "projection ledger=" + projections);
                var world = universeWorld().orElseThrow();
                var shell = diskBlock(world, damagedShell());
                var marker = diskBlock(world, interiorMarker());
                expect("repairLostWithTheCrash", GLASS.equals(shell.name()), () -> "磁碟上被改壞的 shell 是 " + shell);
                expect("interiorMarkerOnDisk", GOLD_BLOCK.equals(marker.name()), () -> "磁碟上的 interior 標記是 " + marker);
            }
            default -> throw new IllegalArgumentException("restart phase 不支援的窗口：" + window);
        }
        proof.put("startedDisk", M5CollapseDiskEvidence.summary(saveRoot(), sid));
    }

    /**
     * W4、W6 的 restart（JOIN 之前）：以 probe 的 ticket（{@link ProbeTicket}）載入投影所在的 chunk，開始觀察磁碟上這個 Controller 的 load-sync
     * （{@code SERVER_STARTED} 的 AFTER phase：開服 bootstrap 已載入時，佇列也要到 tick 1 才處理，所以觀察得到）。load-sync 處理時就同步 refresh
     * （{@code REDSTONE.onLoad}），讀電位的 ±3 格都在同一個 chunk，所以 level FULL 就夠，不必 ticking。
     */
    private void loadProjection() {
        var world = universeWorld().orElseThrow();
        // load-sync 佇列用自己的 logger（不是 "quantumchamber"）：載入之前就開始收，「沒有 log 提到座標」才涵蓋它。
        loadSyncLog = new LogCapture(ChamberControllerLoadSyncQueue.class.getName());
        var chunk = new ChunkPos(FRAME.controllerPos());
        proof.put("projectionChunkLoadedAtStart", world.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null);
        holdProjectionChunk(world);
        if (!(world.getBlockEntity(FRAME.controllerPos()) instanceof ChamberControllerBlockEntity controller)) {
            throw new AssertionError("projectionControllerLoaded：載入之後 U 的 Controller 位置沒有 Controller block entity：" + world.getBlockState(FRAME.controllerPos()));
        }
        observed.put("projectionControllerLoaded", true);
        projectionController = controller;
        proof.put("projectionControllerAtLoad", Map.of("instanceKind", controller.instanceKind().name(), "state", controller.chamberState().name(),
                "powerInitialized", controller.powerInitialized(), "loadSyncPending", ChamberLoadSyncTestAccess.hasPending(server, controller)));
        loadSync = ChamberLoadSyncTestAccess.observe(world, controller);
    }

    /**
     * W4、W6：load-sync 處理（CONSUMED）之後，磁碟上的 Controller 是 inert 的 PROJECTION（spec §9.3：依投影保護索引，INVALID、比較器 0、沒有 chamber UUID），整段沒有任何
     * log 提到它的座標，參與者還沒有 JOIN。W6（COMPLETE）的不變量 cell 受保護、interior 不受；W4（PENDING）只辨識、不保護。probe 的 ticket 不移除（{@link ProbeTicket}）。
     */
    private boolean projectionRecognized() {
        var world = universeWorld().orElseThrow();
        var controller = projectionController;
        if (!loadSync.outcomes().contains(ChamberLoadSyncOutcome.CONSUMED) || !inertProjection(world, controller)) return false;
        var pos = FRAME.controllerPos();
        var mentions = Stream.concat(capture.entries().stream(), loadSyncLog.entries().stream())
                .map(LogCapture.Entry::message).filter(message -> message.contains(pos.toShortString()) || message.contains(pos.toString())).toList();
        expect("noLogAboutTheProjectionController", mentions.isEmpty(), () -> "log 提到投影 Controller 的座標：" + mentions);
        expect("recognizedBeforeJoin", onlinePlayer() == null, () -> "參與者已 JOIN");
        var key = world.getRegistryKey();
        expect("indexKnowsTheController", ProjectionProtectionIndex.projectionControllerAt(server, key, pos), () -> "投影保護索引不認得這個 Controller");
        var protection = ChamberProtectionService.get();
        if (restartWindow == CrashWindow.PROJECTION_COMPLETE) {
            expect("invariantCellsProtected", !protection.mayMutate(world, pos) && !protection.mayMutate(world, local(0, 0, 0))
                    && !protection.mayMutate(world, local(3, 3, 0)) && protection.mayMutate(world, local(3, 3, 3)),
                    () -> "Controller、shell、門應受保護，interior 不應受保護");
        } else {
            expect("pendingNotProtected", protection.mayMutate(world, pos) && protection.mayMutate(world, local(0, 0, 0)), () -> "PENDING 的投影不應受保護");
        }
        proof.put("loadSyncOutcomes", loadSync.outcomes().stream().map(Enum::name).toList());
        closeLoadSync();
        return true;
    }

    private void closeLoadSync() {
        var observation = loadSync;
        loadSync = null;
        if (observation == null) return;
        try {
            observation.close();
        } catch (RuntimeException | Error failure) {
            failures.add("closeLoadSync：" + failure);
        }
    }

    /**
     * 每個 tick（spec §12 的共同斷言）：catalog 只出現 Destination 的 UniverseId、NEW 最多多 1 筆（EXISTING 不變）；record 在時 Destination 與候選不變；參與者在來源世界或 U。
     * W1 的參與者 JOIN 在走廊（playerdata 存在走廊），返還之前可以在走廊，但 JOIN 之後超過 {@link #RETURN_TICK_LIMIT} 個 tick 仍在走廊就是被困住。
     */
    private void restartInvariants() {
        var catalogIds = UniverseRegistryState.get(server).records().keySet();
        expect("catalogOnlyDestination", catalogIds.stream().allMatch(universe::equals), () -> "Destination 是 " + universe.value() + "，catalog 有 " + catalogIds);
        expect(created() ? "catalogAtMostOneMore" : "catalogNotGrown", catalogIds.size() <= catalogBefore + catalogGrowth(),
                () -> "catalog " + catalogIds.size() + " 筆，crash 前 " + catalogBefore);
        var current = journal.flushedRecords().get(sid);
        if (current != null) {
            expect("sameDestination", destination(current).equals(Optional.of(universe)), () -> "Destination=" + current.collapse().destination());
            expect("noRedraw", sameCandidate(receipt, current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
        }
        var player = onlinePlayer();
        if (player != null) {
            var world = player.getServerWorld();
            if (restartWindow == CrashWindow.COLLAPSING_COMMITTED && !returned && world == corridor()) {
                expect("notTrappedInTheCorridor", ticks - joinedAt <= RETURN_TICK_LIMIT, () -> "JOIN 之後 " + (ticks - joinedAt) + " 個 tick 仍在走廊：" + diagnostic());
            } else {
                expect("participantInSourceOrUniverse", world == source || world == universeWorld().orElse(null), () -> "參與者在 " + world.getRegistryKey().getValue());
            }
        }
    }

    private void joinParticipant() {
        joinParticipant(false);
    }

    /**
     * 參與者真 JOIN，在來源世界、沒有被 recovery 攔截。crash phase 的 playerdata 是觸發之前存的（P 在艙外）；退化的 setup 是在 P 進入艙內之後正常停服，所以 P JOIN 在艙內
     * （{@code insideExpected}）。
     */
    private void joinParticipant(boolean insideExpected) {
        var connection = connect(new GameProfile(participant, "m5-" + participant.toString().substring(0, 8)));
        var player = connection.player();
        expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
        expect("joinedInTheSourceWorld", player.getServerWorld() == source, () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
        if (insideExpected) expect("joinedInsideTheChamber", inside(player), () -> "參與者 JOIN 在艙外：" + player.getPos());
        else expect("joinedOutsideTheChamber", !inside(player), () -> "參與者 JOIN 在艙內：" + player.getPos());
        expect("participantNotBlocked", !SessionRecoveryManager.blocks(participant, server), () -> "已返還的參與者被 recovery 攔截");
    }

    private boolean resolvedThenEnter() throws Exception {
        if (capture.matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).isEmpty()) return false;
        expect("materialized", universeWorld().isPresent(), () -> "RESOLVE 完成但 U 沒有物化");
        var catalog = UniverseRegistryState.get(server).flushedRecords();
        expect(created() ? "catalogExactlyOneMore" : "catalogUnchanged", catalog.size() == catalogBefore + catalogGrowth() && eagerEnabled(catalog.get(universe)),
                () -> "catalog=" + catalog);
        expect("discoveredOnce", M5CollapseDiskEvidence.discovery(saveRoot()).stream().filter(record -> record.universeId().equals(universe)).count() == 1,
                () -> "discovery 沒有恰一筆 U");
        M5GameTestSupport.moveInside(participantPlayer(), FRAME);
        return true;
    }

    /** PASSAGE_READY 之後最多再等 5 個 tick，讓門與 Controller 的刷新落地，再斷言 passable、比較器 15 與投影 COMPLETE；之後 P 站到門板中線內側。 */
    private boolean passageReady() throws Exception {
        var record = journal.flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.PASSAGE_READY) return false;
        readyTicks++;
        boolean door = M5GameTestSupport.doorPassable(source, FRAME), comparator = controllerSignal(ChamberState.PASSAGE, 15);
        if (!(door && comparator) && readyTicks < 5) return false;
        expect("noOneCrossedYet", record.collapse().crossed().isEmpty(), () -> "Crossed=" + record.collapse().crossed());
        expect("doorPassable", door, () -> "PASSAGE_READY 之後門不是 passable 量子膜");
        expect("comparatorFifteen", comparator, () -> "PASSAGE_READY 之後 Controller 不是 PASSAGE／比較器 15");
        expect("projectionComplete", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection())), () -> "projection ledger=" + projectionsOrError());
        preparedAsTheWindowRequires();
        M5GameTestSupport.standInDoorway(participantPlayer(), FRAME, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO);
        return true;
    }

    /**
     * 投影窗口的 restart 怎麼完成 PREPARE（「準備完成」INFO 的模式與格數，加上 READY 之前的 checkpoint 已把方塊存到磁碟）：W4、W4b 照第一次寫入重寫（PENDING），
     * W6 修復 0 格（COMPLETE，方塊與 crash 前存下的相同），W6x 再修復一次被改壞的那一格、interior 的標記保留。其他窗口不檢查。
     */
    private void preparedAsTheWindowRequires() throws Exception {
        if (restartWindow == null) return;
        var prepared = capture.matching(Level.INFO, "準備完成（預載與投影都已完成）", sid.toString()).stream().map(LogCapture.Entry::message).toList();
        var world = universeWorld().orElseThrow();
        switch (restartWindow) {
            case PROJECTION_CELL_WRITTEN, DESTINATION_CHECKPOINT -> {
                expect("rewrittenAsFirstWrite", prepared.size() == 1 && prepared.getFirst().contains("投影=FIRST_WRITE（寫入 ") && !prepared.getFirst().contains("（寫入 0 格）"),
                        () -> "「準備完成」=" + prepared);
                var last = diskBlock(world, local(6, 6, 6));
                var controller = diskBlock(world, FRAME.controllerPos());
                expect("checkpointedBeforeReady", BEDROCK.equals(last.name()) && controllerBlock(controller), () -> "READY 時磁碟上最後一格 " + last + "，Controller 位置 " + controller);
            }
            case PROJECTION_COMPLETE -> expect("repairedNothing", prepared.size() == 1 && prepared.getFirst().contains("投影=REPAIR（寫入 0 格）"), () -> "「準備完成」=" + prepared);
            case PROJECTION_REPAIRED -> {
                expect("repairedAgain", prepared.size() == 1 && prepared.getFirst().contains("投影=REPAIR（寫入 1 格）"), () -> "「準備完成」=" + prepared);
                var shell = diskBlock(world, damagedShell());
                expect("repairCheckpointedBeforeReady", BEDROCK.equals(shell.name()) && world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK),
                        () -> "READY 時磁碟上被改壞的 shell 是 " + shell);
                var marker = diskBlock(world, interiorMarker());
                expect("interiorMarkerKept", GOLD_BLOCK.equals(marker.name()) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                        () -> "interior 標記：磁碟 " + marker + "，記憶體 " + world.getBlockState(interiorMarker()));
            }
            default -> { }
        }
    }

    private boolean crossed() {
        var world = universeWorld().orElseThrow();
        var player = participantPlayer();
        if (player.getServerWorld() != world) return false;
        var record = journal.flushedRecords().get(sid);
        expect("crossedRecorded", record == null || record.collapse().crossed().equals(List.of(participant)), () -> "Crossed=" + record.collapse().crossed());
        expect("oneCrossingInfo", capture.matching(Level.INFO, "參與者已穿過量子通道", sid.toString()).size() == 1, () -> "「參與者已穿過量子通道」不是恰一則");
        return true;
    }

    private boolean endedByEveryoneCrossing() throws Exception {
        if (journal.flushedRecords().containsKey(sid) || journal.records().containsKey(sid)) return false;
        expect("endedByEveryoneCrossing", capture.matching(Level.INFO, "量子通道已結束", "(b) 全員都已跨過", sid.toString()).size() == 1,
                () -> "沒有恰一則 (b) 的結束 INFO");
        expect("plainOpenAfterTheEnd", M5GameTestSupport.doorAll(source, FRAME, true, MembraneState.NONE), () -> "終局之後原艙門不是一般開門");
        sameUniverseEverywhere();
        return true;
    }

    /** spec §12：同一個 UniverseId（Destination、allocation entry、catalog、discovery、projection entry、ACTIVE world key），catalog NEW 恰多 1 筆、EXISTING 不變，P 在 U。 */
    private void sameUniverseEverywhere() throws Exception {
        var catalog = UniverseRegistryState.get(server).flushedRecords();
        var record = catalog.get(universe);
        expect(created() ? "catalogExactlyOneMore" : "catalogUnchanged", catalog.size() == catalogBefore + catalogGrowth() && eagerEnabled(record),
                () -> "catalog=" + catalog);
        expect("catalogOnDisk", M5CollapseDiskEvidence.catalog(data()).keySet().equals(catalog.keySet()), () -> "磁碟上的 catalog 與 flushed 不同");
        expect("oneAllocationForTheToken", M5CollapseDiskEvidence.allocations(data()).equals(List.of(allocation())), () -> "allocation ledger=" + allocationsOrError());
        var discovery = M5CollapseDiskEvidence.discovery(saveRoot());
        expect("discoveredOnce", discovery.size() == 1 && discovery.getFirst().universeId().equals(universe) && discovery.getFirst().gameplayEligible(),
                () -> "discovery=" + discovery);
        expect("projectionComplete", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection())), () -> "projection ledger=" + projectionsOrError());
        var world = universeWorld();
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        expect("activeWorldKeyIsTheDestination", world.isPresent() && descriptor != null && world.get().getRegistryKey().equals(descriptor.worldKey()),
                () -> "ACTIVE world 與 catalog 的 world key 不同");
        long universeWorlds = StreamSupport.stream(server.getWorlds().spliterator(), false)
                .filter(candidate -> UniverseRegistryState.get(server).findByWorldKey(candidate.getRegistryKey()).isPresent()).count();
        expect("oneUniverseWorld", universeWorlds == 1, () -> "Universe world 有 " + universeWorlds + " 個");
        var player = participantPlayer();
        expect("participantInTheUniverse", player.getServerWorld() == world.orElseThrow(), () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue());
        proof.put("finalDisk", M5CollapseDiskEvidence.summary(saveRoot(), sid));
    }

    // ---- EXISTING 再造訪的 crash（W6x） ----

    /**
     * crash phase（W6 的 EXISTING 再造訪）：與 {@link #dormantNewCrash} 相同的原艙與 DORMANT NEW 收據 → P 由艙外進入 → 塌縮到 PASSAGE_READY（U 的投影 COMPLETE）→ 拿掉
     * Controller 上方的紅石塊，通道以 (d) 來源艙沒有供電結束 → P 移到艙外、恢復供電 → U 的投影改壞一格 shell（玻璃）、interior 放金塊標記 → EXISTING 收據（同一個 U）、
     * 原生存檔 → 裝上 crash 點 → P 由艙外進入 → RESOLVE（EXISTING）→ PREPARE 以 REPAIR 修好那一格之後、checkpoint 之前 halt。
     */
    private Phase existingRevisitCrash() {
        var steps = new ArrayList<>(firstPassageSteps());
        steps.addAll(List.of(
                new Step("existingReceipt", 1, () -> {
                    damageProjection();
                    writeCrashReceipt(existingReceiptForTheUniverse());
                    armWindow(CrashWindow.PROJECTION_REPAIRED);
                    return true;
                }),
                new Step("enter", 1, () -> { M5GameTestSupport.moveInside(participantPlayer(), FRAME); return true; }),
                new Step("committed", 40, this::committed),
                new Step("halt", 400, () -> false)));
        return new Phase(Ending.HALT, this::requireFreshWorld, this::crashPointHealthy, steps);
    }

    /**
     * EXISTING 再造訪的前半段（W6x 與退化的 setup 共用）：與 {@link #dormantNewCrash} 相同的原艙與 DORMANT NEW 收據 → P 由艙外進入 → 塌縮到 PASSAGE_READY（U 的投影
     * COMPLETE）→ 拿掉 Controller 上方的紅石塊，通道以 (d) 來源艙沒有供電結束 → P 移到艙外、恢復供電，原艙回到 IDLE。
     */
    private List<Step> firstPassageSteps() {
        return List.of(
                new Step("build", 1, () -> { buildOrigin(); return true; }),
                new Step("registered", 200, () -> {
                    if (!M5GameTestSupport.registeredIdle(source, FRAME)) return false;
                    chamber = M5GameTestSupport.controller(source, FRAME).chamberUuid();
                    return true;
                }),
                new Step("firstReceipt", 1, () -> { putReceipt(dormantNewReceipt()); return true; }),
                new Step("firstEnter", 1, () -> { M5GameTestSupport.moveInside(participantPlayer(), FRAME); return true; }),
                new Step("firstCommitted", 40, this::committed),
                new Step("firstReady", 600, this::firstPassageReady),
                new Step("endedByPowerLoss", 60, this::endedByPowerLoss),
                new Step("repowered", 200, () -> M5GameTestSupport.registeredIdle(source, FRAME) && M5GameTestSupport.controller(source, FRAME).wasPowered()));
    }

    /** DORMANT 的 EXISTING 收據（P 已返還、沒有 lease），候選是 NEW 那一段建立的 U。只建立，不寫入。 */
    private SessionRecoveryRecord existingReceiptForTheUniverse() {
        return M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), Map.of(participant, true), universe);
    }

    /** NEW 那一段到 PASSAGE_READY：U 的投影 COMPLETE；之後拿掉 Controller 上方的紅石塊（斷電）。 */
    private boolean firstPassageReady() throws Exception {
        var record = journal.flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.PASSAGE_READY) return false;
        expect("firstProjectionComplete", M5CollapseDiskEvidence.projections(data()).equals(List.of(projection())), () -> "projection ledger=" + projectionsOrError());
        source.setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
        return true;
    }

    /** 通道以 (d) 來源艙沒有供電結束（record 已移除）之後，P 移到艙外，再放回紅石塊恢復供電（P 不在艙內，不會開始新的疊加）。 */
    private boolean endedByPowerLoss() {
        if (journal.flushedRecords().containsKey(sid) || journal.records().containsKey(sid)) return false;
        expect("firstPassageEndedByPowerLoss", capture.matching(Level.INFO, "量子通道已結束", "(d) 來源艙沒有供電", sid.toString()).size() == 1,
                () -> "沒有恰一則 (d) 來源艙沒有供電的結束 INFO");
        M5GameTestSupport.moveOutside(participantPlayer(), FRAME);
        source.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        return true;
    }

    /** U 的投影（COMPLETE）改壞一格 shell（授權寫入玻璃），interior 放金塊標記（interior 不受保護，一般寫入）。投影的 chunk 以 probe 的 ticket 載入。 */
    private void damageProjection() {
        var world = universeWorld().orElseThrow(() -> new AssertionError("universeActive：EXISTING 收據之前 U 不是 ACTIVE"));
        holdProjectionChunk(world);
        expect("projectionIntactBeforeDamage", world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK) && world.getBlockState(interiorMarker()).isAir(),
                () -> "shell=" + world.getBlockState(damagedShell()) + " interior=" + world.getBlockState(interiorMarker()));
        expect("shellProtectedBeforeDamage", !ChamberProtectionService.get().mayMutate(world, damagedShell()), () -> "COMPLETE 投影的 shell 不受保護");
        ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(damagedShell(), Blocks.GLASS.getDefaultState(), 3));
        expect("shellDamaged", world.getBlockState(damagedShell()).isOf(Blocks.GLASS), () -> "shell=" + world.getBlockState(damagedShell()));
        expect("interiorMarkerPlaced", world.setBlockState(interiorMarker(), Blocks.GOLD_BLOCK.getDefaultState(), 3), () -> "interior 的標記放不上去");
        proof.put("damagedShell", damagedShell().toShortString());
        proof.put("interiorMarker", interiorMarker().toShortString());
    }

    // ---- live session 的 crash／restart（W1） ----

    /**
     * crash phase（W1）：fresh 世界蓋原艙、連線參與者 P 並移到艙內 → 原艙註冊之後以真實的 LOW→HIGH 供電與 Buff 啟動（比照 M4 recovery probe 的 build／arm）→
     * SUPERPOSITION、358 扇側門發布、P 在走廊 → 原生存檔（P 的 playerdata 存在走廊）→ P 以原生互動選一扇 NEW 候選門（checked SELECTED，MEASURED）、寫出
     * {@link #RECEIPT_NBT}、裝上 crash 點 → 下一個 tick 塌縮流程在 operation guard 之內提交 MEASURED→COLLAPSING，提交讀回確認時 halt（返還與清理都還沒有開始）。
     */
    private Phase liveCrash() {
        return new Phase(Ending.HALT, this::requireFreshWorld, this::crashPointHealthy, List.of(
                new Step("build", 1, () -> {
                    buildOrigin();
                    M5GameTestSupport.moveInside(participantPlayer(), FRAME);
                    return true;
                }),
                new Step("armed", 200, this::armed),
                new Step("superposition", 400, this::superposition),
                new Step("selected", 1, this::selectNewDoor),
                new Step("halt", 400, () -> false)));
    }

    /**
     * 原艙註冊之後，比照 M4 recovery probe 的 arm：拿掉紅石塊（LOW）、給 P Buff，readiness 通過之後存 P 的 checkpoint 與原生存檔，再放回紅石塊（HIGH）→ 必須立即有
     * checked ARMING。readiness 還沒有通過時下一個 tick 再試。
     */
    private boolean armed() throws Exception {
        if (!M5GameTestSupport.registeredIdle(source, FRAME)) return false;
        var controller = M5GameTestSupport.controller(source, FRAME);
        chamber = controller.chamberUuid();
        var player = participantPlayer();
        source.setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
        if (!player.hasStatusEffect(ModEffects.QUANTUM_STATE)) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
        if (!new ChamberActivationService().evaluateReadiness(source, controller).accepted()) return false;
        PlayerCheckpointStore.saveAndVerify(server, player, Optional.empty());
        expect("nativeSaveBeforeArming", server.save(false, true, true), () -> "原生存檔失敗");
        source.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        var arming = journal.flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(chamber)).toList();
        expect("armingFlushed", arming.size() == 1 && arming.getFirst().state() == SessionState.ARMING,
                () -> "供電之後這座艙的 flushed record：" + arming.stream().map(record -> record.state().name()).toList());
        sid = arming.getFirst().sessionUuid();
        return true;
    }

    /** SUPERPOSITION、358 扇側門都已發布、P 在走廊。 */
    private boolean superposition() {
        var record = journal.flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.SUPERPOSITION || pages().selectableDoors(sid).size() != 358) return false;
        return participantPlayer().getServerWorld() == corridor();
    }

    /** 選門之前原生存檔（P 的 playerdata 存在走廊）；P 以原生互動選一扇 NEW 候選門，checked SELECTED 之後寫出收據、裝上 W1 的 crash 點。 */
    private boolean selectNewDoor() throws Exception {
        expect("nativeSaveInTheCorridor", server.saveAll(false, true, true), () -> "saveAll 回 false");
        var door = M5GameTestSupport.candidateDoor(pages(), corridor(), sid, QuantumCandidate.New.class);
        var result = M5GameTestSupport.interact(corridor(), door.cell(), participantPlayer());
        var measured = journal.flushedRecords().get(sid);
        expect("newDoorSelected", result == ActionResult.SUCCESS && measured != null && measured.state() == SessionState.MEASURED
                && measured.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New, () -> "選門結果 " + result + "，record=" + M5GameTestSupport.state(journal, sid));
        expect("notReturnedOnSelection", !pages().measuredRecoveryRequested(sid), () -> "正常測量不應立即返還");
        adopt(measured);
        recordReceipt();
        armWindow(CrashWindow.COLLAPSING_COMMITTED);
        return true;
    }

    /**
     * restart phase（W1）：讀回 crash phase 的收據，開服時斷言還沒有返還、走廊 space 已重建、沒有保留式 recovery 的請求、RESOLVE 還沒有開始（{@link #restartStarted}）→
     * P 真 JOIN（在走廊、被 recovery 攔截）→ 不經請求返還到來源世界 → 清 lease、space 釋放（SETTLED）→ RESOLVE → PASSAGE_READY → 跨越 → (b) 終局。
     */
    private Phase liveRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(
                new Step("join", 1, this::joinIntoTheCorridor),
                new Step("returned", 200, this::returnedFromTheCorridor),
                new Step("settled", 200, this::settled)));
        steps.addAll(passageSteps());
        return new Phase(Ending.STOP, () -> restartStarted(CrashWindow.COLLAPSING_COMMITTED, crashPhase), this::restartInvariants, steps);
    }

    private boolean joinIntoTheCorridor() {
        var connection = connect(new GameProfile(participant, "m5-" + participant.toString().substring(0, 8)));
        var player = connection.player();
        expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
        expect("joinedInTheCorridor", player.getServerWorld() == corridor(), () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
        expect("participantBlockedUntilReturned", SessionRecoveryManager.blocks(participant, server), () -> "尚未返還的參與者沒有被 recovery 攔截");
        joinedAt = ticks;
        return true;
    }

    /** P 回到來源世界，flushed record 記為已返還；record 仍是 COLLAPSING，沒有經過保留式 recovery 的請求。 */
    private boolean returnedFromTheCorridor() {
        var player = participantPlayer();
        var record = journal.flushedRecords().get(sid);
        if (player.getServerWorld() != source || record == null || record.participants().stream().anyMatch(person -> !person.returned())) return false;
        expect("returnedWhileCollapsing", record.state() == SessionState.COLLAPSING, () -> "state=" + record.state());
        expect("returnedWithoutRequest", !pages().measuredRecoveryRequested(sid), () -> "COLLAPSING 的返還經過了 requestMeasuredRecovery");
        expect("participantUnblocked", !SessionRecoveryManager.blocks(participant, server), () -> "已返還的參與者仍被 recovery 攔截");
        proof.put("returnedInsideTheChamber", inside(player));
        returned = true;
        return true;
    }

    /** 清 lease、走廊 space 釋放（{@code measuredRecoveryComplete}：SETTLED）；在那之前 record 一直是 COLLAPSING。 */
    private boolean settled() {
        var record = journal.flushedRecords().get(sid);
        expect("collapsingUntilSettled", record != null && record.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(journal, sid));
        if (!pages().measuredRecoveryComplete(sid)) return false;
        expect("leaseCleared", record.spaceLeases().isEmpty() && MeasuredRecoveryPhase.from(record) == MeasuredRecoveryPhase.SETTLED && !spaceExists(),
                () -> "leases=" + record.spaceLeases() + " phase=" + MeasuredRecoveryPhase.from(record));
        return true;
    }

    // ---- 開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本） ----

    /**
     * setup phase（正常停止，不是 crash 窗口）：EXISTING 再造訪的前半段（{@link #firstPassageSteps}：U 的投影 COMPLETE）→ U 的投影 interior 放金塊標記 → EXISTING 收據、
     * 原生存檔 → P 由艙外進入 → 看到 flushed COLLAPSING 的同一個 tick 正常停服（RESOLVE 在下一個 tick，所以停服時還沒有 RESOLVE）。
     */
    private Phase degradedSetup() {
        var steps = new ArrayList<>(firstPassageSteps());
        steps.addAll(List.of(
                new Step("existingReceipt", 1, () -> {
                    placeInteriorMarker();
                    writeCrashReceipt(existingReceiptForTheUniverse());
                    return true;
                }),
                new Step("enter", 1, () -> { M5GameTestSupport.moveInside(participantPlayer(), FRAME); return true; }),
                new Step("committed", 40, () -> {
                    if (!committed()) return false;
                    proof.put("diskAtStop", M5CollapseDiskEvidence.summary(saveRoot(), sid));
                    return true;
                })));
        return new Phase(Ending.STOP, this::requireFreshWorld, NOTHING, steps);
    }

    /** U 的投影（COMPLETE）interior 放金塊標記（interior 不受保護，一般寫入）。投影的 chunk 以 probe 的 ticket 載入。 */
    private void placeInteriorMarker() {
        var world = universeWorld().orElseThrow(() -> new AssertionError("universeActive：EXISTING 收據之前 U 不是 ACTIVE"));
        holdProjectionChunk(world);
        expect("interiorEmptyBeforeMarker", world.getBlockState(interiorMarker()).isAir(), () -> "interior=" + world.getBlockState(interiorMarker()));
        expect("interiorMarkerPlaced", world.setBlockState(interiorMarker(), Blocks.GOLD_BLOCK.getDefaultState(), 3), () -> "interior 的標記放不上去");
        proof.put("interiorMarker", interiorMarker().toShortString());
    }

    /**
     * restart phase（退化）：開服前（{@code onInitialize}）把 projection ledger 移到 evidence → 開服時斷言初始化判定 LOST（{@code AUTHORITY_CORRUPT}）、本 instance 不提交新的
     * COLLAPSING、投影保護索引退化、record 仍是 COLLAPSING 且候選不變，以 probe 的 ticket 載入投影 chunk → 磁碟上的 Controller 走 foreign 的分支 → RESOLVE 進
     * SEALED(AUTHORITY_CORRUPT)，interior 沒有被重寫、ledger 沒有被重建 → P 真 JOIN（在艙內：setup 停服時 P 在艙內）→ P 從艙內照一般方式開門（沒有人被困在封存的艙裡）。
     * 停服之後再確認 ledger 沒有被重建。
     */
    private Phase degradedRestart(String setupPhase) {
        return new Phase(Ending.STOP, this::removeProjectionLedgerBeforeStart, () -> degradedStarted(setupPhase), this::restartInvariants, List.of(
                new Step("foreign", 60, this::projectionControllerForeign),
                new Step("sealed", 400, this::sealedAsCorrupt),
                new Step("join", 1, () -> { joinParticipant(true); return true; }),
                new Step("doorOpens", 1, this::doorOpensNormally)),
                () -> expect("ledgerNotRecreatedAfterStop", !Files.exists(worldData().resolve(ProjectionLedger.FILE_NAME)), () -> "停服之後 projection ledger 被重建"));
    }

    /** 開服前：先開始收 {@code quantumchamber} 的 log（初始化的 ERROR 在 {@code SERVER_STARTED} 的 default phase），再把 projection ledger 移到這個 phase 的 evidence。 */
    private void removeProjectionLedgerBeforeStart() throws Exception {
        startupLog = new LogCapture("quantumchamber");
        var ledger = worldData().resolve(ProjectionLedger.FILE_NAME);
        expect("projectionLedgerOnDiskBeforeStart", Files.isRegularFile(ledger), () -> "開服前沒有 projection ledger：" + ledger);
        var moved = config.evidence().resolve("moved-" + ProjectionLedger.FILE_NAME);
        var sha256 = M5CollapseDiskEvidence.sha256(ledger);
        Files.move(ledger, moved);
        expect("projectionLedgerMovedBeforeStart", !Files.exists(ledger) && sha256.equals(M5CollapseDiskEvidence.sha256(moved)), () -> "projection ledger 沒有移走：" + ledger);
        proof.put("movedProjectionLedger", Map.of("from", ledger.toString(), "to", moved.toString(), "sha256", sha256));
    }

    private void degradedStarted(String setupPhase) throws Exception {
        readCrashReceipt(setupPhase, null);
        var initErrors = startupLog.matching(Level.ERROR, "M5 authority 初始化失敗").stream().map(LogCapture.Entry::message).toList();
        expect("initializationLost", initErrors.size() == 1 && initErrors.getFirst().contains("projection=LOST") && initErrors.getFirst().contains("allocation=LOADED"),
                () -> "初始化的 ERROR：" + initErrors);
        var authorities = CollapseAuthorities.get(server);
        expect("authorityCorrupt", !authorities.initialized()
                && authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER).equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "initialized=" + authorities.initialized() + " unhealthy=" + authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER));
        expect("noNewCollapsingThisInstance", !journal.m5ReadyThisInstance(), () -> "初始化失敗但 m5ReadyThisInstance 為真");
        expect("indexDegraded", ProjectionProtectionIndex.get(server).degradation().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "索引的退化=" + ProjectionProtectionIndex.get(server).degradation());
        var current = journal.flushedRecords().get(sid);
        expect("restartedCollapsing", current != null && current.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(journal, sid));
        expect("sameDestination", destination(current).equals(Optional.of(universe)), () -> "Destination=" + current.collapse().destination());
        expect("noRedraw", sameCandidate(receipt, current), () -> "候選 context、ledger 或 selection 與停服前不同");
        expect("oneAllocationForTheToken", M5CollapseDiskEvidence.allocations(data()).equals(List.of(allocation())), () -> "allocation ledger=" + allocationsOrError());
        var catalog = UniverseRegistryState.get(server).flushedRecords();
        expect("catalogUnchanged", catalog.size() == catalogBefore && eagerEnabled(catalog.get(universe)), () -> "catalog=" + catalog);
        expect("bootstrapMaterializedEagerEnabled", universeWorld().isPresent(), () -> "開服 bootstrap 沒有物化 U");
        expect("alreadyDiscovered", M5CollapseDiskEvidence.discovery(saveRoot()).stream().anyMatch(record -> record.universeId().equals(universe)), () -> "discovery 沒有 U");
        expect("ledgerMissingAtStart", !Files.exists(data().resolve(ProjectionLedger.FILE_NAME)), () -> "開服時 projection ledger 存在（被重建）");
        var world = universeWorld().orElseThrow();
        var marker = diskBlock(world, interiorMarker());
        expect("interiorMarkerOnDisk", GOLD_BLOCK.equals(marker.name()), () -> "磁碟上的 interior 標記是 " + marker);
        var controller = diskBlock(world, FRAME.controllerPos());
        expect("controllerOnDiskAtRestart", controllerBlock(controller), () -> "磁碟上的 Controller 位置是 " + controller);
        diskInstanceKind = M5CollapseDiskEvidence.blockEntityString(dimensionDirectory(world), FRAME.controllerPos(), "InstanceKind");
        proof.put("projectionControllerKindOnDisk", diskInstanceKind);
        proof.put("startedDisk", M5CollapseDiskEvidence.summary(saveRoot(), sid));
        loadProjection();
    }

    /**
     * 退化時投影保護索引不認得磁碟上的 Controller：load-sync 處理（CONSUMED、refresh 過）之後走動態世界的 foreign 分支（{@code ChamberPowerCoordinator} 的
     * 「不是投影、不是原生世界」：INVALID、比較器 0、沒有 chamber UUID），instanceKind 不寫回，維持磁碟上的值。setup 時這個 Controller 已 refresh 過並存檔，磁碟上
     * 已是 PROJECTION、INVALID，所以 block entity 的結果與投影的分支相同，兩者的區別是索引不認得它（{@code projectionControllerAt} 為假）。退化的保護另加同位置的
     * 不變量 cell。
     */
    private boolean projectionControllerForeign() {
        var world = universeWorld().orElseThrow();
        var controller = projectionController;
        if (!loadSync.outcomes().contains(ChamberLoadSyncOutcome.CONSUMED) || !controller.powerInitialized()) return false;
        var pos = FRAME.controllerPos();
        expect("indexDoesNotKnowTheController", !ProjectionProtectionIndex.projectionControllerAt(server, world.getRegistryKey(), pos), () -> "退化的索引認得這個 Controller");
        expect("foreignBranch", !controller.isRemoved() && world.getBlockEntity(pos) == controller && controller.chamberState() == ChamberState.INVALID
                && controller.chamberUuid() == null && world.getBlockState(pos).getComparatorOutput(world, pos) == 0
                && controller.instanceKind().name().equals(diskInstanceKind),
                () -> "Controller：state=" + controller.chamberState() + " uuid=" + controller.chamberUuid() + " kind=" + controller.instanceKind() + "（磁碟上 " + diskInstanceKind + "）");
        // spec §9.3 的退化：保護另加登記中的 Origin 艙在每個動態 Overworld 同位置的不變量 cell（Controller、shell、門），interior 不受保護。
        var protection = ChamberProtectionService.get();
        expect("degradedIndexProtectsTheSite", !protection.mayMutate(world, pos) && !protection.mayMutate(world, local(0, 0, 0))
                && !protection.mayMutate(world, local(3, 3, 0)) && protection.mayMutate(world, local(3, 3, 3)), () -> "退化時同位置的 Controller、shell、門應受保護，interior 不應受保護");
        proof.put("loadSyncOutcomes", loadSync.outcomes().stream().map(Enum::name).toList());
        closeLoadSync();
        return true;
    }

    /** RESOLVE 進 SEALED(AUTHORITY_CORRUPT)：永久原因的 ERROR 一則、沒有 PREPARE、interior 沒有被重寫（記憶體與磁碟）、ledger 沒有被重建、原艙門關著。 */
    private boolean sealedAsCorrupt() throws Exception {
        var record = journal.flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.SEALED) return false;
        expect("sealedAuthorityCorrupt", record.collapse().sealedReason().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)) && record.equals(journal.records().get(sid)),
                () -> "SEALED 的原因=" + record.collapse().sealedReason());
        var errors = capture.matching(Level.ERROR, sid.toString()).stream().map(LogCapture.Entry::message).toList();
        expect("onePermanentAdminError", errors.size() == 1 && errors.getFirst().contains("SEALED(AUTHORITY_CORRUPT)") && errors.getFirst().contains("需要管理員處理"),
                () -> "這個 session 的 ERROR：" + errors);
        expect("neverPrepared", capture.matching(Level.INFO, "準備完成", sid.toString()).isEmpty(), () -> "退化之後仍有「準備完成」");
        var world = universeWorld().orElseThrow();
        var marker = diskBlock(world, interiorMarker());
        expect("interiorNotRewritten", world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK) && GOLD_BLOCK.equals(marker.name()),
                () -> "interior 標記：記憶體 " + world.getBlockState(interiorMarker()) + "，磁碟 " + marker);
        expect("ledgerNotRecreated", !Files.exists(data().resolve(ProjectionLedger.FILE_NAME)), () -> "projection ledger 被重建");
        expect("doorClosedOnSeal", M5GameTestSupport.doorAll(source, FRAME, false, MembraneState.NONE), () -> "SEALED 之後原艙門不是關著");
        return true;
    }

    /** SEALED 之後 P 從艙內照一般方式開門（spec §11.2）；record 仍是 SEALED(AUTHORITY_CORRUPT)，spec §12 的四件事（退化版：沒有 projection entry）。 */
    private boolean doorOpensNormally() throws Exception {
        var player = participantPlayer();
        var result = M5GameTestSupport.interact(source, local(3, 3, 0), player);
        expect("doorOpensNormally", result.isAccepted() && M5GameTestSupport.doorAll(source, FRAME, true, MembraneState.NONE), () -> "互動結果 " + result);
        var record = journal.flushedRecords().get(sid);
        expect("stillSealed", record != null && record.state() == SessionState.SEALED && record.collapse().sealedReason().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "state=" + M5GameTestSupport.state(journal, sid));
        var catalog = UniverseRegistryState.get(server).flushedRecords();
        expect("catalogUnchanged", catalog.size() == catalogBefore && eagerEnabled(catalog.get(universe)), () -> "catalog=" + catalog);
        expect("oneAllocationForTheToken", M5CollapseDiskEvidence.allocations(data()).equals(List.of(allocation())), () -> "allocation ledger=" + allocationsOrError());
        var discovery = M5CollapseDiskEvidence.discovery(saveRoot());
        expect("discoveredOnce", discovery.size() == 1 && discovery.getFirst().universeId().equals(universe), () -> "discovery=" + discovery);
        expect("ledgerNotRecreated", !Files.exists(data().resolve(ProjectionLedger.FILE_NAME)), () -> "projection ledger 被重建");
        expect("participantInTheSourceWorld", player.getServerWorld() == source, () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue());
        proof.put("finalDisk", M5CollapseDiskEvidence.summary(saveRoot(), sid));
        return true;
    }

    // ---- 共用 ----

    /** 以 probe 的 ticket（{@link ProbeTicket}，持有到 JVM 結束）載入投影所在的 chunk。 */
    private void holdProjectionChunk(ServerWorld world) {
        var chunk = new ChunkPos(FRAME.controllerPos());
        world.getChunkManager().addTicket(ProbeTicket.TYPE, chunk, 0, chunk);
        world.getChunk(chunk.x, chunk.z);
    }

    private CorridorPageManager pages() { return CorridorPageManager.forServer(server); }

    private ServerWorld corridor() { return server.getWorld(SuperpositionWorld.KEY); }

    /** 這個 session 有走廊 space（{@code currentMappings} 對沒有 space 的 session 丟 {@link IllegalArgumentException}）。 */
    private boolean spaceExists() {
        try {
            pages().currentMappings(sid);
            return true;
        } catch (IllegalArgumentException missing) {
            return false;
        }
    }

    /** {@code world} 的存檔目錄（raw MCA 的上層）。 */
    private Path dimensionDirectory(ServerWorld world) {
        return DimensionType.getSaveDirectory(world.getRegistryKey(), saveRoot());
    }

    /** 開服前就能用的 {@code world/data}（root 是 cwd，level-name 由 harness 固定為 {@code world}）。 */
    private Path worldData() { return config.root().resolve("world").resolve("data"); }

    /**
     * probe 自己的 chunk ticket（level FULL、不持久化；只在需要時建立，probe 關閉時不碰 ChunkTicketType）。加上之後一直持有到 JVM 結束，不移除：
     * 開發時在 W6 的 restart 於 load-sync 之後移除，之後 PREPARE 的預載又以 production 的 ticket 載入同一個 chunk，而這個 phase 沒有任何 flush 存檔（修復 0 格，
     * 不做 checkpoint）；停服時原版的 {@code MinecraftServer.shutdown → ServerChunkLoadingManager.unloadChunks} 每次都忙等卡住（半徑 2 與 0 都是），不加 ticket 時則
     * 正常停服。持有到底就沒有這個卸載再載入的循環，也不影響 production 的判斷（PREPARE 只以 {@code getWorldChunk} 查 FULL）。
     */
    private static final class ProbeTicket {
        static final ChunkTicketType<ChunkPos> TYPE = ChunkTicketType.create("quantumchamber_m5_collapse_probe", Comparator.comparingLong(ChunkPos::toLong));
    }

    private static final String BEDROCK = "minecraft:bedrock";
    private static final String GLASS = "minecraft:glass";
    private static final String GOLD_BLOCK = "minecraft:gold_block";

    /** 原艙（與 U 的投影，同 XYZ、同朝向）在區域座標 (x, y, z) 的世界座標。 */
    private static BlockPos local(int x, int y, int z) {
        return ChamberGeometry.localToWorld(FRAME, x, y, z);
    }

    /** W6x 改壞的 shell cell：區域 (6,3,3)（側牆，不是門也不是 Controller）。 */
    private static BlockPos damagedShell() { return local(6, 3, 3); }

    /** W6x 的 interior 標記：區域 (1,1,5)（interior 的角落）。 */
    private static BlockPos interiorMarker() { return local(1, 1, 5); }

    private boolean isUniverse(ServerWorld world) {
        return universeWorld().filter(active -> active == world).isPresent();
    }

    /** {@code world} 在 {@code pos} 的方塊，以 raw MCA 從磁碟讀（{@link M5CollapseDiskEvidence#block}）。 */
    private DiskBlock diskBlock(ServerWorld world, BlockPos pos) throws IOException {
        return M5CollapseDiskEvidence.block(DimensionType.getSaveDirectory(world.getRegistryKey(), saveRoot()), pos);
    }

    /** 磁碟上這一格是 Controller，而且有 Controller 的 block entity。 */
    private static boolean controllerBlock(DiskBlock disk) {
        return Registries.BLOCK.getId(ModBlocks.CHAMBER_CONTROLLER).toString().equals(disk.name())
                && String.valueOf(Registries.BLOCK_ENTITY_TYPE.getId(ModBlockEntities.CHAMBER_CONTROLLER)).equals(disk.blockEntity());
    }

    /** inert 的 PROJECTION（比照 {@code M5UniverseGameTests}）：仍在世界上、PROJECTION、INVALID、比較器 0、沒有 chamber UUID。 */
    private static boolean inertProjection(ServerWorld world, ChamberControllerBlockEntity controller) {
        return !controller.isRemoved() && world.getBlockEntity(controller.getPos()) == controller && controller.instanceKind() == ChamberInstanceKind.PROJECTION
                && controller.chamberState() == ChamberState.INVALID && controller.chamberUuid() == null
                && world.getBlockState(controller.getPos()).getComparatorOutput(world, controller.getPos()) == 0;
    }

    private ConnectedGameTestPlayer connect(GameProfile profile) {
        var connection = new ConnectedGameTestPlayer(source, profile);
        players.add(connection);
        connection.confirmTeleport();
        connection.player().setNoGravity(true);
        return connection;
    }

    private ServerPlayerEntity onlinePlayer() {
        return participant == null ? null : server.getPlayerManager().getPlayer(participant);
    }

    private ServerPlayerEntity participantPlayer() {
        var player = onlinePlayer();
        if (player == null) throw new AssertionError("participantOnline：參與者 " + participant + " 不在線上");
        return player;
    }

    private Optional<ServerWorld> universeWorld() {
        return UniverseLifecycleService.resolveActive(server, universe);
    }

    private Allocation allocation() {
        return new Allocation(HexFormat.of().formatHex(token.bytes()), universe.value());
    }

    private Projection projection() {
        return projection("COMPLETE");
    }

    /** 這座艙在 U 的 projection entry，狀態為 {@code status}（PENDING 或 COMPLETE）。 */
    private Projection projection(String status) {
        return new Projection(chamber, universe.value(), status);
    }

    private String allocationsOrError() {
        try { return M5CollapseDiskEvidence.allocations(data()).toString(); } catch (IOException | RuntimeException failure) { return "UNREADABLE: " + failure; }
    }

    private String projectionsOrError() {
        try { return M5CollapseDiskEvidence.projections(data()).toString(); } catch (IOException | RuntimeException failure) { return "UNREADABLE: " + failure; }
    }

    /** 參與者在艙內（區域座標 1..6 的內部空間）。 */
    private static boolean inside(ServerPlayerEntity player) {
        var local = ChamberSpaceCoordinates.localPosition(FRAME, player.getPos());
        return local.x >= 1 && local.x < 6 && local.y >= 1 && local.y < 6 && local.z >= 1 && local.z < 6;
    }

    /** 來源 Controller 目前是 {@code state}，而且比較器輸出是 {@code signal}（spec §10.7）。 */
    private boolean controllerSignal(ChamberState state, int signal) {
        var pos = FRAME.controllerPos();
        return source.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller && controller.chamberState() == state
                && source.getBlockState(pos).getComparatorOutput(source, pos) == signal;
    }

    private static Optional<UniverseId> destination(SessionRecoveryRecord record) {
        return record != null && record.collapse().destination() instanceof CollapseDestination.Universe target ? Optional.of(target.id()) : Optional.empty();
    }

    private static boolean eagerEnabled(UniverseRecord record) {
        return record != null && record.desiredAvailability() == DesiredAvailability.EAGER_ENABLED;
    }

    /** 沒有重抽：session、原艙、ORIGIN、參與者與候選的 context、ledger、selection 都與 crash 前的收據 exact 相同。 */
    private static boolean sameCandidate(SessionRecoveryRecord before, SessionRecoveryRecord after) {
        return after != null && before.sessionUuid().equals(after.sessionUuid()) && before.chamberUuid().equals(after.chamberUuid())
                && before.origin().equals(after.origin())
                && before.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList()
                        .equals(after.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList())
                && before.candidateContext().equals(after.candidateContext()) && before.candidateLedger().equals(after.candidateLedger())
                && before.candidateSelection().equals(after.candidateSelection());
    }

    private String diagnostic() {
        try {
            var point = crashPoint;
            return "step=" + (phase == null || stepIndex >= phase.steps().size() ? "-" : phase.steps().get(stepIndex).name()) + " session=" + sid
                    + " state=" + (sid == null ? "-" : M5GameTestSupport.state(journal, sid)) + " catalog=" + UniverseRegistryState.get(server).records().keySet()
                    + " crash=" + (point == null ? "-" : point.window + " reached=" + point.reached() + " hits=" + point.hits());
        } catch (RuntimeException failure) {
            return "（診斷失敗：" + failure + "）";
        }
    }

    /** 具名斷言：成立時記在 {@code observed}；不成立時丟出以名稱開頭的 AssertionError。 */
    private void expect(String name, boolean condition, Supplier<String> detail) {
        if (!condition) throw new AssertionError(name + "：" + detail.get());
        observed.put(name, true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private Path saveRoot() { return server.getSavePath(WorldSavePath.ROOT); }

    private Path data() { return saveRoot().resolve("data"); }

    private void event(String name) {
        try {
            Files.writeString(config.evidence().resolve("events.jsonl"), JSON.toJson(Map.of("event", name, "timeUtc", Instant.now().toString(),
                    "startupNonce", config.startupNonce(), "pid", ProcessHandle.current().pid())) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /** CREATE_NEW 寫出並 {@code force}（halt 之前一定落盤；已存在時丟出，不覆寫）。 */
    private static void writeDurably(Path path, String text) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    /** probe 的設定（system property {@code quantumchamber.m5collapse.*}，由 build.gradle 的 m5Collapse run 從 {@code -Pm5Collapse*} 轉入）。 */
    record Configuration(String phase, String nonce, String startupNonce, Path root, Path evidence) {
        /** 沒有 phase 屬性時回 null（probe 關閉）；有但不合法時丟出。 */
        static Configuration read() {
            String prefix = "quantumchamber.m5collapse.";
            String phase = System.getProperty(prefix + "phase");
            if (phase == null) return null;
            String nonce = System.getProperty(prefix + "nonce", ""), startup = System.getProperty(prefix + "startupNonce", "");
            String supplied = System.getProperty(prefix + "root", "");
            if (!PHASES.containsKey(phase) || !nonce.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || nonce.equals("disabled") || !startup.matches("[0-9a-f]{32}") || supplied.isBlank()) {
                throw new IllegalStateException("M5 collapse probe 的設定不合法：phase=" + phase + " nonce=" + nonce + " startupNonce=" + startup + " root=" + supplied);
            }
            try {
                var given = Path.of(supplied);
                var root = given.toRealPath();
                if (!given.isAbsolute() || !root.equals(given.normalize()) || !root.equals(Path.of("").toRealPath())
                        || !root.getFileName().toString().equals("m5-collapse-" + nonce) || !root.getParent().getFileName().toString().equals("run")) {
                    throw new IllegalStateException("M5 collapse probe 的 root 必須是 cwd 的 run/m5-collapse-" + nonce + "：" + supplied);
                }
                var evidence = root.getParent().getParent().resolve(OWNER).resolve("collapse-" + nonce).resolve(phase);
                if (!evidence.toRealPath().equals(evidence)) throw new IllegalStateException("M5 collapse probe 的 evidence 必須是 canonical 路徑：" + evidence);
                for (var existing : List.of("final.json", "halt.json", "events.jsonl", RECEIPT_NBT)) {
                    if (Files.exists(evidence.resolve(existing))) throw new IllegalStateException("phase evidence 已有 " + existing + "，禁止重跑：" + evidence);
                }
                return new Configuration(phase, nonce, startup, root, evidence);
            } catch (IOException failure) {
                throw new UncheckedIOException("M5 collapse probe 的 root 或 evidence 不存在：" + supplied, failure);
            }
        }
    }
}
