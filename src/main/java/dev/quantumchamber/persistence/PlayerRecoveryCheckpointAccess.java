package dev.quantumchamber.persistence;
import java.util.Optional;
import net.minecraft.nbt.NbtElement;
public interface PlayerRecoveryCheckpointAccess {
    Optional<PlayerRecoveryCheckpoint> quantumchamber$getRecoveryCheckpoint();
    void quantumchamber$setRecoveryCheckpoint(PlayerRecoveryCheckpoint checkpoint);
    /** 只複製 namespaced marker，保留未知型別供原生 copyFrom 使用。 */
    Optional<NbtElement> quantumchamber$copyRecoveryCheckpointMarker();
}
