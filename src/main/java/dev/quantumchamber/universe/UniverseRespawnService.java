package dev.quantumchamber.universe;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * spec §5（Task R，2026-11-11 使用者裁示「只換世界，位置照原版」）：原版 {@code getRespawnTarget} 要退回 canonical 主世界時，
 * 若死亡世界是 checked catalog 對應的 exact live U:OVERWORLD，就把 fallback 世界換成同一 U。位置、{@code missingRespawnBlock}
 * 與 TeleportTarget 其他欄位仍由原版產生；有效床／錨不經這裡，照原版優先。
 */
public final class UniverseRespawnService {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    private UniverseRespawnService() { }

    /** mixin 入口：回傳原版建構 fallback TeleportTarget 時要用的世界。 */
    public static ServerWorld fallbackWorld(ServerPlayerEntity player, ServerWorld canonical) {
        var death = player.getServerWorld();
        var server = death.getServer();
        var key = death.getRegistryKey();
        var decision = decide(key, death, server.isOnThread(), () -> lookup(server, key));
        switch (decision.outcome()) {
            case UNIVERSE -> {
                LOGGER.info("UNIVERSE_RESPAWN_FALLBACK player={} universe={} world={}", player.getUuid(), decision.universe(), key.getValue());
                return death;
            }
            case KEPT_CANONICAL -> LOGGER.warn("UNIVERSE_RESPAWN_KEPT_CANONICAL player={} world={} reason={} target={}",
                    player.getUuid(), key.getValue(), decision.reason(), canonical.getRegistryKey().getValue());
            case VANILLA, NOT_UNIVERSE -> { }
        }
        return canonical;
    }

    enum Outcome {
        /** 死亡世界是原版主世界／Nether／End：不查 catalog。 */
        VANILLA,
        /** catalog 沒有這個 world key（例如走廊或其他 mod 的維度）：照原版，不記 log。 */
        NOT_UNIVERSE,
        /** exact live U:OVERWORLD：fallback 改為同一 U。 */
        UNIVERSE,
        /** 是 U 但 checked 條件不成立：照原版並記原因。 */
        KEPT_CANONICAL
    }

    record Decision(Outcome outcome, String universe, String reason) { }

    /** catalog 中 descriptor world key 等於死亡世界的那筆，以及它的 checked 條件。 */
    record Checked<W>(String universe, boolean overworldRole, boolean currentMatchesFlushed, Optional<W> active) { }

    /** 純判斷；{@code lookup} 只在非原版世界且位於 server thread 時呼叫。 */
    static <W> Decision decide(RegistryKey<World> key, W death, boolean onServerThread, Supplier<Optional<Checked<W>>> lookup) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(death, "death");
        if (DimensionRole.fromVanillaKey(key).isPresent()) return new Decision(Outcome.VANILLA, null, null);
        if (!onServerThread) return new Decision(Outcome.KEPT_CANONICAL, null, "不在 server thread");
        Optional<Checked<W>> checked;
        try {
            checked = lookup.get();
        } catch (RuntimeException failure) {
            // 其他 mod 的維度不因 catalog 問題產生雜訊；只有看起來是 U 的 key 才記原因。
            return looksLikeUniverse(key)
                    ? new Decision(Outcome.KEPT_CANONICAL, null, "catalog 查詢失敗：" + failure)
                    : new Decision(Outcome.NOT_UNIVERSE, null, null);
        }
        if (checked.isEmpty()) return new Decision(Outcome.NOT_UNIVERSE, null, null);
        var row = checked.get();
        if (!row.overworldRole()) return kept(row, "descriptor 不是 OVERWORLD");
        if (!row.currentMatchesFlushed()) return kept(row, "catalog current 與 flushed 不同");
        if (row.active().isEmpty()) return kept(row, "U 不是 ACTIVE");
        if (row.active().get() != death) return kept(row, "ACTIVE world 與死亡世界不是同一實例");
        return new Decision(Outcome.UNIVERSE, row.universe(), null);
    }

    private static Decision kept(Checked<?> row, String reason) {
        return new Decision(Outcome.KEPT_CANONICAL, row.universe(), reason);
    }

    static boolean looksLikeUniverse(RegistryKey<World> key) {
        return key.getValue().getNamespace().equals("quantumchamber") && key.getValue().getPath().startsWith("universe/");
    }

    /** 只讀 flushed catalog 找 key；current 是否相同與 exact ACTIVE instance 交給 {@link #decide} 判斷。 */
    private static Optional<Checked<ServerWorld>> lookup(MinecraftServer server, RegistryKey<World> key) {
        var catalog = UniverseRegistryState.get(server);
        for (var record : catalog.flushedRecords().values()) {
            for (var descriptor : record.definition().worlds().values()) {
                if (!descriptor.worldKey().equals(key)) continue;
                var id = record.definition().universeId();
                return Optional.of(new Checked<>(id.value().toString(), descriptor.role() == DimensionRole.OVERWORLD,
                        record.equals(catalog.records().get(id)), UniverseLifecycleService.resolveActive(server, id)));
            }
        }
        return Optional.empty();
    }
}
