package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModPotions;
import dev.quantumchamber.universe.DimensionRole;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ConnectedClientData;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
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
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

public final class M1ChamberGameTests implements FabricGameTest {
    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void powered_idle_origin_emits_bounded_native_blue_particles_without_state_mutation(TestContext context) {
        var frame = build(context, true);
        var packets = new ArrayList<ParticleS2CPacket>();
        var observer = participant(context, false, packets);
        context.waitAndRun(2, () -> {
            try {
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                state(context, ChamberState.IDLE, 3);
                var controller = ChamberGameTestBuilder.controller(context);
                var before = controller.createNbt(context.getWorld().getRegistryManager());
                var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
                var records = registry.records();
                packets.clear();
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertEquals(24, packets.size(), "真實 ServerWorld 原生粒子封包預算");
                int blue = 0;
                int spark = 0;
                for (var packet : packets) {
                    context.assertEquals(0, packet.getCount(), "count=0 精確單粒子模式，不隨機散射");
                    context.assertTrue(packet.getOffsetY() >= 0.08f && packet.getOffsetY() <= 0.16f,
                            "原生封包保留向上速度");
                    context.assertTrue(packet.getOffsetX() == 0 && packet.getOffsetZ() == 0 && packet.getSpeed() == 1,
                            "沒有側向隨機速度");
                    if (packet.getParameters() instanceof DustColorTransitionParticleEffect dust) {
                        context.assertTrue(dust.getFromColor().z > dust.getFromColor().x
                                && dust.getFromColor().y > dust.getFromColor().x, "青藍原生漸變");
                        blue++;
                    } else if (packet.getParameters() == ParticleTypes.END_ROD) {
                        spark++;
                    }
                }
                context.assertEquals(20, blue, "柔和青藍樣本");
                context.assertEquals(4, spark, "少量原生明亮 spark");
                context.assertEquals(before, controller.createNbt(context.getWorld().getRegistryManager()), "不修改 controller 狀態");
                context.assertEquals(records, registry.records(), "不修改供電／保護／registry");
            } finally {
                context.getWorld().getServer().getPlayerManager().remove(observer);
            }
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void glow_rejects_draft_wrong_identity_nonpowered_and_unloaded_origins(TestContext context) {
        var frame = build(context, true);
        var packets = new ArrayList<ParticleS2CPacket>();
        var observer = participant(context, false, packets);
        context.waitAndRun(2, () -> {
            try {
                packets.clear();
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertTrue(packets.isEmpty(), "未註冊原艙無輸出");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                var controller = ChamberGameTestBuilder.controller(context);
                var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
                var uuid = controller.chamberUuid();
                packets.clear();
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertEquals(24, packets.size(), "負面 fixture 的有效供電控制組確實輸出");
                packets.clear();
                controller.setChamberUuid(UUID.randomUUID());
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertTrue(packets.isEmpty(), "錯誤 UUID 必須拒絕");
                controller.setChamberUuid(uuid);
                controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertTrue(packets.isEmpty(), "projection 身分必須拒絕");
                controller.setInstanceKind(ChamberInstanceKind.ORIGIN);
                for (var power : new ChamberPowerState[] {ChamberPowerState.OFF, ChamberPowerState.RETURNING, ChamberPowerState.UNKNOWN}) {
                    registry.setPowerState(uuid, power);
                    ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                    context.assertTrue(packets.isEmpty(), power + " 不新增粒子");
                }
                registry.setPowerState(uuid, ChamberPowerState.POWERED);
                controller.setWasPowered(false);
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertTrue(packets.isEmpty(), "斷電 latch 不新增粒子");
                controller.setWasPowered(true);
                var distant = frame.controllerPos().add(100000, 0, 100000);
                context.assertTrue(!context.getWorld().isChunkLoaded(distant), "控制組遠方 chunk 未載入");
                ChamberGlowEmitter.emit(context.getWorld(), distant);
                context.assertTrue(packets.isEmpty() && !context.getWorld().isChunkLoaded(distant), "不強載 chunk");
            } finally {
                context.getWorld().getServer().getPlayerManager().remove(observer);
            }
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void scheduled_glow_obeys_original_cadence_off_and_native_observation_distance(TestContext context) {
        var frame = build(context, true);
        var packets = new ArrayList<ParticleS2CPacket>();
        var observer = participant(context, false, packets);
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            packets.clear();
        });
        context.waitAndRun(25, () -> {
            boolean emitted = packets.stream().anyMatch(p -> glowPacketAt(p, frame));
            if (!emitted) context.getWorld().getServer().getPlayerManager().remove(observer);
            context.assertTrue(emitted, "既有 scheduledTick 會產生艙體輝光");
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
            packets.clear();
        });
        context.waitAndRun(50, () -> {
            try {
                context.assertTrue(packets.stream().noneMatch(p -> glowPacketAt(p, frame)), "斷電後週期不再新增粒子");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                observer.refreshPositionAndAngles(frame.controllerPos().getX() + 100, frame.controllerPos().getY(),
                        frame.controllerPos().getZ(), 0, 0);
                packets.clear();
                ChamberGlowEmitter.emit(context.getWorld(), frame.controllerPos());
                context.assertTrue(packets.isEmpty(), "原生非強制粒子不傳送到遠距離觀察者");
            } finally {
                context.getWorld().getServer().getPlayerManager().remove(observer);
            }
            context.complete();
        });
    }

    private static boolean glowPacketAt(ParticleS2CPacket packet, ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        return packet.getX() >= bounds.getMinX() - 0.5 && packet.getX() <= bounds.getMaxX() + 1.5
                && packet.getZ() >= bounds.getMinZ() - 0.5 && packet.getZ() <= bounds.getMaxZ() + 1.5;
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void native_failed_door_rollback_false_blocks_start_until_successful_repair(TestContext context) {
        failedDoorRollback(context, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100)
    public void native_failed_door_rollback_throw_blocks_start_until_successful_repair(TestContext context) {
        failedDoorRollback(context, true);
    }

    private static void failedDoorRollback(TestContext context, boolean throwing) {
        var frame = build(context, true);
        var world = context.getWorld();
        var player = participant(context, true);
        context.waitAndRun(2, () -> {
            var saved = ChamberSessions.gateway();
            int[] starts = {0};
            int[] consumptions = {0};
            int[] returns = {0};
            try {
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                var controller = ChamberGameTestBuilder.controller(context);
                UUID uuid = controller.chamberUuid();
                var faultGateway = new ChamberSessionGateway() {
                    @Override public Presence presence(net.minecraft.server.MinecraftServer server, UUID requested) {
                        return uuid.equals(requested) ? Presence.NONE : saved.presence(server, requested);
                    }
                    @Override public StartResult start(net.minecraft.server.world.ServerWorld origin,
                                                       ChamberControllerBlockEntity requested, java.util.List<UUID> participants) {
                        if (!uuid.equals(requested.chamberUuid())) return saved.start(origin, requested, participants);
                        starts[0]++;
                        if (player.hasStatusEffect(ModEffects.QUANTUM_STATE)) {
                            player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                            consumptions[0]++;
                        }
                        return StartResult.ARMED_ONLY;
                    }
                    @Override public boolean returnToOrigin(net.minecraft.server.world.ServerWorld origin, ChamberControllerBlockEntity requested) {
                        if (!uuid.equals(requested.chamberUuid())) return saved.returnToOrigin(origin, requested);
                        returns[0]++;
                        return true;
                    }
                };
                ChamberSessions.install(faultGateway);
                var result = DoorWriteFault.during(world, frame, throwing, () -> useController(context, frame, player));
                context.assertTrue(!result.isAccepted(), "rollback 失敗原生互動必須拒絕");
                assertDoors(context, frame, false);
                context.assertEquals(0, starts[0], "失敗交易仍 sealed 不得啟動");
                context.assertEquals(0, consumptions[0], "失敗交易不得消耗 buff");
                for (int index = 0; index < 3; index++) world.updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
                context.assertEquals(0, starts[0], "重複 neighbor 不得繞過故障");
                var nbt = controller.createNbt(world.getRegistryManager());
                world.removeBlockEntity(frame.controllerPos());
                var restored = new ChamberControllerBlockEntity(frame.controllerPos(), controller.getCachedState());
                restored.read(nbt, world.getRegistryManager());
                world.addBlockEntity(restored);
                ChamberSessions.install(saved);
                context.waitAndRun(25, () -> {
                    var previous = ChamberSessions.gateway();
                    ChamberSessions.install(faultGateway);
                    try {
                        context.assertTrue(restored.chamberState() != ChamberState.ARMED, "真實 20 tick 刷新不得繞過故障");
                        ChamberControllerBlock.refreshState(world, frame.controllerPos());
                        context.assertEquals(0, starts[0], "重載及 20 tick 刷新仍不可啟動");
                        context.assertEquals(0, consumptions[0], "重載及週期刷新不得消耗 buff");
                        context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE), "故障前效果仍存在");
                        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                        context.assertTrue(returns[0] > 0, "門故障不得阻止安全返還");
                        context.assertEquals(ChamberPowerState.OFF, ChamberRegistryState.get(world.getServer()).registry().records().get(uuid).powerState(), "返還後照常 OFF");
                        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                        context.assertEquals(0, starts[0], "單純復電不得解除故障");
                        context.assertTrue(useController(context, frame, player).isAccepted(), "完整成功開門解除故障");
                        assertDoors(context, frame, true);
                        context.assertEquals(0, starts[0], "成功開門尚未 sealed 不啟動");
                        context.assertTrue(useController(context, frame, player).isAccepted(), "修復後完整關門成功");
                        context.assertEquals(1, starts[0], "修復後只啟動一次");
                        context.assertEquals(1, consumptions[0], "僅修復後 backend 接獲合法啟動才消耗");
                    } finally {
                        ChamberSessions.install(previous);
                        world.getServer().getPlayerManager().remove(player);
                    }
                    context.complete();
                });
            } catch (RuntimeException | Error failure) {
                ChamberSessions.install(saved);
                world.getServer().getPlayerManager().remove(player);
                throw failure;
            }
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_power_enter_close_buff_return_repower_and_remove(TestContext context) {
        var frame = build(context, true);
        var world = context.getWorld();
        var player = participant(context, false);
        moveToController(player, frame);
        context.waitAndRun(2, () -> {
            var savedGateway = ChamberSessions.gateway();
            try {
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                var controller = ChamberGameTestBuilder.controller(context);
                var uuid = controller.chamberUuid();
                context.assertTrue(uuid != null, "先供電空艙取得 UUID");
                var gateway = new RecordingGateway(context, controller, player.getUuid());
                ChamberSessions.install(gateway);
                var inside = context.getAbsolutePos(new BlockPos(7, 2, 7));
                player.refreshPositionAndAngles(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, 0, 0);
                context.assertTrue(useController(context, frame, player).isAccepted(), "入艙關門");
                context.assertEquals(0, gateway.starts, "缺藥水不能啟動");
                player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 3600));
                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                state(context, ChamberState.ARMED, 11);
                context.assertEquals(1, gateway.starts, "持續高電位補 buff 啟動一次");
                context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE), "M1.2 不消耗效果");
                player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                moveToController(player, frame);
                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                state(context, ChamberState.ARMED, 11);
                context.assertEquals(1, gateway.starts, "活動 session 不因原艙空了重啟");
                gateway.returned = false;
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                state(context, ChamberState.ARMED, 11);
                context.assertTrue(!world.removeBlock(frame.controllerPos(), false), "返還未完成不得拆除");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                context.assertEquals(1, gateway.starts, "返還未完成復電不得另開 session");
                gateway.returned = true;
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                state(context, ChamberState.INVALID, 0);
                var registry = ChamberRegistryState.get(world.getServer()).registry();
                context.assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState(), "明確返還後才 OFF");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                context.assertEquals(uuid, controller.chamberUuid(), "未拆 Controller 復電沿用 UUID");
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                context.assertTrue(registry.records().get(uuid).enabled(), "一般停電不變更 enabled");
                ChamberControllerLoadSyncQueue.enqueue(world, controller);
                var replacementPending = new ChamberControllerBlockEntity(controller.getPos(), controller.getCachedState());
                replacementPending.setWorld(world);
                ChamberControllerLoadSyncQueue.enqueue(world, replacementPending);
                context.assertTrue(!world.removeBlock(frame.controllerPos(), false), "enqueue 後 OFF 尚未重新協調，不得普通拆除");
                ChamberControllerBlock.refreshState(world, frame.controllerPos());
                context.assertTrue(world.removeBlock(frame.controllerPos(), false), "OFF 原生 removeBlock 成功");
                context.assertTrue(!registry.records().containsKey(uuid), "原生成功移除清 record");
                context.assertTrue(!ChamberLoadSyncTestAccess.hasPending(world.getServer(), controller), "成功移除立即清同 BE queue");
                context.assertTrue(ChamberLoadSyncTestAccess.hasPending(world.getServer(), replacementPending), "不得清除同座標另一 BE 的 entry");
                context.assertTrue(registry.findAt(DimensionRole.OVERWORLD, frame.controllerPos().south(3)).isEmpty(), "原生移除清索引");
            } finally {
                ChamberSessions.install(savedGateway);
                world.getServer().getPlayerManager().remove(player);
            }
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_reloaded_off_blocks_ordinary_remove_before_held_high_sync(TestContext context) {
        reloadedOffRemainsProtected(context, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_reloaded_off_blocks_creative_attack_before_held_high_sync(TestContext context) {
        reloadedOffRemainsProtected(context, true);
    }

    private static void reloadedOffRemainsProtected(TestContext context, boolean creativeAttack) {
        var frame = build(context, true);
        var world = context.getWorld();
        var player = participant(context, false);
        player.changeGameMode(GameMode.CREATIVE);
        moveToController(player, frame);
        context.waitAndRun(2, () -> {
            try {
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                var original = ChamberGameTestBuilder.controller(context);
                var uuid = original.chamberUuid();
                var registry = ChamberRegistryState.get(world.getServer()).registry();
                var nbt = original.createNbt(world.getRegistryManager());
                context.assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState(), "已保存 OFF 前置條件");
                world.removeBlockEntity(frame.controllerPos());
                var restored = new ChamberControllerBlockEntity(frame.controllerPos(), original.getCachedState());
                restored.read(nbt, world.getRegistryManager());
                world.addBlockEntity(restored);
                world.setBlockState(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 2);
                context.assertTrue(ChamberLoadSyncTestAccess.hasPending(world.getServer(), restored)
                        && world.isReceivingRedstonePower(frame.controllerPos()), "重載未協調但實際高位");
                if (creativeAttack) {
                    nativeAttack(context, frame, player);
                    context.assertTrue(world.getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER), "pending OFF 必須阻擋創造模式拆除");
                } else {
                    context.assertTrue(!world.removeBlock(frame.controllerPos(), false), "pending OFF 必須阻擋普通拆除");
                }
                context.assertEquals(uuid, restored.chamberUuid(), "pending 不變更 UUID");
                context.assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState(), "runtime gate 不改 persisted OFF");
                context.waitAndRun(2, () -> {
                    try {
                        context.assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState(), "原生 queue 重讀高位");
                        context.assertTrue(!world.removeBlock(frame.controllerPos(), false), "高位協調完成仍保護");
                        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
                        context.assertTrue(world.removeBlock(frame.controllerPos(), false), "真實低位與返還完成才解除");
                        context.assertTrue(!registry.records().containsKey(uuid), "成功拆除清 record");
                    } finally {
                        world.getServer().getPlayerManager().remove(player);
                    }
                    context.complete();
                });
            } catch (RuntimeException | Error failure) {
                world.getServer().getPlayerManager().remove(player);
                throw failure;
            }
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_door_commit_starts_once_after_all_twenty_five_cells(TestContext context) {
        var frame = build(context, true);
        var player = participant(context, true);
        context.waitAndRun(2, () -> {
            var saved = ChamberSessions.gateway();
            try {
                context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
                var gateway = new RecordingGateway(context, ChamberGameTestBuilder.controller(context), player.getUuid());
                gateway.reentrantRefresh = true;
                ChamberSessions.install(gateway);
                context.assertTrue(useController(context, frame, player).isAccepted(), "整面關門提交成功");
                context.assertEquals(1, gateway.starts, "整面門關閉後僅啟動一次");
                state(context, ChamberState.ARMED, 11);
                ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
                context.assertEquals(1, gateway.starts, "持續高電位不重複啟動");
            } finally {
                ChamberSessions.install(saved);
                context.getWorld().getServer().getPlayerManager().remove(player);
            }
            context.complete();
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void off_cleanup_rejects_false_throw_wrong_be_and_same_block_refresh(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        var world = context.getWorld();
        var controller = ChamberGameTestBuilder.controller(context);
        var uuid = controller.chamberUuid();
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
        ChamberControllerBlock.refreshState(world, frame.controllerPos());
        var registry = ChamberRegistryState.get(world.getServer()).registry();
        context.assertTrue(!ChamberLifecycleService.mutateController(world, frame.controllerPos(), Blocks.AIR.getDefaultState(), () -> false), "false 原生移除不清理");
        try {
            ChamberLifecycleService.mutateController(world, frame.controllerPos(), Blocks.AIR.getDefaultState(), () -> { throw new IllegalStateException("預期移除失敗"); });
            throw new AssertionError("throw 必須保留");
        } catch (IllegalStateException expected) { }
        context.assertTrue(registry.records().containsKey(uuid), "false／throw 保留 record");
        context.assertTrue(!world.setBlockState(frame.controllerPos(), controller.getCachedState()), "相同狀態不視為移除");
        context.assertTrue(registry.records().containsKey(uuid), "同方塊刷新不清理");
        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        context.assertTrue(world.removeBlock(frame.controllerPos(), false), "測試一般底層移除錯誤 BE");
        context.assertTrue(registry.records().containsKey(uuid), "錯誤 projection BE 不得取得原艙清理權");
        finish(context, player);
    }

    private static final class RecordingGateway implements ChamberSessionGateway {
        private final TestContext context;
        private final ChamberControllerBlockEntity controller;
        private final UUID participant;
        private Presence presence = Presence.NONE;
        private boolean returned = true;
        private int starts;
        private boolean reentrantRefresh;
        private RecordingGateway(TestContext context, ChamberControllerBlockEntity controller, UUID participant) {
            this.context = context;
            this.controller = controller;
            this.participant = participant;
        }
        @Override public Presence presence(net.minecraft.server.MinecraftServer server, UUID uuid) {
            context.assertTrue(server == context.getWorld().getServer() && uuid.equals(controller.chamberUuid()), "gateway 接收精確 server 與 UUID");
            return presence;
        }
        @Override public StartResult start(net.minecraft.server.world.ServerWorld world, ChamberControllerBlockEntity requested,
                                           java.util.List<UUID> participants) {
            context.assertTrue(world == context.getWorld() && requested == controller, "啟動接收原 world 與 BE 實例");
            context.assertEquals(java.util.List.of(participant), participants, "啟動接收完整參與者 UUID");
            assertDoors(context, new ChamberFrame(controller.getPos(), controller.getCachedState().get(ChamberControllerBlock.FACING)), false);
            context.assertEquals(ChamberPowerState.POWERED, ChamberRegistryState.get(world.getServer()).registry()
                    .records().get(controller.chamberUuid()).powerState(), "啟動前已提交保護");
            starts++;
            if (starts == 1 && reentrantRefresh) ChamberControllerBlock.refreshState(world, controller.getPos());
            presence = Presence.ACTIVE;
            return StartResult.STARTED;
        }
        @Override public boolean returnToOrigin(net.minecraft.server.world.ServerWorld world, ChamberControllerBlockEntity requested) {
            context.assertTrue(world == context.getWorld() && requested == controller, "返還接收原艙實例");
            context.assertEquals(ChamberPowerState.RETURNING, ChamberRegistryState.get(world.getServer()).registry()
                    .records().get(controller.chamberUuid()).powerState(), "返還前先提交 RETURNING");
            if (returned) presence = Presence.NONE;
            return returned;
        }
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_lever_powers_empty_open_chamber_before_entry(TestContext context) {
        var frame = build(context, true);
        var lever = ChamberGameTestBuilder.CONTROLLER.north();
        context.setBlockState(lever, Blocks.LEVER.getDefaultState()
                .with(LeverBlock.FACE, BlockFace.WALL).with(LeverBlock.FACING, Direction.NORTH));
        var operator = participant(context, false);
        moveToController(operator, frame);
        context.waitAndRun(2, () -> {
            context.assertTrue(new ChamberOccupantService().findParticipants(context.getWorld(), frame).isEmpty(), "供電時艙內零人");
            context.useBlock(lever, operator);
            var uuid = ChamberGameTestBuilder.controller(context).chamberUuid();
            context.assertTrue(uuid != null, "有效開門空艙經原生拉桿供電即取得 UUID");
            context.assertEquals(ChamberPowerState.POWERED,
                    ChamberRegistryState.get(context.getWorld().getServer()).registry().records().get(uuid).powerState(), "先提交供電保護");
            context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos().add(0, -4, 3), Blocks.STONE.getDefaultState()), "進人前已禁止普通修改");
            state(context, ChamberState.IDLE, 3);
            finish(context, operator);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void own_registered_maintenance_callback_does_not_force_distant_unloaded_chunk(TestContext context) {
        var player = participant(context, false);
        var world = context.getWorld();
        var distant = new BlockPos(1_000_000, world.getBottomY() + 10, 1_000_000);
        context.assertTrue(!world.isChunkLoaded(distant), "遠距離目標 chunk 起初未載入");
        context.assertTrue(!player.canInteractWithBlockAt(distant, 1.0), "遠距離目標確實超出原生 reach");
        // 只測真實已註冊的模組 callback；Fabric router 基線會自行查詢遠端方塊，這不是原生端到端 noForce 證據。
        var eventResult = maintenanceCallback().interact(player, world, Hand.MAIN_HAND, distant, Direction.NORTH);
        context.assertEquals(ActionResult.PASS, eventResult, "遠距離 callback 回 PASS");
        context.assertTrue(!world.isChunkLoaded(distant), "模組 callback 本身不得強載 chunk");
        finish(context, player);
    }

    private static AttackBlockCallback maintenanceCallback() {
        try {
            var field = AttackBlockCallback.EVENT.getClass().getDeclaredField("handlers");
            field.setAccessible(true);
            for (var handler : (AttackBlockCallback[]) field.get(AttackBlockCallback.EVENT)) {
                if (handler.getClass().getNestHost() == ChamberMaintenanceInteraction.class) return handler;
            }
            throw new AssertionError("Origin 維護 callback 尚未註冊");
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("無法讀取真實事件監聽器", exception);
        }
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_empty_hands_sneaking_disables_without_shell_and_offhand_does_not_toggle_twice(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.assertTrue(attempt(context).accepted(), "維護 fixture 已註冊");
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var controller = ChamberGameTestBuilder.controller(context);
        var uuid = controller.chamberUuid();
        ChamberProtectionService.get().authorizedMutation(() -> context.getWorld().removeBlock(frame.controllerPos().west(3), false));
        player.setSneaking(true);
        context.assertTrue(nativeUse(context, frame, player, Hand.MAIN_HAND).isAccepted(), "破損 shell 仍可停用");
        context.assertTrue(!registry.records().get(uuid).enabled(), "雙空手蹲下切換為停用");
        state(context, ChamberState.INVALID, 0);
        nativeUse(context, frame, player, Hand.OFF_HAND);
        context.assertTrue(!registry.records().get(uuid).enabled(), "off-hand 不重複切換");
        context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos().add(0, -4, 3), Blocks.STONE.getDefaultState()), "停用仍保護 volume");
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_sneaking_item_placement_and_spectator_preserve_enabled(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        var uuid = ChamberGameTestBuilder.controller(context).chamberUuid();
        player.setSneaking(true);
        player.setStackInHand(Hand.MAIN_HAND, new ItemStack(Items.LEVER));
        context.assertTrue(nativeUse(context, frame, player, Hand.MAIN_HAND).isAccepted(), "持物蹲下走原生放置");
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos().north()).isOf(Blocks.LEVER), "拉桿實際放置");
        context.assertTrue(registry.records().get(uuid).enabled(), "持物不切換 enabled");
        player.setStackInHand(Hand.MAIN_HAND, ItemStack.EMPTY);
        player.setStackInHand(Hand.OFF_HAND, new ItemStack(Items.COMPARATOR));
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        context.assertTrue(registry.records().get(uuid).enabled(), "副手持物不切換");
        player.setStackInHand(Hand.OFF_HAND, ItemStack.EMPTY);
        player.changeGameMode(GameMode.SPECTATOR);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        context.assertTrue(registry.records().get(uuid).enabled(), "旁觀者不得維護");
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_reenable_held_high_arms_and_disabled_door_still_works(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        player.setSneaking(true);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        state(context, ChamberState.INVALID, 0);
        player.setSneaking(false);
        context.assertTrue(nativeUse(context, frame, player, Hand.MAIN_HAND).isAccepted(), "停用仍可開門");
        assertDoors(context, frame, true);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        assertDoors(context, frame, false);
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
        state(context, ChamberState.INVALID, 0);
        player.setSneaking(true);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        state(context, ChamberState.ARMED, 11);
        var controller = ChamberGameTestBuilder.controller(context);
        context.assertTrue(controller.wasPowered() && controller.powerInitialized(), "重新啟用同步實際高電位");
        context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
        state(context, ChamberState.ARMED, 11);
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_creative_attack_dismantles_only_off_origin_and_releases_volume(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        var controller = ChamberGameTestBuilder.controller(context);
        var uuid = controller.chamberUuid();
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        moveToController(player, frame);
        nativeAttack(context, frame, player);
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER), "啟用中不能左鍵拆除");
        player.setSneaking(true);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        context.assertTrue(!registry.records().get(uuid).enabled(), "拆除前先停用");
        nativeAttack(context, frame, player);
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER), "停用但高電位仍不可拆除");
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        context.assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState(), "斷電返還完成");
        nativeAttack(context, frame, player);
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos()).isAir(), "Controller 真正移除為 air");
        context.assertTrue(controller.isRemoved(), "原 BE 已 removed");
        context.assertTrue(!registry.records().containsKey(uuid), "成功後移除 record");
        var bounds = ChamberGeometry.bounds(frame);
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++) for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) {
            context.assertTrue(registry.findAt(DimensionRole.OVERWORLD, new BlockPos(x, bounds.getMinY(), z)).isEmpty(), "全部 bounds 無 ghost 保護");
        }
        context.assertTrue(context.getWorld().setBlockState(frame.controllerPos().add(0, -4, 3), Blocks.STONE.getDefaultState()), "普通 volume 修改恢復");
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos().west(3)).isOf(Blocks.BEDROCK), "不自動拆其他 shell");
        context.getWorld().removeBlock(frame.controllerPos().add(0, -4, 3), false);
        context.getWorld().setBlockState(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                .with(ChamberControllerBlock.FACING, frame.outwardFacing()));
        var interior = context.getAbsolutePos(new BlockPos(7, 2, 7));
        player.refreshPositionAndAngles(interior.getX() + 0.5, interior.getY(), interior.getZ() + 0.5, 0, 0);
        assertDraft(context, frame);
        context.waitAndRun(25, () -> {
            assertDraft(context, frame);
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
            context.assertTrue(!ChamberGameTestBuilder.controller(context).chamberUuid().equals(uuid), "同位置重建需新 edge 與新 UUID");
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_attack_rejects_survival_projection_mismatch_and_distant_player(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        var controller = ChamberGameTestBuilder.controller(context);
        var uuid = controller.chamberUuid();
        var registry = ChamberRegistryState.get(context.getWorld().getServer()).registry();
        new ChamberLifecycleService(registry).setEnabled(uuid, false);
        moveToController(player, frame);
        player.changeGameMode(GameMode.SURVIVAL);
        nativeAttack(context, frame, player);
        assertOriginRemains(context, frame, uuid);
        player.changeGameMode(GameMode.CREATIVE);
        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        nativeAttack(context, frame, player);
        assertOriginRemains(context, frame, uuid);
        controller.setInstanceKind(ChamberInstanceKind.ORIGIN);
        controller.setChamberUuid(UUID.randomUUID());
        nativeAttack(context, frame, player);
        assertOriginRemains(context, frame, uuid);
        controller.setChamberUuid(uuid);
        player.refreshPositionAndAngles(frame.controllerPos().getX() + 100, frame.controllerPos().getY(), frame.controllerPos().getZ(), 0, 0);
        nativeAttack(context, frame, player);
        assertOriginRemains(context, frame, uuid);
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void maintenance_adapter_rejects_wrong_be_world_facing_removed_uuid_and_projection(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        attempt(context);
        var world = context.getWorld();
        var controller = ChamberGameTestBuilder.controller(context);
        var uuid = controller.chamberUuid();
        var registry = ChamberRegistryState.get(world.getServer()).registry();
        var original = registry.records().get(uuid);
        var nether = world.getServer().getWorld(World.NETHER);
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(nether, frame.controllerPos(), player).isAccepted(), "錯誤世界拒絕");
        controller.setWorld(nether);
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), player).isAccepted(), "BE world 身分錯誤拒絕");
        controller.setWorld(world);
        var cached = controller.getCachedState();
        controller.setCachedState(cached.with(ChamberControllerBlock.FACING, Direction.SOUTH));
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), player).isAccepted(), "BE cached facing 錯誤拒絕");
        controller.setCachedState(cached);
        controller.markRemoved();
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), player).isAccepted(), "removed BE 拒絕");
        controller.cancelRemoval();
        controller.setChamberUuid(null);
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), player).isAccepted(), "已註冊位置缺 UUID 拒絕");
        controller.setChamberUuid(uuid);
        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        context.assertTrue(!ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), player).isAccepted(), "Projection 維護拒絕");
        controller.setInstanceKind(ChamberInstanceKind.ORIGIN);
        context.assertEquals(original, registry.records().get(uuid), "所有拒絕保留原紀錄");
        context.assertTrue(!world.removeBlock(frame.controllerPos(), false), "所有拒絕保留保護");
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void exact_target_scope_rejects_other_cell_and_world_restores_nested_scope_and_throw(TestContext context) {
        var frame = build(context, false);
        registerEligibleFixture(context);
        var protection = ChamberProtectionService.get();
        var world = context.getWorld();
        var target = frame.controllerPos();
        var other = target.add(0, -4, 3);
        var nether = world.getServer().getWorld(World.NETHER);
        ChamberRegistryState.get(world.getServer()).registry().registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, frame);
        protection.authorizedMutation(world, target, () -> {
            context.assertTrue(!world.setBlockState(other, Blocks.STONE.getDefaultState()), "target scope 不授權另一格");
            context.assertTrue(!protection.mayMutate(nether, target), "不同 world identity 不授權");
            protection.authorizedMutation(world, other, () -> {
                context.assertTrue(protection.mayMutate(world, other), "巢狀目標可寫入");
                context.assertTrue(!protection.mayMutate(world, target), "內層取代外層 scope");
                return true;
            });
            context.assertTrue(protection.mayMutate(world, target), "內層結束恢復外層 scope");
            context.assertTrue(!protection.mayMutate(world, other), "外層不洩漏內層授權");
            try {
                protection.authorizedMutation(world, other, () -> { throw new IllegalStateException("巢狀預期例外"); });
            } catch (IllegalStateException expected) { }
            context.assertTrue(protection.mayMutate(world, target), "巢狀 throw 後恢復外層 target");
            context.assertTrue(!protection.mayMutate(world, other), "巢狀 throw 不洩漏授權");
            return true;
        });
        try {
            protection.authorizedMutation(world, target, () -> { throw new IllegalStateException("預期例外"); });
        } catch (IllegalStateException expected) { }
        context.assertTrue(!world.removeBlock(target, false), "throw 後一般移除仍受保護");
        context.assertTrue(protection.authorizedMutation(world, target, () -> world.removeBlock(target, false)), "精確授權可移除目標 C");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_draft_maintenance_does_not_register_and_creative_break_remains_vanilla(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        player.setSneaking(true);
        nativeUse(context, frame, player, Hand.MAIN_HAND);
        assertDraft(context, frame);
        moveToController(player, frame);
        nativeAttack(context, frame, player);
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos()).isAir(), "未註冊草稿保留原生 Creative 破壞");
        finish(context, player);
    }

    private static ActionResult nativeUse(TestContext context, ChamberFrame frame, ServerPlayerEntity player, Hand hand) {
        var pos = frame.controllerPos();
        return player.interactionManager.interactBlock(player, context.getWorld(), player.getStackInHand(hand), hand,
                new BlockHitResult(Vec3d.ofCenter(pos), Direction.NORTH, pos, false));
    }

    private static void nativeAttack(TestContext context, ChamberFrame frame, ServerPlayerEntity player) {
        player.interactionManager.processBlockBreakingAction(frame.controllerPos(),
                PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, Direction.NORTH, context.getWorld().getTopY(), 0);
    }

    private static void moveToController(ServerPlayerEntity player, ChamberFrame frame) {
        var pos = frame.controllerPos();
        player.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY() - 1, pos.getZ() - 2, 0, 0);
    }

    private static void assertOriginRemains(TestContext context, ChamberFrame frame, UUID uuid) {
        context.assertTrue(context.getWorld().getBlockState(frame.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER), "拒絕不得移除 Controller");
        context.assertTrue(ChamberRegistryState.get(context.getWorld().getServer()).registry().records().containsKey(uuid), "拒絕保留 record");
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unpowered_draft_waits_for_native_lever_and_falling_power_unlocks(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.INVALID, 0);
        assertDraft(context, frame);
        context.assertTrue(useController(context, frame, player).isAccepted(), "草稿可開門");
        state(context, ChamberState.INVALID, 0);
        assertDraft(context, frame);
        context.assertTrue(useController(context, frame, player).isAccepted(), "草稿可關門");
        assertDraft(context, frame);
        var lever = ChamberGameTestBuilder.CONTROLLER.north();
        context.setBlockState(lever, Blocks.LEVER.getDefaultState()
                .with(LeverBlock.FACE, BlockFace.WALL).with(LeverBlock.FACING, Direction.NORTH));
        context.waitAndRun(25, () -> {
            state(context, ChamberState.INVALID, 0);
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
            context.assertTrue(context.getWorld().setBlockState(frame.controllerPos().add(0, -4, 3),
                    Blocks.STONE.getDefaultState()), "斷電返還後原艙允許普通修改");
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void zero_participant_power_registers_without_arming(TestContext context) {
        rejectedDraftPulse(context, false, false, false, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void unbuffed_power_registers_without_arming(TestContext context) {
        rejectedDraftPulse(context, false, true, false, false);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void open_door_power_registers_without_arming(TestContext context) {
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
            if (missingShell) assertDraft(context, frame);
            else context.assertTrue(ChamberGameTestBuilder.controller(context).chamberUuid() != null, "有效 shell 先註冊");
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.AIR);
            if (missingShell) assertDraft(context, frame);
            else context.assertEquals(ChamberPowerState.OFF, ChamberRegistryState.get(context.getWorld().getServer()).registry()
                    .records().get(ChamberGameTestBuilder.controller(context).chamberUuid()).powerState(), "低電位保留 UUID 並解除保護");
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
    public void controller_interaction_toggles_all_cells_and_rearms_after_commit(TestContext context) {
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
                state(context, ChamberState.ARMED, 11);
                context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
                state(context, ChamberState.ARMED, 11);
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
                state(context, ChamberState.ARMED, 11);
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
    public void controller_load_and_periodic_refresh_read_actual_power_and_arm(TestContext context) {
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
            state(context, ChamberState.ARMED, 11);
            context.assertTrue(controller.chamberUuid() != null, "載入時高電位可註冊");
            // 故意讓快取偏離世界電位，後續有限週期刷新必須再次讀取真實電位。
            controller.setWasPowered(false);
        });
        context.waitAndRun(25, () -> {
            context.assertTrue(controller.powerInitialized() && controller.wasPowered(), "一般 refresh 仍讀真實電位");
            state(context, ChamberState.ARMED, 11);
            finish(context, player);
        });
    }

    private static ChamberFrame build(TestContext context, boolean open) {
        return ChamberGameTestBuilder.build(context, open, Direction.NORTH, false);
    }

    private static ServerPlayerEntity participant(TestContext context, boolean buffed) {
        return participant(context, buffed, null);
    }

    private static ServerPlayerEntity participant(TestContext context, boolean buffed,
            List<ParticleS2CPacket> particles) {
        // Vanilla helper 將 isSpectator 固定為 false；此處使用未覆寫的真實 ServerPlayerEntity。
        UUID uuid = UUID.randomUUID();
        var data = ConnectedClientData.createDefault(new GameProfile(uuid, "m1-" + uuid.toString().substring(0, 8)), false);
        var player = new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(), data.gameProfile(), data.syncedOptions());
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        var channel = new EmbeddedChannel(connection);
        if (particles != null) channel.pipeline().addLast(new ChannelOutboundHandlerAdapter() {
            @Override public void write(ChannelHandlerContext channelContext, Object message,
                                        ChannelPromise promise) throws Exception {
                if (message instanceof ParticleS2CPacket particle) particles.add(particle);
                super.write(channelContext, message, promise);
            }
        });
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
        var result = new ChamberActivationService().attemptArm(context.getWorld(), ChamberGameTestBuilder.controller(context));
        if (result.accepted()) {
            context.getWorld().setBlockState(context.getAbsolutePos(ChamberGameTestBuilder.CONTROLLER.up()), Blocks.REDSTONE_BLOCK.getDefaultState(), 2);
            ChamberControllerBlock.refreshState(context.getWorld(), context.getAbsolutePos(ChamberGameTestBuilder.CONTROLLER));
        }
        return result;
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
    public void valid_closed_buffed_draft_previews_ready_but_unpowered_output_is_zero(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.assertTrue(new ChamberActivationService().evaluateReadiness(
                context.getWorld(), ChamberGameTestBuilder.controller(context)).accepted(), "有效且有 buff 的參與者可預覽");
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.INVALID, 0);
        assertDraft(context, frame);
        finish(context, player);
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void open_bulkhead_stays_idle(TestContext context) {
        var frame = build(context, true);
        var player = participant(context, true);
        ChamberControllerBlock.refreshState(context.getWorld(), frame.controllerPos());
        state(context, ChamberState.INVALID, 0);
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
    public void held_high_reconciles_readiness_without_another_edge(TestContext context) {
        var frame = build(context, false);
        var player = participant(context, true);
        context.waitAndRun(2, () -> {
            context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
            // 可見狀態落後時由實際高電位重新協調。
            ChamberGameTestBuilder.controller(context).setChamberState(ChamberState.READY);
            context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
            finish(context, player);
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void held_power_registers_and_arms_after_reload_sync(TestContext context) {
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
            context.assertTrue(restored.chamberUuid() != null, "載入高電位註冊有效原艙");
            context.getWorld().updateNeighborsAlways(frame.controllerPos().up(), Blocks.REDSTONE_BLOCK);
            state(context, ChamberState.ARMED, 11);
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
        context.setBlockState(ChamberGameTestBuilder.CONTROLLER.up(), Blocks.REDSTONE_BLOCK);
        context.waitAndRun(25, () -> {
            state(context, ChamberState.IDLE, 3);
            context.assertEquals(3, ((ComparatorBlockEntity) context.getBlockEntity(comparatorPos)).getOutputSignal(), "真實 comparator 輸出 IDLE");
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 3600));
        });
        context.waitAndRun(50, () -> {
            state(context, ChamberState.ARMED, 11);
            context.assertEquals(11, ((ComparatorBlockEntity) context.getBlockEntity(comparatorPos)).getOutputSignal(), "真實 comparator 收到自動 ARMED 更新");
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
            state(context, ChamberState.ARMED, 11);
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
