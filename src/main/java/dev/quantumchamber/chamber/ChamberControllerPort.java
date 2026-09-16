package dev.quantumchamber.chamber;

/** Persisted controller fields needed by redstone handling. */
public interface ChamberControllerPort {
    boolean wasPowered();

    void setWasPowered(boolean wasPowered);

    boolean powerInitialized();

    void setPowerInitialized(boolean powerInitialized);

    ChamberState chamberState();

    void setChamberState(ChamberState chamberState);
}
