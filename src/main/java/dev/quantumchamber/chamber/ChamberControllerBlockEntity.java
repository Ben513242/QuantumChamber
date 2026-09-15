package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlockEntities;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

public final class ChamberControllerBlockEntity extends BlockEntity {
    private UUID chamberUuid;
    private ChamberInstanceKind instanceKind = ChamberInstanceKind.ORIGIN;
    private ChamberState state = ChamberState.INVALID;
    private boolean wasPowered;
    private boolean powerInitialized;

    public ChamberControllerBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.CHAMBER_CONTROLLER, pos, state);
    }

    public UUID chamberUuid() {
        return chamberUuid;
    }

    public void setChamberUuid(UUID chamberUuid) {
        this.chamberUuid = chamberUuid;
    }

    public ChamberInstanceKind instanceKind() {
        return instanceKind;
    }

    public void setInstanceKind(ChamberInstanceKind instanceKind) {
        this.instanceKind = instanceKind;
    }

    public ChamberState chamberState() {
        return state;
    }

    public void setChamberState(ChamberState state) {
        this.state = state;
    }

    public boolean wasPowered() {
        return wasPowered;
    }

    public void setWasPowered(boolean wasPowered) {
        this.wasPowered = wasPowered;
    }

    public boolean powerInitialized() {
        return powerInitialized;
    }

    public void setPowerInitialized(boolean powerInitialized) {
        this.powerInitialized = powerInitialized;
    }
}
