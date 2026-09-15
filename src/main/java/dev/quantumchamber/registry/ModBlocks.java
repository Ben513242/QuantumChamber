package dev.quantumchamber.registry;

import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    public static final Block CHAMBER_CONTROLLER = register("chamber_controller",
            new ChamberControllerBlock(AbstractBlock.Settings.copy(Blocks.BEDROCK)
                    .pistonBehavior(PistonBehavior.BLOCK)));
    public static final Block QUANTUM_BULKHEAD = register("quantum_bulkhead",
            new QuantumBulkheadBlock(AbstractBlock.Settings.copy(Blocks.BEDROCK)
                    .pistonBehavior(PistonBehavior.BLOCK)));

    private ModBlocks() {
    }

    public static void register() {
        // Class loading performs the registrations above.
    }

    private static Block register(String path, Block block) {
        return Registry.register(Registries.BLOCK, Identifier.of("quantumchamber", path), block);
    }
}
