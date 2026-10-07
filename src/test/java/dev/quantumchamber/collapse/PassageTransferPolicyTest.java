package dev.quantumchamber.collapse;

import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.ALLOW;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_NOT_FULL;
import static dev.quantumchamber.universe.UniverseTransferAuthority.Decision.REJECT_TARGET_UNSAFE;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.quantumchamber.universe.TransferSafety;
import dev.quantumchamber.universe.TransferSafetyTest;
import dev.quantumchamber.universe.UniverseTransferAuthority;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * F5-1（spec §10.4、§15 pure JVM「PassageTransferPolicy 的決策表（含跳躍、水、岩漿時的驗證與回滾資格）」、plan 決策 4）：passage 的政策只檢查有限值、世界高度與邊界、
 * bbox 外擴 2 格的 chunks 為 FULL；碰撞、流體與地板一律不看。{@code UniverseTransferService} 把同一個政策傳給三處（移動之前的前置檢查＝目的地、移動之後的驗證＝
 * 目的地、回滾資格＝來源），三處都是同一個 {@code check(site, box)}，所以每一列的判定同時就是這三處的判定。對照組是 M3 的 {@link TransferSafety#M3_SAFE_LANDING}：同一個假世界，M3 拒絕跳躍、水、岩漿、石頭（所以 M3 的政策會讓這些情況在前置檢查被拒、在驗證失敗，
 * 或讓回滾判為 {@code SOURCE_UNSAFE} 而成為 {@code FAILED_RECOVERY_REQUIRED}），passage 照樣通過，而且完全不讀地形（假世界在 passage 的列禁止任何方塊查詢）。
 */
class PassageTransferPolicyTest {
    record Row(String name, Supplier<TransferSafetyTest.FakeSite> site, Box box, UniverseTransferAuthority.Decision passage,
            UniverseTransferAuthority.Decision m3) { }

    @Test void passagePolicyDecisionTable() {
        var standing = TransferSafetyTest.standing(0.5, 65, 0.5);
        var rows = List.of(
                new Row("石頭地板上站著", TransferSafetyTest.FakeSite::flat, standing, ALLOW, ALLOW),
                new Row("在門口跳躍（腳底不在方塊頂面）", TransferSafetyTest.FakeSite::flat, TransferSafetyTest.standing(0.5, 65.42, 0.5), ALLOW, REJECT_TARGET_UNSAFE),
                new Row("目的地是水", () -> TransferSafetyTest.FakeSite.flat().fluid(new BlockPos(0, 65, 0)), standing, ALLOW, REJECT_TARGET_UNSAFE),
                new Row("目的地是岩漿", () -> TransferSafetyTest.FakeSite.flat().fluid(new BlockPos(0, 66, 0)), standing, ALLOW, REJECT_TARGET_UNSAFE),
                new Row("目的地是石頭（身體與方塊相交）", () -> TransferSafetyTest.FakeSite.flat().solid(new BlockPos(0, 66, 0)), standing, ALLOW, REJECT_TARGET_UNSAFE),
                new Row("懸崖或空中（腳下沒有地板）", TransferSafetyTest.FakeSite::empty, standing, ALLOW, REJECT_TARGET_UNSAFE),
                new Row("非有限值", TransferSafetyTest.FakeSite::flat, new Box(0, 65, 0, 1, Double.NaN, 1), REJECT_TARGET_UNSAFE, REJECT_TARGET_UNSAFE),
                new Row("低於世界底部", () -> TransferSafetyTest.FakeSite.flat().height(70, 320), standing, REJECT_TARGET_UNSAFE, REJECT_TARGET_UNSAFE),
                new Row("高於世界頂部", () -> TransferSafetyTest.FakeSite.flat().height(-64, 66), standing, REJECT_TARGET_UNSAFE, REJECT_TARGET_UNSAFE),
                new Row("世界邊界外", () -> TransferSafetyTest.FakeSite.flat().border(-100, 100, 0.6, 100), standing, REJECT_TARGET_UNSAFE, REJECT_TARGET_UNSAFE),
                new Row("外擴 2 格的 chunk 不是 FULL", () -> TransferSafetyTest.FakeSite.flat().notFull((x, z) -> x == 0 && z == -1), standing,
                        REJECT_TARGET_NOT_FULL, REJECT_TARGET_NOT_FULL),
                new Row("chunk 範圍超過 4", TransferSafetyTest.FakeSite::flat, new Box(0, 65, 0, 1, 66, 80), REJECT_TARGET_UNSAFE, REJECT_TARGET_UNSAFE));
        var cases = new ArrayList<Executable>();
        for (var row : rows) {
            cases.add(() -> assertEquals(row.passage(), PassageTransferPolicy.INSTANCE.check(row.site().get().forbidTerrain(), row.box()), "passage：" + row.name()));
            cases.add(() -> assertEquals(row.m3(), TransferSafety.M3_SAFE_LANDING.check(row.site().get(), row.box()), "M3 對照：" + row.name()));
        }
        assertAll(cases);
    }
}
