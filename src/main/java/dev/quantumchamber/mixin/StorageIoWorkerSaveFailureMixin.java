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
        quantumchamber$failures.observe(callback.getReturnValue());
    }
    @Inject(method="completeAll",at=@At("RETURN"))
    private void quantumchamber$flush(boolean sync,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        quantumchamber$failures.observe(callback.getReturnValue());
    }
    @Override public NativeSaveFailures quantumchamber$saveFailures() { return quantumchamber$failures; }
}
