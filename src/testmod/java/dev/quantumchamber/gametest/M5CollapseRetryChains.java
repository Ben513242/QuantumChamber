package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseBackoff;
import dev.quantumchamber.collapse.CollapseServiceTestAccess;
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
import net.minecraft.world.GameRules;
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
 * <li>{@code w8late}（H7，spec v5.9 §7.1「來源艙還沒載入時，這次觸發不消耗」、§4 H7）：crash 同 {@code w8}。restart 不預先載入原艙附近、spawn chunks 的半徑改為 0，等原艙附近
 *     卸載之後參與者才 JOIN 在艙內（實際的 server：玩家在開服之後才連線，原艙的 chunk 在 JOIN 之後幾個 tick 才載入）；不離開再進入就提交 COLLAPSING，續行到 (b) 終局。</li>
 * <li>{@code unreadablelate}（H7 的 SEALED 版，§11.2 的重試觸發）：crash 同 {@code unreadable}，另在參與者在艙內時原生存檔；restart 同 {@code w8late} 的條件，不離開再進入就
 *     重試一次，續行到 (b) 終局。</li>
 * <li>{@code waitinglate}（H7 的 WAITING 版，§7.3 的閘門）：crash 同 {@code w5}（DISCOVERY_APPENDED），只是 level.dat 帶著半徑 0 的 spawn chunks；restart 開服時原艙附近
 *     還沒有載入、參與者在第一個 tick 之前已在艙內，閘門第一次評估時來源 ticket 的 chunk 還在載入；不離開再進入就進 PREPARE，續行到 (b) 終局。</li>
 * </ul>
 * restart 開服時都以 probe 的 ticket 同步載入原艙附近（{@link M5CollapseFixture#loadAroundTheChamberNow}；H7 的三條除外）：來源艙已載入是 §7.1／§11.2 觸發成立的條件之一，
 * 載入在第一次觀察之前完成，這幾條 chain 驗的是觸發之後的續行；載入晚於觸發的情況由 {@code w8late}、{@code unreadablelate}（{@link ChamberAtStart#UNLOADED}）與
 * {@code waitinglate} 驗。
 * <p>放在新的類別（不併入 {@link M5CollapsePassageChains}）：這幾條 chain 的 halt 當下不是 PASSAGE_READY 或終局，而是 DORMANT、SEALED 或 COLLAPSING＋DISABLED，restart 都要先由
 * 觸發（或開服之後的第一次 RESOLVE）續行，再接 {@link M5CollapsePassage} 的 ready／cross／ended；通道的 chain 則是開服時就結束通道。
 * <p>F6-2d-2 Step 0（F6-2d-1 quality Minor 1、2，H7 quality 給 F6-2d-2 的建議）：觀察與等待的 tick 數由 {@link M5CollapsePassage#counting}／{@link M5CollapsePassage#watch}
 * 的區域計數器計（不再共用可變的 {@code watched}、{@code waited}、{@code triggerWaited}）；三個 restart builder 合成 {@link #restartPhase}（F6-2d-2 起以 {@link ChamberAtStart} 指定開服時原艙附近怎麼處理）；不離開再進入
 * 的提交與重試各合成一個參數化的 step（{@link #committedWithoutReentry}、{@link #retried}，等法由 {@link Await} 決定）。具名斷言與 step 的名稱都不變。
 */
final class M5CollapseRetryChains {
    /**
     * 封存之後等多少 tick 再由艙外進入：per-session 的退避與 ledger 收斂的退避的第一次延遲都是 {@link CollapseBackoff#INITIAL_TICKS}（spec §11.2 的 200 ticks），留 5 ticks 餘裕
     * （比照 G-DISABLED 的 {@code registered + 2 + 205}）。F6-2d-2 Step 0（F6-2d-1 quality nit）：由寫死的 205 改為指向 production 的常數（值不變）。
     */
    static final int BACKOFF_TICKS = CollapseBackoff.INITIAL_TICKS + 5;
    /** restart 開服時以 probe 的 ticket 同步載入的半徑（chunk；原艙與 {@code CollapseService.neighborhoodLoaded} 外擴的 3 格都在 Controller 所在 chunk 的半徑 1 之內）。 */
    private static final int CHAMBER_LOAD_RADIUS = 1;
    /** w8：PREPARE 等多少 tick（RESOLVE 之後閘門第一次以位準評估就該成立；超過時由具名斷言判 FAIL，不是 stepBounded）。 */
    private static final int PREPARE_WAIT_TICKS = 10;
    /** H7（{@code w8late}、{@code unreadablelate}）的 restart：等原艙附近卸載的上限（spawn chunks 的半徑改為 0 之後，原版在下一次 chunk 更新就把它降到 FULL 以下）。 */
    private static final int UNLOAD_WAIT_TICKS = 400;
    /**
     * H7：參與者 JOIN 在艙內之後，最多等多少 tick 觸發（原艙附近在 JOIN 之後幾個 tick 才由玩家的 ticket 載入；超過時由具名斷言判 FAIL，不是 stepBounded：修正之前的
     * RED 失敗在目標斷言）。
     */
    private static final int LATE_TRIGGER_TICKS = 20;
    /**
     * W8：開服之後第一次觀察的提交最多等幾個 tick（{@link Await#FIRST_OBSERVATION}；F6-2d-2 Step 0，F6-2d-1 quality nit：原本是 {@code committedOnTheFirstObservation} 的行內
     * 常數 3）。
     */
    private static final int FIRST_OBSERVATION_TICKS = 3;

    /**
     * 觸發之後的 step 怎麼等（F6-2d-2 Step 0，H7 quality 給 F6-2d-2 的建議：{@code committedOnTheFirstObservation}／{@code committedWithoutReentry}、
     * {@code retried(reason, count)}／{@code retriedWithoutReentry} 各合成一個參數化的 step，{@link #committedWithoutReentry}、{@link #retried}）。每一種都最多等 {@code waitTicks}
     * 個 tick，之後仍沒有發生就由具名斷言判 FAIL（不是 stepBounded：mutation 讓觸發落空時，失敗在目標斷言）。{@link #EDGE} 之外都是參與者 JOIN 之後<b>不離開再進入</b>：另斷言
     * 參與者沒有移動（{@link #notMovedSinceTheJoin}）、記下發生的 tick。F6-2d-2（F6-2d-2 Step 0 quality nit）：{@link #EDGE} 原本是 {@code (10, 0)}、以 {@code this == EDGE}
     * 另外處理（{@code waitTicks} 0 是沒用到的 sentinel，等到 stepBounded 為止）；改成 {@code waitTicks} 等於 step 的上限，等待的時間相同，只是到時仍沒有發生時失敗在具名斷言。
     * <p>F6-2d-2 B1（A 段 quality Minor 4）：只留一個上限 {@code waitTicks}，step 的上限就是它。原本另有 {@code maxTicks}（{@code FIRST_OBSERVATION} 5、{@code AFTER_THE_LOAD} 25），
     * 但 step 一定在第 {@code waitTicks} 個 tick 完成（{@link #keepWaiting} 為假時就斷言、回 true），多出的餘裕永遠到不了，兩者之間也沒有檢查。等待的 tick 數與通過條件都不變。
     */
    private enum Await {
        /** 由艙外進入（{@link #enter}）之後：最多等 10 個 tick，不斷言位置。 */
        EDGE(10),
        /** W8：原艙在開服時已載入，開服之後的第一次觀察就觸發。 */
        FIRST_OBSERVATION(FIRST_OBSERVATION_TICKS),
        /** H7：JOIN 時原艙附近還沒有載入，載入之後才觸發；另記下原艙附近載入完成的 tick（{@code chamberLoadedAtPhaseTick}）。 */
        AFTER_THE_LOAD(LATE_TRIGGER_TICKS);

        /** 最多等幾個 tick（也是 step 的上限）：到第 {@code waitTicks} 個 tick 仍沒有發生時，由具名斷言判 FAIL。 */
        final int waitTicks;

        Await(int waitTicks) {
            this.waitTicks = waitTicks;
        }

        /** 還沒有發生、而且還在等待的時間內（{@code tick} 是這個 step 的第幾個 tick，由 1 起）：這個 tick 先不斷言。 */
        boolean keepWaiting(boolean happened, int tick) {
            return !happened && tick < waitTicks;
        }
    }

    /** {@link #reenabled} 是否另斷言本 instance 沒有這個 session 的「塌縮進入 SEALED」WARN（F6-2d-2 Step 0，F6-2d-1 quality nit：原本以 {@code retries == 0} 代表）。 */
    private enum SealWarns {
        /** 本 instance 不應進過 SEALED（w9cd：開服時就是 COLLAPSING，第一次 RESOLVE 就改回）。 */
        NONE_EXPECTED,
        /** 不檢查（w9、w9io：開服時是 SEALED；w9io 在本 instance 另封存過一次）。 */
        NOT_CHECKED
    }

    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** unreadable 的 crash、w9io 的 restart：probe 安裝的 checked 讀取失敗（命中次數）。 */
    private M5TestHooks.CheckedReadFault readFault;
    /** 封存的 server tick（unreadable 的 crash、w9io 的 restart：之後等過退避期）。 */
    private int markTick = -1;
    /** unreadable 的 crash：封存之後的 record（之後觀察它沒有變）。 */
    private SessionRecoveryRecord sealedRecord;
    /** w8 的 restart：參與者 JOIN 時的位置（之後斷言沒有移動）。 */
    private Vec3d joinedAt;
    /** w9io 的 restart：另一個 session 已落地的 allocation entry（spec §6 roll-forward：不移除）。 */
    private M5CollapseDiskEvidence.Allocation otherAllocation;
    /** restart 開服時這個 session 的 record（{@link #started} 無條件記下，F6-2d-2 B1；H7 的 JOIN 時斷言還沒有觸發）。 */
    private SessionRecoveryRecord atStart;

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
     * 當下就在 journal 的 session，第一次觀察到在艙內才算觸發），所以觀察 {@link M5CollapsePassage#WATCH_TICKS} 個 tick 都沒有提交（{@code notCommittedWithoutAnEdge}）；之後
     * 裝上 crash 點，塌縮流程下一次處理這個 DORMANT 之前 halt。
     */
    Phase w8Crash() {
        var steps = new ArrayList<>(origin());
        steps.add(new Step("inside", 1, () -> { fixture.moveInside(); return true; }));
        steps.add(new Step("receipt", 1, () -> { fixture.writeCrashReceipt(fixture.dormantNewReceipt()); return true; }));
        steps.add(M5CollapsePassage.watch("notCommitted", M5CollapsePassage.WATCH_TICKS, tick -> notCommittedWithoutAnEdge()));
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
     * 離開艙內、等過 per-session 的退避期再由艙外進入 → 觀察 {@link M5CollapsePassage#WATCH_TICKS} 個 tick：同一個 instance 內不重試（{@code notRetriedInTheSameInstance}，spec §11.2 的
     * restart-only）→ 裝上 crash 點，塌縮流程下一次處理這個 SEALED 之前 halt。
     */
    Phase unreadableCrash() {
        return crashPhase(unreadableSteps(false));
    }

    /**
     * H7 的 {@code unreadablelate} 的 crash：同 {@link #unreadableCrash}，裝上 crash 點之前另在參與者在艙內時原生存檔（{@code savedInside}：playerdata 在艙內，restart 才能
     * JOIN 在艙內）。同一個 instance 內 AUTHORITY_UNREADABLE 不重試，所以存檔時仍是同一筆 SEALED。
     */
    Phase unreadableLateCrash() {
        return crashPhase(unreadableSteps(true));
    }

    /** {@link #unreadableCrash} 與 {@link #unreadableLateCrash} 的 step；{@code savedInside} 時在裝上 crash 點之前插入 {@code savedInside}。 */
    private List<Step> unreadableSteps(boolean savedInside) {
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
        steps.add(M5CollapsePassage.watch("notRetried", M5CollapsePassage.WATCH_TICKS, tick -> notRetriedInTheSameInstance()));
        if (savedInside) steps.add(new Step("savedInside", 1, this::savedInside));
        steps.add(arm(CrashWindow.SEALED_STEP));
        steps.add(new Step("halt", 5, () -> false));
        return steps;
    }

    /** H7 的 {@code unreadablelate} 的 crash：參與者在艙內（{@code notRetried} 之後）時原生存檔，playerdata 在艙內。 */
    private boolean savedInside() {
        run.expect("insideWhenSaved", fixture.players().stream().allMatch(M5CollapseFixture::inside), () -> "存檔時參與者不在艙內");
        run.expect("nativeSaveInside", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
        return true;
    }

    /** step：依窗口表裝上 {@code id} 的 crash 點（觀察完之後才裝，所以 halt 一定在觀察之後）。 */
    private Step arm(CrashWindow id) {
        return new Step("arm", 1, () -> { M5CollapseWindows.arm(run, fixture, id); return true; });
    }

    /**
     * W8 的 crash：每個 tick record 都與收據 exact 相同（flushed 與 current）、沒有「塌縮已提交」的 INFO、參與者在艙內（{@code notCommittedWithoutAnEdge}）；由 step
     * {@code notCommitted}（{@link M5CollapsePassage#watch}）觀察 {@link M5CollapsePassage#WATCH_TICKS} 個 tick。
     */
    private void notCommittedWithoutAnEdge() {
        var sid = fixture.sid();
        var committed = run.capture().matching(Level.INFO, "塌縮已提交", sid.toString()).size();
        boolean inside = fixture.players().stream().allMatch(M5CollapseFixture::inside);
        run.expect("notCommittedWithoutAnEdge", fixture.receipt().equals(run.journal().flushedRecords().get(sid)) && fixture.receipt().equals(run.journal().records().get(sid))
                && committed == 0 && inside, () -> "state=" + M5GameTestSupport.state(run.journal(), sid) + " 提交的 INFO=" + committed + " 在艙內=" + inside);
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
     * INFO、參與者在艙內（{@code notRetriedInTheSameInstance}）；由 step {@code notRetried}（{@link M5CollapsePassage#watch}）觀察 {@link M5CollapsePassage#WATCH_TICKS} 個 tick。
     */
    private void notRetriedInTheSameInstance() {
        var sid = fixture.sid();
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
        boolean inside = fixture.players().stream().allMatch(M5CollapseFixture::inside);
        run.expect("notRetriedInTheSameInstance", sealedRecord.equals(run.journal().flushedRecords().get(sid)) && sealedRecord.equals(run.journal().records().get(sid))
                && retried == 0 && inside, () -> "state=" + M5GameTestSupport.state(run.journal(), sid) + " 重試的 INFO=" + retried + " 在艙內=" + inside);
    }

    // ---- restart phase ----

    /**
     * restart 開服時（共用）：讀回 crash phase 的收據、依窗口表斷言磁碟上的狀態（窗口列宣告 {@code withRecordKept} 時，窗口表另比對開服時的 record 與 halt 當下的摘要相同，
     * {@code recordKeptAcrossTheRestart}；F6-2d-2 Step 0 起由窗口表斷言，原本這裡只在 halt receipt 有摘要時比對），記下開服時這個 session 的 record（{@link #atStart}；
     * F6-2d-2 B1，A 段 quality Minor 2：原本只在 {@link ChamberAtStart#UNLOADED} 記）；之後依 {@code chamber}（{@link ChamberAtStart}）處理原艙附近：
     * {@link ChamberAtStart#PRELOADED} 以 probe 的 ticket 同步載入（{@code chamberLoadedBeforeTheFirstTick}）；{@link ChamberAtStart#UNLOADED} 把 spawn chunks 的半徑改為 0
     * （原版的 gamerule callback 移除 START ticket；fixture 的 Controller 在 spawn chunks 內，不這樣做原艙一直是載入的）；{@link ChamberAtStart#AS_SAVED} 不處理。
     * <p>F6-2d-2 B1：A 段 quality Minor 1，改成 exhaustive 的 switch expression（新增 {@link ChamberAtStart} 的值卻忘了 case 時不能編譯；原本的 switch statement 照樣編譯、
     * 執行時什麼都不做）。A 段 spec review I-1，卸載內聯在這裡的 case {@code UNLOADED}：原本的 {@code unloadTheChamberAtStart} 與 {@link M5CollapseRun.Checked} 同形，
     * {@code restartPhase(..., PRELOADED, steps, this::unloadTheChamberAtStart)} 仍能編譯；現在沒有可交給 {@code afterStarted} 的卸載 helper，那樣寫編譯失敗。
     */
    private void started(String crashPhase, CrashWindow id, ChamberAtStart chamber) throws Exception {
        var window = M5CollapseWindows.of(id);
        var halt = fixture.readCrashReceipt(crashPhase, id);
        M5CollapseWindows.expectAtRestart(run, fixture, window, halt);
        atStart = run.journal().flushedRecords().get(fixture.sid());
        M5CollapseRun.Checked aroundTheChamber = switch (chamber) {
            case PRELOADED -> () -> run.expect("chamberLoadedBeforeTheFirstTick", fixture.loadAroundTheChamberNow(run.source(), CHAMBER_LOAD_RADIUS),
                    () -> "原艙附近沒有都載入到 FULL");
            case UNLOADED -> () -> run.source().getGameRules().get(GameRules.SPAWN_CHUNK_RADIUS).set(0, run.server());
            case AS_SAVED -> M5CollapseRun.NOTHING;
        };
        aroundTheChamber.run();
    }

    /** 這條 chain 的窗口不得要求 JOIN 之前載入投影（這裡的 restart 不做；窗口表的規則允許 COLLAPSING 的列指定它，w9cd 就是 COLLAPSING）。在 {@code onInitialize} 丟出。 */
    private static void requireNoProjectionLoad(CrashWindow id) {
        if (M5CollapseWindows.of(id).atRestart().recognizeBeforeJoin()) {
            throw new IllegalStateException("M5CollapseRetryChains 的 restart 不在 JOIN 之前載入投影：" + id);
        }
    }

    /**
     * 這個類別的 restart phase（F6-2d-2 Step 0，H7 quality 給 F6-2d-2 的建議：原本 {@code restartPhase}、H7 的 {@code lateRestartPhase} 與 {@code waitingLateRestart} 內嵌的
     * 一份三個幾乎相同的 builder，合成這一個）：開服時 {@link #started}（依 {@link ChamberAtStart} 處理原艙附近），再 {@code afterStarted}；
     * 每個 tick {@link M5CollapsePassage#invariants}。窗口不得要求 JOIN 之前載入投影（{@link #requireNoProjectionLoad}，在 {@code onInitialize} 丟出）。
     */
    private Phase restartPhase(String crashPhase, CrashWindow id, ChamberAtStart chamber, List<Step> steps, M5CollapseRun.Checked afterStarted) {
        requireNoProjectionLoad(id);
        return Phase.stop(steps).started(() -> { started(crashPhase, id, chamber); afterStarted.run(); }).everyTick(() -> passage.invariants(null));
    }

    /**
     * restart 開服時原艙附近的 chunk 怎麼處理（{@link #restartPhase}、{@link #started}；F6-2d-2，F6-2d-2 Step 0 quality Minor 4：取代位置參數 {@code boolean preload}）。只管 chunk：
     * 開服時的 record 由 {@link #started} 無條件記下（F6-2d-2 B1，A 段 quality Minor 2）。卸載只由 {@link #started} 的 case {@code UNLOADED} 做，沒有可交給 {@code afterStarted}
     * 的卸載 helper，所以「預先載入又卸載」不能以 {@link #restartPhase} 的參數表達（F6-2d-2 B1，A 段 spec review I-1：F6-2d-2 的 {@code unloadTheChamberAtStart} 與
     * {@link M5CollapseRun.Checked} 同形，{@code PRELOADED} 配它當 {@code afterStarted} 仍能編譯）。
     */
    private enum ChamberAtStart {
        /** 以 probe 的 ticket 同步載入原艙附近，在第一個 tick 之前完成（{@code chamberLoadedBeforeTheFirstTick}）：W8、W9 與它的兩個變體、F6-2-07。 */
        PRELOADED,
        /**
         * 把 spawn chunks 的半徑改為 0（原版的 gamerule callback 移除 START ticket）。只移除 spawn ticket：開服當下原艙附近仍載入，之後的 tick 才卸載（DORMANT 與 SEALED 沒有
         * 來源 ticket），呼叫端要先等 {@code chamberUnloaded}。H7 的 {@code w8late}、{@code unreadablelate}。
         */
        UNLOADED,
        /**
         * 不載入也不卸載：原艙附近維持 level.dat 與 attach 留下的狀態（例如 {@code waitinglate} 的 level.dat 帶著半徑 0 的 spawn chunks，原艙附近只由 attach 為 COLLAPSING 取得
         * 的來源 ticket 載入）。
         */
        AS_SAVED
    }

    /**
     * H7：原艙附近是否已載入（艙體向水平各方向外擴 3 格的 chunk 都是 FULL）。F6-2d-2 Step 0（H7 quality Minor 2）起直接呼叫 production 的
     * {@code CollapseService.neighborhoodLoaded}（package-private，經 testmod 的 split package {@link CollapseServiceTestAccess}；原本在這裡複製一份判斷，Javadoc 誤寫
     * 「testmod 讀不到」）。只查詢，不加 ticket、不載入。
     */
    private boolean chamberLoaded() {
        return CollapseServiceTestAccess.neighborhoodLoaded(run.source(), FRAME);
    }

    /** H7 的 step {@code chamberUnloaded}：原艙附近已不是全部 FULL（spawn chunks 的 ticket 移除之後）。 */
    private boolean chamberUnloaded() {
        if (chamberLoaded()) return false;
        run.proof.put("chamberUnloadedAtPhaseTick", run.ticks());
        return true;
    }

    /**
     * H7 的 step {@code join}：參與者 JOIN 在艙內（crash 前存的位置），斷言 JOIN 時原艙附近還沒有載入（{@code chamberNotLoadedAtTheJoin}：否則量不到 H7 的情況），而且 record
     * 與開服時相同（{@code unchangedBeforeTheJoin}：還沒有觸發）；記下 JOIN 的位置（之後斷言沒有移動）。
     */
    private Step joinInsideLate() {
        return new Step("join", 1, () -> {
            boolean loaded = chamberLoaded();
            var record = run.journal().flushedRecords().get(fixture.sid());
            fixture.joinAllInTheSourceWorld(Inside.INSIDE);
            joinedAt = fixture.players().getFirst().getPos();
            run.expect("chamberNotLoadedAtTheJoin", !loaded, () -> "JOIN 時原艙附近已載入，量不到「來源艙還沒載入時的觸發」");
            run.expect("unchangedBeforeTheJoin", atStart != null && atStart.equals(record), () -> "JOIN 之前 state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
            run.proof.put("joinedAtPhaseTick", run.ticks());
            run.proof.put("joinedAt", joinedAt.toString());
            return true;
        });
    }

    /**
     * H7 的 {@code w8late} 的 restart（crash 同 {@link #w8Crash}）：開服時不預先載入原艙附近（{@link ChamberAtStart#UNLOADED}）→ 原艙附近卸載（{@code chamberUnloaded}）→
     * 參與者 JOIN 在艙內，這時原艙附近還沒有載入 → 參與者<b>不離開再進入</b>，在 {@link #LATE_TRIGGER_TICKS} 個 tick 內提交 COLLAPSING（{@code committedWithoutReentry}，spec
     * v5.9 §7.1：來源艙還沒載入時這次觸發不消耗）→ RESOLVE → PREPARE → PASSAGE_READY → 跨越 → (b) 終局（spec §12 的四件事由 {@link M5CollapsePassage} 斷言）。修正之前，JOIN
     * 的觸發在來源艙還沒載入時被消耗，要離開再進入才續行（F6-2d-1 的 {@code obs-w8-late-join}）。
     */
    Phase w8LateRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(new Step("chamberUnloaded", UNLOAD_WAIT_TICKS, this::chamberUnloaded), joinInsideLate(),
                committedWithoutReentry("committed", "committedWithoutReentry", Await.AFTER_THE_LOAD)));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.DORMANT_STEP, ChamberAtStart.UNLOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * H7 的 {@code unreadablelate} 的 restart（crash 是 {@link #unreadableLateCrash}）：開服時（窗口表的斷言同 {@link #unreadableRestart}）不預先載入原艙附近
     * （{@link ChamberAtStart#UNLOADED}）→ 原艙附近卸載 → 參與者 JOIN 在艙內，這時原艙附近還沒有載入 → 參與者<b>不離開再進入</b>，在 {@link #LATE_TRIGGER_TICKS} 個 tick 內
     * 由 SEALED(AUTHORITY_UNREADABLE) 重試一次（{@code retriedWithoutReentry}，spec §11.2：開服之後的第一次觸發，來源艙還沒載入時不消耗）→ RESOLVE 追加 discovery →
     * PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase unreadableLateRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(new Step("chamberUnloaded", UNLOAD_WAIT_TICKS, this::chamberUnloaded), joinInsideLate(),
                retried("retried", "retriedWithoutReentry", SealedReason.AUTHORITY_UNREADABLE, 1, Await.AFTER_THE_LOAD)));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.SEALED_STEP, ChamberAtStart.UNLOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * H7 的 {@code waitinglate} 的 crash（WAITING 的閘門）：同 {@link M5CollapseDormantChains#crash} 的 {@code DISCOVERY_APPENDED}（DORMANT 的 NEW 收據 → 參與者由艙外進入 →
     * COLLAPSING → RESOLVE 追加 discovery 之後 halt），只是收據的原生存檔之前把 spawn chunks 的半徑改為 0（level.dat 帶著它：restart 開服時原艙附近沒有載入，只有 attach 為
     * COLLAPSING 取得的來源 ticket 會載入它）。參與者在艙外附近，crash phase 的原艙一直由他的 ticket 載入。
     */
    Phase waitingLateCrash() {
        var steps = new ArrayList<>(origin());
        steps.add(new Step("receipt", 1, () -> {
            run.source().getGameRules().get(GameRules.SPAWN_CHUNK_RADIUS).set(0, run.server());
            fixture.writeCrashReceipt(fixture.dormantNewReceipt());
            M5CollapseWindows.arm(run, fixture, CrashWindow.DISCOVERY_APPENDED);
            return true;
        }));
        steps.add(enter());
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("halt", 400, () -> false));
        return crashPhase(steps);
    }

    /**
     * H7 的 {@code waitinglate} 的 restart（spec v5.9 §7.3：開服之後的第一次評估遇到來源艙還沒載入時，不算評估過）：開服時是 COLLAPSING（目的地的權威都已確認），原艙附近
     * 沒有載入（{@code chamberNotLoadedAtStart}；來源 ticket 是 attach 剛加的）。第一個 tick 之前參與者 JOIN（艙外，crash 前存的位置）並移到艙內
     * （{@code insideBeforeTheFirstTick}）；RESOLVE 在第一個 tick 重新確認，閘門在下一個 tick 第一次評估，這時來源 ticket 的 chunk 通常還在載入。參與者<b>不離開再進入</b>，在
     * {@link #LATE_TRIGGER_TICKS} 個 tick 內進入 PREPARE（{@code preparedWithoutReentry}）→ PASSAGE_READY → 跨越 → (b) 終局。修正之前，第一次評估在來源艙還沒載入時就算評估過
     * （記下在艙內的人），要離開再進入才進 PREPARE（H7 的觀察 {@code obs-waiting-2}）。來源艙載入得比閘門第一次評估早時，這條 chain 驗不到落空（GREEN 仍成立），所以
     * 另記下 RESOLVE 完成、原艙附近載入完成與進入 PREPARE 的 tick。
     */
    Phase waitingLateRestart(String crashPhase) {
        var id = CrashWindow.DISCOVERY_APPENDED;
        var steps = new ArrayList<Step>(List.of(M5CollapsePassage.counting("prepared", LATE_TRIGGER_TICKS, this::preparedWithoutReentry)));
        steps.addAll(passage.steps(M5CollapseWindows.of(id).prepared()));
        return restartPhase(crashPhase, id, ChamberAtStart.AS_SAVED, steps, this::joinInsideBeforeTheFirstEvaluation);
    }

    /**
     * {@code waitinglate} 的開服（{@link #restartPhase} 的 {@code afterStarted}，{@link ChamberAtStart#AS_SAVED}）：原艙附近還沒有載入（{@code chamberNotLoadedAtStart}），參與者 JOIN
     * （艙外，crash 前存的位置）並移到艙內（{@code insideBeforeTheFirstTick}）；記下 JOIN 的位置。
     */
    private void joinInsideBeforeTheFirstEvaluation() {
        boolean loaded = chamberLoaded();
        fixture.joinAllInTheSourceWorld(Inside.OUTSIDE);
        fixture.moveInside();
        joinedAt = fixture.players().getFirst().getPos();
        run.expect("chamberNotLoadedAtStart", !loaded, () -> "開服時原艙附近已載入，量不到「來源艙還沒載入時的第一次評估」");
        run.expect("insideBeforeTheFirstTick", run.ticks() == 0 && fixture.players().stream().allMatch(M5CollapseFixture::inside),
                () -> "tick=" + run.ticks() + " 參與者在 " + joinedAt);
        run.proof.put("joinedAt", joinedAt.toString());
    }

    /**
     * {@code waitinglate} 的 step {@code prepared}（{@code tick} 是這個 step 的第幾個 tick，由 1 起）：參與者沒有移動，在 {@link #LATE_TRIGGER_TICKS} 個 tick 內進入 PREPARE
     * （「進入 PREPARE（閘門成立）」的 INFO 一則）；之後仍沒有就由這個具名斷言判 FAIL。另記下 RESOLVE 完成、原艙附近載入完成與進入 PREPARE 的 tick（閘門第一次評估在 RESOLVE
     * 完成的下一個 tick）。F6-2d-2 B1（A 段 quality Minor 4）：step 的上限由 {@code LATE_TRIGGER_TICKS + 5} 改為等於等待的上限（第 {@code LATE_TRIGGER_TICKS} 個 tick 一定完成，
     * 多出的 5 tick 到不了）；參數名比照 {@link M5CollapsePassage.Watching} 改為 {@code tick}（A 段 quality nit）。
     */
    private boolean preparedWithoutReentry(int tick) {
        var sid = fixture.sid().toString();
        if (chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
        if (!run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid).isEmpty()) run.proof.putIfAbsent("resolvedAtPhaseTick", run.ticks());
        var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", sid).size();
        if (prepares == 0 && tick < LATE_TRIGGER_TICKS) return false;
        boolean still = notMovedSinceTheJoin();
        run.expect("preparedWithoutReentry", prepares == 1 && still, () -> "開服之後 " + run.ticks() + " 個 tick：「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
        run.proof.put("preparedAtPhaseTick", run.ticks());
        return true;
    }

    /**
     * step {@code stepName}：參與者 JOIN 之後沒有移動，提交 COLLAPSING（「觸發=DORMANT 續行」的 INFO 一則、Destination 是 U，具名斷言 {@code assertion}），等法是 {@code await}
     * （{@link Await#FIRST_OBSERVATION}：W8 的 {@code committedOnTheFirstObservation}；{@link Await#AFTER_THE_LOAD}：H7 的 {@code committedWithoutReentry}，另記下原艙附近載入
     * 完成的 tick）。記下提交的 tick。F6-2d-2 Step 0 由兩份合成（判斷、斷言與等待的 tick 數都不變）。
     */
    private Step committedWithoutReentry(String stepName, String assertion, Await await) {
        if (await == Await.EDGE) throw new IllegalArgumentException("committedWithoutReentry 是不離開再進入的提交，不能用 EDGE：" + stepName);
        return M5CollapsePassage.counting(stepName, await.waitTicks, tick -> {
            var sid = fixture.sid();
            var record = run.journal().flushedRecords().get(sid);
            boolean committed = record != null && record.state() == SessionState.COLLAPSING;
            if (await == Await.AFTER_THE_LOAD && chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
            if (await.keepWaiting(committed, tick)) return false;
            var info = run.capture().matching(Level.INFO, "塌縮已提交", "觸發=DORMANT 續行", sid.toString()).size();
            boolean still = notMovedSinceTheJoin();
            run.expect(assertion, committed && info == 1 && still && M5CollapseFixture.destination(record).equals(Optional.of(fixture.universe())),
                    () -> "JOIN 之後 " + tick + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), sid) + " 提交的 INFO=" + info + " 沒有移動=" + still);
            run.proof.put("committedAtPhaseTick", run.ticks());
            return true;
        });
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
     * <li>{@code gateWaitsForAnEdge}：恢復供電、參與者一直在艙內，{@link M5CollapsePassage#WATCH_TICKS} 個 tick 都沒有再進 PREPARE（之後只看邊緣觸發）；之後參與者離開艙內。</li>
     * <li>參與者由艙外再進入 → PREPARE → PASSAGE_READY → 跨越 → (b) 終局；終局時「進入 PREPARE」恰兩則（{@code preparedAgainOnlyAfterTheEdge}）。</li>
     * </ol>
     */
    Phase w8Restart(String crashPhase) {
        var steps = new ArrayList<Step>();
        steps.add(committedWithoutReentry("committedOnTheFirstObservation", "committedOnTheFirstObservation", Await.FIRST_OBSERVATION));
        steps.add(new Step("resolved", 400, this::resolvedWithoutMoving));
        steps.add(M5CollapsePassage.counting("preparedOnTheLevel", PREPARE_WAIT_TICKS, this::preparedOnTheLevel));
        steps.add(new Step("leftPrepare", 40, this::leftPrepareWithoutPower));
        steps.add(M5CollapsePassage.watch("gateWaitsForAnEdge", M5CollapsePassage.WATCH_TICKS, this::gateWaitsForAnEdge, this::poweredThenLeave));
        steps.add(enter());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> {
            var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
            run.expect("preparedAgainOnlyAfterTheEdge", prepares == 2, () -> "「進入 PREPARE」=" + prepares);
            return End.crossed(fixture);
        }));
        return restartPhase(crashPhase, CrashWindow.DORMANT_STEP, ChamberAtStart.PRELOADED, steps, this::joinInsideBeforeTheFirstTick);
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

    /** W8 的 step {@code resolved}：RESOLVE 完成（{@link M5CollapsePassage#expectResolved}），參與者仍沒有移動（{@code stillInsideSinceTheJoin}）。 */
    private boolean resolvedWithoutMoving() throws Exception {
        if (run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", fixture.sid().toString()).isEmpty()) return false;
        passage.expectResolved();
        run.expect("stillInsideSinceTheJoin", notMovedSinceTheJoin(), () -> "參與者在 " + fixture.players().getFirst().getPos() + "（JOIN 時 " + joinedAt + "）");
        return true;
    }

    /**
     * W8 的 step {@code preparedOnTheLevel}（{@code tick} 是這個 step 的第幾個 tick，由 1 起）。F6-2d-2 B1（A 段 quality Minor 4、nit）：step 的上限由 {@code PREPARE_WAIT_TICKS + 5}
     * 改為等於等待的上限（第 {@code PREPARE_WAIT_TICKS} 個 tick 一定完成）；參數名比照 {@link M5CollapsePassage.Watching} 改為 {@code tick}。
     */
    private boolean preparedOnTheLevel(int tick) {
        var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
        // 最多等 PREPARE_WAIT_TICKS 個 tick，之後仍沒有進 PREPARE 就由這個具名斷言判 FAIL（mutation：閘門第一次也只看邊緣時，失敗在這裡）。
        if (prepares == 0 && tick < PREPARE_WAIT_TICKS) return false;
        boolean still = notMovedSinceTheJoin();
        run.expect("preparedWithoutAnEdge", prepares == 1 && still, () -> "RESOLVE 之後 " + tick + " 個 tick：「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
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
        return true;
    }

    /** W8 的 step {@code gateWaitsForAnEdge}（{@link M5CollapsePassage#watch}；{@code tick} 是恢復供電之後的第幾個 tick，由 1 起）：每個 tick 都還沒有再進 PREPARE。 */
    private void gateWaitsForAnEdge(int tick) {
        var sid = fixture.sid();
        var prepares = run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", sid.toString()).size();
        var record = run.journal().flushedRecords().get(sid);
        boolean still = notMovedSinceTheJoin();
        run.expect("gateWaitsForAnEdge", prepares == 1 && record != null && record.state() == SessionState.COLLAPSING && still,
                () -> "恢復供電之後第 " + tick + " 個 tick：「進入 PREPARE」=" + prepares + " state=" + M5GameTestSupport.state(run.journal(), sid) + " 沒有移動=" + still);
    }

    /** W8：{@code gateWaitsForAnEdge} 觀察完之後，Controller 仍有紅石輸入（{@code poweredWhileWaiting}），參與者離開艙內。 */
    private void poweredThenLeave() {
        run.expect("poweredWhileWaiting", run.source().isReceivingRedstonePower(FRAME.controllerPos()), () -> "恢復供電之後 Controller 沒有紅石輸入");
        fixture.moveOutside();
    }

    /**
     * W9 的 restart：開服時窗口表的斷言（SEALED(MATERIALIZE_FAILED) 與 halt 當下相同、catalog 恰多 U 一筆而且是 DISABLED、開服 bootstrap 沒有物化它、開服沒有停止）→ 參與者 JOIN
     * 在艙外 → 由艙外進入 → 重試一次（{@code retriedOnceAfterTheRestart}）→ RESOLVE 把 DISABLED 視為 §8.3 已完成（{@code disabledReenabledOnTheRetry}：改回 EAGER_ENABLED、
     * 物化、目的地確定，不是 AUTHORITY_CORRUPT）→ PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase w9Restart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), enter(), retried("retried", "retriedOnceAfterTheRestart", SealedReason.MATERIALIZE_FAILED, 1, Await.EDGE),
                new Step("reenabled", 400, () -> reenabled("disabledReenabledOnTheRetry", 1, SealWarns.NOT_CHECKED))));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.MATERIALIZE_SEALED, ChamberAtStart.PRELOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * W9 的變體 (a)（F4a I-1 的跨重啟版）：開服時同 {@link #w9Restart}；參與者 JOIN 在艙外之後，讓另一個 session 的 allocation 追加讀回失敗（{@code otherAppendUnconfirmed}：
     * 已落地、ledger 未確認、以權威為單位退避）→ 由艙外進入 → 重試一次 → RESOLVE 第 1 步因 ledger 未確認封存為 ALLOCATION_IO_FAILED，改回 EAGER_ENABLED 之前
     * （{@code firstRetrySealedOnTheAllocation}：catalog 仍是 DISABLED、沒有物化）→ 參與者離開，退避期過後再進入 → 第二次重試 → ledger 收斂、RESOLVE 仍把 DISABLED 視為 §8.3
     * 已完成（{@code disabledReenabledAfterTheAllocationRetry}）→ PASSAGE_READY → 跨越 → (b) 終局（allocation ledger 另有那一筆，spec §6 roll-forward 不移除）。
     */
    Phase w9AllocationRetryRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), new Step("otherAppend", 1, this::otherSessionAppendUnconfirmed), enter(),
                retried("retried", "retriedOnceAfterTheRestart", SealedReason.MATERIALIZE_FAILED, 1, Await.EDGE),
                new Step("sealedOnTheAllocation", 20, this::sealedOnTheUnconfirmedAllocation), new Step("backoff", BACKOFF_TICKS + 10, this::backoffElapsedThenEnter),
                retried("retriedAgain", "retriedAgainAfterTheBackoff", SealedReason.ALLOCATION_IO_FAILED, 2, Await.EDGE),
                new Step("reenabled", 400, () -> reenabled("disabledReenabledAfterTheAllocationRetry", 2, SealWarns.NOT_CHECKED))));
        // F6-2d-2 Step 0（F6-2d-1 quality Minor 2）：原本為了換 End 手抄一份 passage.steps()。
        steps.addAll(passage.steps(null, () -> End.crossed(fixture).withOtherAllocations(List.of(otherAllocation))));
        return restartPhase(crashPhase, CrashWindow.MATERIALIZE_SEALED, ChamberAtStart.PRELOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * W9 的變體 (b)：開服時是 COLLAPSING、catalog 是 DISABLED（§8.3 先寫 DISABLED、再寫 SEALED，兩者之間 crash）；開服沒有停止、bootstrap 沒有物化它。參與者 JOIN 在艙外；
     * 本 instance 的第一次 RESOLVE 就是 §8.3 的重試（{@code firstResolveReenabledTheDisabled}：不進 SEALED，改回 EAGER_ENABLED、物化、目的地確定）→ 進入艙內 → PASSAGE_READY →
     * 跨越 → (b) 終局。
     */
    Phase w9CollapsingDisabledRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), new Step("reenabled", 400, () -> reenabled("firstResolveReenabledTheDisabled", 0, SealWarns.NONE_EXPECTED))));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.CATALOG_DISABLED, ChamberAtStart.PRELOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * F6-2-07 的 restart：開服時窗口表的斷言（SEALED(AUTHORITY_UNREADABLE) 與 halt 當下相同、catalog 有 U 而且開服 bootstrap 已物化、discovery 沒有 U）→ 參與者 JOIN 在艙外 →
     * 由艙外進入 → 開服之後第一次重試（{@code retriedOnceAfterTheRestart}）→ RESOLVE 追加 discovery → PASSAGE_READY → 跨越 → (b) 終局。
     */
    Phase unreadableRestart(String crashPhase) {
        var steps = new ArrayList<Step>(List.of(joinOutside(), enter(), retried("retried", "retriedOnceAfterTheRestart", SealedReason.AUTHORITY_UNREADABLE, 1, Await.EDGE)));
        steps.addAll(untilTheEnd());
        return restartPhase(crashPhase, CrashWindow.SEALED_STEP, ChamberAtStart.PRELOADED, steps, M5CollapseRun.NOTHING);
    }

    /**
     * step {@code stepName}：由 SEALED({@code reason}) 重試（「SEALED 重試：SEALED(reason)→COLLAPSING」的 INFO 恰一則），這時這個 session 的重試恰為第 {@code count} 次
     * （具名斷言 {@code assertion}）；等法是 {@code await}（{@link Await}：{@link Await#EDGE} 是由艙外進入之後；{@link Await#AFTER_THE_LOAD} 是 H7 的
     * {@code retriedWithoutReentry}，另斷言參與者沒有移動、記下原艙附近載入完成與重試的 tick）。F6-2d-2 Step 0：{@code retried(reason, count)} 與
     * {@code retriedWithoutReentry} 合成這一個；step 名稱改由呼叫端給（F6-2d-1 quality Minor 5：原本由 {@code count} 推出 {@code retried}／{@code retriedAgain}，receipt 的
     * witness 以 step 名稱為鍵，不能重複；runner 在建立 phase 時檢查重名）。F6-2d-2（F6-2d-2 Step 0 quality Minor 2）：也接受 {@link Await#FIRST_OBSERVATION}（開服 attach 的
     * session 第一次觀察到原參與者在艙內就算觸發，production 的 {@code CollapseService.sealed} 以 {@code track.entry(startupSessions.contains(sid))} 實作）；原本拒絕它，訊息也與 production 相反。
     */
    private Step retried(String stepName, String assertion, SealedReason reason, int count, Await await) {
        return M5CollapsePassage.counting(stepName, await.waitTicks, tick -> {
            var sid = fixture.sid().toString();
            var fromReason = run.capture().matching(Level.INFO, "SEALED 重試：SEALED(" + reason + ")→COLLAPSING", sid).size();
            if (await == Await.AFTER_THE_LOAD && chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
            if (await.keepWaiting(fromReason > 0, tick)) return false;
            var all = run.capture().matching(Level.INFO, "SEALED 重試", sid).size();
            if (await == Await.EDGE) {
                run.expect(assertion, fromReason == 1 && all == count,
                        () -> "SEALED(" + reason + ") 的重試 INFO=" + fromReason + " 全部的重試 INFO=" + all + "，預期第 " + count + " 次");
                return true;
            }
            boolean still = notMovedSinceTheJoin();
            run.expect(assertion, fromReason == 1 && all == count && still,
                    () -> "JOIN 之後 " + tick + " 個 tick：SEALED(" + reason + ") 的重試 INFO=" + fromReason + " 全部的重試 INFO=" + all + "，預期第 " + count + " 次，沒有移動=" + still);
            run.proof.put("retriedAtPhaseTick", run.ticks());
            return true;
        });
    }

    /**
     * RESOLVE 的結果（W9 與兩個變體共用）：等到「目的地已確定」，或重試之後又離開 COLLAPSING（修改前會進 SEALED(AUTHORITY_CORRUPT)）。斷言 {@code name}：目的地已確定恰一則、
     * record 是 COLLAPSING，catalog 的這筆已改回 EAGER_ENABLED、U 已物化，沒有 AUTHORITY_CORRUPT（這個 session 沒有 ERROR、也沒有提到它的 WARN），allocation ledger 已確認；
     * 這個 session 的重試恰為 {@code retries} 次（w9cd 是 0：開服時就是 COLLAPSING，沒有經過 SEALED）。{@code sealWarns} 是 {@link SealWarns#NONE_EXPECTED} 時，另斷言本 instance
     * 沒有這個 session 的「塌縮進入 SEALED」WARN（F6-2d-2 Step 0，F6-2d-1 quality nit：原本以 {@code retries == 0} 代表這件事）。
     */
    private boolean reenabled(String name, int retries, SealWarns sealWarns) {
        var sid = fixture.sid();
        var resolved = run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid.toString()).size();
        var record = run.journal().flushedRecords().get(sid);
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
        if (resolved == 0 && (retried < retries || (record != null && record.state() == SessionState.COLLAPSING))) return false;
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords().get(fixture.universe());
        var errors = run.capture().matching(Level.ERROR, sid.toString()).size();
        var corrupt = run.capture().matching(Level.WARN, "AUTHORITY_CORRUPT", sid.toString()).size();
        var sealedWarns = sealWarns == SealWarns.NONE_EXPECTED ? run.capture().matching(Level.WARN, "塌縮進入 SEALED", sid.toString()).size() : 0;
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
        fixture.expectAllocationsWithOthers("otherAppendLanded", List.of(otherAllocation));
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
