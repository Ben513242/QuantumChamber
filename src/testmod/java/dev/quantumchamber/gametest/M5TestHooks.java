package dev.quantumchamber.gametest;

import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;

/**
 * M5 GameTest 專用的 testmod hook：mixin 讀取的靜態狀態（F4a-0 從 {@code M4CandidateDoorGameTests} 搬來）。release JAR 不含此類。
 * <ul>
 * <li>collapse-suspended（M5 plan 決策 7，{@code CollapseSuspendMixin} 讀取）：被加入的 session，{@code CollapseService} 完全不推進，
 *     M4 的 GameTest 因此維持「MEASURED 會一直等待」的原斷言。不變量：同一個 JVM 內不得清除（沒有移除的 API）；default run 中每一個
 *     會產生 MEASURED 的 test 都必須在 CollapseService 第一次看到該 session 之前（選門的同一個 tick 之前）加入。</li>
 * <li>journal flush 探針：{@code SessionJournalWindowFaultMixin} 在每次 journal flush 開頭呼叫 {@link #beforeJournalFlush}，探針丟出例外即讓
 *     那次寫出受控失敗。只由 M5 的 test 設定（{@code M5GameTestSupport.installFlushProbe}），並在所有路徑上以 TestHooks 清除。</li>
 * </ul>
 */
public final class M5TestHooks {
    private M5TestHooks() {}

    private static final Set<UUID> COLLAPSE_SUSPENDED = ConcurrentHashMap.newKeySet();
    static volatile Consumer<SessionRecoveryState> flushProbe;

    /** {@code CollapseSuspendMixin}：這個 session 是否被暫停（system property {@code quantumchamber.test.collapseSuspended=all} 暫停全部）。 */
    public static boolean collapseSuspended(UUID session) {
        return "all".equals(System.getProperty("quantumchamber.test.collapseSuspended")) || COLLAPSE_SUSPENDED.contains(session);
    }

    /** 暫停這個 session 的塌縮流程（不可撤銷）。 */
    static void suspendCollapse(UUID session) { COLLAPSE_SUSPENDED.add(Objects.requireNonNull(session, "session")); }

    /** {@code SessionJournalWindowFaultMixin}：每次 journal flush 開頭呼叫；只在 server thread 上把 journal 交給目前的探針。 */
    public static void beforeJournalFlush(SessionRecoveryState state, MinecraftServer server) {
        var probe = flushProbe;
        if (probe != null && server.isOnThread()) probe.accept(state);
    }
}
