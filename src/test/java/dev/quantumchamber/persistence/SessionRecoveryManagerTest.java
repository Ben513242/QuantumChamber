package dev.quantumchamber.persistence;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SessionRecoveryManagerTest {
    private static final UUID SESSION=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OLDER=UUID.fromString("00000000-0000-0000-0000-000000000002");

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
