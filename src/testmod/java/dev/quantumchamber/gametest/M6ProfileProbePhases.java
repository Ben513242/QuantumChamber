package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.corridor.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.registry.*;
import dev.quantumchamber.superposition.*;
import dev.quantumchamber.universe.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.*;
import net.minecraft.registry.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.structure.Structure;
import org.slf4j.LoggerFactory;

/** 每條 same-seed 鏈使用一個原版來源；normal/amplified 各自擁有獨立 root 與三個 JVM。 */
public final class M6ProfileProbePhases {
    private static final Gson JSON = new Gson();
    private static final long SEED = 314159265358L;
    private static final String PACKS = "vanilla,fabric,fabric-gametest-api-v1,quantumchamber,fabric-convention-tags-v2,quantumchamber-testmod";
    private static final BlockPos BUILDING = new BlockPos(2051, 300, 2051);
    private static final BlockPos SOURCE_EDIT = BUILDING.east();
    private static final BlockPos TARGET_EDIT = BUILDING.west();
    private final String phase;
    private final String chain;
    private final Path root, evidence;
    private final Map<String,Object> proof;
    private final List<Flow> flows = new ArrayList<>();
    private MinecraftServer server;
    private M5TestHooks.CrashPoint cut;
    private boolean finished;
    private int ticks;

    M6ProfileProbePhases(String phase, Path root, Path evidence, Map<String,Object> proof) {
        this.phase=phase; this.root=root; this.evidence=evidence; this.proof=proof; this.chain=(String)proof.get("chain");
    }

