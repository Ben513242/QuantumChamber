package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;
import static dev.quantumchamber.gametest.M5UniverseHold.asTestFailure;
import static dev.quantumchamber.gametest.M5UniverseHold.awaitConvergedThenComplete;
import static dev.quantumchamber.gametest.M5UniverseHold.begin;
import static dev.quantumchamber.gametest.M5UniverseHold.complete;
import static dev.quantumchamber.gametest.M5UniverseHold.converged;
import static dev.quantumchamber.gametest.M5UniverseHold.failDefinitely;
import static dev.quantumchamber.gametest.M5UniverseSupport.allocationEntries;
import static dev.quantumchamber.gametest.M5UniverseSupport.bytes;
import static dev.quantumchamber.gametest.M5UniverseSupport.receiptStillSealed;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealWriteWarnings;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealedWith;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.DestinationResolver;
import dev.quantumchamber.collapse.ResolveResult;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.gametest.mixin.GameTestStateRunnablesTestAccessor;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Box;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * plan 決策 6 的隔離 run：會配置或物化 Universe、或會留下 M5 record 的 GameTest 都放在 M5 run 的 GameTest class（見下一段），只在 {@code gameTestM5} run
 * （{@code -Dquantumchamber.gametest.m5Only=true}，獨立的 fresh 世界 {@code run/gametest-m5}）執行。用 {@code gates.ps1 -Mode M5GameTest}
 * 跑；新增 test 的步驟另見 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 * <p>F6-3c-2（盤點 #06）起依主題分成四個 class；test 的方法名稱、batchId、tickLimit 與 {@code @GameTest} 的屬性都不變，只有 class 改變：本類（catalog 的
 * harness 自我測試、RESOLVE 與目的地的確定）、{@link M5UniverseProjectionGameTests}（投影與 PREPARE）、{@link M5UniverseDoorGameTests}（原艙門與找不到所屬
 * session 的量子膜）、{@link M5UniversePassageGameTests}（量子通道）。兩個以上的 class 共用的 helper 在 {@link M5UniverseSupport}；catalog baseline、完成判定與
 * 收斂的 hold 在 {@link M5UniverseHold}。四個 class 都列在 testmod {@code fabric.mod.json} 的 {@code fabric-gametest} entrypoint 與 {@code gates.ps1} 的 M5 run
 * class 清單。以下規則適用於四個 class。
 * <ul>
 * <li>batchId 一律是 {@code m5_universe_<名稱>}，每個 test 自己一個 batch。batch 依序執行，所以 {@link M5UniverseHold#begin} 到完成之間量到的 catalog
 *     增量只屬於該 test。{@code M4LegacyGameTestFilterMixin} 在 default run 排除這個前綴，在 M5 run 只保留這個前綴。</li>
 * <li>需要 Windows 原生 checkpoint 的 test 名稱以 {@code _windows} 結尾，平台略過（{@code context.complete()} 後 return）要放在
 *     {@link M5UniverseHold#begin} 之前，略過時不留下 baseline。Windows 上每個 test 都必須走到 {@link M5UniverseHold#complete} 或
 *     {@link M5UniverseHold#awaitConvergedThenComplete} 並通過，gate 逐 test 要求一行 PASS 的增量。</li>
 * <li>第一個動作是 {@code begin(context, "<方法名稱>")}：不在 M5 run 時在碰 catalog 之前就失敗；名稱必須等於方法名稱。最後一個動作是
 *     {@link M5UniverseHold#awaitConvergedThenComplete}（主動收斂、逾時即失敗；可另給 ready 條件）或 {@link M5UniverseHold#complete}（可重試）。把 complete 放進
 *     {@code context.addInstantFinalTask} 時，只能在最後一步之後註冊（例如在最後一個 {@code waitAndRun} 的 callback 裡）：在 test
 *     開頭就註冊的話，catalog 增量一達標就會 complete，之後排定的檢查不會執行，等於誤判 PASS。注入寫出失敗時，權威的退避從 200 tick
 *     起跳，{@code maxTicks} 與 {@code @GameTest(tickLimit = …)}（預設 100）都要留足，見 {@link M5UniverseHold#awaitConvergedThenComplete}。
 *     通過時寫一行 {@code M5_UNIVERSE_CATALOG_DELTA … status=PASS}，由 gate 逐 test 核對並依序串接；suite 結束時
 *     （{@code SERVER_STOPPED}）再寫一行 {@code M5_UNIVERSE_SUITE_END}，gate 核對它等於最後一個 test 的結果。M5 run 不註冊 default run
 *     的 {@code M4GameTestBoundaryProbe}，改用這些逐 test 的增量。</li>
 * <li>衛生規則：test 完成前，catalog 與四個 M5 權威都必須已收斂（catalog 不 dirty、current 等於 flushed，沒有未確認的寫入）。注入失敗的
 *     test 要等收斂之後才完成；catalog 一直 dirty 的話，之後所有 NEW 配置都會一直 WAIT。{@link M5UniverseHold#begin} 也檢查開始時已收斂，失敗時列出
 *     還沒通過的 test。F6-3b 起，一個 test 在收斂之前失敗時，下一個 test 由原版初始化之前先經 {@link M5UniverseHold#holdBeforeBegin} 有界地等收斂，不再連坐。
 *     F6-3b fix round 1 起，前一個 test <b>通過</b>之後才留下未確認的寫入時，hold 一樣嘗試收斂，但下一個 test 的 {@link M5UniverseHold#begin} 以此失敗（F6-3c-1 起訊息帶 hold 的結果與
 *     heldTicks），不會被吞掉。</li>
 * </ul>
 */
public final class M5UniverseGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final UUID SANITY_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c01");
    private static final UUID RETRY_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c11");
    private static final UUID AWAIT_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c12");
    private static final UUID NESTED_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c13");
    private static final int NESTED_CHECKS = 5;

    /**
     * F3c sanity：M5 run 可以配置 catalog。比照 M3 的 DISABLED fixture（{@code M3UniverseLifecycleGameTests}）：配置一筆 OVERWORLD、
     * 改為 DISABLED（停服時不會期待一個沒物化的 world）、checked flush。不物化：runtime 物化 API 到 F4a 才有，直接呼叫 backend 會繞過
     * lifecycle。catalog 恰好多 1 筆，world 不被建立。最後以 {@link M5UniverseHold#awaitConvergedThenComplete} 完成（示範一般的結尾寫法）。
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
     * F3c fix round 1：{@link M5UniverseHold#complete} 可以重試。catalog 的變更 5 tick 之後才寫出，以 {@code addInstantFinalTask}（原版每個 tick 重試，
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
        var failing = new AtomicBoolean();
        context.addInstantFinalTask(() -> {
            if (failing.get()) throw new GameTestException("等待確定失敗");
            int attempt = attempts.incrementAndGet();
            boolean convergedNow;
            try {
                convergedNow = converged(CollapseAuthorities.get(server));
            } catch (RuntimeException failure) {
                // runner 裡不讓非 GameTestException 的例外丟出（會讓 GameTest server 崩潰）；確定失敗一次，之後的重試不再記 ERROR。
                var converted = asTestFailure(context, "retry harness 的收斂檢查", failure);
                failing.set(true);
                failDefinitely(context, converted);
                throw converted;
            }
            if (convergedNow) {
                if (attempt <= 1) {
                    failing.set(true);
                    failDefinitely(context, new GameTestException("第一次嘗試就已收斂，complete 的重試路徑沒有被走到"));
                    throw new GameTestException("等待確定失敗");
                }
                LOGGER.info("M5_UNIVERSE_HARNESS_RETRY attempts={}", attempt);
            }
            complete(context, 1);
        });
    }

    /**
     * F3c fix round 1：{@link M5UniverseHold#awaitConvergedThenComplete} 主動收斂。catalog 的變更不自行寫出，由 helper 呼叫
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
     * F3c fix round 2：在排程的 callback（{@code waitAndRun}）裡呼叫 {@link M5UniverseHold#awaitConvergedThenComplete}，第一輪不能完成（ready 未成立），
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
     * F4a（spec §8.2、§7.3、§6.3、§6.4；取代 F3d 的 RESOLVE stub test）：真原艙上的 DORMANT NEW 收據，參與者 P 由艙外進入（已供電、enabled）
     * → 提交 COLLAPSING（Destination 是 allocation token 的推導值，DORMANT 續行取得來源 ticket）。RESOLVE 之前斷電：RESOLVE 不需要供電，照樣依序
     * 完成 allocation entry（推導值，恰一筆）、catalog（EAGER_ENABLED，恰好多 1 筆）、lifecycle 物化（exact ACTIVE world）與 discovery
     * （firstObserver＝P、gameplayEligible），記一則 INFO「目的地已確定」，不進 SEALED。期間 P 開關門不影響塌縮（spec §3 詮釋 3）。
     * <p>F4b-2（spec §7.3、§7.1；F4a quality Q2-m6）：RESOLVE 之後的 PREPARE 閘門要求已供電。第一次評估（RESOLVE 的下一個 tick）時沒有供電 → 停在 WAITING，
     * 不進入 PREPARE、不加目的世界的 ticket；之後恢復供電，但 P 一直在艙內，沒有 §7.1 的邊緣觸發，仍停在 WAITING（F4a 的 PREPARE stub 已移除，所以改名）。
     * 這個 session 從頭到尾沒有任何 WARN／ERROR（{@code noWarnOrError}），也沒有塌縮流程的 WARN／ERROR（{@code collapseFlowNeverWarned}，含不帶 sid 的
     * tick 層級 log）。收尾以終局 API 移除 record（宇宙保留），catalog 增量 1。不需要原生 checkpoint，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_resolve_new", tickLimit = 400)
    public void dormant_new_receipt_resolves_without_power_then_waits_for_an_entry_edge(TestContext context) {
        begin(context, "dormant_new_receipt_resolves_without_power_then_waits_for_an_entry_edge");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var witness = new LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            at(context, registered + 2, hooks, () -> {
                var committed = journal.flushedRecords().get(sid);
                witness.put("entryEdgeCommits", committed != null && committed.state() == SessionState.COLLAPSING
                        && new CollapseDestination.Universe(id).equals(committed.collapse().destination()));
                witness.put("dormantResumeTakesTicket", M4CandidateTestAccess.sourceTicketHeld(sid));
                // RESOLVE（這個 tick 的 END_SERVER_TICK）之前斷電，並由 P 打開原艙門。
                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                witness.put("resolveDoorOpens", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
            });
            at(context, registered + 3, hooks, () -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("resolvedWithoutPower", !world.isReceivingRedstonePower(frame.controllerPos())
                        && record != null && record.state() == SessionState.COLLAPSING);
                witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
                var catalog = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("catalogEagerEnabled", catalog != null && catalog.desiredAvailability() == DesiredAvailability.EAGER_ENABLED);
                witness.put("worldActive", UniverseLifecycleService.resolveActive(server, id).filter(active -> server.getWorld(active.getRegistryKey()) == active).isPresent());
                var discovered = CollapseAuthorities.get(server).discovery(id);
                witness.put("discoveredByP", discovered.value().filter(found -> found.gameplayEligible()
                        && found.firstObserver().equals(Optional.of(p.getUuid()))).isPresent());
                witness.put("oneResolvedInfo", capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                witness.put("resolveDoorCloses", interact(world, door, p).isAccepted() && doorAll(world, frame, false, MembraneState.NONE));
            });
            at(context, registered + 5, hooks, () -> {
                // RESOLVE 之後的第一次閘門評估（registered+3）沒有供電：WAITING，沒有進入 PREPARE、沒有目的世界的 ticket。
                var universe = UniverseLifecycleService.resolveActive(server, id).orElseThrow();
                witness.put("waitingWithoutPower", capture.matching(Level.INFO, "進入 PREPARE", sid.toString()).isEmpty()
                        && M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                // 恢復供電：P 一直在艙內，沒有邊緣觸發。
                context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
            });
            at(context, registered + 9, hooks, () -> {
                var held = journal.flushedRecords().get(sid);
                witness.put("stillWaiting", held != null && held.state() == SessionState.COLLAPSING && held.equals(journal.records().get(sid)));
                var universe = UniverseLifecycleService.resolveActive(server, id).orElseThrow();
                witness.put("noPrepareWithoutAnEntryEdge", world.isReceivingRedstonePower(frame.controllerPos())
                        && capture.matching(Level.INFO, "進入 PREPARE", sid.toString()).isEmpty() && M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                // F4a quality Q2-m6：依 sid 篩選（不分 marker），另以 marker 涵蓋塌縮流程不帶 sid 的 tick 層級 WARN／ERROR。
                witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                witness.put("collapseFlowNeverWarned", M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                // 收尾：終局 API 移除 record（宇宙保留、已 discovered），正式 API 釋放來源 ticket。
                hooks.release();
                witness.put("ticketReleasedAfterTerminal", !M4CandidateTestAccess.sourceTicketHeld(sid) && !journal.flushedRecords().containsKey(sid));
                context.assertTrue(!witness.containsValue(false), "NEW 的 RESOLVE：" + witness);
                awaitConvergedThenComplete(context, 1, 20);
            });
        });
    }

    /**
     * F4a（spec §8.1、§15 的 EXISTING）：先以 DORMANT NEW 收據 D1 經真的 RESOLVE 建立宇宙 U（allocation entry、catalog、ACTIVE world、discovery 一致），
     * 終局 D1 之後，同一座原艙寫入選中 EXISTING U 的 DORMANT 收據 D2。P 由艙外進入 → 提交 COLLAPSING（Destination＝U）→ RESOLVE 只重驗：
     * 目的地已確定、不進 SEALED，allocation ledger、catalog、discovery 的檔案都不被 D2 改動，lifecycle 仍是同一個 world instance。catalog 增量 1（只有 D1）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_resolve_existing", tickLimit = 400)
    public void existing_receipt_revalidates_the_same_universe_without_writing(TestContext context) {
        begin(context, "existing_receipt_revalidates_the_same_universe_without_writing");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var first = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var u = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) first.selectedCandidate().orElseThrow()).intent().allocationToken());
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // D1：提交在 registered+1、RESOLVE 在 registered+2；registered+3 確認 U 已建立，再以終局 API 移除 D1，P 回到艙外。
            whenTicks(context, registered + 3, 20, () -> UniverseLifecycleService.resolveActive(server, u).isPresent()
                    && capture.matching(Level.INFO, "目的地已確定", first.sessionUuid().toString()).size() == 1, created -> {
                cleanupFixtures(server, List.of(first.sessionUuid()));
                moveOutside(p, frame);
                var worldBefore = UniverseLifecycleService.resolveActive(server, u).orElseThrow();
                byte[][] before = {bytes(data, UniverseAllocationLedger.FILE_NAME), bytes(data, UniverseRegistryState.STATE_ID + ".dat"),
                        bytes(data, UniverseDiscoveryState.FILE_NAME)};
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), u));
                var sid = second.sessionUuid();
                at(context, created + 1, hooks, () -> moveInside(p, frame));
                at(context, created + 4, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("existingCommitted", record != null && record.state() == SessionState.COLLAPSING
                            && new CollapseDestination.Universe(u).equals(record.collapse().destination()));
                    witness.put("oneResolvedInfo", capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                    witness.put("noSealedWarnOrError", capture.matching(Level.ERROR, sid.toString()).isEmpty()
                            && capture.matching(Level.WARN, "SEALED", sid.toString()).isEmpty());
                    witness.put("authoritiesUnchanged", Arrays.equals(before[0], bytes(data, UniverseAllocationLedger.FILE_NAME))
                            && Arrays.equals(before[1], bytes(data, UniverseRegistryState.STATE_ID + ".dat"))
                            && Arrays.equals(before[2], bytes(data, UniverseDiscoveryState.FILE_NAME)));
                    witness.put("sameWorldInstance", UniverseLifecycleService.resolveActive(server, u).orElse(null) == worldBefore);
                    hooks.release();
                    context.assertTrue(!witness.containsValue(false), "EXISTING 的重驗：" + witness);
                    awaitConvergedThenComplete(context, 1, 20);
                });
            }, () -> "D1 的 RESOLVE 沒有建立宇宙：record=" + M5GameTestSupport.state(journal, first.sessionUuid()), hooks);
        });
    }

    /**
     * F4a（spec §8.1、§11.2、§7.3、§5、§10.7、§10.8）：DORMANT EXISTING 收據指向 discovery 沒有的 UniverseId。P 由艙外進入 → 提交 COLLAPSING →
     * RESOLVE 重驗不通過 → 以 roll-forward 寫入 SEALED(EXISTING_INELIGIBLE)。RESOLVE 之前 P 打開原艙門並站在門口：門口有 entity，所以門維持一般開門
     * （不關在 P 身上）。P 收到「量子通道無法開啟，已封存。」（翻譯 key）；記一則 ERROR「需要管理員處理」；來源 ticket 已釋放；presence 為 COLLAPSE，
     * Controller 已刷新為 ARMED（11）。永久原因：P 再次由艙外進入也不重試。catalog、allocation ledger、discovery 都不變，catalog 增量 0。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_existing_ineligible", tickLimit = 400)
    public void existing_receipt_without_a_discovered_universe_seals_permanently(TestContext context) {
        begin(context, "existing_receipt_without_a_discovered_universe_seals_permanently");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var data = server.getSavePath(WorldSavePath.ROOT).resolve("data");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var unknown = UniverseId.of(UUID.randomUUID());
            var receipt = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                    Map.of(p.getUuid(), true), unknown));
            // 基準在收據寫入之後才取：fresh 世界裡，建立收據的 candidate context 會先建立空的 discovery 檔（M4 的 loadOrCreate）。
            byte[][] before = {bytes(data, UniverseAllocationLedger.FILE_NAME), bytes(data, UniverseRegistryState.STATE_ID + ".dat"),
                    bytes(data, UniverseDiscoveryState.FILE_NAME)};
            var sid = receipt.sessionUuid();
            var witness = new LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            at(context, registered + 1, hooks, () -> { M5GameTestSupport.overlayKeys(origin.connection); moveInside(p, frame); });
            at(context, registered + 2, hooks, () -> {
                witness.put("committed", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                // RESOLVE（這個 tick 的 END_SERVER_TICK）之前：P 打開原艙門、站在門口。
                witness.put("doorOpened", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                var doorway = ChamberSpaceCoordinates.position(frame, 3.5, 1, 0.5);
                p.teleport(world, doorway.x, doorway.y, doorway.z, Set.of(), 0, 0);
                var a = ChamberGeometry.localToWorld(frame, 1, 1, 0); var b = ChamberGeometry.localToWorld(frame, 5, 5, 0);
                witness.put("standsInTheDoorway", new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1)
                        .intersects(p.getBoundingBox()));
            });
            at(context, registered + 3, hooks, () -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("sealedIneligible", record != null && record.state() == SessionState.SEALED
                        && record.collapse().sealedReason().equals(Optional.of(SealedReason.EXISTING_INELIGIBLE))
                        && record.equals(journal.records().get(sid)));
                witness.put("occupiedDoorwayStaysOpen", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("sealedMessage", M5GameTestSupport.overlayKeys(origin.connection).contains(SEALED_KEY));
                var errors = capture.matching(Level.ERROR, sid.toString());
                witness.put("oneAdminError", errors.size() == 1 && errors.getFirst().message().contains("需要管理員處理")
                        && !capture.marked(Level.ERROR, CollapseService.LOG_MARKER.getName(), sid.toString()).isEmpty());
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("presenceCollapse", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.COLLAPSE);
                witness.put("controllerRefreshedArmed", M5GameTestSupport.controller(world, frame).chamberState() == ChamberState.ARMED);
                moveOutside(p, frame);
            });
            at(context, registered + 4, hooks, () -> moveInside(p, frame));
            at(context, registered + 7, hooks, () -> {
                witness.put("permanentNeverRetried", receiptStillSealed(journal, sid)
                        && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                witness.put("authoritiesUnchanged", Arrays.equals(before[0], bytes(data, UniverseAllocationLedger.FILE_NAME))
                        && Arrays.equals(before[1], bytes(data, UniverseRegistryState.STATE_ID + ".dat"))
                        && Arrays.equals(before[2], bytes(data, UniverseDiscoveryState.FILE_NAME)));
                witness.put("errorStillOnce", capture.matching(Level.ERROR, sid.toString()).size() == 1);
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "EXISTING 重驗不通過進 SEALED：" + witness);
                awaitConvergedThenComplete(context, 0, 20);
            });
        });
    }

    /**
     * F4a（spec §8.3、§11.2、§7.3、§10.8；plan Step 1「注入物化失敗」）：DORMANT NEW 收據的 RESOLVE 在物化時失敗（testmod 的 {@code MaterializeFaultMixin}：
     * backend 回 {@code FAILED_ROLLED_BACK}，沒有建立 world）。catalog 的這筆 checked 改為 DISABLED（已寫出、不 dirty），進 SEALED(MATERIALIZE_FAILED)；
     * 不追加 discovery。RESOLVE 之前 P 在艙內打開了原艙門（門口沒有人）：進入 SEALED 時門改為關閉，之後 P 照一般方式可以再打開；來源 ticket 已釋放，
     * P 收到「量子通道無法開啟，已封存。」；記一則「只在重啟之後重試」的 WARN、沒有 ERROR。per-session 退避期過後 P 再次由艙外進入也不重試
     * （restart-only，同一個 server instance 內不重試）。lifecycle 對同一個 descriptor 直接拒絕：暫時把 catalog 改回 EAGER_ENABLED 越過前提，
     * {@code materializeAllocated} 回 {@code FAILED_BEFORE}、不再呼叫 backend，之後改回 DISABLED。catalog 增量 1（DISABLED）。重啟之後的重試屬 F6。
     * F4a fix round 2（m-1）：封存之後再以 {@code DestinationResolver.forServer} 取 resolver，拿到同一個，而且看得到本 instance 已用掉的物化額度。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_materialize_failed", tickLimit = 600)
    public void materialize_failure_disables_the_record_and_seals_until_a_restart(TestContext context) {
        begin(context, "materialize_failure_disables_the_record_and_seals_until_a_restart");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var key = UniverseKeys.world(id, DimensionRole.OVERWORLD);
            var fault = M5GameTestSupport.installMaterializeFault(context, id, hooks);
            var witness = new LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            at(context, registered + 1, hooks, () -> { M5GameTestSupport.overlayKeys(origin.connection); moveInside(p, frame); });
            at(context, registered + 2, hooks, () -> {
                witness.put("committed", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                // RESOLVE（這個 tick 的 END_SERVER_TICK）之前，P 在艙內打開原艙門；門口沒有人。
                witness.put("doorOpenedBeforeResolve", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
            });
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedMaterializeFailed", sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED));
                var catalog = UniverseRegistryState.get(server);
                var flushed = catalog.flushedRecords().get(id);
                witness.put("catalogDisabledAndWritten", flushed != null && flushed.desiredAvailability() == DesiredAvailability.DISABLED
                        && !catalog.isDirty() && flushed.equals(catalog.records().get(id)));
                witness.put("noWorld", UniverseLifecycleService.resolveActive(server, id).isEmpty() && server.getWorld(key) == null);
                witness.put("backendCalledOnce", fault.calls() == 1);
                // F4a fix round 2（S-m2）：materializeAllocated 把這次 FAILED_ROLLED_BACK 記入 lifecycle Context 的 materialize failures（停服驗證依它核對）。
                witness.put("lifecycleRecordedTheRolledBackFailure",
                        UniverseLifecycleService.materializeFailures(server, id, MaterializeResult.Status.FAILED_ROLLED_BACK) == 1);
                witness.put("noDiscovery", CollapseAuthorities.get(server).discovery(id).status() == CollapseAuthorities.Lookup.Status.ABSENT);
                witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
                // F4a fix round 2（m-1）：每個 server instance 恰好一個 resolver（一份物化額度），不依賴 CollapseService 的快取。再取一次拿到同一個；以只在
                // 記憶體的 COLLAPSING view（不寫 journal）再解析一次：本 instance 已用掉這個宇宙的物化額度，直接 MATERIALIZE_FAILED，不改回 EAGER_ENABLED、不再物化。
                var resolver = DestinationResolver.forServer(server);
                witness.put("oneResolverPerServer", resolver == DestinationResolver.forServer(server));
                var sealedRecord = journal.flushedRecords().get(sid);
                var again = sealedRecord == null || sealedRecord.state() != SessionState.SEALED ? null
                        : resolver.resolve(sealedRecord.withCollapse(SessionState.COLLAPSING, sealedRecord.collapse().retry()));
                witness.put("anotherLookupSeesTheSpentQuota", again instanceof ResolveResult.Sealed spent && spent.reason() == SealedReason.MATERIALIZE_FAILED
                        && spent.failure().getMessage().contains("已用掉") && fault.calls() == 1
                        && UniverseRegistryState.get(server).flushedRecords().get(id).desiredAvailability() == DesiredAvailability.DISABLED);
                witness.put("doorClosedOnSeal", doorAll(world, frame, false, MembraneState.NONE));
                witness.put("sealedMessage", M5GameTestSupport.overlayKeys(origin.connection).contains(SEALED_KEY));
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("oneRestartOnlyWarnAndNoError", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(MATERIALIZE_FAILED)", "只在重啟之後", sid.toString()).size() == 1
                        && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                // spec §11.2：SEALED 時任何玩家都可以照一般方式開門。
                witness.put("doorOpensAgain", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                moveOutside(p, frame);
            });
            // per-session 退避（200）過後再由艙外進入：MATERIALIZE_FAILED 只在重啟之後重試，本 instance 不重試。
            at(context, registered + 210, hooks, () -> moveInside(p, frame));
            at(context, registered + 213, hooks, () -> {
                witness.put("noRetryThisInstance", sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED) && fault.calls() == 1
                        && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                // lifecycle：同一個 instance 內物化失敗過的 descriptor 直接拒絕（不呼叫 backend）。暫時把 catalog 改回 EAGER_ENABLED 才能越過前提，之後改回 DISABLED。
                var catalog = UniverseRegistryState.get(server);
                catalog.changeAvailability(id, DesiredAvailability.EAGER_ENABLED); catalog.flush(server);
                var outcome = UniverseLifecycleService.materializeAllocated(server, id);
                catalog.changeAvailability(id, DesiredAvailability.DISABLED); catalog.flush(server);
                witness.put("lifecycleRefusesTheFailedDescriptor", outcome.status() == UniverseLifecycleService.MaterializeOutcome.Status.FAILED_BEFORE
                        && fault.calls() == 1 && server.getWorld(key) == null
                        && UniverseLifecycleService.materializeFailures(server, id, MaterializeResult.Status.FAILED_ROLLED_BACK) == 1);
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "物化失敗：" + witness + " outcome=" + outcome);
                awaitConvergedThenComplete(context, 1, 20);
            });
        });
    }

    /**
     * F4a（spec §6 roll-forward、§11.2；plan Step 1「ALLOCATION_IO_FAILED 經邊緣觸發＋退避重試成功，而且沒有第二個宇宙」）：DORMANT NEW 收據的 RESOLVE
     * 寫 allocation entry 時，atomic move 之後的讀回失敗（testmod 的 {@code CheckedNbtFileReadFaultMixin}）。entry 已落地、ledger 未確認，進
     * SEALED(ALLOCATION_IO_FAILED)，catalog 沒有配置；ledger 的 WARN 帶 {@code TICK_LOG_MARKER}（F4a 補上）。退避期內的邊緣觸發不重試；退避期過後
     * P 由艙外進入 → SEALED→COLLAPSING（取回來源 ticket）→ RESOLVE 先收斂 ledger、沿用同一筆 entry，再配置 catalog、物化、追加 discovery。
     * allocation entry 恰一筆，catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_allocation_io", tickLimit = 600)
    public void allocation_write_fault_seals_then_retries_after_the_backoff_with_one_universe(TestContext context) {
        begin(context, "allocation_write_fault_seals_then_retries_after_the_backoff_with_one_universe");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var fault = M5GameTestSupport.installCheckedReadFault(context, UniverseAllocationLedger.FILE_NAME, 0, 1, hooks);
            var authorities = CollapseAuthorities.get(server);
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // RESOLVE 在 registered+2 的 END_SERVER_TICK：寫 allocation entry 時讀回失敗。
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedAllocationIo", sealedWith(journal, sid, SealedReason.ALLOCATION_IO_FAILED));
                witness.put("ledgerUnconfirmed", authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER));
                witness.put("landedEntryKept", allocationEntries(server, id) == 1);
                witness.put("noCatalogYet", UniverseRegistryState.get(server).records().get(id) == null);
                witness.put("readbackFaultedOnce", fault.hits() == 1);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("oneRetryableWarn", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(ALLOCATION_IO_FAILED)", "退避期之外", sid.toString()).size() == 1);
                // F4a-0 裁示 1：權威的 WARN 帶 TICK_LOG_MARKER，collapseFlowNeverWarned 依 marker 看得到它。這則 log 屬於權威、不屬於任何 session，
                // 訊息沒有 sid 可篩選，所以依 README「log 斷言」不斷言絕對筆數，只要求至少一則（F4a fix round 2，m-7）。
                witness.put("ledgerWarnCarriesTheTickMarker", capture.marked(Level.WARN, CollapseService.TICK_LOG_MARKER.getName(),
                        "ALLOCATION_LEDGER 的 checked 寫入未確認").size() >= 1);
                moveOutside(p, frame);
            });
            // 退避期內的邊緣觸發：不重試。
            at(context, registered + 5, hooks, () -> moveInside(p, frame));
            at(context, registered + 8, hooks, () -> {
                witness.put("noRetryWithinTheBackoff", sealedWith(journal, sid, SealedReason.ALLOCATION_IO_FAILED)
                        && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                moveOutside(p, frame);
            });
            // 封存在 registered+2 的 END_SERVER_TICK；200 ticks 的退避（per-session 與 ledger 的收斂各一個）之後再由艙外進入，留 5 ticks 餘裕。
            at(context, registered + 2 + 205, hooks, () -> moveInside(p, frame));
            whenTicks(context, registered + 2 + 206, 20, () -> capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1, resolved -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("retriedOnce", capture.matching(Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                witness.put("collapsingAgainWithTicket", record != null && record.state() == SessionState.COLLAPSING && M4CandidateTestAccess.sourceTicketHeld(sid));
                // 收斂完成的 INFO 同樣屬於權威、沒有 sid：只要求至少一則（m-7）；「已收斂」由 unconfirmed 判定。
                witness.put("ledgerConvergedFirst", !authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER)
                        && capture.marked(Level.INFO, CollapseService.TICK_LOG_MARKER.getName(), "ALLOCATION_LEDGER 收斂完成").size() >= 1);
                witness.put("stillOneAllocationEntry", allocationEntries(server, id) == 1);
                var catalog = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("oneUniverse", catalog != null && catalog.desiredAvailability() == DesiredAvailability.EAGER_ENABLED
                        && UniverseLifecycleService.resolveActive(server, id).isPresent()
                        && authorities.discovery(id).status() == CollapseAuthorities.Lookup.Status.FOUND);
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "allocation 寫入失敗之後的重試：" + witness);
                awaitConvergedThenComplete(context, 1, 20);
            }, () -> "退避之後的重試沒有確定目的地：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4a（spec §6 讀取失敗的分類、§11.2；plan Step 1「注入 IO → AUTHORITY_UNREADABLE」）：DORMANT NEW 收據的 RESOLVE 在第 4 步讀 discovery 時 IO 例外
     * （testmod 的 {@code CheckedNbtFileReadFaultMixin}）。allocation entry、catalog（EAGER_ENABLED）與 ACTIVE world 都已完成（discovery 在物化之後），
     * discovery 沒有追加；進 SEALED(AUTHORITY_UNREADABLE)，記一則「只在重啟之後重試」的 WARN。per-session 退避期過後 P 再次由艙外進入也不重試
     * （restart-only）。catalog 增量 1。重啟之後的重試屬 F6 的重啟矩陣。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_authority_unreadable", tickLimit = 600)
    public void discovery_read_fault_seals_unreadable_until_a_restart(TestContext context) {
        begin(context, "discovery_read_fault_seals_unreadable_until_a_restart");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            // 建立收據的 candidate context 會讀 discovery（fresh 世界時先建立）；注入排在那之後。
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var fault = M5GameTestSupport.installCheckedReadFault(context, UniverseDiscoveryState.FILE_NAME, 0, 1, hooks);
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedUnreadable", sealedWith(journal, sid, SealedReason.AUTHORITY_UNREADABLE));
                var catalog = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("materializedBeforeDiscovery", catalog != null && catalog.desiredAvailability() == DesiredAvailability.EAGER_ENABLED
                        && UniverseLifecycleService.resolveActive(server, id).isPresent() && allocationEntries(server, id) == 1);
                witness.put("discoveryReadFaultedOnce", fault.hits() == 1);
                witness.put("noDiscovery", CollapseAuthorities.get(server).discovery(id).status() == CollapseAuthorities.Lookup.Status.ABSENT);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("oneRestartOnlyWarnAndNoError", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(AUTHORITY_UNREADABLE)", "只在重啟之後", sid.toString()).size() == 1
                        && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                moveOutside(p, frame);
            });
            at(context, registered + 210, hooks, () -> moveInside(p, frame));
            at(context, registered + 213, hooks, () -> {
                witness.put("noRetryThisInstance", sealedWith(journal, sid, SealedReason.AUTHORITY_UNREADABLE)
                        && capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "discovery 讀取失敗：" + witness);
                awaitConvergedThenComplete(context, 1, 20);
            });
        });
    }

    /**
     * F4a fix round 1（I-1；spec §6 收斂規則表、§6.2、§8.3、§11.2）：catalog 中本 session 推導的 UniverseId 已是 DISABLED（§8.3 已完成，例如改為
     * DISABLED 之後、寫入 SEALED 之前 crash，重啟後仍是 COLLAPSING）。第一次 RESOLVE 在改回 EAGER_ENABLED 之前就以別的原因封存：allocation entry 的
     * 讀回失敗 → SEALED(ALLOCATION_IO_FAILED)。退避期過後 P 由艙外進入 → 重試 → RESOLVE 仍把 DISABLED 視為 §8.3 已完成：checked 改回 EAGER_ENABLED、
     * 物化、追加 discovery，目的地確定；不會因為最近一次封存的原因不是 MATERIALIZE_FAILED 就判為 AUTHORITY_CORRUPT。catalog 增量 1（fixture 預先配置的那筆）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_disabled_retry", tickLimit = 600)
    public void disabled_record_of_the_session_is_reenabled_after_an_allocation_io_retry(TestContext context) {
        begin(context, "disabled_record_of_the_session_is_reenabled_after_an_allocation_io_retry");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            // fixture：§8.3 已完成的 catalog（這個 UniverseId 已配置、已改為 DISABLED、已落盤），沒有 world。
            var catalog = UniverseRegistryState.get(server);
            catalog.allocateOverworld(id.value(), nextOrdinal(catalog));
            catalog.changeAvailability(id, DesiredAvailability.DISABLED);
            catalog.flush(server);
            var fault = M5GameTestSupport.installCheckedReadFault(context, UniverseAllocationLedger.FILE_NAME, 0, 1, hooks);
            var authorities = CollapseAuthorities.get(server);
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // RESOLVE 在 registered+2 的 END_SERVER_TICK：寫 allocation entry 時讀回失敗，改回 EAGER_ENABLED 之前就封存。
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedAllocationIoBeforeReenable", sealedWith(journal, sid, SealedReason.ALLOCATION_IO_FAILED) && fault.hits() == 1);
                var flushed = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("stillDisabledWithoutWorld", flushed != null && flushed.desiredAvailability() == DesiredAvailability.DISABLED
                        && UniverseLifecycleService.resolveActive(server, id).isEmpty());
                moveOutside(p, frame);
            });
            // 封存在 registered+2；per-session 與 ledger 收斂的退避（各 200）之後再由艙外進入。
            at(context, registered + 2 + 205, hooks, () -> moveInside(p, frame));
            // 重試之後等 RESOLVE 有結果：目的地確定，或又離開 COLLAPSING（修改前會進 SEALED(AUTHORITY_CORRUPT)）。
            whenTicks(context, registered + 2 + 206, 20, () -> !capture.matching(Level.INFO, "SEALED 重試", sid.toString()).isEmpty()
                    && (!capture.matching(Level.INFO, "目的地已確定", sid.toString()).isEmpty()
                        || !M5GameTestSupport.state(journal, sid).equals("COLLAPSING")), settled -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("retriedOnce", capture.matching(Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                witness.put("resolvedAfterTheRetry", record != null && record.state() == SessionState.COLLAPSING
                        && capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                witness.put("neverAuthorityCorrupt", capture.matching(Level.ERROR, sid.toString()).isEmpty()
                        && capture.matching(Level.WARN, "AUTHORITY_CORRUPT", sid.toString()).isEmpty());
                var reenabled = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("reenabledMaterializedAndDiscovered", reenabled != null && reenabled.desiredAvailability() == DesiredAvailability.EAGER_ENABLED
                        && UniverseLifecycleService.resolveActive(server, id).isPresent()
                        && authorities.discovery(id).status() == CollapseAuthorities.Lookup.Status.FOUND);
                witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "DISABLED 的重試：" + witness + " record=" + M5GameTestSupport.state(journal, sid)
                        + " sealed=" + (record == null ? "-" : record.collapse().sealedReason()));
                awaitConvergedThenComplete(context, 1, 20);
            }, () -> "重試之後 RESOLVE 沒有結果：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4a fix round 1（M-2；spec §6.2、§8.1、F2c 裁示 3）：CollapseService 的 RESOLVE 等待分支。D1（DORMANT NEW）的 discovery 追加在 atomic move
     * 之後讀回失敗（testmod 的 {@code CheckedNbtFileReadFaultMixin}）→ D1 進 SEALED(ALLOCATION_IO_FAILED)，discovery 權威未確認（已落地的 record 保留）。
     * D1 收尾之後，同一座原艙的 D2（DORMANT EXISTING，目的地是 D1 建立的宇宙 U）提交 COLLAPSING；discovery 的收斂再失敗兩次（權威的退避 200、400），
     * 所以 D2 的 RESOLVE 等待約 1400 ticks：期間一直是 COLLAPSING、不進 SEALED，連續等待超過 600 ticks 時只記一則 WARN（帶 LOG_MARKER 與 session），
     * 等待約 585 ticks 時還沒有（F4a fix round 2，S-m1）；discovery 收斂之後 D2 照常確定目的地。catalog 增量 1（D1 的 U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_resolve_wait", tickLimit = 1700)
    public void existing_resolve_waits_for_an_unconfirmed_discovery_without_sealing_and_warns_once(TestContext context) {
        begin(context, "existing_resolve_waits_for_an_unconfirmed_discovery_without_sealing_and_warns_once");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var first = origin.receipt(chamber, Map.of(p.getUuid(), true), true);
            var u = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) first.selectedCandidate().orElseThrow()).intent().allocationToken());
            // 建立收據的 candidate context 會讀 discovery：D2 的收據也在注入之前建立，D1 收尾之後才寫入 journal。
            var second = M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(p.getUuid(), true), u);
            var sid1 = first.sessionUuid(); var sid2 = second.sessionUuid();
            // D1 的追加先讀一次正式檔（放行），atomic move 之後的讀回失敗；之後兩次收斂的讀回也失敗，第三次收斂成功。
            var fault = M5GameTestSupport.installCheckedReadFault(context, UniverseDiscoveryState.FILE_NAME, 1, 3, hooks);
            var authorities = CollapseAuthorities.get(server);
            var witness = new LinkedHashMap<String, Boolean>();
            origin.own(first);
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            at(context, registered + 3, hooks, () -> {
                witness.put("d1SealedWithAnUnconfirmedDiscovery", sealedWith(journal, sid1, SealedReason.ALLOCATION_IO_FAILED)
                        && authorities.unconfirmed(CollapseAuthorities.Authority.DISCOVERY) && fault.hits() == 1
                        && UniverseLifecycleService.resolveActive(server, u).isPresent());
                cleanupFixtures(server, List.of(sid1));
                moveOutside(p, frame);
                origin.own(second);
            });
            at(context, registered + 4, hooks, () -> moveInside(p, frame));
            // D2 的 RESOLVE 從 registered+5 起等待；超過 600 ticks 的 WARN 約在 registered+606。
            // F4a fix round 2（S-m1）：WARN 的起點不早於預期——registered+590（等待約 585 ticks）時還沒有等待的 WARN。
            at(context, registered + 590, hooks, () -> witness.put("noWaitWarnBeforeSixHundredTicks",
                    M5GameTestSupport.state(journal, sid2).equals("COLLAPSING") && waitWarnings(capture, sid2) == 0));
            at(context, registered + 620, hooks, () -> {
                witness.put("waitingWithoutSealing", M5GameTestSupport.state(journal, sid2).equals("COLLAPSING")
                        && capture.matching(Level.INFO, "目的地已確定", sid2.toString()).isEmpty()
                        && authorities.unconfirmed(CollapseAuthorities.Authority.DISCOVERY));
                witness.put("oneWaitWarnAfterSixHundredTicks", waitWarnings(capture, sid2) == 1);
            });
            at(context, registered + 1300, hooks, () -> witness.put("stillWaitingWithTheSameWarn",
                    M5GameTestSupport.state(journal, sid2).equals("COLLAPSING") && waitWarnings(capture, sid2) == 1
                            && capture.matching(Level.INFO, "目的地已確定", sid2.toString()).isEmpty()));
            whenTicks(context, registered + 1400, 60, () -> !capture.matching(Level.INFO, "目的地已確定", sid2.toString()).isEmpty(), resolved -> {
                witness.put("resolvedAfterTheDiscoveryConverged", M5GameTestSupport.state(journal, sid2).equals("COLLAPSING")
                        && !authorities.unconfirmed(CollapseAuthorities.Authority.DISCOVERY) && fault.hits() == 3);
                witness.put("warnedOnlyOnce", waitWarnings(capture, sid2) == 1);
                witness.put("neverSealed", capture.matching(Level.WARN, "塌縮進入 SEALED", sid2.toString()).isEmpty()
                        && capture.matching(Level.ERROR, sid2.toString()).isEmpty());
                hooks.release();
                context.assertTrue(!witness.containsValue(false), "RESOLVE 的等待：" + witness);
                awaitConvergedThenComplete(context, 1, 20);
            }, () -> "discovery 收斂之後 D2 沒有確定目的地：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4a fix round 2（m-5(a)；spec §6 collapse view 與 roll-forward、§11.2、§7.3、§5、§10.8）：進入 SEALED 的 journal 寫出未確認（{@code ROLL_FORWARD_PENDING}）時，
     * SEALED 依 collapse view 照樣生效。DORMANT NEW 收據的 RESOLVE 物化失敗（testmod 的 {@code MaterializeFaultMixin}）→ catalog 改為 DISABLED →
     * 進入 SEALED(MATERIALIZE_FAILED) 的 C→S 寫出在 flush 開頭受控失敗一次（flush 探針＋{@code FlushFault}，比照 G-SEAL）。未確認的那一個 tick：門已關（P 在
     * RESOLVE 之前打開了門，門口沒有人）、來源 ticket 已釋放、P 收到「量子通道無法開啟，已封存。」，這個 session 的「寫入 SEALED」WARN 恰好一則。下一個 tick
     * recovery 的 RETRY_FLUSH 確認同一份 SEALED（探針看到的第 2 次寫出）；之後不重跑 RESOLVE：沒有「目的地已確定」、backend 只物化過一次、catalog 這筆仍是
     * DISABLED、allocation entry 恰一筆（catalog 增量 1，沒有第二個宇宙）。原生 autosave 不經過探針、會直接把未確認的寫出帶出去，所以在
     * {@code afterAutosaveClear} 的窗口內開始，確認時斷言窗口內 journal 的每一次寫出都經過探針、只帶這個 session 的變更。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_sealed_pending", tickLimit = 400)
    public void sealed_entry_with_an_unconfirmed_write_still_closes_the_door_and_releases_the_ticket(TestContext context) {
        begin(context, "sealed_entry_with_an_unconfirmed_write_still_closes_the_door_and_releases_the_ticket");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var materialize = M5GameTestSupport.installMaterializeFault(context, id, hooks);
            // 「current 是 SEALED、flushed 仍是 COLLAPSING」的寫出：第 1 次（seal 的 commitRollForward）受控失敗，之後照常（recovery 的 RETRY_FLUSH）。
            var flush = new M5GameTestSupport.FlushFault(server, "F4a fix round 2 GameTest 受控：進入 SEALED 的 journal 寫出失敗", 1, state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return staged != null && durable != null && staged.state() == SessionState.SEALED && durable.state() == SessionState.COLLAPSING;
            });
            // 失敗路徑：先解除探針（hooks 後進先出），再把尚未確認的寫出帶出去，最後才由 DormantOrigin 的收尾以終局 API 移除。
            hooks.add(() -> { if (journal.rollForwardPending(sid)) journal.flush(server); });
            var witness = new LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            M5GameTestSupport.afterAutosaveClear(context, registered + 1, "sealed_pending", 40, (start, autosave) -> {
                M5GameTestSupport.installFlushProbe(context, flush, hooks);
                M5GameTestSupport.overlayKeys(origin.connection);
                // 提交在 start 的 END_SERVER_TICK；RESOLVE（物化失敗 → 進入 SEALED）在 start+1 的 END_SERVER_TICK。
                moveInside(p, frame);
                at(context, start + 1, hooks, () -> {
                    witness.put("committed", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                    // RESOLVE 之前，P 在艙內打開原艙門；門口沒有人。
                    witness.put("doorOpenedBeforeResolve", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                });
                whenTicks(context, start + 2, 10, () -> journal.rollForwardPending(sid), pending -> {
                    var view = journal.collapseView(sid).orElse(null);
                    witness.put("pendingSealedView", view != null && view.state() == SessionState.SEALED
                            && view.collapse().sealedReason().equals(Optional.of(SealedReason.MATERIALIZE_FAILED))
                            && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && flush.failures() == 1);
                    witness.put("doorClosedWhilePending", doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("ticketReleasedWhilePending", !M4CandidateTestAccess.sourceTicketHeld(sid));
                    witness.put("sealedMessageWhilePending", M5GameTestSupport.overlayKeys(origin.connection).contains(SEALED_KEY));
                    witness.put("oneSealWriteWarn", sealWriteWarnings(capture, sid) == 1);
                    whenTicks(context, pending + 1, 10, () -> sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED) && !journal.rollForwardPending(sid), confirmed -> {
                        var attempts = flush.ticks();
                        witness.put("confirmedByTheRetryFlush", flush.failures() == 1 && attempts.size() == 2 && view != null
                                && view.equals(journal.flushedRecords().get(sid)));
                        var journalWrites = autosave.journalWrites(server, attempts, Set.of(sid));
                        witness.put("onlyProbedJournalWrites", journalWrites.onlyProbed());
                        at(context, confirmed + 5, hooks, () -> {
                            witness.put("noResolveAfterTheConfirmation", sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED)
                                    && capture.matching(Level.INFO, "目的地已確定", sid.toString()).isEmpty() && materialize.calls() == 1);
                            var catalog = UniverseRegistryState.get(server).flushedRecords().get(id);
                            witness.put("oneDisabledUniverse", catalog != null && catalog.desiredAvailability() == DesiredAvailability.DISABLED
                                    && allocationEntries(server, id) == 1);
                            witness.put("sealWriteWarnStillOnce", sealWriteWarnings(capture, sid) == 1);
                            hooks.release();
                            context.assertTrue(!witness.containsValue(false), "進入 SEALED 的寫出未確認：" + witness + " attempts=" + attempts
                                    + " start=" + autosave.start() + " autosaveAt=" + autosave.autosaveAt() + " journalWrites=" + journalWrites.problems());
                            awaitConvergedThenComplete(context, 1, 20);
                        });
                    }, () -> "RETRY_FLUSH 沒有確認 SEALED：record=" + M5GameTestSupport.state(journal, sid) + " attempts=" + flush.ticks(), hooks);
                }, () -> "進入 SEALED 的寫出沒有停在未確認：record=" + M5GameTestSupport.state(journal, sid) + " attempts=" + flush.ticks()
                        + " witness=" + witness, hooks);
            }, hooks);
        });
    }

    /** 這個 session 的「目的地解析持續等待」WARN（帶 LOG_MARKER）。 */
    private static int waitWarnings(LogCapture capture, UUID sid) {
        return capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(),
                "目的地解析持續等待", sid.toString()).size();
    }

    /** spec §10.8「進入 SEALED」（以字面值斷言，不信任 production 常數）。 */
    private static final String SEALED_KEY = "message.quantumchamber.m5.sealed";

    /**
     * 原版 {@code GameTestState} 的 runAtTick 表（{@code ticksByRunnables}）目前的項目數，只讀。{@code GameTestState} 經 {@code M5GameTestSupport.gameTestState}、表經 testmod 的
     * accessor mixin {@code GameTestStateRunnablesTestAccessor} 取得（F6-0，F4a-0 quality re-review Minor 3：讀 {@code TestContext.test} 的反射收斂成一處）。
     */
    private static int scheduledCount(TestContext context) {
        return ((GameTestStateRunnablesTestAccessor) M5GameTestSupport.gameTestState(context)).quantumchamberTest$getTicksByRunnables().size();
    }

    /** 與 M5 的 NEW 配置相同的 ordinal：flushed 的最大 ordinal＋1。 */
    private static long nextOrdinal(UniverseRegistryState catalog) {
        return catalog.flushedRecords().values().stream().mapToLong(record -> record.definition().allocationOrdinal()).max().orElse(-1) + 1;
    }

    private static List<String> worldKeys(MinecraftServer server) {
        return StreamSupport.stream(server.getWorlds().spliterator(), false)
                .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList();
    }
}
