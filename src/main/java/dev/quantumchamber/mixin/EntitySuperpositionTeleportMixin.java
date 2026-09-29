package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import net.minecraft.entity.Entity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * backstop：非管理 entity 跨維度進入量子走廊世界時，在原 entity 被移出來源世界、乘客被拆開之前就拒絕，
 * 因此 entity 留在原世界、不會因 addEntity 被拒而遺失。玩家覆寫 teleportTo，不經過此處。
 */
@Mixin(Entity.class)
abstract class EntitySuperpositionTeleportMixin {
    @Inject(method = "teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$keepUnmanagedOut(TeleportTarget target, CallbackInfoReturnable<Entity> callback) {
        var self = (Entity) (Object) this;
        if (SuperpositionEntityPolicy.restricted(target.world()) && self.getWorld() != target.world()
                && !SuperpositionEntityPolicy.managed(self)) {
            SuperpositionEntityPolicy.rejected(self, "跨維度進入");
            callback.setReturnValue(null);
        }
    }
}
