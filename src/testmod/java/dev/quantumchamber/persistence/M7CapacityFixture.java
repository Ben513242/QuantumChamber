package dev.quantumchamber.persistence;

import static dev.quantumchamber.persistence.HomeSessionRecord.*;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.*;
import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.*;
import net.minecraft.world.World;

/** 以正式 journal 寫入合法 HOME 列，產生已占滿預留容量的測試狀態。 */
public final class M7CapacityFixture {
    private M7CapacityFixture() {}
    public static HomeSessionRecord admit(MinecraftServer server,SessionRecoveryRecord old) {
        var journal=SessionRecoveryState.get(server);
        var a=old.origin();
        var chamber=UUID.randomUUID(); var sid=UUID.randomUUID(); var u=UUID.randomUUID();
        var root=new ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                a.controllerPos().add(100,0,0),a.facing(),ChamberInstanceKind.ORIGIN);
        var entry=new EntryEndpoint(chamber,UniverseId.of(u),Identifier.of("quantumchamber","universe/"+u+"/overworld"),
                DimensionRole.OVERWORLD,root.controllerPos(),root.facing(),ChamberInstanceKind.PROJECTION);
        var held=new SessionRecoveryRecord.SpaceLease(63,new BlockBox(900000,64,900000,900006,70,900006));
        var snapshot=new NbtCompound(); snapshot.putByteArray("Padding",new byte[0]); var player=UUID.randomUUID();
        var home=new Home(new Unselected(),-1,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var pressure=new HomeSessionRecord(sid,chamber,root,entry,List.of(person(player,snapshot,false)),List.of(held),SessionState.ARMING,home);
        var data=new NbtCompound();data.putInt("SchemaVersion",6);var rows=new NbtList();
        journal.allSessions().values().forEach(row->rows.add(row instanceof SessionRecoveryRecord r?r.toNbt(5):HomeJournalCodec.encode((HomeSessionRecord)row)));
        rows.add(HomeJournalCodec.encode(pressure));data.put("Records",rows);var wrapped=new NbtCompound();wrapped.put("data",data);NbtHelper.putDataVersion(wrapped);
        try {
            var out=new ByteArrayOutputStream();NbtIo.writeCompressed(wrapped,out);var tracker=NbtSizeTracker.of(16L*1024*1024);
            NbtIo.readCompressed(new ByteArrayInputStream(out.toByteArray()),tracker);
            int padding=Math.toIntExact(16L*1024*1024-tracker.getAllocatedBytes()-HomeJournalBudget.remaining(pressure));
            snapshot.putByteArray("Padding",new byte[padding]);
        } catch(IOException e) { throw new UncheckedIOException(e); }
        var actual=new HomeSessionRecord(sid,chamber,root,entry,List.of(person(player,snapshot,false)),List.of(held),SessionState.ARMING,home);
        if(!journal.admitHome(server,actual).committed()) throw new IllegalStateException("測試 HOME 預留未確認");
        return actual;
    }
    private static SessionRecoveryRecord.Participant person(UUID id,NbtCompound snap,boolean returned) {
        return new SessionRecoveryRecord.Participant(id,Vec3d.ZERO,Vec3d.ZERO,0,0,snap,returned);
    }
    public static void release(MinecraftServer server,HomeSessionRecord initial) {
        var journal=SessionRecoveryState.get(server);
        var returning=new HomeSessionRecord(initial.sessionUuid(),initial.chamberUuid(),initial.rootAnchor(),initial.entryEndpoint(),
                initial.participants(),initial.spaceLeases(),SessionState.RETURNING,initial.home());
        var first=initial.participants().getFirst();
        var done=new HomeSessionRecord(initial.sessionUuid(),initial.chamberUuid(),initial.rootAnchor(),initial.entryEndpoint(),
                List.of(person(first.playerUuid(),first.quantumStateSnapshot(),true)),initial.spaceLeases(),SessionState.RETURNING,initial.home());
        if(!journal.commitHomeRestore(server,initial,returning).committed() || !journal.commitHomeRollForward(server,returning,done).committed()
                || !journal.terminateHome(server,done).removed()) throw new IllegalStateException("測試 HOME 空間未釋出");
    }
}
