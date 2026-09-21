package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M4CandidateRecoveryProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(MinecraftServer.class)
abstract class M4RecoveryServerSaveProbeMixin {
    @Inject(method="save",at=@At("RETURN"))
    private void quantumchamberTest$returned(boolean suppress,boolean flush,boolean force,CallbackInfoReturnable<Boolean> callback) {
        M4CandidateRecoveryProbe.afterServerSave((MinecraftServer)(Object)this,callback.getReturnValue());
    }
    @Inject(method="onChunkSaveFailure",at=@At("HEAD"))
    private void quantumchamberTest$reported(Throwable error,StorageKey key,ChunkPos pos,CallbackInfo callback) {
        M4CandidateRecoveryProbe.nativeFailureReported(key,pos);
    }
}
