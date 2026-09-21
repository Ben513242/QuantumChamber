package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M2PersistenceProbe;
import dev.quantumchamber.persistence.SessionRecoveryState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SessionRecoveryState.class)
abstract class SessionJournalWindowFaultMixin {
    @Inject(method="flush(Lnet/minecraft/server/MinecraftServer;)V",at=@At("HEAD"),remap=false)
    private void quantumchamberTest$before(MinecraftServer server,CallbackInfo callback) {
        dev.quantumchamber.gametest.M4CandidateRecoveryProbe.beforeJournalFlush((SessionRecoveryState)(Object)this,server);
        dev.quantumchamber.gametest.M2CorridorGameTests.beforeDisconnectJournalFlush((SessionRecoveryState)(Object)this,server);
        M2PersistenceProbe.beforeJournalFlush((SessionRecoveryState)(Object)this,server);
        dev.quantumchamber.gametest.M4CandidateDoorGameTests.beforeCandidateFlush((SessionRecoveryState)(Object)this,server);
    }
    @Inject(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void quantumchamberTest$preserveCrashWindow(java.io.File file,net.minecraft.registry.RegistryWrapper.WrapperLookup lookup,CallbackInfo callback) {
        if(dev.quantumchamber.gametest.M4CandidateRecoveryProbe.suppressJournalSave(file.toPath())) callback.cancel();
    }
    @Inject(method="flush(Lnet/minecraft/server/MinecraftServer;)V",at=@At("RETURN"),remap=false)
    private void quantumchamberTest$after(MinecraftServer server,CallbackInfo callback) {
        dev.quantumchamber.gametest.M4CandidateDoorGameTests.afterCandidateFlush((SessionRecoveryState)(Object)this,server);
    }
}
