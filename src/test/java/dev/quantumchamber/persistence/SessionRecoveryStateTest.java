package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.io.UncheckedIOException;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import dev.quantumchamber.candidate.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.superposition.SessionState;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;

class SessionRecoveryStateTest {
    @TempDir Path directory;
    @BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }

    @Test void emptySchemaThreeRetainsCandidateInitializationOnWrite() {
        var input=new NbtCompound(); input.putInt("SchemaVersion",3); input.put("Records",new NbtList());
        var state=SessionRecoveryState.fromNbt(input); state.requireHealthy();
        assertTrue(state.candidateInitialized()); assertFalse(new SessionRecoveryState().candidateInitialized());
        assertEquals(input,state.writeNbt(new NbtCompound()),"空schema3本身就是已初始化證據");
        assertEquals(2,new SessionRecoveryState().writeNbt(new NbtCompound()).getInt("SchemaVersion"));
    }

    @Test void removingLastCandidatePersistsSchemaThreeAcrossCheckedSaveAndReload() {
        var state=new SessionRecoveryState(); var record=candidateRecord(false); var file=directory.resolve("initialized-empty.dat");
        state.put(record); assertTrue(state.candidateInitialized()); state.save(file.toFile(),null);
        state.remove(record.sessionUuid()); state.save(file.toFile(),null);
        var restored=SessionRecoveryState.load(file); restored.requireHealthy();
        assertTrue(restored.candidateInitialized());
        assertTrue(restored.records().isEmpty());
        assertEquals(3,restored.writeNbt(new NbtCompound()).getInt("SchemaVersion"),"最後一筆移除不撤銷durable初始化證據");
    }

    @Test void initializedCandidateEnvelopeRejectsLegacyEvenWhenEmpty() {
        var original=SessionRecoveryState.fromNbt(fixture("RETURNING",false)).records().values().iterator().next();
        var legacy=new SessionRecoveryRecord(UUID.randomUUID(),original.chamberUuid(),original.origin(),original.participants(),
                original.spaceLeases(),original.state(),original.restoreEntryEffectOnReturn(),original.semantics());
        var input=new NbtCompound(); input.putInt("SchemaVersion",3); input.put("Records",new NbtList());
        var loaded=SessionRecoveryState.fromNbt(input);
        assertThrows(IllegalArgumentException.class,() -> loaded.put(legacy));
        var initialized=new SessionRecoveryState(); var candidate=candidateRecord(false);
        initialized.put(candidate); initialized.remove(candidate.sessionUuid());
        assertThrows(IllegalArgumentException.class,() -> initialized.put(legacy));
        assertTrue(initialized.records().isEmpty());
    }

    @Test void candidateAuthorityOnlyAcceptsCanonicalSupersetAndFrozenContext() {
        var original = candidateRecord(false);
        var ledger = original.candidateLedger();
        var appended = new java.util.ArrayList<>(ledger);
        appended.add(new CandidateLedgerEntry(new DoorKey(original.sessionUuid(), 6, DoorKey.DoorWallSide.NEGATIVE_LATERAL),
                new QuantumCandidate.Source(new CandidateId(new CandidateBytes(SessionRecoverySchema3Test.bytes(60))))));
        var next = candidateCopy(original, original.candidateContext(), appended, original.candidateSelection(), original.state());
        assertTrue(SessionRecoveryRecord.sameAuthority(original, next));
        assertFalse(SessionRecoveryRecord.sameAuthority(next, original));
        var changed = new java.util.ArrayList<>(ledger);
        changed.set(0, new CandidateLedgerEntry(ledger.getFirst().doorKey(),
                new QuantumCandidate.Source(new CandidateId(new CandidateBytes(SessionRecoverySchema3Test.bytes(61))))));
        var inserted = new java.util.ArrayList<>(ledger);
        inserted.add(new CandidateLedgerEntry(new DoorKey(original.sessionUuid(), -3, DoorKey.DoorWallSide.NEGATIVE_LATERAL),
                appended.getLast().candidate()));
        assertTrue(SessionRecoveryRecord.sameAuthority(original,
                candidateCopy(original, original.candidateContext(), inserted, original.candidateSelection(), original.state())));
        for (var invalidLedger : List.of(List.<CandidateLedgerEntry>of(), ledger.subList(1, ledger.size()), changed)) {
            assertFalse(SessionRecoveryRecord.sameAuthority(original,
                    candidateCopy(original, original.candidateContext(), invalidLedger, original.candidateSelection(), original.state())));
        }
        var context = original.candidateContext().orElseThrow();
        var raised = new CandidatePolicySnapshot(context.policyId(), 1, 1, 5, 20, 75, 5, 16384,
                context.entropyFingerprint(), context.sourceFamilyRef());
        var changedContext = candidateCopy(original, Optional.of(raised), ledger, original.candidateSelection(), original.state());
        assertFalse(SessionRecoveryRecord.sameAuthority(original, changedContext));
        assertFalse(SessionRecoveryRecord.sameAuthority(changedContext, original));
        var legacy = candidateCopy(original, Optional.empty(), List.of(), Optional.empty(), original.state());
        assertFalse(SessionRecoveryRecord.sameAuthority(original, legacy));
        assertFalse(SessionRecoveryRecord.sameAuthority(legacy, original));
    }

    @Test void selectedAuthorityCannotResetOrChangeDoorCandidatePlayerTime() {
        var selectable = candidateRecord(false); var selected = candidateRecord(true);
        assertTrue(SessionRecoveryRecord.sameAuthority(selectable, selected));
        assertTrue(SessionRecoveryRecord.sameAuthority(selected, selected));
        assertFalse(SessionRecoveryRecord.sameAuthority(selected, selectable));
        var choice = (CandidateSelection.Selected) selected.candidateSelection().orElseThrow();
        var other = selected.candidateLedger().getFirst();
        for (var changed : List.of(
                new CandidateSelection.Selected(other.doorKey(), other.candidate().candidateId(), choice.selectedBy(), 0, 1),
                new CandidateSelection.Selected(choice.doorKey(), choice.candidateId(), UUID.randomUUID(), 0, 1),
                new CandidateSelection.Selected(choice.doorKey(), choice.candidateId(), choice.selectedBy(), 1, 1))) {
            assertFalse(SessionRecoveryRecord.sameAuthority(selected,
                    candidateCopy(selected, selected.candidateContext(), selected.candidateLedger(), Optional.of(changed), SessionState.MEASURED)));
        }
    }

    @Test void candidatePutProtectsCurrentFlushedAndHistoryAfterRejectedRemoveAndSave() {
        var selectable = candidateRecord(false); var selected = candidateRecord(true);
        for (int mode = 0; mode < 5; mode++) {
            var state = mode == 0 ? new SessionRecoveryState()
                    : SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(mode == 1 || mode == 4, false));
            if (mode != 1 && mode != 4) state.put(selected);
            if (mode >= 2) assertThrows(IllegalStateException.class,() -> state.remove(selected.sessionUuid()));
            if (mode >= 3) state.save(directory.resolve("removed.dat").toFile(), null);
            var before = state.records(); var flushed = state.flushedRecords(); boolean dirty = state.isDirty();
            assertThrows(IllegalArgumentException.class, () -> state.put(selectable), "mode=" + mode);
            assertEquals(before, state.records()); assertEquals(flushed, state.flushedRecords()); assertEquals(dirty, state.isDirty());
            state.put(selected);
            assertEquals(selected, state.records().get(selected.sessionUuid()));
        }
    }

    @Test void unsavedRemoveAndReinsertStillProtectsLegacyAuthority() {
        var original = SessionRecoveryState.fromNbt(fixture("RETURNING", false)).records().values().iterator().next();
        var state = new SessionRecoveryState(); state.put(original); state.remove(original.sessionUuid());
        for (var changed : authorityChanges(original)) {
            assertThrows(IllegalArgumentException.class, () -> state.put(changed));
        }
    }

    @Test void checkedSaveReadbackFailuresPreserveDirtyAndPreviousFlushedSnapshot() throws Exception {
        for (String fault : List.of("io", "malformed", "stale", "changed-progress", "extra-wrapper")) {
            boolean[] armed = {false}; NbtCompound[] previous = {null};
            var state = new SessionRecoveryState(path -> {
                if (!armed[0]) return SessionJournalStore.read(path);
                if (fault.equals("io")) throw new java.io.IOException("注入正式檔讀取失敗");
                var actual = SessionJournalStore.read(path);
                if (fault.equals("malformed")) actual.getCompound("data").putInt("SchemaVersion", 99);
                if (fault.equals("stale")) actual = previous[0];
                if (fault.equals("changed-progress")) participant(actual.getCompound("data")).putBoolean("Returned", true);
                if (fault.equals("extra-wrapper")) actual.putString("Unexpected", "extra");
                NbtIo.writeCompressed(actual, path);
                return SessionJournalStore.read(path);
            });
            var file = directory.resolve("readback-" + fault + ".dat");
            state.put(candidateRecord(false)); state.save(file.toFile(), null);
            previous[0] = SessionJournalStore.read(file);
            var flushed = state.flushedRecords();
            state.put(candidateRecord(true)); armed[0] = true;
            assertThrows(UncheckedIOException.class, () -> state.save(file.toFile(), null), fault);
            assertTrue(state.isDirty(), fault); assertEquals(flushed, state.flushedRecords(), fault);
            assertEquals(SessionState.MEASURED, state.records().values().iterator().next().state());
        }
    }

    @Test void dormantReceiptBlocksOnlyItsChamberAndReleasesReturnedParticipants() {
        var dormant=dormantReceipt(); var player=dormant.participants().getFirst().playerUuid();
        assertEquals(MeasuredRecoveryPhase.DORMANT,MeasuredRecoveryPhase.from(dormant));
        var active=otherSession(player,SessionState.SUPERPOSITION,false,new UUID(0,0x61),new UUID(0,0x62),7);
        var state=new SessionRecoveryState(); state.put(dormant);
        state.put(active);
        assertEquals(active,state.records().get(active.sessionUuid()),"DORMANT receipt 不得佔用已返還參與者");
        var file=directory.resolve("dormant-other-chamber.dat"); state.save(file.toFile(),null);
        var reloaded=SessionRecoveryState.load(file); reloaded.requireHealthy();
        assertEquals(java.util.Map.of(dormant.sessionUuid(),dormant,active.sessionUuid(),active),reloaded.flushedRecords(),
                "strict reload 必須接受 DORMANT receipt 與另一座 Chamber 的同一玩家");
        var sameChamber=otherSession(new UUID(0,0x63),SessionState.ARMING,false,new UUID(0,0x64),dormant.chamberUuid(),8);
        assertThrows(IllegalArgumentException.class,() -> state.put(sameChamber),"DORMANT receipt 仍封鎖自己的 Chamber");
        assertEquals(dormant,state.records().get(dormant.sessionUuid()));
    }

    @Test void nonDormantParticipantsRemainExclusiveAcrossSessions() {
        var measured=candidateRecord(true); var player=measured.participants().getFirst().playerUuid();
        var geometryPending=SessionRecoveryManager.returnedRecord(measured,player);
        assertEquals(MeasuredRecoveryPhase.RELEASE_GEOMETRY,MeasuredRecoveryPhase.from(geometryPending));
        var returning=otherSession(player,SessionState.RETURNING,true,new UUID(0,0x71),new UUID(0,0x72),9);
        var active=otherSession(player,SessionState.SUPERPOSITION,false,new UUID(0,0x73),new UUID(0,0x74),10);
        for(var holder : List.of(measured,geometryPending,returning,active)) {
            var state=new SessionRecoveryState(); state.put(holder);
            var competing=otherSession(player,SessionState.ARMING,false,new UUID(0,0x75),new UUID(0,0x76),11);
            assertThrows(IllegalArgumentException.class,() -> state.put(competing),"非 DORMANT 參與者仍為跨 session 唯一："+holder.state());
            assertEquals(java.util.Map.of(holder.sessionUuid(),holder),state.records());
        }
    }

    @Test void abortUncheckedCasRestoresCurrentAndHistoryToFlushedAuthority() {
        var state=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false,false));
        var previous=candidateRecord(false); var selected=candidateRecord(true);
        state.put(selected);
        assertFalse(state.recoveryWritesSafe(),"put→flush 同步窗口內仍是未 checked selection");
        assertTrue(state.abortUnchecked(selected));
        assertEquals(previous,state.records().get(previous.sessionUuid()),"current 還原為上一份 flushed authority");
        assertEquals(previous,state.flushedRecords().get(previous.sessionUuid()));
        assertTrue(state.recoveryWritesSafe(),"還原後 recovery 不得停擺");
        assertTrue(state.isDirty(),"還原後保留 dirty，下一次寫出會覆寫任何未確認的磁碟內容");
        var returning=previous.withProgress(previous.participants(),previous.spaceLeases(),SessionState.RETURNING,false);
        state.put(returning);
        var file=directory.resolve("abort-returning.dat"); state.save(file.toFile(),null);
        assertEquals(returning,SessionRecoveryState.load(file).flushedRecords().get(previous.sessionUuid()),"history 已還原，flushed SELECTABLE 可寫 RETURNING");
    }

    @Test void abortUncheckedIsNoOpWhenNextAlreadyCheckedOrNeverWritten() {
        var state=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false,false));
        var previous=candidateRecord(false); var selected=candidateRecord(true);
        assertTrue(state.abortUnchecked(selected),"put 未發生：current 仍 checked，無需還原");
        assertEquals(previous,state.records().get(previous.sessionUuid())); assertFalse(state.isDirty());
        state.put(selected); state.save(directory.resolve("already-checked.dat").toFile(),null);
        assertTrue(state.abortUnchecked(selected),"next 已 checked 落盤（例如 flush 後才失敗）時不得倒退 durable receipt");
        assertEquals(selected,state.records().get(selected.sessionUuid())); assertEquals(selected,state.flushedRecords().get(selected.sessionUuid()));
    }

    @Test void abortUncheckedWithoutFlushedRecordRemovesCurrentAndRestoresCheckedHistory() {
        var fresh=new SessionRecoveryState(); var initial=candidateRecord(false);
        fresh.put(initial); assertFalse(fresh.recoveryWritesSafe());
        assertTrue(fresh.abortUnchecked(initial));
        assertTrue(fresh.records().isEmpty(),"沒有上一份 flushed record 時移除未 checked current");
        assertTrue(fresh.recoveryWritesSafe());
        var file=directory.resolve("abort-new-session.dat"); fresh.save(file.toFile(),null);
        assertTrue(SessionRecoveryState.load(file).flushedRecords().isEmpty(),"之後任何寫出都不得承認未 checked 的新 session");
        // 已 checked 後移除的同 UUID：還原只回到最後 checked authority，不能讓 remove→reinsert 繞過。
        var state=SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture("RETURNING",false));
        var original=state.records().values().iterator().next();
        state.remove(original.sessionUuid()); state.save(directory.resolve("removed.dat").toFile(),null);
        state.put(original); assertTrue(state.abortUnchecked(original));
        assertTrue(state.records().isEmpty());
        for(var changed : authorityChanges(original)) assertThrows(IllegalArgumentException.class,() -> state.put(changed));
    }

    @Test void casMismatchQuarantinesOnlyThatSessionAndSaveWritesItsFlushedAuthority() {
        var state=SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(false,false));
        var previous=candidateRecord(false); var selected=candidateRecord(true);
        state.put(selected);
        var progressed=SessionRecoveryManager.returnedRecord(selected,selected.participants().getFirst().playerUuid());
        state.put(progressed);
        assertFalse(state.abortUnchecked(selected),"CAS 不符且仍含未 checked selection 時必須隔離而非猜測");
        assertEquals(progressed,state.records().get(previous.sessionUuid()),"隔離不得猜測性覆寫 current");
        assertTrue(state.recoveryWritesSafe(),"隔離只影響該 session，其他 recovery 不停擺");
        assertThrows(IllegalStateException.class,() -> state.put(previous.withProgress(previous.participants(),previous.spaceLeases(),SessionState.RETURNING,false)));
        assertThrows(IllegalStateException.class,() -> state.remove(previous.sessionUuid()));
        var other=otherSession(new UUID(0,0x81),SessionState.SUPERPOSITION,false,new UUID(0,0x82),new UUID(0,0x83),12);
        state.put(other);
        var file=directory.resolve("quarantine.dat"); state.save(file.toFile(),null);
        var disk=SessionRecoveryState.load(file); disk.requireHealthy();
        assertEquals(java.util.Map.of(previous.sessionUuid(),previous,other.sessionUuid(),other),disk.flushedRecords(),
                "隔離 session 只寫出上一份 flushed authority；其他 session 的合法進度照常落盤");
        assertEquals(disk.flushedRecords(),state.flushedRecords());
    }

    @Test void readbackFailureAfterWriteRevertsAndReturningOverwritesUnacknowledgedDisk() throws Exception {
        boolean[] armed={false};
        var state=new SessionRecoveryState(path -> {
            if(armed[0]) { armed[0]=false; throw new java.io.IOException("注入正式檔寫入後的 readback 失敗"); }
            return SessionJournalStore.read(path);
        });
        var previous=candidateRecord(false); var selected=candidateRecord(true);
        var file=directory.resolve("readback-window.dat");
        state.put(previous); state.save(file.toFile(),null);
        state.put(selected); armed[0]=true;
        assertThrows(UncheckedIOException.class,() -> state.save(file.toFile(),null));
        assertEquals(SessionState.MEASURED,SessionRecoveryState.load(file).flushedRecords().get(previous.sessionUuid()).state(),"fixture：next 已寫入但未確認");
        assertEquals(previous,state.flushedRecords().get(previous.sessionUuid()),"readback 失敗不得前進 flushed");
        assertTrue(state.abortUnchecked(selected));
        var returning=previous.withProgress(previous.participants(),previous.spaceLeases(),SessionState.RETURNING,false);
        state.put(returning); state.save(file.toFile(),null);
        assertEquals(returning,SessionRecoveryState.load(file).flushedRecords().get(previous.sessionUuid()),"同 process 以 checked RETURNING 覆寫未確認的 MEASURED");
    }

    @Test void participantsAvailableSkipsOnlyDormantReceipts() {
        var dormant=dormantReceipt(); var player=dormant.participants().getFirst().playerUuid();
        var state=new SessionRecoveryState(); state.put(dormant);
        assertTrue(state.participantsAvailable(List.of(player)),"DORMANT receipt 不佔用已返還參與者");
        var active=otherSession(player,SessionState.SUPERPOSITION,false,new UUID(0,0x91),new UUID(0,0x92),13);
        state.put(active);
        assertFalse(state.participantsAvailable(List.of(new UUID(0,0x99),player)),"非 DORMANT session 的參與者不可再入場");
        var loaded=SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture("RETURNING",false));
        var holder=loaded.records().values().iterator().next(); loaded.remove(holder.sessionUuid());
        assertFalse(loaded.participantsAvailable(List.of(holder.participants().getFirst().playerUuid())),"尚未確認移除的 flushed record 仍佔用玩家");
        assertTrue(loaded.participantsAvailable(List.of(new UUID(0,0x99))));
    }

    static SessionRecoveryRecord dormantReceipt() {
        var measured=candidateRecord(true);
        var returned=SessionRecoveryManager.returnedRecord(measured,measured.participants().getFirst().playerUuid());
        return returned.withProgress(returned.participants(),List.of(),SessionState.MEASURED,false);
    }

    static SessionRecoveryRecord otherSession(UUID player,SessionState phase,boolean returned,UUID sid,UUID chamber,int slot) {
        var template=candidateRecord(false); var source=template.participants().getFirst();
        var origin=new dev.quantumchamber.chamber.ChamberOriginAuthority(chamber,template.origin().worldKey(),template.origin().role(),
                template.origin().controllerPos().add(32*slot,0,0),template.origin().facing(),dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN);
        var person=new SessionRecoveryRecord.Participant(player,source.sourcePosition(),source.sourceVelocity(),source.yaw(),source.pitch(),
                source.quantumStateSnapshot(),returned);
        return new SessionRecoveryRecord(sid,chamber,origin,List.of(person),
                List.of(new SessionRecoveryRecord.SpaceLease(slot,new net.minecraft.util.math.BlockBox(0,0,96*slot,95,20,96*slot+10))),
                phase,false,template.semantics(),template.candidateContext(),List.of(),Optional.of(new CandidateSelection.Selectable()));
    }

    private static SessionRecoveryRecord candidateRecord(boolean selected) {
        return SessionRecoveryState.fromNbt(SessionRecoverySchema3Test.fixture(selected, false)).records().values().iterator().next();
    }

    private static SessionRecoveryRecord candidateCopy(SessionRecoveryRecord original, Optional<CandidatePolicySnapshot> context,
            List<CandidateLedgerEntry> ledger, Optional<CandidateSelection> selection, SessionState phase) {
        return new SessionRecoveryRecord(original.sessionUuid(), original.chamberUuid(), original.origin(), original.participants(),
                original.spaceLeases(), phase, false, original.semantics(), context, ledger, selection);
    }

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

    @Test void schemaOneAndTwoKeepEmptyCompoundListsAndUnknownFieldCompatibility() throws Exception {
        for (int schema : new int[] {1, 2}) {
            var input = fixture("RETURNING", false); input.putInt("SchemaVersion", schema);
            record(input).putString("SessionSemantics", "LEGACY_FORWARD_CONSUMED");
            input.putString("UnknownEnvelopeField", "legacy"); record(input).putString("UnknownRecordField", "legacy");
            var loaded = SessionRecoveryState.fromNbt(input); loaded.requireHealthy();
            assertTrue(loaded.records().values().iterator().next().candidateContext().isEmpty());
            input.put("Records", SessionRecoverySchema3Test.emptyList(NbtElement.COMPOUND_TYPE));
            var empty = SessionRecoveryState.fromNbt(input); empty.requireHealthy(); assertTrue(empty.records().isEmpty());
        }
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
