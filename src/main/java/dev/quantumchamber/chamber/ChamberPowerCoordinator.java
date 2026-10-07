package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 將實際電位、原艙保護及 session 返還序列化。 */
public final class ChamberPowerCoordinator {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private static final ThreadLocal<Deferred> DEFERRED = new ThreadLocal<>();
    private static final ThreadLocal<Set<RefreshTarget>> REFRESHING = ThreadLocal.withInitial(LinkedHashSet::new);
    /**
     * R2 M5（M5 spec §10.7 :634）：目前的 ARMED 是上一次 {@link #reconcile} 由 {@code start} 回 ARMED_ONLY／STARTED 留下的原艙（presence 一直是 NONE；M1.2 的 adapter 與 test
     * 的 fake gateway）。每次協調先取出，只有再由 start 留下 ARMED（或依記號保持）時才重新記下，所以 presence 一旦不是 NONE、或結果不是這種 ARMED 就不再保持。不持久化：重啟之後
     * Controller 存下的 ARMED 一律重新評估。production 的 session gateway 的 {@code start} 只回 STAGING／REJECTED，不會記下任何一座艙。
     */
    private static final Set<UUID> ARMED_BY_START = ConcurrentHashMap.newKeySet();
    private final ChamberActivationService activation = new ChamberActivationService();

    public void refresh(ServerWorld world, BlockPos controllerPos) {
        Objects.requireNonNull(world, "world");
        if (!world.getServer().isOnThread()) throw new IllegalStateException("供電協調必須在 server thread 執行");
        Deferred deferred = DEFERRED.get();
        if (deferred != null) {
            deferred.pending.add(new RefreshTarget(world, controllerPos.toImmutable()));
            return;
        }
        var target = new RefreshTarget(world, controllerPos.toImmutable());
        var refreshing = REFRESHING.get();
        if (!refreshing.add(target)) return;
        try {
            refreshNow(world, controllerPos);
        } finally {
            refreshing.remove(target);
            if (refreshing.isEmpty()) REFRESHING.remove();
        }
    }

