package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseService.LOG_MARKER;
import static dev.quantumchamber.collapse.CollapseService.TICK_LOG_MARKER;

import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.chamber.ChamberRecord;
import dev.quantumchamber.chamber.ChamberRegistry;
import dev.quantumchamber.chamber.ChamberRegistryState;
import dev.quantumchamber.chamber.ChamberSessions;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.HomeSessionRecord.Seal;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.superposition.SuperpositionSessionManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 回程旅程對原艙 A 的停用防線（M7 spec §9.1、§6 [R1][R3]）。
 * <ul>
 * <li><b>提交點通知</b>：{@link ChamberRegistry} 的停用（直接 setter、維護入口都經它）與真正移除，經 {@link ChamberCollapsePort.Roots} 同步呼叫
 *     {@link #onRootEvent}；每個已選定、CloseIntent=NONE、尚未停止、非永久封存的 HOME 列各自 first-wins checked latch 不可清除的 StopReason。
 *     latch 寫在該 SID 的 session operation guard 內；journal 尚未收斂或同 SID 已有進行中的操作時先排入本 server 的佇列，回報 pending，並拒絕同根復啟。
 *     普通斷電不是根事件；UNKNOWN 不 mint ROOT。</li>
 * <li><b>開服重建</b>（{@link #bootstrap}）：journal strict 載入與 registry／ownership 完成後、任何維護 enable 之前，掃描全部 active selected HOME 列
 *     （含沒有 B runtime 的 DORMANT；不掃收據），把持久 registry 已確認的停用、移除或身分撤銷重建為同一 latch。{@code SERVER_STARTING} 已先放入未就緒的 fence，
 *     掃描完成前一律拒絕 enable。</li>
 * <li><b>A 保護</b>：同艙另一端的回程從暫占起到 checked 終局，一般拆除被拒（{@link ChamberCollapsePort.Roots#permitsRemoval}）；管理員停用仍可。</li>
 * </ul>
 * 只在 server thread 執行；log 一律帶塌縮流程的 marker。
 */
public final class HomeRootFence {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    private static final Map<MinecraftServer, HomeRootFence> FENCES = new ConcurrentHashMap<>();
    private static boolean initialized;

    private final MinecraftServer server;
    /** 已收到、尚未 checked latch 的根事件（每個 SID first-wins）。 */
    private final Map<UUID, Event> queued = new LinkedHashMap<>();
    private boolean ready;

    private record Event(RootReason reason, long observedAt) {}

    private HomeRootFence(MinecraftServer server) { this.server = server; }

    /**
     * 註冊 port、開服接手與每 tick 推進。{@code QuantumSuperpositionMod} 在 {@code CollapseService.initialize()} 之後呼叫，所以開服時 journal 與 M5 authority 已就緒。
     * <p>{@code SERVER_STARTING}（世界載入之前）先放入未就緒的 fence（M7 spec §9.1 :246）：從世界載入到 {@code SERVER_STARTED} 的 {@link #bootstrap} 完成之前，
     * port 已能辨認這個 server，{@link #permitsEnable} 一律回 false，任何 enable 都不能繞過尚未完成的開服掃描。只放入記憶體物件，不讀 journal 或 registry，
     * 不改變其他開服流程的順序。
     */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        ChamberCollapsePort.installRoots(new Port());
        ServerLifecycleEvents.SERVER_STARTING.register(server -> FENCES.computeIfAbsent(server, HomeRootFence::new));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> { HomeClosure.startup(server); bootstrap(server); });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            var fence = FENCES.get(server);
            if (fence == null) return;
            fence.tick();
            HomeClosure.tick(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { FENCES.remove(server); HomeClosure.release(server); });
    }

    /** 開服重建 fence：掃描 active selected HOME 列，持久 registry 已確認停用、移除或身分撤銷的立即 latch。初始化失敗維持阻擋 enable。 */
    public static void bootstrap(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("原艙停用防線只在 server thread 重建");
        var fence = FENCES.computeIfAbsent(server, HomeRootFence::new);
        fence.ready = false;
        SessionRecoveryState journal;
        Map<UUID, ChamberRecord> registry;
        try {
            journal = SessionRecoveryState.get(server); journal.requireHealthy();
            var state = ChamberRegistryState.get(server); state.requireHealthy();
            registry = state.registry().records();
        } catch (RuntimeException unhealthy) {
            LOGGER.error(TICK_LOG_MARKER, "原艙停用防線無法重建（journal 或 registry 不健康），維持拒絕原艙復啟", unhealthy);
            return;
        }
        long now = server.getOverworld().getTime();
        for (var row : List.copyOf(journal.allSessions().values())) {
            if (!(row instanceof HomeSessionRecord home) || !latchable(home)) continue;
            revoked(registry.get(home.chamberUuid()), home.rootAnchor()).ifPresent(reason -> fence.event(journal, home, reason, now));
        }
        fence.ready = true;
    }

    /** 根事件（停用、移除、身分撤銷、實際觀察到的結構失效）；回 true 只表示所有需要停止的旅程都已 checked latch，或沒有這種旅程。 */
    public static boolean onRootEvent(MinecraftServer server, ChamberOriginAuthority root, HomeEndpointAuthority.RootStatus status, long observedAt) {
        var reason = switch (status) {
            case DISABLED -> RootReason.ROOT_DISABLED;
            case INVALID_STRUCTURE -> RootReason.ROOT_INVALID_STRUCTURE;
            case DESTROYED -> RootReason.ROOT_DESTROYED;
            case IDENTITY_MISMATCH -> RootReason.ROOT_IDENTITY_MISMATCH;
            // 普通斷電、未載入與正常都不是根事件（M7 spec §9.1、§9.2）。
            case READY, LOW, UNKNOWN -> null;
        };
        if (reason == null) return true;
        if (!server.isOnThread()) throw new IllegalStateException("根事件只在 server thread 通知");
        var fence = FENCES.computeIfAbsent(server, HomeRootFence::new);
        SessionRecoveryState journal;
        try { journal = SessionRecoveryState.get(server); journal.requireHealthy(); }
        catch (RuntimeException unhealthy) {
            LOGGER.error(TICK_LOG_MARKER, "原艙根事件無法讀取 journal，視為 pending：chamber={} event={}", root.chamberUuid(), status, unhealthy);
            return false;
        }
        for (var row : List.copyOf(journal.allSessions().values()))
            if (row instanceof HomeSessionRecord home && home.chamberUuid().equals(root.chamberUuid()) && latchable(home))
                fence.event(journal, home, reason, observedAt);
        return fence.settled(journal, root.chamberUuid());
    }

    /** 這座原艙是否可以重新啟用：fence 尚未重建、仍有排隊事件、latch 未 checked，或 registry 已停用而本趟尚未 latch 時都回 false。 */
    public static boolean permitsEnable(MinecraftServer server, UUID chamber) {
        if (!server.isOnThread()) return false;
        var fence = FENCES.get(server);
        if (fence == null || !fence.ready) return false;
        SessionRecoveryState journal;
        try { journal = SessionRecoveryState.get(server); journal.requireHealthy(); }
        catch (RuntimeException unhealthy) { return false; }
        var record = ChamberRegistryState.get(server).registry().records().get(chamber);
        // 註冊已落 disabled、本趟停止尚未落盤（M7 spec §9.1 開服重建 fence 的窗口）：先 latch 再放行。
        if (record != null && !record.enabled()) {
            long now = server.getOverworld().getTime();
            for (var row : List.copyOf(journal.allSessions().values()))
                if (row instanceof HomeSessionRecord home && home.chamberUuid().equals(chamber) && latchable(home))
                    fence.event(journal, home, RootReason.ROOT_DISABLED, now);
        }
        return fence.settled(journal, chamber);
    }

    /** 這個 SID 有排隊中、尚未 checked 的根事件（{@link HomeClosure} 依優先序先等它）。 */
    static boolean queued(MinecraftServer server, UUID sid) {
        var fence = FENCES.get(server);
        return fence != null && fence.queued.containsKey(sid);
    }

    /** 每 tick 重試排隊中的 latch；目標已終局、已停止或已不可 latch 時出列。 */
    private void tick() {
        if (queued.isEmpty()) return;
        SessionRecoveryState journal;
        try { journal = SessionRecoveryState.get(server); journal.requireHealthy(); }
        catch (RuntimeException unhealthy) { return; }
        for (var sid : List.copyOf(queued.keySet())) {
            var event = queued.get(sid);
            if (!(journal.allSessions().get(sid) instanceof HomeSessionRecord row) || !latchable(row)) {
                if (durable(journal, sid)) queued.remove(sid);
                continue;
            }
            event(journal, row, event.reason(), event.observedAt());
        }
    }

    /**
     * 一個 SID 的根事件：journal 已收斂而且取得這個 SID 的 session operation guard 時，在 guard 內立即 checked latch（M7 spec §9.1 :249）；否則（journal 尚未收斂、
     * 同 SID 已有進行中的操作或 guard 尚不可用、latch 未 checked）first-wins 排隊，下一個 tick 再試。同一 SID 只在第一次排隊時記 WARN。
     */
    private void event(SessionRecoveryState journal, HomeSessionRecord row, RootReason reason, long observedAt) {
        var sid = row.sessionUuid();
        var first = queued.get(sid);
        var latched = first != null ? first.reason() : reason;
        if (first != null) observedAt = first.observedAt();
        var selected = ((Selected) row.home().selection()).selectedAtGameTime();
        // StoppedAt 必須 ≥ 選定時間；世界時間被調回時以選定時間為下限，不接受外部時間。
        long stoppedAt = Math.max(observedAt, selected);
        var pages = guard(sid);
        // 前置 current=flushed=expected 與寫入都在 guard 內；未收斂時回 null，不寫入。
        var result = pages == null ? null
                : pages.exclusiveOperation(sid, () -> settled(journal, row) ? journal.commitHomeRollForward(server, row, withStop(row, latched, stoppedAt)) : null);
        if (result != null && result.committed()) {
            queued.remove(sid);
            LOGGER.info(LOG_MARKER, "原艙根事件已 checked latch，本趟停止旅行、保留選擇（{}，tick {}）：session={} chamber={}", latched, stoppedAt,
                    sid, row.chamberUuid());
            return;
        }
        // 審查 M3：最常見的「journal 尚未收斂」與 guard 忙碌也在第一次排隊時記 WARN，與上方 Javadoc 一致。
        if (first == null) {
            if (pages == null) LOGGER.warn(LOG_MARKER, "原艙根事件遇到同一 session 有進行中的操作（或走廊 authority 尚未就緒），先排隊並拒絕復啟，之後的 tick 再 checked latch（{}）："
                    + "session={} chamber={}", latched, sid, row.chamberUuid());
            else if (result == null) LOGGER.warn(LOG_MARKER, "原艙根事件遇到 journal 尚未收斂，先排隊並拒絕復啟，收斂後再 checked latch（{}）：session={} chamber={}", latched, sid,
                    row.chamberUuid());
            else LOGGER.warn(LOG_MARKER, "原艙根事件的 latch 尚未 checked（{}），排隊重試並拒絕復啟：session={} chamber={}", result.status(), sid,
                    row.chamberUuid(), result.failure().orElse(null));
        }
        queued.putIfAbsent(sid, new Event(latched, observedAt));
    }

    /** 同 state 單步寫入不可清除的停止原因與時間，其餘欄位不變（M7 spec §9.1 :243）。 */
    private static HomeSessionRecord withStop(HomeSessionRecord row, RootReason reason, long stoppedAt) {
        var h = row.home();
        return new HomeSessionRecord(row.sessionUuid(), row.chamberUuid(), row.rootAnchor(), row.entryEndpoint(), row.participants(), row.spaceLeases(),
                row.state(), new Home(h.selection(), h.committedAtGameTime(), h.passageOpenedAtGameTime(), h.passageExpiresAtGameTime(),
                h.crossed(), h.recoveryRequired(), Optional.of(reason), stoppedAt, h.closeIntent(), h.sealedReason()));
    }

    /** 這個 SID 可取得的 session operation guard；走廊 authority 尚未 attach（開服掃描之前）或同 SID 已有進行中的操作時為 null。 */
    private CorridorPageManager guard(UUID sid) {
        CorridorPageManager pages;
        try { pages = CorridorPageManager.forServer(server); }
        catch (RuntimeException unavailable) { return null; }
        return pages.operationInProgress(sid) ? null : pages;
    }

    /** 這座原艙所有 HOME 列都沒有排隊事件，而且已 latch 的停止都已 checked。 */
    private boolean settled(SessionRecoveryState journal, UUID chamber) {
        for (var row : Stream.of(journal.allSessions(), journal.allFlushedSessions(), journal.pendingSessions())
                .flatMap(rows -> rows.values().stream()).toList()) {
            if (!(row instanceof HomeSessionRecord home) || !home.chamberUuid().equals(chamber)) continue;
            if (queued.containsKey(home.sessionUuid())) return false;
            if (home.home().stopReason().isPresent() && !durable(journal, home.sessionUuid())) return false;
        }
        return true;
    }

    /** 需要 latch 的列：已選定、CloseIntent=NONE、尚未停止，而且不是永久封存（TRR 等永久證據不能被根事件吞掉）。 */
    private static boolean latchable(HomeSessionRecord row) {
        var h = row.home();
        return h.selection() instanceof Selected && h.closeIntent() instanceof None && h.stopReason().isEmpty()
                && (row.state() != SessionState.SEALED || h.sealedReason().orElse(null) == Seal.AUTHORITY_UNREADABLE);
    }

    private static boolean settled(SessionRecoveryState journal, HomeSessionRecord row) {
        var sid = row.sessionUuid();
        return row.equals(journal.allSessions().get(sid)) && row.equals(journal.allFlushedSessions().get(sid)) && !journal.rollForwardPending(sid)
                && journal.pendingSessions().isEmpty() && !journal.quarantinedSessions().contains(sid);
    }

    private static boolean durable(SessionRecoveryState journal, UUID sid) {
        var current = journal.allSessions().get(sid);
        return java.util.Objects.equals(current, journal.allFlushedSessions().get(sid)) && !journal.rollForwardPending(sid)
                && !journal.pendingSessions().containsKey(sid);
    }

    /** 持久 registry 已確認的根撤銷：沒有紀錄或已拆除為 DESTROYED、身分欄位不符為 IDENTITY_MISMATCH、停用為 DISABLED。 */
    private static Optional<RootReason> revoked(ChamberRecord record, ChamberOriginAuthority root) {
        if (record == null || record.destroyed()) return Optional.of(RootReason.ROOT_DESTROYED);
        if (record.instanceKind() != ChamberInstanceKind.ORIGIN || !record.originWorldKey().equals(root.worldKey())
                || record.originDimensionRole() != root.role() || !record.anchorPos().equals(root.controllerPos()) || record.facing() != root.facing())
            return Optional.of(RootReason.ROOT_IDENTITY_MISMATCH);
        return record.enabled() ? Optional.empty() : Optional.of(RootReason.ROOT_DISABLED);
    }

    /** 同艙另一端的回程從暫占起到 checked 終局（active 列、未確認寫入或 B 暫占）。 */
    private static boolean homeBusy(MinecraftServer server, UUID chamber) {
        if (ChamberSessions.gateway() instanceof SuperpositionSessionManager manager && manager.provisionalClaimed(server, chamber)) return true;
        var journal = SessionRecoveryState.get(server);
        return Stream.of(journal.allSessions(), journal.allFlushedSessions(), journal.pendingSessions()).flatMap(rows -> rows.values().stream())
                .anyMatch(row -> row instanceof HomeSessionRecord && row.chamberUuid().equals(chamber));
    }

    /** {@link ChamberCollapsePort.Roots} 的實作：只處理屬於某個已啟動 server 的 registry，其他一律放行。 */
    private static final class Port implements ChamberCollapsePort.Roots {
        private static MinecraftServer owner(ChamberRegistry registry) {
            for (var server : FENCES.keySet()) {
                try { if (server.isOnThread() && ChamberRegistryState.get(server).registry() == registry) return server; }
                catch (RuntimeException unreadable) { /* 不屬於可讀的 server */ }
            }
            return null;
        }

        @Override public boolean permitsEnable(ChamberRegistry registry, UUID chamber) {
            var server = owner(registry);
            return server == null || HomeRootFence.permitsEnable(server, chamber);
        }

        @Override public boolean onRootEvent(ChamberRegistry registry, ChamberRecord record, ChamberCollapsePort.RootEvent event) {
            var server = owner(registry);
            if (server == null) return true;
            var root = new ChamberOriginAuthority(record.chamberUuid(), record.originWorldKey(), record.originDimensionRole(), record.anchorPos(),
                    record.facing(), record.instanceKind());
            var status = switch (event) {
                case DISABLED -> HomeEndpointAuthority.RootStatus.DISABLED;
                case INVALID_STRUCTURE -> HomeEndpointAuthority.RootStatus.INVALID_STRUCTURE;
                case DESTROYED -> HomeEndpointAuthority.RootStatus.DESTROYED;
                case IDENTITY_MISMATCH -> HomeEndpointAuthority.RootStatus.IDENTITY_MISMATCH;
            };
            try { return HomeRootFence.onRootEvent(server, root, status, server.getOverworld().getTime()); }
            catch (RuntimeException failure) {
                LOGGER.error(TICK_LOG_MARKER, "原艙根事件通知失敗，視為 pending：chamber={} event={}", record.chamberUuid(), event, failure);
                return false;
            }
        }

        /**
         * M7 spec v1.6 D：同艙另一端的回程只剩已 checked 的永久封存時（{@link dev.quantumchamber.chamber.ChamberSessionGateway#permanentlySealedOnly}），拆除防線放行；
         * 一般拆除前的 OFF 與核對仍由既有入口檢查（停用且斷電才會是 OFF）。非永久的 HOME 列照 [R3] 維持保護到收尾。
         */
        @Override public boolean permitsRemoval(ChamberRegistry registry, UUID chamber) {
            var server = owner(registry);
            if (server == null) return true;
            try { return !homeBusy(server, chamber) || ChamberSessions.gateway().permanentlySealedOnly(server, chamber); }
            catch (RuntimeException unreadable) { return false; }
        }
    }
}
