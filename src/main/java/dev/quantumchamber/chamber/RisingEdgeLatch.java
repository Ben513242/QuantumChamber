package dev.quantumchamber.chamber;

/** Keeps the previous power level so callers can react only to a rising edge. */
public final class RisingEdgeLatch {
    private boolean wasPowered;

    public boolean observe(boolean powered) {
        boolean risingEdge = powered && !wasPowered;
        wasPowered = powered;
        return risingEdge;
    }

    public void synchronizeWithoutEdge(boolean powered) {
        wasPowered = powered;
    }
}
