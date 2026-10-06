package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.DestinationResolver;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.ResolveResult;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import net.minecraft.util.WorldSavePath;
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
     * F4a（spec §8.2、§7.3、§6.3、§6.4；取代 F3d 的 RESOLVE stub test）：真原艙上的 DORMANT NEW 收據，參與者 P 由艙外進入（已供電、enabled）
     * → 提交 COLLAPSING（Destination 是 allocation token 的推導值，DORMANT 續行取得來源 ticket）。RESOLVE 之前斷電：RESOLVE 不需要供電，照樣依序
     * 完成 allocation entry（推導值，恰一筆）、catalog（EAGER_ENABLED，恰好多 1 筆）、lifecycle 物化（exact ACTIVE world）與 discovery
     * （firstObserver＝P、gameplayEligible），記一則 INFO「目的地已確定」，不進 SEALED。期間 P 開關門不影響塌縮（spec §3 詮釋 3）。之後 F4b 之前
     * 停在 WAITING：每個 session 只記一次 WARN「準備階段尚未實作」，恢復供電也不重複。收尾以終局 API 移除 record（宇宙保留），catalog 增量 1。
     * 不需要原生 checkpoint，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_resolve_new", tickLimit = 400)
    public void dormant_new_receipt_resolves_without_power_then_waits_at_the_prepare_stub(TestContext context) {
        begin(context, "dormant_new_receipt_resolves_without_power_then_waits_at_the_prepare_stub");
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
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            at(context, registered + 2, hooks, () -> {
                var committed = journal.flushedRecords().get(sid);
                witness.put("entryEdgeCommits", committed != null && committed.state() == SessionState.COLLAPSING
                        && new CollapseDestination.Universe(id).equals(committed.collapse().destination()));
                witness.put("dormantResumeTakesTicket", M4CandidateTestAccess.sourceTicketHeld(sid));
                // RESOLVE（這個 tick 的 END_SERVER_TICK）之前斷電，並由 P 打開原艙門。
                context.setBlockState(DormantOrigin.POWER, net.minecraft.block.Blocks.AIR);
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
                        && found.firstObserver().equals(java.util.Optional.of(p.getUuid()))).isPresent());
                witness.put("oneResolvedInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                witness.put("resolveDoorCloses", interact(world, door, p).isAccepted() && doorAll(world, frame, false, MembraneState.NONE));
            });
            at(context, registered + 5, hooks, () -> {
                witness.put("oneWaitingStubWarn", capture.matching(org.apache.logging.log4j.Level.WARN, "準備階段尚未實作", sid.toString()).size() == 1);
                // 恢復供電：F4b 之前 UNIVERSE 不評估閘門，仍停在 WAITING。
                context.setBlockState(DormantOrigin.POWER, net.minecraft.block.Blocks.REDSTONE_BLOCK);
            });
            at(context, registered + 9, hooks, () -> {
                var held = journal.flushedRecords().get(sid);
                witness.put("stillWaiting", held != null && held.state() == SessionState.COLLAPSING && held.equals(journal.records().get(sid)));
                witness.put("stubWarnedOnce", capture.matching(org.apache.logging.log4j.Level.WARN, "準備階段尚未實作", sid.toString()).size() == 1);
                witness.put("onlyTheStubWarnAndNoError", capture.matching(org.apache.logging.log4j.Level.WARN, sid.toString()).size() == 1
                        && capture.matching(org.apache.logging.log4j.Level.ERROR).isEmpty());
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
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // D1：提交在 registered+1、RESOLVE 在 registered+2；registered+3 確認 U 已建立，再以終局 API 移除 D1，P 回到艙外。
            whenTicks(context, registered + 3, 20, () -> UniverseLifecycleService.resolveActive(server, u).isPresent()
                    && capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", first.sessionUuid().toString()).size() == 1, created -> {
                cleanupFixtures(server, List.of(first.sessionUuid()));
                moveOutside(p, frame);
                var worldBefore = UniverseLifecycleService.resolveActive(server, u).orElseThrow();
                byte[][] before = {bytes(data, UniverseAllocationLedger.FILE_NAME), bytes(data, UniverseRegistryState.STATE_ID + ".dat"),
                        bytes(data, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME)};
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), u));
                var sid = second.sessionUuid();
                at(context, created + 1, hooks, () -> moveInside(p, frame));
                at(context, created + 4, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("existingCommitted", record != null && record.state() == SessionState.COLLAPSING
                            && new CollapseDestination.Universe(u).equals(record.collapse().destination()));
                    witness.put("oneResolvedInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                    witness.put("noSealedWarnOrError", capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty()
                            && capture.matching(org.apache.logging.log4j.Level.WARN, "SEALED", sid.toString()).isEmpty());
                    witness.put("authoritiesUnchanged", java.util.Arrays.equals(before[0], bytes(data, UniverseAllocationLedger.FILE_NAME))
                            && java.util.Arrays.equals(before[1], bytes(data, UniverseRegistryState.STATE_ID + ".dat"))
                            && java.util.Arrays.equals(before[2], bytes(data, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME)));
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
                    bytes(data, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME)};
            var sid = receipt.sessionUuid();
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            var door = ChamberGeometry.localToWorld(frame, 3, 3, 0);
            at(context, registered + 1, hooks, () -> { M5GameTestSupport.overlayKeys(origin.connection); moveInside(p, frame); });
            at(context, registered + 2, hooks, () -> {
                witness.put("committed", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                // RESOLVE（這個 tick 的 END_SERVER_TICK）之前：P 打開原艙門、站在門口。
                witness.put("doorOpened", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                var doorway = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, 3.5, 1, 0.5);
                p.teleport(world, doorway.x, doorway.y, doorway.z, java.util.Set.of(), 0, 0);
                var a = ChamberGeometry.localToWorld(frame, 1, 1, 0); var b = ChamberGeometry.localToWorld(frame, 5, 5, 0);
                witness.put("standsInTheDoorway", new net.minecraft.util.math.Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()),
                        Math.min(a.getZ(), b.getZ()), Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1)
                        .intersects(p.getBoundingBox()));
            });
            at(context, registered + 3, hooks, () -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("sealedIneligible", record != null && record.state() == SessionState.SEALED
                        && record.collapse().sealedReason().equals(java.util.Optional.of(dev.quantumchamber.collapse.SealedReason.EXISTING_INELIGIBLE))
                        && record.equals(journal.records().get(sid)));
                witness.put("occupiedDoorwayStaysOpen", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("sealedMessage", M5GameTestSupport.overlayKeys(origin.connection).contains(SEALED_KEY));
                var errors = capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString());
                witness.put("oneAdminError", errors.size() == 1 && errors.getFirst().message().contains("需要管理員處理")
                        && !capture.marked(org.apache.logging.log4j.Level.ERROR, dev.quantumchamber.collapse.CollapseService.LOG_MARKER.getName(), sid.toString()).isEmpty());
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("presenceCollapse", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.COLLAPSE);
                witness.put("controllerRefreshedArmed", M5GameTestSupport.controller(world, frame).chamberState() == dev.quantumchamber.chamber.ChamberState.ARMED);
                moveOutside(p, frame);
            });
            at(context, registered + 4, hooks, () -> moveInside(p, frame));
            at(context, registered + 7, hooks, () -> {
                witness.put("permanentNeverRetried", receiptStillSealed(journal, sid)
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                witness.put("authoritiesUnchanged", java.util.Arrays.equals(before[0], bytes(data, UniverseAllocationLedger.FILE_NAME))
                        && java.util.Arrays.equals(before[1], bytes(data, UniverseRegistryState.STATE_ID + ".dat"))
                        && java.util.Arrays.equals(before[2], bytes(data, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME)));
                witness.put("errorStillOnce", capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).size() == 1);
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
            var key = dev.quantumchamber.universe.UniverseKeys.world(id, DimensionRole.OVERWORLD);
            var fault = M5GameTestSupport.installMaterializeFault(context, id, hooks);
            var witness = new java.util.LinkedHashMap<String, Boolean>();
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
                witness.put("oneRestartOnlyWarnAndNoError", capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(MATERIALIZE_FAILED)", "只在重啟之後", sid.toString()).size() == 1
                        && capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty());
                // spec §11.2：SEALED 時任何玩家都可以照一般方式開門。
                witness.put("doorOpensAgain", interact(world, door, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                moveOutside(p, frame);
            });
            // per-session 退避（200）過後再由艙外進入：MATERIALIZE_FAILED 只在重啟之後重試，本 instance 不重試。
            at(context, registered + 210, hooks, () -> moveInside(p, frame));
            at(context, registered + 213, hooks, () -> {
                witness.put("noRetryThisInstance", sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED) && fault.calls() == 1
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
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
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // RESOLVE 在 registered+2 的 END_SERVER_TICK：寫 allocation entry 時讀回失敗。
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedAllocationIo", sealedWith(journal, sid, SealedReason.ALLOCATION_IO_FAILED));
                witness.put("ledgerUnconfirmed", authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER));
                witness.put("landedEntryKept", allocationEntries(server, id) == 1);
                witness.put("noCatalogYet", UniverseRegistryState.get(server).records().get(id) == null);
                witness.put("readbackFaultedOnce", fault.hits() == 1);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("oneRetryableWarn", capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(ALLOCATION_IO_FAILED)", "退避期之外", sid.toString()).size() == 1);
                // F4a-0 裁示 1：權威的 WARN 帶 TICK_LOG_MARKER，collapseFlowNeverWarned 依 marker 看得到它。這則 log 屬於權威、不屬於任何 session，
                // 訊息沒有 sid 可篩選，所以依 README「log 斷言」不斷言絕對筆數，只要求至少一則（F4a fix round 2，m-7）。
                witness.put("ledgerWarnCarriesTheTickMarker", capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.TICK_LOG_MARKER.getName(),
                        "ALLOCATION_LEDGER 的 checked 寫入未確認").size() >= 1);
                moveOutside(p, frame);
            });
            // 退避期內的邊緣觸發：不重試。
            at(context, registered + 5, hooks, () -> moveInside(p, frame));
            at(context, registered + 8, hooks, () -> {
                witness.put("noRetryWithinTheBackoff", sealedWith(journal, sid, SealedReason.ALLOCATION_IO_FAILED)
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
                moveOutside(p, frame);
            });
            // 封存在 registered+2 的 END_SERVER_TICK；200 ticks 的退避（per-session 與 ledger 的收斂各一個）之後再由艙外進入，留 5 ticks 餘裕。
            at(context, registered + 2 + 205, hooks, () -> moveInside(p, frame));
            whenTicks(context, registered + 2 + 206, 20, () -> capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).size() == 1, resolved -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("retriedOnce", capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                witness.put("collapsingAgainWithTicket", record != null && record.state() == SessionState.COLLAPSING && M4CandidateTestAccess.sourceTicketHeld(sid));
                // 收斂完成的 INFO 同樣屬於權威、沒有 sid：只要求至少一則（m-7）；「已收斂」由 unconfirmed 判定。
                witness.put("ledgerConvergedFirst", !authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER)
                        && capture.marked(org.apache.logging.log4j.Level.INFO, CollapseService.TICK_LOG_MARKER.getName(), "ALLOCATION_LEDGER 收斂完成").size() >= 1);
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
            var fault = M5GameTestSupport.installCheckedReadFault(context, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME, 0, 1, hooks);
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            at(context, registered + 3, hooks, () -> {
                witness.put("sealedUnreadable", sealedWith(journal, sid, SealedReason.AUTHORITY_UNREADABLE));
                var catalog = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("materializedBeforeDiscovery", catalog != null && catalog.desiredAvailability() == DesiredAvailability.EAGER_ENABLED
                        && UniverseLifecycleService.resolveActive(server, id).isPresent() && allocationEntries(server, id) == 1);
                witness.put("discoveryReadFaultedOnce", fault.hits() == 1);
                witness.put("noDiscovery", CollapseAuthorities.get(server).discovery(id).status() == CollapseAuthorities.Lookup.Status.ABSENT);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("oneRestartOnlyWarnAndNoError", capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.LOG_MARKER.getName(),
                        "SEALED(AUTHORITY_UNREADABLE)", "只在重啟之後", sid.toString()).size() == 1
                        && capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty());
                moveOutside(p, frame);
            });
            at(context, registered + 210, hooks, () -> moveInside(p, frame));
            at(context, registered + 213, hooks, () -> {
                witness.put("noRetryThisInstance", sealedWith(journal, sid, SealedReason.AUTHORITY_UNREADABLE)
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).isEmpty());
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
            var witness = new java.util.LinkedHashMap<String, Boolean>();
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
            whenTicks(context, registered + 2 + 206, 20, () -> !capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).isEmpty()
                    && (!capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).isEmpty()
                        || !M5GameTestSupport.state(journal, sid).equals("COLLAPSING")), settled -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("retriedOnce", capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                witness.put("resolvedAfterTheRetry", record != null && record.state() == SessionState.COLLAPSING
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).size() == 1);
                witness.put("neverAuthorityCorrupt", capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty()
                        && capture.matching(org.apache.logging.log4j.Level.WARN, "AUTHORITY_CORRUPT", sid.toString()).isEmpty());
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
            var fault = M5GameTestSupport.installCheckedReadFault(context, dev.quantumchamber.candidate.UniverseDiscoveryState.FILE_NAME, 1, 3, hooks);
            var authorities = CollapseAuthorities.get(server);
            var witness = new java.util.LinkedHashMap<String, Boolean>();
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
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid2.toString()).isEmpty()
                        && authorities.unconfirmed(CollapseAuthorities.Authority.DISCOVERY));
                witness.put("oneWaitWarnAfterSixHundredTicks", waitWarnings(capture, sid2) == 1);
            });
            at(context, registered + 1300, hooks, () -> witness.put("stillWaitingWithTheSameWarn",
                    M5GameTestSupport.state(journal, sid2).equals("COLLAPSING") && waitWarnings(capture, sid2) == 1
                            && capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid2.toString()).isEmpty()));
            whenTicks(context, registered + 1400, 60, () -> !capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid2.toString()).isEmpty(), resolved -> {
                witness.put("resolvedAfterTheDiscoveryConverged", M5GameTestSupport.state(journal, sid2).equals("COLLAPSING")
                        && !authorities.unconfirmed(CollapseAuthorities.Authority.DISCOVERY) && fault.hits() == 3);
                witness.put("warnedOnlyOnce", waitWarnings(capture, sid2) == 1);
                witness.put("neverSealed", capture.matching(org.apache.logging.log4j.Level.WARN, "塌縮進入 SEALED", sid2.toString()).isEmpty()
                        && capture.matching(org.apache.logging.log4j.Level.ERROR, sid2.toString()).isEmpty());
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
            var witness = new java.util.LinkedHashMap<String, Boolean>();
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
                            && view.collapse().sealedReason().equals(java.util.Optional.of(SealedReason.MATERIALIZE_FAILED))
                            && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && flush.failures() == 1);
                    witness.put("doorClosedWhilePending", doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("ticketReleasedWhilePending", !M4CandidateTestAccess.sourceTicketHeld(sid));
                    witness.put("sealedMessageWhilePending", M5GameTestSupport.overlayKeys(origin.connection).contains(SEALED_KEY));
                    witness.put("oneSealWriteWarn", sealWriteWarnings(capture, sid) == 1);
                    whenTicks(context, pending + 1, 10, () -> sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED) && !journal.rollForwardPending(sid), confirmed -> {
                        var attempts = flush.ticks();
                        witness.put("confirmedByTheRetryFlush", flush.failures() == 1 && attempts.size() == 2 && view != null
                                && view.equals(journal.flushedRecords().get(sid)));
                        var journalWrites = autosave.journalWrites(server, attempts, java.util.Set.of(sid));
                        witness.put("onlyProbedJournalWrites", journalWrites.onlyProbed());
                        at(context, confirmed + 5, hooks, () -> {
                            witness.put("noResolveAfterTheConfirmation", sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED)
                                    && capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).isEmpty() && materialize.calls() == 1);
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

    // ---- F4b-1：投影元件（ProjectionWriter、投影保護索引、保護、載入辨識） ----

    /**
     * F4b-1（spec §9.2；plan 決策 3；plan Step 1 第 2 組的「清 interior、箱子不噴物品、逐格驗證」與第 6 組的動物）：在真的 RESOLVE 建立的宇宙 U、原艙的同 XYZ
     * 同朝向，以 {@code ProjectionWriter} 的第一次寫入寫出投影。寫入之前，volume 內有兩個裝了東西的箱子（interior 一個、shell cell 一個）、interior 的石頭、
     * 一隻（不受傷的）豬與一位旁觀玩家；volume 外放兩個偵測器：看著一格 shell 的觀察者（抓形狀更新，即沒有 {@code FORCE_STATE}），以及懸空在另一格 shell
     * 旁邊的紅石線（抓鄰格的方塊更新，即 {@code NOTIFY_NEIGHBORS}：它一收到就會因為沒有支撐而掉落）。寫完：WRITTEN；343 格逐格是 spec 的目標（test 自己依
     * spec 寫出）；Controller 有預設的 block entity（沒有 chamber UUID）；沒有任何掉落物；觀察者沒有排程、沒有輸出；紅石線還在；豬沒有被移動或移除；旁觀
     * 玩家不擋寫入。catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_first_write", tickLimit = 400)
    public void projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates(TestContext context) {
        begin(context, "projection_first_write_overwrites_the_volume_without_drops_or_neighbor_updates");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            var interiorChest = local(frame, 3, 1, 3); var shellChest = local(frame, 0, 4, 3); var interiorStone = local(frame, 2, 2, 2);
            var watched = local(frame, 0, 3, 3); var observer = outside(frame, -1, 3, 3);
            var wired = local(frame, 6, 2, 3); var wire = outside(frame, 7, 2, 3); var support = outside(frame, 7, 1, 3);
            ChamberProtectionService.get().authorizedMutation(() -> {
                for (var chest : List.of(interiorChest, shellChest)) {
                    universe.setBlockState(chest, net.minecraft.block.Blocks.CHEST.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
                    ((net.minecraft.block.entity.ChestBlockEntity) universe.getBlockEntity(chest)).setStack(0, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 5));
                }
                universe.setBlockState(interiorStone, net.minecraft.block.Blocks.STONE.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
                universe.setBlockState(observer, net.minecraft.block.Blocks.OBSERVER.getDefaultState()
                        .with(net.minecraft.block.ObserverBlock.FACING, toward(observer, watched)), net.minecraft.block.Block.NOTIFY_ALL);
                // 懸空的紅石線：先放在支撐上，再以不送更新的寫法拿掉支撐。
                universe.setBlockState(wire, net.minecraft.block.Blocks.AIR.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
                universe.setBlockState(support, net.minecraft.block.Blocks.STONE.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
                universe.setBlockState(wire, net.minecraft.block.Blocks.REDSTONE_WIRE.getDefaultState(), net.minecraft.block.Block.NOTIFY_ALL);
                universe.setBlockState(support, net.minecraft.block.Blocks.AIR.getDefaultState(),
                        net.minecraft.block.Block.NOTIFY_LISTENERS | net.minecraft.block.Block.FORCE_STATE);
            });
            var pig = net.minecraft.entity.EntityType.PIG.create(universe);
            var pigAt = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, 2.5, 3, 4.5);
            pig.refreshPositionAndAngles(pigAt.x, pigAt.y, pigAt.z, 0, 0);
            pig.setInvulnerable(true); pig.setNoGravity(true); pig.setAiDisabled(true);
            universe.spawnEntity(pig);
            hooks.add(pig::discard);
            var spectator = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(spectator::close);
            spectator.player().changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            var spectatorAt = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, 4.5, 2, 2.5);
            spectator.player().teleport(universe, spectatorAt.x, spectatorAt.y, spectatorAt.z, java.util.Set.of(), 0, 0);
            var area = box(frame).expand(3);
            // 新產生的 chunk 的 entity storage 是非同步載入的：等到豬看得見（entity 的區段已在追蹤中），之後的掉落物檢查才有意義；同時等偵測器靜止。
            whenTicks(context, resolved + 4, 200, () -> visible(universe, pig, area)
                    && !universe.getBlockState(observer).get(net.minecraft.block.ObserverBlock.POWERED)
                    && !universe.getBlockTickScheduler().isQueued(observer, net.minecraft.block.Blocks.OBSERVER), settled -> {
                witness.put("fixtureSettled", universe.getBlockState(wire).isOf(net.minecraft.block.Blocks.REDSTONE_WIRE) && universe.getBlockState(support).isAir()
                        && items(universe, area) == 0 && spectator.player().getServerWorld() == universe);
                var pigBefore = pig.getPos();
                var spectatorBefore = spectator.player().getPos();
                var result = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
                witness.put("written", result.status() == ProjectionWriter.Status.WRITTEN && result.written() > 0 && result.blockingPlayers().isEmpty());
                witness.put("everyCellIsTheSpecTarget", M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
                witness.put("controllerHasADefaultBlockEntity", universe.getBlockEntity(frame.controllerPos())
                        instanceof dev.quantumchamber.chamber.ChamberControllerBlockEntity controller && controller.chamberUuid() == null);
                witness.put("chestsDroppedNothing", items(universe, area) == 0);
                witness.put("observerNotScheduled", !universe.getBlockTickScheduler().isQueued(observer, net.minecraft.block.Blocks.OBSERVER));
                witness.put("floatingWireStays", universe.getBlockState(wire).isOf(net.minecraft.block.Blocks.REDSTONE_WIRE));
                witness.put("pigNotMovedOrRemoved", !pig.isRemoved() && pig.getPos().equals(pigBefore) && visible(universe, pig, area));
                witness.put("spectatorNotMoved", spectator.player().getPos().equals(spectatorBefore));
                LOGGER.info("M5_PROJECTION_FIRST_WRITE settled={} status={} written={} mismatched={}", settled - resolved, result.status(), result.written(),
                        result.mismatched().size());
                at(context, settled + 4, hooks, () -> {
                    witness.put("observerNeverPulsed", !universe.getBlockState(observer).get(net.minecraft.block.ObserverBlock.POWERED)
                            && !universe.getBlockTickScheduler().isQueued(observer, net.minecraft.block.Blocks.OBSERVER));
                    witness.put("wireStillThere", universe.getBlockState(wire).isOf(net.minecraft.block.Blocks.REDSTONE_WIRE));
                    witness.put("nothingDroppedLater", items(universe, area) == 0);
                    witness.put("pigStillThere", !pig.isRemoved() && visible(universe, pig, area));
                    hooks.release();
                    context.assertTrue(!witness.containsValue(false), "投影的第一次寫入：" + witness);
                    awaitConvergedThenComplete(context, 1, 20);
                });
            }, () -> "entity storage 沒有載入或偵測器沒有靜止：pigVisible=" + visible(universe, pig, area), hooks);
        });
    }

    /** {@code entity} 在 {@code area} 內、而且以原版的 entity 查詢看得到（它所在的區段已在追蹤中）。 */
    private static boolean visible(net.minecraft.server.world.ServerWorld world, net.minecraft.entity.Entity entity, net.minecraft.util.math.Box area) {
        return world.getEntitiesByClass(entity.getClass(), area, candidate -> candidate == entity).size() == 1;
    }

    /**
     * F4b-1（spec §9.2 的既有 entity 與兩種寫法；plan Step 1 第 2 組的「修復只動不變量 cell、保留 interior」與第 6 組的玩家）：在宇宙 U 的投影位置，
     * <ol>
     * <li>第一次寫入時，一位（創造模式、不受傷的）玩家 Q 站在 volume 內——他所在的兩格事先已清成 AIR，本來就不需要寫——仍然擋住寫入：
     *     BLOCKED_BY_PLAYER（帶 Q）、343 格一格都沒有改變（第一次寫入的檢查範圍是整個 7×7×7）；Q 移到 volume 外、Controller 的位置先放一座方塊狀態與目標
     *     相同但帶 chamber UUID 的 Controller 之後 WRITTEN：那座 Controller 仍被重寫，block entity 換成預設值（沒有 chamber UUID，不沿用既有身分）；
     *     Controller 的位置由 ABSENT 變成 PRESENT（§6.5 實體交叉檢查用的查詢）。</li>
     * <li>修復：兩格 shell 損壞（AIR、石頭）、25 格門關閉、interior 放一塊玻璃，Q 站在 interior → WRITTEN、恰好改了 27 格；不變量 cell 全部回到目標（門回到
     *     開啟），interior 的玻璃保留，Q 沒有被移動（interior 的玩家不擋修復）。</li>
     * <li>修復被擋：Q 站在一格要寫的損壞 shell 裡 → BLOCKED_BY_PLAYER，兩格損壞都沒有寫；Q 改站在已開啟的門口（門已是目標，不需要寫）→ WRITTEN（修復只看
     *     這次要寫的格）。</li>
     * <li>驗證失敗：同 X／Z、頂端超出世界高度的 frame → VERIFY_FAILED，不符的格都在世界高度之外，帶 failure。</li>
     * <li>未載入：很遠的 frame → NOT_LOADED，沒有載入那個 chunk；Controller 位置的查詢也是 NOT_LOADED。</li>
     * </ol>
     * catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_repair", tickLimit = 400)
    public void projection_writer_waits_for_players_and_repairs_only_the_invariant_cells(TestContext context) {
        begin(context, "projection_writer_waits_for_players_and_repairs_only_the_invariant_cells");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            var witness = new java.util.LinkedHashMap<String, Boolean>();
            var connection = new ConnectedGameTestPlayer(context.getWorld());
            hooks.add(connection::close);
            var q = connection.player();
            q.changeGameMode(net.minecraft.world.GameMode.CREATIVE); q.setNoGravity(true);
            var air = net.minecraft.block.Blocks.AIR.getDefaultState();
            // 1. 第一次寫入：Q 在 volume 內、站在已是目標（AIR）的兩格。
            universe.setBlockState(local(frame, 3, 1, 3), air); universe.setBlockState(local(frame, 3, 2, 3), air);
            standAt(q, universe, frame, 3.5, 1, 3.5);
            witness.put("controllerAbsentBefore", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.ABSENT);
            var before = cells(universe, frame);
            var blocked = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("firstWriteBlockedByQ", blocked.status() == ProjectionWriter.Status.BLOCKED_BY_PLAYER && blocked.blockingPlayers().equals(List.of(q.getUuid()))
                    && blocked.written() == 0);
            witness.put("nothingWrittenWhileBlocked", before.equals(cells(universe, frame)));
            standAt(q, universe, frame, 3.5, 1, -4.5);
            // Controller 的位置上已有一座方塊狀態與目標相同、帶 chamber UUID 的 Controller：第一次寫入仍要重寫它，換成預設的 block entity。
            var stale = dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                    .with(dev.quantumchamber.chamber.ChamberControllerBlock.FACING, frame.outwardFacing());
            universe.setBlockState(frame.controllerPos(), stale);
            var staleController = java.util.Objects.requireNonNull(M5GameTestSupport.controller(universe, frame), "既有 Controller 的 block entity");
            staleController.setChamberUuid(UUID.randomUUID());
            witness.put("staleControllerHasAnIdentity", staleController.chamberUuid() != null
                    && universe.getBlockState(frame.controllerPos()).equals(M5GameTestSupport.projectionTarget(frame, 3, 6, 0)));
            var first = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("firstWriteAfterQLeft", first.status() == ProjectionWriter.Status.WRITTEN && M5GameTestSupport.projectionMismatches(universe, frame, false).isEmpty());
            var rewritten = M5GameTestSupport.controller(universe, frame);
            witness.put("existingControllerRewrittenWithADefaultBlockEntity", rewritten != null && rewritten != staleController && staleController.isRemoved()
                    && rewritten.chamberUuid() == null);
            witness.put("controllerPresentAfter", ProjectionWriter.controllerPresence(universe, frame) == ProjectionWriter.ControllerPresence.PRESENT);
            // 2. 修復：損壞兩格 shell、關門、interior 放玻璃；Q 在 interior。
            var holed = local(frame, 0, 3, 3); var stoned = local(frame, 6, 4, 2); var glass = local(frame, 2, 2, 2);
            damage(universe, frame, holed, stoned, true);
            ChamberProtectionService.get().authorizedMutation(() -> universe.setBlockState(glass, net.minecraft.block.Blocks.GLASS.getDefaultState()));
            standAt(q, universe, frame, 3.5, 1, 3.5);
            var qInside = q.getPos();
            var repaired = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("repairWritesExactlyTheDamagedInvariantCells", repaired.status() == ProjectionWriter.Status.WRITTEN && repaired.written() == 27);
            witness.put("invariantCellsRestoredDoorOpen", M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty()
                    && M5GameTestSupport.doorAll(universe, frame, true, MembraneState.NONE));
            witness.put("interiorKept", universe.getBlockState(glass).isOf(net.minecraft.block.Blocks.GLASS));
            witness.put("interiorPlayerNotMoved", q.getPos().equals(qInside));
            // 3. 修復被擋（Q 在要寫的損壞 shell 裡），之後 Q 站在已開啟的門口也不擋。
            damage(universe, frame, holed, stoned, false);
            standAt(q, universe, frame, 0.5, 3, 3.5);
            var blockedRepair = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("repairBlockedByAPlayerInADamagedCell", blockedRepair.status() == ProjectionWriter.Status.BLOCKED_BY_PLAYER
                    && universe.getBlockState(holed).isAir() && universe.getBlockState(stoned).isOf(net.minecraft.block.Blocks.STONE));
            standAt(q, universe, frame, 3.5, 1, 0.5);
            var doorway = ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.REPAIR);
            witness.put("doorwayPlayerDoesNotBlockTheRepair", doorway.status() == ProjectionWriter.Status.WRITTEN && doorway.written() == 2
                    && M5GameTestSupport.projectionMismatches(universe, frame, true).isEmpty());
            // 4. 驗證失敗：頂端超出世界高度。
            var high = new dev.quantumchamber.chamber.ChamberFrame(frame.controllerPos().withY(universe.getTopY() + 2), frame.outwardFacing());
            var overflow = ProjectionWriter.write(universe, high, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("verifyFailedAboveTheBuildLimit", overflow.status() == ProjectionWriter.Status.VERIFY_FAILED && overflow.failure().isPresent()
                    && !overflow.mismatched().isEmpty() && overflow.mismatched().stream().allMatch(pos -> pos.getY() >= universe.getTopY()));
            // 5. 未載入：很遠的 frame。
            var far = new dev.quantumchamber.chamber.ChamberFrame(frame.controllerPos().add(100_000, 0, 0), frame.outwardFacing());
            var unloaded = ProjectionWriter.write(universe, far, ProjectionWriter.Mode.FIRST_WRITE);
            witness.put("notLoadedWithoutLoading", unloaded.status() == ProjectionWriter.Status.NOT_LOADED
                    && ProjectionWriter.controllerPresence(universe, far) == ProjectionWriter.ControllerPresence.NOT_LOADED
                    && universe.getChunkManager().getWorldChunk(far.controllerPos().getX() >> 4, far.controllerPos().getZ() >> 4) == null);
            LOGGER.info("M5_PROJECTION_REPAIR blocked={} first={} repaired={}/{} blockedRepair={} doorway={}/{} overflow={}/{} unloaded={}", blocked.status(),
                    first.status(), repaired.status(), repaired.written(), blockedRepair.status(), doorway.status(), doorway.written(), overflow.status(),
                    overflow.mismatched().size(), unloaded.status());
            hooks.release();
            context.assertTrue(!witness.containsValue(false), "投影的寫入與修復：" + witness);
            awaitConvergedThenComplete(context, 1, 20);
        });
    }

    /**
     * F4b-1（spec §9.3 的保護；plan Step 1 第 8 組的「保護（破壞、放置、爆炸、活塞、流體）」；補充第 4 點）：宇宙 U 的投影依 F4b-2 的順序經過權威與投影保護索引——
     * checked 寫入 PENDING 並登記（這時還不受保護）、第一次寫入、checked 寫入 COMPLETE 並登記（受保護）。之後：
     * <ul>
     * <li>{@code ChamberProtectionService.mayMutate}：218 個不變量 cell 一律拒絕，125 格 interior 與 volume 外放行。</li>
     * <li>投影艙的門照既有的門邏輯開關：玩家用門板把整面門關上、再打開（既有的授權寫入）。</li>
     * <li>破壞：創造模式的玩家（直接走原版的 {@code tryBreakBlock}）打 shell、Controller 與門板都不會消失；interior 裡的石頭照常打掉。</li>
     * <li>放置：一格 shell 被授權寫入損壞成 AIR（模擬待修復的投影）之後，玩家對它放置石頭被拒、仍是 AIR；interior 照常放得下。</li>
     * <li>爆炸：一格 shell 換成玻璃（授權寫入）、相鄰的 interior 也放玻璃，在 interior 的玻璃上爆炸：interior 的玻璃消失，shell 的玻璃留著。</li>
     * <li>活塞：volume 外三台通電的活塞分別對著 shell、門板與 Controller：都推不動（不變量 cell 都是推不動的方塊，這條由既有的方塊設定擋下，
     *     關掉索引的 RED 也推不動；寫入也經過同一個保護）；對照組的活塞照常推動石頭，證明這裡的觀察看得到推動。</li>
     * <li>流體：一格 shell 損壞成 AIR，外側相鄰放水源，水源南北兩側用石頭擋住，只能往西（損壞的 shell）與往東（對照組的空格）流，兩邊離落差一樣近：
     *     水流不進那一格；對照組的空格照常有水。</li>
     * </ul>
     * 新 chunk 的鄰居是非同步產生的（GameTest server 不以 20 TPS 節流），活塞的方塊事件與流體的排程刻要等 chunk 開始 block ticking 才會執行，
     * 所以先等投影周圍強制載入的 chunk 都 block ticking 再佈置；活塞與流體的判定也等到對照組的活塞推動完成、水源流動過之後（有界等待），
     * 不用固定的 tick 數。catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_protection", tickLimit = 400)
    public void projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids(TestContext context) {
        begin(context, "projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = origin.frame;
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var chunks = M5GameTestSupport.forceProjectionChunks(universe, frame, hooks);
            whenTicks(context, resolved, 200, () -> M5GameTestSupport.blockTicking(universe, chunks),
                    ticking -> projectionProtection(context, hooks, universe, frame, chamber, id, ticking),
                    () -> "投影周圍強制載入的 chunk 沒有開始 block ticking：" + chunks, hooks);
        });
    }

    /** {@link #projection_invariant_cells_resist_breaking_placing_explosions_pistons_and_fluids} 在投影周圍的 chunk 開始 block ticking（{@code ticking}）之後的本體。 */
    private static void projectionProtection(TestContext context, M2CorridorGameTests.TestHooks hooks, net.minecraft.server.world.ServerWorld universe,
            dev.quantumchamber.chamber.ChamberFrame frame, UUID chamber, UniverseId id, int ticking) {
        var server = context.getWorld().getServer();
        var witness = new java.util.LinkedHashMap<String, Boolean>();
        var protection = ChamberProtectionService.get();
        var authorities = CollapseAuthorities.get(server);
        var index = ProjectionProtectionIndex.get(server);
        var shell = local(frame, 0, 3, 3);
        // F4b-2 的順序：PENDING 確認並登記 → 寫方塊 → COMPLETE 確認並登記。
        var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, frame.controllerPos(),
                frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
        witness.put("pendingConfirmed", pending.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(pending.entry().orElseThrow());
        witness.put("pendingNotProtected", protection.mayMutate(universe, shell));
        witness.put("firstWrite", ProjectionWriter.write(universe, frame, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
        var complete = authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
        witness.put("completeConfirmed", complete.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(complete.entry().orElseThrow());
        int denied = 0, allowed = 0;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            boolean may = protection.mayMutate(universe, local(frame, x, y, z));
            if (ChamberGeometry.isShellCell(x, y, z) && !may) denied++;
            if (!ChamberGeometry.isShellCell(x, y, z) && may) allowed++;
        }
        int deniedCells = denied, allowedCells = allowed;
        witness.put("everyInvariantCellDenied", deniedCells == 218);
        witness.put("interiorAllowed", allowedCells == 125);
        witness.put("outsideAllowed", protection.mayMutate(universe, outside(frame, 3, 2, -2)) && protection.mayMutate(universe, outside(frame, 3, 7, 3)));
        var connection = new ConnectedGameTestPlayer(context.getWorld());
        hooks.add(connection::close);
        var b = connection.player();
        b.changeGameMode(net.minecraft.world.GameMode.CREATIVE); b.setNoGravity(true);
        standAt(b, universe, frame, 3.5, 1, 3.5);
        // 門：既有的門邏輯（門板的 onUse → 整面門授權切換）。
        var doorCell = local(frame, 3, 3, 0);
        witness.put("doorClosesByTheExistingLogic", interact(universe, doorCell, b).isAccepted() && doorAll(universe, frame, false, MembraneState.NONE));
        witness.put("doorOpensByTheExistingLogic", interact(universe, doorCell, b).isAccepted() && doorAll(universe, frame, true, MembraneState.NONE));
        // 破壞。
        for (var target : List.of(shell, frame.controllerPos(), doorCell)) b.interactionManager.tryBreakBlock(target);
        witness.put("breakingRefused", universe.getBlockState(shell).isOf(net.minecraft.block.Blocks.BEDROCK)
                && universe.getBlockState(frame.controllerPos()).isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER)
                && M5GameTestSupport.doorIs(universe, doorCell, true, MembraneState.NONE));
        var interiorStone = local(frame, 2, 1, 2);
        universe.setBlockState(interiorStone, net.minecraft.block.Blocks.STONE.getDefaultState());
        b.interactionManager.tryBreakBlock(interiorStone);
        witness.put("interiorBreaks", universe.getBlockState(interiorStone).isAir());
        // 放置：損壞成 AIR 的不變量 cell 不能放；interior 可以。
        protection.authorizedMutation(() -> universe.setBlockState(shell, net.minecraft.block.Blocks.AIR.getDefaultState()));
        b.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.STONE, 64));
        placeOnTop(b, universe, local(frame, 0, 2, 3));
        witness.put("placingIntoAnInvariantCellRefused", universe.getBlockState(shell).isAir());
        placeOnTop(b, universe, local(frame, 2, 0, 3));
        witness.put("interiorPlacementWorks", universe.getBlockState(local(frame, 2, 1, 3)).isOf(net.minecraft.block.Blocks.STONE));
        // 爆炸：shell 的玻璃留著，interior 的玻璃消失。
        var shellGlass = local(frame, 6, 3, 3); var interiorGlass = local(frame, 5, 3, 3);
        protection.authorizedMutation(() -> {
            universe.setBlockState(shellGlass, net.minecraft.block.Blocks.GLASS.getDefaultState());
            universe.setBlockState(interiorGlass, net.minecraft.block.Blocks.GLASS.getDefaultState());
        });
        var blast = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, 5.5, 3.5, 3.5);
        universe.createExplosion(null, blast.x, blast.y, blast.z, 2, net.minecraft.world.World.ExplosionSourceType.BLOCK);
        witness.put("explosionKeepsTheInvariantCell", universe.getBlockState(shellGlass).isOf(net.minecraft.block.Blocks.GLASS));
        witness.put("explosionClearsTheInterior", universe.getBlockState(interiorGlass).isAir());
        // 活塞：對著 shell、門板與 Controller 的活塞推不動；對照組照常推動石頭。
        var pistons = new java.util.ArrayList<net.minecraft.util.math.BlockPos>();
        pistons.add(piston(universe, outside(frame, 7, 2, 4), local(frame, 6, 2, 4), outside(frame, 8, 2, 4)));
        pistons.add(piston(universe, outside(frame, 3, 2, -1), local(frame, 3, 2, 0), outside(frame, 3, 2, -2)));
        pistons.add(piston(universe, outside(frame, 3, 7, 0), frame.controllerPos(), outside(frame, 3, 8, 0)));
        var controlStone = outside(frame, 10, 2, 3); var controlTarget = outside(frame, 9, 2, 3);
        universe.setBlockState(controlTarget, net.minecraft.block.Blocks.AIR.getDefaultState());
        universe.setBlockState(controlStone, net.minecraft.block.Blocks.STONE.getDefaultState());
        var controlPiston = piston(universe, outside(frame, 11, 2, 3), controlStone, outside(frame, 12, 2, 3));
        // 流體：損壞成 AIR 的不變量 cell 不進水；對照組的外側空格有水。水源南北兩側擋住，只剩往西（dry）與往東（wet）兩個方向，
        // 兩邊下一格都有落差（dry 那側是爆炸清掉的 interior 玻璃，wet 那側是外面的空格），流向一樣優先。
        var dry = local(frame, 6, 4, 3); var source = outside(frame, 7, 4, 3); var wet = outside(frame, 8, 4, 3);
        protection.authorizedMutation(() -> universe.setBlockState(dry, net.minecraft.block.Blocks.AIR.getDefaultState()));
        for (var solid : List.of(outside(frame, 7, 3, 3), outside(frame, 8, 3, 3), outside(frame, 7, 4, 2), outside(frame, 7, 4, 4))) {
            universe.setBlockState(solid, net.minecraft.block.Blocks.STONE.getDefaultState());
        }
        universe.setBlockState(wet, net.minecraft.block.Blocks.AIR.getDefaultState());
        universe.setBlockState(source, net.minecraft.block.Blocks.WATER.getDefaultState());
        // 等對照組的活塞真的推動（方塊事件與 block entity 都跑過）、水源也流動過（排程刻跑過；同一次流動同時往 dry 與 wet）再判定。
        java.util.function.BooleanSupplier controlPistonPushed = () -> universe.getBlockState(controlPiston).get(net.minecraft.block.PistonBlock.EXTENDED)
                && universe.getBlockState(controlTarget).isOf(net.minecraft.block.Blocks.STONE);
        java.util.function.BooleanSupplier controlFluidFlowed = () -> !universe.getFluidState(wet).isEmpty();
        java.util.function.BooleanSupplier waterMoved = () -> controlFluidFlowed.getAsBoolean() || !universe.getFluidState(dry).isEmpty();
        whenTicks(context, ticking + 1, 200, () -> controlPistonPushed.getAsBoolean() && waterMoved.getAsBoolean(), settled -> {
            witness.put("pistonsCannotMoveInvariantCells", universe.getBlockState(local(frame, 6, 2, 4)).isOf(net.minecraft.block.Blocks.BEDROCK)
                    && M5GameTestSupport.doorIs(universe, local(frame, 3, 2, 0), true, MembraneState.NONE)
                    && universe.getBlockState(frame.controllerPos()).isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER)
                    && pistons.stream().noneMatch(pos -> universe.getBlockState(pos).get(net.minecraft.block.PistonBlock.EXTENDED)));
            witness.put("controlPistonPushes", controlPistonPushed.getAsBoolean());
            witness.put("fluidCannotEnterAnInvariantCell", universe.getBlockState(dry).isAir() && universe.getFluidState(dry).isEmpty());
            witness.put("controlFluidFlows", controlFluidFlowed.getAsBoolean());
            LOGGER.info("M5_PROJECTION_PROTECTION controlsSettledAfter={} denied={} allowed={}", settled - ticking, deniedCells, allowedCells);
            hooks.release();
            context.assertTrue(!witness.containsValue(false), "投影的保護：" + witness + " denied=" + deniedCells + " allowed=" + allowedCells);
            awaitConvergedThenComplete(context, 1, 20);
        }, () -> "對照組沒有發生：controlPistonPushed=" + controlPistonPushed.getAsBoolean() + " waterMoved=" + waterMoved.getAsBoolean()
                + " piston=" + universe.getBlockState(controlPiston) + " target=" + universe.getBlockState(controlTarget) + " source=" + universe.getFluidState(source)
                + " wet=" + universe.getBlockState(wet) + " blockTicking=" + universe.shouldTickBlockPos(controlPiston) + "/" + universe.shouldTickBlockPos(wet)
                + " tickingFuture=" + universe.getChunkManager().isTickingFutureReady(net.minecraft.util.math.ChunkPos.toLong(controlPiston))
                + "/" + universe.getChunkManager().isTickingFutureReady(net.minecraft.util.math.ChunkPos.toLong(wet)) + " time=" + universe.getTime()
                + " witness=" + witness, hooks);
    }

    /**
     * F4b-1（spec §9.3 的載入辨識；plan Step 1 第 8 組；補充第 6 點）：動態世界的 Controller 依投影保護索引判定，不看 block entity 的 instanceKind。
     * 宇宙 U 裡三座 Controller：
     * <ul>
     * <li>A（原艙的 frame）：PENDING 確認並登記後由寫入器第一次寫入，接著丟掉 U 的 load-sync queue（{@code discardWorld}，收據
     *     DISCARDED_WORLD_UNLOAD），只剩 {@code onBlockAdded} 排的 refresh：寫入後 4 tick 還沒有 refresh 過（{@code powerInitialized=false}、ORIGIN），
     *     之後由排程的 refresh 判定為 PROJECTION。</li>
     * <li>B（沿區域 x 軸外移 32 格的 frame、另一個 chamber UUID）：PENDING 確認並登記後下一 tick 才寫入；load-sync 收據 CONSUMED，寫入後 3 tick 已判定為
     *     PROJECTION（早於它自己 20 tick 後的排程 refresh）。chunk 載入時的 {@code BLOCK_ENTITY_LOAD} 走同一個 queue。</li>
     * <li>C（外移 64 格，不在索引裡）：直接放一座 Controller → 照舊是 foreign：ORIGIN、INVALID、比較器 0。</li>
     * </ul>
     * A、B 是 INVALID、比較器 0、沒有 chamber UUID；兩座轉為 COMPLETE 並登記之後，在 Controller 上方放紅石塊供電，45 tick 後已讀到供電（{@code wasPowered}），
     * 仍是 inert 的 PROJECTION；chamber registry 沒有 U 的紀錄、journal 沒有來源在 U 的 session。整段期間 quantumchamber logger 除了塌縮 session 本身的
     * log 之外沒有 WARN／ERROR，也沒有任何 log 提到這三座 Controller 的位置（排程的 refresh 每 20 tick 一次，不會反覆記 log）。catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_projection_recognition", tickLimit = 600)
    public void projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added(TestContext context) {
        begin(context, "projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added");
        var hooks = new M2CorridorGameTests.TestHooks();
        DormantOrigin origin;
        try {
            origin = DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var a = origin.frame;
        var b = new dev.quantumchamber.chamber.ChamberFrame(outside(a, 35, 6, 0), a.outwardFacing());
        var c = new dev.quantumchamber.chamber.ChamberFrame(outside(a, 67, 6, 0), a.outwardFacing());
        resolvedUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var chunks = new java.util.ArrayList<net.minecraft.util.math.ChunkPos>();
            for (var frame : List.of(a, b, c)) chunks.addAll(M5GameTestSupport.forceProjectionChunks(universe, frame, hooks));
            whenTicks(context, resolved, 200, () -> M5GameTestSupport.blockTicking(universe, chunks),
                    ticking -> projectionRecognition(context, hooks, origin.capture, universe, sid, chamber, id, a, b, c, ticking),
                    () -> "投影周圍強制載入的 chunk 沒有開始 block ticking：" + chunks, hooks);
        });
    }

    /** {@link #projection_controllers_are_recognized_by_the_index_on_load_sync_and_block_added} 在 chunk 開始 block ticking（{@code ticking}）之後的本體。 */
    private static void projectionRecognition(TestContext context, M2CorridorGameTests.TestHooks hooks, LogCapture capture,
            net.minecraft.server.world.ServerWorld universe, UUID sid, UUID chamber, UniverseId id, dev.quantumchamber.chamber.ChamberFrame a,
            dev.quantumchamber.chamber.ChamberFrame b, dev.quantumchamber.chamber.ChamberFrame c, int ticking) {
        var server = context.getWorld().getServer();
        var authorities = CollapseAuthorities.get(server);
        var index = ProjectionProtectionIndex.get(server);
        var witness = new java.util.LinkedHashMap<String, Boolean>();
        int mark = capture.entries().size();
        var pendingA = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, a.controllerPos(), a.outwardFacing(),
                ProjectionLedger.Entry.Status.PENDING));
        var pendingB = authorities.writeProjection(new ProjectionLedger.Entry(UUID.randomUUID(), id, DimensionRole.OVERWORLD, b.controllerPos(),
                b.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
        witness.put("pendingConfirmed", pendingA.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                && pendingB.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
        index.confirmed(pendingA.entry().orElseThrow());
        index.confirmed(pendingB.entry().orElseThrow());
        // A：寫入後丟掉 U 的 load-sync queue，只剩 onBlockAdded 排的 refresh。
        witness.put("aWritten", ProjectionWriter.write(universe, a, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
        var controllerA = java.util.Objects.requireNonNull(M5GameTestSupport.controller(universe, a), "A 的 Controller block entity");
        var receiptsA = dev.quantumchamber.chamber.ChamberLoadSyncTestAccess.observe(universe, controllerA);
        hooks.add(receiptsA::close);
        dev.quantumchamber.chamber.ChamberLoadSyncTestAccess.discardWorld(universe);
        // C：不在索引裡的 Controller。
        universe.setBlockState(c.controllerPos(), dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                .with(dev.quantumchamber.chamber.ChamberControllerBlock.FACING, c.outwardFacing()));
        var controllerC = java.util.Objects.requireNonNull(M5GameTestSupport.controller(universe, c), "C 的 Controller block entity");
        var receiptsC = dev.quantumchamber.chamber.ChamberLoadSyncTestAccess.observe(universe, controllerC);
        hooks.add(receiptsC::close);
        var consumed = dev.quantumchamber.chamber.ChamberLoadSyncOutcome.CONSUMED;
        var discarded = List.of(dev.quantumchamber.chamber.ChamberLoadSyncOutcome.DISCARDED_WORLD_UNLOAD);
        at(context, ticking + 1, hooks, () -> {
            // B：PENDING 已登記一 tick 之後才寫入。
            witness.put("bWritten", ProjectionWriter.write(universe, b, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN);
            var controllerB = java.util.Objects.requireNonNull(M5GameTestSupport.controller(universe, b), "B 的 Controller block entity");
            var receiptsB = dev.quantumchamber.chamber.ChamberLoadSyncTestAccess.observe(universe, controllerB);
            hooks.add(receiptsB::close);
            at(context, ticking + 4, hooks, () -> {
                witness.put("aNotRefreshedYet", !controllerA.powerInitialized() && controllerA.instanceKind() == dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN
                        && receiptsA.outcomes().equals(discarded));
                witness.put("bRecognizedByLoadSync", receiptsB.outcomes().contains(consumed) && inertProjection(universe, controllerB));
                witness.put("cForeignAfterLoadSync", receiptsC.outcomes().contains(consumed) && foreign(universe, controllerC));
                at(context, ticking + 25, hooks, () -> {
                    witness.put("aRecognizedByBlockAdded", receiptsA.outcomes().equals(discarded) && inertProjection(universe, controllerA));
                    var completeA = authorities.writeProjection(pendingA.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
                    var completeB = authorities.writeProjection(pendingB.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
                    witness.put("completeConfirmed", completeA.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                            && completeB.status() == CollapseAuthorities.WriteResult.Status.WRITTEN);
                    index.confirmed(completeA.entry().orElseThrow());
                    index.confirmed(completeB.entry().orElseThrow());
                    // 供電：Controller 上方（volume 外）放紅石塊；B 的 neighbor update 立即 refresh，A 由排程的 refresh 讀到。
                    for (var frame : List.of(a, b)) universe.setBlockState(outside(frame, 3, 7, 0), net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState());
                    at(context, ticking + 70, hooks, () -> {
                        witness.put("poweredButInert", controllerA.wasPowered() && controllerB.wasPowered()
                                && inertProjection(universe, controllerA) && inertProjection(universe, controllerB));
                        witness.put("cStillForeign", foreign(universe, controllerC));
                        var key = universe.getRegistryKey().getValue();
                        witness.put("noRegistryRecordInU", dev.quantumchamber.chamber.ChamberRegistryState.get(server).registry().records().values().stream()
                                .noneMatch(record -> record.originWorldKey().equals(key)));
                        witness.put("noSessionFromU", SessionRecoveryState.get(server).records().values().stream()
                                .noneMatch(record -> record.origin().worldKey().equals(key)));
                        var entries = capture.entries();
                        var window = entries.subList(mark, entries.size());
                        var positions = List.of(a.controllerPos(), b.controllerPos(), c.controllerPos());
                        witness.put("noWarnOrErrorOutsideTheSession", window.stream().noneMatch(entry -> (entry.level() == org.apache.logging.log4j.Level.WARN
                                || entry.level() == org.apache.logging.log4j.Level.ERROR) && !entry.message().contains(sid.toString())));
                        witness.put("noLogAboutTheControllers", window.stream().noneMatch(entry -> positions.stream()
                                .anyMatch(pos -> entry.message().contains(pos.toShortString()) || entry.message().contains(pos.toString()))));
                        LOGGER.info("M5_PROJECTION_RECOGNITION a={}/{} b={}/{} c={}/{} receiptsA={} receiptsB={} receiptsC={}", controllerA.instanceKind(),
                                controllerA.chamberState(), controllerB.instanceKind(), controllerB.chamberState(), controllerC.instanceKind(),
                                controllerC.chamberState(), receiptsA.outcomes(), receiptsB.outcomes(), receiptsC.outcomes());
                        hooks.release();
                        context.assertTrue(!witness.containsValue(false), "投影 Controller 的載入辨識：" + witness);
                        awaitConvergedThenComplete(context, 1, 20);
                    });
                });
            });
        });
    }

    /** inert 的 PROJECTION：仍在世界上、PROJECTION、INVALID、比較器 0、沒有 chamber UUID。 */
    private static boolean inertProjection(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberControllerBlockEntity controller) {
        return inWorld(world, controller) && controller.instanceKind() == dev.quantumchamber.chamber.ChamberInstanceKind.PROJECTION && inert(world, controller);
    }

    /** 未知的 foreign Controller：仍在世界上、refresh 過、ORIGIN（預設值）、INVALID、比較器 0、沒有 chamber UUID。 */
    private static boolean foreign(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberControllerBlockEntity controller) {
        return inWorld(world, controller) && controller.powerInitialized()
                && controller.instanceKind() == dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN && inert(world, controller);
    }

    private static boolean inWorld(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberControllerBlockEntity controller) {
        return !controller.isRemoved() && world.getBlockEntity(controller.getPos()) == controller;
    }

    private static boolean inert(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberControllerBlockEntity controller) {
        return controller.chamberState() == dev.quantumchamber.chamber.ChamberState.INVALID && controller.chamberUuid() == null
                && world.getBlockState(controller.getPos()).getComparatorOutput(world, controller.getPos()) == 0;
    }

    /** 玩家手上的方塊放在 {@code below} 的上方（原版 {@code interactBlock}，打在頂面）。 */
    private static void placeOnTop(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.BlockPos below) {
        var hit = new net.minecraft.util.hit.BlockHitResult(net.minecraft.util.math.Vec3d.ofCenter(below).add(0, 0.5, 0), net.minecraft.util.math.Direction.UP, below, false);
        player.interactionManager.interactBlock(player, world, player.getMainHandStack(), net.minecraft.util.Hand.MAIN_HAND, hit);
    }

    /** 在 {@code at} 放一台朝向相鄰 {@code facing} 的活塞，再在 {@code power} 放紅石塊供電；回傳活塞的位置。 */
    private static net.minecraft.util.math.BlockPos piston(net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.BlockPos at,
            net.minecraft.util.math.BlockPos facing, net.minecraft.util.math.BlockPos power) {
        world.setBlockState(at, net.minecraft.block.Blocks.PISTON.getDefaultState().with(net.minecraft.block.PistonBlock.FACING, toward(at, facing)));
        world.setBlockState(power, net.minecraft.block.Blocks.REDSTONE_BLOCK.getDefaultState());
        return at;
    }

    /** F4b-1 的 fixture 交出的宇宙：RESOLVE 完成的 server tick、宇宙 U 的 world、塌縮的 session、原艙的 chamber UUID 與 U。 */
    @FunctionalInterface private interface ResolvedUniverse {
        void ready(int tick, net.minecraft.server.world.ServerWorld universe, UUID sid, UUID chamber, UniverseId id);
    }

    /**
     * F4b-1 的 fixture：原艙上的 DORMANT NEW 收據，P 由艙外進入 → 提交 COLLAPSING → RESOLVE 建立並物化宇宙 U（之後停在 PREPARE stub 的 WAITING）。
     * RESOLVE 完成之後以那個 tick、U 的 world、session 與 U 呼叫 {@code ready}。收尾時 DormantOrigin 以終局 API 移除收據（宇宙保留，catalog 增量 1）。
     */
    private static void resolvedUniverse(TestContext context, DormantOrigin origin, M2CorridorGameTests.TestHooks hooks, ResolvedUniverse ready) {
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            at(context, registered + 1, hooks, () -> moveInside(p, origin.frame));
            whenTicks(context, registered + 2, 20, () -> UniverseLifecycleService.resolveActive(server, id).isPresent()
                    && origin.capture.matching(org.apache.logging.log4j.Level.INFO, "目的地已確定", sid.toString()).size() == 1,
                    resolved -> ready.ready(resolved, UniverseLifecycleService.resolveActive(server, id).orElseThrow(), sid, chamber, id),
                    () -> "RESOLVE 沒有建立宇宙：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /** 投影在區域座標（0..6）的格。 */
    private static net.minecraft.util.math.BlockPos local(dev.quantumchamber.chamber.ChamberFrame frame, int x, int y, int z) {
        return ChamberGeometry.localToWorld(frame, x, y, z);
    }

    /** 投影區域座標系的任意格（可以在 7×7×7 之外）。 */
    private static net.minecraft.util.math.BlockPos outside(dev.quantumchamber.chamber.ChamberFrame frame, int x, int y, int z) {
        return dev.quantumchamber.chamber.ChamberSpaceCoordinates.block(frame, x, y, z);
    }

    /** 由 {@code from} 指向相鄰的 {@code to} 的方向。 */
    private static net.minecraft.util.math.Direction toward(net.minecraft.util.math.BlockPos from, net.minecraft.util.math.BlockPos to) {
        return java.util.Objects.requireNonNull(net.minecraft.util.math.Direction.fromVector(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ()),
                "不相鄰");
    }

    /** 投影的 7×7×7 外框（整格）。 */
    private static net.minecraft.util.math.Box box(dev.quantumchamber.chamber.ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        return new net.minecraft.util.math.Box(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1);
    }

    /** {@code area} 內的掉落物數。 */
    private static int items(net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.Box area) {
        return world.getEntitiesByClass(net.minecraft.entity.ItemEntity.class, area, entity -> true).size();
    }

    /** 投影 7×7×7 的 343 格目前的方塊狀態（依區域座標順序）。 */
    private static List<net.minecraft.block.BlockState> cells(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberFrame frame) {
        var result = new java.util.ArrayList<net.minecraft.block.BlockState>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) result.add(world.getBlockState(local(frame, x, y, z)));
        return result;
    }

    /** 把玩家移到投影區域座標 (x, y, z) 的位置（同一個世界或跨世界）。 */
    private static void standAt(net.minecraft.server.network.ServerPlayerEntity player, net.minecraft.server.world.ServerWorld world,
            dev.quantumchamber.chamber.ChamberFrame frame, double x, double y, double z) {
        var at = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, x, y, z);
        player.teleport(world, at.x, at.y, at.z, java.util.Set.of(), 0, 0);
        player.setVelocity(net.minecraft.util.math.Vec3d.ZERO);
    }

    /** 以授權寫入損壞兩格不變量 cell（一格 AIR、一格石頭）；{@code closeDoor} 時另把 25 格門關上。 */
    private static void damage(net.minecraft.server.world.ServerWorld world, dev.quantumchamber.chamber.ChamberFrame frame, net.minecraft.util.math.BlockPos holed,
            net.minecraft.util.math.BlockPos stoned, boolean closeDoor) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            world.setBlockState(holed, net.minecraft.block.Blocks.AIR.getDefaultState());
            world.setBlockState(stoned, net.minecraft.block.Blocks.STONE.getDefaultState());
            if (closeDoor) for (var cell : M5GameTestSupport.door(frame)) {
                world.setBlockState(cell, dev.quantumchamber.chamber.QuantumBulkheadBlock.withOpen(world.getBlockState(cell), false));
            }
        });
    }

    /** 這個 session 的「寫入 SEALED」WARN（{@code CollapseService} 的 {@code warn}，帶 LOG_MARKER）。 */
    private static int sealWriteWarnings(LogCapture capture, UUID sid) {
        return capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.LOG_MARKER.getName(), "寫入 SEALED", sid.toString()).size();
    }

    /** 這個 session 的「目的地解析持續等待」WARN（帶 LOG_MARKER）。 */
    private static int waitWarnings(LogCapture capture, UUID sid) {
        return capture.marked(org.apache.logging.log4j.Level.WARN, dev.quantumchamber.collapse.CollapseService.LOG_MARKER.getName(),
                "目的地解析持續等待", sid.toString()).size();
    }

    /** spec §10.8「進入 SEALED」（以字面值斷言，不信任 production 常數）。 */
    private static final String SEALED_KEY = "message.quantumchamber.m5.sealed";

    private static boolean receiptStillSealed(SessionRecoveryState journal, UUID sid) {
        var record = journal.flushedRecords().get(sid);
        return record != null && record.state() == SessionState.SEALED && record.equals(journal.records().get(sid));
    }

    /** flushed 是 SEALED(reason)，而且 current 與 flushed 相同。 */
    private static boolean sealedWith(SessionRecoveryState journal, UUID sid, SealedReason reason) {
        var record = journal.flushedRecords().get(sid);
        return record != null && record.state() == SessionState.SEALED && record.collapse().sealedReason().equals(java.util.Optional.of(reason))
                && record.equals(journal.records().get(sid));
    }

    /** allocation ledger 正式檔裡 UniverseId 為 {@code id} 的 entry 數（直接讀 NBT，不經 production 的 strict decode）。 */
    private static int allocationEntries(MinecraftServer server, UniverseId id) {
        var file = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(UniverseAllocationLedger.FILE_NAME);
        try {
            var entries = net.minecraft.nbt.NbtIo.readCompressed(file, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes()).getList("Entries", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
            int count = 0;
            for (int i = 0; i < entries.size(); i++) if (entries.getCompound(i).getUuid("UniverseId").equals(id.value())) count++;
            return count;
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("讀不到 allocation ledger", failure);
        }
    }

    /** {@code data/<name>} 的 bytes；不存在時為空陣列。 */
    private static byte[] bytes(java.nio.file.Path data, String name) {
        try {
            var file = data.resolve(name);
            return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
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
     * GameTestException，以 {@link #failDefinitely} 讓 test 確定失敗，再丟出（runner 只攔 GameTestException，其他例外會讓 GameTest server
     * 崩潰）。在 runner 裡呼叫時 test 在下一個 tick 失敗，直接呼叫或在 runAtTick 的 callback 裡呼叫時在同一個 tick 失敗，不必等 tickLimit。
     * 之後每次呼叫都直接丟同一個例外（{@code BROKEN}）：失敗落地之前 runner 還會重試一次，這樣不會再記一次 ERROR、不會再排第二個失敗，
     * 也不會在那之前因為判定剛好通過而先寫出 PASS。
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
            failDefinitely(context, converted);
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
     *     崩潰：在 runner 裡時寫一行 {@code status=FAIL error=…}，以 {@link #failDefinitely} 在下一個 tick 確定失敗；在呼叫當下的第一輪時，轉成 GameTestException
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
     * 讓 test 確定失敗：以原版公開的 {@code TimedTaskRunner.fail} 新增一個 runner，它的工作直接呼叫 {@code test.fail}。runner 放在
     * {@code CopyOnWriteArrayList}，不碰 runAtTick 的表，所以在任何情境都能呼叫：在 test 方法或 runAtTick 的 callback 裡時，同一個 tick 的
     * runner 階段就失敗；在 runner 的工作裡時，下一個 tick 失敗。需要它，是因為 runner 工作丟出的 GameTestException 只會被原版當成
     * 「還沒好」而重試，要等到 tickLimit 才會失敗。
     */
    private static void failDefinitely(TestContext context, GameTestException failure) {
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
