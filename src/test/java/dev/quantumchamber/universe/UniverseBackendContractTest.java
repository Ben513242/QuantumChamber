package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniverseBackendContractTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000032");
    @TempDir Path root;

    @BeforeAll
    static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }

    @Test
    void dirtyAllocationCannotCallBackendAndSuccessfulFlushPrecedesMaterialization() {
        var catalog = new UniverseRegistryState();
        var record = catalog.allocateOverworld(ID, 1);
        var descriptor = descriptor(record);
        var backend = new FakeBackend();
        assertEquals("REJECTED", checked(catalog, record, descriptor, backend));
        assertTrue(backend.serviceEvents.isEmpty());
        catalog.save(root.resolve("catalog.dat"));
        backend.serviceEvents.add("CATALOG_FLUSHED");
        assertEquals("MATERIALIZED", checked(catalog, record, descriptor, backend));
        assertEquals(List.of("CATALOG_FLUSHED", "BACKEND_CALLED"), backend.serviceEvents);
        assertEquals(List.of("CONSTRUCTED", "PUBLISHED", "LOAD", "ACTIVE"), backend.events);
        assertSame(backend.world, backend.runtime.resolveActive(backend.server, descriptor).orElseThrow());
    }

    @Test
    void exactFlushedRecordAndDescriptorAreRequiredNotOnlyTheirKey() {
        var catalog = new UniverseRegistryState();
        var record = catalog.allocateOverworld(ID, 1);
        catalog.save(root.resolve("catalog.dat"));
        var descriptor = descriptor(record);
        var backend = new FakeBackend();
        var changedDefinition = new UniverseDefinition(1, record.definition().universeId(), 2,
                record.definition().worlds());
        assertEquals("REJECTED", checked(catalog,
                new UniverseRecord(changedDefinition, DesiredAvailability.EAGER_ENABLED), descriptor, backend));
        var changedDescriptor = new UniverseWorldDescriptor(descriptor.worldKey(), descriptor.role(),
                descriptor.generatorProfile(), descriptor.seedPolicy(), 2);
        assertEquals("REJECTED", checked(catalog, record, changedDescriptor, backend));
        var disabled = catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        assertEquals("REJECTED", checked(catalog, disabled, descriptor, backend));
        assertTrue(backend.serviceEvents.isEmpty());
    }

    @Test
    void flushedDisabledRecordCannotBeOverriddenByDirtyEnabledRecord() {
        var catalog = new UniverseRegistryState();
        var record = catalog.allocateOverworld(ID, 1);
        var disabled = catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        catalog.save(root.resolve("catalog.dat"));
        var enabled = catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.EAGER_ENABLED);
        var backend = new FakeBackend();
        assertEquals("REJECTED", checked(catalog, disabled, descriptor(disabled), backend));
        assertEquals("REJECTED", checked(catalog, enabled, descriptor(enabled), backend));
        assertTrue(backend.serviceEvents.isEmpty());
    }

    @Test
    void corruptCatalogFailsClosedBeforeBackendCall() {
        var healthy = new UniverseRegistry();
        var record = healthy.allocateOverworld(ID, 1);
        var corrupt = UniverseRegistryState.fromNbt(new NbtCompound());
        var backend = new FakeBackend();
        assertThrows(IllegalStateException.class, () -> checked(corrupt, record, descriptor(record), backend));
        assertTrue(backend.serviceEvents.isEmpty());
    }

    @Test
    void rollbackRecordsFailureOnlyAfterExactRemoveAndClose() {
        var catalog = new UniverseRegistryState();
        var record = catalog.allocateOverworld(ID, 1);
        catalog.save(root.resolve("catalog.dat"));
        var backend = new FakeBackend();
        backend.rollback = true;
        assertEquals("FAILED_ROLLED_BACK", checked(catalog, record, descriptor(record), backend));
        assertEquals(List.of("CONSTRUCTED", "PUBLISHED", "LOAD", "UNLOAD", "EXPECTED_REMOVE",
                "CLOSE", "FAILED_ROLLED_BACK"), backend.events);
        assertNull(backend.published);
        assertEquals(DynamicWorldRuntimeState.MATERIALIZE_FAILED,
                backend.runtime.state(backend.server, descriptor(record)));
    }

    @Test
    void rollbackNeverRemovesUnexpectedReplacement() {
        var catalog = new UniverseRegistryState();
        var record = catalog.allocateOverworld(ID, 1);
        catalog.save(root.resolve("catalog.dat"));
        var backend = new FakeBackend();
        backend.rollback = true;
        backend.replaceBeforeRollback = true;
        assertEquals("FAILED_UNHEALTHY", checked(catalog, record, descriptor(record), backend));
        assertSame(backend.replacement, backend.published);
        assertFalse(backend.events.contains("CLOSE"));
        assertEquals(DynamicWorldRuntimeState.MATERIALIZE_FAILED,
                backend.runtime.state(backend.server, descriptor(record)));
    }

    @Test
    void successResultsCannotOmitExactWorld() {
        assertThrows(NullPointerException.class,
                () -> new MaterializeResult(MaterializeResult.Status.MATERIALIZED, null));
        assertThrows(NullPointerException.class,
                () -> new MaterializeResult(MaterializeResult.Status.ALREADY_ACTIVE, null));
        for (var status : List.of(MaterializeResult.Status.REJECTED,
                MaterializeResult.Status.FAILED_ROLLED_BACK, MaterializeResult.Status.FAILED_UNHEALTHY)) {
            assertNull(new MaterializeResult(status, null).world());
        }
    }

    private static String checked(UniverseRegistryState catalog, UniverseRecord record,
            UniverseWorldDescriptor descriptor, FakeBackend backend) {
        return UniverseMaterializationService.materializeChecked(catalog, record, descriptor,
                () -> backend.materialize(descriptor), "REJECTED");
    }

    private static UniverseWorldDescriptor descriptor(UniverseRecord record) {
        return record.definition().worlds().get(DimensionRole.OVERWORLD);
    }

    // 只模擬原生資源邊界；catalog authority 與 runtime state 使用正式實作。
    private static final class FakeBackend {
        final Object server = new Object();
        final Object world = new Object();
        final Object replacement = new Object();
        final UniverseRuntimeRegistry<Object, Object> runtime = new UniverseRuntimeRegistry<>();
        final List<String> serviceEvents = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        Object published;
        boolean rollback;
        boolean replaceBeforeRollback;

        String materialize(UniverseWorldDescriptor descriptor) {
            serviceEvents.add("BACKEND_CALLED");
            runtime.beginMaterialize(server, descriptor);
            events.add("CONSTRUCTED");
            if (published != null) { throw new IllegalStateException("既有 world 不得覆寫"); }
            published = world;
            events.add("PUBLISHED");
            events.add("LOAD");
            if (!rollback) {
                runtime.activate(server, descriptor, world);
                events.add("ACTIVE");
                return "MATERIALIZED";
            }
            if (replaceBeforeRollback) { published = replacement; }
            events.add("UNLOAD");
            if (published != world) {
                runtime.fail(server, descriptor, null);
                return "FAILED_UNHEALTHY";
            }
            published = null;
            events.add("EXPECTED_REMOVE");
            events.add("CLOSE");
            runtime.fail(server, descriptor, null);
            events.add("FAILED_ROLLED_BACK");
            return "FAILED_ROLLED_BACK";
        }
    }
}
