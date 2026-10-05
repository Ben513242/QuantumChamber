package dev.quantumchamber.collapse;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.quantumchamber.candidate.CandidateBytes;
import dev.quantumchamber.candidate.CandidateDoorInteraction;
import dev.quantumchamber.candidate.CandidateId;
import dev.quantumchamber.candidate.CandidateLedgerEntry;
import dev.quantumchamber.candidate.CandidatePolicySnapshot;
import dev.quantumchamber.candidate.CandidateSelection;
import dev.quantumchamber.candidate.NewUniverseIntent;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.SourceFamilyRef;
import dev.quantumchamber.chamber.ChamberDoorService.ApplyResult.Outcome;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.persistence.CollapseFields;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryRecord.Participant;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.GeneratorProfile;
import dev.quantumchamber.universe.UniverseId;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/**
 * F3d：{@link CollapsePhase} 的推導（spec §7.3）、{@link CollapseService} 的純判斷——提交時的 Destination（§7.2）、§11.3 的不支援來源、
 * §7.1 的邊緣觸發——以及 §10.8 的「塌縮」訊息在兩個語言檔都存在。需要 server 的流程由 GameTest 覆蓋。
 */
class CollapseServiceTest {
    static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-0000000000d3");
    static final UUID CHAMBER = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    static final UniverseId EXISTING_ID = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
    /** ledger 的 canonical 順序：0＝SOURCE、1＝NEW、2＝EXISTING。 */
    static final int SOURCE = 0, NEW = 1, EXISTING = 2;

