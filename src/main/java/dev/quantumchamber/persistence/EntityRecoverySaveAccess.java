package dev.quantumchamber.persistence;
import java.util.Set;

/** 空 entity chunk 的快取也必須在失敗後撤銷，讓下一輪真正重寫。 */
public interface EntityRecoverySaveAccess extends NativeStorageSaveAccess {
    void quantumchamber$markEntitiesForResave(Set<Long> chunks);
}
