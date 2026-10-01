package dev.quantumchamber.mixin;
import dev.quantumchamber.persistence.*;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkPosKeyedStorage;
import net.minecraft.world.storage.EntityChunkDataAccess;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityChunkDataAccess.class)
abstract class EntityChunkRecoverySaveMixin implements EntityRecoverySaveAccess {
    @Shadow @Final private LongSet emptyChunks;
    @Shadow @Final private ChunkPosKeyedStorage storage;
    @Unique private final NativeSaveFailures quantumchamber$failures=new NativeSaveFailures();
    @Inject(method="handleSaveFailure",at=@At("HEAD"))
    private void quantumchamber$observe(CompletableFuture<?> future,ChunkPos pos,CallbackInfo callback) {
        quantumchamber$failures.observe(future,pos.toLong());
    }
    @Override public NativeSaveFailures quantumchamber$saveFailures() { return quantumchamber$failures; }
    @Override public StorageIoWorker quantumchamber$storageWorker() { return ((ChunkPosKeyedStorageWorkerAccessor)storage).quantumchamber$worker(); }
    @Override public void quantumchamber$markEntitiesForResave(Set<Long> chunks) {
        for(long chunk : chunks) emptyChunks.remove(chunk);
    }
}
