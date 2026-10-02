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
     */
    @Test void pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings() {
        var rejected = "門的寫入被拒（ROLLED_BACK）";
        var first = CollapseService.doorStep(Outcome.ROLLED_BACK, false, null);
        var second = CollapseService.doorStep(Outcome.ROLLED_BACK, first.retried(), first.reason());
        var third = CollapseService.doorStep(Outcome.ROLLBACK_FAILED, second.retried(), second.reason());
        var incomplete = CollapseService.doorStep(Outcome.DOOR_INCOMPLETE, false, null);
        assertAll(
                () -> assertEquals(new CollapseService.DoorStep(false, rejected, true, false, true), first, "第一次被拒：記 WARN、不退避、下一個 tick 重試"),
                () -> assertEquals(new CollapseService.DoorStep(false, rejected, false, true, true), second, "再被拒：退避，同一原因不再記 WARN"),
                () -> assertEquals(new CollapseService.DoorStep(false, "門的寫入被拒（ROLLBACK_FAILED）", true, true, true), third, "原因改變：再記一次"),
                () -> assertEquals(new CollapseService.DoorStep(false, "門板不是完整的 25 格 bulkhead，等結構恢復", true, true, false), incomplete, "結構性：退避"),
                () -> assertFalse(CollapseService.doorStep(Outcome.DOOR_INCOMPLETE, false, incomplete.reason()).warn(), "結構性的同一原因去重"),
                () -> assertEquals(new CollapseService.DoorStep(false, null, false, false, true), CollapseService.doorStep(Outcome.NOT_LOADED, true, rejected), "未載入：只等待"),
                () -> assertTrue(CollapseService.doorStep(Outcome.APPLIED, true, rejected).done()),
                () -> assertTrue(CollapseService.doorStep(Outcome.UNCHANGED, false, null).done()));
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
