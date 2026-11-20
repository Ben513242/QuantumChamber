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
import java.util.HashSet;
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
 * <li><b>B 收尾票</b>（M7 spec v1.6 C）：本類是 {@link HomeEntryTickets} 的唯一使用者。CLOSE 已 checked 而 B 未載入，或開服集合中曾 READY 的 AUTHORITY_UNREADABLE
 *     要核 B 時，先釋放同 SID 的 A 票再取 ≤4 refs、200 tick 的 B 收尾票；取不到或票期內未就緒時 WARN、沿 per-SID 退避重取，退避到上限仍失敗再記一則 ERROR。
 *     checked 移除、退避都釋放票。B 本來就載入時不取票。</li>
 * </ul>
 * 只在 server thread 執行；三個入口（{@link #step}、{@link #onParticipantClose}、{@link #onEntryStopped}）的核對與寫入都在該 SID 的 session operation guard 內
 * （M7 spec §9.1 :249），同 SID 已有進行中的操作時不寫入（參與者關門與 B 停止訊號另記一則 WARN）。每一則 log 都帶 {@link CollapseService#LOG_MARKER}（不屬於任何 session 的帶 {@link CollapseService#TICK_LOG_MARKER}）。
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
        /** 因同 SID 已有進行中的操作而拒絕過的入口（{@link #refusedUnderGuard}）；與 {@link #warned} 分開，不和其他原因共用去重鍵。 */
        final Map<UUID, Set<String>> guardRefused = new HashMap<>();
        /** B 收尾票退避到上限仍失敗、已記 ERROR 的 SID（M7 spec v1.6 C：每個 server instance 每 SID 一則）。 */
        final Set<UUID> ticketErrored = new HashSet<>();
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
            catch (RuntimeException failure) { warn(server, home, "推進", "回程終局推進丟出例外，下一個 tick 重試：" + FailureText.describe(failure), failure); }
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

    /**
     * 無事件的推進：收斂已凍結的 CLOSE，或由已核的全跨、到期、開服收尾與持久原艙 latch 決定原因。決策、凍結與交接都在這個 SID 的 session operation guard 內
     * （M7 spec §9.1 :249）；同 SID 已有進行中的操作時不推進，下一個 tick 再試。
     */
    public static void step(MinecraftServer server, HomeSessionRecord row) {
        if (!server.isOnThread()) throw new IllegalStateException("回程終局只在 server thread 推進");
        var journal = SessionRecoveryState.get(server);
        if (!settled(journal, row)) return;
        var pages = CorridorPageManager.forServer(server);
        if (pages.operationInProgress(row.sessionUuid())) return;
        pages.exclusiveOperation(row.sessionUuid(), () -> { stepInGuard(server, journal, row); return null; });
    }

    private static void stepInGuard(MinecraftServer server, SessionRecoveryState journal, HomeSessionRecord row) {
        var sid = row.sessionUuid();
        if (row.home().closeIntent() instanceof Close) { converge(server, journal, row); return; }
        if (HomeRootFence.queued(server, sid)) return;
        var context = CONTEXTS.get(server);
        var startup = context != null && context.startup.contains(sid) ? Optional.of(context.instance) : Optional.<UUID>empty();
        boolean recovered = false;
        if (restartVerifies(server, row)) {
            var status = HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint());
            // M7 spec v1.6 C 第 2 種情況：開服集合中曾 READY 的 AUTHORITY_UNREADABLE，RESTART_CLOSED 之前要載入 B 才能核 entry 是否恢復（B 未載入時 inspectEntry 是 UNKNOWN）。
            if (status == HomeEndpointAuthority.EntryStatus.UNKNOWN && entryUnloaded(server, row)) {
                var backoff = backoff(context, sid);
                if (!backoff.ready(server.getTicks()) || !entryTicketReady(server, row, backoff, HomeEntryTickets.Purpose.RESTART_VERIFY)) return;
                status = HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint());
            }
            if (status != HomeEndpointAuthority.EntryStatus.VALID && status != HomeEndpointAuthority.EntryStatus.UNKNOWN && entryTicketFull(server, sid)) {
                entryTicketFailed(server, row, backoff(context, sid), "開服收尾核對 B：B 已載入，但端點不是 exact 合法投影（" + status + "）");
                return;
            }
            recovered = status == HomeEndpointAuthority.EntryStatus.VALID;
        }
        var close = decide(row, new Facts(server.getOverworld().getTime(), startup, recovered));
        if (close.isEmpty()) return;
        var latched = latch(server, journal, row, close.get());
        if (latched != null) converge(server, journal, latched);
    }

    /**
     * 正式互動入口：參與者在 exact B 關門（PREPARE 或 PASSAGE）。在這個 SID 的 session operation guard 內（M7 spec §9.1 :249）核 actor 是在線、存活、非旁觀、
     * 位於 exact B 的 cohort 成員，再凍結與交接；同 SID 已有進行中的操作（包括呼叫端自己持有這個 guard）時回 REFUSED、不寫入，並記一則 WARN
     * （同一 SID 只記一次）。
     */
    public static ChamberCollapsePort.DoorUse onParticipantClose(MinecraftServer server, HomeSessionRecord row, ServerPlayerEntity actor,
            DoorPhase phase, long observedAt) {
        if (!server.isOnThread()) return ChamberCollapsePort.DoorUse.REFUSED;
        var pages = CorridorPageManager.forServer(server);
        if (refusedUnderGuard(server, pages, row, "參與者關門被拒")) return ChamberCollapsePort.DoorUse.REFUSED;
        return pages.exclusiveOperation(row.sessionUuid(), () -> participantCloseInGuard(server, row, actor, phase, observedAt));
    }

    private static ChamberCollapsePort.DoorUse participantCloseInGuard(MinecraftServer server, HomeSessionRecord row, ServerPlayerEntity actor,
            DoorPhase phase, long observedAt) {
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
     * <p>核對與寫入都在這個 SID 的 session operation guard 內（M7 spec §9.1 :249）；同 SID 已有進行中的操作（包括呼叫端自己持有這個 guard）時不 mint、不寫入，
     * 並記一則 WARN（同一 SID 只記一次）。
     */
    public static void onEntryStopped(MinecraftServer server, HomeSessionRecord row, ServerWorld entry, EntrySignal signal, long observedAt) {
        if (!server.isOnThread() || row.state() != SessionState.PASSAGE_READY || signal == null || signal == EntrySignal.DISABLED) return;
        var pages = CorridorPageManager.forServer(server);
        if (refusedUnderGuard(server, pages, row, "B 停止訊號被拒")) return;
        pages.exclusiveOperation(row.sessionUuid(), () -> { entryStoppedInGuard(server, row, entry, signal, observedAt); return null; });
    }

    private static void entryStoppedInGuard(MinecraftServer server, HomeSessionRecord row, ServerWorld entry, EntrySignal signal, long observedAt) {
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
        return exactEntry(server, row.entryEndpoint());
    }

    /** exact live B world：Entry 的 world key 存在，而且是該 universe 目前的 active world；否則 null。 */
    static ServerWorld exactEntry(MinecraftServer server, HomeSessionRecord.EntryEndpoint entry) {
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
                    + result.failure().map(FailureText::describe).orElse("無原因"), result.failure().orElse(null));
            return null;
        }
        LOGGER.info(LOG_MARKER, "回程已 checked 凍結結案（{}，DoorGoal={}）：session={} chamber={}", close.reason(), close.doorGoal(),
                row.sessionUuid(), row.chamberUuid());
        return next;
    }

    /**
     * §5.3 :172 的交接：exact B 改門 → 原生 checkpoint → 收據 → 再驗 → checked 移除 → 釋放票。未就緒時保留 CLOSE 與 busy，下一個 tick 再試。
     * M7 spec v1.6 C 第 1 種情況：CLOSE 已 checked、exact live B world 存在但 B 的 home chunks 或門鄰域尚未全部載入時，先以 B 收尾票（{@link HomeEntryTickets}，
     * CLOSE_CONVERGE）有界載入 B；票期內 FULL 的那個 tick 照同一順序收尾，票期內仍未就緒才退避。B 本來就載入時不取票，行為與 T4 相同。
     */
    private static void converge(MinecraftServer server, SessionRecoveryState journal, HomeSessionRecord row) {
        var sid = row.sessionUuid();
        var context = CONTEXTS.get(server);
        var backoff = backoff(context, sid);
        if (!backoff.ready(server.getTicks())) return;
        var close = (Close) row.home().closeIntent();
        var status = HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint());
        if (status == HomeEndpointAuthority.EntryStatus.UNKNOWN && entryUnloaded(server, row)) {
            if (!entryTicketReady(server, row, backoff, HomeEntryTickets.Purpose.CLOSE_CONVERGE)) return;
            status = HomeEndpointAuthority.inspectEntry(server, row.entryEndpoint());
        }
        if (status != HomeEndpointAuthority.EntryStatus.VALID) {
            if (status != HomeEndpointAuthority.EntryStatus.UNKNOWN) {
                // B 可在兩個 tick 之間載入，這一輪的首次驗證就可能已確定端點不合法。
                if (entryTicketFull(server, sid))
                    entryTicketFailed(server, row, backoff, "收尾核對 B：B 已載入，但端點不是 exact 合法投影（" + status + "）");
                else warn(server, row, "B", "B 端點不是 exact 合法投影（" + status + "），保留結案與占用", null);
            }
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
                warn(server, row, "門", "B 門尚未改為結案目標（" + applied.outcome() + "），退避後重試", null);
                backOff(server, sid, backoff);
                return;
            }
            ChunkSaveCheckpoint.save(server, Map.of(world, chunks));
            new HomeReceiptStore(server.getSavePath(WorldSavePath.ROOT)).publishOrVerify(row);
            // 同一 tick 內沒有遊戲邏輯；門仍是 checkpoint 存下的目標才移除，否則下一個 tick 重做門與 checkpoint。
            if (doors.applyTarget(world, frame, target).outcome() != ChamberDoorService.ApplyResult.Outcome.UNCHANGED) return;
            var removed = journal.finishHomeClose(server, row, new HomeClosureProof(server, row));
            if (!removed.removed()) {
                warn(server, row, "移除", "回程熱列尚未 checked 移除（" + removed.status() + "），保留占用："
                        + removed.failure().map(FailureText::describe).orElse("無原因"), removed.failure().orElse(null));
                backOff(server, sid, backoff);
                return;
            }
        } catch (IOException | RuntimeException failure) {
            warn(server, row, "交接", "回程收尾失敗，保留結案、收據原件與占用，退避後重試：" + FailureText.describe(failure), failure);
            backOff(server, sid, backoff);
            return;
        }
        HomeRootTickets.release(server, sid);
        HomeEntryTickets.release(server, sid);
        if (context != null) { context.backoff.remove(sid); context.warned.remove(sid); context.guardRefused.remove(sid); }
        LOGGER.info(LOG_MARKER, "回程已收尾：B 門 {}、原生 checkpoint、收據與熱列移除都已 checked（{}）：session={} chamber={}", target, close.reason(),
                sid, row.chamberUuid());
    }

    // ---- B 收尾票（M7 spec v1.6 C） ----

    private static final String TICKET_UNAVAILABLE = "B 收尾票取不到（A 票＋B 收尾票合計 64 份／256 refs 或與走廊共用的 4096 coverage 已滿，或停服中）";
    private static final String TICKET_EXPIRED = "B 收尾票在 " + HomeEntryTickets.LEASE_TICKS + " tick 內沒有就緒（B 的 home chunks 沒有全部 FULL，或收尾的其他前提仍未成立）";

    private static boolean entryTicketFull(MinecraftServer server, UUID sid) {
        return HomeEntryTickets.lease(server, sid).filter(HomeEntryTickets.Lease::full).isPresent();
    }

    private static CollapseBackoff backoff(Context context, UUID sid) {
        return context == null ? new CollapseBackoff() : context.backoff.computeIfAbsent(sid, ignored -> new CollapseBackoff());
    }

    /** 記一次失敗並退避；退避期間不持 B 收尾票（釋放失敗時票 owner 保留未清的 refs，下一個 {@code START_SERVER_TICK} 依「列不再適用」或租期再清）。 */
    private static void backOff(MinecraftServer server, UUID sid, CollapseBackoff backoff) {
        backoff.failed(server.getTicks());
        try { HomeEntryTickets.release(server, sid); }
        catch (RuntimeException failure) { LOGGER.warn(LOG_MARKER, "退避時釋放 B 收尾票失敗，票 owner 下一個 tick 續清：session={}", sid, failure); }
    }

    /** exact live B world 存在，但 B 的 home chunks 或門鄰域尚未全部載入（非建立的查詢）。B 世界不存在時不取票（不猜另一個 B）。 */
    private static boolean entryUnloaded(MinecraftServer server, HomeSessionRecord row) {
        var world = exactEntry(server, row);
        if (world == null) return false;
        var frame = new ChamberFrame(row.entryEndpoint().controllerPos(), row.entryEndpoint().facing());
        return !PassageTransferPolicy.homeChunks(frame).stream().allMatch(chunk -> world.getChunkManager().getWorldChunk(chunk.x, chunk.z) != null)
                || !CollapseService.neighborhoodLoaded(world, frame);
    }

    /**
     * B 收尾票是否已 FULL。還沒有票時先釋放同 SID 的 A 票（每 SID 同時最多一份 A 票或 B 收尾票）再取票；取不到、或上一份票租期到而被釋放時，記 WARN（同 SID 同原因一則）、
     * 沿本類的 per-SID 退避（200→12000）後重取，退避到上限仍失敗再記一則 ERROR（每 instance 每 SID）。期間保留 CLOSE、busy 與 A 保護，不猜另一個 B，不改 A。
     */
    private static boolean entryTicketReady(MinecraftServer server, HomeSessionRecord row, CollapseBackoff backoff, HomeEntryTickets.Purpose purpose) {
        var sid = row.sessionUuid();
        if (HomeEntryTickets.consumeExpired(server, sid)) { entryTicketFailed(server, row, backoff, TICKET_EXPIRED); return false; }
        var lease = HomeEntryTickets.lease(server, sid);
        if (lease.isEmpty()) {
            HomeRootTickets.release(server, sid);
            lease = HomeEntryTickets.acquire(server, sid, row.entryEndpoint(), purpose);
            if (lease.isEmpty()) { entryTicketFailed(server, row, backoff, TICKET_UNAVAILABLE); return false; }
            LOGGER.info(LOG_MARKER, "B 未載入：取得 B 收尾票（{}，{} refs，租期 {} tick）有界載入 B：session={} chamber={}", purpose, lease.get().refs(),
                    HomeEntryTickets.LEASE_TICKS, sid, row.chamberUuid());
        }
        return lease.get().full();
    }

    private static void entryTicketFailed(MinecraftServer server, HomeSessionRecord row, CollapseBackoff backoff, String reason) {
        var sid = row.sessionUuid();
        warn(server, row, "B 收尾票", reason + "：保留結案、占用與原艙保護，退避後重取", null);
        backOff(server, sid, backoff);
        var context = CONTEXTS.get(server);
        if (backoff.delayTicks() >= CollapseBackoff.MAX_TICKS && (context == null || context.ticketErrored.add(sid)))
            LOGGER.error(LOG_MARKER, "回程收尾的 B 收尾票退避到上限（{} tick）仍失敗，請管理員處理（確認 B 的世界與 chunk 能載入、B 端點合法、伺服器的 chunk 票額度沒有被占滿）："
                    + "{}；結案與占用保留，A 維持保護：session={} chamber={}", CollapseBackoff.MAX_TICKS, reason, sid, row.chamberUuid());
    }

    /** M7 spec v1.6 C 第 2 種情況：本 instance 開服集合中、曾 READY（通道開過）、未有原艙停止原因且 CloseIntent=NONE 的 AUTHORITY_UNREADABLE SEALED。 */
    static boolean restartVerifies(MinecraftServer server, HomeSessionRecord row) {
        var context = CONTEXTS.get(server);
        var h = row.home();
        return context != null && context.startup.contains(row.sessionUuid()) && row.state() == SessionState.SEALED
                && h.sealedReason().orElse(null) == Seal.AUTHORITY_UNREADABLE && h.closeIntent() instanceof None
                && h.stopReason().isEmpty() && h.passageOpenedAtGameTime() >= 0;
    }

    /**
     * 正式入口的拒絕條件：同 SID 已有進行中的操作（包括呼叫端自己持有這個 guard，例如把門處理包進同 SID 的 {@code exclusiveOperation}）時回 true，
     * 本次不寫入。拒絕時記一則 WARN，與一般拒絕區分（T4 Phase A fix2 審查 N1）；同一 SID、同一入口只記一次，去重與 {@link #warn} 的其他原因分開。
     */
    private static boolean refusedUnderGuard(MinecraftServer server, CorridorPageManager pages, HomeSessionRecord row, String entry) {
        if (!pages.operationInProgress(row.sessionUuid())) return false;
        var context = CONTEXTS.get(server);
        if (context == null || context.guardRefused.computeIfAbsent(row.sessionUuid(), ignored -> new HashSet<>()).add(entry))
            LOGGER.warn(LOG_MARKER, "{}：同一 session 已有進行中的操作（呼叫端可能自己持有 guard），本次不寫入：session={} chamber={}", entry,
                    row.sessionUuid(), row.chamberUuid());
        return true;
    }

    /** 同一 SID 同一原因只記一次 WARN；{@code cause} 是實際的失敗（沒有時為 null），有時附上 stack。 */
    private static void warn(MinecraftServer server, HomeSessionRecord row, String kind, String message, Throwable cause) {
        var context = CONTEXTS.get(server);
        var key = kind + ":" + message;
        if (context != null && key.equals(context.warned.put(row.sessionUuid(), key))) return;
        LOGGER.warn(LOG_MARKER, "{}：session={} chamber={}", message, row.sessionUuid(), row.chamberUuid(), cause);
    }
}
