package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.registry.ModBlocks;
import net.minecraft.block.Blocks;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** 固定的人工定義 fixture，不從 validator 推導預期結果。 */
public final class ChamberGameTestBuilder {
    public static final BlockPos CONTROLLER = new BlockPos(7, 7, 4);
    private ChamberGameTestBuilder() {}

    public static ChamberFrame build(TestContext context, boolean open, Direction facing, boolean missingShell) {
        BlockPos anchor = switch (facing) {
            case NORTH -> CONTROLLER;
            case SOUTH -> new BlockPos(7, 7, 10);
            case EAST -> new BlockPos(10, 7, 7);
            case WEST -> new BlockPos(4, 7, 7);
            default -> throw new IllegalArgumentException("fixture 僅支援水平朝向");
        };
        ChamberFrame frame = new ChamberFrame(context.getAbsolutePos(anchor), facing);
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int x = 0; x < 7; x++) {
                for (int y = 0; y < 7; y++) {
                    for (int z = 0; z < 7; z++) {
                        BlockPos pos = frame.controllerPos().offset(facing.rotateYCounterclockwise(), x - 3)
                                .add(0, y - 6, 0).offset(facing.getOpposite(), z);
                        boolean shell = x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6;
                        var state = shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                        if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) {
                            state = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, open);
                        }
                        if (missingShell && x == 0 && y == 3 && z == 3) state = Blocks.AIR.getDefaultState();
                        context.getWorld().setBlockState(pos, state, 3);
                    }
                }
            }
            context.getWorld().setBlockState(frame.controllerPos(),
                    ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, facing), 3);
        });
        return frame;
    }

    public static ChamberControllerBlockEntity controller(TestContext context) {
        return (ChamberControllerBlockEntity) context.getWorld().getBlockEntity(context.getAbsolutePos(CONTROLLER));
    }
}
