package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** T1 僅驗證 scope／正常 pacing／既有平台與 B guard；正式 HOME 尚未開放。 */
public final class M7HomeSpikeGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_windows",tickLimit=100000)
    public void checkpoint_capability_is_confirmed_on_windows(TestContext context)throws Exception {
        context.assertTrue(com.sun.jna.Platform.isWindows(),"Windows exact平台");formalPlatform(context,true);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_ubuntu",tickLimit=100000)
    public void checkpoint_capability_remains_rejected_on_ubuntu(TestContext context) {
        context.assertTrue(!com.sun.jna.Platform.isWindows(),"Ubuntu exact平台");formalPlatform(context,false);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_portable",tickLimit=200)
    public void m7_scope_keeps_testmod_and_close_fixture_exact(TestContext context) {
        context.assertTrue(Boolean.getBoolean("quantumchamber.test.m7Home"),"M7 旗標必須啟用");
        context.assertTrue(FabricLoader.getInstance().isModLoaded("quantumchamber-testmod"),"M7 runtime 必須包含 testmod");
        context.assertTrue(M7CloseSpikePhases.fixture(true).getCompound("Home").getCompound("CloseIntent").getString("Kind").equals("CLOSE"),"CLOSE fixture");
        context.waitAndRun(25,context::complete);
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_portable",tickLimit=200)
    public void projection_activation_remains_rejected_before_home_production(TestContext context) {
        var fixture=new M2CorridorGameTests.NativeEntry(context,1);
        try{
            var controller=fixture.controller();var service=new ChamberActivationService();
            context.assertTrue(controller.getWorld()==context.getWorld()&&context.getWorld().getBlockEntity(controller.getPos())==controller,"同一個附著BE");
            context.assertTrue(service.evaluateReadiness(context.getWorld(),controller).accepted(),"ORIGIN對照先通過完整結構/cohort/效果資格");
            controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
            var result=service.evaluateReadiness(context.getWorld(),controller);
            context.assertTrue(!result.accepted()&&result.failureReasons().contains(ArmAttemptResult.Failure.INVALID_STRUCTURE),"唯一差異projection guard拒絕");
            controller.setInstanceKind(ChamberInstanceKind.ORIGIN);context.complete();
        }finally{fixture.close();}
    }
    private static void formalPlatform(TestContext context,boolean windows){
        var fixture=new M2CorridorGameTests.NativeEntry(context,1);var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(fixture.server);
        var before=journal.records();var durable=journal.flushedRecords();var runtime=M4CandidateDoorGameTests.runtimeOwnership(fixture);
        var player=fixture.players.getFirst().player();var position=player.getPos();var world=player.getServerWorld();
        context.assertTrue(new ChamberActivationService().evaluateReadiness(context.getWorld(),fixture.controller()).accepted(),"正式A入口前所有來源/cohort條件已成立");
        fixture.power();
        if(!windows){context.waitAndRun(60,()->{try{fixture.assertNoSession();context.assertEquals(before,journal.records(),"拒絕current零增量");context.assertEquals(durable,journal.flushedRecords(),"拒絕durable零增量");context.assertEquals(runtime,M4CandidateDoorGameTests.runtimeOwnership(fixture),"拒絕幾何/lease/票/鎖零增量");context.assertTrue(player.getServerWorld()==world&&player.getPos().equals(position),"拒絕不移動玩家");context.complete();}finally{fixture.close();}});return;}
        awaitWindows(context,fixture,3);
    }
    private static void awaitWindows(TestContext context,M2CorridorGameTests.NativeEntry fixture,int tick){
        context.runAtTick(tick,()->{if(fixture.activeReady()){fixture.trustedTeardown(tick+1,context::complete);return;}context.assertTrue(tick<5000,"正式A入場有限等待");awaitWindows(context,fixture,tick+1);});
    }
}
