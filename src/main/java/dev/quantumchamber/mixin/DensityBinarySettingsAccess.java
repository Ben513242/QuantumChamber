package dev.quantumchamber.mixin;

import net.minecraft.world.gen.densityfunction.DensityFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 僅供同步End路徑核對；不得將live引數存入graph或結果DTO。 */
@Mixin(targets="net.minecraft.world.gen.densityfunction.DensityFunctionTypes$BinaryOperation")
public interface DensityBinarySettingsAccess {
    @Accessor("argument1") DensityFunction quantumchamber$argument1();
    @Accessor("argument2") DensityFunction quantumchamber$argument2();
}
