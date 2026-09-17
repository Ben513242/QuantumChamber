package dev.quantumchamber.mixin;

import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(PlayerManager.class)
public interface PlayerManagerSaveInvoker {
    @Invoker("savePlayerData")
    void quantumchamber$savePlayerData(ServerPlayerEntity player);
}
