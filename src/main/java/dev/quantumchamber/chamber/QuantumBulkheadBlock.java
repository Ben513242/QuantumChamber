package dev.quantumchamber.chamber;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

public final class QuantumBulkheadBlock extends Block {
    public static final BooleanProperty OPEN = Properties.OPEN;

    public QuantumBulkheadBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(OPEN, false));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(OPEN);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? VoxelShapes.empty() : VoxelShapes.fullCube();
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return state.get(OPEN) ? VoxelShapes.empty() : VoxelShapes.fullCube();
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        WorldChamberBlockView view = new WorldChamberBlockView(world);
        ChamberLocator locator = new ChamberLocator(new ChamberDetector());
        locator.findFrame(view, pos).ifPresent(frame -> new ChamberDoorService().toggle(
                view,
                (target, open) -> world.setBlockState(target, world.getBlockState(target).with(OPEN, open)),
                ChamberMutationExecutor.DIRECT,
                frame));
        return ActionResult.SUCCESS;
    }
}
