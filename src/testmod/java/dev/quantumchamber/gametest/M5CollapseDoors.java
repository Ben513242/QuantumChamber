package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;
import static dev.quantumchamber.gametest.M5CollapseFixture.local;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.OriginDoorsTestAccess;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.server.world.ServerWorld;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2d-2 B2a，B1 quality nit）：跨重啟的原艙門斷言共用的查詢與 step，由 {@link M5CollapseDoorChains} 以純搬移抽出：重新套用的排入與套用的 INFO
 * （{@link Queued}、{@link #reapplyQueued}、{@link #reapplied}）、本來關著的門的兩個 step（{@link #closedDoorKeptByTheReapply}，{@link M5CollapseDormantChains#restart} 用）、
 * 門的狀態（{@link #closed}、{@link #plainOpen}、{@link #doorCenter}）與等待的 tick 數。{@link M5CollapseDormantChains} 因此不再依賴 {@link M5CollapseDoorChains}；F6-2d-2 B2b
 * 的 SEALED 門窗口也用這裡。只有 static；原本的 instance 方法 {@code plainOpen()}、{@code doorCenter()} 改收 {@code run}，其餘與搬移之前相同。F6-2d-2 B2 另加 SEALED 的門窗口用的
 * 查詢（{@link Queued#closeAfterARestart}、{@link #applied}、{@link #cancelledCloses}、{@link #is}）與門口的盔甲座（{@link #spawnDoorwayStand}）。
 */
final class M5CollapseDoors {
    /** 重新套用的排入最多等幾個 tick（重啟之後的第一次觀察在第一個 tick；超過時由具名斷言判 FAIL，不是 stepBounded：mutation 讓排入落空時失敗在目標斷言）。 */
    static final int QUEUE_WAIT_TICKS = 10;
    /**
     * 排入之後到套用最多等幾個 tick（排入時記下的 tick 是下一個 tick；之後再 2 個 tick 仍沒有改門就由具名斷言判 FAIL，比照 {@code terminaldoor} 的 {@code ownerlessNormalized}）。
     * F6-2e Step 0（F6-2d-2 B2 quality Minor 3）：用它的 step（{@code closedDoorKept}、DC 的兩個 {@code normalized}）都經 {@link M5CollapsePassage#awaiting}，上限只寫這一次
     * （原本另在條件寫 {@code now < N + 2} 或 {@code now < N}）：「排入」的 step 在 tick N-1 完成，所以等的 step 的第 1 個 tick 是 tick N、第 3 個 tick 是 N+2，與之前相同。
     */
    static final int APPLY_WAIT_TICKS = 3;
    /** 重新套用排入（或延後）時的 INFO（{@code OriginDoors.reapplyDoor}）的「下一個 tick（tick N）」；排入時另帶目標（延後時沒有）。 */
    private static final Pattern QUEUED_TICK = Pattern.compile("下一個 tick（tick (\\d+)）");
    private static final Pattern QUEUED_GOAL = Pattern.compile("依 collapse view 重新套用原艙門（([A-Z_]+)）");
    /** 重新套用完成的 INFO（{@code OriginDoors.applyDoor}）的「（goal：APPLIED，tick N）」（F6-2d-2 B2）。 */
    private static final Pattern APPLIED = Pattern.compile("（([A-Z_]+)：APPLIED，tick (\\d+)）");

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
                    run.recordProof("closedNormalizeTick", queuedFor[0]);
                }),
                // F6-2e Step 0（F6-2d-2 B2 quality Minor 3）：closedDoorKept 也改用 awaiting（之前是 counting 加手寫的 now < N；上限 APPLY_WAIT_TICKS 只寫一次）。等待的上限相同
                // （3 個 tick），通過條件相同（第一個 now ≥ N 的 tick 斷言，要求 now == N）；到時仍沒有到 tick N 時由這個具名斷言判 FAIL（之前是 stepBounded）。
                M5CollapsePassage.awaiting("closedDoorKept", APPLY_WAIT_TICKS, () -> run.server().getTicks() >= queuedFor[0], (tick, reached) -> {
                    int now = run.server().getTicks();
                    boolean closed = closed(run);
                    var applied = reapplied(run, fixture.sid());
                    var queued = reapplyQueued(run, fixture.sid());
                    var pending = OriginDoorsTestAccess.pendingGoal(run.server(), fixture.chamber());
                    run.expect("closedDoorKeptByTheReapply", closed && now == queuedFor[0] && applied.isEmpty() && queued.size() == 1 && pending.isEmpty(),
                            () -> "tick " + now + "（排入的是 tick " + queuedFor[0] + "）：門關著=" + closed + " 門正中=" + doorCenter(run) + " 套用的 INFO=" + applied
                                    + " 排入的 INFO=" + queued + " 待套用的門=" + pending.orElse("none"));
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
        boolean normalizeAfterARestart() { return OriginDoorsTestAccess.NORMALIZE.equals(goal) && firstLoadAfterARestart; }

        /** 重啟之後第一次載入時排入的 CLOSE_ON_SEALED（SEALED 的重新套用目標；F6-2d-2 B2）。 */
        boolean closeAfterARestart() { return OriginDoorsTestAccess.CLOSE_ON_SEALED.equals(goal) && firstLoadAfterARestart; }
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

    /** 重新套用完成的 INFO（{@link #reapplied}）裡的目標與 tick（F6-2d-2 B2：「（goal：APPLIED，tick N）」）。 */
    record Applied(String goal, int tick) {}

    /** 這個 session 的重新套用完成的 INFO，逐則取出目標與 tick（依發生順序；格式不符的一則記為 {@code ("?", -1)}，比對因此失敗）。 */
    static List<Applied> applied(M5CollapseRun run, UUID sid) {
        var result = new ArrayList<Applied>();
        for (var message : reapplied(run, sid)) {
            var match = APPLIED.matcher(message);
            result.add(match.find() ? new Applied(match.group(1), Integer.parseInt(match.group(2))) : new Applied("?", -1));
        }
        return result;
    }

    /** SEALED 的原艙門被玩家照一般方式使用、取消還沒套用的關門的 INFO（{@code OriginDoors.cancelSealedClose}）的則數（F6-2d-2 B2）。 */
    static int cancelledCloses(M5CollapseRun run, UUID sid) {
        return run.capture().matching(Level.INFO, "取消還沒套用的關門", "session=" + sid).size();
    }

    /**
     * 原艙門 25 格是 {@code door}（F6-2d-2 B2；F6-2e-1 起 enum 沒有「未宣告」的值，每個值都有定義）。F6-2e Step 0（F6-2d-2 B2 quality Minor 1）：{@code open}／{@code membrane}
     * 的對應改由 enum 給（{@link M5CollapseWindows.OriginDoorState#open}、{@link M5CollapseWindows.OriginDoorState#membrane}，與窗口表的 {@code doorAsExpectedAtStart} 共用）；原本這裡的
     * switch 是 {@link #closed}、{@link #plainOpen} 與 {@code M5GameTestSupport.doorSealed}，三者都是同一個 {@code doorAll}，判斷相同。
     */
    static boolean is(M5CollapseRun run, M5CollapseWindows.OriginDoorState door) {
        return is(run.source(), FRAME, door);
    }

    /**
     * F6-2e-2（F6-2e-1 quality Minor 1、spec M-3）：{@code world} 裡 {@code frame} 這座艙的 25 格門是 {@code door}。{@link #is(M5CollapseRun, M5CollapseWindows.OriginDoorState)} 寫死
     * 來源世界與 probe 的原艙 {@code FRAME}，所以 W10（Nether 的兩座艙）與窗口表的條件（只拿得到 fixture）原本各以 {@code M5GameTestSupport.doorAll}／{@code doorSealed} 自己對應門的狀態；
     * 都改經這裡，{@code open}／{@code membrane} 只由 {@link M5CollapseWindows.OriginDoorState} 給。
     */
    static boolean is(ServerWorld world, ChamberFrame frame, M5CollapseWindows.OriginDoorState door) {
        return M5GameTestSupport.doorAll(world, frame, door.open(), door.membrane(frame));
    }

    /**
     * F6-2-09 的 {@code sealdoorway}（F6-2d-2 B2）：在門口（門格 local (3,1,0) 的中心、腳在那一格的底）放一座不受重力的盔甲座，比照 {@code M5GameTestSupport.doorwayEntity}（那個
     * helper 要 GameTest 的 {@code TestHooks}；probe 的 crash phase 不移除它，restart 由 chain 移除）。
     */
    static ArmorStandEntity spawnDoorwayStand(M5CollapseRun run) {
        var world = run.source();
        var stand = EntityType.ARMOR_STAND.create(world);
        if (stand == null) throw new IllegalStateException("建不出盔甲座");
        var cell = local(3, 1, 0);
        stand.refreshPositionAndAngles(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
        stand.setNoGravity(true);
        if (!world.spawnEntity(stand)) throw new IllegalStateException("盔甲座沒有加入世界");
        return stand;
    }

    /**
     * F6-2e-1（F6-2e Step 0 review 的測試缺陷）：門口（production 的 {@code OriginDoors.doorwayBox}，經 {@link OriginDoorsTestAccess#doorwayBox}）裡的盔甲座，也就是 probe 自己以
     * {@link #spawnDoorwayStand} 放的。門口的斷言只數它（世界生成的動物等其他 entity 不算；原艙附近的非玩家 entity 已在蓋原艙之後移走，{@code M5CollapseFixture.clearAroundTheOrigin}）。
     */
    static List<ArmorStandEntity> doorwayStands(M5CollapseRun run) {
        return run.source().getEntitiesByClass(ArmorStandEntity.class, OriginDoorsTestAccess.doorwayBox(FRAME), stand -> true);
    }

    /**
     * 原艙門關著（{@link #is} 的 {@code CLOSED}）。F6-2e-1（F6-2e Step 0 quality Minor 2）：原本與 {@link #plainOpen} 各自呼叫 {@code M5GameTestSupport.doorAll}，是與
     * {@link M5CollapseWindows.OriginDoorState} 平行的第二份對應（判斷相同）；改經 {@link #is}，門的 {@code open}／{@code membrane} 只由 enum 給。
     */
    static boolean closed(M5CollapseRun run) {
        return is(run, M5CollapseWindows.OriginDoorState.CLOSED);
    }

    /** 原艙門是一般開門（{@link #is} 的 {@code PLAIN_OPEN}；F6-2e-1 起經 {@link #is}，同 {@link #closed}）。 */
    static boolean plainOpen(M5CollapseRun run) {
        return is(run, M5CollapseWindows.OriginDoorState.PLAIN_OPEN);
    }

    /** 原艙門正中那一格（區域 (3,3,0)）的方塊狀態（失敗訊息用）。 */
    static String doorCenter(M5CollapseRun run) {
        return run.source().getBlockState(local(3, 3, 0)).toString();
    }
}
