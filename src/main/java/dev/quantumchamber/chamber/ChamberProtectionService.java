package dev.quantumchamber.chamber;

import dev.quantumchamber.universe.DimensionRole;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/** Guards registered chamber volumes while keeping persistent-state access off the block mutation path. */
public final class ChamberProtectionService {
    private static final ChamberProtectionService INSTANCE = new ChamberProtectionService();

    private final ThreadLocal<Integer> authorizationDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<TargetAuthorization> targetAuthorization = new ThreadLocal<>();
    private MinecraftServer attachedServer;
    private ChamberRegistry attachedRegistry;

    public static ChamberProtectionService get() {
        return INSTANCE;
    }

    public static void initialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ChamberRegistryState state = ChamberRegistryState.get(server);
            state.requireHealthy();
            INSTANCE.attach(server, state.registry());
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCE::detach);
    }

    public boolean mayMutate(ServerWorld world, BlockPos pos) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(pos, "pos");
        TargetAuthorization target = targetAuthorization.get();
        if (isAuthorized() || (target != null && target.world() == world && target.pos().equals(pos))
                || world.getServer() != attachedServer) {
            return true;
        }
        return DimensionRole.fromVanillaKey(world.getRegistryKey())
                .map(role -> attachedServer.getWorld(world.getRegistryKey()) == world
                        ? mayMutate(world.getRegistryKey().getValue(), role, pos)
                        : mayMutate(role, pos))
                .orElse(true);
    }

    /** 僅讀已發布索引，不讀 PersistentState，也不存取 chunk。 */
    public Optional<ChamberRecord> originAt(ServerWorld world, BlockPos pos) {
        if (attachedRegistry == null || world.getServer() != attachedServer
                || attachedServer.getWorld(world.getRegistryKey()) != world) return Optional.empty();
        return DimensionRole.fromVanillaKey(world.getRegistryKey())
                .flatMap(role -> attachedRegistry.findAt(role, pos))
                .filter(record -> record.anchorPos().equals(pos) && record.originWorldKey().equals(world.getRegistryKey().getValue())
                        && record.instanceKind() == ChamberInstanceKind.ORIGIN && !record.destroyed());
    }

    boolean completeOriginRemoval(ChamberOriginAuthority authority) {
        return attachedRegistry != null && new ChamberLifecycleService(attachedRegistry).completeRemoval(authority);
    }

    boolean mayMutate(Identifier worldKey, DimensionRole role, BlockPos pos) {
        Objects.requireNonNull(worldKey, "worldKey");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pos, "pos");
        ChamberRegistry registry = attachedRegistry;
        return isAuthorized() || registry == null || registry.findAt(role, pos)
                .map(record -> record.powerState() == ChamberPowerState.OFF
                        && record.instanceKind() == ChamberInstanceKind.ORIGIN
                        && !record.destroyed()
                        && record.originWorldKey().equals(worldKey)
                        && record.originDimensionRole() == role)
                .orElse(true);
    }

    boolean mayMutate(DimensionRole role, BlockPos pos) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(pos, "pos");
        ChamberRegistry registry = attachedRegistry;
        return isAuthorized() || registry == null || registry.findAt(role, pos).isEmpty();
    }

    public <T> T authorizedMutation(Supplier<T> mutation) {
        Objects.requireNonNull(mutation, "mutation");
        int previousDepth = authorizationDepth.get();
        authorizationDepth.set(previousDepth + 1);
        try {
            return mutation.get();
        } finally {
            if (previousDepth == 0) {
                authorizationDepth.remove();
            } else {
                authorizationDepth.set(previousDepth);
            }
        }
    }

    public <T> T authorizedMutation(ServerWorld world, BlockPos target, Supplier<T> action) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        TargetAuthorization previous = targetAuthorization.get();
        targetAuthorization.set(new TargetAuthorization(world, target.toImmutable()));
        try {
            return action.get();
        } finally {
            if (previous == null) targetAuthorization.remove();
            else targetAuthorization.set(previous);
        }
    }

    public void authorizedMutation(Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        authorizedMutation(() -> {
            mutation.run();
            return null;
        });
    }

    void attach(ChamberRegistry registry) {
        attachedServer = null;
        attachedRegistry = Objects.requireNonNull(registry, "registry");
    }

    private void attach(MinecraftServer server, ChamberRegistry registry) {
        attachedServer = server;
        attachedRegistry = registry;
    }

    private void detach(MinecraftServer server) {
        if (attachedServer == server) {
            attachedServer = null;
            attachedRegistry = null;
        }
    }

    private boolean isAuthorized() {
        return authorizationDepth.get() > 0;
    }

    private record TargetAuthorization(ServerWorld world, BlockPos pos) { }
}
