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

/** 僅測試啟動：沿用 dedicated server 的靜態資料包維度合併，僅 normal 專用 run 選 DEFAULT，其餘保留 FLAT。 */
@Mixin(TestServer.class)
public abstract class StaticDimensionsTestServerMixin {
    @Redirect(method = "method_40377", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;toConfig(Lnet/minecraft/registry/Registry;)Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder$DimensionsConfig;"))
    private static DimensionOptionsRegistryHolder.DimensionsConfig quantumchamber$includeStaticDimensions(
            DimensionOptionsRegistryHolder holder, Registry<DimensionOptions> ignored,
            LevelInfo levelInfo, SaveLoading.LoadContextSupplierContext context) {
        if (Boolean.getBoolean("quantumchamber.gametest.m4NormalOnly")) {
            holder=context.worldGenRegistryManager().get(RegistryKeys.WORLD_PRESET)
                    .entryOf(WorldPresets.DEFAULT).value().createDimensionsRegistryHolder();
        }
        var config=holder.toConfig(context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION));
        boolean flat=config.dimensions().get(DimensionOptions.OVERWORLD).chunkGenerator()
                instanceof net.minecraft.world.gen.chunk.FlatChunkGenerator;
        boolean normal=Boolean.getBoolean("quantumchamber.gametest.m4NormalOnly");
        if (!normal && !flat) throw new IllegalStateException("M4_TEST_PRESET_MISMATCH：非 normal run 必須保留 flat");
        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info(
                "M4_TEST_PRESET normalScope={} flatConfig={}",normal,flat);
        return config;
    }
}
