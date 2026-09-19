package dev.quantumchamber.universe;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/** 管理 schema 1 Universe definition 與經檢查的期望 availability。 */
public final class UniverseRegistry {
    private final LinkedHashMap<UniverseId, UniverseRecord> records;

    public UniverseRegistry() {
        records = new LinkedHashMap<>();
    }

    UniverseRegistry(Collection<UniverseRecord> source) {
        this();
        for (var record : source) {
            var id = record.definition().universeId();
            if (records.putIfAbsent(id, Objects.requireNonNull(record, "record")) != null) {
                throw new IllegalArgumentException("Universe UUID 重複: " + id.value());
            }
        }
        validate(records.values());
    }

    public UniverseRecord allocateOverworld(UUID uuid, long allocationOrdinal) {
        var id = UniverseId.of(Objects.requireNonNull(uuid, "uuid"));
        var descriptor = new UniverseWorldDescriptor(
                UniverseKeys.world(id, DimensionRole.OVERWORLD),
                DimensionRole.OVERWORLD,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1,
                SeedPolicy.SHARED_SAVE_SEED_V1,
                1);
        var worlds = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        worlds.put(DimensionRole.OVERWORLD, descriptor);
        var candidate = new UniverseRecord(
                new UniverseDefinition(1, id, allocationOrdinal, worlds),
                DesiredAvailability.EAGER_ENABLED);

        var existing = records.get(id);
        if (existing != null) {
            if (!existing.definition().equals(candidate.definition())) {
                throw new IllegalArgumentException("既有 Universe definition 不可改寫: " + uuid);
            }
            return existing;
        }

        records.put(id, candidate);
        try {
            validate(records.values());
        } catch (RuntimeException exception) {
            records.remove(id);
            throw exception;
        }
        return candidate;
    }

    public Optional<UniverseRecord> find(UniverseId id) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(id, "id")));
    }

    public Optional<UniverseRecord> findByWorldKey(RegistryKey<World> worldKey) {
        Objects.requireNonNull(worldKey, "worldKey");
        return records.values().stream()
                .filter(record -> record.definition().worlds().values().stream()
                        .anyMatch(descriptor -> descriptor.worldKey().equals(worldKey)))
                .findFirst();
    }

    public UniverseRecord changeAvailability(UniverseId id, DesiredAvailability availability) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(availability, "availability");
        var existing = records.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Universe 不存在: " + id.value());
        }
        if (existing.desiredAvailability() == availability) {
            return existing;
        }
        var changed = new UniverseRecord(existing.definition(), availability);
        records.put(id, changed);
        return changed;
    }

    public Map<UniverseId, UniverseRecord> records() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(records));
    }

    UniverseRegistry copy() {
        return new UniverseRegistry(records.values());
    }

    static void validate(Collection<UniverseRecord> records) {
        var ids = new HashSet<UniverseId>();
        var worldKeys = new HashSet<RegistryKey<World>>();
        for (var record : records) {
            Objects.requireNonNull(record, "record");
            if (!ids.add(record.definition().universeId())) {
                throw new IllegalArgumentException("Universe UUID 重複: "
                        + record.definition().universeId().value());
            }
            for (var descriptor : record.definition().worlds().values()) {
                if (!worldKeys.add(descriptor.worldKey())) {
                    throw new IllegalArgumentException("Universe world key 重複: "
                            + descriptor.worldKey().getValue());
                }
            }
        }
    }
}
