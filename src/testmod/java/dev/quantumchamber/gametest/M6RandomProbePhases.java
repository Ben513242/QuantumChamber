package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.corridor.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.superposition.*;
import dev.quantumchamber.registry.*;
import dev.quantumchamber.universe.*;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.world.gen.structure.Structure;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.chunk.*;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.util.math.*;
import net.minecraft.item.*;
import net.minecraft.loot.context.*;
import net.minecraft.loot.function.ExplorationMapLootFunction;
import java.util.concurrent.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.slf4j.LoggerFactory;

/** Task11 的 seed owner fixture；Task12 GameplayPhases 另走真正式玩家 candidate／collapse 路徑。 */
public final class M6RandomProbePhases {
    private static final long[] SEEDS = {712345678901L, -99123456789L};
    private static final int[][] POINTS = {{0,64,0},{1032,80,-2056},{-9012,24,7196},{32768,112,16384}};
    private final String phase;
    private final Path root;
    private final Path evidence;
    private final Map<String,Object> proof;
    private MinecraftServer server;
    private boolean sampled;

    M6RandomProbePhases(String phase, Path root, Path evidence, Map<String,Object> proof) {
        this.phase=phase; this.root=root; this.evidence=evidence; this.proof=proof;
    }
    void started(MinecraftServer server) { this.server=server; }
    void tick(MinecraftServer owner) {
        if(owner!=server || sampled) return;
        sampled=true;
        try { execute(); proof.put("functionalPassed",true); }
        catch(Exception failure) {
            failures().add(failure.toString());
            LoggerFactory.getLogger("quantumchamber-m6-native").error("M6_SEED_FUNCTIONAL_FAILED",failure);
        }
        proof.put("stopRequested",true); owner.stop(false);
    }
    void stopping(MinecraftServer owner) { if(owner==server) proof.put("stoppingSeen",true); }
    void stopped(MinecraftServer owner) {
        if(owner!=server)return;
        proof.put("stoppedSeen",true);
        proof.put("status",sampled && failures().isEmpty() && Boolean.TRUE.equals(proof.get("functionalPassed"))?"PASS":"FAIL");
        try {
            Files.createDirectories(evidence);
            Files.writeString(evidence.resolve("final.json"),new Gson().toJson(proof),StandardOpenOption.CREATE_NEW);
        } catch(Exception failure) {throw new IllegalStateException("M6_FUNCTIONAL_RECEIPT_WRITE",failure);}
        LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_NATIVE_END chain=native-seed-functional phase={} mode=FUNCTIONAL status={}",phase,proof.get("status"));
    }
    @SuppressWarnings("unchecked") private List<String> failures() {return (List<String>)proof.get("failures");}

    static void verifyGameTest(MinecraftServer server) throws Exception {
        var proof=new LinkedHashMap<String,Object>();proof.put("failures",new ArrayList<String>());
        var probe=new M6RandomProbePhases("gametest",null,null,proof);
        probe.server=server;probe.execute();
    }

