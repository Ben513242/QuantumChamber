package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberFrame;
import net.minecraft.server.world.ServerWorld;

/**
 * 僅 testmod（M5 F6-2d-2 Step 0，H7 quality Minor 2）：讓跨 JVM 的塌縮 probe（{@code M5CollapseRetryChains}）以 production 的 {@link CollapseService#neighborhoodLoaded}
 * 判斷原艙附近是否已載入（§7.1、§7.3、§11.2 觸發的「來源艙已載入」），不在 testmod 複製一份判斷（production 改了外擴範圍時，複本會讓拿掉修正的 chain 也 PASS）。
 * 那個方法是 package-private，所以放在同一個 package（split package，比照 {@link CollapseLedgerTestAccess}）。只查詢、不加 ticket、不載入。release 與 sources JAR
 * 不含這個類別。
 */
public final class CollapseServiceTestAccess {
    private CollapseServiceTestAccess() {}

    /** {@link CollapseService#neighborhoodLoaded}：{@code frame} 的艙體向水平各方向外擴 3 格的 chunk 在 {@code world} 是否都是 FULL。 */
    public static boolean neighborhoodLoaded(ServerWorld world, ChamberFrame frame) {
        return CollapseService.neighborhoodLoaded(world, frame);
    }
}
