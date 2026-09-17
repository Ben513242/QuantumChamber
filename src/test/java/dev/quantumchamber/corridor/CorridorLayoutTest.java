package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import dev.quantumchamber.chamber.ChamberFrame;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

class CorridorLayoutTest {
    @Test void typedViewsKeepMutableCallerInputsOutsideAuthority() {
        var origin=new BlockPos.Mutable(1003,64,2000);
        var bounds=new net.minecraft.util.math.BlockBox(997,64,1328,1003,70,2767);
        var view=new CorridorPageManager.MappingView(new CorridorPageManager.MappingRef(java.util.UUID.randomUUID(),1,0),
                -1,1,-672,768,0,origin,Direction.NORTH,bounds);
        var input=new java.util.ArrayList<CorridorPageManager.MappingView>(); input.add(view);
        var set=new CorridorPageManager.MappingSet(1,input);
        origin.set(0,0,0); bounds.move(100,0,0); view.bounds().move(200,0,0); input.clear();
        assertEquals(new BlockPos(1003,64,2000),set.instances().getFirst().localBlockOrigin());
        assertEquals(997,set.instances().getFirst().bounds().getMinX());
        assertThrows(UnsupportedOperationException.class,() -> set.instances().clear());
    }
    @Test void logicalOccupiedCapRejectsThe129thPage() {
        var pages=java.util.stream.LongStream.range(0,128).boxed().collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.List.of(new CorridorLayout.LayoutComponent(-1,128,-672,12960)),CorridorLayout.plan(pages,576));
        pages.add(128L);
        assertThrows(IllegalArgumentException.class,() -> CorridorLayout.plan(pages,576));
    }
    @Test void continuousPositionsRespectNegativeAxisCellBoundaries() {
        var origin = new BlockPos(1000, 70, 2000);
        var directions = new Direction[] {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
        var expected = new Vec3d[] {new Vec3d(1000.5,65,2005.5), new Vec3d(1000.5,65,1995.5),
                new Vec3d(995.5,65,2000.5), new Vec3d(1005.5,65,2000.5)};
        for (int i = 0; i < directions.length; i++) {
            var frame = new ChamberFrame(origin, directions[i]);
            assertEquals(expected[i], CorridorGeometry.position(frame, 3.5, 1, 5.5));
            assertEquals(new Vec3d(3.5,1,5.5), CorridorGeometry.localPosition(frame, expected[i]));
        }
        var north = new ChamberFrame(origin, Direction.NORTH);
        assertEquals(new BlockPos(1000,67,2006), CorridorGeometry.block(north,3,3,6));
        assertEquals(new BlockPos(1000,67,2007), CorridorGeometry.block(north,3,3,7));
        assertEquals(new Vec3d(-1,2,3), CorridorGeometry.vector(north, new Vec3d(1,2,3)));
        assertEquals(new Vec3d(1,2,3), CorridorGeometry.localVector(north, new Vec3d(-1,2,3)));
        assertEquals(1, CorridorGeometry.position(north,3.5,1,95.5).distanceTo(
                CorridorGeometry.position(north,3.5,1,96.5)), 1e-9);
    }
    @Test void logicalDoorIdentityDoesNotIncludePhysicalSlot() {
        var session = java.util.UUID.randomUUID();
        assertEquals(new DoorKey(session,-1,DoorKey.Side.LEFT), DoorKey.from(session,-1,DoorKey.Side.LEFT));
        assertEquals(new DoorKey(session,12,DoorKey.Side.RIGHT), DoorKey.from(session,96,DoorKey.Side.RIGHT));
    }
    @Test void negativeAndBoundaryCoordinatesUseFloorDivision() {
        assertEquals(new LogicalAddress(-1, 95), LogicalAddress.from(-1));
        assertEquals(new LogicalAddress(1, 0), LogicalAddress.from(96));
        assertEquals(new LogicalAddress(0, 95.5), LogicalAddress.from(95.5));
        assertEquals(new LogicalAddress(1, .5), LogicalAddress.from(96.5));
    }
    @Test void distantPagesNeverFillTheLogicalGap() {
        var parts = CorridorLayout.plan(Set.of(0L, 1_000_000L), 576);
        assertEquals(2, parts.size());
        assertEquals(new CorridorLayout.LayoutComponent(-1, 1, -672, 768), parts.getFirst());
        assertEquals(new CorridorLayout.LayoutComponent(999_999, 1_000_001, 95_999_328, 96_000_768), parts.getLast());
    }
    @Test void nearbyAliasesShareOneAffineComponent() {
        assertEquals(java.util.List.of(new CorridorLayout.LayoutComponent(-1, 2, -672, 864)),
                CorridorLayout.plan(Set.of(0L, 1L), 576));
    }
    @Test void invalidAndUnrepresentableCoordinatesAreRejected() {
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0x1p63}) {
            assertThrows(IllegalArgumentException.class, () -> LogicalAddress.from(invalid));
        }
        assertThrows(IllegalArgumentException.class, () -> CorridorLayout.plan(Set.of(Long.MAX_VALUE), 576));
        assertThrows(IllegalArgumentException.class, () -> CorridorLayout.plan(Set.of(0L), -1));
    }
}
