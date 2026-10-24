package dev.quantumchamber.gametest.mixin;

import java.util.Collection;
import net.fabricmc.fabric.impl.gametest.FabricGameTestHelper;
import net.minecraft.test.TestFunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod：依 run 選擇 batch，四個 run 各用獨立的新 save。
 * legacy run（legacyOnly）只跑 m4_legacy_runtime；M5 run（m5Only，plan 決策 6）只跑 batch id 前綴 m5_universe 的 test；
 * M6 run（m6Only）只跑 m6_universe；default run 排除這三者，其餘（含 m5_collapse_、m5_f3a_）全部執行。
 */
@Mixin(value=FabricGameTestHelper.class,remap=false)
abstract class M4LegacyGameTestFilterMixin {
    @Inject(method="getTestFunctions",at=@At("RETURN"),cancellable=true,remap=false)
    private static void quantumchamberTest$isolatedLegacy(CallbackInfoReturnable<Collection<TestFunction>> callback) {
        boolean legacy=Boolean.getBoolean("quantumchamber.gametest.legacyOnly");
        boolean m5=Boolean.getBoolean("quantumchamber.gametest.m5Only");
        boolean m6=Boolean.getBoolean("quantumchamber.gametest.m6Only");
        if((legacy ? 1 : 0)+(m5 ? 1 : 0)+(m6 ? 1 : 0)>1) throw new IllegalStateException("M6_SCOPE_MISMATCH：scope flags 不可混用");
        var selected=callback.getReturnValue().stream().filter(test -> {
            boolean legacyBatch=test.batchId().equals("m4_legacy_runtime");
            boolean m5Batch=test.batchId().startsWith("m5_universe");
            boolean m6Batch=test.batchId().startsWith("m6_universe");
            return legacy ? legacyBatch : m5 ? m5Batch : m6 ? m6Batch : !legacyBatch && !m5Batch && !m6Batch;
        }).toList();
        if(selected.isEmpty() || legacy && selected.size()!=1) throw new IllegalStateException("原生測試隔離範圍不符");
        // 僅 RED 取證用：明確列出的 batch 子集；未設定時完整 suite 不受影響。
        var only=System.getProperty("quantumchamber.gametest.onlyBatches","");
        if(!only.isBlank()) {
            var batches=java.util.Set.of(only.split(","));
            selected=selected.stream().filter(test -> batches.contains(test.batchId())).toList();
            if(!selected.stream().map(TestFunction::batchId).collect(java.util.stream.Collectors.toSet()).equals(batches)) throw new IllegalStateException("指定 batch 子集不符："+batches);
        }
        callback.setReturnValue(selected);
    }
}
