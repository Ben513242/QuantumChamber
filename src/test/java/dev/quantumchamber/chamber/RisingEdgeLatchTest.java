package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RisingEdgeLatchTest {
    @Test
    void reportsOnlyLowToHighTransitions() {
        RisingEdgeLatch latch = new RisingEdgeLatch();

        assertFalse(latch.observe(false));
        assertTrue(latch.observe(true));
        assertFalse(latch.observe(true));
        assertFalse(latch.observe(false));
        assertTrue(latch.observe(true));
    }

    @Test
    void synchronizeWithoutEdgeSuppressesHeldHighPower() {
        RisingEdgeLatch latch = new RisingEdgeLatch();

        latch.synchronizeWithoutEdge(true);

        assertFalse(latch.observe(true));
    }
}
