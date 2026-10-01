package dev.quantumchamber.collapse;

/**
 * spec §11.2 的退避數列：第一次失敗之後等 200 ticks，之後每次失敗加倍，上限 12000 ticks（200、400、800、…、6400、12000、12000…）。
 * <p>只記狀態、不讀時鐘：呼叫端傳入目前的 tick（server tick 計數）。一份 instance 對應一個單位：§6 的權威收斂以權威為單位
 * （{@link CollapseAuthorities}），§7.1 的提交失敗與 §11.2 的 SEALED 重試以 session 為單位（F3d／F4a）。
 * 新的 instance 沒有退避，所以每個 server instance 開服後的第一次嘗試不受限制（§11.2）。只在 server thread 使用。
 */
public final class CollapseBackoff {
    public static final int INITIAL_TICKS = 200;
    public static final int MAX_TICKS = 12000;

    private int delayTicks;
    private long retryAtTick;

    /** 目前不在退避期內（從未失敗、已 {@link #reset}，或已到 {@link #retryAtTick()}）。 */
    public boolean ready(long nowTick) { return delayTicks == 0 || nowTick >= retryAtTick; }

    /** 記一次失敗：延遲加倍（第一次為 {@link #INITIAL_TICKS}，上限 {@link #MAX_TICKS}），下一次嘗試排在 now＋延遲。 */
    public void failed(long nowTick) {
        delayTicks = delayTicks == 0 ? INITIAL_TICKS : Math.min(delayTicks * 2, MAX_TICKS);
        retryAtTick = nowTick + delayTicks;
    }

    /** 成功之後清除退避。 */
    public void reset() { delayTicks = 0; retryAtTick = 0; }

    /** 最近一次失敗之後的延遲；沒有退避時為 0。 */
    public int delayTicks() { return delayTicks; }

    /** 下一次允許嘗試的 tick；沒有退避時為 0。 */
    public long retryAtTick() { return retryAtTick; }
}
