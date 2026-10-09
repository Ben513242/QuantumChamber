package dev.quantumchamber.gametest;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 僅 testmod（M5 H5；F6-2c Step 0 由 {@link M5TestHooks} 拆出，H5 quality Minor 6）：chunk 卸載的 livelock 防護（H5）的兩個 testmod hook，mixin 讀取的靜態狀態。release JAR 不含此類。
 * 兩個都沿用 {@link M5TestHooks.Slot}（同一時間只有一個持有者，被占用時再安裝會丟出、不覆蓋），只由 M5 collapse probe 的 unload chain（{@code M5CollapseUnloadChain}）安裝。
 * <ul>
 * <li>chunk 讀檔的保留（{@link #CHUNK_READ_HOLD}，F6-2b 的 {@code vr-hold}）：{@code ChunkReadHoldMixin} 在 {@code ServerChunkLoadingManager.getUpdatedChunkNbt} 回傳時呼叫
 *     {@link #heldChunkRead}，指定範圍的讀檔結果留到放行才交出，重現「停服開始時仍有要等 main thread 的 chunk 載入」。經 {@code M5CollapseRun.install} 安裝（probe 請求停服之前
 *     移除；已保留的讀檔由它自己的 thread 在停服開始之後放行）。</li>
 * <li>卸載工作的 witness（{@link #UNLOAD_TASK_WITNESS}）：{@code ChunkUnloadTaskWitnessMixin} 把 {@code tryUnloadChunk} 的 lambda 對被取回的 holder 的重排交給
 *     {@link #staleReschedule}（由它呼叫原本的方法），{@code tryUnloadChunk} 的本體另呼叫 {@link #unloadTaskBody}，被動計數被丟棄與照原版重排的工作。開服時安裝、
 *     停服之後（{@code SERVER_STOPPED}）移除，所以停服期間的丟棄也計入。</li>
 * </ul>
 */
public final class M5ChunkUnloadHooks {
    private M5ChunkUnloadHooks() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    /** chunk 讀檔的保留（H5；只由 M5 collapse probe 的 unload chain 經 {@code M5CollapseRun.install} 安裝）。 */
    static final M5TestHooks.Slot<ChunkReadHold> CHUNK_READ_HOLD = new M5TestHooks.Slot<>("chunk 讀檔的保留");
    /** 卸載工作的被動 witness（H5；只由 M5 collapse probe 的 unload chain 安裝，停服之後移除）。 */
    static final M5TestHooks.Slot<UnloadTaskWitness> UNLOAD_TASK_WITNESS = new M5TestHooks.Slot<>("卸載工作的 witness");

    /**
     * {@code ChunkReadHoldMixin}：{@code ServerChunkLoadingManager.getUpdatedChunkNbt}（chunk 讀檔）回傳時呼叫（H5）。裝著的 {@link ChunkReadHold} 符合時回傳一個延後完成的
     * future（讀檔本身照常進行，只是結果留到 {@link ChunkReadHold#release} 才交出）；沒有裝或不符合時原樣回傳 {@code original}。不丟出例外。
     */
    public static <T> CompletableFuture<T> heldChunkRead(ServerWorld world, ChunkPos pos, CompletableFuture<T> original) {
        var hold = CHUNK_READ_HOLD.get();
        return hold == null ? original : hold.hold(world, pos, original);
    }

    /** {@code ChunkUnloadTaskWitnessMixin}：這個世界是否裝著卸載工作的 witness（沒有時 mixin 不做任何判斷，直接呼叫原本的方法）。 */
    public static boolean watchingUnloadTasks(ServerWorld world) {
        var witness = UNLOAD_TASK_WITNESS.get();
        return witness != null && witness.world == world;
    }

    /**
     * {@code ChunkUnloadTaskWitnessMixin}：卸載工作（{@code tryUnloadChunk} 的 lambda）因 holder 還不能存而重排、而且這個 holder 已不在 {@code chunksToUnload}（已被取回）時，
     * 由這裡呼叫 {@code original}（原本的 {@code tryUnloadChunk}，恰好一次；H5）。裝著的 witness 比對呼叫前後 {@code tryUnloadChunk} 本體的進入次數
     * （{@link #unloadTaskBody}），一次記下這條重排是被丟棄（沒有進入本體）或照原版重排（進入了本體），兩者都以同一個 {@code pos} 計數（F6-2c Step 0，H5 quality Minor 3：
     * 取代呼叫前後各通知一次、靠跨呼叫的狀態配對的寫法）。沒有裝 witness，或不是它的世界時，只呼叫 {@code original}。
     */
    public static void staleReschedule(ServerWorld world, long pos, Runnable original) {
        var witness = UNLOAD_TASK_WITNESS.get();
        if (witness == null || witness.world != world) {
            original.run();
            return;
        }
        witness.staleReschedule(pos, original);
    }

    /** {@code ChunkUnloadTaskWitnessMixin}：進入 {@code tryUnloadChunk} 的本體（第一個指令 {@code getSavingFuture}）時呼叫（H5）。 */
    public static void unloadTaskBody(ServerWorld world) {
        var witness = UNLOAD_TASK_WITNESS.get();
        if (witness != null && witness.world == world) witness.bodyEntered();
    }

    /**
     * chunk 讀檔的保留（H5；{@link #CHUNK_READ_HOLD}）：{@code world} 裡與 {@code center} 的 Chebyshev 距離在 [{@code minDistance}, {@code maxDistance}] 之間的 chunk 讀檔
     * （{@code getUpdatedChunkNbt}），讀檔本身照常進行，結果留到 {@link #release} 才交出：等同停服開始時仍在進行中、之後要等 main thread 才能完成的 chunk 載入（F6-2b 的條件
     * (M)；真正的磁碟延遲無法決定性重現，F6-2b 的 {@code vr-hold}）。server thread 自己的讀檔（例如存 proto chunk 時 {@code isLevelChunk} 的同步 {@code join}）不保留，否則
     * server thread 會卡在自己的 join。放行之後的讀檔照常。{@link #releaseAfterStop} 另開一條 daemon thread，在 server 不再 running 之後放行（停服開始時仍保留）。
     * {@code hits()} 是保留過的讀檔數（README：以 mixin 注入的 test 必須有 hits 一類的 witness）。
     */
    static final class ChunkReadHold {
        private final ServerWorld world;
        private final ChunkPos center;
        private final int minDistance, maxDistance;
        private final List<Runnable> held = new ArrayList<>();
        private int hits;
        private boolean released;

        ChunkReadHold(ServerWorld world, ChunkPos center, int minDistance, int maxDistance) {
            this.world = Objects.requireNonNull(world, "world");
            this.center = Objects.requireNonNull(center, "center");
            if (minDistance < 1 || maxDistance < minDistance) throw new IllegalArgumentException("保留的距離不合法：" + minDistance + ".." + maxDistance);
            this.minDistance = minDistance;
            this.maxDistance = maxDistance;
        }

        <T> CompletableFuture<T> hold(ServerWorld target, ChunkPos pos, CompletableFuture<T> original) {
            if (target != world || world.getServer().isOnThread()) return original;
            int distance = pos.getChebyshevDistance(center);
            if (distance < minDistance || distance > maxDistance) return original;
            var delayed = new CompletableFuture<T>();
            Runnable release = () -> original.whenComplete((value, failure) -> {
                if (failure != null) delayed.completeExceptionally(failure);
                else delayed.complete(value);
            });
            synchronized (this) {
                if (released) return original;
                held.add(release);
                hits++;
            }
            return delayed;
        }

        /** 交出保留中的讀檔結果，之後不再保留（可以重複呼叫）。 */
        void release() {
            List<Runnable> releases;
            synchronized (this) {
                if (released) return;
                released = true;
                releases = List.copyOf(held);
                held.clear();
            }
            LOGGER.info("H5 chunk 讀檔的保留放行：reads={}", releases.size());
            releases.forEach(Runnable::run);
        }

        /** 另開一條 daemon thread：{@code server} 不再 running（停服開始）之後再等 {@code delayMillis} 毫秒才 {@link #release}。 */
        void releaseAfterStop(MinecraftServer server, long delayMillis) {
            var thread = new Thread(() -> {
                try {
                    while (server.isRunning()) Thread.sleep(10);
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                release();
            }, "quantumchamber-h5-read-hold");
            thread.setDaemon(true);
            thread.start();
        }

        /** 保留過的讀檔數。 */
        synchronized int hits() { return hits; }

        /** 還沒有交出的讀檔數。 */
        synchronized int pending() { return held.size(); }

        synchronized boolean released() { return released; }
    }

    /**
     * 卸載工作的被動 witness（H5；{@link #UNLOAD_TASK_WITNESS}）：只計數，不改變原版或 production 的行為（mixin 照常呼叫原本的方法一次）。「被取回之後的重排」是
     * {@code tryUnloadChunk} 的 lambda 因 holder 還不能存（refCount &gt; 0）而再呼叫 {@code tryUnloadChunk}，而且當時 {@code chunksToUnload} 裡已不是這個 holder（同一個 chunk
     * 在卸載工作執行之前被另一個 ticket 載回）。原版的這條重排會一直重排自己（F6-2b 的殭屍）；production 的 {@code ServerChunkLoadingManagerStaleUnloadMixin} 在
     * {@code tryUnloadChunk} 的 HEAD 取消它。witness 以「這次呼叫有沒有進入 {@code tryUnloadChunk} 的本體」區分：沒有進入是被丟棄（{@link #dropped}），進入了是照原版重排
     * （{@link #rescheduled}），所以不依賴 testmod 與 production 在同一個 HEAD 的注入順序。{@code watched} 的 chunk 另外分開計數。卸載佇列只在 server thread 上執行，
     * 判斷只在 server thread 上做，也只比對 server thread 上的本體次數（其他 thread 的重排只計入 {@link #offThread}，不判斷丟棄與否）。
     */
    static final class UnloadTaskWitness {
        final ServerWorld world;
        private final Set<Long> watched;
        private final AtomicLong dropped = new AtomicLong(), rescheduled = new AtomicLong(), bodies = new AtomicLong(), offThread = new AtomicLong();
        /** server thread 上進入 {@code tryUnloadChunk} 本體的次數（只在 server thread 上讀寫）：{@link #staleReschedule} 比對它的前後。 */
        private long serverThreadBodies;
        private final Map<Long, AtomicLong> droppedAt = new ConcurrentHashMap<>(), rescheduledAt = new ConcurrentHashMap<>();

        UnloadTaskWitness(ServerWorld world, Collection<ChunkPos> watched) {
            this.world = Objects.requireNonNull(world, "world");
            this.watched = watched.stream().map(ChunkPos::toLong).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        /**
         * 被取回 holder 的一次重排（{@code pos}）：呼叫 {@code original} 恰好一次，之間進入了本體就是照原版重排，沒有就是被丟棄。{@code original} 丟出時照樣判斷、計數，
         * 再往外丟。不在 server thread 上時只計入 {@link #offThread}。
         */
        void staleReschedule(long pos, Runnable original) {
            if (!world.getServer().isOnThread()) {
                offThread.incrementAndGet();
                original.run();
                return;
            }
            long before = serverThreadBodies;
            try {
                original.run();
            } finally {
                if (serverThreadBodies > before) count(rescheduled, rescheduledAt, pos);
                else count(dropped, droppedAt, pos);
            }
        }

        void bodyEntered() {
            bodies.incrementAndGet();
            if (world.getServer().isOnThread()) serverThreadBodies++;
        }

        private void count(AtomicLong total, Map<Long, AtomicLong> at, long pos) {
            total.incrementAndGet();
            if (watched.contains(pos)) at.computeIfAbsent(pos, key -> new AtomicLong()).incrementAndGet();
        }

        /** 被丟棄的重排（全部 chunk）。 */
        long dropped() { return dropped.get(); }

        /** 照原版重排的被取回 holder 的工作（全部 chunk）。 */
        long rescheduled() { return rescheduled.get(); }

        /** 進入 {@code tryUnloadChunk} 本體的次數（全部 chunk，含第一次呼叫）。 */
        long bodies() { return bodies.get(); }

        /** 不在 server thread 上的被取回 holder 的重排（沒有判斷丟棄與否；原版不會發生）。 */
        long offThread() { return offThread.get(); }

        /** {@code pos}（必須是 watched）被丟棄的重排。 */
        long dropped(ChunkPos pos) { return at(droppedAt, pos); }

        /** {@code pos}（必須是 watched）照原版重排的次數。 */
        long rescheduled(ChunkPos pos) { return at(rescheduledAt, pos); }

        private long at(Map<Long, AtomicLong> counts, ChunkPos pos) {
            if (!watched.contains(pos.toLong())) throw new IllegalArgumentException("沒有分開計數的 chunk：" + pos);
            var count = counts.get(pos.toLong());
            return count == null ? 0 : count.get();
        }

        /** 摘要（寫進 receipt）。 */
        Map<String, Object> summary() {
            var out = new LinkedHashMap<String, Object>();
            out.put("dropped", dropped());
            out.put("rescheduled", rescheduled());
            out.put("bodies", bodies());
            out.put("offThread", offThread());
            for (long pos : watched) {
                var chunk = new ChunkPos(pos);
                out.put(chunk.x + "," + chunk.z, Map.of("dropped", dropped(chunk), "rescheduled", rescheduled(chunk)));
            }
            return out;
        }
    }
}
