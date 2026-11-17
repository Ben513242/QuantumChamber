package dev.quantumchamber.gametest;

import java.util.UUID;
import net.fabricmc.api.ModInitializer;

/**
 * 僅 testmod（M7 T4 Phase B1、B2a）：Windows 原生跨 JVM 鏈的 entrypoint。只有 system property {@code quantumchamber.m7home.*}（由 build.gradle 的
 * {@code m7HomeProbe} run 自 {@code -Pm7Home*} 轉入）存在時才建立 runner、註冊 listener；其他 run 不受影響。屬性存在但不合法時開服前就丟出。
 * close 矩陣 {@code close-{ordinary|sealed|passable}-{cut}} 的 phase 表、fixture 與斷言在 {@link M7HomeClosurePhases}；B2a 的其餘鏈
 * （{@code restart-*}、{@code receipt-*}）在 {@link M7HomeChainPhases}。cut 點由 {@code mixin.M7HomeCloseWindowMixin} 與
 * {@code mixin.M7HomeReceiptWindowMixin} 呼叫 {@link #reached}。
 */
public final class M7HomeProbe implements ModInitializer {
    /** 一個 phase 的 runner（close 矩陣或 B2a 的其餘鏈）；只在 server thread 記錄事件。 */
    interface Phase {
        void start();
        void reached(String event, UUID session, Object detail);
    }

    private static volatile Phase active;

    @Override public void onInitialize() {
        var config = M7HomeClosurePhases.Configuration.read();
        if (config == null) return;
        Phase run = M7HomeChainPhases.NAMES.contains(config.row()) ? new M7HomeChainPhases.Run(config) : new M7HomeClosurePhases.Run(config);
        active = run;
        run.start();
    }

    /**
     * 正式回程終局 owner 走到一個 cut 點或交接事件（{@code mixin.M7HomeCloseWindowMixin}、{@code mixin.M7HomeReceiptWindowMixin}）。probe 沒有啟用時不做任何事；
     * 啟用時只在 server thread 記錄，crash phase 走到預定的 cut 時寫 halt receipt 並 {@code Runtime.halt}。不丟出例外到正式程式。
     */
    public static void reached(String event, UUID session, Object detail) {
        var run = active;
        if (run != null) run.reached(event, session, detail);
    }
}
