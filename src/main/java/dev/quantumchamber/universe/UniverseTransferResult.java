package dev.quantumchamber.universe;

import java.util.UUID;
import java.util.Objects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * 每次原生嘗試與回滾各保留實際觀測；receipt 僅在 MOVED 存在。
 * 只有 thread 拒絕可以沒有 source／觀測；非有限來源拒絕沒有有效 source，但必須保留 raw 觀測。
 */
public record UniverseTransferResult(Outcome outcome, UniverseTransferAuthority.Decision decision,
        UniverseTransferPoint source, MoveEvidence move, RollbackEvidence rollback,
        UniverseTransferReceipt receipt, String detail) {
    public UniverseTransferResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(move, "move");
        Objects.requireNonNull(rollback, "rollback");
        Objects.requireNonNull(detail, "detail");
        if ((outcome == Outcome.MOVED) != (receipt != null)) {
            throw new IllegalArgumentException("只有 MOVED 必須且可以持有 success receipt");
        }
        boolean attempted = move.outcome() != NativeOutcome.NOT_ATTEMPTED;
        boolean noRollback = rollback.outcome() == RollbackOutcome.NOT_ATTEMPTED;
        boolean beforeObservation = outcome == Outcome.REJECTED_BEFORE_MOVE
                && decision == UniverseTransferAuthority.Decision.REJECT_SERVER_THREAD
                && source == null && move.actual() == null && rollback.actual() == null;
        boolean nonFiniteSource = outcome == Outcome.REJECTED_BEFORE_MOVE
                && decision == UniverseTransferAuthority.Decision.REJECT_SOURCE_NON_FINITE
                && source == null && move.actual() != null && !move.actual().finitePose()
                && move.actual().equals(rollback.actual());
        boolean fullEvidence = source != null && move.actual() != null && rollback.actual() != null;
        if ((!beforeObservation && !nonFiniteSource && !fullEvidence)
                || (decision == UniverseTransferAuthority.Decision.REJECT_SERVER_THREAD && !beforeObservation)
                || (decision == UniverseTransferAuthority.Decision.REJECT_SOURCE_NON_FINITE && !nonFiniteSource)) {
            throw new IllegalArgumentException("只有前置拒絕可以缺少 source 或觀測，且必須符合明確原因");
        }
        boolean valid = switch (outcome) {
            case REJECTED_BEFORE_MOVE -> !attempted && noRollback && decision != UniverseTransferAuthority.Decision.ALLOW;
            case MOVED, RETURNED -> move.outcome() == NativeOutcome.REPORTED_SUCCESS && noRollback
                    && decision == UniverseTransferAuthority.Decision.ALLOW;
            case FAILED_ROLLED_BACK -> attempted && rollback.outcome() == RollbackOutcome.SUCCEEDED
                    && rollback.nativeOutcome() == NativeOutcome.REPORTED_SUCCESS
                    && rollback.actual().matches(source);
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

        public boolean finitePose() {
            return Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)
                    && Double.isFinite(velocity.x) && Double.isFinite(velocity.y) && Double.isFinite(velocity.z)
                    && Float.isFinite(yaw) && Float.isFinite(pitch);
        }
    }

    public record MoveEvidence(NativeOutcome outcome, Observation actual, String detail) {
        public MoveEvidence {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(detail, "detail");
            if (actual == null && outcome != NativeOutcome.NOT_ATTEMPTED) {
                throw new IllegalArgumentException("原生移動嘗試必須保留實際觀測");
            }
        }
    }
    public record RollbackEvidence(RollbackOutcome outcome, NativeOutcome nativeOutcome,
            Observation actual, String detail) {
        public RollbackEvidence {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(nativeOutcome, "nativeOutcome");
            Objects.requireNonNull(detail, "detail");
            if ((actual == null && outcome != RollbackOutcome.NOT_ATTEMPTED)
                    || (outcome == RollbackOutcome.NOT_ATTEMPTED && nativeOutcome != NativeOutcome.NOT_ATTEMPTED)) {
                throw new IllegalArgumentException("回滾分支與原生嘗試／觀測不一致");
            }
            if (outcome == RollbackOutcome.SUCCEEDED && nativeOutcome != NativeOutcome.REPORTED_SUCCESS) {
                throw new IllegalArgumentException("回滾成功必須有原生成功及實際觀測");
            }
        }
    }

    /** 只有 REJECT_SERVER_THREAD 的 pre-observation rejection 回傳 null。 */
    public Observation actual() { return rollback.actual(); }
}
