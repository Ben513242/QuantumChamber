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
 * 僅 testmod（M5 F4b-2）：原生存檔 checkpoint 的探針與失敗注入。在 {@code ChunkSaveCheckpoint.save} 的開頭（守門與存檔之前）呼叫
 * {@link M5TestHooks#beforeChunkCheckpoint}：探針只看指定世界的 checkpoint，記下每一次，前幾次以 {@link IllegalStateException} 受控失敗（production 的
 * 投影 checkpoint 與走廊清理都把 checkpoint 的例外當成失敗）。hook 是 {@code M5TestHooks.Slot}，只由 {@code M5GameTestSupport.installChunkCheckpointProbe}
 * 安裝；沒有裝時不做任何事。既有的 checkpoint fault mixin 都不適用：{@code CheckpointPlatformFaultMixin} 改的是 {@code PlayerCheckpointStore} 的平台判斷，
 * {@code ChunkCheckpointScopeFaultMixin} 綁在 {@code M4CandidateDoorGameTests} 私有的 H2 探針（不是 Slot），{@code MeasuredCheckpointFaultMixin} 只看
 * MEASURED 的 checkpoint。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=ChunkSaveCheckpoint.class,remap=false)
abstract class ChunkSaveCheckpointProbeMixin {
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ljava/util/Map;)V",at=@At("HEAD"),remap=false,require=1)
    private static void quantumchamberTest$probe(MinecraftServer server,Map<ServerWorld,Set<ChunkPos>> relevant,CallbackInfo callback) {
        M5TestHooks.beforeChunkCheckpoint(server,relevant);
    }
}
