package dev.quantumchamber.universe;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;
import net.minecraft.structure.StructureTemplate;

/** 同一原生 template manager 的讀寫協調。關聯表只以 template identity 查詢。 */
public final class Minecraft121TemplateAccessGate {
    private static final long MAX_HOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Object MANAGERS_MONITOR = new Object();
    private static final ReferenceQueue<Object> MANAGERS_COLLECTED = new ReferenceQueue<>();
    private static final HashMap<ManagerKey, Minecraft121TemplateAccessGate> MANAGERS = new HashMap<>();
    private static final Object ASSOCIATIONS_MONITOR = new Object();
    private static final ReferenceQueue<StructureTemplate> COLLECTED = new ReferenceQueue<>();
    private static final HashMap<TemplateKey, Association> ASSOCIATIONS = new HashMap<>();
    private static final ThreadLocal<Minecraft121TemplateAccessGate> ACTIVE_SNAPSHOT = new ThreadLocal<>();

    private final WeakReference<Object> managerIdentity;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);
    private volatile boolean retired;

    private Minecraft121TemplateAccessGate(Object managerIdentity) {
        this.managerIdentity = new WeakReference<>(Objects.requireNonNull(managerIdentity, "manager identity 缺失"));
    }

    public static Minecraft121TemplateAccessGate forManager(Object manager) {
        Objects.requireNonNull(manager, "manager");
        synchronized (MANAGERS_MONITOR) {
            ManagerKey collected;
            while ((collected = (ManagerKey) MANAGERS_COLLECTED.poll()) != null) MANAGERS.remove(collected);
            var key = new ManagerKey(manager);
            var existing = MANAGERS.get(key);
            if (existing != null) return existing;
            var created = new Minecraft121TemplateAccessGate(manager);
            MANAGERS.put(new ManagerKey(manager, MANAGERS_COLLECTED), created);
            return created;
        }
    }

    public <T> T withManagerRead(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        lock.readLock().lock();
        try { return operation.get(); }
        finally { lock.readLock().unlock(); }
    }

    public void associate(StructureTemplate template) {
        Objects.requireNonNull(template, "template");
        lock.readLock().lock();
        try {
            synchronized (ASSOCIATIONS_MONITOR) {
                removeCollected();
                var existing = ASSOCIATIONS.get(new TemplateKey(template));
                if (existing != null) {
                    if (existing.ambiguous || existing.gate != this) {
                        existing.ambiguous = true;
                        throw new IllegalStateException("TEMPLATE_OWNER_AMBIGUOUS");
                    }
                    return;
                }
                ASSOCIATIONS.put(new TemplateKey(template, COLLECTED), new Association(this));
            }
        } finally { lock.readLock().unlock(); }
    }

    public static <T> T withTemplateRead(StructureTemplate template, Supplier<T> operation) {
        Objects.requireNonNull(template, "template");
        Objects.requireNonNull(operation, "operation");
        var gate = association(template);
        if (gate == null) return operation.get(); // 尚未發佈的 isolated instance。
        gate.lock.readLock().lock();
        try {
            if (association(template) != gate) {
                throw new IllegalStateException("TEMPLATE_OWNER_AMBIGUOUS");
            }
            return operation.get();
        } finally { gate.lock.readLock().unlock(); }
    }

    public boolean owns(StructureTemplate template) {
        return association(template) == this;
    }

    public Lease trySnapshot() {
        if (retired || managerIdentity.get() == null || lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread()) {
            throw new IllegalStateException("TEMPLATE_SNAPSHOT_NOT_READY");
        }
        var active = ACTIVE_SNAPSHOT.get();
        if (active != null && active != this) throw new IllegalStateException("TEMPLATE_SNAPSHOT_NOT_READY");
        long attemptStart = System.nanoTime();
        if (!lock.writeLock().tryLock()) throw new IllegalStateException("TEMPLATE_SNAPSHOT_NOT_READY");
        ACTIVE_SNAPSHOT.set(this);
        return new HeldSnapshot(this, attemptStart, Thread.currentThread());
    }

    public void retire() { retired = true; }

    private static Minecraft121TemplateAccessGate association(StructureTemplate template) {
        synchronized (ASSOCIATIONS_MONITOR) {
            removeCollected();
            var found = ASSOCIATIONS.get(new TemplateKey(template));
            if (found == null) return null;
            if (found.ambiguous) throw new IllegalStateException("TEMPLATE_OWNER_AMBIGUOUS");
            return found.gate;
        }
    }

    private static void removeCollected() {
        TemplateKey key;
        while ((key = (TemplateKey) COLLECTED.poll()) != null) ASSOCIATIONS.remove(key);
    }

    private static final class ManagerKey extends WeakReference<Object> {
        private final int hash;
        ManagerKey(Object manager) { super(manager); hash = System.identityHashCode(manager); }
        ManagerKey(Object manager, ReferenceQueue<Object> queue) {
            super(manager, queue); hash = System.identityHashCode(manager);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof ManagerKey key && get() != null && get() == key.get();
        }
    }

    private static final class TemplateKey extends WeakReference<StructureTemplate> {
        private final int hash;
        TemplateKey(StructureTemplate template) { super(template); hash = System.identityHashCode(template); }
        TemplateKey(StructureTemplate template, ReferenceQueue<StructureTemplate> queue) {
            super(template, queue); hash = System.identityHashCode(template);
        }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return this == other || other instanceof TemplateKey key && get() != null && get() == key.get();
        }
    }

    private static final class Association {
        private final Minecraft121TemplateAccessGate gate;
        private boolean ambiguous;
        Association(Minecraft121TemplateAccessGate gate) { this.gate = gate; }
    }

    public interface Lease extends AutoCloseable {
        void requireWithinBudget();
        @Override void close();
    }

    private static final class HeldSnapshot implements Lease {
        private final Minecraft121TemplateAccessGate gate;
        private final long start;
        private final Thread owner;
        private boolean closed;
        HeldSnapshot(Minecraft121TemplateAccessGate gate, long start, Thread owner) {
            this.gate = gate; this.start = start; this.owner = owner;
        }
        @Override public void requireWithinBudget() {
            if (closed || Thread.currentThread() != owner || gate.retired || System.nanoTime() - start > MAX_HOLD_NANOS) {
                throw new IllegalStateException("TEMPLATE_SNAPSHOT_NOT_READY");
            }
        }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != owner) throw new IllegalStateException("TEMPLATE_LEASE_WRONG_THREAD");
            closed = true;
            try { gate.lock.writeLock().unlock(); }
            finally {
                if (!gate.lock.isWriteLockedByCurrentThread()) ACTIVE_SNAPSHOT.remove();
            }
            if (gate.retired || System.nanoTime() - start > MAX_HOLD_NANOS) {
                throw new IllegalStateException("TEMPLATE_SNAPSHOT_NOT_READY");
            }
        }
    }
}
