package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.candidate.CandidateResolverTest.record;
import static dev.quantumchamber.candidate.CandidateResolverTest.universe;
import dev.quantumchamber.universe.DimensionRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.*;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UniverseDiscoveryStateTest {
    @TempDir Path root;
    private static final SourceFamilyRef VANILLA = new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD);

    @Test void firstCreationIsEmptyEvenWhenCatalogFileExistsAndReloadDoesNotRewrite() throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.write(root.resolve("data/quantumchamber_universes.dat"), new byte[] {1, 2, 3});
        var state = UniverseDiscoveryState.loadOrCreate(root);
        assertEquals(-1, state.watermark());
        assertTrue(state.records().isEmpty());
        assertTrue(state.eligibleSnapshot(-1, VANILLA).isEmpty());
        byte[] before = Files.readAllBytes(target());
        var modified = Files.getLastModifiedTime(target());
        assertEquals(state.toNbt(), UniverseDiscoveryState.load(root).toNbt());
        assertArrayEquals(before, Files.readAllBytes(target()));
        assertEquals(modified, Files.getLastModifiedTime(target()));
        try (var paths = Files.list(root.resolve("data"))) { assertEquals(2, paths.count()); }
    }

    @Test void checkedRoundTripKeepsMetadataAndCanonicalSnapshotAcrossReversedInsertion() throws Exception {
        var low = record(universe(0, Long.MAX_VALUE), 0, true);
        var middle = new UniverseDiscoveryRecord(universe(0, Long.MIN_VALUE), 0, 42,
                Optional.of(UUID.fromString("11223344-5566-7788-99aa-bbccddeeff00")), true);
        var high = record(universe(Long.MIN_VALUE, 0), 0, true);
        var future = record(universe(0, 1), 2, true);
        var hidden = record(universe(0, 2), 1, false);
        var input = new ArrayList<>(List.of(future, high, hidden, middle, low));
        var state = UniverseDiscoveryState.fromRecords(input);
        Collections.reverse(input);
        assertEquals(state.toNbt(), UniverseDiscoveryState.fromRecords(input).toNbt());
        assertEquals(List.of(low, middle, high, hidden, future), state.records());
        assertEquals(List.of(low, middle, high), state.eligibleSnapshot(0, VANILLA));
        assertEquals(List.of(low, high), state.eligibleSnapshot(1, new SourceFamilyRef.Catalog(middle.universeId(), DimensionRole.END)));
        assertEquals(2, state.watermark());
        assertTrue(state.eligibleSnapshot(-1, VANILLA).isEmpty());
        state.save(root);
        assertEquals(state.toNbt(), NbtIo.readCompressed(target(), NbtSizeTracker.ofUnlimitedBytes()));
        assertEquals(state.records(), UniverseDiscoveryState.load(root).records());
        assertEquals(state.toNbt(), UniverseDiscoveryState.loadOrCreate(root).toNbt());
        try (var paths = Files.list(root.resolve("data"))) { assertEquals(1, paths.count()); }
    }

    @Test void snapshotsAndCodecPayloadCannotMutateTheAuthority() {
        var input = new ArrayList<>(List.of(record(universe(1, 1), 0, true)));
        var state = UniverseDiscoveryState.fromRecords(input);
        input.clear();
        assertEquals(1, state.records().size());
        assertThrows(UnsupportedOperationException.class, () -> state.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> state.eligibleSnapshot(0, VANILLA).clear());
        var nbt = state.toNbt(); nbt.remove("Records");
        assertEquals(1, state.records().size());
        assertThrows(IllegalArgumentException.class, () -> state.eligibleSnapshot(-2, VANILLA));
        assertThrows(NullPointerException.class, () -> state.eligibleSnapshot(0, null));
    }

    @Test void recordAndFixtureRejectInvalidIdentityOrdinalTimeAndDuplicates() {
        assertThrows(IllegalArgumentException.class, () -> record(universe(0, 1), -1, true));
        assertThrows(NullPointerException.class, () -> record(null, 0, true));
        assertThrows(IllegalArgumentException.class, () -> new UniverseDiscoveryRecord(universe(0, 1), 0, -1, Optional.empty(), true));
        assertThrows(NullPointerException.class, () -> new UniverseDiscoveryRecord(universe(0, 1), 0, 0, null, true));
        var record = record(universe(0, 1), 0, true);
        assertThrows(IllegalArgumentException.class, () -> UniverseDiscoveryState.fromRecords(List.of(record, record)));
    }

    @Test void strictCodecRejectsDuplicateIdsAndOrdinalRegressionButAllowsTies() {
        var first = record(universe(0, 1), 0, true);
        var later = record(universe(0, 2), 1, true);
        var valid = UniverseDiscoveryState.fromRecords(List.of(first, later)).toNbt();
        var duplicate = valid.copy();
        var duplicateList = new NbtList();
        duplicateList.add(entry(valid, 0)); duplicateList.add(entry(valid, 0));
        duplicate.put("Records", duplicateList);
        assertThrows(IllegalArgumentException.class, () -> UniverseDiscoveryState.fromNbt(duplicate));
        var backwards = valid.copy();
        var reverseList = new NbtList();
        reverseList.add(entry(valid, 1)); reverseList.add(entry(valid, 0));
        backwards.put("Records", reverseList);
        assertThrows(IllegalArgumentException.class, () -> UniverseDiscoveryState.fromNbt(backwards));
        var ties = valid.copy();
        ((NbtList) ties.get("Records")).getCompound(1).putLong("DiscoveryOrdinal", 0);
        assertEquals(2, UniverseDiscoveryState.fromNbt(ties).records().size());
    }

    @Test void strictCodecRejectsWrongSchemaTypesUnknownFieldsUuidLengthAndBooleanValues() {
        var valid = UniverseDiscoveryState.fromRecords(List.of(record(universe(1, 1), 0, true))).toNbt();
        var invalid = new ArrayList<NbtCompound>();
        var wrongSchema = valid.copy(); wrongSchema.putInt("SchemaVersion", 2); invalid.add(wrongSchema);
        var schemaType = valid.copy(); schemaType.putLong("SchemaVersion", 1); invalid.add(schemaType);
        var missing = valid.copy(); missing.remove("Records"); invalid.add(missing);
        var unknown = valid.copy(); unknown.putInt("Unexpected", 1); invalid.add(unknown);
        var wrongList = valid.copy(); var strings = new NbtList(); strings.add(NbtString.of("bad")); wrongList.put("Records", strings); invalid.add(wrongList);
        for (String field : new String[] {"UniverseId", "DiscoveryOrdinal", "DiscoveredAtGameTime", "GameplayEligible"}) {
            var wrongType = valid.copy(); ((NbtList) wrongType.get("Records")).getCompound(0).putString(field, "bad"); invalid.add(wrongType);
            var absent = valid.copy(); ((NbtList) absent.get("Records")).getCompound(0).remove(field); invalid.add(absent);
        }
        var uuidLength = valid.copy(); ((NbtList) uuidLength.get("Records")).getCompound(0).putIntArray("UniverseId", new int[3]); invalid.add(uuidLength);
        var negative = valid.copy(); ((NbtList) negative.get("Records")).getCompound(0).putLong("DiscoveryOrdinal", -1); invalid.add(negative);
        var flag = valid.copy(); ((NbtList) flag.get("Records")).getCompound(0).putByte("GameplayEligible", (byte) 2); invalid.add(flag);
        var observer = valid.copy(); ((NbtList) observer.get("Records")).getCompound(0).putString("FirstObserver", "bad"); invalid.add(observer);
        var extra = valid.copy(); ((NbtList) extra.get("Records")).getCompound(0).putInt("Unexpected", 1); invalid.add(extra);
        for (var nbt : invalid) assertThrows(IllegalArgumentException.class, () -> UniverseDiscoveryState.fromNbt(nbt));
    }

    @Test void truncatedOrInvalidFileFailsCheckedWithoutReplacingExistingBytes() throws Exception {
        Files.createDirectories(target().getParent());
        Files.write(target(), new byte[] {31, -117, 8, 0});
        assertRejectedAndPreserved();
        var invalid = UniverseDiscoveryState.fromRecords(List.of()).toNbt(); invalid.putInt("SchemaVersion", 2);
        NbtIo.writeCompressed(invalid, target());
        assertRejectedAndPreserved();
    }

    @Test void missingLoadDirectoryTargetAndBlockedParentFailChecked() throws Exception {
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.load(root));
        assertFalse(Files.exists(root.resolve("data")));
        Files.createDirectories(target());
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.loadOrCreate(root));
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.fromRecords(List.of()).save(root));
        Path blocked = root.resolve("blocked"); Files.createDirectories(blocked); Files.write(blocked.resolve("data"), new byte[] {1});
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.loadOrCreate(blocked));
        assertArrayEquals(new byte[] {1}, Files.readAllBytes(blocked.resolve("data")));
    }

    @Test void overBudgetSavePreservesThePreviousCheckedFile() throws Exception {
        UniverseDiscoveryState.loadOrCreate(root);
        byte[] before = Files.readAllBytes(target());
        var oversized = new ArrayList<UniverseDiscoveryRecord>();
        for (int index = 0; index < 100000; index++) oversized.add(record(universe(0, index), index, true));
        var state = UniverseDiscoveryState.fromRecords(oversized);
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> state.save(root));
        assertArrayEquals(before, Files.readAllBytes(target()));
        assertTrue(UniverseDiscoveryState.load(root).records().isEmpty());
        try (var paths = Files.list(root.resolve("data"))) { assertEquals(1, paths.count()); }
    }

    private void assertRejectedAndPreserved() throws Exception {
        byte[] before = Files.readAllBytes(target());
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.loadOrCreate(root));
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> UniverseDiscoveryState.load(root));
        assertArrayEquals(before, Files.readAllBytes(target()));
    }

    private static NbtCompound entry(NbtCompound nbt, int index) { return ((NbtList) nbt.get("Records")).getCompound(index).copy(); }
    private Path target() { return root.resolve("data/quantumchamber_universe_discovery.dat"); }
}
