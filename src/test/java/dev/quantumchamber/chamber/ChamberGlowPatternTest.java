package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.util.math.BlockBox;
import org.junit.jupiter.api.Test;

class ChamberGlowPatternTest {
    // 人工核對：北向 C=(15,70,14) 的 shell 為 x12..18、y64..70、z14..20。
    private static final BlockBox NORTH_SHELL = new BlockBox(12, 64, 14, 18, 70, 20);

    @Test
    void particlesSurroundAllFourCornersOutsideShellAndRiseWithinBudget() {
        var samples = ChamberGlowPattern.sample(NORTH_SHELL, 0);
        assertFalse(samples.isEmpty(), "供電艙體必須產生可見樣本");
        assertTrue(samples.size() <= 24, "每次有限粒子預算");
        for (var sample : samples) {
            assertTrue(sample.x() < 12 || sample.x() > 19 || sample.z() < 14 || sample.z() > 21,
                    "樣本必須在實際方塊殼外，不在 interior 或建材內");
            assertTrue(sample.x() >= 11.5 && sample.x() <= 19.5);
            assertTrue(sample.z() >= 13.5 && sample.z() <= 21.5);
            assertTrue(sample.y() >= 64 && sample.y() < 71);
            assertEquals(0, sample.velocityX());
            assertEquals(0, sample.velocityZ());
            assertTrue(sample.velocityY() >= 0.08 && sample.velocityY() <= 0.16);
        }
        for (boolean west : new boolean[] {true, false}) {
            for (boolean north : new boolean[] {true, false}) {
                assertTrue(samples.stream().anyMatch(s -> (west ? s.x() < 12 : s.x() > 19)
                        && (north ? s.z() < 14 : s.z() > 21)), "四個艙角都要可見");
            }
        }
        assertTrue(samples.stream().filter(ChamberGlowPattern.Sample::spark).count() > 0);
        assertTrue(samples.stream().filter(ChamberGlowPattern.Sample::spark).count() <= 4);
        assertTrue(samples.stream().anyMatch(s -> !s.spark() && s.y() > 69), "細流覆蓋艙角上段");
        assertTrue(samples.stream().anyMatch(s -> s.y() < 65), "細流由艙底開始");
    }

    @Test
    void fourSecondBreathingChangesObservableIntensityWithoutChangingBudget() {
        var low = ChamberGlowPattern.sample(NORTH_SHELL, 0);
        var high = ChamberGlowPattern.sample(NORTH_SHELL, 40);
        assertFalse(low.isEmpty());
        assertEquals(low.size(), high.size());
        assertTrue(high.getFirst().intensity() - low.getFirst().intensity() > 0.2);
        assertTrue(high.getFirst().velocityY() > low.getFirst().velocityY());
        for (int tick = 0; tick < 80; tick++) {
            for (var sample : ChamberGlowPattern.sample(NORTH_SHELL, tick)) {
                assertTrue(sample.intensity() >= 0.15f && sample.intensity() <= 0.45f);
            }
        }
        assertEquals(low, ChamberGlowPattern.sample(NORTH_SHELL, 80));
    }

    @Test
    void patternTracksTranslatedBoundsInsteadOfControllerCell() {
        var first = ChamberGlowPattern.sample(NORTH_SHELL, 20);
        var moved = ChamberGlowPattern.sample(new BlockBox(-8, 74, 44, -2, 80, 50), 20);
        assertFalse(first.isEmpty());
        assertEquals(first.size(), moved.size());
        for (int index = 0; index < first.size(); index++) {
            assertEquals(first.get(index).x() - 20, moved.get(index).x(), 1.0e-9);
            assertEquals(first.get(index).y() + 10, moved.get(index).y(), 1.0e-9);
            assertEquals(first.get(index).z() + 30, moved.get(index).z(), 1.0e-9);
        }
    }
}
