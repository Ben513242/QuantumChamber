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
 * 都不受影響。屬性存在但設定不合法時，在 {@code onInitialize} 丟出（開服之前就失敗）；窗口表（{@link M5CollapseWindows}）也在這時建好，不合法的列同樣在開服之前失敗
 * （F6-2d Step 0）。
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
 *     W7 與其餘的重啟變體）、{@link M5CollapseRetryChains}（F6-2d-1：重試與續行跨重啟，W8、W9 與它的兩個變體、AUTHORITY_UNREADABLE 的重啟後重試；H7 的 w8late、unreadablelate、waitinglate）、
 *     {@link M5CollapseDoorChains}（F6-2d-2 B1：PREPARE 的原艙門跨重啟，waitingdoor、preparedoor；F6-2d-2 B2：SEALED 的原艙門跨重啟，sealclose、sealdoorway、sealmembrane、sealmembranejoined；與 RetryChains 共用基底
 *     {@link M5CollapseRestartChains}）；投影 chunk 的載入
 *     與 load-sync 的觀察在 {@link M5CollapseProjectionLoad}。{@link M5CollapseUnloadChain}（H5：chunk 卸載的 livelock 防護，不經塌縮流程）。
 *     {@link M5CollapseTemplateRaceChain}（H6：結構範本快取的同時使用，不經塌縮流程）。{@link M5CollapseUnsupportedChain}（F6-2e-1：W10，M4 時期的 Nether／End 收據跨真重啟；
 *     不經 {@link M5CollapseRestartChains} 的 crash 骨架）。{@link M5CollapseInitChain}（F6-2e-2：W11，M4 時期的 schema 3 存檔第一次以 M5 開服、初始化只建立一個 ledger 就 crash；
 *     setup、crash、兩次重啟四個 phase，也不經那份骨架）。{@link M5CollapseConvergenceChain}（F6-2e-3：W12，catalog 配置與 discovery 追加的讀回失敗在同一個 JVM 收斂之後 crash；
 *     繼承 {@link M5CollapseRestartChains}，crash phase 另寫，restart 用 {@link M5CollapseDormantChains#restart}）。</li>
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
        // H6（spec §4 H6，不是 crash 窗口）：結構範本快取的同時使用。物化一個 U，兩個世界在同一個呼叫裡把同一批 jigsaw 結構的 start chunk 推到 STRUCTURE_STARTS，必須都完成、正常停服。
        table.put("h6race-starts", run -> new M5CollapseTemplateRaceChain(run).phase());
        // F6-2d-1：重試與續行跨重啟（M5CollapseRetryChains）。W8（spec §12）：DORMANT 的收據跨重啟續行到 READY（crash 前參與者在艙內、沒有觸發；restart 開服之後第一次觀察就提交）。
        table.put("w8-crash", run -> new M5CollapseRetryChains(run).w8Crash());
        table.put("w8-restart", run -> new M5CollapseRetryChains(run).w8Restart("w8-crash"));
        // W9（spec §12）：物化失敗 → DISABLED、SEALED(MATERIALIZE_FAILED) → 重啟 → 開服不停止 → 重試成功。
        table.put("w9-crash", run -> new M5CollapseRetryChains(run).materializeFailureCrash(CrashWindow.MATERIALIZE_SEALED));
        table.put("w9-restart", run -> new M5CollapseRetryChains(run).w9Restart("w9-crash"));
        // W9 的變體 (a)（F4a I-1 的跨重啟版）：重啟之後的重試先因另一個 session 的 allocation 讀回失敗封存為 ALLOCATION_IO_FAILED，之後的重試仍把 DISABLED 視為 §8.3 已完成。
        table.put("w9io-crash", run -> new M5CollapseRetryChains(run).materializeFailureCrash(CrashWindow.MATERIALIZE_SEALED));
        table.put("w9io-restart", run -> new M5CollapseRetryChains(run).w9AllocationRetryRestart("w9io-crash"));
        // W9 的變體 (b)：§8.3 的 DISABLED 寫入之後、SEALED 之前 crash（開服時 COLLAPSING＋DISABLED），重啟之後的第一次 RESOLVE 就是 §8.3 的重試。
        table.put("w9cd-crash", run -> new M5CollapseRetryChains(run).materializeFailureCrash(CrashWindow.CATALOG_DISABLED));
        table.put("w9cd-restart", run -> new M5CollapseRetryChains(run).w9CollapsingDisabledRestart("w9cd-crash"));
        // F6-2-07：discovery 讀取的 IO 失敗 → SEALED(AUTHORITY_UNREADABLE)，同一個 instance 內不重試 → 重啟之後重試成功。
        table.put("unreadable-crash", run -> new M5CollapseRetryChains(run).unreadableCrash());
        table.put("unreadable-restart", run -> new M5CollapseRetryChains(run).unreadableRestart("unreadable-crash"));
        // H7（spec v5.9 §7.1、§4 H7）：開服時不預先載入原艙附近，原艙卸載之後參與者才 JOIN 在艙內；不離開再進入就續行（DORMANT）／重試（SEALED(AUTHORITY_UNREADABLE)）。
        table.put("w8late-crash", run -> new M5CollapseRetryChains(run).w8Crash());
        table.put("w8late-restart", run -> new M5CollapseRetryChains(run).w8LateRestart("w8late-crash"));
        table.put("unreadablelate-crash", run -> new M5CollapseRetryChains(run).unreadableLateCrash());
        table.put("unreadablelate-restart", run -> new M5CollapseRetryChains(run).unreadableLateRestart("unreadablelate-crash"));
        // H7 的 WAITING 版：開服時 COLLAPSING、原艙附近沒有載入、參與者已在艙內；閘門第一次評估遇到來源艙還沒載入時不算評估過，不離開再進入就進 PREPARE。
        table.put("waitinglate-crash", run -> new M5CollapseRetryChains(run).waitingLateCrash());
        table.put("waitinglate-restart", run -> new M5CollapseRetryChains(run).waitingLateRestart("waitinglate-crash"));
        // F6-2d-2 B1：PREPARE 的原艙門跨重啟（M5CollapseDoorChains；兩條的 crash 相同，都在 READY_HELD：PASSAGE_READY 的提交被暫緩、門是 sealed 量子膜時 halt）。
        // F6-2-08（waitingdoor）：重啟之後閘門不成立、停在 WAITING，下一個 tick 把帶量子膜的門改為一般開門。F6-2-10（preparedoor）：PREPARE 中重啟、艙內有非參與者時維持一般開門，他離開之後重新套用。
        table.put("waitingdoor-crash", run -> new M5CollapseDoorChains(run).readyHeldCrash());
        table.put("waitingdoor-restart", run -> new M5CollapseDoorChains(run).waitingDoorRestart("waitingdoor-crash"));
        table.put("preparedoor-crash", run -> new M5CollapseDoorChains(run).readyHeldCrash());
        table.put("preparedoor-restart", run -> new M5CollapseDoorChains(run).prepareDoorRestart("preparedoor-crash"));
        // F6-2d-2 B2（F6-2-09）：SEALED(AUTHORITY_UNREADABLE) 的寫入已確認、sealCommitted 的關門之前 crash。sealclose：開服時門開著，重啟之後只關一次，參與者打開之後不再關回去；
        // sealdoorway：門關著、門口有盔甲座，重啟之後改為一般開門、不關在盔甲座上。
        table.put("sealclose-crash", run -> new M5CollapseDoorChains(run).plainDoorSealedCrash());
        table.put("sealclose-restart", run -> new M5CollapseDoorChains(run).plainDoorSealedRestart("sealclose-crash"));
        table.put("sealdoorway-crash", run -> new M5CollapseDoorChains(run).doorwaySealedCrash());
        table.put("sealdoorway-restart", run -> new M5CollapseDoorChains(run).doorwaySealedRestart("sealdoorway-crash"));
        // F6-2d-2 B2（F6-2-11）：投影寫入失敗 → SEALED(PROJECTION_FAILED) 的寫入已確認、門是 sealed 量子膜時 crash（兩條的 crash 相同）。sealmembrane：開服時沒有參與者，非參與者使用門不取消關門，
        // 關門清掉量子膜，參與者 JOIN 的邊緣重試；sealmembranejoined：參與者開服時就在艙內，重試之後關門由 NORMALIZE 取代，門一直沒有關上。
        table.put("sealmembrane-crash", run -> new M5CollapseDoorChains(run).membraneSealedCrash());
        table.put("sealmembrane-restart", run -> new M5CollapseDoorChains(run).membraneSealedRestart("sealmembrane-crash"));
        table.put("sealmembranejoined-crash", run -> new M5CollapseDoorChains(run).membraneSealedCrash());
        table.put("sealmembranejoined-restart", run -> new M5CollapseDoorChains(run).membraneSealedJoinedRestart("sealmembranejoined-crash"));
        // F6-2e-1（spec §12 W10，F6-2-04）：M4 時期的 Nether／End 收據（schema 3 的 journal：Nether 的 live NEW、Nether 的 DORMANT SOURCE、End 的 DORMANT NEW），M5 第一次處理之前 crash；
        // restart：NEW 經 M4 的保留式 recovery → DORMANT → 取消，END 開服之後就取消，SOURCE 正常完成。
        table.put("w10-crash", run -> new M5CollapseUnsupportedChain(run).crash());
        table.put("w10-restart", run -> new M5CollapseUnsupportedChain(run).restart("w10-crash"));
        // F6-2e-2（spec §12 W11，F6-2-05）：M4 時期的 schema 3 存檔（discovery 非空）第一次以 M5 開服，M5 authority 初始化只建立了 allocation ledger 就 crash；第一次重啟時讀既有的 ledger 失敗、
        // 不建立缺少的那一個，第二次重啟補建、不被判為遺失，之後第一次 NEW 照常到達 READY、跨越、終局。
        table.put(M5CollapseInitChain.SETUP, run -> new M5CollapseInitChain(run).setup());
        table.put(M5CollapseInitChain.CRASH, run -> new M5CollapseInitChain(run).crash());
        table.put(M5CollapseInitChain.UNREADABLE, run -> new M5CollapseInitChain(run).unreadable());
        table.put(M5CollapseInitChain.RESTART, run -> new M5CollapseInitChain(run).restart());
        // F6-2e-3（spec §12 W12，F6-2-06）：catalog 配置的讀回失敗 → roll-forward、確認之前不物化 → 重試時收斂（同一個 U）→ discovery 追加的讀回失敗 → roll-forward → 重試時收斂、
        // 已落地的 record 不移除 → 收斂之後 crash；restart 同 W5，續行到 READY、跨越、終局。
        table.put(M5CollapseConvergenceChain.CRASH, run -> new M5CollapseConvergenceChain(run).crash());
        table.put(M5CollapseConvergenceChain.RESTART, run -> new M5CollapseDormantChains(run).restart(CrashWindow.DISCOVERY_CONVERGED, M5CollapseConvergenceChain.CRASH));
        return Collections.unmodifiableMap(table);
    }

    private static void dormantNewChain(Map<String, PhaseFactory> table, String chain, CrashWindow window) {
        table.put(chain + "-crash", run -> new M5CollapseDormantChains(run).crash(window));
        table.put(chain + "-restart", run -> new M5CollapseDormantChains(run).restart(window, chain + "-crash"));
    }

    @Override public void onInitialize() {
        var config = M5CollapseRun.Configuration.read(PHASES.keySet());
        if (config == null) return;
        // F6-2d Step 0（F6-2c quality Minor 6）：每個 phase 都在開服之前建好窗口表（class init），不合法的列在這裡就丟出（開服之前失敗），不等到 step 執行時。
        M5CollapseWindows.windows();
        new M5CollapseRun(config, List.copyOf(PHASES.keySet())).start(PHASES.get(config.phase()));
    }
}
