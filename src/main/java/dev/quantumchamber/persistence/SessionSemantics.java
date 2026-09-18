package dev.quantumchamber.persistence;

import dev.quantumchamber.superposition.SessionState;
import java.util.Objects;
import net.minecraft.util.math.Direction;

/** Session 的不可變效果政策與方向語意。 */
public enum SessionSemantics {
    LEGACY_FORWARD_CONSUMED, LATERAL_BUFF_MAINTAINED;

    public void validateEffectPolicy(SessionState state,boolean restoreEntryEffectOnReturn) {
        Objects.requireNonNull(state,"state");
        boolean invalid=this==LATERAL_BUFF_MAINTAINED ? restoreEntryEffectOnReturn
                : state==SessionState.ARMING && !restoreEntryEffectOnReturn
                        || state==SessionState.SUPERPOSITION && restoreEntryEffectOnReturn;
        if (invalid) {
            throw new IllegalArgumentException("狀態與效果恢復政策不一致");
        }
    }
    public Direction corridorFacing(Direction sourceFacing) {
        var facing=horizontal(sourceFacing);
        return this==LATERAL_BUFF_MAINTAINED ? facing.rotateYClockwise() : facing;
    }
    public Direction sourceFacing(Direction corridorFacing) {
        var facing=horizontal(corridorFacing);
        return this==LATERAL_BUFF_MAINTAINED ? facing.rotateYCounterclockwise() : facing;
    }
    private static Direction horizontal(Direction facing) {
        Objects.requireNonNull(facing,"facing");
        if (facing.getAxis().isVertical()) throw new IllegalArgumentException("方向必須水平");
        return facing;
    }
}
