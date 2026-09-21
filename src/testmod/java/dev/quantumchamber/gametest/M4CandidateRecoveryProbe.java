package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.registry.*;
import dev.quantumchamber.superposition.*;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.*;
import net.minecraft.util.math.*;

/** 預設關閉的獨立程序探針；只使用 nonce-owned root，不修改已測量的 selection receipt。 */
public final class M4CandidateRecoveryProbe implements ModInitializer {
    private static final Gson JSON=new Gson();
    private static final String OWNER=".superpowers/sdd/2026-09-21-m4-candidate-doors";
    private static final Identifier BEFORE=Identifier.of("quantumchamber:m4_recovery_before"),AFTER=Identifier.of("quantumchamber:m4_recovery_after");
    private static M4CandidateRecoveryProbe active;
    private final Map<String,Object> proof=new LinkedHashMap<>();
    private final List<String> failures=new ArrayList<>();
    private final List<ConnectedGameTestPlayer> players=new ArrayList<>();
    private final Set<Identifier> savedRecoveryWorlds=new HashSet<>();
    private final ChamberFrame frame=new ChamberFrame(new BlockPos(8,70,8),Direction.NORTH);
    private Configuration config;
    private MinecraftServer server;
    private ServerWorld source,target;
    private CorridorPageManager pages;
    private SessionRecoveryState journal;
    private SessionRecoveryRecord original;
    private UUID sid,chamber;
    private Path damaged;
    private String damagedHash,catalogHash,entropyHash;
    private int stage,ticks,faultCount,selectedTick;
    private boolean finished,passed,freezeSave;

