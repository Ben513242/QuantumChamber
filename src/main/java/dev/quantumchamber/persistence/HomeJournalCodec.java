package dev.quantumchamber.persistence;

import static dev.quantumchamber.persistence.HomeSessionRecord.*;
import static dev.quantumchamber.persistence.JournalNbt.*;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.*;

final class HomeJournalCodec {
    private HomeJournalCodec() {}

    static NbtCompound encode(HomeSessionRecord r) {
        var n = new NbtCompound();
        n.putInt("RecordVersion",2); n.putUuid("SessionUuid",r.sessionUuid()); n.putUuid("ChamberUuid",r.chamberUuid());
        var a = r.rootAnchor(); var b = r.entryEndpoint();
        n.put("RootAnchor", SessionRecoveryRecord.encodeOrigin(a));
        var entry = SessionRecoveryRecord.encodeOrigin(new ChamberOriginAuthority(b.chamberUuid(), b.worldKey(),
                b.role(), b.controllerPos(), b.facing(), b.instanceKind()));
        entry.putUuid("UniverseId", b.universeId().value()); n.put("EntryEndpoint",entry);
        n.put("Participants",SessionRecoveryRecord.encodePeople(r.participants()));
        n.put("SpaceLeases",SessionRecoveryRecord.encodeLeases(r.spaceLeases()));
        n.putString("State",r.state().name()); n.putByte("RestoreEntryEffectOnReturn",(byte)0);
        n.putString("SessionSemantics","LATERAL_BUFF_MAINTAINED"); n.putInt("TravelCapabilityVersion",1);
        var h = r.home(); var fields = new NbtCompound();
        fields.putInt("HomeRevision",1); fields.put("Selection",selection(h.selection()));
        fields.putLong("CommittedAtGameTime",h.committedAtGameTime()); fields.putLong("PassageOpenedAtGameTime",h.passageOpenedAtGameTime());
        fields.putLong("PassageExpiresAtGameTime",h.passageExpiresAtGameTime()); fields.put("Crossed",ids(h.crossed()));
        fields.put("RecoveryRequired",ids(h.recoveryRequired())); fields.putString("StopReason",h.stopReason().map(Enum::name).orElse(""));
        fields.putLong("StoppedAtGameTime",h.stoppedAtGameTime()); fields.put("CloseIntent",close(h.closeIntent()));
        fields.putString("SealedReason",h.sealedReason().map(Enum::name).orElse("")); n.put("Home",fields);
        return n;
    }

