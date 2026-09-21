package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.candidate.CandidateLedgerService.SelectionOutcome.*;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.corridor.DoorKey.DoorWallSide;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateLedgerServiceTest {
    private static final UUID SESSION = CandidateDerivationTest.SESSION;
    private static final UUID PLAYER = new UUID(0, 20);
    private static final UUID SECOND_PLAYER = new UUID(0, 21);
    private static final Runnable OWNER = () -> {};
    @TempDir Path root;
    private CandidateResolver resolver;
    private CandidatePolicySnapshot policy;
    private final CandidateLedgerService service = new CandidateLedgerService();

    @BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }
    @BeforeEach void prepare() throws Exception {
        var entropy = CandidateEntropyStateTest.fixedState(root, CandidateDerivationTest.SECRET);
        resolver = new CandidateResolver(entropy);
        policy = CandidateResolverTest.policy(entropy.entropyFingerprint(), 0,
                new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD));
    }

    @Test void batchDeduplicatesCanonicalKeysAndReturnsOnlyExactReadbackAfterOneFlush() {
        var port = port(List.of());
        var input = new ArrayList<>(List.of(key(1, true), key(-1), key(1), key(-1)));
        var result = commit(port, input);
        assertFalse(result.sessionFailed());
        assertEquals(List.of(key(-1), key(1), key(1, true)), result.committedKeys());
        assertEquals(1, port.puts); assertEquals(1, port.flushes); assertEquals(2, port.reads);
        assertEquals(result.committedKeys(), port.state.flushedRecords().get(SESSION).candidateLedger().stream()
                .map(CandidateLedgerEntry::doorKey).toList());
        assertEquals(List.of(key(1, true), key(-1), key(1), key(-1)), input);
        assertThrows(UnsupportedOperationException.class, () -> result.committedKeys().clear());
    }

    @Test void completeInputReusesDurableEntriesAndDefersOnlyNewKeysPast256() {
        var port = port(List.of());
        var input = java.util.stream.LongStream.range(0, 300).mapToObj(CandidateLedgerServiceTest::key).toList();
        var first = commit(port, input);
        assertFalse(first.sessionFailed()); assertEquals(input.subList(0, 256), first.committedKeys());
        var prefix = port.state.flushedRecords().get(SESSION).candidateLedger();
        var second = commit(port, input.reversed());
        assertFalse(second.sessionFailed()); assertEquals(input, second.committedKeys());
        assertEquals(prefix, port.state.flushedRecords().get(SESSION).candidateLedger().subList(0, 256));
        assertEquals(2, port.puts); assertEquals(2, port.flushes);
    }

    @Test void durableReuseAndEmptyInputNeedNoDerivationOrFlush() {
        var existing = entry(0); var port = port(List.of(existing));
        CandidateLedgerService.InputsLoader forbidden = () -> { throw new AssertionError("既有 candidate 不應重新解析"); };
        var reused = service.commitCandidates(port, OWNER, forbidden, SESSION, List.of(key(0), key(0)));
        assertFalse(reused.sessionFailed()); assertEquals(List.of(key(0)), reused.committedKeys());
        var empty = service.commitCandidates(port, OWNER, forbidden, SESSION, List.of());
        assertFalse(empty.sessionFailed()); assertTrue(empty.committedKeys().isEmpty());
        assertEquals(existing, port.state.flushedRecords().get(SESSION).candidateLedger().getFirst());
        assertEquals(0, port.puts); assertEquals(0, port.flushes);
    }

    @Test void batchDerivesUsingFrozenPolicyAndDiscoveryEligibility() {
        var port = port(List.of());
        var accepted = CandidateResolverTest.record(CandidateResolverTest.universe(1, 1), 0, true);
        var discovery = UniverseDiscoveryState.fromRecords(List.of(accepted,
                CandidateResolverTest.record(CandidateResolverTest.universe(1, 2), 1, true),
                CandidateResolverTest.record(CandidateResolverTest.universe(1, 3), 0, false)));
        var result = service.commitCandidates(port, OWNER, () -> new CandidateLedgerService.CandidateInputs(resolver, discovery),
                SESSION, List.of(key(5)));
        assertFalse(result.sessionFailed());
        var candidate = port.state.flushedRecords().get(SESSION).candidateLedger().getFirst().candidate();
        assertEquals(accepted.universeId(), assertInstanceOf(QuantumCandidate.Existing.class, candidate).universeId());
    }

    @Test void capExhaustionFailsWithoutWritingAndNeverAdvertisesPartialSuccess() {
        var entries = java.util.stream.LongStream.range(0, 16384).mapToObj(CandidateLedgerServiceTest::entry).toList();
        var port = port(entries);
        var reused = commit(port, List.of(key(0)));
        assertFalse(reused.sessionFailed()); assertEquals(List.of(key(0)), reused.committedKeys());
        var failed = commit(port, List.of(key(0), key(16384)));
        assertTrue(failed.sessionFailed()); assertTrue(failed.committedKeys().isEmpty());
        assertEquals(0, port.puts); assertEquals(0, port.flushes);
        var near = port(entries.subList(0, 16383));
        assertFalse(commit(near, List.of(key(16383))).sessionFailed());
        assertEquals(16384, near.state.flushedRecords().get(SESSION).candidateLedger().size());
    }

    @Test void resolverFailureOrNonAppendCanonicalInputFailsBeforeMutation() throws Exception {
        var port = port(List.of());
        var wrong = new CandidateResolver(CandidateEntropyStateTest.fixedState(root.resolve("other"), new byte[32]));
        var mismatch = service.commitCandidates(port, OWNER,
                () -> new CandidateLedgerService.CandidateInputs(wrong, UniverseDiscoveryState.fromRecords(List.of())),
                SESSION, List.of(key(0)));
        assertTrue(mismatch.sessionFailed()); assertTrue(mismatch.committedKeys().isEmpty()); assertEquals(0, port.puts);
        var existing = port(List.of(entry(1)));
        var reordered = commit(existing, List.of(key(0), key(1)));
        assertTrue(reordered.sessionFailed()); assertTrue(reordered.committedKeys().isEmpty());
        assertEquals(List.of(entry(1)), existing.state.flushedRecords().get(SESSION).candidateLedger());
        assertEquals(0, existing.flushes);
    }

    @Test void batchPutFlushAndEveryReadbackFaultReturnNoCommittedKeys() {
        for (var fault : Fault.values()) {
            if (fault == Fault.NONE) continue;
            var port = port(List.of(entry(-1))); port.fault = fault;
            var result = commit(port, List.of(key(-1), key(0)));
            assertTrue(result.sessionFailed(), fault.name()); assertTrue(result.committedKeys().isEmpty(), fault.name());
            assertEquals(1, port.puts); assertEquals(fault == Fault.PUT ? 0 : 1, port.flushes);
        }
    }

    @Test void dirtyCandidateIsNeitherReusedNorSelectableAndConflictingAuthorityFailsClosed() {
        var port = port(List.of());
        port.state.put(copy(port.initial, List.of(entry(0)), new CandidateSelection.Selectable(), SessionState.SUPERPOSITION));
        assertEquals(NOT_SELECTABLE, select(port, key(0), PLAYER, 0));
        var result = commit(port, List.of(key(0)));
        assertTrue(result.sessionFailed()); assertTrue(result.committedKeys().isEmpty());
        assertTrue(port.state.flushedRecords().get(SESSION).candidateLedger().isEmpty()); assertEquals(0, port.flushes);
    }

    @Test void firstParticipantSelectsAndSecondGetsAlreadySelectedWithExactStoredChoice() {
        var port = port(List.of(entry(0), entry(1)));
        assertEquals(SELECTED, select(port, key(0), PLAYER, 7));
        var selected = port.state.flushedRecords().get(SESSION);
        assertEquals(SessionState.MEASURED, selected.state());
        assertEquals(new CandidateSelection.Selected(key(0), entry(0).candidate().candidateId(), PLAYER, 7, 1),
                selected.candidateSelection().orElseThrow());
        assertEquals(ALREADY_SELECTED, select(port, key(1), SECOND_PLAYER, 7));
        assertEquals(selected, port.state.flushedRecords().get(SESSION));
        assertEquals(1, port.puts); assertEquals(1, port.flushes);
    }

    @Test void missingCandidateAndInvalidIdentityPlayerTimeAreRejectedWithoutMutation() {
        var port = port(List.of(entry(0)));
        assertEquals(NOT_SELECTABLE, select(port, key(1), PLAYER, 0));
        assertEquals(REJECTED, select(port, key(0), UUID.randomUUID(), 0));
        assertEquals(REJECTED, select(port, key(0), PLAYER, -1));
        assertEquals(REJECTED, select(port, new DoorKey(UUID.randomUUID(), 0, DoorWallSide.NEGATIVE_LATERAL), PLAYER, 0));
        assertEquals(REJECTED, service.trySelect(port, OWNER, UUID.randomUUID(), key(0), PLAYER, 0));
        assertEquals(REJECTED, select(port, null, PLAYER, 0));
        assertTrue(service.commitCandidates(port, OWNER, this::inputs, SESSION,
                List.of(new DoorKey(UUID.randomUUID(), 0, DoorWallSide.NEGATIVE_LATERAL))).sessionFailed());
        assertEquals(0, port.puts); assertEquals(0, port.flushes);
    }

    @Test void selectionPutFlushAndEveryReadbackFaultNeverReturnSelected() {
        for (var fault : Fault.values()) {
            if (fault == Fault.NONE) continue;
            var port = port(List.of(entry(0))); port.fault = fault;
            assertEquals(REJECTED, select(port, key(0), PLAYER, 0), fault.name());
            assertEquals(1, port.puts); assertEquals(fault == Fault.PUT ? 0 : 1, port.flushes);
        }
    }

    @Test void failedFlushLeavesDirtySelectionButNextInteractionCannotTreatItAsSelected() {
        var port = port(List.of(entry(0), entry(1))); port.fault = Fault.FLUSH;
        assertEquals(REJECTED, select(port, key(0), PLAYER, 0));
        assertInstanceOf(CandidateSelection.Selected.class, port.state.records().get(SESSION).candidateSelection().orElseThrow());
        assertInstanceOf(CandidateSelection.Selectable.class, port.state.flushedRecords().get(SESSION).candidateSelection().orElseThrow());
        port.fault = Fault.NONE;
        assertEquals(REJECTED, select(port, key(1), SECOND_PLAYER, 1));
        assertEquals(1, port.flushes);
        assertInstanceOf(CandidateSelection.Selectable.class, port.state.flushedRecords().get(SESSION).candidateSelection().orElseThrow());
    }

    @Test void nonOwnerThreadAndUnhealthyReadRejectBeforeMutation() {
        var port = port(List.of(entry(0)));
        Runnable wrongThread = () -> { throw new IllegalStateException("非 owner thread"); };
        assertTrue(service.commitCandidates(port, wrongThread, this::inputs, SESSION, List.of(key(1))).sessionFailed());
        assertEquals(REJECTED, service.trySelect(port, wrongThread, SESSION, key(0), PLAYER, 0));
        assertEquals(0, port.reads); assertEquals(0, port.puts); assertEquals(0, port.flushes);
        port.failInitialRead = true;
        assertTrue(commit(port, List.of(key(1))).sessionFailed());
        assertEquals(REJECTED, select(port, key(0), PLAYER, 0));
    }

    private CandidateLedgerService.CandidateBatchResult commit(Port port, List<DoorKey> keys) {
        return service.commitCandidates(port, OWNER, this::inputs, SESSION, keys);
    }
    private CandidateLedgerService.SelectionOutcome select(Port port, DoorKey key, UUID player, long time) {
        return service.trySelect(port, OWNER, SESSION, key, player, time);
    }
    private CandidateLedgerService.CandidateInputs inputs() {
        return new CandidateLedgerService.CandidateInputs(resolver, UniverseDiscoveryState.fromRecords(List.of()));
    }
    private Port port(List<CandidateLedgerEntry> ledger) {
        return new Port(copy(fresh(), ledger, new CandidateSelection.Selectable(), SessionState.SUPERPOSITION));
    }
    private SessionRecoveryRecord fresh() {
        var chamber = new UUID(0, 10);
        var origin = new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                BlockPos.ORIGIN, Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var people = List.of(PLAYER, SECOND_PLAYER).stream().map(id -> new SessionRecoveryRecord.Participant(id,
                Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), false)).toList();
        return SessionRecoveryRecord.candidateAware(SESSION, chamber, origin, people,
                List.of(new SessionRecoveryRecord.SpaceLease(0, new BlockBox(0, 0, 0, 10, 10, 10))),
                SessionState.SUPERPOSITION, false, SessionSemantics.LATERAL_BUFF_MAINTAINED, policy);
    }
    private static SessionRecoveryRecord copy(SessionRecoveryRecord original, List<CandidateLedgerEntry> ledger,
            CandidateSelection selection, SessionState phase) {
        return new SessionRecoveryRecord(original.sessionUuid(), original.chamberUuid(), original.origin(), original.participants(),
                original.spaceLeases(), phase, false, original.semantics(), original.candidateContext(), ledger, Optional.of(selection));
    }
    private static DoorKey key(long station) { return key(station, false); }
    private static DoorKey key(long station, boolean positive) {
        return new DoorKey(SESSION, station, positive ? DoorWallSide.POSITIVE_LATERAL : DoorWallSide.NEGATIVE_LATERAL);
    }
    private static CandidateLedgerEntry entry(long station) {
        byte[] bytes = new byte[32]; ByteBuffer.wrap(bytes).putLong(station);
        return new CandidateLedgerEntry(key(station), new QuantumCandidate.Source(new CandidateId(new CandidateBytes(bytes))));
    }

    private enum Fault { NONE, PUT, FLUSH, READ_THROW, READ_STALE, READ_MISSING, READ_CHANGED }
    private final class Port implements CandidateLedgerService.JournalPort {
        private final SessionRecoveryState state;
        private final SessionRecoveryRecord initial;
        private final Path file;
        private int puts, flushes, reads;
        private boolean failInitialRead;
        private Fault fault = Fault.NONE;
        Port(SessionRecoveryRecord initial) {
            this.initial = initial; file = root.resolve(UUID.randomUUID() + ".dat");
            var seed = new SessionRecoveryState(); seed.put(initial);
            state = SessionRecoveryState.fromNbt(seed.writeNbt(new NbtCompound()));
        }
        @Override public Map<UUID, SessionRecoveryRecord> flushedRecords() {
            reads++;
            if (failInitialRead) throw new IllegalStateException("初次讀取失敗");
            if (flushes > 0) {
                if (fault == Fault.READ_THROW) throw new IllegalStateException("readback 失敗");
                if (fault == Fault.READ_STALE) return Map.of(SESSION, initial);
                if (fault == Fault.READ_MISSING) return Map.of();
                if (fault == Fault.READ_CHANGED) {
                    var actual = state.flushedRecords().get(SESSION);
                    var people = new ArrayList<>(actual.participants()); var first = people.getFirst();
                    people.set(0, new SessionRecoveryRecord.Participant(first.playerUuid(), first.sourcePosition(), first.sourceVelocity(),
                            first.yaw(), first.pitch(), first.quantumStateSnapshot(), true));
                    return Map.of(SESSION, new SessionRecoveryRecord(actual.sessionUuid(), actual.chamberUuid(), actual.origin(),
                            people, actual.spaceLeases(), actual.state(), false, actual.semantics(), actual.candidateContext(),
                            actual.candidateLedger(), actual.candidateSelection()));
                }
            }
            return state.flushedRecords();
        }
        @Override public void put(SessionRecoveryRecord record) {
            puts++;
            if (fault == Fault.PUT) throw new IllegalStateException("put 失敗");
            state.put(record);
        }
        @Override public void flush() {
            flushes++;
            if (fault == Fault.FLUSH) throw new IllegalStateException("flush 失敗");
            state.save(file.toFile(), null);
        }
    }

}
