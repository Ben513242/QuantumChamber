package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.gametest.M5TestHooks;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM probe 的投影寫入 crash 窗口（spec §12）。只把那一刻的值交給 {@link M5TestHooks#reachedCrashWindow}，不改任何結果：
 * <ul>
 * <li>{@code writeCell}（private static）的 RETURN → {@code PROJECTION_CELL_WRITTEN}（W4：投影寫到一半；{@code M5TestHooks.ProjectionCell}，每寫一格都呼叫）；</li>
 * <li>{@code write} 的 RETURN → {@code PROJECTION_REPAIRED}（W6 的 EXISTING 再造訪：REPAIR 寫了格、checkpoint 之前；{@code M5TestHooks.ProjectionWrite}，每次呼叫都呼叫，
 *     兩種模式都是）。</li>
 * </ul>
 * 是否是窗口（Controller 那一格、REPAIR 寫了格）由 crash 點的條件判斷。hook 是 {@code M5TestHooks.Slot}，只由 {@code M5CollapseRecoveryProbe} 安裝；沒有裝時不做任何事。
 * {@code ProjectionWriter} 是 public，所以以 class 指定 target（class 不存在時無法編譯），兩個 injector 都以完整描述子加 {@code require=1}：方法改名、改簽名或改成非
 * static 時，套用 mixin 就失敗。既有的 {@code ProjectionWriteFaultMixin} 也掛在 {@code write} 的 RETURN（換掉結果的故障注入，只給 GameTest）；這裡只讀，不取消。
 */
@Mixin(value=ProjectionWriter.class,remap=false)
abstract class ProjectionWriterCrashWindowMixin {
    @Inject(method="writeCell(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;Ldev/quantumchamber/collapse/ProjectionWriter$Mode;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z",
            at=@At("RETURN"),remap=false,require=1)
    private static void quantumchamberTest$cellWindow(ServerWorld world,ChamberFrame frame,ProjectionWriter.Mode mode,BlockPos pos,BlockState target,
            CallbackInfoReturnable<Boolean> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.PROJECTION_CELL_WRITTEN,
                new M5TestHooks.ProjectionCell(world,frame,mode,pos.toImmutable(),callback.getReturnValueZ()));
    }

    @Inject(method="write(Lnet/minecraft/server/world/ServerWorld;Ldev/quantumchamber/chamber/ChamberFrame;Ldev/quantumchamber/collapse/ProjectionWriter$Mode;)Ldev/quantumchamber/collapse/ProjectionWriter$Result;",
            at=@At("RETURN"),remap=false,require=1)
    private static void quantumchamberTest$writeWindow(ServerWorld world,ChamberFrame frame,ProjectionWriter.Mode mode,
            CallbackInfoReturnable<ProjectionWriter.Result> callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.PROJECTION_REPAIRED,new M5TestHooks.ProjectionWrite(world,frame,mode,callback.getReturnValue()));
    }
}
