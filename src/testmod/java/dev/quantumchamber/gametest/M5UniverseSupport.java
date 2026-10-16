package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5GameTestSupport.at;
import static dev.quantumchamber.gametest.M5GameTestSupport.moveInside;
import static dev.quantumchamber.gametest.M5GameTestSupport.whenTicks;
import static dev.quantumchamber.gametest.M5UniverseHold.asTestFailure;
import static dev.quantumchamber.gametest.M5UniverseHold.awaitConvergedThenComplete;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.collapse.UniverseAllocationLedger;
import dev.quantumchamber.gametest.M5GameTestSupport.DormantOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import org.apache.logging.log4j.Level;

/**
 * M5 run 的 GameTest（{@link M5UniverseGameTests}、{@link M5UniverseProjectionGameTests}、{@link M5UniverseDoorGameTests}、{@link M5UniversePassageGameTests}）
 * 共用的 helper（F6-3c-2，盤點 #06：由 {@code M5UniverseGameTests} 純搬移抽出）：兩個以上的 class 用到的 fixture、log 計數與常數；只有一個 class 用到的
 * helper 留在那個 class。default run 的 {@code M5CollapseGameTests} 不用這裡：兩個 run 都要用的放 {@link M5GameTestSupport}（README「新增 M5 GameTest」）。
 */
final class M5UniverseSupport {
    private M5UniverseSupport() { }

    /**
     * 等 PREPARE 完成（「準備完成」INFO 或 PASSAGE_READY）的上限（F6-2c Step 0；F6-3c-1 依 F6-2c Step 0 quality Minor 5 收成一個常數，原本四個 test 各寫一次 600 與同一段註解；
     * F6-3c-2 依 F6-3c-1 quality Minor 3 更正：等的是 PREPARE 完成，不是進入 PREPARE，四處也不都是 D1）。PREPARE 的預載是
     * wall-clock 的 chunk 載入，機器忙時以 tick 計的等待會變長（F6-2c Step 0 時 GameTest 的 tick 還沒有節流，實測最多約 380 tick；F6-3b 起 M5 run 每個 tick 至少 10 ms）。等待以
     * production 的 §9.1 門檻為界（{@code CollapseService.WAIT_WARN_TICKS}＝600，從進入 PREPARE 起算；超過時 production 自己記 WARN；那個常數是 package-private，testmod 看不到），
     * 用到它的 test 的 tickLimit 當時同步加了 200。
     */
    static final int PREPARE_WAIT_TICKS = 600;

