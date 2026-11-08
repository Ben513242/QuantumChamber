package dev.quantumchamber.universe;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import java.util.EnumMap;
import java.util.Set;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public record UniverseRecord(UniverseDefinition definition, DesiredAvailability desiredAvailability, Generation generation) {
    public enum Provenance { NEW_M6, MIGRATED_SCHEMA1 }

    /** Task15 尚未定義遷移 profile 契約；目前只接受 NEW_M6 的生成權威。 */
    public record Generation(long rootWorldSeed, String profileId, int profileVersion, NbtCompound targetSettingsSnapshot,
            Provenance provenance, CandidateBytes legacySchemaOneDigest) {
        public Generation {
            Objects.requireNonNull(provenance, "provenance");
            if (provenance == Provenance.MIGRATED_SCHEMA1) {
                throw new IllegalArgumentException("MIGRATED_SCHEMA1 的 profile 契約待 Task15 定義，目前拒絕載入");
            }
            if (profileVersion != 1 || Identifier.tryParse(profileId) == null || !profileId.contains(":")
                    || (provenance == Provenance.MIGRATED_SCHEMA1) != (legacySchemaOneDigest != null)) {
                throw new IllegalArgumentException("schema2 profile 或 provenance 不符");
            }
            var snapshot = GenerationSettingsSnapshot.fromNbt(targetSettingsSnapshot);
            var preset = snapshot.parameters().getString("OverworldPreset");
            if (provenance == Provenance.NEW_M6 && (snapshot.providerVersion() != 1
                    || !GenerationSettingsSnapshot.isBuiltin(snapshot.providerId())
                    || !snapshot.providerId().equals("quantumchamber:vanilla_" + preset + "_v1")
                    || !(NewUniverseIntent.SAME_SEED_PROFILE_ID.equals(profileId)
                    || NewUniverseIntent.RANDOM_PROFILE_ID.equals(profileId) && preset.equals("normal")))) {
                throw new IllegalArgumentException("NEW_M6 profile 與受支援目標設定不符");
            }
            targetSettingsSnapshot = snapshot.toNbt();
        }

        @Override public NbtCompound targetSettingsSnapshot() { return targetSettingsSnapshot.copy(); }
    }

    public UniverseRecord(UniverseDefinition definition, DesiredAvailability availability) {
        this(definition, availability, null);
    }

    public UniverseRecord {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(desiredAvailability, "desiredAvailability");
        if ((definition.schemaVersion() == 2) != (generation != null)) {
            throw new IllegalArgumentException("schema 與 generation 欄位不符");
        }
        if (generation != null) {
            for (var descriptor : definition.worlds().values()) {
                boolean shared = generation.provenance() == Provenance.MIGRATED_SCHEMA1 && descriptor.role() == DimensionRole.OVERWORLD;
                if (shared != (descriptor.seedPolicy() == SeedPolicy.SHARED_SAVE_SEED_V1)) {
                    throw new IllegalArgumentException("provenance 與 role seed policy 不符");
                }
            }
        }
    }

    public NbtCompound toNbt() {
        var nbt = new NbtCompound();
        nbt.putInt("SchemaVersion", definition.schemaVersion());
        nbt.putUuid("UniverseUuid", definition.universeId().value());
        nbt.putLong("AllocationOrdinal", definition.allocationOrdinal());
        nbt.putString("DesiredAvailability", desiredAvailability.name());

        if (generation != null) {
            nbt.putLong("RootWorldSeed", generation.rootWorldSeed());
            nbt.putString("ProfileId", generation.profileId());
            nbt.putInt("ProfileVersion", generation.profileVersion());
            nbt.put("TargetSettingsSnapshot", generation.targetSettingsSnapshot());
            nbt.putString("Provenance", generation.provenance().name());
            if (generation.legacySchemaOneDigest() != null) nbt.putByteArray("LegacySchemaOneDigest", generation.legacySchemaOneDigest().bytes());
        }

        var worlds = new NbtList();
        for (var descriptor : definition.worlds().values()) {
            var world = new NbtCompound();
            world.putString("WorldKey", descriptor.worldKey().getValue().toString());
            world.putString("Role", descriptor.role().name());
            if (descriptor.generatorProfile() != null) world.putString("GeneratorProfile", descriptor.generatorProfile().name());
            world.putString("SeedPolicy", descriptor.seedPolicy().name());
            world.putInt("StoragePolicyVersion", descriptor.storagePolicyVersion());
            worlds.add(world);
        }
        nbt.put("Worlds", worlds);
        return nbt;
    }

    public static UniverseRecord fromNbt(NbtCompound nbt) {
        Objects.requireNonNull(nbt, "nbt");
        requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        int schemaVersion = nbt.getInt("SchemaVersion");
        if (schemaVersion != 1 && schemaVersion != 2) {
            throw new IllegalArgumentException("不支援 Universe schema: " + schemaVersion);
        }

        Generation generation = null;
        if (schemaVersion == 2) {
            var provenance = parseEnum(Provenance.class, string(nbt, "Provenance"), "Provenance");
            var keys = new java.util.HashSet<>(Set.of("SchemaVersion", "UniverseUuid", "AllocationOrdinal", "DesiredAvailability",
                    "RootWorldSeed", "ProfileId", "ProfileVersion", "TargetSettingsSnapshot", "Provenance", "Worlds"));
            CandidateBytes digest = null;
            if (provenance == Provenance.MIGRATED_SCHEMA1) {
                keys.add("LegacySchemaOneDigest");
                requireType(nbt, "LegacySchemaOneDigest", NbtElement.BYTE_ARRAY_TYPE);
                digest = new CandidateBytes(nbt.getByteArray("LegacySchemaOneDigest"));
            }
            if (!nbt.getKeys().equals(keys)) throw new IllegalArgumentException("schema2 catalog record 欄位不符");
            requireType(nbt, "RootWorldSeed", NbtElement.LONG_TYPE);
            requireType(nbt, "ProfileVersion", NbtElement.INT_TYPE);
            requireType(nbt, "TargetSettingsSnapshot", NbtElement.COMPOUND_TYPE);
            generation = new Generation(nbt.getLong("RootWorldSeed"), string(nbt, "ProfileId"), nbt.getInt("ProfileVersion"),
                    nbt.getCompound("TargetSettingsSnapshot"), provenance, digest);
        }
        requireType(nbt, "UniverseUuid", NbtElement.INT_ARRAY_TYPE);
        if (!nbt.containsUuid("UniverseUuid")) {
            throw new IllegalArgumentException("UniverseUuid 必須是有效 UUID");
        }
        var universeId = UniverseId.of(nbt.getUuid("UniverseUuid"));

        requireType(nbt, "AllocationOrdinal", NbtElement.LONG_TYPE);
        long allocationOrdinal = nbt.getLong("AllocationOrdinal");
        var availability = parseEnum(
                DesiredAvailability.class, string(nbt, "DesiredAvailability"), "DesiredAvailability");

        requireType(nbt, "Worlds", NbtElement.LIST_TYPE);
        var rawWorlds = (NbtList) nbt.get("Worlds");
        if (!rawWorlds.isEmpty() && rawWorlds.getHeldType() != NbtElement.COMPOUND_TYPE) {
            throw new IllegalArgumentException("Worlds 必須包含 compound");
        }

        var worlds = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        for (int index = 0; index < rawWorlds.size(); index++) {
            var world = rawWorlds.getCompound(index);
            var keyId = Identifier.tryParse(string(world, "WorldKey"));
            if (keyId == null) {
                throw new IllegalArgumentException("WorldKey 不是合法 identifier");
            }
            var role = parseEnum(DimensionRole.class, string(world, "Role"), "Role");
            boolean shared = generation == null || (generation.provenance() == Provenance.MIGRATED_SCHEMA1 && role == DimensionRole.OVERWORLD);
            if (generation != null && !world.getKeys().equals(shared
                    ? Set.of("WorldKey", "Role", "SeedPolicy", "StoragePolicyVersion", "GeneratorProfile")
                    : Set.of("WorldKey", "Role", "SeedPolicy", "StoragePolicyVersion"))) {
                throw new IllegalArgumentException("schema2 role 欄位不符");
            }
            var profile = shared ? parseEnum(GeneratorProfile.class, string(world, "GeneratorProfile"), "GeneratorProfile") : null;
            var seedPolicy = parseEnum(SeedPolicy.class, string(world, "SeedPolicy"), "SeedPolicy");
            requireType(world, "StoragePolicyVersion", NbtElement.INT_TYPE);
            var descriptor = new UniverseWorldDescriptor(
                    RegistryKey.of(RegistryKeys.WORLD, keyId), role, profile, seedPolicy,
                    world.getInt("StoragePolicyVersion"));
            if (worlds.put(role, descriptor) != null) {
                throw new IllegalArgumentException("Worlds 含重複 role: " + role);
            }
        }

        var definition = new UniverseDefinition(schemaVersion, universeId, allocationOrdinal, worlds);
        return new UniverseRecord(definition, availability, generation);
    }

    private static String string(NbtCompound nbt, String key) {
        requireType(nbt, key, NbtElement.STRING_TYPE);
        return nbt.getString(key);
    }

    private static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) {
            throw new IllegalArgumentException(key + " 缺失或 NBT 型別錯誤");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " 值未知: " + value, exception);
        }
    }
}
