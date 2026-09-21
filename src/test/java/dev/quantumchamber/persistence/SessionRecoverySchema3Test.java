package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import java.io.*;
import java.util.*;
import java.util.function.Consumer;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class SessionRecoverySchema3Test {
    static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    static final UUID UNIVERSE = UUID.fromString("00000000-0000-0000-0000-000000000010");

    @Test void candidateFactoryStartsSelectableAndCanonicalConstructorCannotBypassInvariants() {
        var original = SessionRecoveryState.fromNbt(fixture(false, false)).records().get(SESSION);
        var context = original.candidateContext().orElseThrow();
        var fresh = SessionRecoveryRecord.candidateAware(original.sessionUuid(), original.chamberUuid(), original.origin(),
                original.participants(), original.spaceLeases(), SessionState.ARMING, false, original.semantics(), context);
        assertEquals(Optional.of(context), fresh.candidateContext()); assertTrue(fresh.candidateLedger().isEmpty());
        assertEquals(Optional.of(new CandidateSelection.Selectable()), fresh.candidateSelection());
        assertThrows(UnsupportedOperationException.class, () -> original.candidateLedger().clear());
        assertThrows(IllegalArgumentException.class, () -> copy(original, Optional.empty(), original.candidateLedger(), original.candidateSelection(), original.state()));
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), original.candidateLedger(), Optional.empty(), original.state()));
        assertThrows(IllegalArgumentException.class, () -> copy(original, Optional.empty(), List.of(), original.candidateSelection(), original.state()));
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), original.candidateLedger(), original.candidateSelection(), SessionState.MEASURED));
        assertThrows(IllegalArgumentException.class, () -> copy(original, Optional.empty(), List.of(), Optional.empty(), SessionState.MEASURED));
        var chosen = original.candidateLedger().getFirst();
        for (int revision : new int[] {0, 2}) assertThrows(IllegalArgumentException.class,
                () -> new CandidateSelection.Selected(chosen.doorKey(), chosen.candidate().candidateId(), UNIVERSE, 0, revision));
        assertThrows(IllegalArgumentException.class, () -> new CandidateSelection.Selected(chosen.doorKey(), chosen.candidate().candidateId(), UNIVERSE, -1, 1));
        var selected = new CandidateSelection.Selected(chosen.doorKey(), chosen.candidate().candidateId(), UNIVERSE, 0, 1);
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), original.candidateLedger(), Optional.of(selected), SessionState.SUPERPOSITION));
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), List.of(), Optional.of(selected), SessionState.MEASURED));
        var foreign = new CandidateLedgerEntry(new DoorKey(UNIVERSE, 0, DoorKey.DoorWallSide.NEGATIVE_LATERAL), chosen.candidate());
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), List.of(foreign), original.candidateSelection(), original.state()));
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), List.of(chosen, chosen), original.candidateSelection(), original.state()));
        var duplicateId = new CandidateLedgerEntry(new DoorKey(SESSION, 999, DoorKey.DoorWallSide.NEGATIVE_LATERAL), chosen.candidate());
        assertThrows(IllegalArgumentException.class, () -> copy(original, original.candidateContext(), List.of(chosen, duplicateId), original.candidateSelection(), original.state()));
    }

    private static SessionRecoveryRecord copy(SessionRecoveryRecord original, Optional<CandidatePolicySnapshot> context,
            List<CandidateLedgerEntry> ledger, Optional<CandidateSelection> selection, SessionState phase) {
        return new SessionRecoveryRecord(original.sessionUuid(), original.chamberUuid(), original.origin(), original.participants(),
                original.spaceLeases(), phase, false, original.semantics(), context, ledger, selection);
    }

    @Test void allCandidateAndSourceVariantsRoundtripWithExactBytesAndCanonicalLedgerOrder() {
        for (boolean catalog : new boolean[] {false, true}) for (boolean selected : new boolean[] {false, true}) {
            var input = fixture(selected, catalog);
            var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
            var saved = state.writeNbt(new NbtCompound());
            assertEquals(3, saved.getInt("SchemaVersion"));
            var ledger = ledger(saved);
            assertEquals(-2, ledger.getCompound(0).getCompound("DoorKey").getLong("LogicalStationIndex"));
            assertEquals("NEGATIVE_LATERAL", ledger.getCompound(0).getCompound("DoorKey").getString("WallSide"));
            assertEquals("POSITIVE_LATERAL", ledger.getCompound(1).getCompound("DoorKey").getString("WallSide"));
            assertEquals(5, ledger.getCompound(2).getCompound("DoorKey").getLong("LogicalStationIndex"));
            assertArrayEquals(bytes(1), ledger.getCompound(0).getCompound("Candidate").getByteArray("CandidateId"));
            assertArrayEquals(bytes(21), ledger.getCompound(1).getCompound("Candidate").getCompound("Intent").getByteArray("AllocationToken"));
            assertArrayEquals(bytes(31), ledger.getCompound(1).getCompound("Candidate").getCompound("Intent").getByteArray("GenerationSeedMaterial"));
            assertArrayEquals(bytes(41), context(saved).getByteArray("EntropyFingerprint"));
            var expected = input.copy(); var canonical = new NbtList();
            canonical.add(ledger(input).getCompound(1).copy()); canonical.add(ledger(input).getCompound(2).copy()); canonical.add(ledger(input).getCompound(0).copy());
            record(expected).put("CandidateLedger", canonical);
            assertEquals(expected, saved);
            assertEquals(state.records(), SessionRecoveryState.fromNbt(saved).records());
        }
    }

    @Test void selectableStatesAllowEmptyLedgerWithEndOrCompoundHeldType() throws Exception {
        for (String phase : List.of("ARMING", "SUPERPOSITION", "RETURNING")) for (byte type : new byte[] {0, 10}) {
            var input = fixture(false, false); record(input).putString("State", phase);
            record(input).put("CandidateLedger", emptyList(type));
            var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
            assertTrue(ledger(state.writeNbt(new NbtCompound())).isEmpty());
            assertEquals(3, state.writeNbt(new NbtCompound()).getInt("SchemaVersion"));
        }
    }

    @Test void wrongHeldTypesFailEvenWhenListsAreEmpty() throws Exception {
        for (String field : List.of("Records", "CandidateLedger", "Participants", "SpaceLeases")) {
            for (byte type : new byte[] {1, 8}) {
                var input = fixture(false, false);
                (field.equals("Records") ? input : record(input)).put(field, emptyList(type));
                unhealthy(input);
                var nonempty = new NbtList(); nonempty.add(NbtString.of("bad"));
                (field.equals("Records") ? input : record(input)).put(field, nonempty);
                unhealthy(input);
            }
        }
    }

    @Test void malformedPolicyFieldsFailTheWholeState() {
        Map<String, NbtElement> invalid = new LinkedHashMap<>();
        invalid.put("PolicyId", NbtString.of("quantumchamber:unknown"));
        invalid.put("PolicyVersion", NbtInt.of(2)); invalid.put("CandidateSchemaVersion", NbtInt.of(2));
        invalid.put("SourceWeight", NbtInt.of(6)); invalid.put("DiscoveredWeight", NbtInt.of(19)); invalid.put("NewWeight", NbtInt.of(74));
        invalid.put("DiscoveryWatermark", NbtLong.of(-2)); invalid.put("MaxCandidateEntries", NbtInt.of(1));
        invalid.put("EntropyFingerprint", new NbtByteArray(new byte[31]));
        for (var change : invalid.entrySet()) {
            var input = fixture(false, false); context(input).put(change.getKey(), change.getValue()); unhealthy(input);
        }
        for (String key : context(fixture(false, false)).getKeys()) {
            var missing = fixture(false, false); context(missing).remove(key); unhealthy(missing);
            var wrongType = fixture(false, false); context(wrongType).put(key, new NbtList()); unhealthy(wrongType);
        }
    }

    @Test void variantsRejectUnknownOrForeignPayloadAndInvalidEnumsVersionsAndLengths() {
        List<Consumer<NbtCompound>> mutations = List.of(
            n -> n.putString("SchemaVersion", "3"), n -> n.putInt("SchemaVersion", 4),
            n -> record(n).putString("State", "UNKNOWN"),
            n -> source(n).putString("Kind", "UNKNOWN"),
            n -> source(n).putString("Role", "UNKNOWN"),
            n -> source(n).putString("WorldKey", "minecraft:the_nether"),
            n -> source(n).putUuid("UniverseId", UNIVERSE),
            n -> source(n).remove("WorldKey"),
            n -> candidate(n, 0).putString("Kind", "UNKNOWN"),
            n -> candidate(n, 0).remove("UniverseId"),
            n -> candidate(n, 1).putUuid("UniverseId", UNIVERSE),
            n -> candidate(n, 1).put("Intent", new NbtCompound()),
            n -> candidate(n, 2).putUuid("UniverseId", UNIVERSE),
            n -> intent(n).putString("Profile", "UNKNOWN"),
            n -> intent(n).putInt("ProfileVersion", 2),
            n -> door(n, 0).putString("WallSide", "UNKNOWN"),
            n -> door(n, 0).putInt("LogicalStationIndex", 5),
            n -> record(n).remove("CandidateContext"),
            n -> record(n).remove("CandidateLedger"),
            n -> record(n).remove("CandidateSelection"),
            n -> selection(n).putString("Kind", "UNKNOWN"),
            n -> selection(n).putLong("SelectedAtGameTime", 0)
        );
        for (var mutate : mutations) { var input = fixture(false, false); mutate.accept(input); unhealthy(input); }
        for (int length : new int[] {0, 31, 33}) for (String field : List.of("CandidateId", "AllocationToken", "GenerationSeedMaterial", "EntropyFingerprint")) {
            var input = fixture(false, false);
            (field.equals("CandidateId") ? candidate(input, 0) : field.equals("EntropyFingerprint") ? context(input) : intent(input))
                    .putByteArray(field, new byte[length]); unhealthy(input);
        }
        var catalog = fixture(false, true); source(catalog).putString("WorldKey", "minecraft:overworld"); unhealthy(catalog);
        catalog = fixture(false, true); source(catalog).remove("UniverseId"); unhealthy(catalog);
    }

    @Test void duplicateKeysIdsAndForeignSessionsAreRejectedWithoutPartialRecords() {
        List<Consumer<NbtCompound>> mutations = List.of(
            n -> ledger(n).add(ledger(n).getCompound(0).copy()),
            n -> candidate(n, 1).putByteArray("CandidateId", bytes(2)),
            n -> door(n, 0).putUuid("SessionUuid", UNIVERSE),
            n -> selection(n).getCompound("DoorKey").putUuid("SessionUuid", UNIVERSE),
            n -> selection(n).getCompound("DoorKey").putLong("LogicalStationIndex", 99),
            n -> selection(n).putByteArray("CandidateId", bytes(3)),
            n -> record(n).put("CandidateLedger", new NbtList()),
            n -> selection(n).putLong("SelectedAtGameTime", -1),
            n -> selection(n).putInt("SelectionRevision", 0),
            n -> selection(n).putInt("SelectionRevision", 2),
            n -> selection(n).putInt("SelectedAtGameTime", 0),
            n -> selection(n).putByteArray("CandidateId", new byte[31]),
            n -> selection(n).remove("SelectedBy")
        );
        for (var mutate : mutations) { var input = fixture(true, false); mutate.accept(input); unhealthy(input); }
    }

    @Test void stateSelectionMatrixIsExactAndLegacyCannotCarryCandidatePayload() {
        for (String phase : List.of("ARMING", "SUPERPOSITION", "RETURNING", "MEASURED")) for (boolean selected : new boolean[] {false, true}) {
            var input = fixture(selected, false); record(input).putString("State", phase);
            if (phase.equals("MEASURED") == selected) SessionRecoveryState.fromNbt(input).requireHealthy();
            else unhealthy(input);
        }
        for (int schema : new int[] {1, 2}) {
            var measured = SessionRecoveryStateTest.fixture("MEASURED", false); measured.putInt("SchemaVersion", schema);
            record(measured).putString("SessionSemantics", "LATERAL_BUFF_MAINTAINED"); unhealthy(measured);
            for (String field : List.of("CandidateContext", "CandidateLedger", "CandidateSelection")) {
                var input = SessionRecoveryStateTest.fixture("RETURNING", false); input.putInt("SchemaVersion", schema);
                record(input).putString("SessionSemantics", "LATERAL_BUFF_MAINTAINED");
                record(input).put(field, record(fixture(false, false)).get(field).copy()); unhealthy(input);
            }
        }
    }

    static NbtCompound fixture(boolean selected, boolean catalog) {
        var root = SessionRecoveryStateTest.fixture(selected ? "MEASURED" : "SUPERPOSITION", false); root.putInt("SchemaVersion", 3);
        var row = record(root); row.putString("SessionSemantics", "LATERAL_BUFF_MAINTAINED");
        var context = new NbtCompound(); context.putString("PolicyId", "quantumchamber:m4_v1");
        context.putInt("PolicyVersion", 1); context.putInt("CandidateSchemaVersion", 1);
        context.putInt("SourceWeight", 5); context.putInt("DiscoveredWeight", 20); context.putInt("NewWeight", 75);
        context.putLong("DiscoveryWatermark", -1); context.putInt("MaxCandidateEntries", 16384); context.putByteArray("EntropyFingerprint", bytes(41));
        var source = new NbtCompound(); source.putString("Kind", catalog ? "CATALOG" : "VANILLA"); source.putString("Role", "OVERWORLD");
        if (catalog) source.putUuid("UniverseId", UNIVERSE); else source.putString("WorldKey", "minecraft:overworld");
        context.put("SourceFamilyRef", source); row.put("CandidateContext", context);
        var ledger = new NbtList(); ledger.add(entry(5, "NEGATIVE_LATERAL", "EXISTING", 2));
        ledger.add(entry(-2, "NEGATIVE_LATERAL", "SOURCE", 1)); ledger.add(entry(-2, "POSITIVE_LATERAL", "NEW", 3));
        row.put("CandidateLedger", ledger);
        var selection = new NbtCompound(); selection.putString("Kind", selected ? "SELECTED" : "SELECTABLE");
        if (selected) {
            selection.put("DoorKey", ledger.getCompound(0).getCompound("DoorKey").copy()); selection.putByteArray("CandidateId", bytes(2));
            selection.putUuid("SelectedBy", UUID.fromString("00000000-0000-0000-0000-000000000002"));
            selection.putLong("SelectedAtGameTime", 0); selection.putInt("SelectionRevision", 1);
        }
        row.put("CandidateSelection", selection); return root;
    }
    private static NbtCompound entry(long station, String side, String kind, int id) {
        var entry = new NbtCompound(); var door = new NbtCompound(); door.putUuid("SessionUuid", SESSION);
        door.putLong("LogicalStationIndex", station); door.putString("WallSide", side); entry.put("DoorKey", door);
        var candidate = new NbtCompound(); candidate.putString("Kind", kind); candidate.putByteArray("CandidateId", bytes(id));
        if (kind.equals("EXISTING")) candidate.putUuid("UniverseId", UNIVERSE);
        if (kind.equals("NEW")) {
            var intent = new NbtCompound(); intent.putByteArray("AllocationToken", bytes(21));
            intent.putString("Profile", "VANILLA_OVERWORLD_SHARED_SEED_V1"); intent.putInt("ProfileVersion", 1);
            intent.putByteArray("GenerationSeedMaterial", bytes(31)); candidate.put("Intent", intent);
        }
        entry.put("Candidate", candidate); return entry;
    }
    static NbtList emptyList(byte type) throws Exception {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF(""); out.writeByte(9); out.writeUTF("List"); out.writeByte(type); out.writeInt(0); out.writeByte(0);
        var list = (NbtList) NbtIo.readCompound(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())), NbtSizeTracker.ofUnlimitedBytes()).get("List");
        assertNotNull(list); assertEquals(type, list.getHeldType()); return list;
    }
    static byte[] bytes(int seed) { byte[] result = new byte[32]; for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i); return result; }
    static NbtCompound record(NbtCompound root) { return root.getList("Records", 10).getCompound(0); }
    static NbtList ledger(NbtCompound root) { return record(root).getList("CandidateLedger", 10); }
    static NbtCompound context(NbtCompound root) { return record(root).getCompound("CandidateContext"); }
    private static NbtCompound source(NbtCompound root) { return context(root).getCompound("SourceFamilyRef"); }
    private static NbtCompound selection(NbtCompound root) { return record(root).getCompound("CandidateSelection"); }
    private static NbtCompound candidate(NbtCompound root, int index) { return ledger(root).getCompound(index).getCompound("Candidate"); }
    private static NbtCompound door(NbtCompound root, int index) { return ledger(root).getCompound(index).getCompound("DoorKey"); }
    private static NbtCompound intent(NbtCompound root) { return candidate(root, 2).getCompound("Intent"); }
    static void unhealthy(NbtCompound input) {
        var state = SessionRecoveryState.fromNbt(input);
        assertThrows(IllegalStateException.class, state::requireHealthy);
        assertThrows(IllegalStateException.class, state::records);
        assertThrows(IllegalStateException.class, state::flushedRecords);
        assertThrows(IllegalStateException.class, () -> state.writeNbt(new NbtCompound()));
    }
}
