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
import net.minecraft.world.chunk.*;
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
    /** 無 U 的獨立控制；不建立 Flow，也不呼叫會初始化 authority 的 get()。 */
    static final class VanillaNormalControl {
        private static final long CONTROL_SEED = 20260921L;
        private final Path root,evidence;
        private final String chain,expectedPreset;
        private final boolean carversOnly;
        private final Map<String,Object> proof;
        private final List<Map<String,Object>> loads=new ArrayList<>(),snapshots=new ArrayList<>();
        private final List<String> requests=new ArrayList<>();
        private MinecraftServer server;
        private boolean sampled,finished,prepared;
        private int uLoadCount,ticks,preflightAttempts,carversWaitAttempts;
        private Chunk carversChunk;
        private final List<Map<String,Object>> preflightWaits=new ArrayList<>();

        VanillaNormalControl(Path root,Path evidence,Map<String,Object> proof) {
            this.root=root;this.evidence=evidence;this.proof=proof;
            chain=(String)proof.get("chain");
            carversOnly=chain.endsWith("-carvers-control");
            expectedPreset=switch(chain) {
                case "vanilla-normal-control", "vanilla-normal-carvers-control" -> "normal";
                case "vanilla-amplified-control", "vanilla-amplified-carvers-control" -> "amplified";
                default -> throw new IllegalArgumentException("VANILLA_CONTROL_CHAIN");
            };
            proof.put("expectedPreset",expectedPreset);
            proof.put("observerRegisteredAtModInit",true);proof.put("worldLoads",loads);
            proof.put("loadedWorldSnapshots",snapshots);proof.put("requests",requests);proof.put("uLoadCount",0);
            proof.put("preflightWaits",preflightWaits);
        }
        void loaded(MinecraftServer owner,ServerWorld world) {
            var key=world.getRegistryKey().getValue().toString();
            loads.add(Map.of("sequence",loads.size(),"worldKey",key,"serverIdentity",System.identityHashCode(owner)));
            if(key.startsWith("quantumchamber:universe/")) {
                proof.put("uLoadCount",++uLoadCount);failures().add("VANILLA_CONTROL_U_LOADED: "+key);
            }
        }
        void started(MinecraftServer owner) {
            server=owner;
            guarded(() -> {
                require(owner.isOnThread() && owner.getSavePath(WorldSavePath.ROOT).toRealPath().equals(root.resolve("world").toRealPath()),"VANILLA_CONTROL_ROOT");
                proof.put("serverIdentity",System.identityHashCode(owner));
                snapshot("STARTED");authority("startup");
                var properties=new Properties();
                try(var reader=Files.newBufferedReader(root.resolve("server.properties"))){properties.load(reader);}
                proof.put("propertiesSha256",hash(root.resolve("server.properties")));
                proof.put("propertiesText",Files.readString(root.resolve("server.properties")));
                proof.put("configuredPackOrder",properties.getProperty("initial-enabled-packs"));
                proof.put("runtimePackOrder",String.join(",",owner.getDataPackManager().getEnabledIds()));
                proof.put("configuredPreset",properties.getProperty("level-type"));
                proof.put("configuredGenerateStructures",properties.getProperty("generate-structures"));
                proof.put("configuredSeed",properties.getProperty("level-seed"));
                proof.put("seed",Long.toString(owner.getOverworld().getSeed()));
                var settings=Minecraft121SettingsLifecycle.acquire(owner);
                var preset=Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(settings).orElse("");
                proof.put("preset",preset);proof.put("generateStructures",owner.getSaveProperties().getGeneratorOptions().shouldGenerateStructures());
                require(owner.getOverworld().getRegistryKey().equals(net.minecraft.world.World.OVERWORLD),"VANILLA_CONTROL_WORLD_KEY");
                require(owner.getOverworld().getSeed()==CONTROL_SEED && owner.getSaveProperties().getGeneratorOptions().getSeed()==CONTROL_SEED
                        && properties.getProperty("level-seed","").equals(Long.toString(CONTROL_SEED)),carversOnly?"SAME_CARVERS_SEED":"VANILLA_CONTROL_SEED");
                require(preset.equals(expectedPreset) && properties.getProperty("level-type","").equals("minecraft:"+expectedPreset),carversOnly?"SAME_CARVERS_PRESET":"VANILLA_CONTROL_PRESET");
                require(owner.getSaveProperties().getGeneratorOptions().shouldGenerateStructures()
                        && properties.getProperty("generate-structures","").equals("true"),"VANILLA_CONTROL_STRUCTURES");
                require(PACKS.equals(proof.get("configuredPackOrder")),"VANILLA_CONTROL_PACKS");
                require(failures().isEmpty(),"VANILLA_CONTROL_U_LOADED");
            });
        }
        void tick(MinecraftServer owner) {
            if(owner!=server || finished || sampled)return;
            guarded(() -> {
                require(server.isOnThread(),"VANILLA_CONTROL_THREAD");
                require(++ticks<1800,"VANILLA_CONTROL_PREFLIGHT_WAIT_TIMEOUT");
                var world=server.getOverworld();
                if(carversOnly){sampleCarvers(world);return;}
                if(!prepared) {
                    prepared=true;proof.put("firstRequestTick",server.getTicks());snapshot("BEFORE_SAMPLE");
                    requests.add("0,0="+world.getChunk(0,0).getStatus());
                    requests.add("128,128="+world.getChunk(128,128).getStatus());
                }
                if(!preflight(world))return;
                sampled=true;proof.put("sampleTick",server.getTicks());
                var center=world.getChunk(32,32);requests.add("32,32="+center.getStatus());
                var stages=new ArrayList<Map<String,Object>>();proof.put("stages",stages);
                stages.add(sample(world,center,"CENTER_FULL",false));
                for(int x=31;x<=33;x++)for(int z=31;z<=33;z++)
                    requests.add(x+","+z+"="+world.getChunk(x,z).getStatus());
                var expected=new ArrayList<String>();expected.add("0,0=minecraft:full");expected.add("128,128=minecraft:full");expected.add("32,32=minecraft:full");
                for(int x=31;x<=33;x++)for(int z=31;z<=33;z++)expected.add(x+","+z+"=minecraft:full");
                require(requests.equals(expected),"VANILLA_CONTROL_REQUESTS");
                var full=sample(world,center,"FULL_3X3",true);stages.add(full);
                proof.put("sampleCount",full.get("sampleCount"));proof.put("blockHash",full.get("blockHash"));proof.put("biomeHash",full.get("biomeHash"));
                proof.put("matchedMaturity","FULL_3X3");snapshot("AFTER_SAMPLE");
                // 正式 block/biome 快照已固定；以下只讀事後設定及密度，不再要求任何 chunk。
                var parameters=new NbtCompound();parameters.putString("OverworldPreset",expectedPreset);parameters.putBoolean("GenerateStructures",true);
                var providerId=expectedPreset.equals("normal")?Minecraft121VanillaPresetProfileProvider.NORMAL_ID:Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID;
                var frozen=Minecraft121SettingsLifecycle.registry().createSnapshot(providerId,1,
                        parameters,Minecraft121SettingsLifecycle.acquire(server));
                proof.put("frozenSettings",Map.of("frozenSeed",Long.toString(world.getSeed()),"providerId",frozen.providerId(),
                        "providerVersion",frozen.providerVersion(),"parameters",frozen.parameters().toString(),
                        "snapshotSnbt",frozen.toNbt().toString(),"outputFingerprint",HexFormat.of().formatHex(frozen.outputFingerprint()),
                        "provenance","CONTROL_POST_SNAPSHOT_NOT_MEASURED_RECEIPT"));
                var density=world.getChunkManager().getNoiseConfig().getNoiseRouter().finalDensity();
                var densityRows=new ArrayList<Map<String,Object>>();var densityBits=new ArrayList<String>();
                proof.put("noiseEvidenceKind","POST_FULL_FINAL_DENSITY_RAW_BITS");proof.put("densityRows",densityRows);
                for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8) {
                    var point=new net.minecraft.world.gen.densityfunction.DensityFunction.UnblendedNoisePos(x,y,z);
                    var bits=HexFormat.of().toHexDigits(Double.doubleToRawLongBits(density.sample(point)));
                    densityRows.add(Map.of("index",densityRows.size(),"x",x,"y",y,"z",z,"rawDoubleBits",bits));densityBits.add(bits);
                }
                proof.put("densityCount",densityRows.size());proof.put("densityHash",digest(densityBits));
                proof.put("densityLimitation","不是 NOISE 階段或 feature 前方塊；只計算實際 NoiseConfig 的 finalDensity。");
                require(failures().isEmpty(),"VANILLA_CONTROL_U_LOADED");
                writeNew(evidence.resolve("sample.json"),proof);
                proof.put("sampleSha256",hash(evidence.resolve("sample.json")));
                proof.put("diagnosticPassed",true);finished=true;stop();
            });
        }
        private void sampleCarvers(ServerWorld world) throws Exception {
            var metadata=carversMetadata();proof.put("generationMetadata",metadata);
            int halo=((Number)metadata.get("haloRadius")).intValue();
            if(!prepared) {
                snapshot("BEFORE_SAMPLE");
                var before=new LinkedHashMap<String,Object>();proof.put("haloBefore",before);
                carversHalo(world,root.resolve("world"),halo,true,before);
                writeNew(evidence.resolve("carvers-before-generation.json"),proof);
                requests.add("1024,1024=minecraft:carvers");
                carversChunk=world.getChunkManager().getChunk(1024,1024,ChunkStatus.CARVERS,true);
                requireCarversChunk(world,carversChunk,true);proof.put("returnedChunkOwnerVerified",true);
                var holders=(dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor)world.getChunkManager().chunkLoadingManager;
                var holder=holders.quantumchamberTest$currentChunkHolders().get(carversChunk.getPos().toLong());
                proof.put("returnedChunkOwner",Map.of("worldKey",world.getRegistryKey().getValue().toString(),
                        "worldIdentity",System.identityHashCode(world),"managerIdentity",System.identityHashCode(world.getChunkManager()),
                        "holderIdentity",System.identityHashCode(holder),"chunkIdentity",System.identityHashCode(carversChunk),
                        "holderLatestIsReturnedChunk",holder.getLatest()==carversChunk,"holderStatus",holder.getActualStatus().toString(),
                        "returnedStatus",carversChunk.getStatus().toString(),"returnedClass",carversChunk.getClass().getName()));
                writeNew(evidence.resolve("carvers-request.json"),proof);prepared=true;
            }
            var chunk=carversChunk;
            proof.put("request",Map.of("worldKey",world.getRegistryKey().getValue().toString(),
                    "worldIdentity",System.identityHashCode(world),"managerIdentity",System.identityHashCode(world.getChunkManager()),
                    "x",1024,"z",1024,"status","minecraft:carvers","create",true,"explicitCount",1));
            requireCarversChunk(world,chunk,false);
            Map<String,Object> sample;
            try {
                var before=new LinkedHashMap<String,Object>();proof.put("haloBeforeSample",before);
                carversHalo(world,root.resolve("world"),halo,false,before);
                sample=carversSample(chunk);proof.put("carvers",sample);
                requireCarversBlocksExact(carversBlocks(sample),carversBlocks(sample));requireCarversChunk(world,chunk,false);
                var after=new LinkedHashMap<String,Object>();proof.put("haloAfterSample",after);
                carversHalo(world,root.resolve("world"),halo,false,after);
            } catch(IllegalStateException failure) {
                if(!"SAME_CARVERS_SHORT_HEADER".equals(failure.getMessage()))throw failure;
                proof.put("carversHeaderWaitTick",server.getTicks());proof.put("carversHeaderWaitCount",++carversWaitAttempts);
                writeNew(evidence.resolve(String.format(Locale.ROOT,"carvers-header-wait-%04d.json",carversWaitAttempts)),proof);
                return;
            }
            sampled=true;proof.put("sampleTick",server.getTicks());
            proof.put("sampleCount",sample.get("sampleCount"));proof.put("blockHash",sample.get("blockHash"));proof.put("biomeHash",sample.get("biomeHash"));
            var parameters=new NbtCompound();parameters.putString("OverworldPreset",expectedPreset);parameters.putBoolean("GenerateStructures",true);
            var providerId=expectedPreset.equals("normal")?Minecraft121VanillaPresetProfileProvider.NORMAL_ID:Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID;
            var frozen=Minecraft121SettingsLifecycle.registry().createSnapshot(providerId,1,parameters,Minecraft121SettingsLifecycle.acquire(server));
            proof.put("frozenSettings",Map.of("frozenSeed",Long.toString(world.getSeed()),"providerId",frozen.providerId(),
                    "providerVersion",frozen.providerVersion(),"parameters",frozen.parameters().toString(),"snapshotSnbt",frozen.toNbt().toString(),
                    "outputFingerprint",HexFormat.of().formatHex(frozen.outputFingerprint()),"provenance","CONTROL_CARVERS_NOT_MEASURED_RECEIPT"));
            proof.put("matchedMaturity","CARVERS");snapshot("AFTER_SAMPLE");
            require(failures().isEmpty(),"VANILLA_CONTROL_U_LOADED");
            writeNew(evidence.resolve("carvers-sample.json"),proof);
            proof.put("sampleSha256",hash(evidence.resolve("carvers-sample.json")));
            proof.put("diagnosticPassed",true);finished=true;stop();
        }
        /** 只容許空 virgin 區的短 header 等待下一 END tick；原嚴格 preflight 完全不變。 */
        @SuppressWarnings("unchecked") private boolean preflight(ServerWorld world) throws Exception {
            var observed=new LinkedHashMap<String,Object>();int attempt=++preflightAttempts;
            try {
                samplePreflight(server,root.resolve("world"),"sample",List.of(world),proof,observation -> {
                    observed.putAll(observation);
                    var lengths=new LinkedHashMap<String,Object>();
                    var worlds=(List<Map<String,Object>>)observation.get("worlds");
                    for(var owner:worlds)for(var cell:(List<Map<String,Object>>)owner.get("cells")) {
                        var file=Path.of((String)cell.get("regionFile"));
                        lengths.put(file.toString(),Files.exists(file)?Files.size(file):-1L);
                    }
                    observed.put("headerLengthsAfterAttempt",lengths);observed.put("attempt",attempt);observed.put("tick",server.getTicks());
                    var receipt=new LinkedHashMap<>(proof);receipt.put("observation",observed);
                    writeNew(evidence.resolve(String.format(Locale.ROOT,"sample-preflight-attempt-%04d.json",attempt)),receipt);
                    observed.put("attemptReceiptWritten",true);
                });
            } catch(Exception failure) {
                var owners=(List<Map<String,Object>>)observed.get("worlds");
                boolean wait=owners!=null && owners.size()==1;
                int shortHeaders=0,cells=0;
                if(wait)for(var cell:(List<Map<String,Object>>)owners.getFirst().get("cells")) {
                    cells++;
                    boolean shortHeader="ERROR".equals(cell.get("raw"))
                            && "java.lang.IllegalStateException: SAME_SAMPLE_SHORT_HEADER".equals(cell.get("rawError"));
                    if(shortHeader)shortHeaders++;
                    wait &= Boolean.FALSE.equals(cell.get("currentHolder")) && Boolean.FALSE.equals(cell.get("unloadingHolder"))
                            && Boolean.FALSE.equals(cell.get("liveFull")) && (shortHeader || "ABSENT".equals(cell.get("raw")));
                }
                if(wait && cells==9 && shortHeaders>0 && !observed.containsKey("error") && Boolean.TRUE.equals(observed.get("attemptReceiptWritten"))) {
                    preflightWaits.add(Map.of("event","CONTROL_PREFLIGHT_WAIT","attempt",attempt,"tick",server.getTicks(),
                            "headerLengthsAfterAttempt",observed.get("headerLengthsAfterAttempt"),"error",failure.toString()));
                    return false;
                }
                var receipt=new LinkedHashMap<>(proof);receipt.put("observation",observed);
                writeNew(evidence.resolve("sample-before-generation.json"),receipt);throw failure;
            }
            var receipt=new LinkedHashMap<>(proof);receipt.put("observation",observed);
            writeNew(evidence.resolve("sample-before-generation.json"),receipt);return true;
        }
        private Map<String,Object> sample(ServerWorld world,net.minecraft.world.chunk.WorldChunk center,String stage,boolean biome) throws Exception {
            require(center.getWorld()==world && center.getPos().equals(new ChunkPos(32,32))
                    && center.getStatus()==net.minecraft.world.chunk.ChunkStatus.FULL,"VANILLA_CONTROL_REQUESTS");
            var rows=new ArrayList<Map<String,Object>>();var blocks=new ArrayList<String>();var biomes=new ArrayList<String>();
            var result=new LinkedHashMap<String,Object>();result.put("stage",stage);result.put("rows",rows);
            proof.put(stage,result);
            result.put("chunkStatus",center.getStatus().toString());result.put("worldKey",world.getRegistryKey().getValue().toString());
            for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8) {
                var pos=new BlockPos(x,y,z);var row=new LinkedHashMap<String,Object>();
                row.put("index",rows.size());row.put("x",x);row.put("y",y);row.put("z",z);
                var block=center.getBlockState(pos).toString();blocks.add(block);row.put("block",block);
                if(biome) {var key=world.getBiome(pos).getKey().orElseThrow().getValue().toString();biomes.add(key);row.put("biome",key);}
                rows.add(row);
            }
            result.put("sampleCount",rows.size());result.put("blockHash",digest(blocks));
            if(biome)result.put("biomeHash",digest(biomes));
            require(rows.size()==1440,"VANILLA_CONTROL_SAMPLE_COUNT");
            return result;
        }
        private void snapshot(String label) {
            var keys=new ArrayList<String>();for(var world:server.getWorlds())keys.add(world.getRegistryKey().getValue().toString());
            snapshots.add(Map.of("stage",label,"worldKeys",keys,"serverIdentity",System.identityHashCode(server)));
            require(uLoadCount==0 && keys.stream().noneMatch(k -> k.startsWith("quantumchamber:universe/")),"VANILLA_CONTROL_U_LOADED");
            require(loads.stream().allMatch(r -> Integer.valueOf(System.identityHashCode(server)).equals(r.get("serverIdentity"))),"VANILLA_CONTROL_LOAD_OWNER");
        }
        private void authority(String label) throws Exception {
            var result=new LinkedHashMap<String,Object>();proof.put(label+"Authority",result);
            var storage=root.resolve("world/dimensions/quantumchamber/universe");
            require(!Files.exists(storage,LinkOption.NOFOLLOW_LINKS),"VANILLA_CONTROL_U_STORAGE");
            proof.put("uStorageAbsent",true);result.put("uStorageAbsent",true);
            var catalog=root.resolve("world/data/"+UniverseRegistryState.STATE_ID+".dat");
            result.put("catalog",emptyCatalog(catalog));proof.put("catalogEmpty",true);
            var allocation=root.resolve("world/data/"+UniverseAllocationLedger.FILE_NAME);
            if(Files.exists(allocation,LinkOption.NOFOLLOW_LINKS)) {
                var decode=UniverseAllocationLedger.class.getDeclaredMethod("fromNbt",NbtCompound.class);decode.setAccessible(true);
                var value=(UniverseAllocationLedger)decode.invoke(null,CheckedNbtFile.parse(CheckedNbtFile.readFile(allocation)));
                require(value.entries().isEmpty(),"VANILLA_CONTROL_ALLOCATION");result.put("allocation","EMPTY");
            }else result.put("allocation","ABSENT");
            proof.put("allocationEmpty",true);
        }
        private String emptyCatalog(Path path) throws Exception {
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return "ABSENT";
            // 與正式 checked reader 共用 codec，先以相同 16 MiB 上限限制解壓與輸入。
            var wrapped=CheckedNbtFile.parse(CheckedNbtFile.readFile(path));
            require(wrapped.contains("data",NbtElement.COMPOUND_TYPE) && wrapped.contains("DataVersion",NbtElement.INT_TYPE),"VANILLA_CONTROL_CATALOG");
            var catalog=UniverseRegistryState.fromNbt(wrapped.getCompound("data"));catalog.requireHealthy();
            require(catalog.records().isEmpty(),"VANILLA_CONTROL_CATALOG");return "EMPTY";
        }
        void stopping(MinecraftServer owner) {
            if(owner!=server)return;proof.put("stoppingSeen",true);
            guarded(() -> {snapshot("STOPPING");authority("stop");});
        }
        void stopped(MinecraftServer owner) {
            if(owner!=server)return;proof.put("stoppedSeen",true);
            proof.put("status",Boolean.TRUE.equals(proof.get("diagnosticPassed")) && Boolean.TRUE.equals(proof.get("stopRequested"))
                    && Boolean.TRUE.equals(proof.get("stoppingSeen")) && failures().isEmpty()?"PASS":"FAIL");
            try {writeNew(evidence.resolve("final.json"),proof);}
            catch(Exception failure){throw new IllegalStateException("VANILLA_CONTROL_RECEIPT_WRITE",failure);}
            LoggerFactory.getLogger("quantumchamber-m6-native").info("M6_NATIVE_END chain={} preset={} phase=sample mode=DIAGNOSTIC status={}",chain,expectedPreset,proof.get("status"));
        }
        @SuppressWarnings("unchecked") private List<String> failures(){return (List<String>)proof.get("failures");}
        private void stop(){if(!Boolean.TRUE.equals(proof.get("stopRequested"))){proof.put("stopRequested",true);server.stop(false);}}
        private void guarded(Checked action) {
            try{action.run();}catch(Exception failure){failures().add(failure.toString());finished=true;
                LoggerFactory.getLogger("quantumchamber-m6-native").error("VANILLA_CONTROL_FAILED",failure);stop();}
        }
    }
    /** 固定 CARVERS 樣本；只讀傳入 chunk，不透過 world 觸發 FULL。 */
    static Map<String,Object> carversSample(Chunk chunk) throws Exception {
        require(chunk!=null && chunk.getStatus()==ChunkStatus.CARVERS && !(chunk instanceof WorldChunk)
                && chunk.getPos().equals(new ChunkPos(1024,1024)),"SAME_CARVERS_STATUS");
        var rows=new ArrayList<Map<String,Object>>();var blocks=new ArrayList<String>();var biomes=new ArrayList<String>();
        for(int x=16384;x<16400;x+=3)for(int z=16384;z<16400;z+=3)for(int y=-64;y<256;y+=8) {
            var block=chunk.getBlockState(new BlockPos(x,y,z)).toString();
            var biome=chunk.getBiomeForNoiseGen(x>>2,y>>2,z>>2).getKey().orElseThrow().getValue().toString();
            rows.add(Map.of("index",rows.size(),"x",x,"y",y,"z",z,"block",block,"biome",biome));blocks.add(block);biomes.add(biome);
        }
        require(chunk.getStatus()==ChunkStatus.CARVERS && rows.size()==1440,"SAME_CARVERS_STATUS");
        return Map.of("rows",rows,"sampleCount",rows.size(),"blockHash",digest(blocks),"biomeHash",digest(biomes),
                "chunkStatus",chunk.getStatus().toString(),"chunkClass",chunk.getClass().getName(),"biomeKind","STORED_CHUNK_NOISE_BIOME");
    }
    @SuppressWarnings("unchecked") static List<String> carversBlocks(Map<String,Object> sample) {
        return ((List<Map<String,Object>>)sample.get("rows")).stream().map(row -> (String)row.get("block")).toList();
    }
    static void requireCarversBlocksExact(List<String> expected,List<String> actual) {
        require(expected.size()==1440 && actual.size()==1440 && expected.equals(actual),"SAME_CARVERS_BLOCK_EXACT");
    }
    private static void requireCarversChunk(ServerWorld world,Chunk chunk,boolean requireCurrentHolder) {
        require(world.getServer().isOnThread() && chunk!=null && chunk.getStatus()==ChunkStatus.CARVERS
                && chunk instanceof ProtoChunk && !(chunk instanceof WorldChunk) && chunk.getPos().equals(new ChunkPos(1024,1024)),"SAME_CARVERS_STATUS");
        var manager=(dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor)world.getChunkManager().chunkLoadingManager;
        var holder=manager.quantumchamberTest$currentChunkHolders().get(chunk.getPos().toLong());
        require(!requireCurrentHolder || holder!=null && holder.getLatest()==chunk && holder.getActualStatus()==ChunkStatus.CARVERS,"SAME_CARVERS_OWNER");
        for(var candidate:Arrays.asList(holder,manager.quantumchamberTest$chunksToUnload().get(chunk.getPos().toLong())))if(candidate!=null)
            require(candidate.getPos().equals(chunk.getPos()) && (candidate.getLatest()==null || candidate.getLatest()==chunk)
                    && (candidate.getActualStatus()==null || candidate.getActualStatus()==ChunkStatus.CARVERS)
                    && candidate.getWorldChunk()==null,"SAME_CARVERS_OWNER");
    }
    static Map<String,Object> carversMetadata() {
        var rows=new ArrayList<Map<String,Object>>();int write=0;
        for(var step:ChunkGenerationSteps.GENERATION.steps()) {
            write=Math.max(write,step.blockStateWriteRadius());
            rows.add(Map.of("status",step.targetStatus().toString(),"writeRadius",step.blockStateWriteRadius(),
                    "direct",step.directDependencies().getDependencies().stream().map(Object::toString).toList(),
                    "accumulated",step.accumulatedDependencies().getDependencies().stream().map(Object::toString).toList(),
                    "maxLevel",step.accumulatedDependencies().getMaxLevel()));
        }
        for(var status:List.of(ChunkStatus.NOISE,ChunkStatus.SURFACE,ChunkStatus.CARVERS))
            require(ChunkGenerationSteps.GENERATION.get(status).blockStateWriteRadius()==0,"SAME_CARVERS_WRITE_RADIUS");
        int rc=ChunkGenerationSteps.GENERATION.get(ChunkStatus.CARVERS).accumulatedDependencies().getMaxLevel();
        int rf=ChunkGenerationSteps.GENERATION.get(ChunkStatus.FULL).accumulatedDependencies().getMaxLevel();
        return Map.of("stages",rows,"carversDependencyRadius",rc,"fullDependencyRadius",rf,"maximumNonnegativeWriteRadius",write,
                "haloRadius",rc+rf+write+1,"formula","Rc+Rf+W+1","centerX",1024,"centerZ",1024);
    }
    /** 完整 halo 的唯讀觀測；region header 每次 snapshot 有界快取。 */
    static Map<String,Object> carversHalo(ServerWorld world,Path saveRoot,int halo,boolean virgin,Map<String,Object> result) throws Exception {
        require(world.getServer().isOnThread(),"SAME_CARVERS_THREAD");
        var cells=new ArrayList<Map<String,Object>>();result.put("cells",cells);
        var manager=(dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor)world.getChunkManager().chunkLoadingManager;
        var current=manager.quantumchamberTest$currentChunkHolders();var unloading=manager.quantumchamberTest$chunksToUnload();
        var live=new ArrayList<Map<String,Object>>();result.put("liveHolders",live);int nearest=Integer.MAX_VALUE;
        for(var group:List.of(Map.entry("current",current),Map.entry("unloading",unloading)))for(var holder:group.getValue().values()) {
            var latest=holder.getLatest();var status=holder.getActualStatus();var full=holder.getWorldChunk();var pos=holder.getPos();
            int distance=Math.max(Math.abs(pos.x-1024),Math.abs(pos.z-1024));
            var row=new LinkedHashMap<String,Object>();row.put("map",group.getKey());row.put("x",pos.x);row.put("z",pos.z);row.put("distance",distance);
            row.put("actualStatus",status==null?"NONE":status.toString());row.put("latestStatus",latest==null?"NONE":latest.getStatus().toString());row.put("worldChunk",full!=null);live.add(row);
            if(full!=null || status==ChunkStatus.FULL || latest!=null && latest.getStatus()==ChunkStatus.FULL)nearest=Math.min(nearest,distance);
            if(distance<=halo)require((status==null || status.isAtMost(ChunkStatus.CARVERS))
                    && (latest==null || latest.getStatus().isAtMost(ChunkStatus.CARVERS)) && full==null,"SAME_CARVERS_FULL_ISOLATION");
        }
        result.put("nearestLiveFullDistance",nearest==Integer.MAX_VALUE?"NONE_OBSERVED":nearest);
        result.put("nearestLiveFull",live.stream().filter(r -> Boolean.TRUE.equals(r.get("worldChunk")) || "minecraft:full".equals(r.get("actualStatus")) || "minecraft:full".equals(r.get("latestStatus"))).min(Comparator.comparingInt(r -> (Integer)r.get("distance"))).<Object>map(r -> r).orElse("NONE_OBSERVED"));
        require(nearest>halo,"SAME_CARVERS_FULL_ISOLATION");
        require(world.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize().equals(saveRoot),"SAME_CARVERS_SAVE_ROOT");
        var key=world.getRegistryKey().getValue();
        Path region=world==world.getServer().getOverworld()?saveRoot.resolve("region"):
                saveRoot.resolve("dimensions").resolve(key.getNamespace()).resolve(key.getPath()).resolve("region");
        region=region.toAbsolutePath().normalize();require(region.startsWith(saveRoot),"SAME_CARVERS_REGION_ESCAPE");
        result.put("worldKey",key.toString());result.put("region",region.toString());
        var headers=new HashMap<Path,Optional<int[]>>();
        var reader=M5CollapseDiskEvidence.class.getDeclaredMethod("chunk",Path.class,ChunkPos.class);reader.setAccessible(true);
        for(int x=1024-halo;x<=1024+halo;x++)for(int z=1024-halo;z<=1024+halo;z++) {
            var pos=new ChunkPos(x,z);var cell=new LinkedHashMap<String,Object>();cells.add(cell);cell.put("x",x);cell.put("z",z);
            boolean a=current.containsKey(pos.toLong()),b=unloading.containsKey(pos.toLong());cell.put("currentHolder",a);cell.put("unloadingHolder",b);
            Path file=region.resolve("r."+(x>>5)+"."+(z>>5)+".mca");cell.put("regionFile",file.toString());
            if(!headers.containsKey(file)) {
                boolean exists=safeRawFile(file,saveRoot);int[] header=null;
                if(exists){header=new int[1024];try(var input=new java.io.RandomAccessFile(file.toFile(),"r")) {
                    cell.put("headerLength",input.length());
                    require(input.length()>=8192,"SAME_CARVERS_SHORT_HEADER");for(int i=0;i<1024;i++)header[i]=input.readInt();}}
                headers.put(file,Optional.ofNullable(header));
            }
            var header=headers.get(file);int location=header.isPresent()?header.get()[(x&31)+(z&31)*32]:0;
            cell.put("canonicalNonReparse",true);cell.put("regionExists",header.isPresent());cell.put("headerValid",true);cell.put("entryLocation",location);
            String raw="ABSENT";
            if(location!=0) {
                @SuppressWarnings("unchecked") var nbt=(Optional<NbtCompound>)reader.invoke(null,region,pos);
                require(nbt.isPresent() && nbt.get().contains("Status",NbtElement.STRING_TYPE),"SAME_CARVERS_RAW_HEADER_MISMATCH");
                raw=nbt.get().getString("Status");var status=ChunkStatus.byId(raw);
                require(status!=null && status.toString().equals(raw) && status.isAtMost(ChunkStatus.CARVERS),"SAME_CARVERS_RAW_STATUS");
            }
            cell.put("raw",raw);
            require(!virgin || !a && !b && location==0,"SAME_CARVERS_PRIOR_GENERATION");
        }
        result.put("haloRadius",halo);result.put("virginRequired",virgin);result.put("nearestRawFullDistanceLowerBoundExclusive",halo);
        result.put("rawFullClaim","HALO_ONLY_LOWER_BOUND_NOT_GLOBAL_NEAREST");result.put("status","PASS");return result;
    }
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
        boolean collapsing,done,carversPrepared,carversDone;
        final Chunk[] carversChunks=new Chunk[2];
        final List<Map<String,Object>> carversSides=new ArrayList<>();
        Map<String,Object> carversLayer;
        com.google.gson.JsonObject priorCarvers,priorNewFinal;
        SessionRecoveryRecord startupRecord;
        int carversWaits;
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
            var far=new LinkedHashMap<String,Object>();row.put("carversPreflight",far);
            var metadata=carversMetadata();far.put("generationMetadata",metadata);
            var halo=new LinkedHashMap<String,Object>();far.put("sourceHalo",halo);
            carversHalo(source,root.resolve("world"),((Number)metadata.get("haloRadius")).intValue(),true,halo);
            far.put("sourceRecordSha256",row.get("sourceRecordSha256"));far.put("worldKey",source.getRegistryKey().getValue().toString());
            far.put("seed",Long.toString(source.getSeed()));far.put("generated",false);
            writeNew(evidence.resolve("carvers-source-preflight.json"),far);
            row.put("carversPreflightPath","carvers-source-preflight.json");row.put("carversPreflightSha256",hash(evidence.resolve("carvers-source-preflight.json")));
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
            startupRecord=record();frozen(startupRecord);
            for(String key:List.of("preset","session","sourceIdentity","uuid","seed","allocationToken","snapshot","profileId"))
                require(row.get(key).toString().equals(prior.get(key).getAsString()),"SAME_RESTART_IDENTITY:"+key);
            require(prior.get("sourceInitialRequest").getAsString().equals("128,128=minecraft:full"),"SAME_SOURCE_GENERATION_REQUEST");
            row.put("sourceInitialRequest",prior.get("sourceInitialRequest").getAsString());
            row.put("sourceRecordSha256",prior.get("sourceRecordSha256").getAsString());
            for(String key:List.of("formalLeverEntry","formalBatch","formalSelected","emptyStorageBeforeAllocation")) {
                require(prior.get(key).getAsBoolean(),"SAME_FORMAL_SOURCE_RECEIPT:"+key);row.put(key,true);
            }
            captureStartupAuthority();
            participant=new ConnectedGameTestPlayer(source,new com.mojang.authlib.GameProfile(UUID.fromString(prior.get("player").getAsString()),"m6-same"));
            participant.confirmTeleport();participant.player().setNoGravity(true);
        }
        void captureStartupAuthority() throws Exception {
            var journal=SessionRecoveryState.get(server);
            require(startupRecord!=null && startupRecord.equals(journal.records().get(sid)) && startupRecord.equals(journal.flushedRecords().get(sid))
                    && !journal.quarantinedSessions().contains(sid) && !journal.rollForwardPending(sid),"SAME_STARTUP_AUTHORITY_EXACT");
            var encode=SessionRecoveryRecord.class.getDeclaredMethod("toNbt",int.class);encode.setAccessible(true);
            Path nbt=evidence.resolve("carvers-startup-authority.nbt");
            try(var output=Files.newOutputStream(nbt,StandardOpenOption.CREATE_NEW)){NbtIo.writeCompressed((NbtCompound)encode.invoke(startupRecord,5),output);}
            var captured=new LinkedHashMap<String,Object>();
            for(String key:List.of("chain","phase","root","nonce","startupNonce","pid","startTimeUtc"))captured.put(key,proof.get(key));
            for(String key:List.of("preset","session","sourceIdentity","uuid","seed","allocationToken","snapshot","sourceRecordSha256"))captured.put(key,row.get(key));
            captured.put("schema",1);captured.put("kind","CHECKED_STARTUP_SESSION_AUTHORITY");captured.put("startupState",startupRecord.state().toString());
            captured.put("currentEqualsFlushed",true);captured.put("frozenSelectedIntentChecked",true);captured.put("notQuarantinedAndNoRollForward",true);
            captured.put("nbtPath","carvers-startup-authority.nbt");captured.put("nbtSha256",hash(nbt));
            Path receipt=evidence.resolve("carvers-startup-authority.json");writeNew(receipt,captured);
            row.put("startupAuthority",Map.of("path",receipt.getFileName().toString(),"sha256",hash(receipt)));
        }
        Map<String,Object> layerEnvelope() throws Exception {
            var layer=new LinkedHashMap<String,Object>();
            for(String key:List.of("chain","phase","root","nonce","startupNonce","pid","startTimeUtc"))layer.put(key,proof.get(key));
            for(String key:List.of("preset","session","sourceIdentity","uuid","seed","allocationToken","snapshot","sourceRecordSha256"))layer.put(key,row.get(key));
            layer.put("schema",1);layer.put("layer","ACTUAL_CARVERS_V1");layer.put("startupAuthority",row.get("startupAuthority"));
            layer.put("targetIdentity",UniverseKeys.world(targetId,DimensionRole.OVERWORLD).getValue().toString());
            var sourceFinal=evidence.getParent().resolve("source/final.json");var original=readJson(sourceFinal);
            require(original.get("status").getAsString().equals("PASS") && original.get("phase").getAsString().equals("source")
                    && original.get("root").getAsString().equals(root.toString()) && original.get("nonce").getAsString().equals(proof.get("nonce"))
                    && original.get("chain").getAsString().equals(chain),"SAME_CARVERS_SOURCE_LINK");
            layer.put("sourceFinal",Map.of("path","../source/final.json","sha256",hash(sourceFinal),
                    "startupNonce",original.get("startupNonce").getAsString(),"pid",original.get("pid").getAsLong()));
            require(hash(snapshotPath()).equals(row.get("sourceRecordSha256")),"SAME_CARVERS_SOURCE_NBT_SHA");return layer;
        }
        void loadNewCarversWitness() throws Exception {
            Path newFinalPath=evidence.getParent().resolve("new/final.json");priorNewFinal=readJson(newFinalPath);
            require(priorNewFinal.get("status").getAsString().equals("PASS") && priorNewFinal.get("phase").getAsString().equals("new")
                    && priorNewFinal.get("root").getAsString().equals(root.toString()) && priorNewFinal.get("nonce").getAsString().equals(proof.get("nonce"))
                    && priorNewFinal.get("chain").getAsString().equals(chain),"SAME_CARVERS_NEW_FINAL_IDENTITY");
            var priorRow=priorNewFinal.getAsJsonArray("variants").get(0).getAsJsonObject();
            require(priorRow.get("carversReceiptPath").getAsString().equals("carvers-new.json")
                    && hash(evidence.getParent().resolve("new/carvers-new.json")).equals(priorRow.get("carversReceiptSha256").getAsString()),"SAME_CARVERS_NEW_RECEIPT_SHA");
            require(hash(root.resolve("same-seed-"+preset+"-new.json")).equals(priorRow.get("newSnapshotSha256").getAsString()),"SAME_CARVERS_NEW_SNAPSHOT_SHA");
            priorCarvers=readJson(evidence.getParent().resolve("new/carvers-new.json"));
            require(priorCarvers.get("startupNonce").equals(priorNewFinal.get("startupNonce")) && priorCarvers.get("pid").equals(priorNewFinal.get("pid")),"SAME_CARVERS_NEW_PRODUCER");
            for(String key:List.of("chain","root","nonce"))require(priorCarvers.get(key).getAsString().equals(proof.get(key)),"SAME_CARVERS_NEW_IDENTITY:"+key);
            for(String key:List.of("preset","session","sourceIdentity","uuid","seed","allocationToken","snapshot","sourceRecordSha256"))
                require(priorCarvers.get(key).getAsString().equals(row.get(key)),"SAME_CARVERS_NEW_IDENTITY:"+key);
            require(priorCarvers.get("phase").getAsString().equals("new") && priorCarvers.get("targetIdentity").getAsString().equals(UniverseKeys.world(targetId,DimensionRole.OVERWORLD).getValue().toString())
                    && priorCarvers.get("schema").getAsInt()==1 && priorCarvers.get("layer").getAsString().equals("ACTUAL_CARVERS_V1")
                    && priorCarvers.get("generationMetadata").equals(JSON.toJsonTree(carversMetadata())),"SAME_CARVERS_NEW_SCHEMA");
            var sourceLink=priorCarvers.getAsJsonObject("sourceFinal");
            require(sourceLink.get("path").getAsString().equals("../source/final.json") && hash(evidence.getParent().resolve("source/final.json")).equals(sourceLink.get("sha256").getAsString()),"SAME_CARVERS_SOURCE_LINK");
            require(priorCarvers.getAsJsonArray("sides").size()==2,"SAME_CARVERS_NEW_SIDES");
            for(int i=0;i<2;i++) {
                var side=priorCarvers.getAsJsonArray("sides").get(i).getAsJsonObject();
                require(side.get("side").getAsString().equals(i==0?"source":"target") && side.get("worldKey").getAsString().equals(i==0?source.getRegistryKey().getValue().toString():UniverseKeys.world(targetId,DimensionRole.OVERWORLD).getValue().toString())
                        && side.get("seed").getAsString().equals(row.get("seed")),"SAME_CARVERS_NEW_SIDE_IDENTITY");
                requireCarversSample(side.getAsJsonObject("sample"));
            }
            carversLayer.put("newWitness",Map.of("path","../new/carvers-new.json","sha256",hash(evidence.getParent().resolve("new/carvers-new.json")),
                    "newFinalPath","../new/final.json","newFinalSha256",hash(newFinalPath),"newSnapshotPath","same-seed-"+preset+"-new.json",
                    "newSnapshotSha256",priorRow.get("newSnapshotSha256").getAsString()));
        }
        @SuppressWarnings("unchecked") boolean advanceCarvers(ServerWorld target) throws Exception {
            if(carversDone)return true;
            require(source.getServer()==server && target.getServer()==server && server.getWorld(source.getRegistryKey())==source
                    && server.getWorld(target.getRegistryKey())==target && source!=target && source.getSeed()==intent.sourceRootWorldSeed()
                    && target.getSeed()==intent.rootWorldSeed() && target.getRegistryKey().equals(UniverseKeys.world(targetId,DimensionRole.OVERWORLD)),"SAME_CARVERS_RUNTIME_IDENTITY");
            var worlds=List.of(source,target);int halo=((Number)carversMetadata().get("haloRadius")).intValue();
            if(!carversPrepared) {
                carversLayer=layerEnvelope();row.put("carversLayerProgress",carversLayer);carversLayer.put("generationMetadata",carversMetadata());carversLayer.put("sides",carversSides);
                if(phase.equals("restart"))loadNewCarversWitness();
                // 兩側 preflight 都完成後，才開始第一個 CARVERS request。
                for(int i=0;i<2;i++) {
                    var world=worlds.get(i);var side=new LinkedHashMap<String,Object>();carversSides.add(side);
                    side.put("side",i==0?"source":"target");side.put("worldKey",world.getRegistryKey().getValue().toString());side.put("seed",Long.toString(world.getSeed()));
                    side.put("frozenSettings",(i==0?intent.sourceSettingsSnapshot():intent.targetSettingsSnapshot()).toNbt().toString());
                    var initial=new LinkedHashMap<String,Object>();side.put("initialHalo",initial);
                    carversHalo(world,root.resolve("world"),halo,phase.equals("new"),initial);
                    var cells=(List<Map<String,Object>>)initial.get("cells");boolean absent=cells.stream().allMatch(c -> "ABSENT".equals(c.get("raw")) && Boolean.FALSE.equals(c.get("currentHolder")) && Boolean.FALSE.equals(c.get("unloadingHolder")));
                    side.put("provenance",phase.equals("new")?"VIRGIN_NEW":absent?"FRESH_REGENERATED":"REOPENED_OR_COMPLETED_PARTIAL");
                }
                writeNew(evidence.resolve("carvers-"+phase+"-before-generation.json"),carversLayer);
                for(int i=0;i<2;i++) {
                    var world=worlds.get(i);var side=carversSides.get(i);
                    carversChunks[i]=world.getChunkManager().getChunk(1024,1024,ChunkStatus.CARVERS,true);
                    requireCarversChunk(world,carversChunks[i],true);
                    var access=(dev.quantumchamber.gametest.mixin.ServerChunkLoadingManagerTestAccessor)world.getChunkManager().chunkLoadingManager;
                    var holder=access.quantumchamberTest$currentChunkHolders().get(carversChunks[i].getPos().toLong());
                    side.put("request",Map.of("x",1024,"z",1024,"status","minecraft:carvers","create",true,"explicitCount",1));
                    side.put("returnedOwner",Map.of("worldIdentity",System.identityHashCode(world),"managerIdentity",System.identityHashCode(world.getChunkManager()),
                            "holderIdentity",System.identityHashCode(holder),"chunkIdentity",System.identityHashCode(carversChunks[i]),
                            "holderLatestIsReturnedChunk",holder.getLatest()==carversChunks[i],"holderStatus",holder.getActualStatus().toString(),
                            "returnedStatus",carversChunks[i].getStatus().toString(),"returnedClass",carversChunks[i].getClass().getName()));
                    writeNew(evidence.resolve("carvers-"+phase+"-request-"+(i==0?"source":"target")+".json"),carversLayer);
                }
                carversPrepared=true;
            }
            try {
                for(int i=0;i<2;i++) {
                    var world=worlds.get(i);var side=carversSides.get(i);requireCarversChunk(world,carversChunks[i],false);
                    var before=new LinkedHashMap<String,Object>();side.put("beforeSampleHalo",before);carversHalo(world,root.resolve("world"),halo,false,before);
                    side.put("sample",carversSample(carversChunks[i]));requireCarversChunk(world,carversChunks[i],false);
                    var after=new LinkedHashMap<String,Object>();side.put("afterSampleHalo",after);carversHalo(world,root.resolve("world"),halo,false,after);
                }
            } catch(IllegalStateException failure) {
                if(!"SAME_CARVERS_SHORT_HEADER".equals(failure.getMessage()))throw failure;
                carversLayer.put("headerWaitCount",++carversWaits);carversLayer.put("headerWaitTick",server.getTicks());
                writeNew(evidence.resolve(String.format(Locale.ROOT,"carvers-%s-header-wait-%04d.json",phase,carversWaits)),carversLayer);return false;
            }
            var a=(Map<String,Object>)carversSides.get(0).get("sample");var b=(Map<String,Object>)carversSides.get(1).get("sample");
            requireCarversBlocksExact(carversBlocks(a),carversBlocks(b));
            require(a.get("biomeHash").equals(b.get("biomeHash")),"SAME_CARVERS_BIOME_EXACT");
            if(phase.equals("restart"))for(int i=0;i<2;i++) {
                var expected=priorCarvers.getAsJsonArray("sides").get(i).getAsJsonObject().getAsJsonObject("sample");
                var actual=JSON.toJsonTree(carversSides.get(i).get("sample")).getAsJsonObject();
                requireCarversBlocksExact(carversBlocksFromJson(expected),carversBlocksFromJson(actual));
                require(expected.get("biomeHash").equals(actual.get("biomeHash")),"SAME_CARVERS_RESTART_BIOME_EXACT");
            }
            carversLayer.put("blocksExact",true);carversLayer.put("biomesExact",true);carversLayer.put("status","PASS");
            Path receipt=evidence.resolve("carvers-"+phase+".json");writeNew(receipt,carversLayer);
            row.put("carversReceiptPath",receipt.getFileName().toString());row.put("carversReceiptSha256",hash(receipt));row.put("carversReceiptPhase",phase);
            carversDone=true;return true;
        }
        void verifyMaterialized() throws Exception {
            if(done)return;
            var journal=SessionRecoveryState.get(server);var currentRecord=record();
            boolean terminated=((Set<?>)field(journal,"terminated")).contains(sid);
            boolean absent=!journal.records().containsKey(sid) && !journal.flushedRecords().containsKey(sid);
            boolean healthy=!journal.quarantinedSessions().contains(sid) && !journal.rollForwardPending(sid);
            if(currentRecord==null)require(phase.equals("restart") && startupRecord!=null && startupRecord.state()==SessionState.PASSAGE_READY
                    && terminated && absent && healthy,"SAME_COLLAPSE_TERMINATION_AUTHORITY");
            else require(currentRecord.state()!=SessionState.SEALED && currentRecord.equals(journal.records().get(sid)) && healthy,"SAME_COLLAPSE_SEALED");
            row.put("sessionAuthority",Map.of("startupState",startupRecord.state().toString(),"currentState",currentRecord==null?"ABSENT":currentRecord.state().toString(),
                    "kind",currentRecord==null?"CHECKED_PASSAGE_ENDED":"LIVE_CHECKED","terminatedThisInstance",terminated,
                    "currentAndFlushedAbsent",absent,"notQuarantinedAndNoRollForward",healthy));
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
            if(!advanceCarvers(target))return;
            if(phase.equals("new"))samplePreflight(List.of(source,target),row);
            compareAndEdit(source,target,phase.equals("restart"),row,evidence.resolve("comparison-before-assert.json"),
                    () -> nativeConsumer(server,target,targetRecord),FinalComparison.T13_LAYERED);
            row.put("catalogChecked",true);
            if(phase.equals("new")) {
                writeNew(root.resolve("same-seed-"+preset+"-new.json"),row);
                row.put("newSnapshotSha256",hash(root.resolve("same-seed-"+preset+"-new.json")));
            } else {
                var prior=readJson(root.resolve("same-seed-"+preset+"-new.json"));
                for(String key:List.of("biomes","structures"))require(JSON.toJsonTree(row.get(key)).equals(prior.get(key)),"SAME_RESTART_GENERATION:"+key);
                var current=(Map<String,Object>)row.get("finalDiagnostic");
                var previous=prior.getAsJsonObject("finalDiagnostic");
                current.put("newToRestartDifferences",Map.of("source",sampleDifferences(jsonRows(previous.getAsJsonObject("source")),jsonRows(JSON.toJsonTree(current.get("source")).getAsJsonObject())),
                        "target",sampleDifferences(jsonRows(previous.getAsJsonObject("target")),jsonRows(JSON.toJsonTree(current.get("target")).getAsJsonObject()))));
                current.put("newFinalSha256",hash(evidence.getParent().resolve("new/final.json")));
                row.put("restartExact",true);
            }
            done=true;
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

    private static com.google.gson.JsonObject readJson(Path path) throws Exception {
        require(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path),"SAME_CARVERS_RECEIPT_MISSING_OR_TYPE");
        return JsonParser.parseString(Files.readString(path)).getAsJsonObject();
    }
    private static List<Map<String,Object>> jsonRows(com.google.gson.JsonObject sample) {
        var rows=new ArrayList<Map<String,Object>>();
        for(var value:sample.getAsJsonArray("rows")) {
            var row=value.getAsJsonObject();rows.add(Map.of("index",row.get("index").getAsInt(),"x",row.get("x").getAsInt(),"y",row.get("y").getAsInt(),
                    "z",row.get("z").getAsInt(),"block",row.get("block").getAsString(),"biome",row.get("biome").getAsString()));
        }return rows;
    }
    private static List<String> carversBlocksFromJson(com.google.gson.JsonObject sample) throws Exception {
        requireCarversSample(sample);return jsonRows(sample).stream().map(r -> (String)r.get("block")).toList();
    }
    private static void requireCarversSample(com.google.gson.JsonObject sample) throws Exception {
        var rows=jsonRows(sample);require(rows.size()==1440 && sample.get("sampleCount").getAsInt()==1440
                && sample.get("chunkStatus").getAsString().equals("minecraft:carvers") && sample.get("chunkClass").getAsString().equals("net.minecraft.world.chunk.ProtoChunk")
                && sample.get("biomeKind").getAsString().equals("STORED_CHUNK_NOISE_BIOME"),"SAME_CARVERS_SAMPLE_SCHEMA");
        int index=0;for(int x=16384;x<16400;x+=3)for(int z=16384;z<16400;z+=3)for(int y=-64;y<256;y+=8) {
            var row=rows.get(index);require(Integer.valueOf(index++).equals(row.get("index")) && Integer.valueOf(x).equals(row.get("x"))
                    && Integer.valueOf(y).equals(row.get("y")) && Integer.valueOf(z).equals(row.get("z")),"SAME_CARVERS_SAMPLE_COORDINATES");
        }
        require(digest(rows.stream().map(r -> r.get("block")).toList()).equals(sample.get("blockHash").getAsString())
                && digest(rows.stream().map(r -> r.get("biome")).toList()).equals(sample.get("biomeHash").getAsString()),"SAME_CARVERS_SAMPLE_HASH");
    }
    private enum FinalComparison {EXACT,T13_LAYERED}
    private static Map<String,Object> finalSample(ServerWorld world,List<String> blocks,List<String> biomes) throws Exception {
        var rows=new ArrayList<Map<String,Object>>();int index=0;
        for(int x=512;x<528;x+=3)for(int z=512;z<528;z+=3)for(int y=-64;y<256;y+=8) {
            rows.add(Map.of("index",index,"x",x,"y",y,"z",z,"block",blocks.get(index),"biome",biomes.get(index)));index++;
        }
        return Map.of("worldKey",world.getRegistryKey().getValue().toString(),"seed",Long.toString(world.getSeed()),"rows",rows,"sampleCount",rows.size(),
                "blockHash",digest(blocks),"biomeHash",digest(biomes),"chunkStatus","minecraft:full");
    }
    private static List<Map<String,Object>> sampleDifferences(List<Map<String,Object>> before,List<Map<String,Object>> after) {
        require(before.size()==1440 && after.size()==1440,"SAME_FINAL_DIFF_ROWS");var differences=new ArrayList<Map<String,Object>>();
        for(int i=0;i<1440;i++) {
            var a=before.get(i);var b=after.get(i);
            if(!a.get("block").equals(b.get("block")) || !a.get("biome").equals(b.get("biome")))
                differences.add(Map.of("index",i,"x",a.get("x"),"y",a.get("y"),"z",a.get("z"),
                        "beforeBlock",a.get("block"),"afterBlock",b.get("block"),"beforeBiome",a.get("biome"),"afterBiome",b.get("biome")));
        }return differences;
    }
    /** 兩側同一 registry/datapack、同一 FULL 鄰域後採樣，不把 live/raw 不同成熟度混比。 */
    static Map<String,Object> compareAndEdit(ServerWorld source,ServerWorld target,boolean restart) throws Exception {
        return compareAndEdit(source,target,restart,new LinkedHashMap<>(),null,null,FinalComparison.EXACT);
    }
    private static Map<String,Object> compareAndEdit(ServerWorld source,ServerWorld target,boolean restart,
            Map<String,Object> row,Path comparisonReceipt,Checked afterComparison,FinalComparison comparison) throws Exception {
        require(source!=target && source.getRegistryManager()==target.getRegistryManager() && source.getSeed()==target.getSeed(),"SAME_RUNTIME_IDENTITIES");
        // 來源先為玩家建築生成中心；目標也先中心，避免把不同 feature 寫入順序混成 seed 差異。
        var worlds=List.of(source,target);
        var requests=List.of(new ArrayList<String>(),new ArrayList<String>());
        var globalRequests=new ArrayList<String>();
        boolean stageWitness=true;
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
        if(comparison==FinalComparison.T13_LAYERED) {
            var a=finalSample(source,sourceTerrain,sourceBiomes);var b=finalSample(target,targetTerrain,targetBiomes);
            var diagnostic=new LinkedHashMap<String,Object>();diagnostic.put("schema",1);diagnostic.put("kind","T13_FINAL_DIAGNOSTIC");
            diagnostic.put("source",a);diagnostic.put("target",b);
            diagnostic.put("differences",sampleDifferences(jsonRows(JSON.toJsonTree(a).getAsJsonObject()),jsonRows(JSON.toJsonTree(b).getAsJsonObject())));
            diagnostic.put("reviewItems",List.of(Map.of("side","source","blockHash",a.get("blockHash"),"classification","UNRESOLVED","novelty","NOT_CLASSIFIED","reviewStatus","UNRESOLVED"),
                    Map.of("side","target","blockHash",b.get("blockHash"),"classification","UNRESOLVED","novelty","NOT_CLASSIFIED","reviewStatus","UNRESOLVED")));
            diagnostic.put("classificationScope","NO_HISTORICAL_MEMBERSHIP_CHECK");row.put("finalDiagnostic",diagnostic);
        }
        if(afterComparison!=null) {
            row.put("nativeConsumerCoverage","target-only");
            row.put("canonicalSourceNativeConsumer","NOT_COVERED");
            row.put("nativeConsumerOrder","after-snapshot-before-terrain-assert");
        }
        if(comparisonReceipt!=null)writeNew(comparisonReceipt,row);
        else LoggerFactory.getLogger("quantumchamber-m6-gametest").info("SAME_COMPARISON {}",JSON.toJson(row));
        // 原抽樣與收據先完成；owner FAIL 時原 terrain require 尚未執行。
        if(afterComparison!=null)afterComparison.run();
        if(comparison==FinalComparison.T13_LAYERED)require(sourceBiomes.equals(targetBiomes),"SAME_T13_FINAL_BIOME_EXACT"+firstDifference);
        else require(sourceTerrain.equals(targetTerrain) && sourceBiomes.equals(targetBiomes),"sameSeedFreshCopiesSettingsNotBlocks:TERRAIN_BIOME"+firstDifference);
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
