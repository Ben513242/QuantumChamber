package dev.quantumchamber.persistence;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import net.minecraft.world.PersistentState;

/** 使用原生 manager 的生命週期，但讀寫均經過可觀測的 journal 健康閘門。 */
public final class SessionRecoveryState extends PersistentState {
    public static final String STATE_ID = "quantumchamber_sessions";
    @FunctionalInterface interface JournalReadback { NbtCompound read(Path path) throws IOException; }
    private final JournalReadback readback;
    public SessionRecoveryState() { this(SessionJournalStore::read); }
    SessionRecoveryState(JournalReadback readback) { this.readback = java.util.Objects.requireNonNull(readback); }
    private final Map<UUID, SessionRecoveryRecord> records = new LinkedHashMap<>();
    // 僅存活於本 process；remove 與空 journal flush 都不能抹去同 UUID 的最後 authority。
    private final Map<UUID, SessionRecoveryRecord> authorityHistory = new LinkedHashMap<>();
    private Map<UUID, SessionRecoveryRecord> flushed = Map.of();
    // Schema 3 envelope 本身是不可撤銷的初始化證據；不新增 NBT 欄位。
    private boolean candidateInitialized;
    private String loadError;
    private MinecraftServer owner;

    public static SessionRecoveryState get(MinecraftServer server) {
        requireServerThread(server);
        Path path = path(server);
        var manager = server.getOverworld().getPersistentStateManager();
        // 原生 manager 只負責快取；首次 callback 仍從正式檔案 strict read，避免遷移或預設 compound 掩蓋損壞。
        var type = new PersistentState.Type<>(SessionRecoveryState::new,
                (NbtCompound ignored, RegistryWrapper.WrapperLookup lookup) -> load(path), DataFixTypes.LEVEL);
        SessionRecoveryState state = manager.get(type, STATE_ID);
        if (state == null) {
            // 原生讀檔失敗可能回 null；只有 checked read 證明檔案不存在才允許初建。
            state = load(path);
            manager.set(STATE_ID, state);
        }
        state.owner = server;
        return state;
    }

