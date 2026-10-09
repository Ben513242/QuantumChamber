package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 H5，由 F6-2b 的 {@code vr-hold} 實驗轉成正式的 hook）：chunk 讀檔的保留。{@code ServerChunkLoadingManager.getUpdatedChunkNbt}（chunk 載入的讀檔；之後的
 * deserialize 要在 main thread 執行）回傳時呼叫 {@link M5TestHooks#heldChunkRead}，裝著的 {@code M5TestHooks.ChunkReadHold} 符合時把回傳值換成延後完成的 future，等同停服開始時
 * 仍在進行中的載入。hook 是 {@code M5TestHooks.Slot}，只由 M5 collapse probe 的 unload chain 安裝；沒有裝時原樣回傳。testmod 的 mixin 設定沒有 defaultRequire，所以明寫
 * {@code require=1}：方法改名或簽名改變時開服即失敗。
 */
@Mixin(ServerChunkLoadingManager.class)
abstract class ChunkReadHoldMixin {
    @Shadow @Final ServerWorld world;

    @Inject(method="getUpdatedChunkNbt(Lnet/minecraft/util/math/ChunkPos;)Ljava/util/concurrent/CompletableFuture;",at=@At("RETURN"),cancellable=true,require=1)
    private void quantumchamberTest$hold(ChunkPos pos,CallbackInfoReturnable<CompletableFuture<Optional<NbtCompound>>> callback) {
        var original=callback.getReturnValue();
        var held=M5TestHooks.heldChunkRead(world,pos,original);
        if(held!=original) callback.setReturnValue(held);
    }
}
