package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.GenerationSettingsSnapshot;
import java.util.Objects;

/** 候選 NEW 分支才擷取的來源 seed 與不可變設定。 */
public record SourceGenerationSnapshot(long rootSeed, GenerationSettingsSnapshot settings) {
    public SourceGenerationSnapshot { Objects.requireNonNull(settings, "settings"); }
}
