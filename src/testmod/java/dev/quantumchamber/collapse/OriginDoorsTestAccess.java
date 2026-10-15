package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberFrame;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;

/**
 * 僅 testmod（M5 F6-2d-2 B2a，B1 quality Minor 1）：讓跨 JVM 的塌縮 probe（{@code M5CollapseDoors}）查詢 production 的 {@link OriginDoors} 還沒套用的原艙門，證明重新套用
 * 排入的那一扇在那個 tick 已被消耗（移除），不是延後到之後的 tick 才套用。{@link CollapseService} 的 instance 表與 {@code OriginDoors.doors} 都是 private，以反射讀取（本 mod
 * 自己的欄位，不經 remap）；{@code CollapseService.originDoors}、{@code OriginDoors.PendingDoor} 與它的 {@code goal} 是 package-private，所以放在同一個 package（split package，
 * 比照 {@link CollapseServiceTestAccess}）。只查詢、不改任何狀態，只在 server thread 呼叫。release 與 sources JAR 不含這個類別。F6-2d-2 B2 另轉出 package-private 的門口判斷
 * （{@link #doorwayOccupied}、{@link #doorwayEntitiesLoaded}），SEALED 的門窗口以 production 的同一個範圍判斷門口。
 * <p>F6-2e Step 0（F6-2d-2 B2 quality nit）：反射取得的欄位快取起來（原本每次 {@code getDeclaredField}）；讀到的值型別不符時丟出帶欄位名稱的 {@link IllegalStateException}
 * （原本直接 cast，型別不符時是沒有上下文的 {@link ClassCastException}）；probe 比對的目標名稱集中成常數（{@link #CLOSE_ON_SEALED}、{@link #NORMALIZE}，原本字串散在
 * {@code M5CollapseDoors} 與 {@code M5CollapseDoorChains} 多處）。
 */
public final class OriginDoorsTestAccess {
    /** 重新套用的目標 CLOSE_ON_SEALED（SEALED 的關門）的名稱：production 的 {@code OriginDoors.DoorGoal} 的名稱，那裡改名時這裡不能編譯。 */
    public static final String CLOSE_ON_SEALED = OriginDoors.DoorGoal.CLOSE_ON_SEALED.name();
    /** 重新套用的目標 NORMALIZE（COLLAPSING 的重新套用：量子膜改為一般開門）的名稱；同 {@link #CLOSE_ON_SEALED}。 */
    public static final String NORMALIZE = OriginDoors.DoorGoal.NORMALIZE.name();

    /** 反射取得、已 {@code setAccessible} 的欄位（鍵是「類別名.欄位名」）。 */
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private OriginDoorsTestAccess() {}

    /**
     * {@code server} 的塌縮流程還沒套用的原艙門裡，原艙 {@code chamber} 的那一扇的目標（{@code DoorGoal} 的名稱）；沒有時為 empty。這個 server 沒有塌縮流程時丟出（不把
     * 「讀不到」當成「沒有待套用的門」）。
     */
    public static Optional<String> pendingGoal(MinecraftServer server, UUID chamber) {
        var instances = as(Map.class, field(CollapseService.class, null, "INSTANCES"), "CollapseService.INSTANCES");
        if (!(instances.get(server) instanceof CollapseService service)) {
            throw new IllegalStateException("沒有此 server 的 CollapseService");
        }
        var doors = as(Map.class, field(OriginDoors.class, service.originDoors, "doors"), "OriginDoors.doors");
        var door = doors.get(chamber);
        return door == null ? Optional.empty() : Optional.of(as(OriginDoors.PendingDoor.class, door, "OriginDoors.doors 的值").goal.name());
    }

    /**
     * F6-2d-2 B2（F6-2-09）：production 的門口判斷（{@code OriginDoors.doorwayOccupied}：原艙門 25 格的外框裡有任何已載入的 entity 的 bbox），probe 以同一個範圍判斷門口有沒有人。
     * 只查詢。
     */
    public static boolean doorwayOccupied(ServerWorld world, ChamberFrame frame) {
        return OriginDoors.doorwayOccupied(world, frame);
    }

    /**
     * F6-2d-2 B2（F6-2-09）：production 的「門口的 entity storage 已載入」（{@code OriginDoors.doorwayEntitiesLoaded}；SEALED 的關門在它成立之前不判斷門口）。只查詢、不載入。
     */
    public static boolean doorwayEntitiesLoaded(ServerWorld world, ChamberFrame frame) {
        return OriginDoors.doorwayEntitiesLoaded(world, frame);
    }

    /** {@code value} 是 {@code type} 時交出；否則丟出帶 {@code what}（欄位名稱）的 {@link IllegalStateException}。 */
    private static <T> T as(Class<T> type, Object value, String what) {
        if (!type.isInstance(value)) {
            throw new IllegalStateException(what + " 不是 " + type.getSimpleName() + "：" + (value == null ? "null" : value.getClass().getName()));
        }
        return type.cast(value);
    }

    private static Object field(Class<?> type, Object target, String name) {
        var field = FIELDS.computeIfAbsent(type.getName() + "." + name, key -> accessible(type, name));
        try {
            return field.get(target);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("讀不到 " + type.getSimpleName() + "." + name, failure);
        }
    }

    private static Field accessible(Class<?> type, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("讀不到 " + type.getSimpleName() + "." + name, failure);
        }
    }
}
