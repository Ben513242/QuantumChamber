package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.Optional;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;

/**
 * M7 T5-4：回程走廊 HOME 門的互動（spec §4.2；plan :321）。{@code ChamberCollapsePort.HomeDoors} 的實作，由 {@code CollapseService.initialize()} 註冊，
 * {@code QuantumBulkheadBlock.onUse} 在候選門之前呼叫。只是轉接：以 {@link CorridorPageManager#homeDoorAt} 辨識 HOME 門格（current、pending、退休中的實例都算，
 * 一律消耗互動），交 {@link SuperpositionSessionManager#requestHomeSelect} 受理；選門請求的狀態與續行都在 gateway（與 START_CHECK 同一套 tick、guard 與隔離）。
 */
public final class HomeDoorInteraction {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("quantumchamber");

    private HomeDoorInteraction() {}

    /** 不是走廊世界的 HOME 門格時回 empty（交回既有流程）；是 HOME 門格時回受理結果（CONSUME 或 FAIL）。不丟出例外。 */
    public static Optional<ActionResult> onUse(ServerWorld world, BlockPos pos, ServerPlayerEntity player) {
        if (!world.getRegistryKey().equals(SuperpositionWorld.KEY)) return Optional.empty();
        var server = world.getServer();
        if (!server.isOnThread()) return Optional.of(ActionResult.FAIL);
        try {
            var hit = CorridorPageManager.forServer(server).homeDoorAt(world, pos);
            if (hit.isEmpty()) return Optional.empty();
            if (!(ChamberSessions.gateway() instanceof SuperpositionSessionManager sessions)) return Optional.of(ActionResult.FAIL);
            return Optional.of(sessions.requestHomeSelect(world, pos, player, hit.get()));
        } catch (RuntimeException rejected) {
            LOGGER.debug("HOME 門互動拒絕：{}: {}", rejected.getClass().getName(), rejected.getMessage());
            return Optional.of(ActionResult.FAIL);
        }
    }
}
