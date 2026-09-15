package dev.quantumchamber.chamber;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockPos;

public final class ChamberDoorService {
    public boolean toggle(ChamberBlockView view, ChamberBlockMutator mutator,
                          ChamberMutationExecutor executor, ChamberFrame frame) {
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
        return executor.execute(() -> mutateWithRollback(mutator, positions, original, next));
    }

    private static boolean mutateWithRollback(ChamberBlockMutator mutator, List<BlockPos> positions,
                                              boolean original, boolean next) {
        List<BlockPos> changed = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!mutator.set(pos, next)) {
                for (int index = changed.size() - 1; index >= 0; index--) mutator.set(changed.get(index), original);
                return false;
            }
            changed.add(pos);
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
}
