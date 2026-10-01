package dev.quantumchamber.mixin;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原生 IO worker 底下的 region 檔 storage（取它的 region 檔快取逐出記錄）。 */
@Mixin(StorageIoWorker.class)
public interface StorageIoWorkerRegionStorageAccessor {
    @Accessor("storage") RegionBasedStorage quantumchamber$regionStorage();
}
