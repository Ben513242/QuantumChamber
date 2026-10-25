package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.quantumchamber.gametest.M6RaceGameTests.NativeHarness;
import dev.quantumchamber.gametest.M6RaceGameTests.NativeHarness.Kind;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 正式Fabric mixin下的原版Fortress整輪；真世界與/place由GameTest另證。 */
final class NetherFortressGenerationConcurrencyTest {
    private static FlatChunkGenerator generator;

    @BeforeAll static void bootstrap() throws Exception {
        // 沿用同一classes-only來源、無testmod資源與正式mod載入檢查。
        StrongholdGenerationConcurrencyTest.bootstrap();
        var biome = BuiltinRegistries.createWrapperLookup().getWrapperOrThrow(RegistryKeys.BIOME).getOrThrow(BiomeKeys.PLAINS);
        generator = new FlatChunkGenerator(new FlatChunkGeneratorConfig(Optional.empty(), biome, List.of()));
    }

    private static Structure.Context context(long seed, int x, int z) {
        // 9參數原版constructor以seed/chunkPos建立並初始化ChunkRandom；不可傳未初始化random。
        return new Structure.Context(DynamicRegistryManager.EMPTY, generator, generator.getBiomeSource(), null, null,
                seed, new ChunkPos(x, z), HeightLimitView.create(-64, 384), biome -> true);
    }

    @Test void twoWorldFortressesKeepIndependentCounts() throws InterruptedException {
        NativeHarness.race(context(8128, 32, 32), context(481516, -32, -32), Kind.FORTRESS).requireSafe();
    }

    @Test void singleWorldFortressLayoutUnchanged() {
        var baseline = NativeHarness.generate(context(8128, 32, 32), Kind.FORTRESS);
        var expected = NativeHarness.layout(baseline);
        assertEquals("0c60781b533c2b0d82e2f36835d9174a58cc1b72a82cf5035d1cf97150b0149b", expected, "修正前原版完整piece NBT基準");
        assertEquals(155, baseline.toList().pieces().size(), "修正前原版piece總數");
        System.out.println("M6_FORTRESS_BASELINE seed=8128 chunk=32,32 sha256=" + expected + " count="
                + baseline.toList().pieces().size() + " counts=" + NativeHarness.pieceCounts(baseline));
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32), Kind.FORTRESS)), "原版固定seed布局漂移");
        }
    }

    @Test void exceptionReleasesInvocationAndWorkerCanBeReused() throws InterruptedException {
        var expected = NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32), Kind.FORTRESS));
        var failure = new AtomicReference<Throwable>();
        var actual = new AtomicReference<String>();
        var worker = NativeHarness.worker("M6Fortress-reused-worker", () -> {
            var injected = assertThrows(IllegalArgumentException.class, () -> NativeHarness.generate(context(8128, 32, 32), new StructurePiecesCollector() {
                @Override public void addPiece(StructurePiece piece) { super.addPiece(piece); throw new IllegalArgumentException("M6_CONTROLLED_FORTRESS_COLLECTOR_FAILURE"); }
            }, Kind.FORTRESS));
            assertEquals("M6_CONTROLLED_FORTRESS_COLLECTOR_FAILURE", injected.getMessage());
            actual.set(NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32), Kind.FORTRESS)));
            assertEquals(expected, actual.get(), "例外後同worker布局漂移");
        }, failure);
        NativeHarness.join(worker); NativeHarness.rethrow(failure.get());
        var other = NativeHarness.worker("M6Fortress-after-exception-worker", () -> actual.set(NativeHarness.layout(
                NativeHarness.generate(context(8128, 32, 32), Kind.FORTRESS))), failure);
        NativeHarness.join(other); NativeHarness.rethrow(failure.get());
        assertEquals(expected, actual.get(), "例外後另一worker布局漂移或未釋放monitor");
    }
}
