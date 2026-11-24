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
        return admit(server,old.origin().controllerPos(),old.origin().facing(),null);
    }
    /**
     * T5-3：同 {@link #admit(MinecraftServer,SessionRecoveryRecord)}，但容忍一列活的 HOME 列 {@code live}（已 checked、current 與 flushed 相同、沒有未確認的寫入）：
     * padding 同時扣掉壓力列與 {@code live} 兩列的完成預留（{@code HomeJournalBudget.remaining}），admit 之後整份 journal 恰在 16 MiB 預留不變量的邊界上。
     * 壓力列的 Root 位置取 {@code live} 的 Root 旁邊（只供產生唯一身分，不對應真的艙）。
     */
    public static HomeSessionRecord admitBeside(MinecraftServer server,HomeSessionRecord live) {
        return admit(server,live.rootAnchor().controllerPos(),live.rootAnchor().facing(),Objects.requireNonNull(live,"live"));
    }
    private static HomeSessionRecord admit(MinecraftServer server,BlockPos near,Direction facing,HomeSessionRecord live) {
        var journal=SessionRecoveryState.get(server);
        requireNoPriorResidue(journal,live);
        var chamber=UUID.randomUUID(); var sid=UUID.randomUUID(); var u=UUID.randomUUID();
        var root=new ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                near.add(100,0,0),facing,ChamberInstanceKind.ORIGIN);
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
            long reserved=HomeJournalBudget.remaining(pressure)+(live==null ? 0 : HomeJournalBudget.remaining(live));
            int padding=Math.toIntExact(16L*1024*1024-tracker.getAllocatedBytes()-reserved);
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
     * <p>T5-3：{@code tolerated} 不為 null 時容忍這一列活的 HOME 列（它的預留另外扣掉），但它必須已 checked（current 與 flushed 都 exact 等於它）。
     */
    private static void requireNoPriorResidue(SessionRecoveryState journal,HomeSessionRecord tolerated) {
        var homes=new TreeSet<String>();
        for(var rows:List.of(journal.allSessions(),journal.allFlushedSessions()))
            rows.forEach((sid,row)->{ if(row instanceof HomeSessionRecord && (tolerated==null || !sid.equals(tolerated.sessionUuid()))) homes.add(sid+"/"+row.state()); });
        var pending=new TreeSet<String>(); journal.pendingSessions().keySet().forEach(sid->pending.add(sid.toString()));
        if(!homes.isEmpty() || !pending.isEmpty())
            throw new IllegalStateException("PRIOR_TEST_RESIDUE：共用 journal 有前一個 test 殘留的 HOME 列或未確認的寫入，容量 fixture 的前提不成立（不是容量問題）：home="
                    +homes+" pending="+pending);
        if(tolerated!=null && (!tolerated.equals(journal.allSessions().get(tolerated.sessionUuid()))
                || !tolerated.equals(journal.allFlushedSessions().get(tolerated.sessionUuid())) || journal.rollForwardPending(tolerated.sessionUuid())))
            throw new IllegalStateException("LIVE_HOME_NOT_CHECKED：容忍的活 HOME 列必須已 checked（current 與 flushed 都等於它）："+tolerated.sessionUuid());
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
    /** T5-3：壓力列仍以原樣（current 與 flushed 都 exact 等於它）留在 journal 時才 {@link #release}；已移除時不做事。供失敗路徑的冪等收尾。 */
    public static void releaseIfPresent(MinecraftServer server,HomeSessionRecord pressure) {
        var journal=SessionRecoveryState.get(server);
        if(pressure.equals(journal.allSessions().get(pressure.sessionUuid())) && pressure.equals(journal.allFlushedSessions().get(pressure.sessionUuid())))
            release(server,pressure);
    }
    /** T5-3：這一列的完成預留（{@code HomeJournalBudget.remaining}，package-private）；測試核對 CLOSE 空間沒有被侵占。 */
    public static long remaining(HomeSessionRecord row) { return HomeJournalBudget.remaining(row); }

    /**
     * T5-3（{@code formal_tick_capacity_pending_preserves_home_mapping}）：把已 checked 的壓力列在 journal 記憶體權威（current 與 flushed 同一份物件）裡換成 padding
     * 多 {@code extraBytes} 的版本，模擬 spec §5.1「共享預留不符」——合法 journal 下未選定 HOME 列的換頁已含在 admission 的 64 slot 上界內（每筆 lease 在實際量與剩餘預留間
     * 正負抵銷），不會自然出現容量拒絕。磁碟上的 journal 不變、不標 dirty；只有在沒有任何寫出的窗口內有效。回傳的還原動作把兩份 view 換回原物件；若期間有任何寫出
     * （物件被換掉或出現 pending），仍把內容等於膨脹版的 view 換回原列後丟出 {@code INFLATION_WINDOW_WRITTEN}。testmod 反射，收尾必須在任何 journal 寫出之前還原。
     */
    public static Runnable inflateInMemory(MinecraftServer server,HomeSessionRecord pressure,int extraBytes) {
        var journal=SessionRecoveryState.get(server);
        var sid=pressure.sessionUuid();
        if(!pressure.equals(journal.allSessions().get(sid)) || !pressure.equals(journal.allFlushedSessions().get(sid)) || !journal.pendingSessions().isEmpty())
            throw new IllegalStateException("INFLATION_PRECONDITION：壓力列必須已 checked、journal 沒有未確認的寫入");
        var first=pressure.participants().getFirst();
        var snapshot=first.quantumStateSnapshot();
        snapshot.putByteArray("Padding",new byte[snapshot.getByteArray("Padding").length+extraBytes]);
        var inflated=new HomeSessionRecord(sid,pressure.chamberUuid(),pressure.rootAnchor(),pressure.entryEndpoint(),
                List.of(person(first.playerUuid(),snapshot,false)),pressure.spaceLeases(),pressure.state(),pressure.home());
        @SuppressWarnings("unchecked") var records=(Map<UUID,JournalSession>)field(journal,"records");
        var originalCurrent=records.get(sid);
        @SuppressWarnings("unchecked") var originalFlushed=(Map<UUID,JournalSession>)field(journal,"flushed");
        records.put(sid,inflated);
        setField(journal,"flushed",replaced(originalFlushed,sid,inflated));
        return () -> {
            @SuppressWarnings("unchecked") var flushed=(Map<UUID,JournalSession>)field(journal,"flushed");
            boolean written=records.get(sid)!=inflated || flushed.get(sid)!=inflated || !journal.pendingSessions().isEmpty();
            // 期間有寫出時（只在失敗路徑），仍把內容等於膨脹版的 view 換回原壓力列，讓之後的 release 以原列 CAS 並覆寫磁碟；再丟出讓失敗可見。
            if(inflated.equals(records.get(sid))) records.put(sid,originalCurrent);
            if(inflated.equals(flushed.get(sid))) setField(journal,"flushed",replaced(flushed,sid,originalFlushed.get(sid)));
            if(written) throw new IllegalStateException("INFLATION_WINDOW_WRITTEN：膨脹窗口內出現 journal 寫出；記憶體權威已換回原壓力列，磁碟由之後的寫出覆寫："+sid);
        };
    }
    private static Map<UUID,JournalSession> replaced(Map<UUID,JournalSession> rows,UUID sid,JournalSession row) {
        var copy=new LinkedHashMap<>(rows); copy.put(sid,row); return Map.copyOf(copy);
    }
    private static Object field(Object target,String name) {
        try { var field=target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target); }
        catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    private static void setField(Object target,String name,Object value) {
        try { var field=target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target,value); }
        catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
