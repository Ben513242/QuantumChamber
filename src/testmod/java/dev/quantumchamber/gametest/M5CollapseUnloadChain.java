package dev.quantumchamber.gametest;

import com.google.gson.JsonParser;
import dev.quantumchamber.gametest.M5CollapseRun.Ending;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor;
import dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.dimension.DimensionType;

/**
 * 僅 testmod（M5 H5，spec §4 H5、§20.10；根因見 implementation note 的 F6-2b 一節）：chunk 卸載的 livelock 防護的回歸 chain。不經 M5 的塌縮流程：原版的機制與 ticket 的
 * 類型無關（F6-2b），所以在 fresh 世界的 overworld，以 testmod 自己的兩種 ticket 操作遠離 spawn 的 chunk。
 * <p>「殭屍」的確定性做法（F6-2b 的條件 (Z)）：chunk C 載入、穩定之後，在一個 tick 結束時移除它的 ticket，並讓 server thread 睡 {@value #LAG_MILLIS} ms，之後的 tick 都落後排程
 * （原版 {@code shouldKeepTicking} 為 false）：{@code unloadChunks} 每個 tick 只把最多 200 個 holder 移到 {@code chunksToUnload}、不排空卸載佇列。C 的 holder 出現在
 * {@code chunksToUnload}（同一個物件，卸載工作還沒執行）的那個 tick 結束時，以另一種 ticket（半徑 {@value #READD_RADIUS}）再載入；下一個 tick 的 {@code setLevel} 由
 * {@code chunksToUnload} 取回同一個 holder，舊的卸載工作留在佇列裡。再載入之前裝上 chunk 讀檔的保留（{@code M5ChunkUnloadHooks.ChunkReadHold}：與 C 距離
 * {@value #HOLD_MIN}～{@value #HOLD_MAX} 的讀檔，F6-2b 的 {@code vr-hold}），涵蓋 C 的 {@code ChunkLoader} 因此一直等著，C 的 refCount 大於 0（條件 (R)）。
 * <ul>
 * <li>{@code unload-takeback}（stop）：C1 形成殭屍、取回同一個 holder → 等殭屍在 refCount &gt; 0 時執行過（原版是重排、修正後是被丟棄）→ 放行讀檔 → C1 回到 FULL、refCount 歸零 →
 *     在 C1 放金塊標記，同一個 tick 移除 C1 的全部 ticket → C1 的 holder 不在 {@code currentChunkHolders}、也不在 {@code chunksToUnload}，chunk 已卸載（{@code unloadedAfterTakeBack}）。
 *     修正前後都必須成立；mixin 改成無條件 cancel 時在這裡 FAIL（卸載工作被取消，holder 一直留在 {@code chunksToUnload}）。</li>
 * <li>{@code unload-shutdown}（stop）：開服時以 raw MCA 讀回 C1 的標記（{@code takenBackChunkSavedOnDisk}：上一個 JVM 在卸載時存下了修改；C1 已卸載，停服時的存檔不會再存它）→
 *     C2 形成殭屍、取回同一個 holder，讀檔保留到停服開始之後才放行（條件 (M)）→ 2 個 tick 之後停服。修正前停服時 {@code unloadChunks(() -> true)} 無上限地排空佇列，殭屍在
 *     refCount &gt; 0 時一直重排自己，持有 refCount 的 {@code ChunkLoader} 要等 main thread 的 deserialize：livelock，由 {@code m5-collapse-probe.ps1} 的逾時處置判 FAIL
 *     （{@code timeout.json}、jstack 停在 {@code unloadChunks → tryUnloadChunk}）。修正後正常停服，停服之後斷言 C2 的舊工作被丟棄、沒有任何被取回的 holder 的工作照原版重排
 *     （{@code staleUnloadTaskDropped}）。</li>
 * </ul>
 * witness：被丟棄的舊工作數（{@code M5ChunkUnloadHooks.UnloadTaskWitness}，testmod 被動計數，不在 production 加 test seam）、停服的耗時（{@code SERVER_STOPPING} 到
 * {@code SERVER_STOPPED}）、取回的是同一個 holder 物件（{@code sameHolderTakenBack}）。witness 在開服時安裝、停服之後移除；讀檔保留經 {@code M5CollapseRun.install}，請求停服之前移除
 * （已保留的讀檔由它自己的 thread 在停服開始之後 {@value #RELEASE_AFTER_STOP_MILLIS} ms 放行）。
 */
