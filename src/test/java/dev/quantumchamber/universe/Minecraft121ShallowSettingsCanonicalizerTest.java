package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtByte;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryOps;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.dimension.DimensionOptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class Minecraft121ShallowSettingsCanonicalizerTest {
    @BeforeAll static void bootstrap() { Minecraft121WorldgenRegistryFixture.bootstrap(); }

    private static final class Lease implements ServerSettingsContext {
        final DynamicRegistryManager.Immutable registries;
        final boolean sourceStructures;
        int checks;
        int invalidateAt = Integer.MAX_VALUE;
        Lease(DynamicRegistryManager.Immutable registries, boolean sourceStructures) {
            this.registries = registries; this.sourceStructures = sourceStructures;
        }
        @Override public void requireValid() { if (++checks >= invalidateAt) throw new IllegalStateException("CONTEXT_UNAVAILABLE"); }
        @Override public DynamicRegistryManager.Immutable registries() { requireValid(); return registries; }
        @Override public boolean sourceGenerateStructures() { requireValid(); return sourceStructures; }
    }
    private static NbtCompound parameters(String preset, boolean structures) {
        var result = new NbtCompound(); result.putString("OverworldPreset", preset);
        result.putByte("GenerateStructures", (byte)(structures ? 1 : 0)); return result;
    }
    private static Minecraft121ShallowSettingsCanonicalizer.Result result(Lease lease, String preset, boolean structures) {
        var params = parameters(preset, structures);
        var provider = new Minecraft121VanillaPresetProfileProvider("normal".equals(preset)
                ? Minecraft121VanillaPresetProfileProvider.NORMAL_ID : Minecraft121VanillaPresetProfileProvider.AMPLIFIED_ID);
        return new Minecraft121ShallowSettingsCanonicalizer().canonicalize(provider.resolveGenerationSettings(params, lease), params, lease);
    }
    private static String ascii(byte[] bytes) { return new String(bytes, StandardCharsets.ISO_8859_1); }
    private static void referenceText(DataOutputStream out, String text) throws IOException {
        var bytes = text.getBytes(StandardCharsets.UTF_8); out.writeInt(bytes.length); out.write(bytes);
    }
    private static void referenceNbt(DataOutputStream out, NbtElement element) throws IOException {
        out.writeByte(element.getType());
        switch (element.getType()) {
            case NbtElement.BYTE_TYPE -> out.writeByte(((NbtByte)element).byteValue());
            case NbtElement.STRING_TYPE -> referenceText(out, element.asString());
            case NbtElement.COMPOUND_TYPE -> {
                var compound = (NbtCompound)element;
                var keys = new java.util.ArrayList<>(compound.getKeys()); keys.sort(String::compareTo);
                out.writeInt(keys.size());
                for (String key : keys) { referenceText(out, key); referenceNbt(out, compound.get(key)); }
            }
            default -> throw new AssertionError("測試預期的原版 role 出現新 NBT 型別：" + element.getType());
        }
    }
    private static byte[] independentExpected(NbtCompound value) {
        try {
            var bytes = new ByteArrayOutputStream();
            referenceNbt(new DataOutputStream(bytes), value);
            return bytes.toByteArray();
        } catch (IOException error) { throw new AssertionError(error); }
    }
    private static byte[] independentEnvelope(UniverseProfileProvider.ResolvedSettings resolved,
            NbtCompound params, DynamicRegistryManager.Immutable registries) {
        var roles = new NbtCompound();
        var ops = RegistryOps.of(NbtOps.INSTANCE, registries);
        roles.put("the_nether", DimensionOptions.CODEC.encodeStart(ops, resolved.roles().get(DimensionRole.NETHER)).getOrThrow());
        roles.put("overworld", DimensionOptions.CODEC.encodeStart(ops, resolved.roles().get(DimensionRole.OVERWORLD)).getOrThrow());
        roles.put("the_end", DimensionOptions.CODEC.encodeStart(ops, resolved.roles().get(DimensionRole.END)).getOrThrow());
        var envelope = new NbtCompound();
        envelope.put("Roles", roles); envelope.put("Parameters", params.copy());
        envelope.putByte("GenerateStructures", params.getByte("GenerateStructures"));
        envelope.putString("Contract", Minecraft121VanillaPresetProfileProvider.CAPABILITY);
        return independentExpected(envelope);
    }

    @Test void shallowCanonicalIsDeterministicAcrossTwoRegistryLoads() {
        try (var a = Minecraft121WorldgenRegistryFixture.load(); var b = Minecraft121WorldgenRegistryFixture.load()) {
            var first = result(new Lease(a.registries(), true), "normal", true);
            var second = result(new Lease(b.registries(), true), "normal", true);
            assertArrayEquals(first.canonical(), second.canonical()); assertArrayEquals(first.fingerprint(), second.fingerprint());
        }
    }
    @Test void shallowFingerprintDiffersBetweenNormalAndAmplified() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            assertFalse(Arrays.equals(result(new Lease(f.registries(), true), "normal", true).fingerprint(),
                    result(new Lease(f.registries(), true), "amplified", true).fingerprint()));
        }
    }
    @Test void shallowOverworldPresetMapsToExpectedSettingsId() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            String normal = ascii(result(new Lease(f.registries(), true), "normal", true).canonical());
            String amplified = ascii(result(new Lease(f.registries(), true), "amplified", true).canonical());
            assertTrue(normal.contains("minecraft:overworld")); assertFalse(normal.contains("minecraft:amplified"));
            assertTrue(amplified.contains("minecraft:amplified"));
            for (String value : new String[] {normal, amplified}) {
                assertTrue(value.contains("minecraft:nether")); assertTrue(value.contains("minecraft:end"));
            }
        }
    }
    @Test void shallowFingerprintDiffersWhenGenerateStructuresFlips() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var first = result(new Lease(f.registries(), true), "normal", true);
            var second = result(new Lease(f.registries(), true), "normal", false);
            assertFalse(Arrays.equals(first.fingerprint(), second.fingerprint()));
            var params = parameters("normal", false);
            var resolved = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID)
                    .resolveGenerationSettings(params, new Lease(f.registries(), true));
            assertArrayEquals(independentEnvelope(resolved, params, f.registries()), second.canonical());
        }
    }
    @Test void shallowEnvelopeContractIsExactShallowV1() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            assertTrue(ascii(result(new Lease(f.registries(), true), "normal", true).canonical())
                    .contains(Minecraft121VanillaPresetProfileProvider.CAPABILITY));
        }
    }
    @Test void shallowEnvelopeHasExactFourKeys() {
        var envelope = new NbtCompound(); envelope.putString("Contract", "x"); envelope.put("Roles", new NbtCompound());
        envelope.putByte("GenerateStructures", (byte)1); envelope.put("Parameters", new NbtCompound());
        assertDoesNotThrow(() -> Minecraft121ShallowSettingsCanonicalizer.canonicalEnvelope(envelope));
        envelope.putString("Extra", "x");
        assertThrows(IllegalArgumentException.class, () -> Minecraft121ShallowSettingsCanonicalizer.canonicalEnvelope(envelope));
    }
    @Test void shallowRolesKeysAreCanonicallySortedNotDeclarationOrder() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var params = parameters("normal", true);
            var lease = new Lease(f.registries(), true);
            var resolved = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID)
                    .resolveGenerationSettings(params, lease);
            assertArrayEquals(independentEnvelope(resolved, params, f.registries()),
                    new Minecraft121ShallowSettingsCanonicalizer().canonicalize(resolved, params, lease).canonical());
            System.out.println("SHALLOW_ROLE_LENGTH overworld=" + Minecraft121ShallowSettingsCanonicalizer.encodeRole(
                    resolved.roles().get(DimensionRole.OVERWORLD), lease).length + " nether="
                    + Minecraft121ShallowSettingsCanonicalizer.encodeRole(resolved.roles().get(DimensionRole.NETHER), lease).length
                    + " end=" + Minecraft121ShallowSettingsCanonicalizer.encodeRole(resolved.roles().get(DimensionRole.END), lease).length);
        }
    }
    @Test void shallowRejectsUnknownOverworldPreset() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var params = parameters("large_biomes", true);
            assertThrows(IllegalArgumentException.class, () -> new Minecraft121VanillaPresetProfileProvider(
                    Minecraft121VanillaPresetProfileProvider.NORMAL_ID).resolveGenerationSettings(params, new Lease(f.registries(), true)));
        }
    }
    @Test void shallowRejectsMissingRoleOrNullOptions() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var lease = new Lease(f.registries(), true); var params = parameters("normal", true);
            var roles = new EnumMap<>(new Minecraft121VanillaPresetProfileProvider(
                    Minecraft121VanillaPresetProfileProvider.NORMAL_ID).resolveGenerationSettings(params, lease).roles());
            roles.remove(DimensionRole.END);
            assertThrows(IllegalArgumentException.class, () -> new Minecraft121ShallowSettingsCanonicalizer()
                    .canonicalize(new UniverseProfileProvider.ResolvedSettings(roles, true), params, lease));
            roles.put(DimensionRole.END, null);
            assertThrows(RuntimeException.class, () -> new UniverseProfileProvider.ResolvedSettings(roles, true));
        }
    }
    @Test void shallowRequiresValidLeaseBeforeAndAfterEncode() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var params = parameters("normal", true);
            var resolved = new Minecraft121VanillaPresetProfileProvider(Minecraft121VanillaPresetProfileProvider.NORMAL_ID)
                    .resolveGenerationSettings(params, new Lease(f.registries(), true));
            var before = new Lease(f.registries(), true); before.invalidateAt = 1;
            assertThrows(IllegalStateException.class, () -> new Minecraft121ShallowSettingsCanonicalizer().canonicalize(resolved, params, before));
            var after = new Lease(f.registries(), true); after.invalidateAt = 5;
            assertThrows(IllegalStateException.class, () -> new Minecraft121ShallowSettingsCanonicalizer().canonicalize(resolved, params, after));
        }
    }
    @Test void shallowRejectsDirectEntryRole() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            var lease = new Lease(f.registries(), true); var params = parameters("normal", true);
            var roles = new EnumMap<>(new Minecraft121VanillaPresetProfileProvider(
                    Minecraft121VanillaPresetProfileProvider.NORMAL_ID).resolveGenerationSettings(params, lease).roles());
            var old = roles.get(DimensionRole.OVERWORLD);
            roles.put(DimensionRole.OVERWORLD, new DimensionOptions(RegistryEntry.of(old.dimensionTypeEntry().value()), old.chunkGenerator()));
            var error = assertThrows(IllegalArgumentException.class, () -> new Minecraft121ShallowSettingsCanonicalizer()
                    .canonicalize(new UniverseProfileProvider.ResolvedSettings(roles, true), params, lease));
            assertEquals("SHALLOW_INLINE_ELEMENT", error.getMessage());
        }
    }
    @Test void shallowEncodesRegistryReferencesAsIdsOnly() {
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            String canonical = ascii(result(new Lease(f.registries(), true), "amplified", true).canonical());
            assertFalse(canonical.contains("noise_router")); assertFalse(canonical.contains("sea_level"));
            assertFalse(canonical.contains("spawn_target"));
        }
    }
    @Test void shallowRejectsCanonicalOverLimit() {
        var envelope = new NbtCompound(); envelope.putString("Contract", "x".repeat(9000));
        envelope.put("Roles", new NbtCompound()); envelope.putByte("GenerateStructures", (byte)1);
        envelope.put("Parameters", new NbtCompound());
        assertThrows(RuntimeException.class, () -> Minecraft121ShallowSettingsCanonicalizer.canonicalEnvelope(envelope));
    }
    // 已存檔 U 的 snapshot 存著 OutputFingerprint，這些值一變，舊世界開啟時就會 FP 不符；Ubuntu 與 Windows CI 各自比對同一組常數。
    // GenerateStructures=1 的兩個值與 run-all-c3f5f470 原生鏈 receipt 的 target/sourceFingerprint 相同（真 server）；=0 的兩個值取自 1ead254 的實作輸出。
    @Test void shallowFingerprintsMatchPinnedValues() {
        var expected = new TreeMap<String, String>(Map.of(
                "amplified/0", "535d2fdff94fcd1191aee59bc445d14001db527c88eb505af4544650b06a8985",
                "amplified/1", "102962da118dad66a5777ceeabd8590041862eea8f78a68f22725e6e2cdadf40",
                "normal/0", "e9f6ad318e7df291f7fac787b3987c478e41bbc5f8efb5facb4ea50e6efb7094",
                "normal/1", "4bdb138addb5fca11d0cce76c69d08d7d1598b56a6088286debb86202ef5f4b5"));
        var actual = new TreeMap<String, String>();
        try (var f = Minecraft121WorldgenRegistryFixture.load()) {
            for (String preset : new String[] {"normal", "amplified"}) for (boolean structures : new boolean[] {true, false})
                actual.put(preset + "/" + (structures ? 1 : 0),
                        HexFormat.of().formatHex(result(new Lease(f.registries(), true), preset, structures).fingerprint()));
        }
        assertEquals(expected, actual);
    }
    @Test void shallowFingerprintUnchangedWhenNoiseSettingsContentChanges() {
        try (var normal = Minecraft121WorldgenRegistryFixture.load(); var changed = Minecraft121WorldgenRegistryFixture.loadAmplifiedOverride()) {
            assertNotEquals(normal.amplifiedSeaLevel(), changed.amplifiedSeaLevel());
            var first = result(new Lease(normal.registries(), true), "amplified", true);
            var second = result(new Lease(changed.registries(), true), "amplified", true);
            assertArrayEquals(first.canonical(), second.canonical()); assertArrayEquals(first.fingerprint(), second.fingerprint());
        }
    }
}
