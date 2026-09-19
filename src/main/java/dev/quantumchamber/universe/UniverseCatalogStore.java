package dev.quantumchamber.universe;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/** 以同目錄暫存、強制寫入、原子替換及完整回讀保存經檢查的 catalog。 */
public final class UniverseCatalogStore {
    private UniverseCatalogStore() {
    }

    public static void write(Path target, NbtCompound wrapped) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(wrapped, "wrapped");
        validateWrapper(wrapped);
        var expected = wrapped.copy();
        var absolute = target.toAbsolutePath().normalize();
        var parent = absolute.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, absolute.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(expected, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            Files.move(temporary, absolute,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            var actual = read(absolute);
            if (!expected.equals(actual)) {
                throw new IOException("Universe catalog atomic replace 後 exact readback 不符: " + absolute);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static NbtCompound read(Path target) throws IOException {
        Objects.requireNonNull(target, "target");
        var attributes = Files.readAttributes(
                target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Universe catalog 必須是正式一般檔案: " + target);
        }
        try {
            var wrapped = NbtIo.readCompressed(target, NbtSizeTracker.ofUnlimitedBytes());
            validateWrapper(wrapped);
            return wrapped;
        } catch (RuntimeException exception) {
            throw new IOException("Universe catalog NBT 無法解析: " + target, exception);
        }
    }

    private static void validateWrapper(NbtCompound wrapped) throws IOException {
        if (!wrapped.contains("data", NbtElement.COMPOUND_TYPE)
                || !wrapped.contains("DataVersion", NbtElement.INT_TYPE)) {
            throw new IOException("Universe catalog 缺少 data 或 DataVersion，或型別不正確");
        }
    }
}
