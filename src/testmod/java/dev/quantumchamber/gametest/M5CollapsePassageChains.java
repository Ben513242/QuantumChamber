package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import com.google.gson.JsonObject;
import dev.quantumchamber.chamber.ChamberPowerState;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.gametest.M5CollapsePassage.End;
import dev.quantumchamber.gametest.M5CollapsePassage.Place;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2c）：通道與終局的 crash／restart chain（spec §12 的 PASSAGE_READY 列、W7，與其餘的重啟變體）。crash phase 都從 DORMANT 的 NEW 收據開始（與
 * {@link M5CollapseDormantChains#crash} 相同的原艙與觸發：參與者由艙外進入 → COLLAPSING → RESOLVE），之後依窗口推進到 PASSAGE_READY 或通道期間的那一刻。restart 的
 * 開服檢查一律走窗口表（{@link M5CollapseWindows#expectAtRestart}：{@code stateAtHalt}、{@code crossedAtHalt} 與窗口自己的磁碟檢查），另比對 record 與 halt 當下 exact
 * 相同（{@code recordKeptAcrossTheRestart}）；之後的 step 與終局（{@link End} 的逐人位置）依窗口而不同：
 * <ul>
 * <li>{@code w7}（{@code CROSSED_COMMITTED}，spec §12 W7）：兩人，選門者已跨過；重啟之後通道在開服時結束（checked 終局），門在下一個 tick 一般開門、比較器重新評估；已跨過的人在
 *     U 重新加入、另一位在原艙；沒有殘留的 runtime、ticket 或 RETURNING，同一個 server 內原艙再開新 session（checked ARMING）。</li>
 * <li>{@code readycommit}（{@code READY_COMMITTED}，F6-2-16）：PASSAGE_READY 已提交、門改為 passable 之前；重啟之後同 W7 的結束，參與者在艙內、門一般開門。</li>
 * <li>{@code terminaldoor}（{@code PASSAGE_TERMINATED}，F6-2-13）：(d) 終局已落盤、改門之前；重啟之後 Controller 的 load-sync 刷新排入找不到所屬 session 的量子膜的正規化，
 *     門在排入的下一個 tick 一般開門。</li>
 * <li>{@code crossedpending}（{@code CROSSED_UNCONFIRMED}，F6-2-17）：玩家已在 U、Crossed 的寫出沒有確認；重啟之後同 W7 的結束，他回到 playerdata 記的來源世界。</li>
 * <li>{@code trr}（{@code RECOVERY_SEALED}，F6-2-12）：SEALED(TRR) 已落盤；重啟之後仍是 SEALED、RecoveryRequired 與 Crossed 保留，參與者在艙內也不重試，照一般方式開門。</li>
 * <li>{@code trrpending}（{@code RECOVERY_REJECTED}，F6-2-12 的 pendingRecovery）：TRR 的寫入被拒、磁碟仍是 PASSAGE_READY；重啟之後同 W7 的結束，只剩前一個 JVM 的 log 證據。</li>
 * </ul>
 * spec §12 的四件事（同一個 UniverseId、catalog 最多多 1 筆、沒有重抽、沒有人被困住）：每個 tick 由 {@link M5CollapsePassage#invariants} 斷言，終局由
 * {@link M5CollapsePassage#expectEnd} 斷言。
 */
final class M5CollapsePassageChains {
    /** trr 的 restart：參與者在艙內之後觀察幾個 tick 不重試（可重試的原因在第一次觀察到參與者在艙內的那個 tick 就會重試）。 */
    static final int SEALED_WATCH_TICKS = 40;
    private static final String SOURCE_TICKET = "quantumchamber_source";
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** restart：crash 的窗口（窗口表的一列）、讀回的 halt receipt、開服時的 record（終局已落盤時為 null）。 */
    private M5CollapseWindows.Window window;
    private JsonObject halt;
    private SessionRecoveryRecord atStart;
    /** restart：通道在開服時結束的 server tick；trr 觀察了幾個 tick。 */
    private int endTick = -1, watched;
    /** terminaldoor 的 restart：load-sync 排入的正規化的 tick（INFO 的「tick N」）。 */
    private int normalizeTick = -1;
    /**
     * restart 開服時原艙門是不是已經是一般開門（由磁碟載入的值）。原版會在 tick 有餘裕時逐步存下變更過的 chunk（{@code ServerChunkLoadingManager.unloadChunks} 每個 tick 最多
     * 20 個、同一個 chunk 至少隔 10 秒），所以沒有在 halt 之前存檔的窗口，磁碟上的門是 crash 之前最後一次存下的樣子，不一定帶著量子膜。
     */
    private boolean doorPlainAtStart;

    M5CollapsePassageChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    // ---- crash phase ----

    /** crash phase 共用的前半段：fresh 世界蓋原艙、{@code people} 位參與者在艙外 → DORMANT 的 NEW 收據、原生存檔、裝上 {@code id} 的 crash 點 → 進入 → COLLAPSING → RESOLVE。 */
    private List<Step> untilResolved(int people, CrashWindow id) {
        return List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(people); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("receipt", 1, () -> {
                    fixture.writeCrashReceipt(fixture.dormantNewReceipt());
                    M5CollapseWindows.arm(run, fixture, id);
                    return true;
                }),
                new Step("enter", 1, () -> { fixture.moveInside(); return true; }),
                new Step("committed", 40, fixture::committed),
                passage.resolve());
    }

    private static Phase crashPhase(List<Step> steps, M5CollapseFixture fixture) {
        return Phase.halt(steps).started(fixture::requireFreshWorld);
    }

    /** W7：兩人；PASSAGE_READY 之後選門者越過中線，Crossed 的寫入確認時 halt（halt 之前原生存檔，見窗口表）。 */
    Phase w7Crash() {
        var steps = new ArrayList<>(untilResolved(2, CrashWindow.CROSSED_COMMITTED));
        steps.add(passage.ready(null, M5CollapseFixture.SELECTOR));
        steps.addAll(passage.cross(M5CollapseFixture.SELECTOR));
        return crashPhase(steps, fixture);
    }

    /** F6-2-16：一人；PREPARE 完成之後 COLLAPSING→PASSAGE_READY 的提交讀回確認時 halt（門還是 sealed 量子膜）。 */
    Phase readyCommitCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.READY_COMMITTED));
        steps.add(new Step("halt", 600, () -> false));
        return crashPhase(steps, fixture);
    }

    /**
     * F6-2-13：一人；PASSAGE_READY 之後原生存檔（passable 的門、PASSAGE 的 Controller、艙內的參與者都在磁碟上），再拿掉 Controller 上方的紅石塊 → 通道以 (d) 來源艙沒有
     * 供電結束，checked 終局讀回確認時 halt（改門之前）。
     */
    Phase terminalDoorCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.PASSAGE_TERMINATED));
        steps.add(passage.ready(null, M5CollapseFixture.NOBODY));
        steps.add(new Step("saved", 1, () -> {
            run.expect("nativeSaveDuringThePassage", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
            return true;
        }));
        steps.add(new Step("powerLoss", 1, () -> {
            run.source().setBlockState(FRAME.controllerPos().up(), net.minecraft.block.Blocks.AIR.getDefaultState(), 3);
            return true;
        }));
        steps.add(new Step("halt", 60, () -> false));
        return crashPhase(steps, fixture);
    }

    /** F6-2-17：一人；PASSAGE_READY 之後裝上 journal 寫出的失敗（Crossed 的寫入），越過中線、傳送到 U，Crossed 的寫出沒有確認時 halt。 */
    Phase crossedPendingCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.CROSSED_UNCONFIRMED));
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.add(new Step("flushFault", 1, () -> {
            var sid = fixture.sid();
            run.install(M5TestHooks.FLUSH_PROBE, (Consumer<SessionRecoveryState>) new M5GameTestSupport.FlushFault(run.server(), "F6-2c probe 受控：Crossed 的 journal 寫出失敗", 1000,
                    journal -> Optional.ofNullable(journal.records().get(sid)).filter(record -> !record.collapse().crossed().isEmpty()).isPresent()
                            && Optional.ofNullable(journal.flushedRecords().get(sid)).filter(record -> record.collapse().crossed().isEmpty()).isPresent()));
            return true;
        }));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        return crashPhase(steps, fixture);
    }

    /**
     * F6-2-12：兩人；選門者照常跨過（Crossed＝[選門者]）→ 另一位的原生移動往 U 與回滾都被拒（{@code FAILED_RECOVERY_REQUIRED}）→ PASSAGE_READY→SEALED(TRR) 的寫入確認時
     * halt。不存檔。
     */
    Phase trrCrash() {
        var steps = new ArrayList<>(untilResolved(2, CrashWindow.RECOVERY_SEALED));
        steps.add(passage.ready(null, M5CollapseFixture.SELECTOR));
        steps.addAll(passage.cross(M5CollapseFixture.SELECTOR));
        steps.add(new Step("recoveryFault", 1, () -> {
            installRecoveryFault();
            for (var id : fixture.members(M5CollapseFixture.OTHERS)) passage.standInDoorway(id, 0.7);
            return true;
        }));
        steps.add(new Step("recoveryDoorway", 1, () -> {
            for (var id : fixture.members(M5CollapseFixture.OTHERS)) passage.standInDoorway(id, 0.3);
            return true;
        }));
        steps.add(new Step("halt", 40, () -> false));
        return crashPhase(steps, fixture);
    }

    /**
     * F6-2-12 的 pendingRecovery：一人；原生移動往 U 與回滾都被拒，而且這個 session 寫成 SEALED 的 put 一律被拒 → 通道在記憶體內關閉（ERROR 一則）→ 退避期過後的重試又被拒時
     * halt（磁碟與 current 都仍是 PASSAGE_READY）。
     */
    Phase trrPendingCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.RECOVERY_REJECTED));
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.add(new Step("recoveryFaults", 1, () -> {
            installRecoveryFault();
            run.install(M5TestHooks.JOURNAL_PUT_FAULT, new M5TestHooks.JournalPutFault(run.server(), fixture.sid(), SessionState.SEALED, 1000));
            return true;
        }));
        steps.add(new Step("recoveryDoorway", 1, () -> {
            for (var id : fixture.participants()) passage.standInDoorway(id, 0.3);
            return true;
        }));
        steps.add(new Step("halt", 400, () -> false));
        return crashPhase(steps, fixture);
    }

    /** 選門者以外的參與者（trr）或唯一的參與者（trrpending）往 U 的原生移動被拒一次、回滾回來源世界也被拒一次：{@code FAILED_RECOVERY_REQUIRED}。 */
    private void installRecoveryFault() {
        var universe = fixture.universeWorld().orElseThrow().getRegistryKey();
        var others = fixture.members(M5CollapseFixture.OTHERS);
        var failing = others.isEmpty() ? fixture.selectorId() : others.iterator().next();
        run.install(M5TestHooks.NATIVE_MOVE_FAULT, new M5TestHooks.NativeMoveFault(failing, universe, 1, 1));
    }

    // ---- restart phase ----

    /** restart phase：依窗口組 step（見類別說明）。開服時讀回 crash phase 的收據、依窗口表斷言，再比對 record 與 halt 當下 exact 相同。 */
    Phase restart(CrashWindow id, String crashPhase) {
        window = M5CollapseWindows.of(id);
        var steps = new ArrayList<Step>();
        switch (id) {
            case CROSSED_COMMITTED -> {
                steps.addAll(endedAtStartup());
                steps.add(new Step("join", 1, () -> { fixture.joinAll(M5CollapseFixture.SELECTOR, true); return true; }));
                steps.add(new Step("end", 1, () -> {
                    passage.expectEnd(End.inTheSourceWorld(fixture).placed(fixture, M5CollapseFixture.SELECTOR, Place.UNIVERSE));
                    expectNoResidue();
                    return true;
                }));
                steps.add(new Step("closeDoor", 1, this::closeTheDoorForTheNextSession));
                steps.add(new Step("newSession", 200, this::newSessionInTheSameServer));
            }
            case READY_COMMITTED -> {
                steps.addAll(endedAtStartup());
                steps.add(new Step("join", 1, () -> { fixture.joinAll(true); return true; }));
                steps.add(new Step("end", 1, () -> { passage.expectEnd(End.inTheSourceWorld(fixture)); return true; }));
            }
            case PASSAGE_TERMINATED -> {
                steps.add(new Step("ownerlessQueued", 20, this::ownerlessQueued));
                steps.add(new Step("ownerlessNormalized", 20, this::ownerlessNormalizedOnTheNextTick));
                steps.add(new Step("join", 1, () -> { fixture.joinAll(true); return true; }));
                steps.add(new Step("end", 1, () -> { passage.expectEnd(End.inTheSourceWorld(fixture)); return true; }));
            }
            case CROSSED_UNCONFIRMED -> {
                steps.addAll(endedAtStartup());
                steps.add(new Step("join", 1, () -> { fixture.joinAll(false); return true; }));
                steps.add(new Step("end", 1, () -> { passage.expectEnd(End.inTheSourceWorld(fixture)); return true; }));
            }
            case RECOVERY_SEALED -> {
                steps.add(new Step("join", 1, () -> { fixture.joinAll(false); return true; }));
                steps.add(new Step("inside", 1, () -> { fixture.moveInside(); return true; }));
                steps.add(new Step("notRetried", SEALED_WATCH_TICKS + 5, this::sealedNotRetried));
                steps.add(new Step("doorOpens", 1, this::sealedDoorOpensNormally));
            }
            case RECOVERY_REJECTED -> {
                steps.addAll(endedAtStartup());
                steps.add(new Step("join", 1, () -> { fixture.joinAll(false); return true; }));
                steps.add(new Step("end", 1, () -> { passage.expectEnd(End.inTheSourceWorld(fixture)); return true; }));
            }
            default -> throw new IllegalArgumentException("通道的 chain 沒有這個窗口：" + id);
        }
        return Phase.stop(steps).started(() -> started(crashPhase)).everyTick(() -> passage.invariants(null));
    }

    private void started(String crashPhase) throws Exception {
        halt = fixture.readCrashReceipt(crashPhase, window.id());
        M5CollapseWindows.expectAtRestart(run, fixture, window, halt);
        atStart = run.journal().flushedRecords().get(fixture.sid());
        run.proof.put("doorCenterOnDiskAtStart", M5CollapseWindows.doorCenterOnDisk(run, fixture));
        run.proof.put("doorCenterAtStart", run.source().getBlockState(local(3, 3, 0)).toString());
        doorPlainAtStart = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        var atHalt = halt.getAsJsonObject("atHalt");
        if (atStart != null) {
            String expected = M5CollapseRun.text(atHalt, "recordAtHalt");
            run.expect("recordKeptAcrossTheRestart", M5CollapseFixture.recordDigest(atStart).equals(expected),
                    () -> "開服時的 record " + M5CollapseFixture.recordDigest(atStart) + " 與 halt 當下不同：" + expected);
        }
        if (window.id() == CrashWindow.RECOVERY_REJECTED) {
            // spec §10.3：磁碟仍是 PASSAGE_READY、在任何一次寫出成功之前就 crash，重啟依 §12 的 PASSAGE_READY 列結束，TRR 只剩前一個 JVM 的 log 證據。
            var evidence = M5CollapseRun.text(atHalt, "recoveryError");
            run.expect("recoveryOnlyInThePreviousLog", evidence != null && evidence.contains(M5CollapseWindows.REJECTED_RECOVERY_ERROR)
                            && atStart.collapse().recoveryRequired().isEmpty() && atStart.collapse().sealedReason().isEmpty(),
                    () -> "halt 當下的 ERROR=" + evidence + " 開服時的 RecoveryRequired=" + atStart.collapse().recoveryRequired());
        }
    }

    /**
     * spec §12 的 PASSAGE_READY 列：開服時直接結束通道（第一個 tick checked 終局，INFO 一則），門在下一個 tick 一般開門（終局的那個 tick 還沒有改門）；終局之後比較器依
     * presence NONE 重新評估（R2 M5：開服時存下的 PASSAGE／15 不保留；這時沒有人在線上，是 IDLE、3），registry 的 power state 解除為 POWERED。
     */
    private List<Step> endedAtStartup() {
        return List.of(new Step("endedAtStartup", 5, this::passageEndedAtStartup), new Step("plainOpen", 3, this::plainOpenOnTheNextTick));
    }

    private boolean passageEndedAtStartup() {
        var sid = fixture.sid();
        boolean gone = !run.journal().flushedRecords().containsKey(sid) && !run.journal().records().containsKey(sid);
        // 最多等 3 個 tick，之後仍在就由這個具名斷言判 FAIL（不是 stepBounded）：mutation 讓通道在開服之後繼續時，失敗在目標斷言。
        if (!gone && run.ticks() < 3) return false;
        endTick = run.server().getTicks();
        run.proof.put("endTick", endTick);
        run.proof.put("doorCenterOnTheEndTick", run.source().getBlockState(local(3, 3, 0)).toString());
        run.expect("passageEndedAtStartup", gone && run.capture().matching(Level.INFO, "開服時仍是 PASSAGE_READY 的量子通道已結束", sid.toString()).size() == 1,
                () -> "開服之後 " + run.ticks() + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), sid) + "，沒有恰一則開服時結束通道的 INFO");
        run.expect("endedOnTheFirstTick", run.ticks() == 1, () -> "通道在 phase 的第 " + run.ticks() + " 個 tick 才結束");
        // 「門在下一個 tick 正規化」：開服時門不是一般開門（帶著量子膜，或關著）時，終局的那個 tick 還沒有改門。
        if (!doorPlainAtStart) {
            run.expect("notYetOpenOnTheEndTick", !M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE), () -> "終局的那個 tick 原艙門已是一般開門");
        }
        run.expect("comparatorReevaluated", fixture.controllerSignal(ChamberState.IDLE, 3), () -> "終局之後的 Controller=" + controllerState());
        run.expect("poweredAfterTheEnd", powerState() == ChamberPowerState.POWERED, () -> "終局之後 registry 的 power state=" + powerState());
        return true;
    }

    private boolean plainOpenOnTheNextTick() {
        boolean plain = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        int now = run.server().getTicks();
        if (!plain && now < endTick + 3) return false;
        run.expect("plainOpenOnTheNextTick", plain && now == endTick + 1, () -> "原艙門在 tick " + now + "（終局在 tick " + endTick + "）：一般開門=" + plain);
        return true;
    }

    /**
     * F6-2-13：Controller 的 load-sync 刷新（presence NONE、門帶著量子膜）排入找不到所屬 session 的量子膜的正規化（INFO 一則，記下「下一個 tick（tick N）」）；刷新之後比較器
     * 依 presence NONE 重新評估（IDLE、3）。load-sync 的刷新可能早於塌縮流程的第一個 tick，所以只比對 INFO 記下的 tick。
     */
    private boolean ownerlessQueued() {
        var queued = run.startupLog().matching(Level.INFO, "找不到所屬 session、卻帶著量子膜的原艙門", fixture.chamber().toString()).stream().map(LogCapture.Entry::message).toList();
        // 最多等 10 個 tick，之後仍沒有排入就由這個具名斷言判 FAIL（不是 stepBounded）。
        if (queued.isEmpty() && run.ticks() < 10) return false;
        var matcher = java.util.regex.Pattern.compile("tick (\\d+)").matcher(queued.isEmpty() ? "" : queued.getFirst());
        run.expect("ownerlessQueuedByTheLoadSync", queued.size() == 1 && matcher.find(), () -> "開服之後 " + run.ticks() + " 個 tick，排入的 INFO：" + queued);
        normalizeTick = Integer.parseInt(matcher.group(1));
        run.proof.put("ownerlessNormalizeTick", normalizeTick);
        run.expect("noSessionAtRestart", run.journal().flushedRecords().values().stream().noneMatch(record -> record.chamberUuid().equals(fixture.chamber())),
                () -> "開服之後這座艙有 session");
        run.expect("comparatorReevaluated", fixture.controllerSignal(ChamberState.IDLE, 3), () -> "load-sync 刷新之後的 Controller=" + controllerState());
        return true;
    }

    private boolean ownerlessNormalizedOnTheNextTick() {
        boolean plain = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        int now = run.server().getTicks();
        if (!plain && now < normalizeTick + 2) return false;
        var normalized = run.startupLog().matching(Level.INFO, "找不到所屬 session 的量子膜已正規化為一般開門", "tick " + normalizeTick, fixture.chamber().toString());
        run.expect("ownerlessNormalizedOnTheNextTick", plain && now == normalizeTick && normalized.size() == 1,
                () -> "原艙門在 tick " + now + "（排入的是 tick " + normalizeTick + "）：一般開門=" + plain + " INFO=" + normalized.size());
        return true;
    }

    /** W7：終局之後沒有殘留：presence NONE（沒有 runtime）、來源與目的世界都沒有這個 session 的 ticket、registry 的 power state 不是 RETURNING。 */
    private void expectNoResidue() {
        var sid = fixture.sid();
        var presence = ChamberSessions.gateway().presence(run.server(), fixture.chamber());
        run.expect("noRuntimeLeft", presence == ChamberSessionGateway.Presence.NONE, () -> "presence=" + presence);
        var source = fixture.tickets(run.source(), SOURCE_TICKET, sid);
        run.expect("noSourceTicketLeft", source.isEmpty(), () -> "來源 ticket=" + source);
        var destination = M5GameTestSupport.destinationTickets(fixture.universeWorld().orElseThrow(), sid);
        run.expect("noDestinationTicketLeft", destination.isEmpty(), () -> "目的世界的 ticket=" + destination);
        run.expect("notReturning", powerState() != ChamberPowerState.RETURNING, () -> "registry 的 power state=" + powerState());
    }

    /** W7：沒跨過的人在艙內照一般方式關門（下一個 session 要門關著）。 */
    private boolean closeTheDoorForTheNextSession() {
        var other = fixture.player(fixture.members(M5CollapseFixture.OTHERS).iterator().next());
        var result = M5GameTestSupport.interact(run.source(), local(3, 3, 0), other);
        run.expect("doorClosedForTheNextSession", result.isAccepted() && M5GameTestSupport.doorAll(run.source(), FRAME, false, MembraneState.NONE), () -> "互動結果 " + result);
        return true;
    }

    /** W7：同一個 server 內，沒跨過的人以真實的 LOW→HIGH 供電與 Buff 在原艙開新 session（{@link M5CollapseFixture#arm}：checked ARMING）。 */
    private boolean newSessionInTheSameServer() throws Exception {
        var arming = fixture.arm(M5CollapseFixture.OTHERS);
        if (arming == null) return false;
        var others = fixture.members(M5CollapseFixture.OTHERS);
        run.expect("newSessionInTheSameServer", !arming.sessionUuid().equals(fixture.sid())
                        && arming.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).collect(java.util.stream.Collectors.toSet()).equals(others),
                () -> "新的 session=" + arming.sessionUuid() + " 參與者=" + arming.participants());
        run.proof.put("newSession", arming.sessionUuid().toString());
        return true;
    }

    /**
     * trr：參與者在艙內（開服之後第一次觀察到，可重試的原因這時就會重試）之後觀察 {@link #SEALED_WATCH_TICKS} 個 tick：record 一直與開服時 exact 相同；沒有「SEALED 重試」的
     * INFO、也沒有這個 session 的 WARN（journal 層級也拒絕 TRR 的重試，嘗試過就會有 WARN）；presence 是 COLLAPSE、比較器 ARMED（11）。
     */
    private boolean sealedNotRetried() {
        var sid = fixture.sid();
        if (watched == 0) {
            for (var player : fixture.players()) run.expect("participantsInsideWhileSealed", M5CollapseFixture.inside(player), () -> "參與者在 " + player.getPos());
        }
        run.expect("sealedUnchanged", atStart.equals(run.journal().flushedRecords().get(sid)) && atStart.equals(run.journal().records().get(sid)),
                () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        if (++watched < SEALED_WATCH_TICKS) return false;
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).stream().map(LogCapture.Entry::message).toList();
        var warned = run.capture().matching(Level.WARN, sid.toString()).stream().map(LogCapture.Entry::message).toList();
        run.expect("trrNotRetried", retried.isEmpty() && warned.isEmpty(), () -> "重試的 INFO=" + retried + " WARN=" + warned);
        var presence = ChamberSessions.gateway().presence(run.server(), fixture.chamber());
        run.expect("presenceCollapse", presence == ChamberSessionGateway.Presence.COLLAPSE, () -> "presence=" + presence);
        run.expect("comparatorArmedWhileSealed", fixture.controllerSignal(ChamberState.ARMED, 11), () -> "Controller=" + controllerState());
        return true;
    }

    /** trr：SEALED 時任何玩家都可以照一般方式開門（spec §11.2）；record 仍與開服時 exact 相同；spec §12 的終局斷言（參與者都在來源世界）。 */
    private boolean sealedDoorOpensNormally() throws Exception {
        var result = M5GameTestSupport.interact(run.source(), local(3, 3, 0), fixture.selector());
        run.expect("doorOpensNormally", result.isAccepted() && M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE), () -> "互動結果 " + result);
        var sid = fixture.sid();
        run.expect("stillSealed", atStart.equals(run.journal().flushedRecords().get(sid)), () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        passage.expectEnd(End.inTheSourceWorld(fixture));
        return true;
    }

    private ChamberPowerState powerState() {
        var record = ChamberRegistryState.get(run.server()).registry().records().get(fixture.chamber());
        return record == null ? null : record.powerState();
    }

    private String controllerState() {
        var controller = M5GameTestSupport.controller(run.source(), FRAME);
        return controller.chamberState() + "／" + run.source().getBlockState(FRAME.controllerPos()).getComparatorOutput(run.source(), FRAME.controllerPos());
    }
}
