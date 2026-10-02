package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.persistence.SessionRecoveryRecord.Participant;
import dev.quantumchamber.persistence.SessionRecoveryState.CommitResult;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalResult;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.UniverseId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
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
 * F2a：journal schema 4、三個塌縮狀態的合法組合、單調 authority 與狀態邊、跨 session 佔用、checked 終局，
 * 以及 roll-forward／restore 兩種 checked 提交（spec §5、§6）。
 */
class SessionRecoverySchema4Test {
    @TempDir Path directory;
    @BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }

    static final UUID SESSION = SessionRecoverySchema3Test.SESSION;
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000004");
    static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000005");
    static final UUID OUTSIDER = UUID.fromString("00000000-0000-0000-0000-000000000099");
    static final List<UUID> PLAYERS = List.of(A, B, C);
    static final UniverseId EXISTING_ID = UniverseId.of(SessionRecoverySchema3Test.UNIVERSE);
    static final UniverseId NEW_ID = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-000000000020"));
    /** schema 3 fixture 的 canonical ledger 順序：0＝SOURCE、1＝NEW、2＝EXISTING。 */
    static final int SOURCE = 0, NEW = 1, EXISTING = 2;

    // ---- 合法狀態矩陣 ----

    @Test void m4StatesKeepAnEmptyCollapseAndCollapseStatesRequireOne() {
        var measured = measured(EXISTING, 1);
        var fields = CollapseFields.committed(40, destination(EXISTING));
        var selectable = SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false, false)).records().get(SESSION);
        var cases = new ArrayList<Executable>();
        for (var phase : List.of(SessionState.ARMING, SessionState.SUPERPOSITION, SessionState.RETURNING)) cases.add(() ->
                assertThrows(IllegalArgumentException.class, () -> withState(selectable, phase, fields), "M4 狀態帶 Collapse：" + phase));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> measured.withCollapse(SessionState.MEASURED, fields)));
        for (var phase : List.of(SessionState.COLLAPSING, SessionState.PASSAGE_READY, SessionState.SEALED)) {
            cases.add(() -> assertThrows(IllegalArgumentException.class,
                    () -> settled(measured).withCollapse(phase, CollapseFields.EMPTY), "塌縮狀態缺 Collapse：" + phase));
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> withState(selectable, phase, fields), "SELECTABLE 不得塌縮：" + phase));
        }
        var legacy = SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture("RETURNING", false)).records().get(SESSION);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new SessionRecoveryRecord(legacy.sessionUuid(), legacy.chamberUuid(),
                legacy.origin(), legacy.participants(), legacy.spaceLeases(), SessionState.COLLAPSING, false, legacy.semantics(),
                Optional.empty(), List.of(), Optional.empty(), fields), "legacy record 不得塌縮"));
        cases.add(() -> assertEquals(CollapseFields.EMPTY, measured.collapse(), "既有建構子補空 Collapse"));
        cases.add(() -> assertDoesNotThrow(() -> collapsing(measured)));
        assertAll(cases);
    }

    @Test void collapsingMatrixAllowsPartialReturnButNoPassageFields() {
        var measured = measured(EXISTING, 2);
        var base = collapsing(measured);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertDoesNotThrow(() -> returned(base, A), "部分返還、lease 原樣"));
        cases.add(() -> assertDoesNotThrow(() -> settled(base), "全員返還、lease 清空"));
        cases.add(() -> assertDoesNotThrow(() -> returned(base, A, B), "全員返還、lease 原樣"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> cleared(returned(base, A)), "空 lease 蘊含全員已返還"));
        var opened = base.collapse().withPassage(100);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled(base).withCollapse(SessionState.COLLAPSING, opened)));
        var crossed = base.collapse().withCrossed(A);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled(base).withCollapse(SessionState.COLLAPSING, crossed)));
        var sealed = base.collapse().sealed(SealedReason.PROJECTION_FAILED);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled(base).withCollapse(SessionState.COLLAPSING, sealed)));
        var recovery = base.collapse().withPassage(100).transferRecoveryRequired(A);
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled(base).withCollapse(SessionState.COLLAPSING, recovery)));
        // 單一違規：只帶 RecoveryRequired（opened／expires 為 -1、沒有 Crossed 與 SealedReason）。
        var onlyRecovery = fields(base.collapse(), CollapseFields.UNSET, List.of(), List.of(A), Optional.empty());
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled(base).withCollapse(SessionState.COLLAPSING, onlyRecovery),
                "COLLAPSING 只帶 RecoveryRequired"));
        cases.add(() -> assertDoesNotThrow(() -> collapsing(measured(SOURCE, 1)), "SOURCE 目的地可以 COLLAPSING"));
        assertAll(cases);
    }

    @Test void passageReadyMatrixRequiresSettledUniverseWithOpenedPassage() {
        var measured = measured(EXISTING, 2);
        var settled = settled(collapsing(measured));
        var ready = ready(settled);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertDoesNotThrow(() -> ready.withCollapse(SessionState.PASSAGE_READY, ready.collapse().withCrossed(A).withCrossed(B))));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> returned(collapsing(measured), A, B)
                .withCollapse(SessionState.PASSAGE_READY, ready.collapse()), "READY 的 lease 必須已清空"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.PASSAGE_READY, settled.collapse()),
                "READY 必須帶 opened／expires"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.PASSAGE_READY,
                ready.collapse().withCrossed(OUTSIDER)), "Crossed 必須是參與者子集"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.PASSAGE_READY,
                ready.collapse().transferRecoveryRequired(A)), "READY 不帶 RecoveryRequired／SealedReason"));
        // 單一違規：其餘欄位都是合法的 READY。
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.PASSAGE_READY,
                fields(ready.collapse(), 100, List.of(), List.of(A), Optional.empty())), "READY 只帶 RecoveryRequired"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.PASSAGE_READY,
                fields(ready.collapse(), 100, List.of(), List.of(), Optional.of(SealedReason.PROJECTION_FAILED))), "READY 只帶 SealedReason"));
        var source = settled(collapsing(measured(SOURCE, 1)));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> source.withCollapse(SessionState.PASSAGE_READY,
                source.collapse().withPassage(100)), "SOURCE 永遠不會進到 READY"));
        assertAll(cases);
    }

    /** TRANSFER_RECOVERY_REQUIRED 的雙向「必須」：TRR 必須帶 READY 的值與恰一筆 RecoveryRequired；其他原因必須是 -1 與空清單。 */
    @Test void sealedMatrixBindsTransferRecoveryRequiredToPassageFieldsBothWays() {
        var settled = settled(collapsing(measured(EXISTING, 3)));
        var ready = ready(settled); var crossedA = crossed(ready, A);
        var committed = settled.collapse();
        var cases = new ArrayList<Executable>();
        for (var reason : SealedReason.values()) if (reason != SealedReason.TRANSFER_RECOVERY_REQUIRED) {
            cases.add(() -> assertDoesNotThrow(() -> settled.withCollapse(SessionState.SEALED, committed.sealed(reason)), reason.name()));
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                    ready.collapse().sealed(reason)), "非 TRR 不得帶 opened／expires：" + reason));
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                    crossedA.collapse().sealed(reason)), "非 TRR 不得帶 Crossed：" + reason));
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                    fields(committed, committed.passageOpenedAtGameTime(), List.of(), List.of(B), Optional.of(reason))), "非 TRR 不得帶 RecoveryRequired：" + reason));
            // 單一違規：opened／expires 為 -1，只帶 Crossed。
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                    fields(committed, CollapseFields.UNSET, List.of(A), List.of(), Optional.of(reason))), "非 TRR 只帶 Crossed：" + reason));
        }
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                fields(ready.collapse(), 100, List.of(OUTSIDER), List.of(B), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED))),
                "TRR 的 Crossed 只能是參與者"));
        cases.add(() -> assertDoesNotThrow(() -> transfer(crossedA, B)));
        cases.add(() -> assertDoesNotThrow(() -> transfer(ready, B), "沒有人跨過時也可能進入 TRR"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                committed.transferRecoveryRequired(B)), "TRR 必須帶 opened／expires"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                fields(ready.collapse(), 100, List.of(), List.of(), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED))), "TRR 必須恰有一筆 RecoveryRequired"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED,
                fields(ready.collapse(), 100, List.of(), List.of(B, C), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED))), "TRR 只能一筆"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> transfer(crossedA, A), "RecoveryRequired 不得已在 Crossed"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> transfer(crossedA, OUTSIDER), "RecoveryRequired 必須是參與者"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> settled.withCollapse(SessionState.SEALED, committed),
                "SEALED 必須有 SealedReason"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> returned(collapsing(measured(EXISTING, 3)), A, B, C)
                .withCollapse(SessionState.SEALED, committed.sealed(SealedReason.PROJECTION_FAILED)), "SEALED 的 lease 必須已清空"));
        var source = settled(collapsing(measured(SOURCE, 1)));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> source.withCollapse(SessionState.SEALED,
                source.collapse().sealed(SealedReason.PROJECTION_FAILED)), "SOURCE 不會進入 SEALED"));
        assertAll(cases);
    }

    @Test void destinationMustMatchTheSelectedCandidate() {
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertDoesNotThrow(() -> measured(NEW, 1).withCollapse(SessionState.COLLAPSING, CollapseFields.committed(1, destination(NEW)))));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> measured(SOURCE, 1).withCollapse(SessionState.COLLAPSING,
                CollapseFields.committed(1, new CollapseDestination.Universe(EXISTING_ID)))));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> measured(EXISTING, 1).withCollapse(SessionState.COLLAPSING,
                CollapseFields.committed(1, new CollapseDestination.Universe(NEW_ID))), "EXISTING 必須是候選裡的 UniverseId"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> measured(EXISTING, 1).withCollapse(SessionState.COLLAPSING,
                CollapseFields.committed(1, new CollapseDestination.Source()))));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> measured(NEW, 1).withCollapse(SessionState.COLLAPSING,
                CollapseFields.committed(1, new CollapseDestination.Source()))));
        assertAll(cases);
    }

    @Test void collapseFieldsRejectMalformedShapes() {
        var universe = new CollapseDestination.Universe(EXISTING_ID);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(2, 0, universe, -1, -1, List.of(), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, -1, universe, -1, -1, List.of(), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, 0, universe, 100, 1299, List.of(), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, 0, universe, 100, -1, List.of(), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, 0, universe, -1, 1199, List.of(), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, 0, universe, Long.MAX_VALUE, Long.MIN_VALUE + 1199,
                List.of(), List.of(), Optional.empty()), "expires 溢位"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(1, 0, universe, 100, 1300, List.of(A, A), List.of(), Optional.empty())));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapseFields(0, -1, null, -1, -1, List.of(A), List.of(), Optional.empty()),
                "沒有 Destination 只能是 EMPTY"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapseFields.committed(1, universe).sealed(SealedReason.TRANSFER_RECOVERY_REQUIRED)));
        cases.add(() -> assertEquals(1300, CollapseFields.committed(1, universe).withPassage(100).passageExpiresAtGameTime()));
        cases.add(() -> assertTrue(CollapseFields.EMPTY.isEmpty()));
        cases.add(() -> assertEquals(List.of(SealedReason.ALLOCATION_IO_FAILED, SealedReason.PROJECTION_FAILED, SealedReason.MATERIALIZE_FAILED,
                SealedReason.AUTHORITY_UNREADABLE), Arrays.stream(SealedReason.values()).filter(SealedReason::retryable).toList(), "可重試的原因（§11.2）"));
        cases.add(() -> assertEquals(List.of(SealedReason.MATERIALIZE_FAILED, SealedReason.AUTHORITY_UNREADABLE),
                Arrays.stream(SealedReason.values()).filter(SealedReason::restartOnly).toList(), "只在重啟之後重試的原因"));
        assertAll(cases);
    }

    // ---- 單調 authority 與狀態邊 ----

    private record Edge(String label, SessionRecoveryRecord previous, SessionRecoveryRecord next, boolean legal) {}

    @Test void stateEdgesAcceptOnlyTheSpecifiedTransitions() {
        var measured = measured(EXISTING, 2);
        var partialMeasured = returned(measured, A);
        var dormant = settled(measured);
        var collapsing = collapsing(measured);
        var partial = returned(collapsing, A);
        var returnedAll = returned(collapsing, A, B);
        var settled = settled(collapsing);
        var ready = ready(settled); var crossedA = crossed(ready, A); var crossedAB = crossed(crossedA, B);
        var crossedB = crossed(ready, B); var crossedBA = crossed(crossedB, A);
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var otherReason = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.MATERIALIZE_FAILED));
        var retry = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
        var transfer = transfer(crossedA, B);
        var transferNoCross = transfer(ready, B);
        var superposition = new SessionRecoveryRecord(measured.sessionUuid(), measured.chamberUuid(), measured.origin(), measured.participants(),
                measured.spaceLeases(), SessionState.SUPERPOSITION, false, measured.semantics(), measured.candidateContext(),
                measured.candidateLedger(), Optional.of(new CandidateSelection.Selectable()));
        var edges = List.of(
                new Edge("MEASURED→MEASURED", measured, partialMeasured, true),
                new Edge("MEASURED→COLLAPSING", measured, collapsing, true),
                new Edge("部分返還的 MEASURED→COLLAPSING", partialMeasured, returned(collapsing, A), true),
                new Edge("DORMANT→COLLAPSING", dormant, settled, true),
                new Edge("COLLAPSING self-loop：返還", collapsing, partial, true),
                new Edge("COLLAPSING self-loop：全員返還", partial, returnedAll, true),
                new Edge("COLLAPSING self-loop：清 lease", returnedAll, settled, true),
                new Edge("COLLAPSING→PASSAGE_READY", settled, ready, true),
                new Edge("COLLAPSING→SEALED", settled, sealed, true),
                new Edge("PASSAGE_READY self-loop：加入 Crossed", ready, crossedA, true),
                new Edge("PASSAGE_READY self-loop：附加第二位", crossedA, crossedAB, true),
                new Edge("PASSAGE_READY→SEALED(TRR)", crossedA, transfer, true),
                new Edge("PASSAGE_READY→SEALED(TRR)，沒人跨過", ready, transferNoCross, true),
                new Edge("SEALED→COLLAPSING 重試", sealed, retry, true),
                new Edge("SEALED 原樣", sealed, sealed, true),
                new Edge("SEALED(TRR) 原樣", transfer, transfer, true),
                new Edge("COLLAPSING→MEASURED", collapsing, measured, false),
                new Edge("SUPERPOSITION→COLLAPSING", superposition, collapsing, false),
                new Edge("MEASURED→PASSAGE_READY", dormant, ready, false),
                new Edge("MEASURED→SEALED", dormant, sealed, false),
                new Edge("返還倒退", partial, collapsing, false),
                new Edge("清空的 lease 復原", settled, returnedAll, false),
                new Edge("PASSAGE_READY→COLLAPSING", ready, settled, false),
                new Edge("PASSAGE_READY→SEALED(非 TRR)", ready, ready.withCollapse(SessionState.SEALED,
                        fields(ready.collapse(), -1, List.of(), List.of(), Optional.of(SealedReason.PROJECTION_FAILED))), false),
                new Edge("COLLAPSING→SEALED(TRR)", settled, transferNoCross, false),
                new Edge("Crossed 縮減", crossedA, ready, false),
                new Edge("Crossed 重排", crossedAB, crossedBA, false),
                new Edge("TRR 丟掉 Crossed", crossedA, transferNoCross, false),
                new Edge("TRR 改動 opened", crossedA, transfer(crossed(ready(settled, 200), A), B), false),
                new Edge("SEALED 換原因", sealed, otherReason, false),
                new Edge("SEALED→PASSAGE_READY", sealed, ready, false),
                new Edge("TRR 沒有回到 COLLAPSING 的重試邊", transfer, settled, false),
                new Edge("PASSAGE_READY 改動 opened", ready, ready(settled, 200), false),
                // 改變狀態的邊不得夾帶進度：進度只能經同狀態的 self-loop 寫入（§7.1 的提交帶著既有進度，不同時新增）。
                new Edge("MEASURED→COLLAPSING 夾帶返還", measured, partial, false),
                new Edge("MEASURED→COLLAPSING 夾帶清 lease", returned(measured, A, B), settled, false),
                new Edge("COLLAPSING→PASSAGE_READY 夾帶返還與清 lease", partial, ready, false),
                new Edge("COLLAPSING→PASSAGE_READY 夾帶清 lease", returnedAll, ready, false),
                new Edge("COLLAPSING→SEALED 夾帶返還與清 lease", partial, sealed, false),
                new Edge("COLLAPSING→SEALED 夾帶清 lease", returnedAll, sealed, false));
        assertAll(edges.stream().map(edge -> () ->
                assertEquals(edge.legal(), SessionRecoveryRecord.sameAuthority(edge.previous(), edge.next()), edge.label())));
    }

    @Test void immutableCollapseFieldsCannotChange() {
        var settled = settled(collapsing(measured(NEW, 1)));
        var ready = ready(settled);
        var changes = List.of(
                settled.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(41, destination(NEW))),
                settled.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(40, new CollapseDestination.Universe(EXISTING_ID))),
                ready(settled, 101));
        assertAll(changes.stream().map(changed -> () -> {
            assertFalse(SessionRecoveryRecord.sameAuthority(changed.state() == SessionState.PASSAGE_READY ? ready : settled, changed), changed.toString());
        }));
        var ledgerChanged = new SessionRecoveryRecord(settled.sessionUuid(), settled.chamberUuid(), settled.origin(), settled.participants(),
                settled.spaceLeases(), settled.state(), false, settled.semantics(), settled.candidateContext(),
                settled.candidateLedger().subList(1, 3), settled.candidateSelection(), settled.collapse());
        assertFalse(SessionRecoveryRecord.sameAuthority(settled, ledgerChanged), "進入 MEASURED 之後 ledger 完全不變");
    }

    /** SEALED(TRR) 完全凍結：沒有 self-loop、沒有重試邊，RecoveryRequired 只在進入時寫入一次。 */
    @Test void sealedTransferRecoveryRequiredIsFullyFrozen() {
        var ready = ready(settled(collapsing(measured(EXISTING, 3))));
        var crossedA = crossed(ready, A);
        var transfer = transfer(crossedA, B);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertFalse(SessionRecoveryRecord.sameAuthority(transfer, transfer(crossedA, C)), "RecoveryRequired 只寫一次"));
        cases.add(() -> assertFalse(SessionRecoveryRecord.sameAuthority(transfer, transfer.withCollapse(SessionState.SEALED,
                fields(transfer.collapse(), 100, List.of(A, C), List.of(B), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED)))), "Crossed 也凍結"));
        cases.add(() -> {
            var dormant = settled(measured(EXISTING, 3));
            var disk = new Disk("trr-frozen", dormant);
            var settled = settled(collapsing(measured(EXISTING, 3)));
            assertCommitted(disk.state.commitRestore(disk.flush(), dormant, settled));
            assertCommitted(disk.state.commitRestore(disk.flush(), settled, ready));
            assertCommitted(disk.state.commitRollForward(disk.flush(), ready, crossedA));
            assertCommitted(disk.state.commitRollForward(disk.flush(), crossedA, transfer));
            // 有實際變更的 self-loop（再附加一位 Crossed）：拒絕原因必須是 SEALED 沒有 roll-forward 邊，而不是「沒有變更」。
            var moreCrossed = transfer.withCollapse(SessionState.SEALED,
                    fields(transfer.collapse(), 100, List.of(A, C), List.of(B), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED)));
            var selfLoop = disk.state.commitRollForward(neverFlush(), transfer, moreCrossed);
            assertEquals(CommitResult.Status.REJECTED, selfLoop.status(), "沒有 self-loop");
            assertTrue(selfLoop.failure().orElseThrow().getMessage().contains("不是 roll-forward 類"), () -> selfLoop.failure().orElseThrow().toString());
            assertEquals(CommitResult.Status.REJECTED, disk.state.commitRestore(disk.flush(), transfer, settled).status(), "沒有重試邊");
            assertThrows(IllegalArgumentException.class, () -> disk.state.put(transfer(crossedA, C)));
            assertEquals(transfer, disk.state.records().get(SESSION)); assertEquals(transfer, disk.disk().get(SESSION));
        });
        assertAll(cases);
    }

    // ---- 跨 session 的玩家唯一性 ----

    @Test void occupiesReleasesOnlyReturnedCollapseParticipantsAndDormantReceipts() {
        var measured = measured(EXISTING, 2);
        var collapsing = returned(collapsing(measured), A);
        var settled = settled(collapsing(measured));
        var ready = ready(settled);
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.AUTHORITY_CORRUPT));
        var cases = new ArrayList<Executable>();
        for (String phase : List.of("ARMING", "SUPERPOSITION", "RETURNING")) {
            var record = SessionRecoveryStateTest.otherSession(A, SessionState.valueOf(phase), true, new UUID(0, 0x51), new UUID(0, 0x52), 3);
            cases.add(() -> assertTrue(SessionRecoveryState.occupies(record, A), phase + " 佔用全部參與者，含已返還者"));
        }
        cases.add(() -> assertTrue(SessionRecoveryState.occupies(returned(measured, A), A), "非 DORMANT 的 MEASURED 佔用已返還者"));
        cases.add(() -> assertFalse(SessionRecoveryState.occupies(settled(measured), A), "DORMANT 不佔用"));
        cases.add(() -> assertFalse(SessionRecoveryState.occupies(collapsing, A), "COLLAPSING 不佔用已返還者"));
        cases.add(() -> assertTrue(SessionRecoveryState.occupies(collapsing, B), "COLLAPSING 佔用尚未返還者"));
        for (var record : List.of(ready, sealed)) for (var player : List.of(A, B))
            cases.add(() -> assertFalse(SessionRecoveryState.occupies(record, player), record.state() + " 的參與者都已返還"));
        cases.add(() -> assertFalse(SessionRecoveryState.occupies(measured, OUTSIDER)));
        cases.add(() -> assertTrue(SessionRecoveryState.receiptRetained(SessionState.MEASURED)));
        for (var phase : SessionState.values()) cases.add(() -> assertEquals(phase == SessionState.MEASURED || phase == SessionState.COLLAPSING
                || phase == SessionState.PASSAGE_READY || phase == SessionState.SEALED, SessionRecoveryState.receiptRetained(phase), phase.name()));
        assertAll(cases);
    }

    /** spec §5：SEALED A 的已返還參與者同時是活動 B 的參與者；A 仍封鎖自己的原艙，未返還的 COLLAPSING 參與者仍唯一。 */
    @Test void sealedReturnedParticipantCanJoinAnotherActiveSession() {
        var settled = settled(collapsing(measured(EXISTING, 1)));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_CONFLICT));
        var state = journal(sealed);
        assertTrue(state.participantsAvailable(List.of(A)), "SEALED 不佔用已返還的參與者");
        var active = SessionRecoveryStateTest.otherSession(A, SessionState.SUPERPOSITION, false, new UUID(0, 0x61), new UUID(0, 0x62), 7);
        state.put(active);
        assertFalse(state.participantsAvailable(List.of(A)), "活動 B 佔用該玩家");
        var file = directory.resolve("sealed-and-active.dat"); state.save(file.toFile(), null);
        var reloaded = SessionRecoveryState.load(file); reloaded.requireHealthy();
        assertEquals(Map.of(SESSION, sealed, active.sessionUuid(), active), reloaded.flushedRecords(), "strict reload 接受 SEALED A 與活動 B 的同一玩家");
        var sameChamber = SessionRecoveryStateTest.otherSession(OUTSIDER, SessionState.ARMING, false, new UUID(0, 0x63), sealed.chamberUuid(), 8);
        assertThrows(IllegalArgumentException.class, () -> state.put(sameChamber), "SEALED 仍封鎖自己的原艙");
        var pending = journal(returned(collapsing(measured(EXISTING, 2)), A));
        assertTrue(pending.participantsAvailable(List.of(A)));
        assertFalse(pending.participantsAvailable(List.of(B)), "COLLAPSING 未返還者仍佔用");
        var competing = SessionRecoveryStateTest.otherSession(B, SessionState.ARMING, false, new UUID(0, 0x64), new UUID(0, 0x65), 9);
        assertThrows(IllegalArgumentException.class, () -> pending.put(competing));
    }

    @Test void removeRejectsEveryRetainedReceiptState() {
        var settled = settled(collapsing(measured(EXISTING, 1)));
        var records = List.of(collapsing(measured(EXISTING, 1)), ready(settled),
                settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.EXISTING_INELIGIBLE)));
        assertAll(records.stream().map(record -> () -> {
            var state = journal(record);
            assertThrows(IllegalStateException.class, () -> state.remove(SESSION), record.state().name());
            assertEquals(record, state.records().get(SESSION)); assertFalse(state.isDirty());
        }));
    }

    // ---- checked 提交：restore 與 roll-forward ----

    @Test void restoreEdgesOnlyCommitThroughCommitRestore() {
        var measured = measured(EXISTING, 1);
        var settled = settled(collapsing(measured));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var retry = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
        var cases = new ArrayList<Executable>();
        for (var pair : List.of(List.of(measured, collapsing(measured)), List.of(settled, ready(settled)), List.of(sealed, retry))) {
            var state = journal(pair.get(0)); state.markCollapseAuthoritiesInitialized();
            cases.add(() -> assertThrows(IllegalStateException.class, () -> state.put(pair.get(1)), "一般 put 不得寫 restore 邊：" + pair.get(1).state()));
            cases.add(() -> assertEquals(CommitResult.Status.REJECTED, state.commitRollForward(neverFlush(), pair.get(0), pair.get(1)).status()));
        }
        var rollForward = journal(settled);
        cases.add(() -> assertEquals(CommitResult.Status.REJECTED, rollForward.commitRestore(neverFlush(), settled, sealed).status(),
                "commitRestore 只接受 restore 類邊"));
        cases.add(() -> assertDoesNotThrow(() -> rollForward.put(sealed), "roll-forward 類可以經一般 put"));
        var fresh = new SessionRecoveryState(); fresh.markCollapseAuthoritiesInitialized();
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> fresh.put(settled), "塌縮狀態只能由保留的 receipt 經狀態邊進入"));
        assertAll(cases);
    }

    @Test void collapseCommitsRequireEvidenceAndNewCollapsingRequiresReadiness() {
        var measured = measured(EXISTING, 1);
        var file = directory.resolve("readiness.dat");
        var loaded = journal(measured);
        assertTrue(loaded.m5Evidence()); assertFalse(loaded.m5ReadyThisInstance(), "讀到 schema 4 不會讓 readiness 變真");
        assertEquals(CommitResult.Status.REJECTED, loaded.commitRestore(save(loaded, file), measured, collapsing(measured)).status());
        assertEquals(measured, loaded.records().get(SESSION)); assertFalse(loaded.isDirty());
        loaded.markCollapseAuthoritiesInitialized();
        assertCommitted(loaded.commitRestore(save(loaded, file), measured, collapsing(measured)));
        var settled = settled(collapsing(measured));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.MATERIALIZE_FAILED));
        var notReady = journal(sealed);
        var retry = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
        assertEquals(CommitResult.Status.REJECTED, notReady.commitRestore(save(notReady, file), sealed, retry).status(), "重試也是新的 COLLAPSING");
        var readyCommit = journal(settled);
        assertCommitted(readyCommit.commitRestore(save(readyCommit, file), settled, ready(settled)));
        var noEvidence = new SessionRecoveryState(); noEvidence.put(measured);
        noEvidence.save(directory.resolve("no-evidence.dat").toFile(), null);
        assertFalse(noEvidence.m5Evidence());
        var noEvidenceWrite = assertThrows(IllegalStateException.class, () -> noEvidence.put(collapsing(measured)));
        assertTrue(noEvidenceWrite.getMessage().contains("M5 authority 尚未初始化"), noEvidenceWrite::getMessage);
        assertEquals(CommitResult.Status.REJECTED, noEvidence.commitRestore(save(noEvidence, directory.resolve("no-evidence.dat")),
                measured, collapsing(measured)).status(), "schema 4 之前不得寫入塌縮狀態");
    }

    @Test void commitRestoreSucceedsOnlyAfterExactReadback() {
        var measured = measured(SOURCE, 1);
        var disk = new Disk("restore-ok", measured);
        var next = collapsing(measured);
        assertCommitted(disk.state.commitRestore(disk.flush(), measured, next));
        assertEquals(next, disk.state.flushedRecords().get(SESSION)); assertEquals(next, disk.disk().get(SESSION));
        assertFalse(disk.state.isDirty()); assertFalse(disk.state.rollForwardPending(SESSION));
        assertEquals(CommitResult.Status.REJECTED, disk.state.commitRestore(disk.flush(), measured, next).status(), "CAS：expected 已不是 current");
    }

    /** restore 類失敗（寫入前失敗，或 atomic move 完成、讀回失敗）：三條 restore 邊都 current 還原、保持 dirty，下一次寫出覆寫磁碟。 */
    @Test void commitRestoreFailureRestoresCurrentKeepsDirtyAndIsOverwrittenByTheNextWrite() {
        var cases = new ArrayList<Executable>();
        for (String edge : List.of("MEASURED→COLLAPSING", "COLLAPSING→PASSAGE_READY", "SEALED→COLLAPSING"))
            for (String mode : List.of("before-write", "after-write")) cases.add(() -> {
                var label = edge + " " + mode;
                var measured = measured(EXISTING, 1);
                var settled = settled(collapsing(measured));
                var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
                var disk = new Disk("restore-" + Math.abs(label.hashCode()), edge.startsWith("MEASURED") ? measured : settled(measured));
                SessionRecoveryRecord expected; SessionRecoveryRecord next;
                switch (edge) {
                    case "MEASURED→COLLAPSING" -> { expected = measured; next = collapsing(measured); }
                    case "COLLAPSING→PASSAGE_READY" -> {
                        assertCommitted(disk.state.commitRestore(disk.flush(), settled(measured), settled));
                        expected = settled; next = ready(settled);
                    }
                    default -> {
                        assertCommitted(disk.state.commitRestore(disk.flush(), settled(measured), settled));
                        assertCommitted(disk.state.commitRollForward(disk.flush(), settled, sealed));
                        expected = sealed; next = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
                    }
                }
                var result = disk.state.commitRestore(mode.equals("after-write") ? disk.failingReadback() : disk.blocked(), expected, next);
                assertEquals(CommitResult.Status.RESTORED, result.status(), label); assertTrue(result.failure().isPresent());
                assertEquals(expected, disk.state.records().get(SESSION), "current 還原為上一份已確認的內容：" + label);
                assertEquals(expected, disk.state.flushedRecords().get(SESSION));
                assertTrue(disk.state.isDirty()); assertTrue(disk.state.recoveryWritesSafe()); assertFalse(disk.state.rollForwardPending(SESSION));
                assertEquals(Optional.of(expected), disk.state.collapseView(SESSION), "restore 類失敗不設 roll-forward 旗標：" + label);
                if (mode.equals("after-write")) assertEquals(next, disk.disk().get(SESSION), "fixture：新內容已落地但未確認：" + label);
                var other = SessionRecoveryStateTest.otherSession(OUTSIDER, SessionState.SUPERPOSITION, false, new UUID(0, 0x71), new UUID(0, 0x72), 4);
                disk.state.put(other); disk.state.save(disk.file.toFile(), null);
                assertEquals(Map.of(SESSION, expected, other.sessionUuid(), other), disk.disk(), "下一次寫出以上一份已確認的內容覆寫磁碟：" + label);
                assertDoesNotThrow(() -> disk.state.put(expected), "authority history 已還原為 expected：" + label);
            });
        assertAll(cases);
    }

    @Test void commitRestoreCasMismatchQuarantinesAndConvergesToFlushed() {
        var measured = measured(EXISTING, 2);
        var disk = new Disk("restore-cas", measured);
        var next = collapsing(measured);
        // 單執行緒下 CAS 不符只可能來自 flush 期間的重入；以反射在窗口中改動 current 來模擬。
        var result = disk.state.commitRestore(() -> {
            try { field(disk.state, "records").put(SESSION, returned(next, A)); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
            throw new UncheckedIOException(new IOException("注入"));
        }, measured, next);
        assertEquals(CommitResult.Status.QUARANTINED, result.status());
        assertEquals(Set.of(SESSION), disk.state.quarantinedSessions());
        disk.state.save(disk.file.toFile(), null);
        assertEquals(measured, disk.disk().get(SESSION), "隔離期間只寫出上一份 flushed authority");
        assertEquals(Optional.of(measured), disk.state.convergeQuarantined(SESSION), "保留收據的狀態原樣保留");
        assertEquals(measured, disk.state.records().get(SESSION)); assertTrue(disk.state.quarantinedSessions().isEmpty());
        var dormant = settled(measured(EXISTING, 1));
        var sealedDisk = new Disk("restore-cas-sealed", dormant);
        var settled = settled(collapsing(measured(EXISTING, 1)));
        assertCommitted(sealedDisk.state.commitRestore(sealedDisk.flush(), dormant, settled));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        assertCommitted(sealedDisk.state.commitRollForward(sealedDisk.flush(), settled, sealed));
        assertTrue(sealedDisk.state.quarantine(SESSION));
        assertEquals(Optional.of(sealed), sealedDisk.state.convergeQuarantined(SESSION), "SEALED 原樣保留");
    }

    /**
     * 讀回已確認之後才出現的例外（例如 flush 之後的 hook）：結果以 flushed 為準，不把已確認的寫入當成失敗而還原；
     * 例外不得靜默丟棄，以 WARN 記錄，同一個 session 依 {@code FailureText.dedupeKey} 去重。
     */
    @Test void exceptionAfterConfirmedReadbackStillCountsAsCommitted() {
        var dormant = settled(measured(NEW, 1));
        var disk = new Disk("after-readback", dormant);
        var settled = settled(collapsing(measured(NEW, 1)));
        Runnable lateFailure = () -> { disk.state.save(disk.file.toFile(), null); throw new IllegalStateException("注入：讀回確認之後的例外"); };
        Runnable otherLateFailure = () -> { disk.state.save(disk.file.toFile(), null); throw new IllegalStateException("注入：另一個讀回確認之後的例外"); };
        try (var logs = new Logs()) {
            assertCommitted(disk.state.commitRestore(lateFailure, dormant, settled));
            var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
            assertCommitted(disk.state.commitRollForward(lateFailure, settled, sealed));
            assertFalse(disk.state.rollForwardPending(SESSION));
            var retry = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
            assertCommitted(disk.state.commitRestore(otherLateFailure, sealed, retry));
            assertEquals(TerminalResult.Status.REMOVED, disk.state.terminate(otherLateFailure, retry, TerminalOrigin.PREPARE_DOOR_CLOSED).status());
            assertFalse(disk.disk().containsKey(SESSION)); assertThrows(IllegalStateException.class, () -> disk.state.put(retry));
            var warnings = logs.messages(Level.WARN);
            assertEquals(2, warnings.size(), () -> "同一原因只記一次，換原因再記一次：" + warnings);
            assertTrue(warnings.get(0).contains(SESSION.toString()) && warnings.get(0).contains("注入：讀回確認之後的例外"), warnings.get(0));
            assertTrue(warnings.get(1).contains("注入：另一個讀回確認之後的例外"), warnings.get(1));
        }
    }

    /**
     * 讀回已確認之後的例外的去重鍵，在該 session 下一次乾淨（讀回確認、沒有例外）的寫出後清除：同一原因再出現時再記一次 WARN，
     * 與 {@code SessionRecoveryManager} 成功後 {@code failures.remove} 的慣例一致；WARN 附上 stack trace。
     */
    @Test void cleanWriteClearsTheConfirmedWarningKey() {
        var dormant = settled(measured(NEW, 1));
        var disk = new Disk("after-readback-clean", dormant);
        var settled = settled(collapsing(measured(NEW, 1)));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var retry = sealed.withCollapse(SessionState.COLLAPSING, sealed.collapse().retry());
        Runnable lateFailure = () -> { disk.state.save(disk.file.toFile(), null); throw new IllegalStateException("注入：讀回確認之後的例外"); };
        try (var logs = new Logs()) {
            assertCommitted(disk.state.commitRestore(lateFailure, dormant, settled));
            assertCommitted(disk.state.commitRollForward(disk.flush(), settled, sealed));
            assertCommitted(disk.state.commitRestore(lateFailure, sealed, retry));
            var warnings = logs.events(Level.WARN);
            var cases = new ArrayList<Executable>();
            cases.add(() -> assertEquals(2, warnings.size(), () -> "中間的乾淨寫出清除去重鍵，同一原因再出現時再記一次：" + logs.messages(Level.WARN)));
            for (var warning : warnings) cases.add(() -> {
                assertNotNull(warning.getThrown(), "WARN 必須附上 stack trace");
                assertEquals("注入：讀回確認之後的例外", warning.getThrown().getMessage());
            });
            assertAll(cases);
        }
    }

    /** put→flush 的同步窗口中，restore 類內容視同未 checked（不得被 recovery 或其他寫出承認）。 */
    @Test void commitRestoreWindowIsVisibleAsUncheckedAuthority() {
        var measured = measured(EXISTING, 1);
        var disk = new Disk("restore-window", measured);
        var next = collapsing(measured);
        boolean[] observed = {false};
        var result = disk.state.commitRestore(() -> {
            assertFalse(disk.state.recoveryWritesSafe(), "窗口中的 COLLAPSING 提交尚未 checked");
            assertEquals(Set.of(SESSION), disk.state.uncheckedSessions());
            assertEquals(Optional.of(measured), disk.state.collapseView(SESSION), "窗口中的 collapse view 仍是 flushed");
            observed[0] = true;
            throw new UncheckedIOException(new IOException("注入"));
        }, measured, next);
        assertTrue(observed[0]); assertEquals(CommitResult.Status.RESTORED, result.status());
        assertTrue(disk.state.recoveryWritesSafe()); assertTrue(disk.state.uncheckedSessions().isEmpty());
    }

    /** roll-forward 類失敗：current 保留新內容、保持 dirty、recovery 不停擺，之後其他 session 的 flush 把它寫出。 */
    @Test void rollForwardFailureKeepsCurrentAndAnotherSessionFlushWritesIt() {
        assertAll(List.of("before-write", "after-write").stream().map(mode -> () -> {
            var settled = settled(collapsing(measured(EXISTING, 1)));
            var dormant = settled(measured(EXISTING, 1));
            var disk = new Disk("roll-forward-" + mode, dormant);
            assertCommitted(disk.state.commitRestore(disk.flush(), dormant, settled));
            var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.ALLOCATION_IO_FAILED));
            var result = disk.state.commitRollForward(mode.equals("after-write") ? disk.failingReadback() : disk.blocked(), settled, sealed);
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING, result.status(), mode); assertTrue(result.failure().isPresent());
            assertEquals(sealed, disk.state.records().get(SESSION), "current 保留新內容");
            assertEquals(settled, disk.state.flushedRecords().get(SESSION));
            assertTrue(disk.state.isDirty()); assertTrue(disk.state.recoveryWritesSafe(), "roll-forward 內容不讓 recovery 停擺");
            assertTrue(disk.state.uncheckedSessions().isEmpty()); assertTrue(disk.state.rollForwardPending(SESSION));
            assertEquals(Optional.of(sealed), disk.state.collapseView(SESSION), "SEALED 先於落盤生效");
            var other = SessionRecoveryStateTest.otherSession(OUTSIDER, SessionState.SUPERPOSITION, false, new UUID(0, 0x81), new UUID(0, 0x82), 5);
            disk.state.put(other); disk.state.save(disk.file.toFile(), null);
            assertEquals(Map.of(SESSION, sealed, other.sessionUuid(), other), disk.disk(), "其他 session 的 flush 帶著它寫出");
            assertFalse(disk.state.rollForwardPending(SESSION), "讀回確認 flushed 已等於 current 時清除");
            assertEquals(Optional.of(sealed), disk.state.collapseView(SESSION));
        }));
    }

    /**
     * spec §10.3（Review Focus 1）：SEALED(TRR) 的 roll-forward 寫出失敗之後不得被隔離——收斂會以 flushed（PASSAGE_READY）取代 current，
     * 把可能已落地的 TRR 寫回成 READY。current 保留 TRR，下一次 flush 把它寫出。
     */
    @Test void unconfirmedRollForwardIsNotQuarantinedAndTheNextFlushWritesIt() {
        var dormant = settled(measured(EXISTING, 2));
        var disk = new Disk("trr-not-quarantined", dormant);
        var settled = settled(collapsing(measured(EXISTING, 2)));
        var ready = ready(settled); var crossedA = crossed(ready, A); var transfer = transfer(crossedA, B);
        assertCommitted(disk.state.commitRestore(disk.flush(), dormant, settled));
        assertCommitted(disk.state.commitRestore(disk.flush(), settled, ready));
        assertCommitted(disk.state.commitRollForward(disk.flush(), ready, crossedA));
        assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING, disk.state.commitRollForward(disk.failingReadback(), crossedA, transfer).status());
        assertEquals(transfer, disk.disk().get(SESSION), "fixture：TRR 已落地但未確認");
        assertFalse(disk.state.quarantine(SESSION), "未確認的 SEALED(TRR) 不得隔離");
        assertTrue(disk.state.quarantinedSessions().isEmpty());
        assertEquals(transfer, disk.state.records().get(SESSION), "current 仍是 TRR");
        assertEquals(Optional.of(transfer), disk.state.collapseView(SESSION));
        disk.state.save(disk.file.toFile(), null);
        assertEquals(transfer, disk.disk().get(SESSION), "下一次 flush 寫出 TRR，不退回 READY");
        assertEquals(transfer, disk.state.flushedRecords().get(SESSION)); assertFalse(disk.state.rollForwardPending(SESSION));
    }

    /** 終局的 flush 期間有 roll-forward 寫入（重入）而失敗：不隔離、不丟棄它，回 ROLL_FORWARD_PENDING。 */
    @Test void terminalCasMismatchWithUnconfirmedRollForwardIsNotQuarantined() {
        var ready = ready(settled(collapsing(measured(EXISTING, 2))));
        var state = journal(ready);
        var result = state.terminate(() -> { state.put(crossed(ready, A)); throw new UncheckedIOException(new IOException("注入")); },
                ready, TerminalOrigin.PASSAGE_ENDED);
        assertEquals(TerminalResult.Status.ROLL_FORWARD_PENDING, result.status());
        assertTrue(state.quarantinedSessions().isEmpty());
        assertEquals(crossed(ready, A), state.records().get(SESSION)); assertTrue(state.rollForwardPending(SESSION));
        assertEquals(Optional.of(crossed(ready, A)), state.collapseView(SESSION));
    }

    /** restore 提交的 CAS 不符、但 current 是已 checked 的 roll-forward 內容（只能以反射製造）：不隔離，回 ROLL_FORWARD_PENDING。 */
    @Test void commitRestoreCasMismatchWithUnconfirmedRollForwardIsNotQuarantined() {
        var settled = settled(collapsing(measured(EXISTING, 1)));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var state = journal(settled);
        var result = state.commitRestore(() -> { rollForwardBehindTheJournal(state, sealed); throw new UncheckedIOException(new IOException("注入")); },
                settled, ready(settled));
        assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING, result.status());
        assertTrue(state.quarantinedSessions().isEmpty());
        assertEquals(sealed, state.records().get(SESSION)); assertEquals(Optional.of(sealed), state.collapseView(SESSION));
    }

    /** 雙重故障（反射）：roll-forward 旗標與未 checked 的 restore 殘留同時存在時照常隔離；收斂後旗標清除。 */
    @Test void quarantinedDoubleFaultConvergesAndClearsTheRollForwardFlag() {
        var measured = measured(EXISTING, 1);
        var state = journal(measured); state.markCollapseAuthoritiesInitialized();
        doubleFault(state, collapsing(measured));
        assertTrue(state.rollForwardPending(SESSION)); assertEquals(Set.of(SESSION), state.uncheckedSessions());
        assertTrue(state.quarantine(SESSION));
        assertEquals(Optional.of(measured), state.convergeQuarantined(SESSION));
        assertFalse(state.rollForwardPending(SESSION), "收斂以 flushed 取代 current 時清除旗標");
        assertEquals(measured, state.records().get(SESSION));
    }

    /** 前置條件的鎖：先隔離再終局、以 roll-forward 寫 M4 的 MEASURED 進度，都在寫出之前拒絕。 */
    @Test void preconditionsRejectBeforeAnyWrite() {
        var ready = ready(settled(collapsing(measured(EXISTING, 1))));
        var quarantined = journal(ready);
        assertTrue(quarantined.quarantine(SESSION));
        var terminal = quarantined.terminate(neverFlush(), ready, TerminalOrigin.PASSAGE_ENDED);
        assertEquals(TerminalResult.Status.REJECTED, terminal.status(), "隔離中的 session 不得終局");
        assertEquals(ready, quarantined.records().get(SESSION));
        var measured = measured(EXISTING, 2);
        var m4 = journal(measured);
        var progress = m4.commitRollForward(neverFlush(), measured, returned(measured, A));
        assertEquals(CommitResult.Status.REJECTED, progress.status(), "MEASURED 的進度不是 M5 roll-forward");
        assertEquals(measured, m4.records().get(SESSION)); assertFalse(m4.isDirty());
    }

    /** collapse view 只在 roll-forward 尚未確認時回 current：SEALED、Crossed、返還與清 lease 進度。 */
    @Test void collapseViewUsesCurrentOnlyForUnconfirmedRollForward() {
        var measured = measured(EXISTING, 2);
        var collapsing = collapsing(measured);
        var returnedAll = returned(collapsing, A, B);
        var settled = settled(collapsing);
        var ready = ready(settled);
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var writes = List.of(List.of(collapsing, returned(collapsing, A)), List.of(returnedAll, settled), List.of(settled, sealed),
                List.of(ready, crossed(ready, A)));
        var cases = new ArrayList<Executable>();
        for (var write : writes) cases.add(() -> {
            var state = journal(write.get(0));
            assertEquals(Optional.of(write.get(0)), state.collapseView(SESSION));
            var result = state.commitRollForward(() -> { throw new UncheckedIOException(new IOException("注入")); }, write.get(0), write.get(1));
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING, result.status());
            assertEquals(Optional.of(write.get(1)), state.collapseView(SESSION), "未確認的 roll-forward 回 current");
            assertEquals(write.get(0), state.flushedRecords().get(SESSION));
        });
        cases.add(() -> {
            var state = journal(measured); state.put(returned(measured, A));
            assertEquals(Optional.of(measured), state.collapseView(SESSION), "M4 的 MEASURED 進度不屬於 collapse view 的例外");
        });
        cases.add(() -> {
            var state = journal(settled); state.put(sealed);
            assertTrue(state.rollForwardPending(SESSION), "一般 put 的 roll-forward 也設旗標");
            assertEquals(Optional.of(sealed), state.collapseView(SESSION));
            assertFalse(state.quarantine(SESSION), "只差 roll-forward 進度的 session 不得隔離");
            assertEquals(Optional.of(sealed), state.collapseView(SESSION));
        });
        cases.add(() -> {
            // 只有雙重故障（反射）才會同時有 roll-forward 旗標與未 checked 的 restore 殘留；這時照常隔離，collapse view 不採用 current。
            var state = journal(measured); state.markCollapseAuthoritiesInitialized();
            doubleFault(state, collapsing);
            assertTrue(state.quarantine(SESSION));
            assertEquals(Optional.of(measured), state.collapseView(SESSION), "隔離 session 的 current 不採用");
        });
        cases.add(() -> assertEquals(Optional.empty(), journal().collapseView(SESSION)));
        assertAll(cases);
    }

    // ---- checked 終局 ----

    @Test void terminalRemovesAfterCheckedReadbackThenTrimsHistoryAndTombstones() throws Exception {
        var measured = measured(EXISTING, 1);
        var disk = new Disk("terminal", settled(measured));
        var settled = settled(collapsing(measured));
        assertCommitted(disk.state.commitRestore(disk.flush(), settled(measured), settled));
        var ready = ready(settled);
        assertCommitted(disk.state.commitRestore(disk.flush(), settled, ready));
        assertTrue(field(disk.state, "authorityHistory").containsKey(SESSION)); assertTrue(field(disk.state, "checkedAuthority").containsKey(SESSION));
        var result = disk.state.terminate(disk.flush(), ready, TerminalOrigin.PASSAGE_ENDED);
        assertEquals(TerminalResult.Status.REMOVED, result.status()); assertTrue(result.failure().isEmpty());
        assertFalse(disk.state.records().containsKey(SESSION)); assertFalse(disk.state.flushedRecords().containsKey(SESSION));
        assertFalse(disk.disk().containsKey(SESSION), "讀回確認不存在");
        assertFalse(field(disk.state, "authorityHistory").containsKey(SESSION), "成功後才修剪 authorityHistory");
        assertFalse(field(disk.state, "checkedAuthority").containsKey(SESSION), "成功後才修剪 checkedAuthority");
        assertThrows(IllegalStateException.class, () -> disk.state.put(ready), "tombstone：同一 UUID 不得重新放回");
        assertThrows(IllegalStateException.class, () -> disk.state.put(measured), "tombstone 也擋 M4 狀態");
        assertFalse(disk.state.remove(SESSION));
        assertEquals(TerminalResult.Status.REJECTED, disk.state.terminate(disk.flush(), ready, TerminalOrigin.PASSAGE_ENDED).status());
        var other = SessionRecoveryStateTest.otherSession(A, SessionState.ARMING, false, new UUID(0, 0x91), measured.chamberUuid(), 6);
        assertDoesNotThrow(() -> disk.state.put(other), "原艙解除封鎖，參與者可再入場");
    }

    @Test void terminalAcceptsOnlyItsFourOriginsAndExactCurrentAndFlushed() {
        var sourceSettled = settled(collapsing(measured(SOURCE, 1)));
        var universeSettled = settled(collapsing(measured(NEW, 1)));
        var sourcePending = collapsing(measured(SOURCE, 1));
        var ready = ready(settled(collapsing(measured(EXISTING, 1))));
        var sealed = universeSettled.withCollapse(SessionState.SEALED, universeSettled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        record Row(TerminalOrigin origin, SessionRecoveryRecord record, boolean accepted) {}
        var rows = List.of(
                new Row(TerminalOrigin.PASSAGE_ENDED, ready, true),
                new Row(TerminalOrigin.PASSAGE_ENDED, universeSettled, false),
                new Row(TerminalOrigin.SOURCE_FAST_PATH, sourceSettled, true),
                new Row(TerminalOrigin.SOURCE_FAST_PATH, sourcePending, false),
                new Row(TerminalOrigin.SOURCE_FAST_PATH, universeSettled, false),
                new Row(TerminalOrigin.PREPARE_DOOR_CLOSED, universeSettled, true),
                new Row(TerminalOrigin.PREPARE_DOOR_CLOSED, returned(collapsing(measured(NEW, 1)), A), false),
                new Row(TerminalOrigin.PREPARE_DOOR_CLOSED, sourceSettled, false),
                new Row(TerminalOrigin.PREPARE_DOOR_CLOSED, ready, false),
                new Row(TerminalOrigin.PASSAGE_ENDED, sealed, false),
                new Row(TerminalOrigin.SOURCE_FAST_PATH, sealed, false),
                new Row(TerminalOrigin.UNSUPPORTED_SOURCE_CANCEL, sealed, false),
                new Row(TerminalOrigin.UNSUPPORTED_SOURCE_CANCEL, settled(measured(EXISTING, 1)), false));
        var cases = new ArrayList<Executable>();
        for (var row : rows) cases.add(() -> {
            var state = journal(row.record());
            var result = state.terminate(save(state, directory.resolve("terminal-" + UUID.randomUUID() + ".dat")), row.record(), row.origin());
            assertEquals(row.accepted() ? TerminalResult.Status.REMOVED : TerminalResult.Status.REJECTED, result.status(),
                    row.origin() + " ← " + row.record().state());
            if (!row.accepted()) { assertEquals(row.record(), state.records().get(SESSION)); assertFalse(state.isDirty()); }
        });
        cases.add(() -> {
            var state = journal(ready); state.put(crossed(ready, A));
            assertEquals(TerminalResult.Status.REJECTED, state.terminate(neverFlush(), ready, TerminalOrigin.PASSAGE_ENDED).status(),
                    "current 已不是 expected");
            assertEquals(TerminalResult.Status.REJECTED, state.terminate(neverFlush(), crossed(ready, A), TerminalOrigin.PASSAGE_ENDED).status(),
                    "flushed 已不是 expected（roll-forward 尚未確認）");
        });
        assertAll(cases);
    }

    /** §11.3：flushed DORMANT MEASURED、來源 SourceFamilyRef.role() 不是 OVERWORLD、選中 NEW 或 EXISTING。 */
    @Test void unsupportedSourceCancelAcceptsOnlyNonOverworldDormantNewOrExisting() {
        record Row(String label, SessionRecoveryRecord record, boolean accepted) {}
        var rows = List.of(
                new Row("Nether NEW DORMANT", settled(measured(NEW, 1, true)), true),
                new Row("Nether EXISTING DORMANT", settled(measured(EXISTING, 1, true)), true),
                new Row("Nether SOURCE DORMANT", settled(measured(SOURCE, 1, true)), false),
                new Row("Nether NEW 尚未返還", measured(NEW, 1, true), false),
                new Row("Nether NEW 已返還、lease 仍在", returned(measured(NEW, 1, true), A), false),
                new Row("Overworld NEW DORMANT", settled(measured(NEW, 1)), false),
                new Row("只有 Origin 在 Nether、SourceFamilyRef 是 OVERWORLD", settled(measured(NEW, 1, false, true)), false));
        assertAll(rows.stream().map(row -> () -> {
            var empty = new NbtCompound(); empty.putInt("SchemaVersion", 3); empty.put("Records", new NbtList());
            var state = SessionRecoveryState.fromNbt(empty); state.put(row.record());
            var file = directory.resolve("cancel-" + UUID.randomUUID() + ".dat"); state.save(file.toFile(), null);
            assertFalse(state.m5Evidence(), "fixture：schema 3 journal");
            var result = state.terminate(save(state, file), row.record(), TerminalOrigin.UNSUPPORTED_SOURCE_CANCEL);
            assertEquals(row.accepted() ? TerminalResult.Status.REMOVED : TerminalResult.Status.REJECTED, result.status(), row.label());
            assertEquals(!row.accepted(), SessionRecoveryState.load(file).flushedRecords().containsKey(SESSION), row.label());
        }));
    }

    /** 終局的 flush 或讀回失敗：以 CAS 放回 expected、保持 dirty；history 不修剪、不留 tombstone，下一次寫出覆寫磁碟。 */
    @Test void terminalFlushFailureRestoresExpectedAndKeepsDirty() {
        assertAll(List.of("before-write", "after-write").stream().map(mode -> () -> {
            var dormant = settled(measured(SOURCE, 1));
            var disk = new Disk("terminal-" + mode, dormant);
            var settled = settled(collapsing(measured(SOURCE, 1)));
            assertCommitted(disk.state.commitRestore(disk.flush(), dormant, settled));
            TerminalResult result;
            try (var logs = new Logs()) {
                result = disk.state.terminate(mode.equals("after-write") ? disk.failingReadback() : disk.blocked(), settled, TerminalOrigin.SOURCE_FAST_PATH);
                assertEquals(List.of(), logs.messages(Level.WARN), "終局失敗的 WARN 由 caller 依原因去重後記錄，journal 不記");
            }
            assertEquals(TerminalResult.Status.RESTORED, result.status(), mode); assertTrue(result.failure().isPresent());
            assertEquals(settled, disk.state.records().get(SESSION)); assertEquals(settled, disk.state.flushedRecords().get(SESSION));
            assertTrue(disk.state.isDirty()); assertTrue(field(disk.state, "authorityHistory").containsKey(SESSION));
            assertThrows(IllegalArgumentException.class, () -> disk.state.put(settled(measured(SOURCE, 1))), "authority 不倒退、tombstone 未設");
            if (mode.equals("after-write")) assertFalse(disk.disk().containsKey(SESSION), "fixture：移除已落地但未確認");
            disk.state.save(disk.file.toFile(), null);
            assertEquals(settled, disk.disk().get(SESSION), "下一次寫出把 expected 寫回");
            assertEquals(TerminalResult.Status.REMOVED, disk.state.terminate(disk.flush(), settled, TerminalOrigin.SOURCE_FAST_PATH).status(), "可重試");
        }));
    }

    @Test void terminalCasMismatchAfterFailureQuarantines() {
        var settled = settled(collapsing(measured(NEW, 1)));
        var state = journal(settled);
        var result = state.terminate(() -> { state.put(settled); throw new UncheckedIOException(new IOException("注入")); },
                settled, TerminalOrigin.PREPARE_DOOR_CLOSED);
        assertEquals(TerminalResult.Status.QUARANTINED, result.status());
        assertEquals(Set.of(SESSION), state.quarantinedSessions());
        assertEquals(Optional.of(settled), state.convergeQuarantined(SESSION));
    }

    // ---- schema 4 版本與 codec ----

    @Test void schemaFormulaNeverWritesFourWithoutCandidateAndM5Evidence() {
        var empty = new SessionRecoveryState(); empty.markCollapseAuthoritiesInitialized();
        assertEquals(2, schemaOf(empty), "空 journal＋已初始化 → 2");
        var legacy = SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture("RETURNING", false)); legacy.markCollapseAuthoritiesInitialized();
        assertFalse(legacy.candidateInitialized()); assertEquals(2, schemaOf(legacy), "只有 legacy → 2");
        var candidate = new SessionRecoveryState();
        var selectable = SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false, false)).records().get(SESSION);
        candidate.put(selectable);
        assertEquals(3, schemaOf(candidate), "candidateInitialized＋m5Evidence 為假 → 3");
        candidate.markCollapseAuthoritiesInitialized();
        assertEquals(4, schemaOf(candidate), "candidateInitialized＋m5Evidence 為真 → 4");
        candidate.remove(SESSION);
        assertEquals(4, schemaOf(candidate), "不降回");
        assertEquals(2, schemaOf(new SessionRecoveryState()));
    }

    @Test void readingSchemaFourSetsEvidenceButNotReadinessAndNeverDowngrades() {
        var input = new NbtCompound(); input.putInt("SchemaVersion", 4); input.put("Records", new NbtList());
        var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
        assertTrue(state.candidateInitialized()); assertTrue(state.m5Evidence()); assertFalse(state.m5ReadyThisInstance());
        assertEquals(input, state.writeNbt(new NbtCompound()), "空 schema 4 exact 讀回");
        var selectable = SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false, false)).records().get(SESSION);
        state.put(selectable); state.remove(SESSION);
        assertEquals(4, schemaOf(state));
        var file = directory.resolve("schema-four.dat"); state.save(file.toFile(), null);
        var reloaded = SessionRecoveryState.load(file); reloaded.requireHealthy();
        assertTrue(reloaded.m5Evidence()); assertFalse(reloaded.m5ReadyThisInstance(), "新 instance 的 readiness 只取決於本次初始化");
        reloaded.markCollapseAuthoritiesInitialized(); assertTrue(reloaded.m5ReadyThisInstance());
    }

    @Test void schemaThreeLoadsWithEmptyCollapseAndUpgradesOnTheNextCheckedWrite() {
        var input = SessionRecoverySchema3Test.fixture(true, false);
        var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
        assertEquals(CollapseFields.EMPTY, state.records().get(SESSION).collapse(), "schema 3 補空 Collapse");
        assertFalse(state.m5Evidence()); assertEquals(input, state.writeNbt(new NbtCompound()), "初始化前照舊寫 3");
        state.markCollapseAuthoritiesInitialized();
        assertFalse(state.isDirty(), "初始化本身不寫檔；之後第一次 checked 寫入才升 schema 4");
        var expected = input.copy(); expected.putInt("SchemaVersion", 4);
        SessionRecoverySchema3Test.record(expected).put("Collapse", new NbtCompound());
        assertEquals(expected, state.writeNbt(new NbtCompound()));
        var reloaded = SessionRecoveryState.fromNbt(expected); reloaded.requireHealthy();
        assertEquals(state.records(), reloaded.records());
    }

    @Test void schemaFourRoundtripsEveryStateExactly() {
        var measured = measured(EXISTING, 3);
        var settled = settled(collapsing(measured));
        var ready = ready(settled); var crossedA = crossed(ready, A);
        var records = List.of(measured, settled(measured(NEW, 1)), collapsing(measured), returned(collapsing(measured), B),
                settled(collapsing(measured(SOURCE, 1))), settled(collapsing(measured(NEW, 1))), ready, crossedA, crossed(crossedA, C),
                settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.AUTHORITY_UNREADABLE)), transfer(crossedA, B));
        assertAll(records.stream().map(record -> () -> {
            var input = envelope(record);
            var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
            assertEquals(record, state.records().get(SESSION), record.state().name());
            assertEquals(input, state.writeNbt(new NbtCompound()), "exact 讀回：" + record.state());
        }));
        var crossedList = SessionRecoverySchema3Test.record(envelope(crossedA)).getCompound("Collapse").getList("Crossed", NbtElement.INT_ARRAY_TYPE);
        assertEquals(1, crossedList.size(), "Crossed 以 UUID int array 編碼");
        var empty = SessionRecoverySchema3Test.record(envelope(ready)).getCompound("Collapse").get("Crossed");
        assertEquals(NbtElement.END_TYPE, ((NbtList) empty).getHeldType(), "空清單沿用 END held type");
    }

    @Test void schemaFourRejectionFixturesFailClosed() throws Exception {
        var settled = settled(collapsing(measured(EXISTING, 3)));
        var transfer = transfer(crossed(ready(settled), A), B);
        List<Consumer<NbtCompound>> mutations = List.of(
                n -> row(n).remove("Collapse"),
                n -> row(n).putString("Collapse", "none"),
                n -> collapse(n).putString("Unknown", "x"),
                n -> collapse(n).remove("CollapseRevision"),
                n -> collapse(n).putInt("CollapseRevision", 2),
                n -> collapse(n).putLong("CollapseRevision", 1),
                n -> collapse(n).putLong("CommittedAtGameTime", -1),
                n -> collapse(n).putInt("CommittedAtGameTime", 40),
                n -> collapse(n).getCompound("Destination").putString("Kind", "UNKNOWN"),
                n -> collapse(n).getCompound("Destination").remove("UniverseId"),
                n -> collapse(n).getCompound("Destination").putString("Kind", "SOURCE"),
                n -> collapse(n).getCompound("Destination").putIntArray("UniverseId", new int[] {1, 2, 3}),
                n -> collapse(n).getCompound("Destination").putUuid("UniverseId", NEW_ID.value()),
                n -> collapse(n).putLong("PassageExpiresAtGameTime", 1299),
                n -> collapse(n).putLong("PassageOpenedAtGameTime", -1),
                n -> collapse(n).putInt("PassageOpenedAtGameTime", 100),
                n -> collapse(n).put("Crossed", list(NbtString.of("bad"))),
                n -> collapse(n).put("Crossed", list(new NbtIntArray(new int[] {1, 2, 3}))),
                n -> collapse(n).put("Crossed", list(NbtHelper.fromUuid(A), NbtHelper.fromUuid(A))),
                n -> collapse(n).put("Crossed", list(NbtHelper.fromUuid(OUTSIDER))),
                n -> collapse(n).put("RecoveryRequired", new NbtList()),
                n -> collapse(n).put("RecoveryRequired", list(NbtHelper.fromUuid(A))),
                n -> collapse(n).putString("SealedReason", ""),
                n -> collapse(n).putString("SealedReason", "UNKNOWN"),
                n -> collapse(n).putString("SealedReason", "PROJECTION_FAILED"),
                n -> collapse(n).putString("SealedReason", "transfer_recovery_required"),
                n -> row(n).putString("State", "PASSAGE_READY"),
                n -> row(n).putString("State", "MEASURED"),
                n -> row(n).put("Collapse", new NbtCompound()),
                n -> n.putString("Unknown", "x"),
                n -> n.putInt("SchemaVersion", 5),
                n -> n.putInt("SchemaVersion", 3));
        var cases = new ArrayList<Executable>();
        for (var mutate : mutations) cases.add(() -> { var input = envelope(transfer); mutate.accept(input); SessionRecoverySchema3Test.unhealthy(input); });
        cases.add(() -> { var input = envelope(settled); collapse(input).put("Crossed", SessionRecoverySchema3Test.emptyList(NbtElement.INT_ARRAY_TYPE));
            SessionRecoverySchema3Test.unhealthy(input); });
        cases.add(() -> { var input = envelope(measured(EXISTING, 1)); collapse(input).putInt("CollapseRevision", 1);
            SessionRecoverySchema3Test.unhealthy(input); });
        cases.add(() -> { var input = envelope(settled); collapse(input).putString("SealedReason", "PROJECTION_FAILED");
            SessionRecoverySchema3Test.unhealthy(input); });
        cases.add(() -> { var legacy = SessionRecoveryStateTest.fixture("RETURNING", false); legacy.putInt("SchemaVersion", 4);
            SessionRecoverySchema3Test.unhealthy(legacy); });
        cases.add(() -> { var schemaThree = SessionRecoverySchema3Test.fixture(true, false);
            SessionRecoverySchema3Test.record(schemaThree).putString("State", "COLLAPSING"); SessionRecoverySchema3Test.unhealthy(schemaThree); });
        cases.add(() -> { var schemaThree = SessionRecoverySchema3Test.fixture(true, false);
            SessionRecoverySchema3Test.record(schemaThree).put("Collapse", new NbtCompound()); SessionRecoverySchema3Test.unhealthy(schemaThree); });
        assertAll(cases);
    }

    // ---- F3a：既有 MEASURED-only 分支認得三個塌縮狀態（spec §13.2） ----

    /** spec §7.3：RETURN_PLAYERS／RELEASE_GEOMETRY 由 MEASURED 與 COLLAPSING 共用推導；COLLAPSING 完成後是 SETTLED，永遠不會是 DORMANT。 */
    @Test void measuredRecoveryPhaseExtendsToCollapsingAndDropsRetainReceipt() {
        var measured = measured(EXISTING, 2);
        var collapsing = collapsing(measured);
        var settled = settled(collapsing);
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.RETURN_PLAYERS, assertDoesNotThrow(() -> MeasuredRecoveryPhase.from(collapsing)), "COLLAPSING 尚有人未返還"));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.RETURN_PLAYERS, assertDoesNotThrow(() -> MeasuredRecoveryPhase.from(returned(collapsing, A))), "COLLAPSING 部分返還"));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.RELEASE_GEOMETRY, assertDoesNotThrow(() -> MeasuredRecoveryPhase.from(returned(collapsing, A, B))), "COLLAPSING 全員返還、仍有 lease"));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.SETTLED, assertDoesNotThrow(() -> MeasuredRecoveryPhase.from(settled)), "COLLAPSING 返還與清理完成"));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.RETURN_PLAYERS, MeasuredRecoveryPhase.from(measured), "MEASURED 維持 M4 推導"));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.RELEASE_GEOMETRY, MeasuredRecoveryPhase.from(returned(measured, A, B))));
        cases.add(() -> assertEquals(MeasuredRecoveryPhase.DORMANT, MeasuredRecoveryPhase.from(settled(measured))));
        for (var record : List.of(ready(settled), sealed))
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> MeasuredRecoveryPhase.from(record), record.state() + " 沒有返還與清理階段"));
        cases.add(() -> assertEquals(List.of(MeasuredRecoveryPhase.RETURN_PLAYERS, MeasuredRecoveryPhase.RELEASE_GEOMETRY, MeasuredRecoveryPhase.DORMANT,
                MeasuredRecoveryPhase.SETTLED), List.of(MeasuredRecoveryPhase.values()), "永遠不會回傳的 RETAIN_RECEIPT 已移除"));
        for (var phase : MeasuredRecoveryPhase.values()) cases.add(() -> assertEquals(phase == MeasuredRecoveryPhase.DORMANT
                || phase == MeasuredRecoveryPhase.SETTLED, phase.released(), phase.name()));
        assertAll(cases);
    }

    /**
     * spec §5、§13.2：依玩家找所屬 session 只回傳仍佔用他的 record（occupies，塌縮狀態依 collapse view）。flushed 是 Map.copyOf，迭代順序不固定，
     * 所以「SEALED A＋活動 B」重複 16 組彼此獨立的 UUID：只看參與者的 findFirst 全部恰好挑中 B 的機率約 2^-16。
     */
    @Test void recordForFindsOnlyTheSessionThatStillOccupiesThePlayer() {
        var cases = new ArrayList<Executable>();
        var settled = settled(collapsing(measured(EXISTING, 1)));
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        for (int round = 0; round < 16; round++) {
            var active = SessionRecoveryStateTest.otherSession(A, SessionState.RETURNING, false, new UUID(round + 1, 0x71), new UUID(round + 1, 0x72), 7);
            var state = journal(sealed, active);
            cases.add(() -> assertEquals(active, SessionRecoveryManager.recordFor(state, A), "SEALED A 的已返還參與者只屬於活動 B"));
        }
        cases.add(() -> assertNull(SessionRecoveryManager.recordFor(journal(sealed), A), "只有 SEALED A：不找到任何 session"));
        cases.add(() -> assertNull(SessionRecoveryManager.recordFor(journal(ready(settled)), A), "PASSAGE_READY 全員已返還"));
        cases.add(() -> assertNull(SessionRecoveryManager.recordFor(journal(settled(measured(EXISTING, 1))), A), "DORMANT 不佔用"));
        var live = returned(measured(EXISTING, 2), A);
        cases.add(() -> assertEquals(live, SessionRecoveryManager.recordFor(journal(live), A), "非 DORMANT 的 MEASURED 仍佔用已返還者"));
        var partial = returned(collapsing(measured(EXISTING, 2)), A);
        cases.add(() -> assertNull(SessionRecoveryManager.recordFor(journal(partial), A), "COLLAPSING 的已返還者"));
        cases.add(() -> assertEquals(partial, SessionRecoveryManager.recordFor(journal(partial), B), "COLLAPSING 尚未返還者"));
        cases.add(() -> {
            var base = collapsing(measured(EXISTING, 2));
            var state = journal(base);
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING,
                    state.commitRollForward(() -> { throw new UncheckedIOException(new IOException("注入")); }, base, returned(base, A)).status());
            assertNull(SessionRecoveryManager.recordFor(state, A), "返還進度尚未確認：依 collapse view，已返還者不再屬於它");
            assertEquals(returned(base, A), SessionRecoveryManager.recordFor(state, B), "尚未返還者找到 collapse view 的 record");
        });
        assertAll(cases);
    }

    /** spec §5、§6：COLLAPSING 凍結尚未返還者；塌縮狀態只看 collapse view（已返還的人先於落盤解除）；PASSAGE_READY 與 SEALED 不凍結。 */
    @Test void frozenFollowsTheCollapseViewForCollapseStates() {
        var cases = new ArrayList<Executable>();
        var collapsing = collapsing(measured(EXISTING, 2));
        cases.add(() -> {
            var state = journal(collapsing);
            assertTrue(SessionRecoveryManager.frozen(state, A), "COLLAPSING 尚未返還者凍結");
            assertTrue(SessionRecoveryManager.frozen(state, B));
            assertFalse(SessionRecoveryManager.frozen(state, OUTSIDER));
        });
        cases.add(() -> {
            var state = journal(collapsing);
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING,
                    state.commitRollForward(() -> { throw new UncheckedIOException(new IOException("注入")); }, collapsing, returned(collapsing, A)).status());
            assertFalse(SessionRecoveryManager.frozen(state, A), "返還進度尚未確認：collapse view 已返還，解除凍結");
            assertTrue(SessionRecoveryManager.frozen(state, B), "另一位仍凍結");
        });
        var settled = settled(collapsing);
        for (var record : List.of(ready(settled), settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.ALLOCATION_IO_FAILED))))
            cases.add(() -> assertFalse(SessionRecoveryManager.frozen(journal(record), A), record.state() + " 不凍結"));
        cases.add(() -> assertTrue(SessionRecoveryManager.frozen(journal(measured(EXISTING, 1)), A), "M4：MEASURED 尚未返還者照舊凍結"));
        cases.add(() -> {
            var state = journal(measured(EXISTING, 1));
            var returning = SessionRecoveryStateTest.otherSession(B, SessionState.RETURNING, false, new UUID(0, 0x91), new UUID(0, 0x92), 9);
            state.put(returning);
            assertTrue(SessionRecoveryManager.frozen(state, B), "M4：只在 current 的 RETURNING 也凍結（current 或 flushed 任一份）");
        });
        assertAll(cases);
    }

    /**
     * spec §7.3、§6、§13.2：recovery tick 對 COLLAPSING 依 receipt 推導返還與清理（不需要請求），完成後沒有工作；roll-forward 尚未確認時只重試
     * 寫出、不從 flushed 疊寫新進度。PASSAGE_READY、SEALED 沒有返還工作；M4 的 RETURNING 與 MEASURED 維持原規則。
     */
    @Test void recoveryWorkDerivesCollapsingReturnFromTheReceiptAndOnlyRetriesUnconfirmedProgress() {
        var cases = new ArrayList<Executable>();
        var collapsing = collapsing(measured(EXISTING, 2));
        for (var record : List.of(collapsing, returned(collapsing, A), returned(collapsing, A, B))) for (boolean requested : List.of(false, true)) {
            var label = "COLLAPSING returned=" + record.participants().stream().filter(Participant::returned).count() + "/2 leases="
                    + record.spaceLeases().size() + " requested=" + requested;
            cases.add(() -> assertEquals(SessionRecoveryManager.RecoveryWork.RECOVER, SessionRecoveryManager.recoveryWork(journal(record), record, requested), label));
        }
        var settled = settled(collapsing);
        cases.add(() -> assertEquals(SessionRecoveryManager.RecoveryWork.NONE, SessionRecoveryManager.recoveryWork(journal(settled), settled, true), "SETTLED"));
        cases.add(() -> {
            var state = journal(collapsing);
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING,
                    state.commitRollForward(() -> { throw new UncheckedIOException(new IOException("注入")); }, collapsing, returned(collapsing, A)).status());
            assertEquals(SessionRecoveryManager.RecoveryWork.RETRY_FLUSH, SessionRecoveryManager.recoveryWork(state, state.flushedRecords().get(SESSION), false),
                    "返還進度尚未確認：只重試寫出");
        });
        var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        for (var record : List.of(ready(settled), sealed))
            cases.add(() -> assertEquals(SessionRecoveryManager.RecoveryWork.NONE, SessionRecoveryManager.recoveryWork(journal(record), record, true), record.state().name()));
        cases.add(() -> {
            var ready = ready(settled);
            var state = journal(ready);
            assertEquals(CommitResult.Status.ROLL_FORWARD_PENDING,
                    state.commitRollForward(() -> { throw new UncheckedIOException(new IOException("注入")); }, ready, crossed(ready, A)).status());
            assertEquals(SessionRecoveryManager.RecoveryWork.RETRY_FLUSH, SessionRecoveryManager.recoveryWork(state, state.flushedRecords().get(SESSION), true),
                    "PASSAGE_READY 的 Crossed 尚未確認：只重試寫出同一份 current");
        });
        var measured = measured(EXISTING, 1);
        cases.add(() -> assertEquals(SessionRecoveryManager.RecoveryWork.RECOVER, SessionRecoveryManager.recoveryWork(journal(measured), measured, true)));
        cases.add(() -> assertEquals(SessionRecoveryManager.RecoveryWork.NONE, SessionRecoveryManager.recoveryWork(journal(measured), measured, false),
                "MEASURED 仍須獨立 recovery 請求"));
        for (var phase : List.of(SessionState.ARMING, SessionState.SUPERPOSITION, SessionState.RETURNING)) cases.add(() -> {
            var record = SessionRecoveryStateTest.otherSession(A, phase, false, new UUID(0, 0xA1), new UUID(0, 0xA2), 10);
            assertEquals(phase == SessionState.RETURNING ? SessionRecoveryManager.RecoveryWork.RECOVER : SessionRecoveryManager.RecoveryWork.NONE,
                    SessionRecoveryManager.recoveryWork(journal(record), record, false), phase.name());
        });
        assertAll(cases);
    }

    // ---- fixtures ----

    static SessionRecoveryRecord measured(int choice, int people) { return measured(choice, people, false, false); }

    /** 只把凍結的 SourceFamilyRef 改成 Nether（Origin 仍在主世界）：§11.3 的判定只看 SourceFamilyRef.role()。 */
    static SessionRecoveryRecord measured(int choice, int people, boolean netherSource) { return measured(choice, people, netherSource, false); }

    /** MEASURED＋SELECTED（選中 ledger 第 choice 筆），people 位參與者都尚未返還，lease 原樣；兩個旗標分別把 SourceFamilyRef／Origin 改到 Nether。 */
    static SessionRecoveryRecord measured(int choice, int people, boolean netherSource, boolean netherOrigin) {
        var input = SessionRecoverySchema3Test.fixture(false, false);
        if (netherSource) {
            var source = SessionRecoverySchema3Test.context(input).getCompound("SourceFamilyRef");
            source.putString("WorldKey", "minecraft:the_nether"); source.putString("Role", "NETHER");
        }
        if (netherOrigin) {
            var origin = SessionRecoverySchema3Test.record(input).getCompound("Origin");
            origin.putString("WorldKey", "minecraft:the_nether"); origin.putString("Role", "NETHER");
        }
        var base = SessionRecoveryState.fromNbt(input).records().get(SESSION);
        var first = base.participants().getFirst();
        var cohort = new ArrayList<Participant>();
        for (int index = 0; index < people; index++) cohort.add(new Participant(PLAYERS.get(index), first.sourcePosition().add(index, 0, 0),
                first.sourceVelocity(), first.yaw(), first.pitch(), first.quantumStateSnapshot(), false));
        var entry = base.candidateLedger().get(choice);
        var selected = new CandidateSelection.Selected(entry.doorKey(), entry.candidate().candidateId(), A, 0, 1);
        return new SessionRecoveryRecord(base.sessionUuid(), base.chamberUuid(), base.origin(), cohort, base.spaceLeases(),
                SessionState.MEASURED, false, base.semantics(), base.candidateContext(), base.candidateLedger(), Optional.of(selected));
    }
    static CollapseDestination destination(int choice) {
        return switch (choice) {
            case SOURCE -> new CollapseDestination.Source();
            case NEW -> new CollapseDestination.Universe(NEW_ID);
            default -> new CollapseDestination.Universe(EXISTING_ID);
        };
    }
    /** 以 MEASURED 的選擇推導目的地，於 game time 40 提交 COLLAPSING（參與者與 lease 原樣）。 */
    static SessionRecoveryRecord collapsing(SessionRecoveryRecord measured) {
        var chosen = measured.candidateLedger().stream().filter(entry -> entry.doorKey()
                .equals(((CandidateSelection.Selected) measured.candidateSelection().orElseThrow()).doorKey())).findFirst().orElseThrow();
        int choice = measured.candidateLedger().indexOf(chosen);
        return measured.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(40, destination(choice)));
    }
    static SessionRecoveryRecord returned(SessionRecoveryRecord record, UUID... players) {
        var set = Set.of(players);
        var people = record.participants().stream().map(person -> set.contains(person.playerUuid()) ? new Participant(person.playerUuid(),
                person.sourcePosition(), person.sourceVelocity(), person.yaw(), person.pitch(), person.quantumStateSnapshot(), true) : person).toList();
        return record.withProgress(people, record.spaceLeases(), record.state(), record.restoreEntryEffectOnReturn());
    }
    static SessionRecoveryRecord cleared(SessionRecoveryRecord record) {
        return record.withProgress(record.participants(), List.of(), record.state(), record.restoreEntryEffectOnReturn());
    }
    /** 全員返還、lease 清空。 */
    static SessionRecoveryRecord settled(SessionRecoveryRecord record) {
        return cleared(returned(record, record.participants().stream().map(Participant::playerUuid).toArray(UUID[]::new)));
    }
    static SessionRecoveryRecord ready(SessionRecoveryRecord settled) { return ready(settled, 100); }
    static SessionRecoveryRecord ready(SessionRecoveryRecord settled, long openedAt) {
        return settled.withCollapse(SessionState.PASSAGE_READY, settled.collapse().withPassage(openedAt));
    }
    static SessionRecoveryRecord crossed(SessionRecoveryRecord ready, UUID player) {
        return ready.withCollapse(ready.state(), ready.collapse().withCrossed(player));
    }
    static SessionRecoveryRecord transfer(SessionRecoveryRecord ready, UUID player) {
        return ready.withCollapse(SessionState.SEALED, ready.collapse().transferRecoveryRequired(player));
    }
    static CollapseFields fields(CollapseFields base, long openedAt, List<UUID> crossed, List<UUID> recovery, Optional<SealedReason> reason) {
        return new CollapseFields(base.revision(), base.committedAtGameTime(), base.destination(), openedAt,
                openedAt == CollapseFields.UNSET ? CollapseFields.UNSET : openedAt + CollapseFields.PASSAGE_TICKS, crossed, recovery, reason);
    }
    static SessionRecoveryRecord withState(SessionRecoveryRecord record, SessionState state, CollapseFields collapse) {
        return new SessionRecoveryRecord(record.sessionUuid(), record.chamberUuid(), record.origin(), record.participants(), record.spaceLeases(),
                state, false, record.semantics(), record.candidateContext(), record.candidateLedger(), record.candidateSelection(), collapse);
    }

    static NbtCompound envelope(SessionRecoveryRecord... records) {
        var list = new NbtList(); for (var record : records) list.add(record.toNbt(4));
        var root = new NbtCompound(); root.putInt("SchemaVersion", 4); root.put("Records", list); return root;
    }
    /** strict 讀入 schema 4 envelope：current＝flushed，m5Evidence 為真、m5ReadyThisInstance 為假。 */
    static SessionRecoveryState journal(SessionRecoveryRecord... records) {
        var state = SessionRecoveryState.fromNbt(envelope(records)); state.requireHealthy(); return state;
    }
    private static NbtCompound row(NbtCompound root) { return SessionRecoverySchema3Test.record(root); }
    private static NbtCompound collapse(NbtCompound root) { return row(root).getCompound("Collapse"); }
    private static NbtList list(NbtElement... elements) { var list = new NbtList(); list.addAll(List.of(elements)); return list; }
    private static int schemaOf(SessionRecoveryState state) { return state.writeNbt(new NbtCompound()).getInt("SchemaVersion"); }
    private static Runnable save(SessionRecoveryState state, Path file) { return () -> state.save(file.toFile(), null); }
    private static Runnable neverFlush() { return () -> fail("被拒絕的提交不得寫出"); }
    private static void assertCommitted(CommitResult result) {
        assertEquals(CommitResult.Status.COMMITTED, result.status(), () -> "提交失敗：" + result.failure().map(Throwable::toString).orElse(""));
    }
    /** 讀 records／authorityHistory／checkedAuthority（與 testmod {@code M4CandidateTestAccess} 相同的欄位名）。 */
    @SuppressWarnings("unchecked")
    private static Map<UUID, SessionRecoveryRecord> field(SessionRecoveryState state, String name) throws ReflectiveOperationException {
        var handle = SessionRecoveryState.class.getDeclaredField(name); handle.setAccessible(true);
        return (Map<UUID, SessionRecoveryRecord>) handle.get(state);
    }
    /**
     * 以反射在 journal 背後把 current 換成 record，並設 roll-forward 旗標。正常 API 做不到：一般 put 拒絕 restore 邊，
     * roll-forward 旗標也只由一般 put 設定。只用來覆蓋「CAS 不符」與「雙重故障」這兩條防禦分支。
     */
    @SuppressWarnings("unchecked")
    private static void rollForwardBehindTheJournal(SessionRecoveryState state, SessionRecoveryRecord record) {
        try {
            field(state, "records").put(record.sessionUuid(), record);
            var handle = SessionRecoveryState.class.getDeclaredField("rollForwardPending"); handle.setAccessible(true);
            ((Set<UUID>) handle.get(state)).add(record.sessionUuid());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    /** 雙重故障：current 跨過 restore 邊（未 checked），同時帶著 roll-forward 旗標。 */
    private static void doubleFault(SessionRecoveryState state, SessionRecoveryRecord restoreResidue) {
        rollForwardBehindTheJournal(state, restoreResidue);
    }

    /** 只收 {@code quantumchamber} logger 的 log4j appender（比照 testmod 的 LogCapture）；先送探針，證明確實攔得到，不能以空結果冒充「沒有 WARN」。 */
    private static final class Logs extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.LoggerContext context;
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
        Logs() {
            super("f2a-log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
            context = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger("quantumchamber")).getContext();
            start();
            context.getConfiguration().getRootLogger().addAppender(this, null, null);
            context.updateLoggers();
            var probe = "F2A_LOG_CAPTURE_PROBE " + UUID.randomUUID();
            org.slf4j.LoggerFactory.getLogger("quantumchamber").warn(probe);
            if (!messages(Level.WARN).contains(probe)) { close(); throw new IllegalStateException("log capture 無法觀察 quantumchamber 的 WARN"); }
            events.clear();
        }
        @Override public void append(LogEvent event) { if ("quantumchamber".equals(event.getLoggerName())) events.add(event.toImmutable()); }
        List<String> messages(Level level) {
            return events(level).stream().map(event -> event.getMessage().getFormattedMessage()).toList();
        }
        List<LogEvent> events(Level level) {
            synchronized (events) { return events.stream().filter(event -> event.getLevel() == level).toList(); }
        }
        @Override public void close() {
            context.getConfiguration().getRootLogger().removeAppender(getName()); context.updateLoggers(); stop();
        }
    }

    /** 以 checked put→save 建立的 journal（M5 authority 已初始化）。 */
    private final class Disk {
        final Path file;
        final SessionRecoveryState state;
        private final boolean[] armed = {false};
        Disk(String name, SessionRecoveryRecord initial) {
            file = directory.resolve(name + ".dat");
            state = new SessionRecoveryState(path -> {
                if (armed[0]) { armed[0] = false; throw new IOException("注入：正式檔已原子寫入，readback 失敗"); }
                return SessionJournalStore.read(path);
            });
            state.markCollapseAuthoritiesInitialized();
            state.put(initial); state.save(file.toFile(), null);
        }
        Runnable flush() { return save(state, file); }
        /** atomic move 已完成、讀回失敗。 */
        Runnable failingReadback() { return () -> { armed[0] = true; state.save(file.toFile(), null); }; }
        /** 寫入前就失敗（父路徑是一般檔案）。 */
        Runnable blocked() {
            return () -> {
                var blocker = directory.resolve(file.getFileName() + ".blocker");
                try { if (!Files.exists(blocker)) Files.writeString(blocker, "not a directory"); }
                catch (IOException failure) { throw new UncheckedIOException(failure); }
                state.save(blocker.resolve("journal.dat").toFile(), null);
            };
        }
        Map<UUID, SessionRecoveryRecord> disk() {
            var loaded = SessionRecoveryState.load(file); loaded.requireHealthy(); return loaded.flushedRecords();
        }
    }
}
