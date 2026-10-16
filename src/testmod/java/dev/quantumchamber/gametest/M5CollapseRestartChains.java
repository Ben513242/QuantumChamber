package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.collapse.CollapseServiceTestAccess;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
import dev.quantumchamber.gametest.M5CollapseRun.Phase;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.gametest.M5CollapseWindows.OriginDoorState;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameRules;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-2 B1）：由觸發（或開服之後的第一次 RESOLVE、閘門的第一次評估）續行的 restart chain 的共用基底，由 {@link M5CollapseRetryChains} 以純搬移抽出
 * （A 段 quality 給 B 段的建議第 1 點）：restart builder（{@link #restartPhase}，開服時 {@link #started} 依 {@link ChamberAtStart} 處理原艙附近）、觸發之後的等法
 * （{@link Await} 與通用的 {@link #untilTriggered}（F6-2e Step 0 之前叫 {@code awaited}），以及它的兩個用法 {@link #committedWithoutReentry}、{@link #retried}）、JOIN 的位置（{@link #joinedAt}、
 * {@link #notMovedSinceTheJoin}）、開服時的 record（{@link #atStart}）、原艙附近是否已載入（{@link #chamberLoaded}）與 JOIN／進入的 step。
 * <p>放在 abstract 基底（不是另一個以組合使用的類別，也不併入 {@link M5CollapsePassage}）：純搬移之後子類別的呼叫端一字不改（{@code joinedAt}、{@code atStart} 是每條 chain
 * 自己的狀態，由子類別的開服與 JOIN 寫入），restart builder 只有這一份（{@code M5CollapseDoorChains} 也繼承它，不另寫第二份）；{@link M5CollapsePassage} 是所有 chain
 * （含開服時就結束通道、不經觸發的 {@code M5CollapsePassageChains} 與 {@code M5CollapseDormantChains}）共用的 RESOLVE 之後的段與終局斷言，不放只給觸發續行的 restart 用的狀態。
 * 子類別要用的成員由 private 改為 package-private，其餘與搬移之前相同。
 * <p>F6-2e Step 0（F6-2d-2 B2 quality Minor 2）：crash phase 的共用骨架（{@link #crash}，原艙與收據由 {@link CrashSetup} 給）與「discovery 的讀取失敗一次」
 * （{@link #discoveryReadFails}）也在這裡：原本 {@code M5CollapseDoorChains} 的 private {@code crash} 與 {@code M5CollapseRetryChains.materializeFailureCrash} 每一步都相同、
 * discovery 的 fault 兩邊各寫一次。
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
     * 但 step 一定在第 {@code waitTicks} 個 tick 完成（還在等的條件為假時就斷言、回 true），多出的餘裕永遠到不了，兩者之間也沒有檢查。等待的 tick 數與通過條件都不變。
     * F6-2d-2 B2a（B1 quality Minor 2）：「還在等」的條件改由通用的 {@link M5CollapsePassage#awaiting} 判斷（{@link #untilTriggered} 委派給它），拿掉這裡的 {@code keepWaiting}。
     * <p>F6-2e Step 0（F6-2d-2 B2 quality nit）：等完之後要不要斷言位置（{@link Position}）改為這個 enum 的欄位（原本由呼叫端另給，{@code FIRST_OBSERVATION} 配
     * {@link Position#UNCHECKED} 也能編譯，斷言會靜默變弱）；{@code awaited} 改名為 {@link #untilTriggered}（與 {@link M5CollapsePassage#awaiting} 太像）。
     */
    enum Await {
        /**
         * 邊緣之後：參與者由艙外進入（{@link #enter}），或開服之後的第一次觀察時沒有人在艙內、之後 JOIN 在艙內（{@code sealmembrane} 的 JOIN 的邊緣，F6-2d-2 B2）。最多等 10 個
         * tick，不斷言位置（F6-2e Step 0 依 F6-2d-2 B2 quality nit 補上 JOIN 的邊緣：原本只寫「由艙外進入之後」）。
         */
        EDGE(10, Position.UNCHECKED),
        /** W8：原艙在開服時已載入，開服之後的第一次觀察就觸發。 */
        FIRST_OBSERVATION(FIRST_OBSERVATION_TICKS, Position.NOT_MOVED_SINCE_THE_JOIN),
        /** H7：JOIN 時原艙附近還沒有載入，載入之後才觸發；另記下原艙附近載入完成的 tick（{@code chamberLoadedAtPhaseTick}）。 */
        AFTER_THE_LOAD(LATE_TRIGGER_TICKS, Position.NOT_MOVED_SINCE_THE_JOIN);

        /** 最多等幾個 tick（也是 step 的上限）：到第 {@code waitTicks} 個 tick 仍沒有發生時，由具名斷言判 FAIL。 */
        final int waitTicks;
        /** 等完之後要不要另斷言參與者的位置：{@link #EDGE} 不斷言，其餘斷言 JOIN 之後沒有移動。 */
        final Position position;

        Await(int waitTicks, Position position) {
            this.waitTicks = waitTicks;
            this.position = position;
        }
    }

    /**
     * {@link #untilTriggered} 等完之後要不要另斷言參與者的位置（F6-2d-2 B2a，B1 quality nit：原本是可為 null 的 {@code BooleanSupplier still}，與 {@code happened} 同型別，兩者
     * 對調也能編譯）。F6-2e Step 0 起是 {@link Await#position}，呼叫端不再另給。
     */
    enum Position {
        /** 不斷言位置（{@link Await#EDGE}：邊緣之後）。 */
        UNCHECKED,
        /** 參與者 JOIN 之後沒有移動（{@link #notMovedSinceTheJoin}）。 */
        NOT_MOVED_SINCE_THE_JOIN
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

    // ---- crash phase（F6-2e Step 0，F6-2d-2 B2 quality Minor 2）----

    /** crash phase 在 step {@code receipt} 寫入並原生存檔的收據（{@link CrashSetup#receipt}）：只建立，不寫入。 */
    @FunctionalInterface interface CrashReceipt { SessionRecoveryRecord build() throws Exception; }

    /**
     * {@link #crash} 收據之前的原艙與收據（F6-2e Step 0，F6-2d-2 B2 quality Minor 2：原本寫死 {@code fixture.buildOrigin(1)} 與 {@code fixture.dormantNewReceipt()}）：
     * {@code origin} 是 step {@code build} 的本體（fresh 世界蓋原艙與參與者），{@code beforeWrite} 在 step {@code receipt} 寫入收據之前執行（F6-2e-1：H7 的 {@code waitinglate} 把 spawn
     * chunks 的半徑改為 0，收據的原生存檔把它帶進 level.dat；沒有時是 {@link M5CollapseRun#NOTHING}），{@code receipt} 在同一個 step 建立要寫入的收據。
     * <p>F6-2e-1（F6-2e Step 0 quality Minor 1、spec Minor-1）：{@link #crash} 的前提是「收據 → 參與者由艙外進入 → COLLAPSING → 窗口 halt」（收據之後的 step {@code enter} 與
     * {@code committed} 寫死），換 {@code origin} 與收據只能改它之前的部分。W10 不套用它：M4 時期的 Nether／End 收據由 §11.3 的取消路徑從 MEASURED 取消，不經 COLLAPSING（專屬的
     * {@code M5CollapseUnsupportedChain}）；W11 也不套用（crash 在開服時的 M5 authority 初始化，早於任何 tick；收據由 setup phase 寫進 M4 時期的 journal，W11 的 chain
     * 另寫）。原本這裡寫「例如 F6-2e 的 W10 的非 DORMANT 收據」，是錯的。
     * <p>F6-2e-2（F6-2e-1 quality nit：{@code beforeWrite} 在這裡、{@code install} 卻是 {@link #crash} 的參數，位置不對稱）：保留，理由是兩者屬於不同的東西。{@code beforeWrite} 改變收據的原生存檔
     * 帶進存檔的世界狀態（{@code waitinglate} 的 spawn chunks 半徑存進 level.dat），是「原艙與收據」的一部分，與 {@code origin}、{@code receipt} 一起決定 restart 開服時的磁碟；{@code install}
     * 在存檔之後才裝上的 hook 或 fault（discovery 的讀取失敗、投影寫入失敗、暫緩）只活在 crash 的 JVM 裡，是這條 chain 的窗口怎麼到達，與窗口列（{@code id}）一起給。
     */
    record CrashSetup(M5CollapseRun.Checked origin, M5CollapseRun.Checked beforeWrite, CrashReceipt receipt) {
        CrashSetup {
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(beforeWrite, "beforeWrite");
            Objects.requireNonNull(receipt, "receipt");
        }

        /** 收據寫入之前不另做事（{@code beforeWrite} 是 {@link M5CollapseRun#NOTHING}）。 */
        CrashSetup(M5CollapseRun.Checked origin, CrashReceipt receipt) {
            this(origin, M5CollapseRun.NOTHING, receipt);
        }

        /** 同一份原艙與收據，收據寫入之前改做 {@code action}（F6-2e-1）。 */
        CrashSetup withBeforeWrite(M5CollapseRun.Checked action) {
            return new CrashSetup(origin, action, receipt);
        }
    }

    /** F6-2d-2 的 crash phase 都用的 {@link CrashSetup}：一位參與者在艙外（{@code fixture.buildOrigin(1)}）、DORMANT 的 NEW 收據（{@code fixture.dormantNewReceipt()}）。 */
    CrashSetup dormantNewSetup() {
        return new CrashSetup(() -> fixture.buildOrigin(1), fixture::dormantNewReceipt);
    }

    /**
     * crash phase 的共用骨架（F6-2e Step 0，F6-2d-2 B2 quality Minor 2：原本是 {@code M5CollapseDoorChains} 的 private {@code crash}，{@code M5CollapseRetryChains} 的
     * {@code materializeFailureCrash} 另有一份每一步都相同的；移到這裡、改為 package-private，原艙與收據改由 {@code setup} 給）：fresh 世界蓋原艙（{@code build}，
     * {@link CrashSetup#origin}）→ 原艙註冊（{@code registered}）→ {@code beforeReceipt}（收據之前的準備，例如開門、在門口放 entity）→ {@code receipt}：寫入
     * {@link CrashSetup#receipt} 建立的收據、原生存檔，{@code install} 裝上這條 chain 的 hook 或 fault，再依窗口表裝上 {@code id} 的 crash 點 → 參與者由艙外進入（{@code enter}）→
     * COLLAPSING（{@code committed}）→ 最多 {@code haltTicks} 個 tick 等到窗口 halt（{@code halt}；halt 之前的取證在窗口表）。step 的名稱、順序與上限與移動之前的兩份相同。
     * 前提見 {@link CrashSetup}（W10、W11 不套用）。F6-2e-1：step {@code receipt} 先做 {@link CrashSetup#beforeWrite}；前兩步由 {@link #buildAndRegister} 給（{@code M5CollapseRetryChains}
     * 的其他 crash 也用）。F6-3 Step 0（F6-2e-3 quality Minor 3）：build 到 receipt 的前段抽成 {@link #throughTheReceipt}（W12 的 {@code M5CollapseConvergenceChain} 也用），
     * 這裡的 {@code afterWrite} 是 {@code install} 再依窗口表裝上 {@code id} 的 crash 點（與抽出之前的順序相同）。
     */
    Phase crash(CrashSetup setup, List<Step> beforeReceipt, M5CollapseRun.Checked install, CrashWindow id, int haltTicks) {
        var steps = new ArrayList<>(throughTheReceipt(setup, beforeReceipt, () -> {
            install.run();
            M5CollapseWindows.arm(run, fixture, id);
        }));
        steps.add(enter());
        steps.add(new Step("committed", 40, fixture::committed));
        steps.add(new Step("halt", haltTicks, () -> false));
        return Phase.halt(steps).started(fixture::requireFreshWorld);
    }

    /**
     * crash phase 的前兩步（F6-2e-1，F6-2e Step 0 quality nit：原本 {@link #crash} 與 {@code M5CollapseRetryChains.origin()} 各寫一份相同的）：fresh 世界蓋原艙與參與者（step
     * {@code build}，1 tick，本體是 {@code origin}）→ 原艙註冊、IDLE（{@code registered}，最多 200 tick）。
     */
    List<Step> buildAndRegister(M5CollapseRun.Checked origin) {
        return List.of(new Step("build", 1, () -> { origin.run(); return true; }), new Step("registered", 200, fixture::registered));
    }

    /**
     * crash phase 由 build 到收據的前段（F6-3 Step 0，F6-2e-3 quality Minor 3：原本 {@link #crash} 寫一份，W12 的 {@code M5CollapseConvergenceChain.crash} 另寫一份、只用了
     * {@link #dormantNewSetup} 的 {@code origin}，收據直接寫 {@code fixture.dormantNewReceipt()}、沒有 {@link CrashSetup#beforeWrite}，之後改骨架時會悄悄分岔）：{@link #buildAndRegister}
     * （{@code build}、{@code registered}）→ {@code beforeReceipt} → {@code receipt}（1 tick）：先 {@link CrashSetup#beforeWrite}，再寫入 {@link CrashSetup#receipt} 建立的收據並原生存檔，
     * 最後 {@code afterWrite}（這條 chain 在存檔之後才裝的 hook、fault 或 crash 點）。step 的名稱、順序與上限與抽出之前的兩份相同。
     */
    List<Step> throughTheReceipt(CrashSetup setup, List<Step> beforeReceipt, M5CollapseRun.Checked afterWrite) {
        var steps = new ArrayList<>(buildAndRegister(setup.origin()));
        steps.addAll(beforeReceipt);
        steps.add(new Step("receipt", 1, () -> {
            setup.beforeWrite().run();
            fixture.writeCrashReceipt(setup.receipt().build());
            afterWrite.run();
            return true;
        }));
        return List.copyOf(steps);
    }

    /**
     * 收據之後（例如 {@link #crash} 的 {@code install}）：discovery 的下一次 checked 讀取失敗一次（RESOLVE 第 4 步，SEALED(AUTHORITY_UNREADABLE)；建立收據的 candidate context 也讀
     * discovery，所以排在收據之後）。回傳裝上的 fault（命中次數）。F6-2e Step 0（F6-2d-2 B2 quality Minor 2）：原本 {@code M5CollapseDoorChains} 與
     * {@code M5CollapseRetryChains} 的 F6-2-07 crash 各寫一次。
     */
    M5TestHooks.CheckedReadFault discoveryReadFails() {
        return run.install(M5TestHooks.CHECKED_READ_FAULT, new M5TestHooks.CheckedReadFault(UniverseDiscoveryState.FILE_NAME, 0, 1));
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

    /**
     * restart 開服時原艙門的預期（F6-2e Step 0，F6-2d-2 B2 quality Minor 1）：窗口表 {@code id} 那一列的 {@link M5CollapseWindows.AtRestart#withDoorAtStart}（開服時磁碟上的門由
     * 窗口表的 {@code doorAsExpectedAtStart} 斷言）。chain 在開服時與排入時斷言記憶體裡的門，一律從這裡讀，不在 chain 再寫一份（原本 SEALED 的四條 chain 有 7 處寫死與窗口列相同的值）。
     * 那一列沒有宣告（F6-2e-1 起是 {@code AtRestart.doorAtStart} 的空值，原本是 {@code UNSPECIFIED}）時丟出；chain 在建立 phase 時（{@code onInitialize}）呼叫，所以在開服之前失敗。
     * F6-2e-1（F6-2e Step 0 quality nit）：由 {@code doorAtStart} 改名（與 {@code AtRestart.doorAtStart()} 同名、契約不同：那個回空值，這個丟出）。
     */
    static OriginDoorState requireDoorAtStart(CrashWindow id) {
        return M5CollapseWindows.of(id).atRestart().doorAtStart()
                .orElseThrow(() -> new IllegalStateException("窗口表的 " + id + " 沒有宣告開服時的原艙門（withDoorAtStart）"));
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
     * 完成的 tick）。記下提交的 tick。F6-2d-2 Step 0 由兩份合成（判斷、斷言與等待的 tick 數都不變）；F6-2d-2 B1 起經通用的 {@link #untilTriggered}（位置的斷言跟著
     * {@link Await#position}：兩者都是 {@link Position#NOT_MOVED_SINCE_THE_JOIN}，與之前呼叫端給的相同）。
     */
    Step committedWithoutReentry(String stepName, String assertion, Await await) {
        if (await == Await.EDGE) throw new IllegalArgumentException("committedWithoutReentry 是不離開再進入的提交，不能用 EDGE：" + stepName);
        return untilTriggered(stepName, assertion, await, () -> collapsing(run.journal().flushedRecords().get(fixture.sid())), tick -> {
            var record = run.journal().flushedRecords().get(fixture.sid());
            var info = run.capture().matching(Level.INFO, "塌縮已提交", "觸發=DORMANT 續行", fixture.sid().toString()).size();
            return new Verdict(collapsing(record) && info == 1 && M5CollapseFixture.destination(record).equals(Optional.of(fixture.universe())),
                    "JOIN 之後 " + tick + " 個 tick：state=" + M5GameTestSupport.state(run.journal(), fixture.sid()) + " 提交的 INFO=" + info);
        }, "committedAtPhaseTick");
    }

    /**
     * {@code record} 存在而且是 COLLAPSING。F6-2d-2 B2a（B1 quality nit）：原本這裡與 {@code M5CollapseDoorChains} 各有一份 private 的，{@code M5CollapseRetryChains} 另有同形的
     * 行內寫法；收成這一份 package-private 的。
     */
    static boolean collapsing(SessionRecoveryRecord record) {
        return record != null && record.state() == SessionState.COLLAPSING;
    }

    /**
     * 這個 session「進入 PREPARE（閘門成立）」的 INFO 的則數（F6-2d-2 B2a，B1 quality nit：原本 {@code M5CollapseRetryChains} 四處、{@code M5CollapseDoorChains} 一處各寫一次
     * 同樣的查詢）。
     */
    int prepares() {
        return run.capture().matching(Level.INFO, "進入 PREPARE（閘門成立）", fixture.sid().toString()).size();
    }

    /**
     * 參與者真 JOIN 在來源世界的艙內（crash 前存的位置），記下 JOIN 的位置（{@link #joinedAt}，之後以 {@link #notMovedSinceTheJoin} 斷言沒有移動）。F6-2d-2 B2a（B1 quality nit）：
     * 原本 {@code M5CollapseRetryChains} 兩處、{@code M5CollapseDoorChains} 一處各寫一次這兩行。
     */
    void joinInside() {
        fixture.joinAllInTheSourceWorld(Inside.INSIDE);
        joinedAt = fixture.players().getFirst().getPos();
    }

    /** {@link #untilTriggered} 等完之後（已發生，或等滿 {@link Await#waitTicks}）這一步的結果：{@code tick} 是這個 step 的第幾個 tick（由 1 起）。 */
    @FunctionalInterface interface Outcome { Verdict at(int tick) throws Exception; }

    /** {@link Outcome} 的結果：具名斷言是否成立，與失敗時的細節（位置的部分由 {@link #untilTriggered} 接上）。 */
    record Verdict(boolean holds, String detail) {}

    /**
     * 通用的 await step（F6-2d-2 B1，A 段 quality 給 B 段的建議：{@link #committedWithoutReentry} 與 {@link #retried} 原本各寫一份同樣的等法）：step {@code stepName} 每個 tick 問
     * {@code happened}；還沒有發生、而且還在等待的時間內就再等，否則斷言 {@code assertion}：已發生、{@code outcome} 成立，而且 {@link Await#position} 是
     * {@link Position#NOT_MOVED_SINCE_THE_JOIN} 時參與者 JOIN 之後沒有移動（{@link Position#UNCHECKED}：不斷言位置）。上限只有 {@link Await#waitTicks}（也是 step 的上限）。
     * {@link Await#AFTER_THE_LOAD} 每個 tick 另記下原艙附近載入完成的 tick（{@code chamberLoadedAtPhaseTick}）；{@code proofKey} 不是 null 時記下斷言的 tick。判斷、斷言、等待的
     * tick 數與記下的 proof 都與合併之前相同。
     * <p>F6-2d-2 B2a（B1 quality Minor 2）：委派給通用的 {@link M5CollapsePassage#awaiting}（上限與「到時沒有發生」都是 {@link Await#waitTicks}，與之前的 {@code keepWaiting}
     * 相同）。B1 quality nit：{@code still} 改成 {@link Position}，與 {@code happened} 不同型別，對調不能編譯。
     * <p>F6-2e Step 0（F6-2d-2 B2 quality Minor 5）：{@code chamberLoadedAtPhaseTick} 改由 {@code awaiting} 的 {@code eachTick} 記下（原本包在 {@code happened} 的查詢裡、排在
     * {@code happened} 之後）；{@code happened} 只讀。順序因此改為先記 proof 再問 {@code happened}：兩者都只讀、在同一個 tick，記下的 tick 相同。F6-2d-2 B2 quality nit：
     * 由 {@code awaited} 改名（與 {@link M5CollapsePassage#awaiting} 太像），位置的斷言改由 {@link Await#position} 決定（原本是參數 {@code position}，呼叫端給的值與現在的欄位相同）。
     */
    Step untilTriggered(String stepName, String assertion, Await await, M5CollapseRun.CheckedCondition happened, Outcome outcome, String proofKey) {
        // F6-2e-1（F6-2e Step 0 quality nit）：happened 與 M5CollapsePassage.awaiting 同型別（原本是 BooleanSupplier，這裡再以 getAsBoolean 轉交）。
        return M5CollapsePassage.awaiting(stepName, await.waitTicks, tick -> {
            if (await == Await.AFTER_THE_LOAD && chamberLoaded()) run.proof.putIfAbsent("chamberLoadedAtPhaseTick", run.ticks());
        }, happened, (tick, now) -> {
            var verdict = outcome.at(tick);
            boolean checked = await.position == Position.NOT_MOVED_SINCE_THE_JOIN, notMoved = !checked || notMovedSinceTheJoin();
            run.expect(assertion, now && verdict.holds() && notMoved, () -> verdict.detail() + (checked ? " 沒有移動=" + notMoved : ""));
            if (proofKey != null) run.proof.put(proofKey, run.ticks());
        });
    }

    /**
     * step {@code stepName}：由 SEALED({@code reason}) 重試（「SEALED 重試：SEALED(reason)→COLLAPSING」的 INFO 恰一則），這時這個 session 的重試恰為第 {@code count} 次
     * （具名斷言 {@code assertion}）；等法是 {@code await}（{@link Await}：{@link Await#EDGE} 是由艙外進入之後；{@link Await#AFTER_THE_LOAD} 是 H7 的
     * {@code retriedWithoutReentry}，另斷言參與者沒有移動、記下原艙附近載入完成與重試的 tick）。F6-2d-2 Step 0：{@code retried(reason, count)} 與
     * {@code retriedWithoutReentry} 合成這一個；step 名稱改由呼叫端給（F6-2d-1 quality Minor 5：原本由 {@code count} 推出 {@code retried}／{@code retriedAgain}，receipt 的
     * witness 以 step 名稱為鍵，不能重複；runner 在建立 phase 時檢查重名）。F6-2d-2（F6-2d-2 Step 0 quality Minor 2）：也接受 {@link Await#FIRST_OBSERVATION}（開服 attach 的
     * session 第一次觀察到原參與者在艙內就算觸發，production 的 {@code CollapseService.sealed} 以 {@code track.entry(startupSessions.contains(sid))} 實作）；原本拒絕它，訊息也與 production 相反。
     * F6-2d-2 B1 起經通用的 {@link #untilTriggered}：{@link Await#EDGE} 不斷言位置（{@link Position#UNCHECKED}；F6-2d-2 B2a 之前是 {@code still} 為 null）、不記重試的 tick，其餘斷言
     * 參與者沒有移動、記下 {@code retriedAtPhaseTick}（與合併之前相同；F6-2e Step 0 起位置跟著 {@link Await#position}，值與之前的 {@code edge ? UNCHECKED : NOT_MOVED_SINCE_THE_JOIN} 相同）。
     */
    Step retried(String stepName, String assertion, SealedReason reason, int count, Await await) {
        boolean edge = await == Await.EDGE;
        return untilTriggered(stepName, assertion, await, () -> retriesFrom(reason) > 0, tick -> {
            int fromReason = retriesFrom(reason), all = retries(run, fixture.sid());
            return new Verdict(fromReason == 1 && all == count, (edge ? "" : "JOIN 之後 " + tick + " 個 tick：") + "SEALED(" + reason + ") 的重試 INFO=" + fromReason
                    + " 全部的重試 INFO=" + all + "，預期第 " + count + " 次");
        }, edge ? null : "retriedAtPhaseTick");
    }

    /** 這個 session 由 SEALED({@code reason}) 重試的 INFO（「SEALED 重試：SEALED(reason)→COLLAPSING」）的則數。 */
    private int retriesFrom(SealedReason reason) {
        return retriesFrom(run, fixture.sid(), reason);
    }

    /**
     * {@code sid} 由 SEALED({@code reason}) 重試的 INFO 的則數（F6-3 Step 0：由 {@link #retriesFrom(SealedReason)} 抽出 static 的一份，窗口表的 halt 之前取證（只拿得到 run 與
     * fixture）也用，例如 W12 的 {@code M5CollapseConvergenceChain.convergedAtHalt}；查詢與原本相同）。
     */
    static int retriesFrom(M5CollapseRun run, UUID sid, SealedReason reason) {
        return run.capture().matching(Level.INFO, "SEALED 重試：SEALED(" + reason + ")→COLLAPSING", sid.toString()).size();
    }

    /**
     * {@code sid} 全部的「SEALED 重試」INFO 的則數（F6-3 Step 0：原本 {@link #retried} 行內查詢，查詢相同）。F6-3c-1（F6-3 Step 0 quality Minor 3）起，probe 各 chain 的重試計數
     * 都經這裡與 {@link #retriesFrom(M5CollapseRun, UUID, SealedReason)}（{@code M5CollapseDoorChains}、{@code M5CollapseRetryChains} 原本各自寫同樣的查詢）。
     */
    static int retries(M5CollapseRun run, UUID sid) {
        return run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).size();
    }

    /**
     * {@code sid} 的 record（flushed 與 current）都與封存時的 {@code sealed} 相同（F6-3c-2，F6-3c-1 quality Minor 1：原本 {@code M5CollapseConvergenceChain} 的四處與
     * {@code M5CollapseRetryChains} 的一處各寫一份相同的判斷式；判斷不變）。{@code sealed} 是 null 時丟出 NullPointerException，與原本相同。
     */
    static boolean sealedRecordKept(M5CollapseRun run, UUID sid, SessionRecoveryRecord sealed) {
        return sealed.equals(run.journal().flushedRecords().get(sid)) && sealed.equals(run.journal().records().get(sid));
    }
}
