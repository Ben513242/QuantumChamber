package dev.quantumchamber.chamber;

import net.minecraft.util.math.BlockPos;

@FunctionalInterface
public interface ChamberBlockView {
    ChamberCell cellAt(BlockPos pos);
}
