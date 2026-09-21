package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.persistence.SessionSemantics;
import java.util.List;
import java.util.UUID;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class DoorKeyTest {
    private static final UUID SESSION = UUID.fromString("a60c533e-5cb9-4ce4-9ec3-96ca26a2fbd5");

    @Test void fromBlockUsesFloorDivisionAtNegativeAndPositiveStationBoundaries() {
        assertEquals(-2, DoorKey.fromBlock(SESSION, -9, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
        assertEquals(-1, DoorKey.fromBlock(SESSION, -8, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
        assertEquals(-1, DoorKey.fromBlock(SESSION, -1, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
        assertEquals(0, DoorKey.fromBlock(SESSION, 0, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
        assertEquals(0, DoorKey.fromBlock(SESSION, 7, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
        assertEquals(1, DoorKey.fromBlock(SESSION, 8, DoorKey.DoorWallSide.NEGATIVE_LATERAL).logicalStationIndex());
    }

    @Test void sideDoorKeyNormalizesEveryDoorBlockAcrossFacingsMappingsAndSemantics() {
        for (var facing : new Direction[] {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST}) {
            for (var semantics : SessionSemantics.values()) {
                var first = mapping(17, 3, 96, facing);
                var second = mapping(42, 9, -192, facing);
                for (var wallSide : DoorKey.DoorWallSide.values()) {
                    var expected = new DoorKey(SESSION, -2, wallSide);
                    int x = wallSide == DoorKey.DoorWallSide.NEGATIVE_LATERAL ? 0 : 6;
                    for (int logicalZ = -15; logicalZ <= -11; logicalZ++) {
                        for (int y = 1; y <= 5; y++) {
                            assertEquals(expected, CorridorGeometry.sideDoorKey(SESSION, first, semantics,
                                    physical(first, x, y, logicalZ)) .orElseThrow());
                            assertEquals(expected, CorridorGeometry.sideDoorKey(SESSION, second, semantics,
                                    physical(second, x, y, logicalZ)) .orElseThrow());
                        }
                    }
                }
            }
        }
    }

    @Test void sideDoorKeyRejectsFramesFloorsCeilingsEntranceWallsAndDoorExteriors() {
        var view = mapping(1, 4, 0, Direction.NORTH);
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 0, 0, 1)).isEmpty());
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 0, 6, 1)).isEmpty());
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 3, 3, 0)).isEmpty());
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 0, 3, 0)).isEmpty());
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 0, 3, 6)).isEmpty());
        assertTrue(CorridorGeometry.sideDoorKey(SESSION, view, SessionSemantics.LEGACY_FORWARD_CONSUMED,
                physical(view, 1, 3, 1)).isEmpty());
    }

    @Test void sideDoorCellsListsAllTwentyFiveCanonicalLogicalCells() {
        var expected = List.of(
                new LogicalAddress(-1, 89), new LogicalAddress(-1, 89), new LogicalAddress(-1, 89), new LogicalAddress(-1, 89), new LogicalAddress(-1, 89),
                new LogicalAddress(-1, 90), new LogicalAddress(-1, 90), new LogicalAddress(-1, 90), new LogicalAddress(-1, 90), new LogicalAddress(-1, 90),
                new LogicalAddress(-1, 91), new LogicalAddress(-1, 91), new LogicalAddress(-1, 91), new LogicalAddress(-1, 91), new LogicalAddress(-1, 91),
                new LogicalAddress(-1, 92), new LogicalAddress(-1, 92), new LogicalAddress(-1, 92), new LogicalAddress(-1, 92), new LogicalAddress(-1, 92),
                new LogicalAddress(-1, 93), new LogicalAddress(-1, 93), new LogicalAddress(-1, 93), new LogicalAddress(-1, 93), new LogicalAddress(-1, 93));
        assertEquals(expected, CorridorGeometry.sideDoorCells(-1, DoorKey.DoorWallSide.NEGATIVE_LATERAL));
        assertEquals(expected, CorridorGeometry.sideDoorCells(-1, DoorKey.DoorWallSide.POSITIVE_LATERAL));
    }

    @Test void doorKeyRejectsMissingIdentityParts() {
        assertThrows(NullPointerException.class, () -> new DoorKey(null, 0, DoorKey.DoorWallSide.NEGATIVE_LATERAL));
        assertThrows(NullPointerException.class, () -> new DoorKey(SESSION, 0, null));
    }

    private static CorridorPageManager.MappingView mapping(long epoch, int slot, long anchor, Direction facing) {
        return new CorridorPageManager.MappingView(new CorridorPageManager.MappingRef(SESSION, epoch, slot), -1, 1,
                -672, 768, anchor, new BlockPos(1000 + slot * 100, 64, 2000 + slot * 100), facing,
                new BlockBox(-10_000, 0, -10_000, 10_000, 255, 10_000));
    }

    private static BlockPos physical(CorridorPageManager.MappingView view, int x, int y, long logicalZ) {
        return CorridorGeometry.block(CorridorGeometry.frame(view), x, y, Math.toIntExact(logicalZ - view.logicalAnchorBlock()));
    }
}
