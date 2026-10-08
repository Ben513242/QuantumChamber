package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.BEDROCK;
import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.GLASS;
import static dev.quantumchamber.gametest.M5CollapseFixture.GOLD_BLOCK;
import static dev.quantumchamber.gametest.M5CollapseFixture.controllerBlock;
import static dev.quantumchamber.gametest.M5CollapseFixture.damagedShell;
import static dev.quantumchamber.gametest.M5CollapseFixture.interiorMarker;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import com.google.gson.JsonObject;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：窗口表。每個 crash 窗口（{@link CrashWindow}）一列 {@link Window}：
 * <ul>
 * <li>crash 點的條件（hook 交來的 detail 是否就是那一刻）與 halt 之前的取證（寫進 {@code halt.json} 的 {@code atHalt}；丟出時不 halt）；</li>
 * <li>restart 開服時的預期（{@link AtRestart}：參與者是否已返還、RESOLVE 在 halt 之前完成到哪一步（因此 halt 當下是否已物化、allocation、catalog、discovery
 *     的預期）、projection ledger 的狀態、窗口自己的磁碟檢查、JOIN 之前是否先載入並辨識投影 Controller）；</li>
 * <li>PREPARE 怎麼完成（{@link Prepared}：「準備完成」INFO 的模式與格數，與 READY 時的磁碟檢查；不檢查時為 null）。</li>
 * </ul>
 * 新增窗口只要在 {@link #table} 加一列（另要有 hook mixin 與 {@code CrashWindow} 的值，見 {@code scripts/verification/README.md}「M5 collapse probe」）。
 * {@link #expectAtRestart} 與 {@link M5CollapsePassage} 依這一列斷言。
 */
final class M5CollapseWindows {
    private M5CollapseWindows() {}

    /** crash 點的條件：{@code detail} 是不是這個窗口的那一刻（同一個 hook 每次呼叫都交出 detail）。 */
    @FunctionalInterface interface Condition { boolean test(M5CollapseFixture fixture, Object detail); }

    /** halt 之前的取證（或刻意的準備）：寫進 {@code evidence}（{@code halt.json} 的 {@code atHalt}）；丟出時不 halt。 */
    @FunctionalInterface interface Evidence { void collect(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception; }

    /** restart 的磁碟檢查（開服時，或 READY 時）。 */
    @FunctionalInterface interface DiskCheck { void check(M5CollapseRun run, M5CollapseFixture fixture) throws Exception; }

    /** RESOLVE 在 halt 之前完成到哪一步（allocation → catalog → 物化 → discovery）。 */
    enum Resolved {
        /** 還沒有開始（W1：MEASURED→COLLAPSING 剛提交）。 */
        NOTHING,
        /** allocation ledger 已寫出（W2）。 */
        ALLOCATION,
        /** catalog 已寫出、還沒有物化（W3；重啟時由開服 bootstrap 物化）。 */
        CATALOG,
        /** 已物化、discovery 已追加（W5 與 PREPARE 的窗口）。 */
        DISCOVERY;

        boolean allocated() { return this != NOTHING; }

        boolean cataloged() { return compareTo(CATALOG) >= 0; }

        boolean materializedAtHalt() { return this == DISCOVERY; }

        boolean discovered() { return this == DISCOVERY; }
    }

    /**
     * restart 開服時（{@code SERVER_STARTED} 的 AFTER phase）的預期。{@code returned}：參與者在 crash 前是否已返還（W1 為 false：走廊 space 已重建、沒有保留式
     * recovery 的請求）。{@code projection}：projection ledger 這座艙在 U 的狀態（null 為沒有 entry）。{@code disk}：窗口自己的磁碟檢查（{@link #NO_CHECK} 不檢查）。
     * {@code recognizeBeforeJoin}：JOIN 之前以 probe 的 ticket 載入投影 Controller，等它經 load-sync 辨識為 inert 的 PROJECTION（COMPLETE 時另斷言不變量 cell 受保護）。
     */
    record AtRestart(boolean returned, Resolved resolved, ProjectionLedger.Entry.Status projection, DiskCheck disk, boolean recognizeBeforeJoin) {}

    /** PREPARE 怎麼完成：「準備完成」INFO 恰一則，模式是 {@code mode}、寫入 {@code cells} 格（{@link #ANY_CELLS}：不是 0 格即可），之後 READY 時的磁碟檢查。 */
    record Prepared(String name, ProjectionWriter.Mode mode, int cells, DiskCheck afterReady) {
        /** 這一則「準備完成」的訊息符合預期的模式與格數。 */
        boolean matches(String message) {
            if (cells == ANY_CELLS) return message.contains("投影=" + mode.name() + "（寫入 ") && !message.contains("（寫入 0 格）");
            return message.contains("投影=" + mode.name() + "（寫入 " + cells + " 格）");
        }
    }

    static final int ANY_CELLS = -1;

    /** 窗口表的一列。 */
    record Window(CrashWindow id, Condition condition, Evidence beforeHalt, AtRestart atRestart, Prepared prepared) {}

    static final DiskCheck NO_CHECK = (run, fixture) -> { };
    private static final Evidence NO_EVIDENCE = (run, fixture, detail, evidence) -> { };
    /** W4、W4b 的 restart：照第一次寫入重寫（PENDING），READY 時 checkpoint 已把方塊存到磁碟。 */
    private static final Prepared FIRST_WRITE_AGAIN = new Prepared("rewrittenAsFirstWrite", ProjectionWriter.Mode.FIRST_WRITE, ANY_CELLS,
            M5CollapseWindows::checkpointedBeforeReady);

    private static final Map<CrashWindow, Window> TABLE = table();

    private static Map<CrashWindow, Window> table() {
        var rows = new EnumMap<CrashWindow, Window>(CrashWindow.class);
        // W1：live session 的 MEASURED→COLLAPSING 剛提交、返還之前（RESOLVE 還沒有開始）。
        row(rows, CrashWindow.COLLAPSING_COMMITTED, M5CollapseWindows::collapsingCommitted, M5CollapseWindows::committedBeforeTheReturn,
                new AtRestart(false, Resolved.NOTHING, null, NO_CHECK, false), null);
        // W2：allocation ledger 寫出之後、catalog 之前。
        row(rows, CrashWindow.ALLOCATION_APPENDED, M5CollapseWindows::appended, NO_EVIDENCE,
                new AtRestart(true, Resolved.ALLOCATION, null, NO_CHECK, false), null);
        // W3：catalog 寫出之後、物化之前（重啟時由 EAGER_ENABLED 物化）。
        row(rows, CrashWindow.CATALOG_ALLOCATED, M5CollapseWindows::appended, NO_EVIDENCE,
                new AtRestart(true, Resolved.CATALOG, null, NO_CHECK, false), null);
        // W5：discovery 追加之後、投影之前。
        row(rows, CrashWindow.DISCOVERY_APPENDED, M5CollapseWindows::appended, NO_EVIDENCE,
                new AtRestart(true, Resolved.DISCOVERY, null, NO_CHECK, false), null);
        // W4：第一次寫入剛寫完 Controller 那一格（halt 前存檔：磁碟上是寫到一半的投影）。
        row(rows, CrashWindow.PROJECTION_CELL_WRITTEN, M5CollapseWindows::controllerCellWritten, M5CollapseWindows::savedHalfWrittenProjection,
                new AtRestart(true, Resolved.DISCOVERY, ProjectionLedger.Entry.Status.PENDING, M5CollapseWindows::controllerOnDiskAtRestart, true), FIRST_WRITE_AGAIN);
        // W4b：投影寫完並驗證、目的 chunks 的 checkpoint 之前（磁碟上沒有投影）。
        row(rows, CrashWindow.DESTINATION_CHECKPOINT, M5CollapseWindows::destinationCheckpoint, M5CollapseWindows::projectionOnlyInMemory,
                new AtRestart(true, Resolved.DISCOVERY, ProjectionLedger.Entry.Status.PENDING, M5CollapseWindows::noProjectionOnDiskAtRestart, false), FIRST_WRITE_AGAIN);
        // W6：projection ledger 寫入 COMPLETE 之後、PASSAGE_READY 之前（重啟之後修復 0 格）。
        row(rows, CrashWindow.PROJECTION_COMPLETE, M5CollapseWindows::projectionCompleted, M5CollapseWindows::completeOnDisk,
                new AtRestart(true, Resolved.DISCOVERY, ProjectionLedger.Entry.Status.COMPLETE, M5CollapseWindows::controllerOnDiskAtRestart, true),
                new Prepared("repairedNothing", ProjectionWriter.Mode.REPAIR, 0, NO_CHECK));
        // W6 的 EXISTING 再造訪：REPAIR 寫了格之後、checkpoint 之前（重啟之後再修復那一格，interior 的標記保留）。
        row(rows, CrashWindow.PROJECTION_REPAIRED, M5CollapseWindows::repairWroteCells, M5CollapseWindows::repairOnlyInMemory,
                new AtRestart(true, Resolved.DISCOVERY, ProjectionLedger.Entry.Status.COMPLETE, M5CollapseWindows::repairLostWithTheCrash, false),
                new Prepared("repairedAgain", ProjectionWriter.Mode.REPAIR, 1, M5CollapseWindows::repairCheckpointedBeforeReady));
        return Collections.unmodifiableMap(rows);
    }

    private static void row(Map<CrashWindow, Window> rows, CrashWindow id, Condition condition, Evidence beforeHalt, AtRestart atRestart, Prepared prepared) {
        if (rows.put(id, new Window(id, condition, beforeHalt, atRestart, prepared)) != null) throw new IllegalStateException("窗口表重複：" + id);
    }

    /** 這個窗口的一列；表裡沒有時丟出。 */
    static Window of(CrashWindow id) {
        var window = TABLE.get(id);
        if (window == null) throw new IllegalArgumentException("probe 的窗口表沒有這個窗口：" + id);
        return window;
    }

    /** 依窗口表裝上 {@code id} 的 crash 點（{@link M5CollapseRun#armCrash}）。 */
    static void arm(M5CollapseRun run, M5CollapseFixture fixture, CrashWindow id) {
        var window = of(id);
        run.armCrash(id, detail -> window.condition().test(fixture, detail), (detail, evidence) -> window.beforeHalt().collect(run, fixture, detail, evidence));
    }

    // ---- crash 點的條件 ----

    /** W2、W3、W5：checked 寫入回傳 {@code APPENDED}。 */
    private static boolean appended(M5CollapseFixture fixture, Object detail) {
        return detail instanceof CollapseAuthorities.Lookup<?> lookup && lookup.status() == CollapseAuthorities.Lookup.Status.APPENDED;
    }

    /** W1：這個 session 的 MEASURED→COLLAPSING 已提交（checked 讀回確認）。 */
    private static boolean collapsingCommitted(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.CollapsingCommit commit && commit.result().committed() && commit.expected().state() == SessionState.MEASURED
                && commit.next().state() == SessionState.COLLAPSING && commit.next().sessionUuid().equals(fixture.sid());
    }

    /** W4：第一次寫入剛寫完 U 的 Controller 那一格（targets 依 x→y→z：Controller 之前與目標不同的格已寫，之後的還沒有）。 */
    private static boolean controllerCellWritten(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ProjectionCell cell && cell.mode() == ProjectionWriter.Mode.FIRST_WRITE
                && cell.pos().equals(FRAME.controllerPos()) && fixture.isUniverse(cell.world());
    }

    /** W4b：U 的原生存檔 checkpoint 開始之前（投影方塊已寫完並驗證）。 */
    private static boolean destinationCheckpoint(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ChunkCheckpoint checkpoint && checkpoint.relevant().keySet().stream().anyMatch(fixture::isUniverse);
    }

    /** W6：projection ledger 已 checked 寫入這座艙在 U 的 COMPLETE。 */
    private static boolean projectionCompleted(M5CollapseFixture fixture, Object detail) {
        return detail instanceof CollapseAuthorities.WriteResult result && result.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                && result.entry().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE && entry.universeId().equals(fixture.universe())
                        && entry.chamberUuid().equals(fixture.chamber())).isPresent();
    }

    /** W6 的 EXISTING 再造訪：U 的 REPAIR 寫了格（{@code written > 0}），checkpoint 之前。 */
    private static boolean repairWroteCells(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ProjectionWrite write && write.mode() == ProjectionWriter.Mode.REPAIR
                && write.result().status() == ProjectionWriter.Status.WRITTEN && write.result().written() > 0 && fixture.isUniverse(write.world());
    }

    // ---- halt 之前的取證 ----

    /**
     * W1 的 halt 之前：磁碟上的 journal 已是 COLLAPSING（Destination 是推導出的 U），參與者還沒有返還、lease 還在；每位參與者的 playerdata 存在走廊（選門之前的
     * 原生存檔）；allocation ledger 還是空的（RESOLVE 還沒有開始）。
     */
    private static void committedBeforeTheReturn(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var onDisk = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid()).orElse(null);
        evidence.put("journalStateOnDisk", onDisk == null ? "ABSENT" : onDisk.state().name());
        run.expect("journalCollapsingAtHalt", onDisk != null && onDisk.state() == SessionState.COLLAPSING
                        && M5CollapseFixture.destination(onDisk).equals(Optional.of(fixture.universe())),
                () -> "磁碟上的 journal 是 " + (onDisk == null ? "ABSENT" : onDisk.state() + " Destination=" + onDisk.collapse().destination()));
        run.expect("notReturnedAtHalt", onDisk.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !onDisk.spaceLeases().isEmpty(),
                () -> "磁碟上的參與者或 lease：" + onDisk.participants() + " leases=" + onDisk.spaceLeases());
        var dimensions = new LinkedHashMap<String, String>();
        for (var id : fixture.participants()) dimensions.put(id.toString(), M5CollapseDiskEvidence.playerDimension(run.saveRoot(), id));
        evidence.put("playerDimensionOnDisk", dimensions);
        run.expect("playerDataInTheCorridorAtHalt", dimensions.values().stream().allMatch(SuperpositionWorld.KEY.getValue().toString()::equals),
                () -> "磁碟上參與者的 playerdata 在 " + dimensions);
        run.expect("noAllocationAtHalt", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty(), () -> "allocation ledger=" + fixture.allocationsOrError());
    }

    /**
     * W4 的 halt 之前：把 U 存到磁碟（{@code world.save(null, true, false)}：寫到一半的方塊落盤），再以 raw MCA 讀回。寫入迴圈在同一個 tick 內做完，正常情況下磁碟不會有
     * 寫到一半的方塊；這是刻意造出的最壞情況（「寫入途中 crash＋之前剛好存過檔」），比實際嚴格。實際會發生的「PENDING、磁碟上沒有方塊」由 W4b 涵蓋。
     */
    private static void savedHalfWrittenProjection(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var cell = (M5TestHooks.ProjectionCell) detail;
        evidence.put("cellChanged", cell.changed());
        // writeCell 的 hook 到 Controller 為止（含）被呼叫幾次（crash 點的 reached）：只有與目標不同的格（needsWrite）才呼叫 writeCell，所以這是寫到一半的格數，不是全部 189 格。
        evidence.put("writeCellCallsThroughTheController", run.crashPoint().reached());
        cell.world().save(null, true, false);
        var controller = fixture.diskBlock(cell.world(), FRAME.controllerPos());
        var first = fixture.diskBlock(cell.world(), local(0, 0, 0));
        var last = fixture.diskBlock(cell.world(), local(6, 6, 6));
        evidence.put("controllerOnDisk", controller.toString());
        evidence.put("firstCellOnDisk", first.toString());
        evidence.put("lastCellOnDisk", last.toString());
        run.expect("controllerOnDiskBeforeHalt", controllerBlock(controller), () -> "存檔之後磁碟上的 Controller 位置是 " + controller);
        run.expect("halfWrittenOnDisk", BEDROCK.equals(first.name()) && !BEDROCK.equals(last.name()), () -> "第一格 " + first + "，最後一格 " + last);
        run.expect("projectionPendingAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection("PENDING"))),
                () -> "projection ledger=" + fixture.projectionsOrError());
    }

    /** W4b 的 halt 之前：投影方塊只在記憶體（Controller 已寫入 U），磁碟上沒有；projection ledger 是 PENDING。 */
    private static void projectionOnlyInMemory(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var checkpoint = (M5TestHooks.ChunkCheckpoint) detail;
        evidence.put("checkpointChunks", checkpoint.relevant().get(world).stream().map(ChunkPos::toString).sorted().toList());
        var disk = fixture.diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        run.expect("projectionWrittenInMemory", world.getBlockState(FRAME.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                && world.getBlockState(local(6, 6, 6)).isOf(Blocks.BEDROCK), () -> "U 的投影方塊還沒有寫入");
        run.expect("projectionNotOnDiskAtHalt", !controllerBlock(disk), () -> "checkpoint 之前磁碟上已有 Controller：" + disk);
        run.expect("projectionPendingAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection("PENDING"))),
                () -> "projection ledger=" + fixture.projectionsOrError());
    }

    /** W6 的 halt 之前：COMPLETE 已在磁碟上（checkpoint 已存下投影），journal 仍是 COLLAPSING。 */
    private static void completeOnDisk(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var disk = fixture.diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        run.expect("controllerOnDiskBeforeHalt", controllerBlock(disk), () -> "checkpoint 之後磁碟上的 Controller 位置是 " + disk);
        run.expect("projectionCompleteAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        var state = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid()).map(record -> record.state().name()).orElse("ABSENT");
        run.expect("journalCollapsingAtHalt", SessionState.COLLAPSING.name().equals(state), () -> "磁碟上的 journal 是 " + state);
    }

    /** W6x 的 halt 之前：REPAIR 已在記憶體修好被改壞的 shell，磁碟上仍是改壞的狀態；interior 的標記不受影響。 */
    private static void repairOnlyInMemory(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.ProjectionWrite) detail;
        var world = write.world();
        evidence.put("written", write.result().written());
        var disk = fixture.diskBlock(world, damagedShell());
        evidence.put("damagedShellOnDisk", disk.toString());
        run.expect("repairedInMemory", world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                () -> "shell=" + world.getBlockState(damagedShell()) + " marker=" + world.getBlockState(interiorMarker()));
        run.expect("repairNotOnDiskAtHalt", GLASS.equals(disk.name()), () -> "checkpoint 之前磁碟上的 shell 是 " + disk);
    }

    // ---- restart 開服時 ----

    /**
     * restart 的開服檢查（W1 與 DORMANT 的 restart 共用）：窗口在 RESOLVE 的哪一步（halt 當下是否已物化）、record 仍是 COLLAPSING 且 Destination 與候選不變；
     * 參與者還沒有返還的窗口（W1）另斷言走廊 space 已重建、沒有保留式 recovery 的請求；allocation、catalog（與開服 bootstrap 的物化）、discovery、projection 依
     * 這一列的預期，再做窗口自己的磁碟檢查。{@code recognizeBeforeJoin} 的窗口在這裡以 probe 的 ticket 載入投影 Controller、開始觀察 load-sync，回傳它；否則回 null。
     */
    static M5CollapseProjectionLoad expectAtRestart(M5CollapseRun run, M5CollapseFixture fixture, Window window, JsonObject halt) throws Exception {
        var at = window.atRestart();
        boolean materializedWindow = at.resolved().materializedAtHalt();
        boolean activeAtHalt = halt.get("universeActiveAtHalt").getAsBoolean();
        run.expect(materializedWindow ? "materializedBeforeTheCrash" : "notMaterializedBeforeTheCrash",
                activeAtHalt == materializedWindow, () -> "halt 當下 U 的物化狀態=" + activeAtHalt);
        var sid = fixture.sid();
        var current = run.journal.flushedRecords().get(sid);
        run.expect("restartedCollapsing", current != null && current.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(run.journal, sid));
        run.expect("sameDestination", M5CollapseFixture.destination(current).equals(Optional.of(fixture.universe())), () -> "Destination=" + current.collapse().destination());
        run.expect("noRedraw", M5CollapseFixture.sameCandidate(fixture.receipt(), current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
        if (!at.returned()) {
            // W1（spec §13.2）：未返還的 COLLAPSING 在 attach 時重建走廊 space，返還由 receipt 推導、不經 requestMeasuredRecovery。
            run.expect("notReturnedAtRestart", current.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty()
                    && MeasuredRecoveryPhase.from(current) == MeasuredRecoveryPhase.RETURN_PLAYERS, () -> "參與者 " + current.participants() + " leases=" + current.spaceLeases());
            run.expect("spaceRestored", fixture.spaceExists(), () -> "開服之後沒有這個 session 的走廊 space");
            run.expect("noMeasuredRecoveryRequest", !fixture.pages().measuredRecoveryRequested(sid), () -> "COLLAPSING 的 space 標記為已請求保留式 recovery");
        }
        if (at.resolved().allocated()) {
            fixture.expectOneAllocation();
        } else {
            run.expect("noAllocationYet", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty(), () -> "allocation ledger=" + fixture.allocationsOrError());
        }
        var catalog = UniverseRegistryState.get(run.server).flushedRecords();
        boolean discovered = M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().anyMatch(record -> record.universeId().equals(fixture.universe()));
        boolean active = fixture.universeWorld().isPresent();
        if (!at.resolved().cataloged()) {
            run.expect("catalogNotYetAllocated", catalog.size() == fixture.catalogBefore() && !catalog.containsKey(fixture.universe()), () -> "catalog=" + catalog.keySet());
            run.expect("notYetMaterialized", !active, () -> "U 已物化");
        } else {
            fixture.expectCatalog("catalogAllocatedOnce");
            // spec §12 W3：重啟時由 EAGER_ENABLED 物化（UniverseLifecycleService 的開服 bootstrap，早於這個 SERVER_STARTED 的 AFTER phase）。
            run.expect("bootstrapMaterializedEagerEnabled", active, () -> "開服 bootstrap 沒有物化 U");
        }
        if (at.resolved().discovered()) run.expect("alreadyDiscovered", discovered, () -> "discovery 沒有 U");
        else run.expect("notYetDiscovered", !discovered, () -> "discovery 已有 U");
        var expectedProjections = at.projection() == null ? List.<M5CollapseDiskEvidence.Projection>of() : List.of(fixture.projection(at.projection().name()));
        var projectionName = at.projection() == null ? "noProjectionYet"
                : at.projection() == ProjectionLedger.Entry.Status.PENDING ? "projectionStillPending" : "projectionCompleteOnDisk";
        run.expect(projectionName, () -> M5CollapseDiskEvidence.projections(run.data()).equals(expectedProjections), () -> "projection ledger=" + fixture.projectionsOrError());
        at.disk().check(run, fixture);
        M5CollapseProjectionLoad load = null;
        if (at.recognizeBeforeJoin()) {
            load = M5CollapseProjectionLoad.start(run, fixture);
            // spec Minor（F6-2a）：辨識是本 JVM 做的：剛由磁碟載入時是 ORIGIN、power 還沒有初始化、load-sync 還在佇列（checkpoint 存下的是 refresh 之前的 block entity）。
            var atLoad = load.atLoad();
            run.expect("unrecognizedWhenLoaded", atLoad.instanceKind() == ChamberInstanceKind.ORIGIN && !atLoad.powerInitialized() && atLoad.loadSyncPending(),
                    () -> "載入時的 Controller：" + atLoad);
        }
        run.proof.put("startedDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), sid));
        return load;
    }

    /** W4、W6 的開服：磁碟上 U 的 Controller 位置是 Controller（寫到一半或 COMPLETE 的投影）。 */
    private static void controllerOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var disk = fixture.diskBlock(fixture.universeWorld().orElseThrow(), FRAME.controllerPos());
        run.expect("controllerOnDiskAtRestart", controllerBlock(disk), () -> "磁碟上的 Controller 位置是 " + disk);
    }

    /** W4b 的開服：磁碟上沒有投影方塊（Controller 位置不是 Controller，最後一格不是基岩）。 */
    private static void noProjectionOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var controller = fixture.diskBlock(world, FRAME.controllerPos());
        var last = fixture.diskBlock(world, local(6, 6, 6));
        run.expect("noProjectionOnDiskAtRestart", !controllerBlock(controller) && !BEDROCK.equals(last.name()),
                () -> "磁碟上已有投影方塊：Controller 位置 " + controller + "，最後一格 " + last);
    }

    /** W6x 的開服：REPAIR 隨 crash 遺失（磁碟上被改壞的 shell 仍是玻璃），interior 的標記在磁碟上。 */
    private static void repairLostWithTheCrash(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var shell = fixture.diskBlock(world, damagedShell());
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("repairLostWithTheCrash", GLASS.equals(shell.name()), () -> "磁碟上被改壞的 shell 是 " + shell);
        run.expect("interiorMarkerOnDisk", GOLD_BLOCK.equals(marker.name()), () -> "磁碟上的 interior 標記是 " + marker);
    }

    // ---- PREPARE 怎麼完成 ----

    /** PASSAGE_READY 時：「準備完成」INFO 恰一則、符合這一列的模式與格數，再做 READY 時的磁碟檢查。{@code prepared} 為 null 時不檢查。 */
    static void expectPrepared(M5CollapseRun run, M5CollapseFixture fixture, Prepared prepared) throws Exception {
        if (prepared == null) return;
        var messages = run.capture.matching(Level.INFO, "準備完成（預載與投影都已完成）", fixture.sid().toString()).stream().map(LogCapture.Entry::message).toList();
        run.expect(prepared.name(), messages.size() == 1 && prepared.matches(messages.getFirst()), () -> "「準備完成」=" + messages);
        prepared.afterReady().check(run, fixture);
    }

    /** W4、W4b：READY 之前的 checkpoint 已把重寫的方塊存到磁碟（最後一格是基岩、Controller 在）。 */
    private static void checkpointedBeforeReady(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var last = fixture.diskBlock(world, local(6, 6, 6));
        var controller = fixture.diskBlock(world, FRAME.controllerPos());
        run.expect("checkpointedBeforeReady", BEDROCK.equals(last.name()) && controllerBlock(controller), () -> "READY 時磁碟上最後一格 " + last + "，Controller 位置 " + controller);
    }

    /** W6x：READY 之前的 checkpoint 已把修好的 shell 存到磁碟，interior 的標記保留（記憶體與磁碟）。 */
    private static void repairCheckpointedBeforeReady(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var shell = fixture.diskBlock(world, damagedShell());
        run.expect("repairCheckpointedBeforeReady", BEDROCK.equals(shell.name()) && world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK),
                () -> "READY 時磁碟上被改壞的 shell 是 " + shell);
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("interiorMarkerKept", GOLD_BLOCK.equals(marker.name()) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                () -> "interior 標記：磁碟 " + marker + "，記憶體 " + world.getBlockState(interiorMarker()));
    }

    // ---- halt receipt 的 detail ----

    /** 窗口交來的 detail 的文字描述（寫進 {@code halt.json} 的 {@code detail}）。 */
    static String describe(Object detail) {
        return switch (detail) {
            case M5TestHooks.CollapsingCommit commit -> "edge=" + commit.expected().state() + "→" + commit.next().state() + " session=" + commit.next().sessionUuid()
                    + " status=" + commit.result().status();
            case CollapseAuthorities.Lookup<?> lookup -> "status=" + lookup.status() + " value=" + lookup.value().map(String::valueOf).orElse("-");
            case CollapseAuthorities.WriteResult result -> "status=" + result.status() + " entry=" + result.entry().map(String::valueOf).orElse("-");
            case M5TestHooks.ProjectionCell cell -> "world=" + cell.world().getRegistryKey().getValue() + " mode=" + cell.mode() + " pos=" + cell.pos().toShortString()
                    + " changed=" + cell.changed();
            case M5TestHooks.ProjectionWrite write -> "world=" + write.world().getRegistryKey().getValue() + " mode=" + write.mode() + " status=" + write.result().status()
                    + " written=" + write.result().written();
            case M5TestHooks.ChunkCheckpoint checkpoint -> "worlds=" + checkpoint.relevant().entrySet().stream()
                    .map(entry -> entry.getKey().getRegistryKey().getValue() + "×" + entry.getValue().size()).sorted().toList();
            case null, default -> String.valueOf(detail);
        };
    }
}
