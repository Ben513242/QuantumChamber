package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberRegistry;
import dev.quantumchamber.collapse.ProjectionLedger.Entry.Status;
import dev.quantumchamber.persistence.CheckedNbtFile;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

/**
 * F4b-1：spec §9.3 的投影保護索引（{@link ProjectionProtectionIndex}）的純邏輯——建立（entry → 世界 → 不變量 cell）、兩層（PENDING 只辨識、COMPLETE 才保護）、
 * 更新入口、退化（只依權威健康）與查詢。世界與 server 的接線（{@code ChamberProtectionService}、{@code ChamberPowerCoordinator}）由 M5 GameTest 覆蓋。
 */
class ProjectionProtectionIndexTest {
    @TempDir Path root;
    private static final UniverseId U1 = UniverseId.of(UUID.fromString("00000000-0000-8000-8000-0000000000a1"));
    private static final UniverseId U2 = UniverseId.of(UUID.fromString("00000000-0000-8000-8000-0000000000a2"));
    private static final RegistryKey<World> W1 = UniverseKeys.world(U1, DimensionRole.OVERWORLD);
    private static final RegistryKey<World> W2 = UniverseKeys.world(U2, DimensionRole.OVERWORLD);
    private static final UUID CHAMBER_A = UUID.fromString("00000000-0000-4000-8000-00000000c0a1");
    private static final UUID CHAMBER_B = UUID.fromString("00000000-0000-4000-8000-00000000c0b2");
    private static final ChamberFrame FRAME_A = new ChamberFrame(new BlockPos(7, 70, 9), Direction.NORTH);
    private static final ChamberFrame FRAME_B = new ChamberFrame(new BlockPos(40, 70, 9), Direction.EAST);
    /** 只有 catalog 有 U1 的 record；U2 不在 catalog，走 M3 的推導。 */
    private static final BiFunction<UniverseId, DimensionRole, Optional<RegistryKey<World>>> CATALOG =
            (id, role) -> id.equals(U1) && role == DimensionRole.OVERWORLD ? Optional.of(W1) : Optional.empty();

    // ---- 建立與兩層 ----

