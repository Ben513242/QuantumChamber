package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import java.util.Objects;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 共用世界交易與失敗處理；互動回應政策仍由入口決定。 */
final class WorldChamberDoorAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    private WorldChamberDoorAdapter() {}

    static Result toggle(ServerWorld world, WorldChamberBlockView view, ChamberFrame frame) {
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

    /** {@link ChamberDoorService#applyTarget} 的世界版本；交易與失敗處理與 {@link #toggle} 相同。 */
    static ChamberDoorService.ApplyResult apply(ServerWorld world, ChamberFrame frame, ChamberDoorService.DoorState target) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(target, "target");
        if (!world.getServer().isOnThread()) throw new IllegalStateException("門的目標狀態只能在 server thread 套用");
        return ChamberPowerCoordinator.deferActivation(() -> applyDeferred(world, frame, target));
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
        var controller = loadedController(world, frame);
        return new ChamberDoorService.ApplyResult(outcome, controller != null && controller.activationBlocked());
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
        ROLLBACK_FAILED
    }
}
