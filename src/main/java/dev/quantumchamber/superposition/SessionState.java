package dev.quantumchamber.superposition;

/** session 的 durable 狀態。M5 新增 COLLAPSING、PASSAGE_READY、SEALED（spec §5）。 */
public enum SessionState {
    ARMING, SUPERPOSITION, RETURNING, MEASURED, COLLAPSING, PASSAGE_READY, SEALED;

    /**
     * spec §5 的 receiptRetained：MEASURED 與三個塌縮狀態都保留 M4 收據（context、ledger、selection 不再改變）。
     * 呼叫端請用 {@code SessionRecoveryState.receiptRetained}（spec §13.2 的呼叫點都以它為準）；這裡只是它的實作。
     */
    public boolean retainsReceipt() { return this == MEASURED || collapseState(); }

    /** M5 的三個塌縮狀態；只有它們帶非空的 {@code Collapse} 欄位。 */
    public boolean collapseState() { return this == COLLAPSING || this == PASSAGE_READY || this == SEALED; }
}
