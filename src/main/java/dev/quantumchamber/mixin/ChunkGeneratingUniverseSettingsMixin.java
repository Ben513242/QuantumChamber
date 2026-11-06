package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory;
import net.minecraft.world.chunk.*;
import net.minecraft.util.collection.BoundedRegionArray;
import net.minecraft.world.gen.GeneratorOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** worker 只讀 world 自有 immutable 投影，不讀 server-thread catalog 或 ThreadLocal。 */
@Mixin(ChunkGenerating.class)
public abstract class ChunkGeneratingUniverseSettingsMixin {
    @WrapOperation(method="generateStructures", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/gen/GeneratorOptions;shouldGenerateStructures()Z"), require=1)
    private static boolean quantumchamber$structures(GeneratorOptions options, Operation<Boolean> original,
            ChunkGenerationContext context, ChunkGenerationStep step,
            BoundedRegionArray<AbstractChunkHolder> chunks, Chunk chunk) {
        return Minecraft121ServerWorldFactory.generateStructures(context.world(), original.call(options));
    }
}
