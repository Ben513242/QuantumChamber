package dev.quantumchamber.gametest;

import com.mojang.authlib.GameProfile;
import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberCollapsePort;
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
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.collapse.SealedReason;
import dev.quantumchamber.corridor.CorridorGeometry;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.corridor.DoorKey;
import dev.quantumchamber.gametest.mixin.MinecraftServerAutosaveTestAccessor;
import dev.quantumchamber.gametest.mixin.TestContextStateTestAccessor;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionSemantics;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.registry.ModEffects;
import dev.quantumchamber.superposition.SessionState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.OverlayMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestState;
import net.minecraft.test.TestContext;
import net.minecraft.test.TestListener;
import net.minecraft.test.TestRunContext;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
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
 * <li>journal flush 探針：{@link #installFlushProbe}（裝在 {@link M5TestHooks}；被占用時丟出、不覆蓋；hooks 釋放或 test 結束時移除，
 *     見 {@link #installHook}）與計數探針 {@link FlushFault}。F4a 的故障注入：{@link #installMaterializeFault}、{@link #installCheckedReadFault}。</li>
 * <li>原生 autosave 的窗口與 journal 寫出稽核：{@link #afterAutosaveClear}、{@link AutosaveWindow#journalWrites}。</li>
 * <li>塌縮 fixture：{@link #collapseFixture}、{@link #cleanupFixtures}；真原艙＋DORMANT 收據＋玩家在艙外：{@link DormantOrigin}；
 *     Nether 的真原艙＋真玩家：{@link NetherChamber}；選中 EXISTING 的收據：{@link #existingReceipt}（F4a）。</li>
 * <li>玩家收到的 actionbar 訊息：{@link #overlayKeys}（F4a）；同一次讀取的 actionbar 與 title：{@link #hud}、{@link #arrivalTitle}（F5-3）。</li>
 * <li>候選門：{@link CandidateDoor}、{@link #candidateDoor}、{@link #sourceDoor}。</li>
 * <li>log：{@link #captureLogs}（建立 capture 並登記關閉）、{@link #noWarnOrError}（這個 sid 沒有 WARN／ERROR）、
 *     {@link #collapseFlowNeverWarned}（依 marker）。</li>
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

    // ---- journal flush 探針與寫出稽核（mixin 讀取的靜態 hook） ----

    /** 把 {@code probe} 裝成 journal flush 探針（{@link M5TestHooks#beforeJournalFlush}）；安裝、占用與移除的規則見 {@link #installHook}。 */
    static <T extends Consumer<SessionRecoveryState>> T installFlushProbe(TestContext context, T probe, M2CorridorGameTests.TestHooks hooks) {
        installHook(context, M5TestHooks.FLUSH_PROBE, probe, hooks);
        return probe;
    }

    /** 開始稽核 journal 寫出（{@link M5TestHooks#beforeJournalWrite}、{@link M5TestHooks.JournalWriteAudit}）；規則見 {@link #installHook}。 */
    static M5TestHooks.JournalWriteAudit installJournalAudit(TestContext context, M2CorridorGameTests.TestHooks hooks) {
        var audit = new M5TestHooks.JournalWriteAudit(context.getWorld().getServer());
        installHook(context, M5TestHooks.JOURNAL_AUDIT, audit, hooks);
        return audit;
    }

    /** F4a：這個 UniverseId 的 OVERWORLD 每一次 backend 物化都注入失敗（{@link M5TestHooks#MATERIALIZE_FAULT}）；安裝、占用與移除的規則見 {@link #installHook}。 */
    static M5TestHooks.MaterializeFault installMaterializeFault(TestContext context, dev.quantumchamber.universe.UniverseId universe,
            M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.MaterializeFault(dev.quantumchamber.universe.UniverseKeys.world(universe, dev.quantumchamber.universe.DimensionRole.OVERWORLD));
        installHook(context, M5TestHooks.MATERIALIZE_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F4a：checked NBT 檔的讀取失敗（{@link M5TestHooks#CHECKED_READ_FAULT}）：檔名為 {@code fileName}（例如 {@code UniverseAllocationLedger.FILE_NAME}）
     * 的讀取先放行 {@code skip} 次，之後 {@code failures} 次失敗；安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.CheckedReadFault installCheckedReadFault(TestContext context, String fileName, int skip, int failures,
            M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.CheckedReadFault(fileName, skip, failures);
        installHook(context, M5TestHooks.CHECKED_READ_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F4b-2：原生存檔 checkpoint 的探針（{@link M5TestHooks#CHUNK_CHECKPOINT_PROBE}）：{@code world} 的每一次 {@code ChunkSaveCheckpoint.save} 都記下（tick、相關
     * chunks、{@code observe} 在那一刻的觀察），前 {@code failFirst} 次在存檔之前受控失敗；安裝、占用與移除的規則見 {@link #installHook}。{@code observe} 在
     * production 的 {@code END_SERVER_TICK} 裡執行（server thread），可以讀權威、改世界；它丟出的例外只記下。
     */
    static M5TestHooks.ChunkCheckpointProbe installChunkCheckpointProbe(TestContext context, RegistryKey<World> world, int failFirst,
            IntFunction<String> observe, M2CorridorGameTests.TestHooks hooks) {
        var probe = new M5TestHooks.ChunkCheckpointProbe(world, failFirst, observe);
        installHook(context, M5TestHooks.CHUNK_CHECKPOINT_PROBE, probe, hooks);
        return probe;
    }

    /**
     * F4b-2：投影寫入失敗（{@link M5TestHooks#PROJECTION_WRITE_FAULT}）：{@code world} 以 {@code mode} 寫完的前 {@code failures} 次，結果換成 WRITE_FAILED；
     * 安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.ProjectionWriteFault installProjectionWriteFault(TestContext context, RegistryKey<World> world,
            ProjectionWriter.Mode mode, int failures, M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.ProjectionWriteFault(world, mode, failures);
        installHook(context, M5TestHooks.PROJECTION_WRITE_FAULT, fault, hooks);
        return fault;
    }

    /**
     * 安裝 mixin 讀取的靜態 hook（F4a-0 fix round 1，quality Q1），依序：
     * <ol>
     * <li>在 hooks 登記移除：hooks 已釋放時在這裡就丟出，什麼都還沒裝，不會殘留；</li>
     * <li>在這個 test 的原版 {@code GameTestState} 登記 {@link TestListener}：test 結束時（通過、失敗，或 tickLimit 逾時——逾時時原版不執行任何
     *     callback，hooks 不會釋放）若仍裝著它，就移除並記一行 {@code M5_TEST_HOOK_RESIDUE} WARN；</li>
     * <li>安裝：位置已被占用（同一個 batch 的另一個 test 正在使用，或沒有被移除的殘留）時丟 IllegalStateException、不覆蓋，訊息列出持有者。</li>
     * </ol>
     * 移除只在位置仍是這個值時生效（{@link M5TestHooks.Slot#remove}），所以安裝失敗時登記的移除不會動到持有者的值。
     */
    private static <T> void installHook(TestContext context, M5TestHooks.Slot<T> slot, T value, M2CorridorGameTests.TestHooks hooks) {
        Objects.requireNonNull(value, slot.name);
        var test = gameTestState(context);
        hooks.add(() -> slot.remove(value));
        test.addListener(new ReleaseOnCompletion<>(slot, value));
        slot.install(value, test);
    }

    /** {@link #installHook} 的 TestListener：test 結束時位置若仍是 {@code value} 就移除，並記一行 WARN。在原版的 test tick 裡執行，不丟出例外。 */
    private record ReleaseOnCompletion<T>(M5TestHooks.Slot<T> slot, T value) implements TestListener {
        @Override public void onStarted(GameTestState test) { }
        @Override public void onPassed(GameTestState test, TestRunContext context) { release(test); }
        @Override public void onFailed(GameTestState test, TestRunContext context) { release(test); }
        @Override public void onRetry(GameTestState lastState, GameTestState nextState, TestRunContext context) { }

        private void release(GameTestState test) {
            try {
                if (slot.remove(value)) LOGGER.warn("M5_TEST_HOOK_RESIDUE test={} hook={} failed={}：test 結束時仍裝著（hooks 沒有釋放，例如 tickLimit 逾時），"
                        + "已由 TestListener 移除", test, slot.name, test.isFailed());
            } catch (RuntimeException failure) {
                LOGGER.error("M5_TEST_HOOK_RESIDUE_FAILED test={} hook={}", test, slot.name, failure);
            }
        }
    }

    /**
     * 原版 {@code TestContext} 背後的 {@code GameTestState}（{@code TestContext.test}），經 testmod 的 accessor mixin {@code TestContextStateTestAccessor} 讀取（F6-0，F4a-0 quality
     * re-review Minor 3：原本這裡與 {@code M5UniverseGameTests.scheduledCount} 各以反射讀一次）。欄位改名時開服即失敗（accessor 套用不上），不是等到呼叫才丟出。
     */
    static GameTestState gameTestState(TestContext context) {
        return Objects.requireNonNull(((TestContextStateTestAccessor) context).quantumchamberTest$getTest(), "TestContext.test");
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
     * （下一次 autosave 在 {@code getTicks()+window} 或更晚），才在那個 tick 的 callback 開始稽核 journal 寫出（{@link #installJournalAudit}，
     * hooks 釋放或 test 結束時移除）並呼叫 {@code start}（test tick、窗口）。不成立時，倒數最晚再 {@code window} 個 tick 就觸發 autosave 並重設為一整個間隔，
     * 所以最多等 window＋1 個 tick；等不到代表 autosave 間隔被改過（{@code /tick rate}、{@code /tick sprint}），以失敗結束。窗口要涵蓋從開始注入
     * 到最後一次被探針計數的寫出。等待只降低誤判的機率；窗口內有沒有繞過探針的寫出，由 {@link AutosaveWindow#journalWrites} 判定與診斷。
     */
    static void afterAutosaveClear(TestContext context, int tick, String name, int window,
            BiConsumer<Integer, AutosaveWindow> start, M2CorridorGameTests.TestHooks hooks) {
        var server = context.getWorld().getServer();
        whenTicks(context, tick, window + 2, () -> nextAutosaveTick(server) - server.getTicks() >= window, ready -> {
            var audit = installJournalAudit(context, hooks);
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

    /**
     * F4a：選中 EXISTING 候選的 candidate-aware MEASURED＋SELECTED 收據（比照 {@code M4CandidateTestAccess.measuredReceipt}，候選改為
     * {@code Existing(universe)}）。參與者的值是是否已返還，沒有 lease：全員已返還即 DORMANT。選中者是第一位參與者。只建立，不寫入。
     */
    static SessionRecoveryRecord existingReceipt(MinecraftServer server, ChamberOriginAuthority origin, Map<UUID, Boolean> people,
            dev.quantumchamber.universe.UniverseId universe) {
        var sid = UUID.randomUUID();
        var cohort = people.entrySet().stream().map(entry -> new SessionRecoveryRecord.Participant(entry.getKey(), Vec3d.ofCenter(origin.controllerPos()),
                Vec3d.ZERO, 0, 0, new net.minecraft.nbt.NbtCompound(), entry.getValue())).toList();
        var frozen = M4CandidateTestAccess.candidateAware(new SessionRecoveryRecord(sid, origin.chamberUuid(), origin, cohort,
                List.of(new SessionRecoveryRecord.SpaceLease(0, new net.minecraft.util.math.BlockBox(0, 0, 0, 6, 6, 6))), SessionState.ARMING, false,
                dev.quantumchamber.persistence.SessionSemantics.LATERAL_BUFF_MAINTAINED), server);
        var key = new DoorKey(sid, 1, DoorKey.DoorWallSide.NEGATIVE_LATERAL);
        var bytes = new byte[32];
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(bytes);
        var id = new dev.quantumchamber.candidate.CandidateId(new dev.quantumchamber.candidate.CandidateBytes(bytes));
        return new SessionRecoveryRecord(sid, origin.chamberUuid(), origin, cohort, List.of(), SessionState.MEASURED, false, frozen.semantics(),
                frozen.candidateContext(), List.of(new dev.quantumchamber.candidate.CandidateLedgerEntry(key, new QuantumCandidate.Existing(id, universe))),
                Optional.of(new dev.quantumchamber.candidate.CandidateSelection.Selected(key, id, cohort.getFirst().playerUuid(), 0, 1)));
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
        if (record.state() == SessionState.SEALED && record.collapse().sealedReason().filter(reason -> reason == SealedReason.TRANSFER_RECOVERY_REQUIRED).isPresent()) {
            // F5-1：SEALED(TRANSFER_RECOVERY_REQUIRED) 是永久的 recovery authority（spec §10.3、§11.2）：沒有任何 API 可以終局或重試它（SEALED→COLLAPSING 被 journal 拒絕），
            // 所以留在這個 run 的 journal。它的原參與者都已返還、不佔用任何人；原艙是這個 test 自己的，之後的 test 不會用到。
            LOGGER.info("M5 fixture 收尾：SEALED(TRANSFER_RECOVERY_REQUIRED) 是永久的 recovery authority，留在 journal、不移除：session={}", sid);
            return;
        }
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

    // ---- log ----

    /**
     * 開始擷取 {@code quantumchamber} logger 的 log（{@link LogCapture}），並在 hooks 登記關閉（F4a-0 fix round 1，quality Q5）。建立失敗（capture
     * 觀察不到 production logger）或 hooks 已釋放時，先關閉已建立的 capture、釋放 hooks，再丟出。
     */
    static LogCapture captureLogs(M2CorridorGameTests.TestHooks hooks) {
        LogCapture capture = null;
        try {
            capture = new LogCapture("quantumchamber");
            hooks.add(capture::close);
            return capture;
        } catch (RuntimeException | Error failure) {
            if (capture != null) {
                try { capture.close(); } catch (RuntimeException | Error cleanup) { failure.addSuppressed(cleanup); }
            }
            hooks.releaseAfter(failure);
            throw failure;
        }
    }

    /** capture 期間，訊息含這個 sid 的 WARN 與 ERROR 都沒有（不分 marker，塌縮流程以外的 log 也算）。 */
    static boolean noWarnOrError(LogCapture capture, UUID sid) {
        return capture.matching(Level.WARN, sid.toString()).isEmpty() && capture.matching(Level.ERROR, sid.toString()).isEmpty();
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
                return new DormantOrigin(context, frame, connection[0], captureLogs(hooks), owned, hooks);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        }

        ServerPlayerEntity player() { return connection.player(); }

        /**
         * 以呼叫當下的 test tick（{@code context.getTick()}，記為 t0）為基準（F4a-0 fix round 1，quality Q2）：t0+2 供電（經 {@link #at}，例外時先釋放
         * hooks）；從 t0+3 起等原艙註冊並進入 IDLE（最多 40 tick），之後以那個 tick 與 Controller 的 chamber UUID 呼叫 {@code ready}。可以在 test
         * 方法本體（t0=0）或任何 callback 裡呼叫。
         */
        void whenRegistered(BiConsumer<Integer, UUID> ready) {
            int start = Math.toIntExact(context.getTick());
            at(context, start + 2, hooks, () -> context.setBlockState(POWER, Blocks.REDSTONE_BLOCK));
            whenTicks(context, start + 3, 40, () -> registeredIdle(world, frame),
                    registered -> ready.accept(registered, controller(world, frame).chamberUuid()), () -> "原艙沒有註冊並進入 IDLE", hooks);
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
        private final TestContext context;
        private final M2CorridorGameTests.TestHooks hooks;

        private NetherChamber(TestContext context, ServerWorld nether, ChamberFrame frame, ConnectedGameTestPlayer connection,
                M2CorridorGameTests.TestHooks hooks) {
            this.context = context; this.nether = nether; this.frame = frame; this.power = frame.controllerPos().up(); this.connection = connection;
            this.hooks = hooks;
        }

        /** 建置並連線（見類別說明）；之後的 {@link #whenEntered} 沿用這裡的 context 與 hooks。失敗時先釋放 hooks。 */
        static NetherChamber open(TestContext context, BlockPos controller, M2CorridorGameTests.TestHooks hooks) {
            var nether = context.getWorld().getServer().getWorld(World.NETHER);
            var frame = new ChamberFrame(controller, Direction.NORTH);
            var power = frame.controllerPos().up();
            var chunks = bunkerChunks(frame);
            ConnectedGameTestPlayer[] connection = {null};
            hooks.add(() -> chunks.forEach(chunk -> nether.setChunkForced(chunk.x, chunk.z, false)));
            hooks.add(() -> { if (connection[0] != null) connection[0].close(); });
            hooks.add(() -> nether.setBlockState(power, Blocks.AIR.getDefaultState()));
            try {
                chunks.forEach(chunk -> nether.setChunkForced(chunk.x, chunk.z, true));
                buildBunkerChamber(nether, frame);
                connection[0] = new ConnectedGameTestPlayer(context.getWorld());
                var player = connection[0].player();
                var inside = ChamberSpaceCoordinates.position(frame, 3.5, 1, 3.5);
                player.teleport(nether, inside.x, inside.y, inside.z, Set.of(), 0, 0);
                player.setNoGravity(true); player.setVelocity(Vec3d.ZERO);
                player.addStatusEffect(new StatusEffectInstance(ModEffects.QUANTUM_STATE, 1_000_000));
                return new NetherChamber(context, nether, frame, connection[0], hooks);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        }

        ServerPlayerEntity player() { return connection.player(); }

        /** 這座艙（依 Controller 的 chamber UUID）在 journal 裡的 flushed record；尚未註冊或沒有 session 時為 null。 */
        SessionRecoveryRecord record(SessionRecoveryState journal) {
            if (!(nether.getBlockEntity(frame.controllerPos()) instanceof ChamberControllerBlockEntity controller) || controller.chamberUuid() == null) return null;
            return journal.flushedRecords().values().stream().filter(record -> record.chamberUuid().equals(controller.chamberUuid())).findFirst().orElse(null);
        }

        /**
         * 以呼叫當下的 test tick（{@code context.getTick()}，記為 t0）為基準（F4a-0 fix round 1，quality Q2）：t0+2 供電；從 t0+3 起等真入場、走廊
         * 發布 358 扇側門（最多 20000 tick），之後以那個 tick 呼叫 {@code ready}。context 與 hooks 沿用 {@link #open}；可以在 test 方法本體（t0=0）
         * 或任何 callback 裡呼叫。
         */
        void whenEntered(IntConsumer ready) {
            var server = context.getWorld().getServer();
            var journal = SessionRecoveryState.get(server); var pages = CorridorPageManager.forServer(server);
            int start = Math.toIntExact(context.getTick());
            at(context, start + 2, hooks, () -> nether.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState()));
            whenTicks(context, start + 3, 20000, () -> {
                var record = record(journal);
                return record != null && record.state() == SessionState.SUPERPOSITION && pages.currentMappings(record.sessionUuid()).epoch() == 1
                        && pages.selectableDoors(record.sessionUuid()).size() == 358;
            }, ready, () -> "Nether 原艙沒有入場：record=" + Optional.ofNullable(record(journal)).map(value -> value.state().name()).orElse("ABSENT"),
                    hooks);
        }

    }

    /** {@link NetherChamber} 與 {@link RemoteOrigin} 的 fixture 原艙所在的 chunk（含外圍石牆）。 */
    private static List<ChunkPos> bunkerChunks(ChamberFrame frame) {
        var a = ChamberSpaceCoordinates.block(frame, -3, -1, -5); var b = ChamberSpaceCoordinates.block(frame, 9, 9, 9);
        var chunks = new ArrayList<ChunkPos>();
        for (int x = Math.min(a.getX(), b.getX()) >> 4; x <= Math.max(a.getX(), b.getX()) >> 4; x++)
            for (int z = Math.min(a.getZ(), b.getZ()) >> 4; z <= Math.max(a.getZ(), b.getZ()) >> 4; z++) chunks.add(new ChunkPos(x, z));
        return chunks;
    }

    /**
     * {@link NetherChamber} 與 {@link RemoteOrigin} 的 fixture 原艙：以艙的區域座標，外圍（x −3..9、y −1..9、z −5..9 的邊界）一圈石頭、其餘清空，再照
     * {@link ChamberGameTestBuilder} 的配置蓋 7×7×7 的艙（基岩外殼、關著的 25 格門、朝北的 Controller）。F4b-3 從 {@code NetherChamber} 搬出來共用，內容不變。
     */
    private static void buildBunkerChamber(ServerWorld world, ChamberFrame frame) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (int x = -3; x <= 9; x++) for (int y = -1; y <= 9; y++) for (int z = -5; z <= 9; z++) {
                boolean boundary = x == -3 || x == 9 || y == -1 || y == 9 || z == -5 || z == 9;
                boolean chamber = x >= 0 && x <= 6 && y >= 0 && y <= 6 && z >= 0 && z <= 6;
                BlockState state;
                if (boundary) state = Blocks.STONE.getDefaultState();
                else if (!chamber) state = Blocks.AIR.getDefaultState();
                else if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) state = ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, false);
                else state = ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
                world.setBlockState(ChamberSpaceCoordinates.block(frame, x, y, z), state, Block.NOTIFY_ALL);
            }
            world.setBlockState(frame.controllerPos(), ModBlocks.CHAMBER_CONTROLLER.getDefaultState()
                    .with(ChamberControllerBlock.FACING, frame.outwardFacing()), Block.NOTIFY_ALL);
        });
    }

    /**
     * F4b-3：overworld 遠處的真原艙（不在 test 結構裡；只有它自己強制載入，所以可以讓它的 chunk 真的卸載再載入），比照 {@link NetherChamber} 的建置：強制載入涵蓋的
     * chunk、以石頭圍住並蓋朝北的原艙（門關著）。沒有玩家。{@link #open} 依序登記收尾：解除強制載入、斷電。已註冊的原艙比照其他原生 test 留在原地（只斷電）。
     * <ul>
     * <li>{@link #whenRegistered}：同 {@link DormantOrigin#whenRegistered}（t0+2 供電、等註冊並進入 IDLE），交出 tick 與 chamber UUID。</li>
     * <li>{@link #unload}／{@link #reload}：解除／恢復強制載入。附近沒有玩家、塌縮流程也沒有來源 ticket（SEALED 已釋放）時，解除之後 chunk 不再是 FULL
     *     （{@link #unloaded}）；恢復之後重新載入到 FULL（{@link #loaded}）。兩者都要以有界等待確認。</li>
     * </ul>
     */
    static final class RemoteOrigin {
        final ServerWorld world;
        final ChamberFrame frame;
        final BlockPos power;
        final List<ChunkPos> chunks;
        private final TestContext context;
        private final M2CorridorGameTests.TestHooks hooks;

        private RemoteOrigin(TestContext context, ChamberFrame frame, List<ChunkPos> chunks, M2CorridorGameTests.TestHooks hooks) {
            this.context = context; this.world = context.getWorld(); this.frame = frame; this.power = frame.controllerPos().up(); this.chunks = chunks;
            this.hooks = hooks;
        }

        /** 在 test 的世界（overworld）以 {@code controller} 為 Controller 位置建置；失敗時先釋放 hooks。 */
        static RemoteOrigin open(TestContext context, BlockPos controller, M2CorridorGameTests.TestHooks hooks) {
            var world = context.getWorld();
            var frame = new ChamberFrame(controller, Direction.NORTH);
            var chunks = bunkerChunks(frame);
            hooks.add(() -> chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, false)));
            hooks.add(() -> world.setBlockState(frame.controllerPos().up(), Blocks.AIR.getDefaultState()));
            try {
                chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, true));
                buildBunkerChamber(world, frame);
                return new RemoteOrigin(context, frame, chunks, hooks);
            } catch (RuntimeException | Error failure) { hooks.releaseAfter(failure); throw failure; }
        }

        /** 同 {@link DormantOrigin#whenRegistered}：以呼叫當下的 test tick（t0）為基準，t0+2 供電，從 t0+3 起等註冊並進入 IDLE（最多 40 tick）。 */
        void whenRegistered(BiConsumer<Integer, UUID> ready) {
            int start = Math.toIntExact(context.getTick());
            at(context, start + 2, hooks, () -> world.setBlockState(power, Blocks.REDSTONE_BLOCK.getDefaultState()));
            whenTicks(context, start + 3, 40, () -> registeredIdle(world, frame),
                    registered -> ready.accept(registered, controller(world, frame).chamberUuid()), () -> "遠處的原艙沒有註冊並進入 IDLE", hooks);
        }

        /** 這座艙的原艙權威（overworld、朝北）。 */
        ChamberOriginAuthority authority(UUID chamber) { return M4CandidateTestAccess.origin(chamber, frame.controllerPos()); }

        /** 涵蓋的 chunk 都是 FULL（只查詢、不載入）。 */
        boolean loaded() { return chunks.stream().allMatch(chunk -> world.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null); }

        /** 涵蓋的 chunk 都不是 FULL（只查詢、不載入）。 */
        boolean unloaded() { return chunks.stream().allMatch(chunk -> world.getChunkManager().getWorldChunk(chunk.x, chunk.z) == null); }

        /** 涵蓋的 chunk 的 entity storage 都已載入（F4b-3 fix round 1；重新載入之後 entity 是非同步載入的，比 FULL 晚）。 */
        boolean entitiesLoaded() { return chunks.stream().allMatch(chunk -> world.isChunkLoaded(chunk.toLong())); }

        void unload() { chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, false)); }

        void reload() { chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, true)); }
    }

    // ---- 原艙門（F4b-3） ----

    /**
     * F4b-3：journal put 的拒絕（{@link M5TestHooks#JOURNAL_PUT_FAULT}）：{@code session} 寫成 {@code state} 的前 {@code failures} 次 put 被拒（checked 提交回 REJECTED）；
     * 每一次拒絕的 server tick 在 {@code ticks()}（F6-0）。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.JournalPutFault installJournalPutFault(TestContext context, UUID session, SessionState state, int failures,
            M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.JournalPutFault(context.getWorld().getServer(), session, state, failures);
        installHook(context, M5TestHooks.JOURNAL_PUT_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F4b-3：模擬重啟之後的第一次觀察（比照 {@code M4CandidateTestAccess.markStartup}）：把 session 標成開服 attach 當下就在 journal，並以反射清掉塌縮流程對它「來源艙已載入」
     * 的觀察（{@code CollapseService.Track.doorLoaded} 與 {@code doorSeenLoaded}；本 mod 自己的欄位，不經 remap）。下一個 {@code END_SERVER_TICK} 的觀察就是「重啟之後第一次」，依 collapse view
     * 重新套用原艙門。只在 server thread 呼叫；真正跨 JVM 的重啟屬 F6。
     */
    @SuppressWarnings("unchecked")
    static void simulateDoorStartup(MinecraftServer server, UUID sid) {
        var service = collapseService(server);
        ((Set<UUID>) M4CandidateTestAccess.get(service, "startupSessions")).add(sid);
        var track = ((Map<UUID, ?>) M4CandidateTestAccess.get(service, "tracks")).get(sid);
        if (track == null) return;
        M4CandidateTestAccess.set(track, "doorLoaded", null);
        // F4b-3 fix round 1（Nit N-4）：重啟之後的 instance 還沒看過來源艙已載入。
        M4CandidateTestAccess.set(track, "doorSeenLoaded", false);
    }

    /** 這個 server 的 {@code CollapseService}（反射；本 mod 自己的欄位，不經 remap）。 */
    @SuppressWarnings("unchecked")
    private static Object collapseService(MinecraftServer server) {
        var service = ((Map<MinecraftServer, ?>) M4CandidateTestAccess.get(CollapseService.class, "INSTANCES")).get(server);
        if (service == null) throw new IllegalStateException("沒有此 server 的 CollapseService");
        return service;
    }

    /** F4b-3 fix round 1：直接呼叫原艙門的攔截（{@link ChamberCollapsePort#doors()}；bulkhead 與 Controller 的 {@code onUse} 經過同一個 port），回傳它的處置。 */
    static ChamberCollapsePort.DoorUse useDoor(ServerWorld world, ChamberFrame frame, PlayerEntity player) {
        return ChamberCollapsePort.doors().use(world, frame, player);
    }

    /**
     * F4b-3 fix round 1（spec S-M2）：判斷身分時丟出例外的非參與者（不在世界裡，比照 {@code TestContext.createMockPlayer}）：{@code isSpectator()} 丟
     * {@link IllegalStateException}，用來造出攔截「判斷途中」的非預期例外（journal 健康）。
     */
    static PlayerEntity lookupFailingPlayer(ServerWorld world) {
        return new PlayerEntity(world, BlockPos.ORIGIN, 0, new GameProfile(UUID.randomUUID(), "qc-lookup-fault")) {
            @Override public boolean isSpectator() { throw new IllegalStateException("F4b-3 fix round 1 GameTest 受控：判斷玩家身分時丟出例外"); }
            @Override public boolean isCreative() { return false; }
        };
    }

    /**
     * F5-0（F4b-3 quality M-2，取代以反射把 {@code Track.backoff} 換成 null 的 {@code breakParticipantDoorUse}）：原艙門攔截的失敗注入（{@link M5TestHooks#DOOR_USE_FAULT}，
     * testmod {@code OriginDoorUseFaultMixin}）。安裝時就讓這個 session 的 PREPARE 期間參與者開關門的處置每次都在開頭丟出例外（持續性的處置失敗；{@code disarm()}／
     * {@code arm()}，{@code hits()}）；{@code armLookup()}／{@code disarmLookup()} 另讓 {@code frame} 這座艙的攔截在找出所屬 session 時丟出例外（spec m-1，
     * {@code lookupHits()}）。都在同一個 test 內切換。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.DoorUseFault installDoorUseFault(TestContext context, UUID session, ChamberFrame frame, M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.DoorUseFault(session, frame);
        installHook(context, M5TestHooks.DOOR_USE_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F4b-3 fix round 1（quality Q-M1）：在 journal 放入一筆未 checked 的 authority，讓 {@code recoveryWritesSafe()} 為 false（等同 checked 提交 put→flush 之間的窗口，
     * 或 recovery tick 還沒收斂的殘留）：新的 ARMING session，candidate context 還沒經 flush 讀回確認；別的 chamber、別的玩家、不與其他 session 衝突的 slot。不 flush，
     * 也不交給塌縮流程（暫停）。回傳它的 session UUID；呼叫端在同一個 callback 內以 {@link #removeUncheckedAuthority} 移除（hooks 也登記移除）。
     */
    static UUID putUncheckedAuthority(MinecraftServer server, M2CorridorGameTests.TestHooks hooks) {
        var journal = SessionRecoveryState.get(server);
        var origin = M4CandidateTestAccess.origin(UUID.randomUUID(), new BlockPos(-30011, 100, -30011));
        var person = new SessionRecoveryRecord.Participant(UUID.randomUUID(), Vec3d.ofCenter(origin.controllerPos()), Vec3d.ZERO, 0, 0,
                new net.minecraft.nbt.NbtCompound(), false);
        var record = M4CandidateTestAccess.candidateAware(new SessionRecoveryRecord(UUID.randomUUID(), origin.chamberUuid(), origin, List.of(person),
                List.of(new SessionRecoveryRecord.SpaceLease(1_000_003, new net.minecraft.util.math.BlockBox(0, 0, 0, 6, 6, 6))), SessionState.ARMING, false,
                SessionSemantics.LATERAL_BUFF_MAINTAINED), server);
        var sid = record.sessionUuid();
        M5TestHooks.suspendCollapse(sid);
        hooks.add(() -> removeUncheckedAuthority(server, sid));
        journal.put(record);
        return sid;
    }

    /** {@link #putUncheckedAuthority} 放入的 session 從 current 移除（沒有 flush 過，所以 journal 回到放入之前的 authority）；已移除時不做事。 */
    static void removeUncheckedAuthority(MinecraftServer server, UUID sid) {
        var journal = SessionRecoveryState.get(server);
        if (journal.records().containsKey(sid)) journal.remove(sid);
    }

    /** 以授權寫入把 25 格門直接寫成 open＋membrane（例如模擬 crash 留下的量子膜；經 bulkhead 的守門 {@code withDoor}，不經門服務）。 */
    static void writeDoor(ServerWorld world, ChamberFrame frame, boolean open, MembraneState membrane) {
        ChamberProtectionService.get().authorizedMutation(() -> {
            for (var pos : door(frame)) world.setBlockState(pos, QuantumBulkheadBlock.withDoor(world.getBlockState(pos), open, membrane));
        });
    }

    /** 在門口（門格 local (3,1,0) 的中心、腳在那一格的底）放一座不受重力的盔甲座，在 hooks 登記移除，回傳它。 */
    static net.minecraft.entity.decoration.ArmorStandEntity doorwayEntity(ServerWorld world, ChamberFrame frame, M2CorridorGameTests.TestHooks hooks) {
        var stand = net.minecraft.entity.EntityType.ARMOR_STAND.create(world);
        if (stand == null) throw new IllegalStateException("建不出盔甲座");
        var cell = ChamberGeometry.localToWorld(frame, 3, 1, 0);
        stand.refreshPositionAndAngles(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, 0, 0);
        stand.setNoGravity(true);
        hooks.add(stand::discard);
        if (!world.spawnEntity(stand)) throw new IllegalStateException("盔甲座沒有加入世界");
        return stand;
    }

    /**
     * F4b-3 fix round 1：移走門口（25 格門的外框，test 自己算）裡的所有 entity，回傳移走的數目。chunk 重新載入之後，門口的 entity 是從存檔重新載入的另一個物件，
     * 卸載前拿到的參照已經不在世界裡，所以以世界查詢移除。
     */
    static int discardDoorwayEntities(ServerWorld world, ChamberFrame frame) {
        var a = ChamberGeometry.localToWorld(frame, 1, 1, 0);
        var b = ChamberGeometry.localToWorld(frame, 5, 5, 0);
        var box = new net.minecraft.util.math.Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
        var found = world.getOtherEntities(null, box);
        found.forEach(net.minecraft.entity.Entity::discard);
        return found.size();
    }

    /** 25 格門都是朝向的 sealed 量子膜（{@code open=true}＋{@code sealed_<axis>}）。 */
    static boolean doorSealed(ServerWorld world, ChamberFrame frame) {
        return doorAll(world, frame, true, MembraneState.sealedFor(frame.outwardFacing()));
    }

    // ---- 通道（F5-1） ----

    /** 25 格門都是朝向的 passable 量子膜（{@code open=true}＋{@code passable_<axis>}；PASSAGE_READY 的門）。 */
    static boolean doorPassable(ServerWorld world, ChamberFrame frame) {
        return doorAll(world, frame, true, MembraneState.passableFor(frame.outwardFacing()));
    }

    /**
     * F5-1：通道的原生移動被拒（{@link M5TestHooks#NATIVE_MOVE_FAULT}，testmod {@code SessionTransferFaultMixin}）：{@code player} 往 {@code destination} 的原生移動前
     * {@code destinationFailures} 次被拒（玩家不動），往其他世界（回滾回來源）的前 {@code otherFailures} 次被拒。1／0 是 {@code FAILED_ROLLED_BACK}，1／1 是
     * {@code FAILED_RECOVERY_REQUIRED}。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.NativeMoveFault installNativeMoveFault(TestContext context, UUID player, RegistryKey<World> destination, int destinationFailures,
            int otherFailures, M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.NativeMoveFault(player, destination, destinationFailures, otherFailures);
        installHook(context, M5TestHooks.NATIVE_MOVE_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F5-1：這個 session 準備完成之後不提交 PASSAGE_READY（{@link M5TestHooks#READY_HOLD}，testmod {@code CollapseReadyHoldMixin}），PREPARE 照常：給「準備完成之後仍在
     * PREPARE」的原艙門 test 用（F5-1 之前它們停在 READY stub），以及要控制通道起算時點的 test（例如等原生 autosave 的窗口開始才放行，{@link M5TestHooks.ReadyHold#release}）。
     * {@code hits()} 證明暫緩真的發生。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.ReadyHold holdReady(TestContext context, UUID session, M2CorridorGameTests.TestHooks hooks) {
        var hold = new M5TestHooks.ReadyHold(session);
        installHook(context, M5TestHooks.READY_HOLD, hold, hooks);
        return hold;
    }

    /**
     * F5-1 fix round 1（spec review S-m1）：暫停這個 session 的塌縮流程推進（{@link M5TestHooks#STEP_PAUSE}，testmod {@code CollapseSuspendMixin} 經
     * {@link M5TestHooks#cancelStep} 讀它），{@code release()} 之後恢復；與不可撤銷的 {@link M5TestHooks#suspendCollapse} 不同。用來造出 production 中這個 session 的
     * 推進被略過的 tick（journal 有未 checked 的 authority、隔離中、整個 tick 無法執行）。{@code hits()} 證明暫停真的發生，只計 mixin 取消的推進；test 以
     * {@link M5TestHooks#collapseSuspended} 查詢時不計數（F5-2 Step 0，R-m2）。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.StepPause installStepPause(TestContext context, UUID session, M2CorridorGameTests.TestHooks hooks) {
        var pause = new M5TestHooks.StepPause(session);
        installHook(context, M5TestHooks.STEP_PAUSE, pause, hooks);
        return pause;
    }

    /**
     * F5-2（F4a M-3）：進入 SEALED 的 Controller 探針（{@link M5TestHooks#SEAL_PROBE}，testmod {@code CollapseSealProbeMixin}）：這個 session 每一次
     * {@code CollapseService.sealCommitted} 的開頭與 RETURN 各記一次 {@code world} 裡 {@code controller} 的比較器與 {@code ChamberState}。用來鎖住「進入 SEALED 之後刷新
     * Controller」：SEALED 前後比較器的數值不同（例如通道開著的 15 → SEALED(TRANSFER_RECOVERY_REQUIRED) 的 11），而 RETURN 那一刻還沒有任何排程刷新。test 以
     * {@code hits()} 當 witness。安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.SealProbe installSealProbe(TestContext context, UUID session, ServerWorld world, BlockPos controller, M2CorridorGameTests.TestHooks hooks) {
        var probe = new M5TestHooks.SealProbe(session, world, controller);
        installHook(context, M5TestHooks.SEAL_PROBE, probe, hooks);
        return probe;
    }

    /**
     * F6-0（G-NLIVE 的根因）：原生存檔 checkpoint 的 entity storage 就緒保留（{@link M5TestHooks#ENTITY_STORAGE_HOLD}，testmod {@code ChunkSaveCheckpointEntityHoldMixin}）：裝著、
     * 還沒 {@code release()} 的期間，{@code world} 的 {@code chunks} 在 {@code ChunkSaveCheckpoint} 的就緒判斷裡回「entity storage 尚未載入」。test 以 {@code hits()} 當 witness。
     * 安裝、占用與移除的規則見 {@link #installHook}。
     */
    static M5TestHooks.EntityStorageHold holdEntityStorage(TestContext context, ServerWorld world, Set<ChunkPos> chunks, M2CorridorGameTests.TestHooks hooks) {
        var hold = new M5TestHooks.EntityStorageHold(world.getRegistryKey(), chunks);
        installHook(context, M5TestHooks.ENTITY_STORAGE_HOLD, hold, hooks);
        return hold;
    }

    /**
     * F6-0（F4b-3 spec S-M1）：隔離 session 的收斂失敗（{@link M5TestHooks#CONVERGE_FAULT}，testmod {@code QuarantineConvergeFaultMixin}）：裝著、還沒 {@code release()} 的期間，
     * {@code session} 的每一次收斂都失敗，它一直隔離著（test 自己以 {@code SessionRecoveryState.quarantine} 隔離它）。test 以 {@code hits()} 當 witness。安裝、占用與移除的規則見
     * {@link #installHook}；hooks 釋放（移除注入）之後，下一個 tick 的 recovery 就把仍隔離著的 session 收斂。
     */
    static M5TestHooks.ConvergeFault installConvergeFault(TestContext context, UUID session, M2CorridorGameTests.TestHooks hooks) {
        var fault = new M5TestHooks.ConvergeFault(session);
        installHook(context, M5TestHooks.CONVERGE_FAULT, fault, hooks);
        return fault;
    }

    /**
     * F5-1：把玩家放到來源艙的門口（腳在區域座標 ({@code x}, {@code y}, {@code z})，同一個世界），朝向 {@code yaw}、{@code pitch}，速度設為 {@code velocity}。z ≥ 0.5 是
     * 門板中線的內側，z &lt; 0.5 是外側；跨越是「上一個 tick 在內側、這個 tick 在外側」，而且腳在門口（x∈[1,6)、y∈[1,6)；spec §10.3）。
     */
    static void standInDoorway(ServerPlayerEntity player, ChamberFrame frame, double x, double y, double z, float yaw, float pitch, Vec3d velocity) {
        var position = ChamberSpaceCoordinates.position(frame, x, y, z);
        player.teleport(player.getServerWorld(), position.x, position.y, position.z, Set.of(), yaw, pitch);
        player.setVelocity(velocity);
    }

    // ---- 投影（F4b-1） ----

    /** {@link #forceProjectionChunks} 在投影的 7×7×7 外再水平外擴的格數：涵蓋紅石讀取（±3）、門的套用（±3）與 test 在投影外放的方塊。 */
    static final int PROJECTION_MARGIN = 8;

    /**
     * F4b-1：以強制載入（{@code setChunkForced}，entity ticking）讓 {@code frame} 的 7×7×7 與它水平外擴 {@value #PROJECTION_MARGIN} 格涵蓋的 chunk 都是
     * FULL，並在 server thread 上同步產生或載入它們（只有 test 可以這樣等待；production 的預載屬 F4b-2，不阻塞）。先在 hooks 登記解除強制載入，再載入。
     * 回傳這些 chunk。
     */
    static List<ChunkPos> forceProjectionChunks(ServerWorld world, ChamberFrame frame, M2CorridorGameTests.TestHooks hooks) {
        var bounds = ChamberGeometry.bounds(frame);
        var chunks = new ArrayList<ChunkPos>();
        for (int x = (bounds.getMinX() - PROJECTION_MARGIN) >> 4; x <= (bounds.getMaxX() + PROJECTION_MARGIN) >> 4; x++)
            for (int z = (bounds.getMinZ() - PROJECTION_MARGIN) >> 4; z <= (bounds.getMaxZ() + PROJECTION_MARGIN) >> 4; z++) chunks.add(new ChunkPos(x, z));
        hooks.add(() -> chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, false)));
        chunks.forEach(chunk -> world.setChunkForced(chunk.x, chunk.z, true));
        chunks.forEach(chunk -> world.getChunk(chunk.x, chunk.z));
        return chunks;
    }

    /**
     * F4b-1：{@code chunks} 都已 block ticking——在模擬距離內（方塊事件與 block entity 才會執行），而且 ticking future 已完成（排程刻與流體才會執行）。
     * 強制載入的 chunk 本身是同步載入的，但它開始 ticking 還要等鄰居非同步產生；GameTest server 不以 20 TPS 節流，固定等幾個 tick 不夠。
     */
    static boolean blockTicking(ServerWorld world, List<ChunkPos> chunks) {
        return chunks.stream().allMatch(chunk -> world.shouldTickBlocksInChunk(chunk.toLong()) && world.getChunkManager().isTickingFutureReady(chunk.toLong()));
    }

    /**
     * spec §9.2 的投影在區域座標 (x, y, z)（0..6）的目標方塊，由 test 依 spec 自己寫出（不讀 production 的 {@code ProjectionWriter}）：local (3,6,0) 是
     * {@code FACING}＝outwardFacing 的 Controller，門板 25 格是 {@code open=true}、{@code membrane=none} 的 bulkhead，其餘 shell 是 bedrock，interior 是 AIR。
     */
    static BlockState projectionTarget(ChamberFrame frame, int x, int y, int z) {
        if (x == 3 && y == 6 && z == 0) return ModBlocks.CHAMBER_CONTROLLER.getDefaultState().with(ChamberControllerBlock.FACING, frame.outwardFacing());
        if (z == 0 && x > 0 && x < 6 && y > 0 && y < 6) {
            return ModBlocks.QUANTUM_BULKHEAD.getDefaultState().with(QuantumBulkheadBlock.OPEN, true).with(QuantumBulkheadBlock.MEMBRANE, MembraneState.NONE);
        }
        return ChamberGeometry.isShellCell(x, y, z) ? Blocks.BEDROCK.getDefaultState() : Blocks.AIR.getDefaultState();
    }

    /** 投影的 7×7×7 中不符合 {@link #projectionTarget} 的格（{@code shellOnly} 時只看不變量 cell）；全部符合時為空。 */
    static List<BlockPos> projectionMismatches(ServerWorld world, ChamberFrame frame, boolean shellOnly) {
        var result = new ArrayList<BlockPos>();
        for (int x = 0; x < 7; x++) for (int y = 0; y < 7; y++) for (int z = 0; z < 7; z++) {
            if (shellOnly && !ChamberGeometry.isShellCell(x, y, z)) continue;
            var pos = ChamberGeometry.localToWorld(frame, x, y, z);
            if (!world.getBlockState(pos).equals(projectionTarget(frame, x, y, z))) result.add(pos);
        }
        return result;
    }

    // ---- PREPARE（F4b-2） ----

    /** production 預載 ticket 的名稱（spec §9.1；以字面值比對，不讀 production 的常數）。 */
    static final String DESTINATION_TICKET = "quantumchamber_destination";

    /**
     * F4b-2：這個 session 在 {@code world} 持有的預載 ticket（名稱為 {@value #DESTINATION_TICKET}、argument 是 session UUID）所在的 chunk 與 level，
     * 從原生 ticket 表唯讀取得（{@code ChunkTicketManagerTestAccessor}；不修改原生集合）。沒有時為空。
     */
    static Map<ChunkPos, Integer> destinationTickets(ServerWorld world, UUID sid) {
        return tickets(world, DESTINATION_TICKET, sid);
    }

    /**
     * {@code world} 的原生 ticket 表中，類型名稱是 {@code type}、argument 是 {@code argument} 的 ticket 所在的 chunk 與 level（依 x、z 排序；同一個 chunk 有幾張時記最後
     * 讀到的那張），從原生 ticket 表唯讀取得（{@code ChunkTicketManagerTestAccessor}；不修改原生集合）。沒有時為空。F6-2d Step 0（F6-2c quality Minor 8）：由
     * {@link #destinationTickets} 抽出，M5 collapse probe 查來源 ticket 也用它（原本是 {@code M5CollapseFixture.tickets} 的同一個迴圈）。
     */
    static Map<ChunkPos, Integer> tickets(ServerWorld world, String type, Object argument) {
        Objects.requireNonNull(argument, "argument");
        var manager = world.getChunkManager().chunkLoadingManager.getTicketManager();
        var entries = ((dev.quantumchamber.gametest.mixin.ChunkTicketManagerTestAccessor) manager).quantumchamberTest$getTicketsByPosition();
        var result = new java.util.TreeMap<ChunkPos, Integer>(Comparator.comparingInt((ChunkPos chunk) -> chunk.x).thenComparingInt(chunk -> chunk.z));
        for (var entry : entries.long2ObjectEntrySet()) for (var ticket : entry.getValue()) {
            if (!type.equals(ticket.getType().toString())) continue;
            var held = ((dev.quantumchamber.gametest.mixin.ChunkTicketArgumentTestAccessor) (Object) ticket).quantumchamberTest$getArgument();
            if (argument.equals(held)) result.put(new ChunkPos(entry.getLongKey()), ticket.getLevel());
        }
        return result;
    }

    /**
     * F4b-2：testmod 專用的重建 hook（README「投影的殘留與隔離」）。projection ledger 在執行期遺失（檔案被刪、之後的 checked 寫入在前置檢查發現）之後，把檔案以
     * {@code bytes} 寫回，再以反射解除 M5 authority 的遺失標記（{@code CollapseAuthorities} 的 projection slot 的 {@code lost}）與投影保護索引的退化
     * （{@code degraded}），讓同一個 M5 run 之後的 batch 不受影響：兩者在同一個 instance 內本來不會解除，而 batch 的順序不固定。{@code bytes} 必須是遺失之前的正式檔
     * （遺失的偵測不改 current，所以寫回之後檔案、已確認的內容與 current 一致）。之後權威必須健康、索引不退化，否則丟 IllegalStateException。冪等；只在 M5 run 用。
     * 反射的 {@code projection}、{@code lost}、{@code degraded} 都是本 mod 自己的欄位，不經 remap（F4b-2 fix round 1 更正）；改名時這裡要跟著改。
     */
    static void rebuildProjectionAuthority(MinecraftServer server, byte[] bytes) {
        var file = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).resolve("data").resolve(dev.quantumchamber.collapse.ProjectionLedger.FILE_NAME);
        try {
            java.nio.file.Files.write(file, bytes);
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("重建 hook 寫不回 projection ledger", failure);
        }
        var authorities = dev.quantumchamber.collapse.CollapseAuthorities.get(server);
        M4CandidateTestAccess.set(M4CandidateTestAccess.get(authorities, "projection"), "lost", null);
        var index = dev.quantumchamber.collapse.ProjectionProtectionIndex.get(server);
        M4CandidateTestAccess.set(index, "degraded", null);
        var health = authorities.unhealthy(dev.quantumchamber.collapse.CollapseAuthorities.Authority.PROJECTION_LEDGER);
        var degradation = index.degradation();
        if (health.isPresent() || degradation.isPresent()) throw new IllegalStateException("重建之後 projection ledger 仍不健康：" + health + " " + degradation);
        LOGGER.info("M5_PROJECTION_AUTHORITY_REBUILT bytes={}", bytes.length);
    }

    /**
     * spec §9.1 的預載範圍，由 test 自己逐格算出（不讀 production 的 {@code CollapseService.preloadChunks}）。F5-1（F4b-2 spec S-M1，spec 的 errata 交給 F7）起，預載與傳送時的
     * FULL 檢查同一個推導：艙體 bbox（整格的連續座標）水平外擴 1 格，再像 FULL 檢查一樣外擴 2 格，所以是 x 從 {@code minX − 3} 到 {@code maxX + 4}（z 同理）每一格的 chunk。
     */
    static Set<ChunkPos> preloadArea(ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        var chunks = new java.util.HashSet<ChunkPos>();
        for (int x = bounds.getMinX() - 3; x <= bounds.getMaxX() + 4; x++)
            for (int z = bounds.getMinZ() - 3; z <= bounds.getMaxZ() + 4; z++) chunks.add(new ChunkPos(new BlockPos(x, 0, z)));
        return Set.copyOf(chunks);
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

    /**
     * 讀掉玩家目前收到的 actionbar 訊息，回傳翻譯 key（不是翻譯訊息時為文字）；先呼叫一次可以清掉之前的封包。{@link ConnectedGameTestPlayer#drainOutbound} 會讀掉並釋放所有型別的
     * 封包，所以這裡也會讀掉 title 等其他封包（F6-1 Step 0，F5-3 quality n-4 附帶）：同一個時點還要看 title 時，改用 {@link #hud} 一次讀完，不要先呼叫這個。
     */
    static List<String> overlayKeys(ConnectedGameTestPlayer connection) {
        var keys = new ArrayList<String>();
        for (var message : connection.drainOutbound(net.minecraft.network.packet.s2c.play.GameMessageS2CPacket.class)) {
            if (!message.overlay()) continue;
            keys.add(message.content().getContent() instanceof net.minecraft.text.TranslatableTextContent translatable
                    ? translatable.getKey() : message.content().getString());
        }
        return keys;
    }

    /**
     * 一次讀掉玩家目前收到的全部封包，回傳其中的 actionbar 與 title 系列封包（F5-3）。{@link ConnectedGameTestPlayer#drainOutbound} 會讀掉並釋放所有型別的封包，
     * 所以同一個時點要同時看 actionbar 與 title 時只呼叫這一個：先呼叫 {@link #overlayKeys} 再讀 title 會讀不到。先呼叫一次可以清掉之前的封包。
     */
    static Hud hud(ConnectedGameTestPlayer connection) {
        var overlays = new ArrayList<String>();
        var titles = new ArrayList<String>();
        for (var packet : connection.drainOutbound(Object.class)) {
            switch (packet) {
                case GameMessageS2CPacket message when message.overlay() -> overlays.add(textKey(message.content()));
                case OverlayMessageS2CPacket overlay -> overlays.add(textKey(overlay.text()));
                case TitleFadeS2CPacket fade -> titles.add("fade " + fade.getFadeInTicks() + "/" + fade.getStayTicks() + "/" + fade.getFadeOutTicks());
                case TitleS2CPacket title -> titles.add("title " + textKey(title.text()));
                case SubtitleS2CPacket subtitle -> titles.add("subtitle " + textKey(subtitle.text()));
                case ClearTitleS2CPacket clear -> titles.add(clear.shouldReset() ? "reset" : "clear");
                default -> { }
            }
        }
        return new Hud(List.copyOf(overlays), List.copyOf(titles));
    }

    /**
     * {@link #hud} 讀到的封包：{@code overlays} 是 actionbar 的翻譯 key（overlay 的 {@code GameMessageS2CPacket} 與 {@code OverlayMessageS2CPacket}；不是翻譯訊息時為文字），
     * {@code titles} 是 title 系列封包的描述（{@code fade <淡入>/<停留>/<淡出>}、{@code title <key>}、{@code subtitle <key>}、{@code clear}、{@code reset}）；兩者都依送出順序。
     */
    record Hud(List<String> overlays, List<String> titles) { }

    /** spec §20.9 的到達提示依送出順序的 title 封包：先明確送出淡入、停留、淡出 10／70／20 ticks，再送 title，沒有 subtitle（以字面值斷言，不信任 production 常數）。 */
    static List<String> arrivalTitle(String key) {
        return List.of("fade 10/70/20", "title " + key);
    }

    private static String textKey(Text text) {
        return text.getContent() instanceof TranslatableTextContent translatable ? translatable.getKey() : text.getString();
    }

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
