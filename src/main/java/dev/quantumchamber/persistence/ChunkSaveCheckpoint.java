package dev.quantumchamber.persistence;

import dev.quantumchamber.mixin.ServerEntityManagerRecoveryStorageAccessor;
import dev.quantumchamber.mixin.ServerWorldRecoveryStorageAccessor;
import dev.quantumchamber.mixin.StorageIoWorkerRegionStorageAccessor;
import java.util.*;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.StorageIoWorker;

/**
 * 只看相關 chunks 所在 region 的原生存檔 checkpoint（MEASURED 清理與投影共用）；外層 save 的返回值不是成功收據。
 *
 * <p>前置條件：在 server thread 呼叫；每個相關 chunk 都已載入為 FULL 的 WorldChunk，而且它的 entity storage 已載入
 * （可先以 {@link #ready} 檢查；未就緒時 {@link #save} 丟 IllegalStateException，不做任何存檔）。
 * 每次呼叫都會做一次全伺服器的 {@code server.save(false,true,true)} flush；重試與退避由呼叫端負責。
 *
 * <p>流程：重新標髒相關 chunks → {@code server.save(false,true,true)} → 原生 IO barrier → 只比對相關 chunks 所在 region 檔的失敗。
 * 每個世界比對五份記錄：chunk region worker 與 entity storage 的寫入／flush，它們底下 region 檔快取的逐出（close／fsync），
 * 以及 server 的 onChunkSaveFailure（含序列化失敗）。
 * 其他 region、其他世界的失敗不阻擋（前提是在原版 flush 之前已完成）；沒有 chunk 位置的失敗（region flush、region 檔快取逐出）
 * 視為該 storage 全部 region 失敗。
 *
 * <p>界線：原版 flush 時仍 pending 的寫入失敗會由原版 completeAll().join() 讓 server.save 直接丟例外，這是原版語意的界線，不繞過。
 * POI storage 沒有 observer 與 barrier，它的非同步寫入失敗只經 server 的 onChunkSaveFailure 記錄，屬 best-effort：
 * 在 verify 之後才完成的 POI 失敗不會被看見。
 * 任何失敗都重新標髒並丟出例外（fail-closed）。
 */