final class M5CollapseUnloadChain implements M5CollapseRun.Context {
    /** {@code unload-takeback} 的 chunk C1（遠離 spawn 與 C2）。 */
    static final ChunkPos TAKE_BACK = new ChunkPos(-64, 64);
    /** {@code unload-shutdown} 的 chunk C2（遠離 spawn 與 C1）。 */
    static final ChunkPos LIVELOCK = new ChunkPos(64, -64);
    /** C1 的修改：金塊標記（y 300，地形之上的空氣）。 */
    static final BlockPos MARKER = TAKE_BACK.getBlockPos(8, 300, 8);
    /** 再載入的半徑（F6-2b 的 {@code vr-hold}）。 */
    static final int READD_RADIUS = 8;
    /** 讀檔保留的範圍（與 C 的 Chebyshev 距離）：{@code vr-hold} 的「距離 ≥ 12」，上限是再載入的半徑加上 FULL 的生成範圍（11）。 */
    static final int HOLD_MIN = 12, HOLD_MAX = READD_RADIUS + 11;
    /** 移除 ticket 之後讓 server 落後排程的時間（遠小於原版跳過 tick 的 2 秒）。 */
    static final long LAG_MILLIS = 1000;
    /** 停服開始之後多久放行保留的讀檔（{@code vr-hold}）。 */
    static final long RELEASE_AFTER_STOP_MILLIS = 200;
    /** 殭屍在 refCount &gt; 0 時執行過之前，最多等的 tick 數（修正前後都在 server 追上排程之後的第一次排空執行；無條件 cancel 的 mutation 沒有卸載工作，等滿才繼續）。 */
    private static final int STALE_WAIT_TICKS = 300;
    /** 移除全部 ticket 之後，等卸載完成的 tick 數。 */
    private static final int UNLOAD_WAIT_TICKS = 600;

    private final M5CollapseRun run;
    private ChunkPos chunk;
    /** 被取回的 holder（移除第一個 ticket 時的物件，之後的斷言都以物件同一性比較）。 */
    private ChunkHolder holder;
    private M5ChunkUnloadHooks.UnloadTaskWitness witness;
    private M5ChunkUnloadHooks.ChunkReadHold hold;
    private int settledTicks, waitedTicks, staleAtRelease = -1;
    private long stopRequestedNanos, stoppingNanos;
    private final Map<String, Object> zombie = new LinkedHashMap<>();

    M5CollapseUnloadChain(M5CollapseRun run) {
        this.run = run;
        run.context(this);
    }

    /** {@code unload-takeback}：被取回的 holder 在 refCount 歸零之後仍照常卸載、存檔。 */
    Phase takeBack() {
        chunk = TAKE_BACK;
        var steps = new ArrayList<>(zombieSteps());
        steps.addAll(List.of(
                new Step("staleTaskRan", STALE_WAIT_TICKS + 1, this::staleTaskRan),
                new Step("readsReleased", 1, () -> { hold.release(); return true; }),
                new Step("loadedAgain", 2400, this::loadedAgainThenReleased),
                new Step("unloaded", UNLOAD_WAIT_TICKS + 1, this::unloadedAfterTakeBack),
                new Step("staleTaskSeen", 1, this::staleTaskSeen)));
        return Phase.stop(steps).started(() -> {
            expectFresh("freshTakeBackChunk", MARKER);
            expectFresh("freshLivelockChunk", LIVELOCK.getBlockPos(8, 300, 8));
            installWitness();
        }).afterStop(() -> afterStop(false));
    }

