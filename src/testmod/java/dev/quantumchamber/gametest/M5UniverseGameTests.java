package dev.quantumchamber.gametest;

import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * plan 決策 6 的隔離 run：會配置或物化 Universe、或會留下 M5 record 的 GameTest 都放在這裡，只在 {@code gameTestM5} run
 * （{@code -Dquantumchamber.gametest.m5Only=true}，獨立的 fresh 世界 {@code run/gametest-m5}）執行。
 * <ul>
 * <li>batchId 一律是 {@code m5_universe_<名稱>}，每個 test 自己一個 batch。batch 依序執行，所以 {@link #begin}／{@link #complete}
 *     量到的 catalog 增量只屬於該 test。{@code M4LegacyGameTestFilterMixin} 在 default run 排除這個前綴，在 M5 run 只保留這個前綴。</li>
 * <li>每個 test 的第一個動作是 {@link #begin}：不在 M5 run 時，在碰 catalog 之前就失敗。結束時以 {@link #complete} 斷言自己的 catalog
 *     增量，並寫一行 {@code M5_UNIVERSE_CATALOG_DELTA}，由 {@code gates.ps1 -Mode M5GameTest} 逐 test 核對。M5 run 不註冊 default run 的
 *     {@code M4GameTestBoundaryProbe}，改用這個逐 test 的增量。</li>
 * <li>衛生規則：test 結束前，catalog 與四個 M5 權威都必須已收斂（catalog 不 dirty、current 等於 flushed，沒有未確認的寫入）。注入失敗的
 *     test 要等收斂之後才 complete；catalog 一直 dirty 的話，之後所有 NEW 配置都會一直 WAIT。{@link #begin} 也檢查開始時已收斂。</li>
 * </ul>
 */
public final class M5UniverseGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final Map<TestContext, Baseline> ACTIVE = new IdentityHashMap<>();
    private static final UUID SANITY_UNIVERSE = UUID.fromString("50000000-0000-4000-8000-0000000f3c01");

    /**
     * F3c sanity：M5 run 可以配置 catalog。比照 M3 的 DISABLED fixture（{@code M3UniverseLifecycleGameTests}）：配置一筆 OVERWORLD、
     * 改為 DISABLED（停服時不會期待一個沒物化的 world）、checked flush。不物化：runtime 物化 API 到 F4a 才有，直接呼叫 backend 會繞過
     * lifecycle。catalog 恰好多 1 筆，world 不被建立。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_sanity")
    public void sanity_allocates_one_disabled_record_without_materializing(TestContext context) {
        begin(context, "sanity_allocates_one_disabled_record_without_materializing");
        var server = context.getWorld().getServer();
        var catalog = UniverseRegistryState.get(server);
        var worldsBefore = worldKeys(server);
        // ordinal 與 M5 的 NEW 配置相同：flushed 的最大 ordinal＋1。
        long ordinal = catalog.flushedRecords().values().stream()
                .mapToLong(record -> record.definition().allocationOrdinal()).max().orElse(-1) + 1;
        var record = catalog.allocateOverworld(SANITY_UNIVERSE, ordinal);
        catalog.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        catalog.flush(server);
        var flushed = catalog.flushedRecords().get(record.definition().universeId());
        context.assertTrue(flushed != null && flushed.desiredAvailability() == DesiredAvailability.DISABLED, "sanity record 已以 DISABLED 寫出");
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        context.assertTrue(server.getWorld(descriptor.worldKey()) == null, "DISABLED record 不物化 world");
        context.assertEquals(worldsBefore, worldKeys(server), "world keys 不變");
        complete(context, 1);
    }

    /** 每個 M5 Universe test 的第一個動作：確認在 M5 run、權威已初始化而且已收斂，再記下 catalog baseline。 */
    private static void begin(TestContext context, String name) {
        // 決策 6：default run 不得執行這個類別的 test。被誤放進 default run 時，在碰 catalog 之前就失敗。
        context.assertTrue(Boolean.getBoolean("quantumchamber.gametest.m5Only"), "M5 Universe GameTest 只能在 gameTestM5 run 執行：" + name);
        context.assertTrue(!ACTIVE.containsKey(context), "M5 catalog baseline 不可重複：" + name);
        var server = context.getWorld().getServer();
        var authorities = CollapseAuthorities.get(server);
        context.assertTrue(authorities.initialized(), "M5 run 的世界必須有已初始化的 M5 authority：" + name);
        context.assertTrue(converged(authorities), "開始時 catalog 與 M5 權威必須已收斂（前一個 test 違反衛生規則）：" + name);
        var records = UniverseRegistryState.get(server).records();
        ACTIVE.put(context, new Baseline(name, records));
        LOGGER.info("M5_UNIVERSE_BEGIN name={} records={}", name, records.size());
    }

    /**
     * 每個 M5 Universe test 的最後一個動作：catalog 必須恰好多 {@code expectedDelta} 筆、開始時已有的 record 一筆都沒有改變，而且 catalog
     * 與四個 M5 權威都已收斂。結果寫成一行 {@code M5_UNIVERSE_CATALOG_DELTA}（gates.ps1 逐 test 核對），通過才 complete。
     */
    private static void complete(TestContext context, int expectedDelta) {
        var baseline = ACTIVE.remove(context);
        context.assertTrue(baseline != null, "缺少 begin 記下的 catalog baseline");
        var server = context.getWorld().getServer();
        var after = UniverseRegistryState.get(server).records();
        boolean converged = converged(CollapseAuthorities.get(server));
        boolean preserved = baseline.records().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(after.get(entry.getKey())));
        int actual = after.size() - baseline.records().size();
        boolean pass = converged && preserved && actual == expectedDelta;
        LOGGER.info("M5_UNIVERSE_CATALOG_DELTA name={} before={} after={} expected={} actual={} converged={} preserved={} status={}",
                baseline.name(), baseline.records().size(), after.size(), expectedDelta, actual, converged, preserved, pass ? "PASS" : "FAIL");
        context.assertTrue(pass, "M5 catalog 增量不符：" + baseline.name() + " expected=" + expectedDelta + " actual=" + actual
                + " converged=" + converged + " preserved=" + preserved);
        context.complete();
    }

    /** 四個權威都沒有未確認的寫入。CATALOG 由 catalog 本身推導：不 dirty，而且 current 等於 flushed。 */
    private static boolean converged(CollapseAuthorities authorities) {
        for (var authority : CollapseAuthorities.Authority.values()) {
            if (authorities.unconfirmed(authority)) return false;
        }
        return true;
    }

    private static List<String> worldKeys(MinecraftServer server) {
        return StreamSupport.stream(server.getWorlds().spliterator(), false)
                .map(world -> world.getRegistryKey().getValue().toString()).sorted().toList();
    }

    private record Baseline(String name, Map<UniverseId, UniverseRecord> records) { }
}
