package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;
import static dev.quantumchamber.gametest.M5UniverseHold.asTestFailure;
import static dev.quantumchamber.gametest.M5UniverseHold.awaitConvergedThenComplete;
import static dev.quantumchamber.gametest.M5UniverseHold.begin;
import static dev.quantumchamber.gametest.M5UniverseSupport.allocationEntries;
import static dev.quantumchamber.gametest.M5UniverseSupport.collapsedToUniverse;
import static dev.quantumchamber.gametest.M5UniverseSupport.entered;
import static dev.quantumchamber.gametest.M5UniverseSupport.extraPlayer;
import static dev.quantumchamber.gametest.M5UniverseSupport.local;
import static dev.quantumchamber.gametest.M5UniverseSupport.membraneInfos;
import static dev.quantumchamber.gametest.M5UniverseSupport.openOrigin;
import static dev.quantumchamber.gametest.M5UniverseSupport.preloaded;
import static dev.quantumchamber.gametest.M5UniverseSupport.prepared;
import static dev.quantumchamber.gametest.M5UniverseSupport.reappliedTicks;
import static dev.quantumchamber.gametest.M5UniverseSupport.reapplyCauses;
import static dev.quantumchamber.gametest.M5UniverseSupport.reapplyQueuedTicks;
import static dev.quantumchamber.gametest.M5UniverseSupport.receiptStillSealed;
import static dev.quantumchamber.gametest.M5UniverseSupport.releaseThenComplete;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealWriteWarnings;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealedWith;
import static dev.quantumchamber.gametest.M5UniverseSupport.ticks;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.UniverseDiscoveryState;
import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseBackoff;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 run 的 GameTest：PREPARE 與 SEALED 期間的原艙門（量子膜、關門、被拒的寫入與重試、隔離、開服與 chunk 重新載入之後的重新套用、
 * 門的使用失敗），以及找不到所屬 session 的量子膜（F4b-3 起）。由 {@link M5UniverseGameTests} 依主題純搬移拆出（F6-3c-2，盤點 #06）；test 的方法名稱、batchId、tickLimit 與 {@code @GameTest} 的屬性都不變，寫法的規則見
 * {@link M5UniverseGameTests} 的類別說明與 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 */
