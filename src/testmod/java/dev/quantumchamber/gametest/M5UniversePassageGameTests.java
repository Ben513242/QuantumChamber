package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.cleanupFixtures;
import static dev.quantumchamber.gametest.M5GameTestSupport.doorAll;
import static dev.quantumchamber.gametest.M5GameTestSupport.interact;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveOutside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;
import static dev.quantumchamber.gametest.M5UniverseHold.asTestFailure;
import static dev.quantumchamber.gametest.M5UniverseHold.begin;
import static dev.quantumchamber.gametest.M5UniverseSupport.PREPARE_WAIT_TICKS;
import static dev.quantumchamber.gametest.M5UniverseSupport.allocationEntries;
import static dev.quantumchamber.gametest.M5UniverseSupport.collapsedToUniverse;
import static dev.quantumchamber.gametest.M5UniverseSupport.entered;
import static dev.quantumchamber.gametest.M5UniverseSupport.local;
import static dev.quantumchamber.gametest.M5UniverseSupport.membraneInfos;
import static dev.quantumchamber.gametest.M5UniverseSupport.openOrigin;
import static dev.quantumchamber.gametest.M5UniverseSupport.outside;
import static dev.quantumchamber.gametest.M5UniverseSupport.passageOpened;
import static dev.quantumchamber.gametest.M5UniverseSupport.prepared;
import static dev.quantumchamber.gametest.M5UniverseSupport.reappliedTicks;
import static dev.quantumchamber.gametest.M5UniverseSupport.reapplyCauses;
import static dev.quantumchamber.gametest.M5UniverseSupport.reapplyQueuedTicks;
import static dev.quantumchamber.gametest.M5UniverseSupport.releaseThenComplete;
import static dev.quantumchamber.gametest.M5UniverseSupport.sealedWith;

import com.sun.jna.Platform;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.CollapseAuthorities;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DesiredAvailability;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.vehicle.MinecartEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 run 的 GameTest：量子通道（F5-1 起：跨越、到期、關門與斷電的終局、既有宇宙、傳送的回復與未確認的寫入、比較器、到達的提示）。由
 * {@link M5UniverseGameTests} 依主題純搬移拆出（F6-3c-2，盤點 #06）；test 的方法名稱、batchId、tickLimit 與 {@code @GameTest} 的屬性都不變，寫法的規則見
 * {@link M5UniverseGameTests} 的類別說明與 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 */
public final class M5UniversePassageGameTests implements FabricGameTest {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    // ---- F5-1：通道本體（PASSAGE_READY、跨越、passage 傳送、結束） ----

