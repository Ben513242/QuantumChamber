package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.gametest.M5ChunkUnloadHooks;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import java.util.concurrent.CompletableFuture;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 僅 testmod（M5 H5）：卸載工作的被動 witness。不改變任何行為：兩個 {@code WrapOperation} 都恰好呼叫原本的方法一次、參數不變。
 * <ul>
 * <li>{@code tryUnloadChunk} 的 lambda（{@code method_60440}：holder 還不能存時再呼叫 {@code tryUnloadChunk} 重排自己）：這次重排的 holder 已不在 {@code chunksToUnload}
 *     （已被取回，以物件同一性判斷）時，交給 {@link M5ChunkUnloadHooks#staleReschedule} 呼叫原本的方法（恰好一次），它比對呼叫前後本體的進入次數，一次記下丟棄或照原版重排
 *     （F6-2c Step 0，H5 quality Minor 3）。</li>
 * <li>{@code tryUnloadChunk} 的本體（第一個指令 {@code holder.getSavingFuture()}）：通知 {@link M5ChunkUnloadHooks#unloadTaskBody}。production 的
 *     {@code ServerChunkLoadingManagerStaleUnloadMixin} 在 HEAD 取消時，本體不會執行。</li>
 * </ul>
 * 所以「重排的前後之間沒有進入本體」就是這條工作被丟棄，與 testmod、production 的 HEAD 注入順序無關。hook 是 {@code M5TestHooks.Slot}（{@code M5ChunkUnloadHooks.UNLOAD_TASK_WITNESS}），只由 M5 collapse probe 的 unload
 * chain 安裝；沒有裝時不讀 {@code chunksToUnload}，直接呼叫原本的方法。lambda 的名稱是 1.21 的 intermediary（{@code method_60440}，原版沒有 Yarn 名稱）；testmod 的 mixin
 * 設定沒有 defaultRequire，所以明寫 {@code require=1}：目標改變時開服即失敗。
 */
@Mixin(ServerChunkLoadingManager.class)
abstract class ChunkUnloadTaskWitnessMixin {
    @Shadow @Final ServerWorld world;
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> chunksToUnload;

    @WrapOperation(method="method_60440(Lnet/minecraft/server/world/ChunkHolder;J)V",at=@At(value="INVOKE",
            target="Lnet/minecraft/server/world/ServerChunkLoadingManager;tryUnloadChunk(JLnet/minecraft/server/world/ChunkHolder;)V"),require=1)
    private void quantumchamberTest$reschedule(ServerChunkLoadingManager manager,long pos,ChunkHolder holder,Operation<Void> original) {
        if(!M5ChunkUnloadHooks.watchingUnloadTasks(world)||chunksToUnload.get(pos)==holder) {
            original.call(manager,pos,holder);
            return;
        }
        M5ChunkUnloadHooks.staleReschedule(world,pos,() -> original.call(manager,pos,holder));
    }

    @WrapOperation(method="tryUnloadChunk(JLnet/minecraft/server/world/ChunkHolder;)V",at=@At(value="INVOKE",
            target="Lnet/minecraft/server/world/ChunkHolder;getSavingFuture()Ljava/util/concurrent/CompletableFuture;"),require=1)
    private CompletableFuture<?> quantumchamberTest$body(ChunkHolder holder,Operation<CompletableFuture<?>> original) {
        M5ChunkUnloadHooks.unloadTaskBody(world);
        return original.call(holder);
    }
}
