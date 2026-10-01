package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.CandidateId;
import dev.quantumchamber.candidate.CandidateLedgerEntry;
import dev.quantumchamber.candidate.CandidatePolicySnapshot;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.SourceFamilyRef;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.persistence.CollapseFields;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryRecord.Participant;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * F3a fix round 1（m-1）：{@code CorridorPageManager.sameLineage}——走廊 space 的快取 authority 與最新 flushed 之間的關係。單步的
 * sameAuthority 照舊；只有「最新 flushed 是 COLLAPSING、快取是 SUPERPOSITION／MEASURED／COLLAPSING」才放寬為可遞移的不變量。
 */
class CorridorPageManagerLineageTest {
    static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
    static final UUID CHAMBER = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UniverseId EXISTING_ID = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
    static final UniverseId NEW_ID = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000e2"));
    static final UniverseId OTHER_ID = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000e3"));
    /** ledger 的 canonical 順序：0＝SOURCE、1＝NEW、2＝EXISTING。 */
    static final int SOURCE = 0, NEW = 1, EXISTING = 2;

    private record Row(String label, SessionRecoveryRecord cached, SessionRecoveryRecord record, boolean accepted) {}

    @Test void lineageRelaxesOnlyMultiStepProgressIntoCollapsing() {
        var sup = superposition();
        var measured = measured(sup, EXISTING);
        var measuredReturnedA = returned(measured, A);
        var collapsing = collapsing(measured, 40, destination(EXISTING));
        var collapsingA = returned(collapsing, A);
        var settled = cleared(returned(collapsing, A, B));
        var newMeasured = measured(sup, NEW);
        var newCollapsing = collapsing(newMeasured, 40, new CollapseDestination.Universe(NEW_ID));
        var newSettled = cleared(returned(newCollapsing, A, B));
        var ready = newSettled.withCollapse(SessionState.PASSAGE_READY, newSettled.collapse().withPassage(100));
        var trr = ready.withCollapse(SessionState.SEALED, ready.collapse().transferRecoveryRequired(A));
        var sealed = newSettled.withCollapse(SessionState.SEALED, newSettled.collapse().sealed(SealedReason.PROJECTION_FAILED));
        var arming = withState(sup, SessionState.ARMING);
        var rows = List.of(
                // 接受：跨越多次合法寫入，但仍是同一條序列。
                new Row("SUP→M→C（選門後提交）", sup, collapsing, true),
                new Row("SUP→M→C→C（提交後又寫返還）", sup, collapsingA, true),
                new Row("M→C→C（返還）", measured, collapsingA, true),
                new Row("M(A 返還)→C→C（全員返還、清 lease）", measuredReturnedA, settled, true),
                new Row("C(返還)→C(清 lease)（單步）", collapsingA, settled, true),
                new Row("S→C 重試（單步 sameAuthority）", sealed, newCollapsing.withProgress(newSettled.participants(), List.of(),
                        SessionState.COLLAPSING, false), true),
                // 拒絕：不可遞移的欄位改變。
                new Row("Destination 改變", newCollapsing, collapsing(newMeasured, 40, new CollapseDestination.Universe(OTHER_ID)), false),
                new Row("CommittedAtGameTime 改變", collapsing, collapsing(measured, 41, destination(EXISTING)), false),
                new Row("selection 改變", measured, collapsing(measured(sup, SOURCE), 40, destination(SOURCE)), false),
                new Row("ledger 少了一筆", measured, withLedger(collapsing, List.of(sup.candidateLedger().get(EXISTING))), false),
                new Row("快取 SUP、ledger 少了一筆", sup, withLedger(collapsing, List.of(sup.candidateLedger().get(EXISTING))), false),
                new Row("returned 倒退", measuredReturnedA, collapsing, false),
                // 拒絕：放寬範圍之外。
                new Row("R→C", ready, newSettled, false),
                new Row("快取 SEALED(TRR)→C", trr, newSettled, false),
                new Row("M→…→PASSAGE_READY（最新不是 COLLAPSING）", newMeasured, ready, false),
                new Row("M→…→SEALED（最新不是 COLLAPSING）", newMeasured, sealed, false),
                new Row("快取 ARMING→C", arming, collapsing, false));
        var cases = new ArrayList<Executable>();
        for (var row : rows) cases.add(() -> assertEquals(row.accepted(), CorridorPageManager.sameLineage(row.cached(), row.record()), row.label()));
        // 對照：被放寬的接受列，單步 sameAuthority 本來不成立（確實需要放寬）；被拒絕的列，單步 sameAuthority 也不成立（不是靠第一行放行）。
        for (var row : rows) if (!row.label().contains("單步")) cases.add(() -> assertFalse(SessionRecoveryRecord.sameAuthority(row.cached(), row.record()),
                "單步 sameAuthority：" + row.label()));
        assertAll(cases);
    }

