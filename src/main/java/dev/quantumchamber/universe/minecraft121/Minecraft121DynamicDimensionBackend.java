package dev.quantumchamber.universe.minecraft121;

import dev.quantumchamber.chamber.ChamberControllerLoadSyncQueue;
import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.DynamicDimensionBackend;
import dev.quantumchamber.universe.DynamicWorldRuntimeState;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.SeedPolicy;
import dev.quantumchamber.universe.UniverseRuntimeRegistry;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import dev.quantumchamber.universe.UnloadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 由 lifecycle owner 在 server thread 的安全邊界呼叫；不讀取 catalog。 */
public final class Minecraft121DynamicDimensionBackend implements DynamicDimensionBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger(Minecraft121DynamicDimensionBackend.class);
    private final UniverseRuntimeRegistry<MinecraftServer, ServerWorld> runtime = new UniverseRuntimeRegistry<>();
    private final FailureOwnership<MinecraftServer, RegistryKey<World>, ServerWorld> failures = new FailureOwnership<>();
    private final Map<ServerWorld, BorderBinding> borders = new IdentityHashMap<>();
    private final Minecraft121ServerWorldFactory factory = new Minecraft121ServerWorldFactory();

    @Override
    public MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        requireThread(server);
        Objects.requireNonNull(descriptor, "descriptor");
        if (failures.isUnhealthy(server)) {
            return result(MaterializeResult.Status.FAILED_UNHEALTHY);
        }
        if (failures.isBusy(server)) return result(MaterializeResult.Status.REJECTED);
        var access = (MinecraftServerDynamicWorldAccess) server;
        var worlds = access.quantumchamber$getWorlds();
        Path storage;
        try {
            requireVersion(FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString());
            requireDescriptor(descriptor);
            var active = resolveActive(server, descriptor);
            if (active.isPresent()) {
                return new MaterializeResult(MaterializeResult.Status.ALREADY_ACTIVE, active.get());
            }
            if (runtime.state(server, descriptor) != DynamicWorldRuntimeState.ABSENT) {
                throw new IllegalStateException("此 key 已有 runtime owner 或失敗紀錄");
            }
            requireMapIdentity(worlds.get(descriptor.worldKey()), null);
            storage = requireStorage(access.quantumchamber$getSession().getWorldDirectory(World.OVERWORLD),
                    access.quantumchamber$getSession().getWorldDirectory(descriptor.worldKey()), descriptor);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("拒絕物化 {}：guard 未通過", descriptor.worldKey().getValue(), failure);
            return result(MaterializeResult.Status.REJECTED);
        } catch (Error fatal) {
            return result(finishFailure(fatal, null, cause -> failUnhealthy(server, descriptor, cause)));
        }

        try {
            runtime.beginMaterialize(server, descriptor);
        } catch (Error fatal) {
            return result(finishFailure(fatal, null, cause -> failUnhealthy(server, descriptor, cause)));
        }
        ServerWorld created = null;
        boolean published = false;
        boolean loadStarted = false;
        try {
            created = factory.create(server, descriptor);
            var sourceBorder = server.getOverworld().getWorldBorder();
            var listener = new WorldBorderListener.WorldBorderSyncer(created.getWorldBorder());
            borders.put(created, new BorderBinding(sourceBorder, listener));
            created.getWorldBorder().load(sourceBorder.write());
            sourceBorder.addListener(listener);
            requireMapIdentity(worlds.putIfAbsent(descriptor.worldKey(), created), null);
            published = true;
            requireWorldIdentity(server, descriptor, created);
            Path actualStorage = requireStorage(
                    access.quantumchamber$getSession().getWorldDirectory(World.OVERWORLD),
                    access.quantumchamber$getSession().getWorldDirectory(created.getRegistryKey()), descriptor);
            if (!storage.equals(actualStorage)) {
                throw new IllegalStateException("物化後的 storage identity 改變");
            }
            loadStarted = true;
            ServerWorldEvents.LOAD.invoker().onWorldLoad(server, created);
            requireWorldIdentity(server, descriptor, created);
            var success = new MaterializeResult(MaterializeResult.Status.MATERIALIZED, created);
            runtime.activate(server, descriptor, created);
            return success;
        } catch (Exception failure) {
            return completeFailure(server, descriptor, worlds, created, published, loadStarted, failure);
        } catch (Error fatal) {
            return completeFailure(server, descriptor, worlds, created, published, loadStarted, fatal);
        }
    }

    private MaterializeResult completeFailure(MinecraftServer server, UniverseWorldDescriptor descriptor,
            Map<RegistryKey<World>, ServerWorld> worlds, ServerWorld created, boolean published,
            boolean loadStarted, Throwable failure) {
        FailureAction recovery = loadStarted
                ? () -> failures.retainAfterLoadFailure(server, descriptor.worldKey(), created, worlds)
                : created != null ? () -> rollbackUnpublished(worlds, descriptor, created, published) : null;
        if (failure instanceof Error fatal) {
            // 已有致命 Error 時，receipt 的次級失敗不得遮蔽原始 Error 或阻止正常 stop。
            try {
                runtime.fail(server, descriptor, null);
                if (loadStarted && created != null) runtime.retainFailedOwner(server, descriptor, created);
            } catch (Throwable receiptFailure) {
                suppress(fatal, receiptFailure);
            }
            return result(finishFailure(fatal, recovery, cause -> failUnhealthy(server, descriptor, cause)));
        }
        try {
            runtime.fail(server, descriptor, null);
            if (loadStarted && created != null) runtime.retainFailedOwner(server, descriptor, created);
            if (loadStarted) {
                recovery.run();
            }
        } catch (Error fatal) {
            suppress(fatal, failure);
            return completeFailure(server, descriptor, worlds, created, published, loadStarted, fatal);
        } catch (Exception receiptFailure) {
            suppress(failure, receiptFailure);
            return failUnhealthy(server, descriptor, failure);
        }
        return result(finishFailure(failure, loadStarted ? null : recovery,
                cause -> failUnhealthy(server, descriptor, cause)));
    }

    @Override
    public UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld expectedWorld) {
        requireThread(server);
        if (!isOwnedIdentity(server, descriptor, expectedWorld)) return UnloadResult.REJECTED_BUSY;
        return unloadOwned(server, descriptor, expectedWorld,
                ((MinecraftServerDynamicWorldAccess) server).quantumchamber$getWorlds(), runtime, failures,
                nativeUnloadAccess(server), cause -> failUnhealthy(server, descriptor, cause), false);
    }

    /** 不註冊 lifecycle；由 owner 在安全 server-thread 邊界清理 exact quarantine world。 */
    public UnloadResult cleanupQuarantined(MinecraftServer server, UniverseWorldDescriptor descriptor,
            ServerWorld expectedWorld) {
        requireThread(server);
        if (!isOwnedIdentity(server, descriptor, expectedWorld)) return UnloadResult.REJECTED_BUSY;
        return unloadOwned(server, descriptor, expectedWorld,
                ((MinecraftServerDynamicWorldAccess) server).quantumchamber$getWorlds(), runtime, failures,
                nativeUnloadAccess(server), cause -> failUnhealthy(server, descriptor, cause), true);
    }

    public List<UniverseRuntimeRegistry.OwnedWorld<ServerWorld>> runtimeSnapshot(MinecraftServer server) {
        requireThread(server);
        return runtime.snapshot(server);
    }

    public boolean hasRuntimeOwners(MinecraftServer server) { return !runtimeSnapshot(server).isEmpty(); }

    public List<ServerWorld> quarantinedWorlds(MinecraftServer server) {
        requireThread(server);
        return failures.quarantined(server);
    }

    private static boolean isOwnedIdentity(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld world) {
        if (descriptor == null || world == null || world.getServer() != server
                || !world.getRegistryKey().equals(descriptor.worldKey())) return false;
        try {
            requireDescriptor(descriptor);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private UnloadAccess<ServerWorld> nativeUnloadAccess(MinecraftServer server) {
        return new UnloadAccess<>() {
            @Override public boolean playersEmpty(ServerWorld world) { return world.getPlayers().isEmpty(); }
            @Override public boolean forcedChunksEmpty(ServerWorld world) { return world.getForcedChunks().isEmpty(); }
            @Override public boolean releaseResources(ServerWorld world) {
                requireThread(server);
                // 此 backend 不建立 chunk ticket；呼叫者必須先撤回自己的 fixture／transfer ticket。
                var binding = borders.get(world);
                if (binding == null) return false;
                binding.source().removeListener(binding.listener());
                return borders.remove(world, binding);
            }
            @Override public boolean executeQueuedTasks(ServerWorld world) {
                requireThread(server);
                return world.getChunkManager().executeQueuedTasks();
            }
            @Override public boolean flushBlocking(ServerWorld world) {
                requireThread(server);
                requireVersion(FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                        .getMetadata().getVersion().getFriendlyString());
                // Pinned 1.21：chunk save(true) 的 completeAll().join()，接著 entity flush 的
                // awaitAll(true)／join() 與 entity taskExecutor.awaitAll() 都在此呼叫返回前完成。
                world.save(null, true, false);
                return true;
            }
            @Override public void dispatchUnload(ServerWorld world) {
                ServerWorldEvents.UNLOAD.invoker().onWorldUnload(server, world);
            }
            @Override public void discardWorld(ServerWorld world) { ChamberControllerLoadSyncQueue.discardWorld(world); }
            @Override public void close(ServerWorld world) throws IOException { world.close(); }
        };
    }

    @Override
    public Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        requireThread(server);
        Objects.requireNonNull(descriptor, "descriptor");
        if (failures.isUnhealthy(server) || failures.isBusy(server)) {
            return Optional.empty();
        }
        return runtime.resolveActive(server, descriptor).filter(world -> world.getServer() == server
                && world.getRegistryKey().equals(descriptor.worldKey())
                && server.getWorld(descriptor.worldKey()) == world);
    }

    private static void requireThread(MinecraftServer server) {
        if (!Objects.requireNonNull(server, "server").isOnThread()) {
            throw new IllegalStateException("動態 world backend 只能在 owning server thread 操作");
        }
    }

    private static void requireWorldIdentity(MinecraftServer server, UniverseWorldDescriptor descriptor,
            ServerWorld world) {
        requireMapIdentity(server.getWorld(descriptor.worldKey()), world);
        requireMapIdentity(world.getServer(), server);
        if (!world.getRegistryKey().equals(descriptor.worldKey())) {
            throw new IllegalStateException("world key 與 descriptor 不符");
        }
    }

    private void rollbackUnpublished(Map<RegistryKey<World>, ServerWorld> worlds,
            UniverseWorldDescriptor descriptor, ServerWorld created, boolean published) throws IOException {
        if (published) {
            requireMapIdentity(worlds.get(descriptor.worldKey()), created);
            if (!worlds.remove(descriptor.worldKey(), created)) {
                throw new IllegalStateException("無法 expected-remove 未公開的 world");
            }
        }
        var binding = borders.remove(created);
        try {
            if (binding != null) {
                binding.source().removeListener(binding.listener());
            }
        } finally {
            created.close();
        }
    }

    private MaterializeResult failUnhealthy(MinecraftServer server, UniverseWorldDescriptor descriptor,
            Throwable failure) {
        failures.markUnhealthy(server);
        try {
            LOGGER.error("動態 world backend 已不健康，{} quarantine={}；請求正常停止，quarantine 尚未證明 save/close",
                    descriptor.worldKey().getValue(), failures.quarantined(server).size(), failure);
        } finally {
            server.stop(false);
        }
        return result(MaterializeResult.Status.FAILED_UNHEALTHY);
    }

    private static MaterializeResult result(MaterializeResult.Status status) {
        return new MaterializeResult(status, null);
    }

    static MaterializeResult.Status finishFailure(Throwable failure, FailureAction rollback,
            Consumer<Throwable> stopUnhealthy) {
        Throwable terminal = failure;
        boolean rolledBack = false;
        if (rollback != null) {
            try {
                rollback.run();
                rolledBack = true;
            } catch (Exception rollbackFailure) {
                suppress(failure, rollbackFailure);
            } catch (Error rollbackFatal) {
                if (failure instanceof Error) {
                    suppress(failure, rollbackFatal);
                } else {
                    suppress(rollbackFatal, failure);
                    terminal = rollbackFatal;
                }
            }
        }
        // 所有 Error（含 AssertionError）都代表未證明可恢復的 VM／連結／不變量失敗。
        if (terminal instanceof Error fatal) {
            try {
                stopUnhealthy.accept(fatal);
            } catch (Throwable stopFailure) {
                suppress(fatal, stopFailure);
            }
            throw fatal;
        }
        if (rolledBack) {
            LOGGER.error("物化失敗，已撤回尚未發出 LOAD 的 world", failure);
            return MaterializeResult.Status.FAILED_ROLLED_BACK;
        }
        stopUnhealthy.accept(terminal);
        return MaterializeResult.Status.FAILED_UNHEALTHY;
    }

    private static void suppress(Throwable primary, Throwable secondary) {
        if (primary != secondary) {
            primary.addSuppressed(secondary);
        }
    }

    @FunctionalInterface
    interface FailureAction {
        void run() throws Exception;
    }

    interface UnloadAccess<W> {
        boolean playersEmpty(W world);
        boolean forcedChunksEmpty(W world);
        boolean releaseResources(W world) throws Exception;
        boolean executeQueuedTasks(W world);
        boolean flushBlocking(W world) throws Exception;
        void dispatchUnload(W world);
        void discardWorld(W world);
        void close(W world) throws Exception;
    }

    static <S, W> UnloadResult unloadOwned(S server, UniverseWorldDescriptor descriptor, W expected,
            Map<RegistryKey<World>, W> worlds, UniverseRuntimeRegistry<S, W> runtime,
            FailureOwnership<S, RegistryKey<World>, W> failures, UnloadAccess<W> access,
            Consumer<Throwable> stopUnhealthy, boolean quarantine) {
        if (!quarantine && failures.isUnhealthy(server)) return UnloadResult.FAILED_UNHEALTHY;
        if (expected == null || failures.isBusy(server)) return UnloadResult.REJECTED_BUSY;
        boolean owns = quarantine
                ? failures.quarantined(server).stream().anyMatch(world -> world == expected)
                    && runtime.snapshot(server).stream().anyMatch(entry -> entry.world() == expected
                        && entry.descriptor().equals(descriptor)
                        && entry.state() == DynamicWorldRuntimeState.MATERIALIZE_FAILED)
                : runtime.resolveActive(server, descriptor).orElse(null) == expected;
        if (!owns || worlds.get(descriptor.worldKey()) != (quarantine ? null : expected)) {
            return UnloadResult.REJECTED_BUSY;
        }
        failures.beginOperation(server);
        boolean quiesced = false;
        boolean unloadStarted = false;
        boolean ownerReleased = false;
        Throwable pendingFailure = null;
        try {
            try {
                if (quarantine) runtime.beginFailedUnload(server, descriptor, expected);
                else runtime.beginUnload(server, descriptor, expected);
                if (!quiesce(expected, access)) {
                    throw new IllegalStateException("無法取得 blocking final-flush 與 post-flush 無工作 receipt");
                }
                quiesced = true;
                requireMapIdentity(worlds.get(descriptor.worldKey()), quarantine ? null : expected);
                // Invocation 開始即不可回退；observer 拋錯也不能略過收尾或重新 dispatch。
                unloadStarted = true;
                access.dispatchUnload(expected);
            } catch (Exception | Error failure) {
                pendingFailure = failure;
            }
            if (unloadStarted) {
                try {
                    access.discardWorld(expected);
                    requireMapIdentity(worlds.get(descriptor.worldKey()), quarantine ? null : expected);
                    if (!quarantine && !worlds.remove(descriptor.worldKey(), expected)) {
                        throw new IllegalStateException("exact world remove 未成功");
                    }
                    access.close(expected);
                    requireMapIdentity(worlds.get(descriptor.worldKey()), null);
                    runtime.release(server, descriptor, expected);
                    ownerReleased = true;
                    if (quarantine) failures.releaseQuarantine(server, expected);
                } catch (Exception | Error cleanupFailure) {
                    pendingFailure = combineUnloadFailures(pendingFailure, cleanupFailure);
                }
            }
            if (pendingFailure == null) return UnloadResult.UNLOADED;
            recordUnloadFailure(server, descriptor, expected, runtime, failures, pendingFailure,
                    stopUnhealthy, ownerReleased);
            if (pendingFailure instanceof Error fatal) throw fatal;
            return quiesced ? UnloadResult.FAILED_UNHEALTHY : UnloadResult.UNLOAD_UNSUPPORTED;
        } finally {
            failures.endOperation(server);
        }
    }

    private static Throwable combineUnloadFailures(Throwable original, Throwable cleanupFailure) {
        if (original == null || original == cleanupFailure) return cleanupFailure;
        // 原始 Error 保持 exact identity；普通 observer exception 遇 cleanup Error 則保留為 suppressed。
        if (original instanceof Error || !(cleanupFailure instanceof Error)) {
            suppress(original, cleanupFailure);
            return original;
        }
        suppress(cleanupFailure, original);
        return cleanupFailure;
    }

    private static <W> boolean quiesce(W expected, UnloadAccess<W> access) throws Exception {
        if (!access.playersEmpty(expected) || !access.forcedChunksEmpty(expected)
                || !access.releaseResources(expected)) return false;
        long started = System.nanoTime();
        for (int count = 0; count < 4096 && System.nanoTime() - started < 5_000_000_000L; count++) {
            if (!access.executeQueuedTasks(expected)) {
                return access.flushBlocking(expected) && !access.executeQueuedTasks(expected)
                        && access.playersEmpty(expected) && access.forcedChunksEmpty(expected);
            }
        }
        return false;
    }

    private static <S, W> void recordUnloadFailure(S server, UniverseWorldDescriptor descriptor, W expected,
            UniverseRuntimeRegistry<S, W> runtime, FailureOwnership<S, RegistryKey<World>, W> failures,
            Throwable failure, Consumer<Throwable> stopUnhealthy, boolean ownerReleased) {
        // 收尾已 release 的 owner 不再建立虛假的 UNLOAD_FAILED；server 仍必須 unhealthy／stop。
        if (!ownerReleased) {
            try {
                runtime.fail(server, descriptor, expected);
            } catch (Throwable receiptFailure) {
                suppress(failure, receiptFailure);
            }
        }
        failures.markUnhealthy(server);
        try {
            stopUnhealthy.accept(failure);
        } catch (Throwable stopFailure) {
            suppress(failure, stopFailure);
        }
    }

    static final class FailureOwnership<S, K, W> {
        private final Set<S> unhealthy = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<S> busy = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<S, Set<W>> quarantine = new IdentityHashMap<>();

        void retainAfterLoadFailure(S server, K key, W created, Map<K, W> worlds) {
            markUnhealthy(server);
            W current = worlds.putIfAbsent(key, created);
            if (current != null && current != created) {
                // R6：不取代陌生 owner；只保留強參照，Task 7 quiesce 前不宣稱已 save/close。
                quarantine.computeIfAbsent(server, ignored -> Collections.newSetFromMap(new IdentityHashMap<>()))
                        .add(created);
            }
        }

        void markUnhealthy(S server) { unhealthy.add(server); }

        boolean isUnhealthy(S server) { return unhealthy.contains(server); }

        boolean isBusy(S server) { return busy.contains(server); }

        void beginOperation(S server) {
            if (!busy.add(server)) throw new IllegalStateException("此 server 已有 backend unload 操作");
        }

        void endOperation(S server) { busy.remove(server); }

        void releaseQuarantine(S server, W expected) {
            var worlds = quarantine.get(server);
            if (worlds == null || !worlds.remove(expected)) throw new IllegalStateException("exact quarantine owner 已改變");
            if (worlds.isEmpty()) quarantine.remove(server);
        }

        List<W> quarantined(S server) {
            return List.copyOf(quarantine.getOrDefault(server, Set.of()));
        }
    }

    static void requireVersion(String version) {
        if (!"1.21".equals(version)) {
            throw new IllegalArgumentException("動態 world backend 僅支援 Minecraft 1.21: " + version);
        }
    }

    static void requireDescriptor(UniverseWorldDescriptor descriptor) {
        var id = descriptor.worldKey().getValue();
        String[] parts = id.getPath().split("/", -1);
        if (!id.getNamespace().equals("quantumchamber") || parts.length != 3
                || !parts[0].equals("universe") || !parts[2].equals("overworld")
                || !UUID.fromString(parts[1]).toString().equals(parts[1])
                || descriptor.role() != DimensionRole.OVERWORLD
                || descriptor.generatorProfile() != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1
                || descriptor.seedPolicy() != SeedPolicy.SHARED_SAVE_SEED_V1
                || descriptor.storagePolicyVersion() != 1) {
            throw new IllegalArgumentException("descriptor 不符合 schema 1 的 owned Overworld profile");
        }
    }

    static void requireMapIdentity(Object current, Object expected) {
        if (current != expected) {
            throw new IllegalStateException("live map 不是 expected exact world instance");
        }
    }

    static Path requireStorage(Path root, Path directory, UniverseWorldDescriptor descriptor) throws IOException {
        requireDescriptor(descriptor);
        Path canonicalRoot = root.toRealPath();
        Path expected = canonicalRoot.resolve("dimensions/quantumchamber")
                .resolve(descriptor.worldKey().getValue().getPath());
        Path absolute = directory.toAbsolutePath().normalize();
        Path existing = absolute;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("無法解析 world storage 的既有祖先");
            }
        }
        Path canonical = existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        if (!canonical.startsWith(canonicalRoot.resolve("dimensions/quantumchamber/universe"))
                || !canonical.equals(expected)) {
            throw new IllegalArgumentException("world storage 不在 exact owned Universe 目錄: " + canonical);
        }
        return canonical;
    }

    private record BorderBinding(WorldBorder source, WorldBorderListener listener) { }
}
