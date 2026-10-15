package dev.quantumchamber.gametest.mixin;

import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.TestServer;
import net.minecraft.util.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-3b，A3）：GameTest server 的 tick 節流。原版 {@code TestServer} 覆寫 {@code runTasksTillTickEnd()}，只做 {@code runTasks()}，不等到 tick 的結束時間就進下一個
 * tick，所以 tick 速率只受每個 tick 的工作量限制（Ubuntu CI 約 600～1240 t/s，本機約 1100 t/s）。以 tick 計的有界等待等的卻是 wall-clock 才會完成的非同步 chunk 載入或生成
 * （例如 PREPARE 的預載），tick 愈快，同一段 wall-clock 用掉的 tick 愈多，就愈容易逾時。
 * <p>只在 system property {@code quantumchamber.gametest.minTickMillis}（1～1000 的整數毫秒）存在時生效；{@code build.gradle} 只在 {@code gameTestM5} run 設為 10，也就是每個
 * tick 至少 10 ms（≤100 t/s）。真實伺服器是 20 TPS（每 tick 50 ms），節流之後反而更接近實際情況。default、legacy run 沒有這個 property，行為與原版相同。值不合法時開服即失敗。
 * <p>作法：{@code TestServer.tick} 的開頭記下時間；同一個 tick 的 {@code runTasksTillTickEnd} 在原版的 {@code runTasks()} 之後，以 {@code runTasks(() -> 已到期限)} 等到
 * 「tick 開始＋最短時間」。等待期間照常執行 main-thread 的工作（server task 與各 world 的 chunk manager 工作；沒有工作時原版每次 park 0.1 ms），與原版
 * {@code MinecraftServer.runTasksTillTickEnd} 的作法相同，不是單純 sleep。tick 本身已超過最短時間時不再等待。開服的 {@code prepareStartRegion} 與停服時呼叫的
 * {@code runTasksTillTickEnd} 前面沒有 {@code tick}，不節流。
 * <p>不用 {@code /tick rate}：TestServer 不等 tick 的結束時間，它對 TestServer 沒有節流效果，還會改變 autosave 的間隔。testmod 的 mixin 設定沒有 defaultRequire，所以明寫
 * {@code require=1}：target 改名或簽名改變時開服即失敗，而不是默默失去節流。
 */
@Mixin(TestServer.class)
abstract class TestServerTickPaceMixin {
    @Unique private static final String quantumchamberTest$PROPERTY = "quantumchamber.gametest.minTickMillis";
    @Unique private static final long quantumchamberTest$MIN_TICK_NANOS = quantumchamberTest$minTickNanos();
    /** 這個 tick 由 {@code tick} 開始的時間；-1 表示目前的 {@code runTasksTillTickEnd} 前面沒有 {@code tick}（開服、停服），不節流。 */
    @Unique private long quantumchamberTest$tickStartNanos = -1L;
    @Unique private boolean quantumchamberTest$announced;

    @Unique
    private static long quantumchamberTest$minTickNanos() {
        String value = System.getProperty(quantumchamberTest$PROPERTY);
        if (value == null) return 0L;
        long millis;
        try {
            millis = Long.parseLong(value.trim());
        } catch (NumberFormatException invalid) {
            throw new IllegalStateException(quantumchamberTest$PROPERTY + " 必須是 1～1000 的整數毫秒：" + value, invalid);
        }
        if (millis < 1 || millis > 1000) throw new IllegalStateException(quantumchamberTest$PROPERTY + " 必須是 1～1000 的整數毫秒：" + value);
        return millis * 1_000_000L;
    }

    @Inject(method="tick(Ljava/util/function/BooleanSupplier;)V",at=@At("HEAD"),require=1)
    private void quantumchamberTest$markTickStart(BooleanSupplier shouldKeepTicking,CallbackInfo callback) {
        if (quantumchamberTest$MIN_TICK_NANOS <= 0) return;
        quantumchamberTest$tickStartNanos = Util.getMeasuringTimeNano();
        if (!quantumchamberTest$announced) {
            quantumchamberTest$announced = true;
            Logger logger = LoggerFactory.getLogger("quantumchamber-testmod");
            logger.info("GameTest tick 節流：每個 tick 至少 {} ms（{}）", quantumchamberTest$MIN_TICK_NANOS / 1_000_000L, quantumchamberTest$PROPERTY);
        }
    }

    @Inject(method="runTasksTillTickEnd()V",at=@At("TAIL"),require=1)
    private void quantumchamberTest$paceTick(CallbackInfo callback) {
        if (quantumchamberTest$tickStartNanos < 0) return;
        long deadline = quantumchamberTest$tickStartNanos + quantumchamberTest$MIN_TICK_NANOS;
        quantumchamberTest$tickStartNanos = -1L;
        ((MinecraftServer) (Object) this).runTasks(() -> Util.getMeasuringTimeNano() - deadline >= 0);
    }
}
