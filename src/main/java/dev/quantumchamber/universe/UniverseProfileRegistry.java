package dev.quantumchamber.universe;

import java.util.HashMap;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;

/** 啟動期註冊、凍結與查詢 profile metadata。 */
public final class UniverseProfileRegistry {
    public record Metadata(String id, int version, UniverseProfileProvider.ParameterSchema parameterSchema,
            String adapterCapabilities) {}
    private record Key(String id, int version) {}
    private record Registration(UniverseProfileProvider provider, Metadata metadata) {}
    private final Map<Key, Registration> registrations = new HashMap<>();
    private boolean frozen;

    public synchronized void register(UniverseProfileProvider provider) {
        if (frozen) throw new IllegalStateException("Profile registry 已凍結");
        Objects.requireNonNull(provider, "provider");
        var id = provider.id();
        if (id == null || !id.contains(":")) throw new IllegalArgumentException("Profile id 非 namespaced");
        Identifier parsed;
        try { parsed = Identifier.of(id); }
        catch (RuntimeException e) { throw new IllegalArgumentException("Profile id 非法", e); }
        if (!id.equals(parsed.toString()) || provider.version() <= 0) throw new IllegalArgumentException("Profile metadata 非法");
        var schema = Objects.requireNonNull(provider.parameterSchema(), "parameterSchema");
        var capabilities = Objects.requireNonNull(provider.adapterCapabilities(), "adapterCapabilities");
        if (capabilities.isBlank()) throw new IllegalArgumentException("adapterCapabilities 不得空白");
        var key = new Key(id, provider.version());
        if (registrations.containsKey(key)) throw new IllegalArgumentException("Profile id/version 重複");
        registrations.put(key, new Registration(provider, new Metadata(id, key.version(), schema, capabilities)));
    }

    public synchronized void freeze() { frozen = true; }
    public synchronized boolean isFrozen() { return frozen; }
    public synchronized Optional<Metadata> metadata(String id, int version) {
        var entry = registrations.get(new Key(id, version));
        return entry == null ? Optional.empty() : Optional.of(entry.metadata());
    }
    public synchronized Optional<UniverseProfileProvider> find(String id, int version) {
        var entry = registrations.get(new Key(id, version));
        return entry == null ? Optional.empty() : Optional.of(entry.provider());
    }

    public GenerationSettingsSnapshot createSnapshot(String id, int version, NbtCompound parameters,
            ServerSettingsContext context) {
        var registration = requireBound(id, version);
        if (context == null) throw new IllegalStateException("CONTEXT_UNAVAILABLE");
        var input = validateParameters(registration, parameters);
        context.requireValid();
        var resolved = registration.provider().resolveGenerationSettings(input.copy(), context);
        var fingerprint = new Minecraft121ShallowSettingsCanonicalizer()
                .canonicalize(resolved, input, context).fingerprint();
        var nbt = new NbtCompound();
        nbt.putInt("SnapshotVersion", 1);
        nbt.putString("ProviderId", id);
        nbt.putInt("ProviderVersion", version);
        nbt.put("Parameters", input.copy());
        nbt.putByteArray("OutputFingerprint", fingerprint);
        var snapshot = GenerationSettingsSnapshot.fromNbt(nbt);
        context.requireValid();
        return snapshot;
    }
    public UniverseProfileProvider.ResolvedSettings resolve(GenerationSettingsSnapshot snapshot,
            ServerSettingsContext context) {
        Objects.requireNonNull(snapshot, "snapshot");
        var registration = requireBound(snapshot.providerId(), snapshot.providerVersion());
        if (context == null) throw new IllegalStateException("CONTEXT_UNAVAILABLE");
        var input = validateParameters(registration, snapshot.parameters());
        context.requireValid();
        var resolved = registration.provider().resolveGenerationSettings(input.copy(), context);
        var fingerprint = new Minecraft121ShallowSettingsCanonicalizer()
                .canonicalize(resolved, input, context).fingerprint();
        if (!Arrays.equals(fingerprint, snapshot.outputFingerprint()))
            throw new IllegalStateException("SETTINGS_FINGERPRINT_MISMATCH");
        context.requireValid();
        return resolved;
    }

    /** {@link #resolve} 中不需要 lease 的部分：provider 綁定與 Parameters 檢查，失敗訊息相同。 */
    public void requireBindable(GenerationSettingsSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateParameters(requireBound(snapshot.providerId(), snapshot.providerVersion()), snapshot.parameters());
    }

    private synchronized Registration requireBound(String id, int version) {
        if (!frozen) throw new IllegalStateException("Profile registry 尚未凍結");
        var registration = registrations.get(new Key(id, version));
        if (registration == null || version != 1 || !GenerationSettingsSnapshot.isBuiltin(id)
                || registration.provider().getClass() != Minecraft121VanillaPresetProfileProvider.class
                || !Minecraft121VanillaPresetProfileProvider.CAPABILITY.equals(registration.metadata().adapterCapabilities())
                || !id.equals(registration.provider().id()) || version != registration.provider().version()
                || !registration.metadata().adapterCapabilities().equals(registration.provider().adapterCapabilities()))
            throw new IllegalStateException("PROFILE_CLOSURE_UNBOUND");
        return registration;
    }

    private static NbtCompound validateParameters(Registration registration, NbtCompound parameters) {
        if (parameters == null) throw new IllegalArgumentException("Parameters 缺失");
        var copy = parameters.copy();
        registration.metadata().parameterSchema().validate(copy);
        return copy;
    }
}
