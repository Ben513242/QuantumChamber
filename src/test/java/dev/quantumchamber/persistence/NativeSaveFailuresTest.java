package dev.quantumchamber.persistence;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveFailuresTest {
    /**
     * chunk 以 ChunkPos.toLong 表示。RELEVANT 與 OTHER_RELEVANT 在 region (0,-1)；SAME_REGION 是同一 region 內、不在相關範圍的 chunk；
     * OTHER_REGION 在 region (1,0)。
     */
    private static final long RELEVANT=ChunkPos.toLong(3,-7), OTHER_RELEVANT=ChunkPos.toLong(4,-7),
            SAME_REGION=ChunkPos.toLong(31,-32), OTHER_REGION=ChunkPos.toLong(40,12);

    @Test void swallowedNativeFailureStillInvalidatesReceipt() {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(nativeWrite,RELEVANT);
        var outer=nativeWrite.exceptionally(ignored -> null);
        nativeWrite.completeExceptionally(new IOException("受控 region write 失敗"));
        assertNull(outer.join());
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT)));
    }
    @Test void barrierWaitsForAdmittedAsyncFailure() throws Exception {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        tracker.observe(nativeWrite,RELEVANT); long before=tracker.revision();
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT)); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        nativeWrite.completeExceptionally(new IOException("延後完成的 IO 失敗"));
        assertThrows(ExecutionException.class,() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void completedFailureCannotDisappearBeforeBarrierSnapshot() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.failedFuture(new IOException("已完成失敗")),RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT)));
    }
    @Test void newSuccessfulAttemptCanFollowPreviousFailedAttempt() {
        var tracker=new NativeSaveFailures();
        tracker.observe(CompletableFuture.failedFuture(new IOException("第一輪失敗")),RELEVANT);
        assertTrue(tracker.revision()>0);
        long before=tracker.revision(); tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT)));
    }

    // M5 H2：以 region 檔為單位，只比對相關 chunks 所在 region；其他 region 的失敗仍記錄，但不阻擋。
    @Test void otherRegionWriteFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        tracker.observe(CompletableFuture.failedFuture(new IOException("其他 region 的 region write 失敗")),OTHER_REGION);
        assertTrue(tracker.revision()>before,"其他 region 的失敗仍要記錄");
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT)));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT,OTHER_REGION)),"納入相關範圍時同一筆失敗必須拒絕");
    }
    @Test void otherRegionReportedFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(OTHER_REGION);
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT)));
    }
    @Test void barrierWaitsForOtherRegionAsyncFailureWithoutBlocking() throws Exception {
        var tracker=new NativeSaveFailures(); var otherWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(otherWrite,OTHER_REGION);
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT)); });
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
        assertThrows(IllegalStateException.class,() -> written.verify(writtenBefore,Set.of(RELEVANT)),"同 region 的寫入失敗");
        var reported=new NativeSaveFailures(); long reportedBefore=reported.revision();
        reported.fail(SAME_REGION);
        assertThrows(IllegalStateException.class,() -> reported.verify(reportedBefore,Set.of(OTHER_RELEVANT)),"同 region 的同步回報失敗");
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
        if(blocks) assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(relevant.toLong())),failed+" 應擋下 "+relevant);
        else assertDoesNotThrow(() -> tracker.verify(before,Set.of(relevant.toLong())),failed+" 不應擋下 "+relevant);
    }
    @Test void relevantChunkFailureStillFailsClosedAmongUnrelatedFailures() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(OTHER_REGION);
        tracker.observe(CompletableFuture.failedFuture(new IOException("相關 chunk 的 region write 失敗")),RELEVANT);
        tracker.observe(CompletableFuture.completedFuture(null),OTHER_RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_RELEVANT,RELEVANT)));
    }
    @Test void reportedRelevantFailureFailsClosed() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_RELEVANT,RELEVANT)));
    }
    @Test void flushFailureWithoutChunkFailsClosedForEveryChunk() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        tracker.observeFlush(CompletableFuture.failedFuture(new IOException("region flush（sync）失敗")));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT)));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(OTHER_REGION)));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of()));
    }
    @Test void barrierWaitsForAdmittedAsyncFlushFailure() throws Exception {
        var tracker=new NativeSaveFailures(); var flush=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observeFlush(flush);
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT)); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        flush.completeExceptionally(new IOException("延後完成的 flush 失敗"));
        assertThrows(ExecutionException.class,() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void failuresBeforeSnapshotOnlyCountAgainWhenRepeated() {
        var tracker=new NativeSaveFailures();
        tracker.fail(RELEVANT); tracker.observeFlush(CompletableFuture.failedFuture(new IOException("前一輪 flush 失敗")));
        long before=tracker.revision();
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT)));
        tracker.fail(RELEVANT);
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT)),"同一 chunk 在 snapshot 之後再次失敗必須計入");
    }
    @Test void sameChunkCoordinatesInAnotherStorageAreIndependent() {
        // 每份 storage（server 端則是每個世界）各有一份 tracker；相同 chunk 座標互不影響。
        var source=new NativeSaveFailures(); var corridor=new NativeSaveFailures();
        long sourceBefore=source.revision(), corridorBefore=corridor.revision();
        corridor.fail(RELEVANT);
        assertDoesNotThrow(() -> source.verify(sourceBefore,Set.of(RELEVANT)));
        assertThrows(IllegalStateException.class,() -> corridor.verify(corridorBefore,Set.of(RELEVANT)));
    }
}
