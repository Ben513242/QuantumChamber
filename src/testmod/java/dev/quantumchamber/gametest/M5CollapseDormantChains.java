package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionProtectionIndex;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：DORMANT 收據的 crash／restart chain（W2、W3、W5、W4、W4b、W6；restart 也用於 W6x）。
 * 窗口的條件、halt 之前的取證與 restart 的預期都在窗口表（{@link M5CollapseWindows}）。
 */
final class M5CollapseDormantChains {
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** restart：crash 的窗口（窗口表的一列）。 */
    private M5CollapseWindows.Window window;
    /** restart（W4、W6）：JOIN 之前載入的投影 Controller 與它的 load-sync 觀察。 */
    private M5CollapseProjectionLoad load;

    M5CollapseDormantChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    /**
     * crash phase：fresh 世界蓋原艙、連線參與者並移到艙外 → 原艙註冊之後寫 DORMANT 的 NEW 收據（參與者已返還、沒有 lease）、原生存檔（原艙、registry、journal、
     * 參與者的 playerdata 都在磁碟上；參與者存在艙外），再依窗口表裝上 {@code id} 的 crash 點 → 參與者由艙外進入 → 塌縮提交 COLLAPSING → RESOLVE 或 PREPARE 走到窗口時 halt。
     */
    Phase crash(CrashWindow id) {
        return Phase.halt(List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(1); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("receipt", 1, () -> {
                    fixture.writeCrashReceipt(fixture.dormantNewReceipt());
                    M5CollapseWindows.arm(run, fixture, id);
                    return true;
                }),
                new Step("enter", 1, () -> { fixture.moveInside(); return true; }),
                new Step("committed", 40, fixture::committed),
                new Step("halt", 400, () -> false)))
                .started(fixture::requireFreshWorld);
    }

    /**
     * restart phase：讀回 crash phase 的 halt receipt 與 MEASURED 收據，開服時依窗口表斷言窗口的磁碟狀態（{@link M5CollapseWindows#expectAtRestart}；W4、W6 在 JOIN
     * 之前載入磁碟上的投影 Controller，等它經 load-sync 辨識為 inert 的 PROJECTION）；之後參與者真 JOIN（在艙外、來源世界）→ RESOLVE 完成 → 進入艙內 →
     * PASSAGE_READY（門 passable、比較器 15、投影 COMPLETE；投影的窗口另斷言 PREPARE 怎麼完成）→ 越過門板中線、傳送到 U → (b) 全員都已跨過的終局。每個 tick 斷言
     * catalog 只出現 Destination 的 UniverseId、record 的 Destination 與候選不變。F6-2d-2 B1：開服時門是關著的窗口（窗口表的
     * {@code withDoorAtStart(CLOSED)}；F6-2d-2 B2a 之前是這個類別的 {@code CLOSED_DOOR_AT_RESTART}）在 JOIN 之前另斷言
     * 重啟之後的重新套用不把關著的門打開（{@link M5CollapseDoors#closedDoorKeptByTheReapply}；F6-2d-2 B2a 起在共用的 {@link M5CollapseDoors}，不再依賴
     * {@link M5CollapseDoorChains}）。
     */
    Phase restart(CrashWindow id, String crashPhase) {
        window = M5CollapseWindows.of(id);
        var steps = new ArrayList<Step>();
        // W4、W6：參與者 JOIN 之前，磁碟上的投影 Controller 經 load-sync 辨識為 inert 的 PROJECTION（不記 log）。
        if (window.atRestart().recognizeBeforeJoin()) steps.add(new Step("recognized", 60, this::projectionRecognized));
        // F6-2d-2 B1（F6-2-08 的另一半）：開服時原艙門是關著的窗口，參與者 JOIN 之前先斷言重新套用（NORMALIZE）之後門仍關著。
        boolean closedAtStart = window.atRestart().doorAtStart().equals(java.util.Optional.of(M5CollapseWindows.OriginDoorState.CLOSED));
        if (closedAtStart) steps.addAll(M5CollapseDoors.closedDoorKeptByTheReapply(run, fixture));
        steps.add(new Step("join", 1, () -> {
            // F6-2d-2 B2a（B1 quality Minor 1）：開服時門關著的窗口，JOIN 之前（同一個 step，JOIN 的 tick 不變）再驗門仍關著、沒有套用、排入仍恰一則。
            if (closedAtStart) M5CollapseDoors.closedDoorStillKeptAtTheJoin(run, fixture);
            fixture.joinAllInTheSourceWorld(M5CollapseFixture.Inside.OUTSIDE);
            return true;
        }));
        steps.addAll(passage.steps(window.prepared()));
        return Phase.stop(steps).started(() -> load = M5CollapseWindows.expectAtRestart(run, fixture, window, fixture.readCrashReceipt(crashPhase, id)))
                .everyTick(() -> passage.invariants(null));
    }

    /**
     * W4、W6：load-sync 處理（CONSUMED）之後，磁碟上的 Controller 是 inert 的 PROJECTION（spec §9.3：依投影保護索引，INVALID、比較器 0、沒有 chamber UUID），整段沒有任何
     * log 提到它的座標，參與者還沒有 JOIN。COMPLETE（W6）的不變量 cell 受保護、interior 不受；PENDING（W4）只辨識、不保護。probe 的 ticket 不移除。
     */
    private boolean projectionRecognized() {
        var world = fixture.universeWorld().orElseThrow();
        var controller = load.controller();
        if (!load.outcomes().contains(ChamberLoadSyncOutcome.CONSUMED) || !M5CollapseFixture.inertProjection(world, controller)) return false;
        var pos = FRAME.controllerPos();
        var mentions = Stream.concat(run.capture().entries().stream(), load.log().entries().stream())
                .map(LogCapture.Entry::message).filter(message -> message.contains(pos.toShortString()) || message.contains(pos.toString())).toList();
        run.expect("noLogAboutTheProjectionController", mentions.isEmpty(), () -> "log 提到投影 Controller 的座標：" + mentions);
        run.expect("recognizedBeforeJoin", fixture.noneOnline(), () -> "參與者已 JOIN");
        var key = world.getRegistryKey();
        run.expect("indexKnowsTheController", ProjectionProtectionIndex.projectionControllerAt(run.server(), key, pos), () -> "投影保護索引不認得這個 Controller");
        var protection = ChamberProtectionService.get();
        if (window.atRestart().projection() == ProjectionLedger.Entry.Status.COMPLETE) {
            run.expect("invariantCellsProtected", !protection.mayMutate(world, pos) && !protection.mayMutate(world, local(0, 0, 0))
                    && !protection.mayMutate(world, local(3, 3, 0)) && protection.mayMutate(world, local(3, 3, 3)),
                    () -> "Controller、shell、門應受保護，interior 不應受保護");
        } else {
            run.expect("pendingNotProtected", protection.mayMutate(world, pos) && protection.mayMutate(world, local(0, 0, 0)), () -> "PENDING 的投影不應受保護");
        }
        run.recordProof("loadSyncOutcomes", load.outcomes().stream().map(Enum::name).toList());
        load.close();
        return true;
    }
}
