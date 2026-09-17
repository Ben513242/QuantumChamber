package dev.quantumchamber.mixin;

import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
    @WrapMethod(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z")
    private boolean quantumchamber$commitControllerRemoval(BlockPos pos, BlockState newState, int flags, int depth,
                                                          Operation<Boolean> original) {
        World world = (World) (Object) this;
        return world instanceof ServerWorld serverWorld
                ? ChamberLifecycleService.mutateController(serverWorld, pos, newState,
                        () -> original.call(pos, newState, flags, depth))
                : original.call(pos, newState, flags, depth);
    }

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
