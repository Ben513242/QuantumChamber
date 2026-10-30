package dev.quantumchamber.universe;

import java.util.Objects;

/** 單一 server 的設定來源 owner；正式生命週期接線仍由後續切片負責。 */
public final class Minecraft121SettingsContexts {
    public record Sources(Object registries, Object resources, Object templates, Object session,
            boolean generateStructures) {
        public Sources {
            Objects.requireNonNull(registries, "registries");
            Objects.requireNonNull(resources, "resources");
            Objects.requireNonNull(templates, "templates");
            Objects.requireNonNull(session, "session");
        }
        boolean sameIdentities(Sources other) {
            return other != null && registries == other.registries && resources == other.resources
                    && templates == other.templates && session == other.session
                    && generateStructures == other.generateStructures;
        }
    }

    @FunctionalInterface
    interface SourcesReadBarrier { void afterPrecheck(); }

    static final class InitialTicket {
        private final Object incarnation;
        private final long generation;
        private InitialTicket(Object incarnation, long generation) {
            this.incarnation = incarnation;
            this.generation = generation;
        }
    }

    static final class ReloadTicket {
        private final Object incarnation;
        private final long generation;
        private ReloadTicket(Object incarnation, long generation) {
            this.incarnation = incarnation;
            this.generation = generation;
        }
    }

    /** 所有欄位僅由本 monitor 保護；不得保存 native 來源或反向取得 owner 鎖。 */
    static final class Fence {
        private final Object incarnation = new Object();
        private long generation;
        private boolean explicit;
        private boolean stopped;
        private boolean poisoned;
        private boolean admissionClosed = true;
        private int legacyPending;
        private InitialTicket initialTicket;
        private ReloadTicket activeTicket;
        private boolean adopted;
        private boolean settled;

        private void advance() {
            admissionClosed = true;
            if (generation == Long.MAX_VALUE) {
                poisoned = true;
                throw unavailable();
            }
            generation++;
        }

        private void stop() {
            if (stopped) return;
            stopped = true;
            admissionClosed = true;
            if (generation == Long.MAX_VALUE) poisoned = true;
            else generation++;
        }

        private void requireLive() {
            if (stopped || poisoned) throw unavailable();
        }
    }

    private final Fence fence = new Fence();
    private final SourcesReadBarrier sourcesReadBarrier;
    private Object server;
    private Thread ownerThread;
    private Sources current;
    private Sources pending;
    private long epoch;
    private long publishedGeneration;
    private int inflight;
    private boolean failed;
    private boolean initialPublished;
    private boolean stopped;

    public Minecraft121SettingsContexts(Object server) {
        this(server, () -> { });
    }

    Minecraft121SettingsContexts(Object server, SourcesReadBarrier barrier) {
        this.server = Objects.requireNonNull(server, "server");
        sourcesReadBarrier = Objects.requireNonNull(barrier, "barrier");
        ownerThread = Thread.currentThread();
    }

    synchronized InitialTicket captureInitial() {
        requireOwnerThread();
        synchronized (fence) {
            fence.requireLive();
            requireInitial();
            if (fence.initialTicket != null || fence.activeTicket != null) throw unavailable();
            fence.explicit = true;
            fence.initialTicket = new InitialTicket(fence.incarnation, fence.generation);
            return fence.initialTicket;
        }
    }

    synchronized void publishInitial(InitialTicket expected, Sources sources) {
        requireOwnerThread();
        synchronized (fence) {
            fence.requireLive();
            requireInitial();
            if (!fence.explicit || expected == null || expected != fence.initialTicket
                    || expected.incarnation != fence.incarnation || expected.generation != fence.generation
                    || fence.activeTicket != null) throw unavailable();
            publish(Objects.requireNonNull(sources, "sources"));
            initialPublished = true;
            epoch++;
        }
    }

    /** 可由 foreign 呼叫；只撤銷 metadata，絕不觸碰 owner／native 狀態。 */
    ReloadTicket revokeReloadStart() {
        synchronized (fence) {
            if (fence.stopped) throw unavailable();
            fence.explicit = true;
            fence.advance();
            if (fence.legacyPending != 0 || (fence.activeTicket != null && !fence.settled)) {
                fence.poisoned = true;
            }
            fence.activeTicket = new ReloadTicket(fence.incarnation, fence.generation);
            fence.adopted = false;
            fence.settled = false;
            return fence.activeTicket;
        }
    }

    synchronized void adoptReload(ReloadTicket expected) {
        requireOwnerThread();
        synchronized (fence) {
            requireTicket(expected);
            if (fence.adopted || fence.settled || inflight != 0) throw unavailable();
            current = null;
            pending = null;
            failed = false;
            initialPublished = true;
            epoch++;
            inflight = 1;
            fence.adopted = true;
        }
    }

