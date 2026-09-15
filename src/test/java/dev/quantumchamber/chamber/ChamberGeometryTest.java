package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberGeometryTest {
    @Test
    void northFacingFrameHasLockedSevenCubeAndTwentyFiveBulkheadCells() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        Set<BlockPos> bulkhead = ChamberGeometry.bulkheadPositions(frame);
        Set<BlockPos> expectedBulkhead = Set.of(
                new BlockPos(2, -5, 0), new BlockPos(1, -5, 0), new BlockPos(0, -5, 0), new BlockPos(-1, -5, 0), new BlockPos(-2, -5, 0),
                new BlockPos(2, -4, 0), new BlockPos(1, -4, 0), new BlockPos(0, -4, 0), new BlockPos(-1, -4, 0), new BlockPos(-2, -4, 0),
                new BlockPos(2, -3, 0), new BlockPos(1, -3, 0), new BlockPos(0, -3, 0), new BlockPos(-1, -3, 0), new BlockPos(-2, -3, 0),
                new BlockPos(2, -2, 0), new BlockPos(1, -2, 0), new BlockPos(0, -2, 0), new BlockPos(-1, -2, 0), new BlockPos(-2, -2, 0),
                new BlockPos(2, -1, 0), new BlockPos(1, -1, 0), new BlockPos(0, -1, 0), new BlockPos(-1, -1, 0), new BlockPos(-2, -1, 0));

        assertEquals(new BlockBox(-3, -6, 0, 3, 0, 6), ChamberGeometry.bounds(frame));
        assertEquals(expectedBulkhead, bulkhead);
        assertTrue(bulkhead.contains(new BlockPos(2, -5, 0)));
        assertTrue(bulkhead.contains(new BlockPos(-2, -1, 0)));
        assertFalse(bulkhead.contains(new BlockPos(2, -5, 1)));
        assertThrows(UnsupportedOperationException.class, () -> bulkhead.add(new BlockPos(99, 99, 99)));
        assertEquals(BlockPos.ORIGIN, ChamberGeometry.localToWorld(frame, 3, 6, 0));
        assertEquals(new BlockPos(0, -5, 1), ChamberGeometry.localToWorld(frame, 3, 1, 1));
    }

    @Test
    void everyHorizontalFacingKeepsControllerAndVolumeStable() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame frame = new ChamberFrame(new BlockPos(20, 80, -40), facing);
            BlockBox bounds = ChamberGeometry.bounds(frame);
            Box interior = ChamberGeometry.interiorBox(frame);
            BlockPos expectedOffsetPosition = switch (facing) {
                case NORTH -> new BlockPos(22, 76, -36);
                case EAST -> new BlockPos(16, 76, -38);
                case SOUTH -> new BlockPos(18, 76, -44);
                case WEST -> new BlockPos(24, 76, -42);
                default -> throw new AssertionError("Expected a horizontal direction");
            };

            assertEquals(frame.controllerPos(), ChamberGeometry.localToWorld(frame, 3, 6, 0));
            assertEquals(expectedOffsetPosition, ChamberGeometry.localToWorld(frame, 1, 2, 4));
            assertEquals(7, bounds.getBlockCountX());
            assertEquals(7, bounds.getBlockCountY());
            assertEquals(7, bounds.getBlockCountZ());
            assertEquals(5.0, interior.getLengthX());
            assertEquals(5.0, interior.getLengthY());
            assertEquals(5.0, interior.getLengthZ());
        }
    }
}
