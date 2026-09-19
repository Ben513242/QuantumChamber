package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UniverseRegistryTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test
    void allocationIsIdempotentOnlyForTheSameDefinition() {
        var registry = new UniverseRegistry();

        var first = registry.allocateOverworld(ID, 7);

        assertSame(first, registry.allocateOverworld(ID, 7));
        assertThrows(IllegalArgumentException.class, () -> registry.allocateOverworld(ID, 8));
    }

    @Test
    void exactWorldLookupAndAvailabilityChangePreserveDefinition() {
        var registry = new UniverseRegistry();
        var original = registry.allocateOverworld(ID, 7);

        assertSame(original, registry.find(UniverseId.of(ID)).orElseThrow());
        assertSame(original, registry.findByWorldKey(
                UniverseKeys.world(UniverseId.of(ID), DimensionRole.OVERWORLD)).orElseThrow());

        var disabled = registry.changeAvailability(UniverseId.of(ID), DesiredAvailability.DISABLED);

        assertSame(original.definition(), disabled.definition());
        assertEquals(DesiredAvailability.DISABLED, disabled.desiredAvailability());
        assertTrue(registry.findByWorldKey(
                UniverseKeys.world(UniverseId.of(UUID.fromString(
                        "00000000-0000-0000-0000-000000000002")), DimensionRole.OVERWORLD)).isEmpty());
    }
}
