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
        NbtCompound home=home(1);validateHome(home);
        NbtCompound old=legacyFive();
        var allocator=new SlotAllocator();allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();var reserved=allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();
        var lease=old.getList("SpaceLeases",10).getCompound(0);lease.putInt("SlotId",reserved.slotId());var box=reserved.bounds();lease.putIntArray("Bounds",new int[]{box.getMinX(),box.getMinY(),box.getMinZ(),box.getMaxX(),box.getMaxY(),box.getMaxZ()});
        old.getList("Participants",10).getCompound(0).put("SourcePosition",vector(1007.5,66,12.5));
        SessionRecoveryRecord.fromNbt(old,5);
        long current=measure(wrap(List.of(home,old)));NbtCompound template=old.getList("CandidateLedger",10).getCompound(1).copy();
        appendCandidate(old,template,10);long growth=measure(wrap(List.of(home,old)))-current;
        int additions=(int)((LIMIT-current)/growth);
        for(int i=1;i<additions;i++)appendCandidate(old,template,10+i);
        SessionRecoveryRecord.fromNbt(old,5);
        validateOwnership(List.of(home,old));
        NbtCompound missing=old.copy();missing.remove("Collapse");assertThrows(IllegalArgumentException.class,()->SessionRecoveryRecord.fromNbt(missing,5));
        NbtCompound below=wrap(List.of(home,old));long used=measure(below);
        assertTrue(used<=LIMIT);assertTrue(old.getList("CandidateLedger",10).size()<=16384);
        byte[] bytes=compressed(below);assertEquals(6,scan(bytes));assertEquals(below,decode(bytes,LIMIT));
        appendCandidate(old,template,10+additions);SessionRecoveryRecord.fromNbt(old,5);
        NbtCompound above=wrap(List.of(home,old));long next=measure(above);
        assertTrue(next>LIMIT);assertThrows(RuntimeException.class,()->decode(compressed(above),LIMIT));
        var tracker=NbtSizeTracker.of(LIMIT);tracker.add(LIMIT);assertThrows(RuntimeException.class,()->tracker.add(1));
        Files.write(directory.resolve("legal-below.dat"),bytes);Files.write(directory.resolve("legal-above.dat"),compressed(above));
        System.out.println("M7_BUDGET boundary below="+used+" above="+next+" quantum="+growth+" candidates="+old.getList("CandidateLedger",10).size()+" compressed="+bytes.length+" exactLimit="+LIMIT);
    }
    @Test void sameBytesSnapshotDepthAndDuplicatePaths() throws Exception {
        NbtCompound value=wrap(List.of(home(64)));byte[] snapshot=compressed(value);
        Path path=directory.resolve("switch.dat");Files.write(path,snapshot);byte[] same=Files.readAllBytes(path);
        Files.write(path,new byte[]{0});assertEquals(value,decode(same,LIMIT));
        for(String duplicate:List.of("wrapper","data","inner")) {
            byte[] invalid=duplicate(duplicate);
            IOException failure=assertThrows(IOException.class,()->scan(invalid));
            assertEquals("DUPLICATE "+switch(duplicate){case "wrapper"->"$.Repeated";case "data"->"$.data.Repeated";default->"$.data.Home.Repeated";},failure.getMessage());
        }
        int last=0;
        for(int depth=509;depth<=513;depth++) {
            NbtCompound root=new NbtCompound(), cursor=root;
            for(int i=0;i<depth;i++){NbtCompound next=new NbtCompound();cursor.put("n",next);cursor=next;}
            byte[] sameDepth=compressed(root);
            if(depth<=511){decode(sameDepth,LIMIT);scan(sameDepth);last=depth;}else{assertThrows(RuntimeException.class,()->decode(sameDepth,LIMIT));var depthFailure=assertThrows(IOException.class,()->scan(sameDepth));assertEquals("M7_RAW_DEPTH",depthFailure.getMessage());}
        }
        assertEquals(511,last,"原生 tracker compound root 也占一層，512 個容器可讀");
        assertEquals(6,scan(snapshot));
        var over=new NbtCompound();over.putByteArray("payload",new byte[(int)LIMIT+1]);var bomb=compressed(over);
        assertTrue(bomb.length<100000);var rawOver=assertThrows(IOException.class,()->scan(bomb));assertEquals("M7_RAW_TOTAL_BYTES",rawOver.getMessage());
    }
    static NbtCompound home(int leaseCount) {
        NbtCompound row=new NbtCompound();row.putInt("RecordVersion",2);row.putUuid("SessionUuid",SID);row.putUuid("ChamberUuid",CHAMBER);
        NbtCompound root=new NbtCompound();root.putUuid("ChamberUuid",CHAMBER);root.putString("WorldKey","minecraft:overworld");root.putString("Role","OVERWORLD");
        root.putIntArray("ControllerPos",new int[]{7,70,9});root.putString("Facing","NORTH");root.putString("InstanceKind","ORIGIN");row.put("RootAnchor",root);
        NbtCompound entry=root.copy();entry.putUuid("UniverseId",UNIVERSE);entry.putString("WorldKey","quantumchamber:universe/"+UNIVERSE+"/overworld");entry.putString("InstanceKind","PROJECTION");row.put("EntryEndpoint",entry);
        NbtList people=new NbtList();
        for(int i=0;i<25;i++) {
            NbtCompound p=SessionRecoveryStateTest.fixture("SUPERPOSITION",false).getList("Records",10).getCompound(0).getList("Participants",10).getCompound(0).copy();
            p.putUuid("PlayerUuid",new UUID(7,100+i));p.put("SourcePosition",vector(5.5+i%5,66,10.5+i/5));
            p.putBoolean("Returned",false);people.add(p);
        }
        row.put("Participants",people);NbtList leases=new NbtList();SlotAllocator allocator=new SlotAllocator();
        for(int i=0;i<leaseCount;i++){var slot=allocator.reserve(new BlockBox(0,0,0,6,6,6)).orElseThrow();NbtCompound lease=new NbtCompound();lease.putInt("SlotId",slot.slotId());var b=slot.bounds();lease.putIntArray("Bounds",new int[]{b.getMinX(),b.getMinY(),b.getMinZ(),b.getMaxX(),b.getMaxY(),b.getMaxZ()});leases.add(lease);}
        row.put("SpaceLeases",leases);row.putString("State","SUPERPOSITION");
        row.putBoolean("RestoreEntryEffectOnReturn",false);row.putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");row.putInt("TravelCapabilityVersion",1);
        NbtCompound h=new NbtCompound();h.putInt("HomeRevision",1);NbtCompound selection=kind("UNSELECTED");
        h.put("Selection",selection);long committed=-1;h.putLong("CommittedAtGameTime",committed);
        h.putLong("PassageOpenedAtGameTime",committed<0?-1:30);h.putLong("PassageExpiresAtGameTime",committed<0?-1:1230);
        NbtList crosses=new NbtList();h.put("Crossed",crosses);
        NbtList recoveries=new NbtList();h.put("RecoveryRequired",recoveries);
        h.putString("StopReason","");h.putLong("StoppedAtGameTime",-1);h.putString("SealedReason","");h.put("CloseIntent",kind("NONE"));
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
        assertEquals("NONE",h.getCompound("CloseIntent").getString("Kind"));
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
    static int scan(byte[] same)throws IOException {
        if(same.length>LIMIT)throw new IOException("M7_RAW_COMPRESSED_BYTES");
        try(var bounded=new RawBudget(new GZIPInputStream(new ByteArrayInputStream(same)));var input=new DataInputStream(bounded)){
            if(input.readUnsignedByte()!=10)throw new IOException("M7_RAW_ROOT");input.readUTF();var walker=new RawWalker(input);walker.read(10,"$",0);if(input.read()!=-1)throw new IOException("M7_RAW_TAIL");return walker.version;
        }
    }
    static final class RawBudget extends FilterInputStream {
        long count;RawBudget(InputStream input){super(input);}
        void charge(long bytes)throws IOException{count=Math.addExact(count,bytes);if(count>LIMIT)throw new IOException("M7_RAW_TOTAL_BYTES");}
        @Override public int read()throws IOException{int value=in.read();if(value>=0)charge(1);return value;}
        @Override public int read(byte[] bytes,int offset,int length)throws IOException{int amount=in.read(bytes,offset,length);if(amount>0)charge(amount);return amount;}
        @Override public long skip(long amount)throws IOException{long result=in.skip(Math.min(amount,LIMIT+1-count));charge(result);return result;}
    }
    static final class RawWalker {
        final DataInputStream in;int version=-1;long names,characters,elements;
        RawWalker(DataInputStream input){in=input;}
        void read(int type,String path,int containers)throws IOException{
            if(type==9||type==10){if(containers>=512)throw new IOException("M7_RAW_DEPTH");containers++;}
            switch(type){
                case 1->in.readByte();case 2->in.readShort();case 3->{int value=in.readInt();if(path.equals("$.data.SchemaVersion"))version=value;}case 5->in.readInt();case 4,6->in.readLong();case 8->in.readUTF();
                case 7,11,12->{int length=in.readInt();if(length<0)throw new IOException("M7_RAW_LENGTH");long bytes=Math.multiplyExact((long)length,type==7?1:type==11?4:8);if(bytes>LIMIT)throw new IOException("M7_RAW_TOTAL_BYTES");in.skipNBytes(bytes);}
                case 9->{int held=in.readUnsignedByte(),length=in.readInt();elements+=length;if(length<0||elements>LIMIT)throw new IOException("M7_RAW_ELEMENTS");for(int i=0;i<length;i++)read(held,path+"["+i+"]",containers);}
                case 10->{Set<String> keys=new HashSet<>();int child;while((child=in.readUnsignedByte())!=0){String key=in.readUTF();names++;characters+=key.length();if(names>LIMIT/32||characters>LIMIT)throw new IOException("M7_RAW_KEYS");if(!keys.add(key))throw new IOException("DUPLICATE "+path+"."+key);read(child,path+"."+key,containers);}}
                default->throw new IOException("M7_RAW_TYPE");
            }
        }
    }
    static NbtCompound legacyFive(){
        var upgraded=SessionRecoveryRecord.fromNbt(SessionRecoverySchema3Test.record(SessionRecoverySchema3Test.fixture(false,false)),3).toNbt(5);
        upgraded.getCompound("Origin").putIntArray("ControllerPos",new int[]{1007,70,9});upgraded.getList("Participants",10).getCompound(0).put("SourcePosition",vector(1007.5,66,12.5));
        var state=new NbtCompound();state.putInt("SchemaVersion",5);var rows=new NbtList();rows.add(upgraded);state.put("Records",rows);SessionRecoveryState.fromNbt(state).requireHealthy();return upgraded;
    }
    static void validateOwnership(List<NbtCompound> rows){
        var sessions=new HashSet<UUID>();var players=new HashSet<UUID>();var roots=new HashSet<String>();var slots=new HashSet<Integer>();var boxes=new ArrayList<BlockBox>();
        for(var row:rows){if(!sessions.add(row.getUuid("SessionUuid")))throw new IllegalArgumentException("M7_SESSION_DUPLICATE");var origin=row.contains("RecordVersion")?row.getCompound("RootAnchor"):row.getCompound("Origin");if(!roots.add(origin.getString("WorldKey")+Arrays.toString(origin.getIntArray("ControllerPos"))))throw new IllegalArgumentException("M7_ROOT_ALIAS");
            for(var person:row.getList("Participants",10))if(!players.add(((NbtCompound)person).getUuid("PlayerUuid")))throw new IllegalArgumentException("M7_PLAYER_DUPLICATE");
            for(var raw:row.getList("SpaceLeases",10)){var lease=(NbtCompound)raw;var b=lease.getIntArray("Bounds");if(b.length!=6||!slots.add(lease.getInt("SlotId")))throw new IllegalArgumentException("M7_SLOT_DUPLICATE");var box=new BlockBox(b[0],b[1],b[2],b[3],b[4],b[5]);if(b[4]-b[1]!=6||!(b[3]-b[0]==6||b[5]-b[2]==6)||boxes.stream().anyMatch(box::intersects))throw new IllegalArgumentException("M7_SLOT_BOUNDS");boxes.add(box);}
        }if(slots.size()>64)throw new IllegalArgumentException("M7_SLOT_CAP");
    }
}
