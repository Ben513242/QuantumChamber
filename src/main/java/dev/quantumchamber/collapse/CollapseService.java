package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDetector;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOccupantService;
import dev.quantumchamber.chamber.ChamberPowerCoordinator;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.WorldChamberBlockView;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.CollapseFields;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.MeasuredRecoveryPhase;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalResult;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import dev.quantumchamber.universe.DimensionRole;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * spec §7 的塌縮交易（F3d；F4a 接上 RESOLVE 與 SEALED）：把 checked {@code MEASURED＋SELECTED} 提交為 COLLAPSING，並依推導階段（{@link CollapsePhase}）推進。
 * <ul>
 * <li>只讀 spec §6 的 collapse view（flushed，加上 roll-forward 尚未確認的例外）；每次 journal 寫入前都要求該 session 的 current 恰等於 flushed。
 *     roll-forward 尚未確認時只收斂：同一份 current 的寫出一律由 recovery 的 RETRY_FLUSH 以 journal 為單位退避重試（三個塌縮狀態都是，
 *     含返還與清 lease 進度、進入 SEALED、Crossed；{@code SessionRecoveryManager.recoveryWork}），這裡對該 session 全部暫停、不自行重寫。</li>
 * <li>operation guard（spec §7）：走廊操作進行中的 session 一律略過。live 的提交仍有走廊 space，寫入包在
 *     {@code CorridorPageManager.exclusiveOperation} 之內；DORMANT 續行、終局與取消都在 space 釋放之後（PASSAGE_READY 沒有 space），
 *     沒有 guard 可取，在 server thread 上同步寫入。</li>
 * <li>提交（§7.1）：live 在選門之後的第一次 tick 提交，不要求供電；DORMANT 只在邊緣觸發（原參與者由艙外進入艙內，或開服之後第一次觀察到
 *     原參與者在艙內）而且來源艙已載入、結構合法、已供電、Origin enabled、不在退避期內時提交。「已供電」一律是 Controller 的即時紅石輸入。
 *     提交失敗：live 請求 M4 的保留式 recovery 作為 fallback；兩者都以 session 為單位退避（200→12000 ticks，新 instance 沒有退避）。</li>
 * <li>RETURN_PLAYERS、RELEASE_GEOMETRY 由 recovery 與走廊清理依收據推導（F3a），這裡不重複驅動，也不呼叫 {@code requestMeasuredRecovery}。
 *     離開這兩個階段之前，必須等走廊 space 真的釋放（{@code CorridorPageManager.measuredRecoveryComplete}）。</li>
 * <li>RESOLVE（§8，F4a）：UNIVERSE 目的地經 {@link DestinationResolver} 確定（EXISTING 重驗；NEW 依序 ledger、catalog、物化、discovery），
 *     不需要供電、不看閘門。WAIT（catalog 或 discovery 有未確認的寫入）不進 SEALED，持續超過 {@value #WAIT_WARN_TICKS} ticks 記一次 WARN。
 *     「目的地已確定」只記在本 instance 的簿記，重啟之後 RESOLVE 以 idempotent 的方式重新確認。NEW 的 UniverseId 在 catalog 是 DISABLED 時等同
 *     §8.3 已完成（spec §6 的表）：要不要重試由 resolver 依本 instance 的物化額度判斷，與最近一次封存的原因無關，這裡不傳任何旗標（F4a fix round 1）。</li>
 * <li>進入 SEALED（§11.2、§5、§7.3、§10.7、§10.8）：journal 以 roll-forward 寫入 SEALED(reason)（寫入前 current＝flushed；寫出未確認時依
 *     collapse view 照樣生效，重試交給 recovery 的 RETRY_FLUSH）→ 門改為關閉（門口有 entity 時改為一般開門），經待套用門 →
 *     對參與者顯示「量子通道無法開啟，已封存。」→ 刷新 Controller → 釋放來源 ticket。可重試的原因記 WARN、永久原因記 ERROR「需要管理員處理」，
 *     都依 (session, 原因, {@link FailureText#dedupeKey}) 去重。寫入在前置檢查就被拒時，依 per-session 退避只重試這筆 SEALED 寫入，不重跑 RESOLVE
 *     （F4a fix round 2）。</li>
 * <li>SEALED 的重試（§11.2）：可重試的原因，以與 DORMANT 相同的邊緣觸發，並在 per-session 退避（200→12000，開服之後第一次不受限）之外；
 *     {@code MATERIALIZE_FAILED}、{@code AUTHORITY_UNREADABLE} 只在重啟之後、每個 server instance 一次；永久原因與 TRR 不重試。重試時 checked
 *     SEALED→COLLAPSING（restore 類），再取得來源 ticket，之後依推導階段重跑 RESOLVE（未確認的權威由 resolver 先收斂）。</li>
 * <li>WAITING／PREPARE（§7.3）：第一次走到閘門階段時立即評估閘門，之後只在邊緣觸發時再評估。SOURCE 進入 PREPARE 即走 §7.4 的 fast path：
 *     先 checked 終局、再把門改為一般開門、再顯示訊息；終局之後釋放 runtime 與來源 ticket（spec §5）。UNIVERSE 的 PREPARE 屬 F4b：目的地確定之後
 *     停在 WAITING（不評估閘門），每個 session 記一次 WARN「準備階段尚未實作」，不預載、不投影、不寫 projection ledger。</li>
 * <li>§11.3：來源 role 不是 OVERWORLD、選中 NEW／EXISTING 的 MEASURED 不提交，先以保留式 recovery 到 DORMANT，再 checked 終局取消（INFO）。</li>
 * <li>§12：開服時就是 PASSAGE_READY 的 record 直接 checked 終局（{@code PASSAGE_ENDED}），門在下一個 tick 改為一般開門。</li>
 * </ul>
 * 註冊順序見 {@link #initialize()}；每個 session 的處理都在 {@link #step} 裡個別隔離例外，例外不會從 {@code END_SERVER_TICK} 拋出。
 * 終局與提交失敗的 WARN 由這裡依 (session, 寫入種類, {@link FailureText#dedupeKey}) 去重，成功時清除；正常結束（SOURCE fast path、§11.3 的取消、
 * 開服時通道的結束）一律記 INFO。門的攔截（PREPARE、PASSAGE_READY 期間）屬 F4b，F3d／F4a 的階段門都是一般狀態。
 * 這裡的每一則 log 都帶 {@link #LOG_MARKER}（不屬於任何 session 的帶子 marker {@link #TICK_LOG_MARKER}）。
 */
public final class CollapseService {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    /**
     * 塌縮流程每一則 log 的 SLF4J marker：測試依它篩選塌縮流程的 WARN／ERROR，不依訊息的字串。能歸屬到 session 的 WARN／ERROR 都在訊息中帶
     * {@code session=<UUID>}；不屬於任何 session 的（開服讀不到 journal、整個 tick 無法執行與它的恢復）改帶子 marker {@link #TICK_LOG_MARKER}。
     * logger 名稱、等級與訊息都不變；console 與 latest.log 的格式不含 marker。
     */
    public static final Marker LOG_MARKER = MarkerFactory.getMarker("QUANTUMCHAMBER_COLLAPSE");
    /** 不屬於任何 session 的塌縮流程 log（沒有 session 可帶）；父 marker 是 {@link #LOG_MARKER}。 */
    public static final Marker TICK_LOG_MARKER = childMarker("QUANTUMCHAMBER_COLLAPSE_TICK", LOG_MARKER);
    /** {@link #warn} 的寫入種類：每個 session 每個 tick 的推進（{@link #step}）丟出例外。 */
    private static final String STEP = "推進";
    /** spec §10.8「塌縮」：提交 COLLAPSING 時，以及 SOURCE fast path 打開原艙門之後（§7.4 第 3 步）。 */
    public static final String MESSAGE_COLLAPSED = "message.quantumchamber.m5.collapsed";
    /** spec §10.8「進入 SEALED」：執行期間進入 SEALED 時對參與者顯示（F4a）。 */
    public static final String MESSAGE_SEALED = "message.quantumchamber.m5.sealed";
    /** spec §6.2：RESOLVE 的等待（catalog 或 discovery 有未確認的寫入）持續超過這個 tick 數時記一次 WARN。 */
    static final int WAIT_WARN_TICKS = 600;
    /** {@link #warn} 的寫入種類：進入 SEALED 的 journal 寫入、SEALED→COLLAPSING 的重試。 */
    private static final String SEAL_WRITE = "寫入 SEALED", RETRY = "重試 SEALED→COLLAPSING";
    /** {@link #sideEffect} 的種類：提交 COLLAPSING 與 SEALED→COLLAPSING 重試之後取得來源 ticket（兩處共用同一個去重鍵），與失敗之後實際會發生的事。 */
    private static final String ACQUIRE_TICKET = "取得來源 ticket", ACQUIRE_TICKET_NEXT = "本 server instance 不重試，開服 attach 時會為 COLLAPSING 重新取得";
    /** 進入 SEALED 的 WARN／ERROR 的去重鍵前綴（後接原因）；RESOLVE 成功時清除。 */
    private static final String SEALED_LOG = "進入 SEALED ";
    private static final Map<MinecraftServer, CollapseService> INSTANCES = new ConcurrentHashMap<>();
    private static boolean initialized;

    private final MinecraftServer server;
    /** 開服 attach 當下已在 journal 的 session：DORMANT 與 SEALED 重試的「開服之後第一次觀察到原參與者在艙內」只對它們成立（§7.1、§11.2）。 */
    private final Set<UUID> startupSessions;
    /** 開服時就是 PASSAGE_READY 的 session（§12）；F5 之前執行期間不會產生 PASSAGE_READY。 */
    private final Set<UUID> startupPassages;
    private final Map<UUID, Track> tracks = new HashMap<>();
    /** 還沒套用成功的原艙門（以 chamber 為 key）：終局之後的一般開門，或進入 SEALED 時的關門。 */
    private final Map<UUID, PendingDoor> doors = new LinkedHashMap<>();
    private String tickWarning, tickWarningText;
    // 每個 tick 開頭取得；只在 server thread 使用。
    private SessionRecoveryState journal;
    private CorridorPageManager pages;
    private SuperpositionSessionManager sessions;
    private int now;

    private CollapseService(MinecraftServer server, Set<UUID> startupSessions, Set<UUID> startupPassages) {
        this.server = server;
        this.startupSessions = new LinkedHashSet<>(startupSessions);
        this.startupPassages = new LinkedHashSet<>(startupPassages);
    }

    private static Marker childMarker(String name, Marker parent) {
        var marker = MarkerFactory.getMarker(name);
        marker.add(parent);
        return marker;
    }

    /**
     * 註冊開服接手、每 tick 推進與停服釋放。{@code QuantumSuperpositionMod} 在 {@code SuperpositionSessionManager.initialize()} 與
     * {@code CollapseAuthorities.registerEvents()} 之後、{@code UniverseLifecycleService.initialize()} 之前呼叫（plan 決策 1）。Fabric 依註冊順序
     * 執行，所以 {@code SERVER_STARTED} 晚於 session／recovery 的 attach 與 M5 authority 初始化，每個 {@code END_SERVER_TICK} 晚於 session 與
     * recovery 的 tick：本 tick 的返還與清理寫入都已發生，這裡看到的是它們之後的 flushed。
     */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(CollapseService::onStarted);
        ServerTickEvents.END_SERVER_TICK.register(CollapseService::onEndTick);
        ServerLifecycleEvents.SERVER_STOPPED.register(INSTANCES::remove);
        // 每個 server instance 一個 resolver（F4a fix round 2，m-1）：停服時移除，重開世界拿到新的 resolver（物化額度重置）。
        ServerLifecycleEvents.SERVER_STOPPED.register(DestinationResolver::release);
    }

    private static void onStarted(MinecraftServer server) {
        Map<UUID, SessionRecoveryRecord> flushed;
        try {
            flushed = SessionRecoveryState.get(server).flushedRecords();
        } catch (RuntimeException unhealthy) {
            LOGGER.error(TICK_LOG_MARKER, "塌縮流程讀不到 journal，本 server instance 不推進任何塌縮", unhealthy);
            return;
        }
        var passages = new LinkedHashSet<UUID>();
        flushed.values().forEach(record -> { if (record.state() == SessionState.PASSAGE_READY) passages.add(record.sessionUuid()); });
        INSTANCES.put(server, new CollapseService(server, flushed.keySet(), passages));
    }

    private static void onEndTick(MinecraftServer server) {
        var service = INSTANCES.get(server);
        if (service != null) service.tick();
    }

    private void tick() {
        try {
            journal = SessionRecoveryState.get(server);
            pages = CorridorPageManager.forServer(server);
            if (!(ChamberSessions.gateway() instanceof SuperpositionSessionManager manager)) return;
            sessions = manager;
            now = server.getTicks();
            // 有未 checked 的殘留時（H3 的 recovery tick 會隔離並收斂），任何 journal 寫出都可能把它帶出去：本 tick 不寫入。
            if (journal.recoveryWritesSafe()) {
                for (var record : List.copyOf(journal.flushedRecords().values())) {
                    try {
                        step(record);
                        // 推進的去重鍵在成功時清除（同一原因再次發生時重新記 WARN）。
                        var track = tracks.get(record.sessionUuid());
                        if (track != null) track.warnings.remove(STEP);
                    } catch (RuntimeException failure) {
                        warn(record.sessionUuid(), STEP, failure, null, "下一個 tick 重試");
                    }
                }
                var known = journal.flushedRecords().keySet();
                tracks.keySet().retainAll(known);
                startupPassages.retainAll(known);
            }
            for (var door : List.copyOf(doors.values())) {
                try {
                    applyDoor(door);
                } catch (RuntimeException failure) {
                    applyFailure(door, failure, now);
                }
            }
            if (tickWarning != null) LOGGER.info(TICK_LOG_MARKER, "塌縮流程已恢復執行（先前原因：{}）", tickWarningText);
            tickWarning = null;
            tickWarningText = null;
        } catch (RuntimeException failure) {
            // 整個 tick 的失敗不屬於任何 session（沒有 session 可帶），所以帶 TICK_LOG_MARKER。
            var key = FailureText.dedupeKey(failure);
            if (!key.equals(tickWarning)) LOGGER.warn(TICK_LOG_MARKER, "塌縮流程本 tick 無法執行，下一 tick 重試：{}", FailureText.describe(failure), failure);
            tickWarning = key;
            tickWarningText = FailureText.describe(failure);
        }
    }

    /**
     * 一個 session 一個 tick 的推進。testmod 的 collapse-suspended hook（{@code CollapseSuspendMixin}）在這裡的開頭取消被暫停的 session：
     * 被暫停時這個 session 完全不推進（不提交、不 fallback、不終局）。M5 的塌縮狀態只依 collapse view 推導，走廊操作中或已隔離時略過。
     */
    private void step(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        if (pages.operationInProgress(sid) || journal.quarantinedSessions().contains(sid)) return;
        switch (record.state()) {
            case MEASURED -> measured(record);
            case COLLAPSING -> collapsing(record);
            case PASSAGE_READY -> { if (startupPassages.contains(sid)) endStartupPassage(record); }
            // SEALED 只處理重試（§11.2）；它的 roll-forward 寫出由 recovery 重試。其餘不是塌縮流程的狀態。
            case SEALED -> sealed(record);
            case ARMING, SUPERPOSITION, RETURNING -> { }
        }
    }

    // ---- MEASURED：提交、DORMANT 續行、§11.3 取消 ----

    private void measured(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        var current = record.equals(journal.records().get(sid));
        var phase = current ? MeasuredRecoveryPhase.from(record) : null;
        var track = track(sid);
        switch (measuredAction(current, current && unsupportedSource(record), phase == MeasuredRecoveryPhase.DORMANT,
                current && pages.measuredReleaseInProgress(sid), track.backoff.ready(now))) {
            case WAIT -> { }
            case CANCEL_UNSUPPORTED -> cancelUnsupported(record, phase, track);
            case RESUME_DORMANT -> resumeDormant(record, track);
            case COMMIT_LIVE -> commit(record, track, false);
        }
    }

    /** MEASURED 這一個 tick 的處置（{@link #measuredAction}）。 */
    enum MeasuredAction { WAIT, CANCEL_UNSUPPORTED, RESUME_DORMANT, COMMIT_LIVE }

    /**
     * MEASURED 的分流（spec §7、§7.1、§11.3），純函式。operation guard 忙碌、已隔離與 testmod 的 collapse-suspended hook 都在 {@link #step}
     * 開頭、呼叫這裡之前就略過。
     * <ol>
     * <li>寫入前 current 必須恰等於 flushed；M4 的返還進度尚未確認時（recovery 會重試）等待，不算失敗。</li>
     * <li>§11.3：來源不是 OVERWORLD、選中 NEW／EXISTING 的收據一律走取消路徑，不提交（DORMANT 與否由取消路徑自己分）。</li>
     * <li>DORMANT 走邊緣觸發（退避在觸發成立之後才看）。</li>
     * <li>live：M4 的 measured release 進行到一半時、或在退避期內，不提交。</li>
     * </ol>
     */
    static MeasuredAction measuredAction(boolean currentMatchesFlushed, boolean unsupportedSource, boolean dormant, boolean releaseInProgress,
            boolean backoffReady) {
        if (!currentMatchesFlushed) return MeasuredAction.WAIT;
        if (unsupportedSource) return MeasuredAction.CANCEL_UNSUPPORTED;
        if (dormant) return MeasuredAction.RESUME_DORMANT;
        if (releaseInProgress || !backoffReady) return MeasuredAction.WAIT;
        return MeasuredAction.COMMIT_LIVE;
    }

    /** §11.3：來源 role 不是 OVERWORLD，而且選中的是 NEW 或 EXISTING（SOURCE 照常完成）。 */
    static boolean unsupportedSource(SessionRecoveryRecord record) {
        return record.candidateContext().orElseThrow().sourceFamilyRef().role() != DimensionRole.OVERWORLD
                && record.selectedCandidate().filter(chosen -> chosen instanceof QuantumCandidate.New
                        || chosen instanceof QuantumCandidate.Existing).isPresent();
    }

    /** §7.2：SOURCE、EXISTING 的 UniverseId、NEW 的推導值（ledger 的 lookup 屬 RESOLVE，提交時不寫 ledger）。 */
    static CollapseDestination destination(SessionRecoveryRecord record) {
        return switch (record.selectedCandidate().orElseThrow()) {
            case QuantumCandidate.Source ignored -> new CollapseDestination.Source();
            case QuantumCandidate.Existing existing -> new CollapseDestination.Universe(existing.universeId());
            case QuantumCandidate.New created -> new CollapseDestination.Universe(
                    UniverseAllocationLedger.deriveUniverseId(created.intent().allocationToken()));
        };
    }

    private void cancelUnsupported(SessionRecoveryRecord record, MeasuredRecoveryPhase phase, Track track) {
        var sid = record.sessionUuid();
        if (phase != MeasuredRecoveryPhase.DORMANT) {
            // 不提交 COLLAPSING：先依 M4 的保留式 recovery 完成返還與幾何清理（重啟時 attach 已請求）。
            if (!pages.measuredRecoveryRequested(sid)) {
                pages.requestMeasuredRecovery(sid);
                LOGGER.info(LOG_MARKER, "不支援的來源維度：不提交 COLLAPSING，先以保留式 recovery 返還與清理，到 DORMANT 之後取消：session={} chamber={} 來源={}",
                        sid, record.chamberUuid(), record.origin().worldKey());
            }
            return;
        }
        // 一觀察到 flushed DORMANT（走廊 space 也已真的釋放）就直接終局，不必等參與者。
        if (!pages.measuredRecoveryComplete(sid) || !track.backoff.ready(now)) return;
        var result = journal.terminate(server, record, TerminalOrigin.UNSUPPORTED_SOURCE_CANCEL);
        if (!result.removed()) {
            terminalFailed(record, track, "取消不支援的來源維度", result);
            return;
        }
        terminated(record);
        LOGGER.info(LOG_MARKER, "不支援的來源維度，已安全取消（正常結束，不配置宇宙、不重抽）：session={} chamber={} 來源={} 候選={}",
                sid, record.chamberUuid(), record.origin().worldKey(), record.selectedCandidate().orElseThrow().getClass().getSimpleName());
        refresh(record);
    }

    private void resumeDormant(SessionRecoveryRecord record, Track track) {
        var sid = record.sessionUuid();
        // M4 的保留式清理剛完成時，record 可能先於走廊 space 變成 DORMANT：等 space 真的釋放。
        if (!pages.measuredRecoveryComplete(sid)) return;
        var entered = track.entry(startupSessions.contains(sid)).observe(inside(record));
        for (var player : entered) {
            if (!triggerReady(player)) continue;
            if (!chamberGate(record)) return;
            if (!track.backoff.ready(now)) {
                LOGGER.debug(LOG_MARKER, "DORMANT 續行在退避期內，這次觸發不提交：session={} 下一次可在 tick {} 之後", sid, track.backoff.retryAtTick());
                return;
            }
            commit(record, track, true);
            return;
        }
    }

    private void commit(SessionRecoveryRecord record, Track track, boolean dormant) {
        var sid = record.sessionUuid();
        var next = record.withCollapse(SessionState.COLLAPSING, CollapseFields.committed(server.getOverworld().getTime(), destination(record)));
        // spec §7：journal 的變更在這個 session 的 operation guard 之內。live（非 DORMANT）的 MEASURED 一定還有走廊 space：未 released 的
        // MEASURED 在 attach 時重建 space，執行期間的 space 要到 release 完成才移除；step 開頭已確認 guard 閒置。DORMANT 續行時 space 已釋放、
        // 沒有 guard 可取，維持「guard 忙就略過（step 開頭）＋同步寫入」。
        var result = dormant ? journal.commitRestore(server, record, next)
                : pages.exclusiveOperation(sid, () -> journal.commitRestore(server, record, next));
        if (!result.committed()) {
            warn(sid, "提交 MEASURED→COLLAPSING", result.failure().orElseThrow(), result.status().name(),
                    dormant ? "退避期過後、下一次觸發時重試" : "改走 M4 的保留式 recovery，之後依退避重試");
            track.backoff.failed(now);
            // §7.1：live 的提交失敗以 M4 的保留式 recovery 為 fallback（玩家返還，session 變成 DORMANT）；DORMANT 維持原狀。
            // requestMeasuredRecovery 要求沒有進行中的操作，所以在上面的 guard 釋放之後才呼叫。
            if (!dormant && !pages.measuredRecoveryRequested(sid)) pages.requestMeasuredRecovery(sid);
            return;
        }
        track.warnings.remove("提交 MEASURED→COLLAPSING");
        track.backoff.reset();
        track.resetGate();
        // DORMANT 續行沒有來源 ticket（live 的 runtime 入場時已取得，這裡冪等）。
        sideEffect(sid, ACQUIRE_TICKET, ACQUIRE_TICKET_NEXT, () -> sessions.acquireCollapseTicket(server, next));
        notifyParticipants(next, MESSAGE_COLLAPSED);
        LOGGER.info(LOG_MARKER, "塌縮已提交：session={} chamber={} 目的地={} 觸發={}", sid, next.chamberUuid(), describe(next.collapse().destination()),
                dormant ? "DORMANT 續行" : "選門之後");
    }

    // ---- COLLAPSING：推導階段 ----

    private void collapsing(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        // §7：roll-forward 尚未確認時只收斂（recovery 的 RETRY_FLUSH 只重試同一份 current），其他一律暫停、不疊寫。
        if (journal.rollForwardPending(sid)) return;
        var view = journal.collapseView(sid).orElse(record);
        if (!view.equals(journal.records().get(sid))) return;
        var track = track(sid);
        // 進入 SEALED 的寫入在前置檢查就被拒：只重試這一筆寫入，不重跑 RESOLVE（保留 RESOLVE 判定的原因，§8.4），而且依 per-session 退避，
        // 不是每個 tick 重試（F4a fix round 2，m-5(b)；§6 journal 寫入失敗時的 session 行為、§11.2）。
        if (track.pendingSeal != null) {
            if (track.sealRetryDue(now)) seal(view, track, track.pendingSeal, track.pendingSealCause);
            return;
        }
        boolean universe = view.collapse().destination() instanceof CollapseDestination.Universe;
        // UNIVERSE 的 PREPARE 屬 F4b：目的地確定之後停在 WAITING，不評估閘門（不消耗邊緣觸發）。
        var derived = CollapsePhase.derive(view, pages.measuredRecoveryComplete(sid), track.resolved, track.preparing,
                universe ? () -> false : () -> gate(view, track));
        track.preparing = derived.phase() == CollapsePhase.PREPARE;
        switch (derived.phase()) {
            // recovery 與走廊清理依收據推進（F3a）；這裡不重複驅動。
            case RETURN_PLAYERS, RELEASE_GEOMETRY -> { }
            case RESOLVE -> resolve(view, track);
            case WAITING -> { if (universe) prepareStub(view, track); }
            // 只有 SOURCE 會走到 PREPARE（UNIVERSE 的閘門在 F4b 之前恆為假）。
            case PREPARE -> sourceFastPath(view, track);
        }
    }

    /**
     * §8 的 RESOLVE：目的地已確定之後只記在本 instance 的簿記（重啟之後 idempotent 重新確認）；等待不 SEALED；失敗進 SEALED。resolver 每次都經
     * {@link DestinationResolver#forServer} 取得：每個 server instance 恰好一個，這裡不另外快取（F4a fix round 2，m-1）。
     */
    private void resolve(SessionRecoveryRecord view, Track track) {
        var sid = view.sessionUuid();
        var result = DestinationResolver.forServer(server).resolve(view);
        switch (result) {
            case ResolveResult.Wait wait -> waiting(view, track, wait.reason());
            case ResolveResult.Resolved resolved -> {
                track.resolved = true;
                track.endWait();
                track.backoff.reset();
                track.warnings.keySet().removeIf(what -> what.startsWith(SEALED_LOG));
                LOGGER.info(LOG_MARKER, "目的地已確定（RESOLVE 完成）：session={} chamber={} 目的地={}", sid, view.chamberUuid(),
                        describe(view.collapse().destination()));
            }
            case ResolveResult.Sealed sealed -> {
                track.endWait();
                seal(view, track, sealed.reason(), sealed.failure());
            }
        }
    }

    /** RESOLVE 的等待（spec §6.2）：不進 SEALED，下一個 tick 再試；連續等待超過 {@value #WAIT_WARN_TICKS} ticks 記一次 WARN。 */
    private void waiting(SessionRecoveryRecord view, Track track, String reason) {
        if (track.waitSince < 0) track.waitSince = now;
        if (!waitWarning(track.waitSince, now, track.waitWarned)) return;
        track.waitWarned = true;
        LOGGER.warn(LOG_MARKER, "目的地解析持續等待超過 {} ticks（不進 SEALED，繼續等待）：session={} chamber={} 原因：{}，自 tick {}", WAIT_WARN_TICKS,
                view.sessionUuid(), view.chamberUuid(), reason, track.waitSince);
    }

    /** 等待從 {@code since} 開始、到 {@code now} 已超過 {@value #WAIT_WARN_TICKS} ticks，而且這次等待還沒記過 WARN。 */
    static boolean waitWarning(long since, long now, boolean warned) {
        return !warned && since >= 0 && now - since > WAIT_WARN_TICKS;
    }

    /** UNIVERSE 的 PREPARE 屬 F4b：目的地確定之後停在 WAITING，每個 session 只記一次 WARN；不預載、不投影、不寫 projection ledger。 */
    private void prepareStub(SessionRecoveryRecord view, Track track) {
        if (track.prepareWarned) return;
        track.prepareWarned = true;
        LOGGER.warn(LOG_MARKER, "準備階段尚未實作（F4b）：目的地已確定，COLLAPSING 停在 WAITING，不預載、不投影、不寫 projection ledger：session={} chamber={} 目的地={}",
                view.sessionUuid(), view.chamberUuid(), describe(view.collapse().destination()));
    }

    /**
     * 進入 SEALED（§11.2）。journal 以 roll-forward 寫入 SEALED(reason)（current＝flushed 已在呼叫前確認）。寫出未確認時，依 collapse view
     * SEALED 照樣生效，重試交給 recovery 的 RETRY_FLUSH（F3d 的 A2）；寫入在前置檢查就被拒時（journal 沒有改變），依 per-session 退避只重試這筆
     * 寫入（{@link Track#sealRejected}）。之後依序：門改為關閉（門口有 entity 時改為一般開門，§7.3）→ 對參與者顯示訊息（§10.8）→ 刷新 Controller
     * （§10.7）→ 釋放來源 ticket（§5）→ 依原因記 WARN（可重試）或 ERROR（永久，需要管理員處理），依 (session, 原因, dedupeKey) 去重。
     */
    private void seal(SessionRecoveryRecord view, Track track, SealedReason reason, RuntimeException cause) {
        var sid = view.sessionUuid();
        var next = view.withCollapse(SessionState.SEALED, view.collapse().sealed(reason));
        var result = journal.commitRollForward(server, view, next);
        if (result.status() == SessionRecoveryState.CommitResult.Status.REJECTED) {
            track.sealRejected(reason, cause, now);
            warn(sid, SEAL_WRITE, result.failure().orElseThrow(), result.status().name(), track.sealRetryNext());
            return;
        }
        track.sealWritten();
        if (result.committed()) {
            track.warnings.remove(SEAL_WRITE);
        } else {
            warn(sid, SEAL_WRITE, result.failure().orElseThrow(), result.status().name(),
                    "SEALED 已依 collapse view 生效，寫出由 recovery 以 journal 為單位帶退避重試");
        }
        track.resolved = false;
        track.resetGate();
        // 封存時已在艙內的人不算觸發：重試要等原參與者再次由艙外進入（§11.2 的邊緣觸發）。
        track.entry = new EntryWatch(false);
        if (reason.retryable()) track.backoff.failed(now);
        if (reason.restartOnly()) track.restartRetryConsumed = true;
        queueDoor(view, 0, DoorGoal.CLOSE_ON_SEALED);
        notifyParticipants(view, MESSAGE_SEALED);
        refresh(view, "進入 SEALED 之後刷新 Controller");
        sideEffect(sid, "進入 SEALED 時釋放來源 ticket", "不重試；停服時一併釋放", () -> sessions.releaseOnSealed(server, sid));
        sealedLog(view, reason, cause);
    }

    /**
     * 進入 SEALED 的 log：可重試的原因 WARN、永久原因 ERROR「需要管理員處理」，依 (session, 原因, dedupeKey) 去重（與 {@link #warn} 共用
     * {@link #firstWarning}；F4b-1 Step 0，F4a fix round 2 quality Q2-m4）。
     */
    private void sealedLog(SessionRecoveryRecord view, SealedReason reason, RuntimeException cause) {
        var sid = view.sessionUuid();
        if (!firstWarning(track(sid).warnings, SEALED_LOG + reason, cause)) return;
        var destination = describe(view.collapse().destination());
        if (reason.retryable()) {
            LOGGER.warn(LOG_MARKER, "塌縮進入 SEALED({})，可重試（{}）：session={} chamber={} 目的地={}：{}", reason,
                    reason.restartOnly() ? "只在重啟之後重試，每個 server instance 一次" : "原參與者由艙外進入、而且在退避期之外時重試",
                    sid, view.chamberUuid(), destination, FailureText.describe(cause), cause);
        } else {
            LOGGER.error(LOG_MARKER, "塌縮進入 SEALED({})，永久原因，需要管理員處理（不自動重試；原艙維持封鎖，任何玩家都可以照一般方式開門）：session={} chamber={} 目的地={}：{}",
                    reason, sid, view.chamberUuid(), destination, FailureText.describe(cause), cause);
        }
    }

    // ---- SEALED：重試（§11.2） ----

    /** SEALED 這一個 tick 的重試方式（{@link #sealedRetry}）。 */
    enum SealedRetry {
        /** 永久原因、TRR，或 restart-only 的原因在本 instance 已重試過（或不是重啟之後）：不重試。 */
        NONE,
        /** 可重試的原因：邊緣觸發，而且在 per-session 退避期之外。 */
        EDGE_WITH_BACKOFF,
        /** {@code MATERIALIZE_FAILED}、{@code AUTHORITY_UNREADABLE}：只在重啟之後、每個 server instance 一次（開服之後的第一次觸發）。 */
        EDGE_ONCE_AFTER_RESTART
    }

    /**
     * spec §11.2 的分流，純函式。{@code startupSession}：開服 attach 當下就在 journal（重啟之後）；{@code restartRetryConsumed}：本 instance 已經以
     * restart-only 的原因重試過，或本 instance 自己以 restart-only 的原因封存了它。
     */
    static SealedRetry sealedRetry(SealedReason reason, boolean startupSession, boolean restartRetryConsumed) {
        if (!reason.retryable()) return SealedRetry.NONE;
        if (!reason.restartOnly()) return SealedRetry.EDGE_WITH_BACKOFF;
        return startupSession && !restartRetryConsumed ? SealedRetry.EDGE_ONCE_AFTER_RESTART : SealedRetry.NONE;
    }

    private void sealed(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        if (journal.rollForwardPending(sid)) return;
        var view = journal.collapseView(sid).orElse(record);
        if (view.state() != SessionState.SEALED || !view.equals(journal.records().get(sid))) return;
        var reason = view.collapse().sealedReason().orElseThrow();
        var track = track(sid);
        if (sealedRetry(reason, startupSessions.contains(sid), track.restartRetryConsumed) == SealedRetry.NONE) return;
        // §11.2：與 DORMANT 相同的邊緣觸發（§7.1）；開服 attach 當下就在 journal 的 session，第一次觀察到原參與者在艙內也算。
        var entered = track.entry(startupSessions.contains(sid)).observe(inside(view));
        for (var player : entered) {
            if (!triggerReady(player)) continue;
            if (!chamberGate(view)) return;
            if (!track.backoff.ready(now)) {
                LOGGER.debug(LOG_MARKER, "SEALED 的重試在退避期內，這次觸發不重試：session={} 下一次可在 tick {} 之後", sid, track.backoff.retryAtTick());
                return;
            }
            retrySealed(view, track, reason);
            return;
        }
    }

    /** checked SEALED→COLLAPSING（restore 類，§6），再取得來源 ticket；之後依推導階段重跑 RESOLVE（未確認的權威由 resolver 先收斂）。 */
    private void retrySealed(SessionRecoveryRecord view, Track track, SealedReason reason) {
        var sid = view.sessionUuid();
        var next = view.withCollapse(SessionState.COLLAPSING, view.collapse().retry());
        var result = journal.commitRestore(server, view, next);
        if (!result.committed()) {
            warn(sid, RETRY, result.failure().orElseThrow(), result.status().name(), "退避期過後、下一次觸發時重試");
            track.backoff.failed(now);
            return;
        }
        track.warnings.remove(RETRY);
        if (reason.restartOnly()) track.restartRetryConsumed = true;
        track.resolved = false;
        track.resetGate();
        sideEffect(sid, ACQUIRE_TICKET, ACQUIRE_TICKET_NEXT, () -> sessions.acquireCollapseTicket(server, next));
        LOGGER.info(LOG_MARKER, "SEALED 重試：SEALED({})→COLLAPSING，重新解析目的地：session={} chamber={} 目的地={}", reason, sid, view.chamberUuid(),
                describe(view.collapse().destination()));
    }

    /**
     * §7.3 的 PREPARE 閘門在這個 tick 是否成立（{@link CollapsePhase#derive} 的 {@code gate}；轉換由推導結果回傳，這裡不改 {@code track.preparing}，
     * F4b-2，F3d quality m-6(b)）：來源艙已載入、結構合法、已供電、Origin enabled，而且有原參與者在艙內。本 server instance 第一次看到這個 session
     * 走到閘門階段時立即評估（live 塌縮返還之後通常立即成立；開服之後就是「第一次觀察」）；不成立就停在 WAITING，之後只在 §7.1 的邊緣觸發
     * （原參與者由艙外進入艙內）時再評估。進入 PREPARE 之後參與者在艙內不是持續條件：上一個 tick 在 PREPARE 時只看來源艙的條件，不成立就退回 WAITING。
     */
    private boolean gate(SessionRecoveryRecord view, Track track) {
        if (track.preparing) return chamberGate(view);
        var insideNow = inside(view);
        List<UUID> candidates;
        if (!track.gateEvaluated) {
            track.gateEvaluated = true;
            track.entry(false).prime(insideNow);
            candidates = List.copyOf(insideNow);
        } else {
            candidates = track.entry(false).observe(insideNow);
        }
        return candidates.stream().anyMatch(this::triggerReady) && chamberGate(view);
    }

    /** §7.4：先 checked 終局、再把門改為一般開門、再顯示訊息。不配置宇宙、不跨世界傳送、不寫 discovery。 */
    private void sourceFastPath(SessionRecoveryRecord view, Track track) {
        if (!track.backoff.ready(now)) return;
        var result = journal.terminate(server, view, TerminalOrigin.SOURCE_FAST_PATH);
        if (!result.removed()) {
            terminalFailed(view, track, "SOURCE fast path 的終局", result);
            return;
        }
        terminated(view);
        LOGGER.info(LOG_MARKER, "SOURCE 塌縮完成（正常結束）：已 checked 終局，原艙門改為一般開門：session={} chamber={}", view.sessionUuid(), view.chamberUuid());
        openDoor(view, 0);
        notifyParticipants(view, MESSAGE_COLLAPSED);
        refresh(view);
    }

    // ---- PASSAGE_READY：開服時就是 READY 的 record（§12） ----

    private void endStartupPassage(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        if (journal.rollForwardPending(sid) || !record.equals(journal.records().get(sid))) return;
        var track = track(sid);
        if (!track.backoff.ready(now)) return;
        var result = journal.terminate(server, record, TerminalOrigin.PASSAGE_ENDED);
        if (!result.removed()) {
            terminalFailed(record, track, "開服時通道的終局", result);
            return;
        }
        terminated(record);
        LOGGER.info(LOG_MARKER, "開服時仍是 PASSAGE_READY 的量子通道已結束（spec §12，正常結束）：session={} chamber={}，門在下一個 tick 改為一般開門",
                sid, record.chamberUuid());
        openDoor(record, 1);
        refresh(record);
    }

    // ---- 共用 ----

    /** checked 終局讀回確認之後：釋放 runtime 與來源 ticket（spec §5），不再追蹤這個 session。 */
    private void terminated(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        tracks.remove(sid);
        startupPassages.remove(sid);
        sessions.releaseAfterTerminal(server, sid);
    }

    /** 終局沒有完成：依 (session, 寫入種類, 原因) 去重記 WARN；restore 類失敗之後以 session 為單位退避（§6、§11.2）。不改門。 */
    private void terminalFailed(SessionRecoveryRecord record, Track track, String what, TerminalResult result) {
        warn(record.sessionUuid(), what, result.failure().orElseThrow(), result.status().name(), "之後依退避重試");
        track.backoff.failed(now);
    }

    /** 原參與者中在線、存活、非旁觀、位於來源 world 而且 bbox 完整在來源艙 interior 內的人。 */
    private Set<UUID> inside(SessionRecoveryRecord record) {
        var result = new LinkedHashSet<UUID>();
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, record.origin().worldKey()));
        if (world == null) return result;
        var interior = ChamberGeometry.interiorBox(new ChamberFrame(record.origin().controllerPos(), record.origin().facing()));
        for (var person : record.participants()) {
            var player = server.getPlayerManager().getPlayer(person.playerUuid());
            if (player != null && !player.isRemoved() && player.isAlive() && !player.isSpectator() && player.getServerWorld() == world
                    && world.getEntity(player.getUuid()) == player && ChamberOccupantService.contains(interior, player.getBoundingBox()))
                result.add(person.playerUuid());
        }
        return result;
    }

    /** 觸發的參與者沒有被其他 session 佔用（spec §5 的唯一性規則；DORMANT 與已返還的塌縮參與者不佔用）。 */
    private boolean triggerReady(UUID player) {
        return journal.participantsAvailable(List.of(player));
    }

    /**
     * 來源艙已載入、結構合法、已供電、Origin enabled。一律依即時狀態：「已供電」是 Controller 的即時紅石輸入（spec §7），不看 registry 的
     * {@code ChamberPowerState}；「已載入」涵蓋艙體向水平各方向外擴 3 格（紅石讀取與門的套用範圍）的 chunk，只查詢、不載入。
     */
    private boolean chamberGate(SessionRecoveryRecord record) {
        var origin = record.origin();
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, origin.worldKey()));
        if (world == null) return false;
        var frame = new ChamberFrame(origin.controllerPos(), origin.facing());
        if (!neighborhoodLoaded(world, frame)) return false;
        var pos = origin.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller) || controller.isRemoved()
                || controller.instanceKind() != ChamberInstanceKind.ORIGIN || !origin.chamberUuid().equals(controller.chamberUuid())) return false;
        var block = chunk.getBlockState(pos);
        if (!block.isOf(ModBlocks.CHAMBER_CONTROLLER) || block.get(ChamberControllerBlock.FACING) != origin.facing()) return false;
        if (!new ChamberDetector().validate(new WorldChamberBlockView(world), frame).valid()) return false;
        if (!world.isReceivingRedstonePower(pos)) return false;
        var registry = ChamberRegistryState.get(server);
        if (registry.loadError().isPresent()) return false;
        return registry.registry().findOrigin(origin.worldKey(), origin.role(), frame)
                .filter(found -> found.chamberUuid().equals(origin.chamberUuid()) && !found.destroyed()
                        && found.instanceKind() == ChamberInstanceKind.ORIGIN && found.enabled()).isPresent();
    }

    private static boolean neighborhoodLoaded(ServerWorld world, ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        for (int x = (bounds.getMinX() - 3) >> 4; x <= (bounds.getMaxX() + 3) >> 4; x++)
            for (int z = (bounds.getMinZ() - 3) >> 4; z <= (bounds.getMaxZ() + 3) >> 4; z++)
                if (world.getChunkManager().getWorldChunk(x, z) == null) return false;
        return true;
    }

    private void notifyParticipants(SessionRecoveryRecord record, String key) {
        for (var person : record.participants()) {
            var player = server.getPlayerManager().getPlayer(person.playerUuid());
            if (player != null && !player.isRemoved()) player.sendMessage(Text.translatable(key), true);
        }
    }

    /** 讓比較器與 registry 的 power state 立即反映終局（presence 回到 NONE）；Controller 所在 chunk 沒載入時由之後的載入協調處理。 */
    private void refresh(SessionRecoveryRecord record) {
        refresh(record, "終局之後刷新 Controller");
    }

    /** 刷新來源艙的 Controller（§10.7）；{@code what} 是失敗時 WARN 的寫入種類（成功時清掉它的去重鍵，{@link #sideEffect}）。 */
    private void refresh(SessionRecoveryRecord record, String what) {
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, record.origin().worldKey()));
        if (world == null) return;
        sideEffect(record.sessionUuid(), what, "不重試，Controller 在下次載入或紅石更新時協調",
                () -> new ChamberPowerCoordinator().refresh(world, record.origin().controllerPos()));
    }

    /** 終局之後把原艙門改為一般開門（open=true、membrane=none），經 {@link ChamberDoorService#applyTarget}；{@code delay} 個 tick 之後才第一次嘗試。 */
    private void openDoor(SessionRecoveryRecord record, int delay) {
        queueDoor(record, delay, DoorGoal.OPEN_AFTER_TERMINAL);
    }

    /** 排入待套用的原艙門（以 chamber 為 key，取代同一座艙之前排入的門）；{@code delay} 為 0 時立即嘗試一次。 */
    private void queueDoor(SessionRecoveryRecord record, int delay, DoorGoal goal) {
        var origin = record.origin();
        var door = new PendingDoor(record.chamberUuid(), record.sessionUuid(), RegistryKey.of(RegistryKeys.WORLD, origin.worldKey()),
                new ChamberFrame(origin.controllerPos(), origin.facing()), now + delay, goal);
        doors.put(record.chamberUuid(), door);
        if (delay == 0) {
            try {
                applyDoor(door);
            } catch (RuntimeException failure) {
                applyFailure(door, failure, now);
            }
        }
    }

    /**
     * 依 {@link ChamberDoorService.ApplyResult.Outcome} 處理：APPLIED／UNCHANGED 完成；NOT_LOADED 等之後的 tick（不強制載入）；DOOR_INCOMPLETE
     * 是結構性的，記一次並退避、等結構恢復；ROLLED_BACK／ROLLBACK_FAILED 下一個 tick 重試一次，再失敗才退避（ROLLBACK_FAILED 已設
     * activationBlocked 並記 ERROR，之後一次 APPLIED 會解除）。
     * <ul>
     * <li>終局之後的一般開門：原艙已開始新的 session（presence 不是 NONE）時，門屬於新的 session，不再套用。</li>
     * <li>進入 SEALED 的關門（§11.2）：這個 session 的 collapse view 已不是 SEALED（重試回到 COLLAPSING，或已終局）時不再套用。每次嘗試都重新
     *     檢查門口：25 格門內有任何 entity 的 bbox 時改為一般開門（§7.3），不把門關在任何人身上。</li>
     * </ul>
     */
    private void applyDoor(PendingDoor door) {
        if (now < door.notBefore || !door.backoff.ready(now)) return;
        if (door.goal == DoorGoal.OPEN_AFTER_TERMINAL && sessions.presence(server, door.chamber) != ChamberSessionGateway.Presence.NONE) {
            doors.remove(door.chamber);
            LOGGER.info(LOG_MARKER, "原艙已開始新的 session，不再把門改為一般開門：chamber={} 先前 session={}", door.chamber, door.session);
            return;
        }
        if (door.goal == DoorGoal.CLOSE_ON_SEALED
                && journal.collapseView(door.session).filter(view -> view.state() == SessionState.SEALED).isEmpty()) {
            doors.remove(door.chamber);
            LOGGER.info(LOG_MARKER, "session 已不是 SEALED，不再套用封存時的原艙門：chamber={} session={}", door.chamber, door.session);
            return;
        }
        var world = server.getWorld(door.world);
        if (world == null) return;
        var target = door.goal == DoorGoal.CLOSE_ON_SEALED ? sealedDoor(doorwayOccupied(world, door.frame)) : ChamberDoorService.DoorState.OPEN;
        var result = new ChamberDoorService().applyTarget(world, door.frame, target);
        if (applyOutcome(door, result.outcome(), now)) doors.remove(door.chamber);
    }

    /** §11.2、§7.3：進入 SEALED 時門改為關閉；門口有 entity 時改為一般開門（不關閉）。 */
    static ChamberDoorService.DoorState sealedDoor(boolean doorwayOccupied) {
        return doorwayOccupied ? ChamberDoorService.DoorState.OPEN : ChamberDoorService.DoorState.CLOSED;
    }

    /** 25 格門（區域 x、y 1..5，z 0）的範圍內有任何 entity 的 bbox（§7.3「門口有人」）；只看已載入的 entity。 */
    static boolean doorwayOccupied(ServerWorld world, ChamberFrame frame) {
        return !world.getOtherEntities(null, doorwayBox(frame)).isEmpty();
    }

    /** 25 格門的外框（整格）。 */
    static Box doorwayBox(ChamberFrame frame) {
        var a = ChamberGeometry.localToWorld(frame, 1, 1, 0);
        var b = ChamberGeometry.localToWorld(frame, 5, 5, 0);
        return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()) + 1, Math.max(a.getY(), b.getY()) + 1, Math.max(a.getZ(), b.getZ()) + 1);
    }

    /**
     * {@link #applyDoor} 對一次 {@code applyTarget} 結果的處置（F4a-0 fix round 1：{@code applyDoor} 與 JUnit 共用同一份接線）：依門自己記下的狀態
     * （{@code retried}、{@code warned}）推導 {@link #doorStep}。完成時，之前記過 WARN 的話記一則 INFO，回傳 true（呼叫端移除這扇門）；沒有完成時
     * 經 {@link #recordDoorStep} 記錄、退避，回傳 false。
     */
    static boolean applyOutcome(PendingDoor door, ChamberDoorService.ApplyResult.Outcome outcome, int now) {
        var step = doorStep(outcome, door.retried, door.warned);
        if (step.done()) {
            if (door.warned != null) LOGGER.info(LOG_MARKER, "{}（先前：{}）：chamber={}", door.goal.done, door.warned, door.chamber);
            return true;
        }
        recordDoorStep(door, step, null, now);
        return false;
    }

    /** 套用門時丟出例外（{@link #tick} 與 {@link #openDoor} 的 catch 共用）：依門記下的狀態推導 {@link #doorFailure}，經 {@link #recordDoorStep} 記錄並退避。 */
    static void applyFailure(PendingDoor door, RuntimeException failure, int now) {
        recordDoorStep(door, doorFailure(FailureText.describe(failure), door.retried, door.warned), failure, now);
    }

    /**
     * 一次沒有完成的套用（或套用丟出的例外）的處置：只在 {@code step.warn()} 為真時記 WARN（去重由 {@link #doorStep} 依 door 記下的上一個原因
     * 判定）；原因與「已立即重試過一次」交給 door 記下；{@code step.backOff()} 時退避。
     */
    private static void recordDoorStep(PendingDoor door, DoorStep step, Throwable failure, int now) {
        if (step.warn()) LOGGER.warn(LOG_MARKER, "{}，之後重試：chamber={} session={}：{}", door.goal.pending, door.chamber, door.session,
                step.reason(), failure);
        door.remember(step.reason(), step.retried());
        if (step.backOff()) door.backoff.failed(now);
    }

    /**
     * 待套用門對一次 {@code applyTarget} 結果的處置（純函式，{@link #applyOutcome} 經 {@link #recordDoorStep} 照做）。{@code done}：完成並移除；
     * {@code reason}：要記下的原因（null 表示這次沒有原因）；{@code warn}：這次是否記 WARN（原因與上一次記下的不同才記，{@link #newWarning}）；
     * {@code backOff}：是否進入退避；{@code retried}：之後的「已立即重試過一次」狀態。
     */
    record DoorStep(boolean done, String reason, boolean warn, boolean backOff, boolean retried) { }

    static DoorStep doorStep(ChamberDoorService.ApplyResult.Outcome outcome, boolean retried, String warned) {
        return switch (outcome) {
            case APPLIED, UNCHANGED -> new DoorStep(true, null, false, false, retried);
            // 不強制載入：等之後的 tick。
            case NOT_LOADED -> new DoorStep(false, null, false, false, retried);
            // 結構性的：記一次並退避，等結構恢復。
            case DOOR_INCOMPLETE -> pendingStep("門板不是完整的 25 格 bulkhead，等結構恢復", warned, true, retried);
            // 下一個 tick 立即重試一次；之後再被拒才退避。
            case ROLLED_BACK, ROLLBACK_FAILED -> pendingStep("門的寫入被拒（" + outcome + "）", warned, retried, true);
        };
    }

    /** 套用門時丟出例外：記下「套用失敗：…」（同一原因去重）並退避；「已立即重試過一次」不變。 */
    static DoorStep doorFailure(String described, boolean retried, String warned) {
        return pendingStep("套用失敗：" + described, warned, true, retried);
    }

    private static DoorStep pendingStep(String reason, String warned, boolean backOff, boolean retried) {
        return new DoorStep(false, reason, newWarning(warned, reason), backOff, retried);
    }

    /** 待套用門的 WARN 去重：原因與上一次記錄的不同才記。 */
    static boolean newWarning(String previous, String reason) {
        return !reason.equals(previous);
    }

    /**
     * 依 (session, 寫入種類, {@link FailureText#dedupeKey}) 去重記 WARN；{@code next} 是之後實際會發生的事（依退避重試、下一個 tick 重試、
     * 或不重試由誰接手），只寫真的會發生的。鍵什麼時候移除，決定同一原因「失敗 → 成功 → 再失敗」會不會再記一次：
     * <ul>
     * <li>journal 寫入：由呼叫端在這裡自己的寫入確認（committed／removed）時移除。進入 SEALED 的寫入回 {@code ROLL_FORWARD_PENDING} 之後，確認它的是
     *     recovery 的 RETRY_FLUSH，不是這裡，所以 {@code 寫入 SEALED} 的鍵不會在確認時移除：同一個 session 之後再以同一原因 pending，不會再記 WARN
     *     （F4b-1 Step 0，F4a fix round 2 spec S2-mC 縮小原本「成功時移除」的說法）。</li>
     * <li>不重試的附帶動作（取得或釋放來源 ticket、刷新 Controller）一律經 {@link #sideEffect}，成功時自動移除（F4a fix round 2，m-6）。</li>
     * </ul>
     */
    private void warn(UUID sid, String what, RuntimeException failure, String status, String next) {
        if (!firstWarning(track(sid).warnings, what, failure)) return;
        logWarn(sid, what, failure, status, next);
    }

    /** {@link #warn} 與 {@link #sideEffect} 共用的 WARN 字句（不去重）。 */
    private static void logWarn(UUID sid, String what, RuntimeException failure, String status, String next) {
        LOGGER.warn(LOG_MARKER, "塌縮流程的{}沒有完成{}，{}：session={}：{}", what, status == null ? "" : "（" + status + "）", next, sid,
                FailureText.describe(failure), failure);
    }

    /** {@link #warn} 的去重（純函式）：記下 {@code what} 這次的 {@link FailureText#dedupeKey}，與上一次記下的不同才回 true（要記 WARN）。 */
    static boolean firstWarning(Map<String, String> warnings, String what, RuntimeException failure) {
        var key = FailureText.dedupeKey(failure);
        return !key.equals(warnings.put(what, key));
    }

    /**
     * {@link #sideEffect(Map, Supplier, UUID, String, String, Runnable)} 的第一步（唯一的呼叫端）：執行 {@code action}，成功時從 {@code warnings} 移除
     * {@code what} 的去重鍵（{@code warnings} 為 null 表示這個 session 沒有簿記，例如已終局）並回 null；失敗時回傳例外、不動 {@code warnings}，由組合以
     * {@link #firstWarning} 去重並記 WARN。JUnit 經由組合驗證（F4b-2 Step 0，F4b-1 quality Q-m7：原本的「由呼叫端交給 {@code warn}、JUnit 直接驗證」已過時）。
     */
    private static RuntimeException attempt(Map<String, String> warnings, String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            return failure;
        }
        if (warnings != null) warnings.remove(what);
        return null;
    }

    /** 不重試的附帶動作：經 {@link #sideEffect(Map, Supplier, UUID, String, String, Runnable)}；這裡只接上這個 session 的簿記（沒有時為 null）。 */
    private void sideEffect(UUID sid, String what, String next, Runnable action) {
        var track = tracks.get(sid);
        sideEffect(track == null ? null : track.warnings, () -> track(sid).warnings, sid, what, next, action);
    }

    /**
     * 不重試的附帶動作的組合（F4b-1 Step 0，F4a fix round 2 quality Q2-m3：production 與 JUnit 經同一份）：{@link #attempt} 執行 {@code action}
     * （成功時從 {@code warnings} 移除 {@code what} 的去重鍵；{@code warnings} 為 null 表示這個 session 沒有簿記，例如已終局）；失敗時以
     * {@link #firstWarning} 對 {@code ledger} 提供的簿記去重（沒有簿記時由它建立，與 {@link #warn} 相同），第一次遇到這個原因才記 WARN
     * （{@code next} 是之後實際會發生的事）。回傳這次是否記了 WARN。
     */
    static boolean sideEffect(Map<String, String> warnings, Supplier<Map<String, String>> ledger, UUID sid, String what, String next,
            Runnable action) {
        var failure = attempt(warnings, what, action);
        if (failure == null || !firstWarning(ledger.get(), what, failure)) return false;
        logWarn(sid, what, failure, null, next);
        return true;
    }

    private Track track(UUID sid) {
        return tracks.computeIfAbsent(sid, ignored -> new Track());
    }

    private static String describe(CollapseDestination destination) {
        return destination instanceof CollapseDestination.Universe universe ? "UNIVERSE " + universe.id().value() : "SOURCE";
    }

    /** 每個 session 在本 server instance 的塌縮簿記（不持久化）。package-private 只為了 JUnit 直接驗證它的方法（F4a fix round 2）。 */
    static final class Track {
        /**
         * 提交失敗、restore 類寫入（終局、SEALED 的重試）失敗、進入 SEALED 的寫入在前置檢查就被拒（{@link #sealRejected}）、以可重試的原因進入 SEALED
         * 之後的 per-session 退避（§7.1、§11.2；被拒的一項由 F4b-1 Step 0 補進說明，F4a fix round 2 quality Q2-m4）；RESOLVE 完成時清除。
         */
        final CollapseBackoff backoff = new CollapseBackoff();
        /** WARN 的去重：寫入種類 → 最近一次的 {@link FailureText#dedupeKey}；成功時移除。 */
        final Map<String, String> warnings = new HashMap<>();
        EntryWatch entry;
        boolean gateEvaluated, preparing;
        /** UNIVERSE 目的地在本 instance 已確定（RESOLVE 完成）；不持久化，重啟之後重新確認（§7.3）。 */
        boolean resolved;
        /**
         * 本 instance 已用掉 restart-only 原因的那一次 SEALED→COLLAPSING 重試，或本 instance 自己以 restart-only 的原因封存了它（§11.2「每個 server
         * instance 一次」）。只管重試的觸發；物化本身每個 instance 一次由 resolver 依 UniverseId 把關（F4a fix round 1）。
         */
        boolean restartRetryConsumed;
        /** UNIVERSE 的 PREPARE stub 已記過 WARN。 */
        boolean prepareWarned;
        /** RESOLVE 連續等待的起點（-1 表示不在等待）與這次等待是否已記過 WARN。 */
        long waitSince = -1;
        boolean waitWarned;
        /** 進入 SEALED 的寫入在前置檢查就被拒：退避期過後只重試這筆寫入（{@link #sealRejected}）。 */
        SealedReason pendingSeal;
        RuntimeException pendingSealCause;

        /**
         * 進入 SEALED 的寫入在前置檢查就被拒（REJECTED，journal 沒有改變；F4a fix round 2，m-5(b)）：記下 RESOLVE 判定的原因與 root cause（§8.4：
         * RESOLVE 的失敗進 SEALED、保留同一個 Destination），並記一次 per-session 退避。record 留在上一份已確認的狀態，與 restore 類寫入失敗相同，
         * 套用 §11.2 的 per-session 記憶體內退避（spec §6「journal 寫入失敗時的 session 行為」、§11.2 最後一點），不是每個 tick 重試。
         */
        void sealRejected(SealedReason reason, RuntimeException cause, int now) {
            pendingSeal = Objects.requireNonNull(reason, "reason");
            pendingSealCause = Objects.requireNonNull(cause, "cause");
            backoff.failed(now);
        }

        /** 有被拒的 SEALED 寫入，而且在 per-session 退避期之外：這個 tick 重試它（不重跑 RESOLVE）。 */
        boolean sealRetryDue(int now) {
            return pendingSeal != null && backoff.ready(now);
        }

        /**
         * 被拒的 SEALED 寫入在 WARN 裡的「之後會發生的事」：以實際的 per-session 退避寫出延遲與下一次重試的 tick（F4b-1 Step 0，F4a fix round 2 quality
         * Q2-m4：原本固定寫「200 ticks 起、每次被拒加倍」，與加倍之後的實際延遲不一致）。在 {@link #sealRejected} 之後呼叫。
         */
        String sealRetryNext() {
            return "依 per-session 退避（" + backoff.delayTicks() + " ticks 後，tick " + backoff.retryAtTick() + "）只重試這筆 SEALED 寫入（不重跑 RESOLVE）";
        }

        /** 進入 SEALED 的寫入沒有被拒（已確認，或 roll-forward 未確認、交給 recovery）：不再有待重試的寫入。 */
        void sealWritten() {
            pendingSeal = null;
            pendingSealCause = null;
        }

        EntryWatch entry(boolean armedAtStartup) {
            if (entry == null) entry = new EntryWatch(armedAtStartup);
            return entry;
        }

        void resetGate() {
            entry = null;
            gateEvaluated = false;
            preparing = false;
        }

        void endWait() {
            waitSince = -1;
            waitWarned = false;
        }
    }

    /** 待套用門的目標：終局之後的一般開門，或進入 SEALED 時的關門（門口有 entity 時一般開門）。{@code pending}／{@code done} 是 log 的字句。 */
    enum DoorGoal {
        OPEN_AFTER_TERMINAL("終局之後原艙門還沒改為一般開門", "原艙門已改為一般開門"),
        CLOSE_ON_SEALED("進入 SEALED 之後原艙門還沒套用（關門；門口有 entity 時一般開門）", "進入 SEALED 之後的原艙門已套用");

        final String pending, done;

        DoorGoal(String pending, String done) { this.pending = pending; this.done = done; }
    }

    /**
     * §7.1 的邊緣觸發：原參與者由艙外進入艙內。只比較相鄰兩次觀察，不是位準觸發。第一次觀察時，開服 attach 當下就在 journal 的 session
     * （{@code armedAtStartup}）把已在艙內的人當成觸發（「開服之後第一次觀察到原參與者在艙內」）；其他 session 只記下目前的狀態。
     */
    static final class EntryWatch {
        private final boolean armedAtStartup;
        private Set<UUID> inside;

        EntryWatch(boolean armedAtStartup) { this.armedAtStartup = armedAtStartup; }

        /** 回傳這次新進入艙內的人（依參與者順序）。 */
        List<UUID> observe(Set<UUID> insideNow) {
            Objects.requireNonNull(insideNow, "insideNow");
            List<UUID> entered = inside == null
                    ? (armedAtStartup ? List.copyOf(insideNow) : List.of())
                    : insideNow.stream().filter(id -> !inside.contains(id)).toList();
            inside = Set.copyOf(insideNow);
            return entered;
        }

        /** 只記下目前的狀態，不觸發（閘門第一次以位準評估之後）。 */
        void prime(Set<UUID> insideNow) { inside = Set.copyOf(insideNow); }
    }

    /**
     * 還沒套用成功的原艙門（{@link DoorGoal}）。只記下狀態（最近一次記下的原因、是否已立即重試過一次、退避）；要不要記 WARN 由 {@link #recordDoorStep}
     * 依 {@link DoorStep#warn} 決定。
     */
    static final class PendingDoor {
        final UUID chamber, session;
        final RegistryKey<World> world;
        final ChamberFrame frame;
        final int notBefore;
        final DoorGoal goal;
        final CollapseBackoff backoff = new CollapseBackoff();
        String warned;
        boolean retried;

        /** 終局之後的一般開門。 */
        PendingDoor(UUID chamber, UUID session, RegistryKey<World> world, ChamberFrame frame, int notBefore) {
            this(chamber, session, world, frame, notBefore, DoorGoal.OPEN_AFTER_TERMINAL);
        }

        PendingDoor(UUID chamber, UUID session, RegistryKey<World> world, ChamberFrame frame, int notBefore, DoorGoal goal) {
            this.chamber = chamber; this.session = session; this.world = world; this.frame = frame; this.notBefore = notBefore;
            this.goal = Objects.requireNonNull(goal, "goal");
        }

        /** 記下這次的原因（沒有原因時保留上一個）與之後的「已立即重試過一次」狀態。 */
        void remember(String reason, boolean retried) {
            if (reason != null) warned = reason;
            this.retried = retried;
        }
    }
}
