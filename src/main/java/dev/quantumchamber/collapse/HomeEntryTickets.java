package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.SessionRecoveryState;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/**
 * M7 spec v1.6 C：回程收尾時替 B 取的有界票（B 收尾票）。唯一的使用者是回程終局 owner（{@link HomeClosure}），沿 {@link HomeRootTickets}（A 票）的有界做法：
 * <ul>
 * <li>每份票綁定單一 server、SID 與用途，涵蓋 exact live B 的 {@code PassageTransferPolicy.homeChunks}（≤4 refs）；FULL 是非建立的查詢
 *     （{@code getWorldChunk}，與 A 票相同）。</li>
 * <li>只在兩種情況取得（{@link #acquire} 依用途核對，回程終局 owner 只在 B 未載入時才呼叫）：{@link Purpose#CLOSE_CONVERGE}：CloseIntent=CLOSE 已 checked；
 *     {@link Purpose#RESTART_VERIFY}：開服集合中曾 READY 的 AUTHORITY_UNREADABLE SEALED，RESTART_CLOSED 之前要載入 B 才能核 entry 是否恢復。</li>
 * <li>上限與 A 票共用：每 SID 同時最多一份 A 票或 B 收尾票，server 合計 64 份／256 refs，與走廊共用 4096 coverage；覆蓋鍵是 exact world＋chunk
 *     （{@link CorridorPageManager#reserveRootCoverage}）。</li>
 * <li>租期：每份最多 {@value #LEASE_TICKS} server ticks。租期到、列不再適用、{@code /reload}、SERVER_STOPPING 都冪等釋放；租期到時另記下，交回程終局 owner 記 WARN 並退避
 *     （{@link #consumeExpired}）。checked 移除與收尾失敗進退避時由 owner 呼叫 {@link #release}；退避期間不持票。</li>
 * <li>票是 process 資源，不寫 NBT；開服後依上列條件重新取得。</li>
 * </ul>
 * 只在 server thread 操作。
 */
public final class HomeEntryTickets {
    public enum Purpose { CLOSE_CONVERGE, RESTART_VERIFY }
    public static final int LEASE_TICKS = 200;
    private static final ChunkTicketType<UUID> TYPE = ChunkTicketType.create("quantumchamber_home_entry", Comparator.comparing(UUID::toString));
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("quantumchamber");
    private static final Map<MinecraftServer, HomeEntryTickets> servers = new IdentityHashMap<>();
    private static boolean initialized;
    private final Map<UUID, Lease> leases = new HashMap<>();
    /** 租期到而由 {@code START_SERVER_TICK} 釋放、owner 尚未處理的 SID。 */
    private final Set<UUID> expired = new HashSet<>();
    private boolean stopping;

    private HomeEntryTickets() {}

    interface Backend {
        void add(ServerWorld world, ChunkPos pos, UUID sid);
        void remove(ServerWorld world, ChunkPos pos, UUID sid);
        default boolean full(ServerWorld world, ChunkPos pos) { return world.getChunkManager().getWorldChunk(pos.x, pos.z) != null; }
    }
    static final Backend NATIVE = new Backend() {
        public void add(ServerWorld world, ChunkPos pos, UUID sid) { world.getChunkManager().addTicket(TYPE, pos, 2, sid); }
        public void remove(ServerWorld world, ChunkPos pos, UUID sid) { world.getChunkManager().removeTicket(TYPE, pos, 2, sid); }
    };
    /** 新取得的票使用的 backend。production 一律是 {@link #NATIVE}；只有 testmod 以反射暫時替換（模擬 200 tick 都不 FULL），用完還原。 */
    static Backend backend = NATIVE;

