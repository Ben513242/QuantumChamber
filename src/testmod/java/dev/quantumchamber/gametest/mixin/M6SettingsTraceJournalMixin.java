package dev.quantumchamber.gametest.mixin;
import dev.quantumchamber.gametest.M6SettingsTraceObserver;
import dev.quantumchamber.persistence.SessionRecoveryState;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
/** 記錄真首用，不能由 fixture 人工呼叫代替。 */
@Mixin(value=SessionRecoveryState.class,remap=false)
public abstract class M6SettingsTraceJournalMixin {
    @Inject(method="get(Lnet/minecraft/server/MinecraftServer;)Ldev/quantumchamber/persistence/SessionRecoveryState;",at=@At("HEAD"),require=1,expect=1,allow=1,remap=false)
    private static void traceFirstGet(MinecraftServer server, CallbackInfoReturnable<SessionRecoveryState> ci) { M6SettingsTraceObserver.journal(server); }
}
