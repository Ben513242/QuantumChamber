package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChamberActivationEvaluatorTest {
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final ChamberActivationEvaluator evaluator = new ChamberActivationEvaluator();

    @Test
    void returnsInvalidWhenDisabled() {
        assertEquals(ChamberState.INVALID, evaluator.evaluate(snapshot(false, true, true, List.of(eligible(FIRST)))));
    }

    @Test
    void returnsInvalidWhenStructureIsInvalid() {
        assertEquals(ChamberState.INVALID, evaluator.evaluate(snapshot(true, false, true, List.of(eligible(FIRST)))));
    }

    @Test
    void returnsIdleWhenValidChamberIsOpen() {
        assertEquals(ChamberState.IDLE, evaluator.evaluate(snapshot(true, true, false, List.of(eligible(FIRST)))));
    }

    @Test
    void returnsIdleWhenSealedChamberHasNoParticipants() {
        assertEquals(ChamberState.IDLE, evaluator.evaluate(snapshot(true, true, true, List.of())));
    }

    @Test
    void returnsIdleWhenAnyParticipantLacksQuantumState() {
        assertEquals(ChamberState.IDLE,
                evaluator.evaluate(snapshot(true, true, true, List.of(eligible(FIRST), ineligible(SECOND)))));
    }

    @Test
    void returnsReadyWhenEveryParticipantHasQuantumState() {
        assertEquals(ChamberState.READY,
                evaluator.evaluate(snapshot(true, true, true, List.of(eligible(FIRST), eligible(SECOND)))));
    }

    @Test
    void doesNotDependOnParticipantInputOrder() {
        ChamberActivationSnapshot firstOrder = snapshot(true, true, true, List.of(eligible(FIRST), ineligible(SECOND)));
        ChamberActivationSnapshot secondOrder = snapshot(true, true, true, List.of(ineligible(SECOND), eligible(FIRST)));

        assertEquals(evaluator.evaluate(firstOrder), evaluator.evaluate(secondOrder));
    }

    private static ChamberActivationSnapshot snapshot(
            boolean enabled,
            boolean structureValid,
            boolean sealed,
            List<ChamberActivationSnapshot.ParticipantEligibility> participants) {
        return new ChamberActivationSnapshot(enabled, structureValid, sealed, participants);
    }

    private static ChamberActivationSnapshot.ParticipantEligibility eligible(UUID playerUuid) {
        return new ChamberActivationSnapshot.ParticipantEligibility(playerUuid, true);
    }

    private static ChamberActivationSnapshot.ParticipantEligibility ineligible(UUID playerUuid) {
        return new ChamberActivationSnapshot.ParticipantEligibility(playerUuid, false);
    }
}
