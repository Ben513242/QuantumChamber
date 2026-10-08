package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.persistence.SessionJournalStore;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;

/**
 * 僅 testmod（M5 F6-1）：{@code M5CollapseRecoveryProbe} 讀磁碟上的 M5 權威（{@code world/data} 的正式檔），不經 production 的記憶體狀態。只讀，不寫任何檔。
 * allocation 與 projection ledger 直接讀 NBT（production 的 strict decode 是 package-private），catalog 經 {@link UniverseRegistryState#fromNbt}，journal 經
 * {@link SessionJournalStore#read}＋{@link SessionRecoveryState#fromNbt}，discovery 經 {@link UniverseDiscoveryState#load}。檔案不存在時回空的結果。
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

    static List<Allocation> allocations(Path data) throws IOException {
        var result = new ArrayList<Allocation>();
        var file = data.resolve(UniverseAllocationLedger.FILE_NAME);
        if (!Files.exists(file)) return result;
        var entries = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getList("Entries", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < entries.size(); i++) {
            var row = entries.getCompound(i);
            result.add(new Allocation(HexFormat.of().formatHex(row.getByteArray("AllocationToken")), row.getUuid("UniverseId")));
        }
        return result;
    }

    static List<Projection> projections(Path data) throws IOException {
        var result = new ArrayList<Projection>();
        var file = data.resolve(ProjectionLedger.FILE_NAME);
        if (!Files.exists(file)) return result;
        var entries = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getList("Entries", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < entries.size(); i++) {
            var row = entries.getCompound(i);
            result.add(new Projection(row.getUuid("ChamberUuid"), row.getUuid("UniverseId"), row.getString("Status")));
        }
        return result;
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

    static Optional<SessionRecoveryRecord> journalRecord(Path data, UUID session) throws IOException {
        var file = data.resolve(JOURNAL);
        if (!Files.exists(file)) return Optional.empty();
        var decoded = SessionRecoveryState.fromNbt(SessionJournalStore.read(file).getCompound("data"));
        decoded.requireHealthy();
        return Optional.ofNullable(decoded.flushedRecords().get(session));
    }

    static int journalSchema(Path data) throws IOException {
        var file = data.resolve(JOURNAL);
        return Files.exists(file) ? SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion") : -1;
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
