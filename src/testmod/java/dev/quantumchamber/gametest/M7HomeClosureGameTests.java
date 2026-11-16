package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.collapse.*;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.persistence.HomeSessionRecord.*;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.universe.*;
import java.util.*;
import java.util.function.BooleanSupplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

/** T4 Phase A：回程終局 owner、B 門交接與原艙停用防線的同 JVM 驗證（M7 spec §5.3、§6、§9）。 */
public final class M7HomeClosureGameTests implements FabricGameTest {
    private static final ChunkTicketType<ChunkPos> HOLD = ChunkTicketType.create("quantumchamber_m7_closure_test", Comparator.comparingLong(ChunkPos::toLong));

    private static SuperpositionSessionManager gate() { return (SuperpositionSessionManager) ChamberSessions.gateway(); }

    /** N2-M3：排入在先、套用前同艙另一端的回程才變成 BUSY 時，A 的門不得被改寫（M7 spec :212）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_membrane",tickLimit=100)
    public void ownerless_membrane_yields_to_home_busy(TestContext c) {
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        var sid = UUID.randomUUID();
        UUID[] chamber = {null};
        Runnable cleanup = () -> { if (chamber[0] != null) gate().cancelProvisional(f.server, chamber[0], sid); f.close(); };
        try {
            c.assertTrue(new ChamberActivationService().attemptArm(c.getWorld(), f.controller()).accepted(), "正式 A 登記");
            chamber[0] = f.controller().chamberUuid();
            var world = c.getWorld();
            c.assertTrue(new ChamberDoorService().applyTarget(world, f.frame, ChamberDoorService.DoorState.sealed(f.frame.outwardFacing())).succeeded(),
                    "A 門帶著沒有所屬 session 的量子膜");
            c.assertTrue(gate().presence(f.server, chamber[0]) == ChamberSessionGateway.Presence.NONE, "排入時 A 沒有任何旅程");
            ChamberCollapsePort.membranes().normalizeOwnerless(world, f.frame, chamber[0]);
            c.assertTrue(gate().claimProvisional(f.server, chamber[0], sid), "B 的回程在套用前占用同艙");
            c.assertTrue(gate().presence(f.server, chamber[0]) == ChamberSessionGateway.Presence.BUSY, "A 端看到 BUSY");
            c.waitAndRun(3, () -> {
                try {
                    c.assertTrue(ChamberPowerCoordinator.doorHasMembrane(world, f.frame), "N2_M3_BUSY_DOOR_REWRITTEN：BUSY 時仍改寫 A 的門");
                    c.complete();
                } finally { cleanup.run(); }
            });
        } catch (RuntimeException | Error failure) { cleanup.run(); throw failure; }
    }

    /** B 原本普通關門（膜為 NONE）時，CLOSE 必須真的改門、做原生 checkpoint，再發收據與移除熱列（M7 spec §5.3 :173、審查焦點 4）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_door",tickLimit=600)
    public void ordinary_closed_door_needs_native_checkpoint(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            var sid = UUID.randomUUID();
            var dormant = p.dormant(sid, 59);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 的門是普通關門");
            c.assertTrue(!ChamberPowerCoordinator.doorHasMembrane(p.b, p.frame), "B 的門沒有量子膜");
            var store = p.receipts();
            var probe = M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 0, call -> {
                var row = journal.allFlushedSessions().get(sid);
                String close = row instanceof HomeSessionRecord h && h.home().closeIntent() instanceof Close k ? k.reason().name() : "NONE";
                boolean receipt;
                try { receipt = store.lookup(sid).isPresent(); } catch (java.io.IOException failure) { return "receipt=ERROR"; }
                return "door=" + p.doorSummary() + " close=" + close + " receipt=" + receipt;
            }, hooks);
            long now = server.getOverworld().getTime();
            var stopped = withStop(dormant, RootReason.ROOT_DISABLED, now);
            c.assertTrue(journal.commitHomeRollForward(server, dormant, stopped).committed(), "原艙停止原因已 checked latch");
            until(c, 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                try {
                    c.assertTrue("OPEN/NONE".equals(p.doorSummary()), "B 普通關門必須改為一般開門：" + p.doorSummary());
                    var calls = probe.calls();
                    c.assertTrue(!calls.isEmpty() && calls.getFirst().chunks().containsAll(p.doorChunks()),
                            "移除前必須涵蓋 B 門的原生 checkpoint：" + calls);
                    c.assertTrue("door=OPEN/NONE close=ROOT_DISABLED receipt=false".equals(calls.getFirst().observed()),
                            "先凍結 CLOSE 並改門，checkpoint 之後才發收據：" + calls.getFirst().observed());
                    var receipt = store.lookup(sid).orElseThrow();
                    c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED && k.doorGoal() == DoorGoal.OPEN
                            && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, now)), "收據保留原艙停止原因與時間：" + receipt.home().closeIntent());
                    c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                    c.complete();
                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                finally { p.close(); hooks.release(); }
            }, () -> {
                try { throw new GameTestException("ORDINARY_DOOR_NOT_CLOSED_OUT door=" + p.doorSummary() + " row=" + journal.allSessions().get(sid)
                        + " checkpoints=" + probe.calls()); }
                finally { p.close(); hooks.release(); }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 選後停用的直接 setter 與維護入口都同步 latch；同 tick 復啟仍保留 latch、不復活本趟（M7 spec §9.1）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_fence",tickLimit=900)
    public void root_disable_latches_before_ack_and_reenable(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
            var first = UUID.randomUUID();
            p.dormant(first, 58);
            long now = server.getOverworld().getTime();
            c.assertTrue(lifecycle.setEnabled(p.chamber, false), "停用的直接 setter 只在 latch 持久後回報成功");
            c.assertTrue(stopped(journal, first, RootReason.ROOT_DISABLED), "DIRECT_SETTER_LATCH：停用必須先 checked latch 本趟");
            c.assertTrue(((HomeSessionRecord) journal.allFlushedSessions().get(first)).home().stoppedAtGameTime() == now, "latch 時間是事件當下");
            c.assertTrue(lifecycle.setOriginEnabled(p.root, true), "latch 持久後同 tick 可復啟");
            c.assertTrue(stopped(journal, first, RootReason.ROOT_DISABLED), "復啟不撤銷 latch");
            until(c, 200, () -> !journal.allSessions().containsKey(first), () -> {
                try {
                    // 註冊已落 disabled、本趟停止尚未落盤：先 enable 也只能在 latch 之後生效。
                    var second = UUID.randomUUID();
                    p.dormant(second, 57);
                    p.rawEnabled(false);
                    c.assertTrue(lifecycle.setEnabled(p.chamber, true), "latch 完成後才放行 enable");
                    c.assertTrue(stopped(journal, second, RootReason.ROOT_DISABLED), "ENABLE_BEFORE_LATCH：enable 不得跳過尚未落盤的停止");
                    c.assertTrue(ChamberRegistryState.get(server).registry().records().get(p.chamber).enabled(), "registry 已復啟");
                    until(c, 200, () -> !journal.allSessions().containsKey(second), () -> {
                        try {
                            // 開服 fence 掃描：只有持久 registry 的停用，沒有任何通知（含沒有 runtime 的 DORMANT）。
                            var third = UUID.randomUUID();
                            p.dormant(third, 56);
                            p.rawEnabled(false);
                            HomeRootFence.bootstrap(server);
                            c.assertTrue(stopped(journal, third, RootReason.ROOT_DISABLED), "BOOTSTRAP_SCAN_DORMANT：開服掃描必須 latch 無 runtime 的 DORMANT");
                            p.rawEnabled(true);
                            until(c, 200, () -> !journal.allSessions().containsKey(third), () -> {
                                try {
                                    c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "三趟都 checked 收尾後解鎖");
                                    c.complete();
                                } finally { p.close(); hooks.release(); }
                            }, () -> { try { throw new GameTestException("THIRD_NOT_CLOSED " + journal.allSessions().get(third)); } finally { p.close(); hooks.release(); } });
                        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
                    }, () -> { try { throw new GameTestException("SECOND_NOT_CLOSED " + journal.allSessions().get(second)); } finally { p.close(); hooks.release(); } });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            }, () -> { try { throw new GameTestException("FIRST_NOT_CLOSED " + journal.allSessions().get(first)); } finally { p.close(); hooks.release(); } });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** B 暫占起 A 受保護：一般拆除被拒，管理員停用仍可（M7 spec §2 [R3]、V05）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_removal",tickLimit=100)
    public void home_busy_root_rejects_ordinary_removal(TestContext c) {
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        var sid = UUID.randomUUID();
        UUID[] chamber = {null};
        Runnable cleanup = () -> { if (chamber[0] != null) gate().cancelProvisional(f.server, chamber[0], sid); f.close(); };
        try {
            var world = c.getWorld();
            c.assertTrue(new ChamberActivationService().attemptArm(world, f.controller()).accepted(), "正式 A 登記");
            chamber[0] = f.controller().chamberUuid();
            new ChamberPowerCoordinator().refresh(world, f.frame.controllerPos());
            var registry = ChamberRegistryState.get(f.server).registry();
            var shell = f.frame.controllerPos().down(3);
            c.assertTrue(registry.records().get(chamber[0]).powerState() == ChamberPowerState.OFF
                    && ChamberProtectionService.get().mayMutate(world, shell), "前提：A 已斷電協調、平時可改");
            var root = new ChamberOriginAuthority(chamber[0], world.getRegistryKey().getValue(), DimensionRole.OVERWORLD,
                    f.frame.controllerPos(), f.frame.outwardFacing(), ChamberInstanceKind.ORIGIN);
            c.assertTrue(gate().claimProvisional(f.server, chamber[0], sid), "B 回程暫占");
            c.assertTrue(!ChamberProtectionService.get().mayMutate(world, shell), "PROVISIONAL_PROTECTS_ROOT：暫占起 A 受保護");
            var lifecycle = new ChamberLifecycleService(registry);
            boolean removed = lifecycle.dismantleOrigin(root, () -> ChamberProtectionService.get().authorizedMutation(world, f.frame.controllerPos(),
                    () -> world.removeBlock(f.frame.controllerPos(), false)));
            c.assertTrue(!removed && world.getBlockState(f.frame.controllerPos()).isOf(dev.quantumchamber.registry.ModBlocks.CHAMBER_CONTROLLER)
                    && registry.records().containsKey(chamber[0]), "BUSY_REMOVAL：回程期間一般拆除被拒且 Controller 保留");
            c.assertTrue(lifecycle.setOriginEnabled(root, false), "管理員停用仍可");
            c.assertTrue(lifecycle.setOriginEnabled(root, true), "沒有已選定旅程時復啟不需 latch");
            c.complete();
        } finally { cleanup.run(); }
    }

    /** B 門帶著封閉膜時，原艙停止的結案也必須真的改為一般開門，checkpoint 先於收據（M7 spec §5.3 :172–173、§9.2 DoorGoal=OPEN）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_sealed",tickLimit=600)
    public void sealed_door_closes_out_as_ordinary_open(TestContext c) {
        rootStopOpensMembraneDoor(c, 53, true);
    }

    /** B 門帶著可通行膜時同上。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_passable",tickLimit=600)
    public void passable_door_closes_out_as_ordinary_open(TestContext c) {
        rootStopOpensMembraneDoor(c, 52, false);
    }

    private static void rootStopOpensMembraneDoor(TestContext c, int slot, boolean sealed) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            var sid = UUID.randomUUID();
            var dormant = p.dormant(sid, slot);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var facing = p.frame.outwardFacing();
            var door = sealed ? ChamberDoorService.DoorState.sealed(facing) : ChamberDoorService.DoorState.passable(facing);
            c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, door).succeeded() && ChamberPowerCoordinator.doorHasMembrane(p.b, p.frame),
                    "B 的門帶著" + (sealed ? "封閉膜" : "可通行膜") + "：" + p.doorSummary());
            var store = p.receipts();
            var probe = M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 0, call -> {
                var row = journal.allFlushedSessions().get(sid);
                String close = row instanceof HomeSessionRecord h && h.home().closeIntent() instanceof Close k ? k.reason().name() : "NONE";
                boolean receipt;
                try { receipt = store.lookup(sid).isPresent(); } catch (java.io.IOException failure) { return "receipt=ERROR"; }
                return "door=" + p.doorSummary() + " close=" + close + " receipt=" + receipt;
            }, hooks);
            long now = server.getOverworld().getTime();
            c.assertTrue(journal.commitHomeRollForward(server, dormant, withStop(dormant, RootReason.ROOT_DISABLED, now)).committed(), "原艙停止原因已 checked latch");
            until(c, 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                try {
                    c.assertTrue("OPEN/NONE".equals(p.doorSummary()), "MEMBRANE_DOOR_KEPT：帶膜的 B 門結案後必須是一般開門：" + p.doorSummary());
                    var calls = probe.calls();
                    c.assertTrue(!calls.isEmpty() && calls.getFirst().chunks().containsAll(p.doorChunks()), "移除前必須涵蓋 B 門的原生 checkpoint：" + calls);
                    c.assertTrue("door=OPEN/NONE close=ROOT_DISABLED receipt=false".equals(calls.getFirst().observed()),
                            "先凍結 CLOSE 並改門，checkpoint 之後才發收據：" + calls.getFirst().observed());
                    var receipt = store.lookup(sid).orElseThrow();
                    c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED && k.doorGoal() == DoorGoal.OPEN,
                            "收據保留原艙停止原因：" + receipt.home().closeIntent());
                    c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                    c.complete();
                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                finally { p.close(); hooks.release(); }
            }, () -> {
                try { throw new GameTestException("MEMBRANE_DOOR_NOT_CLOSED_OUT door=" + p.doorSummary() + " row=" + journal.allSessions().get(sid)
                        + " checkpoints=" + probe.calls()); }
                finally { p.close(); hooks.release(); }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** READY 期間參與者在 exact B 關門而門口有人：CLOSE_IF_CLEAR_ELSE_OPEN 防夾，改為一般開門；Evidence 是實際 actor／PASSAGE／當下時間（M7 spec §5.3 :173、§9.2）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_pinch",tickLimit=600)
    public void participant_close_keeps_door_open_when_doorway_occupied(TestContext c) {
        participantCloses(c, 51, true);
    }

    /** PREPARE（COLLAPSING）期間參與者在 exact B 關門而門口淨空：CLOSE_IF_CLEAR_ELSE_OPEN 改為關門。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_clear",tickLimit=600)
    public void participant_close_closes_door_when_doorway_clear(TestContext c) {
        participantCloses(c, 50, false);
    }

    private static void participantCloses(TestContext c, int slot, boolean passage) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var sid = UUID.randomUUID();
            var dormant = p.dormant(sid, slot);
            var h = dormant.home();
            long time = server.getOverworld().getTime();
            var collapse = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), SessionState.COLLAPSING,
                    new Home(h.selection(), time, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
            c.assertTrue(journal.commitHomeRestore(server, dormant, collapse).committed(), "checked COLLAPSING");
            var row = collapse;
            if (passage) {
                row = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), SessionState.PASSAGE_READY,
                        new Home(h.selection(), time, time, time + 1200, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
                c.assertTrue(journal.commitHomeRestore(server, collapse, row).committed(), "checked READY");
            }
            c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.passable(p.frame.outwardFacing())).succeeded(),
                    "B 的門是可通行膜");
            var actor = p.f.players.getFirst().player();
            // 門口有人：站在門的中央那一格；淨空：站在艙內中央。
            var spot = Vec3d.ofBottomCenter(ChamberGeometry.localToWorld(p.frame, 3, 1, passage ? 0 : 3));
            c.assertTrue(actor.teleport(p.b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && actor.getServerWorld() == p.b, "參與者在 exact B");
            long now = server.getOverworld().getTime();
            var phase = passage ? HomeClosure.DoorPhase.PASSAGE : HomeClosure.DoorPhase.PREPARE;
            var current = row;
            c.assertTrue(HomeClosure.onParticipantClose(server, current, actor, passage ? HomeClosure.DoorPhase.PREPARE : HomeClosure.DoorPhase.PASSAGE, now)
                    == ChamberCollapsePort.DoorUse.REFUSED && current.equals(journal.allFlushedSessions().get(sid)), "錯誤 phase 不得 mint");
            if (passage) {
                HomeClosure.onEntryStopped(server, current, p.b, HomeClosure.EntrySignal.DISABLED, now);
                c.assertTrue(current.equals(journal.allFlushedSessions().get(sid)) && current.equals(journal.allSessions().get(sid)),
                        "ENTRY_DISABLED_REJECTED：B 端的 DISABLED 訊號一律不 mint（使用者 2026-11-17 裁示）");
            }
            c.assertTrue(HomeClosure.onParticipantClose(server, current, actor, phase, now) == ChamberCollapsePort.DoorUse.HANDLED, "參與者關門由回程終局 owner 處置");
            var store = p.receipts();
            String expected = passage ? "OPEN/NONE" : "CLOSED/NONE";
            until(c, 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                try {
                    c.assertTrue(expected.equals(p.doorSummary()), (passage ? "PINCH_DOOR_CLOSED：門口有人時結案必須開門：" : "CLEAR_DOOR_NOT_CLOSED：門口淨空時結案必須關門：")
                            + p.doorSummary());
                    var receipt = store.lookup(sid).orElseThrow();
                    c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.PARTICIPANT_CLOSED
                            && k.doorGoal() == DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN && k.closedAtGameTime() == now
                            && k.evidence().equals(new ParticipantClosed(actor.getUuid(), HomeSessionRecord.Phase.valueOf(phase.name()), now)),
                            "收據保留實際 actor、phase 與時間：" + receipt.home().closeIntent());
                    c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                    c.complete();
                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                finally { p.close(); hooks.release(); }
            }, () -> {
                try { throw new GameTestException("PARTICIPANT_CLOSE_NOT_CLOSED_OUT door=" + p.doorSummary() + " row=" + journal.allSessions().get(sid)); }
                finally { p.close(); hooks.release(); }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** journal 前一筆寫入尚未確認時，原艙停用先排隊：不回報成功、拒絕同根復啟；前一筆確認後以第一個事件的原因與時間 checked latch（M7 spec §9.1 :245）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_queue",tickLimit=600)
    public void root_event_queues_behind_unconfirmed_journal(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var registry = ChamberRegistryState.get(server).registry();
            var lifecycle = new ChamberLifecycleService(registry);
            var sid = UUID.randomUUID();
            var settled = p.settled(sid, 54);
            var dormant = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, settled.participants(), List.of(), SessionState.MEASURED, settled.home());
            boolean[] fail = {false};
            M5GameTestSupport.installFlushProbe(c, state -> { if (fail[0]) throw new IllegalStateException("M7_FIX1_JOURNAL_FLUSH_FAULT"); }, hooks);
            long eventAt = server.getOverworld().getTime();
            fail[0] = true;
            try {
                c.assertTrue(journal.commitHomeRollForward(server, settled, dormant).status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING
                        && journal.rollForwardPending(sid), "前一筆寫入尚未確認");
                c.assertTrue(!lifecycle.setEnabled(p.chamber, false), "QUEUE_ACK：journal 未收斂時停用不得回報成功");
                c.assertTrue(!registry.records().get(p.chamber).enabled() && dormant.equals(journal.allSessions().get(sid)), "registry 已落停用，本趟尚未寫停止原因");
                HomeRootFence.onRootEvent(server, p.root, HomeEndpointAuthority.RootStatus.INVALID_STRUCTURE, eventAt + 1);
                c.assertTrue(!HomeRootFence.permitsEnable(server, p.chamber) && !lifecycle.setOriginEnabled(p.root, true)
                        && !registry.records().get(p.chamber).enabled(), "QUEUE_ENABLE：排隊期間拒絕同根復啟");
            } finally { fail[0] = false; }
            // 前一筆由下一次 journal 寫出帶出（autosave 或任何 checked 寫出都會）；這裡直接補一次寫出。
            journal.flush(server);
            c.assertTrue(dormant.equals(journal.allFlushedSessions().get(sid)) && !journal.rollForwardPending(sid), "前一筆已 checked");
            var store = p.receipts();
            until(c, 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                try {
                    var receipt = store.lookup(sid).orElseThrow();
                    c.assertTrue(receipt.home().stopReason().equals(Optional.of(RootReason.ROOT_DISABLED)) && receipt.home().stoppedAtGameTime() == eventAt
                            && receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                            && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, eventAt)),
                            "QUEUE_FIRST_WINS：排隊的第一個事件以原原因與時間 latch：" + receipt.home());
                    c.assertTrue(!gate().globalChamberBusy(server, p.chamber) && HomeRootFence.permitsEnable(server, p.chamber)
                            && lifecycle.setOriginEnabled(p.root, true) && registry.records().get(p.chamber).enabled(), "收尾後放行復啟");
                    c.complete();
                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                finally { p.close(); hooks.release(); }
            }, () -> {
                try { throw new GameTestException("QUEUED_LATCH_NOT_CLOSED_OUT row=" + journal.allSessions().get(sid) + " flushed=" + journal.allFlushedSessions().get(sid)); }
                finally { p.close(); hooks.release(); }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 防線重建失敗（journal 不健康）時維持拒絕原艙復啟，重建成功後才放行（M7 spec §9.1 :246–247）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_bootstrap",tickLimit=100)
    public void fence_init_failure_keeps_enable_blocked(TestContext c) {
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        try {
            var world = c.getWorld();
            c.assertTrue(new ChamberActivationService().attemptArm(world, f.controller()).accepted(), "正式 A 登記");
            var chamber = f.controller().chamberUuid();
            var registry = ChamberRegistryState.get(f.server).registry();
            var lifecycle = new ChamberLifecycleService(registry);
            var root = new ChamberOriginAuthority(chamber, world.getRegistryKey().getValue(), DimensionRole.OVERWORLD,
                    f.frame.controllerPos(), f.frame.outwardFacing(), ChamberInstanceKind.ORIGIN);
            c.assertTrue(lifecycle.setOriginEnabled(root, false) && !registry.records().get(chamber).enabled(), "沒有旅程時停用直接成功");
            var journal = SessionRecoveryState.get(f.server);
            var previous = M4CandidateTestAccess.get(journal, "loadError");
            try {
                M4CandidateTestAccess.set(journal, "loadError", "M7_FIX1_FENCE_INIT_FAULT");
                HomeRootFence.bootstrap(f.server);
            } finally { M4CandidateTestAccess.set(journal, "loadError", previous); }
            try {
                c.assertTrue(!HomeRootFence.permitsEnable(f.server, chamber), "INIT_FAILURE_BLOCKS_ENABLE：防線重建失敗時維持拒絕");
                c.assertTrue(!lifecycle.setOriginEnabled(root, true) && !registry.records().get(chamber).enabled(), "INIT_FAILURE_BLOCKS_ENABLE：維護啟用被拒");
            } finally { HomeRootFence.bootstrap(f.server); }
            c.assertTrue(HomeRootFence.permitsEnable(f.server, chamber) && lifecycle.setOriginEnabled(root, true) && registry.records().get(chamber).enabled(),
                    "重建成功後放行啟用");
            c.complete();
        } finally { f.close(); }
    }

    private static boolean stopped(SessionRecoveryState journal, UUID sid, RootReason reason) {
        return journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid))
                && h.home().stopReason().equals(Optional.of(reason)) && !journal.rollForwardPending(sid);
    }

    static HomeSessionRecord withStop(HomeSessionRecord row, RootReason reason, long time) {
        var h = row.home();
        return new HomeSessionRecord(row.sessionUuid(), row.chamberUuid(), row.rootAnchor(), row.entryEndpoint(), row.participants(),
                row.spaceLeases(), row.state(), new Home(h.selection(), h.committedAtGameTime(), h.passageOpenedAtGameTime(),
                h.passageExpiresAtGameTime(), h.crossed(), h.recoveryRequired(), Optional.of(reason), time, h.closeIntent(), h.sealedReason()));
    }

    /** 以測試票保持 B 艙體外擴 4 格的 chunks 為 FULL（正式 B 來源票到 T5 才有），回傳釋放動作。 */
    static Runnable holdChunks(ServerWorld world, ChamberFrame frame) {
        var held = new LinkedHashSet<ChunkPos>();
        var bounds = ChamberGeometry.bounds(frame);
        for (int x = (bounds.getMinX() - 4) >> 4; x <= (bounds.getMaxX() + 4) >> 4; x++)
            for (int z = (bounds.getMinZ() - 4) >> 4; z <= (bounds.getMaxZ() + 4) >> 4; z++) {
                world.getChunk(x, z);
                var pos = new ChunkPos(x, z);
                world.getChunkManager().addTicket(HOLD, pos, 0, pos);
                held.add(pos);
            }
        return () -> { for (var pos : held) world.getChunkManager().removeTicket(HOLD, pos, 0, pos); };
    }

