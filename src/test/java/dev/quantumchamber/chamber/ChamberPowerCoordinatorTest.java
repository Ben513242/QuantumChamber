package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.universe.DimensionRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberPowerCoordinatorTest {
    @Test void doorFaultBlocksStartAcrossRefreshAndRepowerButDoesNotBlockReturn() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.blocked = true;
        assertEquals(ChamberState.INVALID, ChamberPowerCoordinator.reconcile(registry, uuid, true, ChamberState.IDLE,
                () -> new ArmAttemptResult(false, ChamberState.INVALID, List.of(), Set.of(ArmAttemptResult.Failure.INVALID_STRUCTURE)), backend),
                "故障 gate 不得把既有結構 INVALID 顯示成有效 IDLE");
        for (int index = 0; index < 3; index++) assertEquals(ChamberState.IDLE, refresh(true, ChamberState.IDLE, true));
        assertEquals(0, backend.starts);
        assertEquals(ChamberState.INVALID, refresh(false, ChamberState.IDLE, true));
        assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState());
        assertEquals(ChamberState.IDLE, refresh(true, ChamberState.INVALID, true));
        assertEquals(0, backend.starts);
        backend.blocked = false;
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.IDLE, true));
        assertEquals(1, backend.starts);
    }

    private final ChamberRegistry registry = new ChamberRegistry();
    private final UUID uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
            new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH)).chamberUuid();
    private final Backend backend = new Backend();
    private final List<UUID> participants = List.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));

    @Test void poweredWithoutEligibilityProtectsAndThenArmsOnlyOnce() {
        registry.setPowerState(uuid, ChamberPowerState.OFF);
        assertEquals(ChamberState.IDLE, refresh(true, ChamberState.INVALID, false));
        assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
        assertEquals(0, backend.starts);
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.IDLE, true));
        assertEquals(participants, backend.startedParticipants);
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, true));
        assertEquals(1, backend.starts);
    }

    @Test void activeIgnoresEmptyOriginAndConsumedBuff() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.ACTIVE;
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, false));
        assertEquals(0, backend.starts);
    }

    @Test void failedReturnKeepsProtectionAndRepowerMustFinishReturnBeforeStarting() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.ACTIVE;
        backend.returned = false;
        assertEquals(ChamberState.ARMED, refresh(false, ChamberState.ARMED, true));
        assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, true));
        assertEquals(0, backend.starts);
        backend.returned = true;
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, true));
        assertEquals(1, backend.starts);
        assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
        assertEquals(ChamberState.INVALID, refresh(false, ChamberState.ARMED, true));
        assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState());
    }

    @Test void disabledHighReturnsButRetainsPoweredProtection() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        registry.setEnabled(uuid, false);
        backend.presence = ChamberSessionGateway.Presence.ACTIVE;
        backend.returned = false;
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, true));
        backend.returned = true;
        assertEquals(ChamberState.INVALID, refresh(true, ChamberState.ARMED, true));
        assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
        assertEquals(0, backend.starts);
    }

    @Test void unknownOrReturningPresenceAndExceptionsNeverUnlockOrStart() {
        for (var presence : List.of(ChamberSessionGateway.Presence.UNKNOWN, ChamberSessionGateway.Presence.RETURNING)) {
            registry.setPowerState(uuid, ChamberPowerState.POWERED);
            backend.presence = presence;
            backend.returned = false;
            assertEquals(ChamberState.ARMED, refresh(true, ChamberState.READY, true));
            assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
            assertEquals(0, backend.starts);
        }
        backend.throwOnReturn = true;
        assertEquals(ChamberState.ARMED, refresh(false, ChamberState.ARMED, true));
        assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
    }

    @Test void nestedDeferralReleasesAfterException() {
        assertFalse(ChamberPowerCoordinator.activationDeferred());
        assertThrows(IllegalStateException.class, () -> ChamberPowerCoordinator.deferActivation(() -> {
            assertTrue(ChamberPowerCoordinator.activationDeferred());
            ChamberPowerCoordinator.deferActivation(() -> {
                assertTrue(ChamberPowerCoordinator.activationDeferred());
                return null;
            });
            assertTrue(ChamberPowerCoordinator.activationDeferred());
            throw new IllegalStateException("預期例外");
        }));
        assertFalse(ChamberPowerCoordinator.activationDeferred());
    }

    private ChamberState refresh(boolean powered, ChamberState current, boolean eligible) {
        return ChamberPowerCoordinator.reconcile(registry, uuid, powered, current,
                () -> new ArmAttemptResult(eligible, eligible ? ChamberState.READY : ChamberState.IDLE,
                        eligible ? participants : List.of(), Set.of()), backend);
    }

    private final class Backend implements ChamberPowerCoordinator.SessionActions {
        ChamberSessionGateway.Presence presence = ChamberSessionGateway.Presence.NONE;
        boolean returned = true;
        boolean throwOnReturn;
        int starts;
        boolean blocked;
        List<UUID> startedParticipants;
        @Override public ChamberSessionGateway.Presence presence() { return presence; }
        @Override public boolean activationBlocked() { return blocked; }
        @Override public ChamberSessionGateway.StartResult start(List<UUID> ids) {
            assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
            starts++;
            startedParticipants = ids;
            return ChamberSessionGateway.StartResult.ARMED_ONLY;
        }
        @Override public boolean returnToOrigin() {
            assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
            if (throwOnReturn) throw new IllegalStateException("返還失敗");
            if (returned) presence = ChamberSessionGateway.Presence.NONE;
            return returned;
        }
    }
}
