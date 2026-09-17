package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.universe.DimensionRole;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberLifecycleServiceTest {
    private static final ChamberFrame FRAME = new ChamberFrame(new BlockPos(15, 70, 14), Direction.NORTH);

    @Test
    void rejectsEveryMismatchedAuthorityWithoutChangingRecordOrInvokingRemoval() {
        var registry = new ChamberRegistry();
        var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME).chamberUuid();
        var service = new ChamberLifecycleService(registry);
        var original = registry.records().get(uuid);
        var attempts = new AtomicInteger();
        var invalid = List.of(
                new ChamberOriginAuthority(UUID.randomUUID(), World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), Direction.NORTH, ChamberInstanceKind.ORIGIN),
                new ChamberOriginAuthority(uuid, World.NETHER.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), Direction.NORTH, ChamberInstanceKind.ORIGIN),
                new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.NETHER, FRAME.controllerPos(), Direction.NORTH, ChamberInstanceKind.ORIGIN),
                new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos().east(), Direction.NORTH, ChamberInstanceKind.ORIGIN),
                new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), Direction.SOUTH, ChamberInstanceKind.ORIGIN),
                new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME.controllerPos(), Direction.NORTH, ChamberInstanceKind.PROJECTION));
        for (var authority : invalid) {
            assertFalse(service.setOriginEnabled(authority, false));
            assertFalse(service.dismantleOrigin(authority, () -> { attempts.incrementAndGet(); return true; }));
            assertEquals(original, registry.records().get(uuid));
        }
        assertEquals(0, attempts.get());
    }

    @Test
    void rejectsDestroyedOrProjectionRecordsEvenWhenAuthorityMatches() {
        for (var kind : ChamberInstanceKind.values()) {
            var registry = new ChamberRegistry();
            var uuid = UUID.randomUUID();
            var record = new ChamberRecord(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                    FRAME.controllerPos(), Direction.NORTH, kind, false, kind == ChamberInstanceKind.ORIGIN);
            registry.putLoaded(record);
            var service = new ChamberLifecycleService(registry);
            var authority = new ChamberOriginAuthority(uuid, record.originWorldKey(), record.originDimensionRole(),
                    record.anchorPos(), record.facing(), kind);
            assertFalse(service.setOriginEnabled(authority, true));
            assertFalse(service.dismantleOrigin(authority, () -> fail("無效紀錄不得呼叫移除")));
            assertEquals(record, registry.records().get(uuid));
        }
    }

    @Test
    void requiresOffRegardlessOfEnabledAndRetainsIndexOnFalseOrThrow() {
        var registry = new ChamberRegistry();
        var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME).chamberUuid();
        var authority = authority(uuid);
        var service = new ChamberLifecycleService(registry);
        assertFalse(service.dismantleOrigin(authority, () -> fail("UNKNOWN 不得移除")));
        assertTrue(service.setOriginEnabled(authority, false));
        assertFalse(service.dismantleOrigin(authority, () -> true));
        registry.setPowerState(uuid, ChamberPowerState.OFF);
        var disabled = registry.records().get(uuid);
        assertFalse(service.dismantleOrigin(authority, () -> false));
        assertThrows(IllegalStateException.class, () -> service.dismantleOrigin(authority, () -> { throw new IllegalStateException("移除失敗"); }));
        assertEquals(disabled, registry.records().get(uuid));
        assertCovered(registry, uuid, true);
    }

    @Test
    void successfulRemovalClearsEveryCoveredChunkPreservesOtherRoleAndAllowsNewUuid() {
        var changes = new AtomicInteger();
        var registry = new ChamberRegistry(changes::incrementAndGet);
        var uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME).chamberUuid();
        var other = registry.registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, FRAME).chamberUuid();
        var service = new ChamberLifecycleService(registry);
        registry.setPowerState(uuid, ChamberPowerState.OFF);
        assertTrue(service.dismantleOrigin(authority(uuid), () -> {
            assertCovered(registry, uuid, true);
            return true;
        }));
        assertCovered(registry, uuid, false);
        for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) {
            var pos = new BlockPos(x, 68, z);
            assertEquals(other, registry.findAt(DimensionRole.NETHER, pos).orElseThrow().chamberUuid());
            assertTrue(indexOf(registry).candidates(DimensionRole.NETHER, pos).contains(other));
        }
        assertEquals(4, changes.get());
        var replacement = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME);
        assertEquals(ChamberRegistrationResult.Status.CREATED, replacement.status());
        assertNotEquals(uuid, replacement.chamberUuid());
    }

    @Test
    void disableAndDismantleSurviveNbtRoundTripsAndMarkDirty() {
        var state = new ChamberRegistryState();
        var uuid = state.registry().registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME).chamberUuid();
        state.setDirty(false);
        assertTrue(new ChamberLifecycleService(state.registry()).setOriginEnabled(authority(uuid), false));
        assertTrue(state.isDirty());
        var restored = ChamberRegistryState.fromNbt(state.writeNbt(new NbtCompound()));
        assertFalse(restored.registry().records().get(uuid).enabled());
        assertCovered(restored.registry(), uuid, true);
        restored.setDirty(false);
        restored.registry().setPowerState(uuid, ChamberPowerState.OFF);
        assertTrue(new ChamberLifecycleService(restored.registry()).dismantleOrigin(authority(uuid), () -> true));
        assertTrue(restored.isDirty());
        var removed = ChamberRegistryState.fromNbt(restored.writeNbt(new NbtCompound()));
        assertTrue(removed.registry().records().isEmpty());
        assertCovered(removed.registry(), uuid, false);
    }

    private static ChamberOriginAuthority authority(UUID uuid) {
        return new ChamberOriginAuthority(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                FRAME.controllerPos(), Direction.NORTH, ChamberInstanceKind.ORIGIN);
    }

    private static void assertCovered(ChamberRegistry registry, UUID uuid, boolean present) {
        // 手算 bounds x=12..18、y=64..70、z=14..20，跨四個 chunk。
        for (int x : new int[] {15, 16}) for (int z : new int[] {15, 16}) {
            var found = registry.findAt(DimensionRole.OVERWORLD, new BlockPos(x, 68, z));
            assertEquals(present, found.isPresent());
            if (present) assertEquals(uuid, found.orElseThrow().chamberUuid());
            assertEquals(present, indexOf(registry).candidates(DimensionRole.OVERWORLD, new BlockPos(x, 68, z)).contains(uuid));
        }
    }

    private static ProjectionIndex indexOf(ChamberRegistry registry) {
        // 僅測試端取得真索引，不為斷言新增 gameplay／cleanup 介面。
        try {
            var field = ChamberRegistry.class.getDeclaredField("projectionIndex");
            field.setAccessible(true);
            return (ProjectionIndex) field.get(registry);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("無法檢查真 Registry 索引", exception);
        }
    }
}
