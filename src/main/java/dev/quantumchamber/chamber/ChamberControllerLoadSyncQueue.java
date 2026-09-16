package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 載入同步與 periodic block ticks 分離；每個 queue 僅由所屬 server thread 存取。 */
public final class ChamberControllerLoadSyncQueue {
    private static final Map<MinecraftServer, ArrayDeque<PendingLoad>> PENDING = new ConcurrentHashMap<>();
    private static final ChamberRedstoneService REDSTONE = new ChamberRedstoneService();

    private ChamberControllerLoadSyncQueue() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(ChamberControllerLoadSyncQueue::processDue);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> PENDING.remove(server));
    }

    public static void enqueue(ServerWorld world, ChamberControllerBlockEntity controller) {
        MinecraftServer server = world.getServer();
        requireServerThread(server);
        controller.markLoadSyncPending();
        PENDING.computeIfAbsent(server, ignored -> new ArrayDeque<>()).addLast(new PendingLoad(
                server, world.getRegistryKey(), world, controller.getPos().toImmutable(), controller,
                (long) server.getTicks() + 1));
    }

    private static void processDue(MinecraftServer server) {
        requireServerThread(server);
        ArrayDeque<PendingLoad> queue = PENDING.get(server);
        if (queue == null) return;
        // 固定本輪數量；檢查 BE 時若觸發新的 load event，新項目留到之後的 tick。
        int remaining = queue.size();
        while (remaining-- > 0) {
            PendingLoad entry = queue.removeFirst();
            if (server.getTicks() < entry.dueTick()) {
                queue.addLast(entry);
                continue;
            }
            if (entry.server() != server || entry.world().getServer() != server
                    || server.getWorld(entry.worldKey()) != entry.world()
                    || entry.controller().isRemoved() || entry.controller().getWorld() != entry.world()) continue;
            // getWorldChunk 只查已完成的 FULL chunk；不存在即丟棄，不等待也不強載。
            var chunk = entry.world().getChunkManager().getWorldChunk(entry.pos().getX() >> 4, entry.pos().getZ() >> 4);
            if (chunk == null || !chunk.getBlockState(entry.pos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                    || chunk.getBlockEntity(entry.pos()) != entry.controller()) continue;
            if (entry.controller().consumeLoadSyncPending()) {
                REDSTONE.onLoad(entry.world(), entry.pos());
                ChamberControllerBlock.scheduleRefresh(entry.world(), entry.pos());
            }
        }
        if (queue.isEmpty()) PENDING.remove(server, queue);
    }

    static boolean hasPending(MinecraftServer server, ChamberControllerBlockEntity controller) {
        requireServerThread(server);
        var queue = PENDING.get(server);
        return queue != null && queue.stream().anyMatch(entry -> entry.controller() == controller);
    }

    static int pendingCount(MinecraftServer server) {
        requireServerThread(server);
        var queue = PENDING.get(server);
        return queue == null ? 0 : queue.size();
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("Controller load queue 必須在所屬 server thread 執行");
    }

    private record PendingLoad(MinecraftServer server, RegistryKey<World> worldKey, ServerWorld world,
                               BlockPos pos, ChamberControllerBlockEntity controller, long dueTick) {}
}
