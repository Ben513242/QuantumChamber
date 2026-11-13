package dev.quantumchamber.candidate;

import dev.quantumchamber.persistence.HomeJournalBudget.CapacityExceeded;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/** 候選 journal 的 checked 提交入口；不處理實體門與世界物化。 */
public final class CandidateLedgerService {
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("quantumchamber");
    private static final int MAX_NEW_ENTRIES = 256;
    private static final Comparator<DoorKey> KEY_ORDER = Comparator.comparingLong(DoorKey::logicalStationIndex)
            .thenComparing(DoorKey::wallSide);
    public enum BatchFailure { NONE, OTHER, UNKNOWN_SOURCE_PRESET }
    /** 只有來源preset辨識的精確空值使用此型別；不涵蓋provider或IO失敗。 */
    public static final class UnknownSourcePresetException extends IllegalStateException {
        public UnknownSourcePresetException() { super("不支援來源 preset，禁止 NEW"); }
    }
    public record CandidateBatchResult(List<DoorKey> committedKeys, boolean sessionFailed, BatchFailure failure) {
        public CandidateBatchResult(List<DoorKey> keys, boolean failed) { this(keys,failed,failed?BatchFailure.OTHER:BatchFailure.NONE); }
        public CandidateBatchResult {
            committedKeys = List.copyOf(committedKeys);Objects.requireNonNull(failure);
            if(sessionFailed==(failure==BatchFailure.NONE))throw new IllegalArgumentException("batch失敗型別不一致");
        }
    }
    /**
     * COMMIT_FAILED：checked 提交已嘗試但失敗；未 checked authority 已由本 owner 還原或隔離（兩者都失敗時記 ERROR，由 recovery tick 隔離），
     * caller 必須標記 session failure。
     */
    public enum SelectionOutcome { SELECTED, ALREADY_SELECTED, NOT_SELECTABLE, REJECTED, COMMIT_FAILED }

    interface JournalPort {
        Map<UUID, SessionRecoveryRecord> flushedRecords();
        SessionRecoveryRecord currentRecord(UUID sessionUuid);
        void put(SessionRecoveryRecord record);
        void flush();
        boolean abortUnchecked(SessionRecoveryRecord expected);
        /** abort 本身失敗時，以隔離 fail-closed 停住該 session。 */
        boolean quarantine(UUID sessionUuid);
    }
    /** 已完成 CAS 還原／隔離並記錄 root cause 的提交失敗。 */
    private static final class CommitFailure extends RuntimeException {
        CommitFailure(Throwable cause) { super(cause.getMessage(), cause, false, false); }
    }
    record CandidateInputs(CandidateResolver resolver, UniverseDiscoveryState discovery,
            java.util.function.Function<SourceFamilyRef, SourceGenerationSnapshot> source) {
        CandidateInputs(CandidateResolver resolver, UniverseDiscoveryState discovery) {
            this(resolver, discovery, ignored -> { throw new IllegalStateException("M6 source 未接線"); });
        }
    }
    @FunctionalInterface interface InputsLoader { CandidateInputs load() throws IOException; }

