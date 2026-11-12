package dev.quantumchamber.universe;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.DynamicRegistryManager;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Task 22 整分支審查 fix1：設定 context 不可用時，schema2 物化回 NOT_READY 等待，不呼叫 backend。 */
class UniverseLifecycleSettingsGateTest {
    @BeforeAll static void initializeNativeVersion() { net.minecraft.SharedConstants.createGameVersion(); }

    private static UniverseRecord schemaOne() {
        return new UniverseRegistryState().allocateOverworld(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1);
    }

    private static UniverseRecord schemaTwo() {
        var parameters = new NbtCompound(); parameters.putString("OverworldPreset", "normal"); parameters.putBoolean("GenerateStructures", true);
        var snapshot = new NbtCompound(); snapshot.putInt("SnapshotVersion", 1);
        snapshot.putString("ProviderId", "quantumchamber:vanilla_normal_v1"); snapshot.putInt("ProviderVersion", 1);
        snapshot.put("Parameters", parameters); snapshot.putByteArray("OutputFingerprint", new byte[32]);
        var settings = GenerationSettingsSnapshot.fromNbt(snapshot);
        var token = new CandidateBytes(new byte[32]);
        var intent = new NewUniverseIntent(token, token, 1, NewUniverseIntent.RANDOM_PROFILE_ID, 1, 42L, settings, settings, 0);
        return UniverseRegistry.newM6(UniverseAllocationLedger.deriveUniverseId(token), 18, intent);
    }

    private static Supplier<ServerSettingsContext> lease(boolean valid, AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return new ServerSettingsContext() {
                @Override public void requireValid() { if (!valid) throw new IllegalStateException("CONTEXT_UNAVAILABLE"); }
                @Override public DynamicRegistryManager.Immutable registries() { throw new AssertionError("閘門不得讀 registry"); }
                @Override public boolean sourceGenerateStructures() { throw new AssertionError("閘門不得讀 structures"); }
            };
        };
    }

    @Test void schemaOneNeverAsksForSettings() {
        assertTrue(UniverseLifecycleService.settingsNotReady(schemaOne(), () -> { throw new AssertionError("schema1 不得取 lease"); }).isEmpty());
    }

    @Test void schemaTwoWaitsWhenSettingsAreUnavailable() {
        var calls = new AtomicInteger();
        var outcome = UniverseLifecycleService.settingsNotReady(schemaTwo(), lease(false, calls)).orElseThrow();
        assertEquals(UniverseLifecycleService.MaterializeOutcome.Status.NOT_READY, outcome.status());
        assertTrue(outcome.detail().contains("CONTEXT_UNAVAILABLE"), outcome.detail());
        assertTrue(outcome.world().isEmpty() && outcome.backendStatus().isEmpty());
        assertEquals(1, calls.get());
    }

    @Test void schemaTwoProceedsWhenSettingsAreValid() {
        var calls = new AtomicInteger();
        assertTrue(UniverseLifecycleService.settingsNotReady(schemaTwo(), lease(true, calls)).isEmpty());
        assertEquals(1, calls.get());
    }

    @Test void otherFailuresAreNotTurnedIntoWaiting() {
        assertThrows(IllegalArgumentException.class, () -> UniverseLifecycleService.settingsNotReady(schemaTwo(),
                () -> { throw new IllegalArgumentException("不是 lease 不可用"); }));
    }
}
