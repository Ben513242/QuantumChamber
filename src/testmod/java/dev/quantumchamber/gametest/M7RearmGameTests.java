package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;

import dev.quantumchamber.chamber.ChamberActivationService;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberLifecycleService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.GameTestException;
import net.minecraft.test.TestContext;
import org.apache.logging.log4j.Level;

/**
 * M7 T4A（spec v1.6 A、V12）：取消之後要玩家親手重新關門才再出發。PREPARE 與通道期間參與者關門的 checked 終局之後，原艙仍供電、門關著、
 * 全員有藥效也不出發（比較器 3）；玩家親手開一次、再關一次門才出發。M5 塌縮走正式入口（DORMANT NEW 收據、參與者由艙外進入）；出發後的新 session
 * 以 {@code NativeEntry.trustedTeardown} 斷電返還。真入場需要 Windows 原生 checkpoint，所以這兩案在 {@code _windows} batch。
 */
public final class M7RearmGameTests implements FabricGameTest {
    /** 等 PREPARE（sealed 量子膜）或 PASSAGE_READY 的上限：RESOLVE 要配置並物化新的宇宙，PREPARE 的預載是 wall-clock 的 chunk 載入。 */
    private static final int COLLAPSE_WAIT_TICKS = 1200;
    /** 關門之後觀察不出發的 tick 數：涵蓋 Controller 每 20 tick 一次的排程刷新。 */
    private static final int QUIET_TICKS = 25;

