package dev.quantumchamber.universe;

import java.util.Objects;
import java.util.UUID;

public record UniverseId(UUID value) {
    public UniverseId {
        Objects.requireNonNull(value, "value");
    }

    public static UniverseId of(UUID value) {
        return new UniverseId(value);
    }
}
