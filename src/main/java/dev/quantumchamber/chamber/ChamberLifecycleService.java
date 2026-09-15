package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.UUID;

/** M1 lifecycle boundary: only enabled state can be changed. */
public final class ChamberLifecycleService {
    private final ChamberRegistry registry;

    public ChamberLifecycleService(ChamberRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public boolean setEnabled(UUID chamberUuid, boolean enabled) {
        return registry.setEnabled(chamberUuid, enabled);
    }
}
