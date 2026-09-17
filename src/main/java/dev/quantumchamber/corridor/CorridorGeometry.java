package dev.quantumchamber.corridor;

import dev.quantumchamber.chamber.ChamberFrame;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.BlockBox;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import dev.quantumchamber.registry.ModBlocks;
import java.util.Iterator;

/** 走廊使用無限局部座標；不擴張 ChamberGeometry 的 0..6 契約。 */
public final class CorridorGeometry {
    private CorridorGeometry() {}
    public static BlockPos block(ChamberFrame frame, int x, int y, int z) {
        horizontal(frame);
        return frame.controllerPos().offset(frame.outwardFacing().rotateYCounterclockwise(), x - 3)
                .add(0, y - 6, 0).offset(frame.outwardFacing().getOpposite(), z);
    }
    public static Vec3d position(ChamberFrame frame, double x, double y, double z) {
        var origin = block(frame, 0, 0, 0);
        var right = frame.outwardFacing().rotateYCounterclockwise();
        var inward = frame.outwardFacing().getOpposite();
        return new Vec3d(origin.getX() + Math.max(0, -right.getOffsetX() - inward.getOffsetX()), origin.getY(),
                origin.getZ() + Math.max(0, -right.getOffsetZ() - inward.getOffsetZ()))
                .add(vector(frame, new Vec3d(x, y, z)));
    }
    public static Vec3d localPosition(ChamberFrame frame, Vec3d position) {
        return localVector(frame, position.subtract(position(frame, 0, 0, 0)));
    }
    public static Vec3d vector(ChamberFrame frame, Vec3d local) {
        horizontal(frame);
        var right = frame.outwardFacing().rotateYCounterclockwise();
        var inward = frame.outwardFacing().getOpposite();
        return new Vec3d(right.getOffsetX() * local.x + inward.getOffsetX() * local.z, local.y,
                right.getOffsetZ() * local.x + inward.getOffsetZ() * local.z);
    }
    public static Vec3d localVector(ChamberFrame frame, Vec3d physical) {
        horizontal(frame);
        var right = frame.outwardFacing().rotateYCounterclockwise();
        var inward = frame.outwardFacing().getOpposite();
        return new Vec3d(right.getOffsetX() * physical.x + right.getOffsetZ() * physical.z, physical.y,
                inward.getOffsetX() * physical.x + inward.getOffsetZ() * physical.z);
    }
    private static void horizontal(ChamberFrame frame) {
        if (frame.outwardFacing().getAxis().isVertical()) throw new IllegalArgumentException("走廊只支援水平朝向");
    }
    static ChamberFrame frame(CorridorPageManager.MappingView view) {
        return new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(), 3).up(6),
                view.outwardFacing());
    }
    static ChamberFrame entrance(CorridorPageManager.MappingView view) {
        var frame = frame(view);
        return new ChamberFrame(frame.controllerPos().offset(view.outwardFacing().getOpposite(),
                Math.toIntExact(-view.logicalAnchorBlock())), view.outwardFacing());
    }
    static boolean hasEntrance(CorridorPageManager.MappingView view) {
        return view.aliasStartBlock() <= -2 && view.aliasEndBlock() >= 9;
    }
    static BlockBox relativeBounds(CorridorLayout.LayoutComponent component, long anchor, net.minecraft.util.math.Direction facing) {
        var frame = new ChamberFrame(BlockPos.ORIGIN, facing);
        var first = block(frame,0,0,Math.toIntExact(component.aliasStartBlock()-anchor));
        var last = block(frame,6,6,Math.toIntExact(component.aliasEndBlock()-anchor-1));
        return BlockBox.create(first,last);
    }
    record Cell(BlockPos position, BlockState state) {}
    /** 串流產生有限幾何，記憶體不隨 alias 體積成長。 */
    static final class Builder {
        private final CorridorPageManager.MappingView view;
        private final boolean frontOpen, rearOpen;
        private final long volume;
        private long index;
        private int overlay;
        Builder(CorridorPageManager.MappingView view, boolean frontOpen, boolean rearOpen) {
            this.view=view; this.frontOpen=frontOpen; this.rearOpen=rearOpen;
            volume = Math.multiplyExact(view.aliasEndBlock()-view.aliasStartBlock(),49);
            if (!hasEntrance(view)) overlay=343;
        }
        Cell next() {
            while (index < volume) {
                long cursor=index++;
                int x=(int)(cursor%7), y=(int)(cursor/7%7);
                long logicalZ=view.aliasStartBlock()+cursor/49;
                // 只有完整物化入口才排除 343 格；切邊 alias 仍須填滿普通走廊與端蓋。
                if (hasEntrance(view) && logicalZ >= 0 && logicalZ <= 6) continue;
                boolean side=x==0 || x==6;
                boolean shell=side || y==0 || y==6 || logicalZ==view.aliasStartBlock() || logicalZ==view.aliasEndBlock()-1;
                BlockState state=shell ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                if (side && y>0 && y<6 && Math.floorMod(logicalZ,8)>=1 && Math.floorMod(logicalZ,8)<=5) {
                    state=ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
                }
                return new Cell(block(frame(view),x,y,Math.toIntExact(logicalZ-view.logicalAnchorBlock())),state);
            }
            if (overlay>=343) return null;
            int cursor=overlay++, x=cursor%7, y=cursor/7%7, z=cursor/49;
            var frame=entrance(view);
            return new Cell(block(frame,x,y,z),SessionEntranceAllocator.cell(frame,x,y,z,frontOpen,rearOpen));
        }
    }
    static Iterator<BlockPos> clearing(BlockBox box) {
        return BlockPos.iterate(box.getMinX(),box.getMinY(),box.getMinZ(),box.getMaxX(),box.getMaxY(),box.getMaxZ()).iterator();
    }
}
