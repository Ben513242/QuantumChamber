package dev.quantumchamber.universe;

import java.lang.ref.WeakReference;
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

    /** 唯一 monitor；臨界區只做身分、metadata 比對與引用賦值。 */
    private enum InitialState { ISSUED, CAPTURING, COMMITTED, ABORTED }

    static final class Fence {
        private final Object incarnation = new Object();
        private long generation;
        private boolean explicit;
        private boolean stopped;
        private boolean poisoned;
        private boolean admissionClosed = true;
        private int legacyPending;
        private InitialTicket initialTicket;
        private InitialState initialState;
        private Object initialCaptureToken;
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

    /** facade 與 lease 的共同引用槽；一個 incarnation 絕不替換 Cell。 */
    private static final class Cell {
        private final Fence fence = new Fence();
        private OwnerState payload;
        private Cell(OwnerState payload) { this.payload = payload; }
    }
    /** 原生來源與 Thread 只能由可撤銷 payload 強持。 */
    private static final class OwnerState {
        private Object server;
        private Thread ownerThread;
        private final SourcesReadBarrier sourcesReadBarrier;
        private Sources current;
        private Sources pending;
        private long epoch;
        private long publishedGeneration;
        private int inflight;
        private boolean failed;
        private boolean initialPublished;
        private OwnerState(Object server, SourcesReadBarrier barrier) {
            this.server = server;
            this.ownerThread = Thread.currentThread();
            this.sourcesReadBarrier = barrier;
        }
    }

    /** callback 僅強持 metadata；Cell 為弱邊，不保活 owner/native。 */
    static final class RevocationHandle {
        private final Fence fence;
        private final WeakReference<Cell> cell;
        private RevocationHandle(Cell cell) {
            this.fence = cell.fence;
            this.cell = new WeakReference<>(cell);
        }
        ReloadTicket revokeReloadStart() {
            synchronized (fence) {
                if (fence.stopped) throw unavailable();
                fence.explicit = true;
                fence.advance();
                if (fence.legacyPending != 0 || (fence.activeTicket != null && !fence.settled))
                    fence.poisoned = true;
                fence.activeTicket = new ReloadTicket(fence.incarnation, fence.generation);
                fence.adopted = false;
                fence.settled = false;
                return fence.activeTicket;
            }
        }
        /** 不等待 owner 事件迴圈或 native 借用；取得短 monitor 後同步撤銷與斷邊。 */
        void revokeStopAndDetach() {
            synchronized (fence) {
                fence.stop();
                Cell found = cell.get();
                if (found != null) found.payload = null;
            }
        }
        boolean isStopped() {
            synchronized (fence) { return fence.stopped; }
        }
    }

    private final Cell cell;
    public Minecraft121SettingsContexts(Object server) { this(server, () -> { }); }
    Minecraft121SettingsContexts(Object server, SourcesReadBarrier barrier) {
        cell = new Cell(new OwnerState(Objects.requireNonNull(server, "server"),
                Objects.requireNonNull(barrier, "barrier")));
    }
    RevocationHandle revocationHandle() { return new RevocationHandle(cell); }

    /** metadata-only 借用；不得承載原生 source 或 owner 引用。 */
    static final class InitialCapture implements AutoCloseable {
        private final Cell cell;
        private final InitialTicket ticket;
        private final Object token;
        private boolean closed;
        private InitialCapture(Cell cell, InitialTicket ticket, Object token) {
            this.cell = cell;
            this.ticket = ticket;
            this.token = token;
        }
        void checkpoint() {
            synchronized (cell.fence) {
                if (closed) throw unavailable();
                requireCapturing(cell, ticket, this);
            }
        }
        void abort() {
            synchronized (cell.fence) {
                if (closed) throw unavailable();
                requireCapturing(cell, ticket, this);
                cell.fence.initialState = InitialState.ABORTED;
            }
        }
        @Override public void close() {
            synchronized (cell.fence) {
                if (closed) return;
                OwnerState state = cell.payload;
                if (state != null && Thread.currentThread() != state.ownerThread) throw unavailable();
                if (cell.fence.initialCaptureToken == token
                        && cell.fence.initialState == InitialState.CAPTURING)
                    cell.fence.initialState = InitialState.ABORTED;
                closed = true;
            }
        }
    }
    InitialCapture beginInitialCapture(InitialTicket exact) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            fence.requireLive();
            requireInitial(state, fence);
            requireExactInitial(fence, exact);
            if (fence.initialState != InitialState.ISSUED || fence.activeTicket != null)
                throw unavailable();
            var token = new Object();
            var capture = new InitialCapture(cell, exact, token);
            fence.initialCaptureToken = token;
            fence.initialState = InitialState.CAPTURING;
            return capture;
        }
    }
    private static void requireExactInitial(Fence fence, InitialTicket exact) {
        if (!fence.explicit || exact == null || exact != fence.initialTicket
                || exact.incarnation != fence.incarnation || exact.generation != fence.generation)
            throw unavailable();
    }
    private static void requireCapturing(Cell cell, InitialTicket exact, InitialCapture capture) {
        Fence fence = cell.fence;
        OwnerState state = cell.payload;
        if (state == null || Thread.currentThread() != state.ownerThread) throw unavailable();
        fence.requireLive();
        requireInitial(state, fence);
        requireExactInitial(fence, exact);
        if (fence.activeTicket != null || fence.initialState != InitialState.CAPTURING
                || fence.initialCaptureToken != capture.token || capture.closed) throw unavailable();
    }

    InitialTicket captureInitial() {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            fence.requireLive();
            requireInitial(state, fence);
            if (fence.initialTicket != null || fence.activeTicket != null) throw unavailable();
            fence.explicit = true;
            fence.initialTicket = new InitialTicket(fence.incarnation, fence.generation);
            fence.initialState = InitialState.ISSUED;
            return fence.initialTicket;
        }
    }
    void publishInitial(InitialTicket expected, Sources sources) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            fence.requireLive();
            requireInitial(state, fence);
            requireExactInitial(fence, expected);
            if (fence.activeTicket != null || fence.initialState != InitialState.CAPTURING
                    || fence.initialCaptureToken == null) throw unavailable();
            publish(state, fence, Objects.requireNonNull(sources, "sources"));
            fence.initialState = InitialState.COMMITTED;
            state.initialPublished = true;
            state.epoch++;
        }
    }
    /** foreign START 僅撤銷 metadata，不讀 payload。 */
    ReloadTicket revokeReloadStart() { return revocationHandle().revokeReloadStart(); }
    void adoptReload(ReloadTicket expected) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireTicket(fence, expected);
            if (fence.adopted || fence.settled || state.inflight != 0) throw unavailable();
            state.current = null;
            state.pending = null;
            state.failed = false;
            state.initialPublished = true;
            state.epoch++;
            state.inflight = 1;
            fence.adopted = true;
        }
    }
    void finishReload(ReloadTicket expected, boolean success, Sources sources) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireTicket(fence, expected);
            if (!fence.adopted || fence.settled || state.inflight != 1) throw unavailable();
            fence.settled = true;
            state.inflight = 0;
            state.current = null;
            state.pending = null;
            if (success && sources != null) publish(state, fence, sources);
        }
    }
    void revokeStop() { revocationHandle().revokeStopAndDetach(); }

    public void publishInitial(Sources sources) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireLegacy(state, fence);
            requireInitial(state, fence);
            publish(state, fence, Objects.requireNonNull(sources, "sources"));
            state.initialPublished = true;
            state.epoch++;
        }
    }
    public void reloadStarted() {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireLegacy(state, fence);
            if (state.inflight == Integer.MAX_VALUE) {
                fence.poisoned = true;
                fence.admissionClosed = true;
                throw unavailable();
            }
            fence.advance();
            if (state.inflight == 0) { state.pending = null; state.failed = false; }
            state.initialPublished = true;
            state.inflight++;
            fence.legacyPending++;
            state.current = null;
            state.epoch++;
        }
    }
    public void reloadFinished(boolean success, Sources sources) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireLegacy(state, fence);
            if (state.inflight <= 0) throw unavailable();
            if (!success || sources == null) state.failed = true;
            else if (state.pending == null) state.pending = sources;
            else if (!state.pending.sameIdentities(sources)) state.failed = true;
            state.inflight--;
            fence.legacyPending--;
            if (state.inflight == 0) {
                if (!state.failed && state.pending != null) publish(state, fence, state.pending);
                state.pending = null;
            }
        }
    }
    public ServerSettingsContext acquire(Object requestedServer) {
        synchronized (cell.fence) {
            OwnerState state = requireOwnerState();
            Fence fence = cell.fence;
            requireAvailable(state, fence);
            if (state.server != requestedServer) throw unavailable();
            return new Lease(cell, state.epoch, fence.incarnation, state.publishedGeneration);
        }
    }
    public void stop() {
        synchronized (cell.fence) {
            requireOwnerState();
            cell.fence.stop();
            cell.payload = null;
        }
    }

    /** 最後同鎖驗證與擷取才是權威；已逸出的 raw Sources 無撤回能力。 */
    static Sources sources(ServerSettingsContext context) {
        if (!(context instanceof Lease lease)) throw unavailable();
        SourcesReadBarrier barrier;
        synchronized (lease.cell.fence) {
            OwnerState state = lease.validateLocked();
            barrier = state.sourcesReadBarrier;
        }
        // 障礙只供測試，固定在所有鎖外。
        barrier.afterPrecheck();
        synchronized (lease.cell.fence) { return lease.validateLocked().current; }
    }
    private OwnerState requireOwnerState() {
        OwnerState state = cell.payload;
        if (state == null || Thread.currentThread() != state.ownerThread) throw unavailable();
        return state;
    }
    private static void requireInitial(OwnerState state, Fence fence) {
        if (state.initialPublished || state.current != null || state.inflight != 0
                || fence.legacyPending != 0) throw unavailable();
    }
    private static void requireLegacy(OwnerState state, Fence fence) {
        fence.requireLive();
        if (fence.explicit) throw unavailable();
        if (fence.legacyPending != state.inflight) {
            fence.poisoned = true;
            fence.admissionClosed = true;
            throw unavailable();
        }
    }
    private static void requireTicket(Fence fence, ReloadTicket expected) {
        fence.requireLive();
        if (!fence.explicit || expected == null || expected != fence.activeTicket
                || expected.incarnation != fence.incarnation || expected.generation != fence.generation)
            throw unavailable();
    }
    private static void publish(OwnerState state, Fence fence, Sources sources) {
        state.current = sources;
        state.publishedGeneration = fence.generation;
        fence.admissionClosed = false;
    }
    private static void requireAvailable(OwnerState state, Fence fence) {
        fence.requireLive();
        if (fence.admissionClosed || state.inflight != 0 || state.current == null
                || state.publishedGeneration != fence.generation) throw unavailable();
    }
    private static IllegalStateException unavailable() { return new IllegalStateException("CONTEXT_UNAVAILABLE"); }

    private static final class Lease implements ServerSettingsContext {
        private final Cell cell;
        private final long epoch;
        private final Object incarnation;
        private final long generation;
        private Lease(Cell cell, long epoch, Object incarnation, long generation) {
            this.cell = cell;
            this.epoch = epoch;
            this.incarnation = incarnation;
            this.generation = generation;
        }
        private OwnerState validateLocked() {
            Fence fence = cell.fence;
            OwnerState state = cell.payload;
            if (state == null || Thread.currentThread() != state.ownerThread) throw unavailable();
            requireAvailable(state, fence);
            if (state.epoch != epoch || fence.incarnation != incarnation
                    || fence.generation != generation) throw unavailable();
            return state;
        }
        @Override public void requireValid() {
            synchronized (cell.fence) { validateLocked(); }
        }
    }
}
