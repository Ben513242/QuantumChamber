package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryState;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：一個 phase 的 runner。phase 是 {@link Phase}：開服前（{@code onInitialize}）的準備、
 * 開服時（{@code SERVER_STARTED} 的 AFTER phase）的檢查、每個 tick 的不變量、依序執行的 {@link Step}（每個 step 有上限 tick，整個 phase 另以
 * {@link #PHASE_TICK_LIMIT} 為界），與停服之後（{@code SERVER_STOPPED}）的檢查。phase 的結束有兩種（{@link Ending}）：
 * <ul>
 * <li>{@code STOP}：全部 step 完成之後正常停止，{@code SERVER_STOPPED} 時寫 {@code final.json}（status PASS 或 FAIL）。</li>
 * <li>{@code HALT}：在指定的 crash 窗口（{@link CrashWindow}，{@link M5TestHooks#CRASH_POINT}）寫出 {@code halt.json}（CREATE_NEW＋{@code force}）之後以
 *     {@code Runtime.halt(77)} 結束 JVM：不經正常停止、不存檔，等同 crash。窗口沒有到達、或任何斷言失敗時，照常停止並寫 FAIL 的 {@code final.json}。</li>
 * </ul>
 * 斷言一律 {@link #expect}：失敗訊息以斷言名稱開頭（例如 {@code catalogOnlyDestination}），記在 receipt 的 {@code failures}，之後正常停止；成立的記在
 * {@code observed}。例外不會從 {@code END_SERVER_TICK} 拋出（{@link #guarded}）。probe 端安裝的 hook（crash 點與 fault，{@link #install}）在 {@link #stop}
 * 逐一移除。
 */
final class M5CollapseRun {
    static final String OWNER = ".superpowers/sdd/2026-10-01-m5-collapse-passage";
    /** 預期 crash 的 exit code：{@code m5-collapse-probe.ps1} 以它分辨 crash 窗口與其他非零結束。 */
    static final int HALT_EXIT = 77;
    /** halt receipt 寫不出來時的 exit code（不是預期的 crash；harness 判 FAIL）。 */
    static final int HALT_RECEIPT_FAILED_EXIT = 78;
    /** 整個 phase 的上限 tick 數。 */
    static final int PHASE_TICK_LIMIT = 3600;
    /** crash phase 寫出的 MEASURED 收據（單筆 journal 的 NBT），restart phase 以它比對「沒有重抽」。 */
    static final String RECEIPT_NBT = "receipt.nbt";
    static final Gson JSON = new Gson();
    static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m5-collapse");
    private static final Identifier AFTER = Identifier.of("quantumchamber:m5_collapse_after");
    static final Checked NOTHING = () -> { };

    // ---- phase 的型別 ----

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

    /** halt 之前的取證（或刻意的準備，例如 W4 的存檔）：寫進 halt receipt 的 {@code atHalt}。丟出時不 halt，crash 點記下失敗，phase 在下一個 tick FAIL。 */
    @FunctionalInterface interface HaltEvidence { void collect(Object detail, Map<String, Object> evidence) throws Exception; }

    /** chain 的 fixture 提供給 runner 的診斷與 halt 當下的事實。 */
    interface Context {
        /** 診斷訊息的 chain 部分（session、record 的狀態、catalog）。 */
        String diagnostic();

        /** halt 當下這條 chain 的 U 是否已物化（ACTIVE）。 */
        boolean universeActive();

        /** halt 當下磁碟上的 M5 權威摘要（{@link M5CollapseDiskEvidence#summary}）。 */
        Map<String, Object> diskSummary();
    }

    // ---- 狀態 ----

    final M5CollapseRecoveryProbe.Configuration config;
    final Map<String, Object> proof = new LinkedHashMap<>();
    final List<String> failures = new ArrayList<>();
    /** 已完成的 step：名稱 → 完成的 server tick 與花了幾個 tick。 */
    private final Map<String, Object> witness = new LinkedHashMap<>();
    /** 成立過的具名斷言（失敗的斷言直接丟出，不會留在這裡）。 */
    private final Map<String, Boolean> observed = new LinkedHashMap<>();
    /** probe 端安裝的 hook（crash 點與 fault），{@link #stop} 依安裝的相反順序移除。 */
    private final List<Installed<?>> installed = new ArrayList<>();
    /** {@code SERVER_STOPPING} 時依登記順序執行（load-sync 的觀察、參與者的連線）。 */
    private final List<Runnable> onStopping = new ArrayList<>();
    /** {@code SERVER_STOPPED} 寫出 {@code final.json} 之後依登記順序關閉（log 的收集）。 */
    private final List<AutoCloseable> onStopped = new ArrayList<>();
    private Context context;
    MinecraftServer server;
    ServerWorld source;
    SessionRecoveryState journal;
    LogCapture capture;
    private Phase phase;
    int ticks;
    private int stepIndex, stepTicks;
    private boolean finished, passed;
    private M5TestHooks.CrashPoint crashPoint;

    M5CollapseRun(M5CollapseRecoveryProbe.Configuration config) {
        this.config = config;
    }

    /** chain 的 fixture 在建構時登記（診斷與 halt 當下的事實）。 */
    void context(Context fixture) {
        context = fixture;
    }

    // ---- 生命週期 ----

    /** {@code onInitialize}：記下 JVM 的身分、建立 phase、執行開服前的準備，再註冊 listener。 */
    void start(M5CollapseRecoveryProbe.PhaseFactory factory) {
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
        phase = factory.create(this);
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
        for (var action : onStopping) action.run();
        onStopping.clear();
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
        for (var closeable : onStopped) {
            try {
                closeable.close();
            } catch (Exception failure) {
                LOGGER.error("M5 collapse probe：關閉失敗", failure);
            }
        }
    }

    /** 移除 probe 端安裝的全部 hook，再請求正常停服（只請求一次）。 */
    private void stop() {
        for (int index = installed.size() - 1; index >= 0; index--) installed.get(index).remove();
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

    /** {@code SERVER_STOPPING} 時執行 {@code action}（依登記順序）；{@code action} 自己收下失敗。 */
    void onStopping(Runnable action) {
        onStopping.add(action);
    }

    /** {@code SERVER_STOPPED} 寫出 {@code final.json} 之後關閉 {@code closeable}（依登記順序）。 */
    void onStopped(AutoCloseable closeable) {
        onStopped.add(closeable);
    }

    // ---- probe 端安裝的 hook ----

    /**
     * 把 {@code value} 裝進 {@code slot}（{@link M5TestHooks.Slot#install}：probe 不是 GameTest，沒有 {@code GameTestState}；被占用時丟出、不覆蓋），並記下來，
     * {@link #stop} 時依安裝的相反順序移除。crash 點（{@link #armCrash}）與 probe 內的 fault（例如 {@code new M5TestHooks.JournalPutFault(...)} 裝進
     * {@link M5TestHooks#JOURNAL_PUT_FAULT}）都經這裡。回傳 {@code value}。
     */
    <T> T install(M5TestHooks.Slot<T> slot, T value) {
        slot.install(value, null);
        installed.add(new Installed<>(slot, value));
        return value;
    }

    private record Installed<T>(M5TestHooks.Slot<T> slot, T value) {
        void remove() { slot.remove(value); }
    }

    // ---- crash 點與 halt receipt ----

    /** 裝上 crash 點：{@code window} 的 detail 符合 {@code condition} 的第一次，先 {@code evidence}，再寫 halt receipt、halt。 */
    void armCrash(CrashWindow window, Predicate<Object> condition, HaltEvidence evidence) {
        var point = new M5TestHooks.CrashPoint(window, condition, (detail, hit) -> {
            var atHalt = new LinkedHashMap<String, Object>();
            try {
                evidence.collect(detail, atHalt);
            } catch (Exception failure) {
                throw new IllegalStateException("haltEvidence：halt 之前的取證失敗：" + failure, failure);
            }
            halt(window, detail, hit, atHalt);
        });
        install(M5TestHooks.CRASH_POINT, point);
        crashPoint = point;
        proof.put("crashWindow", window.name());
    }

    /** 已裝上的 crash 點；還沒有裝時為 null。 */
    M5TestHooks.CrashPoint crashPoint() {
        return crashPoint;
    }

    /** crash phase 每個 tick 的不變量：crash 點的條件或動作沒有丟出過例外。 */
    void crashPointHealthy() {
        var point = crashPoint;
        if (point != null) expect("crashPointHealthy", point.failure() == null, () -> "crash 點失敗：" + point.failure());
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
            halt.put("detail", M5CollapseWindows.describe(detail));
            halt.put("haltServerTick", server.getTicks());
            halt.put("universeActiveAtHalt", context.universeActive());
            halt.put("stepAtHalt", stepIndex < phase.steps().size() ? phase.steps().get(stepIndex).name() : "-");
            halt.put("atHalt", atHalt);
            halt.put("witness", new LinkedHashMap<>(witness));
            halt.put("observed", new LinkedHashMap<>(observed));
            halt.put("disk", context.diskSummary());
            halt.put("haltTimeUtc", Instant.now().toString());
            writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            event("halt " + window.name());
        } catch (Exception | Error failure) {
            LOGGER.error("M5 collapse probe：halt receipt 寫不出來", failure);
            exit = HALT_RECEIPT_FAILED_EXIT;
        }
        Runtime.getRuntime().halt(exit);
    }

    // ---- 斷言與診斷 ----

    /** 具名斷言：成立時記在 {@code observed}；不成立時丟出以名稱開頭的 AssertionError。 */
    void expect(String name, boolean condition, Supplier<String> detail) {
        if (!condition) throw new AssertionError(name + "：" + detail.get());
        observed.put(name, true);
    }

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    String diagnostic() {
        try {
            var point = crashPoint;
            return "step=" + (phase == null || stepIndex >= phase.steps().size() ? "-" : phase.steps().get(stepIndex).name()) + " "
                    + (context == null ? "" : context.diagnostic())
                    + " crash=" + (point == null ? "-" : point.window + " reached=" + point.reached() + " hits=" + point.hits());
        } catch (RuntimeException failure) {
            return "（診斷失敗：" + failure + "）";
        }
    }

    // ---- 檔案 ----

    Path saveRoot() { return server.getSavePath(WorldSavePath.ROOT); }

    Path data() { return saveRoot().resolve("data"); }

    /** 開服前就能用的 {@code world/data}（root 是 cwd，level-name 由 harness 固定為 {@code world}）。 */
    Path worldData() { return config.root().resolve("world").resolve("data"); }

    /** 同一條 chain 前一個 phase（{@code phase}）的 evidence 目錄。 */
    Path phaseEvidence(String phase) { return config.evidence().resolveSibling(phase); }

    static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

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
    static void writeDurably(Path path, String text) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
