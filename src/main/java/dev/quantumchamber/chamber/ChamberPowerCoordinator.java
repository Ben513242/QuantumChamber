package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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
        var chunk = world.getChunkManager().getWorldChunk(controllerPos.getX() >> 4, controllerPos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(controllerPos) instanceof ChamberControllerBlockEntity controller)
                || controller.isRemoved() || controller.getWorld() != world || !controller.getPos().equals(controllerPos)) return;
        var block = chunk.getBlockState(controllerPos);
        if (!block.isOf(ModBlocks.CHAMBER_CONTROLLER) || !controller.getCachedState().equals(block)) return;
        boolean powered = world.isReceivingRedstonePower(controllerPos);
        controller.setWasPowered(powered);
        controller.setPowerInitialized(true);
        controller.markDirty();
        var role = DimensionRole.fromVanillaKey(world.getRegistryKey());
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
    }

    static ChamberState reconcile(ChamberRegistry registry, UUID uuid, boolean powered, ChamberState current,
                                  Supplier<ArmAttemptResult> readiness, SessionActions sessions) {
        var record = registry.records().get(uuid);
        if (record == null || record.destroyed() || record.instanceKind() != ChamberInstanceKind.ORIGIN) return ChamberState.INVALID;
        try {
            var presence = Objects.requireNonNull(sessions.presence(), "presence");
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
            var preview = readiness.get();
            if (!preview.accepted()) return preview.readiness();
            if (sessions.activationBlocked()) return ChamberState.IDLE;
            if (current == ChamberState.ARMED) return current;
            return switch (Objects.requireNonNull(sessions.start(preview.participantUuids()), "start result")) {
                case ARMED_ONLY, STARTED -> ChamberState.ARMED;
                case REJECTED -> ChamberState.READY;
            };
        } catch (RuntimeException failure) {
            registry.setPowerState(uuid, ChamberPowerState.RETURNING);
            LOGGER.warn("艙體 {} 的 session 協調失敗，保留返還保護", uuid, failure);
            return ChamberState.ARMED;
        }
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
