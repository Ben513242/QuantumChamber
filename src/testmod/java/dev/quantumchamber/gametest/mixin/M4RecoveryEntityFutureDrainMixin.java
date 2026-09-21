package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M4CandidateRecoveryProbe;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.ChunkPosKeyedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.concurrent.CompletableFuture;

@Mixin(ChunkPosKeyedStorage.class)
abstract class M4RecoveryEntityFutureDrainMixin {
    @Inject(method="set",at=@At("RETURN"))
    private void quantumchamberTest$drain(ChunkPos pos,NbtCompound nbt,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        M4CandidateRecoveryProbe.drainFailedNativeWrite(callback.getReturnValue());
    }
}
