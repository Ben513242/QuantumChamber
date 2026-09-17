package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.registry.ModBlocks;
import java.util.HashSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

/** 僅 testmod：對單一 fixture／world 的整面門注入一次最後格失敗及全部 rollback 拒絕。 */
public final class DoorWriteFault {
    private static final ThreadLocal<Plan> CURRENT = new ThreadLocal<>();
    private DoorWriteFault() { }

    public static <T> T during(ServerWorld world, ChamberFrame frame, boolean throwing, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("測試故障 scope 不得巢狀");
        Set<BlockPos> positions = new HashSet<>();
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        CURRENT.set(new Plan(world, positions, throwing));
        try { return action.get(); } finally { CURRENT.remove(); }
    }

    public static boolean write(ServerWorld world, BlockPos pos, BlockState state, BooleanSupplier original) {
        var plan = CURRENT.get();
        if (plan == null || plan.world != world || !plan.positions.contains(pos)
                || !state.isOf(ModBlocks.QUANTUM_BULKHEAD)) return original.getAsBoolean();
        if (state.get(QuantumBulkheadBlock.OPEN)) return false;
        boolean changed = original.getAsBoolean();
        if (++plan.closes == 25) {
            if (plan.throwing) throw new IllegalStateException("測試注入：最後格已改，rollback 全部拒絕");
            return false;
        }
        return changed;
    }

    private static final class Plan {
        final ServerWorld world;
        final Set<BlockPos> positions;
        final boolean throwing;
        int closes;
        Plan(ServerWorld world, Set<BlockPos> positions, boolean throwing) {
            this.world = world;
            this.positions = positions;
            this.throwing = throwing;
        }
    }
}
