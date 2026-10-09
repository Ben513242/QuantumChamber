package dev.quantumchamber.gametest;

import dev.quantumchamber.gametest.M5CollapseRun.PhaseFactory;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.api.ModInitializer;

/**
 * 僅 testmod（M5 F6-1；F6-2a 拆檔）：跨 JVM 的塌縮 crash／restart probe（spec §12 的 crash 窗口）的 entrypoint 與 phase 表。預設關閉：沒有 system property
 * {@code quantumchamber.m5collapse.phase} 時不註冊任何 listener、不記 log，也不建立 runner 與 chain；default、legacy、M5 GameTest、main-only 與 M4 recovery run
 * 都不受影響。屬性存在但設定不合法時，在 {@code onInitialize} 丟出（開服之前就失敗）。
 * <p>每個 phase 一個 JVM，由 {@code scripts/verification/m5-collapse-probe.ps1} 經 Gradle 的 {@code runM5Collapse} 啟動。root 是 nonce-owned 的
 * {@code run/m5-collapse-<nonce>}，必須等於 cwd；evidence 固定在 {@code <repo>/.superpowers/sdd/2026-10-01-m5-collapse-passage/collapse-<nonce>/<phase>}，
 * 已有 receipt 時拒絕（禁止在同一個 evidence 重跑）。
 * <p>類別的分工：
 * <ul>
 * <li>{@link M5CollapseRun}：一個 phase 的生命週期（{@code Phase}、{@code Step}、{@code guarded}、{@code expect}）、receipt（{@code final.json}、{@code halt.json}）、
 *     crash 點與 probe 端安裝的 fault（停服時逐一移除）。</li>
 * <li>{@link M5CollapseWindows}：窗口表，每個 crash 窗口一列：crash 點的條件、halt 之前的取證、restart 開服時的預期與 PREPARE 怎麼完成。</li>
 * <li>{@link M5CollapseFixture}：原艙、參與者（清單與它的子集 {@code Who}）、收據與這條 chain 的 id；{@link M5CollapsePassage}：restart 共用的 RESOLVE → 跨越 → 終局
 *     step（F6-2c 起可以只取 resolve、ready、cross 其中幾段）、每個 tick 的不變量與 spec §12 的終局斷言。</li>
 * <li>chain：{@link M5CollapseDormantChains}（DORMANT NEW 收據，W2～W6；restart 也用於 W6x）、{@link M5CollapseLiveChain}（W1）、
 *     {@link M5CollapseRevisitChains}（EXISTING 再造訪：W6x 的 crash、退化的 setup 與 restart）、{@link M5CollapsePassageChains}（F6-2c：通道與終局的重啟窗口，
 *     W7 與其餘的重啟變體）；投影 chunk 的載入與 load-sync 的觀察在 {@link M5CollapseProjectionLoad}。{@link M5CollapseUnloadChain}（H5：chunk 卸載的 livelock 防護，
 *     不經塌縮流程）。</li>
 * </ul>
 * phase 以 {@link #PHASES} 表示：名稱 → {@link PhaseFactory}（設定 {@code M5CollapseRun.Configuration} 與 factory 的型別都在 runner，F6-2c Step 0：runner 不依賴 entrypoint）。新增 phase 要同時改三份表：這裡的 {@link #phases}、{@code m5-collapse-probe.ps1} 的 chain 表
 * （{@code $chains}：同名的 phase、預期的結束與 halt 的窗口），以及 {@code run-all.ps1} 的 {@code $expectedCollapse}（{@code scripts/verification/README.md}
 * 「M5 collapse probe」）。receipt 記下這張表的全部 phase 名稱（{@code phaseTable}）與這個 phase 的結束（{@code ending}），{@code m5-collapse-probe.ps1} 逐一與
 * {@code $chains} 比對，任何一邊多出或少了 phase、或結束不同都判 FAIL。
 */
public final class M5CollapseRecoveryProbe implements ModInitializer {
    /** phase 表：phase 名稱 → 建立這個 phase 的 factory（{@link PhaseFactory}，依 chain 的順序）。 */
    static final Map<String, PhaseFactory> PHASES = phases();

