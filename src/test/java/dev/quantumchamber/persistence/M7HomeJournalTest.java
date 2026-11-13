package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** 直接經共用 journal 入口核對升版與可達後繼保留。 */
class M7HomeJournalTest {
    @TempDir Path directory;
    @BeforeAll static void version() { net.minecraft.SharedConstants.createGameVersion(); }

    @Test void mixedRowsAndEmptyJournalKeepSix() throws Exception {
        var home = M7BudgetSpikeTest.home(1);
        var old = M7BudgetSpikeTest.legacyFive();
        var two = M7BudgetSpikeTest.home(2).getList("SpaceLeases", 10);
        old.getList("SpaceLeases", 10).set(0, two.getCompound(1));
        var wrapped = M7BudgetSpikeTest.wrap(List.of(old, home));
        Path file = directory.resolve("journal.dat");
        SessionJournalStore.write(file, wrapped);
        assertEquals(wrapped, SessionJournalStore.read(file));
        assertEquals(wrapped.getCompound("data"), SessionRecoveryState.load(file).writeNbt(new NbtCompound()));
        var empty = M7BudgetSpikeTest.wrap(List.of());
        var state = SessionRecoveryState.fromNbt(empty.getCompound("data"));
        assertEquals(empty.getCompound("data"), state.writeNbt(new NbtCompound()));
    }

    static NbtCompound measured(int slots) {
        var row = M7BudgetSpikeTest.home(slots);
        row.putString("State", "MEASURED");
        var selection = M7BudgetSpikeTest.kind("HOME");
        var door = new NbtCompound();
        door.putUuid("SessionUuid", row.getUuid("SessionUuid"));
        door.putLong("LogicalStationIndex", 1);
        door.putString("WallSide", "POSITIVE_LATERAL");
        selection.put("DoorKey", door);
        selection.putUuid("SelectedBy", row.getList("Participants", 10).getCompound(0).getUuid("PlayerUuid"));
        selection.putLong("SelectedAtGameTime", 20);
        row.getCompound("Home").put("Selection", selection);
        return row;
    }

