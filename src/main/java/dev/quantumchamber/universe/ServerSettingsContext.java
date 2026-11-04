package dev.quantumchamber.universe;

import net.minecraft.registry.DynamicRegistryManager;

/** 8B2 發布的 server-bound 唯讀設定 lease；8B1 不提供可用實例。 */
public interface ServerSettingsContext {
    void requireValid();
    DynamicRegistryManager.Immutable registries();
    boolean sourceGenerateStructures();
}
