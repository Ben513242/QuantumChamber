package dev.quantumchamber.collapse;

import dev.quantumchamber.universe.UniverseId;
import java.util.Objects;

/** COLLAPSING 提交時寫入、之後不可變的目的地（spec §5、§7.2）。SOURCE 不配置宇宙；UNIVERSE 指向 exact 的 UniverseId。 */
public sealed interface CollapseDestination permits CollapseDestination.Source, CollapseDestination.Universe {
    record Source() implements CollapseDestination {}

    record Universe(UniverseId id) implements CollapseDestination {
        public Universe { Objects.requireNonNull(id, "id"); }
    }
}
