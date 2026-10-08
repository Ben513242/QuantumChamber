package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.gametest.M5CollapseRun.Step;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseRegistryState;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.stream.StreamSupport;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.Level;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：restart 由 RESOLVE 推進到終局的共用部分：step（RESOLVE 完成 → 參與者進入艙內 →
 * PASSAGE_READY → 越過門板中線、傳送到 U → (b) 全員都已跨過的終局）、每個 tick 的不變量（spec §12 的共同斷言），與終局的 spec §12 斷言（{@link #expectEnd}）。
 */
final class M5CollapsePassage {
    private final M5CollapseRun run;
    private final M5CollapseFixture fixture;
    /** PASSAGE_READY 之後等了幾個 tick（門與 Controller 的刷新落地）。 */
    private int readyTicks = -1;

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
        var catalogIds = UniverseRegistryState.get(run.server).records().keySet();
        run.expect("catalogOnlyDestination", catalogIds.stream().allMatch(universe::equals), () -> "Destination 是 " + universe.value() + "，catalog 有 " + catalogIds);
        run.expect(fixture.created() ? "catalogAtMostOneMore" : "catalogNotGrown", catalogIds.size() <= fixture.catalogBefore() + fixture.catalogGrowth(),
                () -> "catalog " + catalogIds.size() + " 筆，crash 前 " + fixture.catalogBefore());
        var current = run.journal.flushedRecords().get(fixture.sid());
        if (current != null) {
            run.expect("sameDestination", M5CollapseFixture.destination(current).equals(Optional.of(universe)), () -> "Destination=" + current.collapse().destination());
            run.expect("noRedraw", M5CollapseFixture.sameCandidate(fixture.receipt(), current), () -> "候選 context、ledger 或 selection 與 crash 前不同");
        }
        for (var id : fixture.participants()) {
            var player = fixture.onlinePlayer(id);
            if (player == null) continue;
            var world = player.getServerWorld();
            if (allowance != null && allowance.awaitingReturn().getAsBoolean() && world == fixture.corridor()) {
                int joined = allowance.joinedAt().getAsInt();
                run.expect("notTrappedInTheCorridor", run.ticks - joined <= allowance.limit(), () -> "JOIN 之後 " + (run.ticks - joined) + " 個 tick 仍在走廊：" + run.diagnostic());
            } else {
                run.expect("participantInSourceOrUniverse", world == run.source || world == fixture.universeWorld().orElse(null),
                        () -> "參與者在 " + world.getRegistryKey().getValue());
            }
        }
    }

    /** restart 由 RESOLVE 推進到終局的 step（各 restart 共用）；{@code prepared} 是窗口表裡 PREPARE 的預期（null：不檢查）。 */
    List<Step> steps(M5CollapseWindows.Prepared prepared) {
        return List.of(
                new Step("resolved", 400, this::resolvedThenEnter),
                new Step("ready", 600, () -> passageReady(prepared)),
                new Step("doorway", 1, () -> {
                    for (var player : fixture.players()) M5GameTestSupport.standInDoorway(player, FRAME, 3.5, 1, 0.3, 0, 0, Vec3d.ZERO);
                    return true;
                }),
                new Step("crossed", 10, this::crossed),
                new Step("ended", 40, this::endedByEveryoneCrossing));
    }

    private boolean resolvedThenEnter() throws Exception {
        if (run.capture.matching(Level.INFO, "目的地已確定（RESOLVE 完成）", fixture.sid().toString()).isEmpty()) return false;
        run.expect("materialized", fixture.universeWorld().isPresent(), () -> "RESOLVE 完成但 U 沒有物化");
        fixture.expectCatalog("catalogExactlyOneMore");
        run.expect("discoveredOnce", M5CollapseDiskEvidence.discovery(run.saveRoot()).stream().filter(record -> record.universeId().equals(fixture.universe())).count() == 1,
                () -> "discovery 沒有恰一筆 U");
        fixture.moveInside();
        return true;
    }

    /** PASSAGE_READY 之後最多再等 5 個 tick，讓門與 Controller 的刷新落地，再斷言 passable、比較器 15 與投影 COMPLETE；之後參與者站到門板中線內側。 */
    private boolean passageReady(M5CollapseWindows.Prepared prepared) throws Exception {
        var record = run.journal.flushedRecords().get(fixture.sid());
        if (record == null || record.state() != SessionState.PASSAGE_READY) return false;
        readyTicks++;
        boolean door = M5GameTestSupport.doorPassable(run.source, FRAME), comparator = fixture.controllerSignal(ChamberState.PASSAGE, 15);
        if (!(door && comparator) && readyTicks < 5) return false;
        run.expect("noOneCrossedYet", record.collapse().crossed().isEmpty(), () -> "Crossed=" + record.collapse().crossed());
        run.expect("doorPassable", door, () -> "PASSAGE_READY 之後門不是 passable 量子膜");
        run.expect("comparatorFifteen", comparator, () -> "PASSAGE_READY 之後 Controller 不是 PASSAGE／比較器 15");
        run.expect("projectionComplete", M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        M5CollapseWindows.expectPrepared(run, fixture, prepared);
        for (var player : fixture.players()) M5GameTestSupport.standInDoorway(player, FRAME, 3.5, 1, 0.7, 0, 0, Vec3d.ZERO);
        return true;
    }

    private boolean crossed() {
        var world = fixture.universeWorld().orElseThrow();
        var players = fixture.players();
        if (players.stream().anyMatch(player -> player.getServerWorld() != world)) return false;
        var record = run.journal.flushedRecords().get(fixture.sid());
        var participants = fixture.participants();
        run.expect("crossedRecorded", record == null || record.collapse().crossed().equals(participants), () -> "Crossed=" + record.collapse().crossed());
        run.expect("oneCrossingInfo", run.capture.matching(Level.INFO, "參與者已穿過量子通道", fixture.sid().toString()).size() == participants.size(),
                () -> "「參與者已穿過量子通道」不是每位參與者恰一則");
        return true;
    }

    private boolean endedByEveryoneCrossing() throws Exception {
        var sid = fixture.sid();
        if (run.journal.flushedRecords().containsKey(sid) || run.journal.records().containsKey(sid)) return false;
        run.expect("endedByEveryoneCrossing", run.capture.matching(Level.INFO, "量子通道已結束", "(b) 全員都已跨過", sid.toString()).size() == 1,
                () -> "沒有恰一則 (b) 的結束 INFO");
        run.expect("plainOpenAfterTheEnd", M5GameTestSupport.doorAll(run.source, FRAME, true, MembraneState.NONE), () -> "終局之後原艙門不是一般開門");
        expectEnd(new End(fixture.catalogGrowth(), ProjectionAtEnd.COMPLETE, Place.UNIVERSE));
        return true;
    }

    // ---- 終局（spec §12） ----

    /** 終局時 projection ledger 的預期：這座艙在 U 的 entry 是 COMPLETE。 */
    enum ProjectionAtEnd { COMPLETE }

    /** 終局時參與者在哪裡：U（跨越成功）。 */
    enum Place { UNIVERSE }

    /** 終局的 spec §12 斷言的參數：catalog 相對於 crash 前的增量（NEW 1、EXISTING 0）、projection ledger 的預期、參與者在哪裡。 */
    record End(int catalogGrowth, ProjectionAtEnd projection, Place participants) {}

    /**
     * spec §12 的終局：同一個 UniverseId（Destination、allocation entry、catalog、discovery、projection entry、ACTIVE world key），catalog 的增量（NEW 恰多 1 筆、
     * EXISTING 不變），每位參與者在 {@code end.participants()}。
     */
    void expectEnd(End end) throws Exception {
        var universe = fixture.universe();
        fixture.expectCatalog("catalogExactlyOneMore", end.catalogGrowth());
        var catalog = UniverseRegistryState.get(run.server).flushedRecords();
        var record = catalog.get(universe);
        run.expect("catalogOnDisk", M5CollapseDiskEvidence.catalog(run.data()).keySet().equals(catalog.keySet()), () -> "磁碟上的 catalog 與 flushed 不同");
        fixture.expectOneAllocation();
        var discovery = M5CollapseDiskEvidence.discovery(run.saveRoot());
        run.expect("discoveredOnce", discovery.size() == 1 && discovery.getFirst().universeId().equals(universe) && discovery.getFirst().gameplayEligible(),
                () -> "discovery=" + discovery);
        run.expect("projectionComplete", M5CollapseDiskEvidence.projections(run.data()).equals(List.of(fixture.projection())),
                () -> "projection ledger=" + fixture.projectionsOrError());
        var world = fixture.universeWorld();
        var descriptor = record.definition().worlds().get(DimensionRole.OVERWORLD);
        run.expect("activeWorldKeyIsTheDestination", world.isPresent() && descriptor != null && world.get().getRegistryKey().equals(descriptor.worldKey()),
                () -> "ACTIVE world 與 catalog 的 world key 不同");
        long universeWorlds = StreamSupport.stream(run.server.getWorlds().spliterator(), false)
                .filter(candidate -> UniverseRegistryState.get(run.server).findByWorldKey(candidate.getRegistryKey()).isPresent()).count();
        run.expect("oneUniverseWorld", universeWorlds == 1, () -> "Universe world 有 " + universeWorlds + " 個");
        for (var player : fixture.players()) {
            run.expect("participantInTheUniverse", player.getServerWorld() == world.orElseThrow(), () -> "參與者在 " + player.getServerWorld().getRegistryKey().getValue());
        }
        run.proof.put("finalDisk", M5CollapseDiskEvidence.summary(run.saveRoot(), fixture.sid()));
    }
}
