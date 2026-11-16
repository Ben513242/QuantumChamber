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
            var dormant = new HomeSessionRecord(sid, chamber, root, entry, List.of(returned), List.of(), SessionState.MEASURED, selected);
            c.assertTrue(journal.commitHomeRollForward(server, settled, dormant).committed(), "checked 清除租約");
            return dormant;
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
