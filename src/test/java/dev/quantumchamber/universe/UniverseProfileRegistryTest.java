package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

class UniverseProfileRegistryTest {
    private static UniverseProfileProvider provider(String id, int version) {
        return new UniverseProfileProvider() {
            public String id() { return id; }
            public int version() { return version; }
            public ParameterSchema parameterSchema() { return parameters -> {}; }
            public String adapterCapabilities() { return "minecraft121"; }
            public ResolvedSettings resolveGenerationSettings(NbtCompound parameters, ServerSettingsContext context) {
                return new ResolvedSettings(Map.of(), true);
            }
        };
    }

    @Test void registryRejectsLateOrDuplicateRegistration() {
        var registry = new UniverseProfileRegistry();
        var first = provider("quantumchamber:vanilla_normal_v1", 1);
        registry.register(first);
        assertSame(first, registry.find(first.id(), 1).orElseThrow());
        var metadata = registry.metadata(first.id(), 1).orElseThrow();
        assertEquals(first.id(), metadata.id());
        assertEquals(1, metadata.version());
        assertEquals("minecraft121", metadata.adapterCapabilities());
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider(first.id(), 1)));
        registry.freeze();
        assertThrows(IllegalStateException.class, () -> registry.register(provider("quantumchamber:other", 1)));
        assertSame(first, registry.find(first.id(), 1).orElseThrow());
    }

    @Test void registryRejectsInvalidMetadata() {
        var registry = new UniverseProfileRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider("not-namespaced", 1)));
        assertThrows(IllegalArgumentException.class, () -> registry.register(provider("quantumchamber:okay", 0)));
    }

    @Test void unresolvedSnapshotFailsClosed() {
        var registry = new UniverseProfileRegistry();
        assertThrows(IllegalStateException.class, () -> registry.createSnapshot("quantumchamber:vanilla_normal_v1", 1, new NbtCompound(), null));
    }
}
