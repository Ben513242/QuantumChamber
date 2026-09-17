package dev.quantumchamber.gametest;

import com.mojang.authlib.GameProfile;
import com.sun.jna.Platform;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpoint;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpointAccess;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.registry.ModEffects;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.corridor.SessionEntranceDoorService;
import java.util.Set;
import java.util.List;
import java.util.Map;

public final class M2CorridorGameTests implements FabricGameTest {
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_powered_entry", tickLimit = 100000)
    public void native_lever_closed_buffed_cohort_enters_fixed_world_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 真供電入場 checkpoint");
            context.complete(); return;
        }
        var frame = ChamberGameTestBuilder.buildForSession(context,true,Direction.NORTH,false);
        var connection = new ConnectedGameTestPlayer(context.getWorld());
        var player = connection.player();
        player.setNoGravity(true);
        var lever = ChamberGameTestBuilder.CONTROLLER.north();
        context.setBlockState(lever, net.minecraft.block.Blocks.LEVER.getDefaultState()
                .with(net.minecraft.block.LeverBlock.FACE,net.minecraft.block.enums.BlockFace.WALL)
                .with(net.minecraft.block.LeverBlock.FACING,Direction.NORTH));
        context.waitAndRun(2,() -> {
            context.useBlock(lever,player);
            player.refreshPositionAndAngles(dev.quantumchamber.corridor.CorridorGeometry.position(frame,3.5,1,3.5),37,12);
            player.getInventory().setStack(0,new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH,7));
            context.useBlock(ChamberGameTestBuilder.CONTROLLER.down(3),player);
            var potion = net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.POTION,
                    dev.quantumchamber.registry.ModPotions.QUANTUM_STATE);
            potion.finishUsing(context.getWorld(),player);
            context.assertEquals(3600,player.getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"真喝正式3600tick藥水");
            // GT加速tick會在chunk I/O期間跑數千tick；只有等待fixture延長，不改正式藥水數值。
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000));
        });
        when(context,3,() -> player.getServerWorld()==context.getWorld().getServer().getWorld(
                dev.quantumchamber.superposition.SuperpositionWorld.KEY),tick -> {
            try {
                context.assertTrue(player.getServerWorld() == context.getWorld().getServer().getWorld(
                        dev.quantumchamber.superposition.SuperpositionWorld.KEY),"完整cohort必須進入真實固定世界");
                context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE),"成功入場才消耗藥效");
                context.assertTrue(player.getInventory().getStack(0).isOf(net.minecraft.item.Items.TORCH),"入場保留攜帶物品");
                context.complete();
            } finally { connection.close(); }
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_missing_buff",tickLimit=100)
    public void native_missing_one_buff_rejects_whole_cohort(TestContext context) {
        var fixture=new NativeEntry(context,2);
        fixture.players.get(1).player().removeStatusEffect(ModEffects.QUANTUM_STATE);
        context.waitAndRun(2,fixture::power);
        context.waitAndRun(30,() -> {
            try {
                fixture.assertNoSession();
                context.assertTrue(fixture.players.get(0).player().hasStatusEffect(ModEffects.QUANTUM_STATE),"少一人buff不消耗其他人效果");
                context.complete();
            } finally { fixture.close(); }
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_zero",tickLimit=100)
    public void native_zero_people_never_starts_session(TestContext context) {
        var fixture=new NativeEntry(context,0);
        context.waitAndRun(2,fixture::power);
        context.waitAndRun(30,() -> { try { fixture.assertNoSession(); context.complete(); } finally { fixture.close(); } });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_spectator",tickLimit=100)
    public void native_only_spectator_never_starts_or_consumes(TestContext context) {
        var fixture=new NativeEntry(context,1);
        fixture.players.getFirst().player().changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
        context.waitAndRun(2,fixture::power);
        context.waitAndRun(30,() -> {
            try { fixture.assertNoSession(); context.assertTrue(fixture.players.getFirst().player().hasStatusEffect(ModEffects.QUANTUM_STATE),
                    "spectator不消耗效果"); context.complete(); } finally { fixture.close(); }
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_capacity",tickLimit=100)
    public void native_twenty_six_people_rejected_before_reservation(TestContext context) {
        var fixture=new NativeEntry(context,26);
        context.waitAndRun(2,fixture::power);
        context.waitAndRun(30,() -> {
            try { fixture.assertNoSession();
                context.assertTrue(fixture.players.stream().allMatch(connection -> connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE)),
                        "超過25floor slots時全員效果保持"); context.complete(); } finally { fixture.close(); }
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_partial",tickLimit=100000)
    public void native_second_move_failure_rolls_back_hidden_nbt_whole_cohort_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2);
        fixture.players.forEach(connection -> connection.player().addStatusEffect(
                new StatusEffectInstance(ModEffects.QUANTUM_STATE,800_000,2,true,false,false)));
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record();
            context.assertTrue(initial.participants().stream().allMatch(person -> person.quantumStateSnapshot().contains("hidden_effect")),
                    "原生效果必須真的具有hidden chain，不能copy constructor冒充");
            var fault=new SessionTransferFault(context.getWorld(),initial,SessionTransferFault.Case.SECOND_MOVE);
            when(context,tick+1,() -> fixture.returning(),ignored -> {
                try {
                    context.assertTrue(fault.hit() && fault.successfulMoves()==1,"真首人移動成功後第二move才命中失敗");
                    context.assertTrue(fault.rollbackVerified(),"checked RETURNING同tick完整NBT／原pose／bbox rollback");
                    context.assertTrue(fault.rollbackTick()>=0 && fault.rollbackTick()<=fixture.server.getTicks(),"真same-tick capture sentinel");
                    context.assertTrue(fixture.record().restoreEntryEffectOnReturn(),"false提交前維持true政策");
                    context.assertEquals(initial.participants(),fixture.record().participants(),"journal保留原完整cohort與source NBT");
                    fixture.assertInventory(); fixture.assertProtected();
                    context.assertEquals(1,fault.returnFlushes(),"已checked同筆RETURNING重複gateway請求不得再flush其他case的staged資料");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("Task3 partial rollback：SID={} cohort={} captureTick={} {}",
                            initial.sessionUuid(),initial.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList(),
                            fault.rollbackTick(),fault.rollbackEvidence());
                    context.complete();
                } finally { fault.close(); fixture.close(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_publish",tickLimit=100000)
    public void native_after_false_publish_failure_keeps_current_hidden_effect_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record();
            var fault=new SessionTransferFault(context.getWorld(),initial,SessionTransferFault.Case.PUBLISH_AFTER_FALSE);
            when(context,tick+1,() -> fixture.returning(),ignored -> {
                try {
                    context.assertTrue(fault.hit() && fault.falseVerified(),"fault必須命中真SID的durable false publish邊界");
                    context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"false已提交後不能倒退補發entry效果");
                    context.assertTrue(fixture.players.stream().allMatch(connection -> connection.player().getServerWorld()==fixture.target),
                            "提交後不執行precommit rollback");
                    var affected=fixture.server.getPlayerManager().getPlayer(fault.affected());
                    context.assertTrue(affected.hasStatusEffect(ModEffects.QUANTUM_STATE)
                            && affected.getStatusEffect(ModEffects.QUANTUM_STATE).getAmplifier()==2
                            && ((NbtCompound)affected.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt()).contains("hidden_effect"),
                            "提交後新效果與hidden chain不得刪除");
                    context.assertEquals(fault.newEffect(),affected.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"post-false完整新NBT精確不變");
                    context.assertTrue(fixture.players.stream().allMatch(connection -> connection.player().getUuid().equals(fault.affected())
                            || !connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE)),"其他cohort成員不可補發entry效果");
                    fixture.assertInventory(); fixture.assertProtected();
                    context.complete();
                } finally { fault.close(); fixture.close(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_expiry",tickLimit=100000)
    public void native_buff_expiry_during_geometry_keeps_precommit_returning(TestContext context) {
        var fixture=new NativeEntry(context,1);
        var player=fixture.players.getFirst().player();
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,startedTick -> {
        context.assertEquals(SessionState.ARMING,fixture.record().state(),"先證實geometry準備期");
        player.removeStatusEffect(ModEffects.QUANTUM_STATE);
        player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1));
        // EmbeddedChannel不由NetworkIo輪詢；明確執行原生network handler使用的playerTick讓效果自然到期。
        player.playerTick();
        context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE),"原生playerTick自然expiry，非直接remove冒充");
        when(context,startedTick+1,() -> fixture.returning(),tick -> {
            try {
                context.assertTrue(fixture.record().restoreEntryEffectOnReturn(),"自然expiry沒有false提交");
                context.assertEquals(0L,fixture.pages.currentMappings(fixture.record().sessionUuid()).epoch(),"geometry準備時不可publish");
                context.assertTrue(player.getServerWorld()==context.getWorld(),"expiry前尚未移動亦不可留下半cohort");
                fixture.assertProtected(); context.complete();
            } finally { fixture.close(); }
        });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_shared_north",tickLimit=100000)
    public void native_shared_entry_preserves_frozen_relative_pose_north_windows(TestContext context) { sharedEntry(context,Direction.NORTH); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_shared_east",tickLimit=100000)
    public void native_shared_entry_preserves_frozen_relative_pose_east_windows(TestContext context) { sharedEntry(context,Direction.EAST); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_shared_south",tickLimit=100000)
    public void native_shared_entry_preserves_frozen_relative_pose_south_windows(TestContext context) { sharedEntry(context,Direction.SOUTH); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_shared_west",tickLimit=100000)
    public void native_shared_entry_preserves_frozen_relative_pose_west_windows(TestContext context) { sharedEntry(context,Direction.WEST); }
    private static void sharedEntry(TestContext context,Direction facing) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2,facing);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,startedTick -> {
            var initial=fixture.record(); var chamber=fixture.controller().chamberUuid();
            context.assertEquals(SessionState.ARMING,initial.state(),"先checked ARMING,true才geometry／移動");
            context.assertTrue(initial.restoreEntryEffectOnReturn(),"準備期true政策");
            context.assertEquals(2,initial.participants().size(),"完整兩人真cohort");
            context.assertEquals(dev.quantumchamber.chamber.ChamberState.READY,fixture.controller().chamberState(),"來源staging comparator7");
            for(int i=0;i<3;i++) new dev.quantumchamber.chamber.ChamberPowerCoordinator().refresh(context.getWorld(),fixture.frame.controllerPos());
            context.assertEquals(initial.sessionUuid(),fixture.record().sessionUuid(),"held-high刷新不重複start");
            when(context,startedTick+1,fixture::activeReady,publishedTick -> {
                var entrance=fixture.pages.entrance(initial.sessionUuid());
                var delta=new Vec3d(entrance.controllerPos().getX()-fixture.frame.controllerPos().getX(),
                        entrance.controllerPos().getY()-fixture.frame.controllerPos().getY(),entrance.controllerPos().getZ()-fixture.frame.controllerPos().getZ());
                for(var person : initial.participants()) {
                    var player=fixture.server.getPlayerManager().getPlayer(person.playerUuid());
                    context.assertTrue(player.getServerWorld()==fixture.target && fixture.target.getEntity(person.playerUuid())==player,"真fixed world與UUID");
                    context.assertTrue(player.getPos().squaredDistanceTo(person.sourcePosition().add(delta))<1e-12,"same facing物理平移精確保留相對pose");
                    context.assertEquals(person.sourceVelocity(),player.getVelocity(),"velocity不變");
                    context.assertEquals(person.yaw(),player.getYaw(),"yaw不變"); context.assertEquals(person.pitch(),player.getPitch(),"pitch不變");
                    context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE),"完整群一次消耗");
                    context.assertTrue(dev.quantumchamber.chamber.ChamberOccupantService.contains(
                            dev.quantumchamber.chamber.ChamberGeometry.interiorBox(entrance),player.getBoundingBox()),"全員完整bbox位於replica");
                }
                fixture.assertInventory();
                context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"native checkpoint後checked false已提交");
                context.assertEquals(initial.participants(),fixture.record().participants(),"immutable entry snapshots保留完整cohort");
                var replica=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)fixture.target.getBlockEntity(entrance.controllerPos());
                context.assertEquals(ChamberInstanceKind.PROJECTION,replica.instanceKind(),"replica不是另一Origin");
                context.assertEquals(chamber,replica.chamberUuid(),"replica正確來源UUID");
                context.runAtTick(publishedTick+25,() -> {
                    try {
                        context.assertEquals(ChamberInstanceKind.PROJECTION,replica.instanceKind(),"load-sync／periodic仍projection");
                        context.assertEquals(chamber,replica.chamberUuid(),"periodic不改UUID");
                        context.assertEquals(dev.quantumchamber.chamber.ChamberState.ARMED,fixture.controller().chamberState(),"ACTIVE空原艙不降級3");
                        context.assertEquals(dev.quantumchamber.chamber.ChamberSessionGateway.Presence.ACTIVE,
                                dev.quantumchamber.chamber.ChamberSessions.gateway().presence(fixture.server,chamber),"同sid維持active");
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("Task3 native shared：facing={} SID={} cohort={} immutableSources={} actualInventory=true epoch={} rearOpen=true",
                                facing,initial.sessionUuid(),initial.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList(),
                                initial.participants(),fixture.pages.currentMappings(initial.sessionUuid()).epoch());
                        var first=fixture.players.getFirst().player();
                        first.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,500_000,1));
                        var newEffect=first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
                        fixture.power();
                        context.assertEquals(SessionState.RETURNING,fixture.record().state(),"真外部拉桿低位請求安全返還");
                        context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"ACTIVE轉返還不能改回true");
                        context.assertEquals(newEffect,first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"返還請求不得刪新效果");
                        context.assertTrue(first.getServerWorld()==fixture.target,"Task4 backend未實作時保持真pending，不假移回");
                        fixture.trustedTeardown(publishedTick+26,context::complete);
                    } catch(RuntimeException | Error failure) { fixture.close(); throw failure; }
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_offline",tickLimit=100000)
    public void native_staging_disconnect_keeps_frozen_offline_participant(TestContext context) {
        var fixture=new NativeEntry(context,2);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); var disconnected=fixture.players.remove(1); var absent=disconnected.player().getUuid();
            disconnected.close();
            context.assertTrue(fixture.server.getPlayerManager().getPlayer(absent)==null && context.getWorld().getEntity(absent)==null,
                    "第二位真離線，不以returned flag或mock冒充");
            when(context,tick+1,fixture::returning,ignored -> {
                try {
                    context.assertEquals(initial.participants(),fixture.record().participants(),"失去online玩家仍保留完整原cohort來源快照");
                    context.assertTrue(fixture.record().restoreEntryEffectOnReturn(),"offline staging沒有false提交");
                    context.assertEquals(0L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"offline不得publish");
                    context.assertTrue(fixture.players.getFirst().player().getServerWorld()==context.getWorld()
                            && fixture.players.getFirst().player().hasStatusEffect(ModEffects.QUANTUM_STATE),"在線者保持來源與效果");
                    fixture.assertProtected(); context.complete();
                } finally { fixture.close(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_cohort_change",tickLimit=100000)
    public void native_staging_extra_occupant_rejects_without_shortening_journal(TestContext context) {
        var fixture=new NativeEntry(context,2);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); var extra=new ConnectedGameTestPlayer(context.getWorld()); fixture.players.add(extra);
            var player=extra.player(); player.setNoGravity(true);
            player.refreshPositionAndAngles(CorridorGeometry.position(fixture.frame,3.5,1,3.5),0,0);
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000));
            when(context,tick+1,fixture::returning,failedTick -> {
                context.assertEquals(initial.participants(),fixture.record().participants(),"新加入人不能改寫既有凍結cohort");
                context.assertTrue(fixture.record().participants().stream().noneMatch(person -> person.playerUuid().equals(player.getUuid())),
                        "額外UUID不是已授權入場cohort");
                context.assertTrue(player.getServerWorld()==context.getWorld() && player.hasStatusEffect(ModEffects.QUANTUM_STATE),"額外人不搬移／不消耗");
                fixture.assertProtected();
                if(Platform.isWindows()) fixture.trustedTeardown(failedTick+1,context::complete);
                else { fixture.close(); context.complete(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_staging_low",tickLimit=100000)
    public void native_staging_low_keeps_true_pending_and_source_protection(TestContext context) {
        var fixture=new NativeEntry(context,2);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); fixture.power();
            context.assertEquals(SessionState.RETURNING,fixture.record().state(),"真低位先請求返還");
            context.assertTrue(fixture.record().restoreEntryEffectOnReturn(),"未提交的staging取消維持true");
            context.assertEquals(initial.participants(),fixture.record().participants(),"取消不可重建短cohort");
            context.assertTrue(fixture.players.stream().allMatch(connection -> connection.player().getServerWorld()==context.getWorld()
                    && connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE)),"低位全員仍在來源且效果未消耗");
            context.assertEquals(0L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"低位不可publish");
            fixture.assertProtected();
            if(Platform.isWindows()) fixture.trustedTeardown(tick+1,context::complete);
            else { fixture.close(); context.complete(); }
        });
    }
    /** 真backend的來源fixture；不改global gateway、不假返還或清除durable pending。 */
    private static final class NativeEntry implements AutoCloseable {
        final TestContext context;
        final net.minecraft.server.MinecraftServer server;
        final net.minecraft.server.world.ServerWorld target;
        final dev.quantumchamber.chamber.ChamberFrame frame;
        final CorridorPageManager pages;
        final List<ConnectedGameTestPlayer> players=new java.util.ArrayList<>();
        final Map<UUID,net.minecraft.nbt.NbtElement> inventories=new java.util.HashMap<>();
        final BlockPos lever;
        final ConnectedGameTestPlayer operator;
        NativeEntry(TestContext context,int count) {
            this(context,count,Direction.NORTH);
        }
        NativeEntry(TestContext context,int count,Direction facing) {
            this.context=context; server=context.getWorld().getServer(); target=server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
            pages=CorridorPageManager.forServer(server);
            frame=ChamberGameTestBuilder.buildForSession(context,false,facing,false);
            var anchor=switch(facing) { case NORTH -> ChamberGameTestBuilder.CONTROLLER; case SOUTH -> new BlockPos(7,7,10);
                case EAST -> new BlockPos(10,7,7); case WEST -> new BlockPos(4,7,7); default -> throw new IllegalArgumentException(); };
            lever=anchor.offset(facing);
            operator=new ConnectedGameTestPlayer(context.getWorld());
            operator.player().refreshPositionAndAngles(CorridorGeometry.position(frame,3.5,6,-1.5),0,0);
            operator.player().setNoGravity(true);
            context.setBlockState(lever,net.minecraft.block.Blocks.LEVER.getDefaultState()
                    .with(net.minecraft.block.LeverBlock.FACE,net.minecraft.block.enums.BlockFace.WALL)
                    .with(net.minecraft.block.LeverBlock.FACING,facing));
            for(int i=0;i<count;i++) {
                var connection=new ConnectedGameTestPlayer(context.getWorld()); players.add(connection);
                var player=connection.player(); player.setNoGravity(true);
                player.refreshPositionAndAngles(CorridorGeometry.position(frame,1.5+i%5,1,1.5+i/5%5),17+i,9+i);
                player.setVelocity(Vec3d.ZERO);
                player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000));
                player.getInventory().setStack(0,new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH,7+i));
                inventories.put(player.getUuid(),player.writeNbt(new NbtCompound()).get("Inventory").copy());
            }
        }
        void power() { context.useBlock(lever,operator.player()); }
        dev.quantumchamber.chamber.ChamberControllerBlockEntity controller() {
            return (dev.quantumchamber.chamber.ChamberControllerBlockEntity)context.getWorld().getBlockEntity(frame.controllerPos());
        }
        SessionRecoveryRecord record() {
            var chamber=controller().chamberUuid();
            return SessionRecoveryState.get(server).flushedRecords().values().stream()
                    .filter(record -> record.chamberUuid().equals(chamber)).findFirst().orElse(null);
        }
        boolean returning() { return record()!=null && record().state()==SessionState.RETURNING; }
        boolean activeReady() {
            var record=record();
            if(record==null || record.state()!=SessionState.SUPERPOSITION || pages.currentMappings(record.sessionUuid()).epoch()!=1) return false;
            var entrance=pages.entrance(record.sessionUuid());
            return target.getBlockState(CorridorGeometry.block(entrance,3,3,6)).isAir();
        }
        void assertNoSession() {
            context.assertTrue(record()==null,"無效cohort不能reserve／寫journal／入場");
            context.assertTrue(players.stream().allMatch(connection -> connection.player().getServerWorld()==context.getWorld()),"無效case全員保持來源");
            context.assertEquals(4L,java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(),false).count(),"無效case不創造新world");
        }
        void assertInventory() {
            for(var connection : players) context.assertEquals(inventories.get(connection.player().getUuid()),
                    connection.player().writeNbt(new NbtCompound()).get("Inventory"),"native完整inventory保持");
        }
        void assertProtected() {
            context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos().down(3),net.minecraft.block.Blocks.DIRT.getDefaultState()),
                    "pending失敗不可解除來源艙保護");
            context.assertTrue(!dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(context.getWorld(),
                    controller()),"Task4尚未完成不得假returnComplete");
        }
        /** 真實編排僅為回收本測例資源；不是Task4 gateway返還或crash／多JVM恢復。 */
        void trustedTeardown(int tick,Runnable complete) {
            var initial=record();
            context.assertTrue(initial!=null && !dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(context.getWorld(),controller()),
                    "先真gateway請求RETURNING，產品仍不得假returnComplete");
            var returning=record();
            context.assertEquals(SessionState.RETURNING,returning.state(),"trusted teardown要先checked RETURNING");
            var participants=new java.util.ArrayList<SessionRecoveryRecord.Participant>();
            for(var person : returning.participants()) {
                var player=server.getPlayerManager().getPlayer(person.playerUuid());
                var currentEffect=player.getStatusEffect(ModEffects.QUANTUM_STATE);
                var before=currentEffect==null ? null : currentEffect.writeNbt();
                context.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(player,context.getWorld(),person.sourcePosition(),
                        person.sourceVelocity(),person.yaw(),person.pitch()),"trusted逐真人精確移回保存source pose");
                context.assertTrue(dev.quantumchamber.chamber.ChamberOccupantService.contains(
                        dev.quantumchamber.chamber.ChamberGeometry.interiorBox(frame),player.getBoundingBox()),"trusted原艙完整bbox");
                if(returning.restoreEntryEffectOnReturn()) {
                    player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                    player.addStatusEffect(StatusEffectInstance.fromNbt(person.quantumStateSnapshot()));
                    context.assertEquals(person.quantumStateSnapshot(),player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"trusted true完整snapshot");
                } else {
                    var after=player.getStatusEffect(ModEffects.QUANTUM_STATE);
                    context.assertTrue(java.util.Objects.equals(before,after==null ? null : after.writeNbt()),"trusted false不刪當下新效果");
                }
                saveCheckpoint(server,player);
                participants.add(new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),person.yaw(),
                        person.pitch(),person.quantumStateSnapshot(),true));
            }
            var journal=SessionRecoveryState.get(server);
            journal.put(new SessionRecoveryRecord(returning.sessionUuid(),returning.chamberUuid(),returning.origin(),participants,returning.spaceLeases(),
                    SessionState.RETURNING,returning.restoreEntryEffectOnReturn())); journal.flush(server);
            pages.release(returning.sessionUuid());
            when(context,tick,() -> pages.releaseComplete(returning.sessionUuid()),ignored -> {
                try {
                    context.assertTrue(!journal.flushedRecords().containsKey(returning.sessionUuid()),"真release收據需durable journal移除");
                    pages.acknowledgeRelease(returning.sessionUuid());
                    context.assertTrue(!dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(context.getWorld(),controller()),
                            "只回收測例lease，runtime不清／產品return仍false");
                    context.assertTrue(!context.getWorld().setBlockState(frame.controllerPos().down(3),net.minecraft.block.Blocks.DIRT.getDefaultState()),
                            "trusted teardown不解除原艙保護");
                    complete.run();
                } finally { close(); }
            });
        }
        @Override public void close() { players.forEach(ConnectedGameTestPlayer::close); operator.close(); }
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_return_cohort", tickLimit = 100000)
    public void trusted_offline_cohort_cannot_be_dropped_before_release_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生離線 cohort 收尾保護");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,2); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var server=context.getWorld().getServer();
            var first=fixture.initial.participants().get(0); var absent=fixture.initial.participants().get(1);
            var player=fixture.players.get(0).player(); var profile=fixture.players.get(1).player().getGameProfile();
            move(context.getWorld(),player,new CorridorPageManager.PhysicalPose(first.sourcePosition(),first.sourceVelocity(),first.yaw(),first.pitch()));
            saveCheckpoint(server,player); fixture.players.get(0).close(); fixture.players.get(1).close();
            context.assertTrue(server.getPlayerManager().getPlayer(absent.playerUuid())==null && fixture.target.getEntity(absent.playerUuid())==null,
                    "第二位真的離線且不再形成 live pin，不能只模擬 returned flag");
            var returnedFirst=new SessionRecoveryRecord.Participant(first.playerUuid(),first.sourcePosition(),first.sourceVelocity(),first.yaw(),first.pitch(),
                    first.quantumStateSnapshot(),true);
            var full=new SessionRecoveryRecord(fixture.session,fixture.initial.chamberUuid(),fixture.initial.origin(),List.of(returnedFirst,absent),
                    fixture.initial.spaceLeases(),SessionState.RETURNING,false);
            fixture.state.put(full); fixture.state.flush(server); fixture.manager.release(fixture.session);
            var before=journalBytes(server); var writes=DoorWriteFault.observeWrites(fixture.target);
            boolean rejected=false;
            try {
                fixture.state.put(new SessionRecoveryRecord(full.sessionUuid(),full.chamberUuid(),full.origin(),List.of(returnedFirst),
                        full.spaceLeases(),full.state(),full.restoreEntryEffectOnReturn()));
            } catch(IllegalArgumentException expected) { rejected=true; }
            fixture.state.flush(server);
            final boolean refused=rejected;
            context.runAtTick(tick+2,() -> {
                try {
                    context.assertEquals(0,writes.total(),"離線未返還者遭漏列時不得有任何真清理寫入");
                    context.assertTrue(refused,"同 SID 不可用縮減 cohort 取代全員返還");
                    context.assertTrue(java.util.Arrays.equals(before,journalBytes(server)),"拒絕後正式 durable journal bytes 不變，重啟仍有完整 A/B");
                    context.assertEquals(full,fixture.state.flushedRecords().get(fixture.session),"完整來源 cohort 持續保存在 durable snapshot");
                    context.assertTrue(!fixture.manager.releaseComplete(fixture.session),"離線未返還不得完成");
                    var bounds=fixture.initial.spaceLeases().getFirst().bounds();
                    context.assertTrue(!fixture.target.setBlockState(new BlockPos(bounds.getMinX(),bounds.getMinY(),bounds.getMinZ()),net.minecraft.block.Blocks.DIRT.getDefaultState()),
                            "尚未返還的 lease guard 不得移除");
                    var other=new TrustedSpace(context,Direction.NORTH,1);
                    context.assertTrue(other.prepared.target().instances().getFirst().ref().slotId()!=fixture.initial.spaceLeases().getFirst().slotId(),
                            "未返還 lease 不能被另一 reservation 重用");
                    fixture.manager.retire(fixture.manager.cancelPrepared(other.prepared)); other.players.forEach(ConnectedGameTestPlayer::close);
                } finally { writes.close(); }
                // 以相同 UUID 真正重新登入，再由測試可信編排傳回來源與保存，才更新 returned。
                var data=ConnectedClientData.createDefault(profile,false);
                var rejoined=new ServerPlayerEntity(server,context.getWorld(),data.gameProfile(),data.syncedOptions());
                var connection=new ClientConnection(NetworkSide.SERVERBOUND); var channel=new EmbeddedChannel(connection);
                try {
                    server.getPlayerManager().onPlayerConnect(connection,rejoined,data);
                    context.assertEquals(absent.playerUuid(),rejoined.getUuid(),"重新登入保持離線玩家 UUID");
                    context.assertTrue(rejoined.getServerWorld()==fixture.target,"重新載入仍在原先未返還的 Superposition");
                    move(context.getWorld(),rejoined,new CorridorPageManager.PhysicalPose(absent.sourcePosition(),absent.sourceVelocity(),absent.yaw(),absent.pitch()));
                    saveCheckpoint(server,rejoined);
                    var returnedAbsent=new SessionRecoveryRecord.Participant(absent.playerUuid(),absent.sourcePosition(),absent.sourceVelocity(),absent.yaw(),absent.pitch(),
                            absent.quantumStateSnapshot(),true);
                    fixture.state.put(new SessionRecoveryRecord(full.sessionUuid(),full.chamberUuid(),full.origin(),List.of(returnedAbsent,returnedFirst),
                            full.spaceLeases(),full.state(),full.restoreEntryEffectOnReturn())); fixture.state.flush(server);
                } finally { server.getPlayerManager().remove(rejoined); channel.finishAndReleaseAll(); }
                when(context,tick+3,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
            });
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_return_snapshot", tickLimit = 100000)
    public void trusted_return_source_snapshot_changes_are_rejected_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生來源快照不變與返還重試");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit(); fixture.returnTrusted();
            var full=fixture.state.flushedRecords().get(fixture.session); var person=full.participants().getFirst();
            var before=journalBytes(context.getWorld().getServer());
            var changedNbt=person.quantumStateSnapshot(); changedNbt.putString("immutable-source-proof","changed");
            var changed=List.of(
                    new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition().add(1,0,0),person.sourceVelocity(),person.yaw(),person.pitch(),person.quantumStateSnapshot(),true),
                    new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity().add(0,1,0),person.yaw(),person.pitch(),person.quantumStateSnapshot(),true),
                    new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),person.yaw()+1,person.pitch(),person.quantumStateSnapshot(),true),
                    new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch()+1,person.quantumStateSnapshot(),true),
                    new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch(),changedNbt,true));
            int refused=0;
            for(var candidate : changed) try {
                fixture.state.put(new SessionRecoveryRecord(full.sessionUuid(),full.chamberUuid(),full.origin(),List.of(candidate),full.spaceLeases(),full.state(),false));
            } catch(IllegalArgumentException expected) { refused++; }
            context.assertEquals(5,refused,"同 UUID 的 position／velocity／yaw／pitch／完整 NBT 逐欄改動都必須拒絕");
            context.assertTrue(!fixture.state.isDirty(),"被拒絕的 put 不可修改 dirty");
            context.assertEquals(full,fixture.state.records().get(fixture.session),"被拒絕的 put 不可修改 current record");
            fixture.state.flush(context.getWorld().getServer());
            context.assertTrue(java.util.Arrays.equals(before,journalBytes(context.getWorld().getServer())),"來源快照拒絕後正式 bytes 不變");
            when(context,tick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
        });
    }

    private static byte[] journalBytes(net.minecraft.server.MinecraftServer server) {
        try { return java.nio.file.Files.readAllBytes(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat")); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void saveCheckpoint(net.minecraft.server.MinecraftServer server,ServerPlayerEntity player) {
        try { PlayerCheckpointStore.saveAndVerify(server,player,Optional.empty()); }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_entrance_edge", tickLimit = 100000)
    public void trusted_entrance_alias_edge_preserves_open_intent_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生入口切邊與 OPEN 重建正向");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var oldFrame=fixture.manager.entrance(fixture.session);
            context.assertEquals(net.minecraft.util.ActionResult.SUCCESS,useEntrance(fixture,oldFrame),"先由真 Controller 操作保存 OPEN 意圖");
            var player=fixture.players.getFirst().player();
            move(fixture.target,player,new CorridorPageManager.PhysicalPose(fixture.manager.toPhysical(fixture.session,3.5,1,672.5),Vec3d.ZERO,0,0));
            var oldRef=fixture.manager.currentMappings(fixture.session).instances().getFirst().ref();
            var edge=fixture.manager.prepareRemap(fixture.session,1,Set.of(7L));
            var view=edge.target().instances().getFirst();
            context.assertEquals(0L,view.aliasStartBlock(),"第7頁維持精確576 apron起點");
            context.assertEquals(1440L,view.aliasEndBlock(),"第7頁維持精確576 apron終點");
            when(context,tick+1,() -> fixture.manager.ready(edge),edgeTick -> {
                context.assertTrue(nativeLeaseReady(fixture.target,view.bounds()),"完整 footprint 仍需 FULL/entity loaded/ticking");
                var batch=fixture.manager.beginRemap(edge,Map.of(player.getUuid(),oldRef));
                move(fixture.target,player,batch.moves().getFirst().after());
                var retired=fixture.manager.commitRemap(batch);
                assertRejected(context,() -> fixture.manager.entrance(fixture.session),"切邊alias不可提供不完整入口 frame");
                var absentFrame=new dev.quantumchamber.chamber.ChamberFrame(view.localBlockOrigin().add(-3,6,
                        Math.toIntExact(-view.logicalAnchorBlock())),Direction.NORTH);
                for(int z=0;z<=6;z++) for(int y=0;y<=6;y++) for(int x=0;x<=6;x++) {
                    var position=CorridorGeometry.block(absentFrame,x,y,z);
                    var block=fixture.target.getBlockState(position);
                    boolean side=x==0 || x==6;
                    boolean shell=side || y==0 || y==6 || z==0;
                    var expected=side && y>0 && y<6 && z>=1 && z<=5 ? dev.quantumchamber.registry.ModBlocks.QUANTUM_BULKHEAD
                            : shell ? net.minecraft.block.Blocks.BEDROCK : net.minecraft.block.Blocks.AIR;
                    context.assertTrue(block.isOf(expected),"沒有 overlay 時343格全部由普通封端走廊填滿："+x+","+y+","+z);
                    context.assertTrue(fixture.target.getBlockEntity(position)==null,"切邊不得留下 Controller block entity");
                }
                context.assertTrue(!fixture.target.setBlockState(absentFrame.controllerPos(),net.minecraft.block.Blocks.DIRT.getDefaultState()),
                        "沒有入口的current lease仍受保護");
                var stale=SessionEntranceDoorService.tryToggle(fixture.target,oldFrame.controllerPos());
                context.assertTrue(stale.isPresent() && !stale.orElseThrow().changed(),"退休入口不得操作或回落 Origin");
                fixture.manager.retire(retired);
                move(fixture.target,player,new CorridorPageManager.PhysicalPose(fixture.manager.toPhysical(fixture.session,3.5,1,576.5),Vec3d.ZERO,0,0));
                var back=fixture.manager.prepareRemap(fixture.session,2,Set.of(6L));
                context.assertEquals(-96L,back.target().instances().getFirst().aliasStartBlock(),"第6頁仍維持精確576 apron");
                context.assertEquals(1344L,back.target().instances().getFirst().aliasEndBlock(),"第6頁不額外擴大租約");
                when(context,edgeTick+1,() -> fixture.manager.ready(back),backTick -> {
                    var returning=fixture.manager.beginRemap(back,Map.of(player.getUuid(),view.ref()));
                    move(fixture.target,player,returning.moves().getFirst().after());
                    fixture.manager.retire(fixture.manager.commitRemap(returning));
                    var restored=fixture.manager.entrance(fixture.session);
                    var controller=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)fixture.target.getBlockEntity(restored.controllerPos());
                    context.assertTrue(controller!=null,"回到完整連接區後建立真 Controller");
                    context.assertEquals(ChamberInstanceKind.PROJECTION,controller.instanceKind(),"恢復入口仍是 replica");
                    context.assertEquals(fixture.initial.chamberUuid(),controller.chamberUuid(),"恢復來源身分");
                    context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(restored,3,3,0))
                            .get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"暫無入口不丟失前門 OPEN 意圖");
                    context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(restored,3,3,6)).isAir(),"後孔 OPEN 同樣恢復");
                    for(int z : new int[]{-1,7}) context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(restored,3,3,z)).isAir(),
                            "兩側連接格必須為普通走廊內部，不是 cap");
                    fixture.returnTrusted();
                    when(context,backTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
                });
            });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_return_before_prepare", tickLimit = 100000)
    public void trusted_returning_before_prepare_retires_durable_reservation(TestContext context) {
        var fixture=new TrustedSpace(context,Direction.NORTH,1);
        var initial=fixture.initial;
        fixture.state.put(new SessionRecoveryRecord(initial.sessionUuid(),initial.chamberUuid(),initial.origin(),initial.participants(),
                initial.spaceLeases(),SessionState.RETURNING,true)); fixture.state.flush(context.getWorld().getServer());
        assertRejected(context,() -> fixture.manager.prepare(fixture.prepared),"RETURNING不可入列geometry");
        var bounds=fixture.prepared.target().instances().getFirst().bounds();
        when(context,1,() -> !nativeLeaseReady(fixture.target,bounds),tick -> {
            fixture.finishUnentered();
            when(context,tick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_clear_retry", tickLimit = 100000)
    public void trusted_retirement_retries_failed_cell_before_release(TestContext context) {
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            var bounds=fixture.prepared.target().instances().getFirst().bounds();
            var first=new BlockPos(bounds.getMinX(),bounds.getMinY(),bounds.getMinZ());
            context.assertTrue(fixture.target.getBlockState(first).isOf(net.minecraft.block.Blocks.BEDROCK),"選定真實已建造方塊");
            var fault=DoorWriteFault.failFirstClear(fixture.target,first);
            fixture.finishUnentered();
            when(context,tick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {
                try {
                    context.assertTrue(fixture.target.getBlockState(first).isAir(),"不能跳過失敗格就宣告清理完成");
                    context.assertTrue(fault.attempts()>=2,"失敗格需真正重試原生write");
                    fixture.assertComplete(); context.complete();
                } finally { fault.close(); }
            });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_pin_capacity", tickLimit = 100000)
    public void trusted_pin_cap_includes_players_and_items_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生managed entity pin cap正向");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var items=new java.util.ArrayList<net.minecraft.entity.ItemEntity>();
            for(int i=0;i<255;i++) {
                var item=itemAt(fixture.target,fixture.manager.toPhysical(fixture.session,3.5,1,10.5+2*i),1);
                context.assertTrue(fixture.target.spawnEntity(item),"建立精確256個managed pins"); items.add(item);
            }
            var permitted=fixture.manager.prepareRemap(fixture.session,1,Set.of(0L));
            context.assertTrue(permitted!=null,"255 items加1玩家的256 pins仍可準備");
            fixture.manager.retire(fixture.manager.cancelPrepared(permitted));
            var excess=itemAt(fixture.target,fixture.manager.toPhysical(fixture.session,3.5,1,520.5),1);
            context.assertTrue(fixture.target.spawnEntity(excess),"第257個managed pin"); items.add(excess);
            context.runAtTick(tick+2,() -> {
                try {
                    context.assertEquals(SessionState.RETURNING,fixture.state.flushedRecords().get(fixture.session).state(),
                            "即使occupied頁不變，下一tick也必須偵測超出256並安全返還");
                    context.assertEquals(1L,fixture.manager.currentMappings(fixture.session).epoch(),"超量不發布新映射");
                } finally {
                    for(var item : items) {
                        var moved=item.teleportTo(new net.minecraft.world.TeleportTarget(context.getWorld(),fixture.initial.participants().getFirst().sourcePosition().add(10,0,0),
                                Vec3d.ZERO,0,0,net.minecraft.world.TeleportTarget.NO_OP));
                        context.assertTrue(moved instanceof net.minecraft.entity.ItemEntity && ((net.minecraft.entity.ItemEntity)moved).getStack().getCount()==1,
                                "超量仍逐個保留有價物品，不得刪除");
                    }
                    fixture.returnTrusted();
                }
                when(context,tick+3,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
            });
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_far_mapping", tickLimit = 100000)
    public void trusted_far_components_retain_unaffected_instance_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生遠分量mapping正向");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,2); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var original=fixture.manager.currentMappings(fixture.session).instances().getFirst();
            var far=fixture.manager.prepareRemap(fixture.session,1,Set.of(0L,1_000_000L));
            context.assertEquals(2,far.target().instances().size(),"遠頁不生成中間gap");
            when(context,tick+1,() -> fixture.manager.ready(far),farTick -> {
                fixture.manager.retire(fixture.manager.commitRemap(fixture.manager.beginRemap(far,Map.of())));
                var current=fixture.manager.currentMappings(fixture.session);
                context.assertEquals(original.ref(),current.instances().getFirst().ref(),"未受影響分量保留epoch/lease");
                var distant=current.instances().getLast();
                var nearPlayer=fixture.players.get(0).player(); var farPlayer=fixture.players.get(1).player();
                move(fixture.target,nearPlayer,new CorridorPageManager.PhysicalPose(fixture.manager.toPhysical(fixture.session,3.5,1,96.5),Vec3d.ZERO,0,0));
                move(fixture.target,farPlayer,new CorridorPageManager.PhysicalPose(fixture.manager.toPhysical(fixture.session,3.5,1,96_000_003.5),Vec3d.ZERO,0,0));
                var farBefore=farPlayer.getPos();
                context.assertTrue(nearPlayer.getPos().distanceTo(farBefore)<2048,"物理配置不使用9600萬格歷史距離");
                var next=fixture.manager.prepareRemap(fixture.session,2,Set.of(1L,1_000_000L));
                when(context,farTick+1,() -> fixture.manager.ready(next),nextTick -> {
                    var batch=fixture.manager.beginRemap(next,Map.of(nearPlayer.getUuid(),original.ref()));
                    context.assertEquals(1,batch.moves().size(),"只搬移受影響分量，遠玩家保持原owner");
                    move(fixture.target,nearPlayer,batch.moves().getFirst().after());
                    fixture.manager.retire(fixture.manager.commitRemap(batch));
                    context.assertEquals(distant.ref(),fixture.manager.currentMappings(fixture.session).instances().getLast().ref(),"遠分量epoch/lease維持原值");
                    context.assertEquals(farBefore,farPlayer.getPos(),"遠玩家真實位置沒有被整session視窗帶走");
                    fixture.returnTrusted();
                    when(context,nextTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
                });
            });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_final_flush", tickLimit = 100000)
    public void trusted_last_lease_requires_checked_remove_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生 journal rename拒絕與最後lease重試");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        context.assertTrue(!fixture.manager.releaseComplete(UUID.randomUUID()),"未知 SID 不可冒充已完成");
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit(); fixture.returnTrusted();
            var returned=fixture.state.flushedRecords().get(fixture.session);
            fixture.state.remove(fixture.session);
            context.runAtTick(tick+2,() -> {
                context.assertTrue(!fixture.manager.releaseComplete(fixture.session),"只有 in-memory remove 不可當作返還完成");
                context.assertTrue(fixture.state.flushedRecords().containsKey(fixture.session),"未flush必須保留 durable evidence");
                fixture.state.put(returned); fixture.state.flush(context.getWorld().getServer());
                var path=context.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT)
                        .resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat").toAbsolutePath().normalize();
                var kernel=com.sun.jna.platform.win32.Kernel32.INSTANCE;
                var held=kernel.CreateFile(path.toString(),0x80000000,3,null,3,0,null);
                context.assertTrue(held!=null && !com.sun.jna.platform.win32.WinBase.INVALID_HANDLE_VALUE.equals(held),"取得own journal既有檔案的無DELETE分享HANDLE");
                when(context,tick+3,fixture.state::isDirty,failedTick -> {
                    try {
                        context.assertTrue(!fixture.manager.releaseComplete(fixture.session),"原生atomic rename拒絕不得完成退休");
                        context.assertTrue(fixture.state.flushedRecords().containsKey(fixture.session),"失敗仍保留上一份 durable record");
                        context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(fixture.target,
                                fixture.manager.entrance(fixture.session).controllerPos()),"final flush失敗不得解除slot guard");
                    } finally {
                        context.assertTrue(kernel.CloseHandle(held),"測試HANDLE必須對稱關閉");
                    }
                    when(context,failedTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {
                        context.assertTrue(!fixture.state.flushedRecords().containsKey(fixture.session),"重試成功後才移除 durable SID");
                        fixture.assertComplete(); context.complete();
                    });
                });
            });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m2_returning_before_build", tickLimit = 100000)
    public void trusted_returning_before_geometry_never_builds(TestContext context) {
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        var initial=fixture.initial;
        fixture.state.put(new SessionRecoveryRecord(initial.sessionUuid(),initial.chamberUuid(),initial.origin(),initial.participants(),
                initial.spaceLeases(),SessionState.RETURNING,true)); fixture.state.flush(context.getWorld().getServer());
        var bounds=fixture.prepared.target().instances().getFirst().bounds();
        when(context,1,() -> nativeLeaseReady(fixture.target,bounds),tick -> {
            // 單一隔離 batch 的 1440×7×7 建造最多 18 個4096寫入tick；等待20 tick觀察禁止寫入。
            context.runAtTick(tick+20,() -> {
                context.assertTrue(fixture.target.getBlockState(new BlockPos(bounds.getMinX(),bounds.getMinY(),bounds.getMinZ())).isAir(),
                        "durable RETURNING 後，即使 chunk ready 也必須零 geometry");
                context.assertTrue(!fixture.manager.ready(fixture.prepared),"RETURNING 不可回報可提交 ready");
                fixture.finishUnentered();
                when(context,tick+21,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
            });
        });
    }

    private static boolean nativeLeaseReady(net.minecraft.server.world.ServerWorld world,BlockBox bounds) {
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++) for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) {
            var chunk=new net.minecraft.util.math.ChunkPos(x,z);
            if (world.getChunkManager().getWorldChunk(x,z)==null || !world.isChunkLoaded(chunk.toLong()) || !world.shouldTick(chunk)) return false;
        }
        return true;
    }
    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100000)
    public void trusted_incomplete_cohort_after_false_never_publishes_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows durable false 後 cohort 拒絕");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.NORTH,2); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.stageEntry();
            assertRejected(context,() -> fixture.manager.commitInitial(fixture.session,Set.of(fixture.players.getFirst().player().getUuid())),
                    "caller 少列 cohort 不得發布");
            context.assertEquals(0L,fixture.manager.currentMappings(fixture.session).epoch(),"全群不符保持 epoch0");
            var record=fixture.state.flushedRecords().get(fixture.session);
            context.assertEquals(SessionState.RETURNING,record.state(),"false 已提交後，發布核對失敗必須轉安全返還");
            context.assertTrue(!record.restoreEntryEffectOnReturn(),"不可倒退已 durable 的效果決策");
            fixture.returnTrusted();
            when(context,tick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100000)
    public void trusted_publish_checkpoint_failure_preserves_false_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 發布前 checkpoint 拒絕；未執行原生正向保存");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.SOUTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            var cohort=fixture.stageEntry();
            var player=fixture.players.getFirst().player();
            var healthy=player.writeNbt(new NbtCompound()); var broken=healthy.copy();
            broken.put(PlayerRecoveryCheckpoint.NBT_KEY,NbtInt.of(17)); player.readNbt(broken);
            assertRejected(context,() -> fixture.manager.commitInitial(fixture.session,cohort),"壞 marker 必須阻擋發布前保存");
            context.assertEquals(0L,fixture.manager.currentMappings(fixture.session).epoch(),"拒絕不發布 initial");
            var record=fixture.state.flushedRecords().get(fixture.session);
            context.assertEquals(SessionState.RETURNING,record.state(),"發布後驗證失敗交安全返還");
            context.assertTrue(!record.restoreEntryEffectOnReturn(),"已 durable false 不可倒退 true 補發");
            var frame=fixture.manager.entrance(fixture.session);
            context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(frame,3,3,6)).isOf(net.minecraft.block.Blocks.BEDROCK),
                    "失敗不能開 replica 後孔");
            context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE),"失敗不能猜測補發效果");
            // 只撤回本測試自行注入的壞 marker；正式 setter 仍禁止覆蓋未知 NBT。
            player.readNbt(healthy); fixture.returnTrusted();
            when(context,tick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
        });
    }

    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100000)
    public void trusted_retirement_preserves_live_valuable_item_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生入場後 item pin 退休正向");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.WEST,1); fixture.prepare();
        var item=new net.minecraft.entity.ItemEntity[1];
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var position=fixture.manager.toPhysical(fixture.session,3.5,1,20.5);
            item[0]=new net.minecraft.entity.ItemEntity(fixture.target,position.x,position.y,position.z,
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,24));
            item[0].setNoGravity(true); item[0].setPickupDelay(32767);
            context.assertTrue(fixture.target.spawnEntity(item[0]),"建立真有價物品 pin");
            fixture.returnTrusted();
            // 至少跨過一次 PageManager END_SERVER_TICK，驗證 pin 阻擋真清理嘗試。
            context.runAtTick(tick+2,() -> {
            context.assertTrue(!fixture.manager.releaseComplete(fixture.session),"有價物品仍在時不能退休");
            context.assertTrue(!item[0].isRemoved() && item[0].getStack().getCount()==24,"不能刪除 pin 或有價內容");
            var original=fixture.initial.participants().getFirst();
            var moved=item[0].teleportTo(new net.minecraft.world.TeleportTarget(context.getWorld(),original.sourcePosition().add(10,0,0),
                    Vec3d.ZERO,0,0,net.minecraft.world.TeleportTarget.NO_OP));
            context.assertTrue(moved instanceof net.minecraft.entity.ItemEntity && ((net.minecraft.entity.ItemEntity)moved).getStack().getCount()==24,
                    "trusted orchestration 先把原物品完整搬出，再允許清理");
                when(context,tick+3,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
            });
        });
    }
    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100000)
    public void trusted_native_initial_and_whole_cohort_remap_windows(TestContext context) {
        if (!Platform.isWindows()) {
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：Windows 原生 initial/remap 正向；此處不代表 checkpoint 通過");
            context.complete(); return;
        }
        var fixture=new TrustedSpace(context,Direction.EAST,2);
        fixture.prepare();
        var remap=new CorridorPageManager.PreparedMappings[1];
        var old=new CorridorPageManager.MappingSet[1];
        var extras=new net.minecraft.entity.Entity[2];
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            context.assertTrue(fixture.manager.ready(fixture.prepared),"typed initial 已準備");
            var pose=fixture.manager.toPhysical(fixture.prepared.target().instances().getFirst().ref(),
                    new CorridorPageManager.LogicalPose(3.5,1,3.5,Vec3d.ZERO,90,15));
            context.assertTrue(pose!=null,"prepared mapping 必須提供有限 typed physical pose");
            fixture.commit();
            context.assertEquals(1L,fixture.manager.currentMappings(fixture.session).epoch(),"全員 checkpoint 後才發布 epoch1");
            old[0]=fixture.manager.currentMappings(fixture.session);
            var frame=fixture.manager.entrance(fixture.session);
            var controller=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)fixture.target.getBlockEntity(frame.controllerPos());
            controller.setChamberUuid(UUID.randomUUID());
            context.assertEquals(net.minecraft.util.ActionResult.FAIL,useEntrance(fixture,frame),"已知入口錯UUID必須handled拒絕");
            controller.setChamberUuid(fixture.initial.chamberUuid());
            fixture.players.getFirst().player().setSneaking(true);
            context.assertEquals(net.minecraft.util.ActionResult.FAIL,useEntrance(fixture,frame),"projection不提供停用/拆除權");
            fixture.players.getFirst().player().setSneaking(false);
            context.assertEquals(net.minecraft.util.ActionResult.SUCCESS,useEntrance(fixture,frame),"普通Controller右鍵必須整面交易");
            context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(frame,3,3,0))
                    .get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"前門 OPEN 只在成功後發布");
            var sourceFrame=new dev.quantumchamber.chamber.ChamberFrame(fixture.initial.origin().controllerPos(),fixture.initial.origin().facing());
            for(var door : dev.quantumchamber.chamber.ChamberGeometry.bulkheadPositions(sourceFrame)) {
                context.assertTrue(!context.getWorld().getBlockState(door).get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"replica操作不改來源前門");
            }
            for(int i=0;i<2;i++) move(fixture.target,fixture.players.get(i).player(),new CorridorPageManager.PhysicalPose(
                    fixture.manager.toPhysical(fixture.session,3.5,1,95.5+i),Vec3d.ZERO,0,0));
            extras[0]=itemAt(fixture.target,fixture.manager.toPhysical(fixture.session,2.5,1,94.5),4);
            context.assertTrue(fixture.target.spawnEntity(extras[0]),"建立真item remap對象");
            extras[1]=net.minecraft.entity.EntityType.ARROW.create(fixture.target);
            context.assertTrue(extras[1]!=null,"建立原生 projectile");
            var arrowPos=fixture.manager.toPhysical(fixture.session,4.5,1,94.5);
            extras[1].refreshPositionAndAngles(arrowPos.x,arrowPos.y,arrowPos.z,0,0); extras[1].setNoGravity(true); extras[1].setVelocity(Vec3d.ZERO);
            context.assertTrue(fixture.target.spawnEntity(extras[1]),"建立真projectile remap對象");
            remap[0]=fixture.manager.prepareRemap(fixture.session,1,Set.of(1L));
            context.assertTrue(remap[0]!=null,"remap 必須預留新映射");
            context.assertEquals(old[0],fixture.manager.currentMappings(fixture.session),"準備時保留舊映射");
            context.assertEquals(2,fixture.state.flushedRecords().get(fixture.session).spaceLeases().size(),"geometry 前先保存新舊 union");
            when(context,tick+1,() -> fixture.manager.ready(remap[0]),remapTick -> {
            context.assertTrue(fixture.manager.ready(remap[0]),"remap 新幾何已完成");
            var source=old[0].instances().getFirst().ref();
            var a=fixture.players.get(0).player(); var b=fixture.players.get(1).player();
            assertRejected(context,() -> fixture.manager.beginRemap(remap[0],Map.of(a.getUuid(),source)),"少列 live cohort 必須拒絕");
            assertRejected(context,() -> fixture.manager.ready(new CorridorPageManager.PreparedMappings(remap[0].token(),fixture.session,
                    remap[0].baseEpoch(),remap[0].target())),"複製caller token不能取得held authority");
            var batch=fixture.manager.beginRemap(remap[0],Map.of(a.getUuid(),source,b.getUuid(),source,extras[0].getUuid(),source,extras[1].getUuid(),source));
            context.assertTrue(batch!=null && batch.moves().size()==4,"begin需擷取players/items/projectiles完整最新live群");
            assertRejected(context,() -> fixture.manager.commitRemap(new CorridorPageManager.RemapBatch(batch.token(),batch.prepared(),batch.moves())),
                    "caller不能複製batch token冒充authority");
            var first=batch.moves().getFirst();
            move(fixture.target,fixture.target.getEntity(first.entityUuid()),first.after());
            assertRejected(context,() -> fixture.manager.commitRemap(batch),"部分移動不得發布");
            assertRejected(context,() -> fixture.manager.cancelPrepared(remap[0]),"部分移動不可釋放 target owner");
            for (var operation : batch.moves()) move(fixture.target,fixture.target.getEntity(operation.entityUuid()),operation.after());
            var newPin=itemAt(fixture.target,batch.moves().getFirst().before().position(),1);
            context.assertTrue(fixture.target.spawnEntity(newPin),"begin之後新增真pin");
            assertRejected(context,() -> fixture.manager.commitRemap(batch),"begin之後的新pin不得被略過");
            newPin.teleportTo(new net.minecraft.world.TeleportTarget(context.getWorld(),fixture.initial.participants().getFirst().sourcePosition().add(10,0,0),
                    Vec3d.ZERO,0,0,net.minecraft.world.TeleportTarget.NO_OP));
            var retired=fixture.manager.commitRemap(batch);
            context.assertEquals(2L,fixture.manager.currentMappings(fixture.session).epoch(),"真全群搬移後發布 epoch2");
            context.assertEquals(1.0,a.getPos().distanceTo(b.getPos()),"95.5/96.5跨頁近玩家仍保持距離1");
            fixture.manager.retire(retired);
            var remappedFrame=fixture.manager.entrance(fixture.session);
            context.assertTrue(fixture.target.getBlockState(CorridorGeometry.block(remappedFrame,3,3,0))
                    .get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"重建保留入口前門 OPEN 狀態");
            for(int i=0;i<extras.length;i++) {
                var entity=fixture.target.getEntity(extras[i].getUuid());
                var moved=entity.teleportTo(new net.minecraft.world.TeleportTarget(context.getWorld(),fixture.initial.participants().getFirst().sourcePosition().add(11+i,0,0),
                        Vec3d.ZERO,0,0,net.minecraft.world.TeleportTarget.NO_OP));
                context.assertTrue(moved!=null && moved.getUuid().equals(extras[i].getUuid()),"回收前保留item/projectile UUID並安全移出");
                if (moved instanceof net.minecraft.entity.ItemEntity item) context.assertEquals(4,item.getStack().getCount(),"remap不刪有價物品");
            }
            fixture.returnTrusted();
                when(context,remapTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> { fixture.assertComplete(); context.complete(); });
            });
        });
    }

    private static void move(net.minecraft.server.world.ServerWorld world,net.minecraft.entity.Entity entity,CorridorPageManager.PhysicalPose pose) {
        entity.teleportTo(new net.minecraft.world.TeleportTarget(world,pose.position(),pose.velocity(),pose.yaw(),pose.pitch(),
                net.minecraft.world.TeleportTarget.NO_OP));
    }
    private static net.minecraft.util.ActionResult useEntrance(TrustedSpace fixture,dev.quantumchamber.chamber.ChamberFrame frame) {
        return fixture.target.getBlockState(frame.controllerPos()).onUse(fixture.target,fixture.players.getFirst().player(),
                new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(frame.controllerPos()),frame.outwardFacing(),frame.controllerPos(),false));
    }
    private static net.minecraft.entity.ItemEntity itemAt(net.minecraft.server.world.ServerWorld world,Vec3d position,int count) {
        var item=new net.minecraft.entity.ItemEntity(world,position.x,position.y,position.z,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,count));
        item.setNoGravity(true); item.setVelocity(Vec3d.ZERO); item.setNeverDespawn(); item.setPickupDelayInfinite(); return item;
    }
    private static void when(TestContext context,int tick,java.util.function.BooleanSupplier condition,java.util.function.IntConsumer ready) {
        whenUntil(context,tick,condition,ready,System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
    }
    private static void whenUntil(TestContext context,int tick,java.util.function.BooleanSupplier condition,java.util.function.IntConsumer ready,long deadline) {
        context.runAtTick(tick,() -> {
            if (condition.getAsBoolean()) ready.accept(tick);
            else {
                if (tick>=90000 || System.nanoTime()>=deadline) {
                    var world=context.getWorld().getServer().getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
                    var sample=new net.minecraft.util.math.ChunkPos(62,125);
                    context.assertTrue(false,"有限等待逾時：FULL="+(world.getChunkManager().getWorldChunk(62,125)!=null)
                            +" loaded="+world.isChunkLoaded(sample.toLong())+" ticking="+world.shouldTick(sample)
                            +" tickingFuture="+world.getChunkManager().isTickingFutureReady(sample.toLong())
                            +" chunkDebug="+world.getChunkManager().getChunkLoadingDebugInfo(sample)
                            +" worldTime="+world.getTime()+" serverTicks="+world.getServer().getTicks());
                }
                whenUntil(context,tick+1,condition,ready,deadline);
            }
        });
    }

    /** 只用於 geometry 測試的可信編排；紅石資格與失敗返還留 Task3/4。 */
    private static final class TrustedSpace {
        final TestContext context;
        final CorridorPageManager manager;
        final SessionRecoveryState state;
        final net.minecraft.server.world.ServerWorld target;
        final UUID session=UUID.randomUUID();
        final List<ConnectedGameTestPlayer> players=new java.util.ArrayList<>();
        final SessionRecoveryRecord initial;
        final CorridorPageManager.PreparedMappings prepared;
        TrustedSpace(TestContext context,Direction facing,int count) {
            this.context=context;
            var server=context.getWorld().getServer(); manager=CorridorPageManager.forServer(server); state=SessionRecoveryState.get(server);
            target=server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
            var frame=ChamberGameTestBuilder.build(context,false,facing,false);
            var chamber=dev.quantumchamber.chamber.ChamberRegistryState.get(server).registry()
                    .registerOrigin(World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,frame).chamberUuid();
            var people=new java.util.ArrayList<SessionRecoveryRecord.Participant>();
            for(int i=0;i<count;i++) {
                var connected=new ConnectedGameTestPlayer(context.getWorld()); players.add(connected);
                var player=connected.player(); var position=CorridorGeometry.position(frame,2.5+i,1,3.5);
                player.teleport(context.getWorld(),position.x,position.y,position.z,Set.of(),0,0);
                player.setNoGravity(true);
                player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000));
                people.add(new SessionRecoveryRecord.Participant(player.getUuid(),player.getPos(),player.getVelocity(),player.getYaw(),player.getPitch(),
                        (NbtCompound)player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),false));
            }
            prepared=manager.reserveInitial(session,chamber,facing,Set.of(0L));
            initial=new SessionRecoveryRecord(session,chamber,new ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                    frame.controllerPos(),facing,ChamberInstanceKind.ORIGIN),people,prepared.target().instances().stream()
                    .map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(),view.bounds())).toList(),SessionState.ARMING,true);
        }
        void prepare() { state.put(initial); state.flush(context.getWorld().getServer()); manager.prepare(prepared); }
        void commit() { manager.commitInitial(session,stageEntry()); }
        Set<UUID> stageEntry() {
            var cohort=new java.util.HashSet<UUID>();
            for(int i=0;i<players.size();i++) {
                var player=players.get(i).player(); cohort.add(player.getUuid());
                var pose=manager.toPhysical(prepared.target().instances().getFirst().ref(),
                        new CorridorPageManager.LogicalPose(2.5+i,1,3.5,Vec3d.ZERO,90,15));
                move(target,player,pose); player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                try { PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(),player,Optional.empty()); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
            state.put(new SessionRecoveryRecord(session,initial.chamberUuid(),initial.origin(),initial.participants(),initial.spaceLeases(),
                    SessionState.SUPERPOSITION,false)); state.flush(context.getWorld().getServer());
            return Set.copyOf(cohort);
        }
        void returnTrusted() {
            var people=new java.util.ArrayList<SessionRecoveryRecord.Participant>();
            for(int i=0;i<players.size();i++) {
                var player=players.get(i).player(); var original=initial.participants().get(i);
                move(context.getWorld(),player,new CorridorPageManager.PhysicalPose(original.sourcePosition(),original.sourceVelocity(),original.yaw(),original.pitch()));
                try { PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(),player,Optional.empty()); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
                people.add(new SessionRecoveryRecord.Participant(original.playerUuid(),original.sourcePosition(),original.sourceVelocity(),original.yaw(),
                        original.pitch(),original.quantumStateSnapshot(),true));
            }
            var current=state.flushedRecords().get(session);
            state.put(new SessionRecoveryRecord(session,initial.chamberUuid(),initial.origin(),people,current.spaceLeases(),SessionState.RETURNING,
                    current.restoreEntryEffectOnReturn())); state.flush(context.getWorld().getServer()); manager.release(session);
            players.forEach(ConnectedGameTestPlayer::close);
        }
        void finishUnentered() {
            var people=new java.util.ArrayList<SessionRecoveryRecord.Participant>();
            for(int i=0;i<players.size();i++) {
                var player=players.get(i).player(); var original=initial.participants().get(i);
                context.assertTrue(player.getServerWorld()==context.getWorld() && player.getPos().squaredDistanceTo(original.sourcePosition())<1e-9,
                        "未入場 fixture 必須真實仍在原始位置");
                people.add(new SessionRecoveryRecord.Participant(original.playerUuid(),original.sourcePosition(),original.sourceVelocity(),original.yaw(),
                        original.pitch(),original.quantumStateSnapshot(),true));
            }
            var current=state.flushedRecords().get(session);
            state.put(new SessionRecoveryRecord(session,initial.chamberUuid(),initial.origin(),people,current.spaceLeases(),SessionState.RETURNING,true));
            state.flush(context.getWorld().getServer()); manager.release(session); players.forEach(ConnectedGameTestPlayer::close);
        }
        void assertComplete() {
            context.assertTrue(manager.releaseComplete(session),"trusted returned／無 pins／完整清理後取得收據");
            manager.acknowledgeRelease(session);
        }
    }
    @GameTest(templateName = "quantumchamber:m1_empty", tickLimit = 100000)
    public void trusted_geometry_requires_durable_reservation_and_stays_unpublished(TestContext context) {
        var server = context.getWorld().getServer();
        var manager = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
        context.assertTrue(manager != null, "bootstrap 必須發布唯一的走廊 authority");
        var fixture = new ConnectedGameTestPlayer(context.getWorld());
        var player = fixture.player();
        var frame = ChamberGameTestBuilder.build(context, false, Direction.NORTH, false);
        var registration = dev.quantumchamber.chamber.ChamberRegistryState.get(server).registry()
                .registerOrigin(context.getWorld().getRegistryKey().getValue(), DimensionRole.OVERWORLD, frame);
        var chamber = registration.chamberUuid();
        player.teleport(context.getWorld(), frame.controllerPos().getX()+.5, frame.controllerPos().getY()-5,
                frame.controllerPos().getZ()+3.5, java.util.Set.of(), 0,0);
        player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1234));
        var session = UUID.randomUUID();
        var prepared = manager.reserveInitial(session, chamber, Direction.NORTH, java.util.Set.of(0L));
        context.assertTrue(prepared != null, "reserveInitial 必須只配置 typed reservation");
        var state = SessionRecoveryState.get(server);
        var destination = server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
        var writes=DoorWriteFault.observeWrites(destination);
        var controller = manager.entrance(session).controllerPos();
        context.assertTrue(destination.getBlockState(controller).isAir(), "reserve 不可寫 geometry");
        assertRejected(context, () -> manager.prepare(prepared), "沒有 journal 必須拒絕");
        var origin = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                frame.controllerPos(), Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var person = new SessionRecoveryRecord.Participant(player.getUuid(), player.getPos(), player.getVelocity(),
                player.getYaw(), player.getPitch(), (NbtCompound) player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(), false);
        var record = new SessionRecoveryRecord(session, chamber, origin, java.util.List.of(person),
                prepared.target().instances().stream().map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(), view.bounds())).toList(),
                SessionState.ARMING, true);
        state.put(record);
        assertRejected(context, () -> manager.prepare(prepared), "put 不代表 durable");
        context.assertTrue(destination.getBlockState(controller).isAir(), "拒絕必須零 geometry");
        state.flush(server);
        manager.prepare(prepared);
        context.assertEquals(0L, manager.currentMappings(session).epoch(), "準備中不可發布");
        when(context,1,() -> manager.ready(prepared),tick -> {
            context.assertTrue(manager.ready(prepared), "有限 geometry 必須完成");
            context.assertEquals(0L, manager.currentMappings(session).epoch(), "ready 仍不可發布");
            context.assertTrue(destination.getBlockEntity(controller) instanceof dev.quantumchamber.chamber.ChamberControllerBlockEntity,
                    "必須建立真 Controller block entity");
            var replica = (dev.quantumchamber.chamber.ChamberControllerBlockEntity) destination.getBlockEntity(controller);
            context.assertEquals(ChamberInstanceKind.PROJECTION, replica.instanceKind(), "replica 不是新 Origin");
            context.assertEquals(chamber, replica.chamberUuid(), "replica 必須保留來源 UUID");
            context.assertTrue(destination.getBlockState(controller.add(0,-6,96)).isOf(net.minecraft.block.Blocks.BEDROCK),"第96格跨頁仍有真地板");
            context.assertTrue(destination.getBlockState(controller.add(0,-6,-672)).isOf(net.minecraft.block.Blocks.BEDROCK),"負向576 apron末端有真地板");
            context.assertTrue(destination.getBlockState(controller.add(0,-6,767)).isOf(net.minecraft.block.Blocks.BEDROCK),"正向576 apron末端有真地板");
            context.assertTrue(destination.getBlockState(controller.add(0,-3,6)).isOf(net.minecraft.block.Blocks.BEDROCK),"ready 前後孔保持封閉");
            context.assertTrue(destination.getBlockState(controller.add(0,-3,7)).isAir(),"後孔外相鄰走廊內部為 air");
            var toggle=SessionEntranceDoorService.tryToggle(destination,controller);
            context.assertTrue(toggle.isPresent() && !toggle.orElseThrow().changed(),"ARMING 入口 handled拒絕，不回落Origin");
            context.assertTrue(!destination.setBlockState(controller.down(), net.minecraft.block.Blocks.DIRT.getDefaultState()),
                    "已知 slot 的普通 setBlock 必須被 guard 拒絕");
            assertRejected(context, () -> manager.commitInitial(session, java.util.Set.of(player.getUuid())), "ARMING 不得發布");
            var returned = new SessionRecoveryRecord.Participant(person.playerUuid(), person.sourcePosition(), person.sourceVelocity(),
                    person.yaw(), person.pitch(), person.quantumStateSnapshot(), true);
            state.put(new SessionRecoveryRecord(session,chamber,origin,java.util.List.of(returned),record.spaceLeases(),SessionState.RETURNING,true));
            state.flush(server);
            manager.release(session);
            fixture.close();
            when(context,tick+1,() -> manager.releaseComplete(session),ignored -> {
            context.assertTrue(manager.releaseComplete(session), "合法 RETURNING 全員 returned 後清理最後 lease");
            context.assertTrue(!state.flushedRecords().containsKey(session), "最後 lease 完成須移除 durable journal");
            manager.acknowledgeRelease(session);
            context.assertTrue(!manager.releaseComplete(session), "ack 不保留歷史收據");
            context.assertTrue(writes.total()>4096 && writes.peak()<=4096,"多 session 建造／清理／門與 overlay 必須共用每 tick 4096 真寫入上限");
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("走廊真寫入觀察：total={} peakPerTick={}",writes.total(),writes.peak());
            writes.close();
            context.complete();
            });
        });
    }

    private static void assertRejected(TestContext context, Runnable action, String message) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { rejected = true; }
        context.assertTrue(rejected, message);
    }
    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_journal_flush_updates_only_confirmed_snapshot(TestContext context) {
        var server = context.getWorld().getServer();
        var state = SessionRecoveryState.get(server);
        var sessionUuid = UUID.randomUUID(); var chamberUuid = UUID.randomUUID();
        var origin = new ChamberOriginAuthority(chamberUuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new BlockPos(100, 70, 100), Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var participant = new SessionRecoveryRecord.Participant(UUID.randomUUID(), new Vec3d(100.5, 65, 100.5),
                Vec3d.ZERO, 90, 0, (NbtCompound) new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1234).writeNbt(), false);
        var record = new SessionRecoveryRecord(sessionUuid, chamberUuid, origin, java.util.List.of(participant),
                java.util.List.of(new SessionRecoveryRecord.SpaceLease(63, new BlockBox(10000, 0, 0, 10095, 20, 10))), SessionState.ARMING, true);
        try {
            state.put(record);
            context.assertTrue(!state.flushedRecords().containsKey(sessionUuid), "put 不冒充 durable");
            state.flush(server);
            context.assertTrue(state.flushedRecords().containsKey(sessionUuid), "checked flush 後才可發布 durable");
            context.assertTrue(SessionRecoveryState.get(server) == state, "原生 manager 快取同一 state");
            state.remove(sessionUuid);
            context.assertTrue(state.flushedRecords().containsKey(sessionUuid), "未保存的 remove 不提早釋放舊空間");
            state.flush(server);
            context.assertTrue(!state.flushedRecords().containsKey(sessionUuid), "保存移除後才更新 durable");
        } finally {
            state.remove(sessionUuid); state.flush(server);
        }
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void runtime_recovery_mutations_require_server_thread(TestContext context) {
        var state = SessionRecoveryState.get(context.getWorld().getServer());
        boolean rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { state.remove(UUID.randomUUID()); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join();
        context.assertTrue(rejected, "即使不存在的 session，離開 server thread 也不得修改 journal");
        var player = disconnected(context);
        var access = (PlayerRecoveryCheckpointAccess) player;
        var checkpoint = new PlayerRecoveryCheckpoint(UUID.randomUUID(), PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
        access.quantumchamber$setRecoveryCheckpoint(checkpoint);
        rejected = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try { access.quantumchamber$setRecoveryCheckpoint(checkpoint); return false; }
            catch (IllegalStateException expected) { return true; }
        }).join();
        context.assertTrue(rejected, "玩家 marker setter 必須在 server thread");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void static_superposition_world_exists(TestContext context) {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD,
                Identifier.of("quantumchamber", "superposition"));
        var world = context.getWorld().getServer().getWorld(key);
        context.assertTrue(world != null, "固定Superposition世界必須真實存在");
        context.assertTrue(!world.getDimension().hasSkyLight() && world.getBottomY() == 0
                && world.getHeight() == 256, "原生 dimension codec 必須保留無天光與固定高度");
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void native_marker_roundtrip_copy_and_platform_checkpoint(TestContext context) throws Exception {
        var player = connect(context);
        try {
            player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1234));
            player.setStackInHand(net.minecraft.util.Hand.MAIN_HAND, new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND, 3));
            var beforePosition = player.getPos();
            var beforeEffect = player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
            var beforeInventory = player.writeNbt(new NbtCompound()).get("Inventory").copy();
            context.assertTrue(player instanceof PlayerRecoveryCheckpointAccess, "原生玩家必須提供窄 checkpoint API");
            var access = (PlayerRecoveryCheckpointAccess) player;
            context.assertTrue(access.quantumchamber$getRecoveryCheckpoint().isEmpty(), "舊玩家沒有 marker");
            var legacy = disconnected(context);
            legacy.readNbt(player.writeNbt(new NbtCompound()));
            context.assertTrue(((PlayerRecoveryCheckpointAccess) legacy).quantumchamber$getRecoveryCheckpoint().isEmpty(),
                    "舊版無 marker NBT 經原生 read 仍為空");
            assertCheckpointPlatformContract(context, player, Optional.empty());
            var checkpoint = new PlayerRecoveryCheckpoint(UUID.randomUUID(), PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT);
            access.quantumchamber$setRecoveryCheckpoint(checkpoint);
            var encoded = player.writeNbt(new NbtCompound());
            var restored = disconnected(context);
            restored.readNbt(encoded);
            context.assertEquals(Optional.of(checkpoint), ((PlayerRecoveryCheckpointAccess) restored)
                    .quantumchamber$getRecoveryCheckpoint(), "完整原生 write/read 保留 marker");
            var copied = connect(context);
            try {
                copied.copyFrom(player, true);
                context.assertEquals(Optional.of(checkpoint), ((PlayerRecoveryCheckpointAccess) copied)
                        .quantumchamber$getRecoveryCheckpoint(), "原生 copyFrom 保留 marker");
            } finally { context.getWorld().getServer().getPlayerManager().remove(copied); }
            assertCheckpointPlatformContract(context, player, Optional.of(checkpoint));
            context.assertTrue(player.writeNbt(new NbtCompound()).contains("DataVersion", 3), "原生 snapshot 已含 DataVersion");
            boolean rejected = false;
            try {
                PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player,
                        Optional.of(new PlayerRecoveryCheckpoint(UUID.randomUUID(), checkpoint.appliedPolicy())));
            } catch (java.io.IOException expected) { rejected = true; }
            context.assertTrue(rejected, "不符合 runtime marker 的保存必須拒絕");
            context.assertEquals(beforePosition, player.getPos(), "checkpoint 原語不移動玩家");
            context.assertEquals(beforeEffect, player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(), "checkpoint 原語不消耗或改寫效果");
            context.assertEquals(beforeInventory, player.writeNbt(new NbtCompound()).get("Inventory"), "checkpoint 原語不修改背包");
        } finally {
            context.getWorld().getServer().getPlayerManager().remove(player);
        }
        context.complete();
    }

    @GameTest(templateName = "quantumchamber:m1_empty")
    public void malformed_native_marker_is_preserved_and_reported_lazily(TestContext context) {
        var player = disconnected(context);
        context.assertTrue(player instanceof PlayerRecoveryCheckpointAccess, "原生 marker API 已安裝");
        for (var raw : new net.minecraft.nbt.NbtElement[] {NbtInt.of(17), new NbtCompound()}) {
            var encoded = player.writeNbt(new NbtCompound());
            encoded.put(PlayerRecoveryCheckpoint.NBT_KEY, raw.copy());
            player.readNbt(encoded);
            var access = (PlayerRecoveryCheckpointAccess) player;
            boolean rejected = false;
            try { access.quantumchamber$getRecoveryCheckpoint(); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "getter 必須延後明確回報損壞 marker");
            context.assertEquals(raw, player.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "auto-save 保留原始未知 NBT");
            var copy = disconnected(context);
            copy.copyFrom(player, true);
            context.assertEquals(raw, copy.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "copyFrom 也保留壞 marker");
            rejected = false;
            try { ((PlayerRecoveryCheckpointAccess) copy).quantumchamber$getRecoveryCheckpoint(); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "複製後仍不可假裝健康");
            rejected = false;
            try { access.quantumchamber$setRecoveryCheckpoint(new PlayerRecoveryCheckpoint(UUID.randomUUID(),
                    PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY)); }
            catch (IllegalStateException expected) { rejected = true; }
            context.assertTrue(rejected, "setter 不得覆蓋未知 marker");
            context.assertEquals(raw, player.writeNbt(new NbtCompound()).get(PlayerRecoveryCheckpoint.NBT_KEY), "拒絕後仍保留原始 marker");
        }
        context.complete();
    }

    private static void assertCheckpointPlatformContract(TestContext context, ServerPlayerEntity player,
            Optional<PlayerRecoveryCheckpoint> checkpoint) throws java.io.IOException {
        if (Platform.isWindows()) {
            PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player, checkpoint);
            return;
        }
        // 非 Windows 只驗證受控拒絕；這個分支不代表原生 checkpoint 保存成功。
        boolean rejected = false;
        try {
            PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(), player, checkpoint);
        } catch (java.io.IOException failure) {
            rejected = failure.getMessage().contains("非 Windows");
        }
        context.assertTrue(rejected, "非 Windows 必須回報能力拒絕，不能因其他錯誤假通過");
    }

    private static ServerPlayerEntity disconnected(TestContext context) {
        UUID uuid = UUID.randomUUID();
        var data = ConnectedClientData.createDefault(new GameProfile(uuid, "m2-" + uuid.toString().substring(0, 8)), false);
        return new ServerPlayerEntity(context.getWorld().getServer(), context.getWorld(), data.gameProfile(), data.syncedOptions());
    }

    private static ServerPlayerEntity connect(TestContext context) {
        var player = disconnected(context);
        var data = ConnectedClientData.createDefault(player.getGameProfile(), false);
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        context.getWorld().getServer().getPlayerManager().onPlayerConnect(connection, player, data);
        return player;
    }
}
