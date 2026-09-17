package dev.quantumchamber.chamber;

import java.util.Objects;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** 原生電位輸入；供電協調不依賴 rising edge。 */
public final class ChamberRedstoneService {
    private final ChamberActivationEvaluator evaluator;
    private final ChamberPowerCoordinator coordinator = new ChamberPowerCoordinator();
    public ChamberRedstoneService() { this(new ChamberActivationEvaluator()); }
    ChamberRedstoneService(ChamberActivationEvaluator evaluator) { this.evaluator = Objects.requireNonNull(evaluator); }
    public void onLoad(ServerWorld world, BlockPos pos) { coordinator.refresh(world, pos); }
    public void onNeighborUpdate(ServerWorld world, BlockPos pos) {
        if (!world.isChunkLoaded(pos)) return;
        if (!(world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller)
                || controller.loadSyncPending()) return;
        coordinator.refresh(world, pos);
    }
    public void refreshState(ServerWorld world, BlockPos pos) { coordinator.refresh(world, pos); }

    /** 純資格 seam；原生 session 與持久化由 coordinator 管理。 */
    void refreshState(ChamberControllerPort controller, ChamberActivationSnapshot snapshot, ComparatorNotifier notifier) {
        onPowerChanged(controller, controller.wasPowered(), snapshot, notifier, () -> { });
    }
    void onPowerChanged(ChamberControllerPort controller, boolean powered, ChamberActivationSnapshot snapshot) {
        onPowerChanged(controller, powered, snapshot, () -> { }, () -> { });
    }
    void onPowerChanged(ChamberControllerPort controller, boolean powered, ChamberActivationSnapshot snapshot,
                        ComparatorNotifier notifier, ArmAttemptObserver observer) {
        Objects.requireNonNull(controller);
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(notifier);
        Objects.requireNonNull(observer);
        controller.setWasPowered(powered);
        controller.setPowerInitialized(true);
        if (ChamberPowerCoordinator.activationDeferred()) return;
        ChamberState next = powered ? evaluator.evaluate(snapshot) : ChamberState.INVALID;
        if (next == ChamberState.READY) {
            if (controller.chamberState() != ChamberState.ARMED) observer.onAttempt();
            next = ChamberState.ARMED;
        }
        if (controller.chamberState() != next) {
            controller.setChamberState(next);
            notifier.update();
        }
    }
    @FunctionalInterface interface ComparatorNotifier { void update(); }
    @FunctionalInterface interface ArmAttemptObserver { void onAttempt(); }
}
