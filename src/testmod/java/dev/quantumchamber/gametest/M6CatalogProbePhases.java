package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.World;
import org.slf4j.LoggerFactory;

/** 在正式啟服完成後、第一個 tick 前，以同存檔四次啟服定位 catalog 寫入失敗窗口。 */
public final class M6CatalogProbePhases {
    private static final UUID PLAYER = UUID.fromString("67000000-0000-4000-8000-000000000003");
    private static M6CatalogProbePhases armed;
    private final String phase;
    private final Path root;
    private final Path evidence;
    private final Map<String, Object> proof;
    private int starts;
    private int ticks;
    private int stops;
    private int faults;
    private int envelopeMoves;
    private int movesAfterFault;
    private UniverseId expectedId;

    M6CatalogProbePhases(String phase, Path root, Path evidence, Map<String, Object> proof) {
        this.phase = phase;
        this.root = root;
        this.evidence = evidence;
        this.proof = proof;
    }

    void endTick() { ticks++; }
    void stopping() { stops++; }

    public static void beforeCatalogReadback(Path target, NbtCompound wrapped) throws IOException {
        var probe = armed;
        if (probe == null) return;
        require(target.toAbsolutePath().normalize().equals(probe.data().resolve(UniverseRegistryState.STATE_ID + ".dat")), "錯誤 catalog 路徑");
        var records = wrapped.getCompound("data").getList("Records", NbtElement.COMPOUND_TYPE);
        if (probe.faults > 0) {
            probe.movesAfterFault++;
            throw new IOException("M6_CATALOG_SECOND_MOVE");
        }
        if (records.isEmpty()) {
            probe.envelopeMoves++;
            return;
        }
        require(records.size() == 1 && records.getCompound(0).getUuid("UniverseUuid").equals(probe.expectedId.value()), "故障目標 row 不符");
        require(wrapped.equals(UniverseCatalogStore.read(target)), "故障前正式磁碟 bytes 不符");
        probe.faults++;
        throw new IOException("M6_CATALOG_MOVED_READBACK_FAULT");
    }

    private Path data() { return root.resolve("world/data"); }

