package dev.quantumchamber.gametest;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
/** 一個 XML case；BEFORE_WORK 是其中的具名子斷言，不冒稱第二個 GameTest。 */
public final class M6RefreshSealGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_refresh_seal")
    public void naturalstartuprefreshsealsexactboundsources(TestContext context) {
        M6RefreshSealStartupObserver.assertNaturalStartup(context);
        context.complete();
    }
}
