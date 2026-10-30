package dev.quantumchamber.gametest.mixin;

import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.gametest.M6SettingsTraceObserver;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import net.minecraft.registry.CombinedDynamicRegistries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 原 callback 恰一次，原 stage/future 原樣回傳；所有診斷均為旁路。 */
@Mixin(MinecraftServer.class)
public abstract class M6SettingsTraceServerMixin {
    @Shadow @Final protected LevelStorage.Session session;
    @Shadow @Final private StructureTemplateManager structureTemplateManager;

    @Inject(method="reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void traceBegin(Collection<?> packs, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        M6SettingsTraceObserver.begin((MinecraftServer)(Object)this);
    }
    @WrapOperation(method="reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;thenCompose(Ljava/util/function/Function;)Ljava/util/concurrent/CompletableFuture;",ordinal=0),require=1,expect=1,allow=1)
    private CompletableFuture<?> traceCompose(CompletableFuture<?> receiver, Function<Object,CompletionStage<?>> function, Operation<CompletableFuture<?>> original) {
        if(!M6SettingsTraceObserver.enabled()) return original.call(receiver,function);
        int token=M6SettingsTraceObserver.callToken();
        Function<Object,CompletionStage<?>> wrapped=input->{
            M6SettingsTraceObserver.composeInput(token,input);
            CompletionStage<?> result=function.apply(input);
            M6SettingsTraceObserver.stage(token,result);
            return result;
        };
        return original.call(receiver,wrapped);
    }
    @WrapOperation(method="reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",at=@At(value="INVOKE",target="Ljava/util/concurrent/CompletableFuture;thenAcceptAsync(Ljava/util/function/Consumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",ordinal=0),require=1,expect=1,allow=1)
    private CompletableFuture<?> traceAccept(CompletableFuture<?> receiver, Consumer<Object> consumer, Executor executor, Operation<CompletableFuture<?>> original) {
        if(!M6SettingsTraceObserver.enabled()) return original.call(receiver,consumer,executor);
        int token=M6SettingsTraceObserver.callToken();
        Consumer<Object> wrapped=holder->{M6SettingsTraceObserver.holderInput(token,holder);consumer.accept(holder);};
        CompletableFuture<?> result=original.call(receiver,wrapped,executor);
        M6SettingsTraceObserver.future(token,result);
        return result;
    }
    @WrapOperation(method="method_29437(Lcom/google/common/collect/ImmutableList;)Ljava/util/concurrent/CompletionStage;",at=@At(value="INVOKE",target="Lnet/minecraft/server/DataPackContents;reload(Lnet/minecraft/resource/ResourceManager;Lnet/minecraft/registry/CombinedDynamicRegistries;Lnet/minecraft/resource/featuretoggle/FeatureSet;Lnet/minecraft/server/command/CommandManager$RegistrationEnvironment;ILjava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",ordinal=0),require=1,expect=1,allow=1)
    private CompletableFuture<?> traceReload(ResourceManager rm, CombinedDynamicRegistries<?> registries, FeatureSet features, CommandManager.RegistrationEnvironment env, int permission, Executor prepare, Executor apply, Operation<CompletableFuture<?>> original, ImmutableList<?> enclosingPacks) {
        M6SettingsTraceObserver.bindResource(enclosingPacks,rm);
        return original.call(rm,registries,features,env,permission,prepare,apply);
    }
    @Inject(method="method_29437(Lcom/google/common/collect/ImmutableList;)Ljava/util/concurrent/CompletionStage;",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void traceComposeHead(ImmutableList<?> packs, CallbackInfoReturnable<CompletionStage<?>> ci) {M6SettingsTraceObserver.composeHead(packs);}
    @Inject(method="method_29437(Lcom/google/common/collect/ImmutableList;)Ljava/util/concurrent/CompletionStage;",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceComposeReturn(ImmutableList<?> packs, CallbackInfoReturnable<CompletionStage<?>> ci) {M6SettingsTraceObserver.nativeStage(packs,ci.getReturnValue());}
    @Inject(method="reloadResources(Ljava/util/Collection;)Ljava/util/concurrent/CompletableFuture;",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceReturn(Collection<?> packs, CallbackInfoReturnable<CompletableFuture<Void>> ci) {M6SettingsTraceObserver.returned(ci.getReturnValue());}

    @WrapOperation(method="<init>(Ljava/lang/Thread;Lnet/minecraft/world/level/storage/LevelStorage$Session;Lnet/minecraft/resource/ResourcePackManager;Lnet/minecraft/server/SaveLoader;Ljava/net/Proxy;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/util/ApiServices;Lnet/minecraft/server/WorldGenerationProgressListenerFactory;)V",at=@At(value="FIELD",target="Lnet/minecraft/server/MinecraftServer;resourceManagerHolder:Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;",opcode=181,ordinal=0),require=1,expect=1,allow=1)
    private void traceInitialStore(MinecraftServer receiver, @Coerce Object holder, Operation<Void> original) {original.call(receiver,holder);M6SettingsTraceObserver.store(receiver,holder,true);}
    @WrapOperation(method="method_29440(Ljava/util/Collection;Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;)V",at=@At(value="FIELD",target="Lnet/minecraft/server/MinecraftServer;resourceManagerHolder:Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;",opcode=181,ordinal=0),require=1,expect=1,allow=1)
    private void traceReloadStore(MinecraftServer receiver, @Coerce Object holder, Operation<Void> original) {original.call(receiver,holder);M6SettingsTraceObserver.store(receiver,holder,false);}
    @Inject(method="method_29440(Ljava/util/Collection;Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;)V",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void traceApplyHead(Collection<?> packs, @Coerce Object holder, CallbackInfo ci) {M6SettingsTraceObserver.applyHead(holder);}
    @Inject(method="method_29440(Ljava/util/Collection;Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;)V",at=@At(value="INVOKE",target="Lnet/minecraft/structure/StructureTemplateManager;setResourceManager(Lnet/minecraft/resource/ResourceManager;)V",shift=At.Shift.AFTER,ordinal=0),require=1,expect=1,allow=1)
    private void traceTemplates(Collection<?> packs, @Coerce Object holder, CallbackInfo ci) {M6SettingsTraceObserver.active((MinecraftServer)(Object)this,holder,structureTemplateManager,"TEMPLATES_UPDATED");}
    @Inject(method="method_29440(Ljava/util/Collection;Lnet/minecraft/server/MinecraftServer$ResourceManagerHolder;)V",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceApplyReturn(Collection<?> packs, @Coerce Object holder, CallbackInfo ci) {M6SettingsTraceObserver.active((MinecraftServer)(Object)this,holder,structureTemplateManager,"APPLY_EXIT");}
    @Inject(method="<init>(Ljava/lang/Thread;Lnet/minecraft/world/level/storage/LevelStorage$Session;Lnet/minecraft/resource/ResourcePackManager;Lnet/minecraft/server/SaveLoader;Ljava/net/Proxy;Lcom/mojang/datafixers/DataFixer;Lnet/minecraft/util/ApiServices;Lnet/minecraft/server/WorldGenerationProgressListenerFactory;)V",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceConstructor(CallbackInfo ci) {M6SettingsTraceObserver.constructed((MinecraftServer)(Object)this,session,structureTemplateManager);}
    @Inject(method="runServer()V",at=@At(value="INVOKE",target="Lnet/minecraft/server/MinecraftServer;setupServer()Z",ordinal=0),require=1,expect=1,allow=1)
    private void traceStarting(CallbackInfo ci) {M6SettingsTraceObserver.starting((MinecraftServer)(Object)this);}
    @Inject(method="shutdown()V",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void traceStopping(CallbackInfo ci) {M6SettingsTraceObserver.stopping();}
    @Inject(method="shutdown()V",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void traceStopped(CallbackInfo ci) {M6SettingsTraceObserver.stopped();}
}
