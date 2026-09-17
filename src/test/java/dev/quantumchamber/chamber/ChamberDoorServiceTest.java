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
    void unchangedFailedCellDoesNotTurnSuccessfulRollbackIntoFailure() {
        var door = new InMemoryDoor(northDoorPositions(), false, 0);
        int[] calls = {0};
        ChamberBlockMutator setter = (pos, open) -> {
            if (++calls[0] == 13 || door.isOpen(pos) == open) return false;
            return door.set(pos, open);
        };
        assertFalse(new ChamberDoorService().toggle(door, setter, ChamberMutationExecutor.DIRECT,
                new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH)));
        assertTrue(northDoorPositions().stream().noneMatch(door::isOpen));
    }

    @Test
    void deferredDoorFailureNeverArmsFromTransientFullyClosedDoor() {
        for (int failureAt : new int[] {13, 25}) for (boolean throwsFailure : new boolean[] {false, true}) {
            var positions = northDoorPositions();
            var door = new InMemoryDoor(positions, true, 0);
            var controller = new TestController();
            var redstone = new ChamberRedstoneService();
            int[] calls = {0};
            int[] starts = {0};
            ChamberBlockMutator setter = (pos, open) -> {
                door.set(pos, open);
                var snapshot = eligibility(positions.stream().noneMatch(door::isOpen));
                redstone.onPowerChanged(controller, true, snapshot, () -> { }, () -> starts[0]++);
                if (++calls[0] == failureAt) {
                    if (throwsFailure) throw new IllegalStateException("門格已變更後失敗");
                    return false;
                }
                return true;
            };
            assertFalse(ChamberPowerCoordinator.deferActivation(() -> new ChamberDoorService().toggle(
                    door, setter, ChamberMutationExecutor.DIRECT, new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH))));
            assertEquals(0, starts[0], "交易中的暫時 sealed 不得啟動");
            assertTrue(positions.stream().allMatch(door::isOpen));
            redstone.onPowerChanged(controller, true, eligibility(false), () -> { }, () -> starts[0]++);
            assertEquals(ChamberState.IDLE, controller.chamberState());
            assertEquals(0, starts[0]);
        }
    }

    private static ChamberActivationSnapshot eligibility(boolean sealed) {
        return new ChamberActivationSnapshot(true, true, sealed, List.of(
                new ChamberActivationSnapshot.ParticipantEligibility(java.util.UUID.fromString("00000000-0000-0000-0000-000000000001"), true)));
    }

    private static final class TestController implements ChamberControllerPort {
        private ChamberState state = ChamberState.IDLE;
        @Override public boolean wasPowered() { return true; }
        @Override public void setWasPowered(boolean powered) { }
        @Override public boolean powerInitialized() { return true; }
        @Override public void setPowerInitialized(boolean initialized) { }
        @Override public ChamberState chamberState() { return state; }
        @Override public void setChamberState(ChamberState next) { state = next; }
    }

    @Test
    void lastSetterFailureAfterMutationRestoresAllCellsForFalseAndThrow() {
        for (boolean throwsFailure : new boolean[] {false, true}) {
            var positions = northDoorPositions();
            var door = new InMemoryDoor(positions, true, 0);
            int[] calls = {0};
            ChamberBlockMutator setter = (pos, open) -> {
                door.set(pos, open);
                if (++calls[0] == 25) {
                    if (throwsFailure) throw new IllegalStateException("最後格已改後失敗");
                    return false;
                }
                return true;
            };
            boolean result = new ChamberDoorService().toggle(door, setter, ChamberMutationExecutor.DIRECT,
                    new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH));
            assertFalse(result);
            assertTrue(positions.stream().allMatch(door::isOpen), "失敗格也必須 rollback");
        }
    }

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
        assertEquals(25, door.writes().size()); // 13 次嘗試，12 格真的變更才需復原
    }

    @Test
    void reportsIncompleteRollbackButStillAttemptsEveryPreviouslyWrittenCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> expected = northDoorPositions();
        // 此位置在 reverse rollback 中段失敗，後面仍有多格必須繼續復原。
        BlockPos unrecovered = expected.get(7);
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
        assertTrue(door.rollbackAttempts().indexOf(unrecovered) < door.rollbackAttempts().size() - 1);
    }

    @Test
    void incompleteRollbackDoesNotInvokeSuccessfulToggleRefresh() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        InMemoryDoor door = new InMemoryDoor(northDoorPositions(), false, 13, Set.of(northDoorPositions().get(4)));
        List<BlockPos> refreshed = new ArrayList<>();

        assertThrows(IllegalStateException.class,
                () -> new ChamberDoorService().toggle(door, door, ChamberMutationExecutor.DIRECT, frame, refreshed::add));

        assertTrue(refreshed.isEmpty());
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
