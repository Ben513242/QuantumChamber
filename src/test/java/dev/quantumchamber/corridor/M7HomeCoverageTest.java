package dev.quantumchamber.corridor;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class M7HomeCoverageTest {
    /** 原版主世界（A 票）與一個投影宇宙（B 收尾票）的 world key。 */
    private static final Identifier OVERWORLD = Identifier.of("minecraft", "overworld");
    private static final Identifier UNIVERSE = Identifier.of("quantumchamber", "universe/00000000-0000-0000-0000-000000000001/overworld");

    private static Set<SlotAllocator.RootChunk> chunks(Identifier world, long... packed) {
        var keys = new LinkedHashSet<SlotAllocator.RootChunk>();
        for (long chunk : packed) keys.add(new SlotAllocator.RootChunk(world, chunk));
        return keys;
    }

    @Test void sameXZDifferentWorldConsumesCoverage() {
        var allocator = new SlotAllocator();
        allocator.restore(0, new BlockBox(0, 64, 0, 6, 70, 6));
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), chunks(OVERWORLD, ChunkPos.toLong(0, 0))));
        assertEquals(26, allocator.peakCoverage(), "走廊 25 格加原艙同 XZ 仍是 26 格");
    }
    @Test void boundsRootSharesAndReferencesWithoutReleasingOthers() {
        var allocator=new SlotAllocator();
        var owners=new java.util.ArrayList<UUID>();
        var four=chunks(OVERWORLD, 0L, 1L, 2L, 3L);
        for(int i=0;i<64;i++) {
            var sid=UUID.randomUUID();
            owners.add(sid);
            assertTrue(allocator.reserveRoot(sid,four));
            assertFalse(allocator.reserveRoot(sid,chunks(OVERWORLD, 4L)));
        }
        assertEquals(4,allocator.peakCoverage());
        assertFalse(allocator.reserveRoot(UUID.randomUUID(),four));
        allocator.releaseRoot(owners.getFirst());
        assertFalse(allocator.reserveRoot(UUID.randomUUID(),chunks(OVERWORLD, 0L, 1L, 2L, 3L, 4L)));
        assertTrue(allocator.reserveRoot(UUID.randomUUID(),chunks(OVERWORLD, 4L)));
        assertEquals(5,allocator.peakCoverage());
    }

    /**
     * T4B（plan Task 4B :531、spec v1.6 C）：A 票在主世界、B 收尾票在投影宇宙，兩者 chunk XZ 相同時是兩格，不得去重；同 world 同 chunk 仍去重。
     * 走廊 4095 格加 A 的 1 格已滿 4096 時，B 的同 XZ 一格是第 4097 格，必須拒絕；A 與 B 合計仍受每 SID 一份與 64 份的上限。
     * （RED 版在 T4 的 API 上以兩個 SID 同 chunk long 表達 A 與 B，峰值 1、第 4097 格被接受；GREEN 版改傳 world＋chunk，斷言相同。）
     */
    @Test void entryAndRootSameXzDifferentWorldCountSeparately() {
        var allocator = new SlotAllocator();
        long sameXz = ChunkPos.toLong(0, 0);
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), chunks(OVERWORLD, sameXz)), "A 票（主世界）");
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), chunks(UNIVERSE, sameXz)), "B 收尾票（投影宇宙，同 XZ）");
        assertEquals(2, allocator.peakCoverage(), "A 與 B 同 XZ 異世界各記一格");
        assertTrue(allocator.reserveRoot(UUID.randomUUID(), chunks(UNIVERSE, sameXz)), "另一份 B 票同 world 同 chunk");
        assertEquals(2, allocator.peakCoverage(), "同 world 同 chunk 才去重");
        var full = new SlotAllocator();
        full.restore(0, new BlockBox(0, 64, 0, 13030, 70, 6));
        assertEquals(4095, full.peakCoverage());
        assertTrue(full.reserveRoot(UUID.randomUUID(), chunks(OVERWORLD, sameXz)), "A 票補到 4096");
        assertFalse(full.reserveRoot(UUID.randomUUID(), chunks(UNIVERSE, sameXz)), "B 的同 XZ 一格是第 4097 格，必須拒絕");
        var shared = new SlotAllocator();
        var sid = UUID.randomUUID();
        assertTrue(shared.reserveRoot(sid, chunks(OVERWORLD, 0L)), "同一個 SID 先有 A 票");
        assertFalse(shared.reserveRoot(sid, chunks(UNIVERSE, 0L)), "每 SID 同時最多一份 A 票或 B 收尾票");
        for (int i = 1; i < 64; i++) assertTrue(shared.reserveRoot(UUID.randomUUID(), chunks(i % 2 == 0 ? OVERWORLD : UNIVERSE, i)));
        assertFalse(shared.reserveRoot(UUID.randomUUID(), chunks(UNIVERSE, 100L)), "A＋B 合計 64 份");
    }

    @Test void bothOrdersRespectCombinedCoverageLimit() {
        var box=new BlockBox(0,64,0,13030,70,6);
        var first=new SlotAllocator();
        first.restore(0,box);
        assertEquals(4095,first.peakCoverage());
        assertTrue(first.reserveRoot(UUID.randomUUID(),chunks(OVERWORLD, 0L)));
        assertFalse(first.reserveRoot(UUID.randomUUID(),chunks(OVERWORLD, 1L)));
        var second=new SlotAllocator();
        assertTrue(second.reserveRoot(UUID.randomUUID(),chunks(OVERWORLD, 0L, 1L)));
        assertThrows(IllegalStateException.class,()->second.restore(0,box));
    }
}
