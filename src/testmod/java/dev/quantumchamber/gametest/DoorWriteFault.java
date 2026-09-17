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
    private static WriteObservation observation;
    private static ClearFailure clearFailure;
    private DoorWriteFault() { }

    public static <T> T during(ServerWorld world, ChamberFrame frame, boolean throwing, Supplier<T> action) {
        if (CURRENT.get() != null) throw new IllegalStateException("測試故障 scope 不得巢狀");
        Set<BlockPos> positions = new HashSet<>();
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        CURRENT.set(new Plan(world, positions, throwing));
        try { return action.get(); } finally { CURRENT.remove(); }
    }

    public static boolean write(ServerWorld world, BlockPos pos, BlockState state, BooleanSupplier original) {
        var active=observation!=null && observation.world==world ? observation : null;
        BlockState before=active==null ? null : world.getBlockState(pos);
        try { return writeFault(world,pos,state,original); }
        finally {
            if (active!=null && !before.equals(world.getBlockState(pos))) active.changed(world.getServer().getTicks());
        }
    }

    /** 只觀察真 setBlockState 前後的狀態變化，不替代寫入或 budget 實作。 */
    public static WriteObservation observeWrites(ServerWorld world) {
        if (observation!=null) throw new IllegalStateException("寫入觀察 scope 不可重疊");
        observation=new WriteObservation(world); return observation;
    }

    public static final class WriteObservation implements AutoCloseable {
        private final ServerWorld world;
        private final java.util.Map<Integer,Integer> counts=new java.util.HashMap<>();
        private WriteObservation(ServerWorld world) { this.world=world; }
        private void changed(int tick) { counts.merge(tick,1,Integer::sum); }
        public int peak() { return counts.values().stream().mapToInt(Integer::intValue).max().orElse(0); }
        public int total() { return counts.values().stream().mapToInt(Integer::intValue).sum(); }
        @Override public void close() { if (observation==this) observation=null; }
    }

    private static boolean writeFault(ServerWorld world,BlockPos pos,BlockState state,BooleanSupplier original) {
        if (clearFailure!=null && clearFailure.world==world && clearFailure.position.equals(pos) && state.isAir()) {
            clearFailure.attempts++;
            if (clearFailure.attempts==1) return false;
        }
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
    public static ClearFailure failFirstClear(ServerWorld world,BlockPos position) {
        if (clearFailure!=null) throw new IllegalStateException("清理故障scope不可重疊");
        clearFailure=new ClearFailure(world,position.toImmutable()); return clearFailure;
    }
    public static final class ClearFailure implements AutoCloseable {
        private final ServerWorld world; private final BlockPos position; private int attempts;
        private ClearFailure(ServerWorld world,BlockPos position) { this.world=world; this.position=position; }
        public int attempts() { return attempts; }
        @Override public void close() { if (clearFailure==this) clearFailure=null; }
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
