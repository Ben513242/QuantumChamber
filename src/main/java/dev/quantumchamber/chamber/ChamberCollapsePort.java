package dev.quantumchamber.chamber;

import java.util.Objects;
import java.util.UUID;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * {@code chamber} 往 M5 塌縮流程（{@code collapse} package）的反轉介面（M5 F4b-3 Step 0，F4b-1 quality Q-m1）：{@code chamber} 不 import {@code collapse}，
 * 由 {@code collapse} 在 mod 初始化時註冊實作（比照 {@link ChamberSessions#install} 與 {@link ChamberProtectionService#registerAdditionalGuard}）。
 * <ul>
 * <li>{@link Projections}：動態宇宙的投影保護與載入辨識（M5 spec §9.3），由 {@code ProjectionProtectionIndex.registerEvents()} 註冊。</li>
 * <li>{@link Doors}：原艙門的攔截（spec §7 開頭、§7.3「PREPARE 期間的開關門」），排在 {@link ChamberDoorService} 的門切換之前，由
 *     {@code CollapseService.initialize()} 註冊。F5 的通道期間（§10.5）也接在同一個介面。</li>
 * <li>{@link Membranes}：找不到所屬 session、卻帶著量子膜的原艙門的正規化（spec §10.6 :613，M5 F5-2）：{@link ChamberPowerCoordinator} 刷新該艙時交給塌縮流程排入下一個
 *     tick，由 {@code CollapseService.initialize()} 註冊。</li>
 * </ul>
 * 沒有註冊時（例如只有 M1 的 JUnit）一律等同「沒有投影、不攔截、不正規化」：{@link Projections#NONE}、{@link Doors#NONE}、{@link Membranes#NONE}。
 */
public final class ChamberCollapsePort {
    private static volatile Projections projections = Projections.NONE;
    private static volatile Doors doors = Doors.NONE;
    private static volatile Membranes membranes = Membranes.NONE;

    private ChamberCollapsePort() {}

    /** 目前註冊的投影查詢；沒有註冊時是 {@link Projections#NONE}。 */
    public static Projections projections() { return projections; }

    /** 目前註冊的原艙門攔截；沒有註冊時是 {@link Doors#NONE}。 */
    public static Doors doors() { return doors; }

    /** 目前註冊的量子膜正規化；沒有註冊時是 {@link Membranes#NONE}。 */
    public static Membranes membranes() { return membranes; }

    /** 註冊投影查詢（取代之前註冊的）。 */
    public static void installProjections(Projections replacement) { projections = Objects.requireNonNull(replacement, "projections"); }

    /** 註冊原艙門攔截（取代之前註冊的）。 */
    public static void installDoors(Doors replacement) { doors = Objects.requireNonNull(replacement, "doors"); }

    /** 註冊量子膜正規化（取代之前註冊的）。 */
    public static void installMembranes(Membranes replacement) { membranes = Objects.requireNonNull(replacement, "membranes"); }

    /**
     * 動態宇宙（非 vanilla 的 world key）的投影查詢（spec §9.3）。兩個方法都在熱路徑上（每次 {@code setBlockState}、每次 Controller 刷新），只能讀記憶體。
     */
    public interface Projections {
        /** 沒有投影：沒有任何格受保護，也沒有投影的 Controller。 */
        Projections NONE = new Projections() {
            @Override public boolean protectedCell(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) { return false; }
            @Override public boolean projectionControllerAt(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos) { return false; }
        };

        /** 這一格是否受投影保護（{@link ChamberProtectionService#mayMutate} 在非 vanilla 的 world key 時拒絕未授權的變更）。 */
        boolean protectedCell(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos);

        /** 這個位置是否是已確認投影的 Controller（{@link ChamberPowerCoordinator} 的載入辨識：inert 的 PROJECTION）。 */
        boolean projectionControllerAt(MinecraftServer server, RegistryKey<World> worldKey, BlockPos pos);
    }

    /**
     * 原艙門的攔截：玩家以 bulkhead 或 Controller 開關原艙門時，在 server thread 上、門切換之前呼叫（{@code WorldChamberDoorAdapter.toggle}）。實作不得丟出例外；
     * 回 {@link DoorUse#PASS} 以外的值時，門切換不執行（量子膜門不經 {@link ChamberDoorService#toggle}：它的 rollback 只寫回開門，會失去量子膜）。
     */
    @FunctionalInterface
    public interface Doors {
        /** 不攔截：一律照一般門的邏輯。 */
        Doors NONE = (world, frame, player) -> DoorUse.PASS;

        /** {@code player} 使用 {@code world} 裡 {@code frame} 這座艙的門。 */
        DoorUse use(ServerWorld world, ChamberFrame frame, PlayerEntity player);
    }

    /**
     * 找不到所屬 session、卻帶著量子膜的原艙門（spec §10.6 :613；例如終局之後、改門之前 crash）的正規化：{@link ChamberPowerCoordinator} 在 ORIGIN 分支協調完、艙體的 chunk 都已載入、
     * 門的 25 格有任何一格帶著量子膜、而且 presence 不是 {@code COLLAPSE}／{@code PASSAGE} 時，在 server thread 上呼叫（F6-0 補上 chunk 的條件，F5-2 quality Nit）。實作只排入下一個 tick 的正規化（不在刷新的當下寫門：刷新可能就在 chunk 載入的
     * load-sync 裡），套用時再確認沒有 M5 塌縮狀態的 session 擁有這扇門（有 COLLAPSING、SEALED、PASSAGE_READY 時由該 session 的門邏輯負責）。實作不得丟出例外。
     */
    @FunctionalInterface
    public interface Membranes {
        /** 不正規化（沒有塌縮流程）。 */
        Membranes NONE = (world, frame, chamber) -> { };

        /** {@code world} 裡 {@code frame} 這座原艙（registry 的 {@code chamber}）的門帶著量子膜，而且沒有 M5 塌縮狀態的 session。 */
        void normalizeOwnerless(ServerWorld world, ChamberFrame frame, UUID chamber);
    }

    /** {@link Doors#use} 的結果。 */
    public enum DoorUse {
        /** 不屬於塌縮流程，或門本來就是一般狀態的階段：照一般門的邏輯切換。 */
        PASS,
        /** 塌縮流程已處理（例如 checked 終局之後改門，或讓艙內的非參與者開門）：不再切換。 */
        HANDLED,
        /** 拒絕：門不變，也不切換（不顯示訊息）。 */
        REFUSED
    }
}
