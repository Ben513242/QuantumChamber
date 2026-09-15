package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.UUID;

public record ChamberRegistrationResult(Status status, UUID chamberUuid) {
    public enum Status {
        CREATED,
        EXISTING,
        OVERLAP
    }

    public ChamberRegistrationResult {
        Objects.requireNonNull(status, "status");
    }
}