    private static Map<String, PhaseFactory> phases() {
        var table = new LinkedHashMap<String, PhaseFactory>();
        // W1（spec §12）：live session 選門之後，MEASURED→COLLAPSING 剛提交、返還之前 crash；重啟之後走廊 space 已重建，不經請求就返還、清 lease，再推進到終局。
        table.put("w1-crash", run -> new M5CollapseLiveChain(run).crash());
        table.put("w1-restart", run -> new M5CollapseLiveChain(run).restart("w1-crash"));
        // W2、W3、W5（spec §12）：DORMANT 的 NEW 收據，參與者由艙外進入觸發塌縮，在 RESOLVE 的窗口 crash；重啟之後推進到 PASSAGE_READY、跨越與 (b) 終局。
        dormantNewChain(table, "w2", CrashWindow.ALLOCATION_APPENDED);
        dormantNewChain(table, "w3", CrashWindow.CATALOG_ALLOCATED);
        dormantNewChain(table, "w5", CrashWindow.DISCOVERY_APPENDED);
        // W4、W4b、W6（spec §12）：同樣的觸發，crash 在 PREPARE 的投影：寫到 Controller 那一格（W4，halt 前存檔）、目的 chunks 的 checkpoint 之前（W4b）、COMPLETE 之後（W6）。
        dormantNewChain(table, "w4", CrashWindow.PROJECTION_CELL_WRITTEN);
        dormantNewChain(table, "w4b", CrashWindow.DESTINATION_CHECKPOINT);
        dormantNewChain(table, "w6", CrashWindow.PROJECTION_COMPLETE);
        // W6 的 EXISTING 再造訪：先以 NEW 建好 U 的投影，之後 EXISTING 收據，在 REPAIR 寫了格、checkpoint 之前 crash。
        table.put("w6x-crash", run -> new M5CollapseRevisitChains(run).existingRevisitCrash());
        table.put("w6x-restart", run -> new M5CollapseDormantChains(run).restart(CrashWindow.PROJECTION_REPAIRED, "w6x-crash"));
        // P-DEGRADED 的跨 JVM 版本（不是 crash 窗口）：EXISTING 再造訪提交 COLLAPSING 的同一個 tick 正常停服；下一次開服前移走 projection ledger。
        table.put("deg-setup", run -> new M5CollapseRevisitChains(run).degradedSetup());
        table.put("deg-restart", run -> new M5CollapseRevisitChains(run).degradedRestart("deg-setup"));
        // H5（spec §4 H5，不是 crash 窗口）：chunk 卸載的 livelock 防護。被取回的 holder 在 refCount 歸零之後照常卸載、存檔（下一個 JVM 以 raw MCA 讀回）；殭屍＋停服時仍在載入，必須正常停服。
        table.put("unload-takeback", run -> new M5CollapseUnloadChain(run).takeBack());
        table.put("unload-shutdown", run -> new M5CollapseUnloadChain(run).shutdown("unload-takeback"));
        // F6-2c：通道與終局的重啟窗口（M5CollapsePassageChains）。W7（spec §12）：PASSAGE_READY 期間兩人中選門者已跨過，重啟之後通道在開服時結束，同一個 server 內原艙再開新 session。
        table.put("w7-crash", run -> new M5CollapsePassageChains(run).w7Crash());
        table.put("w7-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.CROSSED_COMMITTED, "w7-crash"));
        // F6-2-16：PASSAGE_READY 提交之後、門改為 passable 之前 crash。
        table.put("readycommit-crash", run -> new M5CollapsePassageChains(run).readyCommitCrash());
        table.put("readycommit-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.READY_COMMITTED, "readycommit-crash"));
        // F6-2-13：通道的終局之後、改門之前 crash；重啟之後 load-sync 的刷新排入找不到所屬 session 的量子膜的正規化。
        table.put("terminaldoor-crash", run -> new M5CollapsePassageChains(run).terminalDoorCrash());
        table.put("terminaldoor-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.PASSAGE_TERMINATED, "terminaldoor-crash"));
        // F6-2-17：玩家已在目的世界、Crossed 的寫出沒有確認時 crash。
        table.put("crossedpending-crash", run -> new M5CollapsePassageChains(run).crossedPendingCrash());
        table.put("crossedpending-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.CROSSED_UNCONFIRMED, "crossedpending-crash"));
        // F6-2-12：SEALED(TRANSFER_RECOVERY_REQUIRED) 跨重啟（永久原因、不重試），與 TRR 的寫入被拒（pendingRecovery）期間重啟。
        table.put("trr-crash", run -> new M5CollapsePassageChains(run).trrCrash());
        table.put("trr-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.RECOVERY_SEALED, "trr-crash"));
        table.put("trrpending-crash", run -> new M5CollapsePassageChains(run).trrPendingCrash());
        table.put("trrpending-restart", run -> new M5CollapsePassageChains(run).restart(CrashWindow.RECOVERY_REJECTED, "trrpending-crash"));
        return Collections.unmodifiableMap(table);
    }

    private static void dormantNewChain(Map<String, PhaseFactory> table, String chain, CrashWindow window) {
        table.put(chain + "-crash", run -> new M5CollapseDormantChains(run).crash(window));
        table.put(chain + "-restart", run -> new M5CollapseDormantChains(run).restart(window, chain + "-crash"));
    }

    @Override public void onInitialize() {
        var config = M5CollapseRun.Configuration.read(PHASES.keySet());
        if (config == null) return;
        new M5CollapseRun(config, List.copyOf(PHASES.keySet())).start(PHASES.get(config.phase()));
    }
}
