package dev.quantumchamber.persistence;

import java.nio.file.FileSystemException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * 失敗 log 的共用文字：顯示用的 {@link #describe} 與 WARN 去重用的 {@link #dedupeKey}。兩者都只輸出例外 class 與 message，
 * 不輸出 record、candidate bytes 或 entropy；cause 鏈都以 identity 去重走訪，多節點的 cause 環也會在有限步內停止。
 */
public final class FailureText {
    private FailureText() {}

    /** 顯示用：頂層與 root cause 的 class 與 message（含檔案路徑等細節）。 */
    public static String describe(Throwable failure) {
        var text = failure.getClass().getName() + ": " + failure.getMessage();
        var seen = identitySet();
        var root = failure;
        seen.add(root);
        while (root.getCause() != null && seen.add(root.getCause())) root = root.getCause();
        return root == failure ? text : text + " <- " + root.getClass().getName() + ": " + root.getMessage();
    }

    /**
     * WARN 去重用：cause 鏈每個節點的 class 與 message，以 {@code " <- "} 串接。message 不穩定的兩種節點改用穩定的文字：
     * 檔案系統例外只取 reason（message 帶檔案路徑，例如每次都不同的暫存檔名）；message 只是 cause 的 {@code toString()}
     * （JDK 以單一 cause 包裝時的預設）記為 {@code <cause>}，由下一個節點表達。
     * <p><b>鍵不含檔案身分</b>：檔案系統例外的檔案與另一個檔案（{@code getOtherFile()}）都不進鍵，reason 為 null（Windows 的 AccessDeniedException 常見）時記為
     * {@code null}。所以不同檔案、同一種失敗會共用同一把鍵、只記一次 WARN；要知道是哪個檔案，看 WARN 內文的 {@link #describe}。
     */
    public static String dedupeKey(Throwable failure) {
        var key = new StringBuilder();
        var seen = identitySet();
        for (var node = failure; node != null && seen.add(node); node = node.getCause()) {
            if (!key.isEmpty()) key.append(" <- ");
            key.append(node.getClass().getName()).append(": ").append(stableMessage(node));
        }
        return key.toString();
    }

    private static String stableMessage(Throwable node) {
        if (node instanceof FileSystemException fileSystem) return fileSystem.getReason();
        var cause = node.getCause();
        if (cause != null && Objects.equals(node.getMessage(), cause.toString())) return "<cause>";
        return node.getMessage();
    }

    private static Set<Throwable> identitySet() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }
}
