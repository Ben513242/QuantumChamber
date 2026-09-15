package dev.quantumchamber.chamber;

import java.util.EnumSet;
import net.minecraft.util.math.BlockPos;

public final class ChamberDetector {
    public ChamberStructureResult validate(ChamberBlockView view, ChamberFrame frame) {
        EnumSet<ChamberStructureResult.Failure> failures = EnumSet.noneOf(ChamberStructureResult.Failure.class);
        Boolean doorOpen = null;
        for (int x = 0; x < ChamberGeometry.OUTER_SIZE; x++) {
            for (int y = 0; y < ChamberGeometry.OUTER_SIZE; y++) {
                for (int z = 0; z < ChamberGeometry.OUTER_SIZE; z++) {
                    BlockPos pos = ChamberGeometry.localToWorld(frame, x, y, z);
                    ChamberCell cell = view.cellAt(pos);
                    if (x == 3 && y == 6 && z == 0) {
                        if (cell != ChamberCell.CONTROLLER) failures.add(ChamberStructureResult.Failure.CONTROLLER_MISSING);
                    } else if (z == 0 && x >= 1 && x <= 5 && y >= 1 && y <= 5) {
                        if (cell != ChamberCell.BULKHEAD_OPEN && cell != ChamberCell.BULKHEAD_CLOSED) {
                            failures.add(ChamberStructureResult.Failure.BULKHEAD_MISSING);
                        } else {
                            boolean open = cell == ChamberCell.BULKHEAD_OPEN;
                            if (doorOpen != null && doorOpen != open) failures.add(ChamberStructureResult.Failure.BULKHEAD_MIXED);
                            doorOpen = open;
                        }
                    } else if (ChamberGeometry.isShellCell(x, y, z) && cell != ChamberCell.BEDROCK) {
                        failures.add(ChamberStructureResult.Failure.SHELL_MISSING);
                    }
                }
            }
        }
        boolean valid = failures.isEmpty();
        return new ChamberStructureResult(valid, valid && Boolean.FALSE.equals(doorOpen), failures);
    }
}
