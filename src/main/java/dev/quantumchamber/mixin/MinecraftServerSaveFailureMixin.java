package dev.quantumchamber.mixin;
import dev.quantumchamber.persistence.NativeSaveFailureAccess;
import dev.quantumchamber.persistence.NativeSaveFailures;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.storage.StorageKey;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
abstract class MinecraftServerSaveFailureMixin implements NativeSaveFailureAccess {
    @Unique private final AtomicLong quantumchamber$failureCount=new AtomicLong();
    @Unique private final Map<RegistryKey<World>,NativeSaveFailures> quantumchamber$worldFailures=new ConcurrentHashMap<>();
    @Inject(method="onChunkSaveFailure",at=@At("HEAD"))
    private void quantumchamber$failed(Throwable error,StorageKey key,ChunkPos pos,CallbackInfo callback) {
        quantumchamber$failureCount.incrementAndGet();
        // 依世界（StorageKey.dimension）分開、記到 chunk 所在的 region；同一世界的 chunk、entities、poi storage 合併計入。
        var failures=quantumchamber$saveFailures(key.dimension());
        if(pos==null) failures.failStorage(); else failures.fail(pos.toLong());
    }
    @Override public long quantumchamber$saveFailureRevision() { return quantumchamber$failureCount.get(); }
    @Override public NativeSaveFailures quantumchamber$saveFailures(RegistryKey<World> dimension) {
        return quantumchamber$worldFailures.computeIfAbsent(Objects.requireNonNull(dimension),ignored -> new NativeSaveFailures());
    }
}
