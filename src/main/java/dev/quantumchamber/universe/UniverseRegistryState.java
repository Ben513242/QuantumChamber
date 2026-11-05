package dev.quantumchamber.universe;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;

/** 保存 Universe catalog current／flushed 雙層權威。 */
public final class UniverseRegistryState extends PersistentState {
    public static final String STATE_ID = "quantumchamber_universes";

    public enum StorageInventory {
        READY,
        ORPHANED
    }

    private UniverseRegistry current = new UniverseRegistry();
    private Map<UniverseId, UniverseRecord> flushed = Map.of();
    private int envelopeVersion = 1;
    private final Map<UniverseId, NbtCompound> legacyRows = new LinkedHashMap<>();
    private String loadError;
    private static final Map<MinecraftServer, java.lang.ref.WeakReference<UniverseRegistryState>> INSTALLATIONS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private MinecraftServer owner;
    private NbtCompound checkedWrapper;
    private NbtCompound attemptedWrapper;
    private final UniverseCatalogStore.Readback readback;

    public UniverseRegistryState() {
        this(UniverseCatalogStore::read);
    }

    /** 決策 9 的 package-private IO port：{@link #save} 經 {@link UniverseCatalogStore} 寫出後的讀回。production 為 {@link UniverseCatalogStore#read}。 */
    UniverseRegistryState(UniverseCatalogStore.Readback readback) {
        this.readback = Objects.requireNonNull(readback, "readback");
    }

    public static UniverseRegistryState get(MinecraftServer server) {
        requireServerThread(server);
        var path = path(server);
        var manager = server.getOverworld().getPersistentStateManager();
        var knownRef = INSTALLATIONS.get(server);
        var known = knownRef == null ? null : knownRef.get();
        var loaded = new UniverseRegistryState[1];
        var type = new PersistentState.Type<>(
                UniverseRegistryState::new,
                (NbtCompound ignored, RegistryWrapper.WrapperLookup lookup) -> loaded[0] = load(path),
                DataFixTypes.LEVEL);
        var state = manager.get(type, STATE_ID);
        if (knownRef != null && (known == null || state != known)) {
            throw new IllegalStateException("catalog 原生 state 已被替換");
        }
        if (known == null && state != null && state != loaded[0]) {
            throw new IllegalStateException("catalog key 已有外部提供的 state");
        }
        if (state == null) {
            state = load(path);
            manager.set(STATE_ID, state);
        }
        state.owner = server;
        if (known == null) INSTALLATIONS.put(server, new java.lang.ref.WeakReference<>(state));
        return state;
    }

    static UniverseRegistryState load(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return new UniverseRegistryState();
        } catch (IOException | RuntimeException exception) {
            return failed("無法確認 catalog 路徑: " + exception);
        }

