package dev.quantumchamber.persistence;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveFailuresTest {
    /** ChunkPos.toLong 形式的 chunk 位置；測試只需要彼此不同。 */
    private static final long RELEVANT=pack(3,-7), OTHER_RELEVANT=pack(4,-7), UNRELATED=pack(40,12);
    private static long pack(int x,int z) { return x & 0xFFFFFFFFL | (z & 0xFFFFFFFFL)<<32; }

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

    // M5 H2：只比對本交易相關的 chunks；無關 chunk 的失敗仍記錄，但不阻擋。
    @Test void unrelatedChunkWriteFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.completedFuture(null),RELEVANT);
        tracker.observe(CompletableFuture.failedFuture(new IOException("無關 chunk 的 region write 失敗")),UNRELATED);
        assertTrue(tracker.revision()>before,"無關 chunk 的失敗仍要記錄");
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT)));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(RELEVANT,UNRELATED)),"納入相關範圍時同一筆失敗必須拒絕");
    }
    @Test void unrelatedReportedFailureDoesNotBlockRelevantChunks() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(UNRELATED);
        assertDoesNotThrow(() -> tracker.verify(before,Set.of(RELEVANT,OTHER_RELEVANT)));
    }
    @Test void barrierWaitsForUnrelatedAsyncFailureWithoutBlocking() throws Exception {
        var tracker=new NativeSaveFailures(); var unrelatedWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(unrelatedWrite,UNRELATED);
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before,Set.of(RELEVANT)); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        // barrier 仍等所有已受理的 observer（不提早承認），完成後只比對相關 chunks。
        assertThrows(TimeoutException.class,() -> barrier.get(200,TimeUnit.MILLISECONDS),"observer 未完成前 barrier 不得返回");
        unrelatedWrite.completeExceptionally(new IOException("延後完成的無關 chunk 失敗"));
        assertDoesNotThrow(() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void relevantChunkFailureStillFailsClosedAmongUnrelatedFailures() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.fail(UNRELATED);
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
        assertThrows(IllegalStateException.class,() -> tracker.verify(before,Set.of(UNRELATED)));
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
