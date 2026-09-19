package dev.quantumchamber.universe.minecraft121;

import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.util.List;
import java.util.Objects;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.level.UnmodifiableLevelProperties;

/** 固定 1.21 的共用存檔 seed／generator profile；不承諾獨立時間或亂數序列。 */
public final class Minecraft121ServerWorldFactory {
    ServerWorld create(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        var access = (MinecraftServerDynamicWorldAccess) server;
        var overworld = Objects.requireNonNull(server.getOverworld(), "Overworld 尚未建立");
        var properties = server.getSaveProperties();
        var options = Objects.requireNonNull(server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(RegistryKeys.DIMENSION).get(DimensionOptions.OVERWORLD),
                "同 server registry 缺少 Overworld DimensionOptions");
        return new ServerWorld(server, access.quantumchamber$getWorkerExecutor(),
                access.quantumchamber$getSession(),
                new UnmodifiableLevelProperties(properties, properties.getMainWorldProperties()),
                descriptor.worldKey(), options, WorldGenerationProgressLogger.noSpawnChunks(),
                properties.isDebugWorld(), BiomeAccess.hashSeed(properties.getGeneratorOptions().getSeed()),
                List.of(), false, overworld.getRandomSequences());
    }
}
