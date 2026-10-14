package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.OriginDoorsTestAccess;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-2 B2a，B1 quality nit）：跨重啟的原艙門斷言共用的查詢與 step，由 {@link M5CollapseDoorChains} 以純搬移抽出：重新套用的排入與套用的 INFO
 * （{@link Queued}、{@link #reapplyQueued}、{@link #reapplied}）、本來關著的門的兩個 step（{@link #closedDoorKeptByTheReapply}，{@link M5CollapseDormantChains#restart} 用）、
 * 門的狀態（{@link #closed}、{@link #plainOpen}、{@link #doorCenter}）與等待的 tick 數。{@link M5CollapseDormantChains} 因此不再依賴 {@link M5CollapseDoorChains}；F6-2d-2 B2b
 * 的 SEALED 門窗口也用這裡。只有 static；原本的 instance 方法 {@code plainOpen()}、{@code doorCenter()} 改收 {@code run}，其餘與搬移之前相同。
 */
final class M5CollapseDoors {
    /** 重新套用的排入最多等幾個 tick（重啟之後的第一次觀察在第一個 tick；超過時由具名斷言判 FAIL，不是 stepBounded：mutation 讓排入落空時失敗在目標斷言）。 */
    static final int QUEUE_WAIT_TICKS = 10;
    /** 排入之後到套用最多等幾個 tick（排入時記下的 tick 是下一個 tick；之後再 2 個 tick 仍沒有改門就由具名斷言判 FAIL，比照 {@code terminaldoor} 的 {@code ownerlessNormalized}）。 */
    static final int APPLY_WAIT_TICKS = 3;
    /** 重新套用排入（或延後）時的 INFO（{@code OriginDoors.reapplyDoor}）的「下一個 tick（tick N）」；排入時另帶目標（延後時沒有）。 */
    private static final Pattern QUEUED_TICK = Pattern.compile("下一個 tick（tick (\\d+)）");
    private static final Pattern QUEUED_GOAL = Pattern.compile("依 collapse view 重新套用原艙門（([A-Z_]+)）");

    private M5CollapseDoors() {}

    // ---- 本來關著的門（F6-2-08 的另一半，M5CollapseDormantChains 用）----

    /**
     * F6-2-08 的另一半（spec §7.3「門狀態的重新套用」；F4b-3 偏離第 2 條：WAITING 等一般階段的 NORMALIZE 只把帶量子膜的門改為一般開門，本來關著的門保留關著）：開服時原艙門
     * 是關著的 restart（{@link M5CollapseDormantChains#restart} 的 W2、W3、W5：RESOLVE 期間 crash，門沒有動過），在參與者 JOIN 之前插入兩個 step：重啟之後第一次載入的那個
     * tick 排入一則 NORMALIZE，那時門是關著的（{@code normalizeQueuedForTheClosedDoor}）→ 排入時記下的 tick 結束時門仍關著、沒有「已依 collapse view 重新套用」的 INFO
     * （{@code closedDoorKeptByTheReapply}；mutation：NORMALIZE 不看門有沒有量子膜時失敗在這裡）。
     * <p>F6-2d-2 B2a（B1 quality Minor 1）：{@code closedDoorKeptByTheReapply} 另要求排入的 INFO 仍恰一則、而且這座艙沒有待套用的門（production 的 {@code OriginDoors}，經
     * testmod 的 {@link OriginDoorsTestAccess}）：那一則在 tick N 已被消耗。B1 的版本只看 tick N 的門與套用的 INFO，「延後到下一個 tick 才把關著的門打開」照樣 PASS
     * （mutation {@code mut-normalize-defers-then-opens}）。JOIN 之前另以 {@link #closedDoorStillKeptAtTheJoin} 再驗一次。名稱不變、條件變嚴。
     */
    static List<Step> closedDoorKeptByTheReapply(M5CollapseRun run, M5CollapseFixture fixture) {
        int[] queuedFor = {-1};
        // F6-2d-2 B2a（B1 quality Minor 2）：closedNormalizeQueued 改用通用的 awaiting（之前是 counting 加手寫的等待；等待時間 QUEUE_WAIT_TICKS 與通過條件相同）。
        return List.of(M5CollapsePassage.awaiting("closedNormalizeQueued", QUEUE_WAIT_TICKS, () -> !reapplyQueued(run, fixture.sid()).isEmpty(), (tick, happened) -> {
                    var queued = reapplyQueued(run, fixture.sid());
                    boolean closed = closed(run);
                    run.expect("normalizeQueuedForTheClosedDoor", queued.size() == 1 && queued.getFirst().normalizeAfterARestart() && closed,
                            () -> "開服之後 " + run.ticks() + " 個 tick，排入的 INFO：" + queued + " 門關著=" + closed);
                    queuedFor[0] = queued.getFirst().tick();
                    run.proof.put("closedNormalizeTick", queuedFor[0]);
                }),
                M5CollapsePassage.counting("closedDoorKept", APPLY_WAIT_TICKS, tick -> {
                    int now = run.server().getTicks();
                    if (now < queuedFor[0]) return false;
                    boolean closed = closed(run);
                    var applied = reapplied(run, fixture.sid());
                    var queued = reapplyQueued(run, fixture.sid());
                    var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
                    run.expect("closedDoorKeptByTheReapply", closed && now == queuedFor[0] && applied.isEmpty() && queued.size() == 1 && pending.isEmpty(),
                            () -> "tick " + now + "（排入的是 tick " + queuedFor[0] + "）：門關著=" + closed + " 門正中=" + run.source().getBlockState(local(3, 3, 0)) + " 套用的 INFO=" + applied
                                    + " 排入的 INFO=" + queued + " 待套用的門=" + pending.orElse("none"));
                    return true;
                }));
    }

    /**
     * F6-2d-2 B2a（B1 quality Minor 1）：開服時門關著的 restart（{@link M5CollapseDormantChains#restart}）在參與者 JOIN 之前（{@code join} 的那個 step、JOIN 之前，JOIN 的 tick
     * 不變）再驗一次 {@link #closedDoorKeptByTheReapply} 之後門仍關著：25 格 {@code open=false}＋{@code membrane=none}、沒有「已依 collapse view 重新套用」的 INFO、排入的 INFO
     * 仍恰一則（{@code closedDoorStillKeptAtTheJoin}）。
     */
    static void closedDoorStillKeptAtTheJoin(M5CollapseRun run, M5CollapseFixture fixture) {
        boolean closed = closed(run);
        var applied = reapplied(run, fixture.sid());
        var queued = reapplyQueued(run, fixture.sid());
        run.expect("closedDoorStillKeptAtTheJoin", closed && applied.isEmpty() && queued.size() == 1,
                () -> "JOIN 之前（tick " + run.server().getTicks() + "）：門關著=" + closed + " 門正中=" + doorCenter(run) + " 套用的 INFO=" + applied + " 排入的 INFO=" + queued);
    }

    // ---- 共用的查詢 ----

    /**
     * 重新套用排入（或延後）的 INFO（{@code OriginDoors.reapplyDoor}，「…下一個 tick（tick N）…」）：這個 session 的每一則，依發生順序。{@code goal} 是排入時的目標（延後時為 null），
     * {@code firstLoadAfterARestart} 是原因為「重啟之後第一次載入」。比照 GameTest 的 {@code reapplyQueuedTicks}（那裡是 private）。
     */
    record Queued(int tick, String goal, boolean firstLoadAfterARestart) {
        /** 重啟之後第一次載入時排入的 NORMALIZE（COLLAPSING 的重新套用目標）。 */
        boolean normalizeAfterARestart() { return "NORMALIZE".equals(goal) && firstLoadAfterARestart; }
    }

    static List<Queued> reapplyQueued(M5CollapseRun run, UUID sid) {
        var result = new ArrayList<Queued>();
        for (var entry : run.capture().matching(Level.INFO, "下一個 tick（tick ", "session=" + sid)) {
            var tick = QUEUED_TICK.matcher(entry.message());
            if (!tick.find()) continue;
            var goal = QUEUED_GOAL.matcher(entry.message());
            result.add(new Queued(Integer.parseInt(tick.group(1)), goal.find() ? goal.group(1) : null, entry.message().contains("來源艙重啟之後第一次載入")));
        }
        return result;
    }

    /** 重新套用完成的 INFO（{@code OriginDoors.applyDoor}，只在實際改了門時記：「原艙門已依 collapse view 重新套用（goal：APPLIED，tick N）」）：這個 session 的每一則。 */
    static List<String> reapplied(M5CollapseRun run, UUID sid) {
        return run.capture().matching(Level.INFO, "原艙門已依 collapse view 重新套用", "session=" + sid).stream().map(LogCapture.Entry::message).toList();
    }

    static boolean closed(M5CollapseRun run) {
        return M5GameTestSupport.doorAll(run.source(), FRAME, false, MembraneState.NONE);
    }

    static boolean plainOpen(M5CollapseRun run) {
        return M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE);
    }

    /** 原艙門正中那一格（區域 (3,3,0)）的方塊狀態（失敗訊息用）。 */
    static String doorCenter(M5CollapseRun run) {
        return run.source().getBlockState(local(3, 3, 0)).toString();
    }
}
