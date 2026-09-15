package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.Test;

class ChamberDoorServiceTest {
    @Test
    void togglesEveryListedDoorPosition() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> expected = northDoorPositions();
        InMemoryDoor door = new InMemoryDoor(expected, false, 0);

        assertTrue(new ChamberDoorService().toggle(door, door, ChamberMutationExecutor.DIRECT, frame));
        assertTrue(expected.stream().allMatch(door::isOpen));
        assertEquals(Set.copyOf(expected), Set.copyOf(door.writes()));
    }

    @Test
    void rollsBackOnlyAlreadyWrittenCellsWhenThirteenthWriteFails() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> expected = northDoorPositions();
        InMemoryDoor door = new InMemoryDoor(expected, false, 13);

        assertFalse(new ChamberDoorService().toggle(door, door, ChamberMutationExecutor.DIRECT, frame));
        assertTrue(expected.subList(0, 12).stream().noneMatch(door::isOpen));
        assertTrue(expected.subList(12, 25).stream().noneMatch(door::isOpen));
        assertEquals(25, door.writes().size()); // 13 attempted writes, then 12 rollbacks
    }

    private static List<BlockPos> northDoorPositions() {
        return List.of(
                new BlockPos(-2, -5, 0), new BlockPos(-1, -5, 0), new BlockPos(0, -5, 0), new BlockPos(1, -5, 0), new BlockPos(2, -5, 0),
                new BlockPos(-2, -4, 0), new BlockPos(-1, -4, 0), new BlockPos(0, -4, 0), new BlockPos(1, -4, 0), new BlockPos(2, -4, 0),
                new BlockPos(-2, -3, 0), new BlockPos(-1, -3, 0), new BlockPos(0, -3, 0), new BlockPos(1, -3, 0), new BlockPos(2, -3, 0),
                new BlockPos(-2, -2, 0), new BlockPos(-1, -2, 0), new BlockPos(0, -2, 0), new BlockPos(1, -2, 0), new BlockPos(2, -2, 0),
                new BlockPos(-2, -1, 0), new BlockPos(-1, -1, 0), new BlockPos(0, -1, 0), new BlockPos(1, -1, 0), new BlockPos(2, -1, 0));
    }

    private static final class InMemoryDoor implements ChamberBlockView, ChamberBlockMutator {
        private final Map<BlockPos, Boolean> states = new LinkedHashMap<>();
        private final List<BlockPos> writes = new ArrayList<>();
        private final int failureWrite;

        private InMemoryDoor(List<BlockPos> positions, boolean open, int failureWrite) {
            positions.forEach(pos -> states.put(pos, open));
            this.failureWrite = failureWrite;
        }

        @Override public ChamberCell cellAt(BlockPos pos) {
            Boolean open = states.get(pos);
            return open == null ? ChamberCell.OTHER : open ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED;
        }

        @Override public boolean set(BlockPos pos, boolean open) {
            writes.add(pos);
            if (failureWrite != 0 && writes.size() == failureWrite) return false;
            states.put(pos, open);
            return true;
        }

        boolean isOpen(BlockPos pos) { return states.get(pos); }
        List<BlockPos> writes() { return List.copyOf(writes); }
    }
}