    synchronized void finishReload(ReloadTicket expected, boolean success, Sources sources) {
        requireOwnerThread();
        synchronized (fence) {
            requireTicket(expected);
            if (!fence.adopted || fence.settled || inflight != 1) throw unavailable();
            fence.settled = true;
            inflight = 0;
            current = null;
            pending = null;
            if (success && sources != null) publish(sources);
        }
    }

    void revokeStop() {
        synchronized (fence) { fence.stop(); }
    }

    public synchronized void publishInitial(Sources sources) {
        requireOwnerThread();
        synchronized (fence) {
            requireLegacy();
            requireInitial();
            publish(Objects.requireNonNull(sources, "sources"));
            initialPublished = true;
            epoch++;
        }
    }

    public synchronized void reloadStarted() {
        requireOwnerThread();
        synchronized (fence) {
            requireLegacy();
            if (inflight == Integer.MAX_VALUE) {
                fence.poisoned = true;
                fence.admissionClosed = true;
                throw unavailable();
            }
            fence.advance();
            if (inflight == 0) { pending = null; failed = false; }
            initialPublished = true;
            inflight++;
            fence.legacyPending++;
            current = null;
            epoch++;
        }
    }

    public synchronized void reloadFinished(boolean success, Sources sources) {
        requireOwnerThread();
        synchronized (fence) {
            requireLegacy();
            if (inflight <= 0) throw unavailable();
            if (!success || sources == null) failed = true;
            else if (pending == null) pending = sources;
            else if (!pending.sameIdentities(sources)) failed = true;
            inflight--;
            fence.legacyPending--;
            if (inflight == 0) {
                if (!failed && pending != null) publish(pending);
                pending = null;
            }
        }
    }

    public synchronized ServerSettingsContext acquire(Object requestedServer) {
        requireOwnerThread();
        synchronized (fence) {
            requireAvailable();
            if (server != requestedServer) throw unavailable();
            return new Lease(this, epoch, ownerThread, fence.incarnation, publishedGeneration);
        }
    }

    public synchronized void stop() {
        requireOwnerThread();
        synchronized (fence) {
            fence.stop();
            stopped = true;
            current = null;
            pending = null;
            inflight = 0;
            fence.legacyPending = 0;
            epoch++;
            server = null;
            ownerThread = null;
        }
    }

    /** 最後的同鎖驗證與擷取才是權威；已逸出的 raw Sources 不具撤回能力。 */
    static Sources sources(ServerSettingsContext context) {
        if (!(context instanceof Lease lease)) throw unavailable();
        lease.requireValid();
        // 測試障礙固定在所有鎖外，公開建構子只使用 no-op。
        lease.owner.sourcesReadBarrier.afterPrecheck();
        synchronized (lease.owner) {
            synchronized (lease.owner.fence) {
                lease.validateLocked();
                return lease.owner.current;
            }
        }
    }

    /** 初次發布狀態檢查須已持有 owner → Fence 兩把鎖。 */
    private void requireInitial() {
        if (stopped || initialPublished || current != null || inflight != 0
                || fence.legacyPending != 0) throw unavailable();
    }

    private void requireLegacy() {
        fence.requireLive();
        if (fence.explicit) throw unavailable();
        if (fence.legacyPending != inflight) {
            fence.poisoned = true;
            fence.admissionClosed = true;
            throw unavailable();
        }
    }

    private void requireTicket(ReloadTicket expected) {
        fence.requireLive();
        if (!fence.explicit || expected == null || expected != fence.activeTicket
                || expected.incarnation != fence.incarnation || expected.generation != fence.generation) {
            throw unavailable();
        }
    }

    private void publish(Sources sources) {
        current = sources;
        publishedGeneration = fence.generation;
        fence.admissionClosed = false;
    }

    private void requireAvailable() {
        fence.requireLive();
        if (stopped || fence.admissionClosed || inflight != 0 || current == null
                || publishedGeneration != fence.generation) throw unavailable();
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) throw unavailable();
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("CONTEXT_UNAVAILABLE"); }

    private static final class Lease implements ServerSettingsContext {
        private final Minecraft121SettingsContexts owner;
        private final long epoch;
        private final Thread thread;
        private final Object incarnation;
        private final long generation;
        private Lease(Minecraft121SettingsContexts owner, long epoch, Thread thread,
                Object incarnation, long generation) {
            this.owner = owner;
            this.epoch = epoch;
            this.thread = thread;
            this.incarnation = incarnation;
            this.generation = generation;
        }
        private void validateLocked() {
            owner.requireAvailable();
            if (Thread.currentThread() != thread || owner.ownerThread != thread
                    || owner.epoch != epoch || owner.fence.incarnation != incarnation
                    || owner.fence.generation != generation) throw unavailable();
        }
        @Override public void requireValid() {
            synchronized (owner) {
                synchronized (owner.fence) { validateLocked(); }
            }
        }
    }
}
