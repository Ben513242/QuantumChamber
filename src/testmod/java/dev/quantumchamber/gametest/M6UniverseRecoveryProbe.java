package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quantumchamber.universe.*;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.random.RandomSequencesState;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Schema1現況診斷；PASS只表示舊行為被可靠辨識，獨立seed功能仍是RED。 */
public final class M6UniverseRecoveryProbe implements ModInitializer {
    private static final Gson JSON = new Gson();
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m6-native");
    private static final Identifier AFTER = Identifier.of("quantumchamber:m6_native_after");
    private static final List<UUID> IDS = List.of(UUID.fromString("67000000-0000-4000-8000-000000000001"),
            UUID.fromString("67000000-0000-4000-8000-000000000002"));
    private static final int[][] POINTS = {{0,64,0},{1032,80,-2056},{-9012,24,7196},{32768,112,16384}};
    private final Map<String,Object> proof = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private Configuration config;
    private MinecraftServer server;
    private boolean sampled;

    @Override public void onInitialize() {
        config = Configuration.read();
        if (config == null) return;
        proof.put("producer", "M6UniverseRecoveryProbe"); proof.put("m6ProbeSchema", 1);
        proof.put("expectedMode", config.expectedMode()); proof.put("chain", config.chain());
        proof.put("phase", config.phase()); proof.put("nonce", config.nonce());
        proof.put("startupNonce", config.startupNonce()); proof.put("root", config.root().toString());
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("runtimeScope", "testmod-present");
        proof.put("loadedModIds", FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId()).sorted().toList());
        proof.put("failures", failures);
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER, owner -> {server=owner;});
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerTickEvents.END_SERVER_TICK.register(AFTER, owner -> {
            if(owner != server || sampled) return;
            sampled=true;
            try { diagnose(); } catch(Exception failure) {failures.add(failure.toString());LOGGER.error("M6_NATIVE_DIAGNOSTIC_FAILED",failure);}
            proof.put("stopRequested",true); owner.stop(false);
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(owner -> {if(owner==server)proof.put("stoppingSeen",true);});
        ServerLifecycleEvents.SERVER_STOPPED.register(owner -> {
            if(owner!=server)return;
            proof.put("stoppedSeen",true);
            proof.put("status", failures.isEmpty() && sampled && Boolean.TRUE.equals(proof.get("diagnosticPassed")) ? "PASS" : "FAIL");
            try {writeNew(config.evidence().resolve("final.json"),proof);} catch(Exception failure){throw new IllegalStateException("M6_RECEIPT_WRITE_FAILED",failure);}
            LOGGER.info("M6_NATIVE_END chain={} phase={} mode=DIAGNOSTIC status={} receipt={}", config.chain(),config.phase(),proof.get("status"),config.evidence());
        });
        LOGGER.info("M6_NATIVE_BEGIN chain={} phase={} mode=DIAGNOSTIC startupNonce={}",config.chain(),config.phase(),config.startupNonce());
    }

    private void diagnose() throws Exception {
        require(server.isOnThread(), "M6_OWNER_THREAD_MISMATCH");
        require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world").toRealPath()),"M6_SAVE_ROOT_MISMATCH");
        var catalog=UniverseRegistryState.get(server); catalog.requireHealthy();
        Path catalogPath=config.root().resolve("world/data/quantumchamber_universes.dat");
        boolean baseline=config.phase().equals("baseline");
        if(baseline){
            require(catalog.records().isEmpty() && !Files.exists(catalogPath),"M6_BASELINE_NOT_FRESH");
            for(int i=0;i<IDS.size();i++)catalog.allocateOverworld(IDS.get(i),i);
            catalog.flush(server);
        }
        var disk=UniverseRegistryState.fromNbt(UniverseCatalogStore.read(catalogPath).getCompound("data"));disk.requireHealthy();
        require(catalog.records().size()==2 && catalog.records().equals(catalog.flushedRecords())
                && catalog.records().equals(disk.flushedRecords()) && !catalog.isDirty(),"M6_CATALOG_NOT_CHECKED");
        var worlds=new ArrayList<ServerWorld>();
        for(var key:List.of(World.OVERWORLD,World.NETHER,World.END))worlds.add(Objects.requireNonNull(server.getWorld(key)));
        var descriptors=new ArrayList<Map<String,Object>>();
        for(var id:IDS){
            var record=catalog.records().get(new UniverseId(id));
            require(record!=null,"M6_UUID_MISMATCH");
            var descriptor=record.definition().worlds().get(DimensionRole.OVERWORLD);
            var row=new LinkedHashMap<String,Object>();row.put("uuid",id.toString());row.put("worldKey",descriptor.worldKey().getValue().toString());
            row.put("recordNbt",record.toNbt().toString());descriptors.add(row);
            ServerWorld world;
            if(baseline){
                require(server.getWorld(descriptor.worldKey())==null,"M6_WORLD_ALREADY_PRESENT");
                var backend=new Minecraft121DynamicDimensionBackend();
                var result=new UniverseMaterializationService(backend).materializeChecked(server,record,descriptor);
                require(result.status()==MaterializeResult.Status.MATERIALIZED,"M6_MATERIALIZE_FAILED "+result.status());
                world=result.world();require(backend.resolveActive(server,descriptor).orElseThrow()==world,"M6_BACKEND_IDENTITY_MISMATCH");
            }else world=Objects.requireNonNull(server.getWorld(descriptor.worldKey()),"M6_REOPEN_NOT_MATERIALIZED");
            require(world.getServer()==server && server.getWorld(descriptor.worldKey())==world,"M6_WORLD_IDENTITY_MISMATCH");
            worlds.add(world);
        }
        long seed=server.getSaveProperties().getGeneratorOptions().getSeed();
        proof.put("saveSeed",Long.toString(seed));
        proof.put("serverThread",Thread.currentThread().getName());proof.put("descriptors",descriptors);
        proof.put("catalogSha256",hash(catalogPath));proof.put("catalogChecked",true);
        JsonObject previous=baseline?null:JsonParser.parseString(Files.readString(config.root().resolve("native-baseline.json"))).getAsJsonObject();
        if(previous!=null){
            require(previous.get("catalogSha256").getAsString().equals(proof.get("catalogSha256")),"M6_REOPEN_CATALOG_CHANGED");
            require(previous.get("descriptors").equals(JSON.toJsonTree(descriptors)),"M6_REOPEN_DESCRIPTOR_CHANGED");
            require(previous.get("saveSeed").getAsString().equals(Long.toString(seed)),"M6_REOPEN_SEED_CHANGED");
        }
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<worlds.size();i++)rows.add(observeWorld(worlds.get(i),i,seed));
        proof.put("worlds",rows);
        var sequenceRows=sampleSequences(worlds,seed,baseline?0:1);proof.put("randomSequences",sequenceRows);
        for(int i=3;i<worlds.size();i++){
            require(worlds.get(i).getSeed()==seed,"M6_DIAGNOSTIC_SCHEMA1_SEED_CHANGED");
            require(worlds.get(i).getRandomSequences()==worlds.get(0).getRandomSequences(),"M6_DIAGNOSTIC_SCHEMA1_SEQUENCES_CHANGED");
            require(rows.get(i).get("noiseSamples").equals(rows.get(0).get("noiseSamples")),"M6_DIAGNOSTIC_OVERWORLD_NOISE_CHANGED");
            require(rows.get(i).get("biomeSamples").equals(rows.get(0).get("biomeSamples")),"M6_DIAGNOSTIC_OVERWORLD_BIOME_CHANGED");
        }
        if(previous!=null){
            require(previous.get("worldsStable").equals(JSON.toJsonTree(stableRows(rows))),"M6_REOPEN_GENERATION_CHANGED");
            require(previous.get("sequenceGroups").equals(JSON.toJsonTree(sequenceRows.stream().map(r->r.get("group")).toList())),"M6_REOPEN_SEQUENCE_OWNER_CHANGED");
        }
        var observations=new LinkedHashMap<String,Object>();
        observations.put("newWorldStillReadsSaveSeed",true);observations.put("newWorldSharesCanonicalRandomSequences",true);
        observations.put("canonicalControlsMatch",true);observations.put("reopenExact",!baseline);
        proof.put("diagnosticAssertions",observations);
        boolean independentSeed=worlds.get(3).getSeed()!=seed && worlds.get(4).getSeed()!=seed && worlds.get(3).getSeed()!=worlds.get(4).getSeed();
        boolean independentSequences=worlds.get(3).getRandomSequences()!=worlds.get(0).getRandomSequences()
                && worlds.get(4).getRandomSequences()!=worlds.get(0).getRandomSequences() && worlds.get(3).getRandomSequences()!=worlds.get(4).getRandomSequences();
        require(!independentSeed && !independentSequences,"M6_DIAGNOSTIC_FUNCTIONAL_STATUS_CHANGED");
        proof.put("functionalAssertions",Map.of("newWorldHasIndependentSeed",independentSeed,"newWorldHasIndependentRandomSequences",independentSequences));
        proof.put("expectedFunctionalRed",List.of("newWorldHasIndependentSeed","newWorldHasIndependentRandomSequences"));
        if(baseline){
            var manifest=new LinkedHashMap<String,Object>();manifest.put("catalogSha256",proof.get("catalogSha256"));manifest.put("descriptors",descriptors);
            manifest.put("saveSeed",Long.toString(seed));manifest.put("worldsStable",stableRows(rows));
            manifest.put("sequenceGroups",sequenceRows.stream().map(r->r.get("group")).toList());
            writeNew(config.root().resolve("native-baseline.json"),manifest);
        }
        proof.put("diagnosticPassed",true);
        LOGGER.info("M6_NATIVE_OBSERVATIONS getSeed=shared-save-seed randomSequences=shared-canonical functional=newWorldHasIndependentSeed:RED");
    }

    private Map<String,Object> observeWorld(ServerWorld world,int ordinal,long seed) throws Exception {
        var row=new LinkedHashMap<String,Object>();
        var generator=world.getChunkManager().getChunkGenerator();
        require(generator instanceof NoiseChunkGenerator,"M6_UNEXPECTED_GENERATOR "+generator.getClass().getName());
        var noise=world.getChunkManager().getNoiseConfig();
        var settings=((NoiseChunkGenerator)generator).getSettings();
        var expected=NoiseConfig.create(settings.value(),world.getRegistryManager().getWrapperOrThrow(RegistryKeys.NOISE_PARAMETERS),seed);
        var calculator=world.getChunkManager().getStructurePlacementCalculator();
        var expectedCalculator=generator.createStructurePlacementCalculator(world.getRegistryManager().getWrapperOrThrow(RegistryKeys.STRUCTURE_SET),expected,seed);
        Object locator=field(world,"structureLocator");
        long locatorSeed=(long)field(locator,"seed");
        long structureSeed=(long)field(calculator,"structureSeed"),ringSeed=(long)field(calculator,"concentricRingSeed");
        long expectedStructure=(long)field(expectedCalculator,"structureSeed"),expectedRing=(long)field(expectedCalculator,"concentricRingSeed");
        require(locatorSeed==seed,"M6_LOCATOR_CONTROL_MISMATCH");
        require(structureSeed==expectedStructure && ringSeed==expectedRing,"M6_STRUCTURE_CONTROL_MISMATCH");
        require(world.getSeed()==seed && world.getSeed()==seed,"M6_GETSEED_CONTROL_MISMATCH");
        long biomeAccessSeed=(long)field(world.getBiomeAccess(),"seed");
        require(biomeAccessSeed==BiomeAccess.hashSeed(seed),"M6_BIOME_ACCESS_CONTROL_MISMATCH");
        var samples=noiseSamples(noise);var reference=noiseSamples(expected);
        require(samples.equals(reference),"M6_NOISE_CONTROL_MISMATCH");
        var biomes=biomeSamples(world,noise);var expectedBiomes=biomeSamples(world,expected);
        require(biomes.equals(expectedBiomes),"M6_BIOME_CONTROL_MISMATCH");
        var executor=Executors.newSingleThreadExecutor(task -> new Thread(task,"m6-native-seed-observer-"+ordinal));
        Map<String,Object> worker;
        try {
            worker=executor.submit(() -> Map.<String,Object>of("thread",Thread.currentThread().getName(),"serverThread",server.isOnThread(),
                    "getSeed",Long.toString(world.getSeed()),"noiseSamples",noiseSamples(noise),"biomeSamples",biomeSamples(world,noise))).get(30,TimeUnit.SECONDS);
        } finally {executor.shutdown();require(executor.awaitTermination(30,TimeUnit.SECONDS),"M6_WORKER_NOT_STOPPED");}
        require(Boolean.FALSE.equals(worker.get("serverThread")) && worker.get("getSeed").equals(Long.toString(seed))
                && worker.get("noiseSamples").equals(samples) && worker.get("biomeSamples").equals(biomes),"M6_WORKER_CONTROL_MISMATCH");
        row.put("worldKey",world.getRegistryKey().getValue().toString());row.put("ordinal",ordinal);
        row.put("worldIdentity",System.identityHashCode(world));row.put("generatorClass",generator.getClass().getName());
        row.put("generatorIdentity",System.identityHashCode(generator));row.put("settingsKey",settings.getKey().orElseThrow().getValue().toString());
        row.put("biomeSourceClass",generator.getBiomeSource().getClass().getName());row.put("biomeSourceIdentity",System.identityHashCode(generator.getBiomeSource()));
        row.put("getSeed",Long.toString(world.getSeed()));row.put("locatorSeed",Long.toString(locatorSeed));
        row.put("structureSeed",Long.toString(structureSeed));row.put("concentricRingSeed",Long.toString(ringSeed));
        row.put("expectedStructureSeed",Long.toString(expectedStructure));row.put("expectedConcentricRingSeed",Long.toString(expectedRing));
        row.put("biomeAccessSeed",Long.toString(biomeAccessSeed));row.put("noiseSamples",samples);row.put("biomeSamples",biomes);
        row.put("noiseControlExact",true);row.put("biomeControlExact",true);row.put("structureControlExact",true);row.put("worker",worker);
        Object properties=field(world,"worldProperties");row.put("propertiesClass",properties.getClass().getName());
        row.put("propertiesIdentity",System.identityHashCode(properties));
        if(ordinal>=3){
            row.put("propertiesDelegateSaveCanonical",field(properties,"saveProperties")==server.getSaveProperties());
            row.put("propertiesDelegateMainCanonical",field(properties,"worldProperties")==server.getSaveProperties().getMainWorldProperties());
            require(Boolean.TRUE.equals(row.get("propertiesDelegateSaveCanonical")) && Boolean.TRUE.equals(row.get("propertiesDelegateMainCanonical")),"M6_LEGACY_PROPERTIES_CHANGED");
        }
        return row;
    }

    private static List<String> noiseSamples(NoiseConfig noise) {
        var samples=new ArrayList<String>();
        for(var xyz:POINTS){
            var point=new DensityFunction.UnblendedNoisePos(xyz[0],xyz[1],xyz[2]);var router=noise.getNoiseRouter();
            samples.add(Long.toHexString(Double.doubleToRawLongBits(router.finalDensity().sample(point)))+":"
                    +Long.toHexString(Double.doubleToRawLongBits(router.temperature().sample(point)))+":"
                    +Long.toHexString(Double.doubleToRawLongBits(router.vegetation().sample(point))));
        }
        return samples;
    }

    private static List<String> biomeSamples(ServerWorld world,NoiseConfig noise) {
        var samples=new ArrayList<String>();
        for(var xyz:POINTS)samples.add(world.getChunkManager().getChunkGenerator().getBiomeSource()
                .getBiome(xyz[0],xyz[1],xyz[2],noise.getMultiNoiseSampler()).getKey().orElseThrow().getValue().toString());
        return samples;
    }

    private List<Map<String,Object>> sampleSequences(List<ServerWorld> worlds,long seed,int previousPhases) throws Exception {
        var id=Identifier.of("quantumchamber","m6_native_diagnostic/"+config.nonce());
        var states=new ArrayList<RandomSequencesState>();var groups=new ArrayList<Integer>();var counts=new ArrayList<Integer>();
        for(var world:worlds){
            var state=world.getRandomSequences();int group=-1;
            for(int i=0;i<states.size();i++)if(states.get(i)==state){group=i;break;}
            if(group<0){group=states.size();states.add(state);counts.add(0);}
            groups.add(group);counts.set(group,counts.get(group)+1);
        }
        var controls=new ArrayList<net.minecraft.util.math.random.Random>();
        for(int i=0;i<states.size();i++){
            long actualSeed=(long)field(states.get(i),"seed");require(actualSeed==seed,"M6_SEQUENCE_SEED_CONTROL_MISMATCH");
            require((int)field(states.get(i),"salt")==0 && (boolean)field(states.get(i),"includeWorldSeed")
                    && (boolean)field(states.get(i),"includeSequenceId"),"M6_SEQUENCE_PARAMETERS_CHANGED");
            var control=new RandomSequencesState(actualSeed).getOrCreate(id);
            for(int skip=0;skip<counts.get(i)*previousPhases;skip++)control.nextLong();
            controls.add(control);
        }
        var rows=new ArrayList<Map<String,Object>>();
        for(int i=0;i<worlds.size();i++){
            int group=groups.get(i);long expected=controls.get(group).nextLong();long actual=states.get(group).getOrCreate(id).nextLong();
            require(actual==expected,"M6_SEQUENCE_CONTINUITY_MISMATCH world="+worlds.get(i).getRegistryKey().getValue());
            rows.add(Map.of("worldKey",worlds.get(i).getRegistryKey().getValue().toString(),"group",group,
                    "stateIdentity",System.identityHashCode(states.get(group)),"sharedCanonical",states.get(group)==worlds.get(0).getRandomSequences(),
                    "stateSeed",Long.toString((long)field(states.get(group),"seed")),"sequenceId",id.toString(),"value",Long.toString(actual),
                    "expectedValue",Long.toString(expected),"continuityExact",true));
        }
        return rows;
    }

    private static List<Map<String,Object>> stableRows(List<Map<String,Object>> rows) {
        var stable=new ArrayList<Map<String,Object>>();
        for(var row:rows){var copy=new LinkedHashMap<String,Object>();
            for(var key:List.of("worldKey","getSeed","locatorSeed","structureSeed","concentricRingSeed","biomeAccessSeed","settingsKey","generatorClass","biomeSourceClass","noiseSamples","biomeSamples"))copy.put(key,row.get(key));
            stable.add(copy);
        }
        return stable;
    }
    private static Object field(Object owner,String name) throws ReflectiveOperationException {
        Class<?> type=owner.getClass();
        while(type!=null){try{var field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(NoSuchFieldException absent){type=type.getSuperclass();}}
        throw new NoSuchFieldException(owner.getClass().getName()+"."+name);
    }
    private static void require(boolean condition,String message){if(!condition)throw new IllegalStateException(message);}
    private static String hash(Path path) throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));}
    private static void writeNew(Path path,Object value) throws Exception {Files.createDirectories(path.getParent());Files.writeString(path,JSON.toJson(value),StandardOpenOption.CREATE_NEW);}

    private record Configuration(String nonce,String phase,String startupNonce,Path root,String chain,String expectedMode,Path evidence) {
        static Configuration read(){
            var keys=List.of("nonce","phase","startupNonce","root","chain","expectedMode");
            var values=keys.stream().map(key->System.getProperty("quantumchamber.m6universe."+key)).toList();
            if(values.stream().allMatch(Objects::isNull))return null;
            require(values.stream().allMatch(value->value!=null && !value.isBlank()),"M6_CONFIG_MISSING_PROPERTY");
            require(values.get(0).matches("[a-z0-9]+(?:-[a-z0-9]+)*") && values.get(2).matches("[0-9a-f]{32}"),"M6_CONFIG_NONCE_INVALID");
            require(Set.of("baseline","save-reopen").contains(values.get(1)),"M6_CONFIG_PHASE_UNKNOWN");
            require(values.get(4).equals("native-seed-diagnostic"),"M6_CONFIG_CHAIN_UNKNOWN");
            require(values.get(5).equals("DIAGNOSTIC"),"M6_CONFIG_MODE_MIXED");
            for(var property:System.getProperties().stringPropertyNames()){
                require(!property.startsWith("quantumchamber.m4recovery.") && !property.startsWith("quantumchamber.m5collapse.")
                        && !property.startsWith("fabric-api.gametest") && !property.startsWith("quantumchamber.gametest.")
                        && !property.equals("quantumchamber.test.collapseSuspended"),"M6_CONFIG_RUNTIME_FLAGS_MIXED "+property);
            }
            try{
                Path cwd=Path.of("").toAbsolutePath().normalize().toRealPath();Path root=Path.of(values.get(3)).toAbsolutePath().normalize();
                require(root.equals(cwd) && root.getFileName().toString().equals("m6-universe-"+values.get(0)),"M6_CONFIG_ROOT_MISMATCH");
                Path project=root.getParent().getParent();require(Files.isRegularFile(project.resolve("build.gradle")),"M6_CONFIG_PROJECT_MISSING");
                require(!Files.isSymbolicLink(root) && root.toRealPath().equals(root),"M6_CONFIG_ROOT_REPARSE");
                Path evidence=project.resolve(".superpowers/verification/m6-native-"+values.get(0)).resolve(values.get(1));
                require(!Files.exists(evidence.resolve("final.json")),"M6_CONFIG_PHASE_ALREADY_USED");
                return new Configuration(values.get(0),values.get(1),values.get(2),root,values.get(4),values.get(5),evidence);
            }catch(java.io.IOException failure){throw new IllegalStateException("M6_CONFIG_ROOT_UNREADABLE",failure);}
        }
    }
}
