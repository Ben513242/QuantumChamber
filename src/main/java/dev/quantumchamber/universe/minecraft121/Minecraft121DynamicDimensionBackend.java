package dev.quantumchamber.universe.minecraft121;

import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.DynamicDimensionBackend;
import dev.quantumchamber.universe.DynamicWorldRuntimeState;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.SeedPolicy;
import dev.quantumchamber.universe.UniverseRuntimeRegistry;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import dev.quantumchamber.universe.UnloadResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.border.WorldBorderListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 由 lifecycle owner 在 server thread 的安全邊界呼叫；不讀取 catalog。 */
public final class Minecraft121DynamicDimensionBackend implements DynamicDimensionBackend {
    private static final Logger LOGGER = LoggerFactory.getLogger(Minecraft121DynamicDimensionBackend.class);
    private final UniverseRuntimeRegistry<MinecraftServer, ServerWorld> runtime = new UniverseRuntimeRegistry<>();
    private final Set<MinecraftServer> unhealthy = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<ServerWorld, BorderBinding> borders = new IdentityHashMap<>();
    private final Minecraft121ServerWorldFactory factory = new Minecraft121ServerWorldFactory();

    @Override
    public MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        requireThread(server);
        Objects.requireNonNull(descriptor, "descriptor");
        if (unhealthy.contains(server)) {
            return result(MaterializeResult.Status.FAILED_UNHEALTHY);
        }
        var access = (MinecraftServerDynamicWorldAccess) server;
        var worlds = access.quantumchamber$getWorlds();
        Path storage;
        try {
            requireVersion(FabricLoader.getInstance().getModContainer("minecraft").orElseThrow()
                    .getMetadata().getVersion().getFriendlyString());
            requireDescriptor(descriptor);
            var active = resolveActive(server, descriptor);
            if (active.isPresent()) {
                return new MaterializeResult(MaterializeResult.Status.ALREADY_ACTIVE, active.get());
            }
            if (runtime.state(server, descriptor) != DynamicWorldRuntimeState.ABSENT) {
                throw new IllegalStateException("此 key 已有 runtime owner 或失敗紀錄");
            }
            requireMapIdentity(worlds.get(descriptor.worldKey()), null);
            storage = requireStorage(access.quantumchamber$getSession().getWorldDirectory(World.OVERWORLD),
                    access.quantumchamber$getSession().getWorldDirectory(descriptor.worldKey()), descriptor);
        } catch (IOException | RuntimeException failure) {
            LOGGER.error("拒絕物化 {}：guard 未通過", descriptor.worldKey().getValue(), failure);
            return result(MaterializeResult.Status.REJECTED);
        }

