package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.ALLOW;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_NOT_FULL;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_UNSAFE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;

/**
 * M5 F5-1（plan 決策 4）：{@link TransferSafety#M3_SAFE_LANDING} 就是 F5-1 之前 {@code UniverseTransferService.targetSafety} 的決策（M3 的 test 不改，這裡補上
 * 它的決策表），以及 FULL 檢查共用的 chunk 推導（{@link TransferSafety#fullChunkRange}）。{@link FakeSite} 也給 collapse package 的 {@code PassageTransferPolicyTest} 用。
 */
class TransferSafetyTest {
    /** 站在 (0, 64, 0) 這塊地板上的玩家 bbox（0.6×1.8，腳底恰在 y=65）。 */
    static Box standing(double x, double y, double z) {
        return new Box(x - 0.3, y, z - 0.3, x + 0.3, y + 1.8, z + 0.3);
    }

    @Test
    void m3SafeLandingAllowsOnlyAFullClearSupportedLanding() {
        var rows = new LinkedHashMap<String, Map.Entry<FakeSite, Box>>();
        rows.put("石頭地板上站著", Map.entry(FakeSite.flat(), standing(0.5, 65, 0.5)));
        rows.put("跳躍（腳底不在方塊頂面）", Map.entry(FakeSite.flat(), standing(0.5, 65.42, 0.5)));
        rows.put("水", Map.entry(FakeSite.flat().fluid(new BlockPos(0, 65, 0)), standing(0.5, 65, 0.5)));
        rows.put("岩漿", Map.entry(FakeSite.flat().fluid(new BlockPos(0, 66, 0)), standing(0.5, 65, 0.5)));
        rows.put("石頭（身體與方塊相交）", Map.entry(FakeSite.flat().solid(new BlockPos(0, 66, 0)), standing(0.5, 65, 0.5)));
        rows.put("懸空（腳下沒有地板）", Map.entry(FakeSite.empty(), standing(0.5, 65, 0.5)));
        rows.put("跨兩欄、其中一欄沒有地板", Map.entry(FakeSite.flat().air(new BlockPos(1, 64, 0)), standing(1.0, 65, 0.5)));
        var expected = Map.of("石頭地板上站著", ALLOW);
        rows.forEach((name, row) -> assertEquals(expected.getOrDefault(name, REJECT_TARGET_UNSAFE),
                TransferSafety.M3_SAFE_LANDING.check(row.getKey(), row.getValue()), name));
    }

    @Test
    void sharedChecksRejectTheSameWayForEveryPolicy() {
        var flat = standing(0.5, 65, 0.5);
        assertEquals(REJECT_TARGET_UNSAFE, TransferSafety.M3_SAFE_LANDING.check(FakeSite.flat(), new Box(Double.NaN, 65, 0, 1, 66, 1)), "非有限值");
        assertEquals(REJECT_TARGET_UNSAFE, TransferSafety.M3_SAFE_LANDING.check(FakeSite.flat(), new Box(0, 65, 0, 80, 66, 1)), "chunk 範圍超過 4");
        assertEquals(REJECT_TARGET_UNSAFE, TransferSafety.M3_SAFE_LANDING.check(FakeSite.flat().height(70, 320), flat), "低於世界底部");
        assertEquals(REJECT_TARGET_UNSAFE, TransferSafety.M3_SAFE_LANDING.check(FakeSite.flat().height(-64, 66), flat), "高於世界頂部");
        assertEquals(REJECT_TARGET_UNSAFE, TransferSafety.M3_SAFE_LANDING.check(FakeSite.flat().border(0.6, 100, -100, 100), flat), "世界邊界外");
        var notFull = FakeSite.flat().notFull((x, z) -> x == -1 && z == 0);
        assertEquals(REJECT_TARGET_NOT_FULL, TransferSafety.M3_SAFE_LANDING.check(notFull, flat), "外擴 2 格的 chunk 不是 FULL");
        assertEquals(0, notFull.blockQueries, "chunk 不是 FULL 時不讀方塊");
    }

