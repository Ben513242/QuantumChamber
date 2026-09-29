package dev.quantumchamber.gametest.mixin;

import com.sun.jna.Platform;
import dev.quantumchamber.gametest.CheckpointPlatformFault;
import dev.quantumchamber.persistence.PlayerCheckpointStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** 僅 testmod：own-server 平台模擬只改 PlayerCheckpointStore 內每一個平台判斷，不改 verifier、native API 或 production 行為。 */
@Mixin(PlayerCheckpointStore.class)
abstract class CheckpointPlatformFaultMixin {
    @Redirect(method="*",at=@At(value="INVOKE",target="Lcom/sun/jna/Platform;isWindows()Z",remap=false),remap=false,require=1)
    private static boolean quantumchamberTest$platform() {
        return !CheckpointPlatformFault.platformUnsupported() && Platform.isWindows();
    }
}
