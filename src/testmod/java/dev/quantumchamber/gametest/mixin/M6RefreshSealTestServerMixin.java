package dev.quantumchamber.gametest.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.gametest.M6RefreshSealStartupObserver;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.test.TestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
/** 固定 TestServer 原生 callsite；原 factory 與 future 原樣通過。 */
@Mixin(TestServer.class)
public abstract class M6RefreshSealTestServerMixin {
    @WrapOperation(method="method_43615(Lnet/minecraft/server/SaveLoading$ServerConfig;Lnet/minecraft/world/level/LevelInfo;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;", at=@At(value="INVOKE",target="Lnet/minecraft/server/SaveLoading;load(Lnet/minecraft/server/SaveLoading$ServerConfig;Lnet/minecraft/server/SaveLoading$LoadContextSupplier;Lnet/minecraft/server/SaveLoading$SaveApplierFactory;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;",ordinal=0),require=1,expect=1,allow=1)
    private static CompletableFuture<?> sealFactory(@Coerce Object config,@Coerce Object supplier,@Coerce Object factory,Executor prepare,Executor apply,Operation<CompletableFuture<?>> original) {
        String token=M6RefreshSealStartupObserver.pinFactory(factory);
        CompletableFuture<?> result=original.call(config,supplier,factory,prepare,apply);
        if(token!=null) result.whenComplete((value,error)->{if(error!=null)M6RefreshSealStartupObserver.startupFailed(token);});
        return result;
    }
}