    @Test
    void fullChunkRangeExpandsTheBoxByTwoBlocksOnBothHorizontalAxes() {
        assertEquals(new TransferSafety.ChunkRange(-1, 0, -1, 0), TransferSafety.fullChunkRange(standing(0.5, 65, 0.5)));
        // 貼著 chunk 邊界：min−2 落在上一個 chunk、max+2 落在同一個 chunk。
        assertEquals(new TransferSafety.ChunkRange(0, 1, 0, 0), TransferSafety.fullChunkRange(new Box(17.9, 0, 2.0, 18.5, 1.8, 2.6)));
        assertEquals(new TransferSafety.ChunkRange(-2, -1, -2, -1), TransferSafety.fullChunkRange(new Box(-17.9, 0, -17.9, -17.3, 1.8, -17.3)));
        assertThrows(IllegalArgumentException.class, () -> TransferSafety.fullChunkRange(new Box(0, 0, 0, Double.POSITIVE_INFINITY, 1, 1)));
        assertTrue(TransferSafety.fullChunkRange(standing(0.5, 65, 0.5)).contains(-1, 0));
    }

    /**
     * 假的 {@link TransferSafety.Site}：預設所有 chunk 都 FULL、高度 −64..320、邊界 ±29999984；{@code solids} 是有碰撞、也有完整上表面的方塊，{@code fluids} 是流體。
     * 記下方塊與流體的查詢次數；{@link #forbidTerrain} 之後任何方塊、流體或地板的查詢都丟 {@link AssertionError}（證明政策不讀地形）。
     */
    public static final class FakeSite implements TransferSafety.Site {
        private final Set<BlockPos> solids = new HashSet<>(), fluids = new HashSet<>();
        private BiPredicate<Integer, Integer> notFull = (x, z) -> false;
        private int bottom = -64, top = 320;
        private double west = -29999984, east = 29999984, north = -29999984, south = 29999984;
        private boolean terrainForbidden;
        public int blockQueries;

        /** 沒有任何方塊（懸空）。 */
        public static FakeSite empty() { return new FakeSite(); }

        /** y=64 有一整層 −8..8 的地板。 */
        public static FakeSite flat() {
            var site = new FakeSite();
            for (int x = -8; x <= 8; x++) for (int z = -8; z <= 8; z++) site.solids.add(new BlockPos(x, 64, z));
            return site;
        }

        public FakeSite solid(BlockPos pos) { solids.add(pos); return this; }
        public FakeSite air(BlockPos pos) { solids.remove(pos); return this; }
        public FakeSite fluid(BlockPos pos) { fluids.add(pos); return this; }
        public FakeSite height(int bottom, int top) { this.bottom = bottom; this.top = top; return this; }
        public FakeSite border(double west, double east, double north, double south) {
            this.west = west; this.east = east; this.north = north; this.south = south; return this;
        }
        public FakeSite notFull(BiPredicate<Integer, Integer> notFull) { this.notFull = notFull; return this; }
        public FakeSite forbidTerrain() { terrainForbidden = true; return this; }

        @Override public boolean chunkFull(int chunkX, int chunkZ) { return !notFull.test(chunkX, chunkZ); }
        @Override public int bottomY() { return bottom; }
        @Override public int topY() { return top; }
        @Override public double borderWest() { return west; }
        @Override public double borderEast() { return east; }
        @Override public double borderNorth() { return north; }
        @Override public double borderSouth() { return south; }

        @Override public boolean spaceEmpty(Box box) {
            terrain("spaceEmpty");
            return solids.stream().noneMatch(pos -> new Box(pos).intersects(box));
        }

        @Override public boolean containsFluid(Box box) {
            terrain("containsFluid");
            return fluids.stream().anyMatch(pos -> new Box(pos).intersects(box));
        }

        @Override public boolean floorSupports(int x, int y, int z) {
            terrain("floorSupports");
            return solids.contains(new BlockPos(x, y, z));
        }

        private void terrain(String query) {
            blockQueries++;
            if (terrainForbidden) throw new AssertionError("政策不得讀地形：" + query);
        }
    }
}
