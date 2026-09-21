package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.universe.GeneratorProfile;
import java.util.List;
import java.util.Objects;

/** 純候選解析，不配置 Universe、不物化世界，也不處理 gameplay。 */
public final class CandidateResolver {
    private final CandidateDerivation derivation;
    private final CandidateBytes fingerprint;

    public CandidateResolver(CandidateEntropyState entropy) {
        Objects.requireNonNull(entropy, "entropy");
        derivation = new CandidateDerivation(entropy);
        fingerprint = entropy.entropyFingerprint();
    }

    public QuantumCandidate resolve(DoorKey doorKey, CandidatePolicySnapshot policy,
            List<UniverseDiscoveryRecord> eligiblePool) {
        Objects.requireNonNull(doorKey, "doorKey");
        Objects.requireNonNull(policy, "policy");
        if (!fingerprint.equals(policy.entropyFingerprint())) {
            throw new IllegalArgumentException("Candidate policy entropy fingerprint 不符");
        }
        var pool = UniverseDiscoveryState.canonicalRecords(eligiblePool);
        for (var record : pool) {
            if (!record.gameplayEligible() || record.discoveryOrdinal() > policy.discoveryWatermark()
                    || policy.sourceFamilyRef() instanceof SourceFamilyRef.Catalog source
                    && source.universeId().equals(record.universeId())) {
                throw new IllegalArgumentException("Candidate pool 含不合格 discovery record");
            }
        }
        int schema = policy.candidateSchemaVersion();
        var id = new CandidateId(derivation.derive(DerivationDomain.CANDIDATE_ID, doorKey, schema));
        int bucket = derivation.bucket(doorKey, schema);
        if (bucket < policy.sourceWeight()) return new QuantumCandidate.Source(id);
        if (bucket < policy.sourceWeight() + policy.discoveredWeight() && !pool.isEmpty()) {
            var record = pool.get(derivation.existingIndex(doorKey, schema, pool.size()));
            return new QuantumCandidate.Existing(id, record.universeId());
        }
        return new QuantumCandidate.New(id, new NewUniverseIntent(
                derivation.derive(DerivationDomain.ALLOCATION_TOKEN, doorKey, schema),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1,
                derivation.derive(DerivationDomain.GENERATION_SEED_MATERIAL, doorKey, schema)));
    }
}
