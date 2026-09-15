package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlocks;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class WorldChamberBlockView implements ChamberBlockView {
    private final World world;

    public WorldChamberBlockView(World world) {
        this.world = world;
    }

    @Override
    public ChamberCell cellAt(BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isOf(Blocks.BEDROCK)) return ChamberCell.BEDROCK;
        if (state.isOf(ModBlocks.CHAMBER_CONTROLLER)) return ChamberCell.CONTROLLER;
        if (state.isOf(ModBlocks.QUANTUM_BULKHEAD)) {
            return state.get(QuantumBulkheadBlock.OPEN) ? ChamberCell.BULKHEAD_OPEN : ChamberCell.BULKHEAD_CLOSED;
        }
        return ChamberCell.OTHER;
    }
}
