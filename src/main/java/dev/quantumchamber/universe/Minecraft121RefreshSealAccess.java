package dev.quantumchamber.universe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.ReloadableRegistries;
/** 瞬時只讀診斷，不是 lease、來源或後續使用權威。 */
public interface Minecraft121RefreshSealAccess {
    boolean quantumchamber$isRefreshSealed(ReloadableRegistries.Lookup lookup, DynamicRegistryManager.Immutable manager);
}
