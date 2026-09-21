package dev.quantumchamber.mixin;
import dev.quantumchamber.persistence.NativeSaveFailureAccess;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerSaveFailureMixin implements NativeSaveFailureAccess {
    @Unique private final AtomicLong quantumchamber$saveFailures=new AtomicLong();
    @Inject(method="onChunkSaveFailure",at=@At("HEAD"))
    private void quantumchamber$failed(Throwable error,StorageKey key,ChunkPos pos,CallbackInfo callback) {
        quantumchamber$saveFailures.incrementAndGet();
    }
    @Override public long quantumchamber$saveFailureRevision() { return quantumchamber$saveFailures.get(); }
}
