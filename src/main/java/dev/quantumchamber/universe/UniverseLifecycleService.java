package dev.quantumchamber.universe;

import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 協調持久 Universe 啟動與原生關閉的唯一 owner。 */
public final class UniverseLifecycleService {
    private static final Logger LOGGER = LoggerFactory.getLogger(UniverseLifecycleService.class);
    private static final Map<MinecraftServer, Context> CONTEXTS =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static boolean initialized;

    private UniverseLifecycleService() { }

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ServerWorldEvents.LOAD.register(UniverseLifecycleService::onLoad);
        ServerWorldEvents.UNLOAD.register(UniverseLifecycleService::onUnload);
        ServerLifecycleEvents.SERVER_STARTED.register(UniverseLifecycleService::onStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(UniverseLifecycleService::onStopping);
        // initializer 必須先註冊既有 queue/protection detach，才註冊此停止後驗收。
        ServerLifecycleEvents.SERVER_STOPPED.register(UniverseLifecycleService::onStopped);
    }

    private static void onStarted(MinecraftServer server) {
        var context = new Context();
        if (CONTEXTS.putIfAbsent(server, context) != null) {
            server.stop(false);
            throw new IllegalStateException("同一 server 的 Universe bootstrap 不得重複執行");
        }
        try {
            var result = bootstrap(new BootstrapAccess<ServerWorld>() {
                @Override public UniverseRegistryState catalog() {
                    // 先驗正式檔；損壞 state 不可進入 vanilla 的 persistent-state save 集合。
                    var disk = UniverseRegistryState.load(server.getSavePath(WorldSavePath.ROOT)
                            .resolve("data").resolve(UniverseRegistryState.STATE_ID + ".dat"));
                    disk.requireHealthy();
                    var catalog = UniverseRegistryState.get(server);
                    if (!disk.records().equals(catalog.flushedRecords())) {
                        throw new IllegalStateException("記憶體 catalog 與 strict disk snapshot 不符");
                    }
                    return catalog;
                }

                @Override public UniverseRegistryState.StorageInventory inspectStorage(Collection<UniverseRecord> records)
                        throws java.io.IOException {
                    return UniverseRegistryState.inspectStorage(server.getSavePath(WorldSavePath.ROOT), records);
                }

                @Override public ServerWorld materialize(UniverseRecord record, UniverseWorldDescriptor descriptor) {
                    if (UniverseRoleResolver.resolve(server, descriptor.worldKey()).orElse(null) != descriptor.role()) {
                        throw new IllegalStateException("descriptor 沒有 exact catalog role");
                    }
                    var result = new UniverseMaterializationService(context.backend)
                            .materializeChecked(server, record, descriptor);
                    if (result.status() != MaterializeResult.Status.MATERIALIZED
                            && result.status() != MaterializeResult.Status.ALREADY_ACTIVE) {
                        throw new IllegalStateException("Universe bootstrap 物化失敗: "
                                + descriptor.worldKey().getValue() + " " + result.status());
                    }
                    return result.world();
                }

                @Override public void unload(UniverseWorldDescriptor descriptor, ServerWorld expected) {
                    var result = context.backend.unload(server, descriptor, expected);
                    if (result != UnloadResult.UNLOADED) {
                        throw new IllegalStateException("Universe bootstrap 反向清理未完成: "
                                + descriptor.worldKey().getValue() + " " + result);
                    }
                }

                @Override public void stop() { server.stop(false); }
            });
            context.ready = result.ready();
            if (result.ready()) {
                LOGGER.info("M3_UNIVERSE_BOOTSTRAP_READY worlds={}", result.retained().size());
            } else {
                LOGGER.error("M3_UNIVERSE_BOOTSTRAP_FAILED retainedReceipts={}；已請求正常停止，保留 catalog/storage",
                        result.retained().size(), result.failure());
            }
        } catch (Error fatal) {
            LOGGER.error("M3_UNIVERSE_BOOTSTRAP_FATAL；已請求正常停止", fatal);
            throw fatal;
        }
    }

