package dev.quantumchamber.chamber;

public enum ChamberState {
    INVALID,
    IDLE,
    READY,
    ARMED,
    /**
     * M5 的量子通道開啟中（spec §10.7）：presence 為 {@code PASSAGE}（PASSAGE_READY）、已供電而且 Origin enabled。比較器 15（{@link ChamberStatusSignal}）。
     * 舊 build 讀到這個名稱時，{@code ChamberControllerBlockEntity} 沿用不認得的值的 INVALID fallback（M5 本來就不可降版）。
     */
    PASSAGE
}
