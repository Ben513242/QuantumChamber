package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.GeneratorProfile;
import java.util.Objects;

/** 未配置的 Universe 意圖；候選解析階段只保存 token 與 seed material。 */
public record NewUniverseIntent(CandidateBytes allocationToken, GeneratorProfile profile,
        int profileVersion, CandidateBytes generationSeedMaterial) {
    public NewUniverseIntent {
        Objects.requireNonNull(allocationToken, "allocationToken");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(generationSeedMaterial, "generationSeedMaterial");
        if (profile != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1 || profileVersion != 1) {
            throw new IllegalArgumentException("M4 NEW 僅支援 vanilla overworld shared seed profile v1");
        }
    }
}
