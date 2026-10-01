package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.gametest.M4CandidateDoorGameTests;
import java.util.concurrent.CompletableFuture;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.storage.RegionBasedStorage;
import net.minecraft.world.storage.StorageIoWorker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 H2）：只對 GameTest 指定的 (world, storage type, chunk) 讓原生 region write 受控失敗。
 * 失敗發生在原生 IO worker 自己的 try 範圍內，由 worker completeExceptionally，不寫入 region 檔；
 * 只對該目標等真 IO 完成，固定重現「completeAll 已看不到失敗 pending entry」的窗口，外層 server.save 照常回 true。
 * 不取代、也不改變 M4 recovery probe 專用的 region write／drain mixin。
 */
@Mixin(StorageIoWorker.class)
abstract class ChunkCheckpointScopeFaultMixin {
    @WrapOperation(method="write",at=@At(value="INVOKE",
            target="Lnet/minecraft/world/storage/RegionBasedStorage;write(Lnet/minecraft/util/math/ChunkPos;Lnet/minecraft/nbt/NbtCompound;)V"))
    private void quantumchamberTest$scopedFault(RegionBasedStorage storage,ChunkPos pos,NbtCompound nbt,Operation<Void> original)
            throws java.io.IOException {
        M4CandidateDoorGameTests.beforeScopedRegionWrite(((StorageIoWorker)(Object)this).getStorageKey(),pos);
        original.call(storage,pos,nbt);
    }
    @Inject(method="setResult",at=@At("RETURN"))
    private void quantumchamberTest$scopedDrain(ChunkPos pos,NbtCompound nbt,CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        M4CandidateDoorGameTests.drainScopedFault(((StorageIoWorker)(Object)this).getStorageKey(),pos,callback.getReturnValue());
    }
}
