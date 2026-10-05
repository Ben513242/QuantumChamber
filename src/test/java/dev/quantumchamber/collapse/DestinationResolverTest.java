package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseServiceTest.A;
import static dev.quantumchamber.collapse.CollapseServiceTest.B;
import static dev.quantumchamber.collapse.CollapseServiceTest.EXISTING;
import static dev.quantumchamber.collapse.CollapseServiceTest.EXISTING_ID;
import static dev.quantumchamber.collapse.CollapseServiceTest.NEW;
import static dev.quantumchamber.collapse.CollapseServiceTest.SOURCE;
import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities.Authority;
import dev.quantumchamber.collapse.DestinationResolver.Materialization;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseCatalogStore;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * F4a：{@link DestinationResolver}（spec §8 的 RESOLVE）。權威是真的 {@link CollapseAuthorities}（package-private 建構子；IO 經測試的
 * {@link CollapseAuthorities.LedgerIo} 注入，catalog 是真的 M3 {@link UniverseRegistryState}，寫出失敗經測試的 {@link CollapseAuthorities.CatalogPort}
 * 注入），lifecycle 是 {@link DestinationResolver.Lifecycle} 的測試實作（plan 決策 9），所以 NEW 四步、§8.3 的兩個方向與 EXISTING 重驗都能在 pure JVM
 * 檢查。需要真的物化、門與 journal 的流程由 M5 GameTest 覆蓋。record 取自 {@link CollapseServiceTest} 的 fixture（選中者為 A）。
 */
class DestinationResolverTest {
    @TempDir Path root;
    private final int[] now = {1000};
    private static final long GAME_TIME = 777;

    @BeforeAll static void gameVersion() {
        // catalog 的 {data, DataVersion} wrapper 需要 game version（比照 CollapseAuthoritiesTest）。
        net.minecraft.SharedConstants.createGameVersion();
    }

    // ---- NEW（§8.2） ----

    @Test void newResolvesLedgerCatalogWorldAndDiscoveryInOrderAndIsIdempotent() throws Exception {
        var world = world("new");
        var view = settled(NEW);
        var id = universe(view);
        var order = new ArrayList<String>();
        world.lifecycle.onMaterialize = requested -> order.add("materialize catalog=" + world.flushedAvailability(requested)
                + " confirmed=" + !world.authorities.unconfirmed(Authority.CATALOG) + " discovered=" + world.discovered(requested).isPresent());
        var first = world.resolver.resolve(view);
        var second = world.resolver.resolve(view);
        var record = world.discovered(id).orElse(new UniverseDiscoveryRecord(id, 0, 0, Optional.empty(), false));
        assertAll(
                () -> assertTrue(world.discovered(id).isPresent(), "discovery 已追加"),
                () -> assertEquals(new ResolveResult.Resolved(), first, "第一次：ledger → catalog → 物化 → discovery"),
                () -> assertEquals(List.of("materialize catalog=EAGER_ENABLED confirmed=true discovered=false"), order.stream().limit(1).toList(),
                        "物化在 catalog 以 EAGER_ENABLED checked 寫出、確認之後，discovery 追加之前"),
                () -> assertEquals(Optional.of(UniverseAllocationLedger.Entry.of(intent(view))), world.ledgerEntry(intent(view)), "allocation entry 是推導值"),
                () -> assertEquals(DesiredAvailability.EAGER_ENABLED, world.diskAvailability(id), "catalog 已落盤為 EAGER_ENABLED"),
                () -> assertEquals(Optional.of(0L), Optional.ofNullable(world.catalog.state.flushedRecords().get(id))
                        .map(stored -> stored.definition().allocationOrdinal()), "空 catalog 的 ordinal 從 0 開始"),
                () -> assertEquals(GAME_TIME, record.discoveredAtGameTime(), "discovery 的 game time 是傳入的時鐘（overworld）"),
                () -> assertEquals(Optional.of(A), record.firstObserver(), "firstObserver 是 SelectedBy"),
                () -> assertTrue(record.gameplayEligible()),
                () -> assertEquals(new ResolveResult.Resolved(), second, "第二次：每一步都 idempotent"),
                () -> assertEquals(List.of(id, id), world.lifecycle.materialized, "第二次也經 lifecycle（已經 ACTIVE 時回同一個 world）"),
                () -> assertEquals(1, world.catalog.state.flushedRecords().size(), "沒有第二筆 catalog"),
                () -> assertEquals(1, world.discoveryRecords().size(), "沒有第二筆 discovery"),
                () -> assertEquals(1, world.diskLedger().entries().size(), "沒有第二筆 allocation entry"));
    }

    @Test void newDestinationThatIsNotTheLedgerEntryIsCorrupt() throws Exception {
        var world = world("mismatch");
        var measured = CollapseServiceTest.measured(DimensionRole.OVERWORLD, NEW);
        var other = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000f9"));
        var view = CollapseServiceTest.cleared(CollapseServiceTest.returned(
                CollapseServiceTest.collapsing(measured, new CollapseDestination.Universe(other)), A, B));
        var result = world.resolver.resolve(view);
        assertAll(
                () -> assertSealed(SealedReason.AUTHORITY_CORRUPT, result, "Destination 與 allocation entry 不符"),
                () -> assertTrue(world.catalog.state.flushedRecords().isEmpty(), "不配置 catalog"),
                () -> assertEquals(List.of(), world.lifecycle.materialized, "不物化"),
                () -> assertTrue(world.discoveryRecords().isEmpty(), "不追加 discovery"));
    }

