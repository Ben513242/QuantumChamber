package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.SessionTransferFault;
import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** require=0 對應三個不同 target；native case 的 hit sentinel 必須實際命中。 */
@Mixin({SessionTransferService.class,CorridorPageManager.class,SessionRecoveryState.class})
abstract class SessionTransferFaultMixin {
    @Inject(method="move(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;FF)Z",at=@At("HEAD"),cancellable=true,remap=false,require=0)
    private void quantumchamberTest$reject(ServerPlayerEntity player,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch,
            CallbackInfoReturnable<Boolean> callback) {
        if(SessionTransferFault.rejectMove(player,world,position)) callback.setReturnValue(false);
    }
    @Inject(method="move(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;FF)Z",at=@At("RETURN"),remap=false,require=0)
    private void quantumchamberTest$moved(ServerPlayerEntity player,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch,
            CallbackInfoReturnable<Boolean> callback) {
        SessionTransferFault.moved(player,world,position,callback.getReturnValue());
        dev.quantumchamber.gametest.M2CorridorGameTests.afterRemapMove(player,world,position,velocity,yaw,pitch,callback.getReturnValue());
    }
    @Inject(method="commitInitial",at=@At("HEAD"),remap=false,require=0)
    private void quantumchamberTest$publish(UUID sid,Set<UUID> cohort,CallbackInfo callback) {
        SessionTransferFault.beforePublish(this,sid,cohort);
    }
    @Inject(method="flush",at=@At("RETURN"),remap=false,require=0)
    private void quantumchamberTest$rollback(MinecraftServer server,CallbackInfo callback) {
        SessionTransferFault.afterFlush((SessionRecoveryState)(Object)this,server);
    }
}
