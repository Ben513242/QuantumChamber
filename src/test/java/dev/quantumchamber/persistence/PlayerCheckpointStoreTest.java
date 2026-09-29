package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PlayerCheckpointStoreTest {
    @TempDir Path directory;
    @BeforeEach void resolveOwnedFixture() throws IOException {
        Path original = directory;
        directory = directory.toRealPath();
        System.err.println("CHECKPOINT_STORE_FIXTURE original=" + original + " fixture=" + directory);
    }
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
    @Test void startCapabilityPrecheckAcceptsLocalNtfsPlayerdataAndRejectsJunction() throws Exception {
        Path playerdata = Files.createDirectory(directory.resolve("playerdata"));
        assertEquals(java.util.Optional.empty(), PlayerCheckpointStore.unsupportedReason(playerdata));
        try (var listing = Files.list(playerdata)) { assertEquals(0, listing.count(), "預檢不得建立玩家檔"); }
        Path junction = directory.resolve("junction-playerdata");
        var process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", junction.toString(), playerdata.toString())
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.Charset.defaultCharset());
        assertEquals(0, process.waitFor(), output);
        var reason = PlayerCheckpointStore.unsupportedReason(junction);
        assertTrue(reason.isPresent() && reason.get().contains("reparse"), String.valueOf(reason));
    }
    private static NbtCompound snapshot() {
        var nbt = new NbtCompound(); nbt.putInt("DataVersion", 3953); nbt.putInt("foodLevel", 20);
        var extra = new NbtCompound(); extra.putInt("Energy", 41); nbt.put("othermod:data", extra); return nbt;
    }
}
