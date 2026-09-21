package dev.quantumchamber.candidate;

import java.util.Objects;

/** 候選識別只接受固定 32 bytes 的不可變值。 */
public record CandidateId(CandidateBytes value) {
    public CandidateId { Objects.requireNonNull(value, "value"); }
}