    /** {@code unload-shutdown}：讀回 C1 的修改；C2 形成殭屍、載入仍在進行時停服，必須正常結束。 */
    Phase shutdown(String takeBackPhase) {
        chunk = LIVELOCK;
        var steps = new ArrayList<>(zombieSteps());
        steps.add(new Step("stop", 4, this::readyToStop));
        return Phase.stop(steps).started(() -> {
            readTakeBackReceipt(takeBackPhase);
            expectMarkerOnDisk();
            expectFresh("freshLivelockChunk", LIVELOCK.getBlockPos(8, 300, 8));
            installWitness();
        }).afterStop(() -> afterStop(true));
    }

    /** 兩個 phase 共用：C 載入、穩定 → 移除 ticket 並讓 server 落後排程 → C 的 holder 在 {@code chunksToUnload} 時裝上讀檔保留、以另一種 ticket 再載入 → 取回同一個 holder。 */
    private List<Step> zombieSteps() {
        return List.of(
                new Step("load", 1, () -> { run.source().getChunkManager().addTicket(Tickets.FIRST, chunk, 0, chunk); return true; }),
                new Step("settled", 1200, this::settled),
                new Step("ticketRemoved", 1, this::removeFirstTicket),
                new Step("queuedForUnload", 40, this::queuedForUnloadThenReadded),
                new Step("takenBack", 10, this::takenBack));
    }

    // ---- 殭屍 ----

    /** C 是 FULL、refCount 0，而且 server 連續 20 個 tick 在排程之內（開服的追趕已結束）。 */
    private boolean settled() {
        var current = current();
        if (current == null || run.source().getChunkManager().getWorldChunk(chunk.x, chunk.z) == null || current.getRefCount() != 0 || !onSchedule()) {
            settledTicks = 0;
            return false;
        }
        return ++settledTicks >= 20;
    }

    private boolean removeFirstTicket() throws InterruptedException {
        holder = current();
        run.source().getChunkManager().removeTicket(Tickets.FIRST, chunk, 0, chunk);
        zombie.put("chunk", chunk.x + "," + chunk.z);
        zombie.put("holder", identity(holder));
        zombie.put("ticketRemovedTick", run.server().getTicks());
        Thread.sleep(LAG_MILLIS);
        return true;
    }

    /**
     * C 的 holder 出現在 {@code chunksToUnload}（同一個物件：卸載工作還沒執行）的那個 tick：裝上讀檔保留，以第二種 ticket 再載入。還在 {@code currentChunkHolders}（這個 tick
     * 沒有輪到它）時，若 server 已追上排程就再睡一次，避免同一個 tick 移出又排空。已經不在兩者之中（卸載工作已執行）時，殭屍無法形成：FAIL。
     */
    private boolean queuedForUnloadThenReadded() throws InterruptedException {
        var queued = toUnload();
        var current = current();
        if (queued == null) {
            if (current != holder) throw new AssertionError("zombieFormed：再載入之前 C 的 holder 已卸載或換了物件：current=" + identity(current) + " holder=" + identity(holder));
            if (onSchedule()) Thread.sleep(LAG_MILLIS);
            return false;
        }
        run.expect("sameHolderQueuedForUnload", queued == holder && current == null, () -> "toUnload=" + identity(queued) + " current=" + identity(current) + " holder=" + identity(holder));
        zombie.put("queuedTick", run.server().getTicks());
        zombie.put("unloadQueueAtReadd", queue().size());
        zombie.put("behindMillisAtReadd", behindMillis());
        hold = run.install(M5ChunkUnloadHooks.CHUNK_READ_HOLD, new M5ChunkUnloadHooks.ChunkReadHold(run.source(), chunk, HOLD_MIN, HOLD_MAX));
        hold.releaseAfterStop(run.server(), RELEASE_AFTER_STOP_MILLIS);
        run.source().getChunkManager().addTicket(Tickets.SECOND, chunk, READD_RADIUS, chunk);
        return true;
    }

    /** 下一個 tick 的 {@code setLevel} 由 {@code chunksToUnload} 取回同一個 holder 物件（舊的卸載工作仍在佇列）。 */
    private boolean takenBack() {
        var current = current();
        if (current == null) return false;
        run.expect("sameHolderTakenBack", current == holder && toUnload() == null, () -> "current=" + identity(current) + " toUnload=" + identity(toUnload()) + " holder=" + identity(holder));
        zombie.put("takenBackTick", run.server().getTicks());
        zombie.put("refCountAtTakeBack", holder.getRefCount());
        zombie.put("unloadQueueAtTakeBack", queue().size());
        run.proof.put("zombie", zombie);
        return true;
    }

