package dev.quantumchamber.universe;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

/**
 * 僅保存 runtime ownership，不讀取 catalog 或 native live map。
 * 正式 backend 使用 {@code <MinecraftServer, ServerWorld>}，並在 owning server thread 呼叫。
 * server/world 型別可替換，但 ownership 一律使用 exact instance identity。
 */
public final class UniverseRuntimeRegistry<S, W> {
    private final Map<S, Map<RegistryKey<World>, Entry<W>>> servers = new IdentityHashMap<>();

    public DynamicWorldRuntimeState state(S server, UniverseWorldDescriptor descriptor) {
        var entry = find(server, descriptor);
        return entry == null ? DynamicWorldRuntimeState.ABSENT : entry.state;
    }

    /** 僅回傳本 registry 的 ACTIVE owner；backend 仍須核對 native live map。 */
    public Optional<W> resolveActive(S server, UniverseWorldDescriptor descriptor) {
        var entry = find(server, descriptor);
        return entry != null && entry.descriptor.equals(descriptor)
                && entry.state == DynamicWorldRuntimeState.ACTIVE
                ? Optional.of(entry.world) : Optional.empty();
    }

    public void beginMaterialize(S server, UniverseWorldDescriptor descriptor) {
        if (find(server, descriptor) != null) {
            throw new IllegalStateException("此 server/world key 已有 runtime owner 或 failure receipt");
        }
        servers.computeIfAbsent(server, ignored -> new HashMap<>())
                .put(descriptor.worldKey(), new Entry<>(descriptor));
    }

    public void activate(S server, UniverseWorldDescriptor descriptor, W world) {
        Objects.requireNonNull(world, "world");
        var entry = require(server, descriptor, DynamicWorldRuntimeState.MATERIALIZING);
        entry.world = world;
        entry.state = DynamicWorldRuntimeState.ACTIVE;
    }

    public void beginUnload(S server, UniverseWorldDescriptor descriptor, W expectedWorld) {
        var entry = require(server, descriptor, DynamicWorldRuntimeState.ACTIVE);
        requireExactWorld(entry, expectedWorld);
        entry.state = DynamicWorldRuntimeState.UNLOADING;
    }

    /** 僅在 backend 已完成完整 unload receipt 後釋放 exact owner。 */
    public void release(S server, UniverseWorldDescriptor descriptor, W expectedWorld) {
        var entry = require(server, descriptor, DynamicWorldRuntimeState.UNLOADING);
        requireExactWorld(entry, expectedWorld);
        var worlds = servers.get(server);
        worlds.remove(descriptor.worldKey(), entry);
        if (worlds.isEmpty()) {
            servers.remove(server);
        }
    }

    /**
     * 失敗 receipt 保留到本 registry 結束，不可 release 或原地重試。
     * MATERIALIZING 尚無 ACTIVE world，expectedWorld 必須為 null；UNLOADING 必須為 exact owner。
     */
    public void fail(S server, UniverseWorldDescriptor descriptor, W expectedWorld) {
        var entry = find(server, descriptor);
        if (entry == null || (entry.state != DynamicWorldRuntimeState.MATERIALIZING
                && entry.state != DynamicWorldRuntimeState.UNLOADING)) {
            throw new IllegalStateException("只有進行中的 materialize/unload 可記錄失敗");
        }
        requireDescriptor(entry, descriptor);
        requireExactWorld(entry, expectedWorld);
        entry.state = entry.state == DynamicWorldRuntimeState.MATERIALIZING
                ? DynamicWorldRuntimeState.MATERIALIZE_FAILED : DynamicWorldRuntimeState.UNLOAD_FAILED;
    }

    private Entry<W> find(S server, UniverseWorldDescriptor descriptor) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(descriptor, "descriptor");
        var worlds = servers.get(server);
        return worlds == null ? null : worlds.get(descriptor.worldKey());
    }

    private Entry<W> require(S server, UniverseWorldDescriptor descriptor, DynamicWorldRuntimeState state) {
        var entry = find(server, descriptor);
        if (entry == null) {
            throw new IllegalStateException("此 server/world key 沒有 runtime owner");
        }
        requireDescriptor(entry, descriptor);
        if (entry.state != state) {
            throw new IllegalStateException("runtime 狀態不允許此操作: " + entry.state);
        }
        return entry;
    }

    private static void requireDescriptor(Entry<?> entry, UniverseWorldDescriptor descriptor) {
        if (!entry.descriptor.equals(descriptor)) {
            throw new IllegalArgumentException("descriptor 與 runtime owner 不符");
        }
    }

    private static void requireExactWorld(Entry<?> entry, Object expectedWorld) {
        if (entry.world != expectedWorld) {
            throw new IllegalArgumentException("expectedWorld 不是 exact runtime instance");
        }
    }

    private static final class Entry<W> {
        final UniverseWorldDescriptor descriptor;
        DynamicWorldRuntimeState state = DynamicWorldRuntimeState.MATERIALIZING;
        W world;

        Entry(UniverseWorldDescriptor descriptor) { this.descriptor = descriptor; }
    }
}
