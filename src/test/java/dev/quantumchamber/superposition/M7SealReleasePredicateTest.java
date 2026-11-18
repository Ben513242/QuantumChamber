package dev.quantumchamber.superposition;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.CollapseFields;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.Seal;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.JournalSession;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryRecord.Participant;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * M7 T4A（spec v1.6 D）：「只剩永久封存」的判定（{@link SuperpositionSessionManager#permanentlySealedOnly(UUID, Map, Map, Map, Set, Predicate, boolean, Predicate)}）
 * 逐一覆蓋。只有該艙每一列都是已 checked 的永久原因 SEALED（舊列四種、HOME 三種），而且 current＝flushed、沒有未確認寫入、roll-forward、隔離、runtime、暫占或走廊
 * reservation 時為真。
 */
class M7SealReleasePredicateTest {
    private static final UUID CHAMBER = UUID.fromString("00000000-0000-0000-0000-0000000007c1");
    private static final UUID OTHER_CHAMBER = UUID.fromString("00000000-0000-0000-0000-0000000007c2");
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000007a1");
    private static final UniverseId UNIVERSE = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000007e1"));
    private static final BlockPos POS = new BlockPos(7, 70, 9);
    private static final Set<SealedReason> LEGACY_PERMANENT = Set.of(SealedReason.EXISTING_INELIGIBLE, SealedReason.AUTHORITY_CORRUPT,
            SealedReason.PROJECTION_CONFLICT, SealedReason.TRANSFER_RECOVERY_REQUIRED);
    private static final Set<Seal> HOME_PERMANENT = Set.of(Seal.ENTRY_AUTHORITY_INVALID, Seal.AUTHORITY_CORRUPT, Seal.TRANSFER_RECOVERY_REQUIRED);

    @Test void onlyCheckedPermanentRowsQualify() {
        var cases = new ArrayList<Executable>();
        int[] seed = {0};
        // 舊列四種與 HOME 三種永久原因：逐一為真。
        for (var reason : SealedReason.values()) {
            var row = sealed(sid(seed), CHAMBER, reason);
            boolean permanent = LEGACY_PERMANENT.contains(reason);
            cases.add(() -> assertEquals(permanent, Journal.of(row).qualifies(), "舊列 SEALED(" + reason + ")：" + (permanent ? "永久原因" : "可重試或 restart-only")));
        }
        for (var seal : Seal.values()) {
            var row = home(sid(seed), CHAMBER, seal);
            boolean permanent = HOME_PERMANENT.contains(seal);
            cases.add(() -> assertEquals(permanent, Journal.of(row).qualifies(), "HOME SEALED(" + seal + ")：" + (permanent ? "永久原因" : "restart-only")));
        }
        var permanent = sealed(sid(seed), CHAMBER, SealedReason.EXISTING_INELIGIBLE);
        // 不是 SEALED：DORMANT（全員返還、無租約的 MEASURED）、WAITING（COLLAPSING）、HOME 的 DORMANT。
        cases.add(() -> assertEquals(false, Journal.of(dormant(sid(seed), CHAMBER)).qualifies(), "DORMANT"));
        cases.add(() -> assertEquals(false, Journal.of(waiting(sid(seed), CHAMBER)).qualifies(), "WAITING（COLLAPSING）"));
        cases.add(() -> assertEquals(false, Journal.of(homeDormant(sid(seed), CHAMBER)).qualifies(), "HOME DORMANT"));
        // 未確認的寫入（任何一份送出中尚未確認的 attempt）。
        cases.add(() -> assertEquals(false, Journal.of(permanent).withPending(permanent).qualifies(), "pending 含這一列"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withPending(dormant(sid(seed), OTHER_CHAMBER)).qualifies(), "pending 只含其他艙的列"));
        // roll-forward 尚未確認、隔離（含連艙身分都無法確認的隔離列）。
        cases.add(() -> assertEquals(false, Journal.of(permanent).withRollForward(permanent.sessionUuid()).qualifies(), "roll-forward 尚未確認"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withQuarantine(permanent.sessionUuid()).qualifies(), "這一列隔離中"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withQuarantine(sid(seed)).qualifies(), "隔離中的未知列"));
        // current 與 flushed 不同：未 checked 的封存（current 已是 SEALED、flushed 仍是 COLLAPSING），或只剩 flushed、只剩 current。
        var flushedWaiting = waiting(permanent.sessionUuid(), CHAMBER);
        cases.add(() -> assertEquals(false, Journal.of(permanent).withFlushed(flushedWaiting).qualifies(), "未 checked 的 SEALED"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withoutCurrent(permanent.sessionUuid()).qualifies(), "只剩 flushed"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withoutFlushed(permanent.sessionUuid()).qualifies(), "只剩 current"));
        // 混有其他列：同艙另有非永久封存的列；同艙全是永久封存（舊列＋HOME）仍成立；其他艙的列不影響。
        cases.add(() -> assertEquals(false, Journal.of(permanent, dormant(sid(seed), CHAMBER)).qualifies(), "混有 DORMANT"));
        cases.add(() -> assertEquals(false, Journal.of(permanent, sealed(sid(seed), CHAMBER, SealedReason.PROJECTION_FAILED)).qualifies(), "混有可重試的 SEALED"));
        cases.add(() -> assertEquals(true, Journal.of(permanent, home(sid(seed), CHAMBER, Seal.AUTHORITY_CORRUPT)).qualifies(), "全是永久封存（舊列＋HOME）"));
        cases.add(() -> assertEquals(true, Journal.of(permanent, waiting(sid(seed), OTHER_CHAMBER)).qualifies(), "其他艙的列不影響"));
        // runtime、暫占、走廊 reservation。
        cases.add(() -> assertEquals(false, Journal.of(permanent).withRuntime().qualifies(), "有 runtime 或暫占"));
        cases.add(() -> assertEquals(false, Journal.of(permanent).withReservation(permanent.sessionUuid()).qualifies(), "有走廊 reservation"));
        // 沒有任何列：不是「只剩永久封存」。
        cases.add(() -> assertEquals(false, Journal.of().qualifies(), "沒有列"));
        cases.add(() -> assertEquals(false, Journal.of(waiting(sid(seed), OTHER_CHAMBER)).qualifies(), "只有其他艙的列"));
        assertAll(cases);
    }

    /** 判定的輸入（current、flushed、pending、隔離、roll-forward、runtime／暫占、reservation），預設 current＝flushed。 */
    private record Journal(Map<UUID, JournalSession> current, Map<UUID, JournalSession> flushed, Map<UUID, JournalSession> pending, Set<UUID> quarantined,
            Set<UUID> rollForward, boolean runtime, Set<UUID> reserved) {
        static Journal of(JournalSession... rows) {
            var map = new LinkedHashMap<UUID, JournalSession>();
            for (var row : rows) map.put(row.sessionUuid(), row);
            return new Journal(map, new LinkedHashMap<>(map), Map.of(), Set.of(), Set.of(), false, Set.of());
        }
        Journal withPending(JournalSession row) {
            var map = new LinkedHashMap<>(current); map.put(row.sessionUuid(), row);
            return new Journal(current, flushed, map, quarantined, rollForward, runtime, reserved);
        }
        Journal withFlushed(JournalSession row) {
            var map = new LinkedHashMap<>(flushed); map.put(row.sessionUuid(), row);
            return new Journal(current, map, pending, quarantined, rollForward, runtime, reserved);
        }
        Journal withoutCurrent(UUID sid) {
            var map = new LinkedHashMap<>(current); map.remove(sid);
            return new Journal(map, flushed, pending, quarantined, rollForward, runtime, reserved);
        }
        Journal withoutFlushed(UUID sid) {
            var map = new LinkedHashMap<>(flushed); map.remove(sid);
            return new Journal(current, map, pending, quarantined, rollForward, runtime, reserved);
        }
        Journal withQuarantine(UUID sid) { return new Journal(current, flushed, pending, Set.of(sid), rollForward, runtime, reserved); }
        Journal withRollForward(UUID sid) { return new Journal(current, flushed, pending, quarantined, Set.of(sid), runtime, reserved); }
        Journal withRuntime() { return new Journal(current, flushed, pending, quarantined, rollForward, true, reserved); }
        Journal withReservation(UUID sid) { return new Journal(current, flushed, pending, quarantined, rollForward, runtime, Set.of(sid)); }
        boolean qualifies() {
            return SuperpositionSessionManager.permanentlySealedOnly(CHAMBER, Map.copyOf(current), Map.copyOf(flushed), Map.copyOf(pending), quarantined,
                    rollForward::contains, runtime, reserved::contains);
        }
    }

    private static UUID sid(int[] seed) { return new UUID(0x7A, ++seed[0]); }

    private static ChamberOriginAuthority origin(UUID chamber) {
        return new ChamberOriginAuthority(chamber, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, POS, Direction.NORTH, ChamberInstanceKind.ORIGIN);
    }

    /** 已選 NEW、全員返還、無租約的 MEASURED（DORMANT）。 */
    private static SessionRecoveryRecord dormant(UUID sid, UUID chamber) {
        var people = List.of(new Participant(PLAYER, new Vec3d(7.5, 66, 9.5), Vec3d.ZERO, 0, 0, new NbtCompound(), true));
        var context = new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"), 1, 1, 5, 20, 75, -1, 16384, bytes(41),
                new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD));
        var key = new DoorKey(sid, -2, DoorKey.DoorWallSide.POSITIVE_LATERAL);
        var candidate = new QuantumCandidate.New(new CandidateId(bytes(3)), new NewUniverseIntent(bytes(21), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, bytes(31)));
        return new SessionRecoveryRecord(sid, chamber, origin(chamber), people, List.of(), dev.quantumchamber.superposition.SessionState.MEASURED, false,
                SessionSemantics.LATERAL_BUFF_MAINTAINED, Optional.of(context), List.of(new CandidateLedgerEntry(key, candidate)),
                Optional.of(new CandidateSelection.Selected(key, candidate.candidateId(), PLAYER, 0, 1)));
    }

    /** DORMANT 提交塌縮之後、閘門不成立的 COLLAPSING（WAITING）。 */
    private static SessionRecoveryRecord waiting(UUID sid, UUID chamber) {
        return dormant(sid, chamber).withCollapse(SessionState.COLLAPSING, CollapseFields.committed(40, new CollapseDestination.Universe(UNIVERSE)));
    }

    /** SEALED({@code reason})；TRANSFER_RECOVERY_REQUIRED 經 PASSAGE_READY 進入（RecoveryRequired 是參與者）。 */
    private static SessionRecoveryRecord sealed(UUID sid, UUID chamber, SealedReason reason) {
        var collapsing = waiting(sid, chamber);
        if (reason == SealedReason.TRANSFER_RECOVERY_REQUIRED) {
            var ready = collapsing.withCollapse(SessionState.PASSAGE_READY, collapsing.collapse().withPassage(50));
            return ready.withCollapse(SessionState.SEALED, ready.collapse().transferRecoveryRequired(PLAYER));
        }
        return collapsing.withCollapse(SessionState.SEALED, collapsing.collapse().sealed(reason));
    }

    private static HomeSessionRecord homeRow(UUID sid, UUID chamber, SessionState state, Home home) {
        var entry = new EntryEndpoint(chamber, UNIVERSE, Identifier.of("quantumchamber", "universe/" + UNIVERSE.value() + "/overworld"), DimensionRole.OVERWORLD,
                POS, Direction.NORTH, ChamberInstanceKind.PROJECTION);
        var people = List.of(new Participant(PLAYER, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), true));
        return new HomeSessionRecord(sid, chamber, origin(chamber), entry, people, List.of(), state, home);
    }

    /** HOME SEALED({@code seal})；TRANSFER_RECOVERY_REQUIRED 與 AUTHORITY_UNREADABLE 帶通道時間，前者另帶 RecoveryRequired。 */
    private static HomeSessionRecord home(UUID sid, UUID chamber, Seal seal) {
        boolean opened = seal == Seal.TRANSFER_RECOVERY_REQUIRED || seal == Seal.AUTHORITY_UNREADABLE;
        var selected = new Selected(new DoorKey(sid, 1, DoorKey.DoorWallSide.POSITIVE_LATERAL), PLAYER, 10);
        var home = new Home(selected, 20, opened ? 30 : -1, opened ? 1230 : -1, List.of(), seal == Seal.TRANSFER_RECOVERY_REQUIRED ? List.of(PLAYER) : List.of(),
                Optional.empty(), -1, new None(), Optional.of(seal));
        return homeRow(sid, chamber, SessionState.SEALED, home);
    }

    /** HOME 已選定、全員返回、無租約的 MEASURED。 */
    private static HomeSessionRecord homeDormant(UUID sid, UUID chamber) {
        var selected = new Selected(new DoorKey(sid, 1, DoorKey.DoorWallSide.POSITIVE_LATERAL), PLAYER, 10);
        return homeRow(sid, chamber, SessionState.MEASURED, new Home(selected, -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
    }

    private static CandidateBytes bytes(int seed) {
        byte[] result = new byte[32]; for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i); return new CandidateBytes(result);
    }
}
