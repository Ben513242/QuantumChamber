package dev.quantumchamber.universe;

import java.util.UUID;
import java.util.Objects;

/** 純值權威判斷；Minecraft 狀態讀取由 service 負責。 */
public final class UniverseTransferAuthority {
    public enum Decision {
        ALLOW, REJECT_SERVER_THREAD, REJECT_PLAYER_UUID, REJECT_PLAYER_IDENTITY,
        REJECT_SOURCE_IDENTITY, REJECT_TARGET_NOT_ACTIVE, REJECT_TARGET_IDENTITY,
        REJECT_TARGET_NOT_FULL, REJECT_TARGET_UNSAFE, REJECT_DESCRIPTOR,
        REJECT_RECEIPT_IDENTITY, REJECT_BACKEND_FAILURE, REJECT_SOURCE_NON_FINITE, POST_MOVE_VERIFICATION_FAILED
    }

    public record Snapshot(boolean serverThread, UUID expectedPlayerId, UUID actualPlayerId,
            boolean playerExact, boolean sourceExact, boolean targetActive,
            boolean targetExact, boolean targetFull, boolean targetSafe,
            UniverseTransferPoint source) {
        public Snapshot {
            Objects.requireNonNull(source, "source");
        }
    }

    public Decision evaluate(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.serverThread()) return Decision.REJECT_SERVER_THREAD;
        if (snapshot.expectedPlayerId() == null || !snapshot.expectedPlayerId().equals(snapshot.actualPlayerId())) {
            return Decision.REJECT_PLAYER_UUID;
        }
        if (!snapshot.playerExact()) return Decision.REJECT_PLAYER_IDENTITY;
        if (!snapshot.sourceExact()) return Decision.REJECT_SOURCE_IDENTITY;
        if (!snapshot.targetActive()) return Decision.REJECT_TARGET_NOT_ACTIVE;
        if (!snapshot.targetExact()) return Decision.REJECT_TARGET_IDENTITY;
        if (!snapshot.targetFull()) return Decision.REJECT_TARGET_NOT_FULL;
        if (!snapshot.targetSafe()) return Decision.REJECT_TARGET_UNSAFE;
        return Decision.ALLOW;
    }
}
