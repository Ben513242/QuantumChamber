package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M6SettingsTraceObserver;
import java.util.List;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.registry.ReloadableRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** 原生 apply 的精確返回點。 */
@Mixin(ReloadableRegistries.class)
public abstract class M6SettingsTraceReloadableMixin {
    @Inject(method="apply(Lnet/minecraft/registry/CombinedDynamicRegistries;Ljava/util/List;)Lnet/minecraft/registry/CombinedDynamicRegistries;",at=@At("RETURN"),require=1,expect=1,allow=1)
    private static void traceApply(CombinedDynamicRegistries<?> input, List<?> registries, CallbackInfoReturnable<CombinedDynamicRegistries<?>> ci) {
        M6SettingsTraceObserver.applied(ci.getReturnValue().getCombinedRegistryManager());
    }
}
