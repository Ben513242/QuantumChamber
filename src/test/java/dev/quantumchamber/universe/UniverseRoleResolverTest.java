package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class UniverseRoleResolverTest {
    private static final UUID FIRST = UUID.fromString("80000000-0000-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("80000000-0000-4000-8000-000000000002");
    private static final UUID THIRD = UUID.fromString("80000000-0000-4000-8000-000000000003");

    @Test
    void vanillaRolesResolveBeforeCatalogOrOwnerAccess() {
        var server = new Object();
        for (var expected : Map.of(World.OVERWORLD, DimensionRole.OVERWORLD,
                World.NETHER, DimensionRole.NETHER, World.END, DimensionRole.END).entrySet()) {
            assertEquals(expected.getValue(), UniverseRoleResolver.resolve(server, expected.getKey(),
                    () -> { throw new AssertionError("vanilla 不應存取 catalog"); }).orElseThrow());
        }
    }

    @Test
    void exactCatalogKeyResolvesEvenWhenDisabledButForeignAndNearMatchStayEmpty() {
        var server = new Object();
        var state = new UniverseRegistryState();
        var record = state.allocateOverworld(FIRST, 1);
        state.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        var scope = new UniverseRoleResolver.CatalogScope<>(server, () -> state);
        assertEquals(DimensionRole.OVERWORLD,
                UniverseRoleResolver.resolve(server, descriptor(record).worldKey(), () -> scope).orElseThrow());
        for (var key : List.of("foreign:overworld", "quantumchamber:superposition",
                "quantumchamber:universe/80000000-0000-4000-8000-000000000002/overworld",
                "quantumchamber:universe/80000000-0000-4000-8000-000000000001/nether")) {
            assertTrue(UniverseRoleResolver.resolve(server, worldKey(key), () -> scope).isEmpty(), key);
        }
        assertTrue(DimensionRole.fromVanillaKey(descriptor(record).worldKey()).isEmpty());
    }

    @Test
    void equalButDifferentServerOwnerCannotExposeCatalog() {
        var requested = new String("server");
        var owner = new String("server");
        assertTrue(UniverseRoleResolver.resolve(requested, worldKey("foreign:world"),
                () -> new UniverseRoleResolver.CatalogScope<>(owner,
                        () -> { throw new AssertionError("不屬於此 server 的 catalog 不得被讀取"); })).isEmpty());
    }

    @Test
    void corruptCatalogThrowsInsteadOfReturningUnknownRole() {
        var server = new Object();
        var corrupt = UniverseRegistryState.fromNbt(new NbtCompound());
        assertThrows(IllegalStateException.class, () -> UniverseRoleResolver.resolve(server,
                worldKey("foreign:world"), () -> new UniverseRoleResolver.CatalogScope<>(server, () -> corrupt)));
    }

    @Test
    void bootstrapValidatesEmptyCatalogAndStorageBeforeBecomingReady() {
        var access = new FakeBootstrap(new UniverseRegistryState());
        var result = UniverseLifecycleService.bootstrap(access);
        assertTrue(result.ready());
        assertEquals(List.of("catalog", "inventory"), access.events);
        assertTrue(result.retained().isEmpty());
    }

    @Test
    void bootstrapSortsOrdinalThenUuidAndNeverMaterializesDisabledRecords() {
        var state = new UniverseRegistryState();
        state.allocateOverworld(THIRD, 2);
        state.allocateOverworld(SECOND, 1);
        state.allocateOverworld(FIRST, 1);
        var disabled = state.allocateOverworld(UUID.fromString("80000000-0000-4000-8000-000000000004"), 0);
        state.changeAvailability(disabled.definition().universeId(), DesiredAvailability.DISABLED);
        var access = new FakeBootstrap(durable(state));
        var result = UniverseLifecycleService.bootstrap(access);
        assertTrue(result.ready());
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "create:3"), access.events);
        assertEquals(4, access.inventoryCount);
        assertEquals(3, access.live.size());
        assertEquals(3, result.retained().size());
    }

    @Test
    void secondFailureUnloadsFirstExactInstanceAndStops() {
        var access = threeWorlds();
        access.failCreate = 2;
        var result = UniverseLifecycleService.bootstrap(access);
        assertFalse(result.ready());
        assertSame(access.failure, result.failure());
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "unload:1", "stop"), access.events);
        assertTrue(access.live.isEmpty());
        assertTrue(result.retained().isEmpty());
    }

    @Test
    void thirdFailureCleansEarlierWorldsInReverseOrder() {
        var access = threeWorlds();
        access.failCreate = 3;
        var result = UniverseLifecycleService.bootstrap(access);
        assertFalse(result.ready());
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "create:3",
                "unload:2", "unload:1", "stop"), access.events);
        assertTrue(access.live.isEmpty());
    }

    @Test
    void cleanupFailureRetainsNativeOwnerContinuesEarlierCleanupAndStillStops() {
        var access = threeWorlds();
        access.failCreate = 3;
        access.failUnload = 2;
        var result = UniverseLifecycleService.bootstrap(access);
        assertFalse(result.ready());
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "create:3",
                "unload:2", "unload:1", "stop"), access.events);
        assertEquals(1, access.live.size());
        assertEquals(1, result.retained().size());
        assertSame(access.live.values().iterator().next(), result.retained().getFirst().world());
        assertSame(access.cleanupFailure, result.failure().getSuppressed()[0]);
    }

    @Test
    void orphanStorageStopsBeforeAnyMaterialization() {
        var access = threeWorlds();
        access.orphan = true;
        assertFalse(UniverseLifecycleService.bootstrap(access).ready());
        assertEquals(List.of("catalog", "inventory", "stop"), access.events);
        assertTrue(access.live.isEmpty());
    }

    @Test
    void corruptCatalogStopsBeforeStorageInspection() {
        var access = new FakeBootstrap(UniverseRegistryState.fromNbt(new NbtCompound()));
        assertFalse(UniverseLifecycleService.bootstrap(access).ready());
        assertEquals(List.of("catalog", "stop"), access.events);
    }

    @Test
    void unflushedDefinitionCannotReachBackend() {
        var state = new UniverseRegistryState();
        state.allocateOverworld(FIRST, 1);
        var access = new FakeBootstrap(state);
        assertFalse(UniverseLifecycleService.bootstrap(access).ready());
        assertEquals(List.of("catalog", "stop"), access.events);
        assertTrue(access.live.isEmpty());
    }

    @Test
    void fatalMaterializationStillCleansAndStopsBeforeRethrowingExactError() {
        var access = threeWorlds();
        access.failCreate = 2;
        access.fatal = new AssertionError("可控制的 fatal fixture");
        assertSame(access.fatal, assertThrows(AssertionError.class, () -> UniverseLifecycleService.bootstrap(access)));
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "unload:1", "stop"), access.events);
        assertTrue(access.live.isEmpty());
    }

    @Test
    void stopFailureCannotHideOriginalBootstrapFailure() {
        var access = threeWorlds();
        access.failCreate = 1;
        access.failStop = true;
        var result = UniverseLifecycleService.bootstrap(access);
        assertSame(access.failure, result.failure());
        assertSame(access.stopFailure, result.failure().getSuppressed()[0]);
        assertEquals(List.of("catalog", "inventory", "create:1", "stop"), access.events);
    }

    @Test
    void nativeShutdownReceiptPermitsDroppingOnlyTheExactServerContext() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        var world = new Object();
        var counts = new IdentityHashMap<Object, Integer>();
        counts.put(world, 1);
        assertDoesNotThrow(() -> UniverseLifecycleService.verifyStopped(
                List.of(new UniverseRuntimeRegistry.OwnedWorld<>(descriptor, DynamicWorldRuntimeState.ACTIVE, world)),
                List.of(), counts, key -> world, candidate -> candidate == world, null, List.of()));
    }

    @Test
    void missingOrDuplicateUnloadCannotBeMistakenForNativeCleanup() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        var world = new Object();
        for (int count : List.of(0, 2)) {
            var counts = new IdentityHashMap<Object, Integer>();
            counts.put(world, count);
            assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                    List.of(new UniverseRuntimeRegistry.OwnedWorld<>(descriptor, DynamicWorldRuntimeState.ACTIVE, world)),
                    List.of(), counts, key -> world, candidate -> true, null, List.of()));
        }
    }

    @Test
    void quarantineCannotBeClearedEvenWhenUnloadWasObserved() {
        var world = new Object();
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(), List.of(world), Map.of(world, 1), key -> null, candidate -> true, null, List.of()));
    }

    @Test
    void failedOwnerOutsideNativeMapCannotBeClearedByStoppedBookkeeping() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        var world = new Object();
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(new UniverseRuntimeRegistry.OwnedWorld<>(descriptor, DynamicWorldRuntimeState.UNLOAD_FAILED, world)),
                List.of(), Map.of(world, 1), key -> new Object(), candidate -> true, null, List.of()));
    }

    @Test
    void ongoingRuntimeOperationCannotBecomeAStoppedReceipt() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        var world = new Object();
        for (var state : List.of(DynamicWorldRuntimeState.MATERIALIZING, DynamicWorldRuntimeState.UNLOADING)) {
            assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                    List.of(new UniverseRuntimeRegistry.OwnedWorld<>(descriptor, state, world)),
                    List.of(), Map.of(world, 1), key -> world, candidate -> true, null, List.of()));
        }
    }

    @Test
    void differentServerWorldCannotSatisfyShutdownReceipt() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        var world = new Object();
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(new UniverseRuntimeRegistry.OwnedWorld<>(descriptor, DynamicWorldRuntimeState.ACTIVE, world)),
                List.of(), Map.of(world, 1), key -> world, candidate -> false, null, List.of()));
    }

    @Test
    void anUnownedMaterializeFailureWithoutRollbackProofCannotDetach() {
        var descriptor = descriptor(threeWorlds().state.find(new UniverseId(FIRST)).orElseThrow());
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(new UniverseRuntimeRegistry.OwnedWorld<Object>(descriptor, DynamicWorldRuntimeState.MATERIALIZE_FAILED, null)),
                List.of(), Map.of(), key -> null, candidate -> true, null, List.of()));
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(new UniverseRuntimeRegistry.OwnedWorld<Object>(descriptor, DynamicWorldRuntimeState.ACTIVE, null)),
                List.of(), Map.of(), key -> null, candidate -> true, null, List.of()));
    }

    @Test
    void releasedEarlyWorldStillRequiresItsSingleUnloadReceipt() {
        var oldWorld = new Object();
        assertDoesNotThrow(() -> UniverseLifecycleService.verifyStopped(
                List.of(), List.of(), Map.of(oldWorld, 1), key -> null, candidate -> true, null, List.of()));
        assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(), List.of(), Map.of(oldWorld, 0), key -> null, candidate -> true, null, List.of()));
    }

    @Test
    void stoppingCleanupExceptionCannotEscapeOrDropQuarantineReferences() {
        var first = new Object();
        var second = new Object();
        var quarantine = new ArrayList<>(List.of(first, second));
        var attempted = new ArrayList<Object>();
        var failure = new IllegalStateException("controlled quarantine failure");
        var receipt = assertDoesNotThrow(() -> UniverseLifecycleService.cleanupQuarantines(
                () -> List.copyOf(quarantine), world -> {
                    attempted.add(world);
                    if (world == first) throw failure;
                    quarantine.remove(world);
                }));
        assertSame(failure, receipt);
        assertEquals(List.of(first, second), attempted);
        assertEquals(List.of(first), quarantine);
    }

    @Test
    void stoppingCleanupErrorIsRecordedInsteadOfInterruptingNativeShutdown() {
        var world = new Object();
        var quarantine = new ArrayList<>(List.of(world));
        var fatal = new AssertionError("controlled quarantine fatal");
        var receipt = assertDoesNotThrow(() -> UniverseLifecycleService.cleanupQuarantines(
                () -> List.copyOf(quarantine), ignored -> { throw fatal; }));
        assertSame(fatal, receipt);
        assertEquals(List.of(world), quarantine);
    }

    @Test
    void stoppingInventoryFailureAlsoBecomesAnObservableReceipt() {
        var fatal = new AssertionError("controlled inventory failure");
        var receipt = assertDoesNotThrow(() -> UniverseLifecycleService.cleanupQuarantines(
                () -> { throw fatal; }, ignored -> fail("無 inventory 不得碰 world")));
        assertSame(fatal, receipt);
    }

    @Test
    void stoppingFailuresKeepFatalIdentityAndEarlierCauseWithoutEscaping() {
        var first = new Object();
        var second = new Object();
        var exception = new IllegalStateException("first failure");
        var fatal = new AssertionError("second fatal");
        var receipt = assertDoesNotThrow(() -> UniverseLifecycleService.cleanupQuarantines(
                () -> List.of(first, second), world -> {
                    if (world == first) throw exception;
                    throw fatal;
                }));
        assertSame(fatal, receipt);
        assertSame(exception, receipt.getSuppressed()[0]);
    }

    @Test
    void stoppingFailurePreventsFalseDetachEvenWhenBackendSnapshotIsAlreadyEmpty() {
        var fatal = new AssertionError("cleanup completed but observer failed");
        var failure = assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(
                List.of(), List.of(), Map.of(), key -> null, candidate -> true, fatal, List.of()));
        assertSame(fatal, failure.getCause());
    }

    @Test
    void unhealthyNullWorldResultRetainsContextEvenWithEmptyQuarantineAndUnloadReceipts() {
        var access = oneWorld();
        access.failCreate = 1;
        access.failedResult = new MaterializeResult(MaterializeResult.Status.FAILED_UNHEALTHY, null);
        var result = UniverseLifecycleService.bootstrap(access);
        assertFalse(result.ready());
        assertTrue(access.live.isEmpty() && access.unloads.isEmpty());
        var runtime = access.runtime.snapshot(access.server).getFirst();
        assertEquals(DynamicWorldRuntimeState.MATERIALIZE_FAILED, runtime.state());
        assertNull(runtime.world());
        var failure = assertThrows(IllegalStateException.class, access::verifyAndDetach);
        assertSame(access, access.contexts.get(access.server));
        assertSame(result.failure(), failure.getCause());
        var recorded = access.materializeFailures.getFirst();
        assertEquals(MaterializeResult.Status.FAILED_UNHEALTHY, recorded.status());
        assertTrue(recorded.detail().contains("FAILED_UNHEALTHY"));
        assertSame(result.failure(), recorded.cause());
    }

    @Test
    void rolledBackNullWorldResultCanDetachOnlyWithItsRecordedBackendProof() {
        var access = oneWorld();
        access.failCreate = 1;
        access.failedResult = new MaterializeResult(MaterializeResult.Status.FAILED_ROLLED_BACK, null);
        var result = UniverseLifecycleService.bootstrap(access);
        assertFalse(result.ready());
        assertDoesNotThrow(access::verifyAndDetach);
        assertFalse(access.contexts.containsKey(access.server));
        assertEquals(MaterializeResult.Status.FAILED_ROLLED_BACK, access.materializeFailures.getFirst().status());
        assertSame(result.failure(), access.materializeFailures.getFirst().cause());
    }

    @Test
    void unknownMaterializeExceptionCannotTurnAnUnownedFailureIntoSafeCleanup() {
        var access = oneWorld();
        access.failCreate = 1;
        var result = UniverseLifecycleService.bootstrap(access);
        assertThrows(IllegalStateException.class, access::verifyAndDetach);
        assertSame(access, access.contexts.get(access.server));
        assertNull(access.materializeFailures.getFirst().status());
        assertSame(result.failure(), access.materializeFailures.getFirst().cause());
    }

    @Test
    void fatalMaterializeExceptionKeepsItsContextFailureReceiptAfterRethrow() {
        var access = oneWorld();
        access.failCreate = 1;
        access.fatal = new AssertionError("可控制的 backend fatal");
        assertSame(access.fatal, assertThrows(AssertionError.class, () -> UniverseLifecycleService.bootstrap(access)));
        assertThrows(IllegalStateException.class, access::verifyAndDetach);
        assertSame(access, access.contexts.get(access.server));
        assertSame(access.fatal, access.materializeFailures.getFirst().cause());
    }

    @Test
    void secondUnhealthyResultStillRetainsContextAfterFirstWorldWasSafelyUnloaded() {
        var access = threeWorlds();
        access.failCreate = 2;
        access.failedResult = new MaterializeResult(MaterializeResult.Status.FAILED_UNHEALTHY, null);
        var result = UniverseLifecycleService.bootstrap(access);
        assertEquals(List.of("catalog", "inventory", "create:1", "create:2", "unload:1", "stop"), access.events);
        assertTrue(result.retained().isEmpty() && access.live.isEmpty());
        assertEquals(List.of(1), List.copyOf(access.unloads.values()));
        assertThrows(IllegalStateException.class, access::verifyAndDetach);
        assertSame(access, access.contexts.get(access.server));
        assertEquals(1, access.materializeFailures.size());
        assertEquals(MaterializeResult.Status.FAILED_UNHEALTHY, access.materializeFailures.getFirst().status());
    }

    @Test
    void secondUnhealthyResultAndFirstCleanupFailureKeepBothCausesAndNativeOwner() {
        var access = threeWorlds();
        access.failCreate = 2;
        access.failUnload = 1;
        access.failedResult = new MaterializeResult(MaterializeResult.Status.FAILED_UNHEALTHY, null);
        var result = UniverseLifecycleService.bootstrap(access);
        assertEquals(1, result.retained().size());
        assertSame(access.cleanupFailure, result.failure().getSuppressed()[0]);
        // 模擬第一筆後續由 native shutdown 完成，仍不能掩蓋第二筆無 close 證據。
        access.live.values().forEach(world -> access.unloads.put(world, 1));
        assertThrows(IllegalStateException.class, access::verifyAndDetach);
        assertSame(access, access.contexts.get(access.server));
        assertSame(result.retained().getFirst().world(), access.live.values().iterator().next());
        assertSame(result.failure(), access.materializeFailures.getFirst().cause());
    }

    private static FakeBootstrap oneWorld() {
        var state = new UniverseRegistryState();
        state.allocateOverworld(FIRST, 1);
        return new FakeBootstrap(durable(state));
    }

    private static FakeBootstrap threeWorlds() {
        var state = new UniverseRegistryState();
        state.allocateOverworld(THIRD, 3);
        state.allocateOverworld(FIRST, 1);
        state.allocateOverworld(SECOND, 2);
        return new FakeBootstrap(durable(state));
    }

    private static UniverseRegistryState durable(UniverseRegistryState state) {
        return UniverseRegistryState.fromNbt(state.writeNbt(new NbtCompound()));
    }

    private static UniverseWorldDescriptor descriptor(UniverseRecord record) {
        return record.definition().worlds().get(DimensionRole.OVERWORLD);
    }

    private static RegistryKey<World> worldKey(String value) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of(value));
    }

    private static final class FakeBootstrap implements UniverseLifecycleService.BootstrapAccess<Object> {
        final UniverseRegistryState state;
        final List<String> events = new ArrayList<>();
        final Map<UniverseWorldDescriptor, Object> live = new LinkedHashMap<>();
        final Object server = new Object();
        final UniverseRuntimeRegistry<Object, Object> runtime = new UniverseRuntimeRegistry<>();
        final Map<Object, Integer> unloads = new IdentityHashMap<>();
        final Map<Object, FakeBootstrap> contexts = new IdentityHashMap<>();
        final List<UniverseLifecycleService.MaterializeFailure> materializeFailures = new ArrayList<>();
        final RuntimeException failure = new IllegalStateException("物化失敗 fixture");
        final RuntimeException cleanupFailure = new IllegalStateException("清理失敗 fixture");
        final RuntimeException stopFailure = new IllegalStateException("stop 失敗 fixture");
        int failCreate;
        int failUnload;
        int inventoryCount;
        boolean orphan;
        boolean failStop;
        AssertionError fatal;
        MaterializeResult failedResult;

        FakeBootstrap(UniverseRegistryState state) {
            this.state = state;
            contexts.put(server, this);
        }

        @Override public UniverseRegistryState catalog() {
            events.add("catalog");
            return state;
        }

        @Override public UniverseRegistryState.StorageInventory inspectStorage(Collection<UniverseRecord> records) {
            events.add("inventory");
            inventoryCount = records.size();
            return orphan ? UniverseRegistryState.StorageInventory.ORPHANED : UniverseRegistryState.StorageInventory.READY;
        }

        @Override public Object materialize(UniverseRecord record, UniverseWorldDescriptor descriptor) {
            int id = id(descriptor);
            events.add("create:" + id);
            runtime.beginMaterialize(server, descriptor);
            if (id == failCreate) {
                runtime.fail(server, descriptor, null);
                if (failedResult != null) {
                    return UniverseLifecycleService.materializedWorld(descriptor, failedResult.status(), failedResult.world());
                }
                if (fatal != null) throw fatal;
                throw failure;
            }
            var world = new Object();
            assertNull(live.putIfAbsent(descriptor, world));
            runtime.activate(server, descriptor, world);
            unloads.put(world, 0);
            return world;
        }

        @Override public void recordMaterializeFailure(UniverseLifecycleService.MaterializeFailure failure) {
            materializeFailures.add(failure);
        }

        @Override public void unload(UniverseWorldDescriptor descriptor, Object expected) {
            int id = id(descriptor);
            events.add("unload:" + id);
            assertSame(expected, live.get(descriptor));
            if (id == failUnload) throw cleanupFailure;
            runtime.beginUnload(server, descriptor, expected);
            assertTrue(live.remove(descriptor, expected));
            unloads.put(expected, 1);
            runtime.release(server, descriptor, expected);
        }

        void verifyAndDetach() {
            UniverseLifecycleService.detachAfterVerification(contexts, server, this,
                    () -> UniverseLifecycleService.verifyStopped(runtime.snapshot(server), List.of(), unloads,
                            live::get, world -> true, null, materializeFailures));
        }

        @Override public void stop() {
            events.add("stop");
            if (failStop) throw stopFailure;
        }

        private static int id(UniverseWorldDescriptor descriptor) {
            return Integer.parseInt(descriptor.worldKey().getValue().getPath().split("/")[1].substring(35));
        }
    }
}
