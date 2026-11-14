package dev.quantumchamber.collapse;

import dev.quantumchamber.chamber.*;
import dev.quantumchamber.corridor.CorridorPageManager;
import dev.quantumchamber.persistence.*;
import dev.quantumchamber.superposition.*;
import java.util.*;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;

/** 每份票綁定單一 server、SID 與用途，租期採 server ticks。 */
public final class HomeRootTickets {
    public enum Purpose { START_CHECK, SELECT_CHECK, HOME_PREPARE_PASSAGE }
    private static final ChunkTicketType<UUID> TYPE=ChunkTicketType.create("quantumchamber_home_root",Comparator.comparing(UUID::toString));
    private static final Map<MinecraftServer,HomeRootTickets> servers=new IdentityHashMap<>();
    private final Map<UUID,Lease> leases=new HashMap<>();
    private final Map<UUID,Retry> retries=new HashMap<>();
    private record Retry(long after, int delay) {}
    private boolean stopping;
    private static boolean initialized;
    private HomeRootTickets() {}

    public static void initialize() {
        if (initialized) return;
        initialized=true;
        ServerLifecycleEvents.SERVER_STARTED.register(s -> servers.put(s,new HomeRootTickets()));
        ServerTickEvents.START_SERVER_TICK.register(s -> {
            var owner=servers.get(s);
            if(owner!=null) {
                for (var lease:List.copyOf(owner.leases.values())) lease.refresh();
                if (!owner.retries.isEmpty()) owner.retries.keySet().removeIf(sid -> !preparing(s,sid));
            }
        });
        ServerLifecycleEvents.START_DATA_PACK_RELOAD.register((s,resources) -> closeAll(s,false));
        ServerLifecycleEvents.SERVER_STOPPING.register(s -> closeAll(s,true));
        ServerLifecycleEvents.SERVER_STOPPED.register(servers::remove);
    }

    interface Backend {
        void add(ServerWorld world,ChunkPos pos,UUID sid);
        void remove(ServerWorld world,ChunkPos pos,UUID sid);
    }
    static final Backend NATIVE=new Backend() {
        public void add(ServerWorld world,ChunkPos pos,UUID sid) { world.getChunkManager().addTicket(TYPE,pos,2,sid); }
        public void remove(ServerWorld world,ChunkPos pos,UUID sid) { world.getChunkManager().removeTicket(TYPE,pos,2,sid); }
    };

