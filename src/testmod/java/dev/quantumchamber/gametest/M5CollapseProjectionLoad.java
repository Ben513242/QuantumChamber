package dev.quantumchamber.gametest;

import static dev.quantumchamber.gametest.M5CollapseFixture.FRAME;

import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberControllerLoadSyncQueue;
import dev.quantumchamber.chamber.ChamberLoadSyncOutcome;
import dev.quantumchamber.chamber.ChamberLoadSyncTestAccess;
import java.util.List;
import java.util.Map;
import net.minecraft.util.math.ChunkPos;

/**
 * 僅 testmod（M5 F6-2a，由 F6-1 的 {@code M5CollapseRecoveryProbe} 拆出）：restart 在 JOIN 之前載入磁碟上的投影 Controller，並觀察它的 load-sync
 * （W4、W6 的辨識，退化的 foreign 分支）。以 probe 的 ticket 載入投影所在的 chunk（{@code SERVER_STARTED} 的 AFTER phase：開服 bootstrap 已載入時，佇列也要到
 * tick 1 才處理，所以觀察得到）。load-sync 處理時就同步 refresh（{@code REDSTONE.onLoad}），讀電位的 ±3 格都在同一個 chunk，所以 level FULL 就夠，不必 ticking。
 */
final class M5CollapseProjectionLoad {
    private final M5CollapseRun run;
    private final ChamberControllerBlockEntity controller;
    private final LogCapture log;
    private ChamberLoadSyncTestAccess.Observation observation;

    private M5CollapseProjectionLoad(M5CollapseRun run, ChamberControllerBlockEntity controller, LogCapture log, ChamberLoadSyncTestAccess.Observation observation) {
        this.run = run;
        this.controller = controller;
        this.log = log;
        this.observation = observation;
    }

    /** 載入 U 的投影 Controller 並開始觀察（停服時停止觀察；log 在 {@code SERVER_STOPPED} 關閉）。 */
    static M5CollapseProjectionLoad start(M5CollapseRun run, M5CollapseFixture fixture) {
        var world = fixture.universeWorld().orElseThrow();
        // load-sync 佇列用自己的 logger（不是 "quantumchamber"）：載入之前就開始收，「沒有 log 提到座標」才涵蓋它。
        var log = new LogCapture(ChamberControllerLoadSyncQueue.class.getName());
        run.onStopped(log);
        var chunk = new ChunkPos(FRAME.controllerPos());
        run.proof.put("projectionChunkLoadedAtStart", world.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null);
        fixture.holdProjectionChunk(world);
        var entity = world.getBlockEntity(FRAME.controllerPos());
        run.expect("projectionControllerLoaded", entity instanceof ChamberControllerBlockEntity,
                () -> "載入之後 U 的 Controller 位置沒有 Controller block entity：" + world.getBlockState(FRAME.controllerPos()));
        var controller = (ChamberControllerBlockEntity) entity;
        run.proof.put("projectionControllerAtLoad", Map.of("instanceKind", controller.instanceKind().name(), "state", controller.chamberState().name(),
                "powerInitialized", controller.powerInitialized(), "loadSyncPending", ChamberLoadSyncTestAccess.hasPending(run.server, controller)));
        var load = new M5CollapseProjectionLoad(run, controller, log, ChamberLoadSyncTestAccess.observe(world, controller));
        run.onStopping(load::close);
        return load;
    }

    /** 載入的投影 Controller（磁碟上的 block entity）。 */
    ChamberControllerBlockEntity controller() { return controller; }

    /** load-sync 佇列的 logger 從載入之前收到的 log。 */
    LogCapture log() { return log; }

    /** 到目前為止這個 Controller 的 load-sync 結果（例如 CONSUMED）。 */
    List<ChamberLoadSyncOutcome> outcomes() { return observation.outcomes(); }

    /** 停止觀察（可重複呼叫）；失敗記在 receipt 的 {@code failures}。 */
    void close() {
        var current = observation;
        observation = null;
        if (current == null) return;
        try {
            current.close();
        } catch (RuntimeException | Error failure) {
            run.failures.add("closeLoadSync：" + failure);
        }
    }
}
