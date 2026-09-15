package dev.quantumchamber.chamber;

import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

public final class ChamberGeometry {
    public static final int OUTER_SIZE = 7;
    public static final int INTERIOR_SIZE = 5;
    public static final BlockPos CONTROLLER_LOCAL_POS = new BlockPos(3, 6, 0);

    private ChamberGeometry() {
    }

    public static BlockPos localToWorld(ChamberFrame frame, int x, int y, int z) {
        requireLocalCoordinate(x, y, z);
        Direction right = frame.outwardFacing().rotateYCounterclockwise();
        Direction inward = frame.outwardFacing().getOpposite();
        return frame.controllerPos()
                .offset(right, x - CONTROLLER_LOCAL_POS.getX())
                .add(0, y - CONTROLLER_LOCAL_POS.getY(), 0)
                .offset(inward, z);
    }

    public static BlockBox bounds(ChamberFrame frame) {
        return blockBox(frame, 0, OUTER_SIZE - 1, 0, OUTER_SIZE - 1, 0, OUTER_SIZE - 1);
    }

    public static Box interiorBox(ChamberFrame frame) {
        BlockBox interior = blockBox(frame, 1, OUTER_SIZE - 2, 1, OUTER_SIZE - 2, 1, OUTER_SIZE - 2);
        return new Box(
                interior.getMinX(), interior.getMinY(), interior.getMinZ(),
                interior.getMaxX() + 1, interior.getMaxY() + 1, interior.getMaxZ() + 1);
    }

    public static Set<BlockPos> bulkheadPositions(ChamberFrame frame) {
        Set<BlockPos> positions = new LinkedHashSet<>();
        for (int x = 1; x <= OUTER_SIZE - 2; x++) {
            for (int y = 1; y <= OUTER_SIZE - 2; y++) {
                positions.add(localToWorld(frame, x, y, 0));
            }
        }
        return Set.copyOf(positions);
    }

    public static boolean isShellCell(int x, int y, int z) {
        requireLocalCoordinate(x, y, z);
        return x == 0 || x == OUTER_SIZE - 1
                || y == 0 || y == OUTER_SIZE - 1
                || z == 0 || z == OUTER_SIZE - 1;
    }

    private static BlockBox blockBox(ChamberFrame frame, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        int worldMinX = Integer.MAX_VALUE;
        int worldMinY = Integer.MAX_VALUE;
        int worldMinZ = Integer.MAX_VALUE;
        int worldMaxX = Integer.MIN_VALUE;
        int worldMaxY = Integer.MIN_VALUE;
        int worldMaxZ = Integer.MIN_VALUE;

        for (int x : new int[] {minX, maxX}) {
            for (int y : new int[] {minY, maxY}) {
                for (int z : new int[] {minZ, maxZ}) {
                    BlockPos position = localToWorld(frame, x, y, z);
                    worldMinX = Math.min(worldMinX, position.getX());
                    worldMinY = Math.min(worldMinY, position.getY());
                    worldMinZ = Math.min(worldMinZ, position.getZ());
                    worldMaxX = Math.max(worldMaxX, position.getX());
                    worldMaxY = Math.max(worldMaxY, position.getY());
                    worldMaxZ = Math.max(worldMaxZ, position.getZ());
                }
            }
        }
        return new BlockBox(worldMinX, worldMinY, worldMinZ, worldMaxX, worldMaxY, worldMaxZ);
    }

    private static void requireLocalCoordinate(int x, int y, int z) {
        if (x < 0 || x >= OUTER_SIZE || y < 0 || y >= OUTER_SIZE || z < 0 || z >= OUTER_SIZE) {
            throw new IllegalArgumentException("Chamber local coordinates must be within 0..6");
        }
    }
}
