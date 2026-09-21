package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.UniverseId;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;

/** 獨立的 checked discovery 儲存；M4 不提供正式 gameplay mutation。 */
public final class UniverseDiscoveryState {
    private static final Set<String> ROOT_FIELDS = Set.of("SchemaVersion", "Records");
    private static final Set<String> REQUIRED_RECORD_FIELDS = Set.of(
            "UniverseId", "DiscoveryOrdinal", "DiscoveredAtGameTime", "GameplayEligible");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "UniverseId", "DiscoveryOrdinal", "DiscoveredAtGameTime", "GameplayEligible", "FirstObserver");
    private final List<UniverseDiscoveryRecord> records;

    private UniverseDiscoveryState(List<UniverseDiscoveryRecord> records) {
        this.records = canonicalRecords(records);
    }

    // 僅供同 package fixture 建立初始資料；正式 discovery 寫入流程留給 M5。
    static UniverseDiscoveryState fromRecords(List<UniverseDiscoveryRecord> records) {
        return new UniverseDiscoveryState(records);
    }

    public static synchronized UniverseDiscoveryState loadOrCreate(Path saveRoot) throws UnhealthyDiscoveryException {
        Path target = target(saveRoot);
        try {
            try {
                return read(target);
            } catch (NoSuchFileException absent) {
                var empty = new UniverseDiscoveryState(List.of());
                empty.save(saveRoot);
                return read(target);
            }
        } catch (IOException | RuntimeException exception) {
            throw new UnhealthyDiscoveryException("Discovery 儲存不健康，拒絕載入或建立");
        }
    }

    public static UniverseDiscoveryState load(Path saveRoot) throws UnhealthyDiscoveryException {
        try {
            return read(target(saveRoot));
        } catch (IOException | RuntimeException exception) {
            throw new UnhealthyDiscoveryException("Discovery 儲存不健康，拒絕載入");
        }
    }

    public void save(Path saveRoot) throws UnhealthyDiscoveryException {
        try {
            Path target = target(saveRoot);
            var expected = toNbt();
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            try {
                NbtIo.writeCompressed(expected, temporary);
                try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
                // 先用正式讀取規則驗暫存檔，避免超預算 payload 取代上一份有效權威。
                verifyReadback(temporary, expected);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                verifyReadback(target, expected);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException exception) {
            throw new UnhealthyDiscoveryException("Discovery checked 寫入失敗");
        }
    }

    /** 是否已有發現資料應看 records 是否非空，不以空檔案的存在作為證據。 */
    public List<UniverseDiscoveryRecord> records() { return records; }

    public long watermark() { return records.isEmpty() ? -1 : records.getLast().discoveryOrdinal(); }

    public List<UniverseDiscoveryRecord> eligibleSnapshot(long watermark, SourceFamilyRef source) {
        Objects.requireNonNull(source, "source");
        if (watermark < -1) throw new IllegalArgumentException("Discovery watermark 必須至少為 -1");
        return records.stream().filter(record -> record.gameplayEligible() && record.discoveryOrdinal() <= watermark
                && !(source instanceof SourceFamilyRef.Catalog catalog && catalog.universeId().equals(record.universeId())))
                .toList();
    }

    public NbtCompound toNbt() {
        var nbt = new NbtCompound();
        nbt.putInt("SchemaVersion", 1);
        var entries = new NbtList();
        for (var record : records) {
            var entry = new NbtCompound();
            entry.putUuid("UniverseId", record.universeId().value());
            entry.putLong("DiscoveryOrdinal", record.discoveryOrdinal());
            entry.putLong("DiscoveredAtGameTime", record.discoveredAtGameTime());
            record.firstObserver().ifPresent(observer -> entry.putUuid("FirstObserver", observer));
            entry.putBoolean("GameplayEligible", record.gameplayEligible());
            entries.add(entry);
        }
        nbt.put("Records", entries);
        return nbt;
    }

    public static UniverseDiscoveryState fromNbt(NbtCompound nbt) {
        Objects.requireNonNull(nbt, "nbt");
        if (!nbt.getKeys().equals(ROOT_FIELDS)) throw new IllegalArgumentException("Discovery root 欄位不正確");
        requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        if (nbt.getInt("SchemaVersion") != 1) throw new IllegalArgumentException("不支援的 discovery schema");
        requireType(nbt, "Records", NbtElement.LIST_TYPE);
        var entries = (NbtList) nbt.get("Records");
        int heldType = entries.isEmpty() ? NbtElement.END_TYPE : NbtElement.COMPOUND_TYPE;
        if (entries.getHeldType() != heldType) throw new IllegalArgumentException("Discovery Records list 型別不正確");
        var decoded = new ArrayList<UniverseDiscoveryRecord>(entries.size());
        long previousOrdinal = -1;
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.getCompound(index);
            if (!entry.getKeys().containsAll(REQUIRED_RECORD_FIELDS) || !RECORD_FIELDS.containsAll(entry.getKeys())) {
                throw new IllegalArgumentException("Discovery record 欄位不正確");
            }
            UniverseId id = UniverseId.of(readUuid(entry, "UniverseId"));
            requireType(entry, "DiscoveryOrdinal", NbtElement.LONG_TYPE);
            requireType(entry, "DiscoveredAtGameTime", NbtElement.LONG_TYPE);
            requireType(entry, "GameplayEligible", NbtElement.BYTE_TYPE);
            byte flag = entry.getByte("GameplayEligible");
            if (flag != 0 && flag != 1) throw new IllegalArgumentException("GameplayEligible 必須是 0 或 1");
            long ordinal = entry.getLong("DiscoveryOrdinal");
            if (ordinal < previousOrdinal) throw new IllegalArgumentException("Discovery ordinal 不可倒退");
            previousOrdinal = ordinal;
            Optional<UUID> observer = entry.contains("FirstObserver")
                    ? Optional.of(readUuid(entry, "FirstObserver")) : Optional.empty();
            decoded.add(new UniverseDiscoveryRecord(id, ordinal, entry.getLong("DiscoveredAtGameTime"), observer, flag == 1));
        }
        return new UniverseDiscoveryState(decoded);
    }

    static List<UniverseDiscoveryRecord> canonicalRecords(List<UniverseDiscoveryRecord> input) {
        var copy = new ArrayList<>(Objects.requireNonNull(input, "records"));
        var ids = new HashSet<UniverseId>();
        for (var record : copy) {
            Objects.requireNonNull(record, "record");
            if (!ids.add(record.universeId())) throw new IllegalArgumentException("重複的 discovery UniverseId");
        }
        copy.sort(UniverseDiscoveryRecord.CANONICAL_ORDER);
        return List.copyOf(copy);
    }

    private static UniverseDiscoveryState read(Path target) throws IOException { return fromNbt(readNbt(target)); }

    private static void verifyReadback(Path target, NbtCompound expected) throws IOException {
        var actual = readNbt(target);
        fromNbt(actual);
        if (!expected.equals(actual)) throw new IOException("Discovery exact readback 不符");
    }

    private static NbtCompound readNbt(Path target) throws IOException {
        var attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IOException("Discovery 必須是一般檔案");
        // 防止損壞或惡意 NBT 要求無界限記憶體；不是 session candidate entry cap。
        return NbtIo.readCompressed(target, NbtSizeTracker.of(16L * 1024 * 1024));
    }

    private static UUID readUuid(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.INT_ARRAY_TYPE);
        if (nbt.getIntArray(key).length != 4) throw new IllegalArgumentException("Discovery UUID 必須是四個 int");
        return nbt.getUuid(key);
    }

    private static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) throw new IllegalArgumentException("Discovery 欄位缺失或型別不正確");
    }

    private static Path target(Path saveRoot) {
        return Objects.requireNonNull(saveRoot, "saveRoot").toAbsolutePath().normalize()
                .resolve("data/quantumchamber_universe_discovery.dat");
    }

    public static final class UnhealthyDiscoveryException extends IOException {
        private UnhealthyDiscoveryException(String message) { super(message); }
    }
}