public final class ChunkSaveCheckpoint {
    private ChunkSaveCheckpoint() { }
    /** 與 {@link #save} 的前置條件相同：每個 chunk 已載入為 WorldChunk，而且它的 entity storage 已載入；空集合視為未就緒。 */
    public static boolean ready(ServerWorld world,Set<ChunkPos> chunks) {
        var manager=((ServerWorldRecoveryStorageAccessor)world).quantumchamber$entityManager();
        return !chunks.isEmpty() && chunks.stream().allMatch(pos -> loaded(world,manager,pos));
    }
    public static void save(MinecraftServer server,Map<ServerWorld,Set<ChunkPos>> relevant) {
        if(!server.isOnThread()) throw new IllegalStateException("原生存檔 checkpoint 必須在 server thread");
        if(relevant.isEmpty()) throw new IllegalArgumentException("原生存檔 checkpoint 至少需要一個世界");
        // 先驗證全部世界並取得各失敗來源的基準，之後才標髒與存檔。
        var contexts=new ArrayList<Context>();
        relevant.forEach((world,chunks) -> contexts.add(context(server,world,chunks)));
        boolean accepted=false;
        try {
            contexts.forEach(Context::redirty);
            if(!server.save(false,true,true)) throw new IllegalStateException("原生 world save 未執行");
            // 再取得明確 IO barrier，然後等 error observer 自身完成，防止 callback 比外層 RETURN 晚。
            contexts.forEach(Context::barrier);
            contexts.forEach(Context::verify);
            accepted=true;
        } finally {
            // 原生 serialize 會先清 needsSaving，entity 空快取也會先寫入；兩者均需恢復重試資格。
            if(!accepted) contexts.forEach(Context::redirty);
        }
    }
    private static boolean loaded(ServerWorld world,ServerEntityManager<Entity> manager,ChunkPos pos) {
        return world.getChunkManager().getWorldChunk(pos.x,pos.z)!=null && manager.isLoaded(pos.toLong());
    }
    private static Context context(MinecraftServer server,ServerWorld world,Set<ChunkPos> chunks) {
        if(server.getWorld(world.getRegistryKey())!=world) throw new IllegalArgumentException("checkpoint 世界不屬於此 server");
        if(chunks.isEmpty()) throw new IllegalArgumentException("每個世界至少需要一個相關 chunk");
        var manager=((ServerWorldRecoveryStorageAccessor)world).quantumchamber$entityManager();
        var raw=((ServerEntityManagerRecoveryStorageAccessor)manager).quantumchamber$dataAccess();
        if(!(raw instanceof EntityChunkDataAccess entities) || !(raw instanceof EntityRecoverySaveAccess access)
                || !(world.getChunkManager().getChunkIoWorker() instanceof StorageIoWorker blocks))
            throw new IllegalStateException("不支援的原生 recovery storage");
        var loaded=new ArrayList<WorldChunk>(); var positions=new HashSet<Long>();
        for(var pos : chunks) {
            if(!loaded(world,manager,pos)) throw new IllegalStateException("相關 chunk／entity storage 尚未完整載入");
            loaded.add(world.getChunkManager().getWorldChunk(pos.x,pos.z)); positions.add(pos.toLong());
        }
        var key=world.getRegistryKey().getValue().toString();
        var reported=((NativeSaveFailureAccess)server).quantumchamber$saveFailures(world.getRegistryKey());
        return new Context(key,List.copyOf(loaded),Set.copyOf(positions),blocks,entities,access,
                StorageReceipt.of("chunk",((NativeStorageSaveAccess)blocks).quantumchamber$saveFailures(),blocks),
                StorageReceipt.of("entities",access.quantumchamber$saveFailures(),access.quantumchamber$storageWorker()),
                reported,reported.revision());
    }
    /**
     * 一份原生 storage 的失敗基準：寫入／flush 的記錄，加上它底下 RegionBasedStorage 的 region 檔快取逐出記錄。
     * 逐出時的 close（fsync）失敗會丟給正在讀寫的另一個 chunk，被逐出的 region 也不再由 sync() 走訪，所以另外比對整份 storage。
     */
    record StorageReceipt(String source,NativeSaveFailures writes,NativeSaveFailures regionCache,long writesBefore,long regionCacheBefore) {
        static StorageReceipt of(String source,NativeSaveFailures writes,StorageIoWorker worker) {
            // RegionBasedStorage 是 final class，介面由 mixin 加上，編譯期須經 Object 轉型。
            Object regionStorage=((StorageIoWorkerRegionStorageAccessor)worker).quantumchamber$regionStorage();
            var regionCache=((NativeStorageSaveAccess)regionStorage).quantumchamber$saveFailures();
            return new StorageReceipt(source,writes,regionCache,writes.revision(),regionCache.revision());
        }
        void verify(String world,Collection<Long> positions) {
            writes.verify(writesBefore,positions,"source="+source+" world="+world);
            regionCache.verify(regionCacheBefore,positions,"source=regionCache:"+source+" world="+world);
        }
    }
    private record Context(String world,List<WorldChunk> chunks,Set<Long> positions,StorageIoWorker blocks,EntityChunkDataAccess entities,
            EntityRecoverySaveAccess access,StorageReceipt blockReceipt,StorageReceipt entityReceipt,NativeSaveFailures reported,long reportedBefore) {
        void redirty() { chunks.forEach(chunk -> chunk.setNeedsSaving(true)); access.quantumchamber$markEntitiesForResave(positions); }
        void barrier() { blocks.completeAll(true).join(); entities.awaitAll(true); }
        void verify() {
            blockReceipt.verify(world,positions); entityReceipt.verify(world,positions);
            reported.verify(reportedBefore,positions,"source=server world="+world);
        }
    }
}