    public static void initialize() {
        if (initialized) return;
        initialized = true;
        ServerLifecycleEvents.SERVER_STARTED.register(server -> servers.put(server, new HomeEntryTickets()));
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            var owner = servers.get(server);
            if (owner == null) return;
            for (var lease : List.copyOf(owner.leases.values())) {
                try { lease.refresh(); }
                catch (RuntimeException failure) { lease.warnCleanup(failure); }
            }
            if (!owner.expired.isEmpty()) owner.expired.removeIf(sid -> !present(server, sid));
        });
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((server, resources) -> closeAll(server, false));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> closeAll(server, true));
        ServerLifecycleEvents.SERVER_STOPPED.register(servers::remove);
    }

    /**
     * 取得這個 SID 的 B 收尾票：沒有 owner、停服中、這個 SID 已有票、列不適用 {@code purpose}、exact live B 不存在，或 A＋B 合計份數／refs／coverage 已滿時回空。
     * 呼叫端（回程終局 owner）先釋放同 SID 的 A 票。底層加票途中丟出時，已加的票冪等移除後原樣丟出。
     */
    public static Optional<Lease> acquire(MinecraftServer server, UUID sid, EntryEndpoint entry, Purpose purpose) {
        thread(server);
        Objects.requireNonNull(sid); Objects.requireNonNull(entry); Objects.requireNonNull(purpose);
        var owner = servers.get(server);
        if (owner == null || owner.stopping || owner.leases.containsKey(sid) || !applicable(server, sid, entry, purpose)) return Optional.empty();
        var world = HomeClosure.exactEntry(server, entry);
        if (world == null) return Optional.empty();
        var chunks = PassageTransferPolicy.homeChunks(new ChamberFrame(entry.controllerPos(), entry.facing()));
        var pages = CorridorPageManager.forServer(server);
        if (!pages.reserveRootCoverage(sid, world.getRegistryKey(), chunks)) return Optional.empty();
        var lease = new Lease(owner, world, sid, entry, purpose, pages, server.getTicks() + LEASE_TICKS, backend);
        owner.leases.put(sid, lease);
        owner.expired.remove(sid);
        try {
            for (var chunk : chunks) {
                // 先記責任；即使底層已加票才拋錯，移除同 SID 的票仍可回復。
                lease.chunks.add(chunk);
                lease.backend.add(world, chunk, sid);
            }
            return Optional.of(lease);
        } catch (RuntimeException failure) {
            try { lease.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** 這個 SID 目前的 B 收尾票。 */
    public static Optional<Lease> lease(MinecraftServer server, UUID sid) {
        thread(server);
        var owner = servers.get(server);
        return owner == null ? Optional.empty() : Optional.ofNullable(owner.leases.get(sid));
    }

    /** 這個 SID 的票是否因租期到而被釋放、尚未處理；讀一次即清除。 */
    static boolean consumeExpired(MinecraftServer server, UUID sid) {
        thread(server);
        var owner = servers.get(server);
        return owner != null && owner.expired.remove(sid);
    }

    /** 冪等釋放這個 SID 的票（沒有票時不做事）。 */
    public static void release(MinecraftServer server, UUID sid) {
        thread(server);
        var owner = servers.get(server);
        if (owner == null) return;
        owner.expired.remove(sid);
        var lease = owner.leases.get(sid);
        if (lease != null) lease.close();
    }

    private static void closeAll(MinecraftServer server, boolean stop) {
        thread(server);
        var owner = servers.get(server);
        if (owner == null) return;
        owner.stopping = stop;
        for (var lease : List.copyOf(owner.leases.values())) lease.closeForEvent();
        if (stop) {
            // STOPPING 之後沒有正常 tick；逐 SID 把剩餘 refs 再試最後一次。
            for (var lease : List.copyOf(owner.leases.values())) {
                lease.closeForEvent();
                if (!lease.chunks.isEmpty()) LOGGER.error(CollapseService.LOG_MARKER, "B 收尾票停止時仍未釋放：SID={} refs={}", lease.sid, lease.chunks);
            }
        }
        owner.expired.clear();
    }

    /**
     * 列是否適用 {@code purpose}：journal 上這個 SID 是 HOME 列、Entry 相同、current＝flushed、沒有待確認的 roll-forward、沒有送出中的寫入、沒有隔離；
     * CLOSE_CONVERGE 另要求 CloseIntent=CLOSE（已 checked），RESTART_VERIFY 另要求是開服集合中曾 READY 的 AUTHORITY_UNREADABLE SEALED（{@link HomeClosure#restartVerifies}）。
     */
    private static boolean applicable(MinecraftServer server, UUID sid, EntryEndpoint entry, Purpose purpose) {
        var row = settledRow(server, sid);
        if (row == null || !row.entryEndpoint().equals(entry)) return false;
        return purpose == Purpose.CLOSE_CONVERGE ? row.home().closeIntent() instanceof HomeSessionRecord.Close : HomeClosure.restartVerifies(server, row);
    }

    /** 已取得的票是否仍適用：任一用途的條件成立即可（RESTART_VERIFY 的票在 RESTART_CLOSED checked 之後繼續供收尾使用）。 */
    private static boolean stillApplies(MinecraftServer server, UUID sid, EntryEndpoint entry) {
        return applicable(server, sid, entry, Purpose.CLOSE_CONVERGE) || applicable(server, sid, entry, Purpose.RESTART_VERIFY);
    }

    private static HomeSessionRecord settledRow(MinecraftServer server, UUID sid) {
        try {
            var journal = SessionRecoveryState.get(server);
            if (!(journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row) || !row.equals(journal.allSessions().get(sid)) || journal.rollForwardPending(sid)
                    || journal.pendingSessions().containsKey(sid) || journal.quarantinedSessions().contains(sid)) return null;
            return row;
        } catch (RuntimeException unreadable) { return null; }
    }

    private static boolean present(MinecraftServer server, UUID sid) {
        try {
            var journal = SessionRecoveryState.get(server);
            return journal.allSessions().containsKey(sid) || journal.allFlushedSessions().containsKey(sid);
        } catch (RuntimeException unreadable) { return true; }
    }

    private static void thread(MinecraftServer server) {
        if (!server.isOnThread()) throw new IllegalStateException("B 收尾票必須在 server thread 操作");
    }

    public static final class Lease implements AutoCloseable {
        private final HomeEntryTickets owner;
        private final ServerWorld world;
        private final UUID sid;
        private final EntryEndpoint entry;
        private final Purpose purpose;
        private final CorridorPageManager pages;
        private final Set<ChunkPos> chunks = new LinkedHashSet<>();
        private final Backend backend;
        private final long deadline;
        private boolean closing;
        private boolean warned;

        private Lease(HomeEntryTickets owner, ServerWorld world, UUID sid, EntryEndpoint entry, Purpose purpose, CorridorPageManager pages, long deadline, Backend backend) {
            this.owner = owner; this.world = world; this.sid = sid; this.entry = entry; this.purpose = purpose; this.pages = pages; this.deadline = deadline;
            this.backend = backend;
        }

        public Purpose purpose() { return purpose; }

        public int refs() { return chunks.size(); }

        /** B 的 home chunks 是否都已 FULL（非建立的查詢）；票已不在或清理中時為假。 */
        public boolean full() {
            thread(world.getServer());
            if (owner.leases.get(sid) != this || closing) return false;
            return chunks.stream().allMatch(chunk -> backend.full(world, chunk));
        }

        /** {@code START_SERVER_TICK}：列不再適用就釋放；租期到就釋放並記下（owner 在下一次推進時記 WARN、退避）。 */
        private void refresh() {
            var server = world.getServer();
            thread(server);
            if (owner.leases.get(sid) != this) return;
            if (closing || !stillApplies(server, sid, entry)) { close(); return; }
            if (server.getTicks() >= deadline) {
                close();
                owner.expired.add(sid);
            }
        }

        private void closeForEvent() {
            try { if (owner.leases.get(sid) == this) close(); }
            catch (RuntimeException failure) { warnCleanup(failure); }
        }

        private void warnCleanup(RuntimeException failure) {
            closing = true;
            if (!warned) {
                warned = true;
                LOGGER.warn(CollapseService.LOG_MARKER, "B 收尾票清理失敗，保留未清 refs 下輪續試：SID={} refs={}", sid, chunks, failure);
            }
        }

        @Override public void close() {
            thread(world.getServer());
            if (owner.leases.get(sid) != this) return;
            closing = true;
            RuntimeException failed = null;
            for (var chunk : List.copyOf(chunks)) {
                try {
                    backend.remove(world, chunk, sid);
                    chunks.remove(chunk);
                } catch (RuntimeException ex) {
                    if (failed == null) failed = ex; else failed.addSuppressed(ex);
                }
            }
            if (chunks.isEmpty()) {
                pages.releaseRootCoverage(sid);
                owner.leases.remove(sid, this);
            }
            if (failed != null) throw failed;
        }
    }
}
