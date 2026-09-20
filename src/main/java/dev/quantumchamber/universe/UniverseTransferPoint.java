package dev.quantumchamber.universe;

import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 不持有世界實例的不可變位置快照。 */
public record UniverseTransferPoint(RegistryKey<World> worldKey, Vec3d position,
        Vec3d velocity, float yaw, float pitch) {
    public UniverseTransferPoint {
        Objects.requireNonNull(worldKey, "worldKey");
        requireFinite(Objects.requireNonNull(position, "position"));
        requireFinite(Objects.requireNonNull(velocity, "velocity"));
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("旋轉不得包含 NaN 或 Infinity");
        }
    }

    private static void requireFinite(Vec3d value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException("位置與速度不得包含 NaN 或 Infinity");
        }
    }
}
