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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
            var gate = new CountingGate(row.gateOpen());
            assertEquals(row.expected(), CollapsePhase.derive(row.view(), row.releaseComplete(), row.resolved(), false, gate).phase(), row.label());
            assertEquals(row.gateConsulted() ? 1 : 0, gate.edge, "閘門只在前面的條件都成立時才評估（評估會消耗邊緣觸發）：" + row.label());
            assertEquals(0, gate.stillOpen, "上一個 tick 不在 PREPARE：只評估邊緣觸發：" + row.label());
        });
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapsePhase.derive(measured(DimensionRole.OVERWORLD, NEW), true, true, false,
                new CountingGate(true)), "MEASURED 沒有塌縮的推導階段"));
        var sealed = cleared(returned(universe, A, B)).withCollapse(SessionState.SEALED,
                cleared(returned(universe, A, B)).collapse().sealed(SealedReason.PROJECTION_FAILED));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapsePhase.derive(sealed, true, true, false, new CountingGate(true)),
                "SEALED 沒有推導階段"));
        assertAll(cases);
    }

    /** PREPARE 閘門的 fixture：兩種評估各自計數，回傳同一個值（F4b-2 fix round 1，quality Q-M6）。 */
    private static final class CountingGate implements CollapsePhase.Gate {
        final boolean open;
        int stillOpen, edge;

        CountingGate(boolean open) { this.open = open; }

        @Override public boolean stillOpen() { stillOpen++; return open; }

        @Override public boolean edge() { edge++; return open; }
    }

    /**
     * F4b-2（spec §7.3；F3d quality m-6(b)）：推導回傳相對於上一個 tick 的 PREPARE 轉換，呼叫端在轉換點做事（離開 PREPARE 時釋放目的世界的 ticket），
     * 不靠閘門的副作用。上一個 tick 在 PREPARE、這個 tick 閘門不成立 → 離開（退回 WAITING）；推導回到 PREPARE 之前的階段也是離開，而且不評估閘門。
     */
    @Test void collapsePhaseReportsThePrepareTransition() {
        var settled = cleared(returned(collapsing(measured(DimensionRole.OVERWORLD, NEW), new CollapseDestination.Universe(EXISTING_ID)), A, B));
        record Row(String label, SessionRecoveryRecord view, boolean resolved, boolean preparing, boolean gateOpen, CollapsePhase phase,
                CollapsePhase.Transition transition, boolean gateConsulted) {}
        var rows = List.of(
                new Row("WAITING 時閘門成立：進入", settled, true, false, true, CollapsePhase.PREPARE, CollapsePhase.Transition.ENTER_PREPARE, true),
                new Row("PREPARE 中閘門仍成立：沒有轉換", settled, true, true, true, CollapsePhase.PREPARE, CollapsePhase.Transition.NONE, true),
                new Row("PREPARE 中閘門失效：離開，退回 WAITING", settled, true, true, false, CollapsePhase.WAITING, CollapsePhase.Transition.LEAVE_PREPARE, true),
                new Row("WAITING 時閘門不成立：沒有轉換", settled, true, false, false, CollapsePhase.WAITING, CollapsePhase.Transition.NONE, true),
                new Row("PREPARE 中目的地不再確定：離開，不評估閘門", settled, false, true, true, CollapsePhase.RESOLVE, CollapsePhase.Transition.LEAVE_PREPARE, false),
                new Row("PREPARE 中有人未返還（不會發生，仍要離開）", collapsing(measured(DimensionRole.OVERWORLD, NEW),
                        new CollapseDestination.Universe(EXISTING_ID)), true, true, true, CollapsePhase.RETURN_PLAYERS, CollapsePhase.Transition.LEAVE_PREPARE, false));
        var cases = new ArrayList<Executable>();
        for (var row : rows) cases.add(() -> {
            var gate = new CountingGate(row.gateOpen());
            var derived = CollapsePhase.derive(row.view(), true, row.resolved(), row.preparing(), gate);
            assertEquals(new CollapsePhase.Derivation(row.phase(), row.transition()), derived, row.label());
            int consulted = row.gateConsulted() ? 1 : 0;
            // 上一個 tick 在 PREPARE 時只看來源艙的條件，否則只評估邊緣觸發（F4b-2 fix round 1，quality Q-M6：由 derive 選擇）。
            assertEquals(row.preparing() ? consulted : 0, gate.stillOpen, "來源艙條件的評估次數：" + row.label());
            assertEquals(row.preparing() ? 0 : consulted, gate.edge, "邊緣觸發的評估次數：" + row.label());
        });
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapsePhase.Derivation(CollapsePhase.WAITING, CollapsePhase.Transition.ENTER_PREPARE),
                "進入 PREPARE 的階段必須是 PREPARE"));
        cases.add(() -> assertThrows(IllegalArgumentException.class, () -> new CollapsePhase.Derivation(CollapsePhase.PREPARE, CollapsePhase.Transition.LEAVE_PREPARE),
                "離開 PREPARE 的階段不可能是 PREPARE"));
        assertAll(cases);
    }

    /**
     * F4b-2（spec §9.1）＋F5-1（F4b-2 spec S-M1）：預載的 chunk 由傳送時 FULL 檢查的推導算出（{@code TransferSafety.fullChunkRange}）：艙體 bbox（整格的連續座標）水平外擴
     * {@code PassageTransferPolicy.REACH}＝1 格，再外擴 2 格，所以 x 從 {@code minX − 3} 到 {@code maxX + 4} 的每一格（max 側多 1 格是連續座標的 max 加 1）。期望值由 test
     * 逐格算出（每一格的 chunk），四個朝向、跨 chunk 邊界與負座標都一樣；沒有重複。
     */
    @Test void preloadIsTheTransferFullCheckDerivationOfTheChamberReach() {
        var cases = new ArrayList<Executable>();
        for (var controller : List.of(new BlockPos(7, 70, 9), new BlockPos(-17, 70, -2), new BlockPos(19, 64, 33), new BlockPos(-1, 80, 15)))
            for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                var frame = new ChamberFrame(controller, facing);
                var bounds = dev.quantumchamber.chamber.ChamberGeometry.bounds(frame);
                var expected = new java.util.HashSet<net.minecraft.util.math.ChunkPos>();
                for (int x = bounds.getMinX() - 3; x <= bounds.getMaxX() + 4; x++)
                    for (int z = bounds.getMinZ() - 3; z <= bounds.getMaxZ() + 4; z++) expected.add(new net.minecraft.util.math.ChunkPos(new BlockPos(x, 0, z)));
                var actual = CollapseService.preloadChunks(frame);
                cases.add(() -> assertEquals(expected, Set.copyOf(actual), controller + " " + facing));
                cases.add(() -> assertEquals(actual.size(), Set.copyOf(actual).size(), "沒有重複：" + controller + " " + facing));
            }
        cases.add(() -> assertEquals(1, PassageTransferPolicy.REACH, "艙體 bbox 外擴 1 格，再由 FULL 檢查的推導外擴 2 格"));
        // bbox 從 x=16 開始（左邊界貼著 chunk 邊界）：外擴之後涵蓋 chunk x=0。
        var edge = new ChamberFrame(new BlockPos(19, 64, 40), Direction.NORTH);
        cases.add(() -> assertEquals(16, dev.quantumchamber.chamber.ChamberGeometry.bounds(edge).getMinX(), "fixture：bbox 的 minX 貼著 chunk 邊界"));
        cases.add(() -> assertTrue(CollapseService.preloadChunks(edge).stream().anyMatch(chunk -> chunk.x == 0), "外擴之後涵蓋鄰 chunk"));
        assertAll(cases);
    }

    /**
     * F5-1（F4b-2 spec S-M1）：跨越時玩家的 bbox（腳在門口 local x∈[1,6)、y=1，local z 從 0.5 往外到 −0.69，也就是上一個 tick 還在中線內側、這個 tick 往外少於 1.2 格；−0.7 恰在邊界上，浮點誤差會讓 bbox 的邊超出一點）
     * 由傳送的 FULL 檢查（{@code TransferSafety.fullChunkRange}）要求的每一個 chunk，都在預載範圍內。掃過 controller 的 x、z 在 −18..18 的每一格與四個朝向，所以 bbox 的
     * 每一個邊都貼過 chunk 邊界；F4b-2 的「艙體 bbox 外擴 2 格」在這裡會漏掉 chunk（S-M1 的 RED）。
     */
    @Test void preloadCoversTheTransferFullCheckOfEveryCrossingBox() {
        var missing = new ArrayList<String>();
        for (int cx = -18; cx <= 18; cx++) for (int cz = -18; cz <= 18; cz++)
            for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
                var frame = new ChamberFrame(new BlockPos(cx, 70, cz), facing);
                var preloaded = Set.copyOf(CollapseService.preloadChunks(frame));
                for (double x : new double[] {1.0, 1.3, 3.5, 5.7, 5.999}) for (double z : new double[] {0.499, 0.2, 0.0, -0.3, -0.69}) {
                    var feet = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, x, 1, z);
                    var box = new net.minecraft.util.math.Box(feet.x - 0.3, feet.y, feet.z - 0.3, feet.x + 0.3, feet.y + 1.8, feet.z + 0.3);
                    var range = dev.quantumchamber.universe.TransferSafety.fullChunkRange(box);
                    for (int chunkX = range.minX(); chunkX <= range.maxX(); chunkX++) for (int chunkZ = range.minZ(); chunkZ <= range.maxZ(); chunkZ++)
                        if (!preloaded.contains(new net.minecraft.util.math.ChunkPos(chunkX, chunkZ)) && missing.size() < 10)
                            missing.add(frame + " local(" + x + ",1," + z + ") 少 chunk (" + chunkX + "," + chunkZ + ")");
                }
            }
        assertTrue(missing.isEmpty(), "預載沒有涵蓋跨越時 FULL 檢查要求的 chunk：" + missing);
    }

    /**
     * F4b-2（spec §9.2、§6.5；F4b-1 交下的寫入器結果）：投影的純判斷。寫入器的結果：寫完繼續；玩家擋住、chunk 不是都 FULL 等下一個 tick；驗證或寫入失敗進
     * SEALED。確認之後的 PENDING 決定寫法：新 key 與既有 PENDING 照第一次寫入、既有 COMPLETE 只修復，沒有確認的結果不能決定寫法。§9.2 的衝突只看另一座艙
     * （或另一個宇宙）的 COMPLETE。checkpoint 的相關 chunks 是 7×7×7 涵蓋的 chunk（跨 chunk、負座標也是）。
     */
    @Test void prepareDecisionsFollowTheWriterAndLedgerResults() {
        var cases = new ArrayList<Executable>();
        for (var status : ProjectionWriter.Status.values()) {
            var expected = switch (status) {
                case WRITTEN -> CollapseService.WriteStep.PROCEED;
                case BLOCKED_BY_PLAYER, NOT_LOADED -> CollapseService.WriteStep.WAIT;
                case VERIFY_FAILED, WRITE_FAILED -> CollapseService.WriteStep.SEAL;
            };
            cases.add(() -> assertEquals(expected, CollapseService.writeStep(status), status.name()));
        }
        cases.add(() -> assertEquals(ProjectionWriter.Mode.FIRST_WRITE, CollapseService.writeMode(CollapseAuthorities.WriteResult.Status.WRITTEN), "新 key"));
        cases.add(() -> assertEquals(ProjectionWriter.Mode.FIRST_WRITE, CollapseService.writeMode(CollapseAuthorities.WriteResult.Status.UNCHANGED), "既有 PENDING"));
        cases.add(() -> assertEquals(ProjectionWriter.Mode.REPAIR, CollapseService.writeMode(CollapseAuthorities.WriteResult.Status.ALREADY_COMPLETE), "既有 COMPLETE"));
        for (var status : List.of(CollapseAuthorities.WriteResult.Status.UNCONFIRMED, CollapseAuthorities.WriteResult.Status.WRITE_FAILED,
                CollapseAuthorities.WriteResult.Status.CORRUPT, CollapseAuthorities.WriteResult.Status.UNREADABLE))
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> CollapseService.writeMode(status), "沒有確認：" + status));
        var frame = new ChamberFrame(new BlockPos(-17, 70, -2), Direction.WEST);
        var universe = UniverseId.of(UUID.fromString("00000000-0000-8000-8000-0000000000f1"));
        var other = UniverseId.of(UUID.fromString("00000000-0000-8000-8000-0000000000f2"));
        java.util.function.BiFunction<UUID, UniverseId, java.util.function.Function<ProjectionLedger.Entry.Status, ProjectionLedger.Entry>> entry =
                (chamber, id) -> status -> new ProjectionLedger.Entry(chamber, id, DimensionRole.OVERWORLD, frame.controllerPos(), frame.outwardFacing(), status);
        var ownPending = entry.apply(CHAMBER, universe).apply(ProjectionLedger.Entry.Status.PENDING);
        var ownComplete = entry.apply(CHAMBER, universe).apply(ProjectionLedger.Entry.Status.COMPLETE);
        var otherPending = entry.apply(A, universe).apply(ProjectionLedger.Entry.Status.PENDING);
        var otherComplete = entry.apply(B, universe).apply(ProjectionLedger.Entry.Status.COMPLETE);
        var ownChamberOtherUniverse = entry.apply(CHAMBER, other).apply(ProjectionLedger.Entry.Status.COMPLETE);
        cases.add(() -> assertEquals(Optional.empty(), CollapseService.conflict(List.of(ownPending, ownComplete, otherPending), CHAMBER, universe),
                "本艙在這個宇宙的 entry 與別人的 PENDING 都不算"));
        cases.add(() -> assertEquals(Optional.of(otherComplete), CollapseService.conflict(List.of(ownComplete, otherPending, otherComplete), CHAMBER, universe),
                "另一座艙的 COMPLETE 是衝突"));
        cases.add(() -> assertEquals(Optional.of(ownChamberOtherUniverse), CollapseService.conflict(List.of(ownChamberOtherUniverse), CHAMBER, universe),
                "同一座艙在另一個宇宙的 COMPLETE（同一個世界不會發生）也不是本 key"));
        var bounds = dev.quantumchamber.chamber.ChamberGeometry.bounds(frame);
        var expected = new java.util.HashSet<net.minecraft.util.math.ChunkPos>();
        for (int x = bounds.getMinX(); x <= bounds.getMaxX(); x++)
            for (int z = bounds.getMinZ(); z <= bounds.getMaxZ(); z++) expected.add(new net.minecraft.util.math.ChunkPos(new BlockPos(x, 0, z)));
        cases.add(() -> assertEquals(expected, CollapseService.volumeChunks(frame), "checkpoint 的相關 chunks"));
        cases.add(() -> assertEquals(4, expected.size(), "fixture：跨兩個 chunk 邊界"));
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
     * <p>F4a-0（quality re-review m-3）與 fix round 1（spec m-2）：再以 production 的 {@link OriginDoors#applyOutcome}、
     * {@link OriginDoors#applyFailure}（{@code applyDoor} 與兩處套用例外的 catch 實際呼叫的同一份接線）驅動一扇真的 {@code PendingDoor} 走過一串
     * 結果，並擷取 {@code quantumchamber} logger 的 log，鎖住實際的記錄行為：只有 {@code DoorStep.warn()} 為真的那幾次記 WARN（帶 {@code LOG_MARKER}、
     * chamber 與 session，套用例外附 stack），門記下的原因決定下一次的去重，退避照 {@code backOff}；完成時回 true，之前記過 WARN 才記一則 INFO。
     */
    @Test void pendingDoorRetriesOnceThenBacksOffAndDedupesWarnings() {
        var rejected = "門的寫入被拒（ROLLED_BACK）";
        var thrownReason = "套用失敗：java.lang.IllegalStateException: x";
        var first = OriginDoors.doorStep(Outcome.ROLLED_BACK, false, null);
        var second = OriginDoors.doorStep(Outcome.ROLLED_BACK, first.retried(), first.reason());
        var third = OriginDoors.doorStep(Outcome.ROLLBACK_FAILED, second.retried(), second.reason());
        var incomplete = OriginDoors.doorStep(Outcome.DOOR_INCOMPLETE, false, null);
        var frame = new ChamberFrame(new BlockPos(7, 70, 9), Direction.NORTH);
        var door = new OriginDoors.PendingDoor(CHAMBER, SESSION, World.OVERWORLD, frame, 0);
        // 沒有記過 WARN 就完成的門：不記 INFO。
        var quiet = new OriginDoors.PendingDoor(B, A, World.OVERWORLD, frame, 0);
        var warned = new ArrayList<Integer>();
        var reasons = new ArrayList<String>();
        var doneBeforeApplied = new ArrayList<Boolean>();
        boolean appliedDone, quietDone;
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            for (var outcome : List.of(Outcome.ROLLED_BACK, Outcome.ROLLED_BACK, Outcome.ROLLBACK_FAILED, Outcome.NOT_LOADED, Outcome.DOOR_INCOMPLETE,
                    Outcome.DOOR_INCOMPLETE)) {
                int before = capture.count(Level.WARN);
                doneBeforeApplied.add(OriginDoors.applyOutcome(door, outcome, 100));
                warned.add(capture.count(Level.WARN) - before);
                reasons.add(door.warned);
            }
            // 套用丟出例外（tick 與 openDoor 的 catch）：同一原因去重、一律退避。
            for (int i = 0; i < 2; i++) {
                int before = capture.count(Level.WARN);
                OriginDoors.applyFailure(door, new IllegalStateException("x"), 100);
                warned.add(capture.count(Level.WARN) - before);
                reasons.add(door.warned);
            }
            appliedDone = OriginDoors.applyOutcome(door, Outcome.APPLIED, 100);
            quietDone = OriginDoors.applyOutcome(quiet, Outcome.UNCHANGED, 100);
            logs = capture.entries();
        }
        var warnings = logs.stream().filter(entry -> entry.level() == Level.WARN).toList();
        var infos = logs.stream().filter(entry -> entry.level() == Level.INFO).toList();
        assertAll(
                () -> assertEquals(new OriginDoors.DoorStep(false, rejected, true, false, true), first, "第一次被拒：記 WARN、不退避、下一個 tick 重試"),
                () -> assertEquals(new OriginDoors.DoorStep(false, rejected, false, true, true), second, "再被拒：退避，同一原因不再記 WARN"),
                () -> assertEquals(new OriginDoors.DoorStep(false, "門的寫入被拒（ROLLBACK_FAILED）", true, true, true), third, "原因改變：再記一次"),
                () -> assertEquals(new OriginDoors.DoorStep(false, "門板不是完整的 25 格 bulkhead，等結構恢復", true, true, false), incomplete, "結構性：退避"),
                () -> assertFalse(OriginDoors.doorStep(Outcome.DOOR_INCOMPLETE, false, incomplete.reason()).warn(), "結構性的同一原因去重"),
                () -> assertEquals(new OriginDoors.DoorStep(false, null, false, false, true), OriginDoors.doorStep(Outcome.NOT_LOADED, true, rejected), "未載入：只等待"),
                () -> assertTrue(OriginDoors.doorStep(Outcome.APPLIED, true, rejected).done()),
                () -> assertTrue(OriginDoors.doorStep(Outcome.UNCHANGED, false, null).done()),
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

    /**
     * F5-1（spec §10.8）：通道開啟、傳送失敗與通道結束的訊息，key 在 {@code message.quantumchamber.m5.*}，zh_tw 逐字等於 spec，en_us 有翻譯。F6-1 Step 0（F5-3 quality n-1）
     * 加上到達目的宇宙的 title（§10.8、§20.8：NEW、EXISTING 各一則；常數在 {@link PassageService}）。
     */
    @Test void passageMessagesExistInBothLanguages() throws Exception {
        var zh = lang("zh_tw"); var en = lang("en_us");
        var cases = new ArrayList<Executable>();
        for (var row : List.of(List.of(CollapseService.MESSAGE_PASSAGE_OPENED, "message.quantumchamber.m5.passage_opened", "量子通道已開啟：穿過門口的量子膜即可前往。"),
                List.of(CollapseService.MESSAGE_PASSAGE_TRANSFER_FAILED, "message.quantumchamber.m5.passage_transfer_failed", "通道傳送失敗，你仍在原世界。"),
                List.of(CollapseService.MESSAGE_PASSAGE_CLOSED, "message.quantumchamber.m5.passage_closed", "量子通道已關閉。"),
                List.of(PassageService.MESSAGE_ARRIVED_NEW, "message.quantumchamber.m5.arrived_new", "你已進入新的宇宙。"),
                List.of(PassageService.MESSAGE_ARRIVED_EXISTING, "message.quantumchamber.m5.arrived_existing", "你已進入另一個宇宙。"))) {
            cases.add(() -> assertEquals(row.get(1), row.get(0)));
            cases.add(() -> assertEquals(row.get(2), text(zh, row.get(0))));
            cases.add(() -> assertFalse(text(en, row.get(0)).isBlank(), "en_us 有翻譯：" + row.get(0)));
        }
        cases.add(() -> assertEquals(zh.keySet(), en.keySet(), "兩個語言檔的 key 集合相同"));
        assertAll(cases);
    }

    /**
     * F6-1 Step 0（F5-3 quality n-1，spec §20.8）：到達提示的 key 依選中的候選（{@code PassageService.arrivalKey}，純函式）：NEW「你已進入新的宇宙。」、EXISTING「你已進入另一個宇宙。」；
     * SOURCE 與沒有選中的候選為空（{@code arrived} 不顯示、記 WARN；PASSAGE_READY 的 record 驗證讓 production 走不到）。
     */
    @Test void arrivalKeyFollowsTheSelectedCandidate() {
        assertAll(
                () -> assertEquals(Optional.of(PassageService.MESSAGE_ARRIVED_NEW), PassageService.arrivalKey(measured(DimensionRole.OVERWORLD, NEW).selectedCandidate()), "NEW"),
                () -> assertEquals(Optional.of(PassageService.MESSAGE_ARRIVED_EXISTING), PassageService.arrivalKey(measured(DimensionRole.OVERWORLD, EXISTING).selectedCandidate()),
                        "EXISTING"),
                () -> assertEquals(Optional.empty(), PassageService.arrivalKey(measured(DimensionRole.OVERWORLD, SOURCE).selectedCandidate()), "SOURCE"),
                () -> assertEquals(Optional.empty(), PassageService.arrivalKey(Optional.empty()), "沒有選中的候選"));
    }

    /**
     * F5-1（spec §10.3）：跨越判定的幾何，四個朝向都一樣。腳在門口（local x∈[1,6)、y∈[1,6)），local z 從中線內側（≥0.5）越到外側（&lt;0.5）才觸發；反方向、上一個 tick
     * 沒有觀察、已在外側、門口範圍外都不觸發。local 座標由 production 的 {@code ChamberSpaceCoordinates.localPosition} 換算，test 以 {@code position} 從 local 造出世界座標，
     * 再斷言換算回來的 local 與期望一致（換算本身也被鎖住）。
     */
    @Test void crossingGeometryWorksForEveryFacing() {
        var cases = new ArrayList<Executable>();
        for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            var frame = new ChamberFrame(new BlockPos(-17, 70, 33), facing);
            record Row(String name, double x, double y, double z, Double previous, boolean expected) {}
            for (var row : List.of(
                    new Row("中線內側越到外側", 3.5, 1, 0.45, 0.55, true),
                    new Row("門口的左右邊緣（x=1、x 接近 6）", 1.0, 1, 0.3, 0.7, true),
                    new Row("門口的右邊緣", 5.999, 2.5, 0.3, 0.7, true),
                    new Row("一個 tick 往外走很遠（沒有下限）", 3.5, 1, -2.5, 0.6, true),
                    new Row("反方向（由外往內）", 3.5, 1, 0.6, 0.4, false),
                    new Row("上一個 tick 已在外側", 3.5, 1, 0.3, 0.45, false),
                    new Row("上一個 tick 沒有觀察", 3.5, 1, 0.3, null, false),
                    new Row("還在中線內側", 3.5, 1, 0.5, 0.7, false),
                    new Row("門口範圍外（x<1）", 0.9, 1, 0.3, 0.7, false),
                    new Row("門口範圍外（x=6）", 6.0, 1, 0.3, 0.7, false),
                    new Row("門口範圍外（腳在 y<1）", 3.5, 0.9, 0.3, 0.7, false),
                    new Row("門口範圍外（腳在 y=6）", 3.5, 6.0, 0.3, 0.7, false))) {
                var world = dev.quantumchamber.chamber.ChamberSpaceCoordinates.position(frame, row.x(), row.y(), row.z());
                var local = dev.quantumchamber.chamber.ChamberSpaceCoordinates.localPosition(frame, world);
                cases.add(() -> assertEquals(row.z(), local.z, 1e-9, facing + " local z 換算：" + row.name()));
                cases.add(() -> assertEquals(row.expected(), PassageService.crosses(candidate(local, row.previous())), facing + "：" + row.name()));
            }
        }
        assertAll(cases);
    }

    /** F5-1（spec §10.3）：跨越的其他條件，每個條件單獨不成立就不觸發（幾何成立）；非參與者、已跨過、騎乘、旁觀、不在來源世界、離線或死亡、被其他 session 佔用。 */
    @Test void crossingRequiresEveryParticipantCondition() {
        var inside = new Vec3d(3.5, 1, 0.3);
        var base = candidate(inside, 0.7);
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertTrue(PassageService.crosses(base), "全部成立"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().participant(false).build()), "非參與者"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().alreadyCrossed(true).build()), "已跨過"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().exactOnlineAlive(false).build()), "離線、不是 exact 或死亡"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().spectator(true).build()), "旁觀"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().inSourceWorld(false).build()), "不在 exact 的來源世界"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().riding(true).build()), "騎乘中"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().occupiedElsewhere(true).build()), "被其他 session 佔用"));
        cases.add(() -> assertFalse(PassageService.crosses(base.toBuilder().local(null).build()), "這個 tick 觀察不到"));
        // F5-1 fix round 1（quality Q-m7）：builder 依名稱設定的每個欄位，與 record 的 canonical constructor 的位置一致（上面各列只改一個條件，兩個條件對調時看不出來）。
        cases.add(() -> assertEquals(new PassageService.Candidate(true, false, true, false, true, false, false, inside, 0.7), base, "全部成立的候選"));
        cases.add(() -> assertEquals(new PassageService.Candidate(false, true, false, true, false, true, true, null, null), base.toBuilder().participant(false)
                .alreadyCrossed(true).exactOnlineAlive(false).spectator(true).inSourceWorld(false).riding(true).occupiedElsewhere(true).local(null).previousLocalZ(null)
                .build(), "每個欄位都反過來"));
        assertAll(cases);
    }

    /**
     * F5-1 fix round 1（spec review S-m1；spec §10.3「上一個 tick 還在中線內側」）：跨越只認上一個 tick（now−1）記下的 local z。推進被略過的 tick 不會觀察，更早的觀察
     * （例如略過之前記下的中線內側）不算；同一個 tick 的觀察也不是「上一個 tick」。之後與這個 tick 中線外側的位置一起判斷：只有上一個 tick 的內側觀察觸發。
     */
    @Test void crossingUsesOnlyTheObservationOfThePreviousTick() {
        var observed = Map.of(A, new PassageService.ObservedZ(0.7, 99), B, new PassageService.ObservedZ(0.8, 96),
                SESSION, new PassageService.ObservedZ(0.6, 100));
        var previous = PassageService.previousTick(observed, 100);
        var outside = new Vec3d(3.5, 1, 0.3);
        assertAll(
                () -> assertEquals(Map.of(A, 0.7), previous, "只有 tick 99（now−1）的觀察"),
                () -> assertTrue(PassageService.crosses(candidate(outside, previous.get(A))), "上一個 tick 在內側：觸發"),
                () -> assertFalse(PassageService.crosses(candidate(outside, previous.get(B))), "推進被略過之前的內側觀察（tick 96）：不觸發"),
                () -> assertFalse(PassageService.crosses(candidate(outside, previous.get(SESSION))), "同一個 tick 的觀察不是上一個 tick：不觸發"),
                () -> assertEquals(Map.of(), PassageService.previousTick(Map.of(), 100), "沒有觀察"));
    }

    /** 幾何之外的條件全部成立的候選（參與者、在線而 exact、在來源世界；沒有跨過、不是旁觀、沒有騎乘、沒有被佔用）。 */
    private static PassageService.Candidate candidate(Vec3d local, Double previous) {
        return PassageService.Candidate.builder().participant(true).exactOnlineAlive(true).inSourceWorld(true).local(local).previousLocalZ(previous).build();
    }

    /**
     * F5-1（spec §10.6）：結束條件 (b)(c)(d) 依即時訊號；同時成立時依 (b)(c)(d) 的順序回報一個；來源艙沒有載入時無法判斷 (d)、不結束（(b)(c) 照常）；到期以 game time
     * 計（恰好到 expires 就結束）。
     */
    @Test void passageEndConditionsFollowTheSpecOrder() {
        var OK = CollapseService.SourceSignal.OK;
        var cases = new ArrayList<Executable>();
        cases.add(() -> assertEquals(Optional.empty(), PassageService.endCondition(false, 100, 1300, OK), "沒有任何結束條件"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.ALL_CROSSED), PassageService.endCondition(true, 100, 1300, OK), "(b) 全員都已跨過"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.EXPIRED), PassageService.endCondition(false, 1300, 1300, OK), "(c) 恰好到期"));
        cases.add(() -> assertEquals(Optional.empty(), PassageService.endCondition(false, 1299, 1300, OK), "(c) 還差 1 tick"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.SOURCE_LOW),
                PassageService.endCondition(false, 100, 1300, CollapseService.SourceSignal.LOW), "(d) 沒有供電"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.SOURCE_DISABLED),
                PassageService.endCondition(false, 100, 1300, CollapseService.SourceSignal.DISABLED), "(d) Origin 被停用"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.SOURCE_INVALID),
                PassageService.endCondition(false, 100, 1300, CollapseService.SourceSignal.INVALID), "(d) 結構失效"));
        cases.add(() -> assertEquals(Optional.empty(), PassageService.endCondition(false, 100, 1300, CollapseService.SourceSignal.NOT_LOADED), "沒有載入：不判斷 (d)"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.EXPIRED),
                PassageService.endCondition(false, 1300, 1300, CollapseService.SourceSignal.NOT_LOADED), "沒有載入時 (c) 照常"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.ALL_CROSSED),
                PassageService.endCondition(true, 1300, 1300, CollapseService.SourceSignal.LOW), "同時成立時回報 (b)"));
        cases.add(() -> assertEquals(Optional.of(PassageService.PassageEnd.EXPIRED),
                PassageService.endCondition(false, 1300, 1300, CollapseService.SourceSignal.LOW), "(c)(d) 同時成立時回報 (c)"));
        assertAll(cases);
    }

    /**
     * F5-1 fix round 1（spec review S-m2、quality Q-m2；spec §6 :260、§7.3）：提交 PASSAGE_READY 沒有完成之後，PREPARE 只在 per-session 退避期內暫停（不判斷、也不擁有原艙門）；
     * 退避期一過就恢復，不是整段 PREPARE 的單向閂。沒有 PREPARE、提交沒有失敗過時都不暫停。F5-2 Step 0（F5-1 fix round 1 quality re-review R-m1）：暫停綁在 Track 上、跟著
     * per-session 退避，退避期內閘門失效（丟棄 Prepare）、再進入 PREPARE（新的 Prepare）時仍然暫停。
     */
    @Test void readyFailurePausesThePrepareOnlyDuringTheBackoff() {
        var track = new CollapseService.Track();
        boolean noPrepare = track.readyPaused(100);
        track.prepare = new CollapseService.Prepare(100);
        boolean notFailed = track.readyPaused(100);
        track.readyRetryPending = true;
        track.backoff.failed(100);
        boolean withinTheBackoff = track.readyPaused(299);
        track.prepare = null;
        boolean afterLeavingThePrepare = track.readyPaused(150);
        track.prepare = new CollapseService.Prepare(160);
        boolean afterReenteringThePrepare = track.readyPaused(160);
        boolean afterTheBackoff = track.readyPaused(300);
        track.backoff.failed(300);
        boolean failedAgain = track.readyPaused(301);
        assertAll(
                () -> assertFalse(noPrepare, "沒有 PREPARE、提交也沒有失敗過"),
                () -> assertFalse(notFailed, "提交沒有失敗過"),
                () -> assertTrue(withinTheBackoff, "失敗之後的退避期內暫停"),
                () -> assertTrue(afterLeavingThePrepare, "退避期內閘門失效、Prepare 已丟棄：仍暫停（R-m1）"),
                () -> assertTrue(afterReenteringThePrepare, "退避期內再進入 PREPARE（新的 Prepare）：仍暫停，不套用 sealed 量子膜（R-m1）"),
                () -> assertFalse(afterTheBackoff, "退避期一過就恢復"),
                () -> assertTrue(failedAgain, "重試再失敗：再暫停一段退避期（400 ticks）"));
    }

    /** F5-1（spec §10.2「相關權威都不在未確認狀態」）：PASSAGE_READY 之前要確認的權威；NEW 另加 allocation ledger。 */
    @Test void readyWaitsForTheRelevantAuthorities() {
        var common = Set.of(CollapseAuthorities.Authority.CATALOG, CollapseAuthorities.Authority.DISCOVERY, CollapseAuthorities.Authority.PROJECTION_LEDGER);
        assertAll(
                () -> assertEquals(common, Set.copyOf(CollapseService.readyAuthorities(false)), "EXISTING"),
                () -> assertEquals(union(common, Set.of(CollapseAuthorities.Authority.ALLOCATION_LEDGER)), Set.copyOf(CollapseService.readyAuthorities(true)), "NEW"));
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
        cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.CLOSED, OriginDoors.sealedDoor(false), "門口沒有 entity：關門"));
        cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN, OriginDoors.sealedDoor(true), "門口有 entity：一般開門"));
        for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            var frame = new ChamberFrame(new BlockPos(7, 70, 9), facing);
            var box = OriginDoors.doorwayBox(frame);
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
        var door = new OriginDoors.PendingDoor(CHAMBER, SESSION, World.OVERWORLD, frame, 0, OriginDoors.DoorGoal.CLOSE_ON_SEALED);
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            OriginDoors.applyOutcome(door, Outcome.ROLLED_BACK, 100);
            OriginDoors.applyOutcome(door, Outcome.APPLIED, 100);
            logs = capture.entries();
        }
        assertAll(
                () -> assertEquals(List.of(Level.WARN, Level.INFO), logs.stream().map(CollapseLogCapture.Entry::level).toList()),
                () -> assertTrue(logs.getFirst().message().startsWith("進入 SEALED 之後原艙門還沒套用"), logs.getFirst().message()),
                () -> assertTrue(logs.getLast().message().startsWith("進入 SEALED 之後的原艙門已套用"), logs.getLast().message()),
                () -> assertTrue(logs.stream().allMatch(entry -> entry.marked() && entry.message().contains("chamber=" + CHAMBER)), logs::toString),
                () -> assertTrue(logs.getFirst().message().contains("session=" + SESSION)));
    }

    /**
     * F4b-3（spec §7 :336、§7.3「PREPARE 期間的開關門」、§11.2、§3 詮釋 3；補充第 2 點）：原艙門的攔截規則。只有 COLLAPSING、目的地是 UNIVERSE、PREPARE 擁有原艙門時
     * 才依身分處置（參與者終局再關門、艙內的非參與者一般開門、艙外的非參與者拒絕），roll-forward 尚未確認時一律拒絕；SEALED、一般狀態的階段、SOURCE 與 M1–M4 的狀態一律
     * 照一般門的邏輯。F5-1（§10.5、§7 :336）：PASSAGE_READY 時參與者結束通道（END_PASSAGE），非參與者（艙內或艙外）與 roll-forward 尚未確認時一律拒絕。F5-2（spec re-review n-2）：
     * PASSAGE_READY 而通道在記憶體內關閉（SEALED(TRANSFER_RECOVERY_REQUIRED) 的寫入被拒、待重試）時，門是一般開門，任何人都照一般門的邏輯（PASS）；其他狀態不看它。
     */
    @Test void doorActionFollowsThePrepareDoorRules() {
        var cases = new ArrayList<Executable>();
        for (var state : SessionState.values()) for (boolean universe : new boolean[] {false, true}) for (boolean pending : new boolean[] {false, true})
            for (boolean owns : new boolean[] {false, true}) for (boolean closed : new boolean[] {false, true}) for (var user : OriginDoors.DoorUser.values()) {
                OriginDoors.DoorAction expected;
                if (state == SessionState.PASSAGE_READY) expected = closed ? OriginDoors.DoorAction.PASS
                        : !pending && user == OriginDoors.DoorUser.PARTICIPANT ? OriginDoors.DoorAction.END_PASSAGE : OriginDoors.DoorAction.REFUSE;
                else if (state != SessionState.COLLAPSING || !universe || !owns) expected = OriginDoors.DoorAction.PASS;
                else if (pending) expected = OriginDoors.DoorAction.REFUSE;
                else expected = switch (user) {
                    case PARTICIPANT -> OriginDoors.DoorAction.END_AND_CLOSE;
                    case INSIDER -> OriginDoors.DoorAction.OPEN_FOR_INSIDER;
                    case OUTSIDER -> OriginDoors.DoorAction.REFUSE;
                };
                var row = state + " universe=" + universe + " rollForwardPending=" + pending + " prepareOwnsDoor=" + owns + " passageClosedInMemory=" + closed + " " + user;
                var want = expected;
                cases.add(() -> assertEquals(want, OriginDoors.doorAction(state, universe, pending, owns, closed, user), row));
                // 5 個參數的版本是 passageClosedInMemory 為假。
                if (!closed) cases.add(() -> assertEquals(want, OriginDoors.doorAction(state, universe, pending, owns, user), "5 個參數：" + row));
            }
        // 規則本身的幾個錨點（上面的期望值是同一條規則的另一種寫法，這幾列直接寫死）。
        cases.add(() -> assertEquals(OriginDoors.DoorAction.END_AND_CLOSE,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, false, true, OriginDoors.DoorUser.PARTICIPANT), "PREPARE 期間參與者關門"));
        // F4b-3 fix round 1（Nit N-1）：COLLAPSING＋UNIVERSE＋PREPARE 擁有原艙門時的另外兩種身分，以及 PREPARE 不擁有原艙門時的參與者。
        cases.add(() -> assertEquals(OriginDoors.DoorAction.OPEN_FOR_INSIDER,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, false, true, OriginDoors.DoorUser.INSIDER), "PREPARE 期間艙內的非參與者一般開門"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.REFUSE,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, false, true, OriginDoors.DoorUser.OUTSIDER), "PREPARE 期間艙外的非參與者被拒"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.PASS,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, false, false, OriginDoors.DoorUser.PARTICIPANT), "PREPARE 不擁有原艙門時參與者照一般門的邏輯"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.REFUSE,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, true, true, OriginDoors.DoorUser.PARTICIPANT), "roll-forward 尚未確認時 PREPARE 一律拒絕"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.PASS,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, true, false, OriginDoors.DoorUser.OUTSIDER), "一般狀態的階段照一般門的邏輯"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.PASS,
                OriginDoors.doorAction(SessionState.SEALED, true, true, true, OriginDoors.DoorUser.OUTSIDER), "SEALED 任何玩家都可以照一般方式開門"));
        // F5-1（§10.5）：通道期間。
        cases.add(() -> assertEquals(OriginDoors.DoorAction.END_PASSAGE,
                OriginDoors.doorAction(SessionState.PASSAGE_READY, true, false, false, OriginDoors.DoorUser.PARTICIPANT), "通道期間參與者關門結束通道"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.REFUSE,
                OriginDoors.doorAction(SessionState.PASSAGE_READY, true, false, false, OriginDoors.DoorUser.INSIDER), "通道期間艙內的非參與者被拒"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.REFUSE,
                OriginDoors.doorAction(SessionState.PASSAGE_READY, true, false, false, OriginDoors.DoorUser.OUTSIDER), "通道期間艙外的非參與者被拒"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.REFUSE,
                OriginDoors.doorAction(SessionState.PASSAGE_READY, true, true, false, OriginDoors.DoorUser.PARTICIPANT), "Crossed 未確認時參與者關門被拒（§7）"));
        // F5-2（n-2）：通道在記憶體內關閉的期間，門是一般開門、塌縮流程不擁有它。
        for (var user : OriginDoors.DoorUser.values())
            cases.add(() -> assertEquals(OriginDoors.DoorAction.PASS,
                    OriginDoors.doorAction(SessionState.PASSAGE_READY, true, false, false, true, user), "通道在記憶體內關閉：照一般門的邏輯（" + user + "）"));
        cases.add(() -> assertEquals(OriginDoors.DoorAction.END_AND_CLOSE,
                OriginDoors.doorAction(SessionState.COLLAPSING, true, false, true, true, OriginDoors.DoorUser.PARTICIPANT), "PREPARE 不看通道是否在記憶體內關閉"));
        assertAll(cases);
    }

    /**
     * F5-2（spec §10.6 :613）：找不到所屬 session 的量子膜，套用時依序：艙體外擴 3 格的 chunk 還沒都 FULL 就等（不退避）；這座艙在 collapse view 中有 COLLAPSING、SEALED、
     * PASSAGE_READY 的 session 就丟棄（門由它負責）；門已沒有量子膜就完成；否則改為一般開門。MEASURED（含 DORMANT）與 M1–M4 的狀態不排除。
     */
    @Test void ownerlessMembraneWaitsForChunksSkipsAnOwnedDoorAndOpensTheRest() {
        var cases = new ArrayList<Executable>();
        for (var owner : SessionState.values()) for (boolean membrane : new boolean[] {false, true}) {
            var expected = owner.collapseState() ? OriginDoors.OwnerlessStep.OWNED : membrane ? OriginDoors.OwnerlessStep.OPEN : OriginDoors.OwnerlessStep.DONE;
            cases.add(() -> assertEquals(expected, OriginDoors.ownerlessStep(true, owner, membrane), "owner=" + owner + " membrane=" + membrane));
            cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.WAIT, OriginDoors.ownerlessStep(false, owner, membrane), "chunk 未載入：owner=" + owner + " membrane=" + membrane));
        }
        cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.OPEN, OriginDoors.ownerlessStep(true, null, true), "沒有 session、門帶著量子膜：改為一般開門"));
        cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.DONE, OriginDoors.ownerlessStep(true, null, false), "沒有 session、門已沒有量子膜：完成"));
        cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.WAIT, OriginDoors.ownerlessStep(false, null, true), "chunk 未載入：等之後的 tick"));
        for (var owned : List.of(SessionState.COLLAPSING, SessionState.SEALED, SessionState.PASSAGE_READY))
            cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.OWNED, OriginDoors.ownerlessStep(true, owned, true), owned + " 自己擁有門"));
        cases.add(() -> assertEquals(OriginDoors.OwnerlessStep.OPEN, OriginDoors.ownerlessStep(true, SessionState.MEASURED, true), "MEASURED（含 DORMANT）也正規化"));
        assertAll(cases);
    }

    /** F4b-3（spec §7.3「套用量子膜的條件」；補充第 1、6 點）：門口沒有 entity、艙內沒有非參與者時才是 sealed 量子膜（軸依朝向），否則一般開門。 */
    @Test void membraneIsSealedOnlyWithAClearDoorwayAndNoNonParticipantInside() {
        var cases = new ArrayList<Executable>();
        for (var facing : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)) {
            cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.sealed(facing), OriginDoors.membraneTarget(facing, true, false),
                    facing + "：門口沒有人、艙內沒有非參與者 → sealed"));
            cases.add(() -> assertTrue(OriginDoors.membraneTarget(facing, true, false).membrane().fits(facing), facing + "：量子膜的軸符合門的朝向"));
            cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN, OriginDoors.membraneTarget(facing, false, false), facing + "：門口有 entity"));
            cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN, OriginDoors.membraneTarget(facing, true, true), facing + "：艙內有非參與者"));
            cases.add(() -> assertEquals(dev.quantumchamber.chamber.ChamberDoorService.DoorState.OPEN, OriginDoors.membraneTarget(facing, false, true), facing + "：兩者都有"));
        }
        assertAll(cases);
    }

    /**
     * F4b-3（spec §7.3「門狀態的重新套用」；補充第 5 點的一次性觸發）：只有「看過沒有載入、這次已載入」（來源 chunk 重新載入），或開服時就在 journal 的 session 的第一次
     * 觀察（重啟之後）才觸發；執行期間才出現、一直載入著的 session 不觸發，所以 SEALED 的門被玩家打開之後不會被關回去。目標依 collapse view：SEALED 關門、COLLAPSING
     * 把量子膜正規化、PASSAGE_READY 是 passable 量子膜（F5-1）。
     */
    @Test void doorReapplicationTriggersOnlyAfterAnUnloadOrOnTheFirstStartupObservation() {
        // 一個執行期間才出現的 session 依序的觀察：第一次（已載入）、一直載入、卸載、重新載入、再一直載入。
        Boolean[] previous = {null, true, true, false, true};
        boolean[] loaded = {true, true, false, true, true};
        boolean[] runtime = {false, false, false, true, false};
        var cases = new ArrayList<Executable>();
        for (int i = 0; i < loaded.length; i++) {
            int at = i;
            cases.add(() -> assertEquals(runtime[at], OriginDoors.doorReloaded(previous[at], loaded[at], false), "執行期間的 session，第 " + at + " 次觀察"));
        }
        cases.add(() -> assertTrue(OriginDoors.doorReloaded(null, true, true), "開服時就在的 session：第一次觀察到已載入"));
        cases.add(() -> assertFalse(OriginDoors.doorReloaded(null, false, true), "開服時就在的 session：第一次觀察還沒載入"));
        cases.add(() -> assertTrue(OriginDoors.doorReloaded(false, true, true), "開服時就在的 session：之後才載入"));
        cases.add(() -> assertFalse(OriginDoors.doorReloaded(true, true, true), "開服時就在的 session：已觀察過、一直載入"));
        // F4b-3 fix round 1（Nit N-4）：log 的原因區分「重啟之後第一次載入」與「重新載入」。開服時就在的 session 第一次觀察還沒載入、之後才載入，也是重啟之後第一次載入。
        cases.add(() -> assertTrue(OriginDoors.reapplyCause(true, false).startsWith("重啟之後第一次載入"), "開服時就在、本 instance 還沒看過已載入"));
        cases.add(() -> assertTrue(OriginDoors.reapplyCause(true, true).startsWith("重新載入"), "開服時就在、看過已載入之後卸載再載入"));
        cases.add(() -> assertTrue(OriginDoors.reapplyCause(false, false).startsWith("重新載入"), "執行期間才出現、卸載之後載入"));
        cases.add(() -> assertTrue(OriginDoors.reapplyCause(false, true).startsWith("重新載入"), "執行期間才出現、看過已載入之後卸載再載入"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.CLOSE_ON_SEALED, OriginDoors.reapplyGoal(SessionState.SEALED), "SEALED：關門（門口有 entity 時一般開門）"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.NORMALIZE, OriginDoors.reapplyGoal(SessionState.COLLAPSING), "COLLAPSING：量子膜改為一般開門"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.PASSABLE, OriginDoors.reapplyGoal(SessionState.PASSAGE_READY), "PASSAGE_READY：passable 量子膜（F5-1）"));
        for (var state : List.of(SessionState.ARMING, SessionState.SUPERPOSITION, SessionState.RETURNING, SessionState.MEASURED))
            cases.add(() -> assertThrows(IllegalArgumentException.class, () -> OriginDoors.reapplyGoal(state), state + " 不由塌縮流程重新套用"));
        // F5-1 fix round 1（spec review S-m3）：SEALED(TRANSFER_RECOVERY_REQUIRED) 的寫入被拒、通道在記憶體內關閉時，PASSAGE_READY 的門是一般開門；其他狀態不看它。
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.OPEN_WHILE_PASSAGE_CLOSED, OriginDoors.reapplyGoal(SessionState.PASSAGE_READY, true),
                "PASSAGE_READY、通道在記憶體內關閉：一般開門"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.PASSABLE, OriginDoors.reapplyGoal(SessionState.PASSAGE_READY, false), "PASSAGE_READY、通道開著：passable"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.CLOSE_ON_SEALED, OriginDoors.reapplyGoal(SessionState.SEALED, true), "SEALED 不看通道是否在記憶體內關閉"));
        cases.add(() -> assertEquals(OriginDoors.DoorGoal.NORMALIZE, OriginDoors.reapplyGoal(SessionState.COLLAPSING, true), "COLLAPSING 不看通道是否在記憶體內關閉"));
        assertAll(cases);
    }

    /**
     * F4a fix round 2（m-5(b)，spec §6「journal 寫入失敗時的 session 行為」、§11.2 最後一點、§8.4）：進入 SEALED 的寫入在前置檢查就被拒（REJECTED）時，
     * 只重試這一筆寫入（保留 RESOLVE 判定的原因與 root cause），而且依 per-session 退避——200 ticks 起、每次被拒加倍——不是每個 tick 重試；寫入不再
     * 被拒之後沒有待重試的寫入。
     */
    @Test void rejectedSealWriteIsRetriedOnlyOutOfThePerSessionBackoff() {
        var track = new CollapseService.Track();
        var cause = new IllegalStateException("x");
        boolean beforeRejection = track.sealRetryDue(1000);
        track.sealRejected(SealedReason.MATERIALIZE_FAILED, cause, 1000);
        var firstNext = track.sealRetryNext();
        var first = List.of(track.sealRetryDue(1000), track.sealRetryDue(1001), track.sealRetryDue(1199), track.sealRetryDue(1200));
        track.sealRejected(SealedReason.MATERIALIZE_FAILED, cause, 1200);
        var doubledNext = track.sealRetryNext();
        var doubled = List.of(track.sealRetryDue(1201), track.sealRetryDue(1599), track.sealRetryDue(1600));
        var reason = track.pendingSeal;
        var kept = track.pendingSealCause;
        track.sealWritten();
        assertAll(
                () -> assertFalse(beforeRejection, "沒有被拒的寫入"),
                () -> assertEquals(List.of(false, false, false, true), first, "被拒之後：200 ticks 的退避期內不重試（不是每個 tick）"),
                () -> assertEquals(List.of(false, false, true), doubled, "再被拒：退避加倍為 400"),
                // F4b-1 Step 0（F4a fix round 2 quality Q2-m4）：WARN 的「之後會發生的事」寫的是實際的延遲與 tick，不是固定的「200 ticks 起」。
                () -> assertTrue(firstNext.contains("200 ticks 後，tick 1200"), firstNext),
                () -> assertTrue(doubledNext.contains("400 ticks 後，tick 1600"), doubledNext),
                () -> assertEquals(SealedReason.MATERIALIZE_FAILED, reason, "重試的是 RESOLVE 判定的原因"),
                () -> assertSame(cause, kept, "與它的 root cause"),
                () -> assertFalse(track.sealRetryDue(100_000), "寫入不再被拒之後沒有待重試的寫入"));
    }

    /**
     * F4a fix round 2（m-6，{@code warn} 的 Javadoc）：不重試的附帶動作（取得或釋放來源 ticket、刷新 Controller）成功時清掉自己的 WARN 去重鍵。同一原因
     * 連續失敗只記一次；「失敗 → 成功 → 同一原因再失敗」會再記一次；其他寫入種類的鍵不受影響；沒有簿記的 session（例如已終局）成功時只執行，失敗時記在
     * {@code ledger} 提供的簿記上。
     * <p>F4b-1 Step 0（F4a fix round 2 quality Q2-m3）：改經 production 的組合 {@link CollapseService#sideEffect(java.util.Map, java.util.function.Supplier,
     * UUID, String, String, Runnable)}（執行、清鍵、去重、記 WARN 都在其中），並擷取 {@code quantumchamber} logger，斷言實際記下的 WARN，不在 test 裡重寫組合。
     */
    @Test void sideEffectWarningKeysAreClearedOnSuccess() {
        var warnings = new java.util.HashMap<String, String>();
        warnings.put("提交 MEASURED→COLLAPSING", "其他寫入種類的鍵");
        var failure = new IllegalStateException("ticket");
        Runnable fails = () -> { throw failure; };
        Runnable succeeds = () -> { };
        var what = "進入 SEALED 時釋放來源 ticket";
        var logged = new ArrayList<Boolean>();
        var warned = new ArrayList<Integer>();
        var created = new java.util.HashMap<String, String>();
        boolean untrackedSuccessLogged, untrackedFailureLogged;
        List<CollapseLogCapture.Entry> logs;
        try (var capture = new CollapseLogCapture()) {
            for (var action : List.of(fails, fails, succeeds, fails, succeeds, succeeds)) {
                int before = capture.count(Level.WARN);
                logged.add(CollapseService.sideEffect(warnings, () -> warnings, SESSION, what, "不重試", action));
                warned.add(capture.count(Level.WARN) - before);
            }
            // 沒有簿記的 session（warnings 為 null）：成功時只執行、不建立簿記；失敗時在 ledger 給的（新建的）簿記上去重並記 WARN。
            untrackedSuccessLogged = CollapseService.sideEffect(null, () -> { throw new AssertionError("成功時不該建立簿記"); }, SESSION,
                    "終局之後刷新 Controller", "不重試", succeeds);
            untrackedFailureLogged = CollapseService.sideEffect(null, () -> created, SESSION, "終局之後刷新 Controller", "不重試", fails);
            logs = capture.entries();
        }
        var warnings3 = logs.stream().filter(entry -> entry.level() == Level.WARN).toList();
        assertAll(
                () -> assertEquals(List.of(true, false, false, true, false, false), logged, "失敗記 WARN、同一原因去重、成功清掉之後同一原因再記一次"),
                () -> assertEquals(List.of(1, 0, 0, 1, 0, 0), warned, "回傳值與實際記下的 WARN 一致"),
                () -> assertEquals(java.util.Map.of("提交 MEASURED→COLLAPSING", "其他寫入種類的鍵"), warnings, "成功只清自己的鍵"),
                () -> assertFalse(untrackedSuccessLogged, "沒有簿記的 session：成功時只執行"),
                () -> assertTrue(untrackedFailureLogged && created.containsKey("終局之後刷新 Controller"), "沒有簿記的 session：失敗時在 ledger 的簿記上去重"),
                () -> assertEquals(3, warnings3.size(), () -> "WARN 共 3 則：" + warnings3),
                () -> assertTrue(warnings3.stream().allMatch(entry -> entry.marked() && entry.message().contains("session=" + SESSION)
                        && entry.message().contains("不重試") && entry.thrown() == failure), () -> "WARN 帶 LOG_MARKER、session、next 與 cause：" + warnings3));
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
