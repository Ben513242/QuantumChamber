package dev.quantumchamber.chamber;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.util.math.random.Random;
import org.jetbrains.annotations.Nullable;

public final class ChamberControllerBlock extends BlockWithEntity implements BlockEntityProvider {
    public static final MapCodec<ChamberControllerBlock> CODEC = createCodec(ChamberControllerBlock::new);
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
    private static final int REFRESH_DELAY_TICKS = 20;
    private static final ChamberRedstoneService REDSTONE = new ChamberRedstoneService();

    public ChamberControllerBlock(AbstractBlock.Settings settings) {
        super(settings);
        setDefaultState(getDefaultState().with(FACING, net.minecraft.util.math.Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BlockWithEntity> getCodec() {
        return CODEC;
    }

    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        return getDefaultState().with(FACING, context.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected void appendProperties(StateManager.Builder<net.minecraft.block.Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.MODEL;
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ChamberControllerBlockEntity(pos, state);
    }

    @Override
    protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
        super.onBlockAdded(state, world, pos, oldState, notify);
        if (world instanceof ServerWorld serverWorld) scheduleRefresh(serverWorld, pos);
    }

    @Override
    protected void neighborUpdate(
            BlockState state, World world, BlockPos pos, net.minecraft.block.Block sourceBlock, BlockPos sourcePos, boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
        if (world instanceof ServerWorld serverWorld) {
            REDSTONE.onNeighborUpdate(serverWorld, pos);
            scheduleRefresh(serverWorld, pos);
        }
    }

    @Override
    protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        refreshState(world, pos);
        scheduleRefresh(world, pos);
    }

    @Override
    protected boolean hasComparatorOutput(BlockState state) {
        return true;
    }

    @Override
    protected int getComparatorOutput(BlockState state, World world, BlockPos pos) {
        return world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller
                ? ChamberStatusSignal.forState(controller.chamberState())
                : 0;
    }

    public static void onControllerLoaded(ServerWorld world, BlockPos pos) {
        REDSTONE.onLoad(world, pos);
        scheduleRefresh(world, pos);
    }

    public static void refreshState(ServerWorld world, BlockPos pos) {
        REDSTONE.refreshState(world, pos);
    }

    public static void scheduleRefresh(ServerWorld world, BlockPos pos) {
        world.scheduleBlockTick(pos, dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER, REFRESH_DELAY_TICKS);
    }
}
