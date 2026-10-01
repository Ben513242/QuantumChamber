package dev.quantumchamber.mixin;
import dev.quantumchamber.persistence.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(StorageIoWorker.class)
abstract class StorageIoWorkerSaveFailureMixin implements NativeStorageSaveAccess {
    @Unique private final NativeSaveFailures quantumchamber$failures=new NativeSaveFailures();
    @Inject(method="setResult",at=@At("RETURN"))
    private void quantumchamber$write(ChunkPos pos,NbtCompound nbt,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        quantumchamber$failures.observe(callback.getReturnValue(),pos.toLong());
    }
    @Inject(method="completeAll",at=@At("RETURN"))
    private void quantumchamber$flush(boolean sync,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        // completeAll（含 region flush／sync）沒有 chunk 位置：失敗時保守地視為這個 worker 的所有 region 都失敗。
        quantumchamber$failures.observeFlush(callback.getReturnValue());
    }
    @Override public NativeSaveFailures quantumchamber$saveFailures() { return quantumchamber$failures; }
}
