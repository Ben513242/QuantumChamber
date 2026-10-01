package dev.quantumchamber.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 原生存檔 future 的完成收據，依 chunk（{@code ChunkPos.toLong}）記錄失敗。
 * 每份原生 storage（server 端則是每個世界）各有一份，同一 chunk 座標在不同份之間互不影響。
 * 沒有 chunk 位置的失敗（例如 completeAll 的 region flush）保守地視為這一份的所有 chunks 都失敗。
 */
public final class NativeSaveFailures {
    private final AtomicLong revision=new AtomicLong();
    /** chunk → 最近一次失敗時的 revision；只在失敗時新增。 */
    private final ConcurrentHashMap<Long,Long> chunkFailures=new ConcurrentHashMap<>();
    /** 最近一次沒有 chunk 位置的失敗時的 revision；0 表示從未發生。 */
    private final AtomicLong storageFailure=new AtomicLong();
    private final Set<CompletableFuture<Void>> pending=ConcurrentHashMap.newKeySet();

    /** 單調遞增的失敗計數；checkpoint 在標髒與存檔之前取得，作為 verify 的基準。 */
    public long revision() { return revision.get(); }
    /** 帶 chunk 位置的原生寫入 future（region write、entity chunk write）。 */
    public void observe(CompletableFuture<?> future,long chunk) { track(future,() -> fail(chunk)); }
    /** 沒有 chunk 位置的 flush future（completeAll）：失敗時視為所有 chunks 都失敗（fail-closed）。 */
    public void observeFlush(CompletableFuture<?> future) { track(future,this::failStorage); }
    /** 同步回報、帶 chunk 位置的失敗（例如原生 onChunkSaveFailure，含序列化失敗）。 */
    public void fail(long chunk) {
        long failed=revision.incrementAndGet();
        chunkFailures.merge(chunk,failed,Math::max);
    }
    /** 同步回報、沒有 chunk 位置的失敗：視為所有 chunks 都失敗（fail-closed）。 */
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
     * 之後只比對 before 之後的失敗：沒有 chunk 位置的失敗一律拒絕，帶位置的失敗只看 chunks。
     */
    public void verify(long before,Collection<Long> chunks) {
        for(var receipt : List.copyOf(pending)) receipt.join();
        if(storageFailure.get()>before)
            throw new IllegalStateException("原生 storage flush 已回報失敗（沒有 chunk 位置，視為全部 chunks），拒絕承認 checkpoint");
        for(long chunk : chunks) {
            var failed=chunkFailures.get(chunk);
            if(failed!=null && failed>before) throw new IllegalStateException("相關 chunk 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint");
        }
    }
}
