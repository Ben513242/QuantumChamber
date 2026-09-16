package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.quantumchamber.registry.ModBlocks;
import java.util.UUID;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ChamberControllerBlockEntityNbtTest {
    @Test
    void loadSyncPendingIsConsumedOnceAndNeverPersisted() {
        var original = controller();
        original.setPowerInitialized(true);
        original.markLoadSyncPending();
        assertTrue(original.consumeLoadSyncPending());
        assertFalse(original.consumeLoadSyncPending());
        original.markLoadSyncPending();
        NbtCompound encoded = original.createNbt(DynamicRegistryManager.EMPTY);
        assertFalse(encoded.contains("LoadSyncPending"));
        var restored = controller();
        restored.read(encoded, DynamicRegistryManager.EMPTY);
        assertTrue(restored.powerInitialized());
        assertFalse(restored.consumeLoadSyncPending());
    }

    private static final UUID CHAMBER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000008");

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
    }

    @Test
    void roundTripPreservesControllerFields() {
        ChamberControllerBlockEntity original = controller();
        original.setChamberUuid(CHAMBER_UUID);
        original.setInstanceKind(ChamberInstanceKind.PROJECTION);
        original.setChamberState(ChamberState.ARMED);
        original.setWasPowered(true);
        original.setPowerInitialized(true);

        NbtCompound encoded = original.createNbt(DynamicRegistryManager.EMPTY);
        ChamberControllerBlockEntity restored = controller();
        restored.read(encoded, DynamicRegistryManager.EMPTY);

        assertEquals(CHAMBER_UUID, restored.chamberUuid());
        assertEquals(ChamberInstanceKind.PROJECTION, restored.instanceKind());
        assertEquals(ChamberState.ARMED, restored.chamberState());
        assertEquals(true, restored.wasPowered());
        assertEquals(true, restored.powerInitialized());
    }

    @Test
    void unknownEnumsFallBackAndMissingUuidRemainsNull() {
        NbtCompound encoded = new NbtCompound();
        encoded.putString("InstanceKind", "UNKNOWN");
        encoded.putString("ChamberState", "UNKNOWN");

        ChamberControllerBlockEntity restored = controller();
        restored.read(encoded, DynamicRegistryManager.EMPTY);

        assertNull(restored.chamberUuid());
        assertEquals(ChamberInstanceKind.ORIGIN, restored.instanceKind());
        assertEquals(ChamberState.INVALID, restored.chamberState());
    }

    private static ChamberControllerBlockEntity controller() {
        return new ChamberControllerBlockEntity(new BlockPos(8, 64, 8), ModBlocks.CHAMBER_CONTROLLER.getDefaultState());
    }
}
