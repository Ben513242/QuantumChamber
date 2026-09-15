package dev.quantumchamber.chamber;

public final class ChamberStatusSignal {
    private ChamberStatusSignal() {
    }

    public static int forState(ChamberState state) {
        return switch (state) {
            case INVALID -> 0;
            case IDLE -> 3;
            case READY -> 7;
            case ARMED -> 11;
        };
    }
}
