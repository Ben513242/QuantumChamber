package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Minecraft121SettingsContextsTest {
    private static Minecraft121SettingsContexts.Sources sources() {
        return new Minecraft121SettingsContexts.Sources(new Object(), new Object(), new Object(), new Object(), true);
    }

    @Test void contextEpochRejectsReloadAndStop() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var old = contexts.acquire(server);
        old.requireValid();
        contexts.reloadStarted();
        assertThrows(IllegalStateException.class, old::requireValid);
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        contexts.reloadFinished(true, sources());
        var next = contexts.acquire(server);
        next.requireValid();
        contexts.stop();
        assertThrows(IllegalStateException.class, next::requireValid);
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
    }

    @Test void contextDoesNotCrossServerOrThread() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        assertThrows(IllegalStateException.class, () -> contexts.acquire(new Object()));
        var lease = contexts.acquire(server);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { lease.requireValid(); } catch (Throwable error) { failure.set(error); } });
        thread.start(); thread.join(5_000);
        assertFalse(thread.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test void multipleInflightReloadsAndFailureStayUnavailable() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        contexts.reloadStarted();
        contexts.reloadStarted();
        var first = sources();
        contexts.reloadFinished(true, first);
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        contexts.reloadFinished(false, sources());
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        contexts.reloadStarted();
        contexts.reloadFinished(true, sources());
        contexts.acquire(server).requireValid();
    }

    @Test void mixedSuccessfulReloadSourcesDoNotPublish() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        contexts.reloadStarted();
        contexts.reloadStarted();
        contexts.reloadFinished(true, sources());
        contexts.reloadFinished(true, sources());
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
    }

    @Test void stoppedLeaseDoesNotRetainBorrowedSources() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        contexts.stop();
        assertThrows(IllegalStateException.class, lease::requireValid);
        for (var field : lease.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            assertFalse(field.get(lease) instanceof Minecraft121SettingsContexts.Sources,
                    "stale lease pinned borrowed source: " + field.getName());
        }
        for (var field : Minecraft121SettingsContexts.class.getDeclaredFields()) {
            if (field.getType() != Minecraft121SettingsContexts.Sources.class) continue;
            field.setAccessible(true);
            assertNull(field.get(contexts), "stop retained borrowed source: " + field.getName());
        }
    }

    @Test void failedReloadCannotRepublishInitial() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        contexts.reloadStarted();
        contexts.reloadFinished(false, null);
        var failure = assertThrows(IllegalStateException.class, () -> contexts.publishInitial(sources()));
        assertEquals("CONTEXT_UNAVAILABLE", failure.getMessage());
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
    }

    private static void foreign(Runnable action) throws Exception {
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new Thread(() -> { try { action.run(); } catch (Throwable e) { failure.set(e); } });
        worker.start();
        worker.join(5_000);
        assertFalse(worker.isAlive(), "foreign 操作未在期限內完成");
        if (failure.get() != null) throw new AssertionError("foreign 操作失敗", failure.get());
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS), "交錯等待逾時"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    @Test void oldLeaseRevokedBeforeOwnerDispatch() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var initial = sources();
        contexts.publishInitial(initial);
        var lease = contexts.acquire(server);
        assertSame(initial, Minecraft121SettingsContexts.sources(lease));
        foreign(contexts::revokeReloadStart);
        assertThrows(IllegalStateException.class, lease::requireValid);
        assertThrows(IllegalStateException.class, () -> Minecraft121SettingsContexts.sources(lease));
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
    }

    @Test void sourcesChecksAndCapturesAtomically() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var revoked = new java.util.concurrent.CountDownLatch(1);
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server, () -> { reached.countDown(); await(revoked); });
        var original = sources();
        contexts.publishInitial(original);
        var lease = contexts.acquire(server);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var worker = new Thread(() -> {
            try { await(reached); contexts.revokeReloadStart(); }
            catch (Throwable e) { failure.set(e); }
            finally { revoked.countDown(); }
        });
        worker.start();
        try {
            assertThrows(IllegalStateException.class, () -> {
                var leaked = Minecraft121SettingsContexts.sources(lease);
                assertSame(original, leaked, "反例應是撤銷前仍留在 owner 的原來源");
            });
        } finally {
            reached.countDown(); revoked.countDown(); worker.join(5_000);
            assertFalse(worker.isAlive());
        }
        assertNull(failure.get());
    }

    @Test void sourcesBarrierExceptionCannotExposeSources() {
        var expected = new IllegalArgumentException("測試障礙");
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server, () -> { throw expected; });
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        assertSame(expected, assertThrows(IllegalArgumentException.class,
                () -> Minecraft121SettingsContexts.sources(lease)));
    }

    @Test void explicitInitialCapturesExactGeneration() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var initial = sources();
        contexts.publishInitial(ticket, initial);
        assertSame(initial, Minecraft121SettingsContexts.sources(contexts.acquire(server)));
        assertThrows(IllegalStateException.class, () -> contexts.publishInitial(ticket, sources()));
        assertThrows(IllegalStateException.class, contexts::captureInitial);
        assertThrows(IllegalStateException.class, () -> contexts.publishInitial(sources()));
    }

    @Test void initialTicketRejectsGenerationDriftAndStop() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var initial = contexts.captureInitial();
        var reload = contexts.revokeReloadStart();
        assertThrows(IllegalStateException.class, () -> contexts.publishInitial(initial, sources()));
        contexts.adoptReload(reload);
        contexts.finishReload(reload, true, sources());
        contexts.acquire(server).requireValid();
        var stopped = new Minecraft121SettingsContexts(new Object());
        var captured = stopped.captureInitial();
        stopped.revokeStop();
        assertThrows(IllegalStateException.class, () -> stopped.publishInitial(captured, sources()));
    }

    @Test void ticketMustBeAdoptedExactlyOnce() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.revokeReloadStart();
        assertThrows(IllegalStateException.class, () -> contexts.finishReload(ticket, true, sources()));
        contexts.adoptReload(ticket);
        assertThrows(IllegalStateException.class, () -> contexts.adoptReload(ticket));
        var next = sources();
        contexts.finishReload(ticket, true, next);
        assertSame(next, Minecraft121SettingsContexts.sources(contexts.acquire(server)));
        assertThrows(IllegalStateException.class, () -> contexts.finishReload(ticket, false, null));
        assertSame(next, Minecraft121SettingsContexts.sources(contexts.acquire(server)));
    }

    @Test void settledFailureAllowsIndependentSuccessWithoutAba() {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        for (boolean success : new boolean[] {false, true}) {
            var first = contexts.revokeReloadStart();
            contexts.adoptReload(first);
            contexts.finishReload(first, success, null);
            assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
            var second = contexts.revokeReloadStart();
            contexts.adoptReload(second);
            assertThrows(IllegalStateException.class, () -> contexts.finishReload(first, true, sources()));
            assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
            var next = sources();
            contexts.finishReload(second, true, next);
            assertSame(next, Minecraft121SettingsContexts.sources(contexts.acquire(server)));
        }
    }

    @Test void legacyAfterExplicitNeverReopens() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var ticket = contexts.revokeReloadStart();
        assertThrows(IllegalStateException.class, contexts::reloadStarted);
        assertThrows(IllegalStateException.class, () -> contexts.reloadFinished(true, sources()));
        contexts.adoptReload(ticket);
        contexts.finishReload(ticket, true, sources());
        contexts.acquire(server).requireValid();
        assertThrows(IllegalStateException.class, contexts::reloadStarted);
        assertThrows(IllegalStateException.class, () -> contexts.reloadFinished(true, sources()));
        assertThrows(IllegalStateException.class, () -> contexts.publishInitial(sources()));
        var pending = new Minecraft121SettingsContexts(new Object());
        pending.reloadStarted();
        foreign(pending::revokeReloadStart);
        assertThrows(IllegalStateException.class, () -> pending.reloadFinished(true, sources()));
        var later = pending.revokeReloadStart();
        assertThrows(IllegalStateException.class, () -> pending.adoptReload(later));
    }

    @Test void overlappingTicketsPermanentlyCloseBothCompletionOrders() {
        for (boolean reverse : new boolean[] {false, true}) {
            var server = new Object();
            var contexts = new Minecraft121SettingsContexts(server);
            var first = contexts.revokeReloadStart();
            contexts.adoptReload(first);
            var second = contexts.revokeReloadStart();
            assertThrows(IllegalStateException.class, () -> contexts.adoptReload(second));
            var a = reverse ? second : first;
            var b = reverse ? first : second;
            assertThrows(IllegalStateException.class, () -> contexts.finishReload(a, true, sources()));
            assertThrows(IllegalStateException.class, () -> contexts.finishReload(b, true, sources()));
            var third = contexts.revokeReloadStart();
            assertThrows(IllegalStateException.class, () -> contexts.adoptReload(third));
            assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        }
    }

    @Test void unadoptedOverlapCannotBorrowLatestGeneration() {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var first = contexts.revokeReloadStart();
        var second = contexts.revokeReloadStart();
        assertThrows(IllegalStateException.class, () -> contexts.adoptReload(first));
        assertThrows(IllegalStateException.class, () -> contexts.adoptReload(second));
    }

    @Test void stopRejectsOldLeaseAndLateSuccess() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        foreign(contexts::revokeStop);
        assertThrows(IllegalStateException.class, lease::requireValid);
        assertThrows(IllegalStateException.class, () -> Minecraft121SettingsContexts.sources(lease));
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        assertThrows(IllegalStateException.class, contexts::revokeReloadStart);
        contexts.revokeStop();
        contexts.stop();
        contexts.revokeStop();
        assertThrows(IllegalStateException.class, contexts::stop);
        var pending = new Minecraft121SettingsContexts(new Object());
        var ticket = pending.revokeReloadStart();
        pending.adoptReload(ticket);
        foreign(pending::revokeStop);
        assertThrows(IllegalStateException.class, () -> pending.finishReload(ticket, true, sources()));
        assertThrows(IllegalStateException.class, () -> pending.finishReload(ticket, false, null));
        pending.stop();
    }

    @Test void serverIncarnationsDoNotAlias() {
        var a = new Minecraft121SettingsContexts(new Object());
        var serverB = new Object();
        var b = new Minecraft121SettingsContexts(serverB);
        var ia = a.captureInitial();
        var ib = b.captureInitial();
        assertThrows(IllegalStateException.class, () -> b.publishInitial(ia, sources()));
        b.publishInitial(ib, sources());
        var ta = a.revokeReloadStart();
        var tb = b.revokeReloadStart();
        assertThrows(IllegalStateException.class, () -> b.adoptReload(ta));
        b.adoptReload(tb);
        assertThrows(IllegalStateException.class, () -> b.finishReload(ta, true, sources()));
        b.finishReload(tb, true, sources());
        var leaseB = b.acquire(serverB);
        a.stop();
        leaseB.requireValid();
        assertThrows(IllegalStateException.class, () -> a.acquire(serverB));
    }

    @Test void foreignOwnerOperationsRemainRejected() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var initial = contexts.captureInitial();
        foreign(() -> {
            assertThrows(IllegalStateException.class, contexts::captureInitial);
            assertThrows(IllegalStateException.class, () -> contexts.publishInitial(initial, sources()));
            assertThrows(IllegalStateException.class, () -> contexts.publishInitial(sources()));
            assertThrows(IllegalStateException.class, contexts::reloadStarted);
            assertThrows(IllegalStateException.class, () -> contexts.reloadFinished(true, sources()));
            assertThrows(IllegalStateException.class, contexts::stop);
        });
        contexts.publishInitial(initial, sources());
        var lease = contexts.acquire(server);
        foreign(() -> {
            assertThrows(IllegalStateException.class, lease::requireValid);
            assertThrows(IllegalStateException.class, () -> Minecraft121SettingsContexts.sources(lease));
            assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        });
        var ticket = contexts.revokeReloadStart();
        foreign(() -> assertThrows(IllegalStateException.class, () -> contexts.adoptReload(ticket)));
        contexts.adoptReload(ticket);
        foreign(() -> assertThrows(IllegalStateException.class, () -> contexts.finishReload(ticket, true, sources())));
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        contexts.finishReload(ticket, true, sources());
        contexts.acquire(server).requireValid();
    }

    @Test void stopDropsOwnerReferences() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        contexts.publishInitial(sources());
        var ticket = contexts.revokeReloadStart();
        contexts.adoptReload(ticket);
        contexts.stop();
        for (String name : new String[] {"current", "pending", "server", "ownerThread"}) {
            var field = Minecraft121SettingsContexts.class.getDeclaredField(name);
            field.setAccessible(true);
            assertNull(field.get(contexts), "stop 留下 owner 引用：" + name);
        }
        contexts.revokeStop();
        assertThrows(IllegalStateException.class, () -> contexts.finishReload(ticket, true, sources()));
    }

    @Test void generationExhaustionPermanentlyClosesWithoutWrapping() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var fenceField = Minecraft121SettingsContexts.class.getDeclaredField("fence");
        fenceField.setAccessible(true);
        var fence = fenceField.get(contexts);
        var generation = fence.getClass().getDeclaredField("generation");
        generation.setAccessible(true);
        // 僅測不可實際迭代至的數值邊界；正式路徑仍呼真 START／adopt／finish。
        synchronized (fence) { generation.setLong(fence, Long.MAX_VALUE - 1); }
        var last = contexts.revokeReloadStart();
        contexts.adoptReload(last);
        contexts.finishReload(last, true, sources());
        var lease = contexts.acquire(server);
        lease.requireValid();
        assertThrows(IllegalStateException.class, contexts::revokeReloadStart);
        assertThrows(IllegalStateException.class, lease::requireValid);
        assertThrows(IllegalStateException.class, () -> contexts.acquire(server));
        assertThrows(IllegalStateException.class, contexts::revokeReloadStart);
        synchronized (fence) { assertEquals(Long.MAX_VALUE, generation.getLong(fence)); }
        contexts.revokeStop();
        contexts.stop();
        contexts.revokeStop();
    }
}
