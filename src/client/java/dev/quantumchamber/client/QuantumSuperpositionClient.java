package dev.quantumchamber.client;

import dev.quantumchamber.registry.ModBlocks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.RenderLayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class QuantumSuperpositionClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    @Override
    public void onInitializeClient() {
        // 量子膜（M5 spec §10.1）：置中、雙面、不透明的薄平面，用 cutout 類的 layer。render layer 以方塊為單位，關門的模型也在這一層；
        // 用 cutout mipped 保留關門遠景的 mipmap（cutout 不做 mipmap），貼圖都不透明，不會因 alpha 被裁切。
        BlockRenderLayerMap.INSTANCE.putBlock(ModBlocks.QUANTUM_BULKHEAD, RenderLayer.getCutoutMipped());
        LOGGER.info("QuantumChamber client initialized");
        LOGGER.info("Optional handheld lighting (lambdynlights) present: {}",
                FabricLoader.getInstance().isModLoaded("lambdynlights"));
    }
}
