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
    void seedPolicyRejectsUnknownValue() {
        assertEquals("EXPLICIT_ROOT_SEED_V1", SeedPolicy.valueOf("EXPLICIT_ROOT_SEED_V1").name());
        assertThrows(IllegalArgumentException.class, () -> SeedPolicy.valueOf("EXPLICIT_ROOT_SEED_V2"));
        var legacy = record().toNbt();
        assertRejectedForEachWorldMutation(legacy,
                world -> world.putString("SeedPolicy", "EXPLICIT_ROOT_SEED_V1"),
                world -> world.putString("SeedPolicy", "EXPLICIT_ROOT_SEED_V2"));
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

    @Test void catalogRejectsWrongProvenanceRoleShape() {
        var encoded = newM6RecordNbt();
        assertEquals(encoded, UniverseRecord.fromNbt(encoded).toNbt());
        assertRejectedForEachMutation(encoded,
                nbt -> nbt.putString("Provenance", "UNKNOWN"),
                nbt -> nbt.putByteArray("LegacySchemaOneDigest", new byte[32]),
                nbt -> nbt.putString("Provenance", "MIGRATED_SCHEMA1"),
                nbt -> nbt.remove("RootWorldSeed"),
                nbt -> nbt.getList("Worlds", NbtElement.COMPOUND_TYPE).remove(2));
        assertRejectedForEachWorldMutation(encoded,
                world -> world.putString("SeedPolicy", "SHARED_SAVE_SEED_V1"),
                world -> world.putString("GeneratorProfile", "VANILLA_OVERWORLD_SHARED_SEED_V1"),
                world -> world.putString("WorldKey", "quantumchamber:other"));
    }

    @Test
    void migratedSchemaOneRejectsUnknownProfileUntilTask15() throws Exception {
        var legacy = record().toNbt();
        assertEquals(legacy, UniverseRecord.fromNbt(legacy).toNbt());
        var fresh = newM6RecordNbt();
        assertEquals(fresh, UniverseRecord.fromNbt(fresh).toNbt());

        var migrated = migratedSchemaOneNbt();
        migrated.putString("ProfileId", "quantumchamber:unknown");
        assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(migrated));
    }

    @Test
    void migratedSchemaOneRejectsEvenCurrentNewProfileUntilTask15() throws Exception {
        var migrated = migratedSchemaOneNbt();
        assertEquals("quantumchamber:random_vanilla_v1", migrated.getString("ProfileId"));
        assertThrows(IllegalArgumentException.class, () -> UniverseRecord.fromNbt(migrated));
    }

    private static NbtCompound migratedSchemaOneNbt() throws Exception {
        var legacy = record().toNbt();
        var definition = legacy.copy();
        definition.remove("DesiredAvailability");
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(Minecraft121SettingsGraph.canonicalNbt(definition, 1024 * 1024));
        var migrated = newM6RecordNbt();
        migrated.putString("Provenance", "MIGRATED_SCHEMA1");
        migrated.putByteArray("LegacySchemaOneDigest", digest);
        var worlds = migrated.getList("Worlds", NbtElement.COMPOUND_TYPE);
        worlds.set(0, legacy.getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0).copy());
        assertEquals(32, digest.length);
        assertEquals(3, worlds.size());
        assertEquals(legacy.getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0), worlds.getCompound(0));
        return migrated;
    }

    private static NbtCompound newM6RecordNbt() {
        var encoded = record().toNbt();
        encoded.putInt("SchemaVersion", 2);
        encoded.putString("Provenance", "NEW_M6");
        encoded.putLong("RootWorldSeed", -735L);
        encoded.putString("ProfileId", "quantumchamber:random_vanilla_v1");
        encoded.putInt("ProfileVersion", 1);
        var snapshot = new NbtCompound();
        snapshot.putInt("SnapshotVersion", 1);
        snapshot.putString("ProviderId", "quantumchamber:vanilla_normal_v1");
        snapshot.putInt("ProviderVersion", 1);
        var parameters = new NbtCompound();
        parameters.putString("OverworldPreset", "normal");
        parameters.putBoolean("GenerateStructures", true);
        snapshot.put("Parameters", parameters);
        snapshot.putByteArray("OutputFingerprint", new byte[32]);
        encoded.put("TargetSettingsSnapshot", snapshot);
        var roles = new NbtList();
        for (var role : DimensionRole.values()) {
            var world = new NbtCompound();
            world.putString("WorldKey", UniverseKeys.world(ID, role).getValue().toString());
            world.putString("Role", role.name());
            world.putString("SeedPolicy", "EXPLICIT_ROOT_SEED_V1");
            world.putInt("StoragePolicyVersion", 1);
            roles.add(world);
        }
        encoded.put("Worlds", roles);
        return encoded;
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
