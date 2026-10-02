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
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
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
                witness.put("noDiscovery", CollapseAuthorities.get(server).discovery(id).status() == CollapseAuthorities.Lookup.Status.ABSENT);
                witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
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
                        && fault.calls() == 1 && server.getWorld(key) == null);
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
                // F4a-0 裁示 1：權威的 WARN 帶 TICK_LOG_MARKER，collapseFlowNeverWarned 依 marker 看得到它。
                witness.put("ledgerWarnCarriesTheTickMarker", capture.marked(org.apache.logging.log4j.Level.WARN, CollapseService.TICK_LOG_MARKER.getName(),
                        "ALLOCATION_LEDGER 的 checked 寫入未確認").size() == 1);
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
                witness.put("ledgerConvergedFirst", !authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER)
                        && capture.marked(org.apache.logging.log4j.Level.INFO, CollapseService.TICK_LOG_MARKER.getName(), "ALLOCATION_LEDGER 收斂完成").size() == 1);
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
