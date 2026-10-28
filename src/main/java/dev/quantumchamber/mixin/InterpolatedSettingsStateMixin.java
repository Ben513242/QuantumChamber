package dev.quantumchamber.mixin;

import java.nio.ByteBuffer;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts.SamplerStateSeal;
import net.minecraft.util.math.noise.*;
import org.spongepowered.asm.mixin.*;
import static dev.quantumchamber.universe.Minecraft121NativeValueContracts.*;

/** 八個raw scalar與三個有序sampler的owned封存。 */
@Mixin(InterpolatedNoiseSampler.class)
public abstract class InterpolatedSettingsStateMixin implements Minecraft121NativeValueContracts.InterpolatedStateAccess {
    @Shadow @Final private OctavePerlinNoiseSampler lowerInterpolatedNoise;
    @Shadow @Final private OctavePerlinNoiseSampler upperInterpolatedNoise;
    @Shadow @Final private OctavePerlinNoiseSampler interpolationNoise;
    @Shadow @Final private double xzScale;
    @Shadow @Final private double yScale;
    @Shadow @Final private double xzFactor;
    @Shadow @Final private double yFactor;
    @Shadow @Final private double smearScaleMultiplier;
    @Shadow @Final private double scaledXzScale;
    @Shadow @Final private double scaledYScale;
    @Shadow @Final private double maxValue;
    @Override public SamplerStateSeal quantumchamber$captureInterpolatedState() {
        samplerType(this,InterpolatedNoiseSampler.class);
        Object[] identities={lowerInterpolatedNoise,upperInterpolatedNoise,interpolationNoise};
        var children=new SamplerStateSeal[3];int size=64;
        for(int i=0;i<3;i++) {
            samplerType(identities[i],OctavePerlinNoiseSampler.class);
            for(int j=0;j<i;j++)if(identities[i]==identities[j])throw new IllegalStateException("NATIVE_SAMPLER_ALIASED");
            if(!(identities[i] instanceof OctaveStateAccess access))throw new IllegalStateException("NATIVE_SAMPLER_BRIDGE_MISSING");
            children[i]=access.quantumchamber$captureOctaveState();size+=4+children[i].bytes().length;
        }
        var bytes=ByteBuffer.allocate(size);
        for(double value:new double[]{xzScale,yScale,xzFactor,yFactor,smearScaleMultiplier,scaledXzScale,scaledYScale,maxValue})bytes.putLong(samplerBits(value));
        for(var child:children) {byte[] raw=child.bytes();bytes.putInt(raw.length).put(raw);}
        return SamplerStateSeal.capture(this,null,identities,children,bytes.array());
    }
}