    public static Optional<Lease> acquire(MinecraftServer server,UUID sid,ChamberOriginAuthority root,Purpose purpose) {
        return acquire(server,sid,root,purpose,NATIVE);
    }
    static Optional<Lease> acquire(MinecraftServer server,UUID sid,ChamberOriginAuthority root,Purpose purpose, Backend backend) {
        thread(server);
        Objects.requireNonNull(sid); Objects.requireNonNull(purpose);
        var owner=servers.get(server);
        if (owner==null || owner.stopping || owner.leases.containsKey(sid) || !HomeEndpointAuthority.canonical(root)
                || !HomeEndpointAuthority.registeredRoot(server,root)) return Optional.empty();
        if (!authorized(server,sid,root,purpose)) return Optional.empty();
        long now=server.getTicks();
        var retry=owner.retries.get(sid);
        if (purpose==Purpose.HOME_PREPARE_PASSAGE && retry!=null && now<retry.after()) return Optional.empty();
        var world=server.getOverworld();
        if(world==null) return Optional.empty();
        var chunks=PassageTransferPolicy.homeChunks(new ChamberFrame(root.controllerPos(),root.facing()));
        var pages=CorridorPageManager.forServer(server);
        if (!pages.reserveRootCoverage(sid,chunks.stream().map(ChunkPos::toLong).collect(Collectors.toSet()))) return Optional.empty();
        var lease=new Lease(owner,world,sid,root,purpose,pages,now+200,backend);
        owner.leases.put(sid,lease);
        try {
            for (var chunk:chunks) {
                // 先記責任；即使底層已加票才拋錯，移除同 SID 的票仍可回復。
                lease.chunks.add(chunk);
                backend.add(world,chunk,sid);
            }
            return Optional.of(lease);
        } catch (RuntimeException failure) {
            try { lease.close(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private static boolean preparing(MinecraftServer server,UUID sid) {
        try {
            var row=SessionRecoveryState.get(server).allFlushedSessions().get(sid);
            return row instanceof HomeSessionRecord home && (home.state()==SessionState.COLLAPSING || home.state()==SessionState.PASSAGE_READY)
                    && home.home().closeIntent() instanceof HomeSessionRecord.None && home.home().stopReason().isEmpty();
        } catch (RuntimeException unreadable) { return false; }
    }

    private static boolean authorized(MinecraftServer s,UUID sid,ChamberOriginAuthority root,Purpose purpose) {
        if (purpose==Purpose.START_CHECK) return ChamberSessions.gateway() instanceof SuperpositionSessionManager manager
                && manager.ownsProvisional(s,root.chamberUuid(),sid);
        try {
            var journal=SessionRecoveryState.get(s);
            if (!(journal.allFlushedSessions().get(sid) instanceof HomeSessionRecord row) || !row.rootAnchor().equals(root)
                    || !row.equals(journal.allSessions().get(sid)) || journal.quarantinedSessions().contains(sid)
                    || row.home().closeIntent() instanceof HomeSessionRecord.Close || row.home().stopReason().isPresent()) return false;
            return purpose==Purpose.SELECT_CHECK ? row.state()==SessionState.SUPERPOSITION
                    : row.state()==SessionState.COLLAPSING || row.state()==SessionState.PASSAGE_READY
                    && s.getOverworld().getTime()<row.home().passageExpiresAtGameTime();
        } catch (RuntimeException unreadable) { return false; }
    }

    public static void release(MinecraftServer s,UUID sid) {
        thread(s);
        var owner=servers.get(s);
        if(owner==null) return;
        owner.retries.remove(sid);
        var lease=owner.leases.get(sid);
        if (lease!=null) lease.close();
    }
    private static void closeAll(MinecraftServer s,boolean stop) {
        thread(s);
        var owner=servers.get(s);
        if(owner==null) return;
        owner.stopping=stop;
        for (var lease:List.copyOf(owner.leases.values())) {
            if (lease.purpose==Purpose.START_CHECK && ChamberSessions.gateway() instanceof SuperpositionSessionManager manager)
                manager.cancelProvisional(s,lease.root.chamberUuid(),lease.sid);
            else lease.close();
        }
        owner.retries.clear();
    }

    private static void thread(MinecraftServer s) {
        if (!s.isOnThread()) throw new IllegalStateException("原艙票必須在 server thread 操作");
    }

    public static final class Lease implements AutoCloseable {
        private final HomeRootTickets owner;
        private final ServerWorld world;
        private final UUID sid;
        private final ChamberOriginAuthority root;
        private final Purpose purpose;
        private final CorridorPageManager pages;
        private final Set<ChunkPos> chunks=new LinkedHashSet<>();
        private final Backend backend;
        private long deadline;
        private boolean ready;
        private boolean closing;

        private Lease(HomeRootTickets owner,ServerWorld world,UUID sid,ChamberOriginAuthority root,Purpose purpose,
                CorridorPageManager pages,long deadline,Backend backend) {
            this.backend=backend;
            this.owner=owner; this.world=world; this.sid=sid; this.root=root; this.purpose=purpose; this.pages=pages; this.deadline=deadline;
        }

        public boolean full() {
            if (!refresh()) return false;
            return chunks.stream().allMatch(c -> world.getChunkManager().getWorldChunk(c.x,c.z)!=null);
        }

        private boolean refresh() {
            var server=world.getServer();
            thread(server);
            if (owner.leases.get(sid)!=this) return false;
            if (closing || !authorized(server,sid,root,purpose)) { close(); return false; }
            if (purpose==Purpose.HOME_PREPARE_PASSAGE && !ready) {
                var row=(HomeSessionRecord)SessionRecoveryState.get(server).allFlushedSessions().get(sid);
                if (row.state()==SessionState.PASSAGE_READY && server.getTicks()<deadline
                        && chunks.stream().allMatch(c -> world.getChunkManager().getWorldChunk(c.x,c.z)!=null)) {
                    ready=true;
                    deadline=server.getTicks()+Math.max(0, Math.min(1200,row.home().passageExpiresAtGameTime()-world.getTime()));
                    owner.retries.remove(sid);
                }
            }
            if (server.getTicks()<deadline) return true;
            if (purpose==Purpose.START_CHECK && ChamberSessions.gateway() instanceof SuperpositionSessionManager manager)
                manager.cancelProvisional(server,root.chamberUuid(),sid);
            else close();
            if (purpose==Purpose.HOME_PREPARE_PASSAGE && !ready) {
                var old=owner.retries.get(sid);
                int delay=old==null ? 200 : Math.min(12000,old.delay()*2);
                owner.retries.put(sid,new Retry((long)server.getTicks()+delay,delay));
            }
            return false;
        }
        @Override public void close() {
            thread(world.getServer());
            if (owner.leases.get(sid)!=this) return;
            closing=true;
            RuntimeException failed=null;
            for (var chunk:List.copyOf(chunks)) {
                try {
                    backend.remove(world,chunk,sid);
                    chunks.remove(chunk);
                } catch (RuntimeException ex) {
                    if (failed==null) failed=ex; else failed.addSuppressed(ex);
                }
            }
            if (chunks.isEmpty()) {
                pages.releaseRootCoverage(sid);
                owner.leases.remove(sid,this);
            }
            if (failed!=null) throw failed;
        }
    }
}
