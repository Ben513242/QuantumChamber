package dev.quantumchamber.mixin;

import java.nio.ByteBuffer;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts;
import net.minecraft.util.math.noise.SimplexNoiseSampler;
import org.spongepowered.asm.mixin.*;

/** 將原生Simplex狀態複製為owned bytes；不公開permutation。 */
@Mixin(SimplexNoiseSampler.class)
public abstract class SimplexSettingsStateMixin implements Minecraft121NativeValueContracts.SimplexStateAccess {
    @Shadow @Final private int[] permutation;
    @Shadow @Final public double originX;
    @Shadow @Final public double originY;
    @Shadow @Final public double originZ;
    @Override public Minecraft121NativeValueContracts.SimplexStateSeal quantumchamber$captureSimplexState() {
        if(permutation.length!=512) throw new IllegalStateException("NATIVE_END_SIMPLEX_SHAPE");
        var bytes=ByteBuffer.allocate(2076);
        bytes.putLong(Double.doubleToRawLongBits(originX));bytes.putLong(Double.doubleToRawLongBits(originY));bytes.putLong(Double.doubleToRawLongBits(originZ));
        bytes.putInt(permutation.length);for(int value:permutation) bytes.putInt(value);
        return Minecraft121NativeValueContracts.SimplexStateSeal.capture(permutation,bytes.array());
    }
}
