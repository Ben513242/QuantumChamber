package dev.quantumchamber.corridor;

import java.util.Objects;
import java.util.UUID;

/**
 * M7 HOME 走廊唯一的 HOME 門（spec §4.2）：{@code HomeDoorKey=(SessionUuid, LogicalStationIndex=1, WallSide=POSITIVE_LATERAL)}。
 * station0 的 POSITIVE 側是 lateral 入口 overlay（入口返還門），不得作 HOME。
 */
public final class HomeDoorLayout {
    private static final long STATION = 1;
    private static final DoorKey.DoorWallSide WALL_SIDE = DoorKey.DoorWallSide.POSITIVE_LATERAL;

    private HomeDoorLayout() {}

    /** 這個 session 唯一的 HOME 門。 */
    public static DoorKey key(UUID sessionUuid) {
        return new DoorKey(Objects.requireNonNull(sessionUuid, "sessionUuid"), STATION, WALL_SIDE);
    }

    /**
     * 走廊局部格（x、y 為 0..6 的走廊斷面，logicalZ 為邏輯 block z）是否屬於 HOME 門的 25 格：
     * POSITIVE_LATERAL 側（x=6，與 {@link CorridorGeometry#sideDoorKey} 相同）、y 1..5、station1 的門格（邏輯 z 9..13）。
     */
    public static boolean isHomeCell(int x, int y, long logicalZ) {
        int wallX = WALL_SIDE == DoorKey.DoorWallSide.POSITIVE_LATERAL ? 6 : 0;
        return x == wallX && y >= 1 && y <= 5 && Math.floorDiv(logicalZ, 8) == STATION
                && Math.floorMod(logicalZ, 8) >= 1 && Math.floorMod(logicalZ, 8) <= 5;
    }
}
