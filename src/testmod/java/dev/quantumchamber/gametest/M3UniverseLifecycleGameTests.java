package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberControllerLoadSyncQueue;
import dev.quantumchamber.chamber.ChamberLoadSyncTestAccess;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseRoleResolver;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只由 fabric-gametest 執行；未註冊 main 探針或任何預設開啟的 fixture。 */
public final class M3UniverseLifecycleGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(M3UniverseLifecycleGameTests.class);

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void vanilla_and_foreign_roles_keep_their_boundaries(TestContext context) {
        LOGGER.info("M3_LIFECYCLE_GAMETEST_HIT vanilla_and_foreign_roles_keep_their_boundaries");
        var server = context.getWorld().getServer();
        context.assertEquals(DimensionRole.OVERWORLD, UniverseRoleResolver.resolve(server, World.OVERWORLD).orElseThrow(), "vanilla Overworld");
        context.assertEquals(DimensionRole.NETHER, UniverseRoleResolver.resolve(server, World.NETHER).orElseThrow(), "vanilla Nether");
        context.assertEquals(DimensionRole.END, UniverseRoleResolver.resolve(server, World.END).orElseThrow(), "vanilla End");
        var foreign = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("foreign", "overworld"));
        context.assertTrue(UniverseRoleResolver.resolve(server, foreign).isEmpty(), "foreign key 不可推測 role");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void empty_bootstrap_and_disabled_record_do_not_create_worlds(TestContext context) {
        LOGGER.info("M3_LIFECYCLE_GAMETEST_HIT empty_bootstrap_and_disabled_record_do_not_create_worlds");
        var server = context.getWorld().getServer();
        var contexts = (Map<?, ?>) readStatic(UniverseLifecycleService.class, "CONTEXTS");
        var owner = contexts.get(server);
        context.assertTrue(owner != null && (boolean) read(owner, "ready"), "正式 SERVER_STARTED 已 bootstrap");
        UniverseLifecycleService.initialize();
        UniverseLifecycleService.initialize();
        context.assertTrue(contexts.get(server) == owner, "重複 initialize 保留 exact context");
        var backend = (Minecraft121DynamicDimensionBackend) read(owner, "backend");
        var catalog = UniverseRegistryState.get(server);
        context.assertTrue(catalog.records().isEmpty(), "fresh production bootstrap 使用健康空 catalog");
        context.assertTrue(backend.runtimeSnapshot(server).isEmpty() && backend.quarantinedWorlds(server).isEmpty(), "空 catalog 沒有 runtime owner");
        context.assertEquals(4L, java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(), false).count(), "empty bootstrap 保持四個原生 world");
        var record = catalog.allocateOverworld(UUID.fromString("80000000-0000-4000-8000-000000000008"), 8);
        catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        catalog.flush(server);
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        context.assertTrue(server.getWorld(descriptor.worldKey()) == null, "DISABLED catalog 不物化 world");
        context.assertEquals(DimensionRole.OVERWORLD, UniverseRoleResolver.resolve(server, descriptor.worldKey()).orElseThrow(), "exact catalog role");
        context.assertTrue(DimensionRole.fromVanillaKey(descriptor.worldKey()).isEmpty(), "M1 vanilla role 不擴張");
        context.assertEquals(4L, java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(), false).count(), "角色查詢沒有物化副作用");

        var unloads = new AtomicInteger();
        ServerWorldEvents.UNLOAD.register((stopping, candidate) -> {
            if (stopping == server && candidate.getRegistryKey().equals(descriptor.worldKey())) unloads.incrementAndGet();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
            if (stopping != server) return;
            require(stopping.getWorld(descriptor.worldKey()) == null && backend.runtimeSnapshot(server).isEmpty(), "DISABLED world 不可在測試期間建立");
            // 合成 witness 只證停止後清理，不冒充原生 BLOCK_ENTITY_LOAD。
            var world = stopping.getOverworld();
            var controller = new ChamberControllerBlockEntity(new BlockPos(9, 100, 8), ModBlocks.CHAMBER_CONTROLLER.getDefaultState());
            controller.setWorld(world);
            ChamberLoadSyncTestAccess.observe(world, controller);
            ChamberControllerLoadSyncQueue.enqueue(world, controller);
            require(ChamberLoadSyncTestAccess.hasPending(server, controller), "STOPPING synthetic queue witness 缺失");
            LOGGER.info("M3_LIFECYCLE_STOPPING_WITNESS synthetic=true");
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(stopped -> {
            if (stopped != server) return;
            require(unloads.get() == 0 && backend.runtimeSnapshot(server).isEmpty() && backend.quarantinedWorlds(server).isEmpty(), "DISABLED world 必須保持未建立");
            require(!contexts.containsKey(server), "Universe lifecycle context 必須 exact detach");
            require(!((Map<?, ?>) readStatic(ChamberControllerLoadSyncQueue.class, "PENDING")).containsKey(server), "queue server key 未移除");
            require(!((Map<?, ?>) readStatic(ChamberControllerLoadSyncQueue.class, "OBSERVERS")).containsKey(server), "observer server key 未移除");
            var protection = ChamberProtectionService.get();
            require(read(protection, "attachedServer") == null && read(protection, "attachedRegistry") == null, "protection 未 detach");
            LOGGER.info("M3_LIFECYCLE_STOPPED_VERIFIED dynamicWorlds=0 queueDetached=true protectionDetached=true contextDetached=true");
        });
        context.complete();
    }

    private static Object readStatic(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("只讀 lifecycle 驗收失敗", failure);
        }
    }

    private static Object read(Object target, String name) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("只讀 lifecycle 驗收失敗", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
