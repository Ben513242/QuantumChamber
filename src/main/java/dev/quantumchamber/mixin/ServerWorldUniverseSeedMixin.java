package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory;
import dev.quantumchamber.universe.minecraft121.Minecraft121ServerWorldFactory.GenerationOwner;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.SaveProperties;
import net.minecraft.world.gen.GeneratorOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 對 exact schema2 世界投影 frozen seed／設定；不改全域 options。 */
@Mixin(ServerWorld.class)
public abstract class ServerWorldUniverseSeedMixin implements Minecraft121ServerWorldFactory.WorldGenerationOwnerAccess {
    @Unique private volatile GenerationOwner quantumchamber$generationOwner;
    @Override public GenerationOwner quantumchamber$getGenerationOwner() { return quantumchamber$generationOwner; }
    @Override public void quantumchamber$setGenerationOwner(GenerationOwner owner) {
        if (quantumchamber$generationOwner != null) throw new IllegalStateException("生成 owner 不可覆寫");
        quantumchamber$generationOwner = owner;
    }

    @Inject(method="getSeed()J", at=@At("HEAD"), cancellable=true, require=1)
    private void quantumchamber$rootSeed(CallbackInfoReturnable<Long> callback) {
        var seed = Minecraft121ServerWorldFactory.rootSeed((ServerWorld)(Object)this);
        if (seed.isPresent()) callback.setReturnValue(seed.getAsLong());
    }

    @WrapOperation(method="<init>", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/SaveProperties;getGeneratorOptions()Lnet/minecraft/world/gen/GeneratorOptions;"), require=2)
    private GeneratorOptions quantumchamber$constructionOptions(SaveProperties properties, Operation<GeneratorOptions> original) {
        return Minecraft121ServerWorldFactory.constructorOptions((ServerWorld)(Object)this, original.call(properties));
    }

    @WrapOperation(method="locateStructure(Lnet/minecraft/registry/tag/TagKey;Lnet/minecraft/util/math/BlockPos;IZ)Lnet/minecraft/util/math/BlockPos;",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/gen/GeneratorOptions;shouldGenerateStructures()Z"), require=1)
    private boolean quantumchamber$locateStructures(GeneratorOptions options, Operation<Boolean> original) {
        return Minecraft121ServerWorldFactory.generateStructures((ServerWorld)(Object)this, original.call(options));
    }

}
