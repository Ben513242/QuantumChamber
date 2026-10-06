package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.registry.ModBlocks;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Clearable;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

/**
 * spec §9.2 的投影寫入（F4b-1；PREPARE 的呼叫端屬 F4b-2）：在目的世界的同 XYZ、同朝向寫入投影艙的方塊，寫完逐格驗證。只寫方塊：不讀寫任何權威（projection
 * ledger 的 PENDING／COMPLETE、§6.5 的實體交叉檢查與 §9.2 的衝突都由 F4b-2 判斷），也不記 log（結果帶著原因與 cause，由 F4b-2 依 session 記錄）。只在
 * server thread 呼叫。
 * <ul>
 * <li><b>目標方塊</b>：shell（7×7×7 的邊界）為 bedrock；local (3,6,0) 為 Controller，{@code FACING}＝outwardFacing，block entity 是方塊新建的預設值，不複製
 *     來源艙的身分欄位；門板 25 格（local x、y 1..5，z 0）為 bulkhead，{@code open=true}、{@code membrane=none}。第一次寫入另把 interior 5×5×5 清成 AIR。</li>
 * <li><b>兩種模式</b>（{@link Mode}）：第一次寫入（ledger 為 PENDING）寫完整的 7×7×7；修復（COMPLETE，EXISTING 再次造訪）只寫不變量 cell（shell、Controller、
 *     門）中與目標不同的格，不碰 interior，保留之前玩家在投影艙內的修改，門因此回到開啟。兩種模式都只寫與目標不同的格；第一次寫入時，Controller 的位置上
 *     若已有 Controller（例如寫到一半 crash 之後的 PENDING），先清成 AIR 再寫，讓 block entity 一定是新建的預設值。</li>
 * <li><b>寫入方式</b>（plan 決策 3，比照原版 {@code /setblock}）：寫入前對既有的 block entity 呼叫 {@link Clearable#clear}，箱子等容器在原版的
 *     {@code onStateReplaced} 不會噴出物品；flags 為 {@link #FLAGS}（{@code NOTIFY_LISTENERS | FORCE_STATE | SKIP_DROPS}：不送鄰格的方塊更新，也不送形狀更新，
 *     鄰格不觸發物理與紅石）；全部在 {@link ChamberProtectionService#authorizedMutation} 之內（COMPLETE 投影的不變量 cell 受 §9.3 保護）。</li>
 * <li><b>既有 entity</b>：任何非旁觀玩家的 bbox 與這次要寫入的 cell 相交時，一個方塊都不寫（{@link Status#BLOCKED_BY_PLAYER}，呼叫端下一個 tick 再試）。
 *     第一次寫入的檢查範圍是整個 7×7×7；修復只看這次要寫的不變量 cell，所以留在投影艙 interior 的玩家不會擋住修復。非玩家 entity 不檢查、不移動、不移除，
 *     方塊照常寫入，後果交由原版處理（spec §2）。</li>
 * <li><b>前提</b>：目的 volume 涵蓋的 chunk 都已是 FULL（F4b-2 的預載）。有任何一個不是時不讀不寫任何一格、不載入 chunk，回 {@link Status#NOT_LOADED}。</li>
 * </ul>
 */
public final class ProjectionWriter {
    /** plan 決策 3 的 flags：通知 client（{@code NOTIFY_LISTENERS}），不送鄰格的形狀更新（{@code FORCE_STATE}），不掉落（{@code SKIP_DROPS}）；沒有 {@code NOTIFY_NEIGHBORS}。 */
    public static final int FLAGS = Block.NOTIFY_LISTENERS | Block.FORCE_STATE | Block.SKIP_DROPS;
    /** 驗證失敗時，cause 的訊息最多列出的格數。 */
    private static final int SHOWN_MISMATCHES = 8;

    private ProjectionWriter() {}

    /** 寫法（spec §9.2）。 */
    public enum Mode {
        /** 第一次寫入（ledger 為 PENDING）：整個 7×7×7，interior 清成 AIR；玩家檢查涵蓋整個 volume。 */
        FIRST_WRITE,
        /** 修復（ledger 為 COMPLETE）：只寫與目標不同的不變量 cell，不碰 interior；玩家檢查只看這次要寫的格。 */
        REPAIR
    }

    /** 寫入的結果；F4b-2 依它決定 COMPLETE、下一個 tick 再試，或 SEALED(PROJECTION_FAILED)。 */
    public enum Status {
        /** 寫入完成，而且這個模式的每一格都通過驗證。 */
        WRITTEN,
        /** 有非旁觀玩家的 bbox 與這次要寫入的 cell 相交：沒有寫任何方塊（{@link Result#blockingPlayers()}）；下一個 tick 再試。 */
        BLOCKED_BY_PLAYER,
        /** 目的 volume 涵蓋的 chunk 有一個不是 FULL：沒有讀寫任何一格，也沒有載入 chunk；預載完成之後再試。 */
        NOT_LOADED,
        /** 寫完之後逐格驗證不符（{@link Result#mismatched()}，例如被其他 mod 拒絕或超出世界高度）。 */
        VERIFY_FAILED,
        /** 寫入時丟出例外：可能已寫了一部分；第一次寫入照第一次寫入重試，修復照修復重試（{@link Result#failure()}）。 */
        WRITE_FAILED
    }

