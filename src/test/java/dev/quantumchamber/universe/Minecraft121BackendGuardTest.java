package dev.quantumchamber.universe.minecraft121;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.MaterializeResult;
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
