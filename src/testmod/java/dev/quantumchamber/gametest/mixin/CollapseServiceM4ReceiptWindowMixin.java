package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-2e-1）：跨 JVM probe 的 crash 窗口 {@code M4_RECEIPT_STEP}（W10）。在 {@code CollapseService.step} 的開頭（塌縮流程這個 tick 開始處理一個 session 之前），把那一筆
 * flushed 的 record 交給 {@link M5TestHooks#reachedCrashWindow}：每個 tick、每個 session、任何狀態都呼叫，是不是窗口（M4 時期的 Nether／End 收據：MEASURED、來源 role 不是 OVERWORLD）
 * 由 crash 點的條件判斷。probe 在同一個 tick 寫完那一批收據（塌縮流程那個 tick 已處理過全部 session）、把 journal 換成 schema 3 之後才裝上 crash 點，所以 halt 在塌縮流程第一次處理它們之前
 * （M5 從沒有處理過這批收據）。只讀，不改參數、不取消、不丟出。hook 是 {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝；沒有裝時不做任何事。
 * <p>與 {@code CollapseServiceCrashWindowMixin}（{@code DORMANT_STEP}、{@code SEALED_STEP}）在同一個注入點，另寫一個 mixin，不改既有那一個（它的兩個窗口與這裡的條件無關）。同一個方法的開頭另有
 * {@code CollapseSuspendMixin}（可以取消這一次的推進）；m5Collapse run 不暫停任何 session，三者不互相影響。完整描述子加 {@code require=1}：方法改名或簽名改變時，套用 mixin 就失敗。
 */
@Mixin(value=CollapseService.class,remap=false)
abstract class CollapseServiceM4ReceiptWindowMixin {
    @Inject(method="step(Ldev/quantumchamber/persistence/SessionRecoveryRecord;)V",at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$m4ReceiptWindow(SessionRecoveryRecord record,CallbackInfo callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.M4_RECEIPT_STEP,record);
    }
}
