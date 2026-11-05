package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import dev.quantumchamber.universe.*;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionJournalSchemaFiveTest {
    private static final long INDEPENDENT_SCHEMA_FIVE_LIMIT = 16L * 1024 * 1024;
    @TempDir Path directory;
    @FunctionalInterface interface Raw { void write(DataOutputStream out) throws IOException; }
    private static final class NativeManager extends net.minecraft.world.PersistentStateManager {
        int reads;
        NativeManager(Path parent) { super(parent.toFile(), net.minecraft.datafixer.Schemas.getFixer(), null); }
        @Override public NbtCompound readNbt(String id, net.minecraft.datafixer.DataFixTypes type, int version) throws IOException {
            reads++; return super.readNbt(id, type, version);
        }
    }
    private static SessionRecoveryState acquireNative(Object server, NativeManager manager, Path file) {
        return SessionRecoveryState.getOrInstall(server, manager, () -> SessionRecoveryState.load(file),
                state -> manager.set(SessionRecoveryState.STATE_ID, state));
    }
    @Test void firstPreinstalledForeignOrPresentNullRejectsBeforeLoad() {
        net.minecraft.SharedConstants.createGameVersion();
        for (boolean presentNull : new boolean[] {false, true}) {
            var manager = new NativeManager(directory); var server = new Object(); var loads = new AtomicInteger();
            var foreign = presentNull ? null : new SessionRecoveryState(); manager.set(SessionRecoveryState.STATE_ID, foreign);
            var view = (SessionRecoveryState.JournalSlotView)(Object)manager;
            var prior = view.quantumchamber$inspectJournalSlot(null);
            assertEquals(presentNull ? SessionRecoveryState.JournalSlot.PRESENT_NULL : SessionRecoveryState.JournalSlot.OTHER, prior);
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, manager,
                    () -> { loads.incrementAndGet(); return new SessionRecoveryState(); }, state -> manager.set(SessionRecoveryState.STATE_ID, state)));
            assertEquals(0, loads.get()); assertEquals(0, manager.reads); assertEquals(prior, view.quantumchamber$inspectJournalSlot(null));
        }
    }
    @Test void cachedGetRejectsChangedNativeSlotWithoutReloadOrRepair() {
        net.minecraft.SharedConstants.createGameVersion(); var server = new Object(); var manager = new NativeManager(directory);
        var state = acquireNative(server, manager, directory.resolve(SessionRecoveryState.STATE_ID + ".dat")); state.markDirty();
        var foreign = new SessionRecoveryState(); manager.set(SessionRecoveryState.STATE_ID, foreign);
        // 外部 public set 當下未被攔；驗的是本模組下一次 get，不能宣稱 native autosave 已被保護。
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, manager,
                () -> { throw new AssertionError("不准重載"); }, value -> fail("不可補 set")));
        assertTrue(state.isDirty()); assertTrue(state.flushedRecords().isEmpty()); assertEquals(0, manager.reads);
        assertEquals(SessionRecoveryState.JournalSlot.SAME, ((SessionRecoveryState.JournalSlotView)(Object)manager).quantumchamber$inspectJournalSlot(foreign));
        manager.set(SessionRecoveryState.STATE_ID, state);
        assertThrows(IllegalStateException.class, () -> acquireNative(server, manager, directory.resolve("again.dat")));
    }
    @Test void installPrecheckAndPostcheckRejectSlotDrift() {
        net.minecraft.SharedConstants.createGameVersion();
        var pre = new NativeManager(directory); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(new Object(), pre, () -> {
            pre.set(SessionRecoveryState.STATE_ID, new SessionRecoveryState()); return new SessionRecoveryState();
        }, value -> { calls.incrementAndGet(); pre.set(SessionRecoveryState.STATE_ID, value); }));
        assertEquals(0, calls.get(), "PRECHECK_BEFORE_INSTALLER");
        var post = new NativeManager(directory); var retained = new SessionRecoveryState();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(new Object(), post, () -> retained, value -> {
            post.set(SessionRecoveryState.STATE_ID, value); post.set(SessionRecoveryState.STATE_ID, new SessionRecoveryState());
        }), "POSTCHECK_MUST_REJECT");
        assertThrows(IllegalStateException.class, () -> retained.save(directory.resolve("post.dat").toFile(), null));
    }
    @Test void halfInstalledOwnerCannotSaveAfterInstallerThrows() {
        net.minecraft.SharedConstants.createGameVersion(); var manager = new NativeManager(directory);
        var state = new SessionRecoveryState(); state.markDirty();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(new Object(), manager, () -> state, value -> {
            manager.set(SessionRecoveryState.STATE_ID, value); throw new IllegalStateException("put 後注入失敗");
        }));
        assertThrows(IllegalStateException.class, manager::save);
        assertFalse(Files.exists(directory.resolve(SessionRecoveryState.STATE_ID + ".dat")));
    }
    @Test void retainedOwnedStateRejectsSlotMismatchBeforeCleanSave() {
        net.minecraft.SharedConstants.createGameVersion(); var manager = new NativeManager(directory); var server = new Object();
        var file = directory.resolve(SessionRecoveryState.STATE_ID + ".dat"); var state = acquireNative(server, manager, file);
        var foreign = new SessionRecoveryState(); manager.set(SessionRecoveryState.STATE_ID, foreign);
        assertFalse(state.isDirty()); assertThrows(IllegalStateException.class, () -> state.save(file.toFile(), null), "CLEAN_OWNER_SAVE_MUST_REJECT");
        assertFalse(Files.exists(file)); assertEquals(0, manager.reads);
        assertEquals(SessionRecoveryState.JournalSlot.SAME, ((SessionRecoveryState.JournalSlotView)(Object)manager).quantumchamber$inspectJournalSlot(foreign));
    }
    @Test void legacySchemaThreeAndFourOver16MiBStillLoadAndReadback() throws Exception {
        net.minecraft.SharedConstants.createGameVersion();
        for (int schema : new int[] {3, 4}) {
            var wrapped = largeLegacy(schema); long allocated = allocatedBytes(wrapped);
            assertTrue(allocated > INDEPENDENT_SCHEMA_FIVE_LIMIT, "必須量到真正 NBT 解碼配置量超過 16MiB");
            System.out.println("T9_LEGACY_CAPACITY schema=" + schema + " sessions=2 entriesEach=16384 allocated=" + allocated);
            var file = directory.resolve("legacy-" + schema + ".dat"); SessionJournalStore.write(file, wrapped);
            assertTrue(wrapped.equals(SessionJournalStore.read(file)), "legacy wrapper 必須 exact，不在失敗訊息展開大型 NBT");
            var state = SessionRecoveryState.load(file); state.requireHealthy();
            assertEquals(2, state.records().size());
            for (var row : state.records().values()) assertEquals(16384, row.candidateLedger().size());
            var checked = state.flushedRecords(); state.markDirty(); assertDoesNotThrow(() -> state.save(file.toFile(), null));
            assertFalse(state.isDirty()); assertTrue(checked.equals(SessionRecoveryState.load(file).flushedRecords()), "legacy checked records exact");
            assertTrue(wrapped.equals(SessionJournalStore.read(file)), "legacy checked wrapper exact");
        }
    }
    @Test void schemaFive4096LegalNewRowsRejectBeforeChangingAuthority() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> new TestLease(fixture.registries()));
            var row = expandedRow(fixtureRecord(1, true, registry, lease).toNbt(5), 4096, true, 1);
            var intent = row.getList("CandidateLedger", 10).getCompound(0).getCompound("Candidate").getCompound("Intent");
            assertEquals(219, encodedSize(intent.getCompound("SourceSettingsSnapshot")));
            assertEquals(213, encodedSize(intent.getCompound("TargetSettingsSnapshot")));
            var record = SessionRecoveryRecord.fromNbt(row, 5);
            var source = GenerationSettingsSnapshot.fromNbt(intent.getCompound("SourceSettingsSnapshot"));
            var target = GenerationSettingsSnapshot.fromNbt(intent.getCompound("TargetSettingsSnapshot"));
            registry.resolve(source, lease); registry.resolve(target, lease);
            for (var entry : record.candidateLedger()) {
                var fresh = assertInstanceOf(dev.quantumchamber.candidate.QuantumCandidate.New.class, entry.candidate());
                assertEquals(source.toNbt(), fresh.intent().sourceSettingsSnapshot().toNbt());
                assertEquals(target.toNbt(), fresh.intent().targetSettingsSnapshot().toNbt());
            }
            var rows = new NbtList(); rows.add(row); var data = new NbtCompound(); data.putInt("SchemaVersion", 5); data.put("Records", rows);
            long allocated = allocatedBytes(wrapData(data));
            System.out.println("T9_SCHEMA5_CAPACITY entries=4096 sourceBytes=219 targetBytes=213 allocated=" + allocated);
            assertTrue(allocated > INDEPENDENT_SCHEMA_FIVE_LIMIT);
            var oversized = directory.resolve("all-new-4096-input.dat"); NbtIo.writeCompressed(wrapData(data), oversized);
            assertThrows(IOException.class, () -> SessionJournalStore.read(oversized));
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.load(oversized, validator).requireHealthy());
            var disk = new Disk("4096-authority", validator, fixtureRecord(2, false, registry, lease));
            var checked = disk.state.flushedRecords(); byte[] before = Files.readAllBytes(disk.file);
            disk.state.put(record); assertThrows(UncheckedIOException.class, disk::save);
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(disk.file)));
            assertEquals(checked, disk.state.flushedRecords()); assertTrue(disk.state.isDirty());
            assertEquals(4, SessionJournalStore.read(disk.file).getCompound("data").getInt("SchemaVersion"));
            System.out.println("T9_4096_REJECT beforeSha=" + sha(before) + " afterSha=" + sha(Files.readAllBytes(disk.file)));
            assertTrue(disk.state.abortUnchecked(record)); assertEquals(4, disk.state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
        }
    }
    @Test void schemaFiveAdjacentBudgetSuccessThenOneMorePreservesCheckedAuthority() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> new TestLease(fixture.registries()));
            var template = fixtureRecord(1, true, registry, lease).toNbt(5);
            long one = allocatedBytes(wrapSingleRow(expandedRow(template.copy(), 1, true, 1)));
            long cost = allocatedBytes(wrapSingleRow(expandedRow(template.copy(), 2, true, 1))) - one;
            assertTrue(cost > 0);
            int count = Math.toIntExact((INDEPENDENT_SCHEMA_FIVE_LIMIT - one) / cost + 1);
            assertTrue(count > 1 && count < 4096);
            var inside = expandedRow(template.copy(), count, true, 1);
            var outside = expandedRow(template.copy(), count + 1, true, 1);
            long insideBytes = allocatedBytes(wrapSingleRow(inside)); long outsideBytes = allocatedBytes(wrapSingleRow(outside));
            assertTrue(insideBytes <= INDEPENDENT_SCHEMA_FIVE_LIMIT && outsideBytes > INDEPENDENT_SCHEMA_FIVE_LIMIT);
            System.out.println("T9_SCHEMA5_ADJACENT insideCount=" + count + " insideAllocated=" + insideBytes
                    + " outsideCount=" + (count + 1) + " outsideAllocated=" + outsideBytes);
            var state = new SessionRecoveryState(SessionJournalStore::read, validator); state.markCollapseAuthoritiesInitialized();
            state.put(SessionRecoveryRecord.fromNbt(inside, 5)); var file = directory.resolve("adjacent.dat");
            assertDoesNotThrow(() -> state.save(file.toFile(), null));
            var checked = state.flushedRecords(); byte[] before = Files.readAllBytes(file);
            assertTrue(checked.equals(SessionRecoveryState.load(file, validator).flushedRecords()), "鄰近預算成功後重啟 exact");
            var next = SessionRecoveryRecord.fromNbt(outside, 5); state.put(next);
            assertThrows(UncheckedIOException.class, () -> state.save(file.toFile(), null));
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file))); assertTrue(checked.equals(state.flushedRecords()));
            assertTrue(state.isDirty()); assertEquals(5, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
            System.out.println("T9_ADJACENT_REJECT beforeSha=" + sha(before) + " afterSha=" + sha(Files.readAllBytes(file)));
        }
    }
    @Test void sourceAndExisting4096CountPassBut4097RejectWithinBudget() throws Exception {
        net.minecraft.SharedConstants.createGameVersion();
        var validator = SessionRecoveryState.m6Validator(() -> { throw new AssertionError("SOURCE/EXISTING registry"); },
                () -> { throw new AssertionError("SOURCE/EXISTING acquire"); });
        for (boolean existing : new boolean[] {false, true}) {
            var inside = smallCandidateRow(4096, existing); var outside = smallCandidateRow(4097, existing);
            long measured = allocatedBytes(wrapSingleRow(outside)); assertTrue(measured < INDEPENDENT_SCHEMA_FIVE_LIMIT);
            assertTrue(assertThrows(IllegalArgumentException.class, () -> SessionRecoveryRecord.fromNbt(outside, 5))
                    .getMessage().contains("MaxCandidateEntries"));
            var reads = new AtomicInteger();
            var state = new SessionRecoveryState(path -> { reads.incrementAndGet(); return SessionJournalStore.read(path); }, validator);
            state.markCollapseAuthoritiesInitialized(); var record = SessionRecoveryRecord.fromNbt(inside, 5); state.put(record);
            var file = directory.resolve("small-count-" + existing + ".dat"); state.save(file.toFile(), null);
            assertEquals(1, reads.get()); byte[] before = Files.readAllBytes(file); var checked = state.flushedRecords();
            var bigger = new java.util.ArrayList<>(record.candidateLedger());
            bigger.add(CandidateJournalCodec.entry(outside.getList("CandidateLedger", 10).getCompound(4096)));
            assertThrows(RuntimeException.class, () -> {
                var over = new SessionRecoveryRecord(record.sessionUuid(), record.chamberUuid(), record.origin(), record.participants(),
                        record.spaceLeases(), record.state(), record.restoreEntryEffectOnReturn(), record.semantics(), record.candidateContext(),
                        bigger, record.candidateSelection(), record.collapse());
                state.put(over); state.save(file.toFile(), null);
            });
            assertEquals(1, reads.get(), "4097 應先於正式 write/readback 拒絕");
            assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(file))); assertTrue(checked.equals(state.flushedRecords()));
            System.out.println("T9_COUNT_BOUNDARY kind=" + (existing ? "EXISTING" : "SOURCE") + " rejectedCount=4097 allocated=" + measured);
        }
    }
    private static NbtCompound smallCandidateRow(int count, boolean existing) {
        var row = fixtureRecord(1, false, null, null).toNbt(5);
        var context = CandidateJournalCodecTest.m6Context(); if (existing) context.putLong("DiscoveryWatermark", 0);
        row.put("CandidateContext", context); var template = row.getList("CandidateLedger", 10).getCompound(existing ? 2 : 0).copy();
        var ledger = new NbtList();
        for (int i = 0; i < count; i++) {
            var entry = template.copy(); entry.getCompound("DoorKey").putLong("LogicalStationIndex", i);
            entry.getCompound("DoorKey").putString("WallSide", "NEGATIVE_LATERAL");
            entry.getCompound("Candidate").putByteArray("CandidateId", uniqueBytes(500, i)); ledger.add(entry);
        }
        row.put("CandidateLedger", ledger); return row;
    }
    private static NbtCompound wrapSingleRow(NbtCompound row) {
        var data = new NbtCompound(); data.putInt("SchemaVersion", 5); var rows = new NbtList(); rows.add(row); data.put("Records", rows);
        return wrapData(data);
    }
    @Test void preflightRejectsOverBudgetWithoutChangingFlushed() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var calls = new AtomicInteger();
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> { calls.incrementAndGet(); return lease; });
            var old = largeLegacy(4); var file = directory.resolve("budget.dat"); SessionJournalStore.write(file, old);
            var state = SessionRecoveryState.load(file, validator); state.requireHealthy(); state.markCollapseAuthoritiesInitialized();
            var checked = state.flushedRecords(); byte[] before = Files.readAllBytes(file);
            var fresh = fixtureRecord(3, true, registry, lease); state.put(fresh);
            long allocated = allocatedBytes(wrapData(state.writeNbt(new NbtCompound())));
            assertTrue(allocated > INDEPENDENT_SCHEMA_FIVE_LIMIT);
            assertThrows(UncheckedIOException.class, () -> state.save(file.toFile(), null));
            assertEquals(0, calls.get(), "超限應先於 provider lease 取得");
            var after = Files.readAllBytes(file);
            System.out.println("T9_PREFLIGHT_BUDGET allocated=" + allocated + " beforeSha=" + sha(before) + " afterSha=" + sha(after));
            assertTrue(java.util.Arrays.equals(before, after), "preflight 不得改正式檔 bytes");
            assertTrue(checked.equals(state.flushedRecords()), "preflight 不得改 flushed"); assertTrue(state.isDirty());
            assertEquals(4, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
            assertTrue(state.abortUnchecked(fresh)); assertEquals(4, state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
            assertDoesNotThrow(() -> state.save(file.toFile(), null));
            var oversized = old.copy(); oversized.getCompound("data").putInt("SchemaVersion", 5);
            var bad = directory.resolve("oversized-five.dat"); NbtIo.writeCompressed(oversized, bad);
            assertThrows(IOException.class, () -> SessionJournalStore.read(bad));
            var manager = new NoReadManager(directory); var server = new Object();
            var failed = SessionRecoveryState.getOrInstall(server, manager, () -> SessionRecoveryState.load(bad, validator),
                    value -> manager.set(SessionRecoveryState.STATE_ID, value));
            assertThrows(IllegalStateException.class, failed::requireHealthy); assertEquals(0, manager.reads); assertEquals(1, manager.installs);
            assertEquals(0, calls.get());
        }
    }
    @Test void snapshotStructural512BoundaryIsNotARegisteredProvider() throws Exception {
        var raw = CandidateJournalCodecTest.snapshot("normal"); raw.put("Parameters", new NbtCompound());
        boolean found = false;
        for (int length = 1; length < 600; length++) {
            raw.putString("ProviderId", "test:" + "x".repeat(length));
            if (encodedSize(raw) == 512) { found = true; break; }
        }
        assertTrue(found); var boundary = raw.copy();
        var snapshot = assertDoesNotThrow(() -> GenerationSettingsSnapshot.fromNbt(boundary));
        assertEquals("PROFILE_CLOSURE_UNBOUND", assertThrows(IllegalStateException.class,
                () -> builtin().resolve(snapshot, null)).getMessage());
        var oversized = boundary.copy(); oversized.putString("ProviderId", oversized.getString("ProviderId") + "x");
        assertEquals(513, encodedSize(oversized)); assertThrows(IllegalArgumentException.class, () -> GenerationSettingsSnapshot.fromNbt(oversized));
    }
    @Test void schemaFiveFormalReaderEnforcesIndependentSixteenMiBBoundary() throws Exception {
        long one = allocatedBytes(legacyWithCount(5, 1)); long two = allocatedBytes(legacyWithCount(5, 2));
        long pairCost = two - one; assertTrue(pairCost > 0);
        int count = Math.toIntExact((INDEPENDENT_SCHEMA_FIVE_LIMIT - one) / pairCost + 2);
        assertTrue(count > 1 && count <= 16384, "每 session 必須仍是合法 M4 容量");
        var wrapped = legacyWithCount(5, count); long measured = allocatedBytes(wrapped);
        assertTrue(measured > INDEPENDENT_SCHEMA_FIVE_LIMIT && measured < 17L * 1024 * 1024);
        System.out.println("T9_SCHEMA5_NEAR_BOUNDARY entriesEach=" + count + " allocated=" + measured);
        var file = directory.resolve("near-boundary-five.dat"); NbtIo.writeCompressed(wrapped, file);
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.load(file).requireHealthy());
    }
    private static NbtCompound largeLegacy(int schema) {
        return legacyWithCount(schema, 16384);
    }
    private static NbtCompound legacyWithCount(int schema, int count) {
        var rows = new NbtList();
        for (int ordinal = 1; ordinal <= 2; ordinal++) rows.add(expandedRow(fixtureRecord(ordinal, false, null, null).toNbt(schema), count, false, ordinal));
        var data = new NbtCompound(); data.putInt("SchemaVersion", schema); data.put("Records", rows); return wrapData(data);
    }
    private static NbtCompound expandedRow(NbtCompound row, int count, boolean m6, int ordinal) {
        var template = row.getList("CandidateLedger", 10).getCompound(m6 ? 0 : 1).copy(); var ledger = new NbtList();
        for (int i = 0; i < count; i++) {
            var entry = template.copy(); var door = entry.getCompound("DoorKey");
            door.putLong("LogicalStationIndex", i); door.putString("WallSide", "NEGATIVE_LATERAL");
            var candidate = entry.getCompound("Candidate"); candidate.putByteArray("CandidateId", uniqueBytes(ordinal, i));
            candidate.getCompound("Intent").putByteArray("AllocationToken", uniqueBytes(ordinal + 100, i));
            candidate.getCompound("Intent").putByteArray("GenerationSeedMaterial", uniqueBytes(ordinal + 200, i)); ledger.add(entry);
        }
        row.put("CandidateLedger", ledger); return row;
    }
    private static byte[] uniqueBytes(int ordinal, int index) { return java.nio.ByteBuffer.allocate(32).putInt(ordinal).putInt(index).array(); }
    private static NbtCompound wrapData(NbtCompound data) {
        var wrapped = new NbtCompound(); wrapped.putInt("DataVersion", 3953); wrapped.put("data", data); return wrapped;
    }
    private static long allocatedBytes(NbtCompound wrapped) throws Exception {
        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(wrapped, bytes);
        var tracker = NbtSizeTracker.ofUnlimitedBytes(); NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray()), tracker);
        return tracker.getAllocatedBytes();
    }
    private static int encodedSize(NbtCompound nbt) throws Exception {
        var bytes = new ByteArrayOutputStream(); NbtIo.write(nbt, new DataOutputStream(bytes)); return bytes.size();
    }
    private static String sha(byte[] value) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value));
    }
    @Test void schemaFiveWriteReadbackReloadExact() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var acquisitions = new java.util.ArrayList<TestLease>();
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> {
                var lease = new TestLease(fixture.registries()); acquisitions.add(lease); return lease;
            });
            var lease = new TestLease(fixture.registries());
            var legacy = fixtureRecord(1, false, registry, lease); var fresh = fixtureRecord(2, true, registry, lease);
            var state = new SessionRecoveryState(SessionJournalStore::read, validator); state.markCollapseAuthoritiesInitialized();
            var file = directory.resolve("mixed.dat"); state.put(legacy); state.save(file.toFile(), null);
            assertEquals(0, acquisitions.size()); assertEquals(4, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
            state.put(fresh); assertDoesNotThrow(() -> state.save(file.toFile(), null));
            assertFalse(state.isDirty()); assertEquals(2, acquisitions.size()); assertNotSame(acquisitions.get(0), acquisitions.get(1));
            assertEquals(5, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
            var reloaded = SessionRecoveryState.load(file, validator); assertDoesNotThrow(reloaded::requireHealthy);
            assertEquals(state.flushedRecords(), reloaded.flushedRecords()); assertEquals(3, acquisitions.size());
            assertFalse(reloaded.m5ReadyThisInstance()); assertTrue(reloaded.m5Evidence());
            state.remove(fresh.sessionUuid()); state.remove(legacy.sessionUuid()); state.save(file.toFile(), null);
            assertEquals(5, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
            var naked = SessionRecoveryRecord.fromNbt(SessionRecoverySchema3Test.record(SessionRecoveryStateTest.fixture("ARMING", true)), 1);
            assertThrows(IllegalArgumentException.class, () -> state.put(naked));
        }
    }
    @Test void schemaFiveFirstUpgradeRequiresM5ReadyAndNoOtherUncheckedAuthority() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var state = new SessionRecoveryState(SessionJournalStore::read, validator);
            var fresh = fixtureRecord(2, true, registry, lease); state.put(fresh);
            var file = directory.resolve("not-ready.dat"); assertThrows(RuntimeException.class, () -> state.save(file.toFile(), null));
            assertFalse(Files.exists(file)); assertTrue(state.flushedRecords().isEmpty());
            state.markCollapseAuthoritiesInitialized(); assertDoesNotThrow(() -> state.save(file.toFile(), null));
            var blocked = new SessionRecoveryState(SessionJournalStore::read, validator); blocked.markCollapseAuthoritiesInitialized();
            blocked.put(fixtureRecord(1, false, registry, lease)); blocked.put(fresh);
            assertThrows(RuntimeException.class, () -> blocked.save(directory.resolve("other-unchecked.dat").toFile(), null));
            assertFalse(Files.exists(directory.resolve("other-unchecked.dat")));
            var old = fixtureRecord(4, false, registry, lease); var removal = new Disk("first-five-removal", validator, old);
            byte[] before = Files.readAllBytes(removal.file); var checked = removal.state.flushedRecords();
            assertTrue(removal.state.remove(old.sessionUuid())); removal.state.put(fresh);
            RuntimeException rejected = null;
            try { removal.save(); } catch (RuntimeException failure) { rejected = failure; }
            byte[] after = Files.readAllBytes(removal.file);
            int observed = SessionJournalStore.read(removal.file).getCompound("data").getInt("SchemaVersion");
            System.out.println("T9_FIRST5_REMOVE beforeSha=" + sha(before) + " afterSha=" + sha(after)
                    + " diskSchema=" + observed + " oldStillFlushed=" + removal.state.flushedRecords().containsKey(old.sessionUuid()));
            assertNotNull(rejected, "未 checked 的 M4 移除不得併入首次 schema5");
            assertEquals(4, observed); assertArrayEquals(before, after); assertEquals(checked, removal.state.flushedRecords());
            assertTrue(removal.state.isDirty()); assertTrue(removal.state.abortUnchecked(fresh));
            removal.save(); assertTrue(removal.state.flushedRecords().isEmpty());
            removal.state.put(fresh); assertDoesNotThrow(removal::save);
            assertEquals(5, SessionJournalStore.read(removal.file).getCompound("data").getInt("SchemaVersion"));
        }
    }
    @Test void providerPreflightFailureDoesNotTouchCheckedFile() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var disk = new Disk("provider", validator, fixtureRecord(1, false, registry, lease));
            byte[] before = Files.readAllBytes(disk.file); var checked = disk.state.flushedRecords();
            disk.state.put(fixtureRecord(2, true, registry, lease)); lease.valid = false;
            assertThrows(RuntimeException.class, disk::save);
            assertArrayEquals(before, Files.readAllBytes(disk.file)); assertEquals(checked, disk.state.flushedRecords()); assertTrue(disk.state.isDirty());
            lease.valid = true; assertDoesNotThrow(disk::save);
            assertEquals(5, SessionJournalStore.read(disk.file).getCompound("data").getInt("SchemaVersion"));
        }
    }
    @Test void pendingExactAttemptCannotOverwriteAfterAbortOrCacheHit() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var old = fixtureRecord(1, false, registry, lease); var fresh = fixtureRecord(2, true, registry, lease);
            var disk = new Disk("pending-attempt", validator, old); var checked = disk.state.flushedRecords();
            byte[] oldBytes = Files.readAllBytes(disk.file);
            var server = new Object(); var manager = new NoReadManager(directory);
            assertSame(disk.state, SessionRecoveryState.getOrInstall(server, manager, () -> disk.state, value -> manager.set(SessionRecoveryState.STATE_ID, value)));
            disk.state.put(fresh); disk.failNextRead = true;
            assertThrows(UncheckedIOException.class, disk::save);
            byte[] attempt = Files.readAllBytes(disk.file);
            assertEquals(5, SessionJournalStore.read(disk.file).getCompound("data").getInt("SchemaVersion"));
            assertTrue(disk.state.abortUnchecked(fresh)); assertEquals(checked, disk.state.flushedRecords());
            assertFalse(disk.state.records().containsKey(fresh.sessionUuid()));
            assertEquals(4, disk.state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
            var otherFile = directory.resolve("other-target.dat"); Files.write(otherFile, oldBytes);
            var wrongTarget = assertThrows(UncheckedIOException.class, () -> disk.state.save(otherFile.toFile(), null));
            assertEquals("JOURNAL_PENDING_TARGET_MISMATCH", wrongTarget.getCause().getMessage());
            assertArrayEquals(oldBytes, Files.readAllBytes(otherFile));
            assertSame(disk.state, SessionRecoveryState.getOrInstall(server, manager,
                    () -> { throw new AssertionError("pending 不得 reload"); }, value -> {}));
            disk.state.setDirty(false);
            var blocked = assertThrows(UncheckedIOException.class, disk::save);
            assertEquals("JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED", blocked.getCause().getMessage());
            assertArrayEquals(attempt, Files.readAllBytes(disk.file)); assertEquals(checked, disk.state.flushedRecords());
            var restarted = SessionRecoveryState.load(disk.file, validator); restarted.requireHealthy();
            assertTrue(restarted.flushedRecords().containsKey(fresh.sessionUuid()));
        }
    }
    @Test void firstPendingReadOfExactOldReleasesForCurrentChanges() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var old = fixtureRecord(1, false, registry, lease);
            var fresh = fixtureRecord(2, true, registry, lease);
            var later = fixtureRecord(3, false, registry, lease);
            var disk = new Disk("pending-old-first", validator, old);
            byte[] oldBytes = Files.readAllBytes(disk.file);
            disk.state.put(fresh); disk.failNextRead = true; assertThrows(UncheckedIOException.class, disk::save);
            assertTrue(disk.state.abortUnchecked(fresh)); disk.state.put(later);
            Files.write(disk.file, oldBytes);
            assertDoesNotThrow(disk::save);
            assertEquals(4, SessionJournalStore.read(disk.file).getCompound("data").getInt("SchemaVersion"));
            assertEquals(java.util.Map.of(old.sessionUuid(), old, later.sessionUuid(), later), disk.state.flushedRecords());
        }
    }
    @Test void pendingExactAttemptRequiresRestartEvenAfterOldBytesReturn() throws Exception {
        checkTerminalPending("attempt", "JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED");
    }
    @Test void pendingOtherAuthorityRequiresRestartEvenAfterOldBytesReturn() throws Exception {
        checkTerminalPending("other", "JOURNAL_PENDING_AUTHORITY_UNKNOWN");
    }
    @Test void pendingUnreadableRequiresRestartEvenAfterOldBytesReturn() throws Exception {
        checkTerminalPending("corrupt", "JOURNAL_PENDING_UNREADABLE");
    }
    @Test void pendingMissingRequiresRestartEvenAfterOldBytesReturn() throws Exception {
        checkTerminalPending("missing", "JOURNAL_PENDING_MISSING_OLD");
    }
    private void checkTerminalPending(String observation, String expectedReason) throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var old = fixtureRecord(1, false, registry, lease);
            var fresh = fixtureRecord(2, true, registry, lease);
            var later = fixtureRecord(3, false, registry, lease);
            var disk = new Disk("pending-terminal-" + observation, validator, old);
            var checked = disk.state.flushedRecords(); byte[] oldBytes = Files.readAllBytes(disk.file);
            disk.state.put(fresh); disk.failNextRead = true; assertThrows(UncheckedIOException.class, disk::save);
            assertTrue(disk.state.abortUnchecked(fresh)); disk.state.put(later);
            switch (observation) {
                case "attempt" -> { /* 保留實際 move 的檔案。 */ }
                case "other" -> {
                    var different = SessionJournalStore.read(disk.file);
                    different.putInt("DataVersion", different.getInt("DataVersion") + 1);
                    NbtIo.writeCompressed(different, disk.file);
                }
                case "corrupt" -> Files.write(disk.file, new byte[] {0, 1, 2});
                case "missing" -> Files.delete(disk.file);
                default -> throw new AssertionError(observation);
            }
            byte[] observed = Files.exists(disk.file) ? Files.readAllBytes(disk.file) : null;
            assertEquals(expectedReason, assertThrows(UncheckedIOException.class, disk::save).getCause().getMessage());
            if (observed == null) assertFalse(Files.exists(disk.file));
            else assertArrayEquals(observed, Files.readAllBytes(disk.file));
            Files.write(disk.file, oldBytes);
            assertThrows(UncheckedIOException.class, disk::save, "已判定重啟，還原舊檔後仍須停寫");
            assertArrayEquals(oldBytes, Files.readAllBytes(disk.file)); assertEquals(checked, disk.state.flushedRecords());
            assertTrue(disk.state.isDirty()); assertEquals(later, disk.state.records().get(later.sessionUuid()));
            var restarted = SessionRecoveryState.load(disk.file, validator); restarted.requireHealthy();
            assertEquals(checked, restarted.flushedRecords());
        }
    }
    @Test void preMoveFailureDoesNotPoisonNextSave() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var state = new SessionRecoveryState(SessionJournalStore::read, validator); state.markCollapseAuthoritiesInitialized();
            state.put(fixtureRecord(2, true, registry, lease));
            var blocker = directory.resolve("blocker"); Files.writeString(blocker, "一般檔案");
            assertThrows(UncheckedIOException.class, () -> state.save(blocker.resolve("journal.dat").toFile(), null));
            assertTrue(state.flushedRecords().isEmpty()); assertTrue(state.isDirty());
            var good = directory.resolve("after-not-moved.dat"); assertDoesNotThrow(() -> state.save(good.toFile(), null));
            assertEquals(5, SessionJournalStore.read(good).getCompound("data").getInt("SchemaVersion"));
        }
    }
    @Test void priorUncheckedLegacyWriteBlocksFirstSchemaFiveUntilCheckedAgain() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var disk = new Disk("legacy-unknown", validator, fixtureRecord(1, false, registry, lease));
            disk.state.markDirty(); disk.failNextRead = true; assertThrows(UncheckedIOException.class, disk::save);
            var fresh = fixtureRecord(2, true, registry, lease); disk.state.put(fresh);
            byte[] unknown = Files.readAllBytes(disk.file); assertThrows(RuntimeException.class, disk::save);
            assertArrayEquals(unknown, Files.readAllBytes(disk.file)); assertTrue(disk.state.abortUnchecked(fresh));
            disk.state.markDirty(); assertDoesNotThrow(disk::save);
            disk.state.put(fresh); assertDoesNotThrow(disk::save);
            assertEquals(5, SessionJournalStore.read(disk.file).getCompound("data").getInt("SchemaVersion"));
        }
    }
    @Test void pendingCheckedAbsentCanReleaseOnlyOnProvenAbsence() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var file = directory.resolve("initially-absent.dat");
            var state = SessionRecoveryState.load(file, validator); state.requireHealthy(); state.markCollapseAuthoritiesInitialized();
            var fresh = fixtureRecord(2, true, registry, lease); state.put(fresh);
            // 只對此已載入 absent 的 instance 注入既有 readback port，保留真實 load provenance。
            var port = SessionRecoveryState.class.getDeclaredField("readback"); port.setAccessible(true);
            port.set(state, (SessionRecoveryState.JournalReadback) path -> { throw new IOException("move 後故障"); });
            assertThrows(UncheckedIOException.class, () -> state.save(file.toFile(), null));
            port.set(state, (SessionRecoveryState.JournalReadback) SessionJournalStore::read);
            assertTrue(state.abortUnchecked(fresh));
            Files.delete(file); assertDoesNotThrow(() -> state.save(file.toFile(), null));
            assertTrue(state.flushedRecords().isEmpty());
            assertEquals(4, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
        }
    }
    @Test void alreadySchemaFivePendingStillBlocksSecondAttemptAfterAbort() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var disk = new Disk("already-five", validator, fixtureRecord(1, false, registry, lease));
            disk.state.put(fixtureRecord(2, true, registry, lease)); disk.save();
            var checked = disk.state.flushedRecords(); var next = fixtureRecord(3, true, registry, lease);
            disk.state.put(next); disk.failNextRead = true; assertThrows(UncheckedIOException.class, disk::save);
            byte[] originalAttempt = Files.readAllBytes(disk.file); assertTrue(disk.state.abortUnchecked(next));
            var failure = assertThrows(UncheckedIOException.class, disk::save);
            assertEquals("JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED", failure.getCause().getMessage());
            assertEquals(checked, disk.state.flushedRecords()); assertArrayEquals(originalAttempt, Files.readAllBytes(disk.file));
            assertEquals(5, disk.state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
            assertTrue(SessionRecoveryState.load(disk.file, validator).records().containsKey(next.sessionUuid()));
        }
    }
    @Test void preparedBytesAndNestedNbtDoNotAliasCallerValues() throws Exception {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var wrapped = validSnapshots(validM6Wrapper(), registry, lease); var original = wrapped.copy();
            var prepared = SessionJournalStore.prepareSchemaFive(wrapped,
                    data -> SessionRecoveryState.fromNbt(data, validator).requireHealthy());
            wrapped.getCompound("data").put("Records", new NbtList());
            prepared.wrapped().getCompound("data").put("Records", new NbtList());
            assertEquals(original, prepared.wrapped());
            var file = directory.resolve("prepared.dat"); SessionJournalStore.writePrepared(file, prepared);
            assertEquals(original, SessionJournalStore.read(file));
            assertEquals(1, SessionRecoveryState.load(file, validator).flushedRecords().size());
        }
    }
    private final class Disk {
        final Path file; final SessionRecoveryState state; boolean failNextRead, wrongVersionNextRead;
        Disk(String name, SessionRecoveryState.M6IntentValidator validator, SessionRecoveryRecord initial) {
            file = directory.resolve(name + ".dat");
            state = new SessionRecoveryState(path -> {
                if (failNextRead) { failNextRead = false; throw new IOException("注入：move 後 readback 失敗"); }
                var actual = SessionJournalStore.read(path);
                if (wrongVersionNextRead) { wrongVersionNextRead = false; actual.getCompound("data").putInt("SchemaVersion", 4); }
                return actual;
            }, validator);
            state.markCollapseAuthoritiesInitialized(); state.put(initial); save();
        }
        void save() { state.save(file.toFile(), null); }
    }
    private static SessionRecoveryRecord fixtureRecord(int ordinal, boolean m6, UniverseProfileRegistry registry, TestLease lease) {
        NbtCompound data;
        if (m6) data = validSnapshots(validM6Wrapper(), registry, lease).getCompound("data");
        else {
            data = SessionRecoverySchema3Test.fixture(false, false); data.putInt("SchemaVersion", 4);
            SessionRecoverySchema3Test.record(data).put("Collapse", new NbtCompound());
        }
        var row = SessionRecoverySchema3Test.record(data);
        var sid = new java.util.UUID(222, ordinal); var chamber = new java.util.UUID(111, ordinal);
        row.putUuid("SessionUuid", sid); row.putUuid("ChamberUuid", chamber);
        row.getCompound("Origin").putUuid("ChamberUuid", chamber);
        row.getCompound("Origin").putIntArray("ControllerPos", new int[] {ordinal * 200, 64, 0});
        var people = row.getList("Participants", 10);
        for (int i = 0; i < people.size(); i++) people.getCompound(i).putUuid("PlayerUuid", new java.util.UUID(333, ordinal * 10L + i));
        var slots = row.getList("SpaceLeases", 10);
        for (int i = 0; i < slots.size(); i++) slots.getCompound(i).putInt("SlotId", ordinal * 10 + i);
        for (var value : row.getList("CandidateLedger", 10)) ((NbtCompound) value).getCompound("DoorKey").putUuid("SessionUuid", sid);
        return SessionRecoveryRecord.fromNbt(row, m6 ? 5 : 4);
    }
    @Test void schemaFiveEmptyAndLegacyOnlyJournalNeverAcquire() {
        var validator = SessionRecoveryState.m6Validator(() -> { throw new AssertionError("legacy registry"); },
                () -> { throw new AssertionError("legacy acquire"); });
        for (int version : new int[] {3, 4, 5}) {
            var data = SessionRecoverySchema3Test.fixture(false, false); data.putInt("SchemaVersion", version);
            if (version >= 4) SessionRecoverySchema3Test.record(data).put("Collapse", new NbtCompound());
            assertDoesNotThrow(() -> SessionRecoveryState.fromNbt(data, validator).requireHealthy());
        }
        var empty = new NbtCompound(); empty.putInt("SchemaVersion", 5); empty.put("Records", new NbtList());
        var state = SessionRecoveryState.fromNbt(empty, validator);
        assertDoesNotThrow(state::requireHealthy);
        assertEquals(5, state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
        var sourceOnly = validM6Wrapper().getCompound("data");
        var sourceEntry = SessionRecoverySchema3Test.ledger(SessionRecoverySchema3Test.fixture(false, false)).getCompound(0).copy();
        var ledger = new NbtList(); ledger.add(sourceEntry); SessionRecoverySchema3Test.record(sourceOnly).put("CandidateLedger", ledger);
        assertDoesNotThrow(() -> SessionRecoveryState.fromNbt(sourceOnly, validator).requireHealthy());
    }
    @Test void journalRejectsMissingProvider() {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var data = validSnapshots(validM6Wrapper(), registry, lease).getCompound("data");
            var calls = new AtomicInteger();
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> { calls.incrementAndGet(); return lease; });
            assertDoesNotThrow(() -> SessionRecoveryState.fromNbt(data, validator).requireHealthy());
            assertEquals(1, calls.get());
            var missing = new UniverseProfileRegistry(); missing.freeze();
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.fromNbt(data,
                    SessionRecoveryState.m6Validator(() -> missing, () -> lease)).requireHealthy());
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.fromNbt(data).requireHealthy());
        }
    }
    @Test void journalRejectsWrongSnapshotOrCapability() {
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var data = validSnapshots(validM6Wrapper(), registry, lease).getCompound("data");
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            assertDoesNotThrow(() -> SessionRecoveryState.fromNbt(data, validator).requireHealthy());
            for (String key : List.of("SourceSettingsSnapshot", "TargetSettingsSnapshot")) {
                var bad = data.copy(); var snapshot = firstIntent(bad).getCompound(key);
                var fp = snapshot.getByteArray("OutputFingerprint").clone(); fp[0] ^= 1; snapshot.putByteArray("OutputFingerprint", fp);
                assertThrows(IllegalStateException.class, () -> SessionRecoveryState.fromNbt(bad, validator).requireHealthy(), key);
            }
            var wrong = data.copy(); firstIntent(wrong).putInt("FamilyCapabilityVersion", 1);
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.fromNbt(wrong, validator).requireHealthy());
            lease.valid = false;
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.fromNbt(data, validator).requireHealthy());
        }
    }
    @Test void getOrInstallKeepsExactDirtyStateWithoutNativeLoader() {
        var server = new Object(); var manager = new NoReadManager(directory); var calls = new AtomicInteger();
        var expected = new SessionRecoveryState(); expected.markDirty();
        var first = assertDoesNotThrow(() -> SessionRecoveryState.getOrInstall(server, manager,
                () -> { calls.incrementAndGet(); return expected; }, state -> manager.set(SessionRecoveryState.STATE_ID, state)));
        assertSame(expected, first); assertTrue(first.isDirty());
        assertSame(first, SessionRecoveryState.getOrInstall(server, manager,
                () -> { throw new AssertionError("cache hit 不得 reload"); }, state -> { throw new AssertionError("cache hit 不得 install"); }));
        assertEquals(1, calls.get()); assertEquals(1, manager.installs); assertEquals(0, manager.reads);
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, new Object(),
                SessionRecoveryState::new, state -> {}));
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(new Object(), manager,
                SessionRecoveryState::new, state -> {}));
        var otherManager = new NoReadManager(directory); var separate = SessionRecoveryState.getOrInstall(new Object(), otherManager, SessionRecoveryState::new, state -> otherManager.set(SessionRecoveryState.STATE_ID, state));
        assertNotSame(first, separate);
    }
    @Test void wrongManagerPermanentlyStopsOriginalGetAndOwnedSave() throws Exception {
        net.minecraft.SharedConstants.createGameVersion();
        var server = new Object(); var a = new NoReadManager(directory); var b = new NoReadManager(directory);
        var file = directory.resolve("wrong-manager.dat");
        var state = SessionRecoveryState.getOrInstall(server, a, () -> SessionRecoveryState.load(file),
                value -> a.set(SessionRecoveryState.STATE_ID, value));
        state.markDirty(); state.save(file.toFile(), null);
        byte[] checkedBytes = Files.readAllBytes(file); var checked = state.flushedRecords(); state.markDirty();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, b,
                () -> { throw new AssertionError("B 不得載入"); }, value -> fail("B 不得安裝")));
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, a,
                        SessionRecoveryState::new, value -> fail("A 不得重新安裝"))),
                () -> assertThrows(IllegalStateException.class, () -> state.save(file.toFile(), null)),
                () -> assertArrayEquals(checkedBytes, Files.readAllBytes(file)),
                () -> assertEquals(checked, state.flushedRecords()), () -> assertTrue(state.isDirty()));
        assertEquals(0, b.installs); assertEquals(1, a.installs);
        var separate = SessionRecoveryState.getOrInstall(new Object(), b, SessionRecoveryState::new,
                value -> b.set(SessionRecoveryState.STATE_ID, value));
        assertNotSame(state, separate); assertEquals(0, a.reads + b.reads);
    }
    @Test void managerOwnedByEarlierServerStillInvalidatesOnlyVisitingServer() throws Exception {
        net.minecraft.SharedConstants.createGameVersion();
        var s2 = new Object(); var b = new NoReadManager(directory); var bFile = directory.resolve("earlier-b.dat");
        var bState = SessionRecoveryState.getOrInstall(s2, b, () -> SessionRecoveryState.load(bFile),
                value -> b.set(SessionRecoveryState.STATE_ID, value));
        var s1 = new Object(); var a = new NoReadManager(directory); var aFile = directory.resolve("later-a.dat");
        var aState = SessionRecoveryState.getOrInstall(s1, a, () -> SessionRecoveryState.load(aFile),
                value -> a.set(SessionRecoveryState.STATE_ID, value));
        aState.markDirty(); aState.save(aFile.toFile(), null);
        byte[] checkedBytes = Files.readAllBytes(aFile); var checked = aState.flushedRecords(); aState.markDirty();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(s1, b,
                () -> { throw new AssertionError("失配不能重載"); }, value -> fail("失配不能重新安裝")));
        assertSame(bState, SessionRecoveryState.getOrInstall(s2, b, SessionRecoveryState::new, value -> fail("B 不得重裝")));
        bState.markDirty(); bState.save(bFile.toFile(), null); assertFalse(bState.isDirty()); assertTrue(Files.isRegularFile(bFile));
        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(s1, a,
                        SessionRecoveryState::new, value -> fail("A 不得重新安裝"))),
                () -> assertThrows(IllegalStateException.class, () -> aState.save(aFile.toFile(), null)),
                () -> assertArrayEquals(checkedBytes, Files.readAllBytes(aFile)),
                () -> assertEquals(checked, aState.flushedRecords()), () -> assertTrue(aState.isDirty()));
        assertEquals(1, a.installs); assertEquals(1, b.installs); assertEquals(0, a.reads + b.reads);
    }
    @Test void failedLoaderAndReentryCannotRetryOrInstall() {
        var server = new Object(); var manager = new NoReadManager(directory); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, manager,
                () -> { calls.incrementAndGet(); throw new IllegalStateException("loader failed"); }, state -> fail("不可 install")));
        assertEquals(1, calls.get());
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, manager,
                () -> { calls.incrementAndGet(); return new SessionRecoveryState(); }, state -> fail("不可重試 install")));
        assertEquals(1, calls.get());
        var reentryServer = new Object(); var reentryManager = new NoReadManager(directory);
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(reentryServer, reentryManager, () -> {
            assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(reentryServer, reentryManager,
                    SessionRecoveryState::new, state -> fail("重入 install")));
            return new SessionRecoveryState();
        }, state -> fail("重入失敗後外層也不可 install")));
    }
    @Test void absentAndCorruptFirstLoadsInstallOnceAndRemainDistinct() throws Exception {
        var absentServer = new Object(); var manager = new NoReadManager(directory); var installs = new AtomicInteger();
        var empty = assertDoesNotThrow(() -> SessionRecoveryState.getOrInstall(absentServer, manager,
                () -> SessionRecoveryState.load(directory.resolve("absent.dat"), intents -> {}), state -> { installs.incrementAndGet(); manager.set(SessionRecoveryState.STATE_ID, state); }));
        assertDoesNotThrow(empty::requireHealthy); assertTrue(empty.records().isEmpty()); assertEquals(1, installs.get());
        var badFile = directory.resolve("bad.dat"); Files.write(badFile, new byte[] {0, 1, 2});
        var corruptServer = new Object(); var corruptManager = new NoReadManager(directory);
        var bad = assertDoesNotThrow(() -> SessionRecoveryState.getOrInstall(corruptServer, corruptManager,
                () -> SessionRecoveryState.load(badFile, intents -> {}), state -> { installs.incrementAndGet(); corruptManager.set(SessionRecoveryState.STATE_ID, state); }));
        assertThrows(IllegalStateException.class, bad::requireHealthy); assertEquals(2, installs.get());
        Files.delete(badFile);
        assertSame(bad, SessionRecoveryState.getOrInstall(corruptServer, corruptManager,
                SessionRecoveryState::new, state -> fail("failed state 不得重建")));
    }
    @Test void installationFailureCannotRetryAndLostWeakStateCannotReload() throws Exception {
        var failedServer = new Object(); var failedManager = new NoReadManager(directory); var calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(failedServer, failedManager,
                () -> { calls.incrementAndGet(); return new SessionRecoveryState(); }, state -> { throw new IllegalStateException("install failed"); }));
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(failedServer, failedManager,
                () -> { calls.incrementAndGet(); return new SessionRecoveryState(); }, state -> {}));
        assertEquals(1, calls.get());
        var server = new Object(); var manager = new NoReadManager(directory);
        var state = SessionRecoveryState.getOrInstall(server, manager, SessionRecoveryState::new,
                value -> manager.set(SessionRecoveryState.STATE_ID, value));
        var entriesField = SessionRecoveryState.class.getDeclaredField("INSTALLATIONS"); entriesField.setAccessible(true);
        var entries = (List<?>) entriesField.get(null); Object matched = null;
        for (Object entry : entries) {
            var owner = entry.getClass().getDeclaredField("server"); owner.setAccessible(true);
            if (((java.lang.ref.WeakReference<?>) owner.get(entry)).get() == server) matched = entry;
        }
        assertNotNull(matched);
        for (var field : matched.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
            // static binding 的所有非 enum 邊必為弱參照；不能把 owner／validator 圈強留在 static root。
            assertTrue(field.getType().isEnum() || field.getType() == java.lang.ref.WeakReference.class, field.getName());
        }
        var weakState = matched.getClass().getDeclaredField("state"); weakState.setAccessible(true);
        ((java.lang.ref.WeakReference<?>) weakState.get(matched)).clear();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(server, manager,
                () -> { fail("live server state 失聯不得 reload"); return state; }, value -> {}));
        assertEquals(1, manager.installs);
        var weakServer = matched.getClass().getDeclaredField("server"); weakServer.setAccessible(true);
        ((java.lang.ref.WeakReference<?>) weakServer.get(matched)).clear();
        var nextServer = new Object();
        assertThrows(IllegalStateException.class, () -> SessionRecoveryState.getOrInstall(nextServer, manager, SessionRecoveryState::new,
                value -> manager.set(SessionRecoveryState.STATE_ID, value)));
        assertEquals(1, manager.installs); assertFalse(entries.contains(matched));
    }
    private static final class NoReadManager extends net.minecraft.world.PersistentStateManager {
        int reads, installs;
        NoReadManager(Path path) { super(path.toFile(), net.minecraft.datafixer.Schemas.getFixer(), null); }
        @Override public <T extends net.minecraft.world.PersistentState> T get(net.minecraft.world.PersistentState.Type<T> type, String id) {
            reads++; throw new AssertionError("不得呼原生 get/unlimited loader");
        }
        @Override public <T extends net.minecraft.world.PersistentState> T getOrCreate(net.minecraft.world.PersistentState.Type<T> type, String id) {
            reads++; throw new AssertionError("不得呼原生 getOrCreate");
        }
        @Override public NbtCompound readNbt(String id, net.minecraft.datafixer.DataFixTypes type, int version) {
            reads++; throw new AssertionError("不得呼原生 readNbt");
        }
        @Override public void set(String id, net.minecraft.world.PersistentState state) { installs++; super.set(id, state); }
    }
    private static NbtCompound firstIntent(NbtCompound data) {
        return SessionRecoverySchema3Test.ledger(data).getCompound(0).getCompound("Candidate").getCompound("Intent");
    }
    private static NbtCompound validSnapshots(NbtCompound wrapped, UniverseProfileRegistry registry, TestLease lease) {
        var intent = firstIntent(wrapped.getCompound("data"));
        for (String key : List.of("SourceSettingsSnapshot", "TargetSettingsSnapshot")) {
            var raw = intent.getCompound(key);
            intent.put(key, registry.createSnapshot(raw.getString("ProviderId"), 1, raw.getCompound("Parameters"), lease).toNbt());
        }
        return wrapped;
    }
    private static UniverseProfileRegistry builtin() {
        var registry = new UniverseProfileRegistry();
        registry.register(new Minecraft121VanillaPresetProfileProvider("quantumchamber:vanilla_normal_v1"));
        registry.register(new Minecraft121VanillaPresetProfileProvider("quantumchamber:vanilla_amplified_v1"));
        registry.freeze(); return registry;
    }
    private record Settings(LifecycledResourceManagerImpl resources, DynamicRegistryManager.Immutable registries) implements AutoCloseable {
        public void close() { resources.close(); }
    }
    private static Settings settings() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize(); dev.quantumchamber.registry.ModBlocks.register();
        // 建立唯讀 registry view 後不能補註冊；先備妥同一測試 JVM 會使用的模組型別。
        dev.quantumchamber.registry.ModBlockEntities.register();
        dev.quantumchamber.registry.ModEffects.register();
        dev.quantumchamber.registry.ModPotions.register();
        var resources = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA, List.of(VanillaDataPackProvider.createDefaultPack()));
        try {
            var combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
            return new Settings(resources, RegistryLoader.loadFromResource(resources,
                    combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN), RegistryLoader.DYNAMIC_REGISTRIES));
        } catch (RuntimeException | Error e) { resources.close(); throw e; }
    }
    private static final class TestLease implements ServerSettingsContext {
        private final DynamicRegistryManager.Immutable registries; boolean valid = true;
        TestLease(DynamicRegistryManager.Immutable registries) { this.registries = registries; }
        public void requireValid() { if (!valid) throw new IllegalStateException("CONTEXT_UNAVAILABLE"); }
        public DynamicRegistryManager.Immutable registries() { requireValid(); return registries; }
        public boolean sourceGenerateStructures() { throw new AssertionError("不得覆寫 frozen bool"); }
    }
    @Test void versionProbeReadbackMismatchRejectsWithoutUnlimitedFallback() throws Exception {
        for (int schema : new int[] {-1, 0, 6}) {
            var file = raw("unknown-" + schema, out -> { header(out); integer(out, "SchemaVersion", schema); records(out); end(out); });
            assertThrows(IOException.class, () -> SessionJournalStore.read(file), "schema=" + schema);
        }
        for (int later : new int[] {4, 5}) {
            var duplicate = raw("duplicate-" + later, out -> { header(out); integer(out, "SchemaVersion", 5); records(out); integer(out, "SchemaVersion", later); end(out); });
            assertThrows(IOException.class, () -> SessionJournalStore.read(duplicate));
        }
        // 讓 4 與 5 的 records 都可合法解碼且完全相同，單獨驗證正式 readback 版本不能不同。
        try (var fixture = settings()) {
            var registry = builtin(); var lease = new TestLease(fixture.registries());
            var validator = SessionRecoveryState.m6Validator(() -> registry, () -> lease);
            var old = fixtureRecord(1, false, registry, lease); var fresh = fixtureRecord(2, true, registry, lease);
            var disk = new Disk("readback-version", validator, old);
            disk.state.put(fresh); disk.save(); var previouslyChecked = disk.state.flushedRecords();
            disk.state.remove(fresh.sessionUuid()); disk.wrongVersionNextRead = true;
            assertThrows(UncheckedIOException.class, disk::save, "相同 records 不能掩蓋 schema5→4 的 readback");
            var actual = SessionJournalStore.read(disk.file); assertEquals(5, actual.getCompound("data").getInt("SchemaVersion"));
            var asFour = actual.getCompound("data").copy(); asFour.putInt("SchemaVersion", 4);
            assertEquals(disk.state.records(), SessionRecoveryState.fromNbt(asFour).records());
            assertEquals(previouslyChecked, disk.state.flushedRecords()); assertTrue(disk.state.isDirty());
            assertEquals("JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED",
                    assertThrows(UncheckedIOException.class, disk::save).getCause().getMessage());
        }
    }
    @Test void rawVersionProbeAcceptsBothFieldOrdersAndRejectsMissingOrWrongType() throws Exception {
        for (boolean versionFirst : new boolean[] {false, true}) {
            var file = raw("order-" + versionFirst, out -> {
                header(out); if (versionFirst) integer(out, "SchemaVersion", 5); records(out);
                if (!versionFirst) integer(out, "SchemaVersion", 5); end(out);
            });
            assertEquals(5, SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
        }
        var missing = raw("missing", out -> { header(out); records(out); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(missing));
        var wrong = raw("wrong", out -> { header(out); out.writeByte(8); out.writeUTF("SchemaVersion"); out.writeUTF("5"); records(out); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(wrong));
    }
    @Test void rawSchemaFiveRejectsDuplicateRecordsEvenWhenIdentical() throws Exception {
        var file = raw("records-twice", out -> { header(out); integer(out, "SchemaVersion", 5); records(out); records(out); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
    }
    @Test void rawNestedVersionAndDuplicateDataCannotSelectLegacyReader() throws Exception {
        var nested = raw("nested-version", out -> {
            header(out); out.writeByte(10); out.writeUTF("Nested"); integer(out, "SchemaVersion", 5); out.writeByte(0); records(out); end(out);
        });
        assertThrows(IOException.class, () -> SessionJournalStore.read(nested));
        var duplicate = raw("duplicate-data", out -> {
            header(out); integer(out, "SchemaVersion", 5); records(out); out.writeByte(0);
            out.writeByte(10); out.writeUTF("data"); integer(out, "SchemaVersion", 4); records(out); end(out);
        });
        assertThrows(IOException.class, () -> SessionJournalStore.read(duplicate));
    }
    @Test void rawMalformedUtfTruncatedTagNegativeListAndUnknownTypeReject() throws Exception {
        var utf = raw("utf", out -> { out.writeByte(10); out.writeShort(1); out.writeByte(0xc2); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(utf));
        var truncated = raw("tag-eof", out -> { header(out); integer(out, "SchemaVersion", 5); records(out); out.writeByte(3); out.writeUTF("Broken"); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(truncated));
        var negative = raw("negative-list", out -> { header(out); integer(out, "SchemaVersion", 5); out.writeByte(9); out.writeUTF("Records"); out.writeByte(10); out.writeInt(-1); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(negative));
        var unknown = raw("unknown-tag", out -> { header(out); integer(out, "SchemaVersion", 5); records(out); out.writeByte(13); out.writeUTF("Broken"); end(out); });
        assertThrows(IOException.class, () -> SessionJournalStore.read(unknown));
    }
    @Test void rawMarkerSurvivesLaterLegacyContextOverwrite() throws Exception {
        var file = raw("context-overwrite", out -> {
            header(out); integer(out, "SchemaVersion", 5);
            out.writeByte(9); out.writeUTF("Records"); out.writeByte(10); out.writeInt(1);
            out.writeByte(10); out.writeUTF("CandidateContext"); integer(out, "CandidateSchemaVersion", 2); out.writeByte(0);
            out.writeByte(10); out.writeUTF("CandidateContext"); integer(out, "CandidateSchemaVersion", 1); out.writeByte(0);
            out.writeByte(0); end(out);
        });
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
    }
    @Test void rawMarkerSurvivesLaterLegacyIntentOverwrite() throws Exception {
        var file = raw("intent-overwrite", out -> {
            header(out); integer(out, "SchemaVersion", 5);
            out.writeByte(9); out.writeUTF("Records"); out.writeByte(10); out.writeInt(1);
            out.writeByte(9); out.writeUTF("CandidateLedger"); out.writeByte(10); out.writeInt(1);
            out.writeByte(10); out.writeUTF("Candidate");
            out.writeByte(10); out.writeUTF("Intent"); integer(out, "PolicyVersion", 1); out.writeByte(0);
            out.writeByte(10); out.writeUTF("Intent"); integer(out, "ProfileVersion", 1); out.writeByte(0);
            out.writeByte(0); out.writeByte(0); out.writeByte(0); end(out);
        });
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
    }
    @Test void rawWrongLedgerContainerCannotHideEarlierM6Intent() throws Exception {
        for (boolean m6 : new boolean[] {false, true}) {
            var data = SessionRecoverySchema3Test.fixture(false, false); var row = SessionRecoverySchema3Test.record(data);
            row.put("Collapse", new NbtCompound());
            var earlier = m6 ? CandidateJournalCodecTest.m6Entry(row.getUuid("SessionUuid"))
                    : row.getList("CandidateLedger", 10).getCompound(1);
            var file = raw("ledger-wrong-container-" + m6, out -> {
                header(out); integer(out, "SchemaVersion", 5); out.writeByte(9); out.writeUTF("Records"); out.writeByte(10); out.writeInt(1);
                for (String key : row.getKeys()) {
                    if (key.equals("CandidateLedger")) {
                        out.writeByte(10); out.writeUTF(key); earlier.write(out);
                    }
                    var value = row.get(key); out.writeByte(value.getType()); out.writeUTF(key); value.write(out);
                }
                out.writeByte(0); end(out);
            });
            // 原生物化的最後值確為合法 M4，證明拒絕必須來自 raw M6 marker，不是 codec 的其他錯誤。
            var materialized = NbtIo.readCompressed(file, NbtSizeTracker.of(INDEPENDENT_SCHEMA_FIVE_LIMIT));
            assertDoesNotThrow(() -> SessionRecoveryState.fromNbt(materialized.getCompound("data")).requireHealthy());
            if (m6) assertThrows(IOException.class, () -> SessionJournalStore.read(file));
            else assertDoesNotThrow(() -> SessionJournalStore.read(file));
        }
    }
    @Test void rawParserRejectsNonemptyEndListAndTruncatedGzip() throws Exception {
        var file = raw("end-positive", out -> {
            header(out); integer(out, "SchemaVersion", 5);
            out.writeByte(9); out.writeUTF("Records"); out.writeByte(0); out.writeInt(1); end(out);
        });
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
        var valid = raw("valid", out -> { header(out); integer(out, "SchemaVersion", 5); records(out); end(out); });
        var bytes = Files.readAllBytes(valid); Files.write(valid, java.util.Arrays.copyOf(bytes, bytes.length - 4));
        assertThrows(IOException.class, () -> SessionJournalStore.read(valid));
    }
    @Test void sameCompressedSnapshotSurvivesPathReplacement() throws Exception {
        var file = raw("stable", out -> { header(out); integer(out, "SchemaVersion", 4); records(out); end(out); });
        var snapshot = SessionJournalStore.readSnapshot(file);
        Files.write(file, new byte[] {0, 1, 2});
        assertEquals(4, snapshot.decode().getCompound("data").getInt("SchemaVersion"));
        assertThrows(IOException.class, () -> SessionJournalStore.read(file));
    }
    @Test void rawContainerDepth511And512PassBut513Fails() throws Exception {
        for (int depth : new int[] {511, 512, 513}) {
            var file = raw("depth-" + depth, out -> {
                header(out); integer(out, "SchemaVersion", 5); records(out);
                for (int i = 2; i < depth; i++) { out.writeByte(10); out.writeUTF("Nested"); }
                for (int i = 2; i < depth; i++) out.writeByte(0);
                end(out);
            });
            if (depth <= 512) assertDoesNotThrow(() -> SessionJournalStore.read(file), "depth=" + depth);
            else assertThrows(IOException.class, () -> SessionJournalStore.read(file));
        }
    }
    @Test void rawNegativeAndOversizedArrayLengthsFailWithoutAllocation() throws Exception {
        for (int type : new int[] {7, 11, 12}) for (int length : new int[] {-1, Integer.MAX_VALUE}) {
            var file = raw("array-" + type + "-" + length, out -> {
                header(out); integer(out, "SchemaVersion", 5); records(out);
                out.writeByte(type); out.writeUTF("Broken"); out.writeInt(length); end(out);
            });
            assertThrows(IOException.class, () -> SessionJournalStore.read(file));
        }
    }
    @Test void rawDuplicateScopesAreRowLocalAndDoNotTightenLegacyDeepPayload() throws Exception {
        var m6 = validM6Wrapper();
        String base = "data/Records/[]/";
        for (String path : new String[] {"CandidateContext", "CandidateLedger", "CandidateContext/SourceFamilyRef",
                "CandidateLedger/[]/Candidate", "CandidateLedger/[]/DoorKey", "CandidateLedger/[]/Candidate/Intent",
                "CandidateContext/PolicyId", "CandidateLedger/[]/Candidate/Kind",
                "CandidateLedger/[]/Candidate/Intent/SourceSettingsSnapshot",
                "CandidateLedger/[]/Candidate/Intent/TargetSettingsSnapshot/Parameters",
                "CandidateLedger/[]/Candidate/Intent/SourceSettingsSnapshot/Parameters/OverworldPreset"}) {
            var file = raw("scope-" + path.replace('/', '-'), out -> writeRoot(out, m6, base + path));
            assertThrows(IOException.class, () -> SessionJournalStore.read(file), path);
        }
        // 未改的 QuantumState 深 payload 重複鍵沿 legacy 契約，不泛化成全圖 strict。
        var old = validM6Wrapper(); var row = old.getCompound("data").getList("Records", 10).getCompound(0);
        row.put("CandidateContext", SessionRecoverySchema3Test.context(SessionRecoverySchema3Test.fixture(false, false)));
        row.put("CandidateLedger", SessionRecoverySchema3Test.ledger(SessionRecoverySchema3Test.fixture(false, false)));
        row.getList("Participants", 10).getCompound(0).getCompound("QuantumState").putInt("Historical", 1);
        old.getCompound("data").getList("Records", 10).add(m6.getCompound("data").getList("Records", 10).getCompound(0).copy());
        var file = raw("legacy-deep", out -> writeRoot(out, old, base + "Participants/[]/QuantumState/Historical"));
        assertEquals(old, SessionJournalStore.read(file));
        var reversed = old.copy(); var reverseRows = reversed.getCompound("data").getList("Records", 10);
        var first = reverseRows.remove(0); reverseRows.add(first);
        var reverseFile = raw("legacy-after-m6", out -> writeRoot(out, reversed, base + "Participants/[]/QuantumState/Historical"));
        assertEquals(reversed, SessionJournalStore.read(reverseFile));
    }
    private static NbtCompound validM6Wrapper() {
        var data = SessionRecoverySchema3Test.fixture(false, false); data.putInt("SchemaVersion", 5);
        var row = SessionRecoverySchema3Test.record(data); row.put("Collapse", new NbtCompound());
        row.put("CandidateContext", CandidateJournalCodecTest.m6Context());
        var ledger = new NbtList(); ledger.add(CandidateJournalCodecTest.m6Entry(row.getUuid("SessionUuid"))); row.put("CandidateLedger", ledger);
        var wrapped = new NbtCompound(); wrapped.putInt("DataVersion", 3953); wrapped.put("data", data); return wrapped;
    }
    private static void writeRoot(DataOutputStream out, NbtCompound nbt, String duplicate) throws IOException {
        out.writeByte(10); out.writeUTF(""); writePayload(out, nbt, "", duplicate);
    }
    private static void writePayload(DataOutputStream out, NbtElement element, String path, String duplicate) throws IOException {
        if (element instanceof NbtCompound compound) {
            for (String key : compound.getKeys()) {
                String childPath = path.isEmpty() ? key : path + "/" + key;
                var child = compound.get(key);
                for (int i = 0; i < (childPath.equals(duplicate) ? 2 : 1); i++) {
                    out.writeByte(child.getType()); out.writeUTF(key); writePayload(out, child, childPath, duplicate);
                }
            }
            out.writeByte(0);
        } else if (element instanceof NbtList list) {
            out.writeByte(list.getHeldType()); out.writeInt(list.size());
            for (var child : list) writePayload(out, child, path + "/[]", duplicate);
        } else element.write(out);
    }
    private Path raw(String name, Raw raw) throws Exception {
        var file = directory.resolve(name + ".dat");
        try (var out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(file)))) { raw.write(out); }
        return file;
    }
    private static void header(DataOutputStream out) throws IOException {
        out.writeByte(10); out.writeUTF(""); integer(out, "DataVersion", 3953); out.writeByte(10); out.writeUTF("data");
    }
    private static void integer(DataOutputStream out, String key, int value) throws IOException { out.writeByte(3); out.writeUTF(key); out.writeInt(value); }
    private static void records(DataOutputStream out) throws IOException { out.writeByte(9); out.writeUTF("Records"); out.writeByte(0); out.writeInt(0); }
    private static void end(DataOutputStream out) throws IOException { out.writeByte(0); out.writeByte(0); }
}