    private void execute() throws Exception {
        require(server.isOnThread() && UniverseLifecycleService.ready(server),"M6_FUNCTIONAL_OWNER_NOT_READY");
        var catalog=UniverseRegistryState.get(server);
        if(!phase.equals("reopen")) {
            if(phase.equals("construct"))require(catalog.records().isEmpty(),"M6_FUNCTIONAL_NOT_FRESH");
            catalog.allocateOverworld(new UUID(0,67003),0); catalog.flush(server);
            catalog.upgradeEnvelope(); catalog.flush(server);
            for(int i=0;i<2;i++) {
                var id=UniverseId.of(new UUID(0,67001+i));
                var parameters=new NbtCompound(); parameters.putString("OverworldPreset","normal");
                parameters.putBoolean("GenerateStructures",i==0);
                var snapshot=Minecraft121SettingsLifecycle.registry().createSnapshot("quantumchamber:vanilla_normal_v1",1,
                        parameters,Minecraft121SettingsLifecycle.acquire(server));
                var roles=new EnumMap<DimensionRole,UniverseWorldDescriptor>(DimensionRole.class);
                for(var role:DimensionRole.values()) roles.put(role,new UniverseWorldDescriptor(UniverseKeys.world(id,role),
                        role,null,SeedPolicy.EXPLICIT_ROOT_SEED_V1,1));
                var generation=new UniverseRecord.Generation(SEEDS[i],NewUniverseIntent.RANDOM_PROFILE_ID,1,
                        snapshot.toNbt(),UniverseRecord.Provenance.NEW_M6,null);
                catalog.allocateM6(new UniverseRecord(new UniverseDefinition(2,id,i+1,roles),DesiredAvailability.DISABLED,generation));
                catalog.flush(server);
                catalog.changeAvailability(id,DesiredAvailability.EAGER_ENABLED); catalog.flush(server);
            }
        }
        constructorFailureClearsContext(catalog);
        var legacy=UniverseLifecycleService.materializeAllocated(server,UniverseId.of(new UUID(0,67003)));
        require(legacy.active(),"M6_LEGACY_BACKEND_REJECTED");
        long saveSeed=server.getSaveProperties().getGeneratorOptions().getSeed();
        if(!phase.equals("gametest")) {
            var canonical=(NoiseChunkGenerator)server.getOverworld().getChunkManager().getChunkGenerator();
            require(canonical.getSettings().getKey().orElseThrow().getValue().equals(Identifier.ofVanilla("amplified")),"M6_CANONICAL_PROFILE_CONTROL");
            proof.put("canonicalSettingsKey","minecraft:amplified");
        }
        require(legacy.world().orElseThrow().getSeed()==saveSeed,"legacySchemaOneStillUsesSaveSeed");
        require(legacy.world().orElseThrow().getRandomSequences()==server.getOverworld().getRandomSequences(),"M6_LEGACY_SEQUENCE_CHANGED");
        var rows=new ArrayList<Map<String,Object>>();
        var worlds=new ArrayList<ServerWorld>();
        for(int i=0;i<2;i++) {
            var result=UniverseLifecycleService.materializeAllocated(server,UniverseId.of(new UUID(0,67001+i)));
            require(result.active(),"newUniverseGetSeedChangesAfterConstruction: backend="+result.status()+"/"+result.detail());
            var world=result.world().orElseThrow(); worlds.add(world);
            var descriptor=catalog.flushedRecords().get(UniverseId.of(new UUID(0,67001+i))).definition().worlds().get(DimensionRole.OVERWORLD);
            require(UniverseLifecycleService.rootSeed(server,world.getRegistryKey(),descriptor).orElse(Long.MIN_VALUE)==SEEDS[i],"M6_LIFECYCLE_ROOT_OWNER");
            require(world.getSeed()==SEEDS[i],"newUniverseGetSeedChangesAfterConstruction: expected="+SEEDS[i]+" actual="+world.getSeed());
            var row=observe(world,SEEDS[i],i==0);
            row.putAll(workerAndLocator(world,i==0,true));
            rows.add(row);
        }
        require(worlds.get(0).getRandomSequences()!=worlds.get(1).getRandomSequences()
                && worlds.stream().noneMatch(w->w.getRandomSequences()==server.getOverworld().getRandomSequences()),"twoUniversesDoNotShareRandomSequences");
        var sequenceId=Identifier.of("quantumchamber:m6_functional_sequence");
        for(int i=0;i<2;i++) {
            var control=new RandomSequencesState(SEEDS[i]).getOrCreate(sequenceId);
            if(phase.equals("reopen"))control.nextLong();
            long expected=control.nextLong(), actual=worlds.get(i).getOrCreateRandom(sequenceId).nextLong();
            require(actual==expected,"M6_FUNCTIONAL_SEQUENCE_CONTINUITY");
            rows.get(i).put("sequenceValue",Long.toString(actual));
            rows.get(i).put("sequenceExpected",Long.toString(expected));
        }
        verifyWorldMonitorRead(worlds);
        verifyThirdUniversePublication(worlds,catalog);
        proof.put("parallelWorkers",parallelWorkers(worlds,rows));
        proof.put("worlds",rows);proof.put("legacySeed",Long.toString(saveSeed));
        proof.put("legacyControl",true);proof.put("independentSequences",true);
        proof.put("catalogChecked",catalog.records().equals(catalog.flushedRecords()));
        require(rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("workerExact"))),"M6_FROZEN_WORKER_BRANCH");
        require(rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("locatorExact"))),"M6_LOCATE_FROZEN_FLAG_MISMATCH");
        require(rows.stream().allMatch(row->Boolean.TRUE.equals(row.get("mapExact"))),"M6_EXPLORATION_MAP_FROZEN_FLAG_MISMATCH");
        var controls=new ArrayList<Map<String,Object>>();
        // GameTest 的 canonical/legacy 是 flat fixture；monument 正控制固定由兩phase normal native鏈驗。
        if(!phase.equals("gametest")) for(var controlWorld:List.of(server.getOverworld(),legacy.world().orElseThrow())) {
            for(boolean flag:List.of(true,false)) {
                var control=workerAndLocator(controlWorld,flag,false);
                require(Boolean.TRUE.equals(control.get("workerExact")) && Boolean.TRUE.equals(control.get("locatorExact"))
                        && Boolean.TRUE.equals(control.get("mapExact")),"M6_CANONICAL_LEGACY_LOCATOR_CONTROL");
                control.put("worldKey",controlWorld.getRegistryKey().getValue().toString()); controls.add(control);
            }
        }
        proof.put("vanillaControls",controls);
        proof.put("globalAtStart",server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures());
        if(phase.equals("construct")) setGlobalStructures(false);
        else if(phase.equals("reopen")) require(!server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures(),"M6_REOPEN_GLOBAL_NOT_CHANGED");
        proof.put("globalForNextStart",server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures());
    }

    private void constructorFailureClearsContext(UniverseRegistryState catalog) throws Exception {
        var descriptor=catalog.flushedRecords().get(UniverseId.of(new UUID(0,67001))).definition().worlds().get(DimensionRole.OVERWORLD);
        var type=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.class;
        var scope=type.getDeclaredMethod("withConstruction",dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.GenerationOwner.class,java.util.function.Supplier.class);
        scope.setAccessible(true);
        var owner=new dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.GenerationOwner(server,descriptor,SEEDS[0],true);
        var access=(dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess)server;
        java.util.function.Supplier<ServerWorld> failing=()->new ServerWorld(server,access.quantumchamber$getWorkerExecutor(),
                access.quantumchamber$getSession(),new net.minecraft.world.level.UnmodifiableLevelProperties(server.getSaveProperties(),
                server.getSaveProperties().getMainWorldProperties()),descriptor.worldKey(),null,
                net.minecraft.server.WorldGenerationProgressLogger.noSpawnChunks(),false,0L,List.of(),false,null);
        boolean nativeFailure=false;
        try {scope.invoke(null,owner,failing);}
        catch(java.lang.reflect.InvocationTargetException failure) {
            var cause=failure.getCause();
            nativeFailure=cause instanceof NullPointerException && Arrays.stream(cause.getStackTrace())
                    .anyMatch(frame->frame.getClassName().equals(ServerWorld.class.getName()) && frame.getMethodName().equals("<init>"));
            if(!nativeFailure)throw failure;
        }
        require(nativeFailure,"M6_NATIVE_CONSTRUCTOR_FAILURE_NOT_OBSERVED");
        require(!dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.constructionPending(),"M6_CONSTRUCTION_NOT_CLEARED");
        require(server.getOverworld().getSeed()==server.getSaveProperties().getGeneratorOptions().getSeed(),"M6_CONSTRUCTION_LEAK_CANONICAL");
        proof.put("nativeConstructorFailed",true);proof.put("constructionCleared",true);
    }

    private Map<String,Object> observe(ServerWorld world,long seed,boolean structures) throws Exception {
        require(world.getChunkManager().getChunkGenerator() instanceof NoiseChunkGenerator,"M6_TARGET_PROFILE_GENERATOR");
        var generator=(NoiseChunkGenerator)world.getChunkManager().getChunkGenerator();
        require(generator.getSettings().getKey().orElseThrow().getValue().equals(Identifier.ofVanilla("overworld")),"M6_TARGET_SETTINGS_PROFILE");
        var noise=world.getChunkManager().getNoiseConfig();
        var expected=NoiseConfig.create(generator.getSettings().value(),world.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),seed);
        var expectedCalculator=generator.createStructurePlacementCalculator(world.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),expected,seed);
        var calculator=world.getChunkManager().getStructurePlacementCalculator();
        require(field(world,"structureLocator")!=null,"M6_LOCATOR_ABSENT");
        require((long)field(field(world,"structureLocator"),"seed")==seed,"noiseStructureBiomeUseFrozenRoot: locator");
        require(field(calculator,"structureSeed").equals(field(expectedCalculator,"structureSeed"))
                && field(calculator,"concentricRingSeed").equals(field(expectedCalculator,"concentricRingSeed")),"noiseStructureBiomeUseFrozenRoot: placement");
        require((long)field(world.getBiomeAccess(),"seed")==BiomeAccess.hashSeed(seed),"noiseStructureBiomeUseFrozenRoot: biomeAccess");
        var actualSamples=samples(world,noise);var expectedSamples=samples(world,expected);
        require(actualSamples.equals(expectedSamples),"noiseStructureBiomeUseFrozenRoot: noise/biome");
        require(world.getStructureAccessor().shouldGenerateStructures()==structures,"M6_FROZEN_STRUCTURE_ACCESSOR");
        var row=new LinkedHashMap<String,Object>();
        row.put("worldKey",world.getRegistryKey().getValue().toString());row.put("seed",Long.toString(world.getSeed()));
        row.put("settingsKey",generator.getSettings().getKey().orElseThrow().getValue().toString());
        row.put("frozenStructures",structures);row.put("accessorStructures",world.getStructureAccessor().shouldGenerateStructures());
        row.put("samples",actualSamples);row.put("nativeConsumersExact",true);
        return row;
    }
    /** 真第三 U 的 checked 配置、發布與完整卸載，與兩個已發布 U 的 worker 查詢重疊。 */
    private void verifyThirdUniversePublication(List<ServerWorld> worlds,UniverseRegistryState catalog) throws Exception {
        var id=UniverseId.of(new UUID(0,67004));
        var running=new java.util.concurrent.atomic.AtomicBoolean(true);
        var ready=new CountDownLatch(2);
        var counts=new java.util.concurrent.atomic.AtomicLongArray(2);
        var executor=Executors.newFixedThreadPool(2);
        var tasks=new ArrayList<Future<Long>>();
        try {
            for(int i=0;i<2;i++) {
                final int index=i;var world=worlds.get(i);
                tasks.add(executor.submit(()->{
                    while(running.get()) {
                        var seed=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.rootSeed(world);
                        boolean flag=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.generateStructures(world,index!=0);
                        require(seed.orElse(Long.MIN_VALUE)==SEEDS[index] && flag==(index==0),"M6_BACKGROUND_OWNER_CHANGED_DURING_THIRD_U");
                        if(counts.incrementAndGet(index)==1)ready.countDown();
                        java.util.concurrent.locks.LockSupport.parkNanos(100_000);
                    }
                    return counts.get(index);
                }));
            }
            require(ready.await(10,TimeUnit.SECONDS),"M6_THIRD_U_WORKERS_NOT_READY");
            long before0=counts.get(0),before1=counts.get(1);
            if(!catalog.flushedRecords().containsKey(id)) {
                var parameters=new NbtCompound();parameters.putString("OverworldPreset","normal");parameters.putBoolean("GenerateStructures",true);
                var snapshot=Minecraft121SettingsLifecycle.registry().createSnapshot("quantumchamber:vanilla_normal_v1",1,
                        parameters,Minecraft121SettingsLifecycle.acquire(server));
                var roles=new EnumMap<DimensionRole,UniverseWorldDescriptor>(DimensionRole.class);
                for(var role:DimensionRole.values())roles.put(role,new UniverseWorldDescriptor(UniverseKeys.world(id,role),role,null,SeedPolicy.EXPLICIT_ROOT_SEED_V1,1));
                var generation=new UniverseRecord.Generation(314159265358L,NewUniverseIntent.RANDOM_PROFILE_ID,1,
                        snapshot.toNbt(),UniverseRecord.Provenance.NEW_M6,null);
                catalog.allocateM6(new UniverseRecord(new UniverseDefinition(2,id,3,roles),DesiredAvailability.DISABLED,generation));catalog.flush(server);
            }
            require(catalog.flushedRecords().get(id).desiredAvailability()==DesiredAvailability.DISABLED,"M6_THIRD_U_NOT_DISABLED");
            catalog.changeAvailability(id,DesiredAvailability.EAGER_ENABLED);catalog.flush(server);
            var result=UniverseLifecycleService.materializeAllocated(server,id);
            require(result.active(),"M6_THIRD_U_MATERIALIZE_FAILED");
            var world=result.world().orElseThrow();
            require(world.getSeed()==314159265358L,"M6_THIRD_U_SEED_CHANGED");
            var contexts=UniverseLifecycleService.class.getDeclaredField("CONTEXTS");contexts.setAccessible(true);
            var context=((Map<?,?>)contexts.get(null)).get(server);
            var backend=(dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend)field(context,"backend");
            var descriptor=catalog.flushedRecords().get(id).definition().worlds().get(DimensionRole.OVERWORLD);
            require(backend.unload(server,descriptor,world)==UnloadResult.UNLOADED,"M6_THIRD_U_UNLOAD_FAILED");
            require(server.getWorld(descriptor.worldKey())==null,"M6_THIRD_U_STILL_PUBLISHED");
            catalog.changeAvailability(id,DesiredAvailability.DISABLED);catalog.flush(server);
            require(counts.get(0)>before0 && counts.get(1)>before1,"M6_THIRD_U_WORKERS_DID_NOT_OVERLAP");
            proof.put("thirdUniversePublishedAndUnloaded",true);
            proof.put("thirdUniverseWorkerSamples",List.of(counts.get(0)-before0,counts.get(1)-before1));
        } finally {
            running.set(false);executor.shutdown();
            require(executor.awaitTermination(40,TimeUnit.SECONDS),"M6_THIRD_U_WORKERS_NOT_STOPPED");
            for(var task:tasks)task.get(10,TimeUnit.SECONDS);
        }
    }

    /** server 持有同一 worlds monitor 時，worker 的 live-owner 讀必須等待，不能讀正在重排的 map。 */
    private void verifyWorldMonitorRead(List<ServerWorld> worlds) throws Exception {
        var map=((dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess)server).quantumchamber$getWorlds();
        var ready=new CountDownLatch(2);var start=new CountDownLatch(1);var attempting=new CountDownLatch(2);
        var executor=Executors.newFixedThreadPool(2);
        var tasks=new ArrayList<Future<Map<String,Object>>>();
        try {
            synchronized(map) {
                for(int i=0;i<2;i++) {
                    final int index=i;var world=worlds.get(i);
                    tasks.add(executor.submit(()->{
                        ready.countDown();require(start.await(10,TimeUnit.SECONDS),"M6_MONITOR_START_TIMEOUT");
                        attempting.countDown();
                        var seed=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.rootSeed(world);
                        boolean flag=dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.generateStructures(world,index!=0);
                        return Map.<String,Object>of("seed",seed.orElse(Long.MIN_VALUE),"structures",flag,"index",index);
                    }));
                }
                require(ready.await(10,TimeUnit.SECONDS),"M6_MONITOR_WORKERS_NOT_READY");start.countDown();
                require(attempting.await(10,TimeUnit.SECONDS),"M6_MONITOR_WORKERS_NOT_ATTEMPTING");
                proof.put("worldMonitorReadAttempts",2);
                for(var task:tasks) {
                    try {
                        var escaped=task.get(500,TimeUnit.MILLISECONDS);
                        proof.put("worldMonitorEscaped",escaped);
                        throw new IllegalStateException("M6_WORLD_OWNER_READ_ESCAPED_MONITOR");
                    } catch(TimeoutException expectedBlocked) { }
                }
            }
            for(int i=0;i<tasks.size();i++) {
                var result=tasks.get(i).get(10,TimeUnit.SECONDS);
                require(result.get("seed").equals(SEEDS[i]) && result.get("structures").equals(i==0),"M6_MONITOR_RELEASE_OWNER_CHANGED");
            }
            proof.put("worldMonitorReadBlocked",true);
        } finally {start.countDown();executor.shutdown();require(executor.awaitTermination(40,TimeUnit.SECONDS),"M6_MONITOR_WORKERS_NOT_STOPPED");}
    }

    private List<Map<String,Object>> parallelWorkers(List<ServerWorld> worlds,List<Map<String,Object>> rows) throws Exception {
        var results=new ArrayList<Map<String,Object>>();
        boolean original=server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures();
        try {
            for(boolean global:List.of(true,false)) {
                setGlobalStructures(global);
                var ready=new CountDownLatch(2);var start=new CountDownLatch(1);
                var executor=Executors.newFixedThreadPool(2);
                try {
                    var tasks=new ArrayList<Future<Map<String,Object>>>();
                    for(int i=0;i<2;i++) {
                        final int index=i; var world=worlds.get(i);var row=rows.get(i);
                        tasks.add(executor.submit(()->{
                            ready.countDown();require(start.await(10,TimeUnit.SECONDS),"M6_PARALLEL_BARRIER_TIMEOUT");
                            require(!server.isOnThread(),"M6_PARALLEL_ON_SERVER");
                            var chunk=new ProtoChunk(new ChunkPos((int)row.get("chunkX"),(int)row.get("chunkZ")),
                                    UpgradeData.NO_UPGRADE_DATA,world,world.getRegistryManager().get(RegistryKeys.BIOME),null);
                            var context=new ChunkGenerationContext(world,world.getChunkManager().getChunkGenerator(),
                                    server.getStructureTemplateManager(),world.getChunkManager().getLightingProvider(),null);
                            var method=ChunkGenerating.class.getDeclaredMethod("generateStructures",ChunkGenerationContext.class,
                                    ChunkGenerationStep.class,BoundedRegionArray.class,Chunk.class);method.setAccessible(true);
                            ((CompletableFuture<?>)method.invoke(null,context,null,null,chunk)).join();
                            boolean generated=chunk.getStructureStarts().values().stream().anyMatch(value->value.hasChildren());
                            require(generated==(index==0) && world.getSeed()==SEEDS[index],"M6_PARALLEL_OWNER_LEAK");
                            return Map.<String,Object>of("worldKey",world.getRegistryKey().getValue().toString(),"seed",Long.toString(world.getSeed()),
                                    "global",global,"generated",generated,"thread",Thread.currentThread().getName(),"exact",true);
                        }));
                    }
                    require(ready.await(10,TimeUnit.SECONDS),"M6_PARALLEL_NOT_BOTH_READY");start.countDown();
                    for(var task:tasks)results.add(task.get(40,TimeUnit.SECONDS));
                } finally {start.countDown();executor.shutdown();require(executor.awaitTermination(40,TimeUnit.SECONDS),"M6_PARALLEL_NOT_STOPPED");}
            }
        } finally {setGlobalStructures(original);}
        var map=((dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess)server).quantumchamber$getWorlds();
        for(int i=0;i<2;i++) {
            var world=worlds.get(i);var key=world.getRegistryKey();
            require(map.put(key,server.getOverworld())==world,"M6_STALE_FIXTURE_MAP_CHANGED");
            try {
                require(world.getSeed()==server.getSaveProperties().getGeneratorOptions().getSeed(),"M6_STALE_SEED_OWNER_LEAK");
                require(dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.generateStructures(world,i!=0)==(i!=0),"M6_STALE_FLAG_OWNER_LEAK");
            } finally {require(map.put(key,world)==server.getOverworld(),"M6_STALE_FIXTURE_RESTORE_CHANGED");}
        }
        proof.put("staleInstanceControls",true);
        return results;
    }

    /** 真原生 worker branch；定位用可查得的原生 StructureStart 正控制，沒有替換 locator 回值。 */
    private Map<String,Object> workerAndLocator(ServerWorld world,boolean enabled,boolean owned) throws Exception {
        var generator=world.getChunkManager().getChunkGenerator();
        var calculator=world.getChunkManager().getStructurePlacementCalculator();
        var entry=world.getRegistryManager().get(RegistryKeys.STRUCTURE)
                .getEntry(RegistryKey.of(RegistryKeys.STRUCTURE,Identifier.ofVanilla("monument"))).orElseThrow();
        ChunkPos target=null;
        for(var placement:calculator.getPlacements(entry)) {
            if(!(placement instanceof RandomSpreadStructurePlacement spread))continue;
            for(int x=-8;x<=8 && target==null;x++) for(int z=-8;z<=8 && target==null;z++) {
                var candidate=spread.getStartChunk(world.getSeed(),x*spread.getSpacing(),z*spread.getSpacing());
                if(!spread.shouldGenerate(calculator,candidate.x,candidate.z))continue;
                var context=new Structure.Context(world.getRegistryManager(),generator,generator.getBiomeSource(),
                        world.getChunkManager().getNoiseConfig(),server.getStructureTemplateManager(),world.getSeed(),candidate,
                        world,entry.value().getValidBiomes()::contains);
                if(entry.value().getValidStructurePosition(context).isPresent())target=candidate;
            }
        }
        require(target!=null,"M6_MONUMENT_POSITIVE_FIXTURE_NOT_FOUND");
        var chunk=new ProtoChunk(target,UpgradeData.NO_UPGRADE_DATA,world,world.getRegistryManager().get(RegistryKeys.BIOME),null);
        var context=new ChunkGenerationContext(world,generator,server.getStructureTemplateManager(),world.getChunkManager().getLightingProvider(),null);
        var method=ChunkGenerating.class.getDeclaredMethod("generateStructures",ChunkGenerationContext.class,
                ChunkGenerationStep.class,BoundedRegionArray.class,Chunk.class);
        method.setAccessible(true);
        boolean global=server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures();
        boolean globalControl=owned ? !enabled : enabled;
        setGlobalStructures(globalControl);
        try {
            var executor=Executors.newSingleThreadExecutor(task->new Thread(task,"m6-frozen-structure-worker"));
            String thread;
            try {
                thread=executor.submit(()->{
                    require(!server.isOnThread(),"M6_WORKER_RAN_ON_SERVER");
                    ((CompletableFuture<?>)method.invoke(null,context,null,null,chunk)).join();
                    return Thread.currentThread().getName();
                }).get(40,TimeUnit.SECONDS);
            } finally {executor.shutdown();require(executor.awaitTermination(40,TimeUnit.SECONDS),"M6_WORKER_NOT_STOPPED");}
            boolean generated=chunk.getStructureStarts().values().stream().anyMatch(start->start.hasChildren());
            boolean workerExact=generated==enabled;
            require(workerExact,"M6_FROZEN_WORKER_BRANCH");
            // frozen=false 的定位負控仍需真陽性：以原生 generator 建有效 start，再交正式 locator cache。
            if(!enabled) {
                var start=entry.value().createStructureStart(world.getRegistryManager(),generator,generator.getBiomeSource(),
                        world.getChunkManager().getNoiseConfig(),server.getStructureTemplateManager(),world.getSeed(),target,0,
                        world,entry.value().getValidBiomes()::contains);
                require(start.hasChildren(),"M6_DISABLED_LOCATE_POSITIVE_START_MISSING");
                chunk.setStructureStart(entry.value(),start); world.cacheStructures(chunk);
            }
            var origin=target.getStartPos();
            var tagged=world.getRegistryManager().get(RegistryKeys.STRUCTURE).getEntryList(StructureTags.ON_OCEAN_EXPLORER_MAPS).orElseThrow();
            var direct=generator.locateStructure(world,tagged,origin,0,false);
            require(direct!=null,"M6_LOCATE_DIRECT_POSITIVE_MISSING");
            var actual=world.locateStructure(StructureTags.ON_OCEAN_EXPLORER_MAPS,origin,0,false);
            boolean locatorExact=enabled ? direct.getFirst().equals(actual) : actual==null;
            var parameters=new LootContextParameterSet(world,Map.of(LootContextParameters.ORIGIN,Vec3d.ofCenter(origin)),Map.of(),0);
            var loot=new LootContext.Builder(parameters).random(1L).build(Optional.empty());
            var map=ExplorationMapLootFunction.builder().withDestination(StructureTags.ON_OCEAN_EXPLORER_MAPS)
                    .searchRadius(0).withSkipExistingChunks(false).build().apply(new ItemStack(Items.MAP),loot);
            var row=new LinkedHashMap<String,Object>();
            row.put("chunkX",target.x);row.put("chunkZ",target.z);row.put("workerThread",thread);row.put("workerGenerated",generated);row.put("workerExact",workerExact);
            row.put("locateGlobal",globalControl);row.put("positivePosition",direct.getFirst().toShortString());
            row.put("locatorActual",actual==null?"NULL":actual.toShortString());row.put("locatorExact",locatorExact);
            row.put("mapFilled",map.isOf(Items.FILLED_MAP));row.put("mapExact",map.isOf(Items.FILLED_MAP)==enabled);
            return row;
        } finally {setGlobalStructures(global);}
    }

    /** testmod 專用外部設定變更 fixture；正式程式不得使用此反射。 */
    private void setGlobalStructures(boolean value) throws Exception {
        var options=server.getSaveProperties().getGeneratorOptions();
        var field=options.getClass().getDeclaredField("generateStructures");field.setAccessible(true);field.setBoolean(options,value);
    }

    private static List<String> samples(ServerWorld world,NoiseConfig noise) {
        var result=new ArrayList<String>();
        for(var point:POINTS) {
            var pos=new DensityFunction.UnblendedNoisePos(point[0],point[1],point[2]);
            result.add(Long.toHexString(Double.doubleToRawLongBits(noise.getNoiseRouter().finalDensity().sample(pos)))+":"
                +world.getChunkManager().getChunkGenerator().getBiomeSource().getBiome(point[0],point[1],point[2],noise.getMultiNoiseSampler()).getKey().orElseThrow().getValue());
        }
        return result;
    }
    /** Task12：正式玩家入場取得的 frozen intent；不製造或覆寫 session receipt。 */
    static final class GameplayPhases {
        private final String chain, phase;
        private final Path root, evidence;
        private final Map<String,Object> proof;
        private final ChamberFrame frame = new ChamberFrame(new BlockPos(8,70,8), Direction.NORTH);
        private final BlockPos lever = frame.controllerPos().north();
        private MinecraftServer server;
        private ServerWorld source;
        private ConnectedGameTestPlayer participant, operator;
        private UUID sid;
        private UniverseId id;
        private NewUniverseIntent intent;
        private com.google.gson.JsonObject manifest;
        private M5TestHooks.CrashPoint crash;
        private int stage, ticks;
        private boolean finished;

        GameplayPhases(String chain, String phase, Path root, Path evidence, Map<String,Object> proof) {
            this.chain=chain; this.phase=phase; this.root=root; this.evidence=evidence; this.proof=proof;
        }

        void started(MinecraftServer owner) {
            server=owner; source=owner.getOverworld();
            guarded(() -> {
                require(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()), "FORMAL_ROOT_IDENTITY");
                var preset=Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(Minecraft121SettingsLifecycle.acquire(server));
                require(preset.orElse("").equals("normal"), "FORMAL_SOURCE_NOT_NORMAL");
                proof.put("sourcePreset","normal");
                if(phase.equals("intent")) unknownProviderBeforeIntent();
                if (!phase.equals("intent")) {
                    manifest=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("formal-intent.json"))).getAsJsonObject();
                    sid=UUID.fromString(manifest.get("session").getAsString());
                    var record=record();
                    require(record!=null, "FORMAL_RESTART_RECEIPT_MISSING");
                    takeIntent(record);
                    requireStableManifest();
                    participant=new ConnectedGameTestPlayer(source,new com.mojang.authlib.GameProfile(
                            UUID.fromString(manifest.get("player").getAsString()),"m6-formal"));
                    participant.confirmTeleport();
                    participant.player().setNoGravity(true);
                }
                if (phase.equals("intent") || phase.equals("allocation")) {
                    var window=phase.equals("intent") ? M5TestHooks.CrashWindow.COLLAPSING_COMMITTED : M5TestHooks.CrashWindow.ALLOCATION_APPENDED;
                    crash=new M5TestHooks.CrashPoint(window, ignored -> sid!=null, (detail,hits) -> guarded(() -> {
                        require(hits==1,"FORMAL_CRASH_DUPLICATED");
                        if(detail instanceof M5TestHooks.CollapsingCommit commit) require(commit.next().sessionUuid().equals(sid) && commit.result().committed(),"FORMAL_INTENT_COMMIT");
                        snapshot(false);
                        require(UniverseRegistryState.get(server).flushedRecords().get(id)==null && !Files.exists(storage()),"catalogBeforeWorldStorage");
                        proof.put("worldAndStorageAbsent",true);
                        finishHalt(true);
                    }));
                    M5TestHooks.CRASH_POINT.install(crash,null);
                }
                if (phase.equals("catalog")) installCatalogCut();
            });
        }

        void tick(MinecraftServer owner) {
            if(owner!=server || finished)return;
            guarded(() -> {
                require(++ticks<1800,"FORMAL_BOUNDED_WAIT stage="+stage);
                if(phase.equals("intent")) {
                    if(stage==0) { build(); stage=1; return; }
                    if(stage==1) { arm(); return; }
                    if(stage==2) { select(); return; }
                    return;
                }
                var current=record();
                require(current!=null,"FORMAL_SESSION_LOST");
                require(current.state()!=SessionState.SEALED,"randomNewUsesFrozenSeedAcrossRestart: SEALED="+current.collapse());
                if(!phase.equals("restart"))return;
                var active=UniverseLifecycleService.resolveActive(server,id);
                var discovered=UniverseDiscoveryState.load(root.resolve("world")).records().stream().filter(row -> row.universeId().equals(id)).toList();
                if(active.isEmpty() || discovered.isEmpty())return;
                require(discovered.size()==1 && discovered.getFirst().gameplayEligible(),"FORMAL_DISCOVERY_NOT_UNIQUE");
                snapshot(true);
                require(active.get().getSeed()==intent.rootWorldSeed(),"randomNewUsesFrozenSeedAcrossRestart");
                if(chain.equals("partial-overworld")) {
                    var partial=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("partial-storage.json"))).getAsJsonObject();
                    require(partial.get("uuid").getAsString().equals(id.value().toString()) && partial.get("rootWorldSeed").getAsString().equals(Long.toString(intent.rootWorldSeed())),"PARTIAL_RESTART_IDENTITY");
                    var actual=terrainSamples(active.get(),true);
                    require(new Gson().toJsonTree(actual).equals(partial.get("terrainSamples")),"PARTIAL_RESTART_TERRAIN");
                    proof.put("partialTerrainExact",true);proof.put("terrainSamples",actual);
                }
                proof.put("active",true);proof.put("discoveryCount",discovered.size());proof.put("nativeSeed",Long.toString(active.get().getSeed()));
                proof.put("functionalPassed",true);proof.put("stopRequested",true);finished=true;
                server.stop(false);
            });
        }

        private void build() {
            source.getChunk(0,0);
            M1FoundationSessionFixture.set(source,frame.controllerPos(),false);
            ChamberProtectionService.get().authorizedMutation(() -> {
                for(int x=0;x<7;x++)for(int y=0;y<7;y++)for(int z=0;z<7;z++) {
                    boolean shell=x==0||x==6||y==0||y==6||z==0||z==6;
                    var state=(shell?net.minecraft.block.Blocks.BEDROCK:net.minecraft.block.Blocks.AIR).getDefaultState();
                    if(z==0&&x>0&&x<6&&y>0&&y<6)state=ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                    source.setBlockState(ChamberGeometry.localToWorld(frame,x,y,z),state,3);
                }
                source.setBlockState(frame.controllerPos(),ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING,Direction.NORTH),3);
            });
            source.setBlockState(lever,net.minecraft.block.Blocks.LEVER.getDefaultState()
                    .with(net.minecraft.block.LeverBlock.FACE,net.minecraft.block.enums.BlockFace.WALL)
                    .with(net.minecraft.block.LeverBlock.FACING,Direction.NORTH),3);
            participant=new ConnectedGameTestPlayer(source);participant.confirmTeleport();participant.player().setNoGravity(true);
            participant.player().refreshPositionAndAngles(CorridorGeometry.position(frame,2.5,1,2.5),0,0);
            participant.player().addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(ModEffects.QUANTUM_STATE,1000000));
            operator=new ConnectedGameTestPlayer(source);operator.confirmTeleport();operator.player().setNoGravity(true);
            operator.player().refreshPositionAndAngles(CorridorGeometry.position(frame,3.5,6,-1.5),0,0);
        }

        private void arm() throws Exception {
            var controller=(ChamberControllerBlockEntity)source.getBlockEntity(frame.controllerPos());
            if(controller==null)return;
            boolean pending=(boolean)field(controller,"loadSyncPending");
            proof.put("controllerLoadSyncPending",pending);proof.put("controllerPowerInitialized",controller.powerInitialized());
            if(pending || !controller.powerInitialized())return;
            var readiness=new ChamberActivationService().evaluateReadiness(source,controller);
            proof.put("armReadiness",readiness.toString());
            if(!readiness.accepted())return;
            PlayerCheckpointStore.saveAndVerify(server,participant.player(),Optional.empty());
            require(server.save(false,true,true),"FORMAL_SOURCE_SAVE");
            var action=source.getBlockState(lever).onUse(source,operator.player(),new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(lever),Direction.NORTH,lever,false));
            require(action.isAccepted(),"FORMAL_LEVER_USE");
            var initial=SessionRecoveryState.get(server).flushedRecords().values().stream().filter(row -> row.chamberUuid().equals(controller.chamberUuid())).findFirst()
                    .orElseThrow(() -> new IllegalStateException("FORMAL_ARMING_MISSING powered="+source.isReceivingRedstonePower(frame.controllerPos())
                            +" uuid="+controller.chamberUuid()+" state="+controller.chamberState()+" readiness="+readiness));
            sid=initial.sessionUuid();
            require(initial.state()==SessionState.ARMING && initial.candidateContext().orElseThrow().isM6(),"FORMAL_ARMING_POLICY_ONE");
            proof.put("formalLeverEntry",true);proof.put("armingPolicy",1);proof.put("familyCapability",0);stage=2;
        }

        private void select() throws Exception {
            var current=record();
            if(current==null || current.state()!=SessionState.SUPERPOSITION)return;
            var pages=CorridorPageManager.forServer(server);
            var entry=current.candidateLedger().stream().filter(row -> row.candidate() instanceof QuantumCandidate.New && pages.selectableDoors(sid).contains(row.doorKey())).findFirst();
            if(entry.isEmpty())return;
            var key=entry.get().doorKey();
            BlockPos cell=null;
            for(var view:pages.currentMappings(sid).instances()) {
                var mapped=new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),3).up(6),view.outwardFacing());
                var possible=CorridorGeometry.block(mapped,key.wallSide()==DoorKey.DoorWallSide.NEGATIVE_LATERAL?0:6,1,
                        Math.toIntExact(key.logicalStationIndex()*8+1-view.logicalAnchorBlock()));
                if(view.bounds().contains(possible)) {cell=possible;break;}
            }
            require(cell!=null,"FORMAL_NEW_DOOR_UNMAPPED");
            var corridor=server.getWorld(SuperpositionWorld.KEY);
            require(corridor.getBlockState(cell).onUse(corridor,participant.player(),new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(cell),Direction.UP,cell,false)).isAccepted(),"FORMAL_NEW_DOOR_USE");
            var selected=record();
            require(selected.state()==SessionState.MEASURED && selected.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected,"FORMAL_CHECKED_SELECTED");
            takeIntent(selected);
            proof.put("formalBatch",true);proof.put("formalSelected",true);proof.put("candidateCount",selected.candidateLedger().size());
            proof.put("door",key.toString());proof.put("player",participant.player().getUuid().toString());
            require(server.save(false,true,true),"FORMAL_SELECTED_SAVE");
            unknownProviderAfterIntent(selected);
            stableFields();
            durable(root.resolve("formal-intent.json"),proof);
            stage=3;
        }

        private SessionRecoveryRecord record() { return sid==null?null:SessionRecoveryState.get(server).flushedRecords().get(sid); }
        private void takeIntent(SessionRecoveryRecord row) {
            require(row.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New,"FORMAL_NOT_NEW");
            intent=((QuantumCandidate.New)row.selectedCandidate().orElseThrow()).intent();
            require(intent.isM6() && intent.policyVersion()==1 && intent.familyCapabilityVersion()==0,"FORMAL_FROZEN_POLICY");
            require(intent.sourceRootWorldSeed()==server.getSaveProperties().getGeneratorOptions().getSeed()
                    && intent.sourceSettingsSnapshot().parameters().getString("OverworldPreset").equals("normal")
                    && intent.targetSettingsSnapshot().parameters().getString("OverworldPreset").equals("normal"),"FORMAL_SOURCE_SNAPSHOT");
            id=UniverseAllocationLedger.deriveUniverseId(intent.allocationToken());
        }
        private Path storage() {return root.resolve("world/dimensions/quantumchamber/universe/"+id.value()+"/overworld");}

        /** 只包裝既有 typed catalog port；delegate完成正式checked flush後同步核原件，不改內容。 */
        private void installCatalogCut() throws Exception {
            var authorities=CollapseAuthorities.get(server);
            var slot=CollapseAuthorities.class.getDeclaredField("catalog");slot.setAccessible(true);
            var delegate=slot.get(authorities);
            var wrapper=java.lang.reflect.Proxy.newProxyInstance(slot.getType().getClassLoader(),new Class<?>[]{slot.getType()},(proxy,method,args)->{
                method.setAccessible(true);
                Object result;
                try {result=method.invoke(delegate,args);} catch(java.lang.reflect.InvocationTargetException failure){throw failure.getCause();}
                if(method.getName().equals("flush") && UniverseRegistryState.get(server).flushedRecords().containsKey(id)) {
                    guarded(() -> {
                        snapshot(false);checkedCatalog(DesiredAvailability.DISABLED);
                        require(!Files.exists(storage()) && server.getWorld(UniverseKeys.world(id,DimensionRole.OVERWORLD))==null,"catalogBeforeWorldStorage");
                        require(noDiscovery(),"CATALOG_EARLY_DISCOVERY");
                        proof.put("worldAndStorageAbsent",true);proof.put("discoveryCount",0);
                        finishHalt(true);
                    });
                }
                return result;
            });
            slot.set(authorities,wrapper);
        }

        /** 只能在正式LOAD、map已發布但runtime.activate尚未執行的同步窗口停下。 */
        void loaded(MinecraftServer owner,ServerWorld world) {
            if(!phase.equals("partial") || owner!=server || id==null || !world.getRegistryKey().equals(UniverseKeys.world(id,DimensionRole.OVERWORLD)))return;
            guarded(() -> {
                long started=System.nanoTime();
                require(server.getWorld(world.getRegistryKey())==world,"PARTIAL_EXACT_LIVE_WORLD");
                snapshot(false);var row=checkedCatalog(DesiredAvailability.EAGER_ENABLED);
                require(UniverseLifecycleService.resolveActive(server,id).isEmpty() && noDiscovery(),"PARTIAL_ALREADY_ACTIVE_OR_DISCOVERED");
                var contexts=UniverseLifecycleService.class.getDeclaredField("CONTEXTS");contexts.setAccessible(true);
                var context=((Map<?,?>)contexts.get(null)).get(server);
                var backend=field(context,"backend");
                @SuppressWarnings("unchecked") var runtime=(UniverseRuntimeRegistry<MinecraftServer,ServerWorld>)field(backend,"runtime");
                require(runtime.state(server,row.definition().worlds().get(DimensionRole.OVERWORLD))==DynamicWorldRuntimeState.MATERIALIZING,"PARTIAL_NOT_MATERIALIZING");
                require(world.getSeed()==intent.rootWorldSeed(),"PARTIAL_OWNER_SEED");
                proof.put("loadWindow",true);proof.put("runtimeState","MATERIALIZING");proof.put("active",false);proof.put("discoveryCount",0);
                LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_PARTIAL_SINGLE_CHUNK_BEGIN");
                world.getChunk(0,0);
                require(System.nanoTime()-started<60_000_000_000L,"PARTIAL_CHUNK_DEADLINE");
                world.save(null,true,false);
                require(System.nanoTime()-started<60_000_000_000L,"PARTIAL_SAVE_DEADLINE");
                var terrain=terrainSamples(world,true);
                require(terrain.stream().anyMatch(sample -> !sample.endsWith("minecraft:air")),"PARTIAL_TERRAIN_EMPTY");
                var hashes=new TreeMap<String,String>();
                try(var files=Files.walk(storage())) {
                    for(var path:files.filter(Files::isRegularFile).toList()) {
                        require(!Files.isSymbolicLink(path),"PARTIAL_STORAGE_REPARSE");
                        hashes.put(storage().relativize(path).toString().replace('\\','/'),digest(path));
                    }
                }
                require(hashes.containsKey("region/r.0.0.mca"),"PARTIAL_STORAGE_MISSING");
                require(runtime.state(server,row.definition().worlds().get(DimensionRole.OVERWORLD))==DynamicWorldRuntimeState.MATERIALIZING
                        && UniverseLifecycleService.resolveActive(server,id).isEmpty() && noDiscovery(),"PARTIAL_WINDOW_CHANGED");
                proof.put("partialStorageVerified",true);proof.put("chunkRequests",1);proof.put("terrainSamples",terrain);
                proof.put("partialHashes",hashes);proof.put("partialElapsedMs",(System.nanoTime()-started)/1_000_000L);
                durable(root.resolve("partial-storage.json"),proof);
                finishHalt(true);
            });
            throw new IllegalStateException("PARTIAL_LOAD_RETURNED");
        }

        private List<String> terrainSamples(ServerWorld world,boolean compareDisk) throws Exception {
            var samples=new ArrayList<String>();
            var details=new ArrayList<Map<String,Object>>();
            if(phase.equals("partial")) {
                var region=storage().resolve("region/r.0.0.mca");
                try {proof.put("terrainSampleRegionSha256Before",M5CollapseDiskEvidence.sha256(region));
                    proof.put("terrainSampleRegionLength",Files.size(region));}
                catch(Exception failure) {proof.put("terrainSampleDiagnosticError",diagnosticError("partialRegionBefore",failure));}
            }
            for(int x:new int[]{0,5,10,15})for(int z:new int[]{0,5,10,15})for(int y:new int[]{-32,0,32}) {
                var pos=new BlockPos(x,y,z);
                var live=world.getBlockState(pos);
                var nativeName=net.minecraft.registry.Registries.BLOCK.getId(live.getBlock()).toString();
                var disk=M5CollapseDiskEvidence.block(storage(),pos);
                boolean terrainMatches=!disk.name().equals(M5CollapseDiskEvidence.ABSENT)
                        && (!compareDisk || disk.name().equals(nativeName));
                if (phase.equals("partial")) {
                    var detail=new LinkedHashMap<String,Object>();
                    detail.put("pos",pos.toShortString());detail.put("liveState",live.toString());
                    detail.put("rawState",disk.name());detail.put("rawProperties",disk.properties());
                    try {detail.put("liveBiome",world.getBiome(pos).getKey().map(key->key.getValue().toString()).orElse("UNKEYED"));
                        detail.put("rawBiome",rawBiome(pos));}
                    catch(Exception failure) {detail.put("diagnosticError",diagnosticError("partialBiome",failure));}
                    details.add(detail);
                }
                if (!terrainMatches && !proof.containsKey("firstTerrainDifference")) {
                    try {firstTerrainDifference(world,pos,live,disk,nativeName,disk.name());}
                    catch(Exception failure) {proof.put("diagnosticError",diagnosticError("firstTerrainDifference",failure));}
                }
                require(terrainMatches,"PARTIAL_RAW_MCA_TERRAIN");
                samples.add(x+","+y+","+z+"="+disk.name());
            }
            if (phase.equals("partial")) {
                proof.put("partialTerrainDiagnostics",details);
                try {proof.put("terrainSampleRegionSha256After",M5CollapseDiskEvidence.sha256(storage().resolve("region/r.0.0.mca")));}
                catch(Exception failure) {proof.put("terrainSampleDiagnosticError",diagnosticError("partialRegionAfter",failure));}
            }
            return List.copyOf(samples);
        }

        private void firstTerrainDifference(ServerWorld world,BlockPos pos,net.minecraft.block.BlockState live,
                M5CollapseDiskEvidence.DiskBlock disk,String liveName,String comparedRaw) {
            var detail=new LinkedHashMap<String,Object>();
            proof.put("firstTerrainDifference",detail);
            detail.put("chain",chain);detail.put("phase",phase);detail.put("pos",pos.toShortString());
            detail.put("session",sid.toString());detail.put("uuid",id.value().toString());
            detail.put("worldKey",world.getRegistryKey().getValue().toString());
            detail.put("worldOwnerExact",UniverseLifecycleService.resolveActive(server,id).orElse(null)==world);
            detail.put("liveSeed",Long.toString(world.getSeed()));detail.put("frozenSeed",Long.toString(intent.rootWorldSeed()));
            detail.put("sourceSeed",Long.toString(source.getSeed()));detail.put("serverTick",server.getTicks());
            try {detail.put("storage",storage().toRealPath().toString());}
            catch(Exception failure) {detail.put("storage",storage().toAbsolutePath().normalize().toString());
                detail.put("storageCanonicalError",diagnosticError("storageCanonical",failure));}
            detail.put("liveBlock",liveName);detail.put("liveState",live.toString());
            detail.put("rawBlock",disk.name());detail.put("rawProperties",disk.properties());
            detail.put("rawBlockEntity",disk.blockEntity());detail.put("comparedRaw",comparedRaw);
            detail.put("rawAbsent",disk.name().equals(M5CollapseDiskEvidence.ABSENT));
            detail.put("blockNameMismatch",!disk.name().equals(liveName));
            detail.put("oracleDisagreement",disk.name().equals(liveName) && !disk.name().equals(M5CollapseDiskEvidence.ABSENT));
            var loaded=world.getChunkManager().getWorldChunk(pos.getX()>>4,pos.getZ()>>4);
            detail.put("chunkLoaded",loaded!=null);detail.put("liveChunkStatus",loaded==null?"NOT_LOADED":loaded.getStatus().toString());
            try {
                var rawChunk=rawChunk(pos);
                detail.put("rawChunkStatus",rawChunk.map(nbt->nbt.getString("Status")).orElse("ABSENT_CHUNK"));
                var region=storage().resolve("region/r.0.0.mca");
                try(var input=new java.io.RandomAccessFile(region.toFile(),"r")) {
                    input.seek(4L*((pos.getX()>>4&31)+(pos.getZ()>>4&31)*32));
                    detail.put("rawChunkHeaderLocation",input.readInt());
                }
            } catch(Exception failure) {detail.put("rawChunkReadFailure",diagnosticError("rawChunk",failure));}
            try {detail.put("liveBiome",world.getBiome(pos).getKey().map(key->key.getValue().toString()).orElse("UNKEYED"));
                detail.put("rawBiome",rawBiome(pos));}
            catch(Exception failure) {detail.put("rawBiomeReadFailure",diagnosticError("biome",failure));}
            try {
                var region=storage().resolve("region/r.0.0.mca");
                var before=M5CollapseDiskEvidence.sha256(region);
                detail.put("regionLength",Files.size(region));detail.put("regionSha256Before",before);
                var partialPath=root.resolve("partial-storage.json");
                var partial=com.google.gson.JsonParser.parseString(Files.readString(partialPath)).getAsJsonObject();
                detail.put("partialMcaSha256",partial.getAsJsonObject("partialHashes").get("region/r.0.0.mca").getAsString());
                detail.put("mcaHashMatchesPartial",before.equalsIgnoreCase((String)detail.get("partialMcaSha256")));
                var manifest=com.google.gson.JsonParser.parseString(Files.readString(root.resolve("partial-before-restart.json"))).getAsJsonObject();
                detail.put("partialManifestSha256",M5CollapseDiskEvidence.sha256(partialPath));
                detail.put("manifestHashMatchesPartial",((String)detail.get("partialManifestSha256"))
                        .equalsIgnoreCase(manifest.get("partialManifestSha256").getAsString()));
                var after=M5CollapseDiskEvidence.sha256(region);
                detail.put("regionSha256After",after);detail.put("hashStableDuringRead",before.equals(after));
            } catch(Exception failure) {detail.put("diagnosticError",diagnosticError("hashManifest",failure));}
        }

        private static String diagnosticError(String stage,Exception failure) {
            var message=String.valueOf(failure.getMessage());
            return stage+":"+failure.getClass().getSimpleName()+":"+message.substring(0,Math.min(message.length(),160));
        }

        /** 沿用既有 raw MCA parser 的檔案、header、壓縮與 NBT 限制，只解單格 biome palette。 */
        @SuppressWarnings("unchecked") private Optional<NbtCompound> rawChunk(BlockPos pos) throws Exception {
            var method=M5CollapseDiskEvidence.class.getDeclaredMethod("chunk",Path.class,ChunkPos.class);
            method.setAccessible(true);
            return (Optional<NbtCompound>)method.invoke(null,storage().resolve("region"),new ChunkPos(pos));
        }

        private String rawBiome(BlockPos pos) throws Exception {
            var chunk=rawChunk(pos);
            if (chunk.isEmpty()) return "ABSENT_CHUNK";
            var nbt=chunk.get();
            for (var raw:nbt.getList("sections",net.minecraft.nbt.NbtElement.COMPOUND_TYPE)) {
                var section=(NbtCompound)raw;
                if(section.getByte("Y")!=(pos.getY()>>4))continue;
                if(!section.contains("biomes",net.minecraft.nbt.NbtElement.COMPOUND_TYPE))return "ABSENT_BIOMES";
                var biomes=section.getCompound("biomes");
                var palette=biomes.getList("palette",net.minecraft.nbt.NbtElement.STRING_TYPE);
                if(palette.isEmpty())throw new java.io.IOException("biome palette empty");
                int index=0;
                if(palette.size()>1) {
                    int bits=Math.max(1,32-Integer.numberOfLeadingZeros(palette.size()-1));
                    int perLong=64/bits;
                    int cell=((pos.getY()&15)>>2)*16+((pos.getZ()&15)>>2)*4+((pos.getX()&15)>>2);
                    var data=biomes.getLongArray("data");
                    if(cell/perLong>=data.length)throw new java.io.IOException("biome data short");
                    index=(int)((data[cell/perLong]>>>((cell%perLong)*bits))&((1L<<bits)-1));
                }
                if(index>=palette.size())throw new java.io.IOException("biome palette index");
                return palette.getString(index);
            }
            return "ABSENT_SECTION";
        }
        private boolean noDiscovery() throws Exception {return UniverseDiscoveryState.load(root.resolve("world")).records().stream().noneMatch(row->row.universeId().equals(id));}
        private UniverseRecord checkedCatalog(DesiredAvailability availability) throws Exception {
            var catalog=UniverseRegistryState.get(server);var row=catalog.flushedRecords().get(id);
            require(row!=null && row.equals(catalog.records().get(id)) && row.desiredAvailability()==availability
                    && CollapseAuthorities.catalogRecordMatches(intent,row,CollapseAuthorities.AvailabilityPolicy.MATERIALIZE_RETRY),"catalogBeforeWorldStorage");
            var disk=UniverseRegistryState.fromNbt(UniverseCatalogStore.read(root.resolve("world/data/"+UniverseRegistryState.STATE_ID+".dat")).getCompound("data"));
            disk.requireHealthy();require(row.equals(disk.flushedRecords().get(id)),"FORMAL_CATALOG_STRICT");
            proof.put("catalogChecked",true);proof.put("catalogAvailability",availability.name());return row;
        }
        private static String digest(Path path) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
        private void stableFields() {
            proof.put("session",sid.toString());proof.put("uuid",id.value().toString());proof.put("worldKey",UniverseKeys.world(id,DimensionRole.OVERWORLD).getValue().toString());
            proof.put("rootWorldSeed",Long.toString(intent.rootWorldSeed()));proof.put("sourceRootWorldSeed",Long.toString(intent.sourceRootWorldSeed()));
            proof.put("allocationToken",HexFormat.of().formatHex(intent.allocationToken().bytes()));
            proof.put("targetFingerprint",HexFormat.of().formatHex(intent.targetSettingsSnapshot().outputFingerprint()));
            proof.put("sourceFingerprint",HexFormat.of().formatHex(intent.sourceSettingsSnapshot().outputFingerprint()));
            proof.put("profileId",intent.profileId());proof.put("storage",storage().toString());
        }
        private void requireStableManifest() {
            stableFields();
            for(var key:List.of("session","uuid","worldKey","rootWorldSeed","sourceRootWorldSeed","allocationToken","targetFingerprint","sourceFingerprint","profileId","storage"))
                require(proof.get(key).toString().equals(manifest.get(key).getAsString()),"FORMAL_RESTART_IDENTITY:"+key);
            require(manifest.get("formalLeverEntry").getAsBoolean() && manifest.get("formalBatch").getAsBoolean() && manifest.get("formalSelected").getAsBoolean(),"FORMAL_ENTRY_MANIFEST");
            proof.put("formalLeverEntry",true);proof.put("formalBatch",true);proof.put("formalSelected",true);
            for(var key:List.of("providerACreateRejected","providerAResolveRejected","providerBLoadRejected","providerBPreflightRejected","providerAuthorityUnchanged","providerRegistrationRestored")) {
                require(manifest.get(key).getAsBoolean(),"PROVIDER_BASELINE_MANIFEST:"+key);proof.put(key,true);
            }
        }

        /** Task9原本正確的拒絕是baseline正控制；不假稱新production修復。 */
        private void unknownProviderBeforeIntent() throws Exception {
            var before=authorityHashes();var journal=SessionRecoveryState.get(server);
            require(journal.flushedRecords().isEmpty(),"PROVIDER_A_NOT_FIRST_INTENT");
            var lease=Minecraft121SettingsLifecycle.acquire(server);var registry=Minecraft121SettingsLifecycle.registry();
            var parameters=new NbtCompound();parameters.putString("OverworldPreset","normal");parameters.putBoolean("GenerateStructures",lease.sourceGenerateStructures());
            withMissingProvider(() -> {
                boolean rejected=false;
                try {registry.createSnapshot("quantumchamber:vanilla_normal_v1",1,parameters,lease);}
                catch(IllegalStateException failure){rejected=failure.getMessage().contains("PROFILE_CLOSURE_UNBOUND");}
                require(rejected,"unknownProviderFailsClosedWithoutReplacement:A_CREATE");proof.put("providerACreateRejected",true);
            });
            var snapshot=registry.createSnapshot("quantumchamber:vanilla_normal_v1",1,parameters,lease);
            withMissingProvider(() -> {
                boolean rejected=false;
                try {registry.resolve(snapshot,lease);}catch(IllegalStateException failure){rejected=failure.getMessage().contains("PROFILE_CLOSURE_UNBOUND");}
                require(rejected,"unknownProviderFailsClosedWithoutReplacement:A_RESOLVE");proof.put("providerAResolveRejected",true);
            });
            registry.resolve(snapshot,lease);
            require(before.equals(authorityHashes()) && journal.flushedRecords().isEmpty(),"PROVIDER_A_CHANGED_AUTHORITY");
            proof.put("providerABefore",before);proof.put("providerAAfter",authorityHashes());
        }

        private void unknownProviderAfterIntent(SessionRecoveryRecord selected) throws Exception {
            var journal=SessionRecoveryState.get(server);journal.requireHealthy();
            var path=root.resolve("world/data/"+SessionRecoveryState.STATE_ID+".dat");
            var before=authorityHashes();var beforeBytes=Files.readAllBytes(path);
            Files.createDirectories(evidence);
            Files.write(evidence.resolve("provider-before-journal.dat"),beforeBytes,StandardOpenOption.CREATE_NEW);
            proof.put("providerBeforeHashes",before);
            var validatorField=SessionRecoveryState.class.getDeclaredField("intentValidator");validatorField.setAccessible(true);
            var validator=validatorField.get(journal);
            var load=SessionRecoveryState.class.getDeclaredMethod("load",Path.class,validatorField.getType());load.setAccessible(true);
            withMissingProvider(() -> {
                var newlyLoaded=(SessionRecoveryState)load.invoke(null,path,validator);
                boolean loadRejected=false;
                try {newlyLoaded.requireHealthy();}catch(IllegalStateException failure){loadRejected=failure.getMessage().contains("PROFILE_CLOSURE_UNBOUND");}
                require(newlyLoaded!=journal && loadRejected,"unknownProviderFailsClosedWithoutReplacement:B_LOAD");
                require(Arrays.equals(beforeBytes,Files.readAllBytes(path)) && before.equals(authorityHashes()),"PROVIDER_B1_CHANGED_AUTHORITY");
                proof.put("providerB1AfterHashes",authorityHashes());
                proof.put("providerBLoadRejected",true);
                journal.requireHealthy();
                // 只要求正式state保存既有原件；不put、變更或製造record，不替換validator。
                journal.markDirty();boolean preflightRejected=false;
                try {journal.flush(server);}catch(RuntimeException failure){preflightRejected=FailureText.describe(failure).contains("PROFILE_CLOSURE_UNBOUND");}
                require(preflightRejected,"unknownProviderFailsClosedWithoutReplacement:B_PREFLIGHT");proof.put("providerBPreflightRejected",true);
                require(Arrays.equals(beforeBytes,Files.readAllBytes(path)) && before.equals(authorityHashes())
                        && selected.equals(journal.flushedRecords().get(sid)) && selected.equals(journal.records().get(sid)),"PROVIDER_B_CHANGED_AUTHORITY");
                proof.put("providerB2AfterHashes",authorityHashes());
            });
            require(journal==SessionRecoveryState.get(server),"PROVIDER_REPLACED_INSTALLED_STATE");
            journal.flush(server);
            var restored=(SessionRecoveryState)load.invoke(null,path,validator);restored.requireHealthy();
            require(Arrays.equals(beforeBytes,Files.readAllBytes(path)) && before.equals(authorityHashes())
                    && selected.equals(restored.flushedRecords().get(sid)),"PROVIDER_RESTORE_NOT_EXACT");
            proof.put("providerAuthorityUnchanged",true);proof.put("providerJournalSha256",digest(path));
        }

        /** 隔離且同thread持registry monitor；finally恢復同一映射與exact registration身分。 */
        private void withMissingProvider(CheckedAction action) throws Exception {
            var registry=Minecraft121SettingsLifecycle.registry();
            synchronized(registry) {
                var provider=registry.find("quantumchamber:vanilla_normal_v1",1).orElseThrow();
                @SuppressWarnings("unchecked") var registrations=(Map<Object,Object>)field(registry,"registrations");
                Object key=null;
                for(var entry:registrations.entrySet())if(field(entry.getValue(),"provider")==provider) {require(key==null,"PROVIDER_KEY_NOT_UNIQUE");key=entry.getKey();}
                require(key!=null,"PROVIDER_KEY_MISSING");
                var registration=registrations.remove(key);
                try {action.run();} finally {
                    require(field(registry,"registrations")==registrations,"PROVIDER_MAP_REPLACED");
                    registrations.put(key,registration);
                    require(registrations.get(key)==registration && registry.find("quantumchamber:vanilla_normal_v1",1).orElseThrow()==provider,"PROVIDER_REGISTRATION_NOT_RESTORED");
                    proof.put("providerRegistrationRestored",true);
                }
            }
        }

        private Map<String,String> authorityHashes() throws Exception {
            var result=new TreeMap<String,String>();
            for(var name:List.of(SessionRecoveryState.STATE_ID+".dat",UniverseAllocationLedger.FILE_NAME,UniverseRegistryState.STATE_ID+".dat",UniverseDiscoveryState.FILE_NAME)) {
                var path=root.resolve("world/data/"+name);result.put(name,Files.exists(path)?digest(path):"ABSENT");
            }
            var universeRoot=root.resolve("world/dimensions/quantumchamber/universe");
            if(Files.exists(universeRoot)) {
                try(var paths=Files.walk(universeRoot)){for(var path:paths.toList())result.put("storage/"+universeRoot.relativize(path),Files.isRegularFile(path)?digest(path):"DIRECTORY");}
            }
            return result;
        }
        private void snapshot(boolean active) throws Exception {
            var current=record();takeIntent(current);stableFields();
            var journal=SessionRecoveryState.get(server);
            var validatorField=SessionRecoveryState.class.getDeclaredField("intentValidator");validatorField.setAccessible(true);
            var decode=SessionRecoveryState.class.getDeclaredMethod("fromNbt",NbtCompound.class,validatorField.getType());decode.setAccessible(true);
            var wrapped=SessionJournalStore.read(root.resolve("world/data/"+SessionRecoveryState.STATE_ID+".dat"));
            var diskState=(SessionRecoveryState)decode.invoke(null,wrapped.getCompound("data"),validatorField.get(journal));
            diskState.requireHealthy();
            var disk=diskState.flushedRecords().get(sid);
            require(current.equals(disk) && current.equals(journal.records().get(sid)),"FORMAL_JOURNAL_CHECKED_READBACK");proof.put("journalChecked",true);
            var allocationPath=root.resolve("world/data/"+UniverseAllocationLedger.FILE_NAME);
            var allocation=dev.quantumchamber.persistence.CheckedNbtFile.parse(dev.quantumchamber.persistence.CheckedNbtFile.readFile(allocationPath));
            var rows=allocation.getList("Entries",net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
            proof.put("allocationCount",rows.size());proof.put("allocationChecked",!phase.equals("intent"));
            if(!phase.equals("intent")) {
                require(rows.size()==1,"FORMAL_ALLOCATION_UNIQUE");
                var row=rows.getCompound(0);
                require(row.getUuid("UniverseId").equals(id.value()) && row.getLong("RootWorldSeed")==intent.rootWorldSeed(),"FORMAL_ALLOCATION_IDENTITY");
            } else require(rows.isEmpty(),"FORMAL_ALLOCATION_BEFORE_INTENT");
            if(active) {
                var catalog=UniverseRegistryState.get(server);var row=catalog.flushedRecords().get(id);
                require(row!=null && row.equals(catalog.records().get(id)) && row.desiredAvailability()==DesiredAvailability.EAGER_ENABLED
                        && CollapseAuthorities.catalogRecordMatches(intent,row,CollapseAuthorities.AvailabilityPolicy.EAGER_ONLY),"catalogBeforeWorldStorage");
                var diskCatalog=UniverseCatalogStore.read(root.resolve("world/data/"+UniverseRegistryState.STATE_ID+".dat")).getCompound("data").getList("Records",net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
                require(diskCatalog.size()==1 && UniverseRecord.fromNbt(diskCatalog.getCompound(0)).equals(row),"FORMAL_CATALOG_STRICT");
                require(Files.isDirectory(storage()),"FORMAL_STORAGE_MISSING");proof.put("catalogChecked",true);proof.put("catalogAvailability","EAGER_ENABLED");
            }
        }
        void stopping(MinecraftServer owner) {if(owner==server)proof.put("stoppingSeen",true);}
        void stopped(MinecraftServer owner) {
            if(owner!=server)return;
            proof.put("stoppedSeen",true);proof.put("status",Boolean.TRUE.equals(proof.get("functionalPassed"))?"PASS":"FAIL");
            try {durable(evidence.resolve("final.json"),proof);} catch(Exception failure){throw new IllegalStateException(failure);}
            logEnd();
        }
        private void finishHalt(boolean passed) throws Exception {
            finished=true;proof.put("status",passed?"PASS":"FAIL");proof.put("functionalPassed",passed);proof.put("ending","HALT");
            durable(evidence.resolve("halt.json"),proof);logEnd();Runtime.getRuntime().halt(passed?77:78);
        }
        @SuppressWarnings("unchecked") private void guarded(CheckedAction action) {
            try {action.run();} catch(Throwable failure) {
                ((List<String>)proof.get("failures")).add(failure.toString());
                LoggerFactory.getLogger("quantumchamber-m6-native").error("M6_FORMAL_GAMEPLAY_FAILED",failure);
                try {finishHalt(false);}catch(Exception receiptFailure){throw new IllegalStateException(receiptFailure);}
            }
        }
        private void logEnd() {LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_NATIVE_END chain={} phase={} mode=FUNCTIONAL status={}",chain,phase,proof.get("status"));}
        private static void durable(Path path,Object value) throws Exception {
            Files.createDirectories(path.getParent());
            var bytes=new Gson().toJson(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var channel=java.nio.channels.FileChannel.open(path,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)) {
                var buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
        }
        @FunctionalInterface private interface CheckedAction {void run() throws Exception;}
    }

    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try {var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
            catch(NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
