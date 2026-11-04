package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.serialization.Lifecycle;
import java.util.stream.Stream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.SimpleRegistry;
import net.minecraft.world.dimension.DimensionOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Minecraft121VanillaPresetProfileProviderTest {
    @BeforeAll static void bootstrap() { Minecraft121WorldgenRegistryFixture.bootstrap(); }

    private static NbtCompound params(String preset) {
        var value = new NbtCompound(); value.putString("OverworldPreset", preset);
        value.putByte("GenerateStructures", (byte)1); return value;
    }
    private record Lease(DynamicRegistryManager.Immutable registries) implements ServerSettingsContext {
        @Override public void requireValid() {}
        @Override public boolean sourceGenerateStructures() { return true; }
    }
    private static DynamicRegistryManager.Immutable withOverworld(DynamicRegistryManager.Immutable base, DimensionOptions overworld) {
        var dimension = new SimpleRegistry<>(RegistryKeys.DIMENSION, Lifecycle.stable());
        Registry.register(dimension, DimensionOptions.OVERWORLD, overworld);
        dimension.freeze();
        return new DynamicRegistryManager.ImmutableImpl(Stream.concat(base.streamAllRegistries(),
                Stream.of(new DynamicRegistryManager.Entry<>(RegistryKeys.DIMENSION, dimension)))).toImmutable();
    }

    @Test void builtinProviderRejectsPresetMismatchWithId() {
        var normal = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID);
        var amplified = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID);
        assertEquals(1, normal.version()); assertEquals(1, amplified.version());
        assertEquals(Minecraft121VanillaPresetProfileProvider.CAPABILITY, normal.adapterCapabilities());
        assertEquals(Minecraft121VanillaPresetProfileProvider.CAPABILITY, amplified.adapterCapabilities());
        assertThrows(IllegalArgumentException.class, () -> normal.parameterSchema().validate(params("amplified")));
        assertThrows(IllegalArgumentException.class, () -> amplified.parameterSchema().validate(params("normal")));
        assertDoesNotThrow(() -> normal.parameterSchema().validate(params("normal")));
        assertDoesNotThrow(() -> amplified.parameterSchema().validate(params("amplified")));
    }

    @Test void detectOverworldPresetMatchesOnlyExactVanillaNormalOrAmplified() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var normal = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID);
            var amplified = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID);
            var source = new Lease(f.registries());
            var normalOption = normal.resolveGenerationSettings(params("normal"), source).roles().get(DimensionRole.OVERWORLD);
            var amplifiedOption = amplified.resolveGenerationSettings(params("amplified"), source).roles().get(DimensionRole.OVERWORLD);
            var otherOption = normal.resolveGenerationSettings(params("normal"), source).roles().get(DimensionRole.NETHER);
            assertEquals("normal", Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(
                    new Lease(withOverworld(f.registries(), normalOption))).orElseThrow());
            assertEquals("amplified", Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(
                    new Lease(withOverworld(f.registries(), amplifiedOption))).orElseThrow());
            assertTrue(Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(
                    new Lease(withOverworld(f.registries(), otherOption))).isEmpty());
        }
    }
}
