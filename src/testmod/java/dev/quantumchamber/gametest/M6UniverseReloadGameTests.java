package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.Minecraft121SettingsLifecycle;
import dev.quantumchamber.universe.SeedPolicy;
import dev.quantumchamber.universe.UniverseDefinition;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseLifecycleService.MaterializeOutcome.Status;
import dev.quantumchamber.universe.UniverseRecord;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.util.EnumMap;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.resource.LifecycledResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

/**
 * 整分支審查 fix1：設定 context 在 reload 期間或失敗的 reload 之後不可用時，NEW 物化回 NOT_READY 讓 collapse 等待，
 * 不再走 backend 失敗而讓 server 停機、catalog 改成 DISABLED。
 * <p>不呼叫 {@code reloadResources}：M6 run 的 S1a observer 遇到非初次 {@code DataPackContents.refresh} 會丟 UNEXPECTED_RELOAD。
 * production lifecycle 只透過 Fabric 的 START／END_DATA_PACK_RELOAD 得知 reload，所以直接觸發這兩個事件走的是同一段 listener。
 * 真的 {@code /reload} 由人工清單驗證。本 batch 會讓所有 M6 取 lease 的路徑暫時不可用，單獨成批。
 */
public final class M6UniverseReloadGameTests implements FabricGameTest {
    private static final long SEED = 271828182845L;

    private enum Reload { IDLE, STARTED, FAILED_END }

    /** 照 {@code M6ProfileProbePhases.fixtureSource} 的配置步驟建立 schema2 normal U 並改為 EAGER_ENABLED，但不物化。 */
    private static UniverseId allocate(MinecraftServer server) {
        var id = UniverseId.of(UUID.randomUUID());
        var catalog = UniverseRegistryState.get(server); catalog.upgradeEnvelope(); catalog.flush(server);
        var parameters = new NbtCompound(); parameters.putString("OverworldPreset", "normal"); parameters.putBoolean("GenerateStructures", true);
        var snapshot = Minecraft121SettingsLifecycle.registry().createSnapshot("quantumchamber:vanilla_normal_v1", 1, parameters,
                Minecraft121SettingsLifecycle.acquire(server));
        var roles = new EnumMap<DimensionRole, UniverseWorldDescriptor>(DimensionRole.class);
        for (var role : DimensionRole.values())
            roles.put(role, new UniverseWorldDescriptor(UniverseKeys.world(id, role), role, null, SeedPolicy.EXPLICIT_ROOT_SEED_V1, 1));
        catalog.allocateM6(new UniverseRecord(new UniverseDefinition(2, id, catalog.records().size(), roles), DesiredAvailability.DISABLED,
                new UniverseRecord.Generation(SEED, NewUniverseIntent.SAME_SEED_PROFILE_ID, 1, snapshot.toNbt(), UniverseRecord.Provenance.NEW_M6, null)));
        catalog.flush(server); catalog.changeAvailability(id, DesiredAvailability.EAGER_ENABLED); catalog.flush(server);
        return id;
    }

    private static LifecycledResourceManager resources(MinecraftServer server) {
        if (!(server.getResourceManager() instanceof LifecycledResourceManager manager))
            throw new IllegalStateException("RELOAD_FIXTURE：server 的 resource manager 不是 LifecycledResourceManager");
        return manager;
    }

    private static void start(MinecraftServer server) {
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.invoker().startDataPackReload(server, resources(server));
    }

    private static void end(MinecraftServer server, boolean success) {
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().endDataPackReload(server, resources(server), success);
    }

    /** context 不可用時：不得呼叫 backend、不得停機、不得留下失敗紀錄或改動 catalog。 */
    private static void assertWaits(TestContext context, MinecraftServer server, UniverseId id, String label) {
        var outcome = UniverseLifecycleService.materializeAllocated(server, id);
        context.assertTrue(outcome.status() == Status.NOT_READY, label + "：預期 NOT_READY，實際 " + outcome.status() + " " + outcome.detail());
        context.assertTrue(server.isRunning(), label + "：server 被要求停止");
        for (var status : MaterializeResult.Status.values())
            context.assertTrue(UniverseLifecycleService.materializeFailures(server, id, status) == 0, label + "：留下 " + status + " 物化失敗紀錄");
        context.assertTrue(UniverseLifecycleService.resolveActive(server, id).isEmpty(), label + "：U 不應 ACTIVE");
        var record = UniverseRegistryState.get(server).records().get(id);
        context.assertTrue(record != null && record.desiredAvailability() == DesiredAvailability.EAGER_ENABLED,
                label + "：catalog 應仍為 EAGER_ENABLED，實際 " + (record == null ? "缺少" : record.desiredAvailability()));
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_reload_materialize", tickLimit=1200)
    public void new_universe_waits_while_settings_reload_is_pending_or_failed(TestContext context) {
        var server = context.getWorld().getServer();
        var id = allocate(server);
        var reload = Reload.IDLE;
        try {
            start(server); reload = Reload.STARTED;
            assertWaits(context, server, id, "RELOAD_PENDING");

            end(server, false); reload = Reload.FAILED_END;
            assertWaits(context, server, id, "RELOAD_FAILED");

            start(server); reload = Reload.STARTED;
            end(server, true); reload = Reload.IDLE;
            Minecraft121SettingsLifecycle.acquire(server).requireValid();
            var materialized = UniverseLifecycleService.materializeAllocated(server, id);
            context.assertTrue(materialized.status() == Status.MATERIALIZED,
                    "RELOAD_RECOVERED：預期 MATERIALIZED，實際 " + materialized.status() + " " + materialized.detail());
            var world = materialized.world().orElseThrow();
            context.assertTrue(world.getRegistryKey().equals(UniverseKeys.world(id, DimensionRole.OVERWORLD)), "RELOAD_RECOVERED：world key 不符");

            start(server); reload = Reload.STARTED;
            var active = UniverseLifecycleService.materializeAllocated(server, id);
            context.assertTrue(active.status() == Status.ALREADY_ACTIVE && active.world().orElseThrow() == world,
                    "RELOAD_ACTIVE：context 不可用時已物化的 U 應回 ALREADY_ACTIVE，實際 " + active.status() + " " + active.detail());
            end(server, true); reload = Reload.IDLE;
            context.complete();
        } finally {
            // 只補送需要的事件：RELOADING 時再送 START 會讓 lifecycle 進 POISONED，之後無法恢復。
            if (reload == Reload.STARTED) end(server, true);
            else if (reload == Reload.FAILED_END) { start(server); end(server, true); }
            try { Minecraft121SettingsLifecycle.acquire(server).requireValid(); }
            catch (IllegalStateException unrecovered) {
                throw new IllegalStateException("RELOAD_CLEANUP：設定 context 收尾後仍不可用，後續 M6 batch 可能受影響", unrecovered);
            }
        }
    }
}
