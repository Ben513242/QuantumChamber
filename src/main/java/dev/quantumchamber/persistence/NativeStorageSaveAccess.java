package dev.quantumchamber.persistence;

/**
 * 每份原生 storage 的失敗記錄：StorageIoWorker（寫入與 flush）、EntityChunkDataAccess（entity 寫入）、
 * RegionBasedStorage（region 檔快取逐出時的 close）。
 */
public interface NativeStorageSaveAccess {
    NativeSaveFailures quantumchamber$saveFailures();
}
