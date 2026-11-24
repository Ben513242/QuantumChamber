package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.registry.ModBlocks;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.LongStream;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * M7 T5-1：HOME 走廊的單門幾何（spec §4.2、V08 的幾何部分；plan Task 5 :313、:319、:325、:329）。
 * Builder 的 HOME 模式只在 station1 POSITIVE 側產生 25 格帶 {@code home_marker} 的 bulkhead，其餘側門格是牆；
 * 入口 overlay（含 station0 POSITIVE 側的 lateral 入口返還門）不變；legacy 模式逐格輸出與 T5-1 之前相同。
 * 期待值一律用字面常數（station 1、x=6、邏輯 z 9..13），不從 {@link HomeDoorLayout} 推導。
 */
class M7HomeDoorLayoutTest {
    private static final UUID SESSION = UUID.fromString("5a7d3c2e-0b1f-4c6a-9e8d-71f2a3b4c5d6");
    private static final List<Direction> FACINGS = List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST);
    private static final SessionSemantics LATERAL = SessionSemantics.LATERAL_BUFF_MAINTAINED;
    /** HOME 門的字面 key（spec §4.2），與 {@code HomeSessionRecord} 的 Selected 檢查相同。 */
    private static final DoorKey HOME_KEY = new DoorKey(SESSION, 1, DoorKey.DoorWallSide.POSITIVE_LATERAL);
    /**
     * T5-1 之前的 production（HEAD a1468c3，尚未有 HOME 模式）以同一演算法（{@link #legacyDigest}）算出的逐格 hash；
     * 原件見 M7 SDD {@code t5-1-legacy-hash-probe-9f7f8e16}。home_marker=false 視同沒有這個屬性，所以舊輸出可以直接比對。
     */
    private static final Map<String, String> LEGACY_DIGESTS = Map.of(
            "north/LEGACY_FORWARD_CONSUMED", "806b5825c15fc4680a1e097a93e398474cbe2ee09a878babe1d06e2f358d6099",
            "east/LEGACY_FORWARD_CONSUMED", "7f5e1c2b35237a11408b933515ed0bec1fea6397e246f87b2ee9a2a94a4f1ae8",
            "south/LEGACY_FORWARD_CONSUMED", "f39486c4351d473f3ef48b0577dcb0d015a161eb509f9c46bae01cd4344b5a79",
            "west/LEGACY_FORWARD_CONSUMED", "db41ba52595adea0ce77686bd07bd721a29d1f88084fc15e992f93c1395c26ec",
            "north/LATERAL_BUFF_MAINTAINED", "ac964089522536b4ba5ab8dcad3fa6c342aa10ce63507b10aae09163d0342187",
            "east/LATERAL_BUFF_MAINTAINED", "c3facd8f0187c74c1a849dbdcf4a5a23233cea532385716230417f98e8a4a8ef",
            "south/LATERAL_BUFF_MAINTAINED", "823de8c5b2754991085c038071ce18a6393452e82fba725db28eef3772c6c91f",
            "west/LATERAL_BUFF_MAINTAINED", "bc9404478cb225ec2da9c793ad961010b1f3a3f880213555e4d871925cd60cd8");

    @BeforeAll static void bootstrap() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        ModBlocks.register();
    }

    @Test void home_station_one_has_25_cells_in_four_orientations() {
        for (var facing : FACINGS) {
            var view = view(Set.of(0L), facing, 0);
            assertTrue(CorridorGeometry.hasEntrance(view), facing + "：初始頁含入口 replica");
            var cells = build(view, true, true, true);
            var entranceDoor = entranceDoor(view);
            var doors = new HashSet<BlockPos>();
            for (var cell : cells.entrySet()) {
                if (cell.getValue().isOf(ModBlocks.QUANTUM_BULKHEAD) && !entranceDoor.contains(cell.getKey())) doors.add(cell.getKey());
            }
            assertEquals(25, doors.size(), facing + "：入口返還門以外的 bulkhead 恰為一扇 25 格");
            assertEquals(homeCells(view), doors, facing + "：位置是 station1 POSITIVE 側的 25 格");
            for (var pos : doors) {
                var state = cells.get(pos);
                assertTrue(state.get(QuantumBulkheadBlock.HOME_MARKER), facing + " " + pos.toShortString() + " home_marker=true");
                assertFalse(state.get(QuantumBulkheadBlock.OPEN), facing + " 關門");
                assertEquals(MembraneState.NONE, state.get(QuantumBulkheadBlock.MEMBRANE), facing + " 沒有量子膜");
                assertEquals(Optional.of(HOME_KEY), CorridorGeometry.sideDoorKey(SESSION, view, LATERAL, pos), facing + " DoorKey");
            }
            // 入口返還門（station0 POSITIVE 的 lateral overlay 25 格）仍是一般 bulkhead，key 是 station0，與 HOME 零交集。
            assertEquals(25, entranceDoor.size());
            for (var pos : entranceDoor) {
                var state = cells.get(pos);
                assertTrue(state.isOf(ModBlocks.QUANTUM_BULKHEAD) && !state.get(QuantumBulkheadBlock.HOME_MARKER), facing + " 入口門不帶 HOME 標記");
                assertEquals(Optional.of(new DoorKey(SESSION, 0, DoorKey.DoorWallSide.POSITIVE_LATERAL)),
                        CorridorGeometry.sideDoorKey(SESSION, view, LATERAL, pos), facing + " 入口門在 station0 POSITIVE");
            }
            assertTrue(disjoint(doors, entranceDoor), facing + "：HOME∩入口門=0");
            assertTrue(disjoint(doors, entranceBox(view)), facing + "：HOME 與入口 overlay 的 343 格零交集");
            // Space.overlay（commitInitial 排入的連接開口）與 HOME 門零交集改由真 HOME Space 驗：M7HomeCorridorGameTests
            // .home_entry_commits_superposition_with_entry_b 在 overlay 寫完後讀世界（T5-1 審查 M-3：鏡像同一公式沒有獨立覆蓋力，已移除）。
        }
    }

    @Test void home_mode_walls_other_side_doors_and_keeps_the_entrance_overlay() {
        var home = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.HOME_MARKER, true);
        for (var facing : FACINGS) for (var occupied : List.of(Set.of(0L), Set.of(0L, 1L), Set.of(7L), Set.of(10L))) {
            var view = view(occupied, facing, 0);
            var box = CorridorGeometry.hasEntrance(view) ? entranceBox(view) : Set.<BlockPos>of();
            var expectedHome = homeCells(view);
            for (int flags = 0; flags < 4; flags++) {
                boolean front = (flags & 1) != 0, rear = (flags & 2) != 0;
                String label = facing + " " + occupied + " front=" + front + " rear=" + rear;
                var legacy = new CorridorGeometry.Builder(view, front, rear, LATERAL);
                var homeMode = new CorridorGeometry.Builder(view, front, rear, LATERAL, true);
                int homeSeen = 0, walled = 0;
                for (;;) {
                    var expected = legacy.next();
                    var actual = homeMode.next();
                    if (expected == null || actual == null) {
                        assertNull(expected, label + " HOME 輸出不得較短");
                        assertNull(actual, label + " HOME 輸出不得較長");
                        break;
                    }
                    assertEquals(expected.position(), actual.position(), label + " 輸出順序與位置相同");
                    boolean sideDoor = expected.state().isOf(ModBlocks.QUANTUM_BULKHEAD) && !box.contains(expected.position());
                    BlockState want = !sideDoor ? expected.state() : expectedHome.contains(expected.position()) ? home : Blocks.BEDROCK.getDefaultState();
                    if (!want.equals(actual.state())) fail(label + " " + expected.position().toShortString() + "：期待 " + want + "，實得 " + actual.state());
                    if (sideDoor && want == home) homeSeen++;
                    if (sideDoor && want != home) walled++;
                }
                assertEquals(expectedHome.size(), homeSeen, label + " HOME 門格數");
                assertTrue(walled > 0, label + " 其他側門格改為牆");
            }
        }
    }

    @Test void layout_components_with_station_one_have_exactly_25_home_cells() {
        var combos = new ArrayList<Set<Long>>(List.of(Set.of(0L), Set.of(0L, 1L), Set.of(1L), Set.of(6L), Set.of(7L), Set.of(8L),
                Set.of(-1L), Set.of(-6L), Set.of(-7L), Set.of(-8L), Set.of(0L, 20L), Set.of(3L, 50L), Set.of(-40L)));
        combos.add(Set.copyOf(LongStream.range(0, 128).boxed().toList()));
        for (var facing : List.of(Direction.NORTH, Direction.WEST)) for (var occupied : combos) {
            int total = 0, covering = 0, slot = 0;
            for (var component : CorridorLayout.plan(occupied, 576)) {
                var view = view(component, facing, slot++);
                boolean coversStationOne = component.aliasStartBlock() <= 9 && component.aliasEndBlock() >= 14;
                var entranceDoor = CorridorGeometry.hasEntrance(view) ? entranceDoor(view) : Set.<BlockPos>of();
                var builder = new CorridorGeometry.Builder(view, false, false, LATERAL, true);
                var marked = new HashSet<BlockPos>();
                for (CorridorGeometry.Cell cell; (cell = builder.next()) != null; ) {
                    if (!cell.state().isOf(ModBlocks.QUANTUM_BULKHEAD) || entranceDoor.contains(cell.position())) continue;
                    assertTrue(cell.state().get(QuantumBulkheadBlock.HOME_MARKER), facing + " " + occupied + "：HOME 模式沒有一般側門");
                    assertEquals(Optional.of(HOME_KEY), CorridorGeometry.sideDoorKey(SESSION, view, LATERAL, cell.position()));
                    marked.add(cell.position());
                }
                assertEquals(coversStationOne ? 25 : 0, marked.size(), facing + " " + occupied + " " + component);
                if (coversStationOne) { covering++; assertEquals(homeCells(view), marked); }
                total += marked.size();
            }
            assertTrue(covering <= 1, facing + " " + occupied + "：station1 只在一個分量");
            assertEquals(covering * 25, total, facing + " " + occupied + "：全部分量合計");
        }
    }

    @Test void legacy_builder_output_is_unchanged_cell_by_cell() throws Exception {
        for (var semantics : SessionSemantics.values()) for (var facing : FACINGS) {
            String key = facing.asString() + "/" + semantics;
            assertEquals(LEGACY_DIGESTS.get(key), legacyDigest(facing, semantics, 4), key + " 四參數建構子");
            assertEquals(LEGACY_DIGESTS.get(key), legacyDigest(facing, semantics, 5), key + " 五參數 home=false");
            if (semantics == SessionSemantics.LEGACY_FORWARD_CONSUMED) {
                assertEquals(LEGACY_DIGESTS.get(key), legacyDigest(facing, semantics, 3), key + " 三參數建構子");
            }
        }
    }

    @Test void home_door_key_is_station_one_positive_lateral() {
        assertEquals(HOME_KEY, HomeDoorLayout.key(SESSION));
        assertThrows(NullPointerException.class, () -> HomeDoorLayout.key(null));
        for (int x = 0; x <= 6; x++) for (int y = 0; y <= 6; y++) for (long z = -24; z <= 40; z++) {
            boolean expected = x == 6 && y >= 1 && y <= 5 && z >= 9 && z <= 13;
            assertEquals(expected, HomeDoorLayout.isHomeCell(x, y, z), "x=" + x + " y=" + y + " z=" + z);
        }
    }

    @Test void home_mode_requires_lateral_semantics() {
        var view = view(Set.of(0L), Direction.NORTH, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new CorridorGeometry.Builder(view, false, false, SessionSemantics.LEGACY_FORWARD_CONSUMED, true));
    }

    // ---- helpers ----

    private static CorridorPageManager.MappingView view(Set<Long> occupied, Direction facing, int slot) {
        var components = CorridorLayout.plan(occupied, 576);
        assertEquals(1, components.size(), occupied + " 應為單一分量");
        return view(components.getFirst(), facing, slot);
    }

    /** 比照 CorridorPageManager.reserve：anchor=(firstCorePage+1)*96，localBlockOrigin 由 slot origin 推得。 */
    private static CorridorPageManager.MappingView view(CorridorLayout.LayoutComponent component, Direction facing, int slot) {
        long anchor = Math.multiplyExact(component.firstCorePage() + 1, 96);
        var origin = new BlockPos(1000 + 192 * slot, 70, 2000);
        var relative = CorridorGeometry.relativeBounds(component, anchor, facing);
        var bounds = new BlockBox(relative.getMinX() + origin.getX(), relative.getMinY() + origin.getY(), relative.getMinZ() + origin.getZ(),
                relative.getMaxX() + origin.getX(), relative.getMaxY() + origin.getY(), relative.getMaxZ() + origin.getZ());
        return new CorridorPageManager.MappingView(new CorridorPageManager.MappingRef(SESSION, 1, slot),
                component.firstCorePage(), component.lastCorePage(), component.aliasStartBlock(), component.aliasEndBlock(), anchor,
                CorridorGeometry.block(new ChamberFrame(origin, facing), 0, 0, 0), facing, bounds);
    }

    /** Builder 全部輸出；同一位置不得出現兩次。 */
    private static Map<BlockPos, BlockState> build(CorridorPageManager.MappingView view, boolean front, boolean rear, boolean home) {
        var builder = new CorridorGeometry.Builder(view, front, rear, LATERAL, home);
        var cells = new LinkedHashMap<BlockPos, BlockState>();
        for (CorridorGeometry.Cell cell; (cell = builder.next()) != null; ) {
            assertNull(cells.put(cell.position(), cell.state()), "重複位置 " + cell.position().toShortString());
        }
        return cells;
    }

    /** station1 POSITIVE 側（走廊 x=6、y 1..5、邏輯 z 9..13）的 25 格；分量沒有涵蓋時為空。 */
    private static Set<BlockPos> homeCells(CorridorPageManager.MappingView view) {
        var cells = new HashSet<BlockPos>();
        if (view.aliasStartBlock() > 9 || view.aliasEndBlock() < 14) return cells;
        var frame = CorridorGeometry.frame(view);
        for (long z = 9; z <= 13; z++) for (int y = 1; y <= 5; y++) {
            cells.add(CorridorGeometry.block(frame, 6, y, Math.toIntExact(z - view.logicalAnchorBlock())));
        }
        return cells;
    }

    /** 入口返還門的 25 格（與 CorridorPageManager.entranceCell 相同）。 */
    private static Set<BlockPos> entranceDoor(CorridorPageManager.MappingView view) {
        var frame = CorridorGeometry.entrance(view, LATERAL);
        var cells = new HashSet<BlockPos>();
        for (int x = 1; x <= 5; x++) for (int y = 1; y <= 5; y++) cells.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        return cells;
    }

    /** 入口 replica 的 7×7×7 overlay 範圍。 */
    private static Set<BlockPos> entranceBox(CorridorPageManager.MappingView view) {
        var frame = CorridorGeometry.entrance(view, LATERAL);
        var cells = new HashSet<BlockPos>();
        for (int x = 0; x <= 6; x++) for (int y = 0; y <= 6; y++) for (int z = 0; z <= 6; z++) cells.add(ChamberSpaceCoordinates.block(frame, x, y, z));
        return cells;
    }

    private static boolean disjoint(Set<BlockPos> first, Set<BlockPos> second) {
        return first.stream().noneMatch(second::contains);
    }

    /**
     * 與 T5-1 之前的探針相同的演算法：三個分量（{0}、{0,1}、{10}）× front／rear 四種組合，逐格把位置與 {@link #canonical} 餵入 SHA-256。
     * {@code arity} 選建構子：3＝三參數（只有 LEGACY）、4＝四參數、5＝五參數 home=false。另核每次輸出的格數。
     */
    private static String legacyDigest(Direction facing, SessionSemantics semantics, int arity) throws Exception {
        var sha = MessageDigest.getInstance("SHA-256");
        var cache = new IdentityHashMap<BlockState, byte[]>();
        var buffer = new byte[8];
        int index = 0;
        var counts = List.of(70_560L, 75_264L, 70_560L);
        for (var occupied : List.of(Set.of(0L), Set.of(0L, 1L), Set.of(10L))) {
            var view = view(CorridorLayout.plan(occupied, 576).getFirst(), facing, index);
            for (int flags = 0; flags < 4; flags++) {
                boolean front = (flags & 1) != 0, rear = (flags & 2) != 0;
                sha.update(("|" + index + ":" + front + ":" + rear + "|").getBytes(StandardCharsets.UTF_8));
                var builder = switch (arity) {
                    case 3 -> new CorridorGeometry.Builder(view, front, rear);
                    case 4 -> new CorridorGeometry.Builder(view, front, rear, semantics);
                    default -> new CorridorGeometry.Builder(view, front, rear, semantics, false);
                };
                long count = 0;
                for (CorridorGeometry.Cell cell; (cell = builder.next()) != null; count++) {
                    long pos = cell.position().asLong();
                    for (int i = 0; i < 8; i++) buffer[i] = (byte) (pos >>> (56 - 8 * i));
                    sha.update(buffer);
                    sha.update(cache.computeIfAbsent(cell.state(), state -> (canonical(state) + "\n").getBytes(StandardCharsets.UTF_8)));
                }
                assertEquals(counts.get(index), count, facing + "/" + semantics + " 分量 " + occupied + " 的格數");
                sha.update(("#" + count).getBytes(StandardCharsets.UTF_8));
            }
            index++;
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    /** block id＋屬性（依名稱排序）；home_marker=false 視同沒有這個屬性（T5-1 之前的輸出沒有它）。 */
    private static String canonical(BlockState state) {
        var parts = new TreeMap<String, String>();
        for (Property<?> property : state.getProperties()) {
            String value = name(state, property);
            if (property.getName().equals("home_marker") && value.equals("false")) continue;
            parts.put(property.getName(), value);
        }
        return Registries.BLOCK.getId(state.getBlock()) + parts.toString();
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> property) {
        return property.name(state.get(property));
    }
}