    // ---- fixtures（只用 public 建構子；與 persistence 測試的 schema 3 fixture 同形狀） ----

    static SessionRecoveryRecord superposition() {
        var origin = new ChamberOriginAuthority(CHAMBER, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, new BlockPos(7, 70, 9),
                Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var people = List.of(participant(A, 0), participant(B, 1));
        var context = new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"), 1, 1, 5, 20, 75, -1, 16384, bytes(41),
                new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD));
        var ledger = List.of(
                new CandidateLedgerEntry(new DoorKey(SESSION, -2, DoorKey.DoorWallSide.NEGATIVE_LATERAL), new QuantumCandidate.Source(id(1))),
                new CandidateLedgerEntry(new DoorKey(SESSION, -2, DoorKey.DoorWallSide.POSITIVE_LATERAL), new QuantumCandidate.New(id(3),
                        new NewUniverseIntent(bytes(21), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, bytes(31)))),
                new CandidateLedgerEntry(new DoorKey(SESSION, 5, DoorKey.DoorWallSide.NEGATIVE_LATERAL), new QuantumCandidate.Existing(id(2), EXISTING_ID)));
        return new SessionRecoveryRecord(SESSION, CHAMBER, origin, people, List.of(new SessionRecoveryRecord.SpaceLease(3, new BlockBox(0, 0, 0, 6, 6, 95))),
                SessionState.SUPERPOSITION, false, SessionSemantics.LATERAL_BUFF_MAINTAINED, Optional.of(context), ledger,
                Optional.of(new CandidateSelection.Selectable()));
    }
    static SessionRecoveryRecord measured(SessionRecoveryRecord sup, int choice) {
        var entry = sup.candidateLedger().get(choice);
        return new SessionRecoveryRecord(sup.sessionUuid(), sup.chamberUuid(), sup.origin(), sup.participants(), sup.spaceLeases(),
                SessionState.MEASURED, false, sup.semantics(), sup.candidateContext(), sup.candidateLedger(),
                Optional.of(new CandidateSelection.Selected(entry.doorKey(), entry.candidate().candidateId(), A, 0, 1)));
    }
    static CollapseDestination destination(int choice) {
        return switch (choice) {
            case SOURCE -> new CollapseDestination.Source();
            case NEW -> new CollapseDestination.Universe(NEW_ID);
            default -> new CollapseDestination.Universe(EXISTING_ID);
        };
    }
    static SessionRecoveryRecord collapsing(SessionRecoveryRecord measured, long committedAt, CollapseDestination destination) {
        return measured.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(committedAt, destination));
    }
    static SessionRecoveryRecord returned(SessionRecoveryRecord record, UUID... players) {
        var set = Set.of(players);
        var people = record.participants().stream().map(person -> set.contains(person.playerUuid()) ? new Participant(person.playerUuid(),
                person.sourcePosition(), person.sourceVelocity(), person.yaw(), person.pitch(), person.quantumStateSnapshot(), true) : person).toList();
        return record.withProgress(people, record.spaceLeases(), record.state(), record.restoreEntryEffectOnReturn());
    }
    static SessionRecoveryRecord cleared(SessionRecoveryRecord record) {
        return record.withProgress(record.participants(), List.of(), record.state(), record.restoreEntryEffectOnReturn());
    }
    static SessionRecoveryRecord withState(SessionRecoveryRecord record, SessionState state) {
        return record.withProgress(record.participants(), record.spaceLeases(), state, false);
    }
    static SessionRecoveryRecord withLedger(SessionRecoveryRecord record, List<CandidateLedgerEntry> ledger) {
        return new SessionRecoveryRecord(record.sessionUuid(), record.chamberUuid(), record.origin(), record.participants(), record.spaceLeases(),
                record.state(), record.restoreEntryEffectOnReturn(), record.semantics(), record.candidateContext(), ledger, record.candidateSelection(),
                record.collapse());
    }
    private static Participant participant(UUID player, int index) {
        return new Participant(player, new Vec3d(7.5 + index, 66, 9.5), Vec3d.ZERO, 90, -15, new NbtCompound(), false);
    }
    private static CandidateId id(int seed) { return new CandidateId(bytes(seed)); }
    private static CandidateBytes bytes(int seed) {
        byte[] result = new byte[32]; for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i); return new CandidateBytes(result);
    }
}
