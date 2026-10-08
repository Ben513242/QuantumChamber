package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Allocation;
import dev.quantumchamber.gametest.M5CollapseDiskEvidence.Projection;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
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
 * <p>phase 以 {@link #PHASES} 表示：名稱 → {@link PhaseFactory}。factory 產生一個 {@link Phase}：開服時（{@code SERVER_STARTED}）的檢查、每個 tick 的不變量，
 * 與依序執行的 {@link Step}（每個 step 有上限 tick，整個 phase 另以 {@link #PHASE_TICK_LIMIT} 為界）。新增 phase 只要在 {@link #phases} 加 entry，並在
 * {@code m5-collapse-probe.ps1} 的 chain 表加上同名的 phase 與預期的結束（{@code scripts/verification/README.md}「M5 collapse probe」）。phase 的結束有兩種
 * （{@link Ending}）：
 * <ul>
 * <li>{@code STOP}：全部 step 完成之後正常停止，{@code SERVER_STOPPED} 時寫 {@code final.json}（status PASS 或 FAIL）。</li>
 * <li>{@code HALT}：在指定的 crash 窗口（{@link CrashWindow}，{@link M5TestHooks#CRASH_POINT}）寫出 {@code halt.json}（CREATE_NEW＋{@code force}）之後以
 *     {@code Runtime.halt(77)} 結束 JVM：不經正常停止、不存檔，等同 crash。窗口沒有到達、或任何斷言失敗時，照常停止並寫 FAIL 的 {@code final.json}。</li>
 * </ul>
 * 斷言失敗的訊息以斷言名稱開頭（例如 {@code catalogOnlyDestination}），記在 receipt 的 {@code failures}，之後正常停止；例外不會從 {@code END_SERVER_TICK}
 * 拋出。各 restart phase 的共同斷言是 spec §12 的四件事：同一個 UniverseId、catalog 最多只多 1 筆、沒有重抽、沒有人被困住。
 */
public final class M5CollapseRecoveryProbe implements ModInitializer {
    static final String OWNER = ".superpowers/sdd/2026-10-01-m5-collapse-passage";
    /** 預期 crash 的 exit code：{@code m5-collapse-probe.ps1} 以它分辨 crash 窗口與其他非零結束。 */
    static final int HALT_EXIT = 77;
    /** halt receipt 寫不出來時的 exit code（不是預期的 crash；harness 判 FAIL）。 */
    static final int HALT_RECEIPT_FAILED_EXIT = 78;
    /** 整個 phase 的上限 tick 數。 */
    static final int PHASE_TICK_LIMIT = 3600;
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

    // ---- phase 表 ----

    /** 一個 phase：開服時的檢查、每個 tick 的不變量（在 step 之前執行）與依序執行的 step；{@code ending} 是預期的結束方式（與 m5-collapse-probe.ps1 的 chain 表一致）。 */
    record Phase(Ending ending, Checked started, Checked everyTick, List<Step> steps) {
        Phase {
            steps = List.copyOf(steps);
            if (steps.isEmpty()) throw new IllegalArgumentException("phase 至少要有一個 step");
        }
    }

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
        return Collections.unmodifiableMap(table);
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
            phase = PHASES.get(config.phase()).create(this);
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
        for (var connection : players) {
            try { connection.close(); } catch (RuntimeException failure) { failures.add("closePlayer：" + failure); }
        }
        players.clear();
    }

    private void stopped(MinecraftServer owner) {
        if (owner != server) return;
        proof.put("stoppedSeen", true);
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

    /** 裝上 crash 點：{@code window} 回傳 {@code APPENDED} 的第一次就寫 halt receipt、halt。 */
    private void armCrash(CrashWindow window) {
        var point = new M5TestHooks.CrashPoint(window, M5CollapseRecoveryProbe::appended, (detail, hit) -> halt(window, detail, hit));
        M5TestHooks.CRASH_POINT.install(point, null);
        crashPoint = point;
        proof.put("crashWindow", window.name());
    }

    private static boolean appended(Object detail) {
        return detail instanceof CollapseAuthorities.Lookup<?> lookup && lookup.status() == CollapseAuthorities.Lookup.Status.APPENDED;
    }

    /** crash：寫出 {@code halt.json}（CREATE_NEW＋force）之後 {@code Runtime.halt}。receipt 寫不出來時以 {@link #HALT_RECEIPT_FAILED_EXIT} 結束。 */
    private void halt(CrashWindow window, Object detail, int hit) {
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
        if (detail instanceof CollapseAuthorities.Lookup<?> lookup) {
            return "status=" + lookup.status() + " value=" + lookup.value().map(String::valueOf).orElse("-");
        }
        return String.valueOf(detail);
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

    /** DORMANT 的 NEW 收據（P 已返還、沒有 lease）put＋flush，寫出 {@link #RECEIPT_NBT}，再原生存檔。 */
    private void writeDormantNewReceipt() throws Exception {
        var measured = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, FRAME.controllerPos()), Map.of(participant, true), List.of(), true);
        journal.put(measured);
        journal.flush(server);
        expect("receiptFlushed", measured.equals(journal.flushedRecords().get(measured.sessionUuid())), () -> "flushed 與收據不同");
        adopt(measured);
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
        evidence.put("catalogBefore", catalogBefore);
        evidence.put("receiptNbt", RECEIPT_NBT);
        evidence.put("receiptNbtSha256", M5CollapseDiskEvidence.sha256(file));
        evidence.put("candidateSha256", M5CollapseDiskEvidence.sha256(wrapped));
        proof.put("receipt", evidence);
        expect("nativeSaveBeforeTrigger", server.saveAll(false, true, true), () -> "saveAll 回 false");
    }

    private void adopt(SessionRecoveryRecord measured) {
        receipt = measured;
        sid = measured.sessionUuid();
        token = ((QuantumCandidate.New) measured.selectedCandidate().orElseThrow()).intent().allocationToken();
        universe = UniverseAllocationLedger.deriveUniverseId(token);
    }

    /**
     * restart phase 的開頭：讀回 crash phase（{@code crashPhase}）的 {@code halt.json} 與 {@link #RECEIPT_NBT}，核對 halt receipt 的 status、窗口、phase、nonce，
     * startupNonce 與這個 JVM 不同，receipt NBT 的 SHA-256 與 halt receipt 記的相同；再以收據設定這條 chain 的 fixture（session、原艙、參與者、UniverseId、
     * crash 前的 catalog 筆數）。回傳 halt receipt。
     */
    private JsonObject readCrashReceipt(String crashPhase, CrashWindow window) throws Exception {
        var crashEvidence = config.evidence().resolveSibling(crashPhase);
        var haltPath = crashEvidence.resolve("halt.json");
        expect("crashReceiptPresent", Files.exists(haltPath) && !Files.exists(crashEvidence.resolve("final.json")), () -> "crash phase 沒有 halt receipt：" + crashEvidence);
        JsonObject halt = JsonParser.parseString(Files.readString(haltPath)).getAsJsonObject();
        expect("crashReceiptMatches", "HALT".equals(text(halt, "status")) && window.name().equals(text(halt, "window")) && crashPhase.equals(text(halt, "phase"))
                && config.nonce().equals(text(halt, "nonce")), () -> "halt receipt 不符：status=" + text(halt, "status") + " window=" + text(halt, "window"));
        expect("differentStartupNonce", !config.startupNonce().equals(text(halt, "startupNonce")), () -> "與 crash phase 相同的 startupNonce");
        var noted = halt.getAsJsonObject("receipt");
        var receiptFile = crashEvidence.resolve(RECEIPT_NBT);
        expect("receiptNbtIntact", M5CollapseDiskEvidence.sha256(receiptFile).equals(text(noted, "receiptNbtSha256")), () -> "receipt NBT 的 SHA-256 與 halt receipt 不同");
        var decoded = SessionRecoveryState.fromNbt(NbtIo.readCompressed(receiptFile, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data"));
        decoded.requireHealthy();
        var measured = decoded.flushedRecords().get(UUID.fromString(text(noted, "sessionUuid")));
        expect("receiptDecoded", measured != null && measured.state() == SessionState.MEASURED, () -> "receipt NBT 裡沒有這個 session 的 MEASURED 收據");
        adopt(measured);
        chamber = measured.chamberUuid();
        participant = measured.participants().getFirst().playerUuid();
        catalogBefore = noted.get("catalogBefore").getAsInt();
        expect("receiptIdentity", universe.value().toString().equals(text(noted, "universeId")) && participant.toString().equals(text(noted, "participant")),
                () -> "receipt NBT 與 halt receipt 的 UniverseId 或參與者不同");
        proof.put("crashReceipt", Map.of("phase", crashPhase, "haltSha256", M5CollapseDiskEvidence.sha256(haltPath), "haltPid", halt.get("pid").getAsLong(),
                "haltStartupNonce", text(halt, "startupNonce"), "haltStartTimeUtc", text(halt, "startTimeUtc")));
        proof.put("universeId", universe.value().toString());
        return halt;
    }

    // ---- 共用 ----

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
        return new Projection(chamber, universe.value(), "COMPLETE");
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
