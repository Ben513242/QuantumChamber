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
 * <p>推導同時回傳 PREPARE 的轉換（{@link Transition}，F4b-2，F3d quality m-6(b)）：呼叫端只記「上一個 tick 是否在 PREPARE」，在轉換點做該做的事
 * （離開 PREPARE 時釋放目的世界的 ticket；F4b-3 也在同一點把門改回一般開門），不必把轉換藏在閘門的副作用裡。
 */
public enum CollapsePhase {
    RETURN_PLAYERS, RELEASE_GEOMETRY, RESOLVE, WAITING, PREPARE;

    /** 相對於上一個 tick 的 PREPARE 轉換（spec §7.3）。 */
    public enum Transition {
        /** 沒有轉換：上一個 tick 與這個 tick 都在 PREPARE，或都不在。 */
        NONE,
        /** 進入 PREPARE：上一個 tick 不在 PREPARE，這個 tick 閘門成立。 */
        ENTER_PREPARE,
        /**
         * 離開 PREPARE：上一個 tick 在 PREPARE，這個 tick 不是（閘門失效，退回 WAITING；或推導回到 PREPARE 之前的階段）。已完成的 RESOLVE 與投影都保留，
         * 閘門再次成立時依 §7.1 的邊緣觸發續做。
         */
        LEAVE_PREPARE
    }

    /** 一個 tick 的推導結果：階段，以及相對於上一個 tick 的 PREPARE 轉換。 */
    public record Derivation(CollapsePhase phase, Transition transition) {
        public Derivation {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(transition, "transition");
            if (transition == Transition.ENTER_PREPARE && phase != PREPARE) throw new IllegalArgumentException("進入 PREPARE 的階段必須是 PREPARE：" + phase);
            if (transition == Transition.LEAVE_PREPARE && phase == PREPARE) throw new IllegalArgumentException("離開 PREPARE 的階段不可能是 PREPARE");
        }
    }

    /**
     * PREPARE 的閘門（spec §7.3）。兩種評估由 {@link #derive} 依「上一個 tick 是否在 PREPARE」選一種，呼叫端不必自己再判斷一次（F4b-2 fix round 1，
     * quality Q-M6：「上一個 tick 是否在 PREPARE」只有 {@code derive} 的參數這一個來源）。只在前面的條件都成立時才評估，每個 tick 至多一次。
     */
    public interface Gate {
        /** 上一個 tick 在 PREPARE：來源艙的條件是否仍成立（參與者在艙內不是持續條件）。 */
        boolean stillOpen();

        /**
         * 上一個 tick 不在 PREPARE：這個 tick 是否有 §7.1 的邊緣觸發。評估通常會消耗這次邊緣觸發（同一個觸發只會讓一次評估成立），只有來源艙還沒載入時不消耗
         * （H7，spec v5.9 §7.3：觸發的人留到下一個 tick 再判斷，第一次以位準評估時也不算評估過；F6-2d-2 Step 0 更正，原本寫成一律消耗）。
         */
        boolean edge();
    }

    /**
     * @param view COLLAPSING 的 collapse view（spec §6）；其他狀態丟 {@link IllegalArgumentException}
     * @param releaseComplete 走廊 space 已真的釋放（{@code CorridorPageManager.measuredRecoveryComplete}）
     * @param destinationResolved UNIVERSE 目的地已確定；SOURCE 不看
     * @param preparing 上一個 tick 推導的階段是 PREPARE（呼叫端的簿記，不持久化）；決定轉換，也決定閘門的評估方式
     * @param gate 這個 tick PREPARE 的閘門：{@code preparing} 時呼叫 {@link Gate#stillOpen}，否則呼叫 {@link Gate#edge}（spec §7.3）。只在前面的條件都成立時
     *     才呼叫
     */
    public static Derivation derive(SessionRecoveryRecord view, boolean releaseComplete, boolean destinationResolved, boolean preparing, Gate gate) {
        Objects.requireNonNull(view, "view");
        Objects.requireNonNull(gate, "gate");
        if (view.state() != SessionState.COLLAPSING) throw new IllegalArgumentException("只有 COLLAPSING 有塌縮的推導階段：" + view.state());
        var phase = phase(view, releaseComplete, destinationResolved, preparing ? gate::stillOpen : gate::edge);
        boolean prepare = phase == PREPARE;
        var transition = prepare == preparing ? Transition.NONE : prepare ? Transition.ENTER_PREPARE : Transition.LEAVE_PREPARE;
        return new Derivation(phase, transition);
    }

    private static CollapsePhase phase(SessionRecoveryRecord view, boolean releaseComplete, boolean destinationResolved, BooleanSupplier gate) {
        // 返還與清 lease 的判斷只有一個來源（MeasuredRecoveryPhase）；這裡只多加「走廊 space 真的釋放」。
        var recovery = MeasuredRecoveryPhase.from(view);
        if (recovery == MeasuredRecoveryPhase.RETURN_PLAYERS) return RETURN_PLAYERS;
        if (recovery == MeasuredRecoveryPhase.RELEASE_GEOMETRY || !releaseComplete) return RELEASE_GEOMETRY;
        if (view.collapse().destination() instanceof CollapseDestination.Universe && !destinationResolved) return RESOLVE;
        return gate.getAsBoolean() ? PREPARE : WAITING;
    }
}