    private void refreshNow(ServerWorld world, BlockPos controllerPos) {
        if (world.getServer().getWorld(world.getRegistryKey()) != world) return;
        ChamberProtectionService.get().requireReconciliation(world, controllerPos);
        var chunk = world.getChunkManager().getWorldChunk(controllerPos.getX() >> 4, controllerPos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(controllerPos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld() != world || !controller.getPos().equals(controllerPos)) return;
        var block = chunk.getBlockState(controllerPos);
        if (!block.isOf(ModBlocks.CHAMBER_CONTROLLER) || !controller.getCachedState().equals(block)) return;
        if (!ChamberRedstoneService.powerNeighborhoodLoaded(world, controllerPos)) return;
        boolean powered = world.isReceivingRedstonePower(controllerPos);
        controller.setWasPowered(powered);
        controller.setPowerInitialized(true);
        controller.markDirty();
        var role = DimensionRole.fromVanillaKey(world.getRegistryKey());
        if (role.isEmpty() && ChamberCollapsePort.projections().projectionControllerAt(world.getServer(), world.getRegistryKey(), controllerPos)) {
            // M5 spec §9.3：動態世界的 Controller 依投影保護索引判定（經 ChamberCollapsePort，F4b-3 Step 0），不看 block entity 的 instanceKind。在索引裡 → inert 的 PROJECTION：
            // 比較器 0（INVALID）、不 registerOrigin、不啟動、不記 log。instanceKind 寫回 PROJECTION 只是把判定結果記在 block entity 上
            // （與 M1 走廊投影的 Controller 一致；ORIGIN 專屬的發光、維護手勢與 session 守門因此一併不適用），不是判定依據。
            if (controller.instanceKind() != ChamberInstanceKind.PROJECTION) {
                controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
                controller.markDirty();
            }
            changeState(world, controller, ChamberState.INVALID);
            return;
        }
        if (role.isEmpty() || controller.instanceKind() != ChamberInstanceKind.ORIGIN) {
            changeState(world, controller, ChamberState.INVALID);
            return;
        }
        var state = ChamberRegistryState.get(world.getServer());
        state.requireHealthy();
        var registry = state.registry();
        var frame = new ChamberFrame(controllerPos, block.get(ChamberControllerBlock.FACING));
        var existing = registry.findOrigin(world.getRegistryKey().getValue(), role.get(), frame).orElse(null);
        if ((controller.chamberUuid() != null && (existing == null || !controller.chamberUuid().equals(existing.chamberUuid())))
                || (existing != null && (controller.chamberUuid() == null || existing.destroyed()
                || existing.instanceKind() != ChamberInstanceKind.ORIGIN))) {
            changeState(world, controller, ChamberState.INVALID);
            return;
        }
        // 停電返還不依賴 shell 完整；只有新註冊及資格掃描需要整個 bounds 已載入。
        boolean loaded = boundsLoaded(world, frame);
        if (existing == null) {
            if (!powered || !loaded || !new ChamberDetector().validate(new WorldChamberBlockView(world), frame).valid()) {
                changeState(world, controller, ChamberState.INVALID);
                return;
            }
            var registration = registry.registerOrigin(world.getRegistryKey().getValue(), role.get(), frame);
            if (registration.status() != ChamberRegistrationResult.Status.CREATED) {
                changeState(world, controller, ChamberState.INVALID);
                return;
            }
            controller.setChamberUuid(registration.chamberUuid());
            controller.markDirty();
            registry.setPowerState(registration.chamberUuid(), ChamberPowerState.POWERED);
        }
        var gateway = ChamberSessions.gateway();
        ChamberState next = reconcile(registry, controller.chamberUuid(), powered, controller.chamberState(),
                () -> loaded ? activation.evaluateReadiness(world, controller)
                        : new ArmAttemptResult(false, ChamberState.INVALID, List.of(), Set.of(ArmAttemptResult.Failure.INVALID_STRUCTURE)),
                new SessionActions() {
                    @Override public boolean activationBlocked() { return controller.activationBlocked(); }
                    @Override public ChamberSessionGateway.Presence presence() {
                        return gateway.presence(world.getServer(), controller.chamberUuid());
                    }
                    @Override public ChamberSessionGateway.StartResult start(List<UUID> participants) {
                        return gateway.start(world, controller, participants);
                    }
                    @Override public boolean returnToOrigin() { return gateway.returnToOrigin(world, controller); }
                });
        changeState(world, controller, next);
        if (!controller.isRemoved() && controller.getWorld() == world
                && chunk.getBlockEntity(controllerPos) == controller && chunk.getBlockState(controllerPos).equals(block)
                && controller.getCachedState().equals(block)
                && registry.findOrigin(world.getRegistryKey().getValue(), role.get(), frame)
                        .filter(record -> record.chamberUuid().equals(controller.chamberUuid())).isPresent()) {
            ChamberProtectionService.get().completeReconciliation(registry, controller.chamberUuid());
            if (loaded) normalizeOwnerlessMembrane(world, frame, controller.chamberUuid(), gateway);
        }
    }

    /**
     * M5 spec §10.6 :613（F5-2）：找不到所屬 session、卻帶著量子膜的原艙門（例如終局之後、改門之前 crash），在協調完這座原艙之後交給塌縮流程，排入下一個 tick 正規化為一般開門
     * （{@link ChamberCollapsePort.Membranes}；chamber 不 import collapse）。只在 ORIGIN 分支、艙體的 chunk 都已載入（{@code loaded}）時讀門；門的 25 格有任何一格帶著量子膜，而且
     * presence 不是 M5 的塌縮狀態（{@link #normalizesOwnerless}）時才交出。MEASURED（含 DORMANT）的艙也包含在內（presence 是 ACTIVE 或 UNKNOWN）。有 COLLAPSING、SEALED、
     * PASSAGE_READY 的艙由該 session 的門邏輯負責，這裡不交出，塌縮流程套用時也再排除一次。排入、套用與 log 都在塌縮流程；這裡不寫門、不丟出例外（刷新在 Controller 的排程刻與
     * neighbor update 裡）：presence 讀不到時（{@link #reconcile} 已以同一個原因記 WARN）略過，下一次刷新再判斷。
     */
    private static void normalizeOwnerlessMembrane(ServerWorld world, ChamberFrame frame, UUID chamber, ChamberSessionGateway gateway) {
        if (!doorHasMembrane(world, frame)) return;
        ChamberSessionGateway.Presence presence;
        try {
            presence = Objects.requireNonNull(gateway.presence(world.getServer(), chamber), "presence");
        } catch (RuntimeException failure) {
            LOGGER.debug("艙體 {} 的量子膜正規化略過：讀不到 presence，下一次刷新再判斷", chamber, failure);
            return;
        }
        if (!normalizesOwnerless(presence, true)) return;
        try {
            ChamberCollapsePort.membranes().normalizeOwnerless(world, frame, chamber);
        } catch (RuntimeException failure) {
            LOGGER.warn("艙體 {} 的量子膜正規化沒有排入（塌縮流程丟出例外），下一次刷新再試", chamber, failure);
        }
    }

    /**
     * M5 spec §10.6 :613 的條件（純函式，F5-2）：門帶著量子膜，而且 presence 不是 {@code COLLAPSE}／{@code PASSAGE}（COLLAPSING、SEALED、PASSAGE_READY 的 session 自己擁有門）
     * 時，才交給塌縮流程正規化。
     */
    static boolean normalizesOwnerless(ChamberSessionGateway.Presence presence, boolean doorHasMembrane) {
        Objects.requireNonNull(presence, "presence");
        return doorHasMembrane && presence != ChamberSessionGateway.Presence.COLLAPSE && presence != ChamberSessionGateway.Presence.PASSAGE;
    }

    /** 原艙門的 25 格（區域 x、y 1..5，z 0）有任何一格是帶著量子膜的 bulkhead；呼叫前艙體的 chunk 必須已載入（只查詢、不載入）。 */
    private static boolean doorHasMembrane(ServerWorld world, ChamberFrame frame) {
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            var state = world.getBlockState(ChamberGeometry.localToWorld(frame, x, y, 0));
            if (state.isOf(ModBlocks.QUANTUM_BULKHEAD) && QuantumBulkheadBlock.membrane(state).isMembrane()) return true;
        }
        return false;
    }

