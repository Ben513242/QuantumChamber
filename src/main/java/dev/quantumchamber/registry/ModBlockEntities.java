package dev.quantumchamber.registry;

import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlockEntities {
    public static final BlockEntityType<ChamberControllerBlockEntity> CHAMBER_CONTROLLER = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("quantumchamber", "chamber_controller"),
            BlockEntityType.Builder.create(ChamberControllerBlockEntity::new,
                    ModBlocks.CHAMBER_CONTROLLER).build(null));

    private ModBlockEntities() {
    }

    public static void register() {
        // Class loading performs the registration above.
    }
}
