package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberRegistryTest {
    @Test
    void powerChangesDirtyOnlyOnChangeAndRetainCollisionIndex() {
        ChamberRegistryState state = new ChamberRegistryState();
        ChamberRegistry registry = state.registry();
        ChamberFrame frame = new ChamberFrame(new BlockPos(15, 70, 14), Direction.NORTH);
        UUID uuid = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, frame).chamberUuid();
        state.setDirty(false);
        assertFalse(registry.setPowerState(uuid, ChamberPowerState.UNKNOWN));
        assertFalse(state.isDirty());
        assertFalse(registry.setPowerState(UUID.randomUUID(), ChamberPowerState.OFF));
        assertFalse(state.isDirty());
        assertTrue(registry.setPowerState(uuid, ChamberPowerState.OFF));
        assertTrue(state.isDirty());
        state.setDirty(false);
        assertFalse(registry.setPowerState(uuid, ChamberPowerState.OFF));
        assertFalse(state.isDirty());
        assertEquals(ChamberPowerState.OFF, registry.findAt(DimensionRole.OVERWORLD, frame.controllerPos()).orElseThrow().powerState());
        assertEquals(ChamberRegistrationResult.Status.OVERLAP,
                registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                        new ChamberFrame(new BlockPos(16, 70, 14), Direction.NORTH)).status());
        assertEquals(uuid, registry.findOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, frame).orElseThrow().chamberUuid());
    }

    @Test
    void changingEnabledPreservesIndependentPowerState() {
        ChamberRegistry registry = new ChamberRegistry();
        UUID uuid = UUID.randomUUID();
        registry.putLoaded(new ChamberRecord(uuid, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new BlockPos(0, 70, 0), Direction.NORTH, ChamberInstanceKind.ORIGIN, true, false, ChamberPowerState.POWERED));
        assertTrue(new ChamberLifecycleService(registry).setEnabled(uuid, false));
        assertEquals(ChamberPowerState.POWERED, registry.records().get(uuid).powerState());
        assertFalse(registry.records().get(uuid).enabled());
    }

    @Test
    void rejectsOverlappingOriginInSameDimensionRoleButAllowsSameCoordinatesInAnotherRole() {
        ChamberRegistry registry = new ChamberRegistry();
        ChamberFrame first = new ChamberFrame(new BlockPos(0, 70, 0), Direction.NORTH);
        ChamberFrame overlapping = new ChamberFrame(new BlockPos(5, 70, 0), Direction.NORTH);

        assertEquals(ChamberRegistrationResult.Status.CREATED,
                registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, first).status());
        assertEquals(ChamberRegistrationResult.Status.OVERLAP,
                registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, overlapping).status());
        assertEquals(ChamberRegistrationResult.Status.CREATED,
                registry.registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, overlapping).status());
    }

    @Test
    void retryingSameControllerWorldAndFacingReturnsExistingUuid() {
        ChamberRegistry registry = new ChamberRegistry();
        ChamberFrame frame = new ChamberFrame(new BlockPos(32, 70, -48), Direction.EAST);

        ChamberRegistrationResult first = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, frame);
        ChamberRegistrationResult retry = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, frame);

        assertEquals(ChamberRegistrationResult.Status.CREATED, first.status());
        assertEquals(ChamberRegistrationResult.Status.EXISTING, retry.status());
        assertEquals(first.chamberUuid(), retry.chamberUuid());
    }

    @Test
    void rejectsOverlapThatOnlyTouchesAtCrossChunkBoundary() {
        ChamberRegistry registry = new ChamberRegistry();
        ChamberFrame chunkZero = new ChamberFrame(new BlockPos(10, 70, 0), Direction.NORTH);
        ChamberFrame chunkOne = new ChamberFrame(new BlockPos(16, 70, 0), Direction.NORTH);

        assertEquals(ChamberRegistrationResult.Status.CREATED,
                registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, chunkZero).status());
        assertEquals(ChamberRegistrationResult.Status.OVERLAP,
                registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, chunkOne).status());
    }

    @Test
    void lifecycleChangesEnabledWithoutChangingPersistedIdentity() {
        ChamberRegistry registry = new ChamberRegistry();
        ChamberRegistrationResult registered = registry.registerOrigin(
                World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new ChamberFrame(new BlockPos(0, 70, 0), Direction.NORTH));
        ChamberLifecycleService lifecycle = new ChamberLifecycleService(registry);

        assertTrue(lifecycle.setEnabled(registered.chamberUuid(), false));

        ChamberRecord record = registry.records().get(registered.chamberUuid());
        assertFalse(record.enabled());
        assertEquals(registered.chamberUuid(), record.chamberUuid());
        assertEquals(Identifier.of("minecraft", "overworld"), record.originWorldKey());
        assertFalse(record.destroyed());
    }
}
