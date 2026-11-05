package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;

class CandidateJournalCodecTest {
    @Test void m6ContextAndIntentRoundTripWithoutChangingLegacyShape() {
        var legacy = SessionRecoverySchema3Test.fixture(false, false);
        var oldContext = SessionRecoverySchema3Test.context(legacy);
        assertEquals(oldContext, CandidateJournalCodec.context(CandidateJournalCodec.context(oldContext)));
        var oldEntry = SessionRecoverySchema3Test.ledger(legacy).getCompound(1);
        assertEquals(oldEntry, CandidateJournalCodec.entry(CandidateJournalCodec.entry(oldEntry)));
        var context = m6Context();
        assertEquals(context, CandidateJournalCodec.context(assertDoesNotThrow(() -> CandidateJournalCodec.context(context))));
        var row = m6Entry(UUID.randomUUID());
        assertEquals(row, CandidateJournalCodec.entry(assertDoesNotThrow(() -> CandidateJournalCodec.entry(row))));
    }
    @Test void newShapeDoesNotFallBackAfterMalformedPolicyMarker() {
        var row = m6Entry(UUID.randomUUID()); var intent = row.getCompound("Candidate").getCompound("Intent");
        intent.putString("PolicyVersion", "1");
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.entry(row));
        var context = m6Context(); context.remove("FamilyCapabilityVersion");
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.context(context));
    }
    @Test void unknownOrExtraNewFieldsAreRejected() {
        var row = m6Entry(UUID.randomUUID()); row.getCompound("Candidate").getCompound("Intent").putInt("Extra", 1);
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.entry(row));
        var context = m6Context(); context.putInt("PolicyVersion", 2);
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.context(context));
        var futureFamily = m6Context(); futureFamily.putInt("FamilyCapabilityVersion", 1);
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.context(futureFamily));
        var wrongType = m6Context(); wrongType.putByte("FamilyCapabilityVersion", (byte) 0);
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.context(wrongType));
        var oldWithNewField = SessionRecoverySchema3Test.context(SessionRecoverySchema3Test.fixture(false, false)).copy();
        oldWithNewField.putInt("FamilyCapabilityVersion", 0);
        assertThrows(IllegalArgumentException.class, () -> CandidateJournalCodec.context(oldWithNewField));
    }
    @Test void schemaFiveRoundTripsMixedLegacyIntent() {
        var old = SessionRecoverySchema3Test.record(SessionRecoverySchema3Test.fixture(false, false)).copy();
        old.put("Collapse", new NbtCompound());
        var fresh = old.copy(); fresh.put("CandidateContext", m6Context());
        var entries = new NbtList(); entries.add(m6Entry(fresh.getUuid("SessionUuid"))); fresh.put("CandidateLedger", entries);
        for (var row : new NbtCompound[] {old, fresh}) {
            var decoded = assertDoesNotThrow(() -> SessionRecoveryRecord.fromNbt(row, 5));
            assertEquals(row, decoded.toNbt(5));
        }
        assertThrows(IllegalArgumentException.class, () -> SessionRecoveryRecord.fromNbt(fresh, 4));
        var newContextWithOldIntent = old.copy(); newContextWithOldIntent.put("CandidateContext", m6Context());
        assertThrows(IllegalArgumentException.class, () -> SessionRecoveryRecord.fromNbt(newContextWithOldIntent, 5));
        fresh.put("CandidateContext", old.getCompound("CandidateContext"));
        assertThrows(IllegalArgumentException.class, () -> SessionRecoveryRecord.fromNbt(fresh, 5));
    }
    @Test void formalRecordRejectsUnknownSchemaSix() {
        var row = SessionRecoverySchema3Test.record(SessionRecoverySchema3Test.fixture(false, false));
        assertThrows(IllegalArgumentException.class, () -> SessionRecoveryRecord.fromNbt(row, 6));
    }
    static NbtCompound m6Context() {
        var result = SessionRecoverySchema3Test.context(SessionRecoverySchema3Test.fixture(false, false)).copy();
        result.putString("PolicyId", "quantumchamber:m6_profiles_v1"); result.putInt("CandidateSchemaVersion", 2);
        result.putInt("MaxCandidateEntries", 4096); result.putInt("FamilyCapabilityVersion", 0); return result;
    }
    static NbtCompound m6Entry(UUID session) {
        var entry = new NbtCompound(); var door = new NbtCompound(); door.putUuid("SessionUuid", session);
        door.putLong("LogicalStationIndex", 0); door.putString("WallSide", "NEGATIVE_LATERAL"); entry.put("DoorKey", door);
        var candidate = new NbtCompound(); candidate.putString("Kind", "NEW"); candidate.putByteArray("CandidateId", new byte[32]);
        var intent = new NbtCompound(); intent.putByteArray("AllocationToken", new byte[32]); intent.putByteArray("GenerationSeedMaterial", new byte[32]);
        intent.putInt("PolicyVersion", 1); intent.putString("ProfileId", "quantumchamber:random_vanilla_v1"); intent.putInt("ProfileVersion", 1);
        intent.putLong("SourceRootWorldSeed", 42); intent.putInt("FamilyCapabilityVersion", 0);
        intent.put("SourceSettingsSnapshot", snapshot("amplified")); intent.put("TargetSettingsSnapshot", snapshot("normal"));
        candidate.put("Intent", intent); entry.put("Candidate", candidate); return entry;
    }
    static NbtCompound snapshot(String preset) {
        var nbt = new NbtCompound(); nbt.putInt("SnapshotVersion", 1); nbt.putString("ProviderId", "quantumchamber:vanilla_" + preset + "_v1");
        nbt.putInt("ProviderVersion", 1); var params = new NbtCompound(); params.putString("OverworldPreset", preset); params.putByte("GenerateStructures", (byte) 0);
        nbt.put("Parameters", params); nbt.putByteArray("OutputFingerprint", new byte[32]); return nbt;
    }
}
