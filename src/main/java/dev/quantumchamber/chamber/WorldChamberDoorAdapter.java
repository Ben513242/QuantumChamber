package dev.quantumchamber.chamber;

import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 共用世界交易與失敗處理；互動回應政策仍由入口決定。 */
final class WorldChamberDoorAdapter {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    private WorldChamberDoorAdapter() {}

    static Result toggle(ServerWorld world, WorldChamberBlockView view, ChamberFrame frame) {
        try {
            boolean toggled = new ChamberDoorService().toggle(
                    view,
                    (target, open) -> world.setBlockState(target,
                            world.getBlockState(target).with(QuantumBulkheadBlock.OPEN, open)),
                    ChamberProtectionService.get()::authorizedMutation,
                    frame,
                    controllerPos -> ChamberControllerBlock.refreshState(world, controllerPos));
            return toggled ? Result.TOGGLED : Result.REJECTED;
        } catch (IllegalStateException failure) {
            ChamberControllerBlock.refreshState(world, frame.controllerPos());
            LOGGER.error("整面門控 rollback 失敗，frame {}：{}", frame, failure.getMessage(), failure);
            return Result.ROLLBACK_FAILED;
        }
    }

    enum Result {
        TOGGLED,
        REJECTED,
        ROLLBACK_FAILED
    }
}