    /**
     * 寫入的結果。{@code written} 是實際改變的格數；{@code mismatched} 只在 {@link Status#VERIFY_FAILED} 非空；{@code blockingPlayers} 只在
     * {@link Status#BLOCKED_BY_PLAYER} 非空；{@code failure} 只在 {@link Status#VERIFY_FAILED} 與 {@link Status#WRITE_FAILED} 存在（可直接當成
     * SEALED 的 root cause）。
     */
    public record Result(Status status, int written, List<BlockPos> mismatched, List<UUID> blockingPlayers, Optional<RuntimeException> failure) {
        public Result {
            Objects.requireNonNull(status, "status");
            mismatched = List.copyOf(mismatched);
            blockingPlayers = List.copyOf(blockingPlayers);
            Objects.requireNonNull(failure, "failure");
            if ((status == Status.VERIFY_FAILED || status == Status.WRITE_FAILED) != failure.isPresent()) {
                throw new IllegalArgumentException("只有 VERIFY_FAILED、WRITE_FAILED 帶 failure：" + status);
            }
            if ((status == Status.VERIFY_FAILED) != !mismatched.isEmpty()) throw new IllegalArgumentException("只有 VERIFY_FAILED 帶不符的格：" + status);
            if ((status == Status.BLOCKED_BY_PLAYER) != !blockingPlayers.isEmpty()) throw new IllegalArgumentException("只有 BLOCKED_BY_PLAYER 帶玩家：" + status);
        }

        /** 寫入完成並通過驗證。 */
        public boolean succeeded() { return status == Status.WRITTEN; }
    }

    /** {@link #controllerPresence} 的結果（spec §6.5 的實體交叉檢查用）。 */
    public enum ControllerPresence { ABSENT, PRESENT, NOT_LOADED }

