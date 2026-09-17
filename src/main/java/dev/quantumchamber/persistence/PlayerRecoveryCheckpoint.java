package dev.quantumchamber.persistence;
import java.util.UUID;
import java.util.Objects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
public record PlayerRecoveryCheckpoint(UUID sessionUuid, AppliedPolicy appliedPolicy) {
    public static final String NBT_KEY = "quantumchamber:recovery_checkpoint";
    public enum AppliedPolicy { RESTORE_ENTRY, KEEP_CURRENT }
    public PlayerRecoveryCheckpoint {
        Objects.requireNonNull(sessionUuid, "sessionUuid"); Objects.requireNonNull(appliedPolicy, "appliedPolicy");
    }
    public NbtCompound toNbt() {
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", 1); nbt.putUuid("SessionUuid", sessionUuid);
        nbt.putString("AppliedPolicy", appliedPolicy.name()); return nbt;
    }
    public static PlayerRecoveryCheckpoint fromNbt(NbtElement raw) {
        if (!(raw instanceof NbtCompound nbt)) throw new IllegalArgumentException("checkpoint 必須為 compound");
        SessionRecoveryRecord.requireType(nbt, "SchemaVersion", NbtElement.INT_TYPE);
        if (nbt.getInt("SchemaVersion") != 1) throw new IllegalArgumentException("未知 checkpoint schema");
        return new PlayerRecoveryCheckpoint(SessionRecoveryRecord.uuid(nbt, "SessionUuid"),
                AppliedPolicy.valueOf(SessionRecoveryRecord.string(nbt, "AppliedPolicy")));
    }
}