    /**
     * F5-1（spec §10.2、§10.3、§10.6 (b)、§10.8；plan Step 1 F5-1 的跨越、「艙內有非參與者時仍能提交 READY」、§15「非參與者與騎乘中的參與者不觸發傳送」、F4b-2 交下的
     * {@code releaseDestination} 測試）：DORMANT NEW 收據、參與者 P；非參與者 N 在 RESOLVE 之後、第一次閘門評估之前進艙。
     * <ul>
     * <li>PREPARE 期間 N 在艙內：門維持一般開門（不套用 sealed 量子膜），準備照常完成，PASSAGE_READY 照樣提交（expires＝opened＋1200、Crossed 是空的），門改為 passable 量子膜；
     *     「量子通道已開啟」只給艙內的參與者 P，N 沒有收到。</li>
     * <li>N 由門板中線內側走到外側（穿過量子膜）：什麼都不觸發，N 留在原世界、Crossed 仍是空的。</li>
     * <li>P 在越過中線的同一個 tick 騎上礦車：不觸發傳送，留在原世界。礦車浮在 local y=2（不受重力），騎乘時腳仍在門口、中線外側（礦車的乘坐點比礦車低，礦車放在
     *     y=1 時腳會低於門口），所以只有「沒有騎乘」這個條件擋下跨越（{@code riderInTheDoorwayPastTheMidline}）；這時 P 沒有收到 title（F5-3）。</li>
     * <li>P 下來之後由中線內側越到外側：傳送到宇宙 U 的同 XYZ、同 yaw、pitch，站在目的投影艙的門口（同一個 local 座標，門是 {@code open=true}＋{@code membrane=none}），
     *     Crossed＝[P]，INFO「已穿過量子通道」一則。F5-3（spec §10.8、§20.8、§20.9）：P 收到 NEW 的到達提示 title（先送淡入、停留、淡出 10／70／20，沒有 subtitle），
     *     actionbar 沒有任何訊息。</li>
     * <li>下一個 tick (b) 全員都已跨過：checked 終局（INFO (b) 一則），門改為一般開門，目的世界的 ticket 已釋放（{@code releaseDestination}），runtime 與來源 ticket 都已釋放、
     *     presence 回到 NONE；P 已在 Crossed 中，不再收到「量子通道已關閉」（F5-3，spec §20.9；F5-1 時是收到）。非參與者 N 沒有收到 title。這個 session 沒有任何 WARN／ERROR。</li>
     * </ul>
     * catalog 增量 1（U）。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_cross", tickLimit = 900)
    public void passage_participant_crosses_to_the_same_pose_while_non_participants_and_riders_stay(TestContext context) {
        begin(context, "passage_participant_crosses_to_the_same_pose_while_non_participants_and_riders_stay");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        ConnectedGameTestPlayer outsider;
        try {
            outsider = new ConnectedGameTestPlayer(world);
            hooks.add(outsider::close);
            outsider.player().setNoGravity(true);
            moveOutside(outsider.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var n = outsider.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(n, frame);
            M5GameTestSupport.overlayKeys(origin.connection); M5GameTestSupport.overlayKeys(outsider);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                var ready = journal.flushedRecords().get(sid);
                witness.put("plainOpenWhilePreparingWithANonParticipantInside", membraneInfos(capture, sid, "原艙門維持一般開門") == 1
                        && membraneInfos(capture, sid, "原艙門套用 sealed 量子膜") == 0);
                witness.put("readyCommittedWithTheNonParticipantInside", passageOpened(capture, sid) == 1 && ready.collapse().crossed().isEmpty()
                        && ready.collapse().passageExpiresAtGameTime() == ready.collapse().passageOpenedAtGameTime() + 1200);
                witness.put("passableMembrane", M5GameTestSupport.doorPassable(world, frame));
                witness.put("openedMessageOnlyToTheParticipantInside", M5GameTestSupport.overlayKeys(origin.connection).contains(CollapseService.MESSAGE_PASSAGE_OPENED)
                        && !M5GameTestSupport.overlayKeys(outsider).contains(CollapseService.MESSAGE_PASSAGE_OPENED));
                MinecartEntity[] cart = {null};
                at(context, readyAt + 1, hooks, () -> {
                    M5GameTestSupport.standInDoorway(n, frame, 2.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                    M5GameTestSupport.standInDoorway(p, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                });
                at(context, readyAt + 2, hooks, () -> {
                    // N 穿過量子膜；P 越過中線的同一個 tick 騎上一台不受重力、浮在 local y=2 的礦車（騎乘時腳仍在門口）。
                    M5GameTestSupport.standInDoorway(n, frame, 2.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                    M5GameTestSupport.standInDoorway(p, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                    cart[0] = EntityType.MINECART.create(world);
                    if (cart[0] == null) throw new IllegalStateException("建不出礦車");
                    var cartPos = ChamberSpaceCoordinates.position(frame, 4.5, 2, 0.3);
                    cart[0].refreshPositionAndAngles(cartPos.x, cartPos.y, cartPos.z, 0, 0);
                    cart[0].setNoGravity(true);
                    hooks.add(cart[0]::discard);
                    witness.put("ridingFixture", world.spawnEntity(cart[0]) && p.startRiding(cart[0], true) && p.hasVehicle());
                });
                at(context, readyAt + 3, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("nonParticipantStaysInTheOriginalWorld", n.getServerWorld() == world && record.collapse().crossed().isEmpty());
                    var riderLocal = ChamberSpaceCoordinates.localPosition(frame, p.getPos());
                    witness.put("riderInTheDoorwayPastTheMidline", p.hasVehicle() && riderLocal.x >= 1 && riderLocal.x < 6 && riderLocal.y >= 1 && riderLocal.y < 6
                            && riderLocal.z < 0.5);
                    witness.put("riderStaysInTheOriginalWorld", p.getServerWorld() == world && record.collapse().crossed().isEmpty() && crossedInfos(capture, sid) == 0);
                    // F5-3：沒有傳送就沒有到達提示（騎乘時越過中線）。
                    witness.put("noTitleForTheRider", M5GameTestSupport.hud(origin.connection).titles().isEmpty());
                    p.stopRiding();
                    cart[0].discard();
                });
                float yaw = 37.5f, pitch = -12.25f;
                var expected = ChamberSpaceCoordinates.position(frame, 3.5, 1, 0.3);
                at(context, readyAt + 4, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, yaw, pitch, Vec3d.ZERO));
                at(context, readyAt + 5, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, yaw, pitch, new Vec3d(0.05, 0, -0.12)));
                at(context, readyAt + 6, hooks, () -> {
                    var crossed = journal.flushedRecords().get(sid);
                    witness.put("crossedToTheUniverse", p.getServerWorld() == universe && universe.getEntity(p.getUuid()) == p);
                    witness.put("sameXyz", p.getPos().squaredDistanceTo(expected) < 1e-12);
                    witness.put("sameYawAndPitch", Math.abs(MathHelper.wrapDegrees(p.getYaw() - yaw)) < 1e-4 && Math.abs(p.getPitch() - pitch) < 1e-4);
                    witness.put("inTheProjectedDoorway", ChamberSpaceCoordinates.localPosition(frame, p.getPos()).squaredDistanceTo(new Vec3d(3.5, 1, 0.3)) < 1e-12
                            && doorAll(universe, frame, true, MembraneState.NONE));
                    witness.put("crossedRecorded", crossed != null && crossed.collapse().crossed().equals(List.of(p.getUuid())));
                    witness.put("oneCrossingInfo", crossedInfos(capture, sid) == 1);
                    // F5-3（spec §10.8、§20.8、§20.9）：到達的那位玩家收到 NEW 的 title，先送淡入、停留、淡出 10／70／20，沒有 subtitle，也不在 actionbar。
                    var arrival = M5GameTestSupport.hud(origin.connection);
                    witness.put("arrivalTitleForTheCrossedParticipant", arrival.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                    witness.put("arrivalNotOnTheActionbar", arrival.overlays().isEmpty());
                });
                whenTicks(context, readyAt + 7, 10, () -> !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid), ended -> {
                    witness.put("endedWhenEveryoneCrossed", passageEnded(capture, sid, "(b) 全員都已跨過") == 1);
                    witness.put("plainOpenAfterTheEnd", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    witness.put("runtimeAndSourceTicketReleased", !M4CandidateTestAccess.runtimeHeld(sid) && !M4CandidateTestAccess.sourceTicketHeld(sid));
                    // F5-3（spec §20.9）：已跨過的人不再收到「量子通道已關閉」（到達提示之後什麼都沒有收到）；非參與者 N 沒有收到 title。
                    var afterEnd = M5GameTestSupport.hud(origin.connection);
                    witness.put("noClosedMessageToTheCrossedParticipant", afterEnd.overlays().isEmpty() && afterEnd.titles().isEmpty());
                    witness.put("noTitleForTheNonParticipant", M5GameTestSupport.hud(outsider).titles().isEmpty());
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    LOGGER.info("M5_PASSAGE_CROSS ready={} ended={} position={}", readyAt, ended, p.getPos());
                    releaseThenComplete(context, hooks, witness, "通道的跨越：" + witness, 20, () -> true);
                }, () -> "全員跨過之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.6 (c)、§7.3「門狀態的重新套用」；§15「兩人：一人跨過、一人留下 → 到期後門恢復一般開門、record 已移除 → 同一座艙可以開新 session」）：參與者 P、B 都在艙內。
     * <ul>
     * <li>PASSAGE_READY 之後，模擬重啟之後的第一次觀察（{@code simulateDoorStartup}）並把門寫成一般開門（重啟留下的舊狀態）：觀察到的那個 tick 只排入、不寫門，下一個 tick 依
     *     collapse view 重新套用 passable 量子膜（INFO 記下的排入 tick 與套用 tick 相同），只有一次。</li>
     * <li>P 越過中線、傳送到 U；B 留在艙內。到期之前 record 一直是 PASSAGE_READY、Crossed＝[P]。只有 P 收到到達提示的 title（F5-3）。</li>
     * <li>game time 到 expires（opened＋1200）時 (c) 結束：checked 終局（INFO (c) 一則），門改為一般開門，目的世界的 ticket、runtime 與來源 ticket 都已釋放，presence NONE；
     *     只有 B（在艙內、不在 Crossed 中）收到「量子通道已關閉」，P（在 U、已在 Crossed 中）沒有收到（F5-3，spec §20.9；F5-1 時兩人都收到）。</li>
     * <li>同一座艙可以開新 session：B 有 Buff、在艙內，以 bulkhead 關上門就重新入場（新的 session）。等它真入場（走廊發布 358 扇側門）之後斷電，等這個 M2 session 依一般的 OFF 流程把 B 返還、移除 record 才完成。M2 入場
     *     需要 Windows 原生 checkpoint，所以名稱以 {@code _windows} 結尾；到期本身另由 {@code crossed_write_failure_pauses_the_passage_until_it_converges_then_expires} 在各平台覆蓋。</li>
     * </ul>
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_expire", tickLimit = 45000)
    public void passage_expires_after_one_of_two_crossed_and_the_chamber_opens_a_new_session_windows(TestContext context) {
        if (!Platform.isWindows()) { context.complete(); return; }
        begin(context, "passage_expires_after_one_of_two_crossed_and_the_chamber_opens_a_new_session_windows");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(world);
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var b = second.player();
        collapsedToUniverse(context, origin, hooks, List.of(b.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(b, frame);
            M5GameTestSupport.overlayKeys(origin.connection); M5GameTestSupport.overlayKeys(second);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                long expires = journal.flushedRecords().get(sid).collapse().passageExpiresAtGameTime();
                witness.put("openedMessageToBothInside", M5GameTestSupport.overlayKeys(origin.connection).contains(CollapseService.MESSAGE_PASSAGE_OPENED)
                        && M5GameTestSupport.overlayKeys(second).contains(CollapseService.MESSAGE_PASSAGE_OPENED));
                at(context, readyAt + 1, hooks, () -> {
                    M5GameTestSupport.writeDoor(world, frame, true, MembraneState.NONE);
                    M5GameTestSupport.simulateDoorStartup(server, sid);
                });
                whenTicks(context, readyAt + 2, 10, () -> !reapplyQueuedTicks(capture, sid).isEmpty(), queuedAt -> {
                    witness.put("notWrittenWhenObserved", doorAll(world, frame, true, MembraneState.NONE));
                    whenTicks(context, queuedAt + 1, 5, () -> !reappliedTicks(capture, sid).isEmpty(), appliedAt -> {
                        witness.put("passableReappliedOnTheQueuedTick", M5GameTestSupport.doorPassable(world, frame)
                                && reappliedTicks(capture, sid).equals(reapplyQueuedTicks(capture, sid)) && reapplyCauses(capture, sid).equals(List.of("重啟之後第一次載入")));
                        at(context, appliedAt + 1, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO));
                        at(context, appliedAt + 2, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                        at(context, appliedAt + 4, hooks, () -> {
                            var record = journal.flushedRecords().get(sid);
                            witness.put("oneCrossedOneStayed", p.getServerWorld() == universe && b.getServerWorld() == world && record != null
                                    && record.state() == SessionState.PASSAGE_READY && record.collapse().crossed().equals(List.of(p.getUuid())));
                            witness.put("reappliedOnlyOnce", reapplyQueuedTicks(capture, sid).size() == 1 && reappliedTicks(capture, sid).size() == 1);
                            // F5-3（spec §20.8、§20.9）：只有跨過的 P 收到到達提示的 title，留下的 B 沒有。
                            var crossedHud = M5GameTestSupport.hud(origin.connection);
                            var stayedHud = M5GameTestSupport.hud(second);
                            witness.put("arrivalTitleOnlyForTheOneWhoCrossed", crossedHud.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY))
                                    && stayedHud.titles().isEmpty());
                        });
                        whenTicks(context, appliedAt + 5, 1300, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                            witness.put("endedByExpiry", passageEnded(capture, sid, "(c) game time 已到 PassageExpiresAtGameTime") == 1
                                    && server.getOverworld().getTime() >= expires);
                            witness.put("plainOpenAfterTheExpiry", doorAll(world, frame, true, MembraneState.NONE));
                            witness.put("everythingReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty() && !M4CandidateTestAccess.runtimeHeld(sid)
                                    && !M4CandidateTestAccess.sourceTicketHeld(sid));
                            witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                            // F5-3（spec §20.9）：(c) 結束時只有不在 Crossed 中的 B 收到「量子通道已關閉」，已跨過的 P 沒有收到。
                            var crossedAtEnd = M5GameTestSupport.hud(origin.connection);
                            var stayedAtEnd = M5GameTestSupport.hud(second);
                            witness.put("closedMessageOnlyToTheOneWhoStayed", crossedAtEnd.overlays().isEmpty()
                                    && stayedAtEnd.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED)));
                            witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                            // 同一座艙可以開新 session：B 有 Buff、在艙內，關上門就重新入場。
                            b.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
                            witness.put("doorClosedByB", interact(world, local(frame, 3, 3, 0), b).isAccepted());
                            var next = journal.flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(chamber)).findFirst();
                            witness.put("newSessionStarted", next.isPresent() && !next.get().sessionUuid().equals(sid));
                            context.assertTrue(!witness.containsValue(false), "通道到期與新的 session：" + witness);
                            // 收尾：等新的 session 真入場（走廊發布 358 扇側門，同 NetherChamber.whenEntered），才斷電走一般的 M2 返還。入場途中（ARMING）斷電的返還另由 M2 的 test 覆蓋。
                            var nextSid = next.get().sessionUuid();
                            var pages = dev.quantumchamber.corridor.CorridorPageManager.forServer(server);
                            whenTicks(context, ended + 1, 20000, () -> Optional.ofNullable(journal.flushedRecords().get(nextSid))
                                    .filter(record -> record.state() == SessionState.SUPERPOSITION).isPresent()
                                    && pages.currentMappings(nextSid).epoch() == 1 && pages.selectableDoors(nextSid).size() == 358, entered -> {
                                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                                whenTicks(context, entered + 1, 20000, () -> journal.flushedRecords().values().stream().noneMatch(record -> record.chamberUuid().equals(chamber))
                                        && ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE, gone -> {
                                    witness.put("newSessionReturnedB", b.getServerWorld() == world);
                                    LOGGER.info("M5_PASSAGE_EXPIRE ready={} expires={} ended={} newSession={} entered={} gone={}", readyAt, expires, ended, nextSid, entered, gone);
                                    releaseThenComplete(context, hooks, witness, "通道到期：" + witness, 20, () -> true);
                                }, () -> "新的 M2 session 斷電之後沒有結束：records=" + journal.flushedRecords().values().stream()
                                        .filter(record -> record.chamberUuid().equals(chamber)).map(record -> record.state().name()).toList(), hooks);
                            }, () -> "新的 M2 session 沒有真入場：record=" + M5GameTestSupport.state(journal, nextSid), hooks);
                        }, () -> "到期之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " time=" + server.getOverworld().getTime() + " expires=" + expires
                                + " witness=" + witness, hooks);
                    }, () -> "排入之後沒有重新套用 passable：applied=" + reappliedTicks(capture, sid) + " witness=" + witness, hooks);
                }, () -> "模擬重啟之後沒有排入重新套用：witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.5、§10.6 (a)、§10.8）：PASSAGE_READY 之後，非參與者 N 在艙外以 bulkhead、在艙內以 Controller 開關門：都被拒（ActionResult 不是 accepted），門仍是 passable、
     * record 仍是 PASSAGE_READY。參與者 P 在原艙這一側以 bulkhead 關門：先 checked 終局（INFO (a) 一則），再把門改為關閉（門口沒有 entity），目的世界的 ticket 已釋放、presence NONE；
     * 還沒跨過的 P 收到「量子通道已關閉」（F5-3 起精確比對：恰一則 actionbar、沒有 title，spec §20.9）；之後門一直關著。這個 session 沒有任何 WARN／ERROR。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_close", tickLimit = 900)
    public void passage_participant_closing_the_door_ends_it_and_non_participants_are_refused(TestContext context) {
        begin(context, "passage_participant_closing_the_door_ends_it_and_non_participants_are_refused");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        ServerPlayerEntity n;
        try {
            var outsider = new ConnectedGameTestPlayer(world);
            hooks.add(outsider::close);
            n = outsider.player();
            n.setNoGravity(true);
            moveOutside(n, frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                M5GameTestSupport.overlayKeys(origin.connection);
                witness.put("outsiderRefusedAtTheBulkhead", !interact(world, doorCell, n).isAccepted() && M5GameTestSupport.doorPassable(world, frame));
                moveInside(n, frame);
                witness.put("insiderRefusedAtTheController", !interact(world, frame.controllerPos(), n).isAccepted() && M5GameTestSupport.doorPassable(world, frame));
                witness.put("stillReady", M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY") && passageEnded(capture, sid, "量子通道已結束") == 0);
                moveOutside(n, frame);
                at(context, readyAt + 1, hooks, () -> {
                    witness.put("participantClosesTheDoor", interact(world, doorCell, p).isAccepted());
                    witness.put("terminatedAtOnce", !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid));
                    witness.put("endedByTheParticipant", passageEnded(capture, sid, "(a) 參與者在原艙這一側開關原艙門") == 1);
                    witness.put("doorClosedAfterTheTerminal", doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    // F5-3（spec §20.9）：還沒跨過的參與者仍收到「量子通道已關閉」（恰一則 actionbar，沒有 title）。
                    var atClose = M5GameTestSupport.hud(origin.connection);
                    witness.put("closedMessage", atClose.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED)) && atClose.titles().isEmpty());
                    witness.put("notTransferred", p.getServerWorld() == world && crossedInfos(capture, sid) == 0);
                });
                at(context, readyAt + 5, hooks, () -> {
                    witness.put("staysClosed", doorAll(world, frame, false, MembraneState.NONE));
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    releaseThenComplete(context, hooks, witness, "通道期間的開關門：" + witness, 20, () -> true);
                });
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.6 (d)、§7「已供電」是即時紅石輸入、§10.7 的 OFF 流程）：PASSAGE_READY 之後斷電：下一個 tick checked 終局（INFO (d)「來源艙沒有供電」一則；不經
     * {@code returnToOrigin}），門改為一般開門（不是關門），目的世界的 ticket 釋放，presence NONE，之後 registry 的 power state 依一般的 OFF 流程是 OFF。P 在艙內沒有被傳送，
     * 收到「量子通道已關閉」（F5-3 起精確比對：恰一則 actionbar、沒有 title，spec §20.9）。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_power", tickLimit = 1100)
    public void passage_ends_with_a_plain_open_door_when_the_source_loses_power(TestContext context) {
        begin(context, "passage_ends_with_a_plain_open_door_when_the_source_loses_power");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, PREPARE_WAIT_TICKS, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                M5GameTestSupport.overlayKeys(origin.connection);
                context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                whenTicks(context, readyAt + 1, 5, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                    witness.put("endedNextTick", ended == readyAt + 1);
                    witness.put("endedByPowerLoss", passageEnded(capture, sid, "(d) 來源艙沒有供電") == 1);
                    witness.put("plainOpenNotClosed", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    witness.put("notTransferred", p.getServerWorld() == world);
                    // F5-3（spec §20.9）：還沒跨過的參與者仍收到「量子通道已關閉」（恰一則 actionbar，沒有 title）。
                    var atEnd = M5GameTestSupport.hud(origin.connection);
                    witness.put("closedMessage", atEnd.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED)) && atEnd.titles().isEmpty());
                    whenTicks(context, ended + 1, 20, () -> ChamberRegistryState.get(server).registry().records().get(chamber).powerState()
                            == dev.quantumchamber.chamber.ChamberPowerState.OFF, off -> {
                        witness.put("offFlow", true);
                        witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid));
                        releaseThenComplete(context, hooks, witness, "斷電結束通道：" + witness, 20, () -> true);
                    }, () -> "終局之後沒有走一般的 OFF 流程：power=" + ChamberRegistryState.get(server).registry().records().get(chamber).powerState(), hooks);
                }, () -> "斷電之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid), hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §15「EXISTING：…另一座艙的 session 也能到達同一個宇宙（基本重逢）」、§10.2 的 EXISTING 再驗）：D1 是原艙 A 上的 DORMANT NEW 收據，參與者 P 穿過通道到宇宙 U，
     * (b) 結束。之後 overworld 遠處的另一座原艙 B（{@code RemoteOrigin}）寫入選中 EXISTING U 的 D2，參與者 Q 由艙外進入：RESOLVE 只重驗（不寫 allocation、discovery）→ PREPARE
     * （把 B 投影到 U 的同座標，第一次寫入）→ PASSAGE_READY 之前再驗一次 EXISTING（通過）→ Q 穿過通道：Q 與 P 在同一個宇宙 U 的世界，Q 在 B 的投影艙門口。catalog 增量 1（只有 U）。
     * F5-3（spec §10.8、§20.8、§20.9）：P 收到 NEW 的到達提示 title，Q 收到 EXISTING 的；Q 在 (b) 結束時沒有收到「量子通道已關閉」，P 沒有收到 D2 的 title。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_existing", tickLimit = 1500)
    public void existing_passage_brings_a_second_chamber_to_the_same_universe(TestContext context) {
        begin(context, "existing_passage_brings_a_second_chamber_to_the_same_universe");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        M5GameTestSupport.RemoteOrigin remote;
        ServerPlayerEntity q;
        ConnectedGameTestPlayer qConnection;
        var owned = new ArrayList<UUID>();
        try {
            remote = M5GameTestSupport.RemoteOrigin.open(context, new BlockPos(30203, 100, 30203), hooks);
            var connection = new ConnectedGameTestPlayer(world);
            hooks.add(connection::close);
            qConnection = connection;
            q = connection.player();
            q.setNoGravity(true);
            moveOutside(q, remote.frame);
            hooks.add(() -> cleanupFixtures(server, owned));
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var far = remote.frame;
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid1, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid1).equals("PASSAGE_READY"), readyAt -> {
                at(context, readyAt + 1, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO));
                at(context, readyAt + 2, hooks, () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                whenTicks(context, readyAt + 3, 10, () -> !journal.flushedRecords().containsKey(sid1), firstEnded -> {
                    witness.put("pInTheUniverse", p.getServerWorld() == universe);
                    // F5-3（spec §20.8）：D1 選的是 NEW，P 收到 NEW 的到達提示。
                    witness.put("newArrivalTitleForP", M5GameTestSupport.hud(origin.connection).titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                    var allocationBefore = allocationEntries(server, id);
                    remote.whenRegistered((registered, chamberB) -> {
                        var second = M5GameTestSupport.existingReceipt(server, remote.authority(chamberB), Map.of(q.getUuid(), true), id);
                        owned.add(second.sessionUuid());
                        journal.put(second); journal.flush(server);
                        var sid2 = second.sessionUuid();
                        at(context, registered + 1, hooks, () -> moveInside(q, far));
                        whenTicks(context, registered + 2, 600, () -> M5GameTestSupport.state(journal, sid2).equals("PASSAGE_READY"), secondReady -> {
                            witness.put("revalidatedWithoutWriting", allocationEntries(server, id) == allocationBefore
                                    && capture.matching(Level.INFO, "目的地已確定", sid2.toString()).size() == 1);
                            witness.put("secondChamberProjected", M5GameTestSupport.projectionMismatches(universe, far, false).isEmpty()
                                    && CollapseAuthorities.get(server).projection(chamberB, id).value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isPresent());
                            at(context, secondReady + 1, hooks, () -> M5GameTestSupport.standInDoorway(q, far, 2.5, 1, 0.7, 0, 0, Vec3d.ZERO));
                            at(context, secondReady + 2, hooks, () -> M5GameTestSupport.standInDoorway(q, far, 2.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                            whenTicks(context, secondReady + 3, 10, () -> !journal.flushedRecords().containsKey(sid2), secondEnded -> {
                                witness.put("reunitedInTheSameUniverse", q.getServerWorld() == universe && p.getServerWorld() == universe);
                                witness.put("qInTheSecondProjectionDoorway", ChamberSpaceCoordinates.localPosition(far, q.getPos())
                                        .squaredDistanceTo(new Vec3d(2.5, 1, 0.3)) < 1e-12);
                                witness.put("bothEndedWhenEveryoneCrossed", passageEnded(capture, sid1, "(b) 全員都已跨過") == 1 && passageEnded(capture, sid2, "(b) 全員都已跨過") == 1);
                                // F5-3（spec §20.8、§20.9）：D2 選的是 EXISTING，Q 收到 EXISTING 的到達提示，(b) 結束時不再收到「量子通道已關閉」；不是 D2 參與者的 P 沒有收到 title。
                                var qHud = M5GameTestSupport.hud(qConnection);
                                witness.put("existingArrivalTitleForQ", qHud.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_EXISTING_KEY)));
                                witness.put("noClosedMessageToQ", !qHud.overlays().contains(CollapseService.MESSAGE_PASSAGE_CLOSED));
                                witness.put("noTitleForPFromTheSecondPassage", M5GameTestSupport.hud(origin.connection).titles().isEmpty());
                                witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid1) && M5GameTestSupport.noWarnOrError(capture, sid2));
                                LOGGER.info("M5_PASSAGE_EXISTING first={} second={}", firstEnded, secondEnded);
                                releaseThenComplete(context, hooks, witness, "EXISTING 的基本重逢：" + witness, 20, () -> true);
                            }, () -> "Q 跨過之後 D2 沒有結束：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
                        }, () -> "D2 沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
                    });
                }, () -> "P 跨過之後 D1 沒有結束：record=" + M5GameTestSupport.state(journal, sid1), hooks);
            }, () -> "D1 沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid1), hooks);
        });
    }

    /**
     * F5-1（spec §10.2「EXISTING 再次通過 §8.1 的重驗」、§8.1、§11.2）：D1（原艙 A 上的 DORMANT NEW）建立宇宙 U、提交 PASSAGE_READY 之後以收尾的終局 API 移除。同一座艙寫入選中
     * EXISTING U 的 D2，P 由艙外進入：RESOLVE 重驗通過（INFO「目的地已確定」）之後、PASSAGE_READY 之前，把 catalog 的 U 改為 DISABLED（test 以 checked API 寫入）→ 準備完成時
     * 的再驗不通過：SEALED(EXISTING_INELIGIBLE)（永久，ERROR 一則），沒有提交 PASSAGE_READY，門改為關閉（門口沒有 entity），目的世界的 ticket 已釋放。收尾時（hooks，失敗路徑也是）
     * catalog 改回 EAGER_ENABLED。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_existing_recheck", tickLimit = 1200)
    public void existing_ready_revalidation_seals_when_the_universe_became_ineligible(TestContext context) {
        begin(context, "existing_ready_revalidation_seals_when_the_universe_became_ineligible");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid1, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var authorities = CollapseAuthorities.get(server);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid1).equals("PASSAGE_READY"), firstReady -> {
                cleanupFixtures(server, List.of(sid1));
                moveOutside(p, frame);
                var second = origin.own(M5GameTestSupport.existingReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()),
                        Map.of(p.getUuid(), true), id));
                var sid2 = second.sessionUuid();
                at(context, firstReady + 1, hooks, () -> moveInside(p, frame));
                whenTicks(context, firstReady + 2, 40, () -> capture.matching(Level.INFO, "目的地已確定", sid2.toString()).size() == 1, revalidated -> {
                    hooks.add(() -> authorities.changeCatalogAvailability(id, DesiredAvailability.EAGER_ENABLED, CollapseAuthorities.WriteDirection.RESTORE));
                    var disabled = authorities.changeCatalogAvailability(id, DesiredAvailability.DISABLED, CollapseAuthorities.WriteDirection.ROLL_FORWARD);
                    witness.put("catalogDisabledAfterTheResolve", disabled.succeeded() && M5GameTestSupport.state(journal, sid2).equals("COLLAPSING"));
                    whenTicks(context, revalidated + 1, 400, () -> M5GameTestSupport.state(journal, sid2).equals("SEALED"), sealedAt -> {
                        witness.put("sealedExistingIneligible", sealedWith(journal, sid2, SealedReason.EXISTING_INELIGIBLE));
                        witness.put("revalidatedAtReady", prepared(capture, sid2).size() == 1 && passageOpened(capture, sid2) == 0);
                        witness.put("onePermanentAdminError", capture.marked(Level.ERROR, CollapseService.LOG_MARKER.getName(), "SEALED(EXISTING_INELIGIBLE)", "需要管理員處理",
                                sid2.toString()).size() == 1);
                        witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid2).isEmpty());
                        at(context, sealedAt + 2, hooks, () -> {
                            witness.put("doorClosed", doorAll(world, frame, false, MembraneState.NONE));
                            releaseThenComplete(context, hooks, witness, "READY 之前的 EXISTING 再驗：" + witness, 40, () -> authorities.catalogRecord(id).value()
                                    .filter(record -> record.desiredAvailability() == DesiredAvailability.EAGER_ENABLED).isPresent());
                        });
                    }, () -> "再驗不通過卻沒有封存：record=" + M5GameTestSupport.state(journal, sid2) + " witness=" + witness, hooks);
                }, () -> "D2 沒有完成 RESOLVE：record=" + M5GameTestSupport.state(journal, sid2), hooks);
            }, () -> "D1 沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid1), hooks);
        });
    }

    /**
     * F5-1（spec §10.3 的 {@code FAILED_ROLLED_BACK}；plan Step 1 F5-1）：P 越過中線，往宇宙 U 的原生移動被拒（testmod {@code SessionTransferFaultMixin}，前 2 次），回滾回原世界
     * 成功：P 仍在原世界、在越過中線的那個位置，收到「通道傳送失敗，你仍在原世界。」，通道仍開著（record 仍是 PASSAGE_READY、Crossed 是空的），WARN 一則。P 回到中線內側再越過：
     * 同一個原因再被拒一次，actionbar 再一次、WARN 仍是一則（依原因去重）。第三次越過：注入用完，傳送成功、Crossed＝[P]、(b) 結束。注入的次數由 fault 記下（2 次往 U，回滾 0 次）。
     * F5-3（spec §20.8、§20.9）：被拒的兩次都沒有 title，第三次才收到 NEW 的到達提示 title；(b) 結束時 P 已跨過，沒有收到「量子通道已關閉」。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_rolled_back", tickLimit = 900)
    public void passage_transfer_rolled_back_keeps_the_passage_open_and_warns_once(TestContext context) {
        begin(context, "passage_transfer_rolled_back_keeps_the_passage_open_and_warns_once");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                var fault = M5GameTestSupport.installNativeMoveFault(context, p.getUuid(), universe.getRegistryKey(), 2, 0, hooks);
                M5GameTestSupport.overlayKeys(origin.connection);
                var crossingAt = ChamberSpaceCoordinates.position(frame, 3.5, 1, 0.3);
                Runnable inside = () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                Runnable outside = () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                sequence(context, readyAt + 1, hooks, List.of(inside, outside, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("firstRejectedAndRolledBack", fault.destinationHits() == 1 && fault.otherHits() == 0 && p.getServerWorld() == world
                            && p.getPos().squaredDistanceTo(crossingAt) < 1e-12);
                    witness.put("passageStillOpen", record != null && record.state() == SessionState.PASSAGE_READY && record.collapse().crossed().isEmpty()
                            && M5GameTestSupport.doorPassable(world, frame));
                    // F5-3：actionbar 與 title 在同一次讀取（spec §20.8：FAILED_ROLLED_BACK 不顯示到達提示）。
                    var first = M5GameTestSupport.hud(origin.connection);
                    witness.put("failureMessage", first.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_TRANSFER_FAILED)));
                    witness.put("noTitleOnTheFirstFailure", first.titles().isEmpty());
                    witness.put("oneWarn", transferWarnings(capture, sid) == 1);
                    inside.run();
                }, outside, () -> {
                    var again = M5GameTestSupport.hud(origin.connection);
                    witness.put("secondRejectedTooButWarnedOnce", fault.destinationHits() == 2 && p.getServerWorld() == world && transferWarnings(capture, sid) == 1
                            && again.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_TRANSFER_FAILED)));
                    witness.put("noTitleOnTheSecondFailure", again.titles().isEmpty());
                    inside.run();
                }, outside));
                whenTicks(context, readyAt + 8, 10, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                    witness.put("thirdCrossed", p.getServerWorld() == universe && fault.destinationHits() == 2 && fault.hits() == 2 && crossedInfos(capture, sid) == 1);
                    // F5-3（spec §20.8、§20.9）：第三次（成功）才有 NEW 的到達提示；(b) 結束時已跨過，不再收到「量子通道已關閉」。
                    var third = M5GameTestSupport.hud(origin.connection);
                    witness.put("arrivalTitleOnTheThirdCrossing", third.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                    witness.put("noClosedMessageAfterTheThirdCrossing", third.overlays().isEmpty());
                    witness.put("endedWhenEveryoneCrossed", passageEnded(capture, sid, "(b) 全員都已跨過") == 1);
                    witness.put("stillOneWarnAndNoError", transferWarnings(capture, sid) == 1 && capture.matching(Level.WARN, sid.toString()).size() == 1
                            && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                    releaseThenComplete(context, hooks, witness, "通道傳送失敗並回滾：" + witness, 20, () -> true);
                }, () -> "第三次越過之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " hits=" + fault.hits() + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.3 的 {@code FAILED_RECOVERY_REQUIRED}、§5 的 PASSAGE_READY→SEALED(TRR)、§11.2；plan Review Focus 1）：參與者 P1、P2。P1 先穿過通道（Crossed＝[P1]）。P2 越過中線時，
     * 往 U 的原生移動與回滾都被拒（testmod {@code SessionTransferFaultMixin}，各 1 次）→ {@code FAILED_RECOVERY_REQUIRED}。第一次的 SEALED(TRR) 寫入在 journal 的 put 就被拒
     * （testmod {@code SessionJournalPutFaultMixin}，1 次；防禦性的被拒路徑）：通道在記憶體內關閉（record 仍是 PASSAGE_READY，P2 再越過中線也不傳送；原艙門由 passable 改為一般開門，
     * F5-1 fix round 1，spec review S-m3），ERROR 一則；這段期間門照一般門的邏輯（F5-2，spec re-review n-2）：P2 在艙內以 Controller 關門、再開門都照一般門切換（Controller 的
     * 訊息是 {@code controller_passage}），之後回到門口。per-session 退避之後重試寫入成功：
     * <ul>
     * <li>record 是 SEALED(TRANSFER_RECOVERY_REQUIRED)、RecoveryRequired＝[P2]、Crossed＝[P1]、opened／expires 與 participants 都保留，沒有終局（record 還在）；</li>
     * <li>ERROR 帶著 transfer result 的移動與回滾證據（{@code move=}、{@code rollback=}），「需要管理員處理」；</li>
     * <li>門依 §11.2：P2 在門口，所以改為一般開門（不關門）；目的世界的 ticket 已釋放；兩人都收到「量子通道無法開啟，已封存。」；</li>
     * <li>之後 P2 再越過中線：沒有任何原生移動（不再自動移動他，也不重試），也不重試封存。</li>
     * </ul>
     * 比較器（F5-2，spec §10.7、§15「比較器 11」）：通道開著時 15；寫入被拒、通道在記憶體內關閉的期間仍是 15（presence 仍是 PASSAGE，已知限制）；SEALED(TRR) 之後 11。F4a M-3：
     * 進入 SEALED 的探針（{@code installSealProbe}）記到 {@code sealCommitted} 的開頭是 15、RETURN 是 11（P2 在門口，關門是 UNCHANGED，不經門服務的刷新，所以 11 只來自
     * 「進入 SEALED 之後刷新 Controller」）。
     * SEALED(TRR) 是永久的 recovery authority，收尾時留在 journal（{@code M5GameTestSupport.removeFixture}）。catalog 增量 1。
     * F5-3（spec §20.8）：P1 的跨越有 NEW 的到達提示 title；P2 的 {@code FAILED_RECOVERY_REQUIRED} 與通道在記憶體內關閉期間的再越過都沒有 title。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_recovery", tickLimit = 1200)
    public void passage_transfer_recovery_required_seals_and_keeps_the_crossed_receipt(TestContext context) {
        begin(context, "passage_transfer_recovery_required_seals_and_keeps_the_crossed_receipt");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p1 = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(world);
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var p2 = second.player();
        collapsedToUniverse(context, origin, hooks, List.of(p2.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(p2, frame);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                var ready = journal.flushedRecords().get(sid);
                // F5-2（spec §10.7）：通道開著（已供電、enabled）時比較器 15。
                witness.put("comparatorFifteenWhileReady", controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                at(context, readyAt + 1, hooks, () -> M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.7, 0, 0, Vec3d.ZERO));
                at(context, readyAt + 2, hooks, () -> M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                at(context, readyAt + 3, hooks, () -> {
                    witness.put("p1Crossed", p1.getServerWorld() == universe && journal.flushedRecords().get(sid).collapse().crossed().equals(List.of(p1.getUuid())));
                    // F5-3：P1 的跨越成功，有 NEW 的到達提示（對照下方 P2 的 FAILED_RECOVERY_REQUIRED 沒有）。這次讀取同時清掉之前的封包。
                    witness.put("arrivalTitleForP1", M5GameTestSupport.hud(origin.connection).titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                    M5GameTestSupport.overlayKeys(second);
                });
                M5TestHooks.NativeMoveFault[] fault = {null};
                M5TestHooks.JournalPutFault[] reject = {null};
                M5TestHooks.SealProbe[] probe = {null};
                at(context, readyAt + 4, hooks, () -> {
                    fault[0] = M5GameTestSupport.installNativeMoveFault(context, p2.getUuid(), universe.getRegistryKey(), 1, 1, hooks);
                    reject[0] = M5GameTestSupport.installJournalPutFault(context, sid, SessionState.SEALED, 1, hooks);
                    // F5-2（F4a M-3）：記下 sealCommitted 開頭與 RETURN 那一刻的比較器。
                    probe[0] = M5GameTestSupport.installSealProbe(context, sid, world, frame.controllerPos(), hooks);
                    M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                });
                at(context, readyAt + 5, hooks, () -> M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                at(context, readyAt + 6, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("recoveryRequiredWithBothMovesRefused", fault[0].destinationHits() == 1 && fault[0].otherHits() == 1 && p2.getServerWorld() == world);
                    // F5-3（spec §20.8）：FAILED_RECOVERY_REQUIRED 不顯示到達提示。
                    witness.put("noTitleForTheRecoveryRequired", M5GameTestSupport.hud(second).titles().isEmpty());
                    witness.put("sealWriteRejectedOnce", reject[0].hits() == 1 && record.state() == SessionState.PASSAGE_READY && record.equals(journal.records().get(sid)));
                    witness.put("oneRejectedError", capture.marked(Level.ERROR, CollapseService.LOG_MARKER.getName(), "的寫入被拒", "需要管理員處理", sid.toString()).size() == 1);
                    // fix round 1（spec review S-m3）：通道在記憶體內關閉，原艙門不再是 passable，改為一般開門（比照 PREPARE 中寫入被拒時）。
                    witness.put("plainOpenWhileTheSealIsRejected", doorAll(world, frame, true, MembraneState.NONE));
                    // F5-2（補充第 4 點，已知限制）：journal 沒有改變，collapse view 仍是 PASSAGE_READY，所以 presence 仍是 PASSAGE、比較器 15（短暫誤報，到寫入成功為止）。
                    witness.put("comparatorFifteenWhileClosedInMemory", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.PASSAGE
                            && controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                    // 在記憶體內關閉：P2 再越過中線也不傳送。
                    M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                });
                at(context, readyAt + 7, hooks, () -> M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                at(context, readyAt + 8, hooks, () -> witness.put("closedInMemoryWhileRejected", fault[0].hits() == 2 && p2.getServerWorld() == world
                        && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY")));
                // F5-2（spec re-review n-2）：通道在記憶體內關閉的期間，門已是一般開門，照一般門的邏輯（比照 PREPARE 被拒時 PREPARE 不擁有門）：參與者 P2 在艙內以 Controller
                // 關門、再開門都照一般門切換，record 不變；Controller 的訊息是 PASSAGE 的（補充第 3 點的顯示：presence 仍是 PASSAGE）。
                at(context, readyAt + 9, hooks, () -> {
                    moveInside(p2, frame);
                    // F5-3：通道在記憶體內關閉時再越過中線（readyAt+7）也沒有傳送、沒有 title。這次讀取同時清掉之前的封包。
                    witness.put("noTitleWhileClosedInMemory", M5GameTestSupport.hud(second).titles().isEmpty());
                    witness.put("participantClosesThePlainDoorWhileClosedInMemory", interact(world, frame.controllerPos(), p2).isAccepted()
                            && doorAll(world, frame, false, MembraneState.NONE) && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"));
                    witness.put("controllerPassageMessage", M5GameTestSupport.overlayKeys(second).equals(List.of(CONTROLLER_PASSAGE_KEY)));
                });
                at(context, readyAt + 10, hooks, () -> {
                    witness.put("participantReopensThePlainDoorWhileClosedInMemory", interact(world, frame.controllerPos(), p2).isAccepted()
                            && doorAll(world, frame, true, MembraneState.NONE) && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"));
                    // 放回門口（中線外側）：進入 SEALED 時門口有人，關門改為一般開門（plainOpenWithP2InTheDoorway）。
                    M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                });
                whenTicks(context, readyAt + 11, 260, () -> M5GameTestSupport.state(journal, sid).equals("SEALED"), sealedAt -> {
                    var sealed = journal.flushedRecords().get(sid);
                    witness.put("sealedTransferRecoveryRequired", sealedWith(journal, sid, SealedReason.TRANSFER_RECOVERY_REQUIRED)
                            && sealed.collapse().recoveryRequired().equals(List.of(p2.getUuid())));
                    witness.put("crossedOpenedExpiresAndParticipantsKept", sealed.collapse().crossed().equals(List.of(p1.getUuid()))
                            && sealed.collapse().passageOpenedAtGameTime() == ready.collapse().passageOpenedAtGameTime()
                            && sealed.collapse().passageExpiresAtGameTime() == ready.collapse().passageExpiresAtGameTime()
                            && sealed.participants().equals(ready.participants()) && sealed.candidateSelection().equals(ready.candidateSelection()));
                    witness.put("retriedAfterTheBackoff", sealedAt - readyAt >= 200);
                    witness.put("noTerminal", journal.flushedRecords().containsKey(sid) && passageEnded(capture, sid, "量子通道已結束") == 0);
                    var errors = capture.marked(Level.ERROR, CollapseService.LOG_MARKER.getName(), "塌縮進入 SEALED(TRANSFER_RECOVERY_REQUIRED)", "需要管理員處理", sid.toString());
                    witness.put("oneAdminErrorWithTheEvidence", errors.size() == 1 && errors.getFirst().message().contains("FAILED_RECOVERY_REQUIRED")
                            && errors.getFirst().message().contains("move=") && errors.getFirst().message().contains("rollback="));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("sealedMessageToBoth", M5GameTestSupport.overlayKeys(origin.connection).contains(CollapseService.MESSAGE_SEALED)
                            && M5GameTestSupport.overlayKeys(second).contains(CollapseService.MESSAGE_SEALED));
                    // F5-2（補充第 4 點、spec §15「比較器 11」）：SEALED(TRR) 的 presence 是 COLLAPSE，比較器 11。
                    witness.put("comparatorElevenAfterTheSeal", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.COLLAPSE
                            && controllerSignal(world, frame, ChamberState.ARMED, 11));
                    // F5-2（F4a M-3）：SEALED 前後的比較器不同（15 → 11），而且 sealCommitted 自己刷新了 Controller：RETURN 那一刻已是 11。進入 SEALED 時 P2 在門口、門已是一般開門，
                    // 關門是 UNCHANGED（不經門服務的刷新），所以拿掉「進入 SEALED 之後刷新 Controller」時 RETURN 仍是 15。
                    var seen = probe[0];
                    witness.put("sealProbeHitOnce", seen.hits() == 1 && seen.before().size() == 1);
                    witness.put("comparatorFifteenWhenTheSealCommitted", seen.before().size() == 1 && seen.before().getFirst().comparator() == 15
                            && seen.before().getFirst().state().equals("PASSAGE"));
                    witness.put("sealCommittedRefreshedTheControllerToEleven", seen.after().size() == 1 && seen.after().getFirst().comparator() == 11
                            && seen.after().getFirst().state().equals("ARMED"));
                    at(context, sealedAt + 2, hooks, () -> {
                        witness.put("plainOpenWithP2InTheDoorway", doorAll(world, frame, true, MembraneState.NONE));
                        M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                    });
                    at(context, sealedAt + 3, hooks, () -> M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                    at(context, sealedAt + 5, hooks, () -> {
                        witness.put("neverMovedAgain", fault[0].hits() == 2 && p2.getServerWorld() == world && sealedWith(journal, sid, SealedReason.TRANSFER_RECOVERY_REQUIRED));
                        LOGGER.info("M5_PASSAGE_RECOVERY ready={} sealed={} sealProbe before={} after={} error={}", readyAt, sealedAt, seen.before(), seen.after(),
                                errors.isEmpty() ? "" : errors.getFirst().message());
                        releaseThenComplete(context, hooks, witness, "通道傳送無法回滾：" + witness, 20, () -> true);
                    });
                }, () -> "退避之後沒有寫入 SEALED(TRR)：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.3 :571、§6 的 collapse view；plan Review Focus 1 的第二半）：參與者 P1、P2，P2 的原生移動與回滾都被拒（各 1 次）→ {@code FAILED_RECOVERY_REQUIRED}；SEALED(TRR)
     * 的 journal 寫出在 flush 開頭受控失敗（roll-forward）：寫入本身那一次，以及下一個 tick recovery 的 RETRY_FLUSH（recovery 的退避從它自己的寫出失敗才開始，之後 200 ticks 才再試）。
     * flushed 仍是 PASSAGE_READY，collapse view 已是 SEALED，通道立即關閉——presence 是 COLLAPSE、P1 越過中線也不傳送、門依 §11.2（P2 在門口：一般開門）、目的世界的 ticket 已釋放、
     * 沒有終局。之後另一個 session 的 checked 寫入（suspend 的 fixture，put＋flush）的 flush 帶著這筆 SEALED，flushed 變成 SEALED(TRR)（早於 recovery 的下一次 RETRY_FLUSH）。以
     * {@code afterAutosaveClear} 的窗口確認期間沒有繞過探針的寫出。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_recovery_pending", tickLimit = 1500)
    public void passage_recovery_seal_write_failure_closes_the_passage_until_another_flush(TestContext context) {
        begin(context, "passage_recovery_seal_write_failure_closes_the_passage_until_another_flush");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p1 = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(world);
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var p2 = second.player();
        collapsedToUniverse(context, origin, hooks, List.of(p2.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(p2, frame);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                M5GameTestSupport.afterAutosaveClear(context, readyAt + 1, "passage_recovery_pending", 60, (start, window) -> {
                    var flush = M5GameTestSupport.installFlushProbe(context, new M5GameTestSupport.FlushFault(server, "F5-1 GameTest 受控：SEALED(TRR) 的 journal 寫出失敗", 2,
                            state -> Optional.ofNullable(state.records().get(sid)).filter(record -> record.state() == SessionState.SEALED).isPresent()
                                    && Optional.ofNullable(state.flushedRecords().get(sid)).filter(record -> record.state() == SessionState.PASSAGE_READY).isPresent()), hooks);
                    var fault = M5GameTestSupport.installNativeMoveFault(context, p2.getUuid(), universe.getRegistryKey(), 1, 1, hooks);
                    M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                    at(context, start + 1, hooks, () -> M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                    at(context, start + 2, hooks, () -> {
                        var flushed = journal.flushedRecords().get(sid);
                        var view = journal.collapseView(sid).orElseThrow();
                        witness.put("recoveryRequired", fault.destinationHits() == 1 && fault.otherHits() == 1);
                        witness.put("sealWriteFailed", flush.failures() >= 1 && flushed.state() == SessionState.PASSAGE_READY && journal.rollForwardPending(sid));
                        witness.put("collapseViewSealed", view.state() == SessionState.SEALED && view.collapse().sealedReason().equals(Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED))
                                && view.collapse().recoveryRequired().equals(List.of(p2.getUuid())));
                        witness.put("presenceCollapse", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.COLLAPSE);
                        witness.put("destinationTicketReleasedAtOnce", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                        M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                    });
                    at(context, start + 3, hooks, () -> M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                    at(context, start + 4, hooks, () -> {
                        witness.put("p1NotTransferred", p1.getServerWorld() == world && fault.hits() == 2 && crossedInfos(capture, sid) == 0);
                        witness.put("plainOpenWithP2InTheDoorway", doorAll(world, frame, true, MembraneState.NONE));
                        witness.put("noTerminal", journal.flushedRecords().containsKey(sid) && passageEnded(capture, sid, "量子通道已結束") == 0);
                        witness.put("stillPendingBeforeTheOtherFlush", journal.rollForwardPending(sid) && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY")
                                && flush.failures() == 2);
                        // 另一個 session 的 checked 寫入：它的 flush 帶著這筆 SEALED。
                        var other = M5GameTestSupport.collapseFixture(server, M4CandidateTestAccess.origin(UUID.randomUUID(), frame.controllerPos().add(97, 0, 0)),
                                Map.of(UUID.randomUUID(), true), List.of(), true, SessionState.COLLAPSING, true);
                        origin.owned.add(other.sessionUuid());
                        witness.put("writtenByTheOtherFlush", sealedWith(journal, sid, SealedReason.TRANSFER_RECOVERY_REQUIRED) && !journal.rollForwardPending(sid)
                                && flush.retries().size() >= 1);
                        var writes = window.journalWrites(server, flush.ticks(), List.of(sid, other.sessionUuid()));
                        witness.put("onlyProbedJournalWrites", writes.onlyProbed());
                        LOGGER.info("M5_PASSAGE_RECOVERY_PENDING start={} flushes={} problems={}", start, flush.ticks(), writes.problems());
                        releaseThenComplete(context, hooks, witness, "SEALED(TRR) 的寫出失敗：" + witness + " problems=" + writes.problems(), 20, () -> true);
                    });
                }, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §7 開頭「Crossed 落盤失敗時不傳送、不終局、不評估結束條件、拒絕關門」、§6 的 collapse view、§10.6 (c)；plan Step 1 F5-1 的「Crossed 落盤失敗」）：參與者 P1、P2。
     * P1 穿過通道之後，加入 Crossed 的 journal 寫出在 flush 開頭受控失敗（之後 recovery 的三次 RETRY_FLUSH 也失敗：下一個 tick、退避 200、400 ticks 之後；共 4 次）：
     * <ul>
     * <li>P1 已在 U、collapse view 的 Crossed＝[P1]（flushed 仍是空的），WARN「寫入 Crossed」一則；P1 照常收到 NEW 的到達提示 title（F5-3，spec §20.9 邊界情況）；</li>
     * <li>未確認期間：P2 越過中線不傳送；P2 關門被拒、門仍是 passable；game time 過了 expires 也沒有終局（結束條件不評估，record 仍是 PASSAGE_READY）；</li>
     * <li>第 5 次寫出（RETRY_FLUSH，800 ticks 的退避之後）成功：Crossed 確認，這時已到期，依 (c) 結束（INFO (c) 一則），門改為一般開門。只有還沒跨過的 P2 收到
     *     「量子通道已關閉」，已在 Crossed 中的 P1 沒有收到（F5-3，spec §20.9）。</li>
     * </ul>
     * 通道的 60 秒從 PASSAGE_READY 起算，所以先以 {@code holdReady} 暫緩提交：等暫緩真的發生（{@code readyHeldInPrepare}，{@code hits()>0}；準備完成之後才會呼叫提交）之後才等
     * {@code afterAutosaveClear} 的窗口，窗口開始時才放行；窗口內確認沒有繞過探針的寫出（原生 autosave 會把未確認的 Crossed 直接寫出）。
     * <p>有界等待的總長度（F5-1 fix round 1，quality Q-m5；README「新增 M5 GameTest」的前提）：{@code resolved}（{@code whenRegistered} 的 3＋40 與 RESOLVE 的 2＋20，最多 65）
     * ＋暫緩 1＋800＋窗口 1＋1802（{@code afterAutosaveClear} 最多等 window＋2）＋提交 1＋40＋6＋到期 1300＋1＋收斂與結束 400＋完成 20＝4437，小於 tickLimit 5000。F5-1 的
     * run-all 實測：到期的判斷約在提交之後 1206 ticks、收斂（第 5 次寫出）約 1403 ticks。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_crossed_pending", tickLimit = 5000)
    public void crossed_write_failure_pauses_the_passage_until_it_converges_then_expires(TestContext context) {
        begin(context, "crossed_write_failure_pauses_the_passage_until_it_converges_then_expires");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p1 = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(world);
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var p2 = second.player();
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, List.of(p2.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(p2, frame);
            // 通道的 60 秒從 PASSAGE_READY 起算，而等原生 autosave 的窗口最久要等一整個窗口：先暫緩提交；暫緩真的發生（準備完成）之後才等窗口，窗口開始之後才放行
            // （F5-1 fix round 1，quality Q-m5：原本的窗口可能在準備完成之前就開始，暫緩沒有發生，hits() 是 0）。
            var hold = M5GameTestSupport.holdReady(context, sid, hooks);
            whenTicks(context, resolved + 1, 800, () -> hold.hits() > 0, held -> {
                witness.put("readyHeldInPrepare", hold.hits() > 0 && M5GameTestSupport.state(journal, sid).equals("COLLAPSING"));
                M5GameTestSupport.afterAutosaveClear(context, held + 1, "passage_crossed_pending", 1800, (start, window) -> {
                    var flush = M5GameTestSupport.installFlushProbe(context, new M5GameTestSupport.FlushFault(server, "F5-1 GameTest 受控：Crossed 的 journal 寫出失敗", 4,
                            state -> Optional.ofNullable(state.records().get(sid)).filter(record -> record.collapse().crossed().contains(p1.getUuid())).isPresent()
                                    && Optional.ofNullable(state.flushedRecords().get(sid)).filter(record -> record.collapse().crossed().isEmpty()).isPresent()), hooks);
                    hold.release();
                    whenTicks(context, start + 1, 40, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                        long expires = journal.flushedRecords().get(sid).collapse().passageExpiresAtGameTime();
                        at(context, readyAt + 1, hooks, () -> M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.7, 0, 0, Vec3d.ZERO));
                        at(context, readyAt + 2, hooks, () -> M5GameTestSupport.standInDoorway(p1, frame, 2.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                        at(context, readyAt + 3, hooks, () -> {
                            var view = journal.collapseView(sid).orElseThrow();
                            witness.put("p1Crossed", p1.getServerWorld() == universe && crossedInfos(capture, sid) == 1);
                            witness.put("crossedPendingInTheCollapseView", journal.rollForwardPending(sid) && view.collapse().crossed().equals(List.of(p1.getUuid()))
                                    && journal.flushedRecords().get(sid).collapse().crossed().isEmpty() && flush.failures() == 1);
                            witness.put("oneCrossedWriteWarn", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "寫入 Crossed", sid.toString()).size() == 1);
                            // F5-3（spec §20.9 邊界情況）：Crossed 的寫入沒有確認，P1 仍已在目的世界，照常收到 NEW 的到達提示。
                            witness.put("arrivalTitleWhileTheCrossedWriteIsPending", M5GameTestSupport.hud(origin.connection).titles()
                                    .equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                            M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                        });
                        at(context, readyAt + 4, hooks, () -> M5GameTestSupport.standInDoorway(p2, frame, 4.5, 1, 0.3, 0, 0, Vec3d.ZERO));
                        at(context, readyAt + 5, hooks, () -> {
                            witness.put("p2NotTransferredWhilePending", p2.getServerWorld() == world && crossedInfos(capture, sid) == 1);
                            moveInside(p2, frame);
                            witness.put("p2CloseRefusedDoorUnchanged", !interact(world, doorCell, p2).isAccepted() && M5GameTestSupport.doorPassable(world, frame)
                                    && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"));
                        });
                        whenTicks(context, readyAt + 6, 1300, () -> server.getOverworld().getTime() > expires + 5, expired -> {
                            witness.put("notEndedAfterTheExpiryWhilePending", journal.rollForwardPending(sid) && M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY")
                                    && passageEnded(capture, sid, "量子通道已結束") == 0 && flush.failures() == 4);
                            // F5-3：清掉兩人之前的封包，結束時只看 (c) 的訊息。
                            M5GameTestSupport.hud(origin.connection); M5GameTestSupport.hud(second);
                            whenTicks(context, expired + 1, 400, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                                witness.put("convergedOnTheFifthWrite", flush.failures() == 4 && flush.retries().size() >= 1);
                                witness.put("thenEndedByExpiry", passageEnded(capture, sid, "(c) game time 已到 PassageExpiresAtGameTime") == 1);
                                // F5-3（spec §20.9）：(c) 結束時 P1 已在 Crossed 中（第 5 次寫出已確認），不再收到「量子通道已關閉」；還沒跨過的 P2 收到。
                                var p1AtEnd = M5GameTestSupport.hud(origin.connection);
                                var p2AtEnd = M5GameTestSupport.hud(second);
                                witness.put("closedMessageOnlyToTheOneWhoStayed", p1AtEnd.overlays().isEmpty()
                                        && p2AtEnd.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED)));
                                witness.put("plainOpenAfterTheEnd", doorAll(world, frame, true, MembraneState.NONE));
                                var writes = window.journalWrites(server, flush.ticks(), List.of(sid));
                                witness.put("onlyProbedJournalWrites", writes.onlyProbed());
                                LOGGER.info("M5_PASSAGE_CROSSED_PENDING held={} start={} expires={} ended={} flushes={} problems={}", held, start, expires, ended, flush.ticks(),
                                        writes.problems());
                                releaseThenComplete(context, hooks, witness, "Crossed 的寫出失敗：" + witness + " problems=" + writes.problems(), 20, () -> true);
                            }, () -> "收斂之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " flushes=" + flush.ticks() + " witness=" + witness, hooks);
                        }, () -> "沒有等到 expires：time=" + server.getOverworld().getTime() + " expires=" + expires, hooks);
                    }, () -> "放行之後沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
                }, hooks);
            }, () -> "準備完成之前就逾時（暫緩沒有發生）：record=" + M5GameTestSupport.state(journal, sid) + " hits=" + hold.hits(), hooks);
        });
    }

    /**
     * F5-1（spec §10.4、§15「危險目的地：在水、岩漿、石頭地形的 fixture 中，以及在門口跳躍時，照樣抵達，不被判為失敗」）：PASSAGE_READY 之後，在宇宙 U 的投影艙門外一格
     * （local z=−1）放石頭、水、岩漿；四位（創造模式、不受重力的）參與者在同一個 tick 越過中線：腳在 local z=0.2 時 bbox 伸進門外那一格，所以 M3 的安全落點會因碰撞（石頭）或流體
     * （水、岩漿）拒絕；第四位在半空（腳在 local y=1.42，M3 的地板檢查會拒絕）。passage 的政策不看這些：四位都抵達 U 的同 XYZ，Crossed 有四位，(b) 結束，沒有任何傳送失敗的
     * WARN。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_hazards", tickLimit = 900)
    public void passage_crossings_arrive_in_stone_water_lava_and_mid_jump(TestContext context) {
        begin(context, "passage_crossings_arrive_in_stone_water_lava_and_mid_jump");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var players = new ArrayList<ServerPlayerEntity>();
        players.add(origin.player());
        try {
            for (int i = 0; i < 3; i++) {
                var connection = new ConnectedGameTestPlayer(world);
                hooks.add(connection::close);
                connection.player().setNoGravity(true);
                moveOutside(connection.player(), frame);
                players.add(connection.player());
            }
            for (var player : players) player.changeGameMode(GameMode.CREATIVE);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        double[] columns = {1.5, 2.5, 5.5, 3.5};
        double[] heights = {1, 1, 1, 1.42};
        collapsedToUniverse(context, origin, hooks, players.subList(1, 4).stream().map(ServerPlayerEntity::getUuid).toList(), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            for (var player : players.subList(1, 4)) moveInside(player, frame);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                ChamberProtectionService.get().authorizedMutation(() -> {
                    universe.setBlockState(outside(frame, 1, 1, -1), Blocks.STONE.getDefaultState());
                    universe.setBlockState(outside(frame, 2, 1, -1), Blocks.WATER.getDefaultState());
                    universe.setBlockState(outside(frame, 5, 1, -1), Blocks.LAVA.getDefaultState());
                });
                witness.put("hazardsPlaced", universe.getBlockState(outside(frame, 1, 1, -1)).isOf(Blocks.STONE)
                        && universe.getFluidState(outside(frame, 2, 1, -1)).isIn(net.minecraft.registry.tag.FluidTags.WATER)
                        && universe.getFluidState(outside(frame, 5, 1, -1)).isIn(net.minecraft.registry.tag.FluidTags.LAVA));
                at(context, readyAt + 1, hooks, () -> {
                    for (int i = 0; i < 4; i++) M5GameTestSupport.standInDoorway(players.get(i), frame, columns[i], heights[i], 0.7, 0, 0, Vec3d.ZERO);
                });
                at(context, readyAt + 2, hooks, () -> {
                    for (int i = 0; i < 4; i++) M5GameTestSupport.standInDoorway(players.get(i), frame, columns[i], heights[i], 0.2, 0, 0, Vec3d.ZERO);
                });
                at(context, readyAt + 3, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    var names = List.of("stone", "water", "lava", "midJump");
                    for (int i = 0; i < 4; i++) {
                        var player = players.get(i);
                        var expected = ChamberSpaceCoordinates.position(frame, columns[i], heights[i], 0.2);
                        witness.put(names.get(i) + ".arrived", player.getServerWorld() == universe && player.getPos().squaredDistanceTo(expected) < 1e-12);
                    }
                    witness.put("boxesReachTheHazards", !universe.isSpaceEmpty(players.get(0), players.get(0).getBoundingBox())
                            && universe.containsFluid(players.get(1).getBoundingBox()) && universe.containsFluid(players.get(2).getBoundingBox()));
                    witness.put("allFourCrossed", record != null && record.collapse().crossed().size() == 4);
                });
                whenTicks(context, readyAt + 4, 10, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                    witness.put("endedWhenEveryoneCrossed", passageEnded(capture, sid, "(b) 全員都已跨過") == 1);
                    witness.put("noTransferWarnOrError", transferWarnings(capture, sid) == 0 && M5GameTestSupport.noWarnOrError(capture, sid));
                    releaseThenComplete(context, hooks, witness, "危險的目的地：" + witness, 20, () -> true);
                }, () -> "四位都跨過之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1（spec §10.2 的提交是 restore 類、§6「journal 寫入失敗時的 session 行為」、§11.2 的 per-session 退避）：準備完成時提交 PASSAGE_READY 的 journal 寫出在 flush 開頭受控失敗
     * 一次：record 留在 COLLAPSING（current 還原、flushed 不變），WARN「提交 PASSAGE_READY」一則，PREPARE 暫停、原艙門由 sealed 量子膜改為一般開門（INFO 一則）；退避期（200 ticks）
     * 之內不重試、門維持一般開門。探針在 RESOLVE 之後、PREPARE 之前就裝上；它只計「current 是 PASSAGE_READY、flushed 是 COLLAPSING」的 flush，這只會出現在提交本身的 flush 裡
     * （restore 類寫入失敗之後 current 已還原），所以原生 autosave 不會讓計數誤判，不需要 {@code afterAutosaveClear} 的窗口。
     * <p>F5-1 fix round 1（spec review S-m2、quality Q-m2；§6 :260、§7.3）：
     * <ul>
     * <li>退避期內 PREPARE 不擁有原艙門：參與者 P 以 bulkhead 關門、再開門，都照一般門的邏輯完成（不被拒），record 仍是 COLLAPSING。</li>
     * <li>退避期快結束時以 {@code holdReady} 讓之後的提交暫緩（重試的結果不是提交，等同前置條件還沒成立）：退避期過後 PREPARE 恢復，原艙門照每個 PREPARE tick 的條件改回 sealed
     *     量子膜（修正之前會一直維持一般開門），提交仍沒有發生。放行之後重試這筆提交：PASSAGE_READY、門改為 passable。</li>
     * </ul>
     * catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_ready_fault", tickLimit = 2000)
    public void ready_write_failure_restores_backs_off_and_opens_the_door_plainly(TestContext context) {
        begin(context, "ready_write_failure_restores_backs_off_and_opens_the_door_plainly");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var flush = M5GameTestSupport.installFlushProbe(context, new M5GameTestSupport.FlushFault(server, "F5-1 GameTest 受控：提交 PASSAGE_READY 的 journal 寫出失敗", 1,
                    state -> Optional.ofNullable(state.records().get(sid)).filter(record -> record.state() == SessionState.PASSAGE_READY).isPresent()
                            && Optional.ofNullable(state.flushedRecords().get(sid)).filter(record -> record.state() == SessionState.COLLAPSING).isPresent()), hooks);
            whenTicks(context, resolved + 1, 500, () -> flush.failures() == 1, failedAt -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("readyRestored", record.state() == SessionState.COLLAPSING && record.equals(journal.records().get(sid)) && passageOpened(capture, sid) == 0);
                    witness.put("oneReadyWriteWarn", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "提交 PASSAGE_READY", sid.toString()).size() == 1);
                    witness.put("pauseInfo", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "提交 PASSAGE_READY 沒有完成，PREPARE 暫停", sid.toString()).size() == 1);
                    witness.put("plainOpenWhilePaused", doorAll(world, frame, true, MembraneState.NONE));
                    // fix round 1（S-m2 (a)）：退避期內 PREPARE 不擁有原艙門，參與者照一般門的邏輯開關。
                    at(context, failedAt + 10, hooks, () -> witness.put("participantClosesThePlainDoorWhilePaused", interact(world, doorCell, p).isAccepted()
                            && doorAll(world, frame, false, MembraneState.NONE)));
                    at(context, failedAt + 11, hooks, () -> witness.put("participantReopensThePlainDoorWhilePaused", interact(world, doorCell, p).isAccepted()
                            && doorAll(world, frame, true, MembraneState.NONE) && M5GameTestSupport.state(journal, sid).equals("COLLAPSING")));
                    M5TestHooks.ReadyHold[] hold = {null};
                    at(context, failedAt + 150, hooks, () -> {
                        witness.put("noRetryWithinTheBackoff", flush.ticks().size() == 1
                                && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && doorAll(world, frame, true, MembraneState.NONE));
                        // fix round 1（S-m2 (b)）：退避期過後的重試不是提交（暫緩，等同前置條件還沒成立）。
                        hold[0] = M5GameTestSupport.holdReady(context, sid, hooks);
                    });
                    at(context, failedAt + 215, hooks, () -> {
                        witness.put("prepareDoorResumesAfterTheBackoff", M5GameTestSupport.doorSealed(world, frame) && hold[0].hits() > 0
                                && M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && flush.ticks().size() == 1);
                        witness.put("oneResumeInfo", capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退避期已過，PREPARE 恢復", sid.toString()).size() == 1);
                        hold[0].release();
                    });
                    whenTicks(context, failedAt + 216, 300, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                        witness.put("retriedAfterTheBackoff", readyAt - failedAt >= 195 && flush.ticks().size() == 2 && passageOpened(capture, sid) == 1);
                        witness.put("passableAfterTheRetry", M5GameTestSupport.doorPassable(world, frame));
                        LOGGER.info("M5_PASSAGE_READY_FAULT failed={} ready={} flushes={}", failedAt, readyAt, flush.ticks());
                        releaseThenComplete(context, hooks, witness, "提交 PASSAGE_READY 的寫出失敗：" + witness, 20, () -> true);
                    }, () -> "退避之後沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid) + " flushes=" + flush.ticks() + " witness=" + witness, hooks);
                }, () -> "提交 PASSAGE_READY 的寫出沒有被注入失敗：record=" + M5GameTestSupport.state(journal, sid) + " flushes=" + flush.ticks(), hooks);
        });
    }

    /**
     * F5-2 Step 0（F5-1 fix round 1 quality re-review R-m1；spec §6 :260、§7.3、§11.2 的 per-session 退避）：提交 PASSAGE_READY 的 journal 寫出受控失敗一次（同 T-READY-FAULT），
     * PREPARE 在退避期內暫停、原艙門是一般開門。退避期內斷電：閘門失效、退回 WAITING（這一段的 Prepare 丟棄）；恢復供電之後 P 由艙外再進入，回到 PREPARE（「進入 PREPARE」第 2 則）。
     * 這時仍在同一段 per-session 退避期內：
     * <ul>
     * <li>原艙門維持一般開門，不套用 sealed 量子膜（修正之前暫停綁在 Prepare 上，新的 Prepare 不暫停，門改為 sealed）；</li>
     * <li>P 以 bulkhead 關門、再開門，都照一般門的邏輯完成，record 仍是 COLLAPSING（修正之前 PREPARE 擁有門，P 關門要終局，卻被退避擋下而拒絕：困在 sealed 量子膜後面）。</li>
     * </ul>
     * 退避期過後 PREPARE 恢復（INFO「退避期已過，PREPARE 恢復」一則）並重試提交：PASSAGE_READY、門改為 passable。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_ready_fault_reenter", tickLimit = 1500)
    public void ready_write_failure_keeps_the_door_plain_when_the_prepare_reenters_within_the_backoff(TestContext context) {
        begin(context, "ready_write_failure_keeps_the_door_plain_when_the_prepare_reenters_within_the_backoff");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        var doorCell = local(frame, 3, 3, 0);
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            var flush = M5GameTestSupport.installFlushProbe(context, new M5GameTestSupport.FlushFault(server, "F5-2 GameTest 受控：提交 PASSAGE_READY 的 journal 寫出失敗", 1,
                    state -> Optional.ofNullable(state.records().get(sid)).filter(record -> record.state() == SessionState.PASSAGE_READY).isPresent()
                            && Optional.ofNullable(state.flushedRecords().get(sid)).filter(record -> record.state() == SessionState.COLLAPSING).isPresent()), hooks);
            whenTicks(context, resolved + 1, 500, () -> flush.failures() == 1, failedAt -> {
                witness.put("pausedWithAPlainDoor", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && doorAll(world, frame, true, MembraneState.NONE));
                at(context, failedAt + 2, hooks, () -> context.setBlockState(DormantOrigin.POWER, Blocks.AIR));
                whenTicks(context, failedAt + 3, 20, () -> capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退回 WAITING", sid.toString()).size() == 1, left -> {
                    witness.put("leftThePrepareWithinTheBackoff", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && flush.ticks().size() == 1);
                    context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                    at(context, left + 2, hooks, () -> moveOutside(p, frame));
                    at(context, left + 3, hooks, () -> moveInside(p, frame));
                    whenTicks(context, left + 4, 40, () -> entered(capture, sid) == 2, reentered -> {
                        at(context, reentered + 2, hooks, () -> {
                            witness.put("plainDoorAfterReenteringWithinTheBackoff", doorAll(world, frame, true, MembraneState.NONE) && !M5GameTestSupport.doorSealed(world, frame));
                            witness.put("stillWithinTheBackoff", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && flush.ticks().size() == 1
                                    && capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退避期已過，PREPARE 恢復", sid.toString()).isEmpty());
                            witness.put("participantClosesThePlainDoorWithinTheBackoff", interact(world, doorCell, p).isAccepted()
                                    && doorAll(world, frame, false, MembraneState.NONE));
                        });
                        at(context, reentered + 3, hooks, () -> witness.put("participantReopensThePlainDoorWithinTheBackoff", interact(world, doorCell, p).isAccepted()
                                && doorAll(world, frame, true, MembraneState.NONE) && M5GameTestSupport.state(journal, sid).equals("COLLAPSING")));
                        whenTicks(context, reentered + 4, 300, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                            witness.put("resumedAfterTheBackoffAndCommitted", readyAt - failedAt >= 195 && flush.ticks().size() == 2 && passageOpened(capture, sid) == 1
                                    && capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "退避期已過，PREPARE 恢復", sid.toString()).size() == 1);
                            witness.put("passableAfterTheRetry", M5GameTestSupport.doorPassable(world, frame));
                            LOGGER.info("M5_PASSAGE_READY_FAULT_REENTER failed={} left={} reentered={} ready={} flushes={}", failedAt, left, reentered, readyAt, flush.ticks());
                            releaseThenComplete(context, hooks, witness, "提交 PASSAGE_READY 失敗之後在退避期內再進入 PREPARE：" + witness, 20, () -> true);
                        }, () -> "退避之後沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid) + " flushes=" + flush.ticks() + " witness=" + witness, hooks);
                    }, () -> "恢復供電、P 再進入之後沒有回到 PREPARE：entered=" + entered(capture, sid) + " witness=" + witness, hooks);
                }, () -> "斷電之後沒有退回 WAITING：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "提交 PASSAGE_READY 的寫出沒有被注入失敗：record=" + M5GameTestSupport.state(journal, sid) + " flushes=" + flush.ticks(), hooks);
        });
    }

    /**
     * F5-1 fix round 1（spec review S-m1；spec §10.3「上一個 tick 還在中線內側」）：PASSAGE_READY 之後，P 站在門板中線內側（local z=0.7）被觀察一次，之後這個 session 的塌縮流程
     * 推進暫停 3 個 tick（{@code installStepPause}；等同 production 中推進被略過的 tick：journal 有未 checked 的 authority、隔離中、整個 tick 無法執行），期間 P 走到中線外側
     * （z=0.3）。恢復之後的第一個 tick：記下的內側觀察不是上一個 tick 的，不觸發跨越，P 留在原世界、Crossed 是空的（修正之前這個 tick 會把 P 傳送走）。之後 P 回到內側、再越過中線：
     * 這次上一個 tick 有內側的觀察，傳送到 U，(b) 結束。這個 session 沒有任何 WARN／ERROR。F5-2 Step 0（R-m2）：暫停期間 test 以 {@code M5TestHooks.collapseSuspended} 查詢兩次，
     * {@code hits()} 不變（查詢沒有副作用；只有 mixin 取消推進時計數）。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_stale", tickLimit = 900)
    public void passage_crossing_needs_the_previous_tick_observation_after_skipped_ticks(TestContext context) {
        begin(context, "passage_crossing_needs_the_previous_tick_observation_after_skipped_ticks");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                M5TestHooks.StepPause[] pause = {null};
                Runnable inside = () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO);
                Runnable outside = () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                sequence(context, readyAt + 1, hooks, List.of(
                        // readyAt+1：這個 tick 的推進觀察到 P 在中線內側。
                        inside,
                        // readyAt+2～+4：推進暫停；+3 時 P 走到中線外側（沒有被觀察）。
                        () -> pause[0] = M5GameTestSupport.installStepPause(context, sid, hooks),
                        outside,
                        () -> {
                            // F5-2 Step 0（F5-1 fix round 1 quality re-review R-m2）：test 以 collapseSuspended 查詢不計數，只有 mixin 取消推進時計數。
                            int before = pause[0].hits();
                            witness.put("queryDoesNotCount", M5TestHooks.collapseSuspended(sid) && M5TestHooks.collapseSuspended(sid) && pause[0].hits() == before);
                        },
                        // readyAt+5：放行，這個 tick 的推進照常；上一次的觀察是 readyAt+1 的內側。
                        () -> pause[0].release(),
                        () -> {
                            var record = journal.flushedRecords().get(sid);
                            witness.put("stepPausedForThreeTicks", pause[0].hits() == 3);
                            witness.put("noCrossingFromAStaleObservation", p.getServerWorld() == world && record != null && record.state() == SessionState.PASSAGE_READY
                                    && record.collapse().crossed().isEmpty() && crossedInfos(capture, sid) == 0);
                            inside.run();
                        },
                        // readyAt+7：上一個 tick（+6）觀察到內側，這個 tick 在外側。
                        outside));
                whenTicks(context, readyAt + 8, 10, () -> !journal.flushedRecords().containsKey(sid), ended -> {
                    witness.put("crossedAfterAFreshObservation", p.getServerWorld() == universe && crossedInfos(capture, sid) == 1);
                    witness.put("endedWhenEveryoneCrossed", passageEnded(capture, sid, "(b) 全員都已跨過") == 1);
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    LOGGER.info("M5_PASSAGE_STALE ready={} ended={} paused={}", readyAt, ended, pause[0].hits());
                    releaseThenComplete(context, hooks, witness, "略過推進之後的跨越：" + witness, 20, () -> true);
                }, () -> "P 再越過中線之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    // ---- F5-2：比較器 15 與 membrane 正規化 ----

    /**
     * F5-2（plan Review Focus 5、spec §10.7、§15「沒有 runtime 的 DORMANT 續行（以 M4 receipt fixture 開始）到達 READY、比較器 15，而且撐過強制的 Controller 刷新與 membrane
     * 造成的 neighbor update；WAITING 期間斷電再恢復之後，READY 不會被結束」）：原艙上的 DORMANT NEW 收據（沒有 runtime），參與者 P 由艙外進入 → COLLAPSING → RESOLVE（不需要供電）。
     * <ul>
     * <li>RESOLVE 之後、第一次閘門評估之前斷電：閘門不成立，停在 WAITING（沒有進入 PREPARE）；Controller 協調之後 registry 的 power state 是 RETURNING（保護不解除）、比較器 11
     *     （presence 是 COLLAPSE）。</li>
     * <li>恢復供電：registry 回到 POWERED，仍在 WAITING、比較器仍是 11（P 一直在艙內，沒有新的邊緣）。P 出艙再進艙（§7.1 的邊緣觸發）→ PREPARE → PASSAGE_READY：Controller 是
     *     PASSAGE、比較器 15，門是 passable 量子膜。</li>
     * <li>強制刷新 Controller（{@code ChamberControllerBlock.refreshState}）、門板頂排中間那格對 Controller 的 neighbor update（門板改 membrane 時的那一種，spec §10.7），以及之後
     *     25 ticks（跨過一次每 20 tick 的排程刷新）：一直是 PASSAGE／15，record 一直是 PASSAGE_READY，沒有「量子通道已結束」，門仍是 passable。</li>
     * <li>P 在原艙這一側以 bulkhead 關門：(a) 結束通道（checked 終局），門關上，Controller 依一般流程回到 IDLE（比較器 3；P 沒有 Buff），registry 是 POWERED。</li>
     * </ul>
     * 這個 session 沒有任何 WARN／ERROR。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_comparator", tickLimit = 1200)
    public void dormant_passage_reaches_comparator_fifteen_and_survives_refreshes_after_a_waiting_power_cut(TestContext context) {
        begin(context, "dormant_passage_reaches_comparator_fifteen_and_survives_refreshes_after_a_waiting_power_cut");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            java.util.function.Supplier<dev.quantumchamber.chamber.ChamberPowerState> power =
                    () -> ChamberRegistryState.get(server).registry().records().get(chamber).powerState();
            witness.put("dormantWithoutRuntime", !M4CandidateTestAccess.runtimeHeld(sid));
            // RESOLVE 之後、第一次閘門評估之前斷電（這個 callback 早於那一次評估）。
            context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
            whenTicks(context, resolved + 1, 60, () -> power.get() == dev.quantumchamber.chamber.ChamberPowerState.RETURNING, cut -> {
                witness.put("waitingWithoutPower", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && entered(capture, sid) == 0);
                witness.put("comparatorElevenWhileUnpowered", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.COLLAPSE
                        && controllerSignal(world, frame, ChamberState.ARMED, 11));
                context.setBlockState(DormantOrigin.POWER, Blocks.REDSTONE_BLOCK);
                whenTicks(context, cut + 1, 60, () -> power.get() == dev.quantumchamber.chamber.ChamberPowerState.POWERED, repowered -> {
                    witness.put("stillWaitingAfterTheRepower", M5GameTestSupport.state(journal, sid).equals("COLLAPSING") && entered(capture, sid) == 0
                            && controllerSignal(world, frame, ChamberState.ARMED, 11));
                    // §7.1 的邊緣觸發：P 出艙（兩個塌縮流程的 tick 看到他在艙外）再進艙。
                    at(context, repowered + 1, hooks, () -> moveOutside(p, frame));
                    at(context, repowered + 3, hooks, () -> moveInside(p, frame));
                    whenTicks(context, repowered + 4, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                        witness.put("comparatorFifteenWhenReady", controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                        witness.put("passableMembrane", M5GameTestSupport.doorPassable(world, frame));
                        ChamberControllerBlock.refreshState(world, frame.controllerPos());
                        witness.put("fifteenAfterAForcedRefresh", controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                        world.updateNeighbors(local(frame, 3, 5, 0), ModBlocks.QUANTUM_BULKHEAD);
                        witness.put("fifteenAfterTheMembraneNeighborUpdate", controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                        at(context, readyAt + 25, hooks, () -> {
                            witness.put("fifteenAcrossAScheduledRefresh", controllerSignal(world, frame, ChamberState.PASSAGE, 15));
                            witness.put("readyNotEnded", M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY") && passageEnded(capture, sid, "量子通道已結束") == 0
                                    && M5GameTestSupport.doorPassable(world, frame));
                            witness.put("participantClosesTheDoor", interact(world, local(frame, 3, 3, 0), p).isAccepted());
                            witness.put("endedByTheParticipant", !journal.flushedRecords().containsKey(sid)
                                    && passageEnded(capture, sid, "(a) 參與者在原艙這一側開關原艙門") == 1);
                            witness.put("doorClosed", doorAll(world, frame, false, MembraneState.NONE));
                            witness.put("idleAfterTheTerminal", controllerSignal(world, frame, ChamberState.IDLE, 3)
                                    && power.get() == dev.quantumchamber.chamber.ChamberPowerState.POWERED);
                            witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                            LOGGER.info("M5_PASSAGE_COMPARATOR resolved={} cut={} repowered={} ready={} after={} power={}", resolved, cut, repowered, readyAt,
                                    M5GameTestSupport.controller(world, frame).chamberState(), power.get());
                            releaseThenComplete(context, hooks, witness, "比較器 15（Review Focus 5）：" + witness, 20, () -> true);
                        });
                    }, () -> "恢復供電、P 再進艙之後沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
                }, () -> "恢復供電之後 registry 沒有回到 POWERED：power=" + power.get() + " witness=" + witness, hooks);
            }, () -> "斷電之後 registry 沒有改為 RETURNING：power=" + power.get() + " record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    // ---- F6-1 Step 0（F5-3 spec m-1）：Crossed 的寫入被拒時的到達提示，以及 (a)(d) 結束時「量子通道已關閉」的對象 ----

    /**
     * F6-1 Step 0（F5-3 spec m-1；spec §20.9 的邊界情況「Crossed 的寫入被拒、沒有記下時，玩家其實已經在目的世界。到達提示照常顯示；通道結束時，因為他不在 {@code Crossed} 中，
     * 仍會收到『量子通道已關閉。』」、§10.6 (c)）：參與者 P。PASSAGE_READY 提交之後，以 {@code installJournalPutFault} 讓這個 session 下一次寫成 PASSAGE_READY 的 put（P 的 Crossed
     * 寫入；READY 的提交已在 journal）被拒一次：
     * <ul>
     * <li>P 越過中線、傳送到 U；Crossed 沒有記下（REJECTED：flushed 與 current 相同、Crossed 是空的、沒有待確認的 roll-forward），WARN「寫入 Crossed沒有完成（REJECTED）」一則。
     *     P 照常收到 NEW 的到達提示 title（先送淡入、停留、淡出 10／70／20，沒有 subtitle），actionbar 沒有任何訊息。</li>
     * <li>P 不在 Crossed 中，(b) 不成立：通道一直開著（+100 tick 時仍是 PASSAGE_READY），P 留在 U、不再被傳送。+100 tick 時先讀一次 {@code hud}：跨越之後到這時什麼都沒有收到
     *     （{@code nothingWhileTheRejectedPassageIsOpen}，F6-3c-1 依 F6-1 Step 0 quality Minor 1 補上：原本跨越之後約 1200 tick 都沒有清封包，違反 README「新增 M5 GameTest」的
     *     {@code hud} 規則；結束時的精確比對因此只涵蓋 +100 tick 之後到結束）。</li>
     * <li>game time 到 expires（opened＋1200）時 (c) 結束（INFO (c) 一則），門改為一般開門。P 在 U、不在 Crossed 中，所以收到「量子通道已關閉」（恰一則 actionbar，沒有 title）。</li>
     * </ul>
     * 這個 session 的 WARN 只有那一則被拒，沒有 ERROR。不是 {@code _windows}：只用 journal put 的拒絕，不需要原生 checkpoint。有界等待的總長度：{@code resolved}（最多 65，見
     * {@code crossed_write_failure_pauses_the_passage_until_it_converges_then_expires}）＋提交 1＋400＋到期與結束 101＋1300＋完成 20＝1887，小於 tickLimit 2000。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_crossed_rejected", tickLimit = 2000)
    public void crossed_write_rejected_still_shows_the_arrival_title_and_the_closed_message_at_the_expiry(TestContext context) {
        begin(context, "crossed_write_rejected_still_shows_the_arrival_title_and_the_closed_message_at_the_expiry");
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        collapsedToUniverse(context, origin, hooks, (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                long expires = journal.flushedRecords().get(sid).collapse().passageExpiresAtGameTime();
                // READY 的提交（COLLAPSING→PASSAGE_READY）已在 journal，之後這個 session 寫成 PASSAGE_READY 的 put 只有 Crossed 的寫入。
                var reject = M5GameTestSupport.installJournalPutFault(context, sid, SessionState.PASSAGE_READY, 1, hooks);
                M5GameTestSupport.hud(origin.connection);
                sequence(context, readyAt + 1, hooks, List.of(
                        () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO),
                        () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO),
                        () -> {
                            var flushed = journal.flushedRecords().get(sid);
                            witness.put("crossedToTheUniverse", p.getServerWorld() == universe && universe.getEntity(p.getUuid()) == p && crossedInfos(capture, sid) == 1);
                            witness.put("crossedWriteRejected", reject.hits() == 1 && flushed != null && flushed.state() == SessionState.PASSAGE_READY
                                    && flushed.collapse().crossed().isEmpty() && flushed.equals(journal.records().get(sid)) && !journal.rollForwardPending(sid));
                            witness.put("oneRejectedCrossedWarn", capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "寫入 Crossed沒有完成（REJECTED）",
                                    sid.toString()).size() == 1);
                            // spec §20.9 邊界情況：被拒的人已在目的世界，到達提示照常以 title 顯示；actionbar 沒有任何訊息。
                            var arrival = M5GameTestSupport.hud(origin.connection);
                            witness.put("arrivalTitleForTheRejectedCrossing", arrival.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)));
                            witness.put("nothingOnTheActionbarAtTheCrossing", arrival.overlays().isEmpty());
                        }));
                at(context, readyAt + 100, hooks, () -> {
                    var record = journal.flushedRecords().get(sid);
                    witness.put("stillOpenWithoutTheRejectedOne", record != null && record.state() == SessionState.PASSAGE_READY && record.collapse().crossed().isEmpty()
                            && p.getServerWorld() == universe && crossedInfos(capture, sid) == 1 && passageEnded(capture, sid, "量子通道已結束") == 0);
                    // F6-3c-1（F6-1 Step 0 quality Minor 1）：開放期間先讀一次 hud（清掉跨越之後的封包），跨越之後到這時什麼都沒收到；結束時的精確比對只涵蓋這之後到結束。
                    var open = M5GameTestSupport.hud(origin.connection);
                    witness.put("nothingWhileTheRejectedPassageIsOpen", open.overlays().isEmpty() && open.titles().isEmpty());
                });
                whenTicks(context, readyAt + 101, 1300, () -> !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid), ended -> {
                    witness.put("endedByExpiry", passageEnded(capture, sid, "(c) game time 已到 PassageExpiresAtGameTime") == 1
                            && server.getOverworld().getTime() >= expires);
                    witness.put("plainOpenAfterTheEnd", doorAll(world, frame, true, MembraneState.NONE));
                    witness.put("stillInTheUniverse", p.getServerWorld() == universe && crossedInfos(capture, sid) == 1);
                    // spec §20.9 邊界情況：結束時那筆 record 的 Crossed 沒有 P（寫入被拒），所以他仍收到「量子通道已關閉」（actionbar），沒有其他 title。
                    var atEnd = M5GameTestSupport.hud(origin.connection);
                    witness.put("closedMessageToTheRejectedOne", atEnd.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED)) && atEnd.titles().isEmpty());
                    witness.put("onlyTheRejectedWarn", capture.matching(Level.WARN, sid.toString()).size() == 1 && capture.matching(Level.ERROR, sid.toString()).isEmpty());
                    LOGGER.info("M5_PASSAGE_CROSSED_REJECTED ready={} expires={} ended={} rejected={}", readyAt, expires, ended, reject.ticks());
                    releaseThenComplete(context, hooks, witness, "Crossed 的寫入被拒：" + witness, 20, () -> true);
                }, () -> "到期之後通道沒有結束：record=" + M5GameTestSupport.state(journal, sid) + " time=" + server.getOverworld().getTime() + " expires=" + expires
                        + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F6-1 Step 0（F5-3 spec m-1；spec §20.9 的邊界情況「多人時，先跨過去的人不會再收到通道之後因 (a)(c)(d) 關閉的通知」、§10.6 (a)）：參與者 P、Q 都在艙內。P 穿過通道
     * （Crossed＝[P]，NEW 的到達提示 title），Q 留在艙內；之後 Q 在原艙這一側以 bulkhead 關門：(a) 結束（先 checked 終局、再關門，INFO (a) 一則）。只有還沒跨過的 Q 收到
     * 「量子通道已關閉」（恰一則 actionbar、沒有 title）；已在 Crossed 中的 P 什麼都沒有收到（actionbar 與 title 都是空的）。(d) 的版本是
     * {@link #passage_power_loss_skips_the_one_who_crossed}，兩者共用 {@link #endWithOneCrossed}；(b) 由 {@code passage_participant_crosses_…}、(c) 由
     * {@code crossed_write_failure_pauses_…} 與 {@code passage_expires_…_windows} 覆蓋。不是 {@code _windows}。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_close_crossed", tickLimit = 900)
    public void passage_closed_by_the_one_who_stayed_skips_the_one_who_crossed(TestContext context) {
        begin(context, "passage_closed_by_the_one_who_stayed_skips_the_one_who_crossed");
        endWithOneCrossed(context, CrossedEnd.BY_PARTICIPANT);
    }

    /**
     * F6-1 Step 0（同上，§10.6 (d)）：P 穿過通道之後斷電，下一個 tick 依 (d)「來源艙沒有供電」結束（INFO (d) 一則；{@code endedNextTick}，F6-3c-1 依 F6-1 Step 0 quality Nit 4
     * 補上，原本只寫在這裡、沒有斷言），門改為一般開門。只有還沒跨過的 Q 收到「量子通道已關閉」；已在 Crossed 中的 P 什麼都沒有收到。不是 {@code _windows}。catalog 增量 1。
     */
    @GameTest(templateName = "quantumchamber:m1_empty", batchId = "m5_universe_passage_power_crossed", tickLimit = 900)
    public void passage_power_loss_skips_the_one_who_crossed(TestContext context) {
        begin(context, "passage_power_loss_skips_the_one_who_crossed");
        endWithOneCrossed(context, CrossedEnd.POWER_LOSS);
    }

