package dev.quantumchamber.universe;

/** 8B2 發布的 server-bound 唯讀設定 lease；8B1 不提供可用實例。 */
public interface ServerSettingsContext {
    void requireValid();
}
