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
}
