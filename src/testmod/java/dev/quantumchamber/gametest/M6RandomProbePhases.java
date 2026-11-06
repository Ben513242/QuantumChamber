package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.candidate.NewUniverseIntent;
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

/** 真 checked fixture 的兩個 JVM；不接正式 candidate／玩家入口。 */
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
    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass()) {
            try {var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
            catch(NoSuchFieldException absent) { }
        }
        throw new NoSuchFieldException(name);
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