    /**
     * HOME fixture 的世界前提：正式流程中 HOME 列一定晚於至少一次 A 入場（B 投影來自先前的塌縮），所以第一次入場建立的 candidate discovery 與
     * entropy 檔必然已存在。fixture 直接寫 schema6 journal 時，journal 已有 candidate 證據、這兩個檔卻不存在；之後同一個 test server 的正式 A 入場
     * 會在嚴格載入時被拒、且不記 log（T4 fix1 實測：gate t4a-fix1-newgt-green／green2 的 Windows 入場兩案，世界 data 缺這兩個檔）。
     * 這裡以第一次入場相同的建立方式補建前提，不改任何正式判斷。
     */
    static void requireCandidateStores(MinecraftServer server) {
        var root = server.getSavePath(WorldSavePath.ROOT);
        try {
            dev.quantumchamber.candidate.UniverseDiscoveryState.loadOrCreate(root);
            dev.quantumchamber.candidate.CandidateEntropyState.loadOrCreate(root, false);
        } catch (java.io.IOException failure) { throw new GameTestException("HOME fixture 的 candidate 前提建立失敗：" + failure); }
    }

    /** 每 tick 檢查一次；{@code done} 成立時執行 {@code then}，用完 {@code remaining} 次仍不成立時執行 {@code timeout}。 */
    static void until(TestContext c, int remaining, BooleanSupplier done, Runnable then, Runnable timeout) {
        if (done.getAsBoolean()) { then.run(); return; }
        if (remaining <= 0) { timeout.run(); return; }
        c.waitAndRun(1, () -> {
            // 原版只把 GameTestException 記為測試失敗；其他例外會讓整個 test server 崩潰（T4a RED 實測）。
            try { until(c, remaining - 1, done, then, timeout); }
            catch (GameTestException failure) { throw failure; }
            catch (RuntimeException | Error failure) { throw new GameTestException("回呼內例外：" + failure); }
        });
    }

