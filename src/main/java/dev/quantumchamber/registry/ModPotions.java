package dev.quantumchamber.registry;

import net.fabricmc.fabric.api.registry.FabricBrewingRecipeRegistryBuilder;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.potion.Potion;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

public final class ModPotions {
    public static final RegistryEntry.Reference<Potion> QUANTUM_STATE = Registry.registerReference(
            Registries.POTION,
            Identifier.of("quantumchamber", "quantum_state"),
            new Potion("quantum_state", new StatusEffectInstance(ModEffects.QUANTUM_STATE, 3600, 0)));

    private ModPotions() {
    }

    public static void register() {
        // Class loading performs the registration above.
    }

    public static void registerBrewingRecipe() {
        FabricBrewingRecipeRegistryBuilder.BUILD.register(builder -> builder.registerPotionRecipe(
                Potions.AWKWARD,
                Ingredient.ofItems(Items.ECHO_SHARD),
                QUANTUM_STATE));
    }
}