    /**
     * 一座原艙的協調（純函式，除了 {@link #ARMED_BY_START}）。M5 的塌縮狀態（spec §10.7）：presence 為 {@code COLLAPSE} 或 {@code PASSAGE} 時不走 pending／needsReturn、不呼叫
     * {@code returnToOrigin}；power state 只設 POWERED（已供電而且 enabled）或 RETURNING（不設 OFF，保護維持）；比較器是 {@code PASSAGE}＋已供電＋enabled 時 {@link ChamberState#PASSAGE}
     * （15），其餘 {@link ChamberState#ARMED}（11）。
     * <p>R2 M5（spec §10.7 :634）：presence 為 NONE、目前是 ARMED 時，只有這座艙上一次協調由 {@code start} 回 ARMED_ONLY／STARTED 留下的 ARMED（M1.2 的 adapter：沒有 session，
     * 持續高電位不重複啟動）才保持。其他 ARMED（活動 session、M5 的 COLLAPSE、返還未完成或協調失敗時的保護）在 presence 回到 NONE 之後都已過時——例如 M5 的終局不經這裡的返還
     * 路徑——比照明確返還成功之後重新評估資格，不再卡在 11。
     */
    static ChamberState reconcile(ChamberRegistry registry, UUID uuid, boolean powered, ChamberState current,
                                  Supplier<ArmAttemptResult> readiness, SessionActions sessions) {
        // R2 M5：先取出上一次的記號，這一次只有再由 start 回 ARMED_ONLY／STARTED（或依記號保持）時才重新記下。
        boolean armedByStart = ARMED_BY_START.remove(uuid);
        var record = registry.records().get(uuid);
        if (record == null || record.destroyed() || record.instanceKind() != ChamberInstanceKind.ORIGIN) return ChamberState.INVALID;
        try {
            var presence = Objects.requireNonNull(sessions.presence(), "presence");
            if (presence == ChamberSessionGateway.Presence.COLLAPSE || presence == ChamberSessionGateway.Presence.PASSAGE) {
                // M5 塌縮狀態（spec §10.7）：不走 pending／needsReturn、不呼叫 returnToOrigin（斷電等即時訊號由塌縮流程處理）。
                boolean poweredAndEnabled = powered && record.enabled();
                registry.setPowerState(uuid, poweredAndEnabled ? ChamberPowerState.POWERED : ChamberPowerState.RETURNING);
                return presence == ChamberSessionGateway.Presence.PASSAGE && poweredAndEnabled ? ChamberState.PASSAGE : ChamberState.ARMED;
            }
            boolean pending = record.powerState() == ChamberPowerState.RETURNING
                    || record.powerState() == ChamberPowerState.UNKNOWN
                    || presence == ChamberSessionGateway.Presence.RETURNING
                    || presence == ChamberSessionGateway.Presence.UNKNOWN;
            if (pending || !powered || !record.enabled()) {
                boolean needsReturn = pending || presence != ChamberSessionGateway.Presence.NONE
                        || record.powerState() != ChamberPowerState.OFF && (!powered || current == ChamberState.ARMED);
                if (needsReturn) {
                    registry.setPowerState(uuid, ChamberPowerState.RETURNING);
                    if (!sessions.returnToOrigin()) return ChamberState.ARMED;
                    // 明確返還成功後才允許解除保護；復電也要重新評估資格。
                    current = ChamberState.INVALID;
                    presence = Objects.requireNonNull(sessions.presence(), "presence");
                    if (presence != ChamberSessionGateway.Presence.NONE) {
                        registry.setPowerState(uuid, ChamberPowerState.RETURNING);
                        return ChamberState.ARMED;
                    }
                    registry.setPowerState(uuid, powered ? ChamberPowerState.POWERED : ChamberPowerState.OFF);
                }
                if (!powered) return ChamberState.INVALID;
            }
            registry.setPowerState(uuid, ChamberPowerState.POWERED);
            if (!record.enabled()) return ChamberState.INVALID;
            if (presence == ChamberSessionGateway.Presence.ACTIVE) return ChamberState.ARMED;
            if (presence == ChamberSessionGateway.Presence.ARMING) return ChamberState.READY;
            var preview = readiness.get();
            if (!preview.accepted()) return preview.readiness();
            if (sessions.activationBlocked()) return ChamberState.IDLE;
            if (current == ChamberState.ARMED && armedByStart) return armedByStart(uuid);
            return switch (Objects.requireNonNull(sessions.start(preview.participantUuids()), "start result")) {
                case ARMED_ONLY, STARTED -> armedByStart(uuid);
                case STAGING -> ChamberState.READY;
                case REJECTED -> ChamberState.READY;
            };
        } catch (RuntimeException failure) {
            registry.setPowerState(uuid, ChamberPowerState.RETURNING);
            LOGGER.warn("艙體 {} 的 session 協調失敗，保留返還保護", uuid, failure);
            return ChamberState.ARMED;
        }
    }

