package dev.quantumchamber.collapse;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * §7.1 的邊緣觸發：原參與者由艙外進入艙內。只比較相鄰兩次觀察，不是位準觸發。第一次觀察時，開服 attach 當下就在 journal 的 session
 * （{@code armedAtStartup}）把已在艙內的人當成觸發（「開服之後第一次觀察到原參與者在艙內」）；其他 session 只記下目前的狀態。
 * <p>H7 先由 {@link CollapseService} 的巢狀類別純搬移出來（{@code c2e01d6}，行為不變；交接 F6-2-19：讓 {@code CollapseService} 留在約 1600 行之內），再加上
 * {@link #forget}、{@link #trigger} 與 §7.3 閘門的第一次評估（H7 的 {@code level}）。F6-2d-2 Step 0（H7 quality Minor 1、spec Minor 1）：閘門「是否已評估過」由
 * {@code CollapseService.Track.gateEvaluated} 移進這裡，對外只有 {@link #gate}（行為不變），WAITING 的接線因此可以不經 server 測（{@code EntryWatchTest}）。
 * <p><b>來源艙還沒載入時，這次觸發不消耗</b>（H7，spec v5.9 §7.1、§7.3、§11.2、§4 H7；{@link #trigger}、{@link #gate}）：觸發的人已符合其他參與者條件、但來源艙還沒載入
 * （{@link SourceSignal#NOT_LOADED}）時，不記為已觀察，之後每個 tick 再判斷，直到來源艙載入或他離開 interior。理由：重啟之後玩家在原艙內登入時，原艙不在 spawn chunks
 * 的話，chunk 要在 JOIN 之後幾個 tick 才由玩家（或 COLLAPSING 的來源）ticket 載入；在這時消耗觸發，開服之後的第一次觀察（或 JOIN 在艙內的邊緣）就會落空，玩家要離開再
 * 進入，而且沒有任何訊息。其他條件不成立時（結構不合法、未供電、Origin 停用；不符合參與者條件的人；呼叫端的退避期）照樣消耗這次觸發，維持邊緣觸發的語意。只查詢、
 * 不寫入、不記 log（來源艙未載入期間每個 tick 都會判斷）。
 */
final class EntryWatch {
    private final boolean armedAtStartup;
    private Set<UUID> inside;
    /**
     * §7.3 閘門已在本 server instance 評估過（{@link #gate}；F6-2d-2 Step 0 由 {@code Track.gateEvaluated} 移進來）。與 {@link #inside} 同一個生命週期：
     * {@code Track.resetGate} 丟棄整個 {@code EntryWatch}，兩者一起重設，與移進來之前相同。
     */
    private boolean gateEvaluated;

    EntryWatch(boolean armedAtStartup) { this.armedAtStartup = armedAtStartup; }

    /** 回傳這次新進入艙內的人（依參與者順序）。 */
    List<UUID> observe(Set<UUID> insideNow) {
        Objects.requireNonNull(insideNow, "insideNow");
        List<UUID> entered = inside == null
                ? (armedAtStartup ? List.copyOf(insideNow) : List.of())
                : insideNow.stream().filter(id -> !inside.contains(id)).toList();
        inside = Set.copyOf(insideNow);
        return entered;
    }

    /** 只記下目前的狀態，不觸發（閘門第一次以位準評估之後；離開 PREPARE 時重新記下快照）。 */
    void prime(Set<UUID> insideNow) { inside = Set.copyOf(insideNow); }

    /** 把 {@code players} 移出上一次的觀察：下一次觀察時，仍在艙內的人又是新進入（H7）。還沒有觀察過時不做任何事。 */
    void forget(Collection<UUID> players) {
        if (inside == null) return;
        var kept = new HashSet<>(inside);
        kept.removeAll(players);
        inside = Set.copyOf(kept);
    }

    /**
     * 觀察一次，回傳這個 tick 是否有 §7.1 的觸發成立（DORMANT 的續行、§11.2 SEALED 的重試、§7.3 閘門評估過之後的邊緣）：新進入艙內、而且 {@code ready}（沒有被其他
     * session 佔用）的人觸發，{@code source}（只在有這樣的人時才查詢）是 {@code OK} 才成立；呼叫端之後再看退避。{@code NOT_LOADED} 時把這些人移出觀察（{@link #forget}），
     * 不消耗這次觸發；其他原因照樣消耗（見類別說明）。
     */
    boolean trigger(Set<UUID> insideNow, Predicate<UUID> ready, Supplier<SourceSignal> source) {
        var triggering = observe(insideNow).stream().filter(ready).toList();
        if (triggering.isEmpty()) return false;
        var signal = source.get();
        if (signal == SourceSignal.NOT_LOADED) forget(triggering);
        return signal == SourceSignal.OK;
    }

    /**
     * §7.3 PREPARE 的閘門（{@code CollapsePhase.Gate#edge}：上一個 tick 不在 PREPARE）：這個 tick 是否成立。本 server instance 第一次評估時以位準評估：在艙內、{@code ready}
     * 的人存在而且 {@code source} 是 {@code OK} 才成立，之後只看邊緣（{@link #prime}，再來都是 {@link #trigger}）。第一次評估時有這樣的人、但來源艙還沒載入
     * （{@code NOT_LOADED}）時回 false，而且<b>不算評估過</b>、不 prime：下一個 tick 再以位準評估（H7，spec v5.9 §7.3）。沒有這樣的人時不查詢 {@code source}（評估過、
     * 不成立）。F6-2d-2 Step 0 由 H7 的 {@code level}（回傳 nullable 的 {@code Boolean}）與 {@code PrepareGate.edge} 的 {@code gateEvaluated} 合成，行為不變。
     */
    boolean gate(Set<UUID> insideNow, Predicate<UUID> ready, Supplier<SourceSignal> source) {
        if (gateEvaluated) return trigger(insideNow, ready, source);
        var signal = insideNow.stream().anyMatch(ready) ? source.get() : null;
        if (signal == SourceSignal.NOT_LOADED) return false;
        prime(insideNow);
        gateEvaluated = true;
        return signal == SourceSignal.OK;
    }
}
