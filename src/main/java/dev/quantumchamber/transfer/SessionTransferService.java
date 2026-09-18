package dev.quantumchamber.transfer;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.TeleportTarget;

/** 原生移動後直接核對玩家身分與完整 pose，不信任 teleport 的 boolean。 */
public final class SessionTransferService {
    /** 跨世界可能產生新 Entity；以 UUID 查核目標實例，不沿用 removed 舊參照。 */
    public boolean move(net.minecraft.entity.Entity entity,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch) {
        if(entity instanceof ServerPlayerEntity player) return move(player,world,position,velocity,yaw,pitch);
        var server=world.getServer(); var id=entity.getUuid();
        if(!server.isOnThread() || entity.isRemoved() || entity.getServer()!=server
                || server.getWorld(world.getRegistryKey())!=world || !(entity.getWorld() instanceof ServerWorld source)
                || source.getEntity(id)!=entity) return false;
        try {
            var moved=entity.teleportTo(new TeleportTarget(world,position,velocity,yaw,pitch,TeleportTarget.NO_OP));
            return moved!=null && !moved.isRemoved() && moved.getUuid().equals(id) && moved.getWorld()==world && world.getEntity(id)==moved
                    && moved.getPos().squaredDistanceTo(position)<1e-12 && moved.getVelocity().squaredDistanceTo(velocity)<1e-12
                    && Math.abs(MathHelper.wrapDegrees(moved.getYaw()-yaw))<1e-4 && Math.abs(moved.getPitch()-pitch)<1e-4;
        } catch(RuntimeException failure) { return false; }
    }
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
