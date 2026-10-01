package dev.quantumchamber.persistence;

import dev.quantumchamber.superposition.SessionState;

/**
 * 保留收據的返還與清理階段，由 receipt 推導、不另存（spec §7.3 的 RETURN_PLAYERS、RELEASE_GEOMETRY）。M4 的 MEASURED 與 M5 的
 * COLLAPSING 共用同一份推導：有人尚未返還為 RETURN_PLAYERS；全員已返還但仍有 lease 為 RELEASE_GEOMETRY；全員已返還、lease 已清空時，
 * MEASURED 是 DORMANT（M4 的保留收據），COLLAPSING 是 SETTLED（返還與清理已完成；之後的 RESOLVE／WAITING／PREPARE 由塌縮流程推導）。
 */
public enum MeasuredRecoveryPhase {
    RETURN_PLAYERS, RELEASE_GEOMETRY, DORMANT, SETTLED;

    public static MeasuredRecoveryPhase from(SessionRecoveryRecord record) {
        var state=record.state();
        if((state!=SessionState.MEASURED && state!=SessionState.COLLAPSING)
                || !(record.candidateSelection().orElse(null) instanceof dev.quantumchamber.candidate.CandidateSelection.Selected))
            throw new IllegalArgumentException("只有 MEASURED 或 COLLAPSING（SELECTED）可推導保留式返還與清理階段");
        if(record.participants().stream().anyMatch(person -> !person.returned())) return RETURN_PLAYERS;
        if(!record.spaceLeases().isEmpty()) return RELEASE_GEOMETRY;
        return state==SessionState.MEASURED ? DORMANT : SETTLED;
    }

    /** 返還與清理都已完成（DORMANT 或 SETTLED）：不再需要走廊 space、lease 與返還。 */
    public boolean released() { return this==DORMANT || this==SETTLED; }
}
