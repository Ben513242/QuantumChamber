package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M2PersistenceProbe;
import dev.quantumchamber.persistence.*;
import java.io.IOException;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerCheckpointStore.class)
abstract class CheckpointWindowFaultMixin {
    @Inject(method="saveAndVerify(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/network/ServerPlayerEntity;Ljava/util/Optional;)V",at=@At("HEAD"),remap=false)
    private static void quantumchamberTest$before(MinecraftServer server,ServerPlayerEntity player,Optional<PlayerRecoveryCheckpoint> expected,CallbackInfo callback) throws IOException {
        M2PersistenceProbe.beforeCheckpoint(server,player,expected);
    }
    @Inject(method="saveAndVerify(Lnet/minecraft/server/MinecraftServer;Lnet/minecraft/server/network/ServerPlayerEntity;Ljava/util/Optional;)V",at=@At("RETURN"),remap=false)
    private static void quantumchamberTest$after(MinecraftServer server,ServerPlayerEntity player,Optional<PlayerRecoveryCheckpoint> expected,CallbackInfo callback) {
        M2PersistenceProbe.afterCheckpoint(server,player,expected);
    }
}
