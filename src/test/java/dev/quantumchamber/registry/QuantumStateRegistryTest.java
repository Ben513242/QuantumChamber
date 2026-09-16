package dev.quantumchamber.registry;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class QuantumStateRegistryTest {
    @BeforeAll
    static void initializeRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ModEffects.register();
        ModPotions.register();
    }

    @Test
    void registersQuantumStateEffectAndPotionWithExpectedBaseline() {
        assertEquals(Identifier.of("quantumchamber", "quantum_state"),
                Registries.STATUS_EFFECT.getId(ModEffects.QUANTUM_STATE.value()));
        assertEquals(Identifier.of("quantumchamber", "quantum_state"),
                Registries.POTION.getId(ModPotions.QUANTUM_STATE.value()));

        StatusEffectInstance effect = ModPotions.QUANTUM_STATE.value().getEffects().getFirst();
        assertEquals(ModEffects.QUANTUM_STATE, effect.getEffectType());
        assertEquals(3600, effect.getDuration());
        assertEquals(0, effect.getAmplifier());
    }
}
