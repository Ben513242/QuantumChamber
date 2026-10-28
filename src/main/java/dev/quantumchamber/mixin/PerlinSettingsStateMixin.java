package dev.quantumchamber.mixin;

import java.nio.ByteBuffer;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts.SamplerStateSeal;
import net.minecraft.util.math.noise.PerlinNoiseSampler;
import org.spongepowered.asm.mixin.*;
import static dev.quantumchamber.universe.Minecraft121NativeValueContracts.*;

/** 固定Perlin三origin與完整排列；不外洩原陣列。 */
@Mixin(PerlinNoiseSampler.class)
public abstract class PerlinSettingsStateMixin implements Minecraft121NativeValueContracts.PerlinStateAccess {
    @Shadow @Final private byte[] permutation;
    @Shadow @Final public double originX;
    @Shadow @Final public double originY;
    @Shadow @Final public double originZ;
    @Override public SamplerStateSeal quantumchamber$capturePerlinState() {
        samplerType(this,PerlinNoiseSampler.class);
        if(permutation==null || permutation.length!=256)throw new IllegalStateException("NATIVE_PERMUTATION_SHAPE");
        var bytes=ByteBuffer.allocate(284);
        bytes.putLong(samplerBits(originX)).putLong(samplerBits(originY)).putLong(samplerBits(originZ));
        bytes.putInt(256).put(permutation);
        return SamplerStateSeal.capture(this,permutation,new Object[0],new SamplerStateSeal[0],bytes.array());
    }
}
