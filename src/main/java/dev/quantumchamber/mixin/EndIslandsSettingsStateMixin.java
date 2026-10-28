package dev.quantumchamber.mixin;

import dev.quantumchamber.universe.Minecraft121NativeValueContracts;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import org.spongepowered.asm.mixin.*;

/** 只封存原owner/sampler身分與owned狀態，不公開sampler。 */
@Mixin(targets="net.minecraft.world.gen.densityfunction.DensityFunctionTypes$EndIslands")
public abstract class EndIslandsSettingsStateMixin implements Minecraft121NativeValueContracts.EndStateAccess {
    @Shadow @Final private SimplexNoiseSampler sampler;
    @Override public Minecraft121NativeValueContracts.EndStateSeal quantumchamber$captureEndState() {
        if(sampler==null || sampler.getClass()!=SimplexNoiseSampler.class
                || !(sampler instanceof Minecraft121NativeValueContracts.SimplexStateAccess access))
            throw new IllegalStateException("NATIVE_END_SIMPLEX_OWNER");
        return Minecraft121NativeValueContracts.EndStateSeal.capture(this,sampler,access.quantumchamber$captureSimplexState());
    }
}
