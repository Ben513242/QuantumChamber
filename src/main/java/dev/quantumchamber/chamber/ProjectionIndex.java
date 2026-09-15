package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

/** Indexes chamber bounds by role and all chunks they intersect. */
public final class ProjectionIndex {
    private final Map<Key, Set<UUID>> chambersByChunk = new HashMap<>();

    public void add(DimensionRole role, UUID chamberUuid, BlockBox bounds) {
        forEachChunk(bounds, chunkKey -> chambersByChunk
                .computeIfAbsent(new Key(role, chunkKey), ignored -> new HashSet<>())
                .add(chamberUuid));
    }

    public void remove(DimensionRole role, UUID chamberUuid, BlockBox bounds) {
        forEachChunk(bounds, chunkKey -> {
            Key key = new Key(role, chunkKey);
            Set<UUID> candidates = chambersByChunk.get(key);
            if (candidates == null) {
                return;
            }
            candidates.remove(chamberUuid);
            if (candidates.isEmpty()) {
                chambersByChunk.remove(key);
            }
        });
    }

    public Set<UUID> candidates(DimensionRole role, BlockPos pos) {
        Set<UUID> candidates = chambersByChunk.get(new Key(role, ChunkPos.toLong(pos)));
        return candidates == null ? Set.of() : Set.copyOf(candidates);
    }

    public Set<UUID> candidates(DimensionRole role, BlockBox bounds) {
        Set<UUID> candidates = new HashSet<>();
        forEachChunk(bounds, chunkKey -> {
            Set<UUID> indexed = chambersByChunk.get(new Key(role, chunkKey));
            if (indexed != null) {
                candidates.addAll(indexed);
            }
        });
        return Set.copyOf(candidates);
    }

    private static void forEachChunk(BlockBox bounds, java.util.function.LongConsumer consumer) {
        int minChunkX = Math.floorDiv(bounds.getMinX(), 16);
        int maxChunkX = Math.floorDiv(bounds.getMaxX(), 16);
        int minChunkZ = Math.floorDiv(bounds.getMinZ(), 16);
        int maxChunkZ = Math.floorDiv(bounds.getMaxZ(), 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                consumer.accept(ChunkPos.toLong(chunkX, chunkZ));
            }
        }
    }

    private record Key(DimensionRole role, long chunkKey) {
    }
}
