package dev.quantumchamber.universe;

import java.util.EnumMap;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

public record UniverseRecord(UniverseDefinition definition, DesiredAvailability desiredAvailability) {
    public UniverseRecord {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(desiredAvailability, "desiredAvailability");
    }

    public NbtCompound toNbt() {
        var nbt = new NbtCompound();
        nbt.putInt("SchemaVersion", definition.schemaVersion());
        nbt.putUuid("UniverseUuid", definition.universeId().value());
        nbt.putLong("AllocationOrdinal", definition.allocationOrdinal());
        nbt.putString("DesiredAvailability", desiredAvailability.name());

        var worlds = new NbtList();
        for (var descriptor : definition.worlds().values()) {
            var world = new NbtCompound();
            world.putString("WorldKey", descriptor.worldKey().getValue().toString());
            world.putString("Role", descriptor.role().name());
            world.putString("GeneratorProfile", descriptor.generatorProfile().name());
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
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("不支援 Universe schema: " + schemaVersion);
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
            var profile = parseEnum(
                    GeneratorProfile.class, string(world, "GeneratorProfile"), "GeneratorProfile");
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
        return new UniverseRecord(definition, availability);
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
