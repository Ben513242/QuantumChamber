package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M2LeaseBootstrapProbe;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 正式world已建立、SERVER_STARTED authority尚未attach；預設完全不執行fixture。 */
@Mixin(MinecraftServer.class)
abstract class M2LeaseBootstrapFixtureMixin {
    @Inject(method="loadWorld",at=@At("RETURN"))
    private void quantumchamberTest$installLeaseFixture(CallbackInfo callback) {
        M2LeaseBootstrapProbe.beforeAuthorityAttach((MinecraftServer)(Object)this);
    }
}
