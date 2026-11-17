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
        dev.quantumchamber.gametest.M5TestHooks.beforeJournalFlush((SessionRecoveryState)(Object)this,server);
        dev.quantumchamber.gametest.M4CandidateDoorGameTests.beforeCandidateFlush((SessionRecoveryState)(Object)this,server);
    }
    @Inject(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",at=@At("HEAD"),cancellable=true,remap=false)
    private void quantumchamberTest$preserveCrashWindow(java.io.File file,net.minecraft.registry.RegistryWrapper.WrapperLookup lookup,CallbackInfo callback) {
        if(dev.quantumchamber.gametest.M4CandidateRecoveryProbe.suppressJournalSave(file.toPath())) callback.cancel();
    }
    /** 正式檔已原子替換後、strict readback 前的受控失敗窗口；僅 M4 owned-root probe 會武裝。 */
    @Inject(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/persistence/SessionRecoveryState$JournalReadback;read(Ljava/nio/file/Path;)Lnet/minecraft/nbt/NbtCompound;"),remap=false)
    private void quantumchamberTest$readbackWindow(java.io.File file,net.minecraft.registry.RegistryWrapper.WrapperLookup lookup,CallbackInfo callback) {
        dev.quantumchamber.gametest.M4CandidateRecoveryProbe.beforeJournalReadback(file.toPath());
    }
    /**
     * T4 P2：journal 正式寫出（{@code SessionJournalStore.writePrepared}，原生存檔與 checked flush 共用）的目標檔。{@code M7NativeSaveFault} 武裝時改指向既存目錄，
     * 讓真正的 {@code Files.move} 失敗（Windows 是 AccessDenied），正式 journal 檔不動；沒有武裝時原樣傳回。{@code save} 內恰有一處 writePrepared。
     */
    @ModifyArg(method="save(Ljava/io/File;Lnet/minecraft/registry/RegistryWrapper$WrapperLookup;)V",
            at=@At(value="INVOKE",target="Ldev/quantumchamber/persistence/SessionJournalStore;writePrepared(Ljava/nio/file/Path;Ldev/quantumchamber/persistence/SessionJournalStore$PreparedWrite;)V"),
            index=0,remap=false,require=1,allow=1)
    private java.nio.file.Path quantumchamberTest$writeTarget(java.nio.file.Path target) {
        return dev.quantumchamber.gametest.M7NativeSaveFault.writeTarget(target);
    }
    @Inject(method="flush(Lnet/minecraft/server/MinecraftServer;)V",at=@At("RETURN"),remap=false)
    private void quantumchamberTest$after(MinecraftServer server,CallbackInfo callback) {
        dev.quantumchamber.gametest.M4CandidateDoorGameTests.afterCandidateFlush((SessionRecoveryState)(Object)this,server);
    }
}
