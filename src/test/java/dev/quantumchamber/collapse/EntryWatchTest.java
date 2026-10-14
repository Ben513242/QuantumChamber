package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

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
 * {@code trigger} 與 {@code gate}（F6-2d-2 Step 0 起取代 {@code level}）只有 {@code NOT_LOADED} 不消耗，其他原因（INVALID、LOW、DISABLED）與不符合參與者條件的人照樣消耗；
 * 沒有人觸發時不查詢來源艙。
 * <p>F6-2d-2 Step 0（H7 quality Minor 5）：同一個 {@link EntryWatch} 的有狀態動作改成循序斷言（前一步失敗時就停下，不會出現連鎖的誤導訊息）；{@code assertAll} 只用在
 * 彼此獨立的 case（每個 case 有自己的 {@link EntryWatch}）。§7.1 的邊緣觸發本身（{@link #entryWatchTriggersOnlyOnEntryEdges}）由 {@code CollapseServiceTest} 搬來。
 */
class EntryWatchTest {
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final Predicate<UUID> EVERYONE = id -> true;
    static final Supplier<SourceSignal> NOT_QUERIED = () -> { throw new AssertionError("沒有人觸發時不應查詢來源艙"); };

    /**
     * §7.1 的邊緣觸發（F6-2d-2 Step 0 由 {@code CollapseServiceTest} 搬來，改成循序斷言）：只比較相鄰兩次觀察；開服 attach 的 session 第一次觀察到在艙內的人也算觸發；
     * 閘門以位準評估過（{@code prime}）之後只看邊緣。
     */
    @Test void entryWatchTriggersOnlyOnEntryEdges() {
        var fresh = new EntryWatch(false);
        assertEquals(List.of(), fresh.observe(Set.of(A)), "執行期間才開始追蹤：第一次觀察只記下狀態，不觸發");
        assertEquals(List.of(), fresh.observe(Set.of(A)), "一直在艙內：不是每個 tick 都觸發");
        assertEquals(List.of(B), fresh.observe(ordered(A, B)), "另一位由艙外進入：觸發");
        assertEquals(List.of(), fresh.observe(Set.of(B)), "離開不觸發");
        assertEquals(List.of(A), fresh.observe(ordered(B, A)), "離開之後再進入：觸發");

        var startup = new EntryWatch(true);
        assertEquals(List.of(A), startup.observe(Set.of(A)), "開服 attach 之後第一次觀察到原參與者在艙內：觸發");
        assertEquals(List.of(), startup.observe(Set.of(A)), "之後回到邊緣觸發");

        var primed = new EntryWatch(false);
        primed.prime(Set.of(A));
        assertEquals(List.of(), primed.observe(Set.of(A)), "閘門以位準評估過之後只看邊緣");
        assertEquals(List.of(B), primed.observe(ordered(A, B)), "prime 之後新進入的人觸發");
    }

    @Test void forgottenPlayersEnterAgainOnTheNextObservation() {
        var watch = new EntryWatch(false);
        assertEquals(List.of(), watch.observe(Set.of(A)), "執行期間才開始追蹤：第一次觀察只記下狀態");
        watch.forget(List.of(A));
        assertEquals(List.of(A), watch.observe(Set.of(A)), "forget 之後仍在艙內：下一次觀察是邊緣");
        assertEquals(List.of(), watch.observe(Set.of(A)), "之後回到邊緣觸發");
        watch.forget(List.of(B));
        assertEquals(List.of(), watch.observe(Set.of(A)), "forget 別人不影響 A");

        var unobserved = new EntryWatch(false);
        unobserved.forget(List.of(A));
        assertEquals(List.of(), unobserved.observe(Set.of(A)), "還沒觀察過時 forget 不做任何事");

        var armed = new EntryWatch(true);
        assertEquals(List.of(A), armed.observe(Set.of(A)), "開服 attach 的第一次觀察");
        armed.forget(List.of(A));
        assertEquals(List.of(A), armed.observe(Set.of(A)), "開服的第一次觀察被 forget 之後，下一次仍是觸發");
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
        assertFalse(leaving.trigger(Set.of(), EVERYONE, NOT_QUERIED), "執行期間才開始追蹤：第一次觀察，沒有人在艙內");
        assertFalse(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "來源艙還沒載入");
        assertFalse(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "仍沒有載入：每個 tick 再判斷");
        assertFalse(leaving.trigger(Set.of(), EVERYONE, NOT_QUERIED), "離開 interior：不算這次觸發");
        assertTrue(leaving.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), "再進入：觸發");

        var busy = new EntryWatch(false);
        assertFalse(busy.trigger(Set.of(), EVERYONE, NOT_QUERIED), "執行期間才開始追蹤：第一次觀察，沒有人在艙內");
        assertFalse(busy.trigger(ordered(A, B), id -> id.equals(A), () -> SourceSignal.NOT_LOADED), "B 被其他 session 佔用，A 符合條件");
        assertFalse(busy.trigger(ordered(A, B), id -> id.equals(B), NOT_QUERIED), "B 不符合參與者條件時的進入已消耗：之後符合也不再觸發");

        var startup = new EntryWatch(true);
        assertFalse(startup.trigger(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "開服之後第一次觀察，來源艙還沒載入");
        assertTrue(startup.trigger(Set.of(A), EVERYONE, () -> SourceSignal.OK), "載入之後，仍在艙內的 A 觸發");
    }

    /**
     * §7.3 閘門的第一次評估（{@link EntryWatch#gate}；F6-2d-2 Step 0 由 H7 的 {@code level} 改測 {@code gate}）：以位準評估，只有 NOT_LOADED 不算評估過（下一個 tick 再以位準
     * 評估）；評估過之後只看邊緣。沒有人、或只有不符合參與者條件的人時評估過、不查詢來源艙。
     */
    @Test void theFirstGateEvaluationWaitsOnlyForAnUnloadedSourceChamber() {
        var cases = new ArrayList<Executable>();
        for (var signal : SourceSignal.values()) {
            var watch = new EntryWatch(false);
            cases.add(() -> {
                if (signal == SourceSignal.NOT_LOADED) {
                    assertFalse(watch.gate(Set.of(A), EVERYONE, () -> signal), "NOT_LOADED：不成立");
                    assertTrue(watch.gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), "NOT_LOADED 不算評估過：下一個 tick 再以位準評估（A 沒有移動）");
                } else {
                    assertEquals(signal == SourceSignal.OK, watch.gate(Set.of(A), EVERYONE, () -> signal), signal + "：以位準評估");
                }
                assertFalse(watch.gate(Set.of(A), EVERYONE, NOT_QUERIED), signal + "：評估過之後只看邊緣（已 prime，A 一直在艙內）");
                assertFalse(watch.gate(Set.of(), EVERYONE, NOT_QUERIED), signal + "：A 離開");
                assertTrue(watch.gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), signal + "：A 由艙外再進入是邊緣");
            });
        }
        var nobody = new EntryWatch(false);
        cases.add(() -> {
            assertFalse(nobody.gate(Set.of(), EVERYONE, NOT_QUERIED), "沒有人在艙內：評估過、不查詢來源艙");
            assertTrue(nobody.gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), "之後由艙外進入是邊緣");
        });
        var busy = new EntryWatch(false);
        cases.add(() -> {
            assertFalse(busy.gate(Set.of(B), id -> false, NOT_QUERIED), "只有不符合參與者條件的人：評估過、不查詢來源艙");
            assertFalse(busy.gate(Set.of(B), EVERYONE, NOT_QUERIED), "已 prime：B 之後符合條件也不是邊緣");
        });
        assertAll(cases);
    }

    /**
     * WAITING 的接線（F6-2d-2 Step 0，H7 spec Minor 1、quality Minor 1：原本「第一次評估遇到 NOT_LOADED 時不設 gateEvaluated」分在 {@code EntryWatch.level} 與
     * {@code PrepareGate.edge} 兩處，沒有確定性的回歸）。{@code PrepareGate.edge} 只呼叫 {@code track.entry(false).gate(...)}：開服之後第一次評估時參與者已在艙內、來源艙
     * 還沒載入（{@code waitinglate} 的情況），之後每個 tick 仍以位準評估，載入的那個 tick 就成立，參與者不必離開再進入；{@code resetGate}（提交、進入 SEALED、重試）之後
     * 下一段又從第一次評估開始。
     */
    @Test void theWaitingGateEvaluatesOnTheLevelOnceTheSourceChamberLoads() {
        var track = new CollapseService.Track();
        assertFalse(track.entry(false).gate(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "開服之後第一次評估：來源艙還沒載入");
        assertFalse(track.entry(false).gate(Set.of(A), EVERYONE, () -> SourceSignal.NOT_LOADED), "仍沒有載入：下一個 tick 再以位準評估");
        assertTrue(track.entry(false).gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), "載入的那個 tick：參與者沒有移動就成立");
        assertFalse(track.entry(false).gate(Set.of(A), EVERYONE, NOT_QUERIED), "評估過之後只看邊緣");
        track.resetGate();
        assertTrue(track.entry(false).gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), "resetGate 之後是新的一段：第一次評估又以位準評估");
    }

    /**
     * PREPARE 中途閘門失效之後的 {@code prime}（F6-2d-2，F6-2d-2 Step 0 quality 給本體的建議：PREPARE 中重啟的窗口之前先補）。本 test 直接呼叫 {@code prime}，只鎖兩件事：
     * {@code prime} 不重設「已評估過」（之後不再以位準評估），而且 {@code Track.entry(false)} 每次交出同一個 {@link EntryWatch}（prime 記下的快照在下一次 {@code gate} 時還在）。
     * 第一次評估以位準進 PREPARE → prime（A、B 都在艙內；B 是 PREPARE 期間進艙的）→ 兩人都沒有移動時不是邊緣、不查詢來源艙 → B 離開再進入才是邊緣。
     * {@code CollapseService.leavePrepare} 確實以目前在艙內的原參與者呼叫 {@code prime} 那一行不在本 test 的範圍，由 GameTest P-POWER 的 {@code noReentryWithoutAnEntryEdge}
     * 鎖住（F6-2d-2 B1，A 段 quality Minor 5：原本的說明寫成本 test 經 {@code Track} 的接線驗了 {@code leavePrepare}）。不應查詢來源艙的兩步各以帶這一步說明的 supplier
     * （{@link #notQueried}）表達，查詢時的失敗訊息看得出是哪一步。
     */
    @Test void afterThePrimeOnLeavingPrepareTheGateWaitsForAnEdge() {
        var track = new CollapseService.Track();
        assertTrue(track.entry(false).gate(Set.of(A), EVERYONE, () -> SourceSignal.OK), "第一次評估：A 在艙內，以位準進 PREPARE");
        track.entry(false).prime(Set.of(A, B));
        assertFalse(track.entry(false).gate(Set.of(A, B), EVERYONE, notQueried("prime 之後：A 與 PREPARE 期間進艙的 B 都沒有移動")),
                "prime 之後：A 與 PREPARE 期間進艙的 B 都沒有移動，不是邊緣");
        assertFalse(track.entry(false).gate(Set.of(A), EVERYONE, notQueried("B 離開")), "B 離開");
        assertTrue(track.entry(false).gate(Set.of(A, B), EVERYONE, () -> SourceSignal.OK), "B 由艙外再進入是邊緣");
    }

    /** 同 {@link #NOT_QUERIED}，失敗訊息另帶 {@code step}（查詢時看得出是哪一步；F6-2d-2 B1，A 段 quality Minor 5）。 */
    private static Supplier<SourceSignal> notQueried(String step) {
        return () -> { throw new AssertionError(step + "：沒有人觸發時不應查詢來源艙"); };
    }

    private static Set<UUID> ordered(UUID... ids) {
        return new LinkedHashSet<>(List.of(ids));
    }
}
