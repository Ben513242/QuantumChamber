package dev.quantumchamber.persistence;

import static dev.quantumchamber.persistence.HomeSessionRecord.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.collapse.HomeClosure;
import java.nio.file.*;
import java.util.*;
import net.minecraft.nbt.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** T4：回程終局 owner 的同 JVM 決策矩陣與 journal 守門（M7 spec §5.3、§9.2）。 */
class M7CloseMatrixTest {
    @TempDir Path directory;
    @BeforeAll static void version() { net.minecraft.SharedConstants.createGameVersion(); }

    private static final UUID INSTANCE = new UUID(7, 900);

    /** 已選 HOME、全員返回、無租約的列；{@code state}／時間由呼叫者補齊。 */
    private static NbtCompound cleaned(String state) {
        var row = M7HomeJournalTest.measured(1);
        for (var raw : row.getList("Participants", 10)) ((NbtCompound) raw).putBoolean("Returned", true);
        row.put("SpaceLeases", new NbtList());
        row.putString("State", state);
        return row;
    }

    /** PASSAGE_READY：commit=21、opened=30、expires=1230，前 {@code crossed} 位已跨越。 */
    static HomeSessionRecord ready(int crossed, String stop, long stoppedAt) {
        var row = cleaned("PASSAGE_READY");
        var h = row.getCompound("Home");
        h.putLong("CommittedAtGameTime", 21); h.putLong("PassageOpenedAtGameTime", 30); h.putLong("PassageExpiresAtGameTime", 1230);
        var list = new NbtList();
        for (int i = 0; i < crossed; i++) list.add(row.getList("Participants", 10).getCompound(i).get("PlayerUuid").copy());
        h.put("Crossed", list);
        h.putString("StopReason", stop); h.putLong("StoppedAtGameTime", stoppedAt);
        return HomeJournalCodec.decode(row);
    }

    private static HomeSessionRecord dormant(String stop, long stoppedAt) {
        var row = cleaned("MEASURED");
        row.getCompound("Home").putString("StopReason", stop); row.getCompound("Home").putLong("StoppedAtGameTime", stoppedAt);
        return HomeJournalCodec.decode(row);
    }

    private static HomeSessionRecord unreturnedWithStop() {
        var row = M7HomeJournalTest.measured(1);
        row.getCompound("Home").putString("StopReason", "ROOT_DISABLED"); row.getCompound("Home").putLong("StoppedAtGameTime", 25);
        return HomeJournalCodec.decode(row);
    }

    private static HomeSessionRecord collapsing() {
        var row = cleaned("COLLAPSING");
        row.getCompound("Home").putLong("CommittedAtGameTime", 21);
        return HomeJournalCodec.decode(row);
    }

    static HomeSessionRecord sealed(Seal seal) {
        var row = cleaned("SEALED");
        var h = row.getCompound("Home");
        h.putLong("CommittedAtGameTime", 21);
        if (seal == Seal.TRANSFER_RECOVERY_REQUIRED || seal == Seal.AUTHORITY_UNREADABLE) {
            h.putLong("PassageOpenedAtGameTime", 30); h.putLong("PassageExpiresAtGameTime", 1230);
        }
        if (seal == Seal.TRANSFER_RECOVERY_REQUIRED) {
            var one = new NbtList(); one.add(row.getList("Participants", 10).getCompound(0).get("PlayerUuid").copy());
            h.put("RecoveryRequired", one);
        }
        h.putString("SealedReason", seal.name());
        return HomeJournalCodec.decode(row);
    }

    private static HomeClosure.Facts at(long now) { return new HomeClosure.Facts(now, Optional.empty(), false); }
    private static HomeClosure.Facts startup(long now, boolean recovered) { return new HomeClosure.Facts(now, Optional.of(INSTANCE), recovered); }

    private static Close close(HomeSessionRecord row, HomeClosure.Facts facts) {
        var close = HomeClosure.decide(row, facts).orElseThrow(() -> new AssertionError("應 mint 結案：" + row.state() + " facts=" + facts));
        assertDoesNotThrow(() -> row.withClose(close), "mint 的結案必須通過正式矩陣");
        return close;
    }

