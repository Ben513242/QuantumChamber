package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseService.LOG_MARKER;
import static dev.quantumchamber.collapse.CollapseService.TICK_LOG_MARKER;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberGeometry;
import dev.quantumchamber.chamber.ChamberSessionGateway;
import dev.quantumchamber.chamber.QuantumBulkheadBlock;
import dev.quantumchamber.collapse.CollapseService.Prepare;
import dev.quantumchamber.collapse.CollapseService.Track;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin;
import dev.quantumchamber.registry.ModBlocks;
import dev.quantumchamber.superposition.SessionState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 塌縮流程的原艙門（spec §7 開頭、§7.3、§11.2；F3d 起，F4b-3 加上 PREPARE 期間的門、攔截與重新套用）。F5-0 從 {@link CollapseService} 逐字搬來，行為不變。
 * 每個 server instance 一個，由 {@link CollapseService} 建立並持有；只在 server thread 上、經它的 tick 或原艙門的攔截呼叫，共用它每個 tick 取得的 context
 * （journal、session manager、server tick）與每個 session 的簿記（{@link CollapseService.Track}）。
 * <ul>
 * <li><b>攔截</b>（{@code ChamberCollapsePort.Doors}）：{@link CollapseService#initialize} 註冊 {@code CollapseService::useDoor}，它找到這個 server 的塌縮流程之後交給
 *     {@link #door}；處置由純函式 {@link #doorAction} 決定（參與者 {@link #endByParticipant}、艙內的非參與者 {@link #openForInsider}、其他拒絕或照一般門的邏輯）。</li>
 * <li><b>PREPARE 期間的門</b>：{@link #prepareDoor}（每個 PREPARE 的 tick，由 {@code CollapseService.prepareUniverse} 呼叫）、{@link #releasePrepareDoor}（離開或暫停
 *     PREPARE）。</li>
 * <li><b>重新套用</b>：{@link #watchDoor}（每個 tick 對 COLLAPSING、SEALED 觀察一次來源艙）。</li>
 * <li><b>待套用的門</b>：{@link #queueDoor}、{@link #openDoor}（排入），{@link #applyPending}（每個 tick 套用），目標見 {@link DoorGoal}。</li>
 * </ul>
 * <p><b>F5-1 的接點</b>（spec §10.2–§10.6）：PASSAGE_READY 期間的開關門（§10.5：參與者結束通道，先終局、再改門；非參與者拒絕）接在 {@link #doorAction} 的 PASSAGE_READY
 * 分支（roll-forward 尚未確認時已拒絕）與 {@link #door} 的處置 switch，{@code CollapseService.useDoor} 與 port 不必改。提交 PASSAGE_READY 時的 passable 量子膜經
 * {@code ChamberDoorService.applyTarget}；{@link #prepareDoor} 只在 PREPARE 擁有原艙門時執行，READY 之後不會把它改回 sealed。通道結束 (a)–(d) 的改門排入
 * {@link #queueDoor}（例如 {@link DoorGoal#OPEN_AFTER_TERMINAL}）。{@link #watchDoor} 目前不看 PASSAGE_READY（開服時的通道由 §12 在第一個 tick 終局）。
 * <p><b>F5-2 的接點</b>（spec §10.6 :613）：找不到所屬 session 的量子膜，在 {@code ChamberPowerCoordinator} 刷新該艙時排入下一個 tick 正規化為一般開門。待套用的門以 chamber
 * 為 key（{@link #doors}），但 {@link PendingDoor} 帶著 session、{@link DoorGoal#NORMALIZE} 要求 collapse view 是 COLLAPSING，所以沒有 session 的正規化要另一個目標；條件見
 * M5 note F4b-3「補充第 8 點」。有 COLLAPSING／SEALED 的 session 時由 {@link #watchDoor} 與待套用的門處理，兩者不要同時寫門。
 * <p>每個 session 的門狀態（{@code doorLoaded}、{@code doorSeenLoaded}、{@code membrane}、{@code doorReleased}）留在 {@link CollapseService.Track}：它們的生命週期（第一次
 * 推進時建立、只保留 journal 裡的 session、終局時移除、{@code resetGate} 重設 PREPARE 的部分）就是 Track 的，而這裡本來就要讀 Track 的 {@code preparing}、
 * {@code pendingSeal}、{@code backoff} 與 {@code warnings}。
 * <p>這裡的每一則 log 都帶 {@link CollapseService#LOG_MARKER} 或子 marker {@link CollapseService#TICK_LOG_MARKER}（{@code CollapseLogMarkerGuardTest}）。
 */
final class OriginDoors {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");

    /**
     * {@link CollapseService#warn} 的寫入種類的前綴：原艙門的攔截（{@link #door}）在判斷或處置途中丟出例外（F4b-3 fix round 1，quality Q-I1）。去重鍵依處置區分
     * （{@link #doorUseKey}，F5-0，F4b-3 quality M-1）：這個 session 以同一個處置沒有例外地完成時，只清這個處置的鍵。
     */
    private static final String DOOR_USE = "原艙門的攔截";
    /** 擁有這些門的塌縮流程（同一個 server instance）：共用它每個 tick 取得的 context（journal、session manager、server tick）與每個 session 的簿記。 */
    private final CollapseService service;
    /**
     * 還沒套用成功的原艙門（以 chamber 為 key，{@link DoorGoal}）：終局之後的一般開門（{@code OPEN_AFTER_TERMINAL}）、進入 SEALED 時（含重新套用）的關門
     * （{@code CLOSE_ON_SEALED}）、PREPARE 期間參與者關門的終局之後的關門（{@code CLOSE_AFTER_TERMINAL}）、PREPARE 結束或暫停之後的一般開門（{@code OPEN_ON_LEAVE}）、
     * 重新套用時的量子膜正規化（{@code NORMALIZE}）。PREPARE 每個 tick 的原艙門不在這裡（{@link CollapseService.Track#membrane}）。
     */
    private final Map<UUID, PendingDoor> doors = new LinkedHashMap<>();

    OriginDoors(CollapseService service) { this.service = Objects.requireNonNull(service, "service"); }

    // ---- PREPARE 期間的原艙門（F4b-3；spec §7.3「套用量子膜的條件」「門口有人時的規則」） ----

    /**
     * PREPARE 結束或暫停時把原艙門交還為一般開門（{@code open=true}、{@code membrane=none}；spec §7.3 閘門失效的第 2 步、§6 :260 restore 類失敗時 PREPARE 中的門；
     * F4b-3 補充第 3、4 點）：丟棄 PREPARE 的門的簿記，排入待套用的門（{@link DoorGoal#OPEN_ON_LEAVE}，立即嘗試一次；chunk 不是 FULL 時等之後的 tick，不載入）。
     * PREPARE 再次擁有原艙門時（{@link #prepareDoor}）會移除這扇待套用的門。
     */
    void releasePrepareDoor(SessionRecoveryRecord view, Track track) {
        track.membrane = null;
        track.doorReleased = true;
        queueDoor(view, 0, DoorGoal.OPEN_ON_LEAVE);
    }

    /**
     * PREPARE 的原艙門（spec §7.3「套用量子膜的條件」與「門口有人時的規則」；F4b-3 補充第 1、6 點），每個 PREPARE 的 tick（含準備完成之後）重新判斷：門的 25 格內
     * 沒有任何 entity 的 bbox（門口那幾格的 entity storage 要已載入，否則當成門口有人），<b>而且</b>艙內沒有非參與者玩家（不含旁觀者），才套用 sealed 量子膜
     * （{@code open=true}＋{@code membrane=sealed_<axis>}）；否則維持一般開門，準備照常在背景進行。這段期間 PREPARE 擁有原艙門：先移除這座艙其他待套用的門。
     * {@code applyTarget} 的結果依 {@link #applyOutcome}（UNCHANGED 不寫入；NOT_LOADED 下一個 tick 再試；DOOR_INCOMPLETE、ROLLED_BACK、ROLLBACK_FAILED 依原因去重記
     * WARN、必要時退避，狀態記在 {@link CollapseService.Track#membrane}，成功時重設）。
     */
    void prepareDoor(SessionRecoveryRecord view, Track track, Prepare progress) {
        var world = service.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, view.origin().worldKey()));
        if (world == null) return;
        var frame = new ChamberFrame(view.origin().controllerPos(), view.origin().facing());
        doors.remove(view.chamberUuid());
        track.doorReleased = false;
        boolean doorwayClear = doorwayEntitiesLoaded(world, frame) && !doorwayOccupied(world, frame);
        boolean outsider = outsiderInside(view, world, frame);
        var target = membraneTarget(frame.outwardFacing(), doorwayClear, outsider);
        if (track.membrane == null)
            track.membrane = new PendingDoor(view.chamberUuid(), view.sessionUuid(), world.getRegistryKey(), frame, service.now, DoorGoal.PREPARE_MEMBRANE);
        var door = track.membrane;
        if (!door.backoff.ready(service.now)) return;
        ChamberDoorService.ApplyResult result;
        try {
            result = new ChamberDoorService().applyTarget(world, frame, target);
        } catch (RuntimeException failure) {
            applyFailure(door, failure, service.now);
            return;
        }
        if (!applyOutcome(door, result.outcome(), service.now)) return;
        track.membrane = null;
        if (result.outcome() != ChamberDoorService.ApplyResult.Outcome.APPLIED) return;
        // 改了門才記；每一段 PREPARE 兩種結果各只記一次（門口的 entity 進出時不洗版）。
        boolean sealed = target.membrane().sealed();
        if (sealed ? progress.sealedLogged : progress.openLogged) return;
        if (sealed) progress.sealedLogged = true; else progress.openLogged = true;
        if (sealed) {
            LOGGER.info(LOG_MARKER, "PREPARE：原艙門套用 sealed 量子膜（有碰撞，不能穿過；這一段 PREPARE 只記一次）：session={} chamber={}", view.sessionUuid(),
                    view.chamberUuid());
        } else {
            LOGGER.info(LOG_MARKER, "PREPARE：{}，原艙門維持一般開門（不套用量子膜，準備照常進行，之後每個 tick 再判斷；這一段 PREPARE 只記一次）：session={} chamber={}",
                    doorwayClear ? "艙內有非參與者玩家" : outsider ? "門口有 entity，而且艙內有非參與者玩家" : "門口有 entity（或門口的 entity storage 尚未載入）",
                    view.sessionUuid(), view.chamberUuid());
        }
    }

    /** 套用量子膜的條件（spec §7.3，純函式）：門口沒有 entity、艙內沒有非參與者玩家時是 sealed 量子膜，否則是一般開門。 */
    static ChamberDoorService.DoorState membraneTarget(Direction outwardFacing, boolean doorwayClear, boolean outsiderInside) {
        return doorwayClear && !outsiderInside ? ChamberDoorService.DoorState.sealed(outwardFacing) : ChamberDoorService.DoorState.OPEN;
    }

    /**
     * 艙內有非參與者的玩家（spec §7.3；不含旁觀者）：在來源 world、bbox 與 interior 相交（含完整在內）。相交比「完整在內」寬：跨在門口的人另由門口的規則涵蓋，
     * 兩者都偏向不套用有碰撞的門、讓人可以開門離開。
     */
    private boolean outsiderInside(SessionRecoveryRecord view, ServerWorld world, ChamberFrame frame) {
        var interior = ChamberGeometry.interiorBox(frame);
        for (var player : world.getPlayers()) {
            if (player.isRemoved() || player.isSpectator() || participates(view, player.getUuid())) continue;
            if (player.getBoundingBox().intersects(interior)) return true;
        }
        return false;
    }

    private static boolean participates(SessionRecoveryRecord view, UUID player) {
        return view.participants().stream().anyMatch(person -> person.playerUuid().equals(player));
    }

    /**
     * 門口（25 格門的外框）所在 chunk 的方塊與 entity storage 都已載入（只查詢、不載入）。重啟或 chunk 重新載入之後 entity 是非同步載入的：還沒載入時
     * {@link #doorwayOccupied} 看不到門口的 entity，所以會讓門變成有碰撞的轉換都要先等它（§7.3「門口有人時的規則」）。
     */
    static boolean doorwayEntitiesLoaded(ServerWorld world, ChamberFrame frame) {
        var box = doorwayBox(frame);
        for (int x = (int) Math.floor(box.minX) >> 4; x <= (int) Math.floor(box.maxX - 1) >> 4; x++)
            for (int z = (int) Math.floor(box.minZ) >> 4; z <= (int) Math.floor(box.maxZ - 1) >> 4; z++)
                if (world.getChunkManager().getWorldChunk(x, z) == null || !world.isChunkLoaded(ChunkPos.toLong(x, z))) return false;
        return true;
    }

    /** 25 格門裡有任何一格帶著量子膜（{@code open=true}＋sealed 或 passable）；呼叫前門的 chunk 必須已載入。 */
    private static boolean doorHasMembrane(ServerWorld world, ChamberFrame frame) {
        for (int y = 1; y <= 5; y++) for (int x = 1; x <= 5; x++) {
            var state = world.getBlockState(ChamberGeometry.localToWorld(frame, x, y, 0));
            if (state.isOf(ModBlocks.QUANTUM_BULKHEAD) && QuantumBulkheadBlock.membrane(state).isMembrane()) return true;
        }
        return false;
    }

    // ---- 重新套用與攔截（F4b-3；spec §7 開頭、§7.3） ----

    /**
     * 原艙門依 collapse view 的重新套用（spec §7.3「門狀態的重新套用」；F4b-3 補充第 5 點），以及隔離中的 PREPARE 的門（補充第 4 點）。每個 tick 對 COLLAPSING 與
     * SEALED（collapse view；含 roll-forward 尚未確認的 SEALED）的 session 觀察一次來源艙（艙體向水平外擴 3 格的 chunk，只查詢、不載入）：
     * <ul>
     * <li><b>一次性的觸發</b>（{@link #doorReloaded}）：本 instance 看過它沒有載入、這次已載入（來源 chunk 重新載入），或開服 attach 當下就在 journal、而這是本 instance
     *     第一次觀察（重啟之後）。只在這時排入下一個 tick 的佇列重新套用（不在 chunk 載入的事件當下寫方塊）：SEALED 關門、門口有 entity 時一般開門；COLLAPSING 把帶著量子膜
     *     的門改為一般開門（一般狀態的階段不得留下有碰撞的膜；PREPARE 則由 {@link #prepareDoor} 每個 tick 重新判斷）。已經有待套用的門（例如閘門失效時排入、chunk
     *     不是 FULL 而一直等著）時只把它延到下一個 tick。一直載入著的 session 不會再觸發：SEALED 的門被玩家打開之後不會被關回去（§11.2、§7.3 :404）。</li>
     * <li>PASSAGE_READY 屬 F5（開服時的通道由 §12 的終局處理）。來源 ticket 涵蓋 COLLAPSING 的來源艙，所以 COLLAPSING 在同一個 instance 內不會卸載；它的觸發只來自重啟。</li>
     * <li>隔離中、而上一次推導在 PREPARE：PREPARE 暫停，原艙門交還為一般開門（一次，{@link #releasePrepareDoor}）。隔離解除之後依原進度續做，{@link #prepareDoor}
     *     重新判斷量子膜。</li>
     * </ul>
     */
    void watchDoor(SessionRecoveryRecord record, boolean quarantined) {
        var sid = record.sessionUuid();
        var view = service.journal.collapseView(sid).orElse(record);
        if (view.state() != SessionState.COLLAPSING && view.state() != SessionState.SEALED) return;
        var track = service.track(sid);
        var world = service.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, view.origin().worldKey()));
        boolean loaded = world != null && CollapseService.neighborhoodLoaded(world, new ChamberFrame(view.origin().controllerPos(), view.origin().facing()));
        boolean startupSession = service.startupSessions.contains(sid);
        boolean reloaded = doorReloaded(track.doorLoaded, loaded, startupSession);
        var cause = reapplyCause(startupSession, track.doorSeenLoaded);
        track.doorLoaded = loaded;
        if (loaded) track.doorSeenLoaded = true;
        if (quarantined && track.preparing && !track.doorReleased) {
            releasePrepareDoor(view, track);
            LOGGER.info(LOG_MARKER, "session 隔離中（H3 的收斂），PREPARE 暫停：原艙門改為一般開門，隔離解除之後依原進度續做：session={} chamber={}", sid, view.chamberUuid());
        }
        if (reloaded) reapplyDoor(view, cause);
    }

    /**
     * 重新套用的一次性觸發（純函式，F4b-3 補充第 5 點）。{@code previous} 是本 instance 上一次觀察到的「來源艙已載入」（null 表示還沒觀察過），{@code loaded} 是這次的觀察，
     * {@code startupSession} 是開服 attach 當下就在 journal。觸發只有兩種：看過沒有載入、這次已載入；或還沒觀察過（重啟之後的第一次觀察）而且是開服時就在的 session。
     * 執行期間才出現、一直載入著的 session 不觸發。
     */
    static boolean doorReloaded(Boolean previous, boolean loaded, boolean startupSession) {
        return loaded && (previous == null ? startupSession : !previous);
    }

    /**
     * 重新套用的原因在 log 裡的字句（純函式，F4b-3 fix round 1，Nit N-4）：開服 attach 當下就在 journal、而本 instance 還沒看過來源艙已載入（{@code seenLoaded}）的 session，
     * 是重啟之後第一次載入（重啟之後來源 chunk 是非同步載入的，第一次觀察常常還沒載入，所以不只看是不是第一次觀察）；其餘是本 instance 看過它沒有載入之後的重新載入。
     */
    static String reapplyCause(boolean startupSession, boolean seenLoaded) {
        return startupSession && !seenLoaded ? "重啟之後第一次載入：開服 attach 當下就在 journal 的 session，本 instance 第一次觀察到來源艙已載入"
                : "重新載入：本 instance 看過來源艙沒有載入，這次已載入";
    }

    /** 重新套用的目標（純函式）：SEALED 是關門（門口有 entity 時一般開門）；COLLAPSING 是把帶著量子膜的門改為一般開門。 */
    static DoorGoal reapplyGoal(SessionState viewState) {
        return switch (viewState) {
            case SEALED -> DoorGoal.CLOSE_ON_SEALED;
            case COLLAPSING -> DoorGoal.NORMALIZE;
            case ARMING, SUPERPOSITION, RETURNING, MEASURED, PASSAGE_READY -> throw new IllegalArgumentException("只有 COLLAPSING 與 SEALED 由塌縮流程重新套用原艙門：" + viewState);
        };
    }

    /**
     * 排入下一個 tick 的重新套用（{@link #watchDoor}；{@code cause} 是 {@link #reapplyCause} 的字句）；已有待套用的門時只把它延到下一個 tick。排入（或延後）記 INFO：
     * 它記下的「下一個 tick（tick N）」是「不在 chunk 載入的當下寫方塊」的證據（GameTest 以它比對套用的 tick）；完成時只有實際改了門（APPLIED）才記 INFO（{@link #applyDoor}）。
     */
    private void reapplyDoor(SessionRecoveryRecord view, String cause) {
        var existing = doors.get(view.chamberUuid());
        if (existing != null) {
            existing.deferTo(service.now + 1);
            existing.reapplied = true;
            LOGGER.info(LOG_MARKER, "來源艙{}：已有待套用的原艙門（{}），延到下一個 tick（tick {}）套用，不在 chunk 載入的當下寫方塊：session={} chamber={}", cause,
                    existing.goal, service.now + 1, view.sessionUuid(), view.chamberUuid());
            return;
        }
        var goal = reapplyGoal(view.state());
        queueDoor(view, 1, goal).reapplied = true;
        LOGGER.info(LOG_MARKER, "來源艙{}：下一個 tick（tick {}）依 collapse view 重新套用原艙門（{}），不在 chunk 載入的當下寫方塊：session={} chamber={} 狀態={}", cause, service.now + 1,
                goal, view.sessionUuid(), view.chamberUuid(), view.state());
    }

    /** 玩家與這座原艙的關係（{@link #doorAction}）。 */
    enum DoorUser {
        /** 這個 session 的原參與者（不論在艙內或艙外；攔截只看原艙這一側的門）。 */
        PARTICIPANT,
        /** 非參與者，bbox 與 interior 相交（在艙內，含跨在門口的人）。 */
        INSIDER,
        /** 非參與者，在艙外。 */
        OUTSIDER
    }

    /** 原艙門的攔截對一次開關門的處置（{@link #doorAction}）。 */
    enum DoorAction {
        /** 照一般門的邏輯切換（不屬於塌縮流程，或門本來就是一般狀態的階段）。 */
        PASS,
        /** 拒絕、不改門。 */
        REFUSE,
        /** 艙內的非參與者：門改為一般開門讓他離開，塌縮不結束、不改變階段。 */
        OPEN_FOR_INSIDER,
        /** PREPARE 期間參與者開關門：先 checked 終局、再關門（門口有 entity 時一般開門）。 */
        END_AND_CLOSE
    }

    /**
     * 原艙門的攔截規則（純函式；spec §7 :336、§7.3「PREPARE 期間的開關門」，F4b-3 補充第 2 點）。{@code prepareOwnsDoor} 是上一次推導在 PREPARE，而且 PREPARE 沒有
     * 暫停（沒有被拒而待重試的 SEALED 寫入、沒有隔離）：
     * <ul>
     * <li>COLLAPSING、目的地是 UNIVERSE、PREPARE 擁有原艙門：roll-forward 尚未確認 → 拒絕；參與者 → 終局再關門；艙內的非參與者 → 一般開門；艙外的非參與者 → 拒絕。</li>
     * <li>PASSAGE_READY：roll-forward 尚未確認 → 拒絕（§7 :336）；其餘屬 F5（§10.5），目前照一般門的邏輯（F5 之前執行期間不會出現 PASSAGE_READY，開服時的由 §12 在第一個
     *     tick 終局）。</li>
     * <li>其他（SEALED、門本來就是一般狀態的階段、SOURCE、M1–M4 的狀態）→ 照一般門的邏輯（§11.2：SEALED 時任何玩家都可以照一般方式開門；§3 詮釋 3）。</li>
     * </ul>
     */
    static DoorAction doorAction(SessionState viewState, boolean universe, boolean rollForwardPending, boolean prepareOwnsDoor, DoorUser user) {
        Objects.requireNonNull(viewState, "viewState");
        Objects.requireNonNull(user, "user");
        if (viewState == SessionState.PASSAGE_READY) return rollForwardPending ? DoorAction.REFUSE : DoorAction.PASS;
        if (viewState != SessionState.COLLAPSING || !universe || !prepareOwnsDoor) return DoorAction.PASS;
        if (rollForwardPending) return DoorAction.REFUSE;
        return switch (user) {
            case PARTICIPANT -> DoorAction.END_AND_CLOSE;
            case INSIDER -> DoorAction.OPEN_FOR_INSIDER;
            case OUTSIDER -> DoorAction.REFUSE;
        };
    }

    /**
     * 一次開關原艙門的攔截（玩家互動的封包處理裡、server thread 上，排在門切換之前）。找出以這座艙為原艙的 session，依 collapse view 與 {@link #doorAction} 處置。
     * 不丟出例外（F4b-3 fix round 1，quality Q-I1、spec S-M2）：
     * <ul>
     * <li>只有 journal 不健康（{@code requireHealthy} 不通過）時不攔截、照一般門的邏輯（記 DEBUG）：這時塌縮流程本身也不推進（{@link CollapseService#tick} 記 tick 層級的 WARN），而
     *     M1–M4 的門行為不得因此改變；照一般門切換只會讓門變成一般狀態，不會把人困住。</li>
     * <li>其他非預期的例外（找出所屬 session 之後的判斷，或處置途中的終局、改門）一律拒絕、不改門，不交給門服務（PREPARE 擁有原艙門時不得經
     *     {@code ChamberDoorService.toggle}），以 {@link CollapseService#warn} 依 (session, 處置, 原因) 去重記 WARN（{@link #doorUseKey}；判斷途中的例外記成「判斷」）；
     *     這個 session 之後以同一個處置沒有例外地完成時，只清除這個處置的去重鍵（F5-0，F4b-3 quality M-1：別人以別的處置成功，例如艙內的非參與者開門，不讓參與者的處置
     *     失敗再記一次），所以同一個處置同一原因「失敗 → 成功 → 再失敗」會再記一次。「判斷」沒有對應的處置，它的鍵不會被處置的成功清除，同一原因只記一次（不同原因照常各記一次），
     *     直到這個 session 的簿記移除。PREPARE 的門仍由每個 tick 的 {@link #prepareDoor} 維持（艙內有非參與者時照樣改為一般開門）。</li>
     * <li>找出所屬 session 之前的例外（journal 健康時 {@link #sessionAt} 只讀記憶體內的 record，不會發生）：沒有 session 可以歸屬，也無法判斷門是否屬於塌縮流程，
     *     拒絕並記 WARN（不去重）。</li>
     * </ul>
     * SEALED 的門照一般門的邏輯時（§11.2：任何玩家都可以照一般方式開門），同時取消這座艙還沒套用的關門（{@link #cancelSealedClose}，F4b-3 fix round 1，quality Q-M2）。
     */
    ChamberCollapsePort.DoorUse door(ServerWorld world, ChamberFrame frame, PlayerEntity player) {
        if (!service.server.isOnThread()) return ChamberCollapsePort.DoorUse.PASS;
        try {
            service.journal = SessionRecoveryState.get(service.server);
            service.journal.requireHealthy();
        } catch (RuntimeException unhealthy) {
            LOGGER.debug(TICK_LOG_MARKER, "原艙門的攔截讀不到 journal（不健康），這次不攔截、照一般門的邏輯：frame={}：{}", frame, FailureText.describe(unhealthy));
            return ChamberCollapsePort.DoorUse.PASS;
        }
        SessionRecoveryRecord view = null;
        DoorAction action = null;
        try {
            view = sessionAt(world, frame);
            if (view == null) return ChamberCollapsePort.DoorUse.PASS;
            // 沒有 SuperpositionSessionManager 時這個 server 沒有塌縮流程（tick 也不推進）。
            if (!service.refreshContext()) return ChamberCollapsePort.DoorUse.PASS;
            var sid = view.sessionUuid();
            var track = service.tracks.get(sid);
            boolean owns = track != null && track.preparing && track.pendingSeal == null && !service.journal.quarantinedSessions().contains(sid);
            boolean universe = view.state().collapseState() && view.collapse().destination() instanceof CollapseDestination.Universe;
            var user = participates(view, player.getUuid()) ? DoorUser.PARTICIPANT
                    : !player.isSpectator() && player.getBoundingBox().intersects(ChamberGeometry.interiorBox(frame)) ? DoorUser.INSIDER : DoorUser.OUTSIDER;
            action = doorAction(view.state(), universe, service.journal.rollForwardPending(sid), owns, user);
            var use = switch (action) {
                case PASS -> {
                    if (view.state() == SessionState.SEALED) cancelSealedClose(view, player);
                    yield ChamberCollapsePort.DoorUse.PASS;
                }
                case REFUSE -> {
                    LOGGER.debug(LOG_MARKER, "原艙門的開關被拒、不改門：session={} chamber={} 玩家={} 身分={}", sid, view.chamberUuid(), player.getUuid(), user);
                    yield ChamberCollapsePort.DoorUse.REFUSED;
                }
                case OPEN_FOR_INSIDER -> openForInsider(view, world, frame, player);
                case END_AND_CLOSE -> endByParticipant(view, track, player);
            };
            var after = service.tracks.get(sid);
            if (after != null) after.warnings.remove(doorUseKey(action));
            return use;
        } catch (RuntimeException failure) {
            if (view == null) {
                LOGGER.warn(LOG_MARKER, "原艙門的攔截在找出所屬 session 時丟出例外（journal 健康），這次拒絕、不改門：frame={} 玩家={}：{}", frame, player.getUuid(),
                        FailureText.describe(failure), failure);
            } else {
                service.warn(view.sessionUuid(), doorUseKey(action), failure, null,
                        "這次拒絕、不改門（不交給門服務；PREPARE 的門仍由每個 tick 判斷），之後的開關門重新判斷");
            }
            return ChamberCollapsePort.DoorUse.REFUSED;
        }
    }

    /**
     * 原艙門的攔截失敗的 WARN 去重鍵（也是 WARN 的寫入種類；純函式，F5-0，F4b-3 quality M-1）：依處置區分，判斷途中（還沒有處置，{@code action} 為 null）是「判斷」。
     * 例如「原艙門的攔截（END_AND_CLOSE）」。
     */
    static String doorUseKey(DoorAction action) {
        return DOOR_USE + "（" + (action == null ? "判斷" : action.name()) + "）";
    }

    /**
     * SEALED 的原艙門被玩家照一般方式使用（spec §11.2、§7.3 :404「玩家打開之後不會被關回去」；F4b-3 fix round 1，quality Q-M2）：移除這座艙由同一個 session 排入、
     * 還沒套用的關門（{@link DoorGoal#CLOSE_ON_SEALED}：進入 SEALED 時排入，或 chunk 重新載入之後的重新套用；例如還在等門口的 entity storage 載入、chunk 不是 FULL、
     * 或在退避中）。之後的門由玩家照一般方式開關；只有下一次真的重新載入（{@link #watchDoor}）才再依 collapse view 關門一次。在門切換之前呼叫：門切換本身被拒時
     * （例如門板不完整），門維持原狀，同樣不再由塌縮流程關上。
     */
    private void cancelSealedClose(SessionRecoveryRecord view, PlayerEntity player) {
        var pending = doors.get(view.chamberUuid());
        if (pending == null || pending.goal != DoorGoal.CLOSE_ON_SEALED || !pending.session.equals(view.sessionUuid())) return;
        doors.remove(view.chamberUuid());
        LOGGER.info(LOG_MARKER, "SEALED 的原艙門被玩家照一般方式使用：取消還沒套用的關門（{}），玩家開關的門不會被關回去：session={} chamber={} 玩家={}",
                pending.reapplied ? "chunk 重新載入之後的重新套用" : "進入 SEALED 時排入", view.sessionUuid(), view.chamberUuid(), player.getUuid());
    }

    /** 以這座艙為原艙的 session 的 collapse view（依 world key、Controller 位置與朝向）；沒有時為 null。 */
    private SessionRecoveryRecord sessionAt(ServerWorld world, ChamberFrame frame) {
        var key = world.getRegistryKey().getValue();
        for (var record : service.journal.flushedRecords().values()) {
            var origin = record.origin();
            if (origin.worldKey().equals(key) && origin.controllerPos().equals(frame.controllerPos()) && origin.facing() == frame.outwardFacing())
                return service.journal.collapseView(record.sessionUuid()).orElse(record);
        }
        return null;
    }

    /**
     * 艙內的非參與者開關門（§7.3）：門改為一般開門讓他離開，塌縮不結束、不改變階段；他離開之後 {@link #prepareDoor} 依套用量子膜的條件重新判斷。依 {@code applyTarget}
     * 的結果回傳（F4b-3 fix round 1）：APPLIED（記 INFO）與 UNCHANGED（門已是一般開門，記 DEBUG）是 HANDLED；其他（NOT_LOADED、DOOR_INCOMPLETE、ROLLED_BACK、
     * ROLLBACK_FAILED）門沒有改，回 REFUSED、記 DEBUG：艙內有非參與者時，下一個 tick 的 {@link #prepareDoor} 照樣把門改為一般開門（失敗時由它依原因去重記 WARN、退避）。
     */
    private ChamberCollapsePort.DoorUse openForInsider(SessionRecoveryRecord view, ServerWorld world, ChamberFrame frame, PlayerEntity player) {
        var outcome = new ChamberDoorService().applyTarget(world, frame, ChamberDoorService.DoorState.OPEN).outcome();
        switch (outcome) {
            case APPLIED -> {
                LOGGER.info(LOG_MARKER, "PREPARE 期間艙內的非參與者開關門：原艙門改為一般開門，塌縮不結束；他離開之後依套用量子膜的條件重新判斷：session={} chamber={} 玩家={}",
                        view.sessionUuid(), view.chamberUuid(), player.getUuid());
                return ChamberCollapsePort.DoorUse.HANDLED;
            }
            case UNCHANGED -> {
                LOGGER.debug(LOG_MARKER, "PREPARE 期間艙內的非參與者開關門：原艙門已是一般開門，塌縮不結束：session={} chamber={} 玩家={}", view.sessionUuid(),
                        view.chamberUuid(), player.getUuid());
                return ChamberCollapsePort.DoorUse.HANDLED;
            }
            default -> {
                LOGGER.debug(LOG_MARKER, "PREPARE 期間艙內的非參與者開關門，但原艙門沒有改（{}）：這次拒絕；下一個 tick 依艙內有非參與者改為一般開門：session={} chamber={} 玩家={}",
                        outcome, view.sessionUuid(), view.chamberUuid(), player.getUuid());
                return ChamberCollapsePort.DoorUse.REFUSED;
            }
        }
    }

    /**
     * PREPARE 期間參與者在原艙這一側開關門（§7.3、設計 §20.4）：先 checked 終局（{@code PREPARE_DOOR_CLOSED}；不重抽，目的宇宙在 RESOLVE 已配置、物化並 discovered），
     * 再把門改為關閉（門口有 entity 時一般開門，{@link DoorGoal#CLOSE_AFTER_TERMINAL}），之後刷新 Controller。終局之後釋放 runtime、來源與目的世界的 ticket
     * （{@link CollapseService#terminated}）。寫入前 journal 不得有未 checked 的 authority（{@code recoveryWritesSafe()}，比照 {@link CollapseService#tick}：終局的寫出會把它一併帶出去；F4b-3 fix
     * round 1，quality Q-M1），current 必須等於 flushed，也要在 per-session 退避期之外；不符時拒絕、不改門（記 DEBUG）。終局沒有完成時記 WARN、退避，拒絕、不改門（§5）。
     */
    private ChamberCollapsePort.DoorUse endByParticipant(SessionRecoveryRecord view, Track track, PlayerEntity player) {
        var sid = view.sessionUuid();
        var wait = !service.journal.recoveryWritesSafe() ? "journal 有未 checked 的 authority（終局的寫出會把它帶出去）"
                : !view.equals(service.journal.records().get(sid)) ? "current 不等於 flushed"
                : !track.backoff.ready(service.now) ? "在 per-session 退避期內" : null;
        if (wait != null) {
            LOGGER.debug(LOG_MARKER, "PREPARE 期間參與者關門，但{}：這次拒絕、不改門：session={} 玩家={}", wait, sid, player.getUuid());
            return ChamberCollapsePort.DoorUse.REFUSED;
        }
        var result = service.journal.terminate(service.server, view, TerminalOrigin.PREPARE_DOOR_CLOSED);
        if (!result.removed()) {
            service.terminalFailed(view, track, "PREPARE 期間參與者關門的終局", result);
            return ChamberCollapsePort.DoorUse.REFUSED;
        }
        service.terminated(view);
        LOGGER.info(LOG_MARKER, "PREPARE 期間參與者關門，塌縮已結束（checked 終局，正常結束；不重抽，目的宇宙保留）：session={} chamber={} 目的地={} 玩家={}", sid,
                view.chamberUuid(), CollapseService.describe(view.collapse().destination()), player.getUuid());
        queueDoor(view, 0, DoorGoal.CLOSE_AFTER_TERMINAL);
        service.refresh(view);
        return ChamberCollapsePort.DoorUse.HANDLED;
    }

    // ---- 待套用的門（F3d 起；F4a 加上 SEALED 的關門，F4b-3 加上 PREPARE 期間的目標與重新套用） ----

    /**
     * 每個 tick 嘗試套用每一扇待套用的門（{@link CollapseService#tick} 在 session 的推進之後呼叫，不論 {@code recoveryWritesSafe()}；F5-0 從 {@code tick} 搬來，行為不變）。
     * 套用丟出的例外依門記下的狀態記錄並退避（{@link #applyFailure}），不影響其他的門，也不會從 {@code END_SERVER_TICK} 拋出。
     */
    void applyPending() {
        for (var door : List.copyOf(doors.values())) {
            try {
                applyDoor(door);
            } catch (RuntimeException failure) {
                applyFailure(door, failure, service.now);
            }
        }
    }

    /** 終局之後把原艙門改為一般開門（open=true、membrane=none），經 {@link ChamberDoorService#applyTarget}；{@code delay} 個 tick 之後才第一次嘗試。 */
    void openDoor(SessionRecoveryRecord record, int delay) {
        queueDoor(record, delay, DoorGoal.OPEN_AFTER_TERMINAL);
    }

    /** 排入待套用的原艙門（以 chamber 為 key，取代同一座艙之前排入的門），回傳它；{@code delay} 為 0 時立即嘗試一次。 */
    PendingDoor queueDoor(SessionRecoveryRecord record, int delay, DoorGoal goal) {
        var origin = record.origin();
        var door = new PendingDoor(record.chamberUuid(), record.sessionUuid(), RegistryKey.of(RegistryKeys.WORLD, origin.worldKey()),
                new ChamberFrame(origin.controllerPos(), origin.facing()), service.now + delay, goal);
        doors.put(record.chamberUuid(), door);
        if (delay == 0) {
            try {
                applyDoor(door);
            } catch (RuntimeException failure) {
                applyFailure(door, failure, service.now);
            }
        }
        return door;
    }

    /**
     * 依 {@link ChamberDoorService.ApplyResult.Outcome} 處理：APPLIED／UNCHANGED 完成；NOT_LOADED 等之後的 tick（不強制載入）；DOOR_INCOMPLETE
     * 是結構性的，記一次並退避、等結構恢復；ROLLED_BACK／ROLLBACK_FAILED 下一個 tick 重試一次，再失敗才退避（ROLLBACK_FAILED 已設
     * activationBlocked 並記 ERROR，之後一次 APPLIED 會解除）。
     * <ul>
     * <li>終局之後的一般開門：原艙已開始新的 session（presence 不是 NONE）時，門屬於新的 session，不再套用。</li>
     * <li>進入 SEALED 的關門（§11.2）：這個 session 的 collapse view 已不是 SEALED（重試回到 COLLAPSING，或已終局）時不再套用。每次嘗試都重新
     *     檢查門口：25 格門內有任何 entity 的 bbox 時改為一般開門（§7.3），不把門關在任何人身上。</li>
     * <li>PREPARE 期間參與者關門的終局之後的關門（F4b-3）：同終局之後的一般開門，原艙已開始新的 session 時不再套用；門口的規則同上。</li>
     * <li>會讓門變成有碰撞的兩種關門（SEALED、終局之後），都要等門口的 entity storage 載入之後才判斷門口（重啟或 chunk 重新載入之後 entity 是非同步載入的）；
     *     等待期間不寫入、不退避。</li>
     * <li>PREPARE 結束或暫停之後的一般開門（F4b-3）：一律套用（沒有碰撞）；PREPARE 再次擁有門時由 {@link #prepareDoor} 移除。</li>
     * <li>重新套用的量子膜正規化（F4b-3）：COLLAPSING 已不是這個 session 的 collapse view 時不再套用；門沒有量子膜時不寫入、完成；有時改為一般開門。</li>
     * </ul>
     * 由重新套用排入（或延後）的門（{@code reapplied}）完成時另記一則 INFO（目標的結果與 tick），作為「不在 chunk 載入的當下寫方塊」的證據。
     */
    private void applyDoor(PendingDoor door) {
        if (service.now < door.notBefore || !door.backoff.ready(service.now)) return;
        if ((door.goal == DoorGoal.OPEN_AFTER_TERMINAL || door.goal == DoorGoal.CLOSE_AFTER_TERMINAL)
                && service.sessions.presence(service.server, door.chamber) != ChamberSessionGateway.Presence.NONE) {
            doors.remove(door.chamber);
            LOGGER.info(LOG_MARKER, door.goal == DoorGoal.OPEN_AFTER_TERMINAL ? "原艙已開始新的 session，不再把門改為一般開門：chamber={} 先前 session={}"
                    : "原艙已開始新的 session，不再套用終局之後的原艙門：chamber={} 先前 session={}", door.chamber, door.session);
            return;
        }
        if (door.goal == DoorGoal.CLOSE_ON_SEALED
                && service.journal.collapseView(door.session).filter(view -> view.state() == SessionState.SEALED).isEmpty()) {
            doors.remove(door.chamber);
            LOGGER.info(LOG_MARKER, "session 已不是 SEALED，不再套用封存時的原艙門：chamber={} session={}", door.chamber, door.session);
            return;
        }
        if (door.goal == DoorGoal.NORMALIZE
                && service.journal.collapseView(door.session).filter(view -> view.state() == SessionState.COLLAPSING).isEmpty()) {
            doors.remove(door.chamber);
            return;
        }
        var world = service.server.getWorld(door.world);
        if (world == null) return;
        ChamberDoorService.DoorState target;
        switch (door.goal) {
            case OPEN_AFTER_TERMINAL, OPEN_ON_LEAVE -> target = ChamberDoorService.DoorState.OPEN;
            case CLOSE_ON_SEALED, CLOSE_AFTER_TERMINAL -> {
                if (!doorwayEntitiesLoaded(world, door.frame)) return;
                target = sealedDoor(doorwayOccupied(world, door.frame));
            }
            case NORMALIZE -> {
                if (!CollapseService.neighborhoodLoaded(world, door.frame)) return;
                if (!doorHasMembrane(world, door.frame)) {
                    doors.remove(door.chamber);
                    if (door.reapplied) LOGGER.debug(LOG_MARKER, "原艙門的重新套用不需要改門（{}：門沒有量子膜，tick {}）：chamber={} session={}", door.goal, service.now,
                            door.chamber, door.session);
                    return;
                }
                target = ChamberDoorService.DoorState.OPEN;
            }
            case PREPARE_MEMBRANE -> throw new IllegalStateException("PREPARE 的原艙門由 prepareDoor 每個 tick 套用，不排入待套用的門");
            default -> throw new IllegalStateException("未知的原艙門目標：" + door.goal);
        }
        var result = new ChamberDoorService().applyTarget(world, door.frame, target);
        if (!applyOutcome(door, result.outcome(), service.now)) return;
        doors.remove(door.chamber);
        if (!door.reapplied) return;
        // F4b-3 fix round 1（Nit N-3）：實際改了門才記 INFO；門已是目標（UNCHANGED）記 DEBUG。
        if (result.outcome() == ChamberDoorService.ApplyResult.Outcome.APPLIED) {
            LOGGER.info(LOG_MARKER, "原艙門已依 collapse view 重新套用（{}：{}，tick {}）：chamber={} session={}", door.goal, result.outcome(), service.now, door.chamber, door.session);
        } else {
            LOGGER.debug(LOG_MARKER, "原艙門的重新套用不需要改門（{}：{}，tick {}）：chamber={} session={}", door.goal, result.outcome(), service.now, door.chamber, door.session);
        }
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

    /** 套用門時丟出例外（{@link #applyPending} 與 {@link #queueDoor} 的 catch 共用）：依門記下的狀態推導 {@link #doorFailure}，經 {@link #recordDoorStep} 記錄並退避。 */
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
     * 待套用門的目標：終局之後的一般開門，進入 SEALED 時的關門（門口有 entity 時一般開門）；F4b-3 另加 PREPARE 期間參與者關門的終局之後的關門、PREPARE 結束或暫停
     * 之後的一般開門、重新套用時的量子膜正規化，以及 PREPARE 每個 tick 的原艙門（{@link CollapseService.Track#membrane}，不排入待套用的門）。{@code pending}／{@code done} 是 log 的字句。
     */
    enum DoorGoal {
        OPEN_AFTER_TERMINAL("終局之後原艙門還沒改為一般開門", "原艙門已改為一般開門"),
        CLOSE_ON_SEALED("進入 SEALED 之後原艙門還沒套用（關門；門口有 entity 時一般開門）", "進入 SEALED 之後的原艙門已套用"),
        CLOSE_AFTER_TERMINAL("PREPARE 期間參與者關門：終局之後原艙門還沒套用（關門；門口有 entity 時一般開門）", "PREPARE 期間參與者關門：終局之後的原艙門已套用"),
        OPEN_ON_LEAVE("PREPARE 結束或暫停之後原艙門還沒改為一般開門", "PREPARE 結束或暫停之後原艙門已改為一般開門"),
        NORMALIZE("重新套用：原艙門的量子膜還沒改為一般開門", "重新套用：原艙門的量子膜已改為一般開門"),
        PREPARE_MEMBRANE("PREPARE 的原艙門還沒套用（sealed 量子膜，或門口有人、艙內有非參與者時的一般開門）", "PREPARE 的原艙門已套用");

        final String pending, done;

        DoorGoal(String pending, String done) { this.pending = pending; this.done = done; }
    }

    /**
     * 還沒套用成功的原艙門（{@link DoorGoal}）。只記下狀態（最近一次記下的原因、是否已立即重試過一次、退避）；要不要記 WARN 由 {@link #recordDoorStep}
     * 依 {@link DoorStep#warn} 決定。
     */
    static final class PendingDoor {
        final UUID chamber, session;
        final RegistryKey<World> world;
        final ChamberFrame frame;
        /** 第一次嘗試的 server tick；重新套用時可以延後（{@link #deferTo}）。 */
        int notBefore;
        final DoorGoal goal;
        final CollapseBackoff backoff = new CollapseBackoff();
        String warned;
        boolean retried;
        /** 由重新套用排入或延後（{@link #watchDoor}，F4b-3）：完成時另記一則 INFO。 */
        boolean reapplied;

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

        /** 最早在 {@code tick} 才嘗試（只會往後延）。 */
        void deferTo(int tick) {
            notBefore = Math.max(notBefore, tick);
        }
    }
}
