package dev.quantumchamber.superposition;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public final class SuperpositionWorld {
    public static final RegistryKey<World> KEY = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("quantumchamber", "superposition"));
    private SuperpositionWorld() {}
}
