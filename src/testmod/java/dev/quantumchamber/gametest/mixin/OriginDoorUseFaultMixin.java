package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F5-0）：原艙門攔截的失敗注入，兩個位置：
 * <ul>
 * <li>{@code OriginDoors.endByParticipant}（PREPARE 期間參與者開關原艙門的處置）的開頭呼叫 {@link M5TestHooks#beforeParticipantDoorUse}（F4b-3 quality M-2：取代 F4b-3 fix round 1
 *     以反射把 final 的 {@code Track.backoff} 換成 null 的作法）：攔截在處置途中失敗（production 拒絕、不改門、依處置與原因去重記 WARN）。</li>
 * <li>{@code OriginDoors.sessionAt}（找出以這座艙為原艙的 session）的開頭呼叫 {@link M5TestHooks#beforeDoorSessionLookup}（F4b-3 spec m-1）：攔截在還沒有 session 可以歸屬時失敗
 *     （production 拒絕、不改門、記 tick 層級的 WARN）。</li>
 * </ul>
 * 符合條件（指定的 session 或艙，而且武裝中）時丟 {@link IllegalStateException}。{@code OriginDoors} 與 {@code CollapseService.Track} 是 package-private，所以以
 * {@code targets} 指定 class，Track 的參數以 {@link Coerce} 收成 {@code Object}。hook 是 {@code M5TestHooks.Slot}，只由 {@code M5GameTestSupport.installDoorUseFault} 安裝；
 * 沒有裝時不做任何事。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 * <p><b>{@code targets} 的界線</b>（F5-1 Step 0，F5-0 quality m-2）：{@code @Mixin(targets=…)} 指定的 class 找不到時（例如改名或搬移），Mixin 只記 WARN、不套用這個 mixin
 * （sponge-mixin 的 {@code strict} 預設關閉），{@code require=1} 只保護 class 找得到之後的方法選擇器，啟動不會因此失敗。所以用這種寫法的注入，test 必須有證明注入真的發生的
 * witness（{@code hits()}／{@code lookupHits()} 一類；T-FAIL 的 {@code closeFailuresWereInjected} 與 {@code refusedWhenTheSessionLookupFails}），不能只靠「沒有注入時行為不同」。
 */
@Mixin(targets="dev.quantumchamber.collapse.OriginDoors",remap=false)
abstract class OriginDoorUseFaultMixin {
    @Inject(method="endByParticipant(Ldev/quantumchamber/persistence/SessionRecoveryRecord;Ldev/quantumchamber/collapse/CollapseService$Track;Lnet/minecraft/entity/player/PlayerEntity;)Ldev/quantumchamber/chamber/ChamberCollapsePort$DoorUse;",
            at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$participantFault(SessionRecoveryRecord view,@Coerce Object track,PlayerEntity player,
            CallbackInfoReturnable<ChamberCollapsePort.DoorUse> callback) {
        M5TestHooks.beforeParticipantDoorUse(view);
    }

    @Inject(method="sessionAt(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;)Ldev/quantumchamber/persistence/SessionRecoveryRecord;",
            at=@At("HEAD"),remap=false,require=1)
    private void quantumchamberTest$lookupFault(ServerWorld world,ChamberFrame frame,CallbackInfoReturnable<SessionRecoveryRecord> callback) {
        M5TestHooks.beforeDoorSessionLookup(frame);
    }
}