    // ---- unload-takeback ----

    /** 殭屍在 refCount &gt; 0 時執行過（witness：被丟棄或照原版重排至少一次），或等滿 {@value #STALE_WAIT_TICKS} 個 tick。之後放行讀檔。 */
    private boolean staleTaskRan() {
        long stale = witness.dropped(chunk) + witness.rescheduled(chunk);
        if (stale < 1 && ++waitedTicks < STALE_WAIT_TICKS) return false;
        staleAtRelease = (int) Math.min(Integer.MAX_VALUE, stale);
        run.expect("readsHeldWhileReferenced", hold.hits() > 0 && hold.pending() > 0 && holder.getRefCount() > 0,
                () -> "hits=" + hold.hits() + " pending=" + hold.pending() + " refCount=" + holder.getRefCount());
        zombie.put("staleBeforeRelease", stale);
        zombie.put("droppedBeforeRelease", witness.dropped(chunk));
        zombie.put("heldReads", hold.hits());
        zombie.put("releaseTick", run.server().getTicks());
        return true;
    }

    /**
     * 放行之後 C1 回到 FULL、refCount 歸零（仍是同一個 holder）：放上金塊標記，同一個 tick 移除 C1 的全部 ticket（之間沒有 {@code unloadChunks}，所以標記不會先被增量存檔）。
     */
    private boolean loadedAgainThenReleased() {
        if (current() != holder || run.source().getChunkManager().getWorldChunk(chunk.x, chunk.z) == null || holder.getRefCount() != 0) return false;
        boolean placed = run.source().setBlockState(MARKER, Blocks.GOLD_BLOCK.getDefaultState(), Block.NOTIFY_ALL);
        run.expect("markerPlaced", placed && run.source().getBlockState(MARKER).isOf(Blocks.GOLD_BLOCK), () -> "標記 " + MARKER.toShortString() + " 是 " + run.source().getBlockState(MARKER));
        run.source().getChunkManager().removeTicket(Tickets.SECOND, chunk, READD_RADIUS, chunk);
        var left = tickets();
        run.expect("noTicketLeft", left.isEmpty(), () -> "C1 仍有 ticket：" + left);
        zombie.put("markerTick", run.server().getTicks());
        waitedTicks = 0;
        return true;
    }

    /** refCount 歸零、ticket 都移除之後，被取回的 holder 照常卸載：不在 {@code currentChunkHolders}、不在 {@code chunksToUnload}，chunk 已卸載。 */
    private boolean unloadedAfterTakeBack() {
        if (current() == null && toUnload() == null && !run.source().getChunkManager().isChunkLoaded(chunk.x, chunk.z)) {
            run.expect("unloadedAfterTakeBack", true, () -> "");
            zombie.put("unloadedTick", run.server().getTicks());
            return true;
        }
        if (++waitedTicks < UNLOAD_WAIT_TICKS) return false;
        throw new AssertionError("unloadedAfterTakeBack：移除全部 ticket 之後 " + UNLOAD_WAIT_TICKS + " 個 tick，被取回的 holder 仍沒有卸載：current=" + identity(current())
                + " toUnload=" + identity(toUnload()) + " holder=" + identity(holder) + " refCount=" + holder.getRefCount() + " unloadQueue=" + queue().size());
    }

    /** 放在「照常卸載」之後：殭屍確實在 refCount &gt; 0 時執行過（修正前是重排，修正後是被丟棄；witness 的分項另記在 receipt）。 */
    private boolean staleTaskSeen() {
        run.expect("staleTaskSeenWhileHeld", staleAtRelease >= 1, () -> "放行讀檔之前，C1 被取回的 holder 的舊工作沒有執行過：" + witness.summary());
        return true;
    }

    // ---- unload-shutdown ----

