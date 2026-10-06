package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberRegistry;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * spec §9.3 的投影保護索引（F4b-1）：只在記憶體的 {@code worldKey → UniverseId → 不變量 cell}，供 {@code ChamberProtectionService.mayMutate}（保護）與
 * {@code ChamberPowerCoordinator}（載入辨識）查詢。每個 server instance 一份。
 * <ul>
 * <li><b>建立</b>：開服時（{@code SERVER_STARTED}；{@code QuantumSuperpositionMod} 把 {@link #registerEvents()} 註冊在
 *     {@link CollapseAuthorities#registerEvents()} 之後、{@code UniverseLifecycleService.initialize()} 之前，所以早於 bootstrap 物化任何動態世界；plan
 *     決策 2）由 {@link CollapseAuthorities#confirmedProjections()}（已確認的 projection entries）與 catalog 的 {@code UniverseId → worldKey} 建立。catalog
 *     沒有這筆時用 M3 的推導 {@link UniverseKeys#world}（catalog 的 descriptor 必定等於它，{@code UniverseDefinition} 的不變量）。</li>
 * <li><b>兩層</b>：已確認的 PENDING 與 COMPLETE entry 的 Controller 位置都用於載入辨識（{@link #projectionController}）；只有 COMPLETE 的不變量 cell
 *     （shell、Controller、門，即 7×7×7 的邊界）受保護（{@link #protects}），interior 不保護。</li>
 * <li><b>更新入口</b>（F4b-2 呼叫 {@link #confirmed}，傳入 checked 寫入的結果，F4b-2 Step 0 Q-m4）：PENDING 確認之後、寫方塊之前登記一次，投影寫入
 *     Controller 時 {@code onBlockAdded} 排入的 refresh 與 load-sync 的 refresh 因此辨識為 PROJECTION（plan Step 1 第 8 組）；COMPLETE 確認之後再登記一次，
 *     這時才轉為受保護（spec §9.3「每次 COMPLETE 時更新」）。開服時的 PENDING entry 也入索引，同樣只用於辨識。Status 只會前進（PENDING→COMPLETE），不倒退。</li>
 * <li><b>熱路徑</b>：{@link #protects} 與 {@link #projectionController} 每次 {@code setBlockState}、每次 Controller 刷新都可能經過，只讀 volatile 的不可變快照，
 *     不讀 PersistentState、不取鎖。健康時任何執行緒都可以呼叫；退化之後 {@link #protects} 另讀 {@link ChamberRegistry}（不是執行緒安全的集合），只在
 *     server thread 安全（F4b-2 Step 0 Q-m2）。更新只在 server thread，以 copy-on-write 發布新的快照。</li>
 * <li><b>退化</b>（§9.3）：只依權威健康切換——{@link CollapseAuthorities#unhealthy}（初始化結果、執行期間的遺失）；單筆不符或 ledger 已滿（單次呼叫的 CORRUPT）
 *     不會退化。退化時另外保護「所有目前登記中的 OVERWORLD Origin 艙，在每個動態 Overworld 同位置的不變量 cell」（{@link ChamberRegistry}）。開服時就退化
 *     （初始化失敗）不採用任何 ledger 內容；執行期間才遺失時，之前已在索引中的投影照樣受保護（較保守的一方）。一旦退化，本 instance 不再解除；下次開服若讀取
 *     成功，新的 instance 自動解除。健康在每個 {@code END_SERVER_TICK} 同步一次，也在 {@link #degradation} 查詢時同步。</li>
 * <li>Origin 拆除之後 entry 保留：索引不看 chamber registry（Dead Chamber 照樣受保護）。不改 {@code ChamberRegistryState}，投影不進 chamber registry 或
 *     {@code ProjectionIndex}。</li>
 * </ul>
 * 這裡的 log 記的是索引本身（不屬於任何 session），一律帶 {@link CollapseService#TICK_LOG_MARKER}：建立時一則 INFO；退化時一則 WARN（不附 stack）；建立或
 * 同步時發生非預期的例外時記 ERROR（附 stack，只記這一次），並以退化規則 fail closed。
 */
public final class ProjectionProtectionIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private static final Map<MinecraftServer, ProjectionProtectionIndex> INSTANCES = new ConcurrentHashMap<>();
    private static boolean registered;

    /** catalog 的 {@code (UniverseId, role) → worldKey}；沒有這筆時為空（呼叫端改用 M3 的推導）。只在 server thread 呼叫。 */
    private final BiFunction<UniverseId, DimensionRole, Optional<RegistryKey<World>>> catalogWorld;
    /** 退化時的 Origin 來源；chamber registry 讀不到時為 null（那時 {@code ChamberProtectionService} 已讓開服失敗）。 */
    private final @Nullable ChamberRegistry registry;
    /** projection ledger 的權威健康（{@link CollapseAuthorities#unhealthy}）。只在 server thread 呼叫。 */
    private final Supplier<Optional<SealedReason>> health;
    /** 目前是否在擁有者的 server thread（production 是 {@code server::isOnThread}）：{@link #confirmed} 與 {@link #intersecting} 的守門（F4b-2 Step 0 Q-m4）。 */
    private final BooleanSupplier onServerThread;
    private final Map<RegistryKey<World>, Boolean> dynamicOverworlds = new ConcurrentHashMap<>();
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile @Nullable SealedReason degraded;

    /**
     * package-private：JUnit 以自己的權威健康、catalog、registry 與執行緒判斷建立；之後以 {@link #load} 載入開服時的 entries。
     * @param onServerThread 目前是否在擁有者的 server thread；不在時 {@link #confirmed} 與 {@link #intersecting} 丟 {@link IllegalStateException}
     */
    ProjectionProtectionIndex(Supplier<Optional<SealedReason>> health, BiFunction<UniverseId, DimensionRole, Optional<RegistryKey<World>>> catalogWorld,
            @Nullable ChamberRegistry registry, BooleanSupplier onServerThread) {
        this.health = Objects.requireNonNull(health, "health");
        this.catalogWorld = Objects.requireNonNull(catalogWorld, "catalogWorld");
        this.registry = registry;
        this.onServerThread = Objects.requireNonNull(onServerThread, "onServerThread");
    }

    /**
     * 註冊開服建立、每 tick 同步健康與停服釋放。{@code QuantumSuperpositionMod} 在 {@code CollapseAuthorities.registerEvents()}（與
     * {@code CollapseService.initialize()}）之後、{@code UniverseLifecycleService.initialize()} 之前呼叫（plan 決策 2）：Fabric 依註冊順序執行，所以建立時
     * M5 authority 已初始化、還沒有任何動態世界被物化，每個 {@code END_SERVER_TICK} 的同步晚於塌縮流程的 tick（同一個 tick 內的遺失當場生效）。
     * 同時把 {@link #protectedCell} 與 {@link #projectionControllerAt} 註冊成 {@code ChamberCollapsePort} 的投影查詢（F4b-3 Step 0，F4b-1 quality Q-m1）：
     * {@code ChamberProtectionService} 與 {@code ChamberPowerCoordinator} 經它查詢，chamber package 不 import collapse。
     */
    public static synchronized void registerEvents() {
        if (registered) return;
        registered = true;
        ChamberCollapsePort.installProjections(new ChamberCollapsePort.Projections() {
            @Override public boolean protectedCell(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) {
                return ProjectionProtectionIndex.protectedCell(server, worldKey, pos);
            }

            @Override public boolean projectionControllerAt(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) {
                return ProjectionProtectionIndex.projectionControllerAt(server, worldKey, pos);
            }
        });
        ServerLifecycleEvents.SERVER_STARTED.register(ProjectionProtectionIndex::onStarted);
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var index = INSTANCES.get(server);
            if (index != null) index.syncHealth();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
    }

    private static void onStarted(MinecraftServer server) {
        ChamberRegistry registry = null;
        try {
            registry = ChamberRegistryState.get(server).registry();
        } catch (RuntimeException unreadable) {
            // chamber registry 讀不到時，ChamberProtectionService 的 SERVER_STARTED 已讓開服失敗；這裡退化時就沒有 Origin 可保護。
        }
        CollapseAuthorities authorities = null;
        try {
            authorities = CollapseAuthorities.get(server);
        } catch (RuntimeException missing) {
            var index = new ProjectionProtectionIndex(() -> Optional.of(SealedReason.AUTHORITY_UNREADABLE), (id, role) -> Optional.empty(), registry,
                    server::isOnThread);
            index.failClosed("沒有此 server 的 M5 authority", missing);
            INSTANCES.put(server, index);
            return;
        }
        var source = authorities;
        var index = new ProjectionProtectionIndex(() -> source.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER), catalogWorld(server), registry,
                server::isOnThread);
        index.load(source::confirmedProjections);
        INSTANCES.put(server, index);
    }

    /** production 的 catalog 查詢：flushed catalog 的這筆 record 在這個 role 的 descriptor；catalog 不健康或沒有這筆時為空。 */
    private static BiFunction<UniverseId, DimensionRole, Optional<RegistryKey<World>>> catalogWorld(MinecraftServer server) {
        return (id, role) -> {
            Map<UniverseId, UniverseRecord> flushed;
            try {
                flushed = UniverseRegistryState.get(server).flushedRecords();
            } catch (RuntimeException unhealthy) {
                return Optional.empty();
            }
            return Optional.ofNullable(flushed.get(id)).map(record -> record.definition().worlds().get(role)).map(descriptor -> descriptor.worldKey());
        };
    }

    /**
     * 本 server instance 的索引（F4b-2：{@link #confirmed}、{@link #degradation}、{@link #intersecting}）。instance 在 {@link #registerEvents()} 註冊的
     * {@code SERVER_STARTED} listener 裡才建立，在 {@code SERVER_STOPPED} 釋放；這段期間以外丟 {@link IllegalStateException}。
     */
    public static ProjectionProtectionIndex get(MinecraftServer server) {
        var index = INSTANCES.get(Objects.requireNonNull(server, "server"));
        if (index == null) throw new IllegalStateException("沒有此 server 的投影保護索引：instance 在 SERVER_STARTED 建立、SERVER_STOPPED 釋放");
        return index;
    }

    /**
     * 熱路徑（{@code ChamberProtectionService.mayMutate} 遇到非 vanilla 的 world key 時）：這一格是否受投影保護（{@link #protects}）。沒有這個 server 的索引時
     * 為 false：開服的 {@code SERVER_STARTED} 之前還沒有動態世界（bootstrap 在索引之後），停服之後不再有寫入。健康時任何執行緒都可以呼叫；退化之後會讀
     * {@link ChamberRegistry}，只在 server thread 安全（F4b-2 Step 0 Q-m2）。
     */
    public static boolean protectedCell(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) {
        var index = INSTANCES.get(server);
        return index != null && index.protects(worldKey, pos);
    }

    /** 熱路徑（{@code ChamberPowerCoordinator} 的載入辨識）：這個位置是否是已確認投影的 Controller（{@link #projectionController}）。沒有索引時為 false。 */
    public static boolean projectionControllerAt(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) {
        var index = INSTANCES.get(server);
        return index != null && index.projectionController(worldKey, pos);
    }

    /**
     * 開服時載入：權威健康時，以 {@code entries}（已確認的 projection entries）建立快照；不健康時退化、不採用任何 ledger 內容。非預期的例外以退化 fail closed。
     * 每個 instance 只呼叫一次（server thread）。
     */
    void load(Supplier<List<ProjectionLedger.Entry>> entries) {
        try {
            var unhealthy = health.get();
            if (unhealthy.isPresent()) {
                degrade(unhealthy.get());
            } else {
                var indexed = new ArrayList<Indexed>();
                int unmapped = 0;
                for (var entry : entries.get()) {
                    var world = worldKey(entry);
                    if (world.isEmpty()) { unmapped++; continue; }
                    indexed.add(Indexed.of(entry, world.get()));
                }
                var next = Snapshot.of(indexed);
                snapshot = next;
                LOGGER.info(CollapseService.TICK_LOG_MARKER, "M5 投影保護索引已建立：COMPLETE {} 筆（受保護）、PENDING {} 筆（只辨識），{} 個世界；無法對應世界而略過 {} 筆",
                        next.count(ProjectionLedger.Entry.Status.COMPLETE), next.count(ProjectionLedger.Entry.Status.PENDING), next.worlds().size(), unmapped);
            }
        } catch (RuntimeException unexpected) {
            failClosed("建立時發生非預期的例外", unexpected);
        }
    }

    /**
     * 更新入口（F4b-2）：登記一筆 checked 寫入已確認的 projection entry。傳入的是 {@link CollapseAuthorities#writeProjection} 的結果，不是寫入的輸入值
     * （F4b-2 Step 0 Q-m4）：只有 {@code WRITTEN}、{@code UNCHANGED}、{@code ALREADY_COMPLETE} 帶著已確認的 entry；其他結果（未確認、寫入失敗、損壞、讀不到）
     * 沒有 entry，登記它們會讓未確認的投影提早被辨識、甚至受保護，所以丟 {@link IllegalArgumentException}，不改變索引。PENDING 在 checked 寫入確認之後、
     * 寫方塊之前呼叫（寫入時 Controller 的 refresh 辨識為 PROJECTION）；COMPLETE 在 checked 寫入確認之後呼叫，這時這個投影的不變量 cell 才受保護。同一個
     * (chamber, UniverseId) 的 Status 只會前進，已是 COMPLETE 時再登記 PENDING（例如 {@code ALREADY_COMPLETE} 帶回的 entry）維持 COMPLETE。
     * @throws IllegalArgumentException 結果不是上述三種成功狀態；同一個 key 的 site（Role、ControllerPos、OutwardFacing）與已登記的不同；或 entry 對應不到任何
     *     世界（本切片只有 OVERWORLD）——ledger 已保證後兩者，違反都是 caller 錯誤
     * @throws IllegalStateException 不在 server thread
     */
    public void confirmed(CollapseAuthorities.WriteResult result) {
        Objects.requireNonNull(result, "result");
        requireServerThread("confirmed");
        var entry = switch (result.status()) {
            case WRITTEN, UNCHANGED, ALREADY_COMPLETE -> result.entry().orElseThrow();
            case UNCONFIRMED, WRITE_FAILED, CORRUPT, UNREADABLE -> throw new IllegalArgumentException(
                    "只有已確認的 projection 寫入結果（WRITTEN／UNCHANGED／ALREADY_COMPLETE）可以登記：" + result.status());
        };
        var world = worldKey(entry).orElseThrow(() -> new IllegalArgumentException("projection entry 對應不到世界（本切片只有 OVERWORLD）：" + entry));
        var previous = snapshot.byKey().get(new Key(entry.chamberUuid(), entry.universeId()));
        if (previous != null && !previous.entry().sameSite(entry)) {
            throw new IllegalArgumentException("同一個投影的 Role／ControllerPos／OutwardFacing 與已登記的不同：" + previous.entry() + " → " + entry);
        }
        if (previous != null && previous.entry().status() == ProjectionLedger.Entry.Status.COMPLETE) return;
        snapshot = snapshot.with(entry, world);
    }

    /**
     * 目前是否退化（F4b-2：退化時新的 PREPARE 進 SEALED）。先依權威健康同步，退化時回 SEALED 的原因（{@code AUTHORITY_CORRUPT} 或
     * {@code AUTHORITY_UNREADABLE}），否則為空。只在 server thread 呼叫。
     */
    public Optional<SealedReason> degradation() {
        syncHealth();
        return Optional.ofNullable(degraded);
    }

    /**
     * 與 {@code volume} 相交的已登記投影（PENDING 與 COMPLETE，依登記順序），供 F4b-2 判斷 §9.2 的「衝突」（另一座艙的 COMPLETE 投影）。只在 server thread 呼叫
     * （否則丟 {@link IllegalStateException}，F4b-2 Step 0 Q-m4）。
     */
    public List<ProjectionLedger.Entry> intersecting(RegistryKey<World> worldKey, BlockBox volume) {
        Objects.requireNonNull(worldKey, "worldKey"); Objects.requireNonNull(volume, "volume");
        requireServerThread("intersecting");
        var view = snapshot.worlds().get(worldKey);
        if (view == null) return List.of();
        return view.all().stream().filter(indexed -> indexed.bounds().intersects(volume)).map(Indexed::entry).toList();
    }

    /**
     * 熱路徑：這一格是否受投影保護。COMPLETE 投影的不變量 cell（7×7×7 的邊界）；退化時另加「登記中的 OVERWORLD Origin 艙在動態 Overworld 同位置的不變量 cell」。
     * interior 不保護。只讀記憶體。
     */
    boolean protects(RegistryKey<World> worldKey, BlockPos pos) {
        var view = snapshot.worlds().get(worldKey);
        if (view != null) {
            var candidates = view.byChunk().get(ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4));
            if (candidates != null) {
                for (var indexed : candidates) {
                    if (indexed.entry().status() == ProjectionLedger.Entry.Status.COMPLETE && onShell(indexed.bounds(), pos)) return true;
                }
            }
        }
        var origins = registry;
        if (degraded == null || origins == null || !dynamicOverworld(worldKey)) return false;
        return origins.findAt(DimensionRole.OVERWORLD, pos)
                .filter(record -> record.originDimensionRole() == DimensionRole.OVERWORLD
                        && onShell(ChamberGeometry.bounds(new ChamberFrame(record.anchorPos(), record.facing())), pos))
                .isPresent();
    }

    /** 熱路徑：這個位置是否是已確認投影（PENDING 或 COMPLETE）的 Controller。只讀記憶體。 */
    boolean projectionController(RegistryKey<World> worldKey, BlockPos pos) {
        var view = snapshot.worlds().get(worldKey);
        return view != null && view.controllers().contains(pos);
    }

    /** 依權威健康同步退化旗標（不會解除）。例外時以退化 fail closed；不丟出例外（在 {@code END_SERVER_TICK} 執行）。 */
    void syncHealth() {
        if (degraded != null) return;
        try {
            var unhealthy = health.get();
            if (unhealthy.isPresent()) degrade(unhealthy.get());
        } catch (RuntimeException unexpected) {
            failClosed("同步權威健康時發生非預期的例外", unexpected);
        }
    }

    /**
     * 退化（只一次）：記一則 WARN，不附 stack（F4b-2 Step 0 Q-m3）——權威不健康的原因已由 {@code CollapseAuthorities} 的 ERROR 記下，非預期的例外已由
     * {@link #failClosed} 的 ERROR 記下，不重複印同一份 stack。字句描述 F4b-2 接上之後的實際行為：塌縮流程每個 PREPARE 的 tick 都先查 {@link #degradation}。
     */
    private void degrade(SealedReason reason) {
        if (degraded != null) return;
        degraded = reason;
        LOGGER.warn(CollapseService.TICK_LOG_MARKER, "M5 投影保護索引退化（{}）：projection ledger 不健康，保護另加「登記中的 OVERWORLD Origin 艙在每個動態 Overworld 同位置的"
                + "不變量 cell」，已在索引中的投影照樣受保護；還沒完成準備的 PREPARE（進行中的，以及之後開始的）一律進 SEALED({})，已完成準備的不受影響；"
                + "下次開服若讀取成功自動解除", reason, reason);
    }

    /** 非預期的例外：記 ERROR（附 stack，只記這一次），以退化規則 fail closed（原因記為 {@code AUTHORITY_UNREADABLE}，重啟之後重建）。 */
    private void failClosed(String what, RuntimeException failure) {
        LOGGER.error(CollapseService.TICK_LOG_MARKER, "M5 投影保護索引{}，改以退化規則保護：{}", what, failure.toString(), failure);
        degrade(SealedReason.AUTHORITY_UNREADABLE);
    }

    private void requireServerThread(String what) {
        if (!onServerThread.getAsBoolean()) throw new IllegalStateException("投影保護索引的 " + what + " 只能在 server thread 呼叫");
    }

    /** entry 的世界：catalog 的 descriptor；沒有時用 M3 的推導（只有 OVERWORLD）；都沒有時為空。 */
    private Optional<RegistryKey<World>> worldKey(ProjectionLedger.Entry entry) {
        var fromCatalog = catalogWorld.apply(entry.universeId(), entry.role());
        if (fromCatalog.isPresent()) return fromCatalog;
        return entry.role() == DimensionRole.OVERWORLD ? Optional.of(UniverseKeys.world(entry.universeId(), entry.role())) : Optional.empty();
    }

    /** 動態 Overworld（{@code quantumchamber:universe/<uuid>/overworld}，M3 的 {@link UniverseKeys#world}）；退化時才查，結果依 key 快取。 */
    private boolean dynamicOverworld(RegistryKey<World> worldKey) {
        return dynamicOverworlds.computeIfAbsent(worldKey, ProjectionProtectionIndex::isDynamicOverworld);
    }

    static boolean isDynamicOverworld(RegistryKey<World> worldKey) {
        var path = worldKey.getValue().getPath();
        if (!path.startsWith("universe/") || !path.endsWith("/overworld")) return false;
        try {
            var uuid = UUID.fromString(path.substring("universe/".length(), path.length() - "/overworld".length()));
            // 推導的 key 必須 exact 相等（namespace 也在其中）。
            return UniverseKeys.world(UniverseId.of(uuid), DimensionRole.OVERWORLD).equals(worldKey);
        } catch (IllegalArgumentException notUuid) {
            return false;
        }
    }

    /** {@code pos} 在 7×7×7 的邊界上（shell、Controller、門）。 */
    static boolean onShell(BlockBox bounds, BlockPos pos) {
        int x = pos.getX(), y = pos.getY(), z = pos.getZ();
        if (x < bounds.getMinX() || x > bounds.getMaxX() || y < bounds.getMinY() || y > bounds.getMaxY() || z < bounds.getMinZ() || z > bounds.getMaxZ()) return false;
        return x == bounds.getMinX() || x == bounds.getMaxX() || y == bounds.getMinY() || y == bounds.getMaxY() || z == bounds.getMinZ() || z == bounds.getMaxZ();
    }

    private record Key(UUID chamber, UniverseId universe) { }

    private record Indexed(ProjectionLedger.Entry entry, RegistryKey<World> world, BlockBox bounds) {
        static Indexed of(ProjectionLedger.Entry entry, RegistryKey<World> world) {
            return new Indexed(entry, world, ChamberGeometry.bounds(new ChamberFrame(entry.controllerPos(), entry.outwardFacing())));
        }

        Key key() { return new Key(entry.chamberUuid(), entry.universeId()); }
    }

    /** 一個世界的索引：依 chunk 找投影（含 PENDING）、Controller 位置（PENDING 與 COMPLETE），以及依登記順序的全部投影。不可變。 */
    private record WorldView(Long2ObjectOpenHashMap<List<Indexed>> byChunk, Set<BlockPos> controllers, List<Indexed> all) {
        static WorldView of(List<Indexed> all) {
            var byChunk = new Long2ObjectOpenHashMap<List<Indexed>>();
            var controllers = new HashSet<BlockPos>();
            for (var indexed : all) {
                controllers.add(indexed.entry().controllerPos());
                var bounds = indexed.bounds();
                for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++)
                    for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++)
                        byChunk.computeIfAbsent(ChunkPos.toLong(x, z), ignored -> new ArrayList<>()).add(indexed);
            }
            byChunk.replaceAll((chunk, list) -> List.copyOf(list));
            return new WorldView(byChunk, Set.copyOf(controllers), List.copyOf(all));
        }
    }

    /** 整份索引的不可變快照：依 key 的投影（登記順序）與依世界的檢視。更新時建立新的快照（copy-on-write）。 */
    private record Snapshot(Map<Key, Indexed> byKey, Map<RegistryKey<World>, WorldView> worlds) {
        static final Snapshot EMPTY = of(List.of());

        /** 一次建立（開服時）：每個世界的檢視各建一次。 */
        static Snapshot of(List<Indexed> all) {
            var keys = new LinkedHashMap<Key, Indexed>();
            for (var indexed : all) keys.put(indexed.key(), indexed);
            var byWorld = new LinkedHashMap<RegistryKey<World>, List<Indexed>>();
            for (var indexed : keys.values()) byWorld.computeIfAbsent(indexed.world(), ignored -> new ArrayList<>()).add(indexed);
            var views = new HashMap<RegistryKey<World>, WorldView>();
            byWorld.forEach((world, inWorld) -> views.put(world, WorldView.of(inWorld)));
            return new Snapshot(Collections.unmodifiableMap(keys), Map.copyOf(views));
        }

        /** 加入或前進一筆 entry（同一個 key 原位取代），回傳新的快照；只重建那個世界的檢視。 */
        Snapshot with(ProjectionLedger.Entry entry, RegistryKey<World> world) {
            var indexed = Indexed.of(entry, world);
            var keys = new LinkedHashMap<>(byKey);
            keys.put(indexed.key(), indexed);
            var inWorld = keys.values().stream().filter(candidate -> candidate.world().equals(world)).toList();
            var views = new HashMap<>(worlds);
            views.put(world, WorldView.of(inWorld));
            return new Snapshot(Collections.unmodifiableMap(keys), Map.copyOf(views));
        }

        long count(ProjectionLedger.Entry.Status status) {
            return byKey.values().stream().filter(indexed -> indexed.entry().status() == status).count();
        }
    }
}
