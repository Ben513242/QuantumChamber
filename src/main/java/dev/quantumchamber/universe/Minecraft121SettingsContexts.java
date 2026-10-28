package dev.quantumchamber.universe;

import java.util.Objects;

/** 單一 server 的只讀設定來源發布owner；事件接線由8B3負責。 */
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

    private Object server;
    private Thread ownerThread;
    private Sources current;
    private Sources pending;
    private long epoch;
    private int inflight;
    private boolean failed;
    private boolean initialPublished;
    private boolean stopped;

    public Minecraft121SettingsContexts(Object server) {
        this.server = Objects.requireNonNull(server, "server");
        ownerThread = Thread.currentThread();
    }

    public synchronized void publishInitial(Sources sources) {
        requireOwnerThread();
        if (stopped || initialPublished || current != null || inflight != 0) throw unavailable();
        current = Objects.requireNonNull(sources, "sources");
        initialPublished = true;
        epoch++;
    }

    public synchronized void reloadStarted() {
        requireOwnerThread();
        if (stopped) throw unavailable();
        if (inflight == 0) { pending = null; failed = false; }
        initialPublished = true;
        inflight++;
        current = null;
        epoch++;
    }

    public synchronized void reloadFinished(boolean success, Sources sources) {
        requireOwnerThread();
        if (stopped || inflight <= 0) throw unavailable();
        if (!success || sources == null) failed = true;
        else if (pending == null) pending = sources;
        else if (!pending.sameIdentities(sources)) failed = true;
        inflight--;
        if (inflight == 0) {
            if (!failed) current = pending;
            pending = null;
        }
    }

    public synchronized ServerSettingsContext acquire(Object requestedServer) {
        requireOwnerThread();
        if (stopped || server != requestedServer || inflight != 0 || current == null) throw unavailable();
        return new Lease(this, epoch, ownerThread);
    }

    public synchronized void stop() {
        requireOwnerThread();
        stopped = true;
        current = null;
        pending = null;
        epoch++;
        server = null;
        ownerThread = null;
    }

    static Sources sources(ServerSettingsContext context) {
        if (!(context instanceof Lease lease)) throw unavailable();
        lease.requireValid();
        synchronized (lease.owner) { return lease.owner.current; }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) throw unavailable();
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("CONTEXT_UNAVAILABLE"); }

    private static final class Lease implements ServerSettingsContext {
        private final Minecraft121SettingsContexts owner;
        private final long epoch;
        private final Thread thread;
        private Lease(Minecraft121SettingsContexts owner, long epoch, Thread thread) {
            this.owner = owner; this.epoch = epoch; this.thread = thread;
        }
        @Override public void requireValid() {
            synchronized (owner) {
                if (Thread.currentThread() != thread || owner.ownerThread != thread || owner.stopped
                        || owner.inflight != 0 || owner.epoch != epoch || owner.current == null) throw unavailable();
            }
        }
    }
}
