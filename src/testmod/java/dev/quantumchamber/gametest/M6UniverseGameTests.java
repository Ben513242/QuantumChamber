package dev.quantumchamber.gametest;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/** 同 server 的真 schema2 backend 與 canonical／legacy 控制組。 */
public final class M6UniverseGameTests implements FabricGameTest {
    /** 獨立 resolver owner 控制組，不冒充正式玩家跨 JVM 功能鏈。 */
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void source_and_existing_do_not_resolve_unknown_preset(TestContext context) throws Exception {
        verifyLazyBranches(context, false);
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void existing_legacy_new_never_becomes_random(TestContext context) throws Exception {
        verifyLazyBranches(context, true);
        context.complete();
    }

    private static void verifyLazyBranches(TestContext context, boolean legacy) throws Exception {
        var root = context.getWorld().getServer().getSavePath(net.minecraft.util.WorldSavePath.ROOT);
        var entropy = dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, false);
        var policy = new dev.quantumchamber.candidate.CandidatePolicySnapshot(net.minecraft.util.Identifier.of(
                legacy ? "quantumchamber:m4_v1" : "quantumchamber:m6_profiles_v1"), 1, legacy ? 1 : 2,
                5, 20, 75, 0, legacy ? 16384 : 4096, entropy.entropyFingerprint(),
                new dev.quantumchamber.candidate.SourceFamilyRef.Vanilla(net.minecraft.world.World.OVERWORLD,
                        dev.quantumchamber.universe.DimensionRole.OVERWORLD));
        int[] sourceCalls = {0}, acquireCalls = {0};
        var resolver = new dev.quantumchamber.candidate.CandidateResolver(entropy,
                dev.quantumchamber.universe.Minecraft121SettingsLifecycle.registry(), () -> {
                    acquireCalls[0]++; throw new IllegalStateException("UNKNOWN_PRESET_ACQUIRE");
                });
        java.util.function.Supplier<dev.quantumchamber.candidate.SourceGenerationSnapshot> unknown = () -> {
            sourceCalls[0]++; throw new IllegalStateException("UNKNOWN_PRESET_OWNER_CONTROL");
        };
        var pool = java.util.List.of(new dev.quantumchamber.candidate.UniverseDiscoveryRecord(
                new dev.quantumchamber.universe.UniverseId(java.util.UUID.randomUUID()), 0, 0, java.util.Optional.empty(), true));
        var derive = dev.quantumchamber.candidate.CandidateEntropyState.class.getDeclaredMethod("derive",
                dev.quantumchamber.candidate.DerivationDomain.class, dev.quantumchamber.corridor.DoorKey.class, int.class);
        derive.setAccessible(true);
        boolean sourceSeen = false, existingSeen = false, newSeen = false;
        var sid = java.util.UUID.randomUUID();
        for (int station = 0; station < 4096 && !(sourceSeen && existingSeen && newSeen); station++) {
            var key = new dev.quantumchamber.corridor.DoorKey(sid, station,
                    dev.quantumchamber.corridor.DoorKey.DoorWallSide.NEGATIVE_LATERAL);
            var bytes = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.BUCKET, key, policy.candidateSchemaVersion());
            int bucket = new java.math.BigInteger(1, bytes.bytes()).mod(java.math.BigInteger.valueOf(100)).intValueExact();
            if (bucket < 5 && !sourceSeen) {
                context.assertTrue(resolver.resolve(key, policy, pool, unknown) instanceof dev.quantumchamber.candidate.QuantumCandidate.Source,
                        "SOURCE bucket 必須獨立於未知 preset");
                sourceSeen = true;
            } else if (bucket >= 5 && bucket < 25 && !existingSeen) {
                context.assertTrue(resolver.resolve(key, policy, pool, unknown) instanceof dev.quantumchamber.candidate.QuantumCandidate.Existing,
                        "EXISTING bucket 必須獨立於未知 preset");
                existingSeen = true;
            } else if (bucket >= 25 && !newSeen) {
                if (legacy) {
                    var candidate = (dev.quantumchamber.candidate.QuantumCandidate.New)resolver.resolve(key, policy, pool, unknown);
                    context.assertTrue(!candidate.intent().isM6(), "LEGACY_NEW_CHANGED：舊 frozen NEW 不升 random");
                    context.assertEquals(candidate, resolver.resolve(key, policy, pool, unknown), "舊 frozen候選再次解析完全一致");
                } else {
                    boolean rejected = false;
                    try { resolver.resolve(key, policy, pool, unknown); }
                    catch (IllegalStateException failure) { rejected = failure.getMessage().contains("UNKNOWN_PRESET_OWNER_CONTROL"); }
                    context.assertTrue(rejected, "NEW 正控制必須真正呼叫未知來源 supplier 並拒絕");
                }
                newSeen = true;
            }
        }
        context.assertTrue(sourceSeen && existingSeen && newSeen, "三個可控bucket均須實際命中");
        context.assertEquals(legacy ? 0 : 1, sourceCalls[0], "只有M6 NEW可求來源 supplier");
        context.assertEquals(0, acquireCalls[0], "SOURCE／EXISTING／legacy與未知NEW拒絕都不偷取得lease");
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void formal_entry_freezes_policy_one_on_flat(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1);
        context.runAtTick(2, () -> {
            fixture.power();
            var initial = fixture.record();
            context.assertTrue(initial != null, "真拉桿入場須同步寫入 ARMING");
            fixture.trustedTeardown(3, () -> {
                context.assertEquals(dev.quantumchamber.superposition.SessionState.ARMING, initial.state(), "正式 ARMING");
                var policy = initial.candidateContext().orElseThrow();
                context.assertTrue(policy.isM6(), "FORMAL_ENTRY_POLICY_ONE：新玩家 session 必須凍結 M6 policy1");
                context.assertEquals(1, policy.policyVersion(), "固定 policy1");
                context.assertEquals(0, policy.familyCapabilityVersion(), "固定 family0");
                context.assertTrue(initial.candidateLedger().isEmpty(), "ARMING 不偷產 snapshot");
                context.complete();
            });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_random", tickLimit=100000)
    public void flat_unknown_new_fails_closed_and_returns(TestContext context) {
        if (!com.sun.jna.Platform.isWindows()) { context.complete(); return; }
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1);
        var capture = new LogCapture("quantumchamber");
        context.runAtTick(2, () -> {
            fixture.power();
            var initial = fixture.record();
            context.assertTrue(initial != null, "未知 preset 仍允許正式 ARMING");
            awaitUnknownNew(context, fixture, capture, initial, 3, System.nanoTime() + 60_000_000_000L);
        });
    }

    /** 真 batch 的 NEW 失敗須帶分支／候選見證；看到拒因立即斷電，避免重入。 */
    private static void awaitUnknownNew(TestContext context, M2CorridorGameTests.NativeEntry fixture,
            LogCapture capture, dev.quantumchamber.persistence.SessionRecoveryRecord initial, int tick, long deadline) {
        var sid = initial.sessionUuid();
        context.runAtTick(tick, () -> {
            var failures = capture.entries().stream().filter(entry -> entry.message().contains(sid.toString())
                    && entry.message().contains("不支援來源 preset")).toList();
            if (failures.isEmpty()) {
                if (System.nanoTime() >= deadline) {
                    fixture.trustedTeardown(tick + 1, () -> {
                        try { context.assertTrue(false, "FLAT_NEW_REJECTION_MISSING：未觀察到正式 NEW 拒因"); }
                        finally { capture.close(); }
                    });
                } else awaitUnknownNew(context, fixture, capture, initial, tick + 1, deadline);
                return;
            }
            fixture.trustedTeardown(tick + 1, () -> {
                try {
                    var event = failures.stream().map(LogCapture.Entry::message)
                            .filter(message -> exactNewWitness(fixture.server, initial, message)).findFirst();
                    context.assertTrue(event.isPresent(), "FLAT_NEW_WITNESS_MISSING：真 batch 的同 SID／DoorKey／bucket／ID fingerprint 必須精確相符");
                    var message = event.orElseThrow();
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replace(sid.toString(), java.util.UUID.randomUUID().toString())),
                            "錯誤 SID 不可採計");
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replaceFirst("candidateIdSha256=[0-9a-f]{64}", "candidateIdSha256=" + "0".repeat(64))),
                            "錯誤 fingerprint 不可採計");
                    context.assertTrue(!exactNewWitness(fixture.server, initial, message.replaceFirst("bucket=[0-9]+", "bucket=100")),
                            "錯誤 bucket 不可採計");
                    var lease = dev.quantumchamber.universe.Minecraft121SettingsLifecycle.acquire(fixture.server);
                    context.assertTrue(dev.quantumchamber.universe.Minecraft121VanillaPresetProfileProvider.detectOverworldPreset(lease).isEmpty(),
                            "真 flat 不能辨識成 normal/amplified");
                    context.assertTrue(!dev.quantumchamber.persistence.SessionRecoveryState.get(fixture.server).flushedRecords().containsKey(sid),
                            "安全返還後 checked 移除 session");
                    fixture.assertInventory();
                    context.complete();
                } finally { capture.close(); }
            });
        });
    }

    /** 僅 testmod：以既存 entropy 的正式 derive 重算，不讀 secret、不輸出原 ID。 */
    private static boolean exactNewWitness(net.minecraft.server.MinecraftServer server,
            dev.quantumchamber.persistence.SessionRecoveryRecord initial, String message) {
        var pattern = java.util.regex.Pattern.compile("NEW_BUCKET bucket=([0-9]+) door=DoorKey\\[sessionUuid=([0-9a-f-]{36}), logicalStationIndex=(-?[0-9]+), wallSide=(NEGATIVE_LATERAL|POSITIVE_LATERAL)\\] candidateIdSha256=([0-9a-f]{64})");
        var match = pattern.matcher(message);
        if (!match.find() || !message.contains(" <- java.lang.IllegalStateException: 不支援來源 preset，禁止 NEW")
                || !initial.sessionUuid().toString().equals(match.group(2))) return false;
        try {
            var policy = initial.candidateContext().orElseThrow();
            var key = new dev.quantumchamber.corridor.DoorKey(initial.sessionUuid(), Long.parseLong(match.group(3)),
                    dev.quantumchamber.corridor.DoorKey.DoorWallSide.valueOf(match.group(4)));
            var root = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            var entropy = dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, true);
            var derive = dev.quantumchamber.candidate.CandidateEntropyState.class.getDeclaredMethod("derive",
                    dev.quantumchamber.candidate.DerivationDomain.class, dev.quantumchamber.corridor.DoorKey.class, int.class);
            derive.setAccessible(true);
            var bucketBytes = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.BUCKET, key, policy.candidateSchemaVersion());
            int bucket = new java.math.BigInteger(1, bucketBytes.bytes()).mod(java.math.BigInteger.valueOf(100)).intValueExact();
            var pool = dev.quantumchamber.candidate.UniverseDiscoveryState.load(root)
                    .eligibleSnapshot(policy.discoveryWatermark(), policy.sourceFamilyRef());
            if (bucket != Integer.parseInt(match.group(1)) || bucket < policy.sourceWeight()
                    || bucket < policy.sourceWeight() + policy.discoveredWeight() && !pool.isEmpty()) return false;
            var id = (dev.quantumchamber.candidate.CandidateBytes)derive.invoke(entropy,
                    dev.quantumchamber.candidate.DerivationDomain.CANDIDATE_ID, key, policy.candidateSchemaVersion());
            var expected = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(id.bytes()));
            return expected.equals(match.group(5));
        } catch (ReflectiveOperationException | java.io.IOException | java.security.NoSuchAlgorithmException failure) {
            throw new IllegalStateException("NEW witness 無法重核既存權威", failure);
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_seed_owner", tickLimit=1200)
    public void new_universe_native_seed_settings_and_random_sequences(TestContext context) throws Exception {
        M6RandomProbePhases.verifyGameTest(context.getWorld().getServer());
        context.complete();
    }
}
