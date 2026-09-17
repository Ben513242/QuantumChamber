package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import java.util.Objects;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** Server-authoritative redstone transitions for a single chamber controller. */
public final class ChamberRedstoneService {
    private final ChamberActivationService activationService;
    private final ChamberActivationEvaluator evaluator;

    public ChamberRedstoneService() {
        this(new ChamberActivationService(), new ChamberActivationEvaluator());
    }

    ChamberRedstoneService(ChamberActivationEvaluator evaluator) {
        this(new ChamberActivationService(), evaluator);
    }

    ChamberRedstoneService(ChamberActivationService activationService, ChamberActivationEvaluator evaluator) {
        this.activationService = Objects.requireNonNull(activationService, "activationService");
        this.evaluator = Objects.requireNonNull(evaluator, "evaluator");
    }

    /** Records current power after load without treating a held-high input as a pulse. */
    public void onLoad(ServerWorld world, BlockPos pos) {
        ChamberControllerBlockEntity controller = controllerAt(world, pos);
        if (controller == null) return;
        synchronize(controller, world.isReceivingRedstonePower(pos));
        controller.markDirty();
    }

    /** Handles a neighboring redstone update and calls attemptArm at most once per rising edge. */
    public void onNeighborUpdate(ServerWorld world, BlockPos pos) {
        ChamberControllerBlockEntity controller = controllerAt(world, pos);
        // 首次 block tick 同步前，持久化電平可能已落後於世界；不能據此推導新 edge。
        if (controller == null || controller.loadSyncPending()) return;
        boolean powered = world.isReceivingRedstonePower(pos);
        if (!controller.powerInitialized()) {
            synchronize(controller, powered);
            controller.markDirty();
            return;
        }

        boolean previouslyPowered = controller.wasPowered();
        RisingEdgeLatch latch = latchFor(controller);
        boolean risingEdge = latch.observe(powered);
        persistLatch(controller, powered);
        controller.markDirty();
        if (risingEdge) {
            ArmAttemptResult result = activationService.attemptArm(world, controller);
            changeState(world, pos, controller, result.accepted() ? ChamberState.ARMED : result.readiness());
        } else if (previouslyPowered && !powered) {
            changeState(world, pos, controller, activationService.evaluateReadiness(world, controller).readiness());
        }
    }

    /** Refreshes readiness/comparator output; a still-valid ARMED chamber remains armed. */
    public void refreshState(ServerWorld world, BlockPos pos) {
        ChamberControllerBlockEntity controller = controllerAt(world, pos);
        if (controller == null) return;
        ChamberState evaluated = activationService.evaluateReadiness(world, controller).readiness();
        if (controller.chamberState() != ChamberState.ARMED || evaluated != ChamberState.READY) {
            changeState(world, pos, controller, evaluated);
        }
    }

    /** Pure direct-refresh seam used by successful door mutations and unit tests. */
    void refreshState(
            ChamberControllerPort controller, ChamberActivationSnapshot snapshot, ComparatorNotifier comparatorNotifier) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(comparatorNotifier, "comparatorNotifier");
        ChamberState evaluated = evaluator.evaluate(snapshot);
        if (controller.chamberState() != ChamberState.ARMED || evaluated != ChamberState.READY) {
            changeState(controller, evaluated, comparatorNotifier);
        }
    }

    /** Pure seam for unit tests: it deliberately has no world, registry, or player-effect mutation. */
    void onPowerChanged(ChamberControllerPort controller, boolean powered, ChamberActivationSnapshot snapshot) {
        onPowerChanged(controller, powered, snapshot, () -> { }, () -> { });
    }

    /** Pure edge path with observability for tests; same-level events deliberately do nothing. */
    void onPowerChanged(
            ChamberControllerPort controller,
            boolean powered,
            ChamberActivationSnapshot snapshot,
            ComparatorNotifier comparatorNotifier,
            ArmAttemptObserver armAttemptObserver) {
        Objects.requireNonNull(controller, "controller");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(comparatorNotifier, "comparatorNotifier");
        Objects.requireNonNull(armAttemptObserver, "armAttemptObserver");
        if (!controller.powerInitialized()) {
            synchronize(controller, powered);
            return;
        }

        boolean previouslyPowered = controller.wasPowered();
        RisingEdgeLatch latch = latchFor(controller);
        boolean risingEdge = latch.observe(powered);
        persistLatch(controller, powered);
        ChamberState evaluated = evaluator.evaluate(snapshot);
        if (risingEdge) {
            armAttemptObserver.onAttempt();
            changeState(controller, evaluated == ChamberState.READY ? ChamberState.ARMED : evaluated, comparatorNotifier);
        } else if (previouslyPowered && !powered) {
            changeState(controller, evaluated, comparatorNotifier);
        }
    }

    private static ChamberControllerBlockEntity controllerAt(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof ChamberControllerBlockEntity controller ? controller : null;
    }

    private static RisingEdgeLatch latchFor(ChamberControllerPort controller) {
        RisingEdgeLatch latch = new RisingEdgeLatch();
        latch.synchronizeWithoutEdge(controller.wasPowered());
        return latch;
    }

    private static void synchronize(ChamberControllerPort controller, boolean powered) {
        controller.setWasPowered(powered);
        controller.setPowerInitialized(true);
    }

    private static void persistLatch(ChamberControllerPort controller, boolean powered) {
        controller.setWasPowered(powered);
        controller.setPowerInitialized(true);
    }

    private static void changeState(
            ServerWorld world, BlockPos pos, ChamberControllerBlockEntity controller, ChamberState state) {
        if (controller.chamberState() == state) return;
        controller.setChamberState(state);
        controller.markDirty();
        world.updateComparators(pos, ModBlocks.CHAMBER_CONTROLLER);
    }

    private static void changeState(
            ChamberControllerPort controller, ChamberState state, ComparatorNotifier comparatorNotifier) {
        if (controller.chamberState() == state) return;
        controller.setChamberState(state);
        comparatorNotifier.update();
    }

    @FunctionalInterface
    interface ComparatorNotifier {
        void update();
    }

    @FunctionalInterface
    interface ArmAttemptObserver {
        void onAttempt();
    }
}
