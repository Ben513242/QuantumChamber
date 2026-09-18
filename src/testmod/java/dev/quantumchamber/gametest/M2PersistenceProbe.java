package dev.quantumchamber.gametest;

import com.google.gson.*;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.*;

/** 預設關閉；只在 canonical fresh nonce root 驗證真 checkpoint 與跨 JVM 原生載入。 */
public final class M2PersistenceProbe implements ModInitializer {
    private static M2PersistenceProbe active;
    private final String phase=System.getProperty("quantumchamber.m2.checkpoint.phase");
    private final String kind=System.getProperty("quantumchamber.m2.checkpoint.case");
    private final String nonce=System.getProperty("quantumchamber.m2.nonce");
    private MinecraftServer server;
    private ServerWorld source;
    private Path root;
    private final ChamberFrame frame=new ChamberFrame(new BlockPos(8,70,8),Direction.NORTH);
    private final List<ConnectedGameTestPlayer> connections=new ArrayList<>();
    private final Map<UUID,GameProfile> profiles=new LinkedHashMap<>();
    private UUID sid,chamber,a,b;
    private int stage,ticks,nativeTicks,blockedFlushes,checkpointSuccesses;
    private boolean finished,windowSaved,aEntrySaved,barrierEntered,staleArmed,staleHit,staleVerified;
    private SessionTransferFault transferFault;

