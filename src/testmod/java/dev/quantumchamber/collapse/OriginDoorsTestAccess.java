package dev.quantumchamber.collapse;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/**
 * 僅 testmod（M5 F6-2d-2 B2a，B1 quality Minor 1）：讓跨 JVM 的塌縮 probe（{@code M5CollapseDoors}）查詢 production 的 {@link OriginDoors} 還沒套用的原艙門，證明重新套用
 * 排入的那一扇在那個 tick 已被消耗（移除），不是延後到之後的 tick 才套用。{@link CollapseService} 的 instance 表與 {@code OriginDoors.doors} 都是 private，以反射讀取（本 mod
 * 自己的欄位，不經 remap）；{@code CollapseService.originDoors}、{@code OriginDoors.PendingDoor} 與它的 {@code goal} 是 package-private，所以放在同一個 package（split package，
 * 比照 {@link CollapseServiceTestAccess}）。只查詢、不改任何狀態，只在 server thread 呼叫。release 與 sources JAR 不含這個類別。
 */
public final class OriginDoorsTestAccess {
    private OriginDoorsTestAccess() {}

    /**
     * {@code server} 的塌縮流程還沒套用的原艙門裡，原艙 {@code chamber} 的那一扇的目標（{@code DoorGoal} 的名稱）；沒有時為 empty。這個 server 沒有塌縮流程時丟出（不把
     * 「讀不到」當成「沒有待套用的門」）。
     */
    public static Optional<String> pendingGoal(MinecraftServer server, UUID chamber) {
        if (!(((Map<?, ?>) field(CollapseService.class, null, "INSTANCES")).get(server) instanceof CollapseService service)) {
            throw new IllegalStateException("沒有此 server 的 CollapseService");
        }
        var door = ((Map<?, ?>) field(OriginDoors.class, service.originDoors, "doors")).get(chamber);
        return door == null ? Optional.empty() : Optional.of(((OriginDoors.PendingDoor) door).goal.name());
    }

    private static Object field(Class<?> type, Object target, String name) {
        try {
            var field = type.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("讀不到 " + type.getSimpleName() + "." + name, failure);
        }
    }
}
