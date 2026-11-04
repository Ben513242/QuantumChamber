package dev.quantumchamber.universe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;

/** 每次由原版 pack 真載入一份獨立 worldgen registry。 */
final class Minecraft121WorldgenRegistryFixture implements AutoCloseable {
    private final LifecycledResourceManagerImpl resources;
    private final DynamicRegistryManager.Immutable registries;
    private final Path overlay;

    private Minecraft121WorldgenRegistryFixture(LifecycledResourceManagerImpl resources,
            DynamicRegistryManager.Immutable registries, Path overlay) {
        this.resources = resources;
        this.registries = registries;
        this.overlay = overlay;
    }

    static void bootstrap() {
        net.minecraft.SharedConstants.createGameVersion();
        net.minecraft.Bootstrap.initialize();
        dev.quantumchamber.registry.ModBlocks.register();
    }

    static Minecraft121WorldgenRegistryFixture load() { return load(false); }
    static Minecraft121WorldgenRegistryFixture loadAmplifiedOverride() { return load(true); }

    private static Minecraft121WorldgenRegistryFixture load(boolean override) {
        Path overlay = null;
        LifecycledResourceManagerImpl rm = null;
        try {
            var packs = new ArrayList<net.minecraft.resource.ResourcePack>();
            packs.add(VanillaDataPackProvider.createDefaultPack());
            if (override) {
                overlay = Files.createTempDirectory("qc-amplified-override-");
                Path destination = overlay.resolve("data/minecraft/worldgen/noise_settings/amplified.json");
                Files.createDirectories(destination.getParent());
                try (var vanilla = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA,
                        List.of(VanillaDataPackProvider.createDefaultPack()))) {
                    var resource = vanilla.getResourceOrThrow(Identifier.ofVanilla("worldgen/noise_settings/amplified.json"));
                    String json;
                    try (var stream = resource.getInputStream()) { json = new String(stream.readAllBytes(), StandardCharsets.UTF_8); }
                    String changed = json.replaceFirst("\"sea_level\"\\s*:\\s*[0-9]+", "\"sea_level\": 61");
                    if (changed.equals(json)) throw new IllegalStateException("AMPLIFIED_OVERRIDE_SOURCE_MISSING");
                    Files.writeString(destination, changed, StandardCharsets.UTF_8);
                }
                var info = new ResourcePackInfo("amplified-test", Text.literal("amplified-test"), ResourcePackSource.NONE, java.util.Optional.empty());
                packs.add(new DirectoryResourcePack(info, overlay));
            }
            rm = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA, packs);
            var combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
            var worldgen = RegistryLoader.loadFromResource(rm,
                    combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN),
                    RegistryLoader.DYNAMIC_REGISTRIES);
            return new Minecraft121WorldgenRegistryFixture(rm, worldgen, overlay);
        } catch (Exception | Error error) {
            if (rm != null) rm.close();
            if (overlay != null) deleteOverlay(overlay);
            throw new IllegalStateException("WORLDGEN_FIXTURE_FAILED", error);
        }
    }

    DynamicRegistryManager.Immutable registries() { return registries; }
    int amplifiedSeaLevel() { return registries.get(RegistryKeys.CHUNK_GENERATOR_SETTINGS)
            .getOrThrow(ChunkGeneratorSettings.AMPLIFIED).seaLevel(); }

    @Override public void close() {
        resources.close();
        if (overlay != null) deleteOverlay(overlay);
    }

    private static void deleteOverlay(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.delete(path); } catch (IOException error) { throw new IllegalStateException(error); }
            });
        } catch (IOException error) { throw new IllegalStateException(error); }
    }
}
