package dev.quantumchamber.collapse;

import dev.quantumchamber.collapse.CollapseService.SourceSignal;
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
 * <p>H7 由 {@link CollapseService} 的巢狀類別搬出（純搬移，行為不變；交接 F6-2-19：讓 {@code CollapseService} 留在約 1600 行之內）。
 * <p><b>來源艙還沒載入時，這次觸發不消耗</b>（H7，spec v5.9 §7.1、§7.3、§11.2、§4 H7；{@link #trigger}、{@link #level}）：觸發的人已符合其他參與者條件、但來源艙還沒載入
 * （{@link SourceSignal#NOT_LOADED}）時，不記為已觀察，之後每個 tick 再判斷，直到來源艙載入或他離開 interior。理由：重啟之後玩家在原艙內登入時，原艙不在 spawn chunks
 * 的話，chunk 要在 JOIN 之後幾個 tick 才由玩家（或 COLLAPSING 的來源）ticket 載入；在這時消耗觸發，開服之後的第一次觀察（或 JOIN 在艙內的邊緣）就會落空，玩家要離開再
 * 進入，而且沒有任何訊息。其他條件不成立時（結構不合法、未供電、Origin 停用；不符合參與者條件的人；呼叫端的退避期）照樣消耗這次觸發，維持邊緣觸發的語意。只查詢、
 * 不寫入、不記 log（來源艙未載入期間每個 tick 都會判斷）。
 */
final class EntryWatch {
    private final boolean armedAtStartup;
    private Set<UUID> inside;

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

    /** 只記下目前的狀態，不觸發（閘門第一次以位準評估之後）。 */
    void prime(Set<UUID> insideNow) { inside = Set.copyOf(insideNow); }

    /** 把 {@code players} 移出上一次的觀察：下一次觀察時，仍在艙內的人又是新進入（H7）。還沒有觀察過時不做任何事。 */
    void forget(Collection<UUID> players) {
        if (inside == null) return;
        var kept = new HashSet<>(inside);
        kept.removeAll(players);
        inside = Set.copyOf(kept);
    }

    /**
     * 觀察一次，回傳這個 tick 是否有 §7.1 的觸發成立（DORMANT 的續行、§11.2 SEALED 的重試、§7.3 閘門第一次評估之後的邊緣）：新進入艙內、而且 {@code ready}（沒有被其他
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
     * §7.3 閘門第一次以位準評估：在艙內、{@code ready} 的人存在而且 {@code source} 是 {@code OK} 才成立，之後只看邊緣（{@link #prime}）。有這樣的人、但來源艙還沒載入
     * （{@code NOT_LOADED}）時回 {@code null}：不算評估過、不 prime，呼叫端下一個 tick 再以位準評估（spec v5.9 §7.3）。沒有這樣的人時不查詢 {@code source}。
     */
    Boolean level(Set<UUID> insideNow, Predicate<UUID> ready, Supplier<SourceSignal> source) {
        var signal = insideNow.stream().anyMatch(ready) ? source.get() : null;
        if (signal == SourceSignal.NOT_LOADED) return null;
        prime(insideNow);
        return signal == SourceSignal.OK;
    }
}
