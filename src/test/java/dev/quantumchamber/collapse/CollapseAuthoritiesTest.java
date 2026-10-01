package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.UniverseAllocationLedgerTest.intent;
import static dev.quantumchamber.collapse.UniverseAllocationLedgerTest.token;
import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities.Authority;
import dev.quantumchamber.collapse.CollapseAuthorities.InitResult.Outcome;
import dev.quantumchamber.collapse.CollapseAuthorities.Lookup;
import dev.quantumchamber.collapse.CollapseAuthorities.WriteResult;
import dev.quantumchamber.collapse.ProjectionLedger.Entry.Status;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * spec §6 的 M5 authority：初始化與遺失判定（§15 的判定表）、既有紀錄的驗證、讀取失敗的分類，以及 roll-forward 收斂。
 * 檔案 IO 的失敗以 {@link CollapseAuthorities.LedgerIo} 這個 package-private port 注入（plan 決策 9），不用 testmod mixin。
 */
class CollapseAuthoritiesTest {
    @TempDir Path root;
    private final Io io = new Io();
    private final int[] now = {1000};
    private static final UUID CHAMBER = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
    private static final BlockPos CONTROLLER = new BlockPos(32, 70, -48);

    // ---- 初始化與遺失判定（spec §6、§15） ----

    @Test void freshSaveCreatesBothEmptyLedgersAndItsFirstNewIsNotLost() throws Exception {
        var save = save("fresh");
        var journal = new SessionRecoveryState();
        assertFalse(journal.m5Evidence());
        var authorities = authorities(save);
        var result = authorities.initialize(() -> journal);
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
        for (var existing : Authority.values()) cases.add(() -> {
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
        for (var deleted : Authority.values()) cases.add(() -> {
            var save = save("lost-" + deleted);
            seed(save);
            var gone = deleted == Authority.ALLOCATION_LEDGER ? allocationFile(save) : projectionFile(save);
            var kept = deleted == Authority.ALLOCATION_LEDGER ? projectionFile(save) : allocationFile(save);
            Files.delete(gone);
            byte[] keptBytes = Files.readAllBytes(kept);
            var journal = journal(4);
            try (var logs = new Logs()) {
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
        for (var deleted : Authority.values()) cases.add(() -> {
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
        try (var logs = new Logs()) {
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
            () -> assertEquals(WriteResult.Status.INVALID_TRANSITION, authorities.writeProjection(pending(UniverseId.of(UUID.randomUUID())).withStatus(Status.COMPLETE)).status(),
                    "沒有 PENDING 不能直接 COMPLETE"),
            () -> assertThrows(IllegalArgumentException.class, () -> authorities.writeProjection(new ProjectionLedger.Entry(CHAMBER, existingUniverse(),
                    DimensionRole.NETHER, CONTROLLER, Direction.NORTH, Status.PENDING)), "本切片的投影只有 OVERWORLD"));
        assertEquals(reads, io.reads.size(), "被拒或沒有變更的寫入不碰檔案");
        assertArrayEquals(afterPending, Files.readAllBytes(projectionFile(save)));
        var complete = pending.withStatus(Status.COMPLETE);
        assertEquals(WriteResult.Status.WRITTEN, authorities.writeProjection(complete).status());
        var regression = authorities.writeProjection(pending);
        assertEquals(WriteResult.Status.INVALID_TRANSITION, regression.status(), "COMPLETE 不能退回 PENDING");
        assertEquals(Optional.of(complete), regression.entry(), "帶回既有的 COMPLETE，caller 改走修復路徑");
        assertEquals(WriteResult.Status.UNCHANGED, authorities.writeProjection(complete).status());
        assertEquals(List.of(complete), diskProjectionLedger(save).entries());
    }

    // ---- checked 寫入失敗的收斂（spec §6 roll-forward） ----

    @Test void allocationReadbackFailureAfterTheMoveKeepsTheAppendAndConvergesBeforeTheNextUse() throws Exception {
        var save = save("allocation-roll-forward");
        var authorities = authorities(save);
        assertTrue(authorities.initialize(SessionRecoveryState::new).succeeded());
        var first = UniverseAllocationLedger.Entry.of(intent(token(21)));
        var second = UniverseAllocationLedger.Entry.of(intent(token(22)));
        try (var logs = new Logs()) {
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
        }
        // 重啟後新內容照常 strict 讀取。
        var restarted = authorities(save);
        assertEquals(Outcome.LOADED, restarted.initialize(() -> journal(4)).allocation());
        assertEquals(Lookup.Status.FOUND, restarted.lookupOrAppendAllocation(intent(token(22))).status());
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
        try (var logs = new Logs()) {
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
        try (var logs = new Logs()) {
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

    // ---- fixtures ----

    private void failedInitialization(String name, Fixture fixture, Outcome allocation, Outcome projection, SealedReason reason) throws Exception {
        io.clear();
        var save = save("init-fail-" + name);
        fixture.prepare(save);
        var journal = journal(3);
        try (var logs = new Logs()) {
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
            assertEquals(1, logs.messages(Level.ERROR).size(), name + "：初始化失敗記 ERROR");
            assertNoTemporaryFiles(save);
        } finally {
            io.clear();
        }
    }

    @FunctionalInterface private interface Fixture { void prepare(Path save) throws Exception; }

    private CollapseAuthorities authorities(Path save) { return new CollapseAuthorities(save, io, () -> now[0]); }

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

    private static NbtCompound disk(Path file) throws IOException { return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()); }
    private static UniverseAllocationLedger diskAllocationLedger(Path save) throws IOException { return UniverseAllocationLedger.fromNbt(disk(allocationFile(save))); }
    private static ProjectionLedger diskProjectionLedger(Path save) throws IOException { return ProjectionLedger.fromNbt(disk(projectionFile(save))); }

    private static void write(Path file, byte[] bytes) throws IOException { Files.createDirectories(file.getParent()); Files.write(file, bytes); }

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

    /** {@link CollapseAuthorities.LedgerIo} 的注入實作：委派正式的 {@code readFile}，依檔名注入 IO 例外，並記錄每一次讀取。 */
    private static final class Io implements CollapseAuthorities.LedgerIo {
        private record Fault(String fileName, boolean temporary, boolean once, String label) {
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
                if (fault.once()) iterator.remove();
                throw new IOException("注入：" + fault.label());
            }
            return CollapseAuthorities.readFile(path);
        }

        /** 下一次讀正式檔失敗：寫入時就是「atomic move 完成、讀回失敗」，初始化時就是讀取的 IO 例外。 */
        void failReadback(String fileName) { faults.add(new Fault(fileName, false, true, "atomic move 完成、讀回失敗")); }
        void failReadbackAlways(String fileName) { faults.add(new Fault(fileName, false, false, "讀回持續失敗")); }
        /** 下一次讀暫存檔失敗：寫入在 atomic move 之前就失敗，正式檔不變。 */
        void failTemporary(String fileName) { faults.add(new Fault(fileName, true, true, "暫存檔驗證失敗")); }
        void clear() { faults.clear(); }
        int readsOf(String fileName) { return (int) reads.stream().filter(path -> path.getFileName().toString().startsWith(fileName)).count(); }
    }

    /** 只收 {@code quantumchamber} logger 的 log4j appender（比照 SessionRecoverySchema4Test）；先送探針，證明確實攔得到。 */
    private static final class Logs extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.LoggerContext context;
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
        Logs() {
            super("f2b-log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
            context = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger("quantumchamber")).getContext();
            start();
            context.getConfiguration().getRootLogger().addAppender(this, null, null);
            context.updateLoggers();
            var probe = "F2B_LOG_CAPTURE_PROBE " + UUID.randomUUID();
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn(probe);
            if (!messages(Level.WARN).contains(probe)) { close(); throw new IllegalStateException("log capture 無法觀察 quantumchamber 的 WARN"); }
            events.clear();
        }
        @Override public void append(LogEvent event) { if ("quantumchamber".equals(event.getLoggerName())) events.add(event.toImmutable()); }
        List<String> messages(Level level) {
            synchronized (events) {
                return events.stream().filter(event -> event.getLevel() == level).map(event -> event.getMessage().getFormattedMessage()).toList();
            }
        }
        @Override public void close() {
            context.getConfiguration().getRootLogger().removeAppender(getName()); context.updateLoggers(); stop();
        }
    }
}
