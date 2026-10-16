package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.afterAutosaveClear;
import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.candidateDoor;
import static dev.quantumchamber.gametest.M5GameTestSupport.captureLogs;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.collapseFixture;
import static dev.quantumchamber.gametest.M5GameTestSupport.collapseFixtureOwnedBy;
import static dev.quantumchamber.gametest.M5GameTestSupport.collapseFlowNeverWarned;
import static dev.quantumchamber.gametest.M5GameTestSupport.controller;
import static dev.quantumchamber.gametest.M5GameTestSupport.door;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorIs;
import static dev.quantumchamber.gametest.M5GameTestSupport.installFlushProbe;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.noWarnOrError;
import static dev.quantumchamber.gametest.M5GameTestSupport.sourceDoor;
import static dev.quantumchamber.gametest.M5GameTestSupport.state;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;

import com.sun.jna.Platform;
import dev.quantumchamber.candidate.CandidateLedgerService;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberCell;
import dev.quantumchamber.chamber.ChamberMaintenanceInteraction;
import dev.quantumchamber.chamber.ChamberOccupantService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.ChamberPowerState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.gametest.M5GameTestSupport.FlushFault;
import dev.quantumchamber.gametest.M5GameTestSupport.NetherChamber;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.transfer.ChamberReturnPlacement;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.chamber.ChamberDetector;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberDoorService.ApplyResult;
import dev.quantumchamber.chamber.ChamberDoorService.ApplyResult.Outcome;
import dev.quantumchamber.chamber.ChamberDoorService.DoorState;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.chamber.WorldChamberBlockView;
import dev.quantumchamber.registry.ModBlocks;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 的 default run GameTest（plan 決策 6）：只放不配置 Universe、不留 M5 record 的 test。batchId 一律以 {@code m5_collapse_}
 * 開頭（{@code m5_universe} 前綴保留給 F3c 的隔離 run）。fixture 的 Chamber 不供電、不註冊；建置也在 try 之內，
 * 不論成功或失敗都移除自己寫入的方塊。
 */
