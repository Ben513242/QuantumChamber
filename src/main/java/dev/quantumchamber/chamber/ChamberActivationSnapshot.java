package dev.quantumchamber.chamber;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable input to the pure chamber-readiness decision. */
public record ChamberActivationSnapshot(
        boolean enabled,
        boolean structureValid,
        boolean sealed,
        List<ParticipantEligibility> participants) {
    public ChamberActivationSnapshot {
        participants = List.copyOf(Objects.requireNonNull(participants, "participants"));
    }

    /** The activation-relevant state for one detected participant. */
    public record ParticipantEligibility(UUID playerUuid, boolean quantumState) {
        public ParticipantEligibility {
            Objects.requireNonNull(playerUuid, "playerUuid");
        }
    }
}
