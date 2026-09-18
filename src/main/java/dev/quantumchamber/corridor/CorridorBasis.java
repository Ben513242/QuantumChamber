package dev.quantumchamber.corridor;

import dev.quantumchamber.persistence.SessionSemantics;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import java.util.Objects;

/** 來源艙與走廊間的不可變基底。 */
public record CorridorBasis(Direction sourceFacing,SessionSemantics semantics) {
    public CorridorBasis {
        Objects.requireNonNull(semantics,"semantics"); semantics.corridorFacing(sourceFacing);
    }
    public Direction corridorFacing() { return semantics.corridorFacing(sourceFacing); }
    public Vec3d toCorridorPosition(Vec3d value) {
        finite(value); return lateral() ? new Vec3d(7-value.z,value.y,value.x) : value;
    }
    public Vec3d toSourcePosition(Vec3d value) {
        finite(value); return lateral() ? new Vec3d(value.z,value.y,7-value.x) : value;
    }
    public Vec3d toCorridorVector(Vec3d value) {
        finite(value); return lateral() ? new Vec3d(-value.z,value.y,value.x) : value;
    }
    public Vec3d toSourceVector(Vec3d value) {
        finite(value); return lateral() ? new Vec3d(value.z,value.y,-value.x) : value;
    }
    public float toCorridorYaw(float value) { return yaw(toCorridorVector(direction(value))); }
    public float toSourceYaw(float value) { return yaw(toSourceVector(direction(value))); }
    private boolean lateral() { return semantics==SessionSemantics.LATERAL_BUFF_MAINTAINED; }
    private static Vec3d direction(float yaw) {
        if(!Float.isFinite(yaw)) throw new IllegalArgumentException("yaw必須有限");
        double angle=Math.toRadians(yaw); return new Vec3d(-Math.sin(angle),0,Math.cos(angle));
    }
    private static float yaw(Vec3d vector) { return MathHelper.wrapDegrees((float)Math.toDegrees(Math.atan2(-vector.x,vector.z))); }
    private static void finite(Vec3d value) {
        if(!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) throw new IllegalArgumentException("基底座標必須有限");
    }
}