    @Override public void onInitialize() {
        if(phase==null) return;
        if(!Set.of("prepare","recover","verify").contains(phase) || !Set.of("restore","keep","entry","stale").contains(kind)
                || nonce==null || !nonce.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("未知或不完整 checkpoint probe phase");
        ServerLifecycleEvents.SERVER_STARTED.register(this::attach);
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(owner -> { if(owner==server) active=null; });
    }

    private void attach(MinecraftServer owner) {
        try {
            server=owner; source=owner.getOverworld(); root=Path.of(".").toRealPath();
            var expected=Path.of(System.getProperty("quantumchamber.m2.checkpoint.root")).toRealPath();
            require(root.equals(expected) && root.getFileName().toString().equals("m2-task4-"+kind+"-"+nonce),"canonical owned runRoot 不符");
            require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()),"真 storage 不是 own world");
            require(active==null && owner.isOnThread(),"probe 不可重複或跨執行緒 attach"); active=this;
            if(phase.equals("prepare")) {
                require(SessionRecoveryState.get(owner).flushedRecords().isEmpty(),"首次 fixture 必須 bootstrap0");
                var playerdata=owner.getSavePath(WorldSavePath.PLAYERDATA);
                if(Files.isDirectory(playerdata)) try(var files=Files.list(playerdata)) { require(files.findAny().isEmpty(),"首次 fixture 不接受既有玩家"); }
                stage=-1;
            } else readManifest();
            log("ATTACHED phase="+phase+" kind="+kind+" pid="+ProcessHandle.current().pid()+" runRoot="+root);
        } catch(Exception failure) { throw new IllegalStateException("checkpoint probe attach 拒絕",failure); }
    }

    private void tick(MinecraftServer owner) {
        if(owner!=server || finished) return;
        try {
            requireScope(); if(++ticks>12000) throw new IllegalStateException("checkpoint probe 有界等待逾時");
            // testmod 與 main 的 STARTED callback 註冊順序不固定；所有 actor 必須等真正 server tick 才建立。
            if(phase.equals("prepare") && stage==-1) { build(); stage=0; return; }
            if(phase.equals("prepare")) prepare(); else recover();
        } catch(Exception failure) {
            finished=true;
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").error("M2_CHECKPOINT_PROBE_FAILED",failure);
            try { result("failure", "FAILED",Map.of("message",failure.toString())); }
            catch(IOException writing) { throw new UncheckedIOException(writing); }
        }
    }

    private void build() {
        M1FoundationSessionFixture.set(source,frame.controllerPos(),false);
        ChamberProtectionService.get().authorizedMutation(() -> {
            for(int x=0;x<7;x++) for(int y=0;y<7;y++) for(int z=0;z<7;z++) {
                boolean shell=x==0 || x==6 || y==0 || y==6 || z==0 || z==6;
                var block=shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if(z==0 && x>0 && x<6 && y>0 && y<6) block=ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN,false);
                source.setBlockState(ChamberGeometry.localToWorld(frame,x,y,z),block,3);
            }
            source.setBlockState(frame.controllerPos(),ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING,Direction.NORTH),3);
        });
        // 先以空艙真 HIGH 註冊 Origin；取得 UUID 前不給玩家入場效果，避免提早建立 session。
        source.setBlockState(frame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
        int count=kind.equals("entry") || kind.equals("restore") ? 2 : 1;
        for(int i=0;i<count;i++) {
            var connection=new ConnectedGameTestPlayer(source); connections.add(connection);
            var player=connection.player(); player.setNoGravity(true);
            player.refreshPositionAndAngles(CorridorGeometry.position(frame,1.5+i,1,1.5),17+i,9+i);
            player.getInventory().setStack(0,new net.minecraft.item.ItemStack(net.minecraft.item.Items.TORCH,7+i));
            profiles.put(player.getUuid(),player.getGameProfile());
        }
    }

    private void prepare() throws Exception {
        var controller=controller();
        if(stage==0) {
            if(controller==null || controller.chamberUuid()==null) return;
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
            for(var connection : connections) if(!connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE))
                connection.player().addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
            var preview=new ChamberActivationService().evaluateReadiness(source,controller);
            if(!preview.accepted() || preview.participantUuids().size()!=connections.size()) return;
            a=preview.participantUuids().getFirst(); b=connections.size()==2 ? preview.participantUuids().get(1) : null;
            chamber=controller.chamberUuid();
            for(var id : preview.participantUuids()) {
                var player=player(id); player.removeStatusEffect(ModEffects.QUANTUM_STATE);
                var effect=kind.equals("restore") && id.equals(a) ? entryWithHidden()
                        : new StatusEffectInstance(ModEffects.QUANTUM_STATE,id.equals(a) ? 1200 : 1800,id.equals(a) ? 0 : 1);
                player.addStatusEffect(effect);
                PlayerCheckpointStore.saveAndVerify(server,player,Optional.empty());
                copyOfficial(id,"entry");
            }
            // 來源 Controller UUID／registry／完整艙體必須先由原生保存，才能中止此新世界的 JVM。
            require(server.save(false,true,true),"首次原生 worlds save 未完成");
            source.setBlockState(frame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
            var record=SessionRecoveryState.get(server).flushedRecords().values().stream()
                    .filter(value -> value.chamberUuid().equals(chamber)).findFirst().orElseThrow();
            sid=record.sessionUuid(); require(record.state()==SessionState.ARMING && record.restoreEntryEffectOnReturn(),"先真 checked ARMING,true");
            require(record.participants().getFirst().playerUuid().equals(a),"真 frozen checkpoint 順序與 A 不符");
            writeManifest();
            if(kind.equals("restore")) transferFault=new SessionTransferFault(source,record,SessionTransferFault.Case.SECOND_MOVE);
            stage=1;
            if(kind.equals("stale")) stale();
            return;
        }
        if(kind.equals("stale")) {
            if(complete()) { require(staleVerified,"stale save 必須真 readback 拒絕"); result("ready","PASS",Map.of("staleNativeSaveHit",staleHit)); finished=true; }
            return;
        }
        if(kind.equals("keep") && stage==1 && record()!=null && record().state()==SessionState.SUPERPOSITION) {
            player(a).addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,900,2));
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3); stage=2;
        }
        if((kind.equals("restore") || kind.equals("keep")) && blockedFlushes>0) {
            require(record()!=null && record().state()==SessionState.RETURNING && !participant(record(),a).returned(),"W1 durable returned 必須仍 false");
            require(player(a).getServerWorld()==source && windowSaved,"W1 已在原艙且有真 checkpoint");
            player(a).playerTick(); nativeTicks++;
            int expected=(kind.equals("restore") ? 2400 : 900)-nativeTicks;
            require(player(a).getStatusEffect(ModEffects.QUANTUM_STATE).getDuration()==expected,"真 playerTick 倒數不符");
            if(kind.equals("restore")) require(((NbtCompound)player(a).getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt())
                    .getCompound("hidden_effect").getInt("duration")==600-nativeTicks,"hidden chain 也必須自然倒數");
            if(nativeTicks==40) {
                PlayerCheckpointStore.saveAndVerify(server,player(a),Optional.of(marker()));
                witness("window"); result("ready","HALT_READY",Map.of("nativePlayerTicks",nativeTicks,"blockedFlushes",blockedFlushes)); finished=true;
            }
        }
    }

    private StatusEffectInstance entryWithHidden() {
        var nbt=new NbtCompound(); nbt.putString("id","quantumchamber:quantum_state"); nbt.putInt("duration",2400); nbt.putByte("amplifier",(byte)1);
        var hidden=new NbtCompound(); hidden.putInt("duration",600); hidden.putByte("amplifier",(byte)0); nbt.put("hidden_effect",hidden);
        return Objects.requireNonNull(StatusEffectInstance.fromNbt(nbt));
    }

    private void stale() throws Exception {
        var previous=readOfficial(a); player(a).getInventory().setStack(1,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,2));
        staleArmed=true; boolean rejected=false;
        try { PlayerCheckpointStore.saveAndVerify(server,player(a),Optional.empty()); }
        catch(IOException expected) { rejected=true; log("STALE_NATIVE_READBACK_REJECTED "+expected.getMessage()); }
        finally { staleArmed=false; }
        require(staleHit && rejected && readOfficial(a).equals(previous),"native save 返回但正式檔 stale 必須由真 readback 拒絕");
        require(!player(a).writeNbt(new NbtCompound()).equals(previous),"stale witness 必須有完整 NBT 實際差異");
        Files.writeString(root.resolve("stale-expected-"+a+".snbt"),player(a).writeNbt(new NbtCompound()).toString(),StandardOpenOption.CREATE_NEW);
        staleVerified=true; copyOfficial(a,"stale-rejected");
        source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
    }

    private void recover() throws Exception {
        if(stage==0) {
            if(controller()==null || !chamber.equals(controller().chamberUuid())) return;
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
            for(var profile : profiles.values()) {
                var connection=new ConnectedGameTestPlayer(source,profile); connections.add(connection);
                require(connection.joinTick()==server.getTicks(),"重啟必須觸發真 JOIN");
                var formal=readOfficial(profile.getId());
                require(((PlayerRecoveryCheckpointAccess)connection.player()).quantumchamber$getRecoveryCheckpoint().equals(markerFrom(formal)),
                        "runtime marker 必須來自正式 playerdata 的 native load");
                log("NATIVE_LOAD uuid="+profile.getId()+" world="+connection.player().getServerWorld().getRegistryKey().getValue()
                        +" effect="+effectNbt(connection.player())+" joinTick="+connection.joinTick());
            }
            stage=1; return;
        }
        if(!complete()) return;
        require(record()==null,"完成不能只看 in-memory remove");
        for(var connection : connections) {
            var player=connection.player();
            require(player.getServerWorld()==source && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),player.getBoundingBox()),"真原艙完整 bbox");
            require(((PlayerRecoveryCheckpointAccess)player).quantumchamber$getRecoveryCheckpoint().equals(Optional.of(marker())),"完整 marker SID/policy");
            var effect=player.getStatusEffect(ModEffects.QUANTUM_STATE);
            int duration=player.getUuid().equals(a) ? kind.equals("restore") ? 2360 : kind.equals("keep") ? 860 : 1200 : 1800;
            int amplifier=player.getUuid().equals(a) ? kind.equals("restore") ? 1 : kind.equals("keep") ? 2 : 0 : 1;
            require(effect!=null && effect.getDuration()==duration && effect.getAmplifier()==amplifier,"重啟不得重套 entry 或覆寫新劑量");
            Path expected=root.resolve((kind.equals("entry") ? "entry-" : "window-")+player.getUuid()+".effect.snbt");
            if(Files.exists(expected)) require(StringNbtReader.parse(Files.readString(expected)).equals(effect.writeNbt()),"完整 effect NBT 與原生窗口證據不符");
            PlayerCheckpointStore.saveAndVerify(server,player,Optional.of(marker()));
        }
        require(server.save(false,true,true),"完成的原生 worlds save 未完成");
        witness(phase); result("ready","PASS",Map.of("nativeLoads",connections.size(),"cohortReturned",true,"sourceOff",true)); finished=true;
    }

    private boolean complete() {
        return ChamberSessions.gateway().presence(server,chamber)==ChamberSessionGateway.Presence.NONE
                && !SessionRecoveryState.get(server).flushedRecords().containsKey(sid)
                && ChamberProtectionService.get().mayMutate(source,frame.controllerPos());
    }

    public static void beforeCheckpoint(MinecraftServer owner,ServerPlayerEntity player,Optional<PlayerRecoveryCheckpoint> expected) throws IOException {
        var probe=scoped(owner,player);
        if(probe==null || !probe.phase.equals("prepare") || !probe.kind.equals("entry") || !player.getUuid().equals(probe.b)
                || !probe.aEntrySaved || expected.isPresent() || probe.barrierEntered) return;
        try {
            require(probe.record()!=null && probe.record().state()==SessionState.ARMING && probe.record().restoreEntryEffectOnReturn(),"W2 必須 checked ARMING,true");
            for(var person : probe.record().participants()) require(probe.player(person.playerUuid()).getServerWorld()==owner.getWorld(SuperpositionWorld.KEY)
                    && !probe.player(person.playerUuid()).hasStatusEffect(ModEffects.QUANTUM_STATE),"W2 全群必須真 fixed 且耗藥");
            require(probe.readOfficial(probe.a).getString("Dimension").equals(SuperpositionWorld.KEY.getValue().toString()),"A 正式檔必須已在 fixed");
            require(probe.readOfficial(probe.b).equals(readNbt(probe.root.resolve("entry-"+probe.b+".dat"))),"B 正式檔必須尚未被 checkpoint 改動");
            probe.barrierEntered=true; probe.witness("window");
            probe.result("ready","HALT_READY",Map.of("aNativeCheckpoint",true,"bCheckpointBlockedNotReturned",true,"barrierSeconds",30));
            long deadline=System.nanoTime()+30_000_000_000L;
            while(System.nanoTime()<deadline) java.util.concurrent.locks.LockSupport.parkNanos(Math.min(100_000_000L,deadline-System.nanoTime()));
            probe.finished=true; probe.result("failure","NON_CRASH_BARRIER_TIMEOUT",Map.of("bExceptionReturned",true));
        } catch(Exception failure) { throw new IOException("W2 owned checkpoint barrier 拒絕",failure); }
        throw new IOException("W2 barrier 已逾時；此 run 不可當作 crash 證據");
    }

    public static void afterCheckpoint(MinecraftServer owner,ServerPlayerEntity player,Optional<PlayerRecoveryCheckpoint> expected) {
        var probe=scoped(owner,player); if(probe==null || !probe.phase.equals("prepare")) return;
        probe.checkpointSuccesses++;
        if(probe.sid!=null && player.getUuid().equals(probe.a) && expected.isEmpty() && probe.record()!=null
                && probe.record().state()==SessionState.ARMING && player.getServerWorld()==owner.getWorld(SuperpositionWorld.KEY)) probe.aEntrySaved=true;
        if(probe.sid!=null && player.getUuid().equals(probe.a) && expected.equals(Optional.of(probe.marker())) && player.getServerWorld()==probe.source) {
            probe.windowSaved=true;
            if(probe.transferFault!=null) { require(probe.transferFault.hit(),"W1 必須真第二move拒絕後 rollback"); probe.transferFault.close(); probe.transferFault=null; }
        }
    }

    public static void beforeJournalFlush(SessionRecoveryState journal,MinecraftServer owner) {
        var probe=active;
        if(probe==null || probe.server!=owner || !probe.phase.equals("prepare") || !(probe.kind.equals("restore") || probe.kind.equals("keep"))
                || probe.sid==null || !probe.windowSaved) return;
        probe.requireScope(); require(SessionRecoveryState.get(owner)==journal,"fault journal owner 不符");
        var current=journal.records().get(probe.sid); var durable=journal.flushedRecords().get(probe.sid);
        if(current!=null && durable!=null && current.state()==SessionState.RETURNING && participant(current,probe.a).returned()
                && !participant(durable,probe.a).returned()) {
            probe.blockedFlushes++;
            throw new UncheckedIOException(new IOException("W1 own SID player checkpoint 已成功、returned flush 受控拒絕"));
        }
    }

    public static boolean rejectNativeSave(Object manager,ServerPlayerEntity player) {
        var probe=scoped(player.getServer(),player);
        if(probe==null || !probe.staleArmed || !probe.kind.equals("stale") || probe.sid==null
                || manager!=probe.server.getPlayerManager() || !player.getUuid().equals(probe.a)) return false;
        probe.staleHit=true; return true;
    }

    private static M2PersistenceProbe scoped(MinecraftServer owner,ServerPlayerEntity player) {
        var probe=active;
        if(probe==null || probe.server!=owner || !owner.isOnThread() || !probe.profiles.containsKey(player.getUuid())) return null;
        probe.requireScope(); require(owner.getPlayerManager().getPlayer(player.getUuid())==player && player.getServer()==owner
                && owner.getWorld(player.getServerWorld().getRegistryKey())==player.getServerWorld()
                && probe.profiles.get(player.getUuid()).equals(player.getGameProfile()),"fault 的 native player/profile/world 不符");
        return probe;
    }

    private void requireScope() {
        try { require(server.isOnThread() && Path.of(".").toRealPath().equals(root)
                && server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()),"probe 失去 canonical server/world scope"); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private ChamberControllerBlockEntity controller() { return source.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity value ? value : null; }
    private ServerPlayerEntity player(UUID id) { return Objects.requireNonNull(server.getPlayerManager().getPlayer(id),"own player offline"); }
    private SessionRecoveryRecord record() { return sid==null ? null : SessionRecoveryState.get(server).flushedRecords().get(sid); }
    private PlayerRecoveryCheckpoint marker() { return new PlayerRecoveryCheckpoint(sid,kind.equals("keep")
            ? PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT : PlayerRecoveryCheckpoint.AppliedPolicy.RESTORE_ENTRY); }
    private static SessionRecoveryRecord.Participant participant(SessionRecoveryRecord record,UUID id) {
        return record.participants().stream().filter(person -> person.playerUuid().equals(id)).findFirst().orElseThrow();
    }
    private static Optional<PlayerRecoveryCheckpoint> markerFrom(NbtCompound nbt) {
        return nbt.contains(PlayerRecoveryCheckpoint.NBT_KEY) ? Optional.of(PlayerRecoveryCheckpoint.fromNbt(nbt.get(PlayerRecoveryCheckpoint.NBT_KEY))) : Optional.empty();
    }
    private static NbtCompound readNbt(Path path) throws IOException { return NbtIo.readCompressed(path,NbtSizeTracker.of(64L*1024*1024)); }
    private NbtCompound readOfficial(UUID id) throws IOException { return readNbt(server.getSavePath(WorldSavePath.PLAYERDATA).resolve(id+".dat")); }
    private static NbtElement effectNbt(ServerPlayerEntity player) { return player.hasStatusEffect(ModEffects.QUANTUM_STATE)
            ? player.getStatusEffect(ModEffects.QUANTUM_STATE).writeNbt() : new NbtCompound(); }
    private void copyOfficial(UUID id,String label) throws IOException {
        Files.copy(server.getSavePath(WorldSavePath.PLAYERDATA).resolve(id+".dat"),root.resolve(label+"-"+id+".dat"));
        Files.writeString(root.resolve(label+"-"+id+".effect.snbt"),effectNbt(player(id)).toString(),StandardOpenOption.CREATE_NEW);
    }
    private void witness(String label) throws IOException {
        var journal=server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat");
        Files.copy(journal,root.resolve(label+"-journal.dat"));
        Files.writeString(root.resolve(label+"-journal.snbt"),readNbt(journal).toString(),StandardOpenOption.CREATE_NEW);
        for(var id : profiles.keySet()) copyOfficial(id,label);
    }
    private void writeManifest() throws IOException {
        var data=new JsonObject(); data.addProperty("nonce",nonce); data.addProperty("kind",kind); data.addProperty("sid",sid.toString());
        data.addProperty("chamber",chamber.toString()); data.addProperty("a",a.toString()); if(b!=null) data.addProperty("b",b.toString());
        var people=new JsonArray(); for(var profile : profiles.values()) { var person=new JsonObject(); person.addProperty("uuid",profile.getId().toString());
            person.addProperty("name",profile.getName()); people.add(person); } data.add("profiles",people);
        Files.writeString(root.resolve("manifest.json"),data.toString(),StandardOpenOption.CREATE_NEW);
    }
    private void readManifest() throws IOException {
        var data=JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        require(nonce.equals(data.get("nonce").getAsString()) && kind.equals(data.get("kind").getAsString()),"manifest 身分不符");
        sid=UUID.fromString(data.get("sid").getAsString()); chamber=UUID.fromString(data.get("chamber").getAsString());
        a=UUID.fromString(data.get("a").getAsString()); b=data.has("b") ? UUID.fromString(data.get("b").getAsString()) : null;
        for(var value : data.getAsJsonArray("profiles")) { var person=value.getAsJsonObject(); var id=UUID.fromString(person.get("uuid").getAsString());
            profiles.put(id,new GameProfile(id,person.get("name").getAsString())); }
    }
    private void result(String name,String status,Map<String,?> values) throws IOException {
        var data=new LinkedHashMap<String,Object>(); data.put("status",status); data.put("phase",phase); data.put("kind",kind); data.put("nonce",nonce);
        data.put("pid",ProcessHandle.current().pid()); data.put("runRoot",root.toString()); data.put("sid",sid==null ? null : sid.toString());
        var durable=record(); data.put("journalState",durable==null ? "ABSENT" : durable.state().name());
        data.put("restorePolicy",durable==null ? null : durable.restoreEntryEffectOnReturn());
        data.put("durableReturnedA",durable==null || a==null ? null : participant(durable,a).returned());
        data.put("checkpointSuccesses",checkpointSuccesses); data.putAll(values);
        var path=root.resolve(phase+"-"+name+".json"); var temporary=root.resolve(phase+"-"+name+".tmp");
        Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(data),StandardOpenOption.CREATE_NEW);
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE); log("RESULT status="+status+" path="+path);
    }
    private static void require(boolean valid,String message) { if(!valid) throw new IllegalStateException(message); }
    private static void log(String message) { org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("M2_CHECKPOINT_PROBE {}",message); }
}
