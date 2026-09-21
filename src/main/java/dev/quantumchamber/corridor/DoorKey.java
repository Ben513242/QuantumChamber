package dev.quantumchamber.corridor;

import java.util.UUID;

/** 一扇側向走廊門的穩定邏輯身分。 */
public record DoorKey(UUID sessionUuid, long logicalStationIndex, DoorWallSide wallSide) {
    public DoorKey {
        java.util.Objects.requireNonNull(sessionUuid, "sessionUuid");
        java.util.Objects.requireNonNull(wallSide, "wallSide");
    }

    public enum DoorWallSide { NEGATIVE_LATERAL, POSITIVE_LATERAL }

    public static DoorKey fromBlock(UUID sessionUuid, long blockLogicalZ, DoorWallSide wallSide) {
        return new DoorKey(sessionUuid, Math.floorDiv(blockLogicalZ, 8), wallSide);
    }
}
