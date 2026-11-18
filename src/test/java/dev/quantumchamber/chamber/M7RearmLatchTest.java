package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.DimensionRole;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * M7 T4A（spec v1.6 A、D）：供電協調的 latch 與永久封存的解除路徑（{@link ChamberPowerCoordinator#reconcile}），以及 Controller block entity 的 latch NBT。
 */
class M7RearmLatchTest {
    private static final List<UUID> PARTICIPANTS = List.of(UUID.fromString("00000000-0000-0000-0000-0000000007b1"));

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    /**
     * latch 為真：已供電、enabled、沒有 session、資格成立也回 IDLE（比較器 3），不做資格預覽、不呼叫 start；持續高電位重複協調仍不出發。停用優先回 INVALID；
     * ACTIVE、ARMING 的判斷在 latch 之前、順序不變；斷電回 INVALID，協調本身不清 latch，恢復供電仍是 IDLE。latch 清除之後同樣的條件就出發。
     */
    @Test void latchedOriginReturnsIdleWithoutStart() {
        var registry = new ChamberRegistry();
        var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH)).chamberUuid();
        registry.setPowerState(uuid, ChamberPowerState.POWERED);
        var actions = new Actions(registry, uuid);
        actions.rearm = true;
        var preview = new int[1];
        java.util.function.Supplier<ArmAttemptResult> eligible = () -> { preview[0]++; return new ArmAttemptResult(true, ChamberState.READY, PARTICIPANTS, Set.of()); };
        var cases = new ArrayList<Executable>();
        var first = ChamberPowerCoordinator.reconcile(registry, uuid, true, ChamberState.IDLE, eligible, actions);
        cases.add(() -> assertEquals(ChamberState.IDLE, first, "latch：資格成立也回 IDLE"));
        cases.add(() -> assertEquals(3, ChamberStatusSignal.forState(first), "比較器 3"));
        var held = new ArrayList<ChamberState>();
        for (int i = 0; i < 3; i++) held.add(ChamberPowerCoordinator.reconcile(registry, uuid, true, held.isEmpty() ? first : held.getLast(), eligible, actions));
        cases.add(() -> assertEquals(List.of(ChamberState.IDLE, ChamberState.IDLE, ChamberState.IDLE), held, "持續高電位重複協調仍不出發"));
        int startsWhileLatched = actions.starts, previewsWhileLatched = preview[0];
        cases.add(() -> assertEquals(0, startsWhileLatched, "latch 期間不呼叫 start"));
        cases.add(() -> assertEquals(0, previewsWhileLatched, "latch 在資格預覽之前"));
        registry.setEnabled(uuid, false);
        var disabled = ChamberPowerCoordinator.reconcile(registry, uuid, true, ChamberState.IDLE, eligible, actions);
        registry.setEnabled(uuid, true);
        cases.add(() -> assertEquals(ChamberState.INVALID, disabled, "DISABLED 優先回 INVALID"));
        actions.presence = ChamberSessionGateway.Presence.ARMING;
        var arming = ChamberPowerCoordinator.reconcile(registry, uuid, true, ChamberState.IDLE, eligible, actions);
        actions.presence = ChamberSessionGateway.Presence.ACTIVE;
        var active = ChamberPowerCoordinator.reconcile(registry, uuid, true, ChamberState.IDLE, eligible, actions);
        actions.presence = ChamberSessionGateway.Presence.NONE;
        cases.add(() -> assertEquals(ChamberState.READY, arming, "ARMING 的判斷在 latch 之前"));
        cases.add(() -> assertEquals(ChamberState.ARMED, active, "ACTIVE 的判斷在 latch 之前"));
        var low = ChamberPowerCoordinator.reconcile(registry, uuid, false, ChamberState.IDLE, eligible, actions);
        boolean latchAfterLow = actions.rearm;
        var repowered = ChamberPowerCoordinator.reconcile(registry, uuid, true, low, eligible, actions);
        cases.add(() -> assertEquals(ChamberState.INVALID, low, "斷電回 INVALID"));
        cases.add(() -> assertTrue(latchAfterLow, "斷電不清 latch"));
        cases.add(() -> assertEquals(ChamberState.IDLE, repowered, "恢復供電仍是 IDLE"));
        int startsBeforeUnlock = actions.starts;
        cases.add(() -> assertEquals(0, startsBeforeUnlock, "整段 latch 期間都沒有 start"));
        actions.rearm = false;
        actions.startResult = ChamberSessionGateway.StartResult.STAGING;
        var unlocked = ChamberPowerCoordinator.reconcile(registry, uuid, true, repowered, eligible, actions);
        int startsAfterUnlock = actions.starts;
        cases.add(() -> assertEquals(ChamberState.READY, unlocked, "玩家親手關門清除 latch 之後同樣的條件就出發"));
        cases.add(() -> assertEquals(1, startsAfterUnlock));
        assertAll(cases);
    }

    /**
     * spec v1.6 D：presence 為 COLLAPSE 或 BUSY、原艙停用、實際 LOW，而且只剩永久封存時，power state 設為 OFF、回 INVALID（比較器 0），不呼叫 returnToOrigin 或 start。
     * 其他組合（enabled、已供電、不是只剩永久封存）照既有規則：POWERED 或 RETURNING、ARMED；PASSAGE 一律不適用。OFF 之後重新供電或啟用就回到既有保護。
     */
    @Test void permanentSealOnlyReachesOffWhenDisabledAndLow() {
        var cases = new ArrayList<Executable>();
        for (var presence : List.of(ChamberSessionGateway.Presence.COLLAPSE, ChamberSessionGateway.Presence.BUSY, ChamberSessionGateway.Presence.PASSAGE))
            for (var prior : ChamberPowerState.values()) for (boolean powered : List.of(true, false)) for (boolean enabled : List.of(true, false))
                for (boolean sealedOnly : List.of(true, false)) {
                    var registry = new ChamberRegistry();
                    var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH)).chamberUuid();
                    registry.setPowerState(uuid, prior); registry.setEnabled(uuid, enabled);
                    var actions = new Actions(registry, uuid);
                    actions.presence = presence; actions.sealedOnly = sealedOnly;
                    var next = ChamberPowerCoordinator.reconcile(registry, uuid, powered, ChamberState.ARMED,
                            () -> new ArmAttemptResult(true, ChamberState.READY, PARTICIPANTS, Set.of()), actions);
                    var power = registry.records().get(uuid).powerState();
                    var label = presence + " prior=" + prior + " powered=" + powered + " enabled=" + enabled + " sealedOnly=" + sealedOnly;
                    boolean release = presence != ChamberSessionGateway.Presence.PASSAGE && !powered && !enabled && sealedOnly;
                    var expected = release ? ChamberState.INVALID
                            : presence == ChamberSessionGateway.Presence.PASSAGE && powered && enabled ? ChamberState.PASSAGE : ChamberState.ARMED;
                    var expectedPower = release ? ChamberPowerState.OFF : powered && enabled ? ChamberPowerState.POWERED : ChamberPowerState.RETURNING;
                    int returns = actions.returns, starts = actions.starts;
                    cases.add(() -> assertEquals(expected, next, label));
                    cases.add(() -> assertEquals(release ? 0 : expected == ChamberState.PASSAGE ? 15 : 11, ChamberStatusSignal.forState(next), "比較器：" + label));
                    cases.add(() -> assertEquals(expectedPower, power, label));
                    cases.add(() -> assertEquals(0, returns, "不呼叫 returnToOrigin：" + label));
                    cases.add(() -> assertEquals(0, starts, "不開新 session：" + label));
                }
        // OFF 之後重新供電、或重新啟用：回到既有保護。
        var registry = new ChamberRegistry();
        var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH)).chamberUuid();
        registry.setEnabled(uuid, false);
        var actions = new Actions(registry, uuid);
        actions.presence = ChamberSessionGateway.Presence.COLLAPSE; actions.sealedOnly = true;
        var released = ChamberPowerCoordinator.reconcile(registry, uuid, false, ChamberState.ARMED, () -> null, actions);
        var releasedPower = registry.records().get(uuid).powerState();
        var repowered = ChamberPowerCoordinator.reconcile(registry, uuid, true, released, () -> null, actions);
        var repoweredPower = registry.records().get(uuid).powerState();
        registry.setEnabled(uuid, true);
        var reenabled = ChamberPowerCoordinator.reconcile(registry, uuid, false, repowered, () -> null, actions);
        var reenabledPower = registry.records().get(uuid).powerState();
        cases.add(() -> assertEquals(ChamberState.INVALID, released));
        cases.add(() -> assertEquals(ChamberPowerState.OFF, releasedPower));
        cases.add(() -> assertEquals(ChamberState.ARMED, repowered, "重新供電：回到保護"));
        cases.add(() -> assertEquals(ChamberPowerState.RETURNING, repoweredPower, "停用中重新供電：RETURNING"));
        cases.add(() -> assertEquals(ChamberState.ARMED, reenabled, "重新啟用（斷電中）：回到保護"));
        cases.add(() -> assertEquals(ChamberPowerState.RETURNING, reenabledPower));
        assertAll(cases);
    }

    /** latch 寫入 NBT 的 {@code RearmRequiresPlayerClose}：設定之後經 NBT 讀回仍為真；舊存檔缺這個 key 讀成 false（也會覆蓋記憶體中的真值）；清除之後寫出 false。 */
    @Test void nbtRoundTripAndLegacyDefault() {
        var original = controller();
        assertFalse(original.rearmRequiresPlayerClose(), "新的 block entity 沒有 latch");
        original.requireRearmPlayerClose();
        assertTrue(original.rearmRequiresPlayerClose());
        var encoded = original.createNbt(DynamicRegistryManager.EMPTY);
        assertTrue(encoded.contains("RearmRequiresPlayerClose") && encoded.getBoolean("RearmRequiresPlayerClose"), "latch 寫入 NBT");
        var restored = controller();
        restored.read(encoded, DynamicRegistryManager.EMPTY);
        assertTrue(restored.rearmRequiresPlayerClose(), "讀回仍為真（重啟、chunk 重新載入）");
        encoded.remove("RearmRequiresPlayerClose");
        var legacy = controller();
        legacy.read(encoded, DynamicRegistryManager.EMPTY);
        assertFalse(legacy.rearmRequiresPlayerClose(), "舊存檔缺 key 讀成 false");
        restored.read(encoded, DynamicRegistryManager.EMPTY);
        assertFalse(restored.rearmRequiresPlayerClose(), "讀入缺 key 的 NBT 覆蓋記憶體中的真值");
        restored.requireRearmPlayerClose();
        restored.clearRearmRequiresPlayerClose();
        assertFalse(restored.rearmRequiresPlayerClose());
        assertFalse(restored.createNbt(DynamicRegistryManager.EMPTY).getBoolean("RearmRequiresPlayerClose"), "清除之後寫出 false");
    }

    private static ChamberControllerBlockEntity controller() {
        return new ChamberControllerBlockEntity(new BlockPos(8, 64, 8), ModBlocks.CHAMBER_CONTROLLER.getDefaultState());
    }

    /** 一座艙的 {@code SessionActions}：{@code rearm} 是 Controller 上的 latch，{@code sealedOnly} 是 gateway 的「只剩永久封存」。 */
    private static final class Actions implements ChamberPowerCoordinator.SessionActions {
        private final ChamberRegistry registry;
        private final UUID uuid;
        ChamberSessionGateway.Presence presence = ChamberSessionGateway.Presence.NONE;
        ChamberSessionGateway.StartResult startResult = ChamberSessionGateway.StartResult.ARMED_ONLY;
        boolean rearm;
        boolean sealedOnly;
        boolean held;
        int starts;
        int returns;

        Actions(ChamberRegistry registry, UUID uuid) { this.registry = registry; this.uuid = uuid; }

        @Override public boolean activationBlocked() { return false; }
        @Override public boolean heldByStart() { return held; }
        @Override public void setHeldByStart(boolean value) { held = value; }
        @Override public boolean candidateRetryRequiresLow() { return false; }
        @Override public void clearCandidateRetryRequiresLow() { }
        @Override public boolean rearmRequiresPlayerClose() { return rearm; }
        @Override public boolean permanentlySealedOnly() { return sealedOnly; }
        @Override public ChamberSessionGateway.Presence presence() { return presence; }
        @Override public ChamberSessionGateway.StartResult start(List<UUID> participants) {
            assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
            starts++;
            return startResult;
        }
        @Override public boolean returnToOrigin() {
            returns++;
            presence = ChamberSessionGateway.Presence.NONE;
            return true;
        }
    }
}
