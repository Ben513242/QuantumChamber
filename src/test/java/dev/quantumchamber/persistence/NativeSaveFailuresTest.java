package dev.quantumchamber.persistence;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.StorageIoWorker;
import net.minecraft.world.storage.StorageKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveFailuresTest {
    /**
     * chunk 以 ChunkPos.toLong 表示。RELEVANT 與 OTHER_RELEVANT 在 region (0,-1)；SAME_REGION 是同一 region 內、不在相關範圍的 chunk；
     * OTHER_REGION 在 region (1,0)。
     */
    private static final long RELEVANT=ChunkPos.toLong(3,-7), OTHER_RELEVANT=ChunkPos.toLong(4,-7),
            SAME_REGION=ChunkPos.toLong(31,-32), OTHER_REGION=ChunkPos.toLong(40,12);
    private static final String SOURCE="source=chunk world=minecraft:overworld";

    @Test void swallowedNativeFailureStillInvalidatesReceipt() {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(nativeWrite,RELEVANT);
        var outer=nativeWrite.exceptionally(ignored -> null);
        nativeWrite.completeExceptionally(new IOException("受控 region write 失敗"));
        assertNull(outer.join());
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT),SOURCE));
    }
    @Test void barrierWaitsForAdmittedAsyncFailure() throws Exception {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        tracker.observe(nativeWrite,RELEVANT); long before=tracker.revision();
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT),SOURCE); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        nativeWrite.completeExceptionally(new IOException("延後完成的 IO 失敗"));
        assertThrows(ExecutionException.class,() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void completedFailureCannotDisappearBeforeBarrierSnapshot() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.failedFuture(new IOException("已完成失敗")),RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT),SOURCE));
    }
    @Test void newSuccessfulAttemptCanFollowPreviousFailedAttempt() {
        var tracker=new NativeSaveFailures();
        tracker.observe(CompletableFuture.failedFuture(new IOException("第一輪失敗")),RELEVANT);
        assertTrue(tracker.revision()>0);
        long before=tracker.revision(); tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT),SOURCE));
    }

    // M5 H2：以 region 檔為單位，只比對相關 chunks 所在 region；其他 region 的失敗仍記錄，但不阻擋。
    @Test void otherRegionWriteFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        tracker.observe(CompletableFuture.failedFuture(new IOException("其他 region 的 region write 失敗")),OTHER_REGION);
        assertTrue(tracker.revision()>before,"其他 region 的失敗仍要記錄");
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT),SOURCE));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT,OTHER_REGION),SOURCE),"納入相關範圍時同一筆失敗必須拒絕");
    }
    @Test void otherRegionReportedFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(OTHER_REGION);
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT),SOURCE));
    }
    @Test void barrierWaitsForOtherRegionAsyncFailureWithoutBlocking() throws Exception {
        var tracker=new NativeSaveFailures(); var otherWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(otherWrite,OTHER_REGION);
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT),SOURCE); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        // barrier 仍等所有已受理的 observer（不提早承認），完成後只比對相關 region。
        assertThrows(TimeoutException.class,() -> barrier.get(200,TimeUnit.MILLISECONDS),"observer 未完成前 barrier 不得返回");
        otherWrite.completeExceptionally(new IOException("延後完成的其他 region 失敗"));
        assertDoesNotThrow(() -> barrier.get(2,TimeUnit.SECONDS));
    }
    /** region 檔 header 由同一 region 的 32×32 chunks 共用：同 region 的無關 chunk 失敗也必須拒絕。 */
    @Test void sameRegionUnrelatedChunkFailureFailsClosed() {
        var written=new NativeSaveFailures(); long writtenBefore=written.revision();
        written.observe(CompletableFuture.failedFuture(new IOException("同 region 無關 chunk 的 region write 失敗")),SAME_REGION);
        assertThrows(IllegalStateException.class,() -> written.verify(writtenBefore,Set.of(RELEVANT),SOURCE),"同 region 的寫入失敗");
        var reported=new NativeSaveFailures(); long reportedBefore=reported.revision();
        reported.fail(SAME_REGION);
        assertThrows(IllegalStateException.class,() -> reported.verify(reportedBefore,Set.of(OTHER_RELEVANT),SOURCE),"同 region 的同步回報失敗");
    }
    /** region 邊界與原版 region 檔一致（ChunkPos.getRegionX／Z，即 chunk >> 5），含負座標。 */
    @Test void regionBoundaryMatchesVanillaRegionFiles() {
        assertAll(
                () -> assertRegionJudgement(-1,5,-32,31,true),
                () -> assertRegionJudgement(31,0,0,31,true),
                () -> assertRegionJudgement(0,5,-1,5,false),
                () -> assertRegionJudgement(32,31,31,31,false),
                () -> assertRegionJudgement(5,-33,5,-32,false));
    }
    private static void assertRegionJudgement(int failedX,int failedZ,int relevantX,int relevantZ,boolean blocks) {
        var failed=new ChunkPos(failedX,failedZ); var relevant=new ChunkPos(relevantX,relevantZ);
        assertEquals(blocks,failed.getRegionX()==relevant.getRegionX() && failed.getRegionZ()==relevant.getRegionZ(),"fixture 與原版 region 計算不一致");
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(failed.toLong());
        if(blocks) assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(relevant.toLong()),SOURCE),failed+" 應擋下 "+relevant);
        else assertDoesNotThrow(() -> tracker.verify(before,Set.of(relevant.toLong()),SOURCE),failed+" 不應擋下 "+relevant);
    }
    @Test void relevantChunkFailureStillFailsClosedAmongUnrelatedFailures() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(OTHER_REGION);
        tracker.observe(CompletableFuture.failedFuture(new IOException("相關 chunk 的 region write 失敗")),RELEVANT);
        tracker.observe(CompletableFuture.completedFuture(null),OTHER_RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_RELEVANT,RELEVANT),SOURCE));
    }
    @Test void reportedRelevantFailureFailsClosed() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_RELEVANT,RELEVANT),SOURCE));
    }
    @Test void flushFailureWithoutChunkFailsClosedForEveryChunk() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        tracker.observeFlush(CompletableFuture.failedFuture(new IOException("region flush（sync）失敗")));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT),SOURCE));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_REGION),SOURCE));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(),SOURCE));
    }
    @Test void barrierWaitsForAdmittedAsyncFlushFailure() throws Exception {
        var tracker=new NativeSaveFailures(); var flush=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observeFlush(flush);
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT),SOURCE); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        flush.completeExceptionally(new IOException("延後完成的 flush 失敗"));
        assertThrows(ExecutionException.class,() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void failuresBeforeSnapshotOnlyCountAgainWhenRepeated() {
        var tracker=new NativeSaveFailures();
        tracker.fail(RELEVANT); tracker.observeFlush(CompletableFuture.failedFuture(new IOException("前一輪 flush 失敗")));
        long before=tracker.revision();
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT),SOURCE));
        tracker.fail(RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT),SOURCE),"同一 chunk 在 snapshot 之後再次失敗必須計入");
    }
    @Test void sameChunkCoordinatesInAnotherStorageAreIndependent() {
        // 每份 storage（server 端則是每個世界）各有一份 tracker；相同 chunk 座標互不影響。
        var source=new NativeSaveFailures(); var corridor=new NativeSaveFailures();
        long sourceBefore=source.revision(), corridorBefore=corridor.revision();
        corridor.fail(RELEVANT);
        assertDoesNotThrow(() -> source.verify(sourceBefore,Set.of(RELEVANT),SOURCE));
        assertThrows(IllegalStateException.class,() -> corridor.verify(corridorBefore,Set.of(RELEVANT),SOURCE));
    }

    // H2 fix round 2
    /**
     * M-1：observer 記錄失敗本身失敗（例如記錄時丟例外）時，無法判斷遺失的失敗是否相關，verify 必須拒絕；
     * 即使 receipt 早在 verify 取快照之前就已完成，之後每一次 verify 也都要拒絕。
     */
    @Test void observerRecordingFailureFailsClosedPermanently() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.track(CompletableFuture.failedFuture(new IOException("受控寫入失敗")),() -> { throw new IllegalStateException("受控記錄失敗"); });
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_REGION),SOURCE),"已完成的記錄失敗");
        assertThrows(IllegalStateException.class,() -> tracker.verify(tracker.revision(),Set.of(OTHER_REGION),SOURCE),"之後的 checkpoint 也必須拒絕");
    }
    /** M-4：訊息帶穩定的來源、世界與第一個失敗的相關 region，不含 revision；同一原因重複失敗時訊息相同（WARN 依 class＋message 去重）。 */
    @Test void failureMessagesNameSourceWorldAndRegionStably() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(ChunkPos.toLong(40,12)); tracker.fail(SAME_REGION);
        var relevant=Set.of(ChunkPos.toLong(41,13),RELEVANT,ChunkPos.toLong(-1,0));
        var first=assertThrows(IllegalStateException.class,() -> tracker.verify(before,relevant,SOURCE)).getMessage();
        assertEquals("相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint："+SOURCE+" region=[0, -1]",first,
                "兩個失敗的相關 region (0,-1)、(1,0) 中以座標最小者為準，與集合走訪順序無關");
        tracker.fail(SAME_REGION); tracker.fail(ChunkPos.toLong(40,12));
        assertEquals(first,assertThrows(IllegalStateException.class,() -> tracker.verify(before,relevant,SOURCE)).getMessage(),"revision 改變時訊息不變");
        var flush=new NativeSaveFailures(); long flushBefore=flush.revision(); flush.failStorage();
        var regionCache="source=regionCache:chunk world=minecraft:overworld";
        assertEquals("原生 storage 已回報沒有 chunk 位置的失敗（flush 或 region 檔快取逐出），視為全部 region，拒絕承認 checkpoint："+regionCache,
                assertThrows(IllegalStateException.class,() -> flush.verify(flushBefore,Set.of(RELEVANT),regionCache)).getMessage());
    }
    /**
     * I-1：原版 RegionBasedStorage 快取滿 256 個 region 檔時，取新的 region 檔會先 close 最久未用的那一個（fillLastSector＋force(true)），
     * close 的例外丟給正在讀寫的另一個 chunk，sync() 之後也不再走訪被逐出的檔。被逐出的相關 region 若 close 失敗，checkpoint 必須拒絕。
     * 以真的 StorageIoWorker／RegionBasedStorage（含 production mixin）在暫存目錄重現：先關掉相關 region 檔的 FileChannel，讓逐出時的 close 真的丟例外。
     */
    @Test void regionCacheEvictionCloseFailureFailsClosed(@TempDir Path directory) throws Exception {
        var worker=new StorageIoWorker(new StorageKey("h2-eviction",World.OVERWORLD,"chunk"),directory,false) { };
        try {
            var relevant=ChunkPos.fromRegion(0,0);
            write(worker,relevant);
            for(int region=1;region<256;region++) write(worker,ChunkPos.fromRegion(region,0));
            var cache=(it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap<?>)field(field(worker,"storage"),"cachedRegionFiles");
            assertEquals(256,cache.size(),"region 檔快取必須已滿");
            long relevantRegion=ChunkPos.toLong(relevant.getRegionX(),relevant.getRegionZ());
            assertEquals(relevantRegion,cache.lastLongKey(),"相關 region 必須是最久未用、下一個被逐出的檔");
            ((FileChannel)field(cache.get(relevantRegion),"channel")).close();
            var receipt=ChunkSaveCheckpoint.StorageReceipt.of("chunk",((NativeStorageSaveAccess)worker).quantumchamber$saveFailures(),worker);
            var trigger=ChunkPos.fromRegion(300,0);
            var triggered=worker.setResult(trigger,new NbtCompound());
            var thrown=assertThrows(CompletionException.class,triggered::join,"逐出時的 close 失敗會丟給觸發的 chunk");
            assertInstanceOf(IOException.class,thrown.getCause());
            assertFalse(cache.containsKey(relevantRegion),"相關 region 已被逐出，sync() 不會再走訪它");
            assertThrows(IllegalStateException.class,() -> receipt.verify("minecraft:overworld",Set.of(relevant.toLong())),
                    "被逐出的相關 region close 失敗必須讓 checkpoint 拒絕");
        } finally { worker.close(); }
    }
    private static void write(StorageIoWorker worker,ChunkPos pos) { worker.setResult(pos,new NbtCompound()).join(); }
    private static Object field(Object target,String name) throws ReflectiveOperationException {
        for(Class<?> type=target.getClass();type!=null;type=type.getSuperclass()) {
            try { var field=type.getDeclaredField(name); field.setAccessible(true); return field.get(target); }
            catch(NoSuchFieldException next) { }
        }
        throw new NoSuchFieldException(name);
    }
}
