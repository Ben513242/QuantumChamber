package dev.quantumchamber.chamber;

import java.util.Set;

public record ChamberStructureResult(boolean valid, boolean sealed, Set<Failure> failures) {
    public ChamberStructureResult {
        failures = Set.copyOf(failures);
    }

    public enum Failure {
        CONTROLLER_MISSING,
        SHELL_MISSING,
        BULKHEAD_MISSING,
        BULKHEAD_MIXED
    }
}
