package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M4CandidateDoorGameTests;
import dev.quantumchamber.persistence.MeasuredWorldSaveCheckpoint;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 僅 testmod：記錄 MEASURED 原生存檔 checkpoint 的呼叫 tick，並在 own SID 故障窗口內讓它受控失敗；
 * RETURN 只在 checkpoint 正常返回（被承認）時觸發，例外路徑不會經過。
 */
@Mixin(MeasuredWorldSaveCheckpoint.class)
abstract class MeasuredCheckpointFaultMixin {
    // M7 T5-2 起 save 有 HOME overload（HomeSessionRecord）；只注入舊列的 overload，明寫完整 descriptor（X6）。
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;)V",at=@At("HEAD"),remap=false)
    private static void quantumchamberTest$fault(MinecraftServer server,SessionRecoveryRecord record,CallbackInfo callback) {
        M4CandidateDoorGameTests.beforeMeasuredCheckpoint(server,record);
    }
    @Inject(method="save(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/persistence/SessionRecoveryRecord;)V",at=@At("RETURN"),remap=false)
    private static void quantumchamberTest$accepted(MinecraftServer server,SessionRecoveryRecord record,CallbackInfo callback) {
        M4CandidateDoorGameTests.afterMeasuredCheckpoint(server,record);
    }
}
