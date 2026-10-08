package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM probe 的 crash 窗口 W1（spec §12）。在 {@code SessionRecoveryState.commitRestore(MinecraftServer, …)} 的回傳點，把這一次的
 * 變更前、變更後的 record 與結果（{@code M5TestHooks.CollapsingCommit}）交給 {@link M5TestHooks#reachedCrashWindow}，窗口是 {@code COLLAPSING_COMMITTED}：
 * live 的 MEASURED→COLLAPSING 已 checked 落盤、返還與清理還沒有開始（{@code CollapseService} 在 session 的 operation guard 之內提交）。每次回傳都呼叫
 * （READY、SEALED→COLLAPSING 等其他 restore 類的邊，與提交失敗的結果也是），是否是窗口由 crash 點的條件判斷。只讀，不改結果、不丟出。hook 是
 * {@code M5TestHooks.Slot}，只由 {@code M5CollapseRecoveryProbe} 安裝；沒有裝時不做任何事。{@code SessionRecoveryState} 是 public，以 class 指定 target，
 * 完整描述子加 {@code require=1}（只掛 public 的 server 版本，不掛 package-private 的 {@code Runnable} 版本）：方法改名或簽名改變時，套用 mixin 就失敗。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class SessionRecoveryStateCrashWindowMixin {
    @Inject(method="commitRestore(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/persistence/SessionRecoveryRecord;)Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$collapsingWindow(MinecraftServer server,SessionRecoveryRecord expected,SessionRecoveryRecord next,
            CallbackInfoReturnable<SessionRecoveryState.CommitResult> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.COLLAPSING_COMMITTED,new M5TestHooks.CollapsingCommit(expected,next,callback.getReturnValue()));
    }
}
