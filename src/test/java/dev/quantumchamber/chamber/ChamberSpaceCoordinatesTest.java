package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.transfer.ChamberReturnPlacement;
import java.util.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;

class ChamberSpaceCoordinatesTest {
    @Test void literalWorldFeetAndSourceFloorRetainAllFourFacings() {
        Direction[] facing={Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST};
        Vec3d[] feet={new Vec3d(10.5,65,15.5),new Vec3d(10.5,65,5.5),new Vec3d(5.5,65,10.5),new Vec3d(15.5,65,10.5)};
        Vec3d[] floors={new Vec3d(12.5,65,11.5),new Vec3d(8.5,65,9.5),new Vec3d(9.5,65,12.5),new Vec3d(11.5,65,8.5)};
        BlockPos[] cells={new BlockPos(10,65,15),new BlockPos(10,65,5),new BlockPos(5,65,10),new BlockPos(15,65,10)};
        var id=new UUID(0,1);
        for(int i=0;i<4;i++) {
            var frame=new ChamberFrame(new BlockPos(10,70,10),facing[i]);
            assertEquals(feet[i],ChamberSpaceCoordinates.position(frame,3.5,1,5.5));
            assertEquals(cells[i],ChamberSpaceCoordinates.block(frame,3,1,5));
            assertEquals(floors[i],ChamberReturnPlacement.plan(frame,List.of(id)).get(id));
            assertEquals(new Vec3d(3.5,1,5.5),ChamberSpaceCoordinates.localPosition(frame,feet[i]));
            var signed=new Vec3d(-97.25,-3,192.5);
            assertEquals(signed,ChamberSpaceCoordinates.localPosition(frame,ChamberSpaceCoordinates.position(frame,signed.x,signed.y,signed.z)));
            assertEquals(signed,ChamberSpaceCoordinates.localVector(frame,ChamberSpaceCoordinates.vector(frame,signed)));
        }
    }
    @Test void rejectsNonfiniteCoordinatesAndVerticalFrames() {
        var frame=new ChamberFrame(BlockPos.ORIGIN,Direction.NORTH);
        assertThrows(IllegalArgumentException.class,() -> ChamberSpaceCoordinates.position(frame,Double.NaN,1,1));
        assertThrows(IllegalArgumentException.class,() -> ChamberSpaceCoordinates.vector(frame,new Vec3d(1,Double.POSITIVE_INFINITY,1)));
        assertThrows(IllegalArgumentException.class,() -> ChamberSpaceCoordinates.localPosition(frame,new Vec3d(1,1,Double.NaN)));
        assertThrows(IllegalArgumentException.class,() -> ChamberSpaceCoordinates.localVector(frame,new Vec3d(1,1,Double.NEGATIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class,() -> ChamberSpaceCoordinates.block(new ChamberFrame(BlockPos.ORIGIN,Direction.UP),0,0,0));
    }
}