    private static void onLoad(MinecraftServer server, ServerWorld world) {
        var context = CONTEXTS.get(server);
        if (context == null) return;
        boolean materializing = context.backend.runtimeSnapshot(server).stream().anyMatch(entry ->
                entry.state() == DynamicWorldRuntimeState.MATERIALIZING
                        && entry.descriptor().worldKey().equals(world.getRegistryKey()));
        if (!materializing) return;
        if (context.unloads.putIfAbsent(world, 0) != null || world.getServer() != server
                || server.getWorld(world.getRegistryKey()) != world) {
            throw new IllegalStateException("Universe LOAD 不是新的 exact native owner");
        }
        LOGGER.info("M3_UNIVERSE_LOAD key={}", world.getRegistryKey().getValue());
    }

    private static void onUnload(MinecraftServer server, ServerWorld world) {
        var context = CONTEXTS.get(server);
        if (context == null || !context.unloads.containsKey(world)) return;
        int count = context.unloads.compute(world, (ignored, previous) -> previous + 1);
        // 不在 observer 拋錯阻斷原生 close；重複 receipt 由 STOPPED 拒絕 detach。
        LOGGER.info("M3_UNIVERSE_UNLOAD key={} count={}", world.getRegistryKey().getValue(), count);
    }

    private static void onStopping(MinecraftServer server) {
        var context = CONTEXTS.get(server);
        if (context == null) return;
        context.ready = false;
        Throwable failure = cleanupQuarantines(() -> context.backend.quarantinedWorlds(server), world -> {
            var owner = context.backend.runtimeSnapshot(server).stream()
                    .filter(entry -> entry.world() == world).findFirst().orElseThrow();
            var result = context.backend.cleanupQuarantined(server, owner.descriptor(), world);
            if (result != UnloadResult.UNLOADED) {
                throw new IllegalStateException("Universe quarantine 清理未完成: " + result);
            }
        });
        if (failure != null) {
            context.stoppingFailure = context.stoppingFailure == null
                    ? failure : combine(context.stoppingFailure, failure);
            try {
                LOGGER.error("M3_UNIVERSE_QUARANTINE_RETAINED；不碰 foreign owner，維持正常停止", failure);
            } catch (Exception | Error logFailure) {
                context.stoppingFailure = combine(context.stoppingFailure, logFailure);
            }
            // 已在 STOPPING；連 Error 都記入 receipt，不能中斷後面的 vanilla save/close。
        }
        // 健康 active world 留在 native map；vanilla/Fabric 各自 save、UNLOAD、close 一次。
    }

    private static void onStopped(MinecraftServer server) {
        var context = CONTEXTS.get(server);
        if (context == null) return;
        var snapshot = context.backend.runtimeSnapshot(server);
        try {
            verifyStopped(snapshot, context.backend.quarantinedWorlds(server), context.unloads,
                    descriptor -> server.getWorld(descriptor.worldKey()), world -> world.getServer() == server,
                    context.stoppingFailure);
        } catch (RuntimeException failure) {
            LOGGER.error("M3_UNIVERSE_STOPPED_UNVERIFIED；保留 context，不以 bookkeeping 取代 cleanup", failure);
            throw failure;
        }
        if (!CONTEXTS.remove(server, context)) {
            throw new IllegalStateException("Universe 停止後的 exact context owner 已改變");
        }
        // SERVER_STOPPED 已晚於 native close；丟棄此 server 專用 backend，不偽造 early-unload receipt。
        LOGGER.info("M3_UNIVERSE_STOPPED_VERIFIED worlds={} runtimeReceipts={} quarantine=0 contextDetached=true",
                context.unloads.size(), snapshot.size());
    }

    static <W> void verifyStopped(List<UniverseRuntimeRegistry.OwnedWorld<W>> runtime, List<W> quarantined,
            Map<W, Integer> unloads, Function<UniverseWorldDescriptor, W> nativeWorld,
            Predicate<W> ownsServer, Throwable stoppingFailure) {
        if (stoppingFailure != null) {
            throw new IllegalStateException("STOPPING 已記錄清理失敗，禁止以空 snapshot 冒稱成功 detach", stoppingFailure);
        }
        if (!quarantined.isEmpty()) throw new IllegalStateException("停止後仍有 quarantine 強參照");
        for (var count : unloads.values()) {
            if (count != 1) throw new IllegalStateException("每個自有 world 必須恰有一次 UNLOAD: " + count);
        }
        for (var owner : runtime) {
            if (owner.state() == DynamicWorldRuntimeState.MATERIALIZING
                    || owner.state() == DynamicWorldRuntimeState.UNLOADING) {
                throw new IllegalStateException("停止後仍有進行中的 Universe 操作");
            }
            var world = owner.world();
            if (world == null) {
                if (owner.state() != DynamicWorldRuntimeState.MATERIALIZE_FAILED) {
                    throw new IllegalStateException("runtime owner 缺少 exact world");
                }
                continue;
            }
            if (!ownsServer.test(world) || nativeWorld.apply(owner.descriptor()) != world
                    || unloads.getOrDefault(world, 0) != 1) {
                throw new IllegalStateException("失敗／active owner 沒有 exact native shutdown receipt");
            }
        }
    }

