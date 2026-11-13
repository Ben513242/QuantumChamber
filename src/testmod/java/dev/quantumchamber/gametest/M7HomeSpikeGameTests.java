package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** T1 僅驗證 scope／正常 pacing／既有平台與 B guard；正式 HOME 尚未開放。 */
public final class M7HomeSpikeGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_windows",tickLimit=200)
    public void checkpoint_capability_is_confirmed_on_windows(TestContext context)throws Exception {
        java.nio.file.Files.createDirectories(context.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.PLAYERDATA));
        context.assertTrue(com.sun.jna.Platform.isWindows(),"Windows 清單不能在 Ubuntu 冒名採計");
        var reason=PlayerCheckpointStore.unsupportedReason(context.getWorld().getServer());
        context.assertTrue(reason.isEmpty(),"Windows 原生 player checkpoint 能力必須實證："+reason);context.complete();
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_ubuntu",tickLimit=200)
    public void checkpoint_capability_remains_rejected_on_ubuntu(TestContext context) {
        context.assertTrue(!com.sun.jna.Platform.isWindows(),"Ubuntu 清單不能在 Windows 冒名採計");
        context.assertTrue(PlayerCheckpointStore.unsupportedReason(context.getWorld().getServer()).filter(reason->reason.equals("checkpoint 尚未驗證非 Windows 平台，保守拒絕")).isPresent(),"Ubuntu 原正式拒絕不能被 test fixture 放寬");context.complete();
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
        var controller=new ChamberControllerBlockEntity(context.getAbsolutePos(new net.minecraft.util.math.BlockPos(1,1,1)),dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER.getDefaultState());
        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        var result=new ChamberActivationService().evaluateReadiness(context.getWorld(),controller);
        context.assertTrue(!result.accepted(),"普通 B activation guard 維持拒絕");context.complete();
    }
}