    public CandidateBatchResult commitCandidates(MinecraftServer server, UUID sessionUuid, List<DoorKey> completeKeys) {
        CandidateBatchResult result;
        try {
            result = commitCandidates(journal(server), () -> requireServerThread(server), () -> {
                var root = server.getSavePath(WorldSavePath.ROOT);
                // 已存在 candidate session 就是 M4 evidence；缺失的 entropy 不得重新產生。
                return new CandidateInputs(new CandidateResolver(CandidateEntropyState.loadOrCreate(root, true),
                        dev.quantumchamber.universe.Minecraft121SettingsLifecycle.registry(),
                        () -> dev.quantumchamber.universe.Minecraft121SettingsLifecycle.acquire(server)),
                        UniverseDiscoveryState.load(root), source -> sourceSnapshot(server, source));
            }, sessionUuid, completeKeys);
        } catch (RuntimeException exception) {
            LOGGER.warn("候選 batch 無法進入 checked 提交，session={} 將安全返還：{}", sessionUuid, FailureText.describe(exception));
            return failedBatch();
        }
        // 通知必須在上方catch之外；正常返回才表示exact owner已實際設旗標。
        if(result.failure()==BatchFailure.UNKNOWN_SOURCE_PRESET) {
            var state=SessionRecoveryState.get(server);
            var record=state.flushedRecords().get(sessionUuid);
            if(record==null || !record.equals(state.records().get(sessionUuid)))throw new IllegalStateException("未知preset通知缺少exact checked record");
            dev.quantumchamber.chamber.ChamberSessions.gateway().onUnknownSourcePreset(server,record);
        }
        return result;
    }
    public SelectionOutcome trySelect(MinecraftServer server, UUID sessionUuid, DoorKey doorKey, UUID playerUuid, long gameTime) {
        try {
            return trySelect(journal(server), () -> requireServerThread(server), sessionUuid, doorKey, playerUuid, gameTime);
        } catch (RuntimeException exception) {
            LOGGER.debug("側門選擇在提交前拒絕，session={}：{}", sessionUuid, FailureText.describe(exception));
            return SelectionOutcome.REJECTED;
        }
    }
    CandidateBatchResult commitCandidates(JournalPort journal, Runnable threadGuard, InputsLoader inputs,
            UUID sessionUuid, List<DoorKey> completeKeys) {
        try {
            threadGuard.run();
            var previous = candidateRecord(journal, sessionUuid);
            if (previous.state() != SessionState.SUPERPOSITION
                    || !(previous.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable)) return failedBatch();
            var requested = new TreeSet<DoorKey>(KEY_ORDER);
            for (var key : completeKeys) {
                if (!sessionUuid.equals(key.sessionUuid())) throw new IllegalArgumentException("DoorKey session 不符");
                requested.add(key);
            }
            var existing = new HashSet<DoorKey>();
            previous.candidateLedger().forEach(entry -> existing.add(entry.doorKey()));
            var missing = requested.stream().filter(key -> !existing.contains(key)).limit(MAX_NEW_ENTRIES).toList();
            if (missing.isEmpty()) return committedKeys(previous, requested);
            var context = previous.candidateContext().orElseThrow();
            if (previous.candidateLedger().size() + missing.size() > context.maxCandidateEntries()) return failedBatch();
            var loaded = inputs.load();
            var pool = loaded.discovery().eligibleSnapshot(context.discoveryWatermark(), context.sourceFamilyRef());
            var ledger = new ArrayList<>(previous.candidateLedger());
            for (var key : missing) ledger.add(new CandidateLedgerEntry(key, loaded.resolver().resolve(key, context, pool,
                    () -> loaded.source().apply(context.sourceFamilyRef()))));
            var next = copy(previous, ledger, previous.candidateSelection().orElseThrow(), previous.state());
            try { return committedKeys(commitChecked(journal, previous, next), requested); }
            catch(CapacityExceeded pending) {
                // 舊門保留已確認的發佈權；漏掉的新 key 由既有每 tick 的批次重新提出。
                return committedKeys(previous,requested);
            }
        } catch (CommitFailure alreadyLogged) {
            return failedBatch();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("候選 batch 無法 checked 提交，session={} 將安全返還：{}", sessionUuid, FailureText.describe(exception));
            var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
            for(Throwable cause=exception;cause!=null && seen.add(cause);cause=cause.getCause()) {
                if(cause instanceof UnknownSourcePresetException)return new CandidateBatchResult(List.of(),true,BatchFailure.UNKNOWN_SOURCE_PRESET);
            }
            return failedBatch();
        }
    }