    /** §6 roll-forward 與 §11.2：allocation 的讀回失敗 → ALLOCATION_IO_FAILED；收斂之後重試只得到同一個宇宙。 */
    @Test void allocationReadbackFailureSealsAllocationIoFailedAndRetryConvergesToOneUniverse() throws Exception {
        var world = world("allocation-io");
        var view = settled(NEW);
        var id = universe(view);
        world.io.failReadback(UniverseAllocationLedger.FILE_NAME);
        var failed = world.resolver.resolve(view);
        boolean unconfirmed = world.authorities.unconfirmed(Authority.ALLOCATION_LEDGER);
        var landed = world.diskLedger().entries().size();
        var withinBackoff = world.resolver.resolve(view);
        now[0] += CollapseBackoff.INITIAL_TICKS;
        var retried = world.resolver.resolve(view);
        assertAll(
                () -> assertSealed(SealedReason.ALLOCATION_IO_FAILED, failed, "atomic move 完成、讀回失敗"),
                () -> assertTrue(unconfirmed, "allocation 權威未確認"),
                () -> assertEquals(1, landed, "已落地的 append 不移除"),
                () -> assertSealed(SealedReason.ALLOCATION_IO_FAILED, withinBackoff, "權威的退避期內：仍未確認，不做新決策"),
                () -> assertEquals(new ResolveResult.Resolved(), retried, "退避之後先收斂再繼續"),
                () -> assertEquals(1, world.diskLedger().entries().size(), "只有一筆 allocation entry"),
                () -> assertEquals(Set.of(id), world.catalog.state.flushedRecords().keySet(), "只有一個宇宙"),
                () -> assertEquals(List.of(id), world.lifecycle.materialized, "只物化一次"));
    }

    /** §6.2：catalog 有未確認的寫入時等待、不 SEALED、不物化；收斂之後照常完成。 */
    @Test void catalogWithUnconfirmedWritesWaitsWithoutSealing() throws Exception {
        var world = world("catalog-wait");
        var view = settled(NEW);
        var id = universe(view);
        // 另一筆配置的寫出持續失敗（例如另一個 session）：catalog 一直 dirty。
        world.catalog.state.allocateOverworld(UUID.fromString("00000000-0000-0000-0000-0000000000b7"), 0);
        world.catalog.failBeforeMove(100);
        var waiting = world.resolver.resolve(view);
        var stillWaiting = world.resolver.resolve(view);
        world.catalog.failBeforeMove(0);
        now[0] += CollapseBackoff.INITIAL_TICKS;
        var resolved = world.resolver.resolve(view);
        assertAll(
                () -> assertTrue(waiting instanceof ResolveResult.Wait, () -> "等待而不 SEALED：" + waiting),
                () -> assertTrue(stillWaiting instanceof ResolveResult.Wait, () -> "退避期內仍等待：" + stillWaiting),
                () -> assertEquals(new ResolveResult.Resolved(), resolved, "catalog 收斂之後完成"),
                () -> assertEquals(Optional.of(1L), Optional.ofNullable(world.catalog.state.flushedRecords().get(id))
                        .map(record -> record.definition().allocationOrdinal()), "收斂之後才配置，ordinal 接在另一筆之後"),
                () -> assertEquals(List.of(id), world.lifecycle.materialized, "等待期間不物化"));
    }

