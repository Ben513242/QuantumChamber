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
    private static final org.slf4j.Logger WAIT_LOG = org.slf4j.LoggerFactory.getLogger("quantumchamber-testmod");

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
            whenEntryReady(c, p, "ordinary_closed_door_needs_native_checkpoint", 380, hooks, () -> {
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
                    until(c, "ordinary_closed_door_needs_native_checkpoint", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
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
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** 選後停用的直接 setter 與維護入口都同步 latch；同 tick 復啟仍保留 latch、不復活本趟（M7 spec §9.1）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_fence",tickLimit=900)
    public void root_disable_latches_before_ack_and_reenable(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "root_disable_latches_before_ack_and_reenable", 280, hooks, () -> {
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
                    until(c, "root_disable_latches_before_ack_and_reenable/first", 200, () -> !journal.allSessions().containsKey(first), () -> {
                        try {
                            // 註冊已落 disabled、本趟停止尚未落盤：先 enable 也只能在 latch 之後生效。
                            var second = UUID.randomUUID();
                            p.dormant(second, 57);
                            p.rawEnabled(false);
                            c.assertTrue(lifecycle.setEnabled(p.chamber, true), "latch 完成後才放行 enable");
                            c.assertTrue(stopped(journal, second, RootReason.ROOT_DISABLED), "ENABLE_BEFORE_LATCH：enable 不得跳過尚未落盤的停止");
                            c.assertTrue(ChamberRegistryState.get(server).registry().records().get(p.chamber).enabled(), "registry 已復啟");
                            until(c, "root_disable_latches_before_ack_and_reenable/second", 200, () -> !journal.allSessions().containsKey(second), () -> {
                                try {
                                    // 開服 fence 掃描：只有持久 registry 的停用，沒有任何通知（含沒有 runtime 的 DORMANT）。
                                    var third = UUID.randomUUID();
                                    p.dormant(third, 56);
                                    p.rawEnabled(false);
                                    HomeRootFence.bootstrap(server);
                                    c.assertTrue(stopped(journal, third, RootReason.ROOT_DISABLED), "BOOTSTRAP_SCAN_DORMANT：開服掃描必須 latch 無 runtime 的 DORMANT");
                                    p.rawEnabled(true);
                                    until(c, "root_disable_latches_before_ack_and_reenable/third", 200, () -> !journal.allSessions().containsKey(third), () -> {
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
            });
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
            whenEntryReady(c, p, sealed ? "sealed_door_closes_out_as_ordinary_open" : "passable_door_closes_out_as_ordinary_open", 380, hooks, () -> {
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
                    until(c, sealed ? "sealed_door_closes_out_as_ordinary_open" : "passable_door_closes_out_as_ordinary_open", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
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
            whenEntryReady(c, p, passage ? "participant_close_keeps_door_open_when_doorway_occupied" : "participant_close_closes_door_when_doorway_clear", 380, hooks, () -> {
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
                    var writes = guardWitness(c, p, sid, hooks);
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
                    until(c, passage ? "participant_close_keeps_door_open_when_doorway_occupied" : "participant_close_closes_door_when_doorway_clear", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                        try {
                            c.assertTrue(expected.equals(p.doorSummary()), (passage ? "PINCH_DOOR_CLOSED：門口有人時結案必須開門：" : "CLEAR_DOOR_NOT_CLOSED：門口淨空時結案必須關門：")
                                    + p.doorSummary());
                            var receipt = store.lookup(sid).orElseThrow();
                            c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.PARTICIPANT_CLOSED
                                    && k.doorGoal() == DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN && k.closedAtGameTime() == now
                                    && k.evidence().equals(new ParticipantClosed(actor.getUuid(), HomeSessionRecord.Phase.valueOf(phase.name()), now)),
                                    "收據保留實際 actor、phase 與時間：" + receipt.home().closeIntent());
                            c.assertTrue(writes.size() >= 3 && writes.stream().allMatch(write -> write.endsWith("guard=true")),
                                    "GUARD_WRITE_OUTSIDE：參與者關門的結案寫出與 B 門 checkpoint 都必須在 session operation guard 內：" + writes);
                            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                            c.complete();
                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                        finally { p.close(); hooks.release(); }
                    }, () -> {
                        try { throw new GameTestException("PARTICIPANT_CLOSE_NOT_CLOSED_OUT door=" + p.doorSummary() + " row=" + journal.allSessions().get(sid)
                                + " thresholds=" + closureThresholds(p, sid)); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /** journal 前一筆寫入尚未確認時，原艙停用先排隊：不回報成功、拒絕同根復啟；前一筆確認後以第一個事件的原因與時間 checked latch（M7 spec §9.1 :245）。 */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_queue",tickLimit=600)
    public void root_event_queues_behind_unconfirmed_journal(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "root_event_queues_behind_unconfirmed_journal", 360, hooks, () -> {
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
                    // 任何路徑收尾都先解除故障並補一次寫出，不把本 fixture 未確認的寫入留給之後的 test（mut16 實測：中途失敗時之後兩案 admitHome 被拒）。
                    // 這次寫出只在判定之後的收尾執行，不代替下面要驗的 production 重試。
                    hooks.add(() -> { fail[0] = false; journal.flush(server); });
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
                    // 審查 I-1：前一筆只由 production 的唯一重試者（SessionRecoveryManager 的 HOME 分支，沿用同一個 journal 退避）收斂，測試不手動寫出。
                    // 故障在同一個 tick 內就已解除，第一次重試沒有退避，所以有界等待 20 ticks。重試寫出確認後，同一個 END_SERVER_TICK 內防線就 latch、終局 owner 就結案
                    // 並移除熱列（fix2 green 實測），所以只等「roll-forward 已確認」，不等中間狀態；之後的結果由收據驗。
                    until(c, "root_event_queues_behind_unconfirmed_journal/retry", 20, () -> !journal.rollForwardPending(sid), () -> {
                        try {
                            var store = p.receipts();
                            until(c, "root_event_queues_behind_unconfirmed_journal/closeout", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
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
                    }, () -> {
                        try { throw new GameTestException("HOME_ROLL_FORWARD_NOT_RETRIED：HOME roll-forward 寫出失敗後，production 沒有在有界時間內重試收斂：current="
                                + journal.allSessions().get(sid) + " flushed=" + journal.allFlushedSessions().get(sid) + " pending=" + journal.rollForwardPending(sid)); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
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

    /**
     * 回程的寫入都在 session operation guard 內（M7 spec §9.1 :249）：同 SID 已有操作時，原艙停用只排隊、不回報成功、拒絕同根復啟；guard 釋放後由
     * 防線與終局 owner 在 guard 內 latch、結案與移除，journal 寫出與 B 門 checkpoint 當下都持有 guard。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_guard",tickLimit=600)
    public void home_writes_hold_session_guard(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "home_writes_hold_session_guard", 380, hooks, () -> {
                try {
                    var server = p.f.server;
                    var journal = SessionRecoveryState.get(server);
                    var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
                    var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
                    var sid = UUID.randomUUID();
                    p.dormant(sid, 49);
                    var writes = guardWitness(c, p, sid, hooks);
                    long eventAt = server.getOverworld().getTime();
                    boolean[] acked = {true}, reenabled = {true};
                    pages.exclusiveOperation(sid, () -> {
                        acked[0] = lifecycle.setEnabled(p.chamber, false);
                        reenabled[0] = lifecycle.setEnabled(p.chamber, true);
                        return null;
                    });
                    c.assertTrue(!acked[0] && ((HomeSessionRecord) journal.allSessions().get(sid)).home().stopReason().isEmpty() && writes.isEmpty(),
                            "GUARD_EVENT_NOT_QUEUED：同 SID 的操作進行中時，原艙停用只能排隊、不在 guard 外寫入、不回報成功：acked=" + acked[0] + " writes=" + writes);
                    c.assertTrue(!reenabled[0] && !ChamberRegistryState.get(server).registry().records().get(p.chamber).enabled(), "GUARD_ENABLE_BLOCKED：排隊期間拒絕同根復啟");
                    var store = p.receipts();
                    until(c, "home_writes_hold_session_guard", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                        try {
                            var receipt = store.lookup(sid).orElseThrow();
                            c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                                    && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, eventAt)), "guard 釋放後以排隊事件的原因與時間結案：" + receipt.home());
                            c.assertTrue(writes.size() >= 4 && writes.stream().allMatch(write -> write.endsWith("guard=true")),
                                    "GUARD_WRITE_OUTSIDE：原艙 latch、結案、移除的 journal 寫出與 B 門 checkpoint 都必須在 session operation guard 內：" + writes);
                            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                            c.complete();
                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                        finally { p.close(); hooks.release(); }
                    }, () -> {
                        try { throw new GameTestException("GUARDED_CLOSE_NOT_FINISHED row=" + journal.allSessions().get(sid) + " writes=" + writes); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * 開服掃描完成前，原艙停用防線的 port 已 fail-closed（M7 spec §9.1 :246）：{@link M7HomeFenceStartupProbe} 在 SERVER_STARTED 的預設 phase 之前
     * （世界已載入、HomeRootFence 的開服掃描尚未執行）以 ChamberRegistry.setEnabled 用的同一個 port 查詢；掃描完成後同一個查詢才放行。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_startup",tickLimit=100)
    public void fence_rejects_enable_before_startup_scan(TestContext c) {
        var server = c.getWorld().getServer();
        var observed = M7HomeFenceStartupProbe.observed(server);
        c.assertTrue(observed.equals(Optional.of("REJECTED")), "STARTING_FENCE：開服掃描完成前 port 必須拒絕 enable：" + observed);
        c.assertTrue(ChamberCollapsePort.roots().permitsEnable(ChamberRegistryState.get(server).registry(), M7HomeFenceStartupProbe.PROBE_CHAMBER),
                "開服掃描完成後同一個查詢放行");
        c.complete();
    }

    /**
     * T4 Phase B0 N1：呼叫端自己持有同 SID 的 session operation guard 時，參與者關門與 B 停止訊號都拒絕、不寫入（M7 spec §9.1 :249），並各記一則
     * WARN（同一 SID、同一入口只記一次；WARN 以 GREEN log 為證，這裡不斷言 log）。B 斷電，所以沒有 guard 時同一個 LOW 訊號會 mint ENTRY_STOPPED；
     * 兩個入口交錯各呼叫兩次。guard 釋放後同一個參與者關門照常結案。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_guard_refused",tickLimit=600)
    public void caller_held_guard_refuses_participant_close_and_entry_stop(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "caller_held_guard_refuses_participant_close_and_entry_stop", 380, hooks, () -> {
                try {
                    var server = p.f.server;
                    var journal = SessionRecoveryState.get(server);
                    var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
                    var sid = UUID.randomUUID();
                    var dormant = p.dormant(sid, 45);
                    var h = dormant.home();
                    long time = server.getOverworld().getTime();
                    var collapse = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), SessionState.COLLAPSING,
                            new Home(h.selection(), time, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
                    c.assertTrue(journal.commitHomeRestore(server, dormant, collapse).committed(), "checked COLLAPSING");
                    var ready = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, dormant.participants(), List.of(), SessionState.PASSAGE_READY,
                            new Home(h.selection(), time, time, time + 1200, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
                    c.assertTrue(journal.commitHomeRestore(server, collapse, ready).committed(), "checked READY");
                    c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.passable(p.frame.outwardFacing())).succeeded(),
                            "B 的門是可通行膜");
                    c.assertTrue(!p.b.isReceivingRedstonePower(p.frame.controllerPos()), "前提：B 斷電，沒有 guard 時 LOW 訊號成立");
                    var writes = guardWitness(c, p, sid, hooks);
                    var actor = p.f.players.getFirst().player();
                    var spot = Vec3d.ofBottomCenter(ChamberGeometry.localToWorld(p.frame, 3, 1, 3));
                    c.assertTrue(actor.teleport(p.b, spot.x, spot.y, spot.z, Set.of(), 0, 0) && actor.getServerWorld() == p.b, "參與者在 exact B");
                    long now = server.getOverworld().getTime();
                    BooleanSupplier unchanged = () -> ready.equals(journal.allSessions().get(sid)) && ready.equals(journal.allFlushedSessions().get(sid))
                            && !journal.rollForwardPending(sid);
                    // 依呼叫順序記錄每次呼叫的結果與之後 journal 是否改變；失敗訊息以第一個偏差的呼叫命名（後面的呼叫看到的改變可能是前一個呼叫寫的）。
                    var calls = new ArrayList<String>();
                    pages.exclusiveOperation(sid, () -> {
                        for (int round = 1; round <= 2; round++) {
                            try {
                                var use = HomeClosure.onParticipantClose(server, ready, actor, HomeClosure.DoorPhase.PASSAGE, now);
                                calls.add("participant#" + round + "=" + use + (unchanged.getAsBoolean() ? "" : "/WRITTEN"));
                            } catch (RuntimeException failure) { calls.add("participant#" + round + "=THREW:" + failure); }
                            try {
                                HomeClosure.onEntryStopped(server, ready, p.b, HomeClosure.EntrySignal.LOW, now);
                                calls.add("entry#" + round + (unchanged.getAsBoolean() ? "" : "=WRITTEN"));
                            } catch (RuntimeException failure) { calls.add("entry#" + round + "=THREW:" + failure); }
                        }
                        return null;
                    });
                    var expectedCalls = List.of("participant#1=REFUSED", "entry#1", "participant#2=REFUSED", "entry#2");
                    int first = 0;
                    while (first < calls.size() && first < expectedCalls.size() && calls.get(first).equals(expectedCalls.get(first))) first++;
                    boolean entryFirst = first < calls.size() && calls.get(first).startsWith("entry");
                    c.assertTrue(calls.equals(expectedCalls), (entryFirst
                            ? "GUARD_HELD_ENTRY_WROTE：呼叫端持有同 SID 的 guard 時，B 停止訊號不得 mint、journal 不變（第一個偏差在 B 停止訊號）："
                            : "GUARD_HELD_PARTICIPANT_WROTE：呼叫端持有同 SID 的 guard 時，參與者關門必須回 REFUSED、journal 不變（第一個偏差在參與者關門）：")
                            + calls);
                    c.assertTrue(writes.isEmpty(), "GUARD_HELD_WRITES：持有 guard 期間不得有 journal 寫出或 B 門 checkpoint：" + writes);
                    c.assertTrue(HomeClosure.onParticipantClose(server, ready, actor, HomeClosure.DoorPhase.PASSAGE, server.getOverworld().getTime())
                            == ChamberCollapsePort.DoorUse.HANDLED, "guard 釋放後同一個參與者關門照常處置");
                    var store = p.receipts();
                    until(c, "caller_held_guard_refuses_participant_close_and_entry_stop", 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                        try {
                            var receipt = store.lookup(sid).orElseThrow();
                            c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.PARTICIPANT_CLOSED
                                    && k.evidence().equals(new ParticipantClosed(actor.getUuid(), HomeSessionRecord.Phase.PASSAGE, now)),
                                    "guard 釋放後以參與者關門結案：" + receipt.home().closeIntent());
                            c.assertTrue(!writes.isEmpty() && writes.stream().allMatch(write -> write.endsWith("guard=true")),
                                    "GUARD_WRITE_OUTSIDE：guard 釋放後的結案寫出與 B 門 checkpoint 都必須在 session operation guard 內：" + writes);
                            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                            c.complete();
                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                        finally { p.close(); hooks.release(); }
                    }, () -> {
                        try { throw new GameTestException("GUARD_RELEASED_CLOSE_NOT_FINISHED row=" + journal.allSessions().get(sid) + " writes=" + writes
                                + " thresholds=" + closureThresholds(p, sid)); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 Phase B0 N2：HOME 列 roll-forward 的寫出持續失敗時，唯一重試者 SessionRecoveryManager 沿用 journal 退避（M7 spec §6 :186）。寫入當下 1 次、
     * 同一個 tick 的重試 1 次都失敗；之後 200 ticks 內不再寫出，第 200 個 tick 才重試（仍失敗）；下一次間隔是 400（每個失敗的 tick 只算一次）。
     * 原生 autosave 不經過 flush 探針，所以先等到接下來 800 個 tick 內不會有 autosave，確認時稽核窗口內每一次寫出都經過探針、只帶本案的 session。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_backoff",tickLimit=2000)
    public void home_roll_forward_retry_follows_journal_backoff(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            hooks.add(p::close);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var sid = UUID.randomUUID();
            M5GameTestSupport.afterAutosaveClear(c, 1, "m7_home_retry_backoff", 800, (start, autosave) -> {
                var settled = p.settled(sid, 47);
                var dormant = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, settled.participants(), List.of(), SessionState.MEASURED, settled.home());
                var fault = new M5GameTestSupport.FlushFault(server, "T4b0 GameTest 受控：HOME roll-forward 的 journal 寫出持續失敗", Integer.MAX_VALUE,
                        state -> !Objects.equals(state.allSessions().get(sid), state.allFlushedSessions().get(sid)));
                M5GameTestSupport.installFlushProbe(c, fault, hooks);
                // 任何路徑收尾都先解除故障並帶出尚未確認的寫出，不把全域 pending 留給之後的 test（hooks 後進先出）；只在判定之後執行。
                hooks.add(() -> { fault.disarm(); if (journal.rollForwardPending(sid)) journal.flush(server); });
                c.assertTrue(journal.commitHomeRollForward(server, settled, dormant).status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING
                        && journal.rollForwardPending(sid), "前提：HOME roll-forward 的寫出受控失敗、待確認");
                // 第三次寫出（第一次失敗後的第一次重試）之後才解除故障，讓下一次重試確認。
                M5GameTestSupport.whenTicks(c, start + 1, 400, () -> fault.ticks().size() >= 3, retried -> {
                    fault.disarm();
                    M5GameTestSupport.whenTicks(c, retried + 1, 800, () -> !journal.rollForwardPending(sid), confirmed -> {
                        var attempts = fault.ticks();
                        var journalWrites = autosave.journalWrites(server, attempts, Set.of(sid));
                        c.assertTrue(journalWrites.onlyProbed(), "窗口內每一次 journal 寫出都經過探針、只帶本案的 session：" + journalWrites.problems());
                        c.assertTrue(attempts.size() == 4 && attempts.stream().filter(attempts.getFirst()::equals).count() == 2,
                                "HOME_RETRY_ATTEMPTS：寫入當下與同一個 tick 的重試各一次，之後恰好兩次重試：" + attempts);
                        c.assertTrue(attempts.get(2) - attempts.get(1) == CollapseBackoff.INITIAL_TICKS,
                                "HOME_RETRY_IGNORED_BACKOFF：第一次失敗後 200 ticks 內不得再寫出，第 200 個 tick 才重試：" + attempts);
                        c.assertTrue(attempts.get(3) - attempts.get(2) == 2 * CollapseBackoff.INITIAL_TICKS,
                                "HOME_RETRY_DOUBLE_COUNTED：每個失敗的 tick 只算一次，下一次退避是 400：" + attempts);
                        closeOutDormant(c, p, dormant, confirmed, hooks);
                    }, () -> "HOME roll-forward 的重試沒有確認：attempts=" + fault.ticks() + " pending=" + journal.rollForwardPending(sid), hooks);
                }, () -> "HOME roll-forward 沒有重試：attempts=" + fault.ticks() + " pending=" + journal.rollForwardPending(sid), hooks);
            }, hooks);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 Phase B0 N2：legacy 列與 HOME 列在同一個 tick 都寫出失敗時，journal 退避只算一次失敗（M7 spec §6 :186）。legacy COLLAPSING 進入 SEALED
     * 與 HOME roll-forward 在同一個 callback 內都受控失敗；同一個 tick 的 recovery 只重試一次（legacy 的 RETRY_FLUSH 失敗後 HOME 分支不再寫）；
     * 之後 200 ticks 內不再寫出，第 200 個 tick 重試（仍失敗），下一次退避是 400，不是 800。legacy fixture 暫停塌縮流程，收尾以終局 API 移除。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_backoff_shared",tickLimit=2000)
    public void legacy_and_home_failing_in_one_tick_count_once(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            hooks.add(p::close);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var sid = UUID.randomUUID();
            var owned = new ArrayList<UUID>();
            hooks.add(() -> M5GameTestSupport.cleanupFixtures(server, owned));
            var far = c.getAbsolutePos(net.minecraft.util.math.BlockPos.ORIGIN).add(6000, 70, 6000);
            M5GameTestSupport.afterAutosaveClear(c, 1, "m7_home_legacy_same_tick", 800, (start, autosave) -> {
                var legacy = M5GameTestSupport.collapseFixtureOwnedBy(server, M4CandidateTestAccess.origin(UUID.randomUUID(), far),
                        Map.of(UUID.randomUUID(), true), List.of(), true, SessionState.COLLAPSING, true, owned);
                var legacySid = legacy.sessionUuid();
                var sealed = legacy.withCollapse(SessionState.SEALED, legacy.collapse().sealed(SealedReason.PROJECTION_FAILED));
                var settled = p.settled(sid, 46);
                var dormant = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, settled.participants(), List.of(), SessionState.MEASURED, settled.home());
                var fault = new M5GameTestSupport.FlushFault(server, "T4b0 GameTest 受控：legacy 與 HOME 的 journal 寫出持續失敗", Integer.MAX_VALUE,
                        state -> !Objects.equals(state.allSessions().get(sid), state.allFlushedSessions().get(sid))
                                || !Objects.equals(state.allSessions().get(legacySid), state.allFlushedSessions().get(legacySid)));
                M5GameTestSupport.installFlushProbe(c, fault, hooks);
                hooks.add(() -> { fault.disarm(); if (journal.rollForwardPending(sid) || journal.rollForwardPending(legacySid)) journal.flush(server); });
                c.assertTrue(journal.commitRollForward(server, legacy, sealed).status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING
                        && journal.rollForwardPending(legacySid), "前提：legacy 進入 SEALED 的寫出受控失敗、待確認");
                c.assertTrue(journal.commitHomeRollForward(server, settled, dormant).status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING
                        && journal.rollForwardPending(sid), "前提：同一個 tick 的 HOME roll-forward 寫出也受控失敗、待確認");
                M5GameTestSupport.whenTicks(c, start + 1, 400, () -> fault.ticks().size() >= 4, retried -> {
                    fault.disarm();
                    M5GameTestSupport.whenTicks(c, retried + 1, 800, () -> !journal.rollForwardPending(sid) && !journal.rollForwardPending(legacySid), confirmed -> {
                        var attempts = fault.ticks();
                        var journalWrites = autosave.journalWrites(server, attempts, Set.of(sid, legacySid));
                        c.assertTrue(journalWrites.onlyProbed(), "窗口內每一次 journal 寫出都經過探針、只帶本案的 session：" + journalWrites.problems());
                        c.assertTrue(attempts.size() == 5 && attempts.stream().filter(attempts.getFirst()::equals).count() == 3,
                                "SAME_TICK_SINGLE_RETRY：兩個寫入當下各一次，同一個 tick 的 recovery 只重試一次，之後恰好兩次重試：" + attempts);
                        c.assertTrue(attempts.get(3) - attempts.get(2) == CollapseBackoff.INITIAL_TICKS,
                                "SAME_TICK_IGNORED_BACKOFF：第一次失敗後 200 ticks 內不得再寫出，第 200 個 tick 才重試：" + attempts);
                        c.assertTrue(attempts.get(4) - attempts.get(3) == 2 * CollapseBackoff.INITIAL_TICKS,
                                "SAME_TICK_DOUBLE_COUNTED：legacy 與 HOME 同一個 tick 失敗只算一次，下一次退避是 400、不是 800：" + attempts);
                        c.assertTrue(sealed.equals(journal.flushedRecords().get(legacySid)) && dormant.equals(journal.allFlushedSessions().get(sid)),
                                "同一次重試確認 legacy 與 HOME 兩列");
                        closeOutDormant(c, p, dormant, confirmed, hooks);
                    }, () -> "重試沒有確認：attempts=" + fault.ticks() + " home=" + journal.rollForwardPending(sid) + " legacy=" + journal.rollForwardPending(legacySid),
                            hooks);
                }, () -> "沒有重試：attempts=" + fault.ticks(), hooks);
            }, hooks);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 Phase B2a：checkpoint 結果未知（M7 spec §5.3 :173「checkpoint未知皆pending，不修A、不假確認」、plan Task 4 :265）。B 門已改成結案目標之後，第一次原生
     * checkpoint 受控失敗：不得發收據、保留已凍結的 CLOSE 與占用；沿 owner 的退避（200 ticks）重做門與 checkpoint，成功之後才發收據、移除熱列。
     * 單一 JVM 即可證明：故障與重試都在同一個 server 的 converge 內；crash 落在 checkpoint 前後的跨 JVM 行為由 B1 的 before-checkpoint／after-checkpoint 列涵蓋。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_checkpoint_unknown",tickLimit=1000)
    public void checkpoint_failure_keeps_close_and_lock(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "checkpoint_failure_keeps_close_and_lock", 380, hooks, () -> {
                try {
                    var sid = UUID.randomUUID();
                    var dormant = p.dormant(sid, 44);
                    var server = p.f.server;
                    var journal = SessionRecoveryState.get(server);
                    c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 的門是普通關門");
                    var store = p.receipts();
                    var probe = M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 1, call -> {
                        boolean receipt;
                        try { receipt = store.lookup(sid).isPresent(); } catch (java.io.IOException failure) { return "receipt=ERROR"; }
                        return "door=" + p.doorSummary() + " receipt=" + receipt;
                    }, hooks);
                    long now = server.getOverworld().getTime();
                    c.assertTrue(journal.commitHomeRollForward(server, dormant, withStop(dormant, RootReason.ROOT_DISABLED, now)).committed(), "原艙停止原因已 checked latch");
                    until(c, "checkpoint_failure_keeps_close_and_lock/failed", 200, () -> !probe.calls().isEmpty(), () -> {
                        try {
                            var first = probe.calls().getFirst();
                            c.assertTrue(first.failed() && "door=OPEN/NONE receipt=false".equals(first.observed()), "前提：門改好之後第一次 checkpoint 受控失敗：" + first);
                            var row = journal.allFlushedSessions().get(sid);
                            c.assertTrue(row instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.home().closeIntent() instanceof Close k
                                    && k.reason() == CloseReason.ROOT_DISABLED && !journal.rollForwardPending(sid),
                                    "CHECKPOINT_UNKNOWN_CLOSE_KEPT：checkpoint 結果未知時保留已凍結的 CLOSE：" + row);
                            c.assertTrue(store.lookup(sid).isEmpty(), "CHECKPOINT_UNKNOWN_PUBLISHED：checkpoint 結果未知時不得發收據");
                            c.assertTrue(gate().globalChamberBusy(server, p.chamber), "CHECKPOINT_UNKNOWN_UNLOCKED：checkpoint 結果未知時仍占用");
                            until(c, "checkpoint_failure_keeps_close_and_lock/retry", 400, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                                try {
                                    var calls = probe.calls();
                                    c.assertTrue(calls.size() == 2 && !calls.get(1).failed() && calls.get(1).tick() - calls.get(0).tick() >= CollapseBackoff.INITIAL_TICKS
                                            && "door=OPEN/NONE receipt=false".equals(calls.get(1).observed()) && calls.get(1).chunks().containsAll(p.doorChunks()),
                                            "CHECKPOINT_RETRY：退避後重做 checkpoint，成功之後才發收據：" + calls);
                                    var receipt = store.lookup(sid).orElseThrow();
                                    c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                                            && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, now)), "收據保留原艙停止原因與時間：" + receipt.home().closeIntent());
                                    c.assertTrue("OPEN/NONE".equals(p.doorSummary()) && !gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                                    c.complete();
                                } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                                finally { p.close(); hooks.release(); }
                            }, () -> {
                                try { throw new GameTestException("CHECKPOINT_RETRY_NOT_CLOSED_OUT row=" + journal.allSessions().get(sid) + " checkpoints=" + probe.calls()
                                        + " thresholds=" + closureThresholds(p, sid)); }
                                finally { p.close(); hooks.release(); }
                            });
                        } catch (java.io.IOException failure) { p.close(); hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                        catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
                    }, () -> {
                        try { throw new GameTestException("CHECKPOINT_NOT_ATTEMPTED row=" + journal.allSessions().get(sid) + " thresholds=" + closureThresholds(p, sid)); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 Phase B2a：已存在的 target 內容不同（M7 spec §5.3 :175「已存在receipt只驗exact，錯配／損壞保鎖交管理員，不覆寫、不刪除」）。CLOSE 已凍結、門已改好、
     * 原生 checkpoint 當下（發佈之前），同 SID 的收據檔已經存在而且內容不同（同一列、closedAt 晚 5 tick 的合法收據）：owner 不得覆寫或刪除它，保留 CLOSE 與占用，
     * 每次退避重試都一樣；測試清掉自己放的檔之後，owner 才發佈正確的收據並移除熱列。單一 JVM 即可證明：比對發生在同一個 server 的 converge 內；
     * store 層的不覆寫由 JUnit {@code duplicateRawKeysAndHistoryMismatchFailClosed} 證明。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_existing_mismatch",tickLimit=1500)
    public void existing_receipt_mismatch_keeps_lock(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "existing_receipt_mismatch_keeps_lock", 380, hooks, () -> {
                try {
                    var sid = UUID.randomUUID();
                    var dormant = p.dormant(sid, 43);
                    var server = p.f.server;
                    var journal = SessionRecoveryState.get(server);
                    c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 的門是普通關門");
                    var store = p.receipts();
                    var target = receiptPath(p, sid);
                    String[] injected = {null};
                    var probe = M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 0, call -> {
                        if (call != 0) return "attempt=" + call;
                        try {
                            var frozen = (HomeSessionRecord) journal.allFlushedSessions().get(sid);
                            java.nio.file.Files.write(target, variantReceipt(frozen), java.nio.file.StandardOpenOption.CREATE_NEW);
                            injected[0] = M7HomeClosurePhases.sha256(target);
                            return "injected";
                        } catch (java.io.IOException | RuntimeException failure) { return "inject=ERROR:" + failure; }
                    }, hooks);
                    // 任何路徑收尾都清掉測試自己放的檔（只在內容仍是放入的那一份時），不留給之後的 test。
                    hooks.add(() -> { try { if (injected[0] != null && java.nio.file.Files.exists(target) && injected[0].equals(M7HomeClosurePhases.sha256(target))) java.nio.file.Files.delete(target); } catch (java.io.IOException ignored) { } });
                    long now = server.getOverworld().getTime();
                    c.assertTrue(journal.commitHomeRollForward(server, dormant, withStop(dormant, RootReason.ROOT_DISABLED, now)).committed(), "原艙停止原因已 checked latch");
                    until(c, "existing_receipt_mismatch_keeps_lock/first", 200, () -> !probe.calls().isEmpty(), () -> {
                        try {
                            c.assertTrue("injected".equals(probe.calls().getFirst().observed()) && injected[0] != null, "前提：發佈之前放入同 SID、內容不同的收據：" + probe.calls());
                            var temps = publishTemps(p);
                            assertMismatchKept(c, p, journal, sid, target, injected[0], "第一次");
                            until(c, "existing_receipt_mismatch_keeps_lock/second", 300, () -> probe.calls().size() >= 2, () -> {
                                try {
                                    // 退避後再試一次：仍只驗 exact、不覆寫、不刪除，也不產生暫存檔。
                                    assertMismatchKept(c, p, journal, sid, target, injected[0], "退避重試");
                                    c.assertTrue(publishTemps(p).equals(temps), "MISMATCH_TEMP_CREATED：既有收據不符時不得建立發佈暫存檔：" + publishTemps(p));
                                    // 清掉測試自己放的檔（管理員處理），owner 下一次重試才發佈正確的收據。
                                    java.nio.file.Files.delete(target);
                                    until(c, "existing_receipt_mismatch_keeps_lock/resolved", 500, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), () -> {
                                        try {
                                            var receipt = store.lookup(sid).orElseThrow();
                                            c.assertTrue(receipt.home().closeIntent() instanceof Close k && k.reason() == CloseReason.ROOT_DISABLED
                                                    && k.evidence().equals(new RootStop(RootReason.ROOT_DISABLED, now)) && !M7HomeClosurePhases.sha256(target).equals(injected[0]),
                                                    "處理之後發佈的是本列的正確收據：" + receipt.home().closeIntent());
                                            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                                            c.complete();
                                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                                        finally { p.close(); hooks.release(); }
                                    }, () -> {
                                        try { throw new GameTestException("MISMATCH_RESOLVED_NOT_CLOSED_OUT row=" + journal.allSessions().get(sid) + " thresholds=" + closureThresholds(p, sid)); }
                                        finally { p.close(); hooks.release(); }
                                    });
                                } catch (java.io.IOException failure) { p.close(); hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                                catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
                            }, () -> {
                                try { throw new GameTestException("MISMATCH_NOT_RETRIED checkpoints=" + probe.calls() + " thresholds=" + closureThresholds(p, sid)); }
                                finally { p.close(); hooks.release(); }
                            });
                        } catch (java.io.IOException failure) { p.close(); hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                        catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
                    }, () -> {
                        try { throw new GameTestException("MISMATCH_CHECKPOINT_NOT_ATTEMPTED row=" + journal.allSessions().get(sid) + " thresholds=" + closureThresholds(p, sid)); }
                        finally { p.close(); hooks.release(); }
                    });
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 Phase B2a：跨檔不符（M7 spec §5.3 :175–176、§10「receipt發佈／exact讀回前後、active未移除…錯配或未知維持鎖與原件」、V10「receipt與active錯配」）。
     * 收據已發佈、熱列移除的 journal 寫出受控失敗（RESTORED）之後，收據檔被換成同 SID、內容不同的合法收據：owner 的退避重試不得覆寫或刪除它、不得移除熱列，
     * 保留占用；測試還原原本的收據之後，下一次重試才移除。單一 JVM 即可證明：比對都在同一個 server 內；
     * 開服載入與寫入時的不符由 JUnit {@code everySchemaSixActiveSidMustAgreeWithItsHistory}、移除前 exact 由 {@code matchingTypedReceiptStillRequiresExactWrapperBeforeRemovingActive} 證明。
     * <p>T4 P1（B2a-M6、t4b2a-review P1）：不再避開原生 autosave 的窗口，改成在「收據不符、journal dirty」期間正面觸發原生存檔——autosave 的同一條路徑
     * {@code saveAll(true,false,false)}，以及 {@code /save-all flush} 的同一條路徑 {@code saveAll(true,true,true)}（原版 {@code SaveAllCommand}）。例外不得傳出原版存檔鏈；overworld 的其他 PersistentState
     * 與 level.dat 都要存到（flush 時兩側世界的 chunk 也要存到）；不符的那一列未變動、已在磁碟上，journal 照原樣寫出、不再 dirty；不符的 SID 仍保鎖（收據原件、
     * 熱列與占用不變）；管理員 ERROR（{@code CollapseService.LOG_MARKER}、{@code session=<SID>}）每個 instance 只記一次。跨 JVM 的停服面由原生列
     * {@code receipt-mismatch-stop} 證明。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_published_mismatch",tickLimit=2000)
    public void published_receipt_mismatch_keeps_lock(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            whenEntryReady(c, p, "published_receipt_mismatch_keeps_lock", 380, hooks, () -> {
                try {
                    var sid = UUID.randomUUID();
                    var dormant = p.dormant(sid, 42);
                    var server = p.f.server;
                    var journal = SessionRecoveryState.get(server);
                    c.assertTrue(new ChamberDoorService().applyTarget(p.b, p.frame, ChamberDoorService.DoorState.CLOSED).succeeded(), "B 的門是普通關門");
                    var store = p.receipts();
                    var target = receiptPath(p, sid);
                    var logs = M5GameTestSupport.captureLogs(hooks);
                    // nativeSave 會翻轉 announceAdvancements 來核對 level.dat；結束時還原，不留給之後的 test。
                    boolean announce = server.getGameRules().getBoolean(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS);
                    hooks.add(() -> server.getGameRules().get(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS).set(announce, server));
                    int start = (int) c.getTick();
                    var fault = new M5GameTestSupport.FlushFault(server, "T4b2a GameTest 受控：回程熱列移除的 journal 寫出失敗", 1,
                            state -> !state.allSessions().containsKey(sid) && state.allFlushedSessions().containsKey(sid));
                    M5GameTestSupport.installFlushProbe(c, fault, hooks);
                    var probe = M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 0, call -> "attempt=" + call, hooks);
                    byte[][] original = {null};
                    // 任何路徑收尾都先還原原本的收據，讓 dirty 的 journal 能寫出，不把收據不符留給之後的 test。
                    hooks.add(() -> { try { if (original[0] != null) java.nio.file.Files.write(target, original[0]); } catch (java.io.IOException ignored) { } });
                    hooks.add(p::close);
                    long now = server.getOverworld().getTime();
                    c.assertTrue(journal.commitHomeRollForward(server, dormant, withStop(dormant, RootReason.ROOT_DISABLED, now)).committed(), "原艙停止原因已 checked latch");
                    M5GameTestSupport.whenTicks(c, start + 1, 200, () -> fault.failures() >= 1, removalFailed -> {
                        try {
                            var frozen = journal.allFlushedSessions().get(sid);
                            c.assertTrue(frozen instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.home().closeIntent() instanceof Close
                                    && store.lookup(sid).orElseThrow().equals(h), "前提：收據已 exact 發佈、熱列移除失敗後 CLOSE 仍在：" + frozen);
                            c.assertTrue(journal.isDirty(), "前提：熱列移除失敗（RESTORED）之後 journal 仍 dirty");
                            original[0] = java.nio.file.Files.readAllBytes(target);
                            var tampered = variantReceipt((HomeSessionRecord) frozen);
                            java.nio.file.Files.write(target, tampered);
                            var tamperedSha = M7HomeClosurePhases.sha256(target);
                            var temps = publishTemps(p);
                            int attempts = probe.calls().size();
                            // T4 P1：不符而且 dirty 的期間正面觸發原生存檔（autosave 與 /save-all flush 的同一條原版路徑）。
                            var autosave = nativeSave(p, () -> server.saveAll(true, false, false), false);
                            c.assertTrue(autosave.thrown() == null, "NATIVE_AUTOSAVE_THREW：收據不符時原生 autosave 的例外傳進原版存檔鏈：" + autosave);
                            c.assertTrue(autosave.othersSaved(), "NATIVE_AUTOSAVE_INCOMPLETE：原生 autosave 沒有存完其他 PersistentState 與 level.dat：" + autosave);
                            c.assertTrue(!journal.isDirty() && frozen.equals(journal.allFlushedSessions().get(sid)) && frozen.equals(journal.allSessions().get(sid)),
                                    "NATIVE_AUTOSAVE_JOURNAL_HELD：未變動、已在磁碟上的不符列不得擋下 journal 寫出：dirty=" + journal.isDirty());
                            assertMismatchKept(c, p, journal, sid, target, tamperedSha, "原生 autosave 之後");
                            // 再標 dirty 一次，讓 /save-all flush 也走到收據核對：ERROR 不得重記。
                            journal.markDirty();
                            var flush = nativeSave(p, () -> server.saveAll(true, true, true), true);
                            c.assertTrue(flush.thrown() == null, "NATIVE_SAVE_ALL_THREW：收據不符時 /save-all flush 的例外傳進原版存檔鏈：" + flush);
                            c.assertTrue(flush.othersSaved() && flush.chunksSaved(), "NATIVE_SAVE_ALL_INCOMPLETE：/save-all flush 沒有存完其他 PersistentState、level.dat 與兩側世界的 chunk：" + flush);
                            c.assertTrue(!journal.isDirty() && frozen.equals(journal.allFlushedSessions().get(sid)),
                                    "NATIVE_SAVE_ALL_JOURNAL_HELD：未變動、已在磁碟上的不符列不得擋下 journal 寫出：dirty=" + journal.isDirty());
                            assertMismatchKept(c, p, journal, sid, target, tamperedSha, "/save-all flush 之後");
                            c.assertTrue(oneAdminError(adminErrors(logs, sid)), "MISMATCH_ERROR_COUNT：收據不符的管理員 ERROR 每個 instance 每個 SID 只記一次、帶 LOG_MARKER：" + adminErrors(logs, sid));
                            M5GameTestSupport.whenTicks(c, removalFailed + 1, 300, () -> probe.calls().size() > attempts, retried -> {
                                try {
                                    assertMismatchKept(c, p, journal, sid, target, tamperedSha, "退避重試");
                                    c.assertTrue(fault.ticks().size() == 1, "CROSS_FILE_REMOVAL_ATTEMPTED：收據與熱列不符時不得再嘗試移除熱列：" + fault.ticks());
                                    c.assertTrue(publishTemps(p).equals(temps), "MISMATCH_TEMP_CREATED：收據不符時不得建立發佈暫存檔：" + publishTemps(p));
                                    // 測試還原原本的收據（管理員處理），下一次重試才移除熱列。
                                    java.nio.file.Files.write(target, original[0]);
                                    M5GameTestSupport.whenTicks(c, retried + 1, 500, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), gone -> {
                                        try {
                                            c.assertTrue(java.util.Arrays.equals(original[0], java.nio.file.Files.readAllBytes(target)), "還原後收據就是原本發佈的那一份");
                                            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "checked 移除後解鎖");
                                            c.assertTrue(oneAdminError(adminErrors(logs, sid)), "MISMATCH_ERROR_COUNT：收尾時管理員 ERROR 仍只有一則：" + adminErrors(logs, sid));
                                            hooks.release();
                                            c.complete();
                                        } catch (java.io.IOException failure) { throw new GameTestException("收據讀取失敗：" + failure); }
                                    }, () -> "還原收據之後沒有移除熱列：row=" + journal.allSessions().get(sid) + " thresholds=" + closureThresholds(p, sid), hooks);
                                } catch (java.io.IOException failure) { hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                            }, () -> "收據不符後沒有退避重試：checkpoints=" + probe.calls() + " thresholds=" + closureThresholds(p, sid), hooks);
                        } catch (java.io.IOException failure) { hooks.release(); throw new GameTestException("收據讀取失敗：" + failure); }
                    }, () -> "熱列移除的受控失敗沒有發生：row=" + journal.allSessions().get(sid) + " faults=" + fault.ticks(), hooks);
                } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { p.close(); hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 P2（使用者 2026-11-17 裁示「M7 一併修」；t4b2a-review P1-M3）：原生存檔遇到 journal 正式寫出失敗——Windows atomic move 的 AccessDenied（防毒或索引鎖檔），
     * 以 {@link M7NativeSaveFault} 讓真正的 {@code writePrepared} 的 move 失敗——時不丟進原版存檔鏈，而且保持 M5 既有的 pending 語意：
     * <ol>
     * <li>HOME roll-forward 的 checked 寫出先因同一個故障失敗：結果不明（possiblyMoved），保留 pending、roll-forward 待確認，journal 檔不變。</li>
     * <li>故障仍在時正面呼叫原版 autosave 的同一條路徑 {@code saveAll(true,false,false)} 與 {@code /save-all flush} 的同一條路徑 {@code saveAll(true,true,true)}
     *     （pending 狀態下的原生存檔：先核對磁碟仍是上一份權威，才重試寫出、再次失敗）：例外不得傳出；overworld 的 raids 與 level.dat（flush 時另有兩側 chunk）
     *     都要存到；journal 檔 bytes 不變、仍 dirty、pending 與待確認的 roll-forward 都保留；原生存檔的 ERROR（{@code TICK_LOG_MARKER}）恰一則。</li>
     * <li>同一個 tick 結束前，recovery 的 journal 退避重試也撞上故障（失敗一次）；解除故障之後，由它在退避期滿時自行收斂（不手動 flush）。</li>
     * </ol>
     * 收尾以原艙停止 checked latch，由回程終局 owner 結案移除。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_native_write",tickLimit=2000)
    public void native_save_write_failure_keeps_pending(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            hooks.add(p::close);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var sid = UUID.randomUUID();
            var target = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat");
            var logs = M5GameTestSupport.captureLogs(hooks);
            // nativeSave 會翻轉 announceAdvancements 來核對 level.dat；結束時還原，不留給之後的 test。
            boolean announce = server.getGameRules().getBoolean(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS);
            hooks.add(() -> server.getGameRules().get(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS).set(announce, server));
            M5GameTestSupport.afterAutosaveClear(c, 1, "m7_t4p2_native_write", 800, (start, autosave) -> {
                try {
                    var settled = p.settled(sid, 48);
                    var dormant = new HomeSessionRecord(sid, p.chamber, p.root, p.entry, settled.participants(), List.of(), SessionState.MEASURED, settled.home());
                    var fault = M7NativeSaveFault.arm(target);
                    // 任何路徑收尾都先解除故障並帶出尚未確認的寫出，不把全域 pending 留給之後的 test（hooks 後進先出；只在判定之後執行）。
                    hooks.add(() -> { fault.close(); if (journal.rollForwardPending(sid) || !journal.pendingSessions().isEmpty()) journal.flush(server); });
                    var before = M7HomeClosurePhases.sha256(target);
                    var commit = journal.commitHomeRollForward(server, settled, dormant);
                    c.assertTrue(commit.status() == SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING && journal.rollForwardPending(sid) && journal.isDirty()
                            && dormant.equals(journal.pendingSessions().get(sid)) && fault.hits() == 1 && before.equals(M7HomeClosurePhases.sha256(target)),
                            "前提：checked 寫出因 atomic move 失敗、結果不明（pending）、journal 檔不變：status=" + commit.status() + " hits=" + fault.hits()
                                    + " pending=" + journal.pendingSessions().keySet());
                    var autosaveResult = nativeSave(p, () -> server.saveAll(true, false, false), false);
                    c.assertTrue(autosaveResult.thrown() == null, "NATIVE_AUTOSAVE_THREW：journal 寫出失敗時原生 autosave 的例外傳進原版存檔鏈：" + autosaveResult);
                    c.assertTrue(autosaveResult.othersSaved(), "NATIVE_AUTOSAVE_INCOMPLETE：原生 autosave 沒有存完其他 PersistentState 與 level.dat：" + autosaveResult);
                    assertPendingKept(c, journal, sid, settled, dormant, target, before, fault, 2, "原生 autosave 之後");
                    var flush = nativeSave(p, () -> server.saveAll(true, true, true), true);
                    c.assertTrue(flush.thrown() == null, "NATIVE_SAVE_ALL_THREW：journal 寫出失敗時 /save-all flush 的例外傳進原版存檔鏈：" + flush);
                    c.assertTrue(flush.othersSaved() && flush.chunksSaved(), "NATIVE_SAVE_ALL_INCOMPLETE：/save-all flush 沒有存完其他 PersistentState、level.dat 與兩側世界的 chunk：" + flush);
                    assertPendingKept(c, journal, sid, settled, dormant, target, before, fault, 3, "/save-all flush 之後");
                    c.assertTrue(nativeSaveErrors(logs).size() == 1 && allNativeSaveErrors(logs) == 1,
                            "NATIVE_ERROR_COUNT：原生存檔的寫出失敗每個 instance 只記一則 ERROR、帶 TICK_LOG_MARKER：" + nativeSaveErrors(logs) + " total=" + allNativeSaveErrors(logs));
                    c.assertTrue(nativeSaveErrors(logs).getFirst().contains("寫入正式檔失敗") && nativeSaveErrors(logs).getFirst().contains(target.toAbsolutePath().normalize().toString()),
                            "NATIVE_ERROR_TEXT：ERROR 說明失敗類別與 journal 路徑：" + nativeSaveErrors(logs));
                    M5GameTestSupport.whenTicks(c, start + 1, 300, () -> fault.hits() >= 4, retried -> {
                        try {
                            // recovery 的 journal 退避重試在故障期間也失敗一次（同一個故障、同一份 pending），之後才解除故障。
                            assertPendingKept(c, journal, sid, settled, dormant, target, before, fault, 4, "recovery 重試失敗之後");
                            fault.disarm();
                            M5GameTestSupport.whenTicks(c, retried + 1, 600, () -> !journal.rollForwardPending(sid), confirmed -> {
                                try {
                                    c.assertTrue(dormant.equals(journal.allFlushedSessions().get(sid)) && dormant.equals(journal.allSessions().get(sid))
                                            && journal.pendingSessions().isEmpty() && !before.equals(M7HomeClosurePhases.sha256(target)),
                                            "NATIVE_NOT_CONVERGED：解除故障後 journal 沒有收斂到待確認的那一份：flushed=" + journal.allFlushedSessions().get(sid));
                                    c.assertTrue(confirmed - start >= CollapseBackoff.INITIAL_TICKS && fault.hits() == 4,
                                            "NATIVE_CONVERGED_BY_OTHER：應由 recovery 的 journal 退避重試在退避期滿時收斂：start=" + start + " confirmed=" + confirmed + " hits=" + fault.hits());
                                    c.assertTrue(nativeSaveErrors(logs).size() == 1, "NATIVE_ERROR_COUNT：收斂之後仍只有一則：" + nativeSaveErrors(logs));
                                    closeOutDormant(c, p, dormant, confirmed, hooks);
                                } catch (java.io.IOException failure) { throw new GameTestException("journal 讀取失敗：" + failure); }
                            }, () -> "解除故障後沒有收斂：pending=" + journal.pendingSessions().keySet() + " rollForward=" + journal.rollForwardPending(sid), hooks);
                        } catch (java.io.IOException failure) { hooks.release(); throw new GameTestException("journal 讀取失敗：" + failure); }
                    }, () -> "recovery 的重試沒有撞上故障：hits=" + fault.hits(), hooks);
                } catch (java.io.IOException failure) { hooks.release(); throw new GameTestException("故障武裝或 journal 讀取失敗：" + failure); }
            }, hooks);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * T4 P2：journal 不健康（開服時讀不到；以 {@code loadError} 重現，沿 {@code fence_init_failure_keeps_enable_blocked} 的做法，同一個 callback 內設定並還原）時，
     * 原生存檔不丟進原版存檔鏈、也不寫 journal：例外不得傳出；其他 PersistentState、level.dat 與兩側 chunk 都要存到；journal 檔 bytes 不變、仍 dirty；
     * ERROR（{@code TICK_LOG_MARKER}）兩次存檔只記一則。恢復健康之後，下一次原生存檔照常寫出。
     */
    @GameTest(templateName="quantumchamber:m1_empty",batchId="m7_home_closure_native_unhealthy",tickLimit=200)
    public void native_save_skips_unhealthy_journal(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var p = new Projection(c, hooks);
        try {
            hooks.add(p::close);
            var server = p.f.server;
            var journal = SessionRecoveryState.get(server);
            var target = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(SessionRecoveryState.STATE_ID + ".dat");
            var logs = M5GameTestSupport.captureLogs(hooks);
            boolean announce = server.getGameRules().getBoolean(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS);
            hooks.add(() -> server.getGameRules().get(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS).set(announce, server));
            c.waitAndRun(1, () -> {
                try {
                    journal.markDirty();
                    var before = M7HomeClosurePhases.sha256(target);
                    var previous = M4CandidateTestAccess.get(journal, "loadError");
                    NativeSave flush, autosaveResult;
                    try {
                        M4CandidateTestAccess.set(journal, "loadError", "M7_T4P2_UNHEALTHY_FAULT");
                        flush = nativeSave(p, () -> server.saveAll(true, true, true), true);
                        autosaveResult = nativeSave(p, () -> server.saveAll(true, false, false), false);
                    } finally { M4CandidateTestAccess.set(journal, "loadError", previous); }
                    c.assertTrue(flush.thrown() == null && autosaveResult.thrown() == null,
                            "NATIVE_UNHEALTHY_THREW：journal 不健康時原生存檔的例外傳進原版存檔鏈：" + flush + " / " + autosaveResult);
                    c.assertTrue(flush.othersSaved() && flush.chunksSaved() && autosaveResult.othersSaved(),
                            "NATIVE_UNHEALTHY_INCOMPLETE：原生存檔沒有存完其他資料：" + flush + " / " + autosaveResult);
                    c.assertTrue(before.equals(M7HomeClosurePhases.sha256(target)) && journal.isDirty(), "NATIVE_UNHEALTHY_WROTE：journal 不健康時不得寫出 journal、保持 dirty");
                    c.assertTrue(nativeSaveErrors(logs).size() == 1 && allNativeSaveErrors(logs) == 1 && nativeSaveErrors(logs).getFirst().contains("journal 不健康"),
                            "NATIVE_ERROR_COUNT：同一類只記一則、帶 TICK_LOG_MARKER：" + nativeSaveErrors(logs));
                    var healed = nativeSave(p, () -> server.saveAll(true, false, false), false);
                    c.assertTrue(healed.thrown() == null && !journal.isDirty(), "恢復健康之後下一次原生存檔照常寫出 journal：" + healed + " dirty=" + journal.isDirty());
                    hooks.release();
                    c.complete();
                } catch (java.io.IOException failure) { hooks.release(); throw new GameTestException("journal 讀取失敗：" + failure); }
                catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            });
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /** {@link #native_save_write_failure_keeps_pending}：寫出失敗之後 journal 仍保留 pending、dirty 與待確認的 roll-forward，journal 檔不變，故障已導向 {@code hits} 次。 */
    private static void assertPendingKept(TestContext c, SessionRecoveryState journal, UUID sid, HomeSessionRecord settled, HomeSessionRecord dormant,
            java.nio.file.Path target, String before, M7NativeSaveFault.Handle fault, int hits, String when) throws java.io.IOException {
        c.assertTrue(before.equals(M7HomeClosurePhases.sha256(target)), "NATIVE_JOURNAL_OVERWRITTEN：" + when + "：journal 檔不得改變");
        c.assertTrue(journal.isDirty() && journal.rollForwardPending(sid) && dormant.equals(journal.pendingSessions().get(sid))
                && settled.equals(journal.allFlushedSessions().get(sid)) && dormant.equals(journal.allSessions().get(sid)),
                "NATIVE_PENDING_LOST：" + when + "：必須保留 dirty、pending 與待確認的 roll-forward：dirty=" + journal.isDirty() + " pending=" + journal.pendingSessions().keySet()
                        + " flushed=" + journal.allFlushedSessions().get(sid));
        c.assertTrue(fault.hits() == hits, "NATIVE_WRITE_ATTEMPTS：" + when + "：pending 先核對磁碟、才重試寫出，寫出次數應為 " + hits + "：" + fault.hits());
    }

    /** 原生存檔的 journal 寫出失敗 ERROR（T4 P2）：帶 {@code TICK_LOG_MARKER}（父 marker 是 {@code LOG_MARKER}）、訊息以固定前綴開頭。 */
    private static List<String> nativeSaveErrors(LogCapture logs) {
        return logs.marked(org.apache.logging.log4j.Level.ERROR, CollapseService.TICK_LOG_MARKER.getName(), NATIVE_SAVE_ERROR).stream().map(LogCapture.Entry::message).toList();
    }

    /** 同前綴的 ERROR 總數（不分 marker）：與 {@link #nativeSaveErrors} 相等時，代表每一則都帶 TICK marker。 */
    private static int allNativeSaveErrors(LogCapture logs) {
        return logs.matching(org.apache.logging.log4j.Level.ERROR, NATIVE_SAVE_ERROR).size();
    }

    private static final String NATIVE_SAVE_ERROR = "原生存檔（autosave、/save-all 或停服）的 journal 寫出失敗";

    /**
     * 一次原生存檔的觀察（T4 P1）：呼叫前把 A、B 控制器所在的 chunk 標為待存、overworld 的 raids（另一個原版 PersistentState）標 dirty，並翻轉 gamerule
     * {@code announceAdvancements}（存在 level.dat；不用 level.dat 的 hash，同一 tick 內兩次寫出的內容可能相同）；呼叫後看例外有沒有傳出、raids 是否已存（不再 dirty）、
     * level.dat 的 gamerule 是否等於剛設的值，{@code flush} 時另看兩側 chunk 是否已存（不再待存）。呼叫端負責在結束時還原 gamerule。
     */
    private static NativeSave nativeSave(Projection p, Runnable save, boolean flush) throws java.io.IOException {
        var server = p.f.server;
        var level = server.getSavePath(WorldSavePath.LEVEL_DAT);
        var a = server.getOverworld().getWorldChunk(p.frame.controllerPos());
        var b = p.b.getWorldChunk(p.frame.controllerPos());
        var raids = server.getOverworld().getRaidManager();
        var announce = server.getGameRules().get(net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS);
        boolean expected = !announce.get();
        a.setNeedsSaving(true); b.setNeedsSaving(true); raids.markDirty(); announce.set(expected, server);
        RuntimeException thrown = null;
        try { save.run(); } catch (RuntimeException failure) { thrown = failure; }
        var rules = net.minecraft.nbt.NbtIo.readCompressed(level, net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes()).getCompound("Data").getCompound("GameRules");
        boolean raidsSaved = !raids.isDirty(), levelSaved = String.valueOf(expected).equals(rules.getString("announceAdvancements"));
        boolean chunksSaved = !a.needsSaving() && !b.needsSaving();
        if (thrown != null) WAIT_LOG.error("M7_T4P1_NATIVE_SAVE_THREW flush={}", flush, thrown);
        return new NativeSave(thrown == null ? null : thrown.toString(), raidsSaved, levelSaved, chunksSaved);
    }

    /** {@link #nativeSave} 的結果；{@code thrown} 是傳出的例外（沒有時為 null）；{@code chunksSaved} 只在 flush 時有意義（autosave 有原版的每 chunk 節流）。 */
    private record NativeSave(String thrown, boolean raidsSaved, boolean levelSaved, boolean chunksSaved) {
        boolean othersSaved() { return raidsSaved && levelSaved; }
    }

    /**
     * 本 SID 的 ERROR：訊息含 {@code session=<SID>} 的全部 ERROR（不分 marker），每則記成 {@code <marker>|<訊息>}。呼叫端核對恰一則，而且那一則帶
     * {@code CollapseService.LOG_MARKER}（見 {@link #oneAdminError}）。
     */
    private static List<String> adminErrors(LogCapture logs, UUID sid) {
        return logs.matching(org.apache.logging.log4j.Level.ERROR, "session=" + sid).stream()
                .map(entry -> (entry.marker() == null ? "NO_MARKER" : entry.marker().getName()) + "|" + entry.message()).toList();
    }

    private static boolean oneAdminError(List<String> errors) {
        return errors.size() == 1 && errors.getFirst().startsWith(CollapseService.LOG_MARKER.getName() + "|");
    }

    /** 收據不符時 owner 必須保鎖：收據檔不被覆寫或刪除、已凍結的 CLOSE 與熱列仍在（current＝flushed）、仍占用。 */
    private static void assertMismatchKept(TestContext c, Projection p, SessionRecoveryState journal, UUID sid, java.nio.file.Path target, String sha, String when)
            throws java.io.IOException {
        c.assertTrue(java.nio.file.Files.exists(target) && sha.equals(M7HomeClosurePhases.sha256(target)),
                "MISMATCH_OVERWRITTEN：" + when + "：內容不同的收據不得被覆寫或刪除");
        var row = journal.allFlushedSessions().get(sid);
        c.assertTrue(row instanceof HomeSessionRecord h && h.equals(journal.allSessions().get(sid)) && h.home().closeIntent() instanceof Close,
                "MISMATCH_ACTIVE_REMOVED：" + when + "：收據不符時熱列與 CLOSE 必須保留：" + row);
        c.assertTrue(gate().globalChamberBusy(p.f.server, p.chamber), "MISMATCH_UNLOCKED：" + when + "：收據不符時仍占用");
    }

    /** 本 SID 的收據檔路徑（存檔根的 {@code data/quantumchamber_home_receipts/<sid>.dat}）。 */
    private static java.nio.file.Path receiptPath(Projection p, UUID sid) {
        return p.f.server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve("quantumchamber_home_receipts").resolve(sid + ".dat");
    }

    /** 收據目錄裡的發佈暫存檔（{@code publish-*.tmp}）名稱。 */
    private static Set<String> publishTemps(Projection p) throws java.io.IOException {
        var names = new TreeSet<String>();
        for (var file : M7HomeClosurePhases.listing(receiptPath(p, NO_SESSION).getParent())) {
            var name = (String) file.get("name");
            if (name.startsWith("publish-")) names.add(name);
        }
        return names;
    }

    /**
     * 同一列、closedAt 晚 5 tick 的合法收據（內容不同、SID 相同），以正式 {@link HomeReceiptStore} 發佈到另一個暫存的存檔根之後讀出 bytes；
     * 讀出之後遞迴刪除那個暫存目錄（B2a 審查 M2）。
     */
    static byte[] variantReceipt(HomeSessionRecord frozen) throws java.io.IOException {
        var close = (Close) frozen.home().closeIntent();
        var variant = frozen.withClose(new Close(close.reason(), close.closedAtGameTime() + 5, close.doorGoal(), close.evidence()));
        var scratch = java.nio.file.Files.createTempDirectory("m7-b2a-receipt-variant");
        try {
            new HomeReceiptStore(scratch).publishOrVerify(variant);
            return java.nio.file.Files.readAllBytes(scratch.resolve("data").resolve("quantumchamber_home_receipts").resolve(frozen.sessionUuid() + ".dat"));
        } finally {
            try (var walk = java.nio.file.Files.walk(scratch)) {
                for (var path : walk.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
            }
        }
    }

    /** N2 收尾：已確認的 DORMANT 以原艙停止 checked latch，由回程終局 owner 結案移除；之後釋放 hooks（含 Projection）。 */
    private static void closeOutDormant(TestContext c, Projection p, HomeSessionRecord dormant, int tick, M2CorridorGameTests.TestHooks hooks) {
        var server = p.f.server;
        var journal = SessionRecoveryState.get(server);
        var sid = dormant.sessionUuid();
        c.assertTrue(journal.commitHomeRollForward(server, dormant, withStop(dormant, RootReason.ROOT_DISABLED, server.getOverworld().getTime())).committed(),
                "收尾：原艙停止 checked latch");
        M5GameTestSupport.whenTicks(c, tick + 1, 200, () -> !journal.allSessions().containsKey(sid) && !journal.allFlushedSessions().containsKey(sid), gone -> {
            c.assertTrue(!gate().globalChamberBusy(server, p.chamber), "收尾：checked 移除後解鎖");
            hooks.release();
            c.complete();
        }, () -> "收尾：原艙停止後沒有結案移除：row=" + journal.allSessions().get(sid), hooks);
    }

    /** 記下每一次 journal 寫出與 B 門原生 checkpoint 當下，這個 SID 的 session operation guard 是否持有（M7 spec §9.1 :249）。 */
    private static List<String> guardWitness(TestContext c, Projection p, UUID sid, M2CorridorGameTests.TestHooks hooks) {
        var writes = new ArrayList<String>();
        var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(p.f.server);
        M5GameTestSupport.installFlushProbe(c, state -> writes.add("flush guard=" + pages.operationInProgress(sid)), hooks);
        M5GameTestSupport.installChunkCheckpointProbe(c, p.b.getRegistryKey(), 0, tick -> {
            writes.add("checkpoint guard=" + pages.operationInProgress(sid));
            return "";
        }, hooks);
        return writes;
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

    /**
     * 逾時診斷（CI 43993912938 Ubuntu 的 participant_close_closes_door_when_doorway_clear）：回程終局 owner 在 step 與 converge 中不記 log 就等待的每一個門檻，
     * 在呼叫當下的值。只讀、不改 production：公開 API 直接呼叫，package 可見或 private 的部分以測試內反射讀取；每一項各自讀，讀不到時記下例外、不中斷。
     * 只放進失敗訊息，不參與判定。
     */
    static String closureThresholds(Projection p, UUID sid) {
        var server = p.f.server;
        var out = new LinkedHashMap<String, Object>();
        diagnose(out, "serverTicks", server::getTicks);
        diagnose(out, "gameTime", () -> server.getOverworld().getTime());
        // HomeClosure.tick：journal 不健康時整個 tick 直接 return。
        diagnose(out, "journalHealthy", () -> { SessionRecoveryState.get(server).requireHealthy(); return true; });
        var journal = SessionRecoveryState.get(server);
        Object current = null;
        try {
            current = journal.allSessions().get(sid);
            out.put("current", current instanceof HomeSessionRecord row ? row.state() + "/" + (row.home().closeIntent() instanceof Close k
                    ? k.reason() + "/" + k.doorGoal() : row.home().closeIntent().getClass().getSimpleName()) : String.valueOf(current));
        } catch (RuntimeException failure) { out.put("current", "ERROR:" + failure); }
        // step：settled（current＝flushed、非 roll-forward 待確認、沒有送出中的 attempt 帶這個 SID、未隔離）、根事件排隊、session operation guard。
        var currentRow = current;
        diagnose(out, "currentEqualsFlushed", () -> Objects.equals(currentRow, journal.allFlushedSessions().get(sid)));
        diagnose(out, "rollForwardPending", () -> journal.rollForwardPending(sid));
        diagnose(out, "pendingAttemptHasSid", () -> journal.pendingSessions().containsKey(sid));
        diagnose(out, "pendingAttemptSessions", () -> journal.pendingSessions().keySet());
        diagnose(out, "quarantined", () -> journal.quarantinedSessions().contains(sid));
        diagnose(out, "queued", () -> invokeStatic("dev.quantumchamber.collapse.HomeRootFence", "queued", new Class<?>[]{MinecraftServer.class, UUID.class}, server, sid));
        diagnose(out, "operationInProgress", () -> dev.quantumchamber.corridor.CorridorPageManager.forServer(server).operationInProgress(sid));
        // converge：本 instance 的 context 與這個 SID 的退避、B 端點、exact B 世界、鄰域、門口 entity、原生 checkpoint 前置。
        diagnose(out, "context", () -> {
            var context = ((Map<?, ?>) M4CandidateTestAccess.get(HomeClosure.class, "CONTEXTS")).get(server);
            if (context == null) return "ABSENT";
            var backoff = (CollapseBackoff) ((Map<?, ?>) M4CandidateTestAccess.get(context, "backoff")).get(sid);
            return "backoff=" + (backoff == null ? "NONE" : "delay:" + backoff.delayTicks() + ",retryAt:" + backoff.retryAtTick() + ",ready:" + backoff.ready(server.getTicks()))
                    + " warned=" + ((Map<?, ?>) M4CandidateTestAccess.get(context, "warned")).get(sid)
                    + " startup=" + ((Set<?>) M4CandidateTestAccess.get(context, "startup")).contains(sid);
        });
        diagnose(out, "inspectEntry", () -> HomeEndpointAuthority.inspectEntry(server, p.entry));
        ServerWorld world = null;
        try { world = server.getWorld(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, p.entry.worldKey())); }
        catch (RuntimeException failure) { out.put("worldLoaded", "ERROR:" + failure); }
        var entryWorld = world;
        out.putIfAbsent("worldLoaded", entryWorld != null);
        diagnose(out, "worldIsFixtureB", () -> entryWorld == p.b);
        diagnose(out, "activeMatches", () -> entryWorld != null && UniverseLifecycleService.resolveActive(server, p.entry.universeId()).orElse(null) == entryWorld);
        var target = entryWorld != null ? entryWorld : p.b;
        diagnose(out, "neighborhoodLoaded", () -> invokeStatic("dev.quantumchamber.collapse.CollapseService", "neighborhoodLoaded",
                new Class<?>[]{ServerWorld.class, ChamberFrame.class}, target, p.frame));
        diagnose(out, "doorwayEntitiesLoaded", () -> invokeStatic("dev.quantumchamber.collapse.OriginDoors", "doorwayEntitiesLoaded",
                new Class<?>[]{ServerWorld.class, ChamberFrame.class}, target, p.frame));
        diagnose(out, "doorwayOccupied", () -> invokeStatic("dev.quantumchamber.collapse.OriginDoors", "doorwayOccupied",
                new Class<?>[]{ServerWorld.class, ChamberFrame.class}, target, p.frame));
        diagnose(out, "checkpoint", () -> {
            @SuppressWarnings("unchecked")
            var chunks = (Set<ChunkPos>) invokeStatic("dev.quantumchamber.collapse.PassageTransferPolicy", "homeChunks", new Class<?>[]{ChamberFrame.class}, p.frame);
            var missing = new ArrayList<String>();
            for (var pos : chunks) {
                boolean block = target.getChunkManager().getWorldChunk(pos.x, pos.z) != null, entities = target.isChunkLoaded(pos.toLong());
                if (!block || !entities) missing.add(pos.x + "," + pos.z + ":block=" + block + ",entities=" + entities);
            }
            return "ready=" + ChunkSaveCheckpoint.ready(target, chunks) + " chunks=" + chunks.size() + " notLoaded=" + missing;
        });
        diagnose(out, "receiptPublished", () -> {
            try { return p.receipts().lookup(sid).isPresent(); }
            catch (java.io.IOException failure) { return "ERROR:" + failure; }
        });
        diagnose(out, "door", p::doorSummary);
        return out.toString();
    }

    private static void diagnose(Map<String, Object> out, String name, java.util.function.Supplier<Object> value) {
        try { out.put(name, value.get()); }
        catch (RuntimeException | LinkageError failure) { out.put(name, "ERROR:" + failure); }
    }

    /** 測試內反射：呼叫 production 的 package 可見 static 方法（只讀的查詢），依名稱與參數型別。 */
    private static Object invokeStatic(String type, String name, Class<?>[] parameters, Object... args) {
        try {
            var method = Class.forName(type).getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method.invoke(null, args);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new IllegalStateException(type + "." + name + " 丟出：" + failure.getCause(), failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); }
    }

    /** 前置等待逾時時 thresholds 用的 SID：等待在本案建立 session 之前，沒有本案的列。 */
    private static final UUID NO_SESSION = new UUID(0L, 0L);

    /**
     * B 端就緒的前置等待（使用者 2026-11-17 裁示「測試先等 B 區塊就緒」，CI 44003465357 Ubuntu pinch 案）：本案建立 session、觸發收尾之前，有界等待 B 的
     * {@code PassageTransferPolicy.homeChunks} 每個 chunk 的方塊與 entity storage 都已載入（與 {@link ChunkSaveCheckpoint#ready} 同一判斷），以及
     * {@code OriginDoors.doorwayEntitiesLoaded} 為真；就緒後才執行 {@code body}。等不到時丟出以 {@code ENV_NOT_READY} 開頭的 GameTestException（附 thresholds），
     * 標明是測試環境未就緒、不是回程終局 owner 的失敗。收尾等待（200 tick）、斷言、tickLimit 與案例集合都不變；{@code bound} 由呼叫端依該案 tickLimit
     * 扣掉其後的等待決定，前置等待加上收尾等待不超過 tickLimit。{@code body} 在 tick callback 內執行，失敗時由它自己釋放 fixture。
     */
    private static void whenEntryReady(TestContext c, Projection p, String label, int bound, M2CorridorGameTests.TestHooks hooks, Runnable body) {
        @SuppressWarnings("unchecked")
        var chunks = (Set<ChunkPos>) invokeStatic("dev.quantumchamber.collapse.PassageTransferPolicy", "homeChunks", new Class<?>[]{ChamberFrame.class}, p.frame);
        until(c, label + "/entry_ready", bound, () -> ChunkSaveCheckpoint.ready(p.b, chunks)
                && (Boolean) invokeStatic("dev.quantumchamber.collapse.OriginDoors", "doorwayEntitiesLoaded", new Class<?>[]{ServerWorld.class, ChamberFrame.class}, p.b, p.frame),
                body, () -> {
                    try {
                        throw new GameTestException("ENV_NOT_READY：B 端在 " + bound + " tick 內沒有就緒（homeChunks 的方塊與 entity、門口 entity），是測試環境未就緒、"
                                + "不是回程終局 owner 的失敗（本案 session 尚未建立）：thresholds=" + closureThresholds(p, NO_SESSION));
                    } finally { p.close(); hooks.release(); }
                });
    }

    /**
     * 每 tick 檢查一次；{@code done} 成立時執行 {@code then}，用完 {@code bound} 次仍不成立時執行 {@code timeout}。
     * 結束時先記一行 {@code M7_GT_WAIT used=<實際用掉的 tick 數> bound=<上限> case=<label>}（逾時另加 {@code timeout=true}），再執行回呼；
     * used 是條件成立前經過的 {@code waitAndRun(1)} 次數（當下就成立為 0，逾時等於 bound）。只記錄各平台的等待餘量，不參與判定。
     */
    static void until(TestContext c, String label, int bound, BooleanSupplier done, Runnable then, Runnable timeout) {
        untilStep(c, label, bound, bound, done, then, timeout);
    }

    private static void untilStep(TestContext c, String label, int bound, int remaining, BooleanSupplier done, Runnable then, Runnable timeout) {
        if (done.getAsBoolean()) {
            WAIT_LOG.info("M7_GT_WAIT used={} bound={} case={}", bound - remaining, bound, label);
            then.run();
            return;
        }
        if (remaining <= 0) {
            WAIT_LOG.info("M7_GT_WAIT used={} bound={} case={} timeout=true", bound - remaining, bound, label);
            timeout.run();
            return;
        }
        c.waitAndRun(1, () -> {
            // 原版只把 GameTestException 記為測試失敗；其他例外會讓整個 test server 崩潰（T4a RED 實測）。
            try { untilStep(c, label, bound, remaining - 1, done, then, timeout); }
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
