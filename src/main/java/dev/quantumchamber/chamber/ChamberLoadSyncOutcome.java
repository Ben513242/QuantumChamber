package dev.quantumchamber.chamber;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** 單筆載入同步的處理結果；正式 runtime 不保留 receipt 歷史。 */
public enum ChamberLoadSyncOutcome {
    CONSUMED,
    REQUEUED_NOT_DUE,
    REQUEUED_NEIGHBOR_NOT_READY,
    DROPPED_SERVER_IDENTITY,
    DROPPED_WORLD_IDENTITY,
    DROPPED_REMOVED_BE,
    DROPPED_NO_FULL_CHUNK,
    DROPPED_REPLACED_BE,
    DROPPED_PENDING_TOKEN_MISSING,
    DISCARDED_WORLD_UNLOAD;

    /** 保留實際 instance，不能僅以 registry key 或座標代表身分。 */
    public record Receipt(MinecraftServer server, ServerWorld world,
                          ChamberControllerBlockEntity controller, BlockPos pos,
                          ChamberLoadSyncOutcome outcome) {}
}
