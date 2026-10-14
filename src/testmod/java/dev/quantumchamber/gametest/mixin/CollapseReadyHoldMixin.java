package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.universe.UniverseId;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F5-1）：PASSAGE_READY 的提交暫緩。{@code CollapseService.ready}（準備完成之後，§10.2 的前置條件與提交）的開頭問 {@link M5TestHooks#readyHeld}，裝著時這次
 * 不提交，PREPARE 照常（原艙門每個 tick 依條件判斷），等同 production 可以到達的「PASSAGE_READY 的前置條件還沒成立」，只是時間拉長。只給「準備完成之後仍在 PREPARE」的
 * 原艙門 test（F4b-3 的 T-MEM、T-FAIL），以及要控制通道起算時點的 test（F5-1 的 {@code m5_universe_passage_crossed_pending}）用；hook 是 {@code M5TestHooks.Slot}，只由 {@code M5GameTestSupport.holdReady} 安裝，沒有裝時不做任何事。
 * F6-2d-2 B1：M5 collapse probe 的 {@code M5CollapseDoorChains} 也以 {@code run.install(M5TestHooks.READY_HOLD, …)} 安裝；暫緩的那一刻（{@code readyHeld} 回 true、取消之前）另呼叫
 * {@link M5TestHooks#reachedCrashWindow} 的 {@code READY_HELD}（detail 是 {@link M5TestHooks.ReadyHeld}；沒有裝 crash 點時不做任何事，不往 production 丟例外）。
 * {@code CollapseService} 是 public，所以直接指定 class（不是 {@code targets}），{@code require=1}：方法改名或簽名改變時開服即失敗；package-private 的 {@code Track}、
 * {@code Prepare} 參數以 {@link Coerce} 收成 {@code Object}。
 */
@Mixin(value=CollapseService.class,remap=false)
abstract class CollapseReadyHoldMixin {
    @Inject(method="ready(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/collapse/CollapseService$Track;Ldev/quantumchamber/collapse/CollapseService$Prepare;Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/universe/UniverseId;)V",
            at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void quantumchamberTest$holdReady(SessionRecoveryRecord view,@Coerce Object track,@Coerce Object progress,ServerWorld world,UniverseId id,
            CallbackInfo callback) {
        if(M5TestHooks.readyHeld(view.sessionUuid())) {
            M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.READY_HELD,new M5TestHooks.ReadyHeld(view));
            callback.cancel();
        }
    }
}
