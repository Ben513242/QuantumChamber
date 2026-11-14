package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.universe.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.persistence.HomeSessionRecord.*;
import dev.quantumchamber.superposition.SessionState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;

public final class M7HomeAuthorityGameTests implements FabricGameTest {
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_ticket",tickLimit=300)
    public void partial_acquire_and_late_callback_release_native_tickets(TestContext context) {
        var f=new M2CorridorGameTests.NativeEntry(context,1);
        var manager=(dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
        var a=f.controller();
        var sid=UUID.randomUUID();
        try {
            context.assertTrue(new ChamberActivationService().attemptArm(context.getWorld(),a).accepted(),"正式 A 登記");
            var root=new ChamberOriginAuthority(a.chamberUuid(),context.getWorld().getRegistryKey().getValue(),DimensionRole.OVERWORLD,
                    f.frame.controllerPos(),f.frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
            context.assertTrue(manager.claimProvisional(f.server,a.chamberUuid(),sid),"開始前暫占");
            context.assertTrue(HomeTicketFaultFixture.failAfterAdding(f.server,sid,root)==1,"故障點已有一張原生票");
            context.assertTrue(HomeTicketFaultFixture.count(context.getWorld(),sid)==0,"部分取得後須清完原生票");
            var lease=HomeRootTickets.acquire(f.server,sid,root,HomeRootTickets.Purpose.START_CHECK).orElseThrow();
            context.assertTrue(HomeTicketFaultFixture.count(context.getWorld(),sid)>0,"重試有自己的原生票");
            context.waitAndRun(201,()->{
                try {
                    context.assertTrue(!lease.full() && HomeTicketFaultFixture.count(context.getWorld(),sid)==0,
                            "第 201 tick 才來的 FULL 回調不得留下票");
                    context.assertTrue(!manager.globalChamberBusy(f.server,a.chamberUuid()),"已取消的暫占不可復活");
                    lease.close();
                    context.complete();
                } finally { manager.cancelProvisional(f.server,a.chamberUuid(),sid); f.close(); }
            });
        } catch(RuntimeException | Error failure) {
            manager.cancelProvisional(f.server,a.chamberUuid(),sid); f.close(); throw failure;
        }
    }

    private static void checkDurableOwnership(TestContext c,M2CorridorGameTests.NativeEntry f,EntryEndpoint entry) throws Exception {
        var server=f.server;
        var gate=(dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
        var journal=SessionRecoveryState.get(server);
        var sid=UUID.randomUUID();
        var root=new ChamberOriginAuthority(entry.chamberUuid(),net.minecraft.world.World.OVERWORLD.getValue(),DimensionRole.OVERWORLD,
                entry.controllerPos(),entry.facing(),ChamberInstanceKind.ORIGIN);
        c.assertTrue(HomeEndpointAuthority.checkRoot(server,root,false)==HomeEndpointAuthority.RootStatus.READY,"原艙結構與身分");
        c.assertTrue(HomeEndpointAuthority.checkRoot(server,root,true)==HomeEndpointAuthority.RootStatus.LOW,"即時電位 LOW");
        var actor=f.players.getFirst().player().getUuid();
        var person=new SessionRecoveryRecord.Participant(actor,net.minecraft.util.math.Vec3d.ZERO,net.minecraft.util.math.Vec3d.ZERO,
                0,0,new net.minecraft.nbt.NbtCompound(),false);
        var lease=new SessionRecoveryRecord.SpaceLease(62,new net.minecraft.util.math.BlockBox(600000,64,600000,600006,70,600006));
        var empty=new Home(new Unselected(),-1,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var row=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(person),List.of(lease),SessionState.ARMING,empty);
        c.assertTrue(gate.claimProvisional(server,entry.chamberUuid(),sid),"先占後寫");
        c.assertTrue(journal.admitHome(server,row).committed() && gate.finishProvisional(server,entry.chamberUuid(),sid),"原 SID 升級 checked ARMING");
        c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"ARMING 已接手互斥");
        var active=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(person),List.of(lease),SessionState.SUPERPOSITION,empty);
        c.assertTrue(journal.commitHomeRestore(server,row,active).committed(),"正式 SUPERPOSITION");
        try(var ticket=HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.SELECT_CHECK).orElseThrow()) {
            c.assertTrue(ticket.full(),"選擇檢查取得已載入原艙");
        }
        long time=c.getWorld().getTime();
        var selection=new Selected(new dev.quantumchamber.corridor.DoorKey(sid,1,
                dev.quantumchamber.corridor.DoorKey.DoorWallSide.POSITIVE_LATERAL),actor,time);
        var selected=new Home(selection,-1,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var measured=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(person),List.of(lease),SessionState.MEASURED,selected);
        c.assertTrue(journal.commitHomeRestore(server,active,measured).committed(),"正式選擇");
        var returned=new SessionRecoveryRecord.Participant(actor,net.minecraft.util.math.Vec3d.ZERO,net.minecraft.util.math.Vec3d.ZERO,0,0,person.quantumStateSnapshot(),true);
        var settled=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(lease),SessionState.MEASURED,selected);
        c.assertTrue(journal.commitHomeRollForward(server,measured,settled).committed(),"返還參與者");
        var dormant=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.MEASURED,selected);
        c.assertTrue(journal.commitHomeRollForward(server,settled,dormant).committed(),"釋放空間");
        c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"無空間 DORMANT 仍占艙");
        var prepared=new Home(selection,time,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var collapse=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.COLLAPSING,prepared);
        c.assertTrue(journal.commitHomeRestore(server,dormant,collapse).committed(),"正式準備");
        var ticket=HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE).orElseThrow();
        c.waitAndRun(201,()->{
            c.assertTrue(!ticket.full() && HomeTicketFaultFixture.count(c.getWorld(),sid)==0,"PREPARE 最多 200 ticks");
            c.assertTrue(HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE).isEmpty(),"退避中不得逐 tick 重新領票");
            c.assertTrue(collapse.equals(journal.allFlushedSessions().get(sid)),"技術等待保留原 SID、HOME 與空白停止原因");
            c.waitAndRun(201,()->finishDurableOwnership(c,f,collapse,HomeRootTickets.acquire(server,sid,root,
                    HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE).orElseThrow()));
        });
    }

    private static void finishDurableOwnership(TestContext c,M2CorridorGameTests.NativeEntry f,HomeSessionRecord collapse,HomeRootTickets.Lease ticket) {
        var server=f.server;
        var sid=collapse.sessionUuid();
        var entry=collapse.entryEndpoint();
        var root=collapse.rootAnchor();
        var selection=collapse.home().selection();
        var time=collapse.home().committedAtGameTime();
        var returned=collapse.participants().getFirst();
        var actor=returned.playerUuid();
        var gate=(dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
        var journal=SessionRecoveryState.get(server);
        var sealedHome=new Home(selection,time,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.of(Seal.AUTHORITY_UNREADABLE));
        var sealed=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.SEALED,sealedHome);
        c.assertTrue(journal.commitHomeRollForward(server,collapse,sealed).committed(),"正式封存");
        c.assertTrue(!ticket.full() && HomeTicketFaultFixture.count(c.getWorld(),sid)==0,"SEALED 不持票");
        c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"SEALED 仍占艙");
        c.assertTrue(journal.commitHomeRestore(server,sealed,collapse).committed(),"可重試封存");
        var opened=new Home(selection,time,time,time+1200,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var ready=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.PASSAGE_READY,opened);
        c.assertTrue(journal.commitHomeRestore(server,collapse,ready).committed(),"正式通道");
        var world=UniverseLifecycleService.resolveActive(server,entry.universeId()).orElseThrow();
        var bounds=ChamberGeometry.bounds(new ChamberFrame(entry.controllerPos(),entry.facing()));
        // 模擬玩家回到 B，準備期間經過四百 tick，原來測試方塊所在的區塊可能已自然卸載。
        for (int x=(bounds.getMinX()-4)>>4; x<=(bounds.getMaxX()+4)>>4; x++)
            for (int z=(bounds.getMinZ()-4)>>4; z<=(bounds.getMaxZ()+4)>>4; z++) world.getChunk(x,z);
        var bPresence=gate.localPresence(world,entry.controllerPos());
        var aPresence=gate.localPresence(c.getWorld(),root.controllerPos());
        c.assertTrue(bPresence==ChamberSessionGateway.Presence.PASSAGE && aPresence==ChamberSessionGateway.Presence.BUSY,
                "B 的通道不能在 A 開門："+bPresence+"/"+aPresence);
        var ended=ready.withClose(new Close(CloseReason.PARTICIPANT_CLOSED,time,DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN,new ParticipantClosed(actor,Phase.PASSAGE,time)));
        c.assertTrue(journal.commitHomeRollForward(server,ready,ended).committed(),"正式 CLOSE");
        c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"CLOSE 持續占艙");
        try { new HomeReceiptStore(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)).publishOrVerify(ended); }
        catch(java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        c.assertTrue(journal.terminateHome(server,ended).removed() && !gate.globalChamberBusy(server,entry.chamberUuid()),"checked 移除解鎖，歷史不重建占用");
        c.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_authority",tickLimit=1500)
    public void only_exact_complete_projection_resolves(TestContext context) throws Exception {
        try (var fixture = new M2CorridorGameTests.NativeEntry(context, 1)) {
            var a = fixture.controller();
            context.assertTrue(new ChamberActivationService().attemptArm(context.getWorld(),a).accepted(), "正式 A 登記");
            var frame = new ChamberFrame(a.getPos(), a.getCachedState().get(ChamberControllerBlock.FACING));
            var id = UniverseId.of(UUID.randomUUID());
            var world = M6ProfileProbePhases.fixtureSource(fixture.server, id, "normal");
            var authorities = CollapseAuthorities.get(fixture.server);
            var index = ProjectionProtectionIndex.get(fixture.server);
            var pending = authorities.writeProjection(new ProjectionLedger.Entry(a.chamberUuid(), id,
                    DimensionRole.OVERWORLD, a.getPos(), frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
            index.confirmed(pending);
            var bounds = ChamberGeometry.bounds(frame);
            for (int x = (bounds.getMinX()-4) >> 4; x <= (bounds.getMaxX()+4) >> 4; x++)
                for (int z = (bounds.getMinZ()-4) >> 4; z <= (bounds.getMaxZ()+4) >> 4; z++) world.getChunk(x,z);
            var write = ProjectionWriter.write(world, frame, ProjectionWriter.Mode.FIRST_WRITE);
            context.assertTrue(write.status() == ProjectionWriter.Status.WRITTEN, "正式投影寫入");
            var b = (ChamberControllerBlockEntity)world.getBlockEntity(a.getPos());
            b.setInstanceKind(ChamberInstanceKind.PROJECTION);
            context.assertTrue(HomeEndpointAuthority.resolveEntry(world,b).isEmpty(), "PENDING 必須拒絕");
            index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
            var endpoint=new dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint(a.chamberUuid(),id,world.getRegistryKey().getValue(),
                    DimensionRole.OVERWORLD,a.getPos(),frame.outwardFacing(),ChamberInstanceKind.PROJECTION);
            context.assertTrue(HomeEndpointAuthority.resolveEntry(world,b).filter(endpoint::equals).isPresent(),
                    "已確認且同實例的 COMPLETE 投影可辨識："+HomeEndpointAuthority.inspectEntry(fixture.server,endpoint));
            var stale = new ChamberControllerBlockEntity(b.getPos(),b.getCachedState());
            stale.setWorld(world);
            stale.setInstanceKind(ChamberInstanceKind.PROJECTION);
            context.assertTrue(HomeEndpointAuthority.resolveEntry(world,stale).isEmpty(),"相同位置舊 BE 仍拒絕");
            b.setChamberUuid(UUID.randomUUID());
            context.assertTrue(HomeEndpointAuthority.resolveEntry(world,b).isEmpty(),"異艙 UUID 拒絕");
            b.setChamberUuid(null);
            checkDurableOwnership(context,fixture,endpoint);
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_authority",tickLimit=200)
    public void a_and_two_b_entries_contend_once(TestContext context) {
        try(var f=new M2CorridorGameTests.NativeEntry(context,1)) {
            var gate=(dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
            var origin=f.controller();
            context.assertTrue(new ChamberActivationService().attemptArm(context.getWorld(),origin).accepted(),"A 已登記");
            var sid=UUID.randomUUID();
            var chamber=origin.chamberUuid();
            context.assertTrue(gate.claimProvisional(f.server,chamber,sid),"B1 先占");
            try {
                f.pages.exclusiveOperation(sid,()->{
                    boolean refused=false;
                    try { f.pages.exclusiveOperation(sid,()->null); } catch (IllegalStateException nested) { refused=true; }
                    context.assertTrue(refused,"無空間仍拒絕同 SID 重入");
                    context.assertTrue(!gate.claimProvisional(f.server,chamber,UUID.randomUUID()),"B2 同艙拒絕");
                    context.assertTrue(gate.start(context.getWorld(),origin,java.util.List.of(f.players.getFirst().player().getUuid()))
                            ==ChamberSessionGateway.StartResult.REJECTED,"已有 B 暫占時 A 拒絕");
                    return null;
                });
            } finally { gate.cancelProvisional(f.server,chamber,sid); }
            context.assertTrue(!gate.globalChamberBusy(f.server,chamber),"取消後可用");
            context.complete();
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_authority",tickLimit=200)
    public void provisional_wins_before_first_ticket(TestContext context) throws Exception {
        try(var fixture = new M2CorridorGameTests.NativeEntry(context,1)) {
            context.assertTrue(new ChamberActivationService().attemptArm(context.getWorld(),fixture.controller()).accepted(), "正式 A 登記");
            var manager = (dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
            var chamber = fixture.controller().chamberUuid();
            var sid = UUID.randomUUID();
            var root=new ChamberOriginAuthority(chamber,context.getWorld().getRegistryKey().getValue(),DimensionRole.OVERWORLD,
                    fixture.frame.controllerPos(),fixture.frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
            try {
                context.assertTrue(HomeRootTickets.acquire(fixture.server,sid,root,HomeRootTickets.Purpose.START_CHECK).isEmpty(),"不能先加票再占位");
                context.assertTrue(manager.claimProvisional(fixture.server,chamber,sid),"首位成功");
                context.assertTrue(manager.presence(fixture.server,chamber)!=ChamberSessionGateway.Presence.NONE,"第一張票之前已占用");
                var ticket=HomeRootTickets.acquire(fixture.server,sid,root,HomeRootTickets.Purpose.START_CHECK).orElseThrow();
                context.assertTrue(HomeRootTickets.acquire(fixture.server,sid,root,HomeRootTickets.Purpose.START_CHECK).isEmpty(),"同 SID 單票");
                manager.cancelProvisional(fixture.server,chamber,sid);
                context.assertTrue(HomeTicketFaultFixture.count(context.getWorld(),sid)==0,"取消必須立即清除真正的票");
                context.assertTrue(!ticket.full() && !manager.globalChamberBusy(fixture.server,chamber),"取消使舊票失效且釋放占位");
            } finally { manager.cancelProvisional(fixture.server,chamber,sid); }
            context.complete();
        }
    }
}
