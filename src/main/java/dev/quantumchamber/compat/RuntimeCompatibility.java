package dev.quantumchamber.compat;

public record RuntimeCompatibility(
        boolean sodiumLoaded,
        boolean irisLoaded,
        boolean immersivePortalsLoaded) {
}
