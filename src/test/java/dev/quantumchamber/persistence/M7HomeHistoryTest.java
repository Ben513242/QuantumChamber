package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class M7HomeHistoryTest {
    @TempDir Path save;
    @BeforeAll static void version() { net.minecraft.SharedConstants.createGameVersion(); }

    static HomeSessionRecord closed(long time) {
        var row = M7HomeJournalTest.measured(1);
        for (var raw : row.getList("Participants",10)) ((NbtCompound)raw).putBoolean("Returned",true);
        row.put("SpaceLeases", new NbtList());
        var h = row.getCompound("Home");
        h.putString("StopReason","ROOT_INVALID_STRUCTURE");
        h.putLong("StoppedAtGameTime",30);
        var active = HomeJournalCodec.decode(row);
        return active.withClose(new HomeSessionRecord.Close(HomeSessionRecord.CloseReason.ROOT_INVALID_STRUCTURE,
                time, HomeSessionRecord.DoorGoal.OPEN, new HomeSessionRecord.RootStop(HomeSessionRecord.RootReason.ROOT_INVALID_STRUCTURE, 30)));
    }

    @Test void duplicateRawKeysAndHistoryMismatchFailClosed() throws Exception {
        var store = new HomeReceiptStore(save);
        var frozen = closed(31);
        store.requireAdmissible(frozen.sessionUuid());
        store.publishOrVerify(frozen);
        assertEquals(frozen, store.lookup(frozen.sessionUuid()).orElseThrow());
        var target = save.resolve("data/quantumchamber_home_receipts").resolve(frozen.sessionUuid()+".dat");
        byte[] before = Files.readAllBytes(target);
        assertThrows(Exception.class, () -> store.publishOrVerify(closed(32)));
        assertArrayEquals(before, Files.readAllBytes(target));
        assertThrows(Exception.class, () -> store.requireAdmissible(frozen.sessionUuid()));
        Files.write(target,new byte[]{1,2,3});
        assertThrows(Exception.class, () -> store.lookup(frozen.sessionUuid()));
        assertThrows(Exception.class, () -> store.publishOrVerify(frozen));
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(target));
    }

    @Test void concurrentPublicationKeepsWinner() throws Exception {
        var store = new HomeReceiptStore(save);
        var first = closed(31); var second = closed(32);
        store.requireAdmissible(first.sessionUuid());
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<Boolean>>();
            for (var row : List.of(first, second)) results.add(workers.submit(() -> {
                start.await();
                try { store.publishOrVerify(row); return true; } catch (java.io.IOException mismatch) { return false; }
            }));
            start.countDown();
            assertNotEquals(results.get(0).get(), results.get(1).get());
            assertEquals(results.get(0).get() ? first : second, store.lookup(first.sessionUuid()).orElseThrow());
        }
    }
    @Test void bothReadersRejectRawDuplicateAtEveryHomeDepth() throws Exception {
        var frozen=closed(31);
        var encoded=HomeJournalCodec.encode(frozen);
        var hot=M7BudgetSpikeTest.wrap(List.of(encoded));
        var store=new HomeReceiptStore(save);
        store.publishOrVerify(frozen);
        var target=save.resolve("data/quantumchamber_home_receipts").resolve(frozen.sessionUuid()+".dat");
        var receipt=HomeReceiptStore.wrapper(frozen);
        for (var value : List.of(hot,receipt)) {
            for (var key : List.of("DataVersion","SchemaVersion","HomeRevision","TravelCapabilityVersion")) {
                byte[] malformed=duplicateInt(value,key);
                if(value==hot) {
                    var p=save.resolve("bad-hot.dat");
                    Files.write(p,malformed);
                    assertThrows(java.io.IOException.class,() -> SessionJournalStore.read(p),key);
                } else {
                    Files.write(target,malformed);
                    assertThrows(java.io.IOException.class,() -> store.lookup(frozen.sessionUuid()),key);
                }
            }
        }
    }

    private static byte[] duplicateInt(NbtCompound wrapped,String key) throws Exception {
        var buffer=new java.io.ByteArrayOutputStream();
        try(var out=new java.io.DataOutputStream(buffer)) { out.writeByte(3); out.writeUTF(key); }
        byte[] marker=buffer.toByteArray(), raw;
        try(var input=new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(M7BudgetSpikeTest.compressed(wrapped)))) {
            raw=input.readAllBytes();
        }
        int index=-1;
        for(int i=0;i<=raw.length-marker.length;i++) if(Arrays.equals(raw,i,i+marker.length,marker,0,marker.length)) {
            assertEquals(-1,index); index=i;
        }
        assertTrue(index>=0);
        var result=new java.io.ByteArrayOutputStream();
        try(var out=new java.util.zip.GZIPOutputStream(result)) {
            out.write(raw,0,index); out.write(raw,index,marker.length+4); out.write(raw,index,raw.length-index);
        }
        return result.toByteArray();
    }

    @Test void closeMatrixRejectsMismatchedOriginOrEvidence() {
        var close=closed(31);
        var encoded=HomeJournalCodec.encode(close);
        for (String key : List.of("DoorGoal","CloseReason","ClosedAtGameTime","Evidence","StopReason","StoppedAtGameTime","State")) {
            var bad=encoded.copy(); var h=bad.getCompound("Home"); var intent=h.getCompound("CloseIntent");
            switch(key) {
                case "DoorGoal" -> intent.putString(key,"CLOSE_IF_CLEAR_ELSE_OPEN");
                case "CloseReason" -> intent.putString(key,"ALL_CROSSED");
                case "ClosedAtGameTime" -> intent.putLong(key,19);
                case "Evidence" -> intent.getCompound(key).putInt("Extra",1);
                case "StopReason" -> h.putString(key,"ROOT_DISABLED");
                case "StoppedAtGameTime" -> h.putLong(key,26);
                case "State" -> bad.putString(key,"RETURNING");
            }
            assertThrows(RuntimeException.class,() -> HomeJournalCodec.decode(bad),key);
        }
    }
    @Test void everySchemaSixActiveSidMustAgreeWithItsHistory() throws Exception {
        var frozen=closed(31);
        var store=new HomeReceiptStore(save); store.publishOrVerify(frozen);
        var original=save.resolve("data/quantumchamber_home_receipts").resolve(frozen.sessionUuid()+".dat");
        var old=M7HomeJournalTest.other();
        var sid=old.getUuid("SessionUuid");
        var receipt=original.resolveSibling(sid+".dat");
        var normalized=HomeReceiptStore.wrapper(frozen);
        var data=normalized.getCompound("data"); data.putUuid("SessionUuid",sid);
        var session=data.getCompound("Session"); session.putUuid("SessionUuid",sid);
        session.getCompound("Home").getCompound("Selection").getCompound("DoorKey").putUuid("SessionUuid",sid);
        var target=save.resolve("data/session.dat");
        for(boolean corrupt:List.of(false,true)) {
            if(corrupt) Files.write(receipt,new byte[]{1,2,3}); else NbtIo.writeCompressed(normalized,receipt);
            var immutable=Files.readAllBytes(receipt);
            for(boolean mixed:List.of(false,true)) {
                var rows=mixed?List.of(old,HomeJournalCodec.encode(frozen)):List.of(old);
                var wrapped=M7BudgetSpikeTest.wrap(rows);
                NbtIo.writeCompressed(wrapped,target);
                var input=Files.readAllBytes(target);
                assertThrows(RuntimeException.class,() -> SessionRecoveryState.load(target).requireHealthy());
                assertThrows(Exception.class,() -> SessionJournalStore.write(target,wrapped));
                assertArrayEquals(input,Files.readAllBytes(target));
                assertArrayEquals(immutable,Files.readAllBytes(receipt));
            }
        }
    }

    @Test void matchingTypedReceiptStillRequiresExactWrapperBeforeRemovingActive() throws Exception {
        var frozen=closed(31); var store=new HomeReceiptStore(save);
        var target=save.resolve("data/session.dat");
        SessionJournalStore.write(target,M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(frozen))));
        var state=SessionRecoveryState.load(target); store.publishOrVerify(frozen);
        var receipt=save.resolve("data/quantumchamber_home_receipts").resolve(frozen.sessionUuid()+".dat");
        var tampered=HomeReceiptStore.wrapper(frozen);
        tampered.putInt("DataVersion",tampered.getInt("DataVersion")+1); NbtIo.writeCompressed(tampered,receipt);
        var before=Files.readAllBytes(receipt); var hot=Files.readAllBytes(target);
        assertThrows(Exception.class,() -> store.publishOrVerify(frozen));
        assertFalse(state.terminateHome(store,() -> state.save(target.toFile(),null),frozen).removed());
        assertThrows(RuntimeException.class,() -> SessionRecoveryState.load(target).requireHealthy());
        assertArrayEquals(before,Files.readAllBytes(receipt)); assertArrayEquals(hot,Files.readAllBytes(target));
    }
}
