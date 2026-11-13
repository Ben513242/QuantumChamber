package dev.quantumchamber.persistence;

import java.io.*;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockBox;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import static dev.quantumchamber.persistence.HomeSessionRecord.*;
import static dev.quantumchamber.superposition.SessionState.*;

/** 量測整檔與每個 SID 的可達峰值；一律使用原生有界 NBT 計量。 */
public final class HomeJournalBudget {
    private HomeJournalBudget() {}
    /** 僅由已知大小上限觸發；嚴格內容、深度與 IO 問題一律交原本錯誤路徑。 */
    public static final class CapacityExceeded extends IllegalArgumentException {
        private CapacityExceeded(String message) { super(message); }
    }
    private static long measure(NbtCompound encoded) {
        try {
            var bytes=new ByteArrayOutputStream(); NbtIo.writeCompressed(encoded,bytes);
            var tracker=new NbtSizeTracker(SessionJournalStore.SCHEMA_FIVE_BUDGET,512) {
                @Override public void add(long bytes) {
                    try { super.add(bytes); }
                    catch(NbtSizeValidationException size) { throw new CapacityExceeded("HOME NBT 配置量超過 16 MiB"); }
                }
            };
            NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()),tracker);
            return tracker.getAllocatedBytes();
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    public static void requireReserved(NbtCompound wrapped) {
        long total=measure(wrapped);
        for(var raw:JournalNbt.compounds(JournalNbt.compound(wrapped,"data"),"Records",6)) {
            var n=(NbtCompound)raw;
            if(!n.contains("RecordVersion")) continue;
            var actual=HomeJournalCodec.decode(n);
            var compact=compact(actual);
            long base=measure(HomeJournalCodec.encode(compact));
            var peaks=peaks(compact);
            long growth=peaks.hot-base;
            total=Math.addExact(total,growth);
            if(total>SessionJournalStore.SCHEMA_FIVE_BUDGET) throw new CapacityExceeded("HOME 熱 journal 完成預留不足");
            // 固定來源快照的差額只計一次；wrapper 與 NBT list 祖先各自實測。
            if(peaks.receipt>0 && measure(n)-base+peaks.receipt>SessionJournalStore.SCHEMA_FIVE_BUDGET)
                throw new CapacityExceeded("HOME 單 SID 最壞收據超限");
        }
    }
    static long remaining(HomeSessionRecord row) {
        var compact=compact(row);
        return peaks(compact).hot-measure(HomeJournalCodec.encode(compact));
    }
    private static HomeSessionRecord compact(HomeSessionRecord r) {
        var people=r.participants().stream().map(p -> new SessionRecoveryRecord.Participant(p.playerUuid(),
                p.sourcePosition(),p.sourceVelocity(),p.yaw(),p.pitch(),new NbtCompound(),p.returned())).toList();
        return new HomeSessionRecord(r.sessionUuid(),r.chamberUuid(),r.rootAnchor(),r.entryEndpoint(),people,r.spaceLeases(),r.state(),r.home());
    }
    private static final class Peaks {
        long hot,receipt;
        void add(HomeSessionRecord r) {
            hot=Math.max(hot,measure(HomeJournalCodec.encode(r)));
            if(r.home().closeIntent() instanceof Close) receipt=Math.max(receipt,measure(HomeReceiptStore.wrapper(r)));
        }
    }
    private static Peaks peaks(HomeSessionRecord r) {
        var result=new Peaks(); result.add(r);
        if(r.home().closeIntent() instanceof Close || r.state()==RETURNING
                || r.state()==SEALED && r.home().sealedReason().orElseThrow()!=Seal.AUTHORITY_UNREADABLE) return result;
        var cohort=r.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList();
        var selection=new Selected(new DoorKey(r.sessionUuid(),1,DoorKey.DoorWallSide.POSITIVE_LATERAL),cohort.getFirst(),0);
        var leases=r.spaceLeases();
        if(r.home().selection() instanceof Unselected) {
            var slots=new ArrayList<SessionRecoveryRecord.SpaceLease>();
            for(int i=0;i<64;i++) slots.add(new SessionRecoveryRecord.SpaceLease(i,new BlockBox(i*192,64,0,i*192+6,70,6)));
            leases=List.copyOf(slots);
        }
        var returned=r.participants().stream().map(p -> new SessionRecoveryRecord.Participant(p.playerUuid(),p.sourcePosition(),
                p.sourceVelocity(),p.yaw(),p.pitch(),p.quantumStateSnapshot(),true)).toList();
        var root=RootReason.ROOT_INVALID_STRUCTURE;
        // 持租約時，停止原因與 COLLAPSING 狀態字串都可能同時增長（包含固定 32 格）。
        if(r.state()==ARMING || r.state()==SUPERPOSITION || r.state()==MEASURED || r.state()==COLLAPSING) {
            var held=new Home(selection,0,-1,-1,List.of(),List.of(),Optional.of(root),0,new None(),Optional.empty());
            result.add(row(r,r.participants(),leases,COLLAPSING,held));
        }
        // 清租約後的上界按合法形狀取最大值，不能將 TRR 與全員 Crossed 拼在同一列。
        for(var reason:CloseReason.values()) {
            boolean stopped=reason.name().startsWith("ROOT_");
            var stop=stopped ? Optional.of(RootReason.valueOf(reason.name())) : Optional.<RootReason>empty();
            var ev=switch(reason) {
                case ALL_CROSSED -> new AllCrossed();
                case EXPIRED -> new Expired(1200);
                case ENTRY_STOPPED -> new EntryStopped(Signal.DISABLED,0);
                case PARTICIPANT_CLOSED -> new ParticipantClosed(cohort.getFirst(),Phase.PASSAGE,0);
                case RESTART_CLOSED -> new RestartClosed(r.sessionUuid(),0);
                default -> new RootStop(stop.orElseThrow(),0);
            };
            var goal=reason==CloseReason.PARTICIPANT_CLOSED ? DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN : DoorGoal.OPEN;
            var close=new Close(reason,1200,goal,ev);
            var h=new Home(selection,0,0,1200,cohort,List.of(),stop,stopped?0:-1,close,Optional.empty());
            result.add(row(r,returned,List.of(),PASSAGE_READY,h));
            if(stopped || reason==CloseReason.RESTART_CLOSED) result.add(row(r,returned,List.of(),SEALED,
                    new Home(selection,0,0,1200,cohort,List.of(),stop,stopped?0:-1,close,Optional.of(Seal.AUTHORITY_UNREADABLE))));
        }
        for(var seal:Seal.values()) {
            boolean trr=seal==Seal.TRANSFER_RECOVERY_REQUIRED;
            var crossed=trr?cohort.subList(0,cohort.size()-1):cohort;
            var recovery=trr?List.of(cohort.getLast()):List.<UUID>of();
            var stop=seal==Seal.AUTHORITY_UNREADABLE ? Optional.of(root) : Optional.<RootReason>empty();
            result.add(row(r,returned,List.of(),SEALED,new Home(selection,0,0,1200,crossed,recovery,stop,stop.isPresent()?0:-1,new None(),Optional.of(seal))));
        }
        return result;
    }
    private static HomeSessionRecord row(HomeSessionRecord r,List<SessionRecoveryRecord.Participant> people,
            List<SessionRecoveryRecord.SpaceLease> leases,SessionState state,Home h) {
        return new HomeSessionRecord(r.sessionUuid(),r.chamberUuid(),r.rootAnchor(),r.entryEndpoint(),people,leases,state,h);
    }
}
