package dev.quantumchamber.chamber;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/** 原艙仿射座標；與無限走廊的模式分離。 */
public final class ChamberSpaceCoordinates {
    private ChamberSpaceCoordinates() {}
    public static BlockPos block(ChamberFrame frame,int x,int y,int z) {
        horizontal(frame);
        return frame.controllerPos().offset(frame.outwardFacing().rotateYCounterclockwise(),x-3)
                .add(0,y-6,0).offset(frame.outwardFacing().getOpposite(),z);
    }
    public static Vec3d position(ChamberFrame frame,double x,double y,double z) {
        var local=new Vec3d(x,y,z); finite(local);
        var origin=block(frame,0,0,0);
        var right=frame.outwardFacing().rotateYCounterclockwise(); var inward=frame.outwardFacing().getOpposite();
        return new Vec3d(origin.getX()+Math.max(0,-right.getOffsetX()-inward.getOffsetX()),origin.getY(),
                origin.getZ()+Math.max(0,-right.getOffsetZ()-inward.getOffsetZ())).add(vector(frame,local));
    }
    public static Vec3d vector(ChamberFrame frame,Vec3d value) {
        horizontal(frame); finite(value);
        var right=frame.outwardFacing().rotateYCounterclockwise(); var inward=frame.outwardFacing().getOpposite();
        return new Vec3d(right.getOffsetX()*value.x+inward.getOffsetX()*value.z,value.y,right.getOffsetZ()*value.x+inward.getOffsetZ()*value.z);
    }
    public static Vec3d localPosition(ChamberFrame frame,Vec3d value) {
        finite(value); return localVector(frame,value.subtract(position(frame,0,0,0)));
    }
    public static Vec3d localVector(ChamberFrame frame,Vec3d value) {
        horizontal(frame); finite(value);
        var right=frame.outwardFacing().rotateYCounterclockwise(); var inward=frame.outwardFacing().getOpposite();
        return new Vec3d(right.getOffsetX()*value.x+right.getOffsetZ()*value.z,value.y,inward.getOffsetX()*value.x+inward.getOffsetZ()*value.z);
    }
    private static void horizontal(ChamberFrame frame) {
        if(frame.outwardFacing().getAxis().isVertical()) throw new IllegalArgumentException("原艙座標只支援水平朝向");
    }
    private static void finite(Vec3d value) {
        if(!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) throw new IllegalArgumentException("原艙座標必須有限");
    }
}
