package dev.quantumchamber.candidate;

import java.util.Objects;
import net.minecraft.util.Identifier;

/** M4 v1 的完整凍結契約；不接受只有權重總和相同的替代 policy。 */
public record CandidatePolicySnapshot(Identifier policyId, int policyVersion, int candidateSchemaVersion,
        int sourceWeight, int discoveredWeight, int newWeight, long discoveryWatermark,
        int maxCandidateEntries, CandidateBytes entropyFingerprint, SourceFamilyRef sourceFamilyRef) {
    public CandidatePolicySnapshot {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(entropyFingerprint, "entropyFingerprint");
        Objects.requireNonNull(sourceFamilyRef, "sourceFamilyRef");
        if (!Identifier.of("quantumchamber:m4_v1").equals(policyId) || policyVersion != 1
                || candidateSchemaVersion != 1 || sourceWeight != 5 || discoveredWeight != 20
                || newWeight != 75 || discoveryWatermark < -1 || maxCandidateEntries != 16384) {
            throw new IllegalArgumentException("不支援的 M4 candidate policy snapshot");
        }
    }
}
