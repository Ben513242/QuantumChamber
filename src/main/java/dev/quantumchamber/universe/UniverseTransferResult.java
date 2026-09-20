package dev.quantumchamber.universe;

import java.util.UUID;
import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/** 每次原生嘗試與回滾各保留實際觀測；receipt 僅在 MOVED 存在。 */
public record UniverseTransferResult(Outcome outcome, UniverseTransferAuthority.Decision decision,
        UniverseTransferPoint source, MoveEvidence move, RollbackEvidence rollback,
        UniverseTransferReceipt receipt, String detail) {
    public UniverseTransferResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(rollback, "rollback");
        Objects.requireNonNull(detail, "detail");
        if ((outcome == Outcome.MOVED) != (receipt != null)) {
            throw new IllegalArgumentException("只有 MOVED 必須且可以持有 success receipt");
        }
        boolean attempted = move.outcome() != NativeOutcome.NOT_ATTEMPTED;
        boolean noRollback = rollback.outcome() == RollbackOutcome.NOT_ATTEMPTED;
        boolean valid = switch (outcome) {
            case REJECTED_BEFORE_MOVE -> !attempted && noRollback && decision != UniverseTransferAuthority.Decision.ALLOW;
            case MOVED, RETURNED -> move.outcome() == NativeOutcome.REPORTED_SUCCESS && noRollback
                    && decision == UniverseTransferAuthority.Decision.ALLOW;
            case FAILED_ROLLED_BACK -> attempted && rollback.outcome() == RollbackOutcome.SUCCEEDED;
            case FAILED_RECOVERY_REQUIRED -> attempted && !noRollback && rollback.outcome() != RollbackOutcome.SUCCEEDED;
        };
        if (!valid || (receipt != null && (!receipt.source().equals(source)
                || !receipt.playerId().equals(move.actual().playerId())
                || !move.actual().matches(receipt.destination())))) {
            throw new IllegalArgumentException("結果與移動／回滾證據不一致");
        }
    }
    public enum Outcome { REJECTED_BEFORE_MOVE, MOVED, FAILED_ROLLED_BACK, FAILED_RECOVERY_REQUIRED, RETURNED }
    public enum NativeOutcome { NOT_ATTEMPTED, REPORTED_SUCCESS, REPORTED_FAILURE, THREW }
    public enum RollbackOutcome { NOT_ATTEMPTED, SUCCEEDED, SOURCE_IDENTITY_LOST, PLAYER_IDENTITY_LOST, SOURCE_UNSAFE, FAILED }

    /** 原生故障可能產生非有限數；觀測值保留原貌，不轉成有效 transfer point。 */
    public record Observation(UUID playerId, RegistryKey<World> worldKey, Vec3d position,
            Vec3d velocity, float yaw, float pitch, boolean playerExact,
            boolean worldExact, boolean worldEntityExact) {
        public Observation {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(worldKey, "worldKey");
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(velocity, "velocity");
        }
        public boolean matches(UniverseTransferPoint point) {
            return playerExact && worldExact && worldEntityExact && worldKey.equals(point.worldKey())
                    && position.squaredDistanceTo(point.position()) < 1e-12
                    && velocity.squaredDistanceTo(point.velocity()) < 1e-12
                    && Math.abs(MathHelper.wrapDegrees(yaw - point.yaw())) < 1e-4
                    && Math.abs(pitch - point.pitch()) < 1e-4;
        }
    }

    public record MoveEvidence(NativeOutcome outcome, Observation actual, String detail) {
        public MoveEvidence {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(detail, "detail");
        }
    }
    public record RollbackEvidence(RollbackOutcome outcome, NativeOutcome nativeOutcome,
            Observation actual, String detail) {
        public RollbackEvidence {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(nativeOutcome, "nativeOutcome");
            Objects.requireNonNull(actual, "actual");
            Objects.requireNonNull(detail, "detail");
            if (outcome == RollbackOutcome.SUCCEEDED && nativeOutcome != NativeOutcome.REPORTED_SUCCESS) {
                throw new IllegalArgumentException("回滾成功必須有原生成功及實際觀測");
            }
        }
    }

    public Observation actual() { return rollback.actual(); }
}
