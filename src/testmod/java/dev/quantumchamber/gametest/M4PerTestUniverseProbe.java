package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import org.slf4j.LoggerFactory;

/** 每個 M4 測試保有自己的真實 baseline；成功完成前必須通過 exact comparison。 */
final class M4PerTestUniverseProbe {
    private static final String NONCE = UUID.randomUUID().toString().replace("-", "");
    private static final Path DIRECTORY = Path.of("m4-per-test-" + ProcessHandle.current().pid() + "-" + NONCE);
    private static final Map<TestContext, Observation> ACTIVE = new IdentityHashMap<>();

    private M4PerTestUniverseProbe() { }

    static void begin(TestContext context, String name) {
        context.assertTrue(!ACTIVE.containsKey(context), "M4 per-test baseline 不可重複");
        var before = snapshot(context.getWorld().getServer());
        ACTIVE.put(context, new Observation(name, before, List.copyOf(M4CandidateTestAccess.runtime(context.getWorld().getServer()))));
        write(name + ".before.json", receipt(name, "BEFORE", before, before));
        LoggerFactory.getLogger("quantumchamber-testmod").info("M4_PER_TEST_BEGIN name={} receipt={}", name, DIRECTORY.resolve(name + ".before.json"));
    }

    static void complete(TestContext context) {
        var observation = ACTIVE.remove(context);
        context.assertTrue(observation != null, "每個 M4 成功路徑都必須先保存自己的 Universe baseline");
        var after = snapshot(context.getWorld().getServer());
        boolean snapshotSame = observation.before().equals(after);
        boolean handlesSame = observation.runtimeHandles().equals(M4CandidateTestAccess.runtime(context.getWorld().getServer()));
        boolean same = snapshotSame && handlesSame;
        var finalReceipt = new LinkedHashMap<>(receipt(observation.name(), same ? "PASS" : "FAIL", observation.before(), after));
        finalReceipt.put("snapshotExactUnchanged", snapshotSame);
        finalReceipt.put("runtimeHandlesExactUnchanged", handlesSame);
        write(observation.name() + ".json", finalReceipt);
        LoggerFactory.getLogger("quantumchamber-testmod").info("M4_PER_TEST_END name={} status={} receipt={}",
                observation.name(), finalReceipt.get("status"), DIRECTORY.resolve(observation.name() + ".json"));
        context.assertTrue(same, "M4 per-test Universe snapshot 改變：" + observation.name() + " before=" + observation.before() + " after=" + after);
        context.complete();
    }

    private static Map<String, Object> snapshot(MinecraftServer server) {
        try {
            var catalog = UniverseRegistryState.get(server);
            Path path = server.getSavePath(WorldSavePath.ROOT).resolve("data/quantumchamber_universes.dat");
            var flushed = catalog.flushedRecords().values().stream().map(record -> record.toNbt().toString()).sorted().toList();
            var handles = M4CandidateTestAccess.runtime(server).stream()
                    .map(handle -> (dev.quantumchamber.universe.UniverseRuntimeRegistry.OwnedWorld<?>) handle)
                    .map(handle -> Map.of("descriptor", handle.descriptor().toString(), "state", handle.state().name(),
                            "worldIdentity", System.identityHashCode(handle.world()))).toList();
            return Map.of("catalogExists", Files.exists(path), "catalogSha256", Files.exists(path) ? hash(Files.readAllBytes(path)) : "ABSENT",
                    "recordCount", catalog.records().size(), "recordsSha256", hash(catalog.writeNbt(new NbtCompound()).toString().getBytes(StandardCharsets.UTF_8)),
                    "flushedRecordsSha256", hash(new Gson().toJson(flushed).getBytes(StandardCharsets.UTF_8)), "runtimeHandles", handles,
                    "worldKeys", java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(), false)
                            .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList());
        } catch (Exception failure) { throw new IllegalStateException("M4 per-test Universe snapshot 讀取失敗", failure); }
    }

    private static Map<String, Object> receipt(String name, String status, Map<String, Object> before, Map<String, Object> after) {
        return Map.of("runtimeScope", "testmod-present", "testName", name, "status", status,
                "pid", ProcessHandle.current().pid(), "startupNonce", NONCE,
                "startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString(), "before", before, "after", after);
    }

    private static void write(String name, Map<String, Object> value) {
        try {
            Files.createDirectories(DIRECTORY);
            Files.writeString(DIRECTORY.resolve(name), new Gson().toJson(value), StandardOpenOption.CREATE_NEW);
        } catch (Exception failure) { throw new IllegalStateException("M4 per-test receipt 寫入失敗", failure); }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Observation(String name, Map<String, Object> before, List<?> runtimeHandles) { }
}
