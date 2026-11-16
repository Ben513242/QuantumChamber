package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseService.LOG_MARKER;
import static dev.quantumchamber.collapse.CollapseService.TICK_LOG_MARKER;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberControllerBlockEntity;
import dev.quantumchamber.chamber.ChamberDetector;
import dev.quantumchamber.chamber.ChamberDoorService;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.WorldChamberBlockView;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.ChunkSaveCheckpoint;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.HomeReceiptStore;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.AllCrossed;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryStopped;
import dev.quantumchamber.persistence.HomeSessionRecord.Expired;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.ParticipantClosed;
import dev.quantumchamber.persistence.HomeSessionRecord.RestartClosed;
import dev.quantumchamber.persistence.HomeSessionRecord.RootStop;
import dev.quantumchamber.persistence.HomeSessionRecord.Seal;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.UniverseLifecycleService;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回程旅程的唯一終局 owner（M7 spec §5.3、§9.2、§10）。
 * <ul>
 * <li><b>決策</b>：{@link #decide} 依共同矩陣與優先序（已凍結的 CLOSE → 已 latch 的原艙停止 → 全跨／開服收尾／到期）決定可 mint 的結案；參與者關門只經
 *     {@link #onParticipantClose}，B 停止只經 {@link #onEntryStopped}，無事件的 {@link #step} 不補造 actor、時間或訊號。永久封存（TRR、ENTRY_AUTHORITY_INVALID、
 *     AUTHORITY_CORRUPT）一律不歸檔。RESTART_CLOSED 只給開服當下由 {@link #startup} 擷取的集合，持本 instance 的 UUID。</li>
 * <li><b>凍結</b>：NONE→CLOSE 只經 {@link SessionRecoveryState#latchHomeClose} 與本類在事件 guard 內建立的一次性 {@link CloseMint}；未 checked 前只收斂、不改門。</li>
 * <li><b>交接</b>（§5.3 :172）：exact B 依 DoorGoal 實際改門（普通關門也會改，不以膜已是 NONE 當完成）→ B 相關 chunks 原生 checkpoint → 發佈或 exact 驗既有單 SID 收據
 *     → 同一 tick 再確定門仍是目標 → 以一次性 {@link HomeClosureProof} checked 移除熱列 → 釋放原艙票。任何一步未知或錯配都保留 CLOSE 與 busy，下一個 tick 再試；
 *     不操作 A，也不掃歷史。</li>
 * </ul>
 * 只在 server thread 執行；每一則 log 都帶 {@link CollapseService#LOG_MARKER}（不屬於任何 session 的帶 {@link CollapseService#TICK_LOG_MARKER}）。
 */
public final class HomeClosure {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    public enum DoorPhase { PREPARE, PASSAGE }
    public enum EntrySignal { LOW, DISABLED, INVALID }
    private static final Map<MinecraftServer, Context> CONTEXTS = new ConcurrentHashMap<>();

    private HomeClosure() {}

    /** 每個 server instance 的 startup owner 與每個 SID 的退避／去重。 */
    private static final class Context {
        final UUID instance = UUID.randomUUID();
        final Set<UUID> startup;
        final Map<UUID, CollapseBackoff> backoff = new HashMap<>();
        final Map<UUID, String> warned = new HashMap<>();
        Context(Set<UUID> startup) { this.startup = Set.copyOf(startup); }
    }

    /** 決策需要的當下事實；{@code startupInstance} 只在該 SID 屬於開服當下的 READY 集合時存在。 */
    public record Facts(long now, Optional<UUID> startupInstance, boolean entryRecovered) {
        public Facts { Objects.requireNonNull(startupInstance, "startupInstance"); }
    }

    /** 一次性的結案提交權威：只由本類在事件 guard 內建立。 */
    public static final class CloseMint {
        private final MinecraftServer server;
        private final HomeSessionRecord expected, next;
        private boolean used;
        private CloseMint(MinecraftServer server, HomeSessionRecord expected, HomeSessionRecord next) {
            this.server = server; this.expected = expected; this.next = next;
        }
        public HomeSessionRecord expected() { return expected; }
        public HomeSessionRecord next() { return next; }
        public boolean consume(MinecraftServer owner) {
            if (used || owner == null || owner != server) return false;
            used = true;
            return true;
        }
    }

    /** 一次性的移除權威：B 門、原生 checkpoint 與 exact 收據都已核對後，只由本類建立。 */
    public static final class HomeClosureProof {
        private final MinecraftServer server;
        private final HomeSessionRecord row;
        private boolean used;
        private HomeClosureProof(MinecraftServer server, HomeSessionRecord row) { this.server = server; this.row = row; }
        public boolean consume(MinecraftServer owner, HomeSessionRecord expected) {
            if (used || owner == null || owner != server || !row.equals(expected)) return false;
            used = true;
            return true;
        }
    }

    /** 開服接手（{@code SERVER_STARTED}）：擷取本 instance 開服當下 CloseIntent=NONE 的 READY 與曾 READY 的 AUTHORITY_UNREADABLE 封存。 */
    static void startup(MinecraftServer server) {
        var startup = new LinkedHashSet<UUID>();
        try {
            for (var row : SessionRecoveryState.get(server).allFlushedSessions().values())
                if (row instanceof HomeSessionRecord h && h.home().closeIntent() instanceof None && h.home().passageOpenedAtGameTime() >= 0
                        && (h.state() == SessionState.PASSAGE_READY
                        || h.state() == SessionState.SEALED && h.home().sealedReason().orElse(null) == Seal.AUTHORITY_UNREADABLE))
                    startup.add(h.sessionUuid());
        } catch (RuntimeException unreadable) {
            LOGGER.error(TICK_LOG_MARKER, "回程終局 owner 開服時讀不到 journal，本 instance 沒有開服收尾集合", unreadable);
        }
        CONTEXTS.put(server, new Context(startup));
    }

    static void release(MinecraftServer server) { CONTEXTS.remove(server); }

    /** 每個 tick 推進每一列 HOME（{@link HomeRootFence} 的 tick 之後）。 */
    static void tick(MinecraftServer server) {
        SessionRecoveryState journal;
        try { journal = SessionRecoveryState.get(server); journal.requireHealthy(); }
        catch (RuntimeException unhealthy) { return; }
        for (var row : List.copyOf(journal.allSessions().values())) {
            if (!(row instanceof HomeSessionRecord home)) continue;
            try { step(server, home); }
            catch (RuntimeException failure) { warn(server, home, "推進", "回程終局推進丟出例外，下一個 tick 重試：" + FailureText.describe(failure)); }
        }
    }

    // ---- 純決策（M7 spec §9.2 共同矩陣） ----

    /** 無事件的結案決策；回傳的 Close 一定通過 {@link HomeSessionRecord} 的正式矩陣。PARTICIPANT_CLOSED 與 ENTRY_STOPPED 永不從這裡產生。 */
    public static Optional<Close> decide(HomeSessionRecord row, Facts facts) {
        var h = row.home();
        if (!(h.closeIntent() instanceof None) || !(h.selection() instanceof Selected) || permanent(row) || !cleaned(row)) return Optional.empty();
        long now = facts.now();
        Close close = null;
        if (h.stopReason().isPresent()) {
            var reason = h.stopReason().get();
            close = new Close(CloseReason.valueOf(reason.name()), now, DoorGoal.OPEN, new RootStop(reason, h.stoppedAtGameTime()));
        } else if (row.state() == SessionState.PASSAGE_READY) {
            if (h.crossed().size() == row.participants().size()) close = new Close(CloseReason.ALL_CROSSED, now, DoorGoal.OPEN, new AllCrossed());
            else if (facts.startupInstance().isPresent())
                close = new Close(CloseReason.RESTART_CLOSED, now, DoorGoal.OPEN, new RestartClosed(facts.startupInstance().get(), now));
            else if (now >= h.passageExpiresAtGameTime()) close = new Close(CloseReason.EXPIRED, now, DoorGoal.OPEN, new Expired(now));
        } else if (row.state() == SessionState.SEALED && h.passageOpenedAtGameTime() >= 0 && facts.startupInstance().isPresent() && facts.entryRecovered()) {
            close = new Close(CloseReason.RESTART_CLOSED, now, DoorGoal.OPEN, new RestartClosed(facts.startupInstance().get(), now));
        }
        return valid(row, close);
    }

    /** 參與者關門的結案（§9.2 PARTICIPANT_CLOSED）：actor 屬 cohort、phase 對 state、時間就是當下事件；原艙停止或永久封存優先。 */
    public static Optional<Close> participantClose(HomeSessionRecord row, UUID actor, DoorPhase phase, long observedAt, long now) {
        var h = row.home();
        if (observedAt != now || !(h.closeIntent() instanceof None) || h.stopReason().isPresent() || permanent(row)
                || row.participants().stream().noneMatch(p -> p.playerUuid().equals(actor))) return Optional.empty();
        var expected = switch (row.state()) {
            case COLLAPSING -> DoorPhase.PREPARE;
            case PASSAGE_READY -> DoorPhase.PASSAGE;
            default -> null;
        };
        if (phase != expected) return Optional.empty();
        return valid(row, new Close(CloseReason.PARTICIPANT_CLOSED, observedAt, DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN,
                new ParticipantClosed(actor, HomeSessionRecord.Phase.valueOf(phase.name()), observedAt)));
    }

    private static Optional<Close> valid(HomeSessionRecord row, Close close) {
        if (close == null) return Optional.empty();
        try { row.withClose(close); return Optional.of(close); }
        catch (IllegalArgumentException invalid) { return Optional.empty(); }
    }

    private static boolean permanent(HomeSessionRecord row) {
        return row.state() == SessionState.SEALED && row.home().sealedReason().orElse(null) != Seal.AUTHORITY_UNREADABLE;
    }

    private static boolean cleaned(HomeSessionRecord row) {
        return row.spaceLeases().isEmpty() && row.participants().stream().allMatch(SessionRecoveryRecord.Participant::returned);
    }

    // ---- 事件與推進 ----

    /** 無事件的推進：收斂已凍結的 CLOSE，或由已核的全跨、到期、開服收尾與持久原艙 latch 決定原因。 */
    public static void step(MinecraftServer server, HomeSessionRecord row) {
        if (!server.isOnThread()) throw new IllegalStateException("回程終局只在 server thread 推進");
        var journal = SessionRecoveryState.get(server);
        var sid = row.sessionUuid();
        if (!settled(journal, row) || CorridorPageManager.forServer(server).operationInProgress(sid)) return;
        if (row.home().closeIntent() instanceof Close) { converge(server, journal, row); return; }
        if (HomeRootFence.queued(server, sid)) return;
        var context = CONTEXTS.get(server);
        var startup = context != null && context.startup.contains(sid) ? Optional.of(context.instance) : Optional.<UUID>empty();
        boolean recovered = row.state() == SessionState.SEALED && startup.isPresent()
                && HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint()) == HomeEndpointAuthority.EntryStatus.VALID;
        var close = decide(row, new Facts(server.getOverworld().getTime(), startup, recovered));
        if (close.isEmpty()) return;
        var latched = latch(server, journal, row, close.get());
        if (latched != null) converge(server, journal, latched);
    }

    /** 正式互動入口：參與者在 exact B 關門（PREPARE 或 PASSAGE）。在 guard 內核 actor 是在線、存活、非旁觀、位於 exact B 的 cohort 成員。 */
    public static ChamberCollapsePort.DoorUse onParticipantClose(MinecraftServer server, HomeSessionRecord row, ServerPlayerEntity actor,
            DoorPhase phase, long observedAt) {
        if (!server.isOnThread()) return ChamberCollapsePort.DoorUse.REFUSED;
        var journal = SessionRecoveryState.get(server);
        if (!settled(journal, row) || HomeRootFence.queued(server, row.sessionUuid()) || actor == null || actor.getServer() != server
                || actor.isRemoved() || !actor.isAlive() || actor.isSpectator()) return ChamberCollapsePort.DoorUse.REFUSED;
        var entry = exactEntry(server, row);
        if (entry == null || actor.getServerWorld() != entry) return ChamberCollapsePort.DoorUse.REFUSED;
        var close = participantClose(row, actor.getUuid(), phase, observedAt, server.getOverworld().getTime());
        if (close.isEmpty()) return ChamberCollapsePort.DoorUse.REFUSED;
        var latched = latch(server, journal, row, close.get());
        if (latched == null) return ChamberCollapsePort.DoorUse.REFUSED;
        converge(server, journal, latched);
        return ChamberCollapsePort.DoorUse.HANDLED;
    }

    /**
     * 正式訊號入口：READY 期間 exact B 斷電或結構失效（§9.2 ENTRY_STOPPED）。在 guard 內重讀 B 當下的訊號，與傳入的一致才 mint。
     * B 端的停止只有 {@link EntrySignal#LOW}（斷電）與 {@link EntrySignal#INVALID}（結構失效）。{@link EntrySignal#DISABLED} 依使用者 2026-11-17 裁示
     * 當成不會發生，一律拒絕、不 mint：投影艙沒有停用開關（{@code ChamberMaintenanceInteraction.toggleEnabled} 要求原艙身分）。記錄格式仍保留
     * {@code HomeSessionRecord.Signal.DISABLED}，只為舊資料相容。
     */
    public static void onEntryStopped(MinecraftServer server, HomeSessionRecord row, ServerWorld entry, EntrySignal signal, long observedAt) {
        if (!server.isOnThread() || row.state() != SessionState.PASSAGE_READY || signal == null || signal == EntrySignal.DISABLED) return;
        var journal = SessionRecoveryState.get(server);
        if (!settled(journal, row) || HomeRootFence.queued(server, row.sessionUuid()) || entry == null || exactEntry(server, row) != entry) return;
        var h = row.home();
        if (observedAt != server.getOverworld().getTime() || !(h.closeIntent() instanceof None) || h.stopReason().isPresent()) return;
        if (entrySignal(entry, new ChamberFrame(row.entryEndpoint().controllerPos(), row.entryEndpoint().facing())).orElse(null) != signal) return;
        var close = valid(row, new Close(CloseReason.ENTRY_STOPPED, observedAt, DoorGoal.OPEN,
                new EntryStopped(HomeSessionRecord.Signal.valueOf(signal.name()), observedAt)));
        if (close.isEmpty()) return;
        var latched = latch(server, journal, row, close.get());
        if (latched != null) converge(server, journal, latched);
    }

    /** exact B 當下的停止訊號：結構或 Controller 不符為 INVALID，沒有即時紅石輸入為 LOW；鄰域未 FULL 或正常時為空。 */
    private static Optional<EntrySignal> entrySignal(ServerWorld world, ChamberFrame frame) {
        if (!CollapseService.neighborhoodLoaded(world, frame)) return Optional.empty();
        var pos = frame.controllerPos();
        if (!(world.getBlockEntity(pos) instanceof ChamberControllerBlockEntity controller) || controller.instanceKind() != ChamberInstanceKind.PROJECTION
                || !new ChamberDetector().validate(new WorldChamberBlockView(world), frame).valid()) return Optional.of(EntrySignal.INVALID);
        return world.isReceivingRedstonePower(pos) ? Optional.empty() : Optional.of(EntrySignal.LOW);
    }

    private static ServerWorld exactEntry(MinecraftServer server, HomeSessionRecord row) {
        var entry = row.entryEndpoint();
        var world = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, entry.worldKey()));
        return world != null && UniverseLifecycleService.resolveActive(server, entry.universeId()).orElse(null) == world ? world : null;
    }

    private static boolean settled(SessionRecoveryState journal, HomeSessionRecord row) {
        var sid = row.sessionUuid();
        return row.equals(journal.allSessions().get(sid)) && row.equals(journal.allFlushedSessions().get(sid)) && !journal.rollForwardPending(sid)
                && !journal.pendingSessions().containsKey(sid) && !journal.quarantinedSessions().contains(sid);
    }

    /** checked 凍結 CLOSE；未 checked（含 roll-forward 待確認）時回 null，只收斂、不改門。 */
    private static HomeSessionRecord latch(MinecraftServer server, SessionRecoveryState journal, HomeSessionRecord row, Close close) {
        var next = row.withClose(close);
        var result = journal.latchHomeClose(server, new CloseMint(server, row, next));
        if (!result.committed()) {
            warn(server, row, "凍結", "回程結案尚未 checked（" + result.status() + "），只收斂、不改門："
                    + result.failure().map(FailureText::describe).orElse("無原因"));
            return null;
        }
        LOGGER.info(LOG_MARKER, "回程已 checked 凍結結案（{}，DoorGoal={}）：session={} chamber={}", close.reason(), close.doorGoal(),
                row.sessionUuid(), row.chamberUuid());
        return next;
    }

    /** §5.3 :172 的交接：exact B 改門 → 原生 checkpoint → 收據 → 再驗 → checked 移除 → 釋放票。未就緒時保留 CLOSE 與 busy，下一個 tick 再試。 */
    private static void converge(MinecraftServer server, SessionRecoveryState journal, HomeSessionRecord row) {
        var sid = row.sessionUuid();
        var context = CONTEXTS.get(server);
        var backoff = context == null ? new CollapseBackoff() : context.backoff.computeIfAbsent(sid, ignored -> new CollapseBackoff());
        if (!backoff.ready(server.getTicks())) return;
        var close = (Close) row.home().closeIntent();
        var status = HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint());
        if (status != HomeEndpointAuthority.EntryStatus.VALID) {
            if (status != HomeEndpointAuthority.EntryStatus.UNKNOWN) warn(server, row, "B", "B 端點不是 exact 合法投影（" + status + "），保留結案與占用");
            return;
        }
        var world = exactEntry(server, row);
        var frame = new ChamberFrame(row.entryEndpoint().controllerPos(), row.entryEndpoint().facing());
        if (world == null || !CollapseService.neighborhoodLoaded(world, frame)) return;
        ChamberDoorService.DoorState target;
        if (close.doorGoal() == DoorGoal.CLOSE_IF_CLEAR_ELSE_OPEN) {
            if (!OriginDoors.doorwayEntitiesLoaded(world, frame)) return;
            target = OriginDoors.sealedDoor(OriginDoors.doorwayOccupied(world, frame));
        } else target = ChamberDoorService.DoorState.OPEN;
        var chunks = PassageTransferPolicy.homeChunks(frame);
        if (!ChunkSaveCheckpoint.ready(world, chunks)) return;
        try {
            var doors = new ChamberDoorService();
            var applied = doors.applyTarget(world, frame, target);
            if (!applied.succeeded()) {
                warn(server, row, "門", "B 門尚未改為結案目標（" + applied.outcome() + "），退避後重試");
                backoff.failed(server.getTicks());
                return;
            }
            ChunkSaveCheckpoint.save(server, Map.of(world, chunks));
            new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)).publishOrVerify(row);
            // 同一 tick 內沒有遊戲邏輯；門仍是 checkpoint 存下的目標才移除，否則下一個 tick 重做門與 checkpoint。
            if (doors.applyTarget(world, frame, target).outcome() != ChamberDoorService.ApplyResult.Outcome.UNCHANGED) return;
            var removed = journal.finishHomeClose(server, row, new HomeClosureProof(server, row));
            if (!removed.removed()) {
                warn(server, row, "移除", "回程熱列尚未 checked 移除（" + removed.status() + "），保留占用："
                        + removed.failure().map(FailureText::describe).orElse("無原因"));
                backoff.failed(server.getTicks());
                return;
            }
        } catch (IOException | RuntimeException failure) {
            warn(server, row, "交接", "回程收尾失敗，保留結案、收據原件與占用，退避後重試：" + FailureText.describe(failure));
            backoff.failed(server.getTicks());
            return;
        }
        HomeRootTickets.release(server, sid);
        if (context != null) { context.backoff.remove(sid); context.warned.remove(sid); }
        LOGGER.info(LOG_MARKER, "回程已收尾：B 門 {}、原生 checkpoint、收據與熱列移除都已 checked（{}）：session={} chamber={}", target, close.reason(),
                sid, row.chamberUuid());
    }

    /** 同一 SID 同一原因只記一次 WARN。 */
    private static void warn(MinecraftServer server, HomeSessionRecord row, String kind, String message) {
        var context = CONTEXTS.get(server);
        var key = kind + ":" + message;
        if (context != null && key.equals(context.warned.put(row.sessionUuid(), key))) return;
        LOGGER.warn(LOG_MARKER, "{}：session={} chamber={}", message, row.sessionUuid(), row.chamberUuid());
    }
}
