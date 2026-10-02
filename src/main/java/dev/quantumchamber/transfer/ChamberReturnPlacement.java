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
     * 返還落點避開原艙內的方塊（整合審查延後清單 R2 M6）。順序與 {@link #plan(ChamberFrame,List,Map)} 相同；每位玩家依序取第一個
     * {@code clear} 成立、而且還沒被前面的人取走的候選。候選依序是 25 個地板 slot（local y=1），再依序是同樣 25 格往上一層的 y=2、3、4
     * （站在室內裝飾上；玩家 bbox 高 1.8，y=4 時仍完整在 interior 內）。地板沒有被擋時，結果與 {@link #plan(ChamberFrame,List,Map)} 相同。
     * 全部候選都不成立時沿用原本的地板 slot（舊行為），呼叫端以同一個 {@code clear} 判斷是否落入這個退路並自行記錄。
     *
     * @param clear 站在這個位置（腳底中心）的玩家 bbox 是否不與方塊碰撞
     */
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people,Map<UUID,Vec3d> sources,Predicate<Vec3d> clear) {
        Objects.requireNonNull(clear,"clear");
        var ordered = ordered(frame,people,sources);
        var fallback = place(frame,ordered);
        var candidates = new ArrayList<Vec3d>(LEVELS*SLOTS);
        for (int level=1;level<=LEVELS;level++) for (int i=0;i<SLOTS;i++) candidates.add(slot(frame,i,level));
        var taken = new HashSet<Vec3d>();
        var result = new LinkedHashMap<UUID,Vec3d>();
        for (UUID id : ordered) {
            var chosen = candidates.stream().filter(candidate -> !taken.contains(candidate) && clear.test(candidate)).findFirst()
                    .orElse(fallback.get(id));
            taken.add(chosen); result.put(id,chosen);
        }
        return Collections.unmodifiableMap(result);
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
