package dev.quantumchamber.universe;

import java.util.HashMap;
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
        throw new IllegalStateException("M6 context/canonicalizer 尚未就緒");
    }
    public UniverseProfileProvider.ResolvedSettings resolve(GenerationSettingsSnapshot snapshot,
            ServerSettingsContext context) {
        throw new IllegalStateException("M6 context/canonicalizer 尚未就緒");
    }
}
