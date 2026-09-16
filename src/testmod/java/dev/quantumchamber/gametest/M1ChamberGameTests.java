package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModEffects;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public final class M1ChamberGameTests implements FabricGameTest {
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

    private static void state(TestContext context, ChamberState expected, int signal) {
        var controller = ChamberGameTestBuilder.controller(context);
        context.assertEquals(expected, controller.chamberState(), "controller 狀態");
        context.assertEquals(signal, context.getWorld().getBlockState(controller.getPos())
                .getComparatorOutput(context.getWorld(), controller.getPos()), "comparator 輸出");
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void valid_closed_buffed_chamber_becomes_ready(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.assertTrue(attempt(context).accepted(), "有效且有 buff 的參與者可啟用");
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.READY, 7);
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
        ChamberControllerBlock.onControllerLoaded(context.getWorld(), frame.controllerPos());
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
        state(context, ChamberState.ARMED, 11);
        // 已消耗同一 edge 後，將可見狀態退回 READY；持續高電位不得再次 arm。
        ChamberGameTestBuilder.controller(context).setChamberState(ChamberState.READY);
        context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
        state(context, ChamberState.READY, 7);
        finish(context, player);
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
        nbt.putBoolean("PowerInitialized", false);
        context.getWorld().removeBlockEntity(frame.controllerPos());
        var restored = new ChamberControllerBlockEntity(frame.controllerPos(), original.getCachedState());
        restored.read(nbt, context.getWorld().getRegistryManager());
        context.getWorld().addBlockEntity(restored);
        context.waitAndRun(2, () -> {
            context.assertTrue(restored.wasPowered() && restored.powerInitialized(), "BLOCK_ENTITY_LOAD 同步高電位");
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
        attempt(context);
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var result = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new ChamberFrame(frame.controllerPos().east(), frame.outwardFacing()));
        context.assertEquals(ChamberRegistrationResult.Status.OVERLAP, result.status(), "同 role 重疊被拒絕");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void same_xyz_different_role_registry_records_are_allowed(TestContext context) {
        var frame = build(context, false);
        attempt(context);
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var result = registry.registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, frame);
        context.assertEquals(ChamberRegistrationResult.Status.CREATED, result.status(), "不同 role 同座標可註冊");
        context.assertTrue(registry.findAt(DimensionRole.NETHER, frame.controllerPos()).isPresent(), "Nether 記錄存在");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void protected_volume_rejects_ordinary_set_block(TestContext context) {
        var frame = build(context, false);
        attempt(context);
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
        attempt(context);
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
        var frame = build(context, false);
        attempt(context);
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
        var frame = build(context, true);
        attempt(context);
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
        ChamberControllerBlock.onControllerLoaded(context.getWorld(), frame.controllerPos());
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
