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

    @Test void worldPublishWaitsForWorldsMonitor() throws Exception { verifyWorldMapWrite(false); }

    @Test void worldRemovalWaitsForWorldsMonitor() throws Exception { verifyWorldMapWrite(true); }

    private static void verifyWorldMapWrite(boolean remove) throws Exception {
        var map = new java.util.LinkedHashMap<String, Object>();
        var value = new Object();
        if (remove) map.put("owned", value);
        var method = dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend.class.getDeclaredMethod(
                remove ? "removeWorld" : "putWorldIfAbsent", java.util.Map.class, Object.class, Object.class);
        method.setAccessible(true);
        var attempted = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<Object> future;
            synchronized (map) {
                future = executor.submit(() -> { attempted.countDown(); return method.invoke(null, map, "owned", value); });
                assertTrue(attempted.await(10, java.util.concurrent.TimeUnit.SECONDS));
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> future.get(500, java.util.concurrent.TimeUnit.MILLISECONDS),
                        "worlds monitor 釋放前不得發布或移除世界");
            }
            var result = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            if (remove) {
                assertEquals(Boolean.TRUE, result);
                assertFalse(map.containsKey("owned"));
            } else {
                assertNull(result);
                assertSame(value, map.get("owned"));
            }
        } finally {
            executor.shutdown();
            assertTrue(executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    private static UniverseWorldDescriptor descriptor(long id, SeedPolicy policy) {
        return new UniverseWorldDescriptor(UniverseKeys.world(UniverseId.of(new UUID(0, id)), DimensionRole.OVERWORLD),
                DimensionRole.OVERWORLD, policy == SeedPolicy.SHARED_SAVE_SEED_V1
                    ? GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1 : null, policy, 1);
    }
}
