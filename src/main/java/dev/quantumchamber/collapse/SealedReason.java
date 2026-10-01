package dev.quantumchamber.collapse;

/** SEALED 的原因分類（spec §11.2）。只有可重試的原因有 SEALED→COLLAPSING 的重試觸發；永久原因不自動重試。 */
public enum SealedReason {
    ALLOCATION_IO_FAILED(true, false),
    PROJECTION_FAILED(true, false),
    MATERIALIZE_FAILED(true, true),
    AUTHORITY_UNREADABLE(true, true),
    EXISTING_INELIGIBLE(false, false),
    AUTHORITY_CORRUPT(false, false),
    PROJECTION_CONFLICT(false, false),
    /** 只能由 PASSAGE_READY 進入（spec §10.3）；journal 層級沒有 SEALED→COLLAPSING 的邊。 */
    TRANSFER_RECOVERY_REQUIRED(false, false);

    private final boolean retryable;
    private final boolean restartOnly;

    SealedReason(boolean retryable, boolean restartOnly) {
        this.retryable = retryable;
        this.restartOnly = restartOnly;
    }

    /** 可重試：依 §11.2 的邊緣觸發（與退避）回到 COLLAPSING。 */
    public boolean retryable() { return retryable; }

    /** 只在重啟之後重試，每個 server instance 一次（§8.3、§11.2）。只有可重試的原因可能為真。 */
    public boolean restartOnly() { return restartOnly; }
}
