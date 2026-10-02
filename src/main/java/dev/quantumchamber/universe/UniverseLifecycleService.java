package dev.quantumchamber.universe;

import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
                    return materializedWorld(descriptor, result.status(), result.world());
                }

                @Override public void recordMaterializeFailure(MaterializeFailure failure) {
                    context.materializeFailures.add(failure);
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

    /**
     * spec §6.3（M5 F4a）：執行期間物化一筆已配置的 Universe（NEW 的 RESOLVE）。使用 lifecycle Context 內既有的 backend 與 runtime registry，
     * 不另外建立 backend；只能在 server thread 呼叫。只回傳結果，不記塌縮流程的 log（塌縮流程的 log 由 collapse package 帶 marker 記錄）；
     * backend 與 M3 既有的 log 照舊。
     * <ul>
     * <li>前提（不符就不呼叫 backend）：lifecycle 有 ready 的 context（{@link MaterializeOutcome.Status#NOT_READY}）；flushed catalog 有這筆、
     *     current 與 flushed 的這筆相同、availability 為 {@code EAGER_ENABLED}、有 exact catalog role 的 OVERWORLD descriptor
     *     （{@link MaterializeOutcome.Status#NOT_ELIGIBLE}）。</li>
     * <li>已經 ACTIVE：回傳同一個 world instance（{@link MaterializeOutcome.Status#ALREADY_ACTIVE}）。</li>
     * <li>同一個 server instance 內這個 descriptor 物化失敗過：直接拒絕（{@link MaterializeOutcome.Status#FAILED_BEFORE}）。runtime registry
     *     不允許原地重試（{@link UniverseRuntimeRegistry#fail}）；backend 沒有留下 receipt 的失敗（例如 REJECTED）也一樣拒絕。</li>
     * <li>backend 失敗（{@link MaterializeOutcome.Status#FAILED}）：記入本 instance 的失敗集合；會留下 runtime receipt 或未證明安全的失敗
     *     （{@code FAILED_ROLLED_BACK}、{@code FAILED_UNHEALTHY}、例外）同時記入 Context 的 materialize failures，與 bootstrap 一致，
     *     停服驗證據此核對 rollback 收據（否則 {@code FAILED_ROLLED_BACK} 留下的 MATERIALIZE_FAILED owner 會讓正常停服 {@code STOPPED_UNVERIFIED}）。
     *     {@code REJECTED} 在建立任何 runtime owner 之前就拒絕，不記入（記入的話，停服驗證會把它當成沒有安全 close 證據的失敗）。
     *     {@link Error} 記入之後照舊往外丟（M3：Error 代表未證明可恢復的失敗）。</li>
     * </ul>
     */
    public static MaterializeOutcome materializeAllocated(MinecraftServer server, UniverseId id) {
        requireServerThread(server);
        Objects.requireNonNull(id, "id");
        var context = CONTEXTS.get(server);
        if (context == null || !context.ready) return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_READY, "lifecycle 沒有 ready 的 context");
        UniverseRecord record;
        try {
            var catalog = UniverseRegistryState.get(server);
            catalog.requireHealthy();
            record = catalog.flushedRecords().get(id);
            if (record == null) return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_ELIGIBLE, "flushed catalog 沒有這筆");
            if (!record.equals(catalog.records().get(id))) return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_ELIGIBLE, "current 與 flushed 的這筆不同");
        } catch (IllegalStateException unhealthy) {
            return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_ELIGIBLE, "catalog 不健康：" + unhealthy.getMessage());
        }
        if (record.desiredAvailability() != DesiredAvailability.EAGER_ENABLED) {
            return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_ELIGIBLE, "availability 不是 EAGER_ENABLED：" + record.desiredAvailability());
        }
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        if (descriptor == null || UniverseRoleResolver.resolve(server, descriptor.worldKey()).orElse(null) != DimensionRole.OVERWORLD) {
            return MaterializeOutcome.refused(MaterializeOutcome.Status.NOT_ELIGIBLE, "沒有 exact catalog role 的 OVERWORLD descriptor");
        }
        var active = context.backend.resolveActive(server, descriptor);
        if (active.isPresent()) return MaterializeOutcome.succeeded(MaterializeOutcome.Status.ALREADY_ACTIVE, active.get());
        var checked = record;
        return attemptMaterialize(descriptor, context.failedDescriptors, context.materializeFailures,
                () -> new UniverseMaterializationService(context.backend).materializeChecked(server, checked, descriptor));
    }

    /**
     * {@link #materializeAllocated} 在前提成立、world 不是 ACTIVE 之後的部分（F4a fix round 1，M-1：抽成 package-private，讓 JUnit 以
     * {@link #verifyStopped} 驗證停服驗證依賴這裡的失敗紀錄）：同一個 instance 內失敗過的 descriptor 直接 {@code FAILED_BEFORE}（不呼叫
     * {@code materializer}）；否則物化一次，失敗時記入 {@code failedDescriptors}，並依 M3 的停服驗證規則記入 {@code materializeFailures}：
     * 例外與 {@code Error} 記為沒有 status 的失敗（未證明安全，停服驗證會拒絕），{@code FAILED_ROLLED_BACK}、{@code FAILED_UNHEALTHY} 記下
     * status；{@code REJECTED} 不記（它在 backend 建立 runtime owner 之前就被拒絕，沒有 owner 需要 rollback 收據）。{@code Error} 記入之後照舊丟出。
     */
    static MaterializeOutcome attemptMaterialize(UniverseWorldDescriptor descriptor, Set<UniverseWorldDescriptor> failedDescriptors,
            List<MaterializeFailure> materializeFailures, Supplier<MaterializeResult> materializer) {
        if (failedDescriptors.contains(descriptor)) {
            return MaterializeOutcome.refused(MaterializeOutcome.Status.FAILED_BEFORE, "同一個 server instance 內已物化失敗過：" + descriptor.worldKey().getValue());
        }
        MaterializeResult result;
        try {
            result = materializer.get();
        } catch (RuntimeException | Error failure) {
            failedDescriptors.add(descriptor);
            materializeFailures.add(new MaterializeFailure(descriptor, null, failure.toString(), failure));
            if (failure instanceof Error fatal) throw fatal;
            return new MaterializeOutcome(MaterializeOutcome.Status.FAILED, Optional.empty(), Optional.empty(), failure.toString());
        }
        if (result.status() == MaterializeResult.Status.MATERIALIZED || result.status() == MaterializeResult.Status.ALREADY_ACTIVE) {
            return MaterializeOutcome.succeeded(result.status() == MaterializeResult.Status.MATERIALIZED
                    ? MaterializeOutcome.Status.MATERIALIZED : MaterializeOutcome.Status.ALREADY_ACTIVE, result.world());
        }
        failedDescriptors.add(descriptor);
        var detail = "runtime 物化失敗: " + descriptor.worldKey().getValue() + " " + result.status();
        if (result.status() != MaterializeResult.Status.REJECTED) {
            materializeFailures.add(new MaterializeFailure(descriptor, result.status(), detail, null));
        }
        return new MaterializeOutcome(MaterializeOutcome.Status.FAILED, Optional.empty(), Optional.of(result.status()), detail);
    }

    /**
     * spec §6.3（M5 F4a）：這個 UniverseId 的 exact ACTIVE world（EXISTING 重驗、之後的預載、投影與跨越判定使用）。依 flushed catalog 的
     * OVERWORLD descriptor 向 lifecycle 的 backend 查詢；backend 同時核對 owning server、world key、native live map 與 exact instance。
     * lifecycle 沒有 ready 的 context、catalog 不健康或沒有這筆時為空。只能在 server thread 呼叫；不記 log。
     */
    public static Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseId id) {
        requireServerThread(server);
        Objects.requireNonNull(id, "id");
        var context = CONTEXTS.get(server);
        if (context == null || !context.ready) return Optional.empty();
        UniverseRecord record;
        try {
            var catalog = UniverseRegistryState.get(server);
            catalog.requireHealthy();
            record = catalog.flushedRecords().get(id);
        } catch (IllegalStateException unhealthy) {
            return Optional.empty();
        }
        if (record == null) return Optional.empty();
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        return descriptor == null ? Optional.empty() : context.backend.resolveActive(server, descriptor);
    }

    private static void requireServerThread(MinecraftServer server) {
        if (!Objects.requireNonNull(server, "server").isOnThread()) {
            throw new IllegalStateException("Universe lifecycle 的 runtime API 必須在伺服器執行緒操作");
        }
    }

    /**
     * {@link #materializeAllocated} 的結果。只有 {@link Status#MATERIALIZED} 與 {@link Status#ALREADY_ACTIVE} 帶 exact world；
     * {@code backendStatus} 只在 backend 回報失敗時存在；{@code detail} 是給呼叫端 log 的說明。
     */
    public record MaterializeOutcome(Status status, Optional<ServerWorld> world, Optional<MaterializeResult.Status> backendStatus, String detail) {
        public enum Status {
            /** 本次物化成功。 */
            MATERIALIZED,
            /** 已經 ACTIVE，回傳同一個 world instance。 */
            ALREADY_ACTIVE,
            /** lifecycle 沒有 ready 的 context（bootstrap 失敗、停服中，或沒有 context）；沒有呼叫 backend。 */
            NOT_READY,
            /** record 不符物化的前提；沒有呼叫 backend。 */
            NOT_ELIGIBLE,
            /** 同一個 server instance 內這個 descriptor 物化失敗過；沒有呼叫 backend。 */
            FAILED_BEFORE,
            /** backend 回報失敗（{@code backendStatus}），或丟出例外（{@code backendStatus} 空）。 */
            FAILED
        }

        public MaterializeOutcome {
            Objects.requireNonNull(status, "status"); Objects.requireNonNull(world, "world");
            Objects.requireNonNull(backendStatus, "backendStatus"); Objects.requireNonNull(detail, "detail");
            boolean active = status == Status.MATERIALIZED || status == Status.ALREADY_ACTIVE;
            if (active != world.isPresent()) throw new IllegalArgumentException("只有成功的物化帶 exact world");
            if (backendStatus.isPresent() && status != Status.FAILED) throw new IllegalArgumentException("只有 FAILED 帶 backend status");
        }

        /** 成功：本次物化或已經 ACTIVE。 */
        public boolean active() { return world.isPresent(); }

        private static MaterializeOutcome succeeded(Status status, ServerWorld world) {
            return new MaterializeOutcome(status, Optional.of(world), Optional.empty(), "");
        }

        private static MaterializeOutcome refused(Status status, String detail) {
            return new MaterializeOutcome(status, Optional.empty(), Optional.empty(), detail);
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
            detachAfterVerification(CONTEXTS, server, context, () -> verifyStopped(snapshot, context.backend.quarantinedWorlds(server), context.unloads,
                    descriptor -> server.getWorld(descriptor.worldKey()), world -> world.getServer() == server,
                    context.stoppingFailure, context.materializeFailures));
        } catch (RuntimeException failure) {
            LOGGER.error("M3_UNIVERSE_STOPPED_UNVERIFIED；保留 context，不以 bookkeeping 取代 cleanup", failure);
            throw failure;
        }
        // SERVER_STOPPED 已晚於 native close；丟棄此 server 專用 backend，不偽造 early-unload receipt。
        LOGGER.info("M3_UNIVERSE_STOPPED_VERIFIED worlds={} runtimeReceipts={} quarantine=0 contextDetached=true",
                context.unloads.size(), snapshot.size());
    }

    static <W> void verifyStopped(List<UniverseRuntimeRegistry.OwnedWorld<W>> runtime, List<W> quarantined,
            Map<W, Integer> unloads, Function<UniverseWorldDescriptor, W> nativeWorld,
            Predicate<W> ownsServer, Throwable stoppingFailure, List<MaterializeFailure> materializeFailures) {
        if (stoppingFailure != null) {
            throw new IllegalStateException("STOPPING 已記錄清理失敗，禁止以空 snapshot 冒稱成功 detach", stoppingFailure);
        }
        for (var failure : materializeFailures) {
            if (failure.status() != MaterializeResult.Status.FAILED_ROLLED_BACK) {
                throw new IllegalStateException("bootstrap 物化失敗沒有安全 close 證據: " + failure.detail(), failure.cause());
            }
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
                boolean rolledBack = materializeFailures.stream().anyMatch(failure ->
                        failure.descriptor().equals(owner.descriptor())
                                && failure.status() == MaterializeResult.Status.FAILED_ROLLED_BACK);
                if (owner.state() != DynamicWorldRuntimeState.MATERIALIZE_FAILED || !rolledBack) {
                    throw new IllegalStateException("runtime owner 缺少 exact world 或已證明的 rollback close 收據");
                }
                continue;
            }
            if (!ownsServer.test(world) || nativeWorld.apply(owner.descriptor()) != world
                    || unloads.getOrDefault(world, 0) != 1) {
                throw new IllegalStateException("失敗／active owner 沒有 exact native shutdown receipt");
            }
        }
    }

    static <S, C> void detachAfterVerification(Map<S, C> contexts, S server, C expected, Runnable verification) {
        verification.run();
        if (contexts.get(server) != expected || !contexts.remove(server, expected)) {
            throw new IllegalStateException("Universe 停止後的 exact context owner 已改變");
        }
    }

    static <W> W materializedWorld(UniverseWorldDescriptor descriptor, MaterializeResult.Status status, W world) {
        Objects.requireNonNull(status, "backend materialize status");
        if (status != MaterializeResult.Status.MATERIALIZED && status != MaterializeResult.Status.ALREADY_ACTIVE) {
            if (world != null) throw new IllegalStateException("失敗的 materialize result 不得包含可用 world");
            throw new MaterializeRejected(descriptor, status);
        }
        return Objects.requireNonNull(world, "成功的 materialize result 必須包含 exact world");
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
                try {
                    var world = Objects.requireNonNull(access.materialize(record, descriptor), "物化沒有返回 exact world");
                    retained.add(new OwnedWorld<>(descriptor, world));
                } catch (Exception | Error failure) {
                    // 只信任正式 result 的 typed status；未知例外不能推測成已完成 rollback。
                    var status = failure instanceof MaterializeRejected rejected ? rejected.status : null;
                    access.recordMaterializeFailure(new MaterializeFailure(descriptor, status, failure.toString(), failure));
                    throw failure;
                }
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
        void recordMaterializeFailure(MaterializeFailure failure);
        void unload(UniverseWorldDescriptor descriptor, W expected) throws Exception;
        void stop();
    }

    record OwnedWorld<W>(UniverseWorldDescriptor descriptor, W world) { }

    record MaterializeFailure(UniverseWorldDescriptor descriptor, MaterializeResult.Status status,
                              String detail, Throwable cause) { }

    private static final class MaterializeRejected extends IllegalStateException {
        final MaterializeResult.Status status;

        MaterializeRejected(UniverseWorldDescriptor descriptor, MaterializeResult.Status status) {
            super("Universe bootstrap 物化失敗: " + descriptor.worldKey().getValue() + " " + status);
            this.status = status;
        }
    }

    record BootstrapResult<W>(List<OwnedWorld<W>> retained, Throwable failure) {
        BootstrapResult { retained = List.copyOf(retained); }
        boolean ready() { return failure == null; }
    }

    private static final class Context {
        final Minecraft121DynamicDimensionBackend backend = new Minecraft121DynamicDimensionBackend();
        final Map<ServerWorld, Integer> unloads = new IdentityHashMap<>();
        final List<MaterializeFailure> materializeFailures = new ArrayList<>();
        /** {@link #materializeAllocated} 在本 server instance 物化失敗過的 descriptor（含 REJECTED）：同一個 instance 內直接拒絕。 */
        final Set<UniverseWorldDescriptor> failedDescriptors = new HashSet<>();
        boolean ready;
        Throwable stoppingFailure;
    }
}