public final class M5UniverseDoorGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    // ---- F4b-3：PREPARE 期間的原艙門 ----

    /**
     * F4b-3（spec §7.3「套用量子膜的條件」「門口有人時的規則」「PREPARE 期間的開關門」「非參與者不會被困」；plan Step 1 第 11 組；補充第 1、2、6 點）：DORMANT NEW
     * 收據、參與者 P 在艙內。
     * <ul>
     * <li>RESOLVE 之後、第一次閘門評估之前，非參與者 N 進艙：進入 PREPARE 時門維持一般開門（不套用量子膜），準備照常在背景進行（目的世界的 ticket）。N 離開之後下一個
     *     tick 套用 sealed 量子膜；N 改成旁觀者再進艙，不影響（不含旁觀者）。</li>
     * <li>準備完成之後（仍每個 tick 判斷；F5-1 起以 {@code holdReady} 暫緩 PASSAGE_READY 的提交，留在 PREPARE）：門口出現 entity（盔甲座）→ 一般開門；移走之後 → sealed。</li>
     * <li>sealed 期間以傳送（等同指令）把 N 送進艙內、在同一個 tick 開門：門當場改為一般開門，塌縮不結束（record 仍是 COLLAPSING、ticket 照樣持有、沒有終局的 INFO）；
     *     N 離開之後下一個 tick 重新套用 sealed。</li>
     * <li>艙外的 N 以 bulkhead 或 Controller 開關門：都被拒（ActionResult 不是 accepted），門不變。</li>
     * <li>log：「套用 sealed 量子膜」與「維持一般開門」的 INFO 這一段 PREPARE 各一則；這個 session 沒有 WARN／ERROR（F4b-3 時另有「通道尚未實作」一則）。</li>
     * </ul>
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_membrane", tickLimit = 1200)
    public void prepare_seals_the_membrane_only_with_a_clear_doorway_and_no_non_participant_inside(TestContext context) {
        begin(context, "prepare_seals_the_membrane_only_with_a_clear_doorway_and_no_non_participant_inside");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var n = extraPlayer(context, hooks, frame).player();
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            // F5-1：準備完成之後仍要驗 PREPARE 的門（F4b-3 時停在 READY stub），所以暫緩 PASSAGE_READY 的提交。
            var hold = M5GameTestSupport.holdReady(context, sid, hooks);
            moveInside(n, frame);
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, prepareAt -> {
                witness.put("plainOpenWithANonParticipantInside", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("preparingInTheBackground", !M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                moveOutside(n, frame);
                at(context, prepareAt + 1, hooks, () -> {
                    witness.put("sealedAfterTheNonParticipantLeft", M5GameTestSupport.doorSealed(world, frame));
                    n.changeGameMode(GameMode.SPECTATOR);
                    moveInside(n, frame);
                });
                at(context, prepareAt + 3, hooks, () -> {
                    witness.put("aSpectatorInsideKeepsTheMembrane", M5GameTestSupport.doorSealed(world, frame));
                    n.changeGameMode(GameMode.SURVIVAL);
                    moveOutside(n, frame);
                });
                whenTicks(context, prepareAt + 4, 500, () -> !prepared(capture, sid).isEmpty(), ready -> {
                    witness.put("sealedWhenPrepared", M5GameTestSupport.doorSealed(world, frame));
                    var stand = M5GameTestSupport.doorwayEntity(world, frame, hooks);
                    at(context, ready + 2, hooks, () -> {
                        witness.put("plainOpenWithAnEntityInTheDoorwayAfterThePreparation", doorAll(world, frame, true, MembraneState.NONE));
                        stand.discard();
                    });
                    at(context, ready + 4, hooks, () -> witness.put("sealedAgainOnceTheDoorwayIsClear", M5GameTestSupport.doorSealed(world, frame)));
                    at(context, ready + 5, hooks, () -> {
                        moveInside(n, frame);
                        witness.put("insiderOpensAtOnce", interact(world, doorCell, n).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                        witness.put("collapseContinues", M5GameTestSupport.state(journal, sid).equals("COLLAPSING")
                                && !M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && endedByADoor(capture, sid) == 0);
                        moveOutside(n, frame);
                    });
                    at(context, ready + 7, hooks, () -> {
                        witness.put("resealedAfterTheInsiderLeft", M5GameTestSupport.doorSealed(world, frame));
                        witness.put("outsiderRefusedAtTheBulkhead", !interact(world, doorCell, n).isAccepted() && M5GameTestSupport.doorSealed(world, frame));
                        witness.put("outsiderRefusedAtTheController", !interact(world, frame.controllerPos(), n).isAccepted() && M5GameTestSupport.doorSealed(world, frame));
                        witness.put("stillCollapsing", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && endedByADoor(capture, sid) == 0);
                        witness.put("oneSealedAndOneOpenInfo", membraneInfos(capture, sid, "原艙門套用 sealed 量子膜") == 1
                                && membraneInfos(capture, sid, "原艙門維持一般開門") == 1);
                        witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                        witness.put("readyHeldInPrepare", hold.hits() > 0 && M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                        releaseThenComplete(context, hooks, witness, "量子膜的套用條件與非參與者的門：" + witness, 20,
                                () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    });
                }, () -> "沒有準備完成：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-3（spec §7.3「PREPARE 期間的開關門」、設計 §20.4；plan Step 1 第 12 組、Review Focus 3；補充第 2 點）：進入 PREPARE、預載還沒完成時，參與者 P 以 bulkhead
     * 關門 → 當場 checked 終局（{@code PREPARE_DOOR_CLOSED}；current 與 flushed 都沒有這個 session），之後才改門：journal 寫出終局的那一次 flush 開頭，門仍是
     * sealed 量子膜（flush 探針記下）；終局之後門關上（門口沒有 entity）。不重抽：目的宇宙保留，catalog 有它的 EAGER_ENABLED record、discovery 有它的
     * gameplay-eligible record、allocation entry 一筆；來源與目的世界的 ticket 當場釋放；INFO「PREPARE 期間參與者關門，塌縮已結束」一則，沒有 WARN／ERROR。
     * 幾個 tick 之後門仍關著、原艙的 presence 是 NONE。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_close", tickLimit = 600)
    public void prepare_participant_closing_the_door_ends_the_collapse_before_the_preload_with_the_universe_kept(TestContext context) {
        begin(context, "prepare_participant_closing_the_door_ends_the_collapse_before_the_preload_with_the_universe_kept");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var atTerminalFlush = new AtomicReference<String>();
            M5GameTestSupport.installFlushProbe(context, state -> {
                if (atTerminalFlush.get() == null && !state.records().containsKey(sid))
                    atTerminalFlush.set(M5GameTestSupport.doorSealed(world, frame) ? "SEALED" : "NOT_SEALED");
            }, hooks);
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, prepareAt -> {
                witness.put("sealedAtThePrepareStart", M5GameTestSupport.doorSealed(world, frame));
                witness.put("beforeThePreloadCompleted", preloaded(capture, sid).isEmpty());
                witness.put("participantCloses", interact(world, local(frame, 3, 3, 0), p).isAccepted());
                witness.put("terminalBeforeTheDoorChanged", "SEALED".equals(atTerminalFlush.get()));
                witness.put("terminatedAtOnce", !journal.records().containsKey(sid) && !journal.flushedRecords().containsKey(sid));
                witness.put("doorClosedAfterTheTerminal", doorAll(world, frame, false, MembraneState.NONE));
                witness.put("ticketsReleasedAtOnce", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                var record = UniverseRegistryState.get(server).flushedRecords().get(id);
                witness.put("universeKeptInTheCatalog", record != null && record.desiredAvailability() == DesiredAvailability.EAGER_ENABLED
                        && UniverseLifecycleService.resolveActive(server, id).isPresent());
                witness.put("universeDiscovered", discovered(server, id));
                witness.put("oneAllocationEntry", allocationEntries(server, id) == 1);
                witness.put("oneTerminalInfo", endedByADoor(capture, sid) == 1);
                witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                at(context, prepareAt + 3, hooks, () -> {
                    witness.put("staysClosed", doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    releaseThenComplete(context, hooks, witness, "PREPARE 一開始參與者關門：" + witness + " atTerminalFlush=" + atTerminalFlush.get(), 20, () -> true);
                });
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-3（spec §7.3「門口有人時的規則」「PREPARE 期間的開關門」；plan Step 1 第 12 組的「門口有 entity 時改為一般開門」；補充第 6 點）：RESOLVE 之後、PREPARE 之前，門口
     * 放一座盔甲座：進入 PREPARE 時門維持一般開門（門口有 entity，不套用量子膜）。參與者 P 以 Controller 開關門 → checked 終局，門不關上，維持 {@code open=true}＋
     * {@code membrane=none}（不把門關在 entity 上）；兩種 ticket 已釋放。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_close_doorway", tickLimit = 600)
    public void prepare_participant_closing_by_the_controller_with_an_entity_in_the_doorway_leaves_it_open(TestContext context) {
        begin(context, "prepare_participant_closing_by_the_controller_with_an_entity_in_the_doorway_leaves_it_open");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            M5GameTestSupport.doorwayEntity(world, frame, hooks);
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, prepareAt -> {
                witness.put("plainOpenWithAnEntityInTheDoorway", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("participantClosesByTheController", interact(world, frame.controllerPos(), p).isAccepted());
                witness.put("terminatedAtOnce", !journal.records().containsKey(sid) && !journal.flushedRecords().containsKey(sid) && endedByADoor(capture, sid) == 1);
                witness.put("leftPlainOpenNotClosed", doorAll(world, frame, true, MembraneState.NONE));
                witness.put("ticketsReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                at(context, prepareAt + 3, hooks, () -> {
                    witness.put("staysPlainOpen", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    releaseThenComplete(context, hooks, witness, "門口有 entity 時參與者以 Controller 關門：" + witness, 20, () -> true);
                });
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F4b-3（spec §6 :260「restore 類失敗時 PREPARE 中的門依 §7.3 改為一般開門」、§7.3；補充第 4 點，F4b-2 spec re-review M-1）：PREPARE 中進入 SEALED 的寫入在前置
     * 檢查就被拒（testmod 的 {@code SessionJournalPutFaultMixin}，對這個 session 的 SEALED 拒絕一次；觸發的是第一次投影寫入的受控失敗 → PROJECTION_FAILED）。被拒時
     * collapsing() 只依 per-session 退避重試這筆寫入、不推導階段：門當場由 sealed 量子膜改回一般開門（INFO「PREPARE 暫停」一則），record 仍是 COLLAPSING，目的世界的
     * ticket 繼續持有（F4b-2 Q-M4）；退避期間門一直是一般開門。退避（200 tick）之後重試成功：SEALED(PROJECTION_FAILED)，門關上（門口沒有 entity），兩種 ticket
     * 釋放。put 只被拒一次。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_rejected", tickLimit = 1200)
    public void prepare_rejected_seal_write_hands_the_door_back_open_until_the_retry_seals_it(TestContext context) {
        begin(context, "prepare_rejected_seal_write_hands_the_door_back_open_until_the_retry_seals_it");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var write = M5GameTestSupport.installProjectionWriteFault(context, universe.getRegistryKey(), ProjectionWriter.Mode.FIRST_WRITE, 1, hooks);
            var put = M5GameTestSupport.installJournalPutFault(context, sid, SessionState.SEALED, 1, hooks);
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1, prepareAt -> {
                witness.put("sealedWhilePreparing", M5GameTestSupport.doorSealed(world, frame));
                whenTicks(context, prepareAt, 400, () -> put.hits() == 1, rejectedAt -> {
                    witness.put("writeFaultedOnce", write.hits() == 1);
                    witness.put("plainOpenWhileTheSealIsRetried", doorAll(world, frame, true, MembraneState.NONE));
                    var record = journal.flushedRecords().get(sid);
                    witness.put("stillCollapsing", record != null && record.state() == SessionState.COLLAPSING && record.equals(journal.records().get(sid)));
                    witness.put("destinationTicketKept", !M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("oneRejectedWarn", sealWriteWarnings(capture, sid) == 1);
                    witness.put("onePauseInfo", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "進入 SEALED 的寫入被拒，PREPARE 暫停", sid.toString()).size() == 1);
                    at(context, rejectedAt + 100, hooks, () -> witness.put("stillPlainOpenDuringTheBackoff", doorAll(world, frame, true, MembraneState.NONE)
                            && M5GameTestSupport.state(journal, sid).equals("COLLAPSING")));
                    whenTicks(context, rejectedAt + 101, 260, () -> sealedWith(journal, sid, SealedReason.PROJECTION_FAILED), sealed -> {
                        witness.put("closedOnceSealed", doorAll(world, frame, false, MembraneState.NONE));
                        witness.put("ticketsReleasedOnceSealed", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.sourceTicketHeld(sid));
                        witness.put("putRejectedOnlyOnce", put.hits() == 1);
                        releaseThenComplete(context, hooks, witness, "SEALED 的寫入被拒時的原艙門：" + witness, 20, () -> true);
                    }, () -> "退避之後沒有進 SEALED：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
                }, () -> "SEALED 的寫入沒有被拒：record=" + M5GameTestSupport.state(journal, sid) + " write=" + write.hits() + " put=" + put.hits(), hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F6-0（F4b-3 fix round 1 的 spec S-M1；spec §6 :260「restore 類失敗時 PREPARE 中的門依 §7.3 閘門失效的處理改為一般開門」、§7.3）：隔離中的 PREPARE 交還原艙門
     * （{@code OriginDoors.watchDoor} 的隔離分支）。PREPARE 的 sealed 量子膜套用之後，test 以 {@code SessionRecoveryState.quarantine} 隔離這個 session，並讓它的收斂一直失敗
     * （testmod {@code QuarantineConvergeFaultMixin}；recovery 的收斂排在塌縮流程之前，沒有這個注入時同一個 tick 就收斂、塌縮流程看不到隔離）。
     * <ul>
     * <li>隔離的那個 tick：原艙門由 sealed 量子膜改為一般開門，INFO「session 隔離中…PREPARE 暫停」一則；record 仍是 COLLAPSING、目的世界的 ticket 繼續持有（F4b-2 Q-M4）。</li>
     * <li>隔離期間 PREPARE 不擁有原艙門：參與者開關門照一般門的邏輯（攔截回 PASS）；門一直是一般開門，交還只做一次；recovery 的「尚無法收斂」WARN 一則。</li>
     * <li>收斂恢復之後（注入停止，下一個 tick 收斂）：PREPARE 依原進度續做，原艙門重新套用 sealed 量子膜；準備完成之後以 {@code holdReady} 留在 PREPARE。塌縮流程沒有 WARN／ERROR。</li>
     * </ul>
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_quarantine", tickLimit = 1000)
    public void quarantined_prepare_hands_the_origin_door_back_open_and_reseals_it_after_converging(TestContext context) {
        begin(context, "quarantined_prepare_hands_the_origin_door_back_open_and_reseals_it_after_converging");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            // 收斂恢復之後要看 PREPARE 重新套用量子膜：準備完成之後暫緩 PASSAGE_READY 的提交，留在 PREPARE。
            var hold = M5GameTestSupport.holdReady(context, sid, hooks);
            whenTicks(context, resolved + 1, 20, () -> entered(capture, sid) == 1 && M5GameTestSupport.doorSealed(world, frame), prepareAt -> {
                var fault = M5GameTestSupport.installConvergeFault(context, sid, hooks);
                witness.put("quarantinedByTheTest", journal.quarantine(sid));
                at(context, prepareAt + 1, hooks, () -> {
                    witness.put("stillQuarantined", journal.quarantinedSessions().contains(sid) && fault.hits() >= 1);
                    witness.put("plainOpenWhileQuarantined", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("onePauseInfo", quarantinePauses(capture, sid) == 1);
                    witness.put("stillCollapsing", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                    witness.put("destinationTicketKept", !M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("participantUsePassesWhileQuarantined", M5GameTestSupport.useDoor(world, frame, p) == ChamberCollapsePort.DoorUse.PASS);
                });
                at(context, prepareAt + 20, hooks, () -> {
                    witness.put("plainOpenThroughTheQuarantine", doorAll(world, frame, true, MembraneState.NONE) && journal.quarantinedSessions().contains(sid));
                    witness.put("handedBackOnlyOnce", quarantinePauses(capture, sid) == 1);
                    witness.put("convergenceKeptFailing", fault.hits() >= 19);
                    witness.put("oneConvergeWarn", capture.matching(Level.WARN, "隔離 session 尚無法收斂", sid.toString()).size() == 1);
                    fault.release();
                });
                whenTicks(context, prepareAt + 21, 600, () -> !journal.quarantinedSessions().contains(sid) && M5GameTestSupport.doorSealed(world, frame)
                        && hold.hits() > 0, resealed -> {
                    witness.put("convergedOnceTheFaultStopped", capture.matching(Level.INFO, "未 checked authority 已收斂", sid.toString()).size() == 1);
                    witness.put("stillCollapsingAfterTheQuarantine", M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                    witness.put("resealedByThePrepare", M5GameTestSupport.doorSealed(world, frame));
                    witness.put("handedBackStillOnce", quarantinePauses(capture, sid) == 1);
                    witness.put("noCollapseWarn", M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    LOGGER.info("M5_DOOR_QUARANTINE prepare={} resealed={} convergeFaults={} readyHolds={}", prepareAt, resealed, fault.hits(), hold.hits());
                    releaseThenComplete(context, hooks, witness, "隔離中的 PREPARE 的原艙門：" + witness, 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                }, () -> "收斂恢復之後沒有重新套用量子膜：record=" + M5GameTestSupport.state(journal, sid) + " quarantined=" + journal.quarantinedSessions().contains(sid)
                        + " witness=" + witness, hooks);
            }, () -> "RESOLVE 之後沒有進入 PREPARE、套用量子膜：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /** 這個 session「隔離中…PREPARE 暫停」的 INFO（帶 LOG_MARKER；{@code OriginDoors.watchDoor} 的隔離分支，F6-0）。 */
    private static int quarantinePauses(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "session 隔離中", "PREPARE 暫停", sid.toString()).size();
    }

    /**
     * F6-0（F4a fix round 2 的 spec S2-mB；F4b-3 交給 F6 的 PJF）：經 {@code collapsing()} 驅動的「進入 SEALED 的寫入在前置檢查就被拒」的重試（spec §6「journal 寫入失敗時的
     * session 行為」、§11.2 的 per-session 退避、§8.4）。DORMANT NEW 收據的 RESOLVE 物化失敗（testmod {@code MaterializeFaultMixin}）→ SEALED(MATERIALIZE_FAILED) 的寫入被拒兩次
     * （testmod {@code SessionJournalPutFaultMixin}）。塌縮流程只依 per-session 退避重試這一筆寫入：
     * <ul>
     * <li>被拒的 server tick 恰為 [R, R+200]（不是每個 tick；第二次被拒之後退避加倍），第三次在 R+200+400 寫入成功（{@code installSealProbe} 記下 {@code sealCommitted} 的 tick）；</li>
     * <li>重試期間 record 一直是 COLLAPSING（current＝flushed）、來源 ticket 繼續持有；被拒的 WARN 恰一則，寫明 200 ticks 之後、tick R+200 重試；</li>
     * <li>不重跑 RESOLVE：backend 只物化一次，沒有「目的地已確定」，進入 SEALED 的 WARN 帶的是 RESOLVE 判定的原因與 cause（「物化失敗，catalog 已改為 DISABLED」），不是重跑時
     *     resolver 回的「本 server instance 已用掉…」；</li>
     * <li>寫入成功之後：SEALED(MATERIALIZE_FAILED)、來源 ticket 釋放。</li>
     * </ul>
     * catalog 增量 1（DISABLED）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_seal_rejected_retry", tickLimit = 1300)
    public void rejected_seal_write_is_retried_on_the_per_session_backoff_without_rerunning_resolve(TestContext context) {
        begin(context, "rejected_seal_write_is_retried_on_the_per_session_backoff_without_rerunning_resolve");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, Map.of(p.getUuid(), true), true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            var materialize = M5GameTestSupport.installMaterializeFault(context, id, hooks);
            var put = M5GameTestSupport.installJournalPutFault(context, sid, SessionState.SEALED, 2, hooks);
            var probe = M5GameTestSupport.installSealProbe(context, sid, world, frame.controllerPos(), hooks);
            var witness = new LinkedHashMap<String, Boolean>();
            at(context, registered + 1, hooks, () -> moveInside(p, frame));
            // 提交在 registered+1、RESOLVE（物化失敗 → 進入 SEALED 的寫入被拒）在 registered+2 的 END_SERVER_TICK。
            whenTicks(context, registered + 3, 20, () -> put.hits() == 1, rejectedAt -> {
                at(context, rejectedAt + 100, hooks, () -> {
                    witness.put("collapsingWithinTheFirstBackoff", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && put.hits() == 1
                            && journal.flushedRecords().get(sid).equals(journal.records().get(sid)));
                    witness.put("sourceTicketKeptWhileRejected", M4CandidateTestAccess.sourceTicketHeld(sid));
                });
                whenTicks(context, rejectedAt + 101, 300, () -> put.hits() == 2, secondAt -> {
                    at(context, secondAt + 100, hooks, () -> witness.put("collapsingWithinTheDoubledBackoff",
                            M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && put.hits() == 2));
                    whenTicks(context, secondAt + 101, 500, () -> sealedWith(journal, sid, SealedReason.MATERIALIZE_FAILED), sealed -> {
                        var rejected = put.ticks();
                        var commits = probe.after();
                        witness.put("rejectedOnlyOnThePerSessionBackoff", rejected.size() == 2 && rejected.get(1) - rejected.get(0) == CollapseBackoff.INITIAL_TICKS);
                        witness.put("committedAfterTheDoubledBackoff", put.hits() == 2 && commits.size() == 1 && rejected.size() == 2
                                && commits.getFirst().tick() - rejected.get(1) == 2 * CollapseBackoff.INITIAL_TICKS);
                        // 只數被拒（REJECTED）的那一則：寫入成功的那一次若遇到落盤失敗（ROLL_FORWARD_PENDING）會另記一則「寫入 SEALED」WARN，不屬於這個 witness。
                        var rejectedWarns = capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "寫入 SEALED沒有完成（REJECTED）", sid.toString());
                        witness.put("oneRejectedWarnWithTheFirstBackoff", rejectedWarns.size() == 1 && !rejected.isEmpty()
                                && rejectedWarns.getFirst().message().contains(CollapseBackoff.INITIAL_TICKS + " ticks 後，tick " + (rejected.getFirst() + CollapseBackoff.INITIAL_TICKS)));
                        witness.put("materializedOnce", materialize.calls() == 1);
                        var sealedWarns = capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "SEALED(MATERIALIZE_FAILED)", sid.toString());
                        // 不重跑 RESOLVE 的 witness：重跑時 resolver 看到本 instance 已用掉物化額度，回同一個原因、但 cause 是「已用掉…」，寫入成功時的 WARN 就帶它。
                        witness.put("sealedWithTheOriginalResolveCause", sealedWarns.size() == 1
                                && sealedWarns.getFirst().message().contains("物化失敗，catalog 已改為 DISABLED") && !sealedWarns.getFirst().message().contains("已用掉"));
                        witness.put("neverResolved", capture.matching(Level.INFO, "目的地已確定", sid.toString()).isEmpty());
                        witness.put("sourceTicketReleasedOnceSealed", !M4CandidateTestAccess.sourceTicketHeld(sid));
                        witness.put("noError", capture.matching(Level.ERROR, sid.toString()).isEmpty());
                        LOGGER.info("M5_SEAL_REJECTED_RETRY rejected={} committed={} materialize={}", rejected,
                                commits.stream().map(M5TestHooks.SealProbe.Observation::tick).toList(), materialize.calls());
                        releaseThenComplete(context, hooks, witness, "進入 SEALED 的寫入被拒之後的重試：" + witness + " rejected=" + rejected + " committed="
                                + commits.stream().map(M5TestHooks.SealProbe.Observation::tick).toList(), 20, () -> true);
                    }, () -> "第二次被拒之後沒有寫入 SEALED：record=" + M5GameTestSupport.state(journal, sid) + " rejected=" + put.ticks() + " witness=" + witness, hooks);
                }, () -> "退避之後沒有再重試：record=" + M5GameTestSupport.state(journal, sid) + " rejected=" + put.ticks(), hooks);
            }, () -> "進入 SEALED 的寫入沒有被拒：record=" + M5GameTestSupport.state(journal, sid) + " materialize=" + materialize.calls(), hooks);
        });
    }

    /**
     * F4b-3（spec §7.3「門狀態的重新套用」；plan Step 1 第 10 組；補充第 5 點「開服時就是 WAITING：門要是一般開門，不得留下有碰撞的膜」）：RESOLVE 之後、第一次閘門
     * 評估之前斷電 → WAITING。以授權寫入在門上留下 sealed 量子膜（模擬 sealed 期間 crash 留下的膜），再模擬重啟之後的第一次觀察
     * （{@code M5GameTestSupport.simulateDoorStartup}；真正跨 JVM 的重啟屬 F6）。塌縮流程觀察到的那個 tick 只排入佇列（INFO 記下套用的 tick N，原因是「重啟之後第一次載入」，
     * F4b-3 fix round 1），下一個 tick（tick N）
     * 才依 collapse view 套用：門改為一般開門（INFO 記下 tick N）。之後不再重新套用（一次性）。恢復供電、P 由艙外再進入 → PREPARE → 依套用量子膜的條件重新套用 sealed。
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_startup", tickLimit = 900)
    public void startup_reapplies_a_stale_membrane_in_waiting_once_on_the_next_tick(TestContext context) {
        begin(context, "startup_reapplies_a_stale_membrane_in_waiting_once_on_the_next_tick");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            at(context, resolved + 2, hooks, () -> {
                witness.put("waitingWithoutPower", entered(capture, sid) == 0 && M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                M5GameTestSupport.writeDoor(world, frame, true, MembraneState.sealedFor(frame.outwardFacing()));
                witness.put("staleMembraneWritten", M5GameTestSupport.doorSealed(world, frame));
                M5GameTestSupport.simulateDoorStartup(server, sid);
            });
            whenTicks(context, resolved + 3, 10, () -> !reapplyQueuedTicks(capture, sid).isEmpty(), queuedAt -> {
                witness.put("notWrittenWhenObserved", M5GameTestSupport.doorSealed(world, frame));
                whenTicks(context, queuedAt, 5, () -> !reappliedTicks(capture, sid).isEmpty(), appliedAt -> {
                    witness.put("plainOpenAfterTheReapply", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("appliedOnTheQueuedTick", reappliedTicks(capture, sid).equals(reapplyQueuedTicks(capture, sid)));
                    at(context, appliedAt + 5, hooks, () -> {
                        witness.put("onlyOnce", reapplyQueuedTicks(capture, sid).size() == 1 && reappliedTicks(capture, sid).size() == 1);
                        witness.put("queuedAsTheFirstLoadAfterARestart", reapplyCauses(capture, sid).equals(List.of("重啟之後第一次載入")));
                        context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                        moveOutside(p, frame);
                    });
                    at(context, appliedAt + 6, hooks, () -> moveInside(p, frame));
                    whenTicks(context, appliedAt + 7, 40, () -> entered(capture, sid) == 1 && M5GameTestSupport.doorSealed(world, frame), resealed -> {
                        witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                        LOGGER.info("M5_DOOR_STARTUP queued={} applied={}", reapplyQueuedTicks(capture, sid), reappliedTicks(capture, sid));
                        releaseThenComplete(context, hooks, witness, "重啟之後的原艙門：" + witness + " queued=" + reapplyQueuedTicks(capture, sid) + " applied="
                                + reappliedTicks(capture, sid), 20, () -> M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    }, () -> "恢復供電、由艙外進入之後沒有重新套用 sealed：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
                }, () -> "排入之後沒有套用：applied=" + reappliedTicks(capture, sid) + " witness=" + witness, hooks);
            }, () -> "模擬重啟之後沒有排入重新套用：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4b-3（spec §7.3「門狀態的重新套用」、§11.2、§7.3 :404；plan Step 1 第 10 組的 SEALED 版本；補充第 5、6 點）：遠處的真原艙（{@code M5GameTestSupport.RemoteOrigin}，
     * 只有它自己強制載入；不在 test 結構裡）上的 SEALED 收據（參與者是離線的隨機玩家，不會觸發重試），門先是一般開門。
     * <ul>
     * <li>執行期間一直載入著：不重新套用（門仍開著）。</li>
     * <li>解除強制載入 → chunk 不再是 FULL（SEALED 沒有來源 ticket；多等 3 個 tick，讓塌縮流程觀察得到）→ 恢復 → 載入：觀察到的那個 tick 只排入佇列，最早下一個
     *     tick 依 collapse view 關門一次（關門要等門口的 entity storage 載入，所以套用的 tick 不早於排入時記下的 tick）。</li>
     * <li>非參與者 Q（遠在 test 結構那裡；開關門不需要靠近）照一般方式開門（SEALED 不攔截）：40 tick 之後仍開著，沒有第二次重新套用。</li>
     * <li>Q 關門、門口放一座盔甲座，再卸載、載入一次：重新套用等門口的 entity storage 載入之後才判斷，門口有 entity → 改為一般開門（不把門關在 entity 上）。</li>
     * <li>F4b-3 fix round 1（quality Q-M2）：移走盔甲座、Q 關門，第三次卸載、載入：排入的關門還在等門口的 entity storage 時，Q 照一般方式開門 → 排入的關門取消
     *     （INFO 一則），entity storage 載入之後門仍開著，沒有第三次套用。三次排入的 INFO 原因都是「重新載入」。</li>
     * </ul>
     * catalog 增量 0（SEALED 收據不配置宇宙）。
     * <p>有界等待的總和（README「前提」；F5-0，F4b-3 quality Nit）：註冊最晚 tick 43；第一次載入之後套用最晚 registered＋605＝648；第二次最晚 closedAt＋642＝1290；第三次排入最晚
     * openedAt＋602＝1892，entity storage 最晚再 300＝2192；最後 2 tick 之後收斂最多 20 tick，共 2214，所以 tickLimit 是 2300（原本 2000 不夠）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_reload", tickLimit = 2300)
    public void sealed_door_closes_once_after_its_chunk_reloads_and_stays_open_once_a_player_opens_it(TestContext context) {
        begin(context, "sealed_door_closes_once_after_its_chunk_reloads_and_stays_open_once_a_player_opens_it");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        M5GameTestSupport.RemoteOrigin remote;
        ServerPlayerEntity q;
        LogCapture capture;
        var owned = new ArrayList<UUID>();
        try {
            remote = M5GameTestSupport.RemoteOrigin.open(context, new BlockPos(30003, 100, 30003), hooks);
            var connection = new ConnectedGameTestPlayer(world);
            hooks.add(connection::close);
            q = connection.player();
            q.setNoGravity(true);
            capture = M5GameTestSupport.captureLogs(hooks);
            hooks.add(() -> cleanupFixtures(server, owned));
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var frame = remote.frame;
        var doorCell = local(frame, 3, 3, 0);
        var log = capture;
        remote.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            M5GameTestSupport.writeDoor(world, frame, true, MembraneState.NONE);
            var sealedRecord = M5GameTestSupport.collapseFixture(server, remote.authority(chamber), Map.of(UUID.randomUUID(), true), List.of(), true,
                    SessionState.SEALED, false);
            var sid = sealedRecord.sessionUuid();
            owned.add(sid);
            at(context, registered + 3, hooks, () -> {
                witness.put("notReappliedWhileLoaded", doorAll(world, frame, true, MembraneState.NONE) && reapplyQueuedTicks(log, sid).isEmpty());
                remote.unload();
            });
            // 卸載之後多等幾個 tick 再恢復強制載入：塌縮流程的觀察（END_SERVER_TICK）要看得到「沒有載入」。同一個 tick 內恢復時，chunk 還在記憶體裡、門的方塊也沒有
            // 重新讀入，塌縮流程看不到卸載，也就不需要重新套用。
            whenTicks(context, registered + 4, 200, remote::unloaded, unloadedAt -> at(context, unloadedAt + 3, hooks, () -> {
                witness.put("stillUnloadedBeforeTheReload", remote.unloaded());
                remote.reload();
            }), () -> "解除強制載入之後 chunk 仍是 FULL：witness=" + witness, hooks);
            whenTicks(context, registered + 5, 600, () -> remote.loaded() && reappliedTicks(log, sid).size() == 1, closedAt -> {
                witness.put("closedOnceAfterTheReload", doorAll(world, frame, false, MembraneState.NONE));
                // 不在 chunk 載入的當下寫：套用的 tick 不早於排入時記下的「下一個 tick」（關門另要等門口的 entity storage 載入，可能更晚）。
                witness.put("appliedNoEarlierThanTheQueuedTick", reapplyQueuedTicks(log, sid).size() == 1
                        && reappliedTicks(log, sid).getFirst() >= reapplyQueuedTicks(log, sid).getFirst());
                witness.put("anyPlayerOpensASealedDoor", interact(world, doorCell, q).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                at(context, closedAt + 40, hooks, () -> {
                    witness.put("notClosedAgain", doorAll(world, frame, true, MembraneState.NONE) && reapplyQueuedTicks(log, sid).size() == 1);
                    witness.put("closesAgainByHand", interact(world, doorCell, q).isAccepted() && doorAll(world, frame, false, MembraneState.NONE));
                    M5GameTestSupport.doorwayEntity(world, frame, hooks);
                    remote.unload();
                });
                whenTicks(context, closedAt + 41, 200, remote::unloaded, unloadedAgainAt -> at(context, unloadedAgainAt + 3, hooks, () -> {
                    witness.put("stillUnloadedBeforeTheSecondReload", remote.unloaded());
                    remote.reload();
                }), () -> "第二次解除強制載入之後 chunk 仍是 FULL：witness=" + witness, hooks);
                whenTicks(context, closedAt + 42, 600, () -> remote.loaded() && reappliedTicks(log, sid).size() == 2, openedAt -> {
                    witness.put("openedForTheEntityInTheDoorway", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("secondReapplyNoEarlierThanItsQueuedTick", reapplyQueuedTicks(log, sid).size() == 2
                            && reappliedTicks(log, sid).get(1) >= reapplyQueuedTicks(log, sid).get(1));
                    // 第三次（F4b-3 fix round 1，quality Q-M2）：Q 關門之後卸載、載入；排入的關門還在等門口的 entity storage 時 Q 開門 → 排入的關門取消，不會被關回去。
                    // 第二次重新載入之後，門口的盔甲座是從存檔重新載入的另一個 entity（原本的物件已隨 chunk 卸載移除），所以以世界查詢移走門口的 entity。
                    witness.put("doorwayClearedBeforeTheThirdReload", M5GameTestSupport.discardDoorwayEntities(world, frame) == 1);
                    witness.put("closedByHandBeforeTheThirdReload", interact(world, doorCell, q).isAccepted() && doorAll(world, frame, false, MembraneState.NONE));
                    remote.unload();
                    whenTicks(context, openedAt + 1, 200, remote::unloaded, unloadedThirdAt -> at(context, unloadedThirdAt + 3, hooks, () -> {
                        witness.put("stillUnloadedBeforeTheThirdReload", remote.unloaded());
                        remote.reload();
                    }), () -> "第三次解除強制載入之後 chunk 仍是 FULL：witness=" + witness, hooks);
                    whenTicks(context, openedAt + 2, 600, () -> remote.loaded() && reapplyQueuedTicks(log, sid).size() == 3, queuedAt -> {
                        witness.put("closeStillPendingWhenQueued", doorAll(world, frame, false, MembraneState.NONE) && reappliedTicks(log, sid).size() == 2);
                        witness.put("opensWhileTheCloseIsPending", interact(world, doorCell, q).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                        whenTicks(context, queuedAt, 300, () -> remote.entitiesLoaded(), storageAt -> at(context, storageAt + 2, hooks, () -> {
                            witness.put("staysOpenOnceTheEntityStorageLoads", doorAll(world, frame, true, MembraneState.NONE) && reappliedTicks(log, sid).size() == 2);
                            witness.put("onePendingCloseCancelled", pendingClosesCancelled(log, sid) == 1);
                            witness.put("queuedAsReloads", reapplyCauses(log, sid).equals(List.of("重新載入", "重新載入", "重新載入")));
                            witness.put("stillSealed", receiptStillSealed(journal, sid));
                            witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(log, sid));
                            LOGGER.info("M5_DOOR_RELOAD queued={} applied={} thirdQueuedAt={} entityStorageAt={}", reapplyQueuedTicks(log, sid), reappliedTicks(log, sid),
                                    queuedAt, storageAt);
                            hooks.release();
                            context.assertTrue(!witness.containsValue(false), "SEALED 的原艙門在 chunk 重新載入之後：" + witness + " queued=" + reapplyQueuedTicks(log, sid));
                            awaitConvergedThenComplete(context, 0, 20);
                        }), () -> "第三次載入之後門口的 entity storage 沒有載入：witness=" + witness, hooks);
                    }, () -> "第三次載入之後沒有排入重新套用：queued=" + reapplyQueuedTicks(log, sid) + " witness=" + witness, hooks);
                }, () -> "第二次載入之後沒有重新套用：applied=" + reappliedTicks(log, sid) + " witness=" + witness, hooks);
            }, () -> "載入之後沒有重新套用：loaded=" + remote.loaded() + " applied=" + reappliedTicks(log, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F4b-3 fix round 1（quality Q-I1、Q-M1，spec S-M2；spec §7.3「PREPARE 期間的開關門」、§6 H3、§5）：PREPARE 擁有原艙門時，攔截的非預期例外與不安全的終局寫出都拒絕、
     * 不改門、不結束塌縮，WARN 依 (session, 種類, 原因) 去重。DORMANT NEW 收據、參與者 P 在艙內；準備完成之後（門是 sealed 量子膜）依序：
     * <ul>
     * <li>判斷途中的例外（S-M2）：判斷身分時丟例外的非參與者（{@code M5GameTestSupport.lookupFailingPlayer}）直接呼叫 port 兩次：兩次都 REFUSED（不交給門服務），
     *     門不變，WARN「原艙門的攔截」只多一則。</li>
     * <li>處置途中的持續性例外（Q-I1）：{@code M5GameTestSupport.installDoorUseFault}（F5-0 起；testmod 的 fault mixin，取代以反射弄壞 {@code Track.backoff}）讓 P 的關門處置
     *     每次都丟例外；P 以 bulkhead 連點兩次：都被拒，門仍是 sealed、record 仍是 COLLAPSING、沒有終局，WARN 只多一則。</li>
     * <li>別的處置成功不清這個處置的去重鍵（F5-0，quality M-1）：艙內的非參與者 N 開門（處置成功）；N 離開、門重新套用 sealed 之後 P 再點一次 → 被拒，WARN 不再多。</li>
     * <li>recovery 寫入不安全（Q-M1）：還原 P 的處置；journal 有一筆未 checked 的 authority（{@code M5GameTestSupport.putUncheckedAuthority}）時 P 關門 → 被拒、
     *     不終局、門不變、那筆 authority 沒有被寫出，也沒有 WARN；移除之後：</li>
     * <li>同一個處置「失敗 → 成功 → 再失敗」會再記一次（F5-0，quality M-1）：P 的處置剛才沒有例外地完成（不安全而拒絕），清掉了自己的鍵；再讓處置失敗、P 點一次 →
     *     被拒，WARN 再多一則。</li>
     * <li>找出所屬 session 之前的例外（F5-0，spec m-1）：讓這座艙的攔截在找出所屬 session 時丟例外，P 直接呼叫 port 一次 → REFUSED、門不變；WARN 沒有 session 可帶，
     *     帶 tick 層級的 marker（{@code TICK_LOG_MARKER}，不算進這個 session 的 WARN）。之後 P 關門 → checked 終局、門關上。</li>
     * </ul>
     * 這個 session 的 WARN 只有上面三則（F4b-3 時另有「通道尚未實作」一則），沒有 ERROR。F5-1 起以 {@code holdReady} 暫緩 PASSAGE_READY 的提交，準備完成之後仍在 PREPARE。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_refusal", tickLimit = 1200)
    public void prepare_door_use_failures_refuse_and_warn_once_per_cause_until_recovery_writes_are_safe(TestContext context) {
        begin(context, "prepare_door_use_failures_refuse_and_warn_once_per_cause_until_recovery_writes_are_safe");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        var n = extraPlayer(context, hooks, frame).player();
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            // F5-1：準備完成之後仍要驗 PREPARE 期間的門（F4b-3 時停在 READY stub），所以暫緩 PASSAGE_READY 的提交。
            var hold = M5GameTestSupport.holdReady(context, sid, hooks);
            whenTicks(context, resolved + 1, 600, () -> !prepared(capture, sid).isEmpty(), ready -> {
                witness.put("sealedWhenPrepared", M5GameTestSupport.doorSealed(world, frame));
                witness.put("readyHeldInPrepare", hold.hits() > 0 && M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                int before = doorUseWarnings(capture, sid);
                var faulty = M5GameTestSupport.lookupFailingPlayer(world);
                witness.put("lookupFailureRefusedTwice", M5GameTestSupport.useDoor(world, frame, faulty) == ChamberCollapsePort.DoorUse.REFUSED
                        && M5GameTestSupport.useDoor(world, frame, faulty) == ChamberCollapsePort.DoorUse.REFUSED);
                witness.put("lookupFailureWarnedOnce", doorUseWarnings(capture, sid) - before == 1);
                witness.put("sealedAfterTheLookupFailures", M5GameTestSupport.doorSealed(world, frame));
                var fault = M5GameTestSupport.installDoorUseFault(context, sid, frame, hooks);
                int beforeHandling = doorUseWarnings(capture, sid);
                witness.put("failingCloseRefusedTwice", !interact(world, doorCell, p).isAccepted() && !interact(world, doorCell, p).isAccepted());
                witness.put("oneWarnForTwoFailingCloses", doorUseWarnings(capture, sid) - beforeHandling == 1);
                witness.put("sealedAndCollapsingAfterTheFailures", M5GameTestSupport.doorSealed(world, frame)
                        && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && endedByADoor(capture, sid) == 0);
                moveInside(n, frame);
                witness.put("insiderOpens", interact(world, doorCell, n).isAccepted() && doorAll(world, frame, true, MembraneState.NONE));
                moveOutside(n, frame);
                at(context, ready + 2, hooks, () -> {
                    witness.put("resealedAfterTheInsiderLeft", M5GameTestSupport.doorSealed(world, frame));
                    int beforeAgain = doorUseWarnings(capture, sid);
                    witness.put("failingCloseRefusedAgain", !interact(world, doorCell, p).isAccepted() && M5GameTestSupport.doorSealed(world, frame));
                    // F5-0（quality M-1）：去重鍵依處置區分，N 的成功（OPEN_FOR_INSIDER）不清 P 的處置（END_AND_CLOSE）的鍵，所以 P 同一原因的失敗不再記。
                    witness.put("notWarnedAgainAfterAnotherActionSucceeded", doorUseWarnings(capture, sid) == beforeAgain);
                    // F5-0（quality M-2）：三次失敗的關門都是注入的處置失敗（fault mixin），不是別的原因。
                    witness.put("closeFailuresWereInjected", fault.hits() == 3);
                    // 門改不了時（25 格之一暫時不是 bulkhead → DOOR_INCOMPLETE），艙內的非參與者被拒；同一個 callback 內放回原方塊（中間沒有 tick）。
                    var corner = local(frame, 1, 1, 0);
                    var cornerState = world.getBlockState(corner);
                    moveInside(n, frame);
                    ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(corner, Blocks.STONE.getDefaultState()));
                    witness.put("insiderRefusedWhenTheDoorCannotChange", M5GameTestSupport.useDoor(world, frame, n) == ChamberCollapsePort.DoorUse.REFUSED);
                    ChamberProtectionService.get().authorizedMutation(() -> world.setBlockState(corner, cornerState));
                    moveOutside(n, frame);
                    witness.put("sealedAfterTheIncompleteDoor", M5GameTestSupport.doorSealed(world, frame));
                    fault.disarm();
                    var unchecked = M5GameTestSupport.putUncheckedAuthority(server, hooks);
                    witness.put("recoveryWritesUnsafe", !journal.recoveryWritesSafe());
                    int beforeUnsafe = doorUseWarnings(capture, sid);
                    witness.put("refusedWhileRecoveryWritesAreUnsafe", !interact(world, doorCell, p).isAccepted() && M5GameTestSupport.doorSealed(world, frame)
                            && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && endedByADoor(capture, sid) == 0);
                    witness.put("uncheckedAuthorityNotWrittenOut", !journal.flushedRecords().containsKey(unchecked));
                    witness.put("noWarnWhileUnsafe", doorUseWarnings(capture, sid) == beforeUnsafe);
                    M5GameTestSupport.removeUncheckedAuthority(server, unchecked);
                    witness.put("recoveryWritesSafeAgain", journal.recoveryWritesSafe());
                    // F5-0（quality M-1）：P 的處置剛才沒有例外地完成（不安全而拒絕），清掉了自己的鍵：同一原因「失敗 → 成功 → 再失敗」會再記一次。
                    fault.arm();
                    int beforeOwnSuccess = doorUseWarnings(capture, sid);
                    witness.put("failingCloseRefusedAfterItsOwnSuccess", !interact(world, doorCell, p).isAccepted() && M5GameTestSupport.doorSealed(world, frame)
                            && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && fault.hits() == 4);
                    witness.put("warnedAgainAfterTheSameActionSucceeded", doorUseWarnings(capture, sid) - beforeOwnSuccess == 1);
                    fault.disarm();
                    // F5-0（spec m-1）：找出所屬 session 之前的例外沒有 session 可以歸屬：拒絕、不改門，WARN 帶 tick 層級的 marker（TICK_LOG_MARKER）。
                    fault.armLookup();
                    int lookupWarnsBefore = sessionLookupWarnings(capture, p);
                    witness.put("refusedWhenTheSessionLookupFails", M5GameTestSupport.useDoor(world, frame, p) == ChamberCollapsePort.DoorUse.REFUSED
                            && M5GameTestSupport.doorSealed(world, frame) && fault.lookupHits() == 1);
                    witness.put("sessionLookupFailureWarnsAtTheTickLevel", sessionLookupWarnings(capture, p) - lookupWarnsBefore == 1);
                    fault.disarmLookup();
                    witness.put("closesOnceSafe", interact(world, doorCell, p).isAccepted() && !journal.records().containsKey(sid)
                            && !journal.flushedRecords().containsKey(sid) && endedByADoor(capture, sid) == 1 && doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("onlyTheseWarnsAndNoError", doorUseWarnings(capture, sid) == 3
                            && capture.matching(Level.WARN, sid.toString()).size() == 3
                            && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                    releaseThenComplete(context, hooks, witness, "原艙門的攔截失敗與不安全的終局：" + witness + " warnings=" + doorUseWarnings(capture, sid), 20, () -> true);
                });
            }, () -> "沒有準備完成：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-0（F4b-3 spec m-2；spec §11.2、§7.3 :404）：SEALED 的原艙門帶著量子膜時，玩家照一般方式使用門不取消還沒套用的關門。之後的門切換若被拒或 rollback（門板不完整、
     * 寫入被拒），門仍是量子膜；關門照樣把它清掉，SEALED 的艙不會一直留著有碰撞的膜。原艙（{@code DormantOrigin}）上的 SEALED 收據（參與者是離線的隨機玩家，不會觸發重試），
     * 門上留著 sealed 量子膜（模擬進入 SEALED 時的關門還沒套用），再模擬重啟之後的第一次觀察（{@code M5GameTestSupport.simulateDoorStartup}）：塌縮流程只排入下一個 tick 的關門。
     * 排入之後、套用之前，非參與者 P 直接呼叫 port（SEALED 照一般門的邏輯：PASS）而不切換門（等同之後的門切換被拒）：
     * <ul>
     * <li>關門沒有被取消（沒有「取消還沒套用的關門」的 INFO）；</li>
     * <li>排入的關門照常在下一個 tick 套用：門關上，量子膜清掉。</li>
     * </ul>
     * 門沒有量子膜時照舊取消（T-RELOAD 的第三次重新載入）。catalog 增量 0（SEALED 收據不配置宇宙）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_door_sealed_membrane", tickLimit = 300)
    public void sealed_door_with_a_membrane_keeps_its_pending_close_when_a_player_uses_it(TestContext context) {
        begin(context, "sealed_door_with_a_membrane_keeps_its_pending_close_when_a_player_uses_it");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var sealedRecord = M5GameTestSupport.collapseFixture(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), Map.of(UUID.randomUUID(), true),
                    List.of(), true, SessionState.SEALED, false);
            var sid = sealedRecord.sessionUuid();
            origin.owned.add(sid);
            M5GameTestSupport.writeDoor(world, frame, true, MembraneState.sealedFor(frame.outwardFacing()));
            witness.put("membraneWritten", M5GameTestSupport.doorSealed(world, frame));
            M5GameTestSupport.simulateDoorStartup(server, sid);
            whenTicks(context, registered + 1, 10, () -> reapplyQueuedTicks(capture, sid).size() == 1, queuedAt -> {
                witness.put("stillAMembraneWhenUsed", M5GameTestSupport.doorSealed(world, frame) && reappliedTicks(capture, sid).isEmpty());
                // 只呼叫 port、不切換門：等同 PASS 之後的門切換被拒或 rollback，門維持量子膜。
                witness.put("usePassesForASealedChamber", M5GameTestSupport.useDoor(world, frame, p) == ChamberCollapsePort.DoorUse.PASS);
                witness.put("stillAMembraneAfterTheUse", M5GameTestSupport.doorSealed(world, frame));
                whenTicks(context, queuedAt + 1, 20, () -> !reappliedTicks(capture, sid).isEmpty() || pendingClosesCancelled(capture, sid) > 0, settledAt -> {
                    witness.put("pendingCloseKeptWhileTheDoorHasAMembrane", pendingClosesCancelled(capture, sid) == 0);
                    witness.put("closedByThePendingClose", doorAll(world, frame, false, MembraneState.NONE) && reappliedTicks(capture, sid).size() == 1);
                    witness.put("stillSealed", receiptStillSealed(journal, sid));
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                    LOGGER.info("M5_DOOR_SEALED_MEMBRANE queued={} applied={} cancelled={}", reapplyQueuedTicks(capture, sid), reappliedTicks(capture, sid),
                            pendingClosesCancelled(capture, sid));
                    hooks.release();
                    context.assertTrue(!witness.containsValue(false), "SEALED 的原艙門帶著量子膜時被使用：" + witness);
                    awaitConvergedThenComplete(context, 0, 20);
                }, () -> "排入的關門沒有套用、也沒有被取消：witness=" + witness, hooks);
            }, () -> "模擬重啟之後沒有排入重新套用：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
        });
    }

    /**
     * F5-2（spec §10.6 :613、plan Step 1「沒有所屬 session 的 membrane 在下一 tick 正規化」；M5 note F4b-3「補充第 8 點」的四個條件）：同一座已供電的原艙（{@code DormantOrigin}）
     * 依序（每次都以 {@code ChamberControllerBlock.refreshState} 強制刷新 Controller；門的改動本身也會經 neighbor update 刷新）：
     * <ol>
     * <li>SEALED 的 fixture（暫停，不交給塌縮流程）＋門上的 sealed 量子膜：presence 是 COLLAPSE，不排入正規化（沒有「找不到所屬 session」的 INFO），幾個 tick 之後門仍是 sealed
     *     （排除，chamber 端；有 SEALED session 的門由它自己的關門負責）。</li>
     * <li>以終局 API 結束這個 fixture、門不動（模擬終局之後、改門之前 crash）→ 刷新：排入下一個 tick（INFO 記下 tick N＝刷新的 server tick＋1），刷新當下門還是量子膜；
     *     tick N 正規化為一般開門（完成的 INFO 記下的 tick 也是 N）。</li>
     * <li>門改為 passable 量子膜（沒有 session：排入）→ 同一個 callback 建立 PASSAGE_READY 的 fixture（暫停）：下一個 tick 丟棄這次正規化（INFO「由塌縮流程的 session 負責」，
     *     帶這個 session），門仍是 passable（排除，塌縮流程端）。終局之後再刷新 → 正規化為一般開門（sealed 與 passable 都改為 OPEN）。</li>
     * <li>MEASURED DORMANT 收據（參與者是離線的隨機玩家，不會觸發續行）＋sealed 量子膜 → 刷新 → 正規化為一般開門（MEASURED／DORMANT 的艙也包含在內）。</li>
     * </ol>
     * 這座艙沒有任何 WARN／ERROR，也沒有 tick 層級的 WARN／ERROR。catalog 增量 0（fixture 與收據都不配置宇宙）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_membrane_ownerless", tickLimit = 400)
    public void ownerless_membrane_is_normalized_on_the_next_tick_unless_a_collapse_session_owns_the_door(TestContext context) {
        begin(context, "ownerless_membrane_is_normalized_on_the_next_tick_unless_a_collapse_session_owns_the_door");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var sealedMembrane = MembraneState.sealedFor(frame.outwardFacing());
        Runnable refresh = () -> ChamberControllerBlock.refreshState(world, frame.controllerPos());
        origin.whenRegistered((registered, chamber) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authority = M4CandidateTestAccess.origin(chamber, frame.controllerPos());
            // 1. SEALED 的 session 擁有門：chamber 端不排入。
            var sealed = M5GameTestSupport.collapseFixture(server, authority, Map.of(UUID.randomUUID(), true), List.of(), true, SessionState.SEALED, true);
            origin.owned.add(sealed.sessionUuid());
            M5GameTestSupport.writeDoor(world, frame, true, sealedMembrane);
            refresh.run();
            at(context, registered + 5, hooks, () -> {
                witness.put("sealedSessionKeepsItsMembrane", M5GameTestSupport.doorSealed(world, frame) && ownerlessQueued(capture, chamber).isEmpty());
                // 2. 終局之後、改門之前 crash：門仍是量子膜，沒有 session。
                cleanupFixtures(server, List.of(sealed.sessionUuid()));
                witness.put("terminatedWithTheMembraneLeft", !journal.flushedRecords().containsKey(sealed.sessionUuid()) && M5GameTestSupport.doorSealed(world, frame));
                int refreshedAt = server.getTicks();
                refresh.run();
                witness.put("queuedForTheNextTickWithoutWriting", ownerlessQueued(capture, chamber).equals(List.of(refreshedAt + 1))
                        && M5GameTestSupport.doorSealed(world, frame));
                whenTicks(context, registered + 6, 20, () -> !ownerlessNormalized(capture, chamber).isEmpty(), normalizedAt -> {
                    witness.put("normalizedOnTheQueuedTick", ownerlessNormalized(capture, chamber).equals(List.of(refreshedAt + 1))
                            && doorAll(world, frame, true, MembraneState.NONE));
                    // 3. passable → 排入 → 同一個 callback 建立 PASSAGE_READY 的 session：塌縮流程端在套用時排除。
                    M5GameTestSupport.writeDoor(world, frame, true, MembraneState.passableFor(frame.outwardFacing()));
                    refresh.run();
                    witness.put("passableQueued", ownerlessQueued(capture, chamber).size() == 2);
                    var ready = M5GameTestSupport.collapseFixture(server, authority, Map.of(UUID.randomUUID(), true), List.of(), true, SessionState.PASSAGE_READY, true);
                    origin.owned.add(ready.sessionUuid());
                    whenTicks(context, normalizedAt + 1, 20, () -> ownerlessSkipped(capture, chamber, ready.sessionUuid()) > 0, skippedAt -> {
                        witness.put("readySessionKeepsItsPassableMembrane", M5GameTestSupport.doorPassable(world, frame)
                                && ownerlessNormalized(capture, chamber).size() == 1 && ownerlessSkipped(capture, chamber, ready.sessionUuid()) == 1);
                        cleanupFixtures(server, List.of(ready.sessionUuid()));
                        refresh.run();
                        whenTicks(context, skippedAt + 1, 20, () -> ownerlessNormalized(capture, chamber).size() == 2, passableNormalizedAt -> {
                            witness.put("passableNormalizedAfterTheTerminal", doorAll(world, frame, true, MembraneState.NONE)
                                    && ownerlessQueued(capture, chamber).size() == 3);
                            // 4. MEASURED DORMANT 的艙也包含在內。
                            var dormant = origin.own(origin.receipt(chamber, Map.of(UUID.randomUUID(), true), true));
                            M5GameTestSupport.writeDoor(world, frame, true, sealedMembrane);
                            refresh.run();
                            whenTicks(context, passableNormalizedAt + 1, 20, () -> ownerlessNormalized(capture, chamber).size() == 3, dormantNormalizedAt -> {
                                witness.put("dormantReceiptChamberNormalized", doorAll(world, frame, true, MembraneState.NONE)
                                        && ownerlessQueued(capture, chamber).size() == 4 && M5GameTestSupport.state(journal, dormant.sessionUuid()).equals("MEASURED"));
                                // 每一次都在排入時記下的 tick N 完成（第 2 次在 3. 被丟棄）。
                                var queuedTicks = ownerlessQueued(capture, chamber);
                                witness.put("normalizedEachOnItsQueuedTick", queuedTicks.size() == 4
                                        && ownerlessNormalized(capture, chamber).equals(List.of(queuedTicks.get(0), queuedTicks.get(2), queuedTicks.get(3))));
                                witness.put("noWarnOrError", capture.matching(Level.WARN, chamber.toString()).isEmpty() && capture.matching(Level.ERROR, chamber.toString()).isEmpty()
                                        && capture.marked(Level.WARN, CollapseService.TICK_LOG_MARKER.getName()).isEmpty()
                                        && capture.marked(Level.ERROR, CollapseService.TICK_LOG_MARKER.getName()).isEmpty());
                                LOGGER.info("M5_MEMBRANE_OWNERLESS registered={} queued={} normalized={} skipped={}", registered, ownerlessQueued(capture, chamber),
                                        ownerlessNormalized(capture, chamber), ownerlessSkipped(capture, chamber, ready.sessionUuid()));
                                hooks.release();
                                context.assertTrue(!witness.containsValue(false), "找不到所屬 session 的量子膜：" + witness);
                                awaitConvergedThenComplete(context, 0, 20);
                            }, () -> "DORMANT 收據的艙沒有正規化：queued=" + ownerlessQueued(capture, chamber) + " witness=" + witness, hooks);
                        }, () -> "PASSAGE_READY 終局之後沒有正規化：queued=" + ownerlessQueued(capture, chamber) + " witness=" + witness, hooks);
                    }, () -> "PASSAGE_READY 的 session 沒有讓正規化被丟棄：queued=" + ownerlessQueued(capture, chamber) + " normalized="
                            + ownerlessNormalized(capture, chamber) + " passable=" + M5GameTestSupport.doorPassable(world, frame) + " witness=" + witness, hooks);
                }, () -> "終局之後沒有正規化：queued=" + ownerlessQueued(capture, chamber) + " witness=" + witness, hooks);
            });
        });
    }

    /**
     * F5-2（spec §10.6 :613）：這座艙「找不到所屬 session、卻帶著量子膜的原艙門：下一個 tick（tick N）」的 INFO 記下的 tick N，依發生順序（帶 TICK_LOG_MARKER：不屬於任何
     * session，所以依 chamber 篩選）。
     */
    private static List<Integer> ownerlessQueued(LogCapture capture, UUID chamber) {
        return ticks(capture.marked(Level.INFO, CollapseService.TICK_LOG_MARKER.getName(), "找不到所屬 session、卻帶著量子膜的原艙門", "chamber=" + chamber),
                Pattern.compile("下一個 tick（tick (\\d+)）"));
    }

    /** F5-2：這座艙「找不到所屬 session 的量子膜已正規化為一般開門（…，tick M）」的 INFO 記下的 tick M，依發生順序（帶 TICK_LOG_MARKER）。 */
    private static List<Integer> ownerlessNormalized(LogCapture capture, UUID chamber) {
        return ticks(capture.marked(Level.INFO, CollapseService.TICK_LOG_MARKER.getName(), "找不到所屬 session 的量子膜已正規化為一般開門", "chamber=" + chamber),
                Pattern.compile("，tick (\\d+)）"));
    }

    /** F5-2：這座艙的正規化因為 {@code sid} 擁有門而被丟棄的 INFO 數（帶 LOG_MARKER 與 session）。 */
    private static int ownerlessSkipped(LogCapture capture, UUID chamber, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "不再正規化找不到所屬 session 的量子膜", "chamber=" + chamber, "session=" + sid).size();
    }

    /** 這個 session「SEALED 的原艙門被玩家照一般方式使用：取消還沒套用的關門」的 INFO 數（帶 LOG_MARKER；F4b-3 fix round 1）。 */
    private static int pendingClosesCancelled(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "取消還沒套用的關門", "session=" + sid).size();
    }

    /** 這個 session「原艙門的攔截」的 WARN 數（帶 LOG_MARKER；判斷與處置途中的非預期例外）。 */
    private static int doorUseWarnings(LogCapture capture, UUID sid) {
        return capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "原艙門的攔截", sid.toString()).size();
    }

    /** 這位玩家「原艙門的攔截在找出所屬 session 時丟出例外」的 WARN 數，只算帶 TICK_LOG_MARKER 的（沒有 session 可帶；F5-0，spec m-1）。 */
    private static int sessionLookupWarnings(LogCapture capture, ServerPlayerEntity player) {
        return capture.marked(Level.WARN, CollapseService.TICK_LOG_MARKER.getName(), "找出所屬 session", "玩家=" + player.getUuid()).size();
    }

    /** 這個 session「PREPARE 期間參與者關門，塌縮已結束」的 INFO 數（帶 LOG_MARKER）。 */
    private static int endedByADoor(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "PREPARE 期間參與者關門，塌縮已結束", sid.toString()).size();
    }

    /** discovery 有這個 UniverseId 的 gameplay-eligible record（直接讀正式檔）。 */
    private static boolean discovered(MinecraftServer server, UniverseId id) {
        try {
            return UniverseDiscoveryState.load(server.getSavePath(WorldSavePath.ROOT)).find(id).filter(record -> record.gameplayEligible()).isPresent();
        } catch (IOException failure) {
            throw new IllegalStateException("讀不到 discovery", failure);
        }
    }
}