    static HomeSessionRecord decode(NbtCompound n) {
        keys(n,"RecordVersion","SessionUuid","ChamberUuid","RootAnchor","EntryEndpoint","Participants","SpaceLeases",
                "State","RestoreEntryEffectOnReturn","SessionSemantics","TravelCapabilityVersion","Home");
        fixed(n,"RecordVersion",2); fixed(n,"TravelCapabilityVersion",1); requireType(n,"RestoreEntryEffectOnReturn",1);
        check(n.getByte("RestoreEntryEffectOnReturn")==0 && string(n,"SessionSemantics").equals("LATERAL_BUFF_MAINTAINED"),"藥效策略");
        var root = compound(n,"RootAnchor"); var entry = compound(n,"EntryEndpoint");
        keys(root,"ChamberUuid","WorldKey","Role","ControllerPos","Facing","InstanceKind");
        keys(entry,"ChamberUuid","UniverseId","WorldKey","Role","ControllerPos","Facing","InstanceKind");
        var a = SessionRecoveryRecord.decodeOrigin(root,true); var b = SessionRecoveryRecord.decodeOrigin(entry,true);
        var endpoint = new EntryEndpoint(b.chamberUuid(),UniverseId.of(uuid(entry,"UniverseId")),b.worldKey(),b.role(),b.controllerPos(),b.facing(),b.instanceKind());
        var h = compound(n,"Home");
        keys(h,"HomeRevision","Selection","CommittedAtGameTime","PassageOpenedAtGameTime","PassageExpiresAtGameTime","Crossed",
                "RecoveryRequired","StopReason","StoppedAtGameTime","CloseIntent","SealedReason");
        fixed(h,"HomeRevision",1);
        return new HomeSessionRecord(uuid(n,"SessionUuid"),uuid(n,"ChamberUuid"),a,endpoint,SessionRecoveryRecord.decodePeople(n,6),
                SessionRecoveryRecord.decodeLeases(n,6),SessionState.valueOf(string(n,"State")),new Home(selection(compound(h,"Selection")),
                longValue(h,"CommittedAtGameTime"),longValue(h,"PassageOpenedAtGameTime"),longValue(h,"PassageExpiresAtGameTime"),ids(h,"Crossed"),
                ids(h,"RecoveryRequired"),optional(h,"StopReason",RootReason.class),longValue(h,"StoppedAtGameTime"),close(compound(h,"CloseIntent")),
                optional(h,"SealedReason",Seal.class)));
    }
    private static <E extends Enum<E>> Optional<E> optional(NbtCompound n,String key,Class<E> type) {
        String s = string(n,key); return s.isEmpty() ? Optional.empty() : Optional.of(Enum.valueOf(type,s));
    }
    static void fixed(NbtCompound n,String key,int value) { requireType(n,key,3); check(n.getInt(key)==value,key); }
    static NbtCompound kind(String k) { var n=new NbtCompound(); n.putString("Kind",k); return n; }
    private static NbtList ids(List<UUID> values) {
        var list = new NbtList(); values.forEach(v -> list.add(NbtHelper.fromUuid(v))); return list;
    }
    private static List<UUID> ids(NbtCompound n,String key) {
        requireType(n,key,9); var list=(NbtList)n.get(key);
        check(list.isEmpty() ? list.getHeldType()==0 : list.getHeldType()==11,key);
        return list.stream().map(NbtHelper::toUuid).toList();
    }
    private static Selection selection(NbtCompound n) {
        if (string(n,"Kind").equals("UNSELECTED")) { keys(n,"Kind"); return new Unselected(); }
        keys(n,"Kind","DoorKey","SelectedBy","SelectedAtGameTime"); check(string(n,"Kind").equals("HOME"),"選擇");
        var d=compound(n,"DoorKey"); keys(d,"SessionUuid","LogicalStationIndex","WallSide");
        return new Selected(new DoorKey(uuid(d,"SessionUuid"),longValue(d,"LogicalStationIndex"),DoorKey.DoorWallSide.valueOf(string(d,"WallSide"))),
                uuid(n,"SelectedBy"),longValue(n,"SelectedAtGameTime"));
    }
    private static NbtCompound selection(Selection sel) {
        if (sel instanceof Unselected) return kind("UNSELECTED");
        var s=(Selected)sel; var n=kind("HOME"); var d=new NbtCompound();
        d.putUuid("SessionUuid",s.doorKey().sessionUuid()); d.putLong("LogicalStationIndex",s.doorKey().logicalStationIndex());
        d.putString("WallSide",s.doorKey().wallSide().name()); n.put("DoorKey",d); n.putUuid("SelectedBy",s.selectedBy());
        n.putLong("SelectedAtGameTime",s.selectedAtGameTime()); return n;
    }
    static NbtCompound close(CloseIntent intent) {
        if (intent instanceof None) return kind("NONE");
        var c=(Close)intent; var n=kind("CLOSE"); n.putInt("CloseRevision",1); n.putString("CloseReason",c.reason().name());
        n.putLong("ClosedAtGameTime",c.closedAtGameTime()); n.putString("DoorGoal",c.doorGoal().name());
        var e=kind(c.evidence() instanceof RootStop ? "ROOT_STOP" : c.reason().name());
        switch(c.evidence()) {
            case AllCrossed ignored -> {}
            case Expired v -> e.putLong("ObservedAtGameTime",v.observedAtGameTime());
            case EntryStopped v -> { e.putString("Signal",v.signal().name()); e.putLong("ObservedAtGameTime",v.observedAtGameTime()); }
            case ParticipantClosed v -> { e.putUuid("Actor",v.actor()); e.putString("Phase",v.phase().name()); e.putLong("ObservedAtGameTime",v.observedAtGameTime()); }
            case RestartClosed v -> { e.putUuid("StartupInstanceUuid",v.startupInstanceUuid()); e.putLong("ObservedAtGameTime",v.observedAtGameTime()); }
            case RootStop v -> { e.putString("Reason",v.reason().name()); e.putLong("ObservedAtGameTime",v.observedAtGameTime()); }
        }
        n.put("Evidence",e); return n;
    }
    static CloseIntent close(NbtCompound n) {
        if (string(n,"Kind").equals("NONE")) { keys(n,"Kind"); return new None(); }
        keys(n,"Kind","CloseRevision","CloseReason","ClosedAtGameTime","DoorGoal","Evidence");
        check(string(n,"Kind").equals("CLOSE"),"結案類型"); fixed(n,"CloseRevision",1); var e=compound(n,"Evidence");
        Evidence evidence=switch(string(e,"Kind")) {
            case "ALL_CROSSED" -> { keys(e,"Kind"); yield new AllCrossed(); }
            case "EXPIRED" -> { keys(e,"Kind","ObservedAtGameTime"); yield new Expired(longValue(e,"ObservedAtGameTime")); }
            case "PARTICIPANT_CLOSED" -> { keys(e,"Kind","Actor","Phase","ObservedAtGameTime"); yield new ParticipantClosed(uuid(e,"Actor"),Phase.valueOf(string(e,"Phase")),longValue(e,"ObservedAtGameTime")); }
            case "ENTRY_STOPPED" -> { keys(e,"Kind","Signal","ObservedAtGameTime"); yield new EntryStopped(Signal.valueOf(string(e,"Signal")),longValue(e,"ObservedAtGameTime")); }
            case "RESTART_CLOSED" -> { keys(e,"Kind","StartupInstanceUuid","ObservedAtGameTime"); yield new RestartClosed(uuid(e,"StartupInstanceUuid"),longValue(e,"ObservedAtGameTime")); }
            case "ROOT_STOP" -> { keys(e,"Kind","Reason","ObservedAtGameTime"); yield new RootStop(RootReason.valueOf(string(e,"Reason")),longValue(e,"ObservedAtGameTime")); }
            default -> throw new IllegalArgumentException("未知結案證據");
        };
        return new Close(CloseReason.valueOf(string(n,"CloseReason")), longValue(n,"ClosedAtGameTime"),DoorGoal.valueOf(string(n,"DoorGoal")),evidence);
    }
}
