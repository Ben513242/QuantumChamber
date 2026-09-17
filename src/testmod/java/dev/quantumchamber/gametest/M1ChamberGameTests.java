package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModPotions;
import dev.quantumchamber.universe.DimensionRole;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.UUID;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ConnectedClientData;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.ComparatorBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.entity.ComparatorBlockEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public final class M1ChamberGameTests implements FabricGameTest {
    @GameTest(templateName = "quantumchamber:m1_empty")
    public void draft_refresh_and_doors_do_not_register_until_native_lever_edge(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.READY, 7);
        assertDraft(context, frame);
        context.assertTrue(useController(context, frame, player).isAccepted(), "草稿可開門");
        state(context, ChamberState.IDLE, 3);
        assertDraft(context, frame);
        context.assertTrue(useController(context, frame, player).isAccepted(), "草稿可關門");
        assertDraft(context, frame);
        var lever = ChamberGameTestBuilder.CONTROLLER.north();
        context.setBlockState(lever, Blocks.LEVER.getDefaultState()
                .with(LeverBlock.FACE, BlockFace.WALL).with(LeverBlock.FACING, Direction.NORTH));
        context.waitAndRun(25, () -> {
            state(context, ChamberState.READY, 7);
            assertDraft(context, frame);
            context.useBlock(lever, player);
            state(context, ChamberState.ARMED, 11);
            var controller = ChamberGameTestBuilder.controller(context);
            context.assertTrue(controller.chamberUuid() != null, "真正拉桿 edge 才配發 UUID");
            var record = ChamberRegistryState.get(context.getWorld().getServer()).registry()
                    .findOrigin(context.getWorld().getRegistryKey().getValue(), DimensionRole.OVERWORLD, frame).orElseThrow();
            context.assertEquals(controller.chamberUuid(), record.chamberUuid(), "BE 與 record UUID 相同");
            player.removeStatusEffect(ModEffects.QUANTUM_STATE);
            context.useBlock(lever, player);
            context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos().add(0, -4, 3),
                    Blocks.STONE.getDefaultState()), "falling 與 buff 消失仍保持已註冊保護");
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void zero_participant_pulse_does_not_register(TestContext context) {
        rejectedDraftPulse(context, false, false, false, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unbuffed_pulse_does_not_register(TestContext context) {
        rejectedDraftPulse(context, false, true, false, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void open_door_pulse_does_not_register(TestContext context) {
        rejectedDraftPulse(context, true, true, true, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void invalid_shell_pulse_does_not_register(TestContext context) {
        rejectedDraftPulse(context, false, true, true, true);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unknown_existing_uuid_is_not_treated_as_draft(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        UUID unknown = UUID.randomUUID();
        ChamberGameTestBuilder.controller(context).setChamberUuid(unknown);
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.INVALID, 0);
            context.assertEquals(unknown, ChamberGameTestBuilder.controller(context).chamberUuid(), "不得覆寫未知 UUID");
            context.assertTrue(ChamberRegistryState.get(context.getWorld().getServer()).registry()
                    .findAt(DimensionRole.OVERWORLD, frame.controllerPos()).isEmpty(), "身分拒絕不得註冊");
            finish(context, player);
        });
    }

    private static void rejectedDraftPulse(TestContext context, boolean open, boolean hasPlayer,
            boolean buffed, boolean missingShell) {
        var frame = ChamberGameTestBuilder.build(context, open, Direction.NORTH, missingShell);
        var player = hasPlayer ? participant(context, buffed) : null;
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            context.assertTrue(ChamberGameTestBuilder.controller(context).chamberState() != ChamberState.ARMED,
                    "條件不足不得 ARMED");
            assertDraft(context, frame);
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
            assertDraft(context, frame);
            if (player == null) context.complete(); else finish(context, player);
        });
    }

    private static void assertDraft(TestContext context, ChamberFrame frame) {
        context.assertTrue(ChamberGameTestBuilder.controller(context).chamberUuid() == null, "預覽不得配發 UUID");
        context.assertTrue(ChamberRegistryState.get(context.getWorld().getServer()).registry()
                .findOrigin(context.getWorld().getRegistryKey().getValue(), DimensionRole.OVERWORLD, frame).isEmpty(),
                "預覽不得註冊");
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_interaction_toggles_all_cells_and_refreshes_without_rearming(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        context.waitAndRun(2, () -> {
            try {
                var interior = frame.controllerPos().add(0, -4, 3);
                context.assertTrue(!context.getWorld().setBlockState(interior, Blocks.STONE.getDefaultState()), "互動前一般受保護寫入被拒絕");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                state(context, ChamberState.ARMED, 11);
                context.assertTrue(useController(context, frame, player).isAccepted(), "Controller 原生互動必須成功");
                assertDoors(context, frame, true);
                state(context, ChamberState.IDLE, 3);
                context.assertTrue(!context.getWorld().setBlockState(interior, Blocks.STONE.getDefaultState()), "開門後授權不得洩漏");
                context.assertTrue(useController(context, frame, player).isAccepted(), "Controller 可再次關門");
                assertDoors(context, frame, false);
                state(context, ChamberState.READY, 7);
                context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
                state(context, ChamberState.READY, 7);
                context.assertTrue(!context.getWorld().setBlockState(interior, Blocks.STONE.getDefaultState()), "關門後一般受保護寫入仍被拒絕");
                context.assertTrue(context.getWorld().getBlockState(interior).isAir(), "受保護內部保持 air");
                context.complete();
            } finally {
                context.getWorld().getServer().getPlayerManager().remove(player);
            }
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_interaction_uses_its_east_facing(TestContext context) {
        var frame = ChamberGameTestBuilder.build(context, false, Direction.EAST, false);
        var player = participant(context, true);
        try {
            context.assertTrue(useController(context, frame, player).isAccepted(), "東向 Controller 原生互動必須成功");
            assertDoors(context, frame, true);
            context.complete();
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_interaction_rejects_missing_shell_without_door_changes(TestContext context) {
        var frame = ChamberGameTestBuilder.build(context, false, Direction.NORTH, true);
        assertRejectedController(context, frame);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_interaction_rejects_mixed_door_without_partial_changes(TestContext context) {
        var frame = build(context, false);
        context.getWorld().setBlockState(frame.controllerPos().down(), ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, true));
        assertRejectedController(context, frame);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_interaction_rejects_missing_door_without_partial_changes(TestContext context) {
        var frame = build(context, false);
        context.getWorld().setBlockState(frame.controllerPos().down(), Blocks.AIR.getDefaultState());
        assertRejectedController(context, frame);
    }

    private static ActionResult useController(TestContext context, ChamberFrame frame, ServerPlayerEntity player) {
        var pos = frame.controllerPos();
        return context.getWorld().getBlockState(pos).onUse(context.getWorld(), player,
                new BlockHitResult(Vec3d.ofCenter(pos), frame.outwardFacing(), pos, false));
    }

    private static void assertDoors(TestContext context, ChamberFrame frame, boolean open) {
        for (int x = -2; x <= 2; x++) for (int y = -5; y <= -1; y++) {
            var pos = frame.controllerPos().offset(frame.outwardFacing().rotateYCounterclockwise(), x).add(0, y, 0);
            var cell = context.getWorld().getBlockState(pos);
            context.assertTrue(cell.isOf(ModBlocks.QUANTUM_BULKHEAD) && cell.get(QuantumBulkheadBlock.OPEN) == open,
                    "整面 25 格門狀態必須一致：" + pos.toShortString());
        }
    }

    private static void assertRejectedController(TestContext context, ChamberFrame frame) {
        var before = new java.util.LinkedHashMap<BlockPos, net.minecraft.block.BlockState>();
        for (int x = -2; x <= 2; x++) for (int y = -5; y <= -1; y++) {
            var pos = frame.controllerPos().add(x, y, 0);
            before.put(pos, context.getWorld().getBlockState(pos));
        }
        var player = participant(context, true);
        try {
            context.assertEquals(useController(context, frame, player), ActionResult.FAIL, "無效 shell／門格必須回傳失敗");
            before.forEach((pos, original) -> context.assertEquals(context.getWorld().getBlockState(pos), original, "拒絕互動不得部分寫門格"));
            context.complete();
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unloaded_chunk_entry_is_dropped_without_loading_it(TestContext context) {
        var world = context.getWorld();
        BlockPos pos = context.getAbsolutePos(new BlockPos(4096, 3, 4096));
        context.assertTrue(world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) == null, "測試前 chunk 未載入");
        var detached = new ChamberControllerBlockEntity(pos, ModBlocks.CHAMBER_CONTROLLER.getDefaultState());
        detached.setWorld(world);
        ChamberControllerLoadSyncQueue.enqueue(world, detached);
        context.waitAndRun(2, () -> {
            context.assertTrue(world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4) == null, "queue 不得強載 chunk");
            context.assertTrue(!detached.powerInitialized(), "未載入 chunk 的 BE 不得同步");
            context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(world.getServer(), detached), "未載入 chunk 的 entry 已丟棄");
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void replaced_entity_is_not_synced_by_an_older_queue_entry(TestContext context) {
        BlockPos pos = new BlockPos(7, 3, 7);
        var world = context.getWorld();
        world.setBlockState(context.getAbsolutePos(pos.up()), Blocks.REDSTONE_BLOCK.getDefaultState(), 2);
        context.setBlockState(pos, ModBlocks.CHAMBER_CONTROLLER);
        var original = (ChamberControllerBlockEntity) context.getBlockEntity(pos);
        context.waitAndRun(1, () -> {
            world.removeBlockEntity(context.getAbsolutePos(pos));
            var replacement = new ChamberControllerBlockEntity(context.getAbsolutePos(pos), original.getCachedState());
            replacement.setPowerInitialized(true);
            world.addBlockEntity(replacement);
            // 即使舊物件的 removed flag 已被清除，也必須由 current BE identity 排除它。
            original.cancelRemoval();
            context.waitAndRun(1, () -> {
                context.assertTrue(!replacement.wasPowered(), "舊 entry 不得提前同步替代 BE");
                context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(world.getServer(), original), "舊 identity entry 已丟棄");
                context.assertTrue(ChamberLoadSyncTestAccess.hasPending(world.getServer(), replacement), "新 entry 要等自己的 due tick");
            });
            context.waitAndRun(2, () -> {
                context.assertTrue(replacement.wasPowered(), "新 BE 在自己的 due tick 才同步");
                context.assertTrue(!original.powerInitialized(), "不得同步舊物件");
                context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(world.getServer(), replacement), "新 entry 已完成並釋放");
                context.complete();
            });
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void reload_sync_is_not_delayed_by_existing_periodic_block_tick(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.waitAndRun(3, () -> {
            var original = ChamberGameTestBuilder.controller(context);
            context.assertTrue(original.powerInitialized(), "第一次載入同步已完成");
            context.assertTrue(context.getWorld().getBlockTickScheduler().isQueued(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER), "20 tick periodic refresh 已存在");
            context.getWorld().setBlockState(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 2);
            original.setChamberState(ChamberState.READY);
            var nbt = original.createNbt(context.getWorld().getRegistryManager());
            nbt.putBoolean("WasPowered", false);
            nbt.putBoolean("PowerInitialized", true);
            context.getWorld().removeBlockEntity(frame.controllerPos());
            var restored = new ChamberControllerBlockEntity(frame.controllerPos(), original.getCachedState());
            restored.read(nbt, context.getWorld().getRegistryManager());
            context.getWorld().addBlockEntity(restored);
            context.assertTrue(!restored.wasPowered(), "本 tick 不可立即 sync");
            context.waitAndRun(2, () -> {
                context.assertTrue(restored.wasPowered(), "既有 periodic tick 不得把 load sync 延後到20 ticks");
                state(context, ChamberState.READY, 7);
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                state(context, ChamberState.ARMED, 11);
                finish(context, player);
            });
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void removed_controller_load_tick_does_not_touch_old_entity(TestContext context) {
        BlockPos pos = new BlockPos(7, 3, 7);
        context.setBlockState(pos, ModBlocks.CHAMBER_CONTROLLER);
        var oldController = (ChamberControllerBlockEntity) context.getBlockEntity(pos);
        context.assertTrue(!oldController.powerInitialized(), "load 不可在同一 tick 同步");
        context.removeBlock(pos);
        context.waitAndRun(2, () -> {
            context.assertTrue(oldController.isRemoved(), "舊 controller 已移除");
            context.assertTrue(!oldController.powerInitialized(), "不得同步已移除的 BE");
            context.assertTrue(context.getBlockState(pos).isAir(), "移除後保持 air");
            context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(context.getWorld().getServer(), oldController), "queue 必須釋放已移除的 BE");
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void replaced_controller_load_tick_does_not_touch_replacement_entity(TestContext context) {
        BlockPos pos = new BlockPos(7, 3, 7);
        context.setBlockState(pos, ModBlocks.CHAMBER_CONTROLLER);
        var oldController = (ChamberControllerBlockEntity) context.getBlockEntity(pos);
        context.setBlockState(pos, Blocks.CHEST);
        var replacement = context.getBlockEntity(pos);
        context.waitAndRun(2, () -> {
            context.assertTrue(oldController.isRemoved() && !oldController.powerInitialized(), "不得同步被替換的 controller");
            context.assertTrue(context.getBlockEntity(pos) == replacement && !replacement.isRemoved(), "替代 chest BE 保持原身分");
            context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(context.getWorld().getServer(), oldController), "queue 必須釋放已替換的 BE");
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void controller_load_syncs_once_on_next_tick_without_arming(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        var controller = ChamberGameTestBuilder.controller(context);
        ChamberProtectionService.get().authorizedMutation(() -> context.getWorld().setBlockState(
                controller.getPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 2));
        controller.setChamberState(ChamberState.READY);
        controller.setWasPowered(false);
        controller.setPowerInitialized(true);
        context.assertTrue(!controller.wasPowered(), "callback 不可同步當前 tick");
        context.waitAndRun(2, () -> {
            context.assertTrue(controller.powerInitialized() && controller.wasPowered(), "下一個 block tick 同步 held-high");
            state(context, ChamberState.READY, 7);
            assertDraft(context, frame);
            // 保留 initialized=true，令可見 latch 偏離實際電平，用來偵測後續不應發生的重複 sync。
            controller.setWasPowered(false);
        });
        context.waitAndRun(25, () -> {
            context.assertTrue(controller.powerInitialized() && !controller.wasPowered(), "一般 refresh 不得重做 load sync");
            state(context, ChamberState.READY, 7);
            assertDraft(context, frame);
            finish(context, player);
        });
    }

    private static ChamberFrame build(TestContext context, boolean open) {
        return ChamberGameTestBuilder.build(context, open, Direction.NORTH, false);
    }

    private static ServerPlayerEntity participant(TestContext context, boolean buffed) {
        // Vanilla helper 將 isSpectator 固定為 false；此處使用未覆寫的真實 ServerPlayerEntity。
        UUID uuid = UUID.randomUUID();
        var data = ConnectedClientData.createDefault(new GameProfile(uuid, "m1-" + uuid.toString().substring(0, 8)), false);
        var player = new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(), data.gameProfile(), data.syncedOptions());
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        context.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        player.changeGameMode(GameMode.CREATIVE);
        BlockPos pos = context.getAbsolutePos(new BlockPos(7, 2, 7));
        player.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        player.setNoGravity(true);
        if (buffed) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 3600));
        return player;
    }

    private static void finish(TestContext context, ServerPlayerEntity... players) {
        for (ServerPlayerEntity player : players) context.getWorld().getServer().getPlayerManager().remove(player);
        context.complete();
    }

    private static ArmAttemptResult attempt(TestContext context) {
        return new ChamberActivationService().attemptArm(context.getWorld(), ChamberGameTestBuilder.controller(context));
    }

    private static void registerEligibleFixture(TestContext context) {
        var player = participant(context, true);
        try {
            context.assertTrue(attempt(context).accepted(), "保護 fixture 必須先通過完整資格才註冊");
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
    }

    private static void state(TestContext context, ChamberState expected, int signal) {
        var controller = ChamberGameTestBuilder.controller(context);
        context.assertEquals(controller.chamberState(), expected, "controller 狀態");
        context.assertEquals(context.getWorld().getBlockState(controller.getPos())
                .getComparatorOutput(context.getWorld(), controller.getPos()), signal, "comparator 輸出");
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void valid_closed_buffed_chamber_becomes_ready(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.assertTrue(new ChamberActivationService().evaluateReadiness(
                context.getWorld(), ChamberGameTestBuilder.controller(context)).accepted(), "有效且有 buff 的參與者可預覽");
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.READY, 7);
        assertDraft(context, frame);
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void open_bulkhead_stays_idle(TestContext context) {
        var frame = build(context, true);
        var player = participant(context, true);
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.IDLE, 3);
        context.assertTrue(!attempt(context).accepted(), "開門不得啟用");
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void missing_shell_is_invalid(TestContext context) {
        var frame = ChamberGameTestBuilder.build(context, false, Direction.NORTH, true);
        context.assertTrue(!new ChamberDetector().validate(new WorldChamberBlockView(context.getWorld()), frame).valid(),
                "缺少 shell 必須無效");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unbuffed_participant_blocks_activation(TestContext context) {
        build(context, false);
        var buffed = participant(context, true);
        var unbuffed = participant(context, false);
        var result = attempt(context);
        context.assertTrue(!buffed.getUuid().equals(unbuffed.getUuid()), "兩位獨立 UUID");
        context.assertEquals(2, result.participantUuids().size(), "兩位參與者確實都被檢查");
        context.assertTrue(!result.accepted(), "兩位玩家其中一位無 buff 必須拒絕");
        context.assertTrue(result.failureReasons().contains(ArmAttemptResult.Failure.MISSING_QUANTUM_STATE), "缺少 buff 原因");
        finish(context, buffed, unbuffed);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void spectator_is_excluded(TestContext context) {
        build(context, false);
        var buffed = participant(context, true);
        var spectator = participant(context, false);
        spectator.changeGameMode(GameMode.SPECTATOR);
        context.assertTrue(spectator.isSpectator(), "實際玩家模式為 spectator");
        var result = attempt(context);
        context.assertTrue(result.accepted(), "旁觀者不得阻止已 buff 的參與者");
        context.assertEquals(1, result.participantUuids().size(), "僅一位參與者");
        finish(context, buffed, spectator);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void rising_edge_arms_once(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
            // 已消耗同一 edge 後，將可見狀態退回 READY；持續高電位不得再次 arm。
            ChamberGameTestBuilder.controller(context).setChamberState(ChamberState.READY);
            context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.READY, 7);
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void held_power_does_not_retrigger_after_reload_sync(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
        var original = ChamberGameTestBuilder.controller(context);
        original.setChamberState(ChamberState.READY);
        var nbt = original.createNbt(context.getWorld().getRegistryManager());
        // 故意載入落後於世界實際電平的 latch，證明 LOAD 回呼真的同步且不產生 edge。
        nbt.putBoolean("WasPowered", false);
        nbt.putBoolean("PowerInitialized", true);
        context.getWorld().removeBlockEntity(frame.controllerPos());
        var restored = new ChamberControllerBlockEntity(frame.controllerPos(), original.getCachedState());
        restored.read(nbt, context.getWorld().getRegistryManager());
        context.getWorld().addBlockEntity(restored);
        context.assertTrue(restored.powerInitialized() && !restored.wasPowered(), "保留已持久化 initialized=true；runtime load 尚未同步");
        context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
        state(context, ChamberState.READY, 7);
        context.waitAndRun(2, () -> {
            context.assertTrue(restored.wasPowered() && restored.powerInitialized(), "BLOCK_ENTITY_LOAD 同步高電位");
            assertDraft(context, frame);
            context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.READY, 7);
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void scheduled_refresh_drives_comparator(TestContext context) {
        build(context, false);
        var comparatorPos = ChamberGameTestBuilder.CONTROLLER.north();
        context.setBlockState(comparatorPos.down(), Blocks.BEDROCK);
        context.setBlockState(comparatorPos, Blocks.COMPARATOR.getDefaultState().with(ComparatorBlock.FACING, Direction.SOUTH));
        var player = participant(context, false);
        context.waitAndRun(25, () -> {
            state(context, ChamberState.IDLE, 3);
            context.assertEquals(3, ((ComparatorBlockEntity) context.getBlockEntity(comparatorPos)).getOutputSignal(), "真實 comparator 輸出 IDLE");
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 3600));
        });
        context.waitAndRun(50, () -> {
            state(context, ChamberState.READY, 7);
            context.assertEquals(7, ((ComparatorBlockEntity) context.getBlockEntity(comparatorPos)).getOutputSignal(), "真實 comparator 收到更新");
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void overlapping_same_role_chamber_is_rejected(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var result = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new ChamberFrame(frame.controllerPos().east(), frame.outwardFacing()));
        context.assertEquals(ChamberRegistrationResult.Status.OVERLAP, result.status(), "同 role 重疊被拒絕");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void same_xyz_different_role_registry_records_are_allowed(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var result = registry.registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, frame);
        context.assertEquals(ChamberRegistrationResult.Status.CREATED, result.status(), "不同 role 同座標可註冊");
        context.assertTrue(registry.findAt(DimensionRole.NETHER, frame.controllerPos()).isPresent(), "Nether 記錄存在");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void protected_volume_rejects_ordinary_set_block(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        M1LifecycleProbe.observeProtectedPosition(context.getWorld(), frame.controllerPos());
        var interior = frame.controllerPos().add(0, -4, 3);
        context.assertTrue(!context.getWorld().setBlockState(interior, Blocks.STONE.getDefaultState()), "registered interior 禁止 setBlock");
        context.assertTrue(context.getWorld().getBlockState(interior).isAir(), "interior 保持 air");
        context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState()), "controller 禁止刪除");
        context.assertTrue(context.getWorld().setBlockState(frame.controllerPos().up(2), Blocks.STONE.getDefaultState()), "外部仍可修改");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void explosion_does_not_modify_registered_volume(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        var target = frame.controllerPos().add(0, -4, 3);
        ChamberProtectionService.get().authorizedMutation(() -> context.getWorld().setBlockState(target, Blocks.GLASS.getDefaultState()));
        var outside = frame.controllerPos().add(0, 2, 0);
        context.getWorld().setBlockState(outside, Blocks.GLASS.getDefaultState());
        context.getWorld().createExplosion(null, target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 3, World.ExplosionSourceType.BLOCK);
        context.getWorld().createExplosion(null, outside.getX() + 0.5, outside.getY() + 0.5, outside.getZ() + 0.5, 2, World.ExplosionSourceType.BLOCK);
        context.assertTrue(context.getWorld().getBlockState(target).isOf(Blocks.GLASS), "registered 可破壞方塊免於爆炸");
        context.assertTrue(context.getWorld().getBlockState(outside).isAir(), "外部控制組確實被爆炸移除");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void piston_cannot_move_registered_bulkhead_or_shell(TestContext context) {
        // 僅驗證方塊移動 contract；Bulkhead／bedrock 本身不可推動，不獨立證明 Mixin。
        var frame = build(context, false);
        registerEligibleFixture(context);
        var door = frame.controllerPos().down(3);
        var piston = door.north();
        context.getWorld().setBlockState(piston, Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, Direction.SOUTH));
        context.getWorld().setBlockState(piston.north(), Blocks.REDSTONE_BLOCK.getDefaultState());
        var shell = frame.controllerPos().add(3, -3, 0);
        context.getWorld().setBlockState(shell.north(), Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, Direction.SOUTH));
        context.getWorld().setBlockState(shell.north(2), Blocks.REDSTONE_BLOCK.getDefaultState());
        var control = frame.controllerPos().add(5, -3, 0);
        context.getWorld().setBlockState(control, Blocks.STONE.getDefaultState());
        context.getWorld().setBlockState(control.north(), Blocks.PISTON.getDefaultState().with(PistonBlock.FACING, Direction.SOUTH));
        context.getWorld().setBlockState(control.north(2), Blocks.REDSTONE_BLOCK.getDefaultState());
        context.waitAndRun(5, () -> {
            context.assertTrue(context.getWorld().getBlockState(door).isOf(dev.quantumchamber.registry.ModBlocks.QUANTUM_BULKHEAD), "bulkhead 未移動");
            context.assertTrue(context.getWorld().getBlockState(door.south()).isAir(), "bulkhead 未推入內部");
            context.assertTrue(context.getWorld().getBlockState(frame.controllerPos().west(3)).isOf(Blocks.BEDROCK), "shell 保留");
            context.assertTrue(context.getWorld().getBlockState(shell).isOf(Blocks.BEDROCK), "活塞目標 shell 未移動");
            context.assertTrue(context.getWorld().getBlockState(control.south()).isOf(Blocks.STONE), "外部控制組確實被活塞推動");
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void fluid_update_cannot_replace_registered_volume(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        var player = participant(context, true);
        try {
            context.assertTrue(useController(context, frame, player).isAccepted(), "fluid fixture 在註冊後開門");
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
        var source = frame.controllerPos().add(0, -3, 2);
        ChamberProtectionService.get().authorizedMutation(() -> context.getWorld().setBlockState(source, Blocks.WATER.getDefaultState()));
        var control = frame.controllerPos().add(5, -3, 2);
        context.getWorld().setBlockState(control, Blocks.WATER.getDefaultState());
        context.waitAndRun(15, () -> {
            context.assertTrue(context.getWorld().getBlockState(source.down()).isAir(), "fluid 排程不得覆蓋 protected air");
            context.assertTrue(!context.getWorld().getFluidState(control.down()).isEmpty(), "外部控制組確實流動");
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void authorized_bulkhead_toggle_changes_all_twenty_five_cells(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
            context.useBlock(ChamberGameTestBuilder.CONTROLLER.down(3), player);
            int opened = 0;
            for (int x = -2; x <= 2; x++) for (int y = -5; y <= -1; y++) {
                if (context.getWorld().getBlockState(frame.controllerPos().add(x, y, 0)).get(QuantumBulkheadBlock.OPEN)) opened++;
            }
            context.assertEquals(25, opened, "onUse 一次開啟 25 格");
            state(context, ChamberState.IDLE, 3);
            context.useBlock(ChamberGameTestBuilder.CONTROLLER.down(3), player);
            context.assertTrue(new ChamberDetector().validate(new WorldChamberBlockView(context.getWorld()), frame).sealed(), "第二次 onUse 關閉整面門");
            state(context, ChamberState.READY, 7);
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void quantum_state_brewing_recipe_produces_registered_potion(TestContext context) {
        var input = PotionContentsComponent.createStack(Items.POTION, Potions.AWKWARD);
        var ingredient = new ItemStack(Items.ECHO_SHARD);
        var recipes = context.getWorld().getBrewingRecipeRegistry();
        context.assertTrue(recipes.hasRecipe(input, ingredient), "runtime brewing recipe 存在");
        var output = recipes.craft(ingredient, input);
        context.assertEquals(ModPotions.QUANTUM_STATE, output.get(DataComponentTypes.POTION_CONTENTS).potion().orElseThrow(), "產物 potion registry entry");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void null_uuid_is_omitted_and_load_event_initializes_power(TestContext context) {
        ChamberGameTestBuilder.build(context, false, Direction.NORTH, true);
        context.waitAndRun(2, () -> {
            var controller = ChamberGameTestBuilder.controller(context);
            context.assertTrue(controller.powerInitialized(), "真實 BLOCK_ENTITY_LOAD 初始化 latch");
            context.assertTrue(!controller.createNbt(context.getWorld().getRegistryManager()).contains("ChamberUuid"), "null UUID 不寫入 NBT");
            state(context, ChamberState.INVALID, 0);
            context.complete();
        });
    }
}
