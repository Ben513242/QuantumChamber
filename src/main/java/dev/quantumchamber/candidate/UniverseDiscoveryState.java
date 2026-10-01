package dev.quantumchamber.candidate;

import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.persistence.LedgerNbt;
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

/**
 * 獨立的 checked discovery 儲存（不是 PersistentState；M4 的讀取點每次都從磁碟讀）。M4 只讀；M5 以 {@link #append} 算出 spec §6.4 的正式追加，
 * checked 寫入（與 {@link #save} 相同的 {@link CheckedNbtFile} 寫法）、讀取失敗的分類與 roll-forward 收斂由 {@code CollapseAuthorities} 擁有。
 */
public final class UniverseDiscoveryState {
    public static final String FILE_NAME = "quantumchamber_universe_discovery.dat";
    private static final Set<String> ROOT_FIELDS = Set.of("SchemaVersion", "Records");
    private static final Set<String> REQUIRED_RECORD_FIELDS = Set.of(
            "UniverseId", "DiscoveryOrdinal", "DiscoveredAtGameTime", "GameplayEligible");
    private static final Set<String> RECORD_FIELDS = Set.of(
            "UniverseId", "DiscoveryOrdinal", "DiscoveredAtGameTime", "GameplayEligible", "FirstObserver");
    private final List<UniverseDiscoveryRecord> records;

    /**
     * spec §6.4 的追加結果（{@link #append}）。只算出內容，不寫檔。
     * @param record APPENDED 為新的 record；EXISTING、MISMATCH 為既有的 record
     * @param next APPENDED 為多了 record 的新 state；EXISTING、MISMATCH 為原本的 state（不改動）
     */
    public record Append(Outcome outcome, UniverseDiscoveryRecord record, UniverseDiscoveryState next) {
        public enum Outcome {
            /** 沒有這個 UniverseId：next 多了一筆 record，尚待 checked 寫入。 */
            APPENDED,
            /** 已有這個 UniverseId，而且正是本 session 會寫出的內容（gameplayEligible、firstObserver 等於 SelectedBy）：idempotent，不改動。 */
            EXISTING,
            /** 已有這個 UniverseId，但 gameplayEligible 為假或 firstObserver 不符：進 SEALED(AUTHORITY_CORRUPT)，不沿用、不改寫。 */
            MISMATCH
        }

        public Append {
            Objects.requireNonNull(outcome, "outcome"); Objects.requireNonNull(record, "record"); Objects.requireNonNull(next, "next");
        }
    }

    private UniverseDiscoveryState(List<UniverseDiscoveryRecord> records) {
        this.records = canonicalRecords(records);
    }

    // 僅供同 package fixture 建立初始資料；正式的追加是 append（spec §6.4）。
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

    public void save(Path saveRoot) throws UnhealthyDiscoveryException { save(saveRoot, CheckedNbtFile::readFile); }

    /**
     * 決策 9 的 package-private IO port：暫存檔驗證與 atomic move 之後的讀回都經過 {@code io}，pure JVM 測試以它注入
     * 「atomic move 完成、讀回失敗」。失敗時不還原正式檔（spec §6：已落地的 append 不移除）。
     */
    void save(Path saveRoot, CheckedNbtFile.ReadPort io) throws UnhealthyDiscoveryException {
        try {
            // 暫存檔先以正式讀取規則驗證，避免超預算 payload 取代上一份有效權威（CheckedNbtFile）。
            CheckedNbtFile.write(target(saveRoot), toNbt(), io, UniverseDiscoveryState::fromNbt, "Discovery");
        } catch (IOException | RuntimeException exception) {
            throw new UnhealthyDiscoveryException("Discovery checked 寫入失敗");
        }
    }

    /** 是否已有發現資料應看 records 是否非空，不以空檔案的存在作為證據。 */
    public List<UniverseDiscoveryRecord> records() { return records; }

    public long watermark() { return records.isEmpty() ? -1 : records.getLast().discoveryOrdinal(); }

    public Optional<UniverseDiscoveryRecord> find(UniverseId universeId) {
        Objects.requireNonNull(universeId, "universeId");
        return records.stream().filter(record -> record.universeId().equals(universeId)).findFirst();
    }

    /**
     * spec §6.4 的正式追加（只算出內容，不寫檔；只有 NEW 在 RESOLVE 物化成功之後呼叫）。以 UniverseId 判斷 idempotency：
     * <ul>
     * <li>沒有這個 UniverseId：新的 record 為 {@code discoveryOrdinal}＝{@link #watermark()}＋1、{@code discoveredAtGameTime}＝當下的 game time、
     *     {@code firstObserver}＝SelectedBy、{@code gameplayEligible}＝true（{@link Append.Outcome#APPENDED}）。</li>
     * <li>已有：必須 {@code gameplayEligible} 為真，而且 {@code firstObserver} 等於本 session 的 SelectedBy（這個 UniverseId 由本 session 的
     *     token 推導而來，只有本 session 會追加它）。符合為 {@link Append.Outcome#EXISTING}，不改動；{@code discoveryOrdinal} 與
     *     {@code discoveredAtGameTime} 可以不同（例如上一次寫入已落地、讀回才失敗）。不符為 {@link Append.Outcome#MISMATCH}，不改寫。</li>
     * </ul>
     * game time 為負時丟 {@link IllegalArgumentException}；ordinal 溢位時丟 {@link ArithmeticException}。
     */
    public Append append(UniverseId universeId, long discoveredAtGameTime, UUID selectedBy) {
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(selectedBy, "selectedBy");
        var existing = find(universeId);
        if (existing.isPresent()) {
            var record = existing.get();
            boolean own = record.gameplayEligible() && record.firstObserver().equals(Optional.of(selectedBy));
            return new Append(own ? Append.Outcome.EXISTING : Append.Outcome.MISMATCH, record, this);
        }
        var record = new UniverseDiscoveryRecord(universeId, Math.addExact(watermark(), 1), discoveredAtGameTime, Optional.of(selectedBy), true);
        var next = new ArrayList<>(records);
        next.add(record);
        return new Append(Append.Outcome.APPENDED, record, new UniverseDiscoveryState(next));
    }

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
        return Objects.requireNonNull(saveRoot, "saveRoot").toAbsolutePath().normalize().resolve("data").resolve(FILE_NAME);
    }

    public static final class UnhealthyDiscoveryException extends IOException {
        private UnhealthyDiscoveryException(String message) { super(message); }
    }
}
