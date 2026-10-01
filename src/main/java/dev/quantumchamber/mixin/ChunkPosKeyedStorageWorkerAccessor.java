package dev.quantumchamber.mixin;
import net.minecraft.world.storage.ChunkPosKeyedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** entity storage（ChunkPosKeyedStorage）底下的原生 IO worker。 */
@Mixin(ChunkPosKeyedStorage.class)
public interface ChunkPosKeyedStorageWorkerAccessor {
    @Accessor("worker") StorageIoWorker quantumchamber$worker();
}
