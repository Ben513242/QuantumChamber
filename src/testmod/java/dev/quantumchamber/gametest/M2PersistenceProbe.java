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
    private final boolean normal=System.getProperty("quantumchamber.m2.phase")!=null;
    private final String phase=System.getProperty(normal ? "quantumchamber.m2.phase" : "quantumchamber.m2.checkpoint.phase");
    private final String kind=normal ? normalKind(phase) : System.getProperty("quantumchamber.m2.checkpoint.case");
    private final String nonce=System.getProperty("quantumchamber.m2.nonce");
    private final String startupNonce=System.getProperty("quantumchamber.m2.startupNonce");
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
    private UUID itemId;
    private boolean playerReturnRejected,entityReturnRejected,unreadySeen,itemLoaded;
    private int disconnectedAt=-1,joinedAt=-1,normalReturningAt=-1;
    private final Set<UUID> observedReturned=new HashSet<>();

    @Override public void onInitialize() {
        if(phase==null) return;
        if(normal && (!Set.of("active-save","active-resume-one","active-resume-last","return-disconnect-save","return-disconnect-resume",
                "arming-save","arming-resume-one","arming-resume-last","origin-missing-save","origin-missing-resume").contains(phase)
                && !phase.matches("legacy-(arming-true|super-false|return-true|return-false)-(save|resume|verify)")
                && !phase.equals("legacy-arming-true-halt")
                || startupNonce==null || !startupNonce.matches("[a-f0-9]{32}"))) throw new IllegalArgumentException("未知 M2 normal phase 或 startup nonce");
        if((!normal && (!Set.of("prepare","recover","verify").contains(phase) || !Set.of("keep","entry","stale").contains(kind)))
                || nonce==null || !nonce.matches("[a-f0-9]{32}") || startupNonce==null || !startupNonce.matches("[a-f0-9]{32}")) throw new IllegalArgumentException("未知或不完整 checkpoint probe phase");
        if(normal && !Set.of("milk","expiry").contains(System.getProperty("quantumchamber.m2.returnCause","milk"))) throw new IllegalArgumentException("未知返還原因");
        ServerLifecycleEvents.SERVER_STARTED.register(this::attach);
        if(normal) ServerTickEvents.START_SERVER_TICK.register(owner -> { if(owner==server && !finished) observeNormalLoad(); });
        ServerTickEvents.END_SERVER_TICK.register(this::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(owner -> {
            if(owner==server) {
                if(normal) log("M2_NORMAL STOPPED phase="+phase+" startupNonce="+startupNonce+" pid="+ProcessHandle.current().pid());
                active=null;
            }
        });
    }

    private void attach(MinecraftServer owner) {
        try {
            server=owner; source=owner.getOverworld(); root=Path.of(".").toRealPath();
            var expected=Path.of(System.getProperty(normal ? "quantumchamber.m2.root" : "quantumchamber.m2.checkpoint.root")).toRealPath();
            require(root.equals(expected) && root.getFileName().toString().equals((normal ? "m2-" : "m2-task4-")+kind+"-"+nonce),"canonical owned runRoot 不符");
            require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()),"真 storage 不是 own world");
            require(active==null && owner.isOnThread(),"probe 不可重複或跨執行緒 attach"); active=this;
            if(phase.equals("prepare") || normal && phase.endsWith("-save")) {
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
            if((phase.equals("prepare") || normal && phase.endsWith("-save")) && stage==-1) { build(); stage=0; return; }
            if(normal) { observeNormalProgress(); normalTick(); } else if(phase.equals("prepare")) prepare(); else recover();
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
        int count=normal || kind.equals("entry") || kind.equals("restore") ? 2 : 1;
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
            sid=record.sessionUuid(); require(record.state()==SessionState.ARMING && !record.restoreEntryEffectOnReturn()
                    && record.semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED,"先真新 native checked ARMING,false");
            require(record.participants().getFirst().playerUuid().equals(a),"真 frozen checkpoint 順序與 A 不符");
            writeManifest();
            if(kind.equals("entry")) {
                for(int i=0;i<40;i++) player(a).playerTick();
                require(player(a).getStatusEffect(ModEffects.QUANTUM_STATE).getDuration()==1160,"W2 ARMING 後 A 真倒數，不可退款 snapshot1200");
                log("W2_NATIVE_EFFECT_TICKS nativePlayerTicks=40 serverTickDelta=0 A=1160 B=1800");
            }
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
            int duration=player.getUuid().equals(a) ? kind.equals("restore") ? 2360 : kind.equals("keep") ? 860 : 1160 : 1800;
            int amplifier=player.getUuid().equals(a) ? kind.equals("restore") ? 1 : kind.equals("keep") ? 2 : 0 : 1;
            require(effect!=null && effect.getDuration()==duration && effect.getAmplifier()==amplifier,"重啟不得重套 entry 或覆寫新劑量");
            Path expected=root.resolve("window-"+player.getUuid()+".effect.snbt");
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
            require(probe.record()!=null && probe.record().state()==SessionState.ARMING && !probe.record().restoreEntryEffectOnReturn()
                    && probe.record().semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED,"W2 必須新 native checked ARMING,false");
            for(var person : probe.record().participants()) require(probe.player(person.playerUuid()).getServerWorld()==owner.getWorld(SuperpositionWorld.KEY)
                    && probe.player(person.playerUuid()).hasStatusEffect(ModEffects.QUANTUM_STATE),"W2 全群必須真 fixed 且保留 Buff");
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
        var probe=scoped(owner,player); if(probe==null) return;
        if(probe.normal) {
            probe.checkpointSuccesses++;
            if(probe.phase.equals("legacy-arming-true-halt") && player.getUuid().equals(probe.a)
                    && expected.equals(Optional.of(probe.marker())) && player.getServerWorld()==probe.source) {
                require(probe.record()!=null && probe.record().semantics()==SessionSemantics.LEGACY_FORWARD_CONSUMED
                        && probe.record().state()==SessionState.RETURNING && probe.record().restoreEntryEffectOnReturn(),"舊 RESTORE checkpoint 只接受正式 legacy RETURNING,true");
                probe.windowSaved=true;
            }
            return;
        }
        if(!probe.phase.equals("prepare")) return;
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
        if(probe==null || probe.server!=owner || !(probe.phase.equals("prepare") && probe.kind.equals("keep")
                || probe.phase.equals("legacy-arming-true-halt"))
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
    private PlayerRecoveryCheckpoint marker() { return new PlayerRecoveryCheckpoint(sid,!(kind.equals("restore") || kind.equals("legacy-arming-true") || kind.equals("legacy-return-true"))
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
        NbtElement effect;
        {
            effect=new NbtCompound();
            for(var value : readOfficial(id).getList("active_effects",NbtElement.COMPOUND_TYPE)) {
                var candidate=(NbtCompound)value;
                if(candidate.getString("id").equals("quantumchamber:quantum_state")) { effect=candidate; break; }
            }
        }
        Files.writeString(root.resolve(label+"-"+id+".effect.snbt"),effect.toString(),StandardOpenOption.CREATE_NEW);
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
        if(itemId!=null) data.addProperty("item",itemId.toString());
        var people=new JsonArray(); for(var profile : profiles.values()) { var person=new JsonObject(); person.addProperty("uuid",profile.getId().toString());
            person.addProperty("name",profile.getName()); people.add(person); } data.add("profiles",people);
        Files.writeString(root.resolve("manifest.json"),data.toString(),StandardOpenOption.CREATE_NEW);
    }
    private void readManifest() throws IOException {
        var data=JsonParser.parseString(Files.readString(root.resolve("manifest.json"))).getAsJsonObject();
        require(nonce.equals(data.get("nonce").getAsString()) && kind.equals(data.get("kind").getAsString()),"manifest 身分不符");
        sid=UUID.fromString(data.get("sid").getAsString()); chamber=UUID.fromString(data.get("chamber").getAsString());
        a=UUID.fromString(data.get("a").getAsString()); b=data.has("b") ? UUID.fromString(data.get("b").getAsString()) : null;
        itemId=data.has("item") ? UUID.fromString(data.get("item").getAsString()) : null;
        for(var value : data.getAsJsonArray("profiles")) { var person=value.getAsJsonObject(); var id=UUID.fromString(person.get("uuid").getAsString());
            profiles.put(id,new GameProfile(id,person.get("name").getAsString())); }
    }
    private void result(String name,String status,Map<String,?> values) throws IOException {
        var data=new LinkedHashMap<String,Object>(); data.put("status",status); data.put("phase",phase); data.put("kind",kind); data.put("nonce",nonce);
        data.put("pid",ProcessHandle.current().pid()); data.put("runRoot",root.toString()); data.put("sid",sid==null ? null : sid.toString());
        data.put("startupNonce",startupNonce);
        var durable=record(); data.put("journalState",durable==null ? "ABSENT" : durable.state().name());
        data.put("semantics",durable==null ? null : durable.semantics().name());
        data.put("restorePolicy",durable==null ? null : durable.restoreEntryEffectOnReturn());
        data.put("durableReturnedA",durable==null || a==null ? null : participant(durable,a).returned());
        data.put("checkpointSuccesses",checkpointSuccesses); data.putAll(values);
        var path=root.resolve(phase+"-"+name+".json"); var temporary=root.resolve(phase+"-"+name+".tmp");
        Files.writeString(temporary,new GsonBuilder().setPrettyPrinting().create().toJson(data),StandardOpenOption.CREATE_NEW);
        Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE); log("RESULT status="+status+" path="+path);
    }
    private static String normalKind(String value) {
        if(value.equals("legacy-arming-true-halt")) return "legacy-arming-true";
        if(value.matches("legacy-(arming-true|super-false|return-true|return-false)-(save|resume|verify)")) return value.substring(0,value.lastIndexOf('-'));
        if(value.startsWith("active-")) return "active-pending";
        if(value.startsWith("arming-")) return "arming-rollback";
        if(value.startsWith("return-disconnect-")) return "return-disconnect";
        if(value.startsWith("origin-missing-")) return "origin-missing";
        return "unknown";
    }

    /** 正常 phase 僅使用正式 world／playerdata；manifest 只有識別字，沒有可回填的 NBT。 */
    private void normalTick() throws Exception {
        if(kind.startsWith("legacy-")) { legacyTick(); return; }
        if(phase.endsWith("-save")) { normalPrepare(); return; }
        if(stage==0) {
            if(!kind.equals("origin-missing") && (controller()==null || !chamber.equals(controller().chamberUuid()))) return;
            require(record()!=null && record().state()==SessionState.RETURNING,"bootstrap 必須由正式 journal 恢復 RETURNING");
            require(record().semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED && !record().restoreEntryEffectOnReturn(),"新 native 重啟必須保留 lateral／KEEP_CURRENT");
            require(record().participants().size()==2,"離線者不可丟失凍結名單");
            require(ChamberSessions.gateway().presence(server,chamber)!=ChamberSessionGateway.Presence.ACTIVE,"不可重建舊 ACTIVE");
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
            var ids=kind.equals("origin-missing") ? List.of(a,b) : List.of(phase.endsWith("-one") ? a : b);
            for(var id : ids) {
                var before=readOfficial(id); var beforeProgress=participant(record(),id).returned();
                var connection=new ConnectedGameTestPlayer(source,profiles.get(id)); connections.add(connection);
                joinedAt=server.getTicks(); require(connection.joinTick()==joinedAt,"必須真 JOIN callback");
                require(participant(record(),id).returned()==beforeProgress,"JOIN callback 不得立即標記 returned");
                require(connection.player().getServerWorld().getRegistryKey().getValue().toString().equals(before.getString("Dimension")),"JOIN 必須正常 load 正式 Dimension");
                require(((PlayerRecoveryCheckpointAccess)connection.player()).quantumchamber$getRecoveryCheckpoint().equals(markerFrom(before)),"marker 必須正式 native load");
                connection.confirmTeleport();
                log("M2_NORMAL NATIVE_JOIN uuid="+id+" tick="+joinedAt+" dimension="+before.getString("Dimension")+" marker="+markerFrom(before));
            }
            stage=1; return;
        }
        require(server.getTicks()>joinedAt,"返還必須 JOIN 下一 tick");
        if(kind.equals("origin-missing")) {
            if(ticks<40) return;
            require(record()!=null && record().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned),"缺來源不能完成");
            for(var id : List.of(a,b)) require(player(id).getServerWorld()==server.getWorld(SuperpositionWorld.KEY),"缺來源不能去 spawn／床／其他世界");
            requireProtected(); normalReady(); return;
        }
        if(phase.endsWith("-one")) {
            if(record()==null || !participant(record(),a).returned()) return;
            require(!participant(record(),b).returned() && server.getPlayerManager().getPlayer(b)==null,"B offline 仍 pending");
            verifyReturned(a); requireProtected();
            if(itemId!=null && !itemInSource()) return;
            normalReady(); return;
        }
        if(!complete()) return;
        verifyReturned(b);
        if(kind.equals("arming-rollback")) {
            var previous=readNbt(root.resolve("arming-resume-one-formal").resolve(a+".dat"));
            require(readOfficial(a).equals(previous),"A 未重登不能被再次套用快照或改 duration");
        }
        if(itemId!=null) require(itemInSource(),"最後收尾五鑽石必須仍在原艙");
        normalReady();
    }

    private void normalPrepare() throws Exception {
        if(stage==0) {
            var controller=controller(); if(controller==null || controller.chamberUuid()==null) return;
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
            for(var connection : connections) if(!connection.player().hasStatusEffect(ModEffects.QUANTUM_STATE))
                connection.player().addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
            var preview=new ChamberActivationService().evaluateReadiness(source,controller);
            if(!preview.accepted() || preview.participantUuids().size()!=2) return;
            a=preview.participantUuids().getFirst(); b=preview.participantUuids().get(1); chamber=controller.chamberUuid();
            for(var id : List.of(a,b)) {
                player(id).removeStatusEffect(ModEffects.QUANTUM_STATE);
                player(id).addStatusEffect(id.equals(a) ? entryWithHidden()
                        : new StatusEffectInstance(ModEffects.QUANTUM_STATE,id.equals(a) ? 1200 : 1800,id.equals(a) ? 0 : 1));
                PlayerCheckpointStore.saveAndVerify(server,player(id),Optional.empty()); copyOfficial(id,"entry");
            }
            require(server.save(false,true,true),"原艙必須先真 save");
            source.setBlockState(frame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
            var nativeRecord=SessionRecoveryState.get(server).flushedRecords().values().stream()
                    .filter(value -> value.chamberUuid().equals(chamber)).findFirst().orElseThrow();
            sid=nativeRecord.sessionUuid();
            require(nativeRecord.state()==SessionState.ARMING && !nativeRecord.restoreEntryEffectOnReturn()
                    && nativeRecord.semantics()==SessionSemantics.LATERAL_BUFF_MAINTAINED,"真 powered activation 必須先 durable 新 ARMING,false");
            log("M2_NORMAL NATIVE_ARMING sid="+sid+" chamber="+chamber+" authority="+nativeRecord.origin()+" cohort="+List.of(a,b));
            stage=1;
            if(kind.equals("arming-rollback")) {
                tickCurrentEffects();
                writeManifest(); disconnect(b); disconnect(a);
                log("M2_NORMAL ARMING_GEOMETRY_WINDOW bothOffline=true consumed=false");
            }
            return;
        }
        if(kind.equals("arming-rollback")) {
            if(record().state()!=SessionState.RETURNING) return;
            require(!record().restoreEntryEffectOnReturn() && record().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned),"自然 ARMING 中斷仍 KEEP_CURRENT 與兩人 pending");
            requireProtected(); normalReady(); return;
        }
        if(stage==1) {
            if(record().state()!=SessionState.SUPERPOSITION || ChamberSessions.gateway().presence(server,chamber)!=ChamberSessionGateway.Presence.ACTIVE) return;
            for(var id : List.of(a,b)) require(player(id).getServerWorld()==server.getWorld(SuperpositionWorld.KEY)
                    && player(id).hasStatusEffect(ModEffects.QUANTUM_STATE),"全 cohort 必須真入場且保留 Buff");
            require(!record().restoreEntryEffectOnReturn(),"真 committed false");
            tickCurrentEffects();
            log("M2_NORMAL NATIVE_ACTIVE sid="+sid+" A="+player(a).getPos()+" B="+player(b).getPos());
            if(kind.equals("active-pending")) {
                var target=server.getWorld(SuperpositionWorld.KEY); var pos=player(a).getPos().add(0,0,2);
                var item=new net.minecraft.entity.ItemEntity(target,pos.x,pos.y,pos.z,new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND,5));
                item.setNoGravity(true); item.setVelocity(Vec3d.ZERO); item.setPickupDelay(32767); item.setNeverDespawn();
                require(target.spawnEntity(item),"五鑽石必須真 spawn"); itemId=item.getUuid();
                writeManifest(); disconnect(b);
            } else {
                writeManifest();
                if(kind.equals("origin-missing")) {
                    ChamberProtectionService.get().authorizedMutation(() -> source.setBlockState(frame.controllerPos(),Blocks.AIR.getDefaultState(),3));
                    log("M2_NORMAL TRUSTED_SOURCE_REMOVAL originalAuthority="+record().origin());
                }
                if(kind.equals("return-disconnect")) {
                    var person=player(a); String cause=System.getProperty("quantumchamber.m2.returnCause","milk");
                    int before=server.getTicks(), elapsed=0;
                    if(cause.equals("milk")) {
                        person.getInventory().setStack(8,new net.minecraft.item.ItemStack(net.minecraft.item.Items.MILK_BUCKET));
                        var remaining=person.getInventory().getStack(8).finishUsing(person.getServerWorld(),person);
                        require(remaining.isOf(net.minecraft.item.Items.BUCKET),"原生喝奶必須返回空桶");
                        person.getInventory().setStack(8,remaining);
                    } else {
                        while(person.hasStatusEffect(ModEffects.QUANTUM_STATE) && elapsed<2400) { person.playerTick(); elapsed++; }
                        require(elapsed==2360,"剩餘 2360 原生 ticks 必須自然到期");
                    }
                    require(!person.hasStatusEffect(ModEffects.QUANTUM_STATE),"任一 Buff 失效的真來源");
                    Files.writeString(root.resolve("return-current-"+a+".effect.snbt"),effectNbt(person).toString(),StandardOpenOption.CREATE_NEW);
                    log("M2_NORMAL BUFF_ENDED cause="+cause+" nativePlayerTicks="+elapsed+" serverTickDelta="+(server.getTicks()-before)+" high=true");
                } else {
                    source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
                    log("M2_NORMAL POWER_LOST tick="+server.getTicks());
                }
            }
            stage=2; return;
        }
        if(record()==null || record().state()!=SessionState.RETURNING) return;
        if(normalReturningAt<0) { normalReturningAt=server.getTicks(); log("M2_NORMAL RETURNING_PROTECTED tick="+normalReturningAt); }
        requireProtected();
        if(kind.equals("return-disconnect")) {
            if(disconnectedAt<0) {
                require(!participant(record(),b).returned() && player(b).getServerWorld()==server.getWorld(SuperpositionWorld.KEY),"Buff 失效後 B 必須尚未返回才可斷線");
                disconnect(b); return;
            }
            if(!participant(record(),a).returned()) return;
            require(!participant(record(),b).returned(),"B pending 不可消失"); verifyReturned(a); normalReady(); return;
        }
        if(kind.equals("origin-missing")) {
            if(server.getTicks()-normalReturningAt<20) return;
            for(var id : List.of(a,b)) require(player(id).getServerWorld()==server.getWorld(SuperpositionWorld.KEY),"缺來源不得不安全返還");
            require(record().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned),"缺來源仍 pending"); normalReady(); return;
        }
        if(!playerReturnRejected) return;
        var pins=dev.quantumchamber.corridor.CorridorPageManager.forServer(server).returnEntityPins(sid);
        if(pins.isEmpty()) return;
        require(pins.get().contains(a) && pins.get().contains(itemId),"保存前 FULL／loaded／ticking 的原生 pins 必須同時含 A 與鑽石");
        require(player(a).getServerWorld()==server.getWorld(SuperpositionWorld.KEY) && server.getPlayerManager().getPlayer(b)==null,"active-save A 在線 fixed、B 真 offline");
        require(record().participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned),"active-save 兩位都未 returned");
        require(itemAt(server.getWorld(SuperpositionWorld.KEY))!=null,"active-save 鑽石仍 fixed"); normalReady();
    }

    /** 舊世界相容來源與新 native activation 分開；只有正式 NBT 在下一 JVM 載入。 */
    private void legacyTick() throws Exception {
        if(phase.endsWith("-save")) {
            if(controller()==null || controller().chamberUuid()==null) return;
            source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
            require(SessionRecoveryState.get(server).flushedRecords().isEmpty(),"trusted legacy 不得經新 session factory");
            a=connections.get(0).player().getUuid(); b=connections.get(1).player().getUuid();
            chamber=controller().chamberUuid(); sid=UUID.randomUUID();
            boolean restore=kind.endsWith("true"), alreadyMarked=kind.startsWith("legacy-return-");
            var people=new ArrayList<SessionRecoveryRecord.Participant>();
            for(var id : List.of(a,b)) {
                var person=player(id); var effect=id.equals(a) ? entryWithHidden() : new StatusEffectInstance(ModEffects.QUANTUM_STATE,1800,1);
                person.addStatusEffect(effect);
                var snapshot=((NbtCompound)effect.writeNbt()).copy();
                people.add(new SessionRecoveryRecord.Participant(id,person.getPos(),person.getVelocity(),person.getYaw(),person.getPitch(),snapshot,false));
                NbtElement expected=snapshot;
                if(!restore || alreadyMarked && id.equals(a)) {
                    for(int i=0;i<40;i++) person.playerTick();
                    expected=effectNbt(person);
                } else person.removeStatusEffect(ModEffects.QUANTUM_STATE);
                if(alreadyMarked && id.equals(a)) ((PlayerRecoveryCheckpointAccess)person).quantumchamber$setRecoveryCheckpoint(marker());
                else require(person.teleport(server.getWorld(SuperpositionWorld.KEY),1000.5+(id.equals(b) ? 1 : 0),65,2000.5,Set.of(),person.getYaw(),person.getPitch()),"trusted fixture 原生teleport失敗");
                PlayerCheckpointStore.saveAndVerify(server,person,alreadyMarked && id.equals(a) ? Optional.of(marker()) : Optional.empty());
                Files.writeString(root.resolve("current-"+id+".effect.snbt"),expected.toString(),StandardOpenOption.CREATE_NEW);
            }
            require(server.save(false,true,true),"legacy source／玩家／fixed 必須先原生save");
            var origin=new ChamberOriginAuthority(chamber,source.getRegistryKey().getValue(),dev.quantumchamber.universe.DimensionRole.OVERWORLD,
                    frame.controllerPos(),Direction.NORTH,ChamberInstanceKind.ORIGIN);
            var state=kind.equals("legacy-arming-true") ? SessionState.ARMING : kind.equals("legacy-super-false") ? SessionState.SUPERPOSITION : SessionState.RETURNING;
            var legacy=new SessionRecoveryRecord(sid,chamber,origin,people,List.of(new SessionRecoveryRecord.SpaceLease(0,new BlockBox(997,64,1328,1003,70,2767))),state,restore,SessionSemantics.LEGACY_FORWARD_CONSUMED);
            M2LeaseBootstrapProbe.writeTrustedLegacy(server,root,legacy);
            writeManifest(); normalReady(); return;
        }
        if(stage==0) {
            if(controller()==null || !chamber.equals(controller().chamberUuid())) return;
            if(phase.endsWith("-resume") || phase.endsWith("-halt")) {
                var durable=record();
                require(durable!=null && durable.state()==SessionState.RETURNING && durable.semantics()==SessionSemantics.LEGACY_FORWARD_CONSUMED
                        && durable.restoreEntryEffectOnReturn()==kind.endsWith("true") && durable.participants().size()==2,"legacy 正式 loader 必須 return-only／完整cohort");
                var box=durable.spaceLeases().getFirst().bounds();
                require(box.getMinX()==997 && box.getMaxX()==1003 && box.getMinY()==64 && box.getMaxY()==70
                        && box.getMinZ()==1328 && box.getMaxZ()==2767,"legacy loader 不得旋轉舊 bounds");
                log("TASK10_LEGACY_NATIVE_LOAD semantics="+durable.semantics()+" policy="+marker().appliedPolicy()+" unmodifiedBounds="+box);
            } else require(record()==null,"legacy 第二次restart journal 必須已退休");
            for(var id : List.of(a,b)) {
                var formal=readOfficial(id); var connection=new ConnectedGameTestPlayer(source,profiles.get(id)); connections.add(connection);
                require(connection.joinTick()==server.getTicks() && connection.player().getServerWorld().getRegistryKey().getValue().toString().equals(formal.getString("Dimension"))
                        && ((PlayerRecoveryCheckpointAccess)connection.player()).quantumchamber$getRecoveryCheckpoint().equals(markerFrom(formal)),"legacy 必須正常 playerdata load／JOIN");
                connection.confirmTeleport();
                if(phase.endsWith("-verify")) verifyReturned(id);
                log("TASK10_LEGACY_NATIVE_JOIN uuid="+id+" formalMarker="+markerFrom(formal)+" effect="+effectNbt(connection.player()));
            }
            joinedAt=server.getTicks(); stage=1; return;
        }
        if(phase.equals("legacy-arming-true-halt")) {
            if(blockedFlushes==0) return;
            require(record()!=null && record().state()==SessionState.RETURNING && record().restoreEntryEffectOnReturn()
                    && record().semantics()==SessionSemantics.LEGACY_FORWARD_CONSUMED && !participant(record(),a).returned()
                    && windowSaved && player(a).getServerWorld()==source,"舊 W1 必須正式 checkpoint 成功且 durable returned=false");
            player(a).playerTick(); nativeTicks++;
            var effect=(NbtCompound)effectNbt(player(a));
            require(effect.getInt("duration")==2400-nativeTicks && effect.getCompound("hidden_effect").getInt("duration")==600-nativeTicks,"舊 W1 主／hidden 必須自然倒數，不能重套");
            if(nativeTicks==40) {
                PlayerCheckpointStore.saveAndVerify(server,player(a),Optional.of(marker()));
                Files.writeString(root.resolve("return-current-"+a+".effect.snbt"),effect.toString(),StandardOpenOption.CREATE_NEW);
                witness("window");
                result("ready","HALT_READY",Map.of("nativePlayerTicks",nativeTicks,"blockedFlushes",blockedFlushes,"legacySource","schema1-native-loader","serverTick",server.getTicks()));
                finished=true;
            }
            return;
        }
        if(server.getTicks()<=joinedAt || !complete()) return;
        for(var id : List.of(a,b)) verifyReturned(id);
        normalReady();
    }

    private void disconnect(UUID id) {
        var connection=connections.stream().filter(value -> value.player().getUuid().equals(id)).findFirst().orElseThrow();
        connection.disconnect(); require(connection.disconnectTick()==server.getTicks() && connection.disconnectOnThread()
                && server.getPlayerManager().getPlayer(id)==null,"必須 channel close→Fabric DISCONNECT→native removal");
        if(id.equals(b)) disconnectedAt=server.getTicks();
        log("M2_NORMAL NATIVE_DISCONNECT uuid="+id+" tick="+server.getTicks());
    }

    private void requireProtected() {
        require(record()!=null && !record().spaceLeases().isEmpty() && !ChamberProtectionService.get().mayMutate(source,frame.controllerPos()),"pending lease／來源保護必須保留");
    }

    private void requireNormalAuthority(SessionRecoveryRecord record) {
        require(record.sessionUuid().equals(sid) && record.chamberUuid().equals(chamber)
                && record.origin().chamberUuid().equals(chamber) && record.origin().worldKey().equals(source.getRegistryKey().getValue())
                && record.origin().controllerPos().equals(frame.controllerPos()) && record.origin().facing()==Direction.NORTH
                && record.origin().instanceKind()==ChamberInstanceKind.ORIGIN
                && record.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).collect(java.util.stream.Collectors.toSet()).equals(Set.of(a,b)),
                "normal witness 必須同一 frozen A+B／SID／原艙 authority");
    }

    private void observeNormalProgress() throws Exception {
        var durable=record(); if(durable==null) return;
        for(var person : durable.participants()) if(person.returned() && observedReturned.add(person.playerUuid())) {
            requireProtected();
            if(server.getPlayerManager().getPlayer(person.playerUuid())!=null) verifyReturned(person.playerUuid());
            var formal=readOfficial(person.playerUuid());
            require(markerFrom(formal).equals(Optional.of(marker())),"durable returned 必須對應正式 checkpoint");
            log("M2_NORMAL RETURNED_DURABLE uuid="+person.playerUuid()+" tick="+server.getTicks()+" protected=true formalMarker="+markerFrom(formal));
        }
    }

    private void verifyReturned(UUID id) throws Exception {
        var person=player(id);
        require(person.getServerWorld()==source && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),person.getBoundingBox()),"真原 world／完整 bbox 返還");
        require(((PlayerRecoveryCheckpointAccess)person).quantumchamber$getRecoveryCheckpoint().equals(Optional.of(marker())),"返還 marker SID／policy 必須相同");
        var expected=root.resolve("return-current-"+id+".effect.snbt");
        if(!Files.exists(expected)) expected=root.resolve("current-"+id+".effect.snbt");
        require(StringNbtReader.parse(Files.readString(expected)).equals(effectNbt(person)),
                "新 session 返還必須保留當下完整效果／hidden，不能退款 entry");
    }

    /** EmbeddedChannel 不輪詢 NetworkIo；這裡執行同一原生 playerTick，分別記錄效果與 server ticks。 */
    private void tickCurrentEffects() throws IOException {
        int before=server.getTicks();
        for(var id : List.of(a,b)) {
            var person=player(id);
            for(int i=0;i<40;i++) person.playerTick();
            var effect=person.getStatusEffect(ModEffects.QUANTUM_STATE);
            require(effect!=null && effect.getDuration()==(id.equals(a) ? 2360 : 1760),"40 次原生 tick 必須維持已流逝 duration");
            if(id.equals(a)) require(((NbtCompound)effect.writeNbt()).getCompound("hidden_effect").getInt("duration")==560,"hidden effect 也必須自然倒數 40");
            Files.writeString(root.resolve("current-"+id+".effect.snbt"),effect.writeNbt().toString(),StandardOpenOption.CREATE_NEW);
            log("M2_NORMAL NATIVE_EFFECT_TICKS uuid="+id+" nativePlayerTicks=40 serverTickDelta="+(server.getTicks()-before)+" effect="+effect.writeNbt());
        }
    }

    private net.minecraft.entity.ItemEntity itemAt(ServerWorld world) {
        if(itemId==null) return null;
        var entity=world.getEntity(itemId); if(entity==null) return null;
        require(entity instanceof net.minecraft.entity.ItemEntity,"相同 UUID 必須仍是 ItemEntity");
        var item=(net.minecraft.entity.ItemEntity)entity;
        require(item.getStack().isOf(net.minecraft.item.Items.DIAMOND) && item.getStack().getCount()==5,"正式 entity load 五鑽石數量不符");
        return item;
    }

    private boolean itemInSource() {
        var item=itemAt(source);
        return item!=null && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),item.getBoundingBox());
    }

    private void observeNormalLoad() {
        if(itemId==null || phase.endsWith("-save") || record()==null) return;
        var target=server.getWorld(SuperpositionWorld.KEY);
        boolean ready=true;
        for(var lease : record().spaceLeases()) {
            var bounds=lease.bounds();
            for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++) for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) {
                var chunk=new ChunkPos(x,z);
                ready &= target.getChunkManager().getWorldChunk(x,z)!=null && target.isChunkLoaded(chunk.toLong()) && target.shouldTick(chunk);
            }
        }
        if(!ready) {
            unreadySeen=true; requireProtected();
            require(dev.quantumchamber.corridor.CorridorPageManager.forServer(server).returnEntityPins(sid).isEmpty(),"未 FULL＋entityLoaded＋ticking 前不可把空查詢當 ready");
        }
        var item=itemAt(target);
        if(item!=null && !itemLoaded) {
            itemLoaded=true;
            log("M2_NORMAL NATIVE_ENTITY_LOAD uuid="+itemId+" count=5 world="+target.getRegistryKey().getValue()+" pose="+item.getPos()+" allLeasesReady="+ready+" unreadySeen="+unreadySeen);
        }
    }

    public static boolean normalRejectMove(net.minecraft.entity.Entity entity,ServerWorld target) {
        var probe=active;
        if(probe==null || !probe.normal || !probe.phase.equals("active-save") || probe.sid==null || probe.itemId==null
                || !probe.server.isOnThread() || target!=probe.source || entity.getWorld()!=probe.server.getWorld(SuperpositionWorld.KEY)
                || probe.server.getPlayerManager().getPlayer(probe.b)!=null || probe.disconnectedAt<0) return false;
        probe.requireScope(); var record=probe.record();
        if(record==null || record.state()!=SessionState.RETURNING || record.restoreEntryEffectOnReturn()
                || record.participants().stream().anyMatch(SessionRecoveryRecord.Participant::returned)) return false;
        probe.requireNormalAuthority(record);
        require(probe.player(probe.a).getServerWorld()==probe.server.getWorld(SuperpositionWorld.KEY),"窗口 A 必須在線 fixed");
        require(probe.itemAt(probe.server.getWorld(SuperpositionWorld.KEY))!=null,"窗口必須持有真五鑽石");
        var pins=dev.quantumchamber.corridor.CorridorPageManager.forServer(probe.server).returnEntityPins(probe.sid);
        require(pins.isPresent() && pins.get().contains(probe.a) && pins.get().contains(probe.itemId),"拒絕窗口必須使用全 lease ready 的真 A／item pins");
        boolean first;
        if(entity.getUuid().equals(probe.a)) { first=!probe.playerReturnRejected; probe.playerReturnRejected=true; }
        else if(entity.getUuid().equals(probe.itemId)) { first=!probe.entityReturnRejected; probe.entityReturnRejected=true; }
        else return false;
        if(first) log("M2_NORMAL TRUSTED_RETURN_REJECT uuid="+entity.getUuid()+" tick="+probe.server.getTicks()); return true;
    }

    /** 僅觀察正常 load 與真返還之間的同 tick 邊界；不改結果、實體或 NBT。 */
    public static void normalObserveEntityMove(net.minecraft.entity.Entity entity,ServerWorld target) {
        var probe=active;
        if(probe==null || !probe.normal || !probe.phase.equals("active-resume-one") || probe.itemId==null
                || !probe.itemId.equals(entity.getUuid()) || target!=probe.source) return;
        probe.requireScope();
        var fixed=probe.server.getWorld(SuperpositionWorld.KEY);
        require(entity.getWorld()==fixed && fixed.getEntity(probe.itemId)==entity,"必須真 fixed entity identity");
        require(probe.itemAt(fixed)==entity,"正式 load UUID／五鑽石必須相同");
        var record=probe.record();
        require(record!=null && record.state()==SessionState.RETURNING && !record.restoreEntryEffectOnReturn(),"真返還仍使用 durable false SID");
        probe.requireNormalAuthority(record);
        var pins=dev.quantumchamber.corridor.CorridorPageManager.forServer(probe.server).returnEntityPins(probe.sid);
        require(pins.isPresent() && pins.get().contains(probe.itemId),"返還前完整 held lease 必須 FULL／entityLoaded／ticking 且原生 pins 有相同 item");
        probe.itemLoaded=true;
        log("M2_NORMAL NATIVE_ENTITY_LOAD_BEFORE_RETURN uuid="+probe.itemId+" count=5 world="+fixed.getRegistryKey().getValue()
                +" pose="+entity.getPos()+" tick="+probe.server.getTicks()+" allLeasesReady=true unreadySeen="+probe.unreadySeen);
    }

    private void normalReady() throws Exception {
        for(var profile : profiles.values()) {
            var player=server.getPlayerManager().getPlayer(profile.getId());
            if(player!=null) PlayerCheckpointStore.saveAndVerify(server,player,Optional.empty());
        }
        require(server.save(false,true,true),"phase 必須原生 save"); witness(phase);
        if(itemId!=null) {
            var item=itemAt(server.getWorld(SuperpositionWorld.KEY)); if(item==null) item=itemAt(source);
            require(item!=null,"ready 不可遺失五鑽石");
            Files.writeString(root.resolve(phase+"-item.snbt"),item.writeNbt(new NbtCompound()).toString(),StandardOpenOption.CREATE_NEW);
            if(phase.equals("active-resume-one")) require(itemLoaded && unreadySeen && itemInSource(),"重啟必須觀測未就緒→正式 entity load→同 UUID 安全返還");
        }
        var values=new LinkedHashMap<String,Object>();
        values.put("chamber",chamber.toString()); values.put("a",a.toString()); values.put("b",b.toString());
        values.put("authority",record()==null ? "retired" : record().origin().toString());
        values.put("durableReturnedB",record()==null ? null : participant(record(),b).returned());
        values.put("protected",!ChamberProtectionService.get().mayMutate(source,frame.controllerPos()));
        values.put("playerReturnRejected",playerReturnRejected); values.put("entityReturnRejected",entityReturnRejected);
        if(phase.equals("active-save")) values.put("orderedNativePins",dev.quantumchamber.corridor.CorridorPageManager.forServer(server).returnEntityPins(sid).orElseThrow());
        values.put("item",itemId==null ? null : itemId.toString()); values.put("itemLoaded",itemLoaded); values.put("unreadySeen",unreadySeen);
        values.put("disconnectTick",disconnectedAt); values.put("joinTick",joinedAt); values.put("readyTick",server.getTicks());
        if(kind.startsWith("legacy-") && phase.endsWith("-save")) {
            require(SessionRecoveryState.get(server).records().isEmpty(),"legacy seed runtime 不得接管fixture");
            values.put("trustedLegacySchema",1);
            values.put("trustedJournalSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve("trusted-legacy-journal.dat")))));
        }
        result("ready","PASS",values); finished=true;
        log("M2_PHASE_READY_FOR_STOP phase="+phase+" startupNonce="+startupNonce);
    }

    private static void require(boolean valid,String message) { if(!valid) throw new IllegalStateException(message); }
    private static void log(String message) { org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("M2_CHECKPOINT_PROBE {}",message); }
}
