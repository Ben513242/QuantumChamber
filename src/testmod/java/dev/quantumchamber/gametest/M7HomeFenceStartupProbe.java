package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberRegistryState;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;

/**
 * T4 Phase A fix2（審查 M5）：開服掃描完成前，原艙停用防線的 port 必須已 fail-closed（M7 spec §9.1 :246）。
 * <p>只在 M7 GameTest run（{@code -Dquantumchamber.test.m7Home=true}）註冊。在 {@code SERVER_STARTED} 的預設 phase 之前觀察一次：此時世界已載入，
 * {@code HomeRootFence} 的開服掃描（預設 phase）尚未執行。只讀：以 {@code ChamberRegistry.setEnabled} 與維護入口用的同一個 port，查詢一個不存在的艙能否啟用，
 * 不寫入任何資料。結果由 {@code M7HomeClosureGameTests.fence_rejects_enable_before_startup_scan} 斷言。
 */
public final class M7HomeFenceStartupProbe implements ModInitializer {
    /** 不屬於任何艙的固定 UUID：只用來詢問 port 是否放行。 */
    static final UUID PROBE_CHAMBER = UUID.fromString("7a4f0f2e-5d1b-4c39-9e0a-0b7c2f6d4e11");
    private static final Identifier BEFORE_FENCE = Identifier.of("quantumchamber-test", "m7_before_home_fence");
    private static final Map<MinecraftServer, String> OBSERVED = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        if (!Boolean.getBoolean("quantumchamber.test.m7Home")) return;
        ServerLifecycleEvents.SERVER_STARTED.addPhaseOrdering(BEFORE_FENCE, Event.DEFAULT_PHASE);
        ServerLifecycleEvents.SERVER_STARTED.register(BEFORE_FENCE, server -> OBSERVED.put(server, observe(server)));
        ServerLifecycleEvents.SERVER_STOPPED.register(OBSERVED::remove);
    }

    private static String observe(MinecraftServer server) {
        try {
            var registry = ChamberRegistryState.get(server).registry();
            return ChamberCollapsePort.roots().permitsEnable(registry, PROBE_CHAMBER) ? "PERMITTED" : "REJECTED";
        } catch (RuntimeException failure) {
            return "ERROR " + failure;
        }
    }

    /** 這個 server 開服掃描前的觀察結果；沒有觀察（不是 M7 GameTest run）時為空。 */
    static Optional<String> observed(MinecraftServer server) {
        return Optional.ofNullable(OBSERVED.get(server));
    }
}
