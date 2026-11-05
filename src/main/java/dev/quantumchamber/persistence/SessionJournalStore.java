package dev.quantumchamber.persistence;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/** 單一 handle 的輸入快照；版本探測先於 NBT 物化，schema5 固定有界。 */
public final class SessionJournalStore {
    static final long SCHEMA_FIVE_BUDGET = 16L * 1024 * 1024;
    private SessionJournalStore() {}

    public static void write(Path target, NbtCompound wrapped) throws IOException {
        validateWrapper(wrapped);
        var data = wrapped.getCompound("data");
        if (!data.contains("SchemaVersion", 3) || data.getInt("SchemaVersion") < 1 || data.getInt("SchemaVersion") > 5)
            throw new IOException("journal 寫入版本缺失或未知");
        if (data.getInt("SchemaVersion") == 5) {
            writePrepared(target, prepareSchemaFive(wrapped, value -> SessionRecoveryState.fromNbt(value).requireHealthy()));
            return;
        }
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

    public static NbtCompound read(Path target) throws IOException { return readSnapshot(target).decode(); }

    /** 對候選同一份 bytes 做完整有界讀回；回呼只收 data，回傳前再核整份 wrapper exact。 */
    static PreparedWrite prepareSchemaFive(NbtCompound wrapped, java.util.function.Consumer<NbtCompound> strict) throws IOException {
        validateWrapper(wrapped);
        if (!wrapped.getCompound("data").contains("SchemaVersion", 3)
                || wrapped.getCompound("data").getInt("SchemaVersion") != 5)
            throw new IOException("schema5 preflight 不接受其他版本");
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(wrapped, bytes);
        var snapshot = new Snapshot(bytes.toByteArray()); var decoded = snapshot.decode();
        strict.accept(decoded.getCompound("data"));
        if (!wrapped.equals(decoded)) throw new IOException("schema5 preflight exact readback 不符");
        return new PreparedWrite(snapshot, decoded);
    }
    static final class PreparedWrite {
        private final Snapshot snapshot;
        private final NbtCompound wrapped;
        private final int schema;
        private PreparedWrite(Snapshot snapshot, NbtCompound wrapped) {
            this.snapshot = snapshot; this.wrapped = wrapped.copy(); this.schema = wrapped.getCompound("data").getInt("SchemaVersion");
        }
        NbtCompound wrapped() { return wrapped.copy(); }
        int schema() { return schema; }
    }
    static final class WriteFailure extends IOException {
        private final boolean possiblyMoved;
        WriteFailure(IOException cause, boolean possiblyMoved) {
            super("journal 原子寫入失敗，possiblyMoved=" + possiblyMoved, cause); this.possiblyMoved = possiblyMoved;
        }
        boolean possiblyMoved() { return possiblyMoved; }
    }
    /** 一進入 move 呼叫就視為可能已移動；不依 IOException 子型別猜磁碟結果。 */
    static void writePrepared(Path target, PreparedWrite prepared) throws IOException {
        Path absolute = target.toAbsolutePath().normalize(); Path temporary = null;
        boolean enteredMove = false; IOException failure = null;
        try {
            Files.createDirectories(absolute.getParent());
            temporary = Files.createTempFile(absolute.getParent(), absolute.getFileName() + ".", ".tmp");
            Files.write(temporary, prepared.snapshot.compressed, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            enteredMove = true;
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException error) {
            failure = error instanceof IOException io ? io : new IOException("journal 寫入失敗", error);
        } finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException | RuntimeException cleanup) {
                var io = cleanup instanceof IOException value ? value : new IOException("journal 暫存清理失敗", cleanup);
                if (failure == null) failure = io; else failure.addSuppressed(io);
            }
        }
        if (failure != null) throw new WriteFailure(failure, enteredMove);
    }

