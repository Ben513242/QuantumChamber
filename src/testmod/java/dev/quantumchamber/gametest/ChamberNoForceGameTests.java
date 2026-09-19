package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.util.Comparator;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

/** 真實 FULL 控制器 chunk 與未 FULL 鄰區的原生讀電位反例。 */
public final class ChamberNoForceGameTests implements FabricGameTest {
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create(
            "quantumchamber_final_fix", Comparator.comparingLong(ChunkPos::toLong));

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void full_controller_does_not_force_missing_weak_power_neighbor(TestContext context) {
        verifyMissingNeighbor(context, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void full_controller_does_not_force_missing_strong_power_neighbor(TestContext context) {
        verifyMissingNeighbor(context, true);
    }

    private static void verifyMissingNeighbor(TestContext context, boolean strong) {
        var world = context.getWorld();
        var manager = world.getChunkManager();
        int chunkX = strong ? 2112 : 2048;
        int chunkZ = 2048;
        var chunkPos = new ChunkPos(chunkX, chunkZ);
        // level 33 只維持 C 所在 chunk 為 FULL，不把鄰接 chunk 提升到 FULL。
        manager.addTicket(TICKET, chunkPos, 0, chunkPos);
        ChamberLoadSyncTestAccess.Observation receipts = null;
        try {
            var chunk = world.getChunk(chunkX, chunkZ);
            var pos = new BlockPos(chunkX * 16 + (strong ? 14 : 15), 70, chunkZ * 16 + 8);
            var frame = new ChamberFrame(pos, Direction.NORTH);
            if (strong) chunk.setBlockState(pos.east(), Blocks.STONE.getDefaultState(), false);
            chunk.setBlockState(pos, ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                    .with(ChamberControllerBlock.FACING, Direction.NORTH), false);
            var controller = (ChamberControllerBlockEntity) chunk.getBlockEntity(pos);
            context.assertTrue(controller != null && controller.getWorld() == world, "真實 C BE 已存在");
            var registry = ChamberRegistryState.get(world.getServer()).registry();
            var uuid = registry.registerOrigin(world.getRegistryKey().getValue(), DimensionRole.OVERWORLD, frame).chamberUuid();
            controller.setChamberUuid(uuid);
            registry.setPowerState(uuid, ChamberPowerState.OFF);
            controller.setPowerInitialized(false);
            controller.setWasPowered(true);
            var observation = ChamberLoadSyncTestAccess.observe(world, controller);
            receipts = observation;
            ChamberControllerLoadSyncQueue.enqueue(world, controller);
            context.assertTrue(manager.getWorldChunk(chunkX, chunkZ) == chunk, "C 已 FULL");
            context.assertTrue(manager.getWorldChunk(chunkX + 1, chunkZ) == null, "讀電位前鄰接 chunk 未 FULL");
            new ChamberRedstoneService().onLoad(world, pos);
            context.assertTrue(manager.getWorldChunk(chunkX + 1, chunkZ) == null, "原生讀電位不得強載鄰接 chunk");
            context.assertTrue(!controller.powerInitialized() && controller.wasPowered(), "缺 chunk 不得當成低電位");
            context.assertTrue(!ChamberProtectionService.get().mayMutate(world, pos), "未協調 OFF 保持保護");
            context.waitAndRun(2, () -> {
                try {
                    context.assertTrue(manager.getWorldChunk(chunkX, chunkZ) == chunk, "queue 執行時 C 仍 FULL");
                    context.assertTrue(manager.getWorldChunk(chunkX + 1, chunkZ) == null, "原生 queue 也不得強載鄰接 chunk");
                    context.assertTrue(ChamberLoadSyncTestAccess.hasPending(world.getServer(), controller), "缺鄰格保留重試");
                    context.assertTrue(observation.outcomes().contains(ChamberLoadSyncOutcome.REQUEUED_NEIGHBOR_NOT_READY), "必須實際經過缺鄰格重排分支");
                    context.assertTrue(!observation.outcomes().contains(ChamberLoadSyncOutcome.CONSUMED), "鄰格未齊不得消耗 token");
                    context.assertTrue(!controller.powerInitialized() && controller.wasPowered(), "重試不得發布假低位");
                    // 只有測試明示載入缺少的鄰區；production 仍只能唯讀 FULL。
                    world.getChunk(chunkX + 1, chunkZ);
                    context.waitAndRun(2, () -> {
                        try {
                            context.assertTrue(controller.powerInitialized() && !controller.wasPowered(), "鄰格齊全後重讀真實低位");
                            context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(world.getServer(), controller), "完成後釋放 queue");
                            var outcomes = observation.outcomes();
                            int retried = outcomes.indexOf(ChamberLoadSyncOutcome.REQUEUED_NEIGHBOR_NOT_READY);
                            int consumed = outcomes.indexOf(ChamberLoadSyncOutcome.CONSUMED);
                            context.assertTrue(retried >= 0 && consumed > retried
                                            && outcomes.stream().filter(outcome -> outcome == ChamberLoadSyncOutcome.CONSUMED).count() == 1,
                                    "receipt 必須先缺鄰格重排，再且僅消耗一次：" + outcomes);
                            for (int index = 0; index < outcomes.size(); index++) {
                                var outcome = outcomes.get(index);
                                boolean expected = index < consumed
                                        ? outcome == ChamberLoadSyncOutcome.REQUEUED_NOT_DUE
                                                || outcome == ChamberLoadSyncOutcome.REQUEUED_NEIGHBOR_NOT_READY
                                        : index == consumed
                                                ? outcome == ChamberLoadSyncOutcome.CONSUMED
                                                : outcome == ChamberLoadSyncOutcome.DROPPED_PENDING_TOKEN_MISSING;
                                context.assertTrue(expected, "僅允許消耗前重排，消耗後丟棄重複 entry 的缺失 token：" + outcomes);
                            }
                            context.assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState(), "保留同一 OFF 紀錄");
                            context.assertTrue(world.removeBlock(pos, false), "協調完成才開放普通拆除");
                            context.assertTrue(!registry.records().containsKey(uuid), "成功移除清原 UUID");
                        } finally {
                            manager.removeTicket(TICKET, chunkPos, 0, chunkPos);
                            observation.close();
                        }
                        context.complete();
                    });
                } catch (RuntimeException | Error failure) {
                    manager.removeTicket(TICKET, chunkPos, 0, chunkPos);
                    observation.close();
                    throw failure;
                }
            });
        } catch (RuntimeException | Error failure) {
            manager.removeTicket(TICKET, chunkPos, 0, chunkPos);
            if (receipts != null) receipts.close();
            throw failure;
        }
    }
}
