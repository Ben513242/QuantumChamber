package dev.quantumchamber.gametest.mixin;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
/** 只讀目前模板來源，不持有額外所有權。 */
@Mixin(StructureTemplateManager.class)
public interface M6SettingsTraceTemplateAccessor {
    @Accessor("resourceManager") ResourceManager quantumchamber$traceResourceManager();
}
