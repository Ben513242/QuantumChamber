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
    private static volatile NativeFault nativeFault;
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
    private boolean outerSaveReturned;
    // whole-branch fix1：selection checked 提交失敗窗口與 DORMANT 參與者另一座 Chamber 的跨 JVM 證據。
    private static final Set<String> SELECTION_FAULTS=Set.of("selection-flush-fault","readback-fault","readback-crash");
    private final ChamberFrame frame2=new ChamberFrame(new BlockPos(24,70,8),Direction.NORTH);
    private SessionRecoveryRecord preFault,diskAtReadback,dormantReceipt;
    private NbtCompound diskJournalAtReadback;
    private boolean selectionFaultFired,readbackArmed;
    private UUID chamber2,sid2;
    private List<?> runtimeBeforeSecond;

    @Override public void onInitialize() {
        config=Configuration.read(); if(config==null) { M4GameTestBoundaryProbe.register(); return; }
        active=this;
        proof.put("producer","M4CandidateRecoveryProbe"); proof.put("phase",config.phase()); proof.put("nonce",config.nonce());
        proof.put("startupNonce",config.startupNonce()); proof.put("pid",ProcessHandle.current().pid());
        proof.put("startTimeUtc",ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("root",config.root().toString()); proof.put("failures",failures);
        proof.put("runtimeScope","testmod-present");
        proof.put("loadedModIds",net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId()).sorted().toList());
        proof.put("javaClassPath",System.getProperty("java.class.path"));
        proof.put("jvmArguments",java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
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
            proof.put("universeBefore",universeEvidence());
            if(config.phase().equals("journal-corrupt")) {
                var unhealthy=SessionRecoveryState.get(owner); boolean rejected=false;
                try { unhealthy.requireHealthy(); } catch(IllegalStateException expected) { rejected=true; }
                require(rejected,"損壞 journal 必須拒絕 authority");
                proof.put("controlledUnhealthy",true); proof.put("noTransientDoorOrSelection",true);
                require(UniverseRegistryState.get(owner).records().isEmpty() && !Files.exists(data("quantumchamber_universes.dat")),"journal bootstrap fault 不能配置 Universe");
                proof.put("noUniverseAllocation",true); proof.put("bootstrapRejectedBeforeSpaces",M4CandidateTestAccess.get(CorridorPageManager.class,"active")==null);
                passed=true; stop(); return;
            }
            if(config.phase().equals("selection-fault-restart")) {
                journal=SessionRecoveryState.get(owner);
                var manifest=JsonParser.parseString(Files.readString(config.root().resolve("fault-manifest.json"))).getAsJsonObject();
                sid=UUID.fromString(manifest.get("sessionUuid").getAsString()); chamber=UUID.fromString(manifest.get("chamberUuid").getAsString());
                original=journal.flushedRecords().get(sid);
                var receipt=SessionRecoveryState.fromNbt(NbtIo.readCompressed(config.root().resolve("fault-receipt.dat"),NbtSizeTracker.ofUnlimitedBytes()));
                require(original!=null && original.equals(receipt.flushedRecords().get(sid)),"跨 JVM：重啟 flushed record 必須 exact 等於失敗後 checked receipt");
                require(original.state()==SessionState.RETURNING && original.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable,
                        "selection 提交失敗後重啟不得回 SELECTED／MEASURED");
                require(ledgerSha256(original).equals(manifest.get("ledgerSha256").getAsString()) && original.candidateLedger().size()==manifest.get("ledgerSize").getAsInt(),
                        "重啟不得重抽或截斷 candidate ledger");
                catalogHash=manifest.get("catalogHash").getAsString(); entropyHash=manifest.get("entropyHash").getAsString();
                require(hash(data("quantumchamber_universes.dat")).equals(catalogHash) && hash(data("quantumchamber_candidate_entropy.dat")).equals(entropyHash),
                        "跨 JVM catalog／entropy bytes 改變");
                proof.put("restartReceiptExact",true); proof.put("restartState","RETURNING"); proof.put("restartSelection","SELECTABLE");
                proof.put("faultPhase",manifest.get("faultPhase").getAsString());
                return;
            }
            if(config.phase().equals("dormant-reentry")) {
                journal=SessionRecoveryState.get(owner); original=journal.flushedRecords().values().iterator().next();
                sid=original.sessionUuid(); chamber=original.chamberUuid(); dormantReceipt=original;
                require(journal.flushedRecords().size()==1 && MeasuredRecoveryPhase.from(original)==MeasuredRecoveryPhase.DORMANT,"必須由真實 retained recovery 的 DORMANT receipt 開始");
                var expected=NbtIo.readCompressed(config.root().resolve("selected-receipt.dat"),NbtSizeTracker.ofUnlimitedBytes());
                require(sameReceipt(SessionRecoveryState.fromNbt(expected).flushedRecords().get(sid),original),"DORMANT receipt 與首次 SELECTED receipt 不符");
                var manifest=JsonParser.parseString(Files.readString(config.root().resolve("manifest.json"))).getAsJsonObject();
                catalogHash=manifest.get("catalogHash").getAsString(); entropyHash=manifest.get("entropyHash").getAsString();
                require(hash(data("quantumchamber_universes.dat")).equals(catalogHash) && hash(data("quantumchamber_candidate_entropy.dat")).equals(entropyHash),
                        "跨 JVM catalog／entropy bytes 改變");
                proof.put("dormantReceiptLoaded",true);
                return;
            }
            if(reloading()) {
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
                if(config.phase().equals("recover-after-write-fail")) {
                    require(!M4RecoveryDiskEvidence.geometryEmpty(config.root(),original.spaceLeases()),"fault 後正式磁碟應保留未清除 geometry，才能證明跨 JVM 重試");
                    proof.put("diskGeometryPresentBeforeRetry",true);
                }
            }
        });
    }
    private void started(MinecraftServer owner) {
        if(owner!=server || finished) return;
        guarded(() -> {
            source=owner.getOverworld(); target=owner.getWorld(SuperpositionWorld.KEY);
            pages=CorridorPageManager.forServer(owner); journal=SessionRecoveryState.get(owner);
            if(config.phase().equals("selection-fault-restart")) {
                require(M4CandidateTestAccess.space(pages,sid)!=null,"RETURNING restart 必須恢復租約才能安全返還");
                require(!pages.measuredRecoveryRequested(sid),"SELECTABLE restart 不得走 MEASURED retained recovery");
            } else if(config.phase().equals("dormant-reentry")) {
                require(M4CandidateTestAccess.space(pages,sid)==null,"dormant 不能重建 geometry／ticket space");
                require(((Map<?,?>)M4CandidateTestAccess.get(ChamberSessions.gateway(),"tickets")).isEmpty(),"dormant 不能重建來源票");
            } else if(config.phase().equals("verify")) {
                require(M4CandidateTestAccess.space(pages,sid)==null,"dormant 不能重建 geometry／ticket space");
                require(((Map<?,?>)M4CandidateTestAccess.get(ChamberSessions.gateway(),"tickets")).isEmpty(),"dormant 不能重建來源票");
            } else if(reloading()) {
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
            if(config.phase().equals("selection-fault-restart")) { restartAfterSelectionFault(); return; }
            if(config.phase().equals("dormant-reentry")) { dormantReentry(); return; }
            if(config.phase().equals("verify")) {
                verifyDormant();
                if(ticks>=5) { proof.put("dormantRepeatedTicks",ticks); success(); }
                return;
            }
            if(config.phase().equals("native-write-fail") && nativeFault!=null && nativeFault.reported.get()>0) {
                verifyNativeFailure(); return;
            }
            if(reloading()) { recovering(); return; }
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
                if(SELECTION_FAULTS.contains(config.phase())) preFault=record;
                var result=target.getBlockState(pos).onUse(target,player,new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(pos),Direction.UP,pos,false));
                if(SELECTION_FAULTS.contains(config.phase())) { verifySelectionFault(result); return; }
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
        source.getChunk(0,0); placeChamber(frame);
        var connection=new ConnectedGameTestPlayer(source); players.add(connection); connection.confirmTeleport();
        var player=connection.player(); player.setNoGravity(true);
        player.refreshPositionAndAngles(CorridorGeometry.position(frame,2.5,1,2.5),17,9);
    }
    private void placeChamber(ChamberFrame chamberFrame) {
        M1FoundationSessionFixture.set(source,chamberFrame.controllerPos(),false);
        ChamberProtectionService.get().authorizedMutation(() -> {
            for(int x=0;x<7;x++) for(int y=0;y<7;y++) for(int z=0;z<7;z++) {
                boolean shell=x==0 || x==6 || y==0 || y==6 || z==0 || z==6;
                var block=shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if(z==0 && x>0 && x<6 && y>0 && y<6) block=ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                source.setBlockState(ChamberGeometry.localToWorld(chamberFrame,x,y,z),block,3);
            }
            source.setBlockState(chamberFrame.controllerPos(),ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING,Direction.NORTH),3);
        });
        source.setBlockState(chamberFrame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
    }
    private void arm() throws Exception {
        var initial=armChamber(frame,players.getFirst().player()); if(initial==null) return;
        chamber=initial.chamberUuid(); sid=initial.sessionUuid(); stage=2;
    }
    /** 以真實 LOW→HIGH 供電觸發正式 start；尚未就緒回 null，已供電後必須立即有 checked ARMING。 */
    private SessionRecoveryRecord armChamber(ChamberFrame chamberFrame,net.minecraft.server.network.ServerPlayerEntity player) throws Exception {
        var controller=controllerAt(chamberFrame); if(controller==null || controller.chamberUuid()==null) return null;
        source.setBlockState(chamberFrame.controllerPos().up(),Blocks.AIR.getDefaultState(),3);
        if(!player.hasStatusEffect(ModEffects.QUANTUM_STATE)) player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
        if(!new ChamberActivationService().evaluateReadiness(source,controller).accepted()) return null;
        var uuid=controller.chamberUuid(); PlayerCheckpointStore.saveAndVerify(server,player,Optional.empty());
        require(server.save(false,true,true),"native source save 失敗");
        source.setBlockState(chamberFrame.controllerPos().up(),Blocks.REDSTONE_BLOCK.getDefaultState(),3);
        var initial=journal.flushedRecords().values().stream().filter(value -> value.chamberUuid().equals(uuid)).findFirst()
                .orElseThrow(() -> new AssertionError("供電後缺少 checked ARMING：presence="+ChamberSessions.gateway().presence(server,uuid)));
        require(initial.state()==SessionState.ARMING,"缺少 durable ARMING：state="+initial.state()+" sameAsDormantChamber="+uuid.equals(chamber)
                +" records="+journal.flushedRecords().size());
        return initial;
    }
    private void join(UUID id) { join(id,"measuredParticipantBlocked"); }
    private void join(UUID id,String blockedProof) {
        var connection=new ConnectedGameTestPlayer(source,new GameProfile(id,"m4-"+id.toString().substring(0,8))); players.add(connection);
        connection.confirmTeleport(); connection.player().setNoGravity(true);
        require(connection.joinTick()>=0 && connection.player().getServerWorld()==target,"必須真 JOIN 載入 corridor playerdata");
        require(SessionRecoveryManager.blocks(id,server),"尚未返還的玩家必須被攔截");
        proof.put("nativeJoinObserved",true); proof.put(blockedProof,true);
    }
    private void verifySelectionFault(ActionResult result) throws Exception {
        require(result==ActionResult.FAIL && selectionFaultFired,"受控 selection fault 必須命中且互動只回 FAIL");
        var current=journal.records().get(sid); var flushed=record();
        proof.put("faultPhase",config.phase()); proof.put("faultCount",faultCount);
        require(current!=null && sameReceipt(preFault,current),"失敗的 checked selection 必須由 ledger owner 還原；current 不得殘留未 checked SELECTED");
        require(pages.selectableDoors(sid).isEmpty(),"失敗 session 不得再發布側門");
        if(!config.phase().equals("selection-flush-fault")) {
            require(diskAtReadback!=null && diskAtReadback.state()==SessionState.MEASURED
                    && diskAtReadback.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected,"readback fault 前 next 必須已真正落盤");
            proof.put("diskHadUnacknowledgedSelectedAtReadback",true);
        }
        if(config.phase().equals("readback-crash")) {
            // 模擬「已還原、尚未寫出 RETURNING」時 crash：之後所有 journal 寫入凍結，停止時保留已寫入但未確認的 MEASURED。
            require(preFault.equals(flushed) && freezeSave,"crash 窗口：flushed 仍為上一份 SELECTABLE，後續寫入已凍結");
            var disk=diskRecord();
            require(diskAtReadback.equals(disk),"crash 窗口：磁碟保留已寫入但未確認的 MEASURED+SELECTED");
            original=disk;
            NbtIo.writeCompressed(diskJournalAtReadback,config.root().resolve("selected-receipt.dat"));
            entropyHash=hash(data("quantumchamber_candidate_entropy.dat"));
            var manifest=new LinkedHashMap<String,Object>(); manifest.put("sessionUuid",sid.toString()); manifest.put("chamberUuid",chamber.toString());
            manifest.put("catalogHash",catalogHash); manifest.put("entropyHash",entropyHash); manifest.put("journalHash",hash(data("quantumchamber_sessions.dat")));
            manifest.put("ledgerSize",disk.candidateLedger().size()); manifest.put("ledgerSha256",ledgerSha256(disk)); manifest.put("faultPhase",config.phase());
            write(config.root().resolve("manifest.json"),JSON.toJson(manifest)); proof.putAll(manifest);
            proof.put("inProcessReverted",true); proof.put("crashWindowDiskState","MEASURED+SELECTED");
            success(); return;
        }
        require(current.equals(flushed) && flushed.state()==SessionState.RETURNING && sameReceipt(preFault,flushed),
                "同 process 必須以 flushed SELECTABLE authority 寫 checked RETURNING");
        // 原生 autosave/stop 與其他 session 的 checked flush 都經同一 save(File) 寫整份 journal。
        journal.markDirty(); require(server.save(false,true,true),"原生 save 必須完成");
        var nativeDisk=diskRecord();
        require(flushed.equals(nativeDisk),"原生存檔不得承認已回報失敗的 SELECTED");
        var other=M4CandidateTestAccess.syntheticSession(server,UUID.randomUUID(),10000);
        journal.put(other); journal.flush(server);
        var otherDisk=diskRecord();
        journal.remove(other.sessionUuid()); journal.flush(server);
        require(flushed.equals(otherDisk) && !journal.flushedRecords().containsKey(other.sessionUuid()),"其他 session flush 不得承認已回報失敗的 SELECTED");
        NbtIo.writeCompressed(journal.writeNbt(new NbtCompound()),config.root().resolve("fault-receipt.dat"));
        entropyHash=hash(data("quantumchamber_candidate_entropy.dat"));
        var manifest=new LinkedHashMap<String,Object>(); manifest.put("sessionUuid",sid.toString()); manifest.put("chamberUuid",chamber.toString());
        manifest.put("catalogHash",catalogHash); manifest.put("entropyHash",entropyHash); manifest.put("journalHash",hash(data("quantumchamber_sessions.dat")));
        manifest.put("ledgerSize",flushed.candidateLedger().size()); manifest.put("ledgerSha256",ledgerSha256(flushed)); manifest.put("faultPhase",config.phase());
        write(config.root().resolve("fault-manifest.json"),JSON.toJson(manifest)); proof.putAll(manifest);
        proof.put("inProcessDurable","RETURNING+SELECTABLE"); proof.put("nativeSaveNotAdmitted",true); proof.put("otherSessionFlushNotAdmitted",true);
        success();
    }
    private void restartAfterSelectionFault() throws Exception {
        var current=record();
        if(current!=null) require(sameReceipt(original,current) && current.state()==SessionState.RETURNING && current.equals(journal.records().get(sid)),
                "重啟後不得重抽／重選或殘留未 checked authority");
        if(stage==0) {
            if(ticks<3) return;
            require(current!=null && current.participants().stream().anyMatch(person -> !person.returned()),"離線 restart 不能自動 returned");
            join(original.participants().getFirst().playerUuid(),"returningParticipantBlocked"); stage=1; return;
        }
        if(current==null) {
            var player=players.getFirst().player();
            require(player.getServerWorld()==source && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame),player.getBoundingBox()),"玩家必須安全返還原艙");
            require(journal.records().isEmpty() && journal.flushedRecords().isEmpty() && pages.releaseComplete(sid),"SELECTABLE session 安全返還後依既有契約移除");
            require(M4CandidateTestAccess.space(pages,sid)==null && ((Map<?,?>)M4CandidateTestAccess.get(pages,"protectedLeases")).isEmpty(),"返還後釋放 geometry／lease");
            proof.put("safeReturnCompleted",true); proof.put("neverSelectedAcrossRestart",true); proof.put("ledgerNeverRederived",true);
            success();
        }
    }
    private void dormantReentry() throws Exception {
        require(dormantReceipt.equals(record()) && dormantReceipt.equals(journal.records().get(sid)),"DORMANT receipt 必須保持 exact");
        require(ChamberSessions.gateway().presence(server,chamber)!=ChamberSessionGateway.Presence.NONE,"DORMANT receipt 仍封鎖自己的 Chamber");
        var participant=dormantReceipt.participants().getFirst().playerUuid();
        if(stage==0) {
            if(ticks<3) return;
            var connection=new ConnectedGameTestPlayer(source,new GameProfile(participant,"m4-"+participant.toString().substring(0,8))); players.add(connection);
            connection.confirmTeleport(); var player=connection.player(); player.setNoGravity(true);
            require(player.getServerWorld()==source && !SessionRecoveryManager.blocks(participant,server),"已返還的 DORMANT 參與者不得被 recovery 攔截");
            // KEEP_CURRENT 返還保留了 QuantumState；先移除，只讓 armChamber 的真實 LOW→HIGH 供電觸發第二座 Chamber 的 start。
            player.removeStatusEffect(ModEffects.QUANTUM_STATE);
            source.getChunk(1,0); placeChamber(frame2);
            player.refreshPositionAndAngles(CorridorGeometry.position(frame2,2.5,1,2.5),17,9);
            proof.put("dormantParticipantJoinedAtSource",true); stage=1; return;
        }
        if(stage==1) {
            if(runtimeBeforeSecond==null) runtimeBeforeSecond=runtimeOwnership();
            var initial=armChamber(frame2,players.getFirst().player()); if(initial==null) return;
            chamber2=initial.chamberUuid(); sid2=initial.sessionUuid();
            require(!chamber2.equals(chamber) && initial.participants().stream().anyMatch(person -> person.playerUuid().equals(participant)),
                    "另一座 Chamber 必須接納 DORMANT 參與者");
            proof.put("otherChamberArmed",true); stage=2; return;
        }
        var second=journal.flushedRecords().get(sid2);
        if(stage==2) {
            if(second==null || second.state()!=SessionState.SUPERPOSITION || players.getFirst().player().getServerWorld()!=target) return;
            require(!SessionRecoveryManager.blocks(participant,server),"活動 session 參與者不得因 DORMANT receipt 被攔截");
            proof.put("otherChamberEntered",true);
            source.setBlockState(frame2.controllerPos().up(),Blocks.AIR.getDefaultState(),3); stage=3; return;
        }
        if(stage==3 && second==null && ChamberSessions.gateway().presence(server,chamber2)==ChamberSessionGateway.Presence.NONE) {
            var player=players.getFirst().player();
            require(player.getServerWorld()==source && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame2),player.getBoundingBox()),"玩家安全返還第二座 Chamber");
            require(runtimeBeforeSecond.equals(runtimeOwnership()),"第二座 Chamber 收尾後不得洩漏 space／slot／page／ticket");
            proof.put("otherChamberReleasedWithoutLeak",true); proof.put("dormantReceiptUnchanged",true); proof.put("dormantChamberBlocked",true);
            success();
        }
    }
    private List<?> runtimeOwnership() {
        var gateway=ChamberSessions.gateway();
        return List.of(Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(pages,"spaces")).keySet()),
                Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(pages,"protectedLeases")),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(M4CandidateTestAccess.get(pages,"allocator"),"leases")).keySet()),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(gateway,"sessions")).keySet()),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(gateway,"tickets")).keySet()));
    }
    private SessionRecoveryRecord diskRecord() throws IOException {
        var decoded=SessionRecoveryState.fromNbt(SessionJournalStore.read(data("quantumchamber_sessions.dat")).getCompound("data"));
        decoded.requireHealthy(); return decoded.flushedRecords().get(sid);
    }
    private static String ledgerSha256(SessionRecoveryRecord record) throws Exception {
        var single=new SessionRecoveryState(); single.put(record);
        var ledger=single.writeNbt(new NbtCompound()).getList("Records",NbtElement.COMPOUND_TYPE).getCompound(0).get("CandidateLedger").toString();
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ledger.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
    private void recovering() throws Exception {
        if(stage==0) {
            if(config.phase().equals("recover-after-write-fail")) {
                require(record().participants().stream().allMatch(SessionRecoveryRecord.Participant::returned),"失敗續跑保留原 returned checkpoint");
                stage=1;
            } else {
            // 至少等待三 tick，證明離線 pending 不會自行標記為返還。
            if(ticks<3) return;
            require(record().participants().stream().anyMatch(person -> !person.returned()),"離線 restart 不能自動 returned");
            join(original.participants().getFirst().playerUuid()); stage=1; return;
            }
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
        if(config.phase().equals("recover-after-write-fail")) {
            require(M4RecoveryDiskEvidence.geometryEmpty(config.root(),original.spaceLeases()),"DORMANT 前正式 MCA geometry 仍未清除");
            proof.put("diskGeometryEmptyAfterRetry",true);
        }
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
        if(nativeFault!=null && nativeFault.reported.get()>0 && next.spaceLeases().isEmpty()) {
            probe.freezeSave=true;
            probe.proof.put("outerSaveReturnedNormally",probe.outerSaveReturned);
            probe.proof.put("nativeWriteFailures",nativeFault.writes.get());
            probe.proof.put("nativeFailureCallbacks",nativeFault.reported.get());
            probe.guarded(() -> require(false,"原生 write 失敗被吞掉後仍嘗試寫空 lease"));
            throw new IllegalStateException("RED：缺少 native save failure gate");
        }
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
        if(SELECTION_FAULTS.contains(probe.config.phase()) && !probe.selectionFaultFired
                && next.state()==SessionState.MEASURED && previous.state()!=SessionState.MEASURED) {
            probe.selectionFaultFired=true; probe.faultCount++;
            if(!previous.equals(probe.preFault)) probe.failures.add("selection fault 的 previous 不是互動前 flushed authority");
            // 寫入前失敗：正式檔保持上一份；readback 類：讓 save 真正寫入 next 後才在 strict readback 失敗。
            if(probe.config.phase().equals("selection-flush-fault")) throw new IllegalStateException("M4 owned-root 受控 selection flush fault（寫入前）");
            probe.readbackArmed=true; return;
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
    public static void beforeWorldSave(ServerWorld world) {
        var probe=active;
        if(probe==null || probe.server!=world.getServer() || !probe.config.phase().equals("native-write-fail") || probe.finished || nativeFault!=null) return;
        var record=probe.record();
        if(record.state()==SessionState.MEASURED && record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned)) {
            var positions=new HashSet<Long>(); record.spaceLeases().forEach(lease -> positions.addAll(chunks(lease.bounds())));
            nativeFault=new NativeFault(Set.copyOf(positions),chunks(ChamberGeometry.bounds(probe.frame)),record.origin().worldKey());
        }
    }
    public static void beforeRegionWrite(net.minecraft.world.storage.StorageKey key,ChunkPos pos) throws IOException {
        var fault=nativeFault;
        if(fault==null || !fault.matches(key,pos)) return;
        fault.writes.incrementAndGet(); fault.types.add(key.dimension().getValue()+"/"+key.type());
        throw new IOException("M4 owned-root 真 region write 受控失敗");
    }
    public static void drainFailedNativeWrite(java.util.concurrent.CompletableFuture<?> future) {
        if(nativeFault!=null) future.handle((value,error) -> null).join();
    }
    public static void nativeFailureReported(net.minecraft.world.storage.StorageKey key,ChunkPos pos) {
        var fault=nativeFault; if(fault!=null && fault.matches(key,pos)) fault.reported.incrementAndGet();
    }
    public static void afterServerSave(MinecraftServer owner,boolean result) {
        var probe=active; if(probe!=null && probe.server==owner && nativeFault!=null && result) probe.outerSaveReturned=true;
    }
    private void verifyNativeFailure() throws Exception {
        require(outerSaveReturned,"必須重現外層 server.save 正常回 true 的原生失敗");
        require(record().spaceLeases().equals(original.spaceLeases()) && journal.records().get(sid).spaceLeases().equals(original.spaceLeases()),"write fault 不得移除 current／flushed leases");
        require(sameReceipt(original,record()),"write fault 不得改 selection receipt");
        var space=M4CandidateTestAccess.space(pages,sid);
        require(space!=null && !((Map<?,?>)M4CandidateTestAccess.get(space,"ticketRefs")).isEmpty()
                && !((Map<?,?>)M4CandidateTestAccess.get(pages,"protectedLeases")).isEmpty(),"write fault 必須保留 runtime／tickets／protection");
        for(long packed : nativeFault.corridor) require(target.getChunkManager().getWorldChunk(new ChunkPos(packed).x,new ChunkPos(packed).z).needsSaving(),"corridor chunk 必須重新 dirty");
        for(long packed : nativeFault.source) require(source.getChunkManager().getWorldChunk(new ChunkPos(packed).x,new ChunkPos(packed).z).needsSaving(),"source chunk 必須重新 dirty");
        for(var entry : Map.of(source,nativeFault.source,target,nativeFault.corridor).entrySet()) {
            var entities=M4CandidateTestAccess.get(M4CandidateTestAccess.get(entry.getKey(),"entityManager"),"dataAccess");
            var empty=(it.unimi.dsi.fastutil.longs.LongSet)M4CandidateTestAccess.get(entities,"emptyChunks");
            require(entry.getValue().stream().noneMatch(empty::contains),"entity empty cache 必須撤銷以便真重寫");
            require(nativeFault.types.contains(entry.getKey().getRegistryKey().getValue()+"/entities"),"必須包含 source／corridor entity storage 的真失敗");
        }
        require(!((Map<?,?>)M4CandidateTestAccess.get(M4CandidateTestAccess.get(pages,"allocator"),"leases")).isEmpty(),"write fault 必須保留 allocator leases");
        require(((NativeSaveFailureAccess)server).quantumchamber$saveFailureRevision()>0,"production server failure revision 未觀察到失敗");
        proof.put("outerSaveReturnedNormally",true); proof.put("nativeWriteFailures",nativeFault.writes.get());
        proof.put("nativeFailureCallbacks",nativeFault.reported.get()); proof.put("failedStorageTypes",List.copyOf(nativeFault.types));
        proof.put("leasesAndResourcesRetained",true); proof.put("relatedChunksRedirtied",true); proof.put("entityEmptyCacheInvalidated",true);
        proof.put("productionFailureRevision",((NativeSaveFailureAccess)server).quantumchamber$saveFailureRevision()); success();
    }
    private boolean reloading() { return Set.of("recover","verify","native-write-fail","recover-after-write-fail").contains(config.phase()); }
    private static Set<Long> chunks(BlockBox bounds) {
        var result=new HashSet<Long>();
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++) for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) result.add(ChunkPos.toLong(x,z));
        return Set.copyOf(result);
    }
    private static final class NativeFault {
        final Set<Long> corridor,source; final Identifier sourceKey;
        final java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger(),reported=new java.util.concurrent.atomic.AtomicInteger();
        final Set<String> types=java.util.concurrent.ConcurrentHashMap.newKeySet();
        NativeFault(Set<Long> corridor,Set<Long> source,Identifier key) { this.corridor=corridor; this.source=source; sourceKey=key; }
        boolean matches(net.minecraft.world.storage.StorageKey key,ChunkPos pos) {
            return key.dimension().equals(SuperpositionWorld.KEY) && corridor.contains(pos.toLong())
                    || key.dimension().getValue().equals(sourceKey) && source.contains(pos.toLong());
        }
    }
    /** readback 類 phase：next 已原子寫入正式檔後，讓 strict readback 受控失敗一次。 */
    public static void beforeJournalReadback(Path path) {
        var probe=active;
        if(probe==null || !probe.readbackArmed || probe.finished || probe.sid==null
                || !path.toAbsolutePath().normalize().equals(probe.data("quantumchamber_sessions.dat").toAbsolutePath().normalize())) return;
        probe.readbackArmed=false;
        try {
            var written=SessionJournalStore.read(path).getCompound("data");
            var decoded=SessionRecoveryState.fromNbt(written); decoded.requireHealthy();
            probe.diskJournalAtReadback=written.copy(); probe.diskAtReadback=decoded.flushedRecords().get(probe.sid);
        } catch(IOException | RuntimeException failure) { probe.failures.add("readback fault 前無法讀取已寫入 journal："+failure); }
        if(probe.config.phase().equals("readback-crash")) probe.freezeSave=true;
        throw new IllegalStateException("M4 owned-root 受控 journal readback fault（已寫入後）");
    }
    public static boolean suppressJournalSave(Path path) {
        var probe=active;
        return probe!=null && probe.freezeSave && path.toAbsolutePath().normalize().equals(probe.data("quantumchamber_sessions.dat").toAbsolutePath().normalize());
    }
    private void verifyDirty() throws Exception {
        require(original.equals(record()) && journal.isDirty(),"dirty window 必須保留上一份 flushed authority");
        var current=journal.records().get(sid);
        require(!current.equals(original),"必須真建立 dirty current");
        // fix1 契約：失敗的 checked 提交先由 ledger owner 還原；凍結窗口內只可殘留以 flushed authority 建立的 RETURNING 進度。
        require(sameReceipt(original,current) && current.state()==SessionState.RETURNING,"dirty 窗口不得殘留未 checked candidate／selection");
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
        proof.put("universeAfter",universeEvidence());
        require(proof.get("universeBefore").equals(proof.get("universeAfter")),"M4 前後 catalog records／bytes／world keys 必須完全相同");
        var finalRecord=sid==null ? null : record();
        proof.put("candidateCount",finalRecord==null ? 0 : finalRecord.candidateLedger().size());
        passed=true; stop();
    }
    private void stop() { finished=true; proof.put("stopRequested",true); server.stop(false); }
    private void stopped(MinecraftServer owner) {
        if(owner!=server) return;
        guarded(() -> {
            proof.put("stoppedSeen",true);
            proof.put("universeStopped",universeEvidence());
            require(proof.get("universeBefore").equals(proof.get("universeStopped")),"正常停止不得改 Universe catalog／world keys");
            if(damaged!=null) require(hash(damaged).equals(damagedHash),"正常停止覆寫損壞 bytes");
            proof.put("damagedBytesPreserved",true);
            if(sid!=null && !config.phase().startsWith("entropy") && !config.phase().equals("discovery-corrupt"))
                proof.put("finalJournalHash",hash(data("quantumchamber_sessions.dat")));
            if(Set.of("verify","selection-fault-restart","dormant-reentry").contains(config.phase()))
                require(hash(data("quantumchamber_candidate_entropy.dat")).equals(entropyHash),"重啟 JVM 重抽 entropy");
        });
        proof.put("status",passed && failures.isEmpty() && Boolean.TRUE.equals(proof.get("stoppingSeen")) ? "PASS" : "FAIL");
        write(config.evidence().resolve("final.json"),JSON.toJson(proof)); event("stopped"); active=null; nativeFault=null;
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
    private Map<String,Object> universeEvidence() throws Exception {
        var catalog=UniverseRegistryState.get(server);
        String records=catalog.writeNbt(new NbtCompound()).toString();
        return Map.of("catalogExists",Files.exists(data("quantumchamber_universes.dat")),
                "catalogSha256",hash(data("quantumchamber_universes.dat")),"recordCount",catalog.records().size(),
                "recordsSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(records.getBytes(java.nio.charset.StandardCharsets.UTF_8))),
                "worldKeys",java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(),false)
                        .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList());
    }
    private ChamberControllerBlockEntity controller() { return controllerAt(frame); }
    private ChamberControllerBlockEntity controllerAt(ChamberFrame chamberFrame) {
        return source.getBlockEntity(chamberFrame.controllerPos()) instanceof ChamberControllerBlockEntity c ? c : null;
    }
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
            if(!Set.of("select","recover","verify","low","buff","disconnect","dirty-candidate","dirty-selection","entropy-missing","entropy-corrupt","discovery-corrupt","journal-corrupt","native-write-fail","recover-after-write-fail",
                    "selection-flush-fault","readback-fault","readback-crash","selection-fault-restart","dormant-reentry").contains(phase)
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