    /** 只由 NEW lazy 分支呼叫；以凍結來源取得當次 exact server 的設定 lease。 */
    private static SourceGenerationSnapshot sourceSnapshot(MinecraftServer server, SourceFamilyRef source) {
        var lease = dev.quantumchamber.universe.Minecraft121SettingsLifecycle.acquire(server);
        var registry = dev.quantumchamber.universe.Minecraft121SettingsLifecycle.registry();
        if (source instanceof SourceFamilyRef.Catalog catalogSource) {
            var catalog = dev.quantumchamber.universe.UniverseRegistryState.get(server);
            catalog.requireHealthy();
            var record = java.util.Objects.requireNonNull(catalog.flushedRecords().get(catalogSource.universeId()), "來源 catalog");
            if (!record.equals(catalog.records().get(catalogSource.universeId())))
                throw new IllegalStateException("來源 catalog 尚未 checked");
            if (record.generation() != null) {
                var snapshot = dev.quantumchamber.universe.GenerationSettingsSnapshot.fromNbt(record.generation().targetSettingsSnapshot());
                registry.resolve(snapshot, lease);
                return new SourceGenerationSnapshot(record.generation().rootWorldSeed(), snapshot);
            }
        }
        var preset = dev.quantumchamber.universe.Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(lease)
                .orElseThrow(UnknownSourcePresetException::new);
        var parameters = new net.minecraft.nbt.NbtCompound();
        parameters.putString("OverworldPreset", preset);
        parameters.putByte("GenerateStructures", (byte)(lease.sourceGenerateStructures() ? 1 : 0));
        var snapshot = registry.createSnapshot("quantumchamber:vanilla_" + preset + "_v1", 1, parameters, lease);
        lease.requireValid();
        return new SourceGenerationSnapshot(server.getSaveProperties().getGeneratorOptions().getSeed(), snapshot);
    }
    SelectionOutcome trySelect(JournalPort journal, Runnable threadGuard,
            UUID sessionUuid, DoorKey doorKey, UUID playerUuid, long gameTime) {
        try {
            threadGuard.run();
            var previous = candidateRecord(journal, sessionUuid);
            if (gameTime < 0 || !sessionUuid.equals(doorKey.sessionUuid())
                    || previous.participants().stream().noneMatch(person -> person.playerUuid().equals(playerUuid))) {
                return SelectionOutcome.REJECTED;
            }
            if (previous.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected) {
                return SelectionOutcome.ALREADY_SELECTED;
            }
            if (previous.state() != SessionState.SUPERPOSITION) return SelectionOutcome.REJECTED;
            var entry = previous.candidateLedger().stream().filter(candidate -> candidate.doorKey().equals(doorKey)).findFirst();
            if (entry.isEmpty()) return SelectionOutcome.NOT_SELECTABLE;
            var selection = new CandidateSelection.Selected(doorKey, entry.orElseThrow().candidate().candidateId(), playerUuid, gameTime, 1);
            var next = copy(previous, previous.candidateLedger(), selection, SessionState.MEASURED);
            commitChecked(journal, previous, next);
            return SelectionOutcome.SELECTED;
        } catch (CommitFailure alreadyLogged) {
            return SelectionOutcome.COMMIT_FAILED;
        } catch (RuntimeException exception) {
            LOGGER.debug("側門選擇在提交前拒絕，session={}：{}", sessionUuid, FailureText.describe(exception));
            return SelectionOutcome.REJECTED;
        }
    }

    private static SessionRecoveryRecord candidateRecord(JournalPort journal, UUID sessionUuid) {
        Objects.requireNonNull(sessionUuid, "sessionUuid");
        var record = Objects.requireNonNull(journal.flushedRecords().get(sessionUuid), "flushed session");
        if (!sessionUuid.equals(record.sessionUuid()) || record.candidateContext().isEmpty()
                || record.candidateLedger().size() > record.candidateContext().orElseThrow().maxCandidateEntries()) {
            throw new IllegalArgumentException("缺少合法 candidate session authority");
        }
        requireExpectedCurrent(journal, record);
        return record;
    }

    /**
     * 唯一的 candidate／selection 提交點。put 之後任何失敗都先以 CAS 還原未 checked authority（CAS 不符時隔離；abort 本身失敗時也改為隔離），
     * 確保之後其他 session 的 flush、原生 autosave 或 stop 都不能承認已回報失敗的 ledger／SELECTED。log 依實際處置描述。
     */
    private static SessionRecoveryRecord commitChecked(JournalPort journal, SessionRecoveryRecord previous, SessionRecoveryRecord next) {
        if (!SessionRecoveryRecord.sameAuthority(previous, next)) throw new IllegalArgumentException("候選 authority 非單調更新");
        requireExpectedCurrent(journal, previous);
        try {
            journal.put(next);
        } catch(CapacityExceeded pending) {
            // 僅 put 的無副作用預檢可退避。flush 或其他例外仍要 containment。
            throw pending;
        } catch(RuntimeException failure) {
            throw failedCommit(journal,next,failure);
        }
        try {
            journal.flush();
            var actual = journal.flushedRecords().get(next.sessionUuid());
            if (!next.equals(actual)) throw new IllegalStateException("候選 journal exact readback 不符");
            return actual;
        } catch (RuntimeException failure) {
            throw failedCommit(journal,next,failure);
        }
    }
    private static CommitFailure failedCommit(JournalPort journal,SessionRecoveryRecord next,RuntimeException failure) {
        var containment = contain(journal, next, failure);
        if (containment == Containment.UNCONTAINED) {
            LOGGER.error("候選 checked 提交失敗，session={}，{}：{}", next.sessionUuid(), containment.description, FailureText.describe(failure));
        } else {
            LOGGER.warn("候選 checked 提交失敗，session={}，{}：{}", next.sessionUuid(), containment.description, FailureText.describe(failure));
        }
        return new CommitFailure(failure);
    }

