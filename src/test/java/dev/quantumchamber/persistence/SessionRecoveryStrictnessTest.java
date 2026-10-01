package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Function;
import net.minecraft.nbt.*;
import net.minecraft.util.Identifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * H1 codec strictness：schema 3 的 Origin／Participants／SpaceLeases exact key、ledger 不超過 MaxCandidateEntries、
 * vanilla WorldKey 帶 namespace 且為 canonical；合法 schema 3 照舊 exact 讀回，schema 1／2 的寬鬆解析不變。
 */
class SessionRecoveryStrictnessTest {
    private static final UUID SESSION = SessionRecoverySchema3Test.SESSION;
    private static final String UNKNOWN_FIELD = "schema 3 payload 欄位缺失或含未知欄位";
    private static final String LEDGER_LIMIT = "schema 3 ledger 超過 MaxCandidateEntries";
    private static final String NOT_CANONICAL = "WorldKey 必須帶 namespace 且為 canonical 形式";

    @Test void ledgerAboveMaxCandidateEntriesFailsClosed() {
        rejected(withLedgerSize(16385), LEDGER_LIMIT);
    }

    @Test void ledgerAtMaxCandidateEntriesStillRoundtripsExactly() {
        var input = withLedgerSize(16384);
        var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
        assertEquals(16384, state.records().get(SESSION).candidateLedger().size());
        assertEquals(input, state.writeNbt(new NbtCompound()));
    }

    @Test void originRejectsUnknownKeys() {
        var inputs = new ArrayList<NbtCompound>();
        for (boolean selected : new boolean[] {false, true}) {
            var input = schemaThree(selected, false); origin(input).putString("UnknownOriginField", "unexpected"); inputs.add(input);
        }
        allRejected(inputs, UNKNOWN_FIELD);
    }

    @Test void participantsRejectUnknownKeys() {
        var inputs = new ArrayList<NbtCompound>();
        for (boolean selected : new boolean[] {false, true}) for (int index : new int[] {0, 1}) {
            var input = schemaThree(selected, false); participant(input, index).putString("UnknownParticipantField", "unexpected");
            inputs.add(input);
        }
        allRejected(inputs, UNKNOWN_FIELD);
    }

    @Test void spaceLeasesRejectUnknownKeys() {
        var inputs = new ArrayList<NbtCompound>();
        for (boolean selected : new boolean[] {false, true}) for (int index : new int[] {0, 1}) {
            var input = schemaThree(selected, false); lease(input, index).putIntArray("UnknownLeaseField", new int[] {1});
            inputs.add(input);
        }
        allRejected(inputs, UNKNOWN_FIELD);
    }

    /** 對照：缺 key、錯型別與改名在 H1 之前就會被拒絕，收緊後必須維持拒絕。 */
    @Test void innerMissingRenamedAndWrongTypedKeysStayRejected() {
        List<Function<NbtCompound, NbtCompound>> targets = List.of(
                SessionRecoveryStrictnessTest::origin, n -> participant(n, 1), n -> lease(n, 1));
        for (var target : targets) {
            for (String key : List.copyOf(target.apply(schemaThree(false, false)).getKeys())) {
                var missing = schemaThree(false, false); target.apply(missing).remove(key);
                SessionRecoverySchema3Test.unhealthy(missing);
                var renamed = schemaThree(false, false); var renamedCompound = target.apply(renamed);
                renamedCompound.put(key.toLowerCase(Locale.ROOT), renamedCompound.get(key).copy()); renamedCompound.remove(key);
                SessionRecoverySchema3Test.unhealthy(renamed);
                var wrongType = schemaThree(false, false); var wrongCompound = target.apply(wrongType);
                wrongCompound.put(key, wrongType(wrongCompound.get(key)));
                rejected(wrongType, key);
            }
        }
    }

    @Test void sourceFamilyWorldKeyMustBeNamespacedAndCanonical() {
        var inputs = new ArrayList<NbtCompound>();
        for (String raw : List.of("overworld", ":overworld")) {
            var input = schemaThree(false, false); sourceFamily(input).putString("WorldKey", raw); inputs.add(input);
        }
        allRejected(inputs, NOT_CANONICAL);
    }

    @Test void originWorldKeyMustBeNamespacedAndCanonical() {
        var inputs = new ArrayList<NbtCompound>();
        for (String raw : List.of("overworld", ":overworld")) {
            var input = schemaThree(false, false); origin(input).putString("WorldKey", raw); inputs.add(input);
        }
        allRejected(inputs, NOT_CANONICAL);
    }

    /** 對照：無效識別字在 H1 之前就由 Identifier 解析拒絕，收緊後必須維持拒絕。 */
    @Test void invalidWorldKeysStayRejected() {
        for (String raw : List.of("Minecraft:overworld", "minecraft:Overworld", "minecraft:overworld ")) {
            var source = schemaThree(false, false); sourceFamily(source).putString("WorldKey", raw);
            SessionRecoverySchema3Test.unhealthy(source);
            var origin = schemaThree(false, false); origin(origin).putString("WorldKey", raw);
            SessionRecoverySchema3Test.unhealthy(origin);
        }
    }

