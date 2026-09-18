package dev.quantumchamber.transfer;

import dev.quantumchamber.chamber.ChamberFrame;
import java.util.*;
import net.minecraft.util.math.Vec3d;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;

/** 原艙有限 floor slots 的確定性配置。 */
public final class ChamberReturnPlacement {
    private ChamberReturnPlacement() {}
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people) {
        return place(frame,validated(people).stream().sorted().toList());
    }
    public static Map<UUID,Vec3d> plan(ChamberFrame frame,List<UUID> people,Map<UUID,Vec3d> sources) {
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
        return place(frame,ordered);
    }
    private static List<UUID> validated(List<UUID> people) {
        var copy = List.copyOf(people);
        if (copy.size()>25 || new HashSet<>(copy).size()!=copy.size())
            throw new IllegalArgumentException("floor slots 最多25人且 UUID 不得重複");
        return copy;
    }
    private static Map<UUID,Vec3d> place(ChamberFrame frame,List<UUID> people) {
        var result = new LinkedHashMap<UUID,Vec3d>();
        for(int i=0;i<people.size();i++) result.put(people.get(i),ChamberSpaceCoordinates.position(frame,1.5+i%5,1,1.5+i/5));
        return Collections.unmodifiableMap(result);
    }
}
