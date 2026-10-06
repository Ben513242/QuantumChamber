package dev.quantumchamber.gametest;

import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestState;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 GameTest 專用的 testmod hook：mixin 讀取的靜態狀態（F4a-0 從 {@code M4CandidateDoorGameTests} 搬來）。release JAR 不含此類。
 * <ul>
 * <li>collapse-suspended（M5 plan 決策 7，{@code CollapseSuspendMixin} 讀取）：被加入的 session，{@code CollapseService} 完全不推進，
 *     M4 的 GameTest 因此維持「MEASURED 會一直等待」的原斷言。不變量：同一個 JVM 內不得清除（沒有移除的 API）；default run 中每一個
 *     會產生 MEASURED 的 test 都必須在 CollapseService 第一次看到該 session 之前（選門的同一個 tick 之前）加入。</li>
 * <li>journal flush 探針（{@link #FLUSH_PROBE}）：{@code SessionJournalWindowFaultMixin} 在每次 journal flush 開頭呼叫 {@link #beforeJournalFlush}，
 *     探針丟出例外即讓那次寫出受控失敗。只由 M5 的 test 經 {@code M5GameTestSupport.installFlushProbe} 安裝。</li>
 * <li>journal 寫出稽核（{@link #JOURNAL_AUDIT}，F4a-0 m-1）：{@code SessionJournalWriteCountMixin} 在 journal 正式檔每一次實際寫出之前呼叫
 *     {@link #beforeJournalWrite}。稽核由 {@code M5GameTestSupport.afterAutosaveClear} 在窗口開始時安裝。</li>
 * <li>物化失敗（{@link #MATERIALIZE_FAULT}，F4a）：{@code MaterializeFaultMixin} 在 lifecycle backend 每一次物化的開頭呼叫
 *     {@link #beforeMaterialize}，注入時以 {@code FAILED_ROLLED_BACK} 取代那次物化。由 {@code M5GameTestSupport.installMaterializeFault} 安裝。</li>
 * <li>checked NBT 檔的讀取失敗（{@link #CHECKED_READ_FAULT}，F4a）：{@code CheckedNbtFileReadFaultMixin} 在 {@code CheckedNbtFile.readFile} 的開頭
 *     呼叫 {@link #beforeCheckedRead}，注入時丟 {@link IOException}。由 {@code M5GameTestSupport.installCheckedReadFault} 安裝。</li>
 * <li>原生存檔 checkpoint 的探針（{@link #CHUNK_CHECKPOINT_PROBE}，F4b-2）：{@code ChunkSaveCheckpointProbeMixin} 在 {@code ChunkSaveCheckpoint.save} 的開頭
 *     呼叫 {@link #beforeChunkCheckpoint}，記下指定世界的每一次 checkpoint，前幾次可以受控失敗。由 {@code M5GameTestSupport.installChunkCheckpointProbe} 安裝。</li>
 * </ul>
 * 探針、稽核與兩個故障注入各是一個 {@link Slot}（F4a-0 fix round 1，quality Q1）：同一時間只有一個持有者，被占用時再安裝會丟出、不覆蓋；持有者的 hooks 釋放時，
 * 或持有者的 test 結束時（含 tickLimit 逾時，這時 hooks 不會釋放），由 {@code M5GameTestSupport} 移除。
 */
public final class M5TestHooks {
    private M5TestHooks() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final Set<UUID> COLLAPSE_SUSPENDED = ConcurrentHashMap.newKeySet();
    /** journal flush 探針（{@code M5GameTestSupport.installFlushProbe}）。 */
    static final Slot<Consumer<SessionRecoveryState>> FLUSH_PROBE = new Slot<>("journal flush 探針");
    /** journal 寫出稽核（{@code M5GameTestSupport.installJournalAudit}，由 {@code afterAutosaveClear} 呼叫）。 */
    static final Slot<JournalWriteAudit> JOURNAL_AUDIT = new Slot<>("journal 寫出稽核");
    /** 物化失敗的注入（{@code M5GameTestSupport.installMaterializeFault}）。 */
    static final Slot<MaterializeFault> MATERIALIZE_FAULT = new Slot<>("物化失敗注入");
    /** checked NBT 檔的讀取失敗注入（{@code M5GameTestSupport.installCheckedReadFault}）。 */
    static final Slot<CheckedReadFault> CHECKED_READ_FAULT = new Slot<>("checked NBT 讀取失敗注入");
    /** 原生存檔 checkpoint 的探針與失敗注入（F4b-2，{@code M5GameTestSupport.installChunkCheckpointProbe}）。 */
    static final Slot<ChunkCheckpointProbe> CHUNK_CHECKPOINT_PROBE = new Slot<>("原生存檔 checkpoint 探針");

    /** {@code CollapseSuspendMixin}：這個 session 是否被暫停（system property {@code quantumchamber.test.collapseSuspended=all} 暫停全部）。 */
    public static boolean collapseSuspended(UUID session) {
        return "all".equals(System.getProperty("quantumchamber.test.collapseSuspended")) || COLLAPSE_SUSPENDED.contains(session);
    }

    /** 暫停這個 session 的塌縮流程（不可撤銷）。 */
    static void suspendCollapse(UUID session) { COLLAPSE_SUSPENDED.add(Objects.requireNonNull(session, "session")); }

    /**
     * {@code SessionJournalWindowFaultMixin}：每次 journal flush 開頭呼叫。只在 server thread 上：先讓進行中的稽核記下這次 flush（探針看到的
     * flush），再把 journal 交給目前的探針（探針可以丟出例外讓這次寫出受控失敗）。
     */
    public static void beforeJournalFlush(SessionRecoveryState state, MinecraftServer server) {
        if (!server.isOnThread()) return;
        var audit = JOURNAL_AUDIT.get();
        if (audit != null) audit.record(state, false);
        var probe = FLUSH_PROBE.get();
        if (probe != null) probe.accept(state);
    }

    /** {@code SessionJournalWriteCountMixin}：journal 正式檔每一次實際寫出之前呼叫（不論來源）。沒有進行中的稽核時不做任何事；不丟出例外。 */
    public static void beforeJournalWrite(SessionRecoveryState state) {
        var audit = JOURNAL_AUDIT.get();
        if (audit != null) audit.record(state, true);
    }

    /**
     * {@code MaterializeFaultMixin}：lifecycle backend 每一次物化的開頭呼叫。回傳非 null 時，mixin 以它取代這次物化（不呼叫原本的物化）。
     * 只在 server thread 上生效；不丟出例外。
     */
    public static MaterializeResult beforeMaterialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        if (!server.isOnThread()) return null;
        var fault = MATERIALIZE_FAULT.get();
        return fault == null ? null : fault.intercept(descriptor);
    }

    /** {@code CheckedNbtFileReadFaultMixin}：checked NBT 檔每一次讀取的開頭呼叫；注入時丟 {@link IOException}，那次讀取因此失敗。 */
    public static void beforeCheckedRead(Path path) throws IOException {
        var fault = CHECKED_READ_FAULT.get();
        if (fault != null) fault.intercept(path);
    }

    /**
     * {@code ChunkSaveCheckpointProbeMixin}：{@code ChunkSaveCheckpoint.save} 的開頭呼叫（F4b-2）。只在 server thread 上：交給目前的探針記下這次 checkpoint；
     * 探針在受控失敗時丟 {@link IllegalStateException}，那次 checkpoint 因此在存檔之前失敗（production 一律把 checkpoint 的例外當成失敗）。
     */
    public static void beforeChunkCheckpoint(MinecraftServer server, Map<ServerWorld, Set<ChunkPos>> relevant) {
        if (!server.isOnThread()) return;
        var probe = CHUNK_CHECKPOINT_PROBE.get();
        if (probe != null) probe.before(server, relevant);
    }

    /**
     * mixin 讀取的一個靜態 hook 位置（F4a-0 fix round 1，quality Q1）：同一時間只有一個持有者。安裝與移除只經 {@code M5GameTestSupport}
     * （先在 hooks 登記移除、再登記 test 結束時的移除，最後才安裝）；mixin 只讀 {@link #get}。安裝與移除在 server thread 上。
     */
    static final class Slot<T> {
        final String name;
        private volatile T value;
        private GameTestState owner;

        Slot(String name) { this.name = name; }

        /** 目前裝著的值；沒有時為 null。 */
        T get() { return value; }

        /**
         * 安裝。位置已被占用時丟 IllegalStateException、不覆蓋：可能是另一個 test 同時使用（同一個 batch 的 test 同時執行），或前一個 test 沒有
         * 釋放的殘留；訊息列出 hook、持有者與它是否已結束，以及這次安裝的 test。
         */
        synchronized void install(T next, GameTestState test) {
            Objects.requireNonNull(next, name);
            if (value != null) throw new IllegalStateException(name + " 已被占用，不覆蓋：持有者 " + describe(owner) + "，這次安裝來自 " + describe(test)
                    + "。同一時間只能有一個 test 使用；用探針或稽核的 test 必須獨占一個 batch");
            value = next;
            owner = test;
        }

        /** 目前仍是 {@code expected} 時移除，回傳是否移除；已被移除或換成別的值時不動。 */
        synchronized boolean remove(T expected) {
            if (expected == null || value != expected) return false;
            value = null;
            owner = null;
            return true;
        }

        private static String describe(GameTestState test) {
            if (test == null) return "?";
            return test + (test.isCompleted() ? "（已結束而沒有釋放）" : "（執行中）");
        }
    }

    /**
     * 物化失敗（F4a）：裝著的期間，對 {@code worldKey} 的每一次 backend 物化都計數並回 backend 的 {@code FAILED_ROLLED_BACK}（安全撤回：沒有建立
     * world、沒有 runtime owner），不呼叫原本的物化。其他 world key 照常。lifecycle 對同一 instance 失敗過的 descriptor 的拒絕
     * （{@code FAILED_BEFORE}）不經過 backend，不計數。
     */
    static final class MaterializeFault {
        private final RegistryKey<World> worldKey;
        private final AtomicInteger calls = new AtomicInteger();

        MaterializeFault(RegistryKey<World> worldKey) { this.worldKey = Objects.requireNonNull(worldKey, "worldKey"); }

        MaterializeResult intercept(UniverseWorldDescriptor descriptor) {
            if (!descriptor.worldKey().equals(worldKey)) return null;
            calls.incrementAndGet();
            return new MaterializeResult(MaterializeResult.Status.FAILED_ROLLED_BACK, null);
        }

        /** backend 對這個 world key 的物化次數（每一次都已注入失敗）。 */
        int calls() { return calls.get(); }
    }

    /**
     * checked NBT 檔的讀取失敗（F4a）：檔名恰為 {@code fileName} 的讀取（暫存檔的名稱不同，不算），先放行 {@code skip} 次，之後的 {@code failures} 次
     * 丟 {@link IOException}，再之後照常。對正式檔注入，在寫入時就是「atomic move 完成、讀回失敗」，在讀取時就是讀檔的 IO 例外。
     */
    static final class CheckedReadFault {
        private final String fileName;
        private int skip, remaining;
        private final AtomicInteger hits = new AtomicInteger();

        CheckedReadFault(String fileName, int skip, int failures) {
            this.fileName = Objects.requireNonNull(fileName, "fileName"); this.skip = skip; this.remaining = failures;
        }

        synchronized void intercept(Path path) throws IOException {
            var name = path.getFileName();
            if (name == null || !name.toString().equals(fileName)) return;
            if (skip > 0) { skip--; return; }
            if (remaining <= 0) return;
            remaining--;
            hits.incrementAndGet();
            throw new IOException("F4a GameTest 受控：讀取 " + fileName + " 失敗");
        }

        /** 已注入失敗的次數。 */
        int hits() { return hits.get(); }
    }

    /**
     * 原生存檔 checkpoint 的探針（F4b-2）：只看 {@code relevant} 含 {@code world} 的 checkpoint（其他世界的照常，例如走廊清理的 MEASURED checkpoint）。
     * 每一次都依序記下 server tick、這個世界的相關 chunks，以及 {@code observe} 在那一刻回傳的觀察（例如 projection ledger 的狀態；它也可以在存檔之前改動
     * 世界，模擬「第一次寫入與 checkpoint 之間」的改動）；前 {@code failFirst} 次在存檔之前受控失敗。{@code observe} 丟出的例外只記下，不影響 checkpoint。
     */
    static final class ChunkCheckpointProbe {
        /** 一次 checkpoint：server tick、這個世界的相關 chunks、{@code observe} 的觀察，以及是否受控失敗。 */
        record Call(int tick, Set<ChunkPos> chunks, String observed, boolean failed) { }

        private final RegistryKey<World> world;
        private final int failFirst;
        private final java.util.function.IntFunction<String> observe;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        ChunkCheckpointProbe(RegistryKey<World> world, int failFirst, java.util.function.IntFunction<String> observe) {
            this.world = Objects.requireNonNull(world, "world"); this.failFirst = failFirst; this.observe = Objects.requireNonNull(observe, "observe");
        }

        void before(MinecraftServer server, Map<ServerWorld, Set<ChunkPos>> relevant) {
            var mine = relevant.entrySet().stream().filter(entry -> entry.getKey().getRegistryKey().equals(world)).findFirst();
            if (mine.isEmpty()) return;
            int index = calls.size();
            String observed;
            try {
                observed = observe.apply(index);
            } catch (RuntimeException failure) {
                observed = "觀察失敗：" + failure;
                LOGGER.error("M5_CHECKPOINT_PROBE_OBSERVE_FAILED world={} call={}", world.getValue(), index, failure);
            }
            boolean fail = index < failFirst;
            calls.add(new Call(server.getTicks(), Set.copyOf(mine.get().getValue()), observed, fail));
            if (fail) throw new IllegalStateException("F4b-2 GameTest 受控：原生存檔 checkpoint 失敗（第 " + (index + 1) + " 次）");
        }

        /** 依發生順序的每一次 checkpoint（含受控失敗的那幾次）。 */
        List<Call> calls() { return List.copyOf(calls); }
    }

    /**
     * 窗口內的 journal 寫出稽核（F4a-0 m-1）。記兩種事件：
     * <ul>
     * <li>flush：探針看到的 flush（{@link #beforeJournalFlush}，在探針之前記），含當時有未寫出變更的 session 與呼叫端；</li>
     * <li>write：正式檔每一次實際寫出（{@link #beforeJournalWrite}），依呼叫堆疊判定來源：經 {@code SessionRecoveryState.flush}（探針看得到）、
     *     原生 autosave（{@code MinecraftServer.tick} → {@code saveAll}）、{@code server.save}（例如 {@code ChunkSaveCheckpoint}，呼叫端另列）、
     *     停服存檔，或其他。</li>
     * </ul>
     * 堆疊以 Loom 開發 runtime 的 named 名稱（Yarn 1.21+build.9）比對；GameTest 只在這個 runtime 執行。只在 server thread 上被呼叫。
     */
    static final class JournalWriteAudit {
        private static final String JOURNAL = "dev.quantumchamber.persistence.SessionRecoveryState";
        private static final String SERVER = "net.minecraft.server.MinecraftServer";
        private static final StackWalker WALKER = StackWalker.getInstance();

        /** 寫出的來源。只有 {@link #FLUSH} 經過探針。 */
        enum Origin {
            FLUSH("經 flush（探針看得到）"), AUTOSAVE("原生 autosave"), SERVER_SAVE("server.save"), SHUTDOWN("停服存檔"), OTHER("其他寫出");
            final String label;
            Origin(String label) { this.label = label; }
        }

        /** 一個事件：server tick、是否為實際寫出、來源、呼叫端、當時 current 與 flushed 不同的 session。 */
        record Event(int tick, boolean write, Origin origin, String caller, Set<UUID> changed) {
            @Override public String toString() {
                return (write ? "write" : "flush") + "@" + tick + "[" + origin + " " + caller + " changed=" + changed + "]";
            }
        }

        /** 稽核結果：{@code problems} 是違反「窗口內每一次寫出都經過探針、探針看到的 flush 只帶本 test 的 session」的事件。 */
        record Verdict(boolean pass, List<String> problems, List<Event> events) { }

        private final MinecraftServer server;
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();

        JournalWriteAudit(MinecraftServer server) { this.server = server; }

        /** 記一個事件；稽核本身的例外只記下（判定時失敗），不丟出，以免那次寫出被當成落盤失敗。 */
        void record(SessionRecoveryState state, boolean write) {
            try {
                if (!server.isOnThread()) { errors.add("不在 server thread 的 journal " + (write ? "寫出" : "flush")); return; }
                var frames = WALKER.walk(stream -> stream.toList());
                events.add(new Event(server.getTicks(), write, write ? origin(frames) : Origin.FLUSH, caller(frames), changed(state)));
            } catch (RuntimeException failure) {
                errors.add(failure.toString());
                LOGGER.error("M5_JOURNAL_AUDIT_FAILED 稽核 journal {}時失敗", write ? "寫出" : "flush", failure);
            }
        }

        /**
         * 判定：每一次實際寫出都經 flush（否則是旁路：原生 autosave、server.save、停服或其他寫出）；每一次探針看到的 flush（含受控失敗、沒有寫出的
         * 那幾次）都只帶 {@code own} 的變更（否則是其他 session 的 flush，例如前一個 batch 殘留的 session）。
         */
        Verdict verdict(Collection<UUID> own) {
            var problems = new java.util.ArrayList<String>();
            for (var event : events) {
                if (event.write() && event.origin() != Origin.FLUSH)
                    problems.add("旁路寫出（" + event.origin().label + "，呼叫端 " + event.caller() + "）tick=" + event.tick() + " changed=" + event.changed());
                var foreign = new TreeSet<>(event.changed()); foreign.removeAll(own);
                if (!event.write() && !foreign.isEmpty())
                    problems.add("其他 session 的 flush（session=" + foreign + "，呼叫端 " + event.caller() + "）tick=" + event.tick());
            }
            errors.forEach(error -> problems.add("稽核本身失敗：" + error));
            return new Verdict(problems.isEmpty(), List.copyOf(problems), List.copyOf(events));
        }

        /** 實際寫出的來源：堆疊上有 {@code SessionRecoveryState.flush} 即經 flush；否則依呼叫 {@code MinecraftServer.save/saveAll} 的那一層判定。 */
        private static Origin origin(List<StackWalker.StackFrame> frames) {
            if (frames.stream().anyMatch(frame -> is(frame, JOURNAL, "flush"))) return Origin.FLUSH;
            int save = outermostSave(frames);
            if (save < 0 || save + 1 >= frames.size()) return Origin.OTHER;
            var caller = frames.get(save + 1);
            if (is(caller, SERVER, "tick")) return Origin.AUTOSAVE;
            if (is(caller, SERVER, "shutdown")) return Origin.SHUTDOWN;
            return Origin.SERVER_SAVE;
        }

        /** 呼叫端：經 flush 時是呼叫 flush 的第一個非 journal 方法；否則是呼叫 {@code MinecraftServer.save/saveAll} 的那一層，再沒有就是 journal 之外的第一層。 */
        private static String caller(List<StackWalker.StackFrame> frames) {
            int start = -1;
            for (int i = 0; i < frames.size(); i++) if (is(frames.get(i), JOURNAL, "flush")) { start = i; break; }
            if (start < 0) {
                int save = outermostSave(frames);
                if (save >= 0 && save + 1 < frames.size()) return describe(frames.get(save + 1));
                start = 0;
            }
            for (int i = start; i < frames.size(); i++) {
                var name = frames.get(i).getClassName();
                if (!name.equals(JOURNAL) && !name.equals(M5TestHooks.class.getName()) && !name.startsWith(JournalWriteAudit.class.getName()))
                    return describe(frames.get(i));
            }
            return "?";
        }

        private static int outermostSave(List<StackWalker.StackFrame> frames) {
            int found = -1;
            for (int i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                if (is(frame, SERVER, "save") || is(frame, SERVER, "saveAll")) found = i;
            }
            return found;
        }

        private static boolean is(StackWalker.StackFrame frame, String type, String method) {
            return frame.getClassName().equals(type) && frame.getMethodName().equals(method);
        }

        private static String describe(StackWalker.StackFrame frame) {
            var name = frame.getClassName();
            return name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }

        /** current 與 flushed 不同的 session（含只在其中一邊的）：這次 flush／寫出會帶出的變更。 */
        private static Set<UUID> changed(SessionRecoveryState state) {
            var current = state.records(); var flushed = state.flushedRecords();
            var ids = new TreeSet<UUID>(current.keySet()); ids.addAll(flushed.keySet());
            ids.removeIf(id -> Objects.equals(current.get(id), flushed.get(id)));
            return ids;
        }
    }
}
