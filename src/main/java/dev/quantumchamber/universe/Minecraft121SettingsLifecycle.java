package dev.quantumchamber.universe;

import dev.quantumchamber.mixin.MinecraftServerDynamicWorldAccess;
import java.lang.ref.WeakReference;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

/** 單一 M6 設定入口；短鎖只保護 exact server 與票據 metadata。 */
public final class Minecraft121SettingsLifecycle {
    private static final Minecraft121SettingsLifecycle INSTANCE = new Minecraft121SettingsLifecycle();
    private static final UniverseProfileRegistry REGISTRY = new UniverseProfileRegistry();
    private static boolean initialized;

    enum Phase { INITIALIZING, IDLE, RELOADING, POISONED, STOPPED }

    private static final class Entry {
        private final Minecraft121SettingsContexts contexts;
        private final Minecraft121SettingsContexts.RevocationHandle revoke;
        private final WeakReference<Thread> owner;
        private Phase phase = Phase.INITIALIZING;
        private Minecraft121SettingsContexts.ReloadTicket ticket;
        private boolean adopted;
        private boolean finishing;

        private Entry(Object server) {
            contexts = new Minecraft121SettingsContexts(server);
            revoke = contexts.revocationHandle();
            owner = new WeakReference<>(Thread.currentThread());
        }
    }

    /** 已排工作只持弱 entry 與純票據，不持 server 或 native source。 */
    static final class ReloadAction {
        private final WeakReference<Entry> entry;
        private final Minecraft121SettingsContexts.ReloadTicket ticket;
        private ReloadAction(Entry entry, Minecraft121SettingsContexts.ReloadTicket ticket) {
            this.entry = new WeakReference<>(entry);
            this.ticket = ticket;
        }
    }

    private final Object monitor = new Object();
    private final IdentityHashMap<Object, Entry> entries = new IdentityHashMap<>();

    Minecraft121SettingsLifecycle() {}

    private static IllegalStateException unavailable() { return new IllegalStateException("CONTEXT_UNAVAILABLE"); }

    private boolean live(Entry entry) {
        return entry != null && entries.containsValue(entry) && entry.phase != Phase.STOPPED;
    }

    private static boolean owner(Entry entry) { return entry.owner.get() == Thread.currentThread(); }

    private Entry prepareStarting(Object server) {
        Objects.requireNonNull(server, "server");
        synchronized (monitor) {
            if (entries.containsKey(server)) throw unavailable();
            var entry = new Entry(server);
            entries.put(server, entry);
            return entry;
        }
    }

    private void finishStarting(Entry entry, Minecraft121SettingsContexts.Sources sources) {
        synchronized (monitor) {
            if (!live(entry) || entry.phase != Phase.INITIALIZING || !owner(entry)) throw unavailable();
            entry.contexts.publishInitial(Objects.requireNonNull(sources, "sources"));
            entry.phase = Phase.IDLE;
        }
    }

    void onStarting(Object server, Minecraft121SettingsContexts.Sources sources) {
        onStarting(server, () -> sources);
    }

    void onStarting(Object server, Supplier<Minecraft121SettingsContexts.Sources> factory) {
        Entry entry = prepareStarting(server);
        try { finishStarting(entry, factory.get()); }
        catch (RuntimeException failure) { onStopping(server); throw failure; }
    }

    ReloadAction onReloadStarted(Object server) {
        synchronized (monitor) {
            Entry entry = entries.get(server);
            if (entry == null || entry.phase == Phase.STOPPED || entry.phase == Phase.POISONED) return null;
            Minecraft121SettingsContexts.ReloadTicket ticket;
            try { ticket = entry.revoke.revokeReloadStart(); }
            catch (IllegalStateException rejected) { entry.phase = Phase.POISONED; return null; }
            if (entry.phase != Phase.IDLE) {
                entry.phase = Phase.POISONED;
                entry.ticket = null;
                return null;
            }
            entry.phase = Phase.RELOADING;
            entry.ticket = ticket;
            entry.adopted = false;
            entry.finishing = false;
            return new ReloadAction(entry, ticket);
        }
    }

    void adopt(ReloadAction action) {
        if (action == null) return;
        synchronized (monitor) {
            Entry entry = action.entry.get();
            if (!active(entry, action) || !owner(entry) || entry.finishing || entry.adopted) return;
            entry.contexts.adoptReload(action.ticket);
            entry.adopted = true;
        }
    }

    private boolean active(Entry entry, ReloadAction action) {
        return live(entry) && entry.phase == Phase.RELOADING && entry.ticket == action.ticket;
    }

    ReloadAction prepareEnd(Object server) {
        synchronized (monitor) {
            Entry entry = entries.get(server);
            if (entry == null || entry.phase != Phase.RELOADING || !owner(entry)) return null;
            if (entry.finishing) {
                entry.phase = Phase.POISONED;
                return null;
            }
            var action = new ReloadAction(entry, entry.ticket);
            if (!entry.adopted) {
                entry.contexts.adoptReload(action.ticket);
                entry.adopted = true;
            }
            entry.finishing = true;
            return action;
        }
    }

    void finishEnd(ReloadAction action, boolean success, Minecraft121SettingsContexts.Sources sources) {
        if (action == null) return;
        synchronized (monitor) {
            Entry entry = action.entry.get();
            if (!active(entry, action) || !owner(entry) || !entry.finishing || !entry.adopted) return;
            entry.contexts.finishReload(action.ticket, success, success ? sources : null);
            entry.ticket = null;
            entry.finishing = false;
            entry.phase = Phase.IDLE;
        }
    }

