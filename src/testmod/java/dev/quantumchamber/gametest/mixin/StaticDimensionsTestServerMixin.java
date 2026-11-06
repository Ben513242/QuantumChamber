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

/** 僅測試啟動：沿用 dedicated server 的靜態資料包維度合併，default 與 normal 專用 run 選 DEFAULT，legacy/M5/M6 保留 FLAT。 */
@Mixin(TestServer.class)
public abstract class StaticDimensionsTestServerMixin {
    @Redirect(method = "method_40377", require = 1, at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;toConfig(Lnet/minecraft/registry/Registry;)Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder$DimensionsConfig;"))
    private static DimensionOptionsRegistryHolder.DimensionsConfig quantumchamber$includeStaticDimensions(
            DimensionOptionsRegistryHolder holder, Registry<DimensionOptions> ignored,
            LevelInfo levelInfo, SaveLoading.LoadContextSupplierContext context) {
        boolean defaultNormal="true".equals(System.getProperty("quantumchamber.gametest.defaultNormal"));
        boolean normal="true".equals(System.getProperty("quantumchamber.gametest.m4NormalOnly"));
        if (defaultNormal && (normal || Boolean.getBoolean("quantumchamber.gametest.legacyOnly")
                || Boolean.getBoolean("quantumchamber.gametest.m5Only") || Boolean.getBoolean("quantumchamber.gametest.m6Only")))
            throw new IllegalStateException("M4_TEST_PRESET_SCOPE：defaultNormal 不得與任何 Only scope 混用");
        var normalHolder=context.worldGenRegistryManager().get(RegistryKeys.WORLD_PRESET)
                .entryOf(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder();
        if (defaultNormal || normal) holder=normalHolder;
        var config=holder.toConfig(context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION));
        var generator=config.dimensions().get(DimensionOptions.OVERWORLD).chunkGenerator();
        boolean flat=generator instanceof net.minecraft.world.gen.chunk.FlatChunkGenerator;
        boolean normalConfig=generator==normalHolder.getChunkGenerator();
        if (!(defaultNormal || normal) && !flat)
            throw new IllegalStateException("M4_TEST_PRESET_MISMATCH：legacy/M5/M6 必須保留 flat");
        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info(
                "M4_TEST_PRESET defaultNormal={} normalScope={} flatConfig={} normalConfig={}",defaultNormal,normal,flat,normalConfig);
        return config;
    }
}
