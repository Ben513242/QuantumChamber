package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

class SessionRecoveryStateTest {
    @TempDir Path directory;
    @BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }

    @Test void schemaTwoRoundtripsLateralFalseForEveryStateWithoutRotatingBounds() {
        for (var phase : java.util.List.of(dev.quantumchamber.superposition.SessionState.ARMING,
                dev.quantumchamber.superposition.SessionState.SUPERPOSITION, dev.quantumchamber.superposition.SessionState.RETURNING)) {
            var input=fixture(phase.name(),false); input.putInt("SchemaVersion",2);
            record(input).putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");
            var state=SessionRecoveryState.fromNbt(input); state.requireHealthy();
            var decoded=state.records().values().iterator().next();
            assertEquals(SessionSemantics.LATERAL_BUFF_MAINTAINED,decoded.semantics());
            assertEquals(input,state.writeNbt(new NbtCompound()));
            assertFalse(state.isDirty());
            var invalid=input.copy(); record(invalid).putBoolean("RestoreEntryEffectOnReturn",true); unhealthy(invalid);
        }
    }

    @Test void legacyRecordsRemainCandidateEmptyAndEnvelopeCannotMixGenerations() {
        for (int schema : new int[] {1, 2}) {
            var input = fixture("RETURNING", false); input.putInt("SchemaVersion", schema);
            record(input).putString("SessionSemantics", "LATERAL_BUFF_MAINTAINED");
            var decoded = SessionRecoveryState.fromNbt(input).records().values().iterator().next();
            assertTrue(decoded.candidateContext().isEmpty()); assertTrue(decoded.candidateLedger().isEmpty());
            assertTrue(decoded.candidateSelection().isEmpty());
            assertEquals(2, SessionRecoveryState.fromNbt(input).writeNbt(new NbtCompound()).getInt("SchemaVersion"));
        }
        var legacy = fixture("RETURNING", false); var legacyRow = record(legacy);
        legacyRow.putUuid("SessionUuid", UUID.fromString("00000000-0000-0000-0000-000000000030"));
        var chamber = UUID.fromString("00000000-0000-0000-0000-000000000031");
        legacyRow.putUuid("ChamberUuid", chamber); legacyRow.getCompound("Origin").putUuid("ChamberUuid", chamber);
        participant(legacy).putUuid("PlayerUuid", UUID.fromString("00000000-0000-0000-0000-000000000032"));
        legacyRow.getList("SpaceLeases", 10).getCompound(0).putInt("SlotId", 1);
        var oldRecord = SessionRecoveryState.fromNbt(legacy).records().values().iterator().next();
        var current = SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false, false)).records().values().iterator().next();
        for (boolean legacyFirst : new boolean[] {false, true}) {
            var state = new SessionRecoveryState(); state.put(legacyFirst ? oldRecord : current);
            var before = state.records(); var flushed = state.flushedRecords(); boolean dirty = state.isDirty();
            assertThrows(IllegalArgumentException.class, () -> state.put(legacyFirst ? current : oldRecord));
            assertEquals(before, state.records()); assertEquals(flushed, state.flushedRecords()); assertEquals(dirty, state.isDirty());
        }
        var mixed = SessionRecoverySchema3Test.fixture(false, false); mixed.getList("Records", 10).add(legacyRow.copy());
        unhealthy(mixed); mixed.putInt("SchemaVersion", 2); unhealthy(mixed);
        assertEquals(2, new SessionRecoveryState().writeNbt(new NbtCompound()).getInt("SchemaVersion"));
    }

    @Test void schemaTwoRequiresKnownStringSemanticsAndStrictEffectPolicy() {
        for (var bad : new NbtElement[] {NbtString.of("UNKNOWN"),NbtInt.of(1),NbtByte.of(true)}) {
            var input=fixture("RETURNING",false); input.putInt("SchemaVersion",2);
            record(input).put("SessionSemantics",bad); unhealthy(input);
        }
        var missing=fixture("RETURNING",false); missing.putInt("SchemaVersion",2); unhealthy(missing);
        for (var mode : SessionSemantics.values()) {
            var input=fixture("RETURNING",false); input.putInt("SchemaVersion",2);
            record(input).putString("SessionSemantics",mode.name());
            record(input).remove("RestoreEntryEffectOnReturn"); unhealthy(input);
        }
    }

    @Test void schemaOneAlwaysUsesStrictLegacyEvenWithLateralMetadata() {
        var input=fixture("ARMING",false); record(input).putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");
        unhealthy(input);
        input=fixture("ARMING",true); record(input).putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");
        var decoded=SessionRecoveryState.fromNbt(input).records().values().iterator().next();
        assertEquals(SessionSemantics.LEGACY_FORWARD_CONSUMED,decoded.semantics());
        assertEquals(SessionSemantics.LEGACY_FORWARD_CONSUMED,new SessionRecoveryRecord(decoded.sessionUuid(),decoded.chamberUuid(),
                decoded.origin(),decoded.participants(),decoded.spaceLeases(),decoded.state(),true).semantics());
    }

    @Test void loadingSchemaOneDoesNotRewriteUntilCheckedProgressSave() throws Exception {
        var input=fixture("RETURNING",false); var wrapper=new NbtCompound(); wrapper.put("data",input); wrapper.putInt("DataVersion",3953);
        var path=directory.resolve("legacy.dat"); NbtIo.writeCompressed(wrapper,path);
        byte[] original=java.nio.file.Files.readAllBytes(path);
        var state=SessionRecoveryState.load(path); state.requireHealthy(); state.save(path.toFile(),null);
        assertArrayEquals(original,java.nio.file.Files.readAllBytes(path)); assertFalse(state.isDirty());
        var decoded=state.records().values().iterator().next(); state.put(decoded); state.save(path.toFile(),null);
        var saved=SessionJournalStore.read(path).getCompound("data");
        assertEquals(2,saved.getInt("SchemaVersion"));
        assertEquals("LEGACY_FORWARD_CONSUMED",record(saved).getString("SessionSemantics"));
        assertEquals(record(input).getList("SpaceLeases",10),record(saved).getList("SpaceLeases",10));
    }

    @Test void completeAuthorityCannotChangeCurrentFlushedOrRemoveReplay() {
        var original=SessionRecoveryState.fromNbt(fixture("RETURNING",false)).records().values().iterator().next();
        for (var changed : authorityChanges(original)) for (int mode=0;mode<3;mode++) {
            var state=mode==0 ? new SessionRecoveryState() : SessionRecoveryState.fromNbt(fixture("RETURNING",false));
            if (mode==0) state.put(original);
            if (mode==2) state.remove(original.sessionUuid());
            var before=state.records(); var flushed=state.flushedRecords(); boolean dirty=state.isDirty();
            assertThrows(IllegalArgumentException.class,() -> state.put(changed));
            assertFalse(SessionRecoveryRecord.sameAuthority(original,changed));
            assertEquals(before,state.records()); assertEquals(flushed,state.flushedRecords()); assertEquals(dirty,state.isDirty());
        }
        var otherSid=new SessionRecoveryRecord(UUID.fromString("00000000-0000-0000-0000-000000000009"),original.chamberUuid(),
                original.origin(),original.participants(),original.spaceLeases(),original.state(),false,original.semantics());
        assertFalse(SessionRecoveryRecord.sameAuthority(original,otherSid));
    }

    @Test void authorityIgnoresProgressAndLeasesButRetainsLateralMode() {
        var input=fixture("ARMING",false); input.putInt("SchemaVersion",2);
        record(input).putString("SessionSemantics","LATERAL_BUFF_MAINTAINED");
        var state=SessionRecoveryState.fromNbt(input); var original=state.records().values().iterator().next();
        var people=original.participants().stream().map(p -> new SessionRecoveryRecord.Participant(p.playerUuid(),p.sourcePosition(),
                p.sourceVelocity(),p.yaw(),p.pitch(),p.quantumStateSnapshot(),true)).toList();
        var progress=new SessionRecoveryRecord(original.sessionUuid(),original.chamberUuid(),original.origin(),people,
                java.util.List.of(new SessionRecoveryRecord.SpaceLease(1,new net.minecraft.util.math.BlockBox(96,0,0,191,20,10))),
                dev.quantumchamber.superposition.SessionState.RETURNING,false,original.semantics());
        assertTrue(SessionRecoveryRecord.sameAuthority(original,progress)); state.put(progress);
        assertEquals(SessionSemantics.LATERAL_BUFF_MAINTAINED,state.records().get(original.sessionUuid()).semantics());
        assertEquals(original,state.flushedRecords().get(original.sessionUuid()));
    }

    private static java.util.List<SessionRecoveryRecord> authorityChanges(SessionRecoveryRecord original) {
        var changes=new java.util.ArrayList<SessionRecoveryRecord>();
        changes.add(new SessionRecoveryRecord(original.sessionUuid(),original.chamberUuid(),original.origin(),original.participants(),
                original.spaceLeases(),original.state(),false,SessionSemantics.LATERAL_BUFF_MAINTAINED));
        for (int field=0;field<9;field++) {
            var input=fixture("RETURNING",false); var source=record(input).getCompound("Origin"); var person=participant(input);
            switch (field) {
                case 0 -> { var id=UUID.fromString("00000000-0000-0000-0000-000000000008"); record(input).putUuid("ChamberUuid",id); source.putUuid("ChamberUuid",id); }
                case 1 -> source.putIntArray("ControllerPos",new int[] {8,70,9});
                case 2 -> source.putString("Facing","SOUTH");
                case 3 -> { source.putString("WorldKey","minecraft:the_nether"); source.putString("Role","NETHER"); }
                case 4 -> person.put("SourcePosition",vector(8.5,66,9.5));
                case 5 -> person.put("SourceVelocity",vector(0.2,-0.2,0.3));
                case 6 -> person.putFloat("Yaw",91);
                case 7 -> person.putFloat("Pitch",-14);
                case 8 -> person.getCompound("QuantumState").getCompound("hidden_effect").putInt("duration",2001);
            }
            changes.add(SessionRecoveryState.fromNbt(input).records().values().iterator().next());
        }
        return changes;
    }

    @Test void currentSourceCannotChangeBeforeFirstFlush() {
        var original=SessionRecoveryState.fromNbt(fixture("ARMING",true)).records().values().iterator().next();
        var state=new SessionRecoveryState(); state.put(original);
        var person=original.participants().getFirst();
        var changed=new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition().add(1,0,0),person.sourceVelocity(),
                person.yaw(),person.pitch(),person.quantumStateSnapshot(),false);
        assertThrows(IllegalArgumentException.class,() -> state.put(withPeople(original,java.util.List.of(changed))));
        assertEquals(original,state.records().get(original.sessionUuid()));
        assertTrue(state.isDirty()); assertTrue(state.flushedRecords().isEmpty());
    }

    @Test void flushedSourceCannotBeBypassedByRemovingCurrentRecord() {
        var state=SessionRecoveryState.fromNbt(fixture("RETURNING",false));
        var original=state.records().values().iterator().next();
        var person=original.participants().getFirst();
        var snapshot=person.quantumStateSnapshot(); snapshot.getCompound("hidden_effect").putInt("duration",2001);
        var changed=new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),
                person.yaw(),person.pitch(),snapshot,true);
        state.remove(original.sessionUuid());
        assertThrows(IllegalArgumentException.class,() -> state.put(withPeople(original,java.util.List.of(changed))));
        assertTrue(state.records().isEmpty()); assertTrue(state.isDirty());
        assertEquals(original,state.flushedRecords().get(original.sessionUuid()));
    }

    @Test void sameSidCannotShrinkGrowOrReplaceFrozenUuids() {
        var input=fixture("RETURNING",false);
        var second=participant(input).copy(); second.putUuid("PlayerUuid",UUID.fromString("00000000-0000-0000-0000-000000000004"));
        record(input).getList("Participants",10).add(second);
        var state=SessionRecoveryState.fromNbt(input); var original=state.records().values().iterator().next();
        var first=original.participants().getFirst();
        var third=new SessionRecoveryRecord.Participant(UUID.fromString("00000000-0000-0000-0000-000000000005"),first.sourcePosition(),first.sourceVelocity(),
                first.yaw(),first.pitch(),first.quantumStateSnapshot(),true);
        for(var people : java.util.List.of(java.util.List.of(first),java.util.List.of(first,third),
                java.util.List.of(first,original.participants().getLast(),third))) {
            assertThrows(IllegalArgumentException.class,() -> state.put(withPeople(original,people)));
            assertEquals(original,state.records().get(original.sessionUuid()));
            assertEquals(original,state.flushedRecords().get(original.sessionUuid())); assertFalse(state.isDirty());
        }
    }

    @Test void returnedOnlyUpdatesCanReorderAndPersistCompleteSources() throws Exception {
        var input=fixture("RETURNING",false);
        var second=participant(input).copy(); second.putUuid("PlayerUuid",UUID.fromString("00000000-0000-0000-0000-000000000004"));
        record(input).getList("Participants",10).add(second);
        var state=SessionRecoveryState.fromNbt(input); var original=state.records().values().iterator().next();
        var reordered=new java.util.ArrayList<SessionRecoveryRecord.Participant>();
        for(var person : original.participants().reversed()) reordered.add(new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),person.sourceVelocity(),
                person.yaw(),person.pitch(),person.quantumStateSnapshot(),true));
        var progress=withPeople(original,reordered); state.put(progress);
        assertEquals(original,state.flushedRecords().get(original.sessionUuid()));
        var path=directory.resolve("returned-only.dat"); state.save(path.toFile(),null);
        assertEquals(progress,SessionRecoveryState.load(path).records().get(original.sessionUuid()));
        assertFalse(state.isDirty());
    }

    private static SessionRecoveryRecord withPeople(SessionRecoveryRecord original,java.util.List<SessionRecoveryRecord.Participant> people) {
        return new SessionRecoveryRecord(original.sessionUuid(),original.chamberUuid(),original.origin(),people,original.spaceLeases(),
                original.state(),original.restoreEntryEffectOnReturn(),original.semantics());
    }

    @Test void actualMissingJournalIsHealthyButCorruptionAndDirectoryAreUnhealthy() throws Exception {
        var absent = directory.resolve("missing.dat");
        SessionRecoveryState.load(absent).requireHealthy();
        java.nio.file.Files.write(absent, new byte[] {1,2,3,4});
        assertThrows(IllegalStateException.class, SessionRecoveryState.load(absent)::requireHealthy);
        assertThrows(IllegalStateException.class, SessionRecoveryState.load(directory)::requireHealthy);
        var wrapper = new NbtCompound(); wrapper.putInt("DataVersion", 3953);
        var bad = fixture("ARMING", true); bad.putInt("SchemaVersion", 99); wrapper.put("data", bad);
        NbtIo.writeCompressed(wrapper, absent);
        var failed = SessionRecoveryState.load(absent);
        assertThrows(IllegalStateException.class, failed::requireHealthy);
        assertThrows(IllegalStateException.class, () -> failed.save(absent.toFile(), null));
        assertEquals(wrapper, NbtIo.readCompressed(absent, NbtSizeTracker.ofUnlimitedBytes()));
    }

    @Test void returningRoundtripPreservesBothAuthoritativeEffectPoliciesAndAllSourceData() {
        for (boolean restore : new boolean[] {true, false}) {
            var input = fixture("RETURNING", restore);
            var state = SessionRecoveryState.fromNbt(input);
            state.requireHealthy();
            var expected=input.copy(); expected.putInt("SchemaVersion",2);
            record(expected).putString("SessionSemantics","LEGACY_FORWARD_CONSUMED");
            assertEquals(expected, state.writeNbt(new NbtCompound()));
            assertEquals(state.records(), state.flushedRecords());
        }
    }

    @Test void missingOrWrongSchemaAndMissingSourceFailClosedWithoutPartialRecords() {
        var input = fixture("ARMING", true);
        input.putInt("SchemaVersion", 99);
        unhealthy(input);
        input = fixture("ARMING", true);
        input.remove("SchemaVersion");
        unhealthy(input);
        input = fixture("ARMING", true);
        participant(input).remove("SourcePosition");
        unhealthy(input);
        input = fixture("ARMING", true);
        participant(input).putString("SourceVelocity", "not-a-vector");
        unhealthy(input);
    }

    @Test void restorePolicyIsRequiredStrictBooleanAndConsistentWithEntryState() {
        for (var bad : new NbtElement[] {NbtInt.of(1), NbtByte.of((byte) 2), NbtString.of("true")}) {
            var input = fixture("RETURNING", true);
            record(input).put("RestoreEntryEffectOnReturn", bad);
            unhealthy(input);
        }
        var missing = fixture("RETURNING", true);
        record(missing).remove("RestoreEntryEffectOnReturn");
        unhealthy(missing);
        unhealthy(fixture("ARMING", false));
        unhealthy(fixture("SUPERPOSITION", true));
    }

    @Test void duplicateSessionParticipantLeaseAndNonvanillaAuthorityAreRejected() {
        var input = fixture("ARMING", true);
        input.getList("Records", 10).add(record(input).copy());
        unhealthy(input);
        input = fixture("ARMING", true);
        record(input).getList("Participants", 10).add(participant(input).copy());
        unhealthy(input);
        input = fixture("ARMING", true);
        var leases = record(input).getList("SpaceLeases", 10);
        leases.add(leases.getCompound(0).copy());
        unhealthy(input);
        input = fixture("ARMING", true);
        record(input).getCompound("Origin").putString("WorldKey", "quantumchamber:superposition");
        unhealthy(input);
        input = fixture("ARMING", true);
        record(input).getCompound("Origin").putString("Role", "NETHER");
        unhealthy(input);
    }

    @Test void missingEmptyOrInvertedLeasesAreRejected() {
        var input = fixture("ARMING", true);
        record(input).remove("SpaceLeases");
        unhealthy(input);
        input = fixture("ARMING", true);
        record(input).put("SpaceLeases", new NbtList());
        unhealthy(input);
        input = fixture("ARMING", true);
        record(input).getList("SpaceLeases", 10).getCompound(0).putIntArray("Bounds", new int[] {2,0,0,1,10,10});
        unhealthy(input);
    }

    @Test void recordsAndFlushedSnapshotAreDefensiveAndDirtyIsNotDurable() {
        var input = fixture("ARMING", true);
        var state = SessionRecoveryState.fromNbt(input);
        var record = state.records().values().iterator().next();
        record.participants().getFirst().quantumStateSnapshot().putString("id", "tampered");
        record.spaceLeases().getFirst().bounds().move(500, 0, 0);
        participant(input).putString("QuantumState", "tampered");
        assertEquals("quantumchamber:quantum_state", record.participants().getFirst().quantumStateSnapshot().getString("id"));
        assertEquals(0, record.spaceLeases().getFirst().bounds().getMinX());
        assertThrows(UnsupportedOperationException.class, () -> state.records().clear());
        assertThrows(UnsupportedOperationException.class, () -> record.participants().clear());
        assertTrue(state.remove(record.sessionUuid()));
        assertTrue(state.records().isEmpty());
        assertTrue(state.isDirty());
        assertEquals(1, state.flushedRecords().size());
        state.put(record);
        assertEquals(1, state.records().size());
    }

    @Test void successfulSaveUpdatesDurableSnapshotFailedSaveKeepsOldAndDirty() throws Exception {
        var state = SessionRecoveryState.fromNbt(fixture("ARMING", true));
        var id = state.records().keySet().iterator().next();
        state.remove(id);
        var target = directory.resolve("journal.dat");
        state.save(target.toFile(), null);
        assertFalse(state.isDirty());
        assertTrue(state.flushedRecords().isEmpty());
        assertEquals(2, SessionJournalStore.read(target).getCompound("data").getInt("SchemaVersion"));
        state.put(SessionRecoveryState.fromNbt(fixture("ARMING", true)).records().values().iterator().next());
        assertThrows(UncheckedIOException.class, () -> state.save(target.resolve("impossible.dat").toFile(), null));
        assertTrue(state.isDirty());
        assertTrue(state.flushedRecords().isEmpty());
        assertTrue(SessionJournalStore.read(target).getCompound("data").getList("Records", 10).isEmpty());
    }

    static NbtCompound fixture(String state, boolean restore) {
        UUID chamber = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var origin = new NbtCompound();
        origin.putUuid("ChamberUuid", chamber);
        origin.putString("WorldKey", "minecraft:overworld");
        origin.putString("Role", "OVERWORLD");
        origin.putIntArray("ControllerPos", new int[] {7,70,9});
        origin.putString("Facing", "NORTH");
        origin.putString("InstanceKind", "ORIGIN");
        var participant = new NbtCompound();
        participant.putUuid("PlayerUuid", UUID.fromString("00000000-0000-0000-0000-000000000002"));
        participant.put("SourcePosition", vector(7.5, 66.0, 9.5));
        participant.put("SourceVelocity", vector(0.1, -0.2, 0.3));
        participant.putFloat("Yaw", 90.0f);
        participant.putFloat("Pitch", -15.0f);
        var effect = new NbtCompound();
        effect.putString("id", "quantumchamber:quantum_state");
        effect.putInt("duration", 1000);
        var hidden = new NbtCompound(); hidden.putInt("duration", 2000);
        effect.put("hidden_effect", hidden);
        participant.put("QuantumState", effect);
        participant.putBoolean("Returned", false);
        var participants = new NbtList(); participants.add(participant);
        var lease = new NbtCompound(); lease.putInt("SlotId", 0);
        lease.putIntArray("Bounds", new int[] {0,0,0,95,20,10});
        var leases = new NbtList(); leases.add(lease);
        var record = new NbtCompound();
        record.putUuid("SessionUuid", UUID.fromString("00000000-0000-0000-0000-000000000003"));
        record.putUuid("ChamberUuid", chamber); record.put("Origin", origin);
        record.put("Participants", participants); record.put("SpaceLeases", leases);
        record.putString("State", state); record.putBoolean("RestoreEntryEffectOnReturn", restore);
        var records = new NbtList(); records.add(record);
        var root = new NbtCompound(); root.putInt("SchemaVersion", 1); root.put("Records", records);
        return root;
    }
    private static NbtList vector(double x, double y, double z) {
        var list = new NbtList(); list.add(NbtDouble.of(x)); list.add(NbtDouble.of(y)); list.add(NbtDouble.of(z)); return list;
    }
    private static NbtCompound record(NbtCompound root) { return root.getList("Records", 10).getCompound(0); }
    private static NbtCompound participant(NbtCompound root) { return record(root).getList("Participants", 10).getCompound(0); }
    private static void unhealthy(NbtCompound input) {
        var state = SessionRecoveryState.fromNbt(input);
        assertThrows(IllegalStateException.class, state::requireHealthy);
        assertThrows(IllegalStateException.class, state::records);
        assertThrows(IllegalStateException.class, () -> state.writeNbt(new NbtCompound()));
    }
}
