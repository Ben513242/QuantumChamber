package dev.quantumchamber.registry;

import dev.quantumchamber.chamber.QuantumStateStatusEffect;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;

public final class ModEffects {
    public static final RegistryEntry.Reference<StatusEffect> QUANTUM_STATE = Registry.registerReference(
            Registries.STATUS_EFFECT,
            Identifier.of("quantumchamber", "quantum_state"),
            new QuantumStateStatusEffect());

    private ModEffects() {
    }

    public static void register() {
        // Class loading performs the registration above.
    }
}
