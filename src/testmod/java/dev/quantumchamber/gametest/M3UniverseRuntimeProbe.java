package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberControllerLoadSyncQueue;
import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberLoadSyncTestAccess;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseCatalogStore;
import dev.quantumchamber.universe.UniverseMaterializationService;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 只在四個合法參數同時存在時，於兩個獨立 JVM 驗證正式 catalog 與原生存檔。 */
public final class M3UniverseRuntimeProbe implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m3-probe");
    private static final Gson JSON = new Gson();
    private static final String OWNER = ".superpowers/sdd/2026-09-19-m3a-dynamic-universe-backend";
    private static final UUID UNIVERSE_UUID = UUID.fromString("60000000-0000-4000-8000-000000000006");
    private static final UUID CONTROLLER_UUID = UUID.fromString("6c000000-0000-4000-8000-000000000006");
    private static final BlockPos SENTINEL = new BlockPos(8, 100, 8);
    private static final BlockPos CONTROLLER = new BlockPos(9, 100, 8);
    private static final ChunkPos CHUNK = new ChunkPos(0, 0);
    private static final Identifier AFTER_PRODUCTION = Identifier.of("quantumchamber", "m3_probe_after_production");
    private static final ChunkTicketType<ChunkPos> FIXTURE_TICKET = ChunkTicketType.create(
            "quantumchamber_m3_probe", Comparator.comparingLong(ChunkPos::toLong));

    private final Map<String, Object> proof = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private Configuration configuration;
    private MinecraftServer owner;
    private ServerWorld world;
    private ServerWorld loadWorld;
    private UniverseWorldDescriptor descriptor;
    private ChamberControllerBlockEntity controller;
    private ChamberLoadSyncTestAccess.Observation observation;
    private int loads;
    private int unloads;
    private int blockEntityLoads;
    private int ticks;
    private int loadedAtTick;
    private boolean ticketHeld;

    @Override
    public void onInitialize() {
        configuration = Configuration.read();
        if (configuration == null) return;
        proof.put("producer", "M3UniverseRuntimeProbe");
        proof.put("phase", configuration.phase());
        proof.put("nonce", configuration.nonce());
        proof.put("startupNonce", configuration.startupNonce());
        proof.put("root", configuration.root().toString());
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("failures", failures);
        proof.put("nativeReadOnly", configuration.phase().equals("reload-read"));
        event("initialized", Map.of());

        // 明確排在正式 callback 之後，避免依賴 entrypoint 的偶然載入順序。
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_PRODUCTION);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER_PRODUCTION, server -> owner = server);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_PRODUCTION);
        ServerTickEvents.END_SERVER_TICK.register(AFTER_PRODUCTION, this::tick);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_PRODUCTION);
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register(AFTER_PRODUCTION, this::blockEntityLoad);
        ServerWorldEvents.LOAD.register(this::worldLoad);
        ServerWorldEvents.UNLOAD.register(this::worldUnload);
        ServerLifecycleEvents.SERVER_STOPPING.register(this::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_PRODUCTION);
        ServerLifecycleEvents.SERVER_STOPPED.register(AFTER_PRODUCTION, this::stopped);
    }

    private void tick(MinecraftServer server) {
        if (server != owner || !failures.isEmpty() || Boolean.TRUE.equals(proof.get("stopRequested"))) return;
        guarded(server, () -> {
            if (++ticks == 1) {
                materialize(server);
                if (configuration.phase().equals("create-save")) writeFixture();
                else readFixture();
            } else {
                require(ticks == 2 && server.getTicks() == loadedAtTick + 1, "只能驗下一個 END tick");
                observation.assertHealthy();
                var outcomes = observation.outcomes();
                proof.put("processingOutcomes", outcomes.stream().map(Enum::name).toList());
                require(outcomes.equals(List.of(ChamberLoadSyncOutcome.CONSUMED)), "實際 queue branch 不是唯一 CONSUMED");
                require(!ChamberLoadSyncTestAccess.hasPending(server, controller), "CONSUMED 後仍保留原 BE entry");
                require(controller.powerInitialized() && !controller.wasPowered(), "foreign Controller 未同步真實低電位");
                assertNativeContent();
                proof.put("originCountAfter", ChamberRegistryState.get(server).registry().records().size());
                require((int) proof.get("originCountAfter") == 0, "foreign Controller 新增了 Chamber origin");
                require(loads == 1 && blockEntityLoads == 1 && unloads == 0, "停止前 lifecycle 次數不符");
                event("phase_assertions", Map.of("outcome", "CONSUMED", "nativeReadOnly", proof.get("nativeReadOnly")));
                requestStop(server);
            }
        });
    }

    private void materialize(MinecraftServer server) throws Exception {
        require(server.isOnThread(), "探針必須在 server thread");
        require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(configuration.root().resolve("world")), "實際 save root 不符");
        var chamberRegistry = ChamberRegistryState.get(server).registry();
        proof.put("originCountBefore", chamberRegistry.records().size());
        require(chamberRegistry.records().isEmpty(), "fresh fixture 不得已有 Chamber origin");
        var protection = ChamberProtectionService.get();
        require(readField(protection, "attachedServer") == server && readField(protection, "attachedRegistry") == chamberRegistry,
                "protection 未綁定 exact server／registry");
        proof.put("protectionAttachedBefore", true);
        var catalog = UniverseRegistryState.get(server);
        catalog.requireHealthy();
        Path catalogPath = configuration.root().resolve("world/data/quantumchamber_universes.dat");
        if (configuration.phase().equals("create-save")) {
            require(catalog.records().isEmpty() && !Files.exists(catalogPath), "create 必須使用空 catalog");
            catalog.allocateOverworld(UNIVERSE_UUID, 0);
            catalog.flush(server);
        }
        require(catalog.records().size() == 1, "catalog 必須恰有一筆正式 Universe definition");
        var diskNbt = UniverseCatalogStore.read(catalogPath).getCompound("data");
        var diskCatalog = UniverseRegistryState.fromNbt(diskNbt);
        diskCatalog.requireHealthy();
        require(diskCatalog.records().equals(catalog.records()) && diskCatalog.records().equals(catalog.flushedRecords())
                && diskNbt.equals(catalog.writeNbt(new NbtCompound())) && !catalog.isDirty(), "catalog current／durable／exact NBT readback 不符");
        var record = catalog.records().values().iterator().next();
        require(record.definition().universeId().value().equals(UNIVERSE_UUID), "catalog UUID 不符");
        descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        proof.put("worldKey", descriptor.worldKey().getValue().toString());
        proof.put("catalogChecked", true);
        proof.put("preMapAbsent", server.getWorld(descriptor.worldKey()) == null);
        require(Boolean.TRUE.equals(proof.get("preMapAbsent")), "建構前 map 已有 foreign key");
        assertDimensionRegistryAbsent(server);
        event("pre_materialize", Map.of("mapAbsent", true, "dimensionRegistryAbsent", true, "catalogNbt", diskNbt.toString()));
        var backend = new Minecraft121DynamicDimensionBackend();
        var result = new UniverseMaterializationService(backend).materializeChecked(server, record, descriptor);
        require(result.status() == MaterializeResult.Status.MATERIALIZED, "checked materialize 未成功：" + result.status());
        world = result.world();
        require(world == loadWorld && world.getServer() == server && server.getWorld(descriptor.worldKey()) == world
                && backend.resolveActive(server, descriptor).orElseThrow() == world, "物化後 exact identity 不符");
        var storage = ((MinecraftServerDynamicWorldAccess) server).quantumchamber$getSession().getWorldDirectory(descriptor.worldKey()).toRealPath();
        require(storage.equals(configuration.root().resolve("world/dimensions/quantumchamber/universe/" + UNIVERSE_UUID + "/overworld")), "native storage 不符");
        proof.put("storage", storage.toString());
        proof.put("materializedExact", true);
        assertDimensionRegistryAbsent(server);
        require(DimensionRole.fromVanillaKey(world.getRegistryKey()).isEmpty(), "foreign key 被錯認為 vanilla role");
        proof.put("foreignRoleEmpty", true);
        world.getChunkManager().addTicket(FIXTURE_TICKET, CHUNK, 0, CHUNK);
        ticketHeld = true;
        world.getChunk(CHUNK.x, CHUNK.z);
        require(world.getChunkManager().getWorldChunk(CHUNK.x, CHUNK.z) != null, "fixture chunk 不是 FULL");
        // Controller 與 ±3 格電位鄰域全部位於這個 FULL chunk，不另外載入鄰居。
        for (int x = CONTROLLER.getX() - 3; x <= CONTROLLER.getX() + 3; x++) {
            for (int z = CONTROLLER.getZ() - 3; z <= CONTROLLER.getZ() + 3; z++) {
                require(world.getChunkManager().getWorldChunk(x >> 4, z >> 4) != null, "fixture 電位鄰域不可讀");
            }
        }
        event("materialized", Map.of("identity", true, "storage", storage.toString(), "fixtureChunk", CHUNK.toString()));
    }

    private void writeFixture() {
        require(world.setBlockState(SENTINEL, Blocks.DIAMOND_BLOCK.getDefaultState(), 3), "diamond 寫入失敗");
        require(world.setBlockState(CONTROLLER, ModBlocks.CHAMBER_CONTROLLER.getDefaultState(), 3), "Controller 寫入失敗");
        require(world.getBlockEntity(CONTROLLER) == controller && controller != null, "沒有真 BE LOAD 的 Controller identity");
        controller.setChamberUuid(CONTROLLER_UUID);
        controller.markDirty();
        event("fixture_write", Map.of("sentinel", "minecraft:diamond_block", "controllerUuid", CONTROLLER_UUID.toString()));
    }

    /** reload 不配置 definition、不補方塊、不呼叫 BE setter，只讀原生載入結果。 */
    private void readFixture() {
        require(controller != null && observation != null, "native reload 未發出真 Controller BLOCK_ENTITY_LOAD");
        assertNativeContent();
        event("native_readback", Map.of("sentinel", proof.get("sentinel"), "controllerNbt", proof.get("nativeNbt")));
    }

    private void assertNativeContent() {
        require(world.getBlockState(SENTINEL).isOf(Blocks.DIAMOND_BLOCK), "原生 diamond sentinel 不符");
        require(world.getBlockState(CONTROLLER).isOf(ModBlocks.CHAMBER_CONTROLLER)
                && world.getBlockEntity(CONTROLLER) == controller, "原生 Controller exact identity 不符");
        var chunk = world.getChunkManager().getWorldChunk(CHUNK.x, CHUNK.z);
        require(chunk != null, "純讀驗收前 FULL chunk 消失");
        var nbt = chunk.getPackedBlockEntityNbt(CONTROLLER, world.getRegistryManager());
        assertControllerNbt(nbt);
        proof.put("nativeNbt", nbt.toString());
        proof.put("nativeNbtChecked", true);
        proof.put("sentinel", Registries.BLOCK.getId(world.getBlockState(SENTINEL).getBlock()).toString());
        proof.put("controller", Registries.BLOCK.getId(world.getBlockState(CONTROLLER).getBlock()).toString());
    }

    private static void assertControllerNbt(NbtCompound nbt) {
        require(nbt != null && nbt.getString("id").equals("quantumchamber:chamber_controller"), "原生 BE NBT id 不符");
        require(nbt.containsUuid("ChamberUuid") && nbt.getUuid("ChamberUuid").equals(CONTROLLER_UUID), "原生 BE NBT UUID 不符");
        require(nbt.getInt("x") == 9 && nbt.getInt("y") == 100 && nbt.getInt("z") == 8, "原生 BE NBT 座標不符");
        require(nbt.getString("InstanceKind").equals("ORIGIN") && nbt.getString("ChamberState").equals("INVALID")
                && nbt.getBoolean("PowerInitialized") && !nbt.getBoolean("WasPowered") && !nbt.getBoolean("ActivationBlocked"), "原生 BE NBT 狀態不符");
    }

    private void blockEntityLoad(BlockEntity blockEntity, ServerWorld eventWorld) {
        if (descriptor == null || !eventWorld.getRegistryKey().equals(descriptor.worldKey()) || !blockEntity.getPos().equals(CONTROLLER)) return;
        guarded(eventWorld.getServer(), () -> {
            require(eventWorld == world && blockEntity instanceof ChamberControllerBlockEntity, "BE LOAD world／型別不符");
            require(++blockEntityLoads == 1 && controller == null, "Controller BE LOAD 重複");
            controller = (ChamberControllerBlockEntity) blockEntity;
            require(ChamberLoadSyncTestAccess.hasPending(owner, controller), "真 BE LOAD 後正式 queue 未 enqueue exact Controller");
            proof.put("queuedAtLoad", true);
            loadedAtTick = owner.getTicks();
            if (configuration.phase().equals("reload-read")) {
                var loadedNbt = controller.createNbtWithIdentifyingData(eventWorld.getRegistryManager());
                assertControllerNbt(loadedNbt);
                event("native_nbt_at_load", Map.of("nbt", loadedNbt.toString()));
            }
            observation = ChamberLoadSyncTestAccess.observe(eventWorld, controller,
                    receipt -> event("queue_receipt", Map.of("outcome", receipt.outcome().name(), "exactController", receipt.controller() == controller)));
            event("block_entity_load", Map.of("queuedExactController", true, "tick", loadedAtTick));
        });
    }

    private void worldLoad(MinecraftServer server, ServerWorld eventWorld) {
        if (descriptor == null || !eventWorld.getRegistryKey().equals(descriptor.worldKey())) return;
        require(server == owner && eventWorld.getServer() == server && server.getWorld(descriptor.worldKey()) == eventWorld, "LOAD exact identity 不符");
        loadWorld = eventWorld;
        loads++;
        event("world_load", Map.of("count", loads, "exactMapIdentity", true));
    }

    private void worldUnload(MinecraftServer server, ServerWorld eventWorld) {
        if (descriptor == null || !eventWorld.getRegistryKey().equals(descriptor.worldKey())) return;
        guarded(server, () -> {
            require(server == owner && eventWorld == world && server.getWorld(descriptor.worldKey()) == world, "shutdown UNLOAD exact identity 不符");
            require(Boolean.TRUE.equals(proof.get("stoppingSeen")), "UNLOAD 不在正常 shutdown 內");
            unloads++;
            event("world_unload", Map.of("count", unloads, "exactMapIdentity", true));
        });
    }

    private void assertDimensionRegistryAbsent(MinecraftServer server) {
        require(!server.getCombinedDynamicRegistries().getCombinedRegistryManager().get(RegistryKeys.DIMENSION)
                .containsId(descriptor.worldKey().getValue()), "vanilla DIMENSION registry 出現 foreign definition");
        proof.put("dimensionRegistryAbsent", true);
    }

    private void requestStop(MinecraftServer server) {
        proof.put("stopRequested", true);
        event("stop_requested", Map.of());
        server.stop(false);
    }

    private void stopping(MinecraftServer server) {
        if (server != owner) return;
        guarded(server, () -> {
            proof.put("stoppingSeen", true);
            if (ticketHeld) {
                world.getChunkManager().removeTicket(FIXTURE_TICKET, CHUNK, 0, CHUNK);
                ticketHeld = false;
            }
            proof.put("ticketReleased", !ticketHeld);
            require(world != null && server.getWorld(descriptor.worldKey()) == world, "STOPPING 已失去 exact native owner");
            // 合成的停止清理見證與上面的真 BE LOAD 證據分開；不冒充 native enqueue。
            ChamberControllerLoadSyncQueue.enqueue(world, controller);
            require(ChamberLoadSyncTestAccess.hasPending(server, controller), "STOPPING 清理見證未入列");
            proof.put("queueWitnessBeforeStop", true);
            event("stopping_cleanup_witness", Map.of("synthetic", true, "queueCount", ChamberLoadSyncTestAccess.pendingCount(server)));
        });
    }

    private void stopped(MinecraftServer server) {
        if (server != owner) return;
        guarded(server, () -> {
            proof.put("stoppedSeen", true);
            require(!((Map<?, ?>) readStaticField(ChamberControllerLoadSyncQueue.class, "PENDING")).containsKey(server), "STOPPED 仍有 queue server key");
            proof.put("queueKeyRemoved", true);
            require(!((Map<?, ?>) readStaticField(ChamberControllerLoadSyncQueue.class, "OBSERVERS")).containsKey(server), "STOPPED 仍有 observer server key");
            proof.put("observersRemoved", true);
            var protection = ChamberProtectionService.get();
            require(readField(protection, "attachedServer") == null && readField(protection, "attachedRegistry") == null,
                    "STOPPED protection 未 detach");
            proof.put("protectionDetached", true);
            require(loads == 1 && unloads == 1, "正常停止未得到 LOAD1／UNLOAD1");
            if (observation != null) observation.close();
            event("stopped", Map.of("loads", loads, "unloads", unloads, "queueKeyRemoved", true, "protectionDetached", true));
        });
        proof.put("loads", loads);
        proof.put("unloads", unloads);
        proof.put("blockEntityLoads", blockEntityLoads);
        proof.put("status", failures.isEmpty() && ticks == 2 ? "PASS" : "FAIL");
        write(configuration.evidence().resolve("final.json"), JSON.toJson(proof), false);
        LOGGER.info("M3 phase={} status={} key={} LOAD={} UNLOAD={}", configuration.phase(), proof.get("status"), proof.get("worldKey"), loads, unloads);
    }

    /** 僅讀取私有診斷狀態，不修改正式 queue、observer、protection 或其集合。 */
    private static Object readField(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object readStaticField(Class<?> type, String name) throws ReflectiveOperationException {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private void guarded(MinecraftServer server, CheckedAction action) {
        try {
            action.run();
        } catch (Exception | AssertionError failure) {
            failures.add(failure.toString());
            LOGGER.error("M3 phase 驗收失敗", failure);
            event("failure", Map.of("error", failure.toString()));
            requestStop(server);
        }
    }

    private void event(String kind, Map<String, ?> details) {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("timeUtc", Instant.now().toString());
        entry.put("event", kind);
        entry.put("phase", configuration.phase());
        entry.put("startupNonce", configuration.startupNonce());
        entry.putAll(details);
        write(configuration.evidence().resolve("events.jsonl"), JSON.toJson(entry) + System.lineSeparator(), true);
    }

    private static void write(Path path, String text, boolean append) {
        try {
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException("probe evidence 寫入失敗", failure);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedAction { void run() throws Exception; }

    private record Configuration(String phase, String nonce, String startupNonce, Path root, Path evidence) {
        private static Configuration read() {
            String phase = System.getProperty("quantumchamber.m3.phase", "");
            String nonce = System.getProperty("quantumchamber.m3.nonce", "");
            String startupNonce = System.getProperty("quantumchamber.m3.startupNonce", "");
            String rootText = System.getProperty("quantumchamber.m3.root", "");
            if (!Set.of("create-save", "reload-read").contains(phase) || !nonce.matches("[a-z0-9]+(?:-[a-z0-9]+)*")
                    || nonce.equals("disabled") || !startupNonce.matches("[0-9a-f]{32}") || rootText.isBlank()) return null;
            var allowed = Set.of("quantumchamber.m3.phase", "quantumchamber.m3.nonce", "quantumchamber.m3.startupNonce", "quantumchamber.m3.root");
            if (System.getProperties().stringPropertyNames().stream().anyMatch(key -> key.startsWith("quantumchamber.m3.") && !allowed.contains(key))) return null;
            try {
                Path supplied = Path.of(rootText);
                Path root = supplied.toRealPath();
                if (!supplied.isAbsolute() || !root.equals(supplied.normalize()) || !root.equals(Path.of("").toRealPath())
                        || !root.getFileName().toString().equals("m3-universe-" + nonce)
                        || !root.getParent().getFileName().toString().equals("run")) return null;
                Path owner = root.getParent().getParent().resolve(OWNER);
                if (!owner.toRealPath().equals(owner)) return null;
                Path evidence = owner.resolve("task-6-gate-a-" + nonce).resolve(phase);
                if (!evidence.toRealPath().equals(evidence) || Files.exists(evidence.resolve("final.json"))) return null;
                return new Configuration(phase, nonce, startupNonce, root, evidence);
            } catch (IOException | RuntimeException invalid) {
                return null;
            }
        }
    }
}
