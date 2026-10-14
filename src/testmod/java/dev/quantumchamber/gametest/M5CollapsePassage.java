package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.collapse.ProjectionLedger;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：restart 由 RESOLVE 推進到終局的共用部分：step（RESOLVE 完成 → 參與者進入艙內 →
 * PASSAGE_READY → 越過門板中線、傳送到 U → (b) 全員都已跨過的終局）、每個 tick 的不變量（spec §12 的共同斷言），與終局的 spec §12 斷言（{@link #expectEnd}）。
 * <p>F6-2c（F6-2a quality Minor 1 與給 W7 的建議）：step 拆成可選的三段，chain 只取需要的段：{@link #resolve}（RESOLVE 完成、進入艙內）、{@link #ready}（PASSAGE_READY；
 * 之後讓要跨越的參與者站到門板中線內側）、{@link #cross}（指定的參與者越過中線、傳送到 U；可以只是參與者的子集。F6-2d-2：一個 phase 只取一次，step 名稱固定是 {@code doorway}／{@code crossed}，再取一次
 * 會在 {@code onInitialize} 因 step 重名丟出）。{@link #steps} 是三段全員跨越、再接
 * (b) 終局的完整版本（既有的 restart 都用它，行為與拆段之前相同）。F6-2d-1：(b) 終局的 step 也可以單獨取（{@link #ended}，終局的參數由 chain 交來）。
 * <p>F6-2d-2 Step 0（F6-2d-1 quality Minor 1、2）：chain 共用的 step factory 也在這裡：{@link #counting}（區域計數器）、{@link #watch}（觀察固定 tick 數）、
 * {@link #steps(M5CollapseWindows.Prepared, Supplier)}（換 (b) 終局的參數）與 RESOLVE 完成的斷言 {@link #expectResolved}。
 */
final class M5CollapsePassage {
    /**
     * 觀察「沒有觸發」「沒有重試」「閘門只看邊緣」的 tick 數（{@link #watch}；F6-2d-2 Step 0，F6-2d-1 quality Minor 1：合併 {@code M5CollapseRetryChains.WATCH_TICKS} 與
     * {@code M5CollapsePassageChains.SEALED_WATCH_TICKS}，兩者都是 40）。
     */
    static final int WATCH_TICKS = 40;
    /** 門口的 local x（依參與者在清單中的順序；第一位與拆段之前相同，在門口正中）。兩人以上時錯開，不站在同一格。 */
    private static final double[] DOORWAY_X = {3.5, 1.5, 5.5};
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;

    M5CollapsePassage(M5CollapseRun run, M5CollapseFixture fixture) {
        this.run = run;
        this.fixture = fixture;
    }

    /** W1 的 restart：參與者返還之前可以在走廊（{@code awaitingReturn}），但 JOIN（{@code joinedAt}，phase 的第幾個 tick）之後最多 {@code limit} 個 tick。 */
    record CorridorAllowance(BooleanSupplier awaitingReturn, IntSupplier joinedAt, int limit) {}

    /**
     * 每個 tick（spec §12 的共同斷言）：catalog 只出現 Destination 的 UniverseId、NEW 最多多 1 筆（EXISTING 不變）；record 在時 Destination 與候選不變；在線上的
     * 參與者都在來源世界或 U。{@code allowance} 不是 null 時（W1），返還之前在走廊不算違反，但 JOIN 之後超過上限仍在走廊就是被困住。
     */
    void invariants(CorridorAllowance allowance) {
        var universe = fixture.universe();
        var catalogIds = UniverseRegistryState.get(run.server()).records().keySet();
        run.expect("catalogOnlyDestination", catalogIds.stream().allMatch(universe::equals), () -> "Destination 是 " + universe.value() + "，catalog 有 " + catalogIds);
        run.expect(fixture.created() ? "catalogAtMostOneMore" : "catalogNotGrown", catalogIds.size() <= fixture.catalogBefore() + fixture.catalogGrowth(),
                () -> "catalog " + catalogIds.size() + " 筆，crash 前 " + fixture.catalogBefore());
        var current = run.journal().flushedRecords().get(fixture.sid());
        if (current != null) {
            // F6-2d-1（W8）：DORMANT（MEASURED）的收據還沒有 Destination（提交 COLLAPSING 時才推導），只在塌縮狀態斷言。
            if (current.state().collapseState()) {
                run.expect("sameDestination", M5CollapseFixture.destination(current).equals(Optional.of(universe)), () -> "Destination=" + current.collapse().destination());
            }
            run.expect("noRedraw", M5CollapseFixture.sameCandidate(fixture.receipt(), current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
        }
        for (var id : fixture.participants()) {
            var player = fixture.onlinePlayer(id);
            if (player == null) continue;
            var world = player.getServerWorld();
            if (allowance != null && allowance.awaitingReturn().getAsBoolean() && world == fixture.corridor()) {
                int joined = allowance.joinedAt().getAsInt();
                run.expect("notTrappedInTheCorridor", run.ticks() - joined <= allowance.limit(), () -> "JOIN 之後 " + (run.ticks() - joined) + " 個 tick 仍在走廊：" + run.diagnostic());
            } else {
                run.expect("participantInSourceOrUniverse", world == run.source() || world == fixture.universeWorld().orElse(null),
                        () -> "參與者在 " + world.getRegistryKey().getValue());
            }
        }
    }

    /**
     * restart 由 RESOLVE 推進到終局的 step（各 restart 共用）：{@link #resolve} → {@link #ready}（全員站到中線內側）→ {@link #cross}（全員）→ (b) 全員都已跨過的終局。
     * {@code prepared} 是窗口表裡 PREPARE 的預期（null：不檢查）。
     */
    List<Step> steps(M5CollapseWindows.Prepared prepared) {
        return steps(prepared, () -> End.crossed(fixture));
    }

    /**
     * 同 {@link #steps(M5CollapseWindows.Prepared)}，(b) 終局以 {@code end} 斷言（F6-2d-2 Step 0，F6-2d-1 quality Minor 2：取代 w9io 為了換 {@link End} 手抄的一份；例如
     * allocation ledger 另有一筆的 {@link End#withOtherAllocations}）。
     */
    List<Step> steps(M5CollapseWindows.Prepared prepared, Supplier<End> end) {
        var steps = new ArrayList<Step>();
        steps.add(resolve());
        steps.add(ready(prepared, M5CollapseFixture.EVERYONE));
        steps.addAll(cross(M5CollapseFixture.EVERYONE));
        steps.add(ended(end));
        return List.copyOf(steps);
    }

    /** {@link #counting} 的 step 本體：{@code tick} 是這個 step 的第幾個 tick（由 1 起）；回 true 時完成。 */
    @FunctionalInterface interface Counted { boolean run(int tick) throws Exception; }

    /**
     * {@link #watch} 每個 tick 的觀察（具名斷言）：{@code tick} 是這個 step 的第幾個 tick（由 1 起，與 {@link Counted} 相同；F6-2d-2，F6-2d-2 Step 0 quality nit：原本是
     * 「這個 tick 之前已觀察的 tick 數」，由 0 起，兩者的起算點不同）。
     */
    @FunctionalInterface interface Watching { void tick(int tick) throws Exception; }

    /**
     * step {@code name}（上限 {@code maxTicks}，同 {@link Step}）：每個 tick 以這是這個 step 的第幾個 tick 呼叫 {@code body}。計數器跟著這個 step（每次呼叫各自計算，比照
     * {@link #ready}；F6-2d-2 Step 0，F6-2d-1 quality Minor 1：取代 chain 共用的可變計數器 {@code watched}、{@code waited}、{@code triggerWaited}，同一個 phase 再加一段時不會
     * 因為忘了歸零而只觀察 1 個 tick）。
     */
    static Step counting(String name, int maxTicks, Counted body) {
        Objects.requireNonNull(body, "body");
        int[] tick = {0};
        return new Step(name, maxTicks, () -> body.run(++tick[0]));
    }

    /** 觀察段（step {@code name}）：每個 tick 呼叫 {@code eachTick}，恰 {@code ticks} 個 tick 之後完成（{@link #watch(String, int, Watching, M5CollapseRun.Checked)}）。 */
    static Step watch(String name, int ticks, Watching eachTick) {
        return watch(name, ticks, eachTick, M5CollapseRun.NOTHING);
    }

    /**
     * 觀察段（step {@code name}，上限恰為 {@code ticks}）：每個 tick 呼叫 {@code eachTick}（具名斷言），第 {@code ticks} 個 tick 的 {@code eachTick} 之後再呼叫 {@code then}
     * （觀察完之後的斷言或動作）、完成。計數器同 {@link #counting}。F6-2d-2（F6-2d-2 Step 0 quality nit）：上限原本是 {@code ticks}＋5，但這一段一定在第 {@code ticks} 個 tick
     * 完成（{@code eachTick} 丟出時當場 FAIL），多出的 5 tick 到不了。
     */
    static Step watch(String name, int ticks, Watching eachTick, M5CollapseRun.Checked then) {
        Objects.requireNonNull(eachTick, "eachTick");
        Objects.requireNonNull(then, "then");
        return counting(name, ticks, tick -> {
            eachTick.tick(tick);
            if (tick < ticks) return false;
            then.run();
            return true;
        });
    }

    /**
     * ended 段（step {@code ended}）：(b) 全員都已跨過的終局（record 已不在、「(b) 全員都已跨過」的 INFO 一則、原艙門一般開門），再以 {@code end} 的參數斷言 spec §12 的終局。
     * {@code end} 在終局的那一刻才建立（參與者在開服時才由收據讀回）。F6-2d-1 由 {@link #steps} 拆出（終局的參數因窗口而不同，例如 allocation ledger 另有一筆的 w9io）。
     */
    Step ended(Supplier<End> end) {
        Objects.requireNonNull(end, "end");
        return new Step("ended", 40, () -> endedByEveryoneCrossing(end));
    }

    /** resolve 段（step {@code resolved}）：「目的地已確定（RESOLVE 完成）」INFO 之後，U 已物化、catalog 恰多 1 筆、discovery 恰一筆 U；之後全員進入艙內。 */
    Step resolve() {
        return new Step("resolved", 400, this::resolvedThenEnter);
    }

    /**
     * ready 段（step {@code ready}）：PASSAGE_READY 之後最多再等 5 個 tick，讓門與 Controller 的刷新落地，再斷言還沒有人跨過、passable、比較器 15 與投影 COMPLETE（與
     * PREPARE 怎麼完成）；之後 {@code approaching} 站到門板中線內側（下一個 tick 由 {@link #cross} 越過中線）。等待的 tick 數跟著這一段（每次呼叫各自計算）。
     */
    Step ready(M5CollapseWindows.Prepared prepared, M5CollapseFixture.Who approaching) {
        Objects.requireNonNull(approaching, "approaching");
        int[] readyTicks = {-1};
        return new Step("ready", 600, () -> passageReady(prepared, fixture.members(approaching), readyTicks));
    }

    /**
     * cross 段（step {@code doorway}、{@code crossed}）：{@code crossing}（參與者的子集，至少一位，要已在中線內側）越過門板中線 → 傳送到 U。跨越的那個 tick 結束時 record
     * 一定還在（全員都已跨過的終局在下一個 tick）：Crossed 恰為 {@code crossing} 各一次（{@code crossedRecorded}），「參與者已穿過量子通道」INFO 也是這幾位各一則
     * （{@code oneCrossingInfo}）。一個 phase 只取一次（F6-2d-2，F6-2d-2 Step 0 quality Minor 1：step 名稱固定，第二次會因重名在 {@code onInitialize} 丟出；原本說可以分幾次、
     * 以 {@code crossedSoFar} 累計，與重名的守門矛盾。目前沒有 chain 需要分批跨越）。
     */
    List<Step> cross(M5CollapseFixture.Who crossing) {
        Objects.requireNonNull(crossing, "crossing");
        return List.of(
                new Step("doorway", 1, () -> {
                    var who = fixture.members(crossing);
                    if (who.isEmpty()) throw new IllegalArgumentException("cross 段至少要有一位參與者");
                    for (var id : fixture.participants()) if (who.contains(id)) standInDoorway(id, 0.3);
                    return true;
                }),
                new Step("crossed", 10, () -> crossed(fixture.members(crossing))));
    }

    /** 參與者 {@code id} 站在門口（local x 依清單順序錯開，y 1，z 為 {@code z}；朝向與速度歸零）。 */
    void standInDoorway(UUID id, double z) {
        M5GameTestSupport.standInDoorway(fixture.player(id), FRAME, doorwayX(id), 1, z, 0, 0, Vec3d.ZERO);
    }

    private double doorwayX(UUID id) {
        int index = fixture.participants().indexOf(id);
        if (index < 0 || index >= DOORWAY_X.length) throw new IllegalArgumentException("門口只排得下 " + DOORWAY_X.length + " 位參與者：" + id);
        return DOORWAY_X[index];
    }

    private boolean resolvedThenEnter() throws Exception {
        if (run.capture().matching(Level.INFO, "目的地已確定（RESOLVE 完成）", fixture.sid().toString()).isEmpty()) return false;
        expectResolved();
        fixture.moveInside();
        return true;
    }

    /**
     * 「目的地已確定（RESOLVE 完成）」INFO 之後的斷言：U 已物化（{@code materialized}）、catalog 恰多 1 筆（{@code catalogExactlyOneMore}）、discovery 恰一筆 U
     * （{@code universeDiscoveredOnce}）。{@link #resolve} 與 chain 共用（F6-2d-2 Step 0，F6-2d-1 quality Minor 2：原本 {@code M5CollapseRetryChains.resolvedWithoutMoving}
     * 另寫一份）。
     */
    void expectResolved() throws Exception {
        run.expect("materialized", fixture.universeWorld().isPresent(), () -> "RESOLVE 完成但 U 沒有物化");
        fixture.expectCatalog("catalogExactlyOneMore");
        run.expect("universeDiscoveredOnce", () -> M5CollapseDiskEvidence.discovery(run.saveRoot()).stream()
                .filter(record -> record.universeId().equals(fixture.universe())).count() == 1, () -> "discovery 沒有恰一筆 U");
    }

    /** PASSAGE_READY 之後最多再等 5 個 tick，讓門與 Controller 的刷新落地，再斷言 passable、比較器 15 與投影 COMPLETE；之後 {@code approaching} 站到門板中線內側。 */
    private boolean passageReady(M5CollapseWindows.Prepared prepared, Set<UUID> approaching, int[] readyTicks) throws Exception {
        var record = run.journal().flushedRecords().get(fixture.sid());
        if (record == null || record.state() != SessionState.PASSAGE_READY) return false;
        readyTicks[0]++;
        boolean door = M5GameTestSupport.doorPassable(run.source(), FRAME), comparator = fixture.controllerSignal(ChamberState.PASSAGE, 15);
        if (!(door && comparator) && readyTicks[0] < 5) return false;
        run.expect("noOneCrossedYet", record.collapse().crossed().isEmpty(), () -> "Crossed=" + record.collapse().crossed());
        run.expect("doorPassable", door, () -> "PASSAGE_READY 之後門不是 passable 量子膜");
        run.expect("comparatorFifteen", comparator, () -> "PASSAGE_READY 之後 Controller 不是 PASSAGE／比較器 15");
        run.expect("projectionComplete", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        M5CollapseWindows.expectPrepared(run, fixture, prepared);
        for (var id : fixture.participants()) if (approaching.contains(id)) standInDoorway(id, 0.7);
        return true;
    }

    private boolean crossed(Set<UUID> crossing) {
        var world = fixture.universeWorld().orElseThrow();
        if (crossing.stream().anyMatch(id -> fixture.player(id).getServerWorld() != world)) return false;
        // 跨越的那個 tick 結束時 record 一定還在（全員都已跨過的終局在下一個 tick）：Crossed 恰為跨過的參與者各一次（一個 phase 只有一個 cross 段）。
        var record = run.journal().flushedRecords().get(fixture.sid());
        run.expect("crossedRecorded", record != null && record.collapse().crossed().size() == crossing.size()
                        && Set.copyOf(record.collapse().crossed()).equals(crossing),
                () -> "Crossed=" + (record == null ? "（record 已移除）" : record.collapse().crossed()) + "，預期 " + crossing);
        run.expect("oneCrossingInfo", run.capture().matching(Level.INFO, "參與者已穿過量子通道", fixture.sid().toString()).size() == crossing.size(),
                () -> "「參與者已穿過量子通道」不是每位跨過的參與者恰一則");
        return true;
    }

    private boolean endedByEveryoneCrossing(Supplier<End> end) throws Exception {
        var sid = fixture.sid();
        if (run.journal().flushedRecords().containsKey(sid) || run.journal().records().containsKey(sid)) return false;
        run.expect("endedByEveryoneCrossing", run.capture().matching(Level.INFO, "量子通道已結束", "(b) 全員都已跨過", sid.toString()).size() == 1,
                () -> "沒有恰一則 (b) 的結束 INFO");
        run.expect("plainOpenAfterTheEnd", M5GameTestSupport.doorAll(run.source(), FRAME, true, MembraneState.NONE), () -> "終局之後原艙門不是一般開門");
        expectEnd(end.get());
        return true;
    }

    // ---- 終局（spec §12） ----

    /** 終局時 projection ledger 的預期：這座艙在 U 的 entry 是 COMPLETE，或 ledger 不存在（退化：開服時遺失、沒有被重建）。 */
    enum ProjectionAtEnd { COMPLETE, LEDGER_ABSENT }

    /** 終局時參與者在哪裡：U（跨越成功），或來源世界（沒有跨過：退化時照一般方式開門、通道結束時還沒有跨過）。 */
    enum Place { UNIVERSE, SOURCE }

    /**
     * 這個 session 從 {@code onInitialize} 起的 ERROR 的預期（{@link End#sessionErrors}，{@link #expectEnd} 依它選具名斷言；F6-2d-2 B1，H9 quality Minor 6：原本是 ERROR 的個數
     * {@code sessionErrors} 與 {@code startupReminder} 旗標兩個欄位，組合可以互相矛盾，例如一則 ERROR 卻不知道是哪一種）。
     */
    enum SessionErrors {
        /** 一則都沒有（正常的 restart）：{@code noSessionError}。 */
        NONE("noSessionError", 0),
        /** 只有本 instance 進入永久原因 SEALED 時的那一則（退化的 restart，{@link End#degraded}）：{@code noFurtherSessionError}。 */
        PERMANENT_ENTRY("noFurtherSessionError", 1),
        /** 只有開服時的提醒那一則（開服時就是永久原因 SEALED，H9 (b)，{@link End#withStartupReminder}）：{@code permanentSealedRemindedOnceAtStartup}。 */
        STARTUP_REMINDER("permanentSealedRemindedOnceAtStartup", 1);

        /** 具名斷言的名稱（F6-2d-2 B1 之前的名稱，不變）。 */
        final String assertion;
        /** 這個 session 的 ERROR 恰有幾則。 */
        final int count;

        SessionErrors(String assertion, int count) {
            this.assertion = assertion;
            this.count = count;
        }
    }

    /**
     * 終局的 spec §12 斷言的參數：catalog 相對於 crash 前的增量（NEW 1、EXISTING 0）、projection ledger 的預期、每位參與者在哪裡、這個 session 從 {@code onInitialize}
     * 起的 ERROR 的預期（{@link SessionErrors}：正常的 restart 沒有；退化的只有永久原因那一則；開服時就是永久原因 SEALED 的只有開服時的提醒那一則，{@link #withStartupReminder}，
     * H9 (b)），以及 M5 authority 是否在本 instance 初始化成功（沒有「M5 authority 初始化失敗」的 ERROR）。
     * <p>F6-2c（F6-2c Step 0 quality 給 F6-2c 本體的建議）：不提供位置參數的建構子（兩個 int 互換位置也能編譯），以具名的 factory 建立（{@link #crossed}、{@link #degraded}），
     * 再以 {@link #placed} 逐人改位置（F6-2a quality 給 W7 的建議：參與者在哪裡由全員一個 {@link Place} 改為逐人指定）。在 step 執行時建立（參與者在開服時才由收據讀回）。
     * F6-2d Step 0（F6-2c quality Minor 8）：記下建立它的 fixture，{@link #placed} 不必再傳一次。
     */
    static final class End {
        private final M5CollapseFixture fixture;
        private final int catalogGrowth;
        private final ProjectionAtEnd projection;
        private final Map<UUID, Place> places;
        /** 這個 session 的 ERROR 的預期（F6-2d-2 B1 起取代 ERROR 的個數與 {@code startupReminder} 旗標）。 */
        private final SessionErrors sessionErrors;
        private final boolean authorityInitialized;
        /** allocation ledger 在這條 chain 的那一筆之後另有的 entry（F6-2d-1，w9io）；預設沒有。 */
        private final List<M5CollapseDiskEvidence.Allocation> otherAllocations;

        private End(M5CollapseFixture fixture, int catalogGrowth, ProjectionAtEnd projection, Map<UUID, Place> places, SessionErrors sessionErrors,
                boolean authorityInitialized, List<M5CollapseDiskEvidence.Allocation> otherAllocations) {
            this.fixture = Objects.requireNonNull(fixture, "fixture");
            this.catalogGrowth = catalogGrowth;
            this.projection = Objects.requireNonNull(projection, "projection");
            this.places = Collections.unmodifiableMap(new LinkedHashMap<>(places));
            this.sessionErrors = Objects.requireNonNull(sessionErrors, "sessionErrors");
            this.authorityInitialized = authorityInitialized;
            this.otherAllocations = List.copyOf(otherAllocations);
        }

        /** 每位參與者（依清單順序）都在 {@code place}。 */
        private static Map<UUID, Place> everyone(M5CollapseFixture fixture, Place place) {
            var result = new LinkedHashMap<UUID, Place>();
            for (var id : fixture.participants()) result.put(id, place);
            if (result.isEmpty()) throw new IllegalStateException("End：這條 chain 還沒有參與者（參與者在開服時才由收據讀回，End 要在 step 執行時建立）");
            return result;
        }

        /** 正常的 restart：(b) 全員都已跨過，參與者都在 U，沒有這個 session 的 ERROR，authority 初始化成功。 */
        static End crossed(M5CollapseFixture fixture) {
            return new End(fixture, fixture.catalogGrowth(), ProjectionAtEnd.COMPLETE, everyone(fixture, Place.UNIVERSE), SessionErrors.NONE, true, List.of());
        }

        /**
         * 沒有人跨過的終局（F6-2c，{@code M5CollapsePassageChains}：通道在開服時結束、SEALED(TRR)）：參與者都在來源世界，投影 COMPLETE，沒有這個 session 的 ERROR，authority
         * 初始化成功。已跨過的人另以 {@link #placed} 改為 U。
         */
        static End inTheSourceWorld(M5CollapseFixture fixture) {
            return new End(fixture, fixture.catalogGrowth(), ProjectionAtEnd.COMPLETE, everyone(fixture, Place.SOURCE), SessionErrors.NONE, true, List.of());
        }

        /**
         * 退化的 restart（開服時 projection ledger 遺失，{@code M5CollapseRevisitChains}）：ledger 不存在、參與者照一般方式開門而都在來源世界，這個 session 的 ERROR
         * 只有永久原因那一則，authority 本 instance 初始化失敗。
         */
        static End degraded(M5CollapseFixture fixture) {
            return new End(fixture, fixture.catalogGrowth(), ProjectionAtEnd.LEDGER_ABSENT, everyone(fixture, Place.SOURCE), SessionErrors.PERMANENT_ENTRY, false, List.of());
        }

        /** 同這一份，只有 {@code who} 的每一位改在 {@code place}（{@code who} 的成員都必須是參與者；以建立這一份的 fixture 決定）。 */
        End placed(M5CollapseFixture.Who who, Place place) {
            Objects.requireNonNull(place, "place");
            var next = new LinkedHashMap<>(places);
            for (var id : fixture.members(who)) {
                if (!next.containsKey(id)) throw new IllegalArgumentException("End 沒有這位參與者：" + id);
                next.put(id, place);
            }
            return new End(fixture, catalogGrowth, projection, next, sessionErrors, authorityInitialized, otherAllocations);
        }

        /**
         * 同這一份，allocation ledger 在這條 chain 的那一筆之後另有 {@code others}（依追加的順序；F6-2d-1，w9io：注入的另一個 session 的追加已落地，spec §6 roll-forward 不移除）。
         * 終局改以 {@code oneAllocationForTheTokenWithTheOthers} 斷言 ledger。
         */
        End withOtherAllocations(List<M5CollapseDiskEvidence.Allocation> others) {
            if (others.isEmpty()) throw new IllegalArgumentException("withOtherAllocations 至少要有一筆");
            return new End(fixture, catalogGrowth, projection, places, sessionErrors, authorityInitialized, others);
        }

        /**
         * 同這一份，但這個 session 開服時就是永久原因 SEALED（H9 (b)，spec v5.10 §11.2「永久原因」、§12 SEALED 列；F6-2d-2 spec review 的 M-2）：這個 session 從 {@code onInitialize}
         * 起的 ERROR 恰為開服時的提醒那一則（{@link SessionErrors#STARTUP_REMINDER}，{@link M5GameTestSupport#STARTUP_SEALED_REMINDER}），以 {@code permanentSealedRemindedOnceAtStartup}
         * 斷言，取代 {@code noSessionError}。只接在沒有其他 ERROR 的 End 之後（{@link SessionErrors#NONE}）。
         */
        End withStartupReminder() {
            if (sessionErrors != SessionErrors.NONE) throw new IllegalStateException("withStartupReminder 只接在沒有其他 ERROR 的 End 之後：" + sessionErrors);
            return new End(fixture, catalogGrowth, projection, places, SessionErrors.STARTUP_REMINDER, authorityInitialized, otherAllocations);
        }

        /** allocation ledger 在這條 chain 的那一筆之後另有的 entry。 */
        List<M5CollapseDiskEvidence.Allocation> otherAllocations() { return otherAllocations; }

        int catalogGrowth() { return catalogGrowth; }

        ProjectionAtEnd projection() { return projection; }

        /** 每位參與者在哪裡（依清單順序）。 */
        Map<UUID, Place> places() { return places; }

        /** 這個 session 的 ERROR 的預期。 */
        SessionErrors sessionErrors() { return sessionErrors; }

        boolean authorityInitialized() { return authorityInitialized; }
    }

    /**
     * spec §12 的終局：同一個 UniverseId（Destination、allocation entry、catalog、discovery、projection entry、ACTIVE world key），catalog 的增量（NEW 恰多 1 筆、
     * EXISTING 不變），每位參與者在 {@code end.places()} 指定的地方；另依 {@code end.sessionErrors()}（{@link SessionErrors}）斷言這個 session 的 ERROR，與 authority 的初始化。
     */
    void expectEnd(End end) throws Exception {
        var universe = fixture.universe();
        fixture.expectCatalog("catalogExactlyOneMore", end.catalogGrowth());
        var catalog = UniverseRegistryState.get(run.server()).flushedRecords();
        var record = catalog.get(universe);
        run.expect("catalogOnDisk", M5CollapseDiskEvidence.catalog(run.data()).keySet().equals(catalog.keySet()), () -> "磁碟上的 catalog 與 flushed 不同");
        if (end.otherAllocations().isEmpty()) fixture.expectOneAllocation();
        else fixture.expectAllocationsWithOthers(end.otherAllocations());
        var discovery = M5CollapseDiskEvidence.discovery(run.saveRoot());
        run.expect("onlyTheDestinationDiscovered", discovery.size() == 1 && discovery.getFirst().universeId().equals(universe) && discovery.getFirst().gameplayEligible(),
                () -> "discovery=" + discovery);
        if (end.projection() == ProjectionAtEnd.COMPLETE) {
            run.expect("projectionComplete", () -> M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                    () -> "projection ledger=" + fixture.projectionsOrError());
        } else {
            run.expect("ledgerNotRecreated", !Files.exists(run.data().resolve(ProjectionLedger.FILE_NAME)), () -> "projection ledger 被重建");
        }
        var world = fixture.universeWorld();
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        run.expect("activeWorldKeyIsTheDestination", world.isPresent() && descriptor != null && world.get().getRegistryKey().equals(descriptor.worldKey()),
                () -> "ACTIVE world 與 catalog 的 world key 不同");
        long universeWorlds = StreamSupport.stream(run.server().getWorlds().spliterator(), false)
                .filter(candidate -> UniverseRegistryState.get(run.server()).findByWorldKey(candidate.getRegistryKey()).isPresent()).count();
        run.expect("oneUniverseWorld", universeWorlds == 1, () -> "Universe world 有 " + universeWorlds + " 個");
        if (!end.places().keySet().equals(Set.copyOf(fixture.participants()))) throw new IllegalArgumentException("End 的參與者與這條 chain 不同：" + end.places().keySet());
        for (var entry : end.places().entrySet()) {
            var player = fixture.player(entry.getKey());
            if (entry.getValue() == Place.UNIVERSE) {
                run.expect("participantInTheUniverse", player.getServerWorld() == world.orElseThrow(), () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue());
            } else {
                run.expect("participantInTheSourceWorld", player.getServerWorld() == run.source(), () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue());
            }
        }
        // quality Minor（F6-2a）：這個 session 從 onInitialize 起的 ERROR（quantumchamber logger），正常的 restart 一則都沒有；退化的只有永久原因那一則；開服時就是永久原因 SEALED
        // 的只有開服時的提醒那一則（H9 (b)）。F6-2d-2 B1（H9 quality Minor 6）：依 SessionErrors 選具名斷言，名稱與條件都與之前相同。
        var errors = run.startupLog().matching(Level.ERROR, fixture.sid().toString()).stream().map(LogCapture.Entry::message).toList();
        var expected = end.sessionErrors();
        boolean holds = errors.size() == expected.count
                && (expected != SessionErrors.STARTUP_REMINDER || errors.getFirst().contains(M5GameTestSupport.STARTUP_SEALED_REMINDER));
        run.expect(expected.assertion, holds, () -> "這個 session 的 ERROR：" + errors);
        if (end.authorityInitialized()) {
            var initErrors = run.startupLog().matching(Level.ERROR, "M5 authority 初始化失敗").stream().map(LogCapture.Entry::message).toList();
            run.expect("noAuthorityInitializationFailure", initErrors.isEmpty(), () -> "初始化的 ERROR：" + initErrors);
        }
        run.proof.put("finalDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), fixture.sid()));
    }
}
