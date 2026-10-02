package dev.quantumchamber.collapse;

import java.util.Objects;

/**
 * {@link DestinationResolver#resolve} 的結果（spec §8，F4a）：目的地已確定、依 spec 不得 SEALED 的等待，或進 SEALED 的原因。
 * 只供塌縮流程使用（{@link CollapseService}）。
 */
public sealed interface ResolveResult permits ResolveResult.Resolved, ResolveResult.Wait, ResolveResult.Sealed {
    /**
     * 目的地已確定（spec §7.3）：EXISTING 重驗通過；NEW 的 allocation entry、catalog record、ACTIVE world 與 discovery record 都已確認。
     * 不另存，重啟之後由 RESOLVE 以 idempotent 的方式重新確認。
     */
    record Resolved() implements ResolveResult { }

    /**
     * 依 spec 不得 SEALED 的等待，下一個 tick 再試：catalog 有未確認的寫入（§6.2）、EXISTING 重驗時 discovery 有未確認的寫入（§6 收斂共通
     * 第 2 點、F2c 裁示 3），或 lifecycle 沒有 ready 的 context。{@code reason} 只用於 log。
     */
    record Wait(String reason) implements ResolveResult {
        public Wait { Objects.requireNonNull(reason, "reason"); }
    }

    /** 進 SEALED（spec §8、§11.2）：原因與 root cause。RESOLVE 不會產生 {@link SealedReason#TRANSFER_RECOVERY_REQUIRED}。 */
    record Sealed(SealedReason reason, RuntimeException failure) implements ResolveResult {
        public Sealed {
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(failure, "failure");
            if (reason == SealedReason.TRANSFER_RECOVERY_REQUIRED) throw new IllegalArgumentException("RESOLVE 不會產生 TRANSFER_RECOVERY_REQUIRED");
        }
    }

    static ResolveResult resolved() { return new Resolved(); }

    static ResolveResult waiting(String reason) { return new Wait(reason); }

    static ResolveResult sealed(SealedReason reason, RuntimeException failure) { return new Sealed(reason, failure); }
}
