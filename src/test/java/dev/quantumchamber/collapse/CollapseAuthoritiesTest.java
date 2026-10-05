package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseAuthorities.AvailabilityPolicy.EAGER_ONLY;
import static dev.quantumchamber.collapse.CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY;
import static dev.quantumchamber.collapse.UniverseAllocationLedgerTest.intent;
import static dev.quantumchamber.collapse.UniverseAllocationLedgerTest.token;
import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.candidate.SourceFamilyRef;
import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities.Authority;
import dev.quantumchamber.collapse.CollapseAuthorities.InitResult.Outcome;
import dev.quantumchamber.collapse.CollapseAuthorities.Lookup;
import dev.quantumchamber.collapse.CollapseAuthorities.WriteResult;
import dev.quantumchamber.collapse.ProjectionLedger.Entry.Status;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseCatalogStore;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * spec §6 的 M5 authority：初始化與遺失判定（§15 的判定表）、既有紀錄的驗證、讀取失敗的分類，以及 roll-forward 收斂；F2c 起含 discovery
 * （§6.4）與 catalog 配置（§6.2）。檔案 IO 的失敗以 {@link CollapseAuthorities.LedgerIo}、catalog 的寫出失敗以
 * {@link CollapseAuthorities.CatalogPort} 這兩個 package-private port 注入（plan 決策 9），不用 testmod mixin。
 */
