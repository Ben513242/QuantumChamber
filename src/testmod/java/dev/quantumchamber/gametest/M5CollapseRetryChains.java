package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseBackoff;
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
 * <p>F6-2d-2 B1：這些共用的部分（restart builder、{@link Await}、{@link ChamberAtStart}、通用的 await step、JOIN 的位置與開服時的 record）以純搬移抽到基底
 * {@link M5CollapseRestartChains}（{@code M5CollapseDoorChains} 也繼承它）。
 */
final class M5CollapseRetryChains extends M5CollapseRestartChains {
    /**
     * 封存之後等多少 tick 再由艙外進入：per-session 的退避與 ledger 收斂的退避的第一次延遲都是 {@link CollapseBackoff#INITIAL_TICKS}（spec §11.2 的 200 ticks），留 5 ticks 餘裕
     * （比照 G-DISABLED 的 {@code registered + 2 + 205}）。F6-2d-2 Step 0（F6-2d-1 quality nit）：由寫死的 205 改為指向 production 的常數（值不變）。
     */
    static final int BACKOFF_TICKS = CollapseBackoff.INITIAL_TICKS + 5;
    /** H7（{@code w8late}、{@code unreadablelate}）的 restart：等原艙附近卸載的上限（spawn chunks 的半徑改為 0 之後，原版在下一次 chunk 更新就把它降到 FULL 以下）。 */
    private static final int UNLOAD_WAIT_TICKS = 400;

    /** {@link #reenabled} 是否另斷言本 instance 沒有這個 session 的「塌縮進入 SEALED」WARN（F6-2d-2 Step 0，F6-2d-1 quality nit：原本以 {@code retries == 0} 代表）。 */
    private enum SealWarns {
        /** 本 instance 不應進過 SEALED（w9cd：開服時就是 COLLAPSING，第一次 RESOLVE 就改回）。 */
        NONE_EXPECTED,
        /** 不檢查（w9、w9io：開服時是 SEALED；w9io 在本 instance 另封存過一次）。 */
        NOT_CHECKED
    }

    /** unreadable 的 crash、w9io 的 restart：probe 安裝的 checked 讀取失敗（命中次數）。 */
    private M5TestHooks.CheckedReadFault readFault;
    /** 封存的 server tick（unreadable 的 crash、w9io 的 restart：之後等過退避期）。 */
    private int markTick = -1;
    /** unreadable 的 crash：封存之後的 record（之後觀察它沒有變）。 */
    private SessionRecoveryRecord sealedRecord;
    /** w9io 的 restart：另一個 session 已落地的 allocation entry（spec §6 roll-forward：不移除）。 */
    private M5CollapseDiskEvidence.Allocation otherAllocation;

