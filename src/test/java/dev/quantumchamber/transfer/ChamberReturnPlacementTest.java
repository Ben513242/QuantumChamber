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
    /**
     * R2 M6：返還落點避開原艙內的方塊。順序不變；被擋的 slot 跳過、後面的人依序遞補且不重複；地板全被擋時改站上一層（最高 y=4）；
     * 全部被擋時沿用原本的地板 slot。
     */
    @Test void returnSlotsSkipBlockedCellsThenRaiseOrFallBackDeterministically() {
        var people = List.of(id(1),id(2));
        var sources = Map.of(id(1),new Vec3d(5.5,1,1.5),id(2),new Vec3d(4.5,1,1.5));
        var plain = ChamberReturnPlacement.plan(frame,people,sources);
        assertEquals(Map.of(id(1),new Vec3d(5.5,1,1.5),id(2),new Vec3d(4.5,1,1.5)),plain,"fixture：預設的地板 slot");
        assertEquals(plain,ChamberReturnPlacement.plan(frame,people,sources,position -> true),"沒有碰撞時與原本的配置完全相同");
        var blocked = new Vec3d(5.5,1,1.5);
        assertEquals(Map.of(id(1),new Vec3d(4.5,1,1.5),id(2),new Vec3d(3.5,1,1.5)),
                ChamberReturnPlacement.plan(frame,people,sources,position -> !position.equals(blocked)),"被擋的 slot 跳過，後面的人依序遞補、不重複");
        assertEquals(Map.of(id(1),new Vec3d(5.5,2,1.5),id(2),new Vec3d(4.5,2,1.5)),
                ChamberReturnPlacement.plan(frame,people,sources,position -> position.y>=2),"地板全部被擋時改站上一層");
        assertEquals(Map.of(id(1),new Vec3d(5.5,4,1.5),id(2),new Vec3d(4.5,4,1.5)),
                ChamberReturnPlacement.plan(frame,people,sources,position -> position.y>=4),"最高到 y=4（bbox 仍完整在 interior 內）");
        assertEquals(plain,ChamberReturnPlacement.plan(frame,people,sources,position -> position.y>=5),"y=5 不是候選：bbox 會超出 interior");
        assertEquals(plain,ChamberReturnPlacement.plan(frame,people,sources,position -> false),"全部被擋時沿用原本的地板 slot");
    }
    /**
     * R2 M6（F3d fix round 2，quality m-1）：部分被擋、部分被取走的混合情況。站上一層時先選這一欄還沒有人的候選，不疊在別人頭上；
     * 全部被擋時的退路取第一個還沒被取走的地板 slot，不與前面的人重複。
     */
    @Test void returnSlotsNeitherStackNorRepeatWhenSomeCellsAreBlockedAndOthersTaken() {
        var sources = Map.of(id(1),new Vec3d(5.5,1,1.5),id(2),new Vec3d(4.5,1,1.5),id(3),new Vec3d(3.5,1,1.5));
        var onlyFirstFloor = new Vec3d(5.5,1,1.5);
        assertEquals(Map.of(id(1),onlyFirstFloor,id(2),new Vec3d(4.5,2,1.5),id(3),new Vec3d(3.5,2,1.5)),
                ChamberReturnPlacement.plan(frame,List.of(id(1),id(2),id(3)),sources,position -> position.y>=2 || position.equals(onlyFirstFloor)),
                "地板只剩第一格：第一位站地板，其他人站上一層，而且不在他頭上");
        var onlySecondFloor = new Vec3d(4.5,1,1.5);
        var placed = ChamberReturnPlacement.plan(frame,List.of(id(1),id(2)),Map.of(id(1),sources.get(id(1)),id(2),sources.get(id(2))),
                position -> position.equals(onlySecondFloor));
        assertEquals(Map.of(id(1),onlySecondFloor,id(2),new Vec3d(5.5,1,1.5)),placed,
                "唯一不被擋的地板 slot 被第一位取走之後，第二位的退路是第一個沒被取走的地板 slot，不重複");
    }
    @Test void incompleteOrNonfiniteSourceAuthorityIsRejected() {
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,List.of(id(1)),Map.of()));
        assertThrows(IllegalArgumentException.class,() -> ChamberReturnPlacement.plan(frame,List.of(id(1)),
                Map.of(id(1),new Vec3d(Double.NaN,1,1))));
    }
}
