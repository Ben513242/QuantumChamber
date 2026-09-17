package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

class SlotAllocatorTest {
    @Test void pendingReleaseAndReceiptsShareOneBoundedCapacity() {
        assertTrue(CorridorPageManager.canTrackRelease(63,0));
        assertFalse(CorridorPageManager.canTrackRelease(63,1));
        assertTrue(CorridorPageManager.canTrackRelease(62,1));
        assertFalse(CorridorPageManager.canTrackRelease(0,64));
    }
    @Test void expandedCoverageCountsSharedHaloOnlyOnce() {
        var first=new BlockBox(0,0,0,6,6,1439);
        var adjacent=new BlockBox(0,0,1440,6,6,2879);
        assertEquals(470,SlotAllocator.coverage(java.util.List.of(first)).orElseThrow().size());
        assertEquals(920,SlotAllocator.coverage(java.util.List.of(first,adjacent)).orElseThrow().size());
    }
    @Test void rejectsOversizedTicketFootprintBeforeReservation() {
        var allocator=new SlotAllocator();
        assertTrue(allocator.reserve(new BlockBox(-3,-6,-10000,3,0,10000)).isEmpty());
        assertTrue(allocator.reserve(new BlockBox(-3,-6,-672,3,0,767)).isPresent());
    }
    @Test void allProvisionalLeasesShareTheExpandedCoverageCap() {
        var allocator=new SlotAllocator();
        for(int i=0;i<8;i++) assertTrue(allocator.reserve(new BlockBox(-3,-6,-672,3,0,767)).isPresent());
        assertTrue(allocator.reserve(new BlockBox(-3,-6,-672,3,0,767)).isEmpty());
        allocator.release(0);
        assertTrue(allocator.reserve(new BlockBox(-3,-6,-672,3,0,767)).isPresent());
    }
    @Test void refusesUnknownRecoveredShapeAndHeight() {
        var allocator=new SlotAllocator();
        assertThrows(IllegalArgumentException.class,() -> allocator.restore(0,new BlockBox(0,0,0,8,6,100)));
        assertThrows(IllegalArgumentException.class,() -> allocator.restore(0,new BlockBox(0,-1,0,6,5,100)));
        assertThrows(IllegalArgumentException.class,() -> allocator.restore(0,new BlockBox(0,0,0,6,100,100)));
    }
    @Test void reservesWholeLongBoundsWithoutOverlap() {
        var allocator = new SlotAllocator();
        var first = allocator.reserve(new BlockBox(-3, -6, -672, 3, 0, 767)).orElse(null);
        assertNotNull(first);
        assertEquals(new BlockPos(1000, 70, 2000), first.origin());
        var second = allocator.reserve(new BlockBox(-3, -6, -672, 3, 0, 767)).orElseThrow();
        assertEquals(new BlockPos(1192, 70, 2000), second.origin());
        assertFalse(first.bounds().intersects(second.bounds()));
    }
    @Test void includesPendingLeasesInCapacityAndDefensivelyCopiesBounds() {
        var allocator = new SlotAllocator();
        var input = new BlockBox(-3, -6, -672, 3, 0, 767);
        var first = allocator.reserve(input).orElse(null);
        assertNotNull(first);
        input.move(100, 0, 0);
        first.bounds().move(100, 0, 0);
        assertEquals(997, first.bounds().getMinX());
        for (int i = 1; i < 64; i++) assertTrue(allocator.reserve(new BlockBox(0, 0, 0, 6, 6, 6)).isPresent());
        assertTrue(allocator.reserve(new BlockBox(0, 0, 0, 6, 6, 6)).isEmpty());
        allocator.release(first.slotId());
        assertTrue(allocator.reserve(new BlockBox(0, 0, 0, 6, 6, 6)).isPresent());
    }
}
