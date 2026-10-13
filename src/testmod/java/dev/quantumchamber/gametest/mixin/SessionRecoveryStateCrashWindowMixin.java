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
 * {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝（{@code M5CollapseRun.armCrash} 經 {@code M5CollapseRun.install}；F6-2c Step 0 更正，F6-2a 拆檔起不在 {@code M5CollapseRecoveryProbe}）；沒有裝時不做任何事。{@code SessionRecoveryState} 是 public，以 class 指定 target，
 * 完整描述子加 {@code require=1}（只掛 public 的 server 版本，不掛 package-private 的 {@code Runnable} 版本）：方法改名或簽名改變時，套用 mixin 就失敗。
 * <p>F6-2c 加的注入點（同樣只讀、不改結果、不丟出，完整描述子加 {@code require=1}，只掛 public 的 server 版本）：
 * <ul>
 * <li>{@code commitRestore} 的同一個回傳點另以 {@code READY_COMMITTED} 呼叫（同一個 detail）：COLLAPSING→PASSAGE_READY 已 checked 落盤、門改為 passable 之前（F6-2-16）。</li>
 * <li>{@code commitRollForward(MinecraftServer, …)} 的回傳點，依序以 {@code CROSSED_COMMITTED}、{@code CROSSED_UNCONFIRMED}、{@code RECOVERY_SEALED}、{@code RECOVERY_REJECTED}
 *     呼叫（{@code M5TestHooks.RollForwardWrite}；每次回傳都是，返還與清 lease 進度、Crossed、進入 SEALED 的寫入與各種結果）：窗口表以 {@code CrashWindow} 為 key、一個值一列，
 *     同一個程式點上條件不同的四個窗口各有一個值，只有裝著的 crash 點的那個值會被處理。</li>
 * <li>{@code terminate(MinecraftServer, …)} 的回傳點以 {@code PASSAGE_TERMINATED} 呼叫（{@code M5TestHooks.Termination}；每次回傳都是，任何起點與結果）。</li>
 * </ul>
 * <p>F6-2d-1：{@code commitRollForward} 的回傳點另以 {@code MATERIALIZE_SEALED}（W9：COLLAPSING→SEALED(MATERIALIZE_FAILED)）呼叫，排在原本的四個之後。注入點的寫法一併統一
 * （F6-2c quality Minor 8，F6-2d Step 0 交給本體）：{@code commitRestore} 原本是兩個 {@code @Inject}（{@code COLLAPSING_COMMITTED} 與 {@code READY_COMMITTED} 各一個），改成與
 * {@code commitRollForward} 相同的一個 {@code @Inject}，依原本的順序以兩個值呼叫、共用一個 detail（record 都是 immutable）。每次 {@code reachedCrashWindow} 互不影響，行為與拆開時相同。
 */
@Mixin(value=SessionRecoveryState.class,remap=false)
abstract class SessionRecoveryStateCrashWindowMixin {
    @Inject(method="commitRestore(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/persistence/SessionRecoveryRecord;)Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$restoreWindows(MinecraftServer server,SessionRecoveryRecord expected,SessionRecoveryRecord next,
            CallbackInfoReturnable<SessionRecoveryState.CommitResult> callback) {
        var detail=new M5TestHooks.CollapsingCommit(expected,next,callback.getReturnValue());
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.COLLAPSING_COMMITTED,detail);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.READY_COMMITTED,detail);
    }

    @Inject(method="commitRollForward(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/persistence/SessionRecoveryRecord;)Ldev/quantumchamber/persistence/SessionRecoveryState$CommitResult;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$rollForwardWindows(MinecraftServer server,SessionRecoveryRecord expected,SessionRecoveryRecord next,
            CallbackInfoReturnable<SessionRecoveryState.CommitResult> callback) {
        var detail=new M5TestHooks.RollForwardWrite(expected,next,callback.getReturnValue());
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.CROSSED_COMMITTED,detail);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.CROSSED_UNCONFIRMED,detail);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.RECOVERY_SEALED,detail);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.RECOVERY_REJECTED,detail);
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.MATERIALIZE_SEALED,detail);
    }

    @Inject(method="terminate(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/persistence/SessionRecoveryState$TerminalOrigin;)Ldev/quantumchamber/persistence/SessionRecoveryState$TerminalResult;",
            at=@At("RETURN"),remap=false,require=1)
    private void quantumchamberTest$terminalWindow(MinecraftServer server,SessionRecoveryRecord expected,SessionRecoveryState.TerminalOrigin origin,
            CallbackInfoReturnable<SessionRecoveryState.TerminalResult> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.PASSAGE_TERMINATED,new M5TestHooks.Termination(expected,origin,callback.getReturnValue()));
    }
}