    @Override public void onInitialize() {
        config=Configuration.read(); if(config==null) return;
        active=this;
        proof.put("producer","M4CandidateRecoveryProbe"); proof.put("phase",config.phase()); proof.put("nonce",config.nonce());
        proof.put("startupNonce",config.startupNonce()); proof.put("pid",ProcessHandle.current().pid());
        proof.put("startTimeUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("root",config.root().toString()); proof.put("failures",failures);
        event("initialized");
        if(config.phase().equals("journal-corrupt")) guarded(() -> {
            damaged=config.root().resolve("world/data/quantumchamber_sessions.dat");
            require(!Files.exists(damaged),"corrupt fixture 必須來自 fresh root");
            Files.createDirectories(damaged.getParent()); Files.write(damaged,new byte[]{11,22,33,44});
            damagedHash=hash(damaged); freezeSave=true;
        });
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(BEFORE,Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STARTED.register(BEFORE,this::beforeStarted);
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE,AFTER);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER,this::started);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE,AFTER);
        ServerTickEvents.END_SERVER_TICK.register(AFTER,this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE,AFTER);
        ServerLifecycleEvents.SERVER_STOPPING.register(AFTER,owner -> {
            if(owner!=server) return;
            proof.put("stoppingSeen",true);
            for(var player : players) player.close(); players.clear();
        });
        ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE,AFTER);
        ServerLifecycleEvents.SERVER_STOPPED.register(AFTER,this::stopped);
    }

    private void beforeStarted(MinecraftServer owner) {
        server=owner;
        guarded(() -> {
            require(owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world").toRealPath()),"storage root 不符");
            if(config.phase().equals("journal-corrupt")) {
                var unhealthy=SessionRecoveryState.get(owner); boolean rejected=false;
                try { unhealthy.requireHealthy(); } catch(IllegalStateException expected) { rejected=true; }
                require(rejected,"損壞 journal 必須拒絕 authority");
                proof.put("controlledUnhealthy",true); proof.put("noTransientDoorOrSelection",true);
                require(UniverseRegistryState.get(owner).records().isEmpty() && !Files.exists(data("quantumchamber_universes.dat")),"journal bootstrap fault 不能配置 Universe");
                proof.put("noUniverseAllocation",true); proof.put("bootstrapRejectedBeforeSpaces",M4CandidateTestAccess.get(CorridorPageManager.class,"active")==null);
                passed=true; stop(); return;
            }
            if(Set.of("recover","verify").contains(config.phase())) {
                journal=SessionRecoveryState.get(owner); original=journal.flushedRecords().values().iterator().next();
                sid=original.sessionUuid(); chamber=original.chamberUuid();
                var expected=NbtIo.readCompressed(config.root().resolve("selected-receipt.dat"),NbtSizeTracker.ofUnlimitedBytes());
                var frozen=SessionRecoveryState.fromNbt(expected).flushedRecords().get(sid);
                require(frozen!=null && sameReceipt(frozen,original),"跨 JVM candidate context／ledger／selection 改變");
                require(original.state()==SessionState.MEASURED,"重啟前必須已 MEASURED");
                proof.put("receiptExactBeforeBootstrap",true);
                var manifest=JsonParser.parseString(Files.readString(config.root().resolve("manifest.json"))).getAsJsonObject();
                catalogHash=manifest.get("catalogHash").getAsString(); entropyHash=manifest.get("entropyHash").getAsString();
                require(hash(data("quantumchamber_universes.dat")).equals(catalogHash)
                        && hash(data("quantumchamber_candidate_entropy.dat")).equals(entropyHash),"跨 JVM catalog／entropy bytes 改變");
                if(config.phase().equals("verify")) require(MeasuredRecoveryPhase.from(original)==MeasuredRecoveryPhase.DORMANT,"第三 JVM 必須 dormant");
            }
        });
    }
    private void started(MinecraftServer owner) {
        if(owner!=server || finished) return;
        guarded(() -> {
            source=owner.getOverworld(); target=owner.getWorld(SuperpositionWorld.KEY);
            pages=CorridorPageManager.forServer(owner); journal=SessionRecoveryState.get(owner);
            if(config.phase().equals("verify")) {
                require(M4CandidateTestAccess.space(pages,sid)==null,"dormant 不能重建 geometry／ticket space");
                require(((Map<?,?>)M4CandidateTestAccess.get(ChamberSessions.gateway(),"tickets")).isEmpty(),"dormant 不能重建來源票");
            } else if(config.phase().equals("recover")) {
                require(pages.measuredRecoveryRequested(sid),"active MEASURED restart 未請求 recovery");
                require(M4CandidateTestAccess.space(pages,sid)!=null,"active MEASURED restart 必須恢復租約");
                proof.put("leasesAndTicketsRestored",!((Map<?,?>)M4CandidateTestAccess.get(M4CandidateTestAccess.space(pages,sid),"ticketRefs")).isEmpty());
            } else require(journal.flushedRecords().isEmpty(),"建立 fixture 前 journal 必須為空");
            catalogHash=catalogHash==null ? hash(data("quantumchamber_universes.dat")) : catalogHash;
        });
    }

    private void tick(MinecraftServer owner) {
        if(owner!=server || finished) return;
        guarded(() -> {
            require(++ticks<2400,"probe 有界等待逾時，stage="+stage);
            if(config.phase().equals("verify")) {
                verifyDormant();
                if(ticks>=5) { proof.put("dormantRepeatedTicks",ticks); success(); }
                return;
            }
            if(config.phase().equals("recover")) { recovering(); return; }
            if(stage==0) { build(); stage=1; return; }
            if(stage==1) { arm(); return; }
            if(faultCount>0) { verifyDirty(); return; }
            var record=record();
            if(record==null) return;
            if(stage==2 && Set.of("entropy-missing","entropy-corrupt","discovery-corrupt").contains(config.phase())) {
                corruptAuthority(); stage=3; return;
            }
            if(Set.of("entropy-missing","entropy-corrupt","discovery-corrupt").contains(config.phase()) && stage==3) {
                if(record.state()==SessionState.RETURNING) {
                    require(record.candidateLedger().isEmpty() && record.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable,
                            "authority fault 不得產生 transient candidate／selection");
                    require(pages.selectableDoors(sid).isEmpty(),"authority fault 不得發布側門");
                    proof.put("controlledUnhealthy",true); proof.put("noTransientDoorOrSelection",true); success();
                }
                return;
            }
            if(stage==2 && record.state()==SessionState.SUPERPOSITION && pages.selectableDoors(sid).size()==358) {
                var key=new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL);
                var view=pages.currentMappings(sid).instances().getFirst();
                var pos=cell(view,key); var player=players.getFirst().player();
                var result=target.getBlockState(pos).onUse(target,player,new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(pos),Direction.UP,pos,false));
                if(config.phase().equals("dirty-selection")) {
                    require(result==ActionResult.FAIL && faultCount>0,"dirty SELECTED 必須拒絕成功 action"); verifyDirty(); return;
                }
                require(result==ActionResult.SUCCESS && record().state()==SessionState.MEASURED,"原生側門未 checked SELECTED");
                original=record(); selectedTick=owner.getTicks(); stage=4;
                require(!pages.measuredRecoveryRequested(sid),"正常測量不能立即返還");
                return;
            }
            if(stage==4 && owner.getTicks()>selectedTick+2) {
                require(record().equals(original) && !pages.currentMappings(sid).instances().isEmpty(),"正常 MEASURED 應保持 receipt／mapping");
                if(config.phase().equals("select")) { saveSelected(); success(); return; }
                if(config.phase().equals("low")) source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
                else if(config.phase().equals("buff")) players.getFirst().player().removeStatusEffect(ModEffects.QUANTUM_STATE);
                else if(config.phase().equals("disconnect")) { players.getFirst().disconnect(); players.clear(); }
                else throw new IllegalStateException("未知 phase");
                stage=5; return;
            }
            if(stage==5) {
                require(record().state()==SessionState.MEASURED && sameReceipt(original,record()),"觸發返還不能重設 receipt");
                if(config.phase().equals("disconnect") && players.isEmpty() && owner.getTicks()>selectedTick+5) {
                    var person=original.participants().getFirst();
                    require(!record().participants().getFirst().returned(),"離線玩家不能提前 returned");
                    join(person.playerUuid()); proof.put("offlineWaitedUntilJoin",true);
                }
                if(pages.measuredRecoveryComplete(sid)) { verifyDormant(); success(); }
            }
        });
    }

    private void build() {
        source.getChunk(0,0); M1FoundationSessionFixture.set(source,frame.controllerPos(),false);
        ChamberProtectionService.get().authorizedMutation(() -> {
            for(int x=0;x<7;x++) for(int y=0;y<7;y++) for(int z=0;z<7;z++) {
                boolean shell=x==0 || x==6 || y==0 || y==6 || z==0 || z==6;
                var block=shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if(z==0 && x>0 && x<6 && y>0 && y<6) block=ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                source.setBlockState(ChamberGeometry.localToWorld(frame,x,y,z),block,3);
            }
            source.setBlockState(frame.controllerPos(),ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING,Direction.NORTH),3);
        });
        source.setBlockState(frame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
        var connection=new ConnectedGameTestPlayer(source); players.add(connection); connection.confirmTeleport();
        var player=connection.player(); player.setNoGravity(true);
        player.refreshPositionAndAngles(CorridorGeometry.position(frame,2.5,1,2.5),17,9);
    }
    private void arm() throws Exception {
        var controller=controller(); if(controller==null || controller.chamberUuid()==null) return;
        source.setBlockState(frame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
        var player=players.getFirst().player();
        if(!player.hasStatusEffect(ModEffects.QUANTUM_STATE)) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
        if(!new ChamberActivationService().evaluateReadiness(source,controller).accepted()) return;
        chamber=controller.chamberUuid(); PlayerCheckpointStore.saveAndVerify(server,player,Optional.empty());
        require(server.save(false,true,true),"native source save 失敗");
        source.setBlockState(frame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
        var initial=journal.flushedRecords().values().stream().filter(value -> value.chamberUuid().equals(chamber)).findFirst().orElseThrow();
        sid=initial.sessionUuid(); require(initial.state()==SessionState.ARMING,"缺少 durable ARMING"); stage=2;
    }
    private void join(UUID id) {
        var connection=new ConnectedGameTestPlayer(source,new GameProfile(id,"m4-"+id.toString().substring(0,8))); players.add(connection);
        connection.confirmTeleport(); connection.player().setNoGravity(true);
        require(connection.joinTick()>=0 && connection.player().getServerWorld()==target,"必須真 JOIN 載入 corridor playerdata");
        require(SessionRecoveryManager.blocks(id,server),"尚未返還的 MEASURED 玩家必須被攔截");
        proof.put("nativeJoinObserved",true); proof.put("measuredParticipantBlocked",true);
    }
    private void recovering() throws Exception {
        if(stage==0) {
            // 至少等待三 tick，證明離線 pending 不會自行標記為返還。
            if(ticks<3) return;
            require(record().participants().stream().anyMatch(person -> !person.returned()),"離線 restart 不能自動 returned");
            join(original.participants().getFirst().playerUuid()); stage=1; return;
        }
        require(record().state()==SessionState.MEASURED && sameReceipt(original,record()),"recovery 改變測量收據");
        if(pages.measuredRecoveryComplete(sid)) { verifyDormant(); success(); }
    }
    private void verifyDormant() throws Exception {
        var current=record(); require(current!=null && MeasuredRecoveryPhase.from(current)==MeasuredRecoveryPhase.DORMANT,"缺少 durable dormant receipt");
        require(sameReceipt(original,current) && current.equals(journal.records().get(sid)),"retained receipt exact authority 不符");
        require(M4CandidateTestAccess.space(pages,sid)==null && ((Map<?,?>)M4CandidateTestAccess.get(pages,"protectedLeases")).isEmpty(),"geometry runtime／protection 未清除");
        require(((Map<?,?>)M4CandidateTestAccess.get(ChamberSessions.gateway(),"tickets")).isEmpty(),"來源票未清除");
        require(!((Set<?>)M4CandidateTestAccess.get(pages,"completedReleases")).contains(sid),"不能產生普通刪除式收據");
        require(ChamberSessions.gateway().presence(server,chamber)!=ChamberSessionGateway.Presence.NONE,"同 Chamber 仍須阻擋");
        if(!players.isEmpty()) {
            var player=players.getFirst().player();
            require(player.getServerWorld()==source && source.getEntity(player.getUuid())==player
                    && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),player.getBoundingBox()),"未真正回同一來源艙");
            require(((PlayerRecoveryCheckpointAccess)player).quantumchamber$getRecoveryCheckpoint().equals(Optional.of(
                    new PlayerRecoveryCheckpoint(sid,PlayerRecoveryCheckpoint.AppliedPolicy.KEEP_CURRENT))),"缺少玩家 native checkpoint");
            proof.put("playerReturnedToSource",true); proof.put("nativeCheckpointRetained",true);
        }
        if(!config.phase().equals("verify")) {
            for(var lease : original.spaceLeases()) {
                var b=lease.bounds();
                for(var pos : BlockPos.iterate(b.getMinX(),b.getMinY(),b.getMinZ(),b.getMaxX(),b.getMaxY(),b.getMaxZ()))
                    require(target.getBlockState(pos).isAir(),"原 geometry 仍有方塊");
            }
            require(!ChamberSessions.gateway().returnToOrigin(source,controller()),"dormant LOW 不能回傳可解除保護");
        }
        proof.put("geometryAndTicketsReleased",true); proof.put("receiptRetained",true); proof.put("chamberBlocked",true);
        proof.put("finalRecoveryPhase","DORMANT");
    }

    private void saveSelected() throws Exception {
        require(server.save(false,true,true),"native measured save 失敗");
        for(var player : players) PlayerCheckpointStore.saveAndVerify(server,player.player(),Optional.empty());
        NbtIo.writeCompressed(journal.writeNbt(new NbtCompound()),config.root().resolve("selected-receipt.dat"));
        entropyHash=hash(data("quantumchamber_candidate_entropy.dat"));
        var manifest=new LinkedHashMap<String,Object>(); manifest.put("sessionUuid",sid.toString()); manifest.put("chamberUuid",chamber.toString());
        manifest.put("catalogHash",catalogHash); manifest.put("entropyHash",entropyHash); manifest.put("journalHash",hash(data("quantumchamber_sessions.dat")));
        manifest.put("candidateIds",original.candidateLedger().stream().map(entry -> entry.candidate().candidateId().toString()).toList());
        write(config.root().resolve("manifest.json"),JSON.toJson(manifest)); proof.putAll(manifest); proof.put("flushedSelected",true);
    }
    private void corruptAuthority() throws Exception {
        damaged=data(config.phase().startsWith("entropy") ? "quantumchamber_candidate_entropy.dat" : "quantumchamber_universe_discovery.dat");
        require(Files.isRegularFile(damaged),"fault 必須破壞本 root 已 checked 建立的 authority");
        Files.copy(damaged,config.evidence().resolve("authority-before.dat"));
        if(config.phase().equals("entropy-missing")) Files.move(damaged,config.evidence().resolve("removed-entropy.dat"));
        else Files.write(damaged,new byte[]{41,42,43,44});
        damagedHash=hash(damaged); proof.put("damagedAuthorityHash",damagedHash);
    }
    public static void beforeJournalFlush(SessionRecoveryState state,MinecraftServer owner) {
        var probe=active;
        if(probe==null || owner!=probe.server || probe.sid==null || probe.finished) return;
        var previous=state.flushedRecords().get(probe.sid); var next=state.records().get(probe.sid);
        if(previous==null || next==null) return;
        if(previous.state()==SessionState.MEASURED && !previous.spaceLeases().isEmpty() && next.spaceLeases().isEmpty()) {
            boolean saved=probe.savedRecoveryWorlds.contains(previous.origin().worldKey())
                    && probe.savedRecoveryWorlds.contains(SuperpositionWorld.KEY.getValue());
            probe.proof.put("nativeWorldsSavedBeforeEmptyLease",saved);
            if(!saved) {
                probe.freezeSave=true;
                probe.guarded(() -> require(false,"空 lease receipt 前缺少原生 source／geometry 同步存檔"));
                throw new IllegalStateException("缺少 durable geometry 存檔證據");
            }
        }
        boolean window=probe.config.phase().equals("dirty-candidate") && next.candidateLedger().size()>previous.candidateLedger().size()
                || probe.config.phase().equals("dirty-selection") && next.state()==SessionState.MEASURED && previous.state()!=SessionState.MEASURED;
        if(!window && !probe.freezeSave) return;
        probe.faultCount++; probe.freezeSave=true;
        if(probe.original==null) probe.original=previous;
        throw new IllegalStateException("M4 owned-root 受控 dirty journal crash window");
    }
    public static void afterWorldSave(ServerWorld world) {
        var probe=active;
        if(probe==null || probe.server!=world.getServer() || probe.journal==null || probe.sid==null || probe.finished) return;
        var record=probe.record();
        if(record!=null && record.state()==SessionState.MEASURED && !record.spaceLeases().isEmpty()
                && record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned))
            probe.savedRecoveryWorlds.add(world.getRegistryKey().getValue());
    }
    public static boolean suppressJournalSave(Path path) {
        var probe=active;
        return probe!=null && probe.freezeSave && path.toAbsolutePath().normalize().equals(probe.data("quantumchamber_sessions.dat").toAbsolutePath().normalize());
    }
    private void verifyDirty() throws Exception {
        require(original.equals(record()) && journal.isDirty(),"dirty window 必須保留上一份 flushed authority");
        var current=journal.records().get(sid);
        require(!current.equals(original),"必須真建立 dirty current");
        require(record().candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable,"dirty selection 不得成為 durable SELECTED");
        require(pages.selectableDoors(sid).isEmpty(),"dirty window 不得發布 selectable door");
        var disk=SessionRecoveryState.fromNbt(SessionJournalStore.read(data("quantumchamber_sessions.dat")).getCompound("data"));
        require(original.equals(disk.flushedRecords().get(sid)),"正式 journal 不得包含 transient candidate/selection");
        proof.put("flushedOnly",true); proof.put("controlledUnhealthy",true); proof.put("noTransientDoorOrSelection",true);
        proof.put("faultCount",faultCount); success();
    }
    private void success() throws Exception {
        require(UniverseRegistryState.get(server).records().isEmpty() && M4CandidateTestAccess.runtime(server).isEmpty()
                && hash(data("quantumchamber_universes.dat")).equals(catalogHash),"M4 probe 不得配置／物化 Universe");
        if(damaged!=null) require(hash(damaged).equals(damagedHash),"損壞 authority bytes 被覆寫");
        proof.put("noUniverseAllocation",true); proof.put("damagedBytesPreserved",damaged==null || hash(damaged).equals(damagedHash));
        proof.put("candidateCount",sid==null ? 0 : record().candidateLedger().size());
        passed=true; stop();
    }
    private void stop() { finished=true; proof.put("stopRequested",true); server.stop(false); }
    private void stopped(MinecraftServer owner) {
        if(owner!=server) return;
        guarded(() -> {
            proof.put("stoppedSeen",true);
            if(damaged!=null) require(hash(damaged).equals(damagedHash),"正常停止覆寫損壞 bytes");
            proof.put("damagedBytesPreserved",true);
            if(sid!=null && !config.phase().startsWith("entropy") && !config.phase().equals("discovery-corrupt"))
                proof.put("finalJournalHash",hash(data("quantumchamber_sessions.dat")));
            if(config.phase().equals("verify")) require(hash(data("quantumchamber_candidate_entropy.dat")).equals(entropyHash),"第三 JVM 重抽 entropy");
        });
        proof.put("status",passed && failures.isEmpty() && Boolean.TRUE.equals(proof.get("stoppingSeen")) ? "PASS" : "FAIL");
        write(config.evidence().resolve("final.json"),JSON.toJson(proof)); event("stopped"); active=null;
    }
    private void guarded(Checked action) {
        try { action.run(); }
        catch(Exception | AssertionError failure) {
            failures.add(failure.toString()); org.slf4j.LoggerFactory.getLogger("quantumchamber-m4-recovery").error("M4 recovery probe 失敗",failure);
            event("failure"); if(server!=null) stop();
        }
    }
    private void event(String name) {
        try { Files.writeString(config.evidence().resolve("events.jsonl"),JSON.toJson(Map.of("event",name,"timeUtc",Instant.now().toString(),
                "startupNonce",config.startupNonce(),"pid",ProcessHandle.current().pid()))+System.lineSeparator(),StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    private SessionRecoveryRecord record() { return journal.flushedRecords().get(sid); }
    private ChamberControllerBlockEntity controller() { return source.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity c ? c : null; }
    private Path data(String file) { return config.root().resolve("world/data").resolve(file); }
    private static boolean sameReceipt(SessionRecoveryRecord a,SessionRecoveryRecord b) {
        return a.sessionUuid().equals(b.sessionUuid()) && a.origin().equals(b.origin()) && a.candidateContext().equals(b.candidateContext())
                && a.candidateLedger().equals(b.candidateLedger()) && a.candidateSelection().equals(b.candidateSelection());
    }
    private static BlockPos cell(CorridorPageManager.MappingView view,DoorKey key) {
        var frame=new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),3).up(6),view.outwardFacing());
        return CorridorGeometry.block(frame,0,1,Math.toIntExact(key.logicalStationIndex()*8+1-view.logicalAnchorBlock()));
    }
    private static String hash(Path path) throws Exception {
        return Files.exists(path) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) : "ABSENT";
    }
    private static void write(Path path,String text) {
        try { Files.writeString(path,text,StandardOpenOption.CREATE_NEW); } catch(IOException failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private record Configuration(String phase,String nonce,String startupNonce,Path root,Path evidence) {
        static Configuration read() {
            String prefix="quantumchamber.m4recovery.";
            String phase=System.getProperty(prefix+"phase",""),nonce=System.getProperty(prefix+"nonce",""),startup=System.getProperty(prefix+"startupNonce","");
            String supplied=System.getProperty(prefix+"root","");
            if(!Set.of("select","recover","verify","low","buff","disconnect","dirty-candidate","dirty-selection","entropy-missing","entropy-corrupt","discovery-corrupt","journal-corrupt").contains(phase)
                    || !nonce.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || nonce.equals("disabled") || !startup.matches("[0-9a-f]{32}") || supplied.isBlank()) return null;
            try {
                var root=Path.of(supplied).toRealPath();
                if(!Path.of(supplied).isAbsolute() || !root.equals(Path.of(supplied).normalize()) || !root.equals(Path.of("").toRealPath())
                        || !root.getFileName().toString().equals("m4-recovery-"+nonce) || !root.getParent().getFileName().toString().equals("run")) return null;
                var evidence=root.getParent().getParent().resolve(OWNER).resolve("recovery-"+nonce).resolve(phase);
                if(!evidence.toRealPath().equals(evidence) || Files.exists(evidence.resolve("final.json")) || Files.exists(evidence.resolve("events.jsonl"))) return null;
                return new Configuration(phase,nonce,startup,root,evidence);
            } catch(IOException | RuntimeException invalid) { return null; }
        }
    }
}
