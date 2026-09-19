package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniverseRegistryStateTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @TempDir
    Path root;

    @BeforeAll
    static void initializeNativeVersion() {
        net.minecraft.SharedConstants.createGameVersion();
    }

    @Test
    void corruptExistingCatalogNeverFallsBackToEmpty() throws Exception {
        var target = root.resolve("quantumchamber_universes.dat");
        Files.writeString(target, "not nbt");

        var loaded = UniverseRegistryState.load(target);

        assertThrows(IllegalStateException.class, loaded::requireHealthy);
        assertThrows(IllegalStateException.class, loaded::records);
    }

    @Test
    void currentAndFlushedAuthorityAdvanceOnlyAfterCheckedWrite() throws Exception {
        var target = root.resolve("quantumchamber_universes.dat");
        var state = new UniverseRegistryState();

        var allocated = state.allocateOverworld(ID, 7);
        assertTrue(state.isDirty());
        assertTrue(state.flushedRecords().isEmpty());

        state.save(target);

        assertFalse(state.isDirty());
        assertEquals(allocated, state.flushedRecords().get(UniverseId.of(ID)));
        var loaded = UniverseRegistryState.load(target);
        loaded.requireHealthy();
        assertEquals(state.records(), loaded.records());
        assertEquals(loaded.records(), loaded.flushedRecords());

        state.changeAvailability(UniverseId.of(ID), DesiredAvailability.DISABLED);
        assertEquals(DesiredAvailability.EAGER_ENABLED,
                state.flushedRecords().get(UniverseId.of(ID)).desiredAvailability());
        assertThrows(UncheckedIOException.class, () -> state.save(target.resolve("child.dat")));
        assertTrue(state.isDirty());
        assertEquals(DesiredAvailability.EAGER_ENABLED,
                state.flushedRecords().get(UniverseId.of(ID)).desiredAvailability());
        assertEquals(DesiredAvailability.EAGER_ENABLED,
                UniverseRegistryState.load(target).records().get(UniverseId.of(ID)).desiredAvailability());
    }

    @Test
    void duplicateCatalogIdentityFailsClosedWithoutPartialRecords() throws Exception {
        var state = new UniverseRegistryState();
        state.allocateOverworld(ID, 7);
        var data = state.writeNbt(new NbtCompound());
        data.getList("Records", 10).add(data.getList("Records", 10).getCompound(0).copy());
        var wrapper = new NbtCompound();
        wrapper.put("data", data);
        NbtHelper.putDataVersion(wrapper);
        var target = root.resolve("duplicate.dat");
        NbtIo.writeCompressed(wrapper, target);

        var loaded = UniverseRegistryState.load(target);

        assertThrows(IllegalStateException.class, loaded::requireHealthy);
        assertThrows(IllegalStateException.class, loaded::flushedRecords);
    }

    @Test
    void nonCompoundRecordListNeverDegradesToAnEmptyCatalog() {
        var data = new NbtCompound();
        data.putInt("SchemaVersion", 1);
        var records = new NbtList();
        records.add(NbtInt.of(7));
        data.put("Records", records);

        var loaded = UniverseRegistryState.fromNbt(data);

        assertThrows(IllegalStateException.class, loaded::requireHealthy);
        assertThrows(IllegalStateException.class, loaded::records);
    }

    @Test
    void pathInventoryAllowsKnownStorageAndMarksUnknownOwnedStorageOrphaned() throws Exception {
        var state = new UniverseRegistryState();
        var record = state.allocateOverworld(ID, 7);
        var records = state.records().values();

        var noRegionSave = root.resolve("no-region");
        assertEquals(UniverseRegistryState.StorageInventory.READY,
                UniverseRegistryState.inspectStorage(noRegionSave, records));

        var knownStorage = root.resolve("known-storage")
                .resolve("dimensions/quantumchamber/universe")
                .resolve(ID.toString())
                .resolve("overworld/region");
        Files.createDirectories(knownStorage);
        Files.write(knownStorage.resolve("r.0.0.mca"), new byte[] {1});
        assertEquals(UniverseRegistryState.StorageInventory.READY,
                UniverseRegistryState.inspectStorage(root.resolve("known-storage"), records));

        var orphanId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        var orphanStorage = root.resolve("orphan-storage")
                .resolve("dimensions/quantumchamber/universe")
                .resolve(orphanId.toString())
                .resolve("overworld/region");
        Files.createDirectories(orphanStorage);
        var orphanRegion = orphanStorage.resolve("r.0.0.mca");
        Files.write(orphanRegion, new byte[] {1});

        assertEquals(UniverseRegistryState.StorageInventory.ORPHANED,
                UniverseRegistryState.inspectStorage(root.resolve("orphan-storage"), records));
        assertTrue(Files.exists(orphanRegion), "盤點不得刪除 orphan storage");
        assertEquals(record, state.find(UniverseId.of(ID)).orElseThrow());
    }
}
