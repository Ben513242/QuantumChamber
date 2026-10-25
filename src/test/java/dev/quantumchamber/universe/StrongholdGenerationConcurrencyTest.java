package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.quantumchamber.gametest.M6RaceGameTests.NativeHarness;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.random.CheckedRandom;
import net.minecraft.util.math.random.ChunkRandom;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.structure.Structure;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Fabric JUnit 套用正式 mixin；使用兩個原生生成 context，真 ServerWorld 由 M6 GameTest 另證。 */
final class StrongholdGenerationConcurrencyTest {
    private static FlatChunkGenerator generator;

    @BeforeAll static void bootstrap() throws Exception {
        var loader = StrongholdGenerationConcurrencyTest.class.getClassLoader();
        var sharedClassSource = NativeHarness.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm();
        assertTrue(sharedClassSource.endsWith("/build/classes/java/testmod/"), "共享fixture必須來自本checkout的testmod classes");
        var manifests = Collections.list(loader.getResources("fabric.mod.json"));
        for (var resource : manifests) {
            try (var stream = resource.openStream()) {
                var id = JsonParser.parseString(new String(stream.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().get("id").getAsString();
                assertFalse(id.equals("quantumchamber-testmod"), "JUnit不得帶入testmod manifest：" + resource);
            }
        }
        assertFalse(loader.getResources("quantumchamber-test.mixins.json").hasMoreElements(), "JUnit不得帶入testmod mixin resources");
        assertFalse(FabricLoader.getInstance().isModLoaded("quantumchamber-testmod"), "JUnit不得註冊testmod entrypoint");
        assertTrue(FabricLoader.getInstance().isModLoaded("quantumchamber"), "此JUnit必須由Fabric載入正式mod與mixin");
        System.out.println("M6_STRONGHOLD_JUNIT_CP classes=" + sharedClassSource + " manifests=" + manifests
                + " mods=" + FabricLoader.getInstance().getAllMods().stream().map(mod -> mod.getMetadata().getId()).sorted().toList()
                + " testmodResources=false testmodMod=false");
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        var lookup = BuiltinRegistries.createWrapperLookup();
        var biome = lookup.getWrapperOrThrow(RegistryKeys.BIOME).getOrThrow(BiomeKeys.PLAINS);
        generator = new FlatChunkGenerator(new FlatChunkGeneratorConfig(Optional.empty(), biome, List.of()));
    }

    private static Structure.Context context(long seed, int x, int z) {
        return new Structure.Context(DynamicRegistryManager.EMPTY, generator, generator.getBiomeSource(), null, null,
                new ChunkRandom(new CheckedRandom(0)), seed, new ChunkPos(x, z), HeightLimitView.create(-64, 384), biome -> true);
    }

    @Test void twoWorldStrongholdsDoNotSharePieceCounts() throws InterruptedException {
        var result = NativeHarness.race(context(8128, 32, 32), context(481516, -32, -32));
        result.requireSafe();
    }

    @Test void singleWorldStrongholdLayoutUnchanged() {
        var baseline = NativeHarness.generate(context(8128, 32, 32));
        var expected = NativeHarness.layout(baseline);
        assertEquals("a80452213409b05c039da03127cc9864ae6f779722541e1256336131bb9bd21a", expected, "修正前原版固定seed的完整piece NBT基準");
        assertEquals(98, baseline.toList().pieces().size(), "修正前原版piece總數");
        System.out.println("M6_STRONGHOLD_BASELINE seed=8128 chunk=32,32 sha256=" + expected + " count=" + baseline.toList().pieces().size() + " counts=" + NativeHarness.pieceCounts(baseline));
        for (int i = 0; i < 3; i++) {
            assertEquals(expected, NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32))), "同一世界的固定seed布局漂移");
        }
        assertTrue(baseline.toList().pieces().stream().anyMatch(piece -> piece.getClass().getSimpleName().equals("PortalRoom")), "原版重試必須產生PortalRoom");
    }

    @Test void cleanupKeepsPrimaryFailureAndAttemptsEveryJoin() {
        var primary = new InterruptedException("M6_CONTROLLED_PRE_BARRIER_INTERRUPT");
        var firstJoin = new IllegalStateException("M6_CONTROLLED_FIRST_JOIN_FAILURE");
        var secondJoin = new IllegalArgumentException("M6_CONTROLLED_SECOND_JOIN_FAILURE");
        var order = new java.util.ArrayList<String>();
        var actual = assertThrows(Throwable.class, () -> NativeHarness.withCleanup(
                () -> { order.add("await"); throw primary; },
                () -> order.add("release"),
                () -> { order.add("join-A"); throw firstJoin; },
                () -> { order.add("join-B"); throw secondJoin; }));
        assertTrue(actual == primary, "M6_CLEANUP_PRIMARY_LOST：清理不得遮蔽原始屏障失敗");
        assertEquals(List.of("await", "release", "join-A", "join-B"), order, "M6_CLEANUP_JOIN_SKIPPED");
        assertEquals(List.of(firstJoin, secondJoin), List.of(actual.getSuppressed()), "M6_CLEANUP_SUPPRESSED_MISSING");
    }

    @Test void preBarrierFailureReleasesAndJoinsStartedWorker() throws InterruptedException {
        var release = new java.util.concurrent.CountDownLatch(1);
        var waiting = new java.util.concurrent.CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var worker = NativeHarness.worker("M6Stronghold-cleanup-pre-barrier", () -> {
            waiting.countDown(); NativeHarness.await(release, "cleanup release 遺失");
        }, failure);
        var primary = new InterruptedException("M6_CONTROLLED_PRE_BARRIER_INTERRUPT");
        var actual = assertThrows(InterruptedException.class, () -> NativeHarness.withCleanup(
                () -> { NativeHarness.await(waiting, "worker未啟動"); throw primary; },
                release::countDown, () -> NativeHarness.join(worker)));
        assertTrue(actual == primary, "原始中斷必須保留");
        assertFalse(worker.isAlive(), "M6_CLEANUP_WORKER_LEAK");
        NativeHarness.rethrow(failure.get());
    }

    @Test void cleanupWithoutPrimaryStillAttemptsLaterJoin() {
        var firstJoin = new IllegalStateException("M6_CONTROLLED_FIRST_JOIN_FAILURE");
        var order = new java.util.ArrayList<String>();
        var actual = assertThrows(Throwable.class, () -> NativeHarness.withCleanup(
                () -> order.add("action"), () -> order.add("release"),
                () -> { order.add("join-A"); throw firstJoin; }, () -> order.add("join-B")));
        assertTrue(actual == firstJoin, "首個清理失敗必須保留");
        assertEquals(List.of("action", "release", "join-A", "join-B"), order, "M6_CLEANUP_JOIN_SKIPPED");
    }

    @Test void exceptionReleasesInvocationAndWorkerCanBeReused() throws InterruptedException {
        var expected = NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32)));
        var failure = new AtomicReference<Throwable>();
        var actual = new AtomicReference<String>();
        var worker = NativeHarness.worker("M6Stronghold-reused-worker", () -> {
            var injected = assertThrows(IllegalArgumentException.class, () -> NativeHarness.generate(context(8128, 32, 32), new StructurePiecesCollector() {
                @Override public void addPiece(StructurePiece piece) { super.addPiece(piece); throw new IllegalArgumentException("M6_CONTROLLED_COLLECTOR_FAILURE"); }
            }));
            assertEquals("M6_CONTROLLED_COLLECTOR_FAILURE", injected.getMessage());
            actual.set(NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32))));
            assertEquals(expected, actual.get(), "例外後同worker的下一任務布局漂移");
        }, failure);
        NativeHarness.join(worker); NativeHarness.rethrow(failure.get());
        // 改用另一worker也須完成，防止例外僅在同一可重入monitor owner上掩蓋漏鎖。
        var other = NativeHarness.worker("M6Stronghold-after-exception-worker", () -> actual.set(NativeHarness.layout(NativeHarness.generate(context(8128, 32, 32)))), failure);
        NativeHarness.join(other); NativeHarness.rethrow(failure.get());
        assertEquals(expected, actual.get(), "例外後worker重用或另一worker產生布局漂移");
    }

}
