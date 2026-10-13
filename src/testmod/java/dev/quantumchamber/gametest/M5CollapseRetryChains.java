package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapsePassage.End;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-1）：重試與續行跨重啟的 crash／restart chain（spec §12 的 W8、W9 與 {@code AUTHORITY_UNREADABLE} 的重啟後重試；交接清單 F6-2-02、F6-2-03、
 * F6-2-07）。crash phase 都從 DORMANT 的 NEW 收據開始（原艙與參與者同 {@link M5CollapseDormantChains#crash}，一人），restart 開服時依窗口表斷言磁碟上的狀態
 * （{@link M5CollapseWindows#expectAtRestart}），之後由 spec §7.1／§11.2 的觸發續行，經 RESOLVE、PREPARE 到 PASSAGE_READY、跨越與 (b) 終局。spec §12 的四件事（同一個
 * UniverseId、catalog 最多多 1 筆、沒有重抽、沒有人被困住）每個 tick 由 {@link M5CollapsePassage#invariants} 斷言，終局由 {@link M5CollapsePassage#expectEnd} 斷言。
 * <ul>
 * <li>{@code w8}（{@code DORMANT_STEP}，spec §12 W8）：參與者在艙內時寫 DORMANT 的收據（執行期間的 session 第一次觀察只記下狀態、不觸發），觀察 40 tick 沒有提交，之後
 *     在塌縮流程處理這個 DORMANT 之前 crash。restart 開服時（第一個 tick 之前）參與者 JOIN 在艙內，開服之後的第一次觀察就提交 COLLAPSING（參與者沒有移動）；RESOLVE 之後
 *     閘門第一次以位準評估就進 PREPARE（沒有邊緣）；斷電讓 PREPARE 退回 WAITING，恢復供電之後參與者一直在艙內也不再進 PREPARE（之後只看邊緣），由艙外再進入才進 PREPARE、
 *     到 PASSAGE_READY。以真重啟取代同 JVM test 以 {@code markStartup} 反射寫入模擬的開服（F6-2-02）。</li>
 * <li>{@code w9}（{@code MATERIALIZE_SEALED}，spec §12 W9）：物化注入失敗 → catalog 的這筆 checked 改為 DISABLED → SEALED(MATERIALIZE_FAILED) 已確認時 crash。restart
 *     開服不停止（bootstrap 不物化 DISABLED），參與者由艙外進入 → 重試一次（開服之後第一次）→ RESOLVE 把 DISABLED 視為 §8.3 已完成：改回 EAGER_ENABLED、物化、追加
 *     discovery → PASSAGE_READY。</li>
 * <li>{@code w9io}（同一個窗口，W9 的變體 (a)，F4a I-1 的跨重啟版）：restart 之後先讓「另一個 session」的 allocation 追加讀回失敗（ledger 未確認、仍在退避），重試的 RESOLVE
 *     在第 1 步封存為 ALLOCATION_IO_FAILED（改回 EAGER_ENABLED 之前）；退避期過後由艙外進入，第二次重試仍把 DISABLED 視為 §8.3 已完成，不是 AUTHORITY_CORRUPT。</li>
 * <li>{@code w9cd}（{@code CATALOG_DISABLED}，W9 的變體 (b)）：§8.3 的 DISABLED 已確認、SEALED 寫入之前 crash，開服時是 COLLAPSING＋DISABLED。restart 的第一次 RESOLVE
 *     就是 §8.3 的重試（spec §6「重啟後為什麼合法」：等同 §8.3 已完成；§12 COLLAPSING 列：依推導階段續做），不進 SEALED，改回 EAGER_ENABLED、物化。</li>
 * <li>{@code unreadable}（{@code SEALED_STEP}，F6-2-07）：discovery 的讀取注入 IO 例外 → SEALED(AUTHORITY_UNREADABLE)；同一個 instance 內退避期過後由艙外進入也不重試
 *     （restart-only），之後 crash。restart 之後參與者由艙外進入 → 重試一次 → PASSAGE_READY。</li>
 * </ul>
 * restart 開服時都以 probe 的 ticket 同步載入原艙附近（{@link M5CollapseFixture#loadAroundTheChamberNow}）：來源艙已載入是 §7.1／§11.2 觸發成立的條件之一，載入要在第一次觀察
 * 之前完成，觸發才不會因為 chunk 還在載入而落空（落空時依 §7.1 要再進入一次，不是這裡要驗的東西）。
 * <p>放在新的類別（不併入 {@link M5CollapsePassageChains}）：這幾條 chain 的 halt 當下不是 PASSAGE_READY 或終局，而是 DORMANT、SEALED 或 COLLAPSING＋DISABLED，restart 都要先由
 * 觸發（或開服之後的第一次 RESOLVE）續行，再接 {@link M5CollapsePassage} 的 ready／cross／ended；通道的 chain 則是開服時就結束通道。
 */
final class M5CollapseRetryChains {
    /** 觀察「沒有觸發」「沒有重試」「閘門只看邊緣」的 tick 數（比照 trr 的 {@code SEALED_WATCH_TICKS}）。 */
    static final int WATCH_TICKS = 40;
    /**
     * 封存之後等多少 tick 再由艙外進入：per-session 的退避與 ledger 收斂的退避都是 200 ticks（spec §11.2），留 5 ticks 餘裕（比照 G-DISABLED 的
     * {@code registered + 2 + 205}）。
     */
    static final int BACKOFF_TICKS = 205;
    /** restart 開服時以 probe 的 ticket 同步載入的半徑（chunk；原艙與 {@code chamberGate} 外擴的 3 格都在 Controller 所在 chunk 的半徑 1 之內）。 */
    private static final int CHAMBER_LOAD_RADIUS = 1;
    /** w8：PREPARE 等多少 tick（RESOLVE 之後閘門第一次以位準評估就該成立；超過時由具名斷言判 FAIL，不是 stepBounded）。 */
    private static final int PREPARE_WAIT_TICKS = 10;

    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** unreadable 的 crash、w9io 的 restart：probe 安裝的 checked 讀取失敗（命中次數）。 */
    private M5TestHooks.CheckedReadFault readFault;
    /** 觀察的 tick 數、PREPARE 已等的 tick 數，與封存（或 PREPARE）的 server tick。 */
    private int watched, waited, markTick = -1;
    /** unreadable 的 crash：封存之後的 record（之後觀察它沒有變）。 */
    private SessionRecoveryRecord sealedRecord;
    /** w8 的 restart：參與者 JOIN 時的位置（之後斷言沒有移動）。 */
    private Vec3d joinedAt;
    /** w9io 的 restart：另一個 session 已落地的 allocation entry（spec §6 roll-forward：不移除）。 */
    private M5CollapseDiskEvidence.Allocation otherAllocation;

    M5CollapseRetryChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    // ---- crash phase ----

    /** 共用的前兩步：fresh 世界蓋原艙、一位參與者在艙外 → 原艙註冊。 */
    private List<Step> origin() {
        return List.of(new Step("build", 1, () -> { fixture.buildOrigin(1); return true; }), new Step("registered", 200, fixture::registered));
    }

    private Phase crashPhase(List<Step> steps) {
        return Phase.halt(steps).started(fixture::requireFreshWorld);
    }

    /**
     * W8 的 crash：參與者先進入艙內，再寫 DORMANT 的 NEW 收據、原生存檔（playerdata 在艙內）。執行期間寫入的 session 第一次觀察只記下狀態（spec §7.1：只有開服 attach
     * 當下就在 journal 的 session，第一次觀察到在艙內才算觸發），所以觀察 {@link #WATCH_TICKS} 個 tick 都沒有提交（{@code notCommittedWithoutAnEdge}）；之後裝上 crash 點，
     * 塌縮流程下一次處理這個 DORMANT 之前 halt。
     */
    Phase w8Crash() {
        var steps = new ArrayList<>(origin());
        steps.add(new Step("inside", 1, () -> { fixture.moveInside(); return true; }));
        steps.add(new Step("receipt", 1, () -> { fixture.writeCrashReceipt(fixture.dormantNewReceipt()); return true; }));
        steps.add(new Step("notCommitted", WATCH_TICKS + 5, this::notCommittedWithoutAnEdge));
        steps.add(arm(CrashWindow.DORMANT_STEP));
        steps.add(new Step("halt", 5, () -> false));
        return crashPhase(steps);
    }

    /**
     * W9 與變體 (b) 的 crash：DORMANT 的 NEW 收據、原生存檔，裝上 U 的物化失敗（{@code MATERIALIZE_FAULT}：backend 回 FAILED_ROLLED_BACK）與 {@code id} 的 crash 點 → 參與者
     * 由艙外進入 → COLLAPSING → RESOLVE 物化失敗 → §8.3：catalog checked 改為 DISABLED（{@code CATALOG_DISABLED}：W9 (b) 在這裡 halt）→ SEALED(MATERIALIZE_FAILED) 已確認
     * （{@code MATERIALIZE_SEALED}：W9 與 (a) 在這裡 halt）。
     */
    Phase materializeFailureCrash(CrashWindow id) {
        var steps = new ArrayList<>(origin());
        steps.add(new Step("receipt", 1, () -> {
            fixture.writeCrashReceipt(fixture.dormantNewReceipt());
            run.install(M5TestHooks.MATERIALIZE_FAULT, new M5TestHooks.MaterializeFault(UniverseKeys.world(fixture.universe(), DimensionRole.OVERWORLD)));
            M5CollapseWindows.arm(run, fixture, id);
            return true;
        }));
        steps.add(new Step("enter", 1, () -> { fixture.moveInside(); return true; }));
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("halt", 400, () -> false));
        return crashPhase(steps);
    }

    /**
     * F6-2-07 的 crash：DORMANT 的 NEW 收據、原生存檔之後，裝上 discovery 讀取的 IO 失敗（{@code CHECKED_READ_FAULT}，一次；建立收據的 candidate context 也讀 discovery，
     * 所以排在收據之後，同 G-UNREADABLE）→ 參與者由艙外進入 → RESOLVE 第 4 步讀 discovery 失敗 → SEALED(AUTHORITY_UNREADABLE)（allocation、catalog、物化都已完成）→ 參與者
     * 離開艙內、等過 per-session 的退避期再由艙外進入 → 觀察 {@link #WATCH_TICKS} 個 tick：同一個 instance 內不重試（{@code notRetriedInTheSameInstance}，spec §11.2 的
     * restart-only）→ 裝上 crash 點，塌縮流程下一次處理這個 SEALED 之前 halt。
     */
    Phase unreadableCrash() {
        var steps = new ArrayList<>(origin());
        steps.add(new Step("receipt", 1, () -> {
            fixture.writeCrashReceipt(fixture.dormantNewReceipt());
            readFault = run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseDiscoveryState.FILE_NAME, 0, 1));
            return true;
        }));
        steps.add(new Step("enter", 1, () -> { fixture.moveInside(); return true; }));
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("sealed", 400, this::sealedUnreadable));
        steps.add(new Step("backoff", BACKOFF_TICKS + 10, this::backoffElapsedThenEnter));
        steps.add(new Step("notRetried", WATCH_TICKS + 5, this::notRetriedInTheSameInstance));
        steps.add(arm(CrashWindow.SEALED_STEP));
        steps.add(new Step("halt", 5, () -> false));
        return crashPhase(steps);
    }

    /** step：依窗口表裝上 {@code id} 的 crash 點（觀察完之後才裝，所以 halt 一定在觀察之後）。 */
    private Step arm(CrashWindow id) {
        return new Step("arm", 1, () -> { M5CollapseWindows.arm(run, fixture, id); return true; });
    }

    /**
     * W8 的 crash：每個 tick record 都與收據 exact 相同（flushed 與 current）、沒有「塌縮已提交」的 INFO、參與者在艙內（{@code notCommittedWithoutAnEdge}）；觀察
     * {@link #WATCH_TICKS} 個 tick。
     */
    private boolean notCommittedWithoutAnEdge() {
        var sid = fixture.sid();
        var committed = run.capture().matching(Level.INFO, "塌縮已提交", sid.toString()).size();
        boolean inside = fixture.players().stream().allMatch(M5CollapseFixture::inside);
        run.expect("notCommittedWithoutAnEdge", fixture.receipt().equals(run.journal().flushedRecords().get(sid)) && fixture.receipt().equals(run.journal().records().get(sid))
                && committed == 0 && inside, () -> "state=" + M5GameTestSupport.state(run.journal(), sid) + " 提交的 INFO=" + committed + " 在艙內=" + inside);
        return ++watched >= WATCH_TICKS;
    }

    /**
     * F6-2-07 的 crash：SEALED(AUTHORITY_UNREADABLE)（{@code sealedUnreadable}）；discovery 的讀取恰注入一次；allocation、catalog（EAGER_ENABLED）與物化都已完成
     * （discovery 在物化之後），discovery 沒有 U；恰一則「只在重啟之後重試」的 WARN、沒有這個 session 的 ERROR。之後參與者離開艙內。
     */
    private boolean sealedUnreadable() throws Exception {
        var sid = fixture.sid();
        var record = run.journal().flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.SEALED) return false;
        run.expect("sealedUnreadable", record.collapse().sealedReason().equals(Optional.of(SealedReason.AUTHORITY_UNREADABLE)) && readFault.hits() == 1,
                () -> "SEALED 的原因=" + record.collapse().sealedReason() + " 注入次數=" + readFault.hits());
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords().get(fixture.universe());
        run.expect("materializedBeforeTheDiscovery", M5CollapseFixture.eagerEnabled(catalog) && fixture.universeWorld().isPresent(), () -> "catalog=" + catalog);
        fixture.expectOneAllocation();
        run.expect("noDiscoveryAtTheSeal", () -> M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().noneMatch(entry -> entry.universeId().equals(fixture.universe())),
                () -> "discovery 已有 U");
        var warns = run.capture().matching(Level.WARN, "SEALED(AUTHORITY_UNREADABLE)", "只在重啟之後", sid.toString()).size();
        var errors = run.capture().matching(Level.ERROR, sid.toString()).size();
        run.expect("oneRestartOnlyWarn", warns == 1 && errors == 0, () -> "WARN=" + warns + " ERROR=" + errors);
        sealedRecord = record;
        markTick = run.server().getTicks();
        fixture.moveOutside();
        return true;
    }

    /** 封存之後過了 {@link #BACKOFF_TICKS} 個 tick（per-session 與 ledger 收斂的退避都已過），參與者由艙外進入艙內（邊緣觸發）。 */
    private boolean backoffElapsedThenEnter() {
        if (run.server().getTicks() < markTick + BACKOFF_TICKS) return false;
        fixture.moveInside();
        run.proof.put("reenteredAfterTheBackoffTick", run.server().getTicks());
        return true;
    }

    /**
     * F6-2-07 的 crash：退避期過後的邊緣觸發也不重試（spec §11.2：AUTHORITY_UNREADABLE 只在重啟之後重試）。每個 tick record 都與封存時 exact 相同、沒有「SEALED 重試」的
     * INFO、參與者在艙內（{@code notRetriedInTheSameInstance}）；觀察 {@link #WATCH_TICKS} 個 tick。
     */
    private boolean notRetriedInTheSameInstance() {
        var sid = fixture.sid();
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
        boolean inside = fixture.players().stream().allMatch(M5CollapseFixture::inside);
        run.expect("notRetriedInTheSameInstance", sealedRecord.equals(run.journal().flushedRecords().get(sid)) && sealedRecord.equals(run.journal().records().get(sid))
                && retried == 0 && inside, () -> "state=" + M5GameTestSupport.state(run.journal(), sid) + " 重試的 INFO=" + retried + " 在艙內=" + inside);
        return ++watched >= WATCH_TICKS;
    }

    // ---- restart phase ----

    /**
     * restart 開服時（共用）：讀回 crash phase 的收據、依窗口表斷言磁碟上的狀態；halt 當下記下了 record 的摘要時，另比對開服時的 record 與它相同
     * （{@code recordKeptAcrossTheRestart}）；以 probe 的 ticket 同步載入原艙附近（{@code chamberLoadedBeforeTheFirstTick}）。
     */
    private void started(String crashPhase, CrashWindow id) throws Exception {
        var window = M5CollapseWindows.of(id);
        var halt = fixture.readCrashReceipt(crashPhase, id);
        M5CollapseWindows.expectAtRestart(run, fixture, window, halt);
        var expected = M5CollapseRun.text(halt.getAsJsonObject("atHalt"), "recordAtHalt");
        if (expected != null) {
            var current = run.journal().flushedRecords().get(fixture.sid());
            run.expect("recordKeptAcrossTheRestart", current != null && M5CollapseFixture.recordDigest(current).equals(expected),
                    () -> "開服時的 record " + (current == null ? "ABSENT" : M5CollapseFixture.recordDigest(current)) + " 與 halt 當下不同：" + expected);
        }
        run.expect("chamberLoadedBeforeTheFirstTick", fixture.loadAroundTheChamberNow(run.source(), CHAMBER_LOAD_RADIUS), () -> "原艙附近沒有都載入到 FULL");
    }

    /** 這條 chain 的窗口不得要求 JOIN 之前載入投影（這裡的 restart 不做；窗口表的規則允許 COLLAPSING 的列指定它，w9cd 就是 COLLAPSING）。在 {@code onInitialize} 丟出。 */
    private static void requireNoProjectionLoad(CrashWindow id) {
        if (M5CollapseWindows.of(id).atRestart().recognizeBeforeJoin()) {
            throw new IllegalStateException("M5CollapseRetryChains 的 restart 不在 JOIN 之前載入投影：" + id);
        }
    }

    private Phase restartPhase(String crashPhase, CrashWindow id, List<Step> steps, M5CollapseRun.Checked afterStarted) {
        requireNoProjectionLoad(id);
        return Phase.stop(steps).started(() -> { started(crashPhase, id); afterStarted.run(); }).everyTick(() -> passage.invariants(null));
    }

    /** step：參與者真 JOIN 在來源世界的艙外（playerdata 是觸發之前存的）。 */
    private Step joinOutside() {
        return new Step("join", 1, () -> { fixture.joinAllInTheSourceWorld(Inside.OUTSIDE); return true; });
    }

    /** step：參與者由艙外進入艙內（下一個 tick 是 §7.1／§11.2 的邊緣觸發）。 */
    private Step enter() {
        return new Step("enter", 1, () -> { fixture.moveInside(); return true; });
    }

    /** RESOLVE 之後的共用部分：{@link M5CollapsePassage#steps} 的 resolve → ready → cross → ended（(b) 全員都已跨過）。 */
    private List<Step> untilTheEnd() {
        return passage.steps(null);
    }

    /**
     * W8 的 restart。開服時（第一個 tick 之前）：窗口表的斷言（record 是 DORMANT 的收據 exact、還沒有 Destination、還沒有配置任何東西）、同步載入原艙附近、參與者 JOIN 在
     * 艙內（crash 前存的位置）。之後：
     * <ol>
     * <li>{@code committedOnTheFirstObservation}：開服之後的第一次觀察就提交 COLLAPSING（「觸發=DORMANT 續行」的 INFO 一則），參與者沒有移動（不是由艙外進入的邊緣）。</li>
     * <li>{@code resolved}：RESOLVE 完成（U 物化、catalog 恰多 1 筆、discovery 一筆），參與者仍沒有移動。</li>
     * <li>{@code preparedWithoutAnEdge}：閘門第一次以位準評估就進 PREPARE（「進入 PREPARE」的 INFO 一則，參與者沒有移動）；同一個 step 拿掉 Controller 上方的紅石塊。</li>
     * <li>{@code leftPrepareOnPowerLoss}：PREPARE 的閘門不再成立、退回 WAITING；同一個 step 放回紅石塊。</li>
     * <li>{@code gateWaitsForAnEdge}：恢復供電、參與者一直在艙內，{@link #WATCH_TICKS} 個 tick 都沒有再進 PREPARE（之後只看邊緣觸發）；之後參與者離開艙內。</li>
     * <li>參與者由艙外再進入 → PREPARE → PASSAGE_READY → 跨越 → (b) 終局；終局時「進入 PREPARE」恰兩則（{@code preparedAgainOnlyAfterTheEdge}）。</li>
     * </ol>
     */
    Phase w8Restart(String crashPhase) {
        var steps = new ArrayList<Step>();
        steps.add(new Step("committedOnTheFirstObservation", 5, this::committedOnTheFirstObservation));
        steps.add(new Step("resolved", 400, this::resolvedWithoutMoving));
        steps.add(new Step("preparedOnTheLevel", PREPARE_WAIT_TICKS + 5, this::preparedOnTheLevel));
        steps.add(new Step("leftPrepare", 40, this::leftPrepareWithoutPower));
        steps.add(new Step("gateWaitsForAnEdge", WATCH_TICKS + 5, this::gateWaitsForAnEdge));
        steps.add(enter());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> {
            var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
            run.expect("preparedAgainOnlyAfterTheEdge", prepares == 2, () -> "「進入 PREPARE」=" + prepares);
            return End.crossed(fixture);
        }));
        return restartPhase(crashPhase, CrashWindow.DORMANT_STEP, steps, this::joinInsideBeforeTheFirstTick);
    }

    /** W8 的開服：參與者 JOIN 在艙內（第一個 tick 之前），這時 record 仍是 DORMANT 的收據（{@code dormantWhenJoined}）；記下 JOIN 的位置。 */
    private void joinInsideBeforeTheFirstTick() {
        fixture.joinAllInTheSourceWorld(Inside.INSIDE);
        joinedAt = fixture.players().getFirst().getPos();
        run.expect("dormantWhenJoined", fixture.receipt().equals(run.journal().flushedRecords().get(fixture.sid())) && run.ticks() == 0,
                () -> "JOIN 時 tick=" + run.ticks() + " state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
        run.proof.put("joinedAt", joinedAt.toString());
    }

    /** 參與者在艙內，位置與 JOIN 時相同（沒有移動過，所以之後的提交與 PREPARE 不是由艙外進入的邊緣）。 */
    private boolean notMovedSinceTheJoin() {
        var player = fixture.players().getFirst();
        return M5CollapseFixture.inside(player) && player.getPos().equals(joinedAt);
    }

    private boolean committedOnTheFirstObservation() {
        var sid = fixture.sid();
        var record = run.journal().flushedRecords().get(sid);
        boolean committed = record != null && record.state() == SessionState.COLLAPSING;
        // 最多等 3 個 tick，之後仍沒有提交就由這個具名斷言判 FAIL（不是 stepBounded）：mutation 讓開服之後的第一次觀察不觸發時，失敗在目標斷言。
        if (!committed && run.ticks() < 3) return false;
        var info = run.capture().matching(Level.INFO, "塌縮已提交", "觸發=DORMANT 續行", sid.toString()).size();
        boolean still = notMovedSinceTheJoin();
        run.expect("committedOnTheFirstObservation", committed && info == 1 && still && M5CollapseFixture.destination(record).equals(Optional.of(fixture.universe())),
                () -> "開服之後 " + run.ticks() + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), sid) + " 提交的 INFO=" + info + " 沒有移動=" + still);
        run.proof.put("committedAtPhaseTick", run.ticks());
        return true;
    }

    private boolean resolvedWithoutMoving() throws Exception {
        if (run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", fixture.sid().toString()).isEmpty()) return false;
        run.expect("materialized", fixture.universeWorld().isPresent(), () -> "RESOLVE 完成但 U 沒有物化");
        fixture.expectCatalog("catalogExactlyOneMore");
        run.expect("universeDiscoveredOnce", () -> M5CollapseDiskEvidence.discovery(run.saveRoot()).stream()
                .filter(record -> record.universeId().equals(fixture.universe())).count() == 1, () -> "discovery 沒有恰一筆 U");
        run.expect("stillInsideSinceTheJoin", notMovedSinceTheJoin(), () -> "參與者在 " + fixture.players().getFirst().getPos() + "（JOIN 時 " + joinedAt + "）");
        return true;
    }

    private boolean preparedOnTheLevel() {
        var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
        // 最多等 PREPARE_WAIT_TICKS 個 tick，之後仍沒有進 PREPARE 就由這個具名斷言判 FAIL（mutation：閘門第一次也只看邊緣時，失敗在這裡）。
        if (prepares == 0 && ++waited < PREPARE_WAIT_TICKS) return false;
        boolean still = notMovedSinceTheJoin();
        run.expect("preparedWithoutAnEdge", prepares == 1 && still, () -> "RESOLVE 之後 " + waited + " 個 tick：「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
        // spec §7.3：PREPARE 進行到一半時閘門不再成立（來源艙沒有供電）就退回 WAITING。
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
        return true;
    }

    private boolean leftPrepareWithoutPower() {
        var sid = fixture.sid();
        var left = run.capture().matching(Level.INFO, "PREPARE 的閘門不再成立，退回 WAITING", sid.toString()).size();
        if (left == 0) return false;
        var record = run.journal().flushedRecords().get(sid);
        run.expect("leftPrepareOnPowerLoss", left == 1 && record != null && record.state() == SessionState.COLLAPSING && notMovedSinceTheJoin(),
                () -> "退回 WAITING 的 INFO=" + left + " state=" + M5GameTestSupport.state(run.journal(), sid));
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        watched = 0;
        return true;
    }

    private boolean gateWaitsForAnEdge() {
        var sid = fixture.sid();
        var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", sid.toString()).size();
        var record = run.journal().flushedRecords().get(sid);
        boolean still = notMovedSinceTheJoin();
        run.expect("gateWaitsForAnEdge", prepares == 1 && record != null && record.state() == SessionState.COLLAPSING && still,
                () -> "恢復供電之後第 " + watched + " 個 tick：「進入 PREPARE」=" + prepares + " state=" + M5GameTestSupport.state(run.journal(), sid) + " 沒有移動=" + still);
        if (++watched < WATCH_TICKS) return false;
        run.expect("poweredWhileWaiting", run.source().isReceivingRedstonePower(FRAME.controllerPos()), () -> "恢復供電之後 Controller 沒有紅石輸入");
        fixture.moveOutside();
        return true;
    }

    /**
     * W9 的 restart：開服時窗口表的斷言（SEALED(MATERIALIZE_FAILED) 與 halt 當下相同、catalog 恰多 U 一筆而且是 DISABLED、開服 bootstrap 沒有物化它、開服沒有停止）→ 參與者 JOIN
     * 在艙外 → 由艙外進入 → 重試一次（{@code retriedOnceAfterTheRestart}）→ RESOLVE 把 DISABLED 視為 §8.3 已完成（{@code disabledReenabledOnTheRetry}：改回 EAGER_ENABLED、
     * 物化、目的地確定，不是 AUTHORITY_CORRUPT）→ PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase w9Restart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), enter(), retried(SealedReason.MATERIALIZE_FAILED, 1),
                new Step("reenabled", 400, () -> reenabled("disabledReenabledOnTheRetry", 1))));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.MATERIALIZE_SEALED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * W9 的變體 (a)（F4a I-1 的跨重啟版）：開服時同 {@link #w9Restart}；參與者 JOIN 在艙外之後，讓另一個 session 的 allocation 追加讀回失敗（{@code otherAppendUnconfirmed}：
     * 已落地、ledger 未確認、以權威為單位退避）→ 由艙外進入 → 重試一次 → RESOLVE 第 1 步因 ledger 未確認封存為 ALLOCATION_IO_FAILED，改回 EAGER_ENABLED 之前
     * （{@code firstRetrySealedOnTheAllocation}：catalog 仍是 DISABLED、沒有物化）→ 參與者離開，退避期過後再進入 → 第二次重試 → ledger 收斂、RESOLVE 仍把 DISABLED 視為 §8.3
     * 已完成（{@code disabledReenabledAfterTheAllocationRetry}）→ PASSAGE_READY → 跨越 → (b) 終局（allocation ledger 另有那一筆，spec §6 roll-forward 不移除）。
     */
    Phase w9AllocationRetryRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), new Step("otherAppend", 1, this::otherSessionAppendUnconfirmed), enter(),
                retried(SealedReason.MATERIALIZE_FAILED, 1), new Step("sealedOnTheAllocation", 20, this::sealedOnTheUnconfirmedAllocation),
                new Step("backoff", BACKOFF_TICKS + 10, this::backoffElapsedThenEnter), retried(SealedReason.ALLOCATION_IO_FAILED, 2),
                new Step("reenabled", 400, () -> reenabled("disabledReenabledAfterTheAllocationRetry", 2))));
        steps.add(passage.resolve());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> End.crossed(fixture).withOtherAllocations(List.of(otherAllocation))));
        return restartPhase(crashPhase, CrashWindow.MATERIALIZE_SEALED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * W9 的變體 (b)：開服時是 COLLAPSING、catalog 是 DISABLED（§8.3 先寫 DISABLED、再寫 SEALED，兩者之間 crash）；開服沒有停止、bootstrap 沒有物化它。參與者 JOIN 在艙外；
     * 本 instance 的第一次 RESOLVE 就是 §8.3 的重試（{@code firstResolveReenabledTheDisabled}：不進 SEALED，改回 EAGER_ENABLED、物化、目的地確定）→ 進入艙內 → PASSAGE_READY →
     * 跨越 → (b) 終局。
     */
    Phase w9CollapsingDisabledRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), new Step("reenabled", 400, () -> reenabled("firstResolveReenabledTheDisabled", 0))));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.CATALOG_DISABLED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * F6-2-07 的 restart：開服時窗口表的斷言（SEALED(AUTHORITY_UNREADABLE) 與 halt 當下相同、catalog 有 U 而且開服 bootstrap 已物化、discovery 沒有 U）→ 參與者 JOIN 在艙外 →
     * 由艙外進入 → 開服之後第一次重試（{@code retriedOnceAfterTheRestart}）→ RESOLVE 追加 discovery → PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase unreadableRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), enter(), retried(SealedReason.AUTHORITY_UNREADABLE, 1)));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.SEALED_STEP, steps, M5CollapseRun.NOTHING);
    }

    /**
     * step：由 SEALED({@code reason}) 重試（「SEALED 重試：SEALED(reason)→COLLAPSING」的 INFO 一則），這時這個 session 的重試恰為第 {@code count} 次（第一次
     * {@code retriedOnceAfterTheRestart}，step {@code retried}；之後 {@code retriedAgainAfterTheBackoff}，step {@code retriedAgain}：receipt 的 witness 以 step 名稱為鍵，不能重複）。
     */
    private Step retried(SealedReason reason, int count) {
        return new Step(count == 1 ? "retried" : "retriedAgain", 10, () -> {
            var sid = fixture.sid().toString();
            var fromReason = run.capture().matching(Level.INFO, "SEALED 重試：SEALED(" + reason + ")→COLLAPSING", sid).size();
            if (fromReason == 0) return false;
            var all = run.capture().matching(Level.INFO, "SEALED 重試", sid).size();
            run.expect(count == 1 ? "retriedOnceAfterTheRestart" : "retriedAgainAfterTheBackoff", fromReason == 1 && all == count,
                    () -> "SEALED(" + reason + ") 的重試 INFO=" + fromReason + " 全部的重試 INFO=" + all + "，預期第 " + count + " 次");
            return true;
        });
    }

    /**
     * RESOLVE 的結果（W9 與兩個變體共用）：等到「目的地已確定」，或重試之後又離開 COLLAPSING（修改前會進 SEALED(AUTHORITY_CORRUPT)）。斷言 {@code name}：目的地已確定恰一則、
     * record 是 COLLAPSING，catalog 的這筆已改回 EAGER_ENABLED、U 已物化，沒有 AUTHORITY_CORRUPT（這個 session 沒有 ERROR、也沒有提到它的 WARN），allocation ledger 已確認；
     * 這個 session 的重試恰為 {@code retries} 次（w9cd 是 0：開服時就是 COLLAPSING，沒有經過 SEALED）。
     */
    private boolean reenabled(String name, int retries) {
        var sid = fixture.sid();
        var resolved = run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).size();
        var record = run.journal().flushedRecords().get(sid);
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
        if (resolved == 0 && (retried < retries || (record != null && record.state() == SessionState.COLLAPSING))) return false;
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords().get(fixture.universe());
        var errors = run.capture().matching(Level.ERROR, sid.toString()).size();
        var corrupt = run.capture().matching(Level.WARN, "AUTHORITY_CORRUPT", sid.toString()).size();
        var sealedWarns = retries == 0 ? run.capture().matching(Level.WARN, "塌縮進入 SEALED", sid.toString()).size() : 0;
        boolean confirmed = !CollapseAuthorities.get(run.server()).unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER);
        run.expect(name, resolved == 1 && retried == retries && record != null && record.state() == SessionState.COLLAPSING && M5CollapseFixture.eagerEnabled(catalog)
                        && fixture.universeWorld().isPresent() && errors == 0 && corrupt == 0 && sealedWarns == 0 && confirmed,
                () -> "目的地已確定=" + resolved + " 重試=" + retried + " state=" + M5GameTestSupport.state(run.journal(), sid) + " SEALED 的原因="
                        + (record == null ? "-" : record.collapse().sealedReason()) + " catalog=" + catalog + " ERROR=" + errors + " AUTHORITY_CORRUPT 的 WARN=" + corrupt
                        + " SEALED 的 WARN=" + sealedWarns + " ledger 已確認=" + confirmed);
        return true;
    }

    /**
     * W9 (a)：另一個 session 的 NEW 在 RESOLVE 第 1 步追加 allocation entry 時，atomic move 之後的讀回失敗（{@code CHECKED_READ_FAULT}，一次；比照 DRT 的
     * {@code disabledRecordOfTheSessionIsRetriedRegardlessOfTheLastSealedReason} 以 {@code lookupOrAppendAllocation(otherIntent)} 造出）：結果是 WRITE_FAILED、ledger 未確認、
     * 那一筆已落地（spec §6 roll-forward：之後的寫出帶著它，不移除）。
     */
    private boolean otherSessionAppendUnconfirmed() throws Exception {
        var authorities = CollapseAuthorities.get(run.server());
        readFault = run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseAllocationLedger.FILE_NAME, 0, 1));
        byte[] token = new byte[32], seed = new byte[32];
        var random = new SecureRandom();
        random.nextBytes(token);
        random.nextBytes(seed);
        var intent = new NewUniverseIntent(new CandidateBytes(token), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, new CandidateBytes(seed));
        var lookup = authorities.lookupOrAppendAllocation(intent);
        otherAllocation = new M5CollapseDiskEvidence.Allocation(HexFormat.of().formatHex(token), UniverseAllocationLedger.deriveUniverseId(intent.allocationToken()).value());
        var shown = new LinkedHashMap<String, Object>();
        shown.put("token", otherAllocation.token());
        shown.put("universeId", otherAllocation.universeId().toString());
        shown.put("status", lookup.status().name());
        run.proof.put("otherAllocation", shown);
        run.expect("otherAppendUnconfirmed", lookup.status() == CollapseAuthorities.Lookup.Status.WRITE_FAILED && readFault.hits() == 1
                        && authorities.unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER),
                () -> "另一個 session 的追加=" + lookup.status() + " 注入次數=" + readFault.hits());
        run.expect("otherAppendLanded", () -> M5CollapseDiskEvidence.allocations(run.data()).equals(List.of(fixture.allocation(), otherAllocation)),
                () -> "allocation ledger=" + fixture.allocationsOrError());
        return true;
    }

    /** W9 (a)：重試的 RESOLVE 在第 1 步封存為 ALLOCATION_IO_FAILED，catalog 的這筆仍是 DISABLED、U 沒有物化（改回 EAGER_ENABLED 之前）；之後參與者離開艙內。 */
    private boolean sealedOnTheUnconfirmedAllocation() {
        var sid = fixture.sid();
        var record = run.journal().flushedRecords().get(sid);
        if (record == null || record.state() != SessionState.SEALED) return false;
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords().get(fixture.universe());
        run.expect("firstRetrySealedOnTheAllocation", record.collapse().sealedReason().equals(Optional.of(SealedReason.ALLOCATION_IO_FAILED))
                        && catalog != null && catalog.desiredAvailability() == DesiredAvailability.DISABLED && fixture.universeWorld().isEmpty(),
                () -> "SEALED 的原因=" + record.collapse().sealedReason() + " catalog=" + catalog + " U 已物化=" + fixture.universeWorld().isPresent());
        markTick = run.server().getTicks();
        fixture.moveOutside();
        return true;
    }
}
