package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
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
    void zeroLengthRecordListAcceptsOnlyCanonicalEndHeldType() throws Exception {
        var canonical = new NbtCompound();
        canonical.putInt("SchemaVersion", 1);
        canonical.put("Records", new NbtList());
        UniverseRegistryState.fromNbt(canonical).requireHealthy();

        for (byte heldType = NbtElement.BYTE_TYPE; heldType <= NbtElement.LONG_ARRAY_TYPE; heldType++) {
            var malformed = catalogWithZeroLengthRecords(heldType);
            var records = (NbtList) malformed.get("Records");
            assertTrue(records.isEmpty());
            assertEquals(heldType, records.getHeldType());

            var loaded = UniverseRegistryState.fromNbt(malformed);

            assertThrows(IllegalStateException.class, loaded::requireHealthy,
                    "zero-length Records 宣告 held type " + heldType + " 時必須 fail closed");
            assertThrows(IllegalStateException.class, loaded::records);
        }
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

    /**
     * M5 F2c（spec §6.2、決策 9 的 store 層注入）：catalog 配置的 atomic move 已完成、讀回失敗時，M3 的 save 本來就是 roll-forward——
     * current 保留這筆配置並維持 dirty、flushed 不前進；下一次寫出（這裡用原生 autosave 的入口）帶著它。
     */
    @Test
    void allocationReadbackFailureAfterTheMoveKeepsCurrentAndDirtyAndTheNextWriteCarriesIt() throws Exception {
        var target = root.resolve("quantumchamber_universes.dat");
        var readbacks = new java.util.ArrayList<Path>();
        var failing = new boolean[] {true};
        var state = new UniverseRegistryState(path -> {
            readbacks.add(path);
            if (failing[0]) {
                failing[0] = false;
                throw new java.io.IOException("注入：atomic move 完成、讀回失敗");
            }
            return UniverseCatalogStore.read(path);
        });
        var allocated = state.allocateOverworld(ID, 0);

        assertThrows(UncheckedIOException.class, () -> state.save(target));

        assertEquals(java.util.List.of(target.toAbsolutePath().normalize()), readbacks, "讀回發生在 atomic move 之後");
        assertTrue(state.isDirty(), "保留 dirty");
        assertEquals(java.util.Map.of(UniverseId.of(ID), allocated), state.records(), "current 保留這筆配置");
        assertTrue(state.flushedRecords().isEmpty(), "flushed 不前進");
        assertEquals(java.util.Map.of(UniverseId.of(ID), allocated), UniverseRegistryState.load(target).records(),
                "atomic move 已完成：配置已落地");

        state.save(target.toFile(), null);

        assertFalse(state.isDirty());
        assertEquals(java.util.Map.of(UniverseId.of(ID), allocated), state.flushedRecords(), "下一次寫出帶著它");
        assertEquals(state.records(), UniverseRegistryState.load(target).records());
    }

    private static NbtCompound catalogWithZeroLengthRecords(byte heldType) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeByte(NbtElement.COMPOUND_TYPE);
            output.writeUTF("");
            output.writeByte(NbtElement.INT_TYPE);
            output.writeUTF("SchemaVersion");
            output.writeInt(1);
            output.writeByte(NbtElement.LIST_TYPE);
            output.writeUTF("Records");
            output.writeByte(heldType);
            output.writeInt(0);
            output.writeByte(NbtElement.END_TYPE);
        }
        try (var input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return NbtIo.readCompound(input, NbtSizeTracker.ofUnlimitedBytes());
        }
    }
}
