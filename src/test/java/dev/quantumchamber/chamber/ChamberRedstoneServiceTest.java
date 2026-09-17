package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChamberRedstoneServiceTest {
    private final ChamberRedstoneService service = new ChamberRedstoneService(new ChamberActivationEvaluator());

    @Test
    void invalidPulseDoesNotArm() {
        InMemoryController controller = initialized(ChamberState.INVALID);

        service.onPowerChanged(controller, true, snapshot(false, true));

        assertEquals(ChamberState.INVALID, controller.chamberState());
    }

    @Test
    void addingBuffWhilePowerRemainsHighArmsWithoutAnotherPulse() {
        InMemoryController controller = initialized(ChamberState.IDLE);

        service.onPowerChanged(controller, true, snapshot(true, false));
        service.onPowerChanged(controller, true, snapshot(true, true));

        assertEquals(ChamberState.ARMED, controller.chamberState());
    }

    @Test
    void acceptedPulseArmsOnceWithoutConsumingQuantumState() {
        InMemoryController controller = initialized(ChamberState.READY);
        ChamberActivationSnapshot eligible = snapshot(true, true);

        service.onPowerChanged(controller, true, eligible);
        service.onPowerChanged(controller, true, eligible);

        assertEquals(ChamberState.ARMED, controller.chamberState());
        assertEquals(ChamberState.READY, new ChamberActivationEvaluator().evaluate(eligible));
    }

    @Test
    void fallingPowerReevaluatesArmedState() {
        InMemoryController controller = initialized(ChamberState.ARMED);
        controller.setWasPowered(true);

        service.onPowerChanged(controller, false, snapshot(true, true));

        assertEquals(ChamberState.INVALID, controller.chamberState());

        controller.setChamberState(ChamberState.ARMED);
        controller.setWasPowered(true);
        service.onPowerChanged(controller, false, snapshot(true, false));

        assertEquals(ChamberState.INVALID, controller.chamberState());
    }

    @Test
    void directRefreshDowngradesArmedAndNotifiesComparatorExactlyOnce() {
        InMemoryController controller = initialized(ChamberState.ARMED);
        CountingNotifier notifier = new CountingNotifier();
        controller.setWasPowered(true);

        service.refreshState(controller, snapshot(true, false), notifier);

        assertEquals(ChamberState.IDLE, controller.chamberState());
        assertEquals(1, notifier.count());
    }

    @Test
    void sameHighDoorMutationEventsPreserveArmedUntilCommittedRefresh() {
        InMemoryController controller = initialized(ChamberState.ARMED);
        controller.setWasPowered(true);
        CountingNotifier notifier = new CountingNotifier();
        CountingArmAttempts attempts = new CountingArmAttempts();

        ChamberPowerCoordinator.deferActivation(() -> {
            for (int index = 0; index < 25; index++) {
                service.onPowerChanged(controller, true, snapshot(true, false), notifier, attempts);
            }
            return null;
        });

        assertEquals(ChamberState.ARMED, controller.chamberState());
        assertEquals(0, notifier.count());
        assertEquals(0, attempts.count());

        service.refreshState(controller, snapshot(true, false), notifier);

        assertEquals(ChamberState.IDLE, controller.chamberState());
        assertEquals(1, notifier.count());
        assertEquals(0, attempts.count());
    }

    private static InMemoryController initialized(ChamberState state) {
        return new InMemoryController(false, true, state);
    }

    private static ChamberActivationSnapshot snapshot(boolean enabled, boolean quantumState) {
        return new ChamberActivationSnapshot(
                enabled,
                true,
                true,
                List.of(new ChamberActivationSnapshot.ParticipantEligibility(
                        UUID.fromString("00000000-0000-0000-0000-000000000001"), quantumState)));
    }

    private static final class InMemoryController implements ChamberControllerPort {
        private boolean wasPowered;
        private boolean powerInitialized;
        private ChamberState chamberState;

        private InMemoryController(boolean wasPowered, boolean powerInitialized, ChamberState chamberState) {
            this.wasPowered = wasPowered;
            this.powerInitialized = powerInitialized;
            this.chamberState = chamberState;
        }

        @Override
        public boolean wasPowered() {
            return wasPowered;
        }

        @Override
        public void setWasPowered(boolean wasPowered) {
            this.wasPowered = wasPowered;
        }

        @Override
        public boolean powerInitialized() {
            return powerInitialized;
        }

        @Override
        public void setPowerInitialized(boolean powerInitialized) {
            this.powerInitialized = powerInitialized;
        }

        @Override
        public ChamberState chamberState() {
            return chamberState;
        }

        @Override
        public void setChamberState(ChamberState chamberState) {
            this.chamberState = chamberState;
        }
    }

    private static final class CountingNotifier implements ChamberRedstoneService.ComparatorNotifier {
        private int count;

        @Override
        public void update() {
            count++;
        }

        private int count() {
            return count;
        }
    }

    private static final class CountingArmAttempts implements ChamberRedstoneService.ArmAttemptObserver {
        private int count;

        @Override
        public void onAttempt() {
            count++;
        }

        private int count() {
            return count;
        }
    }
}
