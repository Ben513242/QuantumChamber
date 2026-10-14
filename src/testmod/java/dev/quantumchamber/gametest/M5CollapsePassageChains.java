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
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5CollapseFixture.Inside;
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
 * 相同（{@code recordKeptAcrossTheRestart}；F6-2d-2 Step 0 起由窗口表的 {@code withRecordKept} 宣告、窗口表斷言）；之後的 step 與終局（{@link End} 的逐人位置）依窗口而不同：
 * <ul>
 * <li>{@code w7}（{@code CROSSED_COMMITTED}，spec §12 W7）：兩人，選門者（清單的第二位，spec review M-4）已跨過；crash 在 Crossed 確認的那個 tick 結束時，halt 之前不存檔
 *     （F6-2c fix round 1：已跨過的人在目的地，靠的是 production 在 Crossed 確認之後存下的 playerdata，spec v5.7 §10.3）；重啟之後通道在開服時結束（checked 終局），門在
 *     下一個 tick 一般開門、比較器重新評估；已跨過的人在 U 重新加入、另一位在原艙；沒有殘留的 runtime、ticket 或 RETURNING，同一個 server 內原艙再開新 session
 *     （checked ARMING）。</li>
 * <li>{@code readycommit}（{@code READY_COMMITTED}，F6-2-16）：PASSAGE_READY 已提交、門改為 passable 之前；重啟之後同 W7 的結束，參與者在艙內、門一般開門。</li>
 * <li>{@code terminaldoor}（{@code PASSAGE_TERMINATED}，F6-2-13）：(d) 終局已落盤、改門之前；重啟之後 Controller 的 load-sync 刷新排入找不到所屬 session 的量子膜的正規化，
 *     門在排入的下一個 tick 一般開門。</li>
 * <li>{@code crossedpending}（{@code CROSSED_UNCONFIRMED}，F6-2-17）：玩家已在 U、Crossed 的寫出沒有確認（crash 在那個 tick 結束時；寫入沒有確認時 production 不存
 *     playerdata）；重啟之後同 W7 的結束，他回到 playerdata 記的來源世界。</li>
 * <li>{@code trr}（{@code RECOVERY_SEALED}，F6-2-12）：SEALED(TRR) 已落盤；重啟之後仍是 SEALED、RecoveryRequired 與 Crossed 保留，已跨過的選門者在 U 重新加入（Crossed
 *     確認時存下的 playerdata），無法回滾的那位在艙內也不重試，照一般方式開門。</li>
 * <li>{@code trrpending}（{@code RECOVERY_REJECTED}，F6-2-12 的 pendingRecovery）：TRR 的寫入被拒、磁碟仍是 PASSAGE_READY；重啟之後同 W7 的結束，只剩前一個 JVM 的 log 證據。</li>
 * </ul>
 * spec §12 的四件事（同一個 UniverseId、catalog 最多多 1 筆、沒有重抽、沒有人被困住）：每個 tick 由 {@link M5CollapsePassage#invariants} 斷言，終局由
 * {@link M5CollapsePassage#expectEnd} 斷言。
 * <p>{@code w7}、{@code trr} 的 restart：所有參與者在同一個 tick JOIN（step {@code join}：已跨過的選門者在 U、另一位在來源世界）。F6-2c fix round 1 曾改成先讓沒跨過的人
 * JOIN、等來源世界的生成停下，才讓選門者 JOIN（{@code ce943f7}），以繞開原版 worldgen 的 ConcurrentModificationException；H6 的 production mixin
 * {@code PalettedBlockInfoListConcurrentCacheMixin} 修正之後改回同時 JOIN，回歸由 {@code M5CollapseTemplateRaceChain}（{@code h6race}）與 JUnit 的
 * {@code StructureTemplateCacheConcurrencyTest} 負責。
 */
final class M5CollapsePassageChains {
    private static final String SOURCE_TICKET = "quantumchamber_source";
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    private final M5CollapsePassage passage;
    /** restart：crash 的窗口（窗口表的一列）、讀回的 halt receipt、開服時的 record（終局已落盤時為 null）。 */
    private M5CollapseWindows.Window window;
    private JsonObject halt;
    private SessionRecoveryRecord atStart;
    /** restart：通道在開服時結束的 server tick。trr 觀察的 tick 數改由 {@link M5CollapsePassage#watch} 的區域計數器計（F6-2d-2 Step 0）。 */
    private int endTick = -1;
    /** terminaldoor 的 restart：load-sync 排入的正規化的 tick（INFO 的「tick N」）。 */
    private int normalizeTick = -1;
    /**
     * restart 開服時原艙門是不是已經是一般開門（由磁碟載入的值）。原版會在 tick 有餘裕時逐步存下變更過的 chunk（{@code ServerChunkLoadingManager.unloadChunks} 每個 tick 最多
     * 20 個、同一個 chunk 至少隔 10 秒），所以沒有在 halt 之前存檔的窗口，磁碟上的門是 crash 之前最後一次存下的樣子，不一定帶著量子膜。receipt 的 {@code doorPlainAtStart}
     * 記下它（F6-2d Step 0）；{@code doorUnchangedOnTheEndTick}（F6-2d-1 由 {@code notYetOpenOnTheEndTick} 改名）、{@code plainOpenOnTheNextTick} 在兩種情況下各自斷言有意義的內容。
     */
    private boolean doorPlainAtStart;

    M5CollapsePassageChains(M5CollapseRun run) {
        this.run = run;
        fixture = new M5CollapseFixture(run);
        passage = new M5CollapsePassage(run, fixture);
    }

    // ---- crash phase ----

    /** 收據的選門者是清單的第幾位（F6-2c fix round 1，spec review M-4）。 */
    private enum SelectedBy { FIRST, SECOND }

    /**
     * crash phase 共用的前半段：fresh 世界蓋原艙、{@code people} 位參與者在艙外 → DORMANT 的 NEW 收據（選門者是清單的 {@code selectedBy} 那一位）、原生存檔、裝上
     * {@code id} 的 crash 點 → 進入 → COLLAPSING → RESOLVE。
     */
    private List<Step> untilResolved(int people, CrashWindow id, SelectedBy selectedBy) {
        return List.of(
                new Step("build", 1, () -> { fixture.buildOrigin(people); return true; }),
                new Step("registered", 200, fixture::registered),
                new Step("receipt", 1, () -> {
                    fixture.writeCrashReceipt(selectedBy == SelectedBy.SECOND ? fixture.dormantNewReceiptSelectedByTheSecond() : fixture.dormantNewReceipt());
                    if (selectedBy == SelectedBy.SECOND) expectSelectorIsTheSecond();
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

    /** spec review M-4（F6-2c fix round 1）：這條 chain 的選門者（由收據推導）是清單的第二位，不是第一位。 */
    private void expectSelectorIsTheSecond() {
        run.expect("selectorIsTheSecondParticipant", fixture.participants().size() >= 2 && fixture.selectorId().equals(fixture.participants().get(1)),
                () -> "選門者=" + fixture.selectorId() + " 參與者=" + fixture.participants());
    }

    /**
     * W7：兩人，選門者是清單的第二位（spec review M-4）；PASSAGE_READY 之後讓 U 在原艙附近生成（{@link #universeGeneratedAroundTheChamber}），再原生存檔一次
     * （{@code nativeSaveDuringThePassage}：passable 的門、PASSAGE／15 的 Controller、兩人在來源世界的 playerdata 都在磁碟上，模擬跨越之前有過一次 autosave），選門者越過
     * 中線，Crossed 的寫入確認的那個 tick 結束時 halt（F6-2c fix round 1：之間只有 production 自己在 Crossed 確認之後的 playerdata 存檔，spec v5.7 §10.3；見窗口表）。
     */
    Phase w7Crash() {
        return crashPhase(selectorCrossesAfterASave(CrashWindow.CROSSED_COMMITTED, SelectedBy.SECOND, List.of(universeGeneratedAroundTheChamber())), fixture);
    }

    /**
     * W7 與 F6-2-12 共用的前半段（F6-2d Step 0，F6-2c quality Minor 5）：{@link #untilResolved}（兩人）→ PASSAGE_READY（選門者站到中線內側）→ {@code beforeTheSave}
     * （W7：讓 U 在原艙附近生成；F6-2-12 沒有）→ 原生存檔一次（{@link #nativeSaveDuringThePassage}）→ 選門者越過中線、傳送到 U。step 的順序與抽出之前相同。
     * 回傳的清單是新建的，呼叫端可以直接再加 step（F6-2d-1，F6-2d Step 0 quality nit：{@link #trrCrash} 不再複製一次）。
     */
    private ArrayList<Step> selectorCrossesAfterASave(CrashWindow id, SelectedBy selectedBy, List<Step> beforeTheSave) {
        var steps = new ArrayList<>(untilResolved(2, id, selectedBy));
        steps.add(passage.ready(null, M5CollapseFixture.SELECTOR));
        steps.addAll(beforeTheSave);
        steps.add(nativeSaveDuringThePassage());
        steps.addAll(passage.cross(M5CollapseFixture.SELECTOR));
        return steps;
    }

    /** PASSAGE_READY 期間原生存檔一次（step {@code saved}；W7、F6-2-12、F6-2-13）。 */
    private Step nativeSaveDuringThePassage() {
        return new Step("saved", 1, () -> {
            run.expect("nativeSaveDuringThePassage", run.server().saveAll(false, true, true), () -> "saveAll 回 false");
            return true;
        });
    }

    /**
     * W7（F6-2c fix round 1）：跨越之前，以 probe 的 ticket 讓 U 在原艙附近（半徑是 view distance＋2）的 chunk 都生成到 FULL（step {@code universeGenerated}），接著的
     * {@code saved} 把它們連同較低狀態的外圈一起存到磁碟。restart 時已跨過的人 JOIN 在 U，需要的 chunk 因此由磁碟載入。
     * <p>這一步原本是為了繞開原版 worldgen 的 ConcurrentModificationException（U 在 restart 才從頭生成原艙附近的結構時，與來源世界同時第一次使用同一個
     * {@code StructureTemplate.PalettedBlockInfoList} 快取；見 implementation note 的 F6-2c fix round 1 與 H6 Step 0 兩節）。H6 的 production mixin
     * {@code PalettedBlockInfoListConcurrentCacheMixin} 修正之後不再需要；W7 保留它，讓 W7 的 restart 涵蓋「U 的 chunk 由磁碟載入」，F6-2-12（trr）則拿掉它，涵蓋「restart
     * 時兩個世界同時生成原艙附近」（H6 的真實情境）。
     */
    private Step universeGeneratedAroundTheChamber() {
        return new Step("universeGenerated", 600, () -> {
            int radius = run.server().getPlayerManager().getViewDistance() + 2;
            if (!fixture.loadedAroundTheChamber(fixture.universeWorld().orElseThrow(), radius)) return false;
            run.proof.put("universeGeneratedRadius", radius);
            return true;
        });
    }

    /** F6-2-16：一人；PREPARE 完成之後 COLLAPSING→PASSAGE_READY 的提交讀回確認時 halt（門還是 sealed 量子膜）。 */
    Phase readyCommitCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.READY_COMMITTED, SelectedBy.FIRST));
        steps.add(new Step("halt", 600, () -> false));
        return crashPhase(steps, fixture);
    }

    /**
     * F6-2-13：一人；PASSAGE_READY 之後原生存檔（passable 的門、PASSAGE 的 Controller、艙內的參與者都在磁碟上），再拿掉 Controller 上方的紅石塊 → 通道以 (d) 來源艙沒有
     * 供電結束，checked 終局讀回確認時 halt（改門之前）。
     */
    Phase terminalDoorCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.PASSAGE_TERMINATED, SelectedBy.FIRST));
        steps.add(passage.ready(null, M5CollapseFixture.NOBODY));
        steps.add(nativeSaveDuringThePassage());
        steps.add(new Step("powerLoss", 1, () -> {
            run.source().setBlockState(FRAME.controllerPos().up(), net.minecraft.block.Blocks.AIR.getDefaultState(), 3);
            return true;
        }));
        steps.add(new Step("halt", 60, () -> false));
        return crashPhase(steps, fixture);
    }

    /**
     * F6-2-17：一人；PASSAGE_READY 之後裝上 journal 寫出的失敗（Crossed 的寫入），越過中線、傳送到 U，Crossed 的寫出沒有確認的那個 tick 結束時 halt（F6-2c fix round 1：
     * production 處理完寫入沒有確認的結果之後；見窗口表）。
     */
    Phase crossedPendingCrash() {
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.CROSSED_UNCONFIRMED, SelectedBy.FIRST));
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
     * F6-2-12：兩人；PASSAGE_READY 之後原生存檔一次（跨越之前，兩人的 playerdata 都在來源世界）→ 選門者照常跨過（Crossed＝[選門者]）→ 另一位的原生移動往 U 與回滾都被拒
     * （{@code FAILED_RECOVERY_REQUIRED}）→ PASSAGE_READY→SEALED(TRR) 的寫入確認時 halt。跨越之後 probe 不存檔：選門者的 playerdata 由 production 在他的 Crossed 確認時
     * 存下（spec v5.7 §10.3）。H6 起不再像 W7 那樣在跨越之前讓 U 生成（見 {@link #universeGeneratedAroundTheChamber}）：U 在原艙附近的 chunk 多半不在磁碟上，restart 時
     * 兩個世界同時生成原艙附近（restart 的斷言都不依賴 U 預先生成：開服時讀的是 journal、playerdata 與權威檔，之後的 step 只看玩家所在的世界與來源世界的原艙）。
     */
    Phase trrCrash() {
        var steps = selectorCrossesAfterASave(CrashWindow.RECOVERY_SEALED, SelectedBy.FIRST, List.of());
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
        var steps = new ArrayList<>(untilResolved(1, CrashWindow.RECOVERY_REJECTED, SelectedBy.FIRST));
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

    /**
     * 選門者以外的參與者（trr；依清單順序的第一位，F6-2d Step 0）或唯一的參與者（trrpending）往 U 的原生移動被拒一次、回滾回來源世界也被拒一次：
     * {@code FAILED_RECOVERY_REQUIRED}。
     */
    private void installRecoveryFault() {
        var universe = fixture.universeWorld().orElseThrow().getRegistryKey();
        var others = fixture.inListOrder(M5CollapseFixture.OTHERS);
        var failing = others.isEmpty() ? fixture.selectorId() : others.getFirst();
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
                // 已跨過的選門者 JOIN 在 U、另一位在來源世界的艙內，同一個 tick（H6 之後改回，見類別說明）；依清單順序（選門者是第二位）。
                steps.add(new Step("join", 1, () -> {
                    fixture.joinInTheSourceWorld(M5CollapseFixture.OTHERS, Inside.INSIDE);
                    fixture.joinInTheUniverse(M5CollapseFixture.SELECTOR);
                    return true;
                }));
                steps.add(new Step("end", 1, () -> {
                    passage.expectEnd(End.inTheSourceWorld(fixture).placed(M5CollapseFixture.SELECTOR, Place.UNIVERSE));
                    expectNoResidue();
                    return true;
                }));
                steps.add(new Step("closeDoor", 1, this::closeTheDoorForTheNextSession));
                steps.add(new Step("newSession", 200, this::newSessionInTheSameServer));
            }
            case READY_COMMITTED -> {
                steps.addAll(endedAtStartup());
                steps.addAll(nobodyAcrossJoinsAndEnds(Inside.INSIDE));
            }
            case PASSAGE_TERMINATED -> {
                steps.add(new Step("ownerlessQueued", 20, this::ownerlessQueued));
                steps.add(new Step("ownerlessNormalized", 20, this::ownerlessNormalizedOnTheNextTick));
                steps.addAll(nobodyAcrossJoinsAndEnds(Inside.INSIDE));
            }
            // F6-2-17 與 F6-2-12 的 pendingRecovery：playerdata 是觸發之前存的（艙外）。
            case CROSSED_UNCONFIRMED, RECOVERY_REJECTED -> {
                steps.addAll(endedAtStartup());
                steps.addAll(nobodyAcrossJoinsAndEnds(Inside.OUTSIDE));
            }
            case RECOVERY_SEALED -> {
                // F6-2d-2 B2a（B1 quality nit）：開服時的提醒的原因在建立 phase 時（onInitialize）建立、驗證（永久原因），誤用在那時丟出。
                var reminder = new End.Reminder(SealedReason.TRANSFER_RECOVERY_REQUIRED);
                // F6-2c fix round 1（spec v5.7 §10.3）：選門者跨越、Crossed 確認時已存下 playerdata，JOIN 在 U；無法回滾的另一位 JOIN 在來源世界的艙內（跨越之前存的）。
                // 兩人在同一個 tick JOIN（H6 之後改回，見類別說明）；依清單順序（選門者是第一位）。
                steps.add(new Step("join", 1, () -> {
                    fixture.joinInTheUniverse(M5CollapseFixture.SELECTOR);
                    fixture.joinInTheSourceWorld(M5CollapseFixture.OTHERS, Inside.INSIDE);
                    return true;
                }));
                steps.add(new Step("inside", 1, () -> { fixture.moveInside(M5CollapseFixture.OTHERS); return true; }));
                steps.add(M5CollapsePassage.watch("notRetried", M5CollapsePassage.WATCH_TICKS, this::sealedUnchanged, this::sealedNotRetried));
                steps.add(new Step("doorOpens", 1, () -> sealedDoorOpensNormally(reminder)));
            }
            default -> throw new IllegalArgumentException("通道的 chain 沒有這個窗口：" + id);
        }
        return Phase.stop(steps).started(() -> started(crashPhase)).everyTick(() -> passage.invariants(null));
    }

    /**
     * 沒有人跨過的 restart 的後半段（F6-2d Step 0，F6-2c quality Minor 5：F6-2-16、F6-2-13、F6-2-17、F6-2-12 的 pendingRecovery 原本各寫一次，只差在艙內或艙外）：全員 JOIN
     * 在來源世界（step {@code join}，{@code where}），再斷言全員在來源世界的 spec §12 終局（step {@code end}）。
     */
    private List<Step> nobodyAcrossJoinsAndEnds(Inside where) {
        return List.of(new Step("join", 1, () -> { fixture.joinAllInTheSourceWorld(where); return true; }),
                new Step("end", 1, () -> { passage.expectEnd(End.inTheSourceWorld(fixture)); return true; }));
    }

    private void started(String crashPhase) throws Exception {
        halt = fixture.readCrashReceipt(crashPhase, window.id());
        if (window.id() == CrashWindow.CROSSED_COMMITTED) expectSelectorIsTheSecond();
        M5CollapseWindows.expectAtRestart(run, fixture, window, halt);
        atStart = run.journal().flushedRecords().get(fixture.sid());
        run.proof.put("doorCenterOnDiskAtStart", M5CollapseWindows.doorCenterOnDisk(run, fixture));
        run.proof.put("doorCenterAtStart", run.source().getBlockState(local(3, 3, 0)).toString());
        doorPlainAtStart = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        run.proof.put("doorPlainAtStart", doorPlainAtStart);
        var atHalt = halt.getAsJsonObject("atHalt");
        // F6-2d-2 Step 0（F6-2d-1 quality Minor 4）：recordKeptAcrossTheRestart 由窗口表斷言（expectAtRestart；halt 當下還有 record 的列都 withRecordKept）。
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
        // 「終局的那個 tick 還沒有改門」：終局的那個 tick 原艙門與開服時相同。開服時不是一般開門（帶著量子膜，或關著）時，這個 tick 還不是一般開門（下一個 tick 才正規化）；
        // 開服時已是一般開門（逐步存檔，見 doorPlainAtStart）時，這個 tick 仍是一般開門（終局沒有把門關上或帶回量子膜）。F6-2d Step 0（F6-2c quality Minor 2）：原本後者恆真。
        // F6-2c fix round 1（spec review M-3）：兩種時序都以同一個名稱斷言，具名斷言的集合不隨逐步存檔的時序變動。F6-2d-1（F6-2d Step 0 quality Minor 1）：由
        // notYetOpenOnTheEndTick 改名（開服時已是一般開門時，斷言的是「仍是一般開門」，原名與內容矛盾）；兩種時序仍是同一個名稱。
        boolean plainOnTheEndTick = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        run.expect("doorUnchangedOnTheEndTick", plainOnTheEndTick == doorPlainAtStart,
                () -> "doorPlainAtStart=" + doorPlainAtStart + "：終局的那個 tick 原艙門" + (plainOnTheEndTick ? "已是" : "不是") + "一般開門，與開服時不同");
        run.expect("comparatorReevaluated", fixture.controllerSignal(ChamberState.IDLE, 3), () -> "終局之後的 Controller=" + controllerState());
        run.expect("poweredAfterTheEnd", powerState() == ChamberPowerState.POWERED, () -> "終局之後 registry 的 power state=" + powerState());
        return true;
    }

    /**
     * 終局的下一個 tick 原艙門是一般開門：開服時不是一般開門時，是這個 tick 正規化的；開服時已是一般開門（{@code doorPlainAtStart}，receipt 也記下）時，是終局之後仍維持一般開門
     * （F6-2d Step 0，F6-2c quality Minor 2：沒有正規化可驗，失敗訊息帶 {@code doorPlainAtStart}）。
     */
    private boolean plainOpenOnTheNextTick() {
        boolean plain = M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
        int now = run.server().getTicks();
        if (!plain && now < endTick + 3) return false;
        run.expect("plainOpenOnTheNextTick", plain && now == endTick + 1,
                () -> "doorPlainAtStart=" + doorPlainAtStart + "：原艙門在 tick " + now + "（終局在 tick " + endTick + "）：一般開門=" + plain);
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
        var source = M5GameTestSupport.tickets(run.source(), SOURCE_TICKET, sid);
        run.expect("noSourceTicketLeft", source.isEmpty(), () -> "來源 ticket=" + source);
        var destination = M5GameTestSupport.destinationTickets(fixture.universeWorld().orElseThrow(), sid);
        run.expect("noDestinationTicketLeft", destination.isEmpty(), () -> "目的世界的 ticket=" + destination);
        run.expect("notReturning", powerState() != ChamberPowerState.RETURNING, () -> "registry 的 power state=" + powerState());
    }

    /** W7：沒跨過的人（依清單順序的第一位，F6-2d Step 0）在艙內照一般方式關門（下一個 session 要門關著）。 */
    private boolean closeTheDoorForTheNextSession() {
        var other = fixture.player(fixture.first(M5CollapseFixture.OTHERS));
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
     * trr 的 step {@code notRetried}（{@link M5CollapsePassage#watch}）：沒有跨過的參與者（無法回滾的那位）在艙內（開服之後第一次觀察到，可重試的原因這時就會重試）之後觀察
     * {@link M5CollapsePassage#WATCH_TICKS} 個 tick：record 一直與開服時 exact 相同（這裡，每個 tick；第一個 tick（{@code tick} 由 1 起）另斷言沒有跨過的參與者在艙內）；觀察完之後
     * {@link #sealedNotRetried}。已跨過的選門者在 U（F6-2c fix round 1，spec v5.7 §10.3），不在艙內。
     */
    private void sealedUnchanged(int tick) {
        var sid = fixture.sid();
        if (tick == 1) {
            for (var id : fixture.members(M5CollapseFixture.OTHERS)) {
                var player = fixture.player(id);
                run.expect("participantsInsideWhileSealed", player.getServerWorld() == run.source() && M5CollapseFixture.inside(player), () -> "沒有跨過的參與者在 " + player.getPos());
            }
        }
        run.expect("sealedUnchanged", atStart.equals(run.journal().flushedRecords().get(sid)) && atStart.equals(run.journal().records().get(sid)),
                () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
    }

    /**
     * trr：觀察完之後，沒有「SEALED 重試」的 INFO、也沒有這個 session 的 WARN（journal 層級也拒絕 TRR 的重試，嘗試過就會有 WARN）；presence 是 COLLAPSE、比較器 ARMED（11）。
     */
    private void sealedNotRetried() {
        var sid = fixture.sid();
        var retried = run.capture().matching(Level.INFO, "SEALED 重試", sid.toString()).stream().map(LogCapture.Entry::message).toList();
        var warned = run.capture().matching(Level.WARN, sid.toString()).stream().map(LogCapture.Entry::message).toList();
        run.expect("trrNotRetried", retried.isEmpty() && warned.isEmpty(), () -> "重試的 INFO=" + retried + " WARN=" + warned);
        var presence = ChamberSessions.gateway().presence(run.server(), fixture.chamber());
        run.expect("presenceCollapse", presence == ChamberSessionGateway.Presence.COLLAPSE, () -> "presence=" + presence);
        run.expect("comparatorArmedWhileSealed", fixture.controllerSignal(ChamberState.ARMED, 11), () -> "Controller=" + controllerState());
    }

    /**
     * trr：SEALED 時任何玩家都可以照一般方式開門（spec §11.2；由艙內沒有跨過的那位開，依清單順序的第一位）；record 仍與開服時 exact 相同；spec §12 的終局斷言（已跨過的選門者在 U，F6-2c fix
     * round 1；無法回滾的那位在來源世界）。開服時就是永久原因 SEALED，這個 session 的 ERROR 恰為開服時的提醒那一則（{@code permanentSealedRemindedOnceAtStartup}，H9 (b)，
     * F6-2d-2 spec review 的 M-2；原本是 {@code noSessionError}）；F6-2d-2 B1 起另比對字句裡的 {@code SEALED(TRANSFER_RECOVERY_REQUIRED)} 與「需要管理員處理」。F6-2d-2 B2a：
     * 提醒的原因（{@code reminder}）由 {@link #restart} 在建立 phase 時建立。
     */
    private boolean sealedDoorOpensNormally(End.Reminder reminder) throws Exception {
        var other = fixture.player(fixture.first(M5CollapseFixture.OTHERS));
        var result = M5GameTestSupport.interact(run.source(), local(3, 3, 0), other);
        run.expect("doorOpensNormally", result.isAccepted() && M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE), () -> "互動結果 " + result);
        var sid = fixture.sid();
        run.expect("stillSealed", atStart.equals(run.journal().flushedRecords().get(sid)), () -> "state=" + M5GameTestSupport.state(run.journal(), sid));
        passage.expectEnd(End.inTheSourceWorld(fixture).placed(M5CollapseFixture.SELECTOR, Place.UNIVERSE).withStartupReminder(reminder));
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
