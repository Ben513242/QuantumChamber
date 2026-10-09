package dev.quantumchamber.mixin;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.world.ChunkHolder;
import net.minecraft.server.world.ServerChunkLoadingManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * chunk 卸載的 livelock 防護（M5 spec §4 H5、§20.10；根因見 M5 implementation note 的 F6-2b 一節）：丟棄「holder 已被取回」的過期卸載工作，不再重排。
 * <p><b>原版 1.21 的 livelock。</b>{@code unloadChunks} 把 refCount 為 0 的 holder 由 {@code currentChunkHolders} 移到 {@code chunksToUnload}，並呼叫
 * {@code tryUnloadChunk}；這條工作等 {@code savingFuture} 之後進入 {@code unloadTaskQueue}，執行時 holder 還不能存（refCount &gt; 0）就再呼叫 {@code tryUnloadChunk} 重排自己。
 * 工作還在佇列裡時，同一個 chunk 若被另一個 ticket 載回，{@code setLevel} 會由 {@code chunksToUnload} 取回同一個 holder，舊的工作卻留在佇列：之後每次執行都只會重排
 * （refCount &gt; 0），或在可存時 {@code chunksToUnload.remove(pos, holder)} 失敗、什麼都不做。停服（{@code MinecraftServer.shutdown} 的 {@code tick(() -> true)}）與 flush 存檔
 * （{@code save(true)}）都以 {@code unloadChunks(() -> true)} 無上限地排空佇列；持有這個 holder refCount 的 {@code ChunkLoader} 若在等 main thread 的工作（例如讀檔之後的
 * deserialize），main thread 就永遠困在排空迴圈裡：玩家看到關閉世界時停在 {@code Saving worlds}，遊戲進行中的 checkpoint flush 理論上也會凍結在同一處。
 * <p><b>修正與語意。</b>在 {@code tryUnloadChunk} 的 HEAD，{@code chunksToUnload.get(pos)} 不是同一個 holder 物件時取消這次呼叫。判斷一律用物件同一性：卸載工作屬於某一個
 * holder 物件，同一個位置在 {@code chunksToUnload} 裡可能已是另一個 holder（舊的 holder 卸載之後重新建立的），只看位置（{@code containsKey}）會留下不屬於它的工作。語意不變：
 * <ul>
 * <li>{@code chunksToUnload} 唯一的 {@code put} 在 {@code unloadChunks}，緊接著第一次呼叫 {@code tryUnloadChunk}，所以第一次呼叫一定通過。</li>
 * <li>被取消的只有「holder 已不在 {@code chunksToUnload}」的重排。原版裡這條工作永遠不會卸載或存檔它：重排的結果只有再重排，或 {@code remove(pos, holder)} 失敗、什麼都不做。</li>
 * <li>holder 被取回之後仍在 {@code currentChunkHolders}，level 再降到不可存取時回到 {@code unloadedChunks}；refCount 歸零之後，{@code unloadChunks} 重新 {@code put} 並建立新的
 *     工作，照常存檔、卸載。不會漏卸載、漏存檔，也不會留下孤兒。</li>
 * </ul>
 * 適用於所有世界（也包括原版自己的 ticket 造成的同一種情況），另外消除一般 tick 內殭屍在剩餘時間的空轉。{@code tryUnloadChunk} 與它的重排都在 server thread 上執行（卸載佇列只在
 * {@code unloadChunks} 裡排空），與 {@code chunksToUnload} 的寫入在同一條 thread。
 * <p><b>不記 log。</b>每一次丟棄都是正常的過期工作，記 log 會在大量 chunk 載回時刷版；回歸測試以 testmod 的被動 witness 計數（M5 collapse probe 的 {@code unload} chain）。
 * <p><b>相容性風險。</b>這是原版 chunk 系統的私有方法：重寫 chunk 卸載或 holder 管理的 mod（例如 C2ME、Moonrise）可能改寫或取代這個方法，造成注入失敗或與它們的修正重疊。
 * {@code require=1}：目標的名稱或簽章改變時開服即失敗（M5 的基線固定 1.21），不會靜默失效。
 */
@Mixin(ServerChunkLoadingManager.class)
abstract class ServerChunkLoadingManagerStaleUnloadMixin {
    @Shadow @Final private Long2ObjectLinkedOpenHashMap<ChunkHolder> chunksToUnload;
    @Inject(method="tryUnloadChunk(JLnet/minecraft/server/world/ChunkHolder;)V",at=@At("HEAD"),cancellable=true,require=1)
    private void quantumchamber$dropStaleUnload(long pos,ChunkHolder holder,CallbackInfo callback) {
        if(chunksToUnload.get(pos)!=holder) callback.cancel();
    }
}
