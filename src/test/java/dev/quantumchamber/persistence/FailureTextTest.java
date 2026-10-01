package dev.quantumchamber.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FailureTextTest {
    private static final String TARGET = "C:\\world\\data\\quantumchamber_sessions.dat";

    /** H3 fix round 1 I-1：createTempFile／ATOMIC_MOVE 失敗的 message 帶隨機暫存檔名；去重鍵不得因此每次都不同。 */
    @Test void dedupeKeyIgnoresTemporaryFilePathsOfFileSystemExceptions() {
        var first = journalFailure(new AccessDeniedException(TARGET + ".4401.tmp", TARGET, "拒絕存取"));
        var second = journalFailure(new AccessDeniedException(TARGET + ".9177.tmp", TARGET, "拒絕存取"));
        assertNotEquals(FailureText.describe(first), FailureText.describe(second), "fixture：顯示文字保留各自的暫存檔路徑");
        assertEquals(FailureText.dedupeKey(first), FailureText.dedupeKey(second));
        assertEquals("java.io.UncheckedIOException: journal checked 落盤失敗 <- java.nio.file.AccessDeniedException: 拒絕存取",
                FailureText.dedupeKey(first));
    }

    /** JDK 以單一 cause 包裝時，外層 message 就是 cause.toString()，同樣帶路徑；改由下一個節點表達。 */
    @Test void dedupeKeyDoesNotRepeatAMessageThatIsJustItsCause() {
        var first = new UncheckedIOException(new AccessDeniedException(TARGET + ".1.tmp", TARGET, "拒絕存取"));
        var second = new UncheckedIOException(new AccessDeniedException(TARGET + ".2.tmp", TARGET, "拒絕存取"));
        assertEquals(FailureText.dedupeKey(first), FailureText.dedupeKey(second));
        assertEquals("java.io.UncheckedIOException: <cause> <- java.nio.file.AccessDeniedException: 拒絕存取", FailureText.dedupeKey(first));
    }

    /** 不同原因必須仍是不同的鍵：reason、例外 class、一般例外的 message、cause 鏈的任一節點。 */
    @Test void dedupeKeyKeepsDifferentCausesApart() {
        var denied = FailureText.dedupeKey(journalFailure(new AccessDeniedException(TARGET + ".1.tmp", TARGET, "拒絕存取")));
        assertNotEquals(denied, FailureText.dedupeKey(journalFailure(new AccessDeniedException(TARGET + ".1.tmp", TARGET, "磁碟已滿"))));
        assertNotEquals(denied, FailureText.dedupeKey(journalFailure(new FileSystemException(TARGET + ".1.tmp", TARGET, "拒絕存取"))));
        assertNotEquals(FailureText.dedupeKey(journalFailure(new IOException("journal 正式檔 exact readback 不符"))),
                FailureText.dedupeKey(journalFailure(new IOException("journal strict readback 失敗"))));
        var middle = new IOException("journal strict readback 失敗", new IllegalArgumentException("schema 3 payload 欄位缺失"));
        var other = new IOException("journal strict readback 失敗", new IllegalArgumentException("session UUID 重複"));
        assertNotEquals(FailureText.dedupeKey(journalFailure(middle)), FailureText.dedupeKey(journalFailure(other)), "root 以外的節點也要比對");
        assertEquals(FailureText.dedupeKey(journalFailure(new IOException("同一原因"))), FailureText.dedupeKey(journalFailure(new IOException("同一原因"))));
    }

    /** M4 quality Nit 3：兩節點 cause 環在有限步內結束，輸出逐字固定。 */
    @Test void multiNodeCauseCycleTerminatesWithExactText() {
        var first = new IllegalStateException("cause 環 A"); var second = new IllegalStateException("cause 環 B");
        first.initCause(second); second.initCause(first);
        var text = "java.lang.IllegalStateException: cause 環 A <- java.lang.IllegalStateException: cause 環 B";
        assertEquals(text, assertTimeoutPreemptively(Duration.ofSeconds(10), () -> FailureText.describe(first)));
        assertEquals(text, assertTimeoutPreemptively(Duration.ofSeconds(10), () -> FailureText.dedupeKey(first)));
    }

    @Test void describeShowsTopAndRootOnly() {
        var root = new IOException("磁碟已滿");
        assertEquals("java.lang.IllegalStateException: 單一失敗", FailureText.describe(new IllegalStateException("單一失敗")));
        assertEquals("java.io.UncheckedIOException: journal checked 落盤失敗 <- java.io.IOException: 磁碟已滿",
                FailureText.describe(journalFailure(new IOException("中間層", root))));
    }

    private static UncheckedIOException journalFailure(IOException cause) {
        return new UncheckedIOException("journal checked 落盤失敗", cause);
    }
}
