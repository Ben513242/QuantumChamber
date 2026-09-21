package dev.quantumchamber.mixin;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ServerEntityManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ServerWorld.class)
public interface ServerWorldRecoveryStorageAccessor {
    @Accessor("entityManager") ServerEntityManager<Entity> quantumchamber$entityManager();
}
