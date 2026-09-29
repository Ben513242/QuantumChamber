package dev.quantumchamber.transfer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.TeleportTarget;

/** 原生移動後直接核對玩家身分與完整 pose，不信任 teleport 的 boolean。 */
public final class SessionTransferService {
    /** 同一 entity 最近一次已記錄的原生例外；成功移動後清除，避免每 tick 重試洗版。 */
    private final Map<UUID,String> warnedFailures=new HashMap<>();

    /** 跨世界可能產生新 Entity；以 UUID 查核目標實例，不沿用 removed 舊參照。 */
    public boolean move(net.minecraft.entity.Entity entity,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch) {
        if(entity instanceof ServerPlayerEntity player) return move(player,world,position,velocity,yaw,pitch);
        var server=world.getServer(); var id=entity.getUuid();
        if(!server.isOnThread() || entity.isRemoved() || entity.getServer()!=server
                || server.getWorld(world.getRegistryKey())!=world || !(entity.getWorld() instanceof ServerWorld source)
                || source.getEntity(id)!=entity) return false;
        try {
            var moved=entity.teleportTo(new TeleportTarget(world,position,velocity,yaw,pitch,TeleportTarget.NO_OP));
            return confirmed(id,moved!=null && !moved.isRemoved() && moved.getUuid().equals(id) && moved.getWorld()==world && world.getEntity(id)==moved
                    && moved.getPos().squaredDistanceTo(position)<1e-12 && moved.getVelocity().squaredDistanceTo(velocity)<1e-12
                    && Math.abs(MathHelper.wrapDegrees(moved.getYaw()-yaw))<1e-4 && Math.abs(moved.getPitch()-pitch)<1e-4);
        } catch(RuntimeException failure) { warnFailure(entity,source,world,failure); return false; }
    }
    public boolean move(ServerPlayerEntity player,ServerWorld world,Vec3d position,Vec3d velocity,float yaw,float pitch) {
        var server=world.getServer();
        if (!server.isOnThread() || player.getServer()!=server || server.getWorld(world.getRegistryKey())!=world
                || server.getPlayerManager().getPlayer(player.getUuid())!=player) return false;
        var source=player.getServerWorld();
        try {
            var moved=player.teleportTo(new TeleportTarget(world,position,velocity,yaw,pitch,TeleportTarget.NO_OP));
            return confirmed(player.getUuid(),moved==player && server.getPlayerManager().getPlayer(player.getUuid())==player
                    && player.getServerWorld()==world && world.getEntity(player.getUuid())==player
                    && player.getPos().squaredDistanceTo(position)<1e-12
                    && player.getVelocity().squaredDistanceTo(velocity)<1e-12
                    && Math.abs(MathHelper.wrapDegrees(player.getYaw()-yaw))<1e-4 && Math.abs(player.getPitch()-pitch)<1e-4);
        } catch (RuntimeException failure) { warnFailure(player,source,world,failure); return false; }
    }
    private boolean confirmed(UUID id,boolean success) {
        if(success) warnedFailures.remove(id);
        return success;
    }
    /** 原生 teleport 例外仍回傳 false 由呼叫端保留 pending；此處保留 cause 與 UUID／來源／目的 world，同一 entity 同一原因只記一次。 */
    private void warnFailure(net.minecraft.entity.Entity entity,ServerWorld from,ServerWorld to,RuntimeException failure) {
        var cause=failure.getClass().getName()+": "+failure.getMessage();
        if(cause.equals(warnedFailures.get(entity.getUuid()))) return;
        if(warnedFailures.size()>=256) warnedFailures.clear();
        warnedFailures.put(entity.getUuid(),cause);
        org.slf4j.LoggerFactory.getLogger("quantumchamber").warn("原生移動例外，保留 pending 等待重試：entity={} type={} from={} to={}",
                entity.getUuid(),EntityType.getId(entity.getType()),from.getRegistryKey().getValue(),to.getRegistryKey().getValue(),failure);
    }
}
