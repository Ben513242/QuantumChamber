package dev.quantumchamber.gametest;

import dev.quantumchamber.universe.Minecraft121SettingsLifecycle;
import dev.quantumchamber.universe.DimensionRole;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** 自然開服的唯一設定 context 案例。 */
public final class M6SettingsGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_settings_context")
    public void settingscontextnaturalstartupacquireandresolve(TestContext context) {
        var server = context.getWorld().getServer();
        context.assertTrue(Minecraft121SettingsLifecycle.acquire(server).registries() == server.getRegistryManager(),
                "自然啟動必須發布同一 registry manager");
        var lease = Minecraft121SettingsLifecycle.acquire(server);
        var registry = Minecraft121SettingsLifecycle.registry();
        for (String preset : new String[] { "normal", "amplified" }) {
            String id = "quantumchamber:vanilla_" + preset + "_v1";
            var parameters = new NbtCompound();
            parameters.putString("OverworldPreset", preset);
            parameters.putByte("GenerateStructures", (byte) (lease.sourceGenerateStructures() ? 1 : 0));
            var snapshot = registry.createSnapshot(id, 1, parameters, lease);
            context.assertTrue(snapshot.outputFingerprint().length == 32, "指紋必須為 SHA-256");
            var resolved = registry.resolve(snapshot, lease);
            context.assertTrue(resolved.roles().keySet().containsAll(java.util.Set.of(DimensionRole.values()))
                    && resolved.roles().size() == 3, "normal/amplified 各須解析三個 role");
            context.assertTrue(resolved.generateStructures() == lease.sourceGenerateStructures(),
                    "生成結構旗標須保留");
            var again = registry.createSnapshot(id, 1, parameters, lease);
            context.assertTrue(java.util.Arrays.equals(snapshot.outputFingerprint(), again.outputFingerprint()),
                    "相同設定須重算相同指紋");
        }
        context.complete();
    }
}
