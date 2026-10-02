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
    /** 僅測試：checked 可寫的 DORMANT receipt（MEASURED+SELECTED、全員 returned、無 lease）；真實 retained recovery 另由跨 JVM probe 證明。 */
    static dev.quantumchamber.persistence.SessionRecoveryRecord dormantReceipt(MinecraftServer server,UUID player,int slot) {
        var base=syntheticSession(server,player,slot);
        var key=new dev.quantumchamber.corridor.DoorKey(base.sessionUuid(),1,dev.quantumchamber.corridor.DoorKey.DoorWallSide.NEGATIVE_LATERAL);
        var bytes=new byte[32]; bytes[0]=(byte)slot; bytes[31]=7;
        var candidate=new dev.quantumchamber.candidate.QuantumCandidate.Source(new dev.quantumchamber.candidate.CandidateId(new dev.quantumchamber.candidate.CandidateBytes(bytes)));
        var source=base.participants().getFirst();
        var returned=new dev.quantumchamber.persistence.SessionRecoveryRecord.Participant(player,source.sourcePosition(),source.sourceVelocity(),
                source.yaw(),source.pitch(),source.quantumStateSnapshot(),true);
        return new dev.quantumchamber.persistence.SessionRecoveryRecord(base.sessionUuid(),base.chamberUuid(),base.origin(),List.of(returned),List.of(),
                dev.quantumchamber.superposition.SessionState.MEASURED,false,base.semantics(),base.candidateContext(),
                List.of(new dev.quantumchamber.candidate.CandidateLedgerEntry(key,candidate)),
                java.util.Optional.of(new dev.quantumchamber.candidate.CandidateSelection.Selected(key,candidate.candidateId(),player,0,1)));
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
    /** 僅在保存 DORMANT 測試證據後移除 testmod 自建 receipt；production 仍禁止一般 remove 消耗 MEASURED。 */
    @SuppressWarnings("unchecked")
    static void removeMeasuredForTeardown(MinecraftServer server,UUID sid) {
        var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(server);
        for(String field : List.of("records","authorityHistory","checkedAuthority")) {
            java.lang.reflect.Field handle;
            try { handle=journal.getClass().getDeclaredField(field); }
            catch(NoSuchFieldException absent) { if(field.equals("checkedAuthority")) continue; throw new IllegalStateException(absent); }
            try { handle.setAccessible(true); ((Map<UUID,?>)handle.get(journal)).remove(sid); }
            catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
        }
        journal.markDirty(); journal.flush(server);
        if(journal.flushedRecords().containsKey(sid) || journal.records().containsKey(sid)) throw new IllegalStateException("testmod DORMANT teardown 未確認");
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

    // ---- F3a：M5 塌縮狀態的 fixture 與收尾。record 只經正式 journal API 建立與移除（F2a 裁定 (c)）；runtime 與來源 ticket 經 F3d 的
    // 正式 API（SuperpositionSessionManager.releaseAfterTerminal）釋放。 ----

    /** 僅測試：指定 Controller 位置、朝北的 OVERWORLD ORIGIN 權威。該位置沒有 Controller 時來源身分驗證必定失敗，recovery 不會移動任何人。 */
    static dev.quantumchamber.chamber.ChamberOriginAuthority origin(UUID chamber,net.minecraft.util.math.BlockPos controllerPos) {
        return origin(chamber,net.minecraft.world.World.OVERWORLD,controllerPos);
    }
    /** 僅測試（F3d）：同上，但指定原版世界（Nether／End 的 M4 時期收據，spec §11.3）；role 由 world 推得。 */
    static dev.quantumchamber.chamber.ChamberOriginAuthority origin(UUID chamber,net.minecraft.registry.RegistryKey<net.minecraft.world.World> world,
            net.minecraft.util.math.BlockPos controllerPos) {
        return new dev.quantumchamber.chamber.ChamberOriginAuthority(chamber,world.getValue(),
                dev.quantumchamber.universe.DimensionRole.fromVanillaKey(world).orElseThrow(),controllerPos,net.minecraft.util.math.Direction.NORTH,
                dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN);
    }
    /**
     * 僅測試（F3d）：把 session 標成「開服 attach 當下就在 journal」，模擬重啟之後的第一次觀察。{@code passage} 為真時標成開服時就是
     * PASSAGE_READY（spec §12），否則標成 DORMANT 的「開服之後第一次觀察到原參與者在艙內」（spec §7.1）。只在 CollapseService 第一次看到
     * 這個 session 之前呼叫（同一個 tick 的 test callback 裡）。
     */
    @SuppressWarnings("unchecked")
    static void markStartup(MinecraftServer server,UUID sid,boolean passage) {
        var service=((Map<MinecraftServer,?>)get(dev.quantumchamber.collapse.CollapseService.class,"INSTANCES")).get(server);
        if(service==null) throw new IllegalStateException("沒有此 server 的 CollapseService");
        ((java.util.Set<UUID>)get(service,passage ? "startupPassages" : "startupSessions")).add(sid);
    }
    /**
     * 僅測試：M5 fixture 的起點，candidate-aware 的 MEASURED＋SELECTED（選中 NEW 或 SOURCE）。參與者（值為是否已返還）與 lease 由呼叫端決定：
     * 全員已返還、沒有 lease 即 DORMANT。塌縮狀態只能由這份收據先 put＋flush，再經 commitRestore 進入。
     */
    static dev.quantumchamber.persistence.SessionRecoveryRecord measuredReceipt(MinecraftServer server,
            dev.quantumchamber.chamber.ChamberOriginAuthority origin,Map<UUID,Boolean> people,
            List<dev.quantumchamber.persistence.SessionRecoveryRecord.SpaceLease> leases,boolean newCandidate) {
        var sid=UUID.randomUUID();
        var cohort=people.entrySet().stream().map(entry -> new dev.quantumchamber.persistence.SessionRecoveryRecord.Participant(entry.getKey(),
                net.minecraft.util.math.Vec3d.ofCenter(origin.controllerPos()),net.minecraft.util.math.Vec3d.ZERO,0,0,
                new net.minecraft.nbt.NbtCompound(),entry.getValue())).toList();
        var frozen=candidateAware(new dev.quantumchamber.persistence.SessionRecoveryRecord(sid,origin.chamberUuid(),origin,cohort,
                List.of(new dev.quantumchamber.persistence.SessionRecoveryRecord.SpaceLease(0,new net.minecraft.util.math.BlockBox(0,0,0,6,6,6))),
                dev.quantumchamber.superposition.SessionState.ARMING,false,dev.quantumchamber.persistence.SessionSemantics.LATERAL_BUFF_MAINTAINED),server);
        var key=new dev.quantumchamber.corridor.DoorKey(sid,1,dev.quantumchamber.corridor.DoorKey.DoorWallSide.NEGATIVE_LATERAL);
        var id=new dev.quantumchamber.candidate.CandidateId(randomBytes());
        dev.quantumchamber.candidate.QuantumCandidate candidate=newCandidate
                ? new dev.quantumchamber.candidate.QuantumCandidate.New(id,new dev.quantumchamber.candidate.NewUniverseIntent(randomBytes(),
                        dev.quantumchamber.universe.GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1,1,randomBytes()))
                : new dev.quantumchamber.candidate.QuantumCandidate.Source(id);
        return new dev.quantumchamber.persistence.SessionRecoveryRecord(sid,origin.chamberUuid(),origin,cohort,leases,
                dev.quantumchamber.superposition.SessionState.MEASURED,false,frozen.semantics(),frozen.candidateContext(),
                List.of(new dev.quantumchamber.candidate.CandidateLedgerEntry(key,candidate)),
                java.util.Optional.of(new dev.quantumchamber.candidate.CandidateSelection.Selected(key,id,cohort.getFirst().playerUuid(),0,1)));
    }
    private static dev.quantumchamber.candidate.CandidateBytes randomBytes() {
        var bytes=new byte[32]; java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
        return new dev.quantumchamber.candidate.CandidateBytes(bytes);
    }
    /** 僅測試：MEASURED 收據提交 COLLAPSING 的值（spec §7.2：SOURCE、EXISTING 的 UniverseId、NEW 的推導值）；參與者與 lease 原樣。 */
    static dev.quantumchamber.persistence.SessionRecoveryRecord collapsing(dev.quantumchamber.persistence.SessionRecoveryRecord measured,long gameTime) {
        dev.quantumchamber.collapse.CollapseDestination destination=switch(measured.selectedCandidate().orElseThrow()) {
            case dev.quantumchamber.candidate.QuantumCandidate.Source ignored -> new dev.quantumchamber.collapse.CollapseDestination.Source();
            case dev.quantumchamber.candidate.QuantumCandidate.Existing existing -> new dev.quantumchamber.collapse.CollapseDestination.Universe(existing.universeId());
            case dev.quantumchamber.candidate.QuantumCandidate.New created -> new dev.quantumchamber.collapse.CollapseDestination.Universe(
                    dev.quantumchamber.collapse.UniverseAllocationLedger.deriveUniverseId(created.intent().allocationToken()));
        };
        return measured.withCollapse(dev.quantumchamber.superposition.SessionState.COLLAPSING,
                dev.quantumchamber.persistence.CollapseFields.committed(gameTime,destination));
    }
    /**
     * 僅測試：以正式 API 把 M5 fixture 收尾到 checked 終局，不留 M5 record。SEALED（非 TRANSFER_RECOVERY_REQUIRED）先經 commitRestore 重試回
     * COLLAPSING；尚未全員返還或仍有 lease 的 COLLAPSING 以一次 roll-forward 寫成全員返還、lease 清空；之後 COLLAPSING 依目的地以
     * SOURCE_FAST_PATH 或 PREPARE_DOOR_CLOSED 終局，PASSAGE_READY 以 PASSAGE_ENDED 終局。只用於沒有走廊 space 的 fixture，或 space 已清除之後。
     */
    static void terminateCollapseFixture(MinecraftServer server,UUID sid) {
        var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(server);
        var record=journal.flushedRecords().get(sid);
        if(record==null) throw new IllegalStateException("M5 fixture 已不在 journal：session="+sid);
        if(record.state()==dev.quantumchamber.superposition.SessionState.SEALED) {
            var retry=record.withCollapse(dev.quantumchamber.superposition.SessionState.COLLAPSING,record.collapse().retry());
            var result=journal.commitRestore(server,record,retry);
            if(!result.committed()) throw new IllegalStateException("M5 fixture 收尾：SEALED→COLLAPSING 未確認："+result);
            record=retry;
        }
        if(record.state()==dev.quantumchamber.superposition.SessionState.COLLAPSING && (!record.spaceLeases().isEmpty()
                || record.participants().stream().anyMatch(person -> !person.returned()))) {
            var people=record.participants().stream().map(person -> new dev.quantumchamber.persistence.SessionRecoveryRecord.Participant(person.playerUuid(),
                    person.sourcePosition(),person.sourceVelocity(),person.yaw(),person.pitch(),person.quantumStateSnapshot(),true)).toList();
            var settled=record.withProgress(people,List.of(),record.state(),record.restoreEntryEffectOnReturn());
            var result=journal.commitRollForward(server,record,settled);
            if(!result.committed()) throw new IllegalStateException("M5 fixture 收尾：返還與清 lease 未確認："+result);
            record=settled;
        }
        var origin=switch(record.state()) {
            case PASSAGE_READY -> dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin.PASSAGE_ENDED;
            case COLLAPSING -> record.collapse().destination() instanceof dev.quantumchamber.collapse.CollapseDestination.Source
                    ? dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin.SOURCE_FAST_PATH
                    : dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin.PREPARE_DOOR_CLOSED;
            default -> throw new IllegalStateException("M5 fixture 收尾不支援："+record.state());
        };
        var result=journal.terminate(server,record,origin);
        if(!result.removed() || journal.flushedRecords().containsKey(sid) || journal.records().containsKey(sid))
            throw new IllegalStateException("M5 fixture 終局未確認："+result);
    }
    /** 僅測試：以反射呼叫 SuperpositionSessionManager 開服接手時對每筆 flushed record 執行的那一段（attachRecord）。 */
    static void attachRecord(dev.quantumchamber.persistence.SessionRecoveryRecord record) {
        call(dev.quantumchamber.chamber.ChamberSessions.gateway(),"attachRecord",record);
    }
    /** 僅測試：以反射呼叫 CorridorPageManager 開服時依 durable record 重建 space 的那一段（restoreSpace）。 */
    static void restoreSpace(CorridorPageManager pages,dev.quantumchamber.persistence.SessionRecoveryRecord record) {
        call(pages,"restoreSpace",record);
    }
    /** 僅測試：移除 restoreSpace 為沒有 lease 的 fixture 建立的 space（RED 時 HEAD 會建立）；有 lease 的 space 不在此處理。 */
    @SuppressWarnings("unchecked")
    static boolean removeSpaceForTeardown(CorridorPageManager pages,UUID sid) {
        var space=space(pages,sid);
        if(space==null) return false;
        if(!((Map<?,?>)get(space,"leases")).isEmpty()) throw new IllegalStateException("只移除沒有 lease 的 fixture space");
        ((Map<UUID,?>)get(pages,"spaces")).remove(sid);
        return true;
    }
    static boolean sourceTicketHeld(UUID sid) { return ((Map<?,?>)get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"tickets")).containsKey(sid); }
    static Object runtimeState(UUID chamber) {
        var runtime=((Map<?,?>)get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"sessions")).get(chamber);
        return runtime==null ? null : get(runtime,"state");
    }
    /** 僅測試（只讀）：SuperpositionSessionManager 是否還有這個 session 的 runtime。 */
    static boolean runtimeHeld(UUID sid) {
        return ((Map<?,?>)get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"sessions")).values().stream()
                .anyMatch(runtime -> ((dev.quantumchamber.persistence.SessionRecoveryRecord)get(runtime,"initial")).sessionUuid().equals(sid));
    }
    static boolean disconnectPending(UUID player) {
        return ((java.util.Set<?>)get(get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"recovery"),"disconnected")).contains(player);
    }
    /** 正式 API（spec §5 的終局之後）：釋放 runtime 與來源 ticket。只是型別轉換的捷徑，前置檢查在 production。 */
    static void releaseAfterTerminal(MinecraftServer server,UUID sid) {
        ((dev.quantumchamber.superposition.SuperpositionSessionManager)dev.quantumchamber.chamber.ChamberSessions.gateway()).releaseAfterTerminal(server,sid);
    }
    /**
     * 只在 RED 取證時使用：HEAD 會讓 COLLAPSING 走進 MEASURED-only 分支丟例外。以反射把 journal（current、history、checked authority）與 runtime
     * 退回原本的 MEASURED 並 flush，讓既有的 MEASURED 收尾（{@link #resetMeasuredForTeardown}）接手，避免下一個 END_SERVER_TICK 讓 GameTest server crash。
     */
    @SuppressWarnings("unchecked")
    static void revertCollapseForTeardown(MinecraftServer server,CorridorPageManager pages,UUID sid,dev.quantumchamber.persistence.SessionRecoveryRecord measured) {
        var journal=dev.quantumchamber.persistence.SessionRecoveryState.get(server);
        for(String field : List.of("records","authorityHistory","checkedAuthority"))
            ((Map<UUID,dev.quantumchamber.persistence.SessionRecoveryRecord>)get(journal,field)).put(sid,measured);
        ((java.util.Set<UUID>)get(journal,"rollForwardPending")).remove(sid);
        journal.markDirty(); journal.flush(server);
        if(!measured.equals(journal.flushedRecords().get(sid))) throw new IllegalStateException("RED 回退未確認");
        set(space(pages,sid),"authority",measured);
        var runtime=((Map<?,?>)get(dev.quantumchamber.chamber.ChamberSessions.gateway(),"sessions")).get(measured.chamberUuid());
        if(runtime!=null) set(runtime,"state",dev.quantumchamber.superposition.SessionState.MEASURED);
    }
    /** 依名稱與參數個數找到宣告的方法（含 private 與 private record 的 accessor）並呼叫；被呼叫端的例外原樣拋出。 */
    private static Object call(Object target,String name,Object... args) {
        try {
            var method=java.util.Arrays.stream(target.getClass().getDeclaredMethods())
                    .filter(candidate -> candidate.getName().equals(name) && candidate.getParameterCount()==args.length).findFirst()
                    .orElseThrow(() -> new NoSuchMethodException(target.getClass().getName()+"."+name));
            method.setAccessible(true); return method.invoke(target,args);
        } catch(InvocationTargetException failure) {
            if(failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure.getCause());
        } catch(ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }
}
