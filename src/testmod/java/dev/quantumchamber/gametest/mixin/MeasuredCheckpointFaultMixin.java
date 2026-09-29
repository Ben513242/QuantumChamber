package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M4CandidateDoorGameTests;
import dev.quantumchamber.persistence.MeasuredWorldSaveCheckpoint;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 僅 testmod：記錄 MEASURED 原生存檔 checkpoint 的呼叫 tick，並在 own SID 故障窗口內讓它受控失敗。 */
@Mixin(MeasuredWorldSaveCheckpoint.class)
abstract class MeasuredCheckpointFaultMixin {
    @Inject(method="save",at=@At("HEAD"),remap=false)
    private static void quantumchamberTest$fault(MinecraftServer server,SessionRecoveryRecord record,CallbackInfo callback) {
        M4CandidateDoorGameTests.beforeMeasuredCheckpoint(server,record);
    }
}