    /** 這個 session PREPARE 的原艙門 INFO（{@code fragment} 是「原艙門套用 sealed 量子膜」或「原艙門維持一般開門」）的數目。 */
    static int membraneInfos(LogCapture capture, UUID sid, String fragment) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "PREPARE：", fragment, sid.toString()).size();
    }

    /** 這個 session 的原艙門重新套用排入（或延後）時記下的套用 tick，依發生順序（「下一個 tick（tick N）」）。 */
    static List<Integer> reapplyQueuedTicks(LogCapture capture, UUID sid) {
        return ticks(capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "下一個 tick（tick ", "session=" + sid), Pattern.compile("下一個 tick（tick (\\d+)）"));
    }

    /** 這個 session 的原艙門重新套用完成時記下的 tick，依發生順序（「原艙門已依 collapse view 重新套用（…，tick N）」）。 */
    static List<Integer> reappliedTicks(LogCapture capture, UUID sid) {
        return ticks(capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "原艙門已依 collapse view 重新套用", "session=" + sid),
                Pattern.compile("，tick (\\d+)）"));
    }

    /** 這個 session 的原艙門重新套用排入（或延後）時記下的原因，依發生順序（「來源艙重啟之後第一次載入：」或「來源艙重新載入：」；F4b-3 fix round 1）。 */
    static List<String> reapplyCauses(LogCapture capture, UUID sid) {
        var pattern = Pattern.compile("來源艙(重啟之後第一次載入|重新載入)：");
        var result = new ArrayList<String>();
        for (var entry : capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "下一個 tick（tick ", "session=" + sid)) {
            var matcher = pattern.matcher(entry.message());
            result.add(matcher.find() ? matcher.group(1) : "（沒有原因）");
        }
        return List.copyOf(result);
    }

    static List<Integer> ticks(List<LogCapture.Entry> entries, Pattern pattern) {
        var result = new ArrayList<Integer>();
        for (var entry : entries) {
            var matcher = pattern.matcher(entry.message());
            if (matcher.find()) result.add(Integer.parseInt(matcher.group(1)));
        }
        return List.copyOf(result);
    }

    /** 這個 session「進入 PREPARE」的 INFO 數（帶 LOG_MARKER）。 */
    static int entered(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "進入 PREPARE", sid.toString()).size();
    }

    /** 這個 session「目的世界預載完成」的 INFO（帶 LOG_MARKER）。 */
    static List<LogCapture.Entry> preloaded(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "目的世界預載完成", sid.toString());
    }

    /** 這個 session「準備完成」的 INFO（帶 LOG_MARKER）。 */
    static List<LogCapture.Entry> prepared(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "準備完成（預載與投影都已完成）", sid.toString());
    }

    /** 這個 session「量子通道已開啟（checked PASSAGE_READY）」的 INFO 數（帶 LOG_MARKER，F5-1）。 */
    static int passageOpened(LogCapture capture, UUID sid) {
        return capture.marked(Level.INFO, CollapseService.LOG_MARKER.getName(), "量子通道已開啟（checked PASSAGE_READY）", sid.toString()).size();
    }

    /** F4b-1 的 fixture 交出的宇宙：RESOLVE 完成的 server tick、宇宙 U 的 world、塌縮的 session、原艙的 chamber UUID 與 U。 */
    @FunctionalInterface interface ResolvedUniverse {
        void ready(int tick, ServerWorld universe, UUID sid, UUID chamber, UniverseId id);
    }

    /**
     * F4b-2 的 fixture：同 {@link M5UniverseProjectionGameTests#resolvedUniverse}，但不暫停。RESOLVE 之後的下一個 tick，P 仍在艙內、原艙已供電、Origin enabled，閘門的第一次評估成立，
     * 進入 PREPARE（預載目的世界、寫投影）。{@code ready} 在 RESOLVE 完成之後的 test tick 呼叫，早於第一次閘門評估。
     */
    static void collapsedToUniverse(TestContext context, DormantOrigin origin, M2CorridorGameTests.TestHooks hooks, ResolvedUniverse ready) {
        newCollapse(context, origin, hooks, false, List.of(), ready);
    }

    /** 同 {@link #collapsedToUniverse}，但收據另有 {@code others}（都已返還、在艙外）為原參與者（F4b-2 fix round 1，P-POWER 的第二位參與者）。 */
    static void collapsedToUniverse(TestContext context, DormantOrigin origin, M2CorridorGameTests.TestHooks hooks, List<UUID> others,
            ResolvedUniverse ready) {
        newCollapse(context, origin, hooks, false, others, ready);
    }

    /**
     * PREPARE 系列 test 共用的前段（F4b-3 Step 0，F4b-2 quality Q-M5）：建好 {@link DormantOrigin}，失敗時轉成 test 失敗（{@link M5UniverseHold#asTestFailure}，hooks 已由
     * {@code DormantOrigin.open} 釋放）。之後通常接 {@link #collapsedToUniverse}。
     */
    static DormantOrigin openOrigin(TestContext context, M2CorridorGameTests.TestHooks hooks) {
        try {
            return DormantOrigin.open(context, hooks);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
    }

    /**
     * 額外的一位玩家（第二位參與者或非參與者 N；F6-3c-2，盤點 #07：原本九處各寫一份）：{@link M5GameTestSupport#connectOutside}（在 hooks 登記關閉、不受重力、移到門外），
     * 失敗時轉成 test 失敗（{@link M5UniverseHold#asTestFailure}；與原本相同，不另釋放 hooks）。通常接在 {@link #openOrigin} 之後。
     */
    static ConnectedGameTestPlayer extraPlayer(TestContext context, M2CorridorGameTests.TestHooks hooks, ChamberFrame frame) {
        try {
            return M5GameTestSupport.connectOutside(context, hooks, frame);
        } catch (RuntimeException failure) { throw asTestFailure(context, "fixture", failure); }
    }

    /**
     * PREPARE 系列 test 共用的後段（F4b-3 Step 0，F4b-2 quality Q-M5）：釋放 hooks（收尾的終局 API 移除收據、關閉玩家與 log capture），斷言 {@code witness}
     * 全部成立（失敗訊息是 {@code message}），之後以 {@link M5UniverseHold#awaitConvergedThenComplete} 等權威收斂而且 {@code ready} 成立才完成，catalog 增量 1。
     */
    static void releaseThenComplete(TestContext context, M2CorridorGameTests.TestHooks hooks, Map<String, Boolean> witness, String message,
            int maxTicks, BooleanSupplier ready) {
        hooks.release();
        context.assertTrue(!witness.containsValue(false), message);
        awaitConvergedThenComplete(context, 1, maxTicks, ready);
    }

    static void newCollapse(TestContext context, DormantOrigin origin, M2CorridorGameTests.TestHooks hooks, boolean suspend, List<UUID> others,
            ResolvedUniverse ready) {
        var server = context.getWorld().getServer(); var journal = SessionRecoveryState.get(server);
        var p = origin.player();
        var people = new LinkedHashMap<UUID, Boolean>();
        people.put(p.getUuid(), true);
        for (var other : others) people.put(other, true);
        origin.whenRegistered((registered, chamber) -> {
            var dormant = origin.own(origin.receipt(chamber, people, true));
            var sid = dormant.sessionUuid();
            var id = UniverseAllocationLedger.deriveUniverseId(((QuantumCandidate.New) dormant.selectedCandidate().orElseThrow()).intent().allocationToken());
            at(context, registered + 1, hooks, () -> moveInside(p, origin.frame));
            whenTicks(context, registered + 2, 20, () -> UniverseLifecycleService.resolveActive(server, id).isPresent()
                    && origin.capture.matching(Level.INFO, "目的地已確定", sid.toString()).size() == 1, resolved -> {
                if (suspend) M5TestHooks.suspendCollapse(sid);
                ready.ready(resolved, UniverseLifecycleService.resolveActive(server, id).orElseThrow(), sid, chamber, id);
            }, () -> "RESOLVE 沒有建立宇宙：record=" + M5GameTestSupport.state(journal, sid), hooks);
        });
    }

    /** 投影在區域座標（0..6）的格。 */
    static BlockPos local(ChamberFrame frame, int x, int y, int z) {
        return ChamberGeometry.localToWorld(frame, x, y, z);
    }

    /** 投影區域座標系的任意格（可以在 7×7×7 之外）。 */
    static BlockPos outside(ChamberFrame frame, int x, int y, int z) {
        return ChamberSpaceCoordinates.block(frame, x, y, z);
    }

    /** 這個 session 的「寫入 SEALED」WARN（{@code CollapseService} 的 {@code warn}，帶 LOG_MARKER）。 */
    static int sealWriteWarnings(LogCapture capture, UUID sid) {
        return capture.marked(Level.WARN, CollapseService.LOG_MARKER.getName(), "寫入 SEALED", sid.toString()).size();
    }

    static boolean receiptStillSealed(SessionRecoveryState journal, UUID sid) {
        var record = journal.flushedRecords().get(sid);
        return record != null && record.state() == SessionState.SEALED && record.equals(journal.records().get(sid));
    }

    /** flushed 是 SEALED(reason)，而且 current 與 flushed 相同。 */
    static boolean sealedWith(SessionRecoveryState journal, UUID sid, SealedReason reason) {
        var record = journal.flushedRecords().get(sid);
        return record != null && record.state() == SessionState.SEALED && record.collapse().sealedReason().equals(Optional.of(reason))
                && record.equals(journal.records().get(sid));
    }

    /** allocation ledger 正式檔裡 UniverseId 為 {@code id} 的 entry 數（直接讀 NBT，不經 production 的 strict decode）。 */
    static int allocationEntries(MinecraftServer server, UniverseId id) {
        var file = server.getSavePath(WorldSavePath.ROOT).resolve("data").resolve(UniverseAllocationLedger.FILE_NAME);
        try {
            var entries = NbtIo.readCompressed(file, NbtSizeTracker.ofUnlimitedBytes()).getList("Entries", NbtElement.COMPOUND_TYPE);
            int count = 0;
            for (int i = 0; i < entries.size(); i++) if (entries.getCompound(i).getUuid("UniverseId").equals(id.value())) count++;
            return count;
        } catch (IOException failure) {
            throw new IllegalStateException("讀不到 allocation ledger", failure);
        }
    }

    /** {@code data/<name>} 的 bytes；不存在時為空陣列。 */
    static byte[] bytes(Path data, String name) {
        try {
            var file = data.resolve(name);
            return Files.exists(file) ? Files.readAllBytes(file) : new byte[0];
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
