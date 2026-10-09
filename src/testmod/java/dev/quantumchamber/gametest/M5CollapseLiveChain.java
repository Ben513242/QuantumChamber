package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryManager;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.ActionResult;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：live session 的 crash／restart chain（W1）。
 */
final class M5CollapseLiveChain {
    /** restart：參與者 JOIN 之後最多在走廊幾個 tick（之後仍在走廊就是被困住）。 */
    static final int RETURN_TICK_LIMIT = 100;
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** restart：JOIN 的 tick（phase 的第幾個 tick），與參與者是否都已由走廊返還。 */
    private int joinedAt = -1;
    private boolean returned;

    M5CollapseLiveChain(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    /**
     * crash phase（W1）：fresh 世界蓋原艙、連線參與者並移到艙內 → 原艙註冊之後以真實的 LOW→HIGH 供電與 Buff 啟動（比照 M4 recovery probe 的 build／arm）→
     * SUPERPOSITION、358 扇側門發布、參與者在走廊 → 原生存檔（參與者的 playerdata 存在走廊）→ 選門者以原生互動選一扇 NEW 候選門（checked SELECTED，MEASURED）、寫出
     * {@link M5CollapseRun#RECEIPT_NBT}、裝上 crash 點 → 下一個 tick 塌縮流程在 operation guard 之內提交 MEASURED→COLLAPSING，提交讀回確認時 halt（返還與清理都還沒有開始）。
     */
    Phase crash() {
        return Phase.halt(List.of(
                new Step("build", 1, () -> {
                    fixture.buildOrigin(1);
                    fixture.moveInside();
                    return true;
                }),
                new Step("armed", 200, this::armed),
                new Step("superposition", 400, this::superposition),
                new Step("selected", 1, this::selectNewDoor),
                new Step("halt", 400, () -> false)))
                .started(fixture::requireFreshWorld);
    }

    /**
     * restart phase（W1）：讀回 crash phase 的收據，開服時依窗口表斷言還沒有返還、走廊 space 已重建、沒有保留式 recovery 的請求、RESOLVE 還沒有開始
     * （{@link M5CollapseWindows#expectAtRestart}）→ 參與者真 JOIN（在走廊、被 recovery 攔截）→ 不經請求返還到來源世界 → 清 lease、space 釋放（SETTLED）→ RESOLVE →
     * PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase restart(String crashPhase) {
        var window = M5CollapseWindows.of(CrashWindow.COLLAPSING_COMMITTED);
        var steps = new ArrayList<Step>(List.of(
                new Step("join", 1, this::joinIntoTheCorridor),
                new Step("returned", 200, this::returnedFromTheCorridor),
                new Step("settled", 200, this::settled)));
        steps.addAll(passage.steps(window.prepared()));
        var allowance = new M5CollapsePassage.CorridorAllowance(() -> !returned, () -> joinedAt, RETURN_TICK_LIMIT);
        return Phase.stop(steps).started(() -> M5CollapseWindows.expectAtRestart(run, fixture, window, fixture.readCrashReceipt(crashPhase, window.id())))
                .everyTick(() -> passage.invariants(allowance));
    }

    /**
     * 原艙註冊之後，比照 M4 recovery probe 的 arm（{@link M5CollapseFixture#arm}：拿掉紅石塊（LOW）、給參與者 Buff，readiness 通過之後存參與者的 checkpoint 與原生存檔，
     * 再放回紅石塊（HIGH）→ 必須立即有 checked ARMING）。readiness 還沒有通過時下一個 tick 再試。ARMING 的 session 就是這條 chain 的 session。
     */
    private boolean armed() throws Exception {
        var arming = fixture.arm(M5CollapseFixture.EVERYONE);
        if (arming == null) return false;
        fixture.session(arming.sessionUuid());
        return true;
    }

    /** SUPERPOSITION、358 扇側門都已發布、參與者都在走廊。 */
    private boolean superposition() {
        var record = run.journal().flushedRecords().get(fixture.sid());
        if (record == null || record.state() != SessionState.SUPERPOSITION || fixture.pages().selectableDoors(fixture.sid()).size() != 358) return false;
        return fixture.players().stream().allMatch(player -> player.getServerWorld() == fixture.corridor());
    }

    /** 選門之前原生存檔（參與者的 playerdata 存在走廊）；選門者以原生互動選一扇 NEW 候選門，checked SELECTED 之後寫出收據、裝上 W1 的 crash 點。 */
    private boolean selectNewDoor() throws Exception {
        run.expect("nativeSaveInTheCorridor", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
        var sid = fixture.sid();
        var door = M5GameTestSupport.candidateDoor(fixture.pages(), fixture.corridor(), sid, QuantumCandidate.New.class);
        // 選門之前還沒有收據：由第一位參與者去選門（F6-2c 起 fixture.selector() 由收據的 selection 推導，選門之後才有）。
        var result = M5GameTestSupport.interact(fixture.corridor(), door.cell(), fixture.player(fixture.participants().getFirst()));
        var measured = run.journal().flushedRecords().get(sid);
        run.expect("newDoorSelected", result == ActionResult.SUCCESS && measured != null && measured.state() == SessionState.MEASURED
                && measured.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New, () -> "選門結果 " + result + "，record=" + M5GameTestSupport.state(run.journal(), sid));
        run.expect("notReturnedOnSelection", !fixture.pages().measuredRecoveryRequested(sid), () -> "正常測量不應立即返還");
        fixture.adopt(measured);
        fixture.recordReceipt();
        M5CollapseWindows.arm(run, fixture, CrashWindow.COLLAPSING_COMMITTED);
        return true;
    }

    private boolean joinIntoTheCorridor() {
        for (var id : fixture.participants()) {
            var connection = fixture.connect(id);
            var player = connection.player();
            run.expect("nativeJoin", connection.joinTick() >= 0, () -> "沒有觀察到 JOIN");
            run.expect("joinedInTheCorridor", player.getServerWorld() == fixture.corridor(), () -> "參與者 JOIN 在 " + player.getServerWorld().getRegistryKey().getValue());
            run.expect("participantBlockedUntilReturned", SessionRecoveryManager.blocks(id, run.server()), () -> "尚未返還的參與者沒有被 recovery 攔截");
        }
        joinedAt = run.ticks();
        return true;
    }

    /** 參與者都回到來源世界，flushed record 記為已返還；record 仍是 COLLAPSING，沒有經過保留式 recovery 的請求。 */
    private boolean returnedFromTheCorridor() {
        var players = fixture.players();
        var record = run.journal().flushedRecords().get(fixture.sid());
        if (players.stream().anyMatch(player -> player.getServerWorld() != run.source()) || record == null
                || record.participants().stream().anyMatch(person -> !person.returned())) return false;
        run.expect("returnedWhileCollapsing", record.state() == SessionState.COLLAPSING, () -> "state=" + record.state());
        run.expect("returnedWithoutRequest", !fixture.pages().measuredRecoveryRequested(fixture.sid()), () -> "COLLAPSING 的返還經過了 requestMeasuredRecovery");
        for (var id : fixture.participants()) {
            run.expect("participantUnblocked", !SessionRecoveryManager.blocks(id, run.server()), () -> "已返還的參與者仍被 recovery 攔截");
        }
        run.proof.put("returnedInsideTheChamber", players.stream().allMatch(M5CollapseFixture::inside));
        returned = true;
        return true;
    }

    /** 清 lease、走廊 space 釋放（{@code measuredRecoveryComplete}：SETTLED）；在那之前 record 一直是 COLLAPSING。 */
    private boolean settled() {
        var record = run.journal().flushedRecords().get(fixture.sid());
        run.expect("collapsingUntilSettled", record != null && record.state() == SessionState.COLLAPSING, () -> "state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
        if (!fixture.pages().measuredRecoveryComplete(fixture.sid())) return false;
        run.expect("leaseCleared", record.spaceLeases().isEmpty() && MeasuredRecoveryPhase.from(record) == MeasuredRecoveryPhase.SETTLED && !fixture.spaceExists(),
                () -> "leases=" + record.spaceLeases() + " phase=" + MeasuredRecoveryPhase.from(record));
        return true;
    }
}
