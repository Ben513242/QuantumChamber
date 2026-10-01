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
    @Test void stagingRemainsReadyAndStartsOnlyOnceAcrossIneligibleRefresh() {
        backend.startResult = ChamberSessionGateway.StartResult.STAGING;
        assertEquals(ChamberState.READY,refresh(true,ChamberState.IDLE,true));
        backend.presence = ChamberSessionGateway.Presence.ARMING;
        for(int i=0;i<3;i++) assertEquals(ChamberState.READY,refresh(true,ChamberState.READY,false));
        assertEquals(1,backend.starts);
        assertEquals(ChamberPowerState.POWERED,registry.records().get(uuid).powerState());
    }
    @Test void stagingLowOrDisabledMustReturnAndFalseOrThrowNeverUnlocks() {
        backend.presence = ChamberSessionGateway.Presence.ARMING;
        backend.returned = false;
        assertEquals(ChamberState.ARMED,refresh(false,ChamberState.READY,false));
        assertEquals(ChamberPowerState.RETURNING,registry.records().get(uuid).powerState());
        backend.throwOnReturn = true;
        assertEquals(ChamberState.ARMED,refresh(false,ChamberState.READY,false));
        backend.throwOnReturn = false;
        registry.setEnabled(uuid,false);
        assertEquals(ChamberState.ARMED,refresh(true,ChamberState.READY,false));
        assertEquals(0,backend.starts);
    }
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

    @Test void activePresenceIgnoresEmptyOriginReadiness() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.ACTIVE;
        assertEquals(ChamberState.ARMED, refresh(true, ChamberState.ARMED, false));
        assertEquals(0, backend.starts);
    }

    @Test void buffReturnAtHeldHighWaitsForAckThenIdlesAndRedrinkMayStartAgain() {
        registry.setPowerState(uuid,ChamberPowerState.POWERED);
        backend.presence=ChamberSessionGateway.Presence.RETURNING;
        backend.returned=false;
        assertEquals(ChamberState.ARMED,refresh(true,ChamberState.ARMED,true));
        assertEquals(0,backend.starts,"RETURNING中重新符合Buff也不能撤銷返還");
        backend.returned=true;
        assertEquals(ChamberState.IDLE,refresh(true,ChamberState.ARMED,false));
        assertEquals(ChamberPowerState.POWERED,registry.records().get(uuid).powerState());
        backend.startResult=ChamberSessionGateway.StartResult.STAGING;
        assertEquals(ChamberState.READY,refresh(true,ChamberState.IDLE,true));
        assertEquals(1,backend.starts);
        backend.presence=ChamberSessionGateway.Presence.ARMING;
        backend.returned=false;
        assertEquals(ChamberState.ARMED,refresh(false,ChamberState.READY,false));
        assertEquals(ChamberPowerState.RETURNING,registry.records().get(uuid).powerState());
        backend.returned=true;
        assertEquals(ChamberState.INVALID,refresh(false,ChamberState.ARMED,false));
        assertEquals(ChamberPowerState.OFF,registry.records().get(uuid).powerState());
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

    /**
     * spec §10.7（決策 11）：COLLAPSE 與 PASSAGE 不走 pending／needsReturn、不呼叫 returnToOrigin、不開新 session；power state 只設 POWERED
     * （已供電且 enabled）或 RETURNING（不設 OFF，保護維持）；比較器暫時一律 ARMED（11），PASSAGE→15 留給 F5。涵蓋 registry 的每個
     * power state（含 M4 DORMANT 留下的 RETURNING）、供電與 enabled 的四種組合，以及每個目前的 ChamberState。
     */
    @Test void collapseAndPassagePresenceNeverReturnOrStartAndOnlySetPoweredOrReturning() {
        var cases = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (var presence : List.of(ChamberSessionGateway.Presence.COLLAPSE, ChamberSessionGateway.Presence.PASSAGE))
            for (var prior : ChamberPowerState.values()) for (boolean powered : List.of(true, false)) for (boolean enabled : List.of(true, false))
                for (var current : ChamberState.values()) {
                    var label = presence + " prior=" + prior + " powered=" + powered + " enabled=" + enabled + " current=" + current;
                    registry.setPowerState(uuid, prior); registry.setEnabled(uuid, enabled);
                    backend.presence = presence; backend.returns = 0; backend.starts = 0;
                    var next = refresh(powered, current, true);
                    var power = registry.records().get(uuid).powerState();
                    int returns = backend.returns, starts = backend.starts;
                    cases.add(() -> assertEquals(ChamberState.ARMED, next, label));
                    cases.add(() -> assertEquals(0, returns, "不呼叫 returnToOrigin：" + label));
                    cases.add(() -> assertEquals(0, starts, "不開新 session：" + label));
                    cases.add(() -> assertEquals(powered && enabled ? ChamberPowerState.POWERED : ChamberPowerState.RETURNING, power, label));
                }
        assertAll(cases);
    }

    /** spec §10.7：終局之後 presence 回到 NONE，下一次 reconcile 依既有流程把 M5 斷電時留下的 RETURNING 解除為 POWERED 或 OFF。 */
    @Test void terminalPresenceNoneResumesTheExistingReturningRelease() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.COLLAPSE;
        assertEquals(ChamberState.ARMED, refresh(false, ChamberState.ARMED, false));
        assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
        backend.presence = ChamberSessionGateway.Presence.NONE;
        assertEquals(ChamberState.IDLE, refresh(true, ChamberState.ARMED, false));
        assertEquals(1, backend.returns, "既有流程：RETURNING 先經 returnToOrigin 確認");
        assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
        backend.presence = ChamberSessionGateway.Presence.COLLAPSE;
        assertEquals(ChamberState.ARMED, refresh(false, ChamberState.IDLE, false));
        backend.presence = ChamberSessionGateway.Presence.NONE;
        assertEquals(ChamberState.INVALID, refresh(false, ChamberState.ARMED, false));
        assertEquals(ChamberPowerState.OFF, registry.records().get(uuid).powerState());
        assertEquals(0, backend.starts);
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
        int returns;
        boolean blocked;
        List<UUID> startedParticipants;
        ChamberSessionGateway.StartResult startResult = ChamberSessionGateway.StartResult.ARMED_ONLY;
        @Override public ChamberSessionGateway.Presence presence() { return presence; }
        @Override public boolean activationBlocked() { return blocked; }
        @Override public ChamberSessionGateway.StartResult start(List<UUID> ids) {
            assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
            starts++;
            startedParticipants = ids;
            return startResult;
        }
        @Override public boolean returnToOrigin() {
            returns++;
            assertEquals(ChamberPowerState.RETURNING, registry.records().get(uuid).powerState());
            if (throwOnReturn) throw new IllegalStateException("返還失敗");
            if (returned) presence = ChamberSessionGateway.Presence.NONE;
            return returned;
        }
    }
}
