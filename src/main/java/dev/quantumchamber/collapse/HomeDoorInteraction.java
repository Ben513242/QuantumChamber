package dev.quantumchamber.collapse;

import java.util.Optional;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * M7 T5-4：回程走廊 HOME 門的互動（spec §4.2）。RED 腳手架（C1）：一律回 empty，交回既有流程。
 */
public final class HomeDoorInteraction {
    private HomeDoorInteraction() {}

    /** {@code ChamberCollapsePort.HomeDoors} 的實作；不是 HOME 門格時回 empty。 */
    public static Optional<ActionResult> onUse(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        return Optional.empty();
    }
}
