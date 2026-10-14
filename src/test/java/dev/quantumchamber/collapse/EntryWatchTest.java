package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.collapse.CollapseService.SourceSignal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * H7（spec v5.9 §7.1、§7.3、§11.2、§4 H7）：{@link EntryWatch} 的「來源艙還沒載入時，這次觸發不消耗」，不經 server。{@code forget} 之後下一次觀察是邊緣；
 * {@code trigger} 與 {@code level} 只有 {@code NOT_LOADED} 不消耗，其他原因（INVALID、LOW、DISABLED）與不符合參與者條件的人照樣消耗；沒有人觸發時不查詢來源艙。
 */
class EntryWatchTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final Predicate<UUID> EVERYONE = id -> true;
    static final Supplier<SourceSignal> NOT_QUERIED = () -> { throw new AssertionError("沒有人觸發時不應查詢來源艙"); };

    @Test void forgottenPlayersEnterAgainOnTheNextObservation() {
        var watch = new EntryWatch(false);
        var unobserved = new EntryWatch(false);
        unobserved.forget(List.of(A));
        var armed = new EntryWatch(true);
        assertAll(
                () -> assertEquals(List.of(), watch.observe(Set.of(A)), "執行期間才開始追蹤：第一次觀察只記下狀態"),
                () -> watch.forget(List.of(A)),
                () -> assertEquals(List.of(A), watch.observe(Set.of(A)), "forget 之後仍在艙內：下一次觀察是邊緣"),
                () -> assertEquals(List.of(), watch.observe(Set.of(A)), "之後回到邊緣觸發"),
                () -> watch.forget(List.of(B)),
                () -> assertEquals(List.of(), watch.observe(Set.of(A)), "forget 別人不影響 A"),
                () -> assertEquals(List.of(), unobserved.observe(Set.of(A)), "還沒觀察過時 forget 不做任何事"),
                () -> assertEquals(List.of(A), armed.observe(Set.of(A)), "開服 attach 的第一次觀察"),
                () -> armed.forget(List.of(A)),
                () -> assertEquals(List.of(A), armed.observe(Set.of(A)), "開服的第一次觀察被 forget 之後，下一次仍是觸發"));
    }

    @Test void onlyAnUnloadedSourceChamberKeepsTheTrigger() {
        var cases = new ArrayList<Executable>();
        for (var signal : SourceSignal.values()) {
            var watch = new EntryWatch(false);
            boolean kept = signal == SourceSignal.NOT_LOADED;
            cases.add(() -> {
                assertFalse(watch.trigger(Set.of(), EVERYONE, NOT_QUERIED), signal + "：第一次觀察，沒有人");
                assertEquals(signal == SourceSignal.OK, watch.trigger(Set.of(A), EVERYONE, () -> signal), signal + "：A 由艙外進入");
                assertEquals(kept, watch.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), signal + "：A 一直在艙內，來源艙之後 OK");
                assertFalse(watch.trigger(Set.of(A), EVERYONE, NOT_QUERIED), signal + "：之後回到邊緣觸發");
            });
        }
        assertAll(cases);
    }

    @Test void leavingWhileUnloadedDropsTheTriggerAndOthersStillConsumeIt() {
        var leaving = new EntryWatch(false);
        var busy = new EntryWatch(false);
        var startup = new EntryWatch(true);
        assertAll(
                () -> assertFalse(leaving.trigger(Set.of(), EVERYONE, NOT_QUERIED)),
                () -> assertFalse(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "來源艙還沒載入"),
                () -> assertFalse(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "仍沒有載入：每個 tick 再判斷"),
                () -> assertFalse(leaving.trigger(Set.of(), EVERYONE, NOT_QUERIED), "離開 interior：不算這次觸發"),
                () -> assertTrue(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), "再進入：觸發"),
                () -> assertFalse(busy.trigger(Set.of(), EVERYONE, NOT_QUERIED)),
                () -> assertFalse(busy.trigger(ordered(A, B), id -> id.equals(A), () -> SourceSignal.NOT_LOADED), "B 被其他 session 佔用，A 符合條件"),
                () -> assertFalse(busy.trigger(ordered(A, B), id -> id.equals(B), NOT_QUERIED), "B 不符合參與者條件時的進入已消耗：之後符合也不再觸發"),
                () -> assertFalse(startup.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "開服之後第一次觀察，來源艙還沒載入"),
                () -> assertTrue(startup.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), "載入之後，仍在艙內的 A 觸發"));
    }

    @Test void theFirstLevelEvaluationWaitsOnlyForAnUnloadedSourceChamber() {
        var cases = new ArrayList<Executable>();
        for (var signal : SourceSignal.values()) {
            var watch = new EntryWatch(false);
            cases.add(() -> {
                if (signal == SourceSignal.NOT_LOADED) {
                    assertNull(watch.level(Set.of(A), EVERYONE, () -> signal), "NOT_LOADED：不算評估過");
                    assertEquals(Boolean.TRUE, watch.level(Set.of(A), EVERYONE, () -> SourceSignal.OK), "下一個 tick 再以位準評估");
                } else {
                    assertEquals(signal == SourceSignal.OK, watch.level(Set.of(A), EVERYONE, () -> signal), signal + "：以位準評估");
                }
                assertFalse(watch.trigger(Set.of(A), EVERYONE, NOT_QUERIED), signal + "：評估過之後只看邊緣（已 prime）");
            });
        }
        var nobody = new EntryWatch(false);
        var busy = new EntryWatch(false);
        cases.add(() -> {
            assertEquals(Boolean.FALSE, nobody.level(Set.of(), EVERYONE, NOT_QUERIED), "沒有人在艙內：評估過、不查詢來源艙");
            assertTrue(nobody.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), "之後由艙外進入");
            assertEquals(Boolean.FALSE, busy.level(Set.of(B), id -> false, NOT_QUERIED), "只有不符合參與者條件的人：評估過、不查詢來源艙");
            assertFalse(busy.trigger(Set.of(B), EVERYONE, NOT_QUERIED), "已 prime");
        });
        assertAll(cases);
    }

    private static Set<UUID> ordered(UUID... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }
}
