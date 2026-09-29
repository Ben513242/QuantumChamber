package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * backstop：spawnEntity／tryLoadEntity／spawnEntityAndPassengers／非玩家 onDimensionChanged 都經過 addEntity；
 * 量子走廊世界只接受玩家、掉落物、投射物與經驗球。玩家另走 addPlayer，不受影響。
 * 這裡只能拒絕加入，擋不住呼叫端的後續動作。原版有些路徑先刪除來源資料、且忽略回傳值，必須在來源端先攔下：
 * 使用物品生成 entity（{@link ItemStackSuperpositionEntityMixin}，在扣除物品之前）、跨維度傳送與 /tp（{@link EntitySuperpositionTeleportMixin}，
 * 在移除原 entity 之前）、肩上鸚鵡放下（{@link PlayerEntityShoulderSuperpositionMixin}，在清空肩上 NBT 之前）。
 * /summon 被拒時原版仍回報成功，只是訊息外觀，沒有資料遺失。chunk 載入既有 entity 走 ServerEntityManager，不經過這裡。
 */
@Mixin(ServerWorld.class)
abstract class ServerWorldEntityAdmissionMixin {
    @Inject(method = "addEntity(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$admitManagedOnly(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        if (SuperpositionEntityPolicy.restricted((ServerWorld) (Object) this) && !SuperpositionEntityPolicy.managed(entity)) {
            SuperpositionEntityPolicy.rejected(entity, "加入世界");
            callback.setReturnValue(false);
        }
    }
}
