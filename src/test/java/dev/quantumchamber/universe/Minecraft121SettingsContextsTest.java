package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class Minecraft121SettingsContextsTest {
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object cell(Minecraft121SettingsContexts contexts) throws Exception {
        return field(contexts, "cell");
    }

    private static void detached(Minecraft121SettingsContexts contexts) throws Exception {
        assertNull(field(cell(contexts), "payload"), "Cell 仍持有 owner payload");
        for (var ownerField : Minecraft121SettingsContexts.class.getDeclaredFields()) {
            ownerField.setAccessible(true);
            assertFalse(ownerField.get(contexts) instanceof Thread, "facade 仍持有 Thread");
            assertFalse(ownerField.get(contexts) instanceof Minecraft121SettingsContexts.Sources,
                    "facade 仍持有 Sources");
        }
    }
    private static Minecraft121SettingsContexts.Sources sources() {
        return new Minecraft121SettingsContexts.Sources(new Object(), new Object(), new Object(), new Object(), true);
    }

    private static void unavailable(Runnable action) {
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class, action::run).getMessage());
    }
    private static void initialState(Minecraft121SettingsContexts contexts, String expected) throws Exception {
        var fence = field(cell(contexts), "fence");
        synchronized (fence) { assertEquals(expected, field(fence, "initialState").toString()); }
    }
    @Test void initialMetadataPermitCanCommitFixtureOnOwner() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        unavailable(() -> contexts.publishInitial(ticket, sources()));
        initialState(contexts, "ISSUED");
        var fixture = sources(); // METADATA_ONLY；不證明原生來源已 ready。
        try (var capture = contexts.beginInitialCapture(ticket)) {
            capture.checkpoint();
            contexts.publishInitial(ticket, fixture);
        }
        initialState(contexts, "COMMITTED");
        assertSame(fixture, Minecraft121SettingsContexts.sources(contexts.acquire(server)));
        contexts.acquire(server).requireValid();
    }
    @Test void abortThenDirectPublishRejects() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        capture.abort();
        initialState(contexts, "ABORTED");
        unavailable(() -> contexts.publishInitial(ticket, sources()));
        unavailable(() -> contexts.beginInitialCapture(ticket));
        unavailable(() -> contexts.acquire(server));
        capture.close();
    }
    @Test void closeBeforeCommitAborts() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        capture.close(); capture.close();
        initialState(contexts, "ABORTED");
        unavailable(capture::checkpoint);
        unavailable(capture::abort);
        unavailable(() -> contexts.publishInitial(ticket, sources()));
    }
    @Test void closeAfterCommitKeepsCommitted() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        contexts.publishInitial(ticket, sources());
        capture.close(); capture.close();
        contexts.acquire(server).requireValid();
        initialState(contexts, "COMMITTED");
        unavailable(capture::checkpoint);
        unavailable(capture::abort);
    }
    @Test void abortAfterCommitRejectsAndLeaseRemainsValid() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        contexts.publishInitial(ticket, sources());
        unavailable(capture::abort);
        contexts.acquire(server).requireValid();
        initialState(contexts, "COMMITTED");
        capture.close();
    }
    @Test void explicitTicketBlocksLegacyPublish() {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        capture.abort();
        unavailable(() -> contexts.publishInitial(sources()));
        capture.close();
        unavailable(() -> contexts.publishInitial(sources()));
    }
    @Test void legacyPublishBlocksTicket() {
        var contexts = new Minecraft121SettingsContexts(new Object());
        contexts.publishInitial(sources());
        unavailable(contexts::captureInitial);
    }
    @Test void wrongTicketOrThreadRejects() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var constructor = Minecraft121SettingsContexts.InitialTicket.class.getDeclaredConstructor(Object.class, long.class);
        constructor.setAccessible(true);
        var generation = Minecraft121SettingsContexts.InitialTicket.class.getDeclaredField("generation");
        generation.setAccessible(true);
        var fake = constructor.newInstance(field(ticket, "incarnation"), generation.getLong(ticket));
        unavailable(() -> contexts.beginInitialCapture(fake));
        var capture = contexts.beginInitialCapture(ticket);
        foreign(() -> {
            unavailable(capture::checkpoint);
            unavailable(() -> contexts.publishInitial(ticket, sources()));
        });
        capture.checkpoint();
        unavailable(() -> contexts.publishInitial(fake, sources()));
        capture.close();
    }
    @Test void invalidCheckpointCannotResetTicket() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        foreign(() -> unavailable(capture::checkpoint));
        capture.checkpoint();
        contexts.publishInitial(ticket, sources());
        capture.close();
        initialState(contexts, "COMMITTED");
    }
    @Test void stoppedOrForeignCannotBeginOrClose() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        foreign(() -> unavailable(capture::close));
        capture.checkpoint();
        capture.close();
        var stopped = new Minecraft121SettingsContexts(new Object());
        var stoppedTicket = stopped.captureInitial();
        foreign(stopped::revokeStop);
        unavailable(() -> stopped.beginInitialCapture(stoppedTicket));
    }

    @Test void initialStopBeforeCommitRejects() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        foreign(contexts::revokeStop);
        unavailable(capture::checkpoint);
        unavailable(() -> contexts.publishInitial(ticket, sources()));
        unavailable(() -> contexts.acquire(server));
        assertNull(field(cell(contexts), "payload"));
        capture.close();
    }
    @Test void initialStopAfterMetadataCommitRevokesLease() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        contexts.publishInitial(ticket, sources());
        var lease = contexts.acquire(server);
        foreign(contexts::revokeStop);
        unavailable(lease::requireValid);
        assertNull(field(cell(contexts), "payload"));
        capture.close();
    }
    @Test void retainedPermitAfterForeignStopHasNoOwnerPath() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        foreign(contexts::revokeStop);
        assertNull(field(cell(contexts), "payload"));
        for (var member : capture.getClass().getDeclaredFields()) {
            member.setAccessible(true);
            var value = member.get(capture);
            assertNotSame(server, value);
            assertFalse(value instanceof Thread);
            assertFalse(value instanceof Minecraft121SettingsContexts.Sources);
            assertFalse(member.getName().equals("this$0"));
        }
        capture.close();
    }
    @Test void reloadOverlapAndOverflowCannotResurrectInitial() throws Exception {
        var contexts = new Minecraft121SettingsContexts(new Object());
        var ticket = contexts.captureInitial();
        var capture = contexts.beginInitialCapture(ticket);
        contexts.revokeReloadStart();
        unavailable(capture::checkpoint);
        unavailable(() -> contexts.publishInitial(ticket, sources()));
        unavailable(() -> contexts.publishInitial(sources()));
        capture.close();
        var overflow = new Minecraft121SettingsContexts(new Object());
        var fence = field(cell(overflow), "fence");
        var generation = fence.getClass().getDeclaredField("generation");
        generation.setAccessible(true);
        synchronized (fence) { generation.setLong(fence, Long.MAX_VALUE); }
        var last = overflow.captureInitial();
        unavailable(overflow::revokeReloadStart);
        unavailable(() -> overflow.beginInitialCapture(last));
        unavailable(() -> overflow.publishInitial(last, sources()));
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
        thread.setDaemon(true);
        assertTrue(thread.isDaemon(), "跨執行緒測試 worker 必須為 daemon");
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

    @Test void retainedLeaseAndFacadeDetachAfterForeignStop() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        foreign(contexts::revokeStop);
        assertThrows(IllegalStateException.class, lease::requireValid);
        detached(contexts);
        // 從兩個保留的 root 檢查實際強邊，不以 GC 或失效例外代替斷邊證據。
        for (var field : lease.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            assertNotSame(contexts, field.get(lease), "lease 仍直接保留 facade");
            assertFalse(field.get(lease) instanceof Thread, "lease 仍保留 owner thread");
        }
        for (var field : Minecraft121SettingsContexts.class.getDeclaredFields()) {
            field.setAccessible(true);
            var value = field.get(contexts);
            assertNotSame(server, value, "facade 仍保留 server");
            assertFalse(value instanceof Thread, "facade 仍保留 owner thread");
            assertFalse(value instanceof Minecraft121SettingsContexts.Sources, "facade 仍保留 Sources");
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
        worker.setDaemon(true);
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
        worker.setDaemon(true);
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
        try (var capture = contexts.beginInitialCapture(ticket)) {
            capture.checkpoint();
            contexts.publishInitial(ticket, initial);
        }
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
        assertThrows(IllegalStateException.class, contexts::stop);
        contexts.revokeStop();
        assertThrows(IllegalStateException.class, contexts::stop);
        var pending = new Minecraft121SettingsContexts(new Object());
        var ticket = pending.revokeReloadStart();
        pending.adoptReload(ticket);
        foreign(pending::revokeStop);
        assertThrows(IllegalStateException.class, () -> pending.finishReload(ticket, true, sources()));
        assertThrows(IllegalStateException.class, () -> pending.finishReload(ticket, false, null));
        assertThrows(IllegalStateException.class, pending::stop);
        detached(pending);
    }

    @Test void serverIncarnationsDoNotAlias() {
        var a = new Minecraft121SettingsContexts(new Object());
        var serverB = new Object();
        var b = new Minecraft121SettingsContexts(serverB);
        var ia = a.captureInitial();
        var ib = b.captureInitial();
        assertThrows(IllegalStateException.class, () -> b.publishInitial(ia, sources()));
        try (var capture = b.beginInitialCapture(ib)) {
            capture.checkpoint();
            b.publishInitial(ib, sources());
        }
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
        try (var capture = contexts.beginInitialCapture(initial)) {
            capture.checkpoint();
            contexts.publishInitial(initial, sources());
        }
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
        detached(contexts);
        contexts.revokeStop();
        assertThrows(IllegalStateException.class, () -> contexts.finishReload(ticket, true, sources()));
    }

    @Test void generationExhaustionPermanentlyClosesWithoutWrapping() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        var fence = field(cell(contexts), "fence");
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
        detached(contexts);
        assertThrows(IllegalStateException.class, contexts::stop);
        contexts.revokeStop();
    }

    @Test void callbackGraphIsMetadataOnlyAndStopDetachesRetainedRoots() throws Exception {
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server);
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        var callback = contexts.revocationHandle();
        assertFalse(callback.isStopped());
        assertSame(cell(contexts), field(lease, "cell"));
        assertSame(field(cell(contexts), "fence"), field(callback, "fence"));
        for (var member : callback.getClass().getDeclaredFields()) {
            member.setAccessible(true);
            var value = member.get(callback);
            assertNotSame(contexts, value);
            assertNotSame(cell(contexts), value);
            assertFalse(value instanceof Thread);
            assertFalse(value instanceof Minecraft121SettingsContexts.Sources);
            assertFalse(member.getName().equals("this$0"), "callback 不得捕捉 facade");
        }
        assertInstanceOf(java.lang.ref.WeakReference.class, field(callback, "cell"));
        foreign(callback::revokeStopAndDetach);
        assertTrue(callback.isStopped());
        detached(contexts);
        assertThrows(IllegalStateException.class, lease::requireValid);
        assertThrows(IllegalStateException.class, contexts::stop);
        assertThrows(IllegalStateException.class, contexts::captureInitial);
        assertThrows(IllegalStateException.class, callback::revokeReloadStart);
        callback.revokeStopAndDetach();
        detached(contexts);
    }

    @Test void foreignStopCompletesWhileOwnerIsParkedOutsideFence() throws Exception {
        var reached = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var server = new Object();
        var contexts = new Minecraft121SettingsContexts(server, () -> {
            reached.countDown();
            await(release);
        });
        contexts.publishInitial(sources());
        var lease = contexts.acquire(server);
        var stopper = new Thread(() -> {
            await(reached);
            contexts.revocationHandle().revokeStopAndDetach();
            release.countDown();
        });
        stopper.setDaemon(true);
        stopper.start();
        try {
            assertThrows(IllegalStateException.class, () -> Minecraft121SettingsContexts.sources(lease));
        } finally {
            release.countDown();
            stopper.join(5_000);
            assertFalse(stopper.isAlive());
        }
        detached(contexts);
    }
}
