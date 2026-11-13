package dev.quantumchamber.persistence;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.*;
import java.util.*;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import static dev.quantumchamber.superposition.SessionState.*;

/** 正規回程的不可變熱紀錄。所有欄位以同一套驗證服務 journal 與歷史。 */
public record HomeSessionRecord(UUID sessionUuid, UUID chamberUuid, ChamberOriginAuthority rootAnchor,
        EntryEndpoint entryEndpoint, List<SessionRecoveryRecord.Participant> participants,
        List<SessionRecoveryRecord.SpaceLease> spaceLeases, SessionState state, Home home) implements JournalSession {
    public record EntryEndpoint(UUID chamberUuid, UniverseId universeId, Identifier worldKey, DimensionRole role,
            BlockPos controllerPos, Direction facing, ChamberInstanceKind instanceKind) {
        public EntryEndpoint {
            Objects.requireNonNull(chamberUuid); Objects.requireNonNull(universeId); Objects.requireNonNull(worldKey);
            controllerPos = controllerPos.toImmutable();
            check(role == DimensionRole.OVERWORLD && instanceKind == ChamberInstanceKind.PROJECTION
                    && facing.getAxis().isHorizontal(), "投影端點");
        }
    }
    public sealed interface Selection permits Unselected, Selected {}
    public record Unselected() implements Selection {}
    public record Selected(DoorKey doorKey, UUID selectedBy, long selectedAtGameTime) implements Selection {}
    public enum RootReason { ROOT_DISABLED, ROOT_INVALID_STRUCTURE, ROOT_DESTROYED, ROOT_IDENTITY_MISMATCH }
    public enum Seal { ENTRY_AUTHORITY_INVALID, AUTHORITY_CORRUPT, AUTHORITY_UNREADABLE, TRANSFER_RECOVERY_REQUIRED }
    public enum CloseReason { ALL_CROSSED, EXPIRED, PARTICIPANT_CLOSED, ENTRY_STOPPED, RESTART_CLOSED,
        ROOT_DISABLED, ROOT_INVALID_STRUCTURE, ROOT_DESTROYED, ROOT_IDENTITY_MISMATCH }
    public enum DoorGoal { OPEN, CLOSE_IF_CLEAR_ELSE_OPEN }
    public enum Phase { PREPARE, PASSAGE }
    public enum Signal { LOW, DISABLED, INVALID }
    public sealed interface Evidence permits AllCrossed, Expired, ParticipantClosed, EntryStopped, RestartClosed, RootStop {}
    public record AllCrossed() implements Evidence {}
    public record Expired(long observedAtGameTime) implements Evidence {}
    public record ParticipantClosed(UUID actor, Phase phase, long observedAtGameTime) implements Evidence {}
    public record EntryStopped(Signal signal, long observedAtGameTime) implements Evidence {}
    public record RestartClosed(UUID startupInstanceUuid, long observedAtGameTime) implements Evidence {}
    public record RootStop(RootReason reason, long observedAtGameTime) implements Evidence {}
    public sealed interface CloseIntent permits None, Close {}
    public record None() implements CloseIntent {}
    public record Close(CloseReason reason, long closedAtGameTime, DoorGoal doorGoal, Evidence evidence) implements CloseIntent {}
    public record Home(Selection selection, long committedAtGameTime, long passageOpenedAtGameTime,
            long passageExpiresAtGameTime, List<UUID> crossed, List<UUID> recoveryRequired, Optional<RootReason> stopReason,
            long stoppedAtGameTime, CloseIntent closeIntent, Optional<Seal> sealedReason) {
        public Home {
            Objects.requireNonNull(selection); Objects.requireNonNull(stopReason);
            Objects.requireNonNull(closeIntent); Objects.requireNonNull(sealedReason);
            crossed = List.copyOf(crossed); recoveryRequired = List.copyOf(recoveryRequired);
        }
        public Home withClose(CloseIntent intent) {
            return new Home(selection, committedAtGameTime, passageOpenedAtGameTime, passageExpiresAtGameTime,
                    crossed, recoveryRequired, stopReason, stoppedAtGameTime, intent, sealedReason);
        }
    }

    public HomeSessionRecord {
        Objects.requireNonNull(sessionUuid); Objects.requireNonNull(chamberUuid); Objects.requireNonNull(state);
        check(rootAnchor.role() == DimensionRole.OVERWORLD && rootAnchor.worldKey().equals(World.OVERWORLD.getValue())
                && rootAnchor.instanceKind() == ChamberInstanceKind.ORIGIN && chamberUuid.equals(rootAnchor.chamberUuid())
                && chamberUuid.equals(entryEndpoint.chamberUuid()) && rootAnchor.facing() == entryEndpoint.facing()
                && rootAnchor.controllerPos().equals(entryEndpoint.controllerPos()), "來源與投影身分");
        participants = List.copyOf(participants); spaceLeases = List.copyOf(spaceLeases);
        Set<UUID> people = new HashSet<>();
        check(!participants.isEmpty() && participants.size() <= 25, "人數");
        for (var p : participants) check(people.add(p.playerUuid()), "重複玩家");
        Set<Integer> slots = new HashSet<>();
        check(spaceLeases.size() <= 64,"租約上限");
        for (var l : spaceLeases) check(slots.add(l.slotId()), "重複租約");
        dev.quantumchamber.corridor.SlotAllocator.validateSavedLeases(spaceLeases);
        boolean allReturned = participants.stream().allMatch(SessionRecoveryRecord.Participant::returned);
        check(!spaceLeases.isEmpty() || state.retainsReceipt() && allReturned, "空租約");
        check(state.retainsReceipt() == (home.selection() instanceof Selected), "選擇與狀態");
        if (home.selection() instanceof Selected s) {
            check(new DoorKey(sessionUuid, 1L, DoorKey.DoorWallSide.POSITIVE_LATERAL).equals(s.doorKey())
                    && people.contains(s.selectedBy()) && s.selectedAtGameTime() >= 0, "固定選門");
        }
        long selected = home.selection() instanceof Selected s ? s.selectedAtGameTime() : -1;
        boolean committed = state == COLLAPSING || state == PASSAGE_READY || state == SEALED;
        check(committed ? home.committedAtGameTime() >= selected : home.committedAtGameTime() == -1, "提交時間");
        boolean opened = home.passageOpenedAtGameTime() >= 0;
        check(opened ? (state == PASSAGE_READY || state == SEALED) && home.passageOpenedAtGameTime() >= home.committedAtGameTime()
                && home.passageExpiresAtGameTime() == Math.addExact(home.passageOpenedAtGameTime(), 1200)
                : home.passageOpenedAtGameTime() == -1 && home.passageExpiresAtGameTime() == -1 && state != PASSAGE_READY,
                "通道時間");
        check((state != PASSAGE_READY && state != SEALED) || allReturned && spaceLeases.isEmpty(), "未清理的通道");
        check(home.sealedReason().isPresent() == (state == SEALED), "封存原因");
        check(people.containsAll(home.crossed()) && new HashSet<>(home.crossed()).size() == home.crossed().size()
                && (opened || home.crossed().isEmpty()), "跨越者");
        boolean recovery = home.sealedReason().orElse(null) == Seal.TRANSFER_RECOVERY_REQUIRED;
        check(recovery ? opened && home.recoveryRequired().size() == 1 && people.contains(home.recoveryRequired().getFirst())
                && !home.crossed().contains(home.recoveryRequired().getFirst()) : home.recoveryRequired().isEmpty(), "移動恢復");
        check(home.stopReason().isPresent() ? selected >= 0 && home.stoppedAtGameTime() >= selected
                : home.stoppedAtGameTime() == -1, "停止時間");
        if (home.closeIntent() instanceof Close close) validateClose(state, home, people, allReturned && spaceLeases.isEmpty(), close);
    }

    private static void validateClose(SessionState state, Home h, Set<UUID> people, boolean cleaned, Close c) {
        check(cleaned && h.selection() instanceof Selected && h.recoveryRequired().isEmpty()
                && (state != SEALED || h.sealedReason().orElseThrow() == Seal.AUTHORITY_UNREADABLE), "結案來源");
        long closed = c.closedAtGameTime(), opened = h.passageOpenedAtGameTime(), committed = h.committedAtGameTime();
        check(closed >= ((Selected)h.selection()).selectedAtGameTime() && closed >= opened && closed >= committed, "結案時序");
        check(c.doorGoal() == (c.reason() == CloseReason.PARTICIPANT_CLOSED ? DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN : DoorGoal.OPEN), "結案門目標");
        boolean root = c.evidence() instanceof RootStop;
        check(root == h.stopReason().isPresent(), "結案停止原因");
        boolean valid = switch (c.reason()) {
            case ALL_CROSSED -> state == PASSAGE_READY && c.evidence() instanceof AllCrossed && h.crossed().size() == people.size();
            case EXPIRED -> state == PASSAGE_READY && c.evidence() instanceof Expired e
                    && between(e.observedAtGameTime(), h.passageExpiresAtGameTime(), closed);
            case PARTICIPANT_CLOSED -> c.evidence() instanceof ParticipantClosed e && people.contains(e.actor())
                    && ((state == COLLAPSING && e.phase() == Phase.PREPARE) || (state == PASSAGE_READY && e.phase() == Phase.PASSAGE))
                    && between(e.observedAtGameTime(), Math.max(committed, opened), closed);
            case ENTRY_STOPPED -> state == PASSAGE_READY && c.evidence() instanceof EntryStopped e
                    && e.signal() != null && between(e.observedAtGameTime(), opened, closed);
            case RESTART_CLOSED -> (state == PASSAGE_READY || state == SEALED) && opened >= 0
                    && c.evidence() instanceof RestartClosed e && e.startupInstanceUuid() != null
                    && between(e.observedAtGameTime(), opened, closed);
            case ROOT_DISABLED, ROOT_DESTROYED, ROOT_INVALID_STRUCTURE, ROOT_IDENTITY_MISMATCH ->
                    c.evidence() instanceof RootStop e && e.reason() == h.stopReason().orElse(null)
                    && c.reason().name().equals(e.reason().name()) && e.observedAtGameTime() == h.stoppedAtGameTime()
                    && closed >= h.stoppedAtGameTime();
        };
        check(valid, "結案原因與證據");
    }

    /** 只准單一欄位群或狀態邊前進；來源順序、已選內容及時間均沿用。 */
    static boolean edge(HomeSessionRecord a, HomeSessionRecord b, boolean restore) {
        if(!a.sessionUuid.equals(b.sessionUuid) || !a.chamberUuid.equals(b.chamberUuid)
                || !a.rootAnchor.equals(b.rootAnchor) || !a.entryEndpoint.equals(b.entryEndpoint)
                || !SessionRecoveryRecord.sameParticipantSources(a.participants,b.participants)
                || a.home.closeIntent instanceof Close) return false;
        var old=HomeJournalCodec.encode(a); var next=HomeJournalCodec.encode(b);
        if(old.equals(next)) return false;
        var changes=new HashSet<String>();
        for(var key:old.getKeys()) if(!key.equals("Home") && !old.get(key).equals(next.get(key))) changes.add(key);
        var from=old.getCompound("Home"); var to=next.getCompound("Home");
        for(var key:from.getKeys()) if(!from.get(key).equals(to.get(key))) changes.add(key);
        var h=a.home;
        if(a.state==b.state) {
            if(a.state==SEALED && h.sealedReason.orElseThrow()!=Seal.AUTHORITY_UNREADABLE) return false;
            if(restore) return false;
            if(changes.equals(Set.of("StopReason","StoppedAtGameTime")))
                return h.stopReason.isEmpty() && (a.state!=SEALED || h.sealedReason.orElseThrow()==Seal.AUTHORITY_UNREADABLE);
            if(changes.equals(Set.of("CloseIntent"))) return b.home.closeIntent instanceof Close;
            if(changes.equals(Set.of("SpaceLeases"))) {
                if(a.state==ARMING || a.state==SUPERPOSITION) return true;
                return b.spaceLeases.isEmpty() && (a.state==MEASURED || a.state==COLLAPSING);
            }
            if(changes.equals(Set.of("Participants"))) {
                if(a.state!=RETURNING && a.state!=MEASURED && a.state!=COLLAPSING) return false;
                int count=0;
                for(int i=0;i<a.participants.size();i++) {
                    var was=a.participants.get(i); var now=b.participants.get(i);
                    if(!was.playerUuid().equals(now.playerUuid()) || was.returned() && !now.returned()) return false;
                    if(!was.returned() && now.returned()) count++;
                }
                return count==1;
            }
            if(changes.equals(Set.of("Crossed"))) return a.state==PASSAGE_READY && h.stopReason.isEmpty()
                    && b.home.crossed.size()==h.crossed.size()+1 && b.home.crossed.subList(0,h.crossed.size()).equals(h.crossed);
            return false;
        }
        if(!restore) return b.state==SEALED && (a.state==COLLAPSING || a.state==PASSAGE_READY)
                && h.stopReason.isEmpty() && (changes.equals(Set.of("State","SealedReason"))
                || changes.equals(Set.of("State","SealedReason","RecoveryRequired")));
        if(h.stopReason.isPresent()) return false;
        return switch(a.state) {
            case ARMING -> (b.state==SUPERPOSITION || b.state==RETURNING) && changes.equals(Set.of("State"));
            case SUPERPOSITION -> b.state==RETURNING && changes.equals(Set.of("State"))
                    || b.state==MEASURED && changes.equals(Set.of("State","Selection"));
            case MEASURED -> b.state==COLLAPSING && changes.equals(Set.of("State","CommittedAtGameTime"));
            case COLLAPSING -> b.state==PASSAGE_READY && changes.equals(Set.of("State","PassageOpenedAtGameTime","PassageExpiresAtGameTime"));
            case SEALED -> b.state==COLLAPSING && h.sealedReason.orElseThrow()==Seal.AUTHORITY_UNREADABLE
                    && h.passageOpenedAtGameTime==-1 && changes.equals(Set.of("State","SealedReason"));
            default -> false;
        };
    }
    private static boolean between(long value, long min, long max) { return value >= min && value <= max; }
    static void check(boolean valid, String field) {
        if (!valid) throw new IllegalArgumentException("HOME 資料不合法：" + field);
    }
    public HomeSessionRecord withClose(CloseIntent intent) {
        return new HomeSessionRecord(sessionUuid, chamberUuid, rootAnchor, entryEndpoint, participants, spaceLeases, state, home.withClose(intent));
    }
}
