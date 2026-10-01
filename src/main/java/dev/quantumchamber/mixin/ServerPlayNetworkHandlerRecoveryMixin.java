package dev.quantumchamber.mixin;

import dev.quantumchamber.persistence.SessionRecoveryManager;
import java.util.EnumSet;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.network.packet.s2c.play.VehicleMoveS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原生 forceMainThread 已成功返回後才讀恢復權威；登入與 teleport ACK 保持可用。
 * 凍結中丟棄移動封包時，送回伺服器的權威位置，同一玩家節流（{@link SessionRecoveryManager#correctionDue}），客戶端座標不會一路漂移到返還為止。
 */
@Mixin(ServerPlayNetworkHandler.class)
abstract class ServerPlayNetworkHandlerRecoveryMixin {
    @Shadow public ServerPlayerEntity player;

    // 修正走原版 teleport 流程：requestTeleport 設定待確認的 teleport，ACK 照常由 onTeleportConfirm 處理；之後伺服器任何 teleport
    // 都會換新 id，舊 ACK 不再生效。座標用伺服器現值（絕對座標，客戶端速度歸零）；視角以相對 0 送出，不把鏡頭拉回伺服器上已過時的朝向。
    // 騎乘中的移動封包只帶視角、位置由載具決定（原版同樣只採用視角），所以騎乘時不修正玩家，載具由 onVehicleMove 修正。
    @Inject(method="onPlayerMove(Lnet/minecraft/network/packet/c2s/play/PlayerMoveC2SPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",shift=At.Shift.AFTER),cancellable=true)
    private void quantumchamber$holdPlayerMovement(CallbackInfo callback) {
        if(!SessionRecoveryManager.blocks(player.getUuid(),player.getServer())) return;
        callback.cancel();
        if(player.hasVehicle() || player.notInAnyWorld || !SessionRecoveryManager.correctionDue(player.getUuid(),player.getServer())) return;
        ((ServerPlayNetworkHandler)(Object)this).requestTeleport(player.getX(),player.getY(),player.getZ(),player.getYaw(),player.getPitch(),
                EnumSet.of(PositionFlag.Y_ROT,PositionFlag.X_ROT));
    }

    // 只修正原版 onVehicleMove 會處理的載具：根載具不是玩家本身、而且由玩家控制。這時送回根載具的伺服器位置，與原版拒絕載具移動時的修正相同；
    // 其他情況原版也直接忽略封包，這裡只取消、不消耗節流。原版另要求根載具等於 handler tick 留下的 topmostRiddenEntity（同一條件的上個 tick 快照），
    // 這裡不比對：差別只在剛上車、換載具或剛取得控制的那一個 tick，原版忽略該封包，這裡送回的仍是載具的伺服器位置。
    @Inject(method="onVehicleMove(Lnet/minecraft/network/packet/c2s/play/VehicleMoveC2SPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/network/NetworkThreadUtils;forceMainThread(Lnet/minecraft/network/packet/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/server/world/ServerWorld;)V",shift=At.Shift.AFTER),cancellable=true)
    private void quantumchamber$holdVehicleMovement(CallbackInfo callback) {
        if(!SessionRecoveryManager.blocks(player.getUuid(),player.getServer())) return;
        callback.cancel();
        var vehicle=player.getRootVehicle();
        if(vehicle==player || vehicle.getControllingPassenger()!=player
                || !SessionRecoveryManager.correctionDue(player.getUuid(),player.getServer())) return;
        ((ServerPlayNetworkHandler)(Object)this).sendPacket(new VehicleMoveS2CPacket(vehicle));
    }

    // 先保留原生 sequence ACK，再拒絕 pending 玩家的方塊操作，不留下客戶端未確認的預測。
    @Inject(method="onPlayerInteractBlock(Lnet/minecraft/network/packet/c2s/play/PlayerInteractBlockC2SPacket;)V",
            at=@At(value="INVOKE",target="Lnet/minecraft/server/network/ServerPlayNetworkHandler;updateSequence(I)V",shift=At.Shift.AFTER),cancellable=true)
    private void quantumchamber$holdInteraction(CallbackInfo callback) {
        if(SessionRecoveryManager.blocks(player.getUuid(),player.getServer())) callback.cancel();
    }
}
