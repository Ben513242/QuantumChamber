package dev.quantumchamber.gametest.mixin;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.SaveLoading;
import net.minecraft.test.TestServer;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.level.LevelInfo;
import net.minecraft.world.gen.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 僅測試啟動：沿用 dedicated server 的靜態資料包維度合併，default、normal 與 M5 run 選 DEFAULT，legacy/M6 保留 FLAT。 */
@Mixin(TestServer.class)
public abstract class StaticDimensionsTestServerMixin {
    @Redirect(method = "method_40377", require = 1, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;toConfig(Lnet/minecraft/registry/Registry;)Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder$DimensionsConfig;"))
    private static DimensionOptionsRegistryHolder.DimensionsConfig quantumchamber$includeStaticDimensions(
            DimensionOptionsRegistryHolder holder, Registry<DimensionOptions> ignored,
            LevelInfo levelInfo, SaveLoading.LoadContextSupplierContext context) {
        boolean m7=Boolean.getBoolean("quantumchamber.test.m7Home");
        boolean defaultNormal="true".equals(System.getProperty("quantumchamber.gametest.defaultNormal"));
        boolean normal="true".equals(System.getProperty("quantumchamber.gametest.m4NormalOnly"));
        boolean m5Only="true".equals(System.getProperty("quantumchamber.gametest.m5Only"));
        boolean m5Normal="true".equals(System.getProperty("quantumchamber.gametest.m5Normal"));
        if (m5Normal && (!m5Only || defaultNormal || normal || Boolean.getBoolean("quantumchamber.gametest.legacyOnly")
                || Boolean.getBoolean("quantumchamber.gametest.m6Only")))
            throw new IllegalStateException("M4_TEST_PRESET_SCOPE：m5Normal 只能與 m5Only 使用");
        if (defaultNormal && (normal || Boolean.getBoolean("quantumchamber.gametest.legacyOnly")
                || Boolean.getBoolean("quantumchamber.gametest.m5Only") || Boolean.getBoolean("quantumchamber.gametest.m6Only")))
            throw new IllegalStateException("M4_TEST_PRESET_SCOPE：defaultNormal 不得與任何 Only scope 混用");
        if(m7 && (defaultNormal || normal || m5Only || m5Normal || Boolean.getBoolean("quantumchamber.gametest.m6Only") || Boolean.getBoolean("quantumchamber.gametest.legacyOnly"))) throw new IllegalStateException("M7_SCOPE_MISMATCH");
        var normalHolder=context.worldGenRegistryManager().get(RegistryKeys.WORLD_PRESET)
                .entryOf(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder();
        if (defaultNormal || normal || m5Normal && m5Only) holder=normalHolder;
        var config=holder.toConfig(context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION));
        var generator=config.dimensions().get(DimensionOptions.OVERWORLD).chunkGenerator();
        boolean flat=generator instanceof net.minecraft.world.gen.chunk.FlatChunkGenerator;
        boolean normalConfig=generator==normalHolder.getChunkGenerator();
        if (!(defaultNormal || normal || m5Normal && m5Only) && !flat)
            throw new IllegalStateException("M4_TEST_PRESET_MISMATCH：legacy/M6 或未啟用 normal 的 M5 必須保留 flat");
        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info(
                "M4_TEST_PRESET defaultNormal={} normalScope={} m5Only={} m5Normal={} flatConfig={} normalConfig={}",defaultNormal,normal,m5Only,m5Normal,flat,normalConfig);
        return config;
    }
}
