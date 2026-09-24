package dev.quantumchamber.gametest.mixin;

import java.util.Collection;
import net.fabricmc.fabric.impl.gametest.FabricGameTestHelper;
import net.minecraft.test.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 僅 testmod：legacy 用獨立新 save；正常完整 run 只排除此一個專用 batch。 */
@Mixin(value=FabricGameTestHelper.class,remap=false)
abstract class M4LegacyGameTestFilterMixin {
    @Inject(method="getTestFunctions",at=@At("RETURN"),cancellable=true,remap=false)
    private static void quantumchamberTest$isolatedLegacy(CallbackInfoReturnable<Collection<TestFunction>> callback) {
        boolean legacy=Boolean.getBoolean("quantumchamber.gametest.legacyOnly");
        var selected=callback.getReturnValue().stream().filter(test -> test.batchId().equals("m4_legacy_runtime")==legacy).toList();
        if(selected.isEmpty() || legacy && selected.size()!=1) throw new IllegalStateException("原生測試隔離範圍不符");
        // 僅 RED 取證用：明確列出的 batch 子集；未設定時完整 suite 不受影響。
        var only=System.getProperty("quantumchamber.gametest.onlyBatches","");
        if(!only.isBlank()) {
            var batches=java.util.Set.of(only.split(","));
            selected=selected.stream().filter(test -> batches.contains(test.batchId())).toList();
            if(selected.size()!=batches.size()) throw new IllegalStateException("指定 batch 子集不符："+batches);
        }
        callback.setReturnValue(selected);
    }
}