class CollapseAuthoritiesTest {
    @TempDir Path root;
    private final Io io = new Io();
    private final Map<Path, TestCatalog> catalogs = new HashMap<>();
    private final int[] now = {1000};
    private static final UUID CHAMBER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final BlockPos CONTROLLER = new BlockPos(32, 70, -48);
    /** 兩個由 M5 初始化建立的 ledger（Authority 另有 DISCOVERY 與 CATALOG）。 */
    private static final List<Authority> LEDGERS = List.of(Authority.ALLOCATION_LEDGER, Authority.PROJECTION_LEDGER);
    private static final UUID SELECTED_BY = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final SourceFamilyRef VANILLA = new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD);
    /** M4 時期就存在的 discovery record（fixture 寫入，沒有 firstObserver）。 */
    private static final UniverseDiscoveryRecord M4_RECORD =
            new UniverseDiscoveryRecord(UniverseId.of(UUID.fromString("00000000-0000-4000-8000-0000000000d1")), 0, 5, Optional.empty(), true);

    @BeforeAll static void gameVersion() {
        // catalog 的 {data, DataVersion} wrapper 需要 game version（比照 UniverseRegistryStateTest）。
        net.minecraft.SharedConstants.createGameVersion();
    }

    // ---- 初始化與遺失判定（spec §6、§15） ----

    @Test void freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost() throws Exception {
        var save = save("fresh");
        var journal = new SessionRecoveryState();
        assertFalse(journal.m5Evidence());
        var authorities = authorities(save);
        CollapseAuthorities.InitResult result;
        try (var logs = new CollapseLogCapture()) {
            result = authorities.initialize(() -> journal);
            assertEquals(1, logs.messages(Level.INFO).stream().filter(message -> message.startsWith("M5 authority 初始化完成")).count(),
                    () -> "初始化成功記一則 INFO：" + logs.messages(Level.INFO));
            assertEquals(List.of(), logs.messages(Level.ERROR));
        }
        assertAll(
            () -> assertTrue(result.succeeded(), () -> "初始化必須成功：" + result),
            () -> assertEquals(Outcome.CREATED, result.allocation()),
            () -> assertEquals(Outcome.CREATED, result.projection()),
            () -> assertFalse(result.flushedSchemaFour()),
            () -> assertTrue(authorities.initialized()),
            () -> assertTrue(journal.m5Evidence() && journal.m5ReadyThisInstance(), "成功時標記 journal"),
            () -> assertEquals(UniverseAllocationLedger.empty().toNbt(), disk(allocationFile(save))),
            () -> assertEquals(ProjectionLedger.empty().toNbt(), disk(projectionFile(save))),
            () -> assertNoTemporaryFiles(save));
        var appended = authorities.lookupOrAppendAllocation(intent(token(1)));
        assertEquals(Lookup.Status.APPENDED, appended.status(), () -> "第一次 NEW 不得被判為遺失：" + appended);
        assertEquals(Optional.of(UniverseAllocationLedger.Entry.of(intent(token(1)))), appended.value());
        var pending = pending(UniverseAllocationLedger.deriveUniverseId(token(1)));
        assertEquals(Lookup.Status.ABSENT, authorities.projection(CHAMBER, pending.universeId()).status());
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending).status());
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending.withStatus(Status.COMPLETE)).status());
        // 重啟：journal 已是 schema 4，兩個 ledger 照常 strict 讀取；同一個 token 得到同一筆 entry，不重寫。
        byte[] before = Files.readAllBytes(allocationFile(save));
        var restarted = authorities(save);
        var reload = restarted.initialize(() -> journal(4));
        assertEquals(List.of(Outcome.LOADED, Outcome.LOADED), List.of(reload.allocation(), reload.projection()));
        assertEquals(Lookup.Status.FOUND, restarted.lookupOrAppendAllocation(intent(token(1))).status());
        assertEquals(Optional.of(Status.COMPLETE), restarted.projection(CHAMBER, pending.universeId()).value().map(ProjectionLedger.Entry::status));
        assertArrayEquals(before, Files.readAllBytes(allocationFile(save)));
    }

    @Test void schemaThreeSaveWithNonEmptyDiscoveryIsNotTreatedAsLost() throws Exception {
        var save = save("m4-schema3");
        UniverseDiscoveryState.fromNbt(discoveryWithOneRecord()).save(save);
        var discoveryBytes = Files.readAllBytes(save.resolve("data/quantumchamber_universe_discovery.dat"));
        var journal = journal(3);
        assertEquals(3, schemaOf(journal));
        var authorities = authorities(save);
        var result = authorities.initialize(() -> journal);
        assertAll(
            () -> assertTrue(result.succeeded(), () -> "discovery 非空不是遺失的證據：" + result),
            () -> assertEquals(List.of(Outcome.CREATED, Outcome.CREATED), List.of(result.allocation(), result.projection())),
            () -> assertEquals(4, schemaOf(journal), "之後第一次 checked 寫入升為 schema 4"),
            () -> assertEquals(Lookup.Status.APPENDED, authorities.lookupOrAppendAllocation(intent(token(2))).status(), "升級後第一次 NEW"),
            () -> assertArrayEquals(discoveryBytes, Files.readAllBytes(save.resolve("data/quantumchamber_universe_discovery.dat")), "不碰 discovery"));
    }

    /** W11：初始化只建立了一個 ledger 就中斷；journal 仍是 schema 3，補建另一個，既有的照常讀取、不重寫。 */
    @Test void interruptedInitializationRecreatesOnlyTheMissingLedger() throws Exception {
        var cases = new ArrayList<Executable>();
        for (var existing : LEDGERS) cases.add(() -> {
            var save = save("w11-" + existing);
            var present = existing == Authority.ALLOCATION_LEDGER ? allocationFile(save) : projectionFile(save);
            var missing = existing == Authority.ALLOCATION_LEDGER ? projectionFile(save) : allocationFile(save);
            Files.createDirectories(present.getParent());
            NbtIo.writeCompressed(existing == Authority.ALLOCATION_LEDGER ? UniverseAllocationLedger.empty().toNbt() : ProjectionLedger.empty().toNbt(), present);
            byte[] before = Files.readAllBytes(present);
            var modified = Files.getLastModifiedTime(present);
            var journal = journal(3);
            var authorities = authorities(save);
            var result = authorities.initialize(() -> journal);
            var expected = existing == Authority.ALLOCATION_LEDGER ? List.of(Outcome.LOADED, Outcome.CREATED) : List.of(Outcome.CREATED, Outcome.LOADED);
            assertTrue(result.succeeded(), () -> existing + "：" + result);
            assertEquals(expected, List.of(result.allocation(), result.projection()), existing.toString());
            assertTrue(Files.exists(missing), "補建缺少的 ledger");
            assertArrayEquals(before, Files.readAllBytes(present), "既有的 ledger 不重寫");
            assertEquals(modified, Files.getLastModifiedTime(present));
            assertTrue(journal.m5ReadyThisInstance());
            assertEquals(Lookup.Status.APPENDED, authorities.lookupOrAppendAllocation(intent(token(3))).status());
        });
        assertAll(cases);
    }

    @Test void schemaFourJournalWithAMissingLedgerIsLostAndNeverRecreated() throws Exception {
        var cases = new ArrayList<Executable>();
        for (var deleted : LEDGERS) cases.add(() -> {
            var save = save("lost-" + deleted);
            seed(save);
            var gone = deleted == Authority.ALLOCATION_LEDGER ? allocationFile(save) : projectionFile(save);
            var kept = deleted == Authority.ALLOCATION_LEDGER ? projectionFile(save) : allocationFile(save);
            Files.delete(gone);
            byte[] keptBytes = Files.readAllBytes(kept);
            var journal = journal(4);
            try (var logs = new CollapseLogCapture()) {
                var authorities = authorities(save);
                var result = authorities.initialize(() -> journal);
                var expected = deleted == Authority.ALLOCATION_LEDGER ? List.of(Outcome.LOST, Outcome.LOADED) : List.of(Outcome.LOADED, Outcome.LOST);
                assertFalse(result.succeeded(), deleted.toString());
                assertEquals(expected, List.of(result.allocation(), result.projection()), deleted.toString());
                assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), result.sealedReason());
                assertFalse(authorities.initialized());
                assertFalse(journal.m5ReadyThisInstance(), "失敗時不呼叫 markCollapseAuthoritiesInitialized");
                assertEquals(4, schemaOf(journal), "讀到 schema 4 的 journal 照寫 4");
                assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(4))));
                assertCorrupt(authorities.projection(CHAMBER, existingUniverse()));
                assertEquals(WriteResult.Status.CORRUPT, authorities.writeProjection(pending(existingUniverse())).status());
                assertEquals(WriteResult.Status.CORRUPT, completeWithoutPending(authorities, deleted.toString()).status());
                assertFalse(Files.exists(gone), "不建檔、不補寫");
                assertArrayEquals(keptBytes, Files.readAllBytes(kept));
                assertEquals(1, logs.messages(Level.ERROR).stream().filter(message -> message.contains("M5 authority 初始化失敗")).count());
            }
        });
        assertAll(cases);
    }

    /** 只有 EXISTING 造訪過的存檔（allocation ledger 一直是空的）：刪掉任一個 ledger 也一律是遺失；投影不會被當成「第一次寫入」。 */
    @Test void existingOnlySaveWithADeletedLedgerIsLostToo() throws Exception {
        var cases = new ArrayList<Executable>();
        for (var deleted : LEDGERS) cases.add(() -> {
            var save = save("existing-only-" + deleted);
            var first = authorities(save);
            assertTrue(first.initialize(SessionRecoveryState::new).succeeded());
            var visited = pending(existingUniverse());
            assertEquals(WriteResult.Status.WRITTEN, first.writeProjection(visited).status());
            assertEquals(WriteResult.Status.WRITTEN, first.writeProjection(visited.withStatus(Status.COMPLETE)).status());
            assertTrue(diskAllocationLedger(save).entries().isEmpty(), "沒有任何 NEW");
            Files.delete(deleted == Authority.ALLOCATION_LEDGER ? allocationFile(save) : projectionFile(save));
            var restarted = authorities(save);
            var result = restarted.initialize(() -> journal(4));
            assertEquals(Outcome.LOST, deleted == Authority.ALLOCATION_LEDGER ? result.allocation() : result.projection(), deleted.toString());
            assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), result.sealedReason());
            var lookup = restarted.projection(CHAMBER, existingUniverse());
            assertCorrupt(lookup);
            assertNotEquals(Lookup.Status.ABSENT, lookup.status(), "COMPLETE 投影的 interior 不得被當成第一次寫入而重寫");
        });
        assertAll(cases);
    }

    @Test void initializationFailuresNeverUpgradeTheJournalOrAllowCollapsing() throws Exception {
        var cases = new ArrayList<Executable>();
        cases.add(() -> failedInitialization("garbage", save -> write(allocationFile(save), new byte[] {31, -117, 8, 0, 1, 2}),
                Outcome.CORRUPT, Outcome.NOT_CREATED, SealedReason.AUTHORITY_CORRUPT));
        cases.add(() -> failedInitialization("wrong-schema", save -> {
            var nbt = UniverseAllocationLedger.empty().toNbt(); nbt.putInt("SchemaVersion", 2);
            Files.createDirectories(allocationFile(save).getParent()); NbtIo.writeCompressed(nbt, allocationFile(save));
        }, Outcome.CORRUPT, Outcome.NOT_CREATED, SealedReason.AUTHORITY_CORRUPT));
        cases.add(() -> failedInitialization("read-io", save -> io.failReadback(ProjectionLedger.FILE_NAME),
                Outcome.NOT_CREATED, Outcome.UNREADABLE, SealedReason.AUTHORITY_UNREADABLE));
        cases.add(() -> failedInitialization("create-io", save -> io.failTemporary(ProjectionLedger.FILE_NAME),
                Outcome.CREATED, Outcome.UNREADABLE, SealedReason.AUTHORITY_UNREADABLE));
        cases.add(() -> failedInitialization("corrupt-and-io", save -> {
            write(allocationFile(save), new byte[] {1, 2, 3});
            io.failReadback(ProjectionLedger.FILE_NAME);
        }, Outcome.CORRUPT, Outcome.UNREADABLE, SealedReason.AUTHORITY_CORRUPT));
        assertAll(cases);
    }

    @Test void unhealthyJournalFailsInitializationWithoutTouchingTheLedgers() throws Exception {
        var save = save("journal-unhealthy");
        var broken = new NbtCompound(); broken.putInt("SchemaVersion", 99);
        var journal = SessionRecoveryState.fromNbt(broken);
        assertThrows(IllegalStateException.class, journal::requireHealthy);
        try (var logs = new CollapseLogCapture()) {
            var authorities = authorities(save);
            var result = authorities.initialize(() -> journal);
            assertEquals(List.of(Outcome.NOT_EXAMINED, Outcome.NOT_EXAMINED), List.of(result.allocation(), result.projection()));
            assertEquals(Optional.of(SealedReason.AUTHORITY_UNREADABLE), result.sealedReason());
            assertFalse(Files.exists(save.resolve("data")), "journal 不健康時無法判定補建或遺失，不得建立任何檔案");
            assertEquals(Lookup.Status.UNREADABLE, authorities.lookupOrAppendAllocation(intent(token(5))).status());
            assertEquals(1, logs.messages(Level.ERROR).size());
        }
    }

    @Test void initializationRunsOnceAndIsRequiredBeforeUse() throws Exception {
        var authorities = authorities(save("once"));
        assertThrows(IllegalStateException.class, () -> authorities.lookupOrAppendAllocation(intent(token(6))));
        assertThrows(IllegalStateException.class, () -> authorities.projection(CHAMBER, existingUniverse()));
        assertThrows(IllegalStateException.class, () -> authorities.appendDiscovery(existingUniverse(), 10, SELECTED_BY));
        assertThrows(IllegalStateException.class, () -> authorities.allocateOrVerifyCatalog(existingUniverse(), EAGER_ONLY));
        assertFalse(authorities.initialized());
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        assertThrows(IllegalStateException.class, () -> authorities.initialize(SessionRecoveryState::new));
    }

    // ---- 既有紀錄的驗證（spec §6、§6.1、§6.5） ----

    @Test void existingAllocationEntriesMustBeExactlyWhatThisSessionWouldWrite() throws Exception {
        var save = save("allocation-verify");
        var forgedId = new UniverseAllocationLedger.Entry(token(7), UniverseId.of(UUID.fromString("00000000-0000-8000-8000-000000000007")),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1);
        var wrongVersion = new UniverseAllocationLedger.Entry(token(8), UniverseAllocationLedger.deriveUniverseId(token(8)),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 2);
        var stolenId = new UniverseAllocationLedger.Entry(token(9), UniverseAllocationLedger.deriveUniverseId(token(10)),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1);
        var exact = UniverseAllocationLedger.Entry.of(intent(token(11)));
        var ledger = UniverseAllocationLedger.empty().append(forgedId).append(wrongVersion).append(stolenId).append(exact);
        Files.createDirectories(allocationFile(save).getParent());
        NbtIo.writeCompressed(ledger.toNbt(), allocationFile(save));
        NbtIo.writeCompressed(ProjectionLedger.empty().toNbt(), projectionFile(save));
        byte[] before = Files.readAllBytes(allocationFile(save));
        var authorities = authorities(save);
        assertTrue(authorities.initialize(() -> journal(4)).succeeded(), "strict decode 只驗結構，整份 ledger 照常載入");
        assertAll(
            () -> assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(7)))),
            () -> assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(8)))),
            () -> assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(10)))),
            () -> assertEquals(Lookup.Status.FOUND, authorities.lookupOrAppendAllocation(intent(token(11))).status()),
            () -> assertEquals(Optional.of(exact), authorities.lookupOrAppendAllocation(intent(token(11))).value()),
            () -> assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER)));
        assertArrayEquals(before, Files.readAllBytes(allocationFile(save)), "不沿用、不覆寫既有紀錄");
    }

    @Test void existingProjectionEntriesMustMatchTheSourceChamberAndOnlyAdvance() throws Exception {
        var save = save("projection-verify");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        var pending = pending(existingUniverse());
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending).status());
        byte[] afterPending = Files.readAllBytes(projectionFile(save));
        var moved = new ProjectionLedger.Entry(CHAMBER, existingUniverse(), DimensionRole.OVERWORLD, CONTROLLER.east(), Direction.NORTH, Status.COMPLETE);
        var turned = new ProjectionLedger.Entry(CHAMBER, existingUniverse(), DimensionRole.OVERWORLD, CONTROLLER, Direction.WEST, Status.PENDING);
        int reads = io.reads.size();
        assertAll(
            () -> assertEquals(WriteResult.Status.CORRUPT, authorities.writeProjection(moved).status()),
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), authorities.writeProjection(turned).sealedReason()),
            () -> assertEquals(WriteResult.Status.UNCHANGED, authorities.writeProjection(pending).status()),
            () -> assertThrows(IllegalArgumentException.class, () -> authorities.writeProjection(new ProjectionLedger.Entry(CHAMBER, existingUniverse(),
                    DimensionRole.NETHER, CONTROLLER, Direction.NORTH, Status.PENDING)), "本切片的投影只有 OVERWORLD"));
        assertEquals(reads, io.reads.size(), "被拒或沒有變更的寫入不碰檔案");
        assertArrayEquals(afterPending, Files.readAllBytes(projectionFile(save)));
        var complete = pending.withStatus(Status.COMPLETE);
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(complete).status());
        byte[] afterComplete = Files.readAllBytes(projectionFile(save));
        int readsAfterComplete = io.reads.size();
        var movedPending = new ProjectionLedger.Entry(CHAMBER, existingUniverse(), DimensionRole.OVERWORLD, CONTROLLER.up(), Direction.NORTH, Status.PENDING);
        assertEquals(WriteResult.Status.CORRUPT, authorities.writeProjection(movedPending).status(), "既有 COMPLETE 的 site 不符仍是 CORRUPT");
        assertEquals(WriteResult.Status.UNCHANGED, authorities.writeProjection(complete).status());
        assertEquals(readsAfterComplete, io.reads.size(), "CORRUPT／UNCHANGED 都不寫檔");
        assertArrayEquals(afterComplete, Files.readAllBytes(projectionFile(save)));
        assertEquals(List.of(complete), diskProjectionLedger(save).entries());
    }

    /** 既有 entry 已是 COMPLETE 時寫 PENDING 不是錯誤：site 驗證通過後回 ALREADY_COMPLETE 並帶回既有內容，F4b 改走修復路徑（§9.2）。 */
    @Test void completedProjectionAnswersAlreadyCompleteWithTheExistingEntry() throws Exception {
        var save = save("projection-already-complete");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        var pending = pending(existingUniverse());
        var complete = pending.withStatus(Status.COMPLETE);
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending).status());
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(complete).status());
        byte[] before = Files.readAllBytes(projectionFile(save));
        int reads = io.reads.size();
        var already = authorities.writeProjection(pending);
        assertAll(
            () -> assertEquals(WriteResult.Status.ALREADY_COMPLETE, already.status(), "既有 COMPLETE 不退回 PENDING"),
            () -> assertEquals(Optional.of(complete), already.entry(), "帶回既有的 COMPLETE"),
            () -> assertEquals(Optional.empty(), already.failure(), "ALREADY_COMPLETE 不是失敗"),
            () -> assertEquals(Optional.empty(), already.sealedReason()),
            () -> assertTrue(already.succeeded()));
        assertEquals(reads, io.reads.size(), "ALREADY_COMPLETE 不寫檔");
        assertArrayEquals(before, Files.readAllBytes(projectionFile(save)));
    }

    /** 沒有 PENDING 就直接寫 COMPLETE 是 caller 的錯誤（比照 role 檢查直接丟例外），不寫檔。 */
    @Test void completeWithoutPendingIsACallerError() throws Exception {
        var save = save("projection-complete-without-pending");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        byte[] before = Files.readAllBytes(projectionFile(save));
        int reads = io.reads.size();
        assertThrows(IllegalArgumentException.class, () -> authorities.writeProjection(pending(existingUniverse()).withStatus(Status.COMPLETE)),
                "沒有 PENDING 就直接寫 COMPLETE");
        assertEquals(reads, io.reads.size(), "不寫檔");
        assertArrayEquals(before, Files.readAllBytes(projectionFile(save)));
        assertEquals(Lookup.Status.ABSENT, authorities.projection(CHAMBER, existingUniverse()).status());
    }

    // ---- checked 寫入失敗的收斂（spec §6 roll-forward） ----

    @Test void allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse() throws Exception {
        var save = save("allocation-roll-forward");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        var first = UniverseAllocationLedger.Entry.of(intent(token(21)));
        var second = UniverseAllocationLedger.Entry.of(intent(token(22)));
        try (var logs = new CollapseLogCapture()) {
            io.failReadback(UniverseAllocationLedger.FILE_NAME);
            var failed = authorities.lookupOrAppendAllocation(intent(token(21)));
            assertEquals(Lookup.Status.WRITE_FAILED, failed.status());
            assertEquals(Optional.of(SealedReason.ALLOCATION_IO_FAILED), failed.sealedReason());
            assertEquals(List.of(first), diskAllocationLedger(save).entries(), "atomic move 已完成：追加已落地");
            assertTrue(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
            assertFalse(authorities.unconfirmed(Authority.PROJECTION_LEDGER), "收斂旗標以權威為單位");
            // 未確認期間不做新決策：同一個 token 也不回 FOUND，新的 token 不追加。
            assertUnconfirmed(authorities.lookupOrAppendAllocation(intent(token(21))), SealedReason.ALLOCATION_IO_FAILED);
            assertUnconfirmed(authorities.lookupOrAppendAllocation(intent(token(22))), SealedReason.ALLOCATION_IO_FAILED);
            assertEquals(List.of(first), diskAllocationLedger(save).entries(), "未確認期間不追加");
            assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending(existingUniverse())).status(), "另一個權威照常");
            // 退避期內不重寫；到期才 checked 重寫 current（帶著已落地的追加）。
            int reads = io.readsOf(UniverseAllocationLedger.FILE_NAME);
            now[0] = 1199; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
            assertEquals(reads, io.readsOf(UniverseAllocationLedger.FILE_NAME), "退避期內不嘗試");
            assertTrue(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
            now[0] = 1200; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
            assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "收斂寫入讀回確認");
            assertEquals(List.of(first), diskAllocationLedger(save).entries(), "收斂不移除已落地的追加");
            assertEquals(Lookup.Status.FOUND, authorities.lookupOrAppendAllocation(intent(token(21))).status());
            assertEquals(Lookup.Status.APPENDED, authorities.lookupOrAppendAllocation(intent(token(22))).status());
            assertEquals(List.of(first, second), diskAllocationLedger(save).entries());
            assertEquals(1, logs.messages(Level.WARN).size(), () -> "寫入未確認記一次 WARN：" + logs.messages(Level.WARN));
            assertEquals(1, logs.messages(Level.INFO).stream().filter(message -> message.contains("收斂完成")).count());
            // 收斂成功之後重置：同一種失敗再出現時，退避回到 200 ticks（不是延續成 400），而且去重鍵已清除，再記一次 WARN。
            var third = UniverseAllocationLedger.Entry.of(intent(token(31)));
            io.failReadback(UniverseAllocationLedger.FILE_NAME);
            now[0] = 1300;
            assertEquals(Lookup.Status.WRITE_FAILED, authorities.lookupOrAppendAllocation(intent(token(31))).status());
            int again = io.readsOf(UniverseAllocationLedger.FILE_NAME);
            now[0] = 1499; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
            assertEquals(again, io.readsOf(UniverseAllocationLedger.FILE_NAME), "重置後的第一次退避期內不嘗試");
            now[0] = 1500; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
            assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "成功之後退避重置為 200：1300＋200 到期就重寫");
            assertEquals(List.of(first, second, third), diskAllocationLedger(save).entries());
            assertEquals(2, logs.messages(Level.WARN).size(), () -> "成功之後同一原因再出現要再記一次 WARN：" + logs.messages(Level.WARN));
            assertEquals(2, logs.messages(Level.INFO).stream().filter(message -> message.contains("收斂完成")).count());
        }
        // 重啟後新內容照常 strict 讀取。
        var restarted = authorities(save);
        assertEquals(Outcome.LOADED, restarted.initialize(() -> journal(4)).allocation());
        assertEquals(Lookup.Status.FOUND, restarted.lookupOrAppendAllocation(intent(token(22))).status());
    }

    @Test void backoffIsPerAuthorityWhenFailuresAreStaggered() throws Exception {
        var save = save("staggered-backoff");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        io.failReadback(UniverseAllocationLedger.FILE_NAME);
        now[0] = 1000;
        assertEquals(Lookup.Status.WRITE_FAILED, authorities.lookupOrAppendAllocation(intent(token(32))).status());
        io.failReadback(ProjectionLedger.FILE_NAME);
        now[0] = 1100;
        assertEquals(WriteResult.Status.WRITE_FAILED, authorities.writeProjection(pending(existingUniverse())).status());
        int allocation = io.readsOf(UniverseAllocationLedger.FILE_NAME), projection = io.readsOf(ProjectionLedger.FILE_NAME);
        now[0] = 1199;
        authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER); authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
        assertEquals(List.of(allocation, projection), List.of(io.readsOf(UniverseAllocationLedger.FILE_NAME), io.readsOf(ProjectionLedger.FILE_NAME)),
                "兩個權威都還在各自的退避期內");
        // allocation 的退避只由自己的失敗（1000）決定：projection 在 1100 的失敗不得把它延長。
        now[0] = 1200;
        authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER); authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
        assertAll(
            () -> assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "allocation 在 1000＋200 到期就重寫"),
            () -> assertTrue(authorities.unconfirmed(Authority.PROJECTION_LEDGER), "projection 要到 1100＋200"),
            () -> assertEquals(projection, io.readsOf(ProjectionLedger.FILE_NAME), "projection 在 1200 不嘗試"));
        now[0] = 1299; authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
        assertTrue(authorities.unconfirmed(Authority.PROJECTION_LEDGER));
        now[0] = 1300; authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
        assertFalse(authorities.unconfirmed(Authority.PROJECTION_LEDGER), "projection 的退避也只由自己的失敗決定");
        assertEquals(List.of(UniverseAllocationLedger.Entry.of(intent(token(32)))), diskAllocationLedger(save).entries());
        assertEquals(List.of(pending(existingUniverse())), diskProjectionLedger(save).entries());
    }

    // ---- ledger 已滿（Q2：權威層的預先檢查，避免 ledger 的 IllegalArgumentException 外洩） ----

    @Test void fullAllocationLedgerRejectsNewAppendsWithoutWriting() throws Exception {
        var save = save("allocation-full");
        int limit = UniverseAllocationLedger.MAX_ENTRIES;
        var rows = new NbtList();
        for (int index = 0; index < limit; index++) {
            rows.add(((NbtList) UniverseAllocationLedger.empty().append(UniverseAllocationLedgerTest.entry(index)).toNbt().get("Entries")).getCompound(0).copy());
        }
        var full = new NbtCompound(); full.putInt("SchemaVersion", 1); full.put("Entries", rows);
        Files.createDirectories(allocationFile(save).getParent());
        NbtIo.writeCompressed(full, allocationFile(save));
        NbtIo.writeCompressed(ProjectionLedger.empty().toNbt(), projectionFile(save));
        var authorities = authorities(save);
        var result = authorities.initialize(() -> journal(4));
        assertEquals(List.of(Outcome.LOADED, Outcome.LOADED), List.of(result.allocation(), result.projection()), "寫滿的 ledger 照常讀得回來");
        byte[] before = Files.readAllBytes(allocationFile(save));
        int reads = io.reads.size();
        assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(limit))));
        assertAll(
            () -> assertArrayEquals(before, Files.readAllBytes(allocationFile(save)), "已滿時不寫入"),
            () -> assertEquals(reads, io.reads.size(), "已滿時不嘗試寫入"),
            () -> assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER)),
            () -> assertNoTemporaryFiles(save),
            () -> assertEquals(Lookup.Status.FOUND, authorities.lookupOrAppendAllocation(intent(token(5))).status(), "既有的 entry 照常可用"));
    }

    @Test void fullProjectionLedgerRejectsNewKeysWithoutWriting() throws Exception {
        var save = save("projection-full");
        int limit = ProjectionLedger.MAX_ENTRIES;
        var rows = new NbtList();
        for (int index = 0; index < limit; index++) {
            rows.add(((NbtList) ProjectionLedger.empty().with(ProjectionLedgerTest.entry(index, Direction.SOUTH, Status.PENDING)).toNbt().get("Entries"))
                    .getCompound(0).copy());
        }
        var full = new NbtCompound(); full.putInt("SchemaVersion", 1); full.put("Entries", rows);
        Files.createDirectories(projectionFile(save).getParent());
        NbtIo.writeCompressed(full, projectionFile(save));
        NbtIo.writeCompressed(UniverseAllocationLedger.empty().toNbt(), allocationFile(save));
        var authorities = authorities(save);
        var result = authorities.initialize(() -> journal(4));
        assertEquals(List.of(Outcome.LOADED, Outcome.LOADED), List.of(result.allocation(), result.projection()), "寫滿的 ledger 照常讀得回來");
        byte[] before = Files.readAllBytes(projectionFile(save));
        int reads = io.reads.size();
        var rejected = authorities.writeProjection(ProjectionLedgerTest.entry(limit, Direction.SOUTH, Status.PENDING));
        assertAll(
            () -> assertEquals(WriteResult.Status.CORRUPT, rejected.status()),
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), rejected.sealedReason()),
            () -> assertArrayEquals(before, Files.readAllBytes(projectionFile(save)), "已滿時不寫入"),
            () -> assertEquals(reads, io.reads.size(), "已滿時不嘗試寫入"),
            () -> assertFalse(authorities.unconfirmed(Authority.PROJECTION_LEDGER)),
            () -> assertNoTemporaryFiles(save));
        // 上限只限制新的 key：既有 key 的 PENDING→COMPLETE 是原位取代，照常寫入。
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(ProjectionLedgerTest.entry(7, Direction.SOUTH, Status.COMPLETE)).status());
    }

    // ---- verify 的訊息不得帶隨機暫存檔路徑（Q6：WARN 去重） ----

    @Test void temporaryReadbackMismatchWarnsOncePerCause() throws Exception {
        var save = save("stale-temporary");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        io.staleTemporary(UniverseAllocationLedger.FILE_NAME, compressed(UniverseAllocationLedger.empty().toNbt()));
        try (var logs = new CollapseLogCapture()) {
            assertEquals(Lookup.Status.WRITE_FAILED, authorities.lookupOrAppendAllocation(intent(token(33))).status());
            for (int due : new int[] {1200, 1600}) {
                int reads = io.readsOf(UniverseAllocationLedger.FILE_NAME);
                now[0] = due; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
                assertTrue(io.readsOf(UniverseAllocationLedger.FILE_NAME) > reads, "第 " + due + " tick 必須實際重試");
                assertTrue(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
            }
            var warnings = logs.messages(Level.WARN);
            assertEquals(1, warnings.size(), () -> "暫存檔讀回不符是同一個原因，只記一次 WARN：" + warnings);
            assertFalse(warnings.getFirst().contains(".tmp"), () -> "WARN 不得帶隨機的暫存檔路徑：" + warnings.getFirst());
        }
        assertTrue(diskAllocationLedger(save).entries().isEmpty(), "暫存檔驗證失敗：正式檔不變");
        io.clear();
        now[0] = 2400; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
        assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
        assertEquals(List.of(UniverseAllocationLedger.Entry.of(intent(token(33)))), diskAllocationLedger(save).entries());
    }

    @Test void allocationWriteFailureBeforeTheMoveIsRolledForwardToo() throws Exception {
        var save = save("allocation-before-move");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        io.failTemporary(UniverseAllocationLedger.FILE_NAME);
        assertEquals(Lookup.Status.WRITE_FAILED, authorities.lookupOrAppendAllocation(intent(token(23))).status());
        assertTrue(diskAllocationLedger(save).entries().isEmpty(), "move 之前失敗：磁碟仍是舊內容");
        assertNoTemporaryFiles(save);
        // 舊內容重新讀取也合法：重啟後同一個 token 推導出同一個 UniverseId。
        var restarted = authorities(save("allocation-before-move-copy"));
        copyData(save, save("allocation-before-move-copy"));
        assertEquals(Outcome.LOADED, restarted.initialize(() -> journal(4)).allocation());
        assertEquals(Lookup.Status.APPENDED, restarted.lookupOrAppendAllocation(intent(token(23))).status());
        // 本 instance：current 保留這次追加，收斂把它寫出。
        assertUnconfirmed(authorities.lookupOrAppendAllocation(intent(token(24))), SealedReason.ALLOCATION_IO_FAILED);
        now[0] = 1200; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
        assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
        assertEquals(List.of(UniverseAllocationLedger.Entry.of(intent(token(23)))), diskAllocationLedger(save).entries());
    }

    @Test void convergenceFailuresStayUnconfirmedWithAPerAuthorityDoublingBackoff() throws Exception {
        var save = save("allocation-backoff");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        try (var logs = new CollapseLogCapture()) {
            io.failReadbackAlways(UniverseAllocationLedger.FILE_NAME);
            assertEquals(Lookup.Status.WRITE_FAILED, authorities.lookupOrAppendAllocation(intent(token(25))).status());
            long due = 1000;
            for (int delay : new int[] {200, 400, 800, 1600, 3200, 6400, 12000, 12000}) {
                due += delay;
                int reads = io.readsOf(UniverseAllocationLedger.FILE_NAME);
                now[0] = (int) due - 1; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
                assertEquals(reads, io.readsOf(UniverseAllocationLedger.FILE_NAME), "退避 " + delay + " 期內不嘗試");
                now[0] = (int) due; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
                assertTrue(io.readsOf(UniverseAllocationLedger.FILE_NAME) > reads, "退避 " + delay + " 到期才嘗試");
                assertTrue(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "收斂寫入本身失敗時維持未確認");
                assertUnconfirmed(authorities.lookupOrAppendAllocation(intent(token(26))), SealedReason.ALLOCATION_IO_FAILED);
            }
            assertEquals(1, logs.messages(Level.WARN).size(), "同一原因只記一次 WARN");
        }
        io.clear();
        now[0] += 12000; authorities.convergeIfNeeded(Authority.ALLOCATION_LEDGER);
        assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER));
        assertEquals(List.of(UniverseAllocationLedger.Entry.of(intent(token(25)))), diskAllocationLedger(save).entries());
    }

    @Test void unconfirmedProjectionPendingBlocksDecisionsUntilConverged() throws Exception {
        var save = save("projection-roll-forward");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        var pending = pending(existingUniverse());
        io.failReadback(ProjectionLedger.FILE_NAME);
        var failed = authorities.writeProjection(pending);
        assertEquals(WriteResult.Status.WRITE_FAILED, failed.status());
        assertEquals(Optional.of(SealedReason.PROJECTION_FAILED), failed.sealedReason());
        assertEquals(List.of(pending), diskProjectionLedger(save).entries(), "atomic move 已完成：PENDING 已落地");
        assertTrue(authorities.unconfirmed(Authority.PROJECTION_LEDGER));
        // 投影方塊只在 PENDING 確認之後才寫：未確認期間查詢也不回 FOUND。
        assertUnconfirmed(authorities.projection(CHAMBER, existingUniverse()), SealedReason.PROJECTION_FAILED);
        assertEquals(WriteResult.Status.UNCONFIRMED, authorities.writeProjection(pending.withStatus(Status.COMPLETE)).status());
        assertEquals(WriteResult.Status.UNCONFIRMED, authorities.writeProjection(pending(UniverseId.of(UUID.randomUUID()))).status());
        assertEquals(Lookup.Status.APPENDED, authorities.lookupOrAppendAllocation(intent(token(27))).status(), "另一個權威照常");
        now[0] = 1200; authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
        assertFalse(authorities.unconfirmed(Authority.PROJECTION_LEDGER));
        assertEquals(Optional.of(pending), authorities.projection(CHAMBER, existingUniverse()).value());
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending.withStatus(Status.COMPLETE)).status());
        var restarted = authorities(save);
        assertEquals(Outcome.LOADED, restarted.initialize(() -> journal(4)).projection());
        assertEquals(Optional.of(Status.COMPLETE), restarted.projection(CHAMBER, existingUniverse()).value().map(ProjectionLedger.Entry::status));
    }

    @Test void aLedgerThatDisappearsAtRuntimeIsLostAndNotRecreated() throws Exception {
        var save = save("runtime-lost");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        try (var logs = new CollapseLogCapture()) {
            Files.delete(allocationFile(save));
            assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(28))));
            assertCorrupt(authorities.lookupOrAppendAllocation(intent(token(29))));
            assertFalse(Files.exists(allocationFile(save)), "不重建");
            assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(pending(existingUniverse())).status(), "另一個權威照常");
            // 未確認期間檔案消失：收斂時發現，同樣視為遺失。
            io.failReadback(ProjectionLedger.FILE_NAME);
            assertEquals(WriteResult.Status.WRITE_FAILED, authorities.writeProjection(pending(existingUniverse()).withStatus(Status.COMPLETE)).status());
            Files.delete(projectionFile(save));
            now[0] = 1200; authorities.convergeIfNeeded(Authority.PROJECTION_LEDGER);
            assertCorrupt(authorities.projection(CHAMBER, existingUniverse()));
            assertFalse(Files.exists(projectionFile(save)));
            assertEquals(2, logs.messages(Level.ERROR).size(), () -> "每個權威的遺失記一次 ERROR：" + logs.messages(Level.ERROR));
        }
    }

    @Test void backoffDoublesFrom200To12000AndResets() {
        var backoff = new CollapseBackoff();
        assertTrue(backoff.ready(0), "新的 instance 沒有退避（開服後第一次觸發不受限制）");
        var delays = new ArrayList<Integer>();
        long now = 50;
        for (int attempt = 0; attempt < 9; attempt++) {
            backoff.failed(now);
            delays.add(backoff.delayTicks());
            assertEquals(now + backoff.delayTicks(), backoff.retryAtTick());
            assertFalse(backoff.ready(backoff.retryAtTick() - 1));
            assertTrue(backoff.ready(backoff.retryAtTick()));
            now = backoff.retryAtTick();
        }
        assertEquals(List.of(200, 400, 800, 1600, 3200, 6400, 12000, 12000, 12000), delays);
        backoff.reset();
        assertTrue(backoff.ready(now));
        backoff.failed(now);
        assertEquals(200, backoff.delayTicks(), "成功之後重新從 200 開始");
    }

    // ---- discovery（spec §6.4、§6 收斂；F2c） ----

    @Test void discoveryAppendWritesTheNextRecordAndIsIdempotent() throws Exception {
        var save = save("discovery-append");
        writeDiscovery(save, M4_RECORD);
        var authorities = initialized(save);
        var id = UniverseAllocationLedger.deriveUniverseId(token(40));
        var appended = authorities.appendDiscovery(id, 1234, SELECTED_BY);
        var expected = new UniverseDiscoveryRecord(id, 1, 1234, Optional.of(SELECTED_BY), true);
        assertAll(
            () -> assertEquals(Lookup.Status.APPENDED, appended.status(), () -> appended.toString()),
            () -> assertEquals(Optional.of(expected), appended.value(), "ordinal＝watermark＋1、當下 game time、firstObserver＝SelectedBy、eligible"),
            () -> assertEquals(List.of(M4_RECORD, expected), UniverseDiscoveryState.load(save).records(), "M4 的讀取點讀到這筆 record"),
            () -> assertNoTemporaryFiles(save));
        byte[] written = Files.readAllBytes(discoveryFile(save));
        var again = authorities.appendDiscovery(id, 9999, SELECTED_BY);
        assertAll(
            () -> assertEquals(Lookup.Status.FOUND, again.status(), "以 UniverseId 判斷 idempotency"),
            () -> assertEquals(Optional.of(expected), again.value()),
            () -> assertArrayEquals(written, Files.readAllBytes(discoveryFile(save)), "已存在時不改寫"),
            () -> assertEquals(Optional.of(expected), authorities.discovery(id).value()),
            () -> assertEquals(Lookup.Status.ABSENT, authorities.discovery(UniverseAllocationLedger.deriveUniverseId(token(41))).status()),
            () -> assertFalse(authorities.unconfirmed(Authority.DISCOVERY)));
    }

    @Test void existingDiscoveryRecordsMustBeEligibleAndFirstObservedByThisSession() throws Exception {
        var id = UniverseAllocationLedger.deriveUniverseId(token(42));
        var other = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        var cases = new ArrayList<Executable>();
        for (var mismatch : List.of(
                new UniverseDiscoveryRecord(id, 1, 50, Optional.of(SELECTED_BY), false),
                new UniverseDiscoveryRecord(id, 1, 50, Optional.of(other), true),
                new UniverseDiscoveryRecord(id, 1, 50, Optional.empty(), true))) {
            var name = "discovery-mismatch-" + cases.size();
            cases.add(() -> {
                var save = save(name);
                writeDiscovery(save, M4_RECORD, mismatch);
                byte[] before = Files.readAllBytes(discoveryFile(save));
                var authorities = initialized(save);
                var result = authorities.appendDiscovery(id, 60, SELECTED_BY);
                assertEquals(Lookup.Status.CORRUPT, result.status(), mismatch.toString());
                assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), result.sealedReason(), mismatch.toString());
                assertArrayEquals(before, Files.readAllBytes(discoveryFile(save)), "不沿用、不改寫既有紀錄");
                assertFalse(authorities.unconfirmed(Authority.DISCOVERY));
            });
        }
        assertAll(cases);
    }

    /** spec §6 roll-forward、§12 W12：discovery 的 atomic move 已完成、讀回失敗；已落地的 record 不移除，M4 讀到的候選池不倒退。 */
    @Test void discoveryReadbackFailureKeepsTheLandedRecordAndM4PoolsNeverRegress() throws Exception {
        var save = save("discovery-roll-forward");
        writeDiscovery(save, M4_RECORD);
        var authorities = initialized(save);
        var before = UniverseDiscoveryState.load(save);
        long frozen = before.watermark();
        var poolBefore = before.eligibleSnapshot(frozen, VANILLA);
        var first = UniverseAllocationLedger.deriveUniverseId(token(43));
        var second = UniverseAllocationLedger.deriveUniverseId(token(44));
        var landed = new UniverseDiscoveryRecord(first, 1, 100, Optional.of(SELECTED_BY), true);
        try (var logs = new CollapseLogCapture()) {
            // 第一次讀正式檔是追加前的讀取，第二次才是 atomic move 之後的讀回。
            io.failReadbackAfter(UniverseDiscoveryState.FILE_NAME, 1);
            var failed = authorities.appendDiscovery(first, 100, SELECTED_BY);
            var m4View = UniverseDiscoveryState.load(save);
            assertAll(
                () -> assertEquals(Lookup.Status.WRITE_FAILED, failed.status(), () -> failed.toString()),
                () -> assertEquals(Optional.of(SealedReason.ALLOCATION_IO_FAILED), failed.sealedReason()),
                () -> assertTrue(authorities.unconfirmed(Authority.DISCOVERY)),
                () -> assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "收斂旗標以權威為單位"),
                () -> assertEquals(List.of(M4_RECORD, landed), m4View.records(), "atomic move 已完成：record 已落地"),
                () -> assertEquals(poolBefore, m4View.eligibleSnapshot(frozen, VANILLA), "已凍結 watermark 的候選池不變"),
                () -> assertTrue(m4View.eligibleSnapshot(m4View.watermark(), VANILLA).containsAll(poolBefore), "新的候選池不倒退"),
                () -> assertTrue(m4View.eligibleSnapshot(m4View.watermark(), VANILLA).contains(landed), "新的候選池含已落地的 record"));
            // 未確認期間不做新決策：同一筆也不回 FOUND，新的不追加，也不改檔。NEW 的追加依 §6.4 帶原因（進 SEALED）；
            // 唯讀查詢（EXISTING 重驗）依 §6 收斂共通第 2 點只等待收斂，回不帶原因的 WAIT。
            byte[] landedBytes = Files.readAllBytes(discoveryFile(save));
            assertUnconfirmed(authorities.appendDiscovery(first, 100, SELECTED_BY), SealedReason.ALLOCATION_IO_FAILED);
            assertUnconfirmed(authorities.appendDiscovery(second, 101, SELECTED_BY), SealedReason.ALLOCATION_IO_FAILED);
            assertWait(authorities.discovery(first));
            assertArrayEquals(landedBytes, Files.readAllBytes(discoveryFile(save)));
            // 退避期內不嘗試；到期以 checked 方式重寫 current（帶著已落地的 record）。
            int reads = io.readsOf(UniverseDiscoveryState.FILE_NAME);
            now[0] = 1199; authorities.convergeIfNeeded(Authority.DISCOVERY);
            assertEquals(reads, io.readsOf(UniverseDiscoveryState.FILE_NAME), "退避期內不嘗試");
            assertTrue(authorities.unconfirmed(Authority.DISCOVERY));
            now[0] = 1200; authorities.convergeIfNeeded(Authority.DISCOVERY);
            assertFalse(authorities.unconfirmed(Authority.DISCOVERY), "收斂寫入讀回確認");
            assertEquals(List.of(M4_RECORD, landed), UniverseDiscoveryState.load(save).records(), "收斂不移除已落地的 record");
            var retried = authorities.appendDiscovery(first, 999, SELECTED_BY);
            assertEquals(Lookup.Status.FOUND, retried.status(), "重試時 idempotent（ordinal 與 time 可以不同）");
            assertEquals(Optional.of(landed), retried.value());
            var next = authorities.appendDiscovery(second, 101, SELECTED_BY);
            assertEquals(Optional.of(new UniverseDiscoveryRecord(second, 2, 101, Optional.of(SELECTED_BY), true)), next.value());
            assertEquals(List.of(M4_RECORD, landed, next.value().orElseThrow()), UniverseDiscoveryState.load(save).records());
            assertEquals(1, logs.messages(Level.WARN).size(), () -> "寫入未確認記一次 WARN：" + logs.messages(Level.WARN));
            assertEquals(1, logs.messages(Level.INFO).stream().filter(message -> message.contains("DISCOVERY 收斂完成")).count());
        }
    }

    @Test void discoveryWriteFailureBeforeTheMoveIsRolledForwardToo() throws Exception {
        var save = save("discovery-before-move");
        writeDiscovery(save, M4_RECORD);
        var authorities = initialized(save);
        var id = UniverseAllocationLedger.deriveUniverseId(token(45));
        io.failTemporary(UniverseDiscoveryState.FILE_NAME);
        assertEquals(Lookup.Status.WRITE_FAILED, authorities.appendDiscovery(id, 7, SELECTED_BY).status());
        assertEquals(List.of(M4_RECORD), UniverseDiscoveryState.load(save).records(), "move 之前失敗：磁碟仍是舊內容（候選池沒有變）");
        assertNoTemporaryFiles(save);
        assertTrue(authorities.unconfirmed(Authority.DISCOVERY), "current 保留這筆 append");
        now[0] = 1200; authorities.convergeIfNeeded(Authority.DISCOVERY);
        assertFalse(authorities.unconfirmed(Authority.DISCOVERY));
        assertEquals(List.of(M4_RECORD, new UniverseDiscoveryRecord(id, 1, 7, Optional.of(SELECTED_BY), true)),
                UniverseDiscoveryState.load(save).records(), "收斂把 current 寫出");
    }

    /** spec §6 讀取失敗的分類：遺失與內容損壞為 AUTHORITY_CORRUPT（遺失不重建）；讀檔的 IO 例外為 AUTHORITY_UNREADABLE。 */
    @Test void discoveryReadFailuresAreClassified() throws Exception {
        var id = UniverseAllocationLedger.deriveUniverseId(token(46));
        var missing = save("discovery-missing");
        try (var logs = new CollapseLogCapture()) {
            var authorities = initialized(missing);
            assertCorrupt(authorities.appendDiscovery(id, 1, SELECTED_BY));
            assertCorrupt(authorities.discovery(id));
            now[0] = 5000; authorities.convergeIfNeeded(Authority.DISCOVERY);
            assertFalse(Files.exists(discoveryFile(missing)), "遺失：不建檔、不補寫");
            assertEquals(1, logs.messages(Level.ERROR).size(), () -> "遺失只記一次 ERROR：" + logs.messages(Level.ERROR));
        }
        var garbage = save("discovery-garbage");
        write(discoveryFile(garbage), new byte[] {31, -117, 8, 0, 1, 2});
        var corrupt = initialized(garbage);
        assertCorrupt(corrupt.appendDiscovery(id, 1, SELECTED_BY));
        assertArrayEquals(new byte[] {31, -117, 8, 0, 1, 2}, Files.readAllBytes(discoveryFile(garbage)), "損壞的檔不被覆寫");
        var unreadable = save("discovery-io");
        writeDiscovery(unreadable, M4_RECORD);
        byte[] before = Files.readAllBytes(discoveryFile(unreadable));
        var authorities = initialized(unreadable);
        io.failReadback(UniverseDiscoveryState.FILE_NAME);
        var failed = authorities.appendDiscovery(id, 1, SELECTED_BY);
        assertEquals(Lookup.Status.UNREADABLE, failed.status());
        assertEquals(Optional.of(SealedReason.AUTHORITY_UNREADABLE), failed.sealedReason());
        assertArrayEquals(before, Files.readAllBytes(discoveryFile(unreadable)));
        assertFalse(authorities.unconfirmed(Authority.DISCOVERY), "讀取失敗不是未確認的寫入");
        assertEquals(Lookup.Status.APPENDED, authorities.appendDiscovery(id, 1, SELECTED_BY).status(), "IO 例外不是遺失，之後照常");
    }

    // ---- catalog（spec §6.2、§6 收斂；F2c） ----

    @Test void catalogAllocationUsesTheNextFlushedOrdinalAndVerifiesExistingRecords() throws Exception {
        var save = save("catalog-allocate");
        var authorities = initialized(save);
        var catalog = catalog(save);
        var first = UniverseAllocationLedger.deriveUniverseId(token(50));
        var second = UniverseAllocationLedger.deriveUniverseId(token(51));
        var allocated = authorities.allocateOrVerifyCatalog(first, EAGER_ONLY);
        assertEquals(Lookup.Status.APPENDED, allocated.status(), () -> allocated.toString());
        var firstRecord = allocated.value().orElseThrow();
        assertAll(
            () -> assertEquals(0, firstRecord.definition().allocationOrdinal(), "catalog 為空時 ordinal 為 0"),
            () -> assertEquals(DesiredAvailability.EAGER_ENABLED, firstRecord.desiredAvailability()),
            () -> assertEquals(Map.of(first, firstRecord), catalog.state.flushedRecords(), "checked 寫出之後才算配置完成"),
            () -> assertFalse(catalog.state.isDirty()),
            () -> assertEquals(Map.of(first, firstRecord), catalog.disk().records()),
            () -> assertFalse(authorities.unconfirmed(Authority.CATALOG)));
        int flushes = catalog.flushes;
        var again = authorities.allocateOrVerifyCatalog(first, EAGER_ONLY);
        assertEquals(Lookup.Status.FOUND, again.status(), "已有時不重新配置");
        assertEquals(Optional.of(firstRecord), again.value());
        assertEquals(flushes, catalog.flushes, "驗證不寫出");
        // §8.3 已把 first 改為 DISABLED；下一筆的 ordinal 仍是 flushed 的最大 ordinal＋1。
        catalog.state.changeAvailability(first, DesiredAvailability.DISABLED);
        catalog.state.save(catalog.path);
        var secondRecord = authorities.allocateOrVerifyCatalog(second, EAGER_ONLY).value().orElseThrow();
        assertEquals(1, secondRecord.definition().allocationOrdinal());
        // availability 不符：只有 §8.3 的重試路徑接受 DISABLED；不改寫、不寫出。
        byte[] bytes = Files.readAllBytes(catalog.path);
        flushes = catalog.flushes;
        assertCorrupt(authorities.allocateOrVerifyCatalog(first, EAGER_ONLY));
        var retry = authorities.allocateOrVerifyCatalog(first, MATERIALIZE_RETRY);
        assertEquals(Lookup.Status.FOUND, retry.status());
        var disabled = retry.value().orElseThrow();
        assertEquals(DesiredAvailability.DISABLED, disabled.desiredAvailability());
        assertEquals(flushes, catalog.flushes);
        assertArrayEquals(bytes, Files.readAllBytes(catalog.path));
        assertAll(
            () -> assertFalse(CollapseAuthorities.catalogRecordMatches(disabled, first, EAGER_ONLY)),
            () -> assertTrue(CollapseAuthorities.catalogRecordMatches(disabled, first, MATERIALIZE_RETRY)),
            () -> assertTrue(CollapseAuthorities.catalogRecordMatches(secondRecord, second, EAGER_ONLY)),
            () -> assertTrue(CollapseAuthorities.catalogRecordMatches(secondRecord, second, MATERIALIZE_RETRY), "MATERIALIZE_RETRY 也接受 EAGER_ENABLED"),
            () -> assertFalse(CollapseAuthorities.catalogRecordMatches(secondRecord, first, EAGER_ONLY), "別的 UniverseId 的 record"));
    }

    /** spec §6.2：ordinal 是 flushed 的最大 ordinal＋1，不是筆數（有空號、最大的那筆是 DISABLED 時也一樣）。 */
    @Test void catalogOrdinalIsTheFlushedMaximumPlusOneNotTheRecordCount() throws Exception {
        var save = save("catalog-ordinal-gap");
        var catalog = catalog(save);
        var low = UniverseAllocationLedger.deriveUniverseId(token(60));
        var high = UniverseAllocationLedger.deriveUniverseId(token(61));
        catalog.state.allocateOverworld(low.value(), 0);
        catalog.state.allocateOverworld(high.value(), 7);
        catalog.state.changeAvailability(high, DesiredAvailability.DISABLED);
        catalog.state.save(catalog.path);
        var authorities = initialized(save);
        var next = authorities.allocateOrVerifyCatalog(UniverseAllocationLedger.deriveUniverseId(token(62)), EAGER_ONLY);
        assertEquals(Lookup.Status.APPENDED, next.status(), () -> next.toString());
        assertEquals(8, next.value().orElseThrow().definition().allocationOrdinal(), "flushed 為 {0, 7}（7 是 DISABLED）：下一筆是 8，不是筆數 2");
    }

    /** profile、seed policy、storage policy 不符的 record 在 M3 strict decode 就讓 catalog 不健康；M5 不在不健康的 catalog 上做任何決策。 */
    @Test void catalogRecordsWithAnotherProfileNeverReachAllocation() throws Exception {
        var id = UniverseAllocationLedger.deriveUniverseId(token(52));
        var cases = new ArrayList<Executable>();
        for (var field : List.of("GeneratorProfile", "SeedPolicy", "StoragePolicyVersion")) {
            cases.add(() -> {
                var save = save("catalog-profile-" + field);
                var source = new UniverseRegistryState();
                source.allocateOverworld(id.value(), 0);
                var data = source.writeNbt(new NbtCompound());
                var world = data.getList("Records", 10).getCompound(0).getList("Worlds", 10).getCompound(0);
                if (field.equals("StoragePolicyVersion")) world.putInt(field, 2); else world.putString(field, "VANILLA_NETHER_V1");
                var catalog = catalog(save);
                catalog.state = UniverseRegistryState.fromNbt(data);
                assertThrows(IllegalStateException.class, catalog.state::requireHealthy, field + "：M3 strict decode 拒絕");
                var authorities = initialized(save);
                assertCorrupt(authorities.allocateOrVerifyCatalog(id, EAGER_ONLY));
                assertCorrupt(authorities.allocateOrVerifyCatalog(id, MATERIALIZE_RETRY));
                assertTrue(authorities.unconfirmed(Authority.CATALOG), field + "：不健康的 catalog 不能拿來做決策");
                now[0] = 9000; authorities.convergeIfNeeded(Authority.CATALOG);
                assertEquals(0, catalog.flushes, field + "：不寫出");
                assertFalse(Files.exists(catalog.path));
            });
        }
        assertAll(cases);
    }

    /** spec §6.2、§12 W12：catalog 配置的 atomic move 已完成、讀回失敗；保留 dirty、確認前未確認、下一次寫出帶著它，只會有同一個 UniverseId。 */
    @Test void catalogReadbackFailureKeepsTheAllocationDirtyAndUnconfirmedUntilConverged() throws Exception {
        var save = save("catalog-roll-forward");
        var authorities = initialized(save);
        var catalog = catalog(save);
        var first = UniverseAllocationLedger.deriveUniverseId(token(53));
        var second = UniverseAllocationLedger.deriveUniverseId(token(54));
        try (var logs = new CollapseLogCapture()) {
            catalog.failAfterMove(1);
            var failed = authorities.allocateOrVerifyCatalog(first, EAGER_ONLY);
            assertEquals(Lookup.Status.WRITE_FAILED, failed.status(), () -> failed.toString());
            assertEquals(Optional.of(SealedReason.ALLOCATION_IO_FAILED), failed.sealedReason());
            var record = catalog.state.find(first).orElseThrow();
            assertAll(
                () -> assertTrue(authorities.unconfirmed(Authority.CATALOG), "確認之前 unconfirmed 為真"),
                () -> assertTrue(catalog.state.isDirty(), "保留 dirty"),
                () -> assertEquals(Map.of(first, record), catalog.state.records(), "current 保留這筆配置"),
                () -> assertEquals(Map.of(), catalog.state.flushedRecords(), "flushed 不前進（確認之前不得物化）"),
                () -> assertEquals(Map.of(first, record), catalog.disk().records(), "atomic move 已完成：配置已落地"),
                () -> assertFalse(authorities.unconfirmed(Authority.ALLOCATION_LEDGER), "收斂旗標以權威為單位"));
            // 未確認期間不疊加新的配置：同一筆也不回 FOUND；依 §6.2 是 WAIT（不 SEALED），不是帶原因的失敗。
            int flushes = catalog.flushes;
            assertWait(authorities.allocateOrVerifyCatalog(second, EAGER_ONLY));
            assertWait(authorities.allocateOrVerifyCatalog(first, EAGER_ONLY));
            assertEquals(Map.of(first, record), catalog.state.records(), "沒有疊加第二筆");
            assertEquals(flushes, catalog.flushes);
            // 退避期內不寫出；到期 checked 寫出 current（帶著這筆配置）。
            now[0] = 1199; authorities.convergeIfNeeded(Authority.CATALOG);
            assertEquals(flushes, catalog.flushes, "退避期內不嘗試");
            assertTrue(authorities.unconfirmed(Authority.CATALOG));
            now[0] = 1200; authorities.convergeIfNeeded(Authority.CATALOG);
            assertEquals(flushes + 1, catalog.flushes);
            assertFalse(authorities.unconfirmed(Authority.CATALOG));
            assertEquals(Map.of(first, record), catalog.state.flushedRecords(), "下一次寫出帶著它");
            assertEquals(Lookup.Status.FOUND, authorities.allocateOrVerifyCatalog(first, EAGER_ONLY).status(), "重試時同一個 UniverseId，不重新配置");
            var next = authorities.allocateOrVerifyCatalog(second, EAGER_ONLY);
            assertEquals(Lookup.Status.APPENDED, next.status());
            assertEquals(1, next.value().orElseThrow().definition().allocationOrdinal());
            assertEquals(1, logs.messages(Level.WARN).size(), () -> "寫出未確認記一次 WARN：" + logs.messages(Level.WARN));
            assertEquals(1, logs.messages(Level.INFO).stream().filter(message -> message.contains("CATALOG 收斂完成")).count());
        }
        assertEquals(Set.of(first, second), catalog.disk().records().keySet(), "重新讀盤：catalog 只多這兩筆");
    }

    @Test void nativeAutosaveConfirmsTheCatalogAndARestoredButDirtyCatalogStaysUnconfirmed() throws Exception {
        var save = save("catalog-autosave");
        var authorities = initialized(save);
        var catalog = catalog(save);
        var first = UniverseAllocationLedger.deriveUniverseId(token(55));
        try (var logs = new CollapseLogCapture()) {
            catalog.failBeforeMove(1);
            assertEquals(Lookup.Status.WRITE_FAILED, authorities.allocateOrVerifyCatalog(first, EAGER_ONLY).status());
            assertFalse(Files.exists(catalog.path), "move 之前失敗：磁碟上還沒有 catalog");
            assertTrue(authorities.unconfirmed(Authority.CATALOG));
            // 原生 autosave 寫出 current：不需要 convergeIfNeeded 就不再未確認。
            catalog.state.save(catalog.path.toFile(), null);
            assertFalse(authorities.unconfirmed(Authority.CATALOG), "原生 autosave 帶著它寫出");
            int flushes = catalog.flushes;
            authorities.convergeIfNeeded(Authority.CATALOG);
            assertEquals(flushes, catalog.flushes, "已確認時不再寫出");
            assertEquals(Lookup.Status.FOUND, authorities.allocateOrVerifyCatalog(first, EAGER_ONLY).status());
            assertEquals(1, logs.messages(Level.INFO).stream().filter(message -> message.contains("CATALOG 收斂完成")).count());
        }
        // spec §6 的 restore 類（§8.3 改回 EAGER_ENABLED 的寫入失敗時 F4a 把 current 改回）：current 等於 flushed，但仍 dirty → 仍未確認。
        catalog.state.changeAvailability(first, DesiredAvailability.DISABLED);
        catalog.state.changeAvailability(first, DesiredAvailability.EAGER_ENABLED);
        assertEquals(catalog.state.flushedRecords(), catalog.state.records());
        assertTrue(catalog.state.isDirty());
        assertTrue(authorities.unconfirmed(Authority.CATALOG), "current 等於 flushed 但仍 dirty：下一次寫出之前仍未確認");
        assertWait(authorities.allocateOrVerifyCatalog(UniverseAllocationLedger.deriveUniverseId(token(56)), EAGER_ONLY));
        authorities.convergeIfNeeded(Authority.CATALOG);
        assertFalse(authorities.unconfirmed(Authority.CATALOG));
        assertFalse(catalog.state.isDirty());
    }

    @Test void orphanStorageIsCorruptAndIsNeitherAdoptedNorDeleted() throws Exception {
        var save = save("catalog-orphan");
        var orphan = UniverseAllocationLedger.deriveUniverseId(token(57));
        var region = save.resolve("dimensions/quantumchamber/universe").resolve(orphan.value().toString()).resolve("overworld/region");
        Files.createDirectories(region);
        Files.write(region.resolve("r.0.0.mca"), new byte[] {1});
        var bare = UniverseAllocationLedger.deriveUniverseId(token(58));
        Files.createDirectories(save.resolve("dimensions/quantumchamber/universe").resolve(bare.value().toString()));
        var authorities = initialized(save);
        var catalog = catalog(save);
        assertCorrupt(authorities.allocateOrVerifyCatalog(orphan, EAGER_ONLY));
        assertCorrupt(authorities.allocateOrVerifyCatalog(bare, EAGER_ONLY));
        assertAll(
            () -> assertArrayEquals(new byte[] {1}, Files.readAllBytes(region.resolve("r.0.0.mca")), "不採用、不刪除"),
            () -> assertEquals(Map.of(), catalog.state.records(), "不配置"),
            () -> assertEquals(0, catalog.flushes),
            () -> assertFalse(authorities.unconfirmed(Authority.CATALOG)));
        assertEquals(Lookup.Status.APPENDED, authorities.allocateOrVerifyCatalog(UniverseAllocationLedger.deriveUniverseId(token(59)), EAGER_ONLY).status(),
                "沒有 storage 的 UUID 照常配置");
    }

    // ---- fixtures ----

    /** 新 key 直接寫 COMPLETE：閘門必須排在 caller 錯誤檢查之前，權威不可用時回失敗結果，不丟例外（F2b fix round 1 re-review Minor a）。 */
    private static WriteResult completeWithoutPending(CollapseAuthorities authorities, String name) {
        return assertDoesNotThrow(() -> authorities.writeProjection(pending(existingUniverse()).withStatus(Status.COMPLETE)),
                name + "：權威不可用時，新 key 寫 COMPLETE 回失敗結果而不是丟例外");
    }

    private void failedInitialization(String name, Fixture fixture, Outcome allocation, Outcome projection, SealedReason reason) throws Exception {
        io.clear();
        var save = save("init-fail-" + name);
        fixture.prepare(save);
        var journal = journal(3);
        try (var logs = new CollapseLogCapture()) {
            var authorities = authorities(save);
            var result = authorities.initialize(() -> journal);
            assertFalse(result.succeeded(), name);
            assertEquals(List.of(allocation, projection), List.of(result.allocation(), result.projection()), name);
            assertEquals(Optional.of(reason), result.sealedReason(), name);
            assertFalse(journal.m5ReadyThisInstance(), name + "：不提交新的 COLLAPSING");
            assertFalse(journal.m5Evidence(), name);
            assertEquals(3, schemaOf(journal), name + "：journal 不升 schema 4");
            if (projection == Outcome.NOT_CREATED || projection == Outcome.UNREADABLE) assertFalse(Files.exists(projectionFile(save)), name + "：失敗時不建立缺少的 ledger");
            var lookup = authorities.lookupOrAppendAllocation(intent(token(30)));
            assertEquals(Optional.of(reason), lookup.sealedReason(), name);
            assertEquals(Optional.of(reason), completeWithoutPending(authorities, name).sealedReason(), name);
            // discovery 與 catalog 也 fail closed：回初始化的失敗結果，不讀、不寫、不配置（F2c）。
            assertEquals(Optional.of(reason), authorities.appendDiscovery(existingUniverse(), 10, SELECTED_BY).sealedReason(), name);
            assertEquals(Optional.of(reason), authorities.discovery(existingUniverse()).sealedReason(), name);
            assertEquals(Optional.of(reason), authorities.allocateOrVerifyCatalog(existingUniverse(), EAGER_ONLY).sealedReason(), name);
            assertEquals(0, catalog(save).flushes, name + "：不寫出 catalog");
            assertFalse(Files.exists(discoveryFile(save)), name + "：不建立 discovery");
            assertEquals(1, logs.messages(Level.ERROR).size(), name + "：初始化失敗記 ERROR");
            assertNoTemporaryFiles(save);
        } finally {
            io.clear();
        }
    }

    @FunctionalInterface private interface Fixture { void prepare(Path save) throws Exception; }

    private CollapseAuthorities authorities(Path save) { return new CollapseAuthorities(save, io, catalog(save), () -> now[0]); }

    /** 以全新的 journal 初始化（建立兩個空 ledger）並斷言成功。 */
    private CollapseAuthorities initialized(Path save) {
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        return authorities;
    }

    /** 每個存檔一份 catalog（同一個存檔的「重啟」共用同一個 PersistentState instance）。 */
    private TestCatalog catalog(Path save) { return catalogs.computeIfAbsent(save.toAbsolutePath().normalize(), TestCatalog::new); }

    private Path save(String name) throws IOException { return Files.createDirectories(root.resolve(name)); }

    /** 先以全新存檔初始化，建立兩個空 ledger。 */
    private void seed(Path save) {
        assertTrue(authorities(save).initialize(SessionRecoveryState::new).succeeded());
    }

    private static void copyData(Path from, Path to) throws IOException {
        Files.createDirectories(to.resolve("data"));
        for (var name : List.of(UniverseAllocationLedger.FILE_NAME, ProjectionLedger.FILE_NAME)) {
            Files.copy(from.resolve("data").resolve(name), to.resolve("data").resolve(name));
        }
    }

    private static SessionRecoveryState journal(int schema) {
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", schema); nbt.put("Records", new NbtList());
        var state = SessionRecoveryState.fromNbt(nbt); state.requireHealthy(); return state;
    }

    private static int schemaOf(SessionRecoveryState journal) { return journal.writeNbt(new NbtCompound()).getInt("SchemaVersion"); }

    private static NbtCompound discoveryWithOneRecord() {
        var record = new NbtCompound();
        record.putUuid("UniverseId", UUID.fromString("00000000-0000-4000-8000-0000000000d1"));
        record.putLong("DiscoveryOrdinal", 0); record.putLong("DiscoveredAtGameTime", 5); record.putBoolean("GameplayEligible", true);
        var records = new NbtList(); records.add(record);
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", 1); nbt.put("Records", records);
        return nbt;
    }

    private static UniverseId existingUniverse() { return UniverseId.of(UUID.fromString("00000000-0000-4000-8000-0000000000e1")); }

    private static ProjectionLedger.Entry pending(UniverseId universe) {
        return new ProjectionLedger.Entry(CHAMBER, universe, DimensionRole.OVERWORLD, CONTROLLER, Direction.NORTH, Status.PENDING);
    }

    private static Path allocationFile(Path save) { return save.resolve("data").resolve(UniverseAllocationLedger.FILE_NAME); }
    private static Path projectionFile(Path save) { return save.resolve("data").resolve(ProjectionLedger.FILE_NAME); }
    private static Path discoveryFile(Path save) { return save.resolve("data").resolve(UniverseDiscoveryState.FILE_NAME); }

    /** 以 M4 的正式 codec 與 checked save 寫出 discovery fixture（record 依 canonical 順序）。 */
    private static void writeDiscovery(Path save, UniverseDiscoveryRecord... records) throws IOException {
        var list = new NbtList();
        for (var record : records) {
            var row = new NbtCompound();
            row.putUuid("UniverseId", record.universeId().value());
            row.putLong("DiscoveryOrdinal", record.discoveryOrdinal());
            row.putLong("DiscoveredAtGameTime", record.discoveredAtGameTime());
            record.firstObserver().ifPresent(observer -> row.putUuid("FirstObserver", observer));
            row.putBoolean("GameplayEligible", record.gameplayEligible());
            list.add(row);
        }
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", 1); nbt.put("Records", list);
        UniverseDiscoveryState.fromNbt(nbt).save(save);
    }

    private static NbtCompound disk(Path file) throws IOException { return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()); }
    private static UniverseAllocationLedger diskAllocationLedger(Path save) throws IOException { return UniverseAllocationLedger.fromNbt(disk(allocationFile(save))); }
    private static ProjectionLedger diskProjectionLedger(Path save) throws IOException { return ProjectionLedger.fromNbt(disk(projectionFile(save))); }

    private static void write(Path file, byte[] bytes) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, bytes); }

    private static byte[] compressed(NbtCompound nbt) throws IOException {
        var out = new ByteArrayOutputStream(); NbtIo.writeCompressed(nbt, out); return out.toByteArray();
    }

    private static void assertNoTemporaryFiles(Path save) throws IOException {
        var data = save.resolve("data");
        if (!Files.exists(data)) return;
        try (var files = Files.list(data)) { assertEquals(List.of(), files.filter(file -> file.toString().endsWith(".tmp")).toList()); }
    }

    private static void assertCorrupt(Lookup<?> lookup) {
        assertEquals(Lookup.Status.CORRUPT, lookup.status(), () -> lookup.toString());
        assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), lookup.sealedReason());
    }

    private static void assertUnconfirmed(Lookup<?> lookup, SealedReason reason) {
        assertEquals(Lookup.Status.UNCONFIRMED, lookup.status(), () -> lookup.toString());
        assertEquals(Optional.of(reason), lookup.sealedReason());
    }

    /** catalog 未確認時的配置結果（spec §6.2）：WAIT，沒有 SEALED 原因、沒有 failure，也不算成功。 */
    private static void assertWait(Lookup<?> lookup) {
        assertAll(
            () -> assertEquals(Lookup.Status.WAIT, lookup.status(), () -> lookup.toString()),
            () -> assertEquals(Optional.empty(), lookup.sealedReason(), "WAIT 不 SEALED"),
            () -> assertEquals(Optional.empty(), lookup.failure(), "WAIT 不是失敗"),
            () -> assertEquals(Optional.empty(), lookup.value()),
            () -> assertFalse(lookup.succeeded(), "WAIT 不算成功"));
    }

    /** {@link CollapseAuthorities.LedgerIo} 的注入實作：委派正式的 {@code readFile}，依檔名注入 IO 例外，並記錄每一次讀取。 */
    private static final class Io implements CollapseAuthorities.LedgerIo {
        /**
         * replacement 為 null 時丟 IOException；否則回傳 replacement 取代實際內容（讀到的 bytes 與寫入的不同）。skip 為生效前先放行的
         * 符合讀取次數（discovery 每次使用都先讀正式檔，第二次讀正式檔才是 atomic move 之後的讀回）。
         */
        private static final class Fault {
            final String fileName; final boolean temporary; final boolean once; final String label; final byte[] replacement;
            int skip;
            Fault(String fileName, boolean temporary, boolean once, String label, byte[] replacement, int skip) {
                this.fileName = fileName; this.temporary = temporary; this.once = once; this.label = label; this.replacement = replacement; this.skip = skip;
            }
            boolean matches(Path path) {
                var name = path.getFileName().toString();
                return temporary ? name.startsWith(fileName + ".") && name.endsWith(".tmp") : name.equals(fileName);
            }
        }
        private final List<Fault> faults = new ArrayList<>();
        final List<Path> reads = new ArrayList<>();

        @Override public byte[] read(Path path) throws IOException {
            reads.add(path);
            for (var iterator = faults.iterator(); iterator.hasNext();) {
                var fault = iterator.next();
                if (!fault.matches(path)) continue;
                if (fault.skip > 0) { fault.skip--; continue; }
                if (fault.once) iterator.remove();
                if (fault.replacement != null) return fault.replacement.clone();
                throw new IOException("注入：" + fault.label);
            }
            return CheckedNbtFile.readFile(path);
        }

        /** 下一次讀正式檔失敗：寫入時就是「atomic move 完成、讀回失敗」，初始化（或 discovery 的讀取）時就是讀取的 IO 例外。 */
        void failReadback(String fileName) { faults.add(new Fault(fileName, false, true, "atomic move 完成、讀回失敗", null, 0)); }
        /** 先放行 skip 次讀正式檔，下一次才失敗（discovery：放行追加前的讀取，讓 atomic move 之後的讀回失敗）。 */
        void failReadbackAfter(String fileName, int skip) { faults.add(new Fault(fileName, false, true, "atomic move 完成、讀回失敗", null, skip)); }
        void failReadbackAlways(String fileName) { faults.add(new Fault(fileName, false, false, "讀回持續失敗", null, 0)); }
        /** 下一次讀暫存檔失敗：寫入在 atomic move 之前就失敗，正式檔不變。 */
        void failTemporary(String fileName) { faults.add(new Fault(fileName, true, true, "暫存檔驗證失敗", null, 0)); }
        /** 持續把暫存檔讀成另一份合法的內容：暫存檔的 exact 讀回不符，寫入在 atomic move 之前失敗。 */
        void staleTemporary(String fileName, byte[] replacement) { faults.add(new Fault(fileName, true, false, "暫存檔讀回不符", replacement.clone(), 0)); }
        void clear() { faults.clear(); }
        int readsOf(String fileName) { return (int) reads.stream().filter(path -> path.getFileName().toString().startsWith(fileName)).count(); }
    }

    /**
     * {@link CollapseAuthorities.CatalogPort} 的測試實作（決策 9）：catalog 是真的 M3 {@link UniverseRegistryState}；寫出經 M3 的 save。
     * 注入的失敗不經過 save，所以 catalog 照 M3 save 失敗時的語意維持 current 與 dirty：{@link #failAfterMove} 先以 M3 的 store 寫出再丟例外
     * （「atomic move 完成、讀回失敗」，store 層本身的注入在 {@code UniverseRegistryStateTest}），{@link #failBeforeMove} 不寫出就丟例外。
     */
    private static final class TestCatalog implements CollapseAuthorities.CatalogPort {
        final Path path;
        UniverseRegistryState state = new UniverseRegistryState();
        int flushes;
        private int failures;
        private boolean landBeforeFailure;

        TestCatalog(Path save) { path = save.resolve("data").resolve(UniverseRegistryState.STATE_ID + ".dat"); }

        @Override public UniverseRegistryState catalog() { return state; }

        @Override public void flush(UniverseRegistryState catalog) {
            assertSame(state, catalog, "寫出的是同一個 PersistentState");
            flushes++;
            if (failures > 0) {
                failures--;
                if (landBeforeFailure) {
                    try { UniverseCatalogStore.write(path, wrapped(catalog)); } catch (IOException failure) { throw new UncheckedIOException(failure); }
                }
                throw new UncheckedIOException("注入：" + (landBeforeFailure ? "atomic move 完成、讀回失敗" : "atomic move 之前失敗"), new IOException("注入"));
            }
            catalog.save(path);
        }

        void failAfterMove(int times) { landBeforeFailure = true; failures = times; }
        void failBeforeMove(int times) { landBeforeFailure = false; failures = times; }

        /** 重新讀盤（等同重啟後 M3 載入的 catalog）。 */
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
}
