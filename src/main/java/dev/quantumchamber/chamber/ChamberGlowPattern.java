package dev.quantumchamber.chamber;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.math.BlockBox;

/** 艙殼外的有界輝光樣本；不存取世界或遊戲狀態。 */
public final class ChamberGlowPattern {
    private static final int BREATH_TICKS = 80;
    private static final int SAMPLE_BUDGET = 24;
    private ChamberGlowPattern() { }

    public static List<Sample> sample(BlockBox bounds, long ticks) {
        int phase = Math.floorMod(ticks, BREATH_TICKS);
        double breath = (1 - Math.cos(phase * Math.PI * 2 / BREATH_TICKS)) / 2;
        float intensity = (float) (0.15 + 0.30 * breath);
        double upward = 0.08 + 0.08 * breath;
        double west = bounds.getMinX() - 0.18;
        double east = bounds.getMaxX() + 1.18;
        double north = bounds.getMinZ() - 0.18;
        double south = bounds.getMaxZ() + 1.18;
        double centerX = (bounds.getMinX() + bounds.getMaxX() + 1.0) / 2;
        double centerZ = (bounds.getMinZ() + bounds.getMaxZ() + 1.0) / 2;
        var samples = new ArrayList<Sample>(SAMPLE_BUDGET);
        for (double x : new double[] {west, east}) {
            for (double z : new double[] {north, south}) {
                for (int level = 0; level < 4; level++) {
                    samples.add(new Sample(x, bounds.getMinY() + 0.3 + level * 1.9 + phase * 0.006,
                            z, 0, upward, 0, intensity, false));
                }
                samples.add(new Sample(x, bounds.getMinY() + 0.25, z, 0, upward, 0, intensity, true));
            }
        }
        samples.add(new Sample(west, bounds.getMinY() + 0.5, centerZ, 0, upward, 0, intensity, false));
        samples.add(new Sample(east, bounds.getMinY() + 0.5, centerZ, 0, upward, 0, intensity, false));
        samples.add(new Sample(centerX, bounds.getMinY() + 0.5, north, 0, upward, 0, intensity, false));
        samples.add(new Sample(centerX, bounds.getMinY() + 0.5, south, 0, upward, 0, intensity, false));
        return List.copyOf(samples);
    }

    public record Sample(double x, double y, double z, double velocityX, double velocityY,
                         double velocityZ, float intensity, boolean spark) { }
}