    @Test void orphanStorageIsCorruptAndNothingIsMaterialized() throws Exception {
        var world = world("orphan");
        var view = settled(NEW);
        var id = universe(view);
        var key = UniverseKeys.world(id, DimensionRole.OVERWORLD).getValue();
        var orphan = Files.createDirectories(world.save.resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()));
        var result = world.resolver.resolve(view);
        assertAll(
                () -> assertSealed(SealedReason.AUTHORITY_CORRUPT, result, "orphan storage"),
                () -> assertTrue(Files.isDirectory(orphan), "不刪除 orphan 目錄"),
                () -> assertTrue(world.catalog.state.flushedRecords().isEmpty(), "不配置"),
                () -> assertEquals(List.of(), world.lifecycle.materialized, "不物化"));
    }

    // ---- §8.3 物化失敗 ----

    @Test void materializeFailureDisablesTheRecordAndSealsMaterializeFailed() throws Exception {
        var world = world("materialize-failed");
        var view = settled(NEW);
        var id = universe(view);
        world.lifecycle.fail.add(id);
        Object[] result = {null};
        var errors = world.logs(() -> result[0] = world.resolver.resolve(view), Level.ERROR);
        assertAll(
                () -> assertSealed(SealedReason.MATERIALIZE_FAILED, (ResolveResult) result[0], "物化失敗"),
                () -> assertEquals(DesiredAvailability.DISABLED, world.flushedAvailability(id), "flushed 已改為 DISABLED"),
                () -> assertEquals(DesiredAvailability.DISABLED, world.diskAvailability(id), "已落盤（下次開服 bootstrap 不會因這筆停服）"),
                () -> assertFalse(world.authorities.unconfirmed(Authority.CATALOG), "DISABLED 已確認"),
                () -> assertTrue(world.discoveryRecords().isEmpty(), "不追加 discovery"),
                () -> assertEquals(List.of(), errors, "DISABLED 的寫出成功時不記 ERROR"));
    }

    /** §8.3 方向一：改為 DISABLED 的寫出失敗 → roll-forward（current 保留 DISABLED、維持 dirty），記一則 ERROR，照樣 MATERIALIZE_FAILED。 */
    @Test void disablingWriteFailureRollsForwardKeepsDisabledAndLogsOneError() throws Exception {
        var cases = new ArrayList<Executable>();
        for (boolean landed : new boolean[] {true, false}) {
            var world = world("disable-fails-" + landed);
            var view = settled(NEW);
            var id = universe(view);
            world.lifecycle.fail.add(id);
            // 第 1 次寫出是配置（成功），第 2 次是改為 DISABLED（失敗）。
            world.catalog.failAfter(1, landed);
            Object[] result = {null};
            var errors = world.logs(() -> result[0] = world.resolver.resolve(view), Level.ERROR);
            var current = world.currentAvailability(id);
            var flushed = world.flushedAvailability(id);
            var disk = world.diskAvailability(id);
            boolean dirty = world.catalog.state.isDirty(), unconfirmed = world.authorities.unconfirmed(Authority.CATALOG);
            now[0] += 1;
            world.authorities.convergeIfNeeded(Authority.CATALOG);
            var converged = world.diskAvailability(id);
            String label = landed ? "atomic move 完成、讀回失敗" : "atomic move 之前失敗";
            cases.add(() -> assertSealed(SealedReason.MATERIALIZE_FAILED, (ResolveResult) result[0], label));
            cases.add(() -> assertEquals(DesiredAvailability.DISABLED, current, label + "：current 保留 DISABLED"));
            cases.add(() -> assertEquals(DesiredAvailability.EAGER_ENABLED, flushed, label + "：flushed 仍是上一份"));
            cases.add(() -> assertEquals(landed ? DesiredAvailability.DISABLED : DesiredAvailability.EAGER_ENABLED, disk, label + "：磁碟"));
            cases.add(() -> assertTrue(dirty && unconfirmed, label + "：維持 dirty，catalog 未確認"));
            cases.add(() -> assertEquals(DesiredAvailability.DISABLED, converged, label + "：之後的寫出帶著 DISABLED"));
            cases.add(() -> assertEquals(1, errors.size(), label + "：ERROR 一則：" + errors));
            cases.add(() -> assertTrue(errors.stream().allMatch(entry -> entry.marked() && entry.message().contains("session=" + view.sessionUuid())
                    && entry.thrown() != null), label + "：ERROR 帶 LOG_MARKER、session 與 stack"));
        }
        assertAll(cases);
    }

    /**
     * §8.3 的重試：本 session 推導的 UniverseId 在 catalog 是 DISABLED（§6 收斂規則表：等同 §8.3 已完成），本 instance 第一次遇到時先 checked 改回
     * EAGER_ENABLED 再物化。F4a fix round 1（I-1）取代首輪的「沒有重試旗標時 DISABLED 是損壞」：是否接受 DISABLED 不再由呼叫端依最近一次封存的原因傳入；
     * EXISTING 的 DISABLED 仍是 EXISTING_INELIGIBLE（{@link #existingIneligibleForEachRevalidationCondition}）。
     */
    @Test void disabledRecordOfTheSessionIsReenabledThenMaterialized() throws Exception {
        var world = world("retry");
        var view = settled(NEW);
        var id = universe(view);
        world.afterMaterializeFailure(view);
        var order = new ArrayList<DesiredAvailability>();
        world.lifecycle.onMaterialize = requested -> order.add(world.flushedAvailability(requested));
        var retried = world.resolver.resolve(view);
        assertAll(
                () -> assertEquals(new ResolveResult.Resolved(), retried, "重試：改回 EAGER_ENABLED、物化、discovery"),
                () -> assertEquals(List.of(DesiredAvailability.EAGER_ENABLED), order, "物化之前已 checked 改回 EAGER_ENABLED"),
                () -> assertEquals(DesiredAvailability.EAGER_ENABLED, world.diskAvailability(id)),
                () -> assertTrue(world.discovered(id).isPresent(), "重試成功之後追加 discovery"));
    }

    /**
     * F4a fix round 1（I-1，spec §6 收斂規則表、§6.2、§11.2）：「接受 DISABLED」與最近一次封存的原因無關。同一個 resolver（server instance）中，§8.3 的
     * 重試（重啟之後 MATERIALIZE_FAILED 的那一次重試，或開服時就是 COLLAPSING、catalog 已是 DISABLED）在第 1 步遇到 ledger 未確認（另一個 session 的
     * append 讀回失敗、仍在退避）→ SEALED(ALLOCATION_IO_FAILED)；退避之後的下一次重試仍把 DISABLED 視為 §8.3 已完成：改回 EAGER_ENABLED、物化、
     * RESOLVED，絕不是 AUTHORITY_CORRUPT。暫時性的 IO 不會變成永久原因。
     */
    @Test void disabledRecordOfTheSessionIsRetriedRegardlessOfTheLastSealedReason() throws Exception {
        var world = world("disabled-after-io");
        var view = settled(NEW);
        var id = universe(view);
        world.afterMaterializeFailure(view);
        world.io.failReadback(UniverseAllocationLedger.FILE_NAME);
        var other = world.authorities.lookupOrAppendAllocation(otherIntent());
        var sealedFirst = world.resolver.resolve(view);
        var callsWhileSealed = List.copyOf(world.lifecycle.materialized);
        var availabilityWhileSealed = world.diskAvailability(id);
        now[0] += CollapseBackoff.INITIAL_TICKS;
        var order = new ArrayList<DesiredAvailability>();
        world.lifecycle.onMaterialize = requested -> order.add(world.flushedAvailability(requested));
        var retried = world.resolver.resolve(view);
        assertAll(
                () -> assertEquals(CollapseAuthorities.Lookup.Status.WRITE_FAILED, other.status(), "fixture：另一個 session 的 append 未確認"),
                () -> assertSealed(SealedReason.ALLOCATION_IO_FAILED, sealedFirst, "第 1 步：ledger 未確認"),
                () -> assertEquals(List.of(), callsWhileSealed, "改回 EAGER_ENABLED 之前就封存：沒有物化"),
                () -> assertEquals(DesiredAvailability.DISABLED, availabilityWhileSealed, "封存時 catalog 仍是 DISABLED"),
                () -> assertEquals(new ResolveResult.Resolved(), retried, "下一次重試仍走 §8.3，不是 AUTHORITY_CORRUPT：" + retried),
                () -> assertEquals(List.of(DesiredAvailability.EAGER_ENABLED), order, "物化之前已 checked 改回 EAGER_ENABLED"),
                () -> assertEquals(DesiredAvailability.EAGER_ENABLED, world.diskAvailability(id)),
                () -> assertTrue(world.discovered(id).isPresent(), "重試成功之後追加 discovery"));
    }

    /**
     * F4a fix round 1（I-1）：本 instance 已用掉物化的額度（第一次物化就失敗，或已做過一次 §8.3 的重試：改回 EAGER_ENABLED 之後物化失敗，或改回
     * EAGER_ENABLED 的寫出失敗而 restore）之後再遇到 DISABLED：SEALED(MATERIALIZE_FAILED)，等下次重啟；不是 AUTHORITY_CORRUPT，也不再寫 catalog、
     * 不再物化。
     */
    @Test void aSpentInstanceSealsMaterializeFailedWithoutWritingOrMaterializingAgain() throws Exception {
        record Row(String label, boolean disabledBefore, boolean materializeFails, boolean reenableFails) { }
        var rows = List.of(
                new Row("本 instance 第一次物化就失敗", false, true, false),
                new Row("§8.3 的重試物化失敗", true, true, false),
                new Row("§8.3 的重試改回 EAGER_ENABLED 的寫出失敗（restore）", true, false, true));
        var cases = new ArrayList<Executable>();
        int index = 0;
        for (var row : rows) {
            var world = world("spent-" + index++);
            var view = settled(NEW);
            var id = universe(view);
            if (row.disabledBefore()) world.afterMaterializeFailure(view);
            if (row.materializeFails()) world.lifecycle.fail.add(id);
            if (row.reenableFails()) world.catalog.failAfter(0, false);
            var first = world.resolver.resolve(view);
            // restore 之後 catalog 仍 dirty：先收斂，再比對第二次解析有沒有寫入。
            now[0] += 1;
            world.authorities.convergeIfNeeded(Authority.CATALOG);
            var calls = world.lifecycle.materialized.size();
            var before = world.snapshot();
            var second = world.resolver.resolve(view);
            cases.add(() -> assertSealed(SealedReason.MATERIALIZE_FAILED, first, row.label() + "：fixture"));
            cases.add(() -> assertSealed(SealedReason.MATERIALIZE_FAILED, second, row.label() + "：再遇到 DISABLED 等下次重啟，不是 AUTHORITY_CORRUPT"));
            cases.add(() -> assertEquals(calls, world.lifecycle.materialized.size(), row.label() + "：不再物化"));
            cases.add(() -> assertEquals(before, world.snapshot(), row.label() + "：不再寫任何權威"));
            cases.add(() -> assertEquals(DesiredAvailability.DISABLED, world.diskAvailability(id), row.label() + "：catalog 維持 DISABLED"));
        }
        assertAll(cases);
    }

    /** §8.3 方向二：重試時改回 EAGER_ENABLED 的寫出失敗 → restore（current 改回 DISABLED、維持 dirty），不物化。 */
    @Test void reenableWriteFailureRestoresDisabledAndDoesNotMaterialize() throws Exception {
        var cases = new ArrayList<Executable>();
        for (boolean landed : new boolean[] {true, false}) {
            var world = world("reenable-fails-" + landed);
            var view = settled(NEW);
            var id = universe(view);
            world.afterMaterializeFailure(view);
            world.catalog.failAfter(0, landed);
            var result = world.resolver.resolve(view);
            var current = world.currentAvailability(id);
            var disk = world.diskAvailability(id);
            boolean dirty = world.catalog.state.isDirty();
            var calls = List.copyOf(world.lifecycle.materialized);
            now[0] += 1;
            world.authorities.convergeIfNeeded(Authority.CATALOG);
            var converged = world.diskAvailability(id);
            String label = landed ? "atomic move 完成、讀回失敗" : "atomic move 之前失敗";
            cases.add(() -> assertSealed(SealedReason.MATERIALIZE_FAILED, result, label));
            cases.add(() -> assertEquals(DesiredAvailability.DISABLED, current, label + "：current 改回 DISABLED（restore）"));
            cases.add(() -> assertTrue(dirty, label + "：維持 dirty"));
            cases.add(() -> assertEquals(landed ? DesiredAvailability.EAGER_ENABLED : DesiredAvailability.DISABLED, disk, label + "：磁碟"));
            cases.add(() -> assertEquals(List.of(), calls, label + "：不物化"));
            cases.add(() -> assertEquals(DesiredAvailability.DISABLED, converged, label + "：下一次寫出以 DISABLED 覆寫磁碟"));
        }
        assertAll(cases);
    }

    @Test void materializeRetryThatFailsAgainDisablesAgain() throws Exception {
        var world = world("retry-fails");
        var view = settled(NEW);
        var id = universe(view);
        world.afterMaterializeFailure(view);
        world.lifecycle.fail.add(id);
        var order = new ArrayList<DesiredAvailability>();
        world.lifecycle.onMaterialize = requested -> order.add(world.flushedAvailability(requested));
        var result = world.resolver.resolve(view);
        assertAll(
                () -> assertSealed(SealedReason.MATERIALIZE_FAILED, result, "重試仍失敗"),
                () -> assertEquals(List.of(DesiredAvailability.EAGER_ENABLED), order, "物化之前改回 EAGER_ENABLED"),
                () -> assertEquals(DesiredAvailability.DISABLED, world.diskAvailability(id), "之後再改回 DISABLED"),
                () -> assertTrue(world.discoveryRecords().isEmpty()));
    }

    @Test void lifecycleWithoutAReadyContextWaits() throws Exception {
        var world = world("not-ready");
        var view = settled(NEW);
        world.lifecycle.notReady = true;
        var result = world.resolver.resolve(view);
        assertAll(
                () -> assertTrue(result instanceof ResolveResult.Wait, () -> "等待而不 SEALED：" + result),
                () -> assertEquals(DesiredAvailability.EAGER_ENABLED, world.flushedAvailability(universe(view)), "catalog 不改為 DISABLED"),
                () -> assertTrue(world.discoveryRecords().isEmpty()));
    }

    /** §6 讀取失敗的分類（discovery，NEW 的第 4 步）。 */
    @Test void discoveryFailuresMapToTheirAuthorityReasons() throws Exception {
        var unreadable = world("discovery-io");
        unreadable.io.failReadback(UniverseDiscoveryState.FILE_NAME);
        var missing = world("discovery-missing");
        Files.delete(missing.save.resolve("data").resolve(UniverseDiscoveryState.FILE_NAME));
        var readback = world("discovery-readback");
        // discovery 每次使用都先讀一次正式檔，第二次讀才是 atomic move 之後的讀回。
        readback.io.failReadbackAfter(UniverseDiscoveryState.FILE_NAME, 1);
        var view = settled(NEW);
        var id = universe(view);
        var io = unreadable.resolver.resolve(view);
        var lost = missing.resolver.resolve(view);
        var write = readback.resolver.resolve(view);
        assertAll(
                () -> assertSealed(SealedReason.AUTHORITY_UNREADABLE, io, "讀檔的 IO 例外"),
                () -> assertSealed(SealedReason.AUTHORITY_CORRUPT, lost, "檔案不存在是遺失"),
                () -> assertSealed(SealedReason.ALLOCATION_IO_FAILED, write, "追加的讀回失敗"),
                () -> assertTrue(readback.discovered(id).isPresent(), "已落地的 append 不移除"),
                () -> assertEquals(List.of(id), unreadable.lifecycle.materialized, "discovery 在物化之後才讀"));
    }

    @Test void initializationFailureSealsEveryUniverseDestinationWithTheInitializationReason() throws Exception {
        var cases = new ArrayList<Executable>();
        for (var reason : List.of(SealedReason.AUTHORITY_CORRUPT, SealedReason.AUTHORITY_UNREADABLE)) {
            var save = Files.createDirectories(root.resolve("init-" + reason));
            UniverseDiscoveryState.loadOrCreate(save);
            var io = new Io();
            var catalog = new TestCatalog(save);
            var authorities = new CollapseAuthorities(save, io, catalog, () -> now[0]);
            SessionRecoveryState journal;
            if (reason == SealedReason.AUTHORITY_CORRUPT) {
                // schema 4 之後缺 ledger：遺失。
                journal = journal(4);
            } else {
                journal = new SessionRecoveryState();
                Files.createDirectories(save.resolve("data"));
                Files.write(save.resolve("data").resolve(UniverseAllocationLedger.FILE_NAME), new byte[] {1});
                io.failReadback(UniverseAllocationLedger.FILE_NAME);
            }
            var init = authorities.initialize(() -> journal);
            var lifecycle = new FakeLifecycle(catalog);
            var resolver = new DestinationResolver(authorities, catalog, lifecycle, () -> GAME_TIME);
            var newResult = resolver.resolve(settled(NEW));
            var existingResult = resolver.resolve(settled(EXISTING));
            cases.add(() -> assertEquals(Optional.of(reason), init.sealedReason(), "fixture：初始化失敗的分類"));
            cases.add(() -> assertSealed(reason, newResult, "NEW：" + reason));
            cases.add(() -> assertSealed(reason, existingResult, "EXISTING：" + reason));
            cases.add(() -> assertTrue(catalog.state.flushedRecords().isEmpty() && lifecycle.materialized.isEmpty(), "不配置、不物化：" + reason));
        }
        assertAll(cases);
    }

    // ---- EXISTING（§8.1） ----

    @Test void existingRevalidatesWithoutWritingAnyAuthority() throws Exception {
        var world = world("existing");
        world.existingUniverse(true, DesiredAvailability.EAGER_ENABLED);
        world.lifecycle.active.add(EXISTING_ID);
        var before = world.snapshot();
        var result = world.resolver.resolve(settled(EXISTING));
        assertAll(
                () -> assertEquals(new ResolveResult.Resolved(), result),
                () -> assertEquals(before, world.snapshot(), "只重驗：ledger、catalog、discovery 的檔案都不變"),
                () -> assertEquals(List.of(), world.lifecycle.materialized, "EXISTING 不物化"));
    }

    /** spec §8.1 的每一個條件：任一不符為 EXISTING_INELIGIBLE（含 F2c 交接的 {@code gameplayEligible=false}），而且不寫任何權威。 */
    @Test void existingIneligibleForEachRevalidationCondition() throws Exception {
        record Row(String label, boolean discovered, boolean eligible, DesiredAvailability availability, boolean active, DimensionRole source) { }
        var rows = List.of(
                new Row("discovery 沒有這個 UniverseId", false, true, DesiredAvailability.EAGER_ENABLED, true, DimensionRole.OVERWORLD),
                new Row("gameplayEligible=false", true, false, DesiredAvailability.EAGER_ENABLED, true, DimensionRole.OVERWORLD),
                new Row("catalog 沒有這筆", true, true, null, true, DimensionRole.OVERWORLD),
                new Row("catalog 是 DISABLED", true, true, DesiredAvailability.DISABLED, true, DimensionRole.OVERWORLD),
                new Row("沒有 exact ACTIVE world", true, true, DesiredAvailability.EAGER_ENABLED, false, DimensionRole.OVERWORLD),
                new Row("來源 role 不是 OVERWORLD", true, true, DesiredAvailability.EAGER_ENABLED, true, DimensionRole.NETHER));
        var cases = new ArrayList<Executable>();
        int index = 0;
        for (var row : rows) {
            var world = world("ineligible-" + index++);
            if (row.discovered()) world.existingDiscovery(row.eligible());
            if (row.availability() != null) world.existingCatalog(row.availability());
            if (row.active()) world.lifecycle.active.add(EXISTING_ID);
            var before = world.snapshot();
            var view = settled(row.source(), EXISTING);
            var result = world.resolver.resolve(view);
            cases.add(() -> assertSealed(SealedReason.EXISTING_INELIGIBLE, result, row.label()));
            cases.add(() -> assertEquals(before, world.snapshot(), row.label() + "：不寫任何權威"));
        }
        assertAll(cases);
    }

    /** F2c 裁示 3：discovery 或 catalog 有未確認的寫入時，EXISTING 等待，不判 EXISTING_INELIGIBLE、不 SEALED。 */
    @Test void existingWaitsWhileDiscoveryOrCatalogIsUnconfirmed() throws Exception {
        var discoveryPending = world("existing-discovery-pending");
        discoveryPending.existingUniverse(true, DesiredAvailability.EAGER_ENABLED);
        discoveryPending.lifecycle.active.add(EXISTING_ID);
        discoveryPending.io.failReadbackAfter(UniverseDiscoveryState.FILE_NAME, 1);
        var other = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000a9"));
        var pendingAppend = discoveryPending.authorities.appendDiscovery(other, 5, B);
        var discoveryWait = discoveryPending.resolver.resolve(settled(EXISTING));
        var catalogPending = world("existing-catalog-pending");
        catalogPending.existingUniverse(true, DesiredAvailability.EAGER_ENABLED);
        catalogPending.lifecycle.active.add(EXISTING_ID);
        catalogPending.catalog.state.allocateOverworld(UUID.fromString("00000000-0000-0000-0000-0000000000b8"), 5);
        catalogPending.catalog.failBeforeMove(100);
        var catalogWait = catalogPending.resolver.resolve(settled(EXISTING));
        assertAll(
                () -> assertEquals(CollapseAuthorities.Lookup.Status.WRITE_FAILED, pendingAppend.status(), "fixture：另一個 append 未確認"),
                () -> assertTrue(discoveryWait instanceof ResolveResult.Wait, () -> "discovery 未確認：等待：" + discoveryWait),
                () -> assertTrue(catalogWait instanceof ResolveResult.Wait, () -> "catalog 未確認：等待：" + catalogWait));
    }

    @Test void existingDiscoveryReadFailuresAreAuthorityReasons() throws Exception {
        var unreadable = world("existing-io");
        unreadable.existingUniverse(true, DesiredAvailability.EAGER_ENABLED);
        unreadable.io.failReadback(UniverseDiscoveryState.FILE_NAME);
        var corrupt = world("existing-corrupt");
        Files.write(corrupt.save.resolve("data").resolve(UniverseDiscoveryState.FILE_NAME), new byte[] {1, 2, 3});
        assertAll(
                () -> assertSealed(SealedReason.AUTHORITY_UNREADABLE, unreadable.resolver.resolve(settled(EXISTING)), "讀檔的 IO 例外"),
                () -> assertSealed(SealedReason.AUTHORITY_CORRUPT, corrupt.resolver.resolve(settled(EXISTING)), "內容損壞"));
    }

    @Test void resolveOnlyAcceptsSettledUniverseCollapses() throws Exception {
        var world = world("preconditions");
        var measured = CollapseServiceTest.measured(DimensionRole.OVERWORLD, NEW);
        var unsettled = CollapseServiceTest.collapsing(measured, CollapseService.destination(measured));
        var source = settled(SOURCE);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> world.resolver.resolve(measured), "MEASURED"),
                () -> assertThrows(IllegalArgumentException.class, () -> world.resolver.resolve(unsettled), "尚未全員返還、lease 未清"),
                () -> assertThrows(IllegalArgumentException.class, () -> world.resolver.resolve(source), "SOURCE 沒有 RESOLVE"));
    }

    // ---- fixtures ----

    /** 全員已返還、lease 清空、Destination 依 §7.2 的 COLLAPSING（OVERWORLD 來源）。 */
    private static SessionRecoveryRecord settled(int choice) { return settled(DimensionRole.OVERWORLD, choice); }

    private static SessionRecoveryRecord settled(DimensionRole role, int choice) {
        var measured = CollapseServiceTest.measured(role, choice);
        return CollapseServiceTest.cleared(CollapseServiceTest.returned(
                CollapseServiceTest.collapsing(measured, CollapseService.destination(measured)), A, B));
    }

    private static UniverseId universe(SessionRecoveryRecord view) { return ((CollapseDestination.Universe) view.collapse().destination()).id(); }

    private static NewUniverseIntent intent(SessionRecoveryRecord view) {
        return ((QuantumCandidate.New) view.selectedCandidate().orElseThrow()).intent();
    }

    /** 另一個 session 的 NEW intent（token 與 fixture 的不同）。 */
    private static NewUniverseIntent otherIntent() {
        byte[] token = new byte[32], seed = new byte[32];
        java.util.Arrays.fill(token, (byte) 0x5a); java.util.Arrays.fill(seed, (byte) 0x3c);
        return new NewUniverseIntent(new dev.quantumchamber.candidate.CandidateBytes(token),
                dev.quantumchamber.universe.GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, new dev.quantumchamber.candidate.CandidateBytes(seed));
    }

    private static void assertSealed(SealedReason expected, ResolveResult actual, String label) {
        assertTrue(actual instanceof ResolveResult.Sealed sealed && sealed.reason() == expected, () -> label + "：預期 SEALED(" + expected + ")，實際 " + actual);
    }

    private static SessionRecoveryState journal(int schema) {
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", schema); nbt.put("Records", new NbtList());
        var state = SessionRecoveryState.fromNbt(nbt); state.requireHealthy(); return state;
    }

    /** 一個存檔：已初始化的權威（兩個空 ledger）、M4 建立的空 discovery、空 catalog，以及 resolver。 */
    private World world(String name) throws IOException {
        var save = Files.createDirectories(root.resolve(name));
        UniverseDiscoveryState.loadOrCreate(save);
        var io = new Io();
        var catalog = new TestCatalog(save);
        var authorities = new CollapseAuthorities(save, io, catalog, () -> now[0]);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded(), "fixture：權威初始化");
        var lifecycle = new FakeLifecycle(catalog);
        return new World(save, io, catalog, authorities, lifecycle, new DestinationResolver(authorities, catalog, lifecycle, () -> GAME_TIME));
    }

    private record World(Path save, Io io, TestCatalog catalog, CollapseAuthorities authorities, FakeLifecycle lifecycle, DestinationResolver resolver) {
        DesiredAvailability flushedAvailability(UniverseId id) {
            var record = catalog.state.flushedRecords().get(id);
            return record == null ? null : record.desiredAvailability();
        }

        DesiredAvailability currentAvailability(UniverseId id) {
            var record = catalog.state.records().get(id);
            return record == null ? null : record.desiredAvailability();
        }

        /** 磁碟上的 catalog 的這筆；catalog 檔還不存在或沒有這筆時為 null。 */
        DesiredAvailability diskAvailability(UniverseId id) throws IOException {
            if (!Files.exists(catalog.path)) return null;
            var record = catalog.disk().flushedRecords().get(id);
            return record == null ? null : record.desiredAvailability();
        }

        /**
         * §8.3 進 SEALED(MATERIALIZE_FAILED) 之後的權威狀態（直接寫入，不經 resolver）：allocation entry 已確認、catalog 的這筆是已落盤的
         * {@code DISABLED}、discovery 沒有這筆。
         */
        void afterMaterializeFailure(SessionRecoveryRecord view) {
            assertEquals(CollapseAuthorities.Lookup.Status.APPENDED, authorities.lookupOrAppendAllocation(intent(view)).status(), "fixture：allocation entry");
            var id = universe(view);
            catalog.state.allocateOverworld(id.value(), 0);
            catalog.state.changeAvailability(id, DesiredAvailability.DISABLED);
            catalog.state.save(catalog.path);
        }

        Optional<UniverseAllocationLedger.Entry> ledgerEntry(NewUniverseIntent intent) throws IOException {
            return diskLedger().byToken(intent.allocationToken());
        }

        UniverseAllocationLedger diskLedger() throws IOException {
            return UniverseAllocationLedger.fromNbt(CheckedNbtFile.parse(Files.readAllBytes(save.resolve("data").resolve(UniverseAllocationLedger.FILE_NAME))));
        }

        List<UniverseDiscoveryRecord> discoveryRecords() throws IOException { return UniverseDiscoveryState.load(save).records(); }

        Optional<UniverseDiscoveryRecord> discovered(UniverseId id) {
            try { return UniverseDiscoveryState.load(save).find(id); } catch (IOException failure) { throw new UncheckedIOException(failure); }
        }

        /** EXISTING 的 fixture：discovery 有 EXISTING_ID（是否 gameplayEligible），catalog 有這筆（指定 availability）。 */
        void existingUniverse(boolean eligible, DesiredAvailability availability) throws IOException {
            existingDiscovery(eligible);
            existingCatalog(availability);
        }

        void existingDiscovery(boolean eligible) throws IOException {
            var row = new NbtCompound();
            row.putUuid("UniverseId", EXISTING_ID.value());
            row.putLong("DiscoveryOrdinal", 0); row.putLong("DiscoveredAtGameTime", 5); row.putBoolean("GameplayEligible", eligible);
            var records = new NbtList(); records.add(row);
            var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", 1); nbt.put("Records", records);
            UniverseDiscoveryState.fromNbt(nbt).save(save);
        }

        void existingCatalog(DesiredAvailability availability) {
            catalog.state.allocateOverworld(EXISTING_ID.value(), 0);
            if (availability == DesiredAvailability.DISABLED) catalog.state.changeAvailability(EXISTING_ID, DesiredAvailability.DISABLED);
            catalog.state.save(catalog.path);
        }

        /** 三個權威檔與 catalog 檔的 bytes（不存在的為空），用來斷言「不寫任何權威」。 */
        List<String> snapshot() throws IOException {
            var result = new ArrayList<String>();
            for (var name : List.of(UniverseAllocationLedger.FILE_NAME, ProjectionLedger.FILE_NAME, UniverseDiscoveryState.FILE_NAME, UniverseRegistryState.STATE_ID + ".dat")) {
                var file = save.resolve("data").resolve(name);
                result.add(name + "=" + (Files.exists(file) ? java.util.HexFormat.of().formatHex(Files.readAllBytes(file)) : ""));
            }
            result.add("catalog.current=" + catalog.state.records() + " dirty=" + catalog.state.isDirty());
            return result;
        }

        /** 執行 {@code action}，回傳期間 {@code quantumchamber} logger 的指定等級 log。 */
        List<LogEntry> logs(Runnable action, Level level) {
            try (var capture = new Logs()) {
                action.run();
                return capture.entries().stream().filter(entry -> entry.level() == level).toList();
            }
        }
    }

    /** {@link DestinationResolver.Lifecycle} 的測試實作：記下每一次物化；{@code fail} 中的 UniverseId 物化失敗；{@code active} 是 exact ACTIVE 的宇宙。 */
    private static final class FakeLifecycle implements DestinationResolver.Lifecycle {
        final TestCatalog catalog;
        final List<UniverseId> materialized = new ArrayList<>();
        final Set<UniverseId> fail = new HashSet<>();
        final Set<UniverseId> active = new HashSet<>();
        Consumer<UniverseId> onMaterialize = ignored -> { };
        boolean notReady;

        FakeLifecycle(TestCatalog catalog) { this.catalog = catalog; }

        @Override public Materialization materialize(UniverseId id) {
            if (notReady) return new Materialization(Materialization.Kind.NOT_READY, "測試：沒有 ready 的 context");
            materialized.add(id);
            onMaterialize.accept(id);
            if (fail.contains(id)) return new Materialization(Materialization.Kind.FAILED, "測試：物化失敗");
            active.add(id);
            return new Materialization(Materialization.Kind.ACTIVE, "");
        }

        @Override public boolean active(UniverseId id) { return active.contains(id); }
    }

    /** {@link CollapseAuthorities.LedgerIo} 的注入實作（比照 CollapseAuthoritiesTest）：依檔名讓下一次（或 skip 次之後的）讀取丟 IOException。 */
    private static final class Io implements CollapseAuthorities.LedgerIo {
        private record Fault(String fileName, int[] skip) { }
        private final List<Fault> faults = new ArrayList<>();

        @Override public byte[] read(Path path) throws IOException {
            for (var iterator = faults.iterator(); iterator.hasNext();) {
                var fault = iterator.next();
                if (!path.getFileName().toString().equals(fault.fileName())) continue;
                if (fault.skip()[0] > 0) { fault.skip()[0]--; continue; }
                iterator.remove();
                throw new IOException("注入：讀取 " + fault.fileName() + " 失敗");
            }
            return CheckedNbtFile.readFile(path);
        }

        void failReadback(String fileName) { failReadbackAfter(fileName, 0); }
        void failReadbackAfter(String fileName, int skip) { faults.add(new Fault(fileName, new int[] {skip})); }
    }

    /**
     * {@link CollapseAuthorities.CatalogPort} 的測試實作：catalog 是真的 M3 {@link UniverseRegistryState}，寫出經 M3 的 save；注入的失敗不經過 save，所以
     * catalog 照 M3 save 失敗時的語意維持 current 與 dirty。{@link #failAfter} 讓前 {@code ok} 次寫出照常，之後的一次失敗（{@code landed} 為真時先以 M3 的
     * store 寫出再丟，即「atomic move 完成、讀回失敗」）；{@link #failBeforeMove} 讓之後的 {@code times} 次都在寫出之前失敗。
     */
    private static final class TestCatalog implements CollapseAuthorities.CatalogPort {
        final Path path;
        final UniverseRegistryState state = new UniverseRegistryState();
        private int ok = -1, failures;
        private boolean landed;

        TestCatalog(Path save) { path = save.resolve("data").resolve(UniverseRegistryState.STATE_ID + ".dat"); }

        @Override public UniverseRegistryState catalog() { return state; }

        @Override public void flush(UniverseRegistryState catalog) {
            assertSame(state, catalog, "寫出的是同一個 PersistentState");
            if (ok > 0) { ok--; catalog.save(path); return; }
            if (ok == 0 || failures > 0) {
                if (ok == 0) ok = -1; else failures--;
                if (landed) {
                    try { UniverseCatalogStore.write(path, wrapped(catalog)); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
                throw new UncheckedIOException("注入：" + (landed ? "atomic move 完成、讀回失敗" : "atomic move 之前失敗"), new IOException("注入"));
            }
            catalog.save(path);
        }

        void failAfter(int okFlushes, boolean landBeforeFailure) { ok = okFlushes; landed = landBeforeFailure; failures = 0; }
        void failBeforeMove(int times) { ok = -1; landed = false; failures = times; }

        UniverseRegistryState disk() throws IOException {
            var loaded = UniverseRegistryState.fromNbt(UniverseCatalogStore.read(path).getCompound("data"));
            loaded.requireHealthy();
            return loaded;
        }

        private static NbtCompound wrapped(UniverseRegistryState catalog) {
            var wrapped = new NbtCompound();
            wrapped.put("data", catalog.writeNbt(new NbtCompound()));
            NbtHelper.putDataVersion(wrapped);
            return wrapped;
        }
    }

    private record LogEntry(Level level, String message, Throwable thrown, boolean marked) { }

    /** 只收 {@code quantumchamber} logger 的 log4j appender，另記是否帶 {@link CollapseService#LOG_MARKER}；先送探針確認攔得到 log 與 marker。 */
    private static final class Logs extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.LoggerContext context;
        private final List<LogEntry> events = Collections.synchronizedList(new ArrayList<>());

        Logs() {
            super("f4a-resolver-log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
            context = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger("quantumchamber")).getContext();
            start();
            context.getConfiguration().getRootLogger().addAppender(this, null, null);
            context.updateLoggers();
            var probe = "F4A_RESOLVER_LOG_PROBE " + UUID.randomUUID();
            org.slf4j.LoggerFactory.getLogger("quantumchamber").error(CollapseService.LOG_MARKER, probe);
            if (entries().stream().noneMatch(entry -> entry.message().equals(probe) && entry.marked())) {
                close();
                throw new IllegalStateException("log capture 無法觀察 quantumchamber 的 log 與 LOG_MARKER");
            }
            events.clear();
        }

        @Override public void append(LogEvent event) {
            if (!"quantumchamber".equals(event.getLoggerName())) return;
            var marker = event.getMarker();
            events.add(new LogEntry(event.getLevel(), event.getMessage().getFormattedMessage(), event.getThrown(),
                    marker != null && marker.isInstanceOf(CollapseService.LOG_MARKER.getName())));
        }

        List<LogEntry> entries() { synchronized (events) { return List.copyOf(events); } }

        @Override public void close() {
            context.getConfiguration().getRootLogger().removeAppender(getName());
            context.updateLoggers();
            stop();
        }
    }
}
