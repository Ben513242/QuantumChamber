package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M6SettingsTraceObserver;
import dev.quantumchamber.gametest.M6RefreshSealStartupObserver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.DataPackContents;
import net.minecraft.server.command.CommandManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** 同步 refresh 為硬見證；旁路 completion 不是 barrier。 */
@Mixin(DataPackContents.class)
public abstract class M6SettingsTraceContentsMixin {
    @Inject(method="reload(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/registry/CombinedDynamicRegistries;Lnet/minecraft/resource/featuretoggle/FeatureSet;Lnet/minecraft/server/command/CommandManager$RegistrationEnvironment;ILjava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",at=@At("HEAD"),require=1,expect=1,allow=1)
    private static void traceInput(ResourceManager rm, CombinedDynamicRegistries<?> registries, FeatureSet features, CommandManager.RegistrationEnvironment env, int permission, Executor prepare, Executor apply, CallbackInfoReturnable<CompletableFuture<DataPackContents>> ci) {
        M6SettingsTraceObserver.reloadInput(rm);
    }
    @Inject(method="reload(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/registry/CombinedDynamicRegistries;Lnet/minecraft/resource/featuretoggle/FeatureSet;Lnet/minecraft/server/command/CommandManager$RegistrationEnvironment;ILjava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",at=@At("RETURN"),require=1,expect=1,allow=1)
    private static void traceCompletion(ResourceManager rm, CombinedDynamicRegistries<?> registries, FeatureSet features, CommandManager.RegistrationEnvironment env, int permission, Executor prepare, Executor apply, CallbackInfoReturnable<CompletableFuture<DataPackContents>> ci) {
        M6SettingsTraceObserver.contentsFuture(rm,ci.getReturnValue());
    }
    @Inject(method="refresh()V",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void traceRefreshHead(CallbackInfo ci) { M6SettingsTraceObserver.refresh((DataPackContents)(Object)this,false); M6RefreshSealStartupObserver.refresh((DataPackContents)(Object)this,"REFRESH_HEAD"); }
    @Inject(method="refresh()V",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceRefreshReturn(CallbackInfo ci) { M6SettingsTraceObserver.refresh((DataPackContents)(Object)this,true); M6RefreshSealStartupObserver.refresh((DataPackContents)(Object)this,"REFRESH_RETURN"); }
    @Inject(method="refresh()V",at=@At(value="INVOKE",target="Lnet/minecraft/registry/tag/TagManagerLoader;getRegistryTags()Ljava/util/List;",ordinal=0),require=1,expect=1,allow=1)
    private void sealBeforeWork(CallbackInfo ci) { M6RefreshSealStartupObserver.beforeWork((DataPackContents)(Object)this); }
}
