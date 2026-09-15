package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChamberStatusSignalTest {
    @Test
    void mapsEachM1StateToItsLockedComparatorSignal() {
        assertEquals(0, ChamberStatusSignal.forState(ChamberState.INVALID));
        assertEquals(3, ChamberStatusSignal.forState(ChamberState.IDLE));
        assertEquals(7, ChamberStatusSignal.forState(ChamberState.READY));
        assertEquals(11, ChamberStatusSignal.forState(ChamberState.ARMED));
    }
}
