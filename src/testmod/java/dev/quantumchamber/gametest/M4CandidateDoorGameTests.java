package dev.quantumchamber.gametest;

import com.sun.jna.Platform;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.SourceFamilyRef;
import dev.quantumchamber.corridor.*;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.*;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;

/** 候選接線走原生入場與 checked journal；fixture 不會進入 release JAR。 */
public final class M4CandidateDoorGameTests {
    private static final Map<UUID, FlushObservation> OBSERVATIONS = new HashMap<>();
    private static SelectionProbe selectionProbe;
    private static CandidatePublishProbe publishProbe;
    private static BatchFaultProbe batchFaultProbe;
    private static InitialFlushFaultProbe initialFlushFaultProbe;
    private static final String LOCKED_MESSAGE="量子候選已鎖定，等待塌縮";

    /**
     * 比照 M2 `_windows` 平台契約：真入場需要 Windows 原生玩家 checkpoint，非 Windows 在 per-test baseline 之前完成，
     * 不寫 receipt、不佔走廊容量；Windows 覆蓋由 CI 必要清單要求實際通過。
     */
    private static void platformSkip(TestContext context,String name) {
        org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info("平台略過：{} 需要 Windows 原生玩家 checkpoint",name);
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_first_wins",tickLimit=100000)
    public void native_selection_all_25_cells_first_wins_and_measured_freeze_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_all_25_cells_first_wins_and_measured_freeze_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_all_25_cells_first_wins_and_measured_freeze_windows");
        nativeCandidates(context,2,(fixture,tick) -> {
            var before=fixture.record(); var sid=before.sessionUuid();
            var mappings=fixture.pages.currentMappings(sid); var view=mappings.instances().getFirst();
            var key=new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL);
            var second=new DoorKey(sid,2,DoorKey.DoorWallSide.POSITIVE_LATERAL);
            var snapshot=nativeSnapshot(fixture);
            fixture.players.forEach(M4CandidateDoorGameTests::messages);
            var probe=new SelectionProbe(fixture,"observe",cell(view,second,1,1)); selectionProbe=probe;
            long time=fixture.target.getTime(); int serverTick=fixture.server.getTicks();
            net.minecraft.util.ActionResult first;
            try { first=use(fixture,cell(view,key,1,1),fixture.players.getFirst().player()); }
            finally { selectionProbe=null; }
            var selected=fixture.record();
            var message=messages(fixture.players.getFirst());
            var rejected=use(fixture,cell(view,second,1,1),fixture.players.get(1).player());
            var secondMessage=messages(fixture.players.get(1));
            for(int z=1;z<=5;z++) for(int y=1;y<=5;y++) {
                var pos=cell(view,key,y,z);
                context.assertEquals(net.minecraft.util.ActionResult.FAIL,use(fixture,pos,fixture.players.getFirst().player()),"同門25格在MEASURED都由候選互動拒絕");
                context.assertTrue(!fixture.target.getBlockState(pos).get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN),"選擇與重複點擊25格都不得開門");
            }
            var witness=List.of(first.isAccepted(),rejected==net.minecraft.util.ActionResult.FAIL,
                    selected.state()==SessionState.MEASURED,selected.equals(fixture.record()),
                    message.equals(List.of(LOCKED_MESSAGE)),secondMessage.equals(List.of("候選已鎖定。")),
                    serverTick==fixture.server.getTicks(),snapshot.equals(nativeSnapshot(fixture)),
                    context.getWorld().getBlockState(fixture.frame.controllerPos()).getComparatorOutput(context.getWorld(),fixture.frame.controllerPos())!=15);
            var expected=new CandidateSelection.Selected(key,fixture.pages.flushedCandidate(key).orElseThrow().candidateId(),
                    fixture.players.getFirst().player().getUuid(),time,1);
            context.waitAndRun(3,() -> {
                boolean frozen=fixture.pages.currentMappings(sid).equals(mappings) && fixture.pages.selectableDoors(sid).isEmpty()
                        && fixture.record().equals(selected) && snapshot.equals(nativeSnapshot(fixture));
                boolean remapRejected=rejects(() -> fixture.pages.prepareRemap(sid,mappings.epoch(),Set.of(1L)));
                var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
                boolean occupied=gateway.presence(fixture.server,selected.chamberUuid())==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.ACTIVE;
                boolean rearm=gateway.start(context.getWorld(),fixture.controller(),selected.participants().stream()
                        .map(SessionRecoveryRecord.Participant::playerUuid).toList())==dev.quantumchamber.chamber.ChamberSessionGateway.StartResult.REJECTED;
                selectionTeardown(fixture,Math.toIntExact(context.getTick()+1),() -> {
                    context.assertTrue(!witness.contains(false),"真互動first-wins／25格／actionbar／副作用："+witness+" actionbar="+message+" second="+secondMessage);
                    context.assertEquals(Optional.of(expected),selected.candidateSelection(),"durable selectedBy/time/door/candidate固定");
                    context.assertTrue(probe.before && probe.after && probe.exclusive,"flush前後持有同一guard且尚未送成功訊息");
                    context.assertTrue(frozen && remapRejected && occupied && rearm,"MEASURED停止publish/remap、保留mapping並阻止rearm");
                });
            });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_rejections",tickLimit=100000)
    public void native_selection_rejects_identity_bbox_cohort_source_and_incomplete_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_rejects_identity_bbox_cohort_source_and_incomplete_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_rejects_identity_bbox_cohort_source_and_incomplete_windows");
        nativeCandidates(context,2,(fixture,tick) -> {
            var initial=fixture.record(); var sid=initial.sessionUuid(); var view=fixture.pages.currentMappings(sid).instances().getFirst();
            var key=new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL); var pos=cell(view,key,1,1);
            var player=fixture.players.getFirst().player(); var failures=new ArrayList<String>();
            try(var outsider=new ConnectedGameTestPlayer(fixture.target)) {
                outsider.player().teleport(fixture.target,player.getX(),player.getY(),player.getZ(),Set.of(),0,0);
                deny(fixture,pos,outsider.player(),"outsider",failures);
            }
            player.changeGameMode(net.minecraft.world.GameMode.SPECTATOR);
            deny(fixture,pos,player,"spectator",failures); player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
            var impostor=new net.minecraft.server.network.ServerPlayerEntity(fixture.server,fixture.target,player.getGameProfile(),
                    net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault());
            deny(fixture,pos,impostor,"同UUID錯誤實例",failures);
            var pose=player.getPos(); var box=player.getBoundingBox();
            player.setBoundingBox(box.offset(100000,0,0)); deny(fixture,pos,player,"bbox超出mapping",failures); player.setBoundingBox(box);
            var bounds=view.bounds();
            player.setBoundingBox(new net.minecraft.util.math.Box(bounds.getMinX()-.1,bounds.getMinY()+1,bounds.getMinZ()+1,
                    bounds.getMinX()+.5,bounds.getMinY()+2.8,bounds.getMinZ()+1.6));
            deny(fixture,pos,player,"bbox跨越邊界",failures); player.setBoundingBox(box);
            player.teleport(context.getWorld(),pose.x,pose.y,pose.z,Set.of(),player.getYaw(),player.getPitch());
            deny(fixture,pos,player,"actual world錯誤",failures);
            player.teleport(fixture.target,pose.x,pose.y,pose.z,Set.of(),player.getYaw(),player.getPitch());
            var other=fixture.players.get(1).player(); var effect=new net.minecraft.entity.effect.StatusEffectInstance(other.getStatusEffect(dev.quantumchamber.registry.ModEffects.QUANTUM_STATE));
            other.removeStatusEffect(dev.quantumchamber.registry.ModEffects.QUANTUM_STATE);
            deny(fixture,pos,player,"完整cohort buff失效",failures); other.addStatusEffect(effect);
            var sourceController=fixture.controller(); sourceController.markRemoved();
            try { deny(fixture,pos,player,"來源Controller失效",failures); } finally { sourceController.cancelRemoval(); }
            var missing=cell(view,key,5,5);
            ChamberProtectionService.get().authorizedMutation(fixture.target,missing,() -> fixture.target.setBlockState(missing,net.minecraft.block.Blocks.AIR.getDefaultState(),3));
            deny(fixture,pos,player,"incomplete25格",failures);
            ChamberProtectionService.get().authorizedMutation(fixture.target,missing,() -> fixture.target.setBlockState(missing,ModBlocks.QUANTUM_BULKHEAD.getDefaultState(),3));
            var space=M4CandidateTestAccess.space(fixture.pages,sid);
            var current=fixture.pages.currentMappings(sid);
            var wrong=new CorridorPageManager.MappingView(new CorridorPageManager.MappingRef(sid,view.ref().instanceEpoch()+99,view.ref().slotId()),
                    view.firstCorePage(),view.lastCorePage(),view.aliasStartBlock(),view.aliasEndBlock(),view.logicalAnchorBlock(),view.localBlockOrigin(),view.outwardFacing(),view.bounds());
            M4CandidateTestAccess.set(space,"current",new CorridorPageManager.MappingSet(current.epoch()+1,List.of(wrong)));
            try { deny(fixture,pos,player,"錯誤mapping instance",failures); } finally { M4CandidateTestAccess.set(space,"current",current); }
            for(String field : List.of("failed","releasing","operations")) {
                var previous=M4CandidateTestAccess.get(space,field); M4CandidateTestAccess.set(space,field,field.equals("operations") ? 1 : true);
                try { deny(fixture,pos,player,field,failures); } finally { M4CandidateTestAccess.set(space,field,previous); }
            }
            var journal=SessionRecoveryState.get(fixture.server);
            var interaction=new dev.quantumchamber.candidate.CandidateDoorInteraction();
            context.assertTrue(interaction.onBulkheadUse(context.getWorld(),pos,player).isEmpty(),"非Superposition世界回既有Origin流程");
            var entrance=fixture.pages.entrance(sid);
            context.assertTrue(interaction.onBulkheadUse(fixture.target,dev.quantumchamber.chamber.ChamberGeometry.localToWorld(entrance,1,1,0),player).isEmpty(),
                    "真正入口返還門保留既有入口流程");
            context.assertEquals(Optional.of(net.minecraft.util.ActionResult.FAIL),java.util.concurrent.CompletableFuture.supplyAsync(
                    () -> interaction.onBulkheadUse(fixture.target,pos,player)).join(),"非server thread不可讀選擇權威");
            journal.put(initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.RETURNING,false));
            deny(fixture,pos,player,"dirty RETURNING",failures);
            context.assertEquals(initial,journal.flushedRecords().get(sid),"所有拒絕均不得覆寫durable record");
            journal.flush(fixture.server); deny(fixture,pos,player,"durable RETURNING",failures);
            fixture.trustedTeardown(tick+1,() -> { context.assertTrue(failures.isEmpty(),"側門拒絕不得fallthrough："+failures); M4PerTestUniverseProbe.complete(context); });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_flush_before",tickLimit=100000)
    public void native_selection_flush_failure_never_sends_success_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_flush_failure_never_sends_success_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_flush_failure_never_sends_success_windows");
        selectionFailure(context,"before");
    }
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_flush_after",tickLimit=100000)
    public void native_selection_readback_failure_never_sends_success_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_readback_failure_never_sends_success_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_readback_failure_never_sends_success_windows");
        selectionFailure(context,"after");
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_fault_other_flush",tickLimit=100000)
    public void native_selection_fault_never_admitted_by_other_session_flush_or_native_save_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_fault_never_admitted_by_other_session_flush_or_native_save_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_fault_never_admitted_by_other_session_flush_or_native_save_windows");
        nativeCandidates(context,1,(fixture,tick) -> {
            var before=fixture.record(); var sid=before.sessionUuid(); var connection=fixture.players.getFirst();
            var pos=cell(fixture.pages.currentMappings(sid).instances().getFirst(),new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL),1,1);
            var probe=new SelectionProbe(fixture,"before",pos); messages(connection); selectionProbe=probe;
            net.minecraft.util.ActionResult outcome;
            try { outcome=use(fixture,pos,connection.player()); } finally { selectionProbe=null; }
            var received=messages(connection); var journal=SessionRecoveryState.get(fixture.server);
            var current=journal.records().get(sid); var flushed=journal.flushedRecords().get(sid);
            // 另一個 session 的 checked 寫入與原生 saveAll 都經同一 save(File) 把整份 journal 落盤。
            var other=M4CandidateTestAccess.syntheticSession(fixture.server,UUID.randomUUID(),10003);
            journal.put(other); journal.flush(fixture.server);
            boolean nativeSaved=fixture.server.saveAll(true,true,true);
            var disk=M4CandidateTestAccess.diskRecords(fixture.server).get(sid);
            journal.remove(other.sessionUuid()); journal.flush(fixture.server);
            var witness=new LinkedHashMap<String,Boolean>();
            witness.put("faultHit",probe.before);
            witness.put("outcomeFail",outcome==net.minecraft.util.ActionResult.FAIL);
            witness.put("noSuccessOrAlreadyMessage",!received.contains(LOCKED_MESSAGE) && !received.contains("候選已鎖定。"));
            witness.put("currentEqualsFlushed",flushed.equals(current));
            witness.put("flushedReturningSelectable",flushed.state()==SessionState.RETURNING
                    && flushed.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable);
            witness.put("flushedLedgerUnchanged",flushed.candidateLedger().equals(before.candidateLedger()));
            witness.put("nativeSaved",nativeSaved);
            witness.put("diskNotSelected",disk!=null && disk.state()!=SessionState.MEASURED
                    && disk.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable);
            witness.put("diskEqualsFlushed",flushed.equals(disk));
            witness.put("otherSessionRemoved",!journal.flushedRecords().containsKey(other.sessionUuid()));
            var diskSummary=disk==null ? "ABSENT" : disk.state()+"/"+disk.candidateSelection().orElseThrow().getClass().getSimpleName();
            selectionTeardown(fixture,tick+1,() -> context.assertTrue(!witness.containsValue(false),
                    "選擇 flush fault 後其他 session flush／原生存檔不得承認 SELECTED："+witness+" disk="+diskSummary));
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_batch_fault_returning",tickLimit=100000)
    public void native_candidate_batch_fault_converges_to_checked_returning_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_candidate_batch_fault_converges_to_checked_returning_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_candidate_batch_fault_converges_to_checked_returning_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        var probe=new BatchFaultProbe(fixture); batchFaultProbe=probe;
        context.waitAndRun(2,fixture::power);
        whenWithin(context,3,60,() -> probe.fired,fired -> context.runAtTick(fired+1,() -> {
            batchFaultProbe=null;
            var sid=probe.faulted.sessionUuid(); var journal=SessionRecoveryState.get(fixture.server);
            var current=journal.records().get(sid); var flushed=journal.flushedRecords().get(sid);
            context.assertTrue(flushed!=null && current!=null,"batch fault 後 session record 不得消失");
            var other=M4CandidateTestAccess.syntheticSession(fixture.server,UUID.randomUUID(),10004);
            journal.put(other); journal.flush(fixture.server);
            boolean nativeSaved=fixture.server.saveAll(true,true,true);
            var disk=M4CandidateTestAccess.diskRecords(fixture.server).get(sid);
            journal.remove(other.sessionUuid()); journal.flush(fixture.server);
            var witness=new LinkedHashMap<String,Boolean>();
            witness.put("faultHitPendingBatch",probe.pending>0);
            witness.put("flushedReturning",flushed.state()==SessionState.RETURNING);
            witness.put("flushedLedgerIsPreFault",flushed.candidateLedger().equals(probe.faulted.candidateLedger()));
            witness.put("currentEqualsFlushed",flushed.equals(current));
            witness.put("noSelectableDoor",fixture.pages.selectableDoors(sid).isEmpty());
            witness.put("nativeSaved",nativeSaved);
            witness.put("diskEqualsFlushed",flushed.equals(disk));
            witness.put("otherSessionRemoved",!journal.flushedRecords().containsKey(other.sessionUuid()));
            context.assertTrue(!witness.containsValue(false),"candidate batch flush fault 必須同 process 收斂到 checked RETURNING："+witness
                    +" flushed="+flushed.state()+"/"+flushed.candidateLedger().size()+" disk="+(disk==null ? "ABSENT" : disk.state()+"/"+disk.candidateLedger().size()));
            fixture.trustedTeardown(fired+2,() -> M4PerTestUniverseProbe.complete(context));
        }),() -> "batch fault 未命中");
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_start_flush_fault",tickLimit=100000)
    public void native_start_initial_flush_fault_rejects_without_residue_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_start_initial_flush_fault_rejects_without_residue_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_start_initial_flush_fault_rejects_without_residue_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        var probe=new InitialFlushFaultProbe(fixture);
        context.waitAndRun(2,() -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server); var player=fixture.players.getFirst().player();
            var records=journal.records(); var flushed=journal.flushedRecords();
            var runtime=runtimeOwnership(fixture);
            initialFlushFaultProbe=probe;
            try { fixture.power(); } finally { initialFlushFaultProbe=null; }
            // start 由供電同步觸發（同一次拉桿可能有多個鄰格更新而重試）；取證後立即斷電，避免 20 tick 週期 refresh 在故障解除後入場。
            var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway(); var chamber=fixture.controller().chamberUuid();
            var witness=new LinkedHashMap<String,Boolean>();
            witness.put("initialFlushFaultHit",probe.faults>0);
            witness.put("noJournalForChamber",journal.records().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber))
                    && journal.flushedRecords().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
            witness.put("journalUnchanged",records.equals(journal.records()) && flushed.equals(journal.flushedRecords()));
            // H3 q5：放棄後補一次 checked 寫出，所以不再比對 journal 檔 bytes；正式檔必須 exact 等於 flushed，且不含本 chamber 的 record。
            var disk=M4CandidateTestAccess.diskRecords(server);
            witness.put("diskEqualsFlushedWithoutChamber",disk.equals(journal.flushedRecords())
                    && disk.values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
            witness.put("presenceNone",gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
            witness.put("noReservationOrRuntimeResidue",runtime.equals(runtimeOwnership(fixture)));
            witness.put("recoveryNotPaused",journal.recoveryWritesSafe());
            witness.put("participantStayedAtSource",player.getServerWorld()==context.getWorld());
            var presence=gateway.presence(server,chamber);
            if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
            context.assertTrue(!witness.containsValue(false),"初始 ARMING flush 失敗必須 CAS 移除並取消 reservation，REJECTED 且零殘留："+witness
                    +" presence="+presence+" faults="+probe.faults);
            whenWithin(context,3,30,() -> gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE
                    && journal.records().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)),done -> {
                fixture.close(); M4PerTestUniverseProbe.complete(context);
            },() -> "start fault cleanup presence="+gateway.presence(server,chamber));
        });
    }

    /**
     * H3 q5：初始 ARMING 已原子寫入正式檔、readback 才失敗（W-a）。abandonInitial 放棄之後必須補一次 checked 寫出，
     * 覆寫磁碟上未確認的 ARMING；同一 chamber 以同一原因反覆失敗時，WARN 只記一次並帶 root cause。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m5_h3_start_readback_fault",tickLimit=100000)
    public void native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_start_readback_fault_overwrites_unacknowledged_arming_and_dedupes_warning_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        var probe=new InitialFlushFaultProbe(fixture,InitialFlushFaultProbe.Mode.WRITE_THEN_FAIL);
        context.waitAndRun(2,() -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server); var player=fixture.players.getFirst().player();
            var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
            var hooks=new M2CorridorGameTests.TestHooks();
            var capture=new LogCapture("quantumchamber"); hooks.add(capture::close);
            dev.quantumchamber.chamber.ChamberSessionGateway.StartResult retried;
            initialFlushFaultProbe=probe;
            try {
                // 第一次由供電觸發；再以同一 cohort 直接重試一次，模擬週期 refresh 以同一原因反覆失敗。
                fixture.power();
                retried=gateway.start(context.getWorld(),fixture.controller(),List.of(player.getUuid()));
            } catch(RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            finally { initialFlushFaultProbe=null; }
            if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
            var chamber=fixture.controller().chamberUuid();
            var disk=M4CandidateTestAccess.diskRecords(server);
            var warnings=capture.matching(org.apache.logging.log4j.Level.WARN,"入場 ARMING 未能 checked 落盤");
            var witness=new LinkedHashMap<String,Boolean>();
            witness.put("faultedAtLeastTwice",probe.faults>=2);
            witness.put("unacknowledgedArmingReachedDisk",probe.diskHadArming);
            witness.put("retryRejected",retried==dev.quantumchamber.chamber.ChamberSessionGateway.StartResult.REJECTED);
            witness.put("noJournalForChamber",journal.records().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber))
                    && journal.flushedRecords().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
            witness.put("diskOverwrittenExactlyFlushed",disk.equals(journal.flushedRecords())
                    && disk.values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
            witness.put("presenceNone",gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
            witness.put("participantStayedAtSource",player.getServerWorld()==context.getWorld());
            witness.put("singleWarning",warnings.size()==1);
            witness.put("warningNamesChamberAndRootCause",warnings.size()==1 && warnings.getFirst().message().contains(chamber.toString())
                    && warnings.getFirst().message().contains(InitialFlushFaultProbe.READBACK_FAULT));
            try {
                context.assertTrue(!witness.containsValue(false),"初始 ARMING 寫入後 readback 失敗：放棄後必須以 checked 寫出覆寫磁碟，WARN 依 chamber＋原因去重："
                        +witness+" faults="+probe.faults+" warnings="+warnings.stream().map(LogCapture.Entry::message).toList());
            } catch(RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            hooks.release();
            whenWithin(context,3,30,() -> gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE
                    && journal.records().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)),done -> {
                fixture.close(); M4PerTestUniverseProbe.complete(context);
            },() -> "readback fault cleanup presence="+gateway.presence(server,chamber));
        });
    }

    /**
     * H3 q4：初始 flush 失敗＋abandonInitial 清理失敗＋RETURNING 也寫不出的殘留（current 有未 checked 的 RETURNING，沒有 flushed authority）。
     * 殘留不得被承認、不得讓 recovery 全域暫停；收斂為 checked 移除之後，reservation 與 runtime 也必須釋放，chamber 可再使用。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m5_h3_start_double_fault",tickLimit=100000)
    public void native_start_double_fault_residue_converges_and_releases_chamber_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_start_double_fault_residue_converges_and_releases_chamber_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_start_double_fault_residue_converges_and_releases_chamber_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        var probe=new InitialFlushFaultProbe(fixture,InitialFlushFaultProbe.Mode.DIVERGE_ONCE);
        context.waitAndRun(2,() -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server); var player=fixture.players.getFirst().player();
            var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
            initialFlushFaultProbe=probe;
            try { fixture.power(); } finally { initialFlushFaultProbe=null; }
            // 取證後立即斷電：殘留收斂、chamber 釋放之後，不讓週期 refresh 以同一 cohort 重新入場。
            if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
            var chamber=fixture.controller().chamberUuid(); var residue=probe.residue;
            context.assertTrue(residue!=null,"雙重故障必須命中初始 ARMING 的 checked flush");
            var sid=residue.sessionUuid();
            boolean residueHeld=residue.equals(journal.records().get(sid)) && !journal.flushedRecords().containsKey(sid);
            boolean recoveryNotPaused=journal.recoveryWritesSafe();
            boolean chamberHeld=gateway.presence(server,chamber)!=dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE;
            var hooks=new M2CorridorGameTests.TestHooks();
            // 只在失敗路徑使用：以 CAS 移除仍留在 current 的殘留，避免它在同一 server 被之後的寫出承認。
            hooks.add(() -> {
                var current=journal.records().get(sid);
                if(current!=null && !journal.flushedRecords().containsKey(sid)) { journal.abortUnchecked(current); journal.flush(server); }
            });
            whenWithinTicks(context,3,100,() -> gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE
                    && !journal.records().containsKey(sid),released -> {
                var disk=M4CandidateTestAccess.diskRecords(server);
                var witness=new LinkedHashMap<String,Boolean>();
                witness.put("residueHeldAfterDoubleFault",residueHeld);
                witness.put("recoveryNotPaused",recoveryNotPaused);
                witness.put("chamberHeldUntilConverged",chamberHeld);
                witness.put("residueNeverDurable",!journal.flushedRecords().containsKey(sid) && !disk.containsKey(sid)
                        && disk.values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
                witness.put("noSpaceRuntimeOrTicket",!((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"spaces")).containsKey(sid)
                        && !((Map<?,?>)M4CandidateTestAccess.get(gateway,"sessions")).containsKey(chamber)
                        && !((Map<?,?>)M4CandidateTestAccess.get(gateway,"tickets")).containsKey(sid));
                witness.put("participantStayedAtSourceUnblocked",player.getServerWorld()==context.getWorld() && !SessionRecoveryManager.blocks(player.getUuid(),server));
                context.assertTrue(!witness.containsValue(false),"雙重故障殘留必須收斂為 checked 移除並釋放 chamber："+witness);
                hooks.release(); fixture.close(); M4PerTestUniverseProbe.complete(context);
            },() -> "雙重故障殘留未收斂：presence="+gateway.presence(server,chamber)+" current="
                    +(journal.records().containsKey(sid) ? journal.records().get(sid).state() : "ABSENT")
                    +" durable="+journal.flushedRecords().containsKey(sid),hooks);
        });
    }

    /**
     * H3 q4（recovery tick 的保底）：不經任何 owner 留在 current 的未 checked ARMING（沒有 flushed authority）。
     * tick 必須只隔離並以 checked 寫出移除它，不得全域暫停 recovery，也不得讓任何寫出承認它。不需要原生玩家，各平台都執行。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m5_h3_unchecked_residue",tickLimit=100000)
    public void unchecked_residue_is_quarantined_and_removed_without_pausing_recovery(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"unchecked_residue_is_quarantined_and_removed_without_pausing_recovery");
        context.waitAndRun(1,() -> {
            var server=context.getWorld().getServer(); var journal=SessionRecoveryState.get(server);
            var residue=M4CandidateTestAccess.syntheticSession(server,UUID.randomUUID(),10008); var sid=residue.sessionUuid();
            journal.put(residue);
            boolean residueUnchecked=journal.uncheckedCandidateSessions().contains(sid);
            var hooks=new M2CorridorGameTests.TestHooks();
            // 只在失敗路徑使用：移除殘留（已被承認就一般 remove），避免它留在同一 server。
            hooks.add(() -> {
                var current=journal.records().get(sid);
                if(current==null) return;
                if(journal.flushedRecords().containsKey(sid)) journal.remove(sid); else journal.abortUnchecked(current);
                journal.flush(server);
            });
            whenWithinTicks(context,2,100,() -> !journal.records().containsKey(sid),removed -> {
                var disk=M4CandidateTestAccess.diskRecords(server);
                var witness=new LinkedHashMap<String,Boolean>();
                witness.put("residueWasUnchecked",residueUnchecked);
                witness.put("residueNeverDurable",!journal.flushedRecords().containsKey(sid) && !disk.containsKey(sid));
                witness.put("recoveryNotPaused",journal.recoveryWritesSafe());
                context.assertTrue(!witness.containsValue(false),"未 checked 殘留必須由 recovery 隔離並 checked 移除："+witness);
                hooks.release(); M4PerTestUniverseProbe.complete(context);
            },() -> "未 checked 殘留未收斂：current="+journal.records().containsKey(sid)+" durable="+journal.flushedRecords().containsKey(sid)
                    +" recoveryWritesSafe="+journal.recoveryWritesSafe(),hooks);
        });
    }

    /**
     * H3 q3：隔離中的 session 要能收斂。以 CAS 不符建立隔離（production 中 CAS 必定成立，這是防禦路徑）：未 checked 的 SELECTED
     * 之上再疊返還進度，再以原本的 next 呼叫 abort。recovery 必須以上一份 flushed authority 寫出 checked RETURNING，玩家照常返還。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m5_h3_quarantine_converges",tickLimit=100000)
    public void native_quarantined_selection_converges_to_checked_returning_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_quarantined_selection_converges_to_checked_returning_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_quarantined_selection_converges_to_checked_returning_windows");
        nativeCandidates(context,1,(fixture,tick) -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server);
            var before=fixture.record(); var sid=before.sessionUuid(); var player=fixture.players.getFirst().player();
            var entry=before.candidateLedger().getFirst();
            var selected=new SessionRecoveryRecord(sid,before.chamberUuid(),before.origin(),before.participants(),before.spaceLeases(),
                    SessionState.MEASURED,false,before.semantics(),before.candidateContext(),before.candidateLedger(),
                    Optional.of(new CandidateSelection.Selected(entry.doorKey(),entry.candidate().candidateId(),player.getUuid(),fixture.target.getTime(),1)));
            var returned=selected.participants().stream().map(person -> new SessionRecoveryRecord.Participant(person.playerUuid(),person.sourcePosition(),
                    person.sourceVelocity(),person.yaw(),person.pitch(),person.quantumStateSnapshot(),true)).toList();
            journal.put(selected); journal.put(selected.withProgress(returned,selected.spaceLeases(),SessionState.MEASURED,false));
            boolean quarantined=!journal.abortUnchecked(selected);
            boolean putRejected=rejects(() -> journal.put(before.withProgress(before.participants(),before.spaceLeases(),SessionState.RETURNING,false)));
            boolean recoveryNotPaused=journal.recoveryWritesSafe();
            whenWithinTicks(context,tick+1,200,() -> fixture.record()!=null && fixture.record().state()==SessionState.RETURNING,converged -> {
                var durable=fixture.record(); var disk=M4CandidateTestAccess.diskRecords(server).get(sid);
                var witness=new LinkedHashMap<String,Boolean>();
                witness.put("quarantinedByCasMismatch",quarantined);
                witness.put("quarantinedPutRejected",putRejected);
                witness.put("recoveryNotPaused",recoveryNotPaused);
                witness.put("convergedFromFlushedAuthority",SessionRecoveryRecord.sameAuthority(before,durable)
                        && durable.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable
                        && durable.candidateLedger().equals(before.candidateLedger()));
                witness.put("currentEqualsFlushed",durable.equals(journal.records().get(sid)));
                witness.put("diskNeverSelected",durable.equals(disk));
                context.assertTrue(!witness.containsValue(false),"隔離 session 必須收斂為 checked RETURNING："+witness
                        +" durable="+durable.state()+" disk="+(disk==null ? "ABSENT" : disk.state()));
                // 收尾即驗證玩家返還：全員回到原艙完整 bbox、record 已 checked 移除、保護解除。
                fixture.trustedTeardown(converged+1,() -> M4PerTestUniverseProbe.complete(context));
            },() -> "隔離 session 未收斂：durable="+(fixture.record()==null ? "ABSENT" : fixture.record().state())
                    +" current="+(journal.records().containsKey(sid) ? journal.records().get(sid).state() : "ABSENT"),null);
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_dormant_other_chamber",tickLimit=100000)
    public void dormant_receipt_blocks_only_its_chamber_and_participant_enters_other_chamber_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"dormant_receipt_blocks_only_its_chamber_and_participant_enters_other_chamber_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"dormant_receipt_blocks_only_its_chamber_and_participant_enters_other_chamber_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        context.waitAndRun(2,() -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server); var player=fixture.players.getFirst().player().getUuid();
            var dormant=M4CandidateTestAccess.dormantReceipt(server,player,10005);
            journal.put(dormant); journal.flush(server);
            var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
            var runtime=runtimeOwnership(fixture);
            fixture.power();
            whenWithin(context,3,60,fixture::activeReady,entered -> {
                var active=fixture.record();
                var witness=new LinkedHashMap<String,Boolean>();
                witness.put("activeSessionOwnsParticipant",active.participants().stream().anyMatch(person -> person.playerUuid().equals(player)));
                witness.put("dormantFlushedExact",dormant.equals(journal.flushedRecords().get(dormant.sessionUuid())));
                witness.put("dormantCurrentExact",dormant.equals(journal.records().get(dormant.sessionUuid())));
                witness.put("dormantPhase",MeasuredRecoveryPhase.from(journal.flushedRecords().get(dormant.sessionUuid()))==MeasuredRecoveryPhase.DORMANT);
                witness.put("dormantChamberBlocked",gateway.presence(server,dormant.chamberUuid())!=dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
                witness.put("participantNotRecoveryBlocked",!SessionRecoveryManager.blocks(player,server));
                context.assertTrue(!witness.containsValue(false),"DORMANT receipt 只封鎖自己的 Chamber："+witness);
                fixture.trustedTeardown(entered+1,() -> {
                    var after=new LinkedHashMap<String,Boolean>();
                    after.put("noSpaceSlotOrPageLeak",runtime.equals(runtimeOwnership(fixture)));
                    after.put("dormantStillExact",dormant.equals(journal.flushedRecords().get(dormant.sessionUuid())));
                    after.put("dormantChamberStillBlocked",gateway.presence(server,dormant.chamberUuid())!=dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
                    context.assertTrue(!after.containsValue(false),"另一座 Chamber 收尾後不得洩漏 slot/page，DORMANT receipt 不變："+after);
                    M4CandidateTestAccess.removeMeasuredForTeardown(server,dormant.sessionUuid());
                    M4PerTestUniverseProbe.complete(context);
                });
            },() -> "presence="+gateway.presence(server,fixture.controller().chamberUuid())+" record="+(fixture.record()==null ? "ABSENT" : fixture.record().state()));
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_busy_participant_start",tickLimit=100000)
    public void busy_participant_start_rejected_before_any_reservation_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"busy_participant_start_rejected_before_any_reservation_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"busy_participant_start_rejected_before_any_reservation_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        context.waitAndRun(2,() -> {
            var server=fixture.server; var journal=SessionRecoveryState.get(server); var player=fixture.players.getFirst().player();
            var busy=M4CandidateTestAccess.syntheticSession(server,player.getUuid(),10006);
            journal.put(busy); journal.flush(server);
            var records=journal.records(); var flushed=journal.flushedRecords(); var bytes=journalBytes(server);
            var runtime=runtimeOwnership(fixture);
            fixture.power();
            context.runAtTick(4,() -> {
                var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway(); var chamber=fixture.controller().chamberUuid();
                var witness=new LinkedHashMap<String,Boolean>();
                witness.put("noJournalForChamber",fixture.record()==null && journal.records().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber)));
                witness.put("presenceNone",gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE);
                witness.put("journalUnchanged",records.equals(journal.records()) && flushed.equals(journal.flushedRecords()) && java.util.Arrays.equals(bytes,journalBytes(server)));
                witness.put("noReservationOrRuntimeResidue",runtime.equals(runtimeOwnership(fixture)));
                witness.put("participantStayedAtSource",player.getServerWorld()==context.getWorld());
                context.assertTrue(!witness.containsValue(false),"忙碌參與者必須在任何 reservation 前 REJECTED 且零殘留："+witness
                        +" presence="+gateway.presence(server,chamber));
                if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                journal.remove(busy.sessionUuid()); journal.flush(server);
                whenWithin(context,5,30,() -> gateway.presence(server,chamber)==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE
                        && !journal.flushedRecords().containsKey(busy.sessionUuid()),done -> {
                    fixture.close(); M4PerTestUniverseProbe.complete(context);
                },() -> "busy cleanup presence="+gateway.presence(server,chamber));
            });
        });
    }

    /**
     * MEASURED 清理遇持續原生存檔失敗：checkpoint 依 per-space 20→40→… tick 指數退避、同一原因的 WARN 只附一次 stack；
     * 故障解除後完成 DORMANT，selection receipt 與 fail-closed 契約不變。GameTest server 不以 20 TPS 節流，故以 server tick 計數。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_ifix1_checkpoint_backoff",tickLimit=100000)
    public void native_measured_checkpoint_failure_backs_off_then_reaches_dormant_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_measured_checkpoint_failure_backs_off_then_reaches_dormant_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_measured_checkpoint_failure_backs_off_then_reaches_dormant_windows");
        nativeCandidates(context,1,(fixture,tick) -> {
            var sid=fixture.record().sessionUuid(); var view=fixture.pages.currentMappings(sid).instances().getFirst();
            var player=fixture.players.getFirst().player();
            var selected=use(fixture,cell(view,new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL),1,1),player);
            context.assertTrue(selected.isAccepted() && fixture.record().state()==SessionState.MEASURED,"真側門 checked SELECTED 後才進入 MEASURED 清理");
            // checkpoint fault probe 與 log capture 是本測試專用 hook：任一段等待逾時或斷言失敗都必須移除。
            var hooks=new M2CorridorGameTests.TestHooks();
            var probe=new CheckpointFaultProbe(sid); checkpointFaultProbe=probe;
            hooks.add(() -> { if(checkpointFaultProbe==probe) checkpointFaultProbe=null; });
            LogCapture capture;
            try {
                capture=new LogCapture("quantumchamber"); hooks.add(capture::close);
                player.removeStatusEffect(dev.quantumchamber.registry.ModEffects.QUANTUM_STATE);
            } catch(RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            whenWithin(context,tick+1,120,() -> !probe.calls.isEmpty(),first -> {
                int start=probe.calls.getFirst();
                whenWithin(context,first+1,120,() -> fixture.server.getTicks()>=start+100,observed -> {
                    var calls=List.copyOf(probe.calls);
                    probe.armed=false;
                    whenWithin(context,observed+1,120,() -> fixture.pages.measuredRecoveryComplete(sid),dormant -> {
                        var receipt=SessionRecoveryState.get(fixture.server).flushedRecords().get(sid);
                        List<LogCapture.Entry> warnings;
                        try { warnings=capture.matching(org.apache.logging.log4j.Level.WARN,"MEASURED 保留 receipt 等待安全返還",sid.toString()); }
                        finally { hooks.release(); }
                        var gaps=new ArrayList<Integer>(); for(int i=1;i<calls.size();i++) gaps.add(calls.get(i)-calls.get(i-1));
                        var witness=new LinkedHashMap<String,Boolean>();
                        witness.put("threeCallsInFirst100Ticks",calls.size()==3);
                        witness.put("backoffGaps20Then40",gaps.equals(List.of(20,40)));
                        witness.put("singleWarnWithStack",warnings.size()==1 && warnings.getFirst().thrown()!=null);
                        witness.put("retriedAfterRecovery",probe.calls.size()>calls.size());
                        witness.put("dormantAfterRecovery",receipt!=null && MeasuredRecoveryPhase.from(receipt)==MeasuredRecoveryPhase.DORMANT);
                        witness.put("selectionReceiptKept",receipt!=null && receipt.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected);
                        witness.put("playerReturnedToSource",player.getServerWorld()==context.getWorld());
                        context.assertTrue(!witness.containsValue(false),"MEASURED checkpoint 退避／去重／恢復："+witness+" calls="+calls+" gaps="+gaps
                                +" warnings="+warnings.size()+" totalCalls="+probe.calls.size());
                        // DORMANT receipt 是本案例自建；取證後移除，避免封鎖後續 batch 的玩家與 Chamber。
                        M4CandidateTestAccess.removeMeasuredForTeardown(fixture.server,sid);
                        if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                        whenWithin(context,dormant+1,30,() -> ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos()),done -> {
                            fixture.close(); M4PerTestUniverseProbe.complete(context);
                        },() -> "DORMANT receipt 移除並 LOW 後來源保護未解除");
                    },() -> "故障解除後未完成 DORMANT：calls="+probe.calls,hooks);
                },() -> "退避觀察窗口逾時：calls="+probe.calls,hooks);
            },() -> "MEASURED checkpoint 未被呼叫",hooks);
        });
    }
    private static CheckpointFaultProbe checkpointFaultProbe;
    /** testmod mixin 在 MeasuredWorldSaveCheckpoint.save 開頭呼叫；只對 own SID 記錄 tick，故障窗口內受控失敗。 */
    public static void beforeMeasuredCheckpoint(MinecraftServer server,SessionRecoveryRecord record) {
        var scope=checkpointScopeProbe;
        if(scope!=null && server.isOnThread() && scope.sid.equals(record.sessionUuid())) scope.beforeCall(server);
        var probe=checkpointFaultProbe;
        if(probe==null || !server.isOnThread() || !probe.sid.equals(record.sessionUuid())) return;
        probe.calls.add(server.getTicks());
        if(probe.armed) throw new IllegalStateException("M4 GameTest 受控 MEASURED 原生存檔 checkpoint 失敗");
    }
    /** testmod mixin 在 MeasuredWorldSaveCheckpoint.save 正常返回（checkpoint 被承認）時呼叫；例外路徑不會經過。 */
    public static void afterMeasuredCheckpoint(MinecraftServer server,SessionRecoveryRecord record) {
        var scope=checkpointScopeProbe;
        if(scope!=null && server.isOnThread() && scope.sid.equals(record.sessionUuid())) scope.afterCall(server);
    }
    private static final class CheckpointFaultProbe {
        final UUID sid; final List<Integer> calls=new ArrayList<>(); boolean armed=true;
        CheckpointFaultProbe(UUID sid) { this.sid=sid; }
    }

    /**
     * M5 H2（spec v5.3）：MEASURED 原生存檔 checkpoint 以「同一 storage 的同一個 region 檔」判定，只看相關 chunks 所在的 region。
     * 三次 checkpoint 依序讓不同 chunk 的原生 region write（chunk＋entities）真的失敗：
     * 1. 來源艙 controller 所在 chunk（相關）：必須 fail-closed、保留 leases；
     * 2. 同一 region 內、不在來源艙範圍的已載入 chunk（region header 共用）：仍必須 fail-closed、保留 leases，退避 20→40；
     * 3. 同一世界、同一份 storage、但不在任何相關 region 的 chunk：checkpoint 必須被承認並完成 DORMANT。
     * 被承認後 MEASURED 清理即結束，所以會擋下的情形排在前面。故障在原生 IO worker 內發生，並只對故障目標等真 IO 完成，
     * 外層 server.save 照常回 true（見 ChunkCheckpointScopeFaultMixin）。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m5_h2_checkpoint_scope",tickLimit=100000)
    public void native_measured_checkpoint_ignores_unrelated_chunk_failure_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_measured_checkpoint_ignores_unrelated_chunk_failure_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_measured_checkpoint_ignores_unrelated_chunk_failure_windows");
        nativeCandidates(context,1,(fixture,tick) -> {
            var sid=fixture.record().sessionUuid(); var view=fixture.pages.currentMappings(sid).instances().getFirst();
            var player=fixture.players.getFirst().player();
            var selected=use(fixture,cell(view,new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL),1,1),player);
            context.assertTrue(selected.isAccepted() && fixture.record().state()==SessionState.MEASURED,"真側門 checked SELECTED 後才進入 MEASURED 清理");
            var measured=fixture.record();
            // fault target、probe、probe 強制載入的 chunk 與 log capture 是本測試專用 hook：任一段等待逾時或斷言失敗都必須移除。
            var hooks=new M2CorridorGameTests.TestHooks();
            CheckpointScopeProbe probe; LogCapture capture;
            try {
                probe=new CheckpointScopeProbe(context.getWorld(),fixture.pages,measured); checkpointScopeProbe=probe;
                hooks.add(() -> { if(checkpointScopeProbe==probe) checkpointScopeProbe=null; probe.release(); });
                capture=new LogCapture("quantumchamber"); hooks.add(capture::close);
            } catch(RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            // 其他 region 的 chunk 可能離來源艙 16 個 chunk：先確認它與 entity storage 都已載入，才讓玩家返還、開始清理。
            whenWithin(context,tick+1,60,probe::otherRegionLoaded,loaded -> {
                player.removeStatusEffect(dev.quantumchamber.registry.ModEffects.QUANTUM_STATE);
                whenWithin(context,loaded+1,120,() -> probe.accepted() || probe.calls.size()>=4,ended -> {
                    var calls=List.copyOf(probe.calls);
                    var first=calls.getFirst(); var second=calls.size()>=2 ? calls.get(1) : null; var third=calls.size()>=3 ? calls.get(2) : null;
                    var witness=new LinkedHashMap<String,Boolean>();
                    witness.put("relevantFaultFiredChunkAndEntities",first.target==probe.relevant && first.faultTypes().containsAll(Set.of("chunk","entities")));
                    witness.put("relevantFailureFailedClosed",!first.accepted);
                    witness.put("sameRegionFaultFiredChunkAndEntities",second!=null && second.target==probe.sameRegion && second.faultTypes().containsAll(Set.of("chunk","entities")));
                    witness.put("sameRegionFailureFailedClosed",second!=null && !second.accepted);
                    witness.put("leasesRetainedAfterBothFailures",second!=null && second.retainedBefore && third!=null && third.retainedBefore);
                    witness.put("backoffGaps20Then40",third!=null && second.tick-first.tick==20 && third.tick-second.tick==40);
                    witness.put("otherRegionFaultFiredChunkAndEntities",third!=null && third.target==probe.otherRegion && third.faultTypes().containsAll(Set.of("chunk","entities")));
                    witness.put("productionObservedOtherRegionFailure",third!=null && third.serverFailures()>=2 && third.workerFailures()>=1);
                    witness.put("otherRegionFailureAccepted",third!=null && third.accepted);
                    witness.put("exactlyThreeCalls",calls.size()==3);
                    // 前兩次的受控 write 都要涵蓋 chunk 與 entities 兩份 storage。
                    witness.put("failedCallsWroteChunkAndEntities",second!=null && first.writesAfter-first.writesBefore>=2 && second.writesAfter-second.writesBefore>=2);
                    // 兩次失敗同一 region、同一來源：WARN 只記一次（附 stack），訊息穩定，沒有「前一原因另重複」行。
                    var pos=probe.relevant.pos;
                    var expectedCause="相關 region 的原生序列化或存檔已回報失敗，拒絕承認 checkpoint：source=chunk world="
                            +context.getWorld().getRegistryKey().getValue()+" region=["+pos.getRegionX()+", "+pos.getRegionZ()+"]";
                    var warnings=capture.matching(org.apache.logging.log4j.Level.WARN,"MEASURED 保留 receipt 等待安全返還",sid.toString());
                    witness.put("singleStableRegionWarnWithStack",warnings.size()==1 && warnings.getFirst().thrown()!=null
                            && expectedCause.equals(warnings.getFirst().thrown().getMessage()));
                    witness.put("noRepeatedCauseWarn",capture.matching(org.apache.logging.log4j.Level.WARN,"前一失敗原因另重複",sid.toString()).isEmpty());
                    var diagnostics=" calls="+calls+" relevant="+probe.relevant+" sameRegion="+probe.sameRegion+" otherRegion="+probe.otherRegion
                            +" relevantRegions="+probe.relevantRegions+" forcedByProbe="+probe.forcedByProbe
                            +" warnings="+warnings.stream().map(entry -> entry.thrown()==null ? "null" : entry.thrown().getMessage()).toList();
                    context.assertTrue(!witness.containsValue(false),"H2 checkpoint 以 region 判定："+witness+diagnostics);
                    whenWithin(context,ended+1,120,() -> fixture.pages.measuredRecoveryComplete(sid),dormant -> {
                        var finalReceipt=SessionRecoveryState.get(fixture.server).flushedRecords().get(sid);
                        hooks.release();
                        var after=new LinkedHashMap<String,Boolean>();
                        after.put("dormantAfterAcceptedCheckpoint",finalReceipt!=null && MeasuredRecoveryPhase.from(finalReceipt)==MeasuredRecoveryPhase.DORMANT);
                        after.put("selectionReceiptKept",finalReceipt!=null && finalReceipt.candidateSelection().orElseThrow() instanceof CandidateSelection.Selected);
                        after.put("stillThreeCalls",probe.calls.size()==3);
                        after.put("playerReturnedToSource",player.getServerWorld()==context.getWorld());
                        after.put("probeForcedChunkReleased",!probe.forcedByProbe || !context.getWorld().getForcedChunks().contains(probe.otherRegion.pos.toLong()));
                        context.assertTrue(!after.containsValue(false),"H2 承認後完成 DORMANT："+after+" calls="+probe.calls);
                        // DORMANT receipt 是本案例自建；取證後移除，避免封鎖後續 batch 的玩家與 Chamber。
                        M4CandidateTestAccess.removeMeasuredForTeardown(fixture.server,sid);
                        if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                        whenWithin(context,dormant+1,30,() -> ChamberProtectionService.get().mayMutate(context.getWorld(),fixture.frame.controllerPos()),done -> {
                            fixture.close(); M4PerTestUniverseProbe.complete(context);
                        },() -> "DORMANT receipt 移除並 LOW 後來源保護未解除");
                    },() -> "承認後未完成 DORMANT：calls="+probe.calls,hooks);
                },() -> "checkpoint 未承認也未達第四次呼叫：calls="+probe.calls,hooks);
            },() -> "其他 region 的 chunk 未載入："+probe.otherRegion,hooks);
        });
    }
    private static volatile CheckpointScopeProbe checkpointScopeProbe;
    /** testmod mixin（StorageIoWorker.write 內的 region write）：只對目前故障目標讓原生 write 受控失敗；在原生 IO thread 執行。 */
    public static void beforeScopedRegionWrite(net.minecraft.world.storage.StorageKey key,net.minecraft.util.math.ChunkPos pos) throws java.io.IOException {
        var probe=checkpointScopeProbe; var target=probe==null ? null : probe.target;
        if(target==null || !target.matches(key,pos)) return;
        target.types.add(key.type()); target.writes.incrementAndGet();
        throw new java.io.IOException("M5 H2 GameTest 受控 region write 失敗："+target.label);
    }
    /** testmod mixin（StorageIoWorker.setResult RETURN）：只對故障目標等真 IO 完成，讓失敗 entry 不會留到 completeAll。 */
    public static void drainScopedFault(net.minecraft.world.storage.StorageKey key,net.minecraft.util.math.ChunkPos pos,java.util.concurrent.CompletableFuture<?> future) {
        var probe=checkpointScopeProbe; var target=probe==null ? null : probe.target;
        if(target!=null && target.matches(key,pos)) future.handle((value,error) -> null).join();
    }
    /**
     * 故障目標依呼叫順序：第一次是相關 chunk（controller 所在 chunk），第二次是同 region 的無關 chunk，之後是其他 region 的 chunk。
     * region 與原版 region 檔一致（ChunkPos.getRegionX／Z）；三者都在來源世界，chunk 與 entities 兩份 storage 都會注入。
     */
    private static final class CheckpointScopeProbe {
        final UUID sid; final net.minecraft.server.world.ServerWorld world; final CorridorPageManager pages; final List<SessionRecoveryRecord.SpaceLease> leases;
        final Set<Long> relevantRegions; final ScopedFault relevant,sameRegion,otherRegion; final boolean forcedByProbe;
        final List<ScopedCall> calls=new ArrayList<>();
        volatile ScopedFault target;
        CheckpointScopeProbe(net.minecraft.server.world.ServerWorld world,CorridorPageManager pages,SessionRecoveryRecord record) {
            if(!world.getRegistryKey().getValue().equals(record.origin().worldKey())) throw new IllegalStateException("H2 probe 必須建立在來源世界");
            sid=record.sessionUuid(); this.world=world; this.pages=pages; leases=record.spaceLeases();
            // 與 production 相同的來源艙相關範圍：ChamberGeometry.bounds 涵蓋的 chunks。
            var frame=new ChamberFrame(record.origin().controllerPos(),record.origin().facing());
            var bounds=dev.quantumchamber.chamber.ChamberGeometry.bounds(frame); var related=new HashSet<Long>(); var regions=new HashSet<Long>();
            for(int x=bounds.getMinX()>>4;x<=bounds.getMaxX()>>4;x++) for(int z=bounds.getMinZ()>>4;z<=bounds.getMaxZ()>>4;z++) {
                var pos=new net.minecraft.util.math.ChunkPos(x,z); related.add(pos.toLong()); regions.add(region(pos));
            }
            relevantRegions=Set.copyOf(regions);
            var controller=new net.minecraft.util.math.ChunkPos(frame.controllerPos());
            relevant=new ScopedFault("relevant",world.getRegistryKey(),controller);
            sameRegion=new ScopedFault("same-region",world.getRegistryKey(),sameRegionLoaded(world,controller,related));
            var other=otherRegionChunk(controller,relevantRegions);
            otherRegion=new ScopedFault("other-region",world.getRegistryKey(),other);
            // 其他 region 的 chunk 不一定已載入：只在原本未強制載入時由 probe 強制載入，收尾時解除。
            forcedByProbe=!world.getForcedChunks().contains(other.toLong());
            if(forcedByProbe) world.setChunkForced(other.x,other.z,true);
        }
        private static long region(net.minecraft.util.math.ChunkPos pos) { return net.minecraft.util.math.ChunkPos.toLong(pos.getRegionX(),pos.getRegionZ()); }
        /** 由近而遠找第一個「與 controller 同 region、chunk 與 entity storage 都已載入、但不在來源艙範圍內」的 chunk。 */
        private static net.minecraft.util.math.ChunkPos sameRegionLoaded(net.minecraft.server.world.ServerWorld world,net.minecraft.util.math.ChunkPos center,Set<Long> related) {
            var entities=(net.minecraft.server.world.ServerEntityManager<?>)M4CandidateTestAccess.get(world,"entityManager");
            for(int radius=1;radius<=4;radius++) for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                if(Math.max(Math.abs(dx),Math.abs(dz))!=radius) continue;
                var pos=new net.minecraft.util.math.ChunkPos(center.x+dx,center.z+dz);
                if(region(pos)==region(center) && !related.contains(pos.toLong())
                        && world.getChunkManager().getWorldChunk(pos.x,pos.z)!=null && entities.isLoaded(pos.toLong())) return pos;
            }
            throw new IllegalStateException("H2 probe 找不到同 region 的已載入無關 chunk");
        }
        /** 沿四個軸向由近而遠找第一個不在任何相關 region 的 chunk（距離不超過 17 個 chunk）。 */
        private static net.minecraft.util.math.ChunkPos otherRegionChunk(net.minecraft.util.math.ChunkPos center,Set<Long> regions) {
            for(int distance=1;distance<=64;distance++) for(int[] axis : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                var pos=new net.minecraft.util.math.ChunkPos(center.x+axis[0]*distance,center.z+axis[1]*distance);
                if(!regions.contains(region(pos))) return pos;
            }
            throw new IllegalStateException("H2 probe 找不到其他 region 的 chunk");
        }
        boolean otherRegionLoaded() {
            var entities=(net.minecraft.server.world.ServerEntityManager<?>)M4CandidateTestAccess.get(world,"entityManager");
            return world.getChunkManager().getWorldChunk(otherRegion.pos.x,otherRegion.pos.z)!=null && entities.isLoaded(otherRegion.pos.toLong());
        }
        void beforeCall(MinecraftServer server) {
            // 前一次呼叫若以例外結束，沒有 RETURN 收據：以本次開始時的計數收尾（兩次呼叫之間沒有其他故障目標）。
            if(!calls.isEmpty() && !calls.getLast().closed()) calls.getLast().close(serverRevision(server),workerRevision());
            var fault=switch(calls.size()) { case 0 -> relevant; case 1 -> sameRegion; default -> otherRegion; };
            if(fault!=relevant) {
                // 無關 chunk 不在 production 的 redirty 範圍：本 hook 自行讓它的 chunk 與 entity 在這次 server.save 都會寫入。
                var chunk=world.getChunkManager().getWorldChunk(fault.pos.x,fault.pos.z);
                if(chunk==null) throw new IllegalStateException("H2 故障目標 chunk 已卸載："+fault);
                chunk.setNeedsSaving(true); emptyEntityChunks().remove(fault.pos.toLong());
            }
            // 前一次失敗之後、這一次開始之前：flushed receipt、leases 與 runtime space 必須原樣保留。
            var receipt=SessionRecoveryState.get(server).flushedRecords().get(sid);
            boolean retained=!calls.isEmpty() && receipt!=null && receipt.state()==SessionState.MEASURED && receipt.spaceLeases().equals(leases)
                    && MeasuredRecoveryPhase.from(receipt)==MeasuredRecoveryPhase.RELEASE_GEOMETRY && M4CandidateTestAccess.space(pages,sid)!=null;
            calls.add(new ScopedCall(server.getTicks(),fault,Set.copyOf(fault.types),fault.writes.get(),serverRevision(server),workerRevision(),retained));
            target=fault;
        }
        void afterCall(MinecraftServer server) {
            var call=calls.getLast(); call.accepted=true; target=null;
            call.close(serverRevision(server),workerRevision());
        }
        boolean accepted() { return calls.stream().anyMatch(call -> call.accepted); }
        /** 解除故障，讓兩個無關 chunk 在之後的一般存檔重寫正常資料，並解除 probe 自己的強制載入。 */
        void release() {
            target=null;
            try {
                for(var fault : List.of(sameRegion,otherRegion)) {
                    var chunk=world.getChunkManager().getWorldChunk(fault.pos.x,fault.pos.z);
                    if(chunk!=null) chunk.setNeedsSaving(true);
                    emptyEntityChunks().remove(fault.pos.toLong());
                }
            } finally {
                // 重新標髒失敗也必須解除 probe 自己的強制載入，不能留到後續 batch。
                if(forcedByProbe) world.setChunkForced(otherRegion.pos.x,otherRegion.pos.z,false);
            }
        }
        private it.unimi.dsi.fastutil.longs.LongSet emptyEntityChunks() {
            return (it.unimi.dsi.fastutil.longs.LongSet)M4CandidateTestAccess.get(M4CandidateTestAccess.get(M4CandidateTestAccess.get(world,"entityManager"),"dataAccess"),"emptyChunks");
        }
        private static long serverRevision(MinecraftServer server) { return ((NativeSaveFailureAccess)server).quantumchamber$saveFailureRevision(); }
        private long workerRevision() {
            return ((NativeStorageSaveAccess)world.getChunkManager().getChunkIoWorker()).quantumchamber$saveFailures().revision();
        }
    }
    private static final class ScopedFault {
        final String label; final net.minecraft.registry.RegistryKey<World> dimension; final net.minecraft.util.math.ChunkPos pos;
        final java.util.concurrent.atomic.AtomicInteger writes=new java.util.concurrent.atomic.AtomicInteger();
        final Set<String> types=java.util.concurrent.ConcurrentHashMap.newKeySet();
        ScopedFault(String label,net.minecraft.registry.RegistryKey<World> dimension,net.minecraft.util.math.ChunkPos pos) {
            this.label=label; this.dimension=dimension; this.pos=pos;
        }
        boolean matches(net.minecraft.world.storage.StorageKey key,net.minecraft.util.math.ChunkPos chunk) {
            return key.dimension().equals(dimension) && chunk.equals(pos) && (key.type().equals("chunk") || key.type().equals("entities"));
        }
        @Override public String toString() { return label+"@"+dimension.getValue()+"/"+pos+" writes="+writes.get()+" types="+types; }
    }
    /**
     * 單次 checkpoint 呼叫：故障目標、期間新增的故障類型、受控 write 次數與 production 原生失敗計數
     * （server 全域 onChunkSaveFailure 與來源世界 chunk worker）。被承認時在 RETURN 收尾，以例外結束時在下一次呼叫開始時收尾。
     */
    private static final class ScopedCall {
        final int tick; final ScopedFault target; final Set<String> typesBefore; final int writesBefore; final long serverBefore,workerBefore;
        /** 這次呼叫開始前（前一次失敗之後），receipt、leases 與 runtime space 是否原樣保留；第一次呼叫恆為 false。 */
        final boolean retainedBefore;
        boolean accepted; Set<String> typesAfter; int writesAfter=-1; long serverAfter=-1,workerAfter=-1;
        ScopedCall(int tick,ScopedFault target,Set<String> typesBefore,int writesBefore,long serverBefore,long workerBefore,boolean retainedBefore) {
            this.tick=tick; this.target=target; this.typesBefore=typesBefore; this.writesBefore=writesBefore; this.serverBefore=serverBefore; this.workerBefore=workerBefore;
            this.retainedBefore=retainedBefore;
        }
        boolean closed() { return typesAfter!=null; }
        void close(long server,long worker) { typesAfter=Set.copyOf(target.types); writesAfter=target.writes.get(); serverAfter=server; workerAfter=worker; }
        /** 尚未收尾時以目前值計算（只在最後一次呼叫發生）。 */
        Set<String> faultTypes() { var added=new HashSet<>(closed() ? typesAfter : Set.copyOf(target.types)); added.removeAll(typesBefore); return added; }
        long serverFailures() { return serverAfter<0 ? -1 : serverAfter-serverBefore; }
        long workerFailures() { return workerAfter<0 ? -1 : workerAfter-workerBefore; }
        @Override public String toString() {
            return "{tick="+tick+" target="+target.label+" accepted="+accepted+" faultTypes="+faultTypes()+" writes="+writesBefore+"->"+(writesAfter<0 ? target.writes.get() : writesAfter)
                    +" server+="+serverFailures()+" worker+="+workerFailures()+" retainedBefore="+retainedBefore+"}";
        }
    }

    /** 空間、slot、page、來源票與 runtime session 的可比較快照；只讀 testmod reflection。 */
    static List<?> runtimeOwnership(M2CorridorGameTests.NativeEntry fixture) {
        var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
        return List.of(Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"spaces")).keySet()),
                Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"protectedLeases")),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(M4CandidateTestAccess.get(fixture.pages,"allocator"),"leases")).keySet()),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(gateway,"sessions")).keySet()),
                Set.copyOf(((Map<?,?>)M4CandidateTestAccess.get(gateway,"tickets")).keySet()));
    }
    /** 正式 journal 檔 bytes；尚未建立時以空陣列代表 ABSENT（隔離 batch 的全新 save）。 */
    private static byte[] journalBytes(MinecraftServer server) {
        var path=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat");
        try { return java.nio.file.Files.exists(path) ? java.nio.file.Files.readAllBytes(path) : new byte[0]; }
        catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
    }

    private static void selectionFailure(TestContext context,String mode) {
        nativeCandidates(context,1,(fixture,tick) -> {
            var before=fixture.record(); var view=fixture.pages.currentMappings(before.sessionUuid()).instances().getFirst();
            var pos=cell(view,new DoorKey(before.sessionUuid(),1,DoorKey.DoorWallSide.NEGATIVE_LATERAL),1,1);
            var probe=new SelectionProbe(fixture,mode,pos); messages(fixture.players.getFirst()); selectionProbe=probe;
            net.minecraft.util.ActionResult outcome;
            try { outcome=use(fixture,pos,fixture.players.getFirst().player()); } finally { selectionProbe=null; }
            var received=messages(fixture.players.getFirst()); var journal=SessionRecoveryState.get(fixture.server);
            boolean guardReleased=((Integer)M4CandidateTestAccess.get(M4CandidateTestAccess.space(fixture.pages,before.sessionUuid()),"operations"))==0;
            var durable=fixture.record(); var current=journal.records().get(before.sessionUuid());
            // 新契約：checked 提交失敗由 ledger owner CAS 還原；current 不得殘留任何未 checked selection，session 立即標記 failure。
            boolean noUncheckedCurrent=durable.equals(current);
            boolean sessionFailed=mode.equals("before")
                    ? durable.state()==SessionState.RETURNING && durable.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable
                            && durable.candidateLedger().equals(before.candidateLedger())
                    : fixture.pages.measuredRecoveryRequested(before.sessionUuid());
            if(mode.equals("before")) {
                // 第二次互動不得看到或重選已回報失敗的選擇；不再由 testmod 自行 flush 承認任何 dirty。
                var currentBeforeRetry=journal.records().get(before.sessionUuid());
                var flushedBeforeRetry=journal.flushedRecords().get(before.sessionUuid());
                var retryOutcome=use(fixture,pos,fixture.players.getFirst().player());
                var retryMessages=messages(fixture.players.getFirst());
                context.assertEquals(net.minecraft.util.ActionResult.FAIL,retryOutcome,"失敗選擇後的第二次互動必須 FAIL");
                context.assertEquals(currentBeforeRetry,journal.records().get(before.sessionUuid()),"第二次互動不得改 current record");
                context.assertEquals(flushedBeforeRetry,journal.flushedRecords().get(before.sessionUuid()),"第二次互動不得改 flushed record");
                context.assertTrue(!retryMessages.contains(LOCKED_MESSAGE) && !retryMessages.contains("候選已鎖定。"),
                        "失敗選擇不得回成功或 ALREADY_SELECTED 訊息："+retryMessages);
                received.addAll(retryMessages);
            }
            selectionTeardown(fixture,tick+1,() -> {
                context.assertTrue(probe.before && (mode.equals("before") || probe.after),"fault必須命中正式checked選擇窗口");
                context.assertEquals(net.minecraft.util.ActionResult.FAIL,outcome,"flush/readback例外拒絕");
                context.assertTrue(!received.contains(LOCKED_MESSAGE) && guardReleased,"fault無成功actionbar且finally釋放guard");
                context.assertTrue(noUncheckedCurrent,"checked 提交失敗後 current 必須等於 flushed authority："+mode);
                context.assertTrue(sessionFailed,"checked 提交失敗必須以 flushed authority 標記 session failure："+mode+" durable="+durable.state());
                context.assertEquals(mode.equals("before") ? SessionState.RETURNING : SessionState.MEASURED,durable.state(),"未寫／已寫但回傳失敗窗口明確區別");
            });
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_unflushed",tickLimit=100000)
    public void native_selection_cannot_use_candidate_before_checked_batch_readback_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_cannot_use_candidate_before_checked_batch_readback_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_cannot_use_candidate_before_checked_batch_readback_windows");
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        var probe=new CandidatePublishProbe(fixture); publishProbe=probe;
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.activeReady() && fixture.pages.selectableDoors(fixture.record().sessionUuid()).size()==358,tick -> {
            publishProbe=null;
            fixture.trustedTeardown(tick+1,() -> {
                context.assertTrue(probe.observed && probe.rejected,"真候選batch未flush窗口：側門互動拒絕且current/durable零額外mutation"); M4PerTestUniverseProbe.complete(context);
            });
        });
    }

    /** 先完成選擇／freeze斷言，清理才使用明確testmod-only fixture重設；不宣稱Task8返還已實作。 */
    private static void selectionTeardown(M2CorridorGameTests.NativeEntry fixture,int tick,Runnable assertions) {
        RuntimeException failure=null;
        try { assertions.run(); } catch(RuntimeException caught) { failure=caught; }
        if(failure!=null) {
            // 斷言已失敗時直接回報原因，不讓後續收尾逾時掩蓋真正 RED 訊息。
            org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").error("M4_SELECTION_ASSERTION_FAILED {}",failure.getMessage());
            throw failure;
        }
        M4CandidateTestAccess.resetMeasuredForTeardown(fixture.server,fixture.pages,fixture.record().sessionUuid());
        var observed=failure;
        fixture.trustedTeardown(tick,() -> { if(observed!=null) throw observed; M4PerTestUniverseProbe.complete(fixture.context); });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_select_mapping_windows",tickLimit=100000)
    public void native_selection_rejects_pending_batch_retired_and_unready_mapping_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_selection_rejects_pending_batch_retired_and_unready_mapping_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_selection_rejects_pending_batch_retired_and_unready_mapping_windows");
        nativeCandidates(context,1,(fixture,tick) -> {
            var sid=fixture.record().sessionUuid(); var pages=fixture.pages; var old=pages.currentMappings(sid);
            var key=new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL); var oldCell=cell(old.instances().getFirst(),key,1,1);
            var player=fixture.players.getFirst().player(); var failures=new ArrayList<String>();
            var prepared=pages.prepareRemap(sid,old.epoch(),Set.of(1L));
            deny(fixture,oldCell,player,"pending current",failures);
            when(context,tick+1,() -> pages.ready(prepared),ready -> {
                var next=prepared.target().instances().getFirst(); var nextCell=cell(next,key,1,1);
                deny(fixture,nextCell,player,"prepared非current",failures);
                var batch=pages.beginRemap(prepared,pages.affectedEntityOwners(prepared));
                deny(fixture,oldCell,player,"remap batch",failures);
                for(var move : batch.moves()) {
                    var entity=fixture.target.getEntity(move.entityUuid()); var pose=move.after();
                    ((net.minecraft.server.network.ServerPlayerEntity)entity).teleport(fixture.target,pose.position().x,pose.position().y,pose.position().z,Set.of(),pose.yaw(),pose.pitch());
                    entity.setVelocity(pose.velocity());
                }
                var retired=pages.commitRemap(batch);
                deny(fixture,oldCell,player,"retired physical",failures); deny(fixture,nextCell,player,"retirement未完成",failures);
                pages.retire(retired);
                when(context,ready+1,() -> pages.selectableDoors(sid).contains(key),published -> {
                    var bounds=next.bounds(); var chunk=new net.minecraft.util.math.ChunkPos(bounds.getMinX()>>4,bounds.getMinZ()>>4);
                    M4CandidateTestAccess.tickets(pages,sid,false);
                    when(context,published+1,() -> !fixture.target.shouldTick(chunk),unready -> {
                        deny(fixture,nextCell,player,"真撤票unready",failures); M4CandidateTestAccess.tickets(pages,sid,true);
                        when(context,unready+1,() -> pages.selectableDoors(sid).contains(key),loaded ->
                                fixture.trustedTeardown(loaded+1,() -> {
                                    context.assertTrue(failures.isEmpty(),"pending/batch/retired/unready都攔截互動："+failures); M4PerTestUniverseProbe.complete(context);
                                }));
                    });
                });
            });
        });
    }

    private static void nativeCandidates(TestContext context,int people,java.util.function.BiConsumer<M2CorridorGameTests.NativeEntry,Integer> ready) {
        var fixture=new M2CorridorGameTests.NativeEntry(context,people,Direction.NORTH);
        context.waitAndRun(2,fixture::power);
        when(context,3,() -> fixture.activeReady() && fixture.pages.selectableDoors(fixture.record().sessionUuid()).size()==358,
                tick -> ready.accept(fixture,tick));
    }
    private static net.minecraft.util.ActionResult use(M2CorridorGameTests.NativeEntry fixture,BlockPos pos,net.minecraft.server.network.ServerPlayerEntity player) {
        return fixture.target.getBlockState(pos).onUse(fixture.target,player,new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(pos),Direction.UP,pos,false));
    }
    private static void deny(M2CorridorGameTests.NativeEntry fixture,BlockPos pos,net.minecraft.server.network.ServerPlayerEntity player,String label,List<String> failures) {
        if(use(fixture,pos,player)!=net.minecraft.util.ActionResult.FAIL || fixture.target.getBlockState(pos).get(dev.quantumchamber.chamber.QuantumBulkheadBlock.OPEN)) failures.add(label);
    }
    private static boolean rejects(Runnable operation) { try { operation.run(); return false; } catch(IllegalArgumentException | IllegalStateException expected) { return true; } }
    private static List<String> messages(ConnectedGameTestPlayer fixture) {
        var channel=(io.netty.channel.embedded.EmbeddedChannel)M4CandidateTestAccess.get(fixture,"channel");
        // ServerCommonNetworkHandler在server tick會disableFlush；先flush已實際送入channel的封包，才能同tick觀察。
        channel.runPendingTasks(); channel.flush(); channel.runPendingTasks();
        var messages=new ArrayList<String>(); Object packet;
        while((packet=channel.readOutbound())!=null) {
            if(packet instanceof net.minecraft.network.packet.s2c.play.GameMessageS2CPacket message && message.overlay()) messages.add(message.content().getString());
            io.netty.util.ReferenceCountUtil.release(packet);
        }
        return messages;
    }
    private static List<?> nativeSnapshot(M2CorridorGameTests.NativeEntry fixture) {
        var catalog=UniverseRegistryState.get(fixture.server);
        var space=M4CandidateTestAccess.space(fixture.pages,fixture.record().sessionUuid());
        return List.of(catalog.records(),catalog.flushedRecords(),M4CandidateTestAccess.runtime(fixture.server),
                java.util.stream.StreamSupport.stream(fixture.server.getWorlds().spliterator(),false).toList(),
                fixture.players.stream().map(connection -> List.of(connection.player().getServerWorld(),connection.player().getPos(),
                        connection.player().getVelocity(),connection.player().getYaw(),connection.player().getPitch())).toList(),
                fixture.controller().chamberState(),Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(space,"ticketRefs")),
                Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(space,"leases")),
                ChamberProtectionService.get().mayMutate(fixture.target,fixture.pages.currentMappings(fixture.record().sessionUuid()).instances().getFirst().localBlockOrigin()));
    }
    private static final class CandidatePublishProbe {
        final M2CorridorGameTests.NativeEntry fixture; boolean observed,rejected=true;
        CandidatePublishProbe(M2CorridorGameTests.NativeEntry fixture) { this.fixture=fixture; }
        void observe(SessionRecoveryState journal) {
            var durable=fixture.record();
            if(durable==null || durable.state()!=SessionState.SUPERPOSITION) return;
            var current=journal.records().get(durable.sessionUuid());
            var pending=current.candidateLedger().stream().filter(entry -> durable.candidateLedger().stream()
                    .noneMatch(known -> known.doorKey().equals(entry.doorKey()))).findFirst();
            if(pending.isEmpty()) return;
            observed=true; var snapshot=journal.records();
            var view=fixture.pages.currentMappings(durable.sessionUuid()).instances().getFirst();
            var result=use(fixture,cell(view,pending.get().doorKey(),1,1),fixture.players.getFirst().player());
            rejected &= result==net.minecraft.util.ActionResult.FAIL && snapshot.equals(journal.records()) && durable.equals(fixture.record());
        }
    }
    private static final class SelectionProbe {
        final M2CorridorGameTests.NativeEntry fixture; final String mode; final BlockPos second;
        boolean before,after,exclusive=true;
        SelectionProbe(M2CorridorGameTests.NativeEntry fixture,String mode,BlockPos second) { this.fixture=fixture; this.mode=mode; this.second=second; }
        void observe(SessionRecoveryState journal,boolean afterFlush) {
            var record=journal.records().get(fixture.record().sessionUuid());
            if(record.state()!=SessionState.MEASURED) return;
            if(afterFlush) after=true; else before=true;
            var sid=record.sessionUuid(); var mappings=fixture.pages.currentMappings(sid);
            exclusive &= ((Integer)M4CandidateTestAccess.get(M4CandidateTestAccess.space(fixture.pages,sid),"operations"))==1
                    && fixture.pages.selectableDoors(sid).isEmpty() && messages(fixture.players.getFirst()).isEmpty()
                    && rejects(() -> fixture.pages.prepareRemap(sid,mappings.epoch(),Set.of(1L)))
                    && use(fixture,second,fixture.players.getLast().player())==net.minecraft.util.ActionResult.FAIL;
            var before=journal.records();
            exclusive &= !dev.quantumchamber.chamber.ChamberSessions.gateway().returnToOrigin(fixture.context.getWorld(),fixture.controller())
                    && before.equals(journal.records());
            if(mode.equals(afterFlush ? "after" : "before")) throw new IllegalStateException("Task7受控選擇"+mode+" flush fault");
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_north", tickLimit=100000)
    public void north_complete_doors_recycle_split_merge_and_seam_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"north_complete_doors_recycle_split_merge_and_seam_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"north_complete_doors_recycle_split_merge_and_seam_windows");
        completeDoors(context,Direction.NORTH,true);
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_east", tickLimit=100000)
    public void east_complete_doors_require_checked_ledger_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"east_complete_doors_require_checked_ledger_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"east_complete_doors_require_checked_ledger_windows");
        completeDoors(context,Direction.EAST,false);
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_south", tickLimit=100000)
    public void south_complete_doors_require_checked_ledger_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"south_complete_doors_require_checked_ledger_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"south_complete_doors_require_checked_ledger_windows");
        completeDoors(context,Direction.SOUTH,false);
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_west", tickLimit=100000)
    public void west_complete_doors_require_checked_ledger_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"west_complete_doors_require_checked_ledger_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"west_complete_doors_require_checked_ledger_windows");
        completeDoors(context,Direction.WEST,false);
    }

    private static void completeDoors(TestContext context, Direction facing, boolean lifecycle) {
        var fixture=new M2CorridorGameTests.TrustedSpace(context,facing,1,SessionSemantics.LATERAL_BUFF_MAINTAINED,true);
        var pages=fixture.manager; var sid=fixture.session;
        var observation=new FlushObservation(pages); OBSERVATIONS.put(sid,observation);
        fixture.prepare();
        context.assertTrue(pages.selectableDoors(sid).isEmpty(),"pending／未載入／幾何未完成不得選門");
        when(context,1,() -> pages.ready(fixture.prepared),ready -> {
            context.assertTrue(pages.selectableDoors(sid).isEmpty(),"geometry ready 但 ARMING 未 commit 仍不可選");
            fixture.commit();
            var universe=universeSnapshot(fixture);
            context.assertTrue(pages.selectableDoors(sid).isEmpty(),"mapping commit 後 ledger flush 前不可選");
            when(context,ready+1,() -> pages.selectableDoors(sid).size()>256,tick -> {
                context.assertEquals(358,pages.selectableDoors(sid).size(),"初始 180 個 station、雙牆，扣除入口 station 的兩門");
                var view=pages.currentMappings(sid).instances().getFirst();
                var entryKey=new DoorKey(sid,0,DoorKey.DoorWallSide.POSITIVE_LATERAL);
                context.assertTrue(!pages.selectableDoors(sid).contains(entryKey) && pages.flushedCandidate(entryKey).isEmpty(),
                        "入口返還門不得產生candidate ledger或selectable owner");
                var oppositeKey=new DoorKey(sid,0,DoorKey.DoorWallSide.NEGATIVE_LATERAL);
                context.assertTrue(!pages.selectableDoors(sid).contains(oppositeKey),"入口對側目前不是完整普通bulkhead門");
                for(long station : new long[]{-1,1,11,12}) for(var side : DoorKey.DoorWallSide.values()) {
                    var key=new DoorKey(sid,station,side);
                    assertDoor(context,fixture,view,key);
                }
                context.assertTrue(pages.selectableDoor(context.getWorld(),cell(view,new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL),1,1)).isEmpty(),
                        "相同座標在錯誤 world 不可選");
                boolean immutable=false;
                try { pages.selectableDoors(sid).clear(); } catch(UnsupportedOperationException expected) { immutable=true; }
                context.assertTrue(immutable,"selectableDoors 必須 immutable");
                pages.tick(); pages.tick();
                context.assertTrue(observation.reentryProbed && observation.hidden && observation.counts.values().stream().allMatch(count -> count==1)
                        && observation.sizes.stream().allMatch(size -> size<=256) && observation.sizes.contains(256),
                        "同 session 單 tick 只提交一次且每批最多256；checked flush前不可見");
                var key=new DoorKey(sid,1,DoorKey.DoorWallSide.NEGATIVE_LATERAL);
                var candidate=pages.flushedCandidate(key); var missing=cell(view,key,5,5);
                ChamberProtectionService.get().authorizedMutation(fixture.target,missing,
                        () -> fixture.target.setBlockState(missing,net.minecraft.block.Blocks.AIR.getDefaultState(),3));
                context.assertTrue(pages.selectableDoor(fixture.target,cell(view,key,1,1)).isEmpty()
                        && !pages.selectableDoors(sid).contains(key),"缺一格時整扇25格不可選");
                context.assertEquals(candidate,pages.flushedCandidate(key),"實體門不完整不得刪除 durable candidate");
                ChamberProtectionService.get().authorizedMutation(fixture.target,missing,
                        () -> fixture.target.setBlockState(missing,ModBlocks.QUANTUM_BULKHEAD.getDefaultState(),3));
                var space=M4CandidateTestAccess.space(pages,sid);
                for(String field : List.of("operations","failed","releasing")) {
                    Object previous=M4CandidateTestAccess.get(space,field);
                    M4CandidateTestAccess.set(space,field,field.equals("operations") ? 1 : true);
                    try { context.assertTrue(pages.selectableDoors(sid).isEmpty(),field+" 必須阻止整個 space publish"); }
                    finally { M4CandidateTestAccess.set(space,field,previous); }
                    context.assertTrue(pages.selectableDoors(sid).contains(key),"恢復同一current owner後能從durable view查詢");
                }
                @SuppressWarnings("unchecked") var builds=(Map<Integer,Object>)M4CandidateTestAccess.get(space,"builds");
                builds.put(-1,null);
                try { context.assertTrue(pages.selectableDoors(sid).isEmpty(),"current geometry build未完成不得publish"); }
                finally { builds.remove(-1); }
                context.assertEquals(universe,universeSnapshot(fixture),"候選生成不增加Universe catalog/runtime/world或改玩家world");
                if(lifecycle) {
                    for(int offset=1;offset<=5;offset++) for(int y=1;y<=5;y++) {
                        var pos=cell(view,oppositeKey,y,offset);
                        ChamberProtectionService.get().authorizedMutation(fixture.target,pos,
                                () -> fixture.target.setBlockState(pos,ModBlocks.QUANTUM_BULKHEAD.getDefaultState(),3));
                    }
                    when(context,tick+1,() -> pages.selectableDoors(sid).contains(oppositeKey),oppositeReady -> {
                        assertDoor(context,fixture,view,oppositeKey);
                        context.assertTrue(pages.flushedCandidate(entryKey).isEmpty(),"同station對側變完整仍不暴露入口門");
                        unreadyThenRemap(context,fixture,oppositeReady+1,universe);
                    });
                }
                else finish(context,fixture,tick+1,universe);
            });
        });
    }

    private static void unreadyThenRemap(TestContext context,M2CorridorGameTests.TrustedSpace fixture,int tick,List<?> universe) {
        var view=fixture.manager.currentMappings(fixture.session).instances().getFirst();
        var bounds=view.bounds(); int x=bounds.getMinX()>>4,z=bounds.getMinZ()>>4;
        M4CandidateTestAccess.tickets(fixture.manager,fixture.session,false);
        when(context,tick,() -> fixture.target.getChunkManager().getWorldChunk(x,z)==null
                || !fixture.target.shouldTick(new net.minecraft.util.math.ChunkPos(x,z)),unready -> {
            context.assertTrue(fixture.manager.selectableDoors(fixture.session).isEmpty(),"真實撤票造成非FULL或非ticking時不可選");
            M4CandidateTestAccess.tickets(fixture.manager,fixture.session,true);
            when(context,unready+1,() -> !fixture.manager.selectableDoors(fixture.session).isEmpty(),loaded ->
                    remap(context,fixture,loaded+1,List.of(Set.of(-1L,16L),Set.of(-1L,7L,16L),Set.of(-1L)),0,universe));
        });
    }

    private static void remap(TestContext context,M2CorridorGameTests.TrustedSpace fixture,int tick,List<Set<Long>> plans,int index,List<?> universe) {
        if(index==plans.size()) { finish(context,fixture,tick,universe); return; }
        var pages=fixture.manager; var sid=fixture.session;
        var key=new DoorKey(sid,-1,DoorKey.DoorWallSide.NEGATIVE_LATERAL);
        var candidate=pages.flushedCandidate(key); var previous=pages.currentMappings(sid);
        var priorLedger=fixture.state.flushedRecords().get(sid).candidateLedger();
        var old=previous.instances().stream().filter(view -> view.aliasStartBlock()<=-7 && view.aliasEndBlock()>-3).findFirst().orElseThrow();
        var oldCell=cell(old,key,1,1);
        var prepared=pages.prepareRemap(sid,previous.epoch(),plans.get(index));
        context.assertTrue(pages.selectableDoor(fixture.target,oldCell).isEmpty() && pages.selectableDoors(sid).isEmpty(),
                "prepareRemap起始立即撤下整個space舊projection");
        context.assertEquals(candidate,pages.flushedCandidate(key),"pending remap不變更已決定candidate");
        when(context,tick,() -> pages.ready(prepared),ready -> {
            var batch=pages.beginRemap(prepared,pages.affectedEntityOwners(prepared));
            for(var move : batch.moves()) {
                var player=fixture.target.getServer().getPlayerManager().getPlayer(move.entityUuid()); var pose=move.after();
                player.teleport(fixture.target,pose.position().x,pose.position().y,pose.position().z,Set.of(),pose.yaw(),pose.pitch());
                player.setVelocity(pose.velocity());
            }
            var retired=pages.commitRemap(batch);
            context.assertTrue(pages.selectableDoors(sid).isEmpty(),"commit但舊owner尚未退休時仍不得publish");
            pages.retire(retired);
            when(context,ready+1,() -> pages.selectableDoors(sid).contains(key),published -> {
                context.assertEquals(candidate,pages.flushedCandidate(key),"split/merge/recycle後candidate完整相同");
                context.assertTrue(fixture.state.flushedRecords().get(sid).candidateLedger().containsAll(priorLedger),
                        "page回收必須保留全部既有候選，包括已無physical門的station");
                context.assertTrue(pages.selectableDoor(fixture.target,oldCell).isEmpty(),"舊physical owner不再可選");
                var current=pages.currentMappings(sid);
                context.assertEquals(index==0 ? 2 : 1,current.instances().size(),"真實split/merge映射數量");
                context.assertEquals(previous.epoch()+1,current.epoch(),"mapping epoch必須真實提交下一版");
                var owner=current.instances().stream().filter(view -> view.aliasStartBlock()<=-7 && view.aliasEndBlock()>-3).findFirst().orElseThrow();
                context.assertTrue(owner.ref().instanceEpoch()>old.ref().instanceEpoch() && !owner.ref().equals(old.ref()),
                        "驗證DoorKey必須真正換過physical owner與instance epoch");
                assertDoor(context,fixture,owner,key);
                context.assertEquals(universe,universeSnapshot(fixture),"page生命週期仍無Universe副作用");
                remap(context,fixture,published+1,plans,index+1,universe);
            });
        });
    }

    private static void assertDoor(TestContext context,M2CorridorGameTests.TrustedSpace fixture,CorridorPageManager.MappingView view,DoorKey key) {
        context.assertTrue(fixture.manager.flushedCandidate(key).isPresent(),"完整門具durable candidate");
        for(int offset=1;offset<=5;offset++) for(int y=1;y<=5;y++) {
            var pos=cell(view,key,y,offset);
            context.assertTrue(fixture.target.getBlockState(pos).isOf(ModBlocks.QUANTUM_BULKHEAD),"25格均為真bulkhead");
            context.assertEquals(Optional.of(key),fixture.manager.selectableDoor(fixture.target,pos),"同門25格normalize成同一DoorKey");
        }
    }
    private static BlockPos cell(CorridorPageManager.MappingView view,DoorKey key,int y,int offset) {
        var frame=new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(),3).up(6),view.outwardFacing());
        return CorridorGeometry.block(frame,key.wallSide()==DoorKey.DoorWallSide.NEGATIVE_LATERAL ? 0 : 6,y,
                Math.toIntExact(key.logicalStationIndex()*8+offset-view.logicalAnchorBlock()));
    }
    private static List<?> universeSnapshot(M2CorridorGameTests.TrustedSpace fixture) {
        var server=fixture.target.getServer(); var catalog=UniverseRegistryState.get(server);
        return List.of(catalog.records(),catalog.flushedRecords(),M4CandidateTestAccess.runtime(server),
                java.util.stream.StreamSupport.stream(server.getWorlds().spliterator(),false).toList(),fixture.players.getFirst().player().getServerWorld());
    }
    private static void finish(TestContext context,M2CorridorGameTests.TrustedSpace fixture,int tick,List<?> universe) {
        var observation=OBSERVATIONS.get(fixture.session);
        context.assertTrue(observation.reentryProbed && observation.hidden && observation.counts.values().stream().allMatch(count -> count==1)
                && observation.sizes.stream().allMatch(size -> size<=256),"包含page生命週期在內，每tick都只允許一次256筆checked batch");
        context.assertEquals(universe,universeSnapshot(fixture),"候選階段Universe snapshot保持不變");
        var ledger=fixture.state.flushedRecords().get(fixture.session).candidateLedger();
        fixture.returnTrusted();
        context.assertEquals(ledger,fixture.state.flushedRecords().get(fixture.session).candidateLedger(),"RETURNING仍保留完整ledger");
        context.assertTrue(fixture.manager.selectableDoors(fixture.session).isEmpty(),"RETURNING/RELEASING立即不可選");
        when(context,tick,() -> fixture.manager.releaseComplete(fixture.session),done -> {
            fixture.assertComplete(); OBSERVATIONS.remove(fixture.session); M4PerTestUniverseProbe.complete(context);
        });
    }

    public static void beforeCandidateFlush(SessionRecoveryState state,MinecraftServer server) {
        if(initialFlushFaultProbe!=null) initialFlushFaultProbe.observe(state);
        if(batchFaultProbe!=null) batchFaultProbe.observe(state);
        if(publishProbe!=null) publishProbe.observe(state);
        if(selectionProbe!=null) selectionProbe.observe(state,false);
        for(var entry : OBSERVATIONS.entrySet()) {
            var current=state.records().get(entry.getKey()); var durable=state.flushedRecords().get(entry.getKey());
            if(current==null || durable==null || current.state()!=SessionState.SUPERPOSITION) continue;
            var known=new HashSet<DoorKey>(); durable.candidateLedger().forEach(candidate -> known.add(candidate.doorKey()));
            var missing=current.candidateLedger().stream().filter(candidate -> !known.contains(candidate.doorKey())).toList();
            if(missing.isEmpty()) continue;
            var observation=entry.getValue(); observation.counts.merge(server.getTicks(),1,Integer::sum); observation.sizes.add(missing.size());
            observation.hidden &= observation.pages.selectableDoors(entry.getKey()).isEmpty()
                    && missing.stream().allMatch(candidate -> observation.pages.flushedCandidate(candidate.doorKey()).isEmpty());
        }
    }
    public static void afterCandidateFlush(SessionRecoveryState state,MinecraftServer server) {
        if(selectionProbe!=null) selectionProbe.observe(state,true);
        for(var entry : OBSERVATIONS.entrySet()) {
            var record=state.flushedRecords().get(entry.getKey()); var observation=entry.getValue();
            if(observation.reentryProbed || record==null || record.state()!=SessionState.SUPERPOSITION || record.candidateLedger().size()!=256) continue;
            // 第一批已 readback、第二批仍缺失時，故意在同一 server tick 重入兩次。
            observation.reentryProbed=true;
            observation.pages.tick(); observation.pages.tick();
            observation.hidden &= state.flushedRecords().get(entry.getKey()).candidateLedger().size()==256;
        }
    }
    /**
     * 武裝期間讓該 Chamber 初始 ARMING（尚無 flushed record）的 checked flush 失敗。
     * FAIL：每次都在寫入前失敗，模擬持續 IO 故障；每次重試都必須零殘留。
     * WRITE_THEN_FAIL（H3 q5）：每次先把含該 ARMING 的整份 journal 原子寫入正式檔，再以 readback 失敗結束（W-a）。
     * DIVERGE_ONCE（H3 q4）：只失敗一次；失敗前在 current 疊上同 authority 的 RETURNING，讓 abandonInitial 的 CAS 必定不符（雙重故障的殘留）。
     */
    private static final class InitialFlushFaultProbe {
        enum Mode { FAIL, WRITE_THEN_FAIL, DIVERGE_ONCE }
        static final String READBACK_FAULT="M5 H3 GameTest 受控 readback 失敗（ARMING 已寫入正式檔）";
        final M2CorridorGameTests.NativeEntry fixture; final Mode mode; int faults;
        /** WRITE_THEN_FAIL：每次故障前，正式檔 strict 讀回都含該 ARMING。 */
        boolean diskHadArming=true;
        /** DIVERGE_ONCE：疊在未 checked ARMING 之上的 RETURNING。 */
        SessionRecoveryRecord residue;
        InitialFlushFaultProbe(M2CorridorGameTests.NativeEntry fixture) { this(fixture,Mode.FAIL); }
        InitialFlushFaultProbe(M2CorridorGameTests.NativeEntry fixture,Mode mode) { this.fixture=fixture; this.mode=mode; }
        void observe(SessionRecoveryState journal) {
            if(fixture.controller()==null || fixture.controller().chamberUuid()==null || mode==Mode.DIVERGE_ONCE && residue!=null) return;
            var chamber=fixture.controller().chamberUuid();
            var initial=journal.records().values().stream().filter(record -> record.chamberUuid().equals(chamber)
                    && !journal.flushedRecords().containsKey(record.sessionUuid())).findFirst().orElse(null);
            if(initial==null) return;
            faults++;
            switch(mode) {
                case FAIL -> throw new IllegalStateException("M4 GameTest 受控 initial ARMING flush fault");
                case WRITE_THEN_FAIL -> {
                    var path=fixture.server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID+".dat");
                    var wrapped=new net.minecraft.nbt.NbtCompound(); wrapped.put("data",journal.writeNbt(new net.minecraft.nbt.NbtCompound()));
                    net.minecraft.nbt.NbtHelper.putDataVersion(wrapped);
                    try { SessionJournalStore.write(path,wrapped); }
                    catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
                    diskHadArming &= M4CandidateTestAccess.diskRecords(fixture.server).containsKey(initial.sessionUuid());
                    throw new java.io.UncheckedIOException("journal checked 落盤失敗，保留 dirty 與上一個 durable snapshot",new java.io.IOException(READBACK_FAULT));
                }
                case DIVERGE_ONCE -> {
                    residue=initial.withProgress(initial.participants(),initial.spaceLeases(),SessionState.RETURNING,initial.restoreEntryEffectOnReturn());
                    journal.put(residue);
                    throw new IllegalStateException("M5 H3 GameTest 受控 initial ARMING flush fault（current 已疊上 RETURNING）");
                }
            }
        }
    }
    /** 只在真實 candidate batch 的 checked flush 入口注入一次失敗；不修補 journal。 */
    private static final class BatchFaultProbe {
        final M2CorridorGameTests.NativeEntry fixture; boolean fired; SessionRecoveryRecord faulted; int pending;
        BatchFaultProbe(M2CorridorGameTests.NativeEntry fixture) { this.fixture=fixture; }
        void observe(SessionRecoveryState journal) {
            if(fired || fixture.controller()==null || fixture.controller().chamberUuid()==null) return;
            var durable=fixture.record();
            if(durable==null || durable.state()!=SessionState.SUPERPOSITION) return;
            var current=journal.records().get(durable.sessionUuid());
            if(current==null || current.candidateLedger().size()<=durable.candidateLedger().size()) return;
            fired=true; faulted=durable; pending=current.candidateLedger().size()-durable.candidateLedger().size();
            throw new IllegalStateException("M4 GameTest 受控 candidate batch flush fault");
        }
    }
    private static final class FlushObservation {
        final CorridorPageManager pages; final Map<Integer,Integer> counts=new HashMap<>(); final List<Integer> sizes=new ArrayList<>();
        boolean hidden=true,reentryProbed;
        FlushObservation(CorridorPageManager pages) { this.pages=pages; }
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_authority_rejection",tickLimit=100000)
    public void unhealthy_authorities_reject_before_reservation_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"unhealthy_authorities_reject_before_reservation_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"unhealthy_authorities_reject_before_reservation_windows");
        authorityRejections(context,false);
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_legacy_runtime",tickLimit=100000)
    public void isolated_legacy_schema2_then_empty_schema3_authority_gates_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"isolated_legacy_schema2_then_empty_schema3_authority_gates_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"isolated_legacy_schema2_then_empty_schema3_authority_gates_windows");
        authorityRejections(context,true);
    }

    private static void authorityRejections(TestContext context,boolean legacyOnly) {
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        context.waitAndRun(3,() -> {
            var server=fixture.server; var root=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            var journal=SessionRecoveryState.get(server);
            // legacy 是單一 test 的獨立 fresh save，維持全域為空；共用 default run 的 batch 順序不固定，只看本 test 自己的 authority。
            if(legacyOnly) assertEmptyAuthorities(context,fixture.pages,journal);
            else {
                // 新艙尚未供電註冊、沒有 chamber UUID，不可能已有自己的 journal、space、runtime session 或來源票。
                var controller=fixture.controller();
                context.assertTrue(controller!=null && controller.chamberUuid()==null,
                        "default run 開始時 fixture 必須是尚未註冊的新艙：uuid="+(controller==null ? "NO_CONTROLLER" : controller.chamberUuid()));
            }
            // 本 test 自建的 session／chamber；fixture chamber 的 UUID 在第一次供電才指派，由檢查時即時讀取。
            var ownSessions=new HashSet<UUID>(); var ownChambers=new HashSet<UUID>();
            try {
                var discovery=dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
                var entropy=dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root,!discovery.records().isEmpty());
                var entropyPath=root.resolve("data/quantumchamber_candidate_entropy.dat");
                var discoveryPath=root.resolve("data/quantumchamber_universe_discovery.dat");
                var modes=legacyOnly ? List.of("legacy_current","legacy_durable")
                        : List.of("corrupt_entropy","corrupt_discovery","discovery_missing_entropy","candidate_missing_discovery","candidate_missing_entropy");
                for(String mode : modes) {
                    var sid=UUID.randomUUID(); var chamber=UUID.randomUUID();
                    ownSessions.add(sid); ownChambers.add(chamber);
                    var origin=new dev.quantumchamber.chamber.ChamberOriginAuthority(chamber,World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                            new BlockPos(100,80,100),Direction.NORTH,dev.quantumchamber.chamber.ChamberInstanceKind.ORIGIN);
                    var people=List.of(new SessionRecoveryRecord.Participant(UUID.randomUUID(),Vec3d.ZERO,Vec3d.ZERO,0,0,new net.minecraft.nbt.NbtCompound(),false));
                    var leases=List.of(new SessionRecoveryRecord.SpaceLease(10000,new net.minecraft.util.math.BlockBox(100,80,100,106,86,106)));
                    var policy=new dev.quantumchamber.candidate.CandidatePolicySnapshot(net.minecraft.util.Identifier.of("quantumchamber:m4_v1"),
                            1,1,5,20,75,discovery.watermark(),16384,entropy.entropyFingerprint(),new SourceFamilyRef.Vanilla(World.OVERWORLD,DimensionRole.OVERWORLD));
                    var fake=SessionRecoveryRecord.candidateAware(sid,chamber,origin,people,leases,SessionState.ARMING,false,
                            SessionSemantics.LATERAL_BUFF_MAINTAINED,policy);
                    if(mode.startsWith("legacy")) fake=new SessionRecoveryRecord(sid,chamber,origin,people,leases,SessionState.ARMING,false,
                            SessionSemantics.LATERAL_BUFF_MAINTAINED);
                    if(mode.startsWith("candidate") || mode.startsWith("legacy")) {
                        journal.put(fake); if(!mode.equals("legacy_current")) journal.flush(server);
                    }
                    var current=journal.records(); var durable=journal.flushedRecords();
                    var spaces=Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"spaces"));
                    var target=mode.contains("discovery") && !mode.equals("discovery_missing_entropy") ? discoveryPath : entropyPath;
                    var backup=java.nio.file.Files.createTempFile(root.resolve("data"),"m4-authority-",".backup");
                    java.nio.file.Files.copy(target,backup,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    try {
                        if(mode.equals("discovery_missing_entropy")) {
                            var nbt=discovery.toNbt(); var row=new net.minecraft.nbt.NbtCompound(); row.putUuid("UniverseId",UUID.randomUUID());
                            row.putLong("DiscoveryOrdinal",0); row.putLong("DiscoveredAtGameTime",0); row.putBoolean("GameplayEligible",true);
                            var records=new net.minecraft.nbt.NbtList(); records.add(row); nbt.put("Records",records);
                            dev.quantumchamber.candidate.UniverseDiscoveryState.fromNbt(nbt).save(root);
                        }
                        if(mode.contains("missing")) java.nio.file.Files.delete(target);
                        else if(mode.startsWith("corrupt")) {
                            var invalid=new net.minecraft.nbt.NbtCompound(); invalid.putInt("SchemaVersion",99);
                            net.minecraft.nbt.NbtIo.writeCompressed(invalid,target);
                        }
                        fixture.power();
                        fixture.assertNoSession();
                        context.assertEquals(current,journal.records(),mode+" start不得改current journal");
                        context.assertEquals(durable,journal.flushedRecords(),mode+" start不得改durable journal");
                        context.assertEquals(spaces,M4CandidateTestAccess.get(fixture.pages,"spaces"),mode+" start不得reserve space");
                        if(mode.contains("missing")) context.assertTrue(!java.nio.file.Files.exists(target),mode+" 不得自動重建遺失權威");
                        if(mode.startsWith("candidate") || mode.startsWith("legacy")) {
                            // 範圍檢查不得因限縮而漏掉本 test 自己的殘留；sid 與 chamber 兩條過濾路徑也各自單獨驗證。
                            boolean flushed=!mode.equals("legacy_current");
                            java.util.function.Predicate<List<String>> caught=found -> found.contains("current:"+sid) && (!flushed || found.contains("durable:"+sid));
                            var residue=ownAuthorityResidue(fixture,journal,ownSessions,ownChambers);
                            context.assertTrue(caught.test(residue),mode+" 範圍檢查必須抓到本 test 自己的 journal 殘留："+residue);
                            var bySession=ownAuthorityResidue(fixture,journal,Set.of(sid),Set.of());
                            context.assertTrue(caught.test(bySession),mode+" 只靠 session 過濾也必須抓到本 test 自己的殘留："+bySession);
                            var byChamber=ownAuthorityResidue(fixture,journal,Set.of(),Set.of(chamber));
                            context.assertTrue(caught.test(byChamber),mode+" 只靠 chamber 過濾也必須抓到本 test 自己的殘留："+byChamber);
                        }
                    } finally {
                        if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                        java.nio.file.Files.move(backup,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if(mode.equals("discovery_missing_entropy")) discovery.save(root);
                        journal.remove(sid); journal.flush(server);
                    }
                }
                if(legacyOnly) {
                    assertEmptyAuthorities(context,fixture.pages,journal);
                    context.runAtTick(4,() -> legacySchema2Runtime(context));
                } else {
                    assertOwnAuthoritiesEmpty(context,fixture,journal,ownSessions,ownChambers,"完成時");
                    // 另一座 chamber 的可移除 ARMING 殘留，代表較早 batch 留下的他艙 authority，不得讓範圍檢查失敗。
                    var foreign=M4CandidateTestAccess.syntheticSession(server,UUID.randomUUID(),10007);
                    var cleanup=new M2CorridorGameTests.TestHooks();
                    cleanup.add(() -> { journal.remove(foreign.sessionUuid()); journal.flush(server); });
                    try {
                        journal.put(foreign); journal.flush(server);
                        context.assertTrue(journal.flushedRecords().containsKey(foreign.sessionUuid()),"他艙殘留必須實際存在於 durable journal");
                        assertOwnAuthoritiesEmpty(context,fixture,journal,ownSessions,ownChambers,"他艙注入後");
                    } catch(RuntimeException | Error failure) { cleanup.releaseAfter(failure); throw failure; }
                    cleanup.release();
                    context.assertTrue(!journal.records().containsKey(foreign.sessionUuid()) && !journal.flushedRecords().containsKey(foreign.sessionUuid()),
                            "他艙 synthetic session 收尾必須移除");
                    M4PerTestUniverseProbe.complete(context);
                }
            } catch(java.io.IOException failure) { throw new IllegalStateException(failure); }
            finally { fixture.close(); }
        });
    }

    private static void emptySchemaThreeMissing(TestContext context,M2CorridorGameTests.NativeEntry fixture,
            boolean missingDiscovery,List<String> findings,Runnable next) {
        context.waitAndRun(3,() -> {
            try {
                var root=fixture.server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
                dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
                dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root,false);
                var state=M4CandidateTestAccess.reloadEmptySchemaThree(fixture.server,fixture.pages);
                var journalPath=root.resolve("data/quantumchamber_sessions.dat");
                var journalBytes=java.nio.file.Files.readAllBytes(journalPath);
                var records=state.records(); var durable=state.flushedRecords();
                var spaces=Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"spaces"));
                var manager=dev.quantumchamber.chamber.ChamberSessions.gateway();
                var sessions=Map.copyOf((Map<?,?>)M4CandidateTestAccess.get(manager,"sessions"));
                var target=root.resolve(missingDiscovery ? "data/quantumchamber_universe_discovery.dat" : "data/quantumchamber_candidate_entropy.dat");
                var backup=java.nio.file.Files.createTempFile(root.resolve("data"),"m4-empty-schema3-",".backup");
                java.nio.file.Files.move(target,backup,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                dev.quantumchamber.chamber.ChamberSessionGateway.StartResult startResult;
                List<Boolean> unchanged;
                try {
                    // 只隔離供電事件的自動入場；受測的顯式 start 使用完整正式 gateway。
                    M1FoundationSessionFixture.set(context.getWorld(),fixture.frame.controllerPos(),true);
                    try {
                        if(!context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                        new dev.quantumchamber.chamber.ChamberPowerCoordinator().refresh(context.getWorld(),fixture.frame.controllerPos());
                    }
                    finally { M1FoundationSessionFixture.set(context.getWorld(),fixture.frame.controllerPos(),false); }
                    var preview=new dev.quantumchamber.chamber.ChamberActivationService().evaluateReadiness(context.getWorld(),fixture.controller());
                    context.assertTrue(fixture.controller().chamberUuid()!=null && !fixture.controller().activationBlocked()
                            && context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos()) && preview.accepted(),
                            "受測start前置：uuid="+fixture.controller().chamberUuid()+" blocked="+fixture.controller().activationBlocked()
                                    +" power="+context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())+" "+preview);
                    startResult=manager.start(context.getWorld(),fixture.controller(),fixture.players.stream().map(player -> player.player().getUuid()).toList());
                    unchanged=List.of(!java.nio.file.Files.exists(target),records.equals(state.records()),durable.equals(state.flushedRecords()),
                            spaces.equals(M4CandidateTestAccess.get(fixture.pages,"spaces")),sessions.equals(M4CandidateTestAccess.get(manager,"sessions")),
                            java.util.Arrays.equals(journalBytes,java.nio.file.Files.readAllBytes(journalPath)));
                } finally {
                    java.nio.file.Files.move(backup,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                var witness=unchanged;
                Runnable verify=() -> {
                    var scenario=missingDiscovery ? "discovery" : "entropy";
                    org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod").info(
                            "M4_EMPTY_SCHEMA3_AUTHORITY kind={} start={} fileMissing={} currentSame={} flushedSame={} spaceSame={} sessionSame={} bytesSame={}",
                            scenario,startResult,witness.get(0),witness.get(1),witness.get(2),witness.get(3),witness.get(4),witness.get(5));
                    if(startResult!=dev.quantumchamber.chamber.ChamberSessionGateway.StartResult.REJECTED || witness.contains(false))
                        findings.add(scenario+" start="+startResult+" unchanged="+witness);
                    context.waitAndRun(1,next);
                };
                if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                when(context,Math.toIntExact(context.getTick()+1),() -> dev.quantumchamber.chamber.ChamberSessions.gateway()
                        .presence(fixture.server,fixture.controller().chamberUuid())==dev.quantumchamber.chamber.ChamberSessionGateway.Presence.NONE,
                        done -> { assertEmptyAuthorities(context,fixture.pages,state); verify.run(); });
            } catch(java.io.IOException failure) { fixture.close(); throw new IllegalStateException(failure); }
        });
    }

    /** 只用於獨立 legacy runner（單一 test 的 fresh save），全域為空是該情境的前提；共用 default run 改用 assertOwnAuthoritiesEmpty。 */
    private static void assertEmptyAuthorities(TestContext context,CorridorPageManager pages,SessionRecoveryState journal) {
        if(!Boolean.getBoolean("quantumchamber.gametest.legacyOnly")) throw new IllegalStateException("全域為空檢查只允許獨立 legacy runner");
        context.assertTrue(journal.records().isEmpty() && journal.flushedRecords().isEmpty()
                && ((Map<?,?>)M4CandidateTestAccess.get(pages,"spaces")).isEmpty(),
                "legacy隔離情境開始／完成都必須沒有current、durable journal或space");
    }
    private static void assertOwnAuthoritiesEmpty(TestContext context,M2CorridorGameTests.NativeEntry fixture,SessionRecoveryState journal,
            Set<UUID> sessions,Set<UUID> chambers,String phase) {
        var residue=ownAuthorityResidue(fixture,journal,sessions,chambers);
        context.assertTrue(residue.isEmpty(),phase+"：本 test 的 chamber／session 不得留下 current、durable journal、space、runtime session 或來源票："+residue);
    }
    /**
     * 本 test 自己的 session／chamber（含 fixture chamber）在 journal、page space、runtime session 與來源票上的殘留；他艙 authority 不列入。
     * 本 test 的 start 全部被拒，所以 space:／runtime:／ticket: 三類，以及即時讀取 fixture chamber UUID 的路徑，在這裡只是防護、沒有正向對照；
     * 有正向對照的只有 journal 的 current:／durable:（迴圈內 sid 與 chamber 兩條過濾路徑各自單獨驗證）。
     */
    private static List<String> ownAuthorityResidue(M2CorridorGameTests.NativeEntry fixture,SessionRecoveryState journal,
            Set<UUID> sessions,Set<UUID> chambers) {
        var owned=new HashSet<>(chambers);
        var controller=fixture.controller();
        if(controller!=null && controller.chamberUuid()!=null) owned.add(controller.chamberUuid());
        var residue=new ArrayList<String>();
        journal.records().values().stream().filter(record -> sessions.contains(record.sessionUuid()) || owned.contains(record.chamberUuid()))
                .forEach(record -> residue.add("current:"+record.sessionUuid()));
        journal.flushedRecords().values().stream().filter(record -> sessions.contains(record.sessionUuid()) || owned.contains(record.chamberUuid()))
                .forEach(record -> residue.add("durable:"+record.sessionUuid()));
        ((Map<?,?>)M4CandidateTestAccess.get(fixture.pages,"spaces")).forEach((id,space) -> {
            var origin=(dev.quantumchamber.chamber.ChamberOriginAuthority)M4CandidateTestAccess.get(space,"origin");
            if(sessions.contains(id) || owned.contains(origin.chamberUuid())) residue.add("space:"+id);
        });
        var gateway=dev.quantumchamber.chamber.ChamberSessions.gateway();
        ((Map<?,?>)M4CandidateTestAccess.get(gateway,"sessions")).keySet().stream().filter(owned::contains)
                .forEach(chamber -> residue.add("runtime:"+chamber));
        ((Map<?,?>)M4CandidateTestAccess.get(gateway,"tickets")).forEach((id,ticket) -> {
            var record=(SessionRecoveryRecord)M4CandidateTestAccess.get(ticket,"record");
            if(sessions.contains(id) || owned.contains(record.chamberUuid())) residue.add("ticket:"+id);
        });
        return residue;
    }
    private static void legacySchema2Runtime(TestContext context) {
        var server=context.getWorld().getServer(); var pages=CorridorPageManager.forServer(server); var journal=SessionRecoveryState.get(server);
        assertEmptyAuthorities(context,pages,journal);
        var legacy=new M2CorridorGameTests.TrustedSpace(context,Direction.NORTH,1,SessionSemantics.LEGACY_FORWARD_CONSUMED,false);
        legacy.prepare();
        context.assertEquals(2,journal.writeNbt(new net.minecraft.nbt.NbtCompound()).getInt("SchemaVersion"),"明確保留原生schema2 runtime覆蓋");
        context.assertTrue(legacy.initial.candidateContext().isEmpty(),"legacy fixture不能偷偷補candidate context");
        when(context,5,() -> pages.ready(legacy.prepared),ready -> {
            legacy.commit();
            context.assertTrue(pages.selectableDoors(legacy.session).isEmpty(),"legacy runtime不發布M4候選");
            legacy.returnTrusted();
            when(context,ready+1,() -> pages.releaseComplete(legacy.session),released -> {
                legacy.assertComplete(); assertEmptyAuthorities(context,pages,journal);
                var findings=new ArrayList<String>();
                context.waitAndRun(1,() -> {
                    var source=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH,true,new BlockPos(32,0,0));
                    emptySchemaThreeMissing(context,source,true,findings,
                            () -> emptySchemaThreeMissing(context,source,false,findings,() -> {
                                try {
                                    context.assertTrue(findings.isEmpty(),"空schema3兩種缺權威都必須REJECTED且零mutation："+findings);
                                    M4PerTestUniverseProbe.complete(context);
                                } finally { source.close(); }
                            }));
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_candidate_context", tickLimit=100000)
    public void native_start_freezes_candidate_context_before_geometry_windows(TestContext context) {
        if(!Platform.isWindows()) { platformSkip(context,"native_start_freezes_candidate_context_before_geometry_windows"); return; }
        M4PerTestUniverseProbe.begin(context,"native_start_freezes_candidate_context_before_geometry_windows");
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1, Direction.NORTH);
        context.waitAndRun(2, fixture::power);
        when(context, 3, () -> fixture.record() != null, tick -> {
            var initial = fixture.record();
            context.assertEquals(SessionState.ARMING, initial.state(), "入場先承認 ARMING");
            fixture.trustedTeardown(tick + 1, () -> {
                context.assertTrue(initial.candidateContext().isPresent(), "原生新 session 必須在 ARMING 凍結 candidate context");
                var policy = initial.candidateContext().orElseThrow();
                context.assertEquals(new SourceFamilyRef.Vanilla(World.OVERWORLD, DimensionRole.OVERWORLD), policy.sourceFamilyRef(), "凍結真來源 family");
                context.assertTrue(initial.candidateLedger().isEmpty(), "ARMING 不能提早生成候選");
                context.assertTrue(initial.candidateSelection().orElseThrow() instanceof CandidateSelection.Selectable, "新 session 從 SELECTABLE 開始");
                M4PerTestUniverseProbe.complete(context);
            });
        });
    }

    /** 有界等待（wall-clock，同 when）：逾時直接附診斷訊息失敗，不讓通用逾時訊息掩蓋 RED 原因。 */
    private static void whenWithin(TestContext context,int tick,int seconds,BooleanSupplier condition,IntConsumer ready,
            java.util.function.Supplier<String> diagnostics) {
        whenWithinUntil(context,tick,condition,ready,diagnostics,System.nanoTime()+seconds*1_000_000_000L,null);
    }
    /** 同上；逾時、斷言失敗或例外時先釋放本測試安裝的 hook，避免殘留到同一 server 的後續 batch。 */
    private static void whenWithin(TestContext context,int tick,int seconds,BooleanSupplier condition,IntConsumer ready,
            java.util.function.Supplier<String> diagnostics,M2CorridorGameTests.TestHooks hooks) {
        whenWithinUntil(context,tick,condition,ready,diagnostics,System.nanoTime()+seconds*1_000_000_000L,hooks);
    }
    private static void whenWithinUntil(TestContext context,int tick,BooleanSupplier condition,IntConsumer ready,
            java.util.function.Supplier<String> diagnostics,long deadline,M2CorridorGameTests.TestHooks hooks) {
        context.runAtTick(tick,() -> {
            try {
                if(condition.getAsBoolean()) ready.accept(tick);
                else {
                    context.assertTrue(System.nanoTime()<deadline,"有界等待逾時："+diagnostics.get());
                    whenWithinUntil(context,tick+1,condition,ready,diagnostics,deadline,hooks);
                }
            } catch(RuntimeException | Error failure) {
                if(hooks!=null) hooks.releaseAfter(failure);
                throw failure;
            }
        });
    }
    /**
     * 以 server tick 計的有界等待：GameTest server 不以 20 TPS 節流，wall-clock 期限可能晚於 tickLimit 才到，診斷訊息會被通用逾時蓋掉。
     * 逾時、斷言失敗或例外時先釋放 hooks（可為 null）。
     */
    private static void whenWithinTicks(TestContext context,int tick,int ticks,BooleanSupplier condition,IntConsumer ready,
            java.util.function.Supplier<String> diagnostics,M2CorridorGameTests.TestHooks hooks) {
        whenWithinTicksUntil(context,tick,tick+ticks,condition,ready,diagnostics,hooks);
    }
    private static void whenWithinTicksUntil(TestContext context,int tick,int last,BooleanSupplier condition,IntConsumer ready,
            java.util.function.Supplier<String> diagnostics,M2CorridorGameTests.TestHooks hooks) {
        context.runAtTick(tick,() -> {
            try {
                if(condition.getAsBoolean()) ready.accept(tick);
                else {
                    context.assertTrue(tick<last,"有界等待逾時（至 tick "+last+"）："+diagnostics.get());
                    whenWithinTicksUntil(context,tick+1,last,condition,ready,diagnostics,hooks);
                }
            } catch(RuntimeException | Error failure) {
                if(hooks!=null) hooks.releaseAfter(failure);
                throw failure;
            }
        });
    }
    private static void when(TestContext context, int tick, BooleanSupplier condition, IntConsumer ready) {
        whenUntil(context,tick,condition,ready,System.nanoTime()+60_000_000_000L);
    }
    private static void whenUntil(TestContext context,int tick,BooleanSupplier condition,IntConsumer ready,long deadline) {
        context.runAtTick(tick, () -> {
            if (condition.getAsBoolean()) ready.accept(tick);
            else {
                context.assertTrue(System.nanoTime()<deadline,"候選原生測試等待逾時");
                whenUntil(context, tick + 1, condition, ready,deadline);
            }
        });
    }
}
