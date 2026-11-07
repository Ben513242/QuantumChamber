package dev.quantumchamber.gametest.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.quantumchamber.gametest.M6RandomProbePhases.OreWitness;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.ChunkSectionCache;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.gen.feature.OreFeature;
import net.minecraft.world.gen.feature.OreFeatureConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** 固定 1.21 三處原呼叫的 testmod 見證；參數、回傳、次數及原 Throwable 不變。 */
@Mixin(OreFeature.class)
abstract class M6PartialOreWriterWitnessMixin {
    @WrapOperation(method="generate(Lnet/minecraft/world/gen/feature/util/FeatureContext;)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/gen/feature/OreFeature;generateVeinPart(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/world/gen/feature/OreFeatureConfig;DDDDDDIIIII)Z"),require=1,expect=1,allow=1)
    private boolean quantumchamber$vein(OreFeature receiver,StructureWorldAccess world,Random random,OreFeatureConfig config,
            double a,double b,double c,double d,double e,double f,int g,int h,int i,int j,int k,Operation<Boolean> original) {
        return OreWitness.vein(world,() -> original.call(receiver,world,random,config,a,b,c,d,e,f,g,h,i,j,k));
    }
    @WrapOperation(method="generateVeinPart(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/world/gen/feature/OreFeatureConfig;DDDDDDIIIII)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/ChunkSectionCache;getSection(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/world/chunk/ChunkSection;"),require=1,expect=1,allow=1)
    private ChunkSection quantumchamber$section(ChunkSectionCache receiver,BlockPos pos,Operation<ChunkSection> original) {
        return OreWitness.section(pos,() -> original.call(receiver,pos));
    }
    @WrapOperation(method="generateVeinPart(Lnet/minecraft/world/StructureWorldAccess;Lnet/minecraft/util/math/random/Random;Lnet/minecraft/world/gen/feature/OreFeatureConfig;DDDDDDIIIII)Z",
            at=@At(value="INVOKE",target="Lnet/minecraft/world/chunk/ChunkSection;setBlockState(IIILnet/minecraft/block/BlockState;Z)Lnet/minecraft/block/BlockState;"),require=1,expect=1,allow=1)
    private BlockState quantumchamber$write(ChunkSection receiver,int x,int y,int z,BlockState state,boolean lock,Operation<BlockState> original) {
        return OreWitness.write(receiver,x,y,z,state,() -> original.call(receiver,x,y,z,state,lock));
    }
}
