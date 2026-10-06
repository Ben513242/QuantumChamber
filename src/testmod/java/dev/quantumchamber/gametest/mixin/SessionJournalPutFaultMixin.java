package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F4b-3）：journal {@code put} 的決定性拒絕注入。在 {@code SessionRecoveryState.put(record, restoreCommit)}（所有 put 與 checked 提交的共同入口）的開頭
 * 呼叫 {@link M5TestHooks#beforeJournalPut}：符合條件（指定 session 的指定狀態）時丟 {@link IllegalStateException}，checked 提交因此在寫入之前就被拒（REJECTED，
 * journal 沒有改變）。用來造出 F4a 起「GameTest 造不出」的 PREPARE 中 SEALED 寫入被拒（F4b-2 Q-M4、F4a S2-mB）。hook 是 {@code M5TestHooks.Slot}，只由
 * {@code M5GameTestSupport.installJournalPutFault} 安裝；沒有裝時不做任何事。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class SessionJournalPutFaultMixin {
    @Inject(method="put(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Z)V",at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$fault(SessionRecoveryRecord record,boolean restoreCommit,CallbackInfo callback) {
        M5TestHooks.beforeJournalPut(record);
    }
}
