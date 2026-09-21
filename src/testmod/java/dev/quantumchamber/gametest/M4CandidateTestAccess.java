package dev.quantumchamber.gametest;

import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.minecraft121.Minecraft121DynamicDimensionBackend;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;

/** 僅 testmod 的 fault／readback access；不添加 production 測試 API。 */
final class M4CandidateTestAccess {
    private M4CandidateTestAccess() {}
    static dev.quantumchamber.persistence.SessionRecoveryRecord candidateAware(
            dev.quantumchamber.persistence.SessionRecoveryRecord record,MinecraftServer server) {
        try {
            var root=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            var discovery=dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
            boolean evidence=dev.quantumchamber.persistence.SessionRecoveryState.get(server).flushedRecords().values().stream()
                    .anyMatch(existing -> existing.candidateContext().isPresent()) || !discovery.records().isEmpty();
            var entropy=dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root,evidence);
            var source=net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,record.origin().worldKey());
            var policy=new dev.quantumchamber.candidate.CandidatePolicySnapshot(net.minecraft.util.Identifier.of("quantumchamber:m4_v1"),
                    1,1,5,20,75,discovery.watermark(),16384,entropy.entropyFingerprint(),
                    new dev.quantumchamber.candidate.SourceFamilyRef.Vanilla(source,record.origin().role()));
            return dev.quantumchamber.persistence.SessionRecoveryRecord.candidateAware(record.sessionUuid(),record.chamberUuid(),record.origin(),
                    record.participants(),record.spaceLeases(),record.state(),record.restoreEntryEffectOnReturn(),record.semantics(),policy);
        } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    static Object get(Object target, String name) {
        try {
            var type = target instanceof Class<?> clazz ? clazz : target.getClass();
            var field = type.getDeclaredField(name); field.setAccessible(true);
            return field.get(target instanceof Class<?> ? null : target);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static void set(Object target, String name, Object value) {
        try { var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static Object space(CorridorPageManager pages, UUID session) { return ((Map<?, ?>) get(pages, "spaces")).get(session); }
    static void tickets(CorridorPageManager pages, UUID session, boolean acquire) {
        var space = space(pages, session);
        try {
            var method = CorridorPageManager.class.getDeclaredMethod(acquire ? "acquireTickets" : "removeAllTickets", space.getClass());
            method.setAccessible(true); method.invoke(pages, space);
        } catch (InvocationTargetException failure) { throw new IllegalStateException(failure.getCause()); }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
    static List<?> runtime(MinecraftServer server) {
        var owner = ((Map<?, ?>) get(UniverseLifecycleService.class, "CONTEXTS")).get(server);
        return ((Minecraft121DynamicDimensionBackend) get(owner, "backend")).runtimeSnapshot(server);
    }
}
