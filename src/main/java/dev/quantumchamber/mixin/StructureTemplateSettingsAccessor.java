package dev.quantumchamber.mixin;

import java.util.List;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.Vec3i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** 原生 template 形狀的只讀入口；呼叫者必持有效 gate lease。 */
@Mixin(StructureTemplate.class)
public interface StructureTemplateSettingsAccessor {
    @Accessor("blockInfoLists") List<?> quantumchamber$getSettingsBlockInfoLists();
    @Accessor("entities") List<?> quantumchamber$getSettingsEntities();
    @Accessor("size") Vec3i quantumchamber$getSettingsSize();
    @Accessor("author") String quantumchamber$getSettingsAuthor();
}
