package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.universe.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.*;

/** T1 test-only CLOSE PoC；T4 必須另由 production close owner 重跑相同 inventory。 */
public final class M7CloseSpikePhases {
    static final UUID UNIVERSE=UUID.fromString("77000000-0000-4000-8000-000000000001"), CHAMBER=UUID.fromString("77000000-0000-4000-8000-000000000002");
    static final ChamberFrame FRAME=new ChamberFrame(new BlockPos(15,76,15),Direction.NORTH);
    private static final net.minecraft.server.world.ChunkTicketType<UUID> TICKET=net.minecraft.server.world.ChunkTicketType.create("m7_spike_poc",Comparator.comparing(UUID::toString));
    static final List<String> CUTS=List.of("before-close","after-close","before-door","after-door","before-checkpoint","after-checkpoint","after-temp-force","before-publish","after-publish","after-readback","after-recheck","before-remove","after-remove");
    private final Path root,journal,receipt,temporary;
    private final String phase,cut,door;
    private final ServerWorld world;
    private final Map<String,Object> proof;
    private final ThrowingAction halt;
    private final List<String> trace=new ArrayList<>();
    private final Set<ChunkPos> chunks=new LinkedHashSet<>();
    private int ticks;
    private boolean initialized;
    private NbtCompound closed;
    M7CloseSpikePhases(Path root,String chain,String phase,ServerWorld world,Map<String,Object> proof,ThrowingAction halt) {
        this.root=root;this.phase=phase;this.world=world;this.proof=proof;this.halt=halt;
        String suffix=chain.substring("spike-close-".length());door=suffix.substring(0,suffix.indexOf('-'));cut=suffix.substring(door.length()+1);
        M7HomeProbe.require(CUTS.contains(cut),"M7_CUT");
        Path data=root.resolve("world/data/m7-spike");journal=data.resolve("active.dat");receipt=data.resolve("receipt.dat");temporary=data.resolve("receipt.complete.tmp");
        for(int x=0;x<=1;x++)for(int z=0;z<=1;z++)chunks.add(new ChunkPos(x,z));
    }
    void start(MinecraftServer server)throws Exception {
        // 原版持久 forced 集合在 crash 後仍有相同值；setChunkForced(true) 無變更便不重建 runtime ticket。
        // PoC 與正式 checkpoint caller 一樣，每個 JVM 都重新持有自己的暫載票，不改 production 的 bootstrap。
        for(var chunk:chunks){world.getChunkManager().addTicket(TICKET,chunk,0,CHAMBER);world.getChunk(chunk.x,chunk.z);}
        if(phase.equals("reopen"))readInitialDisk();
    }
    boolean tick(MinecraftServer server)throws Exception {
        if(++ticks>1200)throw new IllegalStateException("M7_CHECKPOINT_READY_TIMEOUT");
        if(!ChunkSaveCheckpoint.ready(world,chunks))return false;
        if(!initialized) {
            initialized=true;
            proof.put("bWorldKey",world.getRegistryKey().getValue().toString());proof.put("checkpointChunks",chunks.stream().map(ChunkPos::toString).toList());proof.put("trace",trace);
            if(phase.equals("crash"))prepare(server);
            execute(server);
        }
        return true;
    }
    private void prepare(MinecraftServer server)throws Exception {
        var authorities=CollapseAuthorities.get(server);
        var pending=authorities.writeProjection(new ProjectionLedger.Entry(CHAMBER,UniverseId.of(UNIVERSE),DimensionRole.OVERWORLD,FRAME.controllerPos(),FRAME.outwardFacing(),ProjectionLedger.Entry.Status.PENDING));
        M7HomeProbe.require(pending.status()==CollapseAuthorities.WriteResult.Status.WRITTEN,"M7_PENDING");ProjectionProtectionIndex.get(server).confirmed(pending);
        M7HomeProbe.require(ProjectionWriter.write(world,FRAME,ProjectionWriter.Mode.FIRST_WRITE).succeeded(),"M7_B_FIXTURE");
        ChunkSaveCheckpoint.save(server,Map.of(world,chunks));
        var complete=authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE));
        M7HomeProbe.require(complete.status()==CollapseAuthorities.WriteResult.Status.WRITTEN,"M7_COMPLETE");ProjectionProtectionIndex.get(server).confirmed(complete);
        var state=world.getBlockEntity(FRAME.controllerPos());M7HomeProbe.require(state instanceof ChamberControllerBlockEntity,"M7_CONTROLLER");
        var controller=(ChamberControllerBlockEntity)state;controller.setChamberUuid(CHAMBER);controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        var membrane=switch(door){case "ordinary"->MembraneState.NONE;case "sealed"->MembraneState.SEALED_X;case "passable"->MembraneState.PASSABLE_X;default->throw new IllegalStateException("M7_DOOR");};
        apply(new ChamberDoorService.DoorState(true,membrane));ChunkSaveCheckpoint.save(server,Map.of(world,chunks));
        Files.createDirectories(journal.getParent());NbtCompound active=fixture(false);writeJournal(active);closed=fixture(true);
        proof.put("fixture","TEST_ONLY_PROOF_OF_CONCEPT_NOT_FORMAL_ADMISSION");proof.put("initialDoor",door);
        // 同一 save volume 的 admission 能力測試；正式 SID 尚未進行任何旅行。
        Path capability=journal.resolveSibling("capability.tmp"),linked=journal.resolveSibling("capability.link");Files.write(capability,new byte[]{7},StandardOpenOption.CREATE_NEW);Files.createLink(linked,capability);M7HomeProbe.require(Files.isSameFile(capability,linked),"M7_VOLUME_CAPABILITY");
        var doorPosition=ChamberGeometry.localToWorld(FRAME,3,2,0);
        var entity=new net.minecraft.entity.decoration.ArmorStandEntity(world,doorPosition.getX()+0.5,doorPosition.getY(),doorPosition.getZ()+0.5);entity.setNoGravity(true);
        M7HomeProbe.require(world.spawnEntity(entity),"M7_ENTITY_FIXTURE");
        boolean blocked=ChamberGeometry.bulkheadPositions(FRAME).stream().anyMatch(pos->!world.getOtherEntities(null,new Box(pos),candidate->candidate.isAlive()).isEmpty());
        M7HomeProbe.require(blocked,"M7_ENTITY_NOT_OBSERVED");
        apply(blocked?ChamberDoorService.DoorState.OPEN:ChamberDoorService.DoorState.CLOSED);
        M7HomeProbe.require(entity.isAlive()&&entity.getPos().equals(new Vec3d(doorPosition.getX()+0.5,doorPosition.getY(),doorPosition.getZ()+0.5)),"M7_ENTITY_CLAMPED");
        proof.put("entityAntiClampWitness",true);entity.discard();apply(new ChamberDoorService.DoorState(true,membrane));
        // 真 checkpoint hook 拒絕一次，未寫 receipt／未移除 active。
        var refusal=new M5TestHooks.ChunkCheckpointProbe(world.getRegistryKey(),1,index->"M7_POC_REFUSAL");M5TestHooks.CHUNK_CHECKPOINT_PROBE.install(refusal,null);
        boolean rejected=false;try{ChunkSaveCheckpoint.save(server,Map.of(world,chunks));}catch(IllegalStateException expected){rejected=true;}finally{M5TestHooks.CHUNK_CHECKPOINT_PROBE.remove(refusal);}
        M7HomeProbe.require(rejected&&refusal.calls().size()==1&&!Files.exists(receipt)&&Files.exists(journal),"M7_CHECKPOINT_REFUSAL");proof.put("checkpointRefusalWitness",refusal.calls().size());
    }
    private void readInitialDisk()throws Exception {
        proof.put("diskInitialDoors",doors());proof.put("diskInitialJournal",Files.exists(journal)?hash(journal):"ABSENT");proof.put("diskInitialReceipt",Files.exists(receipt)?hash(receipt):"ABSENT");
        int index=CUTS.indexOf(cut);
        var initialMembrane=switch(door){case "ordinary"->MembraneState.NONE;case "sealed"->MembraneState.SEALED_X;case "passable"->MembraneState.PASSABLE_X;default->throw new IllegalStateException("M7_DOOR");};
        var expected=index>=CUTS.indexOf("after-checkpoint")?ChamberDoorService.DoorState.CLOSED:new ChamberDoorService.DoorState(true,initialMembrane);
        assertDoors(expected);
        if(Files.exists(journal))M7HomeProbe.require(read(journal).equals(fixture(index>0)),"M7_DISK_JOURNAL_EXACT");
        if(index>=CUTS.indexOf("after-publish")){M7HomeProbe.require(Files.exists(receipt),"M7_RECEIPT_NOT_DURABLE");M7HomeProbe.require(read(receipt).equals(receiptFixture()),"M7_DISK_RECEIPT_EXACT");}
        if(index>=CUTS.indexOf("after-remove"))M7HomeProbe.require(!Files.exists(journal),"M7_ACTIVE_NOT_REMOVED");
        closed=fixture(true);
    }
    private void execute(MinecraftServer server)throws Exception {
        boolean removed=!Files.exists(journal);
        if(removed){M7HomeProbe.require(phase.equals("reopen")&&Files.exists(receipt),"M7_EARLY_REMOVE");proof.put("alreadyRemoved",true);assertDoors(ChamberDoorService.DoorState.CLOSED);complete();return;}
        hit("before-close");writeJournal(closed);hit("after-close");
        hit("before-door");apply(ChamberDoorService.DoorState.CLOSED);hit("after-door");
        hit("before-checkpoint");ChunkSaveCheckpoint.save(server,Map.of(world,chunks));hit("after-checkpoint");
        NbtCompound wrapper=receiptFixture();
        if(!Files.exists(temporary)){NbtIo.writeCompressed(wrapper,temporary);force(temporary);}else M7HomeProbe.require(read(temporary).equals(wrapper),"M7_TEMP_EXACT");hit("after-temp-force");
        hit("before-publish");try{Files.createLink(receipt,temporary);}catch(FileAlreadyExistsException existing){M7HomeProbe.require(read(receipt).equals(wrapper),"M7_RECEIPT_CONFLICT");}hit("after-publish");
        M7HomeProbe.require(read(receipt).equals(wrapper),"M7_READBACK");hit("after-readback");
        assertDoors(ChamberDoorService.DoorState.CLOSED);ChunkSaveCheckpoint.save(server,Map.of(world,chunks));hit("after-recheck");
        hit("before-remove");Files.delete(journal);hit("after-remove");complete();
    }
    private void complete()throws Exception {proof.put("journalFinal","ABSENT");proof.put("receiptHash",hash(receipt));proof.put("finalDoors",doors());proof.put("nativeCheckpoint",true);for(var chunk:chunks)world.getChunkManager().removeTicket(TICKET,chunk,0,CHAMBER);}
    private void hit(String point)throws Exception {trace.add(point);Files.writeString(root.resolve("evidence/"+phase+"/trace.txt"),String.join("\n",trace));if(phase.equals("crash")&&point.equals(cut)){proof.put("hit",point);proof.put("journalHash",Files.exists(journal)?hash(journal):"ABSENT");proof.put("receiptHash",Files.exists(receipt)?hash(receipt):"ABSENT");proof.put("doorsAtCut",doors());halt.run();}}
    private void apply(ChamberDoorService.DoorState target) {var result=new ChamberDoorService().applyTarget(world,FRAME,target);M7HomeProbe.require(Set.of(ChamberDoorService.ApplyResult.Outcome.APPLIED,ChamberDoorService.ApplyResult.Outcome.UNCHANGED).contains(result.outcome()),"M7_APPLY "+result);assertDoors(target);}
    private void assertDoors(ChamberDoorService.DoorState target){for(var position:ChamberGeometry.bulkheadPositions(FRAME)){var state=world.getBlockState(position);M7HomeProbe.require(state.get(QuantumBulkheadBlock.OPEN)==target.open()&&QuantumBulkheadBlock.membrane(state)==target.membrane(),"M7_DISK_DOOR "+position);}}
    private List<String> doors(){return ChamberGeometry.bulkheadPositions(FRAME).stream().sorted(Comparator.comparingLong(BlockPos::asLong)).map(pos->pos.toShortString()+"="+world.getBlockState(pos)).toList();}
    private void writeJournal(NbtCompound value)throws Exception {NbtIo.writeCompressed(value,journal);force(journal);M7HomeProbe.require(read(journal).equals(value),"M7_JOURNAL_READBACK");}
    private static NbtCompound receiptFixture(){NbtCompound wrapper=new NbtCompound(),data=new NbtCompound();wrapper.putInt("DataVersion",3953);data.putInt("SchemaVersion",1);data.putUuid("SessionUuid",new UUID(7,1));data.put("CloseIntent",fixture(true).getCompound("Home").getCompound("CloseIntent").copy());data.put("Session",fixture(false));wrapper.put("data",data);return wrapper;}
    private static void force(Path path)throws Exception{try(var channel=FileChannel.open(path,StandardOpenOption.WRITE)){channel.force(true);}}
    private static NbtCompound read(Path path)throws Exception{return NbtIo.readCompressed(new ByteArrayInputStream(Files.readAllBytes(path)),NbtSizeTracker.of(16_777_216));}
    private static String hash(Path path)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    static NbtCompound fixture(boolean close) {
        NbtCompound row=new NbtCompound();row.putInt("RecordVersion",2);row.putUuid("SessionUuid",new UUID(7,1));row.putUuid("ChamberUuid",CHAMBER);
        NbtCompound anchor=new NbtCompound();anchor.putUuid("ChamberUuid",CHAMBER);anchor.putString("WorldKey","minecraft:overworld");anchor.putString("Role","OVERWORLD");anchor.putIntArray("ControllerPos",new int[]{15,76,15});anchor.putString("Facing","NORTH");anchor.putString("InstanceKind","ORIGIN");row.put("RootAnchor",anchor);
        NbtCompound entry=anchor.copy();entry.putString("WorldKey",UniverseKeys.world(UniverseId.of(UNIVERSE),DimensionRole.OVERWORLD).getValue().toString());entry.putUuid("UniverseId",UNIVERSE);entry.putString("InstanceKind","PROJECTION");row.put("EntryEndpoint",entry);
        NbtCompound person=new NbtCompound();person.putUuid("PlayerUuid",new UUID(7,100));person.put("SourcePosition",vector(15.5,71,18.5));person.put("SourceVelocity",vector(0,0,0));person.putFloat("Yaw",0);person.putFloat("Pitch",0);person.putBoolean("Returned",true);NbtCompound effect=new NbtCompound();effect.putString("id","quantumchamber:quantum_state");effect.putInt("duration",1000);person.put("QuantumState",effect);NbtList people=new NbtList();people.add(person);row.put("Participants",people);row.put("SpaceLeases",new NbtList());row.putString("State","PASSAGE_READY");row.putBoolean("RestoreEntryEffectOnReturn",false);row.putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");row.putInt("TravelCapabilityVersion",1);
        NbtCompound h=new NbtCompound();h.putInt("HomeRevision",1);NbtCompound selection=kind("HOME"),key=new NbtCompound();key.putUuid("SessionUuid",new UUID(7,1));key.putLong("LogicalStationIndex",1);key.putString("WallSide","POSITIVE_LATERAL");selection.put("DoorKey",key);selection.putUuid("SelectedBy",new UUID(7,100));selection.putLong("SelectedAtGameTime",10);h.put("Selection",selection);h.putLong("CommittedAtGameTime",20);h.putLong("PassageOpenedAtGameTime",30);h.putLong("PassageExpiresAtGameTime",1230);h.put("Crossed",new NbtList());h.put("RecoveryRequired",new NbtList());h.putString("StopReason","");h.putLong("StoppedAtGameTime",-1);h.putString("SealedReason","");NbtCompound intent=kind(close?"CLOSE":"NONE");
        if(close){intent.putInt("CloseRevision",1);intent.putString("CloseReason","PARTICIPANT_CLOSED");intent.putLong("ClosedAtGameTime",40);intent.putString("DoorGoal","CLOSE_IF_CLEAR_ELSE_OPEN");NbtCompound evidence=kind("PARTICIPANT_CLOSED");evidence.putUuid("Actor",new UUID(7,100));evidence.putString("Phase","PASSAGE");evidence.putLong("ObservedAtGameTime",40);intent.put("Evidence",evidence);}h.put("CloseIntent",intent);row.put("Home",h);return row;
    }
    private static NbtCompound kind(String value){NbtCompound n=new NbtCompound();n.putString("Kind",value);return n;}
    private static NbtList vector(double x,double y,double z){NbtList list=new NbtList();for(double value:new double[]{x,y,z})list.add(NbtDouble.of(value));return list;}
    @FunctionalInterface interface ThrowingAction {void run()throws Exception;}
}