    @Test void startupIndexProtectsOnlyTheInvariantCellsOfCompleteProjectionsAndRecognizesEveryController() {
        var complete = entry(CHAMBER_A, U1, FRAME_A, Status.COMPLETE);
        var pending = entry(CHAMBER_B, U1, FRAME_B, Status.PENDING);
        var elsewhere = entry(CHAMBER_A, U2, FRAME_A, Status.COMPLETE);
        var index = healthy();
        index.load(() -> List.of(complete, pending, elsewhere));
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertEquals(Optional.empty(), index.degradation(), "權威健康：不退化"));
        // COMPLETE：218 個不變量 cell（shell、Controller、門）受保護，125 格 interior 不保護。
        cases.add(() -> assertEquals(218, countCells(FRAME_A, pos -> index.protects(W1, pos), true), "COMPLETE 的 7×7×7 邊界都受保護"));
        cases.add(() -> assertEquals(125, countCells(FRAME_A, pos -> !index.protects(W1, pos), false), "interior 不保護"));
        cases.add(() -> assertTrue(index.protects(W1, FRAME_A.controllerPos()), "Controller 受保護"));
        cases.add(() -> assertTrue(ChamberGeometry.bulkheadPositions(FRAME_A).stream().allMatch(pos -> index.protects(W1, pos)), "門受保護"));
        cases.add(() -> assertFalse(index.protects(W1, FRAME_A.controllerPos().up()), "7×7×7 之外不保護"));
        cases.add(() -> assertFalse(index.protects(World.OVERWORLD, FRAME_A.controllerPos()), "vanilla 世界不查投影"));
        // PENDING：只辨識，不保護。
        cases.add(() -> assertEquals(0, countCells(FRAME_B, pos -> index.protects(W1, pos), true), "PENDING 不保護"));
        cases.add(() -> assertTrue(index.projectionController(W1, FRAME_B.controllerPos()), "PENDING 的 Controller 辨識為 PROJECTION"));
        cases.add(() -> assertTrue(index.projectionController(W1, FRAME_A.controllerPos()), "COMPLETE 的 Controller 辨識為 PROJECTION"));
        cases.add(() -> assertFalse(index.projectionController(W1, FRAME_A.controllerPos().down()), "其他位置不是投影的 Controller"));
        // catalog 沒有 U2：以 M3 的推導對應到 W2。
        cases.add(() -> assertTrue(index.protects(W2, FRAME_A.controllerPos()) && index.projectionController(W2, FRAME_A.controllerPos()),
                "catalog 沒有這筆時用 M3 的推導 world key"));
        cases.add(() -> assertFalse(index.projectionController(World.OVERWORLD, FRAME_A.controllerPos()), "vanilla 世界沒有投影"));
        // Origin 拆除之後 entry 保留：索引不看 chamber registry（這裡的 registry 是空的）。
        cases.add(() -> assertTrue(index.protects(W1, FRAME_A.controllerPos().down(6)), "沒有登記中的 Origin 也受保護（Dead Chamber）"));
        assertAll(cases);
    }

    @Test void updateEntryRecognizesAConfirmedPendingFirstAndProtectsOnlyOnceComplete() {
        var index = healthy();
        index.load(List::of);
        var pending = entry(CHAMBER_A, U1, FRAME_A, Status.PENDING);
        var shell = ChamberGeometry.localToWorld(FRAME_A, 0, 3, 3);
        boolean beforeRecognized = index.projectionController(W1, FRAME_A.controllerPos());
        index.confirmed(pending);
        boolean pendingRecognized = index.projectionController(W1, FRAME_A.controllerPos());
        boolean pendingProtected = index.protects(W1, shell);
        index.confirmed(pending.withStatus(Status.COMPLETE));
        boolean completeProtected = index.protects(W1, shell);
        index.confirmed(pending);
        boolean stillProtected = index.protects(W1, shell);
        var moved = new ProjectionLedger.Entry(CHAMBER_A, U1, DimensionRole.OVERWORLD, FRAME_A.controllerPos().east(), Direction.NORTH, Status.COMPLETE);
        var nether = new ProjectionLedger.Entry(CHAMBER_B, U1, DimensionRole.NETHER, FRAME_B.controllerPos(), Direction.NORTH, Status.PENDING);
        assertAll(
            () -> assertFalse(beforeRecognized, "登記之前不是投影"),
            () -> assertTrue(pendingRecognized, "PENDING 確認之後登記：寫入時的 refresh 辨識為 PROJECTION"),
            () -> assertFalse(pendingProtected, "PENDING 還不受保護"),
            () -> assertTrue(completeProtected, "COMPLETE 確認之後登記：受保護"),
            () -> assertTrue(stillProtected, "Status 只前進：已是 COMPLETE 時再登記 PENDING 維持 COMPLETE"),
            () -> assertThrows(IllegalArgumentException.class, () -> index.confirmed(moved), "同一個 key 的 site 不同是 caller 錯誤"),
            () -> assertThrows(IllegalArgumentException.class, () -> index.confirmed(nether), "對應不到世界（本切片只有 OVERWORLD）是 caller 錯誤"),
            () -> assertTrue(index.protects(W1, shell) && !index.projectionController(W1, FRAME_B.controllerPos()), "被拒的登記不改變索引"));
    }

    @Test void intersectingListsRegisteredProjectionsWhoseVolumeOverlaps() {
        var complete = entry(CHAMBER_A, U1, FRAME_A, Status.COMPLETE);
        var pending = entry(CHAMBER_B, U1, FRAME_B, Status.PENDING);
        var index = healthy();
        index.load(() -> List.of(complete, pending));
        var overlapA = ChamberGeometry.bounds(new ChamberFrame(FRAME_A.controllerPos().east(3), Direction.NORTH));
        var apart = ChamberGeometry.bounds(new ChamberFrame(FRAME_A.controllerPos().east(20), Direction.NORTH));
        assertAll(
            () -> assertEquals(List.of(complete), index.intersecting(W1, overlapA), "與 COMPLETE 的投影相交"),
            () -> assertEquals(List.of(pending), index.intersecting(W1, ChamberGeometry.bounds(FRAME_B)), "PENDING 也列出（由 F4b-2 依 Status 判斷）"),
            () -> assertEquals(List.of(), index.intersecting(W1, apart), "不相交"),
            () -> assertEquals(List.of(), index.intersecting(W2, ChamberGeometry.bounds(FRAME_A)), "別的世界"));
    }

    // ---- 退化（spec §9.3：只依權威健康） ----

    @Test void startupDegradationIgnoresTheLedgerAndProtectsRegisteredOverworldOriginsInEveryDynamicOverworld() {
        var registry = new ChamberRegistry();
        var origin = registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME_A);
        registry.registerOrigin(World.NETHER.getValue(), DimensionRole.NETHER, FRAME_B);
        var read = new AtomicInteger();
        var index = new ProjectionProtectionIndex(() -> Optional.of(SealedReason.AUTHORITY_CORRUPT), CATALOG, registry);
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            index.load(() -> { read.incrementAndGet(); return List.of(entry(CHAMBER_B, U1, FRAME_B, Status.COMPLETE)); });
            logs = capture.entries();
        }
        var shell = ChamberGeometry.localToWorld(FRAME_A, 0, 3, 3);
        var interior = ChamberGeometry.localToWorld(FRAME_A, 3, 3, 3);
        var foreign = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("othermod", "universe/" + U1.value() + "/overworld"));
        assertAll(
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), index.degradation(), "初始化失敗：退化，原因給 F4b-2"),
            () -> assertEquals(0, read.get(), "退化時不採用（也不讀）ledger 的內容"),
            () -> assertFalse(index.protects(W1, FRAME_B.controllerPos()), "ledger 的 entry 不入索引"),
            () -> assertEquals(218, countCells(FRAME_A, pos -> index.protects(W1, pos), true), "登記中的 OVERWORLD Origin 在動態 Overworld 同位置的不變量 cell 受保護"),
            () -> assertTrue(index.protects(W2, shell), "每一個動態 Overworld 都是"),
            () -> assertFalse(index.protects(W1, interior), "interior 不保護"),
            () -> assertFalse(index.protects(World.OVERWORLD, shell), "vanilla 世界不經投影索引（原艙由 registry 保護）"),
            () -> assertFalse(index.protects(foreign, shell), "不是動態 Overworld 的 world key 不保護"),
            () -> assertFalse(index.protects(W1, FRAME_B.controllerPos()), "Nether 的 Origin 不算"),
            () -> assertTrue(origin.chamberUuid() != null),
            () -> assertEquals(1, logs.stream().filter(entry -> entry.level() == Level.WARN && entry.tick() && entry.message().contains("退化")).count(),
                    () -> "退化記一則帶 TICK_LOG_MARKER 的 WARN：" + logs),
            () -> assertTrue(logs.stream().noneMatch(entry -> entry.level() == Level.ERROR), () -> "沒有 ERROR：" + logs));
    }

    @Test void runtimeLossDegradesAtTheNextSyncKeepsKnownProjectionsAndNeverRecovers() {
        var registry = new ChamberRegistry();
        registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME_B);
        var health = new AtomicReference<Optional<SealedReason>>(Optional.empty());
        var index = new ProjectionProtectionIndex(health::get, CATALOG, registry);
        index.load(() -> List.of(entry(CHAMBER_A, U1, FRAME_A, Status.COMPLETE)));
        var originShell = ChamberGeometry.localToWorld(FRAME_B, 6, 3, 3);
        boolean before = index.protects(W1, originShell);
        health.set(Optional.of(SealedReason.AUTHORITY_CORRUPT));
        boolean beforeSync = index.protects(W1, originShell);
        index.syncHealth();
        boolean afterSync = index.protects(W1, originShell);
        boolean knownKept = index.protects(W1, FRAME_A.controllerPos());
        health.set(Optional.empty());
        index.syncHealth();
        assertAll(
            () -> assertFalse(before, "健康時 Origin 位置不受投影保護"),
            () -> assertFalse(beforeSync, "熱路徑不查權威：同步之前不變"),
            () -> assertTrue(afterSync, "同步之後退化：Origin 同位置的不變量 cell 受保護"),
            () -> assertTrue(knownKept, "已在索引中的投影照樣受保護（較保守）"),
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), index.degradation(), "本 instance 不解除（下次開服才解除）"),
            () -> assertTrue(index.protects(W1, originShell)));
    }

    /**
     * 退化只依權威健康（plan Step 1 第 5 組；F2b 裁示）：以真的 {@link CollapseAuthorities} 驅動。projection ledger 已滿（新 key 回 CORRUPT）與單筆既有 entry
     * 不符（CORRUPT）都不退化，索引照常；ledger 檔在執行期間消失（遺失）之後，下一次同步才退化。
     */
    @Test void onlyAuthorityHealthDegradesNotAFullLedgerOrASingleMismatch() throws Exception {
        var save = Files.createDirectories(root.resolve("health"));
        int limit = ProjectionLedger.MAX_ENTRIES;
        // seed 8 是 PENDING（之後寫它的 COMPLETE 時才會實際寫檔，發現檔案消失），其餘都是 COMPLETE。
        var rows = new NbtList();
        for (int seed = 0; seed < limit; seed++) {
            var pending = ProjectionLedger.empty().with(ProjectionLedgerTest.entry(seed, Direction.SOUTH, Status.PENDING));
            var row = seed == 8 ? pending : pending.with(ProjectionLedgerTest.entry(seed, Direction.SOUTH, Status.COMPLETE));
            rows.add(((NbtList) row.toNbt().get("Entries")).getCompound(0).copy());
        }
        var full = new NbtCompound(); full.putInt("SchemaVersion", 1); full.put("Entries", rows);
        var data = Files.createDirectories(save.resolve("data"));
        NbtIo.writeCompressed(full, data.resolve(ProjectionLedger.FILE_NAME));
        NbtIo.writeCompressed(UniverseAllocationLedger.empty().toNbt(), data.resolve(UniverseAllocationLedger.FILE_NAME));
        var catalog = new UniverseRegistryState();
        var authorities = new CollapseAuthorities(save, CheckedNbtFile::readFile, new CollapseAuthorities.CatalogPort() {
            @Override public UniverseRegistryState catalog() { return catalog; }
            @Override public void flush(UniverseRegistryState state) { throw new AssertionError("這個 test 不寫 catalog"); }
        }, () -> 1000);
        assertTrue(authorities.initialize(() -> journal(4)).succeeded(), "fixture：寫滿的 ledger 照常讀得回來");
        var index = new ProjectionProtectionIndex(() -> authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER), (id, role) -> Optional.empty(), null);
        index.load(authorities::confirmedProjections);
        var world = UniverseKeys.world(ProjectionLedgerTest.UNIVERSE, DimensionRole.OVERWORLD);
        var seven = ProjectionLedgerTest.entry(7, Direction.SOUTH, Status.COMPLETE);
        boolean loaded = index.protects(world, seven.controllerPos());
        var fullResult = authorities.writeProjection(ProjectionLedgerTest.entry(limit, Direction.SOUTH, Status.PENDING));
        index.syncHealth();
        var afterFull = index.degradation();
        var mismatch = authorities.writeProjection(new ProjectionLedger.Entry(seven.chamberUuid(), seven.universeId(), DimensionRole.OVERWORLD,
                seven.controllerPos().up(), Direction.SOUTH, Status.COMPLETE));
        index.syncHealth();
        var afterMismatch = index.degradation();
        Files.delete(data.resolve(ProjectionLedger.FILE_NAME));
        var lost = authorities.writeProjection(ProjectionLedgerTest.entry(8, Direction.SOUTH, Status.COMPLETE));
        var beforeSync = index.degradation();
        assertAll(
            () -> assertTrue(loaded, "寫滿的 ledger 建立索引：COMPLETE 受保護"),
            () -> assertEquals(CollapseAuthorities.WriteResult.Status.CORRUPT, fullResult.status(), "fixture：已滿"),
            () -> assertEquals(Optional.empty(), afterFull, "ledger 已滿不退化"),
            () -> assertEquals(CollapseAuthorities.WriteResult.Status.CORRUPT, mismatch.status(), "fixture：單筆不符"),
            () -> assertEquals(Optional.empty(), afterMismatch, "單筆不符不退化"),
            () -> assertEquals(CollapseAuthorities.WriteResult.Status.CORRUPT, lost.status(), "fixture：執行期間遺失"),
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_CORRUPT), beforeSync, "遺失：degradation() 查詢時同步而退化"),
            () -> assertTrue(index.protects(world, seven.controllerPos()), "已在索引中的投影照樣受保護"));
    }

    @Test void unexpectedFailuresFailClosedWithTheDegradationRule() {
        var registry = new ChamberRegistry();
        registry.registerOrigin(World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, FRAME_A);
        var shell = ChamberGeometry.localToWorld(FRAME_A, 0, 3, 3);
        var broken = new ProjectionProtectionIndex(Optional::empty, CATALOG, registry);
        var throwingHealth = new AtomicReference<RuntimeException>();
        var syncing = new ProjectionProtectionIndex(() -> {
            var failure = throwingHealth.get();
            if (failure != null) throw failure;
            return Optional.empty();
        }, CATALOG, registry);
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            broken.load(() -> { throw new IllegalStateException("注入：列舉失敗"); });
            syncing.load(List::of);
            throwingHealth.set(new IllegalStateException("注入：健康查詢失敗"));
            assertDoesNotThrow(syncing::syncHealth, "同步在 END_SERVER_TICK 執行，不丟出例外");
            logs = capture.entries();
        }
        assertAll(
            () -> assertEquals(Optional.of(SealedReason.AUTHORITY_UNREADABLE), broken.degradation(), "建立失敗：fail closed"),
            () -> assertTrue(broken.protects(W1, shell), "fail closed 時套用退化規則"),
            () -> assertTrue(syncing.protects(W1, shell), "同步失敗：fail closed"),
            () -> assertEquals(2, logs.stream().filter(entry -> entry.level() == Level.ERROR && entry.tick()).count(), () -> "兩則帶 TICK_LOG_MARKER 的 ERROR：" + logs));
    }

    @Test void startupLogsOneInfoWithTheTickMarker() {
        var index = healthy();
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            index.load(() -> List.of(entry(CHAMBER_A, U1, FRAME_A, Status.COMPLETE), entry(CHAMBER_B, U1, FRAME_B, Status.PENDING)));
            logs = capture.entries();
        }
        assertAll(
            () -> assertEquals(1, logs.size(), () -> "正常情況只記一則 INFO：" + logs),
            () -> assertEquals(Level.INFO, logs.getFirst().level()),
            () -> assertTrue(logs.getFirst().tick() && logs.getFirst().message().contains("COMPLETE 1 筆") && logs.getFirst().message().contains("PENDING 1 筆"),
                    () -> logs.getFirst().toString()));
    }

    @Test void dynamicOverworldKeysAreExactlyTheUniverseOverworldKeys() {
        assertAll(
            () -> assertTrue(ProjectionProtectionIndex.isDynamicOverworld(W1)),
            () -> assertFalse(ProjectionProtectionIndex.isDynamicOverworld(World.OVERWORLD)),
            () -> assertFalse(ProjectionProtectionIndex.isDynamicOverworld(RegistryKey.of(RegistryKeys.WORLD,
                    Identifier.of("quantumchamber", "universe/not-a-uuid/overworld")))),
            () -> assertFalse(ProjectionProtectionIndex.isDynamicOverworld(RegistryKey.of(RegistryKeys.WORLD,
                    Identifier.of("quantumchamber", "universe/" + U1.value() + "/the_nether")))),
            () -> assertFalse(ProjectionProtectionIndex.isDynamicOverworld(RegistryKey.of(RegistryKeys.WORLD,
                    Identifier.of("othermod", "universe/" + U1.value() + "/overworld")))));
    }

    @Test void onShellIsTheBoundaryOfTheBox() {
        var bounds = new BlockBox(0, 0, 0, 6, 6, 6);
        int boundary = 0, inside = 0;
        for (int x = -1; x <= 7; x++) for (int y = -1; y <= 7; y++) for (int z = -1; z <= 7; z++) {
            if (ProjectionProtectionIndex.onShell(bounds, new BlockPos(x, y, z))) boundary++;
            else if (x >= 0 && x <= 6 && y >= 0 && y <= 6 && z >= 0 && z <= 6) inside++;
        }
        assertEquals(218, boundary, "7×7×7 的邊界");
        assertEquals(125, inside, "interior");
    }

    // ---- fixtures ----

    private static ProjectionProtectionIndex healthy() {
        return new ProjectionProtectionIndex(Optional::empty, CATALOG, new ChamberRegistry());
    }

    private static ProjectionLedger.Entry entry(UUID chamber, UniverseId universe, ChamberFrame frame, Status status) {
        return new ProjectionLedger.Entry(chamber, universe, DimensionRole.OVERWORLD, frame.controllerPos(), frame.outwardFacing(), status);
    }

    /** 這個 frame 的 7×7×7 中，{@code shell} 指定的那一類格（邊界或 interior）有多少格符合條件。 */
    private static int countCells(ChamberFrame frame, java.util.function.Predicate<BlockPos> condition, boolean shell) {
        int count = 0;
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            if (ChamberGeometry.isShellCell(x, y, z) != shell) continue;
            if (condition.test(ChamberGeometry.localToWorld(frame, x, y, z))) count++;
        }
        return count;
    }

    private static SessionRecoveryState journal(int schema) {
        var nbt = new NbtCompound(); nbt.putInt("SchemaVersion", schema); nbt.put("Records", new NbtList());
        var state = SessionRecoveryState.fromNbt(nbt); state.requireHealthy(); return state;
    }
}