    @Test void collapsePhaseFollowsReturnReleaseResolveThenTheGate() {
        var universe = collapsing(measured(DimensionRole.OVERWORLD, NEW), new CollapseDestination.Universe(EXISTING_ID));
        var source = collapsing(measured(DimensionRole.OVERWORLD, SOURCE), new CollapseDestination.Source());
        var gateCalls = new AtomicInteger();
        java.util.function.BooleanSupplier open = () -> { gateCalls.incrementAndGet(); return true; };
        java.util.function.BooleanSupplier shut = () -> { gateCalls.incrementAndGet(); return false; };
        var cases = new ArrayList<Executable>();
        record Row(String label, SessionRecoveryRecord view, boolean releaseComplete, boolean resolved, boolean gateOpen, CollapsePhase expected, boolean gateConsulted) {}
        var rows = List.of(
                new Row("有人尚未返還", universe, true, true, true, CollapsePhase.RETURN_PLAYERS, false),
                new Row("部分返還", returned(universe, A), true, true, true, CollapsePhase.RETURN_PLAYERS, false),
                new Row("全員返還、仍有 lease", returned(universe, A, B), true, true, true, CollapsePhase.RELEASE_GEOMETRY, false),
                new Row("lease 已清、space 還沒釋放（F3a I-1）", cleared(returned(universe, A, B)), false, true, true, CollapsePhase.RELEASE_GEOMETRY, false),
                new Row("UNIVERSE 目的地未確定", cleared(returned(universe, A, B)), true, false, true, CollapsePhase.RESOLVE, false),
                new Row("UNIVERSE 已確定、閘門成立", cleared(returned(universe, A, B)), true, true, true, CollapsePhase.PREPARE, true),
                new Row("UNIVERSE 已確定、閘門不成立", cleared(returned(universe, A, B)), true, true, false, CollapsePhase.WAITING, true),
                new Row("SOURCE 不需要確定、閘門成立", cleared(returned(source, A, B)), true, false, true, CollapsePhase.PREPARE, true),
                new Row("SOURCE 閘門不成立", cleared(returned(source, A, B)), true, false, false, CollapsePhase.WAITING, true));
        for (var row : rows) cases.add(() -> {
            gateCalls.set(0);
            assertEquals(row.expected(), CollapsePhase.derive(row.view(), row.releaseComplete(), row.resolved(), row.gateOpen() ? open : shut), row.label());
            assertEquals(row.gateConsulted() ? 1 : 0, gateCalls.get(), "閘門只在前面的條件都成立時才評估（評估會消耗邊緣觸發）：" + row.label());
        });
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapsePhase.derive(measured(DimensionRole.OVERWORLD, NEW), true, true, open),
                "MEASURED 沒有塌縮的推導階段"));
        var sealed = cleared(returned(universe, A, B)).withCollapse(SessionState.SEALED,
                cleared(returned(universe, A, B)).collapse().sealed(SealedReason.PROJECTION_FAILED));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapsePhase.derive(sealed, true, true, open), "SEALED 沒有推導階段"));
        assertAll(cases);
    }

    @Test void destinationIsFixedAtCommitFromTheSelectedCandidate() {
        var newMeasured = measured(DimensionRole.OVERWORLD, NEW);
        var token = ((QuantumCandidate.New) newMeasured.selectedCandidate().orElseThrow()).intent().allocationToken();
        assertAll(
                () -> assertEquals(new CollapseDestination.Source(), CollapseService.destination(measured(DimensionRole.OVERWORLD, SOURCE)), "SOURCE"),
                () -> assertEquals(new CollapseDestination.Universe(EXISTING_ID), CollapseService.destination(measured(DimensionRole.OVERWORLD, EXISTING)),
                        "EXISTING：候選裡的 UniverseId"),
                () -> assertEquals(new CollapseDestination.Universe(UniverseAllocationLedger.deriveUniverseId(token)), CollapseService.destination(newMeasured),
                        "NEW：allocation token 的推導值（提交時不寫 ledger）"),
                // 提交的 record 必須通過 F2a 的合法組合與狀態邊（Destination 對應選中的候選）。
                () -> assertDoesNotThrow(() -> newMeasured.withCollapse(SessionState.COLLAPSING,
                        CollapseFields.committed(0, CollapseService.destination(newMeasured)))),
                () -> assertTrue(SessionRecoveryRecord.sameAuthority(newMeasured, newMeasured.withCollapse(SessionState.COLLAPSING,
                        CollapseFields.committed(0, CollapseService.destination(newMeasured)))), "M→C 是合法的單步狀態邊"));
    }

    @Test void unsupportedSourceIsOnlyNetherOrEndWithNewOrExisting() {
        var cases = new ArrayList<Executable>();
        for (var role : DimensionRole.values()) for (int choice : new int[] {SOURCE, NEW, EXISTING}) {
            boolean expected = role != DimensionRole.OVERWORLD && choice != SOURCE;
            cases.add(() -> assertEquals(expected, CollapseService.unsupportedSource(measured(role, choice)), role + " " + choice));
        }
        assertAll(cases);
    }

    @Test void entryWatchTriggersOnlyOnEntryEdges() {
        var fresh = new CollapseService.EntryWatch(false);
        var startup = new CollapseService.EntryWatch(true);
        var primed = new CollapseService.EntryWatch(false);
        primed.prime(Set.of(A));
        assertAll(
                () -> assertEquals(List.of(), fresh.observe(Set.of(A)), "執行期間才開始追蹤：第一次觀察只記下狀態，不觸發"),
                () -> assertEquals(List.of(), fresh.observe(Set.of(A)), "一直在艙內：不是每個 tick 都觸發"),
                () -> assertEquals(List.of(B), fresh.observe(ordered(A, B)), "另一位由艙外進入：觸發"),
                () -> assertEquals(List.of(), fresh.observe(Set.of(B)), "離開不觸發"),
                () -> assertEquals(List.of(A), fresh.observe(ordered(B, A)), "離開之後再進入：觸發"),
                () -> assertEquals(List.of(A), startup.observe(Set.of(A)), "開服 attach 之後第一次觀察到原參與者在艙內：觸發"),
                () -> assertEquals(List.of(), startup.observe(Set.of(A)), "之後回到邊緣觸發"),
                () -> assertEquals(List.of(), primed.observe(Set.of(A)), "閘門以位準評估過之後只看邊緣"),
                () -> assertEquals(List.of(B), primed.observe(ordered(A, B)), "prime 之後新進入的人觸發"));
    }

    /**
     * F3d fix round 2（quality m-2）：MEASURED 的分流逐列。current 與 flushed 不同時一律等待；§11.3 的收據不論 DORMANT、清理、退避都走取消路徑；
     * DORMANT 走邊緣觸發；live 只在 M4 的 measured release 沒有進行到一半、而且不在退避期內時提交。
     */
    @Test void measuredActionCommitsOnlyAnIdleSupportedLiveReceiptOutOfBackoff() {
        record Row(boolean current, boolean unsupported, boolean dormant, boolean releasing, boolean backoffReady, CollapseService.MeasuredAction expected) { }
        var rows = List.of(
                new Row(false, true, true, false, true, CollapseService.MeasuredAction.WAIT),
                new Row(false, false, false, false, true, CollapseService.MeasuredAction.WAIT),
                new Row(true, true, false, false, true, CollapseService.MeasuredAction.CANCEL_UNSUPPORTED),
                new Row(true, true, true, true, false, CollapseService.MeasuredAction.CANCEL_UNSUPPORTED),
                new Row(true, false, true, true, false, CollapseService.MeasuredAction.RESUME_DORMANT),
                new Row(true, false, false, true, true, CollapseService.MeasuredAction.WAIT),
                new Row(true, false, false, false, false, CollapseService.MeasuredAction.WAIT),
                new Row(true, false, false, false, true, CollapseService.MeasuredAction.COMMIT_LIVE));
        assertAll(rows.stream().map(row -> (Executable) () -> assertEquals(row.expected(),
                CollapseService.measuredAction(row.current(), row.unsupported(), row.dormant(), row.releasing(), row.backoffReady()), row.toString())));
    }

    /**
     * F3d fix round 2（quality m-2）：待套用門的處置。ROLLED_BACK／ROLLBACK_FAILED 第一次立即重試、再被拒才退避；DOOR_INCOMPLETE 一律退避；
     * NOT_LOADED 只等待；APPLIED／UNCHANGED 完成。WARN 依原因去重：同一原因只記一次，原因改變時再記。
     * <p>F4a-0（quality re-review m-3）與 fix round 1（spec m-2）：再以 production 的 {@link CollapseService#applyOutcome}、
     * {@link CollapseService#applyFailure}（{@code applyDoor} 與兩處套用例外的 catch 實際呼叫的同一份接線）驅動一扇真的 {@code PendingDoor} 走過一串
     * 結果，並擷取 {@code quantumchamber} logger 的 log，鎖住實際的記錄行為：只有 {@code DoorStep.warn()} 為真的那幾次記 WARN（帶 {@code LOG_MARKER}、
     * chamber 與 session，套用例外附 stack），門記下的原因決定下一次的去重，退避照 {@code backOff}；完成時回 true，之前記過 WARN 才記一則 INFO。
     */
    @Test void pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings() {
        var rejected = "門的寫入被拒（ROLLED_BACK）";
        var thrownReason = "套用失敗：java.lang.IllegalStateException: x";
        var first = CollapseService.doorStep(Outcome.ROLLED_BACK, false, null);
        var second = CollapseService.doorStep(Outcome.ROLLED_BACK, first.retried(), first.reason());
        var third = CollapseService.doorStep(Outcome.ROLLBACK_FAILED, second.retried(), second.reason());
        var incomplete = CollapseService.doorStep(Outcome.DOOR_INCOMPLETE, false, null);
        var frame = new ChamberFrame(new BlockPos(7, 70, 9), Direction.NORTH);
        var door = new CollapseService.PendingDoor(CHAMBER, SESSION, World.OVERWORLD, frame, 0);
        // 沒有記過 WARN 就完成的門：不記 INFO。
        var quiet = new CollapseService.PendingDoor(B, A, World.OVERWORLD, frame, 0);
        var warned = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();
        var doneBeforeApplied = new ArrayList<Boolean>();
        boolean appliedDone, quietDone;
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            for (var outcome : List.of(Outcome.ROLLED_BACK, Outcome.ROLLED_BACK, Outcome.ROLLBACK_FAILED, Outcome.NOT_LOADED, Outcome.DOOR_INCOMPLETE,
                    Outcome.DOOR_INCOMPLETE)) {
                int before = capture.count(Level.WARN);
                doneBeforeApplied.add(CollapseService.applyOutcome(door, outcome, 100));
                warned.add(capture.count(Level.WARN) - before);
                reasons.add(door.warned);
            }
            // 套用丟出例外（tick 與 openDoor 的 catch）：同一原因去重、一律退避。
            for (int i = 0; i < 2; i++) {
                int before = capture.count(Level.WARN);
                CollapseService.applyFailure(door, new IllegalStateException("x"), 100);
                warned.add(capture.count(Level.WARN) - before);
                reasons.add(door.warned);
            }
            appliedDone = CollapseService.applyOutcome(door, Outcome.APPLIED, 100);
            quietDone = CollapseService.applyOutcome(quiet, Outcome.UNCHANGED, 100);
            logs = capture.entries();
        }
        var warnings = logs.stream().filter(entry -> entry.level() == Level.WARN).toList();
        var infos = logs.stream().filter(entry -> entry.level() == Level.INFO).toList();
        assertAll(
                () -> assertEquals(new CollapseService.DoorStep(false, rejected, true, false, true), first, "第一次被拒：記 WARN、不退避、下一個 tick 重試"),
                () -> assertEquals(new CollapseService.DoorStep(false, rejected, false, true, true), second, "再被拒：退避，同一原因不再記 WARN"),
                () -> assertEquals(new CollapseService.DoorStep(false, "門的寫入被拒（ROLLBACK_FAILED）", true, true, true), third, "原因改變：再記一次"),
                () -> assertEquals(new CollapseService.DoorStep(false, "門板不是完整的 25 格 bulkhead，等結構恢復", true, true, false), incomplete, "結構性：退避"),
                () -> assertFalse(CollapseService.doorStep(Outcome.DOOR_INCOMPLETE, false, incomplete.reason()).warn(), "結構性的同一原因去重"),
                () -> assertEquals(new CollapseService.DoorStep(false, null, false, false, true), CollapseService.doorStep(Outcome.NOT_LOADED, true, rejected), "未載入：只等待"),
                () -> assertTrue(CollapseService.doorStep(Outcome.APPLIED, true, rejected).done()),
                () -> assertTrue(CollapseService.doorStep(Outcome.UNCHANGED, false, null).done()),
                () -> assertEquals(List.of(1, 0, 1, 0, 1, 0, 1, 0), warned,
                        "production 只在 DoorStep.warn() 為真時記 WARN：被拒、再被拒、原因改變、未載入、結構性、結構性、套用例外、同一例外"),
                () -> assertEquals(Arrays.asList(rejected, rejected, "門的寫入被拒（ROLLBACK_FAILED）", "門的寫入被拒（ROLLBACK_FAILED）",
                        "門板不是完整的 25 格 bulkhead，等結構恢復", "門板不是完整的 25 格 bulkhead，等結構恢復", thrownReason, thrownReason), reasons,
                        "門記下最近的原因（未載入沒有原因時保留上一個），下一次據此去重"),
                () -> assertEquals(6400, door.backoff.delayTicks(), "退避 6 次（再被拒、原因改變、結構性兩次、套用例外兩次）：200→…→6400"),
                () -> assertTrue(door.retried, "第一次被拒之後已立即重試過一次，之後不再立即重試"),
                () -> assertEquals(List.of(false, false, false, false, false, false), doneBeforeApplied, "沒有完成的結果都回 false（呼叫端不移除這扇門）"),
                () -> assertTrue(appliedDone && quietDone, "APPLIED／UNCHANGED 回 true（呼叫端移除這扇門）"),
                () -> assertEquals(List.of(rejected, "門的寫入被拒（ROLLBACK_FAILED）", "門板不是完整的 25 格 bulkhead，等結構恢復", thrownReason),
                        warnings.stream().map(entry -> entry.message().substring(entry.message().indexOf("session=" + SESSION + "：")
                                + ("session=" + SESSION + "：").length())).toList(), "WARN 的原因依序"),
                () -> assertTrue(warnings.stream().allMatch(entry -> entry.marked() && entry.message().contains("chamber=" + CHAMBER)
                        && entry.message().contains("session=" + SESSION)), () -> "每則 WARN 帶 LOG_MARKER、chamber 與 session：" + warnings),
                () -> assertEquals(List.of(false, false, false, true), warnings.stream().map(entry -> entry.thrown() != null).toList(),
                        "只有套用例外的 WARN 附 stack"),
                () -> assertEquals(List.of("原艙門已改為一般開門（先前：" + thrownReason + "）：chamber=" + CHAMBER), infos.stream().map(CollapseLogCapture.Entry::message).toList(),
                        "完成時之前記過 WARN 才記一則 INFO（沒有記過 WARN 的門不記）"),
                () -> assertTrue(infos.stream().allMatch(CollapseLogCapture.Entry::marked), "INFO 帶 LOG_MARKER"));
    }

    /**
     * spec §10.8：F3d 的「塌縮」與「Nether／End 選門」訊息，key 在 {@code message.quantumchamber.m5.*}，zh_tw 與 en_us 同時存在；
     * zh_tw 逐字等於 spec。
     */
    @Test void f3dMessagesExistInBothLanguages() throws Exception {
        var zh = lang("zh_tw"); var en = lang("en_us");
        var dimension = CandidateDoorInteraction.MESSAGE_DIMENSION_DOOR_UNAVAILABLE;
        assertAll(
                () -> assertTrue(CollapseService.MESSAGE_COLLAPSED.startsWith("message.quantumchamber.m5.")),
                () -> assertEquals("量子態塌縮。", text(zh, CollapseService.MESSAGE_COLLAPSED)),
                () -> assertFalse(text(en, CollapseService.MESSAGE_COLLAPSED).isBlank(), "en_us 有翻譯"),
                () -> assertTrue(dimension.startsWith("message.quantumchamber.m5.")),
                () -> assertEquals("此維度的宇宙門尚未開放。", text(zh, dimension)),
                () -> assertFalse(text(en, dimension).isBlank(), "en_us 有翻譯"),
                () -> assertEquals(zh.keySet(), en.keySet(), "兩個語言檔的 key 集合相同"));
    }

    /** F4a（spec §10.8）：「進入 SEALED」的訊息，key 在 {@code message.quantumchamber.m5.*}，zh_tw 逐字等於 spec，en_us 有翻譯。 */
    @Test void sealedMessageExistsInBothLanguages() throws Exception {
        var zh = lang("zh_tw"); var en = lang("en_us");
        assertAll(
                () -> assertEquals("message.quantumchamber.m5.sealed", CollapseService.MESSAGE_SEALED),
                () -> assertEquals("量子通道無法開啟，已封存。", text(zh, CollapseService.MESSAGE_SEALED)),
                () -> assertFalse(text(en, CollapseService.MESSAGE_SEALED).isBlank(), "en_us 有翻譯"),
                () -> assertEquals(zh.keySet(), en.keySet(), "兩個語言檔的 key 集合相同"));
    }

    /**
     * F4a（spec §11.2 的原因表）：可重試的 ALLOCATION_IO_FAILED、PROJECTION_FAILED 以邊緣觸發＋退避重試；MATERIALIZE_FAILED、AUTHORITY_UNREADABLE
     * 只在重啟之後（開服當下就在 journal）、每個 server instance 一次；永久原因與 TRR 永不重試。逐列寫明，不由 {@link SealedReason} 的旗標反推。
     */
    @Test void sealedRetryFollowsTheReasonTable() {
        var backoff = Set.of(SealedReason.ALLOCATION_IO_FAILED, SealedReason.PROJECTION_FAILED);
        var restartOnce = Set.of(SealedReason.MATERIALIZE_FAILED, SealedReason.AUTHORITY_UNREADABLE);
        var never = Set.of(SealedReason.EXISTING_INELIGIBLE, SealedReason.AUTHORITY_CORRUPT, SealedReason.PROJECTION_CONFLICT,
                SealedReason.TRANSFER_RECOVERY_REQUIRED);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertEquals(Set.of(SealedReason.values()), union(backoff, restartOnce, never), "表涵蓋每一個原因"));
        for (var reason : SealedReason.values()) for (boolean startup : new boolean[] {false, true}) for (boolean consumed : new boolean[] {false, true}) {
            CollapseService.SealedRetry expected = backoff.contains(reason) ? CollapseService.SealedRetry.EDGE_WITH_BACKOFF
                    : restartOnce.contains(reason) && startup && !consumed ? CollapseService.SealedRetry.EDGE_ONCE_AFTER_RESTART
                    : CollapseService.SealedRetry.NONE;
            cases.add(() -> assertEquals(expected, CollapseService.sealedRetry(reason, startup, consumed),
                    reason + " startup=" + startup + " consumed=" + consumed));
        }
        assertAll(cases);
    }

    /** F4a（spec §6.2）：RESOLVE 的等待持續超過 600 ticks 時記一次 WARN；不在等待、還沒超過、或已記過都不記。 */
    @Test void resolveWaitWarnsOnceAfterSixHundredTicks() {
        assertAll(
                () -> assertFalse(CollapseService.waitWarning(-1, 5000, false), "不在等待"),
                () -> assertFalse(CollapseService.waitWarning(100, 700, false), "恰好 600 ticks：還沒超過"),
                () -> assertTrue(CollapseService.waitWarning(100, 701, false), "超過 600 ticks：記一次"),
                () -> assertFalse(CollapseService.waitWarning(100, 5000, true), "這次等待已記過"));
    }

    /**
     * F4a（spec §11.2、§7.3）：進入 SEALED 時門改為關閉，門口有 entity 時改為一般開門；「門口」是 25 格門（區域 x、y 1..5，z 0）的整格範圍，
     * 四個朝向都只涵蓋門板，不含艙內與門外的格。
     */
    @Test void sealedDoorClosesUnlessTheDoorwayIsOccupied() {
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.CLOSED, CollapseService.sealedDoor(false), "門口沒有 entity：關門"));
        cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN, CollapseService.sealedDoor(true), "門口有 entity：一般開門"));
        for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            var frame = new ChamberFrame(new BlockPos(7, 70, 9), facing);
            var box = CollapseService.doorwayBox(frame);
            cases.add(() -> assertEquals(25.0, box.getLengthX() * box.getLengthY() * box.getLengthZ(), 1e-9, facing + "：恰為 25 格"));
            for (int x = 1; x <= 5; x++) for (int y = 1; y <= 5; y++) {
                var cell = dev.quantumchamber.chamber.ChamberGeometry.localToWorld(frame, x, y, 0);
                cases.add(() -> assertTrue(box.contains(Vec3d.ofCenter(cell)), facing + "：門格 " + cell));
            }
            var inside = dev.quantumchamber.chamber.ChamberGeometry.localToWorld(frame, 3, 3, 1);
            var outside = frame.controllerPos().offset(facing, 1).down(3);
            cases.add(() -> assertFalse(box.contains(Vec3d.ofCenter(inside)), facing + "：艙內的格不算門口"));
            cases.add(() -> assertFalse(box.contains(Vec3d.ofCenter(outside)), facing + "：門外的格不算門口"));
        }
        assertAll(cases);
    }

    /** F4a：進入 SEALED 的待套用門與終局之後的門共用同一套處置，log 的字句依目標區分（帶 LOG_MARKER、chamber 與 session）。 */
    @Test void sealedPendingDoorUsesItsOwnWording() {
        var frame = new ChamberFrame(new BlockPos(7, 70, 9), Direction.NORTH);
        var door = new CollapseService.PendingDoor(CHAMBER, SESSION, World.OVERWORLD, frame, 0, CollapseService.DoorGoal.CLOSE_ON_SEALED);
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            CollapseService.applyOutcome(door, Outcome.ROLLED_BACK, 100);
            CollapseService.applyOutcome(door, Outcome.APPLIED, 100);
            logs = capture.entries();
        }
        assertAll(
                () -> assertEquals(List.of(Level.WARN, Level.INFO), logs.stream().map(CollapseLogCapture.Entry::level).toList()),
                () -> assertTrue(logs.getFirst().message().startsWith("進入 SEALED 之後原艙門還沒套用"), logs.getFirst().message()),
                () -> assertTrue(logs.getLast().message().startsWith("進入 SEALED 之後的原艙門已套用"), logs.getLast().message()),
                () -> assertTrue(logs.stream().allMatch(entry -> entry.marked() && entry.message().contains("chamber=" + CHAMBER)), logs::toString),
                () -> assertTrue(logs.getFirst().message().contains("session=" + SESSION)));
    }

    @SafeVarargs
    private static <T> Set<T> union(Set<T>... sets) {
        var result = new java.util.HashSet<T>();
        for (var set : sets) result.addAll(set);
        return result;
    }

    private static JsonObject lang(String name) throws Exception {
        try (var stream = CollapseService.class.getResourceAsStream("/assets/quantumchamber/lang/" + name + ".json")) {
            assertNotNull(stream, "語言檔不在 classpath：" + name);
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }
    private static String text(JsonObject lang, String key) {
        assertTrue(lang.has(key), "缺少 key：" + key);
        return lang.get(key).getAsString();
    }

    // ---- fixtures（只用 public 建構子） ----

    static SessionRecoveryRecord measured(DimensionRole role, int choice) {
        var world = switch (role) { case OVERWORLD -> World.OVERWORLD; case NETHER -> World.NETHER; case END -> World.END; };
        var origin = new ChamberOriginAuthority(CHAMBER, world.getValue(), role, new BlockPos(7, 70, 9), Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var people = List.of(participant(A, 0), participant(B, 1));
        var context = new CandidatePolicySnapshot(Identifier.of("quantumchamber:m4_v1"), 1, 1, 5, 20, 75, -1, 16384, bytes(41),
                new SourceFamilyRef.Vanilla(world, role));
        var ledger = List.of(
                new CandidateLedgerEntry(new DoorKey(SESSION, -2, DoorKey.DoorWallSide.NEGATIVE_LATERAL), new QuantumCandidate.Source(id(1))),
                new CandidateLedgerEntry(new DoorKey(SESSION, -2, DoorKey.DoorWallSide.POSITIVE_LATERAL), new QuantumCandidate.New(id(3),
                        new NewUniverseIntent(bytes(21), GeneratorProfile.VANILLA_OVERWORLD_SHARED_SEED_V1, 1, bytes(31)))),
                new CandidateLedgerEntry(new DoorKey(SESSION, 5, DoorKey.DoorWallSide.NEGATIVE_LATERAL), new QuantumCandidate.Existing(id(2), EXISTING_ID)));
        var entry = ledger.get(choice);
        return new SessionRecoveryRecord(SESSION, CHAMBER, origin, people, List.of(new SessionRecoveryRecord.SpaceLease(3, new BlockBox(0, 0, 0, 6, 6, 95))),
                SessionState.MEASURED, false, SessionSemantics.LATERAL_BUFF_MAINTAINED, Optional.of(context), ledger,
                Optional.of(new CandidateSelection.Selected(entry.doorKey(), entry.candidate().candidateId(), A, 0, 1)));
    }
    static SessionRecoveryRecord collapsing(SessionRecoveryRecord measured, CollapseDestination destination) {
        return measured.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(40, destination));
    }
    static SessionRecoveryRecord returned(SessionRecoveryRecord record, UUID... players) {
        var set = Set.of(players);
        var people = record.participants().stream().map(person -> set.contains(person.playerUuid()) ? new Participant(person.playerUuid(),
                person.sourcePosition(), person.sourceVelocity(), person.yaw(), person.pitch(), person.quantumStateSnapshot(), true) : person).toList();
        return record.withProgress(people, record.spaceLeases(), record.state(), record.restoreEntryEffectOnReturn());
    }
    static SessionRecoveryRecord cleared(SessionRecoveryRecord record) {
        return record.withProgress(record.participants(), List.of(), record.state(), record.restoreEntryEffectOnReturn());
    }
    private static Set<UUID> ordered(UUID... players) { return new LinkedHashSet<>(List.of(players)); }
    private static Participant participant(UUID player, int index) {
        return new Participant(player, new Vec3d(7.5 + index, 66, 9.5), Vec3d.ZERO, 90, -15, new NbtCompound(), false);
    }
    private static CandidateId id(int seed) { return new CandidateId(bytes(seed)); }
    private static CandidateBytes bytes(int seed) {
        byte[] result = new byte[32]; for (int i = 0; i < result.length; i++) result[i] = (byte) (seed + i); return new CandidateBytes(result);
    }
}
