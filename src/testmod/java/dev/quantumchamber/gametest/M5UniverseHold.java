package dev.quantumchamber.gametest;

import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseBackoff;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestException;
import net.minecraft.test.GameTestState;
import net.minecraft.test.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 run 的 catalog baseline、完成判定與收斂 hold 的唯一 owner（F6-3c-2，F6-3c-1 quality review「給 F6-3c-2」；由 {@code M5UniverseGameTests} 純搬移抽出）：
 * 每個 test 的第一個動作 {@link #begin}、最後一個動作 {@link #complete} 或 {@link #awaitConvergedThenComplete}，test 由原版初始化之前的
 * {@link #holdBeforeBegin}（testmod 的 {@code M5ConvergenceHoldMixin} 呼叫），以及 suite 結束時的 {@link #registerSuiteEnd}（{@code M4GameTestBoundaryProbe}
 * 在 M5 run 呼叫）。它們共用的 static 狀態（baseline、{@link #lastBegun}、after-pass）都只在這個類別。寫法的規則見 {@link M5UniverseGameTests} 的類別說明。
 */
public final class M5UniverseHold {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");

    private M5UniverseHold() { }

    /** begin 之後、還沒通過的 test。失敗的 test 留在這裡，下一個 test 的 begin 失敗時列出。 */
    private static final Map<TestContext, Baseline> ACTIVE = new IdentityHashMap<>();
    /** complete 未通過時最後一次記錄的狀態；狀態改變時才再記一行，重試不洗版。 */
    private static final Map<TestContext, String> UNMET = new IdentityHashMap<>();
    /** complete 的判定丟過非 GameTestException 的例外：之後每次呼叫都直接丟同一個 GameTestException。 */
    private static final Map<TestContext, GameTestException> BROKEN = new IdentityHashMap<>();
    private static final AtomicBoolean SUITE_END_REGISTERED = new AtomicBoolean();
    /**
     * {@link #holdBeforeBegin} 最多等的 tick 數（F6-3b B1）：權威寫出失敗之後的第一次重試在退避 200 tick 之後（{@code CollapseBackoff.INITIAL_TICKS}），再失敗時加倍為
     * 400 tick，所以從失敗起算 600 tick 內有兩次重試。等待在前一個 test 失敗之後才開始，實際涵蓋得更寬。
     */
    private static final int HOLD_TICKS = 3 * CollapseBackoff.INITIAL_TICKS;
    /**
     * 最後一個呼叫 {@link #begin} 的 test（F6-3b fix round 1）。{@link #holdBeforeBegin} 以它在原版的結果（{@code GameTestState.isPassed()}）判斷「前一個 test」
     * 是否通過，不以 {@link #finish} 寫出的 PASS 為準：finish 之後的同一個 tick，原版仍可能因 tickLimit 或其他 callback 判它失敗。平台略過的
     * {@code _windows} test 不呼叫 begin，不會取代它。所有存取都在 server thread。
     */
    private static Begun lastBegun;
    /**
     * {@link #holdBeforeBegin} 發現「前一個 test 通過之後仍留下未確認的權威」時記下的失敗原因（F6-3b fix round 1）。下一個呼叫 {@link #begin} 的 test 取走它，
     * 並以它失敗（通常就是 hold 等的那個 test；它是平台略過的 test 時，是再下一個）。F6-3c-1（F6-3b fix round 1 quality Minor 1）：原因在 hold <b>結束</b>時才寫成，帶 hold 的結果
     * （converged、timeout 或 error）與 heldTicks（{@link #endHold}）；原本在 hold 開始時就組好，看不到結果，逾時時「照常收斂」也不成立。
     */
    private static String afterPassFailure;
    /**
     * 這次 hold 開始時發現的 after-pass（{@link #startHold}）；hold 結束時由 {@link #endHold} 補上結果、寫成 {@link #afterPassFailure}。沒有時為 null（F6-3c-1）。F6-3c-2（F6-3c-1 quality
     * Minor 4）起存結構化的 {@link AfterPass}，訊息只在 {@link #endHold} 一次組成（原本前半段字串在這裡、括號的另一半在 endHold）。
     */
    private static AfterPass afterPassStarted;

    /**
     * 由 {@code M4GameTestBoundaryProbe.register()} 在 M5 run 呼叫（取代 default run 的 boundary probe）：{@code SERVER_STOPPED} 時，
     * 也就是整個 suite 結束、原生存檔之後，寫一行 {@code M5_UNIVERSE_SUITE_END records=… digest=… converged=…}。gate 核對它等於最後
     * 一個 test 的 after，最後一個 test 完成之後的任何 catalog 改動都會被抓到。
     */
    static void registerSuiteEnd() {
        if (!SUITE_END_REGISTERED.compareAndSet(false, true)) return;
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            try {
                var catalog = UniverseRegistryState.get(server);
                var records = catalog.records();
                boolean converged = !catalog.isDirty() && records.equals(catalog.flushedRecords());
                LOGGER.info("M5_UNIVERSE_SUITE_END records={} digest={} converged={} unfinished={}",
                        records.size(), digest(records), converged, unfinished());
            } catch (RuntimeException failure) {
                LOGGER.error("M5_UNIVERSE_SUITE_END_FAILED catalog 讀取失敗", failure);
            }
        });
    }

    /**
     * 每個 M5 Universe test 的第一個動作：確認在 M5 run、權威已初始化而且已收斂，再記下 catalog baseline。F6-3b 起，原版初始化這個 test 之前，
     * {@link #holdBeforeBegin} 已有界地等過前一個 test 留下的未確認寫入收斂；等不到時這裡照舊失敗。F6-3b fix round 1 起，hold 發現前一個 test <b>通過</b>之後
     * 仍留下未確認的權威時（{@link #afterPassFailure}，F6-3c-1 起帶 hold 的結果與 heldTicks），這裡以那個原因失敗；hold 照常嘗試收斂，收斂的話再下一個 test 不受影響。這個 test 在 M5 run 的判定之後、其他斷言之前先記為
     * {@link #lastBegun}：它在這裡失敗時，下一個 test 的 hold 把它當成失敗的前一個 test，不會再指控一次。
     */
    static void begin(TestContext context, String name) {
        // 決策 6：default run 不得執行這個類別的 test。被誤放進 default run 時，在碰 catalog 之前就失敗。
        context.assertTrue(Boolean.getBoolean("quantumchamber.gametest.m5Only"), "M5 Universe GameTest 只能在 gameTestM5 run 執行：" + name);
        lastBegun = new Begun(name, M5GameTestSupport.gameTestState(context));
        String afterPass = afterPassFailure;
        afterPassFailure = null;
        context.assertTrue(!ACTIVE.containsKey(context), "M5 catalog baseline 不可重複：" + name + "；還沒通過的 test：" + unfinished());
        var server = context.getWorld().getServer();
        var authorities = CollapseAuthorities.get(server);
        context.assertTrue(authorities.initialized(), "M5 run 的世界必須有已初始化的 M5 authority：" + name);
        if (afterPass != null) {
            throw new GameTestException(afterPass + "；" + name + " 因此在 begin 失敗（目前未確認：" + unconfirmedAuthorities(authorities) + "）");
        }
        context.assertTrue(converged(authorities), "開始時 catalog 與 M5 權威必須已收斂（前一個 test 違反衛生規則；初始化之前最多等 " + HOLD_TICKS
                + " tick）：" + name + "；未確認：" + unconfirmedAuthorities(authorities) + "；還沒通過的 test：" + unfinished());
        var records = UniverseRegistryState.get(server).records();
        ACTIVE.put(context, new Baseline(name, records, digest(records)));
        LOGGER.info("M5_UNIVERSE_BEGIN name={} records={} digest={}", name, records.size(), digest(records));
    }

    /**
     * F6-3b B1（盤點 U6）：一個 test 失敗不再讓後面的 test 連坐。由 testmod 的 {@code M5ConvergenceHoldMixin} 在 M5 run 的每個 test 由原版初始化之前（每個 tick，直到第一次回
     * false；之後這個 test 不再呼叫）呼叫，回 true 表示這個 tick 延後、不初始化（結構還沒放、test 方法還沒執行、tickLimit 還沒起算）。
     * <ul>
     * <li>M5 權威沒有初始化，或四個權威都已收斂（{@link #converged}，純查詢，不呼叫任何收斂）時立刻放行，行為與原版相同。前一個 test 通過時，它在 {@link #finish}
     *     完成的那一刻一定已收斂（PASS 的條件含收斂）；但那之後到這裡之間（同一個 tick 其他的收尾、batch 結束時取消強制載入、之後 tick 的 production）仍可能留下
     *     未確認的寫入，這一點沒有被強制，所以由下一點的第二種情況把它判為失敗。</li>
     * <li>有未確認的寫入時：每個 tick 以 {@link #convergeAll} 對四個權威各呼叫一次 {@code convergeIfNeeded}（退避照 production，退避期內不重寫），收斂之後才放行，
     *     之後 {@link #begin} 照舊斷言已收斂、記下 baseline。最多等 {@link #HOLD_TICKS} 個 tick；逾時仍未收斂、或判定丟出例外時放行，由 {@code begin} 照舊失敗。
     *     開始等待時依前一個 test（{@link #lastBegun}）在原版的結果分成兩種：
     *     <ul>
     *     <li>前一個 test 失敗（例如在權威寫出失敗之後、收斂之前就失敗，像 Windows 原子移動的 AccessDenied），或沒有前一個 test：記一行 WARN（{@code start}）。
     *         收斂之後這個 test 照常執行，不連坐。</li>
     *     <li>前一個 test <b>通過</b>（F6-3b fix round 1）：那是它完成之後才留下的未確認寫入，不能被吞掉。記一行 ERROR（{@code after-pass}），照常嘗試收斂（不連坐：
     *         收斂的話再下一個 test 不受影響），並在 hold 結束時記下 {@link #afterPassFailure}（帶 hold 的結果與 heldTicks，F6-3c-1），讓下一個 {@link #begin}（通常就是這個 test）
     *         以「前一個 test（…）通過之後仍留下未確認的權威」失敗。</li>
     *     </ul></li>
     * </ul>
     * 需要它，是因為 production 只在下一次使用之前收斂，沒有人會替失敗的 test 收斂；原版又在 test 失敗的同一個 tick 就開始下一個 batch（{@code TestRunContext} 的
     * {@code onFailed} → {@code runBatch}），而 {@code begin} 是 test 方法的第一個敘述，沒辦法跨 tick 等待。沒有這段等待時，後面每個 test 的 {@code begin} 都立刻失敗。
     * 失敗的那個 test 照常失敗，這裡不吞掉任何失敗。開始等待記一行 WARN 或 ERROR（見上），結束記一行：收斂是 INFO，逾時與例外是 WARN（逾時時仍未收斂：
     * 除非原版 initialDelay 的 20 tick 內另有寫出讓它收斂，接下來的 begin 會失敗），都以 {@code M5_UNIVERSE_HOLD} 開頭。
     */
    public static boolean holdBeforeBegin(ServerWorld world, String test, int heldTicks) {
        var server = world.getServer();
        try {
            var authorities = CollapseAuthorities.get(server);
            if (!authorities.initialized()) return false;
            if (converged(authorities)) {
                if (heldTicks > 0) {
                    LOGGER.info("M5_UNIVERSE_HOLD converged test={} heldTicks={}", test, heldTicks);
                    endHold("converged", heldTicks);
                }
                return false;
            }
            if (heldTicks == 0) startHold(test, unconfirmedAuthorities(authorities));
            if (heldTicks >= HOLD_TICKS) {
                LOGGER.warn("M5_UNIVERSE_HOLD timeout test={} heldTicks={} unconfirmed={}：放行，由 begin 判定（仍未收斂）", test, heldTicks,
                        unconfirmedAuthorities(authorities));
                endHold("timeout（仍未收斂：" + unconfirmedAuthorities(authorities) + "）", heldTicks);
                return false;
            }
            if (convergeAll(server)) {
                LOGGER.info("M5_UNIVERSE_HOLD converged test={} heldTicks={}", test, heldTicks);
                endHold("converged", heldTicks);
                return false;
            }
            return true;
        } catch (RuntimeException failure) {
            LOGGER.warn("M5_UNIVERSE_HOLD error test={} heldTicks={}：放行，由 begin 判定", test, heldTicks, failure);
            endHold("error（" + failure + "）", heldTicks);
            return false;
        }
    }

    /**
     * {@link #holdBeforeBegin} 開始等待時記一行（F6-3b fix round 1）：前一個 test（{@link #lastBegun}）在原版是通過的，記 ERROR 並記下這個 after-pass
     * （{@link #afterPassStarted}，結構化的 {@link AfterPass}，F6-3c-2；hold 結束時由 {@link #endHold} 寫成 {@link #afterPassFailure}；已有一個還沒被 begin 取走時保留最早的那個）；
     * 否則記 WARN。F6-3c-2（F6-3c-1 quality review「給 F6-3c-2」）：守門同時看 {@link #afterPassStarted}，還沒結束的 hold 已記下的 after-pass 也保留最早的那個（例如 test
     * 在 hold 期間就被原版判完成，mixin 不再詢問、{@link #endHold} 沒有執行）；原本只看 {@link #afterPassFailure}，下一次 hold 會蓋掉它。
     */
    private static void startHold(String test, List<CollapseAuthorities.Authority> unconfirmed) {
        var previous = lastBegun;
        if (previous != null && previous.state().isPassed()) {
            if (afterPassFailure == null && afterPassStarted == null) afterPassStarted = new AfterPass(previous.name(), unconfirmed, test);
            LOGGER.error("M5_UNIVERSE_HOLD after-pass test={} previous={} unconfirmed={}：前一個 test 通過之後仍留下未確認的權威；照常嘗試收斂（不連坐），初始化之前最多等 {} tick，"
                    + "下一個 begin 以此失敗", test, previous.name(), unconfirmed, HOLD_TICKS);
            return;
        }
        String described = previous == null ? "none" : previous.name() + "(" + (previous.state().isCompleted() ? "FAIL" : "RUNNING") + ")";
        LOGGER.warn("M5_UNIVERSE_HOLD start test={} previous={} unconfirmed={} unfinished={}：前一個 test 留下未確認的寫入，初始化之前最多等 {} tick 收斂",
                test, described, unconfirmed, unfinished(), HOLD_TICKS);
    }

    /**
     * {@link #holdBeforeBegin} 放行時（hold 開始之後的每個出口：converged、timeout、error；F6-3c-1，F6-3b fix round 1 quality Minor 1）：這次 hold 開始時發現了 after-pass
     * （{@link #afterPassStarted}）的話，補上 hold 的結果與 heldTicks，寫成 {@link #afterPassFailure}（已有一個還沒被 begin 取走時保留最早的那個）。結束的那一行 log 照舊。
     * 訊息在這裡一次組成（F6-3c-2，F6-3c-1 quality Minor 4；字句與原本相同）。
     */
    private static void endHold(String outcome, int heldTicks) {
        var started = afterPassStarted;
        if (started == null) return;
        if (afterPassFailure == null) {
            afterPassFailure = "前一個 test（" + started.previous() + "）通過之後仍留下未確認的權威：" + started.unconfirmed() + "（hold 在 " + started.test()
                    + " 初始化之前發現，照常嘗試收斂、不連坐；hold 的結果：" + outcome + "，heldTicks=" + heldTicks + "）";
        }
        afterPassStarted = null;
    }

    /** 有未確認寫入的權威（診斷用）。 */
    private static List<CollapseAuthorities.Authority> unconfirmedAuthorities(CollapseAuthorities authorities) {
        return Arrays.stream(CollapseAuthorities.Authority.values()).filter(authorities::unconfirmed).toList();
    }

    /**
     * 完成 test：catalog 必須恰好多 {@code expectedDelta} 筆、開始時已有的 record 一筆都沒有改變，而且 catalog 與四個 M5 權威都已收斂。
     * <p>通過時才移除 baseline、寫一行 {@code M5_UNIVERSE_CATALOG_DELTA … status=PASS}，再 {@code context.complete()}。未通過時丟出
     * GameTest 的斷言例外，baseline 保留、不寫增量行（狀態改變時另記一行 {@code M5_UNIVERSE_CATALOG_UNMET} 供診斷），所以可以放在
     * {@code context.addInstantFinalTask(() -> complete(context, n))} 裡逐 tick 重試。直接呼叫而失敗時，test 就此失敗。
     * <p>放進 {@code addInstantFinalTask} 時，只能在最後一步之後註冊（例如在最後一個 {@code waitAndRun} 的 callback 裡）：runner 從註冊
     * 的那個 tick 起每個 tick 都嘗試，catalog 增量一達標就 complete，之後才排定的檢查不會執行，等於誤判 PASS。
     * <p>判定本身丟出非 GameTestException 的例外（例如 catalog 不健康時 {@code records()} 的 IllegalStateException）時，轉成
     * GameTestException，以 {@link #failDefinitely} 讓 test 確定失敗，再丟出（runner 只攔 GameTestException，其他例外會讓 GameTest server
     * 崩潰）。在 runner 裡呼叫時 test 在下一個 tick 失敗，直接呼叫或在 runAtTick 的 callback 裡呼叫時在同一個 tick 失敗，不必等 tickLimit。
     * 之後每次呼叫都直接丟同一個例外（{@code BROKEN}）：失敗落地之前 runner 還會重試一次，這樣不會再記一次 ERROR、不會再排第二個失敗，
     * 也不會在那之前因為判定剛好通過而先寫出 PASS。
     */
    static void complete(TestContext context, int expectedDelta) {
        var broken = BROKEN.get(context);
        if (broken != null) throw broken;
        try {
            finish(context, expectedDelta, false, true);
        } catch (GameTestException retryOrFailure) {
            throw retryOrFailure;
        } catch (RuntimeException failure) {
            var converted = asTestFailure(context, "complete 的判定", failure);
            BROKEN.put(context, converted);
            failDefinitely(context, converted);
            throw converted;
        }
    }

    /** {@link #awaitConvergedThenComplete(TestContext, int, int, BooleanSupplier)}，沒有額外的 ready 條件。 */
    static void awaitConvergedThenComplete(TestContext context, int expectedDelta, int maxTicks) {
        awaitConvergedThenComplete(context, expectedDelta, maxTicks, () -> true);
    }

    /**
     * 收斂（而且 {@code ready} 成立）之後才完成：每一輪先對四個權威呼叫 {@code CollapseAuthorities.convergeIfNeeded}（catalog 為 checked
     * flush；在退避期內不重寫），全部已收斂而且 ready 成立，就依 {@link #complete} 的條件做最終判定。第一輪在呼叫當下執行，之後由一個
     * 專用的 {@code TimedTaskRunner}（{@code context.createTimedTaskRunner()}）每個 tick 一輪，最多 {@code maxTicks} 個 tick。收斂之後
     * 條件仍不符，或逾時還沒收斂（或 ready 還不成立），都寫一行 {@code status=FAIL} 的增量並讓 test 失敗。
     * <p>為什麼用 runner、不用 {@code runAtTick}：原版 {@code GameTestState} 逐 tick 迭代 {@code runAtTick} 的表
     * （{@code Object2LongOpenHashMap}），執行完一項才 {@code iterator.remove()}。在 {@code runAtTick}／{@code waitAndRun} 的 callback
     * 裡一次插入多項會讓表在迭代中 rehash：外層 callback 重跑、已排定的其他檢查遺失，或 iterator 丟例外。runner 存在另一份
     * {@code CopyOnWriteArrayList}，在該 tick 迭代完那張表之後才執行，所以在任何 callback 裡呼叫本方法都不會改到迭代中的表。
     * <p>呼叫條件：
     * <ul>
     * <li>必須是 test 的最後一個動作（可以在 {@code waitAndRun} 的 callback 裡），不要在 TimedTaskRunner 的工作（例如
     *     {@code addInstantFinalTask}）裡呼叫。</li>
     * <li>完成時點之後才到期的 {@code runAtTick}／{@code waitAndRun} 檢查不會執行（test 完成之後原版不再 tick 它）：還沒到期的檢查，
     *     必須放進 {@code ready}（例如等它們都跑過），或排在呼叫本方法之前、而且在呼叫時已經到期。</li>
     * <li>{@code ready} 與權威收斂丟出的例外（含 GameTestException）、以及最終判定丟出的例外，一律轉成 test 失敗，不會讓 GameTest server
     *     崩潰：在 runner 裡時寫一行 {@code status=FAIL error=…}，以 {@link #failDefinitely} 在下一個 tick 確定失敗；在呼叫當下的第一輪時，轉成 GameTestException
     *     丟給呼叫端（test 方法或 runAtTick 的 callback 會讓 test 失敗）。{@code ready} 用回傳 false 表示「還沒好」。</li>
     * <li>{@code maxTicks} 要小於 test 的 {@code tickLimit}（{@code @GameTest} 預設 100）減去呼叫時的 tick。超過 tickLimit 的那一個 tick，
     *     原版一律判 test 失敗：即使同一個 tick 剛收斂並寫了 PASS 的增量行也一樣；還沒收斂時則以「等待…」的訊息失敗，不會留下
     *     {@code status=FAIL} 的增量行。</li>
     * <li>注入寫出失敗時，權威的退避從 200 tick 起跳（{@code CollapseBackoff.INITIAL_TICKS}，之後加倍）：要等到收斂，{@code maxTicks}
     *     至少要超過 200，{@code tickLimit} 也要跟著加大。</li>
     * </ul>
     */
    static void awaitConvergedThenComplete(TestContext context, int expectedDelta, int maxTicks, BooleanSupplier ready) {
        if (maxTicks < 1) throw new IllegalArgumentException("maxTicks 必須至少 1：" + maxTicks);
        context.assertTrue(ACTIVE.containsKey(context), "缺少 begin 記下的 catalog baseline");
        var server = context.getWorld().getServer();
        // 第一輪在呼叫端的情境裡執行：例外轉成 GameTestException 丟給呼叫端，由 test 方法或 runAtTick 的 callback 讓 test 失敗。
        boolean settledNow;
        try {
            boolean readyNow = ready.getAsBoolean();
            settledNow = convergeAll(server) && readyNow;
        } catch (RuntimeException failure) {
            logFailedDelta(context, failure);
            throw asTestFailure(context, "ready 條件或權威收斂", failure);
        }
        if (settledNow) {
            try {
                finish(context, expectedDelta, true, true);
            } catch (RuntimeException failure) {
                throw asTestFailure(context, "完成判定", failure);
            }
            return;
        }
        long deadline = context.getTick() + maxTicks;
        var done = new AtomicBoolean();
        context.createTimedTaskRunner().createAndAdd(() -> {
            if (done.get()) return;
            // runner 只攔 GameTestException（當成「還沒好」，下一個 tick 再執行這個工作）；其他例外會讓 GameTest server 崩潰，
            // 所以 ready、權威收斂與最終判定的例外都在這裡轉成「下一個 tick 確定失敗」。
            boolean readyThisTick;
            boolean settled;
            try {
                readyThisTick = ready.getAsBoolean();
                settled = convergeAll(server) && readyThisTick;
            } catch (RuntimeException failure) {
                done.set(true);
                logFailedDelta(context, failure);
                failDefinitely(context, asTestFailure(context, "ready 條件或權威收斂", failure));
                return;
            }
            if (!settled && context.getTick() < deadline) throw new GameTestException("等待 M5 權威收斂" + (readyThisTick ? "" : "與 ready 條件"));
            done.set(true);
            try {
                finish(context, expectedDelta, true, readyThisTick);
            } catch (RuntimeException failure) {
                failDefinitely(context, asTestFailure(context, "完成判定", failure));
            }
        });
    }

    /**
     * 例外轉成 test 失敗：GameTestException 原樣回傳；其他 RuntimeException 先記一行 ERROR（含 stack），再包成 GameTestException。
     * 原版的 TimedTaskRunner 只攔 GameTestException，其他例外會丟出 server tick，GameTest server 寫 crash report 之後結束（後面的 test
     * 都不會執行）。
     */
    static GameTestException asTestFailure(TestContext context, String what, RuntimeException failure) {
        if (failure instanceof GameTestException gameTest) return gameTest;
        var baseline = ACTIVE.get(context);
        LOGGER.error("M5_UNIVERSE_HELPER_EXCEPTION name={} {}：{}", baseline == null ? "?" : baseline.name(), what, failure.toString(), failure);
        return new GameTestException(what + "丟出例外：" + failure);
    }

    /** ready 或權威收斂丟出例外時，算不出增量，寫一行只有名稱、狀態與例外的 FAIL 增量行（gate 的 PASS 解析不受影響）。 */
    private static void logFailedDelta(TestContext context, RuntimeException failure) {
        var baseline = ACTIVE.get(context);
        LOGGER.info("M5_UNIVERSE_CATALOG_DELTA name={} status=FAIL error={}", baseline == null ? "?" : baseline.name(), failure.toString());
    }

    /**
     * 讓 test 確定失敗：以原版公開的 {@code TimedTaskRunner.fail} 新增一個 runner，它的工作直接呼叫 {@code test.fail}。runner 放在
     * {@code CopyOnWriteArrayList}，不碰 runAtTick 的表，所以在任何情境都能呼叫：在 test 方法或 runAtTick 的 callback 裡時，同一個 tick 的
     * runner 階段就失敗；在 runner 的工作裡時，下一個 tick 失敗。需要它，是因為 runner 工作丟出的 GameTestException 只會被原版當成
     * 「還沒好」而重試，要等到 tickLimit 才會失敗。
     */
    static void failDefinitely(TestContext context, GameTestException failure) {
        context.createTimedTaskRunner().fail(() -> failure);
    }

    private static void finish(TestContext context, int expectedDelta, boolean finalAttempt, boolean ready) {
        var baseline = ACTIVE.get(context);
        context.assertTrue(baseline != null, "缺少 begin 記下的 catalog baseline");
        var server = context.getWorld().getServer();
        var after = UniverseRegistryState.get(server).records();
        boolean converged = converged(CollapseAuthorities.get(server));
        boolean preserved = baseline.records().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(after.get(entry.getKey())));
        int actual = after.size() - baseline.records().size();
        boolean pass = ready && converged && preserved && actual == expectedDelta;
        // ready 只在不成立時出現在行尾（PASS 的增量行格式不變，gate 的解析不受影響）。
        String state = "name=" + baseline.name() + " before=" + baseline.records().size() + " after=" + after.size() + " expected=" + expectedDelta
                + " actual=" + actual + " converged=" + converged + " preserved=" + preserved + (ready ? "" : " ready=false");
        if (pass || finalAttempt) {
            LOGGER.info("M5_UNIVERSE_CATALOG_DELTA {} beforeDigest={} afterDigest={} status={}", state, baseline.digest(), digest(after), pass ? "PASS" : "FAIL");
        } else if (!state.equals(UNMET.put(context, state))) {
            LOGGER.info("M5_UNIVERSE_CATALOG_UNMET {}", state);
        }
        context.assertTrue(pass, "M5 catalog 增量不符：" + state);
        ACTIVE.remove(context);
        UNMET.remove(context);
        context.complete();
    }

    /** 對四個權威各呼叫一次 convergeIfNeeded，回傳之後是否全部已收斂。 */
    private static boolean convergeAll(MinecraftServer server) {
        var authorities = CollapseAuthorities.get(server);
        for (var authority : CollapseAuthorities.Authority.values()) authorities.convergeIfNeeded(authority);
        return converged(authorities);
    }

    /** 四個權威都沒有未確認的寫入。CATALOG 由 catalog 本身推導：不 dirty，而且 current 等於 flushed。 */
    static boolean converged(CollapseAuthorities authorities) {
        for (var authority : CollapseAuthorities.Authority.values()) {
            if (authorities.unconfirmed(authority)) return false;
        }
        return true;
    }

    /** catalog record 的 SHA-256（依 UUID 排序的 UUID＝record NBT），用來串接各 test 與 suite 結束時的狀態。 */
    private static String digest(Map<UniverseId, UniverseRecord> records) {
        String text = records.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().value().toString()))
                .map(entry -> entry.getKey().value() + "=" + entry.getValue().toNbt())
                .collect(Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static List<String> unfinished() {
        return ACTIVE.values().stream().map(Baseline::name).sorted().toList();
    }

    private record Baseline(String name, Map<UniverseId, UniverseRecord> records, String digest) { }

    /** 呼叫過 {@link #begin} 的 test：名稱與它在原版的 {@code GameTestState}（F6-3b fix round 1，{@link #lastBegun}）。 */
    private record Begun(String name, GameTestState state) { }

    /**
     * hold 開始時發現的 after-pass（{@link #afterPassStarted}，F6-3c-2）：通過之後仍留下未確認權威的前一個 test、當時未確認的權威，以及 hold 在哪個 test 初始化之前發現它。
     */
    private record AfterPass(String previous, List<CollapseAuthorities.Authority> unconfirmed, String test) { }
}
