package dev.quantumchamber.chamber;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.math.BlockPos;

public final class ChamberDoorService {
    public boolean toggle(ChamberBlockView view, ChamberBlockMutator mutator,
                          ChamberMutationExecutor executor, ChamberFrame frame) {
        return toggle(view, mutator, executor, frame, ignored -> { });
    }

    public boolean toggle(ChamberBlockView view, ChamberBlockMutator mutator,
                          ChamberMutationExecutor executor, ChamberFrame frame, ControllerRefresh refresh) {
        Objects.requireNonNull(refresh, "refresh");
        List<BlockPos> positions = aperturePositions(frame);
        Boolean open = null;
        for (BlockPos pos : positions) {
            ChamberCell cell = view.cellAt(pos);
            if (cell != ChamberCell.BULKHEAD_OPEN && cell != ChamberCell.BULKHEAD_CLOSED) return false;
            boolean current = cell == ChamberCell.BULKHEAD_OPEN;
            if (open != null && open != current) return false;
            open = current;
        }
        if (open == null) return false;
        boolean original = open;
        boolean next = !original;
        boolean toggled = executor.execute(() -> mutateWithRollback(view, mutator, positions, original, next));
        if (toggled) refresh.refresh(frame.controllerPos());
        return toggled;
    }

    private static boolean mutateWithRollback(ChamberBlockView view, ChamberBlockMutator mutator, List<BlockPos> positions,
                                              boolean original, boolean next) {
        List<BlockPos> changed = new ArrayList<>();
        for (BlockPos pos : positions) {
            changed.add(pos);
            RuntimeException mutationFailure = null;
            boolean accepted = false;
            try {
                accepted = mutator.set(pos, next);
            } catch (RuntimeException failure) {
                mutationFailure = failure;
            }
            if (!accepted) {
                List<BlockPos> rollbackFailures = new ArrayList<>();
                for (int index = changed.size() - 1; index >= 0; index--) {
                    BlockPos changedPos = changed.get(index);
                    ChamberCell restored = original ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED;
                    // 原生 setBlockState 對相同狀態回 false；尚未改變的失敗格不必再次寫入。
                    if (view.cellAt(changedPos) == restored) continue;
                    try {
                        mutator.set(changedPos, original);
                        if (view.cellAt(changedPos) != restored) rollbackFailures.add(changedPos);
                    } catch (RuntimeException failure) {
                        rollbackFailures.add(changedPos);
                        if (mutationFailure == null) mutationFailure = failure;
                        else mutationFailure.addSuppressed(failure);
                    }
                }
                if (!rollbackFailures.isEmpty()) {
                    throw new IllegalStateException("整面門 rollback 失敗 " + rollbackFailures.size()
                            + " 格：" + rollbackFailures.stream().map(BlockPos::toShortString).toList(), mutationFailure);
                }
                return false;
            }
        }
        return true;
    }

    private static List<BlockPos> aperturePositions(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>(25);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        }
        return positions;
    }

    @FunctionalInterface
    public interface ControllerRefresh {
        void refresh(BlockPos controllerPos);
    }
}