public final class M5CollapseGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final int QUIET = Block.NOTIFY_LISTENERS | Block.FORCE_STATE;
    private static final ChunkTicketType<ChunkPos> NOT_LOADED_TICKET = ChunkTicketType.create(
            "quantumchamber_m5_door_not_loaded", Comparator.comparingLong(ChunkPos::toLong));

    /**
     * F3b（spec §10.1）：四個朝向各建一座未供電的原艙，驗證 membrane 狀態在世界中的行為：
     * ChamberDetector 對任何 membrane 都是 BULKHEAD_OPEN、只有 sealed 有碰撞、既有的門切換關門時清掉 membrane，
     * 以及「套用目標狀態」API 對 25 格一致套用、指令寫出的關門＋量子膜的讀取、軸向、門板完整性與 server thread 的守門。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_membrane_door")
    public void membrane_door_states_keep_the_detector_open_and_every_close_clears_them(TestContext context) {
        var world = context.getWorld();
        List<String> failures = new ArrayList<>();
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame frame = fixtureFrame(context, facing);
            try {
                witness(failures, facing.asString() + ".fixtureFrame",
                        ChamberGameTestBuilder.buildForSession(context, true, facing, false).equals(frame));
                checkMembraneDoor(context, frame, failures);
            } finally {
                clearChamber(world, frame);
            }
            witness(failures, facing.asString() + ".neverRegistered",
                    ChamberProtectionService.get().originAt(world, frame.controllerPos()).isEmpty());
        }
        assertWitnesses(context, "F3b 量子膜門", failures);
        context.complete();
    }

    /**
     * F3b（plan 決策 10）：寫入被拒時的兩種結果。rollback 成功是 ROLLED_BACK，世界與 activationBlocked 都不變；
     * rollback 也失敗時沿用門切換的 activationBlocked 處理：結果為 ROLLBACK_FAILED、Controller 被禁止啟動，而且不留下
     * open=false＋membrane 的狀態；下一次成功套用就解除。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_apply_rollback")
    public void door_target_rollback_failure_blocks_activation_until_the_next_success(TestContext context) {
        var world = context.getWorld();
        List<String> failures = new ArrayList<>();
        ChamberFrame frame = fixtureFrame(context, Direction.NORTH);
        try {
            witness(failures, "fixtureFrame", ChamberGameTestBuilder.buildForSession(context, true, Direction.NORTH, false).equals(frame));
            var service = new ChamberDoorService();
            List<BlockPos> door = door(frame);
            // DoorWriteFault 拒絕所有開門寫入：關著的門套用一般開門時第一格就失敗、什麼都沒改。
            witness(failures, "refused.setupClosed", service.applyTarget(world, frame, DoorState.CLOSED).outcome() == Outcome.APPLIED);
            ApplyResult refused = DoorWriteFault.during(world, frame, false, () -> service.applyTarget(world, frame, DoorState.OPEN));
            witness(failures, "refused.rolledBack", refused.outcome() == Outcome.ROLLED_BACK && !refused.activationBlocked());
            witness(failures, "refused.controllerNotBlocked", !controller(world, frame).activationBlocked());
            witness(failures, "refused.doorUnchanged", door.stream().allMatch(pos -> doorIs(world, pos, false, MembraneState.NONE)));
            for (boolean throwing : new boolean[] {false, true}) {
                String p = (throwing ? "throw" : "false") + ".";
                witness(failures, p + "setupPassable",
                        service.applyTarget(world, frame, DoorState.passable(Direction.NORTH)).outcome() == Outcome.APPLIED);
                // DoorWriteFault：第 25 格關門寫入失敗、所有回到開門的 rollback 寫入都被拒。
                ApplyResult failed = DoorWriteFault.during(world, frame, throwing,
                        () -> service.applyTarget(world, frame, DoorState.CLOSED));
                witness(failures, p + "rollbackFailed", failed.outcome() == Outcome.ROLLBACK_FAILED);
                witness(failures, p + "activationBlockedReported", failed.activationBlocked());
                witness(failures, p + "controllerBlocked", controller(world, frame).activationBlocked());
                witness(failures, p + "noClosedMembrane", door.stream().map(world::getBlockState)
                        .allMatch(state -> state.get(QuantumBulkheadBlock.OPEN) || state.get(QuantumBulkheadBlock.MEMBRANE) == MembraneState.NONE));
                ApplyResult recovered = service.applyTarget(world, frame, DoorState.OPEN);
                witness(failures, p + "recoveredApplied", recovered.outcome() == Outcome.APPLIED && !recovered.activationBlocked());
                witness(failures, p + "controllerUnblocked", !controller(world, frame).activationBlocked());
                witness(failures, p + "recoveredPlainOpen", door.stream().allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));
            }
        } finally {
            clearChamber(world, frame);
        }
        witness(failures, "neverRegistered", ChamberProtectionService.get().originAt(world, frame.controllerPos()).isEmpty());
        assertWitnesses(context, "F3b 套用目標狀態 rollback", failures);
        context.complete();
    }

    /**
     * F3b（spec §10.1）：任何 bulkhead 狀態都不可被流體取代，所以門口擋得住流體。fixture 不在任何 Chamber 內（不受保護），
     * 只靠方塊本身：每個狀態各一個封閉的 1×3 通道（水源｜bulkhead｜乾格），另一組相同通道直接對 bulkhead 倒水桶。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_fluid", tickLimit = 200)
    public void every_bulkhead_state_holds_back_fluids(TestContext context) {
        var world = context.getWorld();
        BlockState closed = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
        List<BlockState> states = new ArrayList<>(List.of(closed));
        for (MembraneState membrane : MembraneState.values()) {
            states.add(closed.with(QuantumBulkheadBlock.OPEN, true).with(QuantumBulkheadBlock.MEMBRANE, membrane));
        }
        List<BlockPos> flowOrigins = new ArrayList<>();
        List<BlockPos> bucketOrigins = new ArrayList<>();
        for (int index = 0; index < states.size(); index++) {
            int x = 2 + 6 * (index % 2);
            int z = 2 + 4 * (index / 2);
            flowOrigins.add(context.getAbsolutePos(new BlockPos(x, 2, z)));
            bucketOrigins.add(context.getAbsolutePos(new BlockPos(x, 6, z)));
        }
        List<FluidPocket> flows = new ArrayList<>();
        List<FluidPocket> buckets = new ArrayList<>();
        try {
            for (int index = 0; index < states.size(); index++) {
                flows.add(FluidPocket.build(world, flowOrigins.get(index), states.get(index)));
                buckets.add(FluidPocket.build(world, bucketOrigins.get(index), states.get(index)));
            }
            // 方塊都放好之後才放水源；setBlockState 的 NOTIFY_ALL 會排程流體 tick。
            flows.forEach(pocket -> world.setBlockState(pocket.wet(), Blocks.WATER.getDefaultState()));
        } catch (RuntimeException | Error failure) {
            flowOrigins.forEach(origin -> FluidPocket.clearAt(world, origin));
            bucketOrigins.forEach(origin -> FluidPocket.clearAt(world, origin));
            throw failure;
        }
        context.waitAndRun(30, () -> {
            List<String> failures = new ArrayList<>();
            try {
                for (var pocket : flows) {
                    String p = label(pocket.state()) + ".flow.";
                    witness(failures, p + "sourceKept", world.getFluidState(pocket.wet()).isStill());
                    witness(failures, p + "bulkheadUnchanged", world.getBlockState(pocket.bulkhead()).equals(pocket.state()));
                    witness(failures, p + "farSideDry", dry(world, pocket.dry()));
                }
                for (var pocket : buckets) {
                    String p = label(pocket.state()) + ".bucket.";
                    boolean placed = ((BucketItem) Items.WATER_BUCKET).placeFluid(null, world, pocket.bulkhead(), null);
                    witness(failures, p + "rejected", !placed);
                    witness(failures, p + "bulkheadUnchanged", world.getBlockState(pocket.bulkhead()).equals(pocket.state()));
                    witness(failures, p + "farSideDry", dry(world, pocket.dry()));
                }
            } finally {
                flowOrigins.forEach(origin -> FluidPocket.clearAt(world, origin));
                bucketOrigins.forEach(origin -> FluidPocket.clearAt(world, origin));
            }
            assertWitnesses(context, "F3b 流體", failures);
            context.complete();
        });
    }

    /**
     * F3b fix round 1–2：門板周圍水平 ±3 格（local x −2～8、z −3～3，含 Controller）所在的 chunk 有一個不是 FULL 時，
     * applyTarget 不讀不寫、回 NOT_LOADED，也不強制載入。沿用 ChamberNoForceGameTests 的手法：遠處的 chunk 以 radius 0 的
     * ticket 維持 FULL，鄰接的 chunk 不是 FULL；fixture 的方塊直接寫進 chunk（不經 World.setBlockState），不觸發鄰格更新，
     * 也不碰未載入的 chunk。門一律朝北：local x 增加是往西，local z 增加（艙內）是往南。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_not_loaded", tickLimit = 100)
    public void door_target_refuses_unloaded_chunks_without_forcing_them(TestContext context) {
        var world = context.getWorld();
        List<String> failures = new ArrayList<>();
        // A：門板 local x=1 那一欄落在東側未載入的 chunk；只有 20 格門在已載入的 chunk。
        checkNotLoaded(world, failures, "straddle", List.of(new ChunkPos(2176, 2048)), new ChunkPos(2177, 2048), 20,
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 14, 70, loaded.getStartZ() + 8), Direction.NORTH));
        // B：門板與 Controller 都在已載入的 chunk，但門外那一排（z=−1，寫入時的 neighbor update 會讀）在北側未載入的 chunk。
        checkNotLoaded(world, failures, "outsideRow", List.of(new ChunkPos(2240, 2048)), new ChunkPos(2240, 2047), 25,
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 8, 70, loaded.getStartZ()), Direction.NORTH));
        // C：只有外殼外 x=8 那一欄落在西側的 chunk、只有艙內 z=+3 那一排落在南側的 chunk，兩者都已載入；只有西南的對角 chunk
        // 未載入，它只被 x=8、z=+3 的角落涵蓋，所以同時鎖住西側與南側的 ±3 邊界。
        checkNotLoaded(world, failures, "diagonal", List.of(new ChunkPos(2304, 2048), new ChunkPos(2303, 2048), new ChunkPos(2304, 2049)),
                new ChunkPos(2303, 2049), 25,
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 4, 70, loaded.getStartZ() + 13), Direction.NORTH));
        // D：只有外殼外 x=−2 那一欄落在東側未載入的 chunk（x=−1 仍在已載入的 chunk）。
        checkNotLoaded(world, failures, "eastEdge", List.of(new ChunkPos(2368, 2048)), new ChunkPos(2369, 2048), 25,
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 11, 70, loaded.getStartZ() + 8), Direction.NORTH));
        // E：只有門外 z=−3 那一排落在北側未載入的 chunk（z=−2 仍在已載入的 chunk）。
        checkNotLoaded(world, failures, "northEdge", List.of(new ChunkPos(2432, 2048)), new ChunkPos(2432, 2047), 25,
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 8, 70, loaded.getStartZ() + 2), Direction.NORTH));
        assertWitnesses(context, "F3b 未載入 chunk", failures);
        context.complete();
    }

    // ---- F3d：CollapseService（spec §7、§11.1、§11.3、§12）。只選 SOURCE 的門或用不配置宇宙的 fixture；結束時不留 M5 record、runtime 或
    // ticket，也不改 catalog 與 world keys（M4PerTestUniverseProbe 逐 test 核對）。 ----

    /** spec §10.8「塌縮」（以字面值斷言，不信任 production 常數）。 */
    private static final String COLLAPSED_KEY = "message.quantumchamber.m5.collapsed";

    /**
     * F3d（spec §7.1、§7.3、§7.4、§5 終局、H4、R2 M6）：兩位玩家真入場、選一扇 SOURCE 門。選門的同一個 tick 仍是 MEASURED，下一個 tick 已是
     * checked COLLAPSING（Destination=SOURCE；提交的寫出持有這個 session 的 operation guard，spec §7），兩人都收到「量子態塌縮。」；未返還的兩人被凍結，同一個 tick 各自收到一則位置修正（節流以玩家
     * 為單位）。第一位玩家的預設返還 slot 放了一塊石頭：返還落點避開它，兩人都完整在艙內、不與方塊碰撞。返還與清理完成之後走 SOURCE fast
     * path：record checked 移除、25 格門是一般開門、記一則 INFO、presence 回 NONE、runtime 與來源 ticket 已釋放、catalog 不變；關上門之後，
     * 同一座艙立即開始新的 session。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_source_live", tickLimit = 100000)
    public void native_source_selection_collapses_returns_the_cohort_and_opens_the_door_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_source_selection_collapses_returns_the_cohort_and_opens_the_door_windows");
        nativeSourceEntry(context, 2, (fixture, ready) -> {
            var server = fixture.server; var world = context.getWorld(); var journal = SessionRecoveryState.get(server);
            var before = fixture.record(); var sid = before.sessionUuid(); var chamber = before.chamberUuid();
            var first = fixture.players.getFirst().player();
            boolean[] tearingDown = {false};
            var hooks = new M2CorridorGameTests.TestHooks();
            hooks.add(() -> { if (!tearingDown[0]) fixture.close(); });
            LogCapture capture;
            BlockPos stone; Vec3d blockedSlot;
            ActionResult selected; SessionRecoveryRecord measured;
            // spec §7（:330）：live 的提交寫出時持有這個 session 的 operation guard。探針在 MEASURED→COLLAPSING 那次 journal 寫出的開頭記下 guard。
            var guardAtCommit = new ArrayList<Boolean>();
            java.util.function.Consumer<SessionRecoveryState> commitProbe = state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                if (staged != null && durable != null && staged.state() == SessionState.COLLAPSING && durable.state() == SessionState.MEASURED)
                    guardAtCommit.add(fixture.pages.operationInProgress(sid));
            };
            try {
                capture = captureLogs(hooks);
                installFlushProbe(context, commitProbe, hooks);
                // R2 M6：第一位玩家的預設返還 slot（原本的地板配置）放一塊石頭。
                var sources = new LinkedHashMap<UUID, Vec3d>();
                before.participants().forEach(person -> sources.put(person.playerUuid(), person.sourcePosition()));
                blockedSlot = ChamberReturnPlacement.plan(fixture.frame, List.copyOf(sources.keySet()), sources).get(first.getUuid());
                stone = BlockPos.ofFloored(blockedSlot);
                var placed = stone;
                setAuthorized(world, placed, Blocks.STONE.getDefaultState());
                hooks.add(() -> setAuthorized(world, placed, Blocks.AIR.getDefaultState()));
                var door = sourceDoor(fixture);
                fixture.players.forEach(M5CollapseGameTests::overlayKeys);
                selected = interact(fixture.target, door.cell(), first);
                measured = fixture.record();
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            witness.put("selectedStillMeasuredThisTick", selected.isAccepted() && measured.state() == SessionState.MEASURED);
            at(context, ready + 1, hooks, () -> {
                var collapsing = journal.flushedRecords().get(sid);
                witness.put("committedNextTick", collapsing != null && collapsing.state() == SessionState.COLLAPSING
                        && collapsing.collapse().destination() instanceof CollapseDestination.Source
                        && collapsing.candidateSelection().equals(measured.candidateSelection())
                        && collapsing.candidateLedger().equals(measured.candidateLedger()));
                witness.put("commitInsideOperationGuard", guardAtCommit.equals(List.of(true)));
                for (var connection : fixture.players)
                    witness.put(label(connection) + ".collapsedMessage", overlayKeys(connection).contains(COLLAPSED_KEY));
                // H4（交給 F3d）：同一個 tick 兩位未返還者各自收到一則位置修正。
                for (var connection : fixture.players) {
                    var player = connection.player(); var anchor = player.getPos();
                    witness.put(label(connection) + ".frozen", SessionRecoveryManager.blocks(player.getUuid(), server));
                    connection.drainOutbound(PlayerPositionLookS2CPacket.class);
                    player.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.Full(anchor.x + 0.125, anchor.y, anchor.z, player.getYaw() + 1,
                            player.getPitch(), false));
                    witness.put(label(connection) + ".serverPositionHeld", player.getPos().equals(anchor));
                }
                for (var connection : fixture.players)
                    witness.put(label(connection) + ".oneCorrectionThisTick", connection.drainOutbound(PlayerPositionLookS2CPacket.class).size() == 1);
                context.assertTrue(!witness.containsValue(false), "選門之後的下一個 tick：" + witness);
                whenTicks(context, ready + 2, 20000, () -> !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid), gone -> {
                    var after = new LinkedHashMap<String, Boolean>();
                    after.put("doorPlainOpen", doorAll(world, fixture.frame, true, MembraneState.NONE));
                    after.put("diskHasNoRecord", !M4CandidateTestAccess.diskRecords(server).containsKey(sid));
                    after.put("oneCommitInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", sid.toString()).size() == 1);
                    after.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                    after.put("noWarnOrError", noWarnOrError(capture, sid));
                    after.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    after.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                    after.put("firstAvoidedTheStone", !first.getPos().equals(blockedSlot));
                    for (var connection : fixture.players) {
                        var player = connection.player();
                        after.put(label(connection) + ".returnedInsideWithoutCollision", player.getServerWorld() == world
                                && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame), player.getBoundingBox())
                                && world.isSpaceEmpty(player.getBoundingBox()));
                        after.put(label(connection) + ".notFrozen", !SessionRecoveryManager.blocks(player.getUuid(), server));
                        after.put(label(connection) + ".fastPathMessage", overlayKeys(connection).contains(COLLAPSED_KEY));
                    }
                    // 終局之後同一座艙立即可以開新 session：關上門（仍供電、全員有 Buff、都在艙內）就重新入場。
                    interact(world, ChamberGeometry.localToWorld(fixture.frame, 3, 3, 0), first);
                    var next = fixture.record();
                    after.put("newSessionStarted", next != null && !next.sessionUuid().equals(sid));
                    context.assertTrue(!after.containsValue(false), "SOURCE fast path 與終局之後：" + after);
                    tearingDown[0] = true;
                    hooks.release();
                    fixture.trustedTeardown(gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                }, () -> "SOURCE 塌縮沒有完成：record=" + state(journal, sid) + " space=" + (M4CandidateTestAccess.space(fixture.pages, sid) != null), hooks);
            });
        });
    }

    /**
     * F3d（spec §6 roll-forward、§7「只收斂」、controller 補充 3、4、12）：兩位玩家選 SOURCE 門之後，第一位的返還寫出在 flush 開頭受控失敗。
     * current 帶著他的返還、flushed 還沒有，collapse view 讓他先解除凍結；第二位仍凍結在走廊，在這份寫出確認之前不被疊寫返還。journal 的
     * RETRY_FLUSH 以 200 ticks 起跳的退避重試同一份 current，WARN 只記一次、帶 root cause 與 stack。確認之後第二位才返還。之後清 lease 的
     * 寫出也受控失敗一次：同一個 tick 內 RETRY_FLUSH 寫出 SETTLED，但走廊 space 下一個 tick 才釋放——這個 tick 塌縮流程不得離開 SETTLED
     * （record 仍在），下一個 tick 才走 SOURCE fast path，space、slot 與 ticket 都沒有洩漏。在那個清理中的 tick，已返還的人打開原艙門，
     * 塌縮不因此結束（spec §3 詮釋 3）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_return_roll_forward", tickLimit = 100000)
    public void native_return_write_fault_retries_only_the_same_write_then_settles_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_return_write_fault_retries_only_the_same_write_then_settles_windows");
        nativeSourceEntry(context, 2, (fixture, ready) -> {
            var server = fixture.server; var journal = SessionRecoveryState.get(server);
            var sid = fixture.record().sessionUuid(); var slots = fixture.record().spaceLeases().stream().map(SessionRecoveryRecord.SpaceLease::slotId).toList();
            boolean[] tearingDown = {false};
            var hooks = new M2CorridorGameTests.TestHooks();
            hooks.add(() -> { if (!tearingDown[0]) fixture.close(); });
            // 返還寫出的受控失敗（spec §6 roll-forward）：COLLAPSING 的 current 比 flushed 多返還一位時，第一次寫出在 flush 開頭失敗；之後同一份
            // current（仍只多那一位）的寫出照常、記下 tick（RETRY_FLUSH）。清 lease 的寫出（current 已清空、flushed 仍有 lease）也受控失敗一次。
            // 兩者互斥（全員返還之後才會清 lease），依序檢查。
            var returnFault = new FlushFault(server, RETURN_FAULT, 1, state -> {
                var current = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return bothCollapsing(current, durable) && returnedCount(current) == 1 && returnedCount(durable) == 0;
            });
            var releaseFault = new FlushFault(server, RELEASE_FAULT, 1, state -> {
                var current = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return bothCollapsing(current, durable) && current.spaceLeases().isEmpty() && !durable.spaceLeases().isEmpty();
            });
            LogCapture capture;
            try {
                capture = captureLogs(hooks);
                installFlushProbe(context, returnFault.andThen(releaseFault), hooks);
                interact(fixture.target, sourceDoor(fixture).cell(), fixture.players.getFirst().player());
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            boolean[] overwrote = {false};
            at(context, ready + 1, hooks, () -> context.assertTrue(state(journal, sid).equals("COLLAPSING"),
                    "選門之後的下一個 tick 應已 checked COLLAPSING：record=" + state(journal, sid)));
            whenTicks(context, ready + 1, 400, () -> returnFault.failures() > 0, faulted -> {
                var current = journal.records().get(sid); var flushed = journal.flushedRecords().get(sid);
                var view = journal.collapseView(sid).orElseThrow();
                var returned = current.participants().stream().filter(SessionRecoveryRecord.Participant::returned).map(SessionRecoveryRecord.Participant::playerUuid).toList();
                witness.put("currentCarriesOneReturn", returned.size() == 1);
                witness.put("flushedHasNoReturn", flushed.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned));
                witness.put("rollForwardPending", journal.rollForwardPending(sid) && view.equals(current));
                for (var connection : fixture.players) {
                    var id = connection.player().getUuid();
                    witness.put(label(connection) + ".frozenFollowsCollapseView", SessionRecoveryManager.blocks(id, server) != returned.contains(id));
                }
                // 寫出確認之前：每個 tick 檢查沒有疊寫第二位的返還；確認的時點（重試或原生 autosave 帶出）之後才繼續。
                whenTicks(context, faulted + 1, 600, () -> {
                    var stagedNow = journal.records().get(sid); var durableNow = journal.flushedRecords().get(sid);
                    if (durableNow.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned)
                            && stagedNow.participants().stream().filter(SessionRecoveryRecord.Participant::returned).count() != 1) overwrote[0] = true;
                    return durableNow.participants().stream().anyMatch(SessionRecoveryRecord.Participant::returned);
                }, confirmed -> {
                    var retries = returnFault.retries(); int faultTick = returnFault.first();
                    witness.put("neverOverwroteAnotherReturn", !overwrote[0]);
                    witness.put("noRetryBeforeBackoff", retries.stream().allMatch(tick -> tick >= faultTick + 200));
                    witness.put("confirmedNotBeforeBackoff", confirmed >= 0 && (retries.isEmpty() || retries.getFirst() >= faultTick + 200));
                    var pending = capture.matching(org.apache.logging.log4j.Level.WARN, "recovery 寫出保留 pending", sid.toString());
                    witness.put("oneWarnWithCause", pending.size() == 1 && pending.getFirst().thrown() != null
                            && pending.getFirst().message().contains(RETURN_FAULT));
                    // 清 lease 的寫出也失敗一次：第一次看到 SETTLED 的那個 tick，space 還在、record 還在（沒有離開 SETTLED）。record 若在
                    // 同一個 tick 已經不在（提早離開 SETTLED），也在這裡停下來取證。
                    whenTicks(context, confirmed + 1, 20000, () -> settled(journal.flushedRecords().get(sid)) || !journal.flushedRecords().containsKey(sid), settledTick -> {
                        witness.put("releaseFaultedOnce", releaseFault.failures() == 1);
                        witness.put("settledWhileSpaceRemains", M4CandidateTestAccess.space(fixture.pages, sid) != null
                                && !fixture.pages.measuredRecoveryComplete(sid) && journal.flushedRecords().containsKey(sid));
                        // spec §3 詮釋 3：清理期間（RELEASE_GEOMETRY）門是一般狀態，已返還的人可以開門，塌縮不因此結束。
                        var cell = ChamberGeometry.localToWorld(fixture.frame, 3, 3, 0);
                        witness.put("releaseDoorOpens", interact(context.getWorld(), cell, fixture.players.getFirst().player()).isAccepted()
                                && doorAll(context.getWorld(), fixture.frame, true, MembraneState.NONE));
                        witness.put("releaseDoorKeepsCollapse", journal.flushedRecords().containsKey(sid) && journal.records().containsKey(sid));
                        whenTicks(context, settledTick + 1, 40, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                            witness.put("leftSettledOnlyAfterSpaceReleased", gone == settledTick + 1);
                            witness.put("noSpaceSlotOrTicketLeak", M4CandidateTestAccess.space(fixture.pages, sid) == null
                                    && slots.stream().noneMatch(((Map<?, ?>) M4CandidateTestAccess.get(fixture.pages, "protectedLeases"))::containsKey)
                                    && slots.stream().noneMatch(((Map<?, ?>) M4CandidateTestAccess.get(M4CandidateTestAccess.get(fixture.pages, "allocator"), "leases"))::containsKey)
                                    && !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                            witness.put("doorPlainOpen", doorAll(context.getWorld(), fixture.frame, true, MembraneState.NONE));
                            context.assertTrue(!witness.containsValue(false), "返還與清 lease 的 roll-forward：" + witness + " faultTick=" + faultTick
                                    + " retries=" + retries + " confirmed=" + confirmed);
                            tearingDown[0] = true;
                            hooks.release();
                            powerOffAndClose(context, fixture, gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                        }, () -> "SETTLED 之後沒有走 SOURCE fast path：record=" + state(journal, sid), hooks);
                    }, () -> "返還確認之後沒有完成清理：record=" + state(journal, sid) + " releaseFaults=" + releaseFault.failures(), hooks);
                }, () -> "返還寫出一直沒有確認：retries=" + returnFault.retries(), hooks);
            }, () -> "返還寫出的受控失敗沒有命中：record=" + state(journal, sid), hooks);
        });
    }

    /**
     * F3d（spec §2、§3 詮釋 3、§12、Review Focus 7）：兩位玩家選 SOURCE 門，第二位在選門的同一個 tick 斷線。塌縮照常提交，第一位返還；
     * 第二位未返還，走廊不消失、塌縮停在 RETURN_PLAYERS。已返還的第一位可以開關原艙門，塌縮不結束；他的 Buff 被移除也不丟例外、不記 ERROR。
     * 第二位重新連線之後，在 JOIN 的下一個 tick 返還（以 journal 寫出的探針記下他的返還寫出所在的 server tick，必須恰為 JOIN 的 tick＋1），
     * 清理完成後走 SOURCE fast path。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_offline_rejoin", tickLimit = 100000)
    public void native_offline_participant_rejoins_and_returns_while_the_collapse_holds_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_offline_participant_rejoins_and_returns_while_the_collapse_holds_windows");
        nativeSourceEntry(context, 2, (fixture, ready) -> {
            var server = fixture.server; var world = context.getWorld(); var journal = SessionRecoveryState.get(server);
            var sid = fixture.record().sessionUuid();
            var online = fixture.players.getFirst(); var offline = fixture.players.get(1);
            var profile = offline.player().getGameProfile(); var absent = offline.player().getUuid();
            ConnectedGameTestPlayer[] rejoined = {null};
            boolean[] tearingDown = {false};
            var hooks = new M2CorridorGameTests.TestHooks();
            hooks.add(() -> { if (!tearingDown[0]) { fixture.close(); offline.close(); if (rejoined[0] != null) rejoined[0].close(); } });
            LogCapture capture;
            try {
                capture = captureLogs(hooks);
                interact(fixture.target, sourceDoor(fixture).cell(), online.player());
                fixture.players.remove(offline);
                offline.disconnect();
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, ready + 1, 400, () -> returned(journal.flushedRecords().get(sid), online.player().getUuid()), onlineBack -> {
                var record = journal.flushedRecords().get(sid);
                witness.put("collapsingHoldsForTheOffline", record.state() == SessionState.COLLAPSING && !returned(record, absent));
                witness.put("corridorKept", M4CandidateTestAccess.space(fixture.pages, sid) != null && !record.spaceLeases().isEmpty());
                witness.put("returnedNotFrozen", !SessionRecoveryManager.blocks(online.player().getUuid(), server));
                witness.put("offlineStillOwned", !journal.participantsAvailable(List.of(absent)));
                // spec §3 詮釋 3：返還期間門維持一般狀態，已返還的人可以開關；塌縮不結束。
                var cell = ChamberGeometry.localToWorld(fixture.frame, 3, 3, 0);
                witness.put("openAccepted", interact(world, cell, online.player()).isAccepted() && doorAll(world, fixture.frame, true, MembraneState.NONE));
                witness.put("openKeepsCollapse", record.equals(journal.flushedRecords().get(sid)));
                // Review Focus 7：COLLAPSING 期間 Buff 到期，tick 不丟例外。
                online.player().removeStatusEffect(ModEffects.QUANTUM_STATE);
                at(context, onlineBack + 1, hooks, () -> {
                    witness.put("closeAccepted", interact(world, cell, online.player()).isAccepted() && doorAll(world, fixture.frame, false, MembraneState.NONE));
                    witness.put("closeKeepsCollapse", record.equals(journal.flushedRecords().get(sid)));
                    at(context, onlineBack + 6, hooks, () -> {
                        witness.put("buffLapseNoErrorOrReconcileWarn", capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty()
                                && capture.matching(org.apache.logging.log4j.Level.WARN, "session 協調失敗", record.chamberUuid().toString()).isEmpty());
                        witness.put("buffLapseKeepsCollapse", record.equals(journal.flushedRecords().get(sid)));
                        context.assertTrue(!witness.containsValue(false), "離線參與者未返還期間：" + witness);
                        // spec §12 COLLAPSING 列：重新連線的人在 JOIN 的下一個 tick 返還。探針在 journal 寫出的開頭記下他的返還第一次寫出的 tick。
                        int[] returnTick = {-1};
                        java.util.function.Consumer<SessionRecoveryState> returnProbe = state -> {
                            if (returnTick[0] < 0 && returned(state.records().get(sid), absent) && !returned(state.flushedRecords().get(sid), absent))
                                returnTick[0] = server.getTicks();
                        };
                        installFlushProbe(context, returnProbe, hooks);
                        rejoined[0] = new ConnectedGameTestPlayer(world, profile);
                        int joined = rejoined[0].joinTick();
                        whenTicks(context, onlineBack + 7, 20000, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                            var after = new LinkedHashMap<String, Boolean>();
                            var back = rejoined[0].player();
                            after.put("rejoinedReturnedInside", back.getServerWorld() == world
                                    && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame), back.getBoundingBox()));
                            after.put("returnedOnTheTickAfterJoin", joined >= 0 && returnTick[0] == joined + 1);
                            after.put("doorPlainOpen", doorAll(world, fixture.frame, true, MembraneState.NONE));
                            after.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                            after.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                            context.assertTrue(!after.containsValue(false), "重新連線之後：" + after + " joinTick=" + joined + " returnTick=" + returnTick[0]);
                            tearingDown[0] = true;
                            hooks.release();
                            fixture.players.add(rejoined[0]);
                            powerOffAndClose(context, fixture, gone + 1, () -> { offline.close(); M4PerTestUniverseProbe.complete(context); });
                        }, () -> "重新連線之後塌縮沒有完成：record=" + state(journal, sid), hooks);
                    });
                });
            }, () -> "在線的參與者沒有返還：record=" + state(journal, sid), hooks);
        });
    }

    /**
     * F3d（spec §5 的唯一性規則）：玩家 P 是塌縮 fixture A 的已返還參與者（A 還有一位未返還、離線的參與者），也是 SEALED fixture A′ 的已返還
     * 參與者；兩者都不佔用 P，所以 P 可以在真的原艙開始 session B。P 斷線之後 B 寫成 checked RETURNING（主要經 SSM 的 cohort 檢查失敗走
     * {@code fail}），A 與 A′ 都沒被改寫（塌縮狀態的 record 本來就不經斷線的 {@code markReturning} 寫入）。P 重新連線後返還，B 完成收尾；
     * A、A′ 以終局 API 移除。本 test 只斷言這個整體結果；「依玩家查找只找到佔用他的 session」的直接斷言在 F3a 的
     * {@code collapse_states_attach_without_returning_and_lookup_only_occupying_sessions}。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_returned_elsewhere", tickLimit = 100000)
    public void native_returned_participant_starts_elsewhere_and_only_the_active_session_returns_him_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_returned_participant_starts_elsewhere_and_only_the_active_session_returns_him_windows");
        var fixture = new M2CorridorGameTests.NativeEntry(context, 1, Direction.NORTH);
        var server = fixture.server; var world = context.getWorld(); var journal = SessionRecoveryState.get(server);
        var connection = fixture.players.getFirst(); var p = connection.player().getUuid(); var profile = connection.player().getGameProfile();
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        ConnectedGameTestPlayer[] rejoined = {null};
        boolean[] tearingDown = {false};
        hooks.add(() -> { if (!tearingDown[0]) { fixture.close(); if (rejoined[0] != null) rejoined[0].close(); } });
        hooks.add(() -> cleanupFixtures(server, owned));
        SessionRecoveryRecord a, sealed;
        try {
            var far = context.getAbsolutePos(new BlockPos(0, 2, 0)).add(4000, 0, 4000);
            a = collapseFixture(server, M4CandidateTestAccess.origin(UUID.randomUUID(), far), Map.of(p, true, UUID.randomUUID(), false),
                    List.of(new SessionRecoveryRecord.SpaceLease(10021, new BlockBox(1000, 80, 1000, 1006, 86, 1006))), false, SessionState.COLLAPSING, true);
            owned.add(a.sessionUuid());
            sealed = collapseFixture(server, M4CandidateTestAccess.origin(UUID.randomUUID(), far.add(32, 0, 0)), Map.of(p, true), List.of(), true,
                    SessionState.SEALED, true);
            owned.add(sealed.sessionUuid());
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        try {
            context.assertTrue(journal.participantsAvailable(List.of(p)), "已返還的塌縮與 SEALED 參與者不佔用 P（spec §5）");
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        var witness = new LinkedHashMap<String, Boolean>();
        context.waitAndRun(2, fixture::power);
        whenTicks(context, 3, 20000, fixture::activeReady, active -> {
            var b = fixture.record();
            witness.put("sessionBOwnsP", b.participants().stream().anyMatch(person -> person.playerUuid().equals(p)));
            witness.put("fixturesUnchanged", a.equals(journal.flushedRecords().get(a.sessionUuid())) && sealed.equals(journal.flushedRecords().get(sealed.sessionUuid())));
            witness.put("pOccupiedByBOnly", !journal.participantsAvailable(List.of(p)));
            fixture.players.remove(connection);
            connection.disconnect();
            whenTicks(context, active + 1, 200, () -> fixture.record() != null && fixture.record().state() == SessionState.RETURNING, returning -> {
                witness.put("disconnectReturnsOnlyB", a.equals(journal.flushedRecords().get(a.sessionUuid()))
                        && sealed.equals(journal.flushedRecords().get(sealed.sessionUuid())));
                context.assertTrue(!witness.containsValue(false), "已返還參與者的唯一性：" + witness);
                // 先斷電，再讓 P 回來：B 收尾之後不會因為仍供電而重新入場。
                fixture.power();
                rejoined[0] = new ConnectedGameTestPlayer(world, profile);
                whenTicks(context, returning + 1, 20000, () -> fixture.record() == null
                        && ChamberSessions.gateway().presence(server, b.chamberUuid()) == ChamberSessionGateway.Presence.NONE, released -> {
                    var back = rejoined[0].player();
                    context.assertTrue(back.getServerWorld() == world && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame),
                            back.getBoundingBox()), "P 重新連線之後由 B 送回原艙");
                    tearingDown[0] = true;
                    hooks.release();
                    try { M4PerTestUniverseProbe.complete(context); } finally { fixture.close(); rejoined[0].close(); }
                }, () -> "B 沒有完成收尾：record=" + (fixture.record() == null ? "ABSENT" : fixture.record().state()), hooks);
            }, () -> "斷線之後 B 沒有進入 RETURNING：record=" + (fixture.record() == null ? "ABSENT" : fixture.record().state()), hooks);
        }, () -> "B 沒有入場：record=" + (fixture.record() == null ? "ABSENT" : fixture.record().state()), hooks);
    }

    /**
     * F3d（spec §7.1、§7.3、§7.4、§11.1）：真原艙上的 DORMANT SOURCE 收據（參與者 P 在線、Q 是活動中 synthetic session B 的參與者）。
     * 邊緣觸發：未供電時 P 進入不觸發；停用時 P 進入不觸發；P 一直待在艙內不觸發（不是每個 tick）；供電而且 enabled、P 由艙外進入才提交
     * COLLAPSING——Q 在別的 session 不影響。提交之後在閘門第一次評估前斷電：停在 WAITING，P 開關門不結束塌縮，恢復供電也不自行繼續；
     * P 再次由艙外進入，才走 SOURCE fast path。最後，另一份 DORMANT 收據若是「開服 attach 當下就在 journal」，第一次觀察到 P 已在艙內即觸發。
     * 不需要原生 checkpoint（沒有玩家移動），各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_dormant_edge", tickLimit = 100000)
    public void dormant_source_receipt_resumes_only_on_a_powered_enabled_entry_edge(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"dormant_source_receipt_resumes_only_on_a_powered_enabled_entry_edge");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var power = DormantOrigin.POWER;
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var frame = origin.frame; var connection = origin.connection; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var q = UUID.randomUUID();
            var busy = M4CandidateTestAccess.syntheticSession(server, q, 10031);
            var other = origin.own(busy.withProgress(busy.participants(), busy.spaceLeases(), SessionState.RETURNING, false));
            var dormant = origin.own(origin.receipt(chamber, new LinkedHashMap<>(Map.of(p.getUuid(), true, q, true)), false));
            var sid = dormant.sessionUuid();
            var witness = new LinkedHashMap<String, Boolean>();
            int t = registered + 1;
            // (a) 未供電：P 由艙外進入，不觸發。
            at(context, t, hooks, () -> context.setBlockState(power, Blocks.AIR));
            at(context, t + 1, hooks, () -> moveInside(p, frame));
            at(context, t + 4, hooks, () -> {
                witness.put("unpoweredEntryIgnored", dormant.equals(journal.flushedRecords().get(sid)));
                moveOutside(p, frame);
                context.setBlockState(power, Blocks.REDSTONE_BLOCK);
                ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), p);
                witness.put("disabledForTheStep", !originEnabled(server, chamber));
            });
            // (b) 停用：P 由艙外進入，不觸發。
            at(context, t + 6, hooks, () -> moveInside(p, frame));
            at(context, t + 9, hooks, () -> {
                witness.put("disabledEntryIgnored", dormant.equals(journal.flushedRecords().get(sid)));
                ChamberMaintenanceInteraction.toggleEnabled(world, frame.controllerPos(), p);
                witness.put("enabledAgain", originEnabled(server, chamber));
            });
            // (c) 供電、enabled，但 P 一直在艙內：沒有新的邊緣，不觸發。
            at(context, t + 13, hooks, () -> {
                witness.put("stayingInsideIgnored", dormant.equals(journal.flushedRecords().get(sid)));
                witness.put("noCommitYet", capture.matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", sid.toString()).isEmpty());
                moveOutside(p, frame);
                overlayKeys(connection);
            });
            // (d) P 由艙外進入：同一個 tick 結束時提交（Q 在別的 session 不影響）。
            at(context, t + 14, hooks, () -> moveInside(p, frame));
            at(context, t + 15, hooks, () -> {
                var committed = journal.flushedRecords().get(sid);
                witness.put("entryEdgeCommits", committed != null && committed.state() == SessionState.COLLAPSING
                        && committed.collapse().destination() instanceof CollapseDestination.Source);
                witness.put("busyParticipantDidNotBlock", other.equals(journal.flushedRecords().get(other.sessionUuid())));
                witness.put("dormantResumeTakesTicket", M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("collapsedMessage", overlayKeys(connection).contains(COLLAPSED_KEY));
                // 在閘門第一次評估之前斷電：停在 WAITING。
                context.setBlockState(power, Blocks.AIR);
            });
            at(context, t + 17, hooks, () -> {
                witness.put("waitingWhileUnpowered", state(journal, sid).equals("COLLAPSING"));
                var cell = ChamberGeometry.localToWorld(frame, 3, 3, 0);
                witness.put("waitingDoorOpens", interact(world, cell, p).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                witness.put("doorDoesNotEndWaiting", state(journal, sid).equals("COLLAPSING"));
            });
            at(context, t + 18, hooks, () -> {
                var cell = ChamberGeometry.localToWorld(frame, 3, 3, 0);
                witness.put("waitingDoorCloses", interact(world, cell, p).isAccepted() && doorAll(world, frame, false, MembraneState.NONE));
                context.setBlockState(power, Blocks.REDSTONE_BLOCK);
            });
            at(context, t + 22, hooks, () -> {
                witness.put("powerBackStillWaiting", state(journal, sid).equals("COLLAPSING"));
                moveOutside(p, frame);
            });
            at(context, t + 23, hooks, () -> moveInside(p, frame));
            at(context, t + 25, hooks, () -> {
                witness.put("entryEdgeEndsWaiting", !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid));
                witness.put("doorPlainOpen", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid) && !M4CandidateTestAccess.runtimeHeld(sid));
                context.assertTrue(!witness.containsValue(false), "DORMANT 的邊緣觸發與 WAITING（a～d）：" + witness);
                // (e) 開服 attach 當下就在 journal 的 DORMANT 收據：第一次觀察到 P 已在艙內即觸發（P 不必進出）。
                var startup = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), false));
                M4CandidateTestAccess.markStartup(server, startup.sessionUuid(), false);
                at(context, t + 28, hooks, () -> {
                    witness.put("startupFirstObservationResumes", !journal.flushedRecords().containsKey(startup.sessionUuid())
                            && capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", startup.sessionUuid().toString()).size() == 1);
                    witness.put("noWarnOrError", noWarnOrError(capture, sid)
                            && capture.matching(org.apache.logging.log4j.Level.ERROR, startup.sessionUuid().toString()).isEmpty());
                    context.assertTrue(!witness.containsValue(false), "DORMANT 的邊緣觸發與 WAITING：" + witness);
                    journal.remove(other.sessionUuid()); journal.flush(server);
                    hooks.release();
                    M4PerTestUniverseProbe.complete(context);
                });
            });
        });
    }

    /**
     * F3d（spec §7.1、§11.2 的退避、§5 兩個旗標）：選 SOURCE 門之後的第一次提交，在 flush 開頭受控失敗（restore 類：RESTORED）。live 改走
     * M4 的保留式 recovery：玩家返還、session 變成 DORMANT。退避期內的邊緣觸發不嘗試提交；200 ticks 之後再觸發才嘗試（再失敗，退避 400）。
     * 接著把本 instance 的 m5ReadyThisInstance 暫時設為假（M5 authority 初始化失敗）：退避期之後的觸發被 journal 拒絕、不提交、不寫出。
     * 恢復之後，再過 800 ticks 的觸發才提交，並走 SOURCE fast path。提交失敗的 WARN 依原因去重：兩種原因各一則。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_commit_fallback", tickLimit = 100000)
    public void native_commit_failure_falls_back_to_dormant_and_backs_off_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_commit_failure_falls_back_to_dormant_and_backs_off_windows");
        nativeSourceEntry(context, 1, (fixture, ready) -> {
            var server = fixture.server; var journal = SessionRecoveryState.get(server);
            var sid = fixture.record().sessionUuid(); var p = fixture.players.getFirst().player();
            boolean[] tearingDown = {false};
            var hooks = new M2CorridorGameTests.TestHooks();
            hooks.add(() -> { if (!tearingDown[0]) fixture.close(); });
            // 收尾還原開場時的原值（不寫死 true）。
            var readyAtStart = (Boolean) M4CandidateTestAccess.get(journal, "m5ReadyThisInstance");
            hooks.add(() -> M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", readyAtStart));
            // 提交 COLLAPSING 的寫出（current 已是 COLLAPSING、flushed 仍是 MEASURED）：記下每次嘗試的 tick，武裝時在 flush 開頭受控失敗。
            var fault = new FlushFault(server, "F3d GameTest 受控：提交 COLLAPSING 的 journal 寫出失敗", Integer.MAX_VALUE, state -> {
                var current = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return current != null && durable != null && current.state() == SessionState.COLLAPSING && durable.state() == SessionState.MEASURED;
            });
            LogCapture capture;
            try {
                capture = captureLogs(hooks);
                installFlushProbe(context, fault, hooks);
                interact(fixture.target, sourceDoor(fixture).cell(), p);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, ready + 1, hooks, () -> context.assertTrue(fault.ticks().size() == 1 && state(journal, sid).equals("MEASURED"),
                    "選門之後的第一個 tick 結束時應已嘗試提交一次（受控失敗）：attempts=" + fault.ticks() + " record=" + state(journal, sid)));
            whenTicks(context, ready + 1, 20000, () -> dormantAndReleased(journal, fixture.pages, sid), dormant -> {
                // 退避以 server tick 計；排程以 test tick 計。兩者每個 tick 都加一，差值固定。
                int offset = server.getTicks() - dormant;
                witness.put("firstAttemptFailedOnce", fault.ticks().size() == 1);
                witness.put("fellBackToDormantWithPlayerHome", p.getServerWorld() == context.getWorld()
                        && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame), p.getBoundingBox()));
                witness.put("neverCommitted", journal.flushedRecords().get(sid).state() == SessionState.MEASURED);
                int failedAt = fault.ticks().getFirst();
                // 退避期內的邊緣觸發：不嘗試提交。
                reenter(context, p, fixture.frame, dormant + 1, hooks);
                at(context, dormant + 4, hooks, () -> witness.put("backoffSuppressesEdge", fault.ticks().size() == 1));
                // 200 ticks 之後的邊緣觸發（進入的那個 tick 在 server tick failedAt+200 之後）：嘗試（仍受控失敗），退避加倍為 400。
                int second = Math.max(dormant + 6, failedAt + 200 - offset);
                reenter(context, p, fixture.frame, second, hooks);
                at(context, second + 3, hooks, () -> {
                    witness.put("retriedAfter200", fault.ticks().size() == 2 && fault.ticks().get(1) >= failedAt + 200);
                    fault.disarm();
                    M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", false);
                    int third = fault.ticks().get(1) + 400 - offset;
                    reenter(context, p, fixture.frame, third, hooks);
                    at(context, third + 3, hooks, () -> {
                        witness.put("notReadyRejectsWithoutWriting", fault.ticks().size() == 2 && journal.flushedRecords().get(sid).state() == SessionState.MEASURED);
                        witness.put("notReadyWarned", !capture.matching(org.apache.logging.log4j.Level.WARN, "M5 authority 尚未初始化成功", sid.toString()).isEmpty());
                        M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", readyAtStart);
                        // 被拒的那一次在 test tick third+1 結束時發生，退避加倍為 800。
                        int fourth = third + 1 + 800;
                        reenter(context, p, fixture.frame, fourth, hooks);
                        whenTicks(context, fourth + 2, 40, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                            witness.put("committedAfter800", fault.ticks().size() == 3);
                            witness.put("twoCommitWarnings", capture.matching(org.apache.logging.log4j.Level.WARN, "提交 MEASURED→COLLAPSING", sid.toString()).size() == 2);
                            witness.put("doorPlainOpen", doorAll(context.getWorld(), fixture.frame, true, MembraneState.NONE));
                            context.assertTrue(!witness.containsValue(false), "提交失敗的 fallback 與退避：" + witness + " attempts=" + fault.ticks()
                                    + " offset=" + offset);
                            tearingDown[0] = true;
                            hooks.release();
                            powerOffAndClose(context, fixture, gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                        }, () -> "恢復之後沒有完成：record=" + state(journal, sid) + " attempts=" + fault.ticks(), hooks);
                    });
                });
            }, () -> "提交失敗之後沒有回到 DORMANT：record=" + state(journal, sid) + " attempts=" + fault.ticks(), hooks);
        });
    }

    /**
     * F3d（spec §11.3、§12 的 Nether／End 列）：M4 時期在 Nether 與 End 留下的 DORMANT NEW 收據，不提交 COLLAPSING、不配置宇宙，直接 checked 終局，
     * 記 INFO「不支援的來源維度，已安全取消」。同一批的 Nether SOURCE 收據不取消，照一般 DORMANT 等待觸發。各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_unsupported_cancel", tickLimit = 200)
    public void nether_and_end_universe_receipts_are_cancelled_once_dormant(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"nether_and_end_universe_receipts_are_cancelled_once_dormant");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        hooks.add(() -> cleanupFixtures(server, owned));
        LogCapture capture;
        SessionRecoveryRecord nether, end, source;
        try {
            capture = captureLogs(hooks);
            var far = context.getAbsolutePos(BlockPos.ORIGIN).add(5000, 70, 5000);
            nether = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(UUID.randomUUID(), World.NETHER, far),
                    Map.of(UUID.randomUUID(), true), List.of(), true);
            end = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(UUID.randomUUID(), World.END, far.add(32, 0, 0)),
                    Map.of(UUID.randomUUID(), true), List.of(), true);
            source = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(UUID.randomUUID(), World.NETHER, far.add(64, 0, 0)),
                    Map.of(UUID.randomUUID(), true), List.of(), false);
            for (var record : List.of(nether, end, source)) { journal.put(record); owned.add(record.sessionUuid()); }
            journal.flush(server);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        at(context, 3, hooks, () -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var disk = M4CandidateTestAccess.diskRecords(server);
            for (var record : List.of(nether, end)) {
                var name = record.origin().worldKey().getPath();
                witness.put(name + ".cancelled", !journal.flushedRecords().containsKey(record.sessionUuid())
                        && !journal.records().containsKey(record.sessionUuid()) && !disk.containsKey(record.sessionUuid()));
                witness.put(name + ".info", capture.matching(org.apache.logging.log4j.Level.INFO, "不支援的來源維度，已安全取消", record.sessionUuid().toString()).size() == 1);
                witness.put(name + ".neverCommitted", capture.matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", record.sessionUuid().toString()).isEmpty());
                witness.put(name + ".noWarn", capture.matching(org.apache.logging.log4j.Level.WARN, record.sessionUuid().toString()).isEmpty());
            }
            witness.put("netherSourceKept", source.equals(journal.flushedRecords().get(source.sessionUuid()))
                    && capture.matching(org.apache.logging.log4j.Level.WARN, source.sessionUuid().toString()).isEmpty()
                    && capture.matching(org.apache.logging.log4j.Level.INFO, "不支援的來源維度", source.sessionUuid().toString()).isEmpty());
            context.assertTrue(!witness.containsValue(false), "Nether／End 收據的取消：" + witness);
            hooks.release();
            M4PerTestUniverseProbe.complete(context);
        });
    }

    /**
     * F3d（spec §12 的 PASSAGE_READY 列）：開服時就是 PASSAGE_READY 的 record 直接 checked 終局（PASSAGE_ENDED），記 INFO；門在下一個 tick
     * 才改為一般開門（不在終局的同一個 tick 寫門）。presence 回 NONE，沒有 runtime 或來源 ticket。F5 之前 runtime 不會產生 READY，以 fixture
     * 與「標成開服當下就在 journal」驗證。各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_startup_passage", tickLimit = 200)
    public void startup_passage_ready_ends_and_normalizes_the_door_next_tick(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"startup_passage_ready_ends_and_normalizes_the_door_next_tick");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var frame = fixtureFrame(context, Direction.NORTH);
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        hooks.add(() -> clearChamber(world, frame));
        hooks.add(() -> cleanupFixtures(server, owned));
        LogCapture capture;
        SessionRecoveryRecord ready;
        try {
            ChamberGameTestBuilder.buildForSession(context, false, Direction.NORTH, false);
            capture = captureLogs(hooks);
            ready = collapseFixture(server, M4CandidateTestAccess.origin(UUID.randomUUID(), frame.controllerPos()), Map.of(UUID.randomUUID(), true),
                    List.of(), true, SessionState.PASSAGE_READY, false);
            owned.add(ready.sessionUuid());
            context.assertTrue(new ChamberDoorService().applyTarget(world, frame, DoorState.passable(Direction.NORTH)).succeeded(), "fixture：READY 的門");
            M4CandidateTestAccess.markStartup(server, ready.sessionUuid(), true);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        var sid = ready.sessionUuid();
        var witness = new LinkedHashMap<String, Boolean>();
        at(context, 1, hooks, () -> {
            witness.put("endedAtStartup", !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid));
            witness.put("info", capture.matching(org.apache.logging.log4j.Level.INFO, "開服時仍是 PASSAGE_READY", sid.toString()).size() == 1);
            witness.put("doorNotYetWritten", doorAll(world, frame, true, MembraneState.PASSABLE_X));
            witness.put("presenceNone", ChamberSessions.gateway().presence(server, ready.chamberUuid()) == ChamberSessionGateway.Presence.NONE);
            witness.put("noRuntimeOrTicket", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
        });
        at(context, 2, hooks, () -> {
            witness.put("doorNormalizedNextTick", doorAll(world, frame, true, MembraneState.NONE));
            context.assertTrue(!witness.containsValue(false), "開服時的 PASSAGE_READY：" + witness);
            hooks.release();
            M4PerTestUniverseProbe.complete(context);
        });
    }

    /** spec §10.8「Nether／End 選門」（以字面值斷言，不信任 production 常數）。 */
    private static final String DIMENSION_DOOR_KEY = "message.quantumchamber.m5.dimension_door_unavailable";

    /**
     * F3d（spec §11.3、§10.8）：在 Nether 的遠方蓋一座真原艙（強制載入涵蓋的 chunk；外圍以石頭圍住，隔開岩漿與地形），一位玩家真入場。
     * 走廊照常發布 358 扇側門；選 SOURCE 門與 NEW 門都被拒：回 FAIL、actionbar 是「此維度的宇宙門尚未開放。」、不寫 SELECTED、record 仍是
     * 同一份 SUPERPOSITION，玩家沒有被凍結，照常可以在走廊移動。斷電收尾之後，同一座 Nether 原艙上的 M4 時期 DORMANT SOURCE 收據照一般
     * 規則完成（§11.3 第二段）：供電、玩家由艙外進入 → 提交 COLLAPSING → SOURCE fast path → 一般開門；不走取消路徑。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_nether_door", tickLimit = 100000)
    public void nether_side_doors_are_refused_and_an_m4_source_receipt_still_completes_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"nether_side_doors_are_refused_and_an_m4_source_receipt_still_completes_windows");
        var server = context.getWorld().getServer();
        var journal = SessionRecoveryState.get(server); var pages = CorridorPageManager.forServer(server);
        var target = server.getWorld(SuperpositionWorld.KEY);
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        var fixture = NetherChamber.open(context, new BlockPos(24008, 100, 24008), hooks);
        var nether = fixture.nether; var frame = fixture.frame; var power = fixture.power; var connection = fixture.connection;
        hooks.add(() -> cleanupFixtures(server, owned));
        var capture = captureLogs(hooks);
        var p = fixture.player();
        fixture.whenEntered(ready -> {
            var record = fixture.record(journal); var sid = record.sessionUuid(); var chamber = record.chamberUuid();
            var witness = new LinkedHashMap<String, Boolean>();
            witness.put("netherSource", record.candidateContext().orElseThrow().sourceFamilyRef().role() == DimensionRole.NETHER
                    && p.getServerWorld() == target);
            // 先 ACK 入場的 teleport（同時讀掉先前的封包），之後的 actionbar 只會是這兩次選門的回應。
            connection.confirmTeleport();
            var sourceDoor = candidateDoor(pages, target, sid, QuantumCandidate.Source.class);
            var newDoor = candidateDoor(pages, target, sid, QuantumCandidate.New.class);
            var refusedSource = interact(target, sourceDoor.cell(), p);
            var refusedNew = interact(target, newDoor.cell(), p);
            var keys = overlayKeys(connection);
            witness.put("sourceDoorFails", refusedSource == ActionResult.FAIL);
            witness.put("newDoorFails", refusedNew == ActionResult.FAIL);
            witness.put("dimensionMessageEachTime", keys.equals(List.of(DIMENSION_DOOR_KEY, DIMENSION_DOOR_KEY)));
            witness.put("noSelectionWritten", record.equals(journal.flushedRecords().get(sid)) && record.equals(journal.records().get(sid))
                    && record.state() == SessionState.SUPERPOSITION && record.selectedCandidate().isEmpty());
            witness.put("notFrozen", !SessionRecoveryManager.blocks(p.getUuid(), server));
            var before = p.getPos();
            p.networkHandler.onPlayerMove(new PlayerMoveC2SPacket.Full(before.x + 0.125, before.y, before.z, p.getYaw(), p.getPitch(), false));
            witness.put("corridorWalkable", p.getServerWorld() == target && Math.abs(p.getX() - before.x - 0.125) < 1e-9);
            context.assertTrue(!witness.containsValue(false), "Nether 的側門選擇：" + witness + " keys=" + keys);
            // 斷電收尾：一般 M2 返還，玩家回到 Nether 原艙。
            nether.setBlockState(power, Blocks.AIR.getDefaultState());
            whenTicks(context, ready + 1, 20000, () -> !journal.flushedRecords().containsKey(sid)
                    && ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE, released -> {
                var after = new LinkedHashMap<String, Boolean>();
                after.put("returnedToTheNetherChamber", p.getServerWorld() == nether
                        && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame), p.getBoundingBox()));
                after.put("refusalLeftNoWarn", capture.matching(org.apache.logging.log4j.Level.WARN, sid.toString()).isEmpty());
                // M4 時期留在這座 Nether 原艙的 DORMANT SOURCE 收據（P 已返還）。
                var receipt = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, World.NETHER, frame.controllerPos()),
                        Map.of(p.getUuid(), true), List.of(), false);
                journal.put(receipt); journal.flush(server); owned.add(receipt.sessionUuid());
                var dormant = receipt.sessionUuid();
                moveOutside(p, frame);
                overlayKeys(connection);
                at(context, released + 1, hooks, () -> nether.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState()));
                at(context, released + 2, hooks, () -> moveInside(p, frame));
                at(context, released + 3, hooks, () -> {
                    var committed = journal.flushedRecords().get(dormant);
                    after.put("entryEdgeCommits", committed != null && committed.state() == SessionState.COLLAPSING
                            && committed.collapse().destination() instanceof CollapseDestination.Source);
                    context.assertTrue(!after.containsValue(false), "Nether 原艙的收尾與 M4 SOURCE 收據的提交：" + after);
                });
                whenTicks(context, released + 4, 40, () -> !journal.flushedRecords().containsKey(dormant), gone -> {
                    after.put("doorPlainOpen", doorAll(nether, frame, true, MembraneState.NONE));
                    after.put("oneCommitInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", dormant.toString()).size() == 1);
                    after.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", dormant.toString()).size() == 1);
                    after.put("notCancelled", capture.matching(org.apache.logging.log4j.Level.INFO, "不支援的來源維度", dormant.toString()).isEmpty());
                    after.put("collapsedMessage", overlayKeys(connection).contains(COLLAPSED_KEY));
                    after.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    after.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(dormant) && !M4CandidateTestAccess.sourceTicketHeld(dormant));
                    after.put("noWarnOrError", noWarnOrError(capture, dormant));
                    context.assertTrue(!after.containsValue(false), "Nether 原艙的收尾與 M4 SOURCE 收據：" + after);
                    hooks.release();
                    M4PerTestUniverseProbe.complete(context);
                }, () -> "Nether 的 DORMANT SOURCE 收據沒有完成：record=" + state(journal, dormant), hooks);
            }, () -> "Nether 原艙斷電之後沒有收尾：record=" + state(journal, sid), hooks);
        });
    }

    /**
     * F3d fix round 1（spec §11.3 第 1、2 步）：Nether 原艙上真入場的 live session，繞過側門互動、在 operation guard 內直接經 ledger 選一扇 NEW 門，
     * 製造非 DORMANT 的 Nether MEASURED NEW（record 形狀與 M4 收據相同；重啟時沒有 runtime、attach 已請求 recovery 的那條路徑屬 F6 的 W10）。塌縮流程不提交 COLLAPSING：選門的下一個 tick 仍是 MEASURED、
     * 已請求 M4 的保留式 recovery、記一則 INFO。玩家照 M4 返還回 Nether 原艙、走廊清理完成、checked 寫成 DORMANT；之後以
     * UNSUPPORTED_SOURCE_CANCEL 取消，記 INFO「不支援的來源維度，已安全取消」，presence 回 NONE、runtime 與來源 ticket 已釋放。從頭到尾沒有
     * 任何一次寫出 COLLAPSING，也沒有「量子態塌縮。」。catalog 不變由 per-test receipt 與 default run 的 boundary oracle 核對。
     * 只覆蓋 NEW：EXISTING 候選要 discovery 裡已有宇宙，default run 的世界沒有；建立它會改變 discovery 與 catalog。判斷本身對 NEW 與 EXISTING
     * 相同（{@code CollapseService.unsupportedSource}，JUnit 逐一覆蓋 3 種 role × 3 種候選）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_nether_live_cancel", tickLimit = 100000)
    public void nether_live_new_selection_recovers_to_dormant_then_cancels_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"nether_live_new_selection_recovers_to_dormant_then_cancels_windows");
        var server = context.getWorld().getServer();
        var journal = SessionRecoveryState.get(server); var pages = CorridorPageManager.forServer(server);
        var target = server.getWorld(SuperpositionWorld.KEY);
        var hooks = new M2CorridorGameTests.TestHooks();
        // 與 G-NETHER 的原艙不同 chunk（每次 gate 都是 fresh 世界，但同一個 run 裡兩個 test 都會留下已註冊的 Nether 原艙）。
        var fixture = NetherChamber.open(context, new BlockPos(24200, 100, 24008), hooks);
        var nether = fixture.nether; var frame = fixture.frame; var connection = fixture.connection;
        var capture = captureLogs(hooks);
        var p = fixture.player();
        fixture.whenEntered(ready -> {
            var record = fixture.record(journal); var sid = record.sessionUuid(); var chamber = record.chamberUuid();
            // 每次 journal 寫出的開頭：這個 session 是否寫出過 COLLAPSING，以及第一次 checked 寫成 DORMANT 的 server tick。不得丟例外。
            boolean[] wroteCollapsing = {false}; int[] dormantAt = {-1};
            java.util.function.Consumer<SessionRecoveryState> probe = state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                if (staged == null) return;
                if (staged.state() == SessionState.COLLAPSING) wroteCollapsing[0] = true;
                boolean stagedDormant = staged.state() == SessionState.MEASURED && MeasuredRecoveryPhase.from(staged) == MeasuredRecoveryPhase.DORMANT;
                boolean durableDormant = durable != null && durable.state() == SessionState.MEASURED
                        && MeasuredRecoveryPhase.from(durable) == MeasuredRecoveryPhase.DORMANT;
                if (dormantAt[0] < 0 && stagedDormant && !durableDormant) dormantAt[0] = server.getTicks();
            };
            installFlushProbe(context, probe, hooks);
            overlayKeys(connection);
            // 繞過 CandidateDoorInteraction（它會拒絕 Nether 的新選擇），照它的 guard 與檢查直接經 ledger 選 NEW 門。
            var door = candidateDoor(pages, target, sid, QuantumCandidate.New.class);
            var location = pages.candidateDoor(target, door.cell()).orElseThrow();
            var manager = (SuperpositionSessionManager) ChamberSessions.gateway();
            var outcome = pages.exclusiveOperation(sid, () -> {
                var selectable = pages.candidateSelectionRecord(location, p);
                if (selectable.isEmpty() || !manager.candidateSourceAuthorized(server, selectable.get()))
                    return CandidateLedgerService.SelectionOutcome.REJECTED;
                var result = new CandidateLedgerService().trySelect(server, sid, location.key(), p.getUuid(), target.getTime());
                if (result == CandidateLedgerService.SelectionOutcome.SELECTED) manager.candidateMeasured(server, sid);
                return result;
            });
            var measured = journal.flushedRecords().get(sid);
            var witness = new LinkedHashMap<String, Boolean>();
            witness.put("netherSource", record.candidateContext().orElseThrow().sourceFamilyRef().role() == DimensionRole.NETHER);
            witness.put("liveNewReceipt", outcome == CandidateLedgerService.SelectionOutcome.SELECTED && measured != null
                    && measured.state() == SessionState.MEASURED && measured.selectedCandidate().orElse(null) instanceof QuantumCandidate.New
                    && MeasuredRecoveryPhase.from(measured) != MeasuredRecoveryPhase.DORMANT);
            witness.put("noRecoveryRequestedYet", !pages.measuredRecoveryRequested(sid));
            context.assertTrue(!witness.containsValue(false), "Nether 的 live NEW 收據：" + witness + " outcome=" + outcome);
            // 選門的 tick 結束時塌縮流程第一次看到它：不提交，請求 M4 的保留式 recovery。
            at(context, ready + 1, hooks, () -> {
                var now = journal.flushedRecords().get(sid);
                witness.put("notCommittedNextTick", now != null && now.state() == SessionState.MEASURED && !wroteCollapsing[0]);
                witness.put("retainedRecoveryRequested", pages.measuredRecoveryRequested(sid));
                witness.put("oneNotCommittingInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "不支援的來源維度：不提交 COLLAPSING", sid.toString()).size() == 1);
                context.assertTrue(!witness.containsValue(false), "Nether 的 live NEW 收據在選門的下一個 tick：" + witness + " record=" + state(journal, sid));
                // 取消之後原艙仍供電、玩家回到艙內：沒有 Buff 就不會立刻開始新的 session，收尾單純。返還已由上面的請求驅動。
                p.removeStatusEffect(ModEffects.QUANTUM_STATE);
            });
            whenTicks(context, ready + 2, 20000, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                var after = new LinkedHashMap<String, Boolean>();
                after.put("neverWroteCollapsing", !wroteCollapsing[0]);
                after.put("reachedDormantFirst", dormantAt[0] >= 0);
                after.put("returnedToTheNetherChamber", p.getServerWorld() == nether
                        && ChamberOccupantService.contains(ChamberGeometry.interiorBox(frame), p.getBoundingBox()));
                after.put("oneCancelInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "不支援的來源維度，已安全取消", sid.toString()).size() == 1);
                after.put("noCommitInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", sid.toString()).isEmpty());
                after.put("noCollapsedMessage", !overlayKeys(connection).contains(COLLAPSED_KEY));
                after.put("diskHasNoRecord", !M4CandidateTestAccess.diskRecords(server).containsKey(sid) && !journal.records().containsKey(sid));
                after.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                after.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                after.put("noWarnOrError", noWarnOrError(capture, sid));
                // spec §11.3「原艙解除封鎖」：取消之後 Controller 已協調（不再停在 session 期間的 ARMED），registry 的 power state 不是 RETURNING。
                after.put("controllerCoordinated", controller(nether, frame).chamberState() != ChamberState.ARMED);
                after.put("registryNotReturning", ChamberRegistryState.get(server).registry().records().get(chamber).powerState()
                        != ChamberPowerState.RETURNING);
                context.assertTrue(!after.containsValue(false), "Nether 的 live NEW 收據的取消：" + after + " dormantAt=" + dormantAt[0] + " gone=" + server.getTicks());
                hooks.release();
                M4PerTestUniverseProbe.complete(context);
            }, () -> "Nether 的 live NEW 收據沒有被取消：record=" + state(journal, sid) + " requested=" + pages.measuredRecoveryRequested(sid)
                    + " dormantAt=" + dormantAt[0], hooks);
        });
    }

    /**
     * F3d fix round 2（quality I-1，spec §6 收斂共通第 2 點）：COLLAPSING 清 lease 的寫出持續失敗。走廊清理第一次寫出失敗之後，current 已是
     * 清空 lease 的那一份、寫出尚未確認：走廊清理不再每個 tick 重掃 lease、重寫 journal，確認只交給 recovery 以 journal 為單位退避的
     * RETRY_FLUSH。受控失敗前 3 次「current 沒有 lease、flushed 仍有 lease」的寫出，第 4 次成功。斷言第一次失敗之後的 200 ticks 內最多 2 次
     * 寫出（同一個 tick 的走廊清理與 RETRY_FLUSH），之後的間隔依退避（200、400）；確認之後 space 釋放，SOURCE fast path 照常完成。
     * 只看次數與間隔，不看絕對 tick。
     * <p>fix round 3（spec re-review m-1）：原生 autosave 不經過 flush 探針。選門之前先等到接下來 800 個 tick 內不會有原生 autosave（選門到第一次
     * 清 lease 的寫出實測 11 個 tick，4 次寫出在其後的第 0、0、200、600 個 tick，留約 190 ticks 餘裕），所以恰好 4 次（fix round 2 為了 autosave
     * 放寬成 3～4 次，這裡恢復）。F4a-0（quality re-review m-1）：確認時斷言窗口內 journal 的每一次寫出都經過探針（{@code onlyProbedJournalWrites}），
     * 涵蓋原生 autosave、{@code server.save}（例如保留式清理的 checkpoint）與其他 session 的 flush，失敗訊息列出是哪一種旁路。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_release_fault_backoff", tickLimit = 100000)
    public void native_persistent_release_write_fault_waits_for_the_journal_backoff_windows(TestContext context) {
        if(!Platform.isWindows()) { context.complete(); return; }
        M4PerTestUniverseProbe.begin(context,"native_persistent_release_write_fault_waits_for_the_journal_backoff_windows");
        nativeSourceEntry(context, 1, (fixture, ready) -> {
            var server = fixture.server; var journal = SessionRecoveryState.get(server);
            var sid = fixture.record().sessionUuid();
            boolean[] tearingDown = {false};
            var hooks = new M2CorridorGameTests.TestHooks();
            hooks.add(() -> { if (!tearingDown[0]) fixture.close(); });
            // 「current 沒有 lease、flushed 仍有 lease」的 COLLAPSING 寫出：前 3 次受控失敗。
            var fault = new FlushFault(server, "F3d GameTest 受控：清 lease 的 journal 寫出持續失敗", 3, state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return staged != null && durable != null && staged.state() == SessionState.COLLAPSING && staged.spaceLeases().isEmpty()
                        && !durable.spaceLeases().isEmpty();
            });
            afterAutosaveClear(context, ready + 1, "release_fault_backoff", 800, (start, autosave) -> {
                installFlushProbe(context, fault, hooks);
                interact(fixture.target, sourceDoor(fixture).cell(), fixture.players.getFirst().player());
                whenTicks(context, start + 1, 20000, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                    var witness = new LinkedHashMap<String, Boolean>();
                    var writes = fault.ticks();
                    int first = writes.isEmpty() ? -1 : writes.getFirst();
                    var journalWrites = autosave.journalWrites(server, writes, Set.of(sid));
                    witness.put("onlyProbedJournalWrites", journalWrites.onlyProbed());
                    witness.put("fourWrites", writes.size() == 4);
                    witness.put("atMostTwoWithinTheFirst200Ticks", writes.stream().filter(tick -> tick < first + 200).count() <= 2);
                    witness.put("retriesFollowTheBackoff", writes.size() == 4 && writes.get(2) - first >= 200 && writes.get(3) - writes.get(2) >= 400);
                    witness.put("spaceReleased", M4CandidateTestAccess.space(fixture.pages, sid) == null);
                    witness.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                    witness.put("doorPlainOpen", doorAll(context.getWorld(), fixture.frame, true, MembraneState.NONE));
                    context.assertTrue(!witness.containsValue(false), "清 lease 的寫出持續失敗：" + witness + " writes=" + writes
                            + " start=" + autosave.start() + " autosaveAt=" + autosave.autosaveAt() + " journalWrites=" + journalWrites.problems());
                    tearingDown[0] = true;
                    hooks.release();
                    powerOffAndClose(context, fixture, gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                }, () -> "清 lease 的寫出沒有確認：record=" + state(journal, sid) + " writes=" + fault.ticks(), hooks);
            }, hooks);
        });
    }

    /**
     * F3d fix round 2（quality m-7(a)，spec §7、§6 收斂共通第 2 點）：返還與清理都完成（SETTLED）的 COLLAPSING 之後的 roll-forward 也由 recovery
     * 以 journal 為單位帶退避重試。以 journal 的 commitRollForward 寫入 COLLAPSING→SEALED（模擬 F4a 進入 SEALED），前 3 次寫出受控失敗：
     * 寫入當下 1 次、recovery 的 RETRY_FLUSH 在同一個 tick 1 次、200 ticks 後 1 次，400 ticks 後的第 4 次確認為 SEALED。期間 current 一直是那份
     * SEALED，塌縮流程暫停、不疊寫（沒有任何塌縮流程的 WARN）。收尾以終局 API 移除。不需要原生 checkpoint，各平台都執行。
     * <p>fix round 3：
     * <ul>
     * <li>決策 7（spec re-review I-1）：fixture 是目的地 NEW 的 SETTLED COLLAPSING，刻意不掛 collapse-suspended hook（要證明塌縮流程在
     *     roll-forward 未確認時自己暫停）。建立 fixture、裝探針、commitRollForward 都在同一個 callback 內完成，塌縮流程（END_SERVER_TICK）
     *     從第一次看到這個 session 起就是「roll-forward 未確認」，一律暫停，不會推導到 RESOLVE（F4a 之後 RESOLVE 會配置宇宙）。整個 test
     *     期間這個 sid 沒有 RESOLVE stub 或其他塌縮流程的 WARN。</li>
     * <li>spec re-review m-1：原生 autosave 不經過 flush 探針。開始注入之前先等到接下來 800 個 tick 內不會有原生 autosave（4 次寫出在開始後的
     *     第 0、0、200、600 個 tick，留 200 ticks 餘裕），所以恰好 4 次（fix round 2 為了 autosave 放寬成 3～4 次，這裡恢復）。</li>
     * </ul>
     * F4a-0（quality re-review m-1、m-2）：確認時斷言窗口內 journal 的每一次寫出都經過探針（{@code onlyProbedJournalWrites}），涵蓋原生
     * autosave、{@code server.save} 與其他 session 的 flush，失敗訊息列出是哪一種旁路；「沒有塌縮流程的 WARN」改依 {@code CollapseService} 的
     * log marker 篩選（{@code collapseFlowOnlySawPendingRollForward}），不再比對訊息字串。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_sealed_roll_forward", tickLimit = 3000)
    public void settled_collapse_roll_forward_into_sealed_is_retried_by_recovery_with_backoff(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"settled_collapse_roll_forward_into_sealed_is_retried_by_recovery_with_backoff");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        hooks.add(() -> cleanupFixtures(server, owned));
        var capture = captureLogs(hooks);
        var far = context.getAbsolutePos(BlockPos.ORIGIN).add(6000, 70, 6000);
        afterAutosaveClear(context, 1, "sealed_roll_forward", 800, (start, autosave) -> {
            // SEALED 只能接在 UNIVERSE 目的地之後（選中 NEW）。
            var settled = collapseFixture(server, M4CandidateTestAccess.origin(UUID.randomUUID(), far), Map.of(UUID.randomUUID(), true), List.of(), true,
                    SessionState.COLLAPSING, false);
            var sid = settled.sessionUuid();
            owned.add(sid);
            var sealed = settled.withCollapse(SessionState.SEALED, settled.collapse().sealed(SealedReason.PROJECTION_FAILED));
            // 「current 是 SEALED、flushed 仍是 COLLAPSING」的寫出：前 3 次受控失敗。
            var fault = new FlushFault(server, "F3d GameTest 受控：進入 SEALED 的 journal 寫出失敗", 3, state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                return staged != null && durable != null && staged.state() == SessionState.SEALED && durable.state() == SessionState.COLLAPSING;
            });
            // 失敗路徑：先解除探針，再把尚未確認的寫出帶出去，最後才以終局 API 收尾（hooks 依後進先出執行）。
            hooks.add(() -> { if (journal.rollForwardPending(sid)) journal.flush(server); });
            // F4a（F3d quality re-review m-4）：放行第 4 次寫出（確認 SEALED）的同時暫停這個 session 的塌縮流程。確認之後的 flushed SEALED
            // (PROJECTION_FAILED) 是可重試的原因，F4a 起塌縮流程會評估它的重試；暫停之後，確認的那一個 tick 起到收尾之前都不推進（決策 7）。
            installFlushProbe(context, fault.andThen(state -> {
                var staged = state.records().get(sid); var durable = state.flushedRecords().get(sid);
                if (fault.failures() >= 3 && staged != null && durable != null && staged.state() == SessionState.SEALED
                        && durable.state() == SessionState.COLLAPSING) M5TestHooks.suspendCollapse(sid);
            }), hooks);
            // 探針已裝好，同一個 callback 內立即寫入：這個 tick 結束時塌縮流程第一次看到它，roll-forward 已經未確認。
            var result = journal.commitRollForward(server, settled, sealed);
            context.assertTrue(!result.committed() && journal.rollForwardPending(sid) && sealed.equals(journal.records().get(sid))
                    && settled.equals(journal.flushedRecords().get(sid)), "fixture：進入 SEALED 的寫出受控失敗，current 帶著 SEALED：" + result);
            boolean[] overwritten = {false};
            whenTicks(context, start + 1, 1500, () -> {
                var current = journal.records().get(sid);
                if (!sealed.equals(current)) overwritten[0] = true;
                var durable = journal.flushedRecords().get(sid);
                return durable != null && durable.state() == SessionState.SEALED;
            }, confirmed -> {
                var witness = new LinkedHashMap<String, Boolean>();
                var attempts = fault.ticks();
                witness.put("confirmedTheSameSealed", sealed.equals(journal.flushedRecords().get(sid)) && !journal.rollForwardPending(sid));
                var journalWrites = autosave.journalWrites(server, attempts, Set.of(sid));
                witness.put("onlyProbedJournalWrites", journalWrites.onlyProbed());
                witness.put("fourAttempts", attempts.size() == 4);
                witness.put("suspendedAtTheConfirmingWrite", M5TestHooks.collapseSuspended(sid));
                witness.put("retriesFollowTheBackoff", attempts.size() == 4 && attempts.get(2) - attempts.get(1) >= 200
                        && attempts.get(3) - attempts.get(2) >= 400);
                witness.put("collapseServiceNeverOverwrote", !overwritten[0]
                        && capture.matching(org.apache.logging.log4j.Level.WARN, "塌縮流程", sid.toString()).isEmpty());
                witness.put("collapseFlowOnlySawPendingRollForward", collapseFlowNeverWarned(capture, sid));
                context.assertTrue(!witness.containsValue(false), "進入 SEALED 的 roll-forward 重試：" + witness + " attempts=" + attempts
                        + " start=" + autosave.start() + " autosaveAt=" + autosave.autosaveAt() + " journalWrites=" + journalWrites.problems());
                hooks.release();
                context.assertTrue(!journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid), "收尾：以終局 API 移除");
                M4PerTestUniverseProbe.complete(context);
            }, () -> "進入 SEALED 的寫出一直沒有確認：attempts=" + fault.ticks() + " pending=" + journal.rollForwardPending(sid), hooks);
        }, hooks);
    }

    /**
     * F3d fix round 2（quality m-2(i)）：SOURCE fast path 的終局寫出（移除 record）受控失敗一次。真原艙上的 DORMANT SOURCE 收據由邊緣觸發提交、
     * 走到 PREPARE；第一次終局失敗只記 1 則 WARN，門維持原狀（一般關門），200 ticks 之前不重試；之後重試成功，門照常改為一般開門。
     * 不需要原生 checkpoint，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_terminal_fault", tickLimit = 2000)
    public void source_fast_path_terminal_write_fault_backs_off_and_keeps_the_door(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"source_fast_path_terminal_write_fault_backs_off_and_keeps_the_door");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var receipt = origin.receipt(chamber, Map.of(p.getUuid(), true), false);
            var sid = receipt.sessionUuid();
            // 終局的寫出（flushed 仍是 COLLAPSING、current 已沒有 record）：第一次受控失敗。
            var fault = new FlushFault(server, "F3d GameTest 受控：SOURCE fast path 的終局寫出失敗", 1, state -> {
                var durable = state.flushedRecords().get(sid);
                return durable != null && durable.state() == SessionState.COLLAPSING && !state.records().containsKey(sid);
            });
            installFlushProbe(context, fault, hooks);
            origin.own(receipt);
            boolean[] doorMovedEarly = {false};
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            whenTicks(context, registered + 2, 1500, () -> {
                if (fault.ticks().size() == 1 && journal.flushedRecords().containsKey(sid) && !doorAll(world, frame, false, MembraneState.NONE))
                    doorMovedEarly[0] = true;
                return !journal.flushedRecords().containsKey(sid);
            }, gone -> {
                var witness = new LinkedHashMap<String, Boolean>();
                var attempts = fault.ticks();
                witness.put("twoTerminalAttempts", attempts.size() == 2);
                witness.put("retryWaitedForTheBackoff", attempts.size() == 2 && attempts.get(1) - attempts.get(0) >= 200);
                witness.put("oneTerminalWarn", capture.matching(org.apache.logging.log4j.Level.WARN, "SOURCE fast path 的終局", sid.toString()).size() == 1);
                witness.put("doorKeptUntilTheTerminal", !doorMovedEarly[0]);
                witness.put("doorPlainOpenAfter", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                witness.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("noError", capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty());
                context.assertTrue(!witness.containsValue(false), "SOURCE fast path 的終局寫出失敗：" + witness + " attempts=" + attempts);
                hooks.release();
                M4PerTestUniverseProbe.complete(context);
            }, () -> "SOURCE 收據沒有完成：record=" + state(journal, sid) + " attempts=" + fault.ticks(), hooks);
        });
    }

    // ---- H9 (a)：SEALED 的重試丟棄還沒套用的關門（spec v5.10 §4 H9、§7.3「門狀態的重新套用」、§12 SEALED 列） ----

    /**
     * H9 (a)：真原艙上的 SEALED(PROJECTION_FAILED) 收據（可重試；參與者 P 在艙內，原艙已供電、enabled），門上留著 sealed 量子膜（例如 PREPARE 期間進入 SEALED、crash
     * 在關門套用之前）。模擬重啟之後的第一次觀察（{@code simulateDoorStartup}）：同一個 tick 排入下一個 tick 的關門（CLOSE_ON_SEALED），並因為 P 開服時就在艙內而重試成
     * COLLAPSING。下一個 tick 斷電（閘門不成立），推導停在 WAITING：
     * <ul>
     * <li>關門不再套用（session 已不是 SEALED），依套用當下的 collapse view（COLLAPSING）改為一般開門：重試之後的下一個 tick 門是 {@code open=true}＋{@code membrane=none}；</li>
     * <li>之後 WAITING 的 40 個 tick 一直是一般開門，record 一直是 COLLAPSING，沒有進入 PREPARE；這個 session 沒有 WARN／ERROR。</li>
     * </ul>
     * default run 不得配置宇宙（plan 決策 6），所以以 {@code skipResolve} 略過 RESOLVE（NEW 的 RESOLVE 會配置宇宙；RESOLVE 不寫原艙門）。真的 RESOLVE 之後停在 WAITING 的跨 JVM
     * 版本是 F6-2d-2 B 段的 {@code sealmembrane}。catalog 不變（per-test baseline）。不需要原生 checkpoint，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_sealed_retry_membrane", tickLimit = 400)
    public void sealed_retry_at_startup_opens_a_membrane_door_and_keeps_it_open_while_waiting(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"sealed_retry_at_startup_opens_a_membrane_door_and_keeps_it_open_while_waiting");
        sealedRetryAtStartup(context, true);
    }

    /**
     * H9 (a)：同上，但門上沒有量子膜（關著：重啟之前 SEALED 的關門已套用，最常見的情況）。重試之後不寫門（spec v5.10 §4 H9「沒有量子膜時不寫入」），WAITING 期間一直是
     * 關著的一般門。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_sealed_retry_closed_door", tickLimit = 400)
    public void sealed_retry_at_startup_leaves_a_door_without_a_membrane_unwritten(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"sealed_retry_at_startup_leaves_a_door_without_a_membrane_unwritten");
        sealedRetryAtStartup(context, false);
    }

    /**
     * H9 (a) 的兩個 test 共用（呼叫端已以 {@code M4PerTestUniverseProbe.begin} 記下 per-test baseline：run-all 要求它是 test 方法的第一個敘述、名稱是字串常值）：
     * {@code membrane} 為真時門上留著 sealed 量子膜，否則是關著的一般門。
     */
    private static void sealedRetryAtStartup(TestContext context, boolean membrane) {
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = DormantOrigin.open(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        var initial = membrane ? MembraneState.sealedFor(frame.outwardFacing()) : MembraneState.NONE;
        // 重試之後應有的門：帶著量子膜時是一般開門，沒有量子膜時不寫入（仍是關著的一般門）。
        boolean expectedOpen = membrane;
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(p, frame);
            // F6-3c-1（H9 quality Minor 5）：fixture 在任何寫入之前就把 session 加入 owned（原本在 fixture 建好之後才加入：SEALED 的寫入失敗時，已確認的 COLLAPSING 不在 owned 裡）。
            var record = collapseFixtureOwnedBy(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(p.getUuid(), true), List.of(), true,
                    SessionState.SEALED, false, origin.owned);
            var sid = record.sessionUuid();
            M5GameTestSupport.writeDoor(world, frame, membrane, initial);
            M5GameTestSupport.simulateDoorStartup(server, sid);
            int observed = server.getTicks();
            // 這個 tick 結束時（重啟之後的第一次觀察）：排入下一個 tick 的關門，並重試成 COLLAPSING。
            at(context, registered + 1, hooks, () -> {
                witness.put("closeQueuedForTheNextTick", capture.matching(org.apache.logging.log4j.Level.INFO,
                        "（tick " + (observed + 1) + "）依 collapse view 重新套用原艙門（CLOSE_ON_SEALED）", sid.toString()).size() == 1);
                witness.put("retriedInTheSameTick", state(journal, sid).equals("COLLAPSING")
                        && capture.matching(org.apache.logging.log4j.Level.INFO, "SEALED 重試", sid.toString()).size() == 1);
                witness.put("doorNotWrittenYet", doorAll(world, frame, membrane, initial));
                context.assertTrue(!witness.containsValue(false), "重試的前提：" + witness);
                M5GameTestSupport.skipResolve(server, sid);
                // 閘門不成立：斷電，推導停在 WAITING。
                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            });
            // 重試之後的下一個 tick 結束時（關門的套用時點）。
            at(context, registered + 2, hooks, () -> {
                context.assertTrue(doorAll(world, frame, expectedOpen, MembraneState.NONE), (membrane
                        ? "重試之後的下一個 tick，帶著量子膜的原艙門沒有改為一般開門：" : "重試之後的下一個 tick，沒有量子膜的原艙門被改寫：")
                        + label(world.getBlockState(door(frame).get(12))));
                witness.put("closeDropped", capture.matching(org.apache.logging.log4j.Level.INFO, "session 已不是 SEALED，不再套用封存時的原艙門", sid.toString()).size() == 1);
                witness.put("normalizedOnlyWithAMembrane", capture.matching(org.apache.logging.log4j.Level.INFO,
                        "原艙門已依 collapse view 重新套用（NORMALIZE：APPLIED，tick " + (observed + 1) + "）", sid.toString()).size() == (membrane ? 1 : 0));
            });
            boolean[] changed = {false};
            whenTicks(context, registered + 3, 45, () -> {
                if (!doorAll(world, frame, expectedOpen, MembraneState.NONE) || !state(journal, sid).equals("COLLAPSING")) changed[0] = true;
                return context.getTick() >= registered + 42;
            }, done -> {
                witness.put("doorKeptWhileWaiting", !changed[0]);
                witness.put("neverPrepared", capture.matching(org.apache.logging.log4j.Level.INFO, "進入 PREPARE", sid.toString()).isEmpty());
                witness.put("stillCollapsing", state(journal, sid).equals("COLLAPSING"));
                witness.put("noWarnOrError", noWarnOrError(capture, sid));
                context.assertTrue(!witness.containsValue(false), "SEALED 的重試之後的原艙門（H9 (a)，" + (membrane ? "帶著量子膜" : "沒有量子膜") + "）：" + witness);
                hooks.release();
                M4PerTestUniverseProbe.complete(context);
            }, () -> "WAITING 的觀察沒有結束：witness=" + witness, hooks);
        });
    }

    // ---- H9 (b)：開服時就是永久原因 SEALED 的提醒（spec v5.10 §4 H9、§11.2「永久原因」、§12 SEALED 列） ----

    /**
     * H9 (b)：開服 attach 當下就在 journal（{@code simulateDoorStartup}）、collapse view 是永久原因 SEALED 的 session，每個 server instance 記一次 ERROR 提醒「需要管理員處理」：
     * 字句是開服時的提醒（{@link M5GameTestSupport#STARTUP_SEALED_REMINDER}，與進入 SEALED 時的「塌縮進入 SEALED」不同），帶 session=、chamber= 與目的地。三種能在 default run
     * 移除的永久原因各一份（{@code TRANSFER_RECOVERY_REQUIRED} 無法移除，留給 M5 collapse probe 的 {@code trr-restart}）。對照組：四種可重試的原因（含只在重啟之後重試的兩種）
     * 同樣標成開服時就在，以及一份沒有標成開服時就在的永久原因，都沒有這則 ERROR。原艙都在遠處（來源艙沒有載入）、參與者都是離線的隨機玩家：提醒不看這些（可重試的原因因此也不會
     * 重試）。之後 100 個 tick 仍各恰好一則、沒有其他 ERROR 或 WARN；record 都沒有被改寫。catalog 不變（per-test baseline）。不需要原生 checkpoint，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_sealed_startup_reminder", tickLimit = 400)
    public void permanently_sealed_sessions_at_startup_remind_the_admin_once(TestContext context) {
        M4PerTestUniverseProbe.begin(context,"permanently_sealed_sessions_at_startup_remind_the_admin_once");
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        hooks.add(() -> cleanupFixtures(server, owned));
        var capture = captureLogs(hooks);
        var far = context.getAbsolutePos(BlockPos.ORIGIN).add(6000, 70, 6000);
        var permanent = new LinkedHashMap<SealedReason, SessionRecoveryRecord>();
        var retryable = new LinkedHashMap<SealedReason, SessionRecoveryRecord>();
        SessionRecoveryRecord[] notAtStartup = {null};
        at(context, 1, hooks, () -> {
            int[] index = {0};
            // F6-3c-1（H9 quality Minor 5）：sealedFixture 在任何寫入之前就把 session 加入 owned（原本在 fixture 建好之後才加入）。
            java.util.function.Function<SealedReason, SessionRecoveryRecord> sealedAt = reason -> M5GameTestSupport.sealedFixture(server,
                    M4CandidateTestAccess.origin(UUID.randomUUID(), far.add(32 * index[0]++, 0, 0)), Map.of(UUID.randomUUID(), true), reason, owned);
            for (var reason : List.of(SealedReason.PROJECTION_CONFLICT, SealedReason.EXISTING_INELIGIBLE, SealedReason.AUTHORITY_CORRUPT))
                permanent.put(reason, sealedAt.apply(reason));
            for (var reason : List.of(SealedReason.PROJECTION_FAILED, SealedReason.ALLOCATION_IO_FAILED, SealedReason.MATERIALIZE_FAILED, SealedReason.AUTHORITY_UNREADABLE))
                retryable.put(reason, sealedAt.apply(reason));
            // 這個 tick 結束時塌縮流程第一次看到它們：標成開服 attach 當下就在 journal（不標的那一份是執行期間才出現的 session）。
            for (var record : List.copyOf(permanent.values())) M5GameTestSupport.simulateDoorStartup(server, record.sessionUuid());
            for (var record : List.copyOf(retryable.values())) M5GameTestSupport.simulateDoorStartup(server, record.sessionUuid());
            notAtStartup[0] = sealedAt.apply(SealedReason.PROJECTION_CONFLICT);
        });
        whenTicks(context, 2, 20, () -> permanent.values().stream().allMatch(record -> !reminders(capture, record).isEmpty()), first -> {
            at(context, first + 100, hooks, () -> {
                var witness = new LinkedHashMap<String, Boolean>();
                permanent.forEach((reason, record) -> {
                    var sid = record.sessionUuid();
                    var reminded = reminders(capture, record);
                    var universe = ((CollapseDestination.Universe) record.collapse().destination()).id().value();
                    witness.put(reason + ".remindedOnce", reminded.size() == 1
                            && capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).size() == 1);
                    witness.put(reason + ".namesTheSessionChamberAndDestination", reminded.size() == 1 && reminded.getFirst().contains("SEALED(" + reason + ")")
                            && reminded.getFirst().contains("需要管理員處理") && reminded.getFirst().contains("chamber=" + record.chamberUuid())
                            && reminded.getFirst().contains("目的地=UNIVERSE " + universe));
                });
                retryable.forEach((reason, record) -> witness.put(reason + ".notReminded",
                        capture.matching(org.apache.logging.log4j.Level.ERROR, record.sessionUuid().toString()).isEmpty()));
                witness.put("notAtStartupNotReminded", capture.matching(org.apache.logging.log4j.Level.ERROR, notAtStartup[0].sessionUuid().toString()).isEmpty());
                var all = new ArrayList<SessionRecoveryRecord>(permanent.values()); all.addAll(retryable.values()); all.add(notAtStartup[0]);
                witness.put("notTheEntryError", capture.matching(org.apache.logging.log4j.Level.ERROR, "塌縮進入 SEALED").stream()
                        .noneMatch(entry -> all.stream().anyMatch(record -> entry.message().contains(record.sessionUuid().toString()))));
                witness.put("noWarn", all.stream().allMatch(record -> capture.matching(org.apache.logging.log4j.Level.WARN, record.sessionUuid().toString()).isEmpty()));
                witness.put("recordsUntouched", all.stream().allMatch(record -> record.equals(journal.flushedRecords().get(record.sessionUuid()))
                        && record.equals(journal.records().get(record.sessionUuid()))));
                LOGGER.info("M5_SEALED_STARTUP_REMINDER first={} reminders={}", first, permanent.values().stream().map(record -> reminders(capture, record).size()).toList());
                context.assertTrue(!witness.containsValue(false), "開服時就是永久原因 SEALED 的提醒（H9 (b)）：" + witness);
                hooks.release();
                M4PerTestUniverseProbe.complete(context);
            });
        }, () -> "有上限的 tick 內沒有每一份永久原因 SEALED 都提醒：" + permanent.values().stream().map(record -> reminders(capture, record).size()).toList(), hooks);
    }

    /** 這份 record 的開服時的提醒（ERROR，含 {@link M5GameTestSupport#STARTUP_SEALED_REMINDER} 與 sid）的訊息。 */
    private static List<String> reminders(LogCapture capture, SessionRecoveryRecord record) {
        return capture.matching(org.apache.logging.log4j.Level.ERROR, M5GameTestSupport.STARTUP_SEALED_REMINDER, record.sessionUuid().toString()).stream()
                .map(LogCapture.Entry::message).toList();
    }

    /**
     * F4a-0 fix round 1（quality Q1）：mixin 讀取的兩個靜態 hook 位置（journal flush 探針、journal 寫出稽核）的安裝規則。(a) hooks 已釋放時安裝在
     * 登記移除時就丟出，位置不殘留；(b) 位置被占用時，第二次安裝立即丟 IllegalStateException、不覆蓋，訊息指出 hook、持有者與這次安裝的 test，
     * 安裝失敗的那組 hooks 釋放時也不動持有者的值；(c) 持有者的 hooks 釋放之後位置清空，可以再裝。「test 結束時（含 tickLimit 逾時）由
     * TestListener 移除殘留」不能在 test 自己裡面驗證，以 mutation 驗證（note F4a-0 的 Fix round 1）。不碰 journal、不建 fixture，各平台都執行。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_harness_hook_slots")
    public void harness_hook_slots_fail_fast_and_leave_no_residue(TestContext context) {
        var self = M5GameTestSupport.gameTestState(context).toString();
        // (a) hooks 已釋放：安裝在登記移除時就丟出，位置不殘留。先單獨判定：殘留會讓 (b) 的第一次安裝失敗、遮住原因。
        var empty = new LinkedHashMap<String, Boolean>();
        empty.put("startsEmpty", M5TestHooks.FLUSH_PROBE.get() == null && M5TestHooks.JOURNAL_AUDIT.get() == null);
        var released = new M2CorridorGameTests.TestHooks();
        released.release();
        java.util.function.Consumer<SessionRecoveryState> late = state -> { };
        var lateFailure = thrown(() -> installFlushProbe(context, late, released));
        empty.put("releasedHooksRejected", lateFailure instanceof IllegalStateException && lateFailure.getMessage().contains("已釋放"));
        empty.put("releasedHooksLeftNoProbe", M5TestHooks.FLUSH_PROBE.get() == null);
        empty.put("releasedHooksLeftNoAudit", thrown(() -> M5GameTestSupport.installJournalAudit(context, released)) instanceof IllegalStateException
                && M5TestHooks.JOURNAL_AUDIT.get() == null);
        context.assertTrue(!empty.containsValue(false), "M5 靜態 hook 位置（hooks 已釋放時安裝）：" + empty);
        var witness = new LinkedHashMap<String, Boolean>();
        var holder = new M2CorridorGameTests.TestHooks();
        var intruder = new M2CorridorGameTests.TestHooks();
        var again = new M2CorridorGameTests.TestHooks();
        try {
            // (b) 占用中：第二次安裝立即丟出、不覆蓋；安裝失敗的那組 hooks 釋放時不動持有者的值。
            java.util.function.Consumer<SessionRecoveryState> first = state -> { }, second = state -> { };
            installFlushProbe(context, first, holder);
            var audit = M5GameTestSupport.installJournalAudit(context, holder);
            var probeTaken = thrown(() -> installFlushProbe(context, second, intruder));
            var auditTaken = thrown(() -> M5GameTestSupport.installJournalAudit(context, intruder));
            witness.put("occupiedProbeRejected", probeTaken instanceof IllegalStateException && probeTaken.getMessage().contains("journal flush 探針 已被占用")
                    && probeTaken.getMessage().contains("持有者 " + self + "（執行中）") && probeTaken.getMessage().contains("這次安裝來自 " + self));
            witness.put("occupiedAuditRejected", auditTaken instanceof IllegalStateException && auditTaken.getMessage().contains("journal 寫出稽核 已被占用"));
            witness.put("occupiedNotOverwritten", M5TestHooks.FLUSH_PROBE.get() == first && M5TestHooks.JOURNAL_AUDIT.get() == audit);
            intruder.release();
            witness.put("intruderReleaseKeepsHolder", M5TestHooks.FLUSH_PROBE.get() == first && M5TestHooks.JOURNAL_AUDIT.get() == audit);
            // (c) 持有者釋放之後位置清空，可以再裝。
            holder.release();
            witness.put("holderReleaseLeavesNoResidue", M5TestHooks.FLUSH_PROBE.get() == null && M5TestHooks.JOURNAL_AUDIT.get() == null);
            installFlushProbe(context, second, again);
            witness.put("reinstallAfterRelease", M5TestHooks.FLUSH_PROBE.get() == second);
            again.release();
            witness.put("endsEmpty", M5TestHooks.FLUSH_PROBE.get() == null && M5TestHooks.JOURNAL_AUDIT.get() == null);
        } catch (RuntimeException | Error failure) {
            for (var hooks : List.of(holder, intruder, again)) hooks.releaseAfter(failure);
            throw failure;
        }
        context.assertTrue(!witness.containsValue(false), "M5 靜態 hook 位置：" + witness);
        context.complete();
    }

    /** 執行並回傳丟出的例外；沒有丟出時為 null。 */
    private static RuntimeException thrown(Runnable action) {
        try { action.run(); return null; } catch (RuntimeException failure) { return failure; }
    }

    // ---- F3d 的 helper（M5 共用的部分在 M5GameTestSupport） ----

    /** 真入場（不暫停塌縮流程）、等 358 扇側門發布之後交給 {@code ready}；逾時就關閉 fixture。 */
    private static void nativeSourceEntry(TestContext context, int people, java.util.function.BiConsumer<M2CorridorGameTests.NativeEntry, Integer> ready) {
        var fixture = new M2CorridorGameTests.NativeEntry(context, people, Direction.NORTH);
        var hooks = new M2CorridorGameTests.TestHooks();
        hooks.add(fixture::close);
        context.waitAndRun(2, fixture::power);
        // 走廊幾何每個 tick 最多寫 4096 格，358 扇門的走廊要建很多個 tick（GameTest server 不以 20 TPS 節流）；只設寬鬆的上限。
        whenTicks(context, 3, 20000, () -> fixture.activeReady() && fixture.pages.selectableDoors(fixture.record().sessionUuid()).size() == 358,
                tick -> ready.accept(fixture, tick), () -> "原生入場沒有發布 358 扇側門：record=" + (fixture.record() == null ? "ABSENT" : fixture.record().state()), hooks);
    }

    /** 讀出連線送出的 actionbar 訊息：translatable 的取 key，其他取文字。 */
    private static List<String> overlayKeys(ConnectedGameTestPlayer connection) {
        var keys = new ArrayList<String>();
        for (var message : connection.drainOutbound(GameMessageS2CPacket.class)) {
            if (!message.overlay()) continue;
            keys.add(message.content().getContent() instanceof TranslatableTextContent translatable ? translatable.getKey() : message.content().getString());
        }
        return keys;
    }

    private static String label(ConnectedGameTestPlayer connection) {
        return connection.player().getGameProfile().getName();
    }

    private static void setAuthorized(ServerWorld world, BlockPos pos, BlockState state) {
        ChamberProtectionService.get().authorizedMutation(world, pos, () -> world.setBlockState(pos, state, Block.NOTIFY_ALL));
    }

    /** 在 {@code tick} 把玩家移到艙外、下一個 tick 移回艙內：塌縮流程在這兩個 tick 結束時各觀察一次，構成一次「由艙外進入」。 */
    private static void reenter(TestContext context, PlayerEntity player, ChamberFrame frame, int tick, M2CorridorGameTests.TestHooks hooks) {
        at(context, tick, hooks, () -> moveOutside(player, frame));
        at(context, tick + 1, hooks, () -> moveInside(player, frame));
    }

    private static boolean originEnabled(MinecraftServer server, UUID chamber) {
        var record = ChamberRegistryState.get(server).registry().records().get(chamber);
        return record != null && record.enabled();
    }

    private static boolean returned(SessionRecoveryRecord record, UUID player) {
        return record != null && record.participants().stream().anyMatch(person -> person.playerUuid().equals(player) && person.returned());
    }

    private static boolean settled(SessionRecoveryRecord record) {
        return record != null && record.state() == SessionState.COLLAPSING && record.spaceLeases().isEmpty()
                && record.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
    }

    private static boolean dormantAndReleased(SessionRecoveryState journal, CorridorPageManager pages, UUID sid) {
        var record = journal.flushedRecords().get(sid);
        return record != null && record.state() == SessionState.MEASURED && MeasuredRecoveryPhase.from(record) == MeasuredRecoveryPhase.DORMANT
                && pages.measuredRecoveryComplete(sid);
    }

    /** G-RF 的兩個受控失敗訊息（{@link FlushFault}）。 */
    private static final String RETURN_FAULT = "F3d GameTest 受控：返還進度的 journal 寫出失敗";
    private static final String RELEASE_FAULT = "F3d GameTest 受控：清 lease 的 journal 寫出失敗";

    private static boolean bothCollapsing(SessionRecoveryRecord current, SessionRecoveryRecord durable) {
        return current != null && durable != null && current.state() == SessionState.COLLAPSING && durable.state() == SessionState.COLLAPSING;
    }

    private static long returnedCount(SessionRecoveryRecord record) {
        return record.participants().stream().filter(SessionRecoveryRecord.Participant::returned).count();
    }

    /** 斷電、等 presence 回 NONE 且原艙保護解除，再關閉 fixture 並完成。 */
    private static void powerOffAndClose(TestContext context, M2CorridorGameTests.NativeEntry fixture, int tick, Runnable complete) {
        if (context.getWorld().isReceivingRedstonePower(fixture.frame.controllerPos())) fixture.power();
        var chamber = fixture.controller().chamberUuid();
        whenTicks(context, tick, 200, () -> ChamberSessions.gateway().presence(fixture.server, chamber) == ChamberSessionGateway.Presence.NONE
                && ChamberProtectionService.get().mayMutate(context.getWorld(), fixture.frame.controllerPos()), done -> {
            try { complete.run(); } finally { fixture.close(); }
        }, () -> "斷電之後原艙沒有解除保護", null);
    }

    /**
     * {@code loaded} 的每個 chunk 以 ticket 維持 FULL（第一個是 frame 的基準），{@code missing} 不是 FULL；門格只寫進已載入的 chunk，
     * 結束時還原原狀態並移除 ticket。
     */
    private static void checkNotLoaded(ServerWorld world, List<String> failures, String name, List<ChunkPos> loaded, ChunkPos missing,
                                       int expectedDoorCells, java.util.function.Function<ChunkPos, ChamberFrame> frameIn) {
        var manager = world.getChunkManager();
        String p = name + ".";
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        // level 33 只維持 ticket 所在的 chunk 為 FULL，不把鄰接 chunk 提升到 FULL。
        loaded.forEach(chunkPos -> manager.addTicket(NOT_LOADED_TICKET, chunkPos, 0, chunkPos));
        try {
            Map<ChunkPos, WorldChunk> chunks = new LinkedHashMap<>();
            for (ChunkPos chunkPos : loaded) chunks.put(chunkPos, world.getChunk(chunkPos.x, chunkPos.z));
            ChamberFrame frame = frameIn.apply(loaded.getFirst());
            witness(failures, p + "missingNotFullBefore", manager.getWorldChunk(missing.x, missing.z) == null);
            BlockState closed = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
            for (BlockPos pos : door(frame)) {
                WorldChunk chunk = chunks.get(new ChunkPos(pos));
                if (chunk == null) continue; // 落在未載入 chunk 的門格不寫
                originals.put(pos.toImmutable(), chunk.getBlockState(pos));
                chunk.setBlockState(pos, closed, false);
            }
            witness(failures, p + "doorCellsInLoadedChunks", originals.size() == expectedDoorCells);
            ApplyResult result = attempt(() -> new ChamberDoorService().applyTarget(world, frame, DoorState.sealed(Direction.NORTH)));
            witness(failures, p + "notLoaded", result != null && result.outcome() == Outcome.NOT_LOADED && !result.activationBlocked());
            witness(failures, p + "missingStillNotFull", manager.getWorldChunk(missing.x, missing.z) == null);
            witness(failures, p + "nothingWritten", originals.keySet().stream()
                    .allMatch(pos -> chunks.get(new ChunkPos(pos)).getBlockState(pos).equals(closed)));
        } finally {
            originals.forEach((pos, state) -> {
                WorldChunk chunk = manager.getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
                if (chunk != null) chunk.setBlockState(pos, state, false);
            });
            loaded.forEach(chunkPos -> manager.removeTicket(NOT_LOADED_TICKET, chunkPos, 0, chunkPos));
        }
    }

    private static void checkMembraneDoor(TestContext context, ChamberFrame frame, List<String> failures) {
        ServerWorld world = context.getWorld();
        Direction facing = frame.outwardFacing();
        String p = facing.asString() + ".";
        List<BlockPos> door = door(frame);
        MembraneState sealed = MembraneState.sealedFor(facing);
        MembraneState passable = MembraneState.passableFor(facing);

        // (1) 直接寫入的 membrane：ChamberDetector／WorldChamberBlockView 一律 BULKHEAD_OPEN；真實碰撞只有 sealed。
        for (MembraneState membrane : List.of(MembraneState.NONE, sealed, passable)) {
            writeDoor(world, door, ignored -> membrane);
            String q = p + membrane.asString() + ".";
            witness(failures, q + "detectorOpen", detectorOpen(world, frame, door));
            witness(failures, q + "collision", door.stream().allMatch(pos -> blocked(world, pos) == membrane.sealed()));
        }
        writeDoor(world, door, index -> index % 2 == 0 ? sealed : passable);
        witness(failures, p + "mixed.detectorOpen", detectorOpen(world, frame, door));

        // (2) 既有的門切換（bulkhead onUse → WorldChamberDoorAdapter.toggle）：關門時清掉 membrane，再開是一般開門。
        PlayerEntity player = context.createMockPlayer(GameMode.SURVIVAL);
        BlockPos middle = ChamberGeometry.localToWorld(frame, 3, 3, 0);
        witness(failures, p + "toggleClose.accepted", use(world, frame, middle, player).isAccepted());
        witness(failures, p + "toggleClose.closedWithoutMembrane",
                door.stream().allMatch(pos -> doorIs(world, pos, false, MembraneState.NONE)));
        witness(failures, p + "toggleClose.detectorSealed", new ChamberDetector().validate(new WorldChamberBlockView(world), frame).sealed());
        witness(failures, p + "toggleOpen.accepted", use(world, frame, middle, player).isAccepted());
        witness(failures, p + "toggleOpen.plainOpen", door.stream().allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));

        // (3) 套用目標狀態 API。
        var service = new ChamberDoorService();
        step(world, frame, door, failures, p + "applySealed", service.applyTarget(world, frame, DoorState.sealed(facing)),
                Outcome.APPLIED, true, sealed);
        step(world, frame, door, failures, p + "applySealedAgain", service.applyTarget(world, frame, DoorState.sealed(facing)),
                Outcome.UNCHANGED, true, sealed);
        step(world, frame, door, failures, p + "applyPassable", service.applyTarget(world, frame, DoorState.passable(facing)),
                Outcome.APPLIED, true, passable);
        step(world, frame, door, failures, p + "applyClosed", service.applyTarget(world, frame, DoorState.CLOSED),
                Outcome.APPLIED, false, MembraneState.NONE);
        step(world, frame, door, failures, p + "applySealedFromClosed", service.applyTarget(world, frame, DoorState.sealed(facing)),
                Outcome.APPLIED, true, sealed);
        step(world, frame, door, failures, p + "applyOpen", service.applyTarget(world, frame, DoorState.OPEN),
                Outcome.APPLIED, true, MembraneState.NONE);

        // (4) 指令才寫得出的 open=false＋量子膜：讀取時視同關門，所以套用關門不重寫（UNCHANGED）；套用一般開門時一併正規化。
        step(world, frame, door, failures, p + "applyClosedForStale", service.applyTarget(world, frame, DoorState.CLOSED),
                Outcome.APPLIED, false, MembraneState.NONE);
        BlockPos stale = door.get(12);
        ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(stale,
                ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.MEMBRANE, sealed), Block.NOTIFY_ALL));
        ApplyResult staleClosed = attempt(() -> service.applyTarget(world, frame, DoorState.CLOSED));
        witness(failures, p + "staleClosed.unchanged", staleClosed != null && staleClosed.outcome() == Outcome.UNCHANGED
                && world.getBlockState(stale).get(QuantumBulkheadBlock.MEMBRANE) == sealed);
        ApplyResult staleOpened = attempt(() -> service.applyTarget(world, frame, DoorState.OPEN));
        witness(failures, p + "staleOpened.normalized", staleOpened != null && staleOpened.outcome() == Outcome.APPLIED
                && door.stream().allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));

        // (5) 不在 server thread：丟 IllegalStateException，不讀不寫。
        Throwable offThread = offThread(() -> service.applyTarget(world, frame, DoorState.sealed(facing)));
        witness(failures, p + "offThread.rejected", offThread instanceof IllegalStateException
                && door.stream().allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));

        // (6) 軸向與門的朝向不符是 caller 錯誤：丟 IllegalArgumentException，不寫任何一格。
        boolean rejectedAxis = false;
        try {
            service.applyTarget(world, frame, DoorState.sealed(facing.rotateYClockwise()));
        } catch (IllegalArgumentException expected) {
            rejectedAxis = true;
        }
        witness(failures, p + "axisGuard", rejectedAxis && door.stream().allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));

        // (7) 門板缺一格：DOOR_INCOMPLETE，其他 24 格都不寫。
        BlockPos hole = door.getFirst();
        ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(hole, Blocks.BEDROCK.getDefaultState(), Block.NOTIFY_ALL));
        ApplyResult incomplete = service.applyTarget(world, frame, DoorState.sealed(facing));
        witness(failures, p + "incompleteRejected", incomplete.outcome() == Outcome.DOOR_INCOMPLETE && !incomplete.activationBlocked()
                && door.stream().skip(1).allMatch(pos -> doorIs(world, pos, true, MembraneState.NONE)));
        ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(hole,
                ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, true), Block.NOTIFY_ALL));
    }

    private static void step(ServerWorld world, ChamberFrame frame, List<BlockPos> door, List<String> failures, String name,
                             ApplyResult result, Outcome expected, boolean open, MembraneState membrane) {
        witness(failures, name + ".outcome", result.outcome() == expected);
        witness(failures, name + ".activationNotBlocked", !result.activationBlocked() && !controller(world, frame).activationBlocked());
        witness(failures, name + ".allCells", door.stream().allMatch(pos -> doorIs(world, pos, open, membrane)));
        var structure = new ChamberDetector().validate(new WorldChamberBlockView(world), frame);
        witness(failures, name + ".detector", structure.valid() && structure.sealed() == !open);
    }

    /** 回傳結果；丟例外時記 log 並回 null（讓 witness 以 false 記錄，而不是中斷整個 test）。 */
    private static ApplyResult attempt(Supplier<ApplyResult> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            LOGGER.info("M5_COLLAPSE_ATTEMPT_THREW {}", failure.toString());
            return null;
        }
    }

    /** 在另一個 thread 執行並等它結束（最多 10 秒）；回傳它丟出的例外，正常回傳時為 null，逾時為 AssertionError。 */
    private static Throwable offThread(Runnable action) {
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable failure) {
                thrown.set(failure);
            }
        }, "m5-collapse-off-thread");
        worker.start();
        try {
            worker.join(10_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new AssertionError("等待 off-thread 呼叫時被中斷");
        }
        return worker.isAlive() ? new AssertionError("off-thread 呼叫逾時") : thrown.get();
    }

    private static boolean detectorOpen(ServerWorld world, ChamberFrame frame, List<BlockPos> door) {
        var view = new WorldChamberBlockView(world);
        var structure = new ChamberDetector().validate(view, frame);
        return door.stream().allMatch(pos -> view.cellAt(pos) == ChamberCell.BULKHEAD_OPEN) && structure.valid() && !structure.sealed();
    }

    private static boolean blocked(ServerWorld world, BlockPos pos) {
        return !world.isSpaceEmpty(new Box(pos).contract(0.25));
    }

    private static void writeDoor(ServerWorld world, List<BlockPos> door, IntFunction<MembraneState> membrane) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int index = 0; index < door.size(); index++) {
                world.setBlockState(door.get(index), ModBlocks.QUANTUM_BULKHEAD.getDefaultState()
                        .with(QuantumBulkheadBlock.OPEN, true).with(QuantumBulkheadBlock.MEMBRANE, membrane.apply(index)), Block.NOTIFY_ALL);
            }
        });
    }

    private static ActionResult use(ServerWorld world, ChamberFrame frame, BlockPos pos, PlayerEntity player) {
        return world.getBlockState(pos).onUse(world, player, new BlockHitResult(Vec3d.ofCenter(pos), frame.outwardFacing(), pos, false));
    }

    /**
     * 與 {@link ChamberGameTestBuilder} 的錨點相同。建置之前就先算好，建置途中失敗時也能清掉同一個範圍；建置之後以 equals 核對。
     */
    private static ChamberFrame fixtureFrame(TestContext context, Direction facing) {
        BlockPos anchor = switch (facing) {
            case NORTH -> ChamberGameTestBuilder.CONTROLLER;
            case SOUTH -> new BlockPos(7, 7, 10);
            case EAST -> new BlockPos(10, 7, 7);
            case WEST -> new BlockPos(4, 7, 7);
            default -> throw new IllegalArgumentException("fixture 僅支援水平朝向：" + facing);
        };
        return new ChamberFrame(context.getAbsolutePos(anchor), facing);
    }

    /** 移除 fixture 的 7×7×7：先移除 Controller，再清空其餘方塊；不觸發鄰格更新。 */
    private static void clearChamber(ServerWorld world, ChamberFrame frame) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            world.setBlockState(frame.controllerPos(), Blocks.AIR.getDefaultState(), QUIET);
            for (int x = 0; x < ChamberGeometry.OUTER_SIZE; x++) for (int y = 0; y < ChamberGeometry.OUTER_SIZE; y++)
                for (int z = 0; z < ChamberGeometry.OUTER_SIZE; z++) {
                    world.setBlockState(ChamberGeometry.localToWorld(frame, x, y, z), Blocks.AIR.getDefaultState(), QUIET);
                }
        });
    }

    private static boolean dry(ServerWorld world, BlockPos pos) {
        return world.getBlockState(pos).isAir() && world.getFluidState(pos).isEmpty();
    }

    private static String label(BlockState state) {
        return state.get(QuantumBulkheadBlock.OPEN) ? state.get(QuantumBulkheadBlock.MEMBRANE).asString() : "closed";
    }

    private static void witness(List<String> failures, String name, boolean ok) {
        if (!ok) failures.add(name);
    }

    /**
     * 完整的 witness 清單寫進 log（{@code M5_COLLAPSE_WITNESS_FAILED}）；斷言訊息只列前 8 項。原版會把失敗訊息寫進 lectern 的書，
     * 太長時那個 chunk 在停服存檔時會失敗。
     */
    private static void assertWitnesses(TestContext context, String title, List<String> failures) {
        if (failures.isEmpty()) return;
        LOGGER.error("M5_COLLAPSE_WITNESS_FAILED {} count={} witnesses={}", title, failures.size(), failures);
        String shown = failures.size() <= 8 ? failures.toString()
                : failures.subList(0, 8) + " …共 " + failures.size() + " 項（完整清單見 log M5_COLLAPSE_WITNESS_FAILED）";
        context.assertTrue(false, title + " witness 失敗：" + shown);
    }

    /** 封閉的 1×3 通道：wet（水源）｜bulkhead｜dry，四周與上下都是 bedrock。 */
    private record FluidPocket(BlockPos wet, BlockPos bulkhead, BlockPos dry, BlockState state) {
        static FluidPocket build(ServerWorld world, BlockPos wet, BlockState state) {
            for (int dx = -1; dx <= 3; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                boolean channel = dy == 0 && dz == 0 && dx >= 0 && dx <= 2;
                world.setBlockState(wet.add(dx, dy, dz), channel ? Blocks.AIR.getDefaultState() : Blocks.BEDROCK.getDefaultState(), QUIET);
            }
            world.setBlockState(wet.east(), state, QUIET);
            return new FluidPocket(wet.toImmutable(), wet.east().toImmutable(), wet.east(2).toImmutable(), state);
        }

        /** 先清通道（流體與 bulkhead），再清外殼；不觸發鄰格更新，不讓殘留的水流出。建置途中失敗時也以同一個範圍清除。 */
        static void clearAt(ServerWorld world, BlockPos wet) {
            for (BlockPos pos : List.of(wet, wet.east(), wet.east(2))) world.setBlockState(pos, Blocks.AIR.getDefaultState(), QUIET);
            for (int dx = -1; dx <= 3; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
                world.setBlockState(wet.add(dx, dy, dz), Blocks.AIR.getDefaultState(), QUIET);
            }
        }
    }
}
