package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F6-0，G-NLIVE 的根因）：原生存檔 checkpoint 的 entity storage 就緒保留。{@code ChunkSaveCheckpoint.loaded}（一個 chunk「已載入為 WorldChunk，而且 entity storage
 * 已載入」；{@code ready} 與 {@code save} 的前置條件共用它）的開頭呼叫 {@link M5TestHooks#entityStorageHeld}，保留中的 chunk 回 false，等同 entity storage 的非同步載入還沒完成。
 * 只改 checkpoint 自己的判斷：原版的 entity 管理、{@code ServerWorld.isChunkLoaded} 與走廊的 {@code leaseReady} 都不受影響。hook 是 {@code M5TestHooks.Slot}，只由
 * {@code M5GameTestSupport.holdEntityStorage} 安裝；沒有裝時不做任何事。{@code ChunkSaveCheckpoint} 是 public，所以直接指定 class；testmod 的 mixin 設定沒有 defaultRequire，
 * 所以明寫 {@code require=1}：方法改名或簽名改變時開服即失敗。
 */
@Mixin(value=ChunkSaveCheckpoint.class,remap=false)
abstract class ChunkSaveCheckpointEntityHoldMixin {
    @Inject(method="loaded(Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/server/world/ServerEntityManager;Lnet/minecraft/util/math/ChunkPos;)Z",
            at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private static void quantumchamberTest$hold(ServerWorld world,ServerEntityManager<Entity> manager,ChunkPos pos,CallbackInfoReturnable<Boolean> callback) {
        if(M5TestHooks.entityStorageHeld(world,pos)) callback.setReturnValue(false);
    }
}
