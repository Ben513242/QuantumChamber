package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.quantumchamber.gametest.DoorWriteFault;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(World.class)
abstract class DoorWriteFaultMixin {
    @WrapMethod(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z")
    private boolean quantumchamberTest$failDoorWrite(BlockPos pos, BlockState state, int flags, int depth,
                                                   Operation<Boolean> original) {
        World world = (World) (Object) this;
        return world instanceof ServerWorld serverWorld
                ? DoorWriteFault.write(serverWorld, pos, state, () -> original.call(pos, state, flags, depth))
                : original.call(pos, state, flags, depth);
    }
}