        runtime.beginMaterialize(server, descriptor);
        ServerWorld created = null;
        boolean published = false;
        boolean loadStarted = false;
        try {
            created = factory.create(server, descriptor);
            var sourceBorder = server.getOverworld().getWorldBorder();
            var listener = new WorldBorderListener.WorldBorderSyncer(created.getWorldBorder());
            borders.put(created, new BorderBinding(sourceBorder, listener));
            created.getWorldBorder().load(sourceBorder.write());
            sourceBorder.addListener(listener);
            requireMapIdentity(worlds.putIfAbsent(descriptor.worldKey(), created), null);
            published = true;
            requireWorldIdentity(server, descriptor, created);
            Path actualStorage = requireStorage(
                    access.quantumchamber$getSession().getWorldDirectory(World.OVERWORLD),
                    access.quantumchamber$getSession().getWorldDirectory(created.getRegistryKey()), descriptor);
            if (!storage.equals(actualStorage)) {
                throw new IllegalStateException("物化後的 storage identity 改變");
            }
            loadStarted = true;
            ServerWorldEvents.LOAD.invoker().onWorldLoad(server, created);
            requireWorldIdentity(server, descriptor, created);
            runtime.activate(server, descriptor, created);
            return new MaterializeResult(MaterializeResult.Status.MATERIALIZED, created);
        } catch (Throwable failure) {
            runtime.fail(server, descriptor, null);
            // LOAD 開始後 observer 可能已有票據／工作；Gate B 前交由正常 shutdown 保存與關閉。
            if (loadStarted || created == null) {
                return failUnhealthy(server, descriptor, failure);
            }
            try {
                rollbackUnpublished(worlds, descriptor, created, published);
                LOGGER.error("物化 {} 失敗，已撤回未公開給 LOAD 的 world", descriptor.worldKey().getValue(), failure);
                return result(MaterializeResult.Status.FAILED_ROLLED_BACK);
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                return failUnhealthy(server, descriptor, failure);
            }
        }
    }

    @Override
    public UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld expectedWorld) {
        requireThread(server);
        return UnloadResult.UNLOAD_UNSUPPORTED;
    }

    @Override
    public Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        requireThread(server);
        Objects.requireNonNull(descriptor, "descriptor");
        if (unhealthy.contains(server)) {
            return Optional.empty();
        }
        return runtime.resolveActive(server, descriptor).filter(world -> world.getServer() == server
                && world.getRegistryKey().equals(descriptor.worldKey())
                && server.getWorld(descriptor.worldKey()) == world);
    }

    private static void requireThread(MinecraftServer server) {
        if (!Objects.requireNonNull(server, "server").isOnThread()) {
            throw new IllegalStateException("動態 world backend 只能在 owning server thread 操作");
        }
    }

    private static void requireWorldIdentity(MinecraftServer server, UniverseWorldDescriptor descriptor,
            ServerWorld world) {
        requireMapIdentity(server.getWorld(descriptor.worldKey()), world);
        requireMapIdentity(world.getServer(), server);
        if (!world.getRegistryKey().equals(descriptor.worldKey())) {
            throw new IllegalStateException("world key 與 descriptor 不符");
        }
    }

    private void rollbackUnpublished(Map<RegistryKey<World>, ServerWorld> worlds,
            UniverseWorldDescriptor descriptor, ServerWorld created, boolean published) throws IOException {
        if (published) {
            requireMapIdentity(worlds.get(descriptor.worldKey()), created);
            if (!worlds.remove(descriptor.worldKey(), created)) {
                throw new IllegalStateException("無法 expected-remove 未公開的 world");
            }
        }
        var binding = borders.remove(created);
        try {
            if (binding != null) {
                binding.source().removeListener(binding.listener());
            }
        } finally {
            created.close();
        }
    }

    private MaterializeResult failUnhealthy(MinecraftServer server, UniverseWorldDescriptor descriptor,
            Throwable failure) {
        unhealthy.add(server);
        LOGGER.error("動態 world backend 已不健康，保留 {} 資料並請求正常停止", descriptor.worldKey().getValue(), failure);
        server.stop(false);
        return result(MaterializeResult.Status.FAILED_UNHEALTHY);
    }

    private static MaterializeResult result(MaterializeResult.Status status) {
        return new MaterializeResult(status, null);
    }

    static void requireVersion(String version) {
        if (!"1.21".equals(version)) {
            throw new IllegalArgumentException("動態 world backend 僅支援 Minecraft 1.21: " + version);
        }
    }

    static void requireDescriptor(UniverseWorldDescriptor descriptor) {
        var id = descriptor.worldKey().getValue();
        String[] parts = id.getPath().split("/", -1);
        if (!id.getNamespace().equals("quantumchamber") || parts.length != 3
                || !parts[0].equals("universe") || !parts[2].equals("overworld")
                || !UUID.fromString(parts[1]).toString().equals(parts[1])
                || descriptor.role() != DimensionRole.OVERWORLD
                || descriptor.generatorProfile() != GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1
                || descriptor.seedPolicy() != SeedPolicy.SHARED_SAVE_SEED_V1
                || descriptor.storagePolicyVersion() != 1) {
            throw new IllegalArgumentException("descriptor 不符合 schema 1 的 owned Overworld profile");
        }
    }

    static void requireMapIdentity(Object current, Object expected) {
        if (current != expected) {
            throw new IllegalStateException("live map 不是 expected exact world instance");
        }
    }

    static Path requireStorage(Path root, Path directory, UniverseWorldDescriptor descriptor) throws IOException {
        requireDescriptor(descriptor);
        Path canonicalRoot = root.toRealPath();
        Path expected = canonicalRoot.resolve("dimensions/quantumchamber")
                .resolve(descriptor.worldKey().getValue().getPath());
        Path absolute = directory.toAbsolutePath().normalize();
        Path existing = absolute;
        while (Files.notExists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
            if (existing == null) {
                throw new IOException("無法解析 world storage 的既有祖先");
            }
        }
        Path canonical = existing.toRealPath().resolve(existing.relativize(absolute)).normalize();
        if (!canonical.startsWith(canonicalRoot.resolve("dimensions/quantumchamber/universe"))
                || !canonical.equals(expected)) {
            throw new IllegalArgumentException("world storage 不在 exact owned Universe 目錄: " + canonical);
        }
        return canonical;
    }

    private record BorderBinding(WorldBorder source, WorldBorderListener listener) { }
}
