package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M4CandidateRecoveryProbe;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 僅觀察原生同步世界存檔完成，不能以 journal receipt 推測方塊已持久化。 */
@Mixin(ServerWorld.class)
abstract class M4RecoveryWorldSaveMixin {
    @Inject(method="save",at=@At("HEAD"))
    private void quantumchamberTest$before(ProgressListener progress,boolean flush,boolean disabled,CallbackInfo callback) {
        if(flush && !disabled) M4CandidateRecoveryProbe.beforeWorldSave((ServerWorld)(Object)this);
    }
    @Inject(method="save",at=@At("RETURN"))
    private void quantumchamberTest$worldSaved(ProgressListener progress,boolean flush,boolean disabled,CallbackInfo callback) {
        if(flush && !disabled) M4CandidateRecoveryProbe.afterWorldSave((ServerWorld)(Object)this);
    }
}
