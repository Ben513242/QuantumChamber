package dev.quantumchamber.persistence;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 原生 server 的序列化／非同步寫入失敗（onChunkSaveFailure），不進入 NBT。 */
public interface NativeSaveFailureAccess {
    /** 全伺服器累計失敗次數；只供診斷與既有 probe，checkpoint 改用下方依世界、依 chunk 的記錄。 */
    long quantumchamber$saveFailureRevision();
    /** 依世界（StorageKey.dimension）分開、依 chunk 記錄的失敗；同一世界的 chunk、entities、poi storage 合併計入。 */
    NativeSaveFailures quantumchamber$saveFailures(RegistryKey<World> dimension);
}
