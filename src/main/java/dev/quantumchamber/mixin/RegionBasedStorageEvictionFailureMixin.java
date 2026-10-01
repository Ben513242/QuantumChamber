package dev.quantumchamber.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.persistence.NativeSaveFailures;
import dev.quantumchamber.persistence.NativeStorageSaveAccess;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 原版 region 檔快取滿 256 個時，getRegionFile 會先 close 最久未用的 region 檔（fillLastSector＋force(true)）。
 * close 的例外會丟給正在讀寫的另一個 chunk，被逐出的檔之後也不再由 sync() 走訪；所以 close 失敗一律記為這份 storage
 * 全部失敗（fail-closed），再原樣重拋，原版行為不變。讀取與寫入觸發的逐出都經過這裡。
 */
@Mixin(RegionBasedStorage.class)
abstract class RegionBasedStorageEvictionFailureMixin implements NativeStorageSaveAccess {
    @Unique private final NativeSaveFailures quantumchamber$failures=new NativeSaveFailures();
    @WrapOperation(method="getRegionFile",at=@At(value="INVOKE",target="Lnet/minecraft/world/storage/RegionFile;close()V"),require=1)
    private void quantumchamber$evict(RegionFile evicted,Operation<Void> original) {
        try { original.call(evicted); }
        catch(Throwable failure) { quantumchamber$failures.failStorage(); throw failure; }
    }
    @Override public NativeSaveFailures quantumchamber$saveFailures() { return quantumchamber$failures; }
}
