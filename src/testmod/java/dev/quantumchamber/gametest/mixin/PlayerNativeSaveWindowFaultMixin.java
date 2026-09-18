package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M2PersistenceProbe;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerManager.class)
abstract class PlayerNativeSaveWindowFaultMixin {
    @Inject(method="savePlayerData(Lnet/minecraft/server/network/ServerPlayerEntity;)V",at=@At("HEAD"),cancellable=true)
    private void quantumchamberTest$stale(ServerPlayerEntity player,CallbackInfo callback) {
        if(M2PersistenceProbe.rejectNativeSave(this,player)) callback.cancel();
    }
}
