package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@DisabledOnOs(OS.WINDOWS)
class NonWindowsPlayerCheckpointStoreTest {
    @TempDir Path directory;

    @Test void unsupportedPlatformRejectsBeforeNativeInitializationOrFileAccess() throws Exception {
        Path absent = directory.resolve("never-created.dat");
        IOException failure = assertThrows(IOException.class,
                () -> PlayerCheckpointStore.verifyAndForce(absent, new NbtCompound()));
        assertTrue(failure.getMessage().contains("非 Windows"), "必須是能力拒絕，不能用缺檔或載入 kernel32 失敗冒充");
        assertTrue(Files.notExists(absent), "不支援的平台不得自行建立玩家檔");
    }

    @Test void startCapabilityPrecheckRejectsBeforeNativeInitializationOrDirectoryAccess() {
        Path playerdata = directory.resolve("playerdata");
        var reason = PlayerCheckpointStore.unsupportedReason(playerdata);
        assertTrue(reason.isPresent() && reason.get().contains("非 Windows"), "入場前預檢必須是同一能力拒絕：" + reason);
        assertTrue(Files.notExists(playerdata), "預檢不得建立 playerdata 或載入 kernel32");
    }
}
