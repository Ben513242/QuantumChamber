package dev.quantumchamber.collapse;

import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * COLLAPSING 的推導階段（spec §7.3）。只由 collapse view 與當下的觀察推導、不另存，重啟之後依同一份推導續做。
 * <ul>
 * <li>{@link #RETURN_PLAYERS}：有人尚未返還。返還由 recovery 依收據驅動（{@code SessionRecoveryManager.recoveryWork}），塌縮流程不重複驅動。</li>
 * <li>{@link #RELEASE_GEOMETRY}：全員已返還，但仍有 lease，或走廊 space 還沒真的釋放（清 lease 的寫出可能先於 space 釋放，F3a I-1）。</li>
 * <li>{@link #RESOLVE}：目的地是 UNIVERSE、還沒確定。</li>
 * <li>{@link #WAITING}：目的地已確定（SOURCE 不需要確定），但 PREPARE 的閘門不成立。</li>
 * <li>{@link #PREPARE}：同上，而且閘門成立。</li>
 * </ul>
 * RETURN_PLAYERS 與 RELEASE_GEOMETRY 直接取自 {@link MeasuredRecoveryPhase#from}（M4 與 M5 共用的推導）；它的 SETTLED 只看 record，
 * 不含走廊 space 是否已釋放，所以這裡另外要求 {@code releaseComplete}。
 */
public enum CollapsePhase {
    RETURN_PLAYERS, RELEASE_GEOMETRY, RESOLVE, WAITING, PREPARE;

    /**
     * @param view COLLAPSING 的 collapse view（spec §6）；其他狀態丟 {@link IllegalArgumentException}
     * @param releaseComplete 走廊 space 已真的釋放（{@code CorridorPageManager.measuredRecoveryComplete}）
     * @param destinationResolved UNIVERSE 目的地已確定；SOURCE 不看
     * @param gate PREPARE 的閘門。只在前面的條件都成立時才呼叫：呼叫本身可能消耗一次邊緣觸發
     */
    public static CollapsePhase derive(SessionRecoveryRecord view, boolean releaseComplete, boolean destinationResolved, BooleanSupplier gate) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(gate, "gate");
        if (view.state() != SessionState.COLLAPSING) throw new IllegalArgumentException("只有 COLLAPSING 有塌縮的推導階段：" + view.state());
        // 返還與清 lease 的判斷只有一個來源（MeasuredRecoveryPhase）；這裡只多加「走廊 space 真的釋放」。
        var recovery = MeasuredRecoveryPhase.from(view);
        if (recovery == MeasuredRecoveryPhase.RETURN_PLAYERS) return RETURN_PLAYERS;
        if (recovery == MeasuredRecoveryPhase.RELEASE_GEOMETRY || !releaseComplete) return RELEASE_GEOMETRY;
        if (view.collapse().destination() instanceof CollapseDestination.Universe && !destinationResolved) return RESOLVE;
        return gate.getAsBoolean() ? PREPARE : WAITING;
    }
}
