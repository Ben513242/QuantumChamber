package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapsePassage.End;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.server.network.ServerPlayerEntity;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-2 B1）：PREPARE 的原艙門跨重啟的 crash／restart chain（spec v5.10 §7.3「套用量子膜的條件」「門口有人時的規則」「門狀態的重新套用」、§12 的 COLLAPSING
 * 列「門的狀態在下一個 tick 重新套用」；交接清單 F6-2-08、F6-2-10）。兩條 chain 共用窗口表的 {@code READY_HELD} 一列（{@link #readyHeldCrash}）：DORMANT 的 NEW 收據（一人），
 * 裝上 PASSAGE_READY 的提交暫緩（{@code READY_HOLD}，hold 類，不靠時序）→ 參與者由艙外進入 → COLLAPSING → RESOLVE → 閘門第一次以位準評估 → PREPARE → 準備完成之後
 * {@code ready} 被暫緩的那一刻 halt（{@code CollapseReadyHoldMixin}；同一個 tick 的 {@code prepareDoor} 已在 {@code ready} 之前套用 sealed 量子膜，projection ledger 已
 * COMPLETE），halt 之前原生存檔：磁碟上是 COLLAPSING、sealed 量子膜、參與者在艙內。restart 開服時依窗口表斷言（{@link M5CollapseWindows#expectAtRestart}），原艙附近以 probe 的
 * ticket 同步載入（{@link ChamberAtStart#PRELOADED}），之後：
 * <ul>
 * <li>{@code waitingdoor}（F6-2-08，T-START 的跨 JVM 版本）：參與者開服時不在線上，閘門第一次評估沒有人在艙內 → WAITING。重啟之後第一次載入的那個 tick 排入一則 NORMALIZE
 *     （原因「重啟之後第一次載入」，那個 tick 門仍是 sealed）→ 排入時記下的下一個 tick 改為一般開門 → 觀察 {@link M5CollapsePassage#WATCH_TICKS} 個 tick：一直是一般開門、
 *     沒有進入 PREPARE、record 是 COLLAPSING、排入與套用各一則 → 參與者 JOIN 之後由艙外進入（邊緣）→ PREPARE → PASSAGE_READY → 跨越 → (b) 終局。</li>
 * <li>{@code preparedoor}（F6-2-10，F4b-3 第 10 組的 PREPARE 版本）：開服時（第一個 tick 之前）裝上 {@code READY_HOLD}、參與者 JOIN 在艙內、另一位非參與者 JOIN 並移到艙內 →
 *     閘門以位準評估 → PREPARE（同一個 tick 移除還在等的 NORMALIZE），艙內有非參與者，原艙門維持一般開門 → 準備完成、PASSAGE_READY 被暫緩，之後再 20 個 tick，每個 tick 都是
 *     一般開門 → 非參與者移到艙外 → 下一個 tick 是 sealed 量子膜 → 釋放暫緩 → PASSAGE_READY → 跨越 → (b) 終局。</li>
 * </ul>
 * spec §12 的四件事每個 tick 由 {@link M5CollapsePassage#invariants} 斷言（{@link #restartPhase}），終局由 {@link M5CollapsePassage#expectEnd}。
 * <p>放在新的類別（不併入 {@link M5CollapseRetryChains}）：窗口條件與門的斷言共用，F6-2d-2 B2 的 SEALED 門窗口也放這裡；restart builder 與觸發之後的等法由基底
 * {@link M5CollapseRestartChains} 提供，不另寫一份。
 */
final class M5CollapseDoorChains extends M5CollapseRestartChains {
    /** 重新套用的排入最多等幾個 tick（重啟之後的第一次觀察在第一個 tick；超過時由具名斷言判 FAIL，不是 stepBounded：mutation 讓排入落空時失敗在目標斷言）。 */
    private static final int QUEUE_WAIT_TICKS = 10;
    /** 排入之後到套用最多等幾個 tick（排入時記下的 tick 是下一個 tick；之後再 2 個 tick 仍沒有改門就由具名斷言判 FAIL，比照 {@code terminaldoor} 的 {@code ownerlessNormalized}）。 */
    private static final int APPLY_WAIT_TICKS = 3;
    /** preparedoor：PREPARE 準備完成、PASSAGE_READY 的提交第一次被暫緩之前最多等幾個 tick（預載、REPAIR 與 checkpoint；比照 {@code passage.ready} 的上限）。 */
    private static final int HELD_WAIT_TICKS = 600;
    /** preparedoor：PASSAGE_READY 的提交被暫緩之後，非參與者再留在艙內幾個 tick（每個 tick 都斷言一般開門）。 */
    private static final int VISITOR_WATCH_TICKS = 20;
    /** preparedoor：非參與者在艙內的位置（區域座標；參與者在正中 (3.5, 1, 3.5)，錯開、不碰到門口那一排）。 */
    private static final double VISITOR_X = 1.5, VISITOR_Z = 4.5;
    /** 重新套用排入（或延後）時的 INFO（{@code OriginDoors.reapplyDoor}）的「下一個 tick（tick N）」；排入時另帶目標（延後時沒有）。 */
    private static final Pattern QUEUED_TICK = Pattern.compile("下一個 tick（tick (\\d+)）");
    private static final Pattern QUEUED_GOAL = Pattern.compile("依 collapse view 重新套用原艙門（([A-Z_]+)）");

    /** preparedoor 的 restart：開服時裝上的 PASSAGE_READY 提交暫緩（crash phase 的那一份隨 crash 結束）。 */
    private M5TestHooks.ReadyHold hold;
    /** waitingdoor 的 restart：重新套用排入時記下的「下一個 tick」。 */
    private int normalizeTick = -1;
    /** preparedoor 的 restart：非參與者（不在收據裡，開服時才連線）。 */
    private ServerPlayerEntity visitor;

    M5CollapseDoorChains(M5CollapseRun run) {
        super(run);
    }

    // ---- crash phase ----

    /**
     * {@code waitingdoor} 與 {@code preparedoor} 共用的 crash（窗口表的 {@code READY_HELD}）：fresh 世界蓋原艙、一位參與者在艙外 → 原艙註冊 → DORMANT 的 NEW 收據、原生存檔，
     * 裝上 PASSAGE_READY 的提交暫緩（{@code READY_HOLD}）與 crash 點 → 參與者由艙外進入 → COLLAPSING → RESOLVE → PREPARE → 準備完成之後第一次被暫緩、門是 sealed 量子膜時 halt
     * （halt 之前的取證與原生存檔在窗口表）。
     */
    Phase readyHeldCrash() {
        return Phase.halt(List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(1); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("receipt", 1, () -> {
                    fixture.writeCrashReceipt(fixture.dormantNewReceipt());
                    run.install(M5TestHooks.READY_HOLD, new M5TestHooks.ReadyHold(fixture.sid()));
                    M5CollapseWindows.arm(run, fixture, CrashWindow.READY_HELD);
                    return true;
                }),
                enter(),
                new Step("committed", 40, fixture::committed),
                new Step("halt", 600, () -> false)))
                .started(fixture::requireFreshWorld);
    }

    // ---- waitingdoor（F6-2-08）----

    /**
     * {@code waitingdoor} 的 restart：開服時窗口表的斷言（record 與 halt 當下相同、COMPLETE、磁碟上是 sealed 量子膜、參與者的 playerdata 在艙內）、原艙附近同步載入，
     * 門在記憶體裡也是 sealed、沒有人在線上（{@link #sealedWithNobodyOnline}）。之後：
     * <ol>
     * <li>{@code normalizeQueued}：重啟之後第一次載入的那個 tick 排入一則 NORMALIZE（原因「重啟之後第一次載入」），那個 tick 門仍是 sealed（{@code notWrittenWhenObserved}）。</li>
     * <li>{@code normalized}：排入時記下的 tick 結束時門是一般開門，「已依 collapse view 重新套用（NORMALIZE：APPLIED，tick N）」一則（{@code appliedOnTheQueuedTick}）。</li>
     * <li>{@code waitingWithThePlainDoor}：{@link M5CollapsePassage#WATCH_TICKS} 個 tick，每個 tick 一般開門、沒有「進入 PREPARE」、record 是 COLLAPSING、排入與套用各一則、
     *     沒有人在線上（{@code plainOpenWhileWaiting}）；之後 RESOLVE 已完成恰一則（{@code resolvedWhileWaiting}：閘門評估過、沒有成立，所以是 WAITING）。</li>
     * <li>{@code join}：參與者 JOIN（crash 前存的位置在艙內），同一個 tick 移到艙外（下一個 tick 塌縮流程觀察到的是艙外，JOIN 本身不是邊緣）。</li>
     * <li>{@link M5CollapsePassage#steps}：RESOLVE 完成的斷言之後參與者由艙外進入（邊緣）→ PREPARE（REPAIR 0 格）→ PASSAGE_READY → 跨越 → (b) 終局。</li>
     * </ol>
     */
    Phase waitingDoorRestart(String crashPhase) {
        var steps = new ArrayList<Step>();
        steps.add(M5CollapsePassage.counting("normalizeQueued", QUEUE_WAIT_TICKS, this::normalizeQueued));
        steps.add(M5CollapsePassage.counting("normalized", APPLY_WAIT_TICKS, this::normalizedOnTheQueuedTick));
        steps.add(M5CollapsePassage.watch("waitingWithThePlainDoor", M5CollapsePassage.WATCH_TICKS, this::plainOpenWhileWaiting, this::resolvedWhileWaiting));
        steps.add(new Step("join", 1, this::joinThenStepOutside));
        steps.addAll(passage.steps(M5CollapseWindows.of(CrashWindow.READY_HELD).prepared()));
        return restartPhase(crashPhase, CrashWindow.READY_HELD, ChamberAtStart.PRELOADED, steps, this::sealedWithNobodyOnline);
    }

    /** waitingdoor 的開服（{@link #restartPhase} 的 {@code afterStarted}）：原艙門在記憶體裡是 sealed 量子膜（由磁碟載入），沒有人在線上、還沒有 tick。 */
    private void sealedWithNobodyOnline() {
        run.expect("sealedDoorAtStart", M5GameTestSupport.doorSealed(run.source(), FRAME), () -> "開服時原艙門不是 sealed 量子膜：" + doorCenter());
        run.expect("nobodyOnlineAtStart", fixture.noneOnline() && run.ticks() == 0, () -> "開服時 tick=" + run.ticks() + " 有人在線上=" + !fixture.noneOnline());
    }

    /** step {@code normalizeQueued}（{@code tick} 是這個 step 的第幾個 tick，由 1 起）。 */
    private boolean normalizeQueued(int tick) {
        var queued = reapplyQueued(run, fixture.sid());
        if (queued.isEmpty() && tick < QUEUE_WAIT_TICKS) return false;
        boolean sealed = M5GameTestSupport.doorSealed(run.source(), FRAME);
        run.expect("normalizeQueuedOnTheFirstLoad", queued.size() == 1 && queued.getFirst().normalizeAfterARestart(),
                () -> "開服之後 " + run.ticks() + " 個 tick，排入的 INFO：" + queued);
        run.expect("notWrittenWhenObserved", sealed, () -> "排入的那個 tick 原艙門已不是 sealed 量子膜：" + doorCenter());
        normalizeTick = queued.getFirst().tick();
        run.proof.put("normalizeQueuedAtServerTick", run.server().getTicks());
        run.proof.put("normalizeTick", normalizeTick);
        return true;
    }

    /** step {@code normalized}：排入時記下的 tick（N）結束時，門是一般開門、套用的 INFO 恰一則而且記的是 tick N；之後 2 個 tick 仍不是一般開門就由這個具名斷言判 FAIL。 */
    private boolean normalizedOnTheQueuedTick(int tick) {
        boolean plain = plainOpen();
        int now = run.server().getTicks();
        if (!plain && now < normalizeTick + 2) return false;
        var applied = reapplied(run, fixture.sid());
        run.expect("appliedOnTheQueuedTick", plain && now == normalizeTick && applied.size() == 1 && applied.getFirst().contains("（NORMALIZE：APPLIED，tick " + normalizeTick + "）"),
                () -> "原艙門在 tick " + now + "（排入的是 tick " + normalizeTick + "）：一般開門=" + plain + " 門正中=" + doorCenter() + " 套用的 INFO=" + applied);
        run.proof.put("normalizedAtServerTick", now);
        return true;
    }

    /** step {@code waitingWithThePlainDoor} 的每個 tick（{@code tick} 由 1 起）。 */
    private void plainOpenWhileWaiting(int tick) {
        var sid = fixture.sid();
        boolean plain = plainOpen();
        var record = run.journal().flushedRecords().get(sid);
        int prepares = prepares(), queued = reapplyQueued(run, sid).size(), applied = reapplied(run, sid).size();
        run.expect("plainOpenWhileWaiting", plain && prepares == 0 && collapsing(record) && queued == 1 && applied == 1 && fixture.noneOnline(),
                () -> "套用之後第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter() + "「進入 PREPARE」=" + prepares + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + " 排入=" + queued + " 套用=" + applied + " 有人在線上=" + !fixture.noneOnline());
    }

    /** {@code waitingWithThePlainDoor} 觀察完之後：RESOLVE 完成恰一則（閘門已到評估的階段、沒有成立，所以這段期間是 WAITING）。 */
    private void resolvedWhileWaiting() {
        int resolved = run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", fixture.sid().toString()).size();
        run.expect("resolvedWhileWaiting", resolved == 1, () -> "「目的地已確定」=" + resolved);
    }

    /** step {@code join}：參與者 JOIN 在艙內（crash 前存的位置），同一個 tick 移到艙外（之後由 {@code passage.resolve} 由艙外進入，那才是邊緣）。 */
    private boolean joinThenStepOutside() {
        fixture.joinAllInTheSourceWorld(Inside.INSIDE);
        fixture.moveOutside();
        run.proof.put("joinedAtPhaseTick", run.ticks());
        return true;
    }

    // ---- preparedoor（F6-2-10）----

    /**
     * {@code preparedoor} 的 restart：開服時窗口表的斷言同 {@link #waitingDoorRestart}；第一個 tick 之前裝上 {@code READY_HOLD}、參與者 JOIN 在艙內、非參與者 JOIN 並移到艙內
     * （{@link #bothInsideBeforeTheFirstTick}）。之後：
     * <ol>
     * <li>{@code preparedOnTheLevel}：閘門以位準評估進 PREPARE（「進入 PREPARE」恰一則，參與者沒有移動：{@code enteredPrepareOnTheLevel}）；那個 tick 門已是一般開門
     *     （{@code plainOpenWhileTheVisitorIsInside}）、「艙內有非參與者玩家，原艙門維持一般開門」一則、NORMALIZE 排入一則而沒有套用的 INFO、RESOLVE 完成恰一則
     *     （{@code preparedOnTheLevelWithTheVisitorInside}）。</li>
     * <li>{@code heldWithTheVisitorInside}：每個 tick 一般開門，直到準備完成、PASSAGE_READY 的提交第一次被暫緩（{@code readyHeldWithTheVisitorInside}：「準備完成」恰一則）。</li>
     * <li>{@code visitorStillInside}：再 {@value #VISITOR_WATCH_TICKS} 個 tick，每個 tick 一般開門；之後非參與者移到艙外。</li>
     * <li>{@code resealed}：下一個 tick 原艙門是 sealed 量子膜、「套用 sealed 量子膜」恰一則（{@code resealedOnTheNextTick}）；之後釋放暫緩。</li>
     * <li>{@link M5CollapsePassage#ready}（REPAIR 0 格）→ 跨越 → (b) 終局。</li>
     * </ol>
     */
    Phase prepareDoorRestart(String crashPhase) {
        var steps = new ArrayList<Step>();
        steps.add(M5CollapsePassage.counting("preparedOnTheLevel", PREPARE_WAIT_TICKS, this::preparedWithTheVisitorInside));
        steps.add(M5CollapsePassage.counting("heldWithTheVisitorInside", HELD_WAIT_TICKS, this::heldWithTheVisitorInside));
        steps.add(M5CollapsePassage.watch("visitorStillInside", VISITOR_WATCH_TICKS, this::plainOpenWhileTheVisitorIsInside, this::visitorLeaves));
        steps.add(M5CollapsePassage.counting("resealed", 1, this::resealedOnTheNextTick));
        steps.add(passage.ready(M5CollapseWindows.of(CrashWindow.READY_HELD).prepared(), M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> End.crossed(fixture)));
        return restartPhase(crashPhase, CrashWindow.READY_HELD, ChamberAtStart.PRELOADED, steps, this::bothInsideBeforeTheFirstTick);
    }

    /**
     * preparedoor 的開服（{@link #restartPhase} 的 {@code afterStarted}，第一個 tick 之前）：裝上 PASSAGE_READY 的提交暫緩；參與者 JOIN 在艙內（crash 前存的位置）、記下位置；
     * 非參與者（新的 UUID，不在收據裡）JOIN 之後移到艙內、與參與者錯開。這時門在記憶體裡是 sealed（由磁碟載入）。
     */
    private void bothInsideBeforeTheFirstTick() {
        hold = run.install(M5TestHooks.READY_HOLD, new M5TestHooks.ReadyHold(fixture.sid()));
        fixture.joinAllInTheSourceWorld(Inside.INSIDE);
        joinedAt = fixture.players().getFirst().getPos();
        visitor = fixture.connect(UUID.randomUUID()).player();
        var spot = ChamberSpaceCoordinates.position(FRAME, VISITOR_X, 1, VISITOR_Z);
        visitor.teleport(run.source(), spot.x, spot.y, spot.z, Set.of(), 0, 0);
        boolean participant = fixture.players().stream().allMatch(M5CollapseFixture::inside), outsider = M5CollapseFixture.inside(visitor);
        run.expect("bothInsideBeforeTheFirstTick", run.ticks() == 0 && participant && outsider && !fixture.participants().contains(visitor.getUuid())
                        && visitor.getServerWorld() == run.source() && M5GameTestSupport.doorSealed(run.source(), FRAME),
                () -> "tick=" + run.ticks() + " 參與者在艙內=" + participant + " 非參與者在 " + visitor.getPos() + " 門正中=" + doorCenter());
        run.proof.put("joinedAt", joinedAt.toString());
        run.proof.put("visitor", visitor.getUuid().toString());
    }

    /** step {@code preparedOnTheLevel}（{@code tick} 由 1 起）：最多等 {@link #PREPARE_WAIT_TICKS} 個 tick 進 PREPARE，之後仍沒有就由 {@code enteredPrepareOnTheLevel} 判 FAIL。 */
    private boolean preparedWithTheVisitorInside(int tick) throws Exception {
        var sid = fixture.sid();
        int prepares = prepares();
        if (prepares == 0 && tick < PREPARE_WAIT_TICKS) return false;
        boolean still = notMovedSinceTheJoin();
        run.expect("enteredPrepareOnTheLevel", prepares == 1 && still, () -> "開服之後 " + run.ticks() + " 個 tick：「進入 PREPARE」=" + prepares + " 參與者沒有移動=" + still);
        // F6-2-10 的目標斷言（mutation：membraneTarget 不看 outsiderInside 時，進入 PREPARE 的這個 tick 就失敗在這裡）。
        plainOpenWhileTheVisitorIsInside(tick);
        var kept = run.capture().matching(Level.INFO, "艙內有非參與者玩家", "原艙門維持一般開門", sid.toString()).stream().map(LogCapture.Entry::message).toList();
        int queued = reapplyQueued(run, sid).size(), applied = reapplied(run, sid).size();
        int resolved = run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).size();
        run.expect("preparedOnTheLevelWithTheVisitorInside", kept.size() == 1 && queued == 1 && applied == 0 && resolved == 1,
                () -> "「維持一般開門」=" + kept + " 排入=" + queued + " 套用=" + applied + " RESOLVE 完成=" + resolved);
        passage.expectResolved();
        run.proof.put("plainDoorInfo", kept.getFirst());
        run.proof.put("preparedAtPhaseTick", run.ticks());
        return true;
    }

    /** step {@code heldWithTheVisitorInside}：每個 tick 一般開門；PASSAGE_READY 的提交第一次被暫緩時（準備完成之後）斷言「準備完成」恰一則、完成。 */
    private boolean heldWithTheVisitorInside(int tick) {
        plainOpenWhileTheVisitorIsInside(tick);
        if (hold.hits() == 0) return false;
        int prepared = run.capture().matching(Level.INFO, "準備完成（預載與投影都已完成）", fixture.sid().toString()).size();
        run.expect("readyHeldWithTheVisitorInside", prepared == 1, () -> "PASSAGE_READY 的提交被暫緩時「準備完成」=" + prepared);
        run.proof.put("readyHeldAtPhaseTick", run.ticks());
        return true;
    }

    /**
     * 非參與者在艙內時（F6-2-10 的目標斷言）：25 格門都是一般開門（{@code open=true}＋{@code membrane=none}）、非參與者在艙內、record 是 COLLAPSING、沒有退回 WAITING、
     * PASSAGE_READY 還沒有提交（被暫緩）。
     */
    private void plainOpenWhileTheVisitorIsInside(int tick) {
        var sid = fixture.sid();
        boolean plain = plainOpen(), inside = M5CollapseFixture.inside(visitor);
        var record = run.journal().flushedRecords().get(sid);
        int left = run.capture().matching(Level.INFO, "PREPARE 的閘門不再成立", sid.toString()).size();
        run.expect("plainOpenWhileTheVisitorIsInside", plain && inside && collapsing(record) && left == 0,
                () -> "第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter() + " 非參與者在艙內=" + inside + " state=" + M5GameTestSupport.state(run.journal(), sid)
                        + " 退回 WAITING=" + left);
    }

    /** {@code visitorStillInside} 觀察完之後：非參與者移到艙外（門外，不在門口那 25 格）。 */
    private void visitorLeaves() {
        M5GameTestSupport.moveOutside(visitor, FRAME);
        run.proof.put("visitorLeftAtServerTick", run.server().getTicks());
    }

    /** step {@code resealed}（只有 1 個 tick：非參與者離開之後的下一個 tick）：原艙門是 sealed 量子膜；之後釋放 PASSAGE_READY 的提交暫緩。 */
    private boolean resealedOnTheNextTick(int tick) {
        var sid = fixture.sid();
        boolean sealed = M5GameTestSupport.doorSealed(run.source(), FRAME), outside = !M5CollapseFixture.inside(visitor);
        var record = run.journal().flushedRecords().get(sid);
        int sealedInfo = run.capture().matching(Level.INFO, "PREPARE：原艙門套用 sealed 量子膜", sid.toString()).size();
        run.expect("resealedOnTheNextTick", sealed && outside && collapsing(record) && sealedInfo == 1,
                () -> "非參與者離開之後第 " + tick + " 個 tick：sealed=" + sealed + " 門正中=" + doorCenter() + " 非參與者在艙外=" + outside + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + "「套用 sealed 量子膜」=" + sealedInfo);
        hold.release();
        run.proof.put("readyReleasedAtServerTick", run.server().getTicks());
        return true;
    }

    // ---- 共用的查詢 ----

    /**
     * 重新套用排入（或延後）的 INFO（{@code OriginDoors.reapplyDoor}，「…下一個 tick（tick N）…」）：這個 session 的每一則，依發生順序。{@code goal} 是排入時的目標（延後時為 null），
     * {@code firstLoadAfterARestart} 是原因為「重啟之後第一次載入」。比照 GameTest 的 {@code reapplyQueuedTicks}（那裡是 private）。
     */
    record Queued(int tick, String goal, boolean firstLoadAfterARestart) {
        /** 重啟之後第一次載入時排入的 NORMALIZE（COLLAPSING 的重新套用目標）。 */
        boolean normalizeAfterARestart() { return "NORMALIZE".equals(goal) && firstLoadAfterARestart; }
    }

    static List<Queued> reapplyQueued(M5CollapseRun run, UUID sid) {
        var result = new ArrayList<Queued>();
        for (var entry : run.capture().matching(Level.INFO, "下一個 tick（tick ", "session=" + sid)) {
            var tick = QUEUED_TICK.matcher(entry.message());
            if (!tick.find()) continue;
            var goal = QUEUED_GOAL.matcher(entry.message());
            result.add(new Queued(Integer.parseInt(tick.group(1)), goal.find() ? goal.group(1) : null, entry.message().contains("來源艙重啟之後第一次載入")));
        }
        return result;
    }

    /** 重新套用完成的 INFO（{@code OriginDoors.applyDoor}，只在實際改了門時記：「原艙門已依 collapse view 重新套用（goal：APPLIED，tick N）」）：這個 session 的每一則。 */
    static List<String> reapplied(M5CollapseRun run, UUID sid) {
        return run.capture().matching(Level.INFO, "原艙門已依 collapse view 重新套用", "session=" + sid).stream().map(LogCapture.Entry::message).toList();
    }

    private boolean plainOpen() {
        return M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
    }

    private int prepares() {
        return run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
    }

    private static boolean collapsing(SessionRecoveryRecord record) {
        return record != null && record.state() == SessionState.COLLAPSING;
    }

    /** 原艙門正中那一格（區域 (3,3,0)）的方塊狀態（失敗訊息用）。 */
    private String doorCenter() {
        return run.source().getBlockState(local(3, 3, 0)).toString();
    }
}
