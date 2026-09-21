package dev.quantumchamber.persistence;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Set;
import java.util.List;
import java.util.Objects;

/** 原生存檔 future 的完成收據。 */
public final class NativeSaveFailures {
    private final AtomicLong failures=new AtomicLong();
    private final Set<CompletableFuture<Void>> pending=ConcurrentHashMap.newKeySet();
    public long revision() { return failures.get(); }
    public void observe(CompletableFuture<?> future) {
        Objects.requireNonNull(future);
        var receipt=new CompletableFuture<Void>(); pending.add(receipt);
        future.whenComplete((ignored,error) -> {
            if(error!=null) failures.incrementAndGet();
            // 先發布 failure revision，再移除 pending；快於 barrier 的失敗也不能遺失。
            pending.remove(receipt); receipt.complete(null);
        });
    }
    /** 呼叫端先完成 native flush barrier；再等 observer 自身完成，不能只等原始 future。 */
    public void verify(long before) {
        for(var receipt : List.copyOf(pending)) receipt.join();
        if(failures.get()!=before) throw new IllegalStateException("原生 storage write／flush 已回報失敗，拒絕承認幾何清理");
    }
}
