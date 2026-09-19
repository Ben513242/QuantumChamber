package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UniverseKeysTest {
    @Test
    void worldKeyIsStableAndRoleQualified() {
        var id = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-000000000123"));

        assertEquals(
                "quantumchamber:universe/00000000-0000-0000-0000-000000000123/overworld",
                UniverseKeys.world(id, DimensionRole.OVERWORLD).getValue().toString());
    }
}