    void started(MinecraftServer owner) {
        server=owner;
        guarded(() -> {
            require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()), "SAME_ROOT_IDENTITY");
            var properties=new Properties();
            try(var reader=Files.newBufferedReader(root.resolve("server.properties"))){properties.load(reader);}
            var configuredPacks=properties.getProperty("initial-enabled-packs");
            var enabledPacks=String.join(",",server.getDataPackManager().getEnabledIds());
            proof.put("configuredPackOrder",configuredPacks);proof.put("runtimePackOrder",enabledPacks);
            require(PACKS.equals(configuredPacks),"SAME_CONFIGURED_PACK_ORDER");
            if(!phase.equals("source")) {
                var sourceProof=JsonParser.parseString(Files.readString(evidence.getParent().resolve("source/final.json"))).getAsJsonObject();
                require(sourceProof.get("status").getAsString().equals("PASS") && sourceProof.get("chain").getAsString().equals(chain)
                        && sourceProof.get("root").getAsString().equals(root.toString())
                        && sourceProof.get("nonce").getAsString().equals(proof.get("nonce")),"SAME_SOURCE_PACK_RECEIPT_IDENTITY");
                require(configuredPacks.equals(sourceProof.get("configuredPackOrder").getAsString())
                        && enabledPacks.equals(sourceProof.get("runtimePackOrder").getAsString()),"SAME_RESTART_PACK_ORDER");
            }
            String preset=chain.equals("same-seed-restart")?"normal":"amplified";
            require(Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(Minecraft121SettingsLifecycle.acquire(server)).orElse("").equals(preset),"SAME_CANONICAL_PRESET");
            var canonicalFlow=new Flow(preset,server.getOverworld());flows.add(canonicalFlow);
            // 先掛同一 row 參考；失敗也必留下已觀測的條件，不等成功才公開證據。
            proof.put("variants",flows.stream().map(f -> f.row).toList());
            if (!phase.equals("source")) canonicalFlow.reopen();
            if (phase.equals("source")) {
                cut=new M5TestHooks.CrashPoint(M5TestHooks.CrashWindow.COLLAPSING_COMMITTED,
                        detail -> detail instanceof M5TestHooks.CollapsingCommit commit
                                && commit.next().state()==SessionState.COLLAPSING && commit.expected().state()==SessionState.MEASURED
                                && flows.stream().anyMatch(f -> commit.next().sessionUuid().equals(f.sid)),
                        (detail,hits) -> guarded(() -> {
                            var commit=(M5TestHooks.CollapsingCommit)detail;
                            var flow=flows.stream().filter(f -> commit.next().sessionUuid().equals(f.sid)).findFirst().orElseThrow();
                            require(commit.result().committed() && commit.next().state()==SessionState.COLLAPSING, "SAME_CHECKED_INTENT");
                            M5TestHooks.suspendCollapse(flow.sid);
                            flow.collapsing=true;
                        }));
                M5TestHooks.CRASH_POINT.install(cut,null);
            }
        });
    }

    void tick(MinecraftServer owner) {
        if (owner!=server || finished) return;
        guarded(() -> {
            require(++ticks<1800, "SAME_BOUNDED_WAIT");
            for (var flow:flows) {
                if (phase.equals("source")) flow.advance();
                else flow.verifyMaterialized();
            }
            if (flows.stream().allMatch(f -> f.done)) {
                proof.put("variants",flows.stream().map(f -> f.row).toList());
                proof.put("functionalPassed",true); proof.put("stopRequested",true);
                require(server.save(false,true,true),"SAME_SAVE");
                finished=true; server.stop(false);
            }
        });
    }
    void stopping(MinecraftServer owner) {
        if (owner==server) { proof.put("stoppingSeen",true); if(cut!=null)M5TestHooks.CRASH_POINT.remove(cut); }
    }
    void stopped(MinecraftServer owner) {
        if(owner!=server)return;
        proof.put("stoppedSeen",true);
        proof.put("status", Boolean.TRUE.equals(proof.get("functionalPassed")) && failures().isEmpty()?"PASS":"FAIL");
        try { writeNew(evidence.resolve("final.json"),proof); }
        catch(Exception failure){throw new IllegalStateException("SAME_RECEIPT_WRITE",failure);}
        LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_NATIVE_END chain={} phase={} mode=FUNCTIONAL status={}",chain,phase,proof.get("status"));
    }
    @SuppressWarnings("unchecked") private List<String> failures(){return (List<String>)proof.get("failures");}
    private void guarded(Checked action) {
        try { action.run(); }
        catch(Exception failure) {
            failures().add(failure.toString()); finished=true; proof.put("stopRequested",true);
            LoggerFactory.getLogger("quantumchamber-m6-native").error("SAME_FUNCTIONAL_FAILED",failure);
            server.stop(false);
        }
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
    @FunctionalInterface private interface ObservationSink {void write(Map<String,Object> observation) throws Exception;}

    private final class Flow {
        final String preset;
        final ServerWorld source;
        final ChamberFrame frame=new ChamberFrame(new BlockPos(8,80,8),Direction.NORTH);
        final BlockPos lever=frame.controllerPos().north();
        final Map<String,Object> row=new LinkedHashMap<>();
        ConnectedGameTestPlayer participant,operator;
        UUID sid;
        NewUniverseIntent intent;
        UniverseId targetId;
        int stage;
        boolean collapsing,done;
        Flow(String preset,ServerWorld source){this.preset=preset;this.source=source;}
        SessionRecoveryRecord record(){return sid==null?null:SessionRecoveryState.get(server).flushedRecords().get(sid);}
        Path storage(){return root.resolve("world/dimensions/quantumchamber/universe/"+targetId.value()+"/overworld");}
        Path snapshotPath(){return root.resolve("same-seed-"+preset+"-source.nbt");}
        void advance() throws Exception {
            if(done)return;
            if(stage==0){build();stage=1;return;}
            if(stage==1){arm();return;}
            if(stage==2){select();return;}
            if(!collapsing)return;
            require(record().state()==SessionState.COLLAPSING,"SAME_SOURCE_INTENT_STATE");
            frozen(record());
            require(!Files.exists(storage()) && !UniverseRegistryState.get(server).flushedRecords().containsKey(targetId)
                    && server.getWorld(UniverseKeys.world(targetId,DimensionRole.OVERWORLD))==null,"SAME_EMPTY_STORAGE_BEFORE_ALLOCATION");
            require(readAllocation().byToken(intent.allocationToken()).isEmpty(),"SAME_ALLOCATION_BEFORE_INTENT");
            row.put("emptyStorageBeforeAllocation",true);
            require(server.save(false,true,true),"SAME_SOURCE_PREFLIGHT_SAVE");
            samplePreflight(List.of(source),row);
            require(!Files.exists(snapshotPath()),"SAME_SOURCE_RECORD_ALREADY_EXISTS");
            var encode=SessionRecoveryRecord.class.getDeclaredMethod("toNbt",int.class);encode.setAccessible(true);
            NbtIo.writeCompressed((NbtCompound)encode.invoke(record(),5),snapshotPath());
            row.put("sourceRecordSha256",hash(snapshotPath()));
            row.put("player",participant.player().getUuid().toString());
            writeNew(root.resolve("same-seed-"+preset+"-source.json"),row);
            done=true;
        }
        void build() {
            source.getChunk(0,0);
            M1FoundationSessionFixture.set(source,frame.controllerPos(),false);
            ChamberProtectionService.get().authorizedMutation(() -> {
                for(int x=0;x<7;x++)for(int y=0;y<7;y++)for(int z=0;z<7;z++) {
                    var state=(x==0||x==6||y==0||y==6||z==0||z==6?Blocks.BEDROCK:Blocks.AIR).getDefaultState();
                    if(z==0&&x>0&&x<6&&y>0&&y<6)state=ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                    source.setBlockState(ChamberGeometry.localToWorld(frame,x,y,z),state,3);
                }
                source.setBlockState(frame.controllerPos(),ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING,Direction.NORTH),3);
            });
            source.setBlockState(lever,Blocks.LEVER.getDefaultState().with(net.minecraft.block.LeverBlock.FACE,
                    net.minecraft.block.enums.BlockFace.WALL).with(net.minecraft.block.LeverBlock.FACING,Direction.NORTH),3);
            var initialChunk=source.getChunk(BUILDING);
            row.put("sourceInitialRequest",new ChunkPos(BUILDING).x+","+new ChunkPos(BUILDING).z+"="+initialChunk.getStatus());
            source.setBlockState(BUILDING,Blocks.DIAMOND_BLOCK.getDefaultState(),3);
            participant=new ConnectedGameTestPlayer(source); participant.confirmTeleport(); participant.player().setNoGravity(true);
            participant.player().refreshPositionAndAngles(CorridorGeometry.position(frame,2.5,1,2.5),0,0);
            participant.player().addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
            operator=new ConnectedGameTestPlayer(source);operator.confirmTeleport();operator.player().setNoGravity(true);
            operator.player().refreshPositionAndAngles(CorridorGeometry.position(frame,3.5,6,-1.5),0,0);
        }
        void arm() throws Exception {
            var controller=(ChamberControllerBlockEntity)source.getBlockEntity(frame.controllerPos());
            if(controller==null || !controller.powerInitialized())return;
            var field=controller.getClass().getDeclaredField("loadSyncPending");field.setAccessible(true);
            if(field.getBoolean(controller))return;
            var readiness=new ChamberActivationService().evaluateReadiness(source,controller);
            row.put("readiness",readiness.toString());
            if(!readiness.accepted())return;
            PlayerCheckpointStore.saveAndVerify(server,participant.player(),Optional.empty());
            require(server.save(false,true,true),"SAME_SOURCE_SAVE");
            require(source.getBlockState(lever).onUse(source,operator.player(),new net.minecraft.util.hit.BlockHitResult(
                    Vec3d.ofCenter(lever),Direction.NORTH,lever,false)).isAccepted(),"SAME_FORMAL_LEVER");
            var initial=SessionRecoveryState.get(server).flushedRecords().values().stream().filter(r -> r.chamberUuid().equals(controller.chamberUuid())).findFirst().orElseThrow();
            sid=initial.sessionUuid();
            require(initial.state()==SessionState.ARMING && initial.candidateContext().orElseThrow().policyVersion()==2
                    && initial.candidateContext().orElseThrow().familyCapabilityVersion()==0
                    && initial.candidateContext().orElseThrow().sourceFamilyRef().equals(new SourceFamilyRef.Vanilla(net.minecraft.world.World.OVERWORLD,DimensionRole.OVERWORLD)),"formalEntryFreezesPolicyTwoAndSourceSnapshot");
            row.put("formalLeverEntry",true);stage=2;
        }
        void select() throws Exception {
            var current=record(); if(current==null || current.state()!=SessionState.SUPERPOSITION)return;
            var pages=CorridorPageManager.forServer(server);
            var entry=current.candidateLedger().stream().filter(r -> r.candidate() instanceof QuantumCandidate.New n
                    && NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(n.intent().profileId()) && pages.selectableDoors(sid).contains(r.doorKey())).findFirst();
            if(entry.isEmpty())return;
            var key=entry.get().doorKey();BlockPos cell=null;
            for(var view:pages.currentMappings(sid).instances()) {
                var mapped=new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),3).up(6),view.outwardFacing());
                var possible=CorridorGeometry.block(mapped,key.wallSide()==DoorKey.DoorWallSide.NEGATIVE_LATERAL?0:6,1,
                        Math.toIntExact(key.logicalStationIndex()*8+1-view.logicalAnchorBlock()));
                if(view.bounds().contains(possible)){cell=possible;break;}
            }
            require(cell!=null,"SAME_DOOR_MAPPING");
            var corridor=server.getWorld(SuperpositionWorld.KEY);
            require(corridor.getBlockState(cell).onUse(corridor,participant.player(),new net.minecraft.util.hit.BlockHitResult(
                    Vec3d.ofCenter(cell),Direction.UP,cell,false)).isAccepted(),"SAME_FORMAL_DOOR_USE");
            require(record().state()==SessionState.MEASURED && record().candidateSelection().orElseThrow() instanceof CandidateSelection.Selected,"SAME_CHECKED_SELECTED");
            frozen(record());row.put("formalBatch",true);row.put("formalSelected",true);stage=3;
        }
        void frozen(SessionRecoveryRecord record) {
            intent=((QuantumCandidate.New)record.selectedCandidate().orElseThrow()).intent();
            targetId=UniverseAllocationLedger.deriveUniverseId(intent.allocationToken());
            var lease=Minecraft121SettingsLifecycle.acquire(server);
            var parameters=new NbtCompound();parameters.putString("OverworldPreset",preset);parameters.putBoolean("GenerateStructures",lease.sourceGenerateStructures());
            var sourceSnapshot=Minecraft121SettingsLifecycle.registry().createSnapshot("quantumchamber:vanilla_"+preset+"_v1",1,parameters,lease);
            require(intent.policyVersion()==2 && record.candidateContext().orElseThrow().policyVersion()==2
                    && intent.familyCapabilityVersion()==0 && NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(intent.profileId())
                    && intent.rootWorldSeed()==source.getSeed() && intent.sourceRootWorldSeed()==source.getSeed()
                    && intent.sourceSettingsSnapshot().toNbt().equals(sourceSnapshot.toNbt())
                    && intent.targetSettingsSnapshot().toNbt().equals(intent.sourceSettingsSnapshot().toNbt())
                    && !UniverseKeys.world(targetId,DimensionRole.OVERWORLD).equals(source.getRegistryKey()),"SAME_FROZEN_SOURCE_TARGET");
            row.put("preset",preset);row.put("session",sid.toString());row.put("sourceIdentity",source.getRegistryKey().getValue().toString());
            row.put("uuid",targetId.value().toString());row.put("seed",Long.toString(intent.rootWorldSeed()));
            row.put("allocationToken",HexFormat.of().formatHex(intent.allocationToken().bytes()));
            row.put("snapshot",intent.targetSettingsSnapshot().toNbt().toString());row.put("policyVersion",intent.policyVersion());
            row.put("profileId",intent.profileId());row.put("sourceTargetExact",true);
        }
        void reopen() throws Exception {
            var prior=JsonParser.parseString(Files.readString(root.resolve("same-seed-"+preset+"-source.json"))).getAsJsonObject();
            sid=UUID.fromString(prior.get("session").getAsString());
            require(hash(snapshotPath()).equals(prior.get("sourceRecordSha256").getAsString()),"SAME_SOURCE_RECORD_HASH");
            var decode=SessionRecoveryRecord.class.getDeclaredMethod("fromNbt",NbtCompound.class,int.class);decode.setAccessible(true);
            var saved=(SessionRecoveryRecord)decode.invoke(null,NbtIo.readCompressed(snapshotPath(),NbtSizeTracker.of(16L*1024*1024)),5);
            require(saved.candidateContext().equals(record().candidateContext()) && saved.selectedCandidate().equals(record().selectedCandidate()),"SAME_RESTART_FROZEN_JOURNAL");
            frozen(record());
            for(String key:List.of("preset","session","sourceIdentity","uuid","seed","allocationToken","snapshot","profileId"))
                require(row.get(key).toString().equals(prior.get(key).getAsString()),"SAME_RESTART_IDENTITY:"+key);
            require(prior.get("sourceInitialRequest").getAsString().equals("128,128=minecraft:full"),"SAME_SOURCE_GENERATION_REQUEST");
            row.put("sourceInitialRequest",prior.get("sourceInitialRequest").getAsString());
            for(String key:List.of("formalLeverEntry","formalBatch","formalSelected","emptyStorageBeforeAllocation")) {
                require(prior.get(key).getAsBoolean(),"SAME_FORMAL_SOURCE_RECEIPT:"+key);row.put(key,true);
            }
            participant=new ConnectedGameTestPlayer(source,new com.mojang.authlib.GameProfile(UUID.fromString(prior.get("player").getAsString()),"m6-same"));
            participant.confirmTeleport();participant.player().setNoGravity(true);
        }
        void verifyMaterialized() throws Exception {
            if(done)return;
            require(record()!=null && record().state()!=SessionState.SEALED,"SAME_COLLAPSE_SEALED");
            var active=UniverseLifecycleService.resolveActive(server,targetId);if(active.isEmpty())return;
            var target=active.orElseThrow();
            var catalog=UniverseRegistryState.get(server);
            var targetRecord=catalog.flushedRecords().get(targetId);
            require(targetRecord!=null && targetRecord.equals(catalog.records().get(targetId))
                    && CollapseAuthorities.catalogRecordMatches(intent,targetRecord,CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY),"SAME_CHECKED_CATALOG");
            var disk=UniverseRegistryState.fromNbt(UniverseCatalogStore.read(root.resolve("world/data/"+UniverseRegistryState.STATE_ID+".dat")).getCompound("data"));
            disk.requireHealthy();require(targetRecord.equals(disk.flushedRecords().get(targetId)),"SAME_CATALOG_RESTART_STRICT");
            require(readAllocation().byToken(intent.allocationToken()).filter(UniverseAllocationLedger.Entry.of(intent)::equals).isPresent(),"SAME_ALLOCATION_RESTART_EXACT");
            row.put("allocationChecked",true);
            if(phase.equals("new"))samplePreflight(List.of(source,target),row);
            compareAndEdit(source,target,phase.equals("restart"),row,evidence.resolve("comparison-before-assert.json"));
            if(phase.equals("new"))writeNew(root.resolve("same-seed-"+preset+"-new.json"),row);
            else {
                var prior=JsonParser.parseString(Files.readString(root.resolve("same-seed-"+preset+"-new.json"))).getAsJsonObject();
                for(String key:List.of("terrain","biomes","structures")) require(JSON.toJsonTree(row.get(key)).equals(prior.get(key)),"SAME_RESTART_GENERATION:"+key);
                row.put("restartExact",true);
            }
            row.put("catalogChecked",true);done=true;
        }
    }

    /** 不加票、不要求 chunk future；原始九格須同時沒有 live holder 與 raw entry。 */
    private void samplePreflight(List<ServerWorld> worlds,Map<String,Object> row) throws Exception {
        samplePreflight(server,root.resolve("world"),phase,worlds,row,
                observation -> writeNew(evidence.resolve("sample-before-generation.json"),observation));
    }

    private static void samplePreflight(MinecraftServer server,Path actualSaveRoot,String label,
            List<ServerWorld> worlds,Map<String,Object> row,ObservationSink sink) throws Exception {
        var observation=new LinkedHashMap<String,Object>();
        var observedWorlds=new ArrayList<Map<String,Object>>();
        var violations=new ArrayList<String>();
        observation.put("phase",label);observation.put("worlds",observedWorlds);observation.put("status","IN_PROGRESS");
        row.put("samplePreflight",observation);
        try {
            var saveRoot=actualSaveRoot.toRealPath();
            require(server.isOnThread() && saveRoot.equals(server.getSavePath(WorldSavePath.ROOT).toRealPath()),"SAME_SAMPLE_SAVE_ROOT");
            var rawReader=M5CollapseDiskEvidence.class.getDeclaredMethod("chunk",Path.class,ChunkPos.class);rawReader.setAccessible(true);
            for(var world:worlds) {
                require(world.getServer()==server && server.getWorld(world.getRegistryKey())==world,"SAME_SAMPLE_OWNER");
                var identity=world.getRegistryKey().getValue();
                Path region=world==server.getOverworld()?saveRoot.resolve("region"):
                        saveRoot.resolve("dimensions").resolve(identity.getNamespace()).resolve(identity.getPath()).resolve("region");
                region=region.toAbsolutePath().normalize();
                require(region.startsWith(saveRoot),"SAME_SAMPLE_REGION_ESCAPE");
                var owner=new LinkedHashMap<String,Object>();var cells=new ArrayList<Map<String,Object>>();
                owner.put("worldKey",identity.toString());owner.put("region",region.toString());owner.put("cells",cells);observedWorlds.add(owner);
                var manager=(dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor)world.getChunkManager().chunkLoadingManager;
                for(int x=31;x<=33;x++)for(int z=31;z<=33;z++) {
                    var pos=new ChunkPos(x,z);var cell=new LinkedHashMap<String,Object>();cells.add(cell);
                    cell.put("x",x);cell.put("z",z);
                    boolean current=manager.quantumchamberTest$currentChunkHolders().containsKey(pos.toLong());
                    boolean unloading=manager.quantumchamberTest$chunksToUnload().containsKey(pos.toLong());
                    boolean full=world.getChunkManager().getWorldChunk(x,z)!=null;
                    cell.put("currentHolder",current);cell.put("unloadingHolder",unloading);cell.put("liveFull",full);
                    Path file=region.resolve("r."+(x>>5)+"."+(z>>5)+".mca");cell.put("regionFile",file.toString());
                    String raw="ERROR";
                    try {
                        boolean exists=safeRawFile(file,saveRoot);
                        cell.put("canonicalNonReparse",true);cell.put("regionExists",exists);
                        int location=0;
                        if(exists) {
                            try(var input=new java.io.RandomAccessFile(file.toFile(),"r")) {
                                require(input.length()>=8192,"SAME_SAMPLE_SHORT_HEADER");
                                input.seek(4L*((x&31)+(z&31)*32));location=input.readInt();
                            }
                        }
                        cell.put("headerValid",true);cell.put("entryLocation",location);
                        @SuppressWarnings("unchecked") var nbt=(Optional<NbtCompound>)rawReader.invoke(null,region,pos);
                        require((location==0)==nbt.isEmpty(),"SAME_SAMPLE_RAW_HEADER_MISMATCH");
                        raw=nbt.map(value -> value.contains("Status",NbtElement.STRING_TYPE)?value.getString("Status"):"PRESENT_WITHOUT_STATUS").orElse("ABSENT");
                    } catch(Exception failure) {
                        cell.put("rawError",failure.toString());
                    }
                    cell.put("raw",raw);
                    if(current || unloading || full || !raw.equals("ABSENT"))
                        violations.add(identity+" "+x+","+z+" raw="+raw+" currentHolder="+current+" unloadingHolder="+unloading+" liveFull="+full);
                }
            }
            observation.put("violations",violations);observation.put("status",violations.isEmpty()?"PASS":"FAIL");
        } catch(Exception failure) {
            observation.put("status","FAIL");observation.put("error",failure.toString());
            throw new IllegalStateException("SAME_SAMPLE_PRIOR_GENERATION: "+failure,failure);
        } finally {
            sink.write(observation);
        }
        require(violations.isEmpty(),"SAME_SAMPLE_PRIOR_GENERATION: "+String.join("; ",violations));
    }

    /** 缺檔可接受；存在的所有祖先都須 canonical／非 reparse，其他 IO 錯誤不得當缺檔。 */
    private static boolean safeRawFile(Path file,Path saveRoot) throws Exception {
        boolean exists=false;
        for(Path current=file;current!=null && current.startsWith(saveRoot);current=current.getParent()) {
            try {
                var attributes=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                require(!attributes.isSymbolicLink() && !attributes.isOther() && current.toRealPath().equals(current),"SAME_SAMPLE_REPARSE_OR_NONCANONICAL");
                require(current.equals(file)?attributes.isRegularFile():attributes.isDirectory(),"SAME_SAMPLE_PATH_TYPE");
                if(current.equals(file))exists=true;
            } catch(NoSuchFileException absent) { /* 仍核查祖先，不能略過 dangling reparse。 */ }
        }
        return exists;
    }

    /** 只讀正式 allocation 原件，走相同 strict decoder；不以 lookup-or-append 補齊缺檔。 */
    private UniverseAllocationLedger readAllocation() throws Exception {
        var nbt=CheckedNbtFile.parse(CheckedNbtFile.readFile(root.resolve("world/data/"+UniverseAllocationLedger.FILE_NAME)));
        var decode=UniverseAllocationLedger.class.getDeclaredMethod("fromNbt",NbtCompound.class);decode.setAccessible(true);
        return (UniverseAllocationLedger)decode.invoke(null,nbt);
    }

    /** Task13 的同 JVM owner／設定控制；不執行 Task21 保留的全量方塊比較。 */
    static void verifyOwnerSettingsGameTest(MinecraftServer server) throws Exception {
        var root=server.getSavePath(WorldSavePath.ROOT);
        var entropy=CandidateEntropyState.loadOrCreate(root,false);
        for(String preset:List.of("normal","amplified")) {
            var sourceId=UniverseId.of(UUID.randomUUID());
            var source=fixtureSource(server,sourceId,preset);
            var catalog=UniverseRegistryState.get(server);
            var sourceRecord=catalog.flushedRecords().get(sourceId);
            var snapshot=GenerationSettingsSnapshot.fromNbt(sourceRecord.generation().targetSettingsSnapshot());
            var policy=M6CandidatePolicySelector.forNewSession(server).freeze(-1,entropy.entropyFingerprint(),
                    new SourceFamilyRef.Catalog(sourceId,DimensionRole.OVERWORLD));
            var resolver=new CandidateResolver(entropy,Minecraft121SettingsLifecycle.registry(),()->Minecraft121SettingsLifecycle.acquire(server));
            NewUniverseIntent intent=null;var session=UUID.randomUUID();
            for(int station=0;station<4096 && intent==null;station++) {
                var candidate=resolver.resolve(new DoorKey(session,station,DoorKey.DoorWallSide.NEGATIVE_LATERAL),policy,List.of(),
                        () -> new SourceGenerationSnapshot(sourceRecord.generation().rootWorldSeed(),snapshot));
                if(candidate instanceof QuantumCandidate.New fresh && NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(fresh.intent().profileId()))intent=fresh.intent();
            }
            require(intent!=null,"SAME_OWNER_SETTINGS_EXACT: same candidate missing");
            var targetId=UniverseAllocationLedger.deriveUniverseId(intent.allocationToken());
            require(!targetId.equals(sourceId) && !Files.exists(root.resolve("dimensions/quantumchamber/universe/"+targetId.value())),"SAME_OWNER_SETTINGS_EXACT: new identity/storage");
            var authority=CollapseAuthorities.get(server);
            require(authority.lookupOrAppendAllocation(intent).succeeded(),"SAME_OWNER_SETTINGS_EXACT: allocation");
            require(authority.allocateOrVerifyCatalog(intent,CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY).succeeded(),"SAME_OWNER_SETTINGS_EXACT: catalog");
            catalog.changeAvailability(targetId,DesiredAvailability.EAGER_ENABLED);catalog.flush(server);
            var result=UniverseLifecycleService.materializeAllocated(server,targetId);
            require(result.active(),"SAME_OWNER_SETTINGS_EXACT: target materialization");
            var target=result.world().orElseThrow();var targetRecord=catalog.flushedRecords().get(targetId);
            var expectedParameters=new NbtCompound();expectedParameters.putString("OverworldPreset",preset);expectedParameters.putBoolean("GenerateStructures",true);
            String provider="quantumchamber:vanilla_"+preset+"_v1";
            var expected=Minecraft121SettingsLifecycle.registry().createSnapshot(provider,1,expectedParameters,Minecraft121SettingsLifecycle.acquire(server));
            require(policy.policyVersion()==2 && policy.familyCapabilityVersion()==0 && intent.policyVersion()==2 && intent.familyCapabilityVersion()==0
                    && intent.rootWorldSeed()==SEED && intent.sourceRootWorldSeed()==SEED
                    && intent.sourceSettingsSnapshot().toNbt().equals(expected.toNbt()) && intent.targetSettingsSnapshot().toNbt().equals(expected.toNbt()),
                    "SAME_OWNER_SETTINGS_EXACT: frozen intent");
            var settingsRows=new ArrayList<Map<String,Object>>();
            for(var record:List.of(sourceRecord,targetRecord)) {
                var actual=GenerationSettingsSnapshot.fromNbt(record.generation().targetSettingsSnapshot());
                require(record.equals(catalog.records().get(record.definition().universeId())) && record.generation().rootWorldSeed()==SEED
                        && actual.providerId().equals(provider) && actual.providerVersion()==1
                        && actual.parameters().getString("OverworldPreset").equals(preset) && actual.parameters().equals(expectedParameters)
                        && Arrays.equals(actual.outputFingerprint(),expected.outputFingerprint()) && actual.toNbt().equals(expected.toNbt()),
                        "SAME_OWNER_SETTINGS_EXACT: source/target snapshot");
                settingsRows.add(Map.of("worldKey",record.definition().worlds().get(DimensionRole.OVERWORLD).worldKey().getValue().toString(),
                        "seed",Long.toString(record.generation().rootWorldSeed()),"providerId",actual.providerId(),"providerVersion",actual.providerVersion(),
                        "preset",preset,"parameters",actual.parameters().toString(),"fingerprint",HexFormat.of().formatHex(actual.outputFingerprint())));
            }
            LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_OWNER_SETTINGS {}",JSON.toJson(Map.of("preset",preset,"status","PASS","worlds",settingsRows)));
            nativeConsumer(server,source,sourceRecord);
            nativeConsumer(server,target,targetRecord);
        }
    }

    /** 同 JVM owner 控制；跨 JVM 的正式玩家路徑由上方 Flow 獨立驗證。 */
    static void verifyGameTest(MinecraftServer server) throws Exception {
        var root=server.getSavePath(WorldSavePath.ROOT);
        var entropy=CandidateEntropyState.loadOrCreate(root,false);
        for(String preset:List.of("normal","amplified")) {
            var sourceId=UniverseId.of(UUID.randomUUID());var source=fixtureSource(server,sourceId,preset);
            var snapshot=GenerationSettingsSnapshot.fromNbt(UniverseRegistryState.get(server).flushedRecords().get(sourceId).generation().targetSettingsSnapshot());
            var policy=M6CandidatePolicySelector.forNewSession(server).freeze(-1,entropy.entropyFingerprint(),new SourceFamilyRef.Catalog(sourceId,DimensionRole.OVERWORLD));
            var resolver=new CandidateResolver(entropy,Minecraft121SettingsLifecycle.registry(),()->Minecraft121SettingsLifecycle.acquire(server));
            NewUniverseIntent intent=null;var sid=UUID.randomUUID();
            for(int i=0;i<4096 && intent==null;i++) {
                var candidate=resolver.resolve(new DoorKey(sid,i,DoorKey.DoorWallSide.NEGATIVE_LATERAL),policy,List.of(),()->new SourceGenerationSnapshot(source.getSeed(),snapshot));
                if(candidate instanceof QuantumCandidate.New fresh && NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(fresh.intent().profileId()))intent=fresh.intent();
            }
            require(intent!=null,"SAME_CANDIDATE_FIXTURE_MISSING");
            var id=UniverseAllocationLedger.deriveUniverseId(intent.allocationToken());
            require(!id.equals(sourceId) && !Files.exists(root.resolve("dimensions/quantumchamber/universe/"+id.value())),"SAME_NEW_UUID_EMPTY_STORAGE");
            var authority=CollapseAuthorities.get(server);
            require(authority.lookupOrAppendAllocation(intent).succeeded(),"SAME_ALLOCATION_FIXTURE");
            require(authority.allocateOrVerifyCatalog(intent,CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY).succeeded(),"SAME_CATALOG_FIXTURE");
            var catalog=UniverseRegistryState.get(server);catalog.changeAvailability(id,DesiredAvailability.EAGER_ENABLED);catalog.flush(server);
            var result=UniverseLifecycleService.materializeAllocated(server,id);require(result.active(),"SAME_TARGET_FIXTURE");
            source.getChunk(BUILDING);source.setBlockState(BUILDING,Blocks.DIAMOND_BLOCK.getDefaultState(),3);
            var target=result.world().orElseThrow();
            var observation=new LinkedHashMap<String,Object>();
            samplePreflight(server,root,"gametest-"+preset,List.of(source,target),observation,
                    value -> LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_SAMPLE_PREFLIGHT {}",JSON.toJson(value)));
            nativeConsumer(server,source,catalog.flushedRecords().get(sourceId));
            nativeConsumer(server,target,catalog.flushedRecords().get(id));
            compareAndEdit(source,target,false);
        }
    }

    /** 預期值只來自 checked frozen 設定；每個有限 consumer 欄先記錄，再統一判定。 */
    private static void nativeConsumer(MinecraftServer server,ServerWorld world,UniverseRecord frozen) throws Exception {
        var row=new LinkedHashMap<String,Object>();row.put("worldKey",world.getRegistryKey().getValue().toString());
        try {
            var id=frozen.definition().universeId();
            var catalog=UniverseRegistryState.get(server);
            require(frozen.equals(catalog.flushedRecords().get(id)) && frozen.equals(catalog.records().get(id)),"SAME_NATIVE_CONSUMER_EXACT: checked catalog");
            var descriptor=frozen.definition().worlds().get(DimensionRole.OVERWORLD);
            var snapshot=GenerationSettingsSnapshot.fromNbt(frozen.generation().targetSettingsSnapshot());
            long frozenSeed=frozen.generation().rootWorldSeed();
            var resolved=Minecraft121SettingsLifecycle.registry().resolve(snapshot,Minecraft121SettingsLifecycle.acquire(server));
            var expectedGenerator=(net.minecraft.world.gen.chunk.NoiseChunkGenerator)resolved.roles().get(DimensionRole.OVERWORLD).chunkGenerator();
            require(world.getChunkManager().getChunkGenerator() instanceof net.minecraft.world.gen.chunk.NoiseChunkGenerator,"SAME_NATIVE_CONSUMER_EXACT: generator");
            var actualGenerator=(net.minecraft.world.gen.chunk.NoiseChunkGenerator)world.getChunkManager().getChunkGenerator();
            var expectedNoise=net.minecraft.world.gen.noise.NoiseConfig.create(expectedGenerator.getSettings().value(),
                    world.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),frozenSeed);
            var actualNoise=world.getChunkManager().getNoiseConfig();
            var actualSamples=new ArrayList<String>();var expectedSamples=new ArrayList<String>();
            int[][] points={{0,64,0},{1032,80,-2056},{-9012,24,7196},{32768,112,16384}};
            for(var point:points) {
                var pos=new net.minecraft.world.gen.densityfunction.DensityFunction.UnblendedNoisePos(point[0],point[1],point[2]);
                expectedSamples.add(Long.toHexString(Double.doubleToRawLongBits(expectedNoise.getNoiseRouter().finalDensity().sample(pos)))+":"
                        +expectedGenerator.getBiomeSource().getBiome(point[0],point[1],point[2],expectedNoise.getMultiNoiseSampler()).getKey().orElseThrow().getValue());
                actualSamples.add(Long.toHexString(Double.doubleToRawLongBits(actualNoise.getNoiseRouter().finalDensity().sample(pos)))+":"
                        +actualGenerator.getBiomeSource().getBiome(point[0],point[1],point[2],actualNoise.getMultiNoiseSampler()).getKey().orElseThrow().getValue());
            }
            var expectedPlacement=expectedGenerator.createStructurePlacementCalculator(world.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),expectedNoise,frozenSeed);
            var actualPlacement=world.getChunkManager().getStructurePlacementCalculator();
            var owner=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.rootSeed(world);
            long locatorSeed=(long)field(field(world,"structureLocator"),"seed");
            long biomeSeed=(long)field(world.getBiomeAccess(),"seed");
            row.put("frozenSeed",Long.toString(frozenSeed));row.put("actualSeed",Long.toString(world.getSeed()));
            row.put("ownerSeed",owner.isPresent()?Long.toString(owner.getAsLong()):"ABSENT");
            row.put("targetFingerprint",HexFormat.of().formatHex(snapshot.outputFingerprint()));
            row.put("expectedSettings",expectedGenerator.getSettings().getKey().orElseThrow().getValue().toString());
            row.put("actualSettings",actualGenerator.getSettings().getKey().orElseThrow().getValue().toString());
            row.put("points",points);row.put("actualSamples",actualSamples);row.put("expectedSamples",expectedSamples);
            row.put("locatorSeed",Long.toString(locatorSeed));row.put("biomeAccessSeed",Long.toString(biomeSeed));
            row.put("expectedBiomeAccessSeed",Long.toString(net.minecraft.world.biome.source.BiomeAccess.hashSeed(frozenSeed)));
            row.put("structureSeed",field(actualPlacement,"structureSeed").toString());row.put("expectedStructureSeed",field(expectedPlacement,"structureSeed").toString());
            row.put("concentricRingSeed",field(actualPlacement,"concentricRingSeed").toString());row.put("expectedConcentricRingSeed",field(expectedPlacement,"concentricRingSeed").toString());
            row.put("actualStructures",world.getStructureAccessor().shouldGenerateStructures());row.put("expectedStructures",resolved.generateStructures());
            boolean exact=world.getServer()==server && server.getWorld(world.getRegistryKey())==world && descriptor.worldKey().equals(world.getRegistryKey())
                    && owner.isPresent() && owner.getAsLong()==frozenSeed && world.getSeed()==frozenSeed
                    && actualGenerator.getSettings().getKey().equals(expectedGenerator.getSettings().getKey())
                    && actualSamples.equals(expectedSamples) && locatorSeed==frozenSeed
                    && biomeSeed==net.minecraft.world.biome.source.BiomeAccess.hashSeed(frozenSeed)
                    && field(actualPlacement,"structureSeed").equals(field(expectedPlacement,"structureSeed"))
                    && field(actualPlacement,"concentricRingSeed").equals(field(expectedPlacement,"concentricRingSeed"))
                    && world.getStructureAccessor().shouldGenerateStructures()==resolved.generateStructures();
            row.put("exact",exact);row.put("status",exact?"PASS":"FAIL");
            require(exact,"SAME_NATIVE_CONSUMER_EXACT: "+world.getRegistryKey().getValue());
        } catch(Exception failure) {
            row.put("status","FAIL");row.put("error",failure.toString());
            throw new IllegalStateException("SAME_NATIVE_CONSUMER_EXACT: "+failure,failure);
        } finally {
            LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_NATIVE_CONSUMER {}",JSON.toJson(row));
        }
    }

    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try {var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
            catch(NoSuchFieldException absent) { /* 只向上找原版同名欄位，不改值。 */ }
        }
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }

    static ServerWorld fixtureSource(MinecraftServer server,UniverseId id,String preset) {
        var catalog=UniverseRegistryState.get(server);catalog.upgradeEnvelope();catalog.flush(server);
        var parameters=new NbtCompound();parameters.putString("OverworldPreset",preset);parameters.putBoolean("GenerateStructures",true);
        var snapshot=Minecraft121SettingsLifecycle.registry().createSnapshot("quantumchamber:vanilla_"+preset+"_v1",1,parameters,Minecraft121SettingsLifecycle.acquire(server));
        var roles=new EnumMap<DimensionRole,UniverseWorldDescriptor>(DimensionRole.class);
        for(var role:DimensionRole.values())roles.put(role,new UniverseWorldDescriptor(UniverseKeys.world(id,role),role,null,SeedPolicy.EXPLICIT_ROOT_SEED_V1,1));
        catalog.allocateM6(new UniverseRecord(new UniverseDefinition(2,id,catalog.records().size(),roles),DesiredAvailability.DISABLED,
                new UniverseRecord.Generation(SEED,NewUniverseIntent.SAME_SEED_PROFILE_ID,1,snapshot.toNbt(),UniverseRecord.Provenance.NEW_M6,null)));
        catalog.flush(server);catalog.changeAvailability(id,DesiredAvailability.EAGER_ENABLED);catalog.flush(server);
        var result=UniverseLifecycleService.materializeAllocated(server,id);require(result.active(),"SAME_FIXTURE_SOURCE:"+result.detail());
        return result.world().orElseThrow();
    }

    /** 兩側同一 registry/datapack、同一 FULL 鄰域後採樣，不把 live/raw 不同成熟度混比。 */
    static Map<String,Object> compareAndEdit(ServerWorld source,ServerWorld target,boolean restart) throws Exception {
        return compareAndEdit(source,target,restart,new LinkedHashMap<>(),null);
    }
    private static Map<String,Object> compareAndEdit(ServerWorld source,ServerWorld target,boolean restart,
            Map<String,Object> row,Path comparisonReceipt) throws Exception {
        require(source!=target && source.getRegistryManager()==target.getRegistryManager() && source.getSeed()==target.getSeed(),"SAME_RUNTIME_IDENTITIES");
        // 來源先為玩家建築生成中心；目標也先中心，避免把不同 feature 寫入順序混成 seed 差異。
        var worlds=List.of(source,target);
        var requests=List.of(new ArrayList<String>(),new ArrayList<String>());
        var globalRequests=new ArrayList<String>();
        boolean stageWitness=comparisonReceipt==null;
        var stages=new ArrayList<Map<String,Object>>();
        var centers=new net.minecraft.world.chunk.WorldChunk[2];
        // 保留 normal5 的全球交錯：兩側中心完成後，才依序完成各自鄰域。
        for(int i=0;i<2;i++) {
            var center=worlds.get(i).getChunk(32,32);
            String request="32,32="+center.getStatus();
            requests.get(i).add(request);globalRequests.add((i==0?"source:":"target:")+request);
            if(stageWitness) {
                centers[i]=center;
                recordStage(i,source,target,centers,stages);
            }
        }
        for(int i=0;i<2;i++) {
            for(int x=31;x<=33;x++)for(int z=31;z<=33;z++) {
                String request=x+","+z+"="+worlds.get(i).getChunk(x,z).getStatus();
                requests.get(i).add(request);globalRequests.add((i==0?"source:":"target:")+request);
            }
            if(stageWitness)recordStage(i+2,source,target,centers,stages);
        }
        row.put("sourceRequests",requests.getFirst());row.put("targetRequests",requests.getLast());row.put("globalRequests",globalRequests);
        require(requests.getFirst().equals(requests.getLast()),"SAME_GENERATION_REQUEST_SEQUENCE");
        if(stageWitness) {
            requireStageReceipts(source,target,stages);
            row.put("stages",stages);
        }
        var sourceTerrain=new ArrayList<String>();var targetTerrain=new ArrayList<String>();
        var sourceBiomes=new ArrayList<String>();var targetBiomes=new ArrayList<String>();
        String firstDifference="";
        for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8) {
            var pos=new BlockPos(x,y,z);
            sourceTerrain.add(source.getBlockState(pos).toString());targetTerrain.add(target.getBlockState(pos).toString());
            sourceBiomes.add(source.getBiome(pos).getKey().orElseThrow().getValue().toString());
            targetBiomes.add(target.getBiome(pos).getKey().orElseThrow().getValue().toString());
            if(firstDifference.isEmpty() && (!sourceTerrain.getLast().equals(targetTerrain.getLast()) || !sourceBiomes.getLast().equals(targetBiomes.getLast())))
                firstDifference=" pos="+pos+" source="+sourceTerrain.getLast()+" target="+targetTerrain.getLast()
                        +" sourceBiome="+sourceBiomes.getLast()+" targetBiome="+targetBiomes.getLast()
                        +" seed="+source.getSeed()+" sourceStatus="+source.getChunk(pos).getStatus()+" targetStatus="+target.getChunk(pos).getStatus();
        }
        row.put("firstDifference",firstDifference);row.put("terrain",digest(sourceTerrain));row.put("targetTerrain",digest(targetTerrain));
        row.put("biomes",digest(sourceBiomes));row.put("targetBiomes",digest(targetBiomes));row.put("sampleCount",sourceTerrain.size());
        row.put("matchedMaturity","FULL_3X3");
        if(comparisonReceipt!=null)writeNew(comparisonReceipt,row);
        else LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_COMPARISON {}",JSON.toJson(row));
        require(sourceTerrain.equals(targetTerrain) && sourceBiomes.equals(targetBiomes),"sameSeedFreshCopiesSettingsNotBlocks:TERRAIN_BIOME"+firstDifference);
        var a=structures(source);var b=structures(target);require(a.equals(b),"sameSeedFreshCopiesSettingsNotBlocks:STRUCTURE");
        require(source.getBlockState(BUILDING).isOf(Blocks.DIAMOND_BLOCK) && !target.getBlockState(BUILDING).isOf(Blocks.DIAMOND_BLOCK),"sameSeedFreshCopiesSettingsNotBlocks:BUILDING");
        if(!restart){source.setBlockState(SOURCE_EDIT,Blocks.EMERALD_BLOCK.getDefaultState(),3);target.setBlockState(TARGET_EDIT,Blocks.GOLD_BLOCK.getDefaultState(),3);}
        require(source.getBlockState(SOURCE_EDIT).isOf(Blocks.EMERALD_BLOCK) && !target.getBlockState(SOURCE_EDIT).isOf(Blocks.EMERALD_BLOCK)
                && target.getBlockState(TARGET_EDIT).isOf(Blocks.GOLD_BLOCK) && !source.getBlockState(TARGET_EDIT).isOf(Blocks.GOLD_BLOCK),"freshUniversesDivergeAfterEdits");
        row.put("structures",a);
        row.put("sampleCount",sourceTerrain.size());row.put("matchedMaturity","FULL_3X3");row.put("buildingAbsent",true);row.put("editsIndependent",true);return row;
    }

    private static final List<String> STAGES=List.of("SOURCE_CENTER","BOTH_CENTER","AFTER_SOURCE_NEIGHBORS","AFTER_TARGET_NEIGHBORS");

    /** 只用原 getChunk 回傳的中心引用；不另求 chunk、不讀 world block/biome。 */
    private static void recordStage(int stageIndex,ServerWorld source,ServerWorld target,
            net.minecraft.world.chunk.WorldChunk[] centers,List<Map<String,Object>> stages) throws Exception {
        var sampleWorlds=List.of(source,target);
        var sides=new ArrayList<Map<String,Object>>();var values=new ArrayList<List<String>>();
        for(int i=0;i<2;i++) {
            var world=sampleWorlds.get(i);var chunk=centers[i];var side=new LinkedHashMap<String,Object>();
            side.put("worldKey",world.getRegistryKey().getValue().toString());side.put("seed",Long.toString(world.getSeed()));
            if(chunk==null) {
                side.put("readiness","NOT_READY");side.put("chunkStatus","NOT_READY");side.put("sampleCount",0);
                values.add(List.of());
            } else {
                require(chunk.getWorld()==world && chunk.getPos().equals(new ChunkPos(32,32))
                        && chunk.getStatus()==net.minecraft.world.chunk.ChunkStatus.FULL,"SAME_STAGE_RECEIPT_INCOMPLETE: center identity");
                var blocks=new ArrayList<String>();
                for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8)
                    blocks.add(chunk.getBlockState(new BlockPos(x,y,z)).toString());
                side.put("readiness","READY");side.put("chunkStatus",chunk.getStatus().toString());
                side.put("sampleCount",blocks.size());side.put("blockHash",digest(blocks));values.add(blocks);
            }
            sides.add(side);
        }
        String difference="NOT_READY";
        if(centers[0]!=null && centers[1]!=null) {
            difference="NONE";int index=0;
            for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8) {
                if(difference.equals("NONE") && !values.get(0).get(index).equals(values.get(1).get(index)))
                    difference="pos="+new BlockPos(x,y,z)+" source="+values.get(0).get(index)+" target="+values.get(1).get(index);
                index++;
            }
        }
        var stage=new LinkedHashMap<String,Object>();stage.put("stageIndex",stageIndex);stage.put("stage",STAGES.get(stageIndex));
        stage.put("tick",source.getServer().getTicks());stage.put("sampleCount",1440);
        stage.put("source",sides.get(0));stage.put("target",sides.get(1));stage.put("firstDifference",difference);
        stages.add(stage);
        LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_STAGE {}",JSON.toJson(stage));
    }

    @SuppressWarnings("unchecked")
    private static void requireStageReceipts(ServerWorld source,ServerWorld target,List<Map<String,Object>> stages) {
        require(stages.size()==4,"SAME_STAGE_RECEIPT_INCOMPLETE: count="+stages.size());
        for(int i=0;i<4;i++) {
            var row=stages.get(i);
            require(Integer.valueOf(i).equals(row.get("stageIndex")) && STAGES.get(i).equals(row.get("stage"))
                    && row.get("tick") instanceof Integer && Integer.valueOf(1440).equals(row.get("sampleCount")),"SAME_STAGE_RECEIPT_INCOMPLETE: stage="+i);
            for(int side=0;side<2;side++) {
                var sample=(Map<String,Object>)row.get(side==0?"source":"target");var world=side==0?source:target;
                boolean ready=i!=0 || side==0;
                require(sample!=null && world.getRegistryKey().getValue().toString().equals(sample.get("worldKey"))
                        && Long.toString(world.getSeed()).equals(sample.get("seed"))
                        && (ready?"READY":"NOT_READY").equals(sample.get("readiness"))
                        && Integer.valueOf(ready?1440:0).equals(sample.get("sampleCount")),"SAME_STAGE_RECEIPT_INCOMPLETE: side="+side);
                require(ready ? "minecraft:full".equals(sample.get("chunkStatus")) && sample.get("blockHash") instanceof String hash && hash.matches("[0-9a-f]{64}")
                        : "NOT_READY".equals(sample.get("chunkStatus")) && !sample.containsKey("blockHash"),"SAME_STAGE_RECEIPT_INCOMPLETE: hash/status");
            }
            require(row.get("firstDifference") instanceof String value && !value.isBlank()
                    && (i==0?value.equals("NOT_READY"):!value.equals("NOT_READY")),"SAME_STAGE_RECEIPT_INCOMPLETE: difference");
        }
    }

    private static List<String> structures(ServerWorld world) {
        var generator=world.getChunkManager().getChunkGenerator();var calculator=world.getChunkManager().getStructurePlacementCalculator();
        var entry=world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntry(RegistryKey.of(RegistryKeys.STRUCTURE,Identifier.ofVanilla("monument"))).orElseThrow();
        for(var placement:calculator.getPlacements(entry))if(placement instanceof RandomSpreadStructurePlacement spread)
            for(int x=-8;x<=8;x++)for(int z=-8;z<=8;z++) {
                var pos=spread.getStartChunk(world.getSeed(),x*spread.getSpacing(),z*spread.getSpacing());
                if(!spread.shouldGenerate(calculator,pos.x,pos.z))continue;
                var context=new Structure.Context(world.getRegistryManager(),generator,generator.getBiomeSource(),world.getChunkManager().getNoiseConfig(),
                        world.getServer().getStructureTemplateManager(),world.getSeed(),pos,world,entry.value().getValidBiomes()::contains);
                if(entry.value().getValidStructurePosition(context).isEmpty())continue;
                var start=world.getChunk(pos.x,pos.z).getStructureStart(entry.value());
                require(start!=null && start.hasChildren(),"SAME_STRUCTURE_POSITIVE");
                return List.of(pos.toString(),start.getBoundingBox().toString(),Integer.toString(start.getChildren().size()));
            }
        throw new IllegalStateException("SAME_STRUCTURE_POSITIVE_NOT_FOUND");
    }
    private static String digest(Object value) throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(JSON.toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
    private static String hash(Path path) throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void writeNew(Path path,Object value) throws Exception {Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(value),StandardOpenOption.CREATE_NEW);}
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
}
