package dev.quantumchamber.transfer;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.TeleportTarget;

/** 原生移動後直接核對玩家身分與完整 pose，不信任 teleport 的 boolean。 */
public final class SessionTransferService {
    public boolean move(ServerPlayerEntity player,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch) {
        var server=world.getServer();
        if (!server.isOnThread() || player.getServer()!=server || server.getWorld(world.getRegistryKey())!=world
                || server.getPlayerManager().getPlayer(player.getUuid())!=player) return false;
        try {
            var moved=player.teleportTo(new TeleportTarget(world,position,velocity,yaw,pitch,TeleportTarget.NO_OP));
            return moved==player && server.getPlayerManager().getPlayer(player.getUuid())==player
                    && player.getServerWorld()==world && world.getEntity(player.getUuid())==player
                    && player.getPos().squaredDistanceTo(position)<1e-12
                    && player.getVelocity().squaredDistanceTo(velocity)<1e-12
                    && Math.abs(MathHelper.wrapDegrees(player.getYaw()-yaw))<1e-4 && Math.abs(player.getPitch()-pitch)<1e-4;
        } catch (RuntimeException failure) { return false; }
    }
}
