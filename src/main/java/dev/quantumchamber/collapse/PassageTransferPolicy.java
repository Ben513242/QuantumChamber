package dev.quantumchamber.collapse;

import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.ALLOW;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_UNSAFE;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.universe.TransferSafety;
import dev.quantumchamber.universe.UniverseTransferAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;

/**
 * passage 的傳送政策（spec §10.4、plan 決策 4；F5-1）：{@link TransferSafety} 的 passage 實作，移動之前的前置檢查、移動之後的驗證與回滾資格三處都用它
 * （{@code UniverseTransferService.moveToUniverse(player, descriptor, PassageTransferPolicy.INSTANCE)}）。
 * <ul>
 * <li><b>保留</b>（與 {@link TransferSafety#M3_SAFE_LANDING} 相同的程式）：bbox 是有限值；bbox 水平外擴 {@value TransferSafety#FULL_MARGIN} 格涵蓋的 chunks 都是 FULL
 *     （{@link TransferSafety#requireFull}）；在世界高度與世界邊界之內（{@link TransferSafety#withinHeightAndBorder}）。exact 身分與傳送後逐欄位核對位置、速度、yaw、
 *     pitch 與 bbox 由 {@code UniverseTransferService} 照做。</li>
 * <li><b>一律不採用</b>：M3 的碰撞、流體與地板支撐檢查。不讀任何方塊或流體（{@link TransferSafety.Site#spaceEmpty}、{@link TransferSafety.Site#containsFluid}、
 *     {@link TransferSafety.Site#floorSupports} 都不呼叫），所以玩家在門口跳躍、目的地是水、岩漿或石頭時，照樣傳送、驗證與回滾。不搜尋安全位置，也不調整 Y。</li>
 * </ul>
 * <p><b>預載與 FULL 檢查的 chunk 推導一致</b>（F4b-2 spec S-M1；spec §9.1 的 errata 交給 F7）：§9.1 原本寫「艙體 bbox 外擴 2 格」，但傳送時的 FULL 檢查是玩家 bbox
 * 外擴 2 格（{@link TransferSafety#fullChunkRange}），跨越門板中線時玩家的 bbox 會超出艙體 bbox，預載可能少 1 格 chunk。所以預載改由同一個推導算出：
 * {@link #preloadChunks} ＝ {@code fullChunkRange(}{@link #reach}{@code )}，{@link #reach} 是艙體 bbox 水平外擴 {@value #REACH} 格。{@code fullChunkRange} 在 min 與 max 上都是
 * 單調的，所以 bbox 在 {@link #reach} 之內的玩家，FULL 檢查涵蓋的 chunks 一定都在預載範圍內。跨越時腳在門口（local x∈[1,6)，bbox 在艙體範圍之內），上一個 tick 腳還在中線
 * 內側（local z ≥ 0.5）：這個 tick 腳的 local z 大於 −0.7（一個 tick 往外少於 1.2 格）時 bbox 都在 {@link #reach} 之內。更快的移動仍可能讓 FULL 檢查看到沒有預載、也沒有
 * 其他 ticket 的 chunk：那次傳送在移動之前就被拒（{@code REJECTED_BEFORE_MOVE}），玩家留在原世界，通道照常開著（{@code PassageService}）。
 */
final class PassageTransferPolicy implements TransferSafety {
    static final PassageTransferPolicy INSTANCE = new PassageTransferPolicy();
    /** 預載涵蓋的範圍：艙體 bbox 水平外擴的格數（再由 {@link TransferSafety#fullChunkRange} 外擴 {@value TransferSafety#FULL_MARGIN} 格）。 */
    static final int REACH = 1;

    private PassageTransferPolicy() { }

    @Override public UniverseTransferAuthority.Decision check(Site site, Box box) {
        Objects.requireNonNull(site, "site");
        if (!TransferSafety.finite(box)) return REJECT_TARGET_UNSAFE;
        var full = TransferSafety.requireFull(site, box);
        if (full != ALLOW) return full;
        return TransferSafety.withinHeightAndBorder(site, box) ? ALLOW : REJECT_TARGET_UNSAFE;
    }

    @Override public String requirement() {
        return "passage：位置必須是有限值、位於世界高度與邊界內，且 bbox 外擴 2 格涵蓋的 chunks 都是 FULL（不檢查碰撞、流體與地板）";
    }

    @Override public String toString() { return "PASSAGE"; }

    /** 艙體 bbox（整格的連續座標）水平外擴 {@value #REACH} 格；垂直方向不外擴（chunk 是整欄）。 */
    static Box reach(ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        return new Box(bounds.getMinX() - REACH, bounds.getMinY(), bounds.getMinZ() - REACH,
                bounds.getMaxX() + 1 + REACH, bounds.getMaxY() + 1, bounds.getMaxZ() + 1 + REACH);
    }

    /** §9.1 預載涵蓋的 chunk：{@code fullChunkRange(reach(frame))}，依 x、z 遞增的順序（與傳送時的 FULL 檢查同一個推導）。 */
    static List<ChunkPos> preloadChunks(ChamberFrame frame) {
        var range = TransferSafety.fullChunkRange(reach(frame));
        var chunks = new ArrayList<ChunkPos>();
        for (int x = range.minX(); x <= range.maxX(); x++)
            for (int z = range.minZ(); z <= range.maxZ(); z++) chunks.add(new ChunkPos(x, z));
        return List.copyOf(chunks);
    }
}
