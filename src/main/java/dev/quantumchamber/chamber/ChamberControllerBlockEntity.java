package dev.quantumchamber.chamber;

import dev.quantumchamber.registry.ModBlockEntities;
import java.util.UUID;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ChamberControllerBlockEntity extends BlockEntity implements ChamberControllerPort {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private UUID chamberUuid;
    private ChamberInstanceKind instanceKind = ChamberInstanceKind.ORIGIN;
    private ChamberState state = ChamberState.INVALID;
    private boolean wasPowered;
    private boolean powerInitialized;
    // 門交易無法完整復原時，持久化禁止新啟動；僅成功整面門交易解除。
    private boolean activationBlocked;
    // 每次 runtime load 都重新標記；不能隨 NBT 跨載入保存。
    private boolean loadSyncPending;

    public void markLoadSyncPending() {
        loadSyncPending = true;
    }

    boolean loadSyncPending() {
        return loadSyncPending;
    }

    boolean consumeLoadSyncPending() {
        boolean pending = loadSyncPending;
        loadSyncPending = false;
        return pending;
    }

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

    @Override
    public boolean wasPowered() {
        return wasPowered;
    }

    @Override
    public void setWasPowered(boolean wasPowered) {
        this.wasPowered = wasPowered;
    }

    @Override
    public boolean powerInitialized() {
        return powerInitialized;
    }

    @Override
    public void setPowerInitialized(boolean powerInitialized) {
        this.powerInitialized = powerInitialized;
    }

    boolean activationBlocked() {
        return activationBlocked;
    }

    void setActivationBlocked(boolean blocked) {
        if (activationBlocked == blocked) return;
        activationBlocked = blocked;
        markDirty();
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.readNbt(nbt, registryLookup);
        chamberUuid = nbt.containsUuid("ChamberUuid") ? nbt.getUuid("ChamberUuid") : null;
        instanceKind = enumValue(nbt.getString("InstanceKind"), ChamberInstanceKind.ORIGIN, "InstanceKind");
        state = enumValue(nbt.getString("ChamberState"), ChamberState.INVALID, "ChamberState");
        wasPowered = nbt.getBoolean("WasPowered");
        powerInitialized = nbt.getBoolean("PowerInitialized");
        activationBlocked = nbt.getBoolean("ActivationBlocked");
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
        super.writeNbt(nbt, registryLookup);
        if (chamberUuid != null) nbt.putUuid("ChamberUuid", chamberUuid);
        nbt.putString("InstanceKind", instanceKind.name());
        nbt.putString("ChamberState", state.name());
        nbt.putBoolean("WasPowered", wasPowered);
        nbt.putBoolean("PowerInitialized", powerInitialized);
        nbt.putBoolean("ActivationBlocked", activationBlocked);
    }

    private static <E extends Enum<E>> E enumValue(String value, E fallback, String key) {
        if (value == null || value.isEmpty()) return fallback;
        try {
            @SuppressWarnings("unchecked")
            Class<E> type = (Class<E>) fallback.getDeclaringClass();
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid {} '{}' in chamber controller NBT; using {}", key, value, fallback);
            return fallback;
        }
    }
}
