package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M2CorridorGameTests;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.TeleportTarget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 僅 testmod：own 玩家／目的 world 的原生 teleport 受控例外，用來驗證 production 移動例外的診斷紀錄。 */
@Mixin(ServerPlayerEntity.class)
abstract class PlayerTeleportExceptionFaultMixin {
    @Inject(method="teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/entity/Entity;",at=@At("HEAD"))
    private void quantumchamberTest$throw(TeleportTarget target,CallbackInfoReturnable<Entity> callback) {
        M2CorridorGameTests.beforePlayerTeleport((ServerPlayerEntity)(Object)this,target);
    }
}
