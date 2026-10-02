package dev.quantumchamber.transfer;

import dev.quantumchamber.chamber.ChamberFrame;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.util.math.Vec3d;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;

/** 原艙有限 floor slots 的確定性配置。 */
public final class ChamberReturnPlacement {
    private ChamberReturnPlacement() {}
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people) {
        return place(frame,validated(people).stream().sorted().toList());
    }
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people,Map<UUID,Vec3d> sources) {
        return place(frame,ordered(frame,people,sources));
    }
    /**
     * 返還落點避開原艙內的方塊（整合審查延後清單 R2 M6）。順序與 {@link #plan(ChamberFrame,List,Map)} 相同；每位玩家依序取：
     * <ol>
     * <li>第一個 {@code clear} 成立、還沒被前面的人取走的地板 slot（local y=1，25 格）；</li>
     * <li>否則站上一層（y=2、3、4，站在室內裝飾上；玩家 bbox 高 1.8，y=4 時仍完整在 interior 內）。先選這一欄（同一個地板 slot 的垂直欄）
     *     還沒有人的候選，避免疊在別人頭上；都沒有時才選任何 {@code clear} 成立、沒被取走的候選；</li>
     * <li>都不成立時退回第一個還沒被取走的地板 slot（會與方塊碰撞；呼叫端以同一個 {@code clear} 判斷並自行記錄）。最多 25 人、每人取走一個
     *     位置，輪到任何人時前面的人最多取走 24 個，所以一定有沒被取走的地板 slot，結果不會重複。</li>
     * </ol>
     * 地板沒有被擋時，結果與 {@link #plan(ChamberFrame,List,Map)} 相同。
     *
     * @param clear 站在這個位置（腳底中心）的玩家 bbox 是否不與方塊碰撞
     */
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people,Map<UUID,Vec3d> sources,Predicate<Vec3d> clear) {
        Objects.requireNonNull(clear,"clear");
        var ordered = ordered(frame,people,sources);
        var taken = new HashSet<Vec3d>();
        var occupiedColumns = new HashSet<Integer>();
        var result = new LinkedHashMap<UUID,Vec3d>();
        for (UUID id : ordered) {
            int chosen = pick(frame,1,1,taken,clear,Set.of());
            if (chosen<0) chosen = pick(frame,2,LEVELS,taken,clear,occupiedColumns);
            if (chosen<0) chosen = pick(frame,2,LEVELS,taken,clear,Set.of());
            if (chosen<0) chosen = pick(frame,1,1,taken,position -> true,Set.of());
            if (chosen<0) throw new IllegalStateException("沒有沒被取走的地板 slot（最多 25 人，不會發生）");
            var position = slot(frame,chosen%SLOTS,chosen/SLOTS+1);
            taken.add(position); occupiedColumns.add(chosen%SLOTS); result.put(id,position);
        }
        return Collections.unmodifiableMap(result);
    }
    /** 依 level、再依 slot 的順序，回傳第一個沒被取走、{@code clear} 成立、而且不在 {@code avoidColumns} 的候選（編碼為 (level-1)*25+slot），沒有則 -1。 */
    private static int pick(ChamberFrame frame,int fromLevel,int toLevel,Set<Vec3d> taken,Predicate<Vec3d> clear,Set<Integer> avoidColumns) {
        for (int level=fromLevel;level<=toLevel;level++) for (int i=0;i<SLOTS;i++) {
            if (avoidColumns.contains(i)) continue;
            var candidate = slot(frame,i,level);
            if (!taken.contains(candidate) && clear.test(candidate)) return (level-1)*SLOTS+i;
        }
        return -1;
    }
    private static final int SLOTS = 25, LEVELS = 4;
    private static List<UUID> ordered(ChamberFrame frame,List<UUID> people,Map<UUID,Vec3d> sources) {
        var ordered = new ArrayList<>(validated(people));
        var local = new HashMap<UUID,Vec3d>();
        for (UUID id : ordered) {
            var source = sources.get(id);
            if (source == null || !Double.isFinite(source.x) || !Double.isFinite(source.y) || !Double.isFinite(source.z))
                throw new IllegalArgumentException("來源 pose 缺失或不是有限座標");
            local.put(id,ChamberSpaceCoordinates.localPosition(frame,source));
        }
        ordered.sort(Comparator.<UUID>comparingDouble(id -> local.get(id).z)
                .thenComparingDouble(id -> local.get(id).x).thenComparing(Comparator.naturalOrder()));
        return ordered;
    }
    private static Vec3d slot(ChamberFrame frame,int index,int level) {
        return ChamberSpaceCoordinates.position(frame,1.5+index%5,level,1.5+index/5);
    }
    private static List<UUID> validated(List<UUID> people) {
        var copy = List.copyOf(people);
        if (copy.size()>25 || new HashSet<>(copy).size()!=copy.size())
            throw new IllegalArgumentException("floor slots 最多25人且 UUID 不得重複");
        return copy;
    }
    private static Map<UUID,Vec3d> place(ChamberFrame frame,List<UUID> people) {
        var result = new LinkedHashMap<UUID,Vec3d>();
        for(int i=0;i<people.size();i++) result.put(people.get(i),slot(frame,i,1));
        return Collections.unmodifiableMap(result);
    }
}
