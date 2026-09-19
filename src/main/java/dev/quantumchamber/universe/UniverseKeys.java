package dev.quantumchamber.universe;

import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class UniverseKeys {
    private UniverseKeys() {
    }

    public static RegistryKey<World> world(UniverseId universeId, DimensionRole role) {
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(role, "role");
        if (role != DimensionRole.OVERWORLD) {
            throw new IllegalArgumentException("schema 1 只支援 OVERWORLD world key");
        }
        String path = "universe/" + universeId.value() + "/overworld";
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of("quantumchamber", path));
    }
}
