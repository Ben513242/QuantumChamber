package dev.quantumchamber.compat;

import java.util.Objects;

public final class CompatibilityManager {
    private CompatibilityManager() {
    }

    public static RuntimeCompatibility detect(ModPresenceProbe probe) {
        Objects.requireNonNull(probe, "probe");
        return new RuntimeCompatibility(
                probe.isLoaded("sodium"),
                probe.isLoaded("iris"),
                probe.isLoaded("immersive_portals"));
    }
}
