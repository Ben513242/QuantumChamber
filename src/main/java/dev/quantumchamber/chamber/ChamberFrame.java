package dev.quantumchamber.chamber;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public record ChamberFrame(BlockPos controllerPos, Direction outwardFacing) {
}
