package dev.quantumchamber.mixin;

import dev.quantumchamber.persistence.PlayerRecoveryCheckpoint;
import dev.quantumchamber.persistence.PlayerRecoveryCheckpointAccess;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public abstract class ServerPlayerRecoveryCheckpointMixin implements PlayerRecoveryCheckpointAccess {
    @Unique private NbtElement quantumchamber$rawRecoveryMarker;

    @Inject(method = "readCustomDataFromNbt", at = @At("TAIL"))
    private void quantumchamber$readMarker(NbtCompound nbt, CallbackInfo ci) {
        // 不在原生 load 的例外攔截範圍內拋錯，避免被當成新玩家或退回舊檔。
        NbtElement raw = nbt.get(PlayerRecoveryCheckpoint.NBT_KEY);
        quantumchamber$rawRecoveryMarker = raw == null ? null : raw.copy();
    }

    @Inject(method = "writeCustomDataToNbt", at = @At("TAIL"))
    private void quantumchamber$writeMarker(NbtCompound nbt, CallbackInfo ci) {
        if (quantumchamber$rawRecoveryMarker != null) {
            nbt.put(PlayerRecoveryCheckpoint.NBT_KEY, quantumchamber$rawRecoveryMarker.copy());
        }
    }

    @Inject(method = "copyFrom", at = @At("TAIL"))
    private void quantumchamber$copyMarker(ServerPlayerEntity oldPlayer, boolean alive, CallbackInfo ci) {
        quantumchamber$rawRecoveryMarker = ((PlayerRecoveryCheckpointAccess) oldPlayer)
                .quantumchamber$copyRecoveryCheckpointMarker().orElse(null);
    }

    @Override public Optional<PlayerRecoveryCheckpoint> quantumchamber$getRecoveryCheckpoint() {
        if (quantumchamber$rawRecoveryMarker == null) return Optional.empty();
        try {
            return Optional.of(PlayerRecoveryCheckpoint.fromNbt(quantumchamber$rawRecoveryMarker));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("玩家恢復 marker 損壞，保留原始資料並拒絕交易", exception);
        }
    }

    @Override public void quantumchamber$setRecoveryCheckpoint(PlayerRecoveryCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!((ServerPlayerEntity) (Object) this).getServer().isOnThread()) {
            throw new IllegalStateException("玩家恢復 marker 只能在伺服器執行緒修改");
        }
        quantumchamber$getRecoveryCheckpoint();
        quantumchamber$rawRecoveryMarker = checkpoint.toNbt();
    }

    @Override public Optional<NbtElement> quantumchamber$copyRecoveryCheckpointMarker() {
        return Optional.ofNullable(quantumchamber$rawRecoveryMarker).map(NbtElement::copy);
    }
}
