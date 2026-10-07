package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F5-2，F4a M-3）：進入 SEALED 的 Controller 探針。{@code CollapseService.sealCommitted}（進入 SEALED 之後的共同步驟：門、訊息、刷新 Controller、釋放 ticket）的開頭與
 * 每一個 RETURN 呼叫 {@link M5TestHooks#aroundSealCommitted}，探針記下那一刻來源 Controller 的比較器與 {@code ChamberState}。RETURN 的值鎖住「進入 SEALED 之後刷新 Controller」：
 * 拿掉那次刷新時，RETURN 那一刻仍是進入 SEALED 之前的值（Controller 每 20 tick 的排程刷新在 world 的 tick 裡，不會插在塌縮流程的 tick 中途）。hook 是 {@code M5TestHooks.Slot}，只由
 * {@code M5GameTestSupport.installSealProbe} 安裝，沒有裝時不做任何事；test 以 {@code hits()} 當 witness（README「新增 M5 GameTest」的規則）。
 * {@code CollapseService} 是 public，所以直接指定 class（不是 {@code targets}），{@code require=1}：方法改名或簽名改變時開服即失敗；package-private 的 {@code Track} 參數以
 * {@link Coerce} 收成 {@code Object}。
 */
@Mixin(value=CollapseService.class,remap=false)
abstract class CollapseSealProbeMixin {
    @Inject(method="sealCommitted(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/collapse/CollapseService$Track;Ldev/quantumchamber/collapse/SealedReason;Ljava/lang/RuntimeException;Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;)V",
            at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$beforeSeal(SessionRecoveryRecord view,@Coerce Object track,SealedReason reason,RuntimeException cause,
            SessionRecoveryState.CommitResult result,CallbackInfo callback) {
        M5TestHooks.aroundSealCommitted(view,false);
    }

    @Inject(method="sealCommitted(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/collapse/CollapseService$Track;Ldev/quantumchamber/collapse/SealedReason;Ljava/lang/RuntimeException;Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;)V",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$afterSeal(SessionRecoveryRecord view,@Coerce Object track,SealedReason reason,RuntimeException cause,
            SessionRecoveryState.CommitResult result,CallbackInfo callback) {
        M5TestHooks.aroundSealCommitted(view,true);
    }
}
