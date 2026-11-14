package dev.quantumchamber.corridor;

import java.util.Set;
import java.util.UUID;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class M7HomeCoverageTest {
    @Test void sameXZDifferentWorldConsumesCoverage() {
        var allocator = new SlotAllocator();
        allocator.restore(0, new BlockBox(0, 64, 0, 6, 70, 6));
        var chunks = Set.of(ChunkPos.toLong(0, 0));
        assertTrue(allocator.reserveRoot(UUID.randomUUID(),chunks));
        assertEquals(26, allocator.peakCoverage(), "走廊 25 格加原艙同 XZ 仍是 26 格");
    }
    @Test void boundsRootSharesAndReferencesWithoutReleasingOthers() {
        var allocator=new SlotAllocator();
        var owners=new java.util.ArrayList<UUID>();
        var four=Set.of(0L,1L,2L,3L);
        for(int i=0;i<64;i++) {
            var sid=UUID.randomUUID();
            owners.add(sid);
            assertTrue(allocator.reserveRoot(sid,four));
            assertFalse(allocator.reserveRoot(sid,Set.of(4L)));
        }
        assertEquals(4,allocator.peakCoverage());
        assertFalse(allocator.reserveRoot(UUID.randomUUID(),four));
        allocator.releaseRoot(owners.getFirst());
        assertFalse(allocator.reserveRoot(UUID.randomUUID(),Set.of(0L,1L,2L,3L,4L)));
        assertTrue(allocator.reserveRoot(UUID.randomUUID(),Set.of(4L)));
        assertEquals(5,allocator.peakCoverage());
    }

    @Test void bothOrdersRespectCombinedCoverageLimit() {
        var box=new BlockBox(0,64,0,13030,70,6);
        var first=new SlotAllocator();
        first.restore(0,box);
        assertEquals(4095,first.peakCoverage());
        assertTrue(first.reserveRoot(UUID.randomUUID(),Set.of(0L)));
        assertFalse(first.reserveRoot(UUID.randomUUID(),Set.of(1L)));
        var second=new SlotAllocator();
        assertTrue(second.reserveRoot(UUID.randomUUID(),Set.of(0L,1L)));
        assertThrows(IllegalStateException.class,()->second.restore(0,box));
    }
}
