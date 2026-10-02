package dev.quantumchamber.gametest;

import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * plan 決策 6 的隔離 run：會配置或物化 Universe、或會留下 M5 record 的 GameTest 都放在這裡，只在 {@code gameTestM5} run
 * （{@code -Dquantumchamber.gametest.m5Only=true}，獨立的 fresh 世界 {@code run/gametest-m5}）執行。用 {@code gates.ps1 -Mode M5GameTest}
 * 跑；新增 test 的步驟另見 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 * <ul>
 * <li>batchId 一律是 {@code m5_universe_<名稱>}，每個 test 自己一個 batch。batch 依序執行，所以 {@link #begin} 到完成之間量到的 catalog
 *     增量只屬於該 test。{@code M4LegacyGameTestFilterMixin} 在 default run 排除這個前綴，在 M5 run 只保留這個前綴。</li>
 * <li>需要 Windows 原生 checkpoint 的 test 名稱以 {@code _windows} 結尾，平台略過（{@code context.complete()} 後 return）要放在
 *     {@link #begin} 之前，略過時不留下 baseline。Windows 上每個 test 都必須走到 {@link #complete} 或
 *     {@link #awaitConvergedThenComplete} 並通過，gate 逐 test 要求一行 PASS 的增量。</li>
 * <li>第一個動作是 {@code begin(context, "<方法名稱>")}：不在 M5 run 時在碰 catalog 之前就失敗；名稱必須等於方法名稱。最後一個動作是
 *     {@link #awaitConvergedThenComplete}（主動收斂、逾時即失敗；可另給 ready 條件）或 {@link #complete}（可重試）。把 complete 放進
 *     {@code context.addInstantFinalTask} 時，只能在最後一步之後註冊（例如在最後一個 {@code waitAndRun} 的 callback 裡）：在 test
 *     開頭就註冊的話，catalog 增量一達標就會 complete，之後排定的檢查不會執行，等於誤判 PASS。注入寫出失敗時，權威的退避從 200 tick
 *     起跳，{@code maxTicks} 與 {@code @GameTest(tickLimit = …)}（預設 100）都要留足，見 {@link #awaitConvergedThenComplete}。
 *     通過時寫一行 {@code M5_UNIVERSE_CATALOG_DELTA … status=PASS}，由 gate 逐 test 核對並依序串接；suite 結束時
 *     （{@code SERVER_STOPPED}）再寫一行 {@code M5_UNIVERSE_SUITE_END}，gate 核對它等於最後一個 test 的結果。M5 run 不註冊 default run
 *     的 {@code M4GameTestBoundaryProbe}，改用這些逐 test 的增量。</li>
 * <li>衛生規則：test 完成前，catalog 與四個 M5 權威都必須已收斂（catalog 不 dirty、current 等於 flushed，沒有未確認的寫入）。注入失敗的
 *     test 要等收斂之後才完成；catalog 一直 dirty 的話，之後所有 NEW 配置都會一直 WAIT。{@link #begin} 也檢查開始時已收斂，失敗時列出
 *     還沒通過的 test。</li>
 * </ul>
 */
public final class M5UniverseGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    /** begin 之後、還沒通過的 test。失敗的 test 留在這裡，下一個 test 的 begin 失敗時列出。 */
    private static final Map<TestContext, Baseline> ACTIVE = new IdentityHashMap<>();
    /** complete 未通過時最後一次記錄的狀態；狀態改變時才再記一行，重試不洗版。 */
    private static final Map<TestContext, String> UNMET = new IdentityHashMap<>();
    /** complete 的判定丟過非 GameTestException 的例外：之後每次呼叫都直接丟同一個 GameTestException。 */
    private static final Map<TestContext, GameTestException> BROKEN = new IdentityHashMap<>();
    private static final AtomicBoolean SUITE_END_REGISTERED = new AtomicBoolean();
    private static final UUID SANITY_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c01");
    private static final UUID RETRY_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c11");
    private static final UUID AWAIT_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c12");
    private static final UUID NESTED_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c13");
    private static final int NESTED_CHECKS = 5;

    /**
     * F3c sanity：M5 run 可以配置 catalog。比照 M3 的 DISABLED fixture（{@code M3UniverseLifecycleGameTests}）：配置一筆 OVERWORLD、
     * 改為 DISABLED（停服時不會期待一個沒物化的 world）、checked flush。不物化：runtime 物化 API 到 F4a 才有，直接呼叫 backend 會繞過
     * lifecycle。catalog 恰好多 1 筆，world 不被建立。最後以 {@link #awaitConvergedThenComplete} 完成（示範一般的結尾寫法）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_sanity")
    public void sanity_allocates_one_disabled_record_without_materializing(TestContext context) {
        begin(context, "sanity_allocates_one_disabled_record_without_materializing");
        var server = context.getWorld().getServer();
        var catalog = UniverseRegistryState.get(server);
        var worldsBefore = worldKeys(server);
        var record = catalog.allocateOverworld(SANITY_UNIVERSE, nextOrdinal(catalog));
        catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        catalog.flush(server);
        var flushed = catalog.flushedRecords().get(record.definition().universeId());
        context.assertTrue(flushed != null && flushed.desiredAvailability() == DesiredAvailability.DISABLED, "sanity record 已以 DISABLED 寫出");
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        context.assertTrue(server.getWorld(descriptor.worldKey()) == null, "DISABLED record 不物化 world");
        context.assertEquals(worldsBefore, worldKeys(server), "world keys 不變");
        awaitConvergedThenComplete(context, 1, 20);
    }

    /**
     * F3c fix round 1：{@link #complete} 可以重試。catalog 的變更 5 tick 之後才寫出，以 {@code addInstantFinalTask}（原版每個 tick 重試，
     * 直到不丟例外）呼叫 complete：寫出之前的嘗試因未收斂而失敗，但保留 baseline、不寫增量行；寫出之後那一次通過。註冊是本 test 的最後
     * 一步，之後沒有其他檢查。通過之前斷言至少已失敗過一次（fix round 2 M2）：第一次就已收斂代表 fixture 失效，重試路徑沒有被走到。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_harness_retry")
    public void harness_complete_retries_without_losing_the_baseline(TestContext context) {
        begin(context, "harness_complete_retries_without_losing_the_baseline");
        var server = context.getWorld().getServer();
        var catalog = UniverseRegistryState.get(server);
        var record = catalog.allocateOverworld(RETRY_UNIVERSE, nextOrdinal(catalog));
        catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        long flushAt = context.getTick() + 5;
        context.runAtTick(flushAt, () -> catalog.flush(server));
        var attempts = new AtomicInteger();
        var fixtureBroken = new AtomicBoolean();
        context.addInstantFinalTask(() -> {
            if (fixtureBroken.get()) throw new GameTestException("等待確定失敗");
            int attempt = attempts.incrementAndGet();
            boolean convergedNow;
            try {
                convergedNow = converged(CollapseAuthorities.get(server));
            } catch (RuntimeException failure) {
                // runner 裡不讓非 GameTestException 的例外丟出（會讓 GameTest server 崩潰）。
                throw asTestFailure(context, "retry harness 的收斂檢查", failure);
            }
            if (convergedNow) {
                if (attempt <= 1) {
                    fixtureBroken.set(true);
                    failNextTick(context, new GameTestException("第一次嘗試就已收斂，complete 的重試路徑沒有被走到"));
                    throw new GameTestException("等待確定失敗");
                }
                LOGGER.info("M5_UNIVERSE_HARNESS_RETRY attempts={}", attempt);
            }
            complete(context, 1);
        });
    }

    /**
     * F3c fix round 1：{@link #awaitConvergedThenComplete} 主動收斂。catalog 的變更不自行寫出，由 helper 呼叫
     * {@code CollapseAuthorities.convergeIfNeeded} 以 checked flush 寫出，之後才 complete。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_harness_await")
    public void harness_await_converges_an_unflushed_catalog_change(TestContext context) {
        begin(context, "harness_await_converges_an_unflushed_catalog_change");
        var server = context.getWorld().getServer();
        var catalog = UniverseRegistryState.get(server);
        var record = catalog.allocateOverworld(AWAIT_UNIVERSE, nextOrdinal(catalog));
        catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        context.assertTrue(CollapseAuthorities.get(server).unconfirmed(CollapseAuthorities.Authority.CATALOG), "寫出之前 catalog 是未確認");
        awaitConvergedThenComplete(context, 1, 20);
    }

    /**
     * F3c fix round 2：在排程的 callback（{@code waitAndRun}）裡呼叫 {@link #awaitConvergedThenComplete}，第一輪不能完成（ready 未成立），
     * 同時還有 {@value #NESTED_CHECKS} 個已排定、尚未到期的 {@code runAtTick} 檢查。helper 的輪詢不得在原版迭代 runAtTick 表
     * （{@code GameTestState} 的 {@code Object2LongOpenHashMap}）的途中插入項目：一次插入多項會讓表在迭代中 rehash，使外層 callback
     * 重跑、已排定的檢查遺失，或 iterator 丟例外。外層與每個檢查重跑時當場失敗；ready 要求外層恰好跑過一次、每個檢查都恰好跑過一次，
     * 檢查遺失時 test 逾時失敗（{@code ready=false}）。這些現象取決於 lambda 的 identity hash，不一定每次發生，所以另外直接斷言
     * helper 呼叫前後 runAtTick 表的項目數不變。
     * maxTicks 取 20：若照 {@code 1988744} 的做法一次預排 maxTicks 項，這張表（5 個檢查＋外層，預設容量的 maxFill 為 24）在插入第 19 項時
     * 就會 rehash。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_harness_nested")
    public void harness_await_inside_a_scheduled_callback_keeps_other_scheduled_checks(TestContext context) {
        begin(context, "harness_await_inside_a_scheduled_callback_keeps_other_scheduled_checks");
        var server = context.getWorld().getServer();
        var catalog = UniverseRegistryState.get(server);
        var checks = new AtomicIntegerArray(NESTED_CHECKS);
        var outer = new AtomicInteger();
        var logged = new AtomicReference<String>();
        long start = context.getTick();
        for (int i = 0; i < NESTED_CHECKS; i++) {
            int id = i;
            context.runAtTick(start + 4 + id, () -> context.assertTrue(checks.incrementAndGet(id) == 1, "已排定的檢查 #" + id + " 重複執行"));
        }
        context.waitAndRun(2, () -> {
            context.assertTrue(outer.incrementAndGet() == 1, "外層 waitAndRun 的 callback 重跑：runAtTick 的表在迭代中被改動");
            var record = catalog.allocateOverworld(NESTED_UNIVERSE, nextOrdinal(catalog));
            catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
            int scheduledBefore = scheduledCount(context);
            awaitConvergedThenComplete(context, 1, 20, () -> {
                String state = "outer=" + outer.get() + " checks=" + checks;
                if (!state.equals(logged.getAndSet(state))) LOGGER.info("M5_UNIVERSE_HARNESS_NESTED {}", state);
                return outer.get() == 1 && IntStream.range(0, NESTED_CHECKS).allMatch(id -> checks.get(id) == 1);
            });
            // 結構面的斷言（不靠 rehash 是否剛好發生）：helper 在這個 callback 裡不得插入 runAtTick 的項目。
            int inserted = scheduledCount(context) - scheduledBefore;
            context.assertTrue(inserted == 0, "awaitConvergedThenComplete 在 runAtTick 的 callback 裡插入了 " + inserted + " 個排程項目");
        });
    }

    /**
     * 原版 {@code GameTestState} 的 runAtTick 表（{@code ticksByRunnables}）目前的項目數，只讀。欄位名稱是 Yarn 1.21+build.9 的
     * {@code TestContext.test} 與 {@code GameTestState.ticksByRunnables}；GameTest 只在 Loom 的開發 runtime（named 名稱）執行。
     */
    private static int scheduledCount(TestContext context) {
        try {
            var testField = TestContext.class.getDeclaredField("test");
            testField.setAccessible(true);
            var state = testField.get(context);
            var mapField = state.getClass().getDeclaredField("ticksByRunnables");
            mapField.setAccessible(true);
            return ((Map<?, ?>) mapField.get(state)).size();
        } catch (ReflectiveOperationException failure) {
            // 丟 RuntimeException：runAtTick 的迴圈只攔 Exception，Error 會讓 GameTest server 崩潰。
            throw new IllegalStateException("讀不到原版的 runAtTick 表", failure);
        }
    }

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

    /** 每個 M5 Universe test 的第一個動作：確認在 M5 run、權威已初始化而且已收斂，再記下 catalog baseline。 */
    private static void begin(TestContext context, String name) {
        // 決策 6：default run 不得執行這個類別的 test。被誤放進 default run 時，在碰 catalog 之前就失敗。
        context.assertTrue(Boolean.getBoolean("quantumchamber.gametest.m5Only"), "M5 Universe GameTest 只能在 gameTestM5 run 執行：" + name);
        context.assertTrue(!ACTIVE.containsKey(context), "M5 catalog baseline 不可重複：" + name + "；還沒通過的 test：" + unfinished());
        var server = context.getWorld().getServer();
        var authorities = CollapseAuthorities.get(server);
        context.assertTrue(authorities.initialized(), "M5 run 的世界必須有已初始化的 M5 authority：" + name);
        context.assertTrue(converged(authorities), "開始時 catalog 與 M5 權威必須已收斂（前一個 test 違反衛生規則）：" + name
                + "；還沒通過的 test：" + unfinished());
        var records = UniverseRegistryState.get(server).records();
        ACTIVE.put(context, new Baseline(name, records, digest(records)));
        LOGGER.info("M5_UNIVERSE_BEGIN name={} records={} digest={}", name, records.size(), digest(records));
    }

    /**
     * 完成 test：catalog 必須恰好多 {@code expectedDelta} 筆、開始時已有的 record 一筆都沒有改變，而且 catalog 與四個 M5 權威都已收斂。
     * <p>通過時才移除 baseline、寫一行 {@code M5_UNIVERSE_CATALOG_DELTA … status=PASS}，再 {@code context.complete()}。未通過時丟出
     * GameTest 的斷言例外，baseline 保留、不寫增量行（狀態改變時另記一行 {@code M5_UNIVERSE_CATALOG_UNMET} 供診斷），所以可以放在
     * {@code context.addInstantFinalTask(() -> complete(context, n))} 裡逐 tick 重試。直接呼叫而失敗時，test 就此失敗。
     * <p>放進 {@code addInstantFinalTask} 時，只能在最後一步之後註冊（例如在最後一個 {@code waitAndRun} 的 callback 裡）：runner 從註冊
     * 的那個 tick 起每個 tick 都嘗試，catalog 增量一達標就 complete，之後才排定的檢查不會執行，等於誤判 PASS。
     * <p>判定本身丟出非 GameTestException 的例外（例如 catalog 不健康時 {@code records()} 的 IllegalStateException）時，轉成
     * GameTestException 再丟出，之後每次呼叫都直接丟同一個：runner 只攔 GameTestException，其他例外會讓 GameTest server 崩潰。在 runner 裡
     * 呼叫時，test 因此在 tickLimit 以這個訊息失敗；直接呼叫或在 runAtTick 的 callback 裡呼叫時當場失敗。
     */
    private static void complete(TestContext context, int expectedDelta) {
        var broken = BROKEN.get(context);
        if (broken != null) throw broken;
        try {
            finish(context, expectedDelta, false, true);
        } catch (GameTestException retryOrFailure) {
            throw retryOrFailure;
        } catch (RuntimeException failure) {
            var converted = asTestFailure(context, "complete 的判定", failure);
            BROKEN.put(context, converted);
            throw converted;
        }
    }

    /** {@link #awaitConvergedThenComplete(TestContext, int, int, BooleanSupplier)}，沒有額外的 ready 條件。 */
    private static void awaitConvergedThenComplete(TestContext context, int expectedDelta, int maxTicks) {
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
     *     崩潰：在 runner 裡時寫一行 {@code status=FAIL error=…}，在下一個 tick 確定失敗；在呼叫當下的第一輪時，轉成 GameTestException
     *     丟給呼叫端（test 方法或 runAtTick 的 callback 會讓 test 失敗）。{@code ready} 用回傳 false 表示「還沒好」。</li>
     * <li>{@code maxTicks} 要小於 test 的 {@code tickLimit}（{@code @GameTest} 預設 100）減去呼叫時的 tick。超過 tickLimit 的那一個 tick，
     *     原版一律判 test 失敗：即使同一個 tick 剛收斂並寫了 PASS 的增量行也一樣；還沒收斂時則以「等待…」的訊息失敗，不會留下
     *     {@code status=FAIL} 的增量行。</li>
     * <li>注入寫出失敗時，權威的退避從 200 tick 起跳（{@code CollapseBackoff.INITIAL_TICKS}，之後加倍）：要等到收斂，{@code maxTicks}
     *     至少要超過 200，{@code tickLimit} 也要跟著加大。</li>
     * </ul>
     */
    private static void awaitConvergedThenComplete(TestContext context, int expectedDelta, int maxTicks, BooleanSupplier ready) {
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
                failNextTick(context, asTestFailure(context, "ready 條件或權威收斂", failure));
                return;
            }
            if (!settled && context.getTick() < deadline) throw new GameTestException("等待 M5 權威收斂" + (readyThisTick ? "" : "與 ready 條件"));
            done.set(true);
            try {
                finish(context, expectedDelta, true, readyThisTick);
            } catch (RuntimeException failure) {
                failNextTick(context, asTestFailure(context, "完成判定", failure));
            }
        });
    }

    /**
     * 例外轉成 test 失敗：GameTestException 原樣回傳；其他 RuntimeException 先記一行 ERROR（含 stack），再包成 GameTestException。
     * 原版的 TimedTaskRunner 只攔 GameTestException，其他例外會丟出 server tick，GameTest server 寫 crash report 之後結束（後面的 test
     * 都不會執行）。
     */
    private static GameTestException asTestFailure(TestContext context, String what, RuntimeException failure) {
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
     * 在 TimedTaskRunner 的工作裡讓 test 確定失敗：runner 會把 GameTestException 當成「還沒好」而在下一個 tick 重試，所以改排一個在下一個
     * tick 丟出的 {@code runAtTick}。只在 runner 的工作裡呼叫：runner 在該 tick 迭代完 runAtTick 的表之後才執行，這時插入不會改到迭代中的表。
     */
    private static void failNextTick(TestContext context, RuntimeException failure) {
        context.runAtTick(context.getTick(), () -> { throw failure; });
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
    private static boolean converged(CollapseAuthorities authorities) {
        for (var authority : CollapseAuthorities.Authority.values()) {
            if (authorities.unconfirmed(authority)) return false;
        }
        return true;
    }

    /** 與 M5 的 NEW 配置相同的 ordinal：flushed 的最大 ordinal＋1。 */
    private static long nextOrdinal(UniverseRegistryState catalog) {
        return catalog.flushedRecords().values().stream().mapToLong(record -> record.definition().allocationOrdinal()).max().orElse(-1) + 1;
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

    private static List<String> worldKeys(MinecraftServer server) {
        return StreamSupport.stream(server.getWorlds().spliterator(), false)
                .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList();
    }

    private record Baseline(String name, Map<UniverseId, UniverseRecord> records, String digest) { }
}
