package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.GenerationSettingsSnapshot;
import dev.quantumchamber.universe.GeneratorProfile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/** 明確分開舊四欄與 M6 九欄意圖；snapshot 本身亦為不可變值。 */
public final class NewUniverseIntent {
    public static final String RANDOM_PROFILE_ID = "quantumchamber:random_vanilla_v1";
    private final CandidateBytes allocationToken;
    private final CandidateBytes generationSeedMaterial;
    private final int profileVersion;
    private final M6Fields m6;
    private record M6Fields(int policyVersion, String profileId, long sourceRootWorldSeed,
            GenerationSettingsSnapshot source, GenerationSettingsSnapshot target, int capability) {}

    public NewUniverseIntent(CandidateBytes allocationToken, GeneratorProfile profile,
            int profileVersion, CandidateBytes generationSeedMaterial) {
        this.allocationToken = Objects.requireNonNull(allocationToken, "allocationToken");
        Objects.requireNonNull(profile, "profile");
        this.generationSeedMaterial = Objects.requireNonNull(generationSeedMaterial, "generationSeedMaterial");
        if (profile != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1 || profileVersion != 1)
            throw new IllegalArgumentException("M4 NEW 僅支援 vanilla overworld shared seed profile v1");
        this.profileVersion = profileVersion; this.m6 = null;
    }

    public NewUniverseIntent(CandidateBytes token, CandidateBytes material, int policyVersion, String profileId,
            int profileVersion, long sourceSeed, GenerationSettingsSnapshot source,
            GenerationSettingsSnapshot target, int capability) {
        allocationToken = Objects.requireNonNull(token, "allocationToken");
        generationSeedMaterial = Objects.requireNonNull(material, "generationSeedMaterial");
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(target, "target");
        if (policyVersion != 1 || profileVersion != 1 || capability != 0
                || !selectedProfileId(policyVersion, material).equals(profileId))
            throw new IllegalArgumentException("不支援的 M6 policy/profile/capability");
        validateBuiltin(source); validateBuiltin(target);
        if (!target.providerId().equals("quantumchamber:vanilla_normal_v1")
                || source.parameters().getByte("GenerateStructures") != target.parameters().getByte("GenerateStructures"))
            throw new IllegalArgumentException("random 目標必須 normal 且保留來源 GenerateStructures");
        this.profileVersion = profileVersion;
        m6 = new M6Fields(policyVersion, profileId, sourceSeed, source, target, capability);
    }

    private static void validateBuiltin(GenerationSettingsSnapshot snapshot) {
        var parameters = snapshot.parameters();
        GenerationSettingsSnapshot.validateBuiltinParameters(parameters);
        String expected = "quantumchamber:vanilla_" + parameters.getString("OverworldPreset") + "_v1";
        if (snapshot.providerVersion() != 1 || !GenerationSettingsSnapshot.isBuiltin(snapshot.providerId())
                || !expected.equals(snapshot.providerId()))
            throw new IllegalArgumentException("M6 來源／目標設定不屬於受支援內建 provider");
    }

    /** 固定 domain 與明確版本；Task 9 只允 policy1 的單一 random 策略。 */
    public static String selectedProfileId(int policyVersion, CandidateBytes material) {
        if (policyVersion != 1) throw new IllegalArgumentException("尚未支援的 profile policy");
        var hash = sha256();
        hash.update("quantumchamber:m6:profile-choice:v1".getBytes(StandardCharsets.UTF_8));
        hash.update(ByteBuffer.allocate(4).putInt(policyVersion).array());
        hash.update(material.bytes());
        long choice = ByteBuffer.wrap(hash.digest()).getLong();
        return new String[] {RANDOM_PROFILE_ID}[(int) Long.remainderUnsigned(choice, 1)];
    }

    public boolean isM6() { return m6 != null; }
    public CandidateBytes allocationToken() { return allocationToken; }
    public CandidateBytes generationSeedMaterial() { return generationSeedMaterial; }
    public int profileVersion() { return profileVersion; }
    public GeneratorProfile profile() {
        if (isM6()) throw new IllegalStateException("M6 intent 不可當作 legacy GeneratorProfile");
        return GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1;
    }
    private M6Fields requireM6() {
        if (m6 == null) throw new IllegalStateException("legacy intent 沒有 M6 欄位");
        return m6;
    }
    public int policyVersion() { return requireM6().policyVersion(); }
    public String profileId() { return requireM6().profileId(); }
    public int familyCapabilityVersion() { return requireM6().capability(); }
    public long sourceRootWorldSeed() { return requireM6().sourceRootWorldSeed(); }
    public GenerationSettingsSnapshot sourceSettingsSnapshot() { return requireM6().source(); }
    public GenerationSettingsSnapshot targetSettingsSnapshot() { return requireM6().target(); }
    public long rootWorldSeed() {
        var fields = requireM6();
        var hash = sha256();
        hash.update("quantumchamber:m6:root-seed:v1".getBytes(StandardCharsets.UTF_8));
        hash.update(generationSeedMaterial.bytes()); hash.update(allocationToken.bytes());
        long result = ByteBuffer.wrap(hash.digest()).getLong();
        return result == fields.sourceRootWorldSeed() ? result ^ 0x9e3779b97f4a7c15L : result;
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    @Override public boolean equals(Object other) {
        if (!(other instanceof NewUniverseIntent value) || !allocationToken.equals(value.allocationToken)
                || !generationSeedMaterial.equals(value.generationSeedMaterial) || profileVersion != value.profileVersion
                || isM6() != value.isM6()) return false;
        return m6 == null || m6.policyVersion() == value.m6.policyVersion()
                && m6.profileId().equals(value.m6.profileId()) && m6.capability() == value.m6.capability()
                && m6.sourceRootWorldSeed() == value.m6.sourceRootWorldSeed()
                && m6.source().toNbt().equals(value.m6.source().toNbt())
                && m6.target().toNbt().equals(value.m6.target().toNbt());
    }
    @Override public int hashCode() {
        return Objects.hash(allocationToken, generationSeedMaterial, profileVersion,
                m6 == null ? null : m6.policyVersion(), m6 == null ? null : m6.profileId(),
                m6 == null ? null : m6.sourceRootWorldSeed(), m6 == null ? null : m6.capability(),
                m6 == null ? null : m6.source().toNbt(), m6 == null ? null : m6.target().toNbt());
    }
    @Override public String toString() { return "NewUniverseIntent[" + (isM6() ? m6.profileId() : "legacy") + "]"; }
}
