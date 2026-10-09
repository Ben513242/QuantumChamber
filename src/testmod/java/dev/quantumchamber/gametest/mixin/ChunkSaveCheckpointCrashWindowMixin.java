package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod（M5 F6-1）：跨 JVM probe 的 crash 窗口 W4b（spec §12）。在 {@code ChunkSaveCheckpoint.save} 的開頭（守門與存檔之前）把這一次要存的世界與 chunks
 * （{@code M5TestHooks.ChunkCheckpoint}）交給 {@link M5TestHooks#reachedCrashWindow}，窗口是 {@code DESTINATION_CHECKPOINT}：投影方塊已寫完並驗證、還沒有存檔，
 * projection ledger 仍是 PENDING。每一次 checkpoint 都呼叫（任何世界、任何呼叫端），是否是目的世界的 checkpoint 由 crash 點的條件判斷。hook 是
 * {@code M5TestHooks.Slot}，只由 M5 collapse probe 安裝（{@code M5CollapseRun.armCrash} 經 {@code M5CollapseRun.install}；F6-2c Step 0 更正，F6-2a 拆檔起不在 {@code M5CollapseRecoveryProbe}）；沒有裝時不做任何事。既有的 {@code ChunkSaveCheckpointProbeMixin} 也掛在這裡（GameTest 的探針與
 * 失敗注入）；這裡只讀，不丟出。{@code ChunkSaveCheckpoint} 是 public，以 class 指定 target，{@code require=1}：方法改名或簽名改變時，套用 mixin 就失敗。
 */
@Mixin(value=ChunkSaveCheckpoint.class,remap=false)
abstract class ChunkSaveCheckpointCrashWindowMixin {
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ljava/util/Map;)V",at=@At("HEAD"),remap=false,require=1)
    private static void quantumchamberTest$checkpointWindow(MinecraftServer server,Map<ServerWorld,Set<ChunkPos>> relevant,CallbackInfo callback) {
        M5TestHooks.reachedCrashWindow(M5TestHooks.CrashWindow.DESTINATION_CHECKPOINT,new M5TestHooks.ChunkCheckpoint(relevant));
    }
}
