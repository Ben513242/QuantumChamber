package dev.quantumchamber.corridor;

import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.TreeSet;

public final class CorridorLayout {
    private CorridorLayout() {}
    public record LayoutComponent(long firstCorePage, long lastCorePage, long aliasStartBlock, long aliasEndBlock) {}
    public static List<LayoutComponent> plan(Set<Long> occupiedPages, int apron) {
        if (occupiedPages.isEmpty() || occupiedPages.size() > 128 || apron < 0) {
            throw new IllegalArgumentException("occupied pages 必須為 1..128，apron 不得為負");
        }
        var result = new ArrayList<LayoutComponent>();
        try {
            for (long page : new TreeSet<>(occupiedPages)) {
                long first = Math.subtractExact(page, 1), last = Math.addExact(page, 1);
                long start = Math.subtractExact(Math.multiplyExact(first, 96), apron);
                long end = Math.addExact(Math.multiplyExact(Math.addExact(last, 1), 96), apron);
                if (Math.abs((double) start) >= 0x1p53 || Math.abs((double) end) >= 0x1p53) {
                    throw new IllegalArgumentException("alias 超出精確座標範圍");
                }
                if (!result.isEmpty() && result.getLast().aliasEndBlock() >= start) {
                    var previous = result.removeLast();
                    result.add(new LayoutComponent(previous.firstCorePage(), last, previous.aliasStartBlock(), end));
                } else result.add(new LayoutComponent(first, last, start, end));
            }
        } catch (ArithmeticException failure) { throw new IllegalArgumentException("邏輯頁範圍溢位", failure); }
        return List.copyOf(result);
    }
}
