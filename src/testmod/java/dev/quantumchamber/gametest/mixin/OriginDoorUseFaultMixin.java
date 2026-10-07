package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F5-0，F4b-3 quality M-2）：原艙門攔截的處置失敗注入。在 {@code OriginDoors.endByParticipant}（PREPARE 期間參與者開關原艙門的處置）的開頭呼叫
 * {@link M5TestHooks#beforeParticipantDoorUse}：符合條件（指定的 session，而且武裝中）時丟 {@link IllegalStateException}，攔截因此在處置途中失敗（production 拒絕、
 * 不改門、依原因去重記 WARN）。取代 F4b-3 fix round 1 以反射把 final 的 {@code Track.backoff} 換成 null 的作法。{@code OriginDoors} 與 {@code CollapseService.Track} 是
 * package-private，所以以 {@code targets} 指定 class，Track 的參數以 {@link Coerce} 收成 {@code Object}。hook 是 {@code M5TestHooks.Slot}，只由
 * {@code M5GameTestSupport.installDoorUseFault} 安裝；沒有裝時不做任何事。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(targets="dev.quantumchamber.collapse.OriginDoors",remap=false)
abstract class OriginDoorUseFaultMixin {
    @Inject(method="endByParticipant(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/collapse/CollapseService$Track;Lnet/minecraft/entity/player/PlayerEntity;)Ldev/quantumchamber/chamber/ChamberCollapsePort$DoorUse;",
            at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$participantFault(SessionRecoveryRecord view,@Coerce Object track,PlayerEntity player,
            CallbackInfoReturnable<ChamberCollapsePort.DoorUse> callback) {
        M5TestHooks.beforeParticipantDoorUse(view);
    }
}