    /** A 原艙、同艙已確認 COMPLETE 的 B 投影，以及 B 區塊的測試票。 */
    static final class Projection implements AutoCloseable {
        final TestContext c;
        final M2CorridorGameTests.NativeEntry f;
        final ServerWorld b;
        final ChamberFrame frame;
        final UUID chamber;
        final EntryEndpoint entry;
        final ChamberOriginAuthority root;
        private Runnable release = () -> { };
        private boolean closed;

        Projection(TestContext c, M2CorridorGameTests.TestHooks hooks) {
            this.c = c;
            f = new M2CorridorGameTests.NativeEntry(c, 1);
            try {
                requireCandidateStores(f.server);
                var a = f.controller();
                c.assertTrue(new ChamberActivationService().attemptArm(c.getWorld(), a).accepted(), "正式 A 登記");
                chamber = a.chamberUuid();
                frame = new ChamberFrame(a.getPos(), a.getCachedState().get(ChamberControllerBlock.FACING));
                var id = UniverseId.of(UUID.randomUUID());
                b = M6ProfileProbePhases.fixtureSource(f.server, id, "normal");
                var authorities = CollapseAuthorities.get(f.server);
                var index = ProjectionProtectionIndex.get(f.server);
                var pending = authorities.writeProjection(new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, a.getPos(),
                        frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
                index.confirmed(pending);
                release = holdChunks(b, frame);
                c.assertTrue(ProjectionWriter.write(b, frame, ProjectionWriter.Mode.FIRST_WRITE).status() == ProjectionWriter.Status.WRITTEN, "正式投影寫入");
                ((ChamberControllerBlockEntity) b.getBlockEntity(a.getPos())).setInstanceKind(ChamberInstanceKind.PROJECTION);
                index.confirmed(authorities.writeProjection(pending.entry().orElseThrow().withStatus(ProjectionLedger.Entry.Status.COMPLETE)));
                entry = new EntryEndpoint(chamber, id, b.getRegistryKey().getValue(), DimensionRole.OVERWORLD, a.getPos(), frame.outwardFacing(),
                        ChamberInstanceKind.PROJECTION);
                root = new ChamberOriginAuthority(chamber, net.minecraft.world.World.OVERWORLD.getValue(), DimensionRole.OVERWORLD,
                        a.getPos(), frame.outwardFacing(), ChamberInstanceKind.ORIGIN);
                c.assertTrue(HomeEndpointAuthority.inspectEntry(f.server, entry) == HomeEndpointAuthority.EntryStatus.VALID, "B 是合法投影端");
            } catch (RuntimeException | Error failure) { close(); throw failure; }
        }

        /** 經正式 journal 入口走到已選定、全員返回、無租約的 MEASURED（HOME DORMANT）。 */
        HomeSessionRecord dormant(UUID sid, int slot) {
            var settled = settled(sid, slot);
            var dormant = new HomeSessionRecord(sid, chamber, root, entry, settled.participants(), List.of(), SessionState.MEASURED, settled.home());
            c.assertTrue(SessionRecoveryState.get(f.server).commitHomeRollForward(f.server, settled, dormant).committed(), "checked 清除租約");
            return dormant;
        }

        /** 經正式 journal 入口走到已選定、全員返回、租約仍在的 MEASURED（下一步才清除租約）。 */
        HomeSessionRecord settled(UUID sid, int slot) {
            var server = f.server;
            var journal = SessionRecoveryState.get(server);
            var actor = f.players.getFirst().player().getUuid();
            var person = new SessionRecoveryRecord.Participant(actor, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), false);
            int x = 610000 + slot * 1000;
            var lease = new SessionRecoveryRecord.SpaceLease(slot, new BlockBox(x, 64, 610000, x + 6, 70, 610006));
            var empty = new Home(new Unselected(), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var arming = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.ARMING, empty);
            c.assertTrue(gate().claimProvisional(server, chamber, sid), "先占後寫");
            c.assertTrue(journal.admitHome(server, arming).committed() && gate().finishProvisional(server, chamber, sid), "checked ARMING");
            var active = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.SUPERPOSITION, empty);
            c.assertTrue(journal.commitHomeRestore(server, arming, active).committed(), "checked SUPERPOSITION");
            long time = server.getOverworld().getTime();
            var selected = new Home(new Selected(new dev.quantumchamber.corridor.DoorKey(sid, 1, dev.quantumchamber.corridor.DoorKey.DoorWallSide.POSITIVE_LATERAL),
                    actor, time), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty());
            var measured = new HomeSessionRecord(sid, chamber, root, entry, List.of(person), List.of(lease), SessionState.MEASURED, selected);
            c.assertTrue(journal.commitHomeRestore(server, active, measured).committed(), "checked HOME 選擇");
            var returned = new SessionRecoveryRecord.Participant(actor, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), true);
            var settled = new HomeSessionRecord(sid, chamber, root, entry, List.of(returned), List.of(lease), SessionState.MEASURED, selected);
            c.assertTrue(journal.commitHomeRollForward(server, measured, settled).committed(), "checked 返回 B");
            return settled;
        }

        /** 模擬持久 registry 已改、沒有任何通知（例如重啟前的 disabled 已存檔）。 */
        @SuppressWarnings("unchecked")
        void rawEnabled(boolean enabled) {
            var registry = ChamberRegistryState.get(f.server).registry();
            var rows = (Map<UUID, ChamberRecord>) M4CandidateTestAccess.get(registry, "records");
            var r = rows.get(chamber);
            rows.put(chamber, new ChamberRecord(r.chamberUuid(), r.originWorldKey(), r.originDimensionRole(), r.anchorPos(), r.facing(),
                    r.instanceKind(), enabled, r.destroyed(), r.powerState()));
        }

        HomeReceiptStore receipts() {
            try { return new HomeReceiptStore(f.server.getSavePath(WorldSavePath.ROOT)); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
        }

        /** B 門 25 格的狀態：全部相同時是 {@code 開關/膜}，否則 {@code MIXED}。 */
        String doorSummary() {
            String seen = null;
            for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
                var state = b.getBlockState(ChamberGeometry.localToWorld(frame, x, y, 0));
                if (!state.isOf(dev.quantumchamber.registry.ModBlocks.QUANTUM_BULKHEAD)) return "NOT_BULKHEAD";
                var cell = (state.get(QuantumBulkheadBlock.OPEN) ? "OPEN" : "CLOSED") + "/" + QuantumBulkheadBlock.membrane(state).name();
                if (seen == null) seen = cell; else if (!seen.equals(cell)) return "MIXED";
            }
            return seen;
        }

        Set<ChunkPos> doorChunks() {
            var chunks = new HashSet<ChunkPos>();
            for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) chunks.add(new ChunkPos(ChamberGeometry.localToWorld(frame, x, y, 0)));
            return chunks;
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            try { release.run(); }
            finally { f.close(); }
        }
    }
}
