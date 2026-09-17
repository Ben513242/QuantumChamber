package dev.quantumchamber.chamber;

import com.mojang.serialization.MapCodec;
import dev.quantumchamber.registry.ModBlocks;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
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
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient) return ActionResult.SUCCESS;
        if (!(world instanceof ServerWorld serverWorld)) return ActionResult.FAIL;
        if (player.isSneaking() && player.getMainHandStack().isEmpty() && player.getOffHandStack().isEmpty()) {
            return ChamberMaintenanceInteraction.toggleEnabled(serverWorld, pos, player);
        }
        if (player.isSpectator()) return ActionResult.FAIL;
        var view = new WorldChamberBlockView(world);
        var frame = new ChamberFrame(pos, state.get(FACING));
        if (!new ChamberDetector().validate(view, frame).valid()) return ActionResult.FAIL;
        if (WorldChamberDoorAdapter.toggle(serverWorld, view, frame) != WorldChamberDoorAdapter.Result.TOGGLED) {
            if (world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller && controller.activationBlocked()) {
                player.sendMessage(Text.literal("艙門交易故障，已禁止新啟動；請完成一次成功開／關門以修復。斷電仍可安全返還。"), true);
            }
            return ActionResult.FAIL;
        }
        if (world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller) {
            String message = !controller.wasPowered() ? "艙門已切換；目前未供電。"
                    : switch (controller.chamberState()) {
                        case INVALID -> "艙門已切換；量子功能已停用或結構無效。";
                        case IDLE -> "已供電保護；請關門、進艙並讓全員取得 QuantumState。";
                        case READY -> "條件已齊，等待啟動。";
                        case ARMED -> "艙體已進入量子準備、活動或安全返還狀態。";
                    };
            player.sendMessage(Text.literal(message), true);
        }
        return ActionResult.SUCCESS;
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
        if (!world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)
                || !(world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld() != world) return;
        refreshState(world, pos);
        scheduleRefresh(world, pos);
        ChamberGlowEmitter.emit(world, pos);
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

    public static void refreshState(ServerWorld world, BlockPos pos) {
        REDSTONE.refreshState(world, pos);
    }

    public static void scheduleRefresh(ServerWorld world, BlockPos pos) {
        world.scheduleBlockTick(pos, ModBlocks.CHAMBER_CONTROLLER, REFRESH_DELAY_TICKS);
    }
}
