package dev.quantumchamber.universe;

import java.util.Objects;
import net.minecraft.server.world.ServerWorld;

public record MaterializeResult(Status status, ServerWorld world) {
    public MaterializeResult {
        Objects.requireNonNull(status, "status");
        boolean success = status == Status.MATERIALIZED || status == Status.ALREADY_ACTIVE;
        if (success) {
            Objects.requireNonNull(world, "成功結果必須包含 exact ServerWorld");
        } else if (world != null) {
            throw new IllegalArgumentException("失敗結果不得包含可用的 ServerWorld");
        }
    }

    public enum Status {
        MATERIALIZED, ALREADY_ACTIVE, REJECTED, FAILED_ROLLED_BACK, FAILED_UNHEALTHY
    }
}
