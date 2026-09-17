package dev.quantumchamber.persistence;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/** journal 使用同目錄暫存、force 與原子替換；不支援原子移動時直接拒絕。 */
public final class SessionJournalStore {
    private SessionJournalStore() {}

    public static void write(Path target, NbtCompound wrapped) throws IOException {
        validateWrapper(wrapped);
        Path absolute = target.toAbsolutePath().normalize();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(wrapped, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static NbtCompound read(Path target) throws IOException {
        var attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("journal 必須是正式一般檔案：" + target);
        try {
            var wrapped = NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes());
            validateWrapper(wrapped);
            return wrapped;
        } catch (RuntimeException exception) {
            throw new IOException("journal NBT 無法解析：" + target, exception);
        }
    }

    private static void validateWrapper(NbtCompound wrapped) throws IOException {
        if (!wrapped.contains("data", 10) || !wrapped.contains("DataVersion", 3)) {
            throw new IOException("journal 缺少 data 或 DataVersion，或型別不正確");
        }
    }
}
