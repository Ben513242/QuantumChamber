package dev.quantumchamber.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.quantumchamber.universe.Minecraft121TemplateAccessGate;
import net.minecraft.block.Block;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryEntryLookup;
import net.minecraft.structure.StructureTemplate;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;

/** 已關聯 live template 的三個資料 mutator 受原 manager shared gate 保護。 */
@Mixin(StructureTemplate.class)
public abstract class StructureTemplateSettingsGateMixin {
    private StructureTemplate quantumchamber$self() { return (StructureTemplate) (Object) this; }

    @WrapMethod(method = "saveFromWorld")
    private void quantumchamber$saveFromWorld(World world, BlockPos start, Vec3i size, boolean includeEntities,
            Block ignored, Operation<Void> original) {
        Minecraft121TemplateAccessGate.withTemplateRead(quantumchamber$self(), () -> {
            original.call(world, start, size, includeEntities, ignored);
            return null;
        });
    }

    @WrapMethod(method = "readNbt")
    private void quantumchamber$readNbt(RegistryEntryLookup<?> lookup, NbtCompound nbt, Operation<Void> original) {
        Minecraft121TemplateAccessGate.withTemplateRead(quantumchamber$self(), () -> {
            original.call(lookup, nbt);
            return null;
        });
    }

    @WrapMethod(method = "setAuthor")
    private void quantumchamber$setAuthor(String author, Operation<Void> original) {
        Minecraft121TemplateAccessGate.withTemplateRead(quantumchamber$self(), () -> {
            original.call(author);
            return null;
        });
    }
}
