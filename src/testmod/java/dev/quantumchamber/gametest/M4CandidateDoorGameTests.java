package dev.quantumchamber.gametest;

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

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_north", tickLimit=100000)
    public void north_complete_doors_recycle_split_merge_and_seam(TestContext context) { completeDoors(context,Direction.NORTH,true); }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_east", tickLimit=100000)
    public void east_complete_doors_require_checked_ledger(TestContext context) { completeDoors(context,Direction.EAST,false); }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_south", tickLimit=100000)
    public void south_complete_doors_require_checked_ledger(TestContext context) { completeDoors(context,Direction.SOUTH,false); }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_doors_west", tickLimit=100000)
    public void west_complete_doors_require_checked_ledger(TestContext context) { completeDoors(context,Direction.WEST,false); }

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
            fixture.assertComplete(); OBSERVATIONS.remove(fixture.session); context.complete();
        });
    }

    public static void beforeCandidateFlush(SessionRecoveryState state,MinecraftServer server) {
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
        for(var entry : OBSERVATIONS.entrySet()) {
            var record=state.flushedRecords().get(entry.getKey()); var observation=entry.getValue();
            if(observation.reentryProbed || record==null || record.state()!=SessionState.SUPERPOSITION || record.candidateLedger().size()!=256) continue;
            // 第一批已 readback、第二批仍缺失時，故意在同一 server tick 重入兩次。
            observation.reentryProbed=true;
            observation.pages.tick(); observation.pages.tick();
            observation.hidden &= state.flushedRecords().get(entry.getKey()).candidateLedger().size()==256;
        }
    }
    private static final class FlushObservation {
        final CorridorPageManager pages; final Map<Integer,Integer> counts=new HashMap<>(); final List<Integer> sizes=new ArrayList<>();
        boolean hidden=true,reentryProbed;
        FlushObservation(CorridorPageManager pages) { this.pages=pages; }
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_authority_rejection",tickLimit=100000)
    public void unhealthy_authorities_reject_before_reservation(TestContext context) { authorityRejections(context,false); }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m4_legacy_runtime",tickLimit=100000)
    public void isolated_legacy_schema2_then_empty_schema3_authority_gates(TestContext context) { authorityRejections(context,true); }

    private static void authorityRejections(TestContext context,boolean legacyOnly) {
        var fixture=new M2CorridorGameTests.NativeEntry(context,1,Direction.NORTH);
        context.waitAndRun(3,() -> {
            var server=fixture.server; var root=server.getSavePath(net.minecraft.util.WorldSavePath.ROOT);
            var journal=SessionRecoveryState.get(server);
            assertEmptyAuthorities(context,fixture.pages,journal);
            try {
                var discovery=dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
                var entropy=dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root,!discovery.records().isEmpty());
                var entropyPath=root.resolve("data/quantumchamber_candidate_entropy.dat");
                var discoveryPath=root.resolve("data/quantumchamber_universe_discovery.dat");
                var modes=legacyOnly ? List.of("legacy_current","legacy_durable")
                        : List.of("corrupt_entropy","corrupt_discovery","discovery_missing_entropy","candidate_missing_discovery","candidate_missing_entropy");
                for(String mode : modes) {
                    var sid=UUID.randomUUID(); var chamber=UUID.randomUUID();
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
                    } finally {
                        if(context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
                        java.nio.file.Files.move(backup,target,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        if(mode.equals("discovery_missing_entropy")) discovery.save(root);
                        journal.remove(sid); journal.flush(server);
                    }
                }
                assertEmptyAuthorities(context,fixture.pages,journal);
                if(legacyOnly) context.runAtTick(4,() -> legacySchema2Runtime(context)); else context.complete();
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

    private static void assertEmptyAuthorities(TestContext context,CorridorPageManager pages,SessionRecoveryState journal) {
        context.assertTrue(journal.records().isEmpty() && journal.flushedRecords().isEmpty()
                && ((Map<?,?>)M4CandidateTestAccess.get(pages,"spaces")).isEmpty(),
                "legacy隔離情境開始／完成都必須沒有current、durable journal或space");
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
                                    context.complete();
                                } finally { source.close(); }
                            }));
                });
            });
        });
    }
    @GameTest(templateName="quantumchamber:m1_empty", batchId="m4_candidate_context", tickLimit=100000)
    public void native_start_freezes_candidate_context_before_geometry(TestContext context) {
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
                context.complete();
            });
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
