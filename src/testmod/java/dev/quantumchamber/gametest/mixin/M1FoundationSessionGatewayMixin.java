package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M1FoundationSessionFixture;
import dev.quantumchamber.chamber.*;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.util.*;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** release 不含此 hook；只把已標記 M1 foundation 保持在 ARMED_ONLY。 */
@Mixin(SuperpositionSessionManager.class)
abstract class M1FoundationSessionGatewayMixin {
    @Inject(method="start",at=@At("HEAD"),cancellable=true,remap=false)
    private void quantumchamberTest$foundation(ServerWorld world,ChamberControllerBlockEntity controller,List<UUID> participants,
            CallbackInfoReturnable<ChamberSessionGateway.StartResult> callback) {
        if(M1FoundationSessionFixture.contains(world,controller.getPos())) callback.setReturnValue(ChamberSessionGateway.StartResult.ARMED_ONLY);
    }
}
