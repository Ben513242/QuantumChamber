package dev.quantumchamber.corridor;

import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;

/** 入口覆寫層與走廊共用同一租約；這個類別不另行配置 slot。 */
public final class SessionEntranceAllocator {
    private SessionEntranceAllocator() {}
    static BlockState cell(ChamberFrame frame, int x, int y, int z, boolean frontOpen, boolean rearOpen) {
        if (x == 3 && y == 6 && z == 0) {
            return ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing());
        }
        if (x > 0 && x < 6 && y > 0 && y < 6) {
            if (z == 0) return ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, frontOpen);
            if (z == 6 && rearOpen) return Blocks.AIR.getDefaultState();
        }
        return x == 0 || x == 6 || y == 0 || y == 6 || z == 0 || z == 6
                ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
    }
    static void identify(ServerWorld world, ChamberFrame frame, UUID chamberUuid) {
        if (!(world.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity controller)) {
            throw new IllegalStateException("入口 Controller 尚未建立");
        }
        controller.setChamberUuid(chamberUuid);
        controller.setInstanceKind(ChamberInstanceKind.PROJECTION);
        controller.markDirty();
    }
}
