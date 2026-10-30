package dev.quantumchamber.gametest.mixin;

import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.server.DataPackContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 僅本次 trace 讀取原生 holder，不能作正式 context API。 */
@Mixin(targets = "net.minecraft.server.MinecraftServer$ResourceManagerHolder")
public interface M6SettingsTraceHolderAccessor {
    @Accessor("resourceManager") LifecycledResourceManager quantumchamber$traceResourceManager();
    @Accessor("dataPackContents") DataPackContents quantumchamber$traceContents();
}
