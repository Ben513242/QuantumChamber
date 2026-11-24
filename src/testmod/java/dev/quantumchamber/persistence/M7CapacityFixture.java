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
        requireNoPriorResidue(journal);
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
    /**
     * T4 B2b-1（P2-M3 修法 1）：{@link #admit} 的 padding 只扣掉本列的完成預留（{@code HomeJournalBudget.remaining}），前提是 journal 裡沒有其他 HOME 列
     * （每一列 HOME 各自另有完成預留）、也沒有未確認的寫入（pending）。不成立時多半是前一個 test 失敗、沒收尾而留在共用 journal 的列：
     * 明確以 {@code PRIOR_TEST_RESIDUE} 標示，不讓它誤報成容量問題（「測試 HOME 預留未確認」）。只讀，不改 journal。
     */
    private static void requireNoPriorResidue(SessionRecoveryState journal) {
        var homes=new TreeSet<String>();
        for(var rows:List.of(journal.allSessions(),journal.allFlushedSessions()))
            rows.forEach((sid,row)->{ if(row instanceof HomeSessionRecord) homes.add(sid+"/"+row.state()); });
        var pending=new TreeSet<String>(); journal.pendingSessions().keySet().forEach(sid->pending.add(sid.toString()));
        if(!homes.isEmpty() || !pending.isEmpty())
            throw new IllegalStateException("PRIOR_TEST_RESIDUE：共用 journal 有前一個 test 殘留的 HOME 列或未確認的寫入，容量 fixture 的前提不成立（不是容量問題）：home="
                    +homes+" pending="+pending);
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
        // T5-2 起 public terminateHome 一律拒絕（未選定的返還只經走廊 owner 的 finishHomeReturn proof）。這列是沒有真 B、沒有走廊 space 的容量壓力列，
        // 由同 package 的 checked 移除本體收掉（與 JUnit 用的同一個本體），不經 HOME checkpoint。
        HomeReceiptStore store;
        try { store=new HomeReceiptStore(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
        if(!journal.commitHomeRestore(server,initial,returning).committed() || !journal.commitHomeRollForward(server,returning,done).committed()
                || !journal.terminateHome(store,() -> journal.flush(server),done).removed()) throw new IllegalStateException("測試 HOME 空間未釋出");
    }
}
