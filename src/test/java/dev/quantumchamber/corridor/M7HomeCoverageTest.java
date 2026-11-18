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

    /**
     * T4B RED（plan Task 4B :531、spec v1.6 C）：A 票在主世界、B 收尾票在投影宇宙，兩者 chunk XZ 相同時是兩格，不得去重。現行 API 只收 chunk long，
     * 兩個 SID 同 long 會被合併成一格：峰值是 1（應為 2），而且走廊 4095 格加 A 的 1 格已滿 4096 時，B 的同 XZ 一格仍被接受（應拒絕）。
     */
    @Test void entryAndRootSameXzDifferentWorldCountSeparately() {
        var allocator = new SlotAllocator();
        var sameXz = Set.of(ChunkPos.toLong(0, 0));
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), sameXz), "A 票（主世界）");
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), sameXz), "B 收尾票（投影宇宙，同 XZ）");
        assertEquals(2, allocator.peakCoverage(), "A 與 B 同 XZ 異世界各記一格");
        var full = new SlotAllocator();
        full.restore(0, new BlockBox(0, 64, 0, 13030, 70, 6));
        assertEquals(4095, full.peakCoverage());
        assertTrue(full.reserveRoot(UUID.randomUUID(), sameXz), "A 票補到 4096");
        assertFalse(full.reserveRoot(UUID.randomUUID(), sameXz), "B 的同 XZ 一格是第 4097 格，必須拒絕");
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
