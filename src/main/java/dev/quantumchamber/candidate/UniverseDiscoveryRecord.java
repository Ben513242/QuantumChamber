package dev.quantumchamber.candidate;

import dev.quantumchamber.universe.UniverseId;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** 玩家發現權威的不可變記錄；不從技術 catalog 推定 gameplay eligibility。 */
public record UniverseDiscoveryRecord(UniverseId universeId, long discoveryOrdinal,
        long discoveredAtGameTime, Optional<UUID> firstObserver, boolean gameplayEligible) {
    static final Comparator<UniverseDiscoveryRecord> CANONICAL_ORDER = Comparator
            .comparingLong(UniverseDiscoveryRecord::discoveryOrdinal)
            .thenComparing((left, right) -> Long.compareUnsigned(
                    left.universeId().value().getMostSignificantBits(), right.universeId().value().getMostSignificantBits()))
            .thenComparing((left, right) -> Long.compareUnsigned(
                    left.universeId().value().getLeastSignificantBits(), right.universeId().value().getLeastSignificantBits()));

    public UniverseDiscoveryRecord {
        Objects.requireNonNull(universeId, "universeId");
        Objects.requireNonNull(firstObserver, "firstObserver");
        if (discoveryOrdinal < 0 || discoveredAtGameTime < 0) {
            throw new IllegalArgumentException("Discovery ordinal 與 game time 必須非負");
        }
    }
}
