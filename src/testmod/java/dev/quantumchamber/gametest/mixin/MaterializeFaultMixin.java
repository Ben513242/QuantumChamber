package dev.quantumchamber.gametest.mixin;

import dev.quantumchamber.gametest.M5TestHooks;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 僅 testmod（M5 F4a）：物化失敗的注入。{@code UniverseLifecycleService.materializeAllocated} 經 lifecycle 的 backend 物化時，這裡在
 * {@code Minecraft121DynamicDimensionBackend.materialize} 的開頭讀 {@link M5TestHooks#beforeMaterialize}：注入時直接回 backend 的
 * {@code FAILED_ROLLED_BACK}（安全撤回：沒有建立 world，也沒有 runtime owner），不呼叫原本的物化。hook 是 {@code M5TestHooks.Slot}，
 * 由 GameTest 的 {@code M5GameTestSupport.installMaterializeFault}，或 M5 collapse probe 的 {@code M5CollapseRun.install}（F6-2a 起；F6-2c Step 0 更正）安裝。testmod 的 mixin 設定沒有 defaultRequire，所以明寫 {@code require=1}。
 */
@Mixin(value=Minecraft121DynamicDimensionBackend.class,remap=false)
abstract class MaterializeFaultMixin {
    @Inject(method="materialize(Lnet/minecraft/server/MinecraftServer;Ldev/quantumchamber/universe/UniverseWorldDescriptor;)Ldev/quantumchamber/universe/MaterializeResult;",
            at=@At("HEAD"),cancellable=true,remap=false,require=1)
    private void quantumchamberTest$fault(MinecraftServer server,UniverseWorldDescriptor descriptor,CallbackInfoReturnable<MaterializeResult> callback) {
        var injected=M5TestHooks.beforeMaterialize(server,descriptor);
        if(injected!=null) callback.setReturnValue(injected);
    }
}