    static SessionRecoveryState load(Path path) {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException absent) {
            return new SessionRecoveryState();
        } catch (IOException | RuntimeException exception) {
            return failed("無法確認 journal 路徑：" + exception);
        }
        try {
            return fromNbt(SessionJournalStore.read(path).getCompound("data"));
        } catch (IOException | RuntimeException exception) {
            // 已見到檔案後消失也屬讀取失敗，不退回空 journal。
            return failed("journal 無法讀取：" + exception);
        }
    }

    public static SessionRecoveryState fromNbt(NbtCompound nbt) {
        try {
            SessionRecoveryRecord.requireType(nbt, "SchemaVersion", 3);
            int schema=nbt.getInt("SchemaVersion");
            if (schema!=1 && schema!=2 && schema!=3) throw new IllegalArgumentException("不支援 journal schema");
            if (schema == 3) CandidateJournalCodec.keys(nbt, "SchemaVersion", "Records");
            var state = new SessionRecoveryState();
            state.candidateInitialized = schema == 3;
            for (var raw : SessionRecoveryRecord.compounds(nbt, "Records", schema)) {
                var record = SessionRecoveryRecord.fromNbt((NbtCompound) raw,schema);
                if (state.records.putIfAbsent(record.sessionUuid(), record) != null) throw new IllegalArgumentException("session UUID 重複");
            }
            validateOwnership(state.records);
            state.flushed = Map.copyOf(state.records);
            state.authorityHistory.putAll(state.flushed);
            return state;
        } catch (RuntimeException exception) {
            return failed("journal schema 無法解析：" + exception);
        }
    }

    public void requireHealthy() {
        if (loadError != null) throw new IllegalStateException("拒絕使用損壞的 " + STATE_ID + "：" + loadError);
    }
    public Map<UUID,SessionRecoveryRecord> records() { requireHealthy(); return Map.copyOf(records); }
    public Map<UUID,SessionRecoveryRecord> flushedRecords() { requireHealthy(); return flushed; }
    public boolean candidateInitialized() { requireHealthy(); return candidateInitialized; }
    /** recovery／世界存檔不可順帶承認其他 session 的 dirty candidate 或 selection。 */
    public boolean recoveryWritesSafe() {
        requireHealthy();
        for(var current : records.values()) if(current.candidateContext().isPresent()) {
            var previous=flushed.get(current.sessionUuid());
            if(previous==null || !previous.candidateContext().equals(current.candidateContext())
                    || !previous.candidateLedger().equals(current.candidateLedger())
                    || !previous.candidateSelection().equals(current.candidateSelection())) return false;
        }
        return true;
    }
    public void put(SessionRecoveryRecord record) {
        requireMutationThread();
        requireHealthy();
        if (candidateInitialized && record.candidateContext().isEmpty()) {
            throw new IllegalArgumentException("已初始化 candidate 的 journal 不得重新加入 legacy record");
        }
        var current=records.get(record.sessionUuid());
        var durable=flushed.get(record.sessionUuid());
        var historical=authorityHistory.get(record.sessionUuid());
        // 同時保護 dirty、durable 與已移除紀錄的 authority。
        if ((current!=null && !SessionRecoveryRecord.sameAuthority(current,record))
                || (durable!=null && !SessionRecoveryRecord.sameAuthority(durable,record))
                || (historical!=null && !SessionRecoveryRecord.sameAuthority(historical,record))) {
            throw new IllegalArgumentException("同一 session 的凍結 authority 不可改動，ledger 與 selection 不可倒退");
        }
        var candidate = new LinkedHashMap<>(records); candidate.put(record.sessionUuid(), record);
        validateOwnership(candidate);
        records.put(record.sessionUuid(), record); authorityHistory.put(record.sessionUuid(), record);
        candidateInitialized |= record.candidateContext().isPresent();
        markDirty();
    }
    public boolean remove(UUID id) {
        requireMutationThread();
        requireHealthy();
        if(java.util.stream.Stream.of(records.get(id),flushed.get(id),authorityHistory.get(id))
                .anyMatch(record -> record!=null && record.state()==dev.quantumchamber.superposition.SessionState.MEASURED))
            throw new IllegalStateException("MEASURED receipt 必須保留，不能經一般 remove 消耗");
        if (records.remove(id) == null) return false;
        markDirty(); return true;
    }
    public void flush(MinecraftServer server) {
        requireServerThread(server);
        if (get(server) != this) throw new IllegalStateException("journal 不屬於此 server");
        save(path(server).toFile(), server.getRegistryManager());
    }
    public NbtCompound writeNbt(NbtCompound nbt) {
        requireHealthy();
        nbt.putInt("SchemaVersion", candidateInitialized ? 3 : envelopeSchema(records));
        var entries = new NbtList(); records.values().forEach(record -> entries.add(record.toNbt()));
        nbt.put("Records", entries); return nbt;
    }
    @Override public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) { return writeNbt(nbt); }

    @Override public void save(File file, RegistryWrapper.WrapperLookup lookup) {
        requireMutationThread();
        requireHealthy();
        if (!isDirty()) return;
        var wrapped = new NbtCompound(); wrapped.put("data", writeNbt(new NbtCompound()));
        NbtHelper.putDataVersion(wrapped);
        var expected = Map.copyOf(records);
        try {
            SessionJournalStore.write(file.toPath(), wrapped);
            var actual = readback.read(file.toPath());
            var decoded = fromNbt(actual.getCompound("data"));
            decoded.requireHealthy();
            if (!wrapped.equals(actual) || !expected.equals(decoded.records())) {
                throw new IOException("journal 正式檔 exact readback 不符");
            }
            flushed = decoded.flushedRecords();
            authorityHistory.putAll(flushed);
        } catch (IOException | RuntimeException exception) {
            throw new UncheckedIOException("journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot",
                    exception instanceof IOException io ? io : new IOException("journal strict readback 失敗", exception));
        }
        setDirty(false);
    }

    private static SessionRecoveryState failed(String error) {
        var state = new SessionRecoveryState(); state.loadError = error; return state;
    }
    private static Path path(MinecraftServer server) { return server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(STATE_ID + ".dat"); }
    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("journal 必須在伺服器執行緒操作");
    }
    private void requireMutationThread() {
        if (owner != null) requireServerThread(owner);
    }
    private static void validateOwnership(Map<UUID, SessionRecoveryRecord> records) {
        envelopeSchema(records);
        var chambers = new HashSet<UUID>(); var players = new HashSet<UUID>(); var slots = new HashSet<Integer>();
        for (var record : records.values()) {
            if (!chambers.add(record.chamberUuid())) throw new IllegalArgumentException("來源艙已有 session");
            for (var person : record.participants()) if (!players.add(person.playerUuid())) throw new IllegalArgumentException("玩家跨 session 重複");
            for (var lease : record.spaceLeases()) if (!slots.add(lease.slotId())) throw new IllegalArgumentException("slot 跨 session 重複");
        }
    }
    private static int envelopeSchema(Map<UUID, SessionRecoveryRecord> records) {
        boolean legacy = false; boolean candidate = false;
        for (var record : records.values()) {
            if (record.candidateContext().isPresent()) candidate = true; else legacy = true;
        }
        if (legacy && candidate) throw new IllegalArgumentException("同一 journal envelope 不可混裝 legacy 與 candidate sessions");
        return candidate ? 3 : 2;
    }
}
