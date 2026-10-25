package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.quantumchamber.universe.EndCityInvocationState;
import java.util.List;
import net.minecraft.structure.EndCityGenerator;
import net.minecraft.structure.StructurePiece;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 固定1.21的五參數整輪，先建私有船隻旗標再執行四個Part.init及全部遞迴。
 * 模板可同步讀取資源；此處不新增全域monitor或跨worker等待。
 * 巢狀整輪與例外都由finally恢復父frame，最外層離開時移除ThreadLocal。
 */
@Mixin(EndCityGenerator.class)
abstract class EndCityGeneratorStateMixin {
    @WrapMethod(method="addPieces(Lnet/minecraft/structure/StructureTemplateManager;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/util/BlockRotation;Ljava/util/List;Lnet/minecraft/util/math/random/Random;)V", require=1)
    private static void quantumchamber$isolateInvocation(StructureTemplateManager templates, BlockPos pos,
            BlockRotation rotation, List<StructurePiece> pieces, Random random, Operation<Void> original) {
        EndCityInvocationState.enter();
        try { original.call(templates, pos, rotation, pieces, random); }
        finally { EndCityInvocationState.exit(); }
    }
}
