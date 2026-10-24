package dev.quantumchamber.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** M6 獨立 runtime 的可觀察 canary；同 batch 有兩個案例，守住完整集合契約。 */
public final class M6GateCanaryGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_gate")
    public void canary_runs_in_exact_m6_scope(TestContext context) {
        context.assertTrue(Boolean.getBoolean("quantumchamber.gametest.m6Only")
                && !Boolean.getBoolean("quantumchamber.gametest.m5Only")
                && !Boolean.getBoolean("quantumchamber.gametest.legacyOnly"), "M6_SCOPE_MISMATCH：必須只有 m6Only");
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_gate")
    public void canary_keeps_all_cases_in_one_batch(TestContext context) {
        context.assertTrue(context.getWorld().getServer().getOverworld() != null, "M6 canary 缺少實際伺服器世界");
        context.complete();
    }
}
