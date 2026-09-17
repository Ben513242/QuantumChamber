package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.UUID;
import java.util.Objects;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** 維護要求的完整 Origin 身分；只有核對既有紀錄後才可提交。 */
public record ChamberOriginAuthority(UUID chamberUuid, Identifier worldKey, DimensionRole role,
        BlockPos controllerPos, Direction facing, ChamberInstanceKind instanceKind) {
    public ChamberOriginAuthority {
        Objects.requireNonNull(chamberUuid, "chamberUuid");
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        controllerPos = Objects.requireNonNull(controllerPos, "controllerPos").toImmutable();
        Objects.requireNonNull(facing, "facing");
        Objects.requireNonNull(instanceKind, "instanceKind");
    }
}