    void onReloadFinished(Object server, boolean success, Minecraft121SettingsContexts.Sources sources) {
        finishEnd(prepareEnd(server), success, sources);
    }

    void onStopping(Object server) {
        synchronized (monitor) {
            Entry entry = entries.get(server);
            if (entry == null) return;
            entry.revoke.revokeStopAndDetach();
            entry.phase = Phase.STOPPED;
            entry.ticket = null;
            entry.finishing = false;
            entries.remove(server);
        }
    }

    ServerSettingsContext acquire(Object server) {
        synchronized (monitor) {
            Entry entry = entries.get(server);
            if (entry == null || entry.phase != Phase.IDLE) throw unavailable();
            return entry.contexts.acquire(server);
        }
    }

    public static UniverseProfileRegistry registry() {
        if (!REGISTRY.isFrozen()) throw unavailable();
        return REGISTRY;
    }

    public static ServerSettingsContext acquire(MinecraftServer server) { return INSTANCE.acquire((Object) server); }

    @FunctionalInterface
    interface Registrar { <T> void register(Event<T> event, T listener); }

    /** 只供 recording registrar 驗證真 listener 的最小語意接點。 */
    interface Core {
        Object reserveStart(Object server);
        void completeStart(Object reservation, Minecraft121SettingsContexts.Sources sources);
        Object startReload(Object server);
        void adoptReload(Object action);
        Object claimEnd(Object server);
        void finishEnd(Object action, boolean success, Minecraft121SettingsContexts.Sources sources);
        void stop(Object server);
    }

    /** 生產來源一律由 callback 的 exact server 即時取得。 */
    static final class Sources {
        private Sources() {}
        static Minecraft121SettingsContexts.Sources of(MinecraftServer server) {
            return new Minecraft121SettingsContexts.Sources(server.getRegistryManager(), server.getResourceManager(),
                    server.getStructureTemplateManager(),
                    ((MinecraftServerDynamicWorldAccess) server).quantumchamber$getSession(),
                    server.getSaveProperties().getGeneratorOptions().shouldGenerateStructures());
        }
    }

    void bind(Registrar registrar, Function<MinecraftServer, Minecraft121SettingsContexts.Sources> factory,
            Predicate<MinecraftServer> isOwner, BiConsumer<MinecraftServer, Runnable> dispatch) {
        bind(registrar, factory, isOwner, dispatch, new Core() {
            @Override public Object reserveStart(Object server) { return prepareStarting(server); }
            @Override public void completeStart(Object reservation, Minecraft121SettingsContexts.Sources sources) {
                finishStarting((Entry) reservation, sources);
            }
            @Override public Object startReload(Object server) { return onReloadStarted(server); }
            @Override public void adoptReload(Object action) { adopt((ReloadAction) action); }
            @Override public Object claimEnd(Object server) { return prepareEnd(server); }
            @Override public void finishEnd(Object action, boolean success, Minecraft121SettingsContexts.Sources sources) {
                Minecraft121SettingsLifecycle.this.finishEnd((ReloadAction) action, success, sources);
            }
            @Override public void stop(Object server) { onStopping(server); }
        });
    }

    void bind(Registrar registrar, Function<MinecraftServer, Minecraft121SettingsContexts.Sources> factory,
            Predicate<MinecraftServer> isOwner, BiConsumer<MinecraftServer, Runnable> dispatch, Core core) {
        registrar.register(ServerLifecycleEvents.SERVER_STARTING, server -> {
            if (!isOwner.test(server)) throw unavailable();
            Object reservation = core.reserveStart(server);
            try {
                Minecraft121SettingsContexts.Sources sources = factory.apply(server);
                core.completeStart(reservation, sources);
            } catch (RuntimeException failure) {
                core.stop(server);
                throw failure;
            }
        });
        registrar.register(ServerLifecycleEvents.START_DATA_PACK_RELOAD, (server, resourceManager) -> {
            Object action = core.startReload(server);
            if (action == null) return;
            if (isOwner.test(server)) core.adoptReload(action);
            else dispatch.accept(server, () -> core.adoptReload(action));
        });
        registrar.register(ServerLifecycleEvents.END_DATA_PACK_RELOAD, (server, manager, success) -> {
            if (!isOwner.test(server)) return;
            Object action = core.claimEnd(server);
            if (action == null) return;
            if (!success) { core.finishEnd(action, false, null); return; }
            Minecraft121SettingsContexts.Sources sources;
            try { sources = factory.apply(server); }
            catch (RuntimeException failure) { core.finishEnd(action, false, null); return; }
            core.finishEnd(action, true, sources);
        });
        registrar.register(ServerLifecycleEvents.SERVER_STOPPING, core::stop);
    }

    public static synchronized void initialize() {
        if (initialized) throw new IllegalStateException("M6 lifecycle 重複初始化");
        REGISTRY.register(new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID));
        REGISTRY.register(new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID));
        REGISTRY.freeze();
        INSTANCE.bind(new Registrar() {
            @Override public <T> void register(Event<T> event, T listener) { event.register(listener); }
        }, Sources::of, MinecraftServer::isOnThread, MinecraftServer::execute);
        initialized = true;
    }
}
