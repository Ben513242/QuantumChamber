package dev.quantumchamber.candidate;

import dev.quantumchamber.corridor.DoorKey;
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
    public record CandidateBatchResult(List<DoorKey> committedKeys, boolean sessionFailed) {
        public CandidateBatchResult { committedKeys = List.copyOf(committedKeys); }
    }
    /** COMMIT_FAILED：checked 提交已嘗試但失敗；未 checked authority 已由本 owner 還原或隔離，caller 必須標記 session failure。 */
    public enum SelectionOutcome { SELECTED, ALREADY_SELECTED, NOT_SELECTABLE, REJECTED, COMMIT_FAILED }

    interface JournalPort {
        Map<UUID, SessionRecoveryRecord> flushedRecords();
        SessionRecoveryRecord currentRecord(UUID sessionUuid);
        void put(SessionRecoveryRecord record);
        void flush();
        boolean abortUnchecked(SessionRecoveryRecord expected);
    }
    /** 已完成 CAS 還原／隔離並記錄 root cause 的提交失敗。 */
    private static final class CommitFailure extends RuntimeException {
        CommitFailure(Throwable cause) { super(cause.getMessage(), cause, false, false); }
    }
    record CandidateInputs(CandidateResolver resolver, UniverseDiscoveryState discovery) {}
    @FunctionalInterface interface InputsLoader { CandidateInputs load() throws IOException; }

    public CandidateBatchResult commitCandidates(MinecraftServer server, UUID sessionUuid, List<DoorKey> completeKeys) {
        try {
            return commitCandidates(journal(server), () -> requireServerThread(server), () -> {
                var root = server.getSavePath(WorldSavePath.ROOT);
                // 已存在 candidate session 就是 M4 evidence；缺失的 entropy 不得重新產生。
                return new CandidateInputs(new CandidateResolver(CandidateEntropyState.loadOrCreate(root, true)),
                        UniverseDiscoveryState.load(root));
            }, sessionUuid, completeKeys);
        } catch (RuntimeException exception) {
            LOGGER.warn("候選 batch 無法進入 checked 提交，session={} 將安全返還：{}", sessionUuid, describe(exception));
            return failedBatch();
        }
    }
    public SelectionOutcome trySelect(MinecraftServer server, UUID sessionUuid, DoorKey doorKey, UUID playerUuid, long gameTime) {
        try {
            return trySelect(journal(server), () -> requireServerThread(server), sessionUuid, doorKey, playerUuid, gameTime);
        } catch (RuntimeException exception) {
            LOGGER.debug("側門選擇在提交前拒絕，session={}：{}", sessionUuid, describe(exception));
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
            for (var key : missing) ledger.add(new CandidateLedgerEntry(key, loaded.resolver().resolve(key, context, pool)));
            var next = copy(previous, ledger, previous.candidateSelection().orElseThrow(), previous.state());
            return committedKeys(commitChecked(journal, previous, next), requested);
        } catch (CommitFailure alreadyLogged) {
            return failedBatch();
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("候選 batch 無法 checked 提交，session={} 將安全返還：{}", sessionUuid, describe(exception));
            return failedBatch();
        }
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
            LOGGER.debug("側門選擇在提交前拒絕，session={}：{}", sessionUuid, describe(exception));
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
     * 唯一的 candidate／selection 提交點。put 之後任何失敗都先以 CAS 還原未 checked authority（或隔離），
     * 確保之後其他 session 的 flush、原生 autosave 或 stop 都不能承認已回報失敗的 ledger／SELECTED。
     */
    private static SessionRecoveryRecord commitChecked(JournalPort journal, SessionRecoveryRecord previous, SessionRecoveryRecord next) {
        if (!SessionRecoveryRecord.sameAuthority(previous, next)) throw new IllegalArgumentException("候選 authority 非單調更新");
        requireExpectedCurrent(journal, previous);
        try {
            journal.put(next);
            journal.flush();
            var actual = journal.flushedRecords().get(next.sessionUuid());
            if (!next.equals(actual)) throw new IllegalStateException("候選 journal exact readback 不符");
            return actual;
        } catch (RuntimeException failure) {
            boolean clean;
            try { clean = journal.abortUnchecked(next); }
            catch (RuntimeException abortFailure) { failure.addSuppressed(abortFailure); clean = false; }
            LOGGER.warn("候選 checked 提交失敗，session={}，{}：{}", next.sessionUuid(),
                    clean ? "未 checked authority 已還原為上一份 flushed authority" : "未能還原，已隔離該 session 的未 checked authority",
                    describe(failure));
            throw new CommitFailure(failure);
        }
    }

    /** 只輸出例外 class 與 message（含 root cause）；不輸出 record、candidate bytes 或 entropy。 */
    private static String describe(Throwable failure) {
        var text = failure.getClass().getName() + ": " + failure.getMessage();
        var root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root == failure ? text : text + " <- " + root.getClass().getName() + ": " + root.getMessage();
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
        };
    }
    private static void requireServerThread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("候選服務必須在伺服器執行緒操作");
    }
}
