package dev.quantumchamber.chamber;

import net.minecraft.server.world.ServerWorld;
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
                            world.getBlockState(target).with(QuantumBulkheadBlock.OPEN, open)),
                    ChamberProtectionService.get()::authorizedMutation,
                    frame,
                    controllerPos -> ChamberControllerBlock.refreshState(world, controllerPos));
            if (toggled) setActivationBlocked(world, frame, false);
            return toggled ? Result.TOGGLED : Result.REJECTED;
        } catch (IllegalStateException failure) {
            // 必須在最外層 finally 執行延後刷新以前發布故障，不能只依最終 sealed 推斷成功。
            setActivationBlocked(world, frame, true);
            ChamberControllerBlock.refreshState(world, frame.controllerPos());
            LOGGER.error("整面門控 rollback 失敗，frame {}：{}", frame, failure.getMessage(), failure);
            return Result.ROLLBACK_FAILED;
        }
    }

    private static void setActivationBlocked(ServerWorld world, ChamberFrame frame, boolean blocked) {
        var pos = frame.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null && chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller
                && controller.getWorld() == world && !controller.isRemoved()) controller.setActivationBlocked(blocked);
    }

    enum Result {
        TOGGLED,
        REJECTED,
        ROLLBACK_FAILED
    }
}
