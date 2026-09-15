package dev.quantumchamber.universe;

import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

public enum DimensionRole {
    OVERWORLD,
    NETHER,
    END;

    public static Optional<DimensionRole> fromVanillaKey(RegistryKey<World> worldKey) {
        if (World.OVERWORLD.equals(worldKey)) {
            return Optional.of(OVERWORLD);
        }
        if (World.NETHER.equals(worldKey)) {
            return Optional.of(NETHER);
        }
        if (World.END.equals(worldKey)) {
            return Optional.of(END);
        }
        return Optional.empty();
    }
}
