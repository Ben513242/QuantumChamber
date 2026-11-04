package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.junit.jupiter.api.Test;

final class Minecraft121SettingsLifecycleTest {
    private static Minecraft121SettingsContexts.Sources sources() {
        return new Minecraft121SettingsContexts.Sources(new Object(), new Object(), new Object(), new Object(), true);
    }

    @Test void lifecyclePublishesOnceAndStopsPerServer() {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var first = new Object();
        var second = new Object();
        lifecycle.onStarting(first, sources());
        lifecycle.onStarting(second, sources());
        lifecycle.acquire(first).requireValid();
        lifecycle.acquire(second).requireValid();
        assertThrows(IllegalStateException.class, () -> lifecycle.onStarting(first, sources()));
        lifecycle.onStopping(first);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> lifecycle.acquire(first)).getMessage());
        lifecycle.acquire(second).requireValid();
    }

    @Test void lifecycleReloadStartInvalidatesAndFinishRepublishes() {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        lifecycle.onStarting(server, sources());
        var old = lifecycle.acquire(server);
        lifecycle.onReloadStarted(server);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class, old::requireValid).getMessage());
        lifecycle.onReloadFinished(server, true, sources());
        lifecycle.acquire(server).requireValid();
        assertThrows(IllegalStateException.class, old::requireValid);
    }

    @Test void lifecycleStoppingRemovesServerEntry() {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        lifecycle.onStarting(server, sources());
        var old = lifecycle.acquire(server);
        lifecycle.onStopping(server);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class, old::requireValid).getMessage());
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> lifecycle.acquire(server)).getMessage());
    }

    private static final class RecordingRegistrar implements Minecraft121SettingsLifecycle.Registrar {
        final IdentityHashMap<Event<?>, Object> listeners = new IdentityHashMap<>();
        @Override public <T> void register(Event<T> event, T listener) {
            assertNull(listeners.put(event, listener));
        }
        @SuppressWarnings("unchecked") <T> T get(Event<T> event) { return (T) listeners.get(event); }
    }

    private static final class RecordingCore implements Minecraft121SettingsLifecycle.Core {
        final List<String> calls = new ArrayList<>();
        final Object reservation = new Object();
        final Object action = new Object();
        Object claim = action;
        Minecraft121SettingsContexts.Sources received;
        @Override public Object reserveStart(Object server) { assertNull(server); calls.add("reserve"); return reservation; }
        @Override public void completeStart(Object token, Minecraft121SettingsContexts.Sources sources) {
            assertSame(reservation, token); received = sources; calls.add("publish");
        }
        @Override public Object startReload(Object server) { assertNull(server); calls.add("start"); return action; }
        @Override public void adoptReload(Object token) { assertSame(action, token); calls.add("adopt"); }
        @Override public Object claimEnd(Object server) { assertNull(server); calls.add("claim"); return claim; }
        @Override public void finishEnd(Object token, boolean success, Minecraft121SettingsContexts.Sources sources) {
            assertSame(action, token); received = sources; calls.add(success ? "finish-success" : "finish-failure");
        }
        @Override public void stop(Object server) { assertNull(server); calls.add("stop"); claim = null; }
    }

    @Test void lifecycleRegistersAllFourFabricEventsAndDelegatesToCore() {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var registrar = new RecordingRegistrar();
        var core = new RecordingCore();
        var source = sources();
        var factoryCalls = new AtomicInteger();
        lifecycle.bind(registrar, ignored -> { factoryCalls.incrementAndGet(); return source; },
                ignored -> true, (ignored, task) -> task.run(), core);
        assertEquals(4, registrar.listeners.size());
        assertTrue(registrar.listeners.keySet().containsAll(List.of(ServerLifecycleEvents.SERVER_STARTING,
                ServerLifecycleEvents.START_DATA_PACK_RELOAD, ServerLifecycleEvents.END_DATA_PACK_RELOAD,
                ServerLifecycleEvents.SERVER_STOPPING)));
        registrar.get(ServerLifecycleEvents.SERVER_STARTING).onServerStarting(null);
        registrar.get(ServerLifecycleEvents.START_DATA_PACK_RELOAD).startDataPackReload(null, null);
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        registrar.get(ServerLifecycleEvents.SERVER_STOPPING).onServerStopping(null);
        assertEquals(List.of("reserve", "publish", "start", "adopt", "claim", "finish-success", "stop"), core.calls);
        assertSame(source, core.received);
        assertEquals(2, factoryCalls.get());
    }

    @Test void lifecycleForeignStartRevokesBeforeOwnerDispatch() throws Exception {
        var registrar = new RecordingRegistrar();
        var recording = new RecordingCore();
        var queued = new AtomicReference<Runnable>();
        new Minecraft121SettingsLifecycle().bind(registrar, ignored -> sources(), ignored -> false,
                (ignored, task) -> queued.set(task), recording);
        registrar.get(ServerLifecycleEvents.START_DATA_PACK_RELOAD).startDataPackReload(null, null);
        assertEquals(List.of("start"), recording.calls, "真 listener 必須先撤銷，才交給 owner queue");
        assertTrue(queued.get() != null);
        queued.get().run();
        assertEquals(List.of("start", "adopt"), recording.calls);

        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        lifecycle.onStarting(server, sources());
        var old = lifecycle.acquire(server);
        var action = new AtomicReference<Minecraft121SettingsLifecycle.ReloadAction>();
        var worker = new Thread(() -> action.set(lifecycle.onReloadStarted(server)));
        runWorker(worker);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class, old::requireValid).getMessage());
        lifecycle.adopt(action.get());
        lifecycle.onReloadFinished(server, true, sources());
        lifecycle.acquire(server).requireValid();
    }

    @Test void lifecycleStoppingDetachesAndLateEndSkipsFactory() throws Exception {
        var idle = new Minecraft121SettingsLifecycle();
        var idleServer = new Object();
        idle.onStarting(idleServer, sources());
        var idleLease = idle.acquire(idleServer);
        var idleStop = new Thread(() -> idle.onStopping(idleServer));
        runWorker(idleStop);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                idleLease::requireValid).getMessage());
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> idle.acquire(idleServer)).getMessage());
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        lifecycle.onStarting(server, sources());
        var old = lifecycle.acquire(server);
        var action = lifecycle.onReloadStarted(server);
        var worker = new Thread(() -> lifecycle.onStopping(server));
        runWorker(worker);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class, old::requireValid).getMessage());
        lifecycle.adopt(action);
        assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                () -> lifecycle.acquire(server)).getMessage());

        var registrar = new RecordingRegistrar();
        var core = new RecordingCore();
        var calls = new AtomicInteger();
        var onOwner = new java.util.concurrent.atomic.AtomicBoolean(true);
        var queued = new AtomicReference<Runnable>();
        lifecycle.bind(registrar, ignored -> { calls.incrementAndGet(); return sources(); },
                ignored -> onOwner.get(), (ignored, task) -> queued.set(task), core);
        core.claim = null;
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        assertEquals(0, calls.get(), "poisoned claim 必須在 factory 前短路");
        registrar.get(ServerLifecycleEvents.SERVER_STOPPING).onServerStopping(null);
        assertTrue(core.calls.contains("stop"), "STOP listener 不可等 owner queue");
        assertNull(queued.get());
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        onOwner.set(false);
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        onOwner.set(true);
        assertEquals(0, calls.get());
        core.claim = core.action;
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        assertEquals(1, calls.get());
    }

    @Test void lifecycleOverlappingStartsNeverRepublish() {
        for (boolean firstEndSuccess : new boolean[] { true, false }) {
            var lifecycle = new Minecraft121SettingsLifecycle();
            var server = new Object();
            lifecycle.onStarting(server, sources());
            var old = lifecycle.acquire(server);
            lifecycle.onReloadStarted(server);
            lifecycle.onReloadStarted(server);
            lifecycle.onReloadFinished(server, firstEndSuccess, sources());
            lifecycle.onReloadFinished(server, !firstEndSuccess, sources());
            assertThrows(IllegalStateException.class, old::requireValid);
            assertEquals("CONTEXT_UNAVAILABLE", assertThrows(IllegalStateException.class,
                    () -> lifecycle.acquire(server)).getMessage());
        }
    }

    @Test void lifecycleSingleFailureThenNextSingleSuccess() {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        lifecycle.onStarting(server, sources());
        var old = lifecycle.acquire(server);
        lifecycle.onReloadStarted(server);
        lifecycle.onReloadFinished(server, false, null);
        assertThrows(IllegalStateException.class, () -> lifecycle.acquire(server));
        lifecycle.onReloadStarted(server);
        lifecycle.onReloadFinished(server, true, sources());
        lifecycle.acquire(server).requireValid();
        assertThrows(IllegalStateException.class, old::requireValid);

        var stopped = new Minecraft121SettingsLifecycle();
        var other = new Object();
        assertThrows(IllegalStateException.class, () -> stopped.onStarting(other, () -> {
            stopped.onStopping(other);
            return sources();
        }));
        assertThrows(IllegalStateException.class, () -> stopped.acquire(other));
    }
    private static void runWorker(Thread worker) throws InterruptedException {
        var failure = new AtomicReference<Throwable>();
        worker.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        worker.setDaemon(true);
        worker.start();
        worker.join(5000);
        assertTrue(!worker.isAlive(), "worker 必須在界限內結束");
        assertNull(failure.get(), "worker 不可隱藏例外");
    }

    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test void lifecycleInitialFactoryFailureDetachesReservation() throws Exception {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        var entry = new AtomicReference<Object>();
        var failure = new IllegalStateException("factory failed");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> lifecycle.onStarting(server, () -> {
            try { entry.set(((java.util.Map<?, ?>) field(lifecycle, "entries")).get(server)); }
            catch (Exception error) { throw new AssertionError(error); }
            throw failure;
        })));
        assertTrue(((java.util.Map<?, ?>) field(lifecycle, "entries")).isEmpty(), "初次 factory 失敗須移除 reservation");
        assertNull(field(field(field(entry.get(), "contexts"), "cell"), "payload"), "失敗 reservation 不保活 owner");
    }
    @Test void lifecycleFinalClaimRejectsStopOverlapAndDuplicate() {
        for (int interruption = 0; interruption < 3; interruption++) {
            var lifecycle = new Minecraft121SettingsLifecycle();
            var server = new Object();
            lifecycle.onStarting(server, sources());
            var old = lifecycle.acquire(server);
            var queued = lifecycle.onReloadStarted(server);
            var claim = lifecycle.prepareEnd(server);
            assertTrue(claim != null, "END 可先於 queued adopt 取得原票");
            if (interruption == 0) lifecycle.onStopping(server);
            else if (interruption == 1) lifecycle.onReloadStarted(server);
            else assertNull(lifecycle.prepareEnd(server), "同票不可重複 factory claim");
            lifecycle.finishEnd(claim, true, sources());
            lifecycle.adopt(queued);
            assertThrows(IllegalStateException.class, old::requireValid);
            assertThrows(IllegalStateException.class, () -> lifecycle.acquire(server));
            assertNull(lifecycle.prepareEnd(server));
        }
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        assertThrows(IllegalStateException.class, () -> lifecycle.onStarting(server, () -> {
            lifecycle.onReloadStarted(server);
            return sources();
        }));
        assertThrows(IllegalStateException.class, () -> lifecycle.acquire(server));
    }

    @Test void lifecycleBinderFailureSkipsSourcesAndFactoryThrowFinishesOriginalClaim() {
        var registrar = new RecordingRegistrar();
        var core = new RecordingCore();
        var calls = new AtomicInteger();
        var failure = new IllegalStateException("factory failed");
        new Minecraft121SettingsLifecycle().bind(registrar, ignored -> { calls.incrementAndGet(); throw failure; },
                ignored -> true, (ignored, task) -> task.run(), core);
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, false);
        assertEquals(0, calls.get(), "失敗 END 不讀 Sources");
        assertEquals(List.of("claim", "finish-failure"), core.calls);
        core.calls.clear();
        registrar.get(ServerLifecycleEvents.END_DATA_PACK_RELOAD).endDataPackReload(null, null, true);
        assertEquals(1, calls.get());
        assertEquals(List.of("claim", "finish-failure"), core.calls);
        assertNull(core.received);
        core.calls.clear();
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> registrar.get(ServerLifecycleEvents.SERVER_STARTING).onServerStarting(null)));
        assertEquals(List.of("reserve", "stop"), core.calls, "初次 factory 例外必須清理 reservation");
    }

    @Test void lifecycleStoppedEntryAndQueuedActionHaveNoStrongOwnerEdges() throws Exception {
        var lifecycle = new Minecraft121SettingsLifecycle();
        var server = new Object();
        var source = sources();
        lifecycle.onStarting(server, source);
        Object entry = ((java.util.Map<?, ?>) field(lifecycle, "entries")).get(server);
        var action = lifecycle.onReloadStarted(server);
        var registrar = new RecordingRegistrar();
        var queued = new AtomicReference<Runnable>();
        var core = new RecordingCore();
        lifecycle.bind(registrar, ignored -> source, ignored -> false, (ignored, task) -> queued.set(task), core);
        registrar.get(ServerLifecycleEvents.START_DATA_PACK_RELOAD).startDataPackReload(null, null);
        runWorker(new Thread(() -> lifecycle.onStopping(server)));
        assertNull(field(field(field(entry, "contexts"), "cell"), "payload"));
        assertNoStrongOwner(entry, server, source);
        assertNoStrongOwner(action, server, source);
        assertNoStrongOwner(queued.get(), server, source);
        assertTrue(((java.util.Map<?, ?>) field(lifecycle, "entries")).isEmpty());
        lifecycle.adopt(action);
        assertThrows(IllegalStateException.class, () -> lifecycle.acquire(server));
    }

    private static void assertNoStrongOwner(Object root, Object server, Minecraft121SettingsContexts.Sources source)
            throws IllegalAccessException {
        var seen = new IdentityHashMap<Object, Boolean>();
        var pending = new java.util.ArrayDeque<Object>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Object value = pending.remove();
            if (seen.put(value, true) != null) continue;
            assertTrue(value != server && value != source && value != source.registries()
                    && value != source.resources() && value != source.templates() && value != source.session()
                    && !(value instanceof Thread), "停止後 metadata 不可強持 owner/native/thread");
            if (value instanceof java.lang.ref.Reference<?> || value.getClass().isEnum()) continue;
            if (value instanceof java.util.Map<?, ?> map) {
                for (var item : map.entrySet()) { pending.add(item.getKey()); pending.add(item.getValue()); }
            } else if (value instanceof Iterable<?> items) {
                for (Object item : items) if (item != null) pending.add(item);
            } else if (value.getClass().getName().startsWith("dev.quantumchamber.")) {
                for (var field : value.getClass().getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                    field.setAccessible(true);
                    Object child = field.get(value);
                    if (child != null) pending.add(child);
                }
            }
        }
    }
}