    /**
     * {@link #endWithOneCrossed} 的結束方式（F6-3c-1，F6-1 Step 0 quality Minor 2：原本是裸 boolean {@code byParticipant}，呼叫點看不出是 (a) 還是 (d)）：「量子通道已結束」INFO 的
     * 原因字句與終局之後原艙門的狀態。結束的動作由 {@link #endWithOneCrossed} 依它分派。
     */
    private enum CrossedEnd {
        /** (a)：還沒跨過的 Q 在原艙這一側以 bulkhead 關門；終局之後門關著。 */
        BY_PARTICIPANT("(a) 參與者在原艙這一側開關原艙門", false),
        /** (d)：來源艙斷電；終局之後門改為一般開門。 */
        POWER_LOSS("(d) 來源艙沒有供電", true);

        /** 「量子通道已結束」INFO 的原因字句。 */
        final String reason;
        /** 終局之後原艙門是一般開門（否則關著）。 */
        final boolean openAfterTheEnd;

        CrossedEnd(String reason, boolean openAfterTheEnd) {
            this.reason = reason;
            this.openAfterTheEnd = openAfterTheEnd;
        }
    }

    /**
     * {@link #passage_closed_by_the_one_who_stayed_skips_the_one_who_crossed}（{@link CrossedEnd#BY_PARTICIPANT}：Q 以 bulkhead 關門，(a)）與
     * {@link #passage_power_loss_skips_the_one_who_crossed}（{@link CrossedEnd#POWER_LOSS}：斷電，(d)）的本體（F6-1 Step 0）。兩者只差在結束的方式、INFO 的原因與門的終局狀態
     * （(a) 關閉，(d) 一般開門）。有界等待的總長度：{@code resolved}（最多 65）＋提交 1＋400＋結束 5＋10＋完成 20＝501，小於 tickLimit 900。
     */
    private static void endWithOneCrossed(TestContext context, CrossedEnd how) {
        var world = context.getWorld(); var server = world.getServer(); var journal = SessionRecoveryState.get(server);
        var hooks = new M2CorridorGameTests.TestHooks();
        var origin = openOrigin(context, hooks);
        var frame = origin.frame; var capture = origin.capture;
        var p = origin.player();
        ConnectedGameTestPlayer second;
        try {
            second = new ConnectedGameTestPlayer(world);
            hooks.add(second::close);
            second.player().setNoGravity(true);
            moveOutside(second.player(), frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
        var q = second.player();
        var doorCell = local(frame, 3, 3, 0);
        var end = how.reason;
        collapsedToUniverse(context, origin, hooks, List.of(q.getUuid()), (resolved, universe, sid, chamber, id) -> {
            var witness = new LinkedHashMap<String, Boolean>();
            moveInside(q, frame);
            whenTicks(context, resolved + 1, 400, () -> M5GameTestSupport.state(journal, sid).equals("PASSAGE_READY"), readyAt -> {
                M5GameTestSupport.hud(origin.connection); M5GameTestSupport.hud(second);
                // POWER_LOSS 斷電的那個 test tick（F6-3c-2，F6-3c-1 quality nit：endedNextTick 不再寫死斷電在 sequence 的第幾步）；BY_PARTICIPANT 不用。
                var poweredOffAt = new AtomicInteger(-1);
                sequence(context, readyAt + 1, hooks, List.of(
                        () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO),
                        () -> M5GameTestSupport.standInDoorway(p, frame, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO),
                        () -> {
                            var record = journal.flushedRecords().get(sid);
                            witness.put("oneCrossedOneStayed", p.getServerWorld() == universe && q.getServerWorld() == world && record != null
                                    && record.state() == SessionState.PASSAGE_READY && record.collapse().crossed().equals(List.of(p.getUuid())));
                            var arrival = M5GameTestSupport.hud(origin.connection);
                            witness.put("arrivalTitleForTheOneWhoCrossed", arrival.titles().equals(M5GameTestSupport.arrivalTitle(ARRIVED_NEW_KEY)) && arrival.overlays().isEmpty());
                            var stayed = M5GameTestSupport.hud(second);
                            witness.put("nothingForTheOneWhoStayedBeforeTheEnd", stayed.overlays().isEmpty() && stayed.titles().isEmpty());
                        },
                        () -> {
                            switch (how) {
                                case BY_PARTICIPANT -> witness.put("closedByTheOneWhoStayed", interact(world, doorCell, q).isAccepted());
                                case POWER_LOSS -> {
                                    poweredOffAt.set(Math.toIntExact(context.getTick()));
                                    context.setBlockState(DormantOrigin.POWER, Blocks.AIR);
                                }
                            }
                        }));
                whenTicks(context, readyAt + 5, 10, () -> !journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid), ended -> {
                    witness.put("endedAsExpected", passageEnded(capture, sid, end) == 1 && passageEnded(capture, sid, "量子通道已結束") == 1);
                    // F6-3c-1（F6-1 Step 0 quality Nit 4）：(d) 在斷電的下一個 tick 結束，比照 passage_ends_with_a_plain_open_door_when_the_source_loses_power 的 endedNextTick。
                    // F6-3c-2（F6-3c-1 quality nit）：以斷電那一步記下的 tick 判斷（斷電仍在 readyAt+4，判斷與原本的 ended == readyAt + 5 相同）。
                    if (how == CrossedEnd.POWER_LOSS) witness.put("endedNextTick", ended == poweredOffAt.get() + 1);
                    witness.put("doorAfterTheTerminal", doorAll(world, frame, how.openAfterTheEnd, MembraneState.NONE));
                    witness.put("destinationTicketReleased", M5GameTestSupport.destinationTickets(universe, sid).isEmpty());
                    witness.put("presenceNone", ChamberSessions.gateway().presence(server, chamber) == ChamberSessionGateway.Presence.NONE);
                    // spec §20.9：已在 Crossed 中的 P 不再收到「量子通道已關閉」（什麼都沒有收到）；還沒跨過的 Q 收到恰一則 actionbar，沒有 title。
                    var crossedAtEnd = M5GameTestSupport.hud(origin.connection);
                    var stayedAtEnd = M5GameTestSupport.hud(second);
                    witness.put("nothingForTheOneWhoCrossed", crossedAtEnd.overlays().isEmpty() && crossedAtEnd.titles().isEmpty());
                    witness.put("closedMessageOnlyToTheOneWhoStayed", stayedAtEnd.overlays().equals(List.of(CollapseService.MESSAGE_PASSAGE_CLOSED))
                            && stayedAtEnd.titles().isEmpty());
                    witness.put("noWarnOrError", M5GameTestSupport.noWarnOrError(capture, sid) && M5GameTestSupport.collapseFlowNeverWarned(capture, sid));
                    LOGGER.info("M5_PASSAGE_END_WITH_ONE_CROSSED end={} ready={} ended={}", end, readyAt, ended);
                    releaseThenComplete(context, hooks, witness, "通道結束（" + end + "）時「量子通道已關閉」的對象：" + witness, 20, () -> true);
                }, () -> "通道沒有結束（" + end + "）：record=" + M5GameTestSupport.state(journal, sid) + " witness=" + witness, hooks);
            }, () -> "沒有提交 PASSAGE_READY：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /**
     * F5-1：從 {@code tick} 起每個 tick 依序執行一步（第 i 步在 {@code tick + i}）。每一步的 callback 只排下一步這一項：在一個 callback 裡一次排很多項 {@code runAtTick}，原版
     * 迭代表的途中插入多項有 rehash 的風險（README「新增 M5 GameTest」的前提）。例外或斷言失敗時先釋放 hooks（{@link M5GameTestSupport#at}）。
     */
    private static void sequence(TestContext context, int tick, M2CorridorGameTests.TestHooks hooks, List<Runnable> steps) {
        if (steps.isEmpty()) return;
        at(context, tick, hooks, () -> {
            steps.getFirst().run();
            sequence(context, tick + 1, hooks, steps.subList(1, steps.size()));
        });
    }

    /** 來源 Controller 目前是 {@code state}，而且比較器輸出是 {@code signal}（F5-2：spec §10.7 的比較器）。 */
    private static boolean controllerSignal(ServerWorld world, ChamberFrame frame, ChamberState state, int signal) {
        var pos = frame.controllerPos();
        return world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller && controller.chamberState() == state
                && world.getBlockState(pos).getComparatorOutput(world, pos) == signal;
    }

    /** 這個 session「參與者已穿過量子通道」的 INFO 數（帶 LOG_MARKER，F5-1）。 */
    private static int crossedInfos(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "參與者已穿過量子通道", sid.toString()).size();
    }

    /** 這個 session「量子通道已結束」的 INFO 中含 {@code fragment} 的則數（帶 LOG_MARKER，F5-1；例如結束原因的字句）。 */
    private static int passageEnded(LogCapture capture, UUID sid, String fragment) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "量子通道已結束", fragment, sid.toString()).size();
    }

    /** 這個 session 通道傳送沒有完成（玩家仍在原世界）的 WARN 數（帶 LOG_MARKER，F5-1）。 */
    private static int transferWarnings(LogCapture capture, UUID sid) {
        return capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "塌縮流程的通道傳送沒有完成", sid.toString()).size();
    }
    /** F5-2：Controller 開關門時，比較器是 PASSAGE（15）的訊息 key（以字面值斷言，不信任 production 常數）。 */
    private static final String CONTROLLER_PASSAGE_KEY = "message.quantumchamber.m5.controller_passage";
    /** F5-3（spec §10.8、§20.8）：到達目的宇宙的 title，候選是 NEW 與 EXISTING（以字面值斷言，不信任 production 常數）。 */
    private static final String ARRIVED_NEW_KEY = "message.quantumchamber.m5.arrived_new", ARRIVED_EXISTING_KEY = "message.quantumchamber.m5.arrived_existing";
}
