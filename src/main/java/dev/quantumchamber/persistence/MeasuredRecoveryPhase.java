package dev.quantumchamber.persistence;

/** 恢復階段由既有 receipt 推導；RETAIN_RECEIPT 僅代表交易內的 checked 寫入。 */
public enum MeasuredRecoveryPhase {
    RETURN_PLAYERS, RELEASE_GEOMETRY, RETAIN_RECEIPT, DORMANT;

    public static MeasuredRecoveryPhase from(SessionRecoveryRecord record) {
        if(record.state()!=dev.quantumchamber.superposition.SessionState.MEASURED
                || !(record.candidateSelection().orElse(null) instanceof dev.quantumchamber.candidate.CandidateSelection.Selected))
            throw new IllegalArgumentException("只有 MEASURED + SELECTED 可進行保留式恢復");
        if(record.participants().stream().anyMatch(person -> !person.returned())) return RETURN_PLAYERS;
        return record.spaceLeases().isEmpty() ? DORMANT : RELEASE_GEOMETRY;
    }
}
