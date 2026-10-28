package dev.quantumchamber.mixin;

import java.nio.ByteBuffer;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts;
import dev.quantumchamber.universe.Minecraft121NativeValueContracts.SamplerStateSeal;
import net.minecraft.util.math.noise.*;
import org.spongepowered.asm.mixin.*;
import static dev.quantumchamber.universe.Minecraft121NativeValueContracts.*;

/** 原array僅作身分token；每槽的身分與null位置另外封存。 */
@Mixin(OctavePerlinNoiseSampler.class)
public abstract class OctavePerlinSettingsStateMixin implements Minecraft121NativeValueContracts.OctaveStateAccess {
    @Shadow @Final private PerlinNoiseSampler[] octaveSamplers;
    @Shadow @Final private int firstOctave;
    @Shadow @Final private DoubleList amplitudes;
    @Shadow @Final private double persistence;
    @Shadow @Final private double lacunarity;
    @Shadow @Final private double maxValue;
    @Override public SamplerStateSeal quantumchamber$captureOctaveState() {
        samplerType(this,OctavePerlinNoiseSampler.class);
        if(octaveSamplers==null || amplitudes==null)throw new IllegalStateException("NATIVE_OCTAVE_SHAPE");
        int count=octaveSamplers.length, amplitudeCount=amplitudes.size();
        if(count>64 || amplitudeCount>64)throw new IllegalStateException("BUDGET_NATIVE_OCTAVES");
        if(count<0 || amplitudeCount<0)throw new IllegalStateException("NATIVE_OCTAVE_SHAPE");
        Object[] identities=octaveSamplers.clone();var children=new SamplerStateSeal[count];
        var bytes=ByteBuffer.allocate(36+8*amplitudeCount+285*count);
        bytes.putInt(firstOctave).putInt(amplitudeCount);
        for(int i=0;i<amplitudeCount;i++){samplerCheckpoint();bytes.putLong(samplerBits(amplitudes.getDouble(i)));}
        bytes.putLong(samplerBits(persistence)).putLong(samplerBits(lacunarity)).putLong(samplerBits(maxValue)).putInt(count);
        for(int i=0;i<count;i++) {
            samplerCheckpoint();Object child=identities[i];bytes.put((byte)(child==null?0:1));
            if(child!=null) {
                samplerType(child,PerlinNoiseSampler.class);
                if(!(child instanceof PerlinStateAccess access))throw new IllegalStateException("NATIVE_SAMPLER_BRIDGE_MISSING");
                children[i]=access.quantumchamber$capturePerlinState();bytes.put(children[i].bytes());
            }
        }
        return SamplerStateSeal.capture(this,octaveSamplers,identities,children,java.util.Arrays.copyOf(bytes.array(),bytes.position()));
    }
}
