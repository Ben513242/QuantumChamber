package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import com.mojang.authlib.GameProfile;
import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseCatalogStore;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseTransferAuthority.Decision;
import dev.quantumchamber.universe.UniverseTransferPoint;
import dev.quantumchamber.universe.UniverseTransferResult;
import dev.quantumchamber.universe.UniverseTransferResult.Outcome;
import dev.quantumchamber.universe.UniverseTransferResult.NativeOutcome;
import dev.quantumchamber.universe.UniverseTransferResult.RollbackOutcome;
import dev.quantumchamber.universe.UniverseTransferService;
import dev.quantumchamber.universe.UniverseTransferTestAccess;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 僅在四個合法啟動參數與 owned root 同時成立時註冊；不提供 gameplay 入口。 */
public final class M3UniverseTransferProbe implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-m3-transfer-probe");
    private static final Gson JSON = new Gson();
    private static final String OWNER = ".superpowers/sdd/2026-09-19-m3b-server-transfer-readiness";
    private static final UUID UNIVERSE = UUID.fromString("63000000-0000-4000-8000-000000000063");
    private static final RegistryKey<World> DESTINATION = UniverseKeys.world(UniverseId.of(UNIVERSE), DimensionRole.OVERWORLD);
    private static final Identifier BEFORE = Identifier.of("quantumchamber", "m3_transfer_before");
    private static final Identifier AFTER = Identifier.of("quantumchamber", "m3_transfer_after");
    private static final ChunkTicketType<ChunkPos> TICKET = ChunkTicketType.create("quantumchamber_m3_transfer", Comparator.comparingLong(ChunkPos::toLong));
    private final Map<String, Object> proof = new LinkedHashMap<>();
    private final List<String> failures = new ArrayList<>();
    private final List<Ticket> tickets = new ArrayList<>();
    private final List<Floor> floors = new ArrayList<>();
    private Configuration config;
    private MinecraftServer owner;
    private UniverseTransferTestAccess.Lifecycle lifecycle;
    private UniverseWorldDescriptor descriptor;
    private ServerWorld destination;
    private ConnectedGameTestPlayer fixture;
    private ServerPlayerEntity player;
    private UniverseTransferPoint home;
    private int loads;
    private int unloads;
    private boolean ran;
    private boolean assertionsPassed;
    private boolean stopRequested;

    @Override public void onInitialize() {
        config = Configuration.read();
        if (config == null) return;
        proof.put("producer", "M3UniverseTransferProbe");
        proof.put("phase", config.phase());
        proof.put("nonce", config.nonce());
        proof.put("startupNonce", config.startupNonce());
        proof.put("root", config.root().toString());
        proof.put("evidenceOwner", config.evidence().getParent().getParent().toString());
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        proof.put("failures", failures);
        event("initialized", Map.of());
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(BEFORE, Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STARTED.register(BEFORE, this::beforeStarted);
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STARTED.register(AFTER, this::started);
        ServerWorldEvents.LOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerWorldEvents.LOAD.register(AFTER, this::worldLoad);
        ServerWorldEvents.UNLOAD.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerWorldEvents.UNLOAD.register(AFTER, this::worldUnload);
        ServerTickEvents.END_SERVER_TICK.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerTickEvents.END_SERVER_TICK.register(AFTER, this::tick);
        ServerLifecycleEvents.SERVER_STOPPING.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STOPPING.register(AFTER, this::stopping);
        ServerLifecycleEvents.SERVER_STOPPED.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER);
        ServerLifecycleEvents.SERVER_STOPPED.register(AFTER, this::stopped);
    }

    private void beforeStarted(MinecraftServer server) {
        owner = server;
        guarded(() -> {
            require(server.isOnThread() && UniverseTransferTestAccess.contextAbsent(server)
                    && server.getWorld(DESTINATION) == null, "正式 bootstrap 前 context／dynamic map 必須不存在");
            require(server.getSavePath(WorldSavePath.ROOT).toRealPath().equals(config.root().resolve("world")), "save root 不符");
            proof.put("boundBeforeProduction", true);
        });
    }

    private void started(MinecraftServer server) {
        if (server != owner) return;
        guarded(() -> {
            lifecycle = UniverseTransferTestAccess.capture(server);
            proof.put("lifecycleContextIdentity", System.identityHashCode(lifecycle.context()));
            proof.put("lifecycleBackendIdentity", System.identityHashCode(lifecycle.backend()));
            proof.put("probeBackendIdentity", System.identityHashCode(lifecycle.backend()));
            proof.put("backendOwner", "production-lifecycle");
            if (config.phase().equals("setup-catalog")) {
                require(loads == 0 && lifecycle.backend().runtimeSnapshot(server).isEmpty(), "setup 必須空 catalog／無動態 world");
            } else {
                require(loads == 1 && destination != null && server.getWorld(DESTINATION) == destination,
                        "production bootstrap 沒有唯一 exact dynamic LOAD");
                lifecycle.assertWorld(destination, 0);
            }
            event("production_adopted", Map.of("loads", loads, "backendIdentity", System.identityHashCode(lifecycle.backend())));
        });
    }

    private void tick(MinecraftServer server) {
        if (server != owner || ran || !failures.isEmpty()) return;
        ran = true;
        guarded(() -> {
            lifecycle.assertAttached(true);
            var catalog = UniverseRegistryState.get(owner);
            Path catalogPath = config.root().resolve("world/data/quantumchamber_universes.dat");
            if (config.phase().equals("setup-catalog")) {
                require(catalog.records().isEmpty() && !Files.exists(catalogPath), "setup 必須使用空 catalog");
                catalog.allocateOverworld(UNIVERSE, 0);
                catalog.flush(owner);
                require(owner.getWorld(DESTINATION) == null && loads == 0, "setup 不得物化 world");
            }
            var disk = UniverseRegistryState.fromNbt(UniverseCatalogStore.read(catalogPath).getCompound("data"));
            disk.requireHealthy();
            require(catalog.records().size() == 1 && disk.records().equals(catalog.records())
                    && disk.records().equals(catalog.flushedRecords()) && !catalog.isDirty(), "正式 catalog readback 不符");
            descriptor = catalog.records().values().iterator().next().definition().worlds().get(DimensionRole.OVERWORLD);
            require(descriptor.worldKey().equals(DESTINATION), "catalog key 不符");
            proof.put("catalogChecked", true);
            proof.put("worldKey", DESTINATION.getValue().toString());
            if (!config.phase().equals("setup-catalog")) {
                require(lifecycle.backend().resolveActive(owner, descriptor).orElseThrow() == destination, "不是 production exact ACTIVE world");
                proof.put("lifecycleMaterializedExact", true);
                proof.put("sourceWorldIdentity", System.identityHashCode(owner.getOverworld()));
                proof.put("destinationWorldIdentity", System.identityHashCode(destination));
                runTransfer();
                cleanup();
            }
            assertionsPassed = true;
            proof.put("assertionsPassed", true);
            event("phase_assertions", Map.of("passed", true));
            requestStop();
        });
    }

    private void runTransfer() throws Exception {
        double x = switch (config.phase()) {
            case "target-not-full" -> 4094.5;
            case "post-move-authority-loss" -> 1032.5;
            case "stale-service-receipt" -> 2056.5;
            default -> 8.5;
        };
        home = new UniverseTransferPoint(World.OVERWORLD, new Vec3d(x, 318, 8.5), new Vec3d(0.125, 0.03125, -0.0625), 37.5f, -12.5f);
        var source = owner.getOverworld();
        int centerX = MathHelper.floor(x) >> 4;
        hold(source, new ChunkPos(centerX, 0));
        if (config.phase().equals("target-not-full")) hold(source, new ChunkPos(centerX + 1, 0));
        hold(destination, new ChunkPos(centerX, 0));
        floor(source, BlockPos.ofFloored(home.position()));
        floor(destination, BlockPos.ofFloored(home.position()));
        UUID uuid = UUID.randomUUID();
        fixture = new ConnectedGameTestPlayer(source, new GameProfile(uuid, "m3-" + uuid.toString().substring(0, 8)));
        player = fixture.player();
        fixture.confirmTeleport();
        // 原生同世界 teleport 保留既有 velocity；先建立 fixture 的非零初始條件。
        player.setVelocity(home.velocity());
        require(new SessionTransferService().move(player, source, home.position(), home.velocity(), home.yaw(), home.pitch()), "fixture 原生來源定位失敗");
        assertPlayer(source, home);
        proof.put("playerUuid", uuid.toString());
        proof.put("playerIdentity", System.identityHashCode(player));
        proof.put("playerManagerIdentity", System.identityHashCode(owner.getPlayerManager()));
        proof.put("joinObserved", fixture.joinTick() >= 0);
        proof.put("sourcePose", point(home));
        savePlayer("source", home);
        var fault = config.phase().equals("post-move-authority-loss")
                ? new UniverseTransferTestAccess.AuthorityLoss(lifecycle.backend(), destination) : null;
        var moves = new UniverseTransferTestAccess.NativeMoves(fault);
        var service = new UniverseTransferService(fault == null ? lifecycle.backend() : fault, moves);
        ChunkPos missing = new ChunkPos(centerX + 1, 0);
        if (config.phase().equals("target-not-full")) {
            require(MathHelper.floor(player.getBoundingBox().maxX + 2) >> 4 == missing.x, "missing chunk 必須落在 bbox 外圈");
            require(destination.getChunkManager().getChunk(missing.x, missing.z, ChunkStatus.FULL, false) == null, "missing fixture 已是 FULL");
            proof.put("missingChunk", List.of(missing.x, missing.z));
            proof.put("missingFullBefore", false);
        }
        var moved = service.moveToUniverse(player, descriptor);
        proof.put("moveResult", result(moved));
        switch (config.phase()) {
            case "success-roundtrip", "stale-service-receipt" -> {
                require(moved.outcome() == Outcome.MOVED && moved.receipt() != null, "原生移入未 MOVED：" + moved);
                var arrival = new UniverseTransferPoint(DESTINATION, home.position(), home.velocity(), home.yaw(), home.pitch());
                assertPlayer(destination, arrival);
                require(moved.actual().matches(arrival), "move result 缺少 exact destination observation");
                proof.put("destinationPose", point(arrival));
                savePlayer("destination", arrival);
                if (config.phase().equals("stale-service-receipt")) {
                    var coldMoves = new UniverseTransferTestAccess.NativeMoves(null);
                    var coldService = new UniverseTransferService(lifecycle.backend(), coldMoves);
                    var rejected = coldService.returnToSource(player, moved.receipt());
                    proof.put("staleResult", result(rejected));
                    require(rejected.outcome() == Outcome.REJECTED_BEFORE_MOVE && rejected.decision() == Decision.REJECT_RECEIPT_IDENTITY
                            && rejected.move().outcome() == NativeOutcome.NOT_ATTEMPTED && coldMoves.calls().isEmpty(), "新 service 必須在 receipt identity gate 拒絕");
                    assertPlayer(destination, arrival);
                    savePlayer("stale-rejected", arrival);
                }
                var returned = service.returnToSource(player, moved.receipt());
                proof.put("returnResult", result(returned));
                require(returned.outcome() == Outcome.RETURNED && returned.actual().matches(home), "原 service 未真 RETURNED");
                assertPlayer(source, home);
                require(moves.calls().size() == 2, "roundtrip 必須恰有兩次真 native move");
            }
            case "target-not-full" -> {
                require(moved.outcome() == Outcome.REJECTED_BEFORE_MOVE && moved.decision() == Decision.REJECT_TARGET_NOT_FULL
                        && moved.move().outcome() == NativeOutcome.NOT_ATTEMPTED && moves.calls().isEmpty(), "FULL 缺失未在 native move 前拒絕");
                require(destination.getChunkManager().getChunk(missing.x, missing.z, ChunkStatus.FULL, false) == null, "拒絕造成 missing chunk force-load");
                proof.put("missingFullAfter", false);
                assertPlayer(source, home);
            }
            case "post-move-authority-loss" -> {
                require(fault.revoked() && fault.activeReads() > 0 && moves.calls().size() == 2, "fault 必須先 ACTIVE 再原生成功，最後真 rollback");
                require(moved.outcome() == Outcome.FAILED_ROLLED_BACK && moved.move().outcome() == NativeOutcome.REPORTED_SUCCESS
                        && moved.move().actual().worldKey().equals(DESTINATION) && moved.move().actual().worldEntityExact()
                        && moved.rollback().outcome() == RollbackOutcome.SUCCEEDED
                        && moved.rollback().nativeOutcome() == NativeOutcome.REPORTED_SUCCESS && moved.actual().matches(home), "沒有完整真 move／rollback 證據");
                require(lifecycle.backend().resolveActive(owner, descriptor).orElseThrow() == destination, "fault 不得改 production backend");
                proof.put("faultRevokedAfterNativeSuccess", true);
                proof.put("productionBackendStillActive", true);
                assertPlayer(source, home);
            }
            default -> throw new AssertionError("未識別 transfer phase");
        }
        proof.put("nativeCalls", moves.calls());
        proof.put("finalPose", point(home));
        savePlayer("final", home);
    }

    private void assertPlayer(ServerWorld expectedWorld, UniverseTransferPoint expected) {
        require(player != null && fixture.player() == player && player.getServer() == owner && !player.isRemoved() && player.isAlive()
                && owner.getPlayerManager().getPlayer(player.getUuid()) == player && player.getServerWorld() == expectedWorld
                && owner.getWorld(expected.worldKey()) == expectedWorld && expectedWorld.getEntity(player.getUuid()) == player,
                "玩家／PlayerManager／native map／world entity exact identity 不符");
        var other = expectedWorld == destination ? owner.getOverworld() : destination;
        require(other.getEntity(player.getUuid()) == null, "其他 world 仍持有相同玩家 entity");
        require(player.getPos().squaredDistanceTo(expected.position()) < 1e-12
                && player.getVelocity().squaredDistanceTo(expected.velocity()) < 1e-12
                && Math.abs(MathHelper.wrapDegrees(player.getYaw() - expected.yaw())) < 1e-4
                && Math.abs(player.getPitch() - expected.pitch()) < 1e-4, "原生完整 pose 不符");
        proof.put("playerAndManagerExact", true);
        proof.put("worldEntityExact", true);
    }

    private void savePlayer(String label, UniverseTransferPoint expected) throws Exception {
        owner.getPlayerManager().saveAllPlayerData();
        Path formal = owner.getSavePath(WorldSavePath.PLAYERDATA).resolve(player.getUuid() + ".dat");
        var nbt = NbtIo.readCompressed(formal, NbtSizeTracker.of(64L * 1024 * 1024));
        require(nbt.getUuid("UUID").equals(player.getUuid()) && nbt.getString("Dimension").equals(expected.worldKey().getValue().toString()), "正式 player NBT UUID／Dimension 不符");
        var position = nbt.getList("Pos", NbtElement.DOUBLE_TYPE);
        var velocity = nbt.getList("Motion", NbtElement.DOUBLE_TYPE);
        var rotation = nbt.getList("Rotation", NbtElement.FLOAT_TYPE);
        require(position.size() == 3 && velocity.size() == 3 && rotation.size() == 2
                && new Vec3d(position.getDouble(0), position.getDouble(1), position.getDouble(2)).squaredDistanceTo(expected.position()) < 1e-12
                && new Vec3d(velocity.getDouble(0), velocity.getDouble(1), velocity.getDouble(2)).squaredDistanceTo(expected.velocity()) < 1e-12
                && Math.abs(rotation.getFloat(0) - expected.yaw()) < 1e-4 && Math.abs(rotation.getFloat(1) - expected.pitch()) < 1e-4,
                "正式 player NBT pose 不符");
        Files.copy(formal, config.evidence().resolve("player-" + label + ".dat"));
        event("native_player_nbt", Map.of("label", label, "dimension", nbt.getString("Dimension"), "uuid", player.getUuid().toString(),
                "sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(formal))), "nbt", nbt.toString()));
        proof.put("playerNbt_" + label, nbt.getString("Dimension"));
    }

    private void hold(ServerWorld world, ChunkPos chunk) {
        world.getChunkManager().addTicket(TICKET, chunk, 0, chunk);
        tickets.add(new Ticket(world, chunk));
        world.getChunk(chunk.x, chunk.z);
        require(world.getChunkManager().getChunk(chunk.x, chunk.z, ChunkStatus.FULL, false) != null, "fixture ticket 未載入 FULL chunk");
        event("fixture_ticket_added", Map.of("world", world.getRegistryKey().getValue().toString(), "chunk", List.of(chunk.x, chunk.z)));
    }

    private void floor(ServerWorld world, BlockPos feet) {
        for (int dy = -1; dy <= 1; dy++) {
            var pos = feet.add(0, dy, 0);
            floors.add(new Floor(world, pos, world.getBlockState(pos)));
            world.setBlockState(pos, dy == -1 ? Blocks.STONE.getDefaultState() : Blocks.AIR.getDefaultState(), 2);
        }
        event("fixture_floor_written", Map.of("world", world.getRegistryKey().getValue().toString(), "feet", feet.toShortString()));
    }

    private void cleanup() {
        if (fixture != null) {
            UUID id = fixture.player().getUuid();
            fixture.close();
            require(owner.getPlayerManager().getPlayer(id) == null && owner.getOverworld().getEntity(id) == null
                    && destination.getEntity(id) == null, "fixture player 未完整移除");
            fixture = null;
            proof.put("playerRemoved", true);
        }
        for (int index = floors.size() - 1; index >= 0; index--) {
            var floor = floors.get(index);
            floor.world().setBlockState(floor.pos(), floor.original(), 2);
            require(floor.world().getBlockState(floor.pos()).equals(floor.original()), "floor fixture 未還原");
        }
        floors.clear();
        for (var ticket : tickets) ticket.world().getChunkManager().removeTicket(TICKET, ticket.chunk(), 0, ticket.chunk());
        tickets.clear();
        proof.put("floorsRestored", true);
        proof.put("ticketsReleased", true);
        event("fixtures_cleaned", Map.of("floors", floors.size(), "tickets", tickets.size()));
    }

    private void worldLoad(MinecraftServer server, ServerWorld world) {
        if (!world.getRegistryKey().equals(DESTINATION)) return;
        guarded(() -> {
            require(owner == server && server.getWorld(DESTINATION) == world && ++loads == 1, "dynamic LOAD exact identity／次數不符");
            destination = world;
            event("world_load", Map.of("count", loads, "identity", System.identityHashCode(world)));
        });
    }

    private void worldUnload(MinecraftServer server, ServerWorld world) {
        if (!world.getRegistryKey().equals(DESTINATION)) return;
        guarded(() -> {
            require(owner == server && world == destination && server.getWorld(DESTINATION) == world
                    && Boolean.TRUE.equals(proof.get("stoppingSeen")) && ++unloads == 1, "dynamic UNLOAD 不是唯一正常停止 receipt");
            lifecycle.assertWorld(world, 1);
            event("world_unload", Map.of("count", unloads, "identity", System.identityHashCode(world)));
        });
    }

    private void stopping(MinecraftServer server) {
        if (server != owner) return;
        guarded(() -> {
            proof.put("stoppingSeen", true);
            lifecycle.assertAttached(false);
            cleanup();
        });
    }

    private void stopped(MinecraftServer server) {
        if (server != owner) return;
        guarded(() -> {
            lifecycle.assertDetached(destination);
            int expected = config.phase().equals("setup-catalog") ? 0 : 1;
            require(loads == expected && unloads == expected && fixture == null && tickets.isEmpty() && floors.isEmpty(), "STOPPED lifecycle／fixture 清理不符");
            proof.put("lifecycleContextDetached", true);
            proof.put("stoppedSeen", true);
            event("stopped", Map.of("loads", loads, "unloads", unloads, "contextDetached", true));
        });
        proof.put("loads", loads);
        proof.put("unloads", unloads);
        proof.put("status", failures.isEmpty() && assertionsPassed && stopRequested ? "PASS" : "FAIL");
        write(config.evidence().resolve("final.json"), JSON.toJson(proof), false);
        LOGGER.info("M3_TRANSFER phase={} status={} LOAD={} UNLOAD={}", config.phase(), proof.get("status"), loads, unloads);
    }

    private void requestStop() { stopRequested = true; proof.put("stopRequested", true); owner.stop(false); }
    private void guarded(Checked action) {
        try { action.run(); }
        catch (Exception | AssertionError failure) {
            failures.add(failure.toString());
            LOGGER.error("M3 transfer phase 失敗", failure);
            event("failure", Map.of("error", failure.toString()));
            if (owner != null) requestStop();
        }
    }

    private static Map<String, Object> point(UniverseTransferPoint point) {
        return Map.of("worldKey", point.worldKey().getValue().toString(), "position", List.of(point.position().x, point.position().y, point.position().z),
                "velocity", List.of(point.velocity().x, point.velocity().y, point.velocity().z), "yaw", point.yaw(), "pitch", point.pitch());
    }

    private static Map<String, Object> result(UniverseTransferResult result) {
        var output = new LinkedHashMap<String, Object>();
        output.put("outcome", result.outcome().name()); output.put("decision", result.decision().name());
        output.put("source", point(result.source())); output.put("moveNative", result.move().outcome().name());
        output.put("moveActual", result.move().actual().toString()); output.put("rollback", result.rollback().outcome().name());
        output.put("rollbackNative", result.rollback().nativeOutcome().name()); output.put("finalActual", result.actual().toString());
        output.put("detail", result.detail());
        if (result.receipt() != null) output.put("receipt", Map.of("playerId", result.receipt().playerId().toString(),
                "source", point(result.receipt().source()), "destination", point(result.receipt().destination()),
                "sourceInstanceId", result.receipt().sourceInstanceId().toString(), "destinationInstanceId", result.receipt().destinationInstanceId().toString()));
        return output;
    }

    private void event(String kind, Map<String, ?> detail) {
        var value = new LinkedHashMap<String, Object>();
        value.put("timeUtc", Instant.now().toString()); value.put("event", kind); value.put("phase", config.phase());
        value.put("startupNonce", config.startupNonce()); value.putAll(detail);
        write(config.evidence().resolve("events.jsonl"), JSON.toJson(value) + System.lineSeparator(), true);
    }

    private static void write(Path path, String value, boolean append) {
        try { Files.writeString(path, value, StandardOpenOption.CREATE, StandardOpenOption.WRITE, append ? StandardOpenOption.APPEND : StandardOpenOption.TRUNCATE_EXISTING); }
        catch (IOException failure) { throw new IllegalStateException("probe evidence 寫入失敗", failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Checked { void run() throws Exception; }
    private record Ticket(ServerWorld world, ChunkPos chunk) { }
    private record Floor(ServerWorld world, BlockPos pos, BlockState original) { }

    private record Configuration(String phase, String nonce, String startupNonce, Path root, Path evidence) {
        private static Configuration read() {
            String prefix = "quantumchamber.m3transfer.";
            String phase = System.getProperty(prefix + "phase", "");
            String nonce = System.getProperty(prefix + "nonce", "");
            String startup = System.getProperty(prefix + "startupNonce", "");
            String text = System.getProperty(prefix + "root", "");
            if (!Set.of("setup-catalog", "success-roundtrip", "target-not-full", "post-move-authority-loss", "stale-service-receipt").contains(phase)
                    || !nonce.matches("[a-z0-9]+(?:-[a-z0-9]+)*") || nonce.equals("disabled") || !startup.matches("[0-9a-f]{32}") || text.isBlank()) return null;
            var allowed = Set.of(prefix + "phase", prefix + "nonce", prefix + "startupNonce", prefix + "root", prefix + "evidenceOwner");
            if (System.getProperties().stringPropertyNames().stream().anyMatch(key -> key.startsWith(prefix) && !allowed.contains(key))) return null;
            try {
                Path supplied = Path.of(text);
                Path root = supplied.toRealPath();
                if (!supplied.isAbsolute() || !root.equals(supplied.normalize()) || !root.equals(Path.of("").toRealPath())
                        || !root.getFileName().toString().equals("m3-transfer-" + nonce) || !root.getParent().getFileName().toString().equals("run")) return null;
                Path owner = ProbeEvidenceOwner.resolve(root, prefix, OWNER);
                Path evidence = owner.resolve("transfer-final-" + nonce).resolve(phase);
                if (!evidence.toRealPath().equals(evidence) || Files.exists(evidence.resolve("final.json")) || Files.exists(evidence.resolve("events.jsonl"))) return null;
                return new Configuration(phase, nonce, startup, root, evidence);
            } catch (IOException | RuntimeException invalid) { return null; }
        }
    }
}