    @Test void decideFollowsCloseMatrixPriority() {
        var all = ready(25, "", -1);
        assertEquals(new Close(CloseReason.ALL_CROSSED, 40, DoorGoal.OPEN, new AllCrossed()), close(all, at(40)));
        var none = ready(0, "", -1);
        assertTrue(HomeClosure.decide(none, at(40)).isEmpty(), "未到期、非開服、未全跨不結案");
        assertEquals(new Close(CloseReason.EXPIRED, 1230, DoorGoal.OPEN, new Expired(1230)), close(none, at(1230)));
        assertEquals(new Close(CloseReason.RESTART_CLOSED, 40, DoorGoal.OPEN, new RestartClosed(INSTANCE, 40)), close(none, startup(40, false)));
        var stopped = ready(3, "ROOT_DISABLED", 35);
        assertEquals(new Close(CloseReason.ROOT_DISABLED, 40, DoorGoal.OPEN, new RootStop(RootReason.ROOT_DISABLED, 35)),
                close(stopped, startup(40, false)), "已 latch 的原艙停止優先於開服收尾");
        assertTrue(HomeClosure.decide(unreturnedWithStop(), at(40)).isEmpty(), "未返完先收斂返還，不提前 CLOSE");
        assertEquals(new Close(CloseReason.ROOT_DESTROYED, 40, DoorGoal.OPEN, new RootStop(RootReason.ROOT_DESTROYED, 25)),
                close(dormant("ROOT_DESTROYED", 25), at(40)), "無 B runtime 的 DORMANT 也能歸檔原艙停止");
        assertTrue(HomeClosure.decide(collapsing(), at(40)).isEmpty(), "沒有互動時 step 不得補造參與者結案");
        assertTrue(HomeClosure.decide(M7HomeHistoryTest.closed(31), at(40)).isEmpty(), "已凍結的 CLOSE 不再 mint");
        assertTrue(HomeClosure.decide(all, at(29)).isEmpty(), "早於通道開啟的時間不能結案");
        var unreadable = sealed(Seal.AUTHORITY_UNREADABLE);
        assertEquals(new Close(CloseReason.RESTART_CLOSED, 40, DoorGoal.OPEN, new RestartClosed(INSTANCE, 40)), close(unreadable, startup(40, true)));
        assertTrue(HomeClosure.decide(unreadable, startup(40, false)).isEmpty(), "讀取問題未驗恢復不歸檔");
        assertTrue(HomeClosure.decide(unreadable, new HomeClosure.Facts(40, Optional.empty(), true)).isEmpty(), "非開服不歸檔曾 READY 的讀取封存");
    }

