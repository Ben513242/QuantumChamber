package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniverseCatalogStoreTest {
    @TempDir
    Path directory;

    @BeforeAll
    static void initializeNativeVersion() {
        net.minecraft.SharedConstants.createGameVersion();
    }

    @Test
    void compressedRoundtripAndAtomicReplacementPreserveExactWrapper() throws Exception {
        var target = directory.resolve("quantumchamber_universes.dat");
        var first = wrapper(7);

        UniverseCatalogStore.write(target, first);
        assertEquals(first, UniverseCatalogStore.read(target));

        var replacement = wrapper(8);
        UniverseCatalogStore.write(target, replacement);

        assertEquals(replacement, NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes()));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "成功替換後不得殘留同目錄暫存檔");
        }
    }

    @Test
    void malformedOrNonRegularCatalogAndInvalidWrapperFailClosed() throws Exception {
        var broken = directory.resolve("broken.dat");
        Files.writeString(broken, "not nbt");
        assertThrows(IOException.class, () -> UniverseCatalogStore.read(broken));
        assertThrows(IOException.class, () -> UniverseCatalogStore.read(directory));
        assertThrows(NoSuchFileException.class,
                () -> UniverseCatalogStore.read(directory.resolve("missing.dat")));

        var invalid = new NbtCompound();
        invalid.putInt("DataVersion", 3953);
        NbtIo.writeCompressed(invalid, broken);
        assertThrows(IOException.class, () -> UniverseCatalogStore.read(broken));
    }

    @Test
    void failedWriteRetainsExistingCatalog() throws Exception {
        var target = directory.resolve("catalog.dat");
        var original = wrapper(7);
        UniverseCatalogStore.write(target, original);

        assertThrows(IOException.class,
                () -> UniverseCatalogStore.write(target.resolve("child.dat"), wrapper(8)));

        assertEquals(original, UniverseCatalogStore.read(target));
    }

    private static NbtCompound wrapper(long ordinal) {
        var data = new NbtCompound();
        data.putInt("SchemaVersion", 1);
        data.putLong("FixtureOrdinal", ordinal);
        var wrapper = new NbtCompound();
        wrapper.put("data", data);
        NbtHelper.putDataVersion(wrapper);
        return wrapper;
    }
}
