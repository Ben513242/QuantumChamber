package dev.quantumchamber.gametest;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.collapse.ProjectionWriter;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTestState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * M5 GameTest 專用的 testmod hook：mixin 讀取的靜態狀態（F4a-0 從 {@code M4CandidateDoorGameTests} 搬來）。release JAR 不含此類。
 * <ul>
 * <li>collapse-suspended（M5 plan 決策 7，{@code CollapseSuspendMixin} 讀取）：被加入的 session，{@code CollapseService} 完全不推進，
 *     M4 的 GameTest 因此維持「MEASURED 會一直等待」的原斷言。不變量：同一個 JVM 內不得清除（沒有移除的 API）；default run 中每一個
 *     會產生 MEASURED 的 test 都必須在 CollapseService 第一次看到該 session 之前（選門的同一個 tick 之前）加入。可撤銷的暫停另見 {@link #STEP_PAUSE}。</li>
 * <li>journal flush 探針（{@link #FLUSH_PROBE}）：{@code SessionJournalWindowFaultMixin} 在每次 journal flush 開頭呼叫 {@link #beforeJournalFlush}，
 *     探針丟出例外即讓那次寫出受控失敗。只由 M5 的 test 經 {@code M5GameTestSupport.installFlushProbe} 安裝。</li>
 * <li>journal 寫出稽核（{@link #JOURNAL_AUDIT}，F4a-0 m-1）：{@code SessionJournalWriteCountMixin} 在 journal 正式檔每一次實際寫出之前呼叫
 *     {@link #beforeJournalWrite}。稽核由 {@code M5GameTestSupport.afterAutosaveClear} 在窗口開始時安裝。</li>
 * <li>物化失敗（{@link #MATERIALIZE_FAULT}，F4a）：{@code MaterializeFaultMixin} 在 lifecycle backend 每一次物化的開頭呼叫
 *     {@link #beforeMaterialize}，注入時以 {@code FAILED_ROLLED_BACK} 取代那次物化。由 {@code M5GameTestSupport.installMaterializeFault} 安裝。</li>
 * <li>checked NBT 檔的讀取失敗（{@link #CHECKED_READ_FAULT}，F4a）：{@code CheckedNbtFileReadFaultMixin} 在 {@code CheckedNbtFile.readFile} 的開頭
 *     呼叫 {@link #beforeCheckedRead}，注入時丟 {@link IOException}。由 {@code M5GameTestSupport.installCheckedReadFault} 安裝。</li>
 * <li>原生存檔 checkpoint 的探針（{@link #CHUNK_CHECKPOINT_PROBE}，F4b-2）：{@code ChunkSaveCheckpointProbeMixin} 在 {@code ChunkSaveCheckpoint.save} 的開頭
 *     呼叫 {@link #beforeChunkCheckpoint}，記下指定世界的每一次 checkpoint，前幾次可以受控失敗。由 {@code M5GameTestSupport.installChunkCheckpointProbe} 安裝。</li>
 * <li>投影寫入失敗（{@link #PROJECTION_WRITE_FAULT}，F4b-2）：{@code ProjectionWriteFaultMixin} 在 {@code ProjectionWriter.write} 回傳之前呼叫
 *     {@link #afterProjectionWrite}，注入時把寫完的結果換成 WRITE_FAILED。由 {@code M5GameTestSupport.installProjectionWriteFault} 安裝。</li>
 * <li>journal put 的拒絕（{@link #JOURNAL_PUT_FAULT}，F4b-3）：{@code SessionJournalPutFaultMixin} 在 {@code SessionRecoveryState.put} 的開頭呼叫
 *     {@link #beforeJournalPut}，注入時丟出例外，checked 提交因此被拒（REJECTED）。由 {@code M5GameTestSupport.installJournalPutFault} 安裝。</li>
 * <li>原艙門攔截的失敗（{@link #DOOR_USE_FAULT}，F5-0，F4b-3 quality M-2、spec m-1）：{@code OriginDoorUseFaultMixin} 在 {@code OriginDoors.endByParticipant}（PREPARE 期間參與者
 *     開關門的處置）的開頭呼叫 {@link #beforeParticipantDoorUse}、在 {@code OriginDoors.sessionAt}（找出所屬 session）的開頭呼叫 {@link #beforeDoorSessionLookup}，注入時丟出例外，
 *     攔截因此在處置途中或找出所屬 session 時失敗（拒絕、記 WARN）。由 {@code M5GameTestSupport.installDoorUseFault} 安裝。</li>
 * <li>通道的原生移動被拒（{@link #NATIVE_MOVE_FAULT}，F5-1）：{@code SessionTransferFaultMixin} 在 {@code SessionTransferService.move(ServerPlayerEntity, …)} 的開頭呼叫
 *     {@link #rejectNativeMove}，注入時那次原生移動回 false（玩家沒有移動）：移動被拒、回滾成功就是 {@code FAILED_ROLLED_BACK}；回滾也被拒就是
 *     {@code FAILED_RECOVERY_REQUIRED}。由 {@code M5GameTestSupport.installNativeMoveFault} 安裝。</li>
 * <li>PASSAGE_READY 的提交暫緩（{@link #READY_HOLD}，F5-1）：{@code CollapseReadyHoldMixin} 在 {@code CollapseService.ready}（準備完成之後提交 PASSAGE_READY）的開頭呼叫
 *     {@link #readyHeld}，裝著時這個 session 不提交、PREPARE 照常（原艙門每個 tick 依條件判斷），等同 production 可以到達的「PASSAGE_READY 的前置條件還沒成立」，只是時間
 *     拉長。給「準備完成之後仍在 PREPARE」的原艙門 test（F4b-3 的 T-MEM、T-FAIL；F5-1 之前它們停在 READY stub），以及要控制通道起算時點的 test（F5-1 的
 *     {@code m5_universe_passage_crossed_pending}）用。由 {@code M5GameTestSupport.holdReady} 安裝。</li>
 * <li>塌縮流程推進的暫停（{@link #STEP_PAUSE}，F5-1 fix round 1）：{@code CollapseSuspendMixin} 經 {@link #cancelStep} 讀它（F5-2 Step 0 起；{@link #collapseSuspended} 是不計數的
 *     純查詢），裝著、還沒放行時這個 session 的
 *     {@code CollapseService.step} 一開始就取消；與 collapse-suspended 的集合不同，可以撤銷。給「這個 session 的推進被略過幾個 tick、之後恢復」的 test 用（F5-1 fix round 1 的
 *     {@code m5_universe_passage_stale}）。由 {@code M5GameTestSupport.installStepPause} 安裝。</li>
 * <li>進入 SEALED 的 Controller 探針（{@link #SEAL_PROBE}，F5-2，F4a M-3）：{@code CollapseSealProbeMixin} 在 {@code CollapseService.sealCommitted}（進入 SEALED 之後的共同步驟）
 *     的開頭與每一個 RETURN 呼叫 {@link #aroundSealCommitted}，記下那一刻來源 Controller 的比較器與 {@code ChamberState}：RETURN 的值證明 {@code sealCommitted} 自己刷新了
 *     Controller（之後的排程刷新不在同一刻）。由 {@code M5GameTestSupport.installSealProbe} 安裝。</li>
 * <li>原生存檔 checkpoint 的 entity storage 就緒保留（{@link #ENTITY_STORAGE_HOLD}，F6-0，G-NLIVE 的根因）：{@code ChunkSaveCheckpointEntityHoldMixin} 在
 *     {@code ChunkSaveCheckpoint} 判斷 chunk 與 entity storage 都已載入的開頭呼叫 {@link #entityStorageHeld}，保留中的 chunk 回「尚未載入」。由
 *     {@code M5GameTestSupport.holdEntityStorage} 安裝。</li>
 * <li>隔離 session 的收斂失敗（{@link #CONVERGE_FAULT}，F6-0，F4b-3 spec S-M1）：{@code QuarantineConvergeFaultMixin} 在 {@code SessionRecoveryState.convergeQuarantined} 的開頭呼叫
 *     {@link #beforeConvergeQuarantined}，注入時丟出例外，session 因此一直隔離著。由 {@code M5GameTestSupport.installConvergeFault} 安裝。</li>
 * <li>跨 JVM 的 crash 點（{@link #CRASH_POINT}，F6-1，spec §12 的 crash 窗口）：每個窗口的 hook mixin（例如 {@code CollapseAuthoritiesCrashWindowMixin}）在窗口剛成立的那一刻
 *     呼叫 {@link #reachedCrashWindow}；裝著的 {@link CrashPoint} 的窗口與條件都符合時執行它的動作。只給 {@code M5CollapseRecoveryProbe} 用：它的動作寫出 halt receipt 之後以
 *     {@code Runtime.halt} 結束 JVM（不經正常停止，等同 crash）。probe 不是 GameTest，所以安裝時沒有 {@link GameTestState}（{@code install(value, null)}），JVM 結束之前不移除。</li>
 * </ul>
 * 三個探針（journal flush、原生存檔 checkpoint、進入 SEALED 的 Controller）、寫出稽核、八個故障注入（物化、checked NBT 讀取、投影寫入、journal put、原艙門的攔截、通道的原生移動、checkpoint 的
 * entity storage 就緒保留、隔離 session 的收斂）、PASSAGE_READY 的提交暫緩與塌縮流程推進的暫停各是一個 {@link Slot}（F4a-0 fix round 1，quality Q1；F4b-3 fix round 1、F5-1、F5-1 fix round 1、F5-2、F6-0 更正數目）：同一時間只有一個持有者，被占用時再安裝會丟出、不覆蓋；持有者的 hooks 釋放時，
 * 或持有者的 test 結束時（含 tickLimit 逾時，這時 hooks 不會釋放），由 {@code M5GameTestSupport} 移除。跨 JVM 的 crash 點也是一個 {@link Slot}，但只由
 * {@code M5CollapseRecoveryProbe} 安裝（F6-1）。
 */
public final class M5TestHooks {
    private M5TestHooks() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber-testmod");
    private static final Set<UUID> COLLAPSE_SUSPENDED = ConcurrentHashMap.newKeySet();
    /** journal flush 探針（{@code M5GameTestSupport.installFlushProbe}）。 */
    static final Slot<Consumer<SessionRecoveryState>> FLUSH_PROBE = new Slot<>("journal flush 探針");
    /** journal 寫出稽核（{@code M5GameTestSupport.installJournalAudit}，由 {@code afterAutosaveClear} 呼叫）。 */
    static final Slot<JournalWriteAudit> JOURNAL_AUDIT = new Slot<>("journal 寫出稽核");
    /** 物化失敗的注入（{@code M5GameTestSupport.installMaterializeFault}）。 */
    static final Slot<MaterializeFault> MATERIALIZE_FAULT = new Slot<>("物化失敗注入");
    /** checked NBT 檔的讀取失敗注入（{@code M5GameTestSupport.installCheckedReadFault}）。 */
    static final Slot<CheckedReadFault> CHECKED_READ_FAULT = new Slot<>("checked NBT 讀取失敗注入");
    /** 原生存檔 checkpoint 的探針與失敗注入（F4b-2，{@code M5GameTestSupport.installChunkCheckpointProbe}）。 */
    static final Slot<ChunkCheckpointProbe> CHUNK_CHECKPOINT_PROBE = new Slot<>("原生存檔 checkpoint 探針");
    /** 投影寫入失敗的注入（F4b-2，{@code M5GameTestSupport.installProjectionWriteFault}）。 */
    static final Slot<ProjectionWriteFault> PROJECTION_WRITE_FAULT = new Slot<>("投影寫入失敗注入");
    /** journal put 的拒絕注入（F4b-3，{@code M5GameTestSupport.installJournalPutFault}）。 */
    static final Slot<JournalPutFault> JOURNAL_PUT_FAULT = new Slot<>("journal put 拒絕注入");
    /** 原艙門攔截的失敗注入（F5-0，{@code M5GameTestSupport.installDoorUseFault}）。 */
    static final Slot<DoorUseFault> DOOR_USE_FAULT = new Slot<>("原艙門攔截的失敗注入");
    /** 通道的原生移動被拒（F5-1，{@code M5GameTestSupport.installNativeMoveFault}）。 */
    static final Slot<NativeMoveFault> NATIVE_MOVE_FAULT = new Slot<>("通道的原生移動被拒注入");
    /** PASSAGE_READY 的提交暫緩（F5-1，{@code M5GameTestSupport.holdReady}）。 */
    static final Slot<ReadyHold> READY_HOLD = new Slot<>("PASSAGE_READY 的提交暫緩");
    /** 塌縮流程推進的暫停（F5-1 fix round 1，可撤銷；{@code M5GameTestSupport.installStepPause}）。 */
    static final Slot<StepPause> STEP_PAUSE = new Slot<>("塌縮流程推進的暫停");
    /** 進入 SEALED 的 Controller 探針（F5-2，F4a M-3；{@code M5GameTestSupport.installSealProbe}）。 */
    static final Slot<SealProbe> SEAL_PROBE = new Slot<>("進入 SEALED 的 Controller 探針");
    /** 原生存檔 checkpoint 的 entity storage 就緒保留（F6-0，G-NLIVE 的根因；{@code M5GameTestSupport.holdEntityStorage}）。 */
    static final Slot<EntityStorageHold> ENTITY_STORAGE_HOLD = new Slot<>("checkpoint 的 entity storage 就緒保留");
    /** 隔離 session 的收斂失敗（F6-0，F4b-3 spec S-M1；{@code M5GameTestSupport.installConvergeFault}）。 */
    static final Slot<ConvergeFault> CONVERGE_FAULT = new Slot<>("隔離 session 的收斂失敗注入");
    /** 跨 JVM 的 crash 點（F6-1；只由 {@code M5CollapseRecoveryProbe} 安裝）。 */
    static final Slot<CrashPoint> CRASH_POINT = new Slot<>("跨 JVM 的 crash 點");

    /**
     * 這個 session 是否被暫停（純查詢，不計數；F5-2 Step 0，F5-1 fix round 1 quality re-review R-m2）：system property {@code quantumchamber.test.collapseSuspended=all} 暫停全部；
     * 不可撤銷的集合（{@link #suspendCollapse}）之外，另看可撤銷的推進暫停（{@link #STEP_PAUSE}，F5-1 fix round 1）。test 的程式可以直接用它判斷；取消推進並計數的入口是
     * {@link #cancelStep}（只由 {@code CollapseSuspendMixin} 呼叫）。
     */
    public static boolean collapseSuspended(UUID session) {
        if (suspendedForGood(session)) return true;
        var pause = STEP_PAUSE.get();
        return pause != null && pause.pausing(session);
    }

    /**
     * {@code CollapseSuspendMixin}：{@code CollapseService.step} 的開頭呼叫，回 true 時取消這個 session 這一次的推進（判斷同 {@link #collapseSuspended}）。由可撤銷的推進暫停
     * （{@link #STEP_PAUSE}）取消時另計一次 {@link StepPause#hits}（F5-2 Step 0，R-m2：計數只在這個入口，查詢沒有副作用）。
     */
    public static boolean cancelStep(UUID session) {
        if (suspendedForGood(session)) return true;
        var pause = STEP_PAUSE.get();
        return pause != null && pause.cancel(session);
    }

    /**
     * 不可撤銷的暫停（{@link #collapseSuspended} 與 {@link #cancelStep} 共用的前半，F6-0，F5-2 quality Nit）：system property {@code quantumchamber.test.collapseSuspended=all}，或
     * 已加入 {@link #suspendCollapse} 的集合。
     */
    private static boolean suspendedForGood(UUID session) {
        return "all".equals(System.getProperty("quantumchamber.test.collapseSuspended")) || COLLAPSE_SUSPENDED.contains(session);
    }

    /** 暫停這個 session 的塌縮流程（不可撤銷）。 */
    static void suspendCollapse(UUID session) { COLLAPSE_SUSPENDED.add(Objects.requireNonNull(session, "session")); }

    /**
     * {@code SessionJournalWindowFaultMixin}：每次 journal flush 開頭呼叫。只在 server thread 上：先讓進行中的稽核記下這次 flush（探針看到的
     * flush），再把 journal 交給目前的探針（探針可以丟出例外讓這次寫出受控失敗）。
     */
    public static void beforeJournalFlush(SessionRecoveryState state, MinecraftServer server) {
        if (!server.isOnThread()) return;
        var audit = JOURNAL_AUDIT.get();
        if (audit != null) audit.record(state, false);
        var probe = FLUSH_PROBE.get();
        if (probe != null) probe.accept(state);
    }

    /** {@code SessionJournalWriteCountMixin}：journal 正式檔每一次實際寫出之前呼叫（不論來源）。沒有進行中的稽核時不做任何事；不丟出例外。 */
    public static void beforeJournalWrite(SessionRecoveryState state) {
        var audit = JOURNAL_AUDIT.get();
        if (audit != null) audit.record(state, true);
    }

    /**
     * {@code MaterializeFaultMixin}：lifecycle backend 每一次物化的開頭呼叫。回傳非 null 時，mixin 以它取代這次物化（不呼叫原本的物化）。
     * 只在 server thread 上生效；不丟出例外。
     */
    public static MaterializeResult beforeMaterialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
        if (!server.isOnThread()) return null;
        var fault = MATERIALIZE_FAULT.get();
        return fault == null ? null : fault.intercept(descriptor);
    }

    /** {@code CheckedNbtFileReadFaultMixin}：checked NBT 檔每一次讀取的開頭呼叫；注入時丟 {@link IOException}，那次讀取因此失敗。 */
    public static void beforeCheckedRead(Path path) throws IOException {
        var fault = CHECKED_READ_FAULT.get();
        if (fault != null) fault.intercept(path);
    }

    /**
     * {@code ChunkSaveCheckpointProbeMixin}：{@code ChunkSaveCheckpoint.save} 的開頭呼叫（F4b-2）。只在 server thread 上：交給目前的探針記下這次 checkpoint；
     * 探針在受控失敗時丟 {@link IllegalStateException}，那次 checkpoint 因此在存檔之前失敗（production 一律把 checkpoint 的例外當成失敗）。
     */
    public static void beforeChunkCheckpoint(MinecraftServer server, Map<ServerWorld, Set<ChunkPos>> relevant) {
        if (!server.isOnThread()) return;
        var probe = CHUNK_CHECKPOINT_PROBE.get();
        if (probe != null) probe.before(server, relevant);
    }

    /**
     * {@code ProjectionWriteFaultMixin}：{@code ProjectionWriter.write} 回傳之前呼叫（F4b-2）。回傳非 null 時，mixin 以它取代這次的結果。只在 server thread 上生效；
     * 不丟出例外。
     */
    public static ProjectionWriter.Result afterProjectionWrite(ServerWorld world, ProjectionWriter.Mode mode, ProjectionWriter.Result result) {
        if (!world.getServer().isOnThread()) return null;
        var fault = PROJECTION_WRITE_FAULT.get();
        return fault == null ? null : fault.intercept(world, mode, result);
    }

    /**
     * {@code SessionJournalPutFaultMixin}：journal 每一次 {@code put}（含 checked 提交）的開頭呼叫（F4b-3）。注入時丟 {@link IllegalStateException}，那次 put 因此被拒
     * （checked 提交回 REJECTED，journal 沒有改變）。journal 的 put 本身只在 server thread 上執行。
     */
    public static void beforeJournalPut(dev.quantumchamber.persistence.SessionRecoveryRecord record) {
        var fault = JOURNAL_PUT_FAULT.get();
        if (fault != null) fault.intercept(record);
    }

    /**
     * {@code OriginDoorUseFaultMixin}：PREPARE 期間參與者開關原艙門的處置（{@code OriginDoors.endByParticipant}）的開頭呼叫（F5-0）。注入時丟 {@link IllegalStateException}：
     * 攔截在處置途中失敗（production 拒絕、不改門、依原因去重記 WARN）。攔截只在 server thread 上執行。
     */
    public static void beforeParticipantDoorUse(dev.quantumchamber.persistence.SessionRecoveryRecord view) {
        var fault = DOOR_USE_FAULT.get();
        if (fault != null) fault.interceptParticipantClose(view);
    }

    /**
     * {@code OriginDoorUseFaultMixin}：原艙門的攔截找出所屬 session（{@code OriginDoors.sessionAt}）的開頭呼叫（F5-0，spec m-1）。注入時丟 {@link IllegalStateException}：
     * 攔截在還沒有 session 可以歸屬時失敗（production 拒絕、不改門、記 tick 層級的 WARN）。
     */
    public static void beforeDoorSessionLookup(dev.quantumchamber.chamber.ChamberFrame frame) {
        var fault = DOOR_USE_FAULT.get();
        if (fault != null) fault.interceptSessionLookup(frame);
    }

    /**
     * {@code SessionTransferFaultMixin}：{@code SessionTransferService.move(ServerPlayerEntity, …)} 的開頭呼叫（F5-1）。回 true 時那次原生移動回 false、玩家不移動。只在
     * server thread 上（原生移動本身只在 server thread 上）。
     */
    public static boolean rejectNativeMove(net.minecraft.server.network.ServerPlayerEntity player, ServerWorld target) {
        var fault = NATIVE_MOVE_FAULT.get();
        return fault != null && fault.intercept(player, target);
    }

    /** {@code CollapseReadyHoldMixin}：{@code CollapseService.ready} 的開頭呼叫（F5-1）。回 true 時這次不提交 PASSAGE_READY。 */
    public static boolean readyHeld(UUID session) {
        var hold = READY_HOLD.get();
        return hold != null && hold.holds(session);
    }

    /**
     * {@code CollapseSealProbeMixin}：{@code CollapseService.sealCommitted} 的開頭（{@code returned} 為假）與每一個 RETURN（為真）呼叫（F5-2，F4a M-3）。只在 server thread 上
     * （塌縮流程的 tick 裡）；不丟出例外。
     */
    public static void aroundSealCommitted(dev.quantumchamber.persistence.SessionRecoveryRecord view, boolean returned) {
        var probe = SEAL_PROBE.get();
        if (probe != null) probe.observe(view, returned);
    }

    /**
     * {@code ChunkSaveCheckpointEntityHoldMixin}：{@code ChunkSaveCheckpoint} 判斷一個 chunk「已載入為 WorldChunk，而且 entity storage 已載入」（{@code ready} 與 {@code save} 的前置條件
     * 共用的 {@code loaded}）的開頭呼叫（F6-0）。回 true 時那次判斷回「尚未載入」。只在 server thread 上（checkpoint 只在 server thread 上執行）。
     */
    public static boolean entityStorageHeld(ServerWorld world, ChunkPos pos) {
        var hold = ENTITY_STORAGE_HOLD.get();
        return hold != null && world.getServer().isOnThread() && hold.holds(world, pos);
    }

    /**
     * {@code QuarantineConvergeFaultMixin}：{@code SessionRecoveryState.convergeQuarantined}（recovery 每個 tick 對隔離中的 session 的收斂）的開頭呼叫（F6-0）。注入時丟
     * {@link IllegalStateException}：recovery 依原因去重記 WARN「隔離 session 尚無法收斂」、維持隔離，下一個 tick 再試（production 的 validateOwnership 一直失敗時的路徑）。
     * 只在 server thread 上（收斂只在 server thread 上執行）。
     */
    public static void beforeConvergeQuarantined(UUID session) {
        var fault = CONVERGE_FAULT.get();
        if (fault != null) fault.intercept(session);
    }

    /**
     * spec §12 的跨 JVM crash 窗口（F6-1）：每個值是「窗口剛成立」的那一個程式點，由該點的 hook mixin 呼叫 {@link #reachedCrashWindow}。呼叫端與交給 crash 點的 detail：
     * <ul>
     * <li>{@code SessionRecoveryStateCrashWindowMixin}：{@link #COLLAPSING_COMMITTED}（每次 {@code commitRestore} 回傳，任何 restore 類的邊與結果都是，
     *     {@link CollapsingCommit}）；</li>
     * <li>{@code CollapseAuthoritiesCrashWindowMixin}：{@link #ALLOCATION_APPENDED}、{@link #CATALOG_ALLOCATED}、{@link #DISCOVERY_APPENDED}（回傳的
     *     {@code CollapseAuthorities.Lookup}），{@link #PROJECTION_COMPLETE}（{@code writeProjection} 回傳的 {@code CollapseAuthorities.WriteResult}，每次寫入都呼叫，
     *     PENDING 與 COMPLETE 都是）；</li>
     * <li>{@code ProjectionWriterCrashWindowMixin}：{@link #PROJECTION_CELL_WRITTEN}（每寫一格，{@link ProjectionCell}）、{@link #PROJECTION_REPAIRED}（每次
     *     {@code write} 回傳，兩種模式都是，{@link ProjectionWrite}）；</li>
     * <li>{@code ChunkSaveCheckpointCrashWindowMixin}：{@link #DESTINATION_CHECKPOINT}（每次原生存檔 checkpoint 開始之前，任何世界，{@link ChunkCheckpoint}）。</li>
     * </ul>
     * 所以 detail 是否就是那個窗口（例如 MEASURED→COLLAPSING 已提交、{@code APPENDED}、寫的是 Controller、REPAIR 寫了格），由 crash 點的條件判斷。
     */
    public enum CrashWindow {
        /** W1：{@code SessionRecoveryState.commitRestore} 把 MEASURED 提交為 COLLAPSING 之後（返還之前）。 */
        COLLAPSING_COMMITTED,
        /** W2：{@code CollapseAuthorities.lookupOrAppendAllocation} 回傳 {@code APPENDED}（allocation ledger 已 checked 寫出，catalog 還沒有配置）。 */
        ALLOCATION_APPENDED,
        /** W3：{@code CollapseAuthorities.allocateOrVerifyCatalog} 回傳 {@code APPENDED}（catalog 已 checked 寫出，還沒有物化）。 */
        CATALOG_ALLOCATED,
        /** W4：{@code ProjectionWriter} 寫完一格（投影寫到一半，ledger 為 PENDING）。 */
        PROJECTION_CELL_WRITTEN,
        /** W4b：目的世界 chunks 的原生存檔 checkpoint 開始之前（投影方塊已寫完並驗證，COMPLETE 還沒有寫入）。 */
        DESTINATION_CHECKPOINT,
        /** W5：{@code CollapseAuthorities.appendDiscovery} 回傳 {@code APPENDED}（discovery 已 checked 追加，投影還沒有開始）。 */
        DISCOVERY_APPENDED,
        /** W6：{@code CollapseAuthorities.writeProjection} 寫入 COMPLETE 之後（PASSAGE_READY 之前）。 */
        PROJECTION_COMPLETE,
        /** W6 的 EXISTING 再造訪：{@code ProjectionWriter.write} 以 REPAIR 修復之後（checkpoint 之前）。 */
        PROJECTION_REPAIRED
    }

    /**
     * crash 窗口的 hook mixin：窗口剛成立時呼叫（F6-1）。{@code detail} 是那一刻的值（例如 {@code CollapseAuthorities.Lookup}），交給裝著的 {@link CrashPoint} 判斷。沒有裝時
     * 不做任何事。crash 點的條件或動作丟出例外時記 ERROR、留在 crash 點上（{@link CrashPoint#failure}），不往 production 的呼叫端丟。
     */
    public static void reachedCrashWindow(CrashWindow window, Object detail) {
        var point = CRASH_POINT.get();
        if (point != null) point.reach(window, detail);
    }

    /** {@link CrashWindow#COLLAPSING_COMMITTED} 的 detail：{@code SessionRecoveryState.commitRestore} 的一次呼叫（變更前、變更後的 record）與它的結果。 */
    public record CollapsingCommit(SessionRecoveryRecord expected, SessionRecoveryRecord next, SessionRecoveryState.CommitResult result) {}

    /** {@link CrashWindow#PROJECTION_CELL_WRITTEN} 的 detail：{@code ProjectionWriter} 剛寫完的一格（世界、艙、模式、位置，與這一格是否改變）。 */
    public record ProjectionCell(ServerWorld world, ChamberFrame frame, ProjectionWriter.Mode mode, BlockPos pos, boolean changed) {}

    /** {@link CrashWindow#PROJECTION_REPAIRED} 的 detail：{@code ProjectionWriter.write} 的一次呼叫（世界、艙、模式）與它的結果。 */
    public record ProjectionWrite(ServerWorld world, ChamberFrame frame, ProjectionWriter.Mode mode, ProjectionWriter.Result result) {}

    /** {@link CrashWindow#DESTINATION_CHECKPOINT} 的 detail：{@code ChunkSaveCheckpoint.save} 這一次要存的世界與 chunks。 */
    public record ChunkCheckpoint(Map<ServerWorld, Set<ChunkPos>> relevant) {}

    /**
     * mixin 讀取的一個靜態 hook 位置（F4a-0 fix round 1，quality Q1）：同一時間只有一個持有者。安裝與移除只經 {@code M5GameTestSupport}
     * （先在 hooks 登記移除、再登記 test 結束時的移除，最後才安裝）；mixin 只讀 {@link #get}。安裝與移除在 server thread 上。
     */
    static final class Slot<T> {
        final String name;
        private volatile T value;
        private GameTestState owner;

        Slot(String name) { this.name = name; }

        /** 目前裝著的值；沒有時為 null。 */
        T get() { return value; }

        /**
         * 安裝。位置已被占用時丟 IllegalStateException、不覆蓋：可能是另一個 test 同時使用（同一個 batch 的 test 同時執行），或前一個 test 沒有
         * 釋放的殘留；訊息列出 hook、持有者與它是否已結束，以及這次安裝的 test。
         */
        synchronized void install(T next, GameTestState test) {
            Objects.requireNonNull(next, name);
            if (value != null) throw new IllegalStateException(name + " 已被占用，不覆蓋：持有者 " + describe(owner) + "，這次安裝來自 " + describe(test)
                    + "。同一時間只能有一個 test 使用；用探針或稽核的 test 必須獨占一個 batch");
            value = next;
            owner = test;
        }

        /** 目前仍是 {@code expected} 時移除，回傳是否移除；已被移除或換成別的值時不動。 */
        synchronized boolean remove(T expected) {
            if (expected == null || value != expected) return false;
            value = null;
            owner = null;
            return true;
        }

        private static String describe(GameTestState test) {
            if (test == null) return "?";
            return test + (test.isCompleted() ? "（已結束而沒有釋放）" : "（執行中）");
        }
    }

    /**
     * 物化失敗（F4a）：裝著的期間，對 {@code worldKey} 的每一次 backend 物化都計數並回 backend 的 {@code FAILED_ROLLED_BACK}（安全撤回：沒有建立
     * world、沒有 runtime owner），不呼叫原本的物化。其他 world key 照常。lifecycle 對同一 instance 失敗過的 descriptor 的拒絕
     * （{@code FAILED_BEFORE}）不經過 backend，不計數。
     */
    static final class MaterializeFault {
        private final RegistryKey<World> worldKey;
        private final AtomicInteger calls = new AtomicInteger();

        MaterializeFault(RegistryKey<World> worldKey) { this.worldKey = Objects.requireNonNull(worldKey, "worldKey"); }

        MaterializeResult intercept(UniverseWorldDescriptor descriptor) {
            if (!descriptor.worldKey().equals(worldKey)) return null;
            calls.incrementAndGet();
            return new MaterializeResult(MaterializeResult.Status.FAILED_ROLLED_BACK, null);
        }

        /** backend 對這個 world key 的物化次數（每一次都已注入失敗）。 */
        int calls() { return calls.get(); }
    }

    /**
     * checked NBT 檔的讀取失敗（F4a）：檔名恰為 {@code fileName} 的讀取（暫存檔的名稱不同，不算），先放行 {@code skip} 次，之後的 {@code failures} 次
     * 丟 {@link IOException}，再之後照常。對正式檔注入，在寫入時就是「atomic move 完成、讀回失敗」，在讀取時就是讀檔的 IO 例外。
     */
    static final class CheckedReadFault {
        private final String fileName;
        private int skip, remaining;
        private final AtomicInteger hits = new AtomicInteger();

        CheckedReadFault(String fileName, int skip, int failures) {
            this.fileName = Objects.requireNonNull(fileName, "fileName"); this.skip = skip; this.remaining = failures;
        }

        synchronized void intercept(Path path) throws IOException {
            var name = path.getFileName();
            if (name == null || !name.toString().equals(fileName)) return;
            if (skip > 0) { skip--; return; }
            if (remaining <= 0) return;
            remaining--;
            hits.incrementAndGet();
            throw new IOException("F4a GameTest 受控：讀取 " + fileName + " 失敗");
        }

        /** 已注入失敗的次數。 */
        int hits() { return hits.get(); }
    }

    /**
     * 原生存檔 checkpoint 的探針（F4b-2）：只看 {@code relevant} 含 {@code world} 的 checkpoint（其他世界的照常，例如走廊清理的 MEASURED checkpoint）。
     * 每一次都依序記下 server tick、這個世界的相關 chunks，以及 {@code observe} 在那一刻回傳的觀察（例如 projection ledger 的狀態；它也可以在存檔之前改動
     * 世界，模擬「第一次寫入與 checkpoint 之間」的改動）；前 {@code failFirst} 次在存檔之前受控失敗。{@code observe} 丟出的例外只記下，不影響 checkpoint。
     */
    static final class ChunkCheckpointProbe {
        /** 一次 checkpoint：server tick、這個世界的相關 chunks、{@code observe} 的觀察，以及是否受控失敗。 */
        record Call(int tick, Set<ChunkPos> chunks, String observed, boolean failed) { }

        private final RegistryKey<World> world;
        private final int failFirst;
        private final IntFunction<String> observe;
        private final List<Call> calls = new CopyOnWriteArrayList<>();

        ChunkCheckpointProbe(RegistryKey<World> world, int failFirst, IntFunction<String> observe) {
            this.world = Objects.requireNonNull(world, "world"); this.failFirst = failFirst; this.observe = Objects.requireNonNull(observe, "observe");
        }

        void before(MinecraftServer server, Map<ServerWorld, Set<ChunkPos>> relevant) {
            var mine = relevant.entrySet().stream().filter(entry -> entry.getKey().getRegistryKey().equals(world)).findFirst();
            if (mine.isEmpty()) return;
            int index = calls.size();
            String observed;
            try {
                observed = observe.apply(index);
            } catch (RuntimeException failure) {
                observed = "觀察失敗：" + failure;
                LOGGER.error("M5_CHECKPOINT_PROBE_OBSERVE_FAILED world={} call={}", world.getValue(), index, failure);
            }
            boolean fail = index < failFirst;
            calls.add(new Call(server.getTicks(), Set.copyOf(mine.get().getValue()), observed, fail));
            if (fail) throw new IllegalStateException("F4b-2 GameTest 受控：原生存檔 checkpoint 失敗（第 " + (index + 1) + " 次）");
        }

        /** 依發生順序的每一次 checkpoint（含受控失敗的那幾次）。 */
        List<Call> calls() { return List.copyOf(calls); }
    }

    /**
     * 投影寫入失敗（F4b-2）：裝著的期間，{@code world} 以 {@code mode} 寫完（WRITTEN）的前 {@code failures} 次，結果換成 WRITE_FAILED（帶受控的例外；方塊已經寫了，
     * 模擬寫入途中丟出例外）。其他世界、其他模式、沒有寫完的結果照常。
     */
    static final class ProjectionWriteFault {
        private final RegistryKey<World> world;
        private final ProjectionWriter.Mode mode;
        private int remaining;
        private final AtomicInteger hits = new AtomicInteger();

        ProjectionWriteFault(RegistryKey<World> world, ProjectionWriter.Mode mode, int failures) {
            this.world = Objects.requireNonNull(world, "world"); this.mode = Objects.requireNonNull(mode, "mode"); this.remaining = failures;
        }

        synchronized ProjectionWriter.Result intercept(ServerWorld target, ProjectionWriter.Mode used, ProjectionWriter.Result result) {
            if (!target.getRegistryKey().equals(world) || used != mode || result.status() != ProjectionWriter.Status.WRITTEN || remaining <= 0) return null;
            remaining--;
            hits.incrementAndGet();
            return new ProjectionWriter.Result(ProjectionWriter.Status.WRITE_FAILED, result.written(), List.of(), List.of(),
                    Optional.of(new IllegalStateException("F4b-2 GameTest 受控：投影寫入失敗（寫入 " + result.written() + " 格之後）")));
        }

        /** 已注入失敗的次數。 */
        int hits() { return hits.get(); }
    }

    /**
     * journal put 的拒絕（F4b-3）：裝著的期間，{@code session} 的 put 中狀態為 {@code state} 的前 {@code failures} 次丟 {@link IllegalStateException}（決定性的驗證失敗；
     * journal 沒有改變），之後照常。其他 session、其他狀態照常。F6-0 另記下每一次拒絕的 server tick（{@link #ticks}），讓 test 斷言重試的間隔（per-session 退避）。
     */
    static final class JournalPutFault {
        private final MinecraftServer server;
        private final UUID session;
        private final dev.quantumchamber.superposition.SessionState state;
        private int remaining;
        private final AtomicInteger hits = new AtomicInteger();
        private final List<Integer> ticks = new CopyOnWriteArrayList<>();

        JournalPutFault(MinecraftServer server, UUID session, dev.quantumchamber.superposition.SessionState state, int failures) {
            this.server = Objects.requireNonNull(server, "server");
            this.session = Objects.requireNonNull(session, "session"); this.state = Objects.requireNonNull(state, "state"); this.remaining = failures;
        }

        synchronized void intercept(dev.quantumchamber.persistence.SessionRecoveryRecord record) {
            if (!record.sessionUuid().equals(session) || record.state() != state || remaining <= 0) return;
            remaining--;
            hits.incrementAndGet();
            ticks.add(server.getTicks());
            throw new IllegalStateException("F4b-3 GameTest 受控：journal 拒絕寫入 " + state + "（session=" + session + "）");
        }

        /** 已注入拒絕的次數。 */
        int hits() { return hits.get(); }

        /** 每一次拒絕的 server tick（依發生順序，F6-0）。 */
        List<Integer> ticks() { return List.copyOf(ticks); }
    }

    /**
     * 原艙門攔截的失敗（F5-0；取代以反射把 {@code Track.backoff} 換成 null 的作法）。兩個位置各自武裝，都丟 {@link IllegalStateException}：
     * <ul>
     * <li>處置（F4b-3 quality M-2）：安裝時就武裝；{@code session} 的參與者開關門的處置每一次都在開頭失敗（持續性的處置失敗）。其他 session 照常。{@link #disarm}／{@link #arm}。</li>
     * <li>找出所屬 session（F4b-3 spec m-1）：安裝時不武裝；{@code frame} 這座艙的每一次攔截都在找出所屬 session 時失敗（還沒有 session 可以歸屬）。
     *     {@link #armLookup}／{@link #disarmLookup}。</li>
     * </ul>
     * 在同一個 test 內切換，不必重新安裝。
     */
    static final class DoorUseFault {
        private final UUID session;
        private final dev.quantumchamber.chamber.ChamberFrame frame;
        private volatile boolean armed = true, lookupArmed;
        private final AtomicInteger hits = new AtomicInteger(), lookupHits = new AtomicInteger();

        DoorUseFault(UUID session, dev.quantumchamber.chamber.ChamberFrame frame) {
            this.session = Objects.requireNonNull(session, "session"); this.frame = Objects.requireNonNull(frame, "frame");
        }

        void interceptParticipantClose(dev.quantumchamber.persistence.SessionRecoveryRecord view) {
            if (!armed || !view.sessionUuid().equals(session)) return;
            hits.incrementAndGet();
            throw new IllegalStateException("F5-0 GameTest 受控：PREPARE 期間參與者開關原艙門的處置失敗（session=" + session + "）");
        }

        void interceptSessionLookup(dev.quantumchamber.chamber.ChamberFrame target) {
            if (!lookupArmed || !frame.equals(target)) return;
            lookupHits.incrementAndGet();
            throw new IllegalStateException("F5-0 GameTest 受控：原艙門的攔截找出所屬 session 時失敗（frame=" + frame + "）");
        }

        /** 之後的處置照常（仍裝著）。 */
        void disarm() { armed = false; }
        /** 之後的處置再次失敗。 */
        void arm() { armed = true; }
        /** 已注入的處置失敗次數。 */
        int hits() { return hits.get(); }
        /** 之後這座艙的攔截在找出所屬 session 時失敗。 */
        void armLookup() { lookupArmed = true; }
        /** 之後找出所屬 session 照常。 */
        void disarmLookup() { lookupArmed = false; }
        /** 已注入的找出所屬 session 失敗次數。 */
        int lookupHits() { return lookupHits.get(); }
    }

    /**
     * 窗口內的 journal 寫出稽核（F4a-0 m-1）。記兩種事件：
     * <ul>
     * <li>flush：探針看到的 flush（{@link #beforeJournalFlush}，在探針之前記），含當時有未寫出變更的 session 與呼叫端；</li>
     * <li>write：正式檔每一次實際寫出（{@link #beforeJournalWrite}），依呼叫堆疊判定來源：經 {@code SessionRecoveryState.flush}（探針看得到）、
     *     原生 autosave（{@code MinecraftServer.tick} → {@code saveAll}）、{@code server.save}（例如 {@code ChunkSaveCheckpoint}，呼叫端另列）、
     *     停服存檔，或其他。</li>
     * </ul>
     * 堆疊以 Loom 開發 runtime 的 named 名稱（Yarn 1.21+build.9）比對；GameTest 只在這個 runtime 執行。只在 server thread 上被呼叫。
     */
    static final class JournalWriteAudit {
        private static final String JOURNAL = "dev.quantumchamber.persistence.SessionRecoveryState";
        private static final String SERVER = "net.minecraft.server.MinecraftServer";
        private static final StackWalker WALKER = StackWalker.getInstance();

        /** 寫出的來源。只有 {@link #FLUSH} 經過探針。 */
        enum Origin {
            FLUSH("經 flush（探針看得到）"), AUTOSAVE("原生 autosave"), SERVER_SAVE("server.save"), SHUTDOWN("停服存檔"), OTHER("其他寫出");
            final String label;
            Origin(String label) { this.label = label; }
        }

        /** 一個事件：server tick、是否為實際寫出、來源、呼叫端、當時 current 與 flushed 不同的 session。 */
        record Event(int tick, boolean write, Origin origin, String caller, Set<UUID> changed) {
            @Override public String toString() {
                return (write ? "write" : "flush") + "@" + tick + "[" + origin + " " + caller + " changed=" + changed + "]";
            }
        }

        /** 稽核結果：{@code problems} 是違反「窗口內每一次寫出都經過探針、探針看到的 flush 只帶本 test 的 session」的事件。 */
        record Verdict(boolean pass, List<String> problems, List<Event> events) { }

        private final MinecraftServer server;
        private final List<Event> events = new CopyOnWriteArrayList<>();
        private final List<String> errors = new CopyOnWriteArrayList<>();

        JournalWriteAudit(MinecraftServer server) { this.server = server; }

        /** 記一個事件；稽核本身的例外只記下（判定時失敗），不丟出，以免那次寫出被當成落盤失敗。 */
        void record(SessionRecoveryState state, boolean write) {
            try {
                if (!server.isOnThread()) { errors.add("不在 server thread 的 journal " + (write ? "寫出" : "flush")); return; }
                var frames = WALKER.walk(stream -> stream.toList());
                events.add(new Event(server.getTicks(), write, write ? origin(frames) : Origin.FLUSH, caller(frames), changed(state)));
            } catch (RuntimeException failure) {
                errors.add(failure.toString());
                LOGGER.error("M5_JOURNAL_AUDIT_FAILED 稽核 journal {}時失敗", write ? "寫出" : "flush", failure);
            }
        }

        /**
         * 判定：每一次實際寫出都經 flush（否則是旁路：原生 autosave、server.save、停服或其他寫出）；每一次探針看到的 flush（含受控失敗、沒有寫出的
         * 那幾次）都只帶 {@code own} 的變更（否則是其他 session 的 flush，例如前一個 batch 殘留的 session）。
         */
        Verdict verdict(Collection<UUID> own) {
            var problems = new java.util.ArrayList<String>();
            for (var event : events) {
                if (event.write() && event.origin() != Origin.FLUSH)
                    problems.add("旁路寫出（" + event.origin().label + "，呼叫端 " + event.caller() + "）tick=" + event.tick() + " changed=" + event.changed());
                var foreign = new TreeSet<>(event.changed()); foreign.removeAll(own);
                if (!event.write() && !foreign.isEmpty())
                    problems.add("其他 session 的 flush（session=" + foreign + "，呼叫端 " + event.caller() + "）tick=" + event.tick());
            }
            errors.forEach(error -> problems.add("稽核本身失敗：" + error));
            return new Verdict(problems.isEmpty(), List.copyOf(problems), List.copyOf(events));
        }

        /** 實際寫出的來源：堆疊上有 {@code SessionRecoveryState.flush} 即經 flush；否則依呼叫 {@code MinecraftServer.save/saveAll} 的那一層判定。 */
        private static Origin origin(List<StackWalker.StackFrame> frames) {
            if (frames.stream().anyMatch(frame -> is(frame, JOURNAL, "flush"))) return Origin.FLUSH;
            int save = outermostSave(frames);
            if (save < 0 || save + 1 >= frames.size()) return Origin.OTHER;
            var caller = frames.get(save + 1);
            if (is(caller, SERVER, "tick")) return Origin.AUTOSAVE;
            if (is(caller, SERVER, "shutdown")) return Origin.SHUTDOWN;
            return Origin.SERVER_SAVE;
        }

        /** 呼叫端：經 flush 時是呼叫 flush 的第一個非 journal 方法；否則是呼叫 {@code MinecraftServer.save/saveAll} 的那一層，再沒有就是 journal 之外的第一層。 */
        private static String caller(List<StackWalker.StackFrame> frames) {
            int start = -1;
            for (int i = 0; i < frames.size(); i++) if (is(frames.get(i), JOURNAL, "flush")) { start = i; break; }
            if (start < 0) {
                int save = outermostSave(frames);
                if (save >= 0 && save + 1 < frames.size()) return describe(frames.get(save + 1));
                start = 0;
            }
            for (int i = start; i < frames.size(); i++) {
                var name = frames.get(i).getClassName();
                if (!name.equals(JOURNAL) && !name.equals(M5TestHooks.class.getName()) && !name.startsWith(JournalWriteAudit.class.getName()))
                    return describe(frames.get(i));
            }
            return "?";
        }

        private static int outermostSave(List<StackWalker.StackFrame> frames) {
            int found = -1;
            for (int i = 0; i < frames.size(); i++) {
                var frame = frames.get(i);
                if (is(frame, SERVER, "save") || is(frame, SERVER, "saveAll")) found = i;
            }
            return found;
        }

        private static boolean is(StackWalker.StackFrame frame, String type, String method) {
            return frame.getClassName().equals(type) && frame.getMethodName().equals(method);
        }

        private static String describe(StackWalker.StackFrame frame) {
            var name = frame.getClassName();
            return name.substring(name.lastIndexOf('.') + 1) + "." + frame.getMethodName() + ":" + frame.getLineNumber();
        }

        /** current 與 flushed 不同的 session（含只在其中一邊的）：這次 flush／寫出會帶出的變更。 */
        private static Set<UUID> changed(SessionRecoveryState state) {
            var current = state.records(); var flushed = state.flushedRecords();
            var ids = new TreeSet<UUID>(current.keySet()); ids.addAll(flushed.keySet());
            ids.removeIf(id -> Objects.equals(current.get(id), flushed.get(id)));
            return ids;
        }
    }

    /**
     * 通道的原生移動被拒（F5-1，{@link #NATIVE_MOVE_FAULT}）：{@code player} 往 {@code destination} 的原生移動前 {@code destinationFailures} 次被拒，往其他世界（回滾回來源）
     * 的原生移動前 {@code otherFailures} 次被拒。{@code destinationFailures}＝1、{@code otherFailures}＝0 是 {@code FAILED_ROLLED_BACK}；兩者都是 1 是
     * {@code FAILED_RECOVERY_REQUIRED}。{@code hits()}／{@code destinationHits()} 是已注入的次數（README：以 mixin 注入的 test 必須有 hits 一類的 witness）。
     */
    static final class NativeMoveFault {
        private final UUID player;
        private final RegistryKey<World> destination;
        private int destinationFailures, otherFailures;
        private final AtomicInteger destinationHits = new AtomicInteger(), otherHits = new AtomicInteger();

        NativeMoveFault(UUID player, RegistryKey<World> destination, int destinationFailures, int otherFailures) {
            this.player = Objects.requireNonNull(player, "player"); this.destination = Objects.requireNonNull(destination, "destination");
            this.destinationFailures = destinationFailures; this.otherFailures = otherFailures;
        }

        synchronized boolean intercept(net.minecraft.server.network.ServerPlayerEntity moving, ServerWorld target) {
            if (!moving.getUuid().equals(player)) return false;
            if (target.getRegistryKey().equals(destination)) {
                if (destinationFailures <= 0) return false;
                destinationFailures--;
                destinationHits.incrementAndGet();
                return true;
            }
            if (otherFailures <= 0) return false;
            otherFailures--;
            otherHits.incrementAndGet();
            return true;
        }

        /** 往目的世界的原生移動已被拒的次數。 */
        int destinationHits() { return destinationHits.get(); }
        /** 往其他世界（回滾）的原生移動已被拒的次數。 */
        int otherHits() { return otherHits.get(); }
        /** 已注入的總次數。 */
        int hits() { return destinationHits.get() + otherHits.get(); }
    }

    /**
     * PASSAGE_READY 的提交暫緩（F5-1，{@link #READY_HOLD}）：{@code session} 在裝著、還沒 {@link #release} 的期間不提交；{@code hits()} 是暫緩的次數（證明 mixin 真的套用）。
     */
    static final class ReadyHold {
        private final UUID session;
        private final AtomicInteger hits = new AtomicInteger();
        private volatile boolean released;

        ReadyHold(UUID session) { this.session = Objects.requireNonNull(session, "session"); }

        boolean holds(UUID candidate) {
            if (released || !session.equals(candidate)) return false;
            hits.incrementAndGet();
            return true;
        }

        /** 已暫緩的提交次數。 */
        int hits() { return hits.get(); }

        /** 之後不再暫緩（仍裝著，hooks 釋放時移除）。 */
        void release() { released = true; }
    }

    /**
     * 塌縮流程推進的暫停（F5-1 fix round 1，spec review S-m1；{@link #STEP_PAUSE}）：{@code session} 在裝著、還沒 {@link #release} 的期間，{@code CollapseSuspendMixin} 在
     * {@code CollapseService.step} 的開頭取消它的推進（與 collapse-suspended 相同，但可撤銷），等同 production 中這個 session 的推進被略過的 tick（journal 有未 checked 的
     * authority、隔離中、整個 tick 無法執行）。{@code hits()} 是被取消的推進次數（證明 mixin 真的套用）；只有 mixin 經 {@link #cancelStep} 取消時計數，{@link #collapseSuspended}
     * 的查詢不計數（F5-2 Step 0，R-m2）。
     */
    static final class StepPause {
        private final UUID session;
        private final AtomicInteger hits = new AtomicInteger();
        private volatile boolean released;

        StepPause(UUID session) { this.session = Objects.requireNonNull(session, "session"); }

        /** 這個 session 目前被暫停（裝著、還沒放行）；純查詢。 */
        boolean pausing(UUID candidate) {
            return !released && session.equals(candidate);
        }

        /** 暫停中時取消這一次推進並計數（{@link #cancelStep}）。 */
        boolean cancel(UUID candidate) {
            if (!pausing(candidate)) return false;
            hits.incrementAndGet();
            return true;
        }

        /** 已取消的推進次數。 */
        int hits() { return hits.get(); }

        /** 之後不再暫停（仍裝著，hooks 釋放時移除）。 */
        void release() { released = true; }
    }

    /**
     * 進入 SEALED 的 Controller 探針（F5-2，F4a M-3；{@link #SEAL_PROBE}）：{@code session} 每一次 {@code CollapseService.sealCommitted} 的開頭（{@link #before}）與 RETURN
     * （{@link #after}）各記一次 {@code world} 裡 {@code controller} 那一格的比較器輸出與 Controller 的 {@code ChamberState}。RETURN 的值是 {@code sealCommitted} 自己的
     * 「進入 SEALED 之後刷新 Controller」之後的值：同一刻沒有其他刷新（Controller 每 20 tick 的排程刷新在 world 的 tick 裡，不在塌縮流程的 tick 中途）。{@code hits()} 是記到的
     * RETURN 次數（證明 mixin 真的套用）。讀取失敗只記成 {@code -1}／{@code "?"}，不丟出。
     */
    static final class SealProbe {
        /** 一次觀察：server tick、比較器輸出、Controller 的 ChamberState 名稱。 */
        record Observation(int tick, int comparator, String state) { }

        private final UUID session;
        private final ServerWorld world;
        private final net.minecraft.util.math.BlockPos controller;
        private final List<Observation> before = new CopyOnWriteArrayList<>(), after = new CopyOnWriteArrayList<>();

        SealProbe(UUID session, ServerWorld world, net.minecraft.util.math.BlockPos controller) {
            this.session = Objects.requireNonNull(session, "session"); this.world = Objects.requireNonNull(world, "world");
            this.controller = Objects.requireNonNull(controller, "controller").toImmutable();
        }

        void observe(dev.quantumchamber.persistence.SessionRecoveryRecord view, boolean returned) {
            if (!view.sessionUuid().equals(session)) return;
            int comparator;
            String state;
            try {
                comparator = world.getBlockState(controller).getComparatorOutput(world, controller);
                state = world.getBlockEntity(controller) instanceof dev.quantumchamber.chamber.ChamberControllerBlockEntity entity ? entity.chamberState().name() : "?";
            } catch (RuntimeException failure) {
                LOGGER.error("M5_SEAL_PROBE_FAILED session={}", session, failure);
                comparator = -1;
                state = "?";
            }
            (returned ? after : before).add(new Observation(world.getServer().getTicks(), comparator, state));
        }

        /** 每一次 {@code sealCommitted} 開頭的觀察（依發生順序）。 */
        List<Observation> before() { return List.copyOf(before); }
        /** 每一次 {@code sealCommitted} RETURN 的觀察（依發生順序）。 */
        List<Observation> after() { return List.copyOf(after); }
        /** 記到的 RETURN 次數。 */
        int hits() { return after.size(); }
    }

    /**
     * 原生存檔 checkpoint 的 entity storage 就緒保留（F6-0，G-NLIVE 的根因；{@link #ENTITY_STORAGE_HOLD}）：裝著、還沒 {@link #release} 的期間，{@code world} 的 {@code chunks} 在
     * {@code ChunkSaveCheckpoint} 的就緒判斷裡回「entity storage 尚未載入」，等同 entity storage 非同步載入還沒完成（G-NLIVE 實際遇到的狀態；真正的載入時間無法決定性重現）。
     * 只影響 checkpoint 的判斷（{@code ChunkSaveCheckpoint.ready} 與 {@code save} 的前置條件），不影響原版的 entity 管理與其他就緒判斷（例如走廊的 {@code leaseReady}）。
     * {@code hits()} 是保留生效的次數（README：以 mixin 注入的 test 必須有 hits 一類的 witness）。
     */
    static final class EntityStorageHold {
        private final RegistryKey<World> world;
        private final Set<ChunkPos> chunks;
        private final AtomicInteger hits = new AtomicInteger();
        private volatile boolean released;

        EntityStorageHold(RegistryKey<World> world, Set<ChunkPos> chunks) {
            this.world = Objects.requireNonNull(world, "world"); this.chunks = Set.copyOf(chunks);
            if (this.chunks.isEmpty()) throw new IllegalArgumentException("至少要保留一個 chunk");
        }

        boolean holds(ServerWorld target, ChunkPos pos) {
            if (released || !target.getRegistryKey().equals(world) || !chunks.contains(pos)) return false;
            hits.incrementAndGet();
            return true;
        }

        /** 保留生效的次數。 */
        int hits() { return hits.get(); }

        /** 之後不再保留（仍裝著，hooks 釋放時移除）。 */
        void release() { released = true; }
    }

    /**
     * 隔離 session 的收斂失敗（F6-0，F4b-3 spec S-M1；{@link #CONVERGE_FAULT}）：裝著、還沒 {@link #release} 的期間，{@code session} 的每一次收斂都在開頭失敗，所以它一直隔離著
     * （recovery 的收斂排在塌縮流程之前，沒有這個注入時，test 造出的隔離在同一個 tick 就被收斂、塌縮流程看不到）。{@code hits()} 是注入的次數。
     */
    static final class ConvergeFault {
        private final UUID session;
        private final AtomicInteger hits = new AtomicInteger();
        private volatile boolean released;

        ConvergeFault(UUID session) { this.session = Objects.requireNonNull(session, "session"); }

        void intercept(UUID target) {
            if (released || !session.equals(target)) return;
            hits.incrementAndGet();
            throw new IllegalStateException("F6-0 GameTest 受控：隔離 session 的收斂失敗（session=" + session + "）");
        }

        /** 已注入的收斂失敗次數。 */
        int hits() { return hits.get(); }

        /** 之後的收斂照常（仍裝著，hooks 釋放時移除）。 */
        void release() { released = true; }
    }

    /**
     * 跨 JVM 的 crash 點（F6-1；{@link #CRASH_POINT}）：{@code window} 的 hook 每呼叫一次記一次 {@link #reached}；{@code matches} 為真時記一次 {@link #hits}，以
     * （detail, 第幾次 hit）呼叫 {@code action}。{@code M5CollapseRecoveryProbe} 的動作寫出 halt receipt 之後以 {@code Runtime.halt} 結束 JVM，所以通常不會回來；
     * 動作也可以依 hit 次數決定這次不 crash 而回傳。條件或動作丟出的例外記在 {@link #failure}、記 ERROR，不往 production 丟。
     */
    static final class CrashPoint {
        final CrashWindow window;
        private final java.util.function.Predicate<Object> matches;
        private final java.util.function.ObjIntConsumer<Object> action;
        private final AtomicInteger reached = new AtomicInteger();
        private final AtomicInteger hits = new AtomicInteger();
        private volatile Throwable failure;

        CrashPoint(CrashWindow window, java.util.function.Predicate<Object> matches, java.util.function.ObjIntConsumer<Object> action) {
            this.window = Objects.requireNonNull(window, "window");
            this.matches = Objects.requireNonNull(matches, "matches");
            this.action = Objects.requireNonNull(action, "action");
        }

        void reach(CrashWindow at, Object detail) {
            if (at != window) return;
            reached.incrementAndGet();
            try {
                if (matches.test(detail)) action.accept(detail, hits.incrementAndGet());
            } catch (RuntimeException | Error problem) {
                failure = problem;
                LOGGER.error("F6-1 crash 點的條件或動作失敗：window={}", window, problem);
            }
        }

        /** 這個窗口的 hook 被呼叫的次數（不論條件）。 */
        int reached() { return reached.get(); }

        /** 條件成立的次數。 */
        int hits() { return hits.get(); }

        /** 條件或動作丟出的例外；沒有時為 null。 */
        Throwable failure() { return failure; }
    }
}