    M5CollapseRetryChains(M5CollapseRun run) {
        super(run);
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
        // F6-2e Step 0（F6-2d-2 B2 quality Minor 2）：改用基底的 crash 骨架（原本這裡逐步寫了一份相同的 step：名稱、順序與上限都不變）。
        return crash(dormantNewSetup(), List.of(),
                () -> run.install(M5TestHooks.MATERIALIZE_FAULT, new M5TestHooks.MaterializeFault(UniverseKeys.world(fixture.universe(), DimensionRole.OVERWORLD))), id, 400);
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
            readFault = discoveryReadFails();
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
            joinInside();
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
        var steps = new ArrayList<Step>(List.of(M5CollapsePassage.awaiting("prepared", LATE_TRIGGER_TICKS, tick -> recordTheLoadAndTheResolve(), () -> prepares() > 0,
                this::preparedWithoutReentry)));
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
     * 多出的 5 tick 到不了）；參數名比照 {@link M5CollapsePassage.Watching} 改為 {@code tick}（A 段 quality nit）。F6-2d-2 B2a（B1 quality Minor 2）：改用通用的
     * {@link M5CollapsePassage#awaiting}（等待時間 {@link #LATE_TRIGGER_TICKS} 與通過條件相同）。F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：每個 tick 記下的 tick 改由
     * {@code awaiting} 的 {@code eachTick}（{@link #recordTheLoadAndTheResolve}），{@code happened} 只問「已進入 PREPARE」；順序與之前相同（先記 proof 再查詢）。
     */
    private void preparedWithoutReentry(int tick, boolean happened) {
        var prepares = prepares();
        boolean still = notMovedSinceTheJoin();
        run.expect("preparedWithoutReentry", prepares == 1 && still, () -> "開服之後 " + run.ticks() + " 個 tick：「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
        run.proof.put("preparedAtPhaseTick", run.ticks());
    }

    /**
     * {@code waitinglate} 的 step {@code prepared} 每個 tick（{@code awaiting} 的 {@code eachTick}）：記下原艙附近載入完成與 RESOLVE 完成的 tick（第一次）。只讀，不改世界。
     * F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：原本是 {@code preparedRecordingTheTicks}，在 {@code happened} 裡記 proof、再回傳是否已進入 PREPARE。
     */
    private void recordTheLoadAndTheResolve() {
        var sid = fixture.sid().toString();
        if (chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
        if (!run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", sid).isEmpty()) run.proof.putIfAbsent("resolvedAtPhaseTick", run.ticks());
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
        steps.add(M5CollapsePassage.awaiting("preparedOnTheLevel", PREPARE_WAIT_TICKS, () -> prepares() > 0, this::preparedOnTheLevel));
        steps.add(new Step("leftPrepare", 40, this::leftPrepareWithoutPower));
        steps.add(M5CollapsePassage.watch("gateWaitsForAnEdge", M5CollapsePassage.WATCH_TICKS, this::gateWaitsForAnEdge, this::poweredThenLeave));
        steps.add(enter());
        steps.add(passage.ready(null, M5CollapseFixture.EVERYONE));
        steps.addAll(passage.cross(M5CollapseFixture.EVERYONE));
        steps.add(passage.ended(() -> {
            var prepares = prepares();
            run.expect("preparedAgainOnlyAfterTheEdge", prepares == 2, () -> "「進入 PREPARE」=" + prepares);
            return End.crossed(fixture);
        }));
        return restartPhase(crashPhase, CrashWindow.DORMANT_STEP, ChamberAtStart.PRELOADED, steps, this::joinInsideBeforeTheFirstTick);
    }

    /** W8 的開服：參與者 JOIN 在艙內（第一個 tick 之前），這時 record 仍是 DORMANT 的收據（{@code dormantWhenJoined}）；記下 JOIN 的位置。 */
    private void joinInsideBeforeTheFirstTick() {
        joinInside();
        run.expect("dormantWhenJoined", fixture.receipt().equals(run.journal().flushedRecords().get(fixture.sid())) && run.ticks() == 0,
                () -> "JOIN 時 tick=" + run.ticks() + " state=" + M5GameTestSupport.state(run.journal(), fixture.sid()));
        run.proof.put("joinedAt", joinedAt.toString());
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
     * 改為等於等待的上限（第 {@code PREPARE_WAIT_TICKS} 個 tick 一定完成）；參數名比照 {@link M5CollapsePassage.Watching} 改為 {@code tick}。F6-2d-2 B2a（B1 quality Minor 2）：
     * 改用通用的 {@link M5CollapsePassage#awaiting}（等待時間 {@link #PREPARE_WAIT_TICKS} 與通過條件相同）。
     */
    private void preparedOnTheLevel(int tick, boolean happened) {
        var prepares = prepares();
        // 最多等 PREPARE_WAIT_TICKS 個 tick（awaiting），之後仍沒有進 PREPARE 就由這個具名斷言判 FAIL（mutation：閘門第一次也只看邊緣時，失敗在這裡）。
        boolean still = notMovedSinceTheJoin();
        run.expect("preparedWithoutAnEdge", prepares == 1 && still, () -> "RESOLVE 之後 " + tick + " 個 tick：「進入 PREPARE」=" + prepares + " 沒有移動=" + still);
        // spec §7.3：PREPARE 進行到一半時閘門不再成立（來源艙沒有供電）就退回 WAITING。
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.AIR.getDefaultState(), 3);
    }

    private boolean leftPrepareWithoutPower() {
        var sid = fixture.sid();
        var left = run.capture().matching(Level.INFO, "PREPARE 的閘門不再成立，退回 WAITING", sid.toString()).size();
        if (left == 0) return false;
        var record = run.journal().flushedRecords().get(sid);
        run.expect("leftPrepareOnPowerLoss", left == 1 && collapsing(record) && notMovedSinceTheJoin(),
                () -> "退回 WAITING 的 INFO=" + left + " state=" + M5GameTestSupport.state(run.journal(), sid));
        run.source().setBlockState(FRAME.controllerPos().up(), Blocks.REDSTONE_BLOCK.getDefaultState(), 3);
        return true;
    }

    /** W8 的 step {@code gateWaitsForAnEdge}（{@link M5CollapsePassage#watch}；{@code tick} 是恢復供電之後的第幾個 tick，由 1 起）：每個 tick 都還沒有再進 PREPARE。 */
    private void gateWaitsForAnEdge(int tick) {
        var sid = fixture.sid();
        var prepares = prepares();
        var record = run.journal().flushedRecords().get(sid);
        boolean still = notMovedSinceTheJoin();
        run.expect("gateWaitsForAnEdge", prepares == 1 && collapsing(record) && still,
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
        if (resolved == 0 && (retried < retries || collapsing(record))) return false;
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords().get(fixture.universe());
        var errors = run.capture().matching(Level.ERROR, sid.toString()).size();
        var corrupt = run.capture().matching(Level.WARN, "AUTHORITY_CORRUPT", sid.toString()).size();
        var sealedWarns = sealWarns == SealWarns.NONE_EXPECTED ? run.capture().matching(Level.WARN, "塌縮進入 SEALED", sid.toString()).size() : 0;
        boolean confirmed = !CollapseAuthorities.get(run.server()).unconfirmed(CollapseAuthorities.Authority.ALLOCATION_LEDGER);
        run.expect(name, resolved == 1 && retried == retries && collapsing(record) && M5CollapseFixture.eagerEnabled(catalog)
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
