package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class DimensionRoleTest {
    @Test
    void recognizesOnlyTheThreeVanillaWorldKeys() {
        assertEquals(Optional.of(DimensionRole.OVERWORLD), DimensionRole.fromVanillaKey(World.OVERWORLD));
        assertEquals(Optional.of(DimensionRole.NETHER), DimensionRole.fromVanillaKey(World.NETHER));
        assertEquals(Optional.of(DimensionRole.END), DimensionRole.fromVanillaKey(World.END));
        assertEquals(Optional.empty(), DimensionRole.fromVanillaKey(
                RegistryKey.of(RegistryKeys.WORLD, Identifier.of("test", "unknown"))));
    }
}
