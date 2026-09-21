package dev.quantumchamber.persistence;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.mixin.ServerWorldRecoveryStorageAccessor;
import dev.quantumchamber.mixin.ServerEntityManagerRecoveryStorageAccessor;
import dev.quantumchamber.superposition.SuperpositionWorld;
import java.util.*;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.StorageIoWorker;

/** 空 lease 前的原生持久化閘門；外層 save 返回值不是成功收據。 */
public final class MeasuredWorldSaveCheckpoint {
    private MeasuredWorldSaveCheckpoint() { }
    public static void save(MinecraftServer server,SessionRecoveryRecord record) {
        if(!server.isOnThread() || MeasuredRecoveryPhase.from(record)!=MeasuredRecoveryPhase.RELEASE_GEOMETRY)
            throw new IllegalStateException("世界 checkpoint 必須在全員返還後的 server thread");
        var source=Objects.requireNonNull(server.getWorld(RegistryKey.of(RegistryKeys.WORLD,record.origin().worldKey())));
        var corridor=Objects.requireNonNull(server.getWorld(SuperpositionWorld.KEY));
        var corridorChunks=new HashSet<Long>(); record.spaceLeases().forEach(lease -> addChunks(corridorChunks,lease.bounds()));
        var sourceChunks=new HashSet<Long>(); addChunks(sourceChunks,ChamberGeometry.bounds(new ChamberFrame(record.origin().controllerPos(),record.origin().facing())));
        var contexts=List.of(context(source,sourceChunks),context(corridor,corridorChunks));
        long before=((NativeSaveFailureAccess)server).quantumchamber$saveFailureRevision();
        boolean accepted=false;
        try {
            contexts.forEach(Context::redirty);
            if(!server.save(false,true,true)) throw new IllegalStateException("原生 world save 未執行");
            // 再取得明確 IO barrier，然後等 error observer 自身完成，防止 callback 比外層 RETURN 晚。
            contexts.forEach(Context::verify);
            if(((NativeSaveFailureAccess)server).quantumchamber$saveFailureRevision()!=before)
                throw new IllegalStateException("原生 chunk／entity 序列化或存檔失敗，保留 MEASURED leases");
            accepted=true;
        } finally {
            // 原生 serialize 會先清 needsSaving，entity 空快取也會先寫入；兩者均需恢復重試資格。
            if(!accepted) contexts.forEach(Context::redirty);
        }
    }
    private static Context context(ServerWorld world,Set<Long> positions) {
        var manager=((ServerWorldRecoveryStorageAccessor)world).quantumchamber$entityManager();
        var raw=((ServerEntityManagerRecoveryStorageAccessor)manager).quantumchamber$dataAccess();
        if(!(raw instanceof EntityChunkDataAccess entities) || !(raw instanceof EntityRecoverySaveAccess access)
                || !(world.getChunkManager().getChunkIoWorker() instanceof StorageIoWorker blocks))
            throw new IllegalStateException("不支援的原生 recovery storage");
        var chunks=new ArrayList<WorldChunk>();
        for(long packed : positions) {
            var pos=new ChunkPos(packed); var chunk=world.getChunkManager().getWorldChunk(pos.x,pos.z);
            if(chunk==null || !manager.isLoaded(packed)) throw new IllegalStateException("相關 chunk／entity storage 尚未完整載入");
            chunks.add(chunk);
        }
        var blockFailures=((NativeStorageSaveAccess)blocks).quantumchamber$saveFailures();
        var entityFailures=access.quantumchamber$saveFailures();
        return new Context(List.copyOf(chunks),Set.copyOf(positions),blocks,entities,access,blockFailures,entityFailures,
                blockFailures.revision(),entityFailures.revision());
    }
    private static void addChunks(Set<Long> chunks,BlockBox bounds) {
        for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++)
            for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) chunks.add(ChunkPos.toLong(x,z));
    }
    private record Context(List<WorldChunk> chunks,Set<Long> positions,StorageIoWorker blocks,EntityChunkDataAccess entities,
            EntityRecoverySaveAccess access,NativeSaveFailures blockFailures,NativeSaveFailures entityFailures,long blockBefore,long entityBefore) {
        void redirty() { chunks.forEach(chunk -> chunk.setNeedsSaving(true)); access.quantumchamber$markEntitiesForResave(positions); }
        void verify() {
            blocks.completeAll(true).join(); entities.awaitAll(true);
            blockFailures.verify(blockBefore); entityFailures.verify(entityBefore);
        }
    }
}
