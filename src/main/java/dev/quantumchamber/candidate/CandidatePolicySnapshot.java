package dev.quantumchamber.candidate;

import java.util.Objects;
import net.minecraft.util.Identifier;

/** 凍結的 M4 或 M6 policy；各版本的容量、權重與能力不得互換。 */
public record CandidatePolicySnapshot(Identifier policyId, int policyVersion, int candidateSchemaVersion,
        int sourceWeight, int discoveredWeight, int newWeight, long discoveryWatermark,
        int maxCandidateEntries, CandidateBytes entropyFingerprint, SourceFamilyRef sourceFamilyRef,
        int familyCapabilityVersion) {
    public CandidatePolicySnapshot(Identifier policyId, int policyVersion, int candidateSchemaVersion,
            int sourceWeight, int discoveredWeight, int newWeight, long discoveryWatermark,
            int maxCandidateEntries, CandidateBytes entropyFingerprint, SourceFamilyRef sourceFamilyRef) {
        this(policyId, policyVersion, candidateSchemaVersion, sourceWeight, discoveredWeight, newWeight,
                discoveryWatermark, maxCandidateEntries, entropyFingerprint, sourceFamilyRef, 0);
    }
    public CandidatePolicySnapshot {
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(entropyFingerprint, "entropyFingerprint");
        Objects.requireNonNull(sourceFamilyRef, "sourceFamilyRef");
        boolean legacy = Identifier.of("quantumchamber:m4_v1").equals(policyId)
                && candidateSchemaVersion == 1 && maxCandidateEntries == 16384;
        boolean m6 = Identifier.of("quantumchamber:m6_profiles_v1").equals(policyId)
                && candidateSchemaVersion == 2 && maxCandidateEntries == 4096;
        if ((!legacy && !m6) || (legacy ? policyVersion != 1 : policyVersion != 1 && policyVersion != 2 && policyVersion != 3) || familyCapabilityVersion != 0
                || sourceWeight != 5 || discoveredWeight != 20 || newWeight != 75 || discoveryWatermark < -1) {
            throw new IllegalArgumentException("不支援的 candidate policy snapshot");
        }
    }
    public boolean isM6() { return candidateSchemaVersion == 2; }
}
