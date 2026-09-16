package dev.quantumchamber;

import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.compat.CompatibilityManager;
import dev.quantumchamber.compat.RuntimeCompatibility;
import dev.quantumchamber.registry.ModBlockEntities;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.registry.ModItems;
import dev.quantumchamber.registry.ModPotions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantumSuperpositionMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    @Override
    public void onInitialize() {
        ModBlocks.register();
        ModItems.register();
        ModBlockEntities.register();
        ModEffects.register();
        ModPotions.register();
        ModPotions.registerBrewingRecipe();
        ChamberProtectionService.initialize();
        RuntimeCompatibility compatibility = CompatibilityManager.detect(
                FabricLoader.getInstance()::isModLoaded);
        LOGGER.info("Detected optional mod compatibility: sodium={}, iris={}, immersive_portals={}",
                compatibility.sodiumLoaded(),
                compatibility.irisLoaded(),
                compatibility.immersivePortalsLoaded());
    }
}
