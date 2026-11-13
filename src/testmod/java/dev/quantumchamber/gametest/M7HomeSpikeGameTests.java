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
    public void m7_scope_keeps_testmod_and_pacing(TestContext context) {
        context.assertTrue(Boolean.getBoolean("quantumchamber.test.m7Home"),"M7 旗標必須啟用");
        context.assertTrue(FabricLoader.getInstance().isModLoaded("quantumchamber-testmod"),"M7 runtime 必須包含 testmod");
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
        var capture=windows?null:new LogCapture("quantumchamber");
        fixture.power();
        if(!windows){context.waitAndRun(60,()->{try{context.assertTrue(capture.matching(org.apache.logging.log4j.Level.WARN,"玩家原生 checkpoint 能力預檢未通過","checkpoint 尚未驗證非 Windows 平台，保守拒絕").size()==1,"真正式入口到達checkpoint平台拒絕，非較早前置拒絕");fixture.assertNoSession();context.assertEquals(before,journal.records(),"拒絕current零增量");context.assertEquals(durable,journal.flushedRecords(),"拒絕durable零增量");context.assertEquals(runtime,M4CandidateDoorGameTests.runtimeOwnership(fixture),"拒絕幾何/lease/票/鎖零增量");context.assertTrue(player.getServerWorld()==world&&player.getPos().equals(position),"拒絕不移動玩家");context.complete();}finally{capture.close();fixture.close();}});return;}
        awaitWindows(context,fixture,3);
    }
    private static void awaitWindows(TestContext context,M2CorridorGameTests.NativeEntry fixture,int tick){
        context.runAtTick(tick,()->{if(fixture.activeReady()){fixture.trustedTeardown(tick+1,context::complete);return;}context.assertTrue(tick<5000,"正式A入場有限等待");awaitWindows(context,fixture,tick+1);});
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_capacity_windows",tickLimit=100000)
    public void candidate_and_remap_capacity_retry_keeps_published_team(TestContext context) {
        var fixture=new M2CorridorGameTests.NativeEntry(context,1);
        context.waitAndRun(2,fixture::power);
        awaitPublished(context,fixture,3);
    }
    private static void awaitPublished(TestContext context,M2CorridorGameTests.NativeEntry f,int tick) {
        context.runAtTick(tick,()-> {
            if(!f.activeReady() || f.pages.selectableDoors(f.record().sessionUuid()).isEmpty()) {
                context.assertTrue(tick<5000,"正式 A 隊伍尚未發布既有側門");
                awaitPublished(context,f,tick+1); return;
            }
            var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(f.server);
            var old=f.record(); var key=new dev.quantumchamber.corridor.DoorKey(old.sessionUuid(),10_000L,
                    dev.quantumchamber.corridor.DoorKey.DoorWallSide.POSITIVE_LATERAL);
            var doors=f.pages.selectableDoors(old.sessionUuid()); var mapping=f.pages.currentMappings(old.sessionUuid());
            var player=f.players.getFirst().player();
            var priorRuntime=M4CandidateDoorGameTests.runtimeOwnership(f);
            M2CorridorGameTests.walkNative(f,player,97.5);
            var pose=player.getPos();
            var pressure=dev.quantumchamber.persistence.M7CapacityFixture.admit(f.server,old);
            var service=new dev.quantumchamber.candidate.CandidateLedgerService();
            String failure=null;
            try {
                var append=service.commitCandidates(f.server,old.sessionUuid(),java.util.List.of(key));
                RuntimeException refusal=null;
                try { f.pages.prepareRemap(old.sessionUuid(),mapping.epoch(),java.util.Set.of(1L)); }
                catch(RuntimeException denied) { refusal=denied; }
                // 同一正式 reposition 入口必須把容量拒絕留為重試；一般故障仍由其外層要求返還。
                new dev.quantumchamber.corridor.CorridorRepositionService(f.server,record->record.sessionUuid().equals(old.sessionUuid())).tick(f.server);
                if(append.sessionFailed() || append.committedKeys().contains(key) || refusal==null
                        || !old.equals(journal.allSessions().get(old.sessionUuid()))
                        || !old.equals(journal.allFlushedSessions().get(old.sessionUuid()))
                        || !mapping.equals(f.pages.currentMappings(old.sessionUuid()))
                        || !priorRuntime.equals(M4CandidateDoorGameTests.runtimeOwnership(f))
                        || !doors.equals(f.pages.selectableDoors(old.sessionUuid())) || !pose.equals(f.players.getFirst().player().getPos()))
                    failure="預算不足須保隊伍、租約、原映射與已發布門";
            } catch(RuntimeException unexpected) { failure=unexpected.toString(); }
            finally { dev.quantumchamber.persistence.M7CapacityFixture.release(f.server,pressure); }
            if(failure==null) try {
                var retry=service.commitCandidates(f.server,old.sessionUuid(),java.util.List.of(key));
                if(retry.sessionFailed() || !retry.committedKeys().contains(key)) failure="容量釋出後候選未能重試";
                else {
                    var prepared=f.pages.prepareRemap(old.sessionUuid(),mapping.epoch(),java.util.Set.of(1L));
                    if(prepared.baseEpoch()!=mapping.epoch()) failure="容量釋出後未從原映射重試";
                    f.pages.retire(f.pages.cancelPrepared(prepared));
                }
            } catch(RuntimeException unexpected) { failure=unexpected.toString(); }
            final String observed=failure;
            if(observed!=null) org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").warn("M7_CAPACITY_CONTRACT_FAILED {}",observed);
            f.trustedTeardown(tick+1,()->{
                context.assertTrue(observed==null,observed==null?"原旅程權威保持完整":observed);
                context.complete();
            });
        });
    }
}
