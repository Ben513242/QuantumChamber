package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.persistence.SessionSemantics;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;

class CorridorBasisTest {
    private final CorridorBasis basis=new CorridorBasis(Direction.NORTH,SessionSemantics.LATERAL_BUFF_MAINTAINED);
    @Test void lateralRotatesPointsAndVectorsWithoutReflecting() {
        assertEquals(Direction.EAST,basis.corridorFacing());
        assertEquals(new Vec3d(1.5,1,3.5),basis.toCorridorPosition(new Vec3d(3.5,1,5.5)));
        assertEquals(new Vec3d(-2,3,1),basis.toCorridorVector(new Vec3d(1,3,2)));
        assertEquals(new Vec3d(3.5,1,5.5),basis.toSourcePosition(new Vec3d(1.5,1,3.5)));
        assertEquals(new Vec3d(1,3,2),basis.toSourceVector(new Vec3d(-2,3,1)));
        assertEquals(90,basis.toCorridorYaw(0),1e-5);
        assertEquals(-180,basis.toCorridorYaw(90),1e-5);
        assertEquals(0,basis.toSourceYaw(90),1e-5);
    }
    @Test void continuousSignedPointsAndVelocitiesRoundtripForEveryFacing() {
        Direction[] sources={Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
        Direction[] corridors={Direction.EAST,Direction.SOUTH,Direction.WEST,Direction.NORTH};
        for(int i=0;i<4;i++) for(var mode : SessionSemantics.values()) {
            var transform=new CorridorBasis(sources[i],mode);
            assertEquals(mode==SessionSemantics.LATERAL_BUFF_MAINTAINED ? corridors[i] : sources[i],transform.corridorFacing());
            for(var value : new Vec3d[]{new Vec3d(-96.75,-2.25,193.5),new Vec3d(7,0,-0.5),new Vec3d(0.125,4,6.875)}) {
                assertEquals(value,transform.toSourcePosition(transform.toCorridorPosition(value)));
                assertEquals(value,transform.toSourceVector(transform.toCorridorVector(value)));
                if(mode==SessionSemantics.LEGACY_FORWARD_CONSUMED) assertEquals(value,transform.toCorridorPosition(value));
            }
            for(float yaw : new float[]{-179,-90,0,90,179}) assertEquals(0,MathHelper.wrapDegrees(
                    transform.toSourceYaw(transform.toCorridorYaw(yaw))-yaw),1e-4);
        }
    }
    @Test void rejectsVerticalMissingAndNonfiniteInputs() {
        assertThrows(IllegalArgumentException.class,() -> new CorridorBasis(Direction.UP,SessionSemantics.LATERAL_BUFF_MAINTAINED));
        assertThrows(NullPointerException.class,() -> new CorridorBasis(Direction.NORTH,null));
        var invalid=new Vec3d(Double.NaN,0,0);
        assertThrows(IllegalArgumentException.class,() -> basis.toCorridorPosition(invalid));
        assertThrows(IllegalArgumentException.class,() -> basis.toSourcePosition(invalid));
        assertThrows(IllegalArgumentException.class,() -> basis.toCorridorVector(invalid));
        assertThrows(IllegalArgumentException.class,() -> basis.toSourceVector(invalid));
        assertThrows(IllegalArgumentException.class,() -> basis.toCorridorYaw(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,() -> basis.toSourceYaw(Float.NaN));
    }
}
