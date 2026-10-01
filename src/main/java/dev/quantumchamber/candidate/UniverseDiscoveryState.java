package dev.quantumchamber.candidate;

import dev.quantumchamber.collapse.LedgerNbt;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.universe.UniverseId;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

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
            // 暫存檔先以正式讀取規則驗證，避免超預算 payload 取代上一份有效權威（CheckedNbtFile）。
            CheckedNbtFile.write(target(saveRoot), toNbt(), CheckedNbtFile::readFile, UniverseDiscoveryState::fromNbt, "Discovery");
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
        LedgerNbt.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        if (nbt.getInt("SchemaVersion") != 1) throw new IllegalArgumentException("不支援的 discovery schema");
        var entries = LedgerNbt.compounds(nbt, "Records");
        var decoded = new ArrayList<UniverseDiscoveryRecord>(entries.size());
        long previousOrdinal = -1;
        for (int index = 0; index < entries.size(); index++) {
            var entry = entries.getCompound(index);
            if (!entry.getKeys().containsAll(REQUIRED_RECORD_FIELDS) || !RECORD_FIELDS.containsAll(entry.getKeys())) {
                throw new IllegalArgumentException("Discovery record 欄位不正確");
            }
            UniverseId id = UniverseId.of(LedgerNbt.uuid(entry, "UniverseId"));
            LedgerNbt.requireType(entry, "DiscoveryOrdinal", NbtElement.LONG_TYPE);
            LedgerNbt.requireType(entry, "DiscoveredAtGameTime", NbtElement.LONG_TYPE);
            LedgerNbt.requireType(entry, "GameplayEligible", NbtElement.BYTE_TYPE);
            byte flag = entry.getByte("GameplayEligible");
            if (flag != 0 && flag != 1) throw new IllegalArgumentException("GameplayEligible 必須是 0 或 1");
            long ordinal = entry.getLong("DiscoveryOrdinal");
            if (ordinal < previousOrdinal) throw new IllegalArgumentException("Discovery ordinal 不可倒退");
            previousOrdinal = ordinal;
            Optional<UUID> observer = entry.contains("FirstObserver")
                    ? Optional.of(LedgerNbt.uuid(entry, "FirstObserver")) : Optional.empty();
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

    // 讀檔限制（必須是一般檔案、檔案與解壓後 NBT 的預算）防止損壞或惡意 NBT 要求無界限記憶體；不是 session candidate entry cap。
    private static UniverseDiscoveryState read(Path target) throws IOException {
        return fromNbt(CheckedNbtFile.read(target, CheckedNbtFile::readFile));
    }

    private static Path target(Path saveRoot) {
        return Objects.requireNonNull(saveRoot, "saveRoot").toAbsolutePath().normalize()
                .resolve("data/quantumchamber_universe_discovery.dat");
    }

    public static final class UnhealthyDiscoveryException extends IOException {
        private UnhealthyDiscoveryException(String message) { super(message); }
    }
}
