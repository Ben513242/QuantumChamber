package dev.quantumchamber.mixin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原生 manager 的有限只讀欄位入口；呼叫者必先持有效 exclusive lease。 */
@Mixin(StructureTemplateManager.class)
public interface StructureTemplateManagerSettingsAccessor {
    @Accessor("templates") Map<Identifier, Optional<StructureTemplate>> quantumchamber$getSettingsTemplates();
    @Accessor("resourceManager") ResourceManager quantumchamber$getSettingsResourceManager();
    @Accessor("generatedPath") Path quantumchamber$getSettingsGeneratedPath();
    @Accessor("blockLookup") RegistryEntryLookup<?> quantumchamber$getSettingsBlockLookup();
}
