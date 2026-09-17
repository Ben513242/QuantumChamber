package dev.quantumchamber.gametest.mixin;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.SaveLoading;
import net.minecraft.test.TestServer;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.level.LevelInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 僅測試啟動：沿用 dedicated server 的靜態資料包維度合併，不動 FLAT preset。 */
@Mixin(TestServer.class)
public abstract class StaticDimensionsTestServerMixin {
    @Redirect(method = "method_40377", at = @At(value = "INVOKE", target =
            "Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder;toConfig(Lnet/minecraft/registry/Registry;)Lnet/minecraft/world/dimension/DimensionOptionsRegistryHolder$DimensionsConfig;"))
    private static DimensionOptionsRegistryHolder.DimensionsConfig quantumchamber$includeStaticDimensions(
            DimensionOptionsRegistryHolder holder, Registry<DimensionOptions> ignored,
            LevelInfo levelInfo, SaveLoading.LoadContextSupplierContext context) {
        return holder.toConfig(context.dimensionsRegistryManager().get(RegistryKeys.DIMENSION));
    }
}
