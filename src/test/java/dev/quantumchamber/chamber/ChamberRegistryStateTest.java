package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberRegistryStateTest {
    @Test
    void schemaVersionOneRoundTripPreservesEveryChamberFieldAndRebuildsIndex() {
        ChamberRegistryState state = new ChamberRegistryState();
        ChamberRegistrationResult registered = state.registry().registerOrigin(
                World.NETHER.getValue(), DimensionRole.NETHER,
                new ChamberFrame(new BlockPos(16, 70, 0), Direction.SOUTH));
        UUID chamberUuid = registered.chamberUuid();
        assertTrue(state.isDirty());
        state.setDirty(false);
        assertTrue(new ChamberLifecycleService(state.registry()).setEnabled(chamberUuid, false));
        assertTrue(state.isDirty());

        NbtCompound encoded = state.writeNbt(new NbtCompound());
        encoded.getList("Records", 10).getCompound(0).putBoolean("Destroyed", true);
        ChamberRegistryState restored = ChamberRegistryState.fromNbt(encoded);
        ChamberRecord record = restored.registry().records().get(chamberUuid);

        assertEquals(1, encoded.getInt("SchemaVersion"));
        assertEquals(chamberUuid, record.chamberUuid());
        assertEquals(Identifier.of("minecraft", "the_nether"), record.originWorldKey());
        assertEquals(DimensionRole.NETHER, record.originDimensionRole());
        assertEquals(new BlockPos(16, 70, 0), record.anchorPos());
        assertEquals(Direction.SOUTH, record.facing());
        assertEquals(ChamberInstanceKind.ORIGIN, record.instanceKind());
        assertFalse(record.enabled());
        assertTrue(record.destroyed());
        assertEquals(chamberUuid, restored.registry().findAt(DimensionRole.NETHER, new BlockPos(16, 70, 0)).orElseThrow().chamberUuid());
    }

    @Test
    void unknownSchemaFailsClosedWithoutAllowingAnEmptyStateToOverwriteIt() {
        NbtCompound unknown = new NbtCompound();
        unknown.putInt("SchemaVersion", 2);

        ChamberRegistryState state = ChamberRegistryState.fromNbt(unknown);

        assertTrue(state.loadError().isPresent());
        assertThrows(IllegalStateException.class, () -> state.writeNbt(new NbtCompound()));
    }

    @Test
    void missingAnchorPositionFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        encoded.getList("Records", 10).getCompound(0).remove("AnchorPos");

        assertFailsClosed(encoded);
    }

    @Test
    void missingEnabledAndDestroyedFlagsFailClosed() {
        NbtCompound encoded = validSchemaOneState();
        NbtCompound record = encoded.getList("Records", 10).getCompound(0);
        record.remove("Enabled");
        record.remove("Destroyed");

        assertFailsClosed(encoded);
    }

    @Test
    void nonByteBooleanFlagFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        encoded.getList("Records", 10).getCompound(0).putInt("Enabled", 1);

        assertFailsClosed(encoded);
    }

    @Test
    void nonListRecordsFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        encoded.putString("Records", "not-a-list");

        assertFailsClosed(encoded);
    }

    @Test
    void verticalFacingFailsClosedEvenThoughItIsADirectionEnumValue() {
        NbtCompound encoded = validSchemaOneState();
        encoded.getList("Records", 10).getCompound(0).putString("Facing", "UP");

        assertFailsClosed(encoded);
    }

    @Test
    void invalidDimensionRoleEnumFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        encoded.getList("Records", 10).getCompound(0).putString("OriginDimensionRole", "VOID");

        assertFailsClosed(encoded);
    }

    private static NbtCompound validSchemaOneState() {
        ChamberRegistryState state = new ChamberRegistryState();
        state.registry().registerOrigin(
                World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                new ChamberFrame(new BlockPos(0, 70, 0), Direction.NORTH));
        return state.writeNbt(new NbtCompound());
    }

    private static void assertFailsClosed(NbtCompound encoded) {
        ChamberRegistryState restored = ChamberRegistryState.fromNbt(encoded);
        assertTrue(restored.loadError().isPresent());
        assertThrows(IllegalStateException.class, () -> restored.writeNbt(new NbtCompound()));
    }
}
