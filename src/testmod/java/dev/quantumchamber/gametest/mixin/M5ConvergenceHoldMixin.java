package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5UniverseGameTests;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestState;
import net.minecraft.test.TestRunContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-3b，B1／盤點 U6）：M5 run 的 test 由原版初始化之前，先等前一個 test 留下的未確認權威寫入收斂。原版 {@code GameTestState.tick} 的開頭，
 * test 還沒初始化（{@code initialized} 為 false：結構還沒放、test 方法還沒執行、test 的 tick 計數與 tickLimit 都還沒開始）時問
 * {@link M5UniverseGameTests#holdBeforeBegin}，回 true 就取消這個 tick 的處理（與原版等結構所在的 chunk 開始 tick 時的延後同一種，只是條件不同）。
 * 只在 {@code quantumchamber.gametest.m5Only=true}（M5 run）生效；default、legacy run 不呼叫。testmod 的 mixin 設定沒有 defaultRequire，所以明寫
 * {@code require=1}：target 改名或簽名改變時開服即失敗，而不是默默失去等待。
 */
@Mixin(GameTestState.class)
abstract class M5ConvergenceHoldMixin {
    @Unique private static final boolean quantumchamberTest$M5_ONLY = Boolean.getBoolean("quantumchamber.gametest.m5Only");
    @Shadow private boolean initialized;
    @Shadow @Final private ServerWorld world;
    /** 這個 test 已經為了等收斂而延後的 tick 數。 */
    @Unique private int quantumchamberTest$heldTicks;
    /** 已經放行：之後原版的 {@code initialDelay}（20 tick）期間 {@code initialized} 仍是 false，不再詢問，每個 test 最多等一次、結束只記一行。 */
    @Unique private boolean quantumchamberTest$released;

    @Inject(method="tick(Lnet/minecraft/test/TestRunContext;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void quantumchamberTest$holdBeforeBegin(TestRunContext context,CallbackInfo callback) {
        if (!quantumchamberTest$M5_ONLY || quantumchamberTest$released || initialized) return;
        var state = (GameTestState) (Object) this;
        if (state.isCompleted()) return;
        if (M5UniverseGameTests.holdBeforeBegin(world, state.getTemplatePath(), quantumchamberTest$heldTicks)) {
            quantumchamberTest$heldTicks++;
            callback.cancel();
        } else {
            quantumchamberTest$released = true;
        }
    }
}
