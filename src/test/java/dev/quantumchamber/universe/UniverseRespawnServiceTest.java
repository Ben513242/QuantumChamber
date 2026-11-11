package dev.quantumchamber.universe;

import static dev.quantumchamber.universe.UniverseRespawnService.Outcome.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

class UniverseRespawnServiceTest {
    private static final UUID FIRST = UUID.fromString("80000000-0000-4000-8000-0000000000a1");
    private static final RegistryKey<World> UNIVERSE_KEY = RegistryKey.of(RegistryKeys.WORLD,
            Identifier.of("quantumchamber", "universe/00000000-0000-0000-0000-0000000000a1/overworld"));
    private static final RegistryKey<World> CORRIDOR = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("quantumchamber", "superposition"));
    private static final RegistryKey<World> OTHER_MOD = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("othermod", "skylands"));
    private final Object death = new Object();

    private static Supplier<Optional<UniverseRespawnService.Checked<Object>>> row(boolean overworld, boolean currentMatches, Optional<Object> active) {
        return () -> Optional.of(new UniverseRespawnService.Checked<>("u-a1", overworld, currentMatches, active));
    }

    /** 不得被呼叫；Error 不會被 decide 的 catch 吃掉，被呼叫就直接讓測試失敗。 */
    private static Supplier<Optional<UniverseRespawnService.Checked<Object>>> neverQueried() {
        return () -> { throw new AssertionError("不應存取 catalog"); };
    }

    private static Supplier<Optional<UniverseRespawnService.Checked<Object>>> unhealthyCatalog() {
        return () -> { throw new IllegalStateException("catalog 不健康"); };
    }

    /** 經 NBT 讀回：flushed 與 current 相同，與開服後 bootstrap 讀到的 catalog 一致。 */
    private static UniverseRegistryState flushedWithOne(boolean disabled) {
        var state = new UniverseRegistryState();
        var record = state.allocateOverworld(FIRST, 1);
        if (disabled) state.changeAvailability(record.definition().universeId(), DesiredAvailability.DISABLED);
        return UniverseRegistryState.fromNbt(state.writeNbt(new NbtCompound()));
    }

    private static RegistryKey<World> onlyOverworldKey(UniverseRegistryState state) {
        return state.flushedRecords().values().iterator().next().definition().worlds().get(DimensionRole.OVERWORLD).worldKey();
    }

    @Test
    void vanillaWorldsNeverQueryCatalog() {
        for (var key : java.util.List.of(World.OVERWORLD, World.NETHER, World.END)) {
            assertEquals(VANILLA, UniverseRespawnService.decide(key, death, true, neverQueried()).outcome(), key.getValue().toString());
        }
    }

    @Test
    void exactLiveUniverseKeepsFallbackInSameUniverse() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.of(death)));
        assertEquals(UNIVERSE, decision.outcome());
        assertEquals("u-a1", decision.universe());
    }

    @Test
    void keyWithoutCatalogRecordIsReportedOnlyWhenItLooksLikeUniverse() {
        var corridor = UniverseRespawnService.decide(CORRIDOR, death, true, Optional::empty);
        assertEquals(NOT_UNIVERSE, corridor.outcome());
        assertNull(corridor.reason());
        var universe = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, Optional::empty);
        assertEquals(KEPT_CANONICAL, universe.outcome());
        assertEquals("flushed catalog 沒有這個 world key", universe.reason());
    }

    @Test
    void differentActiveInstanceIsRejected() {
        var decision = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, row(true, true, Optional.of(new Object())));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("ACTIVE world 與死亡世界不是同一實例", decision.reason());
    }

    @Test
    void inactiveUniverseIsRejected() {
        // UniverseLifecycleService.resolveActive 在 lifecycle 未 ready（例如停服中）或 world 不是 ACTIVE 時都為空。
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
    void offServerThreadNeverQueriesAndIsReportedOnlyForUniverseKeys() {
        var universe = UniverseRespawnService.decide(UNIVERSE_KEY, death, false, neverQueried());
        assertEquals(KEPT_CANONICAL, universe.outcome());
        assertEquals("不在 server thread", universe.reason());
        assertEquals(NOT_UNIVERSE, UniverseRespawnService.decide(OTHER_MOD, death, false, neverQueried()).outcome());
    }

    @Test
    void catalogFailureIsReportedOnlyForUniverseLookingKeys() {
        var universe = UniverseRespawnService.decide(UNIVERSE_KEY, death, true, unhealthyCatalog());
        assertEquals(KEPT_CANONICAL, universe.outcome());
        assertTrue(universe.reason().startsWith("catalog 查詢失敗："), universe.reason());
        assertInstanceOf(IllegalStateException.class, universe.failure());
        assertEquals(NOT_UNIVERSE, UniverseRespawnService.decide(OTHER_MOD, death, true, unhealthyCatalog()).outcome());
        assertEquals(NOT_UNIVERSE, UniverseRespawnService.decide(CORRIDOR, death, true, unhealthyCatalog()).outcome());
    }

    @Test
    void lookupIgnoresAvailabilityForFlushedDisabledUniverse() {
        // 活著的 U 即使被改為 DISABLED 也仍是死亡世界；lookup 不得以 availability 過濾。
        var state = flushedWithOne(true);
        var key = onlyOverworldKey(state);
        var checked = UniverseRespawnService.lookup(state, key, id -> Optional.of(death)).orElseThrow();
        assertTrue(checked.overworldRole());
        assertTrue(checked.currentMatchesFlushed());
        assertEquals(UNIVERSE, UniverseRespawnService.decide(key, death, true, () -> Optional.of(checked)).outcome());
    }

    @Test
    void lookupReportsCurrentDifferentFromFlushed() {
        var state = flushedWithOne(false);
        var key = onlyOverworldKey(state);
        state.changeAvailability(state.flushedRecords().keySet().iterator().next(), DesiredAvailability.DISABLED);
        var checked = UniverseRespawnService.lookup(state, key, id -> Optional.of(death)).orElseThrow();
        assertFalse(checked.currentMatchesFlushed());
        var decision = UniverseRespawnService.decide(key, death, true, () -> Optional.of(checked));
        assertEquals(KEPT_CANONICAL, decision.outcome());
        assertEquals("catalog current 與 flushed 不同", decision.reason());
    }

    @Test
    void lookupFindsNothingForUnknownKeyAndPassesUniverseIdToActive() {
        var state = flushedWithOne(false);
        assertTrue(UniverseRespawnService.lookup(state, UNIVERSE_KEY, id -> { throw new AssertionError("未知 key 不應查 ACTIVE"); }).isEmpty());
        var id = state.flushedRecords().keySet().iterator().next();
        var checked = UniverseRespawnService.lookup(state, onlyOverworldKey(state),
                requested -> requested.equals(id) ? Optional.of(death) : Optional.empty()).orElseThrow();
        assertEquals(id.value().toString(), checked.universe());
        assertSame(death, checked.active().orElseThrow());
    }
}