    static Snapshot readSnapshot(Path target) throws IOException {
        var attrs = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("journal 必須是正式一般檔案：" + target);
        // 開啟後不再讀 path；避免版本 probe 與正式 parse 分別看見兩個檔案。
        try (var stream = Files.newInputStream(target, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            return new Snapshot(stream.readAllBytes());
        }
    }

    /** compressed bytes 不外露；測試可在擷取後替換 path，直接驗同一輸入快照。 */
    static final class Snapshot {
        private final byte[] compressed;
        private Snapshot(byte[] compressed) { this.compressed = compressed.clone(); }
        NbtCompound decode() throws IOException {
            try {
                int schema = new RawWalker(compressed, false).scan();
                var tracker = schema == 5 ? NbtSizeTracker.of(SCHEMA_FIVE_BUDGET) : NbtSizeTracker.ofUnlimitedBytes();
                var wrapped = NbtIo.readCompressed(new ByteArrayInputStream(compressed), tracker);
                validateWrapper(wrapped);
                var data = wrapped.getCompound("data");
                if (!data.contains("SchemaVersion", 3) || data.getInt("SchemaVersion") != schema)
                    throw new IOException("journal probe 與正式版本不符");
                if (schema == 5 && new RawWalker(compressed, true).scan() != schema)
                    throw new IOException("journal raw 版本不符");
                return wrapped;
            } catch (RuntimeException error) { throw new IOException("journal NBT 無法解析", error); }
        }
    }

    private static void validateWrapper(NbtCompound wrapped) throws IOException {
        if (!wrapped.contains("data", 10) || !wrapped.contains("DataVersion", 3))
            throw new IOException("journal 缺少 data 或 DataVersion，或型別不正確");
    }

    private enum Scope { ROOT, DATA, RECORDS, RECORD, LEDGER, ENTRY, CANDIDATE, CONTEXT, SOURCE, INTENT, SNAPSHOT, PARAMETERS, OTHER }
    private static final class Row { boolean m6; boolean duplicate; }

    /** 第一趟只探版本；第二趟在有界物化成功後才累積新形狀的 raw 檢重。 */
    private static final class RawWalker {
        private static final Set<String> INTENT_MARKERS = Set.of("PolicyVersion", "ProfileId", "SourceRootWorldSeed",
                "SourceSettingsSnapshot", "TargetSettingsSnapshot", "FamilyCapabilityVersion");
        private final byte[] compressed;
        private final boolean inspectRows;
        private final byte[] skipBuffer = new byte[8192];
        private DataInputStream input;
        private int dataCount;
        private int versionCount;
        private int version;
        RawWalker(byte[] compressed, boolean inspectRows) { this.compressed = compressed; this.inspectRows = inspectRows; }

        int scan() throws IOException {
            try (var stream = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(compressed)))) {
                input = stream;
                if (input.readUnsignedByte() != 10) throw new IOException("journal root 必須 compound");
                input.readUTF();
                compound(Scope.ROOT, null, 0);
                // 必須讀到底，連 gzip trailer 的截斷也不能以 root END 掩蓋。
                if (input.read() != -1) throw new IOException("journal root END 後有多餘資料");
                if (dataCount != 1 || versionCount != 1 || version < 1 || version > 5)
                    throw new IOException("journal 版本缺失或未知");
                return version;
            }
        }

        private void compound(Scope scope, Row row, int depth) throws IOException {
            int nested = enter(depth);
            Set<String> names = inspectRows && (scope == Scope.DATA || tracked(scope)) ? new HashSet<>() : null;
            while (true) {
                int type = input.readUnsignedByte();
                if (type == 0) return;
                String key = input.readUTF();
                if (names != null && !names.add(key)) {
                    if (scope == Scope.DATA) throw new IOException("schema5 data 直接鍵重複：" + key);
                    if (row != null) row.duplicate = true;
                }
                if (scope == Scope.ROOT && key.equals("data")) {
                    if (++dataCount != 1 || type != 10) throw new IOException("journal data 重複或型別錯誤");
                }
                if (scope == Scope.DATA && key.equals("SchemaVersion")) {
                    if (++versionCount != 1 || type != 3) throw new IOException("journal SchemaVersion 重複或型別錯誤");
                    version = input.readInt(); continue;
                }
                if (inspectRows && row != null) {
                    if (scope == Scope.INTENT && INTENT_MARKERS.contains(key)) row.m6 = true;
                    if (scope == Scope.CONTEXT) {
                        if (key.equals("FamilyCapabilityVersion")) row.m6 = true;
                        if (key.equals("PolicyId") && type == 8) {
                            if (input.readUTF().equals("quantumchamber:m6_profiles_v1")) row.m6 = true;
                            continue;
                        }
                        if (key.equals("CandidateSchemaVersion") && type == 3) {
                            if (input.readInt() == 2) row.m6 = true;
                            continue;
                        }
                    }
                }
                value(type, child(scope, key), row, nested);
            }
        }

