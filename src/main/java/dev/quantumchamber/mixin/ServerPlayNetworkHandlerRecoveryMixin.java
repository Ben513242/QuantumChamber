package dev.quantumchamber.mixin;

import dev.quantumchamber.persistence.SessionRecoveryManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原生 forceMainThread 已成功返回後才讀恢復權威；登入與 teleport ACK 保持可用。 */
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerRecoveryMixin {
    @Shadow public ServerPlayerEntity player;

    @Inject(method={"onPlayerMove(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V",
            "onVehicleMove(Lnet/minecraft/network/packet/c2s/play/VehicleMoveC2SPacket;)V"},
            at=@At(value="INVOKE",target="Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",shift=At.Shift.AFTER),cancellable=true)
    private void quantumchamber$holdMovement(CallbackInfo callback) {
        if(SessionRecoveryManager.blocks(player.getUuid(),player.getServer())) callback.cancel();
    }

    // 先保留原生 sequence ACK，再拒絕 pending 玩家的方塊操作，不留下客戶端未確認的預測。
    @Inject(method="onPlayerInteractBlock(Lnet/minecraft/network/packet/c2s/play/PlayerInteractBlockC2SPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerPlayNetworkHandler;updateSequence(I)V",shift=At.Shift.AFTER),cancellable=true)
    private void quantumchamber$holdInteraction(CallbackInfo callback) {
        if(SessionRecoveryManager.blocks(player.getUuid(),player.getServer())) callback.cancel();
    }
}
