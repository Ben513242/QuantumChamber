package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.quantumchamber.gametest.M6RaceGameTests.NativeHarness;
import java.util.concurrent.atomic.AtomicReference;

import dev.quantumchamber.gametest.M6RaceGameTests.EndCityHarness;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.gen.chunk.FlatChunkGenerator;
import net.minecraft.world.gen.chunk.FlatChunkGeneratorConfig;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.level.storage.LevelStorage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 正式 mixin 的五參數 End city 入口與真原版模板；真世界另由 GameTest 驗。 */
final class EndCityGenerationConcurrencyTest {
    private static FlatChunkGenerator generator;
    private static StructureTemplateManager templates;
    private static LevelStorage.Session session;

    @BeforeAll static void bootstrap() throws Exception {
        StrongholdGenerationConcurrencyTest.bootstrap();
        var biome = BuiltinRegistries.createWrapperLookup().getWrapperOrThrow(RegistryKeys.BIOME).getOrThrow(BiomeKeys.PLAINS);
        generator = new FlatChunkGenerator(new FlatChunkGeneratorConfig(Optional.empty(), biome, List.of()));
        var checkout = Path.of(EndCityHarness.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                .getParent().getParent().getParent().getParent();
        var root = checkout.resolve(".superpowers/sdd/2026-10-24-m6-universe-generation/task-06/template-fixture-" + UUID.randomUUID());
        session = LevelStorage.create(root).createSession("fixture");
        templates = new StructureTemplateManager(ResourceManager.Empty.INSTANCE, session, net.minecraft.datafixer.Schemas.getFixer(), Registries.BLOCK.getReadOnlyWrapper());
        var field = StructureTemplateManager.class.getDeclaredField("templates"); field.setAccessible(true);
        @SuppressWarnings("unchecked") var cache = (Map<Identifier, Optional<StructureTemplate>>) field.get(templates);
        // 全部20個固定原版模板，不允許 getTemplateOrBlank 默默補空模板。
        for (var name : List.of("tower_top", "tower_piece", "tower_floor", "tower_base", "third_roof", "third_floor_2",
                "third_floor_1", "ship", "second_roof", "second_floor_2", "second_floor_1", "fat_tower_top", "fat_tower_middle",
                "fat_tower_base", "bridge_steep_stairs", "bridge_piece", "bridge_gentle_stairs", "bridge_end", "base_roof", "base_floor")) {
            var resource = "/data/minecraft/structure/end_city/" + name + ".nbt";
            byte[] bytes;
            try (var stream = StructureTemplate.class.getResourceAsStream(resource)) {
                if (stream == null) throw new IllegalStateException("M6_END_CITY_NATIVE_TEMPLATE_MISSING " + resource);
                bytes = stream.readAllBytes();
            }
            var nbt = NbtIo.readCompressed(new java.io.ByteArrayInputStream(bytes), NbtSizeTracker.ofUnlimitedBytes());
            var template = templates.createTemplate(nbt);
            assertTrue(template.getSize().getX() > 0 && template.getSize().getY() > 0 && template.getSize().getZ() > 0, "原版template不得為空：" + resource);
            cache.put(Identifier.ofVanilla("end_city/" + name), Optional.of(template));
            System.out.println("M6_END_CITY_TEMPLATE resource=" + resource + " bytes=" + bytes.length + " size=" + template.getSize()
                    + " dataVersion=" + nbt.getInt("DataVersion") + " sha256=" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
        System.out.println("M6_END_CITY_TEMPLATE_ROOT " + root);
    }

    @AfterAll static void closeSession() throws Exception { if (session != null) session.close(); }

    private static Structure.Context context(long seed, int x, int z) {
        return new Structure.Context(DynamicRegistryManager.EMPTY, generator, generator.getBiomeSource(), null, templates,
                seed, new ChunkPos(x, z), HeightLimitView.create(-64, 384), biome -> true);
    }

    @Test void singleWorldEndCityLayoutUnchanged() {
        var baseline = EndCityHarness.generate(context(481516, -32, -32));
        var expected = "d06329e10c9031569b17660abba5d85069ee705fc05145321b5b99daa543925a;count=94;ships=1;rng=-1076416987575963468;rotation=CLOCKWISE_90";
        assertEquals(expected, baseline.signature(), "修正前真原版模板完整NBT/船數/RNG基準");
        System.out.println("M6_END_CITY_BASELINE seed=481516 chunk=-32,-32 pos=-505,80,-505 " + baseline.signature());
        for (int i = 0; i < 3; i++) assertEquals(expected, EndCityHarness.generate(context(481516, -32, -32)).signature());
        assertEquals("33a33d1ffe909140fd5cc5f6bed8a2d06e130b3ffbe924a45f0064a349fb2c13;count=8;ships=0;rng=-8328947346988894843;rotation=CLOCKWISE_180",
                EndCityHarness.generate(context(8128, 32, 32)).signature(), "原版無船控制組");
    }

    @Test void twoWorldEndCitiesKeepShipChoiceIndependent() throws InterruptedException {
        EndCityHarness.race(context(481516, -32, -32), context(8128, 32, 32)).requireSafe();
    }

    @Test void nestedInvocationRestoresParentShipChoice() {
        var expected = EndCityHarness.generate(context(481516, -32, -32)).signature();
        var observer = new EndCityHarness.PausedRandom(() -> {
            assertTrue(EndCityHarness.shipState(), "父 invocation 已選船");
            EndCityHarness.generate(context(8128, 32, 32));
            assertTrue(EndCityHarness.shipState(), "巢狀整輪必須恢復父 ship frame");
        });
        assertEquals(expected, EndCityHarness.generate(context(481516, -32, -32), observer).signature());
        assertNoFrame();
    }

    @Test void exceptionClearsFrameAndWorkerCanBeReused() throws InterruptedException {
        var expected = EndCityHarness.generate(context(481516, -32, -32)).signature();
        var failure = new AtomicReference<Throwable>();
        var worker = NativeHarness.worker("M6EndCity-reused-worker", () -> {
            var thrown = assertThrows(IllegalArgumentException.class, () -> EndCityHarness.generate(context(481516, -32, -32),
                    new EndCityHarness.PausedRandom(() -> { throw new IllegalArgumentException("M6_CONTROLLED_END_CITY_RNG_FAILURE"); })));
            assertEquals("M6_CONTROLLED_END_CITY_RNG_FAILURE", thrown.getMessage());
            assertNoFrame();
            assertEquals(expected, EndCityHarness.generate(context(481516, -32, -32)).signature());
            assertNoFrame();
        }, failure);
        NativeHarness.join(worker); NativeHarness.rethrow(failure.get());
        var other = NativeHarness.worker("M6EndCity-after-exception-worker", () -> {
            assertEquals(expected, EndCityHarness.generate(context(481516, -32, -32)).signature()); assertNoFrame();
        }, failure);
        NativeHarness.join(other); NativeHarness.rethrow(failure.get());
    }


    @Test void nestedExceptionRestoresParentShipChoice() {
        var expected = EndCityHarness.generate(context(481516, -32, -32)).signature();
        var observer = new EndCityHarness.PausedRandom(() -> {
            var thrown = assertThrows(IllegalArgumentException.class, () -> EndCityHarness.generate(context(481516, -32, -32),
                    new EndCityHarness.PausedRandom(() -> { throw new IllegalArgumentException("M6_NESTED_END_CITY_FAILURE"); })));
            assertEquals("M6_NESTED_END_CITY_FAILURE", thrown.getMessage());
            assertTrue(EndCityHarness.shipState(), "子層例外後必須恢復父 ship frame");
        });
        assertEquals(expected, EndCityHarness.generate(context(481516, -32, -32), observer).signature());
        assertNoFrame();
    }

    @Test void directPartCallsWithoutFrameKeepNativeField() throws Exception {
        var bridgeField = net.minecraft.structure.EndCityGenerator.class.getDeclaredField("BRIDGE_PIECE"); bridgeField.setAccessible(true);
        var bridge = bridgeField.get(null); var type = bridge.getClass();
        var ship = type.getDeclaredField("shipGenerated"); ship.setAccessible(true);
        var init = type.getDeclaredMethod("init"); init.setAccessible(true);
        var create = type.getDeclaredMethod("create", StructureTemplateManager.class, int.class,
                net.minecraft.structure.EndCityGenerator.Piece.class, net.minecraft.util.math.BlockPos.class, List.class,
                net.minecraft.util.math.random.Random.class); create.setAccessible(true);
        var parent = new net.minecraft.structure.EndCityGenerator.Piece(templates, "bridge_end",
                new net.minecraft.util.math.BlockPos(0, 80, 0), net.minecraft.util.BlockRotation.NONE, true);
        try {
            ship.setBoolean(bridge, true); init.invoke(bridge);
            assertEquals(false, ship.getBoolean(bridge), "無frame init必須寫入原false字段");
            int shipSeed = -1;
            for (int seed = 0; seed < 32; seed++) {
                init.invoke(bridge);
                var pieces = new java.util.ArrayList<net.minecraft.structure.StructurePiece>();
                create.invoke(bridge, templates, 8, parent, null, pieces, new net.minecraft.util.math.random.CheckedRandom(seed));
                assertNoFrame();
                if (ship.getBoolean(bridge)) { shipSeed = seed; break; }
            }
            assertTrue(shipSeed >= 0, "無frame create選船後必須寫回原true字段");
            var pieces = new java.util.ArrayList<net.minecraft.structure.StructurePiece>();
            // 同一RNG在原字段已true時不得再選船，直接證GET fallback使用原字段。
            create.invoke(bridge, templates, 8, parent, null, pieces, new net.minecraft.util.math.random.CheckedRandom(shipSeed));
            var collector = new net.minecraft.structure.StructurePiecesCollector(); pieces.forEach(collector::addPiece);
            assertEquals(0L, new EndCityHarness.City(collector, 0, net.minecraft.util.BlockRotation.NONE).ships(), "無frame GET必須沿原true字段");
            assertEquals(true, ship.getBoolean(bridge)); assertNoFrame();
        } finally { init.invoke(bridge); }
    }

    private static void assertNoFrame() {
        try {
            var state = Class.forName("dev.quantumchamber.universe.EndCityInvocationState");
            var read = state.getMethod("read", boolean.class);
            assertEquals(false, read.invoke(null, false), "沒有frame時必須沿原field(false)");
            assertEquals(true, read.invoke(null, true), "沒有frame時必須沿原field(true)");
            var field = state.getDeclaredField("FRAMES"); field.setAccessible(true);
            var frames = (ThreadLocal<?>) field.get(null);
            try { assertEquals(null, frames.get(), "worker任務結束不得留下空stack或frame"); }
            finally { frames.remove(); }
        } catch (ClassNotFoundException originalRuntime) {
            // 原版尚無private frame；RED仍由完整ship/NBT/RNG oracle判定。
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
