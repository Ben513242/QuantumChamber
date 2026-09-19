package dev.quantumchamber.chamber;

import dev.quantumchamber.chamber.ChamberLoadSyncOutcome.Receipt;
import dev.quantumchamber.registry.ModBlocks;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 載入同步與 periodic block ticks 分離；每個 queue 僅由所屬 server thread 存取。 */
public final class ChamberControllerLoadSyncQueue {
    private static final Map<MinecraftServer, ArrayDeque<PendingLoad>> PENDING = new ConcurrentHashMap<>();
    private static final Map<MinecraftServer, CopyOnWriteArrayList<Observer>> OBSERVERS = new ConcurrentHashMap<>();
    private static final Logger LOGGER = LoggerFactory.getLogger(ChamberControllerLoadSyncQueue.class);
    private static final ChamberRedstoneService REDSTONE = new ChamberRedstoneService();

    private ChamberControllerLoadSyncQueue() {}

    public static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(ChamberControllerLoadSyncQueue::processDue);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            PENDING.remove(server);
            OBSERVERS.remove(server);
        });
    }

    public static void enqueue(ServerWorld world, ChamberControllerBlockEntity controller) {
        MinecraftServer server = world.getServer();
        requireServerThread(server);
        controller.markLoadSyncPending();
        ChamberProtectionService.get().requireReconciliation(world, controller.getPos());
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
            ChamberLoadSyncOutcome outcome = processEntry(server, queue, entry);
            publish(server, entry, outcome);
        }
        if (queue.isEmpty()) PENDING.remove(server, queue);
    }

    private static ChamberLoadSyncOutcome processEntry(MinecraftServer server, ArrayDeque<PendingLoad> queue,
                                                       PendingLoad entry) {
        if (server.getTicks() < entry.dueTick()) {
            queue.addLast(entry);
            return ChamberLoadSyncOutcome.REQUEUED_NOT_DUE;
        }
        if (entry.server() != server || entry.world().getServer() != server)
            return ChamberLoadSyncOutcome.DROPPED_SERVER_IDENTITY;
        if (server.getWorld(entry.worldKey()) != entry.world() || entry.controller().getWorld() != entry.world())
            return ChamberLoadSyncOutcome.DROPPED_WORLD_IDENTITY;
        if (entry.controller().isRemoved()) return ChamberLoadSyncOutcome.DROPPED_REMOVED_BE;
        // getWorldChunk 只查已完成的 FULL chunk；不存在即丟棄，不等待也不強載。
        var chunk = entry.world().getChunkManager().getWorldChunk(entry.pos().getX() >> 4, entry.pos().getZ() >> 4);
        if (chunk == null) return ChamberLoadSyncOutcome.DROPPED_NO_FULL_CHUNK;
        if (!chunk.getBlockState(entry.pos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                || chunk.getBlockEntity(entry.pos()) != entry.controller())
            return ChamberLoadSyncOutcome.DROPPED_REPLACED_BE;
        if (!ChamberRedstoneService.powerNeighborhoodLoaded(entry.world(), entry.pos())) {
            // 原 BE 仍有效但輸入尚不可讀；保留 pending 與保護，下一 tick 重試。
            queue.addLast(entry);
            return ChamberLoadSyncOutcome.REQUEUED_NEIGHBOR_NOT_READY;
        }
        if (!entry.controller().consumeLoadSyncPending()) return ChamberLoadSyncOutcome.DROPPED_PENDING_TOKEN_MISSING;
        REDSTONE.onLoad(entry.world(), entry.pos());
        ChamberControllerBlock.scheduleRefresh(entry.world(), entry.pos());
        return ChamberLoadSyncOutcome.CONSUMED;
    }

    static AutoCloseable observe(MinecraftServer server, Consumer<Receipt> consumer) {
        requireServerThread(server);
        Observer observer = new Observer(Objects.requireNonNull(consumer));
        var observers = OBSERVERS.computeIfAbsent(server, ignored -> new CopyOnWriteArrayList<>());
        observers.add(observer);
        return () -> {
            requireServerThread(server);
            // Observer 不實作 equals；重複註冊同一 consumer 仍是獨立生命週期。
            observers.remove(observer);
            if (observers.isEmpty()) OBSERVERS.remove(server, observers);
        };
    }

    private static void publish(MinecraftServer server, PendingLoad entry, ChamberLoadSyncOutcome outcome) {
        var observers = OBSERVERS.get(server);
        if (observers == null) return;
        Receipt receipt = new Receipt(entry.server(), entry.world(), entry.controller(), entry.pos(), outcome);
        for (Observer observer : observers) {
            try {
                observer.consumer.accept(receipt);
            } catch (RuntimeException | Error failure) {
                // Queue 已完成該分支；診斷失敗不得改變 consume/drop/requeue 的權威結果。
                LOGGER.error("Chamber load-sync observer 失敗：{}", outcome, failure);
            }
        }
    }

    static void discardWorld(ServerWorld expected) {
        MinecraftServer server = expected.getServer();
        requireServerThread(server);
        var queue = PENDING.get(server);
        if (queue == null) return;
        var discarded = new ArrayList<PendingLoad>();
        var iterator = queue.iterator();
        while (iterator.hasNext()) {
            PendingLoad entry = iterator.next();
            if (entry.server() == server && entry.world() == expected) {
                iterator.remove();
                discarded.add(entry);
            }
        }
        if (queue.isEmpty()) PENDING.remove(server, queue);
        for (PendingLoad entry : discarded) publish(server, entry, ChamberLoadSyncOutcome.DISCARDED_WORLD_UNLOAD);
    }

    private static final class Observer {
        private final Consumer<Receipt> consumer;

        private Observer(Consumer<Receipt> consumer) {
            this.consumer = consumer;
        }
    }

    static boolean hasPending(MinecraftServer server, ChamberControllerBlockEntity controller) {
        requireServerThread(server);
        var queue = PENDING.get(server);
        return queue != null && queue.stream().anyMatch(entry -> entry.controller() == controller);
    }

    static void discard(ServerWorld world, ChamberControllerBlockEntity controller) {
        MinecraftServer server = world.getServer();
        requireServerThread(server);
        var queue = PENDING.get(server);
        if (queue == null) return;
        queue.removeIf(entry -> entry.world() == world && entry.controller() == controller);
        if (queue.isEmpty()) PENDING.remove(server, queue);
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
