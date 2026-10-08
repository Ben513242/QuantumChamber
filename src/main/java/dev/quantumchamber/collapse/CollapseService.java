package dev.quantumchamber.collapse;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.candidate.UniverseDiscoveryRecord;
import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberControllerBlock;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDetector;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOccupantService;
import dev.quantumchamber.chamber.ChamberPowerCoordinator;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.chamber.WorldChamberBlockView;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
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
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseLifecycleService;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
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
 * <li>WAITING／PREPARE（§7.3）：第一次走到閘門階段時立即評估閘門，之後只在邊緣觸發時再評估；推導回傳 PREPARE 的轉換（{@link CollapsePhase.Transition}）。
 *     SOURCE 進入 PREPARE 即走 §7.4 的 fast path：先 checked 終局、再把門改為一般開門、再顯示訊息；終局之後釋放 runtime 與來源 ticket（spec §5）。
 *     UNIVERSE 的 PREPARE（F4b-2，目的宇宙這一側，§9、§6.5）：預載目的世界（M5 的 chunk ticket，非建立模式查詢）→ 退化時進 SEALED → projection ledger
 *     （§6.5 的實體交叉檢查、既有 entry 的 site 驗證）→ §9.2 的衝突 → checked PENDING → 寫投影（第一次寫入或修復）→ 原生存檔 checkpoint → 再驗不變量 cell
 *     → checked COMPLETE（修復寫了任何一格，或之前的段落寫過的方塊還沒有 checkpoint 時，也先做 checkpoint，F5-1 的 S-M2 與 fix round 1 的 Q-m1）→ 準備完成 → §10.2 的前置條件都成立時 checked 提交 PASSAGE_READY（restore 類，
 *     {@link #ready}；F5-1）。閘門在 PREPARE 進行到一半時不再成立就釋放目的世界的 ticket、退回 WAITING。目的世界的 ticket 在閘門失效、進入 SEALED、終局時釋放，
 *     PASSAGE_READY 期間繼續持有，通道結束時隨終局釋放。</li>
 * <li>PASSAGE_READY（§10.2–§10.6，F5-1）：提交之後門改為 passable 量子膜、刷新 Controller、對艙內的參與者顯示「量子通道已開啟」；之後每個 tick 由 {@link PassageService}
 *     判斷跨越、以 passage 的傳送政策傳送、checked 加入 Crossed、處理兩種傳送失敗，並依結束條件 (a)–(d) 結束通道。</li>
 * <li>PREPARE 期間的原艙門（F4b-3，§7 開頭、§7.3；F5-0 起在 {@link OriginDoors}）：每個 PREPARE 的 tick 依「套用量子膜的條件」（門口沒有 entity、艙內沒有非參與者玩家）在 sealed 量子膜與
 *     一般開門之間選一個（{@link OriginDoors#prepareDoor}，門口有人時絕不套用有碰撞的門）；離開或暫停 PREPARE（閘門失效、SEALED 的寫入被拒、隔離、提交 PASSAGE_READY 失敗之後的
 *     退避期）時門改回一般開門。
 *     玩家開關原艙門先經 {@code ChamberCollapsePort} 交給這裡（{@link #useDoor} → {@link OriginDoors#door}，排在 {@code ChamberDoorService} 之前）：PREPARE 期間參與者開關門 →
 *     checked 終局、再關門（門口有 entity 時一般開門）；艙內的非參與者 → 一般開門、塌縮不結束；艙外的非參與者與 roll-forward 尚未確認時 → 拒絕、不改門。
 *     其他階段不攔截。重啟或來源 chunk 重新載入之後，門依 collapse view 排入下一個 tick 重新套用（{@link OriginDoors#watchDoor}）。</li>
 * <li>§11.3：來源 role 不是 OVERWORLD、選中 NEW／EXISTING 的 MEASURED 不提交，先以保留式 recovery 到 DORMANT，再 checked 終局取消（INFO）。</li>
 * <li>§12：開服時就是 PASSAGE_READY 的 record 直接 checked 終局（{@code PASSAGE_ENDED}），門在下一個 tick 改為一般開門。</li>
 * </ul>
 * 註冊順序見 {@link #initialize()}；每個 session 的處理都在 {@link #step} 裡個別隔離例外，例外不會從 {@code END_SERVER_TICK} 拋出。
 * 終局與提交失敗的 WARN 由這裡依 (session, 寫入種類, {@link FailureText#dedupeKey}) 去重，成功時清除；正常結束（SOURCE fast path、§11.3 的取消、
 * 開服時通道的結束、PREPARE 期間參與者關門、通道結束 (a)–(d)、跨越）一律記 INFO。PASSAGE_READY 期間的門（§10.5）也經同一個 {@link #useDoor}（{@link OriginDoors#doorAction}
 * 的 PASSAGE_READY 分支 → {@link PassageService#endByParticipant}）。
 * 這裡的每一則 log 都帶 {@link #LOG_MARKER}（不屬於任何 session 的帶子 marker {@link #TICK_LOG_MARKER}）。
 */
public final class CollapseService {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    /**
     * 塌縮流程每一則 log 的 SLF4J marker：測試依它篩選塌縮流程的 WARN／ERROR，不依訊息的字串。能歸屬到 session 的 WARN／ERROR 都在訊息中帶
     * {@code session=<UUID>}；不屬於任何 session 的（開服讀不到 journal、整個 tick 無法執行與它的恢復、原艙門的攔截讀不到 journal 或在找出所屬 session 之前失敗）
     * 改帶子 marker {@link #TICK_LOG_MARKER}。
     * logger 名稱、等級與訊息都不變；console 與 latest.log 的格式不含 marker。
     */
    public static final Marker LOG_MARKER = MarkerFactory.getMarker("QUANTUMCHAMBER_COLLAPSE");
    /** 不屬於任何 session 的塌縮流程 log（沒有 session 可帶）；父 marker 是 {@link #LOG_MARKER}。 */
    public static final Marker TICK_LOG_MARKER = childMarker("QUANTUMCHAMBER_COLLAPSE_TICK", LOG_MARKER);
    /** {@link #warn} 的寫入種類：每個 session 每個 tick 的推進（{@link #step}）丟出例外。 */
    private static final String STEP = "推進";
    /** spec §10.8「塌縮」：提交 COLLAPSING 時，以及 SOURCE fast path 打開原艙門之後（§7.4 第 3 步）。 */
    public static final String MESSAGE_COLLAPSED = "message.quantumchamber.m5.collapsed";
    /** spec §10.8「進入 SEALED」：執行期間進入 SEALED 時對參與者顯示（F4a；F5-1 起也含 PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)）。 */
    public static final String MESSAGE_SEALED = "message.quantumchamber.m5.sealed";
    /** spec §10.8「通道開啟」：提交 PASSAGE_READY 之後對在艙內的參與者顯示（§10.2 第 3 步，F5-1）。 */
    public static final String MESSAGE_PASSAGE_OPENED = "message.quantumchamber.m5.passage_opened";
    /** spec §10.8「傳送失敗」：通道傳送沒有完成、玩家仍在原世界時對那位玩家顯示（§10.3，F5-1）。 */
    public static final String MESSAGE_PASSAGE_TRANSFER_FAILED = "message.quantumchamber.m5.passage_transfer_failed";
    /** spec §10.8「通道結束」：通道結束 (a)–(d) 時只對在線、而且不在 Crossed 中的參與者顯示（F5-1；對象依 v5.5 的 §20.9，F5-3；判斷見 {@link PassageService}）。 */
    public static final String MESSAGE_PASSAGE_CLOSED = "message.quantumchamber.m5.passage_closed";
    // 到達目的宇宙的 title（spec §10.8、§20.8、§20.9，F5-3）只由 PassageService 送出，key 在那裡：PassageService.MESSAGE_ARRIVED_NEW／MESSAGE_ARRIVED_EXISTING。
    /** spec §6.2：RESOLVE 的等待（catalog 或 discovery 有未確認的寫入）持續超過這個 tick 數時記一次 WARN。 */
    static final int WAIT_WARN_TICKS = 600;
    /** {@link #warn} 的寫入種類：進入 SEALED 的 journal 寫入、SEALED→COLLAPSING 的重試。 */
    private static final String SEAL_WRITE = "寫入 SEALED", RETRY = "重試 SEALED→COLLAPSING";
    /** {@link #warn} 的寫入種類：提交 PASSAGE_READY（restore 類，F5-1）。 */
    private static final String READY_WRITE = "提交 PASSAGE_READY";
    /** {@link #sideEffect} 的種類：提交 COLLAPSING 與 SEALED→COLLAPSING 重試之後取得來源 ticket（兩處共用同一個去重鍵），與失敗之後實際會發生的事。 */
    private static final String ACQUIRE_TICKET = "取得來源 ticket", ACQUIRE_TICKET_NEXT = "本 server instance 不重試，開服 attach 時會為 COLLAPSING 重新取得";
    /** 進入 SEALED 的 WARN／ERROR 的去重鍵前綴（後接原因）；RESOLVE 成功時清除。 */
    private static final String SEALED_LOG = "進入 SEALED ";
    /**
     * spec §9.1：M5 在目的世界的預載 chunk ticket（比照 {@code quantumchamber_source}／{@code quantumchamber_corridor}）。argument 是 session UUID，
     * radius 0（level FULL）：只讓預載範圍的 chunk 到達 FULL，不讓它們 tick。
     */
    private static final ChunkTicketType<UUID> DESTINATION_TICKET = ChunkTicketType.create("quantumchamber_destination", Comparator.comparing(UUID::toString));
    private static final Map<MinecraftServer, CollapseService> INSTANCES = new ConcurrentHashMap<>();
    private static boolean initialized;

    // server、startupSessions、tracks 與下方 context 中的 journal、sessions、now 是 package-private：同一個 server instance 的 OriginDoors 直接讀寫（F5-0，行為不變），
    // F5-1 起 PassageService 也讀 server、journal、now；startupPassages、originDoors、passages 也是 package-private（理由見各自的 Javadoc）。context 的 pages 仍是 private
    // （兩者都不用它）。
    final MinecraftServer server;
    /**
     * 開服 attach 當下已在 journal 的 session：DORMANT 與 SEALED 重試的「開服之後第一次觀察到原參與者在艙內」只對它們成立（§7.1、§11.2）；原艙門的重新套用也用它
     * （{@link OriginDoors#doorReloaded} 的「重啟之後第一次觀察」與 {@link OriginDoors#reapplyCause}，§7.3，F4b-3）。
     */
    final Set<UUID> startupSessions;
    /**
     * 開服時就是 PASSAGE_READY 的 session（§12）：第一個 tick 直接終局，不交給 {@link PassageService}（執行期間提交的 PASSAGE_READY 不在這裡）。package-private：
     * {@link OriginDoors#watchDoor} 不為它們重新套用 passable 量子膜（F5-1）。
     */
    final Set<UUID> startupPassages;
    final Map<UUID, Track> tracks = new HashMap<>();
    /**
     * 這個 server instance 的原艙門（F4b-3 的攔截、PREPARE 期間的門、重新套用與待套用的門；F5-0 從這裡抽出，行為不變）。package-private：{@link PassageService}
     * 經它排入通道結束與進入 SEALED 之後的門（F5-1）。
     */
    final OriginDoors originDoors = new OriginDoors(this);
    /** 這個 server instance 的通道（PASSAGE_READY 的跨越、傳送與結束，F5-1）；與 {@link OriginDoors} 一樣經這裡共用每個 tick 的 context。 */
    final PassageService passages = new PassageService(this);
    /**
     * 每個 session 在目的世界持有的預載 ticket（§9.1，以 session 為 key，不持久化）。在 PREPARE 期間結束（閘門失效）、進入 SEALED、終局時釋放
     * （{@link #releaseDestination(UUID)}）；PASSAGE_READY 期間繼續持有，通道結束 (a)–(d) 是 checked 終局，由 {@link #terminated} 釋放（F5-1）。終局不論由誰執行，
     * 下一個 tick 看到 session 已不在 journal 時都會釋放。
     */
    private final Map<UUID, DestinationTicket> destinations = new HashMap<>();
    private String tickWarning, tickWarningText;
    // 每個 tick 開頭（以及原艙門的攔截開頭）由 refreshContext 取得；只在 server thread 使用。
    SessionRecoveryState journal;
    private CorridorPageManager pages;
    SuperpositionSessionManager sessions;
    int now;

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
        // F4b-3：原艙門的攔截（spec §7、§7.3）經 chamber 定義的 port 交給這裡，排在 ChamberDoorService 之前（chamber 不 import collapse）。
        ChamberCollapsePort.installDoors(CollapseService::useDoor);
        // F5-2：找不到所屬 session 的量子膜（spec §10.6 :613），ChamberPowerCoordinator 刷新原艙時經同一個 port 交給這裡排入下一個 tick。
        ChamberCollapsePort.installMembranes(CollapseService::normalizeOwnerless);
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

    /** 取得這一次要用的 journal、走廊、session manager 與 server tick；session gateway 不是 {@code SuperpositionSessionManager} 時回 false（不推進）。 */
    boolean refreshContext() {
        journal = SessionRecoveryState.get(server);
        pages = CorridorPageManager.forServer(server);
        if (!(ChamberSessions.gateway() instanceof SuperpositionSessionManager manager)) return false;
        sessions = manager;
        now = server.getTicks();
        return true;
    }

    private void tick() {
        try {
            if (!refreshContext()) return;
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
                releaseTerminatedDestinations();
            }
            originDoors.applyPending();
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
        if (pages.operationInProgress(sid)) return;
        boolean quarantined = journal.quarantinedSessions().contains(sid);
        // F4b-3：原艙門的重新套用（spec §7.3）與暫停中的 PREPARE 的門，在隔離中也要照做（隔離中不推導階段）。
        if (record.state().collapseState()) originDoors.watchDoor(record, quarantined);
        // 隔離中（H3 的收斂）不推進，也不釋放目的世界的 ticket（F4b-2 fix round 1，quality Q-M4 的判斷）：隔離是暫時的，解除之後依原進度續做；準備完成之後的
        // tick 不會重新加 ticket，釋放了就要另外補回。原艙門則在 watchDoor 交還為一般開門（F4b-3 補充第 4 點）。
        if (quarantined) return;
        switch (record.state()) {
            case MEASURED -> measured(record);
            case COLLAPSING -> collapsing(record);
            // §12：開服時就是 PASSAGE_READY 的直接終局；執行期間提交的由通道處理（F5-1）。
            case PASSAGE_READY -> { if (startupPassages.contains(sid)) endStartupPassage(record); else passages.tick(record); }
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
        var derived = CollapsePhase.derive(view, pages.measuredRecoveryComplete(sid), track.resolved, track.preparing, new PrepareGate(view, track));
        track.preparing = derived.phase() == CollapsePhase.PREPARE;
        // 轉換點（F4b-2，F3d quality m-6(b)）：UNIVERSE 進入 PREPARE 時開始這一段準備的簿記；離開時（閘門失效、或推導回到 RESOLVE）釋放目的世界的
        // ticket、退回 WAITING。SOURCE 的 PREPARE 在同一個 tick 就以 fast path 終局，沒有目的世界。
        if (universe) switch (derived.transition()) {
            case ENTER_PREPARE -> enterPrepare(view, track);
            case LEAVE_PREPARE -> leavePrepare(view, track, derived.phase());
            case NONE -> { }
        }
        switch (derived.phase()) {
            // recovery 與走廊清理依收據推進（F3a）；這裡不重複驅動。
            case RETURN_PLAYERS, RELEASE_GEOMETRY -> { }
            case RESOLVE -> resolve(view, track);
            // WAITING：等 §7.1 的邊緣觸發（gate 在下一個 tick 再看）。
            case WAITING -> { }
            case PREPARE -> { if (universe) prepareUniverse(view, track); else sourceFastPath(view, track); }
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

    // ---- UNIVERSE 的 PREPARE：目的宇宙這一側（F4b-2；spec §7.3、§9） ----

    /** 進入 PREPARE（閘門剛成立，§7.3）：開始這一段準備的簿記；之後每個 tick 依 collapse view 推導下一步（不另存進度）。 */
    private void enterPrepare(SessionRecoveryRecord view, Track track) {
        track.prepare = new Prepare(now);
        LOGGER.info(LOG_MARKER, "進入 PREPARE（閘門成立）：預載目的世界並寫入投影：session={} chamber={} 目的地={}", view.sessionUuid(), view.chamberUuid(),
                describe(view.collapse().destination()));
    }

    /**
     * 離開 PREPARE（§7.3「PREPARE 進行到一半時閘門不再成立」）：釋放目的世界的 ticket，退回 WAITING。已完成的 RESOLVE（{@code track.resolved}）與已寫入的
     * projection ledger 都保留；這一段準備的記憶體內進度丟棄，閘門再次成立時依 §7.1 的邊緣觸發，從 collapse view 與權威重新推導（PENDING 照第一次寫入、
     * COMPLETE 只修復）。PREPARE 期間閘門不看邊緣、不更新邊緣觸發的快照，所以這裡以目前在艙內的原參與者重新記下快照（F4b-2 fix round 1，quality Q-M2）：
     * PREPARE 期間進艙的人不算之後的邊緣，沒有人移動時不會回到 PREPARE。目的世界不是 ACTIVE 時（不應發生）推導回到 RESOLVE，也經過這裡；RESOLVE 重新確認之後
     * 與閘門失效相同，依邊緣觸發續做（F4b-2 fix round 1，quality Q-M3）。原艙門由 sealed 量子膜改回 {@code open=true}＋{@code membrane=none} 的一般開門（§7.3 閘門失效的
     * 第 2 步，所以不會有人被困住；F4b-3 補充第 3 點），經 {@link OriginDoors#releasePrepareDoor}。
     */
    private void leavePrepare(SessionRecoveryRecord view, Track track, CollapsePhase phase) {
        track.prepare = null;
        track.entry(false).prime(inside(view));
        boolean released = releaseDestination(view.sessionUuid());
        originDoors.releasePrepareDoor(view, track);
        LOGGER.info(LOG_MARKER, "{}（已寫入的投影都保留{}；原艙門改為一般開門）：session={} chamber={}",
                phase == CollapsePhase.WAITING ? "PREPARE 的閘門不再成立，退回 WAITING，已完成的 RESOLVE 保留" : "PREPARE 的目的地需要重新確認，回到 " + phase,
                released ? "；已釋放目的世界的 ticket" : "", view.sessionUuid(), view.chamberUuid());
    }

    /**
     * PREPARE 的一個 tick（UNIVERSE，F4b-2）。每個 tick 依序推導、可以在任一步中斷，重啟之後依同一份推導續做，不另存進度（spec §9、§6.5，F4b-1 交下的呼叫順序）：
     * <ol>
     * <li>預載（§9.1）：對目的世界 {@link #preloadChunks} 的每個 chunk（與傳送時的 FULL 檢查同一個推導，F5-1 S-M1）加上 M5 的 chunk ticket，之後以非建立模式查詢，不在
     *     server thread 上阻塞載入。</li>
     * <li>退化（§9.3）：投影保護索引已退化時（projection ledger 不健康），以它的原因進 SEALED。這一步不需要 chunk，所以在預載完成之前就檢查。</li>
     * <li>預載還沒全部 FULL：等下一個 tick。之後的步驟見 {@link #project}。</li>
     * </ol>
     * 等待（預載、玩家擋住寫入、checkpoint 未就緒、PASSAGE_READY 的前置條件）從進入 PREPARE 起超過 {@value #WAIT_WARN_TICKS} ticks 時記一次 WARN，然後繼續等
     * （§9.1：沒有劇情上的逾時、不重抽）。準備完成（{@link #prepared}）之後的 tick 不再預載或投影，只判斷原艙門（見下一段），並在 §10.2 的前置條件都成立時提交
     * PASSAGE_READY（{@link #ready}，F5-1）；目的世界的 ticket 繼續持有。
     * <p>每個 PREPARE 的 tick 先判斷原艙門（{@link OriginDoors#prepareDoor}：「套用量子膜的條件」，§7.3；F4b-3），再看準備是否已完成：判斷插在
     * {@code if (progress.ready)} 之前（F4b-2 fix round 1，quality Q-M4），所以準備完成之後的 tick 也每個 tick 重新判斷門。提交 PASSAGE_READY 的寫入沒有完成時，PREPARE
     * 在 per-session 退避期內暫停（{@link Track#readyPaused}）：不判斷門（門已改為一般開門，§6 :260），原艙門的開關照一般門的邏輯（{@link OriginDoors#doorAction} 的
     * {@code prepareOwnsDoor} 為假）。退避期過後恢復（F5-1 fix round 1，spec review S-m2、quality Q-m2）：門照每個 PREPARE tick 的條件判斷（§7.3），並重試提交；重試的結果
     * 不是提交失敗時（等待、SEALED）照正常的 PREPARE 處理，再次失敗時再暫停一段退避期。
     */
    private void prepareUniverse(SessionRecoveryRecord view, Track track) {
        // 推導回傳 PREPARE 時，進入的那個 tick 已由 enterPrepare 建立 Prepare，之後一直保留到離開（Track#preparing 的不變式）。
        var progress = Objects.requireNonNull(track.prepare, "PREPARE 的簿記應已由 enterPrepare 建立");
        if (!track.readyPaused(now)) {
            if (track.readyRetryPending) {
                track.readyRetryPending = false;
                LOGGER.info(LOG_MARKER, "提交 PASSAGE_READY 失敗之後的退避期已過，PREPARE 恢復：原艙門依套用量子膜的條件判斷，並重試提交：session={} chamber={}",
                        view.sessionUuid(), view.chamberUuid());
            }
            originDoors.prepareDoor(view, track, progress);
        }
        var id = ((CollapseDestination.Universe) view.collapse().destination()).id();
        var world = UniverseLifecycleService.resolveActive(server, id).orElse(null);
        if (world == null) {
            // RESOLVE 已確認 ACTIVE world，動態世界在執行期間不卸載；不成立時 fail closed：下一個 tick 推導回到 RESOLVE（離開 PREPARE、釋放 ticket），
            // 由它 idempotent 地重新確認（不換宇宙）。之後與閘門失效相同，依 §7.1 的邊緣觸發續做（F4b-2 fix round 1，quality Q-M3：§7.3「閘門再次成立時，
            // 依 §7.1 的邊緣觸發續做」；不改成自動續做，見 note）。
            track.resolved = false;
            LOGGER.warn(LOG_MARKER, "PREPARE 的目的世界不是 ACTIVE（不應發生），回到 RESOLVE 重新確認；之後與閘門失效相同，依原參與者由艙外進入的邊緣觸發續做："
                    + "session={} chamber={} 目的地={}", view.sessionUuid(), view.chamberUuid(), describe(view.collapse().destination()));
            return;
        }
        if (progress.ready) {
            ready(view, track, progress, world, id);
            return;
        }
        var frame = new ChamberFrame(view.origin().controllerPos(), view.origin().facing());
        boolean preloaded = preload(view.sessionUuid(), world, frame);
        var index = ProjectionProtectionIndex.get(server);
        var degraded = index.degradation();
        if (degraded.isPresent()) {
            seal(view, track, degraded.get(), new IllegalStateException("投影保護索引已退化（projection ledger 不健康：" + degraded.get()
                    + "），還沒完成準備的 PREPARE 進 SEALED（spec §9.3）"));
            return;
        }
        if (!preloaded) {
            prepareWait(view, progress, "預載目的世界（chunk 還沒全部 FULL）");
            return;
        }
        if (progress.preloadedAt < 0) {
            progress.preloadedAt = now;
            LOGGER.info(LOG_MARKER, "目的世界預載完成：session={} chamber={} 目的地={}，{} 個 chunk，等了 {} ticks", view.sessionUuid(), view.chamberUuid(),
                    describe(view.collapse().destination()), destinations.get(view.sessionUuid()).chunks().size(), now - progress.since);
        }
        project(view, track, progress, world, frame, id, index);
    }

    /**
     * 預載完成之後的投影（spec §6.5、§9.2；F4b-1 交下的呼叫順序），每個 tick 從權威重新推導：
     * <ol>
     * <li>projection ledger：未確認的寫入先收斂（以權威為單位退避）；仍未確認時等下一個 tick（可能是別的 session 的寫入：只重試收斂、不做新決策，也不進
     *     SEALED；F4b-2 fix round 1，quality Q-M1），損壞或讀不到時以結果帶的原因進 SEALED。沒有這個 (chamber, UniverseId) 的 entry 時做 §6.5 的實體交叉檢查：
     *     目的地的投影 Controller 位置已有 Controller 就是
     *     {@code PROJECTION_CONFLICT}，不寫 PENDING、不清 interior。已有 entry（PENDING 或 COMPLETE）時不做這個檢查。</li>
     * <li>§9.2 的衝突：目的 volume 與另一座艙的 COMPLETE 投影相交 → {@code PROJECTION_CONFLICT}，不覆寫。</li>
     * <li>PENDING：一律以 {@code writeProjection(PENDING)} 取得 site 驗證（plan Step 1 第 4 組；不直接拿查到的 COMPLETE 跳過驗證）：新 key 是 checked 寫入，
     *     既有 PENDING 是 {@code UNCHANGED}（照第一次寫入），既有 COMPLETE 是 {@code ALREADY_COMPLETE}（只修復）；site 不符是 {@code AUTHORITY_CORRUPT}。
     *     寫入沒有確認（含讀回失敗）就進 SEALED，不寫任何方塊（§6 收斂規則第 2 點）。確認之後以寫入結果登記到投影保護索引（只供辨識）。</li>
     * <li>寫方塊（{@link ProjectionWriter}）：第一次寫入（PENDING）或修復（COMPLETE）。玩家擋住、volume 的 chunk 不是都 FULL：下一個 tick 再試；
     *     驗證或寫入失敗：{@code PROJECTION_FAILED}，以結果帶的 failure 當 root cause。修復一格都沒有寫，而且這個 session 之前寫過的方塊都已有 checkpoint，就是準備完成
     *     （方塊沒有改變，不寫 ledger、不做 checkpoint）；修復寫了任何一格時，與第一次寫入一樣要先做下面的 checkpoint 才算準備完成（F5-1，F4b-2 spec S-M2），只是不寫 COMPLETE
     *     （ledger 已是 COMPLETE）。「之前寫過的方塊還沒有 checkpoint」跨 PREPARE 的段落記在 {@link Track#projectionUncheckpointed}（F5-1 fix round 1，quality Q-m1：上一段修復了
     *     方塊、checkpoint 之前閘門失效時，下一段的修復寫 0 格也要先做 checkpoint）。</li>
     * <li>寫了方塊之後：目的 volume 的 chunks 先問 {@link ChunkSaveCheckpoint#ready}（未就緒等下一個 tick）。</li>
     * <li>checkpoint 之前，以修復模式再驗一次不變量 cell（F4b-1 交下的未保護窗口：PENDING 期間不變量 cell 不受保護，第一次寫入之後可能被改動）。修復了任何一格，
     *     或被擋住（玩家站在要修復的格上、chunk 不是 FULL）：不做 checkpoint，下一個 tick 再驗（F4b-2 fix round 1，quality Q-I1：不變量 cell 一直被改動時不會每個
     *     tick 都存檔；這是等待，併入 §9.1 的 600 ticks WARN，「已修復」的 INFO 每一段 PREPARE 只記一次）。</li>
     * <li>再驗乾淨才做原生存檔 checkpoint（失敗 {@code PROJECTION_FAILED}）。</li>
     * <li>checkpoint 之後、寫 COMPLETE 之前，以修復模式再驗一次：這次修復寫了任何一格，checkpoint 就沒有涵蓋它，不寫 COMPLETE，下一個 tick 從 checkpoint 之前的
     *     再驗重來。同一個 tick 內，checkpoint、這次驗證與 COMPLETE 的寫入之間沒有任何遊戲邏輯執行，所以驗證通過時寫下的 COMPLETE 對應的就是 checkpoint 存下的
     *     方塊；只有 checkpoint 本身改動方塊時這次驗證才會修復（GameTest 以 checkpoint 探針模擬）。</li>
     * <li>第一次寫入：checked 寫入 COMPLETE（沒有確認就進 SEALED），確認之後登記到索引（不變量 cell 從這時起受保護）。之後（修復則直接）準備完成。</li>
     * </ol>
     */
    private void project(SessionRecoveryRecord view, Track track, Prepare progress, ServerWorld world, ChamberFrame frame, UniverseId id,
            ProjectionProtectionIndex index) {
        var authorities = CollapseAuthorities.get(server);
        authorities.convergeIfNeeded(CollapseAuthorities.Authority.PROJECTION_LEDGER);
        var known = authorities.projection(view.chamberUuid(), id);
        if (known.status() == CollapseAuthorities.Lookup.Status.UNCONFIRMED) {
            // F4b-2 fix round 1（quality Q-M1，controller 裁定）：未確認的寫入可能是別的 session 的；收斂之前只重試收斂、不做新決策（spec §6 收斂規則第 2 點），
            // 也不進 SEALED。只有本 session 自己的 checked 寫入失敗才進 SEALED（下面的 PENDING 與 COMPLETE）。
            prepareWait(view, progress, "projection ledger 有尚未確認的寫入（收斂之前不做新決策）");
            return;
        }
        if (!known.succeeded()) {
            seal(view, track, known.sealedReason().orElseThrow(), known.failure().orElseThrow());
            return;
        }
        if (known.status() == CollapseAuthorities.Lookup.Status.ABSENT) {
            switch (ProjectionWriter.controllerPresence(world, frame)) {
                case PRESENT -> {
                    seal(view, track, SealedReason.PROJECTION_CONFLICT, new IllegalStateException("projection ledger 沒有這座艙在這個宇宙的 entry，目的地的投影 Controller 位置 "
                            + frame.controllerPos().toShortString() + " 卻已有 Controller（spec §6.5 的實體交叉檢查）：不寫 PENDING、不清 interior"));
                    return;
                }
                case NOT_LOADED -> {
                    prepareWait(view, progress, "投影 Controller 位置的 chunk 不是 FULL");
                    return;
                }
                case ABSENT -> { }
            }
        }
        var conflict = conflict(index.intersecting(world.getRegistryKey(), ChamberGeometry.bounds(frame)), view.chamberUuid(), id);
        if (conflict.isPresent()) {
            seal(view, track, SealedReason.PROJECTION_CONFLICT, new IllegalStateException("目的 volume 已有另一座艙的 COMPLETE 投影（spec §9.2，不覆寫）："
                    + conflict.get()));
            return;
        }
        var pending = authorities.writeProjection(new ProjectionLedger.Entry(view.chamberUuid(), id, DimensionRole.OVERWORLD, frame.controllerPos(),
                frame.outwardFacing(), ProjectionLedger.Entry.Status.PENDING));
        if (!pending.succeeded()) {
            seal(view, track, pending.sealedReason().orElseThrow(), pending.failure().orElseThrow());
            return;
        }
        var entry = pending.entry().orElseThrow();
        if (!entry.equals(progress.registered)) {
            index.confirmed(pending);
            progress.registered = entry;
        }
        var mode = writeMode(pending.status());
        if (!progress.written) {
            var first = ProjectionWriter.write(world, frame, mode);
            if (!proceeds(view, track, progress, first)) return;
            if (mode == ProjectionWriter.Mode.REPAIR && first.written() == 0) {
                if (!track.projectionUncheckpointed) {
                    // 修復一格都沒有寫，而且這個 session 寫過的方塊都已有 checkpoint：方塊與已完成的投影相同，不需要 checkpoint（F4b-2「偏離」第 2 條）。
                    prepared(view, track, progress, mode, 0, world, id);
                    return;
                }
                // F5-1 fix round 1（quality Q-m1）：上一段 PREPARE 寫過的方塊（例如修復之後、checkpoint 之前閘門失效）還沒有 checkpoint 涵蓋：這次修復一格都沒有寫，READY
                // 之前仍走下面同一條 checkpoint 的流程（每段 PREPARE 只會到這裡一次）。
                LOGGER.info(LOG_MARKER, "這次修復一格都沒有寫，但這個 session 之前寫過的投影方塊還沒有原生存檔 checkpoint（例如上一段 PREPARE 在 checkpoint 之前中斷）："
                        + "READY 之前仍先做 checkpoint：session={} chamber={}", view.sessionUuid(), view.chamberUuid());
            }
            // 寫了方塊（第一次寫入，或修復寫了任何一格），或之前寫過的方塊還沒有 checkpoint：READY 之前要有原生存檔 checkpoint（F5-1，F4b-2 spec S-M2：修復之後、存檔之前
            // crash 時，修復會遺失而 READY 已提交）。checkpoint 之前與之後的再驗、只在再驗乾淨時存檔，都與第一次寫入相同（不會每個 tick 存檔，F4b-2 Q-I1）。
            progress.written = true;
            progress.writtenCells = first.written();
            track.projectionUncheckpointed = true;
        }
        var chunks = volumeChunks(frame);
        if (!ChunkSaveCheckpoint.ready(world, chunks)) {
            prepareWait(view, progress, "目的 chunks 的原生存檔 checkpoint 未就緒（chunk 或 entity storage 尚未完整載入）");
            return;
        }
        // checkpoint 之前先再驗（F4b-2 fix round 1，quality Q-I1）：修復了任何一格、或被擋住，都不存檔、下一個 tick 再驗，所以不變量 cell 一直被改動
        // （或有人站在要修復的格上）時不會每個 tick 都做一次 checkpoint（ChunkSaveCheckpoint 的呼叫端契約）。
        var recheck = ProjectionWriter.write(world, frame, ProjectionWriter.Mode.REPAIR);
        if (!proceeds(view, track, progress, recheck)) return;
        if (recheck.written() > 0) {
            repairedBeforeComplete(view, progress, recheck.written());
            return;
        }
        try {
            ChunkSaveCheckpoint.save(server, Map.of(world, chunks));
        } catch (RuntimeException failure) {
            seal(view, track, SealedReason.PROJECTION_FAILED, failure);
            return;
        }
        var verify = ProjectionWriter.write(world, frame, ProjectionWriter.Mode.REPAIR);
        if (!proceeds(view, track, progress, verify)) return;
        if (verify.written() > 0) {
            repairedBeforeComplete(view, progress, verify.written());
            return;
        }
        // checkpoint 存下的就是目前的方塊（之後的再驗沒有改動）：這個 session 寫過的方塊都已有 checkpoint（F5-1 fix round 1，Q-m1）。
        track.projectionUncheckpointed = false;
        if (mode == ProjectionWriter.Mode.FIRST_WRITE) {
            var complete = authorities.writeProjection(entry.withStatus(ProjectionLedger.Entry.Status.COMPLETE));
            if (!complete.succeeded()) {
                seal(view, track, complete.sealedReason().orElseThrow(), complete.failure().orElseThrow());
                return;
            }
            index.confirmed(complete);
            progress.registered = complete.entry().orElseThrow();
        }
        prepared(view, track, progress, mode, progress.writtenCells, world, id);
    }

    /**
     * 第一次寫入之後、寫 COMPLETE 之前的再驗修復了 {@code written} 格（F4b-2 fix round 1，quality Q-I1）：不寫 COMPLETE，下一個 tick 再驗，乾淨才做
     * checkpoint。INFO 這一段 PREPARE 只記一次（{@link Prepare#repairLogged}）；這也是等待，併入 §9.1 的 {@value #WAIT_WARN_TICKS} ticks WARN。
     */
    private void repairedBeforeComplete(SessionRecoveryRecord view, Prepare progress, int written) {
        if (!progress.repairLogged) {
            progress.repairLogged = true;
            LOGGER.info(LOG_MARKER, "投影的不變量 cell 在寫入之後被改動，已修復 {} 格；要等再驗沒有改動才做 checkpoint，之後才寫 COMPLETE（修復時不寫）、準備完成"
                    + "（這一段 PREPARE 只記一次）：session={} chamber={}", written, view.sessionUuid(), view.chamberUuid());
        }
        prepareWait(view, progress, "投影的不變量 cell 在第一次寫入之後一直被改動（每次再驗都要修復，checkpoint 要等再驗沒有改動才做）");
    }

    /**
     * 準備完成（預載與投影都已完成）：記一則 INFO，同一個 tick 就嘗試提交 PASSAGE_READY（{@link #ready}，F5-1；取代 F4b-2 的停點與「通道尚未實作（F5）」的 WARN）。
     * 之後的 PREPARE tick 不再預載或投影，只判斷原艙門（{@link OriginDoors#prepareDoor}，F4b-3），並在前置條件不成立時重試提交。目的世界的 ticket 繼續持有。
     */
    private void prepared(SessionRecoveryRecord view, Track track, Prepare progress, ProjectionWriter.Mode mode, int written, ServerWorld world, UniverseId id) {
        progress.ready = true;
        LOGGER.info(LOG_MARKER, "準備完成（預載與投影都已完成）：session={} chamber={} 目的地={} 投影={}（寫入 {} 格），PREPARE 共 {} ticks", view.sessionUuid(),
                view.chamberUuid(), describe(view.collapse().destination()), mode, written, now - progress.since);
        ready(view, track, progress, world, id);
    }

    /**
     * spec §10.2：準備完成之後，前置條件全部成立時 checked 提交 PASSAGE_READY（restore 類，§6；opened＝現在的 game time，expires＝opened＋1200）。每個 PREPARE 的 tick
     * （準備完成之後）重新判斷，不成立時依原因：
     * <ul>
     * <li><b>等待</b>（不進 SEALED、不退回 WAITING，併入 §9.1 的 600 ticks WARN）：預載的 chunk 不是都 FULL；相關權威（{@link #readyAuthorities}）有尚未確認的寫入
     *     （spec §6 收斂共通第 2 點：收斂之前不做新決策；先 {@code convergeIfNeeded}）；EXISTING 的重驗回 WAIT（catalog 或 discovery 未確認、lifecycle 沒有 ready 的
     *     context）。</li>
     * <li><b>SEALED</b>（§8.4：準備的失敗進 SEALED、保留同一個 Destination）：EXISTING 再次通過 §8.1 的重驗失敗（{@code DestinationResolver.resolve} 的 EXISTING 分支只讀
     *     不寫：不符為 {@code EXISTING_INELIGIBLE}，§8.1、§11.2 的永久原因；權威讀不到或損壞時是它的原因）；投影的 COMPLETE entry 不在、或 NEW 的 discovery record 不在或
     *     不是 gameplay-eligible（已確認的寫入不見了，§6 的「確定遺失」：{@code AUTHORITY_CORRUPT}）；查詢失敗時用權威帶的原因。</li>
     * <li><b>退回 WAITING</b>：來源艙已載入、結構合法、已供電、enabled 是 PREPARE 的閘門（§7.3）。這個 tick 的推導已以它確認（{@link PrepareGate#stillOpen}），不成立時推導
     *     本身就離開 PREPARE（{@link #leavePrepare}），不會走到這裡。目的世界不是 ACTIVE 由 {@link #prepareUniverse} 在這之前處理（回到 RESOLVE）。</li>
     * </ul>
     * 提交之後依序：門改為 passable 量子膜（{@link OriginDoors#openPassage}；passable 沒有碰撞，§7.3「門口有人時的規則」只適用於會讓門變成有碰撞的轉換，所以不看門口；
     * 艙內有非參與者時也照樣提交）→ 刷新 Controller（§10.7）→ 對在艙內的參與者顯示「量子通道已開啟」（§10.2 第 3 步）。PREPARE 的簿記丟棄，之後由 {@link PassageService}
     * 處理。提交沒有完成時依 §6「journal 寫入失敗時的 session 行為」（:260）：record 留在上一份已確認的 COLLAPSING，per-session 退避（§11.2），退避期內 PREPARE 暫停、原艙門
     * 依 §7.3 閘門失效的處理改為一般開門（sealed 量子膜有碰撞，不能在重試期間把人困在艙內），開關門照一般門的邏輯；退避期過後 PREPARE 恢復（門照條件判斷）並重試這筆提交
     * （{@link Track#readyPaused}，F5-1 fix round 1，S-m2／Q-m2）。
     */
    private void ready(SessionRecoveryRecord view, Track track, Prepare progress, ServerWorld world, UniverseId id) {
        var sid = view.sessionUuid();
        if (!track.backoff.ready(now)) return;
        var frame = new ChamberFrame(view.origin().controllerPos(), view.origin().facing());
        if (!preload(sid, world, frame)) {
            prepareWait(view, progress, "PASSAGE_READY 的前置條件：預載目的世界（chunk 還沒全部 FULL）");
            return;
        }
        var authorities = CollapseAuthorities.get(server);
        boolean created = view.selectedCandidate().orElseThrow() instanceof QuantumCandidate.New;
        for (var authority : readyAuthorities(created)) {
            authorities.convergeIfNeeded(authority);
            if (authorities.unconfirmed(authority)) {
                prepareWait(view, progress, "PASSAGE_READY 的前置條件：" + authority + " 有尚未確認的寫入（收斂之前不提交）");
                return;
            }
        }
        var projection = authorities.projection(view.chamberUuid(), id);
        if (!projection.succeeded()) {
            seal(view, track, projection.sealedReason().orElseThrow(), projection.failure().orElseThrow());
            return;
        }
        if (projection.value().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE).isEmpty()) {
            seal(view, track, SealedReason.AUTHORITY_CORRUPT, new IllegalStateException("準備完成之後，projection ledger 沒有這座艙在這個宇宙的 COMPLETE entry"
                    + "（已確認的寫入不見了，spec §6）：" + projection.status()));
            return;
        }
        if (created) {
            var discovered = authorities.discovery(id);
            if (discovered.status() == CollapseAuthorities.Lookup.Status.WAIT) {
                prepareWait(view, progress, "PASSAGE_READY 的前置條件：discovery 有尚未確認的寫入（收斂之前不提交）");
                return;
            }
            if (!discovered.succeeded()) {
                seal(view, track, discovered.sealedReason().orElseThrow(), discovered.failure().orElseThrow());
                return;
            }
            if (discovered.value().filter(UniverseDiscoveryRecord::gameplayEligible).isEmpty()) {
                seal(view, track, SealedReason.AUTHORITY_CORRUPT, new IllegalStateException("NEW 的 discovery record 在 RESOLVE 已確認，PASSAGE_READY 之前卻不在或不是"
                        + " gameplay-eligible（spec §6.4、§10.2）：" + discovered.status()));
                return;
            }
        } else {
            switch (DestinationResolver.forServer(server).resolve(view)) {
                case ResolveResult.Wait wait -> {
                    prepareWait(view, progress, "PASSAGE_READY 之前 EXISTING 的重驗：" + wait.reason());
                    return;
                }
                case ResolveResult.Sealed sealed -> {
                    seal(view, track, sealed.reason(), sealed.failure());
                    return;
                }
                case ResolveResult.Resolved resolved -> { }
            }
        }
        var next = view.withCollapse(SessionState.PASSAGE_READY, view.collapse().withPassage(server.getOverworld().getTime()));
        var result = journal.commitRestore(server, view, next);
        if (!result.committed()) {
            track.backoff.failed(now);
            warn(sid, READY_WRITE, result.failure().orElseThrow(), result.status().name(), "record 留在 COLLAPSING；依 per-session 退避（" + track.backoff.delayTicks()
                    + " ticks 後，tick " + track.backoff.retryAtTick() + "）重試這筆提交，退避期內 PREPARE 暫停、原艙門改為一般開門");
            if (!track.readyRetryPending) {
                track.readyRetryPending = true;
                if (!track.doorReleased) originDoors.releasePrepareDoor(view, track);
                LOGGER.info(LOG_MARKER, "提交 PASSAGE_READY 沒有完成，PREPARE 暫停到 per-session 退避期結束：原艙門改為一般開門（開關照一般門的邏輯），退避期過後恢復 PREPARE 的門"
                        + "並重試這筆提交（目的世界的 ticket 繼續持有）：session={} chamber={}", sid, view.chamberUuid());
            }
            return;
        }
        track.warnings.remove(READY_WRITE);
        track.backoff.reset();
        // PREPARE 的簿記到這裡結束：之後是 PASSAGE_READY，由 PassageService 處理（PREPARE 的門不再執行）。
        track.preparing = false;
        track.prepare = null;
        track.membrane = null;
        track.doorReleased = false;
        track.readyRetryPending = false;
        LOGGER.info(LOG_MARKER, "量子通道已開啟（checked PASSAGE_READY）：session={} chamber={} 目的地={} opened={} expires={}", sid, view.chamberUuid(),
                describe(next.collapse().destination()), next.collapse().passageOpenedAtGameTime(), next.collapse().passageExpiresAtGameTime());
        originDoors.openPassage(next);
        refresh(next, "提交 PASSAGE_READY 之後刷新 Controller");
        notifyInside(next, MESSAGE_PASSAGE_OPENED);
    }

    /** PASSAGE_READY 之前必須都已確認的權威（spec §10.2「相關權威」，純函式）：catalog、discovery、projection ledger，NEW 另加 allocation ledger。 */
    static List<CollapseAuthorities.Authority> readyAuthorities(boolean created) {
        var common = List.of(CollapseAuthorities.Authority.CATALOG, CollapseAuthorities.Authority.DISCOVERY, CollapseAuthorities.Authority.PROJECTION_LEDGER);
        if (!created) return common;
        return List.of(CollapseAuthorities.Authority.CATALOG, CollapseAuthorities.Authority.DISCOVERY, CollapseAuthorities.Authority.PROJECTION_LEDGER,
                CollapseAuthorities.Authority.ALLOCATION_LEDGER);
    }

    /** {@link ProjectionWriter.Result} 的下一步（純函式）。 */
    enum WriteStep {
        /** 寫入完成而且逐格驗證通過：繼續。 */
        PROCEED,
        /** 玩家擋住、volume 的 chunk 不是都 FULL：沒有寫任何方塊，下一個 tick 再試（併入 §9.1 的 600 ticks WARN）。 */
        WAIT,
        /** 驗證或寫入失敗：進 SEALED(PROJECTION_FAILED)，以結果帶的 failure 當 root cause。 */
        SEAL
    }

    static WriteStep writeStep(ProjectionWriter.Status status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case WRITTEN -> WriteStep.PROCEED;
            case BLOCKED_BY_PLAYER, NOT_LOADED -> WriteStep.WAIT;
            case VERIFY_FAILED, WRITE_FAILED -> WriteStep.SEAL;
        };
    }

    /**
     * 寫入器結果的共同處置（{@link #writeStep}，F4b-2 fix round 1，quality Q-M5）：WAIT 交給 {@link #prepareWait}、SEAL 以結果帶的 failure 進
     * SEALED(PROJECTION_FAILED)，兩者都回 false（呼叫端 return）；PROCEED 回 true。
     */
    private boolean proceeds(SessionRecoveryRecord view, Track track, Prepare progress, ProjectionWriter.Result result) {
        return switch (writeStep(result.status())) {
            case PROCEED -> true;
            case WAIT -> {
                prepareWait(view, progress, waitingFor(result));
                yield false;
            }
            case SEAL -> {
                seal(view, track, SealedReason.PROJECTION_FAILED, result.failure().orElseThrow());
                yield false;
            }
        };
    }

    /**
     * 確認之後的 PENDING 寫入結果決定寫法（純函式，spec §9.2）：新 key（{@code WRITTEN}）與既有 PENDING（{@code UNCHANGED}）照第一次寫入；既有 COMPLETE
     * （{@code ALREADY_COMPLETE}）只修復。其他狀態沒有確認，是 caller 錯誤。
     */
    static ProjectionWriter.Mode writeMode(CollapseAuthorities.WriteResult.Status status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case WRITTEN, UNCHANGED -> ProjectionWriter.Mode.FIRST_WRITE;
            case ALREADY_COMPLETE -> ProjectionWriter.Mode.REPAIR;
            case UNCONFIRMED, WRITE_FAILED, CORRUPT, UNREADABLE -> throw new IllegalArgumentException("沒有確認的 PENDING 不能決定寫法：" + status);
        };
    }

    /** §9.2 的衝突（純函式）：相交的已登記投影中，另一座艙（或另一個宇宙）的 COMPLETE；本艙在這個宇宙的 entry（PENDING 或 COMPLETE）與 PENDING 都不算。 */
    static Optional<ProjectionLedger.Entry> conflict(List<ProjectionLedger.Entry> intersecting, UUID chamber, UniverseId universe) {
        return intersecting.stream().filter(entry -> entry.status() == ProjectionLedger.Entry.Status.COMPLETE
                && !(entry.chamberUuid().equals(chamber) && entry.universeId().equals(universe))).findFirst();
    }

    /** 投影寫入時要等的事（WARN 的字句）。 */
    private static String waitingFor(ProjectionWriter.Result result) {
        return result.status() == ProjectionWriter.Status.BLOCKED_BY_PLAYER ? "玩家的 bbox 與要寫入的投影 cell 相交（不把方塊寫在玩家身上）：" + result.blockingPlayers()
                : "投影 volume 的 chunk 不是都 FULL";
    }

    /** 投影的 7×7×7 涵蓋的 chunk（原生存檔 checkpoint 的相關 chunks）。 */
    static Set<ChunkPos> volumeChunks(ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        var chunks = new LinkedHashSet<ChunkPos>();
        for (int x = bounds.getMinX() >> 4; x <= bounds.getMaxX() >> 4; x++)
            for (int z = bounds.getMinZ() >> 4; z <= bounds.getMaxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
        return Set.copyOf(chunks);
    }

    /**
     * §9.1 的預載：這個 session 在目的世界還沒有 ticket 時，對 {@link #preloadChunks} 的每個 chunk 加上 {@link #DESTINATION_TICKET}（level FULL，argument
     * 是 session UUID）；之後每個 tick 以非建立模式（{@code getWorldChunk}，不載入、不阻塞）查詢，全部 FULL 才回 true。
     */
    private boolean preload(UUID sid, ServerWorld world, ChamberFrame frame) {
        var held = destinations.get(sid);
        if (held == null || held.world() != world) {
            if (held != null) releaseDestination(sid);
            held = new DestinationTicket(world, preloadChunks(frame));
            // 先記下再加：加到一半丟出時，釋放會移除已加的（removeTicket 對沒有的 ticket 不做任何事）。
            destinations.put(sid, held);
            for (var chunk : held.chunks()) world.getChunkManager().addTicket(DESTINATION_TICKET, chunk, 0, sid);
        }
        for (var chunk : held.chunks()) if (world.getChunkManager().getWorldChunk(chunk.x, chunk.z) == null) return false;
        return true;
    }

    /**
     * §9.1 預載涵蓋的 chunk，依 x、z 遞增的順序：與傳送時的 FULL 檢查同一個推導（{@link PassageTransferPolicy#preloadChunks}；F5-1，F4b-2 spec S-M1：原本是艙體 bbox
     * 外擴 2 格，可能比跨越時玩家 bbox 外擴 2 格的 FULL 檢查少 1 格 chunk）。
     */
    static List<ChunkPos> preloadChunks(ChamberFrame frame) {
        return PassageTransferPolicy.preloadChunks(frame);
    }

    /** PREPARE 的等待（§9.1）：下一個 tick 再試；從進入 PREPARE 起超過 {@value #WAIT_WARN_TICKS} ticks 還沒準備完成時記一次 WARN，然後繼續等。 */
    private void prepareWait(SessionRecoveryRecord view, Prepare progress, String what) {
        if (!waitWarning(progress.since, now, progress.waitWarned)) return;
        progress.waitWarned = true;
        LOGGER.warn(LOG_MARKER, "PREPARE 持續等待超過 {} ticks（不進 SEALED、不重抽，繼續等待）：session={} chamber={} 目的地={} 正在等：{}，自 tick {}", WAIT_WARN_TICKS,
                view.sessionUuid(), view.chamberUuid(), describe(view.collapse().destination()), what, progress.since);
    }

    /** 終局（不論由誰執行，例如 F4b-3 的參與者關門、F5 的通道結束）：session 已不在 journal（current 與 flushed 都沒有）時，釋放它在目的世界的 ticket。 */
    private void releaseTerminatedDestinations() {
        for (var sid : List.copyOf(destinations.keySet())) {
            if (journal.records().containsKey(sid) || journal.flushedRecords().containsKey(sid)) continue;
            if (releaseDestination(sid)) LOGGER.info(LOG_MARKER, "session 已終局，釋放目的世界的 ticket：session={}", sid);
        }
    }

    /** 釋放這個 session 在目的世界的 ticket（沒有時不做任何事），回傳是否釋放了。 */
    private boolean releaseDestination(UUID sid) {
        var held = destinations.remove(sid);
        if (held == null) return false;
        for (var chunk : held.chunks()) held.world().getChunkManager().removeTicket(DESTINATION_TICKET, chunk, 0, sid);
        return true;
    }

    /**
     * 進入 SEALED（§11.2）。journal 以 roll-forward 寫入 SEALED(reason)（current＝flushed 已在呼叫前確認）。寫出未確認時，依 collapse view
     * SEALED 照樣生效，重試交給 recovery 的 RETRY_FLUSH（F3d 的 A2）；寫入在前置檢查就被拒時（journal 沒有改變），依 per-session 退避只重試這筆
     * 寫入（{@link Track#sealRejected}）。之後依序：門改為關閉（門口有 entity 時改為一般開門，§7.3）→ 對參與者顯示訊息（§10.8）→ 刷新 Controller
     * （§10.7）→ 釋放來源 ticket（§5）與目的世界的 ticket（§9.1）→ 依原因記 WARN（可重試）或 ERROR（永久，需要管理員處理），依 (session, 原因, dedupeKey)
     * 去重。
     * <p>被拒時不釋放目的世界的 ticket（F4b-2 fix round 1，quality Q-M4 的判斷）：journal 沒有改變，record 仍是 COLLAPSING，之後只重試這筆寫入（不推導階段），
     * 寫入成功時才在這裡釋放；session 離開 journal 時由 {@link #releaseTerminatedDestinations} 釋放。PREPARE 中的被拒只有前置檢查的拒絕（呼叫前已確認
     * current＝flushed＝view），沒有可以在 GameTest 造出的路徑，提早釋放無法驗證；交給 F4b-3（note「交給後續」）。
     */
    private void seal(SessionRecoveryRecord view, Track track, SealedReason reason, RuntimeException cause) {
        var sid = view.sessionUuid();
        var next = view.withCollapse(SessionState.SEALED, view.collapse().sealed(reason));
        var result = journal.commitRollForward(server, view, next);
        if (result.status() == SessionRecoveryState.CommitResult.Status.REJECTED) {
            track.sealRejected(reason, cause, now);
            warn(sid, SEAL_WRITE, result.failure().orElseThrow(), result.status().name(), track.sealRetryNext());
            // F4b-3 補充第 4 點（F4b-2 spec re-review M-1）：被拒等同 restore 類失敗（record 留在上一份已確認的 COLLAPSING，§6 :260），重試期間 collapsing() 不推導
            // 階段、看不到閘門失效。PREPARE 中被拒時先把原艙門改回一般開門：sealed 量子膜有碰撞，不能在重試期間把人困在艙內。目的世界的 ticket 不釋放（F4b-2 Q-M4）。
            if (track.preparing && !track.doorReleased) {
                originDoors.releasePrepareDoor(view, track);
                LOGGER.info(LOG_MARKER, "進入 SEALED 的寫入被拒，PREPARE 暫停：原艙門改為一般開門，依 per-session 退避只重試這筆 SEALED 寫入（目的世界的 ticket 繼續持有）："
                        + "session={} chamber={}", sid, view.chamberUuid());
            }
            return;
        }
        sealCommitted(view, track, reason, cause, result);
    }

    /**
     * 進入 SEALED 的寫入沒有在前置檢查被拒（已確認，或 roll-forward 未確認、collapse view 已採用）之後的共同步驟（{@link #seal}；F5-1 起也是 PASSAGE_READY→SEALED
     * (TRANSFER_RECOVERY_REQUIRED) 的 {@link PassageService} 使用）：門改為關閉（門口有 entity 時改為一般開門）→ 對參與者顯示訊息 → 刷新 Controller → 釋放來源 ticket
     * 與目的世界的 ticket → 依原因記 WARN 或 ERROR。{@code view} 是寫入前的 record（原艙、參與者與 session 都與 SEALED 相同）。
     */
    void sealCommitted(SessionRecoveryRecord view, Track track, SealedReason reason, RuntimeException cause, SessionRecoveryState.CommitResult result) {
        var sid = view.sessionUuid();
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
        originDoors.queueDoor(view, 0, OriginDoors.DoorGoal.CLOSE_ON_SEALED);
        notifyParticipants(view, MESSAGE_SEALED);
        refresh(view, "進入 SEALED 之後刷新 Controller");
        sideEffect(sid, "進入 SEALED 時釋放來源 ticket", "不重試；停服時一併釋放", () -> sessions.releaseOnSealed(server, sid));
        // spec §9.1：進入 SEALED 時與來源 ticket 一起釋放目的世界的預載 ticket（F4b-2）。
        if (releaseDestination(sid)) LOGGER.info(LOG_MARKER, "進入 SEALED，已釋放目的世界的 ticket：session={} chamber={}", sid, view.chamberUuid());
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
     * §7.3 的 PREPARE 閘門（{@link CollapsePhase#derive} 的 {@code gate}；轉換由推導結果回傳，這裡不改 {@code track.preparing}，F4b-2，F3d quality m-6(b)）：
     * 來源艙已載入、結構合法、已供電、Origin enabled，而且有原參與者在艙內。由 {@code derive} 依上一個 tick 是否在 PREPARE 選擇評估方式（F4b-2 fix round 1，
     * quality Q-M6：這裡不再讀 {@code track.preparing}）。
     */
    private final class PrepareGate implements CollapsePhase.Gate {
        private final SessionRecoveryRecord view;
        private final Track track;

        PrepareGate(SessionRecoveryRecord view, Track track) { this.view = view; this.track = track; }

        /** 進入 PREPARE 之後參與者在艙內不是持續條件：只看來源艙的條件，不成立就退回 WAITING。 */
        @Override public boolean stillOpen() { return chamberGate(view); }

        /**
         * 本 server instance 第一次看到這個 session 走到閘門階段時立即以位準評估（live 塌縮返還之後通常立即成立；開服之後就是「第一次觀察」）；不成立就停在
         * WAITING，之後只在 §7.1 的邊緣觸發（原參與者由艙外進入艙內）時再評估。
         */
        @Override public boolean edge() {
            var insideNow = inside(view);
            List<UUID> candidates;
            if (!track.gateEvaluated) {
                track.gateEvaluated = true;
                track.entry(false).prime(insideNow);
                candidates = List.copyOf(insideNow);
            } else {
                candidates = track.entry(false).observe(insideNow);
            }
            return candidates.stream().anyMatch(CollapseService.this::triggerReady) && chamberGate(view);
        }
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
        originDoors.openDoor(view, 0);
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
        originDoors.openDoor(record, 1);
        refresh(record);
    }

    // ---- 原艙門（F4b-3；F5-0 起在 OriginDoors） ----

    /**
     * {@link ChamberCollapsePort.Doors} 的實作（{@link #initialize} 註冊）：交給這個 server 的原艙門（{@link OriginDoors#door}）；這個 server 沒有塌縮流程（例如開服時
     * 讀不到 journal）時不攔截。PASSAGE_READY 期間的門（§10.5，F5-1）也經這裡，處置接在 {@link OriginDoors#doorAction}。
     */
    private static ChamberCollapsePort.DoorUse useDoor(ServerWorld world, ChamberFrame frame, PlayerEntity player) {
        var service = INSTANCES.get(world.getServer());
        return service == null ? ChamberCollapsePort.DoorUse.PASS : service.originDoors.door(world, frame, player);
    }

    /**
     * {@link ChamberCollapsePort.Membranes} 的實作（{@link #initialize} 註冊；spec §10.6 :613，F5-2）：交給這個 server 的原艙門排入下一個 tick 的正規化
     * （{@link OriginDoors#queueOwnerless}）。這個 server 沒有塌縮流程時不做事。不丟出例外：排入時的非預期例外記 tick 層級的 WARN（沒有 session 可帶），下一次刷新再試。
     */
    private static void normalizeOwnerless(ServerWorld world, ChamberFrame frame, UUID chamber) {
        var service = INSTANCES.get(world.getServer());
        if (service == null) return;
        try {
            service.originDoors.queueOwnerless(world, frame, chamber);
        } catch (RuntimeException failure) {
            LOGGER.warn(TICK_LOG_MARKER, "找不到所屬 session 的量子膜沒有排入正規化（非預期的例外），下一次刷新再試：chamber={}：{}", chamber, FailureText.describe(failure), failure);
        }
    }

    // ---- 共用（package-private 的幾個也由 OriginDoors 與 PassageService 呼叫：terminated、terminalFailed、neighborhoodLoaded、refresh(record)、warn、track、describe；
    // PassageService 另用 sealCommitted、sourceSignal） ----

    /** checked 終局讀回確認之後：釋放 runtime 與來源 ticket（spec §5）與目的世界的 ticket（§9.1），不再追蹤這個 session。 */
    void terminated(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        tracks.remove(sid);
        startupPassages.remove(sid);
        sessions.releaseAfterTerminal(server, sid);
        if (releaseDestination(sid)) LOGGER.info(LOG_MARKER, "session 已終局，釋放目的世界的 ticket：session={}", sid);
    }

    /** 終局沒有完成：依 (session, 寫入種類, 原因) 去重記 WARN；restore 類失敗之後以 session 為單位退避（§6、§11.2）。不改門。 */
    void terminalFailed(SessionRecoveryRecord record, Track track, String what, TerminalResult result) {
        warn(record.sessionUuid(), what, result.failure().orElseThrow(), result.status().name(), "之後依退避重試");
        track.backoff.failed(now);
    }

    /** 原參與者中在線、存活、非旁觀、位於來源 world 而且 bbox 完整在來源艙 interior 內的人（只在這裡使用；F5-1 fix round 1，quality Q-m7 改回 private）。 */
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
     * 來源艙已載入、結構合法、已供電、Origin enabled（{@link #sourceSignal} 為 {@code OK}）：§7.1 的 DORMANT 觸發、§7.3 的閘門、§11.2 的重試觸發。
     */
    private boolean chamberGate(SessionRecoveryRecord record) {
        return sourceSignal(record) == SourceSignal.OK;
    }

    /** 來源艙的即時訊號（{@link #sourceSignal}）：{@code OK} 之外的每一種都表示那一個條件不成立（依檢查順序，第一個不成立的）。 */
    enum SourceSignal {
        OK,
        /** 來源世界或艙體向水平外擴 3 格的 chunk 沒有都 FULL（只查詢、不載入）。 */
        NOT_LOADED,
        /** 結構失效：Controller 的 block entity、方塊或朝向不符，或艙體驗證不通過。 */
        INVALID,
        /** 沒有供電：Controller 沒有即時紅石輸入。 */
        LOW,
        /** Origin 被停用：registry 讀不到、沒有這座原艙、已拆除，或不是 enabled。 */
        DISABLED
    }

    /**
     * 來源艙的即時訊號（F5-1 由原本的 {@code chamberGate} 拆出原因，判斷的條件與順序不變；§10.6 (d) 要分辨斷電、停用與結構失效）。一律依即時狀態：「已供電」是 Controller
     * 的即時紅石輸入（spec §7），不看 registry 的 {@code ChamberPowerState}；「已載入」涵蓋艙體向水平各方向外擴 3 格（紅石讀取與門的套用範圍）的 chunk，只查詢、不載入。
     */
    SourceSignal sourceSignal(SessionRecoveryRecord record) {
        var origin = record.origin();
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, origin.worldKey()));
        if (world == null) return SourceSignal.NOT_LOADED;
        var frame = new ChamberFrame(origin.controllerPos(), origin.facing());
        if (!neighborhoodLoaded(world, frame)) return SourceSignal.NOT_LOADED;
        var pos = origin.controllerPos();
        var chunk = world.getChunkManager().getWorldChunk(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk == null) return SourceSignal.NOT_LOADED;
        if (!(chunk.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller) || controller.isRemoved()
                || controller.instanceKind() != ChamberInstanceKind.ORIGIN || !origin.chamberUuid().equals(controller.chamberUuid())) return SourceSignal.INVALID;
        var block = chunk.getBlockState(pos);
        if (!block.isOf(ModBlocks.CHAMBER_CONTROLLER) || block.get(ChamberControllerBlock.FACING) != origin.facing()) return SourceSignal.INVALID;
        if (!new ChamberDetector().validate(new WorldChamberBlockView(world), frame).valid()) return SourceSignal.INVALID;
        if (!world.isReceivingRedstonePower(pos)) return SourceSignal.LOW;
        var registry = ChamberRegistryState.get(server);
        if (registry.loadError().isPresent()) return SourceSignal.DISABLED;
        return registry.registry().findOrigin(origin.worldKey(), origin.role(), frame)
                .filter(found -> found.chamberUuid().equals(origin.chamberUuid()) && !found.destroyed()
                        && found.instanceKind() == ChamberInstanceKind.ORIGIN && found.enabled()).isPresent() ? SourceSignal.OK : SourceSignal.DISABLED;
    }

    static boolean neighborhoodLoaded(ServerWorld world, ChamberFrame frame) {
        var bounds = ChamberGeometry.bounds(frame);
        for (int x = (bounds.getMinX() - 3) >> 4; x <= (bounds.getMaxX() + 3) >> 4; x++)
            for (int z = (bounds.getMinZ() - 3) >> 4; z <= (bounds.getMaxZ() + 3) >> 4; z++)
                if (world.getChunkManager().getWorldChunk(x, z) == null) return false;
        return true;
    }

    /** 對在線的每一位參與者顯示 actionbar（spec §10.8；不論他在哪個世界）。 */
    void notifyParticipants(SessionRecoveryRecord record, String key) {
        for (var person : record.participants()) {
            var player = server.getPlayerManager().getPlayer(person.playerUuid());
            if (player != null && !player.isRemoved()) player.sendMessage(Text.translatable(key), true);
        }
    }

    /** 只對在來源艙內的參與者（{@link #inside}）顯示 actionbar（spec §10.2 第 3 步「對在艙內的參與者」）。 */
    private void notifyInside(SessionRecoveryRecord record, String key) {
        for (var id : inside(record)) {
            var player = server.getPlayerManager().getPlayer(id);
            if (player != null && !player.isRemoved()) player.sendMessage(Text.translatable(key), true);
        }
    }

    /** 讓比較器與 registry 的 power state 立即反映終局（presence 回到 NONE）；Controller 所在 chunk 沒載入時由之後的載入協調處理。 */
    void refresh(SessionRecoveryRecord record) {
        refresh(record, "終局之後刷新 Controller");
    }

    /** 刷新來源艙的 Controller（§10.7）；{@code what} 是失敗時 WARN 的寫入種類（成功時清掉它的去重鍵，{@link #sideEffect}）。 */
    private void refresh(SessionRecoveryRecord record, String what) {
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, record.origin().worldKey()));
        if (world == null) return;
        sideEffect(record.sessionUuid(), what, "不重試，Controller 在下次載入或紅石更新時協調",
                () -> new ChamberPowerCoordinator().refresh(world, record.origin().controllerPos()));
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
    void warn(UUID sid, String what, RuntimeException failure, String status, String next) {
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

    Track track(UUID sid) {
        return tracks.computeIfAbsent(sid, ignored -> new Track());
    }

    static String describe(CollapseDestination destination) {
        return destination instanceof CollapseDestination.Universe universe ? "UNIVERSE " + universe.id().value() : "SOURCE";
    }

    /**
     * 每個 session 在本 server instance 的塌縮簿記（不持久化）。package-private 只為了 JUnit 直接驗證它的方法（F4a fix round 2）。
     * <p><b>重設時點</b>（F5-2 Step 0，F5-1 fix round 1 quality re-review R-m3）：整個 Track 在終局（{@link CollapseService#terminated}）或 session 離開 journal（{@code tick} 的
     * {@code retainAll}）時移除。{@link #resetGate}（提交 COLLAPSING、進入 SEALED、SEALED→COLLAPSING 的重試）只重設閘門與 PREPARE 的部分（{@code entry}、{@code gateEvaluated}、
     * {@code preparing}、{@code prepare}、{@code membrane}、{@code doorReleased}）。刻意不隨它重設的：
     * <table>
     * <tr><th>欄位</th><th>重設時點</th><th>理由</th></tr>
     * <tr><td>{@code doorLoaded}、{@code doorSeenLoaded}</td><td>不重設</td><td>來源艙載入的觀察，在 SEALED 與重試之間仍有效</td></tr>
     * <tr><td>{@code projectionUncheckpointed}</td><td>checkpoint 之後的再驗乾淨時</td><td>寫過的投影方塊跨 PREPARE 的段落仍沒有 checkpoint</td></tr>
     * <tr><td>{@code readyRetryPending}</td><td>提交 PASSAGE_READY 成功，或退避期過後 PREPARE 恢復時</td><td>暫停跟著 per-session 退避，閘門失效之後再進入 PREPARE 仍有效</td></tr>
     * <tr><td>{@code backoff}、{@code resolved}、{@code restartRetryConsumed}、{@code pendingSeal}、{@code pendingRecovery}、{@code passageZ}、{@code waitSince}</td>
     *     <td>見各自的說明</td><td>不屬於閘門或 PREPARE 的一段</td></tr>
     * </table>
     */
    static final class Track {
        /**
         * 提交失敗、restore 類寫入（終局、SEALED 的重試）失敗、進入 SEALED 的寫入在前置檢查就被拒（{@link #sealRejected}）、以可重試的原因進入 SEALED
         * 之後的 per-session 退避（§7.1、§11.2；被拒的一項由 F4b-1 Step 0 補進說明，F4a fix round 2 quality Q2-m4）；RESOLVE 完成時清除。
         */
        final CollapseBackoff backoff = new CollapseBackoff();
        /** WARN 的去重：寫入種類 → 最近一次的 {@link FailureText#dedupeKey}；成功時移除。 */
        final Map<String, String> warnings = new HashMap<>();
        EntryWatch entry;
        boolean gateEvaluated;
        /**
         * 上一個 tick 推導的階段是 PREPARE：只交給 {@link CollapsePhase#derive}（轉換與閘門的評估方式都由它決定，F4b-2 fix round 1，quality Q-M6）。UNIVERSE 時
         * 恆等於 {@code prepare != null}（進入時建立、離開時丟棄）；SOURCE 的 PREPARE 沒有 {@link Prepare}。
         */
        boolean preparing;
        /** UNIVERSE 目的地在本 instance 已確定（RESOLVE 完成）；不持久化，重啟之後重新確認（§7.3）。 */
        boolean resolved;
        /**
         * 本 instance 已用掉 restart-only 原因的那一次 SEALED→COLLAPSING 重試，或本 instance 自己以 restart-only 的原因封存了它（§11.2「每個 server
         * instance 一次」）。只管重試的觸發；物化本身每個 instance 一次由 resolver 依 UniverseId 把關（F4a fix round 1）。
         */
        boolean restartRetryConsumed;
        /** UNIVERSE 這一段 PREPARE 的記憶體內進度（進入 PREPARE 時建立，離開、進入 SEALED、重試時丟棄；不持久化，重啟之後重新推導）。 */
        Prepare prepare;
        /**
         * 這個 session 寫過的投影方塊（第一次寫入或修復）還沒有被原生存檔 checkpoint 涵蓋（F5-1 fix round 1，quality Q-m1）：寫了方塊時設定，checkpoint 之後的再驗乾淨時清除
         * （{@link CollapseService#project}）。跨 PREPARE 的段落保留：不隨 {@link #resetGate} 重設，閘門失效、進入 SEALED 與重試之間也保留。修復一格都沒有寫時，它為真就仍要先做 checkpoint 才準備
         * 完成（F4b-2 spec S-M2 原本只在單一一段 PREPARE 內成立：上一段修復了方塊、checkpoint 之前閘門失效，下一段的修復寫 0 格就沒有 checkpoint 地提交 PASSAGE_READY）。
         * 不持久化：重啟之前沒有存到的修復，重啟之後的修復會再寫一次（寫了方塊就做 checkpoint）。
         */
        boolean projectionUncheckpointed;
        /**
         * 提交 PASSAGE_READY 的寫入沒有完成過、PREPARE 還沒恢復（restore 類，F5-1；F5-2 Step 0 由 {@code Prepare.readyFailed} 移到這裡，F5-1 fix round 1 quality re-review R-m1）：
         * per-session 退避期內 PREPARE 暫停（{@link #readyPaused}）。綁在 Track 上，與退避同一個生命週期：退避期內閘門失效（{@code leavePrepare} 丟棄 {@link Prepare}）、參與者再進入
         * PREPARE 時仍然暫停，原艙門維持一般開門、開關照一般門的邏輯（綁在 Prepare 上時會套用 sealed 量子膜，而參與者關門被退避擋下，等於被困住）。退避期過後 PREPARE 恢復時與提交成功時清除。
         */
        boolean readyRetryPending;
        /**
         * PASSAGE_READY 的跨越判定（§10.3，F5-1）：每位參與者最近一次觀察到的腳在來源艙的 local z 與那個 server tick（{@link PassageService#observe}）；不在來源世界或觀察不到時
         * 沒有這個鍵。推進有執行的每個 PASSAGE_READY 的 tick 都更新（roll-forward 未確認、結束條件成立的 tick 也一樣）；推進被略過的 tick（未 checked 的 authority、隔離、例外）
         * 不更新，所以跨越只認 tick 恰為上一個 tick 的觀察（{@link PassageService#previousTick}，F5-1 fix round 1，spec review S-m1）。不持久化：重啟之後 PASSAGE_READY 直接結束（§12）。
         */
        final Map<UUID, PassageService.ObservedZ> passageZ = new HashMap<>();
        /**
         * {@code FAILED_RECOVERY_REQUIRED} 之後 PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED) 的寫入在前置檢查就被拒（journal 沒有改變；F5-1，防禦性）：通道在記憶體內
         * 關閉（不跨越、不評估結束條件、不終局、參與者關門被拒；原艙門改為一般開門，重新套用也是，F5-1 fix round 1，S-m3），依 per-session 退避只重試這筆寫入（{@link PassageService}）。
         */
        PassageService.PendingRecovery pendingRecovery;
        /** RESOLVE 連續等待的起點（-1 表示不在等待）與這次等待是否已記過 WARN。 */
        long waitSince = -1;
        boolean waitWarned;
        /** 進入 SEALED 的寫入在前置檢查就被拒：退避期過後只重試這筆寫入（{@link #sealRejected}）。 */
        SealedReason pendingSeal;
        RuntimeException pendingSealCause;
        // 以下四個是原艙門的 per-session 狀態，由 OriginDoors 讀寫；生命週期跟著 Track（F5-0：沒有搬到 OriginDoors，理由見它的 Javadoc）。
        /**
         * 本 instance 上一次觀察到「來源艙已載入」（{@link OriginDoors#watchDoor}；null 表示還沒觀察過）。重新套用原艙門的一次性觸發由它推導（{@link OriginDoors#doorReloaded}，F4b-3）。
         * 不隨 {@link #resetGate} 重設：同一個 session 在 SEALED 與重試之間來回時，載入的觀察仍有效。
         */
        Boolean doorLoaded;
        /**
         * 本 instance 曾觀察到來源艙已載入（{@link OriginDoors#watchDoor}）。開服時就在的 session 在這之前的觸發是「重啟之後第一次載入」（{@link OriginDoors#reapplyCause}，F4b-3 fix round 1）；
         * 不隨 {@link #resetGate} 重設。
         */
        boolean doorSeenLoaded;
        /** PREPARE 每個 tick 的原艙門的套用狀態（WARN 去重與退避；成功時清除，F4b-3）。 */
        OriginDoors.PendingDoor membrane;
        /** PREPARE 暫停或結束時，原艙門已交還為一般開門（{@link OriginDoors#releasePrepareDoor}）；PREPARE 再次擁有原艙門時清除。 */
        boolean doorReleased;

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

        /**
         * PREPARE 因提交 PASSAGE_READY 沒有完成而暫停（F5-1 fix round 1，spec review S-m2、quality Q-m2）：提交失敗過、還沒恢復（{@link #readyRetryPending}），而且還在
         * per-session 退避期內。spec §6 :260：restore 類失敗時 PREPARE 中的門依 §7.3 閘門失效的處理改為一般開門，所以這段期間 PREPARE 不判斷門、也不擁有門（開關照一般門的邏輯，
         * {@link OriginDoors#door}）；退避期過後恢復每個 PREPARE tick 的門（§7.3）與提交的重試。
         */
        boolean readyPaused(int now) {
            return readyRetryPending && !backoff.ready(now);
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
            prepare = null;
            membrane = null;
            doorReleased = false;
        }

        void endWait() {
            waitSince = -1;
            waitWarned = false;
        }
    }

    /**
     * UNIVERSE 一段 PREPARE 的記憶體內進度（F4b-2）。只記「這一段」裡已觀察到的事（開始的 tick、預載完成的 tick、等待的 WARN 是否已記），權威的狀態
     * （projection ledger 的 PENDING／COMPLETE）每個 tick 重新查；重啟或離開 PREPARE 之後從頭推導。
     */
    static final class Prepare {
        /** 進入 PREPARE 的 server tick（§9.1 的 600 ticks WARN 由此起算）。 */
        final int since;
        /** 預載完成的 server tick（-1 表示還沒完成）。 */
        int preloadedAt = -1;
        /** 這一段的等待 WARN 是否已記過。 */
        boolean waitWarned;
        /** 最近一次登記到投影保護索引的已確認 entry（同一份不重複登記）。 */
        ProjectionLedger.Entry registered;
        /** 第一次寫入已寫完並逐格驗證（本段），之後只等 checkpoint、再驗與 COMPLETE；{@code writtenCells} 是那次實際改變的格數。 */
        boolean written;
        int writtenCells;
        /** 「第一次寫入之後被改動、已修復」的 INFO 已記過（每一段 PREPARE 一次，F4b-2 fix round 1，quality Q-I1）。 */
        boolean repairLogged;
        /** 這一段 PREPARE 已記過「套用 sealed 量子膜」／「維持一般開門」的 INFO（{@link OriginDoors#prepareDoor}，各一次，F4b-3）。 */
        boolean sealedLogged, openLogged;
        /** 準備完成（預載與投影都已完成）；之後只等 PASSAGE_READY 的前置條件（{@link #ready}）。提交失敗之後的暫停在 {@link Track#readyRetryPending}（F5-2 Step 0，R-m1）。 */
        boolean ready;

        Prepare(int since) { this.since = since; }
    }

    /** 一個 session 在目的世界持有的預載 ticket：世界與 ticket 涵蓋的 chunk（{@link #preloadChunks}）。 */
    private record DestinationTicket(ServerWorld world, List<ChunkPos> chunks) { }

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
}
