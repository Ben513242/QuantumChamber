package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/**
 * F4a fix round 1（M-1）：{@link UniverseLifecycleService#materializeAllocated} 的失敗紀錄（{@link UniverseLifecycleService#attemptMaterialize}）。
 * 停服驗證（{@link UniverseLifecycleService#verifyStopped}）依賴這些紀錄：backend 留下 MATERIALIZE_FAILED 的 runtime owner 時，必須有同一個
 * descriptor 的 {@code FAILED_ROLLED_BACK} 紀錄才能通過；例外是未證明安全的失敗，記下之後停服驗證必須拒絕。GameTest 的物化失敗注入在 backend 開頭
 * 回傳、沒有留下 owner，所以這兩點由這裡鎖住。
 */
class UniverseLifecycleMaterializeFailureTest {
    private static final UUID UNIVERSE = UUID.fromString("80000000-0000-4000-8000-0000000000f1");
    private final UniverseWorldDescriptor descriptor =
            new UniverseRegistryState().allocateOverworld(UNIVERSE, 0).definition().worlds().get(DimensionRole.OVERWORLD);
    private final Set<UniverseWorldDescriptor> failed = new HashSet<>();
    private final List<UniverseLifecycleService.MaterializeFailure> failures = new ArrayList<>();

    @Test void rolledBackRuntimeFailureIsRecordedSoTheFailedOwnerVerifiesAtStop() {
        var outcome = attempt(() -> new MaterializeResult(MaterializeResult.Status.FAILED_ROLLED_BACK, null));
        var owner = List.of(new UniverseRuntimeRegistry.OwnedWorld<Object>(descriptor, DynamicWorldRuntimeState.MATERIALIZE_FAILED, null));
        assertAll(
                () -> assertEquals(UniverseLifecycleService.MaterializeOutcome.Status.FAILED, outcome.status()),
                () -> assertEquals(Optional.of(MaterializeResult.Status.FAILED_ROLLED_BACK), outcome.backendStatus()),
                () -> assertEquals(List.of(MaterializeResult.Status.FAILED_ROLLED_BACK), statuses(), "記入 materialize failures"),
                () -> assertEquals(Set.of(descriptor), failed, "記入本 instance 失敗過的 descriptor"),
                () -> assertDoesNotThrow(() -> stop(owner), "有 rollback 紀錄：失敗的 owner 可以通過停服驗證"),
                () -> assertThrows(IllegalStateException.class, () -> UniverseLifecycleService.verifyStopped(owner, List.of(), Map.of(),
                        key -> null, candidate -> true, null, List.of()), "對照：沒有紀錄時同一個 owner 不能通過"));
    }

    @Test void unhealthyRuntimeFailureIsRecordedAndTheStopStaysUnverified() {
        attempt(() -> new MaterializeResult(MaterializeResult.Status.FAILED_UNHEALTHY, null));
        assertAll(
                () -> assertEquals(List.of(MaterializeResult.Status.FAILED_UNHEALTHY), statuses()),
                () -> assertThrows(IllegalStateException.class, () -> stop(List.of()), "沒有安全 close 證據的失敗：停服驗證拒絕"));
    }

    @Test void exceptionIsRecordedAsAnUnprovenFailureSoTheStopStaysUnverified() {
        var thrown = new IllegalStateException("測試：backend 丟出例外");
        var outcome = attempt(() -> { throw thrown; });
        assertAll(
                () -> assertEquals(UniverseLifecycleService.MaterializeOutcome.Status.FAILED, outcome.status()),
                () -> assertEquals(Optional.empty(), outcome.backendStatus(), "例外沒有 backend status"),
                () -> assertEquals(1, failures.size(), "記入 materialize failures"),
                () -> assertNull(failures.isEmpty() ? MaterializeResult.Status.MATERIALIZED : failures.getFirst().status(), "沒有 status：未證明安全"),
                () -> assertSame(thrown, failures.isEmpty() ? null : failures.getFirst().cause()),
                () -> assertEquals(Set.of(descriptor), failed),
                () -> assertThrows(IllegalStateException.class, () -> stop(List.of()), "停服驗證拒絕（STOPPED_UNVERIFIED），不會默默通過"));
    }

    @Test void errorIsRecordedThenRethrown() {
        var fatal = new AssertionError("測試：backend 丟出 Error");
        var thrown = assertThrows(AssertionError.class, () -> attempt(() -> { throw fatal; }));
        assertAll(
                () -> assertSame(fatal, thrown),
                () -> assertEquals(1, failures.size(), "Error 也記入之後才丟出"),
                () -> assertEquals(Set.of(descriptor), failed),
                () -> assertThrows(IllegalStateException.class, () -> stop(List.of())));
    }

    @Test void rejectedIsRefusedAgainButNotRecordedBecauseItHasNoOwner() {
        int[] calls = {0};
        Supplier<MaterializeResult> rejected = () -> { calls[0]++; return new MaterializeResult(MaterializeResult.Status.REJECTED, null); };
        var first = attempt(rejected);
        var second = attempt(rejected);
        assertAll(
                () -> assertEquals(Optional.of(MaterializeResult.Status.REJECTED), first.backendStatus()),
                () -> assertEquals(List.of(), failures, "REJECTED 在建立 runtime owner 之前就被拒絕：不記入"),
                () -> assertDoesNotThrow(() -> stop(List.of()), "沒有 owner、沒有紀錄：停服驗證通過"),
                () -> assertEquals(UniverseLifecycleService.MaterializeOutcome.Status.FAILED_BEFORE, second.status(), "同一個 instance 內直接拒絕"),
                () -> assertEquals(1, calls[0], "第二次不呼叫 backend"));
    }

    @Test void aDescriptorThatFailedBeforeIsRefusedWithoutCallingTheBackend() {
        attempt(() -> new MaterializeResult(MaterializeResult.Status.FAILED_ROLLED_BACK, null));
        var again = attempt(() -> { throw new AssertionError("FAILED_BEFORE 不得呼叫 backend"); });
        assertAll(
                () -> assertEquals(UniverseLifecycleService.MaterializeOutcome.Status.FAILED_BEFORE, again.status()),
                () -> assertEquals(1, failures.size(), "沒有重複記入"));
    }

    private UniverseLifecycleService.MaterializeOutcome attempt(Supplier<MaterializeResult> materializer) {
        return UniverseLifecycleService.attemptMaterialize(descriptor, failed, failures, materializer);
    }

    private List<MaterializeResult.Status> statuses() {
        return failures.stream().map(UniverseLifecycleService.MaterializeFailure::status).toList();
    }

    private void stop(List<UniverseRuntimeRegistry.OwnedWorld<Object>> owners) {
        UniverseLifecycleService.verifyStopped(owners, List.of(), Map.of(), key -> null, candidate -> true, null, failures);
    }
}
