package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void successfulToggleRefreshesTheResolvedControllerExactlyOnce() {
        ChamberFrame frame = new ChamberFrame(new BlockPos(20, 80, -40), Direction.EAST);
        InMemoryDoor door = new InMemoryDoor(doorPositions(frame), false, 0);
        List<BlockPos> refreshed = new ArrayList<>();

        assertTrue(new ChamberDoorService().toggle(
                door, door, ChamberMutationExecutor.DIRECT, frame, refreshed::add));

        assertEquals(List.of(frame.controllerPos()), refreshed);
    }

    @Test
    void failedToggleDoesNotRefreshController() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        InMemoryDoor door = new InMemoryDoor(northDoorPositions(), false, 13);
        List<BlockPos> refreshed = new ArrayList<>();

        assertFalse(new ChamberDoorService().toggle(
                door, door, ChamberMutationExecutor.DIRECT, frame, refreshed::add));

        assertTrue(refreshed.isEmpty());
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

    @Test
    void reportsIncompleteRollbackButStillAttemptsEveryPreviouslyWrittenCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> expected = northDoorPositions();
        BlockPos unrecovered = expected.get(4);
        InMemoryDoor door = new InMemoryDoor(expected, false, 13, Set.of(unrecovered));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new ChamberDoorService().toggle(door, door, ChamberMutationExecutor.DIRECT, frame));

        assertTrue(failure.getMessage().contains("1"));
        assertTrue(failure.getMessage().contains(unrecovered.toShortString()));
        assertTrue(door.isOpen(unrecovered));
        assertTrue(expected.subList(0, 12).stream().filter(pos -> !pos.equals(unrecovered)).noneMatch(door::isOpen));
        assertTrue(expected.subList(12, 25).stream().noneMatch(door::isOpen));
        assertEquals(12, door.rollbackAttempts().size());
        assertTrue(door.rollbackAttempts().contains(unrecovered));
    }

    private static List<BlockPos> northDoorPositions() {
        return List.of(
                new BlockPos(-2, -5, 0), new BlockPos(-1, -5, 0), new BlockPos(0, -5, 0), new BlockPos(1, -5, 0), new BlockPos(2, -5, 0),
                new BlockPos(-2, -4, 0), new BlockPos(-1, -4, 0), new BlockPos(0, -4, 0), new BlockPos(1, -4, 0), new BlockPos(2, -4, 0),
                new BlockPos(-2, -3, 0), new BlockPos(-1, -3, 0), new BlockPos(0, -3, 0), new BlockPos(1, -3, 0), new BlockPos(2, -3, 0),
                new BlockPos(-2, -2, 0), new BlockPos(-1, -2, 0), new BlockPos(0, -2, 0), new BlockPos(1, -2, 0), new BlockPos(2, -2, 0),
                new BlockPos(-2, -1, 0), new BlockPos(-1, -1, 0), new BlockPos(0, -1, 0), new BlockPos(1, -1, 0), new BlockPos(2, -1, 0));
    }

    private static List<BlockPos> doorPositions(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        }
        return positions;
    }

    private static final class InMemoryDoor implements ChamberBlockView, ChamberBlockMutator {
        private final Map<BlockPos, Boolean> states = new LinkedHashMap<>();
        private final List<BlockPos> writes = new ArrayList<>();
        private final int failureWrite;
        private final Set<BlockPos> rollbackFailurePositions;
        private final List<BlockPos> rollbackAttempts = new ArrayList<>();

        private InMemoryDoor(List<BlockPos> positions, boolean open, int failureWrite) {
            this(positions, open, failureWrite, Set.of());
        }

        private InMemoryDoor(List<BlockPos> positions, boolean open, int failureWrite, Set<BlockPos> rollbackFailurePositions) {
            positions.forEach(pos -> states.put(pos, open));
            this.failureWrite = failureWrite;
            this.rollbackFailurePositions = rollbackFailurePositions;
        }

        @Override public ChamberCell cellAt(BlockPos pos) {
            Boolean open = states.get(pos);
            return open == null ? ChamberCell.OTHER : open ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED;
        }

        @Override public boolean set(BlockPos pos, boolean open) {
            writes.add(pos);
            if (failureWrite != 0 && writes.size() == failureWrite) return false;
            if (!open && rollbackFailurePositions.contains(pos)) {
                rollbackAttempts.add(pos);
                return false;
            }
            if (!open && writes.size() > failureWrite) rollbackAttempts.add(pos);
            states.put(pos, open);
            return true;
        }

        boolean isOpen(BlockPos pos) { return states.get(pos); }
        List<BlockPos> writes() { return List.copyOf(writes); }
        List<BlockPos> rollbackAttempts() { return List.copyOf(rollbackAttempts); }
    }
}
