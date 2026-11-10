package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseProfileRegistry;
import dev.quantumchamber.universe.ServerSettingsContext;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** 純候選解析，不配置 Universe、不物化世界，也不處理 gameplay。 */
public final class CandidateResolver {
    private final CandidateDerivation derivation;
    private final CandidateBytes fingerprint;
    private final UniverseProfileRegistry registry;
    private final Supplier<ServerSettingsContext> acquire;

    /** 僅限一次候選 operation 的 resolver；不儲存 lease，正式 caller 須注入 exact server 的 acquire。 */
    public CandidateResolver(CandidateEntropyState entropy, UniverseProfileRegistry registry,
            Supplier<ServerSettingsContext> acquire) {
        Objects.requireNonNull(entropy, "entropy");
        derivation = new CandidateDerivation(entropy);
        fingerprint = entropy.entropyFingerprint();
        this.registry = Objects.requireNonNull(registry, "registry");
        this.acquire = Objects.requireNonNull(acquire, "acquire");
    }

    public CandidateResolver(CandidateEntropyState entropy) {
        Objects.requireNonNull(entropy, "entropy");
        derivation = new CandidateDerivation(entropy);
        fingerprint = entropy.entropyFingerprint();
        registry = null; acquire = null;
    }

    public QuantumCandidate resolve(DoorKey doorKey, CandidatePolicySnapshot policy,
            List<UniverseDiscoveryRecord> eligiblePool) {
        return resolve(doorKey, policy, eligiblePool, () -> { throw new IllegalStateException("M6 來源尚未接線"); });
    }

    public QuantumCandidate resolve(DoorKey doorKey, CandidatePolicySnapshot policy,
            List<UniverseDiscoveryRecord> eligiblePool, Supplier<SourceGenerationSnapshot> sourceSupplier) {
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
        var token = derivation.derive(DerivationDomain.ALLOCATION_TOKEN, doorKey, schema);
        var material = derivation.derive(DerivationDomain.GENERATION_SEED_MATERIAL, doorKey, schema);
        if (!policy.isM6()) return new QuantumCandidate.New(id, new NewUniverseIntent(token,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, material));
        if (registry == null || acquire == null) throw new IllegalStateException("M6 context 尚未接線");
        SourceGenerationSnapshot source;
        try {
            source = Objects.requireNonNull(sourceSupplier.get(), "source");
        } catch (RuntimeException failure) {
            // 保留拒絕原因與候選識別，供營運定位；不輸出 entropy、seed 或衍生 material。
            throw new IllegalStateException("NEW_BUCKET bucket=" + bucket + " door=" + doorKey + " candidateIdSha256=" + candidateFingerprint(id)
                    + "：" + failure.getMessage(), failure);
        }
        var lease = Objects.requireNonNull(acquire.get(), "lease");
        lease.requireValid();
        registry.resolve(source.settings(), lease);
        var profile = NewUniverseIntent.selectedProfileId(policy.policyVersion(), material);
        var target = source.settings();
        if (NewUniverseIntent.RANDOM_PROFILE_ID.equals(profile)) {
            var parameters = source.settings().parameters();
            parameters.putString("OverworldPreset", "normal");
            target = registry.createSnapshot("quantumchamber:vanilla_normal_v1", 1, parameters, lease);
        }
        if (NewUniverseIntent.TOGGLE_AMPLIFIED_PROFILE_ID.equals(profile)) {
            var parameters = source.settings().parameters();
            String preset = parameters.getString("OverworldPreset").equals("normal") ? "amplified" : "normal";
            parameters.putString("OverworldPreset", preset);
            target = registry.createSnapshot("quantumchamber:vanilla_" + preset + "_v1", 1, parameters, lease);
        }
        var intent = new NewUniverseIntent(token, material, policy.policyVersion(),
                profile, 1,
                source.rootSeed(), source.settings(), target, policy.familyCapabilityVersion());
        lease.requireValid();
        return new QuantumCandidate.New(id, intent);
    }

    private static String candidateFingerprint(CandidateId id) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.value().bytes()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
