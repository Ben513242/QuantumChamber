package dev.quantumchamber.persistence;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryManagerTest {
    @org.junit.jupiter.api.BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }
    private static final UUID SESSION=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OLDER=UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test void measuredRecoveryDerivesPlayerAndGeometryPhasesWithoutChangingSelection() {
        var record=measured();
        assertEquals(MeasuredRecoveryPhase.RETURN_PLAYERS,MeasuredRecoveryPhase.from(record));
        var player=record.participants().getFirst().playerUuid();
        var returned=SessionRecoveryManager.returnedRecord(record,player);
        assertEquals(SessionState.MEASURED,returned.state());
        assertTrue(returned.participants().getFirst().returned());
        assertEquals(record.candidateContext(),returned.candidateContext());
        assertEquals(record.candidateLedger(),returned.candidateLedger());
        assertEquals(record.candidateSelection(),returned.candidateSelection());
        assertEquals(MeasuredRecoveryPhase.RELEASE_GEOMETRY,MeasuredRecoveryPhase.from(returned));
        assertEquals(returned,SessionRecoveryManager.returnedRecord(returned,player));
        assertThrows(IllegalArgumentException.class,() -> SessionRecoveryManager.returnedRecord(record,UUID.randomUUID()));
    }

    @Test void dormantRequiresMeasuredSelectedAndEveryParticipantReturned() {
        var record=measured();
        assertThrows(IllegalArgumentException.class,() -> record.withProgress(record.participants(),List.of(),record.state(),false));
        var complete=SessionRecoveryManager.returnedRecord(record,record.participants().getFirst().playerUuid());
        var dormant=complete.withProgress(complete.participants(),List.of(),SessionState.MEASURED,false);
        assertEquals(MeasuredRecoveryPhase.DORMANT,MeasuredRecoveryPhase.from(dormant));
        assertThrows(IllegalArgumentException.class,() -> dormant.withProgress(record.participants(),List.of(),SessionState.MEASURED,false));
        for(String state : List.of("ARMING","SUPERPOSITION","RETURNING")) {
            var legacy=SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture(state,state.equals("ARMING"))).records().values().iterator().next();
            assertThrows(IllegalArgumentException.class,() -> legacy.withProgress(complete.participants(),List.of(),legacy.state(),false));
            assertThrows(IllegalArgumentException.class,() -> MeasuredRecoveryPhase.from(legacy));
        }
        var selectable=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false,false)).records().values().iterator().next();
        assertThrows(IllegalArgumentException.class,() -> selectable.withProgress(complete.participants(),List.of(),SessionState.RETURNING,false));
    }

    @Test void measuredReceiptSurvivesRepeatedRestartAndCannotBeRemoved(@TempDir Path root) {
        var record=measured();
        var state=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(true,false));
        var returned=SessionRecoveryManager.returnedRecord(record,record.participants().getFirst().playerUuid());
        state.put(returned.withProgress(returned.participants(),List.of(),SessionState.MEASURED,false));
        var expected=state.records().get(record.sessionUuid());
        for(int i=0;i<3;i++) {
            state.save(root.resolve("journal.dat").toFile(),null);
            state=SessionRecoveryState.load(root.resolve("journal.dat")); state.requireHealthy();
            assertEquals(expected,state.flushedRecords().get(record.sessionUuid()));
            assertEquals(MeasuredRecoveryPhase.DORMANT,MeasuredRecoveryPhase.from(state.flushedRecords().get(record.sessionUuid())));
            var current=state;
            assertThrows(IllegalStateException.class,() -> current.remove(record.sessionUuid()));
            assertEquals(expected,state.records().get(record.sessionUuid()));
        }
    }

    @Test void measuredProgressCannotRegressOrAppendCandidates() {
        var initial=measured();
        var state=new SessionRecoveryState(); state.put(initial);
        var next=SessionRecoveryManager.returnedRecord(initial,initial.participants().getFirst().playerUuid()); state.put(next);
        assertThrows(IllegalArgumentException.class,() -> state.put(initial));
        var entries=new ArrayList<>(next.candidateLedger());
        entries.add(new CandidateLedgerEntry(new DoorKey(next.sessionUuid(),99,DoorKey.DoorWallSide.POSITIVE_LATERAL),
                new QuantumCandidate.Source(new CandidateId(new CandidateBytes(SessionRecoverySchema3Test.bytes(80))))));
        var appended=new SessionRecoveryRecord(next.sessionUuid(),next.chamberUuid(),next.origin(),next.participants(),next.spaceLeases(),
                next.state(),false,next.semantics(),next.candidateContext(),entries,next.candidateSelection());
        assertFalse(SessionRecoveryRecord.sameAuthority(next,appended));
        assertThrows(IllegalArgumentException.class,() -> state.put(appended));
        assertEquals(next,state.records().get(next.sessionUuid()));
    }

    @Test void selectableAndLegacyReturningStillDeleteNormally(@TempDir Path root) {
        for(boolean candidate : new boolean[]{false,true}) {
            var state=SessionRecoveryState.fromNbt(candidate ? SessionRecoverySchema3Test.fixture(false,false)
                    : SessionRecoveryStateTest.fixture("RETURNING",false));
            var initial=state.records().values().iterator().next();
            var returning=initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.RETURNING,false);
            state.put(SessionRecoveryManager.returnedRecord(returning,returning.participants().getFirst().playerUuid()));
            assertTrue(state.remove(initial.sessionUuid()));
            state.save(root.resolve("returning.dat").toFile(),null);
            assertTrue(SessionRecoveryState.load(root.resolve("returning.dat")).flushedRecords().isEmpty());
        }
    }

    private static SessionRecoveryRecord measured() {
        return SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(true,false)).records().values().iterator().next();
    }

    @Test void partialCohortMustRetainGeometryAndSelectedLeasesCannotRemap() {
        var one=measured(); var people=new ArrayList<>(one.participants()); var first=people.getFirst();
        people.add(new SessionRecoveryRecord.Participant(UUID.randomUUID(),first.sourcePosition().add(1,0,0),first.sourceVelocity(),
                first.yaw(),first.pitch(),first.quantumStateSnapshot(),false));
        var two=one.withProgress(people,one.spaceLeases(),SessionState.MEASURED,false);
        var partial=SessionRecoveryManager.returnedRecord(two,first.playerUuid());
        assertEquals(MeasuredRecoveryPhase.RETURN_PLAYERS,MeasuredRecoveryPhase.from(partial));
        assertThrows(IllegalArgumentException.class,() -> partial.withProgress(partial.participants(),List.of(),SessionState.MEASURED,false));
        var state=new SessionRecoveryState(); state.put(partial);
        var lease=partial.spaceLeases().getFirst();
        var remapped=partial.withProgress(partial.participants(),List.of(new SessionRecoveryRecord.SpaceLease(lease.slotId()+1,lease.bounds())),SessionState.MEASURED,false);
        assertThrows(IllegalArgumentException.class,() -> state.put(remapped));
    }

    @Test void failedCheckedSelectionIsRevertedBeforeRecoveryOrWorldSaveCanAdmitIt(@TempDir Path root) throws Exception {
        var state=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false,false));
        assertTrue(state.recoveryWritesSafe());
        var selected=measured();
        state.put(selected);
        assertFalse(state.recoveryWritesSafe(),"put→flush 同步窗口內的未 checked SELECTED 不可經 recovery 寫出");
        var blocker=root.resolve("blocker"); java.nio.file.Files.writeString(blocker,"not a directory");
        assertThrows(java.io.UncheckedIOException.class,() -> state.save(blocker.resolve("journal.dat").toFile(),null),"模擬 checked flush 失敗");
        assertTrue(state.abortUnchecked(selected),"ledger owner 以 CAS 還原未 checked selection");
        assertTrue(state.recoveryWritesSafe(),"還原後 recovery 不得停擺");
        // 原生 autosave／stop 與其他 session 的 flush 都走同一 save(File)；此時只能寫出 flushed SELECTABLE。
        state.save(root.resolve("world-save.dat").toFile(),null);
        var durable=SessionRecoveryState.load(root.resolve("world-save.dat")).flushedRecords().values().iterator().next();
        assertInstanceOf(CandidateSelection.Selectable.class,durable.candidateSelection().orElseThrow(),"world save 不得承認已回報失敗的 SELECTED");
        assertEquals(SessionState.SUPERPOSITION,durable.state());
        var flushed=state.flushedRecords().values().iterator().next();
        var returning=flushed.withProgress(flushed.participants(),flushed.spaceLeases(),SessionState.RETURNING,false);
        state.put(SessionRecoveryManager.returnedRecord(returning,returning.participants().getFirst().playerUuid()));
        assertTrue(state.recoveryWritesSafe(),"同一份 flushed authority 的返還進度可重試");
        var unsaved=new SessionRecoveryState(); unsaved.put(selected);
        assertFalse(unsaved.recoveryWritesSafe(),"沒有 flushed candidate authority 不得透過 recovery 推測");
    }

    @Test void checkpointSurvivingFailedReturnedFlushMustNotReapplyEntryEffect() throws Exception {
        var marker=new PlayerRecoveryCheckpoint(SESSION,PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY);
        assertFalse(SessionRecoveryManager.shouldApplyMarker(marker,Optional.of(marker)));
    }
    @Test void keepCurrentCheckpointMustNotTouchNewDoseAgain() throws Exception {
        var marker=new PlayerRecoveryCheckpoint(SESSION,PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
        assertFalse(SessionRecoveryManager.shouldApplyMarker(marker,Optional.of(marker)));
    }
    @Test void unmarkedParticipantNeedsExactlyTheFrozenPolicy() throws Exception {
        for(var policy : PlayerRecoveryCheckpoint.AppliedPolicy.values())
            assertTrue(SessionRecoveryManager.shouldApplyMarker(new PlayerRecoveryCheckpoint(SESSION,policy),Optional.empty()));
    }
    @Test void sameSessionWrongPolicyCannotBeOverwritten() {
        var restore=new PlayerRecoveryCheckpoint(SESSION,PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY);
        var keep=new PlayerRecoveryCheckpoint(SESSION,PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
        assertThrows(IOException.class,() -> SessionRecoveryManager.shouldApplyMarker(restore,Optional.of(keep)));
        assertThrows(IOException.class,() -> SessionRecoveryManager.shouldApplyMarker(keep,Optional.of(restore)));
    }
    @Test void completedOlderSessionMarkerDoesNotSuppressNewSessionPolicy() throws Exception {
        var previous=new PlayerRecoveryCheckpoint(OLDER,PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
        var next=new PlayerRecoveryCheckpoint(SESSION,PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY);
        assertTrue(SessionRecoveryManager.shouldApplyMarker(next,Optional.of(previous)));
    }
}
