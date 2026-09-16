package dev.quantumchamber.chamber;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Outcome of one requested M1 arming attempt; it never consumes effects or creates a session. */
public record ArmAttemptResult(
        boolean accepted,
        ChamberState readiness,
        List<UUID> participantUuids,
        Set<Failure> failureReasons) {
    public ArmAttemptResult {
        Objects.requireNonNull(readiness, "readiness");
        participantUuids = List.copyOf(Objects.requireNonNull(participantUuids, "participantUuids"));
        failureReasons = Set.copyOf(Objects.requireNonNull(failureReasons, "failureReasons"));
        if (accepted != (readiness == ChamberState.READY)) {
            throw new IllegalArgumentException("only READY attempts may be accepted");
        }
        if (accepted && !failureReasons.isEmpty()) {
            throw new IllegalArgumentException("accepted attempts cannot have failure reasons");
        }
    }

    public enum Failure {
        UNSUPPORTED_DIMENSION,
        INVALID_STRUCTURE,
        ORIGIN_OVERLAP,
        DISABLED,
        UNSEALED,
        NO_PARTICIPANTS,
        MISSING_QUANTUM_STATE
    }
}
