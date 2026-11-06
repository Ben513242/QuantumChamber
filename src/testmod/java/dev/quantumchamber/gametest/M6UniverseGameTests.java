package dev.quantumchamber.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** 同 server 的真 schema2 backend 與 canonical／legacy 控制組。 */
public final class M6UniverseGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_seed_owner", tickLimit=1200)
    public void new_universe_native_seed_settings_and_random_sequences(TestContext context) throws Exception {
        M6RandomProbePhases.verifyGameTest(context.getWorld().getServer());
        context.complete();
    }
}
