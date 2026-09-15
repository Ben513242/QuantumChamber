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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantumBulkheadBlock extends Block {
    public static final BooleanProperty OPEN = Properties.OPEN;
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

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
        var frame = locator.findFrame(view, pos);
        if (frame.isPresent()) {
            try {
                new ChamberDoorService().toggle(
                        view,
                        (target, open) -> world.setBlockState(target, world.getBlockState(target).with(OPEN, open)),
                        ChamberProtectionService.get()::authorizedMutation,
                        frame.get());
            } catch (IllegalStateException failure) {
                LOGGER.error("Bulkhead toggle rollback failure for frame {}: {}", frame.get(), failure.getMessage(), failure);
                return ActionResult.FAIL;
            }
        }
        return ActionResult.SUCCESS;
    }
}
