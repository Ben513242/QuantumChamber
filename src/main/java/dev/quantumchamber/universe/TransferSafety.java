package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.ALLOW;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_NOT_FULL;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_UNSAFE;

import java.util.Objects;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

/**
 * 跨世界傳送的落點安全政策（M5 plan 決策 4、spec §10.4）。{@link UniverseTransferService} 在三個地方用同一個政策：移動之前的前置檢查（目的地）、移動之後的驗證
 * （目的地），以及回滾資格（來源）。政策只回答「這個 bbox 放在這個世界可不可以」，exact 身分、移動與回滾本身仍由 {@link UniverseTransferService} 負責。
 * <ul>
 * <li>{@link #M3_SAFE_LANDING}：M3 的安全落點（spec M3 §8；{@code UniverseTransferService.moveToUniverse(player, descriptor)} 與 {@code returnToSource} 使用），
 *     內容就是 F5-1 之前 {@code UniverseTransferService.targetSafety} 的程式，逐行搬來、順序不變：有限值 → chunk 範圍 → 外擴 2 格的 chunks 都 FULL →
 *     高度與世界邊界 → 無碰撞 → 無流體 → 腳底恰在方塊頂面而且每一欄都有完整的上表面。</li>
 * <li>passage 的政策是 collapse package 的 {@code PassageTransferPolicy}：只保留前四項（有限值、chunk 範圍、FULL、高度與邊界），不做碰撞、流體與地板的檢查。</li>
 * </ul>
 * 政策經 {@link Site} 讀世界：只在 server thread 呼叫；chunk 一律以非建立模式查詢，所以要先確認涵蓋的 chunks 都是 FULL（{@link #requireFull}），之後才讀方塊。
 */
public interface TransferSafety {
    /** 「bbox 外擴 2 格」的格數（M3 的 FULL 檢查；spec §10.4 的 passage 保留同一個範圍）。 */
    int FULL_MARGIN = 2;
    /** 外擴之後 x 或 z 方向涵蓋的 chunk 數超過這個差值時，直接判為不安全（M3：避免讀取過大的範圍）。 */
    int MAX_CHUNK_SPAN = 4;

    /**
     * M3 的安全落點（行為與 F5-1 之前的 {@code UniverseTransferService.targetSafety} 相同；M3 的 test 不改）。
     */
    TransferSafety M3_SAFE_LANDING = new TransferSafety() {
        @Override public UniverseTransferAuthority.Decision check(Site site, Box box) {
            if (!finite(box)) return REJECT_TARGET_UNSAFE;
            var full = requireFull(site, box);
            if (full != ALLOW) return full;
            if (!withinHeightAndBorder(site, box) || !site.spaceEmpty(box) || site.containsFluid(box)) return REJECT_TARGET_UNSAFE;
            int floorY = MathHelper.floor(box.minY - 1e-7);
            if (floorY < site.bottomY() || Math.abs(box.minY - (floorY + 1.0)) > 1e-6) return REJECT_TARGET_UNSAFE;
            for (int x = MathHelper.floor(box.minX + 1e-7); x <= MathHelper.floor(box.maxX - 1e-7); x++) {
                for (int z = MathHelper.floor(box.minZ + 1e-7); z <= MathHelper.floor(box.maxZ - 1e-7); z++) {
                    if (!site.floorSupports(x, floorY, z)) return REJECT_TARGET_UNSAFE;
                }
            }
            return ALLOW;
        }

        @Override public String requirement() { return "位置必須位於高度／邊界內，且 FULL、無碰撞／液體及完整地板支撐"; }

        @Override public String toString() { return "M3_SAFE_LANDING"; }
    };

    /**
     * 這個 bbox 放在 {@code site} 的世界可不可以：{@link UniverseTransferAuthority.Decision#ALLOW}、{@code REJECT_TARGET_NOT_FULL} 或 {@code REJECT_TARGET_UNSAFE}。
     * 可以丟出例外：前置檢查與回滾資格把例外當成 {@code REJECT_TARGET_UNSAFE}，移動之後的驗證把它當成驗證失敗（與 M3 相同）。
     */
    UniverseTransferAuthority.Decision check(Site site, Box box);

    /** 前置檢查或回滾資格被拒時，結果的 detail 寫的條件說明。 */
    String requirement();

