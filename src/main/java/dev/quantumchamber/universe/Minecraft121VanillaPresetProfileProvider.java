package dev.quantumchamber.universe;

import java.util.Map;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.MultiNoiseBiomeSource;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterList;
import net.minecraft.world.biome.source.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.biome.source.TheEndBiomeSource;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;

/** 1.21 原版 normal／amplified 的兩個 version 1 provider id。 */
public final class Minecraft121VanillaPresetProfileProvider implements UniverseProfileProvider {
    public static final String NORMAL_ID = "quantumchamber:vanilla_normal_v1";
    public static final String AMPLIFIED_ID = "quantumchamber:vanilla_amplified_v1";
    public static final String CAPABILITY = "quantumchamber:m6-settings-shallow-v1";
    private final String id;
    private final String preset;

    public Minecraft121VanillaPresetProfileProvider(String id) {
        if (!NORMAL_ID.equals(id) && !AMPLIFIED_ID.equals(id)) throw new IllegalArgumentException("UNKNOWN_BUILTIN_PROVIDER");
        this.id = id;
        this.preset = NORMAL_ID.equals(id) ? "normal" : "amplified";
    }

    @Override public String id() { return id; }
    @Override public int version() { return 1; }
    @Override public String adapterCapabilities() { return CAPABILITY; }
    @Override public ParameterSchema parameterSchema() { return this::validate; }

    private void validate(NbtCompound parameters) {
        GenerationSettingsSnapshot.validateBuiltinParameters(parameters);
        if (!preset.equals(parameters.getString("OverworldPreset"))) throw new IllegalArgumentException("PROVIDER_PRESET_MISMATCH");
    }

    @Override public ResolvedSettings resolveGenerationSettings(NbtCompound parameters, ServerSettingsContext lease) {
        validate(parameters);
        DynamicRegistryManager.Immutable registries = lease.registries();
        var roles = Map.of(
                DimensionRole.OVERWORLD, new DimensionOptions(
                        entry(registries, RegistryKeys.DIMENSION_TYPE, DimensionTypes.OVERWORLD),
                        new NoiseChunkGenerator(MultiNoiseBiomeSource.create(entry(registries,
                                RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                                MultiNoiseBiomeSourceParameterLists.OVERWORLD)),
                                entry(registries, RegistryKeys.CHUNK_GENERATOR_SETTINGS,
                                        "normal".equals(preset) ? ChunkGeneratorSettings.OVERWORLD : ChunkGeneratorSettings.AMPLIFIED))),
                DimensionRole.NETHER, new DimensionOptions(
                        entry(registries, RegistryKeys.DIMENSION_TYPE, DimensionTypes.THE_NETHER),
                        new NoiseChunkGenerator(MultiNoiseBiomeSource.create(entry(registries,
                                RegistryKeys.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST,
                                MultiNoiseBiomeSourceParameterLists.NETHER)),
                                entry(registries, RegistryKeys.CHUNK_GENERATOR_SETTINGS, ChunkGeneratorSettings.NETHER))),
                DimensionRole.END, new DimensionOptions(
                        entry(registries, RegistryKeys.DIMENSION_TYPE, DimensionTypes.THE_END),
                        new NoiseChunkGenerator(TheEndBiomeSource.createVanilla(registries.get(RegistryKeys.BIOME).getReadOnlyWrapper()),
                                entry(registries, RegistryKeys.CHUNK_GENERATOR_SETTINGS, ChunkGeneratorSettings.END))));
        lease.requireValid();
        return new ResolvedSettings(roles, parameters.getByte("GenerateStructures") == 1);
    }

    private static <T> RegistryEntry.Reference<T> entry(DynamicRegistryManager.Immutable registries,
            RegistryKey<? extends net.minecraft.registry.Registry<T>> registryKey, RegistryKey<T> key) {
        return registries.get(registryKey).getEntry(key).orElseThrow(() -> new IllegalStateException("MISSING_SETTINGS_ENTRY:" + key.getValue()));
    }

    /** 只在來源 Overworld 與兩個原版 shallow 設定逐 byte 相同時辨認。 */
    public static Optional<String> detectOverworldPreset(ServerSettingsContext lease) {
        lease.requireValid();
        var registries = lease.registries();
        var actual = registries.get(RegistryKeys.DIMENSION).get(DimensionOptions.OVERWORLD);
        if (actual == null) return Optional.empty();
        byte[] encoded;
        try {
            encoded = Minecraft121ShallowSettingsCanonicalizer.encodeRole(actual, lease);
        } catch (IllegalArgumentException error) {
            if (!"SHALLOW_INLINE_ELEMENT".equals(error.getMessage())) throw error;
            lease.requireValid();
            return Optional.empty();
        }
        for (String id : new String[] {NORMAL_ID, AMPLIFIED_ID}) {
            var parameters = new NbtCompound();
            parameters.putString("OverworldPreset", NORMAL_ID.equals(id) ? "normal" : "amplified");
            parameters.putByte("GenerateStructures", (byte)(lease.sourceGenerateStructures() ? 1 : 0));
            var expected = new Minecraft121VanillaPresetProfileProvider(id)
                    .resolveGenerationSettings(parameters, lease).roles().get(DimensionRole.OVERWORLD);
            if (java.util.Arrays.equals(encoded, Minecraft121ShallowSettingsCanonicalizer.encodeRole(expected, lease)))
                return Optional.of(NORMAL_ID.equals(id) ? "normal" : "amplified");
        }
        return Optional.empty();
    }
}
