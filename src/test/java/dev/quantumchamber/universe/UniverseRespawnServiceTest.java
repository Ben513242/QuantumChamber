package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.UniverseRespawnService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.function.Supplier;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class UniverseRespawnServiceTest {
    private static final RegistryKey<World> UNIVERSE_KEY = RegistryKey.of(RegistryKeys.WORLD,
            Identifier.of("quantumchamber", "universe/00000000-0000-0000-0000-0000000000a1/overworld"));
    private static final RegistryKey<World> CORRIDOR = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("quantumchamber", "superposition"));
    private static final RegistryKey<World> OTHER_MOD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("othermod", "skylands"));
    private final Object death = new Object();

    private static Supplier<Optional<UniverseRespawnService.Checked<Object>>> row(boolean overworld, boolean currentMatches, Optional<Object> active) {
        return () -> Optional.of(new UniverseRespawnService.Checked<>("u-a1", overworld, currentMatches, active));
    }

    private static Supplier<Optional<UniverseRespawnService.Checked<Object>>> failing() {
        return () -> { throw new IllegalStateException("catalog 不健康"); };
    }

    @Test
    void vanillaWorldsNeverQueryCatalog() {
        for (var key : java.util.List.of(World.OVERWORLD, World.NETHER, World.END)) {
            assertEquals(VANILLA, UniverseRespawnService.decide(key, death, true, failing()).outcome(), key.getValue().toString());
        }
    }

    @Test
    void exactLiveUniverseKeepsFallbackInSameUniverse() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.of(death)));
        assertEquals(UNIVERSE, decision.outcome());
        assertEquals("u-a1", decision.universe());
    }

    @Test
    void keyWithoutCatalogRecordStaysVanillaWithoutReason() {
        var decision = UniverseRespawnService.decide(CORRIDOR, death, true, Optional::empty);
        assertEquals(NOT_UNIVERSE, decision.outcome());
        assertNull(decision.reason());
    }

    @Test
    void differentActiveInstanceIsRejected() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.of(new Object())));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("ACTIVE world 與死亡世界不是同一實例", decision.reason());
    }

    @Test
    void inactiveUniverseIsRejectedIncludingLifecycleNotReady() {
        // resolveActive 在 lifecycle 未 ready（例如停服中）或 world 不是 ACTIVE 時都為空。
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.empty()));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("U 不是 ACTIVE", decision.reason());
    }

    @Test
    void currentDifferentFromFlushedIsRejected() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, false, Optional.of(death)));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("catalog current 與 flushed 不同", decision.reason());
    }

    @Test
    void nonOverworldDescriptorIsRejected() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(false, true, Optional.of(death)));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("descriptor 不是 OVERWORLD", decision.reason());
    }

    @Test
    void liveUniverseMarkedDisabledStillCountsAsExactLive() {
        // resolveActive 不看 availability；活著的 U 就是死亡世界，M5 §8.3 的 DISABLED 只管重啟後是否物化。
        assertEquals(UNIVERSE, UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.of(death))).outcome());
    }

    @Test
    void offServerThreadIsRejectedWithoutQuery() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, false, failing());
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("不在 server thread", decision.reason());
    }

    @Test
    void catalogFailureIsReportedOnlyForUniverseLookingKeys() {
        var universe = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, failing());
        assertEquals(KEPT_CANONICAL, universe.outcome());
        assertTrue(universe.reason().startsWith("catalog 查詢失敗："), universe.reason());
        assertEquals(NOT_UNIVERSE, UniverseRespawnService.decide(OTHER_MOD, death, true, failing()).outcome());
        assertEquals(NOT_UNIVERSE, UniverseRespawnService.decide(CORRIDOR, death, true, failing()).outcome());
    }
}