    /**
     * 目的世界的投影 Controller 位置（local (3,6,0)）是否已經有 Controller 方塊（spec §6.5 的實體交叉檢查：ledger 沒有這個 key、這裡卻有 Controller 時，F4b-2
     * 進 SEALED(PROJECTION_CONFLICT)）。只查已是 FULL 的 chunk，不載入；不是 FULL 時為 {@link ControllerPresence#NOT_LOADED}。只在 server thread 呼叫。
     */
    public static ControllerPresence controllerPresence(ServerWorld world, ChamberFrame frame) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(frame, "frame");
        requireServerThread(world);
        var pos = frame.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return ControllerPresence.NOT_LOADED;
        return chunk.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER) ? ControllerPresence.PRESENT : ControllerPresence.ABSENT;
    }

    /**
     * 依 {@code mode} 寫入投影（見類別說明），回傳結果；不丟出例外（寫入時的例外轉成 {@link Status#WRITE_FAILED}）。只在 server thread 呼叫（否則丟
     * {@link IllegalStateException}，這是 caller 錯誤）。
     */
    public static Result write(ServerWorld world, ChamberFrame frame, Mode mode) {
        Objects.requireNonNull(world, "world"); Objects.requireNonNull(frame, "frame"); Objects.requireNonNull(mode, "mode");
        requireServerThread(world);
        var bounds = ChamberGeometry.bounds(frame);
        if (!loaded(world, bounds)) return new Result(Status.NOT_LOADED, 0, List.of(), List.of(), Optional.empty());
        var targets = targets(frame, mode);
        var pending = new LinkedHashMap<BlockPos, BlockState>();
        targets.forEach((pos, target) -> { if (needsWrite(world, frame, mode, pos, target)) pending.put(pos, target); });
        var reach = mode == Mode.FIRST_WRITE ? List.of(box(bounds)) : pending.keySet().stream().map(Box::new).toList();
        var blocking = blockingPlayers(world, reach);
        if (!blocking.isEmpty()) return new Result(Status.BLOCKED_BY_PLAYER, 0, List.of(), blocking, Optional.empty());
        int[] written = {0};
        try {
            ChamberProtectionService.get().authorizedMutation(() -> pending.forEach((pos, target) -> {
                if (writeCell(world, frame, mode, pos, target)) written[0]++;
            }));
        } catch (RuntimeException failure) {
            return new Result(Status.WRITE_FAILED, written[0], List.of(), List.of(), Optional.of(failure));
        }
        var mismatched = mismatched(world, frame, mode, targets);
        if (mismatched.isEmpty()) return new Result(Status.WRITTEN, written[0], List.of(), List.of(), Optional.empty());
        var shown = mismatched.stream().limit(SHOWN_MISMATCHES).map(pos -> pos.toShortString() + "=" + world.getBlockState(pos)).toList();
        return new Result(Status.VERIFY_FAILED, written[0], mismatched, List.of(), Optional.of(new IllegalStateException(
                "投影寫入之後逐格驗證不符 " + mismatched.size() + " 格（" + mode + "）：" + shown)));
    }

    /**
     * 這個模式要驗證的每一格與它的目標狀態，依 local 座標固定的順序：第一次寫入是整個 7×7×7（interior 為 AIR），修復只有不變量 cell（shell、Controller、門）。
     */
    static Map<BlockPos, BlockState> targets(ChamberFrame frame, Mode mode) {
        var controller = ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing());
        var door = QuantumBulkheadBlock.withDoor(ModBlocks.QUANTUM_BULKHEAD.getDefaultState(), true, MembraneState.NONE);
        var bedrock = Blocks.BEDROCK.getDefaultState();
        var air = Blocks.AIR.getDefaultState();
        var result = new LinkedHashMap<BlockPos, BlockState>();
        int last = ChamberGeometry.OUTER_SIZE - 1;
        for (int x = 0; x <= last; x++) for (int y = 0; y <= last; y++) for (int z = 0; z <= last; z++) {
            boolean shell = ChamberGeometry.isShellCell(x, y, z);
            if (!shell && mode == Mode.REPAIR) continue;
            BlockState target;
            if (x == ChamberGeometry.CONTROLLER_LOCAL_POS.getX() && y == ChamberGeometry.CONTROLLER_LOCAL_POS.getY() && z == ChamberGeometry.CONTROLLER_LOCAL_POS.getZ()) {
                target = controller;
            } else if (z == 0 && x > 0 && x < last && y > 0 && y < last) {
                target = door;
            } else {
                target = shell ? bedrock : air;
            }
            result.put(ChamberGeometry.localToWorld(frame, x, y, z), target);
        }
        return result;
    }

    /** 這一格這次要不要寫：與目標不同；第一次寫入時，Controller 位置上已有的 Controller 也要重寫（block entity 一定是新建的預設值）。 */
    private static boolean needsWrite(ServerWorld world, ChamberFrame frame, Mode mode, BlockPos pos, BlockState target) {
        var current = world.getBlockState(pos);
        if (!current.equals(target)) return true;
        return mode == Mode.FIRST_WRITE && pos.equals(frame.controllerPos());
    }

    /** 寫一格：先清空既有 block entity 的內容（容器不噴出物品），再以 {@link #FLAGS} 寫入；回傳這一格是否改變。 */
    private static boolean writeCell(ServerWorld world, ChamberFrame frame, Mode mode, BlockPos pos, BlockState target) {
        Clearable.clear(world.getBlockEntity(pos));
        boolean changed = false;
        if (mode == Mode.FIRST_WRITE && pos.equals(frame.controllerPos()) && world.getBlockState(pos).isOf(ModBlocks.CHAMBER_CONTROLLER)) {
            changed = world.setBlockState(pos, Blocks.AIR.getDefaultState(), FLAGS);
        }
        return world.setBlockState(pos, target, FLAGS) || changed;
    }

    /** 逐格驗證：每一格都是目標狀態；Controller 另要有 block entity，第一次寫入時 block entity 沒有 chamber UUID（不複製來源的身分欄位）。 */
    private static List<BlockPos> mismatched(ServerWorld world, ChamberFrame frame, Mode mode, Map<BlockPos, BlockState> targets) {
        var result = new ArrayList<BlockPos>();
        targets.forEach((pos, target) -> {
            boolean ok = world.getBlockState(pos).equals(target);
            if (ok && pos.equals(frame.controllerPos())) {
                ok = world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller && !controller.isRemoved()
                        && (mode == Mode.REPAIR || controller.chamberUuid() == null);
            }
            if (!ok) result.add(pos.toImmutable());
        });
        return result;
    }

    /** bbox 與任何一個 {@code reach} 相交的非旁觀玩家（依世界的玩家順序）。 */
    private static List<UUID> blockingPlayers(ServerWorld world, List<Box> reach) {
        if (reach.isEmpty()) return List.of();
        var result = new ArrayList<UUID>();
        for (var player : world.getPlayers()) {
            if (player.isSpectator()) continue;
            var bbox = player.getBoundingBox();
            if (reach.stream().anyMatch(bbox::intersects)) result.add(player.getUuid());
        }
        return result;
    }

    private static boolean loaded(ServerWorld world, BlockBox bounds) {
        var manager = world.getChunkManager();
        for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++)
            for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++)
                if (manager.getWorldChunk(x, z) == null) return false;
        return true;
    }

    private static Box box(BlockBox bounds) {
        return new Box(bounds.getMinX(), bounds.getMinY(), bounds.getMinZ(), bounds.getMaxX() + 1, bounds.getMaxY() + 1, bounds.getMaxZ() + 1);
    }

    private static void requireServerThread(ServerWorld world) {
        if (!world.getServer().isOnThread()) throw new IllegalStateException("投影寫入只能在 server thread 執行");
    }
}
