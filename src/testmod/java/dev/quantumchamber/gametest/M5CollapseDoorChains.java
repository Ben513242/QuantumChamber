package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseDoors.APPLY_WAIT_TICKS;
import static dev.quantumchamber.gametest.M5CollapseDoors.QUEUE_WAIT_TICKS;
import static dev.quantumchamber.gametest.M5CollapseDoors.doorCenter;
import static dev.quantumchamber.gametest.M5CollapseDoors.plainOpen;
import static dev.quantumchamber.gametest.M5CollapseDoors.reapplied;
import static dev.quantumchamber.gametest.M5CollapseDoors.reapplyQueued;
import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.collapse.OriginDoorsTestAccess;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapsePassage.End;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5CollapseWindows.OriginDoorState;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseKeys;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.block.Blocks;
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
 * 「本來關著的門保留關著」（F6-2-08 的另一半）不在這兩條：開服時門是關著的是 RESOLVE 期間的窗口（W2、W3、W5），斷言以 {@link M5CollapseDoors#closedDoorKeptByTheReapply} 加在
 * {@link M5CollapseDormantChains#restart}。spec §12 的四件事每個 tick 由 {@link M5CollapsePassage#invariants} 斷言（{@link #restartPhase}），終局由 {@link M5CollapsePassage#expectEnd}。
 * <p>F6-2d-2 B2：SEALED 的原艙門跨重啟（spec v5.10 §7.3「門狀態的重新套用」「門口有人時的規則」、§11.2、§12 的 SEALED 列；交接清單 F6-2-09、F6-2-11）。crash 在進入 SEALED 的寫入已確認、
 * {@code sealCommitted} 排入關門之前（窗口表的 {@code UNREADABLE_SEALED}、{@code UNREADABLE_SEALED_DOORWAY}、{@code PROJECTION_SEALED}），restart 由「排入 → 套用」兩個 step 斷言重新套用的關門
 * （{@link #closeQueued}、{@link #closeApplied}），之後參與者由艙外進入、重試，續行到 (b) 終局：
 * <ul>
 * <li>{@code sealclose}：開服時門是參與者照一般方式開的一般開門 → 關上一次 → 參與者照一般方式打開之後不再被關回去（{@link #plainDoorSealedRestart}）。</li>
 * <li>{@code sealdoorway}：開服時門關著、門口有盔甲座 → 改為一般開門、不關在盔甲座上（{@link #doorwaySealedRestart}）。</li>
 * <li>{@code sealmembrane}（F6-2-11 變體 (i)，窗口表的 {@code PROJECTION_SEALED}）：開服時門是 sealed 量子膜、沒有參與者 → 非參與者使用門時不取消關門 → 關上、量子膜清掉 → 參與者 JOIN
 *     的邊緣觸發重試（{@link #membraneSealedRestart}）。</li>
 * <li>{@code sealmembranejoined}（F6-2-11 變體 (ii)，H9 (a) 的跨 JVM 版本，同一列）：參與者開服時就在艙內 → 第一次觀察同時排入關門與重試 → 下一個 tick 關門由 NORMALIZE 取代、改為一般開門，
 *     門一直沒有關上 → 斷電讓閘門保持不成立 → 恢復供電、參與者離開再進入（{@link #membraneSealedJoinedRestart}）。兩個變體是兩條 chain 共用一列（restart 讀 crash 的 root，一條 chain
 *     的第二個 restart 會接在第一個改過的世界上）。</li>
 * </ul>
 * <p>放在新的類別（不併入 {@link M5CollapseRetryChains}）：窗口條件與門的斷言共用，F6-2d-2 B2 的 SEALED 門窗口也放這裡；restart builder 與觸發之後的等法由基底
 * {@link M5CollapseRestartChains} 提供，不另寫一份。重新套用的 INFO 與門的狀態的查詢在 {@link M5CollapseDoors}（F6-2d-2 B2a 由這裡以純搬移抽出）。F6-2e Step 0（F6-2d-2 B2
 * quality Minor 2）：crash phase 的骨架（{@code crash}）與 discovery 的讀取失敗（{@code discoveryReadFails}）移到基底，這裡的 crash 都以 {@code dormantNewSetup()} 呼叫它。
 */
final class M5CollapseDoorChains extends M5CollapseRestartChains {
    /** preparedoor：PREPARE 準備完成、PASSAGE_READY 的提交第一次被暫緩之前最多等幾個 tick（預載、REPAIR 與 checkpoint；比照 {@code passage.ready} 的上限）。 */
    private static final int HELD_WAIT_TICKS = 600;
    /** preparedoor：PASSAGE_READY 的提交被暫緩之後，非參與者再留在艙內幾個 tick（每個 tick 都斷言一般開門）。 */
    private static final int VISITOR_WATCH_TICKS = 20;
    /** preparedoor：非參與者在艙內的位置（區域座標；參與者在正中 (3.5, 1, 3.5)，錯開、不碰到門口那一排）。 */
    private static final double VISITOR_X = 1.5, VISITOR_Z = 4.5;
    /**
     * SEALED 的門 chain：排入的關門（CLOSE_ON_SEALED）最多等幾個 tick 套用（F6-2d-2 B2；關門要等門口的 entity storage 由磁碟非同步載入，所以比 NORMALIZE 的
     * {@link M5CollapseDoors#APPLY_WAIT_TICKS} 長；到時仍沒有改門就由具名斷言判 FAIL，不是 stepBounded）。
     */
    private static final int CLOSE_APPLY_WAIT_TICKS = 20;

    /**
     * waitingdoor 的 restart 才用的狀態（F6-2d-2 B2，B1 quality 的建議：只對一條 chain 有效的欄位依 chain 分組；原本是這個類別的欄位 {@code normalizeTick}）。一個 phase 一個
     * {@link M5CollapseDoorChains}，所以每個 phase 各有一份。
     */
    private static final class WaitingDoor {
        /** 重新套用排入時記下的「下一個 tick」。 */
        int normalizeTick = -1;
    }

    /** preparedoor 的 restart 才用的狀態（F6-2d-2 B2，同 {@link WaitingDoor}；原本是這個類別的欄位 {@code hold}、{@code visitor}）。 */
    private static final class PrepareDoor {
        /** 開服時裝上的 PASSAGE_READY 提交暫緩（crash phase 的那一份隨 crash 結束）。 */
        M5TestHooks.ReadyHold hold;
        /** 非參與者（不在收據裡，開服時才連線）。 */
        ServerPlayerEntity visitor;
    }

    /**
     * SEALED 的門 chain（F6-2d-2 B2：{@code sealclose}、{@code sealdoorway}、{@code sealmembrane}、{@code sealmembranejoined}）的 restart 才用的狀態（同 {@link WaitingDoor}）。
     */
    private static final class SealedDoor {
        /** 重新套用的關門（CLOSE_ON_SEALED）排入時記下的「下一個 tick」（N）。 */
        int queuedTick = -1;
        /** probe 第一次看到門口有 entity 的 server tick（{@code sealdoorway} 的盔甲座由磁碟非同步載入；-1：還沒有看到）。只當 proof（F6-2e Step 0 起不參與斷言）。 */
        int doorwaySeenAt = -1;
        /** {@code sealmembrane}：非參與者（不在收據裡，開服時才連線，在艙外）。 */
        ServerPlayerEntity visitor;
    }

    private final WaitingDoor waiting = new WaitingDoor();
    private final PrepareDoor prepare = new PrepareDoor();
    private final SealedDoor sealedDoor = new SealedDoor();

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
        return crash(dormantNewSetup(), List.of(), () -> run.install(M5TestHooks.READY_HOLD, new M5TestHooks.ReadyHold(fixture.sid())), CrashWindow.READY_HELD, 600);
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
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（READY_HELD 是 SEALED_MEMBRANE）。
        var id = CrashWindow.READY_HELD;
        var door = doorAtStart(id);
        var steps = new ArrayList<Step>();
        steps.add(M5CollapsePassage.awaiting("normalizeQueued", QUEUE_WAIT_TICKS, () -> !reapplyQueued(run, fixture.sid()).isEmpty(),
                (tick, happened) -> normalizeQueued(door, tick, happened)));
        steps.add(M5CollapsePassage.awaiting("normalized", APPLY_WAIT_TICKS, () -> plainOpen(run), this::normalizedOnTheQueuedTick));
        steps.add(M5CollapsePassage.watch("waitingWithThePlainDoor", M5CollapsePassage.WATCH_TICKS, this::plainOpenWhileWaiting, this::resolvedWhileWaiting));
        steps.add(new Step("join", 1, this::joinThenStepOutside));
        steps.addAll(passage.steps(M5CollapseWindows.of(id).prepared()));
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> sealedWithNobodyOnline(door));
    }

    /**
     * waitingdoor 的開服（{@link #restartPhase} 的 {@code afterStarted}）：原艙門在記憶體裡是 {@code door}（窗口列的 sealed 量子膜，由磁碟載入），沒有人在線上、還沒有 tick。
     * F6-2e Step 0：門由窗口列讀（原本寫死 {@code doorSealed}），名稱不變。
     */
    private void sealedWithNobodyOnline(OriginDoorState door) {
        run.expect("sealedDoorAtStart", M5CollapseDoors.is(run, door), () -> "開服時原艙門不是 " + door + "：" + doorCenter(run));
        run.expect("nobodyOnlineAtStart", fixture.noneOnline() && run.ticks() == 0, () -> "開服時 tick=" + run.ticks() + " 有人在線上=" + !fixture.noneOnline());
    }

    /**
     * step {@code normalizeQueued}（{@link M5CollapsePassage#awaiting}：排入的 INFO 出現、或等滿 {@link M5CollapseDoors#QUEUE_WAIT_TICKS} 個 tick 時；{@code tick} 是這個 step 的第幾個 tick，
     * 由 1 起）。到時沒有排入時由 {@code normalizeQueuedOnTheFirstLoad} 判 FAIL（F6-2d-2 B2a 之前是 {@code counting} 加手寫的等待，等待時間與通過條件相同）。F6-2e Step 0：
     * 那個 tick 門仍是開服時的 {@code door}（窗口列，原本寫死 {@code doorSealed}；{@code notWrittenWhenObserved}，名稱不變）。
     */
    private void normalizeQueued(OriginDoorState door, int tick, boolean happened) {
        var queued = reapplyQueued(run, fixture.sid());
        boolean sealed = M5CollapseDoors.is(run, door);
        run.expect("normalizeQueuedOnTheFirstLoad", queued.size() == 1 && queued.getFirst().normalizeAfterARestart(),
                () -> "開服之後 " + run.ticks() + " 個 tick，排入的 INFO：" + queued);
        run.expect("notWrittenWhenObserved", sealed, () -> "排入的那個 tick 原艙門已不是 " + door + "：" + doorCenter(run));
        waiting.normalizeTick = queued.getFirst().tick();
        run.proof.put("normalizeQueuedAtServerTick", run.server().getTicks());
        run.proof.put("normalizeTick", waiting.normalizeTick);
    }

    /**
     * step {@code normalized}（{@link M5CollapsePassage#awaiting}，最多 {@link M5CollapseDoors#APPLY_WAIT_TICKS} 個 tick，{@code plain} 是那個 tick 門是否已是一般開門）：排入時記下的
     * tick（N）結束時，門是一般開門、套用的 INFO 恰一則而且記的是 tick N；之後 2 個 tick 仍不是一般開門就由這個具名斷言判 FAIL。F6-2e Step 0（F6-2d-2 B2 quality Minor 3）：
     * 之前是 {@code counting} 加手寫的 {@code !plain && now < N + 2}（上限寫兩次）；step 的第 1 個 tick 是 tick N（{@code normalizeQueued} 在 N-1 完成），所以「再等」的條件
     * {@code tick < 3} 與之前相同，通過條件不變。
     */
    private void normalizedOnTheQueuedTick(int tick, boolean plain) {
        int now = run.server().getTicks(), normalizeTick = waiting.normalizeTick;
        var applied = reapplied(run, fixture.sid());
        run.expect("appliedOnTheQueuedTick", plain && now == normalizeTick && applied.size() == 1 && applied.getFirst().contains("（" + OriginDoorsTestAccess.NORMALIZE + "：APPLIED，tick " + normalizeTick + "）"),
                () -> "原艙門在 tick " + now + "（排入的是 tick " + normalizeTick + "）：一般開門=" + plain + " 門正中=" + doorCenter(run) + " 套用的 INFO=" + applied);
        run.proof.put("normalizedAtServerTick", now);
    }

    /** step {@code waitingWithThePlainDoor} 的每個 tick（{@code tick} 由 1 起）。 */
    private void plainOpenWhileWaiting(int tick) {
        var sid = fixture.sid();
        boolean plain = plainOpen(run);
        var record = run.journal().flushedRecords().get(sid);
        int prepares = prepares(), queued = reapplyQueued(run, sid).size(), applied = reapplied(run, sid).size();
        run.expect("plainOpenWhileWaiting", plain && prepares == 0 && collapsing(record) && queued == 1 && applied == 1 && fixture.noneOnline(),
                () -> "套用之後第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter(run) + "「進入 PREPARE」=" + prepares + " state="
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
     *     （{@code preparedOnTheLevelWithTheVisitorInside}）；進入 PREPARE 的 server tick 不晚於 NORMALIZE 排入時記下的 tick（{@code preparedBeforeTheQueuedNormalize}，這條 chain
     *     的前提，見 {@link #preparedWithTheVisitorInside}）。</li>
     * <li>{@code heldWithTheVisitorInside}：每個 tick 一般開門，直到準備完成、PASSAGE_READY 的提交第一次被暫緩（{@code readyHeldWithTheVisitorInside}：「準備完成」恰一則）。</li>
     * <li>{@code visitorStillInside}：再 {@value #VISITOR_WATCH_TICKS} 個 tick，每個 tick 一般開門；之後非參與者移到艙外。</li>
     * <li>{@code resealed}：下一個 tick 原艙門是 sealed 量子膜、「套用 sealed 量子膜」恰一則（{@code resealedOnTheNextTick}）；之後釋放暫緩。</li>
     * <li>{@link M5CollapsePassage#ready}（REPAIR 0 格）→ 跨越 → (b) 終局。</li>
     * </ol>
     */
    Phase prepareDoorRestart(String crashPhase) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（READY_HELD 是 SEALED_MEMBRANE）。
        var id = CrashWindow.READY_HELD;
        var door = doorAtStart(id);
        var steps = new ArrayList<Step>();
        steps.add(M5CollapsePassage.awaiting("preparedOnTheLevel", PREPARE_WAIT_TICKS, () -> prepares() > 0, this::preparedWithTheVisitorInside));
        steps.add(M5CollapsePassage.counting("heldWithTheVisitorInside", HELD_WAIT_TICKS, this::heldWithTheVisitorInside));
        steps.add(M5CollapsePassage.watch("visitorStillInside", VISITOR_WATCH_TICKS, this::plainOpenWhileTheVisitorIsInside, this::visitorLeaves));
        steps.add(M5CollapsePassage.counting("resealed", 1, this::resealedOnTheNextTick));
        steps.add(passage.ready(M5CollapseWindows.of(id).prepared(), M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> End.crossed(fixture)));
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> bothInsideBeforeTheFirstTick(door));
    }

    /**
     * preparedoor 的開服（{@link #restartPhase} 的 {@code afterStarted}，第一個 tick 之前）：裝上 PASSAGE_READY 的提交暫緩；參與者 JOIN 在艙內（crash 前存的位置）、記下位置；
     * 非參與者（新的 UUID，不在收據裡）JOIN 之後移到艙內、與參與者錯開。這時門在記憶體裡是 {@code door}（窗口列的 sealed 量子膜，由磁碟載入；F6-2e Step 0 起由窗口列讀，原本寫死
     * {@code doorSealed}）。
     */
    private void bothInsideBeforeTheFirstTick(OriginDoorState door) {
        prepare.hold = run.install(M5TestHooks.READY_HOLD, new M5TestHooks.ReadyHold(fixture.sid()));
        joinInside();
        var visitor = prepare.visitor = fixture.connect(UUID.randomUUID()).player();
        var spot = ChamberSpaceCoordinates.position(FRAME, VISITOR_X, 1, VISITOR_Z);
        visitor.teleport(run.source(), spot.x, spot.y, spot.z, Set.of(), 0, 0);
        boolean participant = fixture.players().stream().allMatch(M5CollapseFixture::inside), outsider = M5CollapseFixture.inside(visitor);
        run.expect("bothInsideBeforeTheFirstTick", run.ticks() == 0 && participant && outsider && !fixture.participants().contains(visitor.getUuid())
                        && visitor.getServerWorld() == run.source() && M5CollapseDoors.is(run, door),
                () -> "tick=" + run.ticks() + " 參與者在艙內=" + participant + " 非參與者在 " + visitor.getPos() + " 門正中=" + doorCenter(run));
        run.proof.put("joinedAt", joinedAt.toString());
        run.proof.put("visitor", visitor.getUuid().toString());
    }

    /**
     * step {@code preparedOnTheLevel}（{@link M5CollapsePassage#awaiting}，{@code tick} 由 1 起）：最多等 {@link #PREPARE_WAIT_TICKS} 個 tick 進 PREPARE，之後仍沒有就由
     * {@code enteredPrepareOnTheLevel} 判 FAIL（F6-2d-2 B2a 之前是 {@code counting} 加手寫的等待，等待時間與通過條件相同）。
     * <p>F6-2d-2 B2a（B1 quality Minor 3）：{@code preparedBeforeTheQueuedNormalize} 斷言這條 chain 的前提：進入 PREPARE 的 server tick ≤ 重新套用的 NORMALIZE 排入時記下的
     * tick N（B1 的實測兩者都是 2）。同一個 server tick 裡塌縮流程的 session 推進（{@code prepareDoor}）在待套用的門（{@code applyPending}）之前，PREPARE 在 tick N 開始時，
     * {@code prepareDoor} 先依「套用量子膜的條件」選門（艙內有非參與者 → 一般開門，「維持一般開門」的 INFO）並移除還在等的 NORMALIZE，所以那一刻的一般開門是 F6-2-10 的判斷。
     * PREPARE 晚到 N+1 以後時，tick N 的 NORMALIZE 已先把 sealed 的門改為一般開門，{@code prepareDoor} 是 UNCHANGED、不記 INFO，一般開門就不能證明 PREPARE 的判斷。所以是
     * 「≤ N」，不是派工字面的「早於排入的 tick」（排入的 INFO 本身在 tick N-1，PREPARE 在 tick N）。失敗訊息附兩個 tick。
     */
    private void preparedWithTheVisitorInside(int tick, boolean happened) throws Exception {
        var sid = fixture.sid();
        int prepares = prepares();
        boolean still = notMovedSinceTheJoin();
        run.expect("enteredPrepareOnTheLevel", prepares == 1 && still, () -> "開服之後 " + run.ticks() + " 個 tick：「進入 PREPARE」=" + prepares + " 參與者沒有移動=" + still);
        // F6-2-10 的目標斷言（mutation：membraneTarget 不看 outsiderInside 時，進入 PREPARE 的這個 tick 就失敗在這裡）。
        plainOpenWhileTheVisitorIsInside(tick);
        var kept = run.capture().matching(Level.INFO, "艙內有非參與者玩家", "原艙門維持一般開門", sid.toString()).stream().map(LogCapture.Entry::message).toList();
        var queuedInfo = reapplyQueued(run, sid);
        int queued = queuedInfo.size(), applied = reapplied(run, sid).size();
        int resolved = run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).size();
        run.expect("preparedOnTheLevelWithTheVisitorInside", kept.size() == 1 && queued == 1 && applied == 0 && resolved == 1,
                () -> "「維持一般開門」=" + kept + " 排入=" + queued + " 套用=" + applied + " RESOLVE 完成=" + resolved);
        // F6-2d-2 B2a（B1 quality Minor 3）：這條 chain 的前提（見 Javadoc）：PREPARE 在 NORMALIZE 套用之前（同一個 tick 也算，prepareDoor 在 applyPending 之前）。
        int preparedAt = run.server().getTicks(), normalizeAt = queuedInfo.getFirst().tick();
        run.expect("preparedBeforeTheQueuedNormalize", preparedAt <= normalizeAt,
                () -> "進入 PREPARE 的 server tick " + preparedAt + " 晚於 NORMALIZE 排入時記下的 tick " + normalizeAt);
        run.proof.put("preparedAtServerTick", preparedAt);
        run.proof.put("normalizeTick", normalizeAt);
        passage.expectResolved();
        run.proof.put("plainDoorInfo", kept.getFirst());
        run.proof.put("preparedAtPhaseTick", run.ticks());
    }

    /** step {@code heldWithTheVisitorInside}：每個 tick 一般開門；PASSAGE_READY 的提交第一次被暫緩時（準備完成之後）斷言「準備完成」恰一則、完成。 */
    private boolean heldWithTheVisitorInside(int tick) {
        plainOpenWhileTheVisitorIsInside(tick);
        if (prepare.hold.hits() == 0) return false;
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
        boolean plain = plainOpen(run), inside = M5CollapseFixture.inside(prepare.visitor);
        var record = run.journal().flushedRecords().get(sid);
        int left = run.capture().matching(Level.INFO, "PREPARE 的閘門不再成立", sid.toString()).size();
        run.expect("plainOpenWhileTheVisitorIsInside", plain && inside && collapsing(record) && left == 0,
                () -> "第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter(run) + " 非參與者在艙內=" + inside + " state=" + M5GameTestSupport.state(run.journal(), sid)
                        + " 退回 WAITING=" + left);
    }

    /** {@code visitorStillInside} 觀察完之後：非參與者移到艙外（門外，不在門口那 25 格）。 */
    private void visitorLeaves() {
        M5GameTestSupport.moveOutside(prepare.visitor, FRAME);
        run.proof.put("visitorLeftAtServerTick", run.server().getTicks());
    }

    /** step {@code resealed}（只有 1 個 tick：非參與者離開之後的下一個 tick）：原艙門是 sealed 量子膜；之後釋放 PASSAGE_READY 的提交暫緩。 */
    private boolean resealedOnTheNextTick(int tick) {
        var sid = fixture.sid();
        boolean sealed = M5CollapseDoors.is(run, OriginDoorState.SEALED_MEMBRANE), outside = !M5CollapseFixture.inside(prepare.visitor);
        var record = run.journal().flushedRecords().get(sid);
        int sealedInfo = run.capture().matching(Level.INFO, "PREPARE：原艙門套用 sealed 量子膜", sid.toString()).size();
        run.expect("resealedOnTheNextTick", sealed && outside && collapsing(record) && sealedInfo == 1,
                () -> "非參與者離開之後第 " + tick + " 個 tick：sealed=" + sealed + " 門正中=" + doorCenter(run) + " 非參與者在艙外=" + outside + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + "「套用 sealed 量子膜」=" + sealedInfo);
        prepare.hold.release();
        run.proof.put("readyReleasedAtServerTick", run.server().getTicks());
        return true;
    }

    // ---- sealclose、sealdoorway（F6-2-09）----

    /**
     * {@code sealclose} 的 crash（窗口表的 {@code UNREADABLE_SEALED}）：原艙註冊之後、收據之前，參與者在艙外照一般方式把門打開（IDLE，照一般門；{@code openedBeforeTheReceipt}），收據的
     * 原生存檔把開著的門存到磁碟 → 裝上 discovery 讀取的 IO 失敗（{@code CHECKED_READ_FAULT}，一次；收據的 candidate context 也讀 discovery，所以排在收據之後，同 F6-2-07）→ 參與者由
     * 艙外進入 → COLLAPSING → RESOLVE 第 4 步讀 discovery 失敗 → SEALED(AUTHORITY_UNREADABLE) 已確認、{@code sealCommitted} 的關門之前 halt（halt 之前不存檔）。
     */
    Phase plainDoorSealedCrash() {
        return crash(dormantNewSetup(), List.of(new Step("opened", 1, this::openedBeforeTheReceipt)), this::discoveryReadFails, CrashWindow.UNREADABLE_SEALED, 400);
    }

    /**
     * {@code sealdoorway} 的 crash（窗口表的 {@code UNREADABLE_SEALED_DOORWAY}）：同 {@link #plainDoorSealedCrash}，只是門維持關著，收據之前在門口 local (3,1,0) 放一座不受重力的盔甲座
     * （{@code standInTheDoorwayBeforeTheReceipt}；收據的原生存檔把它存進 entity storage）。
     */
    Phase doorwaySealedCrash() {
        return crash(dormantNewSetup(), List.of(new Step("stand", 1, this::standInTheDoorwayBeforeTheReceipt)), this::discoveryReadFails, CrashWindow.UNREADABLE_SEALED_DOORWAY,
                400);
    }

    /** sealclose 的 crash：參與者（艙外）照一般方式開門，這時原艙是 IDLE（沒有 session），門照一般門切換為一般開門。 */
    private boolean openedBeforeTheReceipt() {
        var result = M5GameTestSupport.interact(run.source(), M5CollapseFixture.local(3, 3, 0), fixture.players().getFirst());
        boolean plain = plainOpen(run);
        run.expect("openedBeforeTheReceipt", result.isAccepted() && plain, () -> "參與者照一般方式開門：" + result + " 門正中=" + doorCenter(run));
        return true;
    }

    /**
     * sealdoorway 的 crash：門口放一座盔甲座，門仍關著。F6-2e-1（F6-2e Step 0 review 的測試缺陷）：另要求門口恰有一座盔甲座（{@link M5CollapseDoors#doorwayStands}，只數 probe 自己
     * 放的）；production 的門口判斷（{@code doorwayOccupied}）仍要成立，那是 crash 窗口的前提。名稱不變、條件變嚴。
     */
    private boolean standInTheDoorwayBeforeTheReceipt() {
        M5CollapseDoors.spawnDoorwayStand(run);
        int stands = M5CollapseDoors.doorwayStands(run).size();
        boolean occupied = OriginDoorsTestAccess.doorwayOccupied(run.source(), FRAME), closed = M5CollapseDoors.closed(run);
        run.expect("standInTheDoorwayBeforeTheReceipt", stands == 1 && occupied && closed,
                () -> "門口的盔甲座=" + stands + " 門口有 entity=" + occupied + " 門關著=" + closed + " 門正中=" + doorCenter(run));
        return true;
    }

    /**
     * {@code sealclose} 的 restart（F6-2-09「SEALED 開服後只關門一次」）：開服時窗口表的斷言（SEALED(AUTHORITY_UNREADABLE) 與 halt 當下相同、磁碟上的門是一般開門），原艙附近同步載入、
     * 門在記憶體裡也是一般開門、沒有人在線上（{@link #doorAtStartWithNobodyOnline}）。之後：
     * <ol>
     * <li>{@code closeQueued}：重啟之後第一次載入的那個 tick 排入一則 CLOSE_ON_SEALED，門仍開著（{@code closeQueuedOnTheFirstLoad}）。</li>
     * <li>{@code closeApplied}：門關上，套用的 INFO 恰一則、記的 tick ≥ 排入時記下的 tick（要等門口的 entity storage），排入仍恰一則、沒有待套用的門（{@code closedOnceAfterTheRestart}）。</li>
     * <li>參與者 JOIN 在艙外（收據存檔時的位置），照一般方式開門（SEALED：照一般門，{@code openedNormallyWhileSealed}）→ {@link M5CollapsePassage#WATCH_TICKS} 個 tick 每個 tick
     *     門都開著、排入與套用各一則（{@code closedOnlyOnce}：一直載入著的 session 不會再觸發重新套用，玩家打開的門不會被關回去）。</li>
     * <li>參與者由艙外進入 → AUTHORITY_UNREADABLE 是 restart-only，開服之後第一次觸發即重試（{@code retriedOnceAfterTheRestart}）→ RESOLVE → PASSAGE_READY → 跨越 → (b) 終局
     *     （可重試的原因：沒有開服時的提醒，{@code noSessionError}）。</li>
     * </ol>
     */
    Phase plainDoorSealedRestart(String crashPhase) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（UNREADABLE_SEALED 是 PLAIN_OPEN），不在這裡再寫一份。
        var id = CrashWindow.UNREADABLE_SEALED;
        var door = doorAtStart(id);
        var steps = new ArrayList<Step>();
        steps.add(closeQueued(door));
        steps.add(closeApplied("closedOnceAfterTheRestart", OriginDoorState.CLOSED, () -> NOTHING_MORE));
        steps.add(joinOutside());
        steps.add(new Step("opened", 1, this::openedNormallyWhileSealed));
        steps.add(M5CollapsePassage.watch("openWhileSealed", M5CollapsePassage.WATCH_TICKS, this::closedOnlyOnce));
        steps.add(enter());
        steps.add(retried("retried", "retriedOnceAfterTheRestart", SealedReason.AUTHORITY_UNREADABLE, 1, Await.EDGE));
        steps.addAll(passage.steps(null));
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> doorAtStartWithNobodyOnline(door));
    }

    /**
     * {@code sealdoorway} 的 restart（F6-2-09「門口有人時一般開門」）：開服時窗口表的斷言（磁碟上的門關著），門在記憶體裡也關著、沒有人在線上。B1 交下的「重啟之後 entity
     * storage 還沒載入也當成門口有人」不在這條 chain（F6-2e Step 0 依 F6-2d-2 B2 spec review M-1 更正：原本寫「也涵蓋」）：跨 JVM 量不到門口 entity storage 還沒載入的情況（排入的
     * tick 門口的 storage 已載入，取證 {@code doorwayEntitiesLoadedWhenQueued}），這一行由同 JVM 的 F4b-3 GameTest（{@code M5UniverseGameTests} 的
     * {@code sealed_door_closes_once_after_its_chunk_reloads_and_stays_open_once_a_player_opens_it}）守住。之後：
     * <ol>
     * <li>{@code closeQueued}：排入一則 CLOSE_ON_SEALED，門仍關著（{@code closeQueuedOnTheFirstLoad}）。</li>
     * <li>{@code closeApplied}：門改為一般開門、不關在盔甲座上（{@code plainOpenForTheDoorway}：套用的 INFO 恰一則、記的 tick ≥ 排入時記下的 tick；盔甲座仍在門口。probe 第一次在
     *     門口看到盔甲座的 tick 只記為 proof，見 {@link #standKeptInTheDoorway}）。</li>
     * <li>{@code standRemoved}：移走門口的盔甲座（恰一座，{@code standRemovedFromTheDoorway}）→ 參與者 JOIN 在艙外 → 由艙外進入 → 重試 → (b) 終局（同 {@link #plainDoorSealedRestart}）。</li>
     * </ol>
     */
    Phase doorwaySealedRestart(String crashPhase) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（UNREADABLE_SEALED_DOORWAY 是 CLOSED）。
        var id = CrashWindow.UNREADABLE_SEALED_DOORWAY;
        var door = doorAtStart(id);
        var steps = new ArrayList<Step>();
        steps.add(closeQueued(door));
        steps.add(closeApplied("plainOpenForTheDoorway", OriginDoorState.PLAIN_OPEN, this::standKeptInTheDoorway));
        steps.add(new Step("standRemoved", 1, this::standRemovedFromTheDoorway));
        steps.add(joinOutside());
        steps.add(enter());
        steps.add(retried("retried", "retriedOnceAfterTheRestart", SealedReason.AUTHORITY_UNREADABLE, 1, Await.EDGE));
        steps.addAll(passage.steps(null));
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> doorAtStartWithNobodyOnline(door));
    }

    /**
     * SEALED 的門 chain 的開服（{@link #restartPhase} 的 {@code afterStarted}，第一個 tick 之前）：原艙門在記憶體裡是 {@code door}（由磁碟載入；窗口表的
     * {@code doorAsExpectedAtStart} 讀磁碟），沒有人在線上、還沒有 tick；記下門口的 entity storage 是否已載入（{@code doorwayEntitiesLoadedAtStart}，取證）。F6-2e Step 0 起
     * {@code door} 一律是窗口列宣告的值（{@link #doorAtStart}，呼叫端在建立 phase 時讀）。
     */
    private void doorAtStartWithNobodyOnline(OriginDoorState door) {
        doorInMemoryAtStart(door);
        run.expect("nobodyOnlineAtStart", fixture.noneOnline() && run.ticks() == 0, () -> "開服時 tick=" + run.ticks() + " 有人在線上=" + !fixture.noneOnline());
    }

    /** 開服時（第一個 tick 之前）原艙門在記憶體裡是 {@code door}（{@code doorInMemoryAtStart}）；記下門口的 entity storage 是否已載入與 probe 是否已看到門口的 entity。 */
    private void doorInMemoryAtStart(OriginDoorState door) {
        run.expect("doorInMemoryAtStart", M5CollapseDoors.is(run, door), () -> "開服時記憶體裡的原艙門不是 " + door + "：門正中=" + doorCenter(run));
        run.proof.put("doorwayEntitiesLoadedAtStart", OriginDoorsTestAccess.doorwayEntitiesLoaded(run.source(), FRAME));
        seeTheDoorway();
    }

    /** probe 第一次看到門口有 entity 時記下那個 server tick（{@link SealedDoor#doorwaySeenAt}）。只查詢。 */
    private void seeTheDoorway() {
        if (sealedDoor.doorwaySeenAt < 0 && OriginDoorsTestAccess.doorwayOccupied(run.source(), FRAME)) {
            sealedDoor.doorwaySeenAt = run.server().getTicks();
            run.proof.put("doorwaySeenAtServerTick", sealedDoor.doorwaySeenAt);
        }
    }

    /**
     * step {@code closeQueued}（{@link M5CollapsePassage#awaiting}，最多 {@link M5CollapseDoors#QUEUE_WAIT_TICKS} 個 tick；SEALED 的門 chain 共用，「排入 → 套用」的前半）：重啟之後第一次載入的
     * 那個 tick 排入恰一則 CLOSE_ON_SEALED（原因「重啟之後第一次載入」），那個 tick 原艙門仍是開服時的 {@code door}，這座艙待套用的門是 CLOSE_ON_SEALED（production 的 {@code OriginDoors}，經
     * {@link OriginDoorsTestAccess}；{@code closeQueuedOnTheFirstLoad}）。記下排入時記下的 tick（N）；另記下那個 tick 門口的 entity storage 是否已載入（取證）。
     */
    private Step closeQueued(OriginDoorState door) {
        return closeQueued(door, M5CollapseRun.NOTHING);
    }

    /** 同 {@link #closeQueued(OriginDoorState)}，斷言之後在同一個 tick 再做 {@code then}（{@code sealmembrane}：非參與者使用門）。 */
    private Step closeQueued(OriginDoorState door, M5CollapseRun.Checked then) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：seeTheDoorway（proof）由 happened 移到 eachTick；順序相同（先記再查詢）。
        return M5CollapsePassage.awaiting("closeQueued", QUEUE_WAIT_TICKS, tick -> seeTheDoorway(), () -> !reapplyQueued(run, fixture.sid()).isEmpty(), (tick, happened) -> {
            var queued = reapplyQueued(run, fixture.sid());
            boolean unchanged = M5CollapseDoors.is(run, door);
            var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
            run.expect("closeQueuedOnTheFirstLoad", queued.size() == 1 && queued.getFirst().closeAfterARestart() && unchanged && pending.equals(Optional.of(OriginDoorsTestAccess.CLOSE_ON_SEALED)),
                    () -> "開服之後 " + run.ticks() + " 個 tick，排入的 INFO：" + queued + " 門仍是 " + door + "=" + unchanged + " 門正中=" + doorCenter(run) + " 待套用的門="
                            + pending.orElse("none"));
            sealedDoor.queuedTick = queued.getFirst().tick();
            run.proof.put("closeQueuedAtServerTick", run.server().getTicks());
            run.proof.put("closeQueuedTick", sealedDoor.queuedTick);
            run.proof.put("doorwayEntitiesLoadedWhenQueued", OriginDoorsTestAccess.doorwayEntitiesLoaded(run.source(), FRAME));
            then.run();
        });
    }

    /** {@link #closeApplied} 之外沒有別的條件。 */
    private static final Verdict NOTHING_MORE = new Verdict(true, "");

    /**
     * step {@code closeApplied}（{@link M5CollapsePassage#awaiting}，最多 {@link #CLOSE_APPLY_WAIT_TICKS} 個 tick；「排入 → 套用」的後半）：原艙門改為 {@code target}；那時套用的 INFO 恰一則、
     * 目標是 CLOSE_ON_SEALED、記的 tick ≥ 排入時記下的 tick（同一個 tick 也算，套用在那個 tick 的 {@code applyPending}；之後的 tick 是等門口的 entity storage）、排入的 INFO 仍恰一則、
     * 這座艙沒有待套用的門，而且 {@code also} 成立（具名斷言 {@code assertion}）。到時門仍不是 {@code target} 時由同一個斷言判 FAIL。
     */
    private Step closeApplied(String assertion, OriginDoorState target, Supplier<Verdict> also) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：seeTheDoorway（proof）由 happened 移到 eachTick；順序相同。
        return M5CollapsePassage.awaiting("closeApplied", CLOSE_APPLY_WAIT_TICKS, tick -> seeTheDoorway(), () -> M5CollapseDoors.is(run, target), (tick, reached) -> {
            var sid = fixture.sid();
            var applied = M5CollapseDoors.applied(run, sid);
            int queued = reapplyQueued(run, sid).size(), now = run.server().getTicks();
            var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
            int at = applied.size() == 1 ? applied.getFirst().tick() : -1;
            boolean once = applied.size() == 1 && OriginDoorsTestAccess.CLOSE_ON_SEALED.equals(applied.getFirst().goal()) && at >= sealedDoor.queuedTick;
            var more = also.get();
            run.expect(assertion, reached && once && queued == 1 && pending.isEmpty() && more.holds(),
                    () -> "tick " + now + "（排入的是 tick " + sealedDoor.queuedTick + "）：門是 " + target + "=" + reached + " 門正中=" + doorCenter(run) + " 套用的 INFO=" + applied
                            + " 排入=" + queued + " 待套用的門=" + pending.orElse("none") + more.detail());
            run.proof.put("closeAppliedTick", at);
            run.proof.put("closeAppliedAtServerTick", now);
        });
    }

    /**
     * sealdoorway 的 {@code closeApplied} 另要求：盔甲座仍在門口。
     * <p>F6-2e Step 0（F6-2d-2 B2 quality Minor 4、spec review M-1）：拿掉子條件 {@code seen >= 0 && at >= seen}（「套用的 tick 不早於 probe 第一次在門口看到盔甲座的 tick」）。
     * 它在結構上恆真：probe 以 {@code AFTER} 註冊，每個 tick 先記下門口（{@link #seeTheDoorway}，{@code eachTick}）再判斷門，與 production 用同一個門口判斷，所以驗不出「關門等到
     * 門口的 entity storage 載入之後才判斷」。兩個 tick 仍記為 proof（{@code doorwaySeenAtServerTick}、{@code closeAppliedTick}）並附在失敗訊息。跨 JVM 量不到門口 entity storage
     * 還沒載入的情況，這一行由同 JVM 的 F4b-3 GameTest（{@code M5UniverseGameTests} 的
     * {@code sealed_door_closes_once_after_its_chunk_reloads_and_stays_open_once_a_player_opens_it}）守住。
     * <p>F6-2e-1（F6-2e Step 0 review 的測試缺陷）：「盔甲座仍在門口」只數 probe 自己放的盔甲座（{@link M5CollapseDoors#doorwayStands}，恰一座），不再以 production 的門口判斷代替（門口另有
     * 世界生成的動物時那個判斷照樣成立）。production 的判斷附在失敗訊息。
     */
    private Verdict standKeptInTheDoorway() {
        int stands = M5CollapseDoors.doorwayStands(run).size();
        boolean occupied = OriginDoorsTestAccess.doorwayOccupied(run.source(), FRAME);
        var applied = M5CollapseDoors.applied(run, fixture.sid());
        int at = applied.isEmpty() ? -1 : applied.getFirst().tick(), seen = sealedDoor.doorwaySeenAt;
        return new Verdict(stands == 1, " 門口的盔甲座=" + stands + " 門口有 entity=" + occupied + " 第一次看到門口的 entity 在 tick " + seen + " 套用在 tick " + at);
    }

    /**
     * sealdoorway 的 step {@code standRemoved}：移走門口的盔甲座（恰一座）；之後門口沒有盔甲座，門仍是一般開門（{@code standRemovedFromTheDoorway}）。F6-2e-1（F6-2e Step 0 review 的測試缺陷）：
     * 原本以 {@code M5GameTestSupport.discardDoorwayEntities} 移走門口的所有 entity、要求恰一個，世界生成的動物也在門口時是「移走 2 個 entity」（F6-2e Step 0 的 run-all-1）；改為只移走、只數
     * probe 自己放的盔甲座（{@link M5CollapseDoors#doorwayStands}）。移走之後 production 的門口判斷記為 proof（{@code doorwayOccupiedAfterTheStand}）。
     */
    private boolean standRemovedFromTheDoorway() {
        var stands = M5CollapseDoors.doorwayStands(run);
        stands.forEach(net.minecraft.entity.Entity::discard);
        int removed = stands.size(), left = M5CollapseDoors.doorwayStands(run).size();
        boolean plain = plainOpen(run);
        run.proof.put("doorwayOccupiedAfterTheStand", OriginDoorsTestAccess.doorwayOccupied(run.source(), FRAME));
        run.expect("standRemovedFromTheDoorway", removed == 1 && left == 0 && plain, () -> "移走 " + removed + " 座盔甲座，門口仍有盔甲座=" + left + " 一般開門=" + plain);
        return true;
    }

    /** sealclose 的 step {@code opened}：參與者（JOIN 在艙外）照一般方式開門：SEALED 的門照一般門的邏輯（PASS），門改為一般開門，record 仍是 SEALED、沒有取消關門的 INFO（已套用，沒有待取消的）。 */
    private boolean openedNormallyWhileSealed() {
        var sid = fixture.sid();
        var result = M5GameTestSupport.interact(run.source(), M5CollapseFixture.local(3, 3, 0), fixture.players().getFirst());
        boolean plain = plainOpen(run), sealed = sealed(run.journal().flushedRecords().get(sid));
        int cancelled = M5CollapseDoors.cancelledCloses(run, sid);
        run.expect("openedNormallyWhileSealed", result.isAccepted() && plain && sealed && cancelled == 0,
                () -> "參與者照一般方式開門：" + result + " 一般開門=" + plain + " 門正中=" + doorCenter(run) + " state=" + M5GameTestSupport.state(run.journal(), sid) + " 取消關門的 INFO=" + cancelled);
        run.proof.put("openedAtServerTick", run.server().getTicks());
        return true;
    }

    /** sealclose 的 step {@code openWhileSealed} 的每個 tick（{@code tick} 由 1 起）：門一直開著、排入與套用各一則、沒有待套用的門、record 仍是 SEALED（只關一次）。 */
    private void closedOnlyOnce(int tick) {
        var sid = fixture.sid();
        boolean plain = plainOpen(run), sealed = sealed(run.journal().flushedRecords().get(sid));
        int queued = reapplyQueued(run, sid).size(), applied = reapplied(run, sid).size();
        var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
        run.expect("closedOnlyOnce", plain && queued == 1 && applied == 1 && pending.isEmpty() && sealed,
                () -> "開門之後第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter(run) + " 排入=" + queued + " 套用=" + applied + " 待套用的門=" + pending.orElse("none")
                        + " state=" + M5GameTestSupport.state(run.journal(), sid));
    }

    /** {@code record} 存在而且是 SEALED。 */
    private static boolean sealed(SessionRecoveryRecord record) {
        return record != null && record.state() == SessionState.SEALED;
    }

    // ---- sealmembrane、sealmembranejoined（F6-2-11）----

    /**
     * {@code sealmembrane} 與 {@code sealmembranejoined} 共用的 crash（窗口表的 {@code PROJECTION_SEALED}）：DORMANT 的 NEW 收據、原生存檔 → 裝上 U 的第一次投影寫入失敗
     * （{@code PROJECTION_WRITE_FAULT}，FIRST_WRITE、一次）→ 參與者由艙外進入 → COLLAPSING → RESOLVE → PREPARE 的第一個 tick 套用 sealed 量子膜 → 投影寫入失敗 →
     * SEALED(PROJECTION_FAILED) 已確認、{@code sealCommitted} 的關門之前 halt（halt 之前原生存檔：sealed 的門與參與者在艙內的 playerdata 都在磁碟上）。
     */
    Phase membraneSealedCrash() {
        return crash(dormantNewSetup(), List.of(), this::projectionWriteFails, CrashWindow.PROJECTION_SEALED, 600);
    }

    /** 收據之後（{@link #crash} 的 {@code install}）：U 的第一次投影寫入失敗一次（{@code CollapseService.project} 已先寫下 PENDING）。 */
    private void projectionWriteFails() {
        run.install(M5TestHooks.PROJECTION_WRITE_FAULT, new M5TestHooks.ProjectionWriteFault(UniverseKeys.world(fixture.universe(), DimensionRole.OVERWORLD),
                ProjectionWriter.Mode.FIRST_WRITE, 1));
    }

    /**
     * {@code sealmembrane} 的 restart（F6-2-11 變體 (i)：開服時沒有參與者；T-SMEM 的跨 JVM 版本）：開服時窗口表的斷言（SEALED(PROJECTION_FAILED) 與 halt 當下相同、projection ledger
     * 仍是 PENDING、磁碟上的門是 sealed 量子膜：不成立就 FAIL），原艙附近同步載入、門在記憶體裡也是 sealed、沒有參與者在線上；非參與者 JOIN 之後移到艙外
     * （{@link #visitorOutsideWithNobodyOnline}）。之後：
     * <ol>
     * <li>{@code closeQueued}：排入一則 CLOSE_ON_SEALED、門仍是 sealed（{@code closeQueuedOnTheFirstLoad}）；同一個 tick 非參與者以 port 使用門（{@code M5GameTestSupport.useDoor}，
     *     不切換門）：PASS、門仍是 sealed、沒有「取消還沒套用的關門」的 INFO、待套用的仍是 CLOSE_ON_SEALED（{@code membraneKeptOnTheVisitorUse}：門還帶著量子膜時不取消關門）。</li>
     * <li>{@code closeApplied}：門關上、量子膜清掉，套用的 INFO 恰一則，仍沒有取消關門的 INFO（{@code closedFromTheMembrane}）。</li>
     * <li>{@code join}：參與者 JOIN 在艙內（halt 之前存的位置）。開服之後的第一次觀察沒有人在艙內，JOIN 之後出現在艙內就是邊緣（{@code stillSealedAtTheJoin}）。</li>
     * <li>{@code retried}：PROJECTION_FAILED 是 EDGE_WITH_BACKOFF，開服之後的第一次觸發不受退避限制，重試一次（{@code retriedOnTheJoinEdge}）→ RESOLVE → 閘門第一次以位準評估 →
     *     PREPARE → PASSAGE_READY → 跨越 → (b) 終局。</li>
     * </ol>
     */
    Phase membraneSealedRestart(String crashPhase) {
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（PROJECTION_SEALED 是 SEALED_MEMBRANE）。
        var id = CrashWindow.PROJECTION_SEALED;
        var door = doorAtStart(id);
        var steps = new ArrayList<Step>();
        steps.add(closeQueued(door, this::visitorUsesTheMembraneDoor));
        steps.add(closeApplied("closedFromTheMembrane", OriginDoorState.CLOSED, this::closeNotCancelled));
        steps.add(new Step("join", 1, this::joinInsideOnTheEdge));
        steps.add(retried("retried", "retriedOnTheJoinEdge", SealedReason.PROJECTION_FAILED, 1, Await.EDGE));
        steps.addAll(passage.steps(null));
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> visitorOutsideWithNobodyOnline(door));
    }

    /** sealmembrane 的開服：{@link #doorAtStartWithNobodyOnline}（{@code door} 是窗口列的 sealed 量子膜）；非參與者（新的 UUID，不在收據裡）JOIN 之後移到艙外（{@code visitorOutsideAtStart}）。 */
    private void visitorOutsideWithNobodyOnline(OriginDoorState door) {
        doorAtStartWithNobodyOnline(door);
        var visitor = sealedDoor.visitor = fixture.connect(UUID.randomUUID()).player();
        M5GameTestSupport.moveOutside(visitor, FRAME);
        boolean outside = !M5CollapseFixture.inside(visitor);
        run.expect("visitorOutsideAtStart", outside && !fixture.participants().contains(visitor.getUuid()) && visitor.getServerWorld() == run.source() && fixture.noneOnline(),
                () -> "非參與者在 " + visitor.getPos() + " 參與者在線上=" + !fixture.noneOnline());
        run.proof.put("visitor", visitor.getUuid().toString());
    }

    /**
     * sealmembrane 的 {@code closeQueued} 之後（同一個 tick）：非參與者以 port 使用門（直接呼叫攔截，不切換門）。SEALED 的門照一般門的邏輯（PASS），門還帶著量子膜，所以保留還沒套用的關門
     * （只記 DEBUG）：門仍是 sealed、沒有「取消還沒套用的關門」的 INFO、待套用的仍是 CLOSE_ON_SEALED（{@code membraneKeptOnTheVisitorUse}）。
     */
    private void visitorUsesTheMembraneDoor() {
        var sid = fixture.sid();
        var use = M5GameTestSupport.useDoor(run.source(), FRAME, sealedDoor.visitor);
        boolean sealedDoorNow = M5CollapseDoors.is(run, OriginDoorState.SEALED_MEMBRANE);
        int cancelled = M5CollapseDoors.cancelledCloses(run, sid);
        var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
        run.expect("membraneKeptOnTheVisitorUse", use == ChamberCollapsePort.DoorUse.PASS && sealedDoorNow && cancelled == 0 && pending.equals(Optional.of(OriginDoorsTestAccess.CLOSE_ON_SEALED)),
                () -> "非參與者使用門：" + use + " sealed=" + sealedDoorNow + " 門正中=" + doorCenter(run) + " 取消關門的 INFO=" + cancelled + " 待套用的門=" + pending.orElse("none"));
        run.proof.put("visitorUsedTheDoorAtServerTick", run.server().getTicks());
    }

    /** sealmembrane 的 {@code closeApplied} 另要求：沒有「取消還沒套用的關門」的 INFO（非參與者使用門時沒有取消）。 */
    private Verdict closeNotCancelled() {
        int cancelled = M5CollapseDoors.cancelledCloses(run, fixture.sid());
        return new Verdict(cancelled == 0, " 取消關門的 INFO=" + cancelled);
    }

    /** sealmembrane 的 step {@code join}：參與者 JOIN 在艙內（halt 之前存的位置），這時 record 仍是 SEALED、還沒有重試（{@code stillSealedAtTheJoin}）；記下 JOIN 的位置。 */
    private boolean joinInsideOnTheEdge() {
        var record = run.journal().flushedRecords().get(fixture.sid());
        int retries = sealedRetries();
        joinInside();
        run.expect("stillSealedAtTheJoin", sealed(record) && retries == 0, () -> "JOIN 之前 state=" + M5GameTestSupport.state(run.journal(), fixture.sid()) + " 重試的 INFO=" + retries);
        run.proof.put("joinedAtServerTick", run.server().getTicks());
        return true;
    }

    /**
     * {@code sealmembranejoined} 的 restart（F6-2-11 變體 (ii)，H9 (a) 的跨 JVM 版本）：開服時窗口表的斷言同 {@link #membraneSealedRestart}；第一個 tick 之前參與者 JOIN 在艙內
     * （{@link #joinedBeforeTheFirstObservation}：JOIN 早於第一次觀察）。開服到 NORMALIZE 套用的每個 tick 原艙門都沒有關上（{@code doorNeverClosedAfterTheRestart}：sealed 或一般開門）。之後：
     * <ol>
     * <li>{@code firstObservation}：開服之後的第一次觀察（tick N-1）同時排入一則 CLOSE_ON_SEALED（tick N）、由 SEALED(PROJECTION_FAILED) 重試一次（參與者沒有移動；
     *     {@code queuedAndRetriedOnTheFirstObservation}）。同一個 probe step 拿掉 Controller 上方的紅石塊：重試的觸發要求來源艙有電（§7.1），所以在重試的 tick 之後才斷電；斷電之後
     *     閘門的第一次評估不成立。</li>
     * <li>{@code normalized}：tick N「session 已不是 SEALED，不再套用封存時的原艙門」恰一則，關門由 COLLAPSING 的重新套用目標取代、立即套用：門改為一般開門、
     *     「（NORMALIZE：APPLIED，tick N）」恰一則（{@code normalizedAfterTheRetry}）。</li>
     * <li>{@code resolved}：RESOLVE 完成，這時沒有電、沒有進 PREPARE、參與者沒有移動（{@code resolvedWithoutPower}）。</li>
     * <li>{@code waitingWithoutPower}：{@link M5CollapsePassage#WATCH_TICKS} 個 tick，每個 tick 一般開門、record 是 COLLAPSING、沒有「進入 PREPARE」、這個 session 沒有 ERROR（沒有開服時的
     *     提醒）、沒有待套用的門、參與者沒有移動（{@code plainOpenWhileWaitingWithoutPower}；閘門走「來源艙沒有電」這條路徑保持不成立）；之後放回紅石塊。</li>
     * <li>{@code left}：恢復供電之後的下一個 tick 仍沒有進 PREPARE（閘門已評估過，之後只看邊緣；{@code poweredWithoutAnEdge}），參與者離開艙內 → 由艙外進入（§7.1 的邊緣）→ PREPARE →
     *     PASSAGE_READY → 跨越 → (b) 終局；終局時「進入 PREPARE」恰一則（{@code preparedOnlyAfterTheEdge}）。</li>
     * </ol>
     */
    Phase membraneSealedJoinedRestart(String crashPhase) {
        var steps = new ArrayList<Step>();
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：每個 tick 的具名斷言 doorNeverClosedAfterTheRestart 由 happened 移到 eachTick；順序相同（先斷言再查詢）。
        steps.add(M5CollapsePassage.awaiting("firstObservation", QUEUE_WAIT_TICKS, tick -> doorNotClosedAfterTheRestart(),
                () -> !reapplyQueued(run, fixture.sid()).isEmpty() || sealedRetries() > 0, this::queuedAndRetriedOnTheFirstObservation));
        steps.add(M5CollapsePassage.awaiting("normalized", APPLY_WAIT_TICKS, tick -> doorNotClosedAfterTheRestart(), () -> plainOpen(run), this::normalizedAfterTheRetry));
        steps.add(new Step("resolved", 400, this::resolvedWithoutPower));
        steps.add(M5CollapsePassage.watch("waitingWithoutPower", M5CollapsePassage.WATCH_TICKS, this::plainOpenWhileWaitingWithoutPower, this::powerRestored));
        steps.add(new Step("left", 1, this::poweredThenLeave));
        steps.add(enter());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> {
            int prepares = prepares();
            run.expect("preparedOnlyAfterTheEdge", prepares == 1, () -> "「進入 PREPARE」=" + prepares);
            return End.crossed(fixture);
        }));
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：開服時的門由窗口列讀（與 sealmembrane 共用 PROJECTION_SEALED）。
        var id = CrashWindow.PROJECTION_SEALED;
        var door = doorAtStart(id);
        return restartPhase(crashPhase, id, ChamberAtStart.PRELOADED, steps, () -> joinedBeforeTheFirstObservation(door));
    }

    /**
     * sealmembranejoined 的開服（第一個 tick 之前）：門在記憶體裡是 {@code door}（窗口列的 sealed 量子膜）；參與者 JOIN 在艙內，這時 record 仍是 SEALED
     * （{@code joinedBeforeTheFirstObservation}）；記下 JOIN 的位置。
     */
    private void joinedBeforeTheFirstObservation(OriginDoorState door) {
        doorInMemoryAtStart(door);
        joinInside();
        var record = run.journal().flushedRecords().get(fixture.sid());
        run.expect("joinedBeforeTheFirstObservation", run.ticks() == 0 && sealed(record), () -> "JOIN 時 tick=" + run.ticks() + " state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
        run.proof.put("joinedAt", joinedAt.toString());
    }

    /** sealmembranejoined：開服之後到 NORMALIZE 套用，每個 tick 原艙門都沒有關上（25 格是 sealed 量子膜或一般開門；{@code doorNeverClosedAfterTheRestart}）。 */
    private void doorNotClosedAfterTheRestart() {
        boolean sealedDoorNow = M5CollapseDoors.is(run, OriginDoorState.SEALED_MEMBRANE), plain = plainOpen(run);
        run.expect("doorNeverClosedAfterTheRestart", sealedDoorNow || plain, () -> "tick " + run.server().getTicks() + "：門正中=" + doorCenter(run));
    }

    /** 這個 session「SEALED 重試」的 INFO 的則數（任何原因）。 */
    private int sealedRetries() {
        return run.capture().matching(Level.INFO, "SEALED 重試", fixture.sid().toString()).size();
    }

    /** sealmembranejoined 的 step {@code firstObservation}（{@code tick} 由 1 起）：見 {@link #membraneSealedJoinedRestart} 第 1 點；之後拿掉 Controller 上方的紅石塊。 */
    private void queuedAndRetriedOnTheFirstObservation(int tick, boolean happened) {
        var sid = fixture.sid();
        var queued = reapplyQueued(run, sid);
        int now = run.server().getTicks(), all = sealedRetries();
        int fromProjection = run.capture().matching(Level.INFO, "SEALED 重試：SEALED(" + SealedReason.PROJECTION_FAILED + ")→COLLAPSING", sid.toString()).size();
        var record = run.journal().flushedRecords().get(sid);
        boolean still = notMovedSinceTheJoin(), sealedDoorNow = M5CollapseDoors.is(run, OriginDoorState.SEALED_MEMBRANE);
        run.expect("queuedAndRetriedOnTheFirstObservation", queued.size() == 1 && queued.getFirst().closeAfterARestart() && queued.getFirst().tick() == now + 1 && fromProjection == 1
                        && all == 1 && collapsing(record) && still && sealedDoorNow,
                () -> "開服之後 " + run.ticks() + " 個 tick（server tick " + now + "）：排入的 INFO=" + queued + " PROJECTION_FAILED 的重試=" + fromProjection + " 全部的重試=" + all + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + " 沒有移動=" + still + " sealed=" + sealedDoorNow);
        sealedDoor.queuedTick = queued.getFirst().tick();
        run.proof.put("firstObservationAtPhaseTick", run.ticks());
        run.proof.put("closeQueuedTick", sealedDoor.queuedTick);
        // 重試的觸發要求來源艙有電（source signal），所以在重試的 tick 之後才斷電；閘門在 RESOLVE 之後第一次以位準評估時不成立（WAITING）。
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
    }

    /**
     * sealmembranejoined 的 step {@code normalized}（{@link M5CollapsePassage#awaiting}，最多 {@link M5CollapseDoors#APPLY_WAIT_TICKS} 個 tick；每個 tick 先斷言
     * {@code doorNeverClosedAfterTheRestart}，{@code plain} 是那個 tick 門是否已是一般開門）：見 {@link #membraneSealedJoinedRestart} 第 2 點；排入的 tick 之後 2 個 tick 仍不是一般開門
     * 就由這個具名斷言判 FAIL。F6-2e Step 0（F6-2d-2 B2 quality Minor 3）：之前是 {@code counting} 加手寫的 {@code !plain && now < N + 2}；step 的第 1 個 tick 是 tick N
     * （{@code firstObservation} 在 N-1 完成），所以等待時間與通過條件都不變。
     */
    private void normalizedAfterTheRetry(int tick, boolean plain) {
        int now = run.server().getTicks(), queuedTick = sealedDoor.queuedTick;
        var sid = fixture.sid();
        int dropped = run.capture().matching(Level.INFO, "session 已不是 SEALED，不再套用封存時的原艙門", "session=" + sid).size();
        var applied = M5CollapseDoors.applied(run, sid);
        run.expect("normalizedAfterTheRetry", plain && now == queuedTick && dropped == 1 && applied.equals(List.of(new M5CollapseDoors.Applied(OriginDoorsTestAccess.NORMALIZE, queuedTick))),
                () -> "tick " + now + "（排入的是 tick " + queuedTick + "）：一般開門=" + plain + " 門正中=" + doorCenter(run) + "「不再套用封存時的原艙門」=" + dropped + " 套用的 INFO="
                        + applied);
        run.proof.put("normalizedAtServerTick", now);
    }

    /** sealmembranejoined 的 step {@code resolved}：見 {@link #membraneSealedJoinedRestart} 第 3 點。 */
    private boolean resolvedWithoutPower() throws Exception {
        var sid = fixture.sid();
        if (run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).isEmpty()) return false;
        passage.expectResolved();
        boolean powered = run.source().isReceivingRedstonePower(FRAME.controllerPos()), plain = plainOpen(run), still = notMovedSinceTheJoin();
        int prepares = prepares();
        var record = run.journal().flushedRecords().get(sid);
        run.expect("resolvedWithoutPower", !powered && prepares == 0 && plain && collapsing(record) && still,
                () -> "RESOLVE 完成時：有電=" + powered + "「進入 PREPARE」=" + prepares + " 一般開門=" + plain + " state=" + M5GameTestSupport.state(run.journal(), sid) + " 沒有移動=" + still);
        run.proof.put("resolvedAtServerTick", run.server().getTicks());
        return true;
    }

    /** sealmembranejoined 的 step {@code waitingWithoutPower} 的每個 tick（{@code tick} 由 1 起）：見 {@link #membraneSealedJoinedRestart} 第 4 點。 */
    private void plainOpenWhileWaitingWithoutPower(int tick) {
        var sid = fixture.sid();
        boolean plain = plainOpen(run), powered = run.source().isReceivingRedstonePower(FRAME.controllerPos()), still = notMovedSinceTheJoin();
        int prepares = prepares(), errors = run.capture().matching(Level.ERROR, sid.toString()).size(), applied = reapplied(run, sid).size();
        var record = run.journal().flushedRecords().get(sid);
        var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
        run.expect("plainOpenWhileWaitingWithoutPower", plain && !powered && prepares == 0 && collapsing(record) && errors == 0 && still && pending.isEmpty() && applied == 1,
                () -> "RESOLVE 之後第 " + tick + " 個 tick：一般開門=" + plain + " 門正中=" + doorCenter(run) + " 有電=" + powered + "「進入 PREPARE」=" + prepares + " state="
                        + M5GameTestSupport.state(run.journal(), sid) + " ERROR=" + errors + " 沒有移動=" + still + " 待套用的門=" + pending.orElse("none") + " 套用=" + applied);
    }

    /** {@code waitingWithoutPower} 觀察完之後：放回 Controller 上方的紅石塊。 */
    private void powerRestored() {
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        run.proof.put("poweredAgainAtServerTick", run.server().getTicks());
    }

    /** sealmembranejoined 的 step {@code left}：見 {@link #membraneSealedJoinedRestart} 第 5 點（之後參與者離開艙內）。 */
    private boolean poweredThenLeave() {
        boolean powered = run.source().isReceivingRedstonePower(FRAME.controllerPos()), still = notMovedSinceTheJoin();
        int prepares = prepares();
        run.expect("poweredWithoutAnEdge", powered && prepares == 0 && still, () -> "恢復供電之後：有電=" + powered + "「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
        fixture.moveOutside();
        return true;
    }

}
