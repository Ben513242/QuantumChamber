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
        if (schemaVersion != SCHEMA_ONE) {
            throw new IllegalArgumentException("不支援 Universe schema: " + schemaVersion);
        }
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(worlds, "worlds");

        var copy = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        copy.putAll(worlds);
        if (copy.size() != 1 || !copy.containsKey(DimensionRole.OVERWORLD)) {
            throw new IllegalArgumentException("schema 1 必須恰有一個 OVERWORLD descriptor");
        }

        var descriptor = Objects.requireNonNull(copy.get(DimensionRole.OVERWORLD), "OVERWORLD descriptor");
        if (descriptor.role() != DimensionRole.OVERWORLD) {
            throw new IllegalArgumentException("world map key 與 descriptor role 不一致");
        }
        if (!descriptor.worldKey().equals(UniverseKeys.world(universeId, DimensionRole.OVERWORLD))) {
            throw new IllegalArgumentException("world key 與 Universe UUID/role 不一致");
        }
        if (descriptor.generatorProfile() != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1) {
            throw new IllegalArgumentException("schema 1 generator profile 不符");
        }
        if (descriptor.seedPolicy() != SeedPolicy.SHARED_SAVE_SEED_V1) {
            throw new IllegalArgumentException("schema 1 seed policy 不符");
        }
        if (descriptor.storagePolicyVersion() != STORAGE_POLICY_ONE) {
            throw new IllegalArgumentException("schema 1 storage policy version 不符");
        }
        worlds = Collections.unmodifiableMap(copy);
    }
}
