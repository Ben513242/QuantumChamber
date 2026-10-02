package dev.quantumchamber.gametest;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberProtectionService;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.chamber.ChamberState;
import dev.quantumchamber.chamber.MembraneState;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseService;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.gametest.mixin.MinecraftServerAutosaveTestAccessor;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.apache.logging.log4j.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 GameTest 的共用支援（F4a-0）：default run 的 {@code M5CollapseGameTests}、M5 run 的 {@code M5UniverseGameTests}，以及
 * {@code M4CandidateDoorGameTests} 中 M5 期間加入的 test 共用。用法見 {@code scripts/verification/README.md}「新增 M5 GameTest」。
 * <ul>
 * <li>排程：{@link #at}、{@link #whenTicks}（以 server tick 計的有界等待；失敗路徑先釋放 hooks）。</li>
 * <li>journal flush 探針：{@link #installFlushProbe}（裝在 {@link M5TestHooks}，hooks 釋放時移除）與計數探針 {@link FlushFault}。</li>
 * <li>原生 autosave 的窗口與 journal 寫出稽核：{@link #afterAutosaveClear}、{@link AutosaveWindow#journalWrites}。</li>
 * <li>塌縮 fixture：{@link #collapseFixture}、{@link #cleanupFixtures}；真原艙＋DORMANT 收據＋玩家在艙外：{@link DormantOrigin}；
 *     Nether 的真原艙＋真玩家：{@link NetherChamber}。</li>
 * <li>候選門：{@link CandidateDoor}、{@link #candidateDoor}、{@link #sourceDoor}。</li>
 * </ul>
 * 這裡的程式不得丟出例外到 {@code END_SERVER_TICK}：探針只在 journal flush 的開頭丟出受控的例外（那次寫出因此失敗）。
 */
final class M5GameTestSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");

    private M5GameTestSupport() {}

    // ---- 排程 ----

    /** 在 {@code tick} 執行；例外或斷言失敗時先釋放 hooks（可為 null）。 */
    static void at(TestContext context, long tick, M2CorridorGameTests.TestHooks hooks, Runnable action) {
        context.runAtTick(tick, () -> {
            try { action.run(); }
            catch (RuntimeException | Error failure) { if (hooks != null) hooks.releaseAfter(failure); throw failure; }
        });
    }

    /**
     * 以 server tick 計的有界等待：GameTest server 不以 20 TPS 節流，wall-clock 期限可能晚於 tickLimit 才到，診斷訊息會被通用逾時蓋掉。
     * 從 {@code tick} 起每個 tick 檢查一次 {@code condition}，成立時以那個 tick 呼叫 {@code ready}；到 {@code tick+ticks} 仍不成立就附
     * {@code diagnostics} 失敗。逾時、斷言失敗或例外時先釋放 hooks（可為 null）。
     */
    static void whenTicks(TestContext context, int tick, int ticks, BooleanSupplier condition, IntConsumer ready,
            Supplier<String> diagnostics, M2CorridorGameTests.TestHooks hooks) {
        whenTicksUntil(context, tick, tick + ticks, condition, ready, diagnostics, hooks);
    }

    private static void whenTicksUntil(TestContext context, int tick, int last, BooleanSupplier condition, IntConsumer ready,
            Supplier<String> diagnostics, M2CorridorGameTests.TestHooks hooks) {
        context.runAtTick(tick, () -> {
            try {
                if (condition.getAsBoolean()) ready.accept(tick);
                else {
                    context.assertTrue(tick < last, "有界等待逾時（至 tick " + last + "）：" + diagnostics.get());
                    whenTicksUntil(context, tick + 1, last, condition, ready, diagnostics, hooks);
                }
            } catch (RuntimeException | Error failure) {
                if (hooks != null) hooks.releaseAfter(failure);
                throw failure;
            }
        });
    }

    // ---- journal flush 探針 ----

    /** 把 {@code probe} 裝成 journal flush 探針（{@link M5TestHooks#beforeJournalFlush}）；hooks 釋放時，若仍是它就移除。 */
    static <T extends Consumer<SessionRecoveryState>> T installFlushProbe(T probe, M2CorridorGameTests.TestHooks hooks) {
        M5TestHooks.flushProbe = probe;
        hooks.add(() -> { if (M5TestHooks.flushProbe == probe) M5TestHooks.flushProbe = null; });
        return probe;
    }

    /**
     * 「記 tick、前 N 次失敗」的計數探針（F4a-0 合併 F3d 的 CommitFault、ReturnWriteFault 與 G-RFB、G-SEAL、G-TERM 的探針）。每次 journal flush
     * 的開頭對 journal 求 {@code matches}：成立就記下 server tick；武裝時前 {@code failFirst} 次以 {@code message} 丟出
     * IllegalStateException，那次寫出因此在 flush 開頭受控失敗。只在 server thread、journal flush 的開頭被呼叫。
     */
    static final class FlushFault implements Consumer<SessionRecoveryState> {
        private final MinecraftServer server;
        private final String message;
        private final int failFirst;
        private final Predicate<SessionRecoveryState> matches;
        private final List<Integer> ticks = new CopyOnWriteArrayList<>();
        private volatile int failures;
        private volatile boolean armed = true;

        FlushFault(MinecraftServer server, String message, int failFirst, Predicate<SessionRecoveryState> matches) {
            this.server = server; this.message = message; this.failFirst = failFirst; this.matches = matches;
        }

        @Override public void accept(SessionRecoveryState journal) {
            if (!matches.test(journal)) return;
            ticks.add(server.getTicks());
            if (armed && failures < failFirst) {
                failures++;
                throw new IllegalStateException(message);
            }
        }

        /** 每一次符合條件的寫出（含受控失敗的那幾次）所在的 server tick，依發生順序。 */
        List<Integer> ticks() { return List.copyOf(ticks); }
        /** 已受控失敗的次數。 */
        int failures() { return failures; }
        /** 第一次符合條件的 server tick；還沒有時為 −1。 */
        int first() { var all = ticks(); return all.isEmpty() ? -1 : all.getFirst(); }
        /** 受控失敗之後、照常寫出的那幾次。 */
        List<Integer> retries() { var all = ticks(); return List.copyOf(all.subList(Math.min(failures, all.size()), all.size())); }
        /** 之後不再受控失敗（仍記 tick）。 */
        void disarm() { armed = false; }
    }

    // ---- 原生 autosave 的窗口 ----

    /**
     * 原生 autosave 的排程（F3d fix round 3，spec re-review m-1）。Minecraft 1.21（Yarn 1.21+build.9）的 {@code MinecraftServer.tick}：
     * {@code ++ticks} → {@code tickWorlds}（GameTest 的 callback 在其中的 {@code TestManager.tick} 執行）→ {@code ticksUntilAutosave} 減一，
     * ≤0 時重設為 {@code getAutosaveInterval()}（20 TPS、沒有 sprint 時是 6000）並 {@code saveAll} → Fabric 的 {@code END_SERVER_TICK}
     * （recovery 的 RETRY_FLUSH、塌縮流程）。autosave 不經過 journal 的 flush 探針，會直接把尚未確認的 roll-forward 寫出確認，探針因此少記
     * 重試。callback 當下讀到倒數 c（≥1）時，下一次 autosave 發生在 server tick {@code getTicks()+c-1}：那個 tick 的 callback 之後、
     * END_SERVER_TICK 之前。
     */
    static int nextAutosaveTick(MinecraftServer server) {
        return server.getTicks() + ((MinecraftServerAutosaveTestAccessor) server).quantumchamberTest$getTicksUntilAutosave() - 1;
    }

    /**
     * 開始注入之前的等待：從 {@code tick} 起逐 tick 檢查，等到「從這個 tick 起連續 {@code window} 個 server tick 都不會有原生 autosave」
     * （下一次 autosave 在 {@code getTicks()+window} 或更晚），才在那個 tick 的 callback 開始稽核 journal 寫出（{@link M5TestHooks#startJournalAudit}，
     * hooks 釋放時移除）並呼叫 {@code start}（test tick、窗口）。不成立時，倒數最晚再 {@code window} 個 tick 就觸發 autosave 並重設為一整個間隔，
     * 所以最多等 window＋1 個 tick；等不到代表 autosave 間隔被改過（{@code /tick rate}、{@code /tick sprint}），以失敗結束。窗口要涵蓋從開始注入
     * 到最後一次被探針計數的寫出。等待只降低誤判的機率；窗口內有沒有繞過探針的寫出，由 {@link AutosaveWindow#journalWrites} 判定與診斷。
     */
    static void afterAutosaveClear(TestContext context, int tick, String name, int window,
            BiConsumer<Integer, AutosaveWindow> start, M2CorridorGameTests.TestHooks hooks) {
        var server = context.getWorld().getServer();
        whenTicks(context, tick, window + 2, () -> nextAutosaveTick(server) - server.getTicks() >= window, ready -> {
            var audit = M5TestHooks.startJournalAudit(server, hooks);
            var clear = new AutosaveWindow(name, window, server.getTicks(), nextAutosaveTick(server), audit);
            LOGGER.info("M5_AUTOSAVE_CLEAR name={} start={} nextAutosave={} window={}", name, clear.start(), clear.autosaveAt(), window);
            start.accept(ready, clear);
        }, () -> "等不到 " + window + " 個 tick 內沒有原生 autosave 的窗口：tick=" + server.getTicks() + " nextAutosave=" + nextAutosaveTick(server), hooks);
    }

    /** {@link #afterAutosaveClear} 開始注入的 server tick、當時的下一次原生 autosave，以及從那時起的 journal 寫出稽核。 */
    record AutosaveWindow(String name, int window, int start, int autosaveAt, M5TestHooks.JournalWriteAudit audit) {
        /**
         * 窗口內每一次 journal 寫出都經過探針（F4a-0，F3d quality re-review m-1）：從開始注入到現在，正式檔的每一次實際寫出都經
         * {@code SessionRecoveryState.flush}（探針在 flush 開頭看得到），而且探針看到的每一次 flush（含受控失敗的那幾次）只帶 {@code own} 的
         * session 的變更。違反的事件逐則列在 {@link JournalWindow#problems}，並區分旁路的種類：原生 autosave、{@code server.save}（附呼叫端，例如
         * {@code ChunkSaveCheckpoint}）、停服存檔、其他寫出，以及其他 session 的 flush（例如前一個 batch 殘留的 session）。另記一行
         * {@code M5_JOURNAL_WINDOW}：被探針計數的 server tick、窗口內每一個事件，以及原生 autosave 的倒數是否移動過（{@code nativeAutosave}，
         * 即 F3d 的 {@code untouched} 為假；autosave 時 journal 沒有變更就不會寫出，所以只供診斷，不單獨判定）。
         */
        JournalWindow journalWrites(MinecraftServer server, List<Integer> counted, Collection<UUID> own) {
            int next = nextAutosaveTick(server);
            boolean nativeAutosave = next != autosaveAt;
            var verdict = audit.verdict(own);
            LOGGER.info("M5_JOURNAL_WINDOW name={} start={} now={} autosaveAt={} nextAutosave={} nativeAutosave={} counted={} own={} events={} problems={} status={}",
                    name, start, server.getTicks(), autosaveAt, next, nativeAutosave, counted, own, verdict.events(), verdict.problems(),
                    verdict.pass() ? "PASS" : "FAIL");
            return new JournalWindow(verdict.pass(), nativeAutosave, verdict.problems());
        }
    }

    /** {@link AutosaveWindow#journalWrites} 的結果：窗口內的寫出是否都經過探針、原生 autosave 的倒數是否移動過、違反的事件。 */
    record JournalWindow(boolean onlyProbed, boolean nativeAutosave, List<String> problems) { }

    // ---- 塌縮 fixture ----

    /** {@link #collapseFixture(MinecraftServer, ChamberOriginAuthority, Map, List, boolean, SessionState, boolean, long)}，時間取 overworld 的 game time。 */
    static SessionRecoveryRecord collapseFixture(MinecraftServer server, ChamberOriginAuthority origin, Map<UUID, Boolean> people,
            List<SessionRecoveryRecord.SpaceLease> leases, boolean newCandidate, SessionState phase, boolean suspend) {
        return collapseFixture(server, origin, people, leases, newCandidate, phase, suspend, server.getOverworld().getTime());
    }

    /**
     * M5 fixture（F2a 裁定 (c)）：MEASURED 收據（選中 NEW 或 SOURCE）put＋flush 之後，只經 commitRestore（M→C、C→R）或 commitRollForward（C→S）
     * 進入指定狀態，最後確認 exact 落盤；全部在同一個 callback 內完成，塌縮流程看不到中間狀態。{@code suspend} 為真時先暫停這個 session 的
     * 塌縮流程（fixture 只用來驗證別的規則，決策 7）。SEALED 與 PASSAGE_READY 只能接在 UNIVERSE 目的地之後（{@code newCandidate} 為真）。
     */
    static SessionRecoveryRecord collapseFixture(MinecraftServer server, ChamberOriginAuthority origin, Map<UUID, Boolean> people,
            List<SessionRecoveryRecord.SpaceLease> leases, boolean newCandidate, SessionState phase, boolean suspend, long time) {
        var journal = SessionRecoveryState.get(server);
        var measured = M4CandidateTestAccess.measuredReceipt(server, origin, people, leases, newCandidate);
        if (suspend) M5TestHooks.suspendCollapse(measured.sessionUuid());
        journal.put(measured); journal.flush(server);
        var record = M4CandidateTestAccess.collapsing(measured, time);
        requireCommitted(journal.commitRestore(server, measured, record), "MEASURED→COLLAPSING");
        if (phase == SessionState.PASSAGE_READY) {
            var ready = record.withCollapse(SessionState.PASSAGE_READY, record.collapse().withPassage(time));
            requireCommitted(journal.commitRestore(server, record, ready), "COLLAPSING→PASSAGE_READY"); record = ready;
        } else if (phase == SessionState.SEALED) {
            var sealed = record.withCollapse(SessionState.SEALED, record.collapse().sealed(SealedReason.PROJECTION_FAILED));
            requireCommitted(journal.commitRollForward(server, record, sealed), "COLLAPSING→SEALED"); record = sealed;
        }
        if (!record.equals(journal.flushedRecords().get(record.sessionUuid()))) throw new IllegalStateException("M5 fixture 未 exact 落盤：" + phase);
        return record;
    }

    private static void requireCommitted(SessionRecoveryState.CommitResult result, String edge) {
        if (!result.committed()) throw new IllegalStateException("M5 fixture " + edge + " 未確認：" + result);
    }

    /**
     * 收尾：{@code owned} 中還在 journal 的 fixture 依 {@link #removeFixture} 移除；不在 journal（current 與 flushed 都沒有）的，以正式 API 釋放
     * runtime 與來源 ticket（已由 production 終局的也再呼叫一次，冪等）。
     */
    static void cleanupFixtures(MinecraftServer server, Collection<UUID> owned) {
        var journal = SessionRecoveryState.get(server);
        for (var sid : List.copyOf(owned)) {
            var record = journal.flushedRecords().get(sid);
            if (record != null) removeFixture(server, record);
            if (!journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid)) M4CandidateTestAccess.releaseAfterTerminal(server, sid);
        }
    }

    /** 收尾：flushed 中符合條件的 fixture 依 {@link #removeFixture} 移除，再以正式 API 釋放 runtime 與來源 ticket。 */
    static void cleanupFixtures(MinecraftServer server, Predicate<SessionRecoveryRecord> owned) {
        var journal = SessionRecoveryState.get(server);
        for (var record : List.copyOf(journal.flushedRecords().values())) if (owned.test(record)) {
            removeFixture(server, record);
            var sid = record.sessionUuid();
            if (!journal.flushedRecords().containsKey(sid) && !journal.records().containsKey(sid)) M4CandidateTestAccess.releaseAfterTerminal(server, sid);
        }
    }

    /**
     * 移除一份 fixture record：先移除沒有 lease 的 space（restoreSpace 為它建立的；有 lease 的 space 不處理），再依狀態移除——塌縮狀態走終局 API，
     * MEASURED（收據，或 collapseFixture 中途失敗留下的）以 testmod 反射移除，其他以一般 remove＋flush。只用於沒有走廊 lease 的 space。
     */
    private static void removeFixture(MinecraftServer server, SessionRecoveryRecord record) {
        var journal = SessionRecoveryState.get(server); var sid = record.sessionUuid();
        M4CandidateTestAccess.removeSpaceForTeardown(CorridorPageManager.forServer(server), sid);
        if (record.state().collapseState()) M4CandidateTestAccess.terminateCollapseFixture(server, sid);
        else if (record.state() == SessionState.MEASURED) M4CandidateTestAccess.removeMeasuredForTeardown(server, sid);
        else { journal.remove(sid); journal.flush(server); }
    }

    /** flushed 的狀態名稱；沒有 record 時為 ABSENT（診斷訊息用）。 */
    static String state(SessionRecoveryState journal, UUID sid) {
        var record = journal.flushedRecords().get(sid);
        return record == null ? "ABSENT" : record.state().name();
    }

    /**
     * capture 期間，這個 session 沒有任何塌縮流程（{@code CollapseService}）的 WARN 或 ERROR（F4a-0，F3d quality re-review m-2）。依 SLF4J marker
     * 篩選，不依訊息字串，所以新的 WARN 用什麼字句都會被看到：
     * <ul>
     * <li>帶 {@code CollapseService.LOG_MARKER}（含子 marker）、訊息含這個 sid 的 WARN／ERROR（能歸屬到 session 的都帶 {@code session=<UUID>}）；</li>
     * <li>任何帶 {@code CollapseService.TICK_LOG_MARKER} 的 WARN／ERROR：整個 tick 無法執行時的 WARN 不屬於任何 session，沒有 sid 可帶，
     *     所以以「整個 capture 期間沒有」涵蓋。</li>
     * </ul>
     * 判定之前先以 production 的子 marker 記一行 INFO 探針（{@code M5_COLLAPSE_MARKER_PROBE}），確認 capture 讀得到 marker 與父子關係；讀不到時
     * 丟出 IllegalStateException，不以空結果冒充「沒有 WARN」。
     */
    static boolean collapseFlowNeverWarned(LogCapture capture, UUID sid) {
        String flow = CollapseService.LOG_MARKER.getName(), tick = CollapseService.TICK_LOG_MARKER.getName();
        String probe = "M5_COLLAPSE_MARKER_PROBE " + UUID.randomUUID();
        LoggerFactory.getLogger("quantumchamber").info(CollapseService.TICK_LOG_MARKER, probe);
        if (capture.marked(Level.INFO, tick, probe).size() != 1 || capture.marked(Level.INFO, flow, probe).size() != 1)
            throw new IllegalStateException("log capture 讀不到塌縮流程的 marker（" + flow + "／" + tick + "）");
        for (var level : List.of(Level.WARN, Level.ERROR))
            if (!capture.marked(level, flow, sid.toString()).isEmpty() || !capture.marked(level, tick).isEmpty()) return false;
        return true;
    }

    /**
     * 真原艙（未供電、未註冊）＋一位在艙外的真玩家（G-DOR、G-TERM、G-RES 共用；之後各自寫入 DORMANT 收據）。{@link #open} 依序登記收尾：
     * 關閉玩家連線、斷電、移除 {@link #owned} 中的 fixture（{@link #cleanupFixtures}），再建置；之後開始的 {@link #capture} 也在 hooks 中關閉。
     * 已註冊的原艙比照其他原生 test 留在原地（只斷電）；不清除方塊，避免留下沒有 Controller 的 registry record。
     */
    static final class DormantOrigin {
        /** Controller 上方的紅石塊位置（test 結構的相對座標）。 */
        static final BlockPos POWER = ChamberGameTestBuilder.CONTROLLER.up();
        final TestContext context;
        final ServerWorld world;
        final MinecraftServer server;
        final ChamberFrame frame;
        final ConnectedGameTestPlayer connection;
        final LogCapture capture;
        /** 收尾時要移除的 session（{@link #own} 寫入的收據，或 test 自己加入的）。 */
        final List<UUID> owned;
        private final M2CorridorGameTests.TestHooks hooks;

        private DormantOrigin(TestContext context, ChamberFrame frame, ConnectedGameTestPlayer connection, LogCapture capture, List<UUID> owned,
                M2CorridorGameTests.TestHooks hooks) {
            this.context = context; this.world = context.getWorld(); this.server = world.getServer(); this.frame = frame;
            this.connection = connection; this.capture = capture; this.owned = owned; this.hooks = hooks;
        }

        /** 建置朝北的原艙、連線一位玩家（不受重力）並移到艙外，再開始擷取 {@code quantumchamber} 的 log。失敗時先釋放 hooks。 */
        static DormantOrigin open(TestContext context, M2CorridorGameTests.TestHooks hooks) {
            var server = context.getWorld().getServer();
            var frame = new ChamberFrame(context.getAbsolutePos(ChamberGameTestBuilder.CONTROLLER), Direction.NORTH);
            var owned = new ArrayList<UUID>();
            ConnectedGameTestPlayer[] connection = {null};
            hooks.add(() -> { if (connection[0] != null) connection[0].close(); });
            hooks.add(() -> context.setBlockState(POWER, Blocks.AIR));
            hooks.add(() -> cleanupFixtures(server, owned));
            try {
                ChamberGameTestBuilder.buildForSession(context, false, Direction.NORTH, false);
                connection[0] = new ConnectedGameTestPlayer(context.getWorld());
                connection[0].player().setNoGravity(true);
                moveOutside(connection[0].player(), frame);
                var capture = new LogCapture("quantumchamber"); hooks.add(capture::close);
                return new DormantOrigin(context, frame, connection[0], capture, owned, hooks);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        }

        ServerPlayerEntity player() { return connection.player(); }

        /** 第 2 tick 供電；原艙註冊並進入 IDLE（最多 40 tick）之後，以那個 tick 與 Controller 的 chamber UUID 呼叫 {@code ready}。 */
        void whenRegistered(BiConsumer<Integer, UUID> ready) {
            context.waitAndRun(2, () -> context.setBlockState(POWER, Blocks.REDSTONE_BLOCK));
            whenTicks(context, 3, 40, () -> registeredIdle(world, frame), registered -> ready.accept(registered, controller(world, frame).chamberUuid()),
                    () -> "原艙沒有註冊並進入 IDLE", hooks);
        }

        /** 這座原艙上的 MEASURED＋SELECTED 收據（只建立，不寫入）；參與者的值是是否已返還，沒有 lease。全員已返還即 DORMANT。 */
        SessionRecoveryRecord receipt(UUID chamber, Map<UUID, Boolean> people, boolean newCandidate) {
            return M4CandidateTestAccess.measuredReceipt(server, M4CandidateTestAccess.origin(chamber, frame.controllerPos()), people, List.of(), newCandidate);
        }

        /** 加入 {@link #owned}（收尾時移除），再 put＋flush。 */
        SessionRecoveryRecord own(SessionRecoveryRecord record) {
            var journal = SessionRecoveryState.get(server);
            owned.add(record.sessionUuid());
            journal.put(record); journal.flush(server);
            return record;
        }
    }

    /**
     * Nether 的真原艙＋一位真玩家（G-NETHER、G-NLIVE 共用）：強制載入涵蓋的 chunk、以石頭圍住（隔開岩漿與地形）並蓋朝北的原艙，玩家移到艙內、
     * 不受重力、有 Buff。{@link #open} 依序登記收尾：解除強制載入、關閉玩家連線、斷電。已註冊的 Nether 原艙比照其他原生 test 留在原地（只斷電）；
     * 不清除方塊，避免留下沒有 Controller 的 registry record。
     */
    static final class NetherChamber {
        final ServerWorld nether;
        final ChamberFrame frame;
        final BlockPos power;
        final ConnectedGameTestPlayer connection;

        private NetherChamber(ServerWorld nether, ChamberFrame frame, ConnectedGameTestPlayer connection) {
            this.nether = nether; this.frame = frame; this.power = frame.controllerPos().up(); this.connection = connection;
        }

        static NetherChamber open(TestContext context, BlockPos controller, M2CorridorGameTests.TestHooks hooks) {
            var nether = context.getWorld().getServer().getWorld(World.NETHER);
            var frame = new ChamberFrame(controller, Direction.NORTH);
            var power = frame.controllerPos().up();
            var chunks = netherBunkerChunks(frame);
            ConnectedGameTestPlayer[] connection = {null};
            hooks.add(() -> chunks.forEach(chunk -> nether.setChunkForced(chunk.x, chunk.z, false)));
            hooks.add(() -> { if (connection[0] != null) connection[0].close(); });
            hooks.add(() -> nether.setBlockState(power, Blocks.AIR.getDefaultState()));
            try {
                chunks.forEach(chunk -> nether.setChunkForced(chunk.x, chunk.z, true));
                buildNetherChamber(nether, frame);
                connection[0] = new ConnectedGameTestPlayer(context.getWorld());
                var player = connection[0].player();
                var inside = ChamberSpaceCoordinates.position(frame, 3.5, 1, 3.5);
                player.teleport(nether, inside.x, inside.y, inside.z, Set.of(), 0, 0);
                player.setNoGravity(true); player.setVelocity(Vec3d.ZERO);
                player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
                return new NetherChamber(nether, frame, connection[0]);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        }

        ServerPlayerEntity player() { return connection.player(); }

        /** 這座艙（依 Controller 的 chamber UUID）在 journal 裡的 flushed record；尚未註冊或沒有 session 時為 null。 */
        SessionRecoveryRecord record(SessionRecoveryState journal) {
            if (!(nether.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity controller) || controller.chamberUuid() == null) return null;
            return journal.flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(controller.chamberUuid())).findFirst().orElse(null);
        }

        /** 第 2 tick 供電；真入場、走廊發布 358 扇側門（最多 20000 tick）之後，以那個 tick 呼叫 {@code ready}。 */
        void whenEntered(TestContext context, M2CorridorGameTests.TestHooks hooks, IntConsumer ready) {
            var server = context.getWorld().getServer();
            var journal = SessionRecoveryState.get(server); var pages = CorridorPageManager.forServer(server);
            at(context, 2, hooks, () -> nether.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState()));
            whenTicks(context, 3, 20000, () -> {
                var record = record(journal);
                return record != null && record.state() == SessionState.SUPERPOSITION && pages.currentMappings(record.sessionUuid()).epoch() == 1
                        && pages.selectableDoors(record.sessionUuid()).size() == 358;
            }, ready, () -> "Nether 原艙沒有入場：record=" + Optional.ofNullable(record(journal)).map(value -> value.state().name()).orElse("ABSENT"),
                    hooks);
        }

        /** Nether fixture 原艙所在的 chunk（含外圍石牆）。 */
        private static List<ChunkPos> netherBunkerChunks(ChamberFrame frame) {
            var a = ChamberSpaceCoordinates.block(frame, -3, -1, -5); var b = ChamberSpaceCoordinates.block(frame, 9, 9, 9);
            var chunks = new ArrayList<ChunkPos>();
            for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
                for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) chunks.add(new ChunkPos(x, z));
            return chunks;
        }

        /**
         * Nether 的 fixture 原艙：以艙的區域座標，外圍（x −3..9、y −1..9、z −5..9 的邊界）一圈石頭、其餘清空，再照 {@link ChamberGameTestBuilder}
         * 的配置蓋 7×7×7 的艙（基岩外殼、關著的 25 格門、朝北的 Controller）。
         */
        private static void buildNetherChamber(ServerWorld nether, ChamberFrame frame) {
            ChamberProtectionService.get().authorizedMutation(() -> {
                for (int x = -3; x <= 9; x++) for (int y = -1; y <= 9; y++) for (int z = -5; z <= 9; z++) {
                    boolean boundary = x == -3 || x == 9 || y == -1 || y == 9 || z == -5 || z == 9;
                    boolean chamber = x >= 0 && x <= 6 && y >= 0 && y <= 6 && z >= 0 && z <= 6;
                    BlockState state;
                    if (boundary) state = Blocks.STONE.getDefaultState();
                    else if (!chamber) state = Blocks.AIR.getDefaultState();
                    else if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) state = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, false);
                    else state = ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                    nether.setBlockState(ChamberSpaceCoordinates.block(frame, x, y, z), state, Block.NOTIFY_ALL);
                }
                nether.setBlockState(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                        .with(ChamberControllerBlock.FACING, frame.outwardFacing()), Block.NOTIFY_ALL);
            });
        }
    }

    // ---- 候選門 ----

    /** 一扇候選門（SOURCE、NEW 或 EXISTING）與它可選擇的門格。 */
    record CandidateDoor(DoorKey key, BlockPos cell) { }

    /** 這個 session 第一個 mapping 裡的一扇 SOURCE 候選門（依 station 與側別排序取第一扇），以及它可選擇的門格。 */
    static CandidateDoor sourceDoor(M2CorridorGameTests.NativeEntry fixture) {
        return candidateDoor(fixture.pages, fixture.target, fixture.record().sessionUuid(), QuantumCandidate.Source.class);
    }

    /** 同 {@link #sourceDoor}，但指定候選種類。 */
    static CandidateDoor candidateDoor(CorridorPageManager pages, ServerWorld target, UUID sid, Class<? extends QuantumCandidate> kind) {
        var view = pages.currentMappings(sid).instances().getFirst();
        return pages.selectableDoors(sid).stream()
                .filter(key -> kind.isInstance(pages.flushedCandidate(key).orElse(null)))
                .sorted(Comparator.comparingLong(DoorKey::logicalStationIndex).thenComparing(DoorKey::wallSide))
                .map(key -> new CandidateDoor(key, doorCell(view, key)))
                .filter(door -> pages.selectableDoor(target, door.cell()).equals(Optional.of(door.key())))
                .findFirst().orElseThrow(() -> new IllegalStateException("358 扇側門裡找不到 " + kind.getSimpleName() + " 候選門"));
    }

    private static BlockPos doorCell(CorridorPageManager.MappingView view, DoorKey key) {
        var frame = new ChamberFrame(view.localBlockOrigin().offset(view.outwardFacing().rotateYCounterclockwise(), 3).up(6), view.outwardFacing());
        return CorridorGeometry.block(frame, key.wallSide() == DoorKey.DoorWallSide.NEGATIVE_LATERAL ? 0 : 6, 1,
                Math.toIntExact(key.logicalStationIndex() * 8 + 1 - view.logicalAnchorBlock()));
    }

    // ---- 原艙與玩家 ----

    /** 以玩家的身分使用 {@code pos} 的方塊（門、側門）。 */
    static ActionResult interact(ServerWorld world, BlockPos pos, PlayerEntity player) {
        return world.getBlockState(pos).onUse(world, player, new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false));
    }

    /** 25 格門都是 bulkhead，而且 open 與 membrane 都符合。 */
    static boolean doorAll(ServerWorld world, ChamberFrame frame, boolean open, MembraneState membrane) {
        return door(frame).stream().allMatch(pos -> doorIs(world, pos, open, membrane));
    }

    static boolean doorIs(ServerWorld world, BlockPos pos, boolean open, MembraneState membrane) {
        BlockState state = world.getBlockState(pos);
        return state.isOf(ModBlocks.QUANTUM_BULKHEAD) && state.get(QuantumBulkheadBlock.OPEN) == open
                && state.get(QuantumBulkheadBlock.MEMBRANE) == membrane;
    }

    /** 原艙門的 25 格（區域 x、y 1..5，z 0）。 */
    static List<BlockPos> door(ChamberFrame frame) {
        List<BlockPos> positions = new ArrayList<>(25);
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) positions.add(ChamberGeometry.localToWorld(frame, x, y, 0));
        return positions;
    }

    static ChamberControllerBlockEntity controller(ServerWorld world, ChamberFrame frame) {
        return (ChamberControllerBlockEntity) world.getBlockEntity(frame.controllerPos());
    }

    /** Controller 已註冊（有 chamber UUID）而且是 IDLE。 */
    static boolean registeredIdle(ServerWorld world, ChamberFrame frame) {
        return world.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity controller
                && controller.chamberUuid() != null && controller.chamberState() == ChamberState.IDLE;
    }

    /** 把玩家移到艙內正中（區域 3.5, 1, 3.5）。 */
    static void moveInside(PlayerEntity player, ChamberFrame frame) {
        var position = ChamberSpaceCoordinates.position(frame, 3.5, 1, 3.5);
        ((ServerPlayerEntity) player).teleport((ServerWorld) player.getWorld(), position.x, position.y, position.z, Set.of(), 0, 0);
    }

    /** 把玩家移到門外（區域 3.5, 1, −2.5）。 */
    static void moveOutside(PlayerEntity player, ChamberFrame frame) {
        var position = ChamberSpaceCoordinates.position(frame, 3.5, 1, -2.5);
        ((ServerPlayerEntity) player).teleport((ServerWorld) player.getWorld(), position.x, position.y, position.z, Set.of(), 0, 0);
    }
}
