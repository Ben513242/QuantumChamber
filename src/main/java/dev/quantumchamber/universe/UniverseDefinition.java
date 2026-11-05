package dev.quantumchamber.universe;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record UniverseDefinition(
        int schemaVersion,
        UniverseId universeId,
        long allocationOrdinal,
        Map<DimensionRole, UniverseWorldDescriptor> worlds) {
    private static final int SCHEMA_ONE = 1;
    private static final int STORAGE_POLICY_ONE = 1;

    public UniverseDefinition {
        if (schemaVersion != SCHEMA_ONE && schemaVersion != 2) {
            throw new IllegalArgumentException("不支援 Universe schema: " + schemaVersion);
        }
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(worlds, "worlds");

        var copy = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        copy.putAll(worlds);
        if (schemaVersion == SCHEMA_ONE) {
            if (copy.size() != 1 || !copy.containsKey(DimensionRole.OVERWORLD)
                    || copy.get(DimensionRole.OVERWORLD).seedPolicy() != SeedPolicy.SHARED_SAVE_SEED_V1) {
                throw new IllegalArgumentException("schema1 必須恰有一個 shared-seed OVERWORLD");
            }
        } else if (!copy.keySet().equals(java.util.EnumSet.allOf(DimensionRole.class))) {
            throw new IllegalArgumentException("schema2 必須恰有三個 role");
        }
        for (var entry : copy.entrySet()) {
            var descriptor = entry.getValue();
            if (descriptor.role() != entry.getKey()
                    || !UniverseKeys.world(universeId, entry.getKey()).equals(descriptor.worldKey())
                    || descriptor.storagePolicyVersion() != STORAGE_POLICY_ONE) {
                throw new IllegalArgumentException("descriptor 的 UUID、role 或 storage policy 不符");
            }
            if (descriptor.seedPolicy() == SeedPolicy.SHARED_SAVE_SEED_V1
                    && (descriptor.role() != DimensionRole.OVERWORLD
                    || descriptor.generatorProfile() != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1)) {
                throw new IllegalArgumentException("shared-seed descriptor 必須是舊 Overworld");
            }
        }
        worlds = Collections.unmodifiableMap(copy);
    }
}
