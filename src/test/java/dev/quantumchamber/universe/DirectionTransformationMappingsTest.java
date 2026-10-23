package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.util.math.AxisTransformation;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.DirectionTransformation;
import org.junit.jupiter.api.Test;

/**
 * M5 H8（spec §4 H8、§20.14；盤點與根因見 M5 implementation note 的 H8 一節）：方向轉換表的同時初始化的單元層級回歸測試。
 * <p>原版的 {@code DirectionTransformation} 是 enum，所有世界與執行緒共用同一組常數。{@code map(Direction)} 以 lazy init 建立 {@code mappings}：先把空的
 * {@code EnumMap} 指定給欄位，再逐一 put（每次 put 都重新讀欄位）。兩條 worldgen worker 同時第一次用同一個轉換時，後到的一方可能看到還沒填完的 map，{@code get}
 * 回 null；jigsaw 方塊的 {@code rotate}（{@code mapJigsawOrientation}）因此丟 {@code NullPointerException}（{@code "facing" is null}）。production 的
 * {@code DirectionTransformationEagerMappingsMixin} 在 class 初始化結束時建好每個常數的 {@code mappings}；fabric-loader-junit 會套用 production mixin（同
 * {@code NativeSaveFailuresTest}），所以這裡驗的是 production 的類別。
 * <p>判準是不變量，不靠時序：class 初始化之後、這個 test 呼叫任何 {@code map} 之前，以反射讀每個常數的 {@code mappings}，必須都已建立、各有 6 個方向，內容與原版的
 * 計算相同（以原版的 {@code axisTransformation}、{@code AxisTransformation.map}、{@code shouldFlipDirection} 與 {@code Direction.from} 逐一重算）；之後 {@code map} 的
 * 回傳值也必須相同。依目前的 test 順序，原版 common 程式只經 {@code JigsawBlock}、{@code CrafterBlock} 的 rotate／mirror 呼叫 {@code map}，最多用到
 * {@code BlockRotation} 與 {@code BlockMirror} 的 6 個常數；因此修正前仍有未建立的常數。若日後同 JVM 的其他 test 先把 48 個常數都用過，這個回歸會被遮住，
 * 須維持或重驗此前提。讀取之前已建立的常數是診斷，輸出到 stdout（{@code H8_DIRECTION_TRANSFORMATION}，test report 的 system-out）。
 */
final class DirectionTransformationMappingsTest {
    @Test
    void everyTransformationHasCompleteMappingsRightAfterClassInitialization() throws ReflectiveOperationException {
        // 觸發（或確認已完成）class 初始化；values() 只複製常數陣列，不呼叫 map。
        var transformations = DirectionTransformation.values();
        var mappingsField = DirectionTransformation.class.getDeclaredField("mappings");
        mappingsField.setAccessible(true);
        var axisField = DirectionTransformation.class.getDeclaredField("axisTransformation");
        axisField.setAccessible(true);
        // 先讀完每個常數的欄位，之後才做任何會呼叫 map 的事。
        var snapshot = new Object[transformations.length];
        for (int i = 0; i < transformations.length; i++) snapshot[i] = mappingsField.get(transformations[i]);

        var missing = new ArrayList<String>();
        var wrong = new ArrayList<String>();
        var mapWrong = new ArrayList<String>();
        var mapClasses = new TreeSet<String>();
        for (int i = 0; i < transformations.length; i++) {
            var transformation = transformations[i];
            var axisTransformation = (AxisTransformation) axisField.get(transformation);
            if (snapshot[i] == null) {
                missing.add(transformation.asString());
            } else {
                var built = (Map<?, ?>) snapshot[i];
                mapClasses.add(built.getClass().getName());
                if (built.size() != Direction.values().length) wrong.add(transformation.asString() + " size=" + built.size());
                for (var direction : Direction.values()) {
                    var expected = vanillaMapping(transformation, axisTransformation, direction);
                    if (built.get(direction) != expected) wrong.add(transformation.asString() + ":" + direction + "=" + built.get(direction) + " expected=" + expected);
                }
            }
            for (var direction : Direction.values()) {
                var expected = vanillaMapping(transformation, axisTransformation, direction);
                var actual = transformation.map(direction);
                if (actual != expected) mapWrong.add(transformation.asString() + ":" + direction + "=" + actual + " expected=" + expected);
            }
        }
        System.out.println("H8_DIRECTION_TRANSFORMATION constants=" + transformations.length + " builtBeforeRead=" + (transformations.length - missing.size())
                + " missing=" + missing.size() + " mapClasses=" + mapClasses + " wrong=" + wrong + " mapWrong=" + mapWrong + " missingNames=" + missing);
        assertAll(
                () -> assertEquals(List.of(), missing, () -> "class 初始化之後仍沒有建立 mappings 的常數（原版的 lazy init；之後第一次同時使用會 race）：" + missing.size()
                        + "/" + transformations.length),
                () -> assertEquals(List.of(), wrong, "class 初始化時建立的 mappings 與原版的計算不同"),
                () -> assertEquals(List.of(), mapWrong, "map 的回傳值與原版的計算不同"));
    }

    /** 原版 {@code DirectionTransformation.map}（1.21 yarn sources :149-164）對一個方向的計算，以原版的 public 方法逐步重算。 */
    private static Direction vanillaMapping(DirectionTransformation transformation, AxisTransformation axisTransformation, Direction direction) {
        var axis = Direction.Axis.values()[axisTransformation.map(direction.getAxis().ordinal())];
        var axisDirection = transformation.shouldFlipDirection(axis) ? direction.getDirection().getOpposite() : direction.getDirection();
        return Direction.from(axis, axisDirection);
    }
}
