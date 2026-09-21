package dev.quantumchamber.gametest;

import com.google.gson.Gson;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/** default／legacy GameTest 的前後快照；此 receipt 明確屬於 testmod runtime。 */
final class M4GameTestBoundaryProbe {
    private static Map<String, Object> activeProof;
    private M4GameTestBoundaryProbe() { }

    static void register() {
        if (System.getProperty("fabric-api.gametest") == null) return;
        String nonce = UUID.randomUUID().toString().replace("-", "");
        var proof = new LinkedHashMap<String, Object>();
        activeProof = proof;
        proof.put("runtimeScope", "testmod-present");
        proof.put("pid", ProcessHandle.current().pid());
        proof.put("startupNonce", nonce);
        proof.put("startTimeUtc", ProcessHandle.current().info().startInstant().orElseThrow().toString());
        ServerLifecycleEvents.SERVER_STARTED.register(server -> proof.put("before", snapshot(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            proof.put("after", snapshot(server));
            Object expected = proof.getOrDefault("m3DisabledFixtureAfter", proof.get("before"));
            proof.put("status", proof.get("after").equals(expected) ? "PASS" : "FAIL");
            try {
                Files.writeString(Path.of("m4-boundary-" + nonce + ".json"), new Gson().toJson(proof), StandardOpenOption.CREATE_NEW);
            } catch (Exception failure) { throw new IllegalStateException("GameTest boundary receipt 寫入失敗", failure); }
        });
    }

    /** 對具名 M3 fixture 的唯一 catalog 變更留下前後 receipt，不改 fixture 行為。 */
    static void m3DisabledFixture(MinecraftServer server, boolean after) {
        if (activeProof == null) throw new AssertionError("缺少 suite boundary observer");
        var value = snapshot(server);
        if (!after) {
            if (!value.equals(activeProof.get("before"))) throw new AssertionError("M3 fixture 前已有其他 Universe 變更");
            activeProof.put("m3DisabledFixtureBefore", value);
        } else {
            var catalog = UniverseRegistryState.get(server);
            if (!activeProof.containsKey("m3DisabledFixtureBefore") || catalog.records().size() != 1
                    || !catalog.records().equals(catalog.flushedRecords()) || catalog.isDirty()) {
                throw new AssertionError("M3 fixture 必須是唯一 checked catalog 變更");
            }
            var record = catalog.records().values().iterator().next();
            if (!record.definition().universeId().value().equals(UUID.fromString("80000000-0000-4000-8000-000000000008"))
                    || record.definition().allocationOrdinal() != 8
                    || record.desiredAvailability() != dev.quantumchamber.universe.DesiredAvailability.DISABLED
                    || !value.get("worldKeys").equals(((Map<?, ?>) activeProof.get("before")).get("worldKeys"))) {
                throw new AssertionError("M3 disabled sentinel 的 exact 身分／world keys 不符");
            }
            activeProof.put("catalogDeltaOwner", "M3UniverseLifecycleGameTests.empty_bootstrap_and_disabled_record_do_not_create_worlds");
            activeProof.put("m3DisabledFixtureNbt", record.toNbt().toString());
            activeProof.put("m3DisabledFixtureAfter", value);
        }
    }

    private static Map<String, Object> snapshot(MinecraftServer server) {
        try {
            var catalog = UniverseRegistryState.get(server);
            Path path = server.getSavePath(WorldSavePath.ROOT).resolve("data/quantumchamber_universes.dat");
            byte[] records = catalog.writeNbt(new NbtCompound()).toString().getBytes(StandardCharsets.UTF_8);
            return Map.of("catalogExists", Files.exists(path), "catalogSha256", Files.exists(path) ? hash(Files.readAllBytes(path)) : "ABSENT",
                    "recordCount", catalog.records().size(), "recordsSha256", hash(records),
                    "worldKeys", java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(), false)
                            .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList());
        } catch (Exception failure) { throw new IllegalStateException("GameTest Universe boundary 讀取失敗", failure); }
    }

    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
