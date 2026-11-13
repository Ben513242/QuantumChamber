package dev.quantumchamber.persistence;

import dev.quantumchamber.superposition.SessionState;
import java.util.List;
import java.util.UUID;

/** 熱 journal 每列共同持有的占用權威。 */
public sealed interface JournalSession permits SessionRecoveryRecord, HomeSessionRecord {
    UUID sessionUuid();
    UUID chamberUuid();
    List<SessionRecoveryRecord.Participant> participants();
    List<SessionRecoveryRecord.SpaceLease> spaceLeases();
    SessionState state();
}
