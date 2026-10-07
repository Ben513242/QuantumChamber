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

/**
 * require=0 對應三個不同 target；native case 的 hit sentinel 必須實際命中。
 * <p>M5 F5-1：原生移動的開頭另外問 {@code M5TestHooks.rejectNativeMove}（Slot，{@code M5GameTestSupport.installNativeMoveFault}），讓通道的傳送在原生移動或回滾時被拒
 * （{@code FAILED_ROLLED_BACK}／{@code FAILED_RECOVERY_REQUIRED}）。既有的兩個條件綁在 M2 的 ARMING 入場與 M2 的持久化 probe，範圍限不到 PASSAGE_READY 的 session，
 * 所以在同一個注入點加一個 Slot 的條件（README：mixin 讀取的靜態 hook 一律是 Slot），不另開一個注入同一個方法的 mixin。
 */
@Mixin({SessionTransferService.class,CorridorPageManager.class,SessionRecoveryState.class})
abstract class SessionTransferFaultMixin {
    @Inject(method="move(Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;FF)Z",at=@At("HEAD"),cancellable=true,remap=false,require=0)
    private void quantumchamberTest$reject(ServerPlayerEntity player,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch,
            CallbackInfoReturnable<Boolean> callback) {
        if(SessionTransferFault.rejectMove(player,world,position)
                || dev.quantumchamber.gametest.M2PersistenceProbe.normalRejectMove(player,world)
                || dev.quantumchamber.gametest.M5TestHooks.rejectNativeMove(player,world)) callback.setReturnValue(false);
    }
    @Inject(method="move(Lnet/minecraft/entity/Entity;Lnet/minecraft/server/world/ServerWorld;Lnet/minecraft/util/math/Vec3d;Lnet/minecraft/util/math/Vec3d;FF)Z",at=@At("HEAD"),cancellable=true,remap=false,require=0)
    private void quantumchamberTest$normalEntity(net.minecraft.entity.Entity entity,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch,
            CallbackInfoReturnable<Boolean> callback) {
        dev.quantumchamber.gametest.M2PersistenceProbe.normalObserveEntityMove(entity,world);
        if(dev.quantumchamber.gametest.M2PersistenceProbe.normalRejectMove(entity,world)) callback.setReturnValue(false);
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
