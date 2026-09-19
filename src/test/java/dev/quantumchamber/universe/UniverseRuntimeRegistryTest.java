package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UniverseRuntimeRegistryTest {
    private final UniverseWorldDescriptor descriptor = new UniverseRegistry()
            .allocateOverworld(UUID.fromString("00000000-0000-0000-0000-000000000031"), 1)
            .definition().worlds().get(DimensionRole.OVERWORLD);
    private final EqualIdentity server = new EqualIdentity();
    private final EqualIdentity worldA = new EqualIdentity();
    private final EqualIdentity worldB = new EqualIdentity();

    @Test
    void replacementIsBlockedUntilExactOldInstanceIsReleased() {
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        runtime.beginMaterialize(server, descriptor);
        assertEquals(DynamicWorldRuntimeState.MATERIALIZING, runtime.state(server, descriptor));
        runtime.activate(server, descriptor, worldA);
        assertSame(worldA, runtime.resolveActive(server, descriptor).orElseThrow());
        assertThrows(IllegalStateException.class, () -> runtime.beginMaterialize(server, descriptor));
        assertThrows(IllegalStateException.class, () -> runtime.release(server, descriptor, worldA));
        assertThrows(IllegalArgumentException.class, () -> runtime.beginUnload(server, descriptor, worldB));
        runtime.beginUnload(server, descriptor, worldA);
        assertTrue(runtime.resolveActive(server, descriptor).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.release(server, descriptor, worldB));
        assertEquals(DynamicWorldRuntimeState.UNLOADING, runtime.state(server, descriptor));
        runtime.release(server, descriptor, worldA);
        assertEquals(DynamicWorldRuntimeState.ABSENT, runtime.state(server, descriptor));
        runtime.beginMaterialize(server, descriptor);
        runtime.activate(server, descriptor, worldB);
        assertThrows(IllegalArgumentException.class, () -> runtime.beginUnload(server, descriptor, worldA));
        assertSame(worldB, runtime.resolveActive(server, descriptor).orElseThrow());
    }

    @Test
    void equalButDifferentServersOwnIndependentRuntimeEntries() {
        var otherServer = new EqualIdentity();
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        runtime.beginMaterialize(server, descriptor);
        runtime.activate(server, descriptor, worldA);
        assertEquals(DynamicWorldRuntimeState.ABSENT, runtime.state(otherServer, descriptor));
        assertTrue(runtime.resolveActive(otherServer, descriptor).isEmpty());
        assertThrows(IllegalStateException.class, () -> runtime.beginUnload(otherServer, descriptor, worldA));
        runtime.beginMaterialize(otherServer, descriptor);
        runtime.activate(otherServer, descriptor, worldB);
        assertSame(worldA, runtime.resolveActive(server, descriptor).orElseThrow());
        assertSame(worldB, runtime.resolveActive(otherServer, descriptor).orElseThrow());
    }

    @Test
    void sameKeyWithChangedDescriptorCannotTakeOverOwnership() {
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        runtime.beginMaterialize(server, descriptor);
        var changed = new UniverseWorldDescriptor(descriptor.worldKey(), descriptor.role(),
                descriptor.generatorProfile(), descriptor.seedPolicy(), 2);
        assertThrows(IllegalArgumentException.class, () -> runtime.activate(server, changed, worldA));
        runtime.activate(server, descriptor, worldA);
        assertTrue(runtime.resolveActive(server, changed).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> runtime.beginUnload(server, changed, worldA));
    }

    @Test
    void failedMaterializationRetainsReceiptAndBlocksRetryInSameServer() {
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        runtime.beginMaterialize(server, descriptor);
        runtime.fail(server, descriptor, null);
        assertEquals(DynamicWorldRuntimeState.MATERIALIZE_FAILED, runtime.state(server, descriptor));
        assertThrows(IllegalStateException.class, () -> runtime.beginMaterialize(server, descriptor));
        assertThrows(IllegalStateException.class, () -> runtime.activate(server, descriptor, worldA));
        assertEquals(DynamicWorldRuntimeState.ABSENT, runtime.state(new EqualIdentity(), descriptor));
    }

    @Test
    void failedUnloadCannotBeReleasedOrReplacedAndRejectsStaleFailure() {
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        runtime.beginMaterialize(server, descriptor);
        runtime.activate(server, descriptor, worldA);
        runtime.beginUnload(server, descriptor, worldA);
        assertThrows(IllegalArgumentException.class, () -> runtime.fail(server, descriptor, worldB));
        runtime.fail(server, descriptor, worldA);
        assertEquals(DynamicWorldRuntimeState.UNLOAD_FAILED, runtime.state(server, descriptor));
        assertTrue(runtime.resolveActive(server, descriptor).isEmpty());
        assertThrows(IllegalStateException.class, () -> runtime.release(server, descriptor, worldA));
        assertThrows(IllegalStateException.class, () -> runtime.beginMaterialize(server, descriptor));
    }

    @Test
    void absentAndActiveStatesRejectOutOfOrderTransitions() {
        var runtime = new UniverseRuntimeRegistry<EqualIdentity, EqualIdentity>();
        assertThrows(IllegalStateException.class, () -> runtime.activate(server, descriptor, worldA));
        assertThrows(IllegalStateException.class, () -> runtime.fail(server, descriptor, null));
        runtime.beginMaterialize(server, descriptor);
        assertThrows(NullPointerException.class, () -> runtime.activate(server, descriptor, null));
        runtime.activate(server, descriptor, worldA);
        assertThrows(IllegalStateException.class, () -> runtime.activate(server, descriptor, worldB));
        assertThrows(IllegalStateException.class, () -> runtime.fail(server, descriptor, worldA));
    }

    // 故意讓 equals 相同，驗證 owner 與 expected-world guard 使用 instance identity。
    private static final class EqualIdentity {
        @Override public boolean equals(Object other) { return other instanceof EqualIdentity; }
        @Override public int hashCode() { return 1; }
    }
}
