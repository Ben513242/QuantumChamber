package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

class ChamberOccupantBoundsTest {
    private static final Box INTERIOR = new Box(10, 20, 30, 15, 25, 35);

    @Test
    void includesPlayerWhoseEntireBoundingBoxIsInsideInterior() {
        assertTrue(ChamberOccupantService.isParticipant(INTERIOR, new Box(10.2, 20.0, 30.4, 10.8, 21.8, 31.0), false));
    }

    @Test
    void excludesPlayerWhoseBoxOnlyIntersectsInterior() {
        assertFalse(ChamberOccupantService.isParticipant(INTERIOR, new Box(9.8, 20.0, 30.4, 10.4, 21.8, 31.0), false));
    }

    @Test
    void excludesPlayerWhoseBoxCrossesInteriorBoundary() {
        assertFalse(ChamberOccupantService.isParticipant(INTERIOR, new Box(14.4, 20.0, 30.4, 15.1, 21.8, 31.0), false));
    }

    @Test
    void excludesSpectatorEvenWhenItsBoxIsInsideInterior() {
        assertFalse(ChamberOccupantService.isParticipant(INTERIOR, new Box(10.2, 20.0, 30.4, 10.8, 21.8, 31.0), true));
    }
}
