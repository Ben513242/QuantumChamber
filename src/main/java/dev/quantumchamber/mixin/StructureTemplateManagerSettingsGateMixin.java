package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.quantumchamber.universe.Minecraft121TemplateAccessGate;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;

/** 原生 manager 六個完整方法共用同一 shared gate，包含例外路徑。 */
@Mixin(StructureTemplateManager.class)
public abstract class StructureTemplateManagerSettingsGateMixin {
    private Minecraft121TemplateAccessGate quantumchamber$settingsGate() {
        return Minecraft121TemplateAccessGate.forManager(this);
    }

    @WrapMethod(method = "getTemplateOrBlank")
    private StructureTemplate quantumchamber$getTemplateOrBlank(Identifier id, Operation<StructureTemplate> original) {
        var gate = quantumchamber$settingsGate();
        return gate.withManagerRead(() -> {
            var template = original.call(id);
            gate.associate(template);
            return template;
        });
    }

    @WrapMethod(method = "getTemplate")
    private Optional<StructureTemplate> quantumchamber$getTemplate(Identifier id, Operation<Optional<StructureTemplate>> original) {
        var gate = quantumchamber$settingsGate();
        return gate.withManagerRead(() -> {
            var result = original.call(id);
            result.ifPresent(gate::associate);
            return result;
        });
    }

    @WrapMethod(method = "setResourceManager")
    private void quantumchamber$setResourceManager(ResourceManager manager, Operation<Void> original) {
        quantumchamber$settingsGate().withManagerRead(() -> { original.call(manager); return null; });
    }

    @WrapMethod(method = "createTemplate")
    private StructureTemplate quantumchamber$createTemplate(NbtCompound nbt, Operation<StructureTemplate> original) {
        var gate = quantumchamber$settingsGate();
        return gate.withManagerRead(() -> {
            var template = original.call(nbt);
            gate.associate(template);
            return template;
        });
    }

    @WrapMethod(method = "saveTemplate")
    private boolean quantumchamber$saveTemplate(Identifier id, Operation<Boolean> original) {
        return quantumchamber$settingsGate().withManagerRead(() -> original.call(id));
    }

    @WrapMethod(method = "unloadTemplate")
    private void quantumchamber$unloadTemplate(Identifier id, Operation<Void> original) {
        quantumchamber$settingsGate().withManagerRead(() -> { original.call(id); return null; });
    }
}
