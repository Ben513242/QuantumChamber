package dev.quantumchamber.corridor;

import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Set;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;

public final class SlotAllocator {
    static final int MAX_TICKET_COVERAGE_CHUNKS=4096;
    private final Map<Integer, SlotLease> leases = new LinkedHashMap<>();
    private int peakCoverage;
    public record SlotLease(int slotId, BlockPos origin, BlockBox bounds) {
        public SlotLease { origin = origin.toImmutable(); bounds = copy(bounds); }
        @Override public BlockBox bounds() { return copy(bounds); }
    }
    public Optional<SlotLease> reserve(BlockBox relativeBounds) {
        if (leases.size() >= 64) return Optional.empty();
        int slot = 0;
        while (leases.containsKey(slot)) slot++;
        // 有限實體格網只依空間碰撞選位，與歷史邏輯距離無關。
        for (int row = 0; row < 128; row++) for (int column = 0; column < 128; column++) {
            var origin = new BlockPos(1000 + 192 * column, 70, 2000 + 192 * row);
            BlockBox bounds;
            try {
                bounds = new BlockBox(Math.addExact(relativeBounds.getMinX(), origin.getX()),
                        Math.addExact(relativeBounds.getMinY(), origin.getY()), Math.addExact(relativeBounds.getMinZ(), origin.getZ()),
                        Math.addExact(relativeBounds.getMaxX(), origin.getX()), Math.addExact(relativeBounds.getMaxY(), origin.getY()),
                        Math.addExact(relativeBounds.getMaxZ(), origin.getZ()));
            } catch (ArithmeticException failure) { throw new IllegalArgumentException("實體 bounds 溢位", failure); }
            validateShape(bounds);
            if (bounds.getMinY() < 0 || bounds.getMaxY() >= 256
                    || Math.abs((long) bounds.getMinX()) >= 29_999_984 || Math.abs((long) bounds.getMaxX()) >= 29_999_984
                    || Math.abs((long) bounds.getMinZ()) >= 29_999_984 || Math.abs((long) bounds.getMaxZ()) >= 29_999_984) continue;
            boolean collision = leases.values().stream().anyMatch(existing -> existing.bounds.intersects(bounds)
                    || existing.origin.getSquaredDistance(origin) < 160 * 160);
            if (!collision) {
                var proposed=new ArrayList<BlockBox>(); leases.values().forEach(lease -> proposed.add(lease.bounds)); proposed.add(bounds);
                var coverage=coverage(proposed);
                if (coverage.isEmpty()) return Optional.empty();
                var lease = new SlotLease(slot, origin, bounds);
                leases.put(slot, lease);
                peakCoverage=Math.max(peakCoverage,coverage.get().size());
                return Optional.of(lease);
            }
        }
        return Optional.empty();
    }
    public void release(int slotId) {
        if (leases.remove(slotId) == null) throw new IllegalArgumentException("未知 slot");
    }
    void restore(int slotId, BlockBox bounds) {
        validateShape(bounds);
        if (slotId < 0 || leases.size() >= 64 || leases.containsKey(slotId)
                || leases.values().stream().anyMatch(lease -> lease.bounds.intersects(bounds))) {
            throw new IllegalStateException("[LEASE_OWNERSHIP_INVALID] 已保存租約重疊或超過容量");
        }
        var proposed=new ArrayList<BlockBox>(); leases.values().forEach(lease -> proposed.add(lease.bounds)); proposed.add(bounds);
        var coverage=coverage(proposed).orElseThrow(() -> new IllegalStateException("[LEASE_COVERAGE_EXCEEDED] 已保存租約超過 ticket coverage 容量"));
        leases.put(slotId, new SlotLease(slotId, bounds.getCenter(), bounds));
        peakCoverage=Math.max(peakCoverage,coverage.size());
    }
    int peakCoverage() { return peakCoverage; }
    static void validateShape(BlockBox bounds) {
        long x=(long)bounds.getMaxX()-bounds.getMinX()+1,y=(long)bounds.getMaxY()-bounds.getMinY()+1,z=(long)bounds.getMaxZ()-bounds.getMinZ()+1;
        if (y!=7 || !(x==7 && z>=7 || z==7 && x>=7) || bounds.getMinY()<0 || bounds.getMaxY()>=256
                || Math.abs((long)bounds.getMinX())>=29_999_984 || Math.abs((long)bounds.getMaxX())>=29_999_984
                || Math.abs((long)bounds.getMinZ())>=29_999_984 || Math.abs((long)bounds.getMaxZ())>=29_999_984) {
            throw new IllegalArgumentException("[LEASE_BOUNDS_INVALID] lease 必須是合法世界範圍內的 7×7 走廊截面");
        }
    }
    static Optional<Set<Long>> coverage(Collection<BlockBox> bounds) {
        var union=new HashSet<Long>();
        for (var box : bounds) {
            long minX=((long)box.getMinX()>>4)-2,maxX=((long)box.getMaxX()>>4)+2;
            long minZ=((long)box.getMinZ()>>4)-2,maxZ=((long)box.getMaxZ()>>4)+2;
            long area=Math.multiplyExact(Math.addExact(maxX-minX,1),Math.addExact(maxZ-minZ,1));
            if (area>MAX_TICKET_COVERAGE_CHUNKS) return Optional.empty();
            for(long x=minX;x<=maxX;x++) for(long z=minZ;z<=maxZ;z++) {
                union.add(ChunkPos.toLong(Math.toIntExact(x),Math.toIntExact(z)));
                if (union.size()>MAX_TICKET_COVERAGE_CHUNKS) return Optional.empty();
            }
        }
        return Optional.of(Set.copyOf(union));
    }
    static BlockBox copy(BlockBox box) {
        return new BlockBox(box.getMinX(), box.getMinY(), box.getMinZ(), box.getMaxX(), box.getMaxY(), box.getMaxZ());
    }
}
