package dev.quantumchamber.persistence;

import dev.quantumchamber.superposition.SessionState;

/**
 * 保留收據的返還與清理階段，由 receipt 推導、不另存（spec §7.3 的 RETURN_PLAYERS、RELEASE_GEOMETRY）。M4 的 MEASURED 與 M5 的
 * COLLAPSING 共用同一份推導：有人尚未返還為 RETURN_PLAYERS；全員已返還但仍有 lease 為 RELEASE_GEOMETRY；全員已返還、lease 已清空時，
 * MEASURED 是 DORMANT（M4 的保留收據），COLLAPSING 是 SETTLED（返還與清理已完成；之後的 RESOLVE／WAITING／PREPARE 由塌縮流程推導）。
 */
public enum MeasuredRecoveryPhase {
    RETURN_PLAYERS, RELEASE_GEOMETRY, DORMANT, SETTLED;

    /**
     * 前提：record 必須是 MEASURED 或 COLLAPSING，而且是 SELECTED。其他狀態（含 PASSAGE_READY 與 SEALED）一律丟
     * {@link IllegalArgumentException}；走訪多筆 record 的呼叫端必須先以狀態過濾，或逐筆隔離例外，不能讓它從 tick 拋出。
     */
    public static MeasuredRecoveryPhase from(SessionRecoveryRecord record) {
        var state=record.state();
        if((state!=SessionState.MEASURED && state!=SessionState.COLLAPSING)
                || !(record.candidateSelection().orElse(null) instanceof dev.quantumchamber.candidate.CandidateSelection.Selected))
            throw new IllegalArgumentException("只有 MEASURED 或 COLLAPSING（SELECTED）可推導保留式返還與清理階段");
        if(record.participants().stream().anyMatch(person -> !person.returned())) return RETURN_PLAYERS;
        if(!record.spaceLeases().isEmpty()) return RELEASE_GEOMETRY;
        return state==SessionState.MEASURED ? DORMANT : SETTLED;
    }

    /**
     * record 推導出的返還與清理都已完成（DORMANT 或 SETTLED）。這只是 flushed record 的推導，不代表走廊 space、slot、保護範圍與 chunk ticket
     * 已經釋放：清 lease 的寫出失敗之後，record 可能先變成 SETTLED，space 要到下一個走廊 tick 才釋放。判斷清理是否真的完成，要用
     * {@code CorridorPageManager.measuredRecoveryComplete}（或 {@code SessionRecoveryManager.measuredRecoveryComplete}）。
     */
    public boolean released() { return this==DORMANT || this==SETTLED; }
}
