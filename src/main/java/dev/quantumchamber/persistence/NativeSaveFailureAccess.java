package dev.quantumchamber.persistence;

import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 原生 server 的序列化／非同步寫入失敗（onChunkSaveFailure），不進入 NBT。 */
public interface NativeSaveFailureAccess {
    /** 全伺服器累計失敗次數；只供診斷與既有 probe，checkpoint 改用下方依世界、以 region 判定的記錄。 */
    long quantumchamber$saveFailureRevision();
    /**
     * 依世界（StorageKey.dimension）分開、以 region 判定的失敗；同一世界的 chunk、entities、poi storage 合併計入（比單一 storage 保守）。
     * POI 沒有自己的 observer 與 barrier：它的非同步寫入失敗只在原版 callback 執行時記到這裡，屬 best-effort。
     * 每個曾回報失敗或被查詢過的世界留一份記錄，世界卸載時不移除。
     */
    NativeSaveFailures quantumchamber$saveFailures(RegistryKey<World> dimension);
}
