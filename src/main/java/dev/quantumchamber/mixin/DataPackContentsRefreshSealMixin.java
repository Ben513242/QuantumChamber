package dev.quantumchamber.mixin;

import dev.quantumchamber.universe.Minecraft121RefreshSealAccess;
import java.lang.ref.WeakReference;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.ReloadableRegistries;
import net.minecraft.server.DataPackContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 正常 refresh 返回的瞬時診斷；不授予來源、epoch 或 lease 權威。 */
@Mixin(DataPackContents.class)
public abstract class DataPackContentsRefreshSealMixin implements Minecraft121RefreshSealAccess {
    @Unique private final Object quantumchamber$sealMonitor=new Object();
    @Unique private WeakReference<ReloadableRegistries.Lookup> quantumchamber$sealedLookup=new WeakReference<>(null);
    @Unique private WeakReference<DynamicRegistryManager.Immutable> quantumchamber$sealedManager=new WeakReference<>(null);
    @Unique private int quantumchamber$refreshDepth;
    @Unique private boolean quantumchamber$refreshPoisoned;

    @Inject(method="refresh()V",at=@At("HEAD"),require=1,expect=1,allow=1)
    private void quantumchamber$refreshStarted(CallbackInfo ci) {
        synchronized(quantumchamber$sealMonitor) {
            quantumchamber$sealedLookup.clear();
            quantumchamber$sealedManager.clear();
            // 未返回、重入或交錯的 instance 保守失效；本片不宣稱其負例已驗。
            if(quantumchamber$refreshDepth!=0)quantumchamber$refreshPoisoned=true;
            if(quantumchamber$refreshDepth==Integer.MAX_VALUE)quantumchamber$refreshPoisoned=true;
            else quantumchamber$refreshDepth++;
        }
    }
    @Inject(method="refresh()V",at=@At("RETURN"),require=1,expect=1,allow=1)
    private void quantumchamber$refreshReturned(CallbackInfo ci) {
        var lookup=((DataPackContents)(Object)this).getReloadableRegistries();
        var manager=lookup.getRegistryManager();
        synchronized(quantumchamber$sealMonitor) {
            if(quantumchamber$refreshDepth!=1)quantumchamber$refreshPoisoned=true;
            if(quantumchamber$refreshDepth>0)quantumchamber$refreshDepth--;
            if(!quantumchamber$refreshPoisoned&&quantumchamber$refreshDepth==0) {
                quantumchamber$sealedLookup=new WeakReference<>(lookup);
                quantumchamber$sealedManager=new WeakReference<>(manager);
            }
        }
    }
    @Override public boolean quantumchamber$isRefreshSealed(ReloadableRegistries.Lookup lookup,DynamicRegistryManager.Immutable manager) {
        synchronized(quantumchamber$sealMonitor) {
            return lookup!=null&&manager!=null&&!quantumchamber$refreshPoisoned&&quantumchamber$refreshDepth==0
                    &&quantumchamber$sealedLookup.get()==lookup&&quantumchamber$sealedManager.get()==manager;
        }
    }
}
