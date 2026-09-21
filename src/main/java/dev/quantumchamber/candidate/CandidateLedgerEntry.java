package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import java.util.Objects;

/** 已揭露門位與候選的不可變配對。 */
public record CandidateLedgerEntry(DoorKey doorKey, QuantumCandidate candidate) {
    public CandidateLedgerEntry {
        Objects.requireNonNull(doorKey, "doorKey");
        Objects.requireNonNull(candidate, "candidate");
    }
}
