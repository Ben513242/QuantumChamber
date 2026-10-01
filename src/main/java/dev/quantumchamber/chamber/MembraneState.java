package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.Optional;
import net.minecraft.util.StringIdentifiable;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * 量子艙門 bulkhead 的 {@code membrane} 屬性值（M5 spec §10.1）：{@code none | sealed_x | sealed_z | passable_x | passable_z}。
 *
 * <ul>
 *   <li>{@code sealed_*}：量子膜有完整碰撞（PREPARE 期間）；{@code passable_*}：量子膜沒有碰撞（PASSAGE_READY）。</li>
 *   <li>後綴是門平面的水平軸，由門的 outwardFacing 推得（{@link #axisFor}）。</li>
 *   <li>{@code open=false} 時只能是 {@link #NONE}；守門在 {@link QuantumBulkheadBlock#withOpen}、
 *       {@link QuantumBulkheadBlock#withDoor} 與 {@link ChamberDoorService.DoorState}。</li>
 * </ul>
 *
 * 什麼時候套用哪個 membrane 不在這裡決定（F4b、F5 的 CollapseService）。
 */
public enum MembraneState implements StringIdentifiable {
    NONE("none", null, false),
    SEALED_X("sealed_x", Direction.Axis.X, true),
    SEALED_Z("sealed_z", Direction.Axis.Z, true),
    PASSABLE_X("passable_x", Direction.Axis.X, false),
    PASSABLE_Z("passable_z", Direction.Axis.Z, false);

    private final String id;
    private final @Nullable Direction.Axis axis;
    private final boolean sealed;

    MembraneState(String id, @Nullable Direction.Axis axis, boolean sealed) {
        this.id = id;
        this.axis = axis;
        this.sealed = sealed;
    }

    /** 有量子膜（畫出薄平面）；{@link #NONE} 是一般的開門或關門。 */
    public boolean isMembrane() {
        return axis != null;
    }

    /** 只有 {@code sealed_*} 有完整碰撞。 */
    public boolean sealed() {
        return sealed;
    }

    /** 門平面的水平軸；{@link #NONE} 沒有。 */
    public Optional<Direction.Axis> axis() {
        return Optional.ofNullable(axis);
    }

    /** 這個值能不能用在朝向為 {@code outwardFacing} 的門：{@link #NONE} 一律可以，量子膜的軸必須等於 {@link #axisFor}。 */
    public boolean fits(Direction outwardFacing) {
        return axis == null || axis == axisFor(outwardFacing);
    }

    /**
     * 門平面的水平軸：{@code outwardFacing.rotateYCounterclockwise().getAxis()}，也就是 {@link ChamberGeometry#localToWorld}
     * 中門板 local x（25 格的左右）所沿的軸，與門的法線（outwardFacing 的軸）垂直。NORTH／SOUTH 為 X，EAST／WEST 為 Z。
     *
     * @throws IllegalArgumentException outwardFacing 不是水平方向
     */
    public static Direction.Axis axisFor(Direction outwardFacing) {
        Objects.requireNonNull(outwardFacing, "outwardFacing");
        if (!outwardFacing.getAxis().isHorizontal()) throw new IllegalArgumentException("門的朝向必須是水平方向：" + outwardFacing);
        return outwardFacing.rotateYCounterclockwise().getAxis();
    }

    /** 朝向為 {@code outwardFacing} 的門的 sealed 量子膜。 */
    public static MembraneState sealedFor(Direction outwardFacing) {
        return axisFor(outwardFacing) == Direction.Axis.X ? SEALED_X : SEALED_Z;
    }

    /** 朝向為 {@code outwardFacing} 的門的 passable 量子膜。 */
    public static MembraneState passableFor(Direction outwardFacing) {
        return axisFor(outwardFacing) == Direction.Axis.X ? PASSABLE_X : PASSABLE_Z;
    }

    @Override
    public String asString() {
        return id;
    }
}
