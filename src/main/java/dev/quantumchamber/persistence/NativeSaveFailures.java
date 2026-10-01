package dev.quantumchamber.persistence;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.math.ChunkPos;

/**
 * 原生存檔失敗的收據，以 region 檔為單位記錄（chunk 以 {@code ChunkPos.toLong} 傳入）。
 * region 檔的 header 由同一 region 的 32×32 chunks 共用，header 寫入失敗可能波及其中任一 chunk，
 * 所以同一 region 內任一 chunk 的失敗都視為整個 region 失敗。
 * 每份原生 storage（StorageIoWorker、EntityChunkDataAccess、RegionBasedStorage；server 端則是每個世界）各有一份，同一座標在不同份之間互不影響。
 * 沒有 chunk 位置的失敗（completeAll 的 region flush、region 檔快取逐出時的 close）保守地視為這一份的所有 region 都失敗。
 */
public final class NativeSaveFailures {
    private final AtomicLong revision=new AtomicLong();
    /** region（與 RegionBasedStorage 的 region 檔快取相同的 key）→ 最近一次失敗時的 revision；只在失敗時新增。 */
    private final ConcurrentHashMap<Long,Long> regionFailures=new ConcurrentHashMap<>();
    /** 最近一次沒有 chunk 位置的失敗時的 revision；0 表示從未發生。 */
    private final AtomicLong storageFailure=new AtomicLong();
    /** 尚未完成的 observer；記錄本身失敗的 receipt 會留在這裡，之後每一次 verify 都拒絕。 */
    private final Set<CompletableFuture<Void>> pending=ConcurrentHashMap.newKeySet();

    /** 單調遞增的失敗計數；checkpoint 在標髒與存檔之前取得，作為 verify 的基準。 */
    public long revision() { return revision.get(); }
    /** 帶 chunk 位置的原生寫入 future（region write、entity chunk write）。 */
    public void observe(CompletableFuture<?> future,long chunk) { track(future,() -> fail(chunk)); }
    /** 沒有 chunk 位置的 flush future（completeAll）：失敗時視為所有 region 都失敗（fail-closed）。 */
    public void observeFlush(CompletableFuture<?> future) { track(future,this::failStorage); }
    /** 同步回報、帶 chunk 位置的失敗（例如原生 onChunkSaveFailure，含序列化失敗）：記到該 chunk 所在的 region。 */
    public void fail(long chunk) {
        long failed=revision.incrementAndGet();
        regionFailures.merge(region(chunk),failed,Math::max);
    }
    /** 同步回報、沒有 chunk 位置的失敗（例如 region 檔快取逐出時的 close）：視為所有 region 都失敗（fail-closed）。 */
    public void failStorage() {
        long failed=revision.incrementAndGet();
        storageFailure.accumulateAndGet(failed,Math::max);
    }
    /** 套件內可見只為了測試記錄本身失敗的路徑；production 只經 observe／observeFlush。 */
    void track(CompletableFuture<?> future,Runnable failure) {
        Objects.requireNonNull(future);
        var receipt=new CompletableFuture<Void>(); pending.add(receipt);
        future.whenComplete((ignored,error) -> {
            // 先發布失敗，再完成並移除 receipt；快於 barrier 的失敗也不能遺失。
            try {
                if(error!=null) failure.run();
                receipt.complete(null); pending.remove(receipt);
            } catch(Throwable record) {
                // 記錄失敗就無法判斷遺失的失敗是否相關：receipt 以例外完成並留在 pending，之後每一次 verify 都拒絕。
                receipt.completeExceptionally(record);
            }
        });
    }
    /**
     * 呼叫端先完成 native flush barrier；再等所有已受理的 observer 自身完成，不能只等原始 future。
     * 之後只比對 before 之後的失敗：沒有 chunk 位置的失敗一律拒絕；帶位置的失敗只看 chunks 所在的 region。
     * source 是穩定的來源描述（例如 {@code source=chunk world=minecraft:overworld}），會放進例外訊息；
     * 訊息不含 revision 等每次都變的值，呼叫端可依 class＋message 去重。
     */
    public void verify(long before,Collection<Long> chunks,String source) {
        for(var receipt : List.copyOf(pending)) {
            try { receipt.join(); }
            catch(CompletionException | CancellationException record) {
                throw new IllegalStateException("原生存檔失敗的記錄本身失敗（無法判斷是否相關），拒絕承認 checkpoint："+source,record);
            }
        }
        if(storageFailure.get()>before)
            throw new IllegalStateException("原生 storage 已回報沒有 chunk 位置的失敗（flush 或 region 檔快取逐出），視為全部 region，拒絕承認 checkpoint："+source);
        // 多個相關 region 都失敗時，以座標最小者為準，讓訊息與集合走訪順序無關。
        chunks.stream().map(ChunkPos::new).filter(pos -> {
                    var failed=regionFailures.get(region(pos.toLong()));
                    return failed!=null && failed>before;
                })
                .map(pos -> new int[]{pos.getRegionX(),pos.getRegionZ()})
                .min(Comparator.<int[]>comparingInt(region -> region[0]).thenComparingInt(region -> region[1]))
                .ifPresent(region -> {
                    throw new IllegalStateException("相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint："+source
                            +" region=["+region[0]+", "+region[1]+"]");
                });
    }
    /** 與原版 RegionBasedStorage 選 region 檔的方式相同：ChunkPos.toLong(getRegionX(), getRegionZ())，即 chunk 座標 >> 5。 */
    private static long region(long chunk) {
        var pos=new ChunkPos(chunk);
        return ChunkPos.toLong(pos.getRegionX(),pos.getRegionZ());
    }
}
