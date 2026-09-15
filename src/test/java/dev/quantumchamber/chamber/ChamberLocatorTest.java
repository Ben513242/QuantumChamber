package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberLocatorTest {
    @Test
    void locatesFrameFromAnyApertureCellInEveryOrientation() {
        ChamberLocator locator = new ChamberLocator(new ChamberDetector());
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame expected = new ChamberFrame(new BlockPos(32, 70, -48), facing);
            Map<BlockPos, ChamberCell> cells = Fixture.validFrame(expected, false);
            BlockPos clickedDoor = Fixture.world(expected, 5, 4, 0);

            Optional<ChamberFrame> found = locator.findFrame(new Fixture.MapView(cells), clickedDoor);
            assertTrue(found.isPresent(), () -> "not found for " + facing);
            assertEquals(expected, found.orElseThrow());
        }
    }

    @Test
    void doesNotReturnFrameForInvalidNearbyBulkhead() {
        ChamberLocator locator = new ChamberLocator(new ChamberDetector());
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        Map<BlockPos, ChamberCell> cells = Fixture.validFrame(frame, false);
        cells.put(Fixture.world(frame, 0, 3, 3), ChamberCell.OTHER);

        assertTrue(locator.findFrame(new Fixture.MapView(cells), Fixture.world(frame, 3, 3, 0)).isEmpty());
    }

    static final class Fixture {
        private Fixture() {}

        static Map<BlockPos, ChamberCell> validFrame(ChamberFrame frame, boolean open) {
            Map<BlockPos, ChamberCell> cells = new HashMap<>();
            for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
                ChamberCell cell = (x == 3 && y == 6 && z == 0) ? ChamberCell.CONTROLLER
                        : (z == 0 && x >= 1 && x <= 5 && y >= 1 && y <= 5) ? (open ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED)
                        : (x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6) ? ChamberCell.BEDROCK : ChamberCell.OTHER;
                cells.put(world(frame, x, y, z), cell);
            }
            return cells;
        }

        static BlockPos world(ChamberFrame frame, int x, int y, int z) {
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

        record MapView(Map<BlockPos, ChamberCell> cells) implements ChamberBlockView {
            @Override public ChamberCell cellAt(BlockPos pos) { return cells.getOrDefault(pos, ChamberCell.OTHER); }
        }
    }
}
