package dev.quantumchamber.candidate;

import static org.junit.jupiter.api.Assertions.*;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.corridor.DoorKey.DoorWallSide;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.GenerationSettingsSnapshot;
import dev.quantumchamber.universe.UniverseProfileRegistry;
import dev.quantumchamber.universe.Minecraft121VanillaPresetProfileProvider;
import dev.quantumchamber.universe.ServerSettingsContext;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryLoader;
import net.minecraft.registry.ServerDynamicRegistryType;
import net.minecraft.resource.LifecycledResourceManagerImpl;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.VanillaDataPackProvider;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CandidateResolverTest {
    @TempDir Path root;
    private static final SourceFamilyRef SOURCE = new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD);

    @Test void policyOneOnlyFreezesRandom() throws Exception {
        var entropy = entropy();
        var policy = assertDoesNotThrow(() -> m6Policy(entropy.entropyFingerprint(), -1));
        try (var fixture = settingsFixture()) {
            var registry = builtinRegistry();
            var lease = new Lease(fixture.registries());
            var calls = new int[2];
            var resolver = new CandidateResolver(entropy, registry, () -> { calls[0]++; return lease; });
            for (boolean structures : new boolean[] {false, true}) {
                var source = registry.createSnapshot("quantumchamber:vanilla_amplified_v1", 1,
                        parameters("amplified", structures), lease);
                var candidate = assertInstanceOf(QuantumCandidate.New.class,
                        resolver.resolve(newKey(entropy), policy, List.of(), () -> {
                            calls[1]++; return new SourceGenerationSnapshot(42L, source);
                        }));
                var intent = candidate.intent();
                assertEquals("quantumchamber:random_vanilla_v1", intent.profileId());
                assertEquals(1, intent.policyVersion());
                assertEquals(0, intent.familyCapabilityVersion());
                assertEquals(42L, intent.sourceRootWorldSeed());
                assertNotEquals(42L, intent.rootWorldSeed());
                assertEquals(source.toNbt(), intent.sourceSettingsSnapshot().toNbt());
                var target = intent.targetSettingsSnapshot();
                assertEquals("quantumchamber:vanilla_normal_v1", target.providerId());
                assertEquals(parameters("normal", structures), target.parameters());
                assertDoesNotThrow(() -> registry.resolve(target, lease));
                for (var measured : List.of(source, target)) {
                    var encoded = new java.io.ByteArrayOutputStream();
                    net.minecraft.nbt.NbtIo.write(measured.toNbt(), new java.io.DataOutputStream(encoded));
                    System.out.println("T9_BUILTIN_SNAPSHOT_BYTES provider=" + measured.providerId()
                            + " structures=" + structures + " bytes=" + encoded.size());
                }
                var digest = java.security.MessageDigest.getInstance("SHA-256");
                digest.update("quantumchamber:m6:root-seed:v1".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update(intent.generationSeedMaterial().bytes());
                digest.update(intent.allocationToken().bytes());
                assertEquals(java.nio.ByteBuffer.wrap(digest.digest()).getLong(), intent.rootWorldSeed());
            }
            assertArrayEquals(new int[] {2, 2}, calls);
        }
    }

    @Test void m6NonNewAndLegacyNeverAcquireOrCapture() throws Exception {
        var entropy = entropy();
        var resolver = new CandidateResolver(entropy, builtinRegistry(), () -> { throw new AssertionError("提早 acquire"); });
        var pool = List.of(record(universe(1, 9), 0, true));
        var policy = assertDoesNotThrow(() -> m6Policy(entropy.entropyFingerprint(), 0));
        var derivation = new CandidateDerivation(entropy);
        boolean sourceSeen = false, existingSeen = false;
        for (int station = 0; station < 1000 && !(sourceSeen && existingSeen); station++) {
            var door = key(station); int bucket = derivation.bucket(door, 2);
            if (bucket >= 25) continue;
            var candidate = resolver.resolve(door, policy, pool, () -> { throw new AssertionError("提早 capture"); });
            if (bucket < 5) { assertInstanceOf(QuantumCandidate.Source.class, candidate); sourceSeen = true; }
            else { assertInstanceOf(QuantumCandidate.Existing.class, candidate); existingSeen = true; }
        }
        assertTrue(sourceSeen && existingSeen);
        assertInstanceOf(QuantumCandidate.New.class, resolver.resolve(key(37),
                policy(entropy.entropyFingerprint(), -1, SOURCE), List.of(), () -> { throw new AssertionError("legacy capture"); }));
    }

    @Test void m6NewRejectsStaleLeaseAndChangedSourceFingerprint() throws Exception {
        var entropy = entropy(); var policy = assertDoesNotThrow(() -> m6Policy(entropy.entropyFingerprint(), -1));
        try (var fixture = settingsFixture()) {
            var registry = builtinRegistry(); var lease = new Lease(fixture.registries());
            var source = registry.createSnapshot("quantumchamber:vanilla_normal_v1", 1, parameters("normal", false), lease);
            var resolver = new CandidateResolver(entropy, registry, () -> lease);
            var raw = source.toNbt(); var bad = raw.getByteArray("OutputFingerprint"); bad[0] ^= 1;
            raw.putByteArray("OutputFingerprint", bad);
            assertThrows(IllegalStateException.class, () -> resolver.resolve(newKey(entropy), policy, List.of(),
                    () -> new SourceGenerationSnapshot(42, GenerationSettingsSnapshot.fromNbt(raw))));
            lease.valid = false;
            assertThrows(IllegalStateException.class, () -> resolver.resolve(newKey(entropy), policy, List.of(),
                    () -> new SourceGenerationSnapshot(42, source)));
        }
    }

    @Test void m6PolicyRejectsFutureVersionsAndWrongCapability() {
        for (int version : new int[] {0, 2, 3, 4}) assertThrows(IllegalArgumentException.class,
                () -> new CandidatePolicySnapshot(Identifier.of("quantumchamber:m6_profiles_v1"), version, 2,
                        5, 20, 75, -1, 4096, bytes(), SOURCE, 0));
        for (int capability : new int[] {-1, 1, 2}) assertThrows(IllegalArgumentException.class,
                () -> new CandidatePolicySnapshot(Identifier.of("quantumchamber:m6_profiles_v1"), 1, 2,
                        5, 20, 75, -1, 4096, bytes(), SOURCE, capability));
    }

    @Test void randomRootSeedMatchesIndependentVectorAndCollisionCorrection() {
        var snapshot = structuralSnapshot("normal", false);
        // Python hashlib/struct 獨立預算，material 與 token 皆為 32 個零。
        var normal = assertDoesNotThrow(() -> new NewUniverseIntent(bytes(), bytes(), 1,
                "quantumchamber:random_vanilla_v1", 1, 42L, snapshot, snapshot, 0));
        assertEquals(2399119142747761084L, normal.rootWorldSeed());
        var collision = new NewUniverseIntent(bytes(), bytes(), 1, "quantumchamber:random_vanilla_v1", 1,
                2399119142747761084L, snapshot, snapshot, 0);
        assertEquals(-4648810344396122711L, collision.rootWorldSeed());
        assertEquals(normal, new NewUniverseIntent(bytes(), bytes(), 1, "quantumchamber:random_vanilla_v1", 1,
                42L, GenerationSettingsSnapshot.fromNbt(snapshot.toNbt()), GenerationSettingsSnapshot.fromNbt(snapshot.toNbt()), 0));
    }

    @Test void m6IntentRejectsWrongTargetAndUnsupportedPolicy() {
        var normal = structuralSnapshot("normal", false); var amplified = structuralSnapshot("amplified", false);
        assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(), bytes(), 1,
                "quantumchamber:random_vanilla_v1", 1, 42L, normal, amplified, 0));
        assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(), bytes(), 1,
                "quantumchamber:random_vanilla_v1", 1, 42L, normal, structuralSnapshot("normal", true), 0));
        assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(), bytes(), 2,
                "quantumchamber:random_vanilla_v1", 1, 42L, normal, normal, 0));
        assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(), bytes(), 1,
                "quantumchamber:random_vanilla_v1", 1, 42L, normal, normal, 1));
    }
    private static GenerationSettingsSnapshot structuralSnapshot(String preset, boolean structures) {
        var nbt = new NbtCompound(); nbt.putInt("SnapshotVersion", 1);
        nbt.putString("ProviderId", "quantumchamber:vanilla_" + preset + "_v1");
        nbt.putInt("ProviderVersion", 1); nbt.put("Parameters", parameters(preset, structures));
        nbt.putByteArray("OutputFingerprint", new byte[32]); return GenerationSettingsSnapshot.fromNbt(nbt);
    }

    private static CandidatePolicySnapshot m6Policy(CandidateBytes fingerprint, long watermark) {
        return new CandidatePolicySnapshot(Identifier.of("quantumchamber:m6_profiles_v1"),
                1, 2, 5, 20, 75, watermark, 4096, fingerprint, SOURCE, 0);
    }
    private static DoorKey newKey(CandidateEntropyState entropy) {
        var derivation = new CandidateDerivation(entropy);
        for (int i = 0; i < 1000; i++) if (derivation.bucket(key(i), 2) >= 25) return key(i);
        throw new AssertionError("fixture 找不到 NEW bucket");
    }
    private static NbtCompound parameters(String preset, boolean structures) {
        var nbt = new NbtCompound(); nbt.putString("OverworldPreset", preset);
        nbt.putByte("GenerateStructures", (byte) (structures ? 1 : 0)); return nbt;
    }
    private static UniverseProfileRegistry builtinRegistry() {
        var registry = new UniverseProfileRegistry();
        registry.register(new Minecraft121VanillaPresetProfileProvider("quantumchamber:vanilla_normal_v1"));
        registry.register(new Minecraft121VanillaPresetProfileProvider("quantumchamber:vanilla_amplified_v1"));
        registry.freeze(); return registry;
    }
    private record SettingsFixture(LifecycledResourceManagerImpl resources,
            DynamicRegistryManager.Immutable registries) implements AutoCloseable {
        @Override public void close() { resources.close(); }
    }
    private static SettingsFixture settingsFixture() {
        net.minecraft.SharedConstants.createGameVersion(); net.minecraft.Bootstrap.initialize();
        dev.quantumchamber.registry.ModBlocks.register();
        var resources = new LifecycledResourceManagerImpl(ResourceType.SERVER_DATA,
                List.of(VanillaDataPackProvider.createDefaultPack()));
        try {
            var combined = ServerDynamicRegistryType.createCombinedDynamicRegistries();
            return new SettingsFixture(resources, RegistryLoader.loadFromResource(resources,
                    combined.getPrecedingRegistryManagers(ServerDynamicRegistryType.WORLDGEN), RegistryLoader.DYNAMIC_REGISTRIES));
        } catch (RuntimeException | Error e) { resources.close(); throw e; }
    }
    private static final class Lease implements ServerSettingsContext {
        private final DynamicRegistryManager.Immutable registries;
        boolean valid = true;
        Lease(DynamicRegistryManager.Immutable registries) { this.registries = registries; }
        public void requireValid() { if (!valid) throw new IllegalStateException("CONTEXT_UNAVAILABLE"); }
        public DynamicRegistryManager.Immutable registries() { requireValid(); return registries; }
        public boolean sourceGenerateStructures() { throw new AssertionError("不得以 live bool 覆蓋 frozen Parameters"); }
    }

    @Test void candidateAndIntentKeepExactImmutableBytes() {
        byte[] input = CandidateDerivationTest.SECRET.clone();
        var id = new CandidateId(new CandidateBytes(input));
        var intent = new NewUniverseIntent(new CandidateBytes(input),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, new CandidateBytes(input));
        input[0] ^= 1;
        id.value().bytes()[1] ^= 1;
        intent.allocationToken().bytes()[2] ^= 1;
        intent.generationSeedMaterial().bytes()[3] ^= 1;
        assertArrayEquals(CandidateDerivationTest.SECRET, id.value().bytes());
        assertArrayEquals(CandidateDerivationTest.SECRET, intent.allocationToken().bytes());
        assertArrayEquals(CandidateDerivationTest.SECRET, intent.generationSeedMaterial().bytes());
        assertEquals(id, new QuantumCandidate.Source(id).candidateId());
        assertEquals(id, new QuantumCandidate.New(id, intent).candidateId());
        for (int length : new int[] {0, 31, 33}) {
            assertThrows(IllegalArgumentException.class, () -> new CandidateId(new CandidateBytes(new byte[length])));
            assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(new CandidateBytes(new byte[length]),
                    GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, bytes()));
            assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(),
                    GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, new CandidateBytes(new byte[length])));
        }
    }

    @Test void candidateVariantsAndIntentRejectMissingOrUnsupportedPayload() {
        var id = new CandidateId(bytes());
        assertThrows(NullPointerException.class, () -> new CandidateId(null));
        assertThrows(NullPointerException.class, () -> new QuantumCandidate.Source(null));
        assertThrows(NullPointerException.class, () -> new QuantumCandidate.Existing(id, null));
        assertThrows(NullPointerException.class, () -> new QuantumCandidate.Existing(null, universe(1, 1)));
        assertThrows(NullPointerException.class, () -> new QuantumCandidate.New(id, null));
        assertThrows(NullPointerException.class, () -> new NewUniverseIntent(null,
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, bytes()));
        assertThrows(NullPointerException.class, () -> new NewUniverseIntent(bytes(), null, 1, bytes()));
        assertThrows(NullPointerException.class, () -> new NewUniverseIntent(bytes(),
                GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, null));
        for (int version : new int[] {-1, 0, 2}) {
            assertThrows(IllegalArgumentException.class, () -> new NewUniverseIntent(bytes(),
                    GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, version, bytes()));
        }
    }

    @Test void sourceFamiliesValidateVanillaWorldRoleAndKeepCatalogIdentity() {
        var vanilla = new SourceFamilyRef.Vanilla(World.NETHER, DimensionRole.NETHER);
        assertEquals(World.NETHER, vanilla.worldKey());
        assertEquals(DimensionRole.NETHER, vanilla.role());
        var catalog = new SourceFamilyRef.Catalog(universe(1, 2), DimensionRole.END);
        assertEquals(universe(1, 2), catalog.universeId());
        assertEquals(DimensionRole.END, catalog.role());
        assertThrows(IllegalArgumentException.class, () -> new SourceFamilyRef.Vanilla(World.NETHER, DimensionRole.OVERWORLD));
        var custom = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("test:custom"));
        assertThrows(IllegalArgumentException.class, () -> new SourceFamilyRef.Vanilla(custom, DimensionRole.OVERWORLD));
        assertThrows(NullPointerException.class, () -> new SourceFamilyRef.Vanilla(null, DimensionRole.OVERWORLD));
        assertThrows(NullPointerException.class, () -> new SourceFamilyRef.Vanilla(World.OVERWORLD, null));
        assertThrows(NullPointerException.class, () -> new SourceFamilyRef.Catalog(null, DimensionRole.OVERWORLD));
        assertThrows(NullPointerException.class, () -> new SourceFamilyRef.Catalog(universe(1, 2), null));
    }

    @Test void policyRejectsUnknownIdentityVersionsWeightsWatermarkAndCap() {
        assertEquals(-1, policy(bytes(), -1, SOURCE).discoveryWatermark());
        assertEquals(Long.MAX_VALUE, policy(bytes(), Long.MAX_VALUE, SOURCE).discoveryWatermark());
        assertThrows(IllegalArgumentException.class, () -> new CandidatePolicySnapshot(Identifier.of("test:unknown"),
                1, 1, 5, 20, 75, 0, 16384, bytes(), SOURCE));
        // 各列獨立破壞 policyVersion、schema、三種權重、watermark 或 cap。
        long[][] invalid = {
            {2, 1, 5, 20, 75, 0, 16384}, {1, 2, 5, 20, 75, 0, 16384},
            {1, 1, 6, 19, 75, 0, 16384}, {1, 1, 5, 21, 74, 0, 16384},
            {1, 1, 5, 20, 74, 0, 16384}, {1, 1, -5, 30, 75, 0, 16384},
            {1, 1, 5, 20, 75, -2, 16384}, {1, 1, 5, 20, 75, 0, 16383},
            {1, 1, 5, 20, 75, 0, 16385}
        };
        for (long[] row : invalid) {
            assertThrows(IllegalArgumentException.class, () -> new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"),
                    (int) row[0], (int) row[1], (int) row[2], (int) row[3], (int) row[4], row[5], (int) row[6], bytes(), SOURCE));
        }
        assertThrows(NullPointerException.class, () -> policy(null, 0, SOURCE));
        assertThrows(NullPointerException.class, () -> policy(bytes(), 0, null));
    }

    @Test void fixedIndependentVectorsExerciseEveryBucketBoundary() throws Exception {
        var entropy = entropy();
        var resolver = new CandidateResolver(entropy);
        var policy = policy(entropy.entropyFingerprint(), 0, SOURCE);
        var pool = List.of(record(universe(1, 1), 0, true));
        // 由 Python hmac/struct 獨立預算；station 對應 bucket：93→0、111→4、25→5、5→24、37→25、11→99。
        for (long station : new long[] {93, 111}) {
            assertInstanceOf(QuantumCandidate.Source.class, resolver.resolve(key(station), policy, pool));
        }
        for (long station : new long[] {25, 5}) {
            assertEquals(universe(1, 1), assertInstanceOf(QuantumCandidate.Existing.class,
                    resolver.resolve(key(station), policy, pool)).universeId());
            assertInstanceOf(QuantumCandidate.New.class, resolver.resolve(key(station), policy, List.of()));
        }
        for (long station : new long[] {37, 11}) {
            assertInstanceOf(QuantumCandidate.New.class, resolver.resolve(key(station), policy, pool));
        }
    }

    @Test void canonicalPoolUsesUnsignedUuidHalvesRegardlessOfInsertionOrder() throws Exception {
        var entropy = entropy();
        var resolver = new CandidateResolver(entropy);
        var policy = policy(entropy.entropyFingerprint(), 1, SOURCE);
        var low = record(universe(0, Long.MAX_VALUE), 0, true);
        var middle = record(universe(0, Long.MIN_VALUE), 0, true);
        var high = record(universe(Long.MIN_VALUE, 0), 0, true);
        var pool = new ArrayList<>(List.of(high, low, middle));
        var first = resolver.resolve(key(5), policy, pool);
        Collections.reverse(pool);
        assertEquals(first, resolver.resolve(key(5), policy, pool));
        // station=5 的獨立 existing-index digest mod 3 = 2。
        assertEquals(high.universeId(), assertInstanceOf(QuantumCandidate.Existing.class, first).universeId());
        // ordinal 優先於 UUID；把同一 high 移到 ordinal 1 仍應最後。
        assertEquals(high.universeId(), assertInstanceOf(QuantumCandidate.Existing.class,
                resolver.resolve(key(5), policy, List.of(record(high.universeId(), 1, true), middle, low))).universeId());
        assertEquals(List.of(high, low, middle).reversed(), pool);
    }

    @Test void resolverRejectsIneligibleDuplicateSourceAndFutureRecordsEvenForSourceBucket() throws Exception {
        var entropy = entropy();
        var resolver = new CandidateResolver(entropy);
        var source = new SourceFamilyRef.Catalog(universe(1, 1), DimensionRole.OVERWORLD);
        var policy = policy(entropy.entropyFingerprint(), 0, source);
        var accepted = record(universe(1, 2), 0, true);
        for (var pool : List.of(List.of(record(universe(1, 3), 0, false)),
                List.of(record(universe(1, 3), 1, true)), List.of(record(source.universeId(), 0, true)),
                List.of(accepted, accepted), List.of(accepted, record(accepted.universeId(), 0, false)))) {
            assertThrows(IllegalArgumentException.class, () -> resolver.resolve(key(93), policy, pool));
        }
        assertThrows(NullPointerException.class, () -> resolver.resolve(null, policy, List.of()));
        assertThrows(NullPointerException.class, () -> resolver.resolve(key(93), null, List.of()));
        assertThrows(NullPointerException.class, () -> resolver.resolve(key(93), policy, null));
        assertThrows(IllegalArgumentException.class, () -> resolver.resolve(key(93), policy(bytes(), 0, SOURCE), List.of()));
    }

    @Test void newCandidateIdTokenAndSeedMatchIndependentVectorsAcrossInstances() throws Exception {
        var entropy = entropy();
        var policy = policy(entropy.entropyFingerprint(), -1, SOURCE);
        var first = assertInstanceOf(QuantumCandidate.New.class, new CandidateResolver(entropy).resolve(key(37), policy, List.of()));
        var second = new CandidateResolver(CandidateEntropyState.loadOrCreate(root, true)).resolve(key(37), policy, List.of());
        assertEquals(first, second);
        assertHex("74ef2a69d8f1e93a140f4b98fc37565e3b0c6592b3dedabb958a1d8f8bb8bb04", first.candidateId().value());
        assertHex("95bea6dba4dc5eb89c4b8679075ad58692c9e9ca33e12781d10f9ad6a7af0e0e", first.intent().allocationToken());
        assertHex("568946ca8c53882d826445b596bdf09027883ee9c2b1c85595324a03ef114fda", first.intent().generationSeedMaterial());
        assertEquals(GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, first.intent().profile());
        assertEquals(1, first.intent().profileVersion());
    }

    static CandidatePolicySnapshot policy(CandidateBytes fingerprint, long watermark, SourceFamilyRef source) {
        return new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"), 1, 1, 5, 20, 75,
                watermark, 16384, fingerprint, source);
    }

    static UniverseDiscoveryRecord record(UniverseId id, long ordinal, boolean eligible) {
        return new UniverseDiscoveryRecord(id, ordinal, 10, Optional.empty(), eligible);
    }

    static UniverseId universe(long most, long least) { return UniverseId.of(new UUID(most, least)); }
    private static CandidateBytes bytes() { return new CandidateBytes(new byte[32]); }
    private CandidateEntropyState entropy() throws Exception { return CandidateEntropyStateTest.fixedState(root, CandidateDerivationTest.SECRET); }
    private static DoorKey key(long station) { return new DoorKey(CandidateDerivationTest.SESSION, station, DoorWallSide.NEGATIVE_LATERAL); }
    private static void assertHex(String expected, CandidateBytes actual) { assertEquals(expected, HexFormat.of().formatHex(actual.bytes())); }
}
