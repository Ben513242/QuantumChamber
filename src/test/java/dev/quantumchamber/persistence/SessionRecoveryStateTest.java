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
            assertEquals(input, state.writeNbt(new NbtCompound()));
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
        assertEquals(1, SessionJournalStore.read(target).getCompound("data").getInt("SchemaVersion"));
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
