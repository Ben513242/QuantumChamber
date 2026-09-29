package dev.quantumchamber.mixin;

import dev.quantumchamber.corridor.SuperpositionEntityPolicy;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 量子走廊世界的物品使用層：在方塊互動已 PASS、物品邏輯尚未執行時拒絕會生成非管理 entity 的物品，因此不消耗物品，
 * 也不影響持物右鍵 Controller／艙門等方塊互動（那些在 ItemStack.useOnBlock 之前處理）。
 */
@Mixin(ItemStack.class)
abstract class ItemStackSuperpositionEntityMixin {
    @Inject(method = "useOnBlock(Lnet/minecraft/item/ItemUsageContext;)Lnet/minecraft/util/ActionResult;", at = @At("HEAD"), cancellable = true)
    private void quantumchamber$denyEntityPlacement(ItemUsageContext context, CallbackInfoReturnable<ActionResult> callback) {
        if (SuperpositionEntityPolicy.restricted(context.getWorld())
                && SuperpositionEntityPolicy.spawnsUnmanagedEntity((ItemStack) (Object) this)) {
            if (context.getPlayer() != null) SuperpositionEntityPolicy.denyItemUse(context.getPlayer());
            callback.setReturnValue(ActionResult.FAIL);
        }
    }

    @Inject(method = "use(Lnet/minecraft/world/World;Lnet/minecraft/entity/player/PlayerEntity;Lnet/minecraft/util/Hand;)Lnet/minecraft/util/TypedActionResult;",
            at = @At("HEAD"), cancellable = true)
    private void quantumchamber$denyEntityUse(World world, PlayerEntity user, Hand hand,
                                              CallbackInfoReturnable<TypedActionResult<ItemStack>> callback) {
        var stack = (ItemStack) (Object) this;
        if (SuperpositionEntityPolicy.restricted(world) && SuperpositionEntityPolicy.spawnsUnmanagedEntity(stack)) {
            SuperpositionEntityPolicy.denyItemUse(user);
            callback.setReturnValue(TypedActionResult.fail(stack));
        }
    }
}
