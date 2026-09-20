package dev.quantumchamber.universe;

import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/** testmod 專用：只讀正式 lifecycle 身分；故障只作用於 transfer 的 backend view。 */
public final class UniverseTransferTestAccess {
    private UniverseTransferTestAccess() { }

    public static boolean contextAbsent(MinecraftServer server) throws ReflectiveOperationException {
        return !contexts().containsKey(server);
    }

    public static Lifecycle capture(MinecraftServer server) throws ReflectiveOperationException {
        Object context = contexts().get(server);
        require(context != null, "正式 lifecycle context 不存在");
        var result = new Lifecycle(server, context,
                (Minecraft121DynamicDimensionBackend) field(context, "backend"));
        result.assertAttached(true);
        return result;
    }

    public record Lifecycle(MinecraftServer server, Object context, Minecraft121DynamicDimensionBackend backend) {
        public void assertAttached(boolean ready) throws ReflectiveOperationException {
            require(server.isOnThread() && contexts().get(server) == context
                    && field(context, "backend") == backend, "正式 context／backend exact identity 不符");
            require(Boolean.valueOf(ready).equals(field(context, "ready")), "正式 lifecycle ready 不符");
            assertHealthy();
        }

        public void assertWorld(ServerWorld world, int unloadCount) throws ReflectiveOperationException {
            var tracked = (Map<?, ?>) field(context, "unloads");
            require(tracked.size() == 1 && Integer.valueOf(unloadCount).equals(tracked.get(world)),
                    "正式 lifecycle exact world 的 LOAD／UNLOAD receipt 不符");
        }

        public void assertDetached(ServerWorld world) throws ReflectiveOperationException {
            require(contextAbsent(server) && field(context, "backend") == backend
                    && Boolean.FALSE.equals(field(context, "ready")), "STOPPED 正式 context 未完整 detach");
            assertHealthy();
            if (world == null) require(((Map<?, ?>) field(context, "unloads")).isEmpty(), "setup 不得持有動態 world");
            else assertWorld(world, 1);
        }

        private void assertHealthy() throws ReflectiveOperationException {
            require(((List<?>) field(context, "materializeFailures")).isEmpty()
                    && field(context, "stoppingFailure") == null, "production lifecycle 存在未解決失敗");
        }
    }

    /** 只在真原生 move 已成功且玩家實際位於目的 world 後，遮蔽 transfer 的 ACTIVE view。 */
    public static final class AuthorityLoss implements DynamicDimensionBackend {
        private final DynamicDimensionBackend delegate;
        private final ServerWorld destination;
        private boolean revoked;
        private int activeReads;

        public AuthorityLoss(DynamicDimensionBackend delegate, ServerWorld destination) {
            this.delegate = delegate;
            this.destination = destination;
        }

        public boolean revoked() { return revoked; }
        public int activeReads() { return activeReads; }

        private void afterNativeSuccess(ServerPlayerEntity player, ServerWorld target) {
            if (target != destination) return;
            require(activeReads > 0 && !revoked && player.getServerWorld() == destination
                    && destination.getEntity(player.getUuid()) == player
                    && player.getServer().getPlayerManager().getPlayer(player.getUuid()) == player,
                    "fault 必須晚於真原生成功與 exact destination observation");
            revoked = true;
        }

        @Override public Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseWorldDescriptor descriptor) {
            if (revoked) return Optional.empty();
            var actual = delegate.resolveActive(server, descriptor);
            if (actual.orElse(null) == destination) activeReads++;
            return actual;
        }

        @Override public MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
            throw new UnsupportedOperationException("transfer probe 不擁有 materialize");
        }

        @Override public UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld world) {
            throw new UnsupportedOperationException("transfer probe 不擁有 unload");
        }
    }

    /** 每次呼叫都執行既有 SessionTransferService；不偽造成功、不直接改玩家 pose。 */
    public static final class NativeMoves implements UniverseTransferService.NativeMove {
        private final SessionTransferService nativeTransfers = new SessionTransferService();
        private final AuthorityLoss fault;
        private final List<Map<String, Object>> calls = new ArrayList<>();

        public NativeMoves(AuthorityLoss fault) { this.fault = fault; }
        public List<Map<String, Object>> calls() { return List.copyOf(calls); }

        @Override public boolean move(ServerPlayerEntity player, ServerWorld target, UniverseTransferPoint point) {
            var evidence = new LinkedHashMap<String, Object>();
            evidence.put("from", player.getServerWorld().getRegistryKey().getValue().toString());
            evidence.put("target", target.getRegistryKey().getValue().toString());
            evidence.put("revokedBefore", fault != null && fault.revoked());
            boolean success = nativeTransfers.move(player, target, point.position(), point.velocity(), point.yaw(), point.pitch());
            evidence.put("nativeSuccess", success);
            evidence.put("actualWorld", player.getServerWorld().getRegistryKey().getValue().toString());
            evidence.put("playerIdentity", System.identityHashCode(player));
            evidence.put("worldEntityExact", target.getEntity(player.getUuid()) == player);
            if (success && fault != null) fault.afterNativeSuccess(player, target);
            evidence.put("revokedAfter", fault != null && fault.revoked());
            calls.add(evidence);
            return success;
        }
    }

    private static Map<?, ?> contexts() throws ReflectiveOperationException {
        var field = UniverseLifecycleService.class.getDeclaredField("CONTEXTS");
        field.setAccessible(true);
        return (Map<?, ?>) field.get(null);
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
