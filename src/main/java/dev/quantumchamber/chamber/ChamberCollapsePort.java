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
 * <li>{@link Roots}：回程旅程的原艙停用防線（M7 spec §9.1）：registry 的停用、啟用與移除提交點同步通知，由 {@code HomeRootFence.initialize()} 註冊。</li>
 * </ul>
 * 沒有註冊時（例如只有 M1 的 JUnit）一律等同「沒有投影、不攔截、不正規化、不阻擋」：{@link Projections#NONE}、{@link Doors#NONE}、{@link Membranes#NONE}、{@link Roots#NONE}。
 */
public final class ChamberCollapsePort {
    private static volatile Projections projections = Projections.NONE;
    private static volatile Doors doors = Doors.NONE;
    private static volatile Membranes membranes = Membranes.NONE;
    private static volatile Roots roots = Roots.NONE;

    private ChamberCollapsePort() {}

    private static volatile HomeDoors homeDoors = HomeDoors.NONE;

    /** 目前註冊的回程 HOME 門互動；沒有註冊時是 {@link HomeDoors#NONE}。 */
    public static HomeDoors homeDoors() { return homeDoors; }

    /** 註冊回程 HOME 門互動（取代之前註冊的）。 */
    public static void installHomeDoors(HomeDoors replacement) { homeDoors = Objects.requireNonNull(replacement, "homeDoors"); }

    /**
     * 回程走廊 HOME 門的互動（M7 spec §4.2，T5-4）：{@code QuantumBulkheadBlock.onUse} 在候選門與一般艙門之前、server thread 上呼叫。回傳 empty 表示這一格不是 HOME 門，
     * 交回既有流程；有值時互動已由回程 owner 處理（消耗），呼叫端直接回傳。實作不得丟出例外。
     */
    @FunctionalInterface
    public interface HomeDoors {
        /** 不攔截：一律交回既有流程。 */
        HomeDoors NONE = (world, pos, player) -> java.util.Optional.empty();

        java.util.Optional<net.minecraft.util.ActionResult> use(ServerWorld world, BlockPos pos, net.minecraft.server.network.ServerPlayerEntity player);
    }

    /** 目前註冊的原艙停用防線；沒有註冊時是 {@link Roots#NONE}。 */
    public static Roots roots() { return roots; }

    /** 註冊原艙停用防線（取代之前註冊的）。 */
    public static void installRoots(Roots replacement) { roots = Objects.requireNonNull(replacement, "roots"); }

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

        /** 只核已確認的 B 與其實例；原艙已撤銷時，既有旅程仍需完成 B 的門 checkpoint。 */
        default java.util.Optional<UUID> entryChamber(ServerWorld world, ChamberControllerBlockEntity controller) {
            return java.util.Optional.empty();
        }

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
     * 門的 25 格有任何一格帶著量子膜、而且 presence 不是 {@code COLLAPSE}／{@code PASSAGE}／{@code BUSY} 時，在 server thread 上呼叫（F6-0 補上 chunk 的條件，F5-2 quality Nit；M7 加上 BUSY）。實作只排入下一個 tick 的正規化（不在刷新的當下寫門：刷新可能就在 chunk 載入的
     * load-sync 裡），套用時再確認沒有 M5 塌縮狀態的 session 擁有這扇門（有 COLLAPSING、SEALED、PASSAGE_READY 時由該 session 的門邏輯負責），presence 也沒有變成 BUSY（同艙另一端的回程擁有門）。實作不得丟出例外。
     */
    @FunctionalInterface
    public interface Membranes {
        /** 不正規化（沒有塌縮流程）。 */
        Membranes NONE = (world, frame, chamber) -> { };

        /** {@code world} 裡 {@code frame} 這座原艙（registry 的 {@code chamber}）的門帶著量子膜，而且沒有 M5 塌縮狀態的 session。 */
        void normalizeOwnerless(ServerWorld world, ChamberFrame frame, UUID chamber);
    }

    /** 原艙在 registry 持久提交點發生的根事件（M7 spec §9.1）；普通斷電不是根事件。 */
    public enum RootEvent { DISABLED, INVALID_STRUCTURE, DESTROYED, IDENTITY_MISMATCH }

    /**
     * 回程旅程對原艙的停用防線（M7 spec §9.1、§6）：{@link ChamberRegistry} 在停用、啟用與移除的持久提交點同步呼叫，由 {@code collapse} 的回程 owner 註冊。
     * {@code registry} 不屬於任何已啟動的 server 時，實作一律放行（例如只有 M1 的 JUnit）。只在 server thread 呼叫，實作不得丟出例外。
     */
    public interface Roots {
        /** 沒有回程 owner：不阻擋、不需要停止任何旅程。 */
        Roots NONE = new Roots() {
            @Override public boolean permitsEnable(ChamberRegistry registry, UUID chamber) { return true; }
            @Override public boolean onRootEvent(ChamberRegistry registry, ChamberRecord record, RootEvent event) { return true; }
            @Override public boolean permitsRemoval(ChamberRegistry registry, UUID chamber) { return true; }
        };

        /** 這座原艙是否可以重新啟用：已選定的旅程停止原因尚未 checked 持久時回 false（pending）。 */
        boolean permitsEnable(ChamberRegistry registry, UUID chamber);

        /** registry 已提交 {@code event}；回 true 只表示所有需要停止的旅程都已 checked latch，或沒有這種旅程。 */
        boolean onRootEvent(ChamberRegistry registry, ChamberRecord record, RootEvent event);

        /** 一般拆除是否允許：同艙另一端的回程從暫占起到 checked 終局都回 false。 */
        boolean permitsRemoval(ChamberRegistry registry, UUID chamber);
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
