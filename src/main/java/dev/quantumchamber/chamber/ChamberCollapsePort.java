package dev.quantumchamber.chamber;

import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * {@code chamber} 往 M5 塌縮流程（{@code collapse} package）的反轉介面（M5 F4b-3 Step 0，F4b-1 quality Q-m1）：{@code chamber} 不 import {@code collapse}，
 * 由 {@code collapse} 在 mod 初始化時註冊實作（比照 {@link ChamberSessions#install} 與 {@link ChamberProtectionService#registerAdditionalGuard}）。
 * <ul>
 * <li>{@link Projections}：動態宇宙的投影保護與載入辨識（M5 spec §9.3），由 {@code ProjectionProtectionIndex.registerEvents()} 註冊。</li>
 * </ul>
 * 沒有註冊時（例如只有 M1 的 JUnit）一律等同「沒有投影」：{@link Projections#NONE}。
 */
public final class ChamberCollapsePort {
    private static volatile Projections projections = Projections.NONE;

    private ChamberCollapsePort() {}

    /** 目前註冊的投影查詢；沒有註冊時是 {@link Projections#NONE}。 */
    public static Projections projections() { return projections; }

    /** 註冊投影查詢（取代之前註冊的）。 */
    public static void installProjections(Projections replacement) { projections = Objects.requireNonNull(replacement, "projections"); }

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
}
