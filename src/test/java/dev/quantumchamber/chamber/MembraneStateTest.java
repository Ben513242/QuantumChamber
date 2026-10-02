package dev.quantumchamber.chamber;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quantumchamber.chamber.ChamberDoorService.ApplyResult.Outcome;
import dev.quantumchamber.chamber.ChamberDoorService.DoorState;
import dev.quantumchamber.registry.ModBlocks;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidFillable;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.world.BlockView;
import net.minecraft.world.EmptyBlockView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * F3b（spec §10.1）：bulkhead 的 {@code membrane} 屬性、門的狀態守門、逐狀態覆寫、資源，以及 25 格門的「套用目標狀態」核心。
 * 世界內的行為（ChamberDetector、真實碰撞、toggle 關門、rollback 失敗與 activationBlocked、流體）由 testmod 的
 * {@code M5CollapseGameTests} 驗證。
 */
class MembraneStateTest {
    private static final List<MembraneState> MEMBRANES =
            List.of(MembraneState.SEALED_X, MembraneState.SEALED_Z, MembraneState.PASSABLE_X, MembraneState.PASSABLE_Z);

    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        // server 載入 data pack 後以 Blocks.refreshShapeCache() 建立每個 state 的 shape cache（isSolid、isFullCube 等都依賴它）；
        // JUnit 不經過那一步，所以對 bulkhead 的 10 個 state 明確建立，與 runtime 一致。
        ModBlocks.QUANTUM_BULKHEAD.getStateManager().getStates().forEach(BlockState::initShapeCache);
    }

    // ---- MembraneState ----

    @Test
    void axisIsTheHorizontalAxisOfTheDoorPlaneForAllFourFacings() {
        Map<Direction, Direction.Axis> expected = Map.of(
                Direction.NORTH, Direction.Axis.X, Direction.SOUTH, Direction.Axis.X,
                Direction.EAST, Direction.Axis.Z, Direction.WEST, Direction.Axis.Z);
        List<Executable> checks = new ArrayList<>();
        for (var entry : expected.entrySet()) {
            Direction facing = entry.getKey();
            Direction.Axis axis = entry.getValue();
            checks.add(() -> assertEquals(axis, MembraneState.axisFor(facing), facing + " 的門平面水平軸"));
            // 與 ChamberGeometry 對照：門板 local x 方向（25 格的左右）正是 axis，local z（門的法線）不是。
            var frame = new ChamberFrame(new BlockPos(100, 64, -40), facing);
            BlockPos left = ChamberGeometry.localToWorld(frame, 1, 3, 0);
            BlockPos right = ChamberGeometry.localToWorld(frame, 5, 3, 0);
            BlockPos inward = ChamberGeometry.localToWorld(frame, 1, 3, 1);
            checks.add(() -> assertEquals(4, Math.abs(axis.choose(right.getX() - left.getX(), 0, right.getZ() - left.getZ())),
                    facing + "：門板左右兩端只沿 axis 相差 4 格"));
            checks.add(() -> assertEquals(0, axis.choose(inward.getX() - left.getX(), 0, inward.getZ() - left.getZ()),
                    facing + "：門的法線方向不在 axis 上"));
            MembraneState sealed = axis == Direction.Axis.X ? MembraneState.SEALED_X : MembraneState.SEALED_Z;
            MembraneState passable = axis == Direction.Axis.X ? MembraneState.PASSABLE_X : MembraneState.PASSABLE_Z;
            checks.add(() -> assertEquals(sealed, MembraneState.sealedFor(facing), facing + " sealed"));
            checks.add(() -> assertEquals(passable, MembraneState.passableFor(facing), facing + " passable"));
            for (MembraneState membrane : MembraneState.values()) {
                boolean fits = membrane == MembraneState.NONE || membrane.axis().orElseThrow() == axis;
                checks.add(() -> assertEquals(fits, membrane.fits(facing), facing + " fits " + membrane));
            }
        }
        for (Direction vertical : List.of(Direction.UP, Direction.DOWN)) {
            checks.add(() -> assertThrows(IllegalArgumentException.class, () -> MembraneState.axisFor(vertical), "垂直方向沒有門平面"));
        }
        assertAll(checks);
    }

    @Test
    void serializedNamesAndPropertyMatchTheSpec() {
        Map<MembraneState, String> names = new LinkedHashMap<>();
        for (MembraneState membrane : MembraneState.values()) names.put(membrane, membrane.asString());
        assertEquals(List.of("none", "sealed_x", "sealed_z", "passable_x", "passable_z"), List.copyOf(names.values()));
        assertEquals("membrane", QuantumBulkheadBlock.MEMBRANE.getName());
        assertEquals(Set.of(MembraneState.values()), Set.copyOf(QuantumBulkheadBlock.MEMBRANE.getValues()));
        assertFalse(MembraneState.NONE.isMembrane());
        assertTrue(MembraneState.NONE.axis().isEmpty());
        for (MembraneState membrane : MEMBRANES) {
            assertTrue(membrane.isMembrane(), membrane.asString());
            assertEquals(membrane.asString().startsWith("sealed"), membrane.sealed(), membrane.asString());
        }
        assertFalse(MembraneState.NONE.sealed());
    }

    // ---- QuantumBulkheadBlock：屬性與守門 ----

    @Test
    void defaultStateAndOldSavesWithoutTheMembraneDecodeToNone() {
        BlockState defaults = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
        assertFalse(defaults.get(QuantumBulkheadBlock.OPEN));
        assertEquals(MembraneState.NONE, defaults.get(QuantumBulkheadBlock.MEMBRANE));
        for (boolean open : new boolean[] {false, true}) {
            // M1–M4 的存檔：方塊狀態只有 open，沒有 membrane。
            NbtCompound legacy = new NbtCompound();
            legacy.putString("Name", "quantumchamber:quantum_bulkhead");
            NbtCompound properties = new NbtCompound();
            properties.putString("open", Boolean.toString(open));
            legacy.put("Properties", properties);
            BlockState fromCodec = BlockState.CODEC.parse(NbtOps.INSTANCE, legacy).getOrThrow();
            BlockState fromHelper = NbtHelper.toBlockState(Registries.BLOCK.getReadOnlyWrapper(), legacy);
            for (BlockState decoded : List.of(fromCodec, fromHelper)) {
                assertTrue(decoded.isOf(ModBlocks.QUANTUM_BULKHEAD));
                assertEquals(open, decoded.get(QuantumBulkheadBlock.OPEN), "open=" + open);
                assertEquals(MembraneState.NONE, decoded.get(QuantumBulkheadBlock.MEMBRANE), "舊存檔 open=" + open + " 沒有 membrane");
            }
        }
    }

    @Test
    void withOpenFalseAlwaysClearsTheMembraneAndOpeningFromClosedIsAPlainOpenDoor() {
        List<Executable> checks = new ArrayList<>();
        // 走訪笛卡兒積的全部 10 個狀態，包含只可能由指令寫出的 open=false＋*_x/z。
        for (BlockState state : ModBlocks.QUANTUM_BULKHEAD.getStateManager().getStates()) {
            String label = state.toString();
            BlockState closed = QuantumBulkheadBlock.withOpen(state, false);
            checks.add(() -> assertFalse(closed.get(QuantumBulkheadBlock.OPEN), label));
            checks.add(() -> assertEquals(MembraneState.NONE, closed.get(QuantumBulkheadBlock.MEMBRANE), label + " 關門一律 none"));
            BlockState opened = QuantumBulkheadBlock.withOpen(state, true);
            if (state.get(QuantumBulkheadBlock.OPEN)) {
                checks.add(() -> assertSame(state, opened, label + " 已開時保留 membrane"));
            } else {
                checks.add(() -> assertTrue(opened.get(QuantumBulkheadBlock.OPEN), label));
                checks.add(() -> assertEquals(MembraneState.NONE, opened.get(QuantumBulkheadBlock.MEMBRANE), label + " 由關到開是一般開門"));
            }
            MembraneState effective = QuantumBulkheadBlock.membrane(state);
            checks.add(() -> assertEquals(state.get(QuantumBulkheadBlock.OPEN) ? state.get(QuantumBulkheadBlock.MEMBRANE) : MembraneState.NONE,
                    effective, label + " 關門時實際生效的 membrane 一律 none"));
        }
        checks.add(() -> assertThrows(IllegalArgumentException.class,
                () -> QuantumBulkheadBlock.withOpen(Blocks.BEDROCK.getDefaultState(), true), "非 bulkhead 拒絕"));
        assertAll(checks);
    }

    @Test
    void withDoorAndDoorStateRejectAClosedDoorWithAMembrane() {
        BlockState base = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
        List<Executable> checks = new ArrayList<>();
        for (MembraneState membrane : MEMBRANES) {
            checks.add(() -> assertThrows(IllegalArgumentException.class,
                    () -> QuantumBulkheadBlock.withDoor(base, false, membrane), "withDoor 關門＋" + membrane));
            checks.add(() -> assertThrows(IllegalArgumentException.class,
                    () -> new DoorState(false, membrane), "DoorState 關門＋" + membrane));
            for (boolean openBefore : new boolean[] {false, true}) {
                BlockState before = QuantumBulkheadBlock.withOpen(base, openBefore);
                BlockState after = QuantumBulkheadBlock.withDoor(before, true, membrane);
                checks.add(() -> assertTrue(after.get(QuantumBulkheadBlock.OPEN) && after.get(QuantumBulkheadBlock.MEMBRANE) == membrane,
                        membrane + " from open=" + openBefore));
            }
        }
        for (BlockState state : ModBlocks.QUANTUM_BULKHEAD.getStateManager().getStates()) {
            BlockState closed = QuantumBulkheadBlock.withDoor(state, false, MembraneState.NONE);
            checks.add(() -> assertTrue(!closed.get(QuantumBulkheadBlock.OPEN) && closed.get(QuantumBulkheadBlock.MEMBRANE) == MembraneState.NONE,
                    state + " withDoor 關門"));
        }
        checks.add(() -> assertEquals(DoorState.CLOSED, new DoorState(false, MembraneState.NONE)));
        checks.add(() -> assertEquals(DoorState.OPEN, new DoorState(true, MembraneState.NONE)));
        checks.add(() -> assertEquals(new DoorState(true, MembraneState.SEALED_Z), DoorState.sealed(Direction.EAST)));
        checks.add(() -> assertEquals(new DoorState(true, MembraneState.PASSABLE_X), DoorState.passable(Direction.SOUTH)));
        assertAll(checks);
    }

    // ---- QuantumBulkheadBlock：形狀與逐狀態覆寫 ----

    @Test
    void collisionIsAFullCubeOnlyWhenClosedOrSealed() {
        List<Executable> checks = new ArrayList<>();
        for (BlockState state : legalStates()) {
            boolean full = !state.get(QuantumBulkheadBlock.OPEN) || state.get(QuantumBulkheadBlock.MEMBRANE).sealed();
            var shape = state.getCollisionShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            checks.add(() -> assertEquals(full, Block.isShapeFullCube(shape), state + " 完整碰撞"));
            checks.add(() -> assertEquals(!full, shape.isEmpty(), state + " 沒有碰撞"));
        }
        assertAll(checks);
    }

    @Test
    void outlineIsTheThinMembranePlaneAlongTheDoorAxis() {
        List<Executable> checks = new ArrayList<>();
        BlockState closed = ModBlocks.QUANTUM_BULKHEAD.getDefaultState();
        checks.add(() -> assertTrue(Block.isShapeFullCube(closed.getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN)), "關門"));
        checks.add(() -> assertTrue(open(MembraneState.NONE).getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).isEmpty(), "一般開門"));
        for (MembraneState membrane : MEMBRANES) {
            List<Box> boxes = open(membrane).getOutlineShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).getBoundingBoxes();
            Box expected = membrane.axis().orElseThrow() == Direction.Axis.X
                    ? new Box(0, 0, 7 / 16.0, 1, 1, 9 / 16.0)
                    : new Box(7 / 16.0, 0, 0, 9 / 16.0, 1, 1);
            checks.add(() -> assertEquals(List.of(expected), boxes, membrane + " 置中的薄平面"));
        }
        assertAll(checks);
    }

    @Test
    void openStatesNeitherSuffocateNorBlockVisionWhileTheClosedDoorStillDoes() {
        List<Executable> checks = new ArrayList<>();
        for (BlockState state : legalStates()) {
            boolean closed = !state.get(QuantumBulkheadBlock.OPEN);
            checks.add(() -> assertEquals(closed, state.shouldSuffocate(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), state + " suffocates"));
            checks.add(() -> assertEquals(closed, state.shouldBlockVision(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), state + " blockVision"));
        }
        assertAll(checks);
    }

    @Test
    void openStatesLetLightThroughAndNeverCullNeighbourFaces() {
        List<Executable> checks = new ArrayList<>();
        BlockPos center = new BlockPos(0, 64, 0);
        for (BlockState state : legalStates()) {
            boolean closed = !state.get(QuantumBulkheadBlock.OPEN);
            checks.add(() -> assertEquals(closed ? 15 : 0, state.getOpacity(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), state + " opacity"));
            var culling = state.getCullingShape(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            checks.add(() -> assertEquals(closed, Block.isShapeFullCube(culling), state + " culling 完整"));
            checks.add(() -> assertEquals(!closed, culling.isEmpty(), state + " culling 為空"));
            checks.add(() -> assertEquals(closed, state.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN), state + " opaque full cube"));
            // 實際的面剔除：四周的 bedrock 面向這一格的面，只有關門時被剔除；薄平面周圍不得出現透視破洞。
            for (Direction side : Direction.values()) {
                BlockPos neighbour = center.offset(side.getOpposite());
                var view = new MapView(Map.of(center, state, neighbour, Blocks.BEDROCK.getDefaultState()));
                checks.add(() -> assertEquals(!closed, Block.shouldDrawSide(Blocks.BEDROCK.getDefaultState(), view, neighbour, side, center),
                        state + " 的 " + side.getOpposite() + " 側 bedrock 面"));
            }
        }
        assertAll(checks);
    }

    @Test
    void noBulkheadStateCanBeFilledOrReplacedByFluids() {
        List<Executable> checks = new ArrayList<>();
        // 包含指令才寫得出的狀態：任何狀態都擋得住流體（沒有碰撞的開門與 passable 也一樣）。
        for (BlockState state : ModBlocks.QUANTUM_BULKHEAD.getStateManager().getStates()) {
            checks.add(() -> assertTrue(state.getBlock() instanceof FluidFillable, state + " 由方塊自己決定流體可否填入"));
            for (var fluid : List.of(Fluids.WATER, Fluids.FLOWING_WATER, Fluids.LAVA, Fluids.FLOWING_LAVA)) {
                checks.add(() -> assertFalse(state.getBlock() instanceof FluidFillable fillable
                        && fillable.canFillWithFluid(null, EmptyBlockView.INSTANCE, BlockPos.ORIGIN, state, fluid), state + " 不可填入 " + fluid));
                checks.add(() -> assertFalse(state.canBucketPlace(fluid), state + " 水桶不可取代 " + fluid));
            }
            checks.add(() -> assertFalse(state.isReplaceable(), state + " 不可被放置取代"));
        }
        assertAll(checks);
    }

    // ---- ChamberDoorService：套用目標狀態的核心（世界版本由 GameTest 驗證） ----

    @Test
    void applyWritesOnlyTheCellsThatDifferAndRefreshesOnce() {
        ChamberFrame frame = new ChamberFrame(new BlockPos(20, 80, -40), Direction.EAST);
        List<BlockPos> door = doorPositions(frame);
        var cells = new MembraneDoor(door, DoorState.OPEN);
        cells.states.put(door.get(3), DoorState.sealed(Direction.EAST));
        cells.states.put(door.get(17), DoorState.sealed(Direction.EAST));
        List<BlockPos> refreshed = new ArrayList<>();

        Outcome outcome = new ChamberDoorService().apply(cells, cells, ChamberMutationExecutor.DIRECT, frame,
                DoorState.sealed(Direction.EAST), refreshed::add);

        assertEquals(Outcome.APPLIED, outcome);
        assertTrue(door.stream().allMatch(pos -> cells.states.get(pos).equals(DoorState.sealed(Direction.EAST))), "25 格一致");
        assertEquals(23, cells.writes.size(), "已是目標的兩格不重寫");
        assertFalse(cells.writes.contains(door.get(3)) || cells.writes.contains(door.get(17)));
        assertEquals(List.of(frame.controllerPos()), refreshed);
    }

    @Test
    void applyWithEveryCellAlreadyAtTheTargetIsUnchangedWithoutWritingOrRefreshing() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        var cells = new MembraneDoor(doorPositions(frame), DoorState.passable(Direction.NORTH));
        List<BlockPos> refreshed = new ArrayList<>();
        int[] executions = {0};

        Outcome outcome = new ChamberDoorService().apply(cells, cells, mutation -> { executions[0]++; return mutation.get(); },
                frame, DoorState.passable(Direction.NORTH), refreshed::add);

        assertEquals(Outcome.UNCHANGED, outcome);
        assertEquals(0, executions[0]);
        assertTrue(cells.writes.isEmpty());
        assertTrue(refreshed.isEmpty());
    }

    @Test
    void applyRejectsAnIncompleteDoorWithoutWritingAnyCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> door = doorPositions(frame);
        var cells = new MembraneDoor(door, DoorState.OPEN);
        cells.states.remove(door.get(24));
        List<BlockPos> refreshed = new ArrayList<>();

        Outcome outcome = new ChamberDoorService().apply(cells, cells, ChamberMutationExecutor.DIRECT, frame,
                DoorState.sealed(Direction.NORTH), refreshed::add);

        assertEquals(Outcome.DOOR_INCOMPLETE, outcome, "結構性的拒絕與寫入被拒分開回報");
        assertTrue(cells.writes.isEmpty(), "缺一格時不寫任何一格");
        assertTrue(refreshed.isEmpty());
    }

    @Test
    void applyRollsEveryChangedCellBackToItsOwnOriginalWhenAWriteFails() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> door = doorPositions(frame);
        for (boolean throwing : new boolean[] {false, true}) for (boolean changedBeforeFailure : new boolean[] {false, true}) {
            String label = "throwing=" + throwing + " changedBeforeFailure=" + changedBeforeFailure;
            var cells = new MembraneDoor(door, DoorState.OPEN);
            // 原狀態不一致（例如前一次 rollback 失敗留下的混合門）：每一格都要回到自己的原狀態。
            for (int index = 0; index < door.size(); index += 3) cells.states.put(door.get(index), DoorState.passable(Direction.NORTH));
            Map<BlockPos, DoorState> before = new HashMap<>(cells.states);
            cells.failAt = 13;
            cells.throwOnFail = throwing;
            cells.changeBeforeFailure = changedBeforeFailure;
            List<BlockPos> refreshed = new ArrayList<>();

            Outcome outcome = new ChamberDoorService().apply(cells, cells, ChamberMutationExecutor.DIRECT, frame,
                    DoorState.CLOSED, refreshed::add);

            assertEquals(Outcome.ROLLED_BACK, outcome, "寫入被拒、rollback 完成與門板不完整分開回報（" + label + "）");
            assertEquals(before, cells.states, "每一格回到自己的原狀態（" + label + "）");
            // 13 次嘗試；真的改過的格才需要復原（失敗格沒改時不重寫）。
            assertEquals(13 + (changedBeforeFailure ? 13 : 12), cells.writes.size(), label);
            assertTrue(refreshed.isEmpty(), label);
        }
    }

    @Test
    void applyReportsAnIncompleteRollbackAndStillTriesEveryChangedCell() {
        ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, Direction.NORTH);
        List<BlockPos> door = doorPositions(frame);
        var cells = new MembraneDoor(door, DoorState.passable(Direction.NORTH));
        cells.failAt = 13;
        BlockPos unrecovered = door.get(7);
        cells.rollbackRejected = Set.of(unrecovered);
        List<BlockPos> refreshed = new ArrayList<>();

        var failure = assertThrows(IllegalStateException.class, () -> new ChamberDoorService().apply(cells, cells,
                ChamberMutationExecutor.DIRECT, frame, DoorState.CLOSED, refreshed::add));

        assertTrue(failure.getMessage().contains("1 格") && failure.getMessage().contains(unrecovered.toShortString()), failure.getMessage());
        assertEquals(DoorState.CLOSED, cells.states.get(unrecovered));
        assertTrue(door.stream().filter(pos -> !pos.equals(unrecovered)).allMatch(pos -> cells.states.get(pos).equals(DoorState.passable(Direction.NORTH))));
        assertEquals(13 + 12, cells.writes.size(), "rollback 失敗的格之後仍繼續復原其他格");
        assertTrue(refreshed.isEmpty());
    }

    @Test
    void applyRejectsATargetWhoseAxisDoesNotFitTheFrame() {
        for (Direction facing : Direction.Type.HORIZONTAL) {
            ChamberFrame frame = new ChamberFrame(BlockPos.ORIGIN, facing);
            var cells = new MembraneDoor(doorPositions(frame), DoorState.OPEN);
            Direction crosswise = facing.rotateYClockwise();
            for (DoorState wrong : List.of(DoorState.sealed(crosswise), DoorState.passable(crosswise))) {
                assertThrows(IllegalArgumentException.class, () -> new ChamberDoorService().apply(cells, cells,
                        ChamberMutationExecutor.DIRECT, frame, wrong, ignored -> { }), facing + " " + wrong);
            }
            assertTrue(cells.writes.isEmpty(), facing.asString());
        }
    }

    // ---- 資源 ----

    @Test
    void blockstateMapsEveryStateToExactlyOneModel() throws IOException {
        JsonObject variants = json("/assets/quantumchamber/blockstates/quantum_bulkhead.json").getAsJsonObject("variants");
        assertNotNull(variants, "variants");
        List<Executable> checks = new ArrayList<>();
        for (BlockState state : ModBlocks.QUANTUM_BULKHEAD.getStateManager().getStates()) {
            List<JsonObject> matches = new ArrayList<>();
            for (var entry : variants.entrySet()) if (matches(state, entry.getKey())) matches.add(entry.getValue().getAsJsonObject());
            checks.add(() -> assertEquals(1, matches.size(), state + " 恰好一個 variant"));
            if (matches.size() != 1) continue;
            JsonObject variant = matches.getFirst();
            String model = variant.get("model").getAsString();
            int rotation = variant.has("y") ? variant.get("y").getAsInt() : 0;
            MembraneState membrane = QuantumBulkheadBlock.membrane(state);
            if (!state.get(QuantumBulkheadBlock.OPEN)) {
                checks.add(() -> assertEquals("quantumchamber:block/quantum_bulkhead", model, state + " 關門"));
            } else if (!membrane.isMembrane()) {
                checks.add(() -> assertEquals("minecraft:block/air", model, state + " 一般開門"));
            } else {
                checks.add(() -> assertEquals("quantumchamber:block/quantum_bulkhead_membrane", model, state + " 量子膜"));
                checks.add(() -> assertEquals(membrane.axis().orElseThrow() == Direction.Axis.X ? 0 : 90, rotation, state + " 平面方向"));
            }
        }
        assertAll(checks);
    }

    @Test
    void membraneModelIsADoubleSidedCentredPlaneWithAnOpaqueSixteenPixelTexture() throws IOException {
        JsonObject model = json("/assets/quantumchamber/models/block/quantum_bulkhead_membrane.json");
        assertEquals("quantumchamber:block/quantum_membrane", model.getAsJsonObject("textures").get("membrane").getAsString());
        assertEquals("quantumchamber:block/quantum_membrane", model.getAsJsonObject("textures").get("particle").getAsString());
        var elements = model.getAsJsonArray("elements");
        assertEquals(1, elements.size());
        JsonObject plane = elements.get(0).getAsJsonObject();
        assertEquals("[0,0,8]", plane.get("from").toString());
        assertEquals("[16,16,8]", plane.get("to").toString());
        var faces = plane.getAsJsonObject("faces");
        assertEquals(Set.of("north", "south"), faces.keySet(), "雙面");
        for (String face : faces.keySet()) {
            assertEquals("#membrane", faces.getAsJsonObject(face).get("texture").getAsString());
            assertFalse(faces.getAsJsonObject(face).has("cullface"), face + " 不被鄰格剔除");
        }

        try (InputStream png = resource("/assets/quantumchamber/textures/block/quantum_membrane.png")) {
            byte[] bytes = png.readAllBytes();
            assertTrue(bytes.length > 8 && (bytes[0] & 0xff) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G', "PNG");
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            assertNotNull(image);
            assertEquals(16, image.getWidth());
            assertEquals(16, image.getHeight());
            for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
                assertEquals(0xff, image.getRGB(x, y) >>> 24, "不透明 (" + x + "," + y + ")");
            }
        }
        assertNull(MembraneStateTest.class.getResource("/assets/quantumchamber/textures/block/quantum_membrane.png.mcmeta"),
                "不使用動畫或 emissive 等 metadata");
    }

    // ---- 寫入路徑守門 ----

    /**
     * 補充第 1 點：open=false＋*_x/z 不能由任何寫入路徑產生。掃描 production 與 client 原始碼：
     * <ul>
     *   <li>bulkhead 屬性（{@code QuantumBulkheadBlock.OPEN／MEMBRANE}，可帶套件名稱；或以 {@code import static} 引入的
     *       {@code OPEN／MEMBRANE}）作為 {@code with}、{@code withIfExists}、{@code cycle} 的參數寫入時，只能在
     *       {@link QuantumBulkheadBlock} 自己。唯一例外是對預設狀態（membrane=none）寫 {@code OPEN}，判定時忽略空白與換行；
     *       目前只有 {@code SessionEntranceAllocator} 一處，新增的位置也要在這裡列出。</li>
     *   <li>兩個門切換點傳給 {@code ChamberDoorService.toggle} 的 mutator，lambda 本體（去掉空白）必須逐字等於經過
     *       {@code withOpen} 的寫法。</li>
     * </ul>
     * 只比對 bulkhead 自己的屬性常數；{@code Properties.OPEN} 是許多原版方塊共用的屬性，不列入。
     */
    @Test
    void everyOpenAndMembraneWriteGoesThroughTheBulkheadGuards() throws IOException {
        Path root = repositoryRoot();
        Pattern qualified = Pattern.compile("(?<![\\w.])(?:dev\\s*\\.\\s*quantumchamber\\s*\\.\\s*chamber\\s*\\.\\s*)?"
                + "QuantumBulkheadBlock\\s*\\.\\s*(OPEN|MEMBRANE)\\b");
        Pattern staticImport = Pattern.compile("import\\s+static\\s+dev\\.quantumchamber\\.chamber\\.QuantumBulkheadBlock\\s*\\.\\s*(OPEN|MEMBRANE|\\*)\\s*;");
        Pattern bare = Pattern.compile("(?<![\\w.])(OPEN|MEMBRANE)\\b");
        Map<String, List<String>> unexpected = new TreeMap<>();
        Map<String, Integer> defaultStateOpenWrites = new TreeMap<>();
        for (Path file : javaSources(root)) {
            String relative = root.relativize(file).toString().replace('\\', '/');
            if (relative.equals("src/main/java/dev/quantumchamber/chamber/QuantumBulkheadBlock.java")) continue;
            String text = Files.readString(file, StandardCharsets.UTF_8);
            Map<Integer, String> references = new TreeMap<>();
            for (Matcher matcher = qualified.matcher(text); matcher.find(); ) references.put(matcher.start(), matcher.group(1));
            Set<String> imported = new java.util.HashSet<>();
            for (Matcher matcher = staticImport.matcher(text); matcher.find(); ) imported.add(matcher.group(1));
            if (!imported.isEmpty()) {
                for (Matcher matcher = bare.matcher(text); matcher.find(); ) {
                    if (imported.contains("*") || imported.contains(matcher.group(1))) references.put(matcher.start(), matcher.group(1));
                }
            }
            for (var reference : references.entrySet()) {
                String receiver = writeReceiver(text, reference.getKey());
                if (receiver == null) continue; // 讀取，例如 state.get(QuantumBulkheadBlock.OPEN)
                if (reference.getValue().equals("OPEN") && receiver.endsWith("QUANTUM_BULKHEAD.getDefaultState()")) {
                    defaultStateOpenWrites.merge(relative, 1, Integer::sum);
                    continue;
                }
                int line = (int) text.substring(0, reference.getKey()).chars().filter(c -> c == '\n').count() + 1;
                unexpected.computeIfAbsent(relative, ignored -> new ArrayList<>()).add(line + ":" + reference.getValue());
            }
        }
        assertEquals(Map.of(), unexpected, "直接寫 OPEN／MEMBRANE 的位置必須改用 QuantumBulkheadBlock.withOpen／withDoor");
        assertEquals(Map.of("src/main/java/dev/quantumchamber/corridor/SessionEntranceAllocator.java", 1), defaultStateOpenWrites,
                "只有入口門從預設狀態（membrane=none）直接寫 OPEN");
        assertEquals("(target,open)->world.setBlockState(target,QuantumBulkheadBlock.withOpen(world.getBlockState(target),open))",
                toggleMutator(root, "src/main/java/dev/quantumchamber/chamber/WorldChamberDoorAdapter.java"), "原艙門切換的 mutator");
        assertEquals("(position,open)->write(position,QuantumBulkheadBlock.withOpen(world.getBlockState(position),open))",
                toggleMutator(root, "src/main/java/dev/quantumchamber/corridor/CorridorPageManager.java"), "入口 replica 門切換的 mutator");
    }

    // ---- helpers ----

    private static List<BlockState> legalStates() {
        List<BlockState> states = new ArrayList<>();
        states.add(ModBlocks.QUANTUM_BULKHEAD.getDefaultState());
        states.add(open(MembraneState.NONE));
        for (MembraneState membrane : MEMBRANES) states.add(open(membrane));
        return states;
    }

    private static BlockState open(MembraneState membrane) {
        return ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, true).with(QuantumBulkheadBlock.MEMBRANE, membrane);
    }

    private static List<BlockPos> doorPositions(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>();
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        return positions;
    }

    private static boolean matches(BlockState state, String key) {
        if (key.isEmpty()) return true;
        Map<String, String> wanted = new HashMap<>();
        for (String pair : key.split(",")) {
            String[] parts = pair.split("=", 2);
            wanted.put(parts[0], parts[1]);
        }
        Map<String, String> actual = state.getProperties().stream()
                .collect(Collectors.toMap(Property::getName, property -> name(state, property)));
        return actual.keySet().containsAll(wanted.keySet())
                && wanted.entrySet().stream().allMatch(entry -> entry.getValue().equals(actual.get(entry.getKey())));
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }

    private static JsonObject json(String path) throws IOException {
        try (InputStream stream = resource(path)) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static InputStream resource(String path) {
        InputStream stream = MembraneStateTest.class.getResourceAsStream(path);
        assertNotNull(stream, "資源不存在：" + path);
        return stream;
    }

    private static List<Path> javaSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String sourceSet : List.of("src/main/java", "src/client/java")) {
            try (var walk = Files.walk(root.resolve(sourceSet))) {
                walk.filter(path -> path.toString().endsWith(".java")).sorted().forEach(files::add);
            }
        }
        return files;
    }

    /**
     * 位置是 {@code <receiver>.with(…)}、{@code withIfExists(…)} 或 {@code cycle(…)} 的參數時，回傳 receiver 結尾（去掉空白與換行，
     * 最多 200 字元）；否則（例如 {@code state.get(…)}）回 null。
     */
    private static String writeReceiver(String text, int argumentStart) {
        int cursor = skipWhitespaceBackward(text, argumentStart - 1);
        if (cursor < 0 || text.charAt(cursor) != '(') return null;
        cursor = skipWhitespaceBackward(text, cursor - 1);
        int nameEnd = cursor + 1;
        while (cursor >= 0 && Character.isJavaIdentifierPart(text.charAt(cursor))) cursor--;
        if (!Set.of("with", "withIfExists", "cycle").contains(text.substring(cursor + 1, nameEnd))) return null;
        cursor = skipWhitespaceBackward(text, cursor);
        if (cursor < 0 || text.charAt(cursor) != '.') return null;
        return text.substring(Math.max(0, cursor - 200), cursor).replaceAll("\\s+", "");
    }

    private static int skipWhitespaceBackward(String text, int index) {
        while (index >= 0 && Character.isWhitespace(text.charAt(index))) index--;
        return index;
    }

    /** {@code new ChamberDoorService().toggle(…)} 的第二個參數（mutator），去掉空白；檔案中必須恰好有一個這樣的呼叫。 */
    private static String toggleMutator(Path root, String relative) throws IOException {
        String text = Files.readString(root.resolve(relative), StandardCharsets.UTF_8);
        Matcher call = Pattern.compile("new\\s+ChamberDoorService\\s*\\(\\s*\\)\\s*\\.\\s*toggle\\s*\\(").matcher(text);
        assertTrue(call.find(), relative + " 沒有門切換呼叫");
        int start = call.end();
        assertFalse(call.find(), relative + " 有多個門切換呼叫");
        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        for (int index = start; index < text.length(); index++) {
            char c = text.charAt(index);
            if (c == '"' || c == '\'') {
                int end = index + 1;
                while (end < text.length() && text.charAt(end) != c) end += text.charAt(end) == '\\' ? 2 : 1;
                current.append(text, index, Math.min(end + 1, text.length()));
                index = end;
                continue;
            }
            if (c == ')' && depth == 0) {
                arguments.add(current.toString());
                break;
            }
            if (c == ',' && depth == 0) {
                arguments.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            if (c == ')' || c == ']' || c == '}') depth--;
            current.append(c);
        }
        assertTrue(arguments.size() >= 2, relative + " 的門切換呼叫缺少 mutator 參數");
        return arguments.get(1).replaceAll("\\s+", "");
    }

    private static Path repositoryRoot() {
        for (Path cursor = Path.of("").toAbsolutePath(); cursor != null; cursor = cursor.getParent()) {
            if (Files.isRegularFile(cursor.resolve("settings.gradle"))
                    && Files.isDirectory(cursor.resolve("src/main/java/dev/quantumchamber"))) return cursor;
        }
        throw new IllegalStateException("找不到 repo root：" + Path.of("").toAbsolutePath());
    }

    /** 純記憶體的 25 格門；可注入第 N 次寫入失敗，以及特定格的 rollback 被拒。 */
    private static final class MembraneDoor implements ChamberDoorService.DoorStateView, ChamberDoorService.DoorStateMutator {
        final Map<BlockPos, DoorState> states = new LinkedHashMap<>();
        final List<BlockPos> writes = new ArrayList<>();
        int failAt;
        boolean throwOnFail;
        boolean changeBeforeFailure;
        Set<BlockPos> rollbackRejected = Set.of();

        MembraneDoor(List<BlockPos> positions, DoorState initial) {
            positions.forEach(pos -> states.put(pos, initial));
        }

        @Override public DoorState doorAt(BlockPos pos) { return states.get(pos); }

        @Override public boolean set(BlockPos pos, DoorState state) {
            writes.add(pos);
            if (!states.containsKey(pos)) return false;
            if (failAt != 0 && writes.size() == failAt) {
                if (changeBeforeFailure) states.put(pos, state);
                if (throwOnFail) throw new IllegalStateException("測試注入：第 " + failAt + " 次寫入失敗");
                return false;
            }
            if (failAt != 0 && writes.size() > failAt && rollbackRejected.contains(pos)) return false;
            states.put(pos, state);
            return true;
        }
    }

    private static final class MapView implements BlockView {
        private final Map<BlockPos, BlockState> states;

        MapView(Map<BlockPos, BlockState> states) { this.states = new HashMap<>(states); }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public int getHeight() { return 384; }
        @Override public int getBottomY() { return -64; }
    }
}
