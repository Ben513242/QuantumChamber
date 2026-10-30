package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M6RefreshSealStartupObserver;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.server.DataPackContents;
import net.minecraft.server.SaveLoading;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** exact factory 配對；不依 listener 順序、不手動 refresh。 */
@Mixin(SaveLoading.class)
public abstract class M6RefreshSealSaveLoadingMixin {
    @Inject(method="method_42097(Lnet/minecraft/server/SaveLoading$SaveApplierFactory;Lnet/minecraft/resource/LifecycledResourceManager;Lnet/minecraft/registry/CombinedDynamicRegistries;Lnet/minecraft/server/SaveLoading$LoadContext;Lnet/minecraft/server/DataPackContents;)Ljava/lang/Object;",at=@At("HEAD"),require=1,expect=1,allow=1)
    private static void sealArm(@Coerce Object factory,LifecycledResourceManager rm,CombinedDynamicRegistries<?> registries,@Coerce Object context,DataPackContents contents,CallbackInfoReturnable<Object> ci) {
        M6RefreshSealStartupObserver.arm(factory,rm,contents);
    }
    @Inject(method="method_42097(Lnet/minecraft/server/SaveLoading$SaveApplierFactory;Lnet/minecraft/resource/LifecycledResourceManager;Lnet/minecraft/registry/CombinedDynamicRegistries;Lnet/minecraft/server/SaveLoading$LoadContext;Lnet/minecraft/server/DataPackContents;)Ljava/lang/Object;",at=@At(value="INVOKE",target="Lnet/minecraft/server/DataPackContents;refresh()V",ordinal=0,shift=At.Shift.AFTER),require=1,expect=1,allow=1)
    private static void sealReturned(@Coerce Object factory,LifecycledResourceManager rm,CombinedDynamicRegistries<?> registries,@Coerce Object context,DataPackContents contents,CallbackInfoReturnable<Object> ci) {
        M6RefreshSealStartupObserver.callReturned(factory,rm,contents);
    }
}
