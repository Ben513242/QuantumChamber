package dev.quantumchamber.transfer;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.chamber.ChamberFrame;
import java.util.*;
import net.minecraft.util.math.*;
import org.junit.jupiter.api.Test;

class ChamberReturnPlacementTest {
    private final ChamberFrame frame = new ChamberFrame(new BlockPos(3,6,0),Direction.NORTH);
    private UUID id(int value) { return new UUID(0,value); }
    @Test void deterministicFloorSlotsIgnoreInputOrderAndRejectOverCapacity() {
        var result = ChamberReturnPlacement.plan(frame,List.of(id(2),id(1)));
        assertEquals(new Vec3d(5.5,1,1.5),result.get(id(1)));
        assertEquals(new Vec3d(4.5,1,1.5),result.get(id(2)));
        var people = java.util.stream.IntStream.rangeClosed(1,25).mapToObj(this::id).toList();
        var full = ChamberReturnPlacement.plan(frame,people);
        assertEquals(25,new HashSet<>(full.values()).size());
        assertEquals(new Vec3d(1.5,1,5.5),full.get(id(25)));
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,
                java.util.stream.IntStream.rangeClosed(1,26).mapToObj(this::id).toList()));
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,List.of(id(1),id(1))));
    }
    @Test void sourceLocalDepthThenLateralOrderingPrecedesUuidForAllFacings() {
        for (var facing : List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST)) {
            var rotated = new ChamberFrame(new BlockPos(10,70,10),facing);
            var sources = Map.of(id(1),dev.quantumchamber.corridor.CorridorGeometry.position(rotated,2.5,1,4.5),
                    id(2),dev.quantumchamber.corridor.CorridorGeometry.position(rotated,4.5,1,2.5),
                    id(3),dev.quantumchamber.corridor.CorridorGeometry.position(rotated,2.5,1,2.5));
            var placements = ChamberReturnPlacement.plan(rotated,List.of(id(1),id(2),id(3)),sources);
            var right = facing.rotateYCounterclockwise();
            var difference = placements.get(id(2)).subtract(placements.get(id(3)));
            assertEquals(new Vec3d(right.getOffsetX(),0,right.getOffsetZ()),difference);
            assertEquals(difference,placements.get(id(1)).subtract(placements.get(id(2))));
        }
    }
    @Test void incompleteOrNonfiniteSourceAuthorityIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,List.of(id(1)),Map.of()));
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,List.of(id(1)),
                Map.of(id(1),new Vec3d(Double.NaN,1,1))));
    }
}