    void started(MinecraftServer server) {
        starts++;
        try {
            require(starts == 1 && ticks == 0 && stops == 0 && server.isOnThread() && UniverseLifecycleService.ready(server), "啟服同步切點不符");
            require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()), "存檔路徑不符");
            execute(server);
            require(starts == 1 && ticks == 0 && stops == 0 && movesAfterFault == 0, "離開同步切點");
            proof.put("startedCallbacks", starts);
            proof.put("endTicks", ticks);
            proof.put("stoppingEvents", stops);
            proof.put("faultCount", faults);
            proof.put("envelopeMoves", envelopeMoves);
            proof.put("movesAfterFault", movesAfterFault);
            proof.put("status", "PASS");
            durable("halt.json");
            LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_NATIVE_END chain=catalog-readback phase={} mode=FUNCTIONAL status=PASS ending=HALT", phase);
            Runtime.getRuntime().halt(77);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("quantumchamber-m6-native").error("M6_CATALOG_PHASE_FAILED", failure);
            proof.put("status", "FAIL");
            proof.put("failure", failure.toString());
            try { durable("failure.json"); } catch (IOException writeFailure) { failure.addSuppressed(writeFailure); }
            Runtime.getRuntime().halt(78);
        }
    }

    private void durable(String file) throws IOException {
        Files.createDirectories(evidence);
        try (var channel = FileChannel.open(evidence.resolve(file), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var bytes = ByteBuffer.wrap(new Gson().toJson(proof).getBytes(StandardCharsets.UTF_8));
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
    }

    private void execute(MinecraftServer server) throws Exception {
        var journal = SessionRecoveryState.get(server);
        var authorities = CollapseAuthorities.get(server);
        require(authorities.initialized(), "配置權威未就緒");
        if (phase.equals("intent")) commitIntent(server, journal);
        require(journal.flushedRecords().size() == 1, "journal 筆數不符");
        var record = journal.flushedRecords().values().iterator().next();
        require(record.state() == SessionState.COLLAPSING && record.candidateLedger().size() == 1, "未取得 checked COLLAPSING");
        var selected = (CandidateSelection.Selected) record.candidateSelection().orElseThrow();
        var entry = record.candidateLedger().getFirst();
        require(selected.doorKey().equals(entry.doorKey()) && selected.candidateId().equals(entry.candidate().candidateId()), "選門收據不符");
        var intent = ((QuantumCandidate.New) entry.candidate()).intent();
        expectedId = UniverseAllocationLedger.deriveUniverseId(intent.allocationToken());
        var catalog = UniverseRegistryState.get(server);
        Path rowFile = data().resolve(UniverseAllocationLedger.FILE_NAME);
        Path catalogFile = data().resolve(UniverseRegistryState.STATE_ID + ".dat");
        if (phase.equals("allocation")) {
            require(authorities.lookupOrAppendAllocation(intent).status() == CollapseAuthorities.Lookup.Status.APPENDED, "應首次 checked 配置");
        }
        var ledger = CheckedNbtFile.parse(CheckedNbtFile.readFile(rowFile));
        var rows = ledger.getList("Entries", NbtElement.COMPOUND_TYPE);
        if (phase.equals("intent")) {
            require(rows.isEmpty(), "intent 提早配置");
        } else {
            require(rows.size() == 1 && ledger.getInt("SchemaVersion") == 2, "allocation envelope 不符");
            require(authorities.lookupOrAppendAllocation(intent).status() == CollapseAuthorities.Lookup.Status.FOUND, "allocation 與 intent 不符");
            var row = rows.getCompound(0);
            require(row.getUuid("UniverseId").equals(expectedId.value()) && row.getLong("RootWorldSeed") == intent.rootWorldSeed()
                    && row.getString("ProfileId").equals(intent.profileId()) && Arrays.equals(row.getByteArray("TargetSettingsFingerprint"),
                    intent.targetSettingsSnapshot().outputFingerprint()), "allocation 磁碟內容不符");
        }
        if (phase.equals("atomic-move-readback")) {
            require(catalog.records().isEmpty(), "意外已有 catalog row");
            armed = this;
            require(authorities.allocateOrVerifyCatalog(intent, CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY).status()
                    == CollapseAuthorities.Lookup.Status.WRITE_FAILED, "應拒絕讀回");
            require(faults == 1 && envelopeMoves == 1 && catalog.isDirty() && catalog.flushedRecords().isEmpty()
                    && catalog.records().containsKey(expectedId) && authorities.unconfirmed(CollapseAuthorities.Authority.CATALOG), "故障後錯認 checked");
        } else if (phase.equals("restart")) {
            require(!catalog.isDirty() && catalog.records().equals(catalog.flushedRecords()), "重啟未自然讀回 catalog");
            require(authorities.allocateOrVerifyCatalog(intent, CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY).status()
                    == CollapseAuthorities.Lookup.Status.FOUND, "重啟未確認原 row");
        } else {
            require(!Files.exists(catalogFile) && catalog.records().isEmpty(), "提早寫入 catalog");
        }
        if (Files.exists(catalogFile)) {
            var disk = UniverseRegistryState.fromNbt(UniverseCatalogStore.read(catalogFile).getCompound("data"));
            var target = disk.flushedRecords().get(expectedId);
            require(disk.flushedRecords().size() == 1 && target.desiredAvailability() == DesiredAvailability.DISABLED
                    && CollapseAuthorities.catalogRecordMatches(intent, target, CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY), "磁碟 catalog 不符");
        }
        var keys = new ArrayList<String>();
        for (var role : DimensionRole.values()) {
            var key = UniverseKeys.world(expectedId, role);
            keys.add(key.getValue().toString());
            var path = root.resolve("world/dimensions").resolve(key.getValue().getNamespace()).resolve(key.getValue().getPath());
            require(server.getWorld(key) == null && Files.notExists(path, LinkOption.NOFOLLOW_LINKS), "提前物化 " + role);
        }
        proof.put("roleKeys", keys);
        proof.put("session", record.sessionUuid().toString());
        proof.put("uuid", expectedId.value().toString());
        proof.put("rootWorldSeed", Long.toString(intent.rootWorldSeed()));
        proof.put("allocationToken", HexFormat.of().formatHex(intent.allocationToken().bytes()));
        proof.put("targetFingerprint", HexFormat.of().formatHex(intent.targetSettingsSnapshot().outputFingerprint()));
        proof.put("profileId", intent.profileId());
        proof.put("profileVersion", intent.profileVersion());
        proof.put("allocationCount", rows.size());
        proof.put("catalogCurrentCount", catalog.records().size());
        proof.put("catalogFlushedCount", catalog.flushedRecords().size());
        proof.put("catalogDirty", catalog.isDirty());
        proof.put("worldAndStorageAbsent", true);
        proof.put("journalHash", digest(data().resolve(SessionRecoveryState.STATE_ID + ".dat")));
        proof.put("allocationHash", digest(rowFile));
        proof.put("catalogHash", Files.exists(catalogFile) ? digest(catalogFile) : "ABSENT");
    }

    private static String digest(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static void commitIntent(MinecraftServer server, SessionRecoveryState journal) throws Exception {
        require(journal.flushedRecords().isEmpty(), "intent 世界非全新");
        var base = M4CandidateTestAccess.dormantReceipt(server, PLAYER, 0);
        var entropy = CandidateEntropyState.loadOrCreate(server.getSavePath(WorldSavePath.ROOT), true);
        var policy = new CandidatePolicySnapshot(Identifier.of("quantumchamber:m6_profiles_v1"), 1, 2, 5, 20, 75, -1, 4096,
                entropy.entropyFingerprint(), new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD), 0);
        var registry = Minecraft121SettingsLifecycle.registry();
        var resolver = new CandidateResolver(entropy, registry, () -> Minecraft121SettingsLifecycle.acquire(server));
        var params = new NbtCompound();
        params.putString("OverworldPreset", "normal");
        params.putBoolean("GenerateStructures", server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures());
        var source = registry.createSnapshot("quantumchamber:vanilla_normal_v1", 1, params, Minecraft121SettingsLifecycle.acquire(server));
        for (int station = 1; station <= 128; station++) {
            var key = new DoorKey(base.sessionUuid(), station, DoorKey.DoorWallSide.NEGATIVE_LATERAL);
            var candidate = resolver.resolve(key, policy, List.of(), () -> new SourceGenerationSnapshot(server.getOverworld().getSeed(), source));
            if (!(candidate instanceof QuantumCandidate.New target)) continue;
            var measured = new SessionRecoveryRecord(base.sessionUuid(), base.chamberUuid(), base.origin(), base.participants(),
                    List.of(), SessionState.MEASURED, false, base.semantics(), Optional.of(policy),
                    List.of(new CandidateLedgerEntry(key, candidate)),
                    Optional.of(new CandidateSelection.Selected(key, candidate.candidateId(), PLAYER, 0, 1)));
            journal.put(measured);
            journal.flush(server);
            require(measured.equals(journal.flushedRecords().get(measured.sessionUuid())), "未 checked MEASURED+SELECTED");
            var id = UniverseAllocationLedger.deriveUniverseId(target.intent().allocationToken());
            var next = measured.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(server.getOverworld().getTime(), new CollapseDestination.Universe(id)));
            require(journal.commitRestore(server, measured, next).committed(), "COLLAPSING 提交失敗");
            return;
        }
        throw new IllegalStateException("找不到 NEW 候選");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