    static <W> Throwable cleanupQuarantines(Supplier<List<W>> inventory, Consumer<W> cleanup) {
        Throwable failure = null;
        try {
            for (var world : inventory.get()) {
                try {
                    cleanup.accept(world);
                } catch (Exception | Error cleanupFailure) {
                    failure = failure == null ? cleanupFailure : combine(failure, cleanupFailure);
                }
            }
        } catch (Exception | Error inventoryFailure) {
            failure = failure == null ? inventoryFailure : combine(failure, inventoryFailure);
        }
        return failure;
    }

    static <W> BootstrapResult<W> bootstrap(BootstrapAccess<W> access) {
        var retained = new ArrayList<OwnedWorld<W>>();
        Throwable terminal;
        try {
            var catalog = access.catalog();
            catalog.requireHealthy();
            var records = catalog.records();
            if (!records.equals(catalog.flushedRecords())) {
                throw new IllegalStateException("Universe 啟動不得使用尚未 durable 的 catalog");
            }
            if (access.inspectStorage(records.values()) != UniverseRegistryState.StorageInventory.READY) {
                throw new IllegalStateException("Universe storage 存在 orphan，拒絕物化且保留原始資料");
            }
            var ordered = records.values().stream().sorted(Comparator
                    .comparingLong((UniverseRecord record) -> record.definition().allocationOrdinal())
                    .thenComparing(record -> record.definition().universeId().value().toString())).toList();
            for (var record : ordered) {
                if (record.desiredAvailability() != DesiredAvailability.EAGER_ENABLED) continue;
                var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
                var world = Objects.requireNonNull(access.materialize(record, descriptor), "物化沒有返回 exact world");
                retained.add(new OwnedWorld<>(descriptor, world));
            }
            return new BootstrapResult<>(retained, null);
        } catch (Exception | Error failure) {
            terminal = failure;
        }
        for (int index = retained.size() - 1; index >= 0; index--) {
            var owned = retained.get(index);
            try {
                access.unload(owned.descriptor(), owned.world());
                retained.remove(index);
            } catch (Exception | Error cleanupFailure) {
                terminal = combine(terminal, cleanupFailure);
            }
        }
        try {
            access.stop();
        } catch (Exception | Error stopFailure) {
            terminal = combine(terminal, stopFailure);
        }
        if (terminal instanceof Error fatal) throw fatal;
        return new BootstrapResult<>(retained, terminal);
    }

    private static Throwable combine(Throwable primary, Throwable secondary) {
        if (primary == secondary) return primary;
        if (!(primary instanceof Error) && secondary instanceof Error) {
            secondary.addSuppressed(primary);
            return secondary;
        }
        primary.addSuppressed(secondary);
        return primary;
    }

    interface BootstrapAccess<W> {
        UniverseRegistryState catalog();
        UniverseRegistryState.StorageInventory inspectStorage(Collection<UniverseRecord> records) throws Exception;
        W materialize(UniverseRecord record, UniverseWorldDescriptor descriptor) throws Exception;
        void unload(UniverseWorldDescriptor descriptor, W expected) throws Exception;
        void stop();
    }

    record OwnedWorld<W>(UniverseWorldDescriptor descriptor, W world) { }

    record BootstrapResult<W>(List<OwnedWorld<W>> retained, Throwable failure) {
        BootstrapResult { retained = List.copyOf(retained); }
        boolean ready() { return failure == null; }
    }

    private static final class Context {
        final Minecraft121DynamicDimensionBackend backend = new Minecraft121DynamicDimensionBackend();
        final Map<ServerWorld, Integer> unloads = new IdentityHashMap<>();
        boolean ready;
        Throwable stoppingFailure;
    }
}
