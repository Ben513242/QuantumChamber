package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
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
 *     {@code Runtime.halt(77)} 結束 JVM：不經正常停止、不存檔，等同 crash。只有乾淨的 halt 才是 77（crash 點的第一次 hit、crash 點沒有記下失敗、{@code failures}
 *     是空的、停服還沒有開始；否則 status {@code HALT_REFUSED}、exit 78）。窗口沒有到達、或任何斷言失敗時，照常停止並寫 FAIL 的 {@code final.json}。halt 的時點有兩種
 *     （{@link HaltAt}，F6-2c fix round 1）：窗口成立的那一刻（hook 裡），或窗口成立的那個 tick 結束時。</li>
 * </ul>
 * 斷言一律 {@link #expect}：失敗訊息以斷言名稱開頭（例如 {@code catalogOnlyDestination}），記在 receipt 的 {@code failures}，之後正常停止；成立的記在
 * {@code observed}。例外不會從 {@code END_SERVER_TICK} 拋出（{@link #guarded}）。probe 端安裝的 hook（crash 點與 fault，{@link #install}）在 {@link #stop}
 * 逐一移除；不經 {@link #stop} 的停服（例如 crash report 的路徑）也在 {@code SERVER_STOPPING} 一開始移除（{@link #stoppingBegins}，F6-2c Step 0）。
 */
final class M5CollapseRun {
    static final String OWNER = ".superpowers/sdd/2026-10-01-m5-collapse-passage";
    /** 預期 crash 的 exit code：{@code m5-collapse-probe.ps1} 以它分辨 crash 窗口與其他非零結束。 */
    static final int HALT_EXIT = 77;
    /** 不是乾淨的 crash 時的 exit code（harness 判 FAIL）：halt receipt 寫不出來，或乾淨的守門拒絕（{@link #halt}）。 */
    static final int HALT_FAILED_EXIT = 78;
    /** 整個 phase 的上限 tick 數。 */
    static final int PHASE_TICK_LIMIT = 3600;
    /** crash phase 寫出的 MEASURED 收據（單筆 journal 的 NBT），restart phase 以它比對「沒有重抽」。 */
    static final String RECEIPT_NBT = "receipt.nbt";
    static final Gson JSON = new Gson();
    static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m5-collapse");
    private static final Identifier AFTER = Identifier.of("quantumchamber:m5_collapse_after");
    /** {@code SERVER_STOPPING} 的 phase：排在 default phase（production 的 listener）之前（{@link #stoppingBegins}）。 */
    private static final Identifier BEFORE = Identifier.of("quantumchamber:m5_collapse_before");
    static final Checked NOTHING = () -> { };

    // ---- phase 的型別 ----

    /**
     * 一個 phase：開服前的準備（{@code onInitialize}，例如移走權威檔）、開服時的檢查、每個 tick 的不變量（在 step 之前執行）、依序執行的 step，與停服之後的檢查
     * （{@code SERVER_STOPPED}，寫 {@code final.json} 之前）；{@code ending} 是預期的結束方式（與 m5-collapse-probe.ps1 的 chain 表一致）。開服前的準備或停服之後的
     * 檢查失敗時，記在 receipt 的 {@code failures}，phase 判 FAIL。
     * <p>建立方式（F6-2c Step 0，F6-2a quality Minor 5：四個動作都是 {@link Checked}，位置參數互換也能編譯，所以不提供位置參數的建構子）：以結束方式的 factory
     * （{@link #stop}、{@link #halt}）帶 step，再以具名的方法加上需要的動作，例如 {@code Phase.halt(steps).started(fixture::requireFreshWorld)}。
     * 沒有指定的動作是 {@link #NOTHING}；每個動作只能指定一次（再指定時丟出）。crash 點的健康檢查（{@link #crashPointHealthy}）不經 {@code everyTick}：runner 每個 tick 在
     * {@code everyTick} 之前自動呼叫（F6-2c，F6-2c Step 0 quality Minor 2），{@code everyTick} 留給 chain 自己的不變量。
     */
    static final class Phase {
        private final Ending ending;
        private final List<Step> steps;
        private final Checked beforeStart, started, everyTick, afterStop;

        private Phase(Ending ending, List<Step> steps, Checked beforeStart, Checked started, Checked everyTick, Checked afterStop) {
            this.ending = ending;
            this.steps = List.copyOf(steps);
            if (this.steps.isEmpty()) throw new IllegalArgumentException("phase 至少要有一個 step");
            this.beforeStart = beforeStart;
            this.started = started;
            this.everyTick = everyTick;
            this.afterStop = afterStop;
        }

        /** 全部 step 完成之後正常停止（{@code final.json}）的 phase。 */
        static Phase stop(List<Step> steps) { return new Phase(Ending.STOP, steps, NOTHING, NOTHING, NOTHING, NOTHING); }

        /** 在 crash 窗口 halt（{@code halt.json}）的 phase：最後一個 step 不會完成。 */
        static Phase halt(List<Step> steps) { return new Phase(Ending.HALT, steps, NOTHING, NOTHING, NOTHING, NOTHING); }

        /** 開服前（{@code onInitialize}）的準備。 */
        Phase beforeStart(Checked action) { return new Phase(ending, steps, once("beforeStart", beforeStart, action), started, everyTick, afterStop); }

        /** 開服時（{@code SERVER_STARTED} 的 AFTER phase）的檢查。 */
        Phase started(Checked action) { return new Phase(ending, steps, beforeStart, once("started", started, action), everyTick, afterStop); }

        /** 每個 tick 的不變量（在 crash 點的健康檢查之後、step 之前執行）。 */
        Phase everyTick(Checked action) { return new Phase(ending, steps, beforeStart, started, once("everyTick", everyTick, action), afterStop); }

        /** 停服之後（{@code SERVER_STOPPED}，寫 {@code final.json} 之前）的檢查。 */
        Phase afterStop(Checked action) { return new Phase(ending, steps, beforeStart, started, everyTick, once("afterStop", afterStop, action)); }

        Ending ending() { return ending; }

        List<Step> steps() { return steps; }

        private static Checked once(String name, Checked current, Checked next) {
            if (current != NOTHING) throw new IllegalStateException("phase 的 " + name + " 已經指定過");
            return Objects.requireNonNull(next, name);
        }
    }

    /** phase 預期的結束：正常停止（{@code final.json}），或在 crash 窗口 halt（{@code halt.json}、exit code {@link #HALT_EXIT}）。 */
    enum Ending { STOP, HALT }

    /**
     * crash 點的 halt 時點（{@link #armCrash}；F6-2c fix round 1）。halt receipt 記在 {@code haltTiming}（F6-2d Step 0 由 {@code haltAt} 改名，F6-2c quality Minor 7：
     * 避免與取證的 {@code atHalt} 混淆），另記窗口成立的 server tick（{@code windowServerTick}）。
     */
    enum HaltAt {
        /** 窗口成立的那一刻：hook 裡就取證、寫 halt receipt、halt（F6-1 起的行為），production 在那一刻之後的程式都不會執行。 */
        WINDOW,
        /**
         * 窗口第一次成立的那個 tick 結束時：hook 只記下 detail，runner 在同一個 tick 的 {@code END_SERVER_TICK}（AFTER phase，晚於 production 在這個 tick 的全部
         * {@code END_SERVER_TICK} 工作，例如 Crossed 確認之後的 playerdata 存檔與到達提示）一開始才取證、寫 halt receipt、halt；那個 tick 的 {@code everyTick} 與 step
         * 不執行。窗口成立到 halt 之間 probe 不做任何事，原版也不存 playerdata（只在 autosave、登出與停服時存；autosave 在 {@code END_SERVER_TICK} 之前，間隔 6000 tick），
         * 所以是「窗口成立的那個 tick 結束時 crash」。halt 不在窗口成立的那個 tick（不應發生）時 {@code HALT_REFUSED}。
         */
        TICK_END
    }

    /** 一個 step：每個 tick 呼叫一次 {@code run}，回 true 時完成（記 witness，下一個 tick 進下一個 step）；{@code maxTicks} 個 tick 內沒有完成時 FAIL。 */
    record Step(String name, int maxTicks, Tick run) {
        Step {
            if (maxTicks < 1) throw new IllegalArgumentException("step 至少要有 1 個 tick");
        }
    }

    @FunctionalInterface interface Tick { boolean run() throws Exception; }

    @FunctionalInterface interface Checked { void run() throws Exception; }

    /**
     * 會丟出例外的條件（例如讀磁碟上的權威）；給 {@link #expect(String, CheckedCondition, Supplier)}。名稱比照 {@link Checked}（可以丟出 checked 例外；F6-2c Step 0 由 {@code Condition}
     * 改名，避免與窗口表的 {@code M5CollapseWindows.Condition} 同名）。
     */
    @FunctionalInterface interface CheckedCondition { boolean holds() throws Exception; }

    /**
     * halt 之前的取證（或刻意的準備，例如 W4 的存檔）：寫進 halt receipt 的 {@code atHalt}。丟出時不 halt：{@link HaltAt#WINDOW} 由 crash 點記下失敗，phase 在下一個 tick FAIL；
     * {@link HaltAt#TICK_END} 在 runner 的 tick 裡丟出，phase 當場 FAIL。窗口成立的那一刻的取證是另一個型別 {@link WindowEvidence}（F6-2d-1）。
     */
    @FunctionalInterface interface HaltEvidence { void collect(Object detail, Map<String, Object> evidence) throws Exception; }

    /**
     * 窗口成立的那一刻（hook 裡）的取證：寫進 halt receipt 的 {@code atWindow}（F6-2d Step 0）。F6-2d-1（F6-2d Step 0 quality Minor 2）：原本與 {@link HaltEvidence} 同型別，
     * {@link #armCrash} 的兩個取證參數互換也能編譯（{@code TICK_END} 的列的取證時點會靜默改變）；這個型別不收 detail，參數個數不同，互換位置不能編譯。丟出時同
     * {@link HaltEvidence}（不 halt）。F6-2d-2 Step 0（F6-2d-1 quality nit）：它也是 runner 內部 {@link #collect} 收的形狀（原本另有同形狀的 {@code EvidenceWriter}）；
     * halt 之前的取證以 lambda 綁上 detail 交給 {@link #collect}。
     */
    @FunctionalInterface interface WindowEvidence { void collect(Map<String, Object> evidence) throws Exception; }

    /** 在 {@code onInitialize} 呼叫（entrypoint 的 phase 表，{@code M5CollapseRecoveryProbe.PHASES}）：建立這個 phase 的 chain，回傳它的 {@link Phase}。 */
    @FunctionalInterface interface PhaseFactory { Phase create(M5CollapseRun run); }

    /** chain 的 fixture 提供給 runner 的診斷與 halt 當下的事實。 */
    interface Context {
        /** 診斷訊息的 chain 部分（session、record 的狀態、catalog）。 */
        String diagnostic();

        /** halt 當下這條 chain 的 U 是否已物化（ACTIVE）。 */
        boolean universeActive();

        /** halt 當下磁碟上的 M5 權威摘要（{@link M5CollapseDiskEvidence#summary}）。 */
        Map<String, Object> diskSummary();
    }

    /**
     * probe 的設定（system property {@code quantumchamber.m5collapse.*}，由 build.gradle 的 m5Collapse run 從 {@code -Pm5Collapse*} 轉入；F6-2c Step 0 由
     * {@code M5CollapseRecoveryProbe} 移到這裡，runner 不再依賴 entrypoint）。
     */
    record Configuration(String phase, String nonce, String startupNonce, Path root, Path evidence) {
        /** 沒有 phase 屬性時回 null（probe 關閉）；有但不合法（含不在 {@code phases} 裡的 phase）時丟出。 */
        static Configuration read(Set<String> phases) {
            String prefix = "quantumchamber.m5collapse.";
            String phase = System.getProperty(prefix + "phase");
            if (phase == null) return null;
            String nonce = System.getProperty(prefix + "nonce", ""), startup = System.getProperty(prefix + "startupNonce", "");
            String supplied = System.getProperty(prefix + "root", "");
            if (!phases.contains(phase) || !nonce.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || nonce.equals("disabled") || !startup.matches("[0-9a-f]{32}") || supplied.isBlank()) {
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

    // ---- 狀態 ----

    final Configuration config;
    /** entrypoint 的 phase 表的全部 phase 名稱（依表的順序）：寫進 receipt 的 {@code phaseTable}，harness 與 chain 表比對。 */
    private final List<String> phaseTable;
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
    private MinecraftServer server;
    private ServerWorld source;
    private SessionRecoveryState journal;
    private LogCapture capture;
    private LogCapture startupLog;
    private Phase phase;
    private int ticks;
    private int stepIndex, stepTicks;
    private boolean finished, passed;
    /**
     * {@code SERVER_STOPPING} 已開始（{@link #stoppingBegins}；receipt 的鍵也是 {@code stoppingSeen}，F6-2c 由 {@code stopping} 改名，F6-2c Step 0 quality Minor 4）；
     * {@link #halt} 讀它（crash 窗口的 hook 可能不在 server thread 上，所以是 volatile）。
     */
    private volatile boolean stoppingSeen;
    private M5TestHooks.CrashPoint crashPoint;
    /** {@link HaltAt#TICK_END} 的 crash 點第一次成立時記下的 halt；還沒有成立時為 null（F6-2c fix round 1）。 */
    private volatile PendingHalt pendingHalt;
    /**
     * crash 點第一次成立的那一刻（hook 裡）記下的取證（{@link #armCrash} 的 {@code windowEvidence}；還沒有成立時是空的）：寫進 halt receipt 的 {@code atWindow}，halt 時的取證以
     * {@link #atWindow()} 讀它（F6-2d Step 0，F6-2c fix round 1 spec re-review Minor 1：{@link HaltAt#TICK_END} 的窗口要分辨「那一刻」與「那個 tick 結束時」的磁碟）。
     */
    private volatile Map<String, Object> atWindow = Map.of();

    /** {@link HaltAt#TICK_END}：窗口第一次成立時的 detail、那一刻的 server tick、detail 的描述與 halt 之前的取證。 */
    private record PendingHalt(CrashWindow window, Object detail, int windowTick, Function<Object, String> describe, HaltEvidence evidence) {}

    M5CollapseRun(Configuration config, List<String> phaseTable) {
        this.config = config;
        this.phaseTable = List.copyOf(phaseTable);
    }

    // ---- chain 與 fixture 讀的狀態（唯讀；F6-2c Step 0 由 package 可見的可變欄位改成 accessor） ----

    /** 這個 phase 的 server（{@code SERVER_STARTED} 起；之前為 null）。 */
    MinecraftServer server() { return server; }

    /** 來源世界（overworld，{@code SERVER_STARTED} 起）。 */
    ServerWorld source() { return source; }

    /** session journal（{@code SERVER_STARTED} 起）。 */
    SessionRecoveryState journal() { return journal; }

    /** {@code quantumchamber} logger 從 {@code SERVER_STARTED} 的 AFTER phase 起的 log。 */
    LogCapture capture() { return capture; }

    /** {@code quantumchamber} logger 從 {@code onInitialize} 起的 log（含開服時 default phase 的初始化，例如「M5 authority 初始化失敗」）。 */
    LogCapture startupLog() { return startupLog; }

    /** 這個 phase 到目前為止的 tick 數（{@code END_SERVER_TICK} 的次數）。 */
    int ticks() { return ticks; }

    /**
     * chain 的 fixture 在建構時登記（診斷與 halt 當下的事實）。一個 phase 只能有一個 Context：再登記時丟出、不覆蓋（F6-2c Step 0，F6-2a quality Minor 7；fixture 在
     * {@code onInitialize} 的 phase factory 裡建立，所以重複登記讓開服之前就失敗）。
     */
    void context(Context fixture) {
        Objects.requireNonNull(fixture, "fixture");
        if (context != null) {
            throw new IllegalStateException("M5 collapse probe 的 runner 已有 Context（" + context.getClass().getSimpleName() + "），不覆蓋："
                    + fixture.getClass().getSimpleName());
        }
        context = fixture;
    }

    // ---- 生命週期 ----

    /** {@code onInitialize}：記下 JVM 的身分、建立 phase、執行開服前的準備，再註冊 listener。 */
    void start(PhaseFactory factory) {
        proof.put("producer", "M5CollapseRecoveryProbe");
        proof.put("phase", config.phase());
        proof.put("phaseTable", phaseTable);
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
        startupLog = new LogCapture("quantumchamber");
        onStopped(startupLog);
        phase = factory.create(this);
        try {
            phase.beforeStart.run();
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
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(BEFORE, Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STOPPING.register(BEFORE, this::stoppingBegins);
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STOPPING.register(AFTER, this::afterProductionStopping);
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
            phase.started.run();
            event("started");
        });
    }

    private void tick(MinecraftServer owner) {
        if (owner != server || finished || phase == null) return;
        guarded(() -> {
            require(++ticks <= PHASE_TICK_LIMIT, "phaseBounded：phase " + config.phase() + " 在 " + PHASE_TICK_LIMIT + " 個 tick 內沒有結束；" + diagnostic());
            // F6-2c fix round 1：TICK_END 的 crash 點在這個 tick 成立過時，這裡（production 的 END_SERVER_TICK 之後）取證、halt，不再執行 everyTick 與 step。
            if (pendingHalt != null) {
                haltAtTheTickEnd(pendingHalt);
                return;
            }
            // F6-2c（F6-2c Step 0 quality Minor 2）：crash 點的健康檢查由 runner 每個 tick 在 everyTick 之前呼叫（原本是三個 HALT phase 各自的 everyTick）；還沒有裝 crash 點時不做事。
            crashPointHealthy();
            phase.everyTick.run();
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

    /**
     * {@code SERVER_STOPPING} 的最前面（{@link #BEFORE}，早於 production 的 listener；F6-2c Step 0，F6-2a quality 給 F6-2 的建議）：記下停服已開始
     * （{@code stoppingSeen}），移除 probe 端安裝的全部 hook。之後 crash 窗口不會再觸發 crash 點；就算還有一次 halt（例如 hook 在移除之前已進入），{@link #halt} 也因
     * {@code stoppingSeen} 拒絕。兩條停服路徑（請求停服、crash report）都會經過這裡；{@link #stop} 在請求停服之前已移除過時，這裡不做任何事。
     */
    private void stoppingBegins(MinecraftServer owner) {
        if (owner != server) return;
        stoppingSeen = true;
        proof.put("stoppingSeen", true);
        removeInstalled();
    }

    /**
     * {@code SERVER_STOPPING} 的 AFTER phase（production 的 listener 之後）：依登記順序執行 {@link #onStopping} 的動作（參與者的連線、load-sync 的觀察）。F6-2c 由
     * {@code stopping} 改名（與欄位 {@code stoppingSeen} 分開；F6-2c Step 0 quality Minor 4）。
     */
    private void afterProductionStopping(MinecraftServer owner) {
        if (owner != server) return;
        for (var action : onStopping) action.run();
        onStopping.clear();
    }

    private void stopped(MinecraftServer owner) {
        if (owner != server) return;
        proof.put("stoppedSeen", true);
        try {
            phase.afterStop.run();
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
        removeInstalled();
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
     * {@link #stop} 或停服一開始（{@link #stoppingBegins}）時依安裝的相反順序移除。crash 點（{@link #armCrash}）與 probe 內的 fault（例如 {@code new M5TestHooks.JournalPutFault(...)} 裝進
     * {@link M5TestHooks#JOURNAL_PUT_FAULT}）都經這裡。回傳 {@code value}。
     * <p>F6-2c（F6-2c Step 0 quality Minor 1）：經這裡的 hook 在 {@code SERVER_STOPPING} 一開始（早於 production 的 listener）就被移除，所以<b>停服期間要保留的 hook 不得經這裡</b>，
     * 要由 chain 自己以 {@link M5TestHooks.Slot#install} 安裝、在 {@code SERVER_STOPPED} 之後移除（例如 unload chain 的卸載工作 witness，它要觀察停服的 {@code unloadChunks}）。
     */
    <T> T install(M5TestHooks.Slot<T> slot, T value) {
        slot.install(value, null);
        installed.add(new Installed<>(slot, value));
        return value;
    }

    private record Installed<T>(M5TestHooks.Slot<T> slot, T value) {
        void remove() { slot.remove(value); }
    }

    /** 依安裝的相反順序移除 probe 端安裝的全部 hook（{@link #stop} 與 {@link #stoppingBegins}）；之後清單是空的，重複呼叫不做任何事。 */
    private void removeInstalled() {
        for (int index = installed.size() - 1; index >= 0; index--) installed.get(index).remove();
        installed.clear();
    }

    // ---- crash 點與 halt receipt ----

    /**
     * 裝上 crash 點：{@code window} 的 detail 符合 {@code condition} 的第一次，先 {@code evidence}，再寫 halt receipt（detail 的文字描述由 {@code describe} 提供，
     * 呼叫端是窗口表）、halt。{@code at} 是 halt 的時點（{@link HaltAt}）：{@code WINDOW} 在 hook 裡就做；{@code TICK_END} 只記下第一次成立的 detail，取證與 halt 在
     * 那個 tick 的 runner tick 一開始（{@link #haltAtTheTickEnd}；之後再成立的 hit 讓乾淨的守門以 {@code hit} 拒絕）。
     * <p>{@code windowEvidence}（F6-2d Step 0）：第一次成立的那一刻在 hook 裡的取證，寫進 halt receipt 的 {@code atWindow}（{@link #atWindow()}）。{@code TICK_END} 用它記下 production
     * 在那一刻之後的程式執行之前的事實（例如 W7 的 playerdata 存檔之前）；{@code WINDOW} 時與 {@code evidence} 是同一刻。丟出時由 crash 點記下失敗、不 halt（同
     * {@link HaltEvidence}）。呼叫端只有窗口表（{@code M5CollapseWindows.arm}），兩個取證都由那一列的具名欄位交來。F6-2d-1：兩個取證是不同的型別（{@link WindowEvidence}、
     * {@link HaltEvidence}），互換位置不能編譯；取證失敗的訊息寫明是哪一段（{@link #collect}）。
     */
    void armCrash(CrashWindow window, HaltAt at, Predicate<Object> condition, Function<Object, String> describe, WindowEvidence windowEvidence, HaltEvidence evidence) {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(windowEvidence, "windowEvidence");
        Objects.requireNonNull(evidence, "evidence");
        var point = new M5TestHooks.CrashPoint(window, condition, (detail, hit) -> {
            if (at == HaltAt.TICK_END) {
                if (pendingHalt == null) {
                    atWindow = Collections.unmodifiableMap(collect(AT_WINDOW, windowEvidence));
                    pendingHalt = new PendingHalt(window, detail, server.getTicks(), describe, evidence);
                }
                return;
            }
            atWindow = Collections.unmodifiableMap(collect(AT_WINDOW, windowEvidence));
            halt(window, detail, describe, hit, collect(AT_HALT, collected -> evidence.collect(detail, collected)), HaltAt.WINDOW, server.getTicks());
        });
        install(M5TestHooks.CRASH_POINT, point);
        crashPoint = point;
        proof.put("crashWindow", window.name());
        proof.put("haltTiming", at.name());
    }

    /** {@link #collect} 的標籤：窗口成立的那一刻的取證（{@code atWindow}）。 */
    private static final String AT_WINDOW = "atWindow：窗口成立的那一刻";
    /** {@link #collect} 的標籤：halt 之前的取證（{@code atHalt}；訊息與 F6-2d-1 之前相同）。 */
    private static final String AT_HALT = "haltEvidence：halt 之前";

    /**
     * 一段取證：寫進一份新的 map 回傳。丟出的 checked 例外包成以 {@code label} 開頭的 IllegalStateException（F6-2d-1，F6-2d Step 0 quality Minor 3：原本兩段都寫成
     * 「halt 之前的取證失敗」，窗口那一刻的取證失敗時會誤導）；具名斷言的 AssertionError 原樣丟出。兩段都以 {@link WindowEvidence} 的形狀交來（F6-2d-2 Step 0）。
     */
    private static Map<String, Object> collect(String label, WindowEvidence writer) {
        var evidence = new LinkedHashMap<String, Object>();
        try {
            writer.collect(evidence);
        } catch (Exception failure) {
            throw new IllegalStateException(label + "的取證失敗：" + failure, failure);
        }
        return evidence;
    }

    /**
     * {@link HaltAt#TICK_END}：窗口成立的那個 tick 的 runner tick 一開始（production 的 {@code END_SERVER_TICK} 之後）取證、halt，hit 是到這時為止 crash 點成立的次數。取證丟出時
     * 由 {@link #guarded} 收下（failures、正常停止並 FAIL），不 halt。
     */
    private void haltAtTheTickEnd(PendingHalt pending) {
        // 與 F6-2d-1 之前相同的求值順序：先讀 hit，再取證。
        int hits = crashPoint.hits();
        var atHalt = collect(AT_HALT, collected -> pending.evidence().collect(pending.detail(), collected));
        halt(pending.window(), pending.detail(), pending.describe(), hits, atHalt, HaltAt.TICK_END, pending.windowTick());
    }

    /** 已裝上的 crash 點；還沒有裝時為 null。 */
    M5TestHooks.CrashPoint crashPoint() {
        return crashPoint;
    }

    /** crash 點第一次成立的那一刻記下的取證（唯讀；還沒有成立時是空的；F6-2d Step 0）。halt 時的取證（{@code atHalt}）以它比對那一刻與 halt 時的差異。 */
    Map<String, Object> atWindow() {
        return atWindow;
    }

    /** crash phase 每個 tick 的不變量：crash 點的條件或動作沒有丟出過例外。runner 每個 tick 在 {@code everyTick} 之前呼叫（F6-2c）；還沒有裝 crash 點時不做事。 */
    private void crashPointHealthy() {
        var point = crashPoint;
        if (point != null) expect("crashPointHealthy", point.failure() == null, () -> "crash 點失敗：" + point.failure());
    }

    /**
     * crash：寫出 {@code halt.json}（CREATE_NEW＋force）之後 {@code Runtime.halt}。乾淨的守門（F6-2a；F6-2c Step 0 加上停服）：這是 crash 點的第一次 hit、crash 點沒有記下失敗
     * （{@code crashPointFailure} 為 {@code none}）、receipt 的 {@code failures} 是空的、停服還沒有開始（{@code stoppingSeen}：停服途中的 halt 會讓 restart 以為是
     * crash，其實已開始正常停止），才是 status {@code HALT}、以 {@link #HALT_EXIT} 結束；任何一項不成立時不當成 crash：status {@code HALT_REFUSED}（{@code refused}
     * 列出原因），以 {@link #HALT_FAILED_EXIT} 結束。receipt 寫不出來時也以 {@link #HALT_FAILED_EXIT} 結束。{@link HaltAt#TICK_END} 另要求 halt 在窗口成立的那個 tick
     * （F6-2c fix round 1）。
     */
    private void halt(CrashWindow window, Object detail, Function<Object, String> describe, int hit, Map<String, Object> atHalt, HaltAt at, int windowTick) {
        var point = crashPoint;
        var refused = new ArrayList<String>();
        if (hit != 1) refused.add("hit=" + hit);
        if (point.failure() != null) refused.add("crashPointFailure=" + point.failure());
        if (!failures.isEmpty()) refused.add("failures=" + failures);
        if (stoppingSeen) refused.add("stoppingSeen：停服已開始（SERVER_STOPPING），不是 crash");
        if (at == HaltAt.TICK_END && server.getTicks() != windowTick) refused.add("haltTick：窗口在 tick " + windowTick + " 成立，halt 在 tick " + server.getTicks());
        int exit = refused.isEmpty() ? HALT_EXIT : HALT_FAILED_EXIT;
        try {
            var halt = new LinkedHashMap<String, Object>(proof);
            halt.put("status", refused.isEmpty() ? "HALT" : "HALT_REFUSED");
            halt.put("refused", refused);
            halt.put("window", window.name());
            halt.put("hit", hit);
            halt.put("reached", point.reached());
            halt.put("crashPointFailure", point.failure() == null ? "none" : point.failure().toString());
            halt.put("detail", describe.apply(detail));
            halt.put("haltTiming", at.name());
            halt.put("windowServerTick", windowTick);
            halt.put("haltServerTick", server.getTicks());
            halt.put("universeActiveAtHalt", context.universeActive());
            halt.put("stepAtHalt", stepIndex < phase.steps().size() ? phase.steps().get(stepIndex).name() : "-");
            halt.put("atWindow", atWindow);
            halt.put("atHalt", atHalt);
            halt.put("witness", new LinkedHashMap<>(witness));
            halt.put("observed", new LinkedHashMap<>(observed));
            halt.put("disk", context.diskSummary());
            halt.put("haltTimeUtc", Instant.now().toString());
            writeDurably(config.evidence().resolve("halt.json"), JSON.toJson(halt));
            event("halt " + window.name());
        } catch (Exception | Error failure) {
            LOGGER.error("M5 collapse probe：halt receipt 寫不出來", failure);
            exit = HALT_FAILED_EXIT;
        }
        if (exit != HALT_EXIT) LOGGER.error("M5 collapse probe：不是乾淨的 halt（{}），以 {} 結束", refused, exit);
        Runtime.getRuntime().halt(exit);
    }

    // ---- 斷言與診斷 ----

    /** 具名斷言：成立時記在 {@code observed}；不成立時丟出以名稱開頭的 AssertionError。 */
    void expect(String name, boolean condition, Supplier<String> detail) {
        if (!condition) throw new AssertionError(name + "：" + detail.get());
        observed.put(name, true);
    }

    /** 同 {@link #expect(String, boolean, Supplier)}，條件本身可能丟出（例如嚴格 decode 磁碟上的 ledger）：丟出時也是這個斷言失敗（訊息以名稱開頭）。 */
    void expect(String name, CheckedCondition condition, Supplier<String> detail) {
        boolean holds;
        try {
            holds = condition.holds();
        } catch (Exception failure) {
            throw new AssertionError(name + "：讀取失敗：" + failure, failure);
        }
        expect(name, holds, detail);
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

    /**
     * 讀回同一條 chain 前一個正常停止的 phase（{@code phase}）的 {@code final.json}（F6-2c Step 0，H5 quality Minor 4：fixture 讀 setup、unload chain 讀 takeback 共用）：
     * {@code <name>ReceiptPresent}（有 {@code final.json}、沒有 {@code halt.json}）、{@code <name>ReceiptMatches}（status PASS、ending STOP、phase 與 nonce 相符），
     * 再 {@link #expectDifferentStartupNonce}。回傳 receipt。
     */
    JsonObject readStoppedReceipt(String phase, String name) throws IOException {
        var evidence = phaseEvidence(phase);
        var path = evidence.resolve("final.json");
        expect(name + "ReceiptPresent", Files.exists(path) && !Files.exists(evidence.resolve("halt.json")), () -> phase + " 沒有正常停止的 receipt：" + evidence);
        var receipt = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        expect(name + "ReceiptMatches", "PASS".equals(text(receipt, "status")) && Ending.STOP.name().equals(text(receipt, "ending"))
                && phase.equals(text(receipt, "phase")) && config.nonce().equals(text(receipt, "nonce")),
                () -> phase + " 的 receipt 不符：status=" + text(receipt, "status") + " ending=" + text(receipt, "ending"));
        expectDifferentStartupNonce(receipt);
        return receipt;
    }

    /** 前一個 phase 的 receipt（{@code final.json} 或 {@code halt.json}）是另一個 JVM 寫的：startupNonce 與這個 JVM 不同（{@code differentStartupNonce}）。 */
    void expectDifferentStartupNonce(JsonObject receipt) {
        expect("differentStartupNonce", !config.startupNonce().equals(text(receipt, "startupNonce")), () -> "與前一個 phase 相同的 startupNonce：" + text(receipt, "startupNonce"));
    }

    static String text(JsonObject object, String key) {
        var value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    /**
     * 在 {@code events.jsonl} 追加一行。不丟出（它也在 {@link #guarded} 的 catch 與 {@code SERVER_STOPPED} 裡呼叫，例外不能從 {@code END_SERVER_TICK} 或 listener 拋出）：
     * 寫不出來時記 ERROR、記在 receipt 的 {@code failures}（phase 因此 FAIL）。
     */
    private void event(String name) {
        try {
            Files.writeString(config.evidence().resolve("events.jsonl"), JSON.toJson(Map.of("event", name, "timeUtc", Instant.now().toString(),
                    "startupNonce", config.startupNonce(), "pid", ProcessHandle.current().pid())) + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failure) {
            failures.add("event：" + name + " 寫不出來：" + failure);
            LOGGER.error("M5 collapse probe：event {} 寫不出來", name, failure);
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
