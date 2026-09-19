package dev.quantumchamber.universe.minecraft121;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.DynamicWorldRuntimeState;
import dev.quantumchamber.universe.UniverseRuntimeRegistry;
import dev.quantumchamber.universe.UnloadResult;
import dev.quantumchamber.universe.SeedPolicy;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Minecraft121BackendGuardTest {
    private static final String OWNED_PATH = "universe/00000000-0000-0000-0000-000000000005/overworld";
    @TempDir Path temp;

    @Test
    void rejectsEveryVersionExceptExactPinnedVersion() {
        for (String version : List.of("1.21.1", "1.20.6", "1.21-pre1", "1.21+custom", "")) {
            assertThrows(IllegalArgumentException.class,
                    () -> Minecraft121DynamicDimensionBackend.requireVersion(version));
        }
        assertDoesNotThrow(() -> Minecraft121DynamicDimensionBackend.requireVersion("1.21"));
    }

    @Test
    void rejectsVanillaKeysEvenWithOverworldProfile() {
        for (String path : List.of("overworld", "the_nether", "the_end")) {
            assertThrows(IllegalArgumentException.class, () -> Minecraft121DynamicDimensionBackend
                    .requireDescriptor(descriptor("minecraft", path, 1)));
        }
    }

    @Test
    void rejectsForeignNamespaceMalformedUuidAndUnsupportedStoragePolicy() {
        for (var candidate : List.of(descriptor("other", OWNED_PATH, 1),
                descriptor("quantumchamber", "universe/not-a-uuid/overworld", 1),
                descriptor("quantumchamber", OWNED_PATH + "/extra", 1),
                descriptor("quantumchamber", OWNED_PATH, 2))) {
            assertThrows(IllegalArgumentException.class,
                    () -> Minecraft121DynamicDimensionBackend.requireDescriptor(candidate));
        }
        assertDoesNotThrow(() -> Minecraft121DynamicDimensionBackend.requireDescriptor(owned()));
    }

    @Test
    void refusesReplacementEvenWhenObjectsAreEqual() {
        Object expected = new String("same-value");
        Object replacement = new String("same-value");
        assertEquals(expected, replacement);
        assertThrows(IllegalStateException.class,
                () -> Minecraft121DynamicDimensionBackend.requireMapIdentity(replacement, expected));
        assertThrows(IllegalStateException.class,
                () -> Minecraft121DynamicDimensionBackend.requireMapIdentity(replacement, null));
        assertDoesNotThrow(() -> Minecraft121DynamicDimensionBackend.requireMapIdentity(expected, expected));
        assertDoesNotThrow(() -> Minecraft121DynamicDimensionBackend.requireMapIdentity(null, null));
    }

    @Test
    void acceptsMissingOwnedStorageWithoutCreatingDirectories() throws Exception {
        Path root = Files.createDirectory(temp.resolve("save")).toRealPath();
        Path directory = root.resolve("dimensions/quantumchamber/" + OWNED_PATH);
        assertEquals(directory, Minecraft121DynamicDimensionBackend.requireStorage(root, directory, owned()));
        assertFalse(Files.exists(directory));
        Files.createDirectories(directory);
        assertEquals(directory.toRealPath(),
                Minecraft121DynamicDimensionBackend.requireStorage(root, directory, owned()));
    }

    @Test
    void rejectsExternalAndSiblingPrefixStorage() throws Exception {
        Path root = Files.createDirectory(temp.resolve("save")).toRealPath();
        for (Path directory : List.of(temp.resolve("external").resolve(OWNED_PATH),
                root.resolve("dimensions/quantumchamber/universe-escape/overworld"),
                root.resolve("DIM-1"), root)) {
            assertThrows(IllegalArgumentException.class,
                    () -> Minecraft121DynamicDimensionBackend.requireStorage(root, directory, owned()));
        }
    }

    @Test
    void rejectsDifferentUniverseDirectoryInsideOwnedNamespace() throws Exception {
        Path root = Files.createDirectory(temp.resolve("save")).toRealPath();
        Path other = root.resolve("dimensions/quantumchamber/universe/00000000-0000-0000-0000-000000000006/overworld");
        assertThrows(IllegalArgumentException.class,
                () -> Minecraft121DynamicDimensionBackend.requireStorage(root, other, owned()));
    }

    @Test
    void loadObserverRemovalRestoresExactWorldForShutdown() {
        var ownership = new Minecraft121DynamicDimensionBackend.FailureOwnership<Object, String, Object>();
        Object server = new Object();
        Object created = new Object();
        var worlds = new HashMap<String, Object>();
        worlds.put("owned", created);
        worlds.remove("owned");

        ownership.retainAfterLoadFailure(server, "owned", created, worlds);

        assertSame(created, worlds.get("owned"));
        assertTrue(ownership.isUnhealthy(server));
        assertTrue(ownership.quarantined(server).isEmpty());
    }

    @Test
    void loadObserverFailureWithUnchangedOwnerDoesNotQuarantineNativeShutdownWorld() {
        var ownership = new Minecraft121DynamicDimensionBackend.FailureOwnership<Object, String, Object>();
        Object server = new Object();
        Object created = new Object();
        var worlds = new HashMap<String, Object>();
        worlds.put("owned", created);

        ownership.retainAfterLoadFailure(server, "owned", created, worlds);

        assertSame(created, worlds.get("owned"));
        assertEquals(1, worlds.size());
        assertTrue(ownership.isUnhealthy(server));
        assertTrue(ownership.quarantined(server).isEmpty());
    }

    @Test
    void loadObserverReplacementIsUntouchedAndCreatedIsQuarantinedForExactServer() {
        var ownership = new Minecraft121DynamicDimensionBackend.FailureOwnership<Object, String, Object>();
        Object server = new String("server");
        Object otherServer = new String("server");
        Object created = new Object();
        Object replacement = new Object();
        var worlds = new HashMap<String, Object>();
        worlds.put("owned", created);
        worlds.put("owned", replacement);

        ownership.retainAfterLoadFailure(server, "owned", created, worlds);
        ownership.retainAfterLoadFailure(server, "owned", created, worlds);

        assertSame(replacement, worlds.get("owned"));
        assertEquals(1, ownership.quarantined(server).size());
        assertSame(created, ownership.quarantined(server).getFirst());
        assertThrows(UnsupportedOperationException.class, () -> ownership.quarantined(server).clear());
        assertTrue(ownership.isUnhealthy(server));
        assertFalse(ownership.isUnhealthy(otherServer));
        assertTrue(ownership.quarantined(otherServer).isEmpty());
    }

    @Test
    void recoverablePreLoadExceptionMayRollbackWithoutStopping() {
        var events = new ArrayList<String>();
        var status = Minecraft121DynamicDimensionBackend.finishFailure(new IOException("fixture"),
                () -> events.add("CLOSE"), failure -> events.add("STOP"));
        assertEquals(MaterializeResult.Status.FAILED_ROLLED_BACK, status);
        assertEquals(List.of("CLOSE"), events);
    }

    @Test
    void fatalPreLoadErrorStillStopsAndRethrowsAfterSuccessfulCleanup() {
        var fatal = new InjectedFatalError();
        var ownership = new Minecraft121DynamicDimensionBackend.FailureOwnership<Object, String, Object>();
        Object server = new Object();
        var events = new ArrayList<String>();
        assertSame(fatal, assertThrows(InjectedFatalError.class, () -> Minecraft121DynamicDimensionBackend
                .finishFailure(fatal, () -> events.add("CLOSE"), failure -> {
                    ownership.markUnhealthy(server);
                    events.add("STOP");
                })));
        assertEquals(List.of("CLOSE", "STOP"), events);
        assertTrue(ownership.isUnhealthy(server));
    }

    @Test
    void fatalPostLoadErrorCannotBecomeAnOrdinaryUnhealthyResult() {
        var fatal = new InjectedFatalError();
        var events = new ArrayList<String>();
        assertSame(fatal, assertThrows(InjectedFatalError.class, () -> Minecraft121DynamicDimensionBackend
                .finishFailure(fatal, null, failure -> events.add("STOP"))));
        assertEquals(List.of("STOP"), events);
    }

    @Test
    void fatalErrorDuringRollbackIsRethrownAndRetainsOriginalException() {
        var original = new IOException("fixture original");
        var fatal = new InjectedFatalError();
        var events = new ArrayList<String>();
        assertSame(fatal, assertThrows(InjectedFatalError.class, () -> Minecraft121DynamicDimensionBackend
                .finishFailure(original, () -> { throw fatal; }, failure -> events.add("STOP"))));
        assertEquals(List.of("STOP"), events);
        assertArrayEquals(new Throwable[] {original}, fatal.getSuppressed());
    }

    @Test
    void cleanupAndStopFailuresCannotHideTheOriginalFatalError() {
        var fatal = new InjectedFatalError();
        var cleanupFailure = new IOException("fixture cleanup");
        var stopFailure = new IllegalStateException("fixture stop");
        var events = new ArrayList<String>();
        assertSame(fatal, assertThrows(InjectedFatalError.class, () -> Minecraft121DynamicDimensionBackend
                .finishFailure(fatal, () -> { throw cleanupFailure; }, failure -> {
                    events.add("STOP");
                    throw stopFailure;
                })));
        assertEquals(List.of("STOP"), events);
        assertArrayEquals(new Throwable[] {cleanupFailure, stopFailure}, fatal.getSuppressed());
    }

    private static final class InjectedFatalError extends Error { }

    @Test
    void activeUnloadBlocksOperationsAndReleasesOnlyAfterFlushUnloadDiscardRemoveClose() {
        var fixture = new UnloadFixture(false);
        assertEquals(UnloadResult.UNLOADED, fixture.unload());
        assertEquals(List.of("RESOURCES", "DRAIN", "FLUSH", "POST_FLUSH", "UNLOAD", "DISCARD", "REMOVE", "CLOSE"), fixture.events);
        assertEquals(DynamicWorldRuntimeState.ABSENT, fixture.runtime.state(fixture.server, owned()));
        assertFalse(fixture.worlds.containsKey(owned().worldKey()));
        assertFalse(fixture.ownership.isUnhealthy(fixture.server));
        assertEquals(UnloadResult.REJECTED_BUSY, fixture.unload());
        assertEquals(1, fixture.events.stream().filter("UNLOAD"::equals).count());
    }

    @Test
    void unloadRejectsForeignServerAndEqualButDifferentExpectedWorldWithoutSideEffects() {
        var fixture = new UnloadFixture(false);
        assertEquals(UnloadResult.REJECTED_BUSY, fixture.unload(new Object(), fixture.expected));
        assertEquals(UnloadResult.REJECTED_BUSY, fixture.unload(fixture.server, new String("world")));
        assertTrue(fixture.events.isEmpty());
        assertEquals(DynamicWorldRuntimeState.ACTIVE, fixture.runtime.state(fixture.server, owned()));
    }

    @Test
    void activeUnloadNeverRemovesEqualButForeignCurrentMapOwner() {
        var fixture = new UnloadFixture(false);
        Object stranger = new String("world");
        fixture.worlds.put(owned().worldKey(), stranger);
        assertEquals(UnloadResult.REJECTED_BUSY, fixture.unload());
        assertSame(stranger, fixture.worlds.get(owned().worldKey()));
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void noQueuedWorkWithoutBlockingFlushReceiptIsUnsupportedAndNeverRemovesWorld() {
        var fixture = new UnloadFixture(false);
        fixture.flushReceipt = false;
        assertEquals(UnloadResult.UNLOAD_UNSUPPORTED, fixture.unload());
        assertEquals(List.of("RESOURCES", "DRAIN", "FLUSH", "STOP"), fixture.events);
        assertSame(fixture.expected, fixture.worlds.get(owned().worldKey()));
        assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, fixture.runtime.state(fixture.server, owned()));
        assertTrue(fixture.ownership.isUnhealthy(fixture.server));
    }

    @Test
    void flushFailureAndPostFlushProgressAreUnsupportedBeforeDestructiveBoundary() {
        for (boolean throwFlush : List.of(false, true)) {
            var fixture = new UnloadFixture(false);
            fixture.throwFlush = throwFlush;
            fixture.postFlushProgress = !throwFlush;
            assertEquals(UnloadResult.UNLOAD_UNSUPPORTED, fixture.unload());
            assertSame(fixture.expected, fixture.worlds.get(owned().worldKey()));
            assertFalse(fixture.events.contains("UNLOAD"));
            assertFalse(fixture.events.contains("REMOVE"));
            assertFalse(fixture.events.contains("CLOSE"));
            assertTrue(fixture.ownership.isUnhealthy(fixture.server));
        }
    }

    @Test
    void playersForcedChunksAndUnreleasableResourcesRejectBeforeFlush() {
        for (String blocked : List.of("PLAYERS", "FORCED", "RESOURCES")) {
            var fixture = new UnloadFixture(false);
            fixture.blocked = blocked;
            assertEquals(UnloadResult.UNLOAD_UNSUPPORTED, fixture.unload());
            assertSame(fixture.expected, fixture.worlds.get(owned().worldKey()));
            assertFalse(fixture.events.contains("FLUSH"));
            assertFalse(fixture.events.contains("UNLOAD"));
            assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, fixture.runtime.state(fixture.server, owned()));
            assertTrue(fixture.ownership.isUnhealthy(fixture.server));
        }
    }

    @Test
    void continuouslyQueuedWorkIsBoundedAndCannotFlushOrUnload() {
        var fixture = new UnloadFixture(false);
        fixture.continuousWork = true;
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () ->
                assertEquals(UnloadResult.UNLOAD_UNSUPPORTED, fixture.unload()));
        assertTrue(fixture.drainCalls > 1 && fixture.drainCalls < 100_000);
        assertFalse(fixture.events.contains("FLUSH"));
        assertSame(fixture.expected, fixture.worlds.get(owned().worldKey()));
    }

    @Test
    void quarantineWithForeignReplacementStaysBusyAndKeepsStrongReference() {
        var fixture = new UnloadFixture(true);
        Object stranger = new String("world");
        fixture.worlds.put(owned().worldKey(), stranger);
        assertEquals(UnloadResult.REJECTED_BUSY, fixture.unload());
        assertSame(stranger, fixture.worlds.get(owned().worldKey()));
        assertEquals(List.of(fixture.expected), fixture.ownership.quarantined(fixture.server));
        assertTrue(fixture.events.isEmpty());
    }

    @Test
    void freeKeyQuarantineUsesSameFlushContractWithoutAnyMapRemoveAndReleasesAfterClose() {
        var fixture = new UnloadFixture(true);
        assertEquals(UnloadResult.UNLOADED, fixture.unload());
        assertEquals(List.of("RESOURCES", "DRAIN", "FLUSH", "POST_FLUSH", "UNLOAD", "DISCARD", "CLOSE"), fixture.events);
        assertTrue(fixture.ownership.quarantined(fixture.server).isEmpty());
        assertTrue(fixture.runtime.snapshot(fixture.server).isEmpty());
        assertEquals(DynamicWorldRuntimeState.ABSENT, fixture.runtime.state(fixture.server, owned()));
        assertTrue(fixture.ownership.isUnhealthy(fixture.server));
    }

    @Test
    void quarantineCannotReleaseStrongReferenceWhenFlushOrCloseFails() {
        for (boolean failClose : List.of(false, true)) {
            var fixture = new UnloadFixture(true);
            fixture.throwClose = failClose;
            fixture.flushReceipt = failClose;
            assertEquals(failClose ? UnloadResult.FAILED_UNHEALTHY : UnloadResult.UNLOAD_UNSUPPORTED, fixture.unload());
            assertEquals(List.of(fixture.expected), fixture.ownership.quarantined(fixture.server));
            assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, fixture.runtime.state(fixture.server, owned()));
            assertFalse(fixture.events.contains("REMOVE"));
            assertTrue(fixture.ownership.isUnhealthy(fixture.server));
        }
    }

    @Test
    void expectedRemoveFailureAndCloseFailureKeepFailedOwnerAndNeverReattach() {
        for (boolean failClose : List.of(false, true)) {
            var fixture = new UnloadFixture(false);
            fixture.throwClose = failClose;
            fixture.rejectRemove = !failClose;
            assertEquals(UnloadResult.FAILED_UNHEALTHY, fixture.unload());
            assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, fixture.runtime.state(fixture.server, owned()));
            assertTrue(fixture.ownership.isUnhealthy(fixture.server));
            assertEquals(failClose ? null : fixture.expected, fixture.worlds.get(owned().worldKey()));
            assertEquals(failClose, fixture.events.contains("CLOSE"));
            assertEquals(UnloadResult.FAILED_UNHEALTHY, fixture.unload());
        }
    }

    @Test
    void unloadObserverReplacementFailsUnhealthyWithoutTouchingStrangerOrClosingExpected() {
        var fixture = new UnloadFixture(false);
        fixture.replaceAtUnload = true;
        assertEquals(UnloadResult.FAILED_UNHEALTHY, fixture.unload());
        assertSame(fixture.stranger, fixture.worlds.get(owned().worldKey()));
        assertFalse(fixture.events.contains("CLOSE"));
        assertTrue(fixture.ownership.isUnhealthy(fixture.server));
    }

    @Test
    void closeFatalIsRethrownAfterRecordingFailureAndNormalStop() {
        var fixture = new UnloadFixture(false);
        fixture.fatal = new InjectedFatalError();
        assertSame(fixture.fatal, assertThrows(InjectedFatalError.class, fixture::unload));
        assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, fixture.runtime.state(fixture.server, owned()));
        assertTrue(fixture.ownership.isUnhealthy(fixture.server));
        assertEquals("STOP", fixture.events.getLast());
        assertNull(fixture.worlds.get(owned().worldKey()));
    }

    private static final class UnloadFixture implements Minecraft121DynamicDimensionBackend.UnloadAccess<Object> {
        final Object server = new Object();
        final Object expected = new String("world");
        final Object stranger = new String("world");
        final UniverseRuntimeRegistry<Object, Object> runtime = new UniverseRuntimeRegistry<>();
        final Minecraft121DynamicDimensionBackend.FailureOwnership<Object, RegistryKey<World>, Object> ownership =
                new Minecraft121DynamicDimensionBackend.FailureOwnership<>();
        final List<String> events = new ArrayList<>();
        final HashMap<RegistryKey<World>, Object> worlds = new HashMap<>() {
            @Override public boolean remove(Object key, Object value) {
                events.add("REMOVE");
                assertSame(expected, value);
                return !rejectRemove && super.remove(key, value);
            }
        };
        final boolean quarantine;
        boolean flushReceipt = true;
        boolean throwFlush;
        boolean postFlushProgress;
        boolean continuousWork;
        boolean throwClose;
        boolean rejectRemove;
        boolean replaceAtUnload;
        String blocked = "";
        int drainCalls;
        boolean flushed;
        Error fatal;

        UnloadFixture(boolean quarantine) {
            this.quarantine = quarantine;
            runtime.beginMaterialize(server, owned());
            if (quarantine) {
                runtime.fail(server, owned(), null);
                runtime.retainFailedOwner(server, owned(), expected);
                worlds.put(owned().worldKey(), stranger);
                ownership.retainAfterLoadFailure(server, owned().worldKey(), expected, worlds);
                worlds.clear();
            } else {
                runtime.activate(server, owned(), expected);
                worlds.put(owned().worldKey(), expected);
            }
        }

        UnloadResult unload() { return unload(server, expected); }
        UnloadResult unload(Object requestedServer, Object requestedWorld) {
            return Minecraft121DynamicDimensionBackend.unloadOwned(requestedServer, owned(), requestedWorld,
                    worlds, runtime, ownership, this, failure -> events.add("STOP"), quarantine);
        }

        @Override public boolean playersEmpty(Object world) { assertSame(expected, world); return !blocked.equals("PLAYERS"); }
        @Override public boolean forcedChunksEmpty(Object world) { return !blocked.equals("FORCED"); }
        @Override public boolean releaseResources(Object world) {
            events.add("RESOURCES");
            assertEquals(DynamicWorldRuntimeState.UNLOADING, runtime.state(server, owned()));
            assertTrue(ownership.isBusy(server));
            assertTrue(runtime.resolveActive(server, owned()).isEmpty());
            assertThrows(IllegalStateException.class, () -> runtime.beginMaterialize(server, owned()));
            return !blocked.equals("RESOURCES");
        }
        @Override public boolean executeQueuedTasks(Object world) {
            drainCalls++;
            events.add(flushed ? "POST_FLUSH" : "DRAIN");
            return continuousWork || (flushed && postFlushProgress);
        }
        @Override public boolean flushBlocking(Object world) throws IOException {
            events.add("FLUSH");
            if (throwFlush) throw new IOException("注入 flush 失敗");
            flushed = true;
            return flushReceipt;
        }
        @Override public void dispatchUnload(Object world) {
            events.add("UNLOAD");
            assertTrue(flushed);
            assertSame(expected, world);
            if (replaceAtUnload) worlds.put(owned().worldKey(), stranger);
        }
        @Override public void discardWorld(Object world) { events.add("DISCARD"); assertSame(expected, world); }
        @Override public void close(Object world) throws IOException {
            events.add("CLOSE");
            assertNull(worlds.get(owned().worldKey()));
            assertEquals(DynamicWorldRuntimeState.UNLOADING, runtime.state(server, owned()));
            if (quarantine) assertEquals(List.of(expected), ownership.quarantined(server));
            if (fatal != null) throw fatal;
            if (throwClose) throw new IOException("注入 close 失敗");
        }
    }

    private static UniverseWorldDescriptor owned() {
        return descriptor("quantumchamber", OWNED_PATH, 1);
    }

    private static UniverseWorldDescriptor descriptor(String namespace, String path, int storageVersion) {
        RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, Identifier.of(namespace, path));
        return new UniverseWorldDescriptor(key, DimensionRole.OVERWORLD,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, SeedPolicy.SHARED_SAVE_SEED_V1,
                storageVersion);
    }
}
