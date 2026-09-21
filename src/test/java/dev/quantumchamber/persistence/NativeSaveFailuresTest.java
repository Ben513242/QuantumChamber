package dev.quantumchamber.persistence;

import java.io.IOException;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeSaveFailuresTest {
    @Test void swallowedNativeFailureStillInvalidatesReceipt() {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        long before=tracker.revision(); tracker.observe(nativeWrite);
        var outer=nativeWrite.exceptionally(ignored -> null);
        nativeWrite.completeExceptionally(new IOException("受控 region write 失敗"));
        assertNull(outer.join());
        assertThrows(IllegalStateException.class,() -> tracker.verify(before));
    }
    @Test void barrierWaitsForAdmittedAsyncFailure() throws Exception {
        var tracker=new NativeSaveFailures(); var nativeWrite=new CompletableFuture<Void>();
        tracker.observe(nativeWrite); long before=tracker.revision();
        var started=new CountDownLatch(1);
        var barrier=CompletableFuture.runAsync(() -> { started.countDown(); tracker.verify(before); });
        assertTrue(started.await(2,TimeUnit.SECONDS));
        nativeWrite.completeExceptionally(new IOException("延後完成的 IO 失敗"));
        assertThrows(ExecutionException.class,() -> barrier.get(2,TimeUnit.SECONDS));
    }
    @Test void completedFailureCannotDisappearBeforeBarrierSnapshot() {
        var tracker=new NativeSaveFailures(); long before=tracker.revision();
        tracker.observe(CompletableFuture.failedFuture(new IOException("已完成失敗")));
        assertThrows(IllegalStateException.class,() -> tracker.verify(before));
    }
    @Test void newSuccessfulAttemptCanFollowPreviousFailedAttempt() {
        var tracker=new NativeSaveFailures();
        tracker.observe(CompletableFuture.failedFuture(new IOException("第一輪失敗")));
        assertTrue(tracker.revision()>0);
        long before=tracker.revision(); tracker.observe(CompletableFuture.completedFuture(null));
        assertDoesNotThrow(() -> tracker.verify(before));
    }
}
