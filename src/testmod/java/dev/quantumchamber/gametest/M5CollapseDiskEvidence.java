package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseLedgerTestAccess;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.persistence.SessionJournalStore;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM 的塌縮 probe 讀磁碟上的 M5 權威（{@code world/data} 的正式檔），不經 production 的記憶體狀態。只讀，不寫任何檔。
 * 每一份都以 production 的嚴格 decode 讀：allocation 與 projection ledger 經 {@link CollapseLedgerTestAccess}（F6-2a：package-private 的 {@code fromNbt}，exact keys、
 * {@code SchemaVersion}、筆數上限；格式不符時丟出，不會讀成空的 ledger），catalog 經 {@link UniverseRegistryState#fromNbt}，journal 經
 * {@link SessionJournalStore#read}＋{@link SessionRecoveryState#fromNbt}，discovery 經 {@link UniverseDiscoveryState#load}。檔案不存在時回空的結果（還沒有寫過）。
 * 投影的方塊以 raw MCA 讀（{@link #block}）。F6-2e-2 起另有 journal 的 envelope 的寫出（{@link #writeJournal}：fixture 與 evidence 的 NBT）與 journal 的讀回斷言
 * （{@link #expectJournalOnDisk}，F6-2e-3 起不限 schema 3）；其餘只讀。
 */
final class M5CollapseDiskEvidence {
    static final String JOURNAL = SessionRecoveryState.STATE_ID + ".dat";
    static final String CATALOG = UniverseRegistryState.STATE_ID + ".dat";
    /** halt receipt 摘要的五個權威檔（依這個順序）。 */
    static final List<String> FILES = List.of(JOURNAL, UniverseAllocationLedger.FILE_NAME, CATALOG, UniverseDiscoveryState.FILE_NAME, ProjectionLedger.FILE_NAME);

    private M5CollapseDiskEvidence() {}

    /** allocation ledger 的一筆（AllocationToken 以十六進位表示）。 */
    record Allocation(String token, UUID universeId) {}

    /** projection ledger 的一筆。 */
    record Projection(UUID chamber, UUID universeId, String status) {}

    /** 磁碟上的 allocation ledger（嚴格 decode；檔案不存在時是空的）。 */
    static List<Allocation> allocations(Path data) throws IOException {
        var file = data.resolve(UniverseAllocationLedger.FILE_NAME);
        if (!Files.exists(file)) return List.of();
        return CollapseLedgerTestAccess.allocations(NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes())).stream()
                .map(entry -> new Allocation(HexFormat.of().formatHex(entry.allocationToken().bytes()), entry.universeId().value())).toList();
    }

    /** 磁碟上的 projection ledger（嚴格 decode；檔案不存在時是空的）。 */
    static List<Projection> projections(Path data) throws IOException {
        var file = data.resolve(ProjectionLedger.FILE_NAME);
        if (!Files.exists(file)) return List.of();
        return CollapseLedgerTestAccess.projections(NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes())).stream()
                .map(entry -> new Projection(entry.chamberUuid(), entry.universeId().value(), entry.status().name())).toList();
    }

    static Map<UniverseId, UniverseRecord> catalog(Path data) throws IOException {
        var file = data.resolve(CATALOG);
        if (!Files.exists(file)) return Map.of();
        var state = UniverseRegistryState.fromNbt(NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getCompound("data"));
        state.requireHealthy();
        return state.records();
    }

    static List<UniverseDiscoveryRecord> discovery(Path saveRoot) throws IOException {
        if (!Files.exists(saveRoot.resolve("data").resolve(UniverseDiscoveryState.FILE_NAME))) return List.of();
        return UniverseDiscoveryState.load(saveRoot).records();
    }

    /**
     * 磁碟上的 journal（{@link #journal}）：envelope 的 {@code SchemaVersion}、檔案的 {@code DataVersion}（沒有時是 -1；F6-2e-3，F6-2e-2 quality Minor 6：原本呼叫端要再讀一次檔）與
     * flushed 的 record（以 session 為鍵）。
     */
    record JournalOnDisk(int schema, int dataVersion, Map<UUID, SessionRecoveryRecord> records) {}

    /**
     * F6-2e-2（F6-2e-1 quality Minor 2）：磁碟上的 journal（{@code data} 的 {@link #JOURNAL}），production 的 {@link SessionJournalStore#read} 讀回、{@link SessionRecoveryState#fromNbt}
     * strict 解碼、{@code requireHealthy}；檔案不存在時是空的。原本 {@link #journalRecord} 與 {@code M5CollapseUnsupportedChain} 各自推導路徑、各寫一份 read＋decode。
     */
    static Optional<JournalOnDisk> journal(Path data) throws IOException {
        var file = data.resolve(JOURNAL);
        if (!Files.exists(file)) return Optional.empty();
        var root = SessionJournalStore.read(file);
        var envelope = root.getCompound("data");
        var decoded = SessionRecoveryState.fromNbt(envelope);
        decoded.requireHealthy();
        return Optional.of(new JournalOnDisk(envelope.getInt("SchemaVersion"), NbtHelper.getDataVersion(root, -1), decoded.flushedRecords()));
    }

    static Optional<SessionRecoveryRecord> journalRecord(Path data, UUID session) throws IOException {
        return journal(data).map(journal -> journal.records().get(session));
    }

    /**
     * F6-2e-2（F6-2e-1 quality Minor 2）：{@code name}：磁碟上的 journal（{@link #journal}）的 {@code SchemaVersion} 是 {@code schema}，flushed 的 record 恰為 {@code expected}；回傳讀到的
     * journal（呼叫端要 DataVersion 時不必再讀一次檔）。proof 記 {@code <name>Schema}。由 {@code M5CollapseUnsupportedChain} 搬來，{@code data} 改由呼叫端給（開服之前讀
     * {@link M5CollapseRun#worldData}）。F6-2e-1 的版本另要求 {@code !m5Evidence}，但 production 的 {@code fromNbt} 只在讀到 schema 4 時設它（spec §5），{@code SchemaVersion}＝3 時恆為
     * false，沒有證明力，拿掉（F6-2e-1 spec review 的 nit）。F6-2e-3（F6-2e-2 quality Minor 6）：由 {@code expectSchemaThreeOnDisk} 一般化，schema 由呼叫端給（M4 時期的 envelope 是 3；
     * {@code M5CollapseInitChain} 原本手寫 schema 4 的版本）。
     */
    static Optional<JournalOnDisk> expectJournalOnDisk(M5CollapseRun run, Path data, String name, int schema, Map<UUID, SessionRecoveryRecord> expected) throws Exception {
        var journal = journal(data);
        int actual = journal.map(JournalOnDisk::schema).orElse(-1);
        var records = journal.map(JournalOnDisk::records).orElse(Map.of());
        run.expect(name, actual == schema && records.equals(expected),
                () -> "磁碟上的 journal：SchemaVersion=" + actual + " " + states(records) + "（預期 SchemaVersion=" + schema + " " + states(expected) + "）");
        run.proof.put(name + "Schema", actual);
        return journal;
    }

    /** 失敗訊息用：每個 session 的狀態。 */
    private static Map<UUID, String> states(Map<UUID, SessionRecoveryRecord> records) {
        var result = new LinkedHashMap<UUID, String>();
        records.forEach((session, record) -> result.put(session, record.state().name()));
        return result;
    }

    /**
     * F6-2e-2（F6-2e-1 quality Minor 2）：以正式的寫出把 {@code records} 寫成一份 journal 的 envelope（全新的 {@link SessionRecoveryState}，依序 put；{@code writtenSchema} 由 production
     * 決定：沒有 M5 證據、有 candidate-aware 的 record 時是 3）到 {@code file}，回傳寫出的 NBT（{@code data} 之下是 {@code writeNbt} 的結果）。{@code records} 可以是空的（那時 production
     * 寫 2，spec §5）。不加 DataVersion：evidence 的 receipt／batch NBT（原本 {@code M5CollapseFixture.recordReceipt}、{@code M5CollapseUnsupportedChain.writeBatch} 各寫一份）。
     */
    static NbtCompound writeJournal(Path file, Collection<SessionRecoveryRecord> records) throws IOException {
        return write(file, records, OptionalInt.empty());
    }

    /** 同 {@link #writeJournal(Path, Collection)}，另加 {@code DataVersion}＝{@code dataVersion}（正式檔 {@code data/}{@link #JOURNAL}，比照 legacy runner 的 {@code reloadEmptySchemaThree}）。 */
    static NbtCompound writeJournal(Path file, Collection<SessionRecoveryRecord> records, int dataVersion) throws IOException {
        return write(file, records, OptionalInt.of(dataVersion));
    }

    /** 兩個 {@code writeJournal} 共用的寫出（F6-2e-3，F6-2e-2 quality Minor 6：原本兩個多載各寫一份）：envelope、有 {@code dataVersion} 時加上它，寫到 {@code file}。 */
    private static NbtCompound write(Path file, Collection<SessionRecoveryRecord> records, OptionalInt dataVersion) throws IOException {
        var state = new SessionRecoveryState();
        records.forEach(state::put);
        var wrapped = new NbtCompound();
        wrapped.put("data", state.writeNbt(new NbtCompound()));
        if (dataVersion.isPresent()) NbtHelper.putDataVersion(wrapped, dataVersion.getAsInt());
        NbtIo.writeCompressed(wrapped, file);
        return wrapped;
    }

    static int journalSchema(Path data) throws IOException {
        var file = data.resolve(JOURNAL);
        return Files.exists(file) ? SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion") : -1;
    }

    /** {@link #block} 的結果在磁碟上沒有這一格（region 檔、chunk 或 section 不存在）時的值。 */
    static final String ABSENT = "ABSENT";

    /** raw MCA 讀到的一格：palette 的 {@code Name} 與 {@code Properties}（SNBT），以及那一格的 block entity id；沒有 block entity 時為 {@link #ABSENT}。 */
    record DiskBlock(String name, String properties, String blockEntity) {
        static final DiskBlock MISSING = new DiskBlock(ABSENT, ABSENT, ABSENT);
    }

    /**
     * 以 raw MCA 讀 {@code dimension}（世界的存檔目錄，例如 {@code DimensionType.getSaveDirectory} 的結果）裡 {@code pos} 這一格，不經 world、chunk 或 IO worker 的
     * 記憶體快取（比照 {@code M4RecoveryDiskEvidence}）。region 檔、chunk 或 section 不存在時回 {@link DiskBlock#MISSING}；檔案損壞或格式不支援時丟出。
     */
    static DiskBlock block(Path dimension, BlockPos pos) throws IOException {
        var chunk = chunk(dimension.resolve("region"), new ChunkPos(pos));
        if (chunk.isEmpty()) return DiskBlock.MISSING;
        var nbt = chunk.get();
        String blockEntity = ABSENT;
        for (var raw : nbt.getList("block_entities", NbtElement.COMPOUND_TYPE)) {
            var entity = (NbtCompound) raw;
            if (entity.getInt("x") == pos.getX() && entity.getInt("y") == pos.getY() && entity.getInt("z") == pos.getZ()) blockEntity = entity.getString("id");
        }
        for (var raw : nbt.getList("sections", NbtElement.COMPOUND_TYPE)) {
            var section = (NbtCompound) raw;
            if (section.getByte("Y") != (pos.getY() >> 4)) continue;
            if (!section.contains("block_states", NbtElement.COMPOUND_TYPE)) return new DiskBlock(ABSENT, ABSENT, blockEntity);
            var states = section.getCompound("block_states");
            var palette = states.getList("palette", NbtElement.COMPOUND_TYPE);
            if (palette.isEmpty()) throw new IOException("磁碟 section 沒有 block palette：" + pos.toShortString());
            int index = 0;
            if (palette.size() > 1) {
                int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1)), perLong = 64 / bits;
                int cell = ((pos.getY() & 15) << 8) | ((pos.getZ() & 15) << 4) | (pos.getX() & 15);
                var packed = states.getLongArray("data");
                if (cell / perLong >= packed.length) throw new IOException("磁碟 block data 長度不足：" + pos.toShortString());
                index = (int) ((packed[cell / perLong] >>> ((cell % perLong) * bits)) & ((1L << bits) - 1));
            }
            if (index >= palette.size()) throw new IOException("磁碟 palette index 超出範圍：" + pos.toShortString());
            var state = palette.getCompound(index);
            return new DiskBlock(state.getString("Name"), state.contains("Properties") ? state.getCompound("Properties").toString() : "{}", blockEntity);
        }
        return new DiskBlock(ABSENT, ABSENT, blockEntity);
    }

    /**
     * 以 raw MCA 讀 {@code dimension} 裡 {@code pos} 這一格的 block entity 的字串欄位 {@code key}（例如 Controller 的 {@code InstanceKind}）。region 檔、chunk 或
     * block entity 不存在時回 {@link #ABSENT}。
     */
    static String blockEntityString(Path dimension, BlockPos pos, String key) throws IOException {
        var chunk = chunk(dimension.resolve("region"), new ChunkPos(pos));
        if (chunk.isEmpty()) return ABSENT;
        for (var raw : chunk.get().getList("block_entities", NbtElement.COMPOUND_TYPE)) {
            var entity = (NbtCompound) raw;
            if (entity.getInt("x") == pos.getX() && entity.getInt("y") == pos.getY() && entity.getInt("z") == pos.getZ()) {
                return entity.contains(key, NbtElement.STRING_TYPE) ? entity.getString(key) : ABSENT;
            }
        }
        return ABSENT;
    }

    /** 磁碟上這位玩家的 playerdata（{@code playerdata/<uuid>.dat}）記的世界（{@code Dimension}）；檔案不存在時回 {@link #ABSENT}。 */
    static String playerDimension(Path saveRoot, UUID player) throws IOException {
        var file = saveRoot.resolve("playerdata").resolve(player + ".dat");
        if (!Files.exists(file)) return ABSENT;
        return NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getString("Dimension");
    }

    private static Optional<NbtCompound> chunk(Path region, ChunkPos pos) throws IOException {
        var file = region.resolve("r." + (pos.x >> 5) + "." + (pos.z >> 5) + ".mca");
        if (!Files.exists(file)) return Optional.empty();
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            if (input.length() < 8192) return Optional.empty();
            input.seek(4L * ((pos.x & 31) + (pos.z & 31) * 32));
            int location = input.readInt();
            int sector = location >>> 8, count = location & 255;
            if (location == 0) return Optional.empty();
            if (sector < 2 || count == 0) throw new IOException("MCA 的 chunk 位置不合法：" + pos);
            input.seek(sector * 4096L);
            int size = input.readInt(), compression = input.readUnsignedByte();
            if (size <= 1 || size > count * 4096 - 4 || (compression & 128) != 0) throw new IOException("不支援或損壞的 MCA entry：" + pos);
            var payload = new byte[size - 1];
            input.readFully(payload);
            InputStream stream = new ByteArrayInputStream(payload);
            stream = switch (compression) {
                case 1 -> new GZIPInputStream(stream);
                case 2 -> new InflaterInputStream(stream);
                case 3 -> stream;
                default -> throw new IOException("不支援的 MCA compression " + compression + "：" + pos);
            };
            try (var data = new DataInputStream(stream)) {
                var nbt = NbtIo.readCompound(data, NbtSizeTracker.of(16L * 1024 * 1024));
                if (nbt.getInt("xPos") != pos.x || nbt.getInt("zPos") != pos.z) throw new IOException("MCA 的 chunk 座標不符：" + pos);
                return Optional.of(nbt);
            }
        }
    }

    static String sha256(Path file) throws IOException {
        if (!Files.exists(file)) return "ABSENT";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String sha256(NbtCompound nbt) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(nbt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * halt receipt 的磁碟權威摘要：五個檔各自的 SHA-256 與內容摘要（journal 的 schema 與這個 session 的狀態、allocation、catalog、discovery、projection 的每一筆）。
     * 讀取失敗的項目記成 {@code UNREADABLE: <原因>}，不丟出（halt 之前一定要寫得出 receipt）。
     */
    static Map<String, Object> summary(Path saveRoot, UUID session) {
        var data = saveRoot.resolve("data");
        var result = new LinkedHashMap<String, Object>();
        var hashes = new LinkedHashMap<String, String>();
        for (var name : FILES) hashes.put(name, attempt(() -> sha256(data.resolve(name))).toString());
        result.put("sha256", hashes);
        result.put("journalSchema", attempt(() -> journalSchema(data)));
        result.put("journalState", attempt(() -> session == null ? "NO_SESSION"
                : journalRecord(data, session).map(record -> record.state().name()).orElse("ABSENT")));
        result.put("allocations", attempt(() -> allocations(data).stream().map(Allocation::toString).toList()));
        result.put("catalog", attempt(() -> catalog(data).values().stream()
                .map(record -> record.definition().universeId().value() + " " + record.desiredAvailability()).toList()));
        result.put("discovery", attempt(() -> discovery(saveRoot).stream()
                .map(record -> record.universeId().value() + " ordinal=" + record.discoveryOrdinal() + " eligible=" + record.gameplayEligible()).toList()));
        result.put("projections", attempt(() -> projections(data).stream().map(Projection::toString).toList()));
        return result;
    }

    @FunctionalInterface private interface Read { Object get() throws Exception; }

    private static Object attempt(Read read) {
        try {
            return read.get();
        } catch (Exception | Error failure) {
            return "UNREADABLE: " + failure;
        }
    }
}
