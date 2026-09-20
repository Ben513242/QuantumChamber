package dev.quantumchamber.universe;

import java.util.Optional;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

/** 依 exact server/catalog 權威解析角色，保留 M1 的純 vanilla 契約。 */
public final class UniverseRoleResolver {
    private UniverseRoleResolver() { }

    public static Optional<DimensionRole> resolve(MinecraftServer server, RegistryKey<World> worldKey) {
        return resolve(server, worldKey, () -> {
            if (!server.isOnThread()) {
                throw new IllegalStateException("Universe role 必須在所屬 server thread 解析");
            }
            var overworld = server.getOverworld();
            return new CatalogScope<>(overworld == null ? null : overworld.getServer(),
                    () -> UniverseRegistryState.get(server));
        });
    }

    static <S> Optional<DimensionRole> resolve(S server, RegistryKey<World> worldKey,
            Supplier<CatalogScope<S>> catalog) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(worldKey, "worldKey");
        var vanilla = DimensionRole.fromVanillaKey(worldKey);
        if (vanilla.isPresent()) return vanilla;
        var scope = Objects.requireNonNull(catalog.get(), "catalog scope");
        if (scope.owner() != server) return Optional.empty();
        var state = scope.state().get();
        state.requireHealthy();
        return state.findByWorldKey(worldKey).flatMap(record -> record.definition().worlds().values().stream()
                .filter(descriptor -> descriptor.worldKey().equals(worldKey))
                .map(UniverseWorldDescriptor::role).findFirst());
    }

    record CatalogScope<S>(S owner, Supplier<UniverseRegistryState> state) { }
}