    /** 取回之後 2 個 tick：C2 仍是同一個 holder、refCount &gt; 0，讀檔仍保留著，請求停服。 */
    private boolean readyToStop() {
        if (++waitedTicks < 2) return false;
        run.expect("referencedAtStop", current() == holder && holder.getRefCount() > 0, () -> "current=" + identity(current()) + " refCount=" + holder.getRefCount());
        run.expect("readsHeldAtStop", hold.pending() > 0, () -> "hits=" + hold.hits() + " pending=" + hold.pending());
        zombie.put("stopTick", run.server().getTicks());
        zombie.put("refCountAtStop", holder.getRefCount());
        zombie.put("heldReadsAtStop", hold.pending());
        zombie.put("unloadQueueAtStop", queue().size());
        zombie.put("behindMillisAtStop", behindMillis());
        zombie.put("droppedBeforeStop", witness.dropped(chunk));
        stopRequestedNanos = System.nanoTime();
        return true;
    }

    /** 讀回 {@code unload-takeback} 的 {@code final.json}：PASS、ending STOP、phase 與 nonce 相符，startupNonce 與這個 JVM 不同。 */
    private void readTakeBackReceipt(String phase) throws Exception {
        var evidence = run.phaseEvidence(phase);
        var path = evidence.resolve("final.json");
        run.expect("takeBackReceiptPresent", Files.exists(path) && !Files.exists(evidence.resolve("halt.json")), () -> "沒有正常停止的 receipt：" + evidence);
        var receipt = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        run.expect("takeBackReceiptMatches", "PASS".equals(M5CollapseRun.text(receipt, "status")) && Ending.STOP.name().equals(M5CollapseRun.text(receipt, "ending"))
                && phase.equals(M5CollapseRun.text(receipt, "phase")) && run.config.nonce().equals(M5CollapseRun.text(receipt, "nonce")),
                () -> "receipt 不符：status=" + M5CollapseRun.text(receipt, "status") + " ending=" + M5CollapseRun.text(receipt, "ending"));
        run.expect("differentStartupNonce", !run.config.startupNonce().equals(M5CollapseRun.text(receipt, "startupNonce")), () -> "與上一個 phase 相同的 startupNonce");
        run.proof.put("takeBackReceipt", Map.of("phase", phase, "finalSha256", M5CollapseDiskEvidence.sha256(path), "finalPid", receipt.get("pid").getAsLong(),
                "finalStartupNonce", M5CollapseRun.text(receipt, "startupNonce")));
    }

    /**
     * 開服時（任何 chunk 載入 C1 之前）以 raw MCA 讀 C1 的標記：上一個 JVM 在被取回的 holder 卸載時存下的金塊。讀到的結果另寫進 {@code marker-on-disk.json}（修正前這個 phase
     * 卡在停服、不會寫出 {@code final.json}，RED 以它證明這個斷言在舊程式上也成立）。
     */
    private void expectMarkerOnDisk() throws Exception {
        var disk = M5CollapseDiskEvidence.block(overworldDirectory(), MARKER);
        var record = new LinkedHashMap<String, Object>();
        record.put("marker", MARKER.toShortString());
        record.put("name", disk.name());
        record.put("properties", disk.properties());
        record.put("startupNonce", run.config.startupNonce());
        M5CollapseRun.writeDurably(run.config.evidence().resolve("marker-on-disk.json"), M5CollapseRun.JSON.toJson(record));
        run.expect("takenBackChunkSavedOnDisk", M5CollapseFixture.GOLD_BLOCK.equals(disk.name()), () -> "磁碟上的標記 " + MARKER.toShortString() + " 是 " + disk);
        run.proof.put("markerOnDisk", record);
    }

    // ---- witness、停服 ----

    private void installWitness() {
        witness = new M5ChunkUnloadHooks.UnloadTaskWitness(run.source(), List.of(chunk));
        M5ChunkUnloadHooks.UNLOAD_TASK_WITNESS.install(witness, null);
        run.onStopping(() -> stoppingNanos = System.nanoTime());
    }

