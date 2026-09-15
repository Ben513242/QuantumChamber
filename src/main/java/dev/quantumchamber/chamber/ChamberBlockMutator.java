package dev.quantumchamber.chamber;

import net.minecraft.util.math.BlockPos;

@FunctionalInterface
public interface ChamberBlockMutator {
    boolean set(BlockPos pos, boolean open);
}
