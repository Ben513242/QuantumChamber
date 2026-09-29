package dev.quantumchamber.persistence;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import com.sun.jna.Platform;
import dev.quantumchamber.mixin.PlayerManagerSaveInvoker;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.WorldSavePath;

/** 原生保存後的窄確認原語；不提供跨檔原子性，也不自行改寫玩家檔案。 */
public final class PlayerCheckpointStore {
    private static final String NON_WINDOWS = "checkpoint 尚未驗證非 Windows 平台，保守拒絕";

    private PlayerCheckpointStore() {}

    /**
     * 入場前的 checkpoint 能力預檢：與 {@link #verifyAndForce} 相同的平台判斷，並以同一個 verifier 對正式 playerdata 目錄
     * 檢查 Windows 磁碟路徑、本機固定 NTFS、同 volume 與每一層祖先的普通目錄／reparse 拒絕及 namespace probe。
     * 不開啟、不讀寫任何玩家檔；回傳空值才代表這台 server 可以嘗試原生 checkpoint。
     */
    public static Optional<String> unsupportedReason(MinecraftServer server) {
        return unsupportedReason(server.getSavePath(WorldSavePath.PLAYERDATA));
    }

    static Optional<String> unsupportedReason(Path playerdata) {
        if (!Platform.isWindows()) return Optional.of(NON_WINDOWS);
        try {
            new WindowsPlayerCheckpointVerifier(new WindowsCheckpointNative()).verifyDirectory(playerdata);
            return Optional.empty();
        } catch (IOException exception) {
            var reason = new StringBuilder(String.valueOf(exception.getMessage()));
            for (Throwable cause = exception.getCause(); cause != null; cause = cause.getCause()) reason.append("；原因：").append(cause);
            return Optional.of(reason.toString());
        } catch (RuntimeException | LinkageError exception) {
            return Optional.of("Windows checkpoint 原生相依性不可用：" + exception);
        }
    }

    public static void saveAndVerify(MinecraftServer server, ServerPlayerEntity player,
            Optional<PlayerRecoveryCheckpoint> expected) throws IOException {
        Objects.requireNonNull(expected, "expected");
        if (!server.isOnThread() || player.getServer() != server) throw new IOException("checkpoint 只能在所屬伺服器執行緒保存");
        verifyMarker(player, expected);
        try {
            ((PlayerManagerSaveInvoker) server.getPlayerManager()).quantumchamber$savePlayerData(player);
            verifyMarker(player, expected);
            // pinned 1.21 PlayerEntity serializer 本身加入 DataVersion；完整快照保留所有其他 mod 欄位。
            NbtCompound snapshot = player.writeNbt(new NbtCompound());
            Path official = server.getSavePath(WorldSavePath.PLAYERDATA).resolve(player.getUuidAsString() + ".dat");
            verifyAndForce(official, snapshot);
        } catch (RuntimeException exception) {
            throw new IOException("原生玩家 checkpoint 保存／讀回失敗", exception);
        }
    }

    private static void verifyMarker(ServerPlayerEntity player, Optional<PlayerRecoveryCheckpoint> expected) throws IOException {
        try {
            var actual = ((PlayerRecoveryCheckpointAccess) player).quantumchamber$getRecoveryCheckpoint();
            if (expected.isPresent() && !expected.equals(actual)) throw new IOException("runtime marker 與要求的 checkpoint 不符");
        } catch (IllegalStateException exception) {
            throw new IOException("玩家 marker 不健康", exception);
        }
    }

    static void verifyAndForce(Path path, NbtCompound expected) throws IOException {
        if (!Platform.isWindows()) throw new IOException(NON_WINDOWS);
        try {
            new WindowsPlayerCheckpointVerifier(new WindowsCheckpointNative()).verify(path, expected);
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("Windows checkpoint 原生相依性不可用", exception);
        }
    }
}
