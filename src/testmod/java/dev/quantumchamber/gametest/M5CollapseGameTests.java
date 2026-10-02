package dev.quantumchamber.gametest;

import com.sun.jna.Platform;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberCell;
import dev.quantumchamber.chamber.ChamberMaintenanceInteraction;
import dev.quantumchamber.chamber.ChamberOccupantService;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.transfer.ChamberReturnPlacement;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
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
import java.util.Optional;
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
     * checked COLLAPSING（Destination=SOURCE），兩人都收到「量子態塌縮。」；未返還的兩人被凍結，同一個 tick 各自收到一則位置修正（節流以玩家
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
            try {
                capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
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
                    after.put("noWarnOrError", capture.matching(org.apache.logging.log4j.Level.WARN, sid.toString()).isEmpty()
                            && capture.matching(org.apache.logging.log4j.Level.ERROR, sid.toString()).isEmpty());
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
            var fault = new ReturnWriteFault(server, sid);
            LogCapture capture;
            try {
                capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
                M4CandidateDoorGameTests.m5FlushProbe = fault;
                hooks.add(() -> { if (M4CandidateDoorGameTests.m5FlushProbe == fault) M4CandidateDoorGameTests.m5FlushProbe = null; });
                interact(fixture.target, sourceDoor(fixture).cell(), fixture.players.getFirst().player());
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            boolean[] overwrote = {false};
            at(context, ready + 1, hooks, () -> context.assertTrue(state(journal, sid).equals("COLLAPSING"),
                    "選門之後的下一個 tick 應已 checked COLLAPSING：record=" + state(journal, sid)));
            whenTicks(context, ready + 1, 400, () -> fault.returnFaultTick >= 0, faulted -> {
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
                    witness.put("neverOverwroteAnotherReturn", !overwrote[0]);
                    witness.put("noRetryBeforeBackoff", fault.sameWriteRetries.stream().allMatch(tick -> tick >= fault.returnFaultTick + 200));
                    witness.put("confirmedNotBeforeBackoff", confirmed >= 0 && (fault.sameWriteRetries.isEmpty()
                            || fault.sameWriteRetries.getFirst() >= fault.returnFaultTick + 200));
                    var pending = capture.matching(org.apache.logging.log4j.Level.WARN, "返還保留 pending", sid.toString());
                    witness.put("oneWarnWithCause", pending.size() == 1 && pending.getFirst().thrown() != null
                            && pending.getFirst().message().contains(ReturnWriteFault.RETURN_FAULT));
                    // 清 lease 的寫出也失敗一次：第一次看到 SETTLED 的那個 tick，space 還在、record 還在（沒有離開 SETTLED）。record 若在
                    // 同一個 tick 已經不在（提早離開 SETTLED），也在這裡停下來取證。
                    whenTicks(context, confirmed + 1, 20000, () -> settled(journal.flushedRecords().get(sid)) || !journal.flushedRecords().containsKey(sid), settledTick -> {
                        witness.put("releaseFaultedOnce", fault.releaseFaults == 1);
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
                            context.assertTrue(!witness.containsValue(false), "返還與清 lease 的 roll-forward：" + witness + " faultTick=" + fault.returnFaultTick
                                    + " retries=" + fault.sameWriteRetries + " confirmed=" + confirmed);
                            tearingDown[0] = true;
                            hooks.release();
                            powerOffAndClose(context, fixture, gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                        }, () -> "SETTLED 之後沒有走 SOURCE fast path：record=" + state(journal, sid), hooks);
                    }, () -> "返還確認之後沒有完成清理：record=" + state(journal, sid) + " releaseFaults=" + fault.releaseFaults, hooks);
                }, () -> "返還寫出一直沒有確認：retries=" + fault.sameWriteRetries, hooks);
            }, () -> "返還寫出的受控失敗沒有命中：record=" + state(journal, sid), hooks);
        });
    }

    /**
     * F3d（spec §2、§3 詮釋 3、§12、Review Focus 7）：兩位玩家選 SOURCE 門，第二位在選門的同一個 tick 斷線。塌縮照常提交，第一位返還；
     * 第二位未返還，走廊不消失、塌縮停在 RETURN_PLAYERS。已返還的第一位可以開關原艙門，塌縮不結束；他的 Buff 被移除也不丟例外、不記 ERROR。
     * 第二位重新連線之後，在 JOIN 的下一個 tick 返還，清理完成後走 SOURCE fast path。
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
                capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
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
                        witness.put("buffLapseNoErrorOrReconcileWarn", capture.matching(org.apache.logging.log4j.Level.ERROR).isEmpty()
                                && capture.matching(org.apache.logging.log4j.Level.WARN, "session 協調失敗").isEmpty());
                        witness.put("buffLapseKeepsCollapse", record.equals(journal.flushedRecords().get(sid)));
                        context.assertTrue(!witness.containsValue(false), "離線參與者未返還期間：" + witness);
                        rejoined[0] = new ConnectedGameTestPlayer(world, profile);
                        int joined = rejoined[0].joinTick();
                        whenTicks(context, onlineBack + 7, 20000, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                            var after = new LinkedHashMap<String, Boolean>();
                            var back = rejoined[0].player();
                            after.put("rejoinedReturnedInside", back.getServerWorld() == world
                                    && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame), back.getBoundingBox()));
                            after.put("returnedAfterJoin", joined >= 0);
                            after.put("doorPlainOpen", doorAll(world, fixture.frame, true, MembraneState.NONE));
                            after.put("oneSourceInfo", capture.matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                            after.put("runtimeAndTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                            context.assertTrue(!after.containsValue(false), "重新連線之後：" + after);
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
     * 參與者；兩者都不佔用 P，所以 P 可以在真的原艙開始 session B。P 斷線時，依玩家查找只找到 B：B 確實寫成 checked RETURNING，A 與 A′
     * 都沒被改寫。P 重新連線後返還，B 完成收尾；A、A′ 以終局 API 移除。
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
        var frame = fixtureFrame(context, Direction.NORTH);
        var power = ChamberGameTestBuilder.CONTROLLER.up();
        var hooks = new M2CorridorGameTests.TestHooks();
        var owned = new ArrayList<UUID>();
        ConnectedGameTestPlayer[] connection = {null};
        // 已註冊的原艙比照其他原生 test 留在原地（只斷電）；不清除方塊，避免留下沒有 Controller 的 registry record。
        hooks.add(() -> { if (connection[0] != null) connection[0].close(); });
        hooks.add(() -> context.setBlockState(power, Blocks.AIR));
        hooks.add(() -> cleanupFixtures(server, owned));
        LogCapture[] capture = {null};
        try {
            ChamberGameTestBuilder.buildForSession(context, false, Direction.NORTH, false);
            connection[0] = new ConnectedGameTestPlayer(world);
            connection[0].player().setNoGravity(true);
            moveOutside(connection[0].player(), frame);
            capture[0] = new LogCapture("quantumchamber"); hooks.add(capture[0]::close);
        } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        var p = connection[0].player();
        context.waitAndRun(2, () -> context.setBlockState(power, Blocks.REDSTONE_BLOCK));
        whenTicks(context, 3, 40, () -> registeredIdle(world, frame), registered -> {
            var chamber = controller(world, frame).chamberUuid();
            var q = UUID.randomUUID();
            var busy = M4CandidateTestAccess.syntheticSession(server, q, 10031);
            var other = busy.withProgress(busy.participants(), busy.spaceLeases(), SessionState.RETURNING, false);
            journal.put(other); journal.flush(server); owned.add(other.sessionUuid());
            var dormant = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                    new LinkedHashMap<>(Map.of(p.getUuid(), true, q, true)), List.of(), false);
            journal.put(dormant); journal.flush(server); owned.add(dormant.sessionUuid());
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
                witness.put("noCommitYet", capture[0].matching(org.apache.logging.log4j.Level.INFO, "塌縮已提交", sid.toString()).isEmpty());
                moveOutside(p, frame);
                overlayKeys(connection[0]);
            });
            // (d) P 由艙外進入：同一個 tick 結束時提交（Q 在別的 session 不影響）。
            at(context, t + 14, hooks, () -> moveInside(p, frame));
            at(context, t + 15, hooks, () -> {
                var committed = journal.flushedRecords().get(sid);
                witness.put("entryEdgeCommits", committed != null && committed.state() == SessionState.COLLAPSING
                        && committed.collapse().destination() instanceof CollapseDestination.Source);
                witness.put("busyParticipantDidNotBlock", other.equals(journal.flushedRecords().get(other.sessionUuid())));
                witness.put("dormantResumeTakesTicket", M4CandidateTestAccess.sourceTicketHeld(sid));
                witness.put("collapsedMessage", overlayKeys(connection[0]).contains(COLLAPSED_KEY));
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
                witness.put("oneSourceInfo", capture[0].matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", sid.toString()).size() == 1);
                witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                witness.put("ticketReleased", !M4CandidateTestAccess.sourceTicketHeld(sid) && !M4CandidateTestAccess.runtimeHeld(sid));
                context.assertTrue(!witness.containsValue(false), "DORMANT 的邊緣觸發與 WAITING（a～d）：" + witness);
                // (e) 開服 attach 當下就在 journal 的 DORMANT 收據：第一次觀察到 P 已在艙內即觸發（P 不必進出）。
                var startup = M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), List.of(), false);
                journal.put(startup); journal.flush(server); owned.add(startup.sessionUuid());
                M4CandidateTestAccess.markStartup(server, startup.sessionUuid(), false);
                at(context, t + 28, hooks, () -> {
                    witness.put("startupFirstObservationResumes", !journal.flushedRecords().containsKey(startup.sessionUuid())
                            && capture[0].matching(org.apache.logging.log4j.Level.INFO, "SOURCE 塌縮完成", startup.sessionUuid().toString()).size() == 1);
                    witness.put("noWarnOrError", capture[0].matching(org.apache.logging.log4j.Level.WARN, sid.toString()).isEmpty()
                            && capture[0].matching(org.apache.logging.log4j.Level.ERROR).isEmpty());
                    context.assertTrue(!witness.containsValue(false), "DORMANT 的邊緣觸發與 WAITING：" + witness);
                    journal.remove(other.sessionUuid()); journal.flush(server);
                    hooks.release();
                    M4PerTestUniverseProbe.complete(context);
                });
            });
        }, () -> "原艙沒有註冊並進入 IDLE", hooks);
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
            hooks.add(() -> M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", true));
            var fault = new CommitFault(server, sid);
            LogCapture capture;
            try {
                capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
                M4CandidateDoorGameTests.m5FlushProbe = fault;
                hooks.add(() -> { if (M4CandidateDoorGameTests.m5FlushProbe == fault) M4CandidateDoorGameTests.m5FlushProbe = null; });
                interact(fixture.target, sourceDoor(fixture).cell(), p);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, ready + 1, hooks, () -> context.assertTrue(fault.attempts.size() == 1 && state(journal, sid).equals("MEASURED"),
                    "選門之後的第一個 tick 結束時應已嘗試提交一次（受控失敗）：attempts=" + fault.attempts + " record=" + state(journal, sid)));
            whenTicks(context, ready + 1, 20000, () -> dormantAndReleased(journal, fixture.pages, sid), dormant -> {
                // 退避以 server tick 計；排程以 test tick 計。兩者每個 tick 都加一，差值固定。
                int offset = server.getTicks() - dormant;
                witness.put("firstAttemptFailedOnce", fault.attempts.size() == 1);
                witness.put("fellBackToDormantWithPlayerHome", p.getServerWorld() == context.getWorld()
                        && ChamberOccupantService.contains(ChamberGeometry.interiorBox(fixture.frame), p.getBoundingBox()));
                witness.put("neverCommitted", journal.flushedRecords().get(sid).state() == SessionState.MEASURED);
                int failedAt = fault.attempts.getFirst();
                // 退避期內的邊緣觸發：不嘗試提交。
                reenter(context, p, fixture.frame, dormant + 1, hooks);
                at(context, dormant + 4, hooks, () -> witness.put("backoffSuppressesEdge", fault.attempts.size() == 1));
                // 200 ticks 之後的邊緣觸發（進入的那個 tick 在 server tick failedAt+200 之後）：嘗試（仍受控失敗），退避加倍為 400。
                int second = Math.max(dormant + 6, failedAt + 200 - offset);
                reenter(context, p, fixture.frame, second, hooks);
                at(context, second + 3, hooks, () -> {
                    witness.put("retriedAfter200", fault.attempts.size() == 2 && fault.attempts.get(1) >= failedAt + 200);
                    fault.armed = false;
                    M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", false);
                    int third = fault.attempts.get(1) + 400 - offset;
                    reenter(context, p, fixture.frame, third, hooks);
                    at(context, third + 3, hooks, () -> {
                        witness.put("notReadyRejectsWithoutWriting", fault.attempts.size() == 2 && journal.flushedRecords().get(sid).state() == SessionState.MEASURED);
                        witness.put("notReadyWarned", !capture.matching(org.apache.logging.log4j.Level.WARN, "M5 authority 尚未初始化成功", sid.toString()).isEmpty());
                        M4CandidateTestAccess.set(journal, "m5ReadyThisInstance", true);
                        // 被拒的那一次在 test tick third+1 結束時發生，退避加倍為 800。
                        int fourth = third + 1 + 800;
                        reenter(context, p, fixture.frame, fourth, hooks);
                        whenTicks(context, fourth + 2, 40, () -> !journal.flushedRecords().containsKey(sid), gone -> {
                            witness.put("committedAfter800", fault.attempts.size() == 3);
                            witness.put("twoCommitWarnings", capture.matching(org.apache.logging.log4j.Level.WARN, "提交 MEASURED→COLLAPSING", sid.toString()).size() == 2);
                            witness.put("doorPlainOpen", doorAll(context.getWorld(), fixture.frame, true, MembraneState.NONE));
                            context.assertTrue(!witness.containsValue(false), "提交失敗的 fallback 與退避：" + witness + " attempts=" + fault.attempts
                                    + " offset=" + offset);
                            tearingDown[0] = true;
                            hooks.release();
                            powerOffAndClose(context, fixture, gone + 1, () -> M4PerTestUniverseProbe.complete(context));
                        }, () -> "恢復之後沒有完成：record=" + state(journal, sid) + " attempts=" + fault.attempts, hooks);
                    });
                });
            }, () -> "提交失敗之後沒有回到 DORMANT：record=" + state(journal, sid) + " attempts=" + fault.attempts, hooks);
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
            capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
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
            capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
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

    // ---- F3d 的 helper ----

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

    private record SourceDoor(DoorKey key, BlockPos cell) { }

    /** 這個 session 第一個 mapping 裡的一扇 SOURCE 候選門（依 station 與側別排序取第一扇），以及它可選擇的門格。 */
    private static SourceDoor sourceDoor(M2CorridorGameTests.NativeEntry fixture) {
        var sid = fixture.record().sessionUuid();
        var view = fixture.pages.currentMappings(sid).instances().getFirst();
        return fixture.pages.selectableDoors(sid).stream()
                .filter(key -> fixture.pages.flushedCandidate(key).orElse(null) instanceof QuantumCandidate.Source)
                .sorted(Comparator.comparingLong(DoorKey::logicalStationIndex).thenComparing(DoorKey::wallSide))
                .map(key -> new SourceDoor(key, doorCell(view, key)))
                .filter(door -> fixture.pages.selectableDoor(fixture.target, door.cell()).equals(Optional.of(door.key())))
                .findFirst().orElseThrow(() -> new IllegalStateException("358 扇側門裡找不到 SOURCE 候選門"));
    }

    private static BlockPos doorCell(CorridorPageManager.MappingView view, DoorKey key) {
        var frame = new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(), 3).up(6), view.outwardFacing());
        return CorridorGeometry.block(frame, key.wallSide() == DoorKey.DoorWallSide.NEGATIVE_LATERAL ? 0 : 6, 1,
                Math.toIntExact(key.logicalStationIndex() * 8 + 1 - view.logicalAnchorBlock()));
    }

    static ActionResult interact(ServerWorld world, BlockPos pos, PlayerEntity player) {
        return world.getBlockState(pos).onUse(world, player, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
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

    static boolean doorAll(ServerWorld world, ChamberFrame frame, boolean open, MembraneState membrane) {
        return door(frame).stream().allMatch(pos -> doorIs(world, pos, open, membrane));
    }

    private static void setAuthorized(ServerWorld world, BlockPos pos, BlockState state) {
        ChamberProtectionService.get().authorizedMutation(world, pos, () -> world.setBlockState(pos, state, Block.NOTIFY_ALL));
    }

    static void moveInside(PlayerEntity player, ChamberFrame frame) {
        var position = ChamberSpaceCoordinates.position(frame, 3.5, 1, 3.5);
        ((net.minecraft.server.network.ServerPlayerEntity) player).teleport((ServerWorld) player.getWorld(), position.x, position.y, position.z, Set.of(), 0, 0);
    }

    static void moveOutside(PlayerEntity player, ChamberFrame frame) {
        var position = ChamberSpaceCoordinates.position(frame, 3.5, 1, -2.5);
        ((net.minecraft.server.network.ServerPlayerEntity) player).teleport((ServerWorld) player.getWorld(), position.x, position.y, position.z, Set.of(), 0, 0);
    }

    /** 在 {@code tick} 把玩家移到艙外、下一個 tick 移回艙內：塌縮流程在這兩個 tick 結束時各觀察一次，構成一次「由艙外進入」。 */
    private static void reenter(TestContext context, PlayerEntity player, ChamberFrame frame, int tick, M2CorridorGameTests.TestHooks hooks) {
        at(context, tick, hooks, () -> moveOutside(player, frame));
        at(context, tick + 1, hooks, () -> moveInside(player, frame));
    }

    static boolean registeredIdle(ServerWorld world, ChamberFrame frame) {
        return world.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity controller
                && controller.chamberUuid() != null && controller.chamberState() == dev.quantumchamber.chamber.ChamberState.IDLE;
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

    static String state(SessionRecoveryState journal, UUID sid) {
        var record = journal.flushedRecords().get(sid);
        return record == null ? "ABSENT" : record.state().name();
    }

    /**
     * M5 fixture：MEASURED 收據 put＋flush 之後，只經 commitRestore（M→C、C→R）或 commitRollForward（C→S）進入指定狀態；全部在同一個 callback
     * 內完成，塌縮流程看不到中間狀態。{@code suspend} 為真時先暫停這個 session 的塌縮流程（fixture 只用來驗證別的規則）。
     */
    private static SessionRecoveryRecord collapseFixture(MinecraftServer server, ChamberOriginAuthority origin, Map<UUID, Boolean> people,
            List<SessionRecoveryRecord.SpaceLease> leases, boolean newCandidate, SessionState phase, boolean suspend) {
        var journal = SessionRecoveryState.get(server);
        var measured = M4CandidateTestAccess.measuredReceipt(server, origin, people, leases, newCandidate);
        if (suspend) M4CandidateDoorGameTests.suspendCollapse(measured.sessionUuid());
        journal.put(measured); journal.flush(server);
        var record = M4CandidateTestAccess.collapsing(measured, server.getOverworld().getTime());
        require(journal.commitRestore(server, measured, record), "MEASURED→COLLAPSING");
        if (phase == SessionState.PASSAGE_READY) {
            var next = record.withCollapse(SessionState.PASSAGE_READY, record.collapse().withPassage(server.getOverworld().getTime()));
            require(journal.commitRestore(server, record, next), "COLLAPSING→PASSAGE_READY"); record = next;
        } else if (phase == SessionState.SEALED) {
            var next = record.withCollapse(SessionState.SEALED, record.collapse().sealed(SealedReason.PROJECTION_FAILED));
            require(journal.commitRollForward(server, record, next), "COLLAPSING→SEALED"); record = next;
        }
        return record;
    }

    private static void require(SessionRecoveryState.CommitResult result, String edge) {
        if (!result.committed()) throw new IllegalStateException("M5 fixture " + edge + " 未確認：" + result);
    }

    /** 收尾：還在 journal 的 fixture——塌縮狀態以終局 API、MEASURED 以 testmod 反射、其他以一般 remove——移除，再以正式 API 釋放 runtime 與 ticket。 */
    private static void cleanupFixtures(MinecraftServer server, List<UUID> owned) {
        var journal = SessionRecoveryState.get(server);
        for (var sid : List.copyOf(owned)) {
            var record = journal.flushedRecords().get(sid);
            if (record != null) {
                if (record.state().collapseState()) M4CandidateTestAccess.terminateCollapseFixture(server, sid);
                else if (record.state() == SessionState.MEASURED) M4CandidateTestAccess.removeMeasuredForTeardown(server, sid);
                else { journal.remove(sid); journal.flush(server); }
            }
            if (!journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid)) M4CandidateTestAccess.releaseAfterTerminal(server, sid);
        }
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

    /** 在 {@code tick} 執行；例外或斷言失敗時先釋放 hooks（可為 null）。 */
    static void at(TestContext context, long tick, M2CorridorGameTests.TestHooks hooks, Runnable action) {
        context.runAtTick(tick, () -> {
            try { action.run(); }
            catch (RuntimeException | Error failure) { if (hooks != null) hooks.releaseAfter(failure); throw failure; }
        });
    }

    /** 以 server tick 計的有界等待（同 M4 的 whenWithinTicks）：逾時、斷言失敗或例外時先釋放 hooks（可為 null）。 */
    static void whenTicks(TestContext context, int tick, int ticks, java.util.function.BooleanSupplier condition, java.util.function.IntConsumer ready,
            Supplier<String> diagnostics, M2CorridorGameTests.TestHooks hooks) {
        whenTicksUntil(context, tick, tick + ticks, condition, ready, diagnostics, hooks);
    }

    private static void whenTicksUntil(TestContext context, int tick, int last, java.util.function.BooleanSupplier condition, java.util.function.IntConsumer ready,
            Supplier<String> diagnostics, M2CorridorGameTests.TestHooks hooks) {
        context.runAtTick(tick, () -> {
            try {
                if (condition.getAsBoolean()) ready.accept(tick);
                else {
                    context.assertTrue(tick < last, "有界等待逾時（至 tick " + last + "）：" + diagnostics.get());
                    whenTicksUntil(context, tick + 1, last, condition, ready, diagnostics, hooks);
                }
            } catch (RuntimeException | Error failure) {
                if (hooks != null) hooks.releaseAfter(failure);
                throw failure;
            }
        });
    }

    /**
     * 返還寫出的受控失敗（spec §6 roll-forward）：COLLAPSING 的 current 比 flushed 多返還一位時，第一次寫出在 flush 開頭失敗；之後同一份 current
     * （仍只多那一位）的寫出記下 tick（RETRY_FLUSH）。清 lease 的寫出（current 已清空、flushed 仍有 lease）也受控失敗一次。
     */
    private static final class ReturnWriteFault implements java.util.function.Consumer<SessionRecoveryState> {
        static final String RETURN_FAULT = "F3d GameTest 受控：返還進度的 journal 寫出失敗";
        static final String RELEASE_FAULT = "F3d GameTest 受控：清 lease 的 journal 寫出失敗";
        final MinecraftServer server; final UUID sid;
        volatile int returnFaultTick = -1, releaseFaults;
        final List<Integer> sameWriteRetries = new java.util.concurrent.CopyOnWriteArrayList<>();
        ReturnWriteFault(MinecraftServer server, UUID sid) { this.server = server; this.sid = sid; }
        @Override public void accept(SessionRecoveryState journal) {
            var current = journal.records().get(sid); var durable = journal.flushedRecords().get(sid);
            if (current == null || durable == null || current.state() != SessionState.COLLAPSING || durable.state() != SessionState.COLLAPSING) return;
            long staged = current.participants().stream().filter(SessionRecoveryRecord.Participant::returned).count();
            long flushed = durable.participants().stream().filter(SessionRecoveryRecord.Participant::returned).count();
            if (staged == 1 && flushed == 0) {
                if (returnFaultTick < 0) { returnFaultTick = server.getTicks(); throw new IllegalStateException(RETURN_FAULT); }
                sameWriteRetries.add(server.getTicks());
                return;
            }
            if (current.spaceLeases().isEmpty() && !durable.spaceLeases().isEmpty() && releaseFaults == 0) {
                releaseFaults++;
                throw new IllegalStateException(RELEASE_FAULT);
            }
        }
    }

    /** 提交 COLLAPSING 的寫出（current 已是 COLLAPSING、flushed 仍是 MEASURED）：記下每次嘗試的 tick，武裝時在 flush 開頭受控失敗。 */
    private static final class CommitFault implements java.util.function.Consumer<SessionRecoveryState> {
        final MinecraftServer server; final UUID sid;
        final List<Integer> attempts = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean armed = true;
        CommitFault(MinecraftServer server, UUID sid) { this.server = server; this.sid = sid; }
        @Override public void accept(SessionRecoveryState journal) {
            var current = journal.records().get(sid); var durable = journal.flushedRecords().get(sid);
            if (current == null || durable == null || current.state() != SessionState.COLLAPSING || durable.state() != SessionState.MEASURED) return;
            attempts.add(server.getTicks());
            if (armed) throw new IllegalStateException("F3d GameTest 受控：提交 COLLAPSING 的 journal 寫出失敗");
        }
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

    private static boolean doorIs(ServerWorld world, BlockPos pos, boolean open, MembraneState membrane) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(ModBlocks.QUANTUM_BULKHEAD) && state.get(QuantumBulkheadBlock.OPEN) == open
                && state.get(QuantumBulkheadBlock.MEMBRANE) == membrane;
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

    private static ChamberControllerBlockEntity controller(ServerWorld world, ChamberFrame frame) {
        return (ChamberControllerBlockEntity) world.getBlockEntity(frame.controllerPos());
    }

    private static List<BlockPos> door(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>(25);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        return positions;
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
