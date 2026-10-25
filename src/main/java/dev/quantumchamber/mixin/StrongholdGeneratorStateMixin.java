package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.structure.StructurePiecesCollector;
import net.minecraft.world.gen.structure.StrongholdStructure;
import net.minecraft.world.gen.structure.Structure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 原版要塞的整輪生成共用同一個 monitor，涵蓋 init、piece 擴張與 PortalRoom 重試。
 * possiblePieces、activePieceType、totalWeight 及 ALL_PIECES 元素的 generatedCount 因而不會跨 invocation 交錯。
 * 原版只有 piece 層遞迴，沒有整輪 addPieces 再入；monitor 本身可重入，例外由 JVM 保證釋放。
 * 固定 1.21 的原版生成路徑只計算本輪 pieces 與原版 generator 高度，不在持鎖時等待 chunk、server executor 或 future。
 * 此結論限已核原版 caller／generator；第三方 collector 或 hook 啟動巢狀整輪不在本修正的相容性保證內。
 */
@Mixin(StrongholdStructure.class)
abstract class StrongholdGeneratorStateMixin {
    @Unique private static final Object quantumchamber$invocationLock = new Object();

    @WrapMethod(method="addPieces(Lnet/minecraft/structure/StructurePiecesCollector;Lnet/minecraft/world/gen/structure/Structure$Context;)V", require=1)
    private static void quantumchamber$isolateInvocation(StructurePiecesCollector collector, Structure.Context context, Operation<Void> original) {
        synchronized (quantumchamber$invocationLock) {
            original.call(collector, context);
        }
    }
}
