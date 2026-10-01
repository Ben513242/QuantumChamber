package dev.quantumchamber.persistence;

import dev.quantumchamber.mixin.ServerEntityManagerRecoveryStorageAccessor;
import dev.quantumchamber.mixin.ServerWorldRecoveryStorageAccessor;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.StorageIoWorker;

/**
 * 只看相關 chunks 所在 region 的原生存檔 checkpoint（MEASURED 清理與投影共用）；外層 save 的返回值不是成功收據。
 * 流程：重新標髒相關 chunks → {@code server.save(false,true,true)} → 原生 IO barrier → 只比對相關 chunks 所在 region 檔的失敗。
 * 每個世界比對三個來源：server 的 onChunkSaveFailure（含序列化失敗）、chunk region worker、entity storage。
 * 其他 region、其他世界的失敗不阻擋（前提是在原版 flush 之前已完成）；沒有 chunk 位置的 region flush 失敗視為該 worker 全部 region 失敗。
 * 原版 flush 時仍 pending 的寫入失敗會由原版 completeAll().join() 讓 server.save 直接丟例外，這是原版語意的界線，不繞過。
 * 任何失敗都重新標髒並丟出例外（fail-closed），由呼叫端決定重試與退避。
 */
public final class ChunkSaveCheckpoint {
    private ChunkSaveCheckpoint() { }
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
            long packed=pos.toLong(); var chunk=world.getChunkManager().getWorldChunk(pos.x,pos.z);
            if(chunk==null || !manager.isLoaded(packed)) throw new IllegalStateException("相關 chunk／entity storage 尚未完整載入");
            loaded.add(chunk); positions.add(packed);
        }
        var reported=((NativeSaveFailureAccess)server).quantumchamber$saveFailures(world.getRegistryKey());
        var blockFailures=((NativeStorageSaveAccess)blocks).quantumchamber$saveFailures();
        var entityFailures=access.quantumchamber$saveFailures();
        return new Context(List.copyOf(loaded),Set.copyOf(positions),blocks,entities,access,reported,blockFailures,entityFailures,
                reported.revision(),blockFailures.revision(),entityFailures.revision());
    }
    private record Context(List<WorldChunk> chunks,Set<Long> positions,StorageIoWorker blocks,EntityChunkDataAccess entities,
            EntityRecoverySaveAccess access,NativeSaveFailures reported,NativeSaveFailures blockFailures,NativeSaveFailures entityFailures,
            long reportedBefore,long blockBefore,long entityBefore) {
        void redirty() { chunks.forEach(chunk -> chunk.setNeedsSaving(true)); access.quantumchamber$markEntitiesForResave(positions); }
        void barrier() { blocks.completeAll(true).join(); entities.awaitAll(true); }
        void verify() {
            blockFailures.verify(blockBefore,positions); entityFailures.verify(entityBefore,positions); reported.verify(reportedBefore,positions);
        }
    }
}
