package dev.quantumchamber.gametest;

import com.mojang.authlib.GameProfile;
import com.sun.jna.Platform;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpoint;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpointAccess;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
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
    /** 捕捉入場消耗／重設正式藥效；全程使用原生飲用及玩家 inventory。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_drink",tickLimit=100000)
    public void native_drink_retains_effect_and_milk_returns_whole_cohort_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2,Direction.NORTH,false);
        var before=new java.util.HashMap<UUID,NbtCompound>();
        for(var connection : fixture.players) {
            var player=connection.player();
            drink(context,player,net.minecraft.component.type.PotionContentsComponent.createStack(net.minecraft.item.Items.POTION,
                    dev.quantumchamber.registry.ModPotions.QUANTUM_STATE));
            context.assertEquals(3600,player.getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"正式藥水3600 ticks");
            before.put(player.getUuid(),((NbtCompound)player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt()).copy());
        }
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,armingTick -> {
            context.assertEquals(SessionState.ARMING,fixture.record().state(),"正式藥水先持久ARMING才移動");
            context.assertEquals(SessionSemantics.LATERAL_BUFF_MAINTAINED,fixture.record().semantics(),"fresh正式飲用只建立新模式");
            context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"fresh ARMING就採KEEP_CURRENT");
            fixture.players.forEach(connection -> nativeEffectTicks(connection.player(),7));
        when(context,armingTick+1,() -> fixture.record()!=null && fixture.record().state()==SessionState.SUPERPOSITION,tick -> {
            try {
            var initial=fixture.record();
            for(var connection : fixture.players) {
                var player=connection.player();
                context.assertTrue(player.getServerWorld()==fixture.target,"真固定world入場");
                context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE),"入場保留QuantumState");
                context.assertEquals(effectAfter(before.get(player.getUuid()),7),player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),
                        "ARMING後7次原生playerTick：完整效果NBT不可重設或移除");
                player.playerTick();
                context.assertEquals(3592,player.getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"一次原生playerTick只減一");
            }
            var first=fixture.players.getFirst().player();
            drink(context,first,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
            context.assertTrue(!first.hasStatusEffect(ModEffects.QUANTUM_STATE),"vanilla milk移除真Buff");
            when(context,tick+1,fixture::returning,returnTick -> {
                context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"milk全組KEEP_CURRENT返還");
                context.assertEquals(initial.participants().size(),fixture.record().participants().size(),"凍結完整cohort");
                when(context,returnTick+1,() -> fixture.record()==null && dev.quantumchamber.chamber.ChamberSessions.gateway()
                        .presence(fixture.server,initial.chamberUuid())==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,idleTick -> {
                    assertHighIdle(fixture,initial.sessionUuid());
                    context.assertTrue(!first.hasStatusEffect(ModEffects.QUANTUM_STATE),"返還不可退款milk已移除的效果");
                    context.assertEquals(3592,fixture.players.get(1).player().getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"同行者效果不可退款或消耗");
                    for(var connection : fixture.players) drink(context,connection.player(),net.minecraft.component.type.PotionContentsComponent.createStack(
                            net.minecraft.item.Items.POTION,dev.quantumchamber.registry.ModPotions.QUANTUM_STATE));
                    when(context,idleTick+1,fixture::activeReady,reentryTick -> {
                        var second=fixture.record();
                        context.assertTrue(!initial.sessionUuid().equals(second.sessionUuid()),"HIGH再喝正式藥水必須新SID");
                        fixture.players.forEach(connection -> context.assertEquals(3600,connection.player().getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),
                                "正式3600再入場不改duration"));
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_DRINK_MILK_HIGH_REENTRY sid={} nextSid={} before={} after={} elapsedNativeTicks=8 serverTicks={}",
                                initial.sessionUuid(),second.sessionUuid(),before,effectAfter(before.get(first.getUuid()),8),fixture.server.getTicks());
                        fixture.trustedTeardown(reentryTick+1,() -> { assertLowAndCreativeBreak(fixture); context.complete(); });
                    });
                });
            });
            } catch(RuntimeException | Error failure) { fixture.close(); throw failure; }
        });
        });
    }
    private static void nativeEffectTicks(ServerPlayerEntity player,int count) {
        // EmbeddedChannel 不經 NetworkIo 輪詢；呼叫相同原生 playerTick，明確計數效果 ticks。
        for(int i=0;i<count;i++) player.playerTick();
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_custom_expiry_single",tickLimit=100000)
    public void native_custom_single_expiry_returns_without_refund_windows(TestContext context) { nativeExpiry(context,1,false); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_custom_hidden",tickLimit=100000)
    public void native_custom_hidden_continues_then_one_expiry_returns_two_windows(TestContext context) { nativeExpiry(context,2,true); }
    private static void nativeExpiry(TestContext context,int count,boolean hidden) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,count,Direction.EAST,false);
        fixture.players.forEach(connection -> customDrink(context,connection.player(),100,0));
        var first=fixture.players.getFirst().player();
        if(hidden) customDrink(context,first,3,2);
        var before=((NbtCompound)first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt()).copy();
        context.assertEquals(hidden,before.contains("hidden_effect"),"native custom多劑飲用形成真hidden chain");
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,armingTick -> {
            nativeEffectTicks(first,2);
            when(context,armingTick+1,fixture::activeReady,tick -> {
                try {
                    var sid=fixture.record().sessionUuid();
                    context.assertEquals(effectAfter(before,2),first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"入場完整hidden/current NBT只受2次原生tick影響");
                    nativeEffectTicks(first,1);
                    context.assertEquals(97,first.getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"hidden接續沿用原生剩餘97tick");
                    context.assertEquals(0,first.getStatusEffect(ModEffects.QUANTUM_STATE).getAmplifier(),"較弱hidden仍有效，不能當作失效");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_CUSTOM_EFFECT_NBT sid={} hidden={} before={} entry={} continued={} elapsedNativeTicks=3",
                            sid,hidden,before,effectAfter(before,2),first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt());
                    context.runAtTick(tick+1,() -> {
                        context.assertEquals(SessionState.SUPERPOSITION,fixture.record().state(),"hidden接續後整組仍active");
                        nativeEffectTicks(first,97);
                        context.assertTrue(!first.hasStatusEffect(ModEffects.QUANTUM_STATE),"只用100次原生tick自然到期");
                        when(context,tick+2,fixture::returning,returnTick -> {
                            context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"任何人到期都KEEP_CURRENT全組返還");
                            when(context,returnTick+1,() -> fixture.record()==null && dev.quantumchamber.chamber.ChamberSessions.gateway()
                                    .presence(fixture.server,fixture.controller().chamberUuid())==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,idleTick -> {
                                try {
                                    assertHighIdle(fixture,sid);
                                    context.assertTrue(!first.hasStatusEffect(ModEffects.QUANTUM_STATE),"已過期效果不得退款");
                                    if(count==2) context.assertEquals(100,fixture.players.get(1).player().getStatusEffect(ModEffects.QUANTUM_STATE).getDuration(),"未到期同行效果完整保留");
                                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_CUSTOM_EXPIRY_OK sid={} cohort={} hidden={} before={} elapsedNativeTicks=100 serverTicks={}",
                                            sid,count,hidden,before,fixture.server.getTicks());
                                    fixture.power(); assertLowAndCreativeBreak(fixture); context.complete();
                                } finally { fixture.close(); }
                            });
                        });
                    });
                } catch(RuntimeException | Error failure) { fixture.close(); throw failure; }
            });
        });
    }
    private static void customDrink(TestContext context,ServerPlayerEntity player,int duration,int amplifier) {
        var stack=new net.minecraft.item.ItemStack(net.minecraft.item.Items.POTION);
        stack.set(net.minecraft.component.DataComponentTypes.POTION_CONTENTS,new net.minecraft.component.type.PotionContentsComponent(
                Optional.empty(),Optional.empty(),List.of(new StatusEffectInstance(ModEffects.QUANTUM_STATE,duration,amplifier,true,false,false))));
        drink(context,player,stack);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_partial_milk",tickLimit=100000)
    public void native_custom_partial_entry_milk_rolls_back_pose_keeps_current_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2,Direction.WEST,false);
        fixture.players.forEach(connection -> customDrink(context,connection.player(),1000,0));
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record();
            var fault=new SessionTransferFault(context.getWorld(),initial,SessionTransferFault.Case.MILK_AFTER_FIRST_MOVE);
            when(context,tick+1,fixture::returning,returnTick -> {
                try {
                    context.assertTrue(fault.hit() && fault.successfulMoves()==1 && fault.rollbackVerified(),"真首人move後milk：全組原pose rollback、效果KEEP_CURRENT");
                    context.assertEquals(0L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"prepared失效不可publish半群");
                    context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"ARMING false不可被當作已提交而跳過pose rollback");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_PARTIAL_MILK_OK sid={} captureTick={} {}",initial.sessionUuid(),fault.rollbackTick(),fault.rollbackEvidence());
                    fault.close(); fixture.trustedTeardown(returnTick+1,context::complete);
                } catch(RuntimeException | Error failure) { fault.close(); fixture.close(); throw failure; }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_returning_redrink",tickLimit=100000)
    public void native_custom_redrink_during_returning_cannot_revoke_old_session_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,1,Direction.NORTH,false);
        var player=fixture.players.getFirst().player(); customDrink(context,player,1000,0);
        context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,tick -> {
            var initial=fixture.record();
            drink(context,player,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
            when(context,tick+1,fixture::returning,returnTick -> {
                customDrink(context,player,2000,1);
                var redrink=player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
                for(int i=0;i<3;i++) new dev.quantumchamber.chamber.ChamberPowerCoordinator().refresh(context.getWorld(),fixture.frame.controllerPos());
                context.assertEquals(initial.sessionUuid(),fixture.record().sessionUuid(),"RETURNING再喝不可換SID");
                context.assertEquals(SessionState.RETURNING,fixture.record().state(),"新Buff不能撤銷RETURNING");
                context.assertTrue(dev.quantumchamber.persistence.SessionRecoveryManager.blocks(player.getUuid(),fixture.server),"RETURNING中redrink後仍凍結玩家");
                when(context,returnTick+1,() -> fixture.record()!=null && !fixture.record().sessionUuid().equals(initial.sessionUuid()) && fixture.activeReady(),reentryTick -> {
                    try {
                        context.assertTrue(!SessionRecoveryState.get(fixture.server).records().containsKey(initial.sessionUuid())
                                && !SessionRecoveryState.get(fixture.server).flushedRecords().containsKey(initial.sessionUuid())
                                && !fixture.pages.releaseComplete(initial.sessionUuid()),"新SID只在舊journal/receipt全部清理後出現");
                        context.assertEquals(redrink,player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"安全返還及新入場不覆寫redrink效果");
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_RETURNING_REDRINK_OK oldSid={} newSid={} effect={}",initial.sessionUuid(),fixture.record().sessionUuid(),redrink);
                        fixture.trustedTeardown(reentryTick+1,context::complete);
                    } catch(RuntimeException | Error failure) { fixture.close(); throw failure; }
                });
            });
        });
    }
    private static NbtCompound effectAfter(NbtCompound original,int ticks) {
        var expected=original.copy();
        expected.putInt("duration",original.getInt("duration")-ticks);
        if(original.contains("hidden_effect")) expected.put("hidden_effect",effectAfter(original.getCompound("hidden_effect"),ticks));
        return expected;
    }
    private static void assertHighIdle(NativeEntry fixture,UUID sid) {
        var context=fixture.context;
        context.assertTrue(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos()),"全組返還不改外部HIGH");
        context.assertEquals(dev.quantumchamber.chamber.ChamberState.IDLE,fixture.controller().chamberState(),"缺Buff全組返還後IDLE");
        context.assertEquals(dev.quantumchamber.chamber.ChamberPowerState.POWERED,dev.quantumchamber.chamber.ChamberRegistryState.get(fixture.server)
                .registry().records().get(fixture.controller().chamberUuid()).powerState(),"HIGH收尾仍POWERED");
        context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos()),"HIGH保護不能解除");
        context.assertTrue(!SessionRecoveryState.get(fixture.server).records().containsKey(sid)
                && !SessionRecoveryState.get(fixture.server).flushedRecords().containsKey(sid) && !fixture.pages.releaseComplete(sid),"journal與release receipt已完整清除及ack");
        for(var connection : fixture.players) context.assertTrue(connection.player().getServerWorld()==context.getWorld(),"完整cohort皆真返還");
    }
    private static void assertLowAndCreativeBreak(NativeEntry fixture) {
        fixture.context.assertEquals(dev.quantumchamber.chamber.ChamberPowerState.OFF,dev.quantumchamber.chamber.ChamberRegistryState.get(fixture.server)
                .registry().records().get(fixture.controller().chamberUuid()).powerState(),"LOW完整返還才OFF");
        var operator=fixture.operator.player(); operator.changeGameMode(net.minecraft.world.GameMode.CREATIVE);
        fixture.context.assertTrue(operator.interactionManager.tryBreakBlock(fixture.frame.controllerPos()),"最後LOW OFF才允許原生Creative拆除");
    }
    private static void drink(TestContext context,ServerPlayerEntity player,net.minecraft.item.ItemStack stack) {
        boolean milk=stack.isOf(net.minecraft.item.Items.MILK_BUCKET);
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        player.getInventory().setStack(8,stack);
        var result=player.getInventory().getStack(8).finishUsing(player.getServerWorld(),player);
        player.getInventory().setStack(8,result);
        context.assertTrue(result.isOf(milk ? net.minecraft.item.Items.BUCKET : net.minecraft.item.Items.GLASS_BOTTLE),
                "正常inventory飲用後容器依Vanilla回傳");
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task7_lateral_north",tickLimit=100000)
    public void trusted_lateral_geometry_north_windows(TestContext context) { lateralGeometry(context,Direction.NORTH); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task7_lateral_south",tickLimit=100000)
    public void trusted_lateral_geometry_south_windows(TestContext context) { lateralGeometry(context,Direction.SOUTH); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task7_lateral_east",tickLimit=100000)
    public void trusted_lateral_geometry_east_windows(TestContext context) { lateralGeometry(context,Direction.EAST); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task7_lateral_west",tickLimit=100000)
    public void trusted_lateral_geometry_west_windows(TestContext context) { lateralGeometry(context,Direction.WEST); }
    /** 只驗可信 lateral 幾何及原生 remap，飲用流程由明確 native drink 案例驗證。 */
    private static void lateralGeometry(TestContext context,Direction facing) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new TrustedSpace(context,facing,2,SessionSemantics.LATERAL_BUFF_MAINTAINED);
        fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            var entrance=fixture.manager.entrance(fixture.session);
            context.assertEquals(net.minecraft.util.ActionResult.SUCCESS,useEntrance(fixture,entrance),"lateral 原正門25格交易");
            // publish 後兩側覆寫需走原本共用4096預算。
            context.runAtTick(tick+1,() -> {
                assertLateralEntrance(fixture);
                lateralRemap(fixture,0,tick+2);
            });
        });
    }
    private static void assertLateralEntrance(TrustedSpace fixture) {
        var context=fixture.context; var frame=fixture.manager.entrance(fixture.session);
        var view=fixture.manager.currentMappings(fixture.session).instances().stream()
                .filter(value -> value.aliasStartBlock()<=-2 && value.aliasEndBlock()>=9).findFirst().orElseThrow();
        var facing=fixture.initial.origin().facing();
        context.assertEquals(facing.rotateYClockwise(),view.outwardFacing(),"mapping 朝向是實際走廊朝向");
        context.assertEquals(facing,frame.outwardFacing(),"入口仍是來源方向");
        var expected=view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),6).up(6)
                .offset(view.outwardFacing().getOpposite(),Math.toIntExact(3-view.logicalAnchorBlock()));
        context.assertEquals(expected,frame.controllerPos(),"source Controller 位於 corridor block(6,6,3-anchor)");
        context.assertEquals(facing,fixture.target.getBlockState(expected).get(dev.quantumchamber.chamber.ChamberControllerBlock.FACING),"Controller FACING 保持原艙");
        for(int y=1;y<=5;y++) for(int n=1;n<=5;n++) {
            context.assertTrue(fixture.target.getBlockState(ChamberSpaceCoordinates.block(frame,n,y,0))
                    .get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"25格來源正門 OPEN 意圖");
            context.assertTrue(fixture.target.getBlockState(ChamberSpaceCoordinates.block(frame,n,y,6)).isOf(net.minecraft.block.Blocks.BEDROCK),"不開來源後牆");
            for(int x : new int[]{-1,0,6,7}) context.assertTrue(fixture.target.getBlockState(ChamberSpaceCoordinates.block(frame,x,y,n)).isAir(),"左右25格與外側connection都是AIR");
        }
        context.assertEquals(6,view.outwardFacing().getAxis()==Direction.Axis.X ? view.bounds().getMaxZ()-view.bounds().getMinZ()
                : view.bounds().getMaxX()-view.bounds().getMinX(),"窄軸採實際corridorFacing");
    }
    private static void lateralRemap(TrustedSpace fixture,int index,int tick) {
        long[][] occupied={{1},{-2},{0},{0,7},{0,13},{0,16},{9},{7},{6}};
        double[][] positions={{95.5,96.5},{-97.5,-98.5},{3.5,4.5},{95.5,672.5},{95.5,1248.5},{95.5,1536.5},{672.5,866.5},{672.5,673.5},{576.5,577.5}};
        if(index==occupied.length) {
            fixture.returnTrusted();
            when(fixture.context,tick,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {fixture.assertComplete(); fixture.context.complete();});
            return;
        }
        var context=fixture.context;
        for(int i=0;i<2;i++) move(fixture.target,fixture.players.get(i).player(),new CorridorPageManager.PhysicalPose(
                fixture.manager.toPhysical(fixture.session,3.5,1,positions[index][i]),Vec3d.ZERO,37,15));
        var extra=itemAt(fixture.target,fixture.manager.toPhysical(fixture.session,2.5,1,positions[index][0]),4);
        context.assertTrue(fixture.target.spawnEntity(extra),"lateral item pin");
        var arrow=net.minecraft.entity.EntityType.ARROW.create(fixture.target);
        var point=fixture.manager.toPhysical(fixture.session,4.5,1,positions[index][0]);
        arrow.refreshPositionAndAngles(point.x,point.y,point.z,-25,12); arrow.setNoGravity(true); arrow.setVelocity(Vec3d.ZERO);
        context.assertTrue(fixture.target.spawnEntity(arrow),"lateral projectile pin");
        var targetPages=java.util.Arrays.stream(occupied[index]).boxed().collect(java.util.stream.Collectors.toSet());
        var prepared=fixture.manager.prepareRemap(fixture.session,index+1,targetPages);
        context.assertEquals(index==5 ? 2 : 1,prepared.target().instances().size(),"0/16 split，其餘merge");
        // 重型 lateral remap 在 CI 曾需 12.468 秒；只延長此 fixture 階段，仍等待真實 readiness。
        whenUntil(context,tick,() -> fixture.manager.ready(prepared),readyTick -> {
            var owners=fixture.manager.affectedEntityOwners(prepared);
            var batch=fixture.manager.beginRemap(prepared,owners);
            for(var operation : batch.moves()) {
                context.assertEquals(operation.before().velocity(),operation.after().velocity(),"正交同方向映射保留速度");
                context.assertTrue(Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(operation.before().yaw()-operation.after().yaw()))<1e-4,"yaw不反射");
                context.assertEquals(operation.before().pitch(),operation.after().pitch(),"pitch保留");
                move(fixture.target,fixture.target.getEntity(operation.entityUuid()),operation.after());
                var destination=prepared.target().instances().stream().filter(view -> view.ref().equals(operation.target())).findFirst().orElseThrow();
                context.assertTrue(fullBox(destination.bounds(),fixture.target.getEntity(operation.entityUuid()).getBoundingBox()),"完整bbox仍在target lease");
            }
            fixture.manager.retire(fixture.manager.commitRemap(batch));
            if(index==7) {
                assertRejected(context,() -> fixture.manager.entrance(fixture.session),"{7}缺完整入口，不可建立半個overlay");
                var view=fixture.manager.currentMappings(fixture.session).instances().getFirst();
                context.assertEquals(0L,view.aliasStartBlock(),"{7}原cap邊界");
                for(int x=1;x<=5;x++) for(int y=1;y<=5;y++) context.assertTrue(fixture.target.getBlockState(
                        view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),x).up(y)
                        .offset(view.outwardFacing().getOpposite(),Math.toIntExact(-view.logicalAnchorBlock())))
                        .isOf(net.minecraft.block.Blocks.BEDROCK),"切邊cap完整，沒有入口AIR覆寫");
            } else if(index!=6) assertLateralEntrance(fixture);
            for(var id : List.of(extra.getUuid(),arrow.getUuid())) {
                var entity=fixture.target.getEntity(id);
                var returned=entity.teleportTo(new net.minecraft.world.TeleportTarget(context.getWorld(),fixture.initial.participants().getFirst().sourcePosition().add(11,0,0),
                        Vec3d.ZERO,0,0,net.minecraft.world.TeleportTarget.NO_OP));
                context.assertTrue(returned!=null && returned.getUuid().equals(id),"item/projectile UUID不遺失");
                if(returned instanceof net.minecraft.entity.ItemEntity item) context.assertEquals(4,item.getStack().getCount(),"有價stack不減少");
            }
            when(context,readyTick+1,() -> fixture.state.flushedRecords().get(fixture.session).spaceLeases().size()
                    ==fixture.manager.currentMappings(fixture.session).instances().size(),nextTick -> lateralRemap(fixture,index+1,nextTick+1));
        },System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30));
    }
    private static final Map<net.minecraft.server.MinecraftServer,DisconnectJournalFault> DISCONNECT_FAULTS=new java.util.IdentityHashMap<>();
    private static final Map<net.minecraft.server.MinecraftServer,RemapPowerLoss> REMAP_LOW=new java.util.IdentityHashMap<>();

    public static void afterRemapMove(ServerPlayerEntity player,net.minecraft.server.world.ServerWorld world,Vec3d position,Vec3d velocity,
            float yaw,float pitch,boolean success) {
        if(!world.getServer().isOnThread()) return;
        var fault=REMAP_LOW.get(world.getServer());
        if(fault==null || fault.hit || !success || world!=fault.fixture.target || player.getServerWorld()!=world
                || fault.fixture.server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY)!=world
                || fault.fixture.server.getWorld(fault.fixture.context.getWorld().getRegistryKey())!=fault.fixture.context.getWorld()
                || world.getEntity(player.getUuid())!=player || fault.fixture.server.getPlayerManager().getPlayer(player.getUuid())!=player
                || fault.initial.participants().stream().noneMatch(person -> person.playerUuid().equals(player.getUuid()))) return;
        var record=SessionRecoveryState.get(fault.fixture.server).flushedRecords().get(fault.initial.sessionUuid());
        if(record==null || record.state()!=SessionState.SUPERPOSITION || record.restoreEntryEffectOnReturn()) return;
        if(!record.origin().equals(fault.initial.origin()) || !SessionRecoveryRecord.sameParticipantSources(record.participants(),fault.initial.participants()))
            throw new IllegalStateException("remap LOW 失去凍結來源範圍");
        var current=fault.fixture.pages.currentMappings(record.sessionUuid());
        var currentSlots=current.instances().stream().map(view -> view.ref().slotId()).collect(java.util.stream.Collectors.toSet());
        if(current.epoch()!=1 || current.instances().stream().anyMatch(view -> fullBox(view.bounds(),player.getBoundingBox()))) return;
        if(record.spaceLeases().stream().noneMatch(lease -> !currentSlots.contains(lease.slotId()) && fullBox(lease.bounds(),player.getBoundingBox()))) return;
        if(player.getPos().squaredDistanceTo(position)>=1e-12 || player.getVelocity().squaredDistanceTo(velocity)>=1e-12
                || Math.abs(net.minecraft.util.math.MathHelper.wrapDegrees(player.getYaw()-yaw))>=1e-4 || Math.abs(player.getPitch()-pitch)>=1e-4)
            throw new IllegalStateException("remap LOW 必須在真 UUID／完整 pose 已確認之後");
        fault.hit=true; fault.tick=fault.fixture.server.getTicks(); fault.moved=player.getUuid();
        if(fault.buffLoss) {
            drink(fault.fixture.context,player,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_REMAP_MILK_HIT sid={} player={} epoch={}",record.sessionUuid(),player.getUuid(),current.epoch());
            return;
        }
        fault.fixture.power();
        fault.fixture.context.assertTrue(fault.fixture.record().state()==SessionState.RETURNING
                && !fault.fixture.record().restoreEntryEffectOnReturn(),"first actual remap move後真LOW持久RETURNING,false");
        fault.fixture.context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(
                fault.fixture.context.getWorld(),fault.fixture.frame.controllerPos()),"partial remap斷電同tick保持source保護");
        fault.fixture.context.assertEquals(1L,fault.fixture.pages.currentMappings(record.sessionUuid()).epoch(),"partial batch不得提早publish");
        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK4_REMAP_LOW_HIT sid={} player={} tick={} pose={} velocity={} yaw={} pitch={} epoch=1 leases={}",
                record.sessionUuid(),player.getUuid(),fault.tick,position,velocity,yaw,pitch,record.spaceLeases().size());
    }
    private static boolean fullBox(BlockBox box,net.minecraft.util.math.Box entity) {
        return dev.quantumchamber.chamber.ChamberOccupantService.contains(new net.minecraft.util.math.Box(box.getMinX(),box.getMinY(),box.getMinZ(),
                box.getMaxX()+1,box.getMaxY()+1,box.getMaxZ()+1),entity);
    }
    private static final class RemapPowerLoss implements AutoCloseable {
        final NativeEntry fixture; final SessionRecoveryRecord initial;
        boolean hit; int tick=-1; UUID moved; final boolean buffLoss;
        RemapPowerLoss(NativeEntry fixture) {
            this(fixture,false);
        }
        RemapPowerLoss(NativeEntry fixture,boolean buffLoss) {
            this.buffLoss=buffLoss;
            this.fixture=fixture; initial=fixture.record();
            if(initial.state()!=SessionState.SUPERPOSITION || initial.restoreEntryEffectOnReturn() || initial.spaceLeases().size()!=1
                    || REMAP_LOW.putIfAbsent(fixture.server,this)!=null) throw new IllegalStateException("只能明確安裝單初始mapping的remap LOW");
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(owner -> { if(owner==fixture.server) close(); });
        }
        @Override public void close() { REMAP_LOW.remove(fixture.server,this); }
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_native_remap_milk",tickLimit=100000)
    public void native_custom_milk_after_first_remap_move_never_publishes_half_cohort_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2,Direction.SOUTH,false);
        fixture.players.forEach(connection -> customDrink(context,connection.player(),1000,0));
        context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,tick -> {
            var initial=fixture.record(); var fault=new RemapPowerLoss(fixture,true);
            walkNative(fixture,fixture.players.get(0).player(),95.5); walkNative(fixture,fixture.players.get(1).player(),96.5);
            when(context,tick+1,() -> fault.hit && fixture.returning(),returnTick -> {
                try {
                    context.assertEquals(1L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"真首人remap移動後milk失效不可publish半群epoch2");
                    context.assertEquals(2,fixture.record().participants().size(),"不可縮減凍結cohort");
                    context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"remap失效保持KEEP_CURRENT");
                    fault.close();
                    fixture.trustedTeardown(returnTick+1,() -> {
                        context.assertTrue(!fixture.server.getPlayerManager().getPlayer(fault.moved).hasStatusEffect(ModEffects.QUANTUM_STATE),"rollback與返還皆不得退款milk");
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_REMAP_MILK_OK sid={} epoch=1 moved={}",initial.sessionUuid(),fault.moved);
                        context.complete();
                    });
                } catch(RuntimeException | Error failure) {
                    fault.close(); fixture.trustedTeardown(returnTick+1,() -> { throw failure; });
                }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_mid_remap_low",tickLimit=100000)
    public void native_power_loss_during_actual_remap_returns_cohort_and_value_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2); context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,tick -> {
            var initial=fixture.record(); var fault=new RemapPowerLoss(fixture);
            var view=fixture.pages.currentMappings(initial.sessionUuid()).instances().getFirst();
            var position=fixture.pages.toPhysical(view.ref(),new CorridorPageManager.LogicalPose(1.5,1,95.5,Vec3d.ZERO,0,0)).position();
            var item=new net.minecraft.entity.ItemEntity(fixture.target,position.x,position.y,position.z,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,5));
            item.setNoGravity(true); item.setVelocity(Vec3d.ZERO); item.setNeverDespawn(); item.setPickupDelayInfinite();
            context.assertTrue(fixture.target.spawnEntity(item),"mid-remap真有價item"); var itemId=item.getUuid();
            walkNative(fixture,fixture.players.get(0).player(),95.5); walkNative(fixture,fixture.players.get(1).player(),96.5);
            when(context,tick+1,() -> fault.hit,lowTick -> {
                fault.close();
                context.assertTrue(fault.moved!=null && fault.tick>=0,"真move RETURN窗口必須命中");
                context.assertTrue(SessionRecoveryRecord.sameParticipantSources(initial.participants(),fixture.record().participants())
                        && fixture.record().spaceLeases().size()>=2,"partial batch保留完整sources與兩邊lease");
                fixture.trustedTeardown(lowTick+1,() -> {
                    var value=context.getWorld().getEntity(itemId);
                    context.assertTrue(value instanceof net.minecraft.entity.ItemEntity returned && returned.getStack().getCount()==5
                            && returned.getStack().isOf(net.minecraft.item.Items.DIAMOND),"cohort與五顆鑽石均真返還後才清理");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK4_REMAP_LOW_OK sid={} sourceOff=true item={} amount=5",initial.sessionUuid(),itemId);
                    context.complete();
                });
            });
        });
    }

    /** 只供此明確安裝的原生案例拒絕整份 journal flush；沒有把共享檔案寫入假稱為單 SID 操作。 */
    public static void beforeDisconnectJournalFlush(SessionRecoveryState journal,net.minecraft.server.MinecraftServer server) {
        if(!server.isOnThread()) return;
        var fault=DISCONNECT_FAULTS.get(server); if(fault==null) return;
        var current=journal.records().get(fault.initial.sessionUuid()); var durable=journal.flushedRecords().get(fault.initial.sessionUuid());
        if(current==null || durable==null || current.state()!=SessionState.RETURNING || durable.state()!=SessionState.SUPERPOSITION) return;
        if(!server.isOnThread() || SessionRecoveryState.get(server)!=journal || server.getWorld(fault.source.getRegistryKey())!=fault.source
                || server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY)!=fault.target
                || !fault.initial.origin().equals(current.origin()) || !fault.initial.origin().equals(durable.origin())
                || durable.restoreEntryEffectOnReturn() || current.restoreEntryEffectOnReturn()
                || !SessionRecoveryRecord.sameParticipantSources(fault.initial.participants(),current.participants())
                || !SessionRecoveryRecord.sameParticipantSources(fault.initial.participants(),durable.participants()))
            throw new AssertionError("DisconnectJournalFault 失去原生 server/world/SID/凍結cohort範圍");
        fault.rejectedTicks.add(server.getTicks());
        throw new java.io.UncheckedIOException(new java.io.IOException("own DISCONNECT RETURNING journal flush 受控拒絕"));
    }
    private static final class DisconnectJournalFault implements AutoCloseable {
        final net.minecraft.server.MinecraftServer server;
        final net.minecraft.server.world.ServerWorld source,target;
        final SessionRecoveryRecord initial;
        final Set<Integer> rejectedTicks=new java.util.HashSet<>();
        DisconnectJournalFault(NativeEntry fixture) {
            server=fixture.server; source=fixture.context.getWorld(); target=fixture.target; initial=fixture.record();
            if(!(server instanceof net.minecraft.test.TestServer) || initial.state()!=SessionState.SUPERPOSITION
                    || initial.restoreEntryEffectOnReturn() || DISCONNECT_FAULTS.putIfAbsent(server,this)!=null)
                throw new IllegalStateException("只能在已知原生 ACTIVE 測試session明確安裝");
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(owner -> { if(owner==server) close(); });
        }
        @Override public void close() { DISCONNECT_FAULTS.remove(server,this); }
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_disconnect_io",tickLimit=100000)
    public void native_disconnect_io_failure_preserves_removal_pending_and_retry_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2); context.waitAndRun(2,fixture::power);
        // 先完成初始候選兩批，再量測拒絕 RETURNING flush 的整份檔案窗口。
        when(context,3,() -> fixture.activeReady() && fixture.pages.selectableDoors(fixture.record().sessionUuid()).size()==358,tick -> {
            var initial=fixture.record(); var fault=new DisconnectJournalFault(fixture); var before=journalBytes(fixture.server);
            var offline=fixture.players.remove(1); var profile=offline.player().getGameProfile(); var online=fixture.players.getFirst();
            RuntimeException[] finding={null};
            try {
                offline.disconnect();
                context.assertTrue(offline.disconnectTick()==fixture.server.getTicks() && fixture.server.getPlayerManager().getPlayer(profile.getId())==null,
                        "IO拒絕不能中斷真Fabric DISCONNECT後的原生player removal");
            } catch(RuntimeException failure) { finding[0]=failure; }
            context.runAtTick(tick+3,() -> {
                try {
                    context.assertTrue(fault.rejectedTicks.size()>=2,"必須在兩個不同server ticks真正重試flush");
                    var after=journalBytes(fixture.server);
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK4_DISCONNECT_IO_FORMAL sid={} beforeSha256={} afterSha256={} retryTicks={}",
                            initial.sessionUuid(),disconnectHash(before),disconnectHash(after),fault.rejectedTicks);
                    context.assertTrue(java.util.Arrays.equals(before,after),"整份formal journal hash/bytes不變，其他SID資料也未被覆寫");
                    var durable=SessionRecoveryState.get(fixture.server).flushedRecords().get(initial.sessionUuid());
                    context.assertTrue(durable.state()==SessionState.SUPERPOSITION && SessionRecoveryRecord.sameParticipantSources(initial.participants(),durable.participants()),
                            "失敗不偽稱durable RETURNING，也不縮減原cohort/source");
                    context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos())
                            && !fixture.pages.releaseComplete(initial.sessionUuid()),"失敗保持來源保護、lease與未完成狀態");
                    online.confirmTeleport(); var position=online.player().getPos();
                    online.player().networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                            position.x+0.125,position.y,position.z,online.player().getYaw(),online.player().getPitch(),false));
                    context.assertEquals(position,online.player().getPos(),"staged RETURNING同群在線者也必須停止正常移動");
                } catch(RuntimeException failure) { if(finding[0]==null) finding[0]=failure; }
                finally { fault.close(); }
                offline.close();
                fixture.players.add(new ConnectedGameTestPlayer(context.getWorld(),profile));
                net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(owner -> {
                    if(owner!=fixture.server) return;
                    if(dev.quantumchamber.persistence.SessionRecoveryManager.blocks(profile.getId(),owner))
                        throw new AssertionError("TASK4_DISCONNECT_STOPPED_FAILED 舊UUID仍pending");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK4_DISCONNECT_STOPPED_OK sid={} uuid={} queueVisible=false",initial.sessionUuid(),profile.getId());
                });
                fixture.trustedTeardown(tick+4,() -> {
                    if(finding[0]!=null) throw finding[0];
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK4_DISCONNECT_IO_OK sid={} retryTicks={} formalUnchanged=true sourceOff=true",
                            initial.sessionUuid(),fault.rejectedTicks);
                    context.complete();
                });
            });
        });
    }
    private static String disconnectHash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_group_walk",tickLimit=100000)
    public void native_cohort_walks_seam_splits_merges_and_returns_windows(TestContext context) {
        cohortWalk(context,Direction.NORTH);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_group_south",tickLimit=100000)
    public void native_cohort_walks_and_returns_south_windows(TestContext context) { cohortWalk(context,Direction.SOUTH); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_group_east",tickLimit=100000)
    public void native_cohort_walks_and_returns_east_windows(TestContext context) { cohortWalk(context,Direction.EAST); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_group_west",tickLimit=100000)
    public void native_cohort_walks_and_returns_west_windows(TestContext context) { cohortWalk(context,Direction.WEST); }
    private static void cohortWalk(TestContext context,Direction facing) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2,facing); context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,activeTick -> {
            var sid=fixture.record().sessionUuid();
            // page0與page16的aliases [-672,768)、[864,2304)分離；每一步仍在舊mapping有底板的範圍內。
            walkStages(fixture,sid,List.of(new WalkStage(95.5,96.5,1),new WalkStage(95.5,672.5,1),
                    new WalkStage(95.5,1248.5,1),new WalkStage(95.5,1536.5,2),new WalkStage(95.5,866.5,1),
                    new WalkStage(95.5,97.5,1),new WalkStage(-97.5,-98.5,1)),0,activeTick+1);
        });
    }
    private record WalkStage(double first,double second,int instances) {}
    private static void walkStages(NativeEntry fixture,UUID sid,List<WalkStage> stages,int index,int tick) {
        if(index==stages.size()) { fixture.trustedTeardown(tick,fixture.context::complete); return; }
        var stage=stages.get(index);
        walkNative(fixture,fixture.players.get(0).player(),stage.first());
        walkNative(fixture,fixture.players.get(1).player(),stage.second());
        when(fixture.context,tick,() -> fixture.pages.currentMappings(sid).epoch()==index+2
                && fixture.record().spaceLeases().size()==fixture.pages.currentMappings(sid).instances().size(),readyTick -> {
            assertLogical(fixture.context,fixture,fixture.players.get(0).player(),stage.first());
            assertLogical(fixture.context,fixture,fixture.players.get(1).player(),stage.second());
            fixture.context.assertEquals(stage.instances(),fixture.pages.currentMappings(sid).instances().size(),"alias是否重疊決定近共享／遠分離");
            walkStages(fixture,sid,stages,index+1,readyTick+1);
        });
    }
    private static void walkNative(NativeEntry fixture,ServerPlayerEntity player,double z) {
        var owners=fixture.pages.currentEntityOwners(fixture.record().sessionUuid()).orElseThrow();
        var target=fixture.pages.toPhysical(owners.get(player.getUuid()),new CorridorPageManager.LogicalPose(3.5,1,z,Vec3d.ZERO,0,0));
        var box=player.getBoundingBox().offset(target.position().subtract(player.getPos()));
        fixture.context.assertTrue(fixture.target.isSpaceEmpty(player,box),"腳本長走終點仍有完整原生collision空間");
        fixture.context.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(player,fixture.target,target.position(),
                target.velocity(),target.yaw(),target.pitch()),"真native pose推進，後續remap由產品tick完成");
    }
    private static void assertLogical(TestContext context,NativeEntry fixture,ServerPlayerEntity player,double z) {
        var owners=fixture.pages.currentEntityOwners(fixture.record().sessionUuid()).orElseThrow();
        var logical=fixture.pages.toLogical(owners.get(player.getUuid()),new CorridorPageManager.PhysicalPose(
                player.getPos(),player.getVelocity(),player.getYaw(),player.getPitch()));
        context.assertTrue(Math.abs(logical.logicalZ()-z)<1e-9,"remap保持手算logical z="+z);
        var floor=BlockPos.ofFloored(player.getPos()).down();
        context.assertTrue(!fixture.target.getBlockState(floor).getCollisionShape(fixture.target,floor).isEmpty(),"remap後仍有原生底板collision");
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_no_entrance",tickLimit=100000)
    public void native_remote_mapping_without_entrance_returns_to_frozen_source_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,1); context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,tick -> {
            var sid=fixture.record().sessionUuid(); var player=fixture.players.getFirst().player();
            walkNative(fixture,player,576.5);
            when(context,tick+1,() -> fixture.pages.currentMappings(sid).epoch()==2,second -> {
                walkNative(fixture,player,672.5);
                when(context,second+1,() -> fixture.pages.currentMappings(sid).epoch()==3,third -> {
                    var view=fixture.pages.currentMappings(sid).instances().getFirst();
                    context.assertEquals(0L,view.aliasStartBlock(),"page7遠端alias從0開始，端cap不可假造入口");
                    boolean absent=false;
                    try { fixture.pages.entrance(sid); } catch(IllegalStateException expected) { absent=true; }
                    context.assertTrue(absent,"遠端current mapping明確沒有物化入口");
                    fixture.trustedTeardown(third+1,context::complete);
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_entities",tickLimit=100000)
    public void native_items_projectiles_cross_seam_and_return_before_cleanup_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,1); context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,tick -> {
            var sid=fixture.record().sessionUuid(); var view=fixture.pages.currentMappings(sid).instances().getFirst();
            var position=fixture.pages.toPhysical(view.ref(),new CorridorPageManager.LogicalPose(1.5,1,95.5,Vec3d.ZERO,0,0)).position();
            var item=new net.minecraft.entity.ItemEntity(fixture.target,position.x,position.y,position.z,
                    new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,3));
            item.setNoGravity(true); item.setVelocity(Vec3d.ZERO); item.setNeverDespawn(); item.setPickupDelayInfinite();
            context.assertTrue(fixture.target.spawnEntity(item),"真有價item入世界");
            var projectile=net.minecraft.entity.EntityType.SNOWBALL.create(fixture.target);
            context.assertTrue(projectile!=null,"原生projectile建立");
            var shot=fixture.pages.toPhysical(view.ref(),new CorridorPageManager.LogicalPose(5,1,96.5,Vec3d.ZERO,0,0)).position();
            projectile.refreshPositionAndAngles(shot,0,0); projectile.setNoGravity(true); projectile.setVelocity(Vec3d.ZERO);
            context.assertTrue(fixture.target.spawnEntity(projectile),"真projectile入世界");
            var itemId=item.getUuid(); var shotId=projectile.getUuid();
            when(context,tick+1,() -> fixture.pages.currentMappings(sid).epoch()==2,remapped -> {
                var owners=fixture.pages.currentEntityOwners(sid).orElseThrow();
                context.assertTrue(owners.containsKey(itemId) && owners.containsKey(shotId),"remap包含item與projectile的唯一UUID owner");
                var actual=fixture.target.getEntity(itemId);
                var next=fixture.pages.toPhysical(owners.get(itemId),new CorridorPageManager.LogicalPose(1.5,1,96.5,Vec3d.ZERO,0,0));
                context.assertTrue(new dev.quantumchamber.transfer.SessionTransferService().move(actual,fixture.target,next.position(),next.velocity(),0,0),
                        "item真跨96seam重定位");
                fixture.trustedTeardown(remapped+1,() -> {
                    var returnedItem=context.getWorld().getEntity(itemId); var returnedShot=context.getWorld().getEntity(shotId);
                    context.assertTrue(returnedItem instanceof net.minecraft.entity.ItemEntity value && value.getStack().isOf(net.minecraft.item.Items.DIAMOND)
                            && value.getStack().getCount()==3,"以真target UUID找到三顆鑽石，不能使用舊removed reference");
                    context.assertTrue(returnedShot instanceof net.minecraft.entity.projectile.ProjectileEntity,"projectile也先返還原world才清lease");
                    context.assertTrue(!returnedItem.getCommandTags().contains("quantumchamber_session:"+sid)
                            && !returnedShot.getCommandTags().contains("quantumchamber_session:"+sid),"返還後移除本session的管理tag");
                    context.complete();
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_reconnect",tickLimit=100000)
    public void native_channel_disconnect_and_join_queue_block_movement_until_next_tick_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2); context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,activeTick -> {
            var initial=fixture.record(); var offline=fixture.players.remove(1); var id=offline.player().getUuid();
            var profile=offline.player().getGameProfile(); offline.disconnect();
            context.assertTrue(offline.disconnectTick()==fixture.server.getTicks() && offline.disconnectOnThread(),
                    "真channel關閉必須進入Fabric DISCONNECT事件與server thread");
            context.assertTrue(fixture.server.getPlayerManager().getPlayer(id)==null,"原生disconnect移除真正online玩家");
            fixture.power();
            when(context,activeTick+1,() -> fixture.record().participants().stream().anyMatch(SessionRecoveryRecord.Participant::returned),returnedTick -> {
                context.assertTrue(SessionRecoveryRecord.sameParticipantSources(initial.participants(),fixture.record().participants()),
                        "離線返還仍保留完整UUID cohort與逐欄來源snapshot");
                context.assertTrue(fixture.record().participants().stream().filter(person -> person.playerUuid().equals(id))
                        .noneMatch(SessionRecoveryRecord.Participant::returned),"離線者不可被當作returned");
                var rejoined=new ConnectedGameTestPlayer(context.getWorld(),profile); fixture.players.add(rejoined);
                context.assertEquals(fixture.server.getTicks(),rejoined.joinTick(),"真JOIN callback已觸發");
                context.assertTrue(rejoined.player().getServerWorld()==fixture.target,"JOIN callback只能queue，原生playerdata仍在fixed world");
                rejoined.confirmTeleport();
                var before=rejoined.player().getPos();
                rejoined.player().networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                        before.x+0.125,before.y,before.z,rejoined.player().getYaw(),rejoined.player().getPitch(),false));
                context.assertEquals(before,rejoined.player().getPos(),"JOIN queue等待下一tick期間封鎖真handler正常移動");
                when(context,returnedTick+1,() -> fixture.record()==null && dev.quantumchamber.chamber.ChamberSessions.gateway()
                        .presence(fixture.server,initial.chamberUuid())==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,finishedTick -> {
                    try {
                        context.assertTrue(fixture.server.getTicks()>rejoined.joinTick(),"不能於JOIN當tick返還");
                        context.assertTrue(rejoined.player().getServerWorld()==context.getWorld(),"下一tick由真backend回同一source");
                        context.assertTrue(dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos()),
                                "離線cohort重登返還完成後才OFF解保護");
                        rejoined.confirmTeleport();
                        var returned=rejoined.player().getPos();
                        rejoined.player().networkHandler.onPlayerMove(new net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket.Full(
                                returned.x+0.125,returned.y,returned.z,rejoined.player().getYaw(),rejoined.player().getPitch(),false));
                        context.assertTrue(Math.abs(rejoined.player().getX()-returned.x-0.125)<1e-9,"recovery完成後恢復正常網路移動");
                        context.complete();
                    } finally { offline.close(); fixture.close(); }
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task4_native_low",tickLimit=100000)
    public void native_external_low_returns_cohort_before_source_unlock_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2);
        fixture.players.get(0).player().refreshPositionAndAngles(CorridorGeometry.position(fixture.frame,4.25,2,4.25),17,9);
        fixture.players.get(1).player().refreshPositionAndAngles(CorridorGeometry.position(fixture.frame,2.25,3,2.25),18,10);
        context.waitAndRun(2,fixture::power);
        when(context,3,fixture::activeReady,activeTick -> {
            var initial=fixture.record();
            var renewed=fixture.players.getFirst().player();
            renewed.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,900,2));
            var renewedNbt=renewed.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
            fixture.power();
            context.assertTrue(!context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos()),
                    "先由真外部拉桿切至LOW");
            context.assertEquals(SessionState.RETURNING,fixture.record().state(),"真LOW立即進入持久RETURNING");
            context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(
                    context.getWorld(),fixture.frame.controllerPos()),"返還尚待處理時原艙保持保護");
            when(context,activeTick+1,() -> fixture.players.stream().allMatch(connection -> connection.player().getServerWorld()==context.getWorld()),returnedTick -> {
                var returning=fixture.record();
                context.assertTrue(returning!=null && returning.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned),
                        "真player checkpoint之後完整cohort returned才checked落盤");
                context.assertTrue(SessionRecoveryRecord.sameParticipantSources(initial.participants(),returning.participants()),
                        "返還floor placement不可回寫入場來源pose／NBT");
                context.assertTrue(!dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos()),
                        "全員returned仍須等最後lease與coordinator收尾");
            });
            context.runAtTick(activeTick+200,() -> {
                try {
                    for(var connection : fixture.players) {
                        var player=connection.player();
                        context.assertTrue(player.getServerWorld()==context.getWorld(),"斷電必須由真backend回到同一來源world");
                        context.assertTrue(dev.quantumchamber.chamber.ChamberOccupantService.contains(
                                dev.quantumchamber.chamber.ChamberGeometry.interiorBox(fixture.frame),player.getBoundingBox()),
                                "返還後全身位於有限原艙interior");
                    }
                    fixture.assertInventory();
                    context.assertEquals(CorridorGeometry.position(fixture.frame,2.5,1,1.5),fixture.players.get(0).player().getPos(),"A依來源z/x排序落在第二個floor slot");
                    context.assertEquals(CorridorGeometry.position(fixture.frame,1.5,1,1.5),fixture.players.get(1).player().getPos(),"B依來源z/x排序落在第一個floor slot");
                    context.assertEquals(renewedNbt,renewed.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"committed false完整保留新900/amp2當下效果");
                    for(var connection : fixture.players) context.assertEquals(Optional.of(new PlayerRecoveryCheckpoint(initial.sessionUuid(),
                            PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT)),((PlayerRecoveryCheckpointAccess)connection.player()).quantumchamber$getRecoveryCheckpoint(),
                            "每個真人持有同sid、false policy持久marker");
                    context.assertTrue(!SessionRecoveryState.get(fixture.server).flushedRecords().containsKey(initial.sessionUuid()),
                            "完整返還及最後lease清理之後journal才移除");
                    context.assertEquals(dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,
                            dev.quantumchamber.chamber.ChamberSessions.gateway().presence(fixture.server,initial.chamberUuid()),
                            "真收尾完成後移除runtime session");
                    context.assertTrue(dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(
                            context.getWorld(),fixture.frame.controllerPos()),"真返還完成後來源coordinator才解除保護");
                    context.complete();
                } finally { fixture.close(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_r1_boundary_publish",tickLimit=100000)
    public void native_projection_identified_before_budget_split_publish_windows(TestContext context) { projectionBoundary(context,false); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_r1_boundary_cancel",tickLimit=100000)
    public void native_projection_identified_before_budget_split_cancel_windows(TestContext context) { projectionBoundary(context,true); }
    private static void projectionBoundary(TestContext context,boolean cancel) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new TrustedSpace(context,Direction.NORTH,1); fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),initialTick -> {
            fixture.commit(); var before=fixture.manager.currentMappings(fixture.session).instances().getFirst();
            // 真1536格rear-open布局36939次changed writes；C在36792，4096週期內4024，尚餘147次，天然跨tick。
            var prepared=fixture.manager.prepareRemap(fixture.session,1,Set.of(0L,1L));
            var target=prepared.target().instances().getFirst();
            var position=BlockPos.ofFloored(fixture.manager.toPhysical(target.ref(),
                    new CorridorPageManager.LogicalPose(3.5,6,0.5,Vec3d.ZERO,0,0)).position());
            RuntimeException[] finding={null};
            when(context,initialTick+1,() -> fixture.target.getBlockEntity(position) instanceof dev.quantumchamber.chamber.ChamberControllerBlockEntity
                    && !fixture.manager.ready(prepared),boundaryTick -> {
                var replica=(dev.quantumchamber.chamber.ChamberControllerBlockEntity)fixture.target.getBlockEntity(position);
                try { assertProjection(context,replica,fixture.initial.chamberUuid(),"4096邊界C真建立同tick必須已標識"); }
                catch(RuntimeException expectedFinding) { finding[0]=expectedFinding; }
                context.assertEquals(1L,fixture.manager.currentMappings(fixture.session).epoch(),"邊界仍未publish新mapping");
                org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("Task3 R1 boundary：SID={} cancel={} tick={} C={} kind={} UUID={} ready=false",
                        fixture.session,cancel,fixture.target.getServer().getTicks(),position,replica.instanceKind(),replica.chamberUuid());
                if(cancel) {
                    fixture.manager.retire(fixture.manager.cancelPrepared(prepared));
                    context.runAtTick(boundaryTick+1,() -> {
                        context.assertTrue(fixture.target.getBlockEntity(position)==replica,"取消下一tick Controller尚未被清理");
                        context.assertTrue(replica.powerInitialized(),"取消下一tick已經過真load-sync，身分仍需正確");
                        try { assertProjection(context,replica,fixture.initial.chamberUuid(),"取消後load-sync下一tick也保持來源身分"); }
                        catch(RuntimeException expectedFinding) { if(finding[0]==null) finding[0]=expectedFinding; }
                        fixture.returnTrusted();
                        when(context,boundaryTick+2,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {
                            fixture.assertComplete(); if(finding[0]!=null) throw finding[0]; context.complete();
                        });
                    });
                } else {
                    when(context,boundaryTick+1,() -> fixture.manager.ready(prepared),readyTick -> {
                        assertProjection(context,replica,fixture.initial.chamberUuid(),"完整ready仍正確projection");
                        var player=fixture.players.getFirst().player();
                        var batch=fixture.manager.beginRemap(prepared,Map.of(player.getUuid(),before.ref()));
                        move(fixture.target,player,batch.moves().getFirst().after());
                        fixture.manager.retire(fixture.manager.commitRemap(batch));
                        context.assertEquals(2L,fixture.manager.currentMappings(fixture.session).epoch(),"真actual-cohort核對後publish epoch2");
                        fixture.returnTrusted();
                        when(context,readyTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {
                            fixture.assertComplete(); if(finding[0]!=null) throw finding[0]; context.complete();
                        });
                    });
                }
            });
        });
    }
    private static void assertProjection(TestContext context,dev.quantumchamber.chamber.ChamberControllerBlockEntity replica,UUID chamber,String phase) {
        context.assertTrue(replica.instanceKind()==ChamberInstanceKind.PROJECTION && chamber.equals(replica.chamberUuid()),phase);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_r1_source_replace",tickLimit=100000)
    public void native_replaced_source_controller_never_rolls_to_snapshot_windows(TestContext context) { invalidSource(context,true); }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_r1_source_uuid",tickLimit=100000)
    public void native_changed_source_uuid_never_rolls_to_snapshot_windows(TestContext context) { invalidSource(context,false); }
    private static void invalidSource(TestContext context,boolean replacement) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2); context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); var original=fixture.controller(); var player=fixture.players.getFirst().player();
            var current=CorridorGeometry.position(fixture.frame,4.5,1,4.5);
            player.refreshPositionAndAngles(current,123,23); player.setVelocity(Vec3d.ZERO);
            var effects=player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
            if(replacement) {
                context.getWorld().removeBlockEntity(fixture.frame.controllerPos());
                var changed=new dev.quantumchamber.chamber.ChamberControllerBlockEntity(fixture.frame.controllerPos(),original.getCachedState());
                changed.setChamberUuid(initial.chamberUuid()); context.getWorld().addBlockEntity(changed);
                context.assertTrue(fixture.controller()!=original && original.isRemoved(),"來源真的替換成另一instance，UUID仍相同");
            } else { original.setChamberUuid(UUID.randomUUID()); original.markDirty(); }
            when(context,tick+1,() -> SessionRecoveryState.get(fixture.server).flushedRecords().get(initial.sessionUuid()).state()==SessionState.RETURNING,failedTick -> {
                RuntimeException finding=null;
                try {
                    context.assertTrue(player.getServerWorld()==context.getWorld() && player.getPos().squaredDistanceTo(current)<1e-12
                            && player.getYaw()==123 && player.getPitch()==23,"來源權威失效不得把目前pose搬回舊snapshot");
                    context.assertEquals(effects,player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"錯來源不得消耗／覆寫目前效果");
                    var returning=SessionRecoveryState.get(fixture.server).flushedRecords().get(initial.sessionUuid());
                    context.assertTrue(!returning.restoreEntryEffectOnReturn() && initial.participants().equals(returning.participants()),"錯身分仍KEEP_CURRENT與完整原cohort");
                    context.assertEquals(0L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"來源失效不得publish");
                    context.assertTrue(!context.getWorld().setBlockState(fixture.frame.controllerPos().down(3),net.minecraft.block.Blocks.DIRT.getDefaultState()),
                            "錯來源仍保留原registry保護");
                } catch(RuntimeException expectedFinding) { finding=expectedFinding; }
                if(replacement) {
                    context.getWorld().removeBlockEntity(fixture.frame.controllerPos()); original.cancelRemoval(); context.getWorld().addBlockEntity(original);
                } else { original.setChamberUuid(initial.chamberUuid()); original.markDirty(); }
                var observed=finding;
                fixture.trustedTeardown(failedTick+1,() -> { if(observed!=null) throw observed; context.complete(); });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_r1_partial_bad_source",tickLimit=100000)
    public void native_partial_move_invalid_source_keeps_actual_world_pose_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new NativeEntry(context,2); context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); var second=fixture.players.get(1).player();
            var secondPose=CorridorGeometry.position(fixture.frame,4.5,1,4.5);
            second.refreshPositionAndAngles(secondPose,77,19); second.setVelocity(Vec3d.ZERO);
            var fault=new SessionTransferFault(context.getWorld(),initial,SessionTransferFault.Case.SECOND_MOVE_INVALID_SOURCE);
            when(context,tick+1,() -> SessionRecoveryState.get(fixture.server).flushedRecords().get(initial.sessionUuid()).state()==SessionState.RETURNING,failedTick -> {
                RuntimeException finding=null;
                try {
                    context.assertTrue(fault.sourceInvalidated() && fault.successfulMoves()==1,"必須真首人入fixed後才錯置來源，第二move前guard拒絕");
                    var first=fixture.server.getPlayerManager().getPlayer(fault.firstMovedUuid());
                    context.assertTrue(first.getServerWorld()==fixture.target && first.getPos().squaredDistanceTo(fault.firstMovedPose().position())<1e-12,
                            "部分move來源失效不得把fixed玩家錯rollback至原艙");
                    context.assertTrue(second.getServerWorld()==context.getWorld() && second.getPos().squaredDistanceTo(secondPose)<1e-12
                            && second.getYaw()==77 && second.getPitch()==19,"未搬者也不能搬向錯來源舊snapshot");
                    var returning=SessionRecoveryState.get(fixture.server).flushedRecords().get(initial.sessionUuid());
                    context.assertTrue(!returning.restoreEntryEffectOnReturn() && initial.participants().equals(returning.participants()),"partial錯來源KEEP_CURRENT，完整journal來源不改");
                    context.assertEquals(0L,fixture.pages.currentMappings(initial.sessionUuid()).epoch(),"partial壞來源不可publish");
                    context.assertTrue(fixture.players.stream().allMatch(connection -> connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE)),"尚未commit不可consume");
                } catch(RuntimeException expectedFinding) { finding=expectedFinding; }
                fault.restoreSourceIdentity(); fault.close();
                var observed=finding;
                fixture.trustedTeardown(failedTick+1,() -> { if(observed!=null) throw observed; context.complete(); });
            });
        });
    }
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
        });
        when(context,3,() -> player.getServerWorld()==context.getWorld().getServer().getWorld(
                dev.quantumchamber.superposition.SuperpositionWorld.KEY),tick -> {
            try {
                context.assertTrue(player.getServerWorld() == context.getWorld().getServer().getWorld(
                        dev.quantumchamber.superposition.SuperpositionWorld.KEY),"完整cohort必須進入真實固定世界");
                context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE),"成功入場保留藥效");
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
                    context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"未提交pose rollback仍KEEP_CURRENT");
                    context.assertEquals(initial.participants(),fixture.record().participants(),"journal保留原完整cohort與source NBT");
                    fixture.assertInventory(); fixture.assertProtected();
                    context.assertEquals(1,fault.returnFlushes(),"已checked同筆RETURNING重複gateway請求不得再flush其他case的staged資料");
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("Task3 partial rollback：SID={} cohort={} captureTick={} {}",
                            initial.sessionUuid(),initial.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList(),
                            fault.rollbackTick(),fault.rollbackEvidence());
                    // 所有原pose／hiddenNBT已證且全員在線；關observer後依真checkpoint／returned／release收尾，不稱Task4。
                    fault.close(); fixture.trustedTeardown(ignored+1,context::complete);
                } catch(RuntimeException | Error failure) { fault.close(); fixture.close(); throw failure; }
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
                            || connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE)),"其他cohort成員保留原有效效果");
                    fixture.assertInventory(); fixture.assertProtected();
                    context.complete();
                } finally { fault.close(); fixture.close(); }
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_native_expiry",tickLimit=100000)
    public void native_buff_expiry_during_geometry_keeps_precommit_returning(TestContext context) {
        var fixture=new NativeEntry(context,1,Direction.NORTH,false);
        var player=fixture.players.getFirst().player();
        customDrink(context,player,1,0);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,startedTick -> {
        context.assertEquals(SessionState.ARMING,fixture.record().state(),"先證實geometry準備期");
        // EmbeddedChannel不由NetworkIo輪詢；明確執行原生network handler使用的playerTick讓效果自然到期。
        player.playerTick();
        context.assertTrue(!player.hasStatusEffect(ModEffects.QUANTUM_STATE),"原生playerTick自然expiry，非直接remove冒充");
        when(context,startedTick+1,() -> fixture.returning(),tick -> {
            try {
                context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"自然expiry不可退款，仍KEEP_CURRENT");
                context.assertEquals(0L,fixture.pages.currentMappings(fixture.record().sessionUuid()).epoch(),"geometry準備時不可publish");
                context.assertTrue(player.getServerWorld()==context.getWorld(),"expiry前尚未移動亦不可留下半cohort");
                fixture.assertProtected();
                org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("TASK8_NATIVE_CUSTOM_PREPARE_EXPIRY_OK sid={} elapsedNativeTicks=1 effectAbsent=true",fixture.record().sessionUuid());
                if(Platform.isWindows()) fixture.trustedTeardown(tick+1,context::complete);
                else { fixture.close(); context.complete(); }
            } catch(RuntimeException | Error failure) { fixture.close(); throw failure; }
        });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m2_task8_trusted_buff_guard",tickLimit=100000)
    public void trusted_lateral_remap_begin_and_commit_recheck_complete_buff_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        var fixture=new TrustedSpace(context,Direction.NORTH,2,SessionSemantics.LATERAL_BUFF_MAINTAINED);
        fixture.prepare();
        when(context,1,() -> fixture.manager.ready(fixture.prepared),tick -> {
            fixture.commit();
            for(int i=0;i<2;i++) move(fixture.target,fixture.players.get(i).player(),new CorridorPageManager.PhysicalPose(
                    fixture.manager.toPhysical(fixture.session,3.5,1,95.5+i),Vec3d.ZERO,0,0));
            var prepared=fixture.manager.prepareRemap(fixture.session,1,Set.of(1L));
            when(context,tick+1,() -> fixture.manager.ready(prepared),readyTick -> {
                var first=fixture.players.getFirst().player();
                drink(context,first,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
                context.assertTrue(!fixture.manager.activeCohortHasBuff(fixture.session),"getter查真Buff而非entry snapshot");
                assertRejected(context,() -> fixture.manager.beginRemap(prepared,fixture.manager.affectedEntityOwners(prepared)),"begin前缺任何一人Buff不能移動");
                customDrink(context,first,1000,0);
                var batch=fixture.manager.beginRemap(prepared,fixture.manager.affectedEntityOwners(prepared));
                for(var operation : batch.moves()) move(fixture.target,fixture.target.getEntity(operation.entityUuid()),operation.after());
                drink(context,first,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
                assertRejected(context,() -> fixture.manager.commitRemap(batch),"actual全移動後失Buff也不可publish");
                context.assertEquals(1L,fixture.manager.currentMappings(fixture.session).epoch(),"failed commit保留epoch1");
                fixture.returnTrusted();
                when(context,readyTick+1,() -> fixture.manager.releaseComplete(fixture.session),ignored -> {fixture.assertComplete(); context.complete();});
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
        fixture.players.getFirst().player().setVelocity(new Vec3d(0.125,0.25,-0.375));
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,startedTick -> {
            var initial=fixture.record(); var chamber=fixture.controller().chamberUuid();
            context.assertEquals(SessionState.ARMING,initial.state(),"先checked ARMING,false才geometry／移動");
            context.assertTrue(!initial.restoreEntryEffectOnReturn(),"準備期KEEP_CURRENT政策");
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
                    context.assertTrue(player.hasStatusEffect(ModEffects.QUANTUM_STATE),"完整群維持原有效效果");
                    context.assertTrue(dev.quantumchamber.chamber.ChamberOccupantService.contains(
                            dev.quantumchamber.chamber.ChamberGeometry.interiorBox(entrance),player.getBoundingBox()),"全員完整bbox位於replica");
                }
                fixture.assertInventory();
                context.assertEquals(new Vec3d(0.125,0.25,-0.375),fixture.players.getFirst().player().getVelocity(),
                        "三軸非零world速度在source/lateral座標轉換與真移動後精確保持");
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
                        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("Task8 native shared：facing={} SID={} cohort={} immutableSources={} actualInventory=true epoch={} lateralOpen=true",
                                facing,initial.sessionUuid(),initial.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList(),
                                initial.participants(),fixture.pages.currentMappings(initial.sessionUuid()).epoch());
                        var first=fixture.players.getFirst().player();
                        first.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,500_000,1));
                        var newEffect=first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt();
                        fixture.power();
                        context.assertEquals(SessionState.RETURNING,fixture.record().state(),"真外部拉桿低位請求安全返還");
                        context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"ACTIVE轉返還不能改回true");
                        context.assertEquals(newEffect,first.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt(),"返還請求不得刪新效果");
                        context.assertTrue(first.getServerWorld()==fixture.target,"返還請求同tick保留真pending，下一tick才安全移回");
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
                    context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"offline staging保持KEEP_CURRENT");
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
    public void native_staging_low_keeps_current_effect_and_source_protection(TestContext context) {
        var fixture=new NativeEntry(context,2);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.record()!=null,tick -> {
            var initial=fixture.record(); fixture.power();
            context.assertEquals(SessionState.RETURNING,fixture.record().state(),"真低位先請求返還");
            context.assertTrue(!fixture.record().restoreEntryEffectOnReturn(),"未提交的staging取消仍KEEP_CURRENT");
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
    static final class NativeEntry implements AutoCloseable {
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
            this(context,count,facing,true);
        }
        NativeEntry(TestContext context,int count,Direction facing,boolean trustedEffects) {
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
                if(trustedEffects) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1_000_000));
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
            return target.getBlockState(ChamberSpaceCoordinates.block(entrance,
                    record.semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED ? 0 : 3,3,
                    record.semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED ? 3 : 6)).isAir();
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
                    controller()),"完整checkpoint與lease尚未收尾前不得假returnComplete");
        }
        /** Task4 之後交由真 backend 收尾；保留既有呼叫名稱以便對照 Task3 入場案例。 */
        void trustedTeardown(int tick,Runnable complete) {
            var initial=record();
            context.assertTrue(initial!=null,"必須是已知 source session 才能要求收尾");
            if(context.getWorld().isReceivingRedstonePower(frame.controllerPos())) power();
            else dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(context.getWorld(),controller());
            var journal=SessionRecoveryState.get(server);
            when(context,tick,() -> dev.quantumchamber.chamber.ChamberSessions.gateway().presence(server,initial.chamberUuid())
                    ==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,ignored -> {
                try {
                    context.assertTrue(!journal.flushedRecords().containsKey(initial.sessionUuid()),"真release收據消費前已durable移除");
                    for(var connection : players) {
                        var player=connection.player();
                        context.assertTrue(player.getServerWorld()==context.getWorld()
                                && dev.quantumchamber.chamber.ChamberOccupantService.contains(
                                        dev.quantumchamber.chamber.ChamberGeometry.interiorBox(frame),player.getBoundingBox()),
                                "真backend全員回同source完整bbox");
                    }
                    context.assertTrue(dev.quantumchamber.chamber.ChamberProtectionService.get().mayMutate(context.getWorld(),frame.controllerPos()),
                            "LOW真返還收尾後解除來源保護");
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
            var full=fixture.initial.withProgress(List.of(returnedFirst,absent),fixture.initial.spaceLeases(),SessionState.RETURNING,false);
            fixture.state.put(full); fixture.state.flush(server); fixture.manager.release(fixture.session);
            var before=journalBytes(server); var writes=DoorWriteFault.observeWrites(fixture.target);
            boolean rejected=false;
            try {
                fixture.state.put(full.withProgress(List.of(returnedFirst),full.spaceLeases(),full.state(),full.restoreEntryEffectOnReturn()));
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
                    fixture.state.put(full.withProgress(List.of(returnedAbsent,returnedFirst),full.spaceLeases(),full.state(),full.restoreEntryEffectOnReturn()));
                    fixture.state.flush(server);
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
                fixture.state.put(full.withProgress(List.of(candidate),full.spaceLeases(),full.state(),false));
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
        fixture.state.put(initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.RETURNING,true));
        fixture.state.flush(context.getWorld().getServer());
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
        fixture.state.put(initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.RETURNING,true));
        fixture.state.flush(context.getWorld().getServer());
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
    @GameTest(templateName = "quantumchamber:m1_empty", batchId="m2_task4_isolated_incomplete", tickLimit = 100000)
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
    @GameTest(templateName = "quantumchamber:m1_empty", batchId="m2_task4_isolated_publish", tickLimit = 100000)
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

    @GameTest(templateName = "quantumchamber:m1_empty", batchId="m2_task4_isolated_value", tickLimit = 100000)
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
    @GameTest(templateName = "quantumchamber:m1_empty", batchId="m2_task4_isolated_remap", tickLimit = 100000)
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
                            +" worldTime="+world.getTime()+" serverTicks="+world.getServer().getTicks()+" own="+waitingLeaseEvidence(context));
                }
                whenUntil(context,tick+1,condition,ready,deadline);
            }
        });
    }
    private static String waitingLeaseEvidence(TestContext context) {
        var server=context.getWorld().getServer(); var journal=SessionRecoveryState.get(server);
        var world=server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
        var base=context.getAbsolutePos(BlockPos.ORIGIN); var details=new java.util.ArrayList<String>();
        for(var record : journal.flushedRecords().values()) {
            var offset=record.origin().controllerPos().subtract(base);
            if(offset.getX()<0 || offset.getX()>20 || offset.getZ()<0 || offset.getZ()>20) continue;
            var staged=journal.records().get(record.sessionUuid());
            String state="sid="+record.sessionUuid()+" flushed="+record.state()+" current="+(staged==null ? "absent" : staged.state());
            for(var lease : record.spaceLeases()) {
                var box=lease.bounds(); int total=0,full=0,loaded=0,ticking=0;
                for(int x=box.getMinX()>>4;x<=box.getMaxX()>>4;x++) for(int z=box.getMinZ()>>4;z<=box.getMaxZ()>>4;z++) {
                    total++; var chunk=new net.minecraft.util.math.ChunkPos(x,z);
                    if(world.getChunkManager().getWorldChunk(x,z)!=null) full++;
                    if(world.isChunkLoaded(chunk.toLong())) loaded++;
                    if(world.shouldTick(chunk)) ticking++;
                }
                state+=" slot="+lease.slotId()+" chunks="+total+" FULL="+full+" loaded="+loaded+" ticking="+ticking;
            }
            details.add(state);
        }
        return details.toString();
    }

    /** 只用於 geometry 測試的可信編排；紅石資格與失敗返還留 Task3/4。 */
    static final class TrustedSpace {
        final TestContext context;
        final CorridorPageManager manager;
        final SessionRecoveryState state;
        final net.minecraft.server.world.ServerWorld target;
        final UUID session=UUID.randomUUID();
        final List<ConnectedGameTestPlayer> players=new java.util.ArrayList<>();
        final SessionRecoveryRecord initial;
        final CorridorPageManager.PreparedMappings prepared;
        TrustedSpace(TestContext context,Direction facing,int count) {
            this(context,facing,count,SessionSemantics.LEGACY_FORWARD_CONSUMED);
        }
        TrustedSpace(TestContext context,Direction facing,int count,SessionSemantics semantics) {
            this(context,facing,count,semantics,true);
        }
        TrustedSpace(TestContext context,Direction facing,int count,SessionSemantics semantics,boolean candidateAware) {
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
            prepared=manager.reserveInitial(session,chamber,facing,Set.of(0L),semantics);
            var legacy=new SessionRecoveryRecord(session,chamber,new ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                    frame.controllerPos(),facing,ChamberInstanceKind.ORIGIN),people,prepared.target().instances().stream()
                    .map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(),view.bounds())).toList(),SessionState.ARMING,
                    semantics==SessionSemantics.LEGACY_FORWARD_CONSUMED,semantics);
            initial=candidateAware ? M4CandidateTestAccess.candidateAware(legacy,server) : legacy;
        }
        void prepare() { state.put(initial); state.flush(context.getWorld().getServer()); manager.prepare(prepared); }
        void commit() { manager.commitInitial(session,stageEntry()); }
        Set<UUID> stageEntry() {
            var cohort=new java.util.HashSet<UUID>();
            for(int i=0;i<players.size();i++) {
                var player=players.get(i).player(); cohort.add(player.getUuid());
                var pose=manager.toPhysical(prepared.target().instances().getFirst().ref(),
                        new CorridorPageManager.LogicalPose(2.5+i,1,3.5,Vec3d.ZERO,90,15));
                move(target,player,pose);
                if(initial.semantics()==SessionSemantics.LEGACY_FORWARD_CONSUMED) player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                try { PlayerCheckpointStore.saveAndVerify(context.getWorld().getServer(),player,Optional.empty()); }
                catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            }
            state.put(new SessionRecoveryRecord(session,initial.chamberUuid(),initial.origin(),initial.participants(),initial.spaceLeases(),
                    SessionState.SUPERPOSITION,false,initial.semantics(),initial.candidateContext(),initial.candidateLedger(),initial.candidateSelection()));
            state.flush(context.getWorld().getServer());
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
                    current.restoreEntryEffectOnReturn(),current.semantics(),current.candidateContext(),current.candidateLedger(),current.candidateSelection()));
            state.flush(context.getWorld().getServer()); manager.release(session);
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
            state.put(current.withProgress(people,current.spaceLeases(),SessionState.RETURNING,current.restoreEntryEffectOnReturn()));
            state.flush(context.getWorld().getServer()); manager.release(session); players.forEach(ConnectedGameTestPlayer::close);
        }
        void assertComplete() {
            context.assertTrue(manager.releaseComplete(session),"trusted returned／無 pins／完整清理後取得收據");
            manager.acknowledgeRelease(session);
        }
    }
    @GameTest(templateName = "quantumchamber:m1_empty", batchId="m2_task4_isolated_reservation", tickLimit = 100000)
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
        var record = M4CandidateTestAccess.candidateAware(new SessionRecoveryRecord(session, chamber, origin, java.util.List.of(person),
                prepared.target().instances().stream().map(view -> new SessionRecoveryRecord.SpaceLease(view.ref().slotId(), view.bounds())).toList(),
                SessionState.ARMING, true),server);
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
            state.put(record.withProgress(java.util.List.of(returned),record.spaceLeases(),SessionState.RETURNING,true));
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
        var record = M4CandidateTestAccess.candidateAware(new SessionRecoveryRecord(sessionUuid, chamberUuid, origin, java.util.List.of(participant),
                java.util.List.of(new SessionRecoveryRecord.SpaceLease(63, new BlockBox(10000, 0, 0, 10095, 20, 10))), SessionState.ARMING, true),server);
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