        try {
            var wrapped = UniverseCatalogStore.read(path);
            var state = fromNbt(wrapped.getCompound("data"));
            state.checkedWrapper = wrapped;
            return state;
        } catch (IOException | RuntimeException exception) {
            return failed("catalog 無法讀取: " + exception);
        }
    }

    public static UniverseRegistryState fromNbt(NbtCompound nbt) {
        try {
            requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
            int schema = nbt.getInt("SchemaVersion");
            if ((schema != 1 && schema != 2) || (schema == 2 && !nbt.getKeys().equals(Set.of("SchemaVersion", "Records")))) {
                throw new IllegalArgumentException("不支援 Universe catalog schema");
            }
            requireType(nbt, "Records", NbtElement.LIST_TYPE);
            var rawRecords = (NbtList) nbt.get("Records");
            int expectedHeldType = rawRecords.isEmpty()
                    ? NbtElement.END_TYPE
                    : NbtElement.COMPOUND_TYPE;
            if (rawRecords.getHeldType() != expectedHeldType) {
                throw new IllegalArgumentException("Records 必須包含 compound");
            }
            var decoded = new ArrayList<UniverseRecord>(rawRecords.size());
            var state = new UniverseRegistryState();
            state.envelopeVersion = schema;
            for (int index = 0; index < rawRecords.size(); index++) {
                var raw = rawRecords.getCompound(index);
                if (schema == 2 && raw.getInt("SchemaVersion") == 1) {
                    if (!raw.getKeys().equals(Set.of("SchemaVersion", "UniverseUuid", "AllocationOrdinal", "DesiredAvailability", "Worlds"))) {
                        throw new IllegalArgumentException("schema2 內的舊 record 必須維持舊形狀");
                    }
                    var roles = raw.getList("Worlds", NbtElement.COMPOUND_TYPE);
                    if (roles.size() != 1 || !roles.getCompound(0).getKeys().equals(Set.of("WorldKey", "Role", "GeneratorProfile", "SeedPolicy", "StoragePolicyVersion"))) {
                        throw new IllegalArgumentException("schema2 內的舊 role 必須維持五欄");
                    }
                }
                var record = UniverseRecord.fromNbt(raw);
                if (record.definition().schemaVersion() > schema) throw new IllegalArgumentException("catalog envelope 版本低於 record");
                if (record.definition().schemaVersion() == 1) state.legacyRows.put(record.definition().universeId(), raw.copy());
                decoded.add(record);
            }
            state.current = new UniverseRegistry(decoded);
            state.flushed = state.current.records();
            return state;
        } catch (RuntimeException exception) {
            return failed("catalog schema 無法解析: " + exception);
        }
    }

    /** 純讀盤點動態 Universe world key 擁有的 storage；不採用、修改或刪除任何路徑。 */
    public static StorageInventory inspectStorage(
            Path saveRoot, Collection<UniverseRecord> catalogRecords) throws IOException {
        Objects.requireNonNull(saveRoot, "saveRoot");
        Objects.requireNonNull(catalogRecords, "catalogRecords");
        UniverseRegistry.validate(catalogRecords);

        var expectedWorlds = new HashSet<Path>();
        var expectedUniverseDirectories = new HashSet<Path>();
        for (var record : catalogRecords) {
            for (var descriptor : record.definition().worlds().values()) {
                var identifier = descriptor.worldKey().getValue();
                var worldStorage = saveRoot.toAbsolutePath().normalize()
                        .resolve("dimensions")
                        .resolve(identifier.getNamespace())
                        .resolve(identifier.getPath())
                        .normalize();
                expectedWorlds.add(worldStorage);
                expectedUniverseDirectories.add(worldStorage.getParent());
            }
        }

        var ownedRoot = saveRoot.toAbsolutePath().normalize()
                .resolve("dimensions")
                .resolve("quantumchamber")
                .resolve("universe");
        if (!Files.exists(ownedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return StorageInventory.READY;
        }
        if (!Files.isDirectory(ownedRoot, LinkOption.NOFOLLOW_LINKS)) {
            return StorageInventory.ORPHANED;
        }

        try (var universeDirectories = Files.list(ownedRoot)) {
            for (var universeDirectory : universeDirectories.toList()) {
                var normalizedUniverse = universeDirectory.toAbsolutePath().normalize();
                if (!expectedUniverseDirectories.contains(normalizedUniverse)
                        || !Files.isDirectory(normalizedUniverse, LinkOption.NOFOLLOW_LINKS)) {
                    return StorageInventory.ORPHANED;
                }
                try (var worldDirectories = Files.list(normalizedUniverse)) {
                    for (var worldDirectory : worldDirectories.toList()) {
                        var normalizedWorld = worldDirectory.toAbsolutePath().normalize();
                        if (!expectedWorlds.contains(normalizedWorld)
                                || !Files.isDirectory(normalizedWorld, LinkOption.NOFOLLOW_LINKS)) {
                            return StorageInventory.ORPHANED;
                        }
                    }
                }
            }
        }
        return StorageInventory.READY;
    }

    public void requireHealthy() {
        if (loadError != null) {
            throw new IllegalStateException("拒絕使用損壞的 " + STATE_ID + ": " + loadError);
        }
    }

    public Map<UniverseId, UniverseRecord> records() {
        requireHealthy();
        return current.records();
    }

    public Map<UniverseId, UniverseRecord> flushedRecords() {
        requireHealthy();
        return flushed;
    }

    public java.util.Optional<UniverseRecord> find(UniverseId id) {
        requireHealthy();
        return current.find(id);
    }

    public java.util.Optional<UniverseRecord> findByWorldKey(RegistryKey<World> worldKey) {
        requireHealthy();
        return current.findByWorldKey(worldKey);
    }

    public UniverseRecord allocateOverworld(UUID uuid, long allocationOrdinal) {
        requireMutationThread();
        requireHealthy();
        var candidate = current.copy();
        var result = candidate.allocateOverworld(uuid, allocationOrdinal);
        return commitCandidate(candidate, result);
    }

    public boolean upgradeEnvelope() {
        requireMutationThread();
        requireHealthy();
        if (envelopeVersion == 2) return false;
        // 升級前先依新 envelope 的規則驗原列，避免寫出下一次載入會拒絕的 catalog。
        var upgraded = writeNbt(new NbtCompound());
        upgraded.putInt("SchemaVersion", 2);
        fromNbt(upgraded).requireHealthy();
        envelopeVersion = 2;
        markDirty();
        return true;
    }

    public UniverseRecord allocateM6(UniverseRecord record) {
        requireMutationThread();
        requireHealthy();
        if (envelopeVersion != 2 || isDirty() || record.generation() == null
                || record.generation().provenance() != UniverseRecord.Provenance.NEW_M6
                || record.desiredAvailability() != DesiredAvailability.DISABLED) {
            throw new IllegalStateException("配置 NEW_M6 前須有 checked schema2 envelope");
        }
        var candidate = current.copy();
        return commitCandidate(candidate, candidate.register(record));
    }

    public UniverseRecord changeAvailability(UniverseId id, DesiredAvailability availability) {
        requireMutationThread();
        requireHealthy();
        var candidate = current.copy();
        var result = candidate.changeAvailability(id, availability);
        return commitCandidate(candidate, result);
    }

    public void flush(MinecraftServer server) {
        requireServerThread(server);
        if (get(server) != this) {
            throw new IllegalStateException("Universe catalog 不屬於此 server");
        }
        save(path(server));
    }

    public void save(Path target) {
        requireMutationThread();
        requireHealthy();
        if (owner != null && get(owner) != this) throw new IllegalStateException("catalog 寫入 owner 不符");
        if (envelopeVersion == 2 && owner != null) requireDiskBaseline(target);
        if (!isDirty()) return;
        var wrapped = new NbtCompound();
        wrapped.put("data", writeNbt(new NbtCompound()));
        NbtHelper.putDataVersion(wrapped);
        try {
            if (envelopeVersion == 2) verifyEncodedState(wrapped);
            attemptedWrapper = wrapped;
            UniverseCatalogStore.write(target, wrapped, candidate -> {
                var observed = readback.read(candidate);
                if (envelopeVersion == 2) verifyEncodedState(observed);
                return observed;
            });
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Universe catalog 落盤失敗，保留 dirty 與上一個 durable snapshot", exception);
        }
        checkedWrapper = wrapped;
        attemptedWrapper = null;
        flushed = current.records();
        setDirty(false);
    }

    private void verifyEncodedState(NbtCompound wrapped) {
        var decoded = fromNbt(wrapped.getCompound("data"));
        decoded.requireHealthy();
        if (decoded.envelopeVersion != envelopeVersion || !decoded.records().equals(current.records())) {
            throw new IllegalStateException("catalog strict 讀回的版本或 records 不符");
        }
    }

    public void verifyIfSchemaTwo() {
        if (envelopeVersion == 2) verifyM6Baseline();
    }

    public void verifyM6Baseline() {
        requireHealthy();
        if (owner != null) {
            if (get(owner) != this) throw new IllegalStateException("catalog 使用的 owner 不符");
            requireDiskBaseline(path(owner));
        }
    }

    private void requireDiskBaseline(Path target) {
        try {
            var actual = UniverseCatalogStore.read(target);
            if (!actual.equals(checkedWrapper) && !actual.equals(attemptedWrapper)) {
                throw new IllegalStateException("catalog 檔案與已確認／等待讀回的內容不符");
            }
        } catch (NoSuchFileException absent) {
            if (checkedWrapper != null) throw new IllegalStateException("已確認的 catalog 檔案消失", absent);
        } catch (IOException failure) {
            throw new UncheckedIOException("無法驗證 catalog 檔案", failure);
        }
    }

    public NbtCompound writeNbt(NbtCompound nbt) {
        requireHealthy();
        nbt.putInt("SchemaVersion", envelopeVersion);
        var entries = new NbtList();
        var records = current.records().values().stream();
        if (envelopeVersion == 1) {
            records = records.sorted(Comparator.comparingLong((UniverseRecord record) -> record.definition().allocationOrdinal())
                    .thenComparing(record -> record.definition().universeId().value().toString()));
        }
        records.forEach(record -> {
            var original = legacyRows.get(record.definition().universeId());
            if (original == null) {
                entries.add(record.toNbt());
            } else {
                var row = original.copy();
                row.putString("DesiredAvailability", record.desiredAvailability().name());
                entries.add(row);
            }
        });
        nbt.put("Records", entries);
        return nbt;
    }

    @Override
    public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
        return writeNbt(nbt);
    }

    @Override
    public void save(File file, RegistryWrapper.WrapperLookup lookup) {
        save(file.toPath());
    }

    private UniverseRecord commitCandidate(UniverseRegistry candidate, UniverseRecord result) {
        UniverseRegistry.validate(candidate.records().values());
        if (!candidate.records().equals(current.records())) {
            current = candidate;
            markDirty();
        }
        return result;
    }

    private static UniverseRegistryState failed(String error) {
        var state = new UniverseRegistryState();
        state.loadError = error;
        return state;
    }

    private static Path path(MinecraftServer server) {
        return server.getSavePath(WorldSavePath.ROOT)
                .resolve("data")
                .resolve(STATE_ID + ".dat");
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) {
            throw new IllegalStateException("Universe catalog 必須在伺服器執行緒操作");
        }
    }

    private void requireMutationThread() {
        if (owner != null) {
            requireServerThread(owner);
        }
    }

    private static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) {
            throw new IllegalArgumentException(key + " 缺失或 NBT 型別錯誤");
        }
    }
}
