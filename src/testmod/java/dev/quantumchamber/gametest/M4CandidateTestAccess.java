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
            boolean initialized=dev.quantumchamber.persistence.SessionRecoveryState.get(server).candidateInitialized();
            var discovery=initialized ? dev.quantumchamber.candidate.UniverseDiscoveryState.load(root)
                    : dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
            boolean evidence=initialized || !discovery.records().isEmpty();
            var entropy=dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root,evidence);
            var source=net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD,record.origin().worldKey());
            var policy=new dev.quantumchamber.candidate.CandidatePolicySnapshot(net.minecraft.util.Identifier.of("quantumchamber:m4_v1"),
                    1,1,5,20,75,discovery.watermark(),16384,entropy.entropyFingerprint(),
                    new dev.quantumchamber.candidate.SourceFamilyRef.Vanilla(source,record.origin().role()));
            return dev.quantumchamber.persistence.SessionRecoveryRecord.candidateAware(record.sessionUuid(),record.chamberUuid(),record.origin(),
                    record.participants(),record.spaceLeases(),record.state(),record.restoreEntryEffectOnReturn(),record.semantics(),policy);
        } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }
    /** 僅測試：以正式 codec 可接受的 candidate-aware 形狀建立另一個 session，觸發整份 journal 的「其他 session」寫入。 */
    static dev.quantumchamber.persistence.SessionRecoveryRecord syntheticSession(MinecraftServer server,UUID player,int slot) {
        var chamber=UUID.randomUUID();
        var origin=new dev.quantumchamber.chamber.ChamberOriginAuthority(chamber,net.minecraft.world.World.OVERWORLD.getValue(),
                dev.quantumchamber.universe.DimensionRole.OVERWORLD,new net.minecraft.util.math.BlockPos(1000+16*slot,80,1000),
                net.minecraft.util.math.Direction.NORTH,dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN);
        var person=new dev.quantumchamber.persistence.SessionRecoveryRecord.Participant(player,net.minecraft.util.math.Vec3d.ZERO,
                net.minecraft.util.math.Vec3d.ZERO,0,0,new net.minecraft.nbt.NbtCompound(),false);
        var lease=new dev.quantumchamber.persistence.SessionRecoveryRecord.SpaceLease(slot,new net.minecraft.util.math.BlockBox(1000,80,1000,1006,86,1006));
        return candidateAware(new dev.quantumchamber.persistence.SessionRecoveryRecord(UUID.randomUUID(),chamber,origin,List.of(person),List.of(lease),
                dev.quantumchamber.superposition.SessionState.ARMING,false,dev.quantumchamber.persistence.SessionSemantics.LATERAL_BUFF_MAINTAINED),server);
    }
    /** 直接 strict decode 正式 journal 檔，不經 in-memory flushed snapshot。 */
    static Map<UUID,dev.quantumchamber.persistence.SessionRecoveryRecord> diskRecords(MinecraftServer server) {
        try {
            var path=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data/quantumchamber_sessions.dat");
            var decoded=dev.quantumchamber.persistence.SessionRecoveryState.fromNbt(
                    dev.quantumchamber.persistence.SessionJournalStore.read(path).getCompound("data"));
            decoded.requireHealthy(); return decoded.flushedRecords();
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
    /** 僅在保存Task7觀察證據後清理fixture；不是MEASURED安全返還的實作或驗證。 */
    @SuppressWarnings("unchecked")
    static void resetMeasuredForTeardown(MinecraftServer server,CorridorPageManager pages,UUID sid) {
        var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(server);
        var record=journal.flushedRecords().get(sid);
        if(record.state()!=dev.quantumchamber.superposition.SessionState.MEASURED) return;
        var reset=new dev.quantumchamber.persistence.SessionRecoveryRecord(record.sessionUuid(),record.chamberUuid(),record.origin(),
                record.participants(),record.spaceLeases(),dev.quantumchamber.superposition.SessionState.SUPERPOSITION,false,record.semantics(),
                record.candidateContext(),record.candidateLedger(),java.util.Optional.of(new dev.quantumchamber.candidate.CandidateSelection.Selectable()));
        ((Map<UUID,dev.quantumchamber.persistence.SessionRecoveryRecord>)get(journal,"records")).put(sid,reset);
        ((Map<UUID,dev.quantumchamber.persistence.SessionRecoveryRecord>)get(journal,"authorityHistory")).put(sid,reset);
        journal.markDirty(); journal.flush(server); set(space(pages,sid),"authority",reset);
        var runtime=((Map<?,?>)get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"sessions")).get(record.chamberUuid());
        set(runtime,"state",dev.quantumchamber.superposition.SessionState.SUPERPOSITION);
    }
    /** 僅獨立 fresh-save runner，在全部 authority 都空的邊界模擬空 schema3 冷載入。 */
    static dev.quantumchamber.persistence.SessionRecoveryState reloadEmptySchemaThree(MinecraftServer server,CorridorPageManager pages)
            throws java.io.IOException {
        var before=dev.quantumchamber.persistence.SessionRecoveryState.get(server);
        var sessions=dev.quantumchamber.chamber.ChamberSessions.gateway();
        if(!Boolean.getBoolean("quantumchamber.gametest.legacyOnly") || !before.records().isEmpty() || !before.flushedRecords().isEmpty()
                || !((Map<?,?>)get(pages,"spaces")).isEmpty() || !((Map<?,?>)get(sessions,"sessions")).isEmpty())
            throw new IllegalStateException("空schema3冷載入只允許獨立runner的全空authority邊界");
        var data=new net.minecraft.nbt.NbtCompound(); data.putInt("SchemaVersion",3); data.put("Records",new net.minecraft.nbt.NbtList());
        var wrapped=new net.minecraft.nbt.NbtCompound(); wrapped.put("data",data); net.minecraft.nbt.NbtHelper.putDataVersion(wrapped);
        var path=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data/quantumchamber_sessions.dat");
        net.minecraft.nbt.NbtIo.writeCompressed(wrapped,path);
        var actual=net.minecraft.nbt.NbtIo.readCompressed(path,net.minecraft.nbt.NbtSizeTracker.of(4096));
        if(!actual.equals(wrapped)) throw new IllegalStateException("空schema3 fixture讀回不符");
        var loaded=dev.quantumchamber.persistence.SessionRecoveryState.fromNbt(actual.getCompound("data")); loaded.requireHealthy();
        server.getOverworld().getPersistentStateManager().set(dev.quantumchamber.persistence.SessionRecoveryState.STATE_ID,loaded);
        if(dev.quantumchamber.persistence.SessionRecoveryState.get(server)!=loaded) throw new IllegalStateException("native journal cache不符");
        set(pages,"journal",loaded); set(sessions,"journal",loaded); set(get(sessions,"recovery"),"journal",loaded);
        return loaded;
    }
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