    /** R2 M5：記下這座艙的 ARMED 來自 start（{@link #ARMED_BY_START}），回 ARMED。 */
    private static ChamberState armedByStart(UUID uuid) {
        ARMED_BY_START.add(uuid);
        return ChamberState.ARMED;
    }

    /** 最外層交易結束後才依最終世界狀態合併刷新，finally 保證巢狀 guard 釋放。 */
    public static <T> T deferActivation(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        if (DEFERRED.get() != null) return action.get();
        Deferred deferred = new Deferred();
        DEFERRED.set(deferred);
        try {
            return action.get();
        } finally {
            DEFERRED.remove();
            for (var target : deferred.pending) new ChamberPowerCoordinator().refresh(target.world, target.pos);
        }
    }

    static boolean activationDeferred() { return DEFERRED.get() != null; }

    private static boolean boundsLoaded(ServerWorld world, ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++) {
            for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++) {
                if (world.getChunkManager().getWorldChunk(x, z) == null) return false;
            }
        }
        return true;
    }

    private static void changeState(ServerWorld world, ChamberControllerBlockEntity controller, ChamberState next) {
        if (controller.chamberState() == next) return;
        controller.setChamberState(next);
        controller.markDirty();
        world.updateComparators(controller.getPos(), ModBlocks.CHAMBER_CONTROLLER);
    }

    interface SessionActions {
        default boolean activationBlocked() { return false; }
        ChamberSessionGateway.Presence presence();
        ChamberSessionGateway.StartResult start(List<UUID> participants);
        boolean returnToOrigin();
    }
    private static final class Deferred { final Set<RefreshTarget> pending = new LinkedHashSet<>(); }
    private record RefreshTarget(ServerWorld world, BlockPos pos) { }
}
