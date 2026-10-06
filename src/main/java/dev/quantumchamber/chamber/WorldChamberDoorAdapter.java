package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 共用世界交易與失敗處理；互動回應政策仍由入口決定。 */
final class WorldChamberDoorAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    /** 門板周圍必須已是 FULL 的水平範圍（格）；與 {@link ChamberRedstoneService#powerNeighborhoodLoaded} 的 ±3 相同。 */
    private static final int DOOR_NEIGHBORHOOD = 3;

    private WorldChamberDoorAdapter() {}

    /**
     * 玩家開關原艙門（bulkhead 與 Controller 的 {@code onUse} 都經這裡）。先交給 M5 塌縮流程的攔截（{@link ChamberCollapsePort#doors()}，M5 spec §7、§7.3；
     * 排在 {@link ChamberDoorService} 之前）：攔截處理了就回 {@link Result#INTERCEPTED}、拒絕就回 {@link Result#REFUSED}，兩者都不切換門；不攔截時照一般門的邏輯切換。
     */
    static Result toggle(ServerWorld world, WorldChamberBlockView view, ChamberFrame frame, PlayerEntity player) {
        switch (ChamberCollapsePort.doors().use(world, frame, player)) {
            case HANDLED -> { return Result.INTERCEPTED; }
            case REFUSED -> { return Result.REFUSED; }
            case PASS -> { }
        }
        return ChamberPowerCoordinator.deferActivation(() -> toggleDeferred(world, view, frame));
    }

    private static Result toggleDeferred(ServerWorld world, WorldChamberBlockView view, ChamberFrame frame) {
        try {
            boolean toggled = new ChamberDoorService().toggle(
                    view,
                    (target, open) -> world.setBlockState(target,
                            QuantumBulkheadBlock.withOpen(world.getBlockState(target), open)),
                    ChamberProtectionService.get()::authorizedMutation,
                    frame,
                    controllerPos -> ChamberControllerBlock.refreshState(world, controllerPos));
            if (toggled) setActivationBlocked(world, frame, false);
            return toggled ? Result.TOGGLED : Result.REJECTED;
        } catch (IllegalStateException failure) {
            blockActivation(world, frame, failure);
            return Result.ROLLBACK_FAILED;
        }
    }

    /**
     * {@link ChamberDoorService#applyTarget} 的世界版本；交易與失敗處理與 {@link #toggle} 相同。順序：server thread、
     * 量子膜軸、相關 chunk 都是 FULL（只查詢、不載入），通過之後才讀寫門格。
     */
    static ChamberDoorService.ApplyResult apply(ServerWorld world, ChamberFrame frame, ChamberDoorService.DoorState target) {
        Objects.requireNonNull(world, "world");
        if (!world.getServer().isOnThread()) throw new IllegalStateException("門的目標狀態只能在 server thread 套用");
        ChamberDoorService.requireFits(frame, target);
        if (!doorChunksLoaded(world, frame)) {
            return new ChamberDoorService.ApplyResult(ChamberDoorService.ApplyResult.Outcome.NOT_LOADED, activationBlocked(world, frame));
        }
        return ChamberPowerCoordinator.deferActivation(() -> applyDeferred(world, frame, target));
    }

    /**
     * 門板 25 格向水平各方向外擴 {@value #DOOR_NEIGHBORHOOD} 格（門平面 local x −2～8、法線方向 local z −3～3；Controller 在
     * local (3,6,0)，也在其中）涵蓋的 chunk 是否都已是 FULL；只用 {@code getWorldChunk} 查詢，不載入。高度不影響 chunk。
     *
     * <p>保證的範圍：寫入門格本身、原版一層 neighbor／shape update（相鄰六格），以及這些鄰格一層的紅石讀取（水平 ±3，與
     * {@link ChamberRedstoneService#powerNeighborhoodLoaded} 的專案慣例相同；例如門外或艙內的紅石元件、強充能、掉落的掛件）
     * 都只會碰到已是 FULL 的 chunk，不會同步載入。更遠的連鎖反應（例如紅石沿線路一路傳遞）不在保證內。
     * 每個軸最多跨 2 個 chunk，所以最多查 4 個 chunk。
     */
    private static boolean doorChunksLoaded(ServerWorld world, ChamberFrame frame) {
        BlockPos first = ChamberGeometry.localToWorld(frame, 1, 1, 0);
        BlockPos last = ChamberGeometry.localToWorld(frame, ChamberGeometry.OUTER_SIZE - 2, 1, 0);
        int minX = Math.min(first.getX(), last.getX()) - DOOR_NEIGHBORHOOD;
        int maxX = Math.max(first.getX(), last.getX()) + DOOR_NEIGHBORHOOD;
        int minZ = Math.min(first.getZ(), last.getZ()) - DOOR_NEIGHBORHOOD;
        int maxZ = Math.max(first.getZ(), last.getZ()) + DOOR_NEIGHBORHOOD;
        var manager = world.getChunkManager();
        for (int chunkX = minX >> 4; chunkX <= maxX >> 4; chunkX++) {
            for (int chunkZ = minZ >> 4; chunkZ <= maxZ >> 4; chunkZ++) {
                if (manager.getWorldChunk(chunkX, chunkZ) == null) return false;
            }
        }
        return true;
    }

    private static ChamberDoorService.ApplyResult applyDeferred(ServerWorld world, ChamberFrame frame,
                                                               ChamberDoorService.DoorState target) {
        ChamberDoorService.ApplyResult.Outcome outcome;
        try {
            outcome = new ChamberDoorService().apply(
                    pos -> doorAt(world, pos),
                    (pos, state) -> world.setBlockState(pos,
                            QuantumBulkheadBlock.withDoor(world.getBlockState(pos), state.open(), state.membrane())),
                    ChamberProtectionService.get()::authorizedMutation,
                    frame,
                    target,
                    controllerPos -> ChamberControllerBlock.refreshState(world, controllerPos));
            if (outcome == ChamberDoorService.ApplyResult.Outcome.APPLIED) setActivationBlocked(world, frame, false);
        } catch (IllegalStateException failure) {
            blockActivation(world, frame, failure);
            outcome = ChamberDoorService.ApplyResult.Outcome.ROLLBACK_FAILED;
        }
        return new ChamberDoorService.ApplyResult(outcome, activationBlocked(world, frame));
    }

    private static boolean activationBlocked(ServerWorld world, ChamberFrame frame) {
        var controller = loadedController(world, frame);
        return controller != null && controller.activationBlocked();
    }

    /** 門格目前實際生效的 open＋membrane；不是 bulkhead 時回 null。 */
    private static ChamberDoorService.DoorState doorAt(ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isOf(ModBlocks.QUANTUM_BULKHEAD)) return null;
        return new ChamberDoorService.DoorState(state.get(QuantumBulkheadBlock.OPEN), QuantumBulkheadBlock.membrane(state));
    }

    /** rollback 失敗：必須在最外層 finally 執行延後刷新以前發布故障，不能只依最終 sealed 推斷成功。 */
    private static void blockActivation(ServerWorld world, ChamberFrame frame, IllegalStateException failure) {
        setActivationBlocked(world, frame, true);
        ChamberControllerBlock.refreshState(world, frame.controllerPos());
        LOGGER.error("整面門控 rollback 失敗，frame {}：{}", frame, failure.getMessage(), failure);
    }

    private static void setActivationBlocked(ServerWorld world, ChamberFrame frame, boolean blocked) {
        var controller = loadedController(world, frame);
        if (controller != null) controller.setActivationBlocked(blocked);
    }

    private static @Nullable ChamberControllerBlockEntity loadedController(ServerWorld world, ChamberFrame frame) {
        var pos = frame.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller
                && controller.getWorld() == world && !controller.isRemoved()) return controller;
        return null;
    }

    enum Result {
        TOGGLED,
        REJECTED,
        ROLLBACK_FAILED,
        /** M5 塌縮流程的攔截已處理這次開關門（門由它改），沒有切換。 */
        INTERCEPTED,
        /** M5 塌縮流程的攔截拒絕這次開關門：門不變。 */
        REFUSED
    }
}
