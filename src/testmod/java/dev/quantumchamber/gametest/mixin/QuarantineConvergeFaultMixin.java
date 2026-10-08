package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.Optional;
import java.util.UUID;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-0，F4b-3 spec S-M1）：隔離 session 的收斂失敗注入。{@code SessionRecoveryState.convergeQuarantined}（recovery 每個 tick 對隔離中的 session 的收斂，排在塌縮流程
 * 之前）的開頭呼叫 {@link M5TestHooks#beforeConvergeQuarantined}：注入時丟 {@link IllegalStateException}，recovery 依原因去重記 WARN、維持隔離，下一個 tick 再試，所以塌縮流程
 * 看得到隔離中的 PREPARE（production 中收斂一直失敗時的路徑）。hook 是 {@code M5TestHooks.Slot}，只由 {@code M5GameTestSupport.installConvergeFault} 安裝；沒有裝時不做任何事。
 * {@code SessionRecoveryState} 是 public，所以直接指定 class；testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class QuarantineConvergeFaultMixin {
    @Inject(method="convergeQuarantined(Ljava/util/UUID;)Ljava/util/Optional;",at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$fault(UUID session,CallbackInfoReturnable<Optional<SessionRecoveryRecord>> callback) {
        M5TestHooks.beforeConvergeQuarantined(session);
    }
}
