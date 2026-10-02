package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberCell;
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
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
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
     * F3b fix round 1（I-1）：門板、Controller 或它們的鄰格所在 chunk 不是 FULL 時，applyTarget 不讀不寫、回 NOT_LOADED，
     * 也不強制載入。沿用 ChamberNoForceGameTests 的手法：遠處的 chunk 以 radius 0 的 ticket 維持 FULL，鄰接的 chunk 不是 FULL；
     * fixture 的方塊直接寫進那個 chunk（不經 World.setBlockState），不觸發鄰格更新，也不碰未載入的 chunk。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_collapse_not_loaded", tickLimit = 100)
    public void door_target_refuses_unloaded_chunks_without_forcing_them(TestContext context) {
        var world = context.getWorld();
        List<String> failures = new ArrayList<>();
        // A：門板最左一欄（local x=1）落在東側未載入的 chunk。
        checkNotLoaded(world, failures, "straddle", new ChunkPos(2176, 2048), new ChunkPos(2177, 2048),
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 14, 70, loaded.getStartZ() + 8), Direction.NORTH));
        // B：門板與 Controller 都在已載入的 chunk，但門外那一排（寫入時的 neighbor update 會讀）在北側未載入的 chunk。
        checkNotLoaded(world, failures, "outsideRow", new ChunkPos(2240, 2048), new ChunkPos(2240, 2047),
                loaded -> new ChamberFrame(new BlockPos(loaded.getStartX() + 8, 70, loaded.getStartZ()), Direction.NORTH));
        assertWitnesses(context, "F3b 未載入 chunk", failures);
        context.complete();
    }

    private static void checkNotLoaded(ServerWorld world, List<String> failures, String name, ChunkPos loaded, ChunkPos missing,
                                       java.util.function.Function<ChunkPos, ChamberFrame> frameIn) {
        var manager = world.getChunkManager();
        String p = name + ".";
        Map<BlockPos, BlockState> originals = new LinkedHashMap<>();
        // level 33 只維持 loaded 為 FULL，不把鄰接 chunk 提升到 FULL。
        manager.addTicket(NOT_LOADED_TICKET, loaded, 0, loaded);
        try {
            WorldChunk chunk = world.getChunk(loaded.x, loaded.z);
            ChamberFrame frame = frameIn.apply(loaded);
            witness(failures, p + "missingNotFullBefore", manager.getWorldChunk(missing.x, missing.z) == null);
            BlockState closed = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
            for (BlockPos pos : door(frame)) {
                if (!new ChunkPos(pos).equals(loaded)) continue; // 落在未載入 chunk 的門格不寫
                originals.put(pos.toImmutable(), chunk.getBlockState(pos));
                chunk.setBlockState(pos, closed, false);
            }
            witness(failures, p + "doorPartlyInMissing", originals.size() == (name.equals("straddle") ? 20 : 25));
            ApplyResult result = attempt(() -> new ChamberDoorService().applyTarget(world, frame, DoorState.sealed(Direction.NORTH)));
            witness(failures, p + "notLoaded", result != null && result.outcome() == Outcome.NOT_LOADED && !result.activationBlocked());
            witness(failures, p + "missingStillNotFull", manager.getWorldChunk(missing.x, missing.z) == null);
            witness(failures, p + "nothingWritten", originals.keySet().stream().allMatch(pos -> chunk.getBlockState(pos).equals(closed)));
        } finally {
            WorldChunk chunk = manager.getWorldChunk(loaded.x, loaded.z);
            if (chunk != null) originals.forEach((pos, state) -> chunk.setBlockState(pos, state, false));
            manager.removeTicket(NOT_LOADED_TICKET, loaded, 0, loaded);
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
