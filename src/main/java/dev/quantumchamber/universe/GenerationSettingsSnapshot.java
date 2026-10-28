package dev.quantumchamber.universe;

import java.io.OutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Identifier;

/** 單份來源或目標生成設定的不可變持久快照。 */
public final class GenerationSettingsSnapshot {
    private static final Set<String> KEYS = Set.of("SnapshotVersion", "ProviderId", "ProviderVersion", "Parameters", "OutputFingerprint");
    private static final Set<String> BUILTIN_PARAMETERS = Set.of("OverworldPreset", "GenerateStructures");
    private final NbtCompound value;

    private GenerationSettingsSnapshot(NbtCompound value) { this.value = value.copy(); }

    public static GenerationSettingsSnapshot fromNbt(NbtCompound nbt) {
        if (nbt == null || !nbt.getKeys().equals(KEYS)) throw new IllegalArgumentException("SettingsSnapshot 欄位不符");
        requireType(nbt, "SnapshotVersion", NbtElement.INT_TYPE);
        requireType(nbt, "ProviderId", NbtElement.STRING_TYPE);
        requireType(nbt, "ProviderVersion", NbtElement.INT_TYPE);
        requireType(nbt, "Parameters", NbtElement.COMPOUND_TYPE);
        requireType(nbt, "OutputFingerprint", NbtElement.BYTE_ARRAY_TYPE);
        if (nbt.getInt("SnapshotVersion") != 1 || nbt.getInt("ProviderVersion") <= 0) {
            throw new IllegalArgumentException("SettingsSnapshot 版本不符");
        }
        var id = nbt.getString("ProviderId");
        Identifier parsed;
        try { parsed = Identifier.of(id); }
        catch (RuntimeException e) { throw new IllegalArgumentException("ProviderId 非法", e); }
        if (!id.equals(parsed.toString()) || !id.contains(":")) throw new IllegalArgumentException("ProviderId 非 canonical");
        if (nbt.getByteArray("OutputFingerprint").length != 32) throw new IllegalArgumentException("OutputFingerprint 長度不符");
        var parameters = nbt.getCompound("Parameters");
        if (isBuiltin(id)) validateBuiltinParameters(parameters);
        if (encodedSize(nbt) > 512) throw new IllegalArgumentException("SettingsSnapshot 超過 512 bytes");
        return new GenerationSettingsSnapshot(nbt);
    }

    public static void validateBuiltinParameters(NbtCompound parameters) {
        if (parameters == null || !parameters.getKeys().equals(BUILTIN_PARAMETERS)) {
            throw new IllegalArgumentException("內建 Parameters 欄位不符");
        }
        requireType(parameters, "OverworldPreset", NbtElement.STRING_TYPE);
        requireType(parameters, "GenerateStructures", NbtElement.BYTE_TYPE);
        var preset = parameters.getString("OverworldPreset");
        if (!preset.equals("normal") && !preset.equals("amplified")) throw new IllegalArgumentException("未知 OverworldPreset");
        byte structures = parameters.getByte("GenerateStructures");
        if (structures != 0 && structures != 1) throw new IllegalArgumentException("GenerateStructures 必須是 0 或 1");
    }

    public static boolean isBuiltin(String id) {
        return id.equals("quantumchamber:vanilla_normal_v1") || id.equals("quantumchamber:vanilla_amplified_v1");
    }

    private static void requireType(NbtCompound nbt, String key, int type) {
        if (!nbt.contains(key, type)) throw new IllegalArgumentException(key + " 型別不符");
    }

    private static int encodedSize(NbtCompound nbt) {
        try {
            var bounded = new BoundedOutputStream();
            NbtIo.write(nbt, new DataOutputStream(bounded));
            return bounded.count;
        } catch (IOException e) { throw new IllegalArgumentException("SettingsSnapshot 無法編碼", e); }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private int count;
        @Override public void write(int value) throws IOException { add(1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { add(length); }
        private void add(int length) throws IOException {
            if (length < 0 || count > 512 - length) throw new IOException("SettingsSnapshot 超過 512 bytes");
            count += length;
        }
    }

    public String providerId() { return value.getString("ProviderId"); }
    public int providerVersion() { return value.getInt("ProviderVersion"); }
    public NbtCompound parameters() { return value.getCompound("Parameters").copy(); }
    public byte[] outputFingerprint() { return value.getByteArray("OutputFingerprint").clone(); }
    public NbtCompound toNbt() { return value.copy(); }
}
