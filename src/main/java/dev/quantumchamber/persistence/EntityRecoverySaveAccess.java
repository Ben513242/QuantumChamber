package dev.quantumchamber.persistence;
import java.util.Set;
import net.minecraft.world.storage.StorageIoWorker;

/** 空 entity chunk 的快取也必須在失敗後撤銷，讓下一輪真正重寫。 */
public interface EntityRecoverySaveAccess extends NativeStorageSaveAccess {
    void quantumchamber$markEntitiesForResave(Set<Long> chunks);
    /** entity storage 底下的原生 IO worker（取它的 region 檔快取逐出記錄）。 */
    StorageIoWorker quantumchamber$storageWorker();
}
