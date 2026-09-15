package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberDetectorTest {
    private final ChamberDetector detector = new ChamberDetector();

    @Test
    void validatesClosedFrameForEveryHorizontalOrientation() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame frame = new ChamberFrame(new BlockPos(40, 80, -20), facing);
            ChamberStructureResult result = detector.validate(new MapView(validFrame(frame, false)), frame);

            assertTrue(result.valid(), () -> "expected valid " + facing + ": " + result.failures());
            assertTrue(result.sealed());
            assertEquals(192, shellCount());
            assertEquals(25, apertureCount());
        }
    }

    @Test
    void acceptsOpenDoorAsValidButUnsealed() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        ChamberStructureResult result = detector.validate(new MapView(validFrame(frame, true)), frame);

        assertTrue(result.valid());
        assertFalse(result.sealed());
    }

    @Test
    void ignoresInteriorDecorationMaterial() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.EAST);
        Map<BlockPos, ChamberCell> cells = validFrame(frame, false);
        cells.put(world(frame, 3, 3, 3), ChamberCell.OTHER);

        assertTrue(detector.validate(new MapView(cells), frame).valid());
    }

    @Test
    void rejectsMissingShellCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.SOUTH);
        Map<BlockPos, ChamberCell> cells = validFrame(frame, false);
        cells.put(world(frame, 0, 3, 3), ChamberCell.OTHER);

        ChamberStructureResult result = detector.validate(new MapView(cells), frame);
        assertFalse(result.valid());
        assertTrue(result.failures().contains(ChamberStructureResult.Failure.SHELL_MISSING));
    }

    @Test
    void rejectsMissingDoorCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.WEST);
        Map<BlockPos, ChamberCell> cells = validFrame(frame, false);
        cells.put(world(frame, 1, 1, 0), ChamberCell.OTHER);

        ChamberStructureResult result = detector.validate(new MapView(cells), frame);
        assertFalse(result.valid());
        assertTrue(result.failures().contains(ChamberStructureResult.Failure.BULKHEAD_MISSING));
    }

    @Test
    void rejectsMixedBulkheadStates() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        Map<BlockPos, ChamberCell> cells = validFrame(frame, false);
        cells.put(world(frame, 1, 1, 0), ChamberCell.BULKHEAD_OPEN);

        ChamberStructureResult result = detector.validate(new MapView(cells), frame);
        assertFalse(result.valid());
        assertTrue(result.failures().contains(ChamberStructureResult.Failure.BULKHEAD_MIXED));
    }

    private static Map<BlockPos, ChamberCell> validFrame(ChamberFrame frame, boolean open) {
        Map<BlockPos, ChamberCell> cells = new HashMap<>();
        for (int x = 0; x < 7; x++) {
            for (int y = 0; y < 7; y++) {
                for (int z = 0; z < 7; z++) {
                    if (x == 3 && y == 6 && z == 0) {
                        cells.put(world(frame, x, y, z), ChamberCell.CONTROLLER);
                    } else if (z == 0 && x >= 1 && x <= 5 && y >= 1 && y <= 5) {
                        cells.put(world(frame, x, y, z), open ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED);
                    } else if (x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6) {
                        cells.put(world(frame, x, y, z), ChamberCell.BEDROCK);
                    }
                }
            }
        }
        return cells;
    }

    // Independent fixture transform; deliberately does not use ChamberGeometry.
    private static BlockPos world(ChamberFrame frame, int x, int y, int z) {
        int lateral = x - 3;
        int vertical = y - 6;
        return switch (frame.outwardFacing()) {
            case NORTH -> frame.controllerPos().add(-lateral, vertical, z);
            case EAST -> frame.controllerPos().add(-z, vertical, -lateral);
            case SOUTH -> frame.controllerPos().add(lateral, vertical, -z);
            case WEST -> frame.controllerPos().add(z, vertical, lateral);
            default -> throw new AssertionError("frame must be horizontal");
        };
    }

    private static int shellCount() {
        return 7 * 7 * 7 - 5 * 5 * 5 - 25 - 1;
    }

    private static int apertureCount() {
        return 5 * 5;
    }

    private record MapView(Map<BlockPos, ChamberCell> cells) implements ChamberBlockView {
        @Override
        public ChamberCell cellAt(BlockPos pos) {
            return cells.getOrDefault(pos, ChamberCell.OTHER);
        }
    }
}
