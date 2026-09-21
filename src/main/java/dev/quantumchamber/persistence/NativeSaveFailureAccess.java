package dev.quantumchamber.persistence;

/** 原生 server 的序列化／非同步寫入失敗 revision，不進入 NBT。 */
public interface NativeSaveFailureAccess {
    long quantumchamber$saveFailureRevision();
}
