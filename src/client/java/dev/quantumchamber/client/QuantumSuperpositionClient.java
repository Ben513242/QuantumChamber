package dev.quantumchamber.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantumSuperpositionClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    @Override
    public void onInitializeClient() {
        LOGGER.info("QuantumChamber client initialized");
        LOGGER.info("Optional handheld lighting (lambdynlights) present: {}",
                FabricLoader.getInstance().isModLoaded("lambdynlights"));
    }
}
