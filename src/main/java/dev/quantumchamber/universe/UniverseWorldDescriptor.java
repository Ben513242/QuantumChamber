package dev.quantumchamber.universe;

import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

public record UniverseWorldDescriptor(
        RegistryKey<World> worldKey,
        DimensionRole role,
        GeneratorProfile generatorProfile,
        SeedPolicy seedPolicy,
        int storagePolicyVersion) {
    public UniverseWorldDescriptor {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(generatorProfile, "generatorProfile");
        Objects.requireNonNull(seedPolicy, "seedPolicy");
        if (storagePolicyVersion < 1) {
            throw new IllegalArgumentException("storagePolicyVersion 必須為正數");
        }
    }
}
