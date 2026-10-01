package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import static dev.quantumchamber.candidate.CandidateResolverTest.record;
import static dev.quantumchamber.candidate.CandidateResolverTest.universe;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.universe.DimensionRole;
import java.io.IOException;
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

    // ---- spec §6.4 的正式追加（M5 F2c） ----

    private static final UUID SELECTED_BY = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    @Test void appendUsesTheNextOrdinalTheGameTimeAndTheSelectedByObserver() {
        var first = record(universe(0, 1), 1, true);
        var hidden = record(universe(0, 2), 4, false);
        var tie = record(universe(0, 3), 4, true);
        var state = UniverseDiscoveryState.fromRecords(List.of(tie, first, hidden));
        assertEquals(4, state.watermark());
        var id = universe(9, 9);
        var append = state.append(id, 1234, SELECTED_BY);
        var expected = new UniverseDiscoveryRecord(id, 5, 1234, Optional.of(SELECTED_BY), true);
        assertAll(
            () -> assertEquals(UniverseDiscoveryState.Append.Outcome.APPENDED, append.outcome()),
            () -> assertEquals(expected, append.record(), "ordinal＝watermark＋1（不可見的 record 也算）、當下 game time、firstObserver＝SelectedBy、eligible"),
            () -> assertEquals(List.of(first, hidden, tie, expected), append.next().records()),
            () -> assertEquals(5, append.next().watermark()),
            () -> assertEquals(Optional.of(expected), append.next().find(id)),
            () -> assertEquals(List.of(first, hidden, tie), state.records(), "原 state 不變"),
            () -> assertEquals(Optional.empty(), state.find(id)),
            () -> assertEquals(List.of(first, tie, expected), append.next().eligibleSnapshot(append.next().watermark(), VANILLA),
                    "新的 watermark 之內可被候選選到"));
        var fromEmpty = UniverseDiscoveryState.fromRecords(List.of()).append(id, 0, SELECTED_BY);
        assertEquals(new UniverseDiscoveryRecord(id, 0, 0, Optional.of(SELECTED_BY), true), fromEmpty.record(), "空的 discovery：第一筆 ordinal 為 0");
        assertThrows(IllegalArgumentException.class, () -> state.append(universe(9, 10), -1, SELECTED_BY), "game time 必須非負");
    }

    @Test void appendIsIdempotentByUniverseIdAndOnlyAcceptsThisSessionsEligibleRecord() {
        var id = universe(7, 7);
        var later = record(universe(0, 8), 6, true);
        // 上一次寫入已落地、讀回才失敗：ordinal 與 game time 可以和這一次不同。
        var own = new UniverseDiscoveryRecord(id, 3, 99, Optional.of(SELECTED_BY), true);
        var state = UniverseDiscoveryState.fromRecords(List.of(own, later));
        var again = state.append(id, 5000, SELECTED_BY);
        assertAll(
            () -> assertEquals(UniverseDiscoveryState.Append.Outcome.EXISTING, again.outcome()),
            () -> assertEquals(own, again.record()),
            () -> assertSame(state, again.next(), "驗證通過就不改動"));
        var other = UUID.fromString("00000000-0000-4000-8000-0000000000b2");
        var cases = new ArrayList<org.junit.jupiter.api.function.Executable>();
        for (var mismatch : List.of(
                new UniverseDiscoveryRecord(id, 3, 99, Optional.of(SELECTED_BY), false),
                new UniverseDiscoveryRecord(id, 3, 99, Optional.of(other), true),
                new UniverseDiscoveryRecord(id, 3, 99, Optional.empty(), true))) {
            cases.add(() -> {
                var existing = UniverseDiscoveryState.fromRecords(List.of(mismatch, later));
                var result = existing.append(id, 5000, SELECTED_BY);
                assertEquals(UniverseDiscoveryState.Append.Outcome.MISMATCH, result.outcome(), mismatch.toString());
                assertEquals(mismatch, result.record());
                assertSame(existing, result.next(), "不符時不改寫");
                assertEquals(List.of(mismatch, later), existing.records());
            });
        }
        assertAll(cases);
    }

    /** 決策 9 的 store 層注入：atomic move 之後讀回失敗，正式檔已是新內容，不被還原；M4 讀取點看到的候選池只增不減。 */
    @Test void readbackFailureAfterTheMoveLeavesTheAppendOnDiskForM4Readers() throws Exception {
        UniverseDiscoveryState.fromRecords(List.of(record(universe(0, 1), 0, true), record(universe(0, 2), 1, false))).save(root);
        var before = UniverseDiscoveryState.load(root);
        long frozen = before.watermark();
        var poolBefore = before.eligibleSnapshot(frozen, VANILLA);
        var append = before.append(universe(0, 3), 77, SELECTED_BY);
        var injected = new ArrayList<Path>();
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> append.next().save(root, failOnce(target(), injected)));
        assertEquals(List.of(target()), injected, "讀回失敗發生在正式檔（atomic move 之後）");
        var after = UniverseDiscoveryState.load(root);
        assertAll(
            () -> assertEquals(append.next().records(), after.records(), "atomic move 已完成：append 已落地，沒有被還原"),
            () -> assertEquals(frozen + 1, after.watermark()),
            () -> assertEquals(poolBefore, after.eligibleSnapshot(frozen, VANILLA), "已凍結 watermark 的 session，候選池不變"),
            () -> assertTrue(after.eligibleSnapshot(after.watermark(), VANILLA).containsAll(poolBefore), "新的候選池不倒退"),
            () -> assertTrue(after.eligibleSnapshot(after.watermark(), VANILLA).contains(append.record()), "新的候選池含這筆 append"),
            () -> assertNoTemporaryFiles());
        // 收斂：同一份 current 重寫成功，record 仍在。
        append.next().save(root);
        assertEquals(append.next().records(), UniverseDiscoveryState.load(root).records());
    }

    @Test void temporaryVerificationFailureLeavesTheFormalFileUnchanged() throws Exception {
        UniverseDiscoveryState.fromRecords(List.of(record(universe(0, 1), 0, true))).save(root);
        byte[] before = Files.readAllBytes(target());
        var append = UniverseDiscoveryState.load(root).append(universe(0, 3), 77, SELECTED_BY);
        var injected = new ArrayList<Path>();
        assertThrows(UniverseDiscoveryState.UnhealthyDiscoveryException.class, () -> append.next().save(root, failOnceOnTemporary(injected)));
        assertEquals(1, injected.size(), "注入在暫存檔驗證");
        assertArrayEquals(before, Files.readAllBytes(target()), "atomic move 之前失敗：正式檔不變");
        assertNoTemporaryFiles();
    }

    private static CheckedNbtFile.ReadPort failOnce(Path failing, List<Path> injected) {
        return path -> {
            if (injected.isEmpty() && path.equals(failing)) { injected.add(path); throw new IOException("注入：atomic move 完成、讀回失敗"); }
            return CheckedNbtFile.readFile(path);
        };
    }

    private static CheckedNbtFile.ReadPort failOnceOnTemporary(List<Path> injected) {
        return path -> {
            if (injected.isEmpty() && path.getFileName().toString().endsWith(".tmp")) { injected.add(path); throw new IOException("注入：暫存檔驗證失敗"); }
            return CheckedNbtFile.readFile(path);
        };
    }

    private void assertNoTemporaryFiles() throws IOException {
        try (var paths = Files.list(root.resolve("data"))) { assertEquals(List.of(), paths.filter(path -> path.toString().endsWith(".tmp")).toList()); }
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
