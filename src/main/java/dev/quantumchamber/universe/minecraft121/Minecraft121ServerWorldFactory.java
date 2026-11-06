package dev.quantumchamber.universe.minecraft121;

import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import dev.quantumchamber.universe.*;
import java.util.OptionalLong;
import net.minecraft.world.gen.GeneratorOptions;
import java.util.List;
import java.util.Objects;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.WorldGenerationProgressLogger;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.dimension.DimensionOptions;
import net.minecraft.world.level.UnmodifiableLevelProperties;

/** 固定 1.21：schema2 由 checked catalog 投影；schema1 保留原版共用行為。 */
public final class Minecraft121ServerWorldFactory {
    private static final ThreadLocal<GenerationOwner> CONSTRUCTION = new ThreadLocal<>();

    /** owner 存在於原生世界本身，不以 worker ThreadLocal 或額外全域 map 保存。 */
    public interface WorldGenerationOwnerAccess {
        GenerationOwner quantumchamber$getGenerationOwner();
        void quantumchamber$setGenerationOwner(GenerationOwner owner);
    }

    public static OptionalLong rootSeed(ServerWorld world) {
        var owner = exactOwner(world);
        return owner == null ? OptionalLong.empty() : OptionalLong.of(owner.seed);
    }

    public static boolean generateStructures(ServerWorld world, boolean vanilla) {
        var owner = exactOwner(world);
        return owner == null ? vanilla : owner.structures;
    }

    public static GeneratorOptions constructorOptions(ServerWorld world, GeneratorOptions vanilla) {
        var owner = exactOwner(world);
        return owner == null ? vanilla : vanilla.withSeed(OptionalLong.of(owner.seed)).withStructures(owner.structures);
    }

    private static GenerationOwner exactOwner(ServerWorld world) {
        if (!(world instanceof WorldGenerationOwnerAccess access)) return null;
        var owner = access.quantumchamber$getGenerationOwner();
        var constructing = CONSTRUCTION.get();
        if (owner == null && constructing != null && constructing.server == world.getServer()
                && constructing.descriptor.worldKey().equals(world.getRegistryKey())) {
            constructing.bind(world.getServer(), constructing.descriptor, world);
            access.quantumchamber$setGenerationOwner(constructing);
            owner = constructing;
        }
        if (owner == null || !owner.matches(world.getServer(), owner.descriptor, world)
                || !owner.descriptor.worldKey().equals(world.getRegistryKey())) return null;
        // 建構窗口只屬原 owning thread；其餘必須已發布成 exact native live instance。
        return constructing == owner || world.getServer().getWorld(world.getRegistryKey()) == world ? owner : null;
    }

    public static boolean constructionPending() { return CONSTRUCTION.get() != null; }

    /** exact 世界的生成投影；決策層測試與原生接線共用此 owner。 */
    public static final class GenerationOwner {
        private final Object server;
        private final UniverseWorldDescriptor descriptor;
        private final long seed;
        private final boolean structures;
        private volatile Object world;

        public GenerationOwner(Object server, UniverseWorldDescriptor descriptor, long seed, boolean structures) {
            this.server = Objects.requireNonNull(server, "server");
            this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
            this.seed = seed;
            this.structures = structures;
        }

        public void bind(Object server, UniverseWorldDescriptor descriptor, Object world) {
            if (this.server != server || !this.descriptor.equals(descriptor) || world == null
                    || (this.world != null && this.world != world)) {
                throw new IllegalStateException("生成投影的 exact owner 不符");
            }
            this.world = world;
        }

        public long rootSeed(Object server, UniverseWorldDescriptor descriptor, Object world, long vanilla) {
            return matches(server, descriptor, world) ? seed : vanilla;
        }

        public boolean generateStructures(Object server, UniverseWorldDescriptor descriptor, Object world, boolean vanilla) {
            return matches(server, descriptor, world) ? structures : vanilla;
        }

        public boolean independentSequences() {
            return descriptor.seedPolicy() == dev.quantumchamber.universe.SeedPolicy.EXPLICIT_ROOT_SEED_V1;
        }

        private boolean matches(Object server, UniverseWorldDescriptor descriptor, Object world) {
            return independentSequences() && this.server == server && this.descriptor.equals(descriptor)
                    && this.world != null && this.world == world;
        }
    }

    ServerWorld create(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        var access = (MinecraftServerDynamicWorldAccess) server;
        var overworld = Objects.requireNonNull(server.getOverworld(), "Overworld 尚未建立");
        var properties = server.getSaveProperties();
        var options = Objects.requireNonNull(server.getCombinedDynamicRegistries()
                .getCombinedRegistryManager().get(RegistryKeys.DIMENSION).get(DimensionOptions.OVERWORLD),
                "同 server registry 缺少 Overworld DimensionOptions");
        GenerationOwner owner = null;
        if (descriptor.seedPolicy() == SeedPolicy.EXPLICIT_ROOT_SEED_V1) {
            var catalog = UniverseRegistryState.get(server);
            catalog.requireHealthy();
            var row = catalog.flushedRecords().values().stream()
                    .filter(record -> descriptor.equals(record.definition().worlds().get(descriptor.role())))
                    .findFirst().orElseThrow(() -> new IllegalStateException("缺少 checked schema2 owner"));
            if (row.definition().schemaVersion() != 2 || row.generation() == null
                    || row.desiredAvailability() != DesiredAvailability.EAGER_ENABLED
                    || !row.equals(catalog.records().get(row.definition().universeId()))) {
                throw new IllegalStateException("schema2 生成權威不符");
            }
            var resolved = Minecraft121SettingsLifecycle.registry().resolve(
                    GenerationSettingsSnapshot.fromNbt(row.generation().targetSettingsSnapshot()),
                    Minecraft121SettingsLifecycle.acquire(server));
            options = Objects.requireNonNull(resolved.roles().get(descriptor.role()), "provider 缺少目標 role");
            owner = new GenerationOwner(server, descriptor, row.generation().rootWorldSeed(), resolved.generateStructures());
        }
        final var capturedOwner = owner;
        final var capturedOptions = options;
        return withConstruction(owner, () -> {
            var activeOwner = capturedOwner;
            var created = new ServerWorld(server, access.quantumchamber$getWorkerExecutor(),
                    access.quantumchamber$getSession(),
                    new UnmodifiableLevelProperties(properties, properties.getMainWorldProperties()),
                    descriptor.worldKey(), capturedOptions, WorldGenerationProgressLogger.noSpawnChunks(),
                    properties.isDebugWorld(), BiomeAccess.hashSeed(activeOwner == null
                            ? properties.getGeneratorOptions().getSeed() : activeOwner.seed),
                    List.of(), false, activeOwner == null ? overworld.getRandomSequences() : null);
            if (activeOwner != null && ((WorldGenerationOwnerAccess) created).quantumchamber$getGenerationOwner() != activeOwner) {
                throw new IllegalStateException("原生世界未接上建構 owner");
            }
            return created;
        });
    }

    private static <T> T withConstruction(GenerationOwner owner, java.util.function.Supplier<T> constructor) {
        if (CONSTRUCTION.get() != null) throw new IllegalStateException("不允許重入世界建構");
        if (owner != null) CONSTRUCTION.set(owner);
        try {
            return constructor.get();
        } finally {
            CONSTRUCTION.remove();
        }
    }
}
