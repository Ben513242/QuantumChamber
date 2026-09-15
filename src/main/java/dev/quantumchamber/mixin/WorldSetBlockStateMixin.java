package dev.quantumchamber.mixin;

import dev.quantumchamber.chamber.ChamberProtectionService;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(World.class)
abstract class WorldSetBlockStateMixin {
    @Inject(
            method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true)
    private void quantumchamber$protectVolume(
            BlockPos pos,
            BlockState newState,
            int flags,
            int maxUpdateDepth,
            CallbackInfoReturnable<Boolean> cir) {
        World world = (World) (Object) this;
        if (world instanceof ServerWorld serverWorld
                && !ChamberProtectionService.get().mayMutate(serverWorld, pos)) {
            cir.setReturnValue(false);
        }
    }
}
