package dev.quantumchamber.gametest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.BlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * F1a fix round 1：enter 的受理條件必須與 transfer 的安全檢查一致（bbox 下方每一欄都是完整頂面、bbox 不與方塊重疊），
 * 否則 enter 成功之後，return 會在同一個來源位置被 REJECT_TARGET_UNSAFE 拒絕。只測純判斷，不走指令路徑。
 */
class M5ClientProbeSafetyTest {
    private static final int FEET_Y = 64;

    @BeforeAll
    static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void acceptsStandingInsideOneFullBlockColumn() {
        var world = new MapView(Map.of(new BlockPos(8, FEET_Y - 1, 8), Blocks.STONE.getDefaultState()));
        assertNull(refusal(player(8.5, 8.5), world, true));
    }

    @Test
    void refusesLeavesUnderTheFeet() {
        var world = new MapView(Map.of(new BlockPos(8, FEET_Y - 1, 8), Blocks.OAK_LEAVES.getDefaultState()));
        String refusal = refusal(player(8.5, 8.5), world, true);
        assertNotNull(refusal, "樹葉的頂面不是完整方塊，transfer 的安全檢查會拒絕");
        assertTrue(refusal.contains("8, 63, 8"), refusal);
    }

    @Test
    void refusesStandingOnTheEdgeOverAir() {
        // bbox x=8.65..9.25 跨兩欄：x=8 是石頭，x=9 是空氣。onGround 為真，但 transfer 要求每一欄都有完整頂面。
        var world = new MapView(Map.of(new BlockPos(8, FEET_Y - 1, 8), Blocks.STONE.getDefaultState()));
        String refusal = refusal(player(8.95, 8.5), world, true);
        assertNotNull(refusal, "站在方塊邊緣、腳下一半是空氣時必須拒絕");
        assertTrue(refusal.contains("9, 63, 8"), refusal);
    }

    @Test
    void refusesWhenTheBoxIsNotEmpty() {
        var world = new MapView(Map.of(new BlockPos(8, FEET_Y - 1, 8), Blocks.STONE.getDefaultState()));
        assertNotNull(refusal(player(8.5, 8.5), world, false), "isSpaceEmpty 不成立時必須拒絕");
    }

    @Test
    void requiredChunksMatchTheTransferSafetyRangeAcrossChunkBorders() {
        assertEquals(List.of(new ChunkPos(-1, 0), new ChunkPos(0, 0)),
                List.copyOf(M5ClientProbe.Probe.requiredChunks(new Box(-0.3, FEET_Y, 8.2, 0.3, FEET_Y + 1.8, 8.8))));
        assertEquals(List.of(new ChunkPos(0, 0), new ChunkPos(1, 0)),
                List.copyOf(M5ClientProbe.Probe.requiredChunks(new Box(13.7, FEET_Y, 8.2, 14.3, FEET_Y + 1.8, 8.8))));
        assertEquals(List.of(new ChunkPos(-1, -1), new ChunkPos(-1, 0), new ChunkPos(0, -1), new ChunkPos(0, 0)),
                List.copyOf(M5ClientProbe.Probe.requiredChunks(new Box(-0.3, FEET_Y, -0.3, 0.3, FEET_Y + 1.8, 0.3))));
        assertEquals(List.of(new ChunkPos(0, 0)),
                List.copyOf(M5ClientProbe.Probe.requiredChunks(new Box(8.2, FEET_Y, 8.2, 8.8, FEET_Y + 1.8, 8.8))));
    }

    private static String refusal(Box box, BlockView world, boolean spaceEmpty) {
        return M5ClientProbe.Probe.standingRefusal(false, true, box, world, false, spaceEmpty);
    }

    private static Box player(double x, double z) {
        return new Box(x - 0.3, FEET_Y, z - 0.3, x + 0.3, FEET_Y + 1.8, z + 0.3);
    }

    private static final class MapView implements BlockView {
        private final Map<BlockPos, BlockState> states;

        MapView(Map<BlockPos, BlockState> states) { this.states = new HashMap<>(states); }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getBottomY() { return -64; }
    }
}
