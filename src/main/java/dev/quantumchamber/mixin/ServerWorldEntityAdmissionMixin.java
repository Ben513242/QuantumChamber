package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * backstop：spawnEntity／tryLoadEntity／spawnEntityAndPassengers／非玩家 onDimensionChanged 都經過 addEntity；
 * 量子走廊世界只接受玩家、掉落物、投射物與經驗球。玩家另走 addPlayer，不受影響。
 */
@Mixin(ServerWorld.class)
abstract class ServerWorldEntityAdmissionMixin {
    @Inject(method = "addEntity(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$admitManagedOnly(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (SuperpositionEntityPolicy.restricted((ServerWorld) (Object) this) && !SuperpositionEntityPolicy.managed(entity)) {
            SuperpositionEntityPolicy.rejected(entity, "加入世界");
            callback.setReturnValue(false);
        }
    }
}