    /** spec v1.6 A 上鎖事件 1：PREPARE 期間參與者關門。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_prepare_windows", tickLimit = 20000)
    public void prepare_participant_close_requires_player_reclose_windows(TestContext context) {
        participantCloseRequiresReclose(context, false, "prepare_participant_close_requires_player_reclose_windows");
    }

    /** spec v1.6 A 上鎖事件 2：通道期間（PASSAGE_READY）參與者關門。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_passage_windows", tickLimit = 20000)
    public void passage_participant_close_requires_player_reclose_windows(TestContext context) {
        participantCloseRequiresReclose(context, true, "passage_participant_close_requires_player_reclose_windows");
    }

    /**
     * 參與者在 PREPARE（{@code passage} 為假）或 PASSAGE_READY 期間以 bulkhead 關門：當場 checked 終局、門關上；之後 {@value #QUIET_TICKS} tick 都沒有新 session、
     * 比較器 3。玩家親手開門（仍不出發、比較器 3），再親手關門才出發（新 session 的 ARMING）；新 session 以正式 LOW 返還收尾。
     */
    private static void participantCloseRequiresReclose(TestContext c, boolean passage, String name) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = Origin.open(c, hooks);
        var f = origin.f;
        var world = c.getWorld(); var journal = SessionRecoveryState.get(f.server);
        var p = f.players.getFirst().player();
        var door = ChamberGeometry.localToWorld(f.frame, 3, 3, 0);
        origin.collapse(passage, (closeAt, chamber, sid) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            witness.put("closeAccepted", interact(world, door, p).isAccepted());
            witness.put("terminated", !journal.records().containsKey(sid) && !journal.flushedRecords().containsKey(sid));
            witness.put("oneTerminalInfo", origin.capture.matching(Level.INFO, passage ? "(a) 參與者在原艙這一側開關原艙門" : "PREPARE 期間參與者關門，塌縮已結束",
                    sid.toString()).size() == 1);
            witness.put("doorClosed", doorAll(world, f.frame, false, MembraneState.NONE));
            if (departed(f.server, chamber)) {
                origin.failAfterTeardown(closeAt, "REARM_DEPARTED_AFTER_PARTICIPANT_CLOSE：" + name + " 參與者關門之後同艙立刻出發（新 session="
                        + sessionsOf(f.server, chamber) + "）witness=" + witness + " comparator=" + comparator(world, f.frame));
                return;
            }
            witness.put("idleRightAfterTheClose", comparator(world, f.frame) == 3);
            at(c, closeAt + QUIET_TICKS, hooks, () -> {
                if (departed(f.server, chamber)) {
                    origin.failAfterTeardown(closeAt + QUIET_TICKS, "REARM_DEPARTED_BEFORE_PLAYER_RECLOSE：" + name + " 關門之後 " + QUIET_TICKS
                            + " tick 內出發（" + sessionsOf(f.server, chamber) + "）witness=" + witness);
                    return;
                }
                witness.put("stillIdleAfterTheScheduledRefresh", comparator(world, f.frame) == 3);
                witness.put("playerOpens", interact(world, door, p).isAccepted() && doorAll(world, f.frame, true, MembraneState.NONE));
                witness.put("openDoesNotDepart", !departed(f.server, chamber) && comparator(world, f.frame) == 3);
                at(c, closeAt + QUIET_TICKS + 2, hooks, () -> {
                    witness.put("playerCloses", interact(world, door, p).isAccepted() && doorAll(world, f.frame, false, MembraneState.NONE));
                    var next = sessionsOf(f.server, chamber);
                    witness.put("departsAfterThePlayerReclose", next.size() == 1 && !next.contains(sid)
                            && journal.flushedRecords().get(next.getFirst()) != null && journal.flushedRecords().get(next.getFirst()).state() == SessionState.ARMING);
                    if (!departed(f.server, chamber)) {
                        origin.releaseAfter(new GameTestException("REARM_RECLOSE_DID_NOT_DEPART：" + name + " witness=" + witness));
                        return;
                    }
                    f.trustedTeardown(closeAt + QUIET_TICKS + 3, () -> {
                        origin.release();
                        c.assertTrue(!witness.containsValue(false), name + "：" + witness);
                        c.complete();
                    });
                });
            });
        });
    }

    /**
     * spec v1.6 A、V12（兩平台）：PREPARE 期間參與者關門之後，Controller 上鎖、比較器 3（全員有藥效、門關著、已供電也一樣），玩家開門仍是 3；玩家親手關門才解鎖，
     * 比較器離開 3（READY 7：Windows 開始新 session，Ubuntu 的原生 checkpoint 預檢拒絕入場）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_idle", tickLimit = 20000)
    public void participant_close_latch_shows_idle_until_player_reclose(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = Origin.open(c, hooks);
        var f = origin.f;
        var world = c.getWorld();
        var p = f.players.getFirst().player();
        var door = ChamberGeometry.localToWorld(f.frame, 3, 3, 0);
        origin.collapse(false, (closeAt, chamber, sid) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            witness.put("closeAccepted", interact(world, door, p).isAccepted() && doorAll(world, f.frame, false, MembraneState.NONE));
            witness.put("latchedByTheParticipantClose", f.controller().rearmRequiresPlayerClose());
            witness.put("idleRightAfter", comparator(world, f.frame) == 3 && !departed(f.server, chamber));
            at(c, closeAt + QUIET_TICKS, hooks, () -> {
                witness.put("stillIdle", comparator(world, f.frame) == 3 && f.controller().rearmRequiresPlayerClose() && !departed(f.server, chamber));
                witness.put("playerOpens", interact(world, door, p).isAccepted() && doorAll(world, f.frame, true, MembraneState.NONE));
                witness.put("idleWhileOpen", comparator(world, f.frame) == 3 && f.controller().rearmRequiresPlayerClose());
                at(c, closeAt + QUIET_TICKS + 2, hooks, () -> {
                    witness.put("playerCloses", interact(world, door, p).isAccepted() && doorAll(world, f.frame, false, MembraneState.NONE));
                    witness.put("unlockedByThePlayerClose", !f.controller().rearmRequiresPlayerClose());
                    witness.put("leavesIdle", comparator(world, f.frame) == 7);
                    if (departed(f.server, chamber)) {
                        f.trustedTeardown(closeAt + QUIET_TICKS + 3, () -> {
                            origin.release();
                            c.assertTrue(!witness.containsValue(false), "參與者關門之後的比較器：" + witness);
                            c.complete();
                        });
                        return;
                    }
                    witness.put("noCheckpointPlatformStaysWithoutSession", !com.sun.jna.Platform.isWindows());
                    f.power();
                    origin.release();
                    c.assertTrue(!witness.containsValue(false), "參與者關門之後的比較器：" + witness);
                    c.complete();
                });
            });
        });
    }

    /**
     * spec v1.6 A、V12：上鎖之後斷電再供電、停用再啟用、系統改門（一般開門、關門、sealed 量子膜與它的無主正規化）都不解鎖：每一步之後仍上鎖、比較器依狀態是 3 或 0，
     * 沒有出發；之後再過一次 Controller 的排程刷新也一樣。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_survives", tickLimit = 20000)
    public void rearm_latch_survives_power_cycle_enable_toggle_and_system_writes(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        hooks.add(f::close);
        var world = c.getWorld(); var server = f.server;
        var witness = new LinkedHashMap<String, Boolean>();
        var chamber = latchedAndPowered(c, f, hooks);
        var lifecycle = new ChamberLifecycleService(ChamberRegistryState.get(server).registry());
        var doors = new ChamberDoorService();
        at(c, 3, hooks, () -> {
            if (!holds(c, f, hooks, chamber, 3, "poweredLatched", witness)) return;
            f.power();
            at(c, 6, hooks, () -> {
                if (!holds(c, f, hooks, chamber, 0, "lowLatched", witness)) return;
                f.power();
                at(c, 9, hooks, () -> {
                    if (!holds(c, f, hooks, chamber, 3, "repoweredLatched", witness)) return;
                    c.assertTrue(lifecycle.setEnabled(chamber, false), "停用");
                    ChamberControllerBlock.refreshState(world, f.frame.controllerPos());
                    if (!holds(c, f, hooks, chamber, 0, "disabledLatched", witness)) return;
                    c.assertTrue(lifecycle.setEnabled(chamber, true), "啟用");
                    ChamberControllerBlock.refreshState(world, f.frame.controllerPos());
                    if (!holds(c, f, hooks, chamber, 3, "reenabledLatched", witness)) return;
                    witness.put("systemOpens", doors.applyTarget(world, f.frame, ChamberDoorService.DoorState.OPEN).outcome() == ChamberDoorService.ApplyResult.Outcome.APPLIED);
                    if (!holds(c, f, hooks, chamber, 3, "afterSystemOpen", witness)) return;
                    witness.put("systemCloses", doors.applyTarget(world, f.frame, ChamberDoorService.DoorState.CLOSED).outcome() == ChamberDoorService.ApplyResult.Outcome.APPLIED);
                    if (!holds(c, f, hooks, chamber, 3, "afterSystemClose", witness)) return;
                    witness.put("systemSeals", doors.applyTarget(world, f.frame, ChamberDoorService.DoorState.sealed(f.frame.outwardFacing())).outcome()
                            == ChamberDoorService.ApplyResult.Outcome.APPLIED);
                    if (!holds(c, f, hooks, chamber, 3, "afterSystemMembrane", witness)) return;
                    at(c, 13, hooks, () -> {
                        witness.put("ownerlessMembraneNormalizedOpen", doorAll(world, f.frame, true, MembraneState.NONE));
                        if (!holds(c, f, hooks, chamber, 3, "afterOwnerlessNormalization", witness)) return;
                        witness.put("systemClosesAgain", doors.applyTarget(world, f.frame, ChamberDoorService.DoorState.CLOSED).outcome()
                                == ChamberDoorService.ApplyResult.Outcome.APPLIED);
                        if (!holds(c, f, hooks, chamber, 3, "afterSecondSystemClose", witness)) return;
                        at(c, 40, hooks, () -> {
                            if (!holds(c, f, hooks, chamber, 3, "afterScheduledRefresh", witness)) return;
                            finish(c, f, hooks, witness, "latch 經斷電、停用與系統改門不解鎖");
                        });
                    });
                });
            });
        });
    }

    /**
     * spec v1.6 A、V12：上鎖之後玩家親手開門（由關切成開）不解鎖；之後由系統 {@code applyTarget(CLOSED)} 關門，或直接寫方塊關門（等同指令或其他 mod 的寫入，
     * 不經門服務），都不出發、比較器 3。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_open_then_system", tickLimit = 20000)
    public void latch_survives_player_open_then_system_close(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        hooks.add(f::close);
        var world = c.getWorld();
        var p = f.players.getFirst().player();
        var door = ChamberGeometry.localToWorld(f.frame, 3, 3, 0);
        var witness = new LinkedHashMap<String, Boolean>();
        var chamber = latchedAndPowered(c, f, hooks);
        at(c, 3, hooks, () -> {
            if (!holds(c, f, hooks, chamber, 3, "poweredLatched", witness)) return;
            witness.put("playerOpens", interact(world, door, p).isAccepted() && doorAll(world, f.frame, true, MembraneState.NONE));
            if (!holds(c, f, hooks, chamber, 3, "afterPlayerOpen", witness)) return;
            witness.put("systemCloses", new ChamberDoorService().applyTarget(world, f.frame, ChamberDoorService.DoorState.CLOSED).outcome()
                    == ChamberDoorService.ApplyResult.Outcome.APPLIED);
            if (!holds(c, f, hooks, chamber, 3, "afterSystemClose", witness)) return;
            at(c, 6, hooks, () -> {
                if (!holds(c, f, hooks, chamber, 3, "afterRefresh", witness)) return;
                witness.put("playerOpensAgain", interact(world, door, p).isAccepted() && doorAll(world, f.frame, true, MembraneState.NONE));
                if (!holds(c, f, hooks, chamber, 3, "afterSecondPlayerOpen", witness)) return;
                M5GameTestSupport.writeDoor(world, f.frame, false, MembraneState.NONE);
                ChamberControllerBlock.refreshState(world, f.frame.controllerPos());
                witness.put("directWriteClosed", doorAll(world, f.frame, false, MembraneState.NONE));
                if (!holds(c, f, hooks, chamber, 3, "afterDirectWrite", witness)) return;
                at(c, 32, hooks, () -> {
                    if (!holds(c, f, hooks, chamber, 3, "afterScheduledRefresh", witness)) return;
                    finish(c, f, hooks, witness, "玩家開門之後由系統關門");
                });
            });
        });
    }

    /** spec v1.6 A、V12：上鎖時直接呼叫正式 {@code start} 被拒，零副作用（沒有 journal 列、暫占、占用，玩家留在原處、藥效與背包不變）。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_formal_start", tickLimit = 20000)
    public void formal_start_refuses_latched_origin(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        hooks.add(f::close);
        var world = c.getWorld(); var server = f.server;
        var witness = new LinkedHashMap<String, Boolean>();
        var chamber = latchedAndPowered(c, f, hooks);
        at(c, 3, hooks, () -> {
            if (!holds(c, f, hooks, chamber, 3, "poweredLatched", witness)) return;
            var participants = f.players.stream().map(connection -> connection.player().getUuid()).toList();
            var result = dev.quantumchamber.chamber.ChamberSessions.gateway().start(world, f.controller(), participants);
            witness.put("rejected", result == dev.quantumchamber.chamber.ChamberSessionGateway.StartResult.REJECTED);
            if (!holds(c, f, hooks, chamber, 3, "afterFormalStart", witness)) return;
            var gate = (dev.quantumchamber.superposition.SuperpositionSessionManager) dev.quantumchamber.chamber.ChamberSessions.gateway();
            witness.put("noProvisionalOrBusy", !gate.provisionalClaimed(server, chamber) && !gate.globalChamberBusy(server, chamber));
            for (var connection : f.players) {
                var player = connection.player();
                witness.put(player.getUuid() + ".stayedInside", player.getServerWorld() == world
                        && dev.quantumchamber.chamber.ChamberOccupantService.contains(ChamberGeometry.interiorBox(f.frame), player.getBoundingBox()));
                witness.put(player.getUuid() + ".effectKept", player.hasStatusEffect(dev.quantumchamber.registry.ModEffects.QUANTUM_STATE));
            }
            try { f.assertInventory(); witness.put("inventoryKept", true); }
            catch (GameTestException changed) { witness.put("inventoryKept", false); }
            finish(c, f, hooks, witness, "上鎖時的正式 start");
        });
    }

    /** spec v1.6 A、V12：上鎖之後 {@code /reload}（沿 M6 既有 GameTest 的觸發方式：直接觸發 Fabric 的 START／END_DATA_PACK_RELOAD）不解鎖、不出發。 */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m7_home_rearm_reload", tickLimit = 20000)
    public void rearm_latch_survives_datapack_reload(TestContext c) {
        var hooks = new M2CorridorGameTests.TestHooks();
        var f = new M2CorridorGameTests.NativeEntry(c, 1);
        hooks.add(f::close);
        var world = c.getWorld(); var server = f.server;
        var witness = new LinkedHashMap<String, Boolean>();
        var chamber = latchedAndPowered(c, f, hooks);
        at(c, 3, hooks, () -> {
            if (!holds(c, f, hooks, chamber, 3, "poweredLatched", witness)) return;
            if (!(server.getResourceManager() instanceof net.minecraft.resource.LifecycledResourceManager resources))
                throw new GameTestException("RELOAD_FIXTURE：server 的 resource manager 不是 LifecycledResourceManager");
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.START_DATA_PACK_RELOAD.invoker().startDataPackReload(server, resources);
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.invoker().endDataPackReload(server, resources, true);
            ChamberControllerBlock.refreshState(world, f.frame.controllerPos());
            if (!holds(c, f, hooks, chamber, 3, "afterReload", witness)) return;
            at(c, 30, hooks, () -> {
                if (!holds(c, f, hooks, chamber, 3, "afterScheduledRefresh", witness)) return;
                finish(c, f, hooks, witness, "/reload 之後");
            });
        });
    }

    /** NativeEntry 的正式 A（一位有 Buff 的玩家在艙內、門關著）：登記、以 collapse owner 的入口上鎖，再供電。 */
    private static UUID latchedAndPowered(TestContext c, M2CorridorGameTests.NativeEntry f, M2CorridorGameTests.TestHooks hooks) {
        try {
            c.assertTrue(new ChamberActivationService().attemptArm(c.getWorld(), f.controller()).accepted(), "正式 A 登記");
            f.controller().requireRearmPlayerClose();
            f.power();
            return f.controller().chamberUuid();
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
    }

    /**
     * 仍上鎖、比較器是 {@code signal}、沒有出發時記下 {@code step}、回 true。已出發（latch 被錯誤清除）時先以正式 LOW 返還收尾，再以具名錯因失敗，回 false
     * （呼叫端不再排後續步驟）。
     */
    private static boolean holds(TestContext c, M2CorridorGameTests.NativeEntry f, M2CorridorGameTests.TestHooks hooks, UUID chamber, int signal, String step,
            Map<String, Boolean> witness) {
        var world = c.getWorld();
        if (departed(f.server, chamber)) {
            var message = "REARM_LATCH_LOST：" + step + " 之後已出發（" + sessionsOf(f.server, chamber) + "）latch=" + f.controller().rearmRequiresPlayerClose()
                    + " witness=" + witness;
            f.trustedTeardown(Math.toIntExact(c.getTick()) + 1, () -> {
                hooks.release();
                throw new GameTestException(message);
            });
            return false;
        }
        witness.put(step, f.controller().rearmRequiresPlayerClose() && comparator(world, f.frame) == signal);
        return true;
    }

    /** 斷電（仍供電時）、釋放 hooks（含玩家連線），斷言全部成立後完成。 */
    private static void finish(TestContext c, M2CorridorGameTests.NativeEntry f, M2CorridorGameTests.TestHooks hooks, Map<String, Boolean> witness, String label) {
        if (c.getWorld().isReceivingRedstonePower(f.frame.controllerPos())) f.power();
        hooks.release();
        c.assertTrue(!witness.containsValue(false), label + "：" + witness);
        c.complete();
    }

    /** 這座艙在 journal（current、flushed）的所有 session。 */
    static List<UUID> sessionsOf(net.minecraft.server.MinecraftServer server, UUID chamber) {
        var journal = SessionRecoveryState.get(server);
        var result = new ArrayList<UUID>();
        for (var rows : List.of(journal.allSessions(), journal.allFlushedSessions()))
            rows.forEach((sid, row) -> { if (row.chamberUuid().equals(chamber) && !result.contains(sid)) result.add(sid); });
        return result;
    }

    static boolean departed(net.minecraft.server.MinecraftServer server, UUID chamber) {
        return !sessionsOf(server, chamber).isEmpty();
    }

    /** Controller 的比較器輸出（原版讀取路徑）。 */
    static int comparator(ServerWorld world, ChamberFrame frame) {
        var pos = frame.controllerPos();
        return world.getBlockState(pos).getComparatorOutput(world, pos);
    }

    /**
     * {@code NativeEntry}（一位有 Buff 的參與者 P）上的正式 A，與 M5 的 DORMANT NEW 收據。{@link #collapse} 以正式入口塌縮（P 由艙外進入），到 PREPARE 擁有原艙門
     * （sealed 量子膜）或 PASSAGE_READY 時交給呼叫端。收尾：收據的 fixture 移除、log capture 關閉、玩家連線關閉。
     */
    static final class Origin {
        final TestContext c;
        final M2CorridorGameTests.NativeEntry f;
        final M2CorridorGameTests.TestHooks hooks;
        final LogCapture capture;
        final List<UUID> owned = new ArrayList<>();
        private boolean closed;

        private Origin(TestContext c, M2CorridorGameTests.NativeEntry f, M2CorridorGameTests.TestHooks hooks, LogCapture capture) {
            this.c = c; this.f = f; this.hooks = hooks; this.capture = capture;
        }

        /** 收尾依登記的相反順序：收據的 fixture 移除、log capture 關閉，最後關閉玩家連線（{@code trustedTeardown} 也會關閉，重複關閉無作用）。 */
        static Origin open(TestContext c, M2CorridorGameTests.TestHooks hooks) {
            var f = new M2CorridorGameTests.NativeEntry(c, 1);
            hooks.add(f::close);
            var capture = M5GameTestSupport.captureLogs(hooks);
            var origin = new Origin(c, f, hooks, capture);
            hooks.add(() -> M5GameTestSupport.cleanupFixtures(f.server, origin.owned));
            return origin;
        }

        @FunctionalInterface interface Ready { void accept(int tick, UUID chamber, UUID sid); }

        /**
         * 正式登記 A、P 移到艙外、寫入 DORMANT NEW 收據、供電，下一個 tick P 由艙外進入：提交 COLLAPSING、RESOLVE（配置並物化新的宇宙）、進入 PREPARE。
         * {@code passage} 為假時等 PREPARE 擁有原艙門（sealed 量子膜），為真時等 checked PASSAGE_READY。
         */
        void collapse(boolean passage, Ready ready) {
            var world = c.getWorld(); var server = f.server; var journal = SessionRecoveryState.get(server);
            UUID chamber;
            UUID sid;
            try {
                c.assertTrue(new ChamberActivationService().attemptArm(world, f.controller()).accepted(), "正式 A 登記");
                chamber = f.controller().chamberUuid();
                var p = f.players.getFirst().player();
                moveOutside(p, f.frame);
                var receipt = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, f.frame.controllerPos()),
                        Map.of(p.getUuid(), true), List.of(), true);
                sid = receipt.sessionUuid();
                owned.add(sid);
                journal.put(receipt); journal.flush(server);
                f.power();
            } catch (RuntimeException | Error failure) { releaseAfter(failure); throw failure; }
            var p = f.players.getFirst().player();
            at(c, 2, hooks, () -> moveInside(p, f.frame));
            whenTicks(c, 3, COLLAPSE_WAIT_TICKS, () -> {
                var record = journal.flushedRecords().get(sid);
                if (record == null) return false;
                return passage ? record.state() == SessionState.PASSAGE_READY && M5GameTestSupport.doorPassable(world, f.frame)
                        : record.state() == SessionState.COLLAPSING && M5GameTestSupport.doorSealed(world, f.frame);
            }, tick -> ready.accept(tick, chamber, sid), () -> "沒有塌縮到 " + (passage ? "PASSAGE_READY" : "PREPARE（sealed 量子膜）") + "：record="
                    + M5GameTestSupport.state(journal, sid) + " entered=" + capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "進入 PREPARE", sid.toString()).size(),
                    hooks);
        }

        /** 已出發的新 session 以正式 LOW 返還收尾之後，以 {@code message} 失敗（GameTestException）。 */
        void failAfterTeardown(int tick, String message) {
            f.trustedTeardown(tick + 1, () -> {
                release();
                throw new GameTestException(message);
            });
        }

        void release() {
            if (closed) return;
            closed = true;
            hooks.release();
        }

        /** 失敗路徑：釋放 hooks（含玩家連線）之後原樣丟出 {@code failure}；釋放本身的例外附在它上面。 */
        void releaseAfter(Throwable failure) {
            if (!closed) { closed = true; hooks.releaseAfter(failure); }
            if (failure instanceof RuntimeException runtime) throw runtime;
            throw (Error) failure;
        }
    }
}
