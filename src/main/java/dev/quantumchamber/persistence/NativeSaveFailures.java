package dev.quantumchamber.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.util.math.ChunkPos;

/**
 * 原生存檔 future 的完成收據，以 region 檔為單位記錄失敗（chunk 以 {@code ChunkPos.toLong} 傳入）。
 * region 檔的 header 由同一 region 的 32×32 chunks 共用，header 寫入失敗可能波及其中任一 chunk，
 * 所以同一 region 內任一 chunk 的失敗都視為整個 region 失敗。
 * 每份原生 storage（server 端則是每個世界）各有一份，同一座標在不同份之間互不影響。
 * 沒有 chunk 位置的失敗（例如 completeAll 的 region flush）保守地視為這一份的所有 region 都失敗。
 */
public final class NativeSaveFailures {
    private final AtomicLong revision=new AtomicLong();
    /** region（與 RegionBasedStorage 的 region 檔快取相同的 key）→ 最近一次失敗時的 revision；只在失敗時新增。 */
    private final ConcurrentHashMap<Long,Long> regionFailures=new ConcurrentHashMap<>();
    /** 最近一次沒有 chunk 位置的失敗時的 revision；0 表示從未發生。 */
    private final AtomicLong storageFailure=new AtomicLong();
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
    /** 同步回報、沒有 chunk 位置的失敗：視為所有 region 都失敗（fail-closed）。 */
    public void failStorage() {
        long failed=revision.incrementAndGet();
        storageFailure.accumulateAndGet(failed,Math::max);
    }
    private void track(CompletableFuture<?> future,Runnable failure) {
        Objects.requireNonNull(future);
        var receipt=new CompletableFuture<Void>(); pending.add(receipt);
        future.whenComplete((ignored,error) -> {
            // 先發布失敗，再移除 pending；快於 barrier 的失敗也不能遺失。
            try { if(error!=null) failure.run(); }
            finally { pending.remove(receipt); receipt.complete(null); }
        });
    }
    /**
     * 呼叫端先完成 native flush barrier；再等所有已受理的 observer 自身完成，不能只等原始 future。
     * 之後只比對 before 之後的失敗：沒有 chunk 位置的失敗一律拒絕；帶位置的失敗只看 chunks 所在的 region。
     */
    public void verify(long before,Collection<Long> chunks) {
        for(var receipt : List.copyOf(pending)) receipt.join();
        if(storageFailure.get()>before)
            throw new IllegalStateException("原生 storage flush 已回報失敗（沒有 chunk 位置，視為全部 region），拒絕承認 checkpoint");
        for(long chunk : chunks) {
            var failed=regionFailures.get(region(chunk));
            if(failed!=null && failed>before) throw new IllegalStateException("相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint");
        }
    }
    /** 與原版 RegionBasedStorage 選 region 檔的方式相同：ChunkPos.toLong(getRegionX(), getRegionZ())，即 chunk 座標 >> 5。 */
    private static long region(long chunk) {
        var pos=new ChunkPos(chunk);
        return ChunkPos.toLong(pos.getRegionX(),pos.getRegionZ());
    }
}
