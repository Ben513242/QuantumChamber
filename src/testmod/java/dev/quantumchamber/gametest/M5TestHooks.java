package dev.quantumchamber.gametest;

import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 GameTest 專用的 testmod hook：mixin 讀取的靜態狀態（F4a-0 從 {@code M4CandidateDoorGameTests} 搬來）。release JAR 不含此類。
 * <ul>
 * <li>collapse-suspended（M5 plan 決策 7，{@code CollapseSuspendMixin} 讀取）：被加入的 session，{@code CollapseService} 完全不推進，
 *     M4 的 GameTest 因此維持「MEASURED 會一直等待」的原斷言。不變量：同一個 JVM 內不得清除（沒有移除的 API）；default run 中每一個
 *     會產生 MEASURED 的 test 都必須在 CollapseService 第一次看到該 session 之前（選門的同一個 tick 之前）加入。</li>
 * <li>journal flush 探針：{@code SessionJournalWindowFaultMixin} 在每次 journal flush 開頭呼叫 {@link #beforeJournalFlush}，探針丟出例外即讓
 *     那次寫出受控失敗。只由 M5 的 test 設定（{@code M5GameTestSupport.installFlushProbe}），並在所有路徑上以 TestHooks 清除。</li>
 * <li>journal 寫出稽核（F4a-0 m-1）：{@code SessionJournalWriteCountMixin} 在 journal 正式檔每一次實際寫出之前呼叫
 *     {@link #beforeJournalWrite}。稽核由 {@code M5GameTestSupport.afterAutosaveClear} 在窗口開始時啟動，hooks 釋放時移除。</li>
 * </ul>
 */
public final class M5TestHooks {
    private M5TestHooks() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final Set<UUID> COLLAPSE_SUSPENDED = ConcurrentHashMap.newKeySet();
    static volatile Consumer<SessionRecoveryState> flushProbe;
    static volatile JournalWriteAudit journalAudit;

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
        var audit = journalAudit;
        if (audit != null) audit.record(state, false);
        var probe = flushProbe;
        if (probe != null) probe.accept(state);
    }

    /** {@code SessionJournalWriteCountMixin}：journal 正式檔每一次實際寫出之前呼叫（不論來源）。沒有進行中的稽核時不做任何事；不丟出例外。 */
    public static void beforeJournalWrite(SessionRecoveryState state) {
        var audit = journalAudit;
        if (audit != null) audit.record(state, true);
    }

    /** 開始稽核 journal 寫出（同一時間只有一個）；hooks 釋放時，若仍是它就移除。 */
    static JournalWriteAudit startJournalAudit(MinecraftServer server, M2CorridorGameTests.TestHooks hooks) {
        var audit = new JournalWriteAudit(server);
        journalAudit = audit;
        hooks.add(() -> { if (journalAudit == audit) journalAudit = null; });
        return audit;
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
