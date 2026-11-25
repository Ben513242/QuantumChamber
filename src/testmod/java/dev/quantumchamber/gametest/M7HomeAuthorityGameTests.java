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
    /**
     * T5-4 Step 0（T5-2 規格 M-3、品質 M-6）：未選定返還的移除只接受走廊 owner 在同一個 tick 建立、未用過、屬於這一列的 proof。以正式 journal 寫入一列
     * checked RETURNING、全員已返還（{@code finishHomeReturn} 本體會接受）的列，逐項核對：沒有 proof、別列的 proof、已用過的 proof、前一個 tick 的 proof 都拒絕且列不變；
     * 同一個 tick 的新 proof 才移除（也是本案的收尾）。proof 以 testmod 反射建立（C6）。RED：proof 沒有綁定建立的 tick（{@code PROOF_STALE_TICK_ACCEPTED}）。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_proof_guard",tickLimit=200)
    public void home_return_proof_rejects_missing_forged_reused_or_stale(TestContext c) {
        var server=c.getWorld().getServer();
        var journal=SessionRecoveryState.get(server);
        var done=M7CapacityFixture.admitReturned(server);
        var sid=done.sessionUuid();
        try {
            var findings=new java.util.ArrayList<String>();
            java.util.function.BooleanSupplier untouched=() -> done.equals(journal.allSessions().get(sid)) && done.equals(journal.allFlushedSessions().get(sid));
            if(journal.finishHomeReturn(server,done,null).removed() || !untouched.getAsBoolean()) findings.add("PROOF_NULL_ACCEPTED");
            var person=done.participants().getFirst();
            var other=new HomeSessionRecord(sid,done.chamberUuid(),done.rootAnchor(),done.entryEndpoint(),List.of(new SessionRecoveryRecord.Participant(person.playerUuid(),
                    person.sourcePosition().add(1,0,0),person.sourceVelocity(),person.yaw(),person.pitch(),person.quantumStateSnapshot(),true)),done.spaceLeases(),done.state(),done.home());
            if(journal.finishHomeReturn(server,done,M7CapacityFixture.forgeReturnProof(server,other)).removed() || !untouched.getAsBoolean()) findings.add("PROOF_FOR_OTHER_ROW_ACCEPTED");
            var used=M7CapacityFixture.forgeReturnProof(server,done);
            if(!used.consume(server,done)) findings.add("前提：同一個 tick、同一列的新 proof 可以消費一次");
            if(journal.finishHomeReturn(server,done,used).removed() || !untouched.getAsBoolean()) findings.add("PROOF_REUSED_ACCEPTED");
            var stale=M7CapacityFixture.forgeReturnProof(server,done);
            c.waitAndRun(1,() -> {
                try {
                    if(journal.finishHomeReturn(server,done,stale).removed() || !untouched.getAsBoolean()) findings.add("PROOF_STALE_TICK_ACCEPTED");
                    if(untouched.getAsBoolean() && !journal.finishHomeReturn(server,done,M7CapacityFixture.forgeReturnProof(server,done)).removed())
                        findings.add("VALID_PROOF_REJECTED：同一個 tick、未用過、屬於這一列的 proof 必須移除");
                    c.assertTrue(findings.isEmpty() && !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid),
                            "HOME_RETURN_PROOF_GUARD："+findings+" row="+journal.allSessions().get(sid));
                    c.complete();
                } catch(RuntimeException | Error failure) {
                    try { M7CapacityFixture.removeIfPresent(server,done); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }
                    if(failure instanceof net.minecraft.test.GameTestException test) throw test;
                    var wrapped=new net.minecraft.test.GameTestException("M7_HOME_PROOF_CALLBACK：回呼內例外："+failure);
                    wrapped.initCause(failure);
                    throw wrapped;
                }
            });
        } catch(RuntimeException | Error failure) {
            try { M7CapacityFixture.removeIfPresent(server,done); } catch(RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_remove_fault",tickLimit=100)
    public void removal_fault_isolated_for_two_native_owners(TestContext c) {
        var first=new M2CorridorGameTests.NativeEntry(c,1);
        M2CorridorGameTests.NativeEntry second;
        try { second=new M2CorridorGameTests.NativeEntry(c,1,net.minecraft.util.math.Direction.NORTH,true,new net.minecraft.util.math.BlockPos(0,12,0)); }
        catch (RuntimeException | Error failure) { first.close(); throw failure; }
        var manager=(dev.quantumchamber.superposition.SuperpositionSessionManager)ChamberSessions.gateway();
        var sid1=UUID.randomUUID();
        var sid2=UUID.randomUUID();
        try {
            var a=first.controller();
            var b=(ChamberControllerBlockEntity)c.getWorld().getBlockEntity(second.frame.controllerPos());
            var arm=new ChamberActivationService();
            c.assertTrue(arm.attemptArm(c.getWorld(),a).accepted() && arm.attemptArm(c.getWorld(),b).accepted(),"兩座不同高度的原艙都經正式登記");
            var rootA=new ChamberOriginAuthority(a.chamberUuid(),c.getWorld().getRegistryKey().getValue(),DimensionRole.OVERWORLD,
                    first.frame.controllerPos(),first.frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
            var rootB=new ChamberOriginAuthority(b.chamberUuid(),c.getWorld().getRegistryKey().getValue(),DimensionRole.OVERWORLD,
                    second.frame.controllerPos(),second.frame.outwardFacing(),ChamberInstanceKind.ORIGIN);
            c.assertTrue(manager.claimProvisional(first.server,a.chamberUuid(),sid1) && manager.claimProvisional(first.server,b.chamberUuid(),sid2),"兩 SID 各自暫占");
            var keep=HomeTicketFaultFixture.failFirstRemoval(first.server,sid1,rootA);
            var other=HomeRootTickets.acquire(first.server,sid2,rootB,HomeRootTickets.Purpose.START_CHECK).orElseThrow();
            var cells=HomeTicketFaultFixture.positions(c.getWorld(),sid1);
            c.assertTrue(!cells.isEmpty() && cells.equals(HomeTicketFaultFixture.positions(c.getWorld(),sid2)),"兩 SID 真正共持相同原生區塊");
            other.close();
            c.assertTrue(cells.equals(HomeTicketFaultFixture.positions(c.getWorld(),sid1)) && HomeTicketFaultFixture.count(c.getWorld(),sid2)==0,
                    "只移除自己的原生 refs");
            var again=HomeRootTickets.acquire(first.server,sid2,rootB,HomeRootTickets.Purpose.START_CHECK).orElseThrow();
            boolean propagated=false;
            try { HomeTicketFaultFixture.reloadTickets(first.server); }
            catch(IllegalStateException failure) {
                if (!"M7_REMOVE_ONCE".equals(failure.getMessage())) throw failure;
                propagated=true;
            }
            boolean unsafe=propagated || HomeTicketFaultFixture.count(c.getWorld(),sid2)!=0;
            if (unsafe) {
                // RED 從已知故障收回兩個 SID，再回報精確斷言。
                manager.cancelProvisional(first.server,a.chamberUuid(),sid1);
                manager.cancelProvisional(first.server,b.chamberUuid(),sid2);
            }
            c.assertTrue(!unsafe,"I05_RELOAD_DROP_OTHER_OR_THROW");
            c.waitAndRun(2,()->{
                try {
                    c.assertTrue(!keep.full() && !again.full() && HomeTicketFaultFixture.count(c.getWorld(),sid1)==0
                            && HomeTicketFaultFixture.count(c.getWorld(),sid2)==0,"I05_RETRY_RETAINS_AND_CLEARS");
                    c.complete();
                } finally { first.close(); second.close(); }
            });
        } catch(RuntimeException | Error failure) {
            if(first.controller().chamberUuid()!=null) manager.cancelProvisional(first.server,first.controller().chamberUuid(),sid1);
            var b=(ChamberControllerBlockEntity)c.getWorld().getBlockEntity(second.frame.controllerPos());
            if(b!=null && b.chamberUuid()!=null) manager.cancelProvisional(first.server,b.chamberUuid(),sid2);
            first.close(); second.close(); throw failure;
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkAuthorityFailures(MinecraftServer server,ServerWorld world,ChamberControllerBlockEntity b,
            EntryEndpoint entry,List<String> findings) {
        var registry=ChamberRegistryState.get(server).registry();
        var rows=(java.util.Map<UUID,ChamberRecord>)M4CandidateTestAccess.get(registry,"records");
        var original=rows.remove(entry.chamberUuid());
        try {
            var root=new ChamberOriginAuthority(entry.chamberUuid(),net.minecraft.world.World.OVERWORLD.getValue(),
                    DimensionRole.OVERWORLD,entry.controllerPos(),entry.facing(),ChamberInstanceKind.ORIGIN);
            if (HomeEndpointAuthority.resolveEntry(world,b).isPresent()
                    || HomeEndpointAuthority.inspectEntry(server,entry)!=HomeEndpointAuthority.EntryStatus.VALID
                    || HomeEndpointAuthority.checkRoot(server,root,false)!=HomeEndpointAuthority.RootStatus.DESTROYED) findings.add("I01_REVOKED");
        } finally { rows.put(entry.chamberUuid(),original); }
        var catalog=UniverseRegistryState.get(server);
        var previous=M4CandidateTestAccess.get(catalog,"loadError");
        try {
            M4CandidateTestAccess.set(catalog,"loadError","M7_CATALOG_CORRUPT");
            if(HomeEndpointAuthority.inspectEntry(server,entry)!=HomeEndpointAuthority.EntryStatus.AUTHORITY_CORRUPT) findings.add("I02_CORRUPT");
        } finally { M4CandidateTestAccess.set(catalog,"loadError",previous); }
        var ctx=((java.util.Map<?,?>)M4CandidateTestAccess.get(UniverseLifecycleService.class,"CONTEXTS")).get(server);
        var ready=M4CandidateTestAccess.get(ctx,"ready");
        try {
            M4CandidateTestAccess.set(ctx,"ready",false);
            if(HomeEndpointAuthority.inspectEntry(server,entry)!=HomeEndpointAuthority.EntryStatus.UNKNOWN) findings.add("I02_NOT_READY");
        } finally { M4CandidateTestAccess.set(ctx,"ready",ready); }
    }

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

    private static void checkDurableOwnership(TestContext c,M2CorridorGameTests.NativeEntry f,EntryEndpoint entry,
            List<String> findings,boolean checkLate,Runnable next) {
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
        var prepared=new Home(selection,time,-1,-1,List.of(),List.of(),Optional.empty(),-1,new None(),Optional.empty());
        var collapse=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.COLLAPSING,prepared);
        if (!checkLate) {
            var settled=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(lease),SessionState.MEASURED,selected);
            c.assertTrue(journal.commitHomeRollForward(server,measured,settled).committed(),"正式返還");
            var dormant=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.MEASURED,selected);
            c.assertTrue(journal.commitHomeRollForward(server,settled,dormant).committed(),"正式進入 DORMANT");
            c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"無空間 DORMANT 仍占艙");
            c.assertTrue(journal.commitHomeRestore(server,dormant,collapse).committed(),"正式開始準備");
            finishDurableOwnership(c,f,collapse,HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE).orElseThrow(),findings,false,next);
            return;
        }
        var early=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(person),List.of(lease),SessionState.COLLAPSING,prepared);
        c.assertTrue(journal.commitHomeRestore(server,measured,early).committed(),"正式進入尚待返還的 COLLAPSING");
        var tooEarly=HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE);
        if (tooEarly.isPresent() || HomeTicketFaultFixture.count(c.getWorld(),sid)!=0) findings.add("I04_UNRETURNED");
        tooEarly.ifPresent(HomeRootTickets.Lease::close);
        var settled=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(lease),SessionState.COLLAPSING,prepared);
        c.assertTrue(journal.commitHomeRollForward(server,early,settled).committed(),"返還參與者");
        var withLease=HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE);
        if (withLease.isPresent() || HomeTicketFaultFixture.count(c.getWorld(),sid)!=0) findings.add("I04_LEASE_HELD");
        withLease.ifPresent(HomeRootTickets.Lease::close);
        c.assertTrue(journal.commitHomeRollForward(server,settled,collapse).committed(),"正式完成返還及空間清理");
        var delay=new HomeTicketFaultFixture.DelayedFull(server);
        var ticket=delay.acquire(sid,root);
        var pos=new net.minecraft.util.math.ChunkPos(root.controllerPos());
        int[] missing={0};
        java.util.function.IntConsumer poll=ordinal -> {
            if (!ticket.full()) missing[0]++;
            if (ordinal==200 && (missing[0]!=200 || delay.lastMissing!=200 || delay.polls<200)) findings.add("I06_EARLY_FULL");
        };
        poll.accept(1);
        // T4 fix1：第二趟由第一趟的收尾回呼啟動，此時原版 GameTestState 正在迭代自己的排程表（fastutil Object2LongOpenHashMap）。
        // 回呼內一次排入兩百筆會讓表在迭代中 rehash，正在執行的回呼留在表內、下一 tick 再跑一次（gate t4a-fix1-diag 實測：
        // 第一趟收尾斷言在 tick 586 通過、587 重跑時第二趟已合法占艙）。改為每 tick 只排下一筆；輪詢次數、tick 位置與判定都不變。
        pollEachTick(c,f,poll,2,()->{
            try {
                boolean first=delay.full(c.getWorld(),pos);
                if (!first || delay.firstFull!=201 || ticket.full() || HomeTicketFaultFixture.count(c.getWorld(),sid)!=0)
                    findings.add("I06_LATE_CALLBACK");
                c.assertTrue(HomeRootTickets.acquire(server,sid,root,HomeRootTickets.Purpose.HOME_PREPARE_PASSAGE).isEmpty(),"退避中不得逐 tick 重新領票");
                c.assertTrue(collapse.equals(journal.allFlushedSessions().get(sid)),"技術等待保留原 SID、HOME 與空白停止原因");
                c.waitAndRun(201,()->{
                    try { finishDurableOwnership(c,f,collapse,delay.acquire(sid,root),findings,true,next); }
                    finally { f.close(); }
                });
            } catch (RuntimeException | Error problem) { f.close(); throw problem; }
        });
    }

    /** 從第 {@code ordinal} 次起每 tick 輪詢一次到第 200 次，再下一個 tick 執行 {@code after}；每次只在排程表排入一筆。 */
    private static void pollEachTick(TestContext c,M2CorridorGameTests.NativeEntry f,java.util.function.IntConsumer poll,int ordinal,Runnable after) {
        c.waitAndRun(1,()->{
            try { poll.accept(ordinal); }
            catch (RuntimeException | Error failure) { f.close(); throw failure; }
            if (ordinal<200) pollEachTick(c,f,poll,ordinal+1,after); else c.waitAndRun(1,after);
        });
    }

    private static void finishDurableOwnership(TestContext c,M2CorridorGameTests.NativeEntry f,HomeSessionRecord collapse,HomeRootTickets.Lease ticket,List<String> findings,boolean checkStop,Runnable next) {
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
        c.assertTrue(ticket.full(),"原 SID 於退避期後重新取得 FULL");
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
        var properties=server.getSaveProperties().getMainWorldProperties();
        long savedTime=properties.getTime();
        try {
            properties.setTime(opened.passageExpiresAtGameTime());
            if (gate.localPresence(world,entry.controllerPos())==ChamberSessionGateway.Presence.PASSAGE
                    || !gate.globalChamberBusy(server,entry.chamberUuid())) findings.add("I03_EXPIRED");
        } finally { properties.setTime(savedTime); }
        // T4：CLOSE 只由回程終局 owner mint，移除只經 owner 的 B 門／checkpoint／收據交接。先保留 B 的 entity storage 未就緒，CLOSE 期間的占用斷言才是決定性的。
        var hooks=new M2CorridorGameTests.TestHooks();
        var hold=M5GameTestSupport.holdEntityStorage(c,world,java.util.Set.of(new net.minecraft.util.math.ChunkPos(entry.controllerPos())),hooks);
        hooks.add(M7HomeClosureGameTests.holdChunks(world,new ChamberFrame(entry.controllerPos(),entry.facing())));
        CloseReason expectedReason;
        if (checkStop) {
            var stoppedHome=new Home(selection,time,time,time+1200,List.of(),List.of(),Optional.of(RootReason.ROOT_DISABLED),time,new None(),Optional.empty());
            var stopped=new HomeSessionRecord(sid,entry.chamberUuid(),root,entry,List.of(returned),List.of(),SessionState.PASSAGE_READY,stoppedHome);
            c.assertTrue(journal.commitHomeRollForward(server,ready,stopped).committed(),"正式停止");
            if(gate.localPresence(world,entry.controllerPos())==ChamberSessionGateway.Presence.PASSAGE
                    || !gate.globalChamberBusy(server,entry.chamberUuid())) findings.add("I03_STOP");
            HomeClosure.step(server,stopped);
            expectedReason=CloseReason.ROOT_DISABLED;
        } else {
            // 參與者關門需要 exact B 的在線 actor 事件（T4 owner 入口）；此處改由 owner 以真到期 mint EXPIRED 收尾。
            try { properties.setTime(opened.passageExpiresAtGameTime()); HomeClosure.step(server,ready); }
            finally { properties.setTime(savedTime); }
            expectedReason=CloseReason.EXPIRED;
        }
        var ended=(HomeSessionRecord)journal.allFlushedSessions().get(sid);
        c.assertTrue(ended!=null && ended.equals(journal.allSessions().get(sid)) && ended.home().closeIntent() instanceof Close k
                && k.reason()==expectedReason,"owner 已 checked 凍結結案："+(ended==null?null:ended.home().closeIntent()));
        if(gate.localPresence(world,entry.controllerPos())==ChamberSessionGateway.Presence.PASSAGE
                || !gate.globalChamberBusy(server,entry.chamberUuid())) findings.add("I03_CLOSE");
        c.assertTrue(!gate.claimProvisional(server,entry.chamberUuid(),UUID.randomUUID()),"CLOSE 持續占艙");
        // T5-4 Step 0（T5-2 規格 M-3、品質 M-6）：恆拒的 public terminateHome 已移除；改核兩個正式移除入口不帶 owner proof 都拒絕，結案列也不能拿未選定返還的 proof 移除。
        c.assertTrue(!journal.finishHomeReturn(server,ended,null).removed() && !journal.finishHomeClose(server,ended,null).removed()
                && !journal.finishHomeReturn(server,ended,M7CapacityFixture.forgeReturnProof(server,ended)).removed()
                && ended.equals(journal.allSessions().get(sid)) && ended.equals(journal.allFlushedSessions().get(sid)),
                "CLOSE_ROW_REMOVED_WITHOUT_OWNER：結案列不能由外部 caller 自宣完成（沒有 owner proof、或拿未選定返還的 proof）");
        hold.release();
        M7HomeClosureGameTests.until(c,checkStop?"only_exact_complete_projection_resolves/root_stop":"only_exact_complete_projection_resolves/expired",200,() -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid),() -> {
            try {
                c.assertTrue(!gate.globalChamberBusy(server,entry.chamberUuid()),"checked 移除解鎖，歷史不重建占用");
                try { c.assertTrue(ended.equals(new HomeReceiptStore(server.getSavePath(net.minecraft.util.WorldSavePath.ROOT)).lookup(sid).orElse(null)),"單 SID 收據 exact"); }
                catch(java.io.IOException failure) { throw new net.minecraft.test.GameTestException("收據讀取失敗："+failure); }
            } finally { hooks.release(); }
            next.run();
        },() -> {
            try { throw new net.minecraft.test.GameTestException("HOME_CLOSE_NOT_FINISHED "+journal.allSessions().get(sid)); }
            finally { hooks.release(); }
        });
    }

    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_authority",tickLimit=1500)
    public void only_exact_complete_projection_resolves(TestContext context) throws Exception {
        var fixture=new M2CorridorGameTests.NativeEntry(context,1);
        try {
            M7HomeClosureGameTests.requireCandidateStores(fixture.server);
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
            var findings=new java.util.ArrayList<String>();
            checkAuthorityFailures(fixture.server,world,b,endpoint,findings);
            // T4：每趟要等 owner 的 checked 收尾完成，下一趟才能在同艙重新暫占。
            checkDurableOwnership(context,fixture,endpoint,findings,false,() -> checkDurableOwnership(context,fixture,endpoint,findings,true,() -> {
                context.assertTrue(findings.isEmpty(),"HOME_FIX1_TARGETS "+findings);
                context.complete();
            }));
        } catch(Exception | Error problem) { fixture.close(); throw problem; }
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
