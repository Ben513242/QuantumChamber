package dev.quantumchamber.compat;

@FunctionalInterface
public interface ModPresenceProbe {
    boolean isLoaded(String modId);
}
