package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.gametest.M5TestHooks;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F4b-2）：投影寫入失敗的注入。在 {@code ProjectionWriter.write} 每一個回傳點讀 {@link M5TestHooks#afterProjectionWrite}：注入時把那次寫完
 * （WRITTEN）的結果換成 WRITE_FAILED（帶受控的例外；方塊已經寫了，模擬寫入途中丟出例外），塌縮流程因此以它當 root cause 進 SEALED(PROJECTION_FAILED)。hook 是
 * {@code M5TestHooks.Slot}，由 GameTest 的 {@code M5GameTestSupport.installProjectionWriteFault}，或 M5 collapse probe 的 {@code M5CollapseRun.install}（F6-2a 起；
 * F6-2c Step 0 更正）安裝；沒有裝時不做任何事。注入的那一次，排在後面的 {@code ProjectionWriterCrashWindowMixin}（{@code PROJECTION_REPAIRED}）不會被呼叫（F6-2a 實測）。testmod 的 mixin 設定沒有 defaultRequire，
 * 所以明寫 {@code require=1}。
 */
@Mixin(value=ProjectionWriter.class,remap=false)
abstract class ProjectionWriteFaultMixin {
    @Inject(method="write(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;Ldev/quantumchamber/collapse/ProjectionWriter$Mode;)Ldev/quantumchamber/collapse/ProjectionWriter$Result;",
            at=@At("RETURN"),cancellable=true,remap=false,require=1)
    private static void quantumchamberTest$fault(ServerWorld world,ChamberFrame frame,ProjectionWriter.Mode mode,CallbackInfoReturnable<ProjectionWriter.Result> callback) {
        var injected=M5TestHooks.afterProjectionWrite(world,mode,callback.getReturnValue());
        if(injected!=null) callback.setReturnValue(injected);
    }
}
