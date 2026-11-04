package dev.quantumchamber.universe;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.util.Identifier;
import net.minecraft.world.dimension.DimensionOptions;

/** ProviderVersion 1 的三 role 原生引用 id 快照，不展開 registry 元素內容。 */
public final class Minecraft121ShallowSettingsCanonicalizer {
    private static final String DOMAIN = "quantumchamber:m6:settings:v1";
    private static final Set<DimensionRole> REQUIRED = Set.of(DimensionRole.OVERWORLD, DimensionRole.NETHER, DimensionRole.END);
    private static final Set<String> ENVELOPE_KEYS = Set.of("Contract", "Roles", "GenerateStructures", "Parameters");

    public record Result(byte[] canonical, byte[] fingerprint) {
        public Result { canonical = canonical.clone(); fingerprint = fingerprint.clone(); }
        @Override public byte[] canonical() { return canonical.clone(); }
        @Override public byte[] fingerprint() { return fingerprint.clone(); }
    }

    public Result canonicalize(UniverseProfileProvider.ResolvedSettings resolved,
            NbtCompound parameters, ServerSettingsContext lease) {
        if (resolved == null || parameters == null || lease == null) throw new IllegalArgumentException("SHALLOW_INPUT_MISSING");
        GenerationSettingsSnapshot.validateBuiltinParameters(parameters);
        if (!resolved.roles().keySet().equals(REQUIRED) || resolved.roles().values().stream().anyMatch(java.util.Objects::isNull))
            throw new IllegalArgumentException("SHALLOW_ROLES_INVALID");
        if (resolved.generateStructures() != (parameters.getByte("GenerateStructures") == 1))
            throw new IllegalArgumentException("SHALLOW_STRUCTURES_MISMATCH");
        lease.requireValid();
        var roles = new NbtCompound();
        // 宣告順序故意不同於 canonical key 排序；Graph 的 NBT encoder 負責排序。
        roles.put("overworld", encodeRoleNbt(resolved.roles().get(DimensionRole.OVERWORLD), lease));
        roles.put("the_nether", encodeRoleNbt(resolved.roles().get(DimensionRole.NETHER), lease));
        roles.put("the_end", encodeRoleNbt(resolved.roles().get(DimensionRole.END), lease));
        var envelope = new NbtCompound();
        envelope.putString("Contract", Minecraft121VanillaPresetProfileProvider.CAPABILITY);
        envelope.put("Roles", roles);
        envelope.putByte("GenerateStructures", parameters.getByte("GenerateStructures"));
        envelope.put("Parameters", parameters.copy());
        byte[] canonical = canonicalEnvelope(envelope);
        lease.requireValid();
        return new Result(canonical, fingerprint(canonical));
    }

    /** 僅供純函式上限測試；不改 provider 或 lease 路徑。 */
    static byte[] canonicalEnvelope(NbtCompound envelope) {
        if (envelope == null || !envelope.getKeys().equals(ENVELOPE_KEYS))
            throw new IllegalArgumentException("SHALLOW_ENVELOPE_INVALID");
        return Minecraft121SettingsGraph.canonicalNbt(envelope, 8192);
    }

    static byte[] encodeRole(DimensionOptions option, ServerSettingsContext lease) {
        lease.requireValid();
        byte[] result = Minecraft121SettingsGraph.canonicalNbt(encodeRoleNbt(option, lease), 8192);
        lease.requireValid();
        return result;
    }

    private static NbtCompound encodeRoleNbt(DimensionOptions option, ServerSettingsContext lease) {
        if (option == null) throw new IllegalArgumentException("SHALLOW_ROLES_INVALID");
        var ops = RegistryOps.of(NbtOps.INSTANCE, lease.registries());
        NbtElement encoded = DimensionOptions.CODEC.encodeStart(ops, option).getOrThrow();
        if (!(encoded instanceof NbtCompound role)) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        requireId(role, "type");
        if (!role.contains("generator", NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        var generator = role.getCompound("generator");
        requireId(generator, "settings");
        if (!generator.contains("biome_source", NbtElement.COMPOUND_TYPE)) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        var biomeSource = generator.getCompound("biome_source");
        if (biomeSource.contains("biomes")) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        if (biomeSource.contains("preset")) requireId(biomeSource, "preset");
        return role;
    }

    private static void requireId(NbtCompound value, String key) {
        if (!value.contains(key, NbtElement.STRING_TYPE)) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        String id = value.getString(key);
        try {
            if (!Identifier.of(id).toString().equals(id)) throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT");
        } catch (RuntimeException error) { throw new IllegalArgumentException("SHALLOW_INLINE_ELEMENT", error); }
    }

    private static byte[] fingerprint(byte[] canonical) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(DOMAIN.getBytes(StandardCharsets.UTF_8));
            return digest.digest(canonical);
        } catch (NoSuchAlgorithmException error) { throw new AssertionError(error); }
    }
}
