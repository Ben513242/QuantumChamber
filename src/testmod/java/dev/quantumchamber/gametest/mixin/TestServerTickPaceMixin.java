package dev.quantumchamber.gametest.mixin;

import java.util.Locale;
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
 * <p>看得到節流確實生效（F6-3b fix round 1，quality Minor 3）：{@code require=1} 擋不住「原版之後不再於 tick 之後呼叫 {@code runTasksTillTickEnd}」這類靜默失效，所以
 * 第一次<b>實際等待</b>時（TAIL 執行而且還沒到期限）才記一則 INFO「GameTest tick 節流：…第一次實際等待」，停服時（{@code TestServer.exit} 的開頭）再記一則 INFO
 * 「GameTest tick 節流 結束：ticks=… waited=… waitedMs=… elapsedMs=… tps=… minTickMs=…」：TAIL 對前面有 {@code tick} 的 {@code runTasksTillTickEnd} 執行的次數、
 * 其中實際等待的次數與時間，以及由第一個這種 tick 的開始到最後一次 TAIL 結束的 wall-clock 估出的 t/s。gate 可以據此斷言節流生效（例如 waited 大於 0、tps 不超過上限）。
 */
@Mixin(TestServer.class)
abstract class TestServerTickPaceMixin {
    @Unique private static final String quantumchamberTest$PROPERTY = "quantumchamber.gametest.minTickMillis";
    @Unique private static final long quantumchamberTest$MIN_TICK_NANOS = quantumchamberTest$minTickNanos();
    /** 這個 tick 由 {@code tick} 開始的時間；-1 表示目前的 {@code runTasksTillTickEnd} 前面沒有 {@code tick}（開服、停服），不節流。 */
    @Unique private long quantumchamberTest$tickStartNanos = -1L;
    /** TAIL 對前面有 {@code tick} 的 {@code runTasksTillTickEnd} 執行的次數（節流適用的 tick 數）。 */
    @Unique private long quantumchamberTest$ticks;
    /** 其中到 TAIL 時還沒到期限、實際等待的次數與等待的總時間。 */
    @Unique private long quantumchamberTest$waitedTicks;
    @Unique private long quantumchamberTest$waitedNanos;
    /** 第一個節流適用的 tick 的開始時間，與最後一次 TAIL 結束的時間；-1 表示還沒有。 */
    @Unique private long quantumchamberTest$firstTickStartNanos = -1L;
    @Unique private long quantumchamberTest$lastTickEndNanos = -1L;

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
    }

    @Inject(method="runTasksTillTickEnd()V",at=@At("TAIL"),require=1)
    private void quantumchamberTest$paceTick(CallbackInfo callback) {
        if (quantumchamberTest$tickStartNanos < 0) return;
        long start = quantumchamberTest$tickStartNanos;
        long deadline = start + quantumchamberTest$MIN_TICK_NANOS;
        quantumchamberTest$tickStartNanos = -1L;
        if (quantumchamberTest$firstTickStartNanos < 0) quantumchamberTest$firstTickStartNanos = start;
        quantumchamberTest$ticks++;
        long before = Util.getMeasuringTimeNano();
        boolean waits = before - deadline < 0;
        if (waits && quantumchamberTest$waitedTicks == 0) {
            Logger logger = LoggerFactory.getLogger("quantumchamber-testmod");
            logger.info("GameTest tick 節流：每個 tick 至少 {} ms（{}）；第 {} 個 tick 第一次實際等待", quantumchamberTest$MIN_TICK_NANOS / 1_000_000L,
                    quantumchamberTest$PROPERTY, quantumchamberTest$ticks);
        }
        ((MinecraftServer) (Object) this).runTasks(() -> Util.getMeasuringTimeNano() - deadline >= 0);
        long after = Util.getMeasuringTimeNano();
        if (waits) {
            quantumchamberTest$waitedTicks++;
            quantumchamberTest$waitedNanos += after - before;
        }
        quantumchamberTest$lastTickEndNanos = after;
    }

    @Inject(method="exit()V",at=@At("HEAD"),require=1)
    private void quantumchamberTest$reportPacing(CallbackInfo callback) {
        if (quantumchamberTest$MIN_TICK_NANOS <= 0) return;
        long elapsedNanos = quantumchamberTest$ticks > 0 ? quantumchamberTest$lastTickEndNanos - quantumchamberTest$firstTickStartNanos : 0L;
        String tps = elapsedNanos > 0 ? String.format(Locale.ROOT, "%.1f", quantumchamberTest$ticks * 1e9 / elapsedNanos) : "n/a";
        Logger logger = LoggerFactory.getLogger("quantumchamber-testmod");
        logger.info("GameTest tick 節流 結束：ticks={} waited={} waitedMs={} elapsedMs={} tps={} minTickMs={}", quantumchamberTest$ticks, quantumchamberTest$waitedTicks,
                quantumchamberTest$waitedNanos / 1_000_000L, elapsedNanos / 1_000_000L, tps, quantumchamberTest$MIN_TICK_NANOS / 1_000_000L);
    }
}
