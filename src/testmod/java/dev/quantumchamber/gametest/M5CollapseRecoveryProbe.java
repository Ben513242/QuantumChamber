package dev.quantumchamber.gametest;

import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <li>{@link M5CollapseFixture}：原艙、參與者（清單）、收據與這條 chain 的 id；{@link M5CollapsePassage}：restart 共用的 RESOLVE → 跨越 → 終局 step、每個 tick 的
 *     不變量與 spec §12 的終局斷言。</li>
 * <li>chain：{@link M5CollapseDormantChains}（DORMANT NEW 收據，W2～W6；restart 也用於 W6x）、{@link M5CollapseLiveChain}（W1）、
 *     {@link M5CollapseRevisitChains}（EXISTING 再造訪：W6x 的 crash、退化的 setup 與 restart）；投影 chunk 的載入與 load-sync 的觀察在
 *     {@link M5CollapseProjectionLoad}。</li>
 * </ul>
 * phase 以 {@link #PHASES} 表示：名稱 → {@link PhaseFactory}。新增 phase 要同時改三份表：這裡的 {@link #phases}、{@code m5-collapse-probe.ps1} 的 chain 表
 * （{@code $chains}：同名的 phase、預期的結束與 halt 的窗口），以及 {@code run-all.ps1} 的 {@code $expectedCollapse}（{@code scripts/verification/README.md}
 * 「M5 collapse probe」）。receipt 記下這張表的全部 phase 名稱（{@code phaseTable}）與這個 phase 的結束（{@code ending}），{@code m5-collapse-probe.ps1} 逐一與
 * {@code $chains} 比對，任何一邊多出或少了 phase、或結束不同都判 FAIL。
 */
public final class M5CollapseRecoveryProbe implements ModInitializer {
    /** phase 表：phase 名稱 → 建立這個 phase 的 factory（依 chain 的順序）。 */
    static final Map<String, PhaseFactory> PHASES = phases();

    /** 在 {@code onInitialize} 呼叫：建立這個 phase 的 chain，回傳它的 {@link M5CollapseRun.Phase}。 */
    @FunctionalInterface interface PhaseFactory { M5CollapseRun.Phase create(M5CollapseRun run); }

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
        return Collections.unmodifiableMap(table);
    }

    private static void dormantNewChain(Map<String, PhaseFactory> table, String chain, CrashWindow window) {
        table.put(chain + "-crash", run -> new M5CollapseDormantChains(run).crash(window));
        table.put(chain + "-restart", run -> new M5CollapseDormantChains(run).restart(window, chain + "-crash"));
    }

    @Override public void onInitialize() {
        var config = Configuration.read(PHASES.keySet());
        if (config == null) return;
        new M5CollapseRun(config, List.copyOf(PHASES.keySet())).start(PHASES.get(config.phase()));
    }

    /** probe 的設定（system property {@code quantumchamber.m5collapse.*}，由 build.gradle 的 m5Collapse run 從 {@code -Pm5Collapse*} 轉入）。 */
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
                var evidence = root.getParent().getParent().resolve(M5CollapseRun.OWNER).resolve("collapse-" + nonce).resolve(phase);
                if (!evidence.toRealPath().equals(evidence)) throw new IllegalStateException("M5 collapse probe 的 evidence 必須是 canonical 路徑：" + evidence);
                for (var existing : List.of("final.json", "halt.json", "events.jsonl", M5CollapseRun.RECEIPT_NBT)) {
                    if (Files.exists(evidence.resolve(existing))) throw new IllegalStateException("phase evidence 已有 " + existing + "，禁止重跑：" + evidence);
                }
                return new Configuration(phase, nonce, startup, root, evidence);
            } catch (IOException failure) {
                throw new UncheckedIOException("M5 collapse probe 的 root 或 evidence 不存在：" + supplied, failure);
            }
        }
    }
}
