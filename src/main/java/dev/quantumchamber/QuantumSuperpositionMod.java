package dev.quantumchamber;

import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.compat.CompatibilityManager;
import dev.quantumchamber.compat.RuntimeCompatibility;
import dev.quantumchamber.registry.ModBlockEntities;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.registry.ModItems;
import dev.quantumchamber.registry.ModPotions;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerBlockEntityEvents;
import net.minecraft.server.ServerTask;
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
        ServerBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, world) -> {
            if (blockEntity instanceof ChamberControllerBlockEntity controller) {
                // BLOCK_ENTITY_LOAD 可能在 chunk 尚未完成 FULL 載入時觸發；禁止在此同步查回該 chunk。
                // send 明確入列；execute 在 server thread 可能立即執行而重入 chunk 載入。
                var server = world.getServer();
                server.send(new ServerTask(server.getTicks(),
                        () -> ChamberControllerBlock.onControllerLoaded(world, controller.getPos())));
            }
        });
        RuntimeCompatibility compatibility = CompatibilityManager.detect(
                FabricLoader.getInstance()::isModLoaded);
        LOGGER.info("Detected optional mod compatibility: sodium={}, iris={}, immersive_portals={}",
                compatibility.sodiumLoaded(),
                compatibility.irisLoaded(),
                compatibility.immersivePortalsLoaded());
    }
}
