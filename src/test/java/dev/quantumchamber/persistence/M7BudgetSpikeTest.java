package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.corridor.SlotAllocator;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.minecraft.nbt.*;
import net.minecraft.util.math.BlockBox;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** T1 exact schema 的 test-only 計量 fixture；正式 HOME codec 由 T2 實作。 */
class M7BudgetSpikeTest {
    static final long LIMIT=16_777_216;
    static final UUID SID=new UUID(7,1), CHAMBER=new UUID(7,2), UNIVERSE=new UUID(7,3);
    @TempDir Path directory;
    @BeforeAll static void version(){net.minecraft.SharedConstants.createGameVersion();}

    @Test void legalHomeAtAndBeyondTrackerLimit() throws Exception {
        NbtCompound home=home(false,1,0,false,"",false);validateHome(home);
        NbtCompound old=SessionRecoverySchema3Test.record(SessionRecoverySchema3Test.fixture(false,false)).copy();
        var allocator=new SlotAllocator();allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();var reserved=allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();
        var lease=old.getList("SpaceLeases",10).getCompound(0);lease.putInt("SlotId",reserved.slotId());var box=reserved.bounds();lease.putIntArray("Bounds",new int[]{box.getMinX(),box.getMinY(),box.getMinZ(),box.getMaxX(),box.getMaxY(),box.getMaxZ()});
        old.getList("Participants",10).getCompound(0).put("SourcePosition",vector(7.5,66,12.5));
        SessionRecoveryRecord.fromNbt(old,3);
        long current=measure(wrap(List.of(home,old)));NbtCompound template=old.getList("CandidateLedger",10).getCompound(1).copy();
        appendCandidate(old,template,10);long growth=measure(wrap(List.of(home,old)))-current;
        int additions=(int)((LIMIT-current)/growth);
        for(int i=1;i<additions;i++)appendCandidate(old,template,10+i);
        SessionRecoveryRecord.fromNbt(old,3);
        NbtCompound below=wrap(List.of(home,old));long used=measure(below);
        assertTrue(used<=LIMIT);assertTrue(old.getList("CandidateLedger",10).size()<=16384);
        byte[] bytes=compressed(below);assertEquals(below,decode(bytes,LIMIT));scan(bytes);
        appendCandidate(old,template,10+additions);SessionRecoveryRecord.fromNbt(old,3);
        NbtCompound above=wrap(List.of(home,old));long next=measure(above);
        assertTrue(next>LIMIT);assertThrows(RuntimeException.class,()->decode(compressed(above),LIMIT));
        var tracker=NbtSizeTracker.of(LIMIT);tracker.add(LIMIT);assertThrows(RuntimeException.class,()->tracker.add(1));
        Files.write(directory.resolve("legal-below.dat"),bytes);Files.write(directory.resolve("legal-above.dat"),compressed(above));
        System.out.println("M7_BUDGET boundary below="+used+" above="+next+" quantum="+growth+" candidates="+old.getList("CandidateLedger",10).size()+" compressed="+bytes.length+" exactLimit="+LIMIT);
    }
    @Test void legacyAppendCannotSpendUnselectedRemapAndCloseReserve() throws Exception {
        NbtCompound unselected=home(false,1,0,false,"",false);
        long current=measure(wrap(List.of(unselected)));
        long maximum=current;String maximumName="unselected-64";
        List<NbtCompound> legal=new ArrayList<>();legal.add(home(false,64,0,false,"",false));
        for(String stop:List.of("","ROOT_DISABLED","ROOT_INVALID_STRUCTURE","ROOT_DESTROYED","ROOT_IDENTITY_MISMATCH")) {
            legal.add(home(true,64,0,false,stop,false));
            legal.add(home(true,0,25,false,stop,false));
            legal.add(home(true,0,24,true,"",false));
            legal.add(home(true,0,0,false,stop,true));
        }
        for(String state:List.of("ARMING","SUPERPOSITION","RETURNING")){var row=home(false,64,0,false,"",false);row.putString("State",state);legal.add(row);}
        for(String stop:List.of("","ROOT_DISABLED","ROOT_INVALID_STRUCTURE","ROOT_DESTROYED","ROOT_IDENTITY_MISMATCH")){
            var row=home(true,64,0,false,stop,false);row.putString("State","COLLAPSING");row.getCompound("Home").putLong("CommittedAtGameTime",20);legal.add(row);
        }
        for(String reason:List.of("ENTRY_AUTHORITY_INVALID","AUTHORITY_CORRUPT","AUTHORITY_UNREADABLE","TRANSFER_RECOVERY_REQUIRED")){
            var row=home(true,0,reason.equals("TRANSFER_RECOVERY_REQUIRED")?24:25,reason.equals("TRANSFER_RECOVERY_REQUIRED"),"",false);row.putString("State","SEALED");row.getCompound("Home").putString("SealedReason",reason);legal.add(row);
        }
        var closes=new ArrayList<NbtCompound>();
        for(String reason:List.of("ALL_CROSSED","EXPIRED","PARTICIPANT_CLOSED","ENTRY_STOPPED","RESTART_CLOSED","ROOT_DISABLED","ROOT_INVALID_STRUCTURE","ROOT_DESTROYED","ROOT_IDENTITY_MISMATCH")){
            var row=home(true,0,reason.equals("ALL_CROSSED")?25:24,false,reason.startsWith("ROOT_")?reason:"",true);
            var intent=row.getCompound("Home").getCompound("CloseIntent");intent.putString("CloseReason",reason);intent.putString("DoorGoal",reason.equals("PARTICIPANT_CLOSED")?"CLOSE_IF_CLEAR_ELSE_OPEN":"OPEN");
            NbtCompound evidence=kind(reason.startsWith("ROOT_")?"ROOT_STOP":reason);
            if(reason.startsWith("ROOT_")){evidence.putString("Reason",reason);evidence.putLong("ObservedAtGameTime",40);}
            else if(reason.equals("EXPIRED"))evidence.putLong("ObservedAtGameTime",1230);
            else if(reason.equals("PARTICIPANT_CLOSED")){evidence.putUuid("Actor",new UUID(7,100));evidence.putString("Phase","PASSAGE");evidence.putLong("ObservedAtGameTime",40);}
            else if(reason.equals("ENTRY_STOPPED")){evidence.putString("Signal","DISABLED");evidence.putLong("ObservedAtGameTime",40);}
            else if(reason.equals("RESTART_CLOSED")){evidence.putUuid("StartupInstanceUuid",new UUID(7,500));evidence.putLong("ObservedAtGameTime",40);}
            intent.put("Evidence",evidence);closes.add(row);legal.add(row);
        }
        var prepare=home(true,0,0,false,"",true);prepare.putString("State","COLLAPSING");prepare.getCompound("Home").putLong("PassageOpenedAtGameTime",-1);prepare.getCompound("Home").putLong("PassageExpiresAtGameTime",-1);prepare.getCompound("Home").getCompound("CloseIntent").getCompound("Evidence").putString("Phase","PREPARE");closes.add(prepare);legal.add(prepare);
        for(NbtCompound row:legal){validateHome(row);long amount=measure(wrap(List.of(row)));if(amount>maximum){maximum=amount;maximumName=row.getString("State")+"/"+row.getCompound("Home").getString("StopReason");}}
        long reserve=maximum-current;
        assertTrue(reserve>0);
        long leaseGrowth=measure(wrap(List.of(home(false,64,0,false,"",false))))-current;
        assertTrue(leaseGrowth>0,"尚未使用的 remap lease 仍須預留");
        NbtCompound competing=SessionRecoverySchema3Test.record(SessionRecoverySchema3Test.fixture(false,false)).copy();
        var competitorLease=competing.getList("SpaceLeases",10).getCompound(0);competitorLease.putInt("SlotId",1);competitorLease.putIntArray("Bounds",new int[]{1192,70,2000,1198,76,2006});
        NbtCompound template=competing.getList("CandidateLedger",10).getCompound(1).copy();long usage=measure(wrap(List.of(unselected,competing)));appendCandidate(competing,template,10);long step=measure(wrap(List.of(unselected,competing)))-usage;usage+=step;
        int index=11;while(usage+reserve<=LIMIT){appendCandidate(competing,template,index++);usage+=step;}
        SessionRecoveryRecord.fromNbt(competing,3);assertEquals(usage,measure(wrap(List.of(unselected,competing))));
        assertTrue(usage<=LIMIT,"合法競爭append仍能被同一有界tracker讀回");assertEquals(wrap(List.of(unselected,competing)),decode(compressed(wrap(List.of(unselected,competing))),LIMIT));
        assertFalse(usage+reserve<=LIMIT,"共用writer必須拒絕吃掉已入艙HOME的remap與close餘額");
        for(int fixed:List.of(1,32,64)) {
            long selected=measure(wrap(List.of(home(true,fixed,0,false,"",false))));
            long cleared=measure(wrap(List.of(home(true,0,0,false,"",false))));assertTrue(selected>=cleared);
        }
        NbtCompound receipt=new NbtCompound();receipt.putInt("SchemaVersion",1);receipt.putUuid("SessionUuid",SID);
        NbtCompound closed=home(true,0,0,false,"ROOT_IDENTITY_MISMATCH",true);
        receipt.put("CloseIntent",closed.getCompound("Home").getCompound("CloseIntent").copy());
        closed.getCompound("Home").put("CloseIntent",kind("NONE"));receipt.put("Session",closed);
        NbtCompound receiptWrapper=new NbtCompound();receiptWrapper.putInt("DataVersion",3953);receiptWrapper.put("data",receipt);
        long largestReceipt=0;for(var closing:closes){var data=new NbtCompound();data.putInt("SchemaVersion",1);data.putUuid("SessionUuid",SID);data.put("CloseIntent",closing.getCompound("Home").getCompound("CloseIntent").copy());var frozen=closing.copy();frozen.getCompound("Home").put("CloseIntent",kind("NONE"));data.put("Session",frozen);var wrapper=new NbtCompound();wrapper.putInt("DataVersion",3953);wrapper.put("data",data);long used=measure(wrapper);largestReceipt=Math.max(largestReceipt,used);assertTrue(used<LIMIT);assertEquals(wrapper,decode(compressed(wrapper),LIMIT));}
        System.out.println("M7_BUDGET legalShapes="+legal.size()+" closeShapes="+closes.size()+" largestReceipt="+largestReceipt+" competingUsage="+usage+" reserve="+reserve);
        assertTrue(measure(receiptWrapper)<LIMIT);assertEquals(receiptWrapper,decode(compressed(receiptWrapper),LIMIT));
        System.out.println("M7_BUDGET reserve current="+current+" maximum="+maximum+" remaining="+reserve+" remap="+leaseGrowth+" worst="+maximumName+" receipt="+measure(receiptWrapper));
    }
    @Test void sameBytesSnapshotDepthAndDuplicatePaths() throws Exception {
        NbtCompound value=wrap(List.of(home(false,64,0,false,"",false)));byte[] snapshot=compressed(value);
        Path path=directory.resolve("switch.dat");Files.write(path,snapshot);byte[] same=Files.readAllBytes(path);
        Files.write(path,new byte[]{0});assertEquals(value,decode(same,LIMIT));
        for(String duplicate:List.of("wrapper","data","inner")) {
            byte[] invalid=duplicate(duplicate);
            IOException failure=assertThrows(IOException.class,()->scan(invalid));
            assertTrue(failure.getMessage().contains("DUPLICATE $"),failure.toString());
        }
        int last=0;
        for(int depth=509;depth<=513;depth++) {
            NbtCompound root=new NbtCompound(), cursor=root;
            for(int i=0;i<depth;i++){NbtCompound next=new NbtCompound();cursor.put("n",next);cursor=next;}
            try{decode(compressed(root),LIMIT);last=depth;}catch(RuntimeException failure){assertTrue(depth>=512);}
        }
        assertEquals(511,last,"原生 tracker compound root 也占一層，512 個容器可讀");
        scan(snapshot);
    }
    static NbtCompound home(boolean selected,int leaseCount,int crossed,boolean recovery,String stop,boolean close) {
        NbtCompound row=new NbtCompound();row.putInt("RecordVersion",2);row.putUuid("SessionUuid",SID);row.putUuid("ChamberUuid",CHAMBER);
        NbtCompound root=new NbtCompound();root.putUuid("ChamberUuid",CHAMBER);root.putString("WorldKey","minecraft:overworld");root.putString("Role","OVERWORLD");
        root.putIntArray("ControllerPos",new int[]{7,70,9});root.putString("Facing","NORTH");root.putString("InstanceKind","ORIGIN");row.put("RootAnchor",root);
        NbtCompound entry=root.copy();entry.putUuid("UniverseId",UNIVERSE);entry.putString("WorldKey","quantumchamber:universe/"+UNIVERSE+"/overworld");entry.putString("InstanceKind","PROJECTION");row.put("EntryEndpoint",entry);
        NbtList people=new NbtList();
        for(int i=0;i<25;i++) {
            NbtCompound p=SessionRecoveryStateTest.fixture("SUPERPOSITION",false).getList("Records",10).getCompound(0).getList("Participants",10).getCompound(0).copy();
            p.putUuid("PlayerUuid",new UUID(7,100+i));p.put("SourcePosition",vector(5.5+i%5,66,10.5+i/5));
            p.putBoolean("Returned",selected&&leaseCount==0);people.add(p);
        }
        row.put("Participants",people);NbtList leases=new NbtList();SlotAllocator allocator=new SlotAllocator();
        for(int i=0;i<leaseCount;i++){var slot=allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();NbtCompound lease=new NbtCompound();lease.putInt("SlotId",slot.slotId());var b=slot.bounds();lease.putIntArray("Bounds",new int[]{b.getMinX(),b.getMinY(),b.getMinZ(),b.getMaxX(),b.getMaxY(),b.getMaxZ()});leases.add(lease);}
        row.put("SpaceLeases",leases);row.putString("State",!selected?"SUPERPOSITION":leaseCount>0?"MEASURED":recovery?"SEALED":"PASSAGE_READY");
        row.putBoolean("RestoreEntryEffectOnReturn",false);row.putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");row.putInt("TravelCapabilityVersion",1);
        NbtCompound h=new NbtCompound();h.putInt("HomeRevision",1);NbtCompound selection=kind(selected?"HOME":"UNSELECTED");
        if(selected){NbtCompound key=new NbtCompound();key.putUuid("SessionUuid",SID);key.putLong("LogicalStationIndex",1);key.putString("WallSide","POSITIVE_LATERAL");selection.put("DoorKey",key);selection.putUuid("SelectedBy",new UUID(7,100));selection.putLong("SelectedAtGameTime",10);}
        h.put("Selection",selection);long committed=selected&&leaseCount==0?20:-1;h.putLong("CommittedAtGameTime",committed);
        h.putLong("PassageOpenedAtGameTime",committed<0?-1:30);h.putLong("PassageExpiresAtGameTime",committed<0?-1:1230);
        NbtList crosses=new NbtList();for(int i=0;i<crossed;i++) crosses.add(net.minecraft.nbt.NbtHelper.fromUuid(new UUID(7,100+i)));h.put("Crossed",crosses);
        NbtList recoveries=new NbtList();if(recovery)recoveries.add(net.minecraft.nbt.NbtHelper.fromUuid(new UUID(7,124)));h.put("RecoveryRequired",recoveries);
        h.putString("StopReason",stop);h.putLong("StoppedAtGameTime",stop.isEmpty()?-1:40);h.putString("SealedReason",recovery?"TRANSFER_RECOVERY_REQUIRED":"");h.put("CloseIntent",kind("NONE"));
        if(close){NbtCompound intent=kind("CLOSE");intent.putInt("CloseRevision",1);intent.putString("CloseReason",stop.isEmpty()?"PARTICIPANT_CLOSED":stop);intent.putLong("ClosedAtGameTime",2000);intent.putString("DoorGoal",stop.isEmpty()?"CLOSE_IF_CLEAR_ELSE_OPEN":"OPEN");NbtCompound evidence=kind(stop.isEmpty()?"PARTICIPANT_CLOSED":"ROOT_STOP");evidence.putLong("ObservedAtGameTime",stop.isEmpty()?1999:40);if(stop.isEmpty()){evidence.putUuid("Actor",new UUID(7,100));evidence.putString("Phase","PASSAGE");}else evidence.putString("Reason",stop);intent.put("Evidence",evidence);h.put("CloseIntent",intent);}
        row.put("Home",h);return row;
    }
    static void validateHome(NbtCompound row) {
        JournalNbt.keys(row,"RecordVersion","SessionUuid","ChamberUuid","RootAnchor","EntryEndpoint","Participants","SpaceLeases","State","RestoreEntryEffectOnReturn","SessionSemantics","TravelCapabilityVersion","Home");
        assertEquals(25,row.getList("Participants",10).size());assertEquals(2,row.getInt("RecordVersion"));assertEquals(1,row.getInt("TravelCapabilityVersion"));
        Set<UUID> ids=new HashSet<>();for(var raw:row.getList("Participants",10)){var p=(NbtCompound)raw;assertTrue(ids.add(p.getUuid("PlayerUuid")));new SessionRecoveryRecord.Participant(p.getUuid("PlayerUuid"),new net.minecraft.util.math.Vec3d(p.getList("SourcePosition",6).getDouble(0),p.getList("SourcePosition",6).getDouble(1),p.getList("SourcePosition",6).getDouble(2)),net.minecraft.util.math.Vec3d.ZERO,p.getFloat("Yaw"),p.getFloat("Pitch"),p.getCompound("QuantumState"),p.getBoolean("Returned"));}
        NbtCompound h=row.getCompound("Home");JournalNbt.keys(h,"HomeRevision","Selection","CommittedAtGameTime","PassageOpenedAtGameTime","PassageExpiresAtGameTime","Crossed","RecoveryRequired","StopReason","StoppedAtGameTime","CloseIntent","SealedReason");
        Set<UUID> crossed=new HashSet<>();for(var id:h.getList("Crossed",11))assertTrue(ids.contains(NbtHelper.toUuid(id))&&crossed.add(NbtHelper.toUuid(id)));
        var recovery=h.getList("RecoveryRequired",11);assertTrue(recovery.size()<=1);for(var id:recovery)assertTrue(ids.contains(NbtHelper.toUuid(id))&&!crossed.contains(NbtHelper.toUuid(id)));
        assertEquals(recovery.size()==1,h.getString("SealedReason").equals("TRANSFER_RECOVERY_REQUIRED"));
        if(h.getCompound("CloseIntent").getString("Kind").equals("CLOSE")){assertTrue(row.getList("SpaceLeases",10).isEmpty());assertTrue(recovery.isEmpty());}
    }
    static void appendCandidate(NbtCompound old,NbtCompound template,int index){
        NbtCompound entry=template.copy();entry.getCompound("DoorKey").putLong("LogicalStationIndex",index);
        byte[] id=new byte[32];java.nio.ByteBuffer.wrap(id).putInt(index);entry.getCompound("Candidate").putByteArray("CandidateId",id);
        byte[] token=id.clone();token[31]=1;entry.getCompound("Candidate").getCompound("Intent").putByteArray("AllocationToken",token);
        old.getList("CandidateLedger",10).add(entry);
    }
    static NbtCompound wrap(List<NbtCompound> rows){NbtCompound wrapper=new NbtCompound(),data=new NbtCompound();wrapper.putInt("DataVersion",3953);data.putInt("SchemaVersion",6);NbtList list=new NbtList();list.addAll(rows);data.put("Records",list);wrapper.put("data",data);return wrapper;}
    static NbtCompound kind(String kind){NbtCompound n=new NbtCompound();n.putString("Kind",kind);return n;}
    static NbtList vector(double x,double y,double z){NbtList n=new NbtList();for(double v:new double[]{x,y,z})n.add(NbtDouble.of(v));return n;}
    static byte[] compressed(NbtCompound value)throws IOException{var output=new ByteArrayOutputStream();NbtIo.writeCompressed(value,output);return output.toByteArray();}
    static NbtCompound decode(byte[] same,long limit)throws IOException{return NbtIo.readCompressed(new ByteArrayInputStream(same),NbtSizeTracker.of(limit));}
    static long measure(NbtCompound value)throws IOException{var tracker=NbtSizeTracker.of(Long.MAX_VALUE);NbtIo.readCompressed(new ByteArrayInputStream(compressed(value)),tracker);return tracker.getAllocatedBytes();}
    static byte[] duplicate(String where)throws IOException{var output=new ByteArrayOutputStream();try(var out=new DataOutputStream(new GZIPOutputStream(output))){out.writeByte(10);out.writeUTF("");if(!where.equals("wrapper")){out.writeByte(10);out.writeUTF("data");}if(where.equals("inner")){out.writeByte(10);out.writeUTF("Home");}for(int i=0;i<2;i++){out.writeByte(3);out.writeUTF("Repeated");out.writeInt(i);}out.writeByte(0);if(where.equals("inner"))out.writeByte(0);if(!where.equals("wrapper"))out.writeByte(0);}return output.toByteArray();}
    static void scan(byte[] same)throws IOException{try(var input=new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(same)))){if(input.readUnsignedByte()!=10)throw new IOException("ROOT");input.readUTF();raw(input,10,"$",0);if(input.read()!=-1)throw new IOException("TAIL");}}
    static void raw(DataInputStream in,int type,String path,int depth)throws IOException {
        if(depth>512)throw new IOException("DEPTH "+path);
        switch(type){
            case 1->in.readByte();case 2->in.readShort();case 3,5->in.readInt();case 4,6->in.readLong();case 8->in.readUTF();
            case 7,11,12->{int length=in.readInt();if(length<0||length>LIMIT)throw new IOException("LENGTH");long bytes=(long)length*(type==7?1:type==11?4:8);in.skipNBytes(bytes);}
            case 9->{int held=in.readUnsignedByte(),length=in.readInt();if(length<0||length>LIMIT)throw new IOException("LENGTH");for(int i=0;i<length;i++)raw(in,held,path+"["+i+"]",depth+1);}
            case 10->{Set<String> keys=new HashSet<>();int child;while((child=in.readUnsignedByte())!=0){String key=in.readUTF();if(!keys.add(key))throw new IOException("DUPLICATE "+path+"."+key);raw(in,child,path+"."+key,depth+1);}}
            default->throw new IOException("TYPE "+type);
        }
    }
}
