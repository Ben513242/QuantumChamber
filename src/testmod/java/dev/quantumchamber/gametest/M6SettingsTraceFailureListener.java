package dev.quantumchamber.gametest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloader;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;
/** 唯一具名失敗 reloader；不替換任何原生 aggregate future。 */
public final class M6SettingsTraceFailureListener implements IdentifiableResourceReloadListener {
    @Override public Identifier getFabricId(){return Identifier.of("quantumchamber:lt_failure_listener");}
    @Override public CompletableFuture<Void> reload(ResourceReloader.Synchronizer synchronizer,ResourceManager manager,Profiler prepareProfiler,Profiler applyProfiler,Executor prepareExecutor,Executor applyExecutor){
        final int token;
        try{token=M6SettingsTraceObserver.resourceToken(manager);}catch(RuntimeException error){M6SettingsTraceObserver.fail("FAULT_UNKNOWN_RM");return CompletableFuture.failedFuture(new IllegalStateException("LT_UNKNOWN_RM"));}
        if(token==2){M6SettingsTraceObserver.controlled(manager);return CompletableFuture.failedFuture(new IllegalStateException("LT_CONTROLLED_FAILURE"));}
        return synchronizer.whenPrepared(Boolean.TRUE).thenAcceptAsync(unused->{},applyExecutor);
    }
}
