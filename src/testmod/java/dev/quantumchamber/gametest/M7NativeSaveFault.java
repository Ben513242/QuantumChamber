package dev.quantumchamber.gametest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 僅 testmod（M7 T4 P2，使用者 2026-11-17 裁示）：journal 正式寫出的 atomic move 失敗。{@code SessionJournalWindowFaultMixin} 在
 * {@code SessionRecoveryState.save} 內 {@code SessionJournalStore.writePrepared} 的呼叫前，把目標檔交給 {@link #writeTarget}。武裝時改指向一個既存目錄，
 * 讓真正的 {@code writePrepared} 在 {@code Files.move} 失敗：Windows 是 {@code AccessDeniedException}（與防毒或索引鎖檔時相同的簽章），Linux 是
 * {@code FileSystemException}（EISDIR）。結果是 {@code WriteFailure(possiblyMoved=true)}，正式 journal 檔完全不動。
 * <p>只對武裝時指定的 journal 路徑生效，其他路徑原樣傳回；沒有武裝時不做任何事，也不丟出例外。原生存檔與 checked flush 都經過同一個 {@code save}，
 * 所以武裝期間兩條路徑都會失敗（與真實的鎖檔相同）。
 */
public final class M7NativeSaveFault {
    private static volatile Armed armed;

    private M7NativeSaveFault() {}

    private record Armed(Path journal, Path blocked, AtomicInteger hits) {}

    /** mixin 呼叫：武裝時，目標是武裝的 journal 就改指向既存目錄並計數；否則原樣傳回。 */
    public static Path writeTarget(Path target) {
        var current = armed;
        if (current == null || !target.toAbsolutePath().normalize().equals(current.journal())) return target;
        current.hits().incrementAndGet();
        return current.blocked();
    }

    /** 武裝 {@code journal} 的寫出故障；同時只能有一個。回傳的 handle 關閉時解除並刪除暫存目錄。 */
    static synchronized Handle arm(Path journal) throws IOException {
        if (armed != null) throw new IllegalStateException("M7_NATIVE_SAVE_FAULT_BUSY：已有武裝中的 journal 寫出故障");
        var scratch = Files.createTempDirectory("m7-t4p2-native-fault");
        var blocked = Files.createDirectory(scratch.resolve("blocked"));
        var handle = new Handle(new Armed(journal.toAbsolutePath().normalize(), blocked, new AtomicInteger()), scratch);
        armed = handle.armed;
        return handle;
    }

    /** 一次武裝；{@link #hits} 是被導向的寫出次數。 */
    static final class Handle implements AutoCloseable {
        private final Armed armed;
        private final Path scratch;
        private boolean disarmed;

        private Handle(Armed armed, Path scratch) { this.armed = armed; this.scratch = scratch; }

        int hits() { return armed.hits().get(); }

        /** 被導向的目錄（失敗訊息裡的目標路徑）。 */
        Path blocked() { return armed.blocked(); }

        /** 之後的寫出照常（可重複呼叫）。 */
        void disarm() {
            synchronized (M7NativeSaveFault.class) {
                if (M7NativeSaveFault.armed == armed) M7NativeSaveFault.armed = null;
                disarmed = true;
            }
        }

        boolean disarmed() { return disarmed; }

        /** 解除並刪除暫存目錄（writePrepared 失敗時已刪掉自己的暫存檔，這裡只清目錄）。 */
        @Override public void close() {
            disarm();
            try (var walk = Files.walk(scratch)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) {
                // 系統暫存目錄的殘留不影響判定。
            }
        }
    }
}
