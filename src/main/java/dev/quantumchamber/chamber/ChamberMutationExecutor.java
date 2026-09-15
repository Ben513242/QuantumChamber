package dev.quantumchamber.chamber;

import java.util.function.Supplier;

@FunctionalInterface
public interface ChamberMutationExecutor {
    ChamberMutationExecutor DIRECT = mutation -> mutation.get();

    boolean execute(Supplier<Boolean> mutation);
}