    @Test void fixed_lease_reserve_covers_root_latch_and_collapsing() throws Exception {
        for (int slots : List.of(32, 64, 1, 8)) {
            var before = measured(slots);
            var latched = before.copy();
            latched.getCompound("Home").putString("StopReason", "ROOT_INVALID_STRUCTURE");
            latched.getCompound("Home").putLong("StoppedAtGameTime", 23);
            var collapsing = before.copy();
            collapsing.putString("State", "COLLAPSING");
            collapsing.getCompound("Home").putLong("CommittedAtGameTime", 23);
            var wrapped = M7BudgetSpikeTest.wrap(List.of(before));
            SessionJournalStore.write(directory.resolve("fixed.dat"), wrapped);
            long used = M7BudgetSpikeTest.measure(wrapped);
            long latchCost = M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(latched)));
            long collapseCost = M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(collapsing)));
            assertTrue(latchCost > used && collapseCost > used);
            var both=collapsing.copy();
            both.getCompound("Home").putString("StopReason","ROOT_INVALID_STRUCTURE");
            both.getCompound("Home").putLong("StoppedAtGameTime",23);
            assertTrue(HomeSessionRecord.edge(HomeJournalCodec.decode(before),HomeJournalCodec.decode(collapsing),true));
            assertTrue(HomeSessionRecord.edge(HomeJournalCodec.decode(collapsing),HomeJournalCodec.decode(both),false));
            long combined=M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(both)));
            long available=HomeJournalBudget.remaining(HomeJournalCodec.decode(before));
            assertTrue(available >= combined-used);
            System.out.printf("I2 兩步持租約峰值 slot=%d combined=%d%n",slots,combined);
            System.out.printf("I2 slot=%d current=%d latch=%d collapsing=%d reserve=%d%n",slots,used,latchCost,collapseCost,available);
            assertTrue(available >= Math.max(latchCost,collapseCost)-used);
            var padded = atLimit(before);
            assertThrows(Exception.class, () -> SessionJournalStore.write(directory.resolve("fixed.dat"), padded));
            assertEquals(wrapped, SessionJournalStore.read(directory.resolve("fixed.dat")));
        }
    }

    static NbtCompound atLimit(NbtCompound row) throws Exception {
        var padded = M7BudgetSpikeTest.wrap(List.of(row.copy()));
        var effect = padded.getCompound("data").getList("Records", 10).getCompound(0)
                .getList("Participants", 10).getCompound(0).getCompound("QuantumState");
        effect.putByteArray("Padding", new byte[0]);
        int room = Math.toIntExact(M7BudgetSpikeTest.LIMIT - M7BudgetSpikeTest.measure(padded));
        effect.putByteArray("Padding", new byte[room]);
        assertEquals(M7BudgetSpikeTest.LIMIT, M7BudgetSpikeTest.measure(padded));
        return padded;
    }

    static NbtCompound other() {
        var old=M7BudgetSpikeTest.legacyFive();
        old.getList("SpaceLeases",10).set(0,M7BudgetSpikeTest.home(2).getList("SpaceLeases",10).getCompound(1));
        return old;
    }

    @Test void legacyAppendCannotSpendUnselectedRemapAndCloseReserve() throws Exception {
        for (boolean selected : List.of(false,true)) {
            var home=selected ? measured(1) : M7BudgetSpikeTest.home(1);
            int slots=selected ? 1 : 64;
            var held=measured(slots);
            held.putString("State","COLLAPSING");
            var fields=held.getCompound("Home"); fields.putLong("CommittedAtGameTime",21);
            fields.putString("StopReason","ROOT_INVALID_STRUCTURE"); fields.putLong("StoppedAtGameTime",22);
            var peak=held;
            if(selected) {
                peak=HomeJournalCodec.encode(M7HomeHistoryTest.closed(1500));
                peak.putString("State","SEALED");
                var h=peak.getCompound("Home");
                h.putLong("CommittedAtGameTime",21); h.putLong("PassageOpenedAtGameTime",30); h.putLong("PassageExpiresAtGameTime",1230);
                h.putString("SealedReason","AUTHORITY_UNREADABLE");
                var crossed=new NbtList();
                for(var raw:peak.getList("Participants",10)) crossed.add(((NbtCompound)raw).get("PlayerUuid").copy());
                h.put("Crossed",crossed);
            }
            HomeJournalCodec.decode(peak);
            // 以正式承認的完整最壞用量找出最後可寫點；append 自身仍可落在 16 MiB 內。
            var old=other();
            var template=old.getList("CandidateLedger",10).getCompound(1).copy();
            var mixed=M7BudgetSpikeTest.wrap(List.of(home,old));
            var snapshot=old.getList("Participants",10).getCompound(0).getCompound("QuantumState");
            snapshot.putByteArray("Padding",new byte[0]);
            var actual=HomeJournalCodec.decode(home);
            int room=Math.toIntExact(M7BudgetSpikeTest.LIMIT-M7BudgetSpikeTest.measure(mixed)
                    - HomeJournalBudget.remaining(actual));
            snapshot.putByteArray("Padding",new byte[room]);
            var target=directory.resolve("shared-"+selected+".dat");
            SessionJournalStore.write(target,mixed);
            var authority=SessionRecoveryState.load(target);
            authority.requireHealthy();
            var next=old.copy(); M7BudgetSpikeTest.appendCandidate(next,template,20);
            assertTrue(M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(home,next)))<=M7BudgetSpikeTest.LIMIT);
            var before=authority.allSessions();
            assertThrows(RuntimeException.class, () -> authority.put(SessionRecoveryRecord.fromNbt(next,5)));
            assertEquals(before,authority.allSessions());
            assertEquals(mixed,SessionJournalStore.read(target));
            long minimum=M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(peak)))
                    -M7BudgetSpikeTest.measure(M7BudgetSpikeTest.wrap(List.of(home)));
            assertTrue(HomeJournalBudget.remaining(actual)>=minimum);
        }
    }

    @Test void sameHandleAndBoundedTrackerRejectOversize() throws Exception {
        var good=M7BudgetSpikeTest.wrap(List.of(M7BudgetSpikeTest.home(64)));
        var file=directory.resolve("snapshot.dat");
        SessionJournalStore.write(file,good);
        var pinned=SessionJournalStore.readSnapshot(file);
        var invalid=atLimit(M7BudgetSpikeTest.home(1));
        invalid.getCompound("data").getList("Records",10).getCompound(0).getList("Participants",10)
                .getCompound(0).getCompound("QuantumState").putInt("extra",1);
        NbtIo.writeCompressed(invalid,file);
        assertEquals(good,pinned.decode());
        assertThrows(java.io.IOException.class,() -> SessionJournalStore.read(file));
    }

    @Test void homeEdgesAndOwnershipShareDurableAuthority() throws Exception {
        var file=directory.resolve("data/session.dat");
        var state=SessionRecoveryState.load(file);
        var store=new HomeReceiptStore(directory);
        var n=M7BudgetSpikeTest.home(1); n.putString("State","ARMING");
        var arm=HomeJournalCodec.decode(n);
        Runnable flush=() -> state.save(file.toFile(),null);
        assertFalse(state.admitHome(store,flush,arm).committed());
        state.markCollapseAuthoritiesInitialized();
        assertTrue(state.admitHome(store,flush,arm).committed());
        n.putString("State","SUPERPOSITION"); var sup=HomeJournalCodec.decode(n);
        assertTrue(state.commitHome(flush,arm,sup,true).committed());
        var m=HomeJournalCodec.decode(measured(1));
        assertTrue(state.commitHome(flush,sup,m,true).committed());
        assertFalse(state.participantsAvailable(List.of(m.participants().getFirst().playerUuid())));
        var changed=measured(1);
        changed.putString("State","COLLAPSING"); changed.getCompound("Home").putLong("CommittedAtGameTime",25);
        var coll=HomeJournalCodec.decode(changed);
        assertFalse(state.commitHome(flush,m,coll,false).committed());
        changed.getList("Participants",10).getCompound(0).putBoolean("Returned",true);
        assertFalse(state.commitHome(flush,m,HomeJournalCodec.decode(changed),true).committed());
        assertTrue(state.commitHome(flush,m,coll,true).committed());
        assertEquals(coll,state.allFlushedSessions().get(coll.sessionUuid()));
        assertEquals(state.allSessions(),SessionRecoveryState.load(file).allSessions());
        assertThrows(RuntimeException.class,() -> state.remove(coll.sessionUuid()));
    }
    @Test void terminalRemovalRequiresHistoryAndKeepsEmptySix() throws Exception {
        var frozen=M7HomeHistoryTest.closed(31);
        var file=directory.resolve("data/session.dat"); var store=new HomeReceiptStore(directory);
        SessionJournalStore.write(file,M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(frozen))));
        var state=SessionRecoveryState.load(file);
        Runnable flush=() -> state.save(file.toFile(),null);
        assertFalse(state.terminateHome(store,flush,frozen).removed());
        store.publishOrVerify(frozen);
        assertTrue(state.terminateHome(store,flush,frozen).removed());
        assertTrue(state.allSessions().isEmpty());
        assertEquals(6,SessionJournalStore.read(file).getCompound("data").getInt("SchemaVersion"));
        assertEquals(frozen,store.lookup(frozen.sessionUuid()).orElseThrow());
    }

    @Test void quarantineRollsUnselectedIntoReturningAndRejectsCrossRowLeaseCollision() throws Exception {
        var file=directory.resolve("data/session.dat");
        var home=M7BudgetSpikeTest.home(1); var row=HomeJournalCodec.decode(home);
        SessionJournalStore.write(file,M7BudgetSpikeTest.wrap(List.of(home)));
        var state=SessionRecoveryState.load(file);
        assertTrue(state.quarantine(row.sessionUuid()));
        var returning=state.convergeQuarantined(row.sessionUuid()).orElseThrow();
        assertEquals(dev.quantumchamber.superposition.SessionState.RETURNING,returning.state());
        state.save(file.toFile(),null);
        assertEquals(returning,state.allFlushedSessions().get(row.sessionUuid()));
        var other=other(); var lease=other.getList("SpaceLeases",10).getCompound(0);
        lease.putIntArray("Bounds",home.getList("SpaceLeases",10).getCompound(0).getIntArray("Bounds"));
        assertThrows(RuntimeException.class,() -> state.put(SessionRecoveryRecord.fromNbt(other,5)));
        assertEquals(Map.of(row.sessionUuid(),returning),state.allFlushedSessions());
    }
    @Test void unknownFirstHomeRetainsParticipantChamberAndSlotUntilReconciliation() throws Exception {
        var file=directory.resolve("data/session.dat");
        var state=new SessionRecoveryState(path -> { throw new java.io.IOException("注入：讀回未確認"); });
        state.markCollapseAuthoritiesInitialized();
        var raw=M7BudgetSpikeTest.home(1); raw.putString("State","ARMING");
        var home=HomeJournalCodec.decode(raw);
        var outcome=state.admitHome(new HomeReceiptStore(directory),() -> state.save(file.toFile(),null),home);
        assertEquals(SessionRecoveryState.CommitResult.Status.RESTORED,outcome.status());
        assertTrue(state.allSessions().isEmpty() && state.allFlushedSessions().isEmpty());
        assertFalse(state.participantsAvailable(List.of(home.participants().getFirst().playerUuid())));
        var fileBytes=Files.readAllBytes(file);
        var other=other();
        other.getList("SpaceLeases",10).set(0,raw.getList("SpaceLeases",10).getCompound(0).copy());
        assertThrows(RuntimeException.class,() -> state.put(SessionRecoveryRecord.fromNbt(other,5)));
        var otherChamber=other();
        otherChamber.putUuid("ChamberUuid",home.chamberUuid());
        otherChamber.getCompound("Origin").putUuid("ChamberUuid",home.chamberUuid());
        assertThrows(RuntimeException.class,() -> state.put(SessionRecoveryRecord.fromNbt(otherChamber,5)));
        assertArrayEquals(fileBytes,Files.readAllBytes(file));
        assertEquals(home,SessionRecoveryState.load(file).allFlushedSessions().get(home.sessionUuid()));
    }
    @Test void uncheckedLeaseReleaseCannotDonateAnotherSidsSlotBoundsOrPlayer() throws Exception {
        var original=measured(1);
        for(var p:original.getList("Participants",10)) ((NbtCompound)p).putBoolean("Returned",true);
        var old=HomeJournalCodec.decode(original);
        var file=directory.resolve("data/session.dat");
        SessionJournalStore.write(file,M7BudgetSpikeTest.wrap(List.of(original)));
        var state=SessionRecoveryState.load(file);
        var cleared=original.copy(); cleared.put("SpaceLeases",new NbtList());
        var next=HomeJournalCodec.decode(cleared);
        assertEquals(SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING,state.commitHome(
                () -> {throw new IllegalStateException("注入：move 前失敗");},old,next,false).status());
        assertTrue(state.pendingSessions().isEmpty());
        assertEquals(next,state.allSessions().get(next.sessionUuid()));
        assertEquals(old,state.allFlushedSessions().get(old.sessionUuid()));
        for(String conflict:List.of("slot","bounds","player")) {
            var candidate=other(); var lease=candidate.getList("SpaceLeases",10).getCompound(0);
            switch(conflict) {
                case "slot" -> lease.putInt("SlotId",0);
                case "bounds" -> lease.putIntArray("Bounds",original.getList("SpaceLeases",10).getCompound(0).getIntArray("Bounds"));
                case "player" -> candidate.getList("Participants",10).getCompound(0).putUuid("PlayerUuid",old.participants().getFirst().playerUuid());
            }
            assertThrows(RuntimeException.class,() -> state.put(SessionRecoveryRecord.fromNbt(candidate,5)),conflict);
        }
        state.save(file.toFile(),null);
        var reuse=other(); reuse.getList("SpaceLeases",10).set(0,original.getList("SpaceLeases",10).getCompound(0).copy());
        reuse.getList("Participants",10).getCompound(0).putUuid("PlayerUuid",old.participants().getFirst().playerUuid());
        assertDoesNotThrow(() -> state.put(SessionRecoveryRecord.fromNbt(reuse,5)));
    }

    @Test void permanentSealsRejectRootLatchWhileRestartOnlySealCanLatch() {
        var base=HomeJournalCodec.encode(M7HomeHistoryTest.closed(32));
        base.putString("State","SEALED");
        var h=base.getCompound("Home"); h.put("CloseIntent",M7BudgetSpikeTest.kind("NONE"));
        h.putLong("CommittedAtGameTime",21); h.putLong("PassageOpenedAtGameTime",25); h.putLong("PassageExpiresAtGameTime",1225);
        for(var seal:HomeSessionRecord.Seal.values()) {
            var row=base.copy(); var fields=row.getCompound("Home"); fields.putString("SealedReason",seal.name());
            if(seal==HomeSessionRecord.Seal.TRANSFER_RECOVERY_REQUIRED) {
                var one=new NbtList(); one.add(row.getList("Participants",10).getCompound(0).get("PlayerUuid").copy());
                fields.put("RecoveryRequired",one);
            }
            if(seal==HomeSessionRecord.Seal.AUTHORITY_UNREADABLE) assertDoesNotThrow(() -> HomeJournalCodec.decode(row));
            else assertThrows(IllegalArgumentException.class,() -> HomeJournalCodec.decode(row),seal.name());
            fields.putString("StopReason",""); fields.putLong("StoppedAtGameTime",-1);
            assertDoesNotThrow(() -> HomeJournalCodec.decode(row));
        }
    }
    @Test void trackerDepthOrMalformedAuthorityDoesNotBecomeCapacityRetry() throws Exception {
        var row=M7BudgetSpikeTest.home(1);
        var snapshot=row.getList("Participants",10).getCompound(0).getCompound("QuantumState");
        var cursor=snapshot;
        for(int i=0;i<512;i++) {
            var child=new NbtCompound();cursor.put("Nested",child);cursor=child;
        }
        var deep=assertThrows(RuntimeException.class,() -> HomeJournalBudget.requireReserved(M7BudgetSpikeTest.wrap(List.of(row))));
        assertFalse(deep instanceof HomeJournalBudget.CapacityExceeded);
        var malformed=M7BudgetSpikeTest.home(1);malformed.putInt("State",10);
        var invalid=assertThrows(RuntimeException.class,() -> HomeJournalBudget.requireReserved(M7BudgetSpikeTest.wrap(List.of(malformed))));
        assertFalse(invalid instanceof HomeJournalBudget.CapacityExceeded);
    }
}
