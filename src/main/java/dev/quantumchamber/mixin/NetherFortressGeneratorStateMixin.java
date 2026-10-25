package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.world.gen.structure.NetherFortressStructure;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 固定原版Fortress的整輪生成使用獨立monitor，涵蓋Start重設、所有piece擴張與shift。
 * Start的兩組清單仍引用static PieceData；整輪互斥才能隔離兩組generatedCount。
 * 原版整輪只操作random、座標與collector，不等待chunk、server executor或future；
 * piece層遞迴不回到整輪入口。例外由JVM釋放monitor，沒有ThreadLocal殘留。
 * 此保證限已核原版caller；不宣稱第三方collector或hook的巢狀整輪相容性。
 */
@Mixin(NetherFortressStructure.class)
abstract class NetherFortressGeneratorStateMixin {
    @Unique private static final Object quantumchamber$invocationLock = new Object();

    @WrapMethod(method="addPieces(Lnet/minecraft/structure/StructurePiecesCollector;Lnet/minecraft/world/gen/structure/Structure$Context;)V", require=1)
    private static void quantumchamber$isolateInvocation(StructurePiecesCollector collector, Structure.Context context, Operation<Void> original) {
        synchronized (quantumchamber$invocationLock) {
            original.call(collector, context);
        }
    }
}
