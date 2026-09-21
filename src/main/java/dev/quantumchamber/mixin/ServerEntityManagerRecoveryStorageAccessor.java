package dev.quantumchamber.mixin;
import net.minecraft.server.world.ServerEntityManager;
import net.minecraft.world.storage.ChunkDataAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerEntityManager.class)
public interface ServerEntityManagerRecoveryStorageAccessor {
    @Accessor("dataAccess") ChunkDataAccess<?> quantumchamber$dataAccess();
}
