package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.BEDROCK;
import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.GLASS;
import static dev.quantumchamber.gametest.M5CollapseFixture.GOLD_BLOCK;
import static dev.quantumchamber.gametest.M5CollapseFixture.controllerBlock;
import static dev.quantumchamber.gametest.M5CollapseFixture.damagedShell;
import static dev.quantumchamber.gametest.M5CollapseFixture.interiorMarker;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import com.google.gson.JsonObject;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5TestHooks.CrashWindow;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionWorld;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：窗口表。每個 crash 窗口（{@link CrashWindow}）一列 {@link Window}：
 * <ul>
 * <li>crash 點的條件（hook 交來的 detail 是否就是那一刻）、halt 的時點（{@link M5CollapseRun.HaltAt}：那一刻，或那個 tick 結束時；F6-2c fix round 1）與 halt 之前的取證
 *     （寫進 {@code halt.json} 的 {@code atHalt}；丟出時不 halt）；那個 tick 結束時才 halt 的列，另可在那一刻取證（{@link AtWindow}，{@code atWindow}；F6-2d Step 0）；</li>
 * <li>restart 開服時的預期（{@link AtRestart}：參與者是否已返還、RESOLVE 在 halt 之前完成到哪一步（因此 halt 當下是否已物化、allocation、catalog、discovery
 *     的預期）、projection ledger 的狀態、窗口自己的磁碟檢查、JOIN 之前是否先載入並辨識投影 Controller、halt 當下磁碟上的 record 狀態與 Crossed）；</li>
 * <li>PREPARE 怎麼完成（{@link Prepared}：「準備完成」INFO 的模式與格數，與 READY 時的磁碟檢查；不檢查時為 null）。</li>
 * </ul>
 * {@link #expectAtRestart} 與 {@link M5CollapsePassage} 依這一列斷言。COLLAPSING 中、RESOLVE／PREPARE 期間的窗口（W1～W6x），新增時只要在 {@link #table} 加一列
 * （另要有 hook mixin 與 {@code CrashWindow} 的值，見 {@code scripts/verification/README.md}「M5 collapse probe」），restart 照 {@code M5CollapsePassage.steps} 推進到
 * (b) 終局。其他窗口（F6-2c：PASSAGE_READY 期間、終局之後）另以 {@link AtRestart} 的 {@code stateAtHalt}、{@code crossedAtHalt} 表達開服時的 record，restart 的
 * step 與終局（{@code M5CollapsePassage.End} 的逐人位置）由 chain 自己組。
 */
final class M5CollapseWindows {
    private M5CollapseWindows() {}

    /** crash 點的條件：{@code detail} 是不是這個窗口的那一刻（同一個 hook 每次呼叫都交出 detail）。 */
    @FunctionalInterface interface Condition { boolean test(M5CollapseFixture fixture, Object detail); }

    /**
     * halt 之前的取證（或刻意的準備）：寫進 {@code evidence}（{@code halt.json} 的 {@code atHalt}）；丟出時不 halt。F6-2c Step 0 由 {@code Evidence} 改名（與這一列的欄位
     * {@code beforeHalt} 同名），避免與 runner 的 {@code M5CollapseRun.HaltEvidence} 混淆。
     */
    @FunctionalInterface interface BeforeHalt { void collect(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception; }

    /**
     * {@link M5CollapseRun.HaltAt#TICK_END} 的列在窗口成立的那一刻（hook 裡，production 在那一刻之後的程式執行之前）的取證：寫進 {@code halt.json} 的 {@code atWindow}，
     * halt 時的 {@link BeforeHalt} 以 {@code run.atWindow()} 讀它（F6-2d Step 0，F6-2c fix round 1 spec re-review Minor 1）。參數與 {@link BeforeHalt} 不同（不需要 detail），
     * 窗口表的一列裡兩者互換位置時不能編譯。
     */
    @FunctionalInterface interface AtWindow { void record(M5CollapseRun run, M5CollapseFixture fixture, Map<String, Object> evidence) throws Exception; }

    /** restart 的磁碟檢查（開服時，或 READY 時）。 */
    @FunctionalInterface interface DiskCheck { void check(M5CollapseRun run, M5CollapseFixture fixture) throws Exception; }

    /** RESOLVE 在 halt 之前完成到哪一步（allocation → catalog → 物化 → discovery）。 */
    enum Resolved {
        /** 還沒有開始（W1：MEASURED→COLLAPSING 剛提交）。 */
        NOTHING,
        /** allocation ledger 已寫出（W2）。 */
        ALLOCATION,
        /** catalog 已寫出、還沒有物化（W3；重啟時由開服 bootstrap 物化）。 */
        CATALOG,
        /** 已物化、discovery 已追加（W5 與 PREPARE 的窗口）。 */
        DISCOVERY;

        boolean allocated() { return this != NOTHING; }

        boolean cataloged() { return compareTo(CATALOG) >= 0; }

        boolean materializedAtHalt() { return this == DISCOVERY; }

        boolean discovered() { return this == DISCOVERY; }
    }

    /**
     * restart 開服時（{@code SERVER_STARTED} 的 AFTER phase）的預期。F6-2c（F6-2a quality Minor 1、F6-2c Step 0 quality 給 F6-2c 本體的建議）：原本是 5 個位置參數的
     * record（兩個 boolean 互換位置也能編譯），改成具名的 factory 與 wither，沒有指定的欄位是預設值。以 {@link #resolved} 建立，再以 {@code with…} 改需要的欄位：
     * <ul>
     * <li>{@code resolved}：RESOLVE 在 halt 之前完成到哪一步（由它推出 halt 當下是否已物化，以及 allocation、catalog、discovery 的預期）。</li>
     * <li>{@link #notReturned}：參與者在 crash 前還沒有返還（W1：另斷言走廊 space 已重建、沒有保留式 recovery 的請求）；預設已返還。</li>
     * <li>{@link #withProjection}：projection ledger 這座艙在 U 的狀態；預設沒有 entry（null）。</li>
     * <li>{@link #withDisk}：窗口自己的磁碟檢查；預設 {@link #NO_CHECK}。</li>
     * <li>{@link #recognizingBeforeJoin}：JOIN 之前以 probe 的 ticket 載入投影 Controller，等它經 load-sync 辨識為 inert 的 PROJECTION（COMPLETE 時另斷言不變量 cell
     *     受保護）；預設不載入。</li>
     * <li>{@link #withStateAtHalt}：halt 當下磁碟上這個 session 的 record 狀態（restart 開服時 flushed 就是它）；預設 COLLAPSING（{@code restartedCollapsing}）。
     *     {@link #terminatedAtHalt} 為終局已落盤、record 已不在（{@code terminatedOnDisk}）。F6-2c 起取代 {@code expectAtRestart} 寫死的 {@code restartedCollapsing}。</li>
     * <li>{@link #withCrossedAtHalt}：halt 當下磁碟上的 Crossed（參與者的子集，開服時才由收據決定是哪幾位）；預設沒有人。只在狀態是 PASSAGE_READY 或 SEALED 時斷言
     *     （{@code crossedAtRestart}）。</li>
     * </ul>
     * <p>F6-2d Step 0（F6-2c quality Minor 6）起驗證組合，restart 會忽略、或要到開服之後才發現的組合丟出 IllegalStateException。F6-2d-1（F6-2d Step 0 quality Minor 4、5
     * 與給本體的建議）：
     * <ul>
     * <li>只看一個 wither 的規則留在 wither：{@code withStateAtHalt} 只接受 PASSAGE_READY、SEALED，{@code withStateAtHalt}／{@code terminatedAtHalt} 只能指定一次。</li>
     * <li>跨欄位的組合規則改由 {@link #validate} 在加入窗口表時（{@code put}）對最終組合驗證一次，訊息帶窗口 id，wither 的呼叫順序不再有限制：{@code withCrossedAtHalt} 只用在
     *     狀態是 PASSAGE_READY 或 SEALED 的列（COLLAPSING 與終局不斷言 Crossed）；{@code notReturned} 只用在 COLLAPSING（W1）；{@code recognizingBeforeJoin} 要有
     *     {@code withProjection}；{@code recognizingBeforeJoin} 與同一列非 null 的 {@link Prepared} 只用在 COLLAPSING 的列（通道與終局的 restart 不讀它們）；非 null 的欄位
     *     再檢查一次 null（預設值依 static 欄位的宣告順序）。</li>
     * </ul>
     * 窗口表在 class init 時建好，entrypoint 在開服之前就初始化它（{@link M5CollapseWindows#windows}），所以不合法的列在開服之前就失敗。
     */
    static final class AtRestart implements Cloneable {
        // F6-2d Step 0（F6-2c quality Minor 6）：欄位只在 copy（建立之後、交出之前）裡改，交出之後不再變動；窗口表在 class init 時建好，之後只讀。
        private boolean returned = true;
        private Resolved resolved;
        private ProjectionLedger.Entry.Status projection;
        private DiskCheck disk = NO_CHECK;
        private boolean recognizeBeforeJoin;
        private SessionState stateAtHalt = SessionState.COLLAPSING;
        private M5CollapseFixture.Who crossedAtHalt = M5CollapseFixture.NOBODY;

        private AtRestart() {}

        /**
         * RESOLVE 在 halt 之前完成到 {@code resolved}；其餘欄位是預設值（已返還、沒有 projection entry、不檢查磁碟、JOIN 之前不載入投影、磁碟上是 COLLAPSING、沒有人跨過）。
         */
        static AtRestart resolved(Resolved resolved) {
            var at = new AtRestart();
            at.resolved = Objects.requireNonNull(resolved, "resolved");
            return at;
        }

        /**
         * 這一份的複本，套用 {@code change} 之後交出（F6-2d Step 0，F6-2c quality Minor 6：取代每個 wither 各寫一次 7 個參數的建構子呼叫）。F6-2d-1（F6-2d Step 0 quality
         * Minor 4）：複本以 {@code super.clone()} 建立（淺複製全部欄位），不再逐欄手抄：新增欄位時不會因為忘了抄而被之後的 wither 重設；加欄位只改宣告與預設值。
         */
        private AtRestart copy(java.util.function.Consumer<AtRestart> change) {
            AtRestart next;
            try {
                next = (AtRestart) super.clone();
            } catch (CloneNotSupportedException impossible) {
                throw new AssertionError("AtRestart 是 Cloneable", impossible);
            }
            change.accept(next);
            return next;
        }

        /**
         * 一列的最終組合的驗證（見類別說明）。F6-2d-1（F6-2d Step 0 quality Minor 4 與給本體的建議）：原本每個 wither 交出之前驗證（{@code validated}），組合規則因此依 wither
         * 的呼叫順序；改由 {@link M5CollapseWindows#put} 對這一列的最終組合驗證一次，訊息帶窗口 id。{@code prepared} 是同一列的 PREPARE 預期（Minor 5 的規則）。不合法時丟出
         * IllegalStateException。
         */
        void validate(CrashWindow id, Prepared prepared) {
            String where = "窗口表的 " + id + "：AtRestart 的 ";
            // F6-2d Step 0 quality Minor 4：預設值依 static 欄位的宣告順序（例如 disk = NO_CHECK，NO_CHECK 要在 TABLE 之前初始化），順序改變時這裡丟出，不靜默變成 null。
            Objects.requireNonNull(resolved, where + "resolved");
            Objects.requireNonNull(disk, where + "disk（NO_CHECK 還沒有初始化？）");
            Objects.requireNonNull(crossedAtHalt, where + "crossedAtHalt（NOBODY 還沒有初始化？）");
            String state = stateAtHalt == null ? "終局已落盤" : stateAtHalt.name();
            boolean passage = stateAtHalt == SessionState.PASSAGE_READY || stateAtHalt == SessionState.SEALED;
            if (crossedAtHalt != M5CollapseFixture.NOBODY && !passage) {
                throw new IllegalStateException(where + "withCrossedAtHalt 只在 halt 當下是 PASSAGE_READY 或 SEALED 時斷言（crossedAtRestart），stateAtHalt=" + state);
            }
            boolean collapsing = stateAtHalt == SessionState.COLLAPSING;
            if (!returned && !collapsing) {
                throw new IllegalStateException(where + "notReturned 只用在 halt 當下是 COLLAPSING 的窗口（W1），stateAtHalt=" + state);
            }
            if (recognizeBeforeJoin && projection == null) {
                throw new IllegalStateException(where + "recognizingBeforeJoin 要有 projection entry（withProjection）");
            }
            // F6-2d Step 0 quality Minor 5：通道與終局的 restart（M5CollapsePassageChains）不讀這兩個欄位，指定了也會被忽略；只用在 halt 當下是 COLLAPSING 的列。
            if (recognizeBeforeJoin && !collapsing) {
                throw new IllegalStateException(where + "recognizingBeforeJoin 只用在 halt 當下是 COLLAPSING 的列（其他列的 restart 不載入投影），stateAtHalt=" + state);
            }
            if (prepared != null && !collapsing) {
                throw new IllegalStateException("窗口表的 " + id + "：Prepared（" + prepared.name() + "）只用在 halt 當下是 COLLAPSING 的列（其他列的 restart 不檢查「準備完成」），stateAtHalt="
                        + state);
            }
        }

        /** {@code withStateAtHalt} 與 {@code terminatedAtHalt} 只能指定一次（預設 COLLAPSING 之後再指定時丟出，不靜默覆蓋）。 */
        private void stateNotYetSet(String name) {
            if (stateAtHalt != SessionState.COLLAPSING) {
                throw new IllegalStateException("AtRestart：" + name + " 之前已指定 stateAtHalt=" + (stateAtHalt == null ? "終局已落盤" : stateAtHalt));
            }
        }

        /** 參與者在 crash 前還沒有返還（W1）。 */
        AtRestart notReturned() { return copy(next -> next.returned = false); }

        /** projection ledger 這座艙在 U 的狀態是 {@code status}。 */
        AtRestart withProjection(ProjectionLedger.Entry.Status status) {
            Objects.requireNonNull(status, "status");
            return copy(next -> next.projection = status);
        }

        /** 窗口自己的磁碟檢查。 */
        AtRestart withDisk(DiskCheck check) {
            Objects.requireNonNull(check, "disk");
            return copy(next -> next.disk = check);
        }

        /** JOIN 之前載入並辨識投影 Controller。 */
        AtRestart recognizingBeforeJoin() { return copy(next -> next.recognizeBeforeJoin = true); }

        /** halt 當下磁碟上的 record 是 {@code state}（PASSAGE_READY、SEALED；COLLAPSING 是預設，不經這裡；其他狀態丟出）。 */
        AtRestart withStateAtHalt(SessionState state) {
            Objects.requireNonNull(state, "state");
            if (state != SessionState.PASSAGE_READY && state != SessionState.SEALED) {
                throw new IllegalStateException("AtRestart：withStateAtHalt 只接受 PASSAGE_READY、SEALED（COLLAPSING 是預設）：" + state);
            }
            stateNotYetSet("withStateAtHalt(" + state + ")");
            return copy(next -> next.stateAtHalt = state);
        }

        /** halt 當下終局已落盤：restart 開服時 journal 沒有這個 session。 */
        AtRestart terminatedAtHalt() {
            stateNotYetSet("terminatedAtHalt");
            return copy(next -> next.stateAtHalt = null);
        }

        /** halt 當下磁碟上的 Crossed 恰為 {@code crossed}。 */
        AtRestart withCrossedAtHalt(M5CollapseFixture.Who crossed) {
            Objects.requireNonNull(crossed, "crossedAtHalt");
            return copy(next -> next.crossedAtHalt = crossed);
        }

        /** halt 當下磁碟上的 record 狀態；null 為終局已落盤。 */
        SessionState stateAtHalt() { return stateAtHalt; }

        /** halt 當下磁碟上的 Crossed。 */
        M5CollapseFixture.Who crossedAtHalt() { return crossedAtHalt; }

        /** 參與者在 crash 前是否已返還。 */
        boolean returned() { return returned; }

        /** RESOLVE 在 halt 之前完成到哪一步。 */
        Resolved resolved() { return resolved; }

        /** projection ledger 這座艙在 U 的狀態（null 為沒有 entry）。 */
        ProjectionLedger.Entry.Status projection() { return projection; }

        /** 窗口自己的磁碟檢查（{@link #NO_CHECK} 不檢查）。 */
        DiskCheck disk() { return disk; }

        /** JOIN 之前是否載入並辨識投影 Controller。 */
        boolean recognizeBeforeJoin() { return recognizeBeforeJoin; }
    }

    /** PREPARE 怎麼完成：「準備完成」INFO 恰一則，模式是 {@code mode}、寫入 {@code cells} 格（{@link #ANY_CELLS}：不是 0 格即可），之後 READY 時的磁碟檢查。 */
    record Prepared(String name, ProjectionWriter.Mode mode, int cells, DiskCheck afterReady) {
        /** 這一則「準備完成」的訊息符合預期的模式與格數。 */
        boolean matches(String message) {
            if (cells == ANY_CELLS) return message.contains("投影=" + mode.name() + "（寫入 ") && !message.contains("（寫入 0 格）");
            return message.contains("投影=" + mode.name() + "（寫入 " + cells + " 格）");
        }
    }

    static final int ANY_CELLS = -1;

    /**
     * 窗口表的一列。{@code haltTiming} 是 halt 的時點（F6-2c fix round 1；預設 {@link M5CollapseRun.HaltAt#WINDOW}，{@code beforeHalt} 也在那一刻執行；F6-2d Step 0 由
     * {@code haltAt} 改名，與 halt receipt 的鍵相同）。F6-2d-1 起由建立這一列的 factory 決定：{@link #row} 是 {@code WINDOW}，{@link #tickEndRow} 是 {@code TICK_END}。
     */
    record Window(CrashWindow id, Condition condition, BeforeHalt beforeHalt, AtRestart atRestart, Prepared prepared, M5CollapseRun.HaltAt haltTiming,
            AtWindow atWindow) {}

    static final DiskCheck NO_CHECK = (run, fixture) -> { };
    private static final BeforeHalt NO_EVIDENCE = (run, fixture, detail, evidence) -> { };
    /** 窗口成立的那一刻不另取證（{@link AtWindow}；W7 以外的每一列）。 */
    private static final AtWindow NOTHING_AT_THE_WINDOW = (run, fixture, evidence) -> { };
    /** W4、W4b 的 restart：照第一次寫入重寫（PENDING），READY 時 checkpoint 已把方塊存到磁碟。 */
    private static final Prepared FIRST_WRITE_AGAIN = new Prepared("rewrittenAsFirstWrite", ProjectionWriter.Mode.FIRST_WRITE, ANY_CELLS,
            M5CollapseWindows::checkpointedBeforeReady);

    private static final Map<CrashWindow, Window> TABLE = table();

    private static Map<CrashWindow, Window> table() {
        var rows = new EnumMap<CrashWindow, Window>(CrashWindow.class);
        // W1：live session 的 MEASURED→COLLAPSING 剛提交、返還之前（RESOLVE 還沒有開始）。
        row(rows, CrashWindow.COLLAPSING_COMMITTED, M5CollapseWindows::collapsingCommitted, M5CollapseWindows::committedBeforeTheReturn,
                AtRestart.resolved(Resolved.NOTHING).notReturned(), null);
        // W2：allocation ledger 寫出之後、catalog 之前。
        row(rows, CrashWindow.ALLOCATION_APPENDED, M5CollapseWindows::appended, NO_EVIDENCE,
                AtRestart.resolved(Resolved.ALLOCATION), null);
        // W3：catalog 寫出之後、物化之前（重啟時由 EAGER_ENABLED 物化）。
        row(rows, CrashWindow.CATALOG_ALLOCATED, M5CollapseWindows::appended, NO_EVIDENCE,
                AtRestart.resolved(Resolved.CATALOG), null);
        // W5：discovery 追加之後、投影之前。
        row(rows, CrashWindow.DISCOVERY_APPENDED, M5CollapseWindows::appended, NO_EVIDENCE,
                AtRestart.resolved(Resolved.DISCOVERY), null);
        // W4：第一次寫入剛寫完 Controller 那一格（halt 前存檔：磁碟上是寫到一半的投影）。
        row(rows, CrashWindow.PROJECTION_CELL_WRITTEN, M5CollapseWindows::controllerCellWritten, M5CollapseWindows::savedHalfWrittenProjection,
                AtRestart.resolved(Resolved.DISCOVERY).withProjection(ProjectionLedger.Entry.Status.PENDING).withDisk(M5CollapseWindows::controllerOnDiskAtRestart)
                        .recognizingBeforeJoin(), FIRST_WRITE_AGAIN);
        // W4b：投影寫完並驗證、目的 chunks 的 checkpoint 之前（磁碟上沒有投影）。
        row(rows, CrashWindow.DESTINATION_CHECKPOINT, M5CollapseWindows::destinationCheckpoint, M5CollapseWindows::projectionOnlyInMemory,
                AtRestart.resolved(Resolved.DISCOVERY).withProjection(ProjectionLedger.Entry.Status.PENDING).withDisk(M5CollapseWindows::noProjectionOnDiskAtRestart),
                FIRST_WRITE_AGAIN);
        // W6：projection ledger 寫入 COMPLETE 之後、PASSAGE_READY 之前（重啟之後修復 0 格）。
        row(rows, CrashWindow.PROJECTION_COMPLETE, M5CollapseWindows::projectionCompleted, M5CollapseWindows::completeOnDisk,
                AtRestart.resolved(Resolved.DISCOVERY).withProjection(ProjectionLedger.Entry.Status.COMPLETE).withDisk(M5CollapseWindows::controllerOnDiskAtRestart)
                        .recognizingBeforeJoin(),
                new Prepared("repairedNothing", ProjectionWriter.Mode.REPAIR, 0, NO_CHECK));
        // W6 的 EXISTING 再造訪：REPAIR 寫了格之後、checkpoint 之前（重啟之後再修復那一格，interior 的標記保留）。
        row(rows, CrashWindow.PROJECTION_REPAIRED, M5CollapseWindows::repairWroteCells, M5CollapseWindows::repairOnlyInMemory,
                AtRestart.resolved(Resolved.DISCOVERY).withProjection(ProjectionLedger.Entry.Status.COMPLETE).withDisk(M5CollapseWindows::repairLostWithTheCrash),
                new Prepared("repairedAgain", ProjectionWriter.Mode.REPAIR, 1, M5CollapseWindows::repairCheckpointedBeforeReady));
        // ---- F6-2c：PASSAGE_READY 期間與終局的窗口（chain 在 M5CollapsePassageChains；RESOLVE、PREPARE 都已在 halt 之前完成，投影 COMPLETE） ----
        var afterReady = AtRestart.resolved(Resolved.DISCOVERY).withProjection(ProjectionLedger.Entry.Status.COMPLETE);
        // W7（spec §12）：PASSAGE_READY 期間兩人中選門者已跨過（Crossed 已 checked 寫入）。F6-2c fix round 1（spec v5.7 §10.3）：crash 在 Crossed 確認的那個 tick 結束時
        // （production 在確認之後存下選門者的 playerdata），halt 之前不存檔；chain 在跨越之前（PASSAGE_READY 期間）存過一次檔。
        // F6-2d Step 0（F6-2c fix round 1 spec re-review Minor 1）：Crossed 確認的那一刻另記選門者的 playerdata（selectorPlayerDataAtTheWindow），halt 時斷言它那時還不在 U。
        tickEndRow(rows, CrashWindow.CROSSED_COMMITTED, M5CollapseWindows::selectorPlayerDataAtTheWindow,
                (fixture, detail) -> selectorCrossing(fixture, detail, SessionRecoveryState.CommitResult.Status.COMMITTED), M5CollapseWindows::selectorAcrossAtTheTickEnd,
                afterReady.withStateAtHalt(SessionState.PASSAGE_READY).withCrossedAtHalt(M5CollapseFixture.SELECTOR).withDisk(M5CollapseWindows::selectorAcrossOnDiskAtRestart), null);
        // F6-2-16：COLLAPSING→PASSAGE_READY 已 checked 提交、門改為 passable 之前；halt 之前原生存檔（PREPARE 的 sealed 量子膜、參與者在艙內都在磁碟上）。
        row(rows, CrashWindow.READY_COMMITTED, M5CollapseWindows::readyCommitted, M5CollapseWindows::savedBeforePassable,
                afterReady.withStateAtHalt(SessionState.PASSAGE_READY).withDisk(M5CollapseWindows::sealedDoorOnDiskAtRestart), null);
        // F6-2-13：通道以 (d) 結束的 checked 終局已讀回確認、改門之前（chain 在 PASSAGE_READY 期間存過檔：磁碟上的門是 passable、Controller 是 PASSAGE）。
        row(rows, CrashWindow.PASSAGE_TERMINATED, M5CollapseWindows::passageTerminated, M5CollapseWindows::terminatedBeforeTheDoor,
                afterReady.terminatedAtHalt().withDisk(M5CollapseWindows::passageOnDiskAtRestart), null);
        // F6-2-17：Crossed 的寫出沒有確認（ROLL_FORWARD_PENDING）：玩家已 MOVED，磁碟上的 Crossed 沒有他，playerdata 也沒有存到 U。F6-2c fix round 1：crash 在那個 tick
        // 結束時（spec v5.7 §10.3：寫入沒有確認時 production 不存 playerdata，halt 時磁碟上仍在來源世界）。
        tickEndRow(rows, CrashWindow.CROSSED_UNCONFIRMED,
                (fixture, detail) -> selectorCrossing(fixture, detail, SessionRecoveryState.CommitResult.Status.ROLL_FORWARD_PENDING), M5CollapseWindows::movedButNotOnDisk,
                afterReady.withStateAtHalt(SessionState.PASSAGE_READY).withDisk(M5CollapseWindows::playerDataInTheSourceAtRestart), null);
        // F6-2-12：PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED) 已 checked 寫入（Crossed＝選門者、RecoveryRequired＝另一位），之後的釋放、門與 ERROR 之前。選門者的
        // playerdata 在他跨越、Crossed 確認時已存在 U（spec v5.7 §10.3），另一位仍是觸發之前存的來源世界。
        row(rows, CrashWindow.RECOVERY_SEALED, (fixture, detail) -> recoveryWrite(fixture, detail, SessionRecoveryState.CommitResult.Status.COMMITTED),
                M5CollapseWindows::recoverySealedOnDisk,
                afterReady.withStateAtHalt(SessionState.SEALED).withCrossedAtHalt(M5CollapseFixture.SELECTOR).withDisk(M5CollapseWindows::recoveryRequiredKeptAtRestart), null);
        // F6-2-12 的 pendingRecovery：TRR 的寫入被拒（ERROR 已記、通道在記憶體內關閉）之後，退避期過後的重試又被拒；磁碟仍是 PASSAGE_READY。
        row(rows, CrashWindow.RECOVERY_REJECTED, (fixture, detail) -> recoveryWrite(fixture, detail, SessionRecoveryState.CommitResult.Status.REJECTED)
                        && fixture.sessionLogged(Level.ERROR, REJECTED_RECOVERY_ERROR),
                M5CollapseWindows::recoveryOnlyInTheLog,
                afterReady.withStateAtHalt(SessionState.PASSAGE_READY).withDisk(M5CollapseWindows::playerDataInTheSourceAtRestart), null);
        return Collections.unmodifiableMap(rows);
    }

    /** {@code PassageService.recoveryRequired} 第一次被拒時的 ERROR 的字句（F6-2-12 的 pendingRecovery）。 */
    static final String REJECTED_RECOVERY_ERROR = "SEALED(TRANSFER_RECOVERY_REQUIRED) 的寫入被拒";

    /** {@code WINDOW} 的列（預設）：crash 點成立的那一刻（hook 裡）就取證（{@code beforeHalt}）、halt。 */
    private static void row(Map<CrashWindow, Window> rows, CrashWindow id, Condition condition, BeforeHalt beforeHalt, AtRestart atRestart, Prepared prepared) {
        put(rows, new Window(id, condition, beforeHalt, atRestart, prepared, M5CollapseRun.HaltAt.WINDOW, NOTHING_AT_THE_WINDOW));
    }

    /**
     * {@code TICK_END} 的列（F6-2c fix round 1：{@code beforeHalt} 在窗口成立的那個 tick 結束時執行）。F6-2d-1（F6-2d Step 0 quality 給本體的建議）：原本是多一個
     * {@code haltTiming} 參數的 {@code row}，另在那一刻取證的版本只有 {@code TICK_END} 合法、要到 class init 才擋下；改成專用的 factory，halt 的時點由 factory 決定，誤用在編譯期擋下。
     */
    private static void tickEndRow(Map<CrashWindow, Window> rows, CrashWindow id, Condition condition, BeforeHalt beforeHalt, AtRestart atRestart, Prepared prepared) {
        tickEndRow(rows, id, NOTHING_AT_THE_WINDOW, condition, beforeHalt, atRestart, prepared);
    }

    /**
     * 同上，另在窗口成立的那一刻取證（{@code atWindow}，F6-2d Step 0）。{@code WINDOW} 的 {@code beforeHalt} 本來就在那一刻執行，所以只有 {@code TICK_END} 的列有這個欄位
     * （F6-2d-1 起由 factory 保證，{@link #put} 另做防守的檢查）。
     */
    private static void tickEndRow(Map<CrashWindow, Window> rows, CrashWindow id, AtWindow atWindow, Condition condition, BeforeHalt beforeHalt, AtRestart atRestart,
            Prepared prepared) {
        put(rows, new Window(id, condition, beforeHalt, atRestart, prepared, M5CollapseRun.HaltAt.TICK_END, Objects.requireNonNull(atWindow, "atWindow")));
    }

    /**
     * 加入一列：先驗證這一列的 {@link AtRestart} 與 {@link Prepared} 的最終組合（{@link AtRestart#validate}，F6-2d-1）；不合法、或同一個窗口再加入時丟出（窗口表在 class init 時
     * 建好，開服之前就失敗）。
     */
    private static void put(Map<CrashWindow, Window> rows, Window window) {
        Objects.requireNonNull(window.atRestart(), "窗口表的 " + window.id() + "：atRestart").validate(window.id(), window.prepared());
        if (window.atWindow() != NOTHING_AT_THE_WINDOW && window.haltTiming() != M5CollapseRun.HaltAt.TICK_END) {
            throw new IllegalStateException("窗口表的 " + window.id() + "：只有 TICK_END 的列另在窗口成立的那一刻取證（WINDOW 的 beforeHalt 已在那一刻）");
        }
        if (rows.put(window.id(), window) != null) throw new IllegalStateException("窗口表重複：" + window.id());
    }

    /**
     * 窗口表的全部窗口（F6-2d Step 0）。{@code M5CollapseRecoveryProbe.onInitialize} 在開服之前呼叫，讓窗口表在那時建好（class init）：不合法的列（{@link AtRestart} 的組合、
     * 重複的窗口）在開服之前就丟出，不等到 crash 點裝上或 restart 讀這一列的時候。
     */
    static Set<CrashWindow> windows() {
        return TABLE.keySet();
    }

    /** 這個窗口的一列；表裡沒有時丟出。 */
    static Window of(CrashWindow id) {
        var window = TABLE.get(id);
        if (window == null) throw new IllegalArgumentException("probe 的窗口表沒有這個窗口：" + id);
        return window;
    }

    /** 依窗口表裝上 {@code id} 的 crash 點（{@link M5CollapseRun#armCrash}，halt 的時點是這一列的 {@code haltTiming}；halt receipt 的 detail 以 {@link #describe} 描述）。 */
    static void arm(M5CollapseRun run, M5CollapseFixture fixture, CrashWindow id) {
        var window = of(id);
        run.armCrash(id, window.haltTiming(), detail -> window.condition().test(fixture, detail), M5CollapseWindows::describe,
                evidence -> window.atWindow().record(run, fixture, evidence),
                (detail, evidence) -> window.beforeHalt().collect(run, fixture, detail, evidence));
    }

    // ---- crash 點的條件 ----

    /** W2、W3、W5：checked 寫入回傳 {@code APPENDED}。 */
    private static boolean appended(M5CollapseFixture fixture, Object detail) {
        return detail instanceof CollapseAuthorities.Lookup<?> lookup && lookup.status() == CollapseAuthorities.Lookup.Status.APPENDED;
    }

    /** W1：這個 session 的 MEASURED→COLLAPSING 已提交（checked 讀回確認）。 */
    private static boolean collapsingCommitted(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.CollapsingCommit commit && commit.result().committed() && commit.expected().state() == SessionState.MEASURED
                && commit.next().state() == SessionState.COLLAPSING && commit.next().sessionUuid().equals(fixture.sid());
    }

    /** W4：第一次寫入剛寫完 U 的 Controller 那一格（targets 依 x→y→z：Controller 之前與目標不同的格已寫，之後的還沒有）。 */
    private static boolean controllerCellWritten(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ProjectionCell cell && cell.mode() == ProjectionWriter.Mode.FIRST_WRITE
                && cell.pos().equals(FRAME.controllerPos()) && fixture.isUniverse(cell.world());
    }

    /** W4b：U 的原生存檔 checkpoint 開始之前（投影方塊已寫完並驗證）。 */
    private static boolean destinationCheckpoint(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ChunkCheckpoint checkpoint && checkpoint.relevant().keySet().stream().anyMatch(fixture::isUniverse);
    }

    /** W6：projection ledger 已 checked 寫入這座艙在 U 的 COMPLETE。 */
    private static boolean projectionCompleted(M5CollapseFixture fixture, Object detail) {
        return detail instanceof CollapseAuthorities.WriteResult result && result.status() == CollapseAuthorities.WriteResult.Status.WRITTEN
                && result.entry().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE && entry.universeId().equals(fixture.universe())
                        && entry.chamberUuid().equals(fixture.chamber())).isPresent();
    }

    /** W6 的 EXISTING 再造訪：U 的 REPAIR 寫了格（{@code written > 0}），checkpoint 之前。 */
    private static boolean repairWroteCells(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.ProjectionWrite write && write.mode() == ProjectionWriter.Mode.REPAIR
                && write.result().status() == ProjectionWriter.Status.WRITTEN && write.result().written() > 0 && fixture.isUniverse(write.world());
    }

    /** F6-2-16：這個 session 的 COLLAPSING→PASSAGE_READY 已提交（checked 讀回確認）。 */
    private static boolean readyCommitted(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.CollapsingCommit commit && commit.result().committed() && commit.expected().state() == SessionState.COLLAPSING
                && commit.next().state() == SessionState.PASSAGE_READY && commit.next().sessionUuid().equals(fixture.sid());
    }

    /** W7、F6-2-17：這個 session 的 PASSAGE_READY 加入 Crossed 的 roll-forward 寫入，結果是 {@code status}，寫入之後的 Crossed 恰為選門者。 */
    private static boolean selectorCrossing(M5CollapseFixture fixture, Object detail, SessionRecoveryState.CommitResult.Status status) {
        return detail instanceof M5TestHooks.RollForwardWrite write && write.result().status() == status && write.expected().state() == SessionState.PASSAGE_READY
                && write.next().state() == SessionState.PASSAGE_READY && write.next().sessionUuid().equals(fixture.sid())
                && write.next().collapse().crossed().equals(List.of(fixture.selectorId()));
    }

    /** F6-2-12：這個 session 的 PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED) 的 roll-forward 寫入，結果是 {@code status}。 */
    private static boolean recoveryWrite(M5CollapseFixture fixture, Object detail, SessionRecoveryState.CommitResult.Status status) {
        return detail instanceof M5TestHooks.RollForwardWrite write && write.result().status() == status && write.expected().state() == SessionState.PASSAGE_READY
                && write.next().state() == SessionState.SEALED && write.next().sessionUuid().equals(fixture.sid())
                && write.next().collapse().sealedReason().equals(Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED));
    }

    /** F6-2-13：這個 session 的通道終局（PASSAGE_ENDED）已讀回確認不在 journal。 */
    private static boolean passageTerminated(M5CollapseFixture fixture, Object detail) {
        return detail instanceof M5TestHooks.Termination termination && termination.origin() == SessionRecoveryState.TerminalOrigin.PASSAGE_ENDED
                && termination.result().removed() && termination.expected().sessionUuid().equals(fixture.sid());
    }

    // ---- halt 之前的取證 ----

    /**
     * W1 的 halt 之前：磁碟上的 journal 已是 COLLAPSING（Destination 是推導出的 U），參與者還沒有返還、lease 還在；每位參與者的 playerdata 存在走廊（選門之前的
     * 原生存檔）；allocation ledger 還是空的（RESOLVE 還沒有開始）。
     */
    private static void committedBeforeTheReturn(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var onDisk = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid()).orElse(null);
        evidence.put("journalStateOnDisk", onDisk == null ? "ABSENT" : onDisk.state().name());
        run.expect("journalCollapsingAtHalt", onDisk != null && onDisk.state() == SessionState.COLLAPSING
                        && M5CollapseFixture.destination(onDisk).equals(Optional.of(fixture.universe())),
                () -> "磁碟上的 journal 是 " + (onDisk == null ? "ABSENT" : onDisk.state() + " Destination=" + onDisk.collapse().destination()));
        run.expect("notReturnedAtHalt", onDisk.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !onDisk.spaceLeases().isEmpty(),
                () -> "磁碟上的參與者或 lease：" + onDisk.participants() + " leases=" + onDisk.spaceLeases());
        var dimensions = new LinkedHashMap<String, String>();
        for (var id : fixture.participants()) dimensions.put(id.toString(), M5CollapseDiskEvidence.playerDimension(run.saveRoot(), id));
        evidence.put("playerDimensionOnDisk", dimensions);
        run.expect("playerDataInTheCorridorAtHalt", dimensions.values().stream().allMatch(SuperpositionWorld.KEY.getValue().toString()::equals),
                () -> "磁碟上參與者的 playerdata 在 " + dimensions);
        run.expect("noAllocationAtHalt", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty(), () -> "allocation ledger=" + fixture.allocationsOrError());
    }

    /**
     * W4 的 halt 之前：把 U 存到磁碟（{@code world.save(null, true, false)}：寫到一半的方塊落盤），再以 raw MCA 讀回。寫入迴圈在同一個 tick 內做完，正常情況下磁碟不會有
     * 寫到一半的方塊；這是刻意造出的最壞情況（「寫入途中 crash＋之前剛好存過檔」），比實際嚴格。實際會發生的「PENDING、磁碟上沒有方塊」由 W4b 涵蓋。
     */
    private static void savedHalfWrittenProjection(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var cell = (M5TestHooks.ProjectionCell) detail;
        evidence.put("cellChanged", cell.changed());
        // writeCell 的 hook 到 Controller 為止（含）被呼叫幾次（crash 點的 reached）：只有與目標不同的格（needsWrite）才呼叫 writeCell，所以這是寫到一半的格數，不是全部 189 格。
        evidence.put("writeCellCallsThroughTheController", run.crashPoint().reached());
        cell.world().save(null, true, false);
        var controller = fixture.diskBlock(cell.world(), FRAME.controllerPos());
        var first = fixture.diskBlock(cell.world(), local(0, 0, 0));
        var last = fixture.diskBlock(cell.world(), local(6, 6, 6));
        evidence.put("controllerOnDisk", controller.toString());
        evidence.put("firstCellOnDisk", first.toString());
        evidence.put("lastCellOnDisk", last.toString());
        run.expect("controllerOnDiskBeforeHalt", controllerBlock(controller), () -> "存檔之後磁碟上的 Controller 位置是 " + controller);
        run.expect("halfWrittenOnDisk", BEDROCK.equals(first.name()) && !BEDROCK.equals(last.name()), () -> "第一格 " + first + "，最後一格 " + last);
        run.expect("projectionPendingAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection("PENDING"))),
                () -> "projection ledger=" + fixture.projectionsOrError());
    }

    /** W4b 的 halt 之前：投影方塊只在記憶體（Controller 已寫入 U），磁碟上沒有；projection ledger 是 PENDING。 */
    private static void projectionOnlyInMemory(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var checkpoint = (M5TestHooks.ChunkCheckpoint) detail;
        evidence.put("checkpointChunks", checkpoint.relevant().get(world).stream().map(ChunkPos::toString).sorted().toList());
        var disk = fixture.diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        run.expect("projectionWrittenInMemory", world.getBlockState(FRAME.controllerPos()).isOf(ModBlocks.CHAMBER_CONTROLLER)
                && world.getBlockState(local(6, 6, 6)).isOf(Blocks.BEDROCK), () -> "U 的投影方塊還沒有寫入");
        run.expect("projectionNotOnDiskAtHalt", !controllerBlock(disk), () -> "checkpoint 之前磁碟上已有 Controller：" + disk);
        run.expect("projectionPendingAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection("PENDING"))),
                () -> "projection ledger=" + fixture.projectionsOrError());
    }

    /** W6 的 halt 之前：COMPLETE 已在磁碟上（checkpoint 已存下投影），journal 仍是 COLLAPSING。 */
    private static void completeOnDisk(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var disk = fixture.diskBlock(world, FRAME.controllerPos());
        evidence.put("controllerOnDisk", disk.toString());
        run.expect("controllerOnDiskBeforeHalt", controllerBlock(disk), () -> "checkpoint 之後磁碟上的 Controller 位置是 " + disk);
        run.expect("projectionCompleteAtHalt", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        var state = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid()).map(record -> record.state().name()).orElse("ABSENT");
        run.expect("journalCollapsingAtHalt", SessionState.COLLAPSING.name().equals(state), () -> "磁碟上的 journal 是 " + state);
    }

    /** W6x 的 halt 之前：REPAIR 已在記憶體修好被改壞的 shell，磁碟上仍是改壞的狀態；interior 的標記不受影響。 */
    private static void repairOnlyInMemory(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.ProjectionWrite) detail;
        var world = write.world();
        evidence.put("written", write.result().written());
        var disk = fixture.diskBlock(world, damagedShell());
        evidence.put("damagedShellOnDisk", disk.toString());
        run.expect("repairedInMemory", world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                () -> "shell=" + world.getBlockState(damagedShell()) + " marker=" + world.getBlockState(interiorMarker()));
        run.expect("repairNotOnDiskAtHalt", GLASS.equals(disk.name()), () -> "checkpoint 之前磁碟上的 shell 是 " + disk);
    }

    /**
     * 通道的窗口（F6-2c）：磁碟上這個 session 的 record exact 是 {@code expected}（名稱 {@code name}），並把它的摘要（{@link M5CollapseFixture#recordDigest}）記在
     * {@code atHalt} 的 {@code recordAtHalt}（restart 開服時比對相同：opened／expires、Crossed、RecoveryRequired 都沒有變）。
     */
    private static void recordOnDisk(M5CollapseRun run, M5CollapseFixture fixture, Map<String, Object> evidence, SessionRecoveryRecord expected, String name) throws Exception {
        var onDisk = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid()).orElse(null);
        evidence.put("recordAtHalt", M5CollapseFixture.recordDigest(expected));
        evidence.put("doorCenterOnDisk", doorCenterOnDisk(run, fixture));
        run.expect(name, expected.equals(onDisk), () -> "磁碟上的 record=" + (onDisk == null ? "ABSENT" : onDisk.state() + " crossed=" + onDisk.collapse().crossed()));
    }

    /** 每位參與者的 playerdata 在磁碟上記的世界（寫進 {@code atHalt} 的 {@code playerDimensionOnDisk}）。 */
    private static Map<UUID, String> playerDimensions(M5CollapseRun run, M5CollapseFixture fixture, Map<String, Object> evidence) throws Exception {
        var dimensions = new LinkedHashMap<UUID, String>();
        for (var id : fixture.participants()) dimensions.put(id, M5CollapseDiskEvidence.playerDimension(run.saveRoot(), id));
        var shown = new LinkedHashMap<String, String>();
        dimensions.forEach((id, dimension) -> shown.put(id.toString(), dimension));
        evidence.put("playerDimensionOnDisk", shown);
        return dimensions;
    }

    /** {@code who} 的每一位的 playerdata 都在 {@code world}（磁碟）。 */
    private static boolean playerDataIn(M5CollapseRun run, M5CollapseFixture fixture, M5CollapseFixture.Who who, ServerWorld world) throws Exception {
        for (var id : fixture.members(who)) {
            if (!world.getRegistryKey().getValue().toString().equals(M5CollapseDiskEvidence.playerDimension(run.saveRoot(), id))) return false;
        }
        return true;
    }

    /**
     * 原生存檔（刻意的準備，比照 W4 的 halt 前存檔）：模擬 crash 之前剛好有過一次 autosave，之後到 crash 都沒有任何寫出。磁碟上因此有這一刻的 playerdata、原艙門與
     * Controller。playerdata 只在原生 autosave、登出與停服時存；production 另外只在跨越的 Crossed 確認之後存那位玩家（spec v5.7 §10.3，F6-2c fix round 1），所以沒有這一步時
     * crash 之後沒跨過的玩家回到上一次存檔的位置（F6-2-17 的判定）。chunk 則另有原版的逐步存檔（{@code ServerChunkLoadingManager.unloadChunks}：tick 有餘裕時每個 tick
     * 最多 20 個變更過的 chunk、同一個 chunk 至少隔 10 秒），沒有這一步時磁碟上的門與 Controller 是 crash 之前最後一次存下的樣子，不固定。
     */
    private static void nativeSaveAtHalt(M5CollapseRun run) {
        run.expect("nativeSaveAtHalt", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
    }

    /**
     * W7 的 halt 之前（F6-2c fix round 1：Crossed 確認的那個 tick 結束時，{@link M5CollapseRun.HaltAt#TICK_END}；halt 之前不存檔）：磁碟上的 record 是 Crossed＝[選門者] 的
     * PASSAGE_READY；選門者在 U，另一位在原艙內。chain 在跨越之前（PASSAGE_READY 期間）存過一次檔，所以另一位的 playerdata 在來源世界、原艙門 passable、Controller 是
     * PASSAGE（比較器 15）都在磁碟上。選門者 halt 時的 playerdata 只記下（{@code playerDimensionOnDisk}），不在這裡斷言：它是這個窗口的目標，由 restart 開服時的磁碟檢查
     * （{@code selectorPlayerDataInTheUniverse}；halt 不存檔，開服時的磁碟就是 halt 時的）與 JOIN（{@code joinedInTheUniverse}）斷言，所以沒有 production 的存檔時 crash 照常、
     * restart 失敗在目標斷言。
     * <p>F6-2d Step 0（F6-2c fix round 1 spec re-review Minor 1）：Crossed 確認的那一刻（{@link #selectorPlayerDataAtTheWindow}）選門者的 playerdata 還不在 U
     * （{@code selectorPlayerDataNotInTheUniverseAtTheWindow}；F6-2d-1 起要求恰為來源世界，也就是跨越之前那一次存檔的位置）：存檔在 Crossed 寫入確認之後，不是之前。只看 halt
     * 時的磁碟分不出這兩種順序（寫入確認時兩種順序在磁碟上的結果相同）。
     */
    private static void selectorAcrossAtTheTickEnd(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.RollForwardWrite) detail;
        recordOnDisk(run, fixture, evidence, write.next(), "crossedOnDiskAtHalt");
        var universe = fixture.universeWorld().orElseThrow();
        run.expect("selectorInTheUniverseAtHalt", fixture.selector().getServerWorld() == universe, () -> "選門者在 " + fixture.selector().getServerWorld().getRegistryKey().getValue());
        var atTheWindow = run.atWindow().get(SELECTOR_PLAYER_DIMENSION);
        // F6-2d-1（F6-2d Step 0 quality nit）：原本只要求「不是 U」（也接受 ABSENT）；那一刻的 playerdata 是跨越之前的存檔（saved），所以要求恰為來源世界。名稱不變。
        run.expect("selectorPlayerDataNotInTheUniverseAtTheWindow", run.source().getRegistryKey().getValue().toString().equals(atTheWindow),
                () -> "Crossed 確認的那一刻選門者的 playerdata 在 " + atTheWindow + "，不是來源世界（atWindow=" + run.atWindow() + "）");
        for (var id : fixture.members(M5CollapseFixture.OTHERS)) {
            var player = fixture.player(id);
            run.expect("othersInsideTheChamberAtHalt", player.getServerWorld() == run.source() && M5CollapseFixture.inside(player), () -> "另一位在 " + player.getPos());
        }
        playerDimensions(run, fixture, evidence);
        run.expect("othersPlayerDataInTheSourceAtHalt", playerDataIn(run, fixture, M5CollapseFixture.OTHERS, run.source()), () -> "playerdata=" + evidence.get("playerDimensionOnDisk"));
        run.expect("passableDoorOnDiskAtHalt", doorOnDisk(run, fixture, true, MembraneState.passableFor(FRAME.outwardFacing())), () -> "磁碟上的原艙門不是 passable");
        var controller = controllerStateOnDisk(run, fixture);
        evidence.put("controllerStateOnDisk", controller);
        run.expect("passageControllerOnDiskAtHalt", ChamberState.PASSAGE.name().equals(controller), () -> "磁碟上的 Controller 是 " + controller);
    }

    /** W7 的 {@code atWindow} 的鍵：Crossed 確認的那一刻選門者的 playerdata 在磁碟上記的世界。 */
    private static final String SELECTOR_PLAYER_DIMENSION = "selectorPlayerDimensionOnDisk";

    /**
     * W7 在 Crossed 確認的那一刻（{@code commitRollForward} 的回傳點，production 的 {@code PassageService.saveCrossedPlayerData} 之前；F6-2d Step 0）：記下選門者的 playerdata
     * 在磁碟上記的世界（{@code atWindow} 的 {@value #SELECTOR_PLAYER_DIMENSION}）。只記下；斷言在 halt 時（{@link #selectorAcrossAtTheTickEnd}）。
     */
    private static void selectorPlayerDataAtTheWindow(M5CollapseRun run, M5CollapseFixture fixture, Map<String, Object> evidence) throws Exception {
        evidence.put(SELECTOR_PLAYER_DIMENSION, M5CollapseDiskEvidence.playerDimension(run.saveRoot(), fixture.selectorId()));
    }

    /**
     * F6-2-16 的 halt 之前：磁碟上的 record 是剛提交的 PASSAGE_READY，門還是 PREPARE 的 sealed 量子膜（還沒有改為 passable）。原生存檔之後，sealed 的門與參與者在艙內的
     * playerdata 都在磁碟上（沒有在下一個 tick 正規化就會把人關在艙內）。
     */
    private static void savedBeforePassable(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var commit = (M5TestHooks.CollapsingCommit) detail;
        recordOnDisk(run, fixture, evidence, commit.next(), "readyOnDiskAtHalt");
        run.expect("sealedDoorBeforePassable", M5GameTestSupport.doorSealed(run.source(), FRAME), () -> "READY 提交的那一刻原艙門不是 PREPARE 的 sealed 量子膜");
        for (var player : fixture.players()) run.expect("insideTheChamberAtHalt", M5CollapseFixture.inside(player), () -> "參與者在 " + player.getPos());
        nativeSaveAtHalt(run);
        playerDimensions(run, fixture, evidence);
        run.expect("playerDataInTheSourceAtHalt", playerDataIn(run, fixture, M5CollapseFixture.EVERYONE, run.source()), () -> "playerdata=" + evidence.get("playerDimensionOnDisk"));
        run.expect("sealedDoorOnDiskAtHalt", doorOnDisk(run, fixture, true, MembraneState.sealedFor(FRAME.outwardFacing())), () -> "磁碟上的原艙門不是 sealed 量子膜");
    }

    /**
     * F6-2-13 的 halt 之前：終局已落盤（磁碟上沒有這個 session），原艙門還是 passable（改門之前）。chain 在 PASSAGE_READY 期間存過檔，所以磁碟上的門也是 passable、
     * Controller 是 PASSAGE（重啟之後找不到所屬 session 的量子膜）。
     */
    private static void terminatedBeforeTheDoor(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var onDisk = M5CollapseDiskEvidence.journalRecord(run.data(), fixture.sid());
        evidence.put("journalStateOnDisk", onDisk.map(record -> record.state().name()).orElse("ABSENT"));
        run.expect("terminatedOnDiskAtHalt", onDisk.isEmpty(), () -> "磁碟上仍有這個 session：" + evidence.get("journalStateOnDisk"));
        run.expect("doorStillPassableAtHalt", M5GameTestSupport.doorPassable(run.source(), FRAME), () -> "改門之前原艙門已不是 passable");
        run.expect("passableDoorOnDiskAtHalt", doorOnDisk(run, fixture, true, MembraneState.passableFor(FRAME.outwardFacing())), () -> "磁碟上的原艙門不是 passable");
        var controller = controllerStateOnDisk(run, fixture);
        evidence.put("controllerStateOnDisk", controller);
        run.expect("passageControllerOnDiskAtHalt", ChamberState.PASSAGE.name().equals(controller), () -> "磁碟上的 Controller 是 " + controller);
    }

    /**
     * F6-2-17 的 halt 之前（F6-2c fix round 1：那個 tick 結束時，{@link M5CollapseRun.HaltAt#TICK_END}）：磁碟上的 record 仍是寫入之前的 PASSAGE_READY（Crossed 是空的），
     * collapse view 的 Crossed 有他、roll-forward 未確認；他已在 U，但 playerdata 沒有存到 U（磁碟上仍是來源世界：spec v5.7 §10.3，寫入沒有確認時 production 不存，
     * 磁碟上的 Crossed 與 playerdata 一致）。
     */
    private static void movedButNotOnDisk(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.RollForwardWrite) detail;
        recordOnDisk(run, fixture, evidence, write.expected(), "crossedNotOnDiskAtHalt");
        var sid = fixture.sid();
        var view = run.journal().collapseView(sid).orElse(null);
        run.expect("crossedPendingInTheCollapseView", run.journal().rollForwardPending(sid) && view != null && view.collapse().crossed().equals(List.of(fixture.selectorId())),
                () -> "collapse view=" + (view == null ? "ABSENT" : view.collapse().crossed()));
        var universe = fixture.universeWorld().orElseThrow();
        run.expect("movedToTheUniverseAtHalt", fixture.selector().getServerWorld() == universe, () -> "玩家在 " + fixture.selector().getServerWorld().getRegistryKey().getValue());
        playerDimensions(run, fixture, evidence);
        run.expect("playerDataNotInTheUniverseAtHalt", playerDataIn(run, fixture, M5CollapseFixture.EVERYONE, run.source()), () -> "playerdata=" + evidence.get("playerDimensionOnDisk"));
    }

    /** F6-2-12 的 halt 之前：磁碟上的 record 是 SEALED(TRR)，Crossed＝[選門者]、RecoveryRequired＝另一位；另一位沒有被移動（仍在來源世界）。不存檔。 */
    private static void recoverySealedOnDisk(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.RollForwardWrite) detail;
        recordOnDisk(run, fixture, evidence, write.next(), "recoverySealedOnDiskAtHalt");
        var others = fixture.members(M5CollapseFixture.OTHERS);
        run.expect("recoveryRequiredForTheOther", Set.copyOf(write.next().collapse().recoveryRequired()).equals(others)
                        && write.next().collapse().crossed().equals(List.of(fixture.selectorId())),
                () -> "RecoveryRequired=" + write.next().collapse().recoveryRequired() + " Crossed=" + write.next().collapse().crossed());
        for (var id : others) {
            var player = fixture.player(id);
            run.expect("recoveryPlayerInTheSourceAtHalt", player.getServerWorld() == run.source(), () -> "無法回滾的玩家在 " + player.getServerWorld().getRegistryKey().getValue());
        }
    }

    /**
     * F6-2-12 的 pendingRecovery，halt 之前：磁碟與 current 都仍是 PASSAGE_READY（寫入被拒，journal 沒有改變、沒有待確認的 roll-forward）；被拒的 ERROR 恰一則（之後只剩這一則
     * log 證據，寫進 {@code atHalt} 的 {@code recoveryError}）；通道在記憶體內關閉，原艙門是一般開門。
     */
    private static void recoveryOnlyInTheLog(M5CollapseRun run, M5CollapseFixture fixture, Object detail, Map<String, Object> evidence) throws Exception {
        var write = (M5TestHooks.RollForwardWrite) detail;
        recordOnDisk(run, fixture, evidence, write.expected(), "passageReadyOnDiskAtHalt");
        var sid = fixture.sid();
        run.expect("journalUnchangedAtHalt", write.expected().equals(run.journal().records().get(sid)) && !run.journal().rollForwardPending(sid),
                () -> "current=" + M5GameTestSupport.state(run.journal(), sid));
        var errors = run.capture().matching(Level.ERROR, REJECTED_RECOVERY_ERROR, sid.toString()).stream().map(LogCapture.Entry::message).toList();
        evidence.put("recoveryError", errors.isEmpty() ? "-" : errors.getFirst());
        run.expect("oneRejectedRecoveryError", errors.size() == 1 && errors.getFirst().contains("需要管理員處理"), () -> "被拒的 ERROR：" + errors);
        run.expect("plainOpenWhilePassageClosedAtHalt", M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE), () -> "通道在記憶體內關閉時原艙門不是一般開門");
    }

    // ---- restart 開服時 ----

    /**
     * restart 的開服檢查（W1、DORMANT 與通道的 restart 共用）：窗口在 RESOLVE 的哪一步（halt 當下是否已物化）；record 是這一列的 {@code stateAtHalt}（預設 COLLAPSING）且
     * Destination 與候選不變（PASSAGE_READY、SEALED 另斷言 Crossed 是 {@code crossedAtHalt}；終局已落盤時改斷言 journal 沒有這個 session）；參與者還沒有返還的窗口（W1）另斷言
     * 走廊 space 已重建、沒有保留式 recovery 的請求；allocation、catalog（與開服 bootstrap 的物化）、discovery、projection 依這一列的預期，再做窗口自己的磁碟檢查。
     * {@code recognizeBeforeJoin} 的窗口在這裡以 probe 的 ticket 載入投影 Controller、開始觀察 load-sync，回傳它；否則回 null。
     */
    static M5CollapseProjectionLoad expectAtRestart(M5CollapseRun run, M5CollapseFixture fixture, Window window, JsonObject halt) throws Exception {
        var at = window.atRestart();
        boolean materializedWindow = at.resolved().materializedAtHalt();
        boolean activeAtHalt = halt.get("universeActiveAtHalt").getAsBoolean();
        run.expect(materializedWindow ? "materializedBeforeTheCrash" : "notMaterializedBeforeTheCrash",
                activeAtHalt == materializedWindow, () -> "halt 當下 U 的物化狀態=" + activeAtHalt);
        var sid = fixture.sid();
        var current = run.journal().flushedRecords().get(sid);
        var state = at.stateAtHalt();
        if (state == null) {
            run.expect("terminatedOnDisk", current == null && !run.journal().records().containsKey(sid), () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        } else {
            run.expect(restartedAs(state), current != null && current.state() == state, () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
            run.expect("sameDestination", M5CollapseFixture.destination(current).equals(Optional.of(fixture.universe())),
                    () -> "Destination=" + current.collapse().destination());
            run.expect("noRedraw", M5CollapseFixture.sameCandidate(fixture.receipt(), current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
            if (state != SessionState.COLLAPSING) {
                var crossed = fixture.members(at.crossedAtHalt());
                run.expect("crossedAtRestart", current.collapse().crossed().size() == crossed.size() && Set.copyOf(current.collapse().crossed()).equals(crossed),
                        () -> "Crossed=" + current.collapse().crossed() + "，預期 " + crossed);
            }
        }
        if (!at.returned()) {
            // W1（spec §13.2）：未返還的 COLLAPSING 在 attach 時重建走廊 space，返還由 receipt 推導、不經 requestMeasuredRecovery。
            run.expect("notReturnedAtRestart", current.participants().stream().noneMatch(SessionRecoveryRecord.Participant::returned) && !current.spaceLeases().isEmpty()
                    && MeasuredRecoveryPhase.from(current) == MeasuredRecoveryPhase.RETURN_PLAYERS, () -> "參與者 " + current.participants() + " leases=" + current.spaceLeases());
            run.expect("spaceRestored", fixture.spaceExists(), () -> "開服之後沒有這個 session 的走廊 space");
            run.expect("noMeasuredRecoveryRequest", !fixture.pages().measuredRecoveryRequested(sid), () -> "COLLAPSING 的 space 標記為已請求保留式 recovery");
        }
        if (at.resolved().allocated()) {
            fixture.expectOneAllocation();
        } else {
            run.expect("noAllocationYet", () -> M5CollapseDiskEvidence.allocations(run.data()).isEmpty(), () -> "allocation ledger=" + fixture.allocationsOrError());
        }
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords();
        boolean discovered = M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().anyMatch(record -> record.universeId().equals(fixture.universe()));
        boolean active = fixture.universeWorld().isPresent();
        if (!at.resolved().cataloged()) {
            run.expect("catalogNotYetAllocated", catalog.size() == fixture.catalogBefore() && !catalog.containsKey(fixture.universe()), () -> "catalog=" + catalog.keySet());
            run.expect("notYetMaterialized", !active, () -> "U 已物化");
        } else {
            fixture.expectCatalog("catalogAllocatedOnce");
            // spec §12 W3：重啟時由 EAGER_ENABLED 物化（UniverseLifecycleService 的開服 bootstrap，早於這個 SERVER_STARTED 的 AFTER phase）。
            run.expect("bootstrapMaterializedEagerEnabled", active, () -> "開服 bootstrap 沒有物化 U");
        }
        if (at.resolved().discovered()) run.expect("alreadyDiscovered", discovered, () -> "discovery 沒有 U");
        else run.expect("notYetDiscovered", !discovered, () -> "discovery 已有 U");
        var expectedProjections = at.projection() == null ? List.<M5CollapseDiskEvidence.Projection>of() : List.of(fixture.projection(at.projection().name()));
        var projectionName = at.projection() == null ? "noProjectionYet"
                : at.projection() == ProjectionLedger.Entry.Status.PENDING ? "projectionStillPending" : "projectionCompleteOnDisk";
        run.expect(projectionName, () -> M5CollapseDiskEvidence.projections(run.data()).equals(expectedProjections), () -> "projection ledger=" + fixture.projectionsOrError());
        at.disk().check(run, fixture);
        M5CollapseProjectionLoad load = null;
        if (at.recognizeBeforeJoin()) {
            load = M5CollapseProjectionLoad.start(run, fixture);
            // spec Minor（F6-2a）：辨識是本 JVM 做的：剛由磁碟載入時是 ORIGIN、power 還沒有初始化、load-sync 還在佇列（checkpoint 存下的是 refresh 之前的 block entity）。
            var atLoad = load.atLoad();
            run.expect("unrecognizedWhenLoaded", atLoad.instanceKind() == ChamberInstanceKind.ORIGIN && !atLoad.powerInitialized() && atLoad.loadSyncPending(),
                    () -> "載入時的 Controller：" + atLoad);
        }
        run.proof.put("startedDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), sid));
        return load;
    }

    /** {@code stateAtHalt} 的斷言名稱：COLLAPSING 沿用 {@code restartedCollapsing}（F6-1 起的名稱），其餘依狀態。 */
    private static String restartedAs(SessionState state) {
        return switch (state) {
            case COLLAPSING -> "restartedCollapsing";
            case PASSAGE_READY -> "restartedPassageReady";
            case SEALED -> "restartedSealed";
            default -> throw new IllegalArgumentException("窗口表的 stateAtHalt 只能是塌縮狀態：" + state);
        };
    }

    /** W4、W6 的開服：磁碟上 U 的 Controller 位置是 Controller（寫到一半或 COMPLETE 的投影）。 */
    private static void controllerOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var disk = fixture.diskBlock(fixture.universeWorld().orElseThrow(), FRAME.controllerPos());
        run.expect("controllerOnDiskAtRestart", controllerBlock(disk), () -> "磁碟上的 Controller 位置是 " + disk);
    }

    /** W4b 的開服：磁碟上沒有投影方塊（Controller 位置不是 Controller，最後一格不是基岩）。 */
    private static void noProjectionOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var controller = fixture.diskBlock(world, FRAME.controllerPos());
        var last = fixture.diskBlock(world, local(6, 6, 6));
        run.expect("noProjectionOnDiskAtRestart", !controllerBlock(controller) && !BEDROCK.equals(last.name()),
                () -> "磁碟上已有投影方塊：Controller 位置 " + controller + "，最後一格 " + last);
    }

    /** W6x 的開服：REPAIR 隨 crash 遺失（磁碟上被改壞的 shell 仍是玻璃），interior 的標記在磁碟上。 */
    private static void repairLostWithTheCrash(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var shell = fixture.diskBlock(world, damagedShell());
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("repairLostWithTheCrash", GLASS.equals(shell.name()), () -> "磁碟上被改壞的 shell 是 " + shell);
        run.expect("interiorMarkerOnDisk", GOLD_BLOCK.equals(marker.name()), () -> "磁碟上的 interior 標記是 " + marker);
    }

    /** F6-2-13 的開服（W7 也用）：磁碟上的原艙門是 passable 量子膜、Controller 是 PASSAGE（重啟之前存下的是 PASSAGE／15，R2 M5）。 */
    private static void passageOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        run.expect("passableDoorOnDiskAtRestart", doorOnDisk(run, fixture, true, MembraneState.passableFor(FRAME.outwardFacing())), () -> "磁碟上的原艙門不是 passable");
        var controller = controllerStateOnDisk(run, fixture);
        run.expect("passageControllerOnDiskAtRestart", ChamberState.PASSAGE.name().equals(controller), () -> "磁碟上的 Controller 是 " + controller);
    }

    /**
     * W7 的開服：{@link #passageOnDiskAtRestart}（跨越之前存下的），另外選門者的 playerdata 在 U（spec v5.7 §10.3：production 在 Crossed 確認之後存下，halt 之前沒有其他
     * 存檔）、另一位在來源世界（{@link #crossedPlayerDataAtRestart}）。
     */
    private static void selectorAcrossOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        passageOnDiskAtRestart(run, fixture);
        crossedPlayerDataAtRestart(run, fixture);
    }

    /** W7、F6-2-12 的開服：已跨過的選門者的 playerdata 在 U（Crossed 確認時存下，spec v5.7 §10.3），其餘參與者在來源世界（沒有跨過，playerdata 是之前存的）。 */
    private static void crossedPlayerDataAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var universe = fixture.universeWorld().orElseThrow();
        var selector = M5CollapseDiskEvidence.playerDimension(run.saveRoot(), fixture.selectorId());
        run.expect("selectorPlayerDataInTheUniverse", universe.getRegistryKey().getValue().toString().equals(selector), () -> "選門者的 playerdata 在 " + selector);
        run.expect("othersPlayerDataInTheSource", playerDataIn(run, fixture, M5CollapseFixture.OTHERS, run.source()), () -> "另一位的 playerdata 不在來源世界");
    }

    /** F6-2-16 的開服：磁碟上的原艙門是 PREPARE 的 sealed 量子膜，參與者的 playerdata 在來源世界（艙內）。 */
    private static void sealedDoorOnDiskAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        run.expect("sealedDoorOnDiskAtRestart", doorOnDisk(run, fixture, true, MembraneState.sealedFor(FRAME.outwardFacing())), () -> "磁碟上的原艙門不是 sealed 量子膜");
        playerDataInTheSourceAtRestart(run, fixture);
    }

    /**
     * F6-2-17、F6-2-12 的 pendingRecovery 的開服：參與者的 playerdata 在來源世界（沒有人的 Crossed 確認過，production 不存：F6-2-17 的寫出沒有確認，pendingRecovery 的
     * 傳送無法回滾、沒有加入 Crossed）。
     */
    private static void playerDataInTheSourceAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        run.expect("playerDataInTheSourceWorld", playerDataIn(run, fixture, M5CollapseFixture.EVERYONE, run.source()), () -> "參與者的 playerdata 不在來源世界");
    }

    /**
     * F6-2-12 的開服：record 仍是 SEALED(TRANSFER_RECOVERY_REQUIRED)，RecoveryRequired 仍是另一位（永久原因，收據保留）；已跨過的選門者的 playerdata 在 U、另一位在來源世界
     * （{@link #crossedPlayerDataAtRestart}，F6-2c fix round 1）。
     */
    private static void recoveryRequiredKeptAtRestart(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var current = run.journal().flushedRecords().get(fixture.sid());
        run.expect("recoveryRequiredKept", current.collapse().sealedReason().equals(Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED))
                        && Set.copyOf(current.collapse().recoveryRequired()).equals(fixture.members(M5CollapseFixture.OTHERS)),
                () -> "SEALED 的原因=" + current.collapse().sealedReason() + " RecoveryRequired=" + current.collapse().recoveryRequired());
        crossedPlayerDataAtRestart(run, fixture);
    }

    /** 磁碟上（raw MCA）原艙門的 25 格都是 bulkhead，而且 open 與 membrane 都符合。 */
    private static boolean doorOnDisk(M5CollapseRun run, M5CollapseFixture fixture, boolean open, MembraneState membrane) throws Exception {
        var directory = fixture.dimensionDirectory(run.source());
        var bulkhead = Registries.BLOCK.getId(ModBlocks.QUANTUM_BULKHEAD).toString();
        for (var pos : M5GameTestSupport.door(FRAME)) {
            var disk = M5CollapseDiskEvidence.block(directory, pos);
            if (!bulkhead.equals(disk.name()) || !disk.properties().contains("open:\"" + open + "\"") || !disk.properties().contains("membrane:\"" + membrane.asString() + "\"")) {
                return false;
            }
        }
        return true;
    }

    /** 磁碟上（raw MCA）原艙門正中那一格（區域 (3,3,0)）的方塊與屬性（取證用）。 */
    static String doorCenterOnDisk(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var disk = M5CollapseDiskEvidence.block(fixture.dimensionDirectory(run.source()), local(3, 3, 0));
        return disk.name() + disk.properties();
    }

    /** 磁碟上（raw MCA）原艙 Controller 的 {@code ChamberState}。 */
    private static String controllerStateOnDisk(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        return M5CollapseDiskEvidence.blockEntityString(fixture.dimensionDirectory(run.source()), FRAME.controllerPos(), "ChamberState");
    }

    // ---- PREPARE 怎麼完成 ----

    /** PASSAGE_READY 時：「準備完成」INFO 恰一則、符合這一列的模式與格數，再做 READY 時的磁碟檢查。{@code prepared} 為 null 時不檢查。 */
    static void expectPrepared(M5CollapseRun run, M5CollapseFixture fixture, Prepared prepared) throws Exception {
        if (prepared == null) return;
        var messages = run.capture().matching(Level.INFO, "準備完成（預載與投影都已完成）", fixture.sid().toString()).stream().map(LogCapture.Entry::message).toList();
        run.expect(prepared.name(), messages.size() == 1 && prepared.matches(messages.getFirst()), () -> "「準備完成」=" + messages);
        prepared.afterReady().check(run, fixture);
    }

    /** W4、W4b：READY 之前的 checkpoint 已把重寫的方塊存到磁碟（最後一格是基岩、Controller 在）。 */
    private static void checkpointedBeforeReady(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var last = fixture.diskBlock(world, local(6, 6, 6));
        var controller = fixture.diskBlock(world, FRAME.controllerPos());
        run.expect("checkpointedBeforeReady", BEDROCK.equals(last.name()) && controllerBlock(controller), () -> "READY 時磁碟上最後一格 " + last + "，Controller 位置 " + controller);
    }

    /** W6x：READY 之前的 checkpoint 已把修好的 shell 存到磁碟，interior 的標記保留（記憶體與磁碟）。 */
    private static void repairCheckpointedBeforeReady(M5CollapseRun run, M5CollapseFixture fixture) throws Exception {
        var world = fixture.universeWorld().orElseThrow();
        var shell = fixture.diskBlock(world, damagedShell());
        run.expect("repairCheckpointedBeforeReady", BEDROCK.equals(shell.name()) && world.getBlockState(damagedShell()).isOf(Blocks.BEDROCK),
                () -> "READY 時磁碟上被改壞的 shell 是 " + shell);
        var marker = fixture.diskBlock(world, interiorMarker());
        run.expect("interiorMarkerKept", GOLD_BLOCK.equals(marker.name()) && world.getBlockState(interiorMarker()).isOf(Blocks.GOLD_BLOCK),
                () -> "interior 標記：磁碟 " + marker + "，記憶體 " + world.getBlockState(interiorMarker()));
    }

    // ---- halt receipt 的 detail ----

    /** 窗口交來的 detail 的文字描述（寫進 {@code halt.json} 的 {@code detail}）。 */
    static String describe(Object detail) {
        return switch (detail) {
            case M5TestHooks.CollapsingCommit commit -> "edge=" + commit.expected().state() + "→" + commit.next().state() + " session=" + commit.next().sessionUuid()
                    + " status=" + commit.result().status();
            case M5TestHooks.RollForwardWrite write -> "edge=" + write.expected().state() + "→" + write.next().state() + " session=" + write.next().sessionUuid()
                    + " status=" + write.result().status() + " crossed=" + write.next().collapse().crossed() + " recoveryRequired=" + write.next().collapse().recoveryRequired()
                    + " sealed=" + write.next().collapse().sealedReason().map(Enum::name).orElse("-");
            case M5TestHooks.Termination termination -> "origin=" + termination.origin() + " from=" + termination.expected().state() + " session="
                    + termination.expected().sessionUuid() + " status=" + termination.result().status();
            case CollapseAuthorities.Lookup<?> lookup -> "status=" + lookup.status() + " value=" + lookup.value().map(String::valueOf).orElse("-");
            case CollapseAuthorities.WriteResult result -> "status=" + result.status() + " entry=" + result.entry().map(String::valueOf).orElse("-");
            case M5TestHooks.ProjectionCell cell -> "world=" + cell.world().getRegistryKey().getValue() + " mode=" + cell.mode() + " pos=" + cell.pos().toShortString()
                    + " changed=" + cell.changed();
            case M5TestHooks.ProjectionWrite write -> "world=" + write.world().getRegistryKey().getValue() + " mode=" + write.mode() + " status=" + write.result().status()
                    + " written=" + write.result().written();
            case M5TestHooks.ChunkCheckpoint checkpoint -> "worlds=" + checkpoint.relevant().entrySet().stream()
                    .map(entry -> entry.getKey().getRegistryKey().getValue() + "×" + entry.getValue().size()).sorted().toList();
            case null, default -> String.valueOf(detail);
        };
    }
}
