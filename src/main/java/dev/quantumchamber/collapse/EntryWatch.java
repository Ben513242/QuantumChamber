package dev.quantumchamber.collapse;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * §7.1 的邊緣觸發：原參與者由艙外進入艙內。只比較相鄰兩次觀察，不是位準觸發。第一次觀察時，開服 attach 當下就在 journal 的 session
 * （{@code armedAtStartup}）把已在艙內的人當成觸發（「開服之後第一次觀察到原參與者在艙內」）；其他 session 只記下目前的狀態。
 * <p>H7 由 {@link CollapseService} 的巢狀類別搬出（純搬移，行為不變；交接 F6-2-19：讓 {@code CollapseService} 留在約 1600 行之內）。
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
}