        private void value(int type, Scope scope, Row row, int depth) throws IOException {
            switch (type) {
                case 1 -> skipExactly(1);
                case 2 -> skipExactly(2);
                case 3, 5 -> skipExactly(4);
                case 4, 6 -> skipExactly(8);
                case 7 -> array(1);
                case 8 -> input.readUTF();
                case 9 -> list(scope, row, depth);
                case 10 -> compound(scope, row, depth);
                case 11 -> array(4);
                case 12 -> array(8);
                default -> throw new IOException("未知 NBT tag：" + type);
            }
        }

        private void list(Scope scope, Row row, int depth) throws IOException {
            int nested = enter(depth);
            int held = input.readUnsignedByte(); int count = input.readInt();
            if (count < 0 || held > 12 || held == 0 && count != 0) throw new IOException("NBT list 型別或長度非法");
            for (int i = 0; i < count; i++) {
                if (inspectRows && scope == Scope.RECORDS) {
                    var current = new Row(); value(held, Scope.RECORD, current, nested);
                    if (current.m6 && current.duplicate) throw new IOException("M6 row 新形狀或祖先鍵重複");
                } else value(held, scope == Scope.LEDGER ? Scope.ENTRY : scope, row, nested);
            }
        }

        private static int enter(int depth) throws IOException {
            if (depth >= 512) throw new IOException("NBT 容器深度超過 512");
            return depth + 1;
        }
        private void array(int width) throws IOException {
            int count = input.readInt();
            if (count < 0) throw new IOException("NBT array 負長度");
            skipExactly(Math.multiplyExact((long) count, width));
        }
        private void skipExactly(long count) throws IOException {
            while (count > 0) {
                int size = (int) Math.min(count, skipBuffer.length);
                input.readFully(skipBuffer, 0, size); count -= size;
            }
        }
        private Scope child(Scope parent, String key) {
            if (parent == Scope.ROOT && key.equals("data")) return Scope.DATA;
            if (!inspectRows) return Scope.OTHER;
            return switch (parent) {
                case DATA -> key.equals("Records") ? Scope.RECORDS : Scope.OTHER;
                case RECORD -> switch (key) {
                    case "CandidateContext" -> Scope.CONTEXT;
                    case "CandidateLedger" -> Scope.LEDGER;
                    default -> Scope.OTHER;
                };
                case ENTRY, LEDGER -> key.equals("Candidate") ? Scope.CANDIDATE : Scope.OTHER;
                case CANDIDATE -> key.equals("Intent") ? Scope.INTENT : Scope.OTHER;
                case CONTEXT -> key.equals("SourceFamilyRef") ? Scope.SOURCE : Scope.OTHER;
                case INTENT -> key.equals("SourceSettingsSnapshot") || key.equals("TargetSettingsSnapshot") ? Scope.SNAPSHOT : Scope.OTHER;
                case SNAPSHOT -> key.equals("Parameters") ? Scope.PARAMETERS : Scope.OTHER;
                default -> Scope.OTHER;
            };
        }
        private static boolean tracked(Scope scope) {
            return switch (scope) {
                case RECORD, ENTRY, CANDIDATE, CONTEXT, SOURCE, INTENT, SNAPSHOT, PARAMETERS -> true;
                default -> false;
            };
        }
    }
}
