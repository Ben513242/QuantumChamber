package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionJournalStoreTest {
    @TempDir Path directory;
    @Test void compressedRoundtripAndReplacementPreserveExactWrappedData() throws Exception {
        var target = directory.resolve("sessions.dat");
        var wrapped = wrapped();
        SessionJournalStore.write(target, wrapped);
        assertEquals(wrapped, SessionJournalStore.read(target));
        wrapped.getCompound("data").putString("Extra", "replacement");
        SessionJournalStore.write(target, wrapped);
        assertEquals(wrapped, NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes()));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }
    @Test void unwritableTargetRetainsOldDataAndMalformedCompressionIsNotEmpty() throws Exception {
        var target = directory.resolve("sessions.dat");
        NbtIo.writeCompressed(wrapped(), target);
        assertThrows(IOException.class, () -> SessionJournalStore.write(target.resolve("child.dat"), wrapped()));
        assertEquals(wrapped(), NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes()));
        var broken = directory.resolve("broken.dat");
        Files.write(broken, new byte[] {1,2,3,4});
        assertThrows(IOException.class, () -> SessionJournalStore.read(broken));
        assertThrows(IOException.class, () -> SessionJournalStore.read(directory));
        assertThrows(NoSuchFileException.class, () -> SessionJournalStore.read(directory.resolve("missing.dat")));
    }
    @Test void missingWrapperFieldsFailClosed() throws Exception {
        var target = directory.resolve("sessions.dat");
        var noData = new NbtCompound(); noData.putInt("DataVersion", 3953);
        NbtIo.writeCompressed(noData, target);
        assertThrows(IOException.class, () -> SessionJournalStore.read(target));
    }
    static NbtCompound wrapped() {
        var nbt = new NbtCompound(); nbt.putInt("DataVersion", 3953);
        nbt.put("data", SessionRecoveryStateTest.fixture("ARMING", true)); return nbt;
    }
}
