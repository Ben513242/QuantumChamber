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
     * （已供電且 enabled）或 RETURNING（不設 OFF，保護維持）；比較器是 PASSAGE＋已供電＋enabled 時 PASSAGE（15，F5-2），其餘 ARMED（11）。涵蓋 registry 的每個
     * power state（含 M4 DORMANT 留下的 RETURNING：已是 RETURNING 時仍能到達 15）、供電與 enabled 的四種組合，以及每個目前的 ChamberState（含 PASSAGE）。
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
                    var expected = presence == ChamberSessionGateway.Presence.PASSAGE && powered && enabled ? ChamberState.PASSAGE : ChamberState.ARMED;
                    cases.add(() -> assertEquals(expected, next, label));
                    cases.add(() -> assertEquals(expected == ChamberState.PASSAGE ? 15 : 11, ChamberStatusSignal.forState(next), "比較器：" + label));
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

    /**
     * spec §10.7、§15 的 reconcile 列（F5-2）：一次塌縮的比較器。COLLAPSE（已供電）11、POWERED → 斷電 11、RETURNING（保護不解除）→ 通道開啟（PASSAGE）而 registry 仍是 RETURNING、
     * 已恢復供電：15、POWERED → 通道期間斷電：11、RETURNING → 終局（presence NONE）之後依既有流程：先經 returnToOrigin 確認，斷電中是 OFF、INVALID；恢復供電、沒有資格時 IDLE。
     * 整段沒有開新 session，M5 presence 的期間也沒有呼叫 returnToOrigin。
     */
    @Test void passageReachesFifteenFromAReturningRegistryAndTheTerminalResumesTheExistingFlow() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.COLLAPSE;
        var collapsing = refresh(true, ChamberState.IDLE, false);
        var collapsingPower = registry.records().get(uuid).powerState();
        var cut = refresh(false, collapsing, false);
        var cutPower = registry.records().get(uuid).powerState();
        backend.presence = ChamberSessionGateway.Presence.PASSAGE;
        var opened = refresh(true, cut, false);
        var openedPower = registry.records().get(uuid).powerState();
        var openedUnpowered = refresh(false, opened, false);
        var openedUnpoweredPower = registry.records().get(uuid).powerState();
        int returnsDuringM5 = backend.returns;
        backend.presence = ChamberSessionGateway.Presence.NONE;
        var ended = refresh(false, openedUnpowered, false);
        var endedPower = registry.records().get(uuid).powerState();
        var repowered = refresh(true, ended, false);
        assertAll(
                () -> assertEquals(ChamberState.ARMED, collapsing), () -> assertEquals(ChamberPowerState.POWERED, collapsingPower),
                () -> assertEquals(ChamberState.ARMED, cut), () -> assertEquals(ChamberPowerState.RETURNING, cutPower, "斷電：保護不解除"),
                () -> assertEquals(ChamberState.PASSAGE, opened, "registry 是 RETURNING 時仍能到達 15"),
                () -> assertEquals(15, ChamberStatusSignal.forState(opened)), () -> assertEquals(ChamberPowerState.POWERED, openedPower),
                () -> assertEquals(ChamberState.ARMED, openedUnpowered, "通道期間斷電：11"), () -> assertEquals(ChamberPowerState.RETURNING, openedUnpoweredPower),
                () -> assertEquals(0, returnsDuringM5, "M5 presence 期間不呼叫 returnToOrigin"),
                () -> assertEquals(ChamberState.INVALID, ended), () -> assertEquals(ChamberPowerState.OFF, endedPower, "終局之後：既有流程解除為 OFF"),
                () -> assertEquals(1, backend.returns, "既有流程：RETURNING 先經 returnToOrigin 確認"),
                () -> assertEquals(ChamberState.IDLE, repowered), () -> assertEquals(0, backend.starts));
    }

    /**
     * R2 M5（spec §10.7 :634，F5-2）：presence 為 NONE、目前是 ARMED，而這個 ARMED 不是 start 留下的（M5 的 COLLAPSE 已終局、活動 session 不經這裡的返還路徑就結束，或重啟之前存下的
     * ARMED）時，不再卡在 11：已供電、enabled、有資格就依 M2 規則開新 session（spec §10.6「通道結束之後，若關了門、而且全員仍有 Buff，依 M2 規則會自動開始新的 session」）。
     * start 回 ARMED_ONLY 留下的 ARMED 仍然保持、不重複啟動（M1.2；{@code poweredWithoutEligibilityProtectsAndThenArmsOnlyOnce}），但 presence 一旦不是 NONE，這個記號就不再有效。
     */
    @Test void armedLeftWithoutTheSessionThatHeldItIsReevaluatedAndOnlyAStartedArmedHolds() {
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        backend.presence = ChamberSessionGateway.Presence.COLLAPSE;
        var collapse = refresh(true, ChamberState.IDLE, true);
        backend.presence = ChamberSessionGateway.Presence.NONE;
        var afterTheTerminal = refresh(true, collapse, true);
        int startsAfterTheTerminal = backend.starts;
        var held = refresh(true, afterTheTerminal, true);
        int startsWhileHeld = backend.starts;
        backend.presence = ChamberSessionGateway.Presence.ACTIVE;
        var active = refresh(true, held, true);
        backend.presence = ChamberSessionGateway.Presence.NONE;
        var afterTheActiveSession = refresh(true, active, true);
        int startsAfterTheActiveSession = backend.starts;
        var restored = new ChamberRegistry();
        var restoredUuid = restored.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, new ChamberFrame(new BlockPos(64, 70, 64), Direction.NORTH)).chamberUuid();
        restored.setPowerState(restoredUuid, ChamberPowerState.POWERED);
        var restart = ChamberPowerCoordinator.reconcile(restored, restoredUuid, true, ChamberState.ARMED,
                () -> new ArmAttemptResult(true, ChamberState.READY, participants, Set.of()), backend);
        int startsAfterARestart = backend.starts;
        assertAll(
                () -> assertEquals(ChamberState.ARMED, collapse),
                () -> assertEquals(ChamberState.ARMED, afterTheTerminal), () -> assertEquals(1, startsAfterTheTerminal, "COLLAPSE 終局之後：開新 session，不卡在 11"),
                () -> assertEquals(ChamberState.ARMED, held), () -> assertEquals(1, startsWhileHeld, "start 留下的 ARMED：持續高電位不重複啟動"),
                () -> assertEquals(ChamberState.ARMED, active),
                () -> assertEquals(ChamberState.ARMED, afterTheActiveSession), () -> assertEquals(2, startsAfterTheActiveSession, "活動 session 不經返還路徑結束之後：重新評估"),
                () -> assertEquals(ChamberState.ARMED, restart), () -> assertEquals(3, startsAfterARestart, "重啟之前存下的 ARMED：重新評估"));
    }

    /**
     * M5 spec §10.6 :613（F5-2）：找不到所屬 session 的量子膜，只在門帶著量子膜、而且 presence 不是 M5 的塌縮狀態時交給塌縮流程正規化。COLLAPSE（COLLAPSING、SEALED）與 PASSAGE
     * （PASSAGE_READY）由該 session 的門邏輯負責；MEASURED（含 DORMANT：ACTIVE 或 UNKNOWN）與沒有 session（NONE）都包含在內。門沒有量子膜時一律不交出。
     */
    @Test void ownerlessMembraneIsHandedOverOnlyWithAMembraneAndWithoutAnM5Presence() {
        var cases = new java.util.ArrayList<org.junit.jupiter.api.function.Executable>();
        for (var presence : ChamberSessionGateway.Presence.values()) {
            boolean m5 = presence == ChamberSessionGateway.Presence.COLLAPSE || presence == ChamberSessionGateway.Presence.PASSAGE;
            cases.add(() -> assertEquals(!m5, ChamberPowerCoordinator.normalizesOwnerless(presence, true), "門帶著量子膜，presence=" + presence));
            cases.add(() -> assertFalse(ChamberPowerCoordinator.normalizesOwnerless(presence, false), "門沒有量子膜，presence=" + presence));
        }
        cases.add(() -> assertTrue(ChamberPowerCoordinator.normalizesOwnerless(ChamberSessionGateway.Presence.NONE, true), "終局之後、改門之前 crash：沒有 session"));
        cases.add(() -> assertTrue(ChamberPowerCoordinator.normalizesOwnerless(ChamberSessionGateway.Presence.UNKNOWN, true), "DORMANT（沒有 runtime 的 MEASURED）"));
        cases.add(() -> assertFalse(ChamberPowerCoordinator.normalizesOwnerless(ChamberSessionGateway.Presence.COLLAPSE, true), "COLLAPSING／SEALED 自己擁有門"));
        cases.add(() -> assertFalse(ChamberPowerCoordinator.normalizesOwnerless(ChamberSessionGateway.Presence.PASSAGE, true), "PASSAGE_READY 自己擁有門"));
        cases.add(() -> assertThrows(NullPointerException.class, () -> ChamberPowerCoordinator.normalizesOwnerless(null, true)));
        assertAll(cases);
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
