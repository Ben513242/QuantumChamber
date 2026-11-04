package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UniverseProfileRegistryTest {
    @BeforeAll static void bootstrap() { Minecraft121WorldgenRegistryFixture.bootstrap(); }
    private static final String NORMAL = Minecraft121VanillaPresetProfileProvider.NORMAL_ID;
    private static final String AMPLIFIED = Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID;
    private static NbtCompound params(String preset, boolean structures) {
        var value = new NbtCompound();
        value.putString("OverworldPreset", preset);
        value.putByte("GenerateStructures", (byte) (structures ? 1 : 0));
        return value;
    }
    private static UniverseProfileRegistry builtin() {
        var result = new UniverseProfileRegistry();
        result.register(new Minecraft121VanillaPresetProfileProvider(NORMAL));
        result.register(new Minecraft121VanillaPresetProfileProvider(AMPLIFIED));
        result.freeze();
        return result;
    }
    private static final class Lease implements ServerSettingsContext {
        final DynamicRegistryManager.Immutable registries;
        final AtomicInteger checks = new AtomicInteger();
        int failAt = Integer.MAX_VALUE;
        Lease(DynamicRegistryManager.Immutable registries) { this.registries = registries; }
        @Override public void requireValid() {
            if (checks.incrementAndGet() >= failAt) throw new IllegalStateException("CONTEXT_UNAVAILABLE");
        }
        @Override public DynamicRegistryManager.Immutable registries() { requireValid(); return registries; }
        @Override public boolean sourceGenerateStructures() { requireValid(); return true; }
    }
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

    @Test void createSnapshotRequiresFrozenRegistry() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = new UniverseProfileRegistry();
            registry.register(new Minecraft121VanillaPresetProfileProvider(NORMAL));
            assertThrows(IllegalStateException.class, () -> registry.createSnapshot(NORMAL, 1, params("normal", true), new Lease(f.registries())));
            registry.freeze();
            assertEquals(NORMAL, registry.createSnapshot(NORMAL, 1, params("normal", true), new Lease(f.registries())).providerId());
        }
    }
    @Test void createSnapshotRejectsUnknownProviderOrVersion() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            assertThrows(IllegalStateException.class, () -> registry.createSnapshot("quantumchamber:unknown", 1, params("normal", true), lease));
            assertThrows(IllegalStateException.class, () -> registry.createSnapshot(NORMAL, 2, params("normal", true), lease));
            assertEquals(32, registry.createSnapshot(NORMAL, 1, params("normal", true), lease).outputFingerprint().length);
        }
    }
    @Test void createSnapshotRejectsUnboundClosureProvider() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = new UniverseProfileRegistry();
            registry.register(provider("quantumchamber:extension", 1));
            registry.register(provider(NORMAL, 1));
            registry.freeze();
            assertEquals("PROFILE_CLOSURE_UNBOUND", assertThrows(IllegalStateException.class,
                    () -> registry.createSnapshot("quantumchamber:extension", 1, params("normal", true), new Lease(f.registries()))).getMessage());
            assertEquals("PROFILE_CLOSURE_UNBOUND", assertThrows(IllegalStateException.class,
                    () -> registry.createSnapshot(NORMAL, 1, params("normal", true), new Lease(f.registries()))).getMessage());
            assertEquals(32, builtin().createSnapshot(NORMAL, 1, params("normal", true), new Lease(f.registries())).outputFingerprint().length);
        }
    }
    @Test void createSnapshotRequiresValidLeaseBeforeAndAfterResolve() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin();
            var before = new Lease(f.registries()); before.failAt = 1;
            assertThrows(IllegalStateException.class, () -> registry.createSnapshot(NORMAL, 1, params("normal", true), before));
            var direct = new Lease(f.registries());
            var parameters = params("normal", true);
            var provider = new Minecraft121VanillaPresetProfileProvider(NORMAL);
            var resolved = provider.resolveGenerationSettings(parameters, direct);
            new Minecraft121ShallowSettingsCanonicalizer().canonicalize(resolved, parameters, direct);
            var after = new Lease(f.registries()); after.failAt = direct.checks.get() + 2;
            assertThrows(IllegalStateException.class, () -> registry.createSnapshot(NORMAL, 1, params("normal", true), after));
        }
    }
    @Test void createSnapshotRejectsInvalidParameters() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            var extra = params("normal", true); extra.putInt("Extra", 1);
            assertThrows(IllegalArgumentException.class, () -> registry.createSnapshot(NORMAL, 1, extra, lease));
            assertThrows(IllegalArgumentException.class, () -> registry.createSnapshot(NORMAL, 1, params("amplified", true), lease));
            assertThrows(IllegalArgumentException.class, () -> registry.createSnapshot(NORMAL, 1, null, lease));
            assertEquals(32, registry.createSnapshot(NORMAL, 1, params("normal", true), lease).outputFingerprint().length);
        }
    }
    @Test void resolveRejectsFingerprintMismatch() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            var original = registry.createSnapshot(NORMAL, 1, params("normal", true), lease);
            var changedParameter = original.toNbt(); changedParameter.getCompound("Parameters").putByte("GenerateStructures", (byte) 0);
            assertThrows(IllegalStateException.class, () -> registry.resolve(GenerationSettingsSnapshot.fromNbt(changedParameter), lease));
            var changedFingerprint = original.toNbt(); var bytes = changedFingerprint.getByteArray("OutputFingerprint"); bytes[0] ^= 1;
            changedFingerprint.putByteArray("OutputFingerprint", bytes);
            assertThrows(IllegalStateException.class, () -> registry.resolve(GenerationSettingsSnapshot.fromNbt(changedFingerprint), lease));
            var mismatchedPreset = original.toNbt();
            mismatchedPreset.getCompound("Parameters").putString("OverworldPreset", "amplified");
            assertThrows(IllegalArgumentException.class,
                    () -> registry.resolve(GenerationSettingsSnapshot.fromNbt(mismatchedPreset), lease));
            assertEquals(3, registry.resolve(original, lease).roles().size());
        }
    }
    @Test void resolveRejectsUnknownProviderOrVersion() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            var nbt = registry.createSnapshot(NORMAL, 1, params("normal", true), lease).toNbt();
            nbt.putString("ProviderId", "quantumchamber:unknown");
            assertThrows(IllegalStateException.class, () -> registry.resolve(GenerationSettingsSnapshot.fromNbt(nbt), lease));
            nbt.putString("ProviderId", NORMAL); nbt.putInt("ProviderVersion", 2);
            assertThrows(IllegalStateException.class, () -> registry.resolve(GenerationSettingsSnapshot.fromNbt(nbt), lease));
        }
    }
    @Test void resolveReturnsThreeRolesAndFrozenGenerateStructures() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            var snapshot = registry.createSnapshot(AMPLIFIED, 1, params("amplified", false), lease);
            var resolved = registry.resolve(snapshot, lease);
            assertEquals(3, resolved.roles().size());
            assertTrue(resolved.roles().keySet().containsAll(Arrays.asList(DimensionRole.values())));
            assertFalse(resolved.generateStructures());
            assertThrows(UnsupportedOperationException.class, () -> resolved.roles().clear());
        }
    }
    @Test void snapshotNbtRoundTripKeepsFingerprintAndResolves() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var registry = builtin(); var lease = new Lease(f.registries());
            var original = registry.createSnapshot(NORMAL, 1, params("normal", true), lease);
            var restored = GenerationSettingsSnapshot.fromNbt(original.toNbt());
            assertArrayEquals(original.outputFingerprint(), restored.outputFingerprint());
            assertEquals(3, registry.resolve(restored, lease).roles().size());
        }
    }
}
