package dev.quantumchamber.universe;

import dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.GenerationOwner;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** owner 決策層；原生 noise／biome／持久亂數結果另由 native chain 驗證。 */
class UniverseRootSeedOwnerTest {
    private final Object server = new Object();
    private final Object world = new Object();
    private final UniverseWorldDescriptor descriptor = descriptor(1, SeedPolicy.EXPLICIT_ROOT_SEED_V1);

    @Test void newUniverseGetSeedChangesAfterConstruction() {
        var owner = new GenerationOwner(server, descriptor, 7123L, false);
        owner.bind(server, descriptor, world);
        assertEquals(7123L, owner.rootSeed(server, descriptor, world, 11L));
        assertEquals(7123L, owner.rootSeed(server, descriptor, world, 12L));
    }

    @Test void noiseStructureBiomeUseFrozenRoot() {
        var owner = new GenerationOwner(server, descriptor, 7123L, false);
        owner.bind(server, descriptor, world);
        assertEquals(7123L, owner.rootSeed(server, descriptor, world, 11L));
        assertFalse(owner.generateStructures(server, descriptor, world, true));
        var enabled = new GenerationOwner(server, descriptor, 7123L, true);
        enabled.bind(server, descriptor, world);
        assertTrue(enabled.generateStructures(server, descriptor, world, false));
    }

    @Test void twoUniversesDoNotShareRandomSequences() {
        var first = new GenerationOwner(server, descriptor, 7123L, true);
        var second = new GenerationOwner(server, descriptor(2, SeedPolicy.EXPLICIT_ROOT_SEED_V1), 991L, false);
        assertTrue(first.independentSequences());
        assertTrue(second.independentSequences());
    }

    @Test void legacySchemaOneStillUsesSaveSeed() {
        var legacy = descriptor(3, SeedPolicy.SHARED_SAVE_SEED_V1);
        var owner = new GenerationOwner(server, legacy, 7123L, false);
        owner.bind(server, legacy, world);
        assertEquals(11L, owner.rootSeed(server, legacy, world, 11L));
        assertEquals(12L, owner.rootSeed(server, legacy, world, 12L));
        assertTrue(owner.generateStructures(server, legacy, world, true));
        assertFalse(owner.independentSequences());
    }

    @Test void foreignServerDescriptorAndWorldKeepVanilla() {
        var owner = new GenerationOwner(server, descriptor, 7123L, false);
        owner.bind(server, descriptor, world);
        assertEquals(11L, owner.rootSeed(new Object(), descriptor, world, 11L));
        assertEquals(11L, owner.rootSeed(server, descriptor(2, SeedPolicy.EXPLICIT_ROOT_SEED_V1), world, 11L));
        assertEquals(11L, owner.rootSeed(server, descriptor, new Object(), 11L));
        assertThrows(IllegalStateException.class, () -> owner.bind(server, descriptor, new Object()));
    }

    private static UniverseWorldDescriptor descriptor(long id, SeedPolicy policy) {
        return new UniverseWorldDescriptor(UniverseKeys.world(UniverseId.of(new UUID(0, id)), DimensionRole.OVERWORLD),
                DimensionRole.OVERWORLD, policy == SeedPolicy.SHARED_SAVE_SEED_V1
                    ? GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1 : null, policy, 1);
    }
}