    /**
     * 政策讀世界的唯讀介面（只在 server thread；一律不載入 chunk）。{@link UniverseTransferService} 以 {@code ServerWorld} 與移動的玩家實作；JUnit 以假的實作驗證
     * 政策的決策表。方塊與流體只能在涵蓋的 chunks 都已確認 FULL 之後讀。
     */
    interface Site {
        /** chunk 已是 FULL（非建立模式查詢）。 */
        boolean chunkFull(int chunkX, int chunkZ);

        int bottomY();

        int topY();

        double borderWest();

        double borderEast();

        double borderNorth();

        double borderSouth();

        /** bbox 內沒有會擋住這位玩家的碰撞（M3 的 {@code isSpaceEmpty(player, box)}）。 */
        boolean spaceEmpty(Box box);

        /** bbox 內有流體（M3 的 {@code containsFluid}）。 */
        boolean containsFluid(Box box);

        /** (x, y, z) 的方塊有完整的上表面（M3 的地板支撐）。 */
        boolean floorSupports(int x, int y, int z);
    }

    /** bbox 的六個邊界都是有限值。 */
    static boolean finite(Box box) {
        Objects.requireNonNull(box, "box");
        return Double.isFinite(box.minX) && Double.isFinite(box.minY) && Double.isFinite(box.minZ)
                && Double.isFinite(box.maxX) && Double.isFinite(box.maxY) && Double.isFinite(box.maxZ);
    }

    /**
     * bbox 水平外擴 {@value #FULL_MARGIN} 格涵蓋的 chunk 範圍（M3 的推導：{@code floor(min − 2) >> 4} 到 {@code floor(max + 2) >> 4}）。這是 FULL 檢查的唯一推導；
     * passage 的預載也由它算出（F5-1，F4b-2 spec S-M1：兩邊的 chunk 推導一致）。bbox 必須是有限值。
     */
    static ChunkRange fullChunkRange(Box box) {
        if (!finite(box)) throw new IllegalArgumentException("bbox 必須是有限值：" + box);
        return new ChunkRange(MathHelper.floor(box.minX - FULL_MARGIN) >> 4, MathHelper.floor(box.maxX + FULL_MARGIN) >> 4,
                MathHelper.floor(box.minZ - FULL_MARGIN) >> 4, MathHelper.floor(box.maxZ + FULL_MARGIN) >> 4);
    }

    /**
     * FULL 檢查（{@link #fullChunkRange}）：範圍超過 {@value #MAX_CHUNK_SPAN} 個 chunk 的差值時 {@code REJECT_TARGET_UNSAFE}；任何一個 chunk 不是 FULL 時
     * {@code REJECT_TARGET_NOT_FULL}；都是 FULL 時 {@code ALLOW}。bbox 必須是有限值。
     */
    static UniverseTransferAuthority.Decision requireFull(Site site, Box box) {
        var range = fullChunkRange(box);
        if (range.maxX() - range.minX() > MAX_CHUNK_SPAN || range.maxZ() - range.minZ() > MAX_CHUNK_SPAN) return REJECT_TARGET_UNSAFE;
        for (int x = range.minX(); x <= range.maxX(); x++) {
            for (int z = range.minZ(); z <= range.maxZ(); z++) {
                if (!site.chunkFull(x, z)) return REJECT_TARGET_NOT_FULL;
            }
        }
        return ALLOW;
    }

    /** bbox 在世界高度（{@code bottomY..topY}）與世界邊界之內（M3 的比較方式）。 */
    static boolean withinHeightAndBorder(Site site, Box box) {
        return !(box.minY < site.bottomY() || box.maxY > site.topY()
                || box.minX < site.borderWest() || box.maxX > site.borderEast()
                || box.minZ < site.borderNorth() || box.maxZ > site.borderSouth());
    }

    /** chunk 座標的閉區間（x、z 各一段）。 */
    record ChunkRange(int minX, int maxX, int minZ, int maxZ) {
        public ChunkRange {
            if (minX > maxX || minZ > maxZ) throw new IllegalArgumentException("chunk 範圍的 min 必須不大於 max");
        }

        /** (x, z) 在範圍內。 */
        public boolean contains(int x, int z) { return x >= minX && x <= maxX && z >= minZ && z <= maxZ; }
    }
}
