package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PlayerCheckpointStoreTest {
    @TempDir Path directory;
    @Test void fullNbtEqualityIncludesUnknownModKeysBeforeForce() throws Exception {
        var expected = snapshot(); var target = directory.resolve("player.dat");
        NbtIo.writeCompressed(expected, target);
        PlayerCheckpointStore.verifyAndForce(target, expected);
        var changed = expected.copy(); changed.getCompound("othermod:data").putInt("Energy", 42);
        assertThrows(IOException.class, () -> PlayerCheckpointStore.verifyAndForce(target, changed));
        assertEquals(expected, NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes()));
    }
    @Test void missingCorruptAndStaleOfficialFileCannotUseBackupAsSuccess() throws Exception {
        var expected = snapshot(); var target = directory.resolve("player.dat");
        NbtIo.writeCompressed(expected, directory.resolve("player.dat_old"));
        assertThrows(IOException.class, () -> PlayerCheckpointStore.verifyAndForce(target, expected));
        Files.write(target, new byte[] {0,1,2});
        assertThrows(IOException.class, () -> PlayerCheckpointStore.verifyAndForce(target, expected));
        var stale = expected.copy(); stale.putInt("foodLevel", 1); NbtIo.writeCompressed(stale, target);
        assertThrows(IOException.class, () -> PlayerCheckpointStore.verifyAndForce(target, expected));
    }
    private static NbtCompound snapshot() {
        var nbt = new NbtCompound(); nbt.putInt("DataVersion", 3953); nbt.putInt("foodLevel", 20);
        var extra = new NbtCompound(); extra.putInt("Energy", 41); nbt.put("othermod:data", extra); return nbt;
    }
}
