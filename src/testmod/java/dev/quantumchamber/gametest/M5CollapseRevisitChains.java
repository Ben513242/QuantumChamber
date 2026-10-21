package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.GOLD_BLOCK;
import static dev.quantumchamber.gametest.M5CollapseFixture.damagedShell;
import static dev.quantumchamber.gametest.M5CollapseFixture.interiorMarker;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.superposition.SessionState;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：EXISTING 再造訪的 chain。W6x 的 crash（restart 用
 * {@link M5CollapseDormantChains#restart}），以及開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本，不是 crash 窗口）的 setup 與 restart。兩者共用前半段
 * （{@link #firstPassageSteps}：以 NEW 塌縮到 PASSAGE_READY、U 的投影 COMPLETE，之後以斷電結束通道）。
 */
final class M5CollapseRevisitChains {
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** 退化的 restart：JOIN 之前載入的投影 Controller，與磁碟上投影 Controller 的 InstanceKind。 */
    private M5CollapseProjectionLoad load;
    private String diskInstanceKind;
    /** 退化的 refresh witness：U 的投影 Controller 正上方的紅石塊（setup 時放上、不觸發更新；restart 的 refresh 讀到它才會讓 WasPowered 變成 true）。 */
    private static final String REDSTONE_BLOCK = "minecraft:redstone_block";

    M5CollapseRevisitChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    // ---- EXISTING 再造訪的 crash（W6x） ----

    /**
     * crash phase（W6 的 EXISTING 再造訪）：前半段（{@link #firstPassageSteps}）→ U 的投影改壞一格 shell（玻璃）、interior 放金塊標記 → EXISTING 收據（同一個 U）、
     * 原生存檔 → 裝上 crash 點 → 參與者由艙外進入 → RESOLVE（EXISTING）→ PREPARE 以 REPAIR 修好那一格之後、checkpoint 之前 halt。
     */
    Phase existingRevisitCrash() {
        var steps = new ArrayList<>(firstPassageSteps());
        steps.addAll(List.of(
                new Step("existingReceipt", 1, () -> {
                    damageProjection();
                    fixture.writeCrashReceipt(fixture.existingReceiptForTheUniverse());
                    M5CollapseWindows.arm(run, fixture, CrashWindow.PROJECTION_REPAIRED);
                    return true;
                }),
                new Step("enter", 1, () -> { fixture.moveInside(); return true; }),
                new Step("committed", 40, fixture::committed),
                new Step("halt", 400, () -> false)));
        return Phase.halt(steps).started(fixture::requireFreshWorld);
    }

    /**
     * EXISTING 再造訪的前半段（W6x 與退化的 setup 共用）：與 {@link M5CollapseDormantChains#crash} 相同的原艙與 DORMANT NEW 收據 → 參與者由艙外進入 → 塌縮到
     * PASSAGE_READY（U 的投影 COMPLETE）→ 拿掉 Controller 上方的紅石塊，通道以 (d) 來源艙沒有供電結束 → 參與者移到艙外、恢復供電，原艙回到 IDLE。
     */
    private List<Step> firstPassageSteps() {
        return List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(1); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("firstReceipt", 1, () -> { fixture.putReceipt(fixture.dormantNewReceipt()); return true; }),
                new Step("firstEnter", 1, () -> { fixture.moveInside(); return true; }),
                new Step("firstCommitted", 40, fixture::committed),
                new Step("firstReady", 600, this::firstPassageReady),
                new Step("endedByPowerLoss", 60, this::endedByPowerLoss),
                new Step("repowered", 200, () -> M5GameTestSupport.registeredIdle(run.source(), FRAME) && M5GameTestSupport.controller(run.source(), FRAME).wasPowered()));
    }

    /** NEW 那一段到 PASSAGE_READY：U 的投影 COMPLETE；之後拿掉 Controller 上方的紅石塊（斷電）。 */
    private boolean firstPassageReady() throws Exception {
        var record = run.journal().flushedRecords().get(fixture.sid());
        if (record == null || record.state() != SessionState.PASSAGE_READY) return false;
        run.expect("firstProjectionComplete", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
        return true;
    }

    /** 通道以 (d) 來源艙沒有供電結束（record 已移除）之後，參與者移到艙外，再放回紅石塊恢復供電（參與者不在艙內，不會開始新的疊加）。 */
    private boolean endedByPowerLoss() {
        var sid = fixture.sid();
        if (run.journal().flushedRecords().containsKey(sid) || run.journal().records().containsKey(sid)) return false;
        run.expect("firstPassageEndedByPowerLoss", run.capture().matching(Level.INFO, "量子通道已結束", "(d) 來源艙沒有供電", sid.toString()).size() == 1,
                () -> "沒有恰一則 (d) 來源艙沒有供電的結束 INFO");
        fixture.moveOutside();
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        return true;
    }

    /** U 的投影（COMPLETE）改壞一格 shell（授權寫入玻璃），interior 放金塊標記（interior 不受保護，一般寫入）。投影的 chunk 以 probe 的 ticket 載入。 */
    private void damageProjection() {
        var world = fixture.universeWorld().orElseThrow(() -> new AssertionError("universeActive：EXISTING 收據之前 U 不是 ACTIVE"));
        fixture.holdProjectionChunk(world);
        run.expect("projectionIntactBeforeDamage", world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK) && world.getBlockState(interiorMarker()).isAir(),
                () -> "shell=" + world.getBlockState(damagedShell()) + " interior=" + world.getBlockState(interiorMarker()));
        run.expect("shellProtectedBeforeDamage", !ChamberProtectionService.get().mayMutate(world, damagedShell()), () -> "COMPLETE 投影的 shell 不受保護");
        ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(damagedShell(), Blocks.GLASS.getDefaultState(), 3));
        run.expect("shellDamaged", world.getBlockState(damagedShell()).isOf(Blocks.GLASS), () -> "shell=" + world.getBlockState(damagedShell()));
        run.expect("interiorMarkerPlaced", world.setBlockState(interiorMarker(), Blocks.GOLD_BLOCK.getDefaultState(), 3), () -> "interior 的標記放不上去");
        run.recordProof("damagedShell", damagedShell().toShortString());
        run.recordProof("interiorMarker", interiorMarker().toShortString());
    }

    // ---- 開服時 projection ledger 遺失（P-DEGRADED 的跨 JVM 版本） ----

    /**
     * setup phase（正常停止，不是 crash 窗口）：前半段（{@link #firstPassageSteps}：U 的投影 COMPLETE）→ U 的投影 interior 放金塊標記 → EXISTING 收據、原生存檔 →
     * 參與者由艙外進入 → 看到 flushed COLLAPSING 的同一個 tick 放上 refresh witness（{@link #placeRefreshWitness}）、正常停服（RESOLVE 在下一個 tick，所以停服時
     * 還沒有 RESOLVE）。
     */
    Phase degradedSetup() {
        var steps = new ArrayList<>(firstPassageSteps());
        steps.addAll(List.of(
                new Step("existingReceipt", 1, () -> {
                    placeInteriorMarker();
                    fixture.writeCrashReceipt(fixture.existingReceiptForTheUniverse());
                    return true;
                }),
                new Step("enter", 1, () -> { fixture.moveInside(); return true; }),
                new Step("committed", 40, () -> {
                    if (!fixture.committed()) return false;
                    placeRefreshWitness();
                    run.recordProof("diskAtStop", M5CollapseDiskEvidence.summary(run.saveRoot(), fixture.sid()));
                    return true;
                })));
        return Phase.stop(steps).started(fixture::requireFreshWorld);
    }

    /**
     * spec Minor（F6-2a）：退化的 restart 要證明 load-sync 的 refresh 越過了 {@code ChamberPowerCoordinator.refreshNow} 的提早 return（CONSUMED 本身不能證明；磁碟上的
     * PowerInitialized 在 setup 時就已是 true）。在 U 的投影 Controller 正上方（投影的 7×7×7 之外）放紅石塊，以 {@code NOTIFY_LISTENERS | FORCE_STATE} 寫入：不觸發
     * 鄰格更新，Controller 不 refresh，WasPowered 仍是 false；之後同一個 tick 停服存檔（不會再有 scheduled tick）。restart 的 refresh 讀到電源，WasPowered 才會變成 true。
     */
    private void placeRefreshWitness() {
        var world = fixture.universeWorld().orElseThrow(() -> new AssertionError("universeActive：停服之前 U 不是 ACTIVE"));
        var controller = world.getBlockEntity(FRAME.controllerPos()) instanceof ChamberControllerBlockEntity entity ? entity : null;
        boolean before = controller != null && !controller.wasPowered();
        boolean placed = world.setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
        run.expect("refreshWitnessPlaced", before && placed && !controller.wasPowered() && world.isReceivingRedstonePower(FRAME.controllerPos()),
                () -> "Controller=" + controller + " placed=" + placed + " wasPowered=" + (controller == null ? "-" : controller.wasPowered()));
        run.recordProof("refreshWitness", FRAME.controllerPos().up().toShortString());
    }

    /** U 的投影（COMPLETE）interior 放金塊標記（interior 不受保護，一般寫入）。投影的 chunk 以 probe 的 ticket 載入。 */
    private void placeInteriorMarker() {
        var world = fixture.universeWorld().orElseThrow(() -> new AssertionError("universeActive：EXISTING 收據之前 U 不是 ACTIVE"));
        fixture.holdProjectionChunk(world);
        run.expect("interiorEmptyBeforeMarker", world.getBlockState(interiorMarker()).isAir(), () -> "interior=" + world.getBlockState(interiorMarker()));
        run.expect("interiorMarkerPlaced", world.setBlockState(interiorMarker(), Blocks.GOLD_BLOCK.getDefaultState(), 3), () -> "interior 的標記放不上去");
        run.recordProof("interiorMarker", interiorMarker().toShortString());
    }

    /**
     * restart phase（退化）：開服前（{@code onInitialize}）把 projection ledger 移到 evidence → 開服時斷言初始化判定 LOST（{@code AUTHORITY_CORRUPT}）、本 instance 不提交新的
     * COLLAPSING、投影保護索引退化、record 仍是 COLLAPSING 且候選不變，以 probe 的 ticket 載入投影 chunk → 磁碟上的 Controller 本 JVM refresh 過（refresh witness）、
     * 走 foreign 的分支 → RESOLVE 進 SEALED(AUTHORITY_CORRUPT)，interior 沒有被重寫、ledger 沒有被重建 → 參與者真 JOIN（在艙內：setup 停服時參與者在艙內）→ 從艙內
     * 照一般方式開門（沒有人被困在封存的艙裡），spec §12 的終局（退化版：ledger 不存在、參與者在來源世界）。停服之後再確認 ledger 沒有被重建。
     */
    Phase degradedRestart(String setupPhase) {
        return Phase.stop(List.of(
                new Step("foreign", 60, this::projectionControllerForeign),
                new Step("sealed", 400, this::sealedAsCorrupt),
                new Step("join", 1, () -> { fixture.joinAllInTheSourceWorld(M5CollapseFixture.Inside.INSIDE); return true; }),
                new Step("doorOpens", 1, this::doorOpensNormally)))
                .beforeStart(this::removeProjectionLedgerBeforeStart).started(() -> degradedStarted(setupPhase)).everyTick(() -> passage.invariants(null))
                .afterStop(() -> run.expect("ledgerNotRecreatedAfterStop", !Files.exists(run.worldData().resolve(ProjectionLedger.FILE_NAME)), () -> "停服之後 projection ledger 被重建"));
    }

    /** 開服前：把 projection ledger 移到這個 phase 的 evidence（runner 已在這之前開始收 {@code quantumchamber} 的 log：初始化的 ERROR 在 {@code SERVER_STARTED} 的 default phase）。 */
    private void removeProjectionLedgerBeforeStart() throws Exception {
        var ledger = run.worldData().resolve(ProjectionLedger.FILE_NAME);
        run.expect("projectionLedgerOnDiskBeforeStart", Files.isRegularFile(ledger), () -> "開服前沒有 projection ledger：" + ledger);
        var moved = run.config.evidence().resolve("moved-" + ProjectionLedger.FILE_NAME);
        var sha256 = M5CollapseDiskEvidence.sha256(ledger);
        Files.move(ledger, moved);
        run.expect("projectionLedgerMovedBeforeStart", !Files.exists(ledger) && sha256.equals(M5CollapseDiskEvidence.sha256(moved)), () -> "projection ledger 沒有移走：" + ledger);
        run.recordProof("movedProjectionLedger", Map.of("from", ledger.toString(), "to", moved.toString(), "sha256", sha256));
    }

    private void degradedStarted(String setupPhase) throws Exception {
        fixture.readCrashReceipt(setupPhase, null);
        var initErrors = run.startupLog().matching(Level.ERROR, "M5 authority 初始化失敗").stream().map(LogCapture.Entry::message).toList();
        run.expect("initializationLost", initErrors.size() == 1 && initErrors.getFirst().contains("projection=LOST") && initErrors.getFirst().contains("allocation=LOADED"),
                () -> "初始化的 ERROR：" + initErrors);
        var authorities = CollapseAuthorities.get(run.server());
        run.expect("authorityCorrupt", !authorities.initialized()
                && authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER).equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "initialized=" + authorities.initialized() + " unhealthy=" + authorities.unhealthy(CollapseAuthorities.Authority.PROJECTION_LEDGER));
        run.expect("noNewCollapsingThisInstance", !run.journal().m5ReadyThisInstance(), () -> "初始化失敗但 m5ReadyThisInstance 為真");
        run.expect("indexDegraded", ProjectionProtectionIndex.get(run.server()).degradation().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "索引的退化=" + ProjectionProtectionIndex.get(run.server()).degradation());
        var sid = fixture.sid();
        var current = run.journal().flushedRecords().get(sid);
        run.expect("restartedCollapsing", current != null && current.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        run.expect("sameDestination", M5CollapseFixture.destination(current).equals(Optional.of(fixture.universe())), () -> "Destination=" + current.collapse().destination());
        run.expect("noRedraw", M5CollapseFixture.sameCandidate(fixture.receipt(), current), () -> "候選 context、ledger 或 selection 與停服前不同");
        fixture.expectOneAllocation();
        fixture.expectCatalog("catalogUnchanged");
        run.expect("bootstrapMaterializedEagerEnabled", fixture.universeWorld().isPresent(), () -> "開服 bootstrap 沒有物化 U");
        run.expect("alreadyDiscovered", M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().anyMatch(record -> record.universeId().equals(fixture.universe())),
                () -> "discovery 沒有 U");
        run.expect("ledgerMissingAtStart", !Files.exists(run.data().resolve(ProjectionLedger.FILE_NAME)), () -> "開服時 projection ledger 存在（被重建）");
        var world = fixture.universeWorld().orElseThrow();
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("interiorMarkerOnDisk", GOLD_BLOCK.equals(marker.name()), () -> "磁碟上的 interior 標記是 " + marker);
        var controller = fixture.diskBlock(world, FRAME.controllerPos());
        run.expect("controllerOnDiskAtRestart", M5CollapseFixture.controllerBlock(controller), () -> "磁碟上的 Controller 位置是 " + controller);
        var witness = fixture.diskBlock(world, FRAME.controllerPos().up());
        run.expect("refreshWitnessOnDisk", REDSTONE_BLOCK.equals(witness.name()), () -> "磁碟上 Controller 正上方是 " + witness);
        diskInstanceKind = M5CollapseDiskEvidence.blockEntityString(fixture.dimensionDirectory(world), FRAME.controllerPos(), "InstanceKind");
        run.recordProof("projectionControllerKindOnDisk", diskInstanceKind);
        run.recordProof("startedDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), sid));
        load = M5CollapseProjectionLoad.start(run, fixture);
        // 剛由磁碟載入時是磁碟上的值：InstanceKind 是 setup 時寫回的 PROJECTION、WasPowered 仍是 false（refresh witness 放上時沒有 refresh）、load-sync 還在佇列。
        var atLoad = load.atLoad();
        run.expect("loadedAsOnDisk", atLoad.instanceKind().name().equals(diskInstanceKind) && !atLoad.wasPowered() && atLoad.loadSyncPending(),
                () -> "載入時的 Controller：" + atLoad + "（磁碟上 " + diskInstanceKind + "）");
    }

    /**
     * 退化時投影保護索引不認得磁碟上的 Controller：load-sync 處理（CONSUMED、refresh 過）之後走動態世界的 foreign 分支（{@code ChamberPowerCoordinator} 的
     * 「不是投影、不是原生世界」：INVALID、比較器 0、沒有 chamber UUID），instanceKind 不寫回，維持磁碟上的值。setup 時這個 Controller 已 refresh 過並存檔，磁碟上
     * 已是 PROJECTION、INVALID，所以 block entity 的結果與投影的分支相同，兩者的區別是索引不認得它（{@code projectionControllerAt} 為假）。退化的保護另加同位置的
     * 不變量 cell。
     */
    private boolean projectionControllerForeign() {
        var world = fixture.universeWorld().orElseThrow();
        var controller = load.controller();
        if (!load.outcomes().contains(ChamberLoadSyncOutcome.CONSUMED) || !controller.powerInitialized()) return false;
        var pos = FRAME.controllerPos();
        // spec Minor（F6-2a）：CONSUMED 之後 WasPowered 由載入時的 false 變成 true：這次 refresh 越過了提早 return，讀到 refresh witness 的電源。
        run.expect("refreshedThisInstance", controller.wasPowered() && !load.atLoad().wasPowered(), () -> "WasPowered：載入時 " + load.atLoad().wasPowered()
                + "，現在 " + controller.wasPowered());
        run.expect("indexDoesNotKnowTheController", !ProjectionProtectionIndex.projectionControllerAt(run.server(), world.getRegistryKey(), pos),
                () -> "退化的索引認得這個 Controller");
        run.expect("foreignBranch", !controller.isRemoved() && world.getBlockEntity(pos) == controller && controller.chamberState() == ChamberState.INVALID
                && controller.chamberUuid() == null && world.getBlockState(pos).getComparatorOutput(world, pos) == 0
                && controller.instanceKind().name().equals(diskInstanceKind),
                () -> "Controller：state=" + controller.chamberState() + " uuid=" + controller.chamberUuid() + " kind=" + controller.instanceKind() + "（磁碟上 " + diskInstanceKind + "）");
        // spec §9.3 的退化：保護另加登記中的 Origin 艙在每個動態 Overworld 同位置的不變量 cell（Controller、shell、門），interior 不受保護。
        var protection = ChamberProtectionService.get();
        run.expect("degradedIndexProtectsTheSite", !protection.mayMutate(world, pos) && !protection.mayMutate(world, local(0, 0, 0))
                && !protection.mayMutate(world, local(3, 3, 0)) && protection.mayMutate(world, local(3, 3, 3)), () -> "退化時同位置的 Controller、shell、門應受保護，interior 不應受保護");
        run.recordProof("loadSyncOutcomes", load.outcomes().stream().map(Enum::name).toList());
        load.close();
        return true;
    }

    /** RESOLVE 進 SEALED(AUTHORITY_CORRUPT)：永久原因的 ERROR 一則、沒有 PREPARE、interior 沒有被重寫（記憶體與磁碟）、ledger 沒有被重建、原艙門關著。 */
    private boolean sealedAsCorrupt() throws Exception {
        var sid = fixture.sid();
        var record = run.journal().flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.SEALED) return false;
        run.expect("sealedAuthorityCorrupt", record.collapse().sealedReason().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)) && record.equals(run.journal().records().get(sid)),
                () -> "SEALED 的原因=" + record.collapse().sealedReason());
        var errors = run.capture().matching(Level.ERROR, sid.toString()).stream().map(LogCapture.Entry::message).toList();
        run.expect("onePermanentAdminError", errors.size() == 1 && errors.getFirst().contains("SEALED(AUTHORITY_CORRUPT)") && errors.getFirst().contains("需要管理員處理"),
                () -> "這個 session 的 ERROR：" + errors);
        run.expect("neverPrepared", run.capture().matching(Level.INFO, "準備完成", sid.toString()).isEmpty(), () -> "退化之後仍有「準備完成」");
        var world = fixture.universeWorld().orElseThrow();
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("interiorNotRewritten", world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK) && GOLD_BLOCK.equals(marker.name()),
                () -> "interior 標記：記憶體 " + world.getBlockState(interiorMarker()) + "，磁碟 " + marker);
        run.expect("ledgerNotRecreated", !Files.exists(run.data().resolve(ProjectionLedger.FILE_NAME)), () -> "projection ledger 被重建");
        run.expect("doorClosedOnSeal", M5CollapseDoors.closed(run), () -> "SEALED 之後原艙門不是關著");
        return true;
    }

    /**
     * SEALED 之後參與者從艙內照一般方式開門（spec §11.2）；record 仍是 SEALED(AUTHORITY_CORRUPT)，spec §12 的終局（{@link M5CollapsePassage#expectEnd}，退化版：catalog
     * 不變、projection ledger 不存在、參與者在來源世界、這個 session 的 ERROR 只有永久原因那一則，authority 本 instance 初始化失敗）。
     */
    private boolean doorOpensNormally() throws Exception {
        var player = fixture.selector();
        var result = M5GameTestSupport.interact(run.source(), local(3, 3, 0), player);
        run.expect("doorOpensNormally", result.isAccepted() && M5CollapseDoors.plainOpen(run), () -> "互動結果 " + result);
        var sid = fixture.sid();
        var record = run.journal().flushedRecords().get(sid);
        run.expect("stillSealed", record != null && record.state() == SessionState.SEALED && record.collapse().sealedReason().equals(Optional.of(SealedReason.AUTHORITY_CORRUPT)),
                () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        passage.expectEnd(M5CollapsePassage.End.degraded(fixture));
        return true;
    }
}
