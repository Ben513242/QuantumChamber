package dev.quantumchamber.chamber;

import java.util.Objects;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidFillable;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldAccess;
import org.jetbrains.annotations.Nullable;

/**
 * 量子艙門（25 格門板的一格）。屬性為 {@link #OPEN} 與 {@link #MEMBRANE}（M5 spec §10.1）。
 *
 * <p>方塊屬性是笛卡兒積，型別上無法排除 {@code open=false}＋{@code *_x/z}；本 mod 的寫入一律經過 {@link #withOpen} 或
 * {@link #withDoor}，所以不會產生它。只有指令（{@code /setblock}）寫得出來，這時它的碰撞、模型與 {@link ChamberDetector}
 * 的判定都與關門相同（{@link #membrane} 回 none）；之後經過守門、改變這一格的寫入（開門或套用量子膜）會把它正規化。
 * 本 task 不在 neighbor update、onBlockAdded 等事件中另外正規化（沒有所屬 session 的量子膜正規化屬 F5）。
 *
 * <p>bulkhead 是 {@code Settings.copy(Blocks.BEDROCK)}（{@code ModBlocks}），預設的 opacity、face culling、suffocation 與
 * blockVision 都由碰撞或外框推導；量子膜是薄平面，所以這四項依 state 覆寫：只有關門維持 bedrock 的行為。
 */
public final class QuantumBulkheadBlock extends Block implements FluidFillable {
    public static final BooleanProperty OPEN = Properties.OPEN;
    /** 量子膜；舊存檔沒有這個屬性，讀入時是預設的 {@code none}。 */
    public static final EnumProperty<MembraneState> MEMBRANE = EnumProperty.of("membrane", MembraneState.class);

    /** 量子膜的外框：置中的 2px 薄板，和模型的平面（z=8 或 x=8）同位置，讓玩家瞄得到。 */
    private static final VoxelShape MEMBRANE_X_OUTLINE = Block.createCuboidShape(0, 0, 7, 16, 16, 9);
    private static final VoxelShape MEMBRANE_Z_OUTLINE = Block.createCuboidShape(7, 0, 0, 9, 16, 16);

    public QuantumBulkheadBlock(AbstractBlock.Settings settings) {
        // bedrock 的預設 suffocates／blockVision 是「blocksMovement 且碰撞為完整方塊」，sealed 量子膜也成立；改成只有關門成立。
        super(settings.suffocates(QuantumBulkheadBlock::closedDoor).blockVision(QuantumBulkheadBlock::closedDoor));
        setDefaultState(getDefaultState().with(OPEN, false).with(MEMBRANE, MembraneState.NONE));
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(OPEN, MEMBRANE);
    }

    /**
     * 切換門的唯一寫法（plan 決策 5）：關門一律把 membrane 設為 none；由關到開是一般開門（none）；已開時再開保留 membrane。
     *
     * @throws IllegalArgumentException state 不是 bulkhead
     */
    public static BlockState withOpen(BlockState state, boolean open) {
        requireBulkhead(state);
        if (open && state.get(OPEN)) return state;
        return state.with(OPEN, open).with(MEMBRANE, MembraneState.NONE);
    }

    /**
     * 門格的完整目標狀態（{@link ChamberDoorService} 的套用目標狀態 API 使用）。membrane 的軸是否符合門的朝向，
     * 由 {@link ChamberDoorService} 依 frame 檢查。
     *
     * @throws IllegalArgumentException state 不是 bulkhead，或 open=false 卻帶量子膜
     */
    public static BlockState withDoor(BlockState state, boolean open, MembraneState membrane) {
        Objects.requireNonNull(membrane, "membrane");
        if (!open && membrane.isMembrane()) throw new IllegalArgumentException("關閉的 bulkhead 只能是 membrane=none：" + membrane.asString());
        return withOpen(state, open).with(MEMBRANE, membrane);
    }

    /** 實際生效的 membrane：關門時一律 none（指令寫出的 open=false＋量子膜，行為與關門相同）。 */
    public static MembraneState membrane(BlockState state) {
        requireBulkhead(state);
        return state.get(OPEN) ? state.get(MEMBRANE) : MembraneState.NONE;
    }

    private static void requireBulkhead(BlockState state) {
        Objects.requireNonNull(state, "state");
        if (!(state.getBlock() instanceof QuantumBulkheadBlock)) throw new IllegalArgumentException("不是 bulkhead：" + state);
    }

    /** suffocates 與 blockVision：只有關門（與 bedrock 相同）；開門與量子膜都不窒息、不遮蔽視線。 */
    private static boolean closedDoor(BlockState state, BlockView world, BlockPos pos) {
        return !state.get(OPEN);
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!state.get(OPEN)) return VoxelShapes.fullCube();
        MembraneState membrane = state.get(MEMBRANE);
        if (!membrane.isMembrane()) return VoxelShapes.empty();
        return membrane.axis().orElseThrow() == Direction.Axis.X ? MEMBRANE_X_OUTLINE : MEMBRANE_Z_OUTLINE;
    }

    /** 關門與 sealed 量子膜是完整方塊；一般開門與 passable 量子膜沒有碰撞。 */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        return !state.get(OPEN) || state.get(MEMBRANE).sealed() ? VoxelShapes.fullCube() : VoxelShapes.empty();
    }

    /** face culling：開門（含量子膜）不剔除鄰格的面，薄平面周圍不會出現透視破洞；關門維持完整方塊。 */
    @Override
    protected VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return state.get(OPEN) ? VoxelShapes.empty() : VoxelShapes.fullCube();
    }

    /** opacity：開門（含量子膜）讓光通過，薄平面不會因所在格沒有光而全黑；關門維持完全不透光。 */
    @Override
    protected int getOpacity(BlockState state, BlockView world, BlockPos pos) {
        return state.get(OPEN) ? 0 : world.getMaxLightLevel();
    }

    /** 任何狀態都不可被流體填入（spec §10.1）：沒有碰撞的開門與 passable 量子膜也擋得住流體。 */
    @Override
    public boolean canFillWithFluid(@Nullable PlayerEntity player, BlockView world, BlockPos pos, BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    public boolean tryFillWithFluid(WorldAccess world, BlockPos pos, BlockState state, FluidState fluidState) {
        return false;
    }

    /** 水桶也不能把它打掉換成流體（預設對非 solid 的狀態允許）。 */
    @Override
    protected boolean canBucketPlace(BlockState state, Fluid fluid) {
        return false;
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
                                 BlockHitResult hit) {
        if (world.isClient) {
            return ActionResult.SUCCESS;
        }
        if (world instanceof ServerWorld serverWorld && player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
            var candidate = new dev.quantumchamber.candidate.CandidateDoorInteraction().onBulkheadUse(serverWorld, pos, serverPlayer);
            if (candidate.isPresent()) return candidate.get();
        }
        WorldChamberBlockView view = new WorldChamberBlockView(world);
        ChamberLocator locator = new ChamberLocator(new ChamberDetector());
        var frame = locator.findFrame(view, pos);
        if (frame.isPresent() && world instanceof ServerWorld serverWorld) {
            if (WorldChamberDoorAdapter.toggle(serverWorld, view, frame.get())
                    == WorldChamberDoorAdapter.Result.ROLLBACK_FAILED) return ActionResult.FAIL;
        }
        return ActionResult.SUCCESS;
    }
}
