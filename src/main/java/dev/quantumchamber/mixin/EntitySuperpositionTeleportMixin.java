package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import java.util.Set;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * backstop：非管理 entity 跨維度進入量子走廊世界時，在原 entity 被移出來源世界之前就拒絕，entity 留在原世界、不會因 addEntity 被拒而遺失。
 * Minecraft 1.21 有兩條各自完成跨維度的原版路徑，兩者都先移除原 entity、再以不看回傳值的 onDimensionChanged 加入副本，因此都在開頭攔下：
 * <ul>
 *   <li>{@code teleportTo(TeleportTarget)}：傳送門等；在拆開乘客、建立副本之前回 null。</li>
 *   <li>{@code teleport(ServerWorld, x, y, z, flags, yaw, pitch)}：/tp、/execute in … run tp、/spreadplayers；在 detach 與
 *       CHANGED_DIMENSION 移除之前回 false。原版指令不因 false 報錯，仍會顯示傳送成功訊息，但 entity 未移動。</li>
 * </ul>
 * 同一 world 內的傳送不受影響。玩家（ServerPlayerEntity）覆寫這兩個方法且不呼叫這裡的實作，玩家本身也屬管理種類，不受影響。
 */
@Mixin(Entity.class)
abstract class EntitySuperpositionTeleportMixin {
    @Inject(method = "teleportTo(Lnet/minecraft/world/TeleportTarget;)Lnet/minecraft/entity/Entity;", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$keepUnmanagedOut(TeleportTarget target, CallbackInfoReturnable<Entity> callback) {
        if (quantumchamber$rejectsCrossWorld(target.world(), "跨維度進入")) callback.setReturnValue(null);
    }

    @Inject(method = "teleport(Lnet/minecraft/server/world/ServerWorld;DDDLjava/util/Set;FF)Z", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$keepUnmanagedOutOfCommandTeleport(ServerWorld world, double x, double y, double z, Set<PositionFlag> flags,
                                                                   float yaw, float pitch, CallbackInfoReturnable<Boolean> callback) {
        if (quantumchamber$rejectsCrossWorld(world, "指令跨維度傳送")) callback.setReturnValue(false);
    }

    private boolean quantumchamber$rejectsCrossWorld(World target, String route) {
        var self = (Entity) (Object) this;
        if (!SuperpositionEntityPolicy.restricted(target) || self.getWorld() == target || SuperpositionEntityPolicy.managed(self)) return false;
        SuperpositionEntityPolicy.rejected(self, route);
        return true;
    }
}
