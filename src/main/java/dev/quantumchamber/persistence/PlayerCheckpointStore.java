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
    private PlayerCheckpointStore() {}

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
        if (!Platform.isWindows()) throw new IOException("checkpoint 尚未驗證非 Windows 平台，保守拒絕");
        try {
            new WindowsPlayerCheckpointVerifier(new WindowsCheckpointNative()).verify(path, expected);
        } catch (RuntimeException | LinkageError exception) {
            throw new IOException("Windows checkpoint 原生相依性不可用", exception);
        }
    }
}
