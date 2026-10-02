package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.gametest.M4CandidateDoorGameTests;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（plan 決策 7、spec §13.1）：collapse-suspended hook。被 hook 的 session，{@code CollapseService} 在它的每 tick 推進
 * （{@code step}）一開始就取消：完全不嘗試提交、不觸發 fallback、不終局。等同 production 可以到達的「live MEASURED、guard 未閒置時延後
 * 提交」，只是時間拉長；M4 的 GameTest 與 recovery probe 因此維持「MEASURED 會一直等待」的原斷言。
 * 依 testmod 的靜態集合（{@link M4CandidateDoorGameTests#suspendCollapse}，同一個 JVM 內不清除）或 system property
 * {@code quantumchamber.test.collapseSuspended=all}（{@code m4Recovery} run）判定。testmod 的 mixin 設定沒有 defaultRequire，所以明寫
 * {@code require=1}：target 改名時開服即失敗，而不是默默失去暫停。
 */
@Mixin(value=CollapseService.class,remap=false)
abstract class CollapseSuspendMixin {
    @Inject(method="step(Ldev/quantumchamber/persistence/SessionRecoveryRecord;)V",at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void quantumchamberTest$suspend(SessionRecoveryRecord record,CallbackInfo callback) {
        if(M4CandidateDoorGameTests.collapseSuspended(record.sessionUuid())) callback.cancel();
    }
}
