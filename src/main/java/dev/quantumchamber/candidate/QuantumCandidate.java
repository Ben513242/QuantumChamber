package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.UniverseId;
import java.util.Objects;

/** SOURCE 使用 session 的來源參照，不重複保存 UniverseId。 */
public sealed interface QuantumCandidate permits QuantumCandidate.Source, QuantumCandidate.Existing, QuantumCandidate.New {
    CandidateId candidateId();

    record Source(CandidateId candidateId) implements QuantumCandidate {
        public Source { Objects.requireNonNull(candidateId, "candidateId"); }
    }

    record Existing(CandidateId candidateId, UniverseId universeId) implements QuantumCandidate {
        public Existing {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(universeId, "universeId");
        }
    }

    record New(CandidateId candidateId, NewUniverseIntent intent) implements QuantumCandidate {
        public New {
            Objects.requireNonNull(candidateId, "candidateId");
            Objects.requireNonNull(intent, "intent");
        }
    }
}
