package dev.quantumchamber.chamber;

import java.util.Optional;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class ChamberLocator {
    private final ChamberDetector detector;

    public ChamberLocator(ChamberDetector detector) {
        this.detector = detector;
    }

    public Optional<ChamberFrame> findFrame(ChamberBlockView view, BlockPos bulkheadPos) {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            Direction right = facing.rotateYCounterclockwise();
            for (int x = 1; x <= 5; x++) {
                for (int y = 1; y <= 5; y++) {
                    BlockPos controller = bulkheadPos.offset(right, 3 - x).add(0, 6 - y, 0);
                    ChamberFrame frame = new ChamberFrame(controller, facing);
                    if (detector.validate(view, frame).valid()) return Optional.of(frame);
                }
            }
        }
        return Optional.empty();
    }
}
