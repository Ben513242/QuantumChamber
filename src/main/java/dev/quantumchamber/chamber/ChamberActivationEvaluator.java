package dev.quantumchamber.chamber;

import java.util.Objects;

/** Applies M1 activation prerequisites without accessing a Minecraft world. */
public final class ChamberActivationEvaluator {
    public ChamberState evaluate(ChamberActivationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.enabled() || !snapshot.structureValid()) {
            return ChamberState.INVALID;
        }
        if (!snapshot.sealed() || snapshot.participants().isEmpty()
                || snapshot.participants().stream().anyMatch(participant -> !participant.quantumState())) {
            return ChamberState.IDLE;
        }
        return ChamberState.READY;
    }
}
