package dev.quantumchamber.corridor;

import java.util.UUID;

public record DoorKey(UUID sessionUuid, long logicalDoorIndex, Side side) {
    public DoorKey { java.util.Objects.requireNonNull(sessionUuid); java.util.Objects.requireNonNull(side); }
    public enum Side { LEFT, RIGHT }
    public static DoorKey from(UUID sessionUuid, long blockLogicalZ, Side side) {
        return new DoorKey(sessionUuid, Math.floorDiv(blockLogicalZ, 8), side);
    }
}
