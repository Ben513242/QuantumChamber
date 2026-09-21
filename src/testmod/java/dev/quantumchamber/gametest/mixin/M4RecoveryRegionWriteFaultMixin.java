package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M4CandidateRecoveryProbe;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 真正的 region write 邊界拋錯，由原生 IO worker 捕捉並完成 exceptional future。 */
@Mixin(RegionBasedStorage.class)
abstract class M4RecoveryRegionWriteFaultMixin {
    @Inject(method="write",at=@At("HEAD"))
    private void quantumchamberTest$fail(ChunkPos pos,NbtCompound nbt,CallbackInfo callback) throws java.io.IOException {
        M4CandidateRecoveryProbe.beforeRegionWrite(((RegionBasedStorage)(Object)this).getStorageKey(),pos);
    }
}