    /** {@code SERVER_STOPPED}：記下停服的耗時與 witness，移除 witness；{@code unload-shutdown} 另斷言 C2 的舊工作被丟棄、沒有被取回的 holder 的工作照原版重排。 */
    private void afterStop(boolean shutdownPhase) {
        long stopped = System.nanoTime();
        try {
            run.proof.put("shutdownMillis", stoppingNanos == 0 ? -1 : (stopped - stoppingNanos) / 1_000_000L);
            run.proof.put("stopRequestToStoppedMillis", stopRequestedNanos == 0 ? -1 : (stopped - stopRequestedNanos) / 1_000_000L);
            if (witness == null) return;
            var summary = witness.summary();
            run.proof.put("unloadTaskWitness", summary);
            if (shutdownPhase) {
                run.expect("staleUnloadTaskDropped", witness.dropped(chunk) >= 1 && witness.rescheduled(chunk) == 0 && witness.rescheduled() == 0 && witness.offThread() == 0,
                        () -> "witness=" + summary);
            }
        } finally {
            if (witness != null) M5ChunkUnloadHooks.UNLOAD_TASK_WITNESS.remove(witness);
        }
    }

    // ---- 查詢 ----

    private ServerChunkLoadingManagerTestAccessor manager() {
        return (ServerChunkLoadingManagerTestAccessor) run.source().getChunkManager().chunkLoadingManager;
    }

    private ChunkHolder current() { return manager().quantumchamberTest$currentChunkHolders().get(chunk.toLong()); }

    private ChunkHolder toUnload() { return manager().quantumchamberTest$chunksToUnload().get(chunk.toLong()); }

    private java.util.Queue<Runnable> queue() { return manager().quantumchamberTest$unloadTaskQueue(); }

    private List<String> tickets() {
        var tickets = ((ChunkTicketManagerTestAccessor) run.source().getChunkManager().chunkLoadingManager.getTicketManager())
                .quantumchamberTest$getTicketsByPosition().get(chunk.toLong());
        var out = new ArrayList<String>();
        if (tickets != null) for (var ticket : tickets) out.add(ticket.toString());
        return out;
    }

    /** 這個 tick 目前在排程之內（原版 {@code shouldKeepTicking} 的時間條件）。 */
    private boolean onSchedule() { return Util.getMeasuringTimeNano() < run.server().getTimeReference(); }

    private long behindMillis() { return (Util.getMeasuringTimeNano() - run.server().getTimeReference()) / 1_000_000L; }

    private Path overworldDirectory() { return DimensionType.getSaveDirectory(run.source().getRegistryKey(), run.saveRoot()); }

    /** fresh 世界：{@code pos} 所在的 chunk 在磁碟上不存在。 */
    private void expectFresh(String name, BlockPos pos) throws Exception {
        var disk = M5CollapseDiskEvidence.block(overworldDirectory(), pos);
        run.expect(name, disk.equals(M5CollapseDiskEvidence.DiskBlock.MISSING), () -> pos.toShortString() + " 在磁碟上是 " + disk);
    }

    private static String identity(ChunkHolder holder) {
        return holder == null ? "null" : "holder@" + Integer.toHexString(System.identityHashCode(holder)) + "[refCount=" + holder.getRefCount() + "]";
    }

    /** testmod 自己的兩種 chunk ticket（level FULL、不持久化；只在這條 chain 建立）：第一次載入與再載入。 */
    private static final class Tickets {
        static final ChunkTicketType<ChunkPos> FIRST = ChunkTicketType.create("quantumchamber_h5_first", Comparator.comparingLong(ChunkPos::toLong));
        static final ChunkTicketType<ChunkPos> SECOND = ChunkTicketType.create("quantumchamber_h5_readd", Comparator.comparingLong(ChunkPos::toLong));
    }

    // ---- runner 的 Context ----

    @Override public String diagnostic() {
        try {
            return "chunk=" + chunk + " holder=" + identity(holder) + " current=" + identity(chunk == null ? null : current()) + " toUnload="
                    + identity(chunk == null ? null : toUnload()) + " unloadQueue=" + queue().size() + " witness=" + (witness == null ? "-" : witness.summary());
        } catch (RuntimeException failure) {
            return "（診斷失敗：" + failure + "）";
        }
    }

    @Override public boolean universeActive() { return false; }

    @Override public Map<String, Object> diskSummary() { return Map.of(); }
}
