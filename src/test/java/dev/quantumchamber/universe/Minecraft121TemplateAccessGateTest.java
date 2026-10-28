package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.datafixer.Schemas;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.resource.ResourceManager;
import net.minecraft.structure.StructureTemplateManager;
import net.minecraft.util.Identifier;
import net.minecraft.world.level.storage.LevelStorage;
import java.util.concurrent.TimeUnit;
import net.minecraft.structure.StructureTemplate;
import org.junit.jupiter.api.Test;

class Minecraft121TemplateAccessGateTest {
    @Test void snapshotRejectsBusyManagerWithoutWaiting() throws Exception {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var reader = new Thread(() -> gate.withManagerRead(() -> {
            entered.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            return null;
        }));
        reader.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            long start = System.nanoTime();
            var failure = assertThrows(IllegalStateException.class, gate::trySnapshot);
            assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", failure.getMessage());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 100);
        } finally {
            release.countDown();
            reader.join(5_000);
            assertFalse(reader.isAlive());
        }
    }

    @Test void sharedReaderCannotUpgradeToSnapshot() {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        gate.withManagerRead(() -> {
            var failure = assertThrows(IllegalStateException.class, gate::trySnapshot);
            assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", failure.getMessage());
            return null;
        });
    }

    @Test void exceptionReleasesManagerReadLock() {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        assertThrows(IllegalArgumentException.class, () -> gate.withManagerRead(() -> {
            throw new IllegalArgumentException("controlled");
        }));
        try (var lease = gate.trySnapshot()) {
            lease.requireWithinBudget();
        }
    }

    @Test void associatedTemplateCannotMoveToSecondManager() {
        var firstManager = new Object();
        var secondManager = new Object();
        var first = Minecraft121TemplateAccessGate.forManager(firstManager);
        var second = Minecraft121TemplateAccessGate.forManager(secondManager);
        var template = new StructureTemplate();
        first.associate(template);
        var failure = assertThrows(IllegalStateException.class, () -> second.associate(template));
        assertEquals("TEMPLATE_OWNER_AMBIGUOUS", failure.getMessage());
    }

    @Test void retiredGateKeepsOldTemplateMutatorWhileRejectingNewSnapshot() {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        var template = new StructureTemplate();
        gate.associate(template);
        gate.retire();
        assertEquals(42, Minecraft121TemplateAccessGate.withTemplateRead(template, () -> 42));
        var failure = assertThrows(IllegalStateException.class, gate::trySnapshot);
        assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", failure.getMessage());
    }

    @Test void managerLookupKeepsExactIdentity() {
        var first = new Object();
        var second = new Object();
        assertSame(Minecraft121TemplateAccessGate.forManager(first), Minecraft121TemplateAccessGate.forManager(first));
        assertNotSame(Minecraft121TemplateAccessGate.forManager(first), Minecraft121TemplateAccessGate.forManager(second));
    }

    @Test void nativeManagerAndTemplateMethodsUseSharedGate() throws Exception {
        SharedConstants.createGameVersion(); Bootstrap.initialize();
        var root = Files.createTempDirectory("qc-8b2-manager-");
        System.out.println("M6_8B2_GATE_FIXTURE_ROOT=" + root);
        try (var session = LevelStorage.create(root).createSession("fixture")) {
            var manager = new StructureTemplateManager(ResourceManager.Empty.INSTANCE, session,
                    Schemas.getFixer(), Registries.BLOCK.getReadOnlyWrapper());
            var gate = Minecraft121TemplateAccessGate.forManager(manager);
            var id = Identifier.of("quantumchamber:gate_probe");
            var blank = new StructureTemplate();
            var blankNbt = blank.writeNbt(new NbtCompound());
            assertSharedWrapper(gate, () -> manager.getTemplate(id));
            assertSharedWrapper(gate, () -> manager.getTemplateOrBlank(id));
            var cached = manager.getTemplateOrBlank(id);
            assertTrue(gate.owns(cached));
            assertSharedWrapper(gate, () -> manager.setResourceManager(ResourceManager.Empty.INSTANCE));
            assertSharedWrapper(gate, () -> manager.createTemplate(blankNbt));
            manager.getTemplateOrBlank(id); // setResourceManager 清除cache後重新建立正式entry。
            assertSharedWrapper(gate, () -> manager.saveTemplate(id));
            assertSharedWrapper(gate, () -> manager.unloadTemplate(id));
            gate.associate(blank);
            assertSharedWrapper(gate, () -> blank.setAuthor("probe"));
            assertSharedWrapper(gate, () -> blank.readNbt(Registries.BLOCK.getReadOnlyWrapper(), blankNbt));
            assertSharedWrapper(gate, () -> blank.saveFromWorld(null, null, null, false, null), true);
            try (var afterException = gate.trySnapshot()) { afterException.requireWithinBudget(); }
        }
    }

    private static void assertSharedWrapper(Minecraft121TemplateAccessGate gate, Runnable operation) throws Exception {
        assertSharedWrapper(gate, operation, false);
    }

    private static void assertSharedWrapper(Minecraft121TemplateAccessGate gate, Runnable operation,
            boolean expectBodyFailure) throws Exception {
        var entered = new CountDownLatch(1);
        var done = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();
        var lease = gate.trySnapshot();
        var thread = new Thread(() -> {
            entered.countDown();
            try { operation.run(); } catch (Throwable error) { failure.set(error); }
            finally { done.countDown(); }
        });
        thread.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(done.await(20, TimeUnit.MILLISECONDS), "shared method bypassed exclusive gate");
        } finally {
            try { lease.close(); } catch (IllegalStateException overBudget) {
                assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", overBudget.getMessage());
            }
        }
        assertTrue(done.await(5, TimeUnit.SECONDS));
        thread.join(5_000);
        assertFalse(thread.isAlive());
        if (expectBodyFailure) assertInstanceOf(NullPointerException.class, failure.get());
        else assertNull(failure.get(), "native method failed after shared gate released");
    }

    @Test void snapshotRejectsHoldLongerThanHundredMillis() throws Exception {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        var lease = gate.trySnapshot();
        Thread.sleep(150);
        var failure = assertThrows(IllegalStateException.class, lease::close);
        assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", failure.getMessage());
        try (var next = gate.trySnapshot()) { next.requireWithinBudget(); }
    }

    @Test void nestedWriteLeaseKeepsExclusiveUntilOuterClose() throws Exception {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        var outer = gate.trySnapshot();
        var inner = gate.trySnapshot();
        inner.close();
        var started = new CountDownLatch(1);
        var completed = new CountDownLatch(1);
        var worker = new Thread(() -> {
            started.countDown();
            gate.withManagerRead(() -> { completed.countDown(); return null; });
        });
        worker.start();
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(completed.await(20, TimeUnit.MILLISECONDS), "inner close released outer exclusive");
        } finally {
            try { outer.close(); } catch (IllegalStateException overBudget) {
                assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", overBudget.getMessage());
            }
        }
        assertTrue(completed.await(5, TimeUnit.SECONDS));
        worker.join(5_000);
        assertFalse(worker.isAlive());
    }

    @Test void gateConstructorIsNotPublicAlternativeLock() {
        assertThrows(NoSuchMethodException.class,
                () -> Minecraft121TemplateAccessGate.class.getConstructor(Object.class));
    }

    @Test void retiringAnOpenLeaseRejectsCloseAndReleasesLock() {
        var managerIdentity = new Object();
        var gate = Minecraft121TemplateAccessGate.forManager(managerIdentity);
        var template = new StructureTemplate();
        gate.associate(template);
        var lease = gate.trySnapshot();
        gate.retire();
        var failure = assertThrows(IllegalStateException.class, lease::close);
        assertEquals("TEMPLATE_SNAPSHOT_NOT_READY", failure.getMessage());
        assertEquals(7, Minecraft121TemplateAccessGate.withTemplateRead(template, () -> 7));
        assertThrows(IllegalStateException.class, gate::trySnapshot);
    }
}
