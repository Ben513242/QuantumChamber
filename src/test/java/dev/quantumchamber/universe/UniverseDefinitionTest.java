package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;

class UniverseDefinitionTest {
    private static final UniverseId ID = UniverseId.of(
            UUID.fromString("00000000-0000-0000-0000-000000000123"));

    @Test
    void schemaOneRejectsNonOverworldAndMismatchedKey() {
        assertThrows(IllegalArgumentException.class, () -> fixture(DimensionRole.NETHER));
        assertThrows(IllegalArgumentException.class, () -> fixtureWithKey(Identifier.of("quantumchamber", "wrong")));
    }

    @Test
    void schemaOneRequiresItsExactRoleProfilePolicyAndStorageVersion() {
        assertThrows(IllegalArgumentException.class, () -> definition(
                descriptor(DimensionRole.OVERWORLD, GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1,
                        SeedPolicy.SHARED_SAVE_SEED_V1, 2)));
        assertThrows(IllegalArgumentException.class, () -> new UniverseDefinition(2, ID, 7L, Map.of(
                DimensionRole.OVERWORLD, descriptor(DimensionRole.OVERWORLD,
                        GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, SeedPolicy.SHARED_SAVE_SEED_V1, 1))));
        assertThrows(IllegalArgumentException.class, () -> new UniverseDefinition(1, ID, 7L, Map.of(
                DimensionRole.NETHER, descriptor(DimensionRole.OVERWORLD,
                        GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, SeedPolicy.SHARED_SAVE_SEED_V1, 1))));
    }

    @Test
    void definitionDefensivelyCopiesItsWorldMap() {
        var worlds = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        var descriptor = descriptor(DimensionRole.OVERWORLD,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, SeedPolicy.SHARED_SAVE_SEED_V1, 1);
        worlds.put(DimensionRole.OVERWORLD, descriptor);

        var definition = new UniverseDefinition(1, ID, 7L, worlds);
        worlds.clear();

        assertEquals(Map.of(DimensionRole.OVERWORLD, descriptor), definition.worlds());
        assertThrows(UnsupportedOperationException.class, () -> definition.worlds().clear());
    }

    @Test
    void schemaOneCodecRoundTripsTheExactDefinitionAndAvailability() {
        for (var availability : DesiredAvailability.values()) {
            var record = new UniverseRecord(fixture(DimensionRole.OVERWORLD), availability);
            var encoded = record.toNbt();

            assertEquals(NbtElement.INT_TYPE, encoded.get("SchemaVersion").getType());
            assertEquals(NbtElement.INT_ARRAY_TYPE, encoded.get("UniverseUuid").getType());
            assertEquals(NbtElement.LONG_TYPE, encoded.get("AllocationOrdinal").getType());
            assertEquals(NbtElement.STRING_TYPE, encoded.get("DesiredAvailability").getType());
            assertEquals(NbtElement.LIST_TYPE, encoded.get("Worlds").getType());
            assertEquals(record, UniverseRecord.fromNbt(encoded));
        }
    }

    @Test
    void codecRejectsMissingOrWrongRootFieldTypes() {
        var encoded = record().toNbt();
        assertRejectedForEachMutation(encoded,
                nbt -> nbt.putString("SchemaVersion", "1"),
                nbt -> nbt.putString("UniverseUuid", ID.value().toString()),
                nbt -> nbt.putString("AllocationOrdinal", "7"),
                nbt -> nbt.putInt("DesiredAvailability", 1),
                nbt -> nbt.putString("Worlds", "not-a-list"));

        for (var field : new String[] {
                "SchemaVersion", "UniverseUuid", "AllocationOrdinal", "DesiredAvailability", "Worlds"
        }) {
            var missing = encoded.copy();
            missing.remove(field);
            assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(missing));
        }
    }

    @Test
    void codecRejectsWrongWorldFieldTypesAndNonCompoundLists() {
        var encoded = record().toNbt();
        assertRejectedForEachWorldMutation(encoded,
                world -> world.putInt("WorldKey", 1),
                world -> world.putInt("Role", 1),
                world -> world.putInt("GeneratorProfile", 1),
                world -> world.putInt("SeedPolicy", 1),
                world -> world.putString("StoragePolicyVersion", "1"));

        var strings = encoded.copy();
        var worlds = new NbtList();
        worlds.add(NbtString.of("not-a-compound"));
        strings.put("Worlds", worlds);
        assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(strings));
    }

    @Test
    void codecRejectsUnknownSchemaEnumsAndDuplicateRoles() {
        var encoded = record().toNbt();
        assertRejectedForEachMutation(encoded,
                nbt -> nbt.putInt("SchemaVersion", 2),
                nbt -> nbt.putString("DesiredAvailability", "LAZY"));
        assertRejectedForEachWorldMutation(encoded,
                world -> world.putString("Role", "MOON"),
                world -> world.putString("GeneratorProfile", "CUSTOM"),
                world -> world.putString("SeedPolicy", "OWN_SEED_V2"));

        var duplicate = encoded.copy();
        var worlds = duplicate.getList("Worlds", NbtElement.COMPOUND_TYPE);
        worlds.add(worlds.getCompound(0).copy());
        assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(duplicate));
    }

    private static UniverseRecord record() {
        return new UniverseRecord(fixture(DimensionRole.OVERWORLD), DesiredAvailability.EAGER_ENABLED);
    }

    private static UniverseDefinition fixture(DimensionRole role) {
        return definition(descriptor(role, GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1,
                SeedPolicy.SHARED_SAVE_SEED_V1, 1));
    }

    private static UniverseDefinition fixtureWithKey(Identifier key) {
        return definition(new UniverseWorldDescriptor(
                RegistryKey.of(RegistryKeys.WORLD, key), DimensionRole.OVERWORLD,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, SeedPolicy.SHARED_SAVE_SEED_V1, 1));
    }

    private static UniverseDefinition definition(UniverseWorldDescriptor descriptor) {
        return new UniverseDefinition(1, ID, 7L, Map.of(descriptor.role(), descriptor));
    }

    private static UniverseWorldDescriptor descriptor(DimensionRole role, GeneratorProfile profile,
            SeedPolicy seedPolicy, int storagePolicyVersion) {
        return new UniverseWorldDescriptor(UniverseKeys.world(ID, role), role, profile, seedPolicy, storagePolicyVersion);
    }

    @SafeVarargs
    private static void assertRejectedForEachMutation(NbtCompound source, Consumer<NbtCompound>... mutations) {
        for (var mutation : mutations) {
            var changed = source.copy();
            mutation.accept(changed);
            assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(changed));
        }
    }

    @SafeVarargs
    private static void assertRejectedForEachWorldMutation(
            NbtCompound source, Consumer<NbtCompound>... mutations) {
        for (var mutation : mutations) {
            var changed = source.copy();
            mutation.accept(changed.getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0));
            assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(changed));
        }
    }
}
