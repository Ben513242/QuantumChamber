package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public record ChamberRecord(
        UUID chamberUuid,
        Identifier originWorldKey,
        DimensionRole originDimensionRole,
        BlockPos anchorPos,
        Direction facing,
        ChamberInstanceKind instanceKind,
        boolean enabled,
        boolean destroyed) {
    public ChamberRecord {
        Objects.requireNonNull(chamberUuid, "chamberUuid");
        Objects.requireNonNull(originWorldKey, "originWorldKey");
        Objects.requireNonNull(originDimensionRole, "originDimensionRole");
        Objects.requireNonNull(anchorPos, "anchorPos");
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(instanceKind, "instanceKind");
    }
}
