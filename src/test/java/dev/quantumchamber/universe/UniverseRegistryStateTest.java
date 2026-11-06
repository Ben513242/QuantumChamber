package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void checkedSchemaTwoEnvelopePreservesExistingRecordOrderAndContents() throws Exception {
        var original = new UniverseRegistryState();
        original.allocateOverworld(ID, 17);
        original.allocateOverworld(new UUID(0, 2), 4);
        var encoded = original.writeNbt(new NbtCompound());
        var old = encoded.copy();
        var records = old.getList("Records", net.minecraft.nbt.NbtElement.COMPOUND_TYPE);
        var first = records.remove(0);
        records.add(first);
        var state = UniverseRegistryState.fromNbt(old);
        assertTrue(state.upgradeEnvelope());
        var file = root.resolve("mixed.dat");
        state.save(file);
        var disk = UniverseCatalogStore.read(file).getCompound("data");
        assertEquals(2, disk.getInt("SchemaVersion"));
        assertEquals(old.get("Records"), disk.get("Records"));
        var reloaded = UniverseRegistryState.load(file);
        assertFalse(reloaded.upgradeEnvelope());
        assertEquals(disk, reloaded.writeNbt(new NbtCompound()));
        assertFalse(reloaded.isDirty());
    }

    @Test
    void schemaTwoUpgradePreservesLegalLegacyRecordAndRoleExtras() throws Exception {
        org.junit.jupiter.api.Assertions.assertAll(
                () -> preservesLegalLegacyExtra(false),
                () -> preservesLegalLegacyExtra(true));
    }

    private void preservesLegalLegacyExtra(boolean onRole) throws Exception {
        var original = new UniverseRegistryState();
        original.allocateOverworld(ID, 17);
        original.allocateOverworld(new UUID(0, 2), 4);
        var legacy = original.writeNbt(new NbtCompound());
        var rows = legacy.getList("Records", NbtElement.COMPOUND_TYPE);
        var first = rows.remove(0);
        rows.add(first);
        var extension = rows.getCompound(0);
        if (onRole) extension = extension.getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0);
        extension.putString("Extra", "既有有效擴充");
        var before0 = rowBytes(rows.getCompound(0));
        var before1 = rowBytes(rows.getCompound(1));
        var state = UniverseRegistryState.fromNbt(legacy);
        state.requireHealthy();
        assertTrue(state.upgradeEnvelope());
        var file = root.resolve(onRole ? "role-extra.dat" : "record-extra.dat");
        state.save(file);
        var envelope = UniverseCatalogStore.read(file).getCompound("data");
        assertEquals(2, envelope.getInt("SchemaVersion"));
        assertArrayEquals(before0, rowBytes(envelope.getList("Records", NbtElement.COMPOUND_TYPE).getCompound(0)));
        assertArrayEquals(before1, rowBytes(envelope.getList("Records", NbtElement.COMPOUND_TYPE).getCompound(1)));

        var reloaded = UniverseRegistryState.load(file);
        reloaded.requireHealthy();
        var candidate = m6CatalogFixture();
        reloaded.allocateM6(candidate);
        reloaded.save(file);
        var finalState = UniverseRegistryState.load(file);
        finalState.requireHealthy();
        var mixed = finalState.writeNbt(new NbtCompound());
        var finalRows = mixed.getList("Records", NbtElement.COMPOUND_TYPE);
        assertEquals(3, finalRows.size());
        assertEquals(rows.getCompound(0), finalRows.getCompound(0));
        assertEquals(rows.getCompound(1), finalRows.getCompound(1));
        assertArrayEquals(before0, rowBytes(finalRows.getCompound(0)));
        assertArrayEquals(before1, rowBytes(finalRows.getCompound(1)));
        var retained = finalRows.getCompound(0);
        if (onRole) retained = retained.getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0);
        assertEquals(NbtElement.STRING_TYPE, retained.get("Extra").getType());
        assertEquals("既有有效擴充", retained.getString("Extra"));
        assertEquals(candidate.toNbt(), finalRows.getCompound(2));
        assertEquals(candidate, finalState.flushedRecords().get(candidate.definition().universeId()));

        var extraRecord = mixed.copy();
        extraRecord.getList("Records", NbtElement.COMPOUND_TYPE).getCompound(2).putString("Extra", "拒絕");
        assertThrows(IllegalStateException.class, () -> UniverseRegistryState.fromNbt(extraRecord).requireHealthy());
        var extraRole = mixed.copy();
        extraRole.getList("Records", NbtElement.COMPOUND_TYPE).getCompound(2)
                .getList("Worlds", NbtElement.COMPOUND_TYPE).getCompound(0).putString("Extra", "拒絕");
        assertThrows(IllegalStateException.class, () -> UniverseRegistryState.fromNbt(extraRole).requireHealthy());
    }

    private static byte[] rowBytes(NbtCompound row) throws Exception {
        // compound key 的物理迭代順序不屬於 NBT 語意；canonical bytes 保留型別、全部欄位與 list 次序。
        return Minecraft121SettingsGraph.canonicalNbt(row, 1024 * 1024);
    }

    private static UniverseRecord m6CatalogFixture() {
        var snapshot = new NbtCompound();
        snapshot.putInt("SnapshotVersion", 1);
        snapshot.putString("ProviderId", "quantumchamber:vanilla_normal_v1");
        snapshot.putInt("ProviderVersion", 1);
        var parameters = new NbtCompound();
        parameters.putString("OverworldPreset", "normal");
        parameters.putBoolean("GenerateStructures", true);
        snapshot.put("Parameters", parameters);
        snapshot.putByteArray("OutputFingerprint", new byte[32]);
        var settings = GenerationSettingsSnapshot.fromNbt(snapshot);
        var token = new dev.quantumchamber.candidate.CandidateBytes(new byte[32]);
        var intent = new dev.quantumchamber.candidate.NewUniverseIntent(token, token, 1,
                dev.quantumchamber.candidate.NewUniverseIntent.RANDOM_PROFILE_ID, 1, 42L, settings, settings, 0);
        var id = dev.quantumchamber.collapse.UniverseAllocationLedger.deriveUniverseId(token);
        return UniverseRegistry.newM6(id, 18, intent);
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
