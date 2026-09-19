package dev.quantumchamber.universe.minecraft121;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.SeedPolicy;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
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