    @Test void restartClosedNeedsStartupOwner() throws Exception {
        var row = ready(0, "", -1);
        assertTrue(HomeClosure.decide(row, at(40)).isEmpty(), "非 startup 不得 RESTART_CLOSED");
        assertEquals(CloseReason.EXPIRED, close(row, at(1300)).reason(), "非 startup 到期只能 EXPIRED");
        assertEquals(new RestartClosed(INSTANCE, 40), close(row, startup(40, false)).evidence());
        var forged = row.withClose(new Close(CloseReason.RESTART_CLOSED, 40, DoorGoal.OPEN, new RestartClosed(UUID.randomUUID(), 40)));
        var file = directory.resolve("data/restart.dat");
        SessionJournalStore.write(file, M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(row))));
        var state = SessionRecoveryState.load(file);
        byte[] before = Files.readAllBytes(file);
        var result = state.commitHome(() -> state.save(file.toFile(), null), row, forged, false);
        assertEquals(SessionRecoveryState.CommitResult.Status.REJECTED, result.status(), "直接 roll-forward 不得自稱 startup owner");
        assertEquals(Map.of(row.sessionUuid(), row), state.allSessions());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void closeLatchRequiresOwnerMint() throws Exception {
        var row = ready(25, "", -1);
        var file = directory.resolve("data/latch.dat");
        SessionJournalStore.write(file, M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(row))));
        var state = SessionRecoveryState.load(file);
        Runnable flush = () -> state.save(file.toFile(), null);
        byte[] before = Files.readAllBytes(file);
        for (var forged : List.of(new Close(CloseReason.ALL_CROSSED, 40, DoorGoal.OPEN, new AllCrossed()),
                new Close(CloseReason.EXPIRED, 1230, DoorGoal.OPEN, new Expired(1230)),
                new Close(CloseReason.PARTICIPANT_CLOSED, 40, DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN,
                        new ParticipantClosed(row.participants().getFirst().playerUuid(), Phase.PASSAGE, 40)))) {
            var result = state.commitHome(flush, row, row.withClose(forged), false);
            assertEquals(SessionRecoveryState.CommitResult.Status.REJECTED, result.status(), "沒有 owner mint 的 " + forged.reason());
        }
        assertEquals(SessionRecoveryState.CommitResult.Status.REJECTED, state.latchHomeClose(flush, null, null).status(), "空 mint");
        assertEquals(Map.of(row.sessionUuid(), row), state.allSessions());
        assertEquals(Map.of(row.sessionUuid(), row), state.allFlushedSessions());
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void closedRowRemovalRequiresClosureProof() throws Exception {
        var frozen = M7HomeHistoryTest.closed(31);
        var file = directory.resolve("data/finish.dat");
        var store = new HomeReceiptStore(directory);
        SessionJournalStore.write(file, M7BudgetSpikeTest.wrap(List.of(HomeJournalCodec.encode(frozen))));
        var state = SessionRecoveryState.load(file);
        store.publishOrVerify(frozen);
        var result = state.finishHomeClose(store, () -> state.save(file.toFile(), null), frozen, null, null);
        assertEquals(SessionRecoveryState.TerminalResult.Status.REJECTED, result.status(), "收據已存在仍需 owner proof");
        assertEquals(Map.of(frozen.sessionUuid(), frozen), state.allFlushedSessions());
        assertEquals(frozen, store.lookup(frozen.sessionUuid()).orElseThrow());
    }

    @Test void participantCloseKeepsActualActorAndPhase() {
        // 兩個獨立 fixture：同形狀的 READY 列各由不同參與者在不同時間操作。
        var first = ready(1, "", -1);
        var second = ready(1, "", -1);
        var a = first.participants().get(0).playerUuid();
        var b = second.participants().get(1).playerUuid();
        assertEquals(new Close(CloseReason.PARTICIPANT_CLOSED, 50, DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN, new ParticipantClosed(a, Phase.PASSAGE, 50)),
                HomeClosure.participantClose(first, a, HomeClosure.DoorPhase.PASSAGE, 50, 50).orElseThrow());
        assertEquals(new Close(CloseReason.PARTICIPANT_CLOSED, 60, DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN, new ParticipantClosed(b, Phase.PASSAGE, 60)),
                HomeClosure.participantClose(second, b, HomeClosure.DoorPhase.PASSAGE, 60, 60).orElseThrow());
        assertDoesNotThrow(() -> first.withClose(HomeClosure.participantClose(first, a, HomeClosure.DoorPhase.PASSAGE, 50, 50).orElseThrow()));
        assertTrue(HomeClosure.participantClose(first, a, HomeClosure.DoorPhase.PREPARE, 50, 50).isEmpty(), "READY 不接受 PREPARE");
        assertTrue(HomeClosure.participantClose(first, UUID.randomUUID(), HomeClosure.DoorPhase.PASSAGE, 50, 50).isEmpty(), "非 cohort");
        assertTrue(HomeClosure.participantClose(first, a, HomeClosure.DoorPhase.PASSAGE, 49, 50).isEmpty(), "不是當下事件的時間");
        assertTrue(HomeClosure.participantClose(first, a, HomeClosure.DoorPhase.PASSAGE, 29, 29).isEmpty(), "早於通道開啟");
        var prepare = collapsing();
        var c = prepare.participants().get(2).playerUuid();
        assertEquals(new ParticipantClosed(c, Phase.PREPARE, 40),
                HomeClosure.participantClose(prepare, c, HomeClosure.DoorPhase.PREPARE, 40, 40).orElseThrow().evidence());
        assertTrue(HomeClosure.participantClose(prepare, c, HomeClosure.DoorPhase.PASSAGE, 40, 40).isEmpty(), "COLLAPSING 不接受 PASSAGE");
        assertTrue(HomeClosure.participantClose(ready(1, "ROOT_DISABLED", 35), a, HomeClosure.DoorPhase.PASSAGE, 50, 50).isEmpty(),
                "已 latch 的原艙停止優先");
        assertTrue(HomeClosure.decide(first, at(50)).isEmpty(), "沒有互動不得由 step mint");
    }

    @Test void permanentSealCannotBeArchived() {
        for (var seal : List.of(Seal.TRANSFER_RECOVERY_REQUIRED, Seal.ENTRY_AUTHORITY_INVALID, Seal.AUTHORITY_CORRUPT)) {
            var row = sealed(seal);
            var actor = row.participants().getFirst().playerUuid();
            assertTrue(HomeClosure.decide(row, startup(1300, true)).isEmpty(), seal + " 不歸檔");
            for (var phase : HomeClosure.DoorPhase.values())
                assertTrue(HomeClosure.participantClose(row, actor, phase, 1300, 1300).isEmpty(), seal + " 不接受參與者結案");
            for (var forged : List.<Close>of(new Close(CloseReason.RESTART_CLOSED, 1300, DoorGoal.OPEN, new RestartClosed(INSTANCE, 1300)),
                    new Close(CloseReason.EXPIRED, 1300, DoorGoal.OPEN, new Expired(1300)),
                    new Close(CloseReason.ROOT_DISABLED, 1300, DoorGoal.OPEN, new RootStop(RootReason.ROOT_DISABLED, 1300))))
                assertThrows(IllegalArgumentException.class, () -> row.withClose(forged), seal + " 偽造 " + forged.reason());
        }
    }
}
