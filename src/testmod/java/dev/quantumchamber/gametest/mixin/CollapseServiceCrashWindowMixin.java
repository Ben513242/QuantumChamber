package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-2d-1）：跨 JVM probe 的 crash 窗口 {@code DORMANT_STEP}（W8）、{@code SEALED_STEP}（F6-2-07 的 AUTHORITY_UNREADABLE）與 {@code M4_RECEIPT_STEP}（W10，F6-2e-2 起；
 * F6-2e-3 依 F6-2e-2 quality Minor 4 更正：開頭原本只寫前兩個）。在 {@code CollapseService.step} 的開頭（塌縮流程這個 tick 開始處理一個 session 之前），把那一筆 flushed 的 record 依序以
 * 這三個值交給 {@link M5TestHooks#reachedCrashWindow}：每個 tick、每個 session、任何狀態都呼叫，是不是窗口（這條 chain 的 DORMANT、SEALED(AUTHORITY_UNREADABLE)、M4 時期的 Nether／End
 * 收據）由 crash 點的條件判斷。W8 與 F6-2-07 的 probe 在觀察完「沒有觸發」「沒有重試」之後才裝上 crash 點，所以 halt 在塌縮流程下一次處理這個 session 之前（那一刻沒有進行中的寫入：DORMANT 與
 * SEALED 的 record 都已 flushed）；W10 在寫完 schema 3 的 journal 之後裝上，halt 在塌縮流程第一次處理那批收據之前（halt 的時點見 {@code M5CollapseUnsupportedChain} 的類別說明）。
 * 只讀，不改參數、不取消、不丟出。hook 是
 * {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝（{@code M5CollapseRun.armCrash} 經 {@code M5CollapseRun.install}）；沒有裝時不做任何事。
 * <p>F6-2e-2（F6-2e-1 quality Minor 4）：同一個注入點之後再以 {@code M4_RECEIPT_STEP}（W10：M4 時期的 Nether／End 收據，窗口表的條件選 MEASURED、來源 role 不是 OVERWORLD）呼叫。
 * F6-2e-1 另寫的 {@code CollapseServiceM4ReceiptWindowMixin}（同一個注入點、只派發這一個值）已拿掉：crash 點一次只裝一個，三個值依序派發與分開的兩個 mixin 結果相同。
 * <p>同一個方法的開頭另有 {@code CollapseSuspendMixin}（可以取消這一次的推進）；m5Collapse run 不暫停任何 session，兩者不互相影響。{@code CollapseService} 是 public，以
 * class 指定 target，完整描述子加 {@code require=1}：方法改名或簽名改變時，套用 mixin 就失敗。
 */
@Mixin(value=CollapseService.class,remap=false)
abstract class CollapseServiceCrashWindowMixin {
    @Inject(method="step(Ldev/quantumchamber/persistence/SessionRecoveryRecord;)V",at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$stepWindows(SessionRecoveryRecord record,CallbackInfo callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.DORMANT_STEP,record);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.SEALED_STEP,record);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.M4_RECEIPT_STEP,record);
    }
}
