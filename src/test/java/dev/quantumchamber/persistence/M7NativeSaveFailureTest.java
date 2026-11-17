package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.collapse.CollapseService;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T4 P2（使用者 2026-11-17 裁示「M7 一併修」；t4b2a-review P1-M3、P1-M1）：原生存檔（{@code save(File,…)}，原版 {@code PersistentStateManager.save} 的入口；
 * autosave、{@code /save-all}、停服都走這裡）遇到任何 journal 寫入失敗，都不丟進原版存檔鏈，而且保持 M5 既有的 pending／dirty 語意：
 * <ul>
 * <li>未確認的寫入不覆寫；結果不明的寫入（possiblyMoved）保留 pending，下一次寫出先核對磁碟；journal 不健康時不寫；</li>
 * <li>失敗後保持 dirty 或 pending，由既有重試者收斂（下一次原生存檔、recovery 的 journal 退避重試；需要重啟的，重啟後由開服 strict 載入收斂）；</li>
 * <li>checked 路徑（{@link SessionRecoveryState#checkedSave}，{@code flush} 用的同一個寫出）照舊丟出；</li>
 * <li>每一類失敗每個 instance 只記一次 ERROR，帶 {@code CollapseService.TICK_LOG_MARKER}（不屬於任何 session）並附 cause。</li>
 * </ul>
 * 收據不符的 ERROR（P1-M1）：收據檔路徑、處理方式、有變動時全服 journal 寫入已暫停；讀檔 IO 錯誤不寫成竄改或損壞；去重鍵是 SID 加類別。
 * <p>atomic move 失敗以「目標是既存目錄」重現：Windows 的 {@code Files.move} 因此丟 {@code AccessDeniedException}（防毒或索引鎖檔時的同一個簽章），Linux 丟
 * {@code FileSystemException}；兩者都是真正的 {@code writePrepared} 失敗（possiblyMoved=true），不是假造的例外。
 */
class M7NativeSaveFailureTest {
    @TempDir Path save;
    @BeforeAll static void version() { net.minecraft.SharedConstants.createGameVersion(); }

    /** HOME 列（全員已返回 B、租約仍在）以失敗的 checked 寫出留下 roll-forward pending：journal dirty、current 是清除租約的下一步。 */
    private record Pending(Path target, SessionRecoveryState state, HomeSessionRecord old, HomeSessionRecord next) {
        UUID sid() { return old.sessionUuid(); }
    }

    private Pending rollForwardPending() throws Exception {
        var original = M7HomeJournalTest.measured(1);
        for (var p : original.getList("Participants", 10)) ((NbtCompound) p).putBoolean("Returned", true);
        var old = HomeJournalCodec.decode(original);
        var target = save.resolve("data/quantumchamber_sessions.dat");
        SessionJournalStore.write(target, M7BudgetSpikeTest.wrap(List.of(original)));
        var state = SessionRecoveryState.load(target); state.requireHealthy();
        var cleared = original.copy(); cleared.put("SpaceLeases", new NbtList());
        var next = HomeJournalCodec.decode(cleared);
        assertEquals(SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING,
                state.commitHome(() -> { throw new IllegalStateException("注入：checked 寫出前失敗"); }, old, next, false).status());
        assertTrue(state.isDirty() && state.rollForwardPending(old.sessionUuid()) && state.pendingSessions().isEmpty(), "前提：dirty、roll-forward 待確認、沒有 pending");
        return new Pending(target, state, old, next);
    }

    /** 把正式檔換成既存目錄，讓下一次 atomic move 失敗；回傳原本 bytes 的保存位置。 */
    private static Path blockAtomicMove(Path target) throws Exception {
        var aside = target.resolveSibling("aside-" + UUID.randomUUID() + ".dat");
        Files.move(target, aside);
        Files.createDirectory(target);
        return aside;
    }

    /** 解除故障：移除目錄、把原本的 bytes 放回（磁碟仍是上一份權威）。 */
    private static void unblock(Path target, Path aside) throws Exception {
        Files.delete(target);
        Files.move(aside, target);
    }

    @Test void atomicMoveFailureDuringNativeSaveKeepsPendingAndTheNextSaveConverges() throws Exception {
        var f = rollForwardPending();
        byte[] durable = Files.readAllBytes(f.target());
        try (var logs = new Logs()) {
            var aside = blockAtomicMove(f.target());
            assertDoesNotThrow(() -> f.state().save(f.target().toFile(), null), "NATIVE_WRITE_THREW：atomic move 失敗時原生存檔不得丟出");
            assertTrue(f.state().isDirty(), "保持 dirty");
            assertEquals(f.old(), f.state().allFlushedSessions().get(f.sid()), "flushed 仍是上一份 durable");
            assertEquals(f.next(), f.state().allSessions().get(f.sid()));
            assertEquals(Map.of(f.sid(), f.next()), f.state().pendingSessions(), "possiblyMoved：結果不明的寫入保留 pending");
            assertTrue(f.state().rollForwardPending(f.sid()));
            assertTrue(Files.isDirectory(f.target()), "正式檔位置沒有被改寫");
            var errors = logs.nativeSaveErrors();
            assertEquals(1, errors.size(), "原生存檔的寫出失敗記一則 ERROR：" + errors);
            var text = errors.getFirst().getMessage().getFormattedMessage();
            assertTrue(text.contains("寫入正式檔失敗") && text.contains("保留 pending") && text.contains(f.target().toAbsolutePath().normalize().toString()), text);
            assertNotNull(errors.getFirst().getThrown(), "ERROR 附 cause");
            assertTrue(errors.getFirst().getMarker().isInstanceOf(CollapseService.TICK_LOG_MARKER.getName()), "不屬於任何 session：TICK marker");
            unblock(f.target(), aside);
            assertArrayEquals(durable, Files.readAllBytes(f.target()));
            assertDoesNotThrow(() -> f.state().save(f.target().toFile(), null), "故障解除後的原生存檔先核對磁碟再寫出");
            assertFalse(f.state().isDirty());
            assertTrue(f.state().pendingSessions().isEmpty());
            assertFalse(f.state().rollForwardPending(f.sid()), "roll-forward 已確認");
            assertEquals(f.next(), f.state().allFlushedSessions().get(f.sid()));
            assertEquals(Map.of(f.sid(), f.next()), SessionRecoveryState.load(f.target()).allFlushedSessions());
            assertEquals(1, logs.nativeSaveErrors().size(), "成功之後不再記 ERROR");
        }
    }

    @Test void checkedFlushStillFailsWhenTheAtomicMoveFails() throws Exception {
        var f = rollForwardPending();
        try (var logs = new Logs()) {
            var aside = blockAtomicMove(f.target());
            var thrown = assertThrows(UncheckedIOException.class, () -> f.state().checkedSave(f.target().toFile(), null), "checked 路徑照舊丟出");
            assertTrue(FailureText.dedupeKey(thrown).contains("journal 原子寫入失敗，possiblyMoved=true"), FailureText.dedupeKey(thrown));
            assertEquals(Map.of(f.sid(), f.next()), f.state().pendingSessions());
            assertTrue(f.state().isDirty());
            assertTrue(logs.nativeSaveErrors().isEmpty(), "checked 路徑不記原生存檔的 ERROR（由 caller 回報）");
            unblock(f.target(), aside);
            assertDoesNotThrow(() -> f.state().checkedSave(f.target().toFile(), null));
            assertEquals(f.next(), f.state().allFlushedSessions().get(f.sid()));
        }
    }

    @Test void unconfirmedAttemptStopsNativeWritesWithoutThrowingUntilRestart() throws Exception {
        var f = rollForwardPending();
        var port = SessionRecoveryState.class.getDeclaredField("readback"); port.setAccessible(true);
        try (var logs = new Logs()) {
            port.set(f.state(), (SessionRecoveryState.JournalReadback) path -> { throw new java.io.IOException("注入：move 之後讀回失敗"); });
            assertDoesNotThrow(() -> f.state().save(f.target().toFile(), null), "NATIVE_READBACK_THREW：讀回失敗時原生存檔不得丟出");
            port.set(f.state(), (SessionRecoveryState.JournalReadback) SessionJournalStore::read);
            assertEquals(Map.of(f.sid(), f.next()), SessionRecoveryState.fromNbt(SessionJournalStore.read(f.target()).getCompound("data")).allSessions(),
                    "前提：這次寫出已經 move 到正式檔、只是沒有確認");
            assertEquals(Map.of(f.sid(), f.next()), f.state().pendingSessions(), "讀回未確認：保留 pending");
            assertEquals(f.old(), f.state().allFlushedSessions().get(f.sid()));
            assertTrue(f.state().isDirty());
            // 未確認的 attempt 已在磁碟上：之後的原生存檔都不得寫出（覆寫）它。mtime 設成固定的舊值，連內容相同的重寫也看得出來。
            var sentinel = FileTime.fromMillis(978307200000L);
            Files.setLastModifiedTime(f.target(), sentinel);
            byte[] attempt = Files.readAllBytes(f.target());
            for (int round = 0; round < 2; round++) {
                int r = round;
                assertDoesNotThrow(() -> f.state().save(f.target().toFile(), null), "NATIVE_PENDING_THREW：pending 未確認時原生存檔不得丟出（第 " + (r + 1) + " 次）");
                assertArrayEquals(attempt, Files.readAllBytes(f.target()), "PENDING_OVERWRITTEN：未確認的寫入不得被覆寫");
                assertEquals(sentinel, Files.getLastModifiedTime(f.target()), "PENDING_REWRITTEN：未確認的寫入不得被重寫");
                assertTrue(f.state().isDirty());
                assertEquals(Map.of(f.sid(), f.next()), f.state().pendingSessions(), "仍保留 pending");
                assertEquals(f.old(), f.state().allFlushedSessions().get(f.sid()));
            }
            var refused = assertThrows(UncheckedIOException.class, () -> f.state().checkedSave(f.target().toFile(), null), "checked 路徑照舊丟出");
            assertEquals("JOURNAL_PENDING_RESTART_REQUIRED", refused.getCause().getMessage());
            assertArrayEquals(attempt, Files.readAllBytes(f.target()));
            var errors = logs.nativeSaveErrors();
            assertEquals(2, errors.size(), "讀回與 pending 兩類各一則，pending 重複時不重記：" + errors.stream().map(e -> e.getMessage().getFormattedMessage()).toList());
            var readback = errors.get(0).getMessage().getFormattedMessage();
            var unconfirmed = errors.get(1).getMessage().getFormattedMessage();
            assertTrue(readback.contains("正式檔讀回失敗或與寫出的內容不符"), readback);
            assertTrue(unconfirmed.contains("上一次寫出的結果尚未確認") && unconfirmed.contains("需要重啟伺服器才能收斂"), unconfirmed);
            assertTrue(FailureText.dedupeKey(errors.get(1).getThrown()).contains("JOURNAL_PENDING_EXACT_ATTEMPT_RESTART_REQUIRED"), FailureText.dedupeKey(errors.get(1).getThrown()));
            assertTrue(errors.stream().allMatch(e -> e.getMarker().isInstanceOf(CollapseService.TICK_LOG_MARKER.getName())));
        }
        var restarted = SessionRecoveryState.load(f.target()); restarted.requireHealthy();
        assertEquals(Map.of(f.sid(), f.next()), restarted.allFlushedSessions(), "重啟後由開服 strict 載入收斂（磁碟上的 attempt 成為 durable）");
    }

    @Test void unhealthyJournalNativeSaveDoesNotThrowOrWrite() throws Exception {
        var target = save.resolve("data/quantumchamber_sessions.dat");
        Files.createDirectories(target.getParent());
        Files.write(target, new byte[] {1, 2, 3, 4});
        var failed = SessionRecoveryState.load(target);
        assertThrows(IllegalStateException.class, failed::requireHealthy, "前提：journal 不健康");
        try (var logs = new Logs()) {
            for (int round = 0; round < 2; round++) {
                assertDoesNotThrow(() -> failed.save(target.toFile(), null), "NATIVE_UNHEALTHY_THREW：journal 不健康時原生存檔不得丟出");
                assertArrayEquals(new byte[] {1, 2, 3, 4}, Files.readAllBytes(target), "journal 不健康時不寫");
            }
            assertThrows(IllegalStateException.class, () -> failed.checkedSave(target.toFile(), null), "checked 路徑照舊丟出");
            var errors = logs.nativeSaveErrors();
            assertEquals(1, errors.size(), "同一類只記一次");
            var text = errors.getFirst().getMessage().getFormattedMessage();
            assertTrue(text.contains("journal 不健康") && text.contains("修復或移走損壞的 journal 檔") && text.contains("重啟伺服器"), text);
        }
    }

    @Test void legacyWriteFailureDuringNativeSaveKeepsDirtyAndTheNextSaveConverges() throws Exception {
        var state = SessionRecoveryState.fromNbt(SessionRecoveryStateTest.fixture("ARMING", true));
        var record = state.records().values().iterator().next();
        state.markDirty();
        var blocker = save.resolve("blocker"); Files.writeString(blocker, "一般檔案");
        try (var logs = new Logs()) {
            assertDoesNotThrow(() -> state.save(blocker.resolve("journal.dat").toFile(), null), "NATIVE_LEGACY_WRITE_THREW：schema 1–4 的寫入失敗時原生存檔不得丟出");
            assertTrue(state.isDirty(), "保持 dirty");
            assertEquals("一般檔案", Files.readString(blocker));
            var good = save.resolve("good.dat");
            assertDoesNotThrow(() -> state.save(good.toFile(), null));
            assertFalse(state.isDirty());
            assertEquals(Map.of(record.sessionUuid(), record), SessionRecoveryState.load(good).flushedRecords());
            assertEquals(1, logs.nativeSaveErrors().size());
            assertTrue(logs.nativeSaveErrors().getFirst().getMessage().getFormattedMessage().contains("寫入正式檔失敗"));
        }
    }

    // ---- 收據不符的 ERROR（P1-M1） ----

    private static Path receipt(Path save, UUID sid) { return save.resolve("data/quantumchamber_home_receipts").resolve(sid + ".dat"); }

    @Test void unchangedMismatchErrorNamesTheReceiptAndHowToRecover() throws Exception {
        var frozen = M7HomeHistoryTest.closed(31);
        var target = save.resolve("data/session.dat");
        SessionJournalStore.write(target, M7BudgetSpikeTest.wrap(List.of(M7HomeJournalTest.other(), HomeJournalCodec.encode(frozen))));
        new HomeReceiptStore(save).publishOrVerify(frozen);
        var state = SessionRecoveryState.load(target); state.requireHealthy();
        NbtIo.writeCompressed(HomeReceiptStore.wrapper(M7HomeHistoryTest.closed(32)), receipt(save, frozen.sessionUuid()));
        state.markDirty();
        try (var logs = new Logs()) {
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            state.markDirty();
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            var errors = logs.sessionErrors(frozen.sessionUuid());
            assertEquals(1, errors.size(), "同 SID 同類只記一次");
            var text = errors.getFirst().getMessage().getFormattedMessage();
            assertTrue(text.contains("與熱列不符") && text.contains("外部竄改") && text.contains("這一列未變動"), text);
            assertTrue(text.contains(receipt(save, frozen.sessionUuid()).toAbsolutePath().normalize().toString()), "附收據檔路徑：" + text);
            assertTrue(text.contains("刪除") && text.contains("還原") && text.contains("下一次存檔"), "附處理方式：" + text);
            assertFalse(text.contains("全服 journal 寫入已暫停"), "未變動型不暫停全服寫入：" + text);
            assertTrue(errors.getFirst().getMarker().isInstanceOf(CollapseService.LOG_MARKER.getName())
                    && !errors.getFirst().getMarker().isInstanceOf(CollapseService.TICK_LOG_MARKER.getName()), "能歸屬到 SID：LOG_MARKER、帶 session=");
        }
    }

    /** 本次有變動（未結案的熱列）旁出現同 SID 的收據檔：讀不出收據（目錄）時寫「無法核對」、不寫竄改；換成內容不同的收據後是「不符」，兩類各記一次。 */
    @Test void changedRowErrorsSeparateUnreadableFromMismatchAndSayWritesArePaused() throws Exception {
        var original = M7HomeJournalTest.measured(1);
        for (var p : original.getList("Participants", 10)) ((NbtCompound) p).putBoolean("Returned", true);
        var old = HomeJournalCodec.decode(original);
        var target = save.resolve("data/session.dat");
        SessionJournalStore.write(target, M7BudgetSpikeTest.wrap(List.of(original)));
        var state = SessionRecoveryState.load(target); state.requireHealthy();
        var cleared = original.copy(); cleared.put("SpaceLeases", new NbtList());
        var next = HomeJournalCodec.decode(cleared);
        assertEquals(SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING,
                state.commitHome(() -> { throw new IllegalStateException("注入：寫出前失敗"); }, old, next, false).status());
        var path = receipt(save, old.sessionUuid());
        Files.createDirectories(path);
        byte[] disk = Files.readAllBytes(target);
        try (var logs = new Logs()) {
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            assertArrayEquals(disk, Files.readAllBytes(target), "無法核對、有變動的列不寫出");
            var unreadable = logs.sessionErrors(old.sessionUuid());
            assertEquals(1, unreadable.size());
            var first = unreadable.getFirst().getMessage().getFormattedMessage();
            assertTrue(first.contains("無法讀取或核對") && first.contains("全服 journal 寫入已暫停") && first.contains(path.toAbsolutePath().normalize().toString()), first);
            assertFalse(first.contains("竄改") || first.contains("損壞（"), "讀檔 IO 錯誤不得寫成外部竄改或損壞：" + first);
            Files.delete(path);
            new HomeReceiptStore(save).publishOrVerify(M7HomeHistoryTest.closed(31));
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            assertArrayEquals(disk, Files.readAllBytes(target), "不符、有變動的列不寫出");
            var errors = logs.sessionErrors(old.sessionUuid());
            assertEquals(2, errors.size(), "去重鍵是 SID 加類別：無法核對與不符各一則：" + errors.stream().map(e -> e.getMessage().getFormattedMessage()).toList());
            var second = errors.get(1).getMessage().getFormattedMessage();
            assertTrue(second.contains("與熱列不符") && second.contains("全服 journal 寫入已暫停") && second.contains("這一列本次有變動"), second);
            Files.delete(path);
            assertDoesNotThrow(() -> state.save(target.toFile(), null));
            assertFalse(state.isDirty(), "移除收據之後下一次存檔就恢復");
            assertEquals(next, state.allFlushedSessions().get(old.sessionUuid()));
        }
    }

    /** 只收 {@code quantumchamber} logger 的 log4j appender；先送探針證明攔得到，不能以空結果冒充「沒有 ERROR」。 */
    private static final class Logs extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.LoggerContext context;
        private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
        Logs() {
            super("t4p2-log-capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY);
            context = ((org.apache.logging.log4j.core.Logger) LogManager.getLogger("quantumchamber")).getContext();
            start();
            context.getConfiguration().getRootLogger().addAppender(this, null, null);
            context.updateLoggers();
            var probe = "T4P2_LOG_CAPTURE_PROBE " + UUID.randomUUID();
            org.slf4j.LoggerFactory.getLogger("quantumchamber").error(CollapseService.TICK_LOG_MARKER, probe);
            boolean seen;
            synchronized (events) { seen = events.stream().anyMatch(event -> event.getMessage().getFormattedMessage().equals(probe) && event.getMarker() != null); }
            if (!seen) { close(); throw new IllegalStateException("log capture 無法觀察 quantumchamber 的 ERROR 與 marker"); }
            events.clear();
        }
        @Override public void append(LogEvent event) { if ("quantumchamber".equals(event.getLoggerName())) events.add(event.toImmutable()); }
        List<LogEvent> errors() { synchronized (events) { return events.stream().filter(event -> event.getLevel() == Level.ERROR).toList(); } }
        /** 原生存檔的 journal 寫出失敗 ERROR（T4 P2）。 */
        List<LogEvent> nativeSaveErrors() {
            return errors().stream().filter(event -> event.getMessage().getFormattedMessage().startsWith("原生存檔（autosave、/save-all 或停服）的 journal 寫出失敗")).toList();
        }
        /** 帶 {@code session=<sid>} 的 ERROR（收據不符）。 */
        List<LogEvent> sessionErrors(UUID sid) {
            return errors().stream().filter(event -> event.getMessage().getFormattedMessage().contains("session=" + sid)).toList();
        }
        @Override public void close() {
            context.getConfiguration().getRootLogger().removeAppender(getName()); context.updateLoggers(); stop();
        }
    }
}