    /** 提交失敗後未 checked authority 的實際處置。 */
    enum Containment {
        RESTORED("已無未 checked authority（CAS 已還原為上一份 flushed authority，或 next 已 checked 而不倒退，或 session 已不在 current）"),
        QUARANTINED("已隔離該 session（CAS 不符，或原本就在隔離中；之後只寫出上一份 flushed authority，由 recovery 收斂）"),
        QUARANTINED_AFTER_ABORT_FAILURE("CAS 還原本身失敗，已隔離該 session（之後只寫出上一份 flushed authority，由 recovery 收斂）"),
        UNCONTAINED("CAS 還原與隔離都失敗，未 checked authority 仍在 current，等待 recovery tick 隔離");
        final String description;
        Containment(String description) { this.description = description; }
    }

    /** 以 CAS 還原未 checked authority；abort 本身失敗時改為隔離。abort／隔離的例外都附在 failure 上。 */
    static Containment contain(JournalPort journal, SessionRecoveryRecord next, RuntimeException failure) {
        try {
            return journal.abortUnchecked(next) ? Containment.RESTORED : Containment.QUARANTINED;
        } catch (RuntimeException abortFailure) {
            failure.addSuppressed(abortFailure);
            try {
                if (journal.quarantine(next.sessionUuid())) return Containment.QUARANTINED_AFTER_ABORT_FAILURE;
                // 沒有新進入隔離：session 已不在 current（沒有可被承認的未 checked authority），或原本就在隔離中。
                return journal.currentRecord(next.sessionUuid()) == null ? Containment.RESTORED : Containment.QUARANTINED;
            } catch (RuntimeException quarantineFailure) {
                failure.addSuppressed(quarantineFailure);
                return Containment.UNCONTAINED;
            }
        }
    }

    /** current 只用於 CAS 前置條件；候選與選擇仍以 expected flushed record 判斷。 */
    private static void requireExpectedCurrent(JournalPort journal, SessionRecoveryRecord expected) {
        if (!expected.equals(journal.currentRecord(expected.sessionUuid()))) {
            throw new IllegalStateException("session current 與 expected flushed record 不符，拒絕覆寫 dirty 進度");
        }
    }

    private static CandidateBatchResult committedKeys(SessionRecoveryRecord checked, Set<DoorKey> requested) {
        return new CandidateBatchResult(checked.candidateLedger().stream().map(CandidateLedgerEntry::doorKey)
                .filter(requested::contains).toList(), false);
    }
    private static CandidateBatchResult failedBatch() { return new CandidateBatchResult(List.of(), true); }

    private static SessionRecoveryRecord copy(SessionRecoveryRecord previous, List<CandidateLedgerEntry> ledger,
            CandidateSelection selection, SessionState state) {
        return new SessionRecoveryRecord(previous.sessionUuid(), previous.chamberUuid(), previous.origin(), previous.participants(),
                previous.spaceLeases(), state, previous.restoreEntryEffectOnReturn(), previous.semantics(),
                previous.candidateContext(), ledger, Optional.of(selection));
    }

    private static JournalPort journal(MinecraftServer server) {
        requireServerThread(server);
        var state = SessionRecoveryState.get(server);
        return new JournalPort() {
            @Override public Map<UUID, SessionRecoveryRecord> flushedRecords() { return state.flushedRecords(); }
            @Override public SessionRecoveryRecord currentRecord(UUID sessionUuid) { return state.records().get(sessionUuid); }
            @Override public void put(SessionRecoveryRecord record) { state.put(record); }
            @Override public void flush() { state.flush(server); }
            @Override public boolean abortUnchecked(SessionRecoveryRecord expected) { return state.abortUnchecked(expected); }
            @Override public boolean quarantine(UUID sessionUuid) { return state.quarantine(sessionUuid); }
        };
    }
    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("候選服務必須在伺服器執行緒操作");
    }
}
