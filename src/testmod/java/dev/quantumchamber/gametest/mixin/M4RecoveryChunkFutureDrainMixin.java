package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M4CandidateRecoveryProbe;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.VersionedChunkStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

/** 只在 owned fault root 等真 IO 完成，固定重現 completeAll 已看不到失敗 pending entry 的窗口。 */
@Mixin(VersionedChunkStorage.class)
abstract class M4RecoveryChunkFutureDrainMixin {
    @Inject(method="setNbt",at=@At("RETURN"))
    private void quantumchamberTest$drain(ChunkPos pos,NbtCompound nbt,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        M4CandidateRecoveryProbe.drainFailedNativeWrite(callback.getReturnValue());
    }
}