    /** 不得退步：既有合法 schema 3 fixture（含多位參與者與多筆 lease）照舊 exact 讀回。 */
    @Test void validSchemaThreeFixturesStillRoundtripExactly() {
        for (boolean selected : new boolean[] {false, true}) for (boolean catalog : new boolean[] {false, true}) {
            for (var input : List.of(SessionRecoverySchema3Test.fixture(selected, catalog), schemaThree(selected, catalog))) {
                var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
                var saved = state.writeNbt(new NbtCompound());
                assertEquals(input, saved);
                assertEquals(state.records(), SessionRecoveryState.fromNbt(saved).records());
                assertEquals(Identifier.of("minecraft", "overworld"), state.records().get(SESSION).origin().worldKey());
            }
        }
    }

    /** 不得退步：schema 1／2 的內層仍容許未知 key 與省略 namespace 的 WorldKey。 */
    @Test void schemaOneAndTwoKeepLenientInnerParsing() {
        for (int schema : new int[] {1, 2}) for (String raw : List.of("overworld", ":overworld", "minecraft:overworld")) {
            var input = SessionRecoveryStateTest.fixture("RETURNING", false); input.putInt("SchemaVersion", schema);
            record(input).putString("SessionSemantics", "LEGACY_FORWARD_CONSUMED");
            origin(input).putString("WorldKey", raw); origin(input).putString("UnknownOriginField", "legacy");
            participant(input, 0).putString("UnknownParticipantField", "legacy");
            lease(input, 0).putString("UnknownLeaseField", "legacy");
            var state = SessionRecoveryState.fromNbt(input); state.requireHealthy();
            var decoded = state.records().values().iterator().next();
            assertEquals(Identifier.of("minecraft", "overworld"), decoded.origin().worldKey());
            assertTrue(decoded.candidateContext().isEmpty());
            var saved = state.writeNbt(new NbtCompound());
            assertEquals(2, saved.getInt("SchemaVersion"));
            assertEquals("minecraft:overworld", origin(saved).getString("WorldKey"));
        }
    }

    /** schema 3 fixture 再加第二位參與者與第二筆 lease，確保逐筆檢查而不只看第一筆。 */
    private static NbtCompound schemaThree(boolean selected, boolean catalog) {
        var input = SessionRecoverySchema3Test.fixture(selected, catalog);
        var second = participant(input, 0).copy(); second.putUuid("PlayerUuid", UUID.fromString("00000000-0000-0000-0000-000000000004"));
        record(input).getList("Participants", NbtElement.COMPOUND_TYPE).add(second);
        var lease = lease(input, 0).copy(); lease.putInt("SlotId", 1); lease.putIntArray("Bounds", new int[] {96,0,0,191,20,10});
        record(input).getList("SpaceLeases", NbtElement.COMPOUND_TYPE).add(lease);
        return input;
    }

    /** M4 policy 的 MaxCandidateEntries 固定為 16384；以 canonical 順序、唯一 id 的 SOURCE entry 填滿指定數量。 */
    private static NbtCompound withLedgerSize(int size) {
        var input = SessionRecoverySchema3Test.fixture(false, false);
        var ledger = new NbtList();
        for (int index = 0; index < size; index++) {
            var door = new NbtCompound(); door.putUuid("SessionUuid", SESSION);
            door.putLong("LogicalStationIndex", index); door.putString("WallSide", "NEGATIVE_LATERAL");
            var candidate = new NbtCompound(); candidate.putString("Kind", "SOURCE");
            candidate.putByteArray("CandidateId", ByteBuffer.allocate(32).putInt(index).array());
            var entry = new NbtCompound(); entry.put("DoorKey", door); entry.put("Candidate", candidate); ledger.add(entry);
        }
        record(input).put("CandidateLedger", ledger);
        return input;
    }

    private static NbtElement wrongType(NbtElement original) {
        return original instanceof NbtString ? NbtInt.of(1) : NbtString.of("wrong-type");
    }

    /** 逐一判定每個 fixture，RED 時列出每一個仍被接受的 fixture，而不只第一個。 */
    private static void allRejected(List<NbtCompound> inputs, String reason) {
        assertAll(inputs.stream().map(input -> (Executable) () -> rejected(input, reason)));
    }

    private static void rejected(NbtCompound input, String reason) {
        var error = assertThrows(IllegalStateException.class, SessionRecoveryState.fromNbt(input)::requireHealthy);
        assertTrue(error.getMessage().contains(reason), () -> "拒絕原因必須包含「" + reason + "」：" + error.getMessage());
        SessionRecoverySchema3Test.unhealthy(input);
    }

    private static NbtCompound record(NbtCompound root) { return SessionRecoverySchema3Test.record(root); }
    private static NbtCompound origin(NbtCompound root) { return record(root).getCompound("Origin"); }
    private static NbtCompound participant(NbtCompound root, int index) {
        return record(root).getList("Participants", NbtElement.COMPOUND_TYPE).getCompound(index);
    }
    private static NbtCompound lease(NbtCompound root, int index) {
        return record(root).getList("SpaceLeases", NbtElement.COMPOUND_TYPE).getCompound(index);
    }
    private static NbtCompound sourceFamily(NbtCompound root) {
        return SessionRecoverySchema3Test.context(root).getCompound("SourceFamilyRef");
    }
}
