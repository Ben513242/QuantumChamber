package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class ChamberRegistryStateTest {
    @Test
    void schemaVersionTwoDecodesAndPreservesOffPowerState() {
        ChamberRegistryState state = new ChamberRegistryState();
        UUID uuid = state.registry().registerOrigin(Identifier.of("minecraft", "overworld"),
                DimensionRole.OVERWORLD, new ChamberFrame(new BlockPos(15, 70, 14), Direction.NORTH)).chamberUuid();
        NbtCompound input = state.writeNbt(new NbtCompound());
        input.putInt("SchemaVersion", 2);
        input.getList("Records", 10).getCompound(0).putString("PowerState", "OFF");

        ChamberRegistryState restored = ChamberRegistryState.fromNbt(input);

        assertTrue(restored.loadError().isEmpty());
        assertEquals(uuid, restored.registry().records().get(uuid).chamberUuid());
        assertEquals("OFF", restored.writeNbt(new NbtCompound())
                .getList("Records", 10).getCompound(0).getString("PowerState"));
    }

    @Test
    void schemaVersionTwoRoundTripsEveryPowerStateWithoutInferringEnabled() {
        for (String power : new String[] {"UNKNOWN", "OFF", "POWERED", "RETURNING"}) {
            NbtCompound encoded = validSchemaTwoState();
            NbtCompound record = encoded.getList("Records", 10).getCompound(0);
            record.putString("PowerState", power);
            record.putBoolean("Enabled", false);
            ChamberRegistryState restored = ChamberRegistryState.fromNbt(encoded);
            assertTrue(restored.loadError().isEmpty());
            NbtCompound output = restored.writeNbt(new NbtCompound()).getList("Records", 10).getCompound(0);
            assertEquals(power, output.getString("PowerState"));
            assertFalse(output.getBoolean("Enabled"));
        }
    }

    @Test
    void schemaVersionTwoRejectsMissingWrongTypeAndInvalidPowerState() {
        NbtCompound missing = validSchemaTwoState();
        missing.getList("Records", 10).getCompound(0).remove("PowerState");
        assertFailsClosed(missing);
        NbtCompound wrongType = validSchemaTwoState();
        wrongType.getList("Records", 10).getCompound(0).putInt("PowerState", 1);
        assertFailsClosed(wrongType);
        NbtCompound invalid = validSchemaTwoState();
        invalid.getList("Records", 10).getCompound(0).putString("PowerState", "ON");
        assertFailsClosed(invalid);
    }

    @Test
    void bothSchemasRejectDuplicateUuidAndMismatchedWorldRoleWithoutPartialState() {
        for (int version : new int[] {1, 2}) {
            NbtCompound duplicate = validSchemaTwoState();
            duplicate.putInt("SchemaVersion", version);
            NbtList records = duplicate.getList("Records", 10);
            records.add(records.getCompound(0).copy());
            assertFailsClosed(duplicate);
            NbtCompound mismatch = validSchemaTwoState();
            mismatch.putInt("SchemaVersion", version);
            mismatch.getList("Records", 10).getCompound(0).putString("OriginWorldKey", "minecraft:the_nether");
            assertFailsClosed(mismatch);
        }
    }

    @Test
    void healthGuardRejectsUnreadableRegistryButAllowsHealthyRegistry() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> new ChamberRegistryState().requireHealthy());
        NbtCompound invalid = new NbtCompound();
        invalid.putInt("SchemaVersion", 99);
        var failed = ChamberRegistryState.fromNbt(invalid);
        var exception = assertThrows(IllegalStateException.class, failed::requireHealthy);
        assertTrue(exception.getMessage().contains(failed.loadError().orElseThrow()));
    }

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
        encoded.putInt("SchemaVersion", 1);
        encoded.getList("Records", 10).getCompound(0).remove("PowerState");
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
        assertEquals(ChamberPowerState.UNKNOWN, record.powerState());
        assertEquals(2, restored.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
        assertEquals(chamberUuid, restored.registry().findAt(DimensionRole.NETHER, new BlockPos(16, 70, 0)).orElseThrow().chamberUuid());
    }

    @Test
    void unknownSchemaFailsClosedWithoutAllowingAnEmptyStateToOverwriteIt() {
        NbtCompound unknown = new NbtCompound();
        unknown.putInt("SchemaVersion", 3);

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
    void nonEmptyStringRecordsListFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        NbtList strings = new NbtList();
        strings.add(NbtString.of("bad-record"));
        encoded.put("Records", strings);

        assertFailsClosed(encoded);
    }

    @Test
    void nonEmptyIntRecordsListFailsClosed() {
        NbtCompound encoded = validSchemaOneState();
        NbtList integers = new NbtList();
        integers.add(NbtInt.of(42));
        encoded.put("Records", integers);

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
        NbtCompound encoded = state.writeNbt(new NbtCompound());
        encoded.putInt("SchemaVersion", 1);
        encoded.getList("Records", 10).getCompound(0).remove("PowerState");
        return encoded;
    }

    private static NbtCompound validSchemaTwoState() {
        NbtCompound encoded = validSchemaOneState();
        encoded.putInt("SchemaVersion", 2);
        encoded.getList("Records", 10).getCompound(0).putString("PowerState", "OFF");
        return encoded;
    }

    private static void assertFailsClosed(NbtCompound encoded) {
        ChamberRegistryState restored = ChamberRegistryState.fromNbt(encoded);
        assertTrue(restored.loadError().isPresent());
        assertTrue(restored.registry().records().isEmpty());
        assertThrows(IllegalStateException.class, () -> restored.writeNbt(new NbtCompound()));
    }
}
