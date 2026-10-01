package dev.quantumchamber.persistence;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/**
 * checked NBT 檔案的共用讀寫（M4 discovery 的寫法；M4 discovery 與 M5 的兩個 ledger 共用）：同目錄暫存檔、force、以正式讀取規則驗證暫存檔、
 * atomic move、strict 讀回並 exact 比對。
 * <p>所有讀取都經過 {@link ReadPort}：正式實作是 {@link #readFile}；pure JVM 測試由各 store／權威以 package-private 的 seam 傳入自己的 port，
 * 注入 IO 例外與「atomic move 完成、讀回失敗」（plan 決策 9）。
 * <p>檔案就是裸的 gzip NBT。catalog 與 journal 的 {@code {data, DataVersion}} wrapper 格式不同，不用本類別。
 */
public final class CheckedNbtFile {
    /** 讀入記憶體的檔案（壓縮後）上限；超過視為內容損壞。 */
    public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;
    /** 解壓後 NBT 的配置預算（與 M4 discovery 相同）。 */
    public static final long MAX_NBT_BYTES = 16L * 1024 * 1024;

    private CheckedNbtFile() {}

    /** 讀取正式檔或暫存檔的原始 bytes。檔案不存在必須丟 {@link java.nio.file.NoSuchFileException}。 */
    @FunctionalInterface public interface ReadPort { byte[] read(Path path) throws IOException; }

    /** 正式的 {@link ReadPort}：必須是一般檔案（不跟隨連結）；最多讀 {@link #MAX_FILE_BYTES}＋1 bytes，超過的部分由 {@link #parse} 判為損壞。 */
    public static byte[] readFile(Path path) throws IOException {
        var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("checked NBT 檔案必須是一般檔案：" + path);
        try (var input = Files.newInputStream(path)) { return input.readNBytes(MAX_FILE_BYTES + 1); }
    }

    /** 原始 bytes → NBT。這一步（含之後的 strict decode）的任何失敗都是內容損壞，不是 IO 例外。 */
    public static NbtCompound parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("checked NBT 檔案超過 " + MAX_FILE_BYTES + " bytes");
        return NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtSizeTracker.of(MAX_NBT_BYTES));
    }

    /** 經 port 讀檔並解析（{@code parse(io.read(path))}）。呼叫端要分辨 IO 與內容損壞時，改為分開呼叫 port 與 {@link #parse}。 */
    public static NbtCompound read(Path path, ReadPort io) throws IOException { return parse(io.read(path)); }

    /**
     * checked 寫入：同目錄暫存檔、force、以正式讀取規則（port＋{@link #parse}＋strictDecode）驗證暫存檔並 exact 比對、atomic move、
     * 再以同一規則讀回正式檔並 exact 比對。暫存檔驗證失敗時正式檔不變；atomic move 之後的讀回失敗時，正式檔已是新內容（呼叫端依 spec §6 收斂，
     * 本方法不還原）。暫存檔一律在結束時刪除。
     * @param label 例外訊息的權威名稱；訊息只帶正式檔名、不帶路徑（暫存檔名每次隨機，帶進訊息會讓 WARN 的去重鍵每次不同）
     */
    public static void write(Path target, NbtCompound expected, ReadPort io, Consumer<NbtCompound> strictDecode, String label) throws IOException {
        Objects.requireNonNull(expected, "expected"); Objects.requireNonNull(io, "io");
        Objects.requireNonNull(strictDecode, "strictDecode"); Objects.requireNonNull(label, "label");
        var absolute = Objects.requireNonNull(target, "target").toAbsolutePath().normalize();
        var parent = absolute.getParent();
        Files.createDirectories(parent);
        var temporary = Files.createTempFile(parent, absolute.getFileName() + ".", ".tmp");
        try {
            NbtIo.writeCompressed(expected, temporary);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            // 先用正式讀取規則驗暫存檔，避免讀不回來（例如超過預算）的內容取代上一份有效權威。
            verify(temporary, absolute, expected, io, strictDecode, label);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            verify(absolute, absolute, expected, io, strictDecode, label);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void verify(Path path, Path target, NbtCompound expected, ReadPort io, Consumer<NbtCompound> strictDecode, String label)
            throws IOException {
        var actual = parse(io.read(path));
        strictDecode.accept(actual);
        if (!expected.equals(actual)) {
            var which = path.equals(target) ? "正式檔" : "暫存檔";
            throw new IOException(label + " 的" + which + " exact readback 不符（" + target.getFileName() + "）");
        }
    }
}
