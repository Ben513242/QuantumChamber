package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 肩上鸚鵡只以 NBT 存在玩家身上。原版 dropShoulderEntities 以 ServerWorld.tryLoadEntity 生成鸚鵡後忽略回傳值，接著無條件清空肩上 NBT；
 * 量子走廊世界的 addEntity backstop 會拒絕鸚鵡，所以在走廊世界整段略過放下：鸚鵡留在肩上，隨玩家返還原艙
 * （死亡重生時 ServerPlayerEntity.copyFrom 無條件複製肩上 NBT，同樣不遺失）。
 * 原版觸發點：tickMovement（落下、碰水、飛行、睡眠、粉雪）、damage、useRiptide、ServerPlayerEntity.onDeath 與切換為旁觀模式。
 * 只比對玩家目前所在 world；client world 與其他 world 照原版放下。
 */
@Mixin(PlayerEntity.class)
abstract class PlayerEntityShoulderSuperpositionMixin {
    @Inject(method = "dropShoulderEntities()V", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$keepShoulderEntities(CallbackInfo callback) {
        if (SuperpositionEntityPolicy.restricted(((PlayerEntity) (Object) this).getWorld())) callback.cancel();
    }
}
