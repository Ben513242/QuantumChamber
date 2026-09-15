package dev.quantumchamber.registry;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModItems {
    public static final Item CHAMBER_CONTROLLER = register("chamber_controller", ModBlocks.CHAMBER_CONTROLLER);
    public static final Item QUANTUM_BULKHEAD = register("quantum_bulkhead", ModBlocks.QUANTUM_BULKHEAD);

    private ModItems() {
    }

    public static void register() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
            entries.add(CHAMBER_CONTROLLER);
            entries.add(QUANTUM_BULKHEAD);
        });
    }

    private static Item register(String path, net.minecraft.block.Block block) {
        return Registry.register(Registries.ITEM, Identifier.of("quantumchamber", path),
                new BlockItem(block, new Item.Settings()));
    }
}
