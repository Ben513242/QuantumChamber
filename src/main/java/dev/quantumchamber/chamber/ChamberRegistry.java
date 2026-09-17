package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class ChamberRegistry {
    private final Map<UUID, ChamberRecord> records = new LinkedHashMap<>();
    private final ProjectionIndex projectionIndex = new ProjectionIndex();
    private final Runnable changed;

    public ChamberRegistry() {
        this(() -> { });
    }

    ChamberRegistry(Runnable changed) {
        this.changed = Objects.requireNonNull(changed, "changed");
    }

    public ChamberRegistrationResult registerOrigin(Identifier worldKey, DimensionRole role, ChamberFrame frame) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(frame, "frame");

        Optional<ChamberRecord> existing = findOrigin(worldKey, role, frame);
        if (existing.isPresent()) {
            return new ChamberRegistrationResult(ChamberRegistrationResult.Status.EXISTING, existing.get().chamberUuid());
        }

        BlockBox candidateBounds = ChamberGeometry.bounds(frame);
        for (UUID candidateUuid : projectionIndex.candidates(role, candidateBounds)) {
            ChamberRecord candidate = records.get(candidateUuid);
            if (candidate != null && intersects(candidateBounds, bounds(candidate))) {
                return new ChamberRegistrationResult(ChamberRegistrationResult.Status.OVERLAP, candidateUuid);
            }
        }

        UUID chamberUuid = UUID.randomUUID();
        ChamberRecord record = new ChamberRecord(
                chamberUuid, worldKey, role, frame.controllerPos(), frame.outwardFacing(),
                ChamberInstanceKind.ORIGIN, true, false);
        putLoaded(record);
        changed.run();
        return new ChamberRegistrationResult(ChamberRegistrationResult.Status.CREATED, chamberUuid);
    }

    /** 完全唯讀地查找相同世界、role、Controller 位置與朝向的既有紀錄。 */
    public Optional<ChamberRecord> findOrigin(Identifier worldKey, DimensionRole role, ChamberFrame frame) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(frame, "frame");
        return records.values().stream()
                .filter(record -> record.originWorldKey().equals(worldKey))
                .filter(record -> record.originDimensionRole() == role)
                .filter(record -> record.anchorPos().equals(frame.controllerPos()))
                .filter(record -> record.facing() == frame.outwardFacing())
                .findFirst();
    }

    public Optional<ChamberRecord> findAt(DimensionRole role, BlockPos pos) {
        for (UUID chamberUuid : projectionIndex.candidates(role, pos)) {
            ChamberRecord record = records.get(chamberUuid);
            if (record != null && contains(bounds(record), pos)) {
                return Optional.of(record);
            }
        }
        return Optional.empty();
    }

    public Map<UUID, ChamberRecord> records() {
        return Map.copyOf(records);
    }

    boolean setEnabled(UUID chamberUuid, boolean enabled) {
        ChamberRecord current = records.get(chamberUuid);
        if (current == null || current.enabled() == enabled) {
            return false;
        }
        records.put(chamberUuid, new ChamberRecord(
                current.chamberUuid(), current.originWorldKey(), current.originDimensionRole(), current.anchorPos(),
                current.facing(), current.instanceKind(), enabled, current.destroyed()));
        changed.run();
        return true;
    }

    void putLoaded(ChamberRecord record) {
        records.put(record.chamberUuid(), record);
        projectionIndex.add(record.originDimensionRole(), record.chamberUuid(), bounds(record));
    }

    boolean removeOrigin(UUID chamberUuid) {
        ChamberRecord record = records.get(chamberUuid);
        if (record == null || record.instanceKind() != ChamberInstanceKind.ORIGIN || record.destroyed()) return false;
        records.remove(chamberUuid);
        projectionIndex.remove(record.originDimensionRole(), chamberUuid, bounds(record));
        changed.run();
        return true;
    }

    private static BlockBox bounds(ChamberRecord record) {
        return ChamberGeometry.bounds(new ChamberFrame(record.anchorPos(), record.facing()));
    }

    private static boolean intersects(BlockBox first, BlockBox second) {
        return first.getMinX() <= second.getMaxX() && first.getMaxX() >= second.getMinX()
                && first.getMinY() <= second.getMaxY() && first.getMaxY() >= second.getMinY()
                && first.getMinZ() <= second.getMaxZ() && first.getMaxZ() >= second.getMinZ();
    }

    private static boolean contains(BlockBox bounds, BlockPos pos) {
        return pos.getX() >= bounds.getMinX() && pos.getX() <= bounds.getMaxX()
                && pos.getY() >= bounds.getMinY() && pos.getY() <= bounds.getMaxY()
                && pos.getZ() >= bounds.getMinZ() && pos.getZ() <= bounds.getMaxZ();
    }
}
