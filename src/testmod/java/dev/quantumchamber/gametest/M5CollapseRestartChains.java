package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.collapse.CollapseServiceTestAccess;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-2 B1）：由觸發（或開服之後的第一次 RESOLVE、閘門的第一次評估）續行的 restart chain 的共用基底，由 {@link M5CollapseRetryChains} 以純搬移抽出
 * （A 段 quality 給 B 段的建議第 1 點）：restart builder（{@link #restartPhase}，開服時 {@link #started} 依 {@link ChamberAtStart} 處理原艙附近）、觸發之後的等法
 * （{@link Await} 與通用的 {@link #awaited}，以及它的兩個用法 {@link #committedWithoutReentry}、{@link #retried}）、JOIN 的位置（{@link #joinedAt}、
 * {@link #notMovedSinceTheJoin}）、開服時的 record（{@link #atStart}）、原艙附近是否已載入（{@link #chamberLoaded}）與 JOIN／進入的 step。
 * <p>放在 abstract 基底（不是另一個以組合使用的類別，也不併入 {@link M5CollapsePassage}）：純搬移之後子類別的呼叫端一字不改（{@code joinedAt}、{@code atStart} 是每條 chain
 * 自己的狀態，由子類別的開服與 JOIN 寫入），restart builder 只有這一份（{@code M5CollapseDoorChains} 也繼承它，不另寫第二份）；{@link M5CollapsePassage} 是所有 chain
 * （含開服時就結束通道、不經觸發的 {@code M5CollapsePassageChains} 與 {@code M5CollapseDormantChains}）共用的 RESOLVE 之後的段與終局斷言，不放只給觸發續行的 restart 用的狀態。
 * 子類別要用的成員由 private 改為 package-private，其餘與搬移之前相同。
 */
abstract class M5CollapseRestartChains {
    /** restart 開服時以 probe 的 ticket 同步載入的半徑（chunk；原艙與 {@code CollapseService.neighborhoodLoaded} 外擴的 3 格都在 Controller 所在 chunk 的半徑 1 之內）。 */
    private static final int CHAMBER_LOAD_RADIUS = 1;
    /** w8：PREPARE 等多少 tick（RESOLVE 之後閘門第一次以位準評估就該成立；超過時由具名斷言判 FAIL，不是 stepBounded）。 */
    static final int PREPARE_WAIT_TICKS = 10;
    /**
     * H7：參與者 JOIN 在艙內之後，最多等多少 tick 觸發（原艙附近在 JOIN 之後幾個 tick 才由玩家的 ticket 載入；超過時由具名斷言判 FAIL，不是 stepBounded：修正之前的
     * RED 失敗在目標斷言）。
     */
    static final int LATE_TRIGGER_TICKS = 20;
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
    enum Await {
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

    final M5CollapseRun run;
    final M5CollapseFixture fixture;
    final M5CollapsePassage passage;
    /** w8 的 restart：參與者 JOIN 時的位置（之後斷言沒有移動）。 */
    Vec3d joinedAt;
    /** restart 開服時這個 session 的 record（{@link #started} 無條件記下，F6-2d-2 B1；H7 的 JOIN 時斷言還沒有觸發）。 */
    SessionRecoveryRecord atStart;

    M5CollapseRestartChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

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
            throw new IllegalStateException("M5CollapseRestartChains.restartPhase 不在 JOIN 之前載入投影：" + id);
        }
    }

    /**
     * 這個類別的 restart phase（F6-2d-2 Step 0，H7 quality 給 F6-2d-2 的建議：原本 {@code restartPhase}、H7 的 {@code lateRestartPhase} 與 {@code waitingLateRestart} 內嵌的
     * 一份三個幾乎相同的 builder，合成這一個）：開服時 {@link #started}（依 {@link ChamberAtStart} 處理原艙附近），再 {@code afterStarted}；
     * 每個 tick {@link M5CollapsePassage#invariants}。窗口不得要求 JOIN 之前載入投影（{@link #requireNoProjectionLoad}，在 {@code onInitialize} 丟出）。
     */
    Phase restartPhase(String crashPhase, CrashWindow id, ChamberAtStart chamber, List<Step> steps, M5CollapseRun.Checked afterStarted) {
        requireNoProjectionLoad(id);
        return Phase.stop(steps).started(() -> { started(crashPhase, id, chamber); afterStarted.run(); }).everyTick(() -> passage.invariants(null));
    }

    /**
     * restart 開服時原艙附近的 chunk 怎麼處理（{@link #restartPhase}、{@link #started}；F6-2d-2，F6-2d-2 Step 0 quality Minor 4：取代位置參數 {@code boolean preload}）。只管 chunk：
     * 開服時的 record 由 {@link #started} 無條件記下（F6-2d-2 B1，A 段 quality Minor 2）。卸載只由 {@link #started} 的 case {@code UNLOADED} 做，沒有可交給 {@code afterStarted}
     * 的卸載 helper，所以「預先載入又卸載」不能以 {@link #restartPhase} 的參數表達（F6-2d-2 B1，A 段 spec review I-1：F6-2d-2 的 {@code unloadTheChamberAtStart} 與
     * {@link M5CollapseRun.Checked} 同形，{@code PRELOADED} 配它當 {@code afterStarted} 仍能編譯）。
     */
    enum ChamberAtStart {
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
    boolean chamberLoaded() {
        return CollapseServiceTestAccess.neighborhoodLoaded(run.source(), FRAME);
    }

    /** 參與者在艙內，位置與 JOIN 時相同（沒有移動過，所以之後的提交與 PREPARE 不是由艙外進入的邊緣）。 */
    boolean notMovedSinceTheJoin() {
        var player = fixture.players().getFirst();
        return M5CollapseFixture.inside(player) && player.getPos().equals(joinedAt);
    }

    /** step：參與者真 JOIN 在來源世界的艙外（playerdata 是觸發之前存的）。 */
    Step joinOutside() {
        return new Step("join", 1, () -> { fixture.joinAllInTheSourceWorld(Inside.OUTSIDE); return true; });
    }

    /** step：參與者由艙外進入艙內（下一個 tick 是 §7.1／§11.2 的邊緣觸發）。 */
    Step enter() {
        return new Step("enter", 1, () -> { fixture.moveInside(); return true; });
    }

    /**
     * step {@code stepName}：參與者 JOIN 之後沒有移動，提交 COLLAPSING（「觸發=DORMANT 續行」的 INFO 一則、Destination 是 U，具名斷言 {@code assertion}），等法是 {@code await}
     * （{@link Await#FIRST_OBSERVATION}：W8 的 {@code committedOnTheFirstObservation}；{@link Await#AFTER_THE_LOAD}：H7 的 {@code committedWithoutReentry}，另記下原艙附近載入
     * 完成的 tick）。記下提交的 tick。F6-2d-2 Step 0 由兩份合成（判斷、斷言與等待的 tick 數都不變）；F6-2d-2 B1 起經通用的 {@link #awaited}。
     */
    Step committedWithoutReentry(String stepName, String assertion, Await await) {
        if (await == Await.EDGE) throw new IllegalArgumentException("committedWithoutReentry 是不離開再進入的提交，不能用 EDGE：" + stepName);
        return awaited(stepName, assertion, await, () -> collapsing(run.journal().flushedRecords().get(fixture.sid())), tick -> {
            var record = run.journal().flushedRecords().get(fixture.sid());
            var info = run.capture().matching(Level.INFO, "塌縮已提交", "觸發=DORMANT 續行", fixture.sid().toString()).size();
            return new Verdict(collapsing(record) && info == 1 && M5CollapseFixture.destination(record).equals(Optional.of(fixture.universe())),
                    "JOIN 之後 " + tick + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), fixture.sid()) + " 提交的 INFO=" + info);
        }, this::notMovedSinceTheJoin, "committedAtPhaseTick");
    }

    private static boolean collapsing(SessionRecoveryRecord record) {
        return record != null && record.state() == SessionState.COLLAPSING;
    }

    /** {@link #awaited} 等完之後（已發生，或等滿 {@link Await#waitTicks}）這一步的結果：{@code tick} 是這個 step 的第幾個 tick（由 1 起）。 */
    @FunctionalInterface interface Outcome { Verdict at(int tick) throws Exception; }

    /** {@link Outcome} 的結果：具名斷言是否成立，與失敗時的細節（位置的部分由 {@link #awaited} 接上）。 */
    record Verdict(boolean holds, String detail) {}

    /**
     * 通用的 await step（F6-2d-2 B1，A 段 quality 給 B 段的建議：{@link #committedWithoutReentry} 與 {@link #retried} 原本各寫一份同樣的等法）：step {@code stepName} 每個 tick 問
     * {@code happened}；還沒有發生、而且還在等待的時間內（{@link Await#keepWaiting}）就再等，否則斷言 {@code assertion}：已發生、{@code outcome} 成立，而且 {@code still} 不是 null
     * 時參與者 JOIN 之後沒有移動（{@code still == null}：不斷言位置）。上限只有 {@link Await#waitTicks}（也是 step 的上限）。{@link Await#AFTER_THE_LOAD} 每個 tick 另記下原艙附近
     * 載入完成的 tick（{@code chamberLoadedAtPhaseTick}）；{@code proofKey} 不是 null 時記下斷言的 tick。判斷、斷言、等待的 tick 數與記下的 proof 都與合併之前相同。
     */
    Step awaited(String stepName, String assertion, Await await, BooleanSupplier happened, Outcome outcome, BooleanSupplier still, String proofKey) {
        return M5CollapsePassage.counting(stepName, await.waitTicks, tick -> {
            boolean now = happened.getAsBoolean();
            if (await == Await.AFTER_THE_LOAD && chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
            if (await.keepWaiting(now, tick)) return false;
            var verdict = outcome.at(tick);
            boolean notMoved = still == null || still.getAsBoolean();
            run.expect(assertion, now && verdict.holds() && notMoved, () -> verdict.detail() + (still == null ? "" : " 沒有移動=" + notMoved));
            if (proofKey != null) run.proof.put(proofKey, run.ticks());
            return true;
        });
    }

    /**
     * step {@code stepName}：由 SEALED({@code reason}) 重試（「SEALED 重試：SEALED(reason)→COLLAPSING」的 INFO 恰一則），這時這個 session 的重試恰為第 {@code count} 次
     * （具名斷言 {@code assertion}）；等法是 {@code await}（{@link Await}：{@link Await#EDGE} 是由艙外進入之後；{@link Await#AFTER_THE_LOAD} 是 H7 的
     * {@code retriedWithoutReentry}，另斷言參與者沒有移動、記下原艙附近載入完成與重試的 tick）。F6-2d-2 Step 0：{@code retried(reason, count)} 與
     * {@code retriedWithoutReentry} 合成這一個；step 名稱改由呼叫端給（F6-2d-1 quality Minor 5：原本由 {@code count} 推出 {@code retried}／{@code retriedAgain}，receipt 的
     * witness 以 step 名稱為鍵，不能重複；runner 在建立 phase 時檢查重名）。F6-2d-2（F6-2d-2 Step 0 quality Minor 2）：也接受 {@link Await#FIRST_OBSERVATION}（開服 attach 的
     * session 第一次觀察到原參與者在艙內就算觸發，production 的 {@code CollapseService.sealed} 以 {@code track.entry(startupSessions.contains(sid))} 實作）；原本拒絕它，訊息也與 production 相反。
     * F6-2d-2 B1 起經通用的 {@link #awaited}：{@link Await#EDGE} 不斷言位置（{@code still} 為 null）、不記重試的 tick，其餘斷言參與者沒有移動、記下 {@code retriedAtPhaseTick}（與合併之前相同）。
     */
    Step retried(String stepName, String assertion, SealedReason reason, int count, Await await) {
        boolean edge = await == Await.EDGE;
        return awaited(stepName, assertion, await, () -> retriesFrom(reason) > 0, tick -> {
            int fromReason = retriesFrom(reason), all = run.capture().matching(Level.INFO, "SEALED 重試", fixture.sid().toString()).size();
            return new Verdict(fromReason == 1 && all == count, (edge ? "" : "JOIN 之後 " + tick + " 個 tick：") + "SEALED(" + reason + ") 的重試 INFO=" + fromReason
                    + " 全部的重試 INFO=" + all + "，預期第 " + count + " 次");
        }, edge ? null : this::notMovedSinceTheJoin, edge ? null : "retriedAtPhaseTick");
    }

    /** 這個 session 由 SEALED({@code reason}) 重試的 INFO（「SEALED 重試：SEALED(reason)→COLLAPSING」）的則數。 */
    private int retriesFrom(SealedReason reason) {
        return run.capture().matching(Level.INFO, "SEALED 重試：SEALED(" + reason + ")→COLLAPSING", fixture.sid().toString()).size();
    }
}
