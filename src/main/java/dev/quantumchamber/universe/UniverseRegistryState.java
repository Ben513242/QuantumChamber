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
    private String loadError;
    private MinecraftServer owner;

    public static UniverseRegistryState get(MinecraftServer server) {
        requireServerThread(server);
        var path = path(server);
        var manager = server.getOverworld().getPersistentStateManager();
        var type = new PersistentState.Type<>(
                UniverseRegistryState::new,
                (NbtCompound ignored, RegistryWrapper.WrapperLookup lookup) -> load(path),
                DataFixTypes.LEVEL);
        var state = manager.get(type, STATE_ID);
        if (state == null) {
            state = load(path);
            manager.set(STATE_ID, state);
        }
        state.owner = server;
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
            return fromNbt(UniverseCatalogStore.read(path).getCompound("data"));
        } catch (IOException | RuntimeException exception) {
            return failed("catalog 無法讀取: " + exception);
        }
    }

    public static UniverseRegistryState fromNbt(NbtCompound nbt) {
        try {
            requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
            if (nbt.getInt("SchemaVersion") != 1) {
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
            for (int index = 0; index < rawRecords.size(); index++) {
                decoded.add(UniverseRecord.fromNbt(rawRecords.getCompound(index)));
            }
            var state = new UniverseRegistryState();
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
        if (!isDirty()) {
            return;
        }
        var wrapped = new NbtCompound();
        wrapped.put("data", writeNbt(new NbtCompound()));
        NbtHelper.putDataVersion(wrapped);
        try {
            UniverseCatalogStore.write(target, wrapped);
        } catch (IOException exception) {
            throw new UncheckedIOException(
                    "Universe catalog 落盤失敗，保留 dirty 與上一個 durable snapshot", exception);
        }
        flushed = current.records();
        setDirty(false);
    }

    public NbtCompound writeNbt(NbtCompound nbt) {
        requireHealthy();
        nbt.putInt("SchemaVersion", 1);
        var entries = new NbtList();
        current.records().values().stream()
                .sorted(Comparator
                        .comparingLong((UniverseRecord record) -> record.definition().allocationOrdinal())
                        .thenComparing(record -> record.definition().universeId().value().toString()))
                .forEach(record -> entries.add(record.toNbt()));
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
