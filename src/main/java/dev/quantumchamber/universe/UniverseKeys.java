package dev.quantumchamber.universe;

import java.util.Locale;
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
        String path = "universe/" + universeId.value() + "/" + role.name().toLowerCase(Locale.ROOT);
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of("quantumchamber", path));
    }
}
