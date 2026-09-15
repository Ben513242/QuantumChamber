package dev.quantumchamber.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityManagerTest {
    @Test
    void detectsOptionalRenderAndPortalModsWithoutLoadingTheirClasses() {
        Set<String> loaded = Set.of("sodium", "iris");

        RuntimeCompatibility result = CompatibilityManager.detect(loaded::contains);

        assertTrue(result.sodiumLoaded());
        assertTrue(result.irisLoaded());
        assertFalse(result.immersivePortalsLoaded());
    }

    @Test
    void defaultsToNoOptionalModsWhenNoneArePresent() {
        RuntimeCompatibility result = CompatibilityManager.detect(modId -> false);

        assertFalse(result.sodiumLoaded());
        assertFalse(result.irisLoaded());
        assertFalse(result.immersivePortalsLoaded());
    }
}
