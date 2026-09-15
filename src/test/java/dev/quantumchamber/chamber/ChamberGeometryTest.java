package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberGeometryTest {
    @Test
    void northFacingFrameHasLockedSevenCubeAndTwentyFiveBulkheadCells() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);

        assertEquals(new BlockBox(-3, -6, 0, 3, 0, 6), ChamberGeometry.bounds(frame));
        assertEquals(25, ChamberGeometry.bulkheadPositions(frame).size());
        assertEquals(BlockPos.ORIGIN, ChamberGeometry.localToWorld(frame, 3, 6, 0));
        assertEquals(new BlockPos(0, -5, 1), ChamberGeometry.localToWorld(frame, 3, 1, 1));
    }

    @Test
    void everyHorizontalFacingKeepsControllerAndVolumeStable() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame frame = new ChamberFrame(new BlockPos(20, 80, -40), facing);
            BlockBox bounds = ChamberGeometry.bounds(frame);
            Box interior = ChamberGeometry.interiorBox(frame);

            assertEquals(frame.controllerPos(), ChamberGeometry.localToWorld(frame, 3, 6, 0));
            assertEquals(7, bounds.getBlockCountX());
            assertEquals(7, bounds.getBlockCountY());
            assertEquals(7, bounds.getBlockCountZ());
            assertEquals(5.0, interior.getLengthX());
            assertEquals(5.0, interior.getLengthY());
            assertEquals(5.0, interior.getLengthZ());
        }
    }
}
